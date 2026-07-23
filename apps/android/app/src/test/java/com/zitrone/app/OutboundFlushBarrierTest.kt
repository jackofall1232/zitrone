// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultImageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * D2c round 2 OUTBOUND durable barrier. [flushThenSend] is the exact decision every send path
 * (deliverText / deliverAttachment / sendReadReceipt) runs between signal.encrypt (which advanced
 * the SENDING ratchet) and ws.sendMessage: reseal the ratchet advance durable BEFORE handing the
 * ciphertext to the relay, and on a non-durable flush DO NOT send (mark failed / queue for retry) —
 * so a crash between hand-off and the coalesced background reseal can never roll the sending ratchet
 * back and re-encrypt a later message at the SAME chain index (key/nonce reuse). Symmetric to the
 * inbound [flushThenAck]; both ratchet directions are now durable-before-handoff.
 *
 * Driven directly for the same reason [FlushBeforeAckBarrierTest] drives the inbound barrier — the
 * full coordinator is not host-drivable (WsClient / SignalProtocolManager are final, the transport
 * is socket-bound) — so the ordering + fail-closed decision is proven here and the barrier's
 * placement at each send site is a source invariant.
 */
class OutboundFlushBarrierTest {

    @Test
    fun `flush runs before send and the message is sent when the flush is durable`() = runTest {
        val order = mutableListOf<String>()
        var notDurable = false

        val handed = flushThenSend(
            flush = { order += "flush" },
            send = { order += "send"; true },
            onNotDurable = { notDurable = true },
        )

        assertTrue("a durable flush hands the message to the relay", handed)
        assertEquals("flush strictly precedes send", listOf("flush", "send"), order)
        assertFalse("no not-durable diagnostic on the happy path", notDurable)
    }

    @Test
    fun `a throwing flush must NOT send (marked for retry)`() = runTest {
        var sent = false
        var notDurable = false

        // A NotDurable / IO / closed / at-capacity flush surfaces as a throw — the sending ratchet
        // advance did NOT reach disk, so the ciphertext must never leave: a resend re-encrypts
        // cleanly and no recipient ever saw a same-index ciphertext.
        val handed = flushThenSend(
            flush = { throw IOException("reseal not durable") },
            send = { sent = true; true },
            onNotDurable = { notDurable = true },
        )

        assertFalse("a non-durable flush does not hand the message to the relay", handed)
        assertTrue("the barrier diag'd the un-sent drop", notDurable)
        assertFalse("ws.sendMessage was NEVER called", sent)
    }

    @Test
    fun `a durable flush whose socket is down returns false (marked for retry) but DID flush`() = runTest {
        val order = mutableListOf<String>()

        // Flush confirmed durable, but the socket is down (send returns false). The caller treats a
        // false as a non-send (markFailed / queue), and the ratchet advance is durable so a retry
        // advances (benign gap) — never a same-index reuse.
        val handed = flushThenSend(
            flush = { order += "flush" },
            send = { order += "send"; false },
            onNotDurable = { order += "notDurable" },
        )

        assertFalse("a socket-down send reports not-handed", handed)
        assertEquals("the flush STILL ran before the (failed) send; no not-durable diag", listOf("flush", "send"), order)
    }

    @Test
    fun `a CancellationException from flush propagates and does not send`() {
        var sent = false
        var notDurableSeen = false

        // Cooperative cancellation must unwind, NOT be folded into a not-durable false.
        assertThrows(CancellationException::class.java) {
            runBlocking {
                flushThenSend(
                    flush = { throw CancellationException("scope torn down") },
                    send = { sent = true; true },
                    onNotDurable = { notDurableSeen = true },
                )
            }
        }
        assertFalse("cancellation never sends", sent)
        assertFalse("cancellation is not folded into the not-durable path", notDurableSeen)
    }

    @Test
    fun `a transient flush blip is retried and then sends once durable`() = runTest {
        val flushCalls = mutableListOf<Int>()
        var sent = false
        var attempt = 0

        val handed = flushThenSend(
            flush = {
                attempt++
                flushCalls += attempt
                if (attempt < 2) throw VaultImageException.NotDurable()
            },
            send = { sent = true; true },
            onNotDurable = { },
            backoff = { /* no real wait under test */ },
        )

        assertTrue("a transient blip that clears resolves to durable + send in-line", handed)
        assertEquals("it retried exactly once before succeeding", listOf(1, 2), flushCalls)
        assertTrue(sent)
    }

    @Test
    fun `a persistent transient failure does NOT send and retries are bounded`() = runTest {
        val flushCalls = mutableListOf<Int>()
        var sent = false
        var notDurable = false
        var attempt = 0

        val handed = flushThenSend(
            flush = { attempt++; flushCalls += attempt; throw IOException("disk still down") },
            send = { sent = true; true },
            onNotDurable = { notDurable = true },
            backoff = { },
        )

        assertFalse("a never-clearing blip fails closed (not sent — the user retries)", handed)
        assertTrue("the not-durable diagnostic fired", notDurable)
        assertFalse("ws.sendMessage never fired", sent)
        assertEquals(
            "retries are BOUNDED to FLUSH_MAX_ATTEMPTS — never an infinite loop",
            FLUSH_MAX_ATTEMPTS, flushCalls.size,
        )
    }

    @Test
    fun `a full-vault flush is NOT retried and does not send`() = runTest {
        var flushCalls = 0
        var sent = false

        val handed = flushThenSend(
            flush = { flushCalls++; throw VaultCapacityException("vault full") },
            send = { sent = true; true },
            onNotDurable = { },
            backoff = { },
        )

        assertFalse("capacity is fail-closed, not sent", handed)
        assertEquals("capacity is non-transient: no retry", 1, flushCalls)
        assertFalse(sent)
    }

    @Test
    fun `a closed-runtime flush is NOT retried and does not send`() = runTest {
        var flushCalls = 0
        var sent = false

        val handed = flushThenSend(
            flush = { flushCalls++; throw IllegalStateException("vault runtime closed") },
            send = { sent = true; true },
            onNotDurable = { },
            backoff = { },
        )

        assertFalse(handed)
        assertEquals("a closed runtime is non-transient: no retry", 1, flushCalls)
        assertFalse(sent)
    }
}
