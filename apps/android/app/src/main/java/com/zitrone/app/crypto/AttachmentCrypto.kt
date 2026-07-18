// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Attachment blob crypto — byte-compatible with the web/desktop reference
 * (packages/crypto/src/attachments.ts + aead.ts + padding.ts). An attachment is
 * encrypted OUTSIDE the Double Ratchet under a fresh random AES-256-GCM key; the
 * key and the blob's redemption token then travel inside the ratchet-encrypted
 * control payload (see data/AttachmentControlPayload), so end-to-end
 * confidentiality is inherited from the session while the relay stores only an
 * opaque bucket-sized blob it can neither read nor tie to an envelope. Forward
 * secrecy of the standalone key is a non-issue by construction: the blob is
 * destroyed at first redemption or at its 72-hour TTL, so there is nothing left
 * to decrypt when a key would leak.
 *
 * The plaintext is padded to 64 KiB buckets BEFORE encryption (reusing the
 * message padding layout — len(4, big-endian) || plaintext || random fill), so
 * the blob's stored size reveals only a bucket count, not the true length. The
 * blob ID the relay stores under is SHA-256(token) — the relay never sees the
 * token until redemption, mirroring the dead-drop construction.
 *
 * Wire layout of [EncryptedBlob.box]: nonce(12) || ciphertext+tag. A fresh
 * random nonce is generated on every call (nonce reuse under GCM is
 * catastrophic). javax's GCM `doFinal` returns ciphertext||tag with a 128-bit
 * tag appended — matching WebCrypto — so the box is just the nonce prepended.
 */
object AttachmentCrypto {

    /** Bucket size the padded plaintext is a multiple of. Mirrors
     *  packages/protocol attachments.ts BLOB_BUCKET_BYTES. */
    const val BLOB_BUCKET_BYTES = 64 * 1024

    /** Redemption-token / key / hash lengths (all 32 bytes). */
    const val BLOB_TOKEN_BYTES = 32

    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    /**
     * The result of encrypting attachment bytes for blind relay storage. All
     * fields are raw bytes; the caller base64-encodes them for the wire (the
     * control payload carries [token]/[key]/[sha256]; the blob store receives
     * [blobId] and [box]).
     */
    class EncryptedBlob(
        /** 32-byte redemption token — goes into the control payload, never uploaded. */
        val token: ByteArray,
        /** SHA-256(token) — the ID the relay stores the blob under (uploaded). */
        val blobId: ByteArray,
        /** 32-byte AES-256-GCM key — goes into the control payload. */
        val key: ByteArray,
        /** nonce(12) || ciphertext+tag of the bucket-padded plaintext (uploaded). */
        val box: ByteArray,
        /** SHA-256 of the plaintext — verified by the recipient after decryption. */
        val sha256: ByteArray,
        /** Plaintext byte length (pre-padding) — carried in the control payload. */
        val size: Int,
    )

    /** Encrypts attachment bytes for blind relay storage. */
    fun encrypt(plain: ByteArray): EncryptedBlob {
        if (plain.isEmpty()) throw IllegalArgumentException("empty attachment")
        val token = ByteArray(BLOB_TOKEN_BYTES).also(random::nextBytes)
        val key = ByteArray(32).also(random::nextBytes)
        val blobId = sha256(token)
        val digest = sha256(plain)
        val padded = MessagePadding.pad(plain, BLOB_BUCKET_BYTES)
        val box = seal(key, padded)
        return EncryptedBlob(token, blobId, key, box, digest, plain.size)
    }

    /**
     * Decrypts a redeemed blob and verifies it against the control payload's
     * declared size and SHA-256. Throws on ANY mismatch — a wrong hash or length
     * means the blob is not what the sender described, and rendering it anyway
     * would let the relay (or anyone who guessed a blob ID) substitute content.
     * The AEAD tag is verified first by [open] (throws on tamper).
     */
    fun decrypt(
        key: ByteArray,
        box: ByteArray,
        expectedSha256: ByteArray,
        expectedSize: Int,
    ): ByteArray {
        val padded = open(key, box)
        val plain = MessagePadding.unpadOrNull(padded)
            ?: throw IllegalArgumentException("corrupt attachment padding")
        if (plain.size != expectedSize) throw IllegalArgumentException("attachment size mismatch")
        // Constant-time compare — a length-varying or short-circuiting equality
        // would leak the hash a byte at a time to a substitution attacker.
        if (!MessageDigest.isEqual(sha256(plain), expectedSha256)) {
            throw IllegalArgumentException("attachment hash mismatch")
        }
        return plain
    }

    private fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        // doFinal returns ciphertext||tag; prepend the nonce for the box layout.
        return nonce + cipher.doFinal(plaintext)
    }

    private fun open(key: ByteArray, box: ByteArray): ByteArray {
        if (box.size <= NONCE_BYTES) throw IllegalArgumentException("ciphertext too short")
        val nonce = box.copyOfRange(0, NONCE_BYTES)
        val ct = box.copyOfRange(NONCE_BYTES, box.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(ct)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
