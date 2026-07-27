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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * **[publish] is invoked EXACTLY ONCE on every path**, including a cover-traffic failure and
     * including cancellation. An implementation that can swallow a real send is a functional
     * regression caused by a privacy feature, which spec §4.3 R-U3-1 forbids absolutely.
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
 * Emits one cover frame beside every real `message.send`, in an order an observer cannot predict and
 * separated by a delay drawn per send.
 *
 * [DecoyEnvelopeBuilder] is canonical for what a cover envelope *is*; this class owns only **when
 * the two frames go out and in which order**. It has no vault access, writes nothing durable, keeps
 * no state about any message and holds no timer — the same "fact about the type" discipline the
 * builder documents.
 *
 * ## R-U3-1 wins every conflict, and this is where it is paid for
 *
 * The real send is published through [CoverTraffic.paired]'s `publish` lambda **exactly once on
 * every path** — success, a cover-traffic failure, a builder refusal, a cancelled scope, even a
 * cancellation while waiting for [window]. That is enforced by the `finally` in [paired] rather than
 * argued: nothing this class can do, and nothing that can go wrong inside it, can cost a real send.
 * `flushSendRatchet` is not touched and neither is its position relative to `ws.sendMessage` — this
 * seam sits strictly between the two, at a point where the path already suspends.
 *
 * **What the ruling costs, per §4.3 R-U3-4 and §2.4.** When the builder throws, the real frame goes
 * out **unpaired** — the exact observable this feature exists to remove. It is accepted because the
 * alternative (dropping the send) is a denial-of-service vector: anything that could induce build
 * failures would silence the user. Per R-U3-3 this is a **defect report, not a runtime path** — U2
 * made essentially every real shape mirrorable, so if this branch is ever reached in practice the
 * builder has a bug. Both known causes are about the inputs and neither is per-envelope chance: a
 * recipient account id whose string length differs from the synthetic account's (both are
 * relay-assigned UUIDs, so it cannot happen against this relay), and a local identity the vault
 * cannot produce (impossible on a path that has just encrypted a message with it).
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
 * - **Uniform**, because uniform is the maximum-entropy distribution over a bounded support: given
 *   that a bound exists at all, any other shape hands the observer a better-than-uniform prior on
 *   the gap. An unbounded distribution (an exponential, the shape a Poisson cadence would suggest)
 *   is rejected twice over — its tail violates R-U3-1 on the half of sends where the real frame
 *   goes second, and its mode at zero makes short gaps *more* likely, i.e. more guessable, which is
 *   the opposite of what the requirement asks for.
 * - **The bound is set by R-U3-1, not by taste.** On a decoy-first send the real frame is delayed by
 *   exactly the drawn gap. [GAP_MAX_MS] is well under the ~100 ms at which UI latency becomes
 *   perceptible, and under the median round-trip to the relay on every supported transport (two
 *   orders of magnitude under I2P/Tor). It is also smaller than the variance the send path already
 *   carries: `flushSendRatchet` performs a blocking durable disk commit immediately before this
 *   point, with a retry backoff measured in whole milliseconds.
 * - **The floor is not cosmetic.** Two writes issued back-to-back can be coalesced into one TCP
 *   segment, which would present the pair as a single double-length frame and throw away the
 *   equal-length property the builder exists to provide. [GAP_MIN_MS] keeps them apart.
 * - **[random] is a [SecureRandom] BY TYPE, and that is a security requirement rather than
 *   hygiene.** The gap is *directly observable* on the wire; the order bit is not. Both are drawn
 *   from the same generator, so a `java.util.Random` here would let an observer recover the 48-bit
 *   LCG state from a handful of measured gaps and then **predict every subsequent order bit** — the
 *   one value this whole mechanism exists to keep secret. The parameter type makes that
 *   unrepresentable rather than relying on every caller passing the right thing.
 *
 * ## Why the pair is emitted under a lock, and why the lock cannot strand a send
 *
 * [window] makes one pair's two frames exclusive against another pair's. Without it two hazards
 * appear, and the second is a leak rather than a nuisance:
 *
 *  - a real send queued behind a decoy-first pairing would **overtake** the paired one on the wire
 *    while it sleeps — reordering, which R-U3-1 forbids categorically (unlike delay, which it
 *    merely bounds). The lock is acquired AFTER the durable flush, i.e. at the same point that
 *    already decides today's wire order, so the order is preserved rather than reconstructed;
 *  - only the decoy-first branch would be interleaving-free, so "a foreign frame appeared between
 *    the pair" would be evidence for **real-first** and the observer could read the order off the
 *    interleaving instead of off the frames. Holding the lock across both branches keeps them
 *    symmetric.
 *
 * The lock is held for one drawn gap and never across the flush, the network, or any vault lock, so
 * a concurrent send waits at most [GAP_MAX_MS]. `withLock` releases it on every path including
 * cancellation, and [paired]'s `finally` publishes the real frame even when the lock was never
 * acquired — so no failure mode of this lock can strand a real send.
 *
 * ## Lock order
 *
 * [window] is the OUTERMOST lock this path takes. It is acquired holding nothing (the per-contact
 * session lock is released before the flush; the [recipient]/[sender] reads happen before it), and
 * nothing that holds `DecoySectionLock`, `VaultRuntime.stateLock`, a session lock or the storage
 * lock ever waits for it — provisioning runs on its own job and never calls into this class. The
 * documented order (section → stateLock → session → storage) is therefore extended at the top, not
 * violated.
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

    private val window = Mutex()

    private val provisioningStarted = AtomicBoolean(false)

    @Volatile
    private var provisionJob: Job? = null

    override suspend fun paired(cover: MessageEnvelope, publish: () -> Unit) {
        val plan = plan(cover)
        if (plan == null) {
            publish()
            return
        }
        // Both emissions latch BEFORE they run, so a throw out of either cannot cause a second
        // attempt from the `finally`, and the `finally` can never double-publish a real send.
        var realDone = false
        var decoyDone = false
        fun real() {
            if (realDone) return
            realDone = true
            publish()
        }
        fun decoy() {
            if (decoyDone) return
            decoyDone = true
            emit(plan.decoy)
        }
        try {
            window.withLock {
                if (plan.decoyFirst) {
                    decoy()
                    sleep(plan.gapMs)
                    real()
                } else {
                    real()
                    sleep(plan.gapMs)
                    decoy()
                }
            }
        } finally {
            // R-U3-1: cover traffic never costs a real send. R-U3-3: a real frame is never left
            // unpaired. Both calls are non-suspending, so they complete even under cancellation —
            // where the drawn gap is the only thing lost and the drawn ORDER is still honoured.
            if (plan.decoyFirst) {
                decoy()
                real()
            } else {
                real()
                decoy()
            }
        }
    }

    override fun stop() {
        provisionJob?.cancel()
        provisionJob = null
    }

    // ── planning ────────────────────────────────────────────────────────────────

    private class Plan(val decoy: MessageEnvelope, val decoyFirst: Boolean, val gapMs: Long)

    /**
     * The whole cover-traffic decision for one send, or null for "this send goes uncovered".
     *
     * **Total by construction** — it catches everything but cancellation, because its caller is the
     * real send path and a throw here would abort a real send. It also runs entirely BEFORE [window]
     * is taken: the vault read and the build must not sit inside the window that blocks another
     * send's tail.
     */
    private fun plan(cover: MessageEnvelope): Plan? = try {
        val syntheticAccountId = recipient()
        if (syntheticAccountId == null) {
            ensureProvisioning()
            null
        } else {
            sender()?.let { from ->
                // A throw here is R-U3-4: the real send proceeds, uncovered. See the class kdoc —
                // reaching it is a defect to report, not a case to swallow quietly.
                Plan(
                    decoy = builder.build(from, syntheticAccountId, cover),
                    decoyFirst = random.nextBoolean(),
                    gapMs = gapMs(),
                )
            }
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
     * throw is contained: the real frame's fate is decided by [paired]'s caller, and nothing here
     * may influence it.
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
         * Floor of the drawn gap, in milliseconds. Not cosmetic: two back-to-back writes can share
         * one TCP segment, which would present the pair as a single double-length frame.
         */
        const val GAP_MIN_MS: Int = 5

        /**
         * Ceiling of the drawn gap, in milliseconds — the worst-case latency cover traffic adds to a
         * real send, and it is added only when the decoy goes first. See the class kdoc for why the
         * bound sits here.
         */
        const val GAP_MAX_MS: Int = 50
    }
}
