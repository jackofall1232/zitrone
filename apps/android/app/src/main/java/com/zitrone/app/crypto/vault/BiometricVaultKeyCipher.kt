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
class BiometricVaultKeyCipher(
    private val alias: String = ALIAS,
) {
    /**
     * Generate a FRESH auth-gated key (replacing any prior one — enable overwrites)
     * and return an ENCRYPT-mode [Cipher] to bind into a CryptoObject. The caller
     * authenticates it via BiometricPrompt, then hands it to [sealVaultKey].
     */
    fun newEncryptCipher(): Cipher {
        deleteKey()
        val key = generateKey()
        return Cipher.getInstance(AES_GCM_TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /**
     * A DECRYPT-mode [Cipher] over the existing key for the nonce recovered from a
     * stored blob ([BiometricWrappedKey.nonce]), to bind into a CryptoObject for the
     * unlock prompt. Throws [android.security.keystore.KeyPermanentlyInvalidatedException]
     * when a new biometric was enrolled since enable (the router catches it and drops to
     * the passphrase field); returns null when the key is absent.
     */
    fun cipherForDecrypt(nonce: ByteArray): Cipher? {
        val key = existingKey() ?: return null
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
     * key material the CALLER owns and MUST wipe; returns null on AEAD failure (a tampered
     * blob). The returned array is exactly [VAULT_KEY_BYTES].
     */
    fun openVaultKey(decryptCipher: Cipher, blob: ByteArray): ByteArray? {
        if (blob.size != BiometricWrappedKey.BLOB_BYTES) return null
        return try {
            decryptCipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: javax.crypto.AEADBadTagException) {
            null
        }
    }

    /** Whether the auth-gated key currently exists (enable created it; disable/invalidate deletes it). */
    fun keyExists(): Boolean = existingKey() != null

    /** Delete the key (disable / re-enable / permanent invalidation). Idempotent. */
    fun deleteKey() {
        try {
            keyStore.deleteEntry(alias)
        } catch (e: Exception) {
            // A missing / already-cleared entry is fine — disable is idempotent and must
            // never throw. Errors (OOM / LinkageError) still propagate.
        }
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    private fun existingKey(): SecretKey? = try {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Exception) {
        // A corrupted / invalidated entry (getEntry throwing UnrecoverableEntryException /
        // GeneralSecurityException) reads as "no usable key" → the router falls back to the
        // passphrase, exactly the invalidation outcome. Errors still propagate.
        null
    }

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (e: Exception) {
                // Broad fallback mirrors KeystoreDeviceKeyCipher / KeyStoreManager: a
                // persistently-buggy StrongBox must never make biometric enable fail forever.
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** The single auth-gated key that wraps this install's slot-A vault key. */
        const val ALIAS = "zitrone_vault_biometric_key"

        const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * The persisted biometric wrap: `{ slotIndex, blob }` — the ONLY evidence a biometric
 * enable leaves. The [blob] is a constant [BLOB_BYTES] (60) `nonce ‖ ct ‖ tag`; the
 * [slotIndex] is which image slot the wrapped key opens. Neither is ever logged.
 */
class BiometricWrappedKey(
    val slotIndex: Int,
    val blob: ByteArray,
) {
    init {
        require(blob.size == BLOB_BYTES) { "biometric blob must be $BLOB_BYTES bytes" }
    }

    /** The GCM nonce prefix — hand to [BiometricVaultKeyCipher.cipherForDecrypt]. */
    val nonce: ByteArray get() = blob.copyOfRange(0, NONCE_BYTES)

    companion object {
        /** `nonce(12) ‖ ct(32) ‖ tag(16)` — the same fixed shape as `vault.dek`. */
        const val BLOB_BYTES: Int = NONCE_BYTES + VAULT_KEY_BYTES + AEAD_TAG_BYTES
    }
}
