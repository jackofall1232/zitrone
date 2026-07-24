// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The AUTH-GATED biometric cipher for the dual-wrap unlock path (posture B) — a
 * distinct key from [KeystoreDeviceKeyCipher]. It wraps the slot-A VAULT KEY (not
 * the image DEK) under a per-use, biometric-only Android Keystore key so a
 * biometric-enabled install can recover its vault key from a single
 * [android.hardware.biometrics] tap instead of re-deriving from the passphrase.
 *
 * KEY POSTURE (see §3 of the D2c plan):
 *  - AES-256-GCM, alias [ALIAS], NON-exportable, StrongBox-preferred with the same
 *    broad fallback as [KeystoreDeviceKeyCipher] (device availability over
 *    StrongBox-strictness).
 *  - `setUserAuthenticationRequired(true)` + biometric-STRONG only, PER USE: every
 *    unwrap requires a fresh [androidx.biometric.BiometricPrompt] over a
 *    [android.security.keystore] CryptoObject bound to the cipher. There is NO
 *    device-credential fallback on this key — the app PASSPHRASE is the fallback
 *    (biometric-1.1.0 CryptoObject+DEVICE_CREDENTIAL has platform caveats).
 *  - `setInvalidatedByBiometricEnrollment(true)`: enrolling a new fingerprint/face
 *    permanently invalidates the key, so [cipherForDecrypt] then throws
 *    [android.security.keystore.KeyPermanentlyInvalidatedException] and the router
 *    drops to the passphrase field.
 *
 * BLOB SHAPE. `nonce(12) ‖ ct(32) ‖ tag(16)` = [BiometricWrappedKey.BLOB_BYTES]
 * (60) — the SAME constant size as `vault.dek`, so the persisted evidence is a
 * fixed-size blob that reveals only "app biometric is on", never a slot.
 *
 * THIN by design: nothing here but Keystore plumbing and the constant-shape
 * assembly. It never logs and its work never varies with key contents. Exercised
 * only on device (the host tests use a fake DeviceKeyCipher-style cipher).
 */
class BiometricVaultKeyCipher {
    /**
     * ATOMIC ENABLE (0.9.2 enable-atomicity): generate a fresh auth-gated key under this enable's
     * OWN unique alias `PREFIX + aliasId` and return an ENCRYPT-mode [Cipher] to bind into a
     * CryptoObject. Unlike the pre-0.9.2 single-alias design, this **does NOT delete any other key**,
     * so a concurrent or interrupted enable can never destroy an existing binding, and the wrap that
     * a later successful enable persists always references its own just-created alias (INV-1: no
     * orphan). Stale aliases from superseded/abandoned enables are reaped by [deleteAllAliasesExcept]
     * at cold start / disable. The caller authenticates the cipher via BiometricPrompt, then hands it
     * to [sealVaultKey] and persists `{slot, aliasId, blob}`.
     */
    fun newEncryptCipher(aliasId: String): Cipher {
        val key = generateKey(aliasFor(aliasId))
        return Cipher.getInstance(AES_GCM_TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /**
     * A DECRYPT-mode [Cipher] over the key at THIS wrap's own alias (`PREFIX + aliasId`) for the
     * nonce recovered from its stored blob ([BiometricWrappedKey.nonce]). Because each wrap names a
     * unique alias that only its own enable ever created (INV-1), a present key here is ALWAYS the key
     * that sealed the blob — so an AEAD-open failure with a present key cannot arise from a
     * concurrent-enable orphan. Throws [android.security.keystore.KeyPermanentlyInvalidatedException]
     * when a new biometric was enrolled since enable (the router catches it → passphrase field);
     * returns null when the key is absent.
     */
    fun cipherForDecrypt(aliasId: String, nonce: ByteArray): Cipher? {
        val key = existingKey(aliasFor(aliasId)) ?: return null
        return Cipher.getInstance(AES_GCM_TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce))
        }
    }

    /**
     * Seal [vaultKey] (32 bytes) with an already-AUTHENTICATED [encryptCipher] (from
     * [newEncryptCipher] after a successful prompt), returning the constant
     * [BiometricWrappedKey.BLOB_BYTES] blob. Does NOT wipe [vaultKey] — the caller owns
     * and wipes the copy it passed.
     */
    fun sealVaultKey(encryptCipher: Cipher, vaultKey: ByteArray): ByteArray {
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
        val nonce = encryptCipher.iv
        check(nonce != null && nonce.size == NONCE_BYTES) { "unexpected biometric nonce size" }
        val ct = encryptCipher.doFinal(vaultKey)
        val out = ByteArray(nonce.size + ct.size)
        nonce.copyInto(out, 0)
        ct.copyInto(out, nonce.size)
        check(out.size == BiometricWrappedKey.BLOB_BYTES) { "unexpected wrapped-key size" }
        return out
    }

    /**
     * Recover the vault key from [blob]'s ciphertext region with an already-AUTHENTICATED
     * [decryptCipher] (from [cipherForDecrypt] after a successful prompt). Returns live
     * key material the CALLER owns and MUST wipe; returns null on ANY decrypt failure (a
     * tampered blob, or a key invalidated between init and doFinal). The returned array is
     * exactly [VAULT_KEY_BYTES].
     */
    fun openVaultKey(decryptCipher: Cipher, blob: ByteArray): ByteArray? {
        if (blob.size != BiometricWrappedKey.BLOB_BYTES) return null
        return try {
            decryptCipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: Exception) {
            // Any decrypt failure → null → the router drops to the passphrase, mirroring
            // KeystoreDeviceKeyCipher.unwrapDek's null-on-ANY-failure posture. Beyond a tampered
            // blob (AEADBadTagException), a key invalidated between init and doFinal surfaces as
            // BadPaddingException / IllegalBlockSizeException (KeyStoreException-caused) and a
            // keystore-daemon glitch as a generic runtime exception — none may crash the unlock.
            // Only Exception is caught; Error / OutOfMemoryError still propagate.
            null
        }
    }

    /** Whether the key for [aliasId] currently exists. */
    fun keyExists(aliasId: String): Boolean = existingKey(aliasFor(aliasId)) != null

    /** Delete ONE enable's key (an abandoned/refused enable's own alias). Idempotent. */
    fun deleteKey(aliasId: String) = deleteAlias(aliasFor(aliasId))

    /**
     * Reap stale biometric aliases (GC): delete every `PREFIX*` Keystore entry EXCEPT the one the
     * current persisted wrap references ([keepAliasId], or null to delete ALL — used by disable /
     * account-delete). Best-effort and idempotent. MUST be called only at quiescent points (cold-start
     * init; disable) — never concurrently with an in-flight enable — so it can never delete the alias
     * the current wrap references (INV-1). Leftover aliases it fails to reap are harmless: unlock uses
     * the wrap's own alias, not an enumeration.
     */
    fun deleteAllAliasesExcept(keepAliasId: String?) {
        val keep = keepAliasId?.let { aliasFor(it) }
        val toDelete = try {
            keyStore.aliases().toList().filter { it.startsWith(PREFIX) && it != keep }
        } catch (e: Exception) {
            return // enumeration hiccup → best-effort; leftover aliases are harmless
        }
        toDelete.forEach { deleteAlias(it) }
    }

    private fun deleteAlias(alias: String) {
        try {
            keyStore.deleteEntry(alias)
        } catch (e: Exception) {
            // A missing / already-cleared entry is fine — deletion is idempotent and must
            // never throw. Errors (OOM / LinkageError) still propagate.
        }
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    private fun existingKey(alias: String): SecretKey? = try {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Exception) {
        // A corrupted / invalidated entry (getEntry throwing UnrecoverableEntryException /
        // GeneralSecurityException) reads as "no usable key" → the router falls back to the
        // passphrase, exactly the invalidation outcome. Errors still propagate.
        null
    }

    private fun generateKey(alias: String): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: Exception) {
                // Broad fallback mirrors KeystoreDeviceKeyCipher / KeyStoreManager: a
                // persistently-buggy StrongBox must never make biometric enable fail forever.
            }
        }
        return generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(MASTER_KEY_BYTES * 8)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Per-use (timeout 0), biometric-STRONG only — no device-credential on this key.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // Pre-R equivalent: -1 = authenticate on EVERY use, which binds to a biometric
            // CryptoObject prompt (no timed device-credential window).
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun aliasFor(aliasId: String): String {
        require(aliasId.matches(ALIAS_ID_SHAPE)) { "invalid biometric aliasId" }
        return PREFIX + aliasId
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Prefix for this install's per-enable auth-gated keys. Each enable appends its own random
         * [ALIAS_ID_BYTES]-byte hex id (0.9.2 enable-atomicity — was a single fixed alias pre-0.9.2).
         */
        const val PREFIX = "zitrone_vault_biometric_key_"

        private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"

        /** Bytes of CSPRNG entropy in an enable's aliasId — 16 bytes = 128 bits, collision-negligible. */
        const val ALIAS_ID_BYTES = 16

        /** A fresh, unique alias id (lowercase hex) for one enable. */
        fun newAliasId(): String {
            val b = ByteArray(ALIAS_ID_BYTES)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** Exactly `2 * ALIAS_ID_BYTES` lowercase hex chars — validated before it ever reaches a Keystore alias. */
        private val ALIAS_ID_SHAPE = Regex("^[0-9a-f]{" + (ALIAS_ID_BYTES * 2) + "}$")

        /** Whether [aliasId] is a well-formed alias id (defends the persisted field against tampering). */
        fun isValidAliasId(aliasId: String): Boolean = aliasId.matches(ALIAS_ID_SHAPE)
    }
}

/**
 * The persisted biometric wrap: `{ slotIndex, aliasId, blob }` — the ONLY evidence a biometric
 * enable leaves. The [blob] is a constant [BLOB_BYTES] (60) `nonce ‖ ct ‖ tag`; the [slotIndex] is
 * which image slot the wrapped key opens; [aliasId] (0.9.2 enable-atomicity) names the per-enable
 * Keystore key that sealed this blob (`PREFIX + aliasId`), so each wrap references its OWN key and no
 * concurrent/interrupted enable can orphan it. None is ever logged.
 */
class BiometricWrappedKey(
    val slotIndex: Int,
    val aliasId: String,
    val blob: ByteArray,
) {
    init {
        require(blob.size == BLOB_BYTES) { "biometric blob must be $BLOB_BYTES bytes" }
        require(BiometricVaultKeyCipher.isValidAliasId(aliasId)) { "invalid biometric aliasId" }
    }

    /** The GCM nonce prefix — hand to [BiometricVaultKeyCipher.cipherForDecrypt]. */
    val nonce: ByteArray get() = blob.copyOfRange(0, NONCE_BYTES)

    companion object {
        /** `nonce(12) ‖ ct(32) ‖ tag(16)` — the same fixed shape as `vault.dek`. */
        const val BLOB_BYTES: Int = NONCE_BYTES + VAULT_KEY_BYTES + AEAD_TAG_BYTES
    }
}
