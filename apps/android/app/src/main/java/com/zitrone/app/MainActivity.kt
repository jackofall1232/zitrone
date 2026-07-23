// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.zitrone.app.data.Conversation
import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.LemonDropVeil
import com.zitrone.app.data.PendingLemonDrop
import com.zitrone.app.data.SettingsRepository
import com.zitrone.app.data.TransportState
import com.zitrone.app.data.parseQrDropLink
import com.zitrone.app.i2p.I2pIntegration
import com.zitrone.app.security.RootDetection
import com.zitrone.app.tor.TorIntegration
import com.zitrone.app.ui.components.buildContactExchangePayload
import com.zitrone.app.ui.screens.AddContactScreen
import com.zitrone.app.ui.screens.ChatListScreen
import com.zitrone.app.ui.screens.ChatScreen
import com.zitrone.app.ui.screens.DiagnosticsScreen
import com.zitrone.app.ui.screens.KeyVerificationScreen
import com.zitrone.app.ui.screens.LemonDropAdvocacyScreen
import com.zitrone.app.ui.screens.LemonDropDeliveredScreen
import com.zitrone.app.ui.screens.LemonDropUnlockScreen
import com.zitrone.app.ui.screens.LockScreen
import com.zitrone.app.ui.screens.OnboardingScreen
import com.zitrone.app.ui.screens.SettingsScreen
import com.zitrone.app.ui.screens.SplashScreen
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.Motion
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import com.zitrone.app.ui.theme.ZitroneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single Activity. Extends FragmentActivity because BiometricPrompt
 * requires it.
 *
 * CRITICAL RULE: FLAG_SECURE is set in onCreate BEFORE setContent. This is
 * the OS-level hard block — screenshots and screen recordings of any screen
 * in this Activity render black. Every Activity that can ever show message
 * content must do exactly this; in this app, that's the only Activity there
 * is.
 */
/** Saved-instance-state key for the lemon-drop advocacy veil's outcome. */
private const val STATE_LEMON_DROP_SCAN = "lemon_drop_scan"

class MainActivity : FragmentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Either way we proceed: notifications are content-free anyway.
        }

    /**
     * The lemon-drop veil's state (see [LemonDropVeil]); null means hidden. The
     * veil raises immediately as advocacy/[LemonDropScanOutcome.UNKNOWN] and
     * refines to the probe's honest outcome when (and only if) it lands while
     * the veil is still up. VIEW intents arrive HERE — onCreate and
     * [onNewIntent] — but the flow itself lives in the AppContainer (process
     * lifetime) so a configuration change keeps a decrypted-but-unrendered
     * drop in memory without EVER writing plaintext to saved state.
     */
    private val lemonDropVeil
        get() = (application as ZitroneApp).container.lemonDropVeil

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── FLAG_SECURE before any content exists. Never remove. ──────────
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val container = (application as ZitroneApp).container

        maybeRequestNotificationPermission()

        // Handle the launch intent ONLY on a fresh start, not on a config-change
        // recreation (savedInstanceState != null): re-running it on every rotation
        // would fire a second fetch and break the "exactly ONE fetch per scan"
        // rule. A genuinely new scan while we're already running arrives via
        // onNewIntent instead. On recreation the veil's VISIBILITY is restored
        // from the saved state (no re-fetch) so rotating the phone doesn't
        // silently swap the advocacy screen for the lock/splash underneath.
        if (savedInstanceState == null) {
            handleDeepLink(intent)
        } else if (lemonDropVeil.value == null) {
            // Process-death restore. Only an ADVOCACY outcome is ever saved —
            // plaintext-bearing states are never persisted (see LemonDropVeil);
            // a drop that was pending unlock is simply gone from the veil, and
            // because nothing was burned it is still on the relay for a
            // re-scan. When the process survived (config change), the
            // container-held veil is authoritative and the saved copy is stale.
            lemonDropVeil.value = savedInstanceState.getString(STATE_LEMON_DROP_SCAN)
                ?.let { saved -> LemonDropScanOutcome.entries.find { it.name == saved } }
                ?.let { LemonDropVeil.Advocacy(it) }
        }

        setContent {
            ZitroneTheme {
                ZitroneRoot(
                    container = container,
                    requestBiometric = ::showBiometricPrompt,
                    startVaultBiometricUnlock = ::startVaultBiometricUnlock,
                    startBiometricEnable = ::startBiometricEnableFromSession,
                    lemonDropVeil = lemonDropVeil.asStateFlow(),
                    onLemonDropDismissed = {
                        (application as ZitroneApp).container.dismissLemonDropVeil()
                    },
                    onLemonDropOpened = ::openLemonDrop,
                )
            }
        }
    }

    // singleTask: a new deep link that arrives while we're already running is
    // delivered here, not through a fresh onCreate. Keep setIntent in sync so any
    // later getIntent() reflects the current link.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // The advocacy veil must survive a configuration change: only its outcome
    // (which selects the copy) is saved — the fetch already fired exactly once
    // when the link arrived and is never replayed on restore.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // ADVOCACY outcome only — AwaitUnlock/Delivered carry plaintext and
        // must never reach the saved-state Bundle (see LemonDropVeil).
        outState.putString(
            STATE_LEMON_DROP_SCAN,
            (lemonDropVeil.value as? LemonDropVeil.Advocacy)?.outcome?.name,
        )
    }

    /**
     * Lemon-drop ("QR dead drop") link handling. When this phone opens
     * `https://zitrone.app/d/{id}`:
     *
     *  1. the veil raises IMMEDIATELY (advocacy/UNKNOWN — it must not wait on
     *     the network);
     *  2. ONE unauthenticated fetch + one ISOLATED open attempt run in the
     *     background ([LemonDropRedeemer.probe] → [LemonDropOneShot], the
     *     one-shot responder that is deliberately separate from ordinary
     *     libsignal messaging);
     *  3. the veil refines to what the probe honestly established — advocacy
     *     copy per [LemonDropScanOutcome], or, when the seal opened for THIS
     *     device and the sender cross-check passed, "unlock to open"
     *     ([LemonDropVeil.AwaitUnlock] — plaintext held, not rendered, until
     *     the biometric gate passes in [openLemonDrop]).
     *
     * The probe is side-effect-free beyond its single fetch: nothing is burned
     * and no prekey is consumed until delivery, so dismissing at any pre-unlock
     * point leaves the drop on the relay for a later re-scan. The orchestration
     * (veil, per-scan token, process-scoped probe) lives in [AppContainer] so it
     * survives a configuration change; this method only extracts the id.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val qrId = intent.dataString?.let(::parseQrDropLink) ?: return
        (application as ZitroneApp).container.onLemonDropLink(qrId)
    }

    // A plaintext-bearing Delivered veil must not survive to a later Activity
    // recreation without a fresh biometric unlock. But a CONFIGURATION change
    // (rotation) recreates the Activity within the same authenticated session,
    // and clearing then would destroy the user's one-shot message on a mere
    // rotation. So clear only on a real stop — background, exit, reclaim, or
    // "don't keep activities" — where a later launch would otherwise re-render
    // plaintext unauthenticated (the drop is already burned, so a cleared copy
    // is simply gone, never re-shown).
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            (application as ZitroneApp).container.clearDeliveredLemonDropVeil()
        }
    }

    /**
     * Biometric success on the "unlock to open" veil: fire the delivery side
     * effects (one-time-prekey consumption synchronously, the best-effort
     * relay burn on IO) and swap the veil to the rendered message. This is the
     * ONLY path to [LemonDropVeil.Delivered] — the one veil state that shows
     * plaintext (see LemonDropVeil's security invariant).
     */
    private fun openLemonDrop(pending: PendingLemonDrop) {
        val container = (application as ZitroneApp).container
        // AwaitUnlock is reachable only over a live session (its probe ran on
        // one). If a forced logout tore the session down between that unlock and
        // this per-drop biometric success, there is no redeemer to fire the
        // delivery side effects — leave the drop unburned on the relay for a
        // re-scan rather than render an undeliverable copy.
        val redeemer = container.session.value?.lemonDropRedeemer ?: return
        // Render immediately; the side effects (encrypted-prefs write + network
        // burn) run off the main thread. Prekey consumption goes first: once
        // the message has rendered, the drop must not be openable again even
        // if the burn never lands (single-use by design; the TTL then reaps
        // the undecryptable relay copy). Run on the PROCESS scope, not the
        // Activity's — a rotation or exit right after unlock must not cancel
        // the prekey deletion, which would leave the drop re-openable.
        lemonDropVeil.value =
            LemonDropVeil.Delivered(pending.text, pending.senderLabel, pending.senderVerified)
        container.scope.launch(Dispatchers.IO) {
            redeemer.deliver(pending)
            redeemer.burn(pending)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Launches the biometric gate. Falls open (with no error) only when the
     * device has no secure lock at all — a gate that cannot exist can't be
     * required.
     */
    private fun showBiometricPrompt(onResult: (Boolean, String?) -> Unit) {
        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val prompt = BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) {
                            onResult(true, null)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            onResult(false, errString.toString())
                        }

                        override fun onAuthenticationFailed() {
                            // Keep the prompt open; the user can retry.
                        }
                    },
                )
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.biometric_title))
                    .setSubtitle(getString(R.string.biometric_subtitle))
                    .setAllowedAuthenticators(authenticators)
                    .build()
                prompt.authenticate(promptInfo)
            }
            else -> onResult(true, null)
        }
    }

    /**
     * Authenticate a CryptoObject-bound cipher with a BIOMETRIC_STRONG-only prompt — NO
     * device-credential on this prompt (the app passphrase IS the fallback; biometric-1.1.0
     * CryptoObject+DEVICE_CREDENTIAL has platform caveats). On success the [cipher] is
     * authenticated and ready for one operation; [onSuccess] runs. Any error / cancel →
     * [onError]. A soft failure (a non-matching finger) keeps the prompt open.
     */
    private fun authenticateCrypto(
        cipher: javax.crypto.Cipher,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError()
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open; the user can retry.
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            // A negative button is REQUIRED when only BIOMETRIC_STRONG is allowed.
            .setNegativeButtonText(getString(R.string.biometric_negative))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * The vault biometric-unlock path (§2): recover the auth-gated decrypt cipher for the
     * stored wrap, prompt (BIOMETRIC_STRONG CryptoObject), and on success open the slot with
     * the recovered vault key. A [android.security.keystore.KeyPermanentlyInvalidatedException]
     * (a new enrollment) — or a missing/failed cipher — drops to the passphrase field via
     * [VaultBiometricResult.INVALIDATED] / [VaultBiometricResult.UNAVAILABLE].
     */
    private fun startVaultBiometricUnlock(onResult: (VaultBiometricResult) -> Unit) {
        val container = (application as ZitroneApp).container
        val wrap = container.biometricStore.load() ?: return onResult(VaultBiometricResult.UNAVAILABLE)
        val cipher = try {
            container.biometricCipher.cipherForDecrypt(wrap.nonce)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            return onResult(VaultBiometricResult.INVALIDATED)
        } catch (e: Exception) {
            return onResult(VaultBiometricResult.UNAVAILABLE)
        } ?: return onResult(VaultBiometricResult.UNAVAILABLE)
        authenticateCrypto(
            cipher,
            onSuccess = {
                lifecycleScope.launch {
                    // Contain ANY keystore/store throw from the unwrap+open (IllegalBlockSizeException
                    // on an invalidated-key race, KeyStoreException, ProviderException, a bad-slot
                    // require) — an AEAD failure already returns false. A throw must DROP TO THE
                    // PASSPHRASE FIELD (result FAILED), never crash the coroutine — but a
                    // CancellationException is cooperative teardown and must propagate, not fold.
                    val ok = try {
                        container.unlockWithBiometric(cipher, wrap)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        false
                    }
                    onResult(if (ok) VaultBiometricResult.SUCCESS else VaultBiometricResult.FAILED)
                }
            },
            onError = { onResult(VaultBiometricResult.CANCELLED) },
        )
    }

    /**
     * Biometric-ENABLE over the LIVE session (spec §1) — used by BOTH the onboarding enable offer
     * (shown AFTER the session is published) and the Settings toggle, so no live VaultOpen is ever
     * held across a recomposition. Generate a fresh auth-gated encrypt cipher, prompt
     * (BIOMETRIC_STRONG CryptoObject), and on success wrap a COPY of the running slot's vault key
     * (via [SessionContainer.withVaultKey], wiped in its `finally`) under it. On any error the
     * unused fresh key is deleted. [onResult] reports whether biometric unlock was enabled.
     */
    private fun startBiometricEnableFromSession(onResult: (Boolean) -> Unit) {
        val container = (application as ZitroneApp).container
        if (container.session.value == null) return onResult(false)
        val cipher = try {
            container.biometricCipher.newEncryptCipher()
        } catch (e: Exception) {
            return onResult(false)
        }
        authenticateCrypto(
            cipher,
            onSuccess = {
                val session = container.session.value
                val ok = session != null &&
                    runCatching { container.enableBiometricFromSession(cipher, session) }.getOrDefault(false)
                if (!ok) container.biometricCipher.deleteKey()
                onResult(ok)
            },
            onError = {
                container.biometricCipher.deleteKey()
                onResult(false)
            },
        )
    }
}

/** Outcome of a vault biometric-unlock attempt (see [MainActivity.startVaultBiometricUnlock]). */
private enum class VaultBiometricResult { SUCCESS, FAILED, INVALIDATED, UNAVAILABLE, CANCELLED }

/**
 * Run the account-delete completion's terminal-wipe teardown so the vault is DESTROYED (no
 * remanence) and the unlock gate is ALWAYS released.
 *
 * ORDER IS LOAD-BEARING. [finishUi] runs FIRST: it tears the session down, and that teardown runs
 * `VaultRuntime.close()`'s final SYNCHRONOUS reseal — which rewrites the image on disk WITH the
 * account's full crypto (identity keypair, ratchet records, roster). [destroyVault] runs NEXT and
 * DELETES `vault.bin` + `vault.dek` (+ the biometric wrap/key), so no resealed image survives — the
 * no-remanence guarantee. destroyVault is in a `finally` around finishUi so even a finishUi throw
 * can NEVER skip the load-bearing file deletion; a finishUi CancellationException still propagates
 * (cooperative unwind) but only AFTER destroyVault has run. Any OTHER finishUi throw is TOLERATED so
 * it can't crash the NonCancellable confined worker (no CoroutineExceptionHandler). [releaseGate]
 * (endTerminalWipe) runs in the OUTERMOST `finally` so nothing above can leave unlock blocked
 * forever. Extracted top-level so the ordering + finally guarantees are host-testable.
 */
internal inline fun completeTerminalWipe(
    finishUi: () -> Unit,
    destroyVault: () -> Unit,
    releaseGate: () -> Unit,
) {
    try {
        try {
            try {
                finishUi()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Tolerated — the account is being deleted regardless, and destroyVault (below,
                // in the finally) must still run so no resealed image is left on disk.
            }
        } finally {
            // ALWAYS destroy AFTER finishUi's runtime.close() reseal, even on a finishUi throw:
            // the file deletion is the no-remanence step and must not be skipped.
            destroyVault()
        }
    } finally {
        releaseGate()
    }
}

// ---------------------------------------------------------------------------
// Navigation — hand-rolled single-stack routing, no nav dependency.
// ---------------------------------------------------------------------------

private sealed interface Route {
    data object Splash : Route
    data object Onboarding : Route
    data object Locked : Route
    data object ChatList : Route
    data class Chat(val conversationId: String) : Route
    data object Settings : Route
    data object Diagnostics : Route
    data object AddContact : Route
    data class Verify(val conversationId: String) : Route
}

@Composable
private fun ZitroneRoot(
    container: AppContainer,
    requestBiometric: ((Boolean, String?) -> Unit) -> Unit,
    startVaultBiometricUnlock: ((VaultBiometricResult) -> Unit) -> Unit,
    startBiometricEnable: ((Boolean) -> Unit) -> Unit,
    lemonDropVeil: StateFlow<LemonDropVeil?>,
    onLemonDropDismissed: () -> Unit,
    onLemonDropOpened: (PendingLemonDrop) -> Unit,
) {
    // Device-half flows only — process-lifetime, safe to read pre-unlock. Every
    // session-derived flow moved into [SessionUi], composed only when the session
    // below is non-null. `settings` still drives the vault-scoped UI fields
    // (ttl / burn / lemon-drop compose), which D2c keeps on legacy prefs (D5 moves them).
    val settings by container.settingsRepository.settings.collectAsState()
    val transportState by container.transportResolver.state.collectAsState()
    val lemonDropVeilState by lemonDropVeil.collectAsState()
    // Built on unlock over the vault, null while locked.
    val session by container.session.collectAsState()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // On an Activity recreation (rotation) the process-scoped session survives, but this `remember`
    // re-runs from scratch: a LIVE session must route straight to the chat list, never back through
    // Splash → Locked (which keys on hasVault() and would fake-lock a live, unlocked session and
    // demand a redundant re-auth on every rotation). A genuine cold start (no session) still lands
    // on Splash → setup/unlock. The full ProcessLifecycleOwner auto-lock is still D3; this only
    // stops hiding an already-live session behind a redundant gate.
    var route by remember {
        mutableStateOf<Route>(if (container.session.value != null) Route.ChatList else Route.Splash)
    }
    var unlocked by remember { mutableStateOf(container.session.value != null) }
    var lockError by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }
    // Routing truth (§0): a vault image present → UNLOCK, absent → SETUP. Flips true the
    // instant a create succeeds; otherwise unchanged for the process lifetime.
    var vaultExists by remember { mutableStateOf(container.hasVault()) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    // The biometric-enable OFFER is shown over the LIVE session (post-publish), so it holds NO
    // VaultOpen across recomposition — an Activity recreation drops only the offer (recoverable via
    // Settings), never key material. Set after an onboarding create, and after a passphrase unlock
    // that follows a biometric invalidation (the re-enable the invalidation note promises).
    var offerBiometricEnroll by remember { mutableStateOf(false) }
    var reofferBiometric by remember { mutableStateOf(false) }
    // Real biometric-enabled state (mirrors biometricStore.isEnabled()); updated on enable/disable
    // so the Settings toggle and the lock-screen affordance reflect the TRUE control, not a flag.
    var biometricEnabled by remember { mutableStateOf(container.biometricStore.isEnabled()) }

    // Whether the platform can authenticate BIOMETRIC_STRONG right now — the gate for both
    // OFFERING enable (onboarding / Settings) and the lock-screen biometric affordance.
    val canAuthenticateStrong =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    var identityFingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        val live = session
        if (live != null && identityFingerprint == null) {
            identityFingerprint = withContext(Dispatchers.Default) {
                runCatching {
                    live.signalManager.ensureIdentity()
                    live.signalManager.localFingerprint()
                }.getOrNull()
            }
        }
    }

    // Session lifecycle — tied to the session INSTANCE, not the route, so it runs
    // once per unlock cycle. A fresh unlock builds a new instance over the durable
    // vault image (state reloads exactly as on a process restart).
    session?.let { live ->
        LaunchedEffect(live) { live.coordinator.start() }
        DisposableEffect(live) {
            live.coordinator.onForcedLogout = {
                unlocked = false
                route = Route.Locked
                container.unlockController.lockIf(live)
            }
            onDispose { live.coordinator.onForcedLogout = null }
        }
    }

    // Root detection: warn once per process, never block.
    var rootWarningVisible by remember {
        mutableStateOf(RootDetection.check(context).likelyRooted)
    }

    // Land on the chat list after a successful unlock (passphrase or biometric); clear the
    // RAM backoff so the next lock cycle starts fresh.
    val onUnlockSuccess: () -> Unit = {
        lockError = null
        unlocking = false
        unlocked = true
        route = Route.ChatList
        container.unlockRouter.recordSuccess()
        // A passphrase unlock that follows a biometric invalidation RE-OFFERS enablement over the
        // now-live session — making BIOMETRIC_REENROLL_NOTE's "after a passphrase unlock" promise
        // real, iff the platform can authenticate.
        if (reofferBiometric && canAuthenticateStrong) offerBiometricEnroll = true
        reofferBiometric = false
    }

    // Passphrase unlock (§2): ALWAYS available. Enforce the RAM backoff BEFORE the off-main
    // attempt, then surface only a uniform generic failure (no per-slot / per-factor branch) —
    // EXCEPT a damaged image, which escalates distinctly (it is not a passphrase guess).
    val onUnlockPassphrase: (String) -> Unit = onUnlockPassphrase@{ pass ->
        if (unlocking) return@onUnlockPassphrase
        unlocking = true
        lockError = null
        scope.launch {
            val backoff = container.unlockRouter.backoffDelayMs()
            if (backoff > 0) delay(backoff)
            runCatching { container.unlockWithPassphrase(pass) }.fold(
                onSuccess = { published ->
                    if (published) {
                        onUnlockSuccess()
                    } else {
                        // No match (wrong passphrase) OR a refused build (which already wiped the
                        // VaultOpen). Reporting success would land on a null session, so treat both
                        // as a non-success: uniform failure + backoff.
                        container.unlockRouter.recordFailure()
                        lockError = VaultUnlockRouter.UNIFORM_FAILURE
                        unlocking = false
                    }
                },
                onFailure = { e ->
                    when {
                        e is kotlinx.coroutines.CancellationException -> throw e
                        e is com.zitrone.app.crypto.vault.VaultImageException.CorruptImage ||
                            e is com.zitrone.app.crypto.vault.VaultImageException.MissingImage -> {
                            // A damaged/unreadable IMAGE is device state, NOT a passphrase guess —
                            // surface a distinct honest error, never the wrong-passphrase uniform
                            // failure (no oracle at stake), and do not bump the backoff.
                            lockError = VaultUnlockRouter.IMAGE_UNREADABLE_NOTE
                            unlocking = false
                        }
                        else -> {
                            // Any other throw (a state decode/version failure from the build, a
                            // transient IO error) → uniform failure; never leak the cause.
                            container.unlockRouter.recordFailure()
                            lockError = VaultUnlockRouter.UNIFORM_FAILURE
                            unlocking = false
                        }
                    }
                },
            )
        }
    }

    // Biometric availability for the lock-screen affordance and the veil CTA.
    val biometricUnlockAvailable = vaultExists && biometricEnabled && canAuthenticateStrong

    // Biometric unlock (§2): BIOMETRIC_STRONG CryptoObject only. Invalidation (a new
    // enrollment) drops to the passphrase field with an honest note, clears the dead wrap, and
    // arms the re-enable that the note promises (fired on the next passphrase unlock).
    val onUnlockBiometric: () -> Unit = onUnlockBiometric@{
        if (unlocking) return@onUnlockBiometric
        unlocking = true
        lockError = null
        startVaultBiometricUnlock { result ->
            when (result) {
                VaultBiometricResult.SUCCESS -> onUnlockSuccess()
                VaultBiometricResult.INVALIDATED -> {
                    try {
                        container.disableBiometric()
                        biometricEnabled = false
                        reofferBiometric = true
                        lockError = VaultUnlockRouter.BIOMETRIC_REENROLL_NOTE
                    } finally {
                        // If disableBiometric()/deleteKey() throws (keystore already unhealthy),
                        // unlocking must STILL clear — both unlock paths gate on !unlocking, so a
                        // throwing cleanup would otherwise strand the lock screen until an app restart.
                        unlocking = false
                    }
                }
                VaultBiometricResult.FAILED -> {
                    lockError = VaultUnlockRouter.UNIFORM_FAILURE
                    unlocking = false
                }
                VaultBiometricResult.UNAVAILABLE -> {
                    // The persisted wrap is UNUSABLE (load() null on a malformed blob, or the
                    // auth-gated Keystore key is gone / uninitializable) yet biometricEnabled was
                    // still true — reconcile so the dead biometric button doesn't persist: revoke
                    // the wrap + key and drop to passphrase, arming the re-enable the note promises.
                    try {
                        container.disableBiometric()
                        biometricEnabled = false
                        reofferBiometric = true
                        lockError = VaultUnlockRouter.BIOMETRIC_REENROLL_NOTE
                    } finally {
                        // disableBiometric()/deleteKey() can throw on the very keystore fault that
                        // produced UNAVAILABLE — unlocking must STILL clear in a finally, or both
                        // unlock paths (gated on !unlocking) stay stuck until an app restart.
                        unlocking = false
                    }
                }
                VaultBiometricResult.CANCELLED -> {
                    // User dismissed the prompt — biometric is fine, nothing to reconcile.
                    unlocking = false
                }
            }
        }
    }

    // Settings "Biometric unlock" toggle — the REAL control (spec §1). Enable dual-wraps the live
    // session's vault key (withVaultKey); disable deletes the wrap blob AND the auth-gated Keystore
    // key (a genuine revoke). The reflected state is biometricStore.isEnabled(), never the inert
    // legacy flag.
    val onToggleBiometric: (Boolean) -> Unit = { enable ->
        if (enable) {
            startBiometricEnable { biometricEnabled = container.biometricStore.isEnabled() }
        } else {
            container.disableBiometric()
            biometricEnabled = false
        }
    }

    // Create the vault (§1): off-main. create+publish happen atomically INSIDE the container call
    // (so a mid-work cancellation cannot strand the fresh VaultOpen — it is consumed-or-wiped before
    // the off-main block returns, and the session lives on the process scope), then land on the chat
    // list and, over the now-LIVE session, offer biometric enable if the platform can. After a
    // create throw the image may have LANDED (a NotDurable whose vault.bin rename was unconfirmed):
    // re-derive hasVault() and route to unlock — never blindly re-call create() (which would throw
    // "already exists" and error-loop). Creation never bricks.
    val onCreateVault: (String) -> Unit = onCreateVault@{ pass ->
        if (creating) return@onCreateVault
        creating = true
        createError = null
        scope.launch {
            val result = runCatching { container.createVaultAndPublish(pass) }
            creating = false
            result.fold(
                onSuccess = { published ->
                    vaultExists = true
                    if (published) {
                        onUnlockSuccess()
                        if (canAuthenticateStrong) offerBiometricEnroll = true
                    } else {
                        // A refused build (a session already live) — route to the lock gate.
                        route = Route.Locked
                    }
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (container.hasVault()) {
                        // Complete-but-unconfirmed vault already on disk — it opens normally with
                        // the passphrase just entered, so route to unlock (no error-loop).
                        vaultExists = true
                        route = Route.Locked
                        createError = null
                    } else {
                        createError = "Couldn't finish creating your vault. Please try again."
                    }
                },
            )
        }
    }

    // Root detection is warn-once. Account delete DESTROYS the vault (no remanence): after the
    // server-delete + burnAll, tear the session down (finishUi → runtime.close reseal) and then
    // DELETE the on-disk image + biometric via container.destroyVaultForAccountDeletion(), so no
    // resealed image survives — do NOT rely on signalStore.wipe()/reseal (which keeps the crypto
    // on disk). hasVault() is then false, so route to Onboarding (fresh-install state), never
    // Splash→Locked.
    val onDeleteAccount: () -> Unit = onDeleteAccount@{
        val live = session ?: return@onDeleteAccount
        container.unlockController.beginTerminalWipe()
        live.coordinator.deleteAccountAndWipe {
            completeTerminalWipe(
                finishUi = {
                    // Zero the live crypto state BEFORE teardown so that if the session is dirty,
                    // runtime.close()'s final reseal writes a ZEROED image, not a full-crypto one.
                    // destroyVault (below) deletes the file regardless, but this shrinks the
                    // post-reseal/pre-unlink crash window from "full account recoverable by
                    // passphrase" to "zeroed image" — the device-seizure threat this app targets.
                    // Tolerated: a runtime already closed by a racing revocation throws here; the
                    // file deletion still covers that case.
                    runCatching { live.signalStore.wipe() }
                    identityFingerprint = null
                    unlocked = false
                    vaultExists = false
                    route = Route.Onboarding
                    // Synchronous session teardown: runtime.close() reseals the image one last
                    // time. destroyVault (below) then deletes it — ordering is load-bearing.
                    container.unlockController.lockIf(live)
                },
                // The load-bearing no-remanence step: delete vault.bin/vault.dek + biometric wrap/key
                // (each tolerant of its own throw). Runs AFTER the reseal, in completeTerminalWipe's
                // finally, so it can never be skipped.
                destroyVault = { container.destroyVaultForAccountDeletion() },
                releaseGate = { container.unlockController.endTerminalWipe() },
            )
        }
    }

    // Biometric-enable offer (§1) — over the LIVE session (holds NO VaultOpen, so an Activity
    // recreation drops only the offer, never key material). Shown after an onboarding create, or
    // after a passphrase unlock that followed a biometric invalidation. Enable dual-wraps the live
    // session's vault key (withVaultKey); skipping proceeds passphrase-only. Short-circuits routing.
    if (offerBiometricEnroll && session != null) {
        BiometricEnrollOffer(
            onEnable = {
                startBiometricEnable {
                    biometricEnabled = container.biometricStore.isEnabled()
                    offerBiometricEnroll = false
                }
            },
            onSkip = { offerBiometricEnroll = false },
        )
        return
    }

    // A Locked veil on a not-yet-created-vault install does NOT compose — normal routing
    // (Splash → Onboarding) runs instead, scan still queued; the first unlock drains it.
    val veilLockedPreOnboarding =
        lemonDropVeilState is LemonDropVeil.Locked && !vaultExists

    // The Locked-veil CTA routes into the SAME unlock router: biometric one-tap when
    // available, otherwise reveal the lock screen (passphrase). No silent auto-unlock, no
    // fail-open (D2b's gate-off branches are removed outright, §0/§2).
    val unlockFromVeil: () -> Unit = {
        when {
            !vaultExists -> Unit // Locked veil is not composed pre-vault
            biometricUnlockAvailable -> onUnlockBiometric()
            else -> {
                // Reveal the passphrase lock screen while KEEPING the queued scan (D2b invariant:
                // "the scan stays queued; the first unlock drains it" via onSessionPublished /
                // onUnlocked) — do NOT dismiss it, which would drop the scanned /d/ drop.
                container.revealLockScreenKeepingLemonDropScan()
                route = Route.Locked
            }
        }
    }

    lemonDropVeilState?.takeUnless { veilLockedPreOnboarding }?.let { veil ->
        BackHandler(enabled = true) { onLemonDropDismissed() }
        when (veil) {
            LemonDropVeil.Locked ->
                LemonDropUnlockScreen(
                    onUnlock = unlockFromVeil,
                    onDismiss = onLemonDropDismissed,
                    identityFingerprint = identityFingerprint,
                )
            is LemonDropVeil.Advocacy ->
                LemonDropAdvocacyScreen(outcome = veil.outcome, onDismiss = onLemonDropDismissed)
            is LemonDropVeil.AwaitUnlock ->
                LemonDropUnlockScreen(
                    onUnlock = {
                        requestBiometric { success, _ ->
                            if (success) onLemonDropOpened(veil.pending)
                        }
                    },
                    onDismiss = onLemonDropDismissed,
                    identityFingerprint = identityFingerprint,
                )
            is LemonDropVeil.Delivered ->
                LemonDropDeliveredScreen(
                    veil = veil,
                    onDismiss = onLemonDropDismissed,
                    identityFingerprint = identityFingerprint,
                )
        }
        return
    }

    BackHandler(enabled = route !is Route.ChatList && unlocked) {
        route = when (val current = route) {
            is Route.Verify -> Route.Chat(current.conversationId)
            is Route.Diagnostics -> Route.Settings
            else -> Route.ChatList
        }
    }

    Crossfade(
        targetState = route,
        animationSpec = tween(Motion.DurationBaseMs, easing = Motion.EasingDefault),
        label = "rootNavigation",
    ) { current ->
        when (current) {
            // Vault-only routing (§0): image present → unlock gate, absent → setup. NO
            // silent auto-unlock.
            Route.Splash -> SplashScreen(
                onFinished = {
                    route = if (vaultExists) Route.Locked else Route.Onboarding
                },
            )

            Route.Onboarding -> OnboardingScreen(
                onCreateVault = onCreateVault,
                creating = creating,
                createError = createError,
            )

            // Vault unlock gate: passphrase always, biometric iff enabled + available. No
            // auto-prompt — the user types a passphrase or taps biometrics.
            Route.Locked -> LockScreen(
                onUnlockWithPassphrase = onUnlockPassphrase,
                onBiometricUnlock = if (biometricUnlockAvailable) onUnlockBiometric else null,
                errorMessage = lockError,
                unlocking = unlocking,
            )

            // Session routes. `route` becomes one of these only after publishSession ran
            // synchronously, so the session is live here.
            else -> session?.let { live ->
                SessionUi(
                    session = live,
                    container = container,
                    route = current,
                    settings = settings,
                    transportState = transportState,
                    identityFingerprint = identityFingerprint,
                    rootWarningVisible = rootWarningVisible,
                    onDismissRootWarning = { rootWarningVisible = false },
                    onNavigate = { route = it },
                    onDeleteAccount = onDeleteAccount,
                    biometricEnabled = biometricEnabled,
                    biometricAvailable = canAuthenticateStrong,
                    onToggleBiometric = onToggleBiometric,
                )
            }
        }
    }
}

/**
 * The skippable biometric-enable offer shown once, right after a fresh vault is created
 * (§1). Enabling dual-wraps the vault key under the auth-gated biometric key so later
 * launches can unlock with a single BIOMETRIC_STRONG tap; the passphrase always remains the
 * fallback. Skipping proceeds passphrase-only.
 */
@Composable
private fun BiometricEnrollOffer(
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Enable biometric unlock?",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Unlock with a fingerprint or face instead of typing your passphrase each " +
                "time. Your passphrase still works, and stays the only way back in if biometrics change.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        Button(
            onClick = onEnable,
            colors = ButtonDefaults.buttonColors(containerColor = Lemon, contentColor = TextOnLemon),
        ) { Text("Enable biometrics") }
        TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) {
            Text("Not now", color = TextSecondary)
        }
    }
}

/**
 * The session-scoped UI subtree — composed ONLY while a session is live (D2b).
 * Every session-derived flow is collected here (never at the root, where it would
 * read a null session pre-unlock), and every session member is reached through
 * the non-null [session] passed in — the delegating getters on [AppContainer] are
 * gone. Renders the single session [route] handed down by the root's Crossfade;
 * device-owned dependencies (settings, transport, boot diagnostics, the lemon-drop
 * entry point) still come off [container].
 */
@Composable
private fun SessionUi(
    session: SessionContainer,
    container: AppContainer,
    route: Route,
    settings: SettingsRepository.Settings,
    transportState: TransportState,
    identityFingerprint: String?,
    rootWarningVisible: Boolean,
    onDismissRootWarning: () -> Unit,
    onNavigate: (Route) -> Unit,
    onDeleteAccount: () -> Unit,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val conversations by session.conversationRepository.conversations.collectAsState()
    val allMessages by session.messageRepository.messages.collectAsState()
    val typingPeers by session.coordinator.typingPeers.collectAsState()
    val connectivity by session.coordinator.connectivity.collectAsState()
    val accountId by session.apiClient.accountIdFlow.collectAsState()

    when (route) {
        Route.ChatList -> ChatListScreen(
            conversations = conversations,
            rootWarningVisible = rootWarningVisible,
            onDismissRootWarning = onDismissRootWarning,
            onOpenConversation = { onNavigate(Route.Chat(it.id)) },
            onDeleteContact = { conversation ->
                session.coordinator.deleteContact(conversation.id)
            },
            onOpenSettings = { onNavigate(Route.Settings) },
            onNewChat = { onNavigate(Route.AddContact) },
            // Same resolve path as App Links / VIEW intents — do not fork.
            onOpenLemonDrop = { qrId -> container.onLemonDropLink(qrId) },
            identityFingerprint = identityFingerprint,
        )

        is Route.Chat -> {
            val conversation = conversations.firstOrNull { it.id == route.conversationId }
            if (conversation == null) {
                // Conversation burned away beneath us.
                LaunchedEffect(route) { onNavigate(Route.ChatList) }
            } else {
                LaunchedEffect(conversation.id) {
                    session.conversationRepository.markConversationRead(conversation.id)
                    // Reset this conversation's notification re-fire cycle so
                    // the next message alerts immediately (and no phantom
                    // re-fire lands for a chat now on screen).
                    session.coordinator.onConversationRead(conversation.id)
                }
                ChatScreen(
                    conversation = conversation,
                    messages = allMessages[conversation.id].orEmpty(),
                    peerTyping = conversation.contactId in typingPeers,
                    defaultTtlSeconds = settings.defaultTtlSeconds,
                    defaultBurnOnRead = settings.burnOnReadDefault,
                    ttlOptions = container.settingsRepository.ttlOptionsSeconds,
                    onBack = { onNavigate(Route.ChatList) },
                    onVerifyKeys = { onNavigate(Route.Verify(conversation.id)) },
                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
                    onRename = { newName ->
                        session.conversationRepository.setDisplayName(
                            conversation.id,
                            newName,
                        ) != null
                    },
                    onSend = { text, ttl, burn ->
                        session.coordinator.sendText(conversation, text, ttl, burn)
                    },
                    onSendAttachment = { bytes, kind, mimetype, filename, caption, ttl, burn ->
                        session.coordinator.sendAttachment(
                            conversation = conversation,
                            bytes = bytes,
                            kind = kind,
                            mimetype = mimetype,
                            filename = filename,
                            caption = caption,
                            ttlSeconds = ttl,
                            burnOnRead = burn,
                        )
                    },
                    // Through the coordinator (not the repository directly):
                    // seen messages arm burn-on-read timers AND, when
                    // enabled, send the encrypted read receipt.
                    onMessagesSeen = { seenIds ->
                        session.coordinator.onMessagesSeen(conversation, seenIds)
                    },
                    onTyping = { started ->
                        session.coordinator.sendTyping(conversation, started)
                    },
                    onRetry = { messageId ->
                        session.coordinator.retry(messageId)
                    },
                    onRevealImage = { messageId ->
                        session.coordinator.revealAttachment(messageId)
                    },
                    identityFingerprint = identityFingerprint,
                    // Seal the draft into a lemon drop for this contact — the
                    // one-shot creator (never touches the persistent session).
                    // P3-1 (review): offer the droplet ONLY when we already hold
                    // an identity key for this contact — pinned out of band, else
                    // the TOFU key learned on first contact. A one-shot drop gets
                    // NO later safety-number check, so it must seal only to an
                    // identity we ALREADY trust; a keyless contact-by-UUID must
                    // not even be offered the button. Null hides the droplet
                    // entirely (LemonDropCreator refuses keyless as a backstop,
                    // but the UI must not offer what it would refuse).
                    // Settings → Privacy "Lemon-drop compose button" (default OFF)
                    // plus a trusted identity key. Null hides the droplet.
                    onSendAsQrDrop = if (
                        settings.lemonDropComposeEnabled &&
                            (conversation.pinnedIdentityKeyBase64
                                ?: conversation.contactIdentityKeyBase64) != null
                    ) {
                        { text, ttlHours ->
                            session.lemonDropCreator.create(conversation, text, ttlHours)
                        }
                    } else {
                        null
                    },
                )
            }
        }

        Route.Settings -> {
            // Re-check Orbot on every resume: the user may install it via
            // the "Get Orbot" action and return to this still-live screen.
            // Deliberately NOT lifecycle-compose's LifecycleResumeEffect:
            // on Compose 1.6.x it resolves its LifecycleOwner by reflection,
            // and R8 strips the reflection target in minified release
            // builds — composing it crashed every Settings open in v1.5.1.
            // compose-ui's LocalLifecycleOwner is provided directly by
            // setContent, no reflection involved.
            var torAvailable by remember {
                mutableStateOf(TorIntegration.isOrbotInstalled(context))
            }
            // Same re-check for the I2P router apps: the user may install the
            // official I2P app (or i2pd) via the actions below and return here.
            var officialRouterInstalled by remember {
                mutableStateOf(I2pIntegration.isOfficialRouterInstalled(context))
            }
            var i2pdInstalled by remember {
                mutableStateOf(I2pIntegration.isI2pdInstalled(context))
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, context) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        torAvailable = TorIntegration.isOrbotInstalled(context)
                        officialRouterInstalled = I2pIntegration.isOfficialRouterInstalled(context)
                        i2pdInstalled = I2pIntegration.isI2pdInstalled(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            SettingsScreen(
                settingsRepository = container.settingsRepository,
                accountId = accountId,
                // Hoisted to the root; "" until it lands, exactly as the old
                // local default behaved.
                identityFingerprint = identityFingerprint ?: "",
                connectivity = connectivity,
                transportState = transportState,
                torAvailable = torAvailable,
                officialRouterInstalled = officialRouterInstalled,
                i2pdInstalled = i2pdInstalled,
                biometricEnabled = biometricEnabled,
                biometricAvailable = biometricAvailable,
                onToggleBiometric = onToggleBiometric,
                onBack = { onNavigate(Route.ChatList) },
                onDeleteAccount = onDeleteAccount,
                onOpenDiagnostics = { onNavigate(Route.Diagnostics) },
            )
        }

        Route.Diagnostics -> DiagnosticsScreen(
            diagnostics = container.bootDiagnostics,
            onBack = { onNavigate(Route.Settings) },
        )

        Route.AddContact -> {
            // Build our own shareable code from the registered identity.
            // Null until first-run registration lands; keyed on the
            // observable accountId so it appears the instant register()
            // completes. Off the main thread — it does keystore + signing.
            var myPayload by remember(accountId) { mutableStateOf<String?>(null) }
            LaunchedEffect(accountId) {
                myPayload = withContext(Dispatchers.Default) {
                    accountId?.let { acct ->
                        runCatching {
                            session.signalManager.ensureIdentity()
                            buildContactExchangePayload(
                                accountId = acct,
                                identityKeyBase64 = session.signalManager.localIdentityPublicKeyBase64(),
                            )
                        }.getOrNull()
                    }
                }
            }
            AddContactScreen(
                myContactPayload = myPayload,
                myAccountId = accountId,
                onBack = { onNavigate(Route.ChatList) },
                onAdd = { contactId, identityKeyBase64, displayName ->
                    // Never establish a Double Ratchet session with our own
                    // identity — libsignal treats that as undefined and it
                    // can corrupt the session store. AddContactScreen already
                    // blocks it in the UI; this is the defensive backstop.
                    if (!contactId.equals(accountId, ignoreCase = true)) {
                        val conversation = Conversation(
                            id = contactId,
                            contactId = contactId,
                            displayName = displayName,
                            // Seed the known key so Verify shows the right
                            // safety number before the first message, and
                            // pin it so a substituted relay bundle is caught.
                            contactIdentityKeyBase64 = identityKeyBase64,
                            pinnedIdentityKeyBase64 = identityKeyBase64,
                            lastActivityMs = System.currentTimeMillis(),
                        )
                        session.conversationRepository.upsert(conversation)
                        onNavigate(Route.Chat(conversation.id))
                    }
                },
            )
        }

        is Route.Verify -> {
            val conversation = conversations.firstOrNull { it.id == route.conversationId }
            if (conversation == null) {
                LaunchedEffect(route) { onNavigate(Route.ChatList) }
            } else {
                val safetyNumber = remember(conversation.contactIdentityKeyBase64) {
                    runCatching {
                        val contactKey = conversation.contactIdentityKeyBase64
                        if (contactKey != null) {
                            session.signalManager.safetyNumberWith(contactKey)
                        } else {
                            // No key exchanged yet — show our own
                            // fingerprint so verification can still start
                            // from the other side.
                            session.signalManager.localFingerprint()
                        }
                    }.getOrDefault("")
                }
                KeyVerificationScreen(
                    conversation = conversation,
                    safetyNumber = safetyNumber,
                    onBack = { onNavigate(Route.Chat(conversation.id)) },
                    onMarkVerified = {
                        session.conversationRepository.setVerified(conversation.id, true)
                    },
                )
            }
        }

        // Pre-session routes never reach [SessionUi] — the root composes them
        // directly, without a session.
        Route.Splash, Route.Onboarding, Route.Locked -> Unit
    }
}
