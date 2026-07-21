// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate-limits + re-fires the app's single content-free notification.
 *
 * WHY THIS EXISTS: [MessagingNotifications] posts under one fixed notification
 * id, so a second arrival silently UPDATES the same tray entry. Historically
 * that update carried `setOnlyAlertOnce(true)`, so a high-volume conversation
 * pinged exactly once and then went silent forever while unread piled up. This
 * scheduler is the trigger layer that fixes it: it fires an alert at most once
 * per conversation per [cooldownMs], and — crucially — RE-FIRES the alert once
 * per window while a conversation stays unread, so the buzz keeps coming until
 * the user actually opens the chat. Every [fire] call is therefore an INTENDED,
 * audible alert; `setOnlyAlertOnce` was removed from the builder to match.
 *
 * ============================ SECURITY INVARIANT ============================
 * The notification this schedules MUST remain byte-for-byte identical no matter
 * which identity/vault produced the triggering message: the same channel, the
 * same content-free "New message" text, the same sound, the same single fixed
 * notification id, the same priority, and the same extra-free tap intent. A
 * notification that reveals which identity it came from — or that more than one
 * identity even exists — is a SECURITY FAILURE (it breaks plausible
 * deniability). The single fixed id and content-free text in
 * [MessagingNotifications.showNewMessage] are load-bearing for that property:
 *   - This scheduler NEVER passes any per-conversation / per-identity data into
 *     [fire]; the conversation id is used ONLY as an in-memory bucket key and
 *     never reaches the notification.
 *   - Do NOT add per-conversation or per-identity notification ids, unread
 *     counts, sender info, previews, or intent extras anywhere downstream.
 *   - [cancelAll] exists so switching identities tears the whole scheduler down
 *     completely, leaving no cross-identity residue (pending re-fire jobs, last
 *     fire timestamps) behind.
 * This comment and every string here are deliberately SLOT-AGNOSTIC: a
 * decompiler reading them must learn nothing about how identities are stored.
 * ===========================================================================
 *
 * Concurrency mirrors the repositories' @Synchronized-monitor style. Per-
 * conversation state lives in a [ConcurrentHashMap]; every transition happens
 * inside `synchronized(state)` on that conversation's [ConvState] monitor. The
 * two entry points may run on different threads — [onIncomingMessage] on the
 * coordinator's confined dispatcher, [onConversationRead] on the main thread —
 * so this makes NO dispatcher-affinity assumption. We never suspend while
 * holding the monitor; [fire] is a quick, non-suspending side effect.
 */
class NotificationScheduler(
    private val scope: CoroutineScope,
    private val fire: () -> Unit,
    private val isEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cooldownMs: Long = 120_000L,
) {

    /**
     * Per-conversation trigger state AND its own monitor. [epoch] is the
     * fire-vs-read race defense: [onConversationRead] bumps it while cancelling,
     * so a re-fire job that already elapsed its delay and is now waiting on this
     * monitor sees the mismatch and does NOT fire a phantom alert.
     */
    private class ConvState {
        var lastFiredAt: Long? = null
        var arrivedSinceFire: Boolean = false
        var job: Job? = null
        var epoch: Int = 0
    }

    private val states = ConcurrentHashMap<String, ConvState>()

    /**
     * Called for every incoming DISPLAYABLE message (never for receipts, which
     * short-circuit before notifying). Fires immediately when outside the
     * cooldown; within it, marks the conversation still-unread and arms a single
     * re-fire check at the window boundary.
     */
    fun onIncomingMessage(conversationId: String) {
        val state = states.getOrPut(conversationId) { ConvState() }
        synchronized(state) {
            // Toggle off ⇒ no alert and no scheduling at all.
            if (!isEnabled()) return
            val now = clock()
            val last = state.lastFiredAt
            if (last == null || now - last >= cooldownMs) {
                // Outside the cooldown (or first ever) — fire right now.
                fire()
                state.lastFiredAt = now
                state.arrivedSinceFire = false
                state.job?.cancel()
                state.job = null
            } else {
                // Suppressed inside the cooldown — remember it's still unread and
                // arm ONE re-fire at the window boundary if not already armed.
                state.arrivedSinceFire = true
                if (state.job == null) {
                    val myEpoch = state.epoch
                    state.job = scope.launch {
                        // Residual wait on the injected clock, like
                        // MessageRepository.scheduleTtl.
                        val wait = (last + cooldownMs) - clock()
                        if (wait > 0) delay(wait)
                        synchronized(state) {
                            // A read (or teardown) between arming and now bumped
                            // the epoch — do not fire a phantom alert.
                            if (state.epoch != myEpoch) return@synchronized
                            // Nothing new arrived, or the toggle went off — quiet.
                            if (!state.arrivedSinceFire) return@synchronized
                            if (!isEnabled()) return@synchronized
                            fire()
                            state.lastFiredAt = clock()
                            state.arrivedSinceFire = false
                            // Drop our own handle inside the lock so a later
                            // re-arm can schedule a fresh job for the next window.
                            state.job = null
                        }
                    }
                }
            }
        }
    }

    /**
     * The user opened the conversation (unread cleared). Full reset ⇒ the next
     * incoming message begins a fresh cycle and fires immediately. Bumping
     * [ConvState.epoch] neutralizes any re-fire job already past its delay.
     */
    fun onConversationRead(conversationId: String) {
        val state = states[conversationId] ?: return
        synchronized(state) {
            state.job?.cancel()
            state.job = null
            state.lastFiredAt = null
            state.arrivedSinceFire = false
            state.epoch++
        }
    }

    /**
     * VAULT-TEARDOWN HOOK: cancel and clear every job and all state. Call on any
     * identity switch / logout / account wipe so no re-fire job or last-fire
     * timestamp survives across identities (see the security invariant above).
     */
    fun cancelAll() {
        states.values.forEach { state ->
            synchronized(state) {
                state.job?.cancel()
                state.job = null
                state.lastFiredAt = null
                state.arrivedSinceFire = false
                state.epoch++
            }
        }
        states.clear()
    }
}
