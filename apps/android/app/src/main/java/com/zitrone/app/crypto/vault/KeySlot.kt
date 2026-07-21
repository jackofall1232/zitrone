// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

/**
 * Key-slot architecture for the plausible-deniability storage layer — an exact
 * Kotlin mirror of packages/crypto/src/vault.ts. This file holds the constants
 * and the two data holders (KeySlot, VaultUnlock); the slot operations live in
 * VaultSlots.kt.
 *
 * Two properties are load-bearing and non-negotiable, identical to the web
 * reference:
 *
 *  1. The integer number of vaults is never stored. Every disk image contains
 *     exactly SLOT_COUNT slots; unused slots hold uniformly random bytes that
 *     are byte-for-byte indistinguishable from a real wrapped key. A slot that
 *     fails to decrypt is indistinguishable from a wrong passphrase, and the
 *     count of real vaults is unknowable from the stored material.
 *
 *  2. Every passphrase attempt does identical work. tryPassphrase derives a key
 *     for and attempts to unwrap ALL slots with no early exit, so the
 *     wall-clock time is the same whether the passphrase matches slot 0, slot
 *     N, or nothing.
 *
 * SLOT-AGNOSTIC everywhere: nothing here (or in the sibling files) names a slot
 * "real" or "decoy", logs slot structure, or leaves anything a decompiler could
 * read that would reveal how many slots are occupied or where.
 */

/** Fixed number of slots on every disk image. Real or random, the count is constant. */
const val SLOT_COUNT: Int = 4

/** Argon2id salt length, bytes. Mirrors kdf.ts SALT_BYTES. */
const val SALT_BYTES: Int = 16

/** Derived-key / vault-key length, bytes. Mirrors kdf.ts MASTER_KEY_BYTES. */
const val MASTER_KEY_BYTES: Int = 32

/** Length of the vault key the slots protect. */
const val VAULT_KEY_BYTES: Int = 32

/** AEAD nonce length for both layers (AES-256-GCM). Mirrors aead.ts NONCE_BYTES. */
const val NONCE_BYTES: Int = 12

/** AES-256-GCM authentication tag length, bytes. */
const val AEAD_TAG_BYTES: Int = 16

/**
 * Length of a wrapped vault key: nonce(12) + ciphertext(32) + GCM tag(16) = 60.
 * Same expression as vault.ts WRAPPED_KEY_BYTES (12 + MASTER_KEY_BYTES + 16).
 */
const val WRAPPED_KEY_BYTES: Int = NONCE_BYTES + MASTER_KEY_BYTES + AEAD_TAG_BYTES

/**
 * Associated data binding a wrapped key to its purpose. Intentionally generic —
 * it names nothing about vault ordering, count, or "decoy" status. Byte-for-byte
 * equal to vault.ts SLOT_AD = utf8("Zitrone-Vault-Slot-v1").
 */
val SLOT_AD: ByteArray = "Zitrone-Vault-Slot-v1".toByteArray(Charsets.UTF_8)

/**
 * One key slot as it sits on disk: a salt and a wrapped key. Both fields are
 * always present and always the same size, whether the slot is real or filler.
 */
class KeySlot(
    /** 16-byte Argon2id salt. */
    val salt: ByteArray,
    /** AES-256-GCM(masterKey, vaultKey): nonce || ciphertext || tag. */
    val wrapped: ByteArray,
) {
    init {
        require(salt.size == SALT_BYTES) { "slot salt must be $SALT_BYTES bytes" }
        require(wrapped.size == WRAPPED_KEY_BYTES) { "wrapped key must be $WRAPPED_KEY_BYTES bytes" }
    }
}

/** Result of a successful unlock. slotIndex is for the caller's bookkeeping only. */
class VaultUnlock(
    val vaultKey: ByteArray,
    val slotIndex: Int,
)

/**
 * Pluggable key deriver — defaults to Argon2id (see [argon2idDeriver]). Injectable
 * so timing-parity tests can substitute a fast, deterministic stand-in without
 * weakening production behavior. Mirrors vault.ts `KeyDeriver`.
 *
 * NOTE: the production deriver runs SLOT_COUNT × 64 MiB Argon2id per unlock and
 * is CPU-heavy; see [tryPassphrase] and [argon2idDeriver].
 */
typealias KeyDeriver = (passphrase: String, salt: ByteArray) -> ByteArray
