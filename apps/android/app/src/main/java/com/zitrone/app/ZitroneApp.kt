// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.app.Application
import android.util.Log
import com.goterl.lazysodium.SodiumAndroid
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.LemonDropSodiumOps
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.crypto.VaultSignalProtocolStore
import com.zitrone.app.crypto.ZitroneSignalStore
import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
import com.zitrone.app.crypto.vault.KeystoreDeviceKeyCipher
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.VaultOpen
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultSodiumOps
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.wipe
import com.zitrone.app.data.BiometricUnlockStore
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.DeviceSettings
import com.zitrone.app.data.LemonDropCreator
import com.zitrone.app.data.LemonDropRedeemer
import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.LemonDropVeil
import com.zitrone.app.data.MessageRepository
import com.zitrone.app.data.MessageState
import com.zitrone.app.data.SettingsRepository
import com.zitrone.app.data.TransportState
import com.zitrone.app.data.VaultAuthStore
import com.zitrone.app.data.VaultRosterStore
import com.zitrone.app.data.VaultSettingsStore
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
import kotlinx.coroutines.withContext
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
 *    diagnostics, the lemon-drop veil, AND the vault device-layer ([imageStore] +
 *    [biometricCipher]) that survives lock/unlock cycles.
 *  - [SessionContainer] is the SESSION half — the messaging objects that live
 *    only while a slot is unlocked, now backed by the vault runtime.
 *
 * PR-D2c makes the app VAULT-ONLY (maintainer decision: zero users/accounts exist,
 * so there is no migration constituency). Routing truth is [hasVault]
 * (`imageStore.exists()`): no image → vault SETUP (onboarding passphrase → create),
 * image present → vault UNLOCK (passphrase always; biometric iff enabled). There is
 * NO silent auto-unlock and no fail-open — every unlock is passphrase-or-biometric.
 * The legacy store CLASSES stay in the tree (still compiled + test-covered); only
 * the runtime WIRING here is the vault path.
 */
class AppContainer(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyStoreManager = KeyStoreManager(app)

    // Legacy settings store — still the single source of truth for DEVICE-level
    // settings and, on ALL D2c paths, the vault-scoped fields too (D5 moves the
    // latter into the vault; D2c keeps them on prefs to avoid a split-brain).
    val settingsRepository = SettingsRepository(keyStoreManager)

    /** Device-scoped, pre-unlock settings view over the SAME legacy store. */
    val deviceSettings = DeviceSettings(settingsRepository)

    // ── Vault device layer (process lifetime; survives lock/unlock) ────────────

    /** Portable AES-256-GCM + Argon2id backend, shared by the image store and every session. */
    private val vaultOps: VaultSodiumOps = LibsodiumVaultOps(SodiumAndroid())

    /**
     * The ONE device-level image store for this install (single-instance-per-baseDir
     * contract). Held open for the process lifetime across lock/unlock — the outer
     * device-key layer is not a slot secret, so keeping it open is fine, and a fresh
     * unlock reuses this instance rather than re-registering the directory.
     */
    val imageStore = VaultImageStore(app.filesDir, vaultOps, KeystoreDeviceKeyCipher())

    /** The auth-gated biometric key that wraps the slot-A vault key (dual-wrap, posture B). */
    val biometricCipher = BiometricVaultKeyCipher()

    /** Persisted `{ slotIndex, wrappedVaultKey }` — present ONLY for a biometric-enabled install. */
    val biometricStore = BiometricUnlockStore(keyStoreManager)

    /** Composable-free unlock decisions (backoff, uniform failure, biometric gating). */
    val unlockRouter = VaultUnlockRouter()

    /** Routing truth: a vault image is present → UNLOCK, absent → SETUP (onboarding). */
    fun hasVault(): Boolean = imageStore.exists()

    // @Volatile so the transport apply-loop (running on Dispatchers.Default) and
    // the construction thread publish/read the current client consistently.
    @Volatile
    private var httpClient =
        CertificatePinning.buildClient(torEnabled = deviceSettings.torEnabled)

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
     * over the vault, not eagerly. Null while locked; a live [SessionContainer]
     * once [publishSession] hands a resolved [VaultOpen] to [UnlockController].
     */
    private val _session = MutableStateFlow<SessionContainer?>(null)
    val session: StateFlow<SessionContainer?> = _session.asStateFlow()

    private val lemonDropVeilController = LemonDropVeilController(
        scope = scope,
        isUnlocked = { _session.value != null },
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
     * The session-per-unlock lifecycle. Builds a fresh vault-backed [SessionContainer]
     * over the CURRENT transport from a resolved [VaultOpen], and tears it down (with a
     * final vault reseal via `runtime.close`) on lock. See [UnlockController].
     */
    val unlockController = UnlockController<SessionContainer>(
        newSessionScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
        // The vault path always builds via unlock(prepared) with a resolved VaultOpen; a
        // no-arg unlock has no VaultOpen to consume and is unused on this install.
        buildSession = { error("vault install builds sessions via unlock(prepared)") },
        publish = { published ->
            synchronized(transportLock) { _session.value = published }
            if (published == null) lemonDropVeilController.onLocked()
        },
        // Teardown: stop the coordinator THEN close the runtime (final reseal + state
        // wipe), under transportLock. The imageStore itself stays open (device half).
        stopSession = {
            synchronized(transportLock) {
                it.coordinator.stop()
                it.runtime.close()
            }
        },
        afterPublish = ::onSessionPublished,
    )

    // ── Vault unlock / create orchestration (all off-main; caller drives the UI) ──

    /**
     * Create a fresh vault sealing an EMPTY keystore under [passphrase], and return the
     * live [VaultOpen] (onboarding = first unlock). CPU-heavy (Argon2id×SLOT_COUNT+1) →
     * runs off-main. Wipes the orphaned legacy prefs at creation (the zero-users
     * clean-break decision, see [wipeLegacyPrefs]). Propagates
     * [com.zitrone.app.crypto.vault.VaultImageException.NotDurable] so the caller can
     * surface a retry — creation NEVER bricks.
     */
    suspend fun createVault(passphrase: String): VaultOpen = withContext(Dispatchers.Default) {
        val initial = VaultStateCodec.encode(VaultState.empty())
        val open = try {
            imageStore.create(passphrase, initial)
        } finally {
            // The genesis plaintext held nothing but empty holders, but zero it anyway —
            // create() does not consume its initialPayload.
            wipe(initial)
        }
        // ZERO-USERS CLEAN BREAK (maintainer decision 2026-07-23): a pre-0.9.1 install
        // upgrading routes to vault setup, and creating the vault WIPES the orphaned legacy
        // signal/auth/contacts prefs — one-time hygiene, no data anyone owns (no accounts /
        // users exist). PREFS_SETTINGS (device settings + the biometric wrap) is deliberately
        // kept.
        wipeLegacyPrefs()
        open
    }

    /**
     * Attempt [passphrase] against the vault (off-main; both slots, no early exit). On a
     * match, publishes the session and returns true; on no match returns false (the caller
     * applies the RAM backoff via [unlockRouter]). Never logs anything credential-shaped.
     */
    suspend fun unlockWithPassphrase(passphrase: String): Boolean {
        val open = withContext(Dispatchers.Default) { imageStore.unlock(passphrase) } ?: return false
        publishSession(open)
        return true
    }

    /**
     * Recover the vault key from [blob] with an already-AUTHENTICATED [decryptCipher] (from a
     * successful CryptoObject BiometricPrompt), open the slot with it off-main, and publish the
     * session. The caller's [blob] is untouched; the recovered vault key is wiped in `finally`
     * (unlockWithKey holds its own independent copy — store contract :474-478). Returns true on
     * success, false on an AEAD failure / no match.
     */
    suspend fun unlockWithBiometric(
        decryptCipher: javax.crypto.Cipher,
        wrap: com.zitrone.app.crypto.vault.BiometricWrappedKey,
    ): Boolean {
        val vaultKey = biometricCipher.openVaultKey(decryptCipher, wrap.blob) ?: return false
        val open = try {
            withContext(Dispatchers.Default) { imageStore.unlockWithKey(vaultKey, wrap.slotIndex) }
        } finally {
            wipe(vaultKey)
        } ?: return false
        publishSession(open)
        return true
    }

    /**
     * Wrap a COPY of [vaultOpen]'s vault key under the auth-gated biometric key using an
     * already-AUTHENTICATED [encryptCipher] (from a successful CryptoObject BiometricPrompt),
     * and persist the constant-size `{ slotIndex, blob }` wrap. The copy is wiped in `finally`;
     * [vaultOpen] is left intact for the following session build. Call BEFORE handing the
     * VaultOpen to [publishSession] (which consumes its arrays).
     */
    fun enableBiometric(encryptCipher: javax.crypto.Cipher, vaultOpen: VaultOpen) {
        val keyCopy = vaultOpen.vaultKey.copyOf()
        val blob = try {
            biometricCipher.sealVaultKey(encryptCipher, keyCopy)
        } finally {
            wipe(keyCopy)
        }
        biometricStore.save(
            com.zitrone.app.crypto.vault.BiometricWrappedKey(vaultOpen.slotIndex, blob),
        )
    }

    /** Disable biometric unlock: delete the persisted wrap AND the auth-gated Keystore key. */
    fun disableBiometric() {
        biometricStore.clear()
        biometricCipher.deleteKey()
    }

    /**
     * Hand a resolved [vaultOpen] to the session build. On an accepted build the VaultSession
     * consumes its arrays; a REFUSED build (terminal wipe / already live) wipes them here so no
     * vault key or plaintext is abandoned. Marks onboarding complete (first unlock = onboarding
     * completion), harmless to repeat on later unlocks.
     */
    fun publishSession(vaultOpen: VaultOpen) {
        unlockController.unlock(
            prepared = { sessionScope -> buildVaultSession(sessionScope, vaultOpen) },
            onRefused = {
                wipe(vaultOpen.vaultKey)
                wipe(vaultOpen.payloadPlaintext)
            },
        )
        settingsRepository.setOnboardingDone(true)
    }

    private fun buildVaultSession(sessionScope: CoroutineScope, vaultOpen: VaultOpen): SessionContainer {
        val (client, apiBase, ws) = transportEndpoints(transportResolver.state.value)
        httpClient = client
        return SessionContainer(
            app = app,
            scope = sessionScope,
            bootDiagnostics = bootDiagnostics,
            settings = settingsRepository,
            httpClient = httpClient,
            apiBaseUrl = apiBase,
            wsUrl = ws,
            vaultOps = vaultOps,
            vaultOpen = vaultOpen,
            persist = imageStore::writeSealedPayload,
        )
    }

    /** Clear the orphaned legacy stores at vault creation (see [createVault]). */
    private fun wipeLegacyPrefs() {
        keyStoreManager.prefs(KeyStoreManager.PREFS_SIGNAL_STORE).edit().clear().apply()
        keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH).edit().clear().apply()
        keyStoreManager.prefs(KeyStoreManager.PREFS_CONTACTS).edit().clear().apply()
    }

    private fun onSessionPublished() {
        synchronized(transportLock) {
            applyTransportLocked(transportResolver.state.value)
        }
        lemonDropVeilController.onUnlocked()
    }

    private val transportLock = Any()

    init {
        transportResolver.start()
        scope.launch {
            transportResolver.state.collect(::applyTransport)
        }
    }

    private fun applyTransport(state: TransportState) =
        synchronized(transportLock) { applyTransportLocked(state) }

    private fun applyTransportLocked(state: TransportState) {
        if (state != transportResolver.state.value) return
        val (client, apiBase, ws) = transportEndpoints(state)
        httpClient = client
        val live = _session.value
        live?.apiClient?.updateTransport(httpClient, apiBase)
        live?.wsClient?.updateTransport(httpClient, ws)
        if (state == TransportState.TOR) TorIntegration.requestOrbotStart(app)
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

        private val i2pApiBaseUrl = "http://${BuildConfig.RELAY_I2P_DEST}"
        private val i2pWsUrl = "ws://${BuildConfig.RELAY_I2P_DEST}/ws"

        internal fun transportEndpoints(state: TransportState): Triple<OkHttpClient, String, String> =
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
                else -> Triple(CertificatePinning.buildClient(torEnabled = false), API_BASE_URL, WS_URL)
            }
    }
}

/**
 * Session-scoped half of the object graph — the messaging objects that live only
 * while a slot is unlocked, VAULT-BACKED (PR-D2c). Built per unlock ([UnlockController])
 * from a resolved [VaultOpen], against the transport resolved at that moment. The object
 * set and construction order match the pre-vault build; only the backing store changed —
 * every facade is a behavioural twin over one shared [VaultRuntime], so the consumers
 * (SignalProtocolManager / ApiClient / ConversationRepository / the lemon-drop objects)
 * are UNCHANGED.
 *
 * Construction ORDER is load-bearing: runtime → signalStore → signalManager → apiClient →
 * wsClient → messageRepository → conversationRepository → lemon-drop redeemer/creator →
 * notificationScheduler → coordinator.
 */
class SessionContainer(
    app: Application,
    scope: CoroutineScope,
    bootDiagnostics: BootDiagnostics,
    settings: SettingsRepository,
    httpClient: OkHttpClient,
    apiBaseUrl: String,
    wsUrl: String,
    vaultOps: VaultSodiumOps,
    vaultOpen: VaultOpen,
    persist: (slotIndex: Int, sealedPayload: ByteArray) -> Unit,
) {
    /**
     * The single mutation gate over this slot's keystore. Built by DECODING a defensive
     * COPY of the VaultOpen payload FIRST — before the [VaultSession] constructor
     * destructively consumes the VaultOpen's payload + key arrays (VaultSession.kt:54-59) —
     * so the two never race over the same buffers. The copy is wiped in `finally`.
     */
    val runtime: VaultRuntime = run {
        val decoded: VaultState = run {
            val copy = vaultOpen.payloadPlaintext.copyOf()
            try {
                VaultStateCodec.decode(copy)
            } finally {
                wipe(copy)
            }
        }
        val session = VaultSession(
            scope = scope,
            ops = vaultOps,
            initialPayload = vaultOpen.payloadPlaintext,
            initialVaultKey = vaultOpen.vaultKey,
            slotIndex = vaultOpen.slotIndex,
            persist = persist,
        )
        VaultRuntime(session, decoded)
    }

    // The concrete facade is kept for the atomic contact-delete's flush-free record removal;
    // consumers see the store-agnostic [ZitroneSignalStore] seam (D2a), unchanged over either store.
    private val vaultSignalStore = VaultSignalProtocolStore(runtime)
    val signalStore: ZitroneSignalStore = vaultSignalStore
    val signalManager = SignalProtocolManager(signalStore)

    val apiClient = ApiClient(apiBaseUrl, httpClient, VaultAuthStore(runtime))

    val wsClient = WsClient(wsUrl, httpClient, scope) { line ->
        Log.w("ZitroneBoot", line)
        bootDiagnostics.record(line)
    }

    val messageRepository = MessageRepository(scope)
    val conversationRepository = ConversationRepository(VaultRosterStore(runtime))

    /**
     * Vault-scoped settings facade — HELD but NOT yet driving SettingsScreen (that switch is
     * D5). D2c keeps every vault-scoped setting reading legacy prefs on all paths to avoid a
     * split-brain; this reference just proves the facade slots in.
     */
    val vaultSettingsStore = VaultSettingsStore(runtime)

    val lemonDropRedeemer = LemonDropRedeemer(
        api = apiClient,
        signalStore = signalStore,
        conversations = conversationRepository,
        sodium = LemonDropSodiumOps(SodiumAndroid()),
    )

    val lemonDropCreator = LemonDropCreator(
        api = apiClient,
        signalStore = signalStore,
        conversations = conversationRepository,
        messages = messageRepository,
        sodium = LemonDropSodiumOps(SodiumAndroid()),
    )

    val notificationScheduler = NotificationScheduler(
        scope = scope,
        fire = { MessagingNotifications.showNewMessage(app) },
        isEnabled = { settings.settings.value.unreadReminderEnabled },
        hasUnread = { conversationId ->
            messageRepository.conversationMessages(conversationId)
                .any { !it.isMine && it.state == MessageState.DELIVERED }
        },
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
        vaultContactDelete = ::deleteContactAtomically,
    )

    /**
     * Vault contact-delete atomicity (VaultSignalProtocolStore :222-231): remove the contact's
     * crypto records + roster entry + tombstone in ONE [VaultRuntime.mutate], then ONE
     * [VaultRuntime.flushBeforeAck]. Returns whether the flush confirmed durable; the coordinator
     * commits the in-memory removal (after burning the messages) only on true. On a flush failure
     * the removal is retained in memory (never rolled back) but reported un-durable so the caller
     * keeps the contact for a retry. [at] anchors the tombstone consistently with the coordinator's
     * later commit.
     */
    private suspend fun deleteContactAtomically(conversationId: String, contactId: String, at: Long): Boolean {
        val blobs = conversationRepository.deletionBlobs(conversationId, contactId, at)
        return try {
            runtime.mutate { state ->
                vaultSignalStore.removeContactCryptoRecords(state, contactId)
                blobs.rosterJson?.let { state.rosterJson = it }
                state.tombstonesJson = blobs.tombstonesJson
            }
            runtime.flushBeforeAck()
            true
        } catch (t: Throwable) {
            false
        }
    }
}
