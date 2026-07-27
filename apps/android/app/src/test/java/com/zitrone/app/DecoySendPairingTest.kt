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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** Run one pairing, recording the real publish in [frames] alongside whatever the socket got. */
    private suspend fun DecoySendPairing.record(cover: MessageEnvelope, frames: MutableList<Any>) =
        paired(cover) { frames.add(Real) }

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
        // It now cannot: the publish is the first statement of `paired`.
        var published = 0
        val pairing = pairing(mutableListOf(), send = { throw CancellationException("cover frame") })
        try {
            pairing.paired(textEnvelope()) { published++ }
        } catch (_: CancellationException) {
            // The cover frame's cancellation still propagates; it just arrives too late to matter.
        }

        assertEquals("cover traffic swallowed a real send", 1, published)
    }

    // ── R-U3-1 edges the round-1 review left uncovered (U3-I) ───────────────────────────────

    @Test
    fun `at the only suspension point the real frame is already on the wire`() = runTest {
        // U3-A, process death. A process cannot be killed mid-statement in a way this suite can
        // observe, but it CAN only be killed at a suspension point — so the property that makes
        // process death harmless is "at every suspension point after the durable barrier, the real
        // frame has already been handed to the socket". There is exactly one suspension point in
        // this class, the drawn gap, and the sleep seam IS that point: asserting here asserts the
        // property exhaustively rather than by sampling.
        val frames = mutableListOf<Any>()
        var atSuspension: List<Any>? = null
        val pairing = pairing(frames, sleep = { atSuspension = frames.toList() })
        pairing.record(textEnvelope(), frames)

        assertEquals(
            "process death at the gap would lose a real message whose ratchet already advanced",
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
                pairing.paired(textEnvelope()) {
                    // The coordinator's real tail, in miniature.
                    contactWasLiveAtPublish = !contactDeleted
                    frames.add(Real)
                }
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
        pairing.paired(textEnvelope()) { spend(Real) }

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
        // pair fails this, which is the mutation it exists to catch.
        val worker = StandardTestDispatcher(testScheduler)
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, sleep = { delay(it) })
        val firstReal = Any()
        val secondReal = Any()

        launch(worker) { pairing.paired(textEnvelope(counter = 1)) { frames.add(firstReal) } }
        launch(worker) { pairing.paired(textEnvelope(counter = 2)) { frames.add(secondReal) } }
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

        pairing.stop()
        advanceUntilIdle()

        assertFalse("nothing decoy-related may outlive the session", finished)
        assertEquals("the real send is unaffected by teardown", listOf<Any>(Real), frames)
    }

    @Test
    fun `CoverTraffic NONE runs the real tail exactly once and emits nothing`() = runTest {
        var published = 0
        CoverTraffic.NONE.paired(textEnvelope()) { published++ }
        CoverTraffic.NONE.stop()

        assertEquals(1, published)
    }
}
