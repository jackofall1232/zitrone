// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Owns the session-per-unlock lifecycle (P1b-2 PR-D2b). [unlock] builds the one
 * live session over the CURRENT transport and publishes it; [lock] tears it down
 * and nulls the published slot. Both are idempotent and serialized against each
 * other — an unlock racing a teardown blocks until the teardown finishes, so the
 * two never interleave into a half-built or half-torn-down session.
 *
 * Teardown order in [lock] is load-bearing: [stopSession] (coordinator.stop —
 * cancel linkJob, disconnect the socket, cancel reminders) → cancel the session
 * scope (kills the coordinator's process-long collectors, which would otherwise
 * leak one per unlock cycle) → publish null.
 *
 * Generic over the session type and factored entirely through lambdas for one
 * reason: host-JVM testability. A real [SessionContainer] cannot be constructed
 * off-device, so tests drive this with fakes; [AppContainer] wires it to real
 * construction and teardown.
 *
 * @param newSessionScope one FRESH [CoroutineScope] per build (owns the session's
 *   coroutines; cancelled on [lock]).
 * @param buildSession builds the session against the current transport, using the
 *   scope it is handed.
 * @param publish sets the observable session slot (the [AppContainer] StateFlow).
 * @param stopSession the canonical session stop (coordinator.stop()).
 * @param afterPublish runs once, with the session already live, right after it is
 *   published: it re-applies the transport (closing the build-vs-publish race —
 *   see [AppContainer.applyTransport]) and drains any queued lemon-drop scan.
 */
class UnlockController<S : Any>(
    private val newSessionScope: () -> CoroutineScope,
    private val buildSession: (CoroutineScope) -> S,
    private val publish: (S?) -> Unit,
    private val stopSession: (S) -> Unit,
    private val afterPublish: () -> Unit,
) {
    private val lock = Any()
    private var current: S? = null
    private var sessionScope: CoroutineScope? = null

    /** Build + publish the session if none is live. Idempotent. */
    fun unlock() {
        synchronized(lock) {
            if (current != null) return
            val scope = newSessionScope()
            val session = buildSession(scope)
            sessionScope = scope
            current = session
            publish(session)
            // AFTER publish, inside the lock so it cannot interleave with a
            // teardown: afterPublish reconciles a transport change that landed
            // mid-build (applyTransport saw a null session) and drains a scan
            // queued while locked — both need the now-live slot.
            afterPublish()
        }
    }

    /** Tear down + null the live session if any. Idempotent. */
    fun lock() {
        synchronized(lock) { lockCurrent() }
    }

    /**
     * [lock], but ONLY if [expected] is still the live session. Teardown
     * callbacks capture the session they belong to (the forced-logout wiring,
     * the account-delete completion); a detached callback firing late — e.g. the
     * NonCancellable account wipe finishing after a concurrent revocation
     * already tore its session down and the user re-unlocked — must not tear
     * down the innocent successor session (Codex PR #45 r1).
     */
    fun lockIf(expected: S) {
        synchronized(lock) { if (current === expected) lockCurrent() }
    }

    private fun lockCurrent() {
        val session = current ?: return
        stopSession(session)
        sessionScope?.cancel()
        publish(null)
        current = null
        sessionScope = null
    }
}
