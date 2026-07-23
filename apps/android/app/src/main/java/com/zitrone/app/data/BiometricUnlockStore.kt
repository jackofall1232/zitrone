// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import android.content.SharedPreferences
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.vault.BiometricWrappedKey
import java.util.Base64

/**
 * Persistence for the biometric dual-wrap (posture B): the `{ slotIndex, wrappedVaultKey
 * blob }` pair, in [KeyStoreManager.PREFS_SETTINGS] under new keys. The blob exists ONLY
 * for a biometric-enabled install — its mere presence is the accepted evidence posture
 * ("app biometric on"), and it reveals NOTHING about slot B; the slot index it stores is
 * slot A's, the only real slot in D2c.
 *
 * The persisted blob is a constant [BiometricWrappedKey.BLOB_BYTES] (60), base64-wrapped;
 * nothing here is ever logged. This class holds only the wrapped ciphertext, never a live
 * vault key — the wrap/unwrap crypto lives in
 * [com.zitrone.app.crypto.vault.BiometricVaultKeyCipher].
 *
 * The [prefs] constructor is the seam under test; the [KeyStoreManager] convenience
 * constructor is what production wires (the same PREFS_SETTINGS file the device settings use).
 */
class BiometricUnlockStore(private val prefs: SharedPreferences) {

    constructor(keyStoreManager: KeyStoreManager) :
        this(keyStoreManager.prefs(KeyStoreManager.PREFS_SETTINGS))

    /** The stored wrap, or null when biometric unlock is not enabled (or the blob is off-shape). */
    fun load(): BiometricWrappedKey? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        val slot = prefs.getInt(KEY_SLOT, -1)
        if (slot < 0) return null
        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (blob.size != BiometricWrappedKey.BLOB_BYTES) return null
        return BiometricWrappedKey(slot, blob)
    }

    /** True when a wrap is present (biometric unlock enabled). */
    fun isEnabled(): Boolean = prefs.contains(KEY_BLOB) && prefs.getInt(KEY_SLOT, -1) >= 0

    /** Persist a fresh wrap (enable / re-enable). Constant-size; never logged. */
    fun save(wrap: BiometricWrappedKey) {
        prefs.edit()
            .putInt(KEY_SLOT, wrap.slotIndex)
            .putString(KEY_BLOB, Base64.getEncoder().encodeToString(wrap.blob))
            .apply()
    }

    /** Drop the wrap (disable / invalidation). Idempotent. */
    fun clear() {
        prefs.edit().remove(KEY_SLOT).remove(KEY_BLOB).apply()
    }

    private companion object {
        const val KEY_SLOT = "biometric_vault_slot"
        const val KEY_BLOB = "biometric_vault_blob"
    }
}
