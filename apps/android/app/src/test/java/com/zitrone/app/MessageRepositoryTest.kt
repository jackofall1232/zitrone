// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.Message
import com.zitrone.app.data.MessageRepository
import com.zitrone.app.data.MessageState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Burn-on-read timing and read-state semantics. Virtual time throughout —
 * the 5s grace window and the 600ms particle window elapse instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryTest {

    private fun TestScope.repository() =
        MessageRepository(scope = backgroundScope, clock = { currentTime })

    private fun message(
        id: String,
        isMine: Boolean = false,
        burnOnRead: Boolean = false,
        ttlSeconds: Int? = null,
    ) = Message(
        id = id,
        conversationId = "c1",
        text = "hello",
        isMine = isMine,
        timestampMs = 0L,
        ttlSeconds = ttlSeconds,
        burnOnRead = burnOnRead,
    )

    @Test
    fun `burn-on-read stays readable for the grace window then burns and notifies the peer`() =
        runTest {
            val repo = repository()
            var burned: Message? = null
            repo.onMessageBurned = { burned = it }
            repo.addIncoming(message("m1", burnOnRead = true))

            // Burn-on-read read is NOT receipt-worthy: the burn is the signal.
            assertFalse(repo.markRead("m1"))
            runCurrent()

            // READ and still visible for the whole window.
            assertEquals(MessageState.READ, repo.conversationMessages("c1").single().state)
            advanceTimeBy(MessageRepository.BURN_ON_READ_DELAY_MS - 1)
            assertEquals(MessageState.READ, repo.conversationMessages("c1").single().state)
            assertNull(burned)

            // Window elapses: burn starts and the peer is notified NOW —
            // burn time ≈ read time + grace window on both devices.
            advanceTimeBy(2)
            assertEquals(MessageState.BURNING, repo.conversationMessages("c1").single().state)
            assertEquals("m1", burned?.id)

            // Particle dissolve ends: the message ceases to exist.
            advanceTimeBy(MessageRepository.BURN_ANIMATION_MS + 1)
            assertTrue(repo.conversationMessages("c1").isEmpty())
        }

    @Test
    fun `repeated marks during the grace window do not shorten or duplicate the burn`() = runTest {
        val repo = repository()
        val burnedIds = mutableListOf<String>()
        repo.onMessageBurned = { burnedIds.add(it.id) }
        repo.addIncoming(message("m1", burnOnRead = true))

        repo.markRead("m1")
        runCurrent()
        advanceTimeBy(MessageRepository.BURN_ON_READ_DELAY_MS / 2)
        repo.markRead("m1") // chat recomposed mid-window
        advanceTimeBy(MessageRepository.BURN_ON_READ_DELAY_MS) // well past the original deadline

        assertEquals(listOf("m1"), burnedIds)
    }

    @Test
    fun `manual burn during the grace window burns once not twice`() = runTest {
        val repo = repository()
        val burnedIds = mutableListOf<String>()
        repo.onMessageBurned = { burnedIds.add(it.id) }
        repo.addIncoming(message("m1", burnOnRead = true))

        repo.markRead("m1")
        runCurrent()
        repo.burnAll("c1") // user hits burn-all mid-window

        advanceTimeBy(
            MessageRepository.BURN_ON_READ_DELAY_MS + MessageRepository.BURN_ANIMATION_MS + 10,
        )
        assertEquals(listOf("m1"), burnedIds)
        assertTrue(repo.conversationMessages("c1").isEmpty())
    }

    @Test
    fun `markRead reports the receipt-worthy transition exactly once`() = runTest {
        val repo = repository()
        repo.addIncoming(message("m1"))

        assertTrue(repo.markRead("m1"))
        assertFalse(repo.markRead("m1")) // repeat
        assertFalse(repo.markRead("missing"))
        assertEquals(MessageState.READ, repo.conversationMessages("c1").single().state)
    }

    @Test
    fun `own messages are never marked read locally`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true))

        assertFalse(repo.markRead("m1"))
        assertEquals(MessageState.SENDING, repo.conversationMessages("c1").single().state)
    }

    @Test
    fun `peer read receipt flips an outgoing message to READ and ignores incoming ones`() =
        runTest {
            val repo = repository()
            repo.addOutgoing(message("mine", isMine = true))
            repo.markDelivered("mine")
            repo.addIncoming(message("theirs"))

            repo.onPeerRead("mine")
            repo.onPeerRead("theirs") // a peer cannot mark THEIR message read on our side
            repo.onPeerRead("missing")

            val byId = repo.conversationMessages("c1").associateBy { it.id }
            assertEquals(MessageState.READ, byId.getValue("mine").state)
            assertEquals(MessageState.DELIVERED, byId.getValue("theirs").state)
        }

    @Test
    fun `outgoing state advances SENDING to SENT to DELIVERED to READ on real acks`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true))
        assertEquals(MessageState.SENDING, repo.conversationMessages("c1").single().state)

        repo.markSent("m1") // relay stored it (message.stored)
        assertEquals(MessageState.SENT, repo.conversationMessages("c1").single().state)

        repo.markDelivered("m1") // recipient received it (message.delivered)
        assertEquals(MessageState.DELIVERED, repo.conversationMessages("c1").single().state)

        repo.onPeerRead("m1") // peer read receipt
        assertEquals(MessageState.READ, repo.conversationMessages("c1").single().state)
    }

    @Test
    fun `markDelivered accepts SENDING directly when the stored ack was lost`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true))
        repo.markDelivered("m1") // no markSent in between
        assertEquals(MessageState.DELIVERED, repo.conversationMessages("c1").single().state)
    }

    @Test
    fun `receipts are monotonic — a late stored or delivered never downgrades`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true))
        repo.markSent("m1")
        repo.markDelivered("m1")
        repo.onPeerRead("m1") // READ

        // Out-of-order frames arriving after READ must not regress the state.
        repo.markSent("m1")
        repo.markDelivered("m1")
        assertEquals(MessageState.READ, repo.conversationMessages("c1").single().state)
    }

    @Test
    fun `markFailed flips an unsent message to FAILED and retryable re-arms it`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true))

        repo.markFailed("m1")
        assertEquals(MessageState.FAILED, repo.conversationMessages("c1").single().state)

        // retryable flips FAILED→SENDING and returns the retained message.
        val armed = repo.retryable("m1")
        assertEquals("m1", armed?.id)
        assertEquals(MessageState.SENDING, repo.conversationMessages("c1").single().state)
        // A non-FAILED message is not retryable (stray tap = no-op).
        assertNull(repo.retryable("m1"))
    }

    @Test
    fun `stored and delivered acks never resurrect a burned or removed message`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true))
        repo.burn("m1", notifyPeer = false) // BURNING

        repo.markSent("m1")
        repo.markDelivered("m1")
        repo.markFailed("m1")
        assertEquals(MessageState.BURNING, repo.conversationMessages("c1").single().state)

        // After the dissolve the message is gone — acks for it are pure no-ops.
        advanceTimeBy(MessageRepository.BURN_ANIMATION_MS + 1)
        repo.markSent("m1")
        repo.markDelivered("m1")
        assertTrue(repo.conversationMessages("c1").isEmpty())
    }

    @Test
    fun `sender TTL starts on DELIVERED, not on send`() = runTest {
        val repo = repository()
        repo.addOutgoing(message("m1", isMine = true, ttlSeconds = 30))
        runCurrent()

        // Enqueued but undelivered: the TTL has NOT started (it used to start on
        // ws-enqueue — the false-optimism bug), so the message does not burn.
        advanceTimeBy(30_000L + 1)
        assertEquals(MessageState.SENDING, repo.conversationMessages("c1").single().state)

        // Real delivery (message.delivered receipt) starts the countdown here.
        repo.markDelivered("m1")
        assertEquals(MessageState.DELIVERED, repo.conversationMessages("c1").single().state)
        advanceTimeBy(30_000L + 1)
        // TTL enforced both sides independently — burns locally, no peer signal.
        assertEquals(MessageState.BURNING, repo.conversationMessages("c1").single().state)
    }

    @Test
    fun `ttl burn still fires locally without notifying the peer`() = runTest {
        val repo = repository()
        val burnedIds = mutableListOf<String>()
        repo.onMessageBurned = { burnedIds.add(it.id) }
        repo.addIncoming(message("m1", ttlSeconds = 30))
        runCurrent()

        advanceTimeBy(30_000L + 1)
        assertEquals(MessageState.BURNING, repo.conversationMessages("c1").single().state)
        // TTL is enforced on both sides independently — no burn signal out.
        assertTrue(burnedIds.isEmpty())
    }
}
