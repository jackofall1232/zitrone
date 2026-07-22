// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.net.TransportResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Device-scoped, pre-unlock settings view.
 *
 * These are the settings that must be readable BEFORE any vault is unlocked —
 * they gate the unlock flow and choose the network transport — and are
 * device-wide, not per-vault. D1 keeps them EXACTLY where they live today: the
 * legacy `zitrone_settings` EncryptedSharedPreferences behind
 * [SettingsRepository]. This class is only a typed view over that device-level
 * subset, so NO DATA MOVES — every accessor reads the very same key/value
 * [SettingsRepository] already reads.
 *
 * The vault-scoped fields (ttl / burn-on-read / read-receipts /
 * lemon-drop-compose / unread-reminder) are deliberately NOT surfaced here and
 * stay on [SettingsRepository]; D2/D5 move those into the vault.
 *
 * The one genuinely new field, [autoLockTimeoutSeconds], has no legacy key and
 * simply defaults to [DEFAULT_AUTO_LOCK_SECONDS] until written. It is UNUSED in
 * D1 (D3 wires the auto-lock timer to it).
 */
class DeviceSettings(
    keyStoreManager: KeyStoreManager,
    private val source: SettingsRepository,
) {
    // The SAME encrypted store SettingsRepository uses. The autolock key (which
    // SettingsRepository does not know about) lives here too, so nothing new is
    // created and no existing value moves.
    private val prefs = keyStoreManager.prefs(KeyStoreManager.PREFS_SETTINGS)

    /** Onboarding completed. Backed by SettingsRepository's `onboarding_done`. */
    val onboardingDone: Boolean get() = source.settings.value.onboardingDone

    /**
     * Whether the biometric/credential unlock gate is required. This is today's
     * `biometricRequired`, surfaced under the vault-neutral name `unlockRequired`
     * — same `biometric_required` key, same value.
     */
    val unlockRequired: Boolean get() = source.settings.value.biometricRequired

    /** Tor-via-Orbot toggle. Backed by `tor_enabled`. */
    val torEnabled: Boolean get() = source.settings.value.torEnabled

    /** I2P-via-router toggle. Backed by `i2p_enabled`. */
    val i2pEnabled: Boolean get() = source.settings.value.i2pEnabled

    /**
     * Reactive transport inputs (the I2P/Tor toggles) for the resolver. This is
     * the exact flow AppContainer built inline before the D1 split, relocated
     * here unchanged so behaviour is identical.
     */
    val transportInputs: Flow<TransportResolver.Inputs> =
        source.settings.map { TransportResolver.Inputs(it.i2pEnabled, it.torEnabled) }

    /** Snapshot of [transportInputs] for the eager `stateIn` seed. */
    fun transportInputsSnapshot(): TransportResolver.Inputs =
        source.settings.value.let { TransportResolver.Inputs(it.i2pEnabled, it.torEnabled) }

    /**
     * Idle auto-lock timeout, in seconds (default 5 min). NEW in D1 with no
     * legacy value — it defaults until written. UNUSED in D1; D3 consumes it.
     */
    val autoLockTimeoutSeconds: Int
        get() = prefs.getInt(KEY_AUTO_LOCK_TIMEOUT, DEFAULT_AUTO_LOCK_SECONDS)

    companion object {
        const val DEFAULT_AUTO_LOCK_SECONDS = 300
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout_seconds"
    }
}
