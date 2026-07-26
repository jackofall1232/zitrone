// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import com.zitrone.app.crypto.KeyStoreManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences, persisted via EncryptedSharedPreferences only.
 * All defaults follow the master spec: Tor OFF (opt-in), biometric gate ON,
 * burn-on-read OFF, no default TTL.
 */
class SettingsRepository(private val prefs: android.content.SharedPreferences) {

    /**
     * What production wires — the same [KeyStoreManager.PREFS_SETTINGS] file the device settings and
     * the biometric wrap share. The [prefs] constructor is the seam under test, matching
     * [BiometricUnlockStore]'s shape over the same store; the only other construction site is
     * `AppContainer`, which uses this one.
     */
    constructor(keyStoreManager: KeyStoreManager) :
        this(keyStoreManager.prefs(KeyStoreManager.PREFS_SETTINGS))

    data class Settings(
        val onboardingDone: Boolean = false,
        val biometricRequired: Boolean = true,
        /** features.messaging.disappearing_messages.options_seconds; null = off. */
        val defaultTtlSeconds: Int? = null,
        val burnOnReadDefault: Boolean = false,
        /** Read receipts are user-controlled (features.messaging.read_receipts). */
        val readReceipts: Boolean = true,
        /** Tor via Orbot — strictly opt-in (security.transport.tor). */
        val torEnabled: Boolean = false,
        /**
         * I2P via a local router (the official I2P app). Opt-OUT (default ON) — the ASYMMETRY
         * with Tor is deliberate: I2P is the fixed-primary relay transport, and
         * auto-detecting a running router is cheap and has no downside, so it's
         * on by default and simply falls through the chain when no router is
         * present. Tor stays opt-in because it's a user-chosen fallback.
         */
        val i2pEnabled: Boolean = true,
        /**
         * When true, the chat compose bar shows the lemon-drop (droplet) create
         * affordance. Default false — creation is rarely used, so the toolbar
         * stays clean until the user opts in under Settings → Privacy.
         */
        val lemonDropComposeEnabled: Boolean = false,
        /**
         * Re-alert (roughly every 2 min) about a conversation that stays unread,
         * instead of a single ping. Default ON — the single fixed-id notification
         * otherwise goes silent after the first arrival. Global on/off.
         */
        val unreadReminderEnabled: Boolean = true,
        /**
         * Idle auto-lock timeout in SECONDS while the app is backgrounded (D3). Default 300 (5 min).
         * 0 = lock immediately on background. DEVICE-level, not per-vault: it describes the device
         * and reveals nothing about vault count or which slot is active (see [DeviceSettings]).
         * Rides this batch [load]; no separate startup decrypt. See [autoLockOptionsSeconds].
         */
        val autoLockTimeoutSeconds: Int = 300,
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** TTL choices from features.messaging.disappearing_messages. */
    val ttlOptionsSeconds: List<Int?> = listOf(null, 30, 60, 300, 3600, 86400, 604800)

    /** Idle auto-lock choices (seconds): immediate / 1 min / 5 min / 15 min. Default is 5 min. */
    val autoLockOptionsSeconds: List<Int> = listOf(0, 60, 300, 900)

    fun setOnboardingDone(done: Boolean) = put { putBoolean(KEY_ONBOARDING, done) }

    fun setBiometricRequired(required: Boolean) = put { putBoolean(KEY_BIOMETRIC, required) }

    fun setDefaultTtlSeconds(seconds: Int?) = put { putInt(KEY_TTL, seconds ?: TTL_OFF) }

    fun setBurnOnReadDefault(enabled: Boolean) = put { putBoolean(KEY_BURN_ON_READ, enabled) }

    fun setReadReceipts(enabled: Boolean) = put { putBoolean(KEY_READ_RECEIPTS, enabled) }

    fun setTorEnabled(enabled: Boolean) = put { putBoolean(KEY_TOR, enabled) }

    fun setI2pEnabled(enabled: Boolean) = put { putBoolean(KEY_I2P, enabled) }

    fun setLemonDropComposeEnabled(enabled: Boolean) =
        put { putBoolean(KEY_LEMON_DROP_COMPOSE, enabled) }

    fun setUnreadReminderEnabled(enabled: Boolean) =
        put { putBoolean(KEY_UNREAD_REMINDER, enabled) }

    fun setAutoLockTimeoutSeconds(seconds: Int) = put { putInt(KEY_AUTOLOCK, seconds) }

    /**
     * Return this store to its FRESH-INSTALL baseline, synchronously and provably (0.9.2 Unit W-B
     * round-2 review, BLOCKING).
     *
     * The baseline is "the file exists, holds the two androidx keyset entries, and has no app key" —
     * which is exactly the state `EncryptedSharedPreferences.create()` leaves behind when this
     * repository's constructor opens the store at startup on a never-used device. So the reset is an
     * in-place key clear, NOT a file delete: `EncryptedSharedPreferences`'s `clear()` removes every
     * non-reserved key and deliberately preserves the keysets (verified against the 1.1.0-alpha06
     * bytecode: `clearKeysIfNeeded()` iterates `getAll()`, which skips reserved keys, and guards
     * `isReservedKey` again before each remove). Deleting the file instead would regenerate a fresh
     * random keyset on the next open, and the gate compares CONTENT HASHES — a new keyset is a new
     * difference, not an erased one.
     *
     * `commit()`, not `apply()`: the burn must not lower the durability hold over a write still
     * queued on another thread. Proven by re-reading `all` (which excludes the keyset entries), so
     * the return value is evidence rather than the editor's own optimism.
     *
     * The in-memory [settings] flow is reloaded from the cleared store, so a live observer sees
     * `onboardingDone = false` and the shipped defaults rather than the burned vault's values.
     */
    fun resetToFreshInstallDefaults(): Boolean {
        val committed = runCatching { prefs.edit().clear().commit() }.getOrDefault(false)
        _settings.value = load()
        return committed && runCatching { prefs.all.isEmpty() }.getOrDefault(false)
    }

    private fun put(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _settings.value = load()
    }

    private fun load(): Settings = Settings(
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
        biometricRequired = prefs.getBoolean(KEY_BIOMETRIC, true),
        defaultTtlSeconds = prefs.getInt(KEY_TTL, TTL_OFF).takeIf { it != TTL_OFF },
        burnOnReadDefault = prefs.getBoolean(KEY_BURN_ON_READ, false),
        readReceipts = prefs.getBoolean(KEY_READ_RECEIPTS, true),
        torEnabled = prefs.getBoolean(KEY_TOR, false),
        i2pEnabled = prefs.getBoolean(KEY_I2P, true),
        lemonDropComposeEnabled = prefs.getBoolean(KEY_LEMON_DROP_COMPOSE, false),
        unreadReminderEnabled = prefs.getBoolean(KEY_UNREAD_REMINDER, true),
        autoLockTimeoutSeconds = prefs.getInt(KEY_AUTOLOCK, DEFAULT_AUTOLOCK_SECONDS),
    )

    companion object {
        private const val TTL_OFF = -1
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_BIOMETRIC = "biometric_required"
        private const val KEY_TTL = "default_ttl_seconds"
        private const val KEY_BURN_ON_READ = "burn_on_read_default"
        private const val KEY_READ_RECEIPTS = "read_receipts"
        private const val KEY_TOR = "tor_enabled"
        private const val KEY_I2P = "i2p_enabled"
        private const val KEY_LEMON_DROP_COMPOSE = "lemon_drop_compose_enabled"
        private const val KEY_UNREAD_REMINDER = "unread_reminder_enabled"
        private const val KEY_AUTOLOCK = "auto_lock_timeout_seconds"
        private const val DEFAULT_AUTOLOCK_SECONDS = 300
    }
}
