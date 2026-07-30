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
 * destroyed at first redemption (fetch-and-burn) or at its 1-week unfetched
 * fallback TTL, so there is nothing left
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

    /**
     * Encrypts attachment bytes for blind relay storage.
     *
     * [reuseToken] / [reuseKey] are supplied ONLY when re-encrypting a message that has already had
     * a deposit attempt — a 0.10.1 retry (0.10.2 item 5a). Passing them keeps `blobId` stable across
     * attempts, so a retry deposits to the same row instead of orphaning the previous blob (up to
     * 8 MiB held for the full TTL, once per retry).
     *
     * **Why reusing the KEY is what makes this safe, and reusing the box is not needed.**
     * `DepositBlob` is `ON CONFLICT (blob_id) DO NOTHING`, so whichever attempt's bytes land first is
     * what the relay keeps. Those bytes carry their OWN nonce inside [EncryptedBlob.box] — the
     * layout is `nonce(12) || ciphertext+tag` — so the recipient can open whichever version was
     * stored, provided the key matches. Holding the key stable is therefore sufficient, and holding
     * the 8 MiB box in memory to guarantee byte-identity is not.
     *
     * **The nonce is still freshly drawn on every call, deliberately.** Deriving it to force
     * byte-identical output would mean a repeated (key, nonce) pair over plaintext that differs —
     * `MessagePadding.pad` fills with random bytes — and that is the one GCM failure mode that is
     * catastrophic rather than merely wasteful. A fresh nonce under a reused key is the ordinary,
     * safe construction.
     *
     * **The token must never be derived from anything the relay sees.** `blobId` is `sha256(token)`
     * and the token IS the redemption capability, while the message id is cleartext to the relay for
     * routing — so a relay-computable token would let the relay redeem the attachment outright.
     * Callers memoize the randomly drawn token; they do not derive it.
     */
    fun encrypt(
        plain: ByteArray,
        reuseToken: ByteArray? = null,
        reuseKey: ByteArray? = null,
    ): EncryptedBlob {
        if (plain.isEmpty()) throw IllegalArgumentException("empty attachment")
        require((reuseToken == null) == (reuseKey == null)) {
            // Half-reuse would pair a stable blobId with a new key (the relay keeps the first bytes,
            // the envelope carries the second key → an undecryptable attachment, surfaced as
            // corruption) or a new blobId with an old key (a fresh orphan, defeating the point).
            "reuseToken and reuseKey must be supplied together or not at all"
        }
        val token = reuseToken ?: ByteArray(BLOB_TOKEN_BYTES).also(random::nextBytes)
        val key = reuseKey ?: ByteArray(32).also(random::nextBytes)
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
