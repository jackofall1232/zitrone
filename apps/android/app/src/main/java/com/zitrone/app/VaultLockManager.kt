// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The action the idle auto-lock should take when the app goes to the background — the pure,
 * host-testable decision, factored out of the [Lifecycle] glue and the coroutine timer.
 */
sealed interface AutoLockAction {
    /** Lock immediately (the "immediate" timeout, or a 0-second setting). */
    data object LockNow : AutoLockAction

    /** Lock after [delayMs] unless the app returns to the foreground first. */
    data class LockAfter(val delayMs: Long) : AutoLockAction

    /** Do nothing — there is no live session to lock, or a delete already owns teardown. */
    data object None : AutoLockAction
}

/**
 * Decide what the idle auto-lock does when the app is backgrounded (D3). Pure, so the branch
 * matrix is verified in host tests without a real [Lifecycle].
 *
 *  - No live session → [AutoLockAction.None]: nothing is unlocked, so there is nothing to lock.
 *  - A terminal (account-delete) wipe in progress → [AutoLockAction.None]: the delete flow owns
 *    teardown; a background timer must not race its ordered teardown.
 *  - timeout ≤ 0 → [AutoLockAction.LockNow] (the user's "immediate" choice).
 *  - otherwise → [AutoLockAction.LockAfter] the configured timeout.
 */
fun autoLockOnBackground(
    sessionLive: Boolean,
    terminalWipe: Boolean,
    timeoutSeconds: Int,
): AutoLockAction = when {
    !sessionLive -> AutoLockAction.None
    terminalWipe -> AutoLockAction.None
    timeoutSeconds <= 0 -> AutoLockAction.LockNow
    else -> AutoLockAction.LockAfter(timeoutSeconds * 1_000L)
}

/**
 * Whether a SCHEDULED auto-lock should still fire when its timer elapses. Re-checked at fire time
 * (not just at schedule time): during the background interval a delete may have STARTED (it now
 * owns teardown) or the session may have been torn down already (forced logout). Pure/host-tested.
 */
fun shouldAutoLockAtFireTime(sessionLive: Boolean, terminalWipe: Boolean): Boolean =
    sessionLive && !terminalWipe

/**
 * D3 idle auto-lock. Observes app-wide foreground/background via [androidx.lifecycle.ProcessLifecycleOwner]
 * (registered in [AppContainer]) and, when the app is backgrounded with a live session, locks the
 * vault after the user's configured timeout — full teardown through the SAME [UnlockController.lock]
 * used by forced-logout and account-delete, so there is no second teardown implementation. Auto-lock
 * only ever LOCKS (reseals + tears down the session), never DELETES: it writes no delete markers and
 * clears no tokens, so it is not a new writer to any of the vault-delete / auth state the D2c review
 * rounds hardened.
 *
 * There is no push stack: messages only arrive over the live WebSocket while the app is unlocked and
 * foreground/backgrounded-but-not-yet-locked. A shorter timeout is more private but locks the socket
 * sooner, delaying delivery until the next unlock — the tradeoff the Settings copy states at the
 * picker.
 *
 * Everything the decision needs is injected as a lambda (mirroring [UnlockController]) so this is
 * driven by fakes off-device; the lifecycle callbacks are the only non-host-testable surface, and
 * the branch logic lives in the pure [autoLockOnBackground] / [shouldAutoLockAtFireTime].
 *
 * @param scope process-lifetime scope for the timer + the (blocking, bounded-drain) [lock] call —
 *   kept off the main thread.
 * @param timeoutSeconds current device-level timeout, read as a snapshot when the app backgrounds.
 * @param sessionLive whether a session is currently unlocked.
 * @param terminalWipe whether an account-delete wipe owns teardown right now.
 * @param lock the canonical session teardown ([UnlockController.lock]); idempotent.
 * @param resetRitual the uninterrupted-sequence guard for the 0.9.2 triple-entry creation gate
 *   ([VaultUnlockRouter.resetCandidate]): invoked UNCONDITIONALLY on every [onStop] — independent of
 *   whether a session is live — because the ritual runs at the lock screen (no session), so a session
 *   gate would miss it. Backgrounding the app breaks any in-progress ritual; process death clears the
 *   RAM candidate on its own. Defaults to a no-op so existing tests need not supply it.
 */
class VaultLockManager(
    private val scope: CoroutineScope,
    private val timeoutSeconds: () -> Int,
    private val sessionLive: () -> Boolean,
    private val terminalWipe: () -> Boolean,
    private val lock: () -> Unit,
    private val resetRitual: () -> Unit = {},
) : DefaultLifecycleObserver {

    private var pending: Job? = null

    /** Register on the process lifecycle (ProcessLifecycleOwner.get().lifecycle). */
    fun register(lifecycle: Lifecycle) {
        lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App backgrounded. FIRST, unconditionally break any in-progress triple-entry creation ritual
        // (0.9.2 uninterrupted-sequence guard) — this is independent of session state and of the
        // auto-lock decision below, because the ritual runs at the lock screen with no live session.
        resetRitual()
        // Cancel any stale timer, then schedule the auto-lock per the pure decision.
        pending?.cancel()
        pending = when (val action = autoLockOnBackground(sessionLive(), terminalWipe(), timeoutSeconds())) {
            AutoLockAction.None -> null
            // Off the main thread: lock()'s bounded teardown drain can block up to a couple of seconds.
            AutoLockAction.LockNow -> scope.launch { lock() }
            is AutoLockAction.LockAfter -> scope.launch {
                delay(action.delayMs)
                // Re-check at fire time — a delete may have started or the session already torn down.
                if (shouldAutoLockAtFireTime(sessionLive(), terminalWipe())) lock()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // Returned to the foreground before the timeout elapsed — cancel the pending auto-lock.
        pending?.cancel()
        pending = null
    }
}
