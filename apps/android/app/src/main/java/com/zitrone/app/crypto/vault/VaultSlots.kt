// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto.vault

/**
 * Slot operations — an exact Kotlin mirror of the functions in
 * packages/crypto/src/vault.ts. Every function is slot-agnostic: nothing is
 * named "real" or "decoy", nothing is logged, and the code path for a filler
 * slot is byte-for-byte the same as for a real one.
 */

/** Holder for a freshly created / added vault, mirroring vault.ts's return shapes. */
class CreatedVault(
    val slots: List<KeySlot>,
    val vaultKey: ByteArray,
    val slotIndex: Int,
)

/**
 * A filler slot: a random salt and random bytes the exact length of a real
 * wrapped key. Indistinguishable from an occupied slot. No passphrase will ever
 * unwrap it (a random 16-byte tail is a valid GCM tag with probability 2^-128).
 */
fun randomSlot(ops: VaultSodiumOps): KeySlot =
    KeySlot(salt = ops.randomBytes(SALT_BYTES), wrapped = ops.randomBytes(WRAPPED_KEY_BYTES))

/** Wrap a vault key under a passphrase, producing a real, unlockable slot. */
fun sealSlot(
    passphrase: String,
    vaultKey: ByteArray,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): KeySlot {
    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
    val salt = ops.randomBytes(SALT_BYTES)
    val masterKey = deriver(passphrase, salt)
    try {
        val wrapped = ops.aeadEncrypt(masterKey, vaultKey, SLOT_AD)
        return KeySlot(salt = salt, wrapped = wrapped)
    } finally {
        wipe(masterKey)
    }
}

/**
 * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
 * real vault sealed under [passphrase]. The rest are random filler. The returned
 * vaultKey is the random key the caller should use to encrypt the vault's data.
 * The real slot is placed at a CSPRNG-random index so position leaks nothing.
 */
fun createVaultSlots(
    passphrase: String,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): CreatedVault {
    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
    val slots = ArrayList<KeySlot>(SLOT_COUNT)
    for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
    val slotIndex = randomIndex(SLOT_COUNT, ops)
    slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
    return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
}

/**
 * Seal a second (or third…) vault into a currently-unoccupied slot. The new
 * vault gets its own independent random vault key — vaults share no key
 * material. The slot chosen is a random currently-unoccupied one so the layout
 * still reveals nothing. Throws if every slot is occupied.
 *
 * [occupied] is supplied by the caller because the stored material deliberately
 * cannot reveal which slots hold real vaults (that is the whole point). Passing
 * an empty set reproduces the web's overwrite-tolerant behavior (storage.ts
 * createVault, the documented VeraCrypt outer-volume tradeoff); passing the
 * known-occupied indices avoids clobbering a live vault.
 */
fun addVaultSlot(
    slots: List<KeySlot>,
    occupied: Set<Int>,
    passphrase: String,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): CreatedVault {
    val free = ArrayList<Int>()
    for (i in slots.indices) if (i !in occupied) free.add(i)
    if (free.isEmpty()) throw IllegalStateException("no free key slots")
    val slotIndex = free[randomIndex(free.size, ops)]
    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
    val next = slots.toMutableList()
    next[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
    return CreatedVault(slots = next, vaultKey = vaultKey, slotIndex = slotIndex)
}

/**
 * Attempt a passphrase against all slots. Returns the unlocked vault key, or
 * null if no slot matched (indistinguishable from a wrong passphrase).
 *
 * Derive+attempt EVERY slot, never break, so wall-clock timing is identical
 * whether a passphrase matches slot 0, slot N, or nothing — a break here is a
 * plausible-deniability side-channel. The first match is recorded but the loop
 * runs to completion regardless; any later match's vault key is wiped, and every
 * derived master key is wiped whether it matched or not.
 *
 * CPU-HEAVY: SLOT_COUNT × Argon2id (64 MiB each) with the production deriver.
 * Callers on a UI thread MUST run this off the main thread.
 */
fun tryPassphrase(
    passphrase: String,
    slots: List<KeySlot>,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): VaultUnlock? {
    var found: VaultUnlock? = null
    for (i in slots.indices) {
        val slot = slots[i]
        val masterKey = deriver(passphrase, slot.salt)
        try {
            val vaultKey = ops.aeadDecrypt(masterKey, slot.wrapped, SLOT_AD)
            if (vaultKey != null) {
                // Record the first match but DO NOT break — every slot is
                // always derived and tried.
                if (found == null) found = VaultUnlock(vaultKey, i) else wipe(vaultKey)
            }
        } finally {
            wipe(masterKey)
        }
    }
    return found
}

/** Overwrite key material in place. Call the moment a key is no longer needed. */
fun wipe(bytes: ByteArray) {
    bytes.fill(0)
}

/**
 * Uniform random index in [0, n) drawn from the CSPRNG. Reads 4 CSPRNG bytes as
 * a big-endian unsigned 32-bit value and reduces mod n (no meaningful modulo
 * bias for the small n used here). Byte-for-byte the same construction as
 * vault.ts randomIndex.
 */
fun randomIndex(n: Int, ops: VaultSodiumOps): Int {
    val buf = ops.randomBytes(4)
    val v = ((buf[0].toInt() and 0xff) shl 24) or
        ((buf[1].toInt() and 0xff) shl 16) or
        ((buf[2].toInt() and 0xff) shl 8) or
        (buf[3].toInt() and 0xff)
    val unsigned = v.toLong() and 0xffffffffL
    return (unsigned % n).toInt()
}
