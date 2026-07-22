// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto.vault

/**
 * The fixed-size on-disk image — an exact Kotlin mirror of the image codec in
 * apps/web/src/lib/storage.ts. Layout:
 *
 *   version(1) ‖ SLOT_COUNT × [salt(16) ‖ wrapped(60)] ‖ SLOT_COUNT × payload(SLOT_PAYLOAD_BYTES)
 *
 * The image is a compile-time-constant IMAGE_BYTES long no matter how many
 * vaults are real — zero, one, or SLOT_COUNT. Neither the size, the structure,
 * nor any byte of the stored material reveals the vault count.
 *
 * This is the isolated PRIMITIVE only. It is deliberately NOT wired into any
 * store, unlock flow, or persistence backend — that is a later phase.
 */

/** On-disk image format version. Mirrors storage.ts IMAGE_VERSION. */
const val IMAGE_VERSION: Int = 2

private const val HEADER_BYTES: Int = 1
private const val SLOT_ENTRY_BYTES: Int = SALT_BYTES + WRAPPED_KEY_BYTES
private const val SLOT_TABLE_BYTES: Int = SLOT_COUNT * SLOT_ENTRY_BYTES

/** Total image size — constant regardless of how many vaults are real. */
const val IMAGE_BYTES: Int = HEADER_BYTES + SLOT_TABLE_BYTES + SLOT_COUNT * SLOT_PAYLOAD_BYTES

/** The image in structured form. payloads[i] belongs to slots[i]. */
class VaultImage(
    val slots: List<KeySlot>,
    val payloads: List<ByteArray>,
)

/** Result of a successful [unlockImage]. slotIndex is for caller bookkeeping only. */
class VaultOpen(
    val vaultKey: ByteArray,
    val slotIndex: Int,
    val payloadPlaintext: ByteArray,
)

/** Serialize a structured image to its fixed-size byte form. */
fun encodeImage(image: VaultImage): ByteArray {
    require(image.slots.size == SLOT_COUNT && image.payloads.size == SLOT_COUNT) {
        "vault image must have exactly SLOT_COUNT slots"
    }
    val out = ByteArray(IMAGE_BYTES)
    out[0] = IMAGE_VERSION.toByte()
    for (i in 0 until SLOT_COUNT) {
        val slot = image.slots[i]
        val payload = image.payloads[i]
        require(payload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
        val entryOffset = HEADER_BYTES + i * SLOT_ENTRY_BYTES
        slot.salt.copyInto(out, entryOffset)
        slot.wrapped.copyInto(out, entryOffset + SALT_BYTES)
        payload.copyInto(out, HEADER_BYTES + SLOT_TABLE_BYTES + i * SLOT_PAYLOAD_BYTES)
    }
    return out
}

/** Parse a fixed-size image back into structured form. */
fun decodeImage(bytes: ByteArray): VaultImage {
    require(bytes.size == IMAGE_BYTES) { "not a vault image" }
    require(bytes[0].toInt() and 0xff == IMAGE_VERSION) { "unsupported vault image version" }
    val slots = ArrayList<KeySlot>(SLOT_COUNT)
    val payloads = ArrayList<ByteArray>(SLOT_COUNT)
    for (i in 0 until SLOT_COUNT) {
        val entryOffset = HEADER_BYTES + i * SLOT_ENTRY_BYTES
        slots.add(
            KeySlot(
                salt = bytes.copyOfRange(entryOffset, entryOffset + SALT_BYTES),
                wrapped = bytes.copyOfRange(entryOffset + SALT_BYTES, entryOffset + SLOT_ENTRY_BYTES),
            ),
        )
        val payloadOffset = HEADER_BYTES + SLOT_TABLE_BYTES + i * SLOT_PAYLOAD_BYTES
        payloads.add(bytes.copyOfRange(payloadOffset, payloadOffset + SLOT_PAYLOAD_BYTES))
    }
    return VaultImage(slots, payloads)
}

/**
 * Build a fresh image sealed under [passphrase]: SLOT_COUNT slots, exactly ONE
 * real (at a random index), the rest random filler, and SLOT_COUNT payload
 * regions — the real slot's payload sealing [payloadPlaintext], every other
 * region a fresh random filler. The number of real slots leaves no on-disk
 * trace, and the returned image is always IMAGE_BYTES long.
 */
fun createImage(
    passphrase: String,
    payloadPlaintext: ByteArray,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): ByteArray {
    val created = createVaultSlots(passphrase, ops, deriver)
    // The key is ephemeral here (the returned image holds the SEALED payload, not
    // the raw key), so wipe it on every exit — including if randomPayload or
    // encodeImage throws between generation and use.
    try {
        val payloads = ArrayList<ByteArray>(SLOT_COUNT)
        for (i in 0 until SLOT_COUNT) payloads.add(randomPayload(ops))
        payloads[created.slotIndex] = sealPayload(created.vaultKey, payloadPlaintext, ops)
        return encodeImage(VaultImage(created.slots, payloads))
    } finally {
        wipe(created.vaultKey)
    }
}

/**
 * Replace ONE slot's payload region in [image] with an ALREADY-SEALED payload,
 * re-encoding the fixed-size image with every other region (the header, the whole
 * slot table, and every OTHER payload region) carried over byte-for-byte
 * unchanged. The result is always the same constant [IMAGE_BYTES] length.
 *
 * This is the reseal splice the STORAGE LAYER (the vault image store, a later
 * sub-phase) performs when a live session hands it a (slotIndex, sealedPayload)
 * pair — the session itself no longer touches the image. It re-encrypts the vault
 * key's OWN keystore payload in place, unlike [addVaultToImage], which seals a
 * NEW vault under a new passphrase into a free slot. It is deliberately
 * slot-agnostic and constant-length — it takes a caller-supplied [sealedPayload]
 * of exactly [SLOT_PAYLOAD_BYTES] and does not know or reveal whether the slot is
 * real or filler.
 */
internal fun spliceImagePayload(
    image: ByteArray,
    slotIndex: Int,
    sealedPayload: ByteArray,
): ByteArray {
    require(image.size == IMAGE_BYTES) { "malformed vault image" }
    require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
    require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
    // Only THIS slot's payload region changes on a reseal; the version byte, the
    // whole slot table, and every other slot's payload are carried through
    // byte-identical. Copy the image and overwrite just the target region in place
    // — no decode + re-encode, so a hot reseal path does not allocate and parse the
    // full (multi-hundred-KiB) image on every flush. The target offset mirrors
    // encodeImage()'s payload layout exactly.
    val out = image.copyOf()
    val payloadOffset = HEADER_BYTES + SLOT_TABLE_BYTES + slotIndex * SLOT_PAYLOAD_BYTES
    sealedPayload.copyInto(out, payloadOffset)
    return out
}

/**
 * Attempt [passphrase] against [image]. Runs [tryPassphrase] over every slot
 * (no early exit — identical work regardless of which slot, if any, matches),
 * then opens the matched slot's payload. Returns null when no slot matches (a
 * wrong passphrase) or the matched payload is corrupt.
 *
 * CPU-HEAVY with the production deriver (SLOT_COUNT × 64 MiB Argon2id); the
 * future integration layer MUST call this off the main thread.
 *
 * TIMING NOTE (deliberate, accepted): the SLOT_COUNT-way slot loop is
 * content-independent, so it gives the required cross-slot parity (matching slot
 * A, slot B, or nothing takes identical work). A SUCCESSFUL unlock additionally
 * opens one fixed-size payload; a wrong passphrase does not. So success and
 * failure are NOT equal-time — but this leaks nothing an observer doesn't
 * already have: the app visibly unlocks (or doesn't) the instant it happens, so
 * the payload-open duration reveals no extra bit. The web reference has the same
 * property. The router (P1b) MUST NOT introduce a NEW timing branch that varies
 * with which slot matched or whether a second vault exists.
 */
fun unlockImage(
    passphrase: String,
    image: ByteArray,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): VaultOpen? {
    val decoded = decodeImage(image)
    val unlock = tryPassphrase(passphrase, decoded.slots, ops, deriver) ?: return null
    // On success the caller owns unlock.vaultKey; on ANY failure (payload returns
    // null OR openPayload throws on corrupt padding/version) wipe it here.
    val plaintext = try {
        openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
    } catch (t: Throwable) {
        wipe(unlock.vaultKey)
        throw t
    }
    if (plaintext == null) {
        wipe(unlock.vaultKey)
        return null
    }
    return VaultOpen(unlock.vaultKey, unlock.slotIndex, plaintext)
}

/**
 * Seal a second (or further) vault into [image] at a random currently-free slot,
 * sealing [payloadPlaintext] into that slot's payload region. Every OTHER slot
 * and payload region is carried over byte-for-byte unchanged. The result is a
 * new image of the same constant IMAGE_BYTES length.
 *
 * [occupied] names the slots already holding real vaults the caller wishes to
 * preserve — the stored image cannot reveal them (that is the point). See
 * [addVaultSlot].
 */
fun addVaultToImage(
    image: ByteArray,
    occupied: Set<Int>,
    passphrase: String,
    payloadPlaintext: ByteArray,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): ByteArray {
    val decoded = decodeImage(image)
    val added = addVaultSlot(decoded.slots, occupied, passphrase, ops, deriver)
    // Ephemeral key (the returned image holds the sealed payload) — wipe on every
    // exit, incl. if the list copy or encodeImage throws.
    try {
        val payloads = decoded.payloads.toMutableList()
        payloads[added.slotIndex] = sealPayload(added.vaultKey, payloadPlaintext, ops)
        return encodeImage(VaultImage(added.slots, payloads))
    } finally {
        wipe(added.vaultKey)
    }
}
