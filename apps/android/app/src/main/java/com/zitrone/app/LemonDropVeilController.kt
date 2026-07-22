// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.LemonDropRedeemer
import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.LemonDropVeil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Owns the lemon-drop veil and the scan orchestration around it (P1b-2 PR-D2b
 * lifted it off [AppContainer] so it is host-JVM testable — a real
 * [SessionContainer] cannot be constructed off-device, so the probe is injected).
 *
 * D2b re-gates the ORDER, not the crypto: a `/d/{id}` scanned while the app is
 * locked is NOT fetched or decrypted — it is queued (single slot, latest-wins)
 * behind [LemonDropVeil.Locked], and the probe fires only once the app unlocks
 * ([onUnlocked], from the post-publish hook). A scan while a session is live runs
 * the probe immediately, exactly as before.
 *
 * The veil lives on the DEVICE half (process lifetime, not Activity) so a
 * configuration change keeps a decrypted-but-unrendered drop in memory without
 * plaintext ever touching saved state — see [LemonDropVeil]'s security invariant.
 * Plaintext is never persisted and the pre-unlock probe is side-effect-free; a
 * queued qrId is process-scoped only (equally lost on process death, as the old
 * pre-unlock probe result was — no regression).
 *
 * @param scope PROCESS scope for the probe, so a configuration change mid-probe
 *   neither cancels it nor strands the veil at UNKNOWN.
 * @param isUnlocked whether a session is live (probes may run).
 * @param probe the single fetch + isolated decrypt; never throws (see
 *   [LemonDropRedeemer.probe]). Injected so tests drive it with a fake.
 * @param ioDispatcher the probe's dispatcher (test seam; IO on device).
 */
class LemonDropVeilController(
    private val scope: CoroutineScope,
    private val isUnlocked: () -> Boolean,
    private val probe: suspend (qrId: String) -> LemonDropRedeemer.ProbeResult,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    val veil = MutableStateFlow<LemonDropVeil?>(null)

    private val lock = Any()

    /**
     * Monotonic id of the most recent scan. A probe launched for an earlier scan
     * must not overwrite a newer scan's veil once superseded — two
     * `Advocacy(UNKNOWN)` values are structurally equal, so a compare-and-set on
     * the value alone would let a stale probe clobber the current scan (Codex
     * PR #4). Dismissal and a fresh scan both bump this so a late probe cannot
     * resurrect a screen the user already closed or replaced.
     */
    private var scanToken = 0L

    /**
     * The qrId scanned while locked, awaiting the app unlock that lets it probe.
     * Single slot, latest-wins — a second locked scan supersedes the first, and a
     * dismissal drops it, so unlock never revives a scan the user walked away from.
     * Process-scoped, never persisted.
     */
    private var pendingQrId: String? = null

    /**
     * Handle a scanned `/d/{id}`. While unlocked: raise advocacy/UNKNOWN and run
     * the single fetch + isolated open in the PROCESS scope. While locked: queue
     * the id and raise [LemonDropVeil.Locked] WITHOUT fetching or decrypting —
     * the probe waits for [onUnlocked].
     */
    fun onScan(qrId: String) {
        val token = synchronized(lock) {
            if (!isUnlocked()) {
                pendingQrId = qrId
                veil.value = LemonDropVeil.Locked
                ++scanToken
                return
            }
            veil.value = LemonDropVeil.Advocacy(LemonDropScanOutcome.UNKNOWN)
            ++scanToken
        }
        launchProbe(qrId, token)
    }

    /**
     * App unlocked: if a scan was queued while locked, probe it NOW — same code
     * path and veil transitions as a live-session scan. No-op otherwise.
     */
    fun onUnlocked() {
        val queued: Pair<String, Long> = synchronized(lock) {
            val qrId = pendingQrId ?: return
            pendingQrId = null
            veil.value = LemonDropVeil.Advocacy(LemonDropScanOutcome.UNKNOWN)
            qrId to ++scanToken
        }
        launchProbe(queued.first, queued.second)
    }

    /** Dismiss the veil, invalidate any in-flight probe, and drop a queued scan. */
    fun dismiss() {
        synchronized(lock) {
            ++scanToken
            pendingQrId = null
            veil.value = null
        }
    }

    /**
     * Drop a plaintext-bearing [LemonDropVeil.Delivered] when the Activity stops.
     * The veil is process-scoped, so without this an opened drop would re-render
     * on a later Activity recreation with no fresh biometric unlock (Codex PR #4).
     * Advocacy/AwaitUnlock/Locked render no plaintext and are kept.
     */
    fun clearDelivered() {
        synchronized(lock) {
            if (veil.value is LemonDropVeil.Delivered) veil.value = null
        }
    }

    private fun launchProbe(qrId: String, token: Long) {
        scope.launch(ioDispatcher) {
            val refined = when (val result = probe(qrId)) {
                is LemonDropRedeemer.ProbeResult.Advocacy -> LemonDropVeil.Advocacy(result.outcome)
                is LemonDropRedeemer.ProbeResult.ReadyToOpen -> LemonDropVeil.AwaitUnlock(result.pending)
            }
            synchronized(lock) {
                if (scanToken == token) veil.value = refined
            }
        }
    }
}
