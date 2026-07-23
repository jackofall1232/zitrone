// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

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
}
