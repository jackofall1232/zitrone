// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zitrone.app.MessagingCoordinator
import com.zitrone.app.notifications.MessagingNotifications
import com.zitrone.app.data.SettingsRepository
import com.zitrone.app.data.TransportState
import com.zitrone.app.i2p.I2pIntegration
import com.zitrone.app.tor.TorIntegration
import com.zitrone.app.ui.components.KeyFingerprintDisplay
import com.zitrone.app.ui.components.LemonSliceSecurity
import com.zitrone.app.ui.components.ttlLabel
import com.zitrone.app.ui.theme.BackgroundElevated
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.BorderColor
import com.zitrone.app.ui.theme.BurnOrange
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.MonoFamily
import com.zitrone.app.ui.theme.Rind
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import com.zitrone.app.ui.theme.TypeScale
import com.zitrone.app.ui.theme.VerifiedGreen

/**
 * Settings (design_system.screens.settings): dark grouped list with lemon
 * accents. Sections — Security, Privacy, Account, Network, Appearance.
 */
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    accountId: String?,
    identityFingerprint: String,
    connectivity: MessagingCoordinator.Connectivity,
    transportState: TransportState,
    torAvailable: Boolean,
    officialRouterInstalled: Boolean,
    i2pdInstalled: Boolean,
    onBack: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by settingsRepository.settings.collectAsState()
    val context = LocalContext.current

    // Live transport. connectivity stays authoritative for connecting/offline
    // (the resolver's TransportState can't grow a CONNECTING member — it's in
    // lockstep with packages/protocol); when ONLINE we overlay the resolver's
    // actual leg (I2P / Tor / clearnet) from the fixed I2P -> Tor -> clearnet
    // chain (see net/TransportResolver.kt).
    val transport = when (connectivity) {
        MessagingCoordinator.Connectivity.ONLINE -> transportState
        MessagingCoordinator.Connectivity.CONNECTING -> null
        MessagingCoordinator.Connectivity.OFFLINE -> TransportState.OFFLINE
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Lemon,
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
        }

        // ----- Security ------------------------------------------------------
        SectionHeader("Security")
        ToggleRow(
            title = "Biometric unlock",
            subtitle = "Require fingerprint or face before showing chats",
            checked = settings.biometricRequired,
            onToggle = settingsRepository::setBiometricRequired,
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Your identity key fingerprint",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = "Contacts can compare this against what their device shows for you.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            KeyFingerprintDisplay(fingerprint = identityFingerprint)
        }

        // ----- Privacy -------------------------------------------------------
        SectionHeader("Privacy")
        ClickableRow(
            title = "Default disappearing timer",
            subtitle = "Applied to new messages: ${ttlLabel(settings.defaultTtlSeconds)}",
            trailing = {
                Text(
                    text = ttlLabel(settings.defaultTtlSeconds),
                    fontFamily = MonoFamily,
                    fontSize = TypeScale.Sm,
                    color = Lemon,
                )
            },
            onClick = {
                val options = settingsRepository.ttlOptionsSeconds
                val next = (options.indexOf(settings.defaultTtlSeconds) + 1) % options.size
                settingsRepository.setDefaultTtlSeconds(options[next])
            },
        )
        ToggleRow(
            title = "Burn on read by default",
            subtitle = "New messages destroy themselves after the first open",
            checked = settings.burnOnReadDefault,
            onToggle = settingsRepository::setBurnOnReadDefault,
        )
        ToggleRow(
            title = "Send read receipts",
            subtitle = "Encrypted signal — the server never knows read status",
            checked = settings.readReceipts,
            onToggle = settingsRepository::setReadReceipts,
        )
        ToggleRow(
            title = "Lemon-drop compose button",
            subtitle = "Show the droplet in chat so you can seal a QR drop. " +
                "Off by default — rare action.",
            checked = settings.lemonDropComposeEnabled,
            onToggle = settingsRepository::setLemonDropComposeEnabled,
        )

        // ----- Notifications -------------------------------------------------
        SectionHeader("Notifications")
        ToggleRow(
            title = "Repeat unread reminders",
            subtitle = "Re-alert about unread conversations roughly every 2 " +
                "minutes until you open them. Off means a single ping per arrival.",
            checked = settings.unreadReminderEnabled,
            onToggle = settingsRepository::setUnreadReminderEnabled,
        )
        ClickableRow(
            title = "Notification sound",
            subtitle = "Plays the Zitrone tone by default. Tap to pick your " +
                "own sound or silence it.",
            trailing = {
                Text(
                    text = "Change",
                    fontFamily = MonoFamily,
                    fontSize = TypeScale.Sm,
                    color = Lemon,
                )
            },
            onClick = {
                if (!MessagingNotifications.openSoundSettings(context)) {
                    Toast.makeText(
                        context,
                        "Couldn't open notification settings on this device.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )

        // ----- Account -------------------------------------------------------
        SectionHeader("Account")
        ClickableRow(
            title = "Account ID",
            // Registration happens automatically at first launch. If it hasn't
            // landed yet, say why instead of a dead-end "Not registered yet".
            subtitle = accountId ?: when (connectivity) {
                MessagingCoordinator.Connectivity.CONNECTING -> "Setting up your encrypted identity…"
                else -> "Not registered yet — waiting for a connection to the relay"
            },
            subtitleMono = accountId != null,
            onClick = {},
        )
        ClickableRow(
            title = "Delete account",
            subtitle = "Purges every key, prekey and pending envelope. Irreversible.",
            titleColor = ErrorRed,
            onClick = onDeleteAccount,
        )

        // ----- Network -------------------------------------------------------
        SectionHeader("Network")
        // I2P is opt-OUT auto-detect (unlike Tor's opt-in): the toggle only
        // permits Zitrone to USE the local I2P app's router if it's present and its
        // tunnels are ready. When it isn't, the chain falls through to
        // Tor/clearnet on its own — the toggle being on does NOT mean traffic is
        // being routed through I2P. The title and the default-state subtitle are
        // written so a default-on toggle can't be misread as "routing is active"
        // (that misread produced a false "app defaults to I2P" bug report).
        ToggleRow(
            title = "Use I2P when available",
            subtitle = when {
                !settings.i2pEnabled -> "Off — Zitrone won't use I2P even if a router is present."
                transport == TransportState.I2P ->
                    "Active — routing through the I2P app's local HTTP proxy."
                officialRouterInstalled ->
                    "I2P app found — building tunnels. This can take a few minutes."
                // i2pd-only: the reversal hint. Zitrone wired i2pd historically but
                // now uses the official app (real-device: i2pd tunnels unreliable).
                i2pdInstalled ->
                    "i2pd is installed, but Zitrone now uses the official I2P app for relay routing."
                // On + no router: the fresh-install default. Describe the fallback
                // as what Zitrone WILL use, not what's active now — this row is
                // shown regardless of online/offline/connecting, so a present-tense
                // "using your normal connection" would misstate an offline device.
                else -> "On, but no I2P app found — Zitrone will use your normal " +
                    "connection. Install the official I2P app to upgrade automatically."
            },
            checked = settings.i2pEnabled,
            onToggle = settingsRepository::setI2pEnabled,
        )
        // No official I2P app? Offer a path to it (Play first, F-Droid second —
        // our audience skews F-Droid). Shown for i2pd-only users too: the hint
        // above tells them why the official app, these give them the way to it.
        if (!officialRouterInstalled) {
            ClickableRow(
                title = "Get the I2P app",
                subtitle = "Install the official I2P app, then come back — routing turns on automatically.",
                titleColor = Lemon,
                onClick = { openI2pInstall(context) },
            )
            ClickableRow(
                title = "…or get the I2P app on F-Droid",
                subtitle = I2pIntegration.I2P_FDROID_URL,
                subtitleMono = true,
                onClick = { context.startActivitySafely(I2pIntegration.i2pFDroidIntent()) },
            )
        }
        ToggleRow(
            title = "Route through Tor",
            subtitle = if (torAvailable) {
                "Uses Orbot's local SOCKS proxy. Slower, more private."
            } else {
                "Requires Orbot — install it first, then enable this."
            },
            checked = settings.torEnabled,
            enabled = torAvailable,
            onToggle = settingsRepository::setTorEnabled,
        )
        // When Orbot is missing, give an actual way to get it (Issue: the
        // toggle said "install it first" but offered no path). Play Store first,
        // F-Droid as an explicit second option — our audience skews F-Droid.
        if (!torAvailable) {
            ClickableRow(
                title = "Get Orbot",
                subtitle = "Install the Tor proxy app, then come back and enable Tor.",
                titleColor = Lemon,
                onClick = { openOrbotInstall(context) },
            )
            ClickableRow(
                title = "…or get Orbot on F-Droid",
                subtitle = TorIntegration.ORBOT_FDROID_URL,
                subtitleMono = true,
                onClick = { context.startActivitySafely(TorIntegration.orbotFDroidIntent()) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connection",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Text(
                    text = when (transport) {
                        TransportState.I2P -> "Connected via I2P — end-to-end encrypted"
                        TransportState.TOR -> "Connected via Tor — end-to-end encrypted"
                        TransportState.CLEARNET_FALLBACK -> "Connected over clearnet"
                        TransportState.OFFLINE -> "Offline"
                        null -> "Connecting…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (transport) {
                        TransportState.I2P, TransportState.TOR -> VerifiedGreen
                        TransportState.CLEARNET_FALLBACK -> BurnOrange
                        TransportState.OFFLINE -> TextMuted
                        null -> Lemon
                    },
                )
                // Clearnet is always warned (docs/…/transport.ts CLEARNET_WARNING):
                // still E2E-encrypted, but the IP address may be visible.
                if (transport == TransportState.CLEARNET_FALLBACK) {
                    Text(
                        text = "Still end-to-end encrypted, but your IP may be visible. " +
                            "Enable Tor above for full anonymity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
            LemonSliceSecurity(
                level = when (transport) {
                    TransportState.I2P, TransportState.TOR -> 8
                    TransportState.CLEARNET_FALLBACK -> 6
                    TransportState.OFFLINE -> 0
                    null -> 4
                },
                verified = transport == TransportState.TOR || transport == TransportState.I2P,
            )
        }

        // ----- Diagnostics ---------------------------------------------------
        SectionHeader("Diagnostics")
        ClickableRow(
            title = "Connection diagnostics",
            // The self-serve answer to "it just says Connecting…": an on-device,
            // adb-free log of every registration/connection attempt.
            subtitle = "On-device log of registration & connection attempts. " +
                "Open it if you're stuck on “Connecting…”, and share it in a bug report.",
            onClick = onOpenDiagnostics,
        )

        // ----- Appearance ----------------------------------------------------
        SectionHeader("Appearance")
        ClickableRow(
            title = "Theme",
            subtitle = "Dark. There is no light mode — lemons grow in the dark here.",
            trailing = {
                Text(
                    text = "Dark",
                    fontFamily = MonoFamily,
                    fontSize = TypeScale.Sm,
                    color = TextSecondary,
                )
            },
            onClick = {},
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontFamily = MonoFamily,
        fontSize = TypeScale.Xs,
        color = TextMuted,
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundPrimary)
            .padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundElevated)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        // Lemon accents on active toggles — never blue.
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextOnLemon,
                checkedTrackColor = Lemon,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Rind,
                uncheckedBorderColor = BorderColor,
            ),
        )
    }
}

@Composable
private fun ClickableRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    titleColor: androidx.compose.ui.graphics.Color = TextPrimary,
    subtitleMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (subtitleMono) {
                Text(
                    text = subtitle,
                    fontFamily = MonoFamily,
                    fontSize = TypeScale.Xs,
                    color = TextMuted,
                )
            } else {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        trailing?.invoke()
    }
}

/**
 * Opens Orbot's install page. Tries the Play Store first; on a device with no
 * store app (e.g. de-Googled), falls back to the F-Droid listing in a browser.
 * Never throws — a missing handler is a no-op, not a crash.
 */
private fun openOrbotInstall(context: Context) {
    if (!context.startActivitySafely(TorIntegration.orbotInstallIntent())) {
        context.startActivitySafely(TorIntegration.orbotFDroidIntent())
    }
}

/**
 * Opens the official I2P app's install page — Play Store first, F-Droid fallback
 * on a store-less (e.g. de-Googled) device. Never throws; a missing handler is a
 * no-op.
 */
private fun openI2pInstall(context: Context) {
    if (!context.startActivitySafely(I2pIntegration.i2pInstallIntent())) {
        context.startActivitySafely(I2pIntegration.i2pFDroidIntent())
    }
}

/** startActivity guarded against ActivityNotFoundException. Returns success. */
private fun Context.startActivitySafely(intent: android.content.Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
}
