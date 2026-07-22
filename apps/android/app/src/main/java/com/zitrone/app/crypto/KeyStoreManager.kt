// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Gatekeeper for everything written to disk.
 *
 * Critical rule: plaintext keys are NEVER stored. All local persistence goes
 * through [EncryptedSharedPreferences], whose master key lives in the Android
 * Keystore System (hardware-backed/StrongBox where the device supports it)
 * and never leaves secure hardware. The app has no other persistence layer.
 */
class KeyStoreManager(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        // Prefer StrongBox where the hardware has it. Key generation throws
        // StrongBoxUnavailableException on devices without it (most of them),
        // so fall back to the standard hardware-backed Keystore explicitly.
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
    }

    private val cache = mutableMapOf<String, SharedPreferences>()

    /** Opens (or creates) an encrypted preferences file. */
    @Synchronized
    fun prefs(name: String): SharedPreferences = cache.getOrPut(name) {
        EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        const val PREFS_SIGNAL_STORE = "zitrone_signal_store"
        const val PREFS_SETTINGS = "zitrone_settings"
        const val PREFS_AUTH = "zitrone_auth"

        // The contact roster (display names + pinned identity keys + verified/
        // key-changed flags). Its own encrypted file, separate from the Signal
        // store so a roster read glitch can never reach key material. See
        // data/RosterStore.kt for WHY the roster must be persisted at all.
        const val PREFS_CONTACTS = "zitrone_contacts"
    }
}
