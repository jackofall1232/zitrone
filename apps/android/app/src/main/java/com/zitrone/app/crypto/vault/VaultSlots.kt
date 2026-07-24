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
 * Slot 0 is RESERVED for the Pucker Burn duress credential (0.9.2). It is sealed
 * byte-identically to any vault slot — same Argon2id, same structure, same timing —
 * so an examiner cannot tell from structure whether it is armed; only a MATCH on it
 * triggers a wipe (handled by the store/app), never an unlock. Arm-state is stored
 * NOWHERE: "armed" simply means a passphrase can match slot 0, exactly what
 * [tryPassphrase] already tests, so an unarmed slot 0 is uniformly-random filler,
 * indistinguishable from a real one.
 *
 * The reservation is a placement-only convention (the byte format is unchanged): no
 * everyday vault and no created vault ever lands here, so vault creation can never
 * clobber the burn credential. This is an ACCEPTED, documented disclosure — it reveals
 * only that a burn FEATURE exists (public), never how many vaults slots 1..N-1 hold.
 */
const val BURN_SLOT_INDEX: Int = 0

/** The vault pool — slots that may hold a real vault. Slot 0 (burn) is excluded. */
val VAULT_SLOT_RANGE: IntRange = (BURN_SLOT_INDEX + 1) until SLOT_COUNT

/**
 * A uniformly-random index into the VAULT pool (never [BURN_SLOT_INDEX]). The SINGLE
 * source of truth for slot-0 reservation, used by BOTH the everyday-vault placement
 * ([createVaultSlots]) and blind second-vault creation
 * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
 * (plus one integer add), so it carries no timing/I-O signature distinct from ordinary
 * placement.
 */
fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)

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
 * Like [sealSlot] but SELF-VERIFYING: immediately after wrapping, it decrypts the wrapped key back under
 * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
 * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
 * lifetime is identical to [sealSlot]'s.
 *
 * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
 * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
 * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
 * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
 * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
 * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
 * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
 * would equally break every other slot operation; failing closed here is correct.
 */
fun sealSlotSelfVerifying(
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
        val recovered = ops.aeadDecrypt(masterKey, wrapped, SLOT_AD)
            ?: throw IllegalStateException("sealed slot failed self-verify (wrapped key did not unwrap)")
        try {
            // Constant-time equality (both are VAULT_KEY_BYTES) — MessageDigest.isEqual is the platform
            // constant-time compare. A mismatch means the AEAD provider does not round-trip: fail closed.
            check(java.security.MessageDigest.isEqual(recovered, vaultKey)) {
                "sealed slot failed self-verify (recovered key mismatch)"
            }
        } finally {
            wipe(recovered)
        }
        return KeySlot(salt = salt, wrapped = wrapped)
    } finally {
        wipe(masterKey)
    }
}

/**
 * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
 * real vault sealed under [passphrase]. The rest are random filler. The returned
 * vaultKey is the random key the caller should use to encrypt the vault's data.
 * The real slot is placed at a CSPRNG-random index IN THE VAULT POOL
 * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
 * stays reserved for the burn credential (see [BURN_SLOT_INDEX]). Slot 0 is left as
 * filler on a fresh onboarding (unarmed burn), indistinguishable from any other slot.
 */
fun createVaultSlots(
    passphrase: String,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): CreatedVault {
    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
    // On SUCCESS the caller owns (and later wipes) vaultKey; on ANY failure path
    // after generation, wipe it here so no live key is abandoned in heap.
    try {
        val slots = ArrayList<KeySlot>(SLOT_COUNT)
        for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
        slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
        return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
    } catch (t: Throwable) {
        wipe(vaultKey)
        throw t
    }
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
 *
 * ⚠️ BURN-UNAWARE (0.9.2): this primitive picks freely over ALL slots incl.
 * [BURN_SLOT_INDEX], so it must NOT be wired into a creation path without excluding
 * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
 * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
 * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
 * as the web-mirrored primitive + tests only.
 */
fun addVaultSlot(
    slots: List<KeySlot>,
    occupied: Set<Int>,
    passphrase: String,
    ops: VaultSodiumOps,
    deriver: KeyDeriver = argon2idDeriver(ops),
): CreatedVault {
    // Reject a passphrase that already unlocks an existing vault: tryPassphrase
    // returns only the FIRST matching slot, so a second seal under the same
    // passphrase would shadow one vault and silently make it unreachable.
    tryPassphrase(passphrase, slots, ops, deriver)?.let {
        wipe(it.vaultKey)
        throw IllegalArgumentException("passphrase already unlocks an existing vault")
    }
    val free = ArrayList<Int>()
    for (i in slots.indices) if (i !in occupied) free.add(i)
    if (free.isEmpty()) throw IllegalStateException("no free key slots")
    val slotIndex = free[randomIndex(free.size, ops)]
    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
    try {
        val next = slots.toMutableList()
        next[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
        return CreatedVault(slots = next, vaultKey = vaultKey, slotIndex = slotIndex)
    } catch (t: Throwable) {
        wipe(vaultKey)
        throw t
    }
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
    try {
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
    } catch (t: Throwable) {
        // A later derivation failing (e.g. OOM under memory pressure) must not
        // abandon an already-matched vault key in heap — the caller never
        // received it to wipe.
        found?.let { wipe(it.vaultKey) }
        throw t
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
