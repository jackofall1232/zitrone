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
import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
import com.zitrone.app.data.Conversation
import com.zitrone.app.data.LemonDropRedeemer
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
import com.zitrone.app.ui.screens.DeleteIncompleteScreen
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    override fun onStart() {
        super.onStart()
        (application as ZitroneApp).container.activityStarted = true
    }

    override fun onStop() {
        super.onStop()
        (application as ZitroneApp).container.activityStarted = false
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
        // RENDER-GATED CONSUME (round 13, Grok P2-1). The biometric for THIS drop already passed,
        // so we RENDER first (gated on the Activity still being STARTED and the veil still being
        // this drop's own AwaitUnlock), and consume the one-time prekey ONLY after a successful
        // render. This closes the permanent-loss window of the old commit-before-render order: if
        // the user backgrounds before render (activityStarted false) or a second /d link steals
        // the veil, NOTHING is consumed and the drop stays fully re-scannable — the prekey is not
        // durably burned out from under an unshown message. The round-12 "no plaintext behind a
        // stopped Activity" property is preserved: the started-check and onStop's Delivered-clear
        // both run on Main and are serialized, and the CAS targets this drop's own AwaitUnlock so
        // a stolen veil (drop B) is never overwritten.
        //
        // Residual (documented, strictly milder than the old loss): if the process dies AFTER
        // render but BEFORE the consume's durable flush lands, the prekey may survive and the drop
        // is re-openable (a bounded DOUBLE-OPEN of an already-seen message, each behind a fresh
        // biometric) — never a permanent loss of an unread message.
        //
        // Run on the PROCESS scope with NO Activity captures (rounds 11-12): the veil + started
        // flag are container state, so a rotation neither leaks the Activity nor cancels the flow.
        val veil = container.lemonDropVeil
        val expectedVeil: LemonDropVeil = LemonDropVeil.AwaitUnlock(pending)
        container.scope.launch(Dispatchers.IO) {
            // 1. RENDER decision on Main: only if the Activity is started AND this drop still owns
            //    the veil. No consume yet — a refused render consumes nothing (drop re-scannable).
            val rendered = withContext(Dispatchers.Main) {
                container.activityStarted && veil.compareAndSet(
                    expectedVeil,
                    LemonDropVeil.Delivered(pending.text, pending.senderLabel, pending.senderVerified),
                )
            }
            if (!rendered) return@launch
            // 2. Shown → NOW consume the one-time prekey durably; on a confirmed-durable commit,
            //    burn the relay copy. A NOT_APPLIED (closed runtime) or APPLIED_UNCONFIRMED commit
            //    leaves the bounded double-open residual above, never a loss (the user has seen it).
            val commit = try {
                redeemer.deliverDurablyCommit(pending)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Throwable) {
                LemonDropRedeemer.DeliveryCommit.NOT_APPLIED
            }
            if (commit == LemonDropRedeemer.DeliveryCommit.DURABLE) redeemer.burn(pending)
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
     * CryptoObject+DEVICE_CREDENTIAL has platform caveats). On success [onSuccess] receives the
     * AUTHENTICATED cipher from the [BiometricPrompt.AuthenticationResult] — NOT the instance
     * passed in: on some OEM/API combinations only the result's cipher is marked authorized, and
     * using the original throws IllegalBlockSize/BadPadding at `doFinal` (Gemini round 4). A
     * result with no cipher is an error. Any error / cancel → [onError]. A soft failure (a
     * non-matching finger) keeps the prompt open.
     */
    private fun authenticateCrypto(
        cipher: javax.crypto.Cipher,
        onSuccess: (javax.crypto.Cipher) -> Unit,
        onError: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated != null) onSuccess(authenticated) else onError()
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
        // Keystore work (blob load + cipher init) off the main thread (round 11, Codex): a slow
        // or busy TEE/StrongBox can stall these binder calls long enough to jank or ANR. Only
        // the BiometricPrompt launch returns to main.
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val wrap = container.biometricStore.load()
                    ?: return@withContext null to VaultBiometricResult.UNAVAILABLE
                try {
                    val cipher = container.biometricCipher.cipherForDecrypt(wrap.aliasId, wrap.nonce)
                        ?: return@withContext null to VaultBiometricResult.UNAVAILABLE
                    (cipher to wrap) to VaultBiometricResult.SUCCESS
                } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                    null to VaultBiometricResult.INVALIDATED
                } catch (e: Exception) {
                    null to VaultBiometricResult.UNAVAILABLE
                }
            }
            val (cipherAndWrap, failure) = prepared
            if (cipherAndWrap == null) {
                onResult(failure)
                return@launch
            }
            val (cipher, wrap) = cipherAndWrap
            startVaultBiometricPrompt(container, cipher, wrap, onResult)
        }
    }

    private fun startVaultBiometricPrompt(
        container: AppContainer,
        cipher: javax.crypto.Cipher,
        wrap: com.zitrone.app.crypto.vault.BiometricWrappedKey,
        onResult: (VaultBiometricResult) -> Unit,
    ) {
        authenticateCrypto(
            cipher,
            onSuccess = { authenticatedCipher ->
                lifecycleScope.launch {
                    // Contain ANY keystore/store throw from the unwrap+open (IllegalBlockSizeException
                    // on an invalidated-key race, KeyStoreException, ProviderException, a bad-slot
                    // require) — an AEAD failure already returns false. A throw must DROP TO THE
                    // PASSPHRASE FIELD (result FAILED), never crash the coroutine — but a
                    // CancellationException is cooperative teardown and must propagate, not fold.
                    val ok = try {
                        container.unlockWithBiometric(authenticatedCipher, wrap)
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
        // Enable is valid ONLY when NO wrap exists yet. This gate is GLOBAL (isEnabled() is the same in
        // an A- and a B-session), so it is NOT a slot oracle and keeps the never-repoint-while-exists
        // property (a second enable can't start while a wrap lives). A stale/desynced UI that reaches
        // here self-resyncs via the result callback (which re-reads isEnabled()). enableBiometricFromSession
        // keeps the per-slot never-repoint belt for a session that changed mid-flight. NOTE (0.9.2
        // enable-atomicity): newEncryptCipher is now NON-destructive — each enable creates its own unique
        // alias and deletes nothing else — so even a concurrent/interrupted enable can never orphan a wrap
        // or destroy an existing binding (INV-1); the isEnabled() gate is now about UX/never-repoint, not
        // about protecting a shared alias from destruction.
        if (container.biometricStore.isEnabled()) return onResult(false)
        // 0.9.2 enable-atomicity: each enable gets its OWN unique alias, so newEncryptCipher(aliasId)
        // creates that key WITHOUT deleting any other — a concurrent/interrupted enable can no longer
        // orphan a wrap or destroy an existing binding. Keystore keygen runs off the main thread (round
        // 11, Codex): a slow TEE/StrongBox can jank/ANR these binder calls. Only the prompt returns to main.
        val aliasId = BiometricVaultKeyCipher.newAliasId()
        lifecycleScope.launch {
            val cipher = try {
                withContext(Dispatchers.IO) { container.biometricCipher.newEncryptCipher(aliasId) }
            } catch (e: Exception) {
                onResult(false)
                return@launch
            }
            startBiometricEnablePrompt(container, cipher, aliasId, onResult)
        }
    }

    private fun startBiometricEnablePrompt(
        container: AppContainer,
        cipher: javax.crypto.Cipher,
        aliasId: String,
        onResult: (Boolean) -> Unit,
    ) {
        authenticateCrypto(
            cipher,
            onSuccess = { authenticatedCipher ->
                val session = container.session.value
                val ok = session != null &&
                    runCatching { container.enableBiometricFromSession(authenticatedCipher, session, aliasId) }.getOrDefault(false)
                // On failure/refusal, delete ONLY this enable's own alias (never a live binding's).
                if (!ok) container.biometricCipher.deleteKey(aliasId)
                onResult(ok)
            },
            onError = {
                container.biometricCipher.deleteKey(aliasId)
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

    /**
     * Account deletion confirmed the SERVER delete but the local vault unlink did not verify
     * ([VaultImageException.DestroyFailed] / the boot-time destroy-pending marker). The only exit
     * is a CONFIRMED destroy → Onboarding. Never the lock gate: a partially-unlinked image no
     * longer opens (a permanent dead-end for the correct passphrase), and a zeroed one would
     * unlock empty and silently auto-register a brand-new account.
     */
    data object DeleteIncomplete : Route
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
    // NO DISK READ ON THE COMPOSITION THREAD (0.9.2 Unit W-B, item #5). This was
    // `mutableStateOf(container.hasVault())` — a stat under `imageLock` in a `remember` initializer,
    // i.e. on the Main thread, every first composition.
    //
    // `false` is not a guess about disk: it is the PRE-RECONCILIATION value, and nothing may route
    // off this until the boot derivation publishes. The Splash gate below is what makes that true —
    // the route stays `Route.Splash` until BOTH the animation ends and `bootReconciled` is set, and
    // the derivation assigns this field before leaving Splash. A composition that read this during
    // Splash would be reading pre-reconciliation state, which the sweep's whole design forbids.
    // CORRECTED (round 3, Codex — adjudicated against source, Grok read it the other way). The
    // previous line here asked a reviewer to "verify no consumer observes this before the Splash
    // effect assigns it", and the answer is that consumers DO observe it: `biometricUnlockAvailable`
    // (~line 1026) and the lemon-drop veil derivation (~line 1349) read it immediately. The claim
    // that survives is narrower and is the one that matters: no consumer ROUTES on it, and both
    // readers are safe when false (hide the biometric affordance; treat as pre-vault). What is NOT
    // yet handled, tracked rather than papered over: on an Activity recreation with a LIVE session,
    // the Splash effect never runs and the boot effect skips derivation, so this stays false until
    // some later transition re-derives — a UI-state misclassification, not a fresh-install-over-
    // residue path.
    var vaultExists by remember { mutableStateOf(false) }

    // ── COLD-START BOOT ROUTING (0.9.2 Unit W-A) ────────────────────────────────────────────────
    // The orphan sweep is a DESTRUCTIVE boot operation: it unlinks residue before any authentication.
    // Nothing may derive a route from disk until it has finished and published its verdict, and the
    // verdict must be CARRIED to the decision rather than re-derived from a fresh stat there — a stat
    // reports absence the instant a file is unlinked, whether or not that survives a crash.
    var splashFinished by remember { mutableStateOf(false) }
    val bootDone by container.bootReconciled.collectAsState()

    // Whichever of {animation ended, boot published} lands second triggers the decision, so there is
    // no window in which Splash can route off pre-reconciliation state.
    LaunchedEffect(splashFinished, bootDone) {
        if (!splashFinished || !bootDone) return@LaunchedEffect
        if (route != Route.Splash) return@LaunchedEffect
        val decided = container.deriveBootDecisionFromDisk()
        // RE-CHECK AFTER THE SUSPEND: the guard above ran before `withContext`, and a decision taken
        // for a tree that has since left Splash must not be applied to it.
        if (route != Route.Splash) return@LaunchedEffect
        vaultExists = decided.present && !decided.legacy
        route = when (decided.route) {
            BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
            BootRoute.ONBOARDING -> Route.Onboarding
            BootRoute.LOCKED -> Route.Locked
        }
    }

    LaunchedEffect(Unit) {
        // Started on the PROCESS scope, never owned by this composition: a rotation that cancelled
        // the claiming coroutine after it won the CAS but before it published would leave every later
        // composition waiting forever. Idempotent — later calls no-op.
        container.startBootReconcile()
        // Every composition — including one created after boot already finished — re-derives once the
        // process-scoped result is available.
        container.bootReconciled.first { it }
        if (container.session.value == null) {
            val snap = container.deriveBootDecisionFromDisk()
            // RE-CHECK AFTER THE SUSPEND (round-1 review, Kimi). The session was checked before
            // `withContext`; a session published while we were off-main must not then be pulled to
            // DeleteIncomplete by a decision taken for a tree that no longer has none. The Splash
            // consumer already re-checks; this one did not — the asymmetry was the finding.
            if (container.session.value != null) return@LaunchedEffect
            vaultExists = snap.present && !snap.legacy
            when (snap.route) {
                BootRoute.DELETE_INCOMPLETE ->
                    if (route != Route.DeleteIncomplete) route = Route.DeleteIncomplete
                // Only ever moves a STALE Locked forward; never pulls a live tree back.
                BootRoute.ONBOARDING -> if (route == Route.Locked) route = Route.Onboarding
                BootRoute.LOCKED -> Unit
            }
        }
    }
    // OBSERVED from the container's process-scoped flow (round 11, Gemini): a rotation
    // mid-create re-attaches the spinner to the still-running create, and a create that fails
    // after the rotation releases it here too (a seeded snapshot would strand the spinner).
    val creating by container.vaultCreating.collectAsState()
    var createError by remember { mutableStateOf<String?>(null) }
    // Route.DeleteIncomplete retry plumbing: single-flight destroy retry off the main thread.
    // Success is judged by disk truth (files gone + marker retired), same as the delete handler.
    var deleteRetrying by remember { mutableStateOf(false) }
    var deleteRetryFailed by remember { mutableStateOf(false) }
    val onRetryDestroy: () -> Unit = retry@{
        if (deleteRetrying) return@retry
        deleteRetrying = true
        deleteRetryFailed = false
        scope.launch {
            // ONE ROUTING AUTHORITY — the LAST sibling (round-4 review, Grok INFO-2). This judged
            // success with `!hasVault() && !serverDeleteConfirmed()` while the other four consumers
            // went through the single derivation, making it a second authority on the same question.
            // It is the structural family this unit exists to close, and leaving one site on the
            // weaker signal is how the family regrows.
            //
            // The criterion is STRONGER ON ABSENCE PROOF, deliberately: `hasVault()` keys on
            // `vault.bin` alone, so a retry that left a stray DEK or temp behind reported SUCCESS and
            // routed to onboarding over recoverable residue — the exact hazard W-A exists to close,
            // still open on this one path. ONBOARDING now additionally requires `vaultProvenAbsent`
            // (`Files.notExists` over all four image-bearing files) and respects the sweep hold.
            // NOT a formal strengthening over every input: `bootRoute`'s legacy arm routes a present
            // LEGACY image to ONBOARDING, where `hasVault()` reported failure. That arm is the
            // reviewed behaviour (a legacy image is unusable and onboarding's `create()` retires it),
            // and it is not a post-destroy product — but the old blanket "strictly stronger" was
            // wrong as stated (follow-up review, Grok).
            //
            // A destroy that leaves residue therefore reports FAILURE here. Destroy is idempotent, so
            // retrying is SAFE and a TRANSIENT fault may clear on the next attempt — but idempotence
            // proves only that the retry is safe, never that it succeeds. A PERSISTENT unlink or stat
            // fault keeps every retry on `Route.DeleteIncomplete`, with no in-app exit. Tracked
            // follow-up (todos.md): the remedy is a product/support answer, not a routing one.
            //
            // Net effect, stated honestly (follow-up review, Codex): this adds ONE pathological state
            // to a stuck class that ALREADY exists — a visible confirmed marker, or a surviving
            // `vault.bin`, already stays on DeleteIncomplete — while removing an UNSAFE onboarding
            // over recoverable residue. The row that changes is the indeterminate-stat one, and
            // routing it fail-closed instead of to Onboarding over an image that cannot be PROVEN
            // absent IS the W-A hazard being fixed, not a regression.
            //
            // No hold supersede here, unlike the delete-completion callback: adding one would mean
            // two more BARE `imageLock` calls on the Main dispatcher — the very shape 0.9.3 is
            // folding INTO the derivation. Do not add it here; fix it there, once, for every
            // consumer. This comment used to justify the omission with "a held boot admits no
            // session — so hold and this path cannot coexist". THAT IS FALSE (follow-up review,
            // Grok): a hold raised while an image is PRESENT routes to LOCKED via the image arm, and
            // a lock screen admits an unlock, hence a session, hence an in-session delete. Reachable
            // only through the fail-closed default (a cancelled boot, or a throw escaping the sweep
            // before gate 1) — remote, since the sweep's own gates return NO_MUTATION over a present
            // image — and the consequence is bounded and restart-recoverable: a successful retry over
            // a clean disk is reported as FAILURE for the rest of the process. Precisely (follow-up
            // review, Grok): the stale hold makes the DERIVED route LOCKED, so the success check
            // below fails and the UI stays on `Route.DeleteIncomplete` — `route` is never rewritten
            // to Locked. Tracked with the 0.9.3 fold, not fixed here.
            //
            // The orchestration lives in `runDeleteRetry` so the WIRING is testable — destroy before
            // derive, derived route only, ONBOARDING-only success, no hold supersede. The truth-table
            // tests over `bootRoute` cannot catch this call site reverting to the weaker predicate;
            // `DeleteRetryOwnerTest` can, and does.
            val succeeded = runDeleteRetry(
                destroy = {
                    withContext(Dispatchers.IO) {
                        runCatching { container.destroyVaultForAccountDeletion() }
                    }
                },
                derive = { container.deriveBootDecisionFromDisk() },
            )
            deleteRetrying = false
            if (succeeded) {
                vaultExists = false
                route = Route.Onboarding
            } else {
                deleteRetryFailed = true
            }
        }
    }
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

    // (The standalone legacy-image routing effect that used to live here is REMOVED. It was a SECOND
    // routing authority: it set Route.Onboarding on its own, without awaiting `bootReconciled`,
    // without the carried `durabilityHold`, and without consulting `serverDeleteConfirmed()` — so
    // with a v2 image over a durable `vault.delete-confirmed` it could preempt Route.DeleteIncomplete,
    // and the create() on that onboarding screen CLEARS both markers, erasing the SOLE authorisation
    // for the account-delete auto-destroy. Legacy detection is now an INPUT to the single boot
    // decision; see bootRoute's `legacyImage` arm, which orders it AFTER the confirmed marker and
    // BEFORE image-present. `onUnlockPassphrase` still routes PassphraseOutcome.LegacyImage to
    // onboarding as an unlock-time backstop.)

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

    // Route reconciliation with the PROCESS-scoped session (round 11, Codex). The route vars
    // above are composition-local: an Activity recreation during a slow vault operation seeds
    // them from a one-time snapshot, and the operation's own completion callback then writes to
    // the DISPOSED composition's state. Two stranded shapes: rotation during an Argon2
    // unlock/create seeds Locked/Onboarding, the surviving worker publishes the session, and no
    // live coroutine ever routes to ChatList (every further unlock is refused — a session is
    // already live); rotation during the NonCancellable account delete seeds ChatList, the
    // delete then nulls the session, and the replacement composes blank. This collector — one
    // per LIVE composition — reconciles both directions. The locked-direction target derives
    // from DISK TRUTH (destroy marker / image presence), the SAME derivation the delete
    // handler's finally uses, so whichever writes last the result is identical — an observer
    // deriving anything else would race that finally and could stomp DeleteIncomplete with a
    // lock gate over a destroyed vault.
    LaunchedEffect(Unit) {
        container.session.collect { live ->
            if (live != null) {
                if (!unlocked) {
                    unlocked = true
                    unlocking = false
                    lockError = null
                    route = Route.ChatList
                }
            } else if (unlocked) {
                unlocked = false
                identityFingerprint = null
                // THE SAME decision function and THE SAME inputs as the two boot consumers. A
                // session going null is not a cold start, but "onboarding requires the carried
                // verdict" is either an invariant everywhere or it is a habit — and an omitted
                // argument is how a weaker consumer hides.
                //
                // Only a CONFIRMED server delete routes to the auto-destroy path. A session going
                // null never carries a mere delete-intent (onNotConfirmed keeps the session live),
                // so intent-only handling lives in the boot decision, not here.
                // Same single derivation the two boot consumers use — see deriveBootDecision.
                val snap = container.deriveBootDecisionFromDisk()
                // A legacy image is present but NOT usable.
                vaultExists = snap.present && !snap.legacy
                route = when (snap.route) {
                    BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
                    BootRoute.ONBOARDING -> Route.Onboarding
                    BootRoute.LOCKED -> Route.Locked
                }
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
    // Pucker Burn (slot 0) match handler. The WIPE HAS LANDED (0.9.2 Unit W-B) — the stub text that
    // stood here described `onBurn` below as an inert no-op while it was already calling burnVault(),
    // which is the "confident prose outliving the code it describes" failure this unit keeps
    // producing. What remains true: slot 0 is UNARMED until burn-setup ships, so no real user can
    // reach this path yet — the credential is not settable. Unreachable-by-credential, not inert.
    /**
     * THE DURESS WIPE (0.9.2 Unit W-B) — replaces the inert stub that showed a uniform failure and
     * destroyed nothing.
     *
     * WIRING INVARIANT (pin it, do not weaken): this is the ONLY consumer of
     * [PassphraseOutcome.Burn] that wipes. `attemptUnlockOrAdd` has a single caller and returns
     * `Burn` only on a real slot-0 match — a create-collision returns `Rejected`, never `Burn` — so a
     * second-vault create can never trigger a wipe. Any future consumer of `Burn` must treat it as
     * "reject candidate".
     *
     * TERMINAL EXCLUSION BEFORE THE FIRST DESTRUCTIVE MUTATION: `beginTerminalWipe()` fences the
     * auto-lock timer and shuts the unlock gate, so no successor session can be built over stores
     * that are being torn out from under it, and no background timer races the wipe.
     *
     * **WB-2 — NonCancellable IS A SECURITY PROPERTY, NOT A ROBUSTNESS ONE. DO NOT MAKE THIS
     * CANCELLABLE.** A duress wipe a rotation can interrupt is a duress wipe a COERCER can interrupt:
     * hand the phone back, rotate the screen, and the wipe stops half-done. This is an
     * attacker-controlled abort, not a responsiveness trade-off. Past the first unlink this runs to
     * completion or to a recorded failure, never to silent abandonment.
     *
     * **WB-1 — THE UNIFORM FAILURE AND THE DURABILITY HOLD ARE ONE INVARIANT, NOT TWO PROPERTIES.**
     * Success routes to ordinary onboarding (P2: VISIBLE RESET — the fresh-install presentation IS
     * the outcome). Failure shows the SAME uniform failure a wrong passphrase shows. The two halves
     * are mutually load-bearing and may not be changed independently:
     *  - the uniform message is only SAFE because the hold stops the next boot presenting a fresh
     *    install over an unproven wipe — without it, "say nothing" degrades to "say nothing and lose
     *    the wipe";
     *  - the hold's value HERE is only realized because the message reveals nothing — without
     *    uniformity the hold protects durability while the screen tells a coercer a burn was tried.
     *
     * **Making this message more informative is an ordinary-looking UX change that breaks the
     * deniability half while every durability test still passes.** Nothing mechanical objects; this
     * comment and invariant WB-1 are the objection.
     */
    val onBurn: () -> Unit = {
        // The whole terminal sequence — terminal exclusion, session quiesce, wipe — lives in
        // `AppContainer.runTerminalBurn`, which the byte-for-byte gate calls too. It is ONE callable
        // deliberately: when the quiesce lived here only, the gate burned a published session without
        // it and could not have failed if this call were deleted.
        // The PROCESS scope, not the composition's: the wipe must survive an Activity recreation
        // (WB-2). Its outcome is SIGNALLED rather than applied here, because the composition that
        // started it may not be the one alive when it finishes.
        container.scope.launch {
            val wiped = withContext(NonCancellable + Dispatchers.IO) {
                // A SUCCESSFUL burn does not return: `terminate` kills the process as its last act,
                // so nothing below this line runs on the success path (see AppContainer.burnVault for
                // why an in-process wipe cannot be durable against a live writer). The FAILURE path
                // returns normally and must still present WB-1's uniform error — killing the process
                // there would both lose the durability hold's RAM state and make a failed burn
                // visibly different from a wrong passphrase.
                runCatching { container.runTerminalBurn(terminate = ::killThisProcess) }.isSuccess
            }
            container.unlockController.endTerminalWipe()
            container.burnCompletion.signal(
                if (wiped) BurnCompletion.Wiped else BurnCompletion.Failed,
            )
        }
    }

    /**
     * APPLY-ONCE (0.9.2 Unit W-B): snapshot → claim → apply. Whichever composition is alive when the
     * wipe finishes renders the outcome exactly once; a recreation mid-wipe picks up an outcome
     * signalled while it did not exist, and two concurrent compositions cannot both render it because
     * only one wins [BurnCompletionCoordinator.claim].
     */
    val pendingBurn by container.burnCompletion.pending.collectAsState()
    LaunchedEffect(pendingBurn) {
        val outcome = pendingBurn ?: return@LaunchedEffect
        if (!container.burnCompletion.claim(outcome)) return@LaunchedEffect
        unlocking = false
        when (outcome) {
            BurnCompletion.Wiped -> {
                vaultExists = false
                route = Route.Onboarding
            }
            // WB-1: uniform with a wrong passphrase. Read the invariant before changing this.
            BurnCompletion.Failed -> lockError = VaultUnlockRouter.UNIFORM_FAILURE
        }
    }

    val onUnlockPassphrase: (String) -> Unit = onUnlockPassphrase@{ pass ->
        if (unlocking) return@onUnlockPassphrase
        unlocking = true
        lockError = null
        scope.launch {
            val backoff = container.unlockRouter.backoffDelayMs()
            if (backoff > 0) delay(backoff)
            runCatching { container.attemptPassphrase(pass) }.fold(
                onSuccess = { outcome ->
                    // The router (attemptPassphrase) owns the triple-entry ritual + the backoff counter;
                    // this only maps the outcome to UI. Unlocked/Created publish a session → the session
                    // collector flips route/unlocking. Rejected is indistinguishable from a wrong password.
                    when (outcome) {
                        PassphraseOutcome.Unlocked, PassphraseOutcome.Created -> onUnlockSuccess()
                        PassphraseOutcome.Burn -> onBurn()
                        PassphraseOutcome.LegacyImage -> {
                            // A PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
                            // reservation; the store threw before any slot was interpreted (never a burn
                            // wipe). Route to fresh onboarding (the create there retires the old image).
                            vaultExists = false
                            route = Route.Onboarding
                            unlocking = false
                        }
                        PassphraseOutcome.ImageUnreadable -> {
                            // A damaged/unreadable IMAGE is device state, NOT a passphrase guess — a
                            // distinct honest error, never the wrong-passphrase uniform failure.
                            lockError = VaultUnlockRouter.IMAGE_UNREADABLE_NOTE
                            unlocking = false
                        }
                        PassphraseOutcome.Rejected, PassphraseOutcome.Retry -> {
                            // Wrong passphrase / no match / fail-closed create (Rejected), or a create whose
                            // durability was unconfirmed (Retry — a re-entry unlocks the now-present vault).
                            // Both surface the same uniform failure so neither is an oracle.
                            lockError = VaultUnlockRouter.UNIFORM_FAILURE
                            unlocking = false
                        }
                    }
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // attemptPassphrase maps every expected image/durability case to an outcome; an
                    // unexpected throw (e.g. a publishSession build-refuse) is a failure — bump the
                    // backoff (parity with the pre-fusion path) and surface a uniform failure, never
                    // leaking the cause.
                    container.unlockRouter.recordFailure()
                    lockError = VaultUnlockRouter.UNIFORM_FAILURE
                    unlocking = false
                },
            )
        }
    }

    // Biometric availability for the lock-screen affordance and the veil CTA.
    val biometricUnlockAvailable = vaultExists && biometricEnabled && canAuthenticateStrong

    // Biometric unlock (§2): BIOMETRIC_STRONG CryptoObject only. Invalidation (a new
    // enrollment) drops to the passphrase field with an honest note, clears the dead wrap, and
    // arms the re-enable that the note promises (fired on the next passphrase unlock).
    // Revoke biometric (wrap blob + auth-gated Keystore key) OFF the main thread, then run the
    // Compose-state reconcile on main (round 12c, Gemini): disableBiometric() → deleteEntry() is a
    // Keystore binder call that can stall main under a busy TEE (same invariant as the round-11b
    // cipher moves). runCatching so a deleteKey throw on an already-unhealthy keystore still runs
    // the full reconcile — the dead biometric affordance must not persist even then.
    val disableBiometricThen: (() -> Unit) -> Unit = { onReconciled ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { container.disableBiometric() } }
            onReconciled()
        }
    }

    val onUnlockBiometric: () -> Unit = onUnlockBiometric@{
        if (unlocking) return@onUnlockBiometric
        unlocking = true
        lockError = null
        startVaultBiometricUnlock { result ->
            when (result) {
                VaultBiometricResult.SUCCESS -> onUnlockSuccess()
                // INVALIDATED (new enrollment) and UNAVAILABLE (unusable wrap / gone key) both
                // revoke off-main and drop to passphrase, arming the re-enable the note promises.
                // unlocking clears in the reconcile (which always runs — runCatching above), so a
                // throwing cleanup can't strand the lock screen (both unlock paths gate !unlocking).
                VaultBiometricResult.INVALIDATED, VaultBiometricResult.UNAVAILABLE ->
                    disableBiometricThen {
                        biometricEnabled = false
                        reofferBiometric = true
                        lockError = VaultUnlockRouter.BIOMETRIC_REENROLL_NOTE
                        unlocking = false
                    }
                VaultBiometricResult.FAILED -> {
                    lockError = VaultUnlockRouter.UNIFORM_FAILURE
                    unlocking = false
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
            disableBiometricThen { biometricEnabled = false }
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
        // PROCESS-scoped single-flight (round 11, Gemini): the composition's own view resets on
        // rotation while the Argon2 create keeps running — without the container-level claim, a
        // second tap on the recreated screen would start a CONCURRENT create. A refused claim
        // means one is already in flight; the collected `creating` flow shows its spinner and
        // the reconciler routes when its session publishes.
        if (!container.tryBeginVaultCreate()) return@onCreateVault
        createError = null
        // Process scope, NOT the composition's: a rotation must neither cancel the create nor
        // orphan the guard release. State writes below may land on a disposed composition after
        // rotation — the session→route reconciler owns the success routing in that case.
        container.scope.launch {
            val result = runCatching { container.createVaultAndPublish(pass) }
            container.endVaultCreate()
            // container.scope is Dispatchers.Default — marshal the Compose-state reconcile to Main
            // (the createVaultAndPublish + endVaultCreate above are correctly off-main). Snapshot
            // state is thread-safe to write, but keeping every state mutation on Main avoids
            // cross-var tearing and matches the openLemonDrop pattern (round 12d, Gemini).
            withContext(Dispatchers.Main) {
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
                    // THROUGH THE SINGLE DERIVATION (0.9.2 Unit W-B, items #1 + #5): this was a bare
                    // `container.hasVault()` — an `imageLock` stat inside `withContext(Main)`. The
                    // question it asks ("is there an image on disk?") is a routing input, and routing
                    // inputs have exactly one owner.
                    if (container.deriveBootDecisionFromDisk().present) {
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
        live.coordinator.deleteAccountAndWipe(
            onIntentNotDurable = {
                // The delete-intent marker could not be made durable, so the delete never touched
                // the server (round 13): lift the gate. Nothing was destroyed — the session is
                // still live. Marshaled to Main (round 12d); Main.immediate + container.scope so it
                // survives a rotation and is not cancelled by the composition.
                container.unlockController.endTerminalWipe()
                container.scope.launch(Dispatchers.Main.immediate) {
                    lockError = "Couldn't start deleting your account. Please try again."
                }
            },
            onNotConfirmed = { definiteFailure ->
                // The server did NOT confirm deletion (round 13): destroy NOTHING, keep the live
                // session AND the intent marker (never abandoned, round 14 F1 — the next unlock's
                // reconcile retries). definiteFailure = the server refused (an auth/permission
                // problem, the account still exists); else ambiguous/offline. The message only
                // surfaces on the lock screen — a known UX gap while the user is on a session route
                // (flagged for follow-up); the load-bearing property is that no local crypto is
                // destroyed over a possibly-live account.
                container.unlockController.endTerminalWipe()
                container.scope.launch(Dispatchers.Main.immediate) {
                    lockError = if (definiteFailure) {
                        "Your account couldn't be deleted. Please try again."
                    } else {
                        "Couldn't reach the server to delete your account. Check your connection and try again."
                    }
                }
            },
            onConfirmedNotDurable = {
                // The server account IS gone, but this device couldn't durably RECORD the
                // confirmation (round 14, F1): destroy NOTHING and clear NO auth. Keep the session
                // + the intent marker so the next unlock's reconcile repeats the (now idempotent-
                // 404) DELETE and records confirmation before destroying. No local crypto is
                // destroyed without a durable confirmed marker.
                container.unlockController.endTerminalWipe()
                container.scope.launch(Dispatchers.Main.immediate) {
                    lockError = "Your account was deleted. This device will finish clearing it the next time you open the app."
                }
            },
            onConfirmed = {
            // Routing derives from DISK TRUTH after the wipe, not from exception classification:
            // Onboarding-as-success ONLY when destroy() CONFIRMED both files gone (no image, no
            // destroy-pending marker); anything else — DestroyFailed, an unexpected teardown
            // throw, even cancellation — lands on DeleteIncomplete, whose only exit is a
            // confirmed (idempotent) destroy retry. The finally guarantees routing ALWAYS runs:
            // without it a throw would strand `route` on a session screen with session == null,
            // which composes a permanent blank.
            try {
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
                        // Synchronous session teardown: runtime.close() reseals the image one last
                        // time. destroyVault (below) then deletes it — ordering is load-bearing.
                        container.unlockController.lockIf(live)
                    },
                    // The load-bearing no-remanence step: delete vault.bin/vault.dek + biometric
                    // wrap/key. Runs AFTER the reseal, in completeTerminalWipe's finally, so it can
                    // never be skipped. THROWS VaultImageException.DestroyFailed if a file survives.
                    destroyVault = { container.destroyVaultForAccountDeletion() },
                    releaseGate = { container.unlockController.endTerminalWipe() },
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // DestroyFailed (a surviving file) or an unexpected teardown throw — either way
                // the routing below derives from disk truth. releaseGate already ran in
                // completeTerminalWipe's outermost finally, so the unlock gate is not stranded.
            } finally {
                // This callback runs on the coordinator's background (confined) dispatcher, so the
                // Compose-state reconcile is marshaled to Main. Main.immediate + container.scope so a
                // rotation mid-wipe cannot cancel it.
                //
                // ONE ROUTING AUTHORITY (round-3 review, Grok; Kimi concurring). `lockIf` publishes
                // session=null above, which also wakes the session collector — so this callback and
                // that collector decide the SAME routing moment. They used to read the same two
                // stats, and a comment here asserted "the two cannot disagree". W-A made that comment
                // FALSE: the collector was given the carried `durabilityHold` and this path was
                // left on `hasVault()` + `serverDeleteConfirmed()`. With a hold raised earlier in the
                // process, the collector computes LOCKED while this computes Onboarding, both write
                // `route`, and the last writer wins — pinning a successfully deleted account to a
                // lock screen for the rest of the process. That is this unit's signature failure
                // class, reintroduced by strengthening one consumer and not its twin.
                //
                // Both now go through the same derivation with the same inputs.
                container.scope.launch(Dispatchers.Main.immediate) {
                    identityFingerprint = null
                    unlocked = false
                    lockError = null
                    // A COMPLETED destroy supersedes an earlier durability hold: it proved
                    // image-bearing absence with its OWN required dirSync and retired both markers
                    // only after that proof. Leaving a stale hold raised would withhold onboarding
                    // over a directory this delete has just proven durably clean.
                    //
                    // FOLDED INTO THE DERIVATION (0.9.2 Unit W-B, items #1 + #5). This site used to
                    // take two fresh stats HERE, on `Dispatchers.Main.immediate`, to decide the
                    // supersede — then call the derivation, which stats the disk again. Disk I/O on
                    // the Main thread, a second re-derivation, and a torn pair-read, in one place.
                    // The flag asks the single owner to decide it from the SAME snapshot it routes
                    // from; no caller assembles routing inputs of its own.
                    val snap = container.deriveBootDecisionFromDisk(supersedeCompletedDestroy = true)
                    vaultExists = snap.present && !snap.legacy
                    // The mapping matches the previous explicit semantics in every ORDINARY
                    // post-destroy state: a surviving image implies the markers were NOT retired, so
                    // `serverDeleteConfirmed` is still set and bootRoute yields DELETE_INCOMPLETE.
                    //
                    // DEFENCE IN DEPTH — DO NOT DELETE THIS AS UNREACHABLE. Read the dependency below
                    // before concluding anything about whether this can fire.
                    //
                    // History, because the reasoning matters more than the outcome. Round 4 (Kimi)
                    // corrected a claim here that "{image survives, confirmed absent} cannot occur:
                    // destroy throws before the retire when absence is unproven". At that time destroy
                    // did NOT throw on unproven absence — its verify was `exists()`-based, true only
                    // on a PROVEN PRESENCE, so an INDETERMINATE stat read as absent and passed; if the
                    // required dirSync then reported DURABLE the markers were retired, making the
                    // state REACHABLE on a pathological filesystem. What made it safe was the ROUTING
                    // below, not destroy.
                    //
                    // CURRENT FACT AND ITS DEPENDENCY (0.9.2 Unit W-B): `obliterateLocked()`'s S4
                    // verify is now PROVEN-ABSENCE (`imageBearingFilesProvenAbsent`, Files.notExists),
                    // so an indeterminate stat is a SURVIVOR and throws `DestroyFailed` before the
                    // marker retire. Through the destroy/burn path that state is therefore currently
                    // UNREACHABLE.
                    //
                    // **THAT IS NOT A REASON TO REMOVE THIS.** The whole value of this check is that
                    // it does NOT depend on S4 being right. Deleting it because "S4 makes it
                    // impossible" would couple correctness HERE to a check three layers up in another
                    // file, in a different unit, that a future change can loosen without ever looking
                    // at this line — which is dead-code-removal reasoning applied to a defence-in-depth
                    // layer, and is exactly backwards.
                    //
                    // The routing property stands on its own: an indeterminate stat leaves
                    // `vaultProvenAbsent` false (`Files.notExists`, proven-absence only) and
                    // `imagePresent` false, so bootRoute falls through to LOCKED — withholding
                    // onboarding over an image it cannot prove gone. Fail-closed by construction,
                    // whatever S4 does. If S4 ever reverts to `exists()`, this comment becomes
                    // VISIBLY wrong (the stated dependency is checkable) rather than silently stale.
                    route = when (snap.route) {
                        BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
                        BootRoute.ONBOARDING -> Route.Onboarding
                        BootRoute.LOCKED -> Route.Locked
                    }
                }
            }
            },
        )
    }

    // Reconcile an interrupted account deletion (round 14, F1). A delete-intent marker that
    // survived a crash means a delete was INITIATED but never durably confirmed — the account may
    // or may not be gone server-side. On the first LIVE session after such a boot (auth is
    // retained, since round 14 no longer clears it early), retry the authenticated DELETE via the
    // same handler: an idempotent 404 (already gone) → confirm + destroy; a success → confirm +
    // destroy; any not-confirmed outcome surfaces + KEEPS the intent for the next unlock. Keyed on
    // the session instance so it runs once per unlock; a confirmed reconcile tears the session
    // down (won't re-fire). The boot path does NOT assume auth is present — a token-less DELETE
    // returns 401 → DEFINITE_FAILURE → surfaced, not silently abandoned.
    LaunchedEffect(session) {
        if (session != null && container.vaultDeleteIntentPending()) {
            onDeleteAccount()
        }
    }

    // Biometric-enable offer (§1) — over the LIVE session (holds NO VaultOpen, so an Activity
    // recreation drops only the offer, never key material). Shown after an onboarding create, or
    // after a passphrase unlock that followed a biometric invalidation. Enable dual-wraps the live
    // session's vault key (withVaultKey); skipping proceeds passphrase-only. Short-circuits routing.
    // SLOT-FREE by construction (VaultUnlockRouter.biometricEnrollOffered takes no slot): the enroll
    // offer renders identically in an A-session and a B-session — the A-only rule is enforced only on
    // the write path (enableBiometricFromSession), never here. The live global isEnabled() gate hides
    // the offer whenever a wrap already exists (in BOTH sessions), so a cross-slot enable is never
    // tappable — closing the enable-action timing tell and the destructive re-enable (round-2).
    if (container.unlockRouter.biometricEnrollOffered(
            offerBiometricEnroll, session != null, container.biometricStore.isEnabled(),
        )
    ) {
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
            // Splash ONLY records that its animation ended. It must not route: boot reconciliation
            // MUTATES what disk says (the orphan sweep unlinks residue), so a decision taken here
            // could read a half-swept directory, or read the durability hold while it still held its
            // default. The decision lives in the effect above, which waits for BOTH signals.
            Route.Splash -> SplashScreen(onFinished = { splashFinished = true })

            Route.Onboarding -> OnboardingScreen(
                onCreateVault = onCreateVault,
                creating = creating,
                createError = createError,
            )

            // Finish an account deletion whose local vault unlink did not verify. Auto-retries
            // once on entry (the failure is usually a transient I/O blip), then offers a manual
            // retry; the ONLY exit is a confirmed destroy → Onboarding via onRetryDestroy.
            Route.DeleteIncomplete -> {
                LaunchedEffect(Unit) { onRetryDestroy() }
                DeleteIncompleteScreen(
                    retrying = deleteRetrying,
                    showError = deleteRetryFailed,
                    onRetry = onRetryDestroy,
                )
            }

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
        Route.Splash, Route.Onboarding, Route.Locked, Route.DeleteIncomplete -> Unit
    }
}

/**
 * End this process — the last act of a SUCCESSFUL duress burn (0.9.2 Unit W-B).
 *
 * `killProcess(myPid())` and NOT `exitProcess`/`finishAffinity`: this must not run shutdown hooks or
 * give any component a chance to flush state back to disk, which is the entire reason the burn ends
 * here (see `AppContainer.burnVault`). It is an immediate SIGKILL of our own process, so every queued
 * `SharedPreferences` write dies with it rather than landing after the burn proved absence.
 *
 * Extracted as a named top-level function so the burn's call site reads as a decision rather than an
 * incantation, and so the ONE place that terminates the app is greppable.
 */
internal fun killThisProcess(): Unit = android.os.Process.killProcess(android.os.Process.myPid())
