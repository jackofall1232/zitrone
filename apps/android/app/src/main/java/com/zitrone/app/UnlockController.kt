// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
    private val drainTimeoutMs: Long = 2_000,
) {
    private val lock = Any()
    private var current: S? = null
    private var sessionScope: CoroutineScope? = null
    private var terminalWipe = false

    /**
     * Build + publish the session if none is live, from the default [buildSession].
     * Idempotent. Refused while a terminal wipe is in progress (see
     * [beginTerminalWipe]) — the UI's normal routing retries once the wipe's
     * completion lifts the gate.
     */
    fun unlock() = unlock(buildSession)

    /**
     * As [unlock], but from a caller-[prepared] factory that already carries resolved
     * credentials — D2c's vault path resolves the [com.zitrone.app.crypto.vault.VaultOpen]
     * OFF the monitor (Argon2id / biometric happen before this call), then hands the build
     * in here. Same monitor, same idempotence + terminal-wipe refusal as [unlock].
     *
     * A REFUSED build (terminal wipe in progress, or a session already live) never invokes
     * [prepared], so the credential it closes over would be abandoned — [onRefused] runs
     * instead so the caller wipes the unused VaultOpen. On an accepted build [prepared] owns
     * the arrays (VaultSession consumes them); [onRefused] is not called.
     */
    fun unlock(prepared: (CoroutineScope) -> S, onRefused: () -> Unit = {}) {
        synchronized(lock) {
            if (terminalWipe) return onRefused()
            if (current != null) return onRefused()
            val scope = newSessionScope()
            val session = try {
                prepared(scope)
            } catch (t: Throwable) {
                // Spec §4: a FAILED build must wipe the VaultOpen it was handed and must not
                // strand the freshly created scope. `onRefused` performs the caller's wipe (safe
                // even if VaultSession already consumed the arrays — a re-wipe of zeroed bytes is
                // a no-op); the partial session's own runtime, if any was built, is resealed+wiped
                // by SessionContainer's construction guard before this throw reaches here.
                scope.cancel()
                onRefused()
                throw t
            }
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
        try {
            stopSession(session)
        } catch (t: Throwable) {
            // Teardown must complete even if stopSession throws (D2c: runtime.close()'s final
            // reseal can throw NotDurable/IO — but it has ALREADY wiped its secrets in a finally).
            // Swallowing here keeps the ordered teardown going so a dead runtime is never left
            // published with `current` still set (which would let the next unlock "succeed" onto a
            // closed runtime and then crash on first use).
        }
        val job = sessionScope?.coroutineContext?.get(Job)
        sessionScope?.cancel()
        // cancel() returns immediately and cancellation is cooperative: work
        // already running — a decrypt persisting a ratchet update — would race a
        // successor session over the SAME legacy stores (concurrent ratchet
        // mutations can permanently break a contact's session — Codex PR #45
        // r2). Wait, bounded, for the scope to drain before a successor can
        // build. The bound covers the realistic window (store writes are
        // ms-scale); a coroutine stuck in uninterruptible network I/O can
        // overrun it — a residual, accepted for D2b since production lock()
        // callers are background threads and an unlock() racing this blocks on
        // the monitor for at most the bound. D2c's VaultRuntime serializes all
        // store access through one lock, retiring this race class outright.
        if (job != null) {
            runBlocking { withTimeoutOrNull(drainTimeoutMs) { job.join() } }
        }
        publish(null)
        current = null
        sessionScope = null
    }

    /**
     * Gate [unlock] shut for the duration of a terminal (account-delete) wipe: a
     * successor session built while the shared legacy stores are being cleared
     * underneath it would hold stale roster/auth state with vanished crypto
     * (Codex PR #45 r2). The wipe runs NonCancellable and its completion calls
     * [endTerminalWipe], so the gate always lifts.
     */
    fun beginTerminalWipe() {
        synchronized(lock) { terminalWipe = true }
    }

    fun endTerminalWipe() {
        synchronized(lock) { terminalWipe = false }
    }

    /**
     * Whether a terminal (account-delete) wipe is in progress. The D3 idle auto-lock reads this to
     * SKIP its timer-fired [lock] while a delete owns teardown — a background timer must not race
     * the account-delete's ordered teardown (the delete's NonCancellable coroutine + fail-safe
     * closed-runtime handling would tolerate it, but not racing is cleaner defense-in-depth).
     */
    fun isTerminalWipe(): Boolean = synchronized(lock) { terminalWipe }
}
