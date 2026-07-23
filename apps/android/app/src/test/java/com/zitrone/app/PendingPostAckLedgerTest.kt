// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * D2c round 7 (Codex :1237) — the owed post-ack side-effects ledger that keeps an attachment's
 * one-shot blob redemption (plus the delivery receipt and notification) retryable when the durable
 * ack only lands on the DUPLICATE-redelivery path: the display branch owes the entry before the
 * roster bump / flush can fail, and whichever path finally acks durable settles it exactly once.
 *
 * The coordinator is not host-drivable (see FlushBeforeAckBarrierTest's header), so these drive
 * the extracted [PendingPostAckLedger] plus the real [flushThenAck] barrier in the same sequence
 * the coordinator runs; the wiring (owe-after-display, settle-after-durable-ack on both paths,
 * dropContact in the outcome-gated delete cleanup) is a source invariant.
 */
class PendingPostAckLedgerTest {

    private fun owedAttachment(sender: String = "contact-a") = PendingPostAckLedger.Owed(
        senderId = sender,
        conversationId = "conv-1",
        sendReceipt = true,
        notify = true,
        attachment = null, // Attachment construction needs a full control payload; null vs
        // non-null does not change ledger semantics, which is what is under test here.
    )

    @Test
    fun `an entry owed before a failed flush is still settleable by the duplicate path`() = runTest {
        val ledger = PendingPostAckLedger()
        val acked = mutableListOf<String>()

        // FIRST delivery: display → owe → durable flush FAILS → no ack, no settle (the coordinator
        // returns out of the branch). The entry must survive for the redelivery.
        ledger.owe("env-1", owedAttachment())
        val firstAck = flushThenAck(
            envelopeId = "env-1",
            flush = { throw IOException("transient reseal failure") },
            ack = { acked += it },
            onNotDurable = { },
        )
        assertEquals(false, firstAck)
        assertTrue("first delivery never acked", acked.isEmpty())
        assertEquals(setOf("env-1"), ledger.pending())

        // REDELIVERY: decrypt throws DuplicateMessageException → ACK_AND_DROP → durable flush now
        // lands → settle hands the owed effects to the duplicate path.
        val dupAck = flushThenAck(
            envelopeId = "env-1",
            flush = { },
            ack = { acked += it },
            onNotDurable = { },
        )
        assertEquals(true, dupAck)
        assertEquals(listOf("env-1"), acked)
        val owed = ledger.settle("env-1")
        assertNotNull("the duplicate path claims the owed effects", owed)
        assertEquals(true, owed!!.sendReceipt)
        assertEquals(true, owed.notify)
        assertTrue("nothing left owed", ledger.pending().isEmpty())
    }

    @Test
    fun `settle is exactly-once — a second claim gets nothing`() {
        val ledger = PendingPostAckLedger()
        ledger.owe("env-2", owedAttachment())
        assertNotNull(ledger.settle("env-2"))
        assertNull("the losing path must not re-run the effects", ledger.settle("env-2"))
    }

    @Test
    fun `settling an envelope that owes nothing is a no-op`() {
        assertNull(PendingPostAckLedger().settle("env-unknown"))
    }

    @Test
    fun `dropContact discards only the deleted contact's owed entries`() {
        val ledger = PendingPostAckLedger()
        ledger.owe("env-a1", owedAttachment(sender = "contact-a"))
        ledger.owe("env-a2", owedAttachment(sender = "contact-a"))
        ledger.owe("env-b1", owedAttachment(sender = "contact-b"))

        ledger.dropContact("contact-a")

        assertEquals(setOf("env-b1"), ledger.pending())
        assertNull("a deleted contact's effects must never fire", ledger.settle("env-a1"))
        assertNotNull(ledger.settle("env-b1"))
    }

    @Test
    fun `re-owing an envelope overwrites the stale entry`() {
        val ledger = PendingPostAckLedger()
        ledger.owe("env-3", owedAttachment(sender = "contact-a"))
        ledger.owe("env-3", owedAttachment(sender = "contact-b"))
        assertEquals("contact-b", ledger.settle("env-3")!!.senderId)
        assertNull(ledger.settle("env-3"))
    }
}
