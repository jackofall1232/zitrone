// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.app.Application
import android.util.Log
import com.goterl.lazysodium.SodiumAndroid
import com.zitrone.app.crypto.EncryptedSignalProtocolStore
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.LemonDropSodiumOps
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.DeviceSettings
import com.zitrone.app.data.EncryptedAuthStore
import com.zitrone.app.data.EncryptedRosterStore
import com.zitrone.app.data.LemonDropCreator
import com.zitrone.app.data.LemonDropRedeemer
import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.LemonDropVeil
import com.zitrone.app.data.MessageRepository
import com.zitrone.app.data.MessageState
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
import com.zitrone.app.notifications.NotificationScheduler
import com.zitrone.app.tor.TorIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
 *
 * The graph is split along a device/session seam (P1b-2 PR-D1):
 *  - `AppContainer` is the DEVICE half — process-lifetime, readable pre-unlock:
 *    the scope, keystore, [DeviceSettings], the transport stack, boot
 *    diagnostics and the lemon-drop veil.
 *  - [SessionContainer] is the SESSION half — the messaging objects that live
 *    only while unlocked.
 *
 * PR-D2b makes the session build ON UNLOCK, not eagerly: [session] is a nullable
 * flow, null while locked, and [UnlockController] builds/tears it down per unlock
 * cycle over the CURRENT transport. Consumers collect [session] and read members
 * through the non-null value (there are no delegating accessors — the UI composes
 * a session-scoped subtree only while it is non-null). This is a lifecycle change
 * only: the object set and construction order are identical to the eager D1 build;
 * D2c swaps the vault in behind the same seam.
 */
class AppContainer(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyStoreManager = KeyStoreManager(app)

    // Legacy settings store — still the single source of truth in D1. The
    // device-scoped subset is exposed as a typed view through [deviceSettings];
    // the vault-scoped fields (ttl / burn-on-read / read-receipts /
    // lemon-drop-compose / unread-reminder) are still read straight off this
    // repository by the UI and the coordinator (D2/D5 move them into the vault).
    val settingsRepository = SettingsRepository(keyStoreManager)

    /**
     * Device-scoped, pre-unlock settings view over the SAME legacy store — the
     * fields that gate unlock and choose the transport. No data moves; see
     * [DeviceSettings].
     */
    val deviceSettings = DeviceSettings(settingsRepository)

    // @Volatile so the transport apply-loop (running on Dispatchers.Default) and
    // the construction thread publish/read the current client consistently.
    @Volatile
    private var httpClient =
        CertificatePinning.buildClient(torEnabled = deviceSettings.torEnabled)

    /**
     * Resolves the fixed I2P -> Tor -> clearnet chain. Context-free by design —
     * the router checks and the HTTP-CONNECT readiness probe are injected, and the
     * one Context-bound side effect (asking Orbot to start) lives in [applyTransport]
     * below. Inputs mirror the user's I2P/Tor toggles, read via [DeviceSettings].
     */
    private val transportInputs: StateFlow<TransportResolver.Inputs> =
        deviceSettings.transportInputs
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                deviceSettings.transportInputsSnapshot,
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

    /**
     * The single session-scoped half of the graph — nullable and built ON UNLOCK
     * (PR-D2b), not eagerly. Null while locked; a live [SessionContainer] once
     * [UnlockController.unlock] builds it over the current transport. Every
     * consumer collects this flow and reads members through the non-null value it
     * yields (there are no delegating accessors any more — the UI composes a
     * session-scoped subtree only while this is non-null). D2c swaps the vault in
     * behind the same lifecycle without touching flow order again.
     */
    private val _session = MutableStateFlow<SessionContainer?>(null)
    val session: StateFlow<SessionContainer?> = _session.asStateFlow()

    /**
     * Lemon-drop bridge (device half, process lifetime). Owns the veil a `/d/{id}`
     * scan raises and the scan orchestration — including D2b's re-gate: a scan
     * while locked is queued, not fetched, until unlock. See
     * [LemonDropVeilController]. The veil itself is exposed for the two direct
     * writes the Activity still owns (the plaintext [LemonDropVeil.Delivered] set
     * at biometric success, and the advocacy-outcome restore from saved state).
     */
    private val lemonDropVeilController = LemonDropVeilController(
        scope = scope,
        isUnlocked = { _session.value != null },
        // Reads the live session at probe time; a session torn down mid-probe
        // (rare — a forced logout while the veil is up) falls to an honest UNKNOWN.
        probe = { qrId ->
            _session.value?.lemonDropRedeemer?.probe(qrId)
                ?: LemonDropRedeemer.ProbeResult.Advocacy(LemonDropScanOutcome.UNKNOWN)
        },
    )

    val lemonDropVeil: MutableStateFlow<LemonDropVeil?> get() = lemonDropVeilController.veil

    /** Handle a scanned `/d/{id}` — see [LemonDropVeilController.onScan]. */
    fun onLemonDropLink(qrId: String) = lemonDropVeilController.onScan(qrId)

    /** Dismiss the veil and invalidate any in-flight/queued scan. */
    fun dismissLemonDropVeil() = lemonDropVeilController.dismiss()

    /** Drop a plaintext-bearing [LemonDropVeil.Delivered] when the Activity stops. */
    fun clearDeliveredLemonDropVeil() = lemonDropVeilController.clearDelivered()

    /**
     * The session-per-unlock lifecycle. Builds a fresh [SessionContainer] over the
     * CURRENT transport on unlock (each with its own scope, cancelled on lock so
     * the coordinator's process-long collectors don't leak per cycle), and tears
     * it down on lock. See [UnlockController].
     */
    val unlockController = UnlockController(
        newSessionScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
        buildSession = ::buildSession,
        publish = { _session.value = it },
        stopSession = { it.coordinator.stop() },
        afterPublish = ::onSessionPublished,
    )

    /**
     * Build the session against the transport resolved RIGHT NOW — not the
     * process-start snapshot. [transportEndpoints] is the single source shared
     * with [applyTransport], so a session built at unlock and a later live-session
     * transport swap always agree on (client, apiBase, wsUrl).
     */
    private fun buildSession(sessionScope: CoroutineScope): SessionContainer {
        val (client, apiBase, ws) = transportEndpoints(transportResolver.state.value)
        httpClient = client
        return SessionContainer(
            app = app,
            scope = sessionScope,
            keyStoreManager = keyStoreManager,
            bootDiagnostics = bootDiagnostics,
            settings = settingsRepository,
            httpClient = httpClient,
            apiBaseUrl = apiBase,
            wsUrl = ws,
        )
    }

    /**
     * Runs once the session is live (from [UnlockController.unlock], inside its
     * lock). Re-applies the transport so a change that landed between the
     * factory's read and publish — which [applyTransport] dropped because it saw a
     * null session — is reconciled onto the now-live session (idempotent:
     * updateTransport just swaps a holder). Then drains any scan queued while
     * locked, so a `/d/{id}` opened pre-unlock probes now.
     */
    private fun onSessionPublished() {
        applyTransport(transportResolver.state.value)
        lemonDropVeilController.onUnlocked()
    }

    init {
        // The resolver owns the whole chain now (I2P/Tor/clearnet); its state
        // drives a single apply-loop. This REPLACES the old torEnabled collector
        // — two loops would fight over httpClient/URL swaps.
        transportResolver.start()
        scope.launch {
            transportResolver.state.collect(::applyTransport)
        }
    }

    /**
     * The transport-state → (client, apiBase, wsUrl) mapping, shared by
     * [applyTransport] and [buildSession] so a session built at unlock and a
     * live-session transport swap resolve to the SAME endpoints. Builds a fresh
     * pinned client per call, as the apply-loop always has.
     */
    private fun transportEndpoints(state: TransportState): Triple<OkHttpClient, String, String> =
        when (state) {
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

    private fun applyTransport(state: TransportState) {
        val (client, apiBase, ws) = transportEndpoints(state)
        // Always reconcile the client so the NEXT connect dials the resolved
        // transport. With NO live session (pre-unlock) this swap is all there is
        // to do — the session is later built against the current transport by
        // [buildSession] and re-reconciled by [onSessionPublished].
        httpClient = client
        val live = _session.value
        live?.apiClient?.updateTransport(httpClient, apiBase)
        live?.wsClient?.updateTransport(httpClient, ws)
        // Side effect of choosing Tor kept here (not in the Context-free
        // resolver): ask Orbot to start, exactly as applyTorSetting did. This is
        // a broadcast to Orbot, not a connection of ours — safe pre-unlock, and
        // it must fire even while our socket is down (no session yet) so Orbot is
        // already up when the post-unlock connect dials through its proxy.
        if (state == TransportState.TOR) TorIntegration.requestOrbotStart(app)
        // Only migrate a LIVE session (connected or mid-handshake) to the new
        // transport. When the socket is disconnected — notably pre-unlock, before
        // coordinator.start() fires — the swap above is enough and we must NOT
        // open the socket here: the token is readable before the biometric gate,
        // so an eager connect would breach it. This preserves the old collector's
        // drop(1) semantics while still migrating a running session when a
        // background probe promotes clearnet/Tor to I2P.
        if (live != null &&
            live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED
        ) {
            live.wsClient.disconnect()
            live.apiClient.accessToken?.let(live.wsClient::connect)
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

/**
 * Session-scoped half of the object graph — the messaging objects that live only
 * while unlocked. PR-D2b builds one of these per unlock ([UnlockController]),
 * against the transport resolved at that moment ([AppContainer.buildSession]),
 * each on its OWN scope that is cancelled on lock; D2c swaps the vault in behind
 * the same lifecycle. The object set, and its construction order, are unchanged
 * from the eager D1 build — only WHEN it is built moved.
 *
 * Construction ORDER is load-bearing and preserved from the pre-split
 * AppContainer: signalStore → signalManager → apiClient → wsClient →
 * messageRepository → conversationRepository → lemon-drop redeemer/creator →
 * notificationScheduler → coordinator (the scheduler is built BEFORE the
 * coordinator that owns it; conversationRepository BEFORE the lemon-drop objects
 * and the coordinator that use it).
 *
 * @param httpClient the CURRENT transport client at build time. Passed by value:
 *   [apiClient]/[wsClient] capture it once here; thereafter
 *   [AppContainer.applyTransport] swaps the client via `updateTransport`.
 */
class SessionContainer(
    app: Application,
    scope: CoroutineScope,
    keyStoreManager: KeyStoreManager,
    bootDiagnostics: BootDiagnostics,
    settings: SettingsRepository,
    // The CURRENT transport client at build time. Passed by value (not a `() ->`
    // accessor): apiClient/wsClient capture it once here, and later transport swaps
    // are pushed through updateTransport(), so the session never re-reads it.
    httpClient: OkHttpClient,
    apiBaseUrl: String,
    wsUrl: String,
) {
    val signalStore = EncryptedSignalProtocolStore(keyStoreManager)
    val signalManager = SignalProtocolManager(signalStore)

    // Legacy auth persistence behind the AuthStore seam (PR-D2a) — same
    // PREFS_AUTH keys, so ApiClient's token/account behaviour is unchanged.
    val apiClient = ApiClient(apiBaseUrl, httpClient, EncryptedAuthStore(keyStoreManager))

    // WsClient shares the coordinator's diagnostic channel (logcat tag +
    // on-device log) so socket-lifecycle failures land in Settings →
    // Diagnostics next to the boot-stage lines. Privacy-safe by the same
    // rule: fixed markers + exception metadata only.
    val wsClient = WsClient(wsUrl, httpClient, scope) { line ->
        Log.w("ZitroneBoot", line)
        bootDiagnostics.record(line)
    }

    val messageRepository = MessageRepository(scope)
    // Roster is persisted (encrypted) — the old in-memory-only ConversationRepository
    // was wiped on every process restart/update, losing pinned keys + verified flags.
    val conversationRepository =
        ConversationRepository(EncryptedRosterStore(keyStoreManager, signalStore))

    /**
     * Lemon-drop redeemer that probes/delivers a drop. The redeemer's sodium
     * adapter is lazysodium-android's prebuilt libsodium (no NDK build; JVM unit
     * tests run the same adapter over lazysodium-java). The process-scoped veil
     * it feeds lives on the device half ([AppContainer.lemonDropVeil]).
     */
    val lemonDropRedeemer = LemonDropRedeemer(
        api = apiClient,
        signalStore = signalStore,
        conversations = conversationRepository,
        sodium = LemonDropSodiumOps(SodiumAndroid()),
    )

    /**
     * Lemon-drop CREATOR (sub-phase 5b): the send-side counterpart of
     * [lemonDropRedeemer]. Same one-shot, session-less isolation contract — it
     * never advances the persistent ratchet or writes contact/session records —
     * and the same lazysodium-android adapter (the JVM suite exercises the byte
     * path over lazysodium-java).
     */
    val lemonDropCreator = LemonDropCreator(
        api = apiClient,
        signalStore = signalStore,
        conversations = conversationRepository,
        messages = messageRepository,
        sodium = LemonDropSodiumOps(SodiumAndroid()),
    )

    /**
     * Rate-limits + re-fires the content-free notification. Constructed BEFORE
     * the coordinator (which owns it). fire posts the one and only notification;
     * isEnabled reads the live toggle at fire time so flipping it takes effect
     * immediately; scope is the process-lifetime container scope.
     */
    val notificationScheduler = NotificationScheduler(
        scope = scope,
        fire = { MessagingNotifications.showNewMessage(app) },
        isEnabled = { settings.settings.value.unreadReminderEnabled },
        // Fire-time truth for the deferred re-fire: an unseen incoming message
        // is one still in DELIVERED (READ/BURNING/removed don't count). Keeps
        // the 2-minute boundary silent when short-TTL or remotely-burned
        // messages already vanished.
        hasUnread = { conversationId ->
            messageRepository.conversationMessages(conversationId)
                .any { !it.isMine && it.state == MessageState.DELIVERED }
        },
        // MONOTONIC clock, not wall time: an NTP sync or manual clock change
        // moving wall time backward would stretch the 2-minute cooldown by the
        // adjustment size and suppress alerts. elapsedRealtime() only ever
        // moves forward. (The scheduler's wall-clock default exists solely for
        // plain-JVM tests.)
        clock = { android.os.SystemClock.elapsedRealtime() },
    )

    val coordinator = MessagingCoordinator(
        appContext = app,
        scope = scope,
        signal = signalManager,
        api = apiClient,
        ws = wsClient,
        messages = messageRepository,
        conversations = conversationRepository,
        settings = settings,
        diagnostics = bootDiagnostics,
        notificationScheduler = notificationScheduler,
    )
}
