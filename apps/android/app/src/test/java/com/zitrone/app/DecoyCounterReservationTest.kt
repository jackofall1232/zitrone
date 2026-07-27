// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.openPayload
import com.zitrone.app.data.DecoyAuthStore
import com.zitrone.app.decoy.DecoyCounterReservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [DecoyCounterReservation] — reserve-then-spend, and the invariant that makes it safe.
 *
 * `counterHighWater` means "every value strictly below this may already have been issued". The
 * DURABLE write precedes the first spend of the block it covers, so an interruption SKIPS counter
 * values (invisible — a real ratchet skips on any dropped message) and can never REGRESS one
 * (a tell no real ratchet can produce).
 *
 * **Durable, not scheduled.** `VaultRuntime.mutate` only marks the session dirty; the bytes reach
 * disk when the coalescing ceiling fires or `flushBeforeAck` forces them. Every durability
 * assertion here therefore reads the SEALED PAYLOAD THE PERSIST SINK WAS HANDED — decoded with the
 * vault key, through the real AEAD + DEFLATE + TLV path — rather than the live `VaultState`, which
 * would report a value that a crash inside the ≤2 s window would erase. The suite is run with a
 * 60 s cooldown precisely so nothing is written unless the reservation forces it: a mark that shows
 * up on disk here was flushed on purpose.
 */
class DecoyCounterReservationTest {

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    /** A live vault whose durable writes are observable. */
    private inner class Vault(
        state: VaultState = VaultState.empty(),
        private val onPersist: (ByteArray) -> Unit = {},
    ) {
        /** Our own copy — [VaultSession] wipes the key it is constructed with. */
        val vaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 }

        /** The last sealed region the sink was handed, or null if nothing was ever persisted. */
        var lastSealed: ByteArray? = null
            private set

        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = vaultKey.copyOf(),
            slotIndex = 0,
            persist = { _, sealed ->
                onPersist(sealed)
                lastSealed = sealed.copyOf()
            },
            // Long enough that the background ceiling never fires during a test: anything that
            // reaches the sink got there through a deliberate synchronous flush.
            cooldownMs = 60_000L,
            flushContext = Dispatchers.IO,
        )

        val runtime = VaultRuntime(session, state)

        /** The high-water mark ON DISK — null when nothing has been persisted at all. */
        fun durableHighWater(): Long? {
            val sealed = lastSealed ?: return null
            val plaintext = requireNotNull(openPayload(vaultKey, sealed, ops)) { "sealed payload did not open" }
            return VaultStateCodec.decode(plaintext).decoy?.counterHighWater ?: 0L
        }

        /** The live (possibly unflushed) mark — never used as a durability assertion. */
        fun liveHighWater(): Long = runtime.read { it.decoy?.counterHighWater ?: 0L }
    }

    @Test
    fun `the first value is issued only AFTER a reservation is DURABLE`() {
        val vault = Vault()
        assertNull("nothing persisted before the first call", vault.durableHighWater())

        val first = DecoyCounterReservation.forRuntime(vault.runtime).next()

        assertEquals("counters start at zero", 0L, first)
        assertEquals(
            "the whole block was on DISK before the first value was spent",
            DecoyCounterReservation.DEFAULT_BLOCK_SIZE.toLong(),
            vault.durableHighWater(),
        )
    }

    @Test
    fun `a reservation whose durable write FAILS issues nothing`() {
        // The defect this pins: `mutate` returning successfully means SCHEDULED, not durable. With
        // a sink that refuses the write, the mark never reaches disk — so no value from that block
        // may be handed out, or a restart would reissue it. A reservation that only mutated would
        // return 0 here and be wrong.
        var failWrites = true
        val vault = Vault(onPersist = { if (failWrites) throw IOException("disk full") })
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime)

        assertThrows(IOException::class.java) { reservation.next() }
        assertNull("nothing reached disk", vault.durableHighWater())

        // And the cursor was not advanced: once the disk recovers, the next call reserves properly
        // rather than spending values whose reservation was never durable.
        failWrites = false
        val issued = reservation.next()
        assertNotNull("now it is durable", vault.durableHighWater())
        assertTrue(
            "the issued value ${issued} is covered by the durable mark ${vault.durableHighWater()}",
            issued < vault.durableHighWater()!!,
        )
    }

    @Test
    fun `one durable write per block, and values are strictly increasing`() {
        val vault = Vault()
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime)

        var previous = -1L
        val marks = mutableListOf<Long>()
        repeat(DecoyCounterReservation.DEFAULT_BLOCK_SIZE * 3) {
            val value = reservation.next()
            assertTrue("counters strictly increase ($previous -> $value)", value > previous)
            previous = value
            marks += vault.durableHighWater()!!
        }

        assertEquals("last value of the third block", (3L * DecoyCounterReservation.DEFAULT_BLOCK_SIZE) - 1, previous)
        assertEquals(
            "exactly three distinct durable marks — one write per 64 values",
            listOf(64L, 128L, 192L),
            marks.distinct(),
        )
    }

    @Test
    fun `a restart SKIPS the unspent remainder and never reuses a value`() {
        // Session 1 spends two values out of a block of 64 and is torn down.
        val first = Vault()
        val issued = mutableListOf<Long>()
        val allocator = DecoyCounterReservation.forRuntime(first.runtime)
        issued += allocator.next()
        issued += allocator.next()

        // Session 2 opens what is ACTUALLY ON DISK — the sealed region the sink was handed, opened
        // with the vault key and decoded through the real codec. Rebuilding the state in RAM from
        // the live mark would assume the very durability this test exists to check.
        val persistedPlaintext = requireNotNull(openPayload(first.vaultKey, first.lastSealed!!, ops))
        val persistedState = VaultStateCodec.decode(persistedPlaintext)
        val persistedMark = requireNotNull(persistedState.decoy).counterHighWater
        assertEquals("the whole block was persisted, not just what was spent", 64L, persistedMark)

        val second = Vault(persistedState)
        val afterRestart = DecoyCounterReservation.forRuntime(second.runtime).next()

        assertEquals("resumes at the persisted mark, skipping the unspent 62", persistedMark, afterRestart)
        assertTrue("no value is ever reissued", issued.none { it == afterRestart })
        assertTrue("and it never regresses", afterRestart > issued.max())
    }

    @Test
    fun `a reservation that cannot be persisted issues NOTHING`() {
        // A vault filled to within a few bytes of the fixed region: the reservation's mutate
        // overflows and throws, so no counter may be handed out — issuing one whose reservation
        // never reached the state is the single failure that could later look like a regression.
        val vault = Vault(VaultCapacityFixture(ops).stateFilledToCap())
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime)

        assertThrows(VaultCapacityException::class.java) { reservation.next() }
        // The throw is the contract: the caller must not send. And the cursor is untouched, so a
        // later call (once capacity frees) reserves properly rather than spending phantom values.
        assertThrows(VaultCapacityException::class.java) { reservation.next() }
        assertNull("and nothing was written", vault.durableHighWater())
    }

    @Test
    fun `a closed runtime refuses to issue`() {
        val vault = Vault()
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime)
        reservation.next()
        vault.runtime.close()

        assertThrows(IllegalStateException::class.java) { reservation.next() }
    }

    // ── one allocator per runtime ─────────────────────────────────────────────────

    @Test
    fun `two callers over one runtime get the SAME allocator`() {
        // Structural, not documentary: two allocators over one runtime would each hold their own
        // RAM block and interleave 0, 64, 1 — a regression on the wire. The factory makes that
        // unrepresentable.
        val vault = Vault()
        val a = DecoyCounterReservation.forRuntime(vault.runtime)
        val b = DecoyCounterReservation.forRuntime(vault.runtime)
        assertSame("one allocator per runtime", a, b)

        // Discriminator: a DIFFERENT runtime must get a different allocator, or the assertion above
        // would also pass for a process-wide singleton (which would share one cursor across vaults).
        val other = Vault()
        assertTrue(
            "a different runtime gets its own allocator",
            DecoyCounterReservation.forRuntime(other.runtime) !== a,
        )
    }

    @Test
    fun `interleaved use never regresses`() {
        // The wire property, asserted end to end: whatever two holders do, the counters an observer
        // sees never go backwards. Stated precisely about what it discriminates — it passes under
        // EITHER defence on its own, and that was checked by mutation, not assumed: with the
        // shared-instance factory disabled it still passes, because the staleness check makes the
        // older holder abandon its block instead of spending 1 after the other issued 64. Defence 1
        // is pinned by the assertSame above; this pins the observable consequence of both.
        val vault = Vault()
        val a = DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4)
        val b = DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4)

        val issued = listOf(a.next(), b.next(), a.next(), b.next(), a.next(), b.next())

        assertEquals("strictly increasing, no regression", issued.sorted(), issued)
        assertEquals("and no repeats", issued.size, issued.toSet().size)
    }

    @Test
    fun `a block whose durable mark moved underneath it is abandoned, not spent`() {
        // Defence in depth for any FUTURE writer of counterHighWater: clearAccount resets the mark
        // to 0 for a re-provisioned account. A live allocator still holding [0,4) must NOT keep
        // spending 1, 2, 3 against a mark that no longer covers them — it must reserve again.
        val vault = Vault(
            VaultState.empty().also {
                it.decoy = DecoyState(accountId = "acct", identityKeyPair = ByteArray(65) { b -> b.toByte() })
            },
        )
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4)
        assertEquals(0L, reservation.next())
        assertEquals("the block is live", 4L, vault.liveHighWater())

        DecoyAuthStore(vault.runtime).clearAccount()
        assertEquals("a cleared account resets the mark", 0L, vault.liveHighWater())

        assertEquals("the stale block is abandoned and a fresh one reserved", 0L, reservation.next())
        assertEquals(4L, vault.durableHighWater())
    }

    @Test
    fun `clearAccount cannot land BETWEEN the staleness check and the spend`() {
        // The staleness check reads the durable mark in one runtime call and spends against it in
        // the next. Round 1 gave this class a PRIVATE lock, so `clearAccount()` — which resets the
        // mark — could land between the two: the allocator then issues from a block the reset mark
        // no longer covers, and its next call detects the staleness and reserves from 0, so the
        // replacement account emits `1, 0`. A cleartext counter regression, and the exact tell no
        // real ratchet produces. A check that is not atomic with the spend is not a check.
        //
        // What that makes observable from here is one thing: with the allocator and the auth store
        // sharing the SECTION lock, `clearAccount()` CANNOT complete while a reservation is in
        // flight. The reservation's own durable flush is the pause point — it happens with the
        // section lock held, exactly where the round-1 code held nothing the clearer respected.
        var armed = true
        val reservationInFlight = CountDownLatch(1)
        val clearCompleted = CountDownLatch(1)
        var clearedMidReservation = false

        val vault = Vault(
            state = VaultState.empty().also {
                it.decoy = DecoyState(accountId = "acct", identityKeyPair = ByteArray(65) { b -> b.toByte() })
            },
            onPersist = {
                if (armed) {
                    armed = false
                    reservationInFlight.countDown()
                    // Generous, and one-directional: too SHORT a window can only ever let a broken
                    // implementation slip through, never fail a correct one.
                    clearedMidReservation = clearCompleted.await(2, TimeUnit.SECONDS)
                }
            },
        )
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4)
        val clearer = Thread {
            reservationInFlight.await()
            DecoyAuthStore(vault.runtime).clearAccount()
            clearCompleted.countDown()
        }

        clearer.start()
        val duringOldAccount = reservation.next()
        clearer.join(30_000)
        // `join(t).let { true }` was the assertion here, which is unconditionally true — including
        // when the thread is still running. join() returns Unit, so the only way to ask whether it
        // finished is to ask the thread.
        assertFalse("the clearer finished", clearer.isAlive)

        assertTrue(
            "clearAccount reset the counter mark while a value was being issued against it",
            !clearedMidReservation,
        )
        assertEquals("the value issued belonged to the old account's block", 0L, duringOldAccount)
        // And the new epoch starts where a real ratchet with a new recipient starts.
        assertEquals("the replacement account starts at zero", 0L, reservation.next())
        assertEquals(4L, vault.durableHighWater())
    }

    @Test
    fun `concurrent callers never receive the same value`() {
        // The send path is reachable from pooled dispatcher threads; a duplicated message_number
        // would be exactly the tell the reservation exists to prevent.
        val vault = Vault()
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime)
        val threadCount = 8
        val perThread = 50
        val issued = ConcurrentLinkedQueue<Long>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                start.await()
                repeat(perThread) { issued += reservation.next() }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("workers finished", done.await(30, TimeUnit.SECONDS))

        val all = issued.toList()
        assertEquals("every issued value is unique", all.size, all.toSet().size)
        assertEquals("and they form a contiguous run from zero", (0L until all.size.toLong()).toSet(), all.toSet())
        assertTrue(
            "the DURABLE mark covers everything issued",
            vault.durableHighWater()!! >= all.max() + 1,
        )
    }

    @Test
    fun `a custom block size is honoured`() {
        val vault = Vault()
        val reservation = DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4)
        repeat(4) { reservation.next() }
        assertEquals(4L, vault.durableHighWater())
        reservation.next()
        assertEquals("a fifth value forces the next reservation", 8L, vault.durableHighWater())
    }

    @Test
    fun `a non-positive block size is rejected`() {
        val vault = Vault()
        assertThrows(IllegalArgumentException::class.java) {
            DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 0)
        }
    }

    @Test
    fun `a second caller asking for a different block size fails closed`() {
        // Two components disagreeing about the reservation size is a caller bug; silently handing
        // back the other one's allocator would hide it.
        val vault = Vault()
        DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 8)
        assertThrows(IllegalStateException::class.java) {
            DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 16)
        }
    }
}
