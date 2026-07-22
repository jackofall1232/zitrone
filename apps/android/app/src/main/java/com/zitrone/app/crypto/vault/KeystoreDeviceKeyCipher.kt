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
import java.security.GeneralSecurityException
import java.security.KeyStore
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
 *  - AES-256-GCM, StrongBox-preferred with a broad explicit fallback for the majority
 *    of devices without a working StrongBox (API < 28 or ANY key-generation failure).
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
        require(dek.size == MASTER_KEY_BYTES) { "dek must be $MASTER_KEY_BYTES bytes" }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // The Keystore drew a fresh random 12-byte IV (setRandomizedEncryptionRequired);
        // read it back and prefix it so the blob is self-describing. JCE GCM appends the
        // 16-byte tag to the ciphertext, giving nonce(12) ‖ ct(32) ‖ tag(16) = 60 bytes.
        // `cipher.iv` is a platform type (ByteArray!); init ran with randomized encryption
        // so an IV is present, but null-guard it before touching nonce.size rather than risk
        // an opaque NPE — a missing IV is a Keystore contract violation, fail LOUDLY.
        val nonce = cipher.iv ?: throw GeneralSecurityException("Keystore cipher returned no IV")
        // Enforce the constant blob shape at the source: a Keystore that returned an off-spec
        // IV length must fail LOUDLY, never silently persist a variable-size blob that would
        // brick the next unwrap or leak a size. Checked BEFORE the encrypt + allocation so an
        // off-spec IV fails fast without doing the crypto work.
        check(nonce.size == NONCE_BYTES) { "unexpected device-key nonce size" }
        val ct = cipher.doFinal(dek)
        val out = ByteArray(nonce.size + ct.size)
        nonce.copyInto(out, 0)
        ct.copyInto(out, nonce.size)
        // Same constant-shape enforcement for the assembled blob (off-spec ciphertext size).
        check(out.size == WRAPPED_KEY_BYTES) { "unexpected wrapped-key size" }
        return out
    }

    override fun unwrapDek(blob: ByteArray): ByteArray? {
        // A wrong-size blob is a clean "no" — kept OUTSIDE the try so it stays a plain null,
        // never routed through the exception path below.
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
        } catch (e: Exception) {
            // ANY Keystore failure is reported as null (mirrors aeadDecrypt's "null means no"),
            // honoring unwrapDek's null-on-ANY-failure contract: an auth failure, a tampered
            // blob, or a key the hardware can no longer honor (GeneralSecurityException); the
            // TEE / StrongBox momentarily unavailable or a provider error (ProviderException,
            // incl. android.security.KeyStoreException); OR a keystore-daemon RUNTIME error that
            // surfaces as a generic NullPointerException / IllegalStateException. Catching every
            // Exception keeps such a daemon crash from ESCAPING and crashing open() — the caller
            // maps null to CorruptImage (which its kdoc documents MAY be transient — a retry /
            // reboot can succeed — and is NEVER auto-repaired). Only Exception is caught, never
            // Error/Throwable, so a LinkageError / OutOfMemoryError still propagates.
            null
        }
    }

    // The loaded KeyStore is a thread-safe handle to the AndroidKeyStore system service, not a
    // copy of key material, so caching it is safe and avoids re-`load(null)`ing on every
    // existingKey / wrap / unwrap. Lazily loaded on first use (mirrors lazy key generation).
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    private fun existingKey(): SecretKey? = try {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Exception) {
        // A corrupted / invalidated Keystore entry (an OS update, a device-credential
        // clear, or hardware-backed key invalidation) makes getEntry throw
        // UnrecoverableEntryException / GeneralSecurityException. Treat it as "no usable
        // key" rather than crash: on the wrap path [getOrCreateKey] then regenerates — and
        // because [wrapDek] runs only from VaultImageStore.create(), which requires NO vault
        // image exists, overwriting the device key loses nothing recoverable; on the unwrap
        // path the caller gets null → CorruptImage, the honest outcome for an image sealed
        // under a key the hardware can no longer produce. Exception-broad (Errors — OOM /
        // LinkageError — still propagate), mirroring [unwrapDek]'s null-on-any-failure posture.
        null
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        // Prefer StrongBox where the hardware has it (API 28+), falling back to the standard
        // hardware-backed Keystore on ANY failure.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (e: Exception) {
                // Broad fallback DELIBERATELY mirrors KeyStoreManager's established master-key
                // posture (crypto/KeyStoreManager.kt:33 — a broad `catch (e: Exception)`): device
                // availability is preferred over StrongBox-strictness, so a persistently-buggy
                // StrongBox that throws a generic ProviderException (not just
                // StrongBoxUnavailableException) can never make key generation — and thus every
                // vault on that device — fail forever. The one-time transient-error-downgrades-the-
                // key risk is the SAME accepted, app-wide tradeoff already made for the master key
                // that protects all existing app storage — not a new corner.
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
