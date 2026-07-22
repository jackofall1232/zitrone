// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

/**
 * The one device-level operation the storage layer needs from secure hardware,
 * and nothing more: wrap and unwrap the 32-byte data-encryption key (DEK) that
 * in turn encrypts the vault image at rest.
 *
 * Kept a plain interface — with no Android dependency — for the SAME reason the
 * crypto surface ([VaultSodiumOps]) is: so [VaultImageStore] is JVM-unit-testable
 * against a fixed-key fake while the production path binds the Android Keystore
 * ([KeystoreDeviceKeyCipher]). The Keystore classes compile only against the
 * Android SDK; keeping them behind this interface keeps them out of the host
 * unit tests entirely.
 *
 * ENVELOPE RATIONALE (approved D2). The DEK — not the Keystore key — encrypts the
 * ~1 MiB image on the flush-before-ack path, with in-process AES-256-GCM (the
 * same portable `javax.crypto` backend [LibsodiumVaultOps] uses). The Keystore
 * key only ever wraps/unwraps the DEK's 32 bytes, ONCE per open/create — so the
 * hardware-gated TEE crypto (~10–50 MB/s) never sits on a per-flush hot path
 * (which would add 20–100 ms to every durable reseal). This mirrors
 * EncryptedSharedPreferences' MasterKey construction.
 *
 * SLOT-AGNOSTIC. Every install that holds a vault image has exactly ONE wrapped
 * DEK of exactly one constant size — constant evidence that reveals nothing about
 * how many slots are real. Implementations MUST NOT log, MUST NOT vary work by
 * DEK contents, and MUST emit a constant-length blob.
 */
interface DeviceKeyCipher {
    /**
     * Wrap [dek] (32 bytes) with the hardware-backed device key, returning a
     * constant-size opaque blob (`nonce(12) ‖ ciphertext(32) ‖ tag(16)` = 60
     * bytes) safe to persist alongside the image. The blob is device-bound: it is
     * only ever unwrappable by [unwrapDek] on the SAME install's non-exportable
     * Keystore key.
     */
    fun wrapDek(dek: ByteArray): ByteArray

    /**
     * Unwrap a blob produced by [wrapDek], recovering the 32-byte DEK. Returns null on
     * ANY authentication failure — a tampered blob, a truncated blob, or a key the
     * hardware can no longer honor — AND on any Keystore PROVIDER failure (a transient
     * TEE / StrongBox runtime error), mirroring [VaultSodiumOps.aeadDecrypt]'s "null means
     * no" contract. The caller treats a null here as a corrupt image and NEVER silently
     * recreates (that would destroy a real vault). Because a provider failure MAY be
     * transient, a CorruptImage at open() is not necessarily PERMANENT — a later retry or
     * a reboot can succeed — but the caller escalates without repairing either way. The
     * returned array is live key material the caller owns and MUST wipe.
     */
    fun unwrapDek(blob: ByteArray): ByteArray?
}
