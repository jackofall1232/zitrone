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
import org.signal.libsignal.protocol.DuplicateMessageException
import java.io.IOException

/**
 * D2c flush-before-ack barrier (absorbs D4). [flushThenAck] is the exact decision the
 * [MessagingCoordinator] runs at every inbound ack site where a decrypt advanced the receiving
 * ratchet (via its private `ackDurable`): reseal durable BEFORE acking, and on a non-durable flush
 * DO NOT ack (relay redelivers → zero acked loss).
 *
 * These drive that real function directly. The full coordinator is NOT host-drivable — WsClient /
 * SignalProtocolManager / ApiClient are final with no observation seam, the transport is socket-
 * bound, and reaching any barrier site needs a real X3DH decrypt — so the ordering + fail-closed
 * decision is proven here, and the barrier's placement at each ack site is a source invariant
 * (see MessagingCoordinator.onMessageDeliver: every post-decrypt branch calls `ackDurable`).
 */
class FlushBeforeAckBarrierTest {

    @Test
    fun `flush runs before ack and the envelope is acked when the flush is durable`() = runTest {
        val order = mutableListOf<String>()
        val acked = mutableListOf<String>()
        var notDurable = false

        val result = flushThenAck(
            envelopeId = "env-1",
            flush = { order += "flush" },
            ack = { order += "ack"; acked += it },
            onNotDurable = { notDurable = true },
        )

        assertTrue("a durable flush acks", result)
        assertEquals("flush strictly precedes ack", listOf("flush", "ack"), order)
        assertEquals(listOf("env-1"), acked)
        assertFalse("no not-durable diagnostic on the happy path", notDurable)
    }

    @Test
    fun `a throwing flush must NOT ack the envelope (relay redelivers)`() = runTest {
        val acked = mutableListOf<String>()
        var notDurable = false

        // A NotDurable / IO / closed / at-capacity flush surfaces as a throw.
        val result = flushThenAck(
            envelopeId = "env-2",
            flush = { throw IOException("reseal not durable") },
            ack = { acked += it },
            onNotDurable = { notDurable = true },
        )

        assertFalse("a non-durable flush does not ack", result)
        assertTrue("the barrier diag'd the un-acked drop", notDurable)
        assertTrue("ackMessage was NEVER called for the envelope", acked.isEmpty())
    }

    @Test
    fun `a CancellationException from flush propagates and does not ack`() {
        val acked = mutableListOf<String>()
        var notDurableSeen = false

        // Cooperative cancellation must unwind, NOT be folded into a not-durable false — so the
        // barrier rethrows it (before the catch-Throwable) and never reaches onNotDurable or ack.
        assertThrows(CancellationException::class.java) {
            runBlocking {
                flushThenAck(
                    envelopeId = "env-3",
                    flush = { throw CancellationException("scope torn down") },
                    ack = { acked += it },
                    onNotDurable = { notDurableSeen = true },
                )
            }
        }
        assertTrue("cancellation never acks", acked.isEmpty())
        assertFalse("cancellation is not folded into the not-durable path", notDurableSeen)
    }

    // -- round 4: duplicate → ack-drop, and bounded transient retry --------------------------------

    @Test
    fun `an already-consumed duplicate is classified ack-and-drop (breaks the redelivery loop)`() {
        // A redelivery of a message whose receiving-ratchet advance is ALREADY durable throws
        // DuplicateMessageException on re-decrypt. The coordinator must ACK it (relay drops its copy)
        // rather than swallow-and-redeliver, which would loop forever and survive restart. This is
        // the universal net that closes the durable-but-unacked loop a transient/capacity flush
        // failure can open via VaultSession's coalesced background reseal.
        assertEquals(
            RecvFailureAction.ACK_AND_DROP,
            classifyRecvFailure(DuplicateMessageException("already consumed")),
        )
    }

    @Test
    fun `classifyRecvFailure keeps its load-bearing ordering for the other arms`() {
        assertEquals(RecvFailureAction.RETHROW, classifyRecvFailure(CancellationException("torn down")))
        assertEquals(
            RecvFailureAction.DIAGNOSE_AT_CAPACITY,
            classifyRecvFailure(VaultCapacityException("vault full")),
        )
        // A VaultCapacityException IS an IllegalStateException, yet must hit the capacity arm above;
        // a PLAIN IllegalStateException (closed runtime) is swallowed → redelivers to a fresh session.
        assertEquals(RecvFailureAction.SWALLOW, classifyRecvFailure(IllegalStateException("closed")))
    }

    @Test
    fun `a transient flush blip is retried and then acks once durable`() = runTest {
        val flushCalls = mutableListOf<Int>()
        val acked = mutableListOf<String>()
        var notDurable = false
        var attempt = 0

        val result = flushThenAck(
            envelopeId = "env-r1",
            flush = {
                attempt++
                flushCalls += attempt
                // NotDurable is a genuinely transient blip; clears on the second attempt.
                if (attempt < 2) throw VaultImageException.NotDurable()
            },
            ack = { acked += it },
            onNotDurable = { notDurable = true },
            backoff = { /* no real wait under test */ },
        )

        assertTrue("a transient blip that clears resolves to durable + ack in-line", result)
        assertEquals("it retried exactly once before succeeding", listOf(1, 2), flushCalls)
        assertEquals(listOf("env-r1"), acked)
        assertFalse("no not-durable diagnostic once it succeeded", notDurable)
    }

    @Test
    fun `a persistent transient failure does NOT ack and retries are bounded`() = runTest {
        val flushCalls = mutableListOf<Int>()
        val acked = mutableListOf<String>()
        var notDurable = false
        var attempt = 0

        val result = flushThenAck(
            envelopeId = "env-r2",
            flush = { attempt++; flushCalls += attempt; throw IOException("disk still down") },
            ack = { acked += it },
            onNotDurable = { notDurable = true },
            backoff = { },
        )

        assertFalse("a never-clearing blip fails closed (no ack — relay + dup path recovers)", result)
        assertTrue("the not-durable diagnostic fired", notDurable)
        assertTrue("ackMessage never fired", acked.isEmpty())
        assertEquals(
            "retries are BOUNDED to FLUSH_MAX_ATTEMPTS — never an infinite loop",
            FLUSH_MAX_ATTEMPTS, flushCalls.size,
        )
    }

    @Test
    fun `a full-vault flush is NOT retried (fail-closed on the first attempt)`() = runTest {
        var flushCalls = 0
        val acked = mutableListOf<String>()

        val result = flushThenAck(
            envelopeId = "env-r3",
            flush = { flushCalls++; throw VaultCapacityException("vault full") },
            ack = { acked += it },
            onNotDurable = { },
            backoff = { },
        )

        assertFalse("capacity is fail-closed, no ack", result)
        assertEquals("capacity is non-transient: no retry", 1, flushCalls)
        assertTrue(acked.isEmpty())
    }

    @Test
    fun `a closed-runtime flush is NOT retried`() = runTest {
        var flushCalls = 0

        val result = flushThenAck(
            envelopeId = "env-r4",
            flush = { flushCalls++; throw IllegalStateException("vault runtime closed") },
            ack = { },
            onNotDurable = { },
            backoff = { },
        )

        assertFalse(result)
        assertEquals("a closed runtime is non-transient: no retry", 1, flushCalls)
    }
}
