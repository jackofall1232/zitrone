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
 * D2c OUTBOUND durable barrier. [flushSendRatchet] is the exact decision every send path
 * (deliverText / deliverAttachment / sendReadReceipt) runs between signal.encrypt (which advanced
 * the SENDING ratchet) and its NON-SUSPENDING `contactExists → ws.sendMessage` tail: reseal the
 * ratchet advance durable, and report whether it confirmed so the caller sends ONLY when it did —
 * so a crash between hand-off and the coalesced background reseal can never roll the sending ratchet
 * back and re-encrypt a later message at the SAME chain index (key/nonce reuse). Symmetric to the
 * inbound [flushThenAck]; both ratchet directions are durable-before-handoff.
 *
 * Round 6 SPLIT the flush OUT of the send (was `flushThenSend(flush, send)`) so the SUSPENDING flush
 * runs BEFORE the check→send tail, never between the check and the send — which would let a queued
 * deleteContact interleave on the confined worker and publish to a just-deleted contact. The last
 * test models the restructured call site: flush → (non-suspending) check → send, with no suspension
 * in the check→send window.
 *
 * Driven directly for the same reason [FlushBeforeAckBarrierTest] drives the inbound barrier — the
 * full coordinator is not host-drivable (WsClient / SignalProtocolManager are final, the transport
 * is socket-bound) — so the ordering + fail-closed decision is proven here and the barrier's
 * placement at each send site is a source invariant.
 */
class OutboundFlushBarrierTest {

    @Test
    fun `a durable flush returns true so the caller proceeds to the send tail`() = runTest {
        var notDurable = false

        val durable = flushSendRatchet(
            flush = { /* confirmed durable */ },
            onNotDurable = { notDurable = true },
        )

        assertTrue("a durable flush lets the caller run its send tail", durable)
        assertFalse("no not-durable diagnostic on the happy path", notDurable)
    }

    @Test
    fun `a throwing flush returns false so the caller must NOT send`() = runTest {
        var notDurable = false

        // A NotDurable / IO / closed / at-capacity flush surfaces as a throw — the sending ratchet
        // advance did NOT reach disk, so the caller must never reach its send tail: a resend
        // re-encrypts cleanly and no recipient ever saw a same-index ciphertext.
        val durable = flushSendRatchet(
            flush = { throw IOException("reseal not durable") },
            onNotDurable = { notDurable = true },
        )

        assertFalse("a non-durable flush tells the caller not to send", durable)
        assertTrue("the barrier diag'd the un-sent drop", notDurable)
    }

    @Test
    fun `a CancellationException from flush propagates and is not folded into false`() {
        var notDurableSeen = false

        // Cooperative cancellation must unwind, NOT be folded into a not-durable false.
        assertThrows(CancellationException::class.java) {
            runBlocking {
                flushSendRatchet(
                    flush = { throw CancellationException("scope torn down") },
                    onNotDurable = { notDurableSeen = true },
                )
            }
        }
        assertFalse("cancellation is not folded into the not-durable path", notDurableSeen)
    }

    @Test
    fun `a transient flush blip is retried and then returns true once durable`() = runTest {
        val flushCalls = mutableListOf<Int>()
        var attempt = 0

        val durable = flushSendRatchet(
            flush = {
                attempt++
                flushCalls += attempt
                if (attempt < 2) throw VaultImageException.NotDurable()
            },
            onNotDurable = { },
            backoff = { /* no real wait under test */ },
        )

        assertTrue("a transient blip that clears resolves to durable in-line", durable)
        assertEquals("it retried exactly once before succeeding", listOf(1, 2), flushCalls)
    }

    @Test
    fun `a persistent transient failure returns false and retries are bounded`() = runTest {
        val flushCalls = mutableListOf<Int>()
        var notDurable = false
        var attempt = 0

        val durable = flushSendRatchet(
            flush = { attempt++; flushCalls += attempt; throw IOException("disk still down") },
            onNotDurable = { notDurable = true },
            backoff = { },
        )

        assertFalse("a never-clearing blip fails closed (caller does not send — the user retries)", durable)
        assertTrue("the not-durable diagnostic fired", notDurable)
        assertEquals(
            "retries are BOUNDED to FLUSH_MAX_ATTEMPTS — never an infinite loop",
            FLUSH_MAX_ATTEMPTS, flushCalls.size,
        )
    }

    @Test
    fun `a full-vault flush is NOT retried and returns false`() = runTest {
        var flushCalls = 0

        val durable = flushSendRatchet(
            flush = { flushCalls++; throw VaultCapacityException("vault full") },
            onNotDurable = { },
            backoff = { },
        )

        assertFalse("capacity is fail-closed, not sent", durable)
        assertEquals("capacity is non-transient: no retry", 1, flushCalls)
    }

    @Test
    fun `a closed-runtime flush is NOT retried and returns false`() = runTest {
        var flushCalls = 0

        val durable = flushSendRatchet(
            flush = { flushCalls++; throw IllegalStateException("vault runtime closed") },
            onNotDurable = { },
            backoff = { },
        )

        assertFalse(durable)
        assertEquals("a closed runtime is non-transient: no retry", 1, flushCalls)
    }

    @Test
    fun `the send tail runs only after a durable flush and has no suspension in check to send`() = runTest {
        // Models the restructured send site: flushSendRatchet (SUSPENDING) fully completes, THEN a
        // non-suspending contactExists→send tail runs. The whole point of round 6: the durable flush
        // is OUTSIDE the check→send window, so a deleteContact can only interleave BEFORE the check
        // (which then drops) — never between the check and the send.
        val order = mutableListOf<String>()

        val durable = flushSendRatchet(
            flush = { order += "flush" },
            onNotDurable = { order += "notDurable" },
        )
        assertTrue("flush confirmed durable", durable)

        // Non-suspending tail (no suspend calls between the check and the send).
        val contactExists = true
        if (contactExists) {
            order += "check"
            order += "send"
        }

        assertEquals("flush strictly precedes the non-suspending check→send tail", listOf("flush", "check", "send"), order)
    }
}
