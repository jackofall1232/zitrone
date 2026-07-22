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
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The production [DeviceKeyCipher]: wraps the vault DEK under a non-exportable,
 * hardware-backed Android Keystore key. Deliberately THIN — nothing here but
 * Keystore plumbing; all vault semantics live in [VaultImageStore]. It is
 * exercised only on device / by instrumentation (the host unit tests inject a
 * fixed-key fake), so keep the logic small enough to trust by inspection.
 *
 * Key posture mirrors KeyStoreManager's MasterKey (crypto/KeyStoreManager.kt):
 *  - AES-256-GCM, StrongBox-preferred with an explicit fallback for the majority
 *    of devices without a StrongBox (API < 28 or a StrongBoxUnavailableException).
 *  - `setUserAuthenticationRequired(false)` (D2: the device key is NOT auth-gated —
 *    a slot's own passphrase / biometric gates the slot; this key only makes the
 *    image undecryptable off-device).
 *  - `setRandomizedEncryptionRequired(true)`, so the Keystore draws a fresh random
 *    GCM IV on every wrap; that IV is read back from the cipher and prefixed to the
 *    blob so [unwrapDek] can reconstruct it.
 *  - Lazy-on-first-use generation: the key is created the first time a vault image
 *    is created, not before.
 *
 * SLOT-AGNOSTIC. One key, one constant-size blob per install; no logging; no
 * behavior that varies with DEK contents.
 */
class KeystoreDeviceKeyCipher(
    private val alias: String = DEFAULT_ALIAS,
) : DeviceKeyCipher {

    override fun wrapDek(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // The Keystore drew a fresh random 12-byte IV (setRandomizedEncryptionRequired);
        // read it back and prefix it so the blob is self-describing. JCE GCM appends the
        // 16-byte tag to the ciphertext, giving nonce(12) ‖ ct(32) ‖ tag(16) = 60 bytes.
        // `cipher.iv` is a platform type (ByteArray!); init ran with randomized encryption
        // so an IV is present, but null-guard it before touching nonce.size rather than risk
        // an opaque NPE — a missing IV is a Keystore contract violation, fail LOUDLY.
        val nonce = cipher.iv ?: throw GeneralSecurityException("Keystore cipher returned no IV")
        val ct = cipher.doFinal(dek)
        val out = ByteArray(nonce.size + ct.size)
        nonce.copyInto(out, 0)
        ct.copyInto(out, nonce.size)
        // Enforce the constant blob shape at the source: a Keystore that returned an
        // off-spec IV length or ciphertext size must fail LOUDLY here, never silently
        // persist a variable-size blob that would brick the next unwrap or leak a size.
        check(nonce.size == NONCE_BYTES) { "unexpected device-key nonce size" }
        check(out.size == WRAPPED_KEY_BYTES) { "unexpected wrapped-key size" }
        return out
    }

    override fun unwrapDek(blob: ByteArray): ByteArray? {
        if (blob.size != WRAPPED_KEY_BYTES) return null
        return try {
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                // Nonce is the first NONCE_BYTES of blob.
                GCMParameterSpec(AEAD_TAG_BYTES * 8, blob, 0, NONCE_BYTES),
            )
            cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            // Tag failure / tampered blob / a key the hardware can no longer honor —
            // all reported the same way, as "no" (mirrors aeadDecrypt). The caller
            // treats null as a corrupt image and never silently recreates.
            null
        } catch (e: ProviderException) {
            // Android Keystore RUNTIME failure — incl. android.security.KeyStoreException,
            // which subclasses ProviderException: the TEE / StrongBox is momentarily
            // unavailable or the provider errored. Reported the same "no". Unlike a tag
            // failure this MAY be transient, so the resulting CorruptImage need not be
            // permanent (a retry / reboot can succeed); the caller escalates regardless.
            null
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? =
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        // Prefer StrongBox where the hardware has it (API 28+). Key generation throws
        // on devices without it, so fall back to the standard hardware-backed Keystore
        // explicitly — the same posture KeyStoreManager uses for its MasterKey.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // This device genuinely has no StrongBox — the ONLY reason to permanently
                // downgrade to the standard hardware-backed key. Catch ONLY this: any OTHER
                // exception (a transient TEE / provider error, e.g. a ProviderException) must
                // PROPAGATE so it surfaces / retries, never silently and permanently wrapping
                // the DEK under a weaker non-StrongBox key. The block is SDK_INT >= P guarded,
                // so this class (API 28+) always resolves here.
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
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** The single device key that wraps this install's vault DEK. */
        const val DEFAULT_ALIAS = "zitrone_vault_device_key"

        /** Portable AES-256-GCM via the platform JCE provider (see [LibsodiumVaultOps]). */
        const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    }
}
