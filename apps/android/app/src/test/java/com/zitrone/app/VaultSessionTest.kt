// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.createImage
import com.zitrone.app.crypto.vault.unlockImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext

/**
 * VaultSession flush-policy tests. Virtual time throughout (the 2s coalescing
 * ceiling elapses instantly), a fake persist sink that records each resealed
 * image and the VIRTUAL time it fired at, and the REAL AEAD byte path
 * ([LibsodiumVaultOps] over SodiumJava) so a reseal actually re-encrypts and the
 * round-trip proves the slot still opens. Only the CPU-heavy Argon2id KDF is
 * swapped for a fast deterministic stand-in ([fast]) so setup is not slow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionTest {

    private val ops = LibsodiumVaultOps(SodiumJava())

    /** Fast, deterministic stand-in for Argon2id: SHA-256(passphrase ‖ salt). */
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    private val passphrase = "correct horse battery staple"

    /** Fake persist sink: captures each resealed image and its virtual fire time. */
    private class FakeSink(private val clock: () -> Long) {
        val images = mutableListOf<ByteArray>()
        val fireTimes = mutableListOf<Long>()
        val count: Int get() = images.size
        fun persist(image: ByteArray) {
            images.add(image)
            fireTimes.add(clock())
        }
    }

    /**
     * Build a session bound to a freshly created, then opened, real image.
     * Returns the session, the sink, and the initial (opened) payload plaintext.
     */
    private fun TestScope.newSession(
        scope: CoroutineScope,
        initialContent: ByteArray,
        cooldownMs: Long = 2_000L,
    ): Triple<VaultSession, FakeSink, ByteArray> {
        val image = createImage(passphrase, initialContent, ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)
        assertNotNull("fixture image must open", open)
        val sink = FakeSink(clock = { currentTime })
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialImage = image,
            initialPayload = open!!.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = sink::persist,
            clock = { currentTime },
            cooldownMs = cooldownMs,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )
        // The session takes ownership and wipes open.payloadPlaintext at construction,
        // so snapshot the pre-seal content instead for later equality checks.
        return Triple(session, sink, initialContent.copyOf())
    }

    // A burst of rapid updates coalesces into ONE flush, fired at first-dirty + 2s
    // (NOT last-dirty + 2s), proving a max-wait ceiling rather than a trailing debounce.
    @Test
    fun `coalescing ceiling fires once at first-dirty plus 2s`() = runTest {
        val (session, sink, _) = newSession(backgroundScope, "v0".toByteArray())

        // Five updates at t = 0, 300, 600, 900, 1200.
        session.update("v1".toByteArray()) // t=0 -> arms ceiling at 2000
        advanceTimeBy(300); session.update("v2".toByteArray()) // t=300
        advanceTimeBy(300); session.update("v3".toByteArray()) // t=600
        advanceTimeBy(300); session.update("v4".toByteArray()) // t=900
        advanceTimeBy(300); session.update("v5".toByteArray()) // t=1200
        assertEquals("nothing persisted mid-burst", 0, sink.count)

        // A trailing debounce would fire at 1200 + 2000 = 3200; the ceiling fires at 2000.
        // (advanceTimeBy runs tasks strictly before the new clock, so crossing a t=2000
        // deadline takes a step PAST it — the resumed continuation still runs AT t=2000.)
        advanceTimeBy(799) // t=1999
        assertEquals("must not fire before the first-dirty ceiling", 0, sink.count)
        advanceTimeBy(2) // t=2001, crossing the 2000 deadline
        assertEquals("exactly one coalesced flush", 1, sink.count)
        assertEquals("fires at firstDirtyAt(0) + cooldown(2000)", 2000L, sink.fireTimes[0])

        // A fresh mutation after the flush starts a NEW ceiling from its own first-dirty.
        advanceTimeBy(499) // t=2500
        val t6 = currentTime
        session.update("v6".toByteArray())
        advanceTimeBy(1999) // just short of t6 + 2000
        assertEquals("second ceiling not yet reached", 1, sink.count)
        advanceTimeBy(2) // cross t6 + 2000
        assertEquals("second coalesced flush", 2, sink.count)
        assertEquals("second ceiling = firstDirtyAt(2500) + 2000", t6 + 2000L, sink.fireTimes[1])
    }

    // flushNow reseals synchronously and cancels the pending ceiling, so no second flush lands.
    @Test
    fun `flushNow persists synchronously and cancels the pending ceiling`() = runTest {
        val (session, sink, _) = newSession(backgroundScope, "v0".toByteArray())

        advanceTimeBy(500) // t=500
        session.update("acked".toByteArray()) // arms ceiling at 2500
        assertEquals("update alone does not persist", 0, sink.count)

        session.flushNow()
        assertEquals("flushNow persists synchronously, in-line", 1, sink.count)
        assertEquals("persisted at the call time, no wait", 500L, sink.fireTimes[0])

        advanceTimeBy(5_000) // well past the cancelled 2500 ceiling
        assertEquals("cancelled ceiling produces no second persist", 1, sink.count)
    }

    // The resealed image round-trips: unlocking the persisted bytes yields the UPDATED payload.
    @Test
    fun `resealed image opens to the updated payload`() = runTest {
        val (session, sink, initial) = newSession(backgroundScope, "genesis".toByteArray())
        val updated = "ratchet-state-after-receive".toByteArray()

        session.update(updated)
        session.flushNow()
        assertEquals(1, sink.count)

        val reopened = unlockImage(passphrase, sink.images.last(), ops, fast)
        assertNotNull("resealed slot must still open", reopened)
        assertArrayEquals("opens to the updated payload", updated, reopened!!.payloadPlaintext)
        // Sanity: it is genuinely the new content, not the old.
        assertTrue(!reopened.payloadPlaintext.contentEquals(initial))
    }

    // Construction takes ownership by wiping the caller's key + payload originals
    // (the session holds private copies), so a discarded VaultOpen leaves no live
    // secret. close() then flushes a dirty payload and turns update() into a no-op.
    @Test
    fun `construction wipes caller secrets and close flushes then rejects updates`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val callerKey = open.vaultKey            // the very arrays handed to the constructor
        val callerPayload = open.payloadPlaintext
        val sink = FakeSink(clock = { currentTime })
        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = sink::persist,
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )

        // Ownership taken at construction: the caller's originals are already wiped,
        // so the discarded VaultOpen holds no live key material or plaintext.
        assertTrue("caller vault key wiped once the session takes ownership", callerKey.all { it == 0.toByte() })
        assertTrue("caller payload wiped once the session takes ownership", callerPayload.all { it == 0.toByte() })

        session.update("dirty".toByteArray())
        session.close()
        assertEquals("close flushes the dirty payload", 1, sink.count)

        // Further update is a no-op: no new persist even after the old ceiling would elapse.
        session.update("after-close".toByteArray())
        advanceTimeBy(5_000)
        assertEquals("update after close is a no-op", 1, sink.count)
    }

    // An over-capacity update throws BEFORE mutating: state stays clean and unchanged.
    @Test
    fun `over-capacity update throws before changing state`() = runTest {
        val (session, sink, initial) = newSession(backgroundScope, "small".toByteArray())

        // One byte past the largest content the fixed region can hold.
        val oversize = ByteArray(PAYLOAD_PLAINTEXT_BYTES)
        assertThrows(IllegalArgumentException::class.java) { session.update(oversize) }

        // Not dirtied: nothing flushes even past a full ceiling.
        advanceTimeBy(5_000)
        assertEquals("rejected update must not persist", 0, sink.count)
        assertArrayEquals("payload unchanged after rejected update", initial, session.read())
    }

    // The exact upper boundary: content of the largest accepted size (capacity
    // minus the 4-byte length prefix) is accepted, flushes, and round-trips.
    @Test
    fun `update at max content capacity is accepted and round-trips`() = runTest {
        val (session, sink, _) = newSession(backgroundScope, "small".toByteArray())
        val maxContent = ByteArray(PAYLOAD_PLAINTEXT_BYTES - 4) { (it and 0x7f).toByte() }

        session.update(maxContent) // must NOT throw
        session.flushNow()
        assertEquals(1, sink.count)
        // One byte more is rejected — pins the boundary from both sides.
        assertThrows(IllegalArgumentException::class.java) {
            session.update(ByteArray(PAYLOAD_PLAINTEXT_BYTES - 3))
        }
        assertArrayEquals("max-size content round-trips through the reseal", maxContent, session.read())
    }

    // read() hands out a copy, so mutating the result cannot corrupt session state.
    @Test
    fun `read returns a defensive copy`() = runTest {
        val (session, _, initial) = newSession(backgroundScope, "state".toByteArray())

        val a = session.read()
        assertArrayEquals(initial, a)
        a.fill(0xFF.toByte()) // scribble on the returned buffer

        val b = session.read()
        assertArrayEquals("session state is unaffected by mutating a read() result", initial, b)
    }

    // Durability contract (flush-before-ack): a persist that throws must leave the
    // session DIRTY and propagate the exception, so a flush-before-ack caller never
    // acks an unpersisted message and a retry actually re-writes.
    @Test
    fun `failed persist keeps the session dirty and a retry re-persists`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        var failNext = true
        val persisted = mutableListOf<ByteArray>()
        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                if (failNext) { failNext = false; throw java.io.IOException("disk full") }
                persisted.add(img)
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )

        val updated = "ratchet-after-receive".toByteArray()
        session.update(updated)
        // First flush hits the failing sink: it must propagate (caller would NOT ack).
        assertThrows(java.io.IOException::class.java) { session.flushNow() }
        assertEquals("a failed persist reaches the sink but is not counted as durable", 0, persisted.size)
        // The session stayed dirty, so a retry genuinely re-writes (not a clean no-op).
        session.flushNow()
        assertEquals("retry after a failed persist re-writes", 1, persisted.size)
        val reopened = unlockImage(passphrase, persisted.last(), ops, fast)!!
        assertArrayEquals("the retry persisted the updated payload", updated, reopened.payloadPlaintext)
    }

    // Teardown must never leak: even when the final reseal's persist throws, close()
    // still wipes the secrets and marks the session closed (read throws, update no-ops).
    @Test
    fun `close tears down even when the final flush persist fails`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { throw java.io.IOException("disk full on teardown") },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )

        session.update("dirty".toByteArray())
        // The final reseal throws, but teardown must run to completion regardless.
        assertThrows(java.io.IOException::class.java) { session.close() }
        // Closed despite the failure: read() throws and a further update is inert.
        assertThrows(IllegalStateException::class.java) { session.read() }
        session.update("after-close".toByteArray()) // no-op, must not throw
    }

    // persist() runs OUTSIDE the state lock, so a reentrant update() during the
    // write is legal and must NOT be silently cleared by the flush's commit — the
    // version counter detects it and keeps the session dirty until it flushes.
    @Test
    fun `an update reentrantly triggered during persist is not lost`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        lateinit var session: VaultSession
        var reentered = false
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                persisted.add(img.copyOf())
                // Reentrant mutation while the first flush is mid-persist.
                if (!reentered) { reentered = true; session.update("reentrant".toByteArray()) }
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )

        session.update("first".toByteArray())
        session.flushNow() // persists "first"; during that write, reentrantly updates
        assertTrue("reentrant update fired", reentered)
        // The reentrant update must still be pending, not swallowed by the commit.
        session.flushNow() // now persists "reentrant"
        val reopened = unlockImage(passphrase, persisted.last(), ops, fast)!!
        assertArrayEquals(
            "the reentrant update survived and reached disk",
            "reentrant".toByteArray(), reopened.payloadPlaintext,
        )
    }

    // A mutation that lands mid-persist must be re-armed and flushed by the ceiling
    // on its own — without any second forced flush — or it would sit dirty in memory
    // indefinitely. Proves flushNow() re-arms (does not cancel) when left dirty.
    @Test
    fun `a mutation landing mid-persist is flushed by the ceiling without a forced flush`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        lateinit var session: VaultSession
        var reentered = false
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                persisted.add(img.copyOf())
                if (!reentered) { reentered = true; session.update("reentrant".toByteArray()) }
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )

        session.update("first".toByteArray()) // arms the ceiling at t=2000
        session.flushNow()                     // persists "first" at t=0; reentrant update -> stays dirty
        assertTrue("reentrant update fired", reentered)
        assertEquals("only the forced flush so far", 1, persisted.size)

        // No second flushNow: the ceiling alone must fire and persist the reentrant update.
        advanceTimeBy(2_001)
        assertEquals("the ceiling flushed the mid-persist mutation on its own", 2, persisted.size)
        val reopened = unlockImage(passphrase, persisted.last(), ops, fast)!!
        assertArrayEquals("reentrant".toByteArray(), reopened.payloadPlaintext)
    }

    // When a mutation lands mid-flush, the re-armed ceiling must wait a FULL cooldown
    // from now — not fire immediately off a stale first-dirty timestamp (which under a
    // steady stream would hot-loop). The timer flushes at t=2000, the reentrant update
    // lands mid-persist, and its re-armed ceiling must land at 2000+2000, not at 2000.
    @Test
    fun `a mid-flush mutation reschedules a full cooldown ahead, not immediately`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        lateinit var session: VaultSession
        var reentered = false
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                persisted.add(img.copyOf())
                if (!reentered) { reentered = true; session.update("reentrant".toByteArray()) }
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep the timer in virtual time
        )

        session.update("first".toByteArray()) // arms the ceiling at t=2000
        advanceTimeBy(2_001)                   // timer fires at 2000: persists "first", reentrant update lands
        assertTrue("reentrant update fired mid-flush", reentered)
        assertEquals("only the first flush so far", 1, persisted.size)

        // Without the firstDirtyAt reset the re-arm would fire immediately at ~2000.
        // With it, the ceiling is re-anchored to 2000, so the next flush lands at 4000.
        advanceTimeBy(1_998) // t=3999, just short of 2000+2000
        assertEquals("re-armed ceiling did not fire early (no hot-loop)", 1, persisted.size)
        advanceTimeBy(2)     // t=4001, cross the re-anchored ceiling
        assertEquals("re-armed ceiling fires one full cooldown after the mid-flush mutation", 2, persisted.size)
        val reopened = unlockImage(passphrase, persisted.last(), ops, fast)!!
        assertArrayEquals("reentrant".toByteArray(), reopened.payloadPlaintext)
    }

    // A failed BACKGROUND flush must (1) surface the error to onFlushError and (2)
    // drop the ceiling anchor — even when a mutation landed mid-flush and anchored it
    // in the past — so a much-later update opens a FRESH cooldown window instead of
    // firing immediately off the stale anchor (the persistent-error hot-loop).
    @Test
    fun `a failed background flush reports the error and drops the ceiling anchor`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        val flushErrors = mutableListOf<Throwable>()
        lateinit var session: VaultSession
        var failNext = true
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                if (failNext) {
                    failNext = false
                    session.update("mid-flush".toByteArray()) // lands mid-flush, anchors firstDirtyAt in the past
                    throw java.io.IOException("disk full")
                }
                persisted.add(img.copyOf())
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
            onFlushError = { flushErrors.add(it) },
        )

        session.update("a".toByteArray()) // arms the ceiling at t=2000
        advanceTimeBy(2_001)              // timer fires at 2000: reentrant mid-flush update, then persist FAILS
        assertEquals("nothing persisted (the flush failed)", 0, persisted.size)
        assertEquals("background flush failure surfaced to onFlushError", 1, flushErrors.size)
        assertTrue("the reported error is the IOException", flushErrors[0] is java.io.IOException)

        // Long past the stale mid-flush anchor: a fresh update must open a NEW window,
        // not fire immediately (without the on-failure reset this would hot-loop).
        advanceTimeBy(3_000) // t=5001
        session.update("c".toByteArray())
        runCurrent()
        assertEquals("no immediate hot-loop flush; a fresh cooldown window started", 0, persisted.size)
        advanceTimeBy(2_001) // reach the fresh ceiling (~5001 + 2000)
        assertEquals("the fresh cooldown window flushed once", 1, persisted.size)
    }

    // Fail-fast: the constructor rejects malformed arguments (wrong key size, bad slot
    // index, malformed image) up front rather than failing on a later flush. Validation
    // runs before the ownership wipes, so a rejected construction leaves inputs usable.
    @Test
    fun `constructor rejects malformed arguments`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!

        assertThrows("wrong vault key size", IllegalArgumentException::class.java) {
            VaultSession(
                scope = backgroundScope, ops = ops, initialImage = image,
                initialPayload = open.payloadPlaintext, initialVaultKey = ByteArray(16),
                slotIndex = open.slotIndex, persist = {}, clock = { currentTime },
                cooldownMs = 2_000L, flushContext = EmptyCoroutineContext,
            )
        }
        assertThrows("out-of-range slot index", IllegalArgumentException::class.java) {
            VaultSession(
                scope = backgroundScope, ops = ops, initialImage = image,
                initialPayload = open.payloadPlaintext, initialVaultKey = open.vaultKey,
                slotIndex = 99, persist = {}, clock = { currentTime },
                cooldownMs = 2_000L, flushContext = EmptyCoroutineContext,
            )
        }
        assertThrows("malformed image", IllegalArgumentException::class.java) {
            VaultSession(
                scope = backgroundScope, ops = ops, initialImage = ByteArray(10),
                initialPayload = open.payloadPlaintext, initialVaultKey = open.vaultKey,
                slotIndex = open.slotIndex, persist = {}, clock = { currentTime },
                cooldownMs = 2_000L, flushContext = EmptyCoroutineContext,
            )
        }
    }

    // A persist that reentrantly triggers another flush on the same thread must not
    // recurse (the reentrant flushLock would otherwise StackOverflow). The reentrancy
    // guard makes the nested doFlush a no-op, so persist is invoked exactly once.
    @Test
    fun `a persist that reentrantly flushes does not recurse`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        lateinit var session: VaultSession
        var depth = 0
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                persisted.add(img.copyOf())
                // Reentrant flush from inside persist. Bounded so a broken guard fails
                // as an assertion mismatch (persisted.size == 4) rather than a crash.
                if (depth++ < 3) session.flushNow()
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
        )

        session.update("x".toByteArray())
        session.flushNow() // must return without recursing
        assertEquals("reentrant flushNow was a guarded no-op, so persist ran once", 1, persisted.size)
    }

    // An update() that lands in the failure/onFlushError window (while the failing
    // timer job is still active, so it can't arm a new timer) must still be re-armed
    // by the timer's cleanup — otherwise it sits dirty-but-unscheduled indefinitely.
    @Test
    fun `an update in the onFlushError window is re-armed and flushed`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        lateinit var session: VaultSession
        var failNext = true
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                if (failNext) { failNext = false; throw java.io.IOException("disk full") }
                persisted.add(img.copyOf())
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
            // Fires in the failure window while the failing job is still active.
            onFlushError = { session.update("after-failure".toByteArray()) },
        )

        session.update("first".toByteArray()) // arms the ceiling at t=2000
        advanceTimeBy(2_001)                   // timer fires, persist FAILS, onFlushError updates
        assertEquals("the failing flush persisted nothing", 0, persisted.size)

        // The onFlushError-window update must have been re-armed; its ceiling flushes it.
        advanceTimeBy(2_001)
        assertEquals("the onFlushError-window update was re-armed and flushed", 1, persisted.size)
        val reopened = unlockImage(passphrase, persisted.last(), ops, fast)!!
        assertArrayEquals("after-failure".toByteArray(), reopened.payloadPlaintext)
    }

    // close() must stop accepting updates BEFORE its final flush, so a mutation racing
    // in (here a reentrant one from the persist sink) is rejected — not executed and
    // then wiped unflushed. Proved with an OVER-CAPACITY racing update: without the
    // `closing` gate it would run, throw, and propagate out of close().
    @Test
    fun `close rejects an update racing its final flush`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val persisted = mutableListOf<ByteArray>()
        lateinit var session: VaultSession
        var raced = false
        session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = { img ->
                persisted.add(img.copyOf())
                if (!raced) {
                    raced = true
                    // Rejected as a no-op once `closing`; if it ran, this over-capacity
                    // payload would throw IllegalArgumentException out of close().
                    session.update(ByteArray(PAYLOAD_PLAINTEXT_BYTES))
                }
            },
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
        )

        session.update("final".toByteArray())
        session.close() // must NOT throw — the racing over-capacity update is a no-op
        assertTrue("the racing update was attempted", raced)
        assertEquals("close flushed the pre-close state exactly once", 1, persisted.size)
        val reopened = unlockImage(passphrase, persisted.last(), ops, fast)!!
        assertArrayEquals("close persisted the state as of teardown", "final".toByteArray(), reopened.payloadPlaintext)
    }

    // VaultSession is Closeable, so `use {}` flushes + tears down deterministically.
    @Test
    fun `use block flushes then closes`() = runTest {
        val image = createImage(passphrase, "v0".toByteArray(), ops, fast)
        val open = unlockImage(passphrase, image, ops, fast)!!
        val sink = FakeSink(clock = { currentTime })
        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialImage = image,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = sink::persist,
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
        )

        session.use { it.update("in-use".toByteArray()) }
        assertEquals("use{} exit called close(), flushing the dirty payload", 1, sink.count)
        assertThrows(IllegalStateException::class.java) { session.read() }
    }
}
