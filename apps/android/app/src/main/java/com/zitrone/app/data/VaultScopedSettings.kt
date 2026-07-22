// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import com.zitrone.app.crypto.vault.VaultRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The subset of [SettingsRepository.Settings] that is PER-VAULT and therefore belongs
 * inside the sealed keystore, not the device.
 *
 * These five are properties of a specific identity's messaging behaviour, so a decoy
 * vault and a real vault can legitimately hold DIFFERENT values, and neither should leave
 * a trace once locked. The DEVICE-level settings (onboarding done, biometric gate, Tor,
 * I2P, autolock) are deliberately NOT here — they describe the device/app and are shared
 * across vaults; PR-D routes those to a separate `DeviceSettings`.
 *
 * Defaults mirror [SettingsRepository.Settings] exactly for these fields, so a fresh vault
 * behaves identically to today's fresh install.
 */
data class VaultScopedSettings(
    /** features.messaging.disappearing_messages default TTL; null = off. */
    val defaultTtlSeconds: Int? = null,
    /** Burn-on-read default for newly composed messages. */
    val burnOnReadDefault: Boolean = false,
    /** Whether outbound read receipts are sent (features.messaging.read_receipts). */
    val readReceipts: Boolean = true,
    /** Whether the compose bar shows the lemon-drop (QR dead-drop) create affordance. */
    val lemonDropComposeEnabled: Boolean = false,
    /** Whether an unread conversation re-alerts (~every 2 min) instead of a single ping. */
    val unreadReminderEnabled: Boolean = true,
)

/**
 * The settings surface [com.zitrone.app.ui.SettingsScreen] needs, over a sealed vault via
 * [VaultRuntime]. Its shape MIRRORS [SettingsRepository] — a [StateFlow] of the current
 * values plus one setter per field — so the PR-D rewiring of the settings screen is
 * mechanical (swap the repository type; the call sites are unchanged).
 *
 * The [StateFlow] is seeded from the vault's current state and re-emitted after each
 * mutate (same `_settings.value = load()` pattern SettingsRepository uses). Writes are
 * COALESCED — a preference toggle is not durability-critical, so it rides the session's
 * normal flush ceiling rather than forcing a flush-before-ack.
 *
 * Isolated unit: the settings screen is NOT switched to it until PR-D.
 */
class VaultSettingsStore(
    private val runtime: VaultRuntime,
) {
    private val _settings = MutableStateFlow(runtime.read { it.settings })

    /** The current vault-scoped settings, updated on every setter. */
    val settings: StateFlow<VaultScopedSettings> = _settings.asStateFlow()

    fun setDefaultTtlSeconds(seconds: Int?) = update { it.copy(defaultTtlSeconds = seconds) }

    fun setBurnOnReadDefault(enabled: Boolean) = update { it.copy(burnOnReadDefault = enabled) }

    fun setReadReceipts(enabled: Boolean) = update { it.copy(readReceipts = enabled) }

    fun setLemonDropComposeEnabled(enabled: Boolean) =
        update { it.copy(lemonDropComposeEnabled = enabled) }

    fun setUnreadReminderEnabled(enabled: Boolean) =
        update { it.copy(unreadReminderEnabled = enabled) }

    /** Apply [transform] to the settings inside a mutate, publishing the new value UNDER the lock. */
    private fun update(transform: (VaultScopedSettings) -> VaultScopedSettings) {
        runtime.mutate { state ->
            state.settings = transform(state.settings)
            // Publish INSIDE the mutate (under the runtime lock) so the StateFlow is ordered with
            // the mutation: two concurrent setters serialize here and can't publish out of order,
            // so the flow never ends stale vs the vault state. The StateFlow assignment is a cheap
            // non-blocking store that never re-enters the runtime, so it is lock-safe.
            _settings.value = state.settings
        }
    }
}
