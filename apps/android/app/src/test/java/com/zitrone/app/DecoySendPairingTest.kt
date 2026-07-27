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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * THE U3 GATE: **a covered send puts two frames of the same length on the wire, in an order the
 * observer cannot predict, and NOTHING that happens on the cover side can cost the real send.**
 *
 * The three properties are tested three different ways on purpose:
 *
 *  - **order and gap** are statistical, per spec §4.3 R-U3-2 ("pinned by a statistical test over
 *    many sends, not by reading the code"), so they are measured over thousands of sends. The
 *    generator is a seeded [SecureRandom], which fixes the SAMPLE and not the mechanism: every
 *    defect these tests exist to catch — a constant order, an alternating one, a biased coin, a
 *    fixed gap, a gap drawn differently per branch — is a property of the mechanism and shows up
 *    whatever the seed is. A separate test covers what a seeded generator cannot: that production's
 *    default source is not itself a fixed stream.
 *  - **R-U3-1** is tested by fault injection at every point that can fail — the builder refusing,
 *    the identity missing, the vault section unreadable, the socket throwing, the scope cancelled
 *    inside the drawn gap — always asking the same question: did the real publish still happen,
 *    exactly once.
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

    // ── R-U3-2: the order ───────────────────────────────────────────────────────────────────

    @Test
    fun `the frame order is uniformly random and independent across many sends`() = runTest {
        val n = 4_000
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, random = seeded(20260727))
        val decoyFirst = BooleanArray(n)
        repeat(n) { i ->
            frames.clear()
            pairing.record(textEnvelope(), frames)
            assertEquals("a send that was not a pair", 2, frames.size)
            decoyFirst[i] = frames.first() !== Real
        }

        val heads = decoyFirst.count { it }
        val p = heads.toDouble() / n
        val sigma = sqrt(0.25 / n)
        // 4σ. A coin at p = 0.55 — a bias an observer could exploit over one conversation — is 6σ
        // out at this n and fails; the generator is seeded, so this is not itself a coin flip.
        assertTrue(
            "decoy-first fraction $p is not 0.5 within 4σ (${4 * sigma})",
            abs(p - 0.5) < 4 * sigma,
        )

        // The fraction alone cannot see an ALTERNATING order, which is perfectly predictable and
        // lands at exactly 0.5. A runs test can: alternating gives n runs, independence gives ~n/2.
        var runs = 1
        for (i in 1 until n) if (decoyFirst[i] != decoyFirst[i - 1]) runs++
        val k = heads.toDouble()
        val expectedRuns = 1 + 2 * k * (n - k) / n
        val runsSigma = sqrt(2 * k * (n - k) * (2 * k * (n - k) - n) / (n.toDouble() * n * (n - 1)))
        assertTrue(
            "run count $runs is not independent-looking (expected $expectedRuns ± ${4 * runsSigma})",
            abs(runs - expectedRuns) < 4 * runsSigma,
        )
    }

    @Test
    fun `the DEFAULT generator is unpredictable, not a fixed stream`() = runTest {
        // The seeded tests prove the mechanism consumes its draws correctly; they cannot prove
        // production does not ship a constant or a fixed seed. Two default-constructed instances
        // must disagree — and note WHY it has to be a cryptographic source: the gap is directly
        // observable on the wire, so a predictable generator would let an observer recover its state
        // from measured gaps and then predict the ORDER bit, the one value the mechanism hides.
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
            val orders = mutableListOf<Boolean>()
            repeat(64) {
                frames.clear()
                pairing.record(textEnvelope(), frames)
                orders.add(frames.first() !== Real)
            }
            orders.toList() to gaps.toList()
        }
        assertNotEquals("two default instances drew the same order sequence", samples[0].first, samples[1].first)
        assertNotEquals("two default instances drew the same gap sequence", samples[0].second, samples[1].second)
    }

    // ── R-U3-2: the gap ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the gap is drawn per send, bounded, uniform, and independent of the order`() = runTest {
        val n = 4_000
        val frames = mutableListOf<Any>()
        val decoyFirstGaps = mutableListOf<Long>()
        val realFirstGaps = mutableListOf<Long>()
        var drawn: Long?
        val pairing = pairing(frames, random = seeded(4242), sleep = { drawn = it })
        repeat(n) {
            frames.clear()
            drawn = null
            pairing.record(textEnvelope(), frames)
            val gap = drawn!!
            if (frames.first() === Real) realFirstGaps.add(gap) else decoyFirstGaps.add(gap)
        }
        val gaps = decoyFirstGaps + realFirstGaps

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

        // The sharp one: if the branches drew from different distributions the OBSERVABLE gap would
        // identify the UNOBSERVABLE order, and same-length frames would stop helping.
        assertTrue(
            "the gap distribution differs by branch: ${decoyFirstGaps.average()} vs ${realFirstGaps.average()}",
            abs(decoyFirstGaps.average() - realFirstGaps.average()) <
                4 * sd * sqrt(1.0 / decoyFirstGaps.size + 1.0 / realFirstGaps.size),
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
        // not become the real send's problem.
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
        // The window cover traffic ADDS: on a decoy-first send the real frame waits out the gap, so
        // a teardown landing inside it must not be what swallows the message — and on a real-first
        // send it must not be what strands the real frame unpaired. Both orders are exercised (the
        // seeds make that deterministic). The drawn ORDER survives cancellation even though the
        // drawn GAP does not.
        var sawDecoyFirst = false
        var sawRealFirst = false
        repeat(12) { iteration ->
            val frames = mutableListOf<Any>()
            val local = pairing(
                frames,
                random = seeded(7 + iteration.toLong()),
                sleep = { delay(it) },
            )
            val job = launch { local.record(textEnvelope(), frames) }
            runCurrent()
            job.cancelAndJoin()

            assertEquals("a cancelled pairing lost a frame", 2, frames.size)
            if (frames.first() === Real) sawRealFirst = true else sawDecoyFirst = true
        }
        assertTrue("the decoy-first branch was never exercised", sawDecoyFirst)
        assertTrue("the real-first branch was never exercised", sawRealFirst)
    }

    @Test
    fun `a pairing in flight delays a concurrent send but never overtakes it`() = runTest {
        // Reordering is forbidden categorically by R-U3-1 (delay is only bounded), and without the
        // window lock the second send's tail would publish while the first pairing sleeps. Two
        // properties: order preserved, and the two pairs not interleaved — an interleaved pair would
        // leak the order, since "a foreign frame landed between these two" means real-first.
        val frames = mutableListOf<Any>()
        val pairing = pairing(frames, random = seeded(3), sleep = { delay(it) })
        val firstReal = Any()
        val secondReal = Any()

        launch { pairing.paired(textEnvelope(counter = 1)) { frames.add(firstReal) } }
        runCurrent()
        launch { pairing.paired(textEnvelope(counter = 2)) { frames.add(secondReal) } }
        advanceUntilIdle()

        assertEquals("four frames, two pairs", 4, frames.size)
        assertTrue(
            "the second send overtook the first",
            frames.indexOf(firstReal) < frames.indexOf(secondReal),
        )
        assertTrue("the two pairs interleaved", frames.indexOf(secondReal) >= 2)
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
