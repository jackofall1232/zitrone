// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import android.content.SharedPreferences
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
import com.zitrone.app.crypto.vault.BiometricWrappedKey
import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
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

    /** The stored wrap, or null when biometric unlock is not enabled (or any field is off-shape). */
    fun load(): BiometricWrappedKey? = try {
        loadUnsafe()
    } catch (e: Exception) {
        // Hostile / corrupt prefs — a field stored with the WRONG TYPE makes the typed getters throw
        // ClassCastException (e.g. a forensic edit turning the aliasId string into an int) — must read as
        // NOT enabled, never crash isEnabled()/boundAliasId()/the unlock coroutine. Errors still propagate.
        null
    }

    private fun loadUnsafe(): BiometricWrappedKey? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        val slot = prefs.getInt(KEY_SLOT, -1)
        // Validate against the VAULT POOL (1..SLOT_COUNT-1), not just >= 0: a corrupted/tampered prefs int
        // — including slot 0, the burn credential, which is NOT a biometric-wrappable vault (F9) — must
        // read as "not enabled" here, never reach unlockWithKey's require(slotIndex in VAULT_SLOT_RANGE)
        // and crash the unlock coroutine. Biometric is A-only, and A always lives in the pool.
        if (slot !in VAULT_SLOT_RANGE) return null
        // aliasId (0.9.2): names the per-enable Keystore key that sealed this wrap. A MISSING aliasId is a
        // pre-0.9.2 (single-alias) or tampered wrap → read as NOT enabled so the user simply re-enrolls
        // (no migration; consistent with the fresh-install / storage-format stance). A malformed aliasId
        // must never reach a Keystore alias, so validate its shape here too.
        val aliasId = prefs.getString(KEY_ALIAS_ID, null) ?: return null
        if (!BiometricVaultKeyCipher.isValidAliasId(aliasId)) return null
        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (blob.size != BiometricWrappedKey.BLOB_BYTES) return null
        return BiometricWrappedKey(slot, aliasId, blob)
    }

    /**
     * True only when a VALID wrap is present (biometric unlock enabled). Delegates to [load] so a
     * present-but-malformed blob (bad base64 / wrong length) or an out-of-range slot reads as NOT
     * enabled — otherwise the lock screen would advertise a biometric button that [load] resolves
     * to null and cannot actually drive (it would silently drop to the passphrase either way).
     */
    fun isEnabled(): Boolean = load() != null

    /**
     * The vault slot the CURRENT wrap is bound to, or null when there is no valid wrap. Reads the
     * SAME plaintext slot metadata [load]/`unlockWithBiometric` already use (adds no new persisted
     * field, no biometric auth, no new artifact) — so `AppContainer.enableBiometricFromSession` can
     * enforce the single-wrap, never-repointed invariant (OQ4) at the WRITE layer: enable is allowed
     * only when this is null (first-enable-wins, OQ-A) or equals the enabling session's slot.
     */
    fun boundSlotIndex(): Int? = load()?.slotIndex

    /**
     * The aliasId of the CURRENT valid wrap, or null when none — the alias GC
     * ([BiometricVaultKeyCipher.deleteAllAliasesExcept]) must KEEP at cold start / after enable, so it
     * reaps only superseded/abandoned aliases and never the one the live wrap references (INV-1).
     */
    fun boundAliasId(): String? = load()?.aliasId

    /**
     * Persist a fresh wrap (enable / re-enable). Constant-size; never logged. Low-level primitive:
     * it does NOT itself enforce the A-bound never-repoint invariant (OQ4). That invariant is enforced
     * by the SOLE production caller, `AppContainer.enableBiometricFromSession`, which fail-closes via
     * [boundSlotIndex]/`biometricEnableAllowed` before calling here (and the entrypoint pre-checks
     * before the Keystore key is even touched). Any NEW caller of `save` MUST apply the same guard —
     * do not repoint the single wrap to a different slot without a prior [clear].
     */
    fun save(wrap: BiometricWrappedKey) {
        prefs.edit()
            .putInt(KEY_SLOT, wrap.slotIndex)
            .putString(KEY_ALIAS_ID, wrap.aliasId)
            .putString(KEY_BLOB, Base64.getEncoder().encodeToString(wrap.blob))
            .apply()
    }

    /** Drop the wrap (disable / invalidation). Idempotent. */
    fun clear() {
        prefs.edit().remove(KEY_SLOT).remove(KEY_ALIAS_ID).remove(KEY_BLOB).apply()
    }

    private companion object {
        const val KEY_SLOT = "biometric_vault_slot"
        const val KEY_ALIAS_ID = "biometric_vault_alias_id"
        const val KEY_BLOB = "biometric_vault_blob"
    }
}
