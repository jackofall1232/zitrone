// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.Conversation
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.OrphanContact
import com.zitrone.app.data.RosterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roster persistence — the regression the fix closes: the roster used to live
 * ONLY in memory, so a process restart (every app update forces one) wiped
 * display names, pinned identity keys, and verified flags. These tests drive
 * the persistence choke point, the never-silent-wipe guard, and the one-time
 * repair path OFF-device via a fake [RosterStore].
 */
class ConversationRepositoryPersistenceTest {

    /**
     * In-memory [RosterStore] standing in for EncryptedSharedPreferences + the
     * Signal store. [blob] is the "disk"; sharing one instance across two
     * repositories simulates a process restart over the same backing file.
     */
    private class FakeRosterStore(
        var blob: String? = null,
        private val orphans: List<OrphanContact> = emptyList(),
    ) : RosterStore {
        override fun readBlob(): String? = blob
        override fun writeBlob(json: String) { blob = json }
        override fun orphanedContacts(): List<OrphanContact> = orphans
    }

    private fun conversation(id: String, verified: Boolean = false, pinned: String? = null) =
        Conversation(
            id = id,
            contactId = id,
            displayName = "Contact $id",
            pinnedIdentityKeyBase64 = pinned,
            verified = verified,
        )

    @Test
    fun `roster survives a process restart over the same backing store`() {
        val store = FakeRosterStore()

        val first = ConversationRepository(store)
        first.upsert(conversation("alice", verified = true, pinned = "pinned-alice"))
        first.upsert(conversation("bob"))

        // A brand-new repository over the SAME store == the app after a restart.
        val second = ConversationRepository(store)
        val restored = second.conversations.value.associateBy { it.id }

        assertEquals(setOf("alice", "bob"), restored.keys)
        // The security-critical fields survive, not just the names.
        assertTrue(restored.getValue("alice").verified)
        assertEquals("pinned-alice", restored.getValue("alice").pinnedIdentityKeyBase64)
    }

    @Test
    fun `malformed blob loads empty in memory and is never overwritten`() {
        val garbage = "{ this is not valid json"
        val store = FakeRosterStore(blob = garbage)

        val repo = ConversationRepository(store)

        // Loads empty in memory...
        assertTrue(repo.conversations.value.isEmpty())
        // ...and the stored blob is left untouched (no silent wipe on save) —
        // even a subsequent mutation must not clobber the recoverable blob.
        assertEquals(garbage, store.blob)
        repo.upsert(conversation("carol"))
        assertEquals(garbage, store.blob)
    }

    @Test
    fun `repair reconstructs a roster from orphaned signal-store contacts`() {
        // Empty contacts store, but the Signal store still holds remote_identity
        // records for two previously-messaged contacts (the 0.7.x wiped install).
        val store = FakeRosterStore(
            blob = null,
            orphans = listOf(
                OrphanContact("acct-1", identityKeyBase64 = "idkey-1"),
                OrphanContact("acct-2", identityKeyBase64 = null),
            ),
        )

        val repo = ConversationRepository(store)
        val byId = repo.conversations.value.associateBy { it.id }

        assertEquals(setOf("acct-1", "acct-2"), byId.keys)
        assertEquals("Unnamed contact", byId.getValue("acct-1").displayName)
        assertEquals("idkey-1", byId.getValue("acct-1").contactIdentityKeyBase64)
        assertNull(byId.getValue("acct-1").pinnedIdentityKeyBase64)
        // The repair is persisted so it runs exactly once.
        val persisted = ConversationRepository(store)
        assertEquals(setOf("acct-1", "acct-2"), persisted.conversations.value.map { it.id }.toSet())
    }

    @Test
    fun `an unknown-newer schema is read-only and left untouched`() {
        val newer = """{"schema_version":9999,"conversations":[]}"""
        val store = FakeRosterStore(blob = newer)

        val repo = ConversationRepository(store)
        repo.upsert(conversation("dave"))

        // In-memory only; the newer-client blob must not be clobbered.
        assertEquals(newer, store.blob)
    }

    @Test
    fun `remove drops the contact from the roster and persists the gap`() {
        val store = FakeRosterStore()
        val repo = ConversationRepository(store)
        repo.upsert(conversation("alice", verified = true, pinned = "pin-a"))
        repo.upsert(conversation("bob"))

        repo.remove("alice")

        assertEquals(listOf("bob"), repo.conversations.value.map { it.id })
        // Survives a restart — the deleted contact must not reappear from disk.
        val restored = ConversationRepository(store)
        assertEquals(listOf("bob"), restored.conversations.value.map { it.id })
        assertNull(restored.find("alice"))
    }
}
