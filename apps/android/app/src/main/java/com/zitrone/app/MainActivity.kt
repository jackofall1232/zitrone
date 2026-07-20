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
import androidx.lifecycle.lifecycleScope
import com.zitrone.app.data.Conversation
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
import com.zitrone.app.ui.screens.LockScreen
import com.zitrone.app.ui.screens.OnboardingScreen
import com.zitrone.app.ui.screens.SettingsScreen
import com.zitrone.app.ui.screens.SplashScreen
import com.zitrone.app.ui.theme.Motion
import com.zitrone.app.ui.theme.ZitroneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
/** Saved-instance-state key for the lemon-drop advocacy veil's visibility. */
private const val STATE_LEMON_DROP_SCAN = "lemon_drop_scan"

class MainActivity : FragmentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Either way we proceed: notifications are content-free anyway.
        }

    /**
     * Raised when this Activity opens a lemon-drop deep link, telling the Compose
     * tree to show the advocacy veil (see [ZitroneRoot]). Owned by the Activity
     * because VIEW intents arrive here — in onCreate and [onNewIntent] — outside
     * of composition.
     */
    private val lemonDropScan = MutableStateFlow(false)

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
        } else {
            lemonDropScan.value = savedInstanceState.getBoolean(STATE_LEMON_DROP_SCAN, false)
        }

        setContent {
            ZitroneTheme {
                ZitroneRoot(
                    container = container,
                    requestBiometric = ::showBiometricPrompt,
                    lemonDropScan = lemonDropScan.asStateFlow(),
                    onLemonDropDismissed = { lemonDropScan.value = false },
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

    // The advocacy veil must survive a configuration change: only its visibility
    // is saved — the fetch already fired exactly once when the link arrived and
    // is never replayed on restore.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LEMON_DROP_SCAN, lemonDropScan.value)
    }

    /**
     * Lemon-drop ("QR dead drop") link handling. When this phone opens
     * `https://zitrone.app/d/{id}`, Zitrone V1 does exactly two things and NOTHING
     * else — no decrypt is attempted, and no crypto dependency is pulled in:
     *
     *  1. fires ONE unauthenticated fetch of the drop, fire-and-forget (below);
     *  2. shows the advocacy screen — always (the veil in [ZitroneRoot]).
     *
     * Why no open attempt is HONEST, not a shortcut: a Zitrone account is
     * per-device, and the only clients that can CREATE a lemon drop in V1 (web and
     * desktop) cannot address one to an Android-family account — the two families'
     * wire formats are not compatible — so an Android device that scans ANY lemon
     * drop is genuinely never its intended recipient today. Attempting to open the
     * sealed box could therefore only ever fail, and pretending otherwise in the UI
     * would be a lie. The full sealed-box open attempt is the documented V1.1
     * follow-up, gated on crypto review.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val qrId = intent.dataString?.let(::parseQrDropLink) ?: return

        // ONE unauthenticated fetch of the real route, fire-and-forget on a
        // background dispatcher. The response and any error are IGNORED — never
        // logged (this repo forbids logging) and never surfaced. Its sole purpose
        // is to make an Android scan network-indistinguishable from a real
        // redemption attempt; we never open what comes back. runCatching swallows
        // everything, including the ApiException that fetchQrDrop throws on a 404
        // (missing/expired/burned) or any transport failure.
        val container = (application as ZitroneApp).container
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { container.apiClient.fetchQrDrop(qrId) }
        }

        // Always show advocacy. Raised as a veil that sits IN FRONT of the
        // biometric gate — it carries zero secret content, so a scan never forces
        // an unlock (see ZitroneRoot).
        lemonDropScan.value = true
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
    lemonDropScan: StateFlow<Boolean>,
    onLemonDropDismissed: () -> Unit,
) {
    val context = LocalContext.current

    val settings by container.settingsRepository.settings.collectAsState()
    val conversations by container.conversationRepository.conversations.collectAsState()
    val allMessages by container.messageRepository.messages.collectAsState()
    val typingPeers by container.coordinator.typingPeers.collectAsState()
    val connectivity by container.coordinator.connectivity.collectAsState()
    val transportState by container.transportResolver.state.collectAsState()
    val accountId by container.apiClient.accountIdFlow.collectAsState()
    val showLemonDrop by lemonDropScan.collectAsState()

    var route by remember { mutableStateOf<Route>(Route.Splash) }
    var unlocked by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }

    // Root detection: warn once per process, never block.
    var rootWarningVisible by remember {
        mutableStateOf(RootDetection.check(context).likelyRooted)
    }

    val unlock: () -> Unit = {
        requestBiometric { success, error ->
            if (success) {
                lockError = null
                unlocked = true
                route = Route.ChatList
            } else {
                lockError = error
            }
        }
    }

    // Boot the messaging stack only after the gate opens.
    LaunchedEffect(unlocked) {
        if (unlocked) container.coordinator.start()
    }

    // Server-side session revocation forces the gate shut again.
    DisposableEffect(Unit) {
        container.coordinator.onForcedLogout = {
            unlocked = false
            route = Route.Locked
        }
        onDispose { container.coordinator.onForcedLogout = null }
    }

    // Lemon-drop advocacy veil. It sits IN FRONT of everything, including the
    // biometric gate, and short-circuits the normal routing below: while it is up,
    // Splash/Locked/ChatList are not composed, so the gate never advances behind
    // it and no secret content renders — safe to show without an unlock because
    // the screen holds none. Dismiss just drops the veil, revealing the app's real
    // state underneath (still locked if it was locked), untouched. See
    // MainActivity.handleDeepLink for why V1 shows advocacy and never decrypts.
    if (showLemonDrop) {
        BackHandler(enabled = true) { onLemonDropDismissed() }
        LemonDropAdvocacyScreen(onDismiss = onLemonDropDismissed)
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
                        Route.ChatList
                    }
                },
            )

            Route.Locked -> {
                LockScreen(onUnlockRequest = unlock, errorMessage = lockError)
                LaunchedEffect(Unit) { unlock() }
            }

            Route.ChatList -> ChatListScreen(
                conversations = conversations,
                rootWarningVisible = rootWarningVisible,
                onDismissRootWarning = { rootWarningVisible = false },
                onOpenConversation = { route = Route.Chat(it.id) },
                onOpenSettings = { route = Route.Settings },
                onNewChat = { route = Route.AddContact },
            )

            is Route.Chat -> {
                val conversation = conversations.firstOrNull { it.id == current.conversationId }
                if (conversation == null) {
                    // Conversation burned away beneath us.
                    LaunchedEffect(current) { route = Route.ChatList }
                } else {
                    LaunchedEffect(conversation.id) {
                        container.conversationRepository.markConversationRead(conversation.id)
                    }
                    ChatScreen(
                        conversation = conversation,
                        messages = allMessages[conversation.id].orEmpty(),
                        peerTyping = conversation.contactId in typingPeers,
                        defaultTtlSeconds = settings.defaultTtlSeconds,
                        defaultBurnOnRead = settings.burnOnReadDefault,
                        ttlOptions = container.settingsRepository.ttlOptionsSeconds,
                        onBack = { route = Route.ChatList },
                        onVerifyKeys = { route = Route.Verify(conversation.id) },
                        onBurnAll = { container.messageRepository.burnAll(conversation.id) },
                        onSend = { text, ttl, burn ->
                            container.coordinator.sendText(conversation, text, ttl, burn)
                        },
                        onSendAttachment = { bytes, kind, mimetype, filename, caption, ttl, burn ->
                            container.coordinator.sendAttachment(
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
                            container.coordinator.onMessagesSeen(conversation, seenIds)
                        },
                        onTyping = { started ->
                            container.coordinator.sendTyping(conversation, started)
                        },
                        onRetry = { messageId ->
                            container.coordinator.retry(messageId)
                        },
                        onRevealImage = { messageId ->
                            container.coordinator.revealAttachment(messageId)
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
                // Keystore + fingerprint work is off the main thread — doing it
                // in composition can drop frames / ANR.
                var identityFingerprint by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    identityFingerprint = withContext(Dispatchers.Default) {
                        runCatching {
                            container.signalManager.ensureIdentity()
                            container.signalManager.localFingerprint()
                        }.getOrDefault("")
                    }
                }
                SettingsScreen(
                    settingsRepository = container.settingsRepository,
                    accountId = accountId,
                    identityFingerprint = identityFingerprint,
                    connectivity = connectivity,
                    transportState = transportState,
                    torAvailable = torAvailable,
                    officialRouterInstalled = officialRouterInstalled,
                    i2pdInstalled = i2pdInstalled,
                    onBack = { route = Route.ChatList },
                    onDeleteAccount = {
                        container.coordinator.deleteAccountAndWipe {
                            container.signalStore.wipe()
                            unlocked = false
                            route = Route.Splash
                        }
                    },
                    onOpenDiagnostics = { route = Route.Diagnostics },
                )
            }

            Route.Diagnostics -> DiagnosticsScreen(
                diagnostics = container.bootDiagnostics,
                onBack = { route = Route.Settings },
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
                                container.signalManager.ensureIdentity()
                                buildContactExchangePayload(
                                    accountId = acct,
                                    identityKeyBase64 = container.signalManager.localIdentityPublicKeyBase64(),
                                )
                            }.getOrNull()
                        }
                    }
                }
                AddContactScreen(
                    myContactPayload = myPayload,
                    myAccountId = accountId,
                    onBack = { route = Route.ChatList },
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
                            container.conversationRepository.upsert(conversation)
                            route = Route.Chat(conversation.id)
                        }
                    },
                )
            }

            is Route.Verify -> {
                val conversation = conversations.firstOrNull { it.id == current.conversationId }
                if (conversation == null) {
                    LaunchedEffect(current) { route = Route.ChatList }
                } else {
                    val safetyNumber = remember(conversation.contactIdentityKeyBase64) {
                        runCatching {
                            val contactKey = conversation.contactIdentityKeyBase64
                            if (contactKey != null) {
                                container.signalManager.safetyNumberWith(contactKey)
                            } else {
                                // No key exchanged yet — show our own
                                // fingerprint so verification can still start
                                // from the other side.
                                container.signalManager.localFingerprint()
                            }
                        }.getOrDefault("")
                    }
                    KeyVerificationScreen(
                        conversation = conversation,
                        safetyNumber = safetyNumber,
                        onBack = { route = Route.Chat(conversation.id) },
                        onMarkVerified = {
                            container.conversationRepository.setVerified(conversation.id, true)
                        },
                    )
                }
            }
        }
    }
}
