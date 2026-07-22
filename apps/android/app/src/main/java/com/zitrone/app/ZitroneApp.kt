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
 *  - [SessionContainer] is the SESSION half — the messaging objects that (from
 *    D2 onward) live only while a vault is unlocked.
 *
 * D1 is a PURE STRUCTURAL refactor: there is no unlock gate yet, so exactly ONE
 * [SessionContainer] is built EAGERLY at construction and every object is
 * created at the same moment, in the same order, as before the split — the app
 * behaves identically. Callers still reach session members through
 * `container.<member>` via the delegating accessors below. D2 makes the session
 * build on unlock over the vault facades.
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
    val deviceSettings = DeviceSettings(keyStoreManager, settingsRepository)

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
     * The single session-scoped half of the graph. Built EAGERLY in D1 (no
     * unlock gate yet) so [applyTransport] can reference `session.apiClient` /
     * `session.wsClient` exactly as the pre-split code referenced the flat
     * `apiClient` / `wsClient` fields — behaviour identical. The session reads
     * the CURRENT transport client via the `{ httpClient }` accessor (invoked
     * once, at construction, returning the initial client — same value the flat
     * fields captured before the split). D2 makes this build on unlock over the
     * vault facades and must then handle the "no session yet" pre-unlock
     * transport apply (OUT OF SCOPE for D1 — the eager build sidesteps it).
     */
    val session = SessionContainer(
        app = app,
        scope = scope,
        keyStoreManager = keyStoreManager,
        bootDiagnostics = bootDiagnostics,
        settings = settingsRepository,
        httpClient = httpClient,
        apiBaseUrl = API_BASE_URL,
        wsUrl = WS_URL,
    )

    // ── Delegating accessors ────────────────────────────────────────────────
    // Callers keep using `container.<member>` unchanged; each resolves to the
    // eagerly-built session's member. Only the members reached from outside
    // AppContainer are re-exposed here.
    val signalStore get() = session.signalStore
    val signalManager get() = session.signalManager
    val apiClient get() = session.apiClient
    val messageRepository get() = session.messageRepository
    val conversationRepository get() = session.conversationRepository
    val coordinator get() = session.coordinator
    val lemonDropRedeemer get() = session.lemonDropRedeemer
    val lemonDropCreator get() = session.lemonDropCreator

    /**
     * Lemon-drop bridge: the veil state a `/d/{id}` scan renders. The veil lives
     * HERE on the DEVICE half (process lifetime, not Activity) so a configuration
     * change keeps a decrypted-but-unrendered drop in memory without plaintext
     * ever touching saved state — see LemonDropVeil's security invariant. The
     * orchestration below owns the veil and delegates the probe/deliver work to
     * the session's redeemer.
     */
    val lemonDropVeil = MutableStateFlow<LemonDropVeil?>(null)

    private val lemonDropLock = Any()

    /**
     * Monotonic id of the most recent scan. A probe launched for an earlier
     * scan must not overwrite a newer scan's veil once superseded — two
     * `Advocacy(UNKNOWN)` values are structurally equal, so a compare-and-set
     * on the value alone would let a stale probe clobber the current scan
     * (Codex PR #4). Dismissal also bumps this so a late probe cannot resurrect
     * a screen the user already closed.
     */
    private var lemonDropScanToken = 0L

    /**
     * Handle a scanned `/d/{id}`: raise the veil, then run the single fetch +
     * isolated open in the PROCESS scope (not an Activity scope) so a
     * configuration change mid-probe neither cancels it nor strands the veil at
     * UNKNOWN. The refine applies only while this remains the current scan.
     */
    fun onLemonDropLink(qrId: String) {
        val token = synchronized(lemonDropLock) {
            lemonDropVeil.value = LemonDropVeil.Advocacy(LemonDropScanOutcome.UNKNOWN)
            ++lemonDropScanToken
        }
        scope.launch(Dispatchers.IO) {
            val refined = when (val probe = session.lemonDropRedeemer.probe(qrId)) {
                is LemonDropRedeemer.ProbeResult.Advocacy -> LemonDropVeil.Advocacy(probe.outcome)
                is LemonDropRedeemer.ProbeResult.ReadyToOpen -> LemonDropVeil.AwaitUnlock(probe.pending)
            }
            synchronized(lemonDropLock) {
                if (lemonDropScanToken == token) lemonDropVeil.value = refined
            }
        }
    }

    /** Dismiss the veil and invalidate any in-flight probe for it. */
    fun dismissLemonDropVeil() {
        synchronized(lemonDropLock) {
            ++lemonDropScanToken
            lemonDropVeil.value = null
        }
    }

    /**
     * Drop a plaintext-bearing [LemonDropVeil.Delivered] when the Activity
     * stops. The veil is process-scoped, so without this an opened drop would
     * re-render on a later Activity recreation with no fresh biometric unlock
     * (Codex PR #4). Advocacy/AwaitUnlock render no plaintext and are kept —
     * AwaitUnlock still forces a biometric before anything is shown. A one-shot
     * drop that's already been delivered is gone regardless, so losing the
     * on-screen copy here costs nothing.
     */
    fun clearDeliveredLemonDropVeil() {
        synchronized(lemonDropLock) {
            if (lemonDropVeil.value is LemonDropVeil.Delivered) lemonDropVeil.value = null
        }
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
        session.apiClient.updateTransport(httpClient, apiBase)
        session.wsClient.updateTransport(httpClient, ws)
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
        if (session.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {
            session.wsClient.disconnect()
            session.apiClient.accessToken?.let(session.wsClient::connect)
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
 * Session-scoped half of the object graph — the messaging objects that (from D2
 * onward) live only while a vault is unlocked. In D1 there is no unlock gate:
 * [AppContainer] builds exactly ONE of these EAGERLY at process start, so every
 * object is constructed at the same moment, and in the same order, as before the
 * split — the app behaves identically. D2 rebuilds this per unlocked slot over
 * the vault facades; D1 only carves the seam.
 *
 * Construction ORDER is load-bearing and preserved from the pre-split
 * AppContainer: signalStore → signalManager → apiClient → wsClient →
 * messageRepository → conversationRepository → lemon-drop redeemer/creator →
 * notificationScheduler → coordinator (the scheduler is built BEFORE the
 * coordinator that owns it; conversationRepository BEFORE the lemon-drop objects
 * and the coordinator that use it).
 *
 * @param httpClient accessor for the CURRENT transport client. Invoked once here,
 *   at construction, to seed [apiClient]/[wsClient] with the initial client —
 *   exactly the value the flat fields captured before the split; thereafter
 *   [AppContainer.applyTransport] swaps the client via `updateTransport`, as today.
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
    val signalManager = SignalProtocolManager(signalStore, keyStoreManager)

    val apiClient = ApiClient(apiBaseUrl, httpClient, keyStoreManager)

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
