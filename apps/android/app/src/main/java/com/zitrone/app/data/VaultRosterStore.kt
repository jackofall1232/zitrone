// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import com.zitrone.app.crypto.knownRemoteContactsOf
import com.zitrone.app.crypto.vault.VaultRuntime

/**
 * [RosterStore] over a sealed vault via [VaultRuntime] — the vault twin of
 * [EncryptedRosterStore]. The roster blob, the tombstone blob, and (for orphan repair)
 * the Signal records all live in ONE [com.zitrone.app.crypto.vault.VaultState] behind ONE
 * runtime lock, which STRUCTURALLY eliminates the cross-store repair hazard the research
 * flagged: today the roster blob and the Signal store are separate prefs files that a
 * crash between two writes can leave disagreeing; here [orphanedContacts] reads the SAME
 * state as every roster write, under the same lock, so they can never desync.
 *
 * DURABILITY MATCHES LEGACY per method:
 *  - [readBlob] / [writeBlob]: [writeBlob] is a COALESCED mutate — the async hot-path write
 *    (legacy `apply()`).
 *  - [writeBlobDurably]: mutate + [VaultRuntime.flushBeforeAck], returning `false` if the
 *    flush throws — the vault analogue of legacy `commit()`'s boolean, so contact deletion
 *    stays durable-or-reported-failed.
 *  - [writeTombstonesBlob]: mutate + flushBeforeAck. Legacy always `commit()`s tombstones
 *    (a straggler from a deleted contact must stay dropped across a restart), so this
 *    preserves the DURABLE nature. See the propagation note on the method.
 *
 * Isolated unit: ConversationRepository is NOT switched to it until PR-D.
 */
class VaultRosterStore(
    private val runtime: VaultRuntime,
) : RosterStore {

    override fun readBlob(): String? = runtime.read { it.rosterJson }

    override fun writeBlob(json: String) {
        runtime.mutate { it.rosterJson = json }
    }

    override fun writeBlobDurably(json: String): Boolean {
        runtime.mutate { it.rosterJson = json }
        return try {
            runtime.flushBeforeAck()
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun orphanedContacts(): List<OrphanContact> =
        runtime.read { knownRemoteContactsOf(it.signalRecords) }
            .map { (id, key) -> OrphanContact(id, key) }

    override fun readTombstonesBlob(): String? = runtime.read { it.tombstonesJson }

    /**
     * Overwrites the tombstone blob and forces it durable. Legacy `writeTombstonesBlob`
     * calls `commit()` and discards its boolean; here the flush-before-ack is genuine and a
     * failed flush THROWS ([com.zitrone.app.crypto.vault.VaultImageException.NotDurable] /
     * IO) — an honest "not durable" signal the caller (PR-D) can act on, rather than the
     * legacy silently-dropped commit result. (Left for architect review: whether PR-D wants
     * this to propagate or to swallow-to-Unit to exactly match the legacy discarded boolean.)
     */
    override fun writeTombstonesBlob(json: String) {
        runtime.mutate { it.tombstonesJson = json }
        runtime.flushBeforeAck()
    }
}
