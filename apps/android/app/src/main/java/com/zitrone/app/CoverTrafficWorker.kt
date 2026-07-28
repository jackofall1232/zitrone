// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * **Where cover-traffic teardown and transport swaps run** — extracted from `MessagingCoordinator`
 * in U3 fix round 5, because the property it carries could not otherwise be tested.
 *
 * ## What this class exists to guarantee
 *
 * `MessagingCoordinator` runs every send on ONE worker ([CoroutineContext] `confined`). The whole U3
 * teardown argument rests on that: the slice from a send's `ws.sendMessage` to its pairing's
 * admission has no suspension point in it, so teardown *enqueued on the same worker* runs strictly
 * before or strictly after that slice and never inside it. Nothing about the argument survives
 * teardown running somewhere else.
 *
 * ## THE ROUND-5 FIX — one primitive was doing two incompatible jobs
 *
 * Round 4 routed BOTH terminal teardown and the non-terminal transport swap through a single helper
 * that dispatched onto the worker, waited 250 ms, and then **ran the work on the calling thread**.
 * For terminal teardown that fallback is safe and necessary: `CoverTraffic.stop` sets its own
 * terminal flag and refuses every later admission, and the alternative to a bound is a vault lock
 * that hangs and never wipes its keys.
 *
 * For a transport swap it was a **P1** (both round-4 reviewers, independently; third-lens tie-break).
 * `CoverTraffic.quiesce` deliberately leaves the register OPEN — that is the whole point of a
 * non-terminal drain. So when the fallback fired it drained an empty register on the calling thread,
 * swapped the socket, and left a send that was mid-slice on the worker free to admit a pairing and
 * emit its cover frame **on the new connection while its real frame had gone out on the old one**: a
 * split pair straddling a TLS boundary, correlated with the user changing their anonymity transport.
 * And no coroutine suspension was needed for it — the "uninterruptible slice" argument only holds
 * against teardown running *on the worker*, and the fallback had just taken teardown off it. The
 * fallback did not merely have an unjustified bound; **it structurally defeated the confinement
 * argument, precisely when it fired.**
 *
 * ## Why the fallback could not simply be removed: a real lock inversion
 *
 * Dropping the fallback while the caller still held the app's transport lock reinstates a verified
 * deadlock (round-4 tie-break, five-step chain):
 *
 * 1. `ZitroneApp.applyTransport` takes `transportLock` and called the blocking reconnect under it.
 * 2. The reconnect waits on work queued on `confined`.
 * 3. `MessagingCoordinator.deleteAccountAndWipe` already runs on that worker.
 * 4. Its `onConfirmed` calls `MainActivity`'s `lockIf`.
 * 5. `lockIf → UnlockController.stopSession` takes `transportLock`.
 *
 * **So the lock boundary was fixed, not the fallback.** `ZitroneApp` now resolves and installs the
 * new endpoints and captures the live session under `transportLock`, **releases it**, and only then
 * asks for a reconnect. That reconnect is therefore free to be what it must be: confined to the
 * worker, with **no caller-thread fallback and no wait at all**. The decisive property — drain and
 * socket swap atomic with respect to every publish/admit slice — is kept; the lock edge that forced
 * the timeout is gone.
 *
 * ## The three entry points, and why they are three
 *
 * | Entry | Thread | Bound | Fallback |
 * |---|---|---|---|
 * | [runTerminalHere] | the caller's, which must ALREADY be the worker | none | n/a |
 * | [runTerminalConfined] | the worker, or the caller after [TERMINAL_TEARDOWN_WAIT_MS] | yes, both waits | yes — key wipe must not hang |
 * | [requestReconnect] | the worker, ALWAYS | none — it does not wait | **never** |
 *
 * They share one terminal latch ([terminal]) so that teardown is exactly-once however it is reached,
 * and so that a reconnect queued behind a teardown is dropped rather than redialling a dead session.
 */
internal class CoverTrafficWorker(
    private val scope: CoroutineScope,
    /** `MessagingCoordinator.confined` — the single worker every send already runs on. */
    private val confined: CoroutineContext,
    /** Seam for [TERMINAL_TEARDOWN_WAIT_MS], so the bounded paths are testable without a wall clock. */
    private val terminalWaitMs: Long = TERMINAL_TEARDOWN_WAIT_MS,
) {

    /**
     * Won exactly once, by whichever of the three entry points reaches terminal teardown first.
     * Atomic rather than `@Volatile`: [runTerminalConfined] has two racing runners (the worker and
     * its own fallback) and the winner has to be decided, not merely observed.
     */
    private val terminal = AtomicBoolean(false)

    /** Monotonic id of the newest requested transport state — see [requestReconnect]. */
    private val requested = AtomicLong(0)

    /** Whether terminal teardown has begun or completed. */
    val isTerminal: Boolean get() = terminal.get()

    /**
     * Run terminal teardown **on the calling thread, which must already be the confined worker** —
     * the account-delete path, which is a coroutine already running there. Dispatching to the worker
     * from the worker and then blocking on the result would stall for the whole bound before falling
     * back, so this path deliberately does not dispatch.
     *
     * @return whether this call is the one that ran it (false = someone else already has, or is
     * running it right now).
     */
    fun runTerminalHere(teardown: () -> Unit): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        teardown()
        return true
    }

    /**
     * Run terminal teardown ON the worker and block until it has — the construction that closes the
     * round-3 R-U3-1 residual (see the class kdoc).
     *
     * **Why it blocks.** `UnlockController` calls `MessagingCoordinator.stop()` and then closes the
     * vault runtime in a `finally` — the final reseal and key-material wipe. Cover traffic reads that
     * runtime to build frames, so returning before the drain has run would let the wipe race a build;
     * and a session whose socket outlives its own lock is worse than any framing defect.
     *
     * **Why it is bounded, on BOTH waits.** [terminalWaitMs] bounds no cover-side work — there is
     * none left to bound, the drain has no wait in it. It bounds only *how long we wait for the
     * single worker to become free*, and it exists because the alternative to a bound here is a vault
     * lock that can hang and never wipe its keys. Round 4 bounded the first wait and left the second
     * one unbounded, which silently reintroduced exactly the failure the bound exists to prevent
     * (round-5 finding): if the worker claimed the teardown at the boundary and then wedged, `stop()`
     * hung forever while holding `transportLock` and `runtime.close()` never ran. Both waits are
     * bounded now, for one rationale rather than two.
     *
     * If the first bound expires, teardown falls back to the calling thread. **That fallback is safe
     * here and only here:** `CoverTraffic.stop` invalidates the transport, so a send whose slice is
     * still on the worker is refused admission and emits nothing. The residual it leaves is an
     * unpaired REAL frame on that one teardown — never a lone decoy, and never a split pair. It is
     * declared in spec §4.3 and exercised by a test.
     */
    fun runTerminalConfined(teardown: () -> Unit) {
        if (isTerminal) return
        val done = CountDownLatch(1)
        // NonCancellable, and deliberately: the session scope is cancelled immediately after this
        // returns, and a teardown that never ran is a socket that never closed.
        scope.launch(confined + NonCancellable) {
            try {
                runTerminalHere(teardown)
            } finally {
                done.countDown()
            }
        }
        if (done.await(terminalWaitMs, TimeUnit.MILLISECONDS)) return
        // Either we take it over, or the worker claimed it in the same instant — in which case wait
        // for it, BOUNDED by the same rationale as the first wait.
        if (!runTerminalHere(teardown)) done.await(terminalWaitMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Ask for a NON-TERMINAL transport swap (Tor/I2P toggle): drain the admitted pairings, replace
     * the socket, and keep pairing over the new one.
     *
     * **Confined, and it never runs on the caller.** That is the round-5 fix and the reason this is a
     * separate entry point rather than a reuse of [runTerminalConfined]: `CoverTraffic.quiesce` keeps
     * the register open, so running it anywhere but the worker splits any pair whose real frame is
     * already on the old socket. There is no fallback and no bound because there is nothing to bound
     * — this **returns immediately**, and the caller no longer holds the app's transport lock while
     * it waits (it does not wait at all).
     *
     * **Skipped once terminal teardown has begun or completed**: a session being torn down must not
     * redial a socket, and `CoverTraffic.stop` has already drained and invalidated.
     *
     * **Coalesced by generation.** Every request takes the next id; on the worker, a request that is
     * no longer the newest drops out. Several transport changes queued behind a busy worker therefore
     * produce ONE reconnect, to the newest requested state — and the endpoints themselves were
     * already installed under `transportLock` before this was called, so the surviving reconnect
     * always dials the current transport regardless of which generation won.
     *
     * Not `NonCancellable`, also deliberately: if the session scope dies before this runs, the right
     * answer is not to redial.
     */
    fun requestReconnect(reconnect: () -> Unit) {
        val mine = requested.incrementAndGet()
        scope.launch(confined) {
            if (isTerminal) return@launch
            if (mine != requested.get()) return@launch
            reconnect()
        }
    }

    companion object {
        /**
         * How long [runTerminalConfined] waits for the confinement worker to become free, per wait.
         * **It bounds scheduling, not cover-side work** — see that method's kdoc. Terminal teardown
         * runs on a user-visible path (vault lock, under the app's transport lock), so it is kept
         * short enough that the worst case is not a visible stall, and its expiry degrades to a
         * declared residual rather than to a lost socket.
         *
         * It is deliberately NOT shared with [requestReconnect], which has no bound because it has
         * no wait: reusing this number there was the round-4 P1.
         */
        const val TERMINAL_TEARDOWN_WAIT_MS = 250L
    }
}
