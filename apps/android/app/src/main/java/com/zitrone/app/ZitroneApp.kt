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
import kotlinx.coroutines.CancellationException
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

    /**
     * Routing truth OVERRIDING [hasVault]: an account deletion confirmed the SERVER delete but
     * not yet the local vault unlink ([VaultImageStore.destroyPending]). The only valid route is
     * "finish the deletion" (retry [destroyVaultForAccountDeletion]) — never the unlock gate: a
     * partially-unlinked image no longer opens, and a zeroed one would silently re-register.
     */
    fun vaultDestroyPending(): Boolean = imageStore.destroyPending()

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
        // runtime.close() (the reseal + key-material wipe) runs in a finally so a
        // throw from coordinator.stop() can NEVER skip the wipe — otherwise a lock
        // would leave the slot key + decrypted plaintext resident in the heap.
        stopSession = {
            synchronized(transportLock) {
                try {
                    it.coordinator.stop()
                } finally {
                    it.runtime.close()
                }
            }
        },
        afterPublish = ::onSessionPublished,
    )

    // ── Vault unlock / create orchestration (all off-main; caller drives the UI) ──

    /**
     * Create a fresh vault sealing an EMPTY keystore under [passphrase], then PUBLISH its session
     * (onboarding = first unlock) in the SAME off-main block — so a mid-work coroutine cancellation
     * can never discard the freshly created [VaultOpen] unwiped: [publishSession] consumes-or-wipes
     * it before this block returns, and the session it builds lives on the process scope, not the
     * Activity. Returns true once the session is published. CPU-heavy (Argon2id×SLOT_COUNT+1). Wipes
     * the orphaned legacy prefs at creation (the zero-users clean-break decision). Propagates
     * [com.zitrone.app.crypto.vault.VaultImageException.NotDurable] so the caller can surface a
     * retry (or re-derive [hasVault] and route to unlock) — creation NEVER bricks.
     */
    suspend fun createVaultAndPublish(passphrase: String): Boolean = withContext(Dispatchers.Default) {
        val initial = VaultStateCodec.encode(VaultState.empty())
        val open = try {
            imageStore.create(passphrase, initial)
        } finally {
            // The genesis plaintext held nothing but empty holders, but zero it anyway —
            // create() does not consume its initialPayload.
            wipe(initial)
        }
        // `open` now holds a LIVE vault key + genesis payload. publishSession consumes them on an
        // accepted build and wipes them on a refused one; anything BEFORE that hand-off (the
        // best-effort legacy cleanup, a cancellation) must not abandon them unwiped.
        var handedOff = false
        try {
            // ZERO-USERS CLEAN BREAK (maintainer decision 2026-07-23): a pre-0.9.1 install
            // upgrading routes to vault setup, and creating the vault WIPES the orphaned legacy
            // signal/auth/contacts prefs — one-time hygiene, no data anyone owns (no accounts /
            // users exist). PREFS_SETTINGS (device settings + the biometric wrap) is deliberately
            // kept. Best-effort: a legacy-prefs error must NOT brick a fresh vault, so it is caught
            // and ignored rather than thrown.
            runCatching { wipeLegacyPrefs() }
            publishSession(open).also { handedOff = true }
        } finally {
            // Wipe only if publishSession never returned (a throw/cancellation before the hand-off):
            // once it returns it has already consumed-or-wiped the arrays, and re-wiping a key we
            // DID hand off would corrupt the running session.
            if (!handedOff) {
                wipe(open.vaultKey)
                wipe(open.payloadPlaintext)
            }
        }
    }

    /**
     * Attempt [passphrase] against the vault (off-main; both slots, no early exit) and, on a
     * match, PUBLISH the session — both in the SAME off-main block so a cancellation that fires as
     * the block ends cannot strand the materialized [VaultOpen] unwiped ([publishSession] consumes
     * or wipes it synchronously before the block returns). Returns whether a session was published
     * (false on no match OR on a refused build). Never logs anything credential-shaped.
     */
    suspend fun unlockWithPassphrase(passphrase: String): Boolean = withContext(Dispatchers.Default) {
        val open = imageStore.unlock(passphrase) ?: return@withContext false
        publishSession(open)
    }

    /**
     * Recover the vault key from [wrap] with an already-AUTHENTICATED [decryptCipher] (from a
     * successful CryptoObject BiometricPrompt), open the slot with it off-main, and PUBLISH the
     * session — the open+publish share one off-main block so cancellation can't strand the
     * [VaultOpen]. The recovered vault key is wiped in `finally` (unlockWithKey holds its own
     * independent copy — store contract :474-478). Returns whether a session was published (false
     * on an AEAD failure / no match / refused build).
     */
    suspend fun unlockWithBiometric(
        decryptCipher: javax.crypto.Cipher,
        wrap: com.zitrone.app.crypto.vault.BiometricWrappedKey,
    ): Boolean = withContext(Dispatchers.Default) {
        // The whole body — including openVaultKey's Cipher.doFinal — runs off-main so no crypto ever
        // executes on the caller (main) thread.
        val vaultKey = biometricCipher.openVaultKey(decryptCipher, wrap.blob) ?: return@withContext false
        try {
            val open = imageStore.unlockWithKey(vaultKey, wrap.slotIndex) ?: return@withContext false
            publishSession(open)
        } finally {
            wipe(vaultKey)
        }
    }

    /**
     * Enable biometric unlock over the LIVE [session] (spec §1): wrap a COPY of the running slot's
     * vault key — obtained via the narrow [SessionContainer.withVaultKey], wiped in its `finally` —
     * under the auth-gated biometric key with an already-AUTHENTICATED [encryptCipher], and persist
     * the constant-size `{ slotIndex, blob }` wrap. Returns true on success. Used by BOTH the
     * onboarding enable offer (post-publish) and the Settings toggle, so no live [VaultOpen] is ever
     * held across a recomposition.
     */
    fun enableBiometricFromSession(
        encryptCipher: javax.crypto.Cipher,
        session: SessionContainer,
    ): Boolean = session.withVaultKey { key ->
        val blob = biometricCipher.sealVaultKey(encryptCipher, key)
        biometricStore.save(com.zitrone.app.crypto.vault.BiometricWrappedKey(session.slotIndex, blob))
        true
    }

    /** Disable biometric unlock: delete the persisted wrap AND the auth-gated Keystore key. */
    fun disableBiometric() {
        biometricStore.clear()
        biometricCipher.deleteKey()
    }

    /**
     * The account-deletion primitive (NO REMANENCE). DESTROYS every on-disk + in-RAM trace of the
     * vault: [VaultImageStore.destroy] deletes `vault.bin` + `vault.dek` (+ tmp leftovers), wipes the
     * RAM DEK, and releases the single-instance registration; then the biometric wrap + its auth-gated
     * Keystore key are removed. Each step tolerates its OWN throw (a [CancellationException] still
     * propagates) so one failure can't strand the rest — the IMAGE destroy is the load-bearing one for
     * the deletion-permanence promise. Idempotent.
     *
     * Do NOT confuse with `runtime.close()` / `signalStore.wipe()`, which RESEAL the image (keeping the
     * account's crypto on disk) — those are a lock, not a deletion. This MUST run AFTER the session
     * teardown (runtime.close reseals the image); destroy() then deletes it, so NO resealed image
     * survives. After this call [hasVault] is false → the app routes to Onboarding (fresh-install state).
     *
     * The IMAGE destroy is the load-bearing no-remanence step and is NOT tolerated: [VaultImageStore.destroy]
     * verifies the unlink and THROWS [com.zitrone.app.crypto.vault.VaultImageException.DestroyFailed] if a
     * file survived, and that throw PROPAGATES so the caller does not claim a delete that did not take (it
     * surfaces a retry rather than routing to Onboarding-as-success). The biometric wrap/key removals are
     * best-effort hygiene (useless once the image is gone) and run FIRST, tolerated, so a Keystore hiccup
     * there cannot mask — or pre-empt — the image destroy's success/failure signal.
     */
    fun destroyVaultForAccountDeletion() {
        tolerateCleanup { biometricStore.clear() }
        tolerateCleanup { biometricCipher.deleteKey() }
        // NOT tolerated: a DestroyFailed (a surviving file) MUST reach the caller as a NOT-deleted signal.
        imageStore.destroy()
    }

    /**
     * Run one account-deletion cleanup step, tolerating its own non-cancellation throw so a single
     * failure (e.g. a Keystore already unhealthy) can't strand the remaining steps. A
     * [CancellationException] is rethrown BEFORE the broad catch so cooperative cancellation still
     * unwinds — the package-wide catch-ordering discipline.
     */
    private inline fun tolerateCleanup(step: () -> Unit) {
        try {
            step()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Tolerated: one cleanup's failure must not strand the others (the image destroy is the
            // load-bearing one; the biometric removals are best-effort hygiene).
        }
    }

    /** Reveal the passphrase lock screen while KEEPING a queued lemon-drop scan (see controller). */
    fun revealLockScreenKeepingLemonDropScan() =
        lemonDropVeilController.revealLockScreenKeepingScan()

    /**
     * Hand a resolved [vaultOpen] to the session build. On an accepted build the VaultSession
     * consumes its arrays; a REFUSED build (terminal wipe / already live) wipes them here so no
     * vault key or plaintext is abandoned, and a BUILD THROW is wiped by [UnlockController] +
     * SessionContainer's construction guard, then rethrown. Returns whether a session was actually
     * published (so the caller never reports success onto a null session). Marks onboarding complete
     * (first unlock = onboarding completion) only when a session was published.
     */
    fun publishSession(vaultOpen: VaultOpen): Boolean {
        var published = false
        unlockController.unlock(
            prepared = { sessionScope ->
                buildVaultSession(sessionScope, vaultOpen).also { published = true }
            },
            onRefused = {
                wipe(vaultOpen.vaultKey)
                wipe(vaultOpen.payloadPlaintext)
            },
        )
        if (published) settingsRepository.setOnboardingDone(true)
        return published
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

    /** Clear the orphaned legacy stores at vault creation (see [createVaultAndPublish]). */
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
    /** Which image slot this session unlocked — needed to persist a biometric re-wrap ([withVaultKey]). */
    val slotIndex: Int = vaultOpen.slotIndex

    /** The single mutation gate over this slot's keystore (see the [VaultRuntime] kdoc). */
    val runtime: VaultRuntime

    // The VaultSession that owns this slot's key + payload. Held ONLY so [withVaultKey] can hand a
    // wiped-in-finally COPY of the vault key to the biometric re-wrap (spec §1); not used otherwise.
    private val vaultSession: VaultSession

    // The concrete facade is kept for the atomic contact-delete's flush-free record removal;
    // consumers see the store-agnostic [ZitroneSignalStore] seam (D2a), unchanged over either store.
    private val vaultSignalStore: VaultSignalProtocolStore
    val signalStore: ZitroneSignalStore
    val signalManager: SignalProtocolManager
    val apiClient: ApiClient
    val wsClient: WsClient
    val messageRepository: MessageRepository
    val conversationRepository: ConversationRepository

    /**
     * Vault-scoped settings facade — HELD but NOT yet driving SettingsScreen (that switch is
     * D5). D2c keeps every vault-scoped setting reading legacy prefs on all paths to avoid a
     * split-brain; this reference just proves the facade slots in.
     */
    val vaultSettingsStore: VaultSettingsStore
    val lemonDropRedeemer: LemonDropRedeemer
    val lemonDropCreator: LemonDropCreator
    val notificationScheduler: NotificationScheduler
    val coordinator: MessagingCoordinator

    init {
        // DECODE a defensive COPY of the payload FIRST — before the [VaultSession] constructor
        // destructively consumes the VaultOpen's payload + key arrays (VaultSession.kt:54-59) — so
        // the two never race over the same buffers, and the copy is wiped in `finally`. A decode
        // failure (e.g. a downgrade over a newer state version) throws HERE, before any
        // VaultSession/runtime exists: the caller's onRefused wipes the still-intact VaultOpen and
        // UnlockController cancels the freshly created scope.
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
        vaultSession = session
        val rt = VaultRuntime(session, decoded)
        runtime = rt
        // From here the runtime holds this slot's live key + payload copies. Any throw while
        // building the facades / coordinator below would otherwise abandon a live VaultSession on
        // the heap with no reseal or wipe — so reseal + wipe it via runtime.close() (idempotent)
        // and rethrow; UnlockController.unlock cancels the scope on that rethrow.
        try {
            vaultSignalStore = VaultSignalProtocolStore(rt)
            signalStore = vaultSignalStore
            signalManager = SignalProtocolManager(signalStore)
            apiClient = ApiClient(apiBaseUrl, httpClient, VaultAuthStore(rt))
            wsClient = WsClient(wsUrl, httpClient, scope) { line ->
                Log.w("ZitroneBoot", line)
                bootDiagnostics.record(line)
            }
            messageRepository = MessageRepository(scope)
            conversationRepository = ConversationRepository(VaultRosterStore(rt))
            vaultSettingsStore = VaultSettingsStore(rt)
            lemonDropRedeemer = LemonDropRedeemer(
                api = apiClient,
                signalStore = signalStore,
                conversations = conversationRepository,
                sodium = LemonDropSodiumOps(SodiumAndroid()),
                // Flush-before-handoff for the open path: the consumed prekey must reach disk
                // before the burn hands the relay its shred order (deliverDurablyThenBurn).
                flushDurable = rt::flushBeforeAck,
            )
            lemonDropCreator = LemonDropCreator(
                api = apiClient,
                signalStore = signalStore,
                conversations = conversationRepository,
                messages = messageRepository,
                sodium = LemonDropSodiumOps(SodiumAndroid()),
            )
            notificationScheduler = NotificationScheduler(
                scope = scope,
                fire = { MessagingNotifications.showNewMessage(app) },
                isEnabled = { settings.settings.value.unreadReminderEnabled },
                hasUnread = { conversationId ->
                    messageRepository.conversationMessages(conversationId)
                        .any { !it.isMine && it.state == MessageState.DELIVERED }
                },
                clock = { android.os.SystemClock.elapsedRealtime() },
            )
            coordinator = MessagingCoordinator(
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
                // Flush-before-ack barrier (D2c, absorbs D4): the coordinator reseals the receiving
                // ratchet durably before acking each inbound delivery. rt is the live runtime.
                flushBeforeAck = rt::flushBeforeAck,
            )
        } catch (t: Throwable) {
            runCatching { rt.close() }
            throw t
        }
    }

    /**
     * Hand a wiped-in-finally COPY of the live vault key to [block] (delegates to
     * [VaultSession.withVaultKey]). The ONLY use is biometric enable over a live session (spec §1)
     * — dual-wrapping the vault key without re-deriving it from the passphrase.
     */
    fun <T> withVaultKey(block: (ByteArray) -> T): T = vaultSession.withVaultKey(block)

    /**
     * Vault contact-delete atomicity (VaultSignalProtocolStore :222-231): the roster entry +
     * tombstone + crypto-record removal seal in ONE [VaultRuntime.mutate] + ONE
     * [VaultRuntime.flushBeforeAck], run INSIDE [ConversationRepository.deleteContactDurably] so the
     * whole operation holds that repo's monitor — the single serialization point that keeps a
     * concurrent roster write from resurrecting or losing an entry. Returns whether the durable
     * flush confirmed; the removal is applied in memory + live state regardless (never rolled back —
     * the crypto cannot be un-removed), so a false return means "unconfirmed durable", not "kept".
     */
    private suspend fun deleteContactAtomically(
        conversationId: String,
        contactId: String,
        at: Long,
    ): ContactDeleteOutcome {
        // Set from INSIDE the mutate block, AFTER the removal has touched live state but BEFORE
        // encode can throw. That placement is load-bearing for the outcome mapping: a closed-runtime
        // mutate throws its `check(!closed)` BEFORE the block runs, so this stays false → NOT_APPLIED
        // (the delete did not take). But a VaultCapacityException thrown by mutate's ENCODE happens
        // AFTER the block already mutated live state, so this is already true → APPLIED_UNCONFIRMED
        // (the crypto IS gone from the runtime; it persists on the next flush that fits), NOT a false
        // NOT_APPLIED. Captured across the seal lambda, which runs synchronously.
        var mutateApplied = false
        return conversationRepository.deleteContactDurably(conversationId, contactId, at) { rosterJson, tombstonesJson ->
            // BOTH mutate and flush are contained: a teardown race (forced logout /
            // revocation runs runtime.close() while this delete is mid-seal) makes
            // mutate throw IllegalStateException("closed") — synchronous, so
            // cancellation can't preempt it. Uncaught, that would crash the
            // confined worker (no CoroutineExceptionHandler) AND leave a half-delete
            // (burnAll already ran; the RAM/tombstone reconcile in the caller would
            // be skipped). Caught, it degrades to a false — and [mutateApplied] tells
            // a lost delete from an unconfirmed one, so the OUTCOME (not just a bool)
            // is returned to the repository: it keeps its RAM entry + tombstone on
            // NOT_APPLIED (the contact is still present). The removal, once applied,
            // is never rolled back.
            val durable = sealDurableOrFalse {
                runtime.mutate { state ->
                    vaultSignalStore.removeContactCryptoRecords(state, contactId)
                    rosterJson?.let { state.rosterJson = it }
                    state.tombstonesJson = tombstonesJson
                    // Mark applied HERE — the removal is now in live state. A capacity-during-encode
                    // throw (below, still inside mutate) then reports APPLIED_UNCONFIRMED, not
                    // NOT_APPLIED; a closed-runtime throw never reaches this line.
                    mutateApplied = true
                }
                runtime.flushBeforeAck()
            }
            contactDeleteOutcome(durable, mutateApplied)
        }
    }
}

/**
 * Runs a vault durability [seal] (a mutate + [VaultRuntime.flushBeforeAck]) and maps its outcome to
 * the [ConversationRepository.deleteContactDurably] contract: `true` when it committed durably;
 * `false` on a NON-cancellation failure ("unconfirmed durable" — the removal is NEVER rolled back,
 * so a false means "not confirmed", not "kept"); and a RETHROWN [CancellationException] so a scope
 * teardown mid-delete (forced logout / revocation running runtime.close()) UNWINDS cooperatively
 * instead of being folded into a false.
 *
 * Extracted top-level (mirroring [flushThenAck]) so the catch-ORDERING — rethrow the cancellation
 * BEFORE the broad `catch (Throwable) -> false` — is host-testable without a live SessionContainer.
 * That ordering is the whole point: were the order reversed, a real teardown cancellation would be
 * swallowed as a false. NOTE a full vault ([VaultCapacityException]) and a closed runtime both throw
 * [IllegalStateException], which lands in the Throwable arm as an honest `false`; only cooperative
 * cancellation escapes.
 */
internal fun sealDurableOrFalse(seal: () -> Unit): Boolean =
    try {
        seal()
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        false
    }
