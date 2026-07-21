// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto.vault

import com.goterl.lazysodium.Sodium
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/**
 * The libsodium surface the vault primitive needs, and nothing more. Kept a
 * plain interface so tests can inject a fast, deterministic key deriver while
 * still exercising the REAL AEAD byte path.
 *
 * The production impl [LibsodiumVaultOps] runs over lazysodium's raw JNA
 * bindings — the SAME `Sodium` base class LemonDropSodiumOps uses — so the code
 * is identical against lazysodium-android's `SodiumAndroid` (device: prebuilt
 * .so per ABI, no NDK build) and lazysodium-java's `SodiumJava` (JVM unit
 * tests). Both bind the identical libsodium C functions, so a unit test through
 * this adapter is a real test of the on-device byte path.
 *
 * Byte-compatibility with the web reference (packages/crypto): the KDF is
 * Argon2id with the exact kdf.ts parameters, and BOTH the wrapped-key layer
 * (vault.ts) and the payload layer (apps/web storage.ts) are AES-256-GCM with a
 * 12-byte nonce — the same algorithm the web reaches for its WebCrypto
 * AES-GCM. The goal is auditability against the reference, not cross-device
 * image sharing.
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

    init {
        // AES-256-GCM in libsodium requires hardware AES (AES-NI / ARMv8
        // Crypto Extensions). Fail loudly at construction rather than let a
        // later encrypt/decrypt abort deep in native code. Effectively all
        // 64-bit Android devices and CI hosts provide it.
        check(sodium.crypto_aead_aes256gcm_is_available() == 1) {
            "AES-256-GCM (hardware AES) is unavailable on this platform"
        }
    }

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
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = ByteArray(plaintext.size + AEAD_TAG_BYTES)
        val cipherLen = LongArray(1)
        val rc = sodium.crypto_aead_aes256gcm_encrypt(
            cipher,
            cipherLen,
            plaintext,
            plaintext.size.toLong(),
            associatedData,
            associatedData.size.toLong(),
            null, // nsec — unused by AES-256-GCM
            nonce,
            key,
        )
        check(rc == 0) { "crypto_aead_aes256gcm_encrypt failed (rc=$rc)" }
        // Layout: nonce || ciphertext || tag — the combined-mode ciphertext
        // already carries the 16-byte tag as its tail.
        return nonce + cipher
    }

    override fun aeadDecrypt(
        key: ByteArray,
        box: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? {
        require(key.size == MASTER_KEY_BYTES) { "AES-256-GCM key must be $MASTER_KEY_BYTES bytes" }
        if (box.size < NONCE_BYTES + AEAD_TAG_BYTES) return null
        val nonce = box.copyOfRange(0, NONCE_BYTES)
        val cipher = box.copyOfRange(NONCE_BYTES, box.size)
        val message = ByteArray(cipher.size - AEAD_TAG_BYTES)
        val messageLen = LongArray(1)
        val rc = sodium.crypto_aead_aes256gcm_decrypt(
            message,
            messageLen,
            null, // nsec
            cipher,
            cipher.size.toLong(),
            associatedData,
            associatedData.size.toLong(),
            nonce,
            key,
        )
        // rc != 0 is a tag failure: wrong key, filler slot, or tampering — all
        // reported the same way, as "no match".
        return if (rc == 0) message else null
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
