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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.zitrone.app.ui.theme.Motion
import com.zitrone.app.ui.theme.ZitroneTheme
import kotlinx.coroutines.Dispatchers
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
    lemonDropVeil: StateFlow<LemonDropVeil?>,
    onLemonDropDismissed: () -> Unit,
    onLemonDropOpened: (PendingLemonDrop) -> Unit,
) {
    // Device-half flows only — process-lifetime, safe to read pre-unlock. Every
    // session-derived flow moved into [SessionUi], composed only when the session
    // below is non-null.
    val settings by container.settingsRepository.settings.collectAsState()
    val transportState by container.transportResolver.state.collectAsState()
    val lemonDropVeilState by lemonDropVeil.collectAsState()
    // Built on unlock, null while locked. The single seam the D2b lifecycle turns
    // on: session-derived objects come alive here, not at process start.
    val session by container.session.collectAsState()

    var route by remember { mutableStateOf<Route>(Route.Splash) }
    var unlocked by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }

    // This device's OWN identity self-fingerprint, tiled as the always-on
    // "security paper" watermark behind every chat surface (Settings also shows
    // it verbatim). Hoisted here — the one place shared by the veil (in front of
    // the gate) and every session surface — so all share a single off-main-thread
    // computation. Null until it lands; a compute failure also leaves it null and
    // simply paints no watermark (never blocks the UI).
    // SESSION-GATED: computed once a session is live (session != null implies
    // unlocked), so ensureIdentity only ever runs behind the real app gate. The
    // locked-scan veil renders unmarked — it holds nothing secret and needs no
    // fingerprint. A live-session lemon drop (Advocacy/AwaitUnlock/Delivered)
    // necessarily has a session, so the mark is present on the see-once plaintext.
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
    // once per unlock cycle (never per navigation). Composed only while a session
    // is live; a fresh unlock builds a new instance and re-runs both effects.
    session?.let { live ->
        // Boot the messaging stack once the gate opens and the session exists.
        LaunchedEffect(live) { live.coordinator.start() }
        // Server-side session revocation forces the gate shut AND tears the
        // session down; a re-unlock builds a fresh one over the durable legacy
        // stores (state reloads exactly as on a process restart). Rewired onto
        // each new session instance.
        DisposableEffect(live) {
            live.coordinator.onForcedLogout = {
                unlocked = false
                route = Route.Locked
                // lockIf: this callback belongs to THIS session; if it fires
                // late (rewire races), it must not tear down a successor.
                container.unlockController.lockIf(live)
            }
            onDispose { live.coordinator.onForcedLogout = null }
        }
    }

    // Root detection: warn once per process, never block.
    val context = LocalContext.current
    var rootWarningVisible by remember {
        mutableStateOf(RootDetection.check(context).likelyRooted)
    }

    val unlock: () -> Unit = {
        requestBiometric { success, error ->
            if (success) {
                lockError = null
                unlocked = true
                route = Route.ChatList
                // Build the session NOW — the one intended lifecycle change.
                // Published synchronously, so the ChatList route below composes
                // against a live session in the same frame.
                container.unlockController.unlock()
            } else {
                lockError = error
            }
        }
    }

    // Account delete: wipe THROUGH the live session (there is no delegating
    // getter any more), in order — coordinator wipe-work → signalStore.wipe() →
    // lock() — then land on Splash. Reachable only from Settings, where a session
    // is live; the guard is a backstop.
    val onDeleteAccount: () -> Unit = onDeleteAccount@{
        val live = session ?: return@onDeleteAccount
        // Gate unlock shut for the wipe's duration: a successor session built
        // while the shared stores are being cleared underneath it would hold
        // stale roster/auth state with vanished crypto (Codex PR #45 r2). The
        // completion below always runs (NonCancellable) and lifts the gate.
        container.unlockController.beginTerminalWipe()
        live.coordinator.deleteAccountAndWipe {
            live.signalStore.wipe()
            // Drop the DELETED account's mark or it would keep watermarking (and
            // being shown in Settings for) the next identity until process death
            // (PR #8 review). The session-keyed effect recomputes it for the
            // fresh identity once the next session builds.
            identityFingerprint = null
            unlocked = false
            route = Route.Splash
            // Session objects are gone after the wipe — tear the slot down last.
            // lockIf: the NonCancellable wipe can outlive its session (a racing
            // revocation tears it down first); a late completion must not tear
            // down a successor. With the terminal-wipe gate above no successor
            // can build during the wipe — this is now pure belt-and-braces.
            container.unlockController.lockIf(live)
            container.unlockController.endTerminalWipe()
        }
    }

    // The Locked veil must make the SAME decision Splash's routing makes — it
    // short-circuits that routing, so re-derive it here. Never unlockable on a
    // not-yet-onboarded install (an app-unlock there would skip onboarding,
    // mint identity keys and register a relay account pre-consent), and never
    // a prompt for a user whose gate is off (Splash unlocks those silently).
    val veilLockedPreOnboarding =
        lemonDropVeilState is LemonDropVeil.Locked && !settings.onboardingDone
    val gateOff = settings.onboardingDone && !settings.biometricRequired
    // Gate-off parity: with the veil short-circuiting routing, Splash's silent
    // auto-unlock can never run while the veil is up — which would force an
    // auth prompt the user disabled, or lose the queued scan on dismiss.
    // Unlock silently NOW (same no-prompt semantics as Splash); the queued
    // scan probes and the veil refines to its outcome with zero interaction,
    // exactly as the pre-unlock probe behaved on main. Route is untouched:
    // dismissing the veil lands on Splash, which routes as usual.
    val veilLocked = lemonDropVeilState is LemonDropVeil.Locked
    LaunchedEffect(veilLocked, gateOff) {
        if (veilLocked && gateOff && !unlocked) {
            unlocked = true
            container.unlockController.unlock()
        }
    }
    val unlockFromVeil: () -> Unit = {
        when {
            !settings.onboardingDone -> Unit // Locked veil is not composed pre-onboarding
            settings.biometricRequired -> unlock()
            else -> {
                // Gate off: no prompt (backstop — the effect above normally
                // beats the tap). Mirrors Splash's silent-unlock branch.
                unlocked = true
                route = Route.ChatList
                container.unlockController.unlock()
            }
        }
    }

    // Lemon-drop veil. It sits IN FRONT of everything, including the biometric
    // gate, and short-circuits the normal routing below: while it is up,
    // Splash/Locked/ChatList are not composed, so the gate never advances
    // behind it. Safe in front of the lock because the pre-unlock states
    // (Locked, Advocacy, AwaitUnlock) render no secret content; Delivered — the
    // one state that shows plaintext — is reachable only through the explicit
    // biometric success wired below (see LemonDropVeil's invariant). Dismiss
    // just drops the veil, revealing the app's real state underneath (still
    // locked if it was locked), untouched — and, pre-unlock, burns nothing.
    // EXCEPTION: a Locked veil on a not-yet-onboarded install does NOT compose —
    // normal routing (Splash → Onboarding) runs instead, with the scan still
    // queued; the first real unlock after onboarding drains it. The veil offers
    // an app-unlock CTA, and there is no legitimate app-unlock before onboarding.
    lemonDropVeilState?.takeUnless { veilLockedPreOnboarding }?.let { veil ->
        BackHandler(enabled = true) { onLemonDropDismissed() }
        when (veil) {
            // Scanned while the app is locked: the gate decision above. On
            // success the queued scan probes (AppContainer.onSessionPublished)
            // and this refines into Advocacy/AwaitUnlock — the same transitions a
            // live-session scan makes. No fingerprint yet (no session) — unmarked.
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
            Route.Splash -> SplashScreen(
                onFinished = {
                    route = when {
                        !settings.onboardingDone -> Route.Onboarding
                        settings.biometricRequired -> Route.Locked
                        else -> {
                            unlocked = true
                            container.unlockController.unlock()
                            Route.ChatList
                        }
                    }
                },
            )

            Route.Onboarding -> OnboardingScreen(
                onDone = {
                    container.settingsRepository.setOnboardingDone(true)
                    route = if (settings.biometricRequired) {
                        Route.Locked
                    } else {
                        unlocked = true
                        container.unlockController.unlock()
                        Route.ChatList
                    }
                },
            )

            Route.Locked -> {
                LockScreen(onUnlockRequest = unlock, errorMessage = lockError)
                LaunchedEffect(Unit) { unlock() }
            }

            // Session routes. `route` becomes one of these only in the same event
            // that called unlock() (which publishes the session synchronously), so
            // the session is live here. During a teardown crossfade the outgoing
            // session screen renders empty rather than reading a dead session.
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
                )
            }
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
