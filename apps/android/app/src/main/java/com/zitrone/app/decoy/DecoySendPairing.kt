// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.data.MessageEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * The send path's cover-traffic seam: it wraps the **non-suspending publish tail** every outbound
 * envelope passes through, so a cover frame can ride beside the real one.
 *
 * Two properties are structural rather than documented, and both matter more than they look:
 *
 *  1. **`publish` is a plain function type, not a suspending one.** The coordinator's
 *     `contactExists → ws.sendMessage` tail must not suspend (see
 *     [com.zitrone.app.flushSendRatchet] — a suspension there lets a queued `deleteContact`
 *     interleave on the confined worker and publish to a just-deleted contact). Handing that tail
 *     to this seam as a `() -> Unit` makes the rule **compiler-enforced** at each call site instead
 *     of a comment three call sites have to keep repeating.
 *  2. **[NONE] is not a flag, it is the whole "cover traffic off" implementation.** A coordinator
 *     built without cover traffic runs the identical tail with one extra non-inlined call, so there
 *     is no `if (decoysEnabled)` anywhere on the real send path to get wrong.
 */
interface CoverTraffic {

    /**
     * Run [publish] — the real send's non-suspending publish tail — with whatever cover traffic this
     * implementation provides around it.
     *
     * **[publish] runs FIRST and EXACTLY ONCE, before any cover code**, per the §4.3 R-U3-2 ruling
     * of 2026-07-27. That is a contract on implementations, not a hope: it is what makes "cover
     * traffic cannot cost a real send" structural instead of guarded. Note that entering a suspend
     * function is not itself a suspension point, so an already-cancelled caller still gets its
     * publish — there is nothing before it that could check for cancellation.
     */
    suspend fun paired(cover: MessageEnvelope, publish: () -> Unit)

    /**
     * Session teardown — called from `MessagingCoordinator.stop()` alongside the notification
     * teardown. Nothing may outlive the session.
     */
    fun stop()

    companion object {
        /** Cover traffic off: the real send path, unchanged. */
        val NONE: CoverTraffic = object : CoverTraffic {
            override suspend fun paired(cover: MessageEnvelope, publish: () -> Unit) = publish()
            override fun stop() = Unit
        }
    }
}

/**
 * Emits one cover frame **after** every real `message.send`, separated by a delay drawn per send.
 *
 * [DecoyEnvelopeBuilder] is canonical for what a cover envelope *is*; this class owns only **when
 * the second frame goes out**. It has no vault access, writes nothing durable, keeps no state about
 * any message and holds no timer — the same "fact about the type" discipline the builder documents.
 *
 * ## REAL-FRAME-FIRST, ALWAYS — and this is why the class is small
 *
 * Spec §4.3 R-U3-2 was **amended by maintainer ruling on 2026-07-27**: random ordering is conceded
 * and the real frame always goes first. The ruling is not a preference but an exhaustion proof —
 * on a decoy-first send there are exactly three places the drawn gap can sit relative to the
 * durability barrier and the atomic `contactExists → ws.sendMessage` tail, and all three break
 * something (widened process-death loss window and `deleteContact` race; the flush's own duration
 * landing inside the decoy-first interval only; or ciphertext to a contact deleted during the gap).
 * There is no fourth position, so **decoy-first has no correct implementation, not merely a worse
 * one.**
 *
 * The whole of R-U3-1 is therefore paid for by **one statement**: `publish()` is the first thing
 * [paired] does, outside every `try`, before a single line of cover code and before any suspension
 * point exists. Four separate defects are *impossible by construction* rather than prevented by a
 * check, and each of them had to be argued about while the order was random:
 *
 *  - **Process death between the durable barrier and the socket.** The real envelope is handed to
 *    the socket at the same instant it was before this feature existed. The only suspension in this
 *    class is the drawn gap, and it is strictly after that handoff, so a process that dies at it
 *    loses a cover frame and nothing else.
 *  - **A queued `deleteContact` interleaving on the confined worker.** There is no suspension
 *    between the flush and the tail to interleave *in*; the pre-U3 `flush · check · write` sequence
 *    is byte-for-byte the sequence that runs.
 *  - **A cover frame taking the last `sendLimit` permit from the real frame it covers.** The real
 *    frame is enqueued first, so within a pair the cover frame can only ever get the permit the
 *    real one did not need. (**Cross-send** preemption — pair N's cover frame taking the permit
 *    pair N+1's real frame wanted — survives every ordering, is inherent to doubling the volume on
 *    a shared per-account budget, and is a **relay-side** item: `sendLimit` is a server constant
 *    the relay never communicates, so no client-side headroom policy is sound. It is not defended
 *    against here, deliberately.)
 *  - **A cover-side throwable suppressing the real publish.** [emit] rethrows
 *    `CancellationException` and that used to be able to skip the real send from inside the guard
 *    that existed to protect it. It now runs after the publish, so there is nothing left for it to
 *    skip.
 *
 * **What the ruling cost, recorded rather than quietly dropped:** an observer watching *both* ends
 * of the network no longer gets 5–50 ms of ambiguity about which of the two frames was real. It
 * reads `recipient_id` in cleartext on both envelopes regardless, so the loss is close to nil; a
 * one-sided observer sees two equal-length frames either way. Spec §2.4 carries it as a residual.
 *
 * ## What survives, and what it costs
 *
 * The remaining requirement is unchanged: the two frames are the **same serialized length**, the
 * gap is drawn per send, and nothing about the pair says which conversation the real frame belonged
 * to.
 *
 * **When the builder throws, the real frame goes out unpaired** (§4.3 R-U3-4, §2.4) — the exact
 * observable this feature exists to remove. It is accepted because the alternative (dropping the
 * send) is a denial-of-service vector: anything that could induce build failures would silence the
 * user. Per R-U3-3 this is a **defect report, not a runtime path** — U2 made essentially every real
 * shape mirrorable, so if this branch is ever reached in practice the builder has a bug. Both known
 * causes are about the inputs and neither is per-envelope chance: a recipient account id whose
 * string length differs from the synthetic account's (both are relay-assigned UUIDs, so it cannot
 * happen against this relay), and a local identity the vault cannot produce (impossible on a path
 * that has just encrypted a message with it).
 *
 * ## Failure is UNIFORM, never per-envelope (R-U3-3)
 *
 * The only condition consulted per send is **"does this vault have a synthetic account id"**
 * ([recipient]). That predicate is durable, and within a session it flips at most once — from absent
 * to present, when provisioning lands. It never flaps. So cover traffic is off for a prefix of the
 * session and on for the rest, which is the "persistent cause → uniformly-off cover" degradation
 * R-U3-3 accepts, not the stutter it forbids.
 *
 * **`DecoyAccountProvisioner.canSend()` is deliberately NOT the predicate here.** It folds in
 * `VaultRuntime.capacityExceeded`, which is transient — exactly the shape R-U3-3 rules out. It is
 * also unobservable at this point even if it were used: `capacityExceeded` fail-closes
 * `flushBeforeAck` for the WHOLE vault, so a send that reaches this seam has already flushed
 * successfully and cannot be in that state. `canSend` answers "may this session act on the
 * credentials it just committed", which is a provisioning question; the send path's question is "is
 * there an account to address", which is `hasAccount`. Adding a second, flappable condition would
 * buy nothing and cost the uniformity requirement.
 *
 * ## OPEN QUESTION — which envelopes are paired. **ANSWER: every one through the choke point.**
 *
 * Text, attachment control payloads and read receipts all reach `WsClient.sendMessage`, and all
 * three are paired. The alternative — pairing only user-visible messages — was rejected because it
 * **destroys a property the product already has**: a receipt envelope is deliberately built to be
 * indistinguishable from a text message (`ttl_seconds: null`, `burn_on_read: false`,
 * `media_type: "text"`, [com.zitrone.app.crypto.MessagePadding]-padded ciphertext), and an
 * attachment's control payload rides `media_type: "text"` for the same reason. Pairing only text
 * would sort the one size class an observer can see into "paired" and "unpaired" halves and hand it
 * a receipt detector that does not exist today — a *new* leak introduced by a privacy feature, and
 * R-U3-3's marked-frame problem in its purest form.
 *
 * **Observable consequence, stated rather than left implicit:** outbound `message.send` volume
 * doubles for every envelope class, receipts included (`sendLimit` is 100/min per account — spec
 * §6.3 — which no human sender approaches), and the synthetic conversation receives cover frames
 * shaped like receipts and attachment controls as well as like messages. It does **not** interact
 * with the uncovered plaintext control channel declared in §2.4 (`typing.*`, `message.ack`,
 * `message.burn`, `message.received`): those frames are an order of magnitude smaller and separable
 * by size alone whatever this class does. The relationship runs the other way — because that channel
 * already leaks per-peer activity, covering receipts costs nothing there, while *not* covering them
 * would add a distinction inside the `message.send` size class that the control channel does not
 * give away.
 *
 * ## OPEN QUESTION — the delay distribution. **ANSWER: uniform over [GAP_MIN_MS]‥[GAP_MAX_MS] ms.**
 *
 * The ruling changed what the bounds are *for*, so they are re-derived here rather than inherited.
 * **The gap no longer delays any real send** — it is drawn and slept only after the real frame is
 * on the socket — so R-U3-1 no longer sets the ceiling. Three other things do:
 *
 * - **Uniform**, because uniform is the maximum-entropy distribution over a bounded support: given
 *   that a bound exists at all, any other shape hands the observer a better-than-uniform prior on
 *   the gap. An unbounded distribution (an exponential, the shape a Poisson cadence would suggest)
 *   is rejected because its mode at zero makes short gaps *more* likely, i.e. more guessable, and
 *   its tail makes the point below worse without limit.
 * - **The ceiling is set by R-U3-3, not by latency.** The cover frame is emitted by the sending
 *   coroutine itself, so a gap the session does not outlive is a cover frame that never goes —
 *   producing exactly the *marked*, unpaired real frame R-U3-3 forbids. Cancellation (vault lock,
 *   teardown, backgrounding) is frequent on a mobile messenger, so the wider the gap the more often
 *   pairing degrades per-send instead of uniformly. [GAP_MAX_MS] keeps that window small; [paired]'s
 *   `finally` closes what is left of it by emitting the cover frame anyway, gapless, when the drawn
 *   gap is cut short.
 * - **The floor is not cosmetic, but it is weaker than it used to claim.** Two writes issued
 *   back-to-back can be coalesced into one TCP segment or TLS record. [GAP_MIN_MS] separates the two
 *   *calls*; it cannot separate the two socket writes, because `WsClient.sendMessage` hands the
 *   frame to OkHttp's asynchronous writer queue and returns — the actual write happens on OkHttp's
 *   writer thread, which this class does not control and cannot flush. **What a coalesced pair
 *   actually costs, now that the order is fixed:** the observer sees one record of exactly twice the
 *   frame length instead of two of the frame length. Both readings say "one covered send happened
 *   here" and neither says which conversation it belonged to — the equal-length property is about
 *   the two halves being indistinguishable *from each other*, and a coalesced pair has no halves to
 *   tell apart. So the floor is a best-effort tidiness measure over a residual that is cosmetic
 *   rather than a leak, and it is documented as that instead of as a guarantee the mechanism cannot
 *   give.
 * - **[random] is a [SecureRandom] BY TYPE, and that is a security requirement rather than
 *   hygiene** — with a different argument than before the ruling, because the order bit it used to
 *   protect no longer exists. The gap is **directly observable on the wire**, and it is now the only
 *   drawn quantity. A `java.util.Random` here would let an observer recover the 48-bit LCG state
 *   from a handful of measured gaps and then *predict this generator's whole future stream* — which
 *   turns the gap into a stable device fingerprint that links pairs to each other, links sessions to
 *   each other, and (because one instance exists per live vault session on one device) could link
 *   two vaults' traffic, which is a plausible-deniability break rather than a traffic-analysis
 *   nuisance. The parameter type makes that unrepresentable rather than relying on every caller
 *   passing the right thing.
 *
 * ## No lock, and why the one this class used to hold is gone
 *
 * An earlier version emitted the pair under a mutex. It existed for two reasons and the ruling
 * removed both: a real send queued behind a **decoy-first** pairing would have overtaken it on the
 * wire (reordering, which R-U3-1 forbids categorically), and holding the lock across both branches
 * was needed to stop "a foreign frame appeared between the pair" from being readable evidence of
 * which branch had been taken. Real-first has no branch, and publishes with no suspension in front
 * of it, so real frames leave in exactly the order the coordinator issues them — the pre-U3 property,
 * restored rather than reconstructed. Pairs from concurrent sends may now interleave on the wire,
 * which reveals nothing: the order within each pair is fixed and public, and an observer can already
 * associate the halves by length.
 *
 * Deleting it also deletes a bound this class could not honestly state. "A concurrent send waits at
 * most [GAP_MAX_MS]" was false under multiple waiters — the bound was per-hop, not total. **The
 * true bound is now zero**: cover traffic introduces no suspension before any real frame and no lock
 * for any send to queue behind, so the delay it adds to a real send is not small, it is none.
 *
 * ## Lock order
 *
 * This class takes no lock. It calls [recipient] and [sender] — which take `DecoySectionLock` and
 * the vault runtime's own locks internally — and it does so holding nothing, from a point where the
 * per-contact session lock has already been released and the durable flush has already completed.
 * The documented order (section → stateLock → session → storage) is untouched.
 *
 * ## Teardown (R-U3-5)
 *
 * The only coroutine this class owns is the one-shot provisioning job, cancelled by [stop] from
 * `MessagingCoordinator.stop()` and again by the session scope dying. There is no timer, no queue
 * and no retained envelope — the trailing frame is emitted by the sending coroutine itself rather
 * than by a scheduled job, so a locked vault has nothing left that could emit. Nothing is logged,
 * recorded or written to device-level storage: this class takes no diagnostics handle, exactly as
 * [DecoyAccountProvisioner] takes none.
 *
 * ## Provisioning is triggered HERE — the first thing in the tree that spends a registration
 *
 * U1 and U2 shipped deliberately unwired. [ensureProvisioning] is the trigger, and it fires from a
 * real send that has already flushed durably — never at vault creation, never at unlock, never from
 * a send whose durable barrier failed. That is §6.2a's laziness rule ("a vault that never sends
 * never spends a registration"); every other budget rule — the one-attempt-per-runtime latch, the
 * write-ahead deferral, the silent degradation — lives in [DecoyAccountProvisioner] and is not
 * restated here. The launch is fire-and-forget by requirement: waiting on a multi-second
 * proof-of-work would block a real send, so the sends that happen while it runs go uncovered.
 */
class DecoySendPairing(
    private val scope: CoroutineScope,
    /**
     * The real account this vault sends as, or null when there is no usable local identity. Read per
     * send rather than captured: the account can be re-linked under a live session.
     */
    private val sender: () -> DecoyEnvelopeBuilder.Sender?,
    /**
     * This vault's synthetic account id, or null while it has none — the SEND predicate, see the
     * uniform-failure section. `DecoyState`'s kdoc is canonical for what the section holds.
     */
    private val recipient: () -> String?,
    /** `WsClient.sendMessage`. A false return (dead socket) is not an error here — see [emit]. */
    private val send: (MessageEnvelope) -> Boolean,
    /** `DecoyAccountProvisioner.provisionIfNeeded` — see the provisioning section. */
    private val provision: suspend () -> Unit,
    private val builder: DecoyEnvelopeBuilder = DecoyEnvelopeBuilder(),
    private val random: SecureRandom = SecureRandom(),
    /** Seam for the drawn gap, so the statistical tests need no wall clock. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Where the one provisioning attempt runs. [Dispatchers.IO] in production — it is a
     * proof-of-work solve and several HTTP round-trips, and it must never occupy the coordinator's
     * confined worker. A seam only so tests can put that job in their own virtual time.
     */
    private val provisionContext: CoroutineContext = Dispatchers.IO,
) : CoverTraffic {

    private val provisioningStarted = AtomicBoolean(false)

    @Volatile
    private var provisionJob: Job? = null

    override suspend fun paired(cover: MessageEnvelope, publish: () -> Unit) {
        // THE REAL FRAME GOES FIRST, AND THIS LINE IS THE WHOLE OF R-U3-1 (§4.3 R-U3-2 ruling).
        // It is deliberately the first statement, outside every `try`, with no suspension point in
        // front of it and no condition guarding it. Everything below runs after the real envelope
        // has been handed to the socket, so no failure, cancellation, delay or rate-limit rejection
        // on the cover side can reach it. A throw out of it is the real path's own throw and is
        // propagated unchanged — swallowing it here would be cover traffic altering real behaviour.
        publish()

        val decoy = coverFor(cover) ?: return
        try {
            sleep(gapMs())
        } finally {
            // R-U3-3: the drawn gap is lost to cancellation, the PAIR is not. An unpaired real frame
            // is a marked frame, and cancellation (vault lock, teardown, backgrounding) is frequent
            // enough that letting it drop the cover frame would mark a recognisable class of sends.
            // Non-suspending, so it still runs while the coroutine is being cancelled.
            emit(decoy)
        }
    }

    override fun stop() {
        provisionJob?.cancel()
        provisionJob = null
    }

    // ── the cover frame ─────────────────────────────────────────────────────────────────────

    /**
     * The cover envelope for one send, or null for "this send goes uncovered".
     *
     * **Total by construction** — it catches everything but cancellation. That containment is still
     * load-bearing after the ruling, but it now protects a different thing: the real send has
     * *already happened* when this runs, so a throw escaping here would propagate into
     * `MessagingCoordinator`'s `runCatching` and mark a delivered message FAILED. Cover traffic
     * would then have corrupted the state of a send it could not otherwise touch.
     */
    private fun coverFor(cover: MessageEnvelope): MessageEnvelope? = try {
        val syntheticAccountId = recipient()
        if (syntheticAccountId == null) {
            ensureProvisioning()
            null
        } else {
            // A throw here is R-U3-4: the real send already went, uncovered. See the class kdoc —
            // reaching it is a defect to report, not a case to swallow quietly.
            sender()?.let { from -> builder.build(from, syntheticAccountId, cover) }
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    /** Uniform over the closed interval — see the delay-distribution answer in the class kdoc. */
    private fun gapMs(): Long = (GAP_MIN_MS + random.nextInt(GAP_MAX_MS - GAP_MIN_MS + 1)).toLong()

    /**
     * Hand one cover frame to the socket. A `false` return is the ordinary dead-socket answer and a
     * throw is contained: the real frame is already gone and nothing here may change what happened
     * to it.
     */
    private fun emit(decoy: MessageEnvelope) {
        try {
            send(decoy)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Silent by requirement — no logging, no diagnostics sink (R-U3-5).
        }
    }

    /**
     * Start the one provisioning attempt this session makes, if it has not started already.
     *
     * The [AtomicBoolean] bounds the number of JOBS to one; the number of relay REGISTRATIONS is
     * bounded by [DecoyAccountProvisioner]'s runtime-scoped latch, which is the guard that actually
     * protects the shared worldwide bucket and is deliberately not duplicated here. This one only
     * keeps a hundred sends from launching a hundred coroutines that would each read the vault and
     * return.
     */
    private fun ensureProvisioning() {
        if (!provisioningStarted.compareAndSet(false, true)) return
        provisionJob = scope.launch(provisionContext) {
            try {
                provision()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Silent degradation to cover-traffic-off (§6.2a) — never surfaced, never recorded.
            }
        }
    }

    companion object {
        /**
         * Floor of the drawn gap, in milliseconds. Best effort, not a guarantee: it separates the
         * two `WsClient.sendMessage` CALLS, and OkHttp's writer thread owns the socket writes. See
         * the delay-distribution section for what a coalesced pair actually costs.
         */
        const val GAP_MIN_MS: Int = 5

        /**
         * Ceiling of the drawn gap, in milliseconds. It bounds no real send's latency — the real
         * frame is already on the socket — it bounds the window in which a teardown can cut the gap
         * short and leave the pair to the `finally`. See the class kdoc.
         */
        const val GAP_MAX_MS: Int = 50
    }
}
