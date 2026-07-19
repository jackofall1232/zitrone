// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net

import com.zitrone.app.data.TransportState
import com.zitrone.app.i2p.I2pIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the fixed transport chain I2P -> Tor -> clearnet (the chain is NOT
 * user-selectable; see docs/TOR_ARCHITECTURE.md §6). Emits the live
 * [TransportState] the app should route over; AppContainer applies each change
 * (swaps the OkHttp client + endpoint URLs and reconnects the socket).
 *
 * Kept deliberately free of android.content.Context so the decision logic is
 * unit-testable: the router-installed checks and the readiness probe are
 * injected as lambdas/an interface, and the coroutine scope is injected too.
 * The one Context-bound side effect of choosing Tor — asking Orbot to start —
 * lives in AppContainer's apply step, not here.
 *
 * Resolution, on every input change:
 *  1. I2P candidate = destination baked in AND setting on AND the I2P app installed.
 *  2. If a candidate, a QUICK probe (short timeout). READY -> I2P, done.
 *  3. Otherwise fall through immediately to Tor (when torEnabled && Orbot
 *     installed) else clearnet — and if I2P was a candidate that simply wasn't
 *     ready yet, keep polling in the background (tolerating the 1–3 min tunnel
 *     build) and promote to I2P the moment a probe succeeds.
 *  4. While I2P is active, a cheap liveness check watches the local proxy port;
 *     if the router vanishes mid-session the state demotes to the fallback and
 *     polling resumes, so a router restart re-promotes without user action.
 */
class TransportResolver(
    private val relayI2pDest: String,
    private val i2pProxyHost: String,
    private val inputs: StateFlow<Inputs>,
    private val isRouterInstalled: () -> Boolean,
    private val isOrbotInstalled: () -> Boolean,
    private val prober: I2pProber,
    private val scope: CoroutineScope,
) {

    /** The user-controlled toggles the chain reacts to. */
    data class Inputs(val i2pEnabled: Boolean, val torEnabled: Boolean)

    // Seed synchronously with the non-I2P fallback for the current inputs: this
    // matches the client AppContainer builds eagerly at construction, so the
    // first apply is a no-op unless a probe later promotes to I2P.
    private val _state = MutableStateFlow(fallbackState(inputs.value))
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /**
     * Begins reacting to input changes. collectLatest cancels any in-flight
     * resolution/polling when the inputs change, so a toggle flip restarts the
     * chain cleanly.
     */
    fun start() {
        scope.launch {
            inputs.collectLatest { resolve(it) }
        }
    }

    private suspend fun resolve(input: Inputs) {
        val i2pCandidate = relayI2pDest.isNotEmpty() && input.i2pEnabled && isRouterInstalled()
        if (!i2pCandidate) {
            _state.value = fallbackState(input)
            return
        }
        // First pass gets the snappy timeout; retries after a miss or a demotion
        // use the generous one. The loop never exits on its own: promotion is
        // followed by monitoring, and a demotion (router vanished) falls back and
        // resumes polling — so a router restart re-promotes without user action.
        var timeoutMs = QUICK_TIMEOUT_MS
        while (currentCoroutineContext().isActive) {
            if (probeReady(timeoutMs)) {
                _state.value = TransportState.I2P
                monitorWhileProxyUp()
                if (!currentCoroutineContext().isActive) return
                // Demoted: the proxy port went dead (router stopped/uninstalled).
                _state.value = fallbackState(input)
            } else {
                // Router not ready yet — serve the fallback now and keep polling
                // so a slow tunnel build still ends up on I2P without the user
                // retrying. (StateFlow dedups, so re-setting is emission-free.)
                _state.value = fallbackState(input)
            }
            timeoutMs = POLL_TIMEOUT_MS
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Holds the I2P state until the local proxy port stops answering. */
    private suspend fun monitorWhileProxyUp() {
        while (currentCoroutineContext().isActive &&
            prober.proxyUp(i2pProxyHost, I2pIntegration.HTTP_PROXY_PORT, MONITOR_TIMEOUT_MS)
        ) {
            delay(MONITOR_INTERVAL_MS)
        }
    }

    private suspend fun probeReady(timeoutMs: Int): Boolean =
        prober.probe(i2pProxyHost, I2pIntegration.HTTP_PROXY_PORT, relayI2pDest, timeoutMs) ==
            I2pProber.Readiness.READY

    private fun fallbackState(input: Inputs): TransportState =
        if (input.torEnabled && isOrbotInstalled()) TransportState.TOR
        else TransportState.CLEARNET_FALLBACK

    companion object {
        /** Candidate check — must be snappy so boot isn't stalled on a cold router. */
        private const val QUICK_TIMEOUT_MS = 4_000

        /**
         * Background poll — generous, since a warm handshake still lags tunnel
         * build. 30s: the first leaseset lookup to a fresh destination took ~19s
         * on an otherwise-warm router (2026-07-19), so the old 15s would
         * chronically time out the promotion poll. Matches desktop i2p.rs's 30s
         * WS_I2P_STEP_TIMEOUT.
         */
        private const val POLL_TIMEOUT_MS = 30_000

        /** Poll cadence while waiting for tunnels (1–3 min typical). */
        private const val POLL_INTERVAL_MS = 20_000L

        /** Liveness-check cadence while I2P is active (bare local TCP, cheap). */
        private const val MONITOR_INTERVAL_MS = 30_000L

        /** Liveness-check timeout — localhost, so anything slow means dead. */
        private const val MONITOR_TIMEOUT_MS = 2_000
    }
}
