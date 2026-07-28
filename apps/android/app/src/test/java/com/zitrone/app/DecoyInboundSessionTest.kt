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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
        /** Shared with a delegate under test, so teardown ORDER across the two is observable. */
        val journal: MutableList<String> = mutableListOf(),
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
            journal += "connect"
        }

        override fun disconnect() {
            disconnects++
            journal += "disconnect"
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
    fun `send-backs charge the SYNTHETIC budget and never black out the real path's cover`() = runTest {
        // The round-2 finding both lenses reached from opposite directions. Codex: send-backs were
        // recorded nowhere, so the meter under-reported the traffic U4 adds. Grok: recording them
        // against the REAL account's ring is worse than not recording them, because a relay can
        // induce send-backs by delivering cover-shaped envelopes and thereby switch off cover for
        // every genuine send for a full off-window — with the real socket quiet throughout.
        //
        // Both are satisfied by charging the synthetic account's own ring. This test pins the
        // asymmetry that makes it correct, which is the part neither a wiring tripwire nor a
        // presence check can see.
        val socket = FakeSocket()
        val pressure = CoverPressure(queuedBytes = { 0L }, nowMs = { testScheduler.currentTime })
        val session = DecoyInboundSession(
            scope = this,
            syntheticAccountId = { SYNTHETIC },
            realAccountId = { REAL },
            accessToken = { "token-1" },
            socket = socket,
            pressure = pressure,
            random = AlwaysZeroRandom(),
        )
        session.start()
        assertFalse("the meter starts clear", pressure.yieldingSendBack())

        repeat(CoverPressure.RATE_FRAMES) {
            socket.onDeliver!!.invoke(envelope(id = "cover-" + it))
            advanceUntilIdle()
        }

        assertTrue(
            "enough accepted send-backs must take FURTHER send-backs off — they are budget spent",
            pressure.yieldingSendBack(),
        )
        assertFalse(
            "…but they must NOT gate the send pairing's cover. That budget belongs to the real " +
                "account, which has sent nothing here.",
            pressure.yielding(),
        )
    }

    @Test
    fun `a REFUSED send-back is not recorded — a frame that never went was never spent`() = runTest {
        val socket = FakeSocket(sendSucceeds = false)
        val pressure = CoverPressure(queuedBytes = { 0L }, nowMs = { testScheduler.currentTime })
        val session = DecoyInboundSession(
            scope = this,
            syntheticAccountId = { SYNTHETIC },
            realAccountId = { REAL },
            accessToken = { "token-1" },
            socket = socket,
            pressure = pressure,
            random = AlwaysZeroRandom(),
        )
        session.start()

        repeat(CoverPressure.RATE_FRAMES) {
            socket.onDeliver!!.invoke(envelope(id = "cover-" + it))
            advanceUntilIdle()
        }

        assertFalse("a refused frame consumed no budget", pressure.yieldingSendBack())
        assertFalse(pressure.yielding())
    }

    @Test
    fun `the session yields its send-back on the SYNTHETIC channel, not only the shared one`() = runTest {
        // The previous test pins the meter's asymmetry by calling it directly; this one pins that
        // the SESSION asks the right question. A mutation swapping yieldingSendBack() for
        // yielding() survived without it: with the real path quiet, yielding() is false, so the
        // send-back went out into a synthetic budget the relay had just refused.
        val socket = FakeSocket()
        val pressure = CoverPressure(queuedBytes = { 0L }, nowMs = { testScheduler.currentTime })
        val session = DecoyInboundSession(
            scope = this,
            syntheticAccountId = { SYNTHETIC },
            realAccountId = { REAL },
            accessToken = { "token-1" },
            socket = socket,
            pressure = pressure,
            random = AlwaysZeroRandom(),
        )
        session.start()
        // The relay pushed back on the SYNTHETIC connection only. The real path is untouched.
        pressure.syntheticRateLimited()
        assertFalse("precondition: the pairing's cover is unaffected", pressure.yielding())

        socket.onDeliver!!.invoke(envelope(id = "cover-9"))
        advanceUntilIdle()

        assertTrue("the send-back must yield to the synthetic account's own budget", socket.sends.isEmpty())
        assertEquals("the ack is still exempt", listOf("cover-9"), socket.acks)
        assertEquals("and so is the burn", listOf("cover-9" to REAL), socket.burns)
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
    fun `stop leaves no outstanding work parked on a delay`() = runTest {
        // Distinct from the test above, and the distinction is what a mutation sweep found: every
        // job body ALSO re-checks `stopped`, so deleting stop()'s cancellation still emits nothing
        // and that test stays green. What cancellation buys is that teardown leaves NOTHING
        // RUNNING — jobs are not left parked on a drawn delay to discover the flag later — and that
        // is only visible here.
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this)
        session.start()

        socket.onDeliver!!.invoke(envelope(id = "cover-9"))
        assertEquals("a burn and a send-back are pending", 2, session.outstandingWork())
        session.stop()

        assertEquals("teardown must cancel them, not merely out-wait them", 0, session.outstandingWork())
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

    @Test
    fun `a concurrent start and reconnect do not both dial the socket`() = runTest {
        // U4 review round 1, Codex P2. The first version latched with an AtomicBoolean, which cannot
        // hold across the suspending token read: a start parked in accessToken() held the latch, a
        // concurrent reconnect cleared it unconditionally, its nested start claimed it and dialled,
        // and the parked one then dialled again. One transport change, two handshakes.
        val socket = FakeSocket()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var firstRead = true
        val session = DecoyInboundSession(
            scope = this,
            syntheticAccountId = { SYNTHETIC },
            realAccountId = { REAL },
            accessToken = {
                // Park ONLY the first token read, inside start()'s critical section.
                if (firstRead) { firstRead = false; gate.await() }
                "token-1"
            },
            socket = socket,
            pressure = CoverPressure(queuedBytes = { 0L }, nowMs = { testScheduler.currentTime }),
        )

        val starting = launch { session.start() }
        runCurrent()
        val reconnecting = launch { session.reconnect() }
        runCurrent()
        gate.complete(Unit)
        starting.join()
        reconnecting.join()

        // COUNTS CANNOT DISCRIMINATE THIS, and asserting them was the first version's mistake —
        // a mutation removing the mutex kept 2 dials and 1 disconnect and stayed green. What the
        // mutex actually buys is ORDER: the parked start finishes its dial before the reconnect
        // begins, so a disconnect always separates the two dials. Without it the reconnect's own
        // start dials first and the parked one then dials again, back to back, on a socket nothing
        // closed in between.
        assertEquals(
            "the socket must never be dialled twice without a disconnect between",
            listOf("connect", "disconnect", "connect"),
            socket.journal.filter { it == "connect" || it == "disconnect" },
        )
    }

    @Test
    fun `a stop concurrent with the dial itself must leave the socket closed`() {
        // U4 review round 1, Grok F2/P1 — and this one CANNOT be written on the test scheduler.
        // The window is between start()'s stopped-check and its dial, and neither suspends, so a
        // single-threaded dispatcher can never interleave there: the first version of this test
        // passed with the fix mutated out. Real threads, with the dial itself held open, are what
        // make the two versions distinguishable.
        //
        // With the fix, stop() blocks on the monitor the dial is holding, so it disconnects AFTER
        // the dial completes and the socket ends closed. Without it, stop() runs to completion
        // first and the dial then reopens the socket behind teardown's back.
        val inConnect = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val dialDone = java.util.concurrent.CountDownLatch(1)
        val socket = object : DecoyInboundSession.SyntheticSocket {
            override var onDeliver: ((MessageEnvelope) -> Unit)? = null

            @Volatile
            var open = false

            override fun connect(accessToken: String) {
                inConnect.countDown()
                release.await()
                open = true
                dialDone.countDown()
            }

            @Volatile
            var disconnects = 0

            override fun disconnect() {
                open = false
                disconnects++
            }

            override fun ack(messageId: String) = true
            override fun burn(messageId: String, peerId: String) = true
            override fun send(envelope: MessageEnvelope) = true
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        try {
            val session = DecoyInboundSession(
                scope = scope,
                syntheticAccountId = { SYNTHETIC },
                realAccountId = { REAL },
                accessToken = { "token-1" },
                socket = socket,
                pressure = CoverPressure(queuedBytes = { 0L }),
            )
            scope.launch { session.start() }
            assertTrue("the dial was never reached", inConnect.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // The vault locks while the dial is in flight.
            val stopper = Thread { session.stop() }
            stopper.start()
            // WAIT FOR stop() TO EITHER LAND OR BLOCK before releasing the dial — releasing
            // immediately was the first version's defect: the stopper had not necessarily run yet,
            // so the dial completed, THEN stop() disconnected, and the assertion passed for the
            // wrong reason with the fix mutated out.
            //
            // With the fix, stop() cannot reach its disconnect at all: it is blocked on the monitor
            // the dial holds, so this poll times out and that is the expected path. Without the
            // fix, stop() runs straight through and the disconnect is visible almost immediately —
            // which is what lets the dial afterwards reopen the socket and fail the assertion.
            val deadline = System.nanoTime() + 500_000_000L
            while (socket.disconnects == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            release.countDown()
            // BOTH must finish before the state is read. Joining only the stopper was the second
            // version's defect: the dial sets `open` after it is released, so the assertion could
            // run before that write and pass with the fix mutated out. The dialer is the one whose
            // completion the assertion actually depends on.
            assertTrue("the dial never completed", dialDone.await(5, java.util.concurrent.TimeUnit.SECONDS))
            stopper.join(5_000)

            assertFalse(
                "a synthetic socket still up after teardown discloses the vault lock by contrast — " +
                    "it would be the one flow that did not stop",
                socket.open,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `outstanding cover work is bounded, and the ack still fires past the cap`() = runTest {
        // Nothing upstream limits how fast the relay may deliver. Unbounded burn and reply jobs
        // would let cover work compete with the real send path for memory and CPU, which is the one
        // thing cover traffic must never do. Past the cap the work is simply not scheduled.
        val socket = FakeSocket()
        val session = session(socket, testScheduler, this, alwaysReply = false)
        session.start()

        repeat(DecoyInboundSession.MAX_OUTSTANDING_WORK + 20) { i ->
            socket.onDeliver!!.invoke(envelope(id = "cover-" + i))
        }

        assertEquals(
            "outstanding work must not grow past the cap",
            DecoyInboundSession.MAX_OUTSTANDING_WORK,
            session.outstandingWork(),
        )
        assertEquals(
            "every delivery is still acked — shedding acks would leave the relay retrying and " +
                "make load disclosable",
            DecoyInboundSession.MAX_OUTSTANDING_WORK + 20,
            socket.acks.size,
        )
    }

    // -- bindTo: teardown ordering --------------------------------------------------------------

    @Test
    fun `bindTo stops the synthetic side BEFORE the send pairing drains`() = runTest {
        val order = mutableListOf<String>()
        val socket = FakeSocket(journal = order)
        val session = session(socket, testScheduler, this)
        session.start()
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

        assertEquals(
            "the synthetic socket must go down BEFORE the pairing drains: a drain emits cover " +
                "frames, and a synthetic side still acking them would put its control frames on " +
                "the wire after the real socket's last real frame",
            listOf("disconnect", "delegate.stop", "invalidate"),
            order.filter { it != "connect" },
        )
        assertEquals(1, socket.disconnects)
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
