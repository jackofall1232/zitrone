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
import com.zitrone.app.decoy.DecoyCounterReservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [DecoyCounterReservation] — reserve-then-spend, and the invariant that makes it safe.
 *
 * `counterHighWater` means "every value strictly below this may already have been issued". The
 * durable write precedes the first spend of the block it covers, so an interruption SKIPS counter
 * values (invisible — a real ratchet skips on any dropped message) and can never REGRESS one
 * (a tell no real ratchet can produce).
 */
class DecoyCounterReservationTest {

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun runtimeOf(state: VaultState = VaultState.empty()): VaultRuntime {
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 },
            slotIndex = 0,
            persist = { _, _ -> },
            cooldownMs = 60_000L,
            flushContext = Dispatchers.IO,
        )
        return VaultRuntime(session, state)
    }

    private fun highWater(runtime: VaultRuntime): Long = runtime.read { it.decoy?.counterHighWater ?: 0L }

    @Test
    fun `the first value is issued only AFTER a reservation is durable`() {
        val runtime = runtimeOf()
        assertEquals("nothing reserved before the first call", 0L, highWater(runtime))

        val reservation = DecoyCounterReservation(runtime)
        val first = reservation.next()

        assertEquals("counters start at zero", 0L, first)
        assertEquals(
            "the whole block was marked issued BEFORE the first value was spent",
            DecoyCounterReservation.DEFAULT_BLOCK_SIZE.toLong(),
            highWater(runtime),
        )
    }

    @Test
    fun `one durable write per block, and values are strictly increasing`() {
        val writes = mutableListOf<Long>()
        val runtime = runtimeOf()
        val reservation = DecoyCounterReservation(runtime)

        var previous = -1L
        repeat(DecoyCounterReservation.DEFAULT_BLOCK_SIZE * 3) {
            val value = reservation.next()
            assertTrue("counters strictly increase ($previous -> $value)", value > previous)
            previous = value
            writes += highWater(runtime)
        }

        assertEquals("last value of the third block", (3L * DecoyCounterReservation.DEFAULT_BLOCK_SIZE) - 1, previous)
        assertEquals(
            "exactly three distinct high-water marks — one durable write per 64 values",
            listOf(64L, 128L, 192L),
            writes.distinct(),
        )
    }

    @Test
    fun `a restart SKIPS the unspent remainder and never reuses a value`() {
        // Session 1 spends two values out of a block of 64 and is torn down.
        val state = VaultState.empty()
        val runtime = runtimeOf(state)
        val issued = mutableListOf<Long>()
        val first = DecoyCounterReservation(runtime)
        issued += first.next()
        issued += first.next()
        val persisted = highWater(runtime)

        // Session 2 opens the SAME durable state (the reservation object is per-session, the
        // high-water mark is not).
        val reopened = runtimeOf(VaultState.empty().also { it.decoy = DecoyState(counterHighWater = persisted) })
        val second = DecoyCounterReservation(reopened)
        val afterRestart = second.next()

        assertEquals("resumes at the persisted mark, skipping the unspent 62", persisted, afterRestart)
        assertTrue("no value is ever reissued", issued.none { it == afterRestart })
        assertTrue("and it never regresses", afterRestart > issued.max())
    }

    @Test
    fun `a reservation that cannot be persisted issues NOTHING`() {
        // A vault filled to within a few bytes of the fixed region: the reservation's mutate
        // overflows and throws, so no counter may be handed out — issuing one whose reservation
        // never reached the state is the single failure that could later look like a regression.
        val runtime = runtimeOf(VaultCapacityFixture(ops).stateFilledToCap())
        val reservation = DecoyCounterReservation(runtime)

        assertThrows(VaultCapacityException::class.java) { reservation.next() }
        // The throw is the contract: the caller must not send. And the cursor is untouched, so a
        // later call (once capacity frees) reserves properly rather than spending phantom values.
        assertThrows(VaultCapacityException::class.java) { reservation.next() }
    }

    @Test
    fun `a closed runtime refuses to issue`() {
        val runtime = runtimeOf()
        val reservation = DecoyCounterReservation(runtime)
        reservation.next()
        runtime.close()

        assertThrows(IllegalStateException::class.java) { reservation.next() }
    }

    @Test
    fun `concurrent callers never receive the same value`() {
        // The send path is reachable from pooled dispatcher threads; a duplicated message_number
        // would be exactly the tell the reservation exists to prevent.
        val runtime = runtimeOf()
        val reservation = DecoyCounterReservation(runtime)
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
            "the durable mark covers everything issued",
            highWater(runtime) >= all.max() + 1,
        )
    }

    @Test
    fun `a custom block size is honoured`() {
        val runtime = runtimeOf()
        val reservation = DecoyCounterReservation(runtime, blockSize = 4)
        repeat(4) { reservation.next() }
        assertEquals(4L, highWater(runtime))
        reservation.next()
        assertEquals("a fifth value forces the next reservation", 8L, highWater(runtime))
    }

    @Test
    fun `a non-positive block size is rejected at construction`() {
        val runtime = runtimeOf()
        assertThrows(IllegalArgumentException::class.java) { DecoyCounterReservation(runtime, blockSize = 0) }
    }
}
