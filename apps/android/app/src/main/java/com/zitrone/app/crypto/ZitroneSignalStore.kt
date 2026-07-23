// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.SignalProtocolStore

/**
 * The Signal-store surface [SignalProtocolManager] and the roster/lemon-drop
 * consumers actually need: libsignal's [SignalProtocolStore] PLUS the extra
 * methods both concrete stores expose beyond that interface.
 *
 * Introduced by PR-D2a as a pure pluggability seam (NO behaviour change): the
 * two implementations — [EncryptedSignalProtocolStore] (legacy
 * EncryptedSharedPreferences, the ONLY one wired at runtime today) and
 * [com.zitrone.app.crypto.VaultSignalProtocolStore] (the isolated PR-C vault
 * facade) — are BEHAVIOURAL TWINS, so a consumer written against this interface
 * is byte-for-byte identical over either. PR-D2c later swaps the legacy store
 * for the vault facade at construction WITHOUT touching any consumer.
 *
 * The six counter accessors below used to live INSIDE [SignalProtocolManager],
 * which reached past its store into `prefs(PREFS_SIGNAL_STORE)` for the prekey /
 * signed-prekey id counters and the signed-prekey timestamp. They now belong to
 * the store — read/written under the SAME `PREFS_SIGNAL_STORE` keys
 * (`next_prekey_id` / `next_signed_prekey_id` / `signed_prekey_created_at`) with
 * the SAME defaults (1 / 1 / 0L) the manager used, so moving the plumbing is
 * behaviour-neutral. The manager keeps ONLY the wrap-and-increment logic and
 * stores the result here.
 */
interface ZitroneSignalStore : SignalProtocolStore {

    // -- local identity -------------------------------------------------------

    /** True once the long-term identity has been generated. */
    fun hasLocalIdentity(): Boolean

    fun setLocalIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int)

    // -- prekey bookkeeping ---------------------------------------------------

    /** Count of one-time prekeys still held in the store. */
    fun countOneTimePreKeys(): Int

    /** The next one-time-prekey id, default 1. Raw counter — see the class doc. */
    fun nextPreKeyId(): Int

    fun setNextPreKeyId(value: Int)

    /** The next signed-prekey id, default 1. Raw counter — see the class doc. */
    fun nextSignedPreKeyId(): Int

    fun setNextSignedPreKeyId(value: Int)

    /** The current signed prekey's creation timestamp (ms), default 0 (never rotated). */
    fun signedPreKeyCreatedAt(): Long

    fun setSignedPreKeyCreatedAt(value: Long)

    /**
     * The signed-prekey id whose PRIVATE half is stored but whose PUBLIC half has not been
     * confirmed uploaded to the relay yet, or 0 for none. Set at generation, cleared only after
     * a confirmed register/upload — this is what makes a flush-gated "upload skipped" genuinely
     * retryable instead of silently lost (generation already bumped [signedPreKeyCreatedAt], so
     * the age gate alone would never retry).
     */
    fun pendingSignedPreKeyUploadId(): Int

    fun setPendingSignedPreKeyUploadId(value: Int)

    /**
     * Ids of one-time prekeys whose PRIVATE halves are stored but whose PUBLIC halves have not
     * been confirmed uploaded, empty for none. Same retry contract as
     * [pendingSignedPreKeyUploadId]; also bounds the orphaned-batch growth in the fixed-capacity
     * vault — a retry re-serves THIS batch instead of generating a fresh one.
     */
    fun pendingOneTimePreKeyUploadIds(): List<Int>

    fun setPendingOneTimePreKeyUploadIds(value: List<Int>)

    // -- contact teardown / repair / wipe -------------------------------------

    /**
     * Full cryptographic teardown for one peer (session + remote identity +
     * sender keys). Returns false when the removal could not be durably flushed.
     */
    fun destroyContactCrypto(name: String): Boolean

    /**
     * Distinct remote-contact account-ids still holding an identity record, each
     * paired with that stored remote identity key (base64), or null if unreadable.
     * The one-time roster-repair source.
     */
    fun knownRemoteContacts(): List<Pair<String, String?>>

    /** Full local wipe — account deletion. Irreversible by design. */
    fun wipe()
}
