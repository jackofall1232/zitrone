// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.AttachmentCrypto
import com.zitrone.app.crypto.MessagePadding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Byte-compatibility contract with packages/crypto/src/attachments.ts +
 * padding.ts + aead.ts: 64 KiB bucket padding (len(4,BE)||plain||random fill),
 * then AES-256-GCM with box layout nonce(12)||ciphertext+tag. Ported from
 * attachments.test.ts.
 */
class AttachmentCryptoTest {

    private val nonce = 12
    private val tag = 16
    private val bucket = AttachmentCrypto.BLOB_BUCKET_BYTES

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun `round-trips and verifies size and hash`() {
        val plain = bytes(100_000)
        val blob = AttachmentCrypto.encrypt(plain)
        val out = AttachmentCrypto.decrypt(blob.key, blob.box, blob.sha256, blob.size)
        assertArrayEquals(plain, out)
        assertEquals(plain.size, blob.size)
    }

    @Test
    fun `pads the ciphertext to 64 KiB buckets (size hiding)`() {
        // 1-byte and 60000-byte attachments occupy the SAME single bucket…
        val tiny = AttachmentCrypto.encrypt(bytes(1))
        val small = AttachmentCrypto.encrypt(bytes(60_000))
        val oneBucket = bucket + nonce + tag
        assertEquals(oneBucket, tiny.box.size)
        assertEquals(oneBucket, small.box.size)
        // …while one past the bucket boundary (minus the 4-byte length prefix)
        // rolls over to two buckets.
        val big = AttachmentCrypto.encrypt(bytes(bucket - 4 + 1))
        assertEquals(2 * bucket + nonce + tag, big.box.size)
    }

    @Test
    fun `bucket padding layout is big-endian length prefixed with random fill`() {
        // 300-byte plaintext padded to one 64 KiB bucket: len(4,BE) then plain.
        val padded = MessagePadding.pad(ByteArray(300), bucket)
        assertEquals(bucket, padded.size)
        assertEquals(0, padded[0].toInt())
        assertEquals(0, padded[1].toInt())
        assertEquals(1, padded[2].toInt())          // 300 = 0x012C
        assertEquals(0x2C, padded[3].toInt() and 0xFF)
        // The fill region is random, NOT zeros — a zero-filled tail would carry
        // recoverable structure. All-zero across ~65k bytes is impossible.
        val fillAllZero = (4 + 300 until padded.size).all { padded[it].toInt() == 0 }
        assertFalse(fillAllZero)
    }

    @Test
    fun `derives the blob id as SHA-256 of the token`() {
        val blob = AttachmentCrypto.encrypt(bytes(10))
        val digest = MessageDigest.getInstance("SHA-256").digest(blob.token)
        assertArrayEquals(digest, blob.blobId)
        assertFalse(blob.blobId.contentEquals(blob.token))
    }

    @Test
    fun `uses a fresh token and key per attachment`() {
        val a = AttachmentCrypto.encrypt(bytes(10))
        val b = AttachmentCrypto.encrypt(bytes(10))
        assertFalse(a.token.contentEquals(b.token))
        assertFalse(a.key.contentEquals(b.key))
        assertFalse(a.box.contentEquals(b.box))
    }

    @Test
    fun `rejects a size mismatch (substituted blob)`() {
        val blob = AttachmentCrypto.encrypt(bytes(1000))
        val e = assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.decrypt(blob.key, blob.box, blob.sha256, 999)
        }
        assertTrue(e.message!!.contains("size mismatch"))
    }

    @Test
    fun `rejects a hash mismatch (substituted blob)`() {
        val blob = AttachmentCrypto.encrypt(bytes(1000))
        val wrongHash = blob.sha256.copyOf()
        wrongHash[0] = (wrongHash[0].toInt() xor 0xFF).toByte()
        val e = assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.decrypt(blob.key, blob.box, wrongHash, blob.size)
        }
        assertTrue(e.message!!.contains("hash mismatch"))
    }

    @Test
    fun `rejects tampered ciphertext (AEAD)`() {
        val blob = AttachmentCrypto.encrypt(bytes(1000))
        val tampered = blob.box.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        // The GCM tag fails — any exception, but never a silent wrong plaintext.
        assertThrows(Exception::class.java) {
            AttachmentCrypto.decrypt(blob.key, tampered, blob.sha256, blob.size)
        }
    }

    @Test
    fun `rejects an empty attachment`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.encrypt(ByteArray(0))
        }
        assertTrue(e.message!!.contains("empty"))
    }

    @Test
    fun `nonce is fresh per encryption (box layout starts with the nonce)`() {
        val a = AttachmentCrypto.encrypt(bytes(10))
        val b = AttachmentCrypto.encrypt(bytes(10))
        val nonceA = a.box.copyOfRange(0, nonce)
        val nonceB = b.box.copyOfRange(0, nonce)
        assertNotEquals(nonceA.toList(), nonceB.toList())
    }
}
