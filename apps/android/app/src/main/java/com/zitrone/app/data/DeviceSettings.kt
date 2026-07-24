// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

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
 * subset, delegating every accessor to [SettingsRepository]'s already-loaded,
 * in-memory `settings` StateFlow — so NO DATA MOVES, no new storage is created,
 * and no additional decryption happens (it reads the same values
 * [SettingsRepository] already loaded).
 *
 * The vault-scoped fields (ttl / burn-on-read / read-receipts /
 * lemon-drop-compose / unread-reminder) are deliberately NOT surfaced here and
 * stay on [SettingsRepository]; D2/D5 move those into the vault.
 *
 * The idle auto-lock timeout ([autoLockTimeoutSeconds]) is device-level and lives on
 * [SettingsRepository] alongside the other device fields (D3). It rides that repository's
 * existing batch load — so it adds NO new startup decrypt (the concern that kept it out of D1
 * was a SEPARATE per-field decrypt, not a field in the already-loaded [SettingsRepository.Settings])
 * — and its setter re-emits the same reactive `settings` StateFlow. Kept device-level, not
 * per-vault, so it reveals nothing about vault count or which slot is active.
 */
class DeviceSettings(
    private val source: SettingsRepository,
) {
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
     * Idle auto-lock timeout (seconds) while backgrounded; 0 = immediate. Read as a snapshot at the
     * moment the app backgrounds (the vault is unlocked then, so the value is current). Backed by
     * `auto_lock_timeout_seconds`; default 300.
     */
    val autoLockTimeoutSeconds: Int get() = source.settings.value.autoLockTimeoutSeconds

    /**
     * Reactive transport inputs (the I2P/Tor toggles) for the resolver. This is
     * the exact flow AppContainer built inline before the D1 split, relocated
     * here unchanged so behaviour is identical.
     */
    val transportInputs: Flow<TransportResolver.Inputs> =
        source.settings.map { TransportResolver.Inputs(it.i2pEnabled, it.torEnabled) }

    /** Snapshot of [transportInputs] for the eager `stateIn` seed. */
    val transportInputsSnapshot: TransportResolver.Inputs
        get() = source.settings.value.let { TransportResolver.Inputs(it.i2pEnabled, it.torEnabled) }
}
