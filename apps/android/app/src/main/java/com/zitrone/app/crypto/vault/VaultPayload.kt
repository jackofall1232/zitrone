// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto.vault

/**
 * The fixed-size payload layer — an exact Kotlin mirror of the payload codec in
 * apps/web/src/lib/storage.ts. Every payload region, real or filler, is exactly
 * SLOT_PAYLOAD_BYTES on disk. A real payload is pad-then-encrypted so its length
 * prefix sits INSIDE the ciphertext: it is byte-for-byte indistinguishable from
 * the uniformly random bytes that fill unused regions, and it never grows.
 */

/** Fixed size of every payload region, real or filler. Mirrors storage.ts. */
const val SLOT_PAYLOAD_BYTES: Int = 256 * 1024

/** Plaintext capacity of a payload region (AEAD adds a 12-byte nonce + 16-byte tag). */
const val PAYLOAD_PLAINTEXT_BYTES: Int = SLOT_PAYLOAD_BYTES - NONCE_BYTES - AEAD_TAG_BYTES

/** Big-endian length prefix width inside a padded payload. */
private const val LEN_PREFIX_BYTES: Int = 4

/**
 * Associated data binding a payload to its purpose. Intentionally generic — it
 * names nothing about slot position, vault count, or "decoy" status. Byte-for-byte
 * equal to storage.ts PAYLOAD_AD = utf8("Zitrone-Vault-Payload-v1").
 */
val PAYLOAD_AD: ByteArray = "Zitrone-Vault-Payload-v1".toByteArray(Charsets.UTF_8)

/**
 * Seal content into a payload region: pad to full plaintext capacity, THEN
 * encrypt. Output is ALWAYS exactly SLOT_PAYLOAD_BYTES. The order is
 * load-bearing: padding after encryption would put a plaintext length prefix on
 * disk, statistically distinguishing real payloads from random filler and
 * leaking the vault count.
 *
 * THROWS if the content exceeds the region's plaintext capacity — it never grows
 * the region, because a larger-than-fixed payload would leak that a real vault
 * lives here (and how big it is).
 */
fun sealPayload(vaultKey: ByteArray, content: ByteArray, ops: VaultSodiumOps): ByteArray {
    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
    if (LEN_PREFIX_BYTES + content.size > PAYLOAD_PLAINTEXT_BYTES) {
        throw IllegalArgumentException("content exceeds vault slot capacity")
    }
    val padded = padToCapacity(content, ops)
    try {
        val sealed = ops.aeadEncrypt(vaultKey, padded, PAYLOAD_AD)
        check(sealed.size == SLOT_PAYLOAD_BYTES) { "sealed payload size mismatch" }
        return sealed
    } finally {
        wipe(padded)
    }
}

/**
 * Open a payload region with an unlocked vault key. Returns the original content,
 * or null on any AEAD failure (wrong key / tampering) or corrupt padding.
 */
fun openPayload(vaultKey: ByteArray, payload: ByteArray, ops: VaultSodiumOps): ByteArray? {
    val padded = ops.aeadDecrypt(vaultKey, payload, PAYLOAD_AD) ?: return null
    try {
        return unpad(padded)
    } catch (e: IllegalArgumentException) {
        // A corrupt length prefix makes unpad throw — honor this function's
        // "returns null on corrupt padding" contract rather than propagating,
        // so unlockImage treats it as an unopenable payload, not a crash.
        return null
    } finally {
        wipe(padded)
    }
}

/** A filler payload region: CSPRNG bytes, indistinguishable from a sealed one. */
fun randomPayload(ops: VaultSodiumOps): ByteArray = ops.randomBytes(SLOT_PAYLOAD_BYTES)

// Exact-fit padding: len(4 BE) || content || random fill, always exactly
// PAYLOAD_PLAINTEXT_BYTES. The fill sits INSIDE the AEAD plaintext; its only job
// is to carry no recoverable structure. Same layout as storage.ts padToCapacity.
private fun padToCapacity(content: ByteArray, ops: VaultSodiumOps): ByteArray {
    val out = ByteArray(PAYLOAD_PLAINTEXT_BYTES)
    out[0] = ((content.size ushr 24) and 0xff).toByte()
    out[1] = ((content.size ushr 16) and 0xff).toByte()
    out[2] = ((content.size ushr 8) and 0xff).toByte()
    out[3] = (content.size and 0xff).toByte()
    content.copyInto(out, LEN_PREFIX_BYTES)
    val fillStart = LEN_PREFIX_BYTES + content.size
    if (fillStart < out.size) ops.randomBytes(out.size - fillStart).copyInto(out, fillStart)
    return out
}

// Recover the original content from a padded region. Mirrors padding.ts unpad.
private fun unpad(padded: ByteArray): ByteArray {
    require(padded.size >= LEN_PREFIX_BYTES) { "padded input too short" }
    val len = ((padded[0].toInt() and 0xff) shl 24) or
        ((padded[1].toInt() and 0xff) shl 16) or
        ((padded[2].toInt() and 0xff) shl 8) or
        (padded[3].toInt() and 0xff)
    val length = len.toLong() and 0xffffffffL
    require(length <= padded.size - LEN_PREFIX_BYTES) { "corrupt padding length" }
    return padded.copyOfRange(LEN_PREFIX_BYTES, LEN_PREFIX_BYTES + length.toInt())
}
