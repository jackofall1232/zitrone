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
import kotlinx.coroutines.asCoroutineDispatcher
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

        /**
         * [Any], not [MessageEnvelope], so the REAL frame can go through the same socket as the
         * cover frame — which is what the fix-round-4 tests need in order to model the coordinator's
         * publish tail (`if (publishOutgoing(...)) cover(...)`) rather than assuming it succeeded.
         * Kotlin function types are contravariant in their parameters, so `(Any) -> Boolean` still
         * satisfies the seam's `(MessageEnvelope) -> Boolean`.
         */
        fun send(frame: Any): Boolean = synchronized(this) {
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
        //
        // ROUND 4: this used to forbid `kotlin.Function` parameters, which pinned exactly ONE shape.
        // A custom SAM (`fun interface RealPublish`), a `Runnable`, or a differently named method
        // all walked straight past it. So the whole INTERFACE is pinned instead — every method, by
        // name and by parameter list. Adding a method, renaming one, or giving `cover` a second
        // parameter of any type whatsoever fails here and has to be argued for on the record.
        val actual = CoverTraffic::class.java.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge }
            .map { m ->
                // The trailing Continuation is the compiler's, not the interface's.
                val params = m.parameterTypes
                    .map { it.name }
                    .filter { it != "kotlin.coroutines.Continuation" }
                "${m.name}(${params.joinToString()})"
            }
            .sorted()
        assertEquals(
            "CoverTraffic's surface changed. Every parameter here is something the seam could be " +
                "handed; a real send among them is the round-2 defect returning under a new type.",
            listOf(
                "cover(com.zitrone.app.data.MessageEnvelope)",
                "quiesce(kotlin.jvm.functions.Function0)",
                "stop(kotlin.jvm.functions.Function0)",
            ),
            actual,
        )
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
    fun `a pairing the drain already emitted does not emit again when it wakes`() = runTest {
        // Exactly-once. The drain emits a sleeping pairing's frame and retires it from the register;
        // when the coroutine later wakes (or unwinds through cancellation) its `finally` must find
        // nothing to emit. A duplicate is not harmless: three frames where the pattern is two marks
        // the send exactly the way one frame does.
        val frames = mutableListOf<Any>()
        val socket = DyingSocket(frames)
        val pairing = pairing(frames, send = socket::send, sleep = { delay(it) })

        val first = launch { pairing.record(textEnvelope(counter = 1), frames) }
        val second = launch { pairing.record(textEnvelope(counter = 2), frames) }
        runCurrent()
        assertEquals("both real frames should be out, both pairings mid-gap", 2, frames.size)

        pairing.stop { socket.disconnect() }
        first.cancelAndJoin()
        second.cancelAndJoin()

        assertEquals("two covered sends are exactly four frames", 4, frames.size)
        assertEquals("a cover frame was emitted twice", 2, decoysIn(frames.toList()).size)
    }

    // ── W4/W2 (fix round 4): teardown serialised on the send worker ─────────────────────────

    /**
     * The coordinator's `confined` worker, modelled as what it is: ONE thread that every send and
     * (since fix round 4) every teardown runs on.
     */
    private fun singleWorker() = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Test
    fun `teardown serialised on the send worker never strands a pairing between handoff and admission`() {
        // W4, and the refutation of round 3's impossibility claim. Round 3 declared a residual: a
        // teardown landing between `ws.sendMessage` returning and the pairing registering leaves an
        // unpaired real frame, and closing it seemed to need a lock in front of the real send.
        //
        // It does not. Terminal teardown is ENQUEUED ON THE WORKER THE SENDS ALREADY RUN ON, so it
        // cannot land inside a send's slice at all — there is no suspension point between the
        // publish tail and the admission, so the worker is never handed over in between. This is the
        // production shape, reproduced exactly: the publish tail is a real socket write, the cover
        // call is guarded by its result (W1), and `stop` is another task on the same single thread.
        //
        // The property asserted is the whole U3 gate in one line: **a send either put NOTHING on the
        // wire, or put a PAIR on it.** Never a lone real frame, and (W1) never a lone decoy.
        val worker = singleWorker()
        val dispatcher = worker.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            repeat(200) { iteration ->
                val frames = java.util.Collections.synchronizedList(mutableListOf<Any>())
                val socket = DyingSocket(frames)
                val pairing = DecoySendPairing(
                    scope = scope,
                    sender = ::sender,
                    recipient = { syntheticAccountId },
                    send = socket::send,
                    provision = {},
                    // A real gap, so teardown genuinely lands mid-pair rather than after it.
                    sleep = { delay(it) },
                    random = seeded(iteration.toLong()),
                    provisionContext = EmptyCoroutineContext,
                )
                val sending = scope.launch {
                    // The coordinator's slice: the non-suspending publish tail, then the cover seam
                    // ONLY IF the relay took the frame. No suspension point between them.
                    if (socket.send(Real)) pairing.cover(textEnvelope())
                }
                val torn = CountDownLatch(1)
                // Enqueued on the SAME worker, exactly as MessagingCoordinator.stop() does it.
                scope.launch { pairing.stop { socket.disconnect() }; torn.countDown() }
                assertTrue("teardown never ran", torn.await(5, TimeUnit.SECONDS))
                runBlocking { sending.cancelAndJoin() }

                val recorded = frames.toList()
                assertTrue(
                    "iteration $iteration: teardown stranded a real frame — got $recorded",
                    recorded.isEmpty() || recorded == listOf(Real, decoysIn(recorded).single()),
                )
            }
        } finally {
            scope.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun `the drain has no wall clock - a slow build cannot be abandoned by a deadline`() {
        // W2. Round 3's drain waited up to 100 ms for a pairing that was admitted but not yet built,
        // and abandoned it after that — so slow cryptographic generation, scheduler starvation or a
        // stalled `recipient()` produced a deterministically UNPAIRED real frame at teardown, which
        // is precisely what the drain exists to prevent. "Non-suspending" bounds suspension, not
        // time, so nothing in the design stopped it.
        //
        // The register now only ever holds BUILT pairings, so there is nothing left to wait for and
        // no deadline to overrun. Driven with a build that takes far longer than the old 100 ms
        // bound, on the confinement worker: teardown queues behind the whole build and the pairing
        // survives. This test would have failed against round 3.
        val worker = singleWorker()
        val dispatcher = worker.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val frames = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val socket = DyingSocket(frames)
        val buildEntered = CountDownLatch(1)
        try {
            val pairing = DecoySendPairing(
                scope = scope,
                sender = ::sender,
                recipient = {
                    buildEntered.countDown()
                    // Three times the abandoned deadline, without suspending once.
                    Thread.sleep(300)
                    syntheticAccountId
                },
                send = socket::send,
                provision = {},
                sleep = { delay(it) },
                random = seeded(9),
                provisionContext = EmptyCoroutineContext,
            )
            val sending = scope.launch { if (socket.send(Real)) pairing.cover(textEnvelope()) }
            assertTrue("the build never started", buildEntered.await(5, TimeUnit.SECONDS))
            val torn = CountDownLatch(1)
            scope.launch { pairing.stop { socket.disconnect() }; torn.countDown() }
            assertTrue("teardown never ran", torn.await(5, TimeUnit.SECONDS))
            runBlocking { sending.cancelAndJoin() }

            assertFalse("the transport was not invalidated", socket.connected)
            assertEquals("a slow build was abandoned at teardown — the real frame is marked", 2, frames.size)
            assertTrue("the real frame did not go first", frames.first() === Real)
        } finally {
            scope.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun `stop cannot slip between the provisioning CAS and the job it has to cancel`() {
        // W5, and it is driven DETERMINISTICALLY rather than by racing threads and hoping.
        //
        // Round 3's `ensureProvisioning` checked `transportInvalid` under the teardown lock,
        // RELEASED it, won the CAS, and only then assigned `provisionJob`. A `stop()` landing in
        // that gap saw a null handle, cancelled nothing, invalidated the transport and returned —
        // and the job then started AFTER teardown: a coroutine outliving its session, free to spend
        // a scarce registration from the shared worldwide bucket and to touch a closing vault
        // runtime. Check → CAS → assign now all happen under the lock.
        //
        // The window is held open from inside the launch itself: `job.start()` on a LAZY job
        // dispatches, so a dispatcher that parks turns "the instant between CAS and assign" into a
        // gate the test controls. With the fix `stop()` must BLOCK on that gate; without it, it
        // sails through and reports a teardown that cancelled nothing.
        val dispatching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatching.countDown()
                release.await(5, TimeUnit.SECONDS)
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var provisionCompleted = false
        try {
            val frames = java.util.Collections.synchronizedList(mutableListOf<Any>())
            val pairing = DecoySendPairing(
                scope = scope,
                sender = ::sender,
                recipient = { null },
                send = { frames.add(it); true },
                provision = { delay(60_000); provisionCompleted = true },
                random = seeded(1),
                provisionContext = gate,
            )
            scope.launch(Dispatchers.Default) { pairing.record(textEnvelope(), frames) }
            assertTrue("provisioning was never triggered", dispatching.await(5, TimeUnit.SECONDS))

            val stopped = CountDownLatch(1)
            kotlin.concurrent.thread { pairing.stop {}; stopped.countDown() }
            assertFalse(
                "stop() completed while ensureProvisioning still had a job to assign — it cancelled " +
                    "nothing and the job outlives the session",
                stopped.await(300, TimeUnit.MILLISECONDS),
            )

            release.countDown()
            assertTrue("teardown never completed", stopped.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertFalse("nothing decoy-related may outlive the session", provisionCompleted)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    // ── W3: the transport SWAP is drained too, and the session survives it ──────────────────

    @Test
    fun `a transport swap drains the pairings it interrupts instead of splitting them`() = runTest {
        // W3, ruled P1 by the third lens. `ZitroneApp.applyTransportLocked` used to disconnect and
        // redial directly on a Tor/I2P toggle, so a pairing sleeping in its drawn gap had its real
        // frame on the OLD connection and its cover frame on the NEW one — or nowhere. A SPLIT pair
        // is a stronger signal than a missing cover frame: two identical-length frames milliseconds
        // apart straddling a TLS teardown and reconnect let an observer link frames across a
        // connection boundary, bind them to an observable infrastructure event, and correlate them
        // with the user changing their anonymity transport.
        val frames = mutableListOf<Any>()
        val socket = DyingSocket(frames)
        val pairing = pairing(frames, send = socket::send, sleep = { delay(it) })

        val job = launch { pairing.record(textEnvelope(), frames) }
        runCurrent()
        assertEquals("the real frame should be out, the pairing mid-gap", listOf<Any>(Real), frames)

        var swapped = 0
        pairing.quiesce { swapped++; socket.disconnect() }

        assertEquals("the swap did not run", 1, swapped)
        assertEquals("the pair was split across the transport swap", 2, frames.size)
        assertTrue("the real frame did not go first", frames.first() === Real)
        job.cancelAndJoin()
        assertEquals("the cover frame was emitted twice", 2, frames.size)
    }

    @Test
    fun `a transport swap is NOT a teardown - pairing resumes over the new socket`() = runTest {
        // The half that distinguishes quiesce from stop, and the mutation it exists to catch:
        // implementing quiesce by delegating to stop would drain correctly and then silently kill
        // cover traffic for the rest of the session — uniformly-off cover after a Tor toggle, which
        // R-U3-3 accepts, but achieved by a bug and never noticed.
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames)

        pairing.quiesce {}
        pairing.record(textEnvelope(), frames)

        assertEquals("a transport swap silently ended cover traffic for the session", 1, decoysIn(frames).size)
        assertEquals(2, frames.size)
    }

    @Test
    fun `CoverTraffic NONE emits nothing and still tears the transport down`() = runTest {
        var invalidated = 0
        var swapped = 0
        CoverTraffic.NONE.cover(textEnvelope())
        CoverTraffic.NONE.stop { invalidated++ }
        CoverTraffic.NONE.quiesce { swapped++ }

        assertEquals("cover-traffic-off must still disconnect the socket", 1, invalidated)
        assertEquals("cover-traffic-off must still swap the transport", 1, swapped)
    }

    // ── the call site itself ────────────────────────────────────────────────────────────────

    @Test
    fun `every socket disconnect in the app goes through cover traffic - the coordinator AND ZitroneApp`() {
        // ROUND 4. Round 3's version of this read ONE file, matched ONE exact line of source, and
        // deliberately excluded the second disconnect owner it knew about
        // (`ZitroneApp.applyTransportLocked`). The third lens ruled that carve-out out: a guard that
        // excludes the known-bad path converts a latent defect into a KNOWN, UNMONITORED violation,
        // with no alarm if the path widens. So the exclusion is gone, the path is fixed, and this
        // now reads both owners.
        //
        // It is also format-tolerant now, which the old one was not: it normalises whitespace and
        // then walks braces, so a correct multi-line lambda passes and a helper that hides the
        // disconnect behind another function fails — which is the right way round, because a second
        // disconnect owner is exactly the defect.
        val allowedOwners = listOf(
            "coverTraffic.stop {",
            "coverTraffic.quiesce {",
            "coordinator.reconnectTransport {",
        )
        val stray = mutableListOf<String>()
        for ((name, source) in listOf(
            "MessagingCoordinator.kt" to coordinatorSource(),
            "ZitroneApp.kt" to appSource("ZitroneApp.kt"),
        )) {
            val code = stripComments(source).replace(Regex("\\s+"), " ")
            var from = 0
            while (true) {
                val at = code.indexOf("disconnect()", from)
                if (at < 0) break
                from = at + 1
                val opener = enclosingLambdaOpener(code, at)
                if (allowedOwners.none { opener.endsWith(it) }) {
                    stray += "$name: disconnect() inside <${opener.takeLast(60)}>"
                }
            }
        }
        assertEquals(
            "a socket disconnect that cover traffic does not own — it can strand or SPLIT a pairing",
            emptyList<String>(),
            stray,
        )
        // …and the two owners are really wired, so deleting the disconnect entirely does not pass.
        val coordinator = stripComments(coordinatorSource()).replace(Regex("\\s+"), " ")
        assertTrue(
            "the cover-traffic teardown is not wired to the disconnect at all",
            "coverTraffic.stop { ws.disconnect() }" in coordinator,
        )
        assertTrue(
            "the transport swap does not go through the coordinator's drain",
            "coordinator.reconnectTransport {" in
                stripComments(appSource("ZitroneApp.kt")).replace(Regex("\\s+"), " "),
        )
    }

    @Test
    fun `the coordinator covers a send only when the relay actually took the real frame`() {
        // W1 — THE FINDING THIS TRIPWIRE ITSELF MISSED LAST ROUND, which is why it is rewritten
        // rather than kept. Round 3's version asserted that the statement above `coverTraffic.cover(`
        // was a publish tail. That is statement ADJACENCY, and adjacency was true while the defect
        // was live: `publishOutgoing` returned Unit, so all three of its outcomes — envelope
        // discarded (contact deleted), envelope refused (socket down), envelope handed off — ran
        // cover. Two of the three emitted a decoy with NO REAL FRAME BEHIND IT: a frame the user
        // never generated, which marks the pair exactly the way a lone real frame does.
        //
        // What is pinned now is the DEPENDENCE, not the adjacency: every cover call is the body of
        // an `if` on a publish tail's result, and both publish tails return that result from
        // `ws.sendMessage` and from nowhere else.
        val code = stripComments(coordinatorSource()).replace(Regex("\\s+"), " ")

        val guarded = Regex("if \\((publishOutgoing|publishReceipt)\\([^()]*\\)\\) coverTraffic\\.cover\\(")
            .findAll(code).count()
        val total = Regex("coverTraffic\\.cover\\(").findAll(code).count()
        assertEquals("the cover seam is not called from all three send paths", 3, total)
        assertEquals(
            "a cover call that does not depend on the real frame having been handed to the relay — " +
                "it can emit a decoy for a send that was discarded or refused",
            total,
            guarded,
        )

        // The guard is only worth anything if the value it tests is the handoff. Both tails must
        // declare Boolean and must return `true` from exactly one place: the `ws.sendMessage` branch.
        for (tail in listOf("publishOutgoing", "publishReceipt")) {
            val signature = code.substringAfter("private fun $tail(").substringBefore("{")
            assertTrue(
                "$tail no longer reports whether the frame was handed off, so the guard above is " +
                    "testing something other than the handoff",
                signature.trimEnd().endsWith("): Boolean"),
            )
            val body = bodyOf(code, "private fun $tail(")
            assertEquals(
                "$tail has a `return true` that the ws.sendMessage branch does not own",
                1,
                Regex("return true").findAll(body).count(),
            )
            assertEquals(
                "$tail returns true from somewhere other than the ws.sendMessage branch",
                1,
                Regex("if \\(ws\\.sendMessage\\(envelope\\)\\) \\{ return true").findAll(body).count(),
            )
        }
    }

    @Test
    fun `terminal teardown is dispatched onto the send worker, not run beside it`() {
        // W4's construction, pinned at the one place this suite cannot reach behaviourally. The
        // serialisation that closes the round-3 residual is not a property of DecoySendPairing — it
        // is a property of WHERE the coordinator runs the teardown. Running `coverTraffic.stop`
        // straight off the calling thread again would restore the race silently, with every
        // behavioural test in this file still green.
        val code = stripComments(coordinatorSource()).replace(Regex("\\s+"), " ")

        // Exactly one place stops cover traffic, and it is a named method — so there is one thing to
        // dispatch correctly rather than one per teardown path.
        assertEquals(
            "cover traffic is stopped from more than one place",
            1,
            Regex("coverTraffic\\.stop \\{").findAll(code).count(),
        )
        assertTrue(
            "the teardown helper no longer dispatches onto the confinement worker",
            "scope.launch(confined + NonCancellable) {" in
                bodyOf(code, "private fun runTerminalTeardownOnConfinedWorker("),
        )
        // stop() must go through the dispatch; the account-delete path is ALREADY on the worker and
        // must not (dispatching to the worker from the worker and blocking on it stalls for the
        // whole quiesce bound before falling back).
        val stopBody = bodyOf(code, "fun stop() {")
        assertTrue(
            "MessagingCoordinator.stop() runs the teardown on the calling thread again",
            "runTerminalTeardownOnConfinedWorker(::coverTeardown)" in stopBody,
        )
        assertTrue(
            "the account-delete teardown dispatches onto the worker it is already running on",
            "coverTeardown()" in bodyOf(code, "fun deleteAccountAndWipe(") &&
                "runTerminalTeardownOnConfinedWorker" !in bodyOf(code, "fun deleteAccountAndWipe("),
        )
        // And step 1 of R-U3-5 is armed before any of it, on both teardown paths.
        for (path in listOf("fun stop() {", "fun deleteAccountAndWipe(")) {
            val body = bodyOf(code, path)
            assertTrue(
                "$path does not stop admitting new real sends before tearing cover traffic down",
                body.indexOf("acceptingSends = false") >= 0 &&
                    body.indexOf("acceptingSends = false") < body.indexOf("coverTeardown"),
            )
        }
        // …and the gate is actually consulted, on every send path, BEFORE the durability barrier —
        // which is what makes it free of R-U3-1: it is nowhere near the barrier→socket window, and a
        // send refused here has advanced no ratchet and written nothing.
        for (path in listOf(
            "suspend fun deliverText(",
            "suspend fun deliverAttachment(",
            "fun sendReadReceipt(",
        )) {
            val body = bodyOf(code, path)
            val gate = body.indexOf("!acceptingSends")
            assertTrue("$path does not refuse new sends once teardown has begun", gate >= 0)
            assertTrue(
                "$path checks the send gate AFTER the durable barrier — too late to be step 1",
                gate < body.indexOf("flushSendRatchet("),
            )
        }
    }

    // ── source-tripwire helpers ─────────────────────────────────────────────────────────────

    /** Strip `//` line comments and `/* */` blocks so a tripwire cannot be satisfied by a comment. */
    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    /** The text immediately before the innermost `{` enclosing [at], in whitespace-normalised code. */
    private fun enclosingLambdaOpener(code: String, at: Int): String {
        var depth = 0
        for (i in at - 1 downTo 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) return code.substring(0, i + 1) else depth--
            }
        }
        return ""
    }

    /**
     * The brace-matched body of the declaration starting at [header], in normalised code. The body's
     * `{` is the first one at PAREN depth zero, so a default lambda argument in the parameter list
     * (`onNotConfirmed: (Boolean) -> Unit = {}`) is not mistaken for the body.
     */
    private fun bodyOf(code: String, header: String): String {
        val start = code.indexOf(header)
        assertTrue("declaration not found: $header", start >= 0)
        var parens = 0
        var open = -1
        for (i in start until code.length) {
            when (code[i]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> if (parens == 0) { open = i; break }
            }
            if (open >= 0) break
        }
        assertTrue("no body found for: $header", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
        }
        throw AssertionError("unbalanced braces after $header")
    }

    private fun coordinatorSource(): String = appSource("MessagingCoordinator.kt")

    private fun appSource(fileName: String): String {
        val relative = "src/main/java/com/zitrone/app/$fileName"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("$fileName not found from ${System.getProperty("user.dir")}")
    }
}
