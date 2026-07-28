// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import kotlinx.coroutines.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * **The yield policy for cover traffic — the whole of spec §4.3 R-U3-1's second half.**
 *
 * R-U3-1 (rewritten 2026-07-28): *"cover traffic must never compete with a real send for any
 * resource. Where a shared resource is contended — the transport's outbound queue, the relay's send
 * budget — **cover yields**: it is dropped, not queued ahead of, not charged against, the real
 * frame."* This class answers one question — [yielding] — and that answer is the yield.
 *
 * ## Why this exists as a class rather than three `if`s in [DecoySendPairing]
 *
 * Round 5 of U3's review found a defence that nothing tested, because it lived inside a class the
 * suite could only reach through the send path. The lesson was to make the mechanism a production
 * type with its own tests, so this one is: the thresholds, the sliding window and the off-window are
 * all directly drivable, and every branch below is executed by `CoverPressureTest` rather than
 * inferred from a source string.
 *
 * ## The rule: BE GENEROUS, and do not predict
 *
 * Maintainer ruling, recorded in the spec: *"Do not compute exact remaining capacity or try to spend
 * the last safe slot — drop on any signal of pressure, and stay off for a window rather than
 * stutter."* Every design decision here follows from that:
 *
 *  - **Nothing here knows what any limit is.** The relay's `sendLimit` is a server constant it never
 *    communicates, and OkHttp's queue cap is an implementation detail of a library. An earlier
 *    ruling called a client-side budget defence *unsound* for exactly that reason — but that
 *    reasoning assumed the client had to **predict** the limit. It does not: it only has to **stop
 *    competing** once something tells it the resource is under pressure, which needs no knowledge of
 *    the limit at all. That is what makes this sound where a headroom policy would not be.
 *  - **Every threshold errs low.** [QUEUE_WATERMARK_BYTES] is 8 KiB against OkHttp's 16 MiB cap —
 *    0.05% — because a healthy socket's queue is empty and any backlog at all means the writer
 *    thread is behind. [RATE_FRAMES] is 40 frames per [RATE_WINDOW_MS], which keeps at least 60% of
 *    the relay's nominal 100/min budget free for real sends at all times.
 *  - **A trip turns cover off for a WINDOW, not for one send.** Stuttering is what R-U3-3 rules out;
 *    a decision that holds for [OFF_WINDOW_MS] is one consistent state, and the window is the same
 *    width as the relay's own bucket so a trip outlives the pressure that caused it.
 *
 * ## The disclosure bound (R-U3-3), checked against every signal here
 *
 * The bound is *"cover must not fail in ways that reveal events an observer cannot **already**
 * observe"* — DISCLOSURE, not correlation-with-anything. Load-shedding is DEGRADATION and is
 * explicitly permitted:
 *
 *  - **Queue depth over the watermark** correlates with a socket whose writer is behind. An observer
 *    watching that connection sees the writes not happening; they learn nothing new.
 *  - **A high recent send rate** correlates with the user sending a lot. The burst of frames is the
 *    thing they are already watching.
 *  - **`rate_limited`** correlates with the relay throttling this account, which follows a burst the
 *    observer has just seen.
 *
 * None of them names a *client lifecycle* event — vault lock, teardown, a transport change — which
 * is the class rounds 3–5 closed and which nothing here may reopen. That is why the drain in
 * [DecoySendPairing.stop] and [DecoySendPairing.quiesce] does **not** consult this class: a pairing
 * already admitted is emitted unconditionally, so no lock and no transport swap can ever be the
 * reason a cover frame is missing.
 *
 * ## What this class deliberately does NOT do
 *
 * It holds no timer, starts no coroutine, writes nothing durable and logs nothing (R-U3-5). It is
 * pure in-memory state owned by the pairing seam, so it dies with the session — which also means the
 * rate meter starts empty in a new session even though the relay's bucket does not. That is stated
 * as a residual on [RATE_FRAMES] rather than papered over; the alternative would be storage, which
 * R-U3-5 forbids outright.
 */
class CoverPressure(
    /**
     * Bytes already queued for transmission on the live transport and not yet written —
     * `WsClient::outboundQueueBytes` in production, which is OkHttp's own `WebSocket.queueSize()`.
     *
     * **This must be the real socket's reading.** A supplier that always answers 0 disables the one
     * signal that closes the outbound-queue mechanism, so production wiring is pinned by a tripwire
     * rather than left to a default — there is deliberately no default value for this parameter.
     */
    private val queuedBytes: () -> Long,
    /**
     * A MONOTONIC millisecond clock. `System.nanoTime` in production because the windows here are
     * durations, and a wall clock that steps backwards over an NTP correction would strand cover
     * traffic in the off state. A seam only so the tests can drive the windows without sleeping.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /**
     * Guards [recent] and [written], and nothing else. Never held across a suspension (there are no
     * suspending members), never taken while holding any other lock, and never taken on the path
     * between a real send's durability barrier and its socket handoff — every caller of
     * [recordFrame] runs strictly **after** `ws.sendMessage` has returned.
     */
    private val meter = ReentrantLock()

    /**
     * The times of the last [RATE_FRAMES] `message.send` frames this session put on the socket, as a
     * ring. Only the OLDEST of them is ever read, which is all a "N frames within T" test needs, so
     * the meter costs one array slot per frame and no allocation.
     */
    private val recent = LongArray(RATE_FRAMES)

    /** Total frames recorded. `written % RATE_FRAMES` indexes the oldest of the last [RATE_FRAMES]. */
    private var written = 0L

    /**
     * Cover is off until this reading of [nowMs]. `Long.MIN_VALUE` — not 0 — because [nowMs] is
     * monotonic-but-arbitrary and may legitimately be negative.
     *
     * `@Volatile`: written from the transport's inbound callback thread ([relayRateLimited], which
     * the socket listener drives) and read on the coordinator's confined worker.
     */
    @Volatile
    private var offUntil: Long = Long.MIN_VALUE

    /**
     * One `message.send` frame — real or cover — was accepted by the transport.
     *
     * Called for the REAL frame at the top of [DecoySendPairing.cover] (which the coordinator enters
     * only on a genuine handoff) and for a cover frame that the socket took. Both charge the same
     * per-account relay bucket, so both are counted: the meter measures **budget consumption**, not
     * user activity.
     */
    fun recordFrame() = meter.withLock {
        recent[(written % recent.size).toInt()] = nowMs()
        written++
    }

    /**
     * The relay answered `rate_limited` — it refused a `message.send` for volume.
     *
     * This is the only signal the relay gives us about the shared per-account budget, and it carries
     * no message id, so it cannot say *which* frame was refused. **It does not have to.** Cover is
     * the discardable half by construction, so the correct response to "the budget is contended" is
     * to stop spending it, immediately and for a full [OFF_WINDOW_MS] — which is also a full width of
     * the relay's own bucket.
     */
    fun relayRateLimited() {
        offUntil = nowMs() + OFF_WINDOW_MS
    }

    /**
     * **Must cover yield?** True means: emit nothing, build nothing, start nothing — this send goes
     * uncovered and the real frame keeps every resource to itself.
     *
     * Evaluated once per send, at the top of [DecoySendPairing.cover], before any cover-side work
     * including provisioning. A trip arms the off-window, so the answer is stable for
     * [OFF_WINDOW_MS] rather than flapping per send.
     *
     * **Total, and it fails toward yielding.** [queuedBytes] reaches a third-party library across a
     * `@Volatile` socket reference; if it ever throws, the answer is "yield", because the real send
     * has already gone and the only thing left to decide is whether to add a frame we are not sure
     * is safe to add. A throw escaping here would instead propagate into `MessagingCoordinator`'s
     * `runCatching` and mark an already-delivered message FAILED — cover traffic corrupting the state
     * of a send it must not be able to touch.
     */
    fun yielding(): Boolean = try {
        val now = nowMs()
        when {
            // Already shedding. Checked first so a re-check inside the window neither extends it nor
            // re-reads the socket: the window is one decision, not a rolling one.
            now < offUntil -> true
            // MECHANISM 1 — the transport's outbound queue. OkHttp buffers frames for its writer
            // thread and refuses (and then CLOSES the connection) once the buffer would pass 16 MiB.
            // A cover frame added to a queue that is already backing up is capacity the next real
            // frame may need, so any backlog at all takes cover out.
            queuedBytes() > QUEUE_WATERMARK_BYTES -> arm(now)
            // MECHANISM 2 — the relay's per-account send budget, without knowing what it is. If this
            // account has put RATE_FRAMES frames on the socket inside RATE_WINDOW_MS it is sending
            // hard, and cover stops adding to the total.
            sendRateHigh(now) -> arm(now)
            else -> false
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        true
    }

    /** Arm the off-window and yield. Always returns true, so it reads as the answer at the call site. */
    private fun arm(now: Long): Boolean {
        offUntil = now + OFF_WINDOW_MS
        return true
    }

    /**
     * Whether the last [RATE_FRAMES] frames all landed inside the trailing [RATE_WINDOW_MS] — a
     * SLIDING window, not a tumbling counter.
     *
     * A tumbling counter would reset on a boundary and let twice the threshold through across two
     * adjacent windows, which is the failure mode a "recent rate" signal exists to catch. Reading
     * only the oldest entry of the ring gives the sliding answer for one array read.
     */
    private fun sendRateHigh(now: Long): Boolean = meter.withLock {
        if (written < recent.size) return@withLock false
        now - recent[(written % recent.size).toInt()] < RATE_WINDOW_MS
    }

    companion object {
        /**
         * Outbound-queue watermark, in bytes. **Low on purpose** — 8 KiB is roughly eight
         * `message.send` frames (spec §2.1: 829–1169 B each) against OkHttp's 16 MiB cap, so cover
         * yields three orders of magnitude before the queue could refuse anything. A healthy socket
         * sits at 0 and a live pair briefly at ~2 KiB, so this is ~4× ordinary peak rather than a
         * computed headroom.
         */
        const val QUEUE_WATERMARK_BYTES: Long = 8L * 1024

        /**
         * Frames within [RATE_WINDOW_MS] that count as "sending hard". Both halves of a pair are
         * counted, so cover shuts off after ~20 covered sends in a minute and can never have
         * contributed more than 20 frames to any minute's total — leaving at least 60 of the relay's
         * nominal 100/min for real sends.
         *
         * **The residual, stated rather than implied:** the ~20 cover frames emitted at the *onset*
         * of a burst, before this trips, are still charged to the account. If that same minute then
         * carries more than 80 real frames the real sends at the tail lose permits those cover frames
         * spent, and only the relay's `rate_limited` ([relayRateLimited]) closes it — after the fact.
         * Eliminating it would require predicting a limit the relay never states, which the ruling
         * above rejects; shrinking it further would mean shedding cover during ordinary conversation,
         * which is the whole feature. The meter also starts empty in a new session while the relay's
         * bucket does not (R-U3-5 forbids storing it), so a lock/unlock inside one minute resets it.
         */
        const val RATE_FRAMES: Int = 40

        /** The rate meter's trailing window. One width of the relay's own per-minute bucket. */
        const val RATE_WINDOW_MS: Long = 60_000

        /**
         * How long cover stays off after any trip. **A window, not a send**, per R-U3-3: a condition
         * that prevents cover must produce a consistent state for as long as it lasts rather than a
         * stutter. One relay bucket wide, so a trip outlives the burst that caused it.
         */
        const val OFF_WINDOW_MS: Long = 60_000
    }
}
