// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.app.Application
import android.util.Log
import com.zitrone.app.crypto.EncryptedSignalProtocolStore
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.MessageRepository
import com.zitrone.app.data.SettingsRepository
import com.zitrone.app.data.TransportState
import com.zitrone.app.diagnostics.BootDiagnostics
import com.zitrone.app.i2p.I2pIntegration
import com.zitrone.app.net.ApiClient
import com.zitrone.app.net.CertificatePinning
import com.zitrone.app.net.HttpConnectI2pProber
import com.zitrone.app.net.TransportResolver
import com.zitrone.app.net.WsClient
import com.zitrone.app.notifications.MessagingNotifications
import com.zitrone.app.tor.TorIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Application entry point. No analytics, no crash reporting, no telemetry —
 * the only thing initialized here is the dependency graph and the
 * content-free notification channel.
 */
class ZitroneApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MessagingNotifications.ensureChannel(this)
    }
}

/**
 * Hand-rolled dependency container — deliberately no DI framework, so the
 * complete object graph of a privacy-critical app stays auditable in one file.
 */
class AppContainer(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyStoreManager = KeyStoreManager(app)
    val settingsRepository = SettingsRepository(keyStoreManager)
    val signalStore = EncryptedSignalProtocolStore(keyStoreManager)
    val signalManager = SignalProtocolManager(signalStore, keyStoreManager)

    private var httpClient =
        CertificatePinning.buildClient(torEnabled = settingsRepository.settings.value.torEnabled)

    /**
     * Resolves the fixed I2P -> Tor -> clearnet chain. Context-free by design —
     * the router checks and the HTTP-CONNECT readiness probe are injected, and the
     * one Context-bound side effect (asking Orbot to start) lives in [applyTransport]
     * below. Inputs mirror the user's I2P/Tor toggles.
     */
    private val transportInputs: StateFlow<TransportResolver.Inputs> =
        settingsRepository.settings
            .map { TransportResolver.Inputs(it.i2pEnabled, it.torEnabled) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                settingsRepository.settings.value.let {
                    TransportResolver.Inputs(it.i2pEnabled, it.torEnabled)
                },
            )

    val transportResolver = TransportResolver(
        relayI2pDest = BuildConfig.RELAY_I2P_DEST,
        i2pProxyHost = BuildConfig.I2P_PROXY_HOST,
        inputs = transportInputs,
        isRouterInstalled = { I2pIntegration.isOfficialRouterInstalled(app) },
        isOrbotInstalled = { TorIntegration.isOrbotInstalled(app) },
        prober = HttpConnectI2pProber(),
        scope = scope,
    )

    /** On-device, adb-free connection diagnostics (Settings → Diagnostics). */
    val bootDiagnostics = BootDiagnostics(app)

    val apiClient = ApiClient(API_BASE_URL, httpClient, keyStoreManager)

    // WsClient shares the coordinator's diagnostic channel (logcat tag +
    // on-device log) so socket-lifecycle failures land in Settings →
    // Diagnostics next to the boot-stage lines. Privacy-safe by the same
    // rule: fixed markers + exception metadata only.
    val wsClient = WsClient(WS_URL, httpClient, scope) { line ->
        Log.w("ZitroneBoot", line)
        bootDiagnostics.record(line)
    }

    val messageRepository = MessageRepository(scope)
    val conversationRepository = ConversationRepository()

    val coordinator = MessagingCoordinator(
        appContext = app,
        scope = scope,
        signal = signalManager,
        api = apiClient,
        ws = wsClient,
        messages = messageRepository,
        conversations = conversationRepository,
        settings = settingsRepository,
        diagnostics = bootDiagnostics,
    )

    init {
        // The resolver owns the whole chain now (I2P/Tor/clearnet); its state
        // drives a single apply-loop. This REPLACES the old torEnabled collector
        // — two loops would fight over httpClient/URL swaps.
        transportResolver.start()
        scope.launch {
            transportResolver.state.collect(::applyTransport)
        }
    }

    private fun applyTransport(state: TransportState) {
        val (client, apiBase, ws) = when (state) {
            TransportState.I2P -> Triple(
                CertificatePinning.buildI2pClient(
                    BuildConfig.I2P_PROXY_HOST,
                    BuildConfig.RELAY_I2P_DEST,
                ),
                i2pApiBaseUrl,
                i2pWsUrl,
            )
            TransportState.TOR ->
                Triple(CertificatePinning.buildClient(torEnabled = true), API_BASE_URL, WS_URL)
            // CLEARNET_FALLBACK — and OFFLINE, which the resolver never emits.
            else -> Triple(CertificatePinning.buildClient(torEnabled = false), API_BASE_URL, WS_URL)
        }
        // Always reconcile the client + endpoint URLs so the NEXT connect dials
        // the resolved transport — this is what the post-unlock coordinator.start()
        // picks up.
        httpClient = client
        apiClient.updateTransport(httpClient, apiBase)
        wsClient.updateTransport(httpClient, ws)
        // Side effect of choosing Tor kept here (not in the Context-free
        // resolver): ask Orbot to start, exactly as applyTorSetting did. This is
        // a broadcast to Orbot, not a connection of ours — safe pre-unlock, and
        // it must fire even while our socket is down so Orbot is already up when
        // the post-unlock connect dials through its proxy.
        if (state == TransportState.TOR) TorIntegration.requestOrbotStart(app)
        // Only migrate a LIVE session (connected or mid-handshake) to the new
        // transport. When the socket is disconnected — notably pre-unlock, before
        // coordinator.start() fires — the swap above is enough and we must NOT
        // open the socket here: the token is readable before the biometric gate,
        // so an eager connect would breach it. This preserves the old collector's
        // drop(1) semantics while still migrating a running session when a
        // background probe promotes clearnet/Tor to I2P.
        if (wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {
            wsClient.disconnect()
            apiClient.accessToken?.let(wsClient::connect)
        }
    }

    companion object {
        // Self-hosters: point these at your deployment AND replace the
        // certificate pin in net/CertificatePinning.kt.
        // TODO(zitrone-cutover): live relay endpoint — repoint only at deploy cutover.
        const val API_BASE_URL = "https://relay.sublemonable.com"
        const val WS_URL = "wss://relay.sublemonable.com/ws"

        // I2P endpoints — plain http/ws (I2P is the transport-security layer; no
        // TLS). Built from BuildConfig.RELAY_I2P_DEST; empty when unset, in which
        // case the resolver never emits I2P so these are never dialed.
        private val i2pApiBaseUrl = "http://${BuildConfig.RELAY_I2P_DEST}"
        private val i2pWsUrl = "ws://${BuildConfig.RELAY_I2P_DEST}/ws"
    }
}
