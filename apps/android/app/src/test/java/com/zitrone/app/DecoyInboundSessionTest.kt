// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.decoy.CoverPressure
import com.zitrone.app.decoy.DecoyInboundSession
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4 — the synthetic side of the cover exchange.
 *
 * The requirements these pin are in spec §4.4, and each was written and falsified there BEFORE this
 * code existed. What is tested here is the behaviour; what is tested in [DecoyU4SourceTripwireTest]
 * is that the *dependencies* still make R-U4-2 and R-U4-3 true by construction, because those two
 * are claims about what this type cannot reach rather than about what it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecoyInboundSessionTest {

    /** Records every frame the synthetic socket was asked to put on the wire, in order. */
    private class FakeSocket(
        var connectSucceeds: Boolean = true,
        var sendSucceeds: Boolean = true,
    ) : DecoyInboundSession.SyntheticSocket {
        override var onDeliver: ((MessageEnvelope) -> Unit)? = null
        val connects = CopyOnWriteArrayList<String>()
        val acks = CopyOnWriteArrayList<String>()
        val burns = CopyOnWriteArrayList<Pair<String, String>>()
        val sends = CopyOnWriteArrayList<MessageEnvelope>()
        var disconnects = 0

        override fun connect(accessToken: String) {
            if (!connectSucceeds) throw IllegalStateException("connect refused")
            connects += accessToken
        }

        override fun disconnect() {
            disconnects++
        }

        override fun ack(messageId: String): Boolean = acks.add(messageId)

        override fun burn(messageId: String, peerId: String): Boolean = burns.add(messageId to peerId)

        override fun send(envelope: MessageEnvelope): Boolean {
            sends += envelope
            return sendSucceeds
        }
    }

    private fun envelope(
        id: String = "env-1",
        senderId: String = REAL,
        recipientId: String = SYNTHETIC,
        ciphertextBytes: Int = 400,
    ) = MessageEnvelope(
        id = id,
        senderId = senderId,
        recipientId = recipientId,
        ciphertext = java.util.Base64.getEncoder().encodeToString(ByteArray(ciphertextBytes)),
        ephemeralKey = null,
        preKeyId = null,
        messageNumber = 3,
        previousChainLength = 0,
        timestamp = "2026-07-28T10:00:00.123Z",
        ttlSeconds = 86_400,
        burnOnRead = false,
        mediaType = "text",
        version = "1",
    )

    /**
     * @param alwaysReply forces every delivery to draw a send-back, so the reply path is exercised
     *   deterministically. The *rate* is not what these tests are about — the behaviour is.
     */
    private fun session(
        socket: FakeSocket,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        scope: kotlinx.coroutines.CoroutineScope,
        synthetic: String? = SYNTHETIC,
        real: String? = REAL,
        token: String? = "token-1",
        queuedBytes: () -> Long = { 0L },
        alwaysReply: Boolean = true,
    ): DecoyInboundSession = DecoyInboundSession(
        scope = scope,
        syntheticAccountId = { synthetic },
        realAccountId = { real },
        accessToken = { token },
        socket = socket,
        pressure = CoverPressure(queuedBytes = queuedBytes, nowMs = { scheduler.currentTime }),
        random = if (alwaysReply) AlwaysZeroRandom() else NeverZeroRandom(),
    )

    /** `nextInt(n)` = 0, so `shouldReply()` is true and every drawn delay is its minimum. */
    private class AlwaysZeroRandom : SecureRandom() {
        override fun nextInt(bound: Int): Int = 0
    }

    /** `nextInt(n)` = n-1, so `shouldReply()` is false for any denominator above 1. */
    private class NeverZeroRandom : SecureRandom() {
        override fun nextInt(bound: Int): Int = bound - 1
    }

    // -- R-U4-2 / delivery ----------------------------------------------------------------------

    @Test
    fun `acks a delivered cover envelope immediately, before any delay elapses`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "cover-9"))

        // No advanceUntilIdle: the ack must already have happened on the callback itself. An ack
        // deferred behind a delay is one the relay is still retrying delivery for.
        assertEquals(listOf("cover-9"), socket.acks)
        assertTrue("the burn is scheduled, not immediate", socket.burns.isEmpty())
    }

    @Test
    fun `burns the envelope after the drawn delay, naming the sender as the peer`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "cover-9", senderId = REAL))
        advanceUntilIdle()

        assertEquals(listOf("cover-9" to REAL), socket.burns)
    }

    @Test
    fun `never decrypts, stores or parses — it reads only the id and the sender`() = runTest {
        // The envelope's ciphertext is deliberately not valid base64-of-anything-meaningful. If this
        // class ever grows a parse step, this test starts failing rather than silently succeeding.
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        val junk = envelope(id = "cover-x").copy(ciphertext = "!!!not-base64!!!")
        socket.onDeliver!!.invoke(junk)
        advanceUntilIdle()

        assertEquals(listOf("cover-x"), socket.acks)
        assertEquals(listOf("cover-x" to REAL), socket.burns)
    }

    // -- R-U4-4: the send-back yields, the ack and burn do not ----------------------------------

    @Test
    fun `sends back an established-session reply addressed to the real account`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope())
        advanceUntilIdle()

        assertEquals(1, socket.sends.size)
        val reply = socket.sends.single()
        assertEquals("the reply is issued BY the synthetic account", SYNTHETIC, reply.senderId)
        assertEquals("the reply is addressed TO the real account", REAL, reply.recipientId)
        assertNull("a reply is never a first message — R-U4-3", reply.ephemeralKey)
        assertNull("a reply consumes no one-time prekey — R-U4-3", reply.preKeyId)
    }

    @Test
    fun `the send-back yields under pressure while the ack and burn still fire`() = runTest {
        val socket = FakeSocket()
        // Past CoverPressure's outbound-queue watermark: the meter is tripped for the whole window.
        val session = session(socket, testScheduler, this, queuedBytes = { 1L shl 20 })
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "cover-9"))
        advanceUntilIdle()

        assertTrue("the send-back is the half that yields", socket.sends.isEmpty())
        assertEquals("shedding an ack would leave the relay retrying — it is exempt", listOf("cover-9"), socket.acks)
        assertEquals("the burn is exempt for the same reason", listOf("cover-9" to REAL), socket.burns)
    }

    @Test
    fun `no send-back when the vault has no usable real account to address it to`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this, real = null)
        session.start()

        socket.onDeliver!!.invoke(envelope())
        advanceUntilIdle()

        assertTrue(socket.sends.isEmpty())
        assertEquals("delivery handling is unaffected", 1, socket.acks.size)
    }

    @Test
    fun `send-backs advance the synthetic account's own sending-chain counter`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "a"))
        advanceUntilIdle()
        socket.onDeliver!!.invoke(envelope(id = "b"))
        advanceUntilIdle()

        assertEquals(listOf(0, 1), socket.sends.map { it.messageNumber })
    }

    @Test
    fun `a delivery that draws no reply still acks and burns`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this, alwaysReply = false)
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "cover-9"))
        advanceUntilIdle()

        assertTrue(socket.sends.isEmpty())
        assertEquals(listOf("cover-9"), socket.acks)
        assertEquals(listOf("cover-9" to REAL), socket.burns)
    }

    // -- R-U4-6: silence, and the socket never outliving the session ----------------------------

    @Test
    fun `stop cancels a pending burn so no frame outlives the session`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "cover-9"))
        // The ack has already gone; the burn is still parked behind its drawn delay.
        assertEquals(listOf("cover-9"), socket.acks)
        session.stop()
        advanceUntilIdle()

        assertTrue("a burn must not fire after teardown", socket.burns.isEmpty())
        assertTrue("nor a send-back", socket.sends.isEmpty())
        assertEquals(1, socket.disconnects)
    }

    @Test
    fun `a delivery arriving after stop is ignored entirely`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()
        val deliver = socket.onDeliver
        session.stop()

        deliver!!.invoke(envelope(id = "late"))
        advanceUntilIdle()

        assertTrue(socket.acks.isEmpty())
        assertTrue(socket.burns.isEmpty())
        assertTrue(socket.sends.isEmpty())
    }

    @Test
    fun `stop detaches the delivery callback`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()
        assertNotNull(socket.onDeliver)

        session.stop()

        assertNull("a stopped session must not still be wired to its socket", socket.onDeliver)
    }

    @Test
    fun `a socket that refuses every frame is silent rather than throwing`() = runTest {
        val socket = FakeSocket(sendSucceeds = false)
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope())
        advanceUntilIdle()

        // The point is that nothing above threw and nothing was retried.
        assertEquals(1, socket.sends.size)
    }

    // -- start / reconnect ----------------------------------------------------------------------

    @Test
    fun `start is idempotent — the second call does not open a second socket`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)

        session.start()
        session.start()
        session.start()

        assertEquals(1, socket.connects.size)
    }

    @Test
    fun `start does nothing until the vault has a synthetic account`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this, synthetic = null)

        session.start()

        assertTrue("provisioning is lazy — no account means no socket", socket.connects.isEmpty())
        assertNull(socket.onDeliver)
    }

    @Test
    fun `a start with no token releases its latch so a later start can retry`() = runTest {
        val socket = FakeSocket()
        var token: String? = null
        val session = DecoyInboundSession(
            scope = this,
            syntheticAccountId = { SYNTHETIC },
            realAccountId = { REAL },
            accessToken = { token },
            socket = socket,
            pressure = CoverPressure(queuedBytes = { 0L }, nowMs = { testScheduler.currentTime }),
        )

        session.start()
        assertTrue(socket.connects.isEmpty())
        token = "token-later"
        session.start()

        assertEquals("a tokenless attempt must not latch the session off forever", 1, socket.connects.size)
    }

    @Test
    fun `a connect that throws releases the latch too`() = runTest {
        val socket = FakeSocket(connectSucceeds = false)
        val session = session(socket, testScheduler, this)

        session.start()
        socket.connectSucceeds = true
        session.start()

        assertEquals(1, socket.connects.size)
    }

    @Test
    fun `reconnect drops the old socket and dials again`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        session.reconnect()

        assertEquals(1, socket.disconnects)
        assertEquals("the redial must actually happen — start alone would no-op", 2, socket.connects.size)
    }

    @Test
    fun `reconnect is non-terminal — the session keeps working afterwards`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()
        session.reconnect()

        socket.onDeliver!!.invoke(envelope(id = "after-swap"))
        advanceUntilIdle()

        assertEquals(listOf("after-swap"), socket.acks)
        assertEquals(1, socket.sends.size)
    }

    @Test
    fun `reconnect after stop does nothing — teardown is terminal`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()
        session.stop()

        session.reconnect()

        assertEquals("stop's disconnect only", 1, socket.disconnects)
        assertEquals("no redial after a terminal stop", 1, socket.connects.size)
    }

    // -- bindTo: teardown ordering --------------------------------------------------------------

    @Test
    fun `bindTo stops the synthetic side BEFORE the send pairing drains`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()
        val order = mutableListOf<String>()
        val delegate = object : com.zitrone.app.decoy.CoverTraffic {
            override suspend fun cover(real: MessageEnvelope) = Unit
            override fun onRelayRateLimited() = Unit
            override fun stop(invalidateTransport: () -> Unit) {
                order += "delegate.stop"
                invalidateTransport()
            }
            override fun quiesce(swapTransport: () -> Unit) {
                order += "delegate.quiesce"
                swapTransport()
            }
        }
        val bound = session.bindTo(delegate)

        bound.stop { order += "invalidate" }

        assertEquals(listOf("delegate.stop", "invalidate"), order)
        assertEquals("the synthetic socket goes down first", 1, socket.disconnects)
        assertNull("and is detached before the drain runs", socket.onDeliver)
    }

    @Test
    fun `bindTo does NOT tear the synthetic side down on a non-terminal quiesce`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()
        val delegate = object : com.zitrone.app.decoy.CoverTraffic {
            override suspend fun cover(real: MessageEnvelope) = Unit
            override fun onRelayRateLimited() = Unit
            override fun stop(invalidateTransport: () -> Unit) = invalidateTransport()
            override fun quiesce(swapTransport: () -> Unit) = swapTransport()
        }

        session.bindTo(delegate).quiesce {}

        assertEquals("a transport toggle must not permanently kill cover traffic", 0, socket.disconnects)
        assertNotNull(socket.onDeliver)
        // And the session is still live: a delivery after the swap is still handled.
        socket.onDeliver!!.invoke(envelope(id = "still-live"))
        assertEquals(listOf("still-live"), socket.acks)
    }

    @Test
    fun `bindTo forwards cover and rate-limit signals unchanged`() = runTest {
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        val seen = mutableListOf<String>()
        val delegate = object : com.zitrone.app.decoy.CoverTraffic {
            override suspend fun cover(real: MessageEnvelope) { seen += "cover:${real.id}" }
            override fun onRelayRateLimited() { seen += "rate" }
            override fun stop(invalidateTransport: () -> Unit) = invalidateTransport()
            override fun quiesce(swapTransport: () -> Unit) = swapTransport()
        }
        val bound = session.bindTo(delegate)

        bound.cover(envelope(id = "real-1"))
        bound.onRelayRateLimited()

        assertEquals(listOf("cover:real-1", "rate"), seen)
        assertFalse("wrapping must not start the synthetic socket", socket.connects.isNotEmpty())
    }

    private companion object {
        const val SYNTHETIC = "acct-synthetic-0001"
        const val REAL = "acct-real-00000001"
    }
}
