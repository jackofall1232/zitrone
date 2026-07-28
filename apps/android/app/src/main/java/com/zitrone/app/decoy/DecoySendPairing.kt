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
 */
interface CoverTraffic {

    /**
     * Emit cover traffic for [real] — **an envelope the caller has ALREADY handed to the socket.**
     *
     * Implementations may suspend for as long as they like: nothing they do can reach the real send,
     * because the real send is over. They must not throw: a throw here would propagate into
     * `MessagingCoordinator`'s `runCatching` and mark an already-delivered message FAILED.
     * Cancellation still propagates — it is the caller's own cancellation.
     */
    suspend fun cover(real: MessageEnvelope)

    /**
     * Session teardown (R-U3-5) — and **the transport's own invalidation is handed to this method
     * rather than performed beside it.**
     *
     * Round 2's teardown disconnected the socket first (`ws.disconnect()`) and stopped cover second,
     * which put a lone real frame followed by a TLS close on the wire every time a vault locked
     * during a drawn gap: a deterministic, recognisable class of unpaired real sends correlated with
     * lock, teardown and backgrounding — precisely what R-U3-3 calls worse than no cover at all.
     * Merely swapping the two statements is **not** sufficient, because a `stop()` that cancels only
     * the provisioning job does not own the pairings already admitted. So the ordering is expressed
     * as a *dependency* instead of as a convention: an implementation must
     *
     *  1. stop admitting new pairings,
     *  2. stop provisioning,
     *  3. cancel, complete or drain every pairing it has already admitted,
     *  4. and only then run [invalidateTransport].
     *
     * [invalidateTransport] runs exactly once, and the caller must not invalidate the transport
     * itself — that is the point of passing it.
     */
    fun stop(invalidateTransport: () -> Unit)

    companion object {
        /** Cover traffic off: the real send path, unchanged, and teardown in its original order. */
        val NONE: CoverTraffic = object : CoverTraffic {
            override suspend fun cover(real: MessageEnvelope) = Unit
            override fun stop(invalidateTransport: () -> Unit) = invalidateTransport()
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
 *    one did not need. (**Cross-send** preemption — pair N's cover frame taking the permit pair N+1's
 *    real frame wanted — survives every ordering, is inherent to doubling the volume on a shared
 *    per-account budget, and is a **relay-side** item: `sendLimit` is a server constant the relay
 *    never communicates, so no client-side headroom policy is sound. It is not defended against
 *    here, deliberately.)
 *  - **A cover-side throwable suppressing the real publish.** There is no longer any construction in
 *    which cover code could run before the publish, so there is nothing left for it to skip.
 *
 * **What the ruling cost, recorded rather than quietly dropped:** an observer watching *both* ends
 * of the network no longer gets 5–50 ms of ambiguity about which of the two frames was real. It
 * reads `recipient_id` in cleartext on both envelopes regardless, so the loss is close to nil; a
 * one-sided observer sees two equal-length frames either way. Spec §2.4 carries it as a residual.
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
 *  - [cover] admits a pairing (a [Pending] in [inFlight]) as its first action, then builds, then
 *    sleeps the drawn gap, then emits.
 *  - [stop] takes the same lock, **emits every admitted pairing's cover frame immediately, gapless,
 *    while the socket is still live**, and only then runs `invalidateTransport`. A pairing whose
 *    frame is still being built is waited for — bounded by [DRAIN_TIMEOUT_MS], and in practice
 *    instant, because **there is no suspension point between admission and the built frame being
 *    handed to the drain**: `buildCover` is a plain non-suspending function, so an admitted pairing
 *    always resolves in straight-line CPU time rather than behind I/O.
 *  - Whichever of the two removes a pairing from the register is the one that emits its frame, so a
 *    cover frame goes out exactly once — see [Pending].
 *
 * **The residual, stated rather than claimed away.** One window survives, and it is *forced by the
 * requirement above it*: between `ws.sendMessage` returning and [cover] taking the lock there are a
 * handful of instructions in which teardown can slip past. Closing it would mean registering the
 * pairing *before* the real publish — i.e. putting cover-side work back in front of the handoff, and
 * a lock a real send could queue on, which R-U3-1 forbids absolutely. So the two requirements meet
 * here, and what is left is a few instructions with no suspension, no I/O and no allocation of
 * consequence — the same class as "the socket dies between the two writes", which is already
 * accepted for ordinary network loss and which no ordering can remove. Round 2's window was 5–50 ms
 * wide and caught *every* pairing that was mid-gap; this one is not a window teardown can be relied
 * on to hit.
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
 * publishes before this class is entered, so no real frame can queue behind anything here. The delay
 * cover traffic adds to a real send is not small, it is none.
 *
 * [teardown] is a different lock with a different job: it serialises *cover* work against *teardown*
 * only. It is taken after the real frame is already gone, it is never held across a suspension, and
 * the only blocking wait on it is [stop]'s bounded drain.
 *
 * ## Lock order
 *
 * [teardown] is a leaf for the send path — [cover] holds nothing else while taking it, and calls
 * [recipient] and [sender] (which take `DecoySectionLock` and the vault runtime's own locks
 * internally) **outside** it. [stop] holds it across `WsClient.sendMessage` and `WsClient.disconnect`,
 * neither of which takes a lock this class can be waiting on. The documented order (section →
 * stateLock → session → storage) is untouched.
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
    /** Monotonic clock for [stop]'s drain deadline. A seam so the timeout is testable. */
    private val nanoTime: () -> Long = System::nanoTime,
) : CoverTraffic {

    private val provisioning = AtomicBoolean(false)

    @Volatile
    private var provisionJob: Job? = null

    /**
     * Serialises cover work against teardown, and **nothing else**. Guards [transportInvalid],
     * [inFlight] and every field of every [Pending] in it. Never held across a suspension point.
     */
    private val teardown = ReentrantLock()

    /** Signalled whenever a [Pending] becomes [Pending.resolved] — [stop]'s drain waits on it. */
    private val resolved = teardown.newCondition()

    /** True from the moment [stop] is about to invalidate the transport. Terminal; never cleared. */
    private var transportInvalid = false

    /** Every pairing admitted and not yet finished. @GuardedBy [teardown]. */
    private val inFlight = mutableSetOf<Pending>()

    /**
     * One admitted pairing. Both fields are @GuardedBy [teardown], deliberately: teardown and the
     * sending coroutine race for the right to emit, and one lock is easier to argue about than two
     * atomics.
     *
     * **MEMBERSHIP OF [inFlight] IS THE RIGHT TO EMIT**, which is why there is no `emitted` flag:
     * whoever removes a pending from the register emits its frame, and the removal happens under the
     * lock, so exactly one of the two ever does. That matters in one real window — [stop]'s drain
     * releases the lock while it waits for a pairing that is still building, and a pairing it has
     * already emitted can wake inside that window and reach [finish] with the transport still valid.
     */
    private class Pending {
        /** The cover frame has been built (or refused, leaving [decoy] null). */
        var resolved = false
        var decoy: MessageEnvelope? = null
    }

    override suspend fun cover(real: MessageEnvelope) {
        // ADMIT FIRST, BUILD SECOND. The register is what makes this pairing teardown's problem, so
        // it is taken before any work that could take time — a pairing that is still building when
        // the vault locks is waited for, not abandoned.
        val pending = Pending()
        val admitted = teardown.withLock {
            if (transportInvalid) false else inFlight.add(pending)
        }
        // Teardown has already invalidated the transport. R-U3-5 forbids emitting anything after
        // that point, and it would be refused by the dead socket in any case. (The real frame this
        // would have covered had almost certainly been refused too — see the residual in the class
        // kdoc for the handful of instructions in which it might not have been.)
        if (!admitted) return
        try {
            // Non-suspending and total: from admission to the frame being handed to the drain below
            // there is no suspension point, which is what bounds stop()'s wait to CPU time.
            val decoy = buildCover(real)
            val proceed = teardown.withLock {
                pending.resolved = true
                pending.decoy = decoy
                resolved.signalAll()
                // Skip the gap if teardown has been — waiting can then only lose the frame, and
                // [finish] will find the register no longer holds this pairing.
                decoy != null && !transportInvalid
            }
            if (proceed) sleep(gapMs())
        } finally {
            // R-U3-3: the drawn gap is lost to cancellation, the PAIR is not. An unpaired real frame
            // is a marked frame, and cancellation (vault lock, teardown, backgrounding) is frequent
            // enough that letting it drop the cover frame would mark a recognisable class of sends.
            // Non-suspending, so it still runs while the coroutine is being cancelled.
            finish(pending)
        }
    }

    override fun stop(invalidateTransport: () -> Unit) {
        // (2) Stop provisioning. Outside the lock: cancellation is non-blocking and the job never
        // touches [teardown].
        provisionJob?.cancel()
        provisionJob = null
        teardown.withLock {
            try {
                // (1) + (3): no pairing admitted from here on, and every pairing already admitted is
                // emitted NOW — gapless, while the socket is still live. A pairing still building is
                // waited for; [DRAIN_TIMEOUT_MS] is a backstop for a thread that never gets
                // scheduled, not an expected path, because the section it is in cannot suspend.
                val deadline = nanoTime() + DRAIN_TIMEOUT_MS * 1_000_000L
                while (true) {
                    drainResolvedLocked()
                    if (inFlight.isEmpty()) break
                    val remaining = deadline - nanoTime()
                    if (remaining <= 0L) break
                    resolved.awaitNanos(remaining)
                }
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
    }

    /** Emit and retire every pairing whose frame is ready. @GuardedBy [teardown]. */
    private fun drainResolvedLocked() {
        val iterator = inFlight.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (!pending.resolved) continue
            // Claim it before emitting: the removal IS the right to emit, and it must not be
            // undone by a throw out of `emit`.
            iterator.remove()
            pending.decoy?.let(::emit)
        }
    }

    /**
     * Retire one pairing: emit its cover frame unless teardown's drain already claimed it, or unless
     * the transport is gone (in which case the drain has been and the socket would refuse it anyway).
     */
    private fun finish(pending: Pending) = teardown.withLock {
        val ours = inFlight.remove(pending)
        // Released BEFORE the emit: a pairing cancelled before its frame was built still has to
        // release stop()'s drain, and `emit` rethrows CancellationException.
        pending.resolved = true
        resolved.signalAll()
        val decoy = pending.decoy
        if (ours && decoy != null && !transportInvalid) emit(decoy)
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
     * **Non-suspending on purpose**, and that is load-bearing twice over: it is what lets [stop]'s
     * drain wait for an admitted pairing without the wait ever standing behind I/O, and it keeps the
     * whole cover-side build off any suspension seam a teardown could interleave with.
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
            send(decoy)
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
     */
    private fun ensureProvisioning() {
        // Nothing decoy-related may start after teardown (R-U3-5).
        if (teardown.withLock { transportInvalid }) return
        if (!provisioning.compareAndSet(false, true)) return
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

        /**
         * Backstop for [stop]'s drain, in milliseconds. It is NOT the expected wait: every admitted
         * pairing resolves in straight-line CPU time (there is no suspension point between admission
         * and the built frame reaching the drain), so this only bounds a thread that is not
         * scheduled at all. Teardown runs under the app's transport lock, so the bound is kept short
         * enough that the worst case is not a visible stall.
         */
        const val DRAIN_TIMEOUT_MS: Long = 100
    }
}
