// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.decoy.CoverTraffic
import com.zitrone.app.decoy.DecoyEnvelopeBuilder
import com.zitrone.app.decoy.DecoySendPairing
import com.zitrone.app.net.WsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * THE U3 GATE: **a covered send puts two frames of the same length on the wire, the REAL ONE FIRST,
 * and nothing that happens on the cover side can cost the real send.**
 *
 * The order half of the gate changed on 2026-07-27: spec §4.3 R-U3-2 was amended by maintainer
 * ruling, random ordering is conceded, and the real frame always goes first. So the statistical
 * order test that used to live here is gone and its replacement is an absolute one — a single
 * decoy-first send is now a failure, not a sample. What that ruling buys is tested directly, which
 * is the point of this file's second half: **four R-U3-1 edges that were arguments in the round-1
 * review are now assertions** (process death at the suspension point, a `deleteContact` queued on
 * the confined worker, the `sendLimit` boundary, and a concurrent send's latency).
 *
 * The three surviving properties are still tested three different ways on purpose:
 *
 *  - **the gap** is statistical, per §4.3 R-U3-2 ("pinned by a statistical test over many sends, not
 *    by reading the code"), so it is measured over thousands of sends. The generator is a seeded
 *    [SecureRandom], which fixes the SAMPLE and not the mechanism: every defect these tests exist to
 *    catch — a fixed gap, a biased draw, a gap drawn once and reused — is a property of the
 *    mechanism and shows up whatever the seed is. A separate test covers what a seeded generator
 *    cannot: that production's default source is not itself a fixed stream.
 *  - **R-U3-1** is tested by fault injection at every point that can fail — the builder refusing,
 *    the identity missing, the vault section unreadable, the socket throwing, the socket dead, the
 *    scope cancelled inside the drawn gap — always asking the same question: did the real publish
 *    still happen, exactly once, and first.
 *  - **uniformity (R-U3-3)** is tested through the shape of the predicate: no envelope class is
 *    treated differently, and the one condition consulted per send flips once and never back.
 *
 * `DecoyEnvelopeBuilderTest` is the gate for what a cover envelope IS (byte lengths measured against
 * real libsignal 0.46.0 output) and nothing here re-litigates it. The fixtures carry ciphertext
 * lengths measured there — 323 B for a one-block subsequent message, 404 B for the first message
 * that wraps one, 579 B for a two-block attachment control payload — and the builder's own closing
 * equal-frame check throws on anything inconsistent, so an unrealistic fixture fails loudly here
 * rather than passing quietly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecoySendPairingTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private val senderAccountId = UUID.randomUUID().toString()
    private val contactAccountId = UUID.randomUUID().toString()
    private val syntheticAccountId = UUID.randomUUID().toString()
    private val senderRegistrationId = 9_142
    private val senderIdentity: IdentityKeyPair = IdentityKeyPair.generate()

    private fun sender() = DecoyEnvelopeBuilder.Sender(
        accountId = senderAccountId,
        registrationId = senderRegistrationId,
        identityKeySerialized = senderIdentity.publicKey.serialize(),
    )

    /** Deterministic, and still a [SecureRandom] — the type the production seam requires. */
    private fun seeded(seed: Long): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

    private fun b64(bytes: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    /** An ordinary text message on an established session — one padded block. */
    private fun textEnvelope(
        counter: Int = 7,
        ttlSeconds: Int? = 3_600,
        burnOnRead: Boolean = false,
    ) = MessageEnvelope(
        id = UUID.randomUUID().toString(),
        senderId = senderAccountId,
        recipientId = contactAccountId,
        ciphertext = b64(323),
        ephemeralKey = null,
        preKeyId = null,
        messageNumber = counter,
        previousChainLength = 0,
        timestamp = "2026-07-27T09:41:07.123Z",
        ttlSeconds = ttlSeconds,
        burnOnRead = burnOnRead,
        mediaType = MessageEnvelope.MEDIA_TEXT,
    )

    /** An X3DH first message — the shape whose frame is ~147 B larger. */
    private fun firstEnvelope() = MessageEnvelope(
        id = UUID.randomUUID().toString(),
        senderId = senderAccountId,
        recipientId = contactAccountId,
        ciphertext = b64(404),
        ephemeralKey = Base64.getEncoder()
            .encodeToString(IdentityKeyPair.generate().publicKey.serialize()),
        preKeyId = 1,
        messageNumber = 0,
        previousChainLength = 0,
        timestamp = "2026-07-27T09:41:07.123456Z",
        ttlSeconds = null,
        burnOnRead = true,
        mediaType = MessageEnvelope.MEDIA_TEXT,
    )

    /**
     * A read receipt exactly as `sendReadReceipt` builds it: no TTL, no burn flag, text media —
     * deliberately indistinguishable from conversation text, which is why it must be paired too.
     */
    private fun receiptEnvelope() = textEnvelope(counter = 12, ttlSeconds = null)

    /** An attachment control payload: two padded blocks, riding media_type "text" like a receipt. */
    private fun attachmentControlEnvelope() = textEnvelope(counter = 3).copy(ciphertext = b64(579))

    // ── harness ─────────────────────────────────────────────────────────────────────────────

    /** Marks the REAL publish in the recorded frame sequence; decoys record their envelope. */
    private object Real

    private fun decoysIn(frames: List<Any>) = frames.filterIsInstance<MessageEnvelope>()

    private fun CoroutineScope.pairing(
        frames: MutableList<Any>,
        random: SecureRandom = seeded(1),
        recipient: () -> String? = { syntheticAccountId },
        sender: () -> DecoyEnvelopeBuilder.Sender? = ::sender,
        send: (MessageEnvelope) -> Boolean = { frames.add(it); true },
        provision: suspend () -> Unit = {},
        sleep: suspend (Long) -> Unit = {},
    ) = DecoySendPairing(
        scope = this,
        sender = sender,
        recipient = recipient,
        send = send,
        provision = provision,
        random = random,
        sleep = sleep,
        // The provisioning job must live in the test's virtual time, not on a real IO thread.
        provisionContext = EmptyCoroutineContext,
    )

    /**
     * ONE COVERED SEND, in the coordinator's own order: the non-suspending publish tail runs at the
     * **call site** and the cover-traffic seam is entered only afterwards.
     *
     * That is not a stylistic choice in the harness — it is the shape of the production call site
     * since U3 fix round 3 (`publishOutgoing(...)` then `coverTraffic.cover(envelope)`), and the
     * reason for it is that the seam can no longer be handed a real send at all. See
     * `the cover-traffic seam cannot be handed a real send to run`.
     */
    private suspend fun DecoySendPairing.record(real: MessageEnvelope, frames: MutableList<Any>) {
        frames.add(Real)
        cover(real)
    }

    /**
     * A socket that really dies. `WsClient.send` is `webSocket?.send(frame) ?: false`, so once
     * `disconnect()` has nulled the socket every subsequent frame is silently refused — which is the
     * whole mechanism behind the round-2 teardown defect and the thing an always-succeeding fake
     * socket could never show.
     */
    private class DyingSocket(private val frames: MutableList<Any>) {
        @Volatile
        var connected = true
            private set

        fun disconnect() {
            connected = false
        }

        fun send(frame: MessageEnvelope): Boolean = synchronized(this) {
            if (!connected) return false
            frames.add(frame)
            true
        }
    }

    private fun frameLength(envelope: MessageEnvelope): Int =
        WsClient.messageSendFrame(envelope).toString().toByteArray(Charsets.UTF_8).size

    // ── R-U3-2 (amended): the real frame is FIRST, always ───────────────────────────────────

    @Test
    fun `the REAL frame always goes first - every send, every envelope class`() = runTest {
        // The amended R-U3-2. Not a statistic: ONE decoy-first send is a defect, because the whole
        // of R-U3-1 is now paid for by the real publish being committed to the socket before any
        // cover code runs. Driven with the PRODUCTION generator rather than a seeded one — the order
        // must not be a function of any draw, so no seed may be able to make it come out right.
        val shapes = listOf<Pair<String, () -> MessageEnvelope>>(
            "text" to { textEnvelope() },
            "first message" to { firstEnvelope() },
            "read receipt" to { receiptEnvelope() },
            "attachment control payload" to { attachmentControlEnvelope() },
        )
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, random = SecureRandom())
        repeat(1_000) { i ->
            val (name, shape) = shapes[i % shapes.size]
            frames.clear()
            pairing.record(shape(), frames)
            assertEquals("$name: a send that was not a pair", 2, frames.size)
            assertTrue("$name: the COVER frame went first on send $i", frames.first() === Real)
        }
    }

    @Test
    fun `no cover-side code runs before the real publish`() = runTest {
        // The ruling's exact words, asserted rather than assumed: "the real frame is committed to
        // the socket before any cover code runs." Every cover-side collaborator — the vault read,
        // the identity read, the socket — records whether the real frame had already gone when it
        // was called. This is the test that catches the *quiet* regression: hoisting the envelope
        // BUILD above the publish introduces no suspension, so the confinement test below would not
        // notice, but it puts cover-side work (and cover-side latency, and a cover-side throw) in
        // front of a real send again.
        val frames = mutableListOf<Any>()
        val realGoneWhenCalled = mutableListOf<Boolean>()
        val pairing = pairing(
            frames,
            recipient = { realGoneWhenCalled.add(frames.contains(Real)); syntheticAccountId },
            sender = {
                realGoneWhenCalled.add(frames.contains(Real))
                this@DecoySendPairingTest.sender()
            },
            send = { realGoneWhenCalled.add(frames.contains(Real)); frames.add(it); true },
        )
        pairing.record(textEnvelope(), frames)

        assertEquals("a cover-side collaborator was never called", 3, realGoneWhenCalled.size)
        assertTrue(
            "cover code ran before the real frame was committed to the socket",
            realGoneWhenCalled.all { it },
        )
    }

    @Test
    fun `the DEFAULT generator is unpredictable, not a fixed stream`() = runTest {
        // The seeded tests prove the mechanism consumes its draw correctly; they cannot prove
        // production does not ship a constant or a fixed seed. Two default-constructed instances
        // must disagree — and note WHY it has to be a cryptographic source now that the order bit is
        // gone: the gap is the only drawn quantity and it is DIRECTLY OBSERVABLE on the wire, so a
        // predictable generator would let an observer recover the whole future stream from a handful
        // of measured gaps and use it as a stable device fingerprint linking pairs, sessions and —
        // one instance per live vault session — vaults.
        val samples = (1..2).map {
            val frames = mutableListOf<Any>()
            val gaps = mutableListOf<Long>()
            val pairing = DecoySendPairing(
                scope = this,
                sender = ::sender,
                recipient = { syntheticAccountId },
                send = { frames.add(it); true },
                provision = {},
                sleep = { gaps.add(it) },
            )
            repeat(64) { pairing.record(textEnvelope(), frames) }
            gaps.toList()
        }
        assertNotEquals("two default instances drew the same gap sequence", samples[0], samples[1])
    }

    // ── R-U3-2: the gap ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the gap is drawn per send, bounded, and uniform`() = runTest {
        val n = 4_000
        val frames = mutableListOf<Any>()
        val gaps = mutableListOf<Long>()
        val pairing = pairing(frames, random = seeded(4242), sleep = { gaps.add(it) })
        repeat(n) { pairing.record(textEnvelope(), frames) }

        assertEquals("exactly one gap is drawn per send", n, gaps.size)
        assertTrue(
            "a gap fell outside the declared bound",
            gaps.all { it >= DecoySendPairing.GAP_MIN_MS && it <= DecoySendPairing.GAP_MAX_MS },
        )
        // A FIXED delay is the defect this discriminates: it would produce exactly one value.
        val span = DecoySendPairing.GAP_MAX_MS - DecoySendPairing.GAP_MIN_MS + 1
        assertEquals("the draw does not cover its own declared support", span, gaps.distinct().size)

        // Uniform over the closed interval → mean at the midpoint. sd of a discrete uniform over
        // `span` values is sqrt((span² − 1)/12); this is 4 standard errors of the mean at this n.
        val mid = (DecoySendPairing.GAP_MIN_MS + DecoySendPairing.GAP_MAX_MS) / 2.0
        val sd = sqrt((span.toDouble() * span - 1) / 12)
        assertTrue(
            "gap mean ${gaps.average()} is not the midpoint $mid of a uniform draw",
            abs(gaps.average() - mid) < 4 * sd / sqrt(n.toDouble()),
        )

        // A gap drawn ONCE and reused would pass the bound and the mean but not this: consecutive
        // draws must be independent, so the lag-1 autocorrelation sits at zero.
        val mean = gaps.average()
        val cov = (0 until n - 1).sumOf { (gaps[it] - mean) * (gaps[it + 1] - mean) } / (n - 1)
        val variance = gaps.sumOf { (it - mean) * (it - mean) } / n
        assertTrue(
            "consecutive gaps are correlated (r=${cov / variance})",
            abs(cov / variance) < 4 / sqrt(n.toDouble()),
        )
    }

    // ── the pair itself ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the two frames are the same length and the cover carries nothing of the real one`() = runTest {
        for (real in listOf(textEnvelope(), firstEnvelope(), receiptEnvelope(), attachmentControlEnvelope())) {
            val frames = mutableListOf<Any>()
            pairing(frames).record(real, frames)

            assertEquals("one real frame and one cover frame", 2, frames.size)
            val decoy = decoysIn(frames).single()
            assertEquals(
                "the cover frame is not the length of the frame it covers",
                frameLength(real),
                frameLength(decoy),
            )
            assertEquals("the cover is addressed to the synthetic account", syntheticAccountId, decoy.recipientId)
            assertEquals("the cover is sent as this account", senderAccountId, decoy.senderId)
            assertNotEquals("the cover reuses the real message id", real.id, decoy.id)
            assertNotEquals("the cover reuses the real ciphertext", real.ciphertext, decoy.ciphertext)
        }
    }

    @Test
    fun `EVERY envelope class through the choke point is paired - receipts and attachments included`() =
        runTest {
            // The answer to the open question, asserted as behaviour. A receipt envelope is built to
            // be indistinguishable from text, so pairing only user-visible messages would sort the
            // one size class an observer can see into paired and unpaired halves — a receipt
            // detector introduced BY the privacy feature. Each fixture is a genuinely different real
            // shape (first vs subsequent, TTL vs none, burn vs not, one block vs two), so an
            // implementation that quietly covered only one of them fails here.
            val classes = mapOf(
                "text" to textEnvelope(),
                "first message" to firstEnvelope(),
                "read receipt" to receiptEnvelope(),
                "attachment control payload" to attachmentControlEnvelope(),
            )
            for ((name, envelope) in classes) {
                val frames = mutableListOf<Any>()
                pairing(frames).record(envelope, frames)
                assertEquals("$name went unpaired", 1, decoysIn(frames).size)
                assertEquals("$name: wrong frame count", 2, frames.size)
            }
        }

    // ── R-U3-1: nothing on the cover side may cost the real send ────────────────────────────

    @Test
    fun `a build refusal sends the real frame UNCOVERED rather than failing it`() = runTest {
        // R-U3-4's ruling, exercised through a REAL refusal rather than a stubbed throw: the builder
        // fails closed when the synthetic recipient id is not the same width as the covered one,
        // because that width is part of the frame.
        val frames = mutableListOf<Any>()
        pairing(frames, recipient = { "short-id" }).record(textEnvelope(), frames)

        assertEquals("the real send did not go", listOf<Any>(Real), frames)
    }

    @Test
    fun `a missing local identity sends the real frame uncovered`() = runTest {
        val frames = mutableListOf<Any>()
        pairing(frames, sender = { throw IllegalStateException("no local identity") })
            .record(textEnvelope(), frames)

        assertEquals(listOf<Any>(Real), frames)
    }

    @Test
    fun `an unreadable vault section sends the real frame uncovered`() = runTest {
        // A closed runtime throws out of `runtime.read`. That read is on the cover side, so it may
        // not become the real send's problem — and it must not escape into the coordinator's
        // runCatching either, which would mark an already-delivered message FAILED.
        val frames = mutableListOf<Any>()
        pairing(frames, recipient = { throw IllegalStateException("closed") })
            .record(textEnvelope(), frames)

        assertEquals(listOf<Any>(Real), frames)
    }

    @Test
    fun `a THROWING socket on the cover frame never reaches the real send`() = runTest {
        val frames = mutableListOf<Any>()
        pairing(frames, send = { throw java.io.IOException("socket blew up") })
            .record(textEnvelope(), frames)

        assertEquals("the real send was lost to the cover frame's failure", listOf<Any>(Real), frames)
    }

    @Test
    fun `a dead socket on the cover frame changes nothing about the real send`() = runTest {
        val frames = mutableListOf<Any>()
        pairing(frames, send = { frames.add(it); false }).record(textEnvelope(), frames)

        assertEquals("a false from the socket is not a failure to handle", 2, frames.size)
    }

    @Test
    fun `cancellation inside the drawn gap still publishes the real frame and still pairs it`() = runTest {
        // Teardown lands in the gap on a mobile messenger constantly (vault lock, backgrounding).
        // It may not swallow the message, and it may not leave the real frame UNPAIRED either —
        // an unpaired frame is a marked frame (R-U3-3), so the `finally` emits the cover frame with
        // the drawn gap cut short rather than dropping it.
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, sleep = { delay(it) })
        val job = launch { pairing.record(textEnvelope(), frames) }
        runCurrent()
        job.cancelAndJoin()

        assertEquals("a cancelled pairing lost a frame", 2, frames.size)
        assertTrue("the real frame did not go first", frames.first() === Real)
    }

    @Test
    fun `a CancellationException out of the cover frame cannot skip the real publish`() = runTest {
        // U3-D, kept as a regression test after the ruling made it impossible. `emit` rethrows
        // CancellationException — the one throwable it deliberately does not swallow — and under the
        // old random ordering that rethrow could run BEFORE the real publish and take it with it.
        // It now cannot: the publish happens at the call site, before the seam is entered at all.
        var published = 0
        val pairing = pairing(mutableListOf(), send = { throw CancellationException("cover frame") })
        try {
            published++
            pairing.cover(textEnvelope())
        } catch (_: CancellationException) {
            // The cover frame's cancellation still propagates; it just arrives too late to matter.
        }

        assertEquals("cover traffic swallowed a real send", 1, published)
    }

    // ── R-U3-1 edges the round-1 review left uncovered (U3-I) ───────────────────────────────

    @Test
    fun `the cover-traffic seam cannot be handed a real send to run`() {
        // U3-A / V1, and the correction of the claim this file used to make. Round 2 asserted that
        // process death was harmless because "a process can only be killed at a suspension point".
        // That is FALSE: a coroutine may only SUSPEND at a suspension point, while the OS can kill
        // the process at ANY instruction — which is exactly what this project's threat model
        // assumes. So "publish() is the first statement of paired()" was not enough: getting into
        // paired() already cost an interface dispatch, a captured lambda and entry into a coroutine
        // state machine, all of it AFTER the ratchet advance was durable and BEFORE ws.sendMessage.
        // A kill in there lost a message whose ratchet had already moved. If the baseline kill
        // window is K, cover traffic made it K ∪ C, and R-U3-1 is absolute.
        //
        // The only way to make C empty is for the caller to publish and THEN call the seam, so the
        // seam must have no parameter it could run. That is the property asserted here, because it
        // is the one an implementer could quietly undo: reintroducing a `publish: () -> Unit`
        // parameter would compile, would pass every behavioural test in this file, and would put
        // cover-specific instructions back in front of the handoff.
        for (method in CoverTraffic::class.java.methods.filter { it.name == "cover" }) {
            assertTrue(
                "CoverTraffic.cover takes a callable — a real send can be handed to cover traffic again",
                method.parameterTypes.none { kotlin.Function::class.java.isAssignableFrom(it) },
            )
        }
    }

    @Test
    fun `the drawn gap is the only suspension point, and it is after the handoff`() = runTest {
        // What survives of U3-A once the false premise is dropped. Process death is no longer
        // argued from suspension points at all — the real frame is on the socket before this class
        // is entered — but the gap must still be the ONLY place this class suspends, because a
        // second suspension seam would be a second place a teardown could interleave and a place
        // stop()'s drain could not wait through (buildCover is deliberately non-suspending).
        val frames = mutableListOf<Any>()
        var atSuspension: List<Any>? = null
        var suspensions = 0
        val pairing = pairing(frames, sleep = { suspensions++; atSuspension = frames.toList() })
        pairing.record(textEnvelope(), frames)

        assertEquals("the class suspends somewhere other than the drawn gap", 1, suspensions)
        assertEquals(
            "the real frame was not already on the socket at the gap",
            listOf<Any>(Real),
            atSuspension,
        )
        assertEquals("the pair did not complete", 2, frames.size)
    }

    @Test
    fun `a deleteContact queued on the confined worker cannot interleave before the publish tail`() =
        runTest {
            // U3-B. The coordinator runs sends on `Dispatchers.IO.limitedParallelism(1)`, and
            // deleteContact is queued on that same worker — so any suspension between the durable
            // flush and the `contactExists → ws.sendMessage` tail lets the delete run in between and
            // the message is discarded, having already advanced the ratchet. Reproduced exactly:
            // both coroutines on ONE dispatcher, the delete queued behind a send that is already
            // running. A pairing that suspends before publishing hands the worker to the delete.
            val worker = StandardTestDispatcher(testScheduler)
            val frames = mutableListOf<Any>()
            var contactDeleted = false
            var contactWasLiveAtPublish: Boolean? = null
            val pairing = pairing(frames, sleep = { delay(it) })

            launch(worker) {
                // The coordinator's real tail, in miniature — at the CALL SITE, where it now lives.
                contactWasLiveAtPublish = !contactDeleted
                frames.add(Real)
                pairing.cover(textEnvelope())
            }
            launch(worker) { contactDeleted = true }
            advanceUntilIdle()

            assertEquals(
                "cover traffic let a queued deleteContact interleave and discard a real send",
                true,
                contactWasLiveAtPublish,
            )
            assertEquals("the pair did not complete", 2, frames.size)
        }

    @Test
    fun `with one send permit left the REAL frame takes it, never the cover frame`() = runTest {
        // U3-C's self-preemption half. The relay charges the AUTHENTICATED account, so both frames
        // draw the same per-account `sendLimit` bucket; a cover frame enqueued first would take the
        // last permit and the real frame would come back `rate_limited` with no message id to mark
        // or retry. Real-first makes that impossible within a pair — modelled here as a socket that
        // accepts exactly one more frame.
        //
        // NOT covered here, deliberately: CROSS-send preemption (pair N's cover frame taking the
        // permit pair N+1's real frame needed) survives every ordering and is a relay-side item.
        var permits = 1
        val accepted = mutableListOf<Any>()
        fun spend(frame: Any): Boolean =
            if (permits > 0) { permits--; accepted.add(frame); true } else false

        val pairing = pairing(mutableListOf(), send = ::spend)
        spend(Real)
        pairing.cover(textEnvelope())

        assertEquals(
            "the cover frame spent the last permit the real send needed",
            listOf<Any>(Real),
            accepted,
        )
    }

    @Test
    fun `an in-flight pairing neither delays nor reorders a concurrent real send`() = runTest {
        // U3-H. The class used to hold a mutex across the pair and claim "a concurrent send waits at
        // most GAP_MAX_MS" — false under multiple waiters, where the bound was per-hop, not total.
        // Real-first needs no lock, so the honest bound is ZERO: no virtual time passes between the
        // two real frames even though the first pairing is mid-gap. Restoring any lock around the
        // pair fails this, which is the mutation it exists to catch — and it now also covers the
        // teardown lock the class DOES hold: taking it anywhere before a publish, or holding it
        // across the gap, would put a real send behind another pair again.
        val worker = StandardTestDispatcher(testScheduler)
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, sleep = { delay(it) })
        val firstReal = Any()
        val secondReal = Any()

        launch(worker) { frames.add(firstReal); pairing.cover(textEnvelope(counter = 1)) }
        launch(worker) { frames.add(secondReal); pairing.cover(textEnvelope(counter = 2)) }
        runCurrent()

        assertEquals(
            "a real send waited on another pair's gap — cover traffic delayed it",
            listOf(firstReal, secondReal),
            frames.toList(),
        )

        advanceUntilIdle()
        assertEquals("both pairs did not complete", 4, frames.size)
        assertTrue("the second send overtook the first", frames.indexOf(firstReal) < frames.indexOf(secondReal))
    }

    // ── R-U3-3: uniform failure, and the provisioning trigger ───────────────────────────────

    @Test
    fun `an unprovisioned vault sends uncovered, provisions ONCE, then covers everything`() = runTest {
        var provisions = 0
        var provisioned = false
        val gate = CompletableDeferred<Unit>()
        val frames = mutableListOf<Any>()
        val pairing = pairing(
            frames,
            recipient = { if (provisioned) syntheticAccountId else null },
            provision = { provisions++; gate.await(); provisioned = true },
        )

        repeat(5) { pairing.record(textEnvelope(), frames) }
        runCurrent()
        assertEquals("provisioning is not triggered from the send path", 1, provisions)
        assertEquals("an unprovisioned vault emitted cover traffic", 0, decoysIn(frames).size)
        assertEquals("five uncovered real sends", 5, frames.size)

        gate.complete(Unit)
        advanceUntilIdle()

        frames.clear()
        repeat(5) { pairing.record(textEnvelope(), frames) }
        assertEquals("cover traffic did not start once the account existed", 5, decoysIn(frames).size)
        assertEquals(10, frames.size)

        // …and the path that spends a registration from the shared worldwide bucket is not re-entered.
        assertEquals("provisioning ran more than once in a session", 1, provisions)
    }

    @Test
    fun `stop cancels the provisioning job`() = runTest {
        var finished = false
        val frames = mutableListOf<Any>()
        val pairing = pairing(
            frames,
            recipient = { null },
            provision = { delay(60_000); finished = true },
        )
        pairing.record(textEnvelope(), frames)
        runCurrent()

        pairing.stop {}
        advanceUntilIdle()

        assertFalse("nothing decoy-related may outlive the session", finished)
        assertEquals("the real send is unaffected by teardown", listOf<Any>(Real), frames)
    }

    @Test
    fun `a back-off that expires mid-session still gets its attempt`() = runTest {
        // V3, and the defect class it belongs to: a new CALLER silently retiring a property another
        // unit pins. U1's own contract is "a back-off window that expires mid-session still gets its
        // one attempt" — a purely local deferral check is one CHECK, not the one ATTEMPT, so it does
        // not burn `Gate.attempted`. U3's wiring latched provisioning to ONCE PER SESSION, so the
        // single call landed inside the window, returned without provisioning, and was never made
        // again: cover traffic stayed off for the whole session even after the window expired.
        //
        // The latch now bounds CONCURRENT jobs, not attempts. The registration budget is unaffected
        // because it was never this latch's job — DecoyAccountProvisioner's runtime-scoped latch is
        // the guard that protects the shared worldwide bucket, and the cross-unit version of this
        // test (DecoyAccountProvisionerTest) drives that guard for real.
        var deferred = true
        var calls = 0
        var provisioned = false
        val frames = mutableListOf<Any>()
        val pairing = pairing(
            frames,
            recipient = { if (provisioned) syntheticAccountId else null },
            provision = {
                calls++
                if (!deferred) provisioned = true
            },
        )

        pairing.record(textEnvelope(), frames)
        advanceUntilIdle()
        assertEquals("provisioning is not triggered from the send path", 1, calls)
        assertEquals("an unprovisioned vault emitted cover traffic", 0, decoysIn(frames).size)

        // The window expires. Same session, same instance, no unlock in between.
        deferred = false
        pairing.record(textEnvelope(), frames)
        advanceUntilIdle()
        assertEquals(
            "the back-off expired mid-session and the wired path never tried again",
            2,
            calls,
        )

        frames.clear()
        pairing.record(textEnvelope(), frames)
        assertEquals("cover traffic never started after the window expired", 1, decoysIn(frames).size)
    }

    @Test
    fun `provisioning is never started after teardown`() = runTest {
        // R-U3-5, and the hole re-arming the latch could have opened: a released latch must not let
        // a send that slips past teardown spend a registration for a session that no longer exists.
        var calls = 0
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, recipient = { null }, provision = { calls++ })

        pairing.stop {}
        pairing.record(textEnvelope(), frames)
        advanceUntilIdle()

        assertEquals("a locked session started a provisioning attempt", 0, calls)
        assertEquals("the real send is unaffected by teardown", listOf<Any>(Real), frames)
    }

    // ── R-U3-3 + R-U3-5: teardown owns the pairings it admitted ─────────────────────────────

    @Test
    fun `teardown drains an in-flight pairing BEFORE the socket dies`() = runTest {
        // V2, the round-2 defect, driven through the real teardown entry point and a socket that
        // really goes dead. Round 2's MessagingCoordinator.stop() called ws.disconnect() FIRST and
        // coverTraffic.stop() second, and stop() cancelled only the provisioning job — so a vault
        // lock landing in a drawn gap put a lone real frame and then a TLS close on the wire. That
        // is a deterministic, recognisable class of unpaired real sends correlated with lock,
        // teardown and backgrounding: exactly what R-U3-3 calls worse than no cover at all.
        //
        // The invalidation is now passed INTO stop() rather than called beside it, so the drain
        // cannot be reordered after it by editing the caller.
        val frames = mutableListOf<Any>()
        val socket = DyingSocket(frames)
        val pairing = pairing(frames, send = socket::send, sleep = { delay(it) })

        val job = launch { pairing.record(textEnvelope(), frames) }
        runCurrent()
        assertEquals("the real frame should already be out, mid-gap", listOf<Any>(Real), frames)

        pairing.stop { socket.disconnect() }

        assertFalse("the socket was not invalidated by teardown", socket.connected)
        assertEquals("teardown lost the cover frame — the real frame is marked", 2, frames.size)
        assertTrue("the real frame did not go first", frames.first() === Real)

        // The sleeping coroutine still unwinds, and must not emit a SECOND cover frame.
        job.cancelAndJoin()
        assertEquals("the cover frame was emitted twice", 2, frames.size)
    }

    @Test
    fun `a pairing admitted after teardown emits nothing at all`() = runTest {
        // The other half of R-U3-5: once the transport is invalid, cover traffic is over. A frame
        // emitted here would be a decoy for a real send the dead socket already refused — and a
        // locked vault must not even DO the work: no vault read, no identity read, no keypair.
        val frames = mutableListOf<Any>()
        val socket = DyingSocket(frames)
        var coverWork = 0
        val pairing = pairing(
            frames,
            recipient = { coverWork++; syntheticAccountId },
            send = socket::send,
            sleep = { delay(it) },
        )

        pairing.stop { socket.disconnect() }
        pairing.record(textEnvelope(), frames)
        advanceUntilIdle()

        assertEquals("a locked session emitted cover traffic", listOf<Any>(Real), frames)
        assertEquals("a locked session read the vault to build a decoy it can never send", 0, coverWork)
    }

    @Test
    fun `a pairing the drain already emitted does not emit again when it wakes`() {
        // Exactly-once, in the ONE window where it can actually be violated: stop()'s drain releases
        // the lock while it waits for a pairing that is still BUILDING, and a pairing it has already
        // emitted can wake inside that window with the transport still valid. Nothing but membership
        // of the register stops it emitting a second time — and a duplicate is not harmless: three
        // frames where the pattern is two marks the send exactly the way one frame does.
        val frames = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val socket = DyingSocket(frames)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val builds = java.util.concurrent.atomic.AtomicInteger(0)
        val slowBuildEntered = CountDownLatch(1)
        val firstSleeping = CountDownLatch(1)
        try {
            val pairing = DecoySendPairing(
                scope = scope,
                sender = ::sender,
                recipient = {
                    // The SECOND pairing is the one caught mid-build by teardown.
                    if (builds.incrementAndGet() == 2) {
                        slowBuildEntered.countDown()
                        Thread.sleep(70)
                    }
                    syntheticAccountId
                },
                send = socket::send,
                provision = {},
                // A fixed gap, longer than it takes teardown to start and shorter than the slow
                // build: the first pairing is guaranteed to wake INSIDE the drain's wait.
                sleep = { firstSleeping.countDown(); delay(45) },
                random = seeded(3),
            )
            val first = scope.launch { pairing.record(textEnvelope(counter = 1), frames) }
            assertTrue(firstSleeping.await(5, TimeUnit.SECONDS))
            val second = scope.launch { pairing.record(textEnvelope(counter = 2), frames) }
            assertTrue(slowBuildEntered.await(5, TimeUnit.SECONDS))

            pairing.stop { socket.disconnect() }
            runBlocking { first.join(); second.join() }

            assertEquals("two covered sends are exactly four frames", 4, frames.size)
            assertEquals("a cover frame was emitted twice", 2, decoysIn(frames.toList()).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `teardown waits for a pairing whose cover frame is still being BUILT`() {
        // The half of the drain that a "cancel everything sleeping" fix would miss. A pairing is
        // admitted before its frame exists, so between admission and the frame reaching the drain
        // there is a window — the vault read, the identity read, a keypair generation. Abandoning a
        // pairing caught there leaves the same marked real frame as losing one mid-gap.
        //
        // Real threads on purpose: stop() blocks, and the point is that it blocks for THIS.
        // buildCover is non-suspending, so the wait can only ever stand behind CPU work, never I/O
        // — which is what makes a bounded wait safe here.
        val frames = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val socket = DyingSocket(frames)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val buildEntered = CountDownLatch(1)
        val teardownReleased = CountDownLatch(1)
        try {
            val pairing = DecoySendPairing(
                scope = scope,
                sender = ::sender,
                recipient = {
                    buildEntered.countDown()
                    teardownReleased.await(5, TimeUnit.SECONDS)
                    // Still inside the build when stop() takes the lock and finds this pairing
                    // admitted but unresolved.
                    Thread.sleep(10)
                    syntheticAccountId
                },
                send = socket::send,
                provision = {},
                random = seeded(9),
            )
            val sending = scope.launch(Dispatchers.Default) { pairing.record(textEnvelope(), frames) }
            assertTrue("the pairing never started building", buildEntered.await(5, TimeUnit.SECONDS))
            teardownReleased.countDown()

            pairing.stop { socket.disconnect() }

            assertFalse(socket.connected)
            assertEquals("a pairing caught mid-build was abandoned, not drained", 2, frames.size)
            assertTrue("the real frame did not go first", frames.first() === Real)
            runBlocking { sending.cancelAndJoin() }
            assertEquals("the cover frame was emitted twice", 2, frames.size)
        } finally {
            teardownReleased.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `the drain is bounded - a pairing that never resolves cannot hold the socket open`() {
        // The backstop, asserted rather than assumed: teardown runs under the app's transport lock
        // on a user-visible path, so an unresolvable pairing must not stall it. The clock is a seam
        // so the bound is tested without spending it.
        val frames = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val socket = DyingSocket(frames)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wedged = CountDownLatch(1)
        val admitted = CountDownLatch(1)
        var clock = 0L
        try {
            val pairing = DecoySendPairing(
                scope = scope,
                sender = ::sender,
                recipient = {
                    admitted.countDown()
                    wedged.await(10, TimeUnit.SECONDS)
                    syntheticAccountId
                },
                send = socket::send,
                provision = {},
                random = seeded(9),
                // Time only moves when stop() consults it, so the deadline is already spent the
                // second time round the drain loop.
                nanoTime = { clock.also { clock += DecoySendPairing.DRAIN_TIMEOUT_MS * 1_000_000L } },
            )
            scope.launch(Dispatchers.Default) { pairing.record(textEnvelope(), frames) }
            assertTrue(admitted.await(5, TimeUnit.SECONDS))

            pairing.stop { socket.disconnect() }

            assertFalse("the drain deadline did not invalidate the transport", socket.connected)
            assertEquals("a wedged pairing was not skipped", listOf<Any>(Real), frames.toList())
        } finally {
            wedged.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `CoverTraffic NONE emits nothing and still tears the transport down`() = runTest {
        var invalidated = 0
        CoverTraffic.NONE.cover(textEnvelope())
        CoverTraffic.NONE.stop { invalidated++ }

        assertEquals("cover-traffic-off must still disconnect the socket", 1, invalidated)
    }

    // ── the call site itself ────────────────────────────────────────────────────────────────

    @Test
    fun `the coordinator never invalidates the transport outside the cover-traffic teardown`() {
        // MessagingCoordinator cannot be constructed off-device, so the one thing this suite cannot
        // reach behaviourally is the CALL SITE — and the call site is where the round-2 defect
        // actually lived (`ws.disconnect()` above `coverTraffic.stop()`). Passing the invalidation
        // INTO stop() makes the ordering structural, and this pins the structure: every disconnect
        // in the coordinator goes through the seam, so there is no second one to get wrong.
        val source = coordinatorSource()
        val stray = source.lines()
            .withIndex()
            .filter { (_, line) -> "ws.disconnect()" in line && "coverTraffic.stop {" !in line }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }

        assertEquals(
            "a transport invalidation outside CoverTraffic.stop — teardown can strand a pairing",
            emptyList<String>(),
            stray,
        )
        assertTrue(
            "the cover-traffic teardown is not wired to the disconnect at all",
            "coverTraffic.stop { ws.disconnect() }" in source,
        )
    }

    @Test
    fun `the coordinator publishes the real frame before it calls the cover seam`() {
        // The other half of what the call site owns, and the half V1 turns on: the seam can no
        // longer be handed a real send (asserted by reflection above), but the two statements could
        // still be written the wrong way round — `coverTraffic.cover(envelope)` above the publish
        // tail would put a decoy on the wire first and cover-side work back in front of the handoff.
        // Nothing else in this suite can see the call site, so it is read.
        val lines = coordinatorSource().lines()
        val callSites = lines.indices.filter { "coverTraffic.cover(" in lines[it] }
        assertEquals("the cover seam is not called from all three send paths", 3, callSites.size)

        for (site in callSites) {
            val previousCode = (site - 1 downTo 0)
                .map { lines[it].trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
            assertTrue(
                "line ${site + 1}: the cover seam is entered before the real publish tail, " +
                    "preceded by <$previousCode>",
                previousCode != null &&
                    (previousCode.startsWith("publishOutgoing(") || previousCode.startsWith("publishReceipt(")),
            )
        }
    }

    private fun coordinatorSource(): String {
        val relative = "src/main/java/com/zitrone/app/MessagingCoordinator.kt"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("MessagingCoordinator.kt not found from ${System.getProperty("user.dir")}")
    }
}
