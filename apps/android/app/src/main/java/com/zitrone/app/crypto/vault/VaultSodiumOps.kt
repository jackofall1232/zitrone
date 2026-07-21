// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto.vault

import com.goterl.lazysodium.Sodium
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto surface the vault primitive needs, and nothing more. Kept a plain
 * interface so tests can inject a fast, deterministic key deriver while still
 * exercising the REAL AEAD byte path.
 *
 * Two backends by deliberate design ([LibsodiumVaultOps]):
 *  - **Argon2id** runs on libsodium (`crypto_pwhash`, via lazysodium's raw JNA
 *    bindings — the SAME `Sodium` base class LemonDropSodiumOps uses), so the
 *    KDF is identical on device (`SodiumAndroid`, prebuilt .so per ABI, no NDK)
 *    and in host tests (`SodiumJava`). libsodium's Argon2id is pure software —
 *    available on every device.
 *  - **AES-256-GCM** runs on the JDK/Android `javax.crypto` provider, NOT
 *    libsodium. This is the important portability fix: libsodium's
 *    `crypto_aead_aes256gcm` is HARDWARE-GATED (AES-NI / ARMv8 Crypto
 *    Extensions) and simply unavailable on many low-end ARM devices — using it
 *    would crash vault unlock on exactly the hardware we most need to support.
 *    `javax.crypto`'s "AES/GCM/NoPadding" is available on every Android device
 *    (software fallback when there's no hardware), and AES-256-GCM is AES-256-GCM
 *    regardless of implementation, so the output stays byte-identical to the web
 *    reference's WebCrypto AES-GCM (same algorithm, same key/nonce/AAD).
 *
 * Byte-compatibility with the web reference (packages/crypto): Argon2id with the
 * exact kdf.ts parameters, and both the wrapped-key layer (vault.ts) and the
 * payload layer (apps/web storage.ts) are AES-256-GCM with a 12-byte nonce. The
 * goal is auditability against the reference, not cross-device image sharing.
 */
interface VaultSodiumOps {
    /**
     * Argon2id (crypto_pwhash, ALG_ARGON2ID13) with the exact kdf.ts params:
     * 64 MiB memory, 3 iterations, 16-byte salt, 32-byte output. libsodium
     * fixes parallelism internally (=1); there is no lanes parameter to set.
     *
     * CPU-HEAVY: ~64 MiB and hundreds of milliseconds per call. A full unlock
     * runs this SLOT_COUNT times; callers on a UI thread MUST run it (and
     * [tryPassphrase]) off the main thread.
     */
    fun argon2idDeriveKey(password: ByteArray, salt: ByteArray): ByteArray

    /**
     * AES-256-GCM seal. Output layout: nonce(12) || ciphertext || tag(16),
     * exactly as aead.ts aeadEncrypt. A fresh random nonce is drawn on EVERY
     * call — nonce reuse under GCM is catastrophic, so no nonce is accepted
     * from the caller. This single primitive backs BOTH the wrapped-key layer
     * (with SLOT_AD) and the payload layer (with PAYLOAD_AD).
     */
    fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * AES-256-GCM open of a nonce(12) || ciphertext || tag(16) box. Returns null
     * on any authentication failure — a wrong key, a filler slot, or tampering,
     * all indistinguishable by design (mirrors aead.ts aeadDecrypt throwing,
     * which vault.ts / storage.ts treat as "no match").
     */
    fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray): ByteArray?

    /** Cryptographically random bytes from the platform CSPRNG. */
    fun randomBytes(length: Int): ByteArray
}

/**
 * Production [VaultSodiumOps]. Construct over `SodiumAndroid` on device and
 * `SodiumJava` in host tests — both are `Sodium`.
 */
class LibsodiumVaultOps(private val sodium: Sodium) : VaultSodiumOps {

    override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val out = ByteArray(MASTER_KEY_BYTES)
        val rc = sodium.crypto_pwhash(
            out,
            out.size.toLong(),
            password,
            password.size.toLong(),
            salt,
            ARGON2ID_OPSLIMIT,
            NativeLong(ARGON2ID_MEMLIMIT_BYTES),
            ARGON2ID_ALG,
        )
        check(rc == 0) { "crypto_pwhash failed (rc=$rc) — out of memory?" }
        return out
    }

    override fun aeadEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == MASTER_KEY_BYTES) { "AES-256-GCM key must be $MASTER_KEY_BYTES bytes" }
        // Fresh random 12-byte nonce per call — nonce reuse under GCM is
        // catastrophic, so the caller never supplies one.
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce),
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        // JCE GCM appends the 16-byte tag to the ciphertext, so doFinal returns
        // ciphertext || tag — matching aead.ts's WebCrypto layout exactly.
        val cipherAndTag = cipher.doFinal(plaintext)
        return nonce + cipherAndTag
    }

    override fun aeadDecrypt(
        key: ByteArray,
        box: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? {
        require(key.size == MASTER_KEY_BYTES) { "AES-256-GCM key must be $MASTER_KEY_BYTES bytes" }
        if (box.size < NONCE_BYTES + AEAD_TAG_BYTES) return null
        val nonce = box.copyOfRange(0, NONCE_BYTES)
        val cipherAndTag = box.copyOfRange(NONCE_BYTES, box.size)
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce),
            )
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            cipher.doFinal(cipherAndTag)
        } catch (e: AEADBadTagException) {
            // Tag failure: wrong key, a filler slot, or tampering — all reported
            // the same way, as "no match" (mirrors aead.ts aeadDecrypt throwing).
            null
        }
    }

    override fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        sodium.randombytes_buf(out, length)
        return out
    }

    private companion object {
        /** kdf.ts ARGON2ID_PARAMS.iterations (opslimit). */
        const val ARGON2ID_OPSLIMIT: Long = 3L

        /** kdf.ts ARGON2ID_PARAMS.memLimitBytes = 65536 * 1024 = 64 MiB. */
        const val ARGON2ID_MEMLIMIT_BYTES: Long = 65536L * 1024L

        /** crypto_pwhash_ALG_ARGON2ID13, mirrored from kdf.ts. */
        val ARGON2ID_ALG: Int = PwHash.Alg.PWHASH_ALG_ARGON2ID13.value

        /** Portable AES-256-GCM via the platform JCE provider (see class kdoc). */
        const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * The default production key deriver: UTF-8-encode the passphrase (matching the
 * web, which hands a JS string to sodium.crypto_pwhash) and run Argon2id.
 *
 * CPU-HEAVY — see [VaultSodiumOps.argon2idDeriveKey]. tryPassphrase invokes this
 * SLOT_COUNT times.
 */
fun argon2idDeriver(ops: VaultSodiumOps): KeyDeriver =
    { passphrase, salt -> ops.argon2idDeriveKey(passphrase.toByteArray(Charsets.UTF_8), salt) }
