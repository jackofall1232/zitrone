// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import com.zitrone.app.crypto.EncryptedSignalProtocolStore
import com.zitrone.app.crypto.KeyStoreManager

/**
 * The tiny disk surface the contact roster needs, pulled behind an interface so
 * the roster logic in [ConversationRepository] is unit-testable off-device (a
 * fake in-memory impl replaces EncryptedSharedPreferences + the Signal store).
 *
 * WHY the roster is persisted at all: it used to live ONLY in an in-memory
 * StateFlow (init `emptyList()`), never written to disk. Identity/login persist
 * via EncryptedSharedPreferences, so a process restart — which every app update
 * forces — left the user logged in but with an EMPTY roster: display names, the
 * pinned anti-key-substitution key, and the verified/key-changed flags all
 * vanished, and a returning contact resurfaced as "Unknown contact", unverified,
 * with its pinned key lost (a real security regression). This store closes that
 * gap while keeping message plaintext OUT of persistence (see [MessageRepository]).
 */
interface RosterStore {

    /** The persisted roster JSON blob, or null when nothing has ever been stored. */
    fun readBlob(): String?

    /** Overwrites the persisted roster JSON blob. */
    fun writeBlob(json: String)

    /**
     * Overwrites the persisted roster JSON blob **synchronously**, returning
     * whether the write reached disk. Used by contact deletion so the removal is
     * durable (a crash right after the crypto teardown must not leave a stale
     * roster blob that resurrects the deleted contact). Ordinary hot-path writes
     * use [writeBlob] (async) — this variant is reserved for deletion.
     */
    fun writeBlobDurably(json: String): Boolean

    /**
     * Orphaned contacts recoverable from the (persisted) Signal store — used
     * ONLY by the one-time repair path for installs wiped by the in-memory-only
     * bug. Empty on a healthy/fresh install.
     */
    fun orphanedContacts(): List<OrphanContact>

    /**
     * Persisted deleted-contact tombstones (a JSON blob of contactId →
     * deletedAtEpochMs), or null when none stored. Kept separate from the roster
     * blob so a deletion tombstone survives a process restart independently: a
     * straggler message from a contact deleted within the relay's undelivered
     * window must stay dropped even across an app update (which forces a
     * restart). See [ConversationRepository.wasRecentlyDeleted].
     */
    fun readTombstonesBlob(): String?

    /** Overwrites the tombstone blob synchronously (deletion is the trigger). */
    fun writeTombstonesBlob(json: String)
}

/** A contact reconstructed from a surviving Signal-store identity record. */
data class OrphanContact(
    val contactId: String,
    /** Stored remote identity key (base64), or null when unreadable. */
    val identityKeyBase64: String?,
)

/**
 * Production [RosterStore]: the roster blob lives in its own encrypted prefs
 * file ([KeyStoreManager.PREFS_CONTACTS]) — all local persistence goes through
 * EncryptedSharedPreferences — and the repair source is the persisted Signal
 * store's orphaned `remote_identity:` records.
 */
class EncryptedRosterStore(
    keyStoreManager: KeyStoreManager,
    private val signalStore: EncryptedSignalProtocolStore,
) : RosterStore {

    private val prefs = keyStoreManager.prefs(KeyStoreManager.PREFS_CONTACTS)

    override fun readBlob(): String? = prefs.getString(KEY_ROSTER, null)

    override fun writeBlob(json: String) {
        prefs.edit().putString(KEY_ROSTER, json).apply()
    }

    override fun writeBlobDurably(json: String): Boolean =
        prefs.edit().putString(KEY_ROSTER, json).commit()

    override fun orphanedContacts(): List<OrphanContact> =
        signalStore.knownRemoteContacts().map { (id, key) -> OrphanContact(id, key) }

    override fun readTombstonesBlob(): String? = prefs.getString(KEY_TOMBSTONES, null)

    override fun writeTombstonesBlob(json: String) {
        prefs.edit().putString(KEY_TOMBSTONES, json).commit()
    }

    companion object {
        private const val KEY_ROSTER = "roster_json"
        private const val KEY_TOMBSTONES = "deleted_tombstones_json"
    }
}
