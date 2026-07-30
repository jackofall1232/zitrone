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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/**
 * The send path's cover-traffic seam. **It is called AFTER the real envelope has been handed to the
 * socket, and it is never given the real send to run.**
 *
 * ## Why the publish tail is no longer passed in (fix round 3, 2026-07-27)
 *
 * Until round 2 this interface took the real send's publish tail as a `() -> Unit` and promised to
 * run it first. That promise was kept — but "first" meant *first inside `paired`*, and getting into
 * `paired` already cost an interface dispatch, a captured lambda, and entry into a suspend
 * function's state machine. Round 2 justified that with *"a process can only die at a suspension
 * point"*, **which is false**: a coroutine may only *suspend* at a suspension point, while the OS
 * can kill the process at **any instruction** — which is exactly what this project's threat model
 * assumes. So those instructions sat between the durable ratchet advance and `ws.sendMessage`, and
 * a kill inside them lost a message whose ratchet had already moved. If the baseline kill window is
 * `K`, cover traffic made it `K ∪ C`; R-U3-1 is absolute and does not have a de minimis exception
 * for `C`.
 *
 * The repair is ordering, not a check: **the caller publishes, and only then calls [cover].** `C` is
 * now empty — the instruction sequence from the durability barrier to `ws.sendMessage` is the
 * pre-U3 one, and every cover-side instruction is strictly after the handoff.
 *
 * **What that gave up, and how it was kept anyway.** Passing the tail as a non-suspending function
 * type made "the `contactExists → ws.sendMessage` tail must not suspend" (D2c) *compiler-enforced*
 * rather than a comment repeated at three call sites. Handing the tail back to the caller would have
 * retired that. It did not: `MessagingCoordinator` now publishes through its own **non-suspending
 * private methods** (`publishOutgoing`, `publishReceipt`), so the compiler still rejects a
 * suspension inside the tail — and it does so through a member of the send path itself, which would
 * remain correct and necessary if cover traffic were deleted tomorrow.
 *
 * [NONE] remains the whole "cover traffic off" implementation: a coordinator built without cover
 * traffic runs the identical publish tail and then one non-inlined call that returns, so there is no
 * `if (decoysEnabled)` anywhere on the real send path to get wrong.
 *
 * ## THE CONFINEMENT CONTRACT (fix round 4) — the only thing an implementation may assume
 *
 * **[cover], [stop] and [quiesce] are all called on ONE single-threaded worker** —
 * `MessagingCoordinator`'s `confined` dispatcher, which is where every send already runs. This is
 * not a convenience: it is what makes "cover is subordinate to the real send" hold under
 * *concurrency* rather than only in program order.
 *
 * Round 3 declared a residual it believed was forced: between `ws.sendMessage` returning and the
 * pairing registering itself with teardown, a concurrent `stop()` could slip past, and closing that
 * window seemed to require a lock (or cover work) in front of the handoff, which R-U3-1 forbids
 * absolutely. **That argument was refuted with a construction, and the construction is this one:**
 * terminal teardown is *enqueued on the worker the sends already run on*, so it cannot interleave
 * with a send at all — it runs strictly before or strictly after, never inside. The publish tail and
 * the pairing's admission sit in the same uninterrupted slice of that worker (there is no suspension
 * point between them), so there is nothing left to interleave *with*. **No lock and no cover-side
 * instruction was added in front of any real send to get it.**
 *
 * Two things follow, and both were P1s before:
 *
 *  - **Admission cannot lose a race with teardown**, so the R-U3-1 residual is retired rather than
 *    accepted.
 *  - **The drain never waits**, so it needs no wall clock. A pairing is admitted only once its cover
 *    frame exists, and the build cannot be interrupted by teardown, so every admitted pairing is
 *    always ready to emit the moment teardown looks at the register. The 100 ms drain deadline that
 *    used to abandon a slow build — bounding *suspension* while claiming to bound *time* — is gone
 *    because there is no longer anything for it to bound.
 */
interface CoverTraffic {

    /**
     * Emit cover traffic for [real] — **an envelope the caller has ALREADY handed to the socket, and
     * which the socket ACCEPTED.**
     *
     * Called only on a genuine handoff (fix round 4): a send whose envelope was discarded (contact
     * deleted mid-send) or refused (socket down) must not reach this method, because a decoy with no
     * real frame behind it is a frame the user never generated — the same marked-pair defect as an
     * unpaired real frame, in the other direction.
     *
     * Implementations may suspend for as long as they like: nothing they do can reach the real send,
     * because the real send is over. They must not throw: a throw here would propagate into
     * `MessagingCoordinator`'s `runCatching` and mark an already-delivered message FAILED.
     * Cancellation still propagates — it is the caller's own cancellation.
     */
    suspend fun cover(real: MessageEnvelope)

    /**
     * The relay refused a `message.send` with `rate_limited` — **the one signal it gives us that the
     * shared per-account send budget is contended.**
     *
     * R-U3-1 makes cover traffic the half that yields when a resource is contended, so this exists to
     * take cover off. It is deliberately **not** an error-handling hook, and that separation
     * OUTLIVED the reason it was first written down. The original reason was that `rate_limited`
     * carried no message id at all, so nothing here *could* attribute a rejection. **That is no
     * longer true** — as of the relay-side change the id rides `message_id` on `rate_limited` /
     * `store_failed` / `bad_envelope`, and `MessagingCoordinator.onServerError` now marks the
     * rejected send FAILED (0.10.1). The separation stands on its own merits instead: the yield
     * must fire even for a rejection the relay could NOT attribute, so it cannot be made
     * conditional on an id being present, and cover traffic must never surface anything to a user.
     *
     * **This is why the client-side budget defence is sound after all.** It was ruled unsound on the
     * reasoning that `sendLimit` is a server constant the relay never communicates — true, and it
     * would defeat any *headroom* policy, which has to predict the limit. Yielding reactively does
     * not predict anything: it needs no number, only the event.
     *
     * Called from the transport's inbound callback thread, not from the confinement worker, so an
     * implementation must be safe there — and must not block, because it runs on the socket's own
     * dispatch path.
     */
    fun onRelayRateLimited()

    /**
     * TERMINAL session teardown (R-U3-5) — and **the transport's own invalidation is handed to this
     * method rather than performed beside it.**
     *
     * Round 2's teardown disconnected the socket first (`ws.disconnect()`) and stopped cover second,
     * which put a lone real frame followed by a TLS close on the wire every time a vault locked
     * during a drawn gap: a deterministic, recognisable class of unpaired real sends correlated with
     * lock, teardown and backgrounding — precisely what R-U3-3 calls worse than no cover at all.
     * Merely swapping the two statements is **not** sufficient, because a `stop()` that cancels only
     * the provisioning job does not own the pairings already admitted. So the ordering is expressed
     * as a *dependency* instead of as a convention: an implementation must
     *
     *  1. stop admitting new pairings (the caller owns the other half of R-U3-5 step 1 — refusing
     *     new REAL sends — because only the caller has a send path to refuse),
     *  2. stop provisioning,
     *  3. cancel, complete or drain every pairing it has already admitted,
     *  4. and only then run [invalidateTransport].
     *
     * [invalidateTransport] runs exactly once, and the caller must not invalidate the transport
     * itself — that is the point of passing it. **Called on the confinement worker** (see the
     * confinement contract above), which is what makes step 3 a drain rather than a race.
     */
    fun stop(invalidateTransport: () -> Unit)

    /**
     * NON-TERMINAL quiesce: drain the admitted pairings, run [swapTransport], **and keep going.**
     *
     * The session survives; only the socket underneath it is replaced. `ZitroneApp` swaps transports
     * in place when the user toggles Tor/I2P, which tears down a live TLS connection and immediately
     * dials a new one. Round 3 left that path undrained and declared it a residual; the third lens
     * ruled it P1 with a distinction neither reviewer had made — **a SPLIT pair is a stronger signal
     * than a missing cover frame.** A missing frame is one low-grade anomaly plausibly attributable
     * to jitter; a split pair is two identical-length frames milliseconds apart straddling a TLS
     * teardown and reconnect, which lets an observer link frames *across connection boundaries*
     * (defeating the unlinkability the padding exists to provide), binds the marked frame to an
     * independently observable infrastructure event, and correlates it with "the user just changed
     * their anonymity transport".
     *
     * So the same drain runs here, with the one difference that matters: **the transport is not
     * invalidated.** New pairings are still admitted afterwards, over the new socket.
     */
    fun quiesce(swapTransport: () -> Unit)

    companion object {
        /** Cover traffic off: the real send path, unchanged, and teardown in its original order. */
        val NONE: CoverTraffic = object : CoverTraffic {
            override suspend fun cover(real: MessageEnvelope) = Unit
            override fun onRelayRateLimited() = Unit
            override fun stop(invalidateTransport: () -> Unit) = invalidateTransport()
            override fun quiesce(swapTransport: () -> Unit) = swapTransport()
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
 * ## REAL-FRAME-FIRST, ALWAYS — and now it is the CALLER that makes it so
 *
 * Spec §4.3 R-U3-2 was amended by maintainer ruling on 2026-07-27: random ordering is conceded and
 * the real frame always goes first. The ruling is an exhaustion proof — on a decoy-first send there
 * are exactly three places the drawn gap can sit relative to the durability barrier and the atomic
 * `contactExists → ws.sendMessage` tail, and all three break something. There is no fourth position,
 * so **decoy-first has no correct implementation, not merely a worse one.**
 *
 * Round 2 implemented that by making `publish()` the first statement of the pairing function. Round
 * 3 goes one step further, for the reason set out on [CoverTraffic]: entering the pairing function
 * *at all* was cover-specific work sitting between the durable ratchet advance and the socket, and
 * the process can be killed there. **Now the real frame is on the socket before this class is
 * entered**, so the four R-U3-1 defects below are not "impossible because of a statement inside this
 * class" — they are impossible because none of this class's code exists in the window at all:
 *
 *  - **Process death between the durable barrier and the socket.** Nothing here runs before the
 *    handoff, so the window is byte-for-byte the pre-U3 one. This is the claim round 2 got wrong,
 *    and the difference is not wording: it is where the code sits.
 *  - **A queued `deleteContact` interleaving on the confined worker.** There is no suspension
 *    between the flush and the tail to interleave *in* — the tail is a non-suspending method of the
 *    coordinator and the compiler enforces it.
 *  - **A cover frame taking the last `sendLimit` permit from the real frame it covers.** The real
 *    frame is enqueued first, so within a pair the cover frame can only ever get the permit the real
 *    one did not need. **Cross-send** preemption — pair N's cover frame taking the permit pair N+1's
 *    real frame wanted — survives every ordering and is not fixed by it; it is fixed by [pressure],
 *    see the subordination section below.
 *  - **A cover-side throwable suppressing the real publish.** There is no longer any construction in
 *    which cover code could run before the publish, so there is nothing left for it to skip.
 *
 * **What the ruling cost, recorded rather than quietly dropped:** an observer watching *both* ends
 * of the network no longer gets 5–50 ms of ambiguity about which of the two frames was real. It
 * reads `recipient_id` in cleartext on both envelopes regardless, so the loss is close to nil; a
 * one-sided observer sees two equal-length frames either way. Spec §2.4 carries it as a residual.
 *
 * ## SUBORDINATION: WHERE A RESOURCE IS CONTENDED, COVER YIELDS (R-U3-1, rewritten 2026-07-28)
 *
 * Real-frame-first settles ordering *within* a pair. It settles nothing **between** pairs, and two
 * shared resources are consumed by both halves:
 *
 *  1. **The transport's outbound queue.** `WsClient.sendMessage` hands the frame to OkHttp's
 *     asynchronous writer and returns; OkHttp buffers it, refuses once the buffer would pass 16 MiB,
 *     and closes the connection when it refuses. A cover frame sitting in that buffer is capacity the
 *     *next* real frame may need.
 *  2. **The relay's per-account send budget.** `sendLimit` is charged to the AUTHENTICATED account
 *     and the cover frame rides the same socket, so a covered send costs two permits, not one.
 *
 * Both were reported as R-U3-1 violations in review round 7, and under the rewritten requirement
 * they are **defects, not residuals**: *"cover traffic must never compete with a real send for any
 * resource. Where a shared resource is contended, cover yields — dropped, not queued ahead of, not
 * charged against, the real frame."* [pressure] is that yield, and [CoverPressure] is canonical for
 * how it decides; nothing about the thresholds is restated here.
 *
 * **What changed in the reasoning, because it had been ruled the other way.** A client-side budget
 * defence was previously ruled *unsound* — `sendLimit` is a server constant the relay never
 * communicates, so a client assuming 100/min against a relay configured lower inverts the priority it
 * claims to guarantee. That is correct, and it kills a **headroom** policy, which must predict the
 * limit. It does not touch a **reactive** one: yielding on a signal of pressure needs no knowledge of
 * any limit. The signals are the queue depth, the relay's own `rate_limited`
 * ([onRelayRateLimited]) and this session's recent frame rate.
 *
 * **The check is at the very top of [cover], before the build and before provisioning**, and the
 * whole send goes uncovered when it trips — that is the *point*: a yield that still did the work
 * would still be competing, for the worker and for the vault read if not for the socket.
 *
 * **The drain does NOT consult it, and that is load-bearing.** [stop] and [quiesce] emit every
 * admitted pairing unconditionally. Pressure-shedding is *degradation* and permitted (a burst of
 * frames is already visible to anyone watching the connection, so the observer learns nothing new).
 * A cover frame missing because the vault locked or the transport changed is *disclosure* and is
 * not — it names a client lifecycle event the observer could not otherwise see, which is the class
 * rounds 3–5 closed. Letting pressure reach the drain would reopen it.
 *
 * **Decided once per send, not re-checked before the emit.** After the gap the frame is built,
 * admitted and owed to the register, and re-checking there would either have to run inside the drain
 * (reopening the paragraph above) or fork the two paths. The window it leaves is the 5–50 ms gap, in
 * which the queue would have to go from under 8 KiB to over 16 MiB — some sixteen thousand frames
 * this app has no way to produce — before a single ~1 KB cover frame could displace anything.
 *
 * ## TEARDOWN OWNS THE PAIRINGS IT ADMITTED (R-U3-3, R-U3-5)
 *
 * The counterpart of "cover never precedes the real send" is **"cover never outlives the socket it
 * needs"**, and round 2 failed it: teardown disconnected first and [stop] cancelled only the
 * provisioning job, so any pairing sleeping in its gap woke to a nulled socket and its cover frame
 * was silently dropped. That marks a deterministic class of real frames — lock, teardown,
 * backgrounding — which is the exact observable this feature exists to remove.
 *
 * So this class keeps a register of **admitted pairings**, and [stop] drains it before the transport
 * is invalidated:
 *
 *  - [cover] **builds the cover frame first and admits the built frame second** (fix round 4), then
 *    sleeps the drawn gap, then emits.
 *  - [stop] takes the same lock, **emits every admitted pairing's cover frame immediately, gapless,
 *    while the socket is still live**, and only then runs `invalidateTransport`.
 *  - Whichever of the two removes a pairing from the register is the one that emits its frame, so a
 *    cover frame goes out exactly once — see [Pending].
 *
 * ## WHY BUILD-THEN-ADMIT IS SAFE NOW, AND WHY IT WAS NOT BEFORE (fix round 4)
 *
 * Round 3 admitted first *because* teardown ran on a different thread: a pairing caught mid-build
 * would otherwise have been abandoned, so the register had to hold unbuilt pairings and the drain
 * had to **wait** for them — bounded by a 100 ms deadline. That deadline was a P1 in its own right.
 * "Non-suspending" bounds *suspension*, not *time*: slow cryptographic generation, scheduler
 * starvation or a stalled `recipient()` all overrun it without suspending, and the drain then
 * abandoned the pairing and disconnected — producing the deterministically unpaired, teardown-
 * correlated real frame the drain exists to prevent.
 *
 * The confinement contract on [CoverTraffic] removes the premise. Teardown is queued on the same
 * single worker every send runs on, and everything from the caller's `ws.sendMessage` through
 * [buildCover] to `inFlight.add` is one uninterrupted slice of that worker with **no suspension
 * point in it**. Teardown therefore cannot land mid-build: it runs strictly before the slice (and
 * the pairing is refused — but so was the real frame it would have covered, because the socket was
 * already dead when the caller's publish tail ran) or strictly after it (and the pairing is in the
 * register, already built, and is drained). So:
 *
 *  - the register never holds an unbuilt pairing, so the drain never waits;
 *  - there is no wall clock anywhere in teardown, so there is nothing left to overrun;
 *  - and the round-3 residual — the "handful of instructions" between the handoff and admission —
 *    **is closed, not accepted**, because those instructions are not interleavable.
 *
 * What that costs, stated: the build now sits between the real frame and the register rather than
 * after the register. It is still strictly *after* `ws.sendMessage`, so R-U3-1 is untouched — no
 * cover-side instruction moved in front of a handoff, and the K window is byte-for-byte the pre-U3
 * one. And it buys the deletion of the resolved-flag, the condition variable, the drain loop and the
 * deadline: four moving parts and two P1s, for one reordering.
 *
 * **The one thing an implementation cannot enforce for itself** is that its caller really is
 * confined. [teardown] is therefore kept even though a strictly confined caller would not need it:
 * it keeps this class internally consistent (exactly-once emit, no torn register) under a caller
 * that violates the contract, so a contract violation degrades to the round-3 behaviour minus the
 * wait, rather than to corruption. The contract itself is pinned by the caller's own tests.
 *
 * ## What survives, and what it costs
 *
 * The remaining requirement is unchanged: the two frames are the **same serialized length**, the gap
 * is drawn per send, and nothing about the pair says which conversation the real frame belonged to.
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
 * ## Failure is bounded by DISCLOSURE, not by rate (R-U3-3, rewritten 2026-07-28)
 *
 * The requirement used to read *"failure is uniform, never intermittent"*, on the rationale that
 * intermittent cover is worse than no cover. That rationale is false as stated and was withdrawn: an
 * unpaired send costs exactly one thing — for that message the adversary's candidate set is 1 instead
 * of 2 — and reveals no content, identity, contact or vault existence, all of which are held by
 * layers that never depended on cover. **The bound is that cover must not fail in ways that reveal
 * events an observer cannot ALREADY observe.**
 *
 * Two conditions are consulted per send, and they sit on opposite sides of that line:
 *
 *  - **"Does this vault have a synthetic account id"** ([recipient]) is durable and flips at most
 *    once per session, from absent to present, when provisioning lands. It never flaps: cover is off
 *    for a prefix of the session and on for the rest.
 *  - **[pressure]** sheds cover under load. It correlates with heavy sending — which is DEGRADATION,
 *    not disclosure, because a burst of frames is already visible to anyone watching the connection.
 *    The observer's candidate set is 1 instead of 2 while the user is busy, and protection thins
 *    exactly when the pipe is full, which is the right trade. It is a window rather than a per-send
 *    verdict precisely so it does not stutter.
 *
 * What stays prohibited is unchanged and is enforced elsewhere in this class: a lone decoy, a pair
 * split across a transport change, and any cover gap that names a vault lock, a teardown or a
 * backgrounding. Those name a client lifecycle event the observer could not otherwise see.
 *
 * **`DecoyAccountProvisioner.canSend()` is deliberately NOT the predicate here.** It folds in
 * `VaultRuntime.capacityExceeded`, which is transient — exactly the shape R-U3-3 rules out. It is
 * also unobservable at this point even if it were used: `capacityExceeded` fail-closes
 * `flushBeforeAck` for the WHOLE vault, so a send that reaches this seam has already flushed
 * successfully and cannot be in that state. `canSend` answers "may this session act on the
 * credentials it just committed", which is a provisioning question; the send path's question is "is
 * there an account to address", which is `hasAccount`.
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
 * doubles for every envelope class, receipts included — **up to the point where [pressure] takes
 * cover off**, which is what keeps the doubling from reaching the relay's per-account budget (see the
 * subordination section; the earlier gloss here, "which no human sender approaches", was the claim
 * review round 7 refuted), and the synthetic conversation receives cover frames
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
 * **The gap no longer delays any real send** — it is drawn and slept only after the real frame is on
 * the socket — so R-U3-1 no longer sets the ceiling. Three other things do:
 *
 * - **Uniform**, because uniform is the maximum-entropy distribution over a bounded support: given
 *   that a bound exists at all, any other shape hands the observer a better-than-uniform prior on
 *   the gap. An unbounded distribution (an exponential, the shape a Poisson cadence would suggest)
 *   is rejected because its mode at zero makes short gaps *more* likely, i.e. more guessable, and
 *   its tail makes the point below worse without limit.
 * - **The ceiling is set by R-U3-3, not by latency.** The cover frame is emitted by the sending
 *   coroutine itself, so a gap the session does not outlive would be a cover frame that never goes —
 *   producing exactly the *marked*, unpaired real frame R-U3-3 forbids. [GAP_MAX_MS] keeps that
 *   window small and [stop]'s drain closes it, but neither is a licence to widen the gap: the drain
 *   is bounded work done while a user is locking their vault.
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
 *   turns the gap into a stable device fingerprint linking pairs to each other and sessions to each
 *   other. The parameter type makes that unrepresentable rather than relying on every caller passing
 *   the right thing.
 *
 * ## Locks, and the one this class does hold
 *
 * There is **no lock on the path a real send takes**, and that is unchanged: the coordinator
 * publishes before this class is entered, so no real frame can queue behind a lock of this class's.
 *
 * > **⚠️ CORRECTED (fix round 6). This paragraph used to end "the delay cover traffic adds to a real
 * > send is not small, it is none", which was true of the LOCK and false of the WORKER.** Under the
 * > confinement contract [cover] runs on the same single dispatcher every real send runs on, so a
 * > real send dispatched while [buildCover] is in progress waits for that build — milliseconds of
 * > CPU and one vault read, and [pressure] removes it entirely under load, but not *none*. The drawn
 * > gap does not add to it: `sleep` suspends, and a suspended coroutine holds no worker.
 * >
 * > **The occupancy is deliberate and must not be "fixed".** The build sits on that worker with no
 * > suspension point in it precisely because that is what makes a pairing's admission atomic against
 * > teardown — which is what retired the drain's 100 ms deadline and closed the split-pair class in
 * > rounds 4 and 5. Moving it off the worker to save a few milliseconds of scheduling would reinstate
 * > two P1s. Spec §4.3 carries it as a priced trade, and this correction is what the honest version
 * > of the claim says.
 *
 * [teardown] is a different lock with a different job: it serialises *cover* work against *teardown*
 * only. It is taken after the real frame is already gone, it is never held across a suspension, and
 * **there is no wait on it at all** — the drain has nothing to wait for (fix round 4), so the only
 * way to block on it is the lock's own uncontended acquisition. Under the confinement contract even
 * that never contends, because teardown and the sending coroutine are the same worker.
 *
 * ## Lock order
 *
 * [teardown] is a leaf for the send path — [cover] holds nothing else while taking it, and calls
 * [recipient] and [sender] (which take `DecoySectionLock` and the vault runtime's own locks
 * internally) **outside** it. [ensureProvisioning] takes it, and takes nothing else under it: the
 * `scope.launch` it performs there only allocates and dispatches. [stop] and [quiesce] hold it
 * across `WsClient.sendMessage` and the transport lambda, neither of which takes a lock this class
 * can be waiting on. The documented order (section → stateLock → session → storage) is untouched.
 *
 * ## Provisioning is triggered HERE — the first thing in the tree that spends a registration
 *
 * U1 and U2 shipped deliberately unwired. [ensureProvisioning] is the trigger, and it fires from a
 * real send that has already flushed durably and already gone out — never at vault creation, never
 * at unlock, never from a send whose durable barrier failed. That is §6.2a's laziness rule ("a vault
 * that never sends never spends a registration"); every other budget rule — the one-attempt-per-
 * runtime latch, the write-ahead deferral, the silent degradation — lives in
 * [DecoyAccountProvisioner] and is not restated here. The launch is fire-and-forget by requirement:
 * waiting on a multi-second proof-of-work would block the pairing behind it.
 *
 * **[provisioning] bounds CONCURRENT attempts to one, not attempts per session, and that distinction
 * is a fix (round 3).** It used to be a once-per-session latch, which silently retired a property U1
 * pins explicitly: *"a back-off window that expires mid-session still gets its one attempt"*. A
 * durable back-off left by a prior session's 429 makes `provisionIfNeeded` return without burning
 * `Gate.attempted` — a local refusal is one *check*, not the one *attempt* — so a session that made
 * its single call inside that window would never call again and cover traffic stayed off for the
 * whole session even after the window expired. The latch is now released when the job completes, so
 * a later send re-enters; the registration budget is unaffected because it was never this latch's
 * job — `DecoyAccountProvisioner`'s runtime-scoped `Gate.attempted` is the guard that protects the
 * shared worldwide bucket, and it is deliberately not duplicated here.
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
    /**
     * The R-U3-1 yield: whether a shared resource is under pressure, in which case cover is dropped
     * rather than allowed to compete. **No default** — a `CoverPressure` wired to a queue reading
     * that is always 0 is a disabled defence that looks live, which is the round-5 failure mode.
     */
    private val pressure: CoverPressure,
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

    private val provisioning = AtomicBoolean(false)

    @Volatile
    private var provisionJob: Job? = null

    /**
     * Serialises cover work against teardown, and **nothing else**. Guards [transportInvalid] and
     * [inFlight]. Never held across a suspension point.
     *
     * Under the [CoverTraffic] confinement contract this lock is never contended — teardown and the
     * sending coroutine are the same worker. It is kept anyway: see "the one thing an implementation
     * cannot enforce for itself" in the class kdoc.
     */
    private val teardown = ReentrantLock()

    /** True from the moment [stop] is about to invalidate the transport. Terminal; never cleared. */
    private var transportInvalid = false

    /**
     * Every pairing admitted and not yet finished. @GuardedBy [teardown].
     *
     * **Every member is already BUILT** (fix round 4) — a pairing is admitted with its cover frame
     * in hand, so the drain has nothing to wait for and needs no deadline.
     */
    private val inFlight = mutableSetOf<Pending>()

    /**
     * One admitted pairing: a cover frame that has been built and not yet emitted.
     *
     * **MEMBERSHIP OF [inFlight] IS THE RIGHT TO EMIT**, which is why there is no `emitted` flag:
     * whoever removes a pending from the register emits its frame, and the removal happens under the
     * lock, so exactly one of the two ever does — the drain, or the sending coroutine waking from
     * its gap (or unwinding through cancellation).
     */
    private class Pending(val decoy: MessageEnvelope)

    override suspend fun cover(real: MessageEnvelope) {
        // The real frame is already on the socket and has already been charged to every shared
        // resource this class can see, so it is counted whatever happens next. Recording it BEFORE
        // the yield below is what lets a session that is shedding cover keep measuring its own send
        // rate — otherwise the meter would empty itself the moment it worked.
        pressure.recordFrame()
        // R-U3-1 SUBORDINATION, and the FIRST thing after that: where a shared resource is contended,
        // cover yields — no build, no vault read, no provisioning launch, no frame. Ahead of the
        // teardown check because it is the cheaper of the two and neither can be wrong here: both
        // answers are "this send goes uncovered", and the real frame has already gone either way.
        if (pressure.yielding()) return
        // BUILD FIRST, ADMIT SECOND — the reverse of round 3, and safe for the reason set out in the
        // class kdoc: teardown runs on this same worker, so this whole prologue (the caller's
        // publish tail, this build, the admission below) is ONE uninterrupted slice with no
        // suspension point in it. Nothing can land in the middle of it, so the register never has to
        // hold an unbuilt pairing and the drain never has to wait for one.
        //
        // R-U3-5, checked before the build rather than only at admission: a locked session must not
        // even DO the cover work — no vault read, no identity read, no keypair. Advisory only (the
        // admission below is the authoritative check); it costs one uncontended lock and saves the
        // whole build on every send that races a teardown it has already lost.
        if (teardown.withLock { transportInvalid }) return
        // Non-suspending and total: a refusal is a null, never a throw (R-U3-4 — the real send has
        // already gone and must not be affected).
        val decoy = buildCover(real) ?: return
        val pending = Pending(decoy)
        val admitted = teardown.withLock {
            if (transportInvalid) false else inFlight.add(pending)
        }
        // Teardown has already invalidated the transport. R-U3-5 forbids emitting anything after
        // that point, and it would be refused by the dead socket in any case — and the real frame
        // this would have covered was refused too, because the caller's `ws.sendMessage` ran on this
        // same worker, in this same slice, after the socket was already dead.
        if (!admitted) return
        try {
            sleep(gapMs())
        } finally {
            // R-U3-3: the drawn gap is lost to cancellation, the PAIR is not. An unpaired real frame
            // is a marked frame, and cancellation (vault lock, teardown, backgrounding) is frequent
            // enough that letting it drop the cover frame would mark a recognisable class of sends.
            // Non-suspending, so it still runs while the coroutine is being cancelled.
            finish(pending)
        }
    }

    /**
     * The relay is throttling this account, so cover stops spending its budget (R-U3-1).
     *
     * Deliberately takes no lock and touches nothing else in this class: it arrives on the socket's
     * inbound callback thread, not on the confinement worker, and it must not be able to block that
     * thread or to contend with [teardown] against a send. [CoverPressure] is a `@Volatile` write.
     */
    override fun onRelayRateLimited() = pressure.relayRateLimited()

    override fun stop(invalidateTransport: () -> Unit) = teardown.withLock {
        try {
            // (2) Stop provisioning. Under the lock, which is what closes the CAS-then-assign race:
            // [ensureProvisioning] holds this same lock from its transportInvalid check through the
            // assignment of [provisionJob], so a job either exists here and is cancelled, or has not
            // been created and never will be (the check below the lock sees transportInvalid).
            provisionJob?.cancel()
            provisionJob = null
            // (1) + (3): no pairing admitted from here on, and every pairing already admitted is
            // emitted NOW — gapless, while the socket is still live. There is no wait: every member
            // of the register is already built.
            drainLocked()
        } finally {
            // (4) ONLY NOW — and in a `finally`, because a teardown that fails to invalidate the
            // transport is a session that outlives its own lock. Held under the same lock as the
            // drain, so no pairing can observe a live socket, be admitted, and then find it
            // dead: it is either admitted before this line and drained above, or refused after
            // it and emits nothing.
            inFlight.clear()
            transportInvalid = true
            invalidateTransport()
        }
    }

    override fun quiesce(swapTransport: () -> Unit) = teardown.withLock {
        try {
            // The same drain, for a socket that is being REPLACED rather than closed: every admitted
            // pairing's cover frame goes out gapless on the connection its real frame went out on,
            // so no pair is split across a TLS teardown/reconnect.
            drainLocked()
        } finally {
            // NOT terminal: [transportInvalid] stays false and the register stays open, so the next
            // send over the new socket is paired exactly as before. Held under the lock so a pairing
            // cannot be admitted against the old socket and emitted against the new one.
            inFlight.clear()
            swapTransport()
        }
    }

    /** Emit and retire every admitted pairing, gapless. @GuardedBy [teardown]. */
    private fun drainLocked() {
        val iterator = inFlight.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            // Claim it before emitting: the removal IS the right to emit, and it must not be
            // undone by a throw out of `emit`.
            iterator.remove()
            emit(pending.decoy)
        }
    }

    /**
     * Retire one pairing: emit its cover frame unless a drain already claimed it, or unless the
     * transport is gone (in which case teardown has been and the socket would refuse it anyway).
     */
    private fun finish(pending: Pending) = teardown.withLock {
        if (inFlight.remove(pending) && !transportInvalid) emit(pending.decoy)
    }

    // ── the cover frame ─────────────────────────────────────────────────────────────────────

    /**
     * The cover envelope for one send, or null for "this send goes uncovered".
     *
     * **Total by construction** — it catches everything but cancellation. The real send has *already
     * happened* when this runs, so a throw escaping here would propagate into
     * `MessagingCoordinator`'s `runCatching` and mark a delivered message FAILED. Cover traffic would
     * then have corrupted the state of a send it could not otherwise touch.
     *
     * **Non-suspending on purpose**, and after fix round 4 that is what the whole teardown argument
     * rests on: because there is no suspension point between the caller's `ws.sendMessage` and this
     * frame reaching the register, the confinement worker cannot be handed to teardown in between,
     * so a build is never interrupted and the register never holds an unbuilt pairing. (Round 3 read
     * this as "the drain's wait can only stand behind CPU work, so a bounded wait is safe". That was
     * the P1: non-suspending bounds *suspension*, not *time*. The property is worth having for the
     * reason above, not for that one.)
     */
    private fun buildCover(real: MessageEnvelope): MessageEnvelope? = try {
        val syntheticAccountId = recipient()
        if (syntheticAccountId == null) {
            ensureProvisioning()
            null
        } else {
            // A throw here is R-U3-4: the real send already went, uncovered. See the class kdoc —
            // reaching it is a defect to report, not a case to swallow quietly.
            sender()?.let { from -> builder.build(from, syntheticAccountId, real) }
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
            // A cover frame the socket TOOK is charged to the same per-account budget the real frames
            // draw on, so the meter counts it. One the socket refused never reached the relay and is
            // not counted — the meter measures consumption, not intent.
            if (send(decoy)) pressure.recordFrame()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Silent by requirement — no logging, no diagnostics sink (R-U3-5).
        }
    }

    /**
     * Start a provisioning attempt if none is running.
     *
     * The [AtomicBoolean] bounds the number of CONCURRENT jobs to one — it keeps a hundred sends
     * from launching a hundred coroutines that would each read the vault and return. It is
     * **released when the job completes**, so a later send in the same session can try again; see
     * the provisioning section of the class kdoc for why that is a requirement and not a
     * relaxation. The number of relay REGISTRATIONS is bounded by [DecoyAccountProvisioner]'s
     * runtime-scoped latch, which is the guard that actually protects the shared worldwide bucket.
     *
     * **The whole method runs under [teardown]** (fix round 4), and that is the fix for a real race,
     * not tidiness. Round 3 checked `transportInvalid` under the lock, released it, then won the CAS
     * and assigned [provisionJob] — so a `stop()` landing in between saw a null handle, cancelled
     * nothing, invalidated the transport and returned, and the job then started **after teardown**:
     * a coroutine outliving its session, able to spend a scarce registration from the shared
     * worldwide bucket and to touch a closing vault runtime. Holding the lock across
     * check → CAS → assign makes the two orders the only two possible ones: either `stop()` gets the
     * lock first and this returns without launching, or this assigns first and `stop()` cancels what
     * it finds. `job.start()` on a LAZY job only dispatches, so nothing runs under the lock.
     */
    private fun ensureProvisioning() = teardown.withLock {
        // Nothing decoy-related may start after teardown (R-U3-5).
        if (transportInvalid) return@withLock
        if (!provisioning.compareAndSet(false, true)) return@withLock
        // LAZY so [provisionJob] is assigned before the body can run: stop() must never find a null
        // handle for a job that is already provisioning.
        val job = scope.launch(provisionContext, start = CoroutineStart.LAZY) {
            try {
                provision()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Silent degradation to cover-traffic-off (§6.2a) — never surfaced, never recorded.
            } finally {
                provisioning.set(false)
            }
        }
        provisionJob = job
        job.start()
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
         * frame is already on the socket — it bounds how much work [stop]'s drain has to do while a
         * user is locking their vault. See the class kdoc.
         */
        const val GAP_MAX_MS: Int = 50

        // There is deliberately no DRAIN_TIMEOUT_MS any more. Round 3 had one, and it was a P1: the
        // drain abandoned any pairing whose build overran 100 ms, which "non-suspending" does not
        // prevent (slow crypto, scheduler starvation, a stalled `recipient()`), and abandoning one
        // is exactly the teardown-correlated unpaired real frame the drain exists to prevent. The
        // register now only ever holds BUILT pairings, so the drain has nothing to wait for and
        // there is no wall clock in this class at all.
    }
}
