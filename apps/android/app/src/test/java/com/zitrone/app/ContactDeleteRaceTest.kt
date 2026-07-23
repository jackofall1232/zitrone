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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Regression TRIPWIRE for the atomic contact-delete race (P1-A) — a bug family that has now
 * recurred across the original contact-delete work and D2c, so this test is the guard that keeps it
 * from coming back silently. It drives a CONCURRENT roster write DURING the single-mutate delete's
 * seal and asserts the delete's monitor serializes them, so the deleted contact is not durably
 * RESURRECTED and a concurrent ADD is not durably LOST.
 *
 * Host-JVM: it exercises the real [ConversationRepository] over an in-memory fake [RosterStore] (the
 * `sealed*` fields stand in for the vault's one sealed generation), so it needs no native libsodium.
 * The property under test — that the delete's seal + in-memory reconcile happen under this repo's
 * monitor, mutually exclusive with every other roster write — is exactly where the fix lives.
 *
 * Against the PRE-FIX split (deletionBlobs snapshot taken OUTSIDE the monitor → an external
 * runtime.mutate → a separate commitDeletion), the concurrent upsert below would NOT block on the
 * repo monitor: it could persist a full-roster snapshot still containing the deletee (resurrection)
 * or be overwritten by the delete's stale snapshot (loss). Both the `blocked` assertion and the
 * final-state assertions here would then fail.
 */
class ContactDeleteRaceTest {

    /** In-memory [RosterStore]: `sealed*` stand in for the vault's single sealed generation. */
    private class FakeRosterStore : RosterStore {
        @Volatile var sealedRoster: String? = null
        @Volatile var sealedTombstones: String? = null
        override fun readBlob(): String? = sealedRoster
        override fun writeBlob(json: String) { sealedRoster = json }
        override fun writeBlobDurably(json: String): Boolean { sealedRoster = json; return true }
        override fun orphanedContacts(): List<OrphanContact> = emptyList()
        override fun readTombstonesBlob(): String? = sealedTombstones
        override fun writeTombstonesBlob(json: String) { sealedTombstones = json }
    }

    private fun convo(id: String) = Conversation(id = id, contactId = id, displayName = id)

    @Test
    fun `a concurrent roster write during the delete neither resurrects the deleted nor loses the add`() {
        val store = FakeRosterStore()
        val repo = ConversationRepository(store, clock = { 1_000_000L })
        repo.upsert(convo("alice"))
        repo.upsert(convo("bob"))
        assertTrue(store.sealedRoster!!.contains("alice"))
        assertTrue(store.sealedRoster!!.contains("bob"))

        val sealEntered = CountDownLatch(1)
        val releaseSeal = CountDownLatch(1)
        val idsInsideSeal = AtomicReference<Set<String>>()

        // Delete "alice" on its own thread; the seal blocks (monitor still held) to open the window
        // during which a concurrent roster write could interleave if the delete were not atomic.
        val deleteThread = thread(name = "delete") {
            repo.deleteContactDurably("alice", "alice", 1_000_000L) { rosterJson, tomb ->
                sealEntered.countDown()
                releaseSeal.await()
                rosterJson?.let { store.sealedRoster = it }
                store.sealedTombstones = tomb
                ContactDeleteOutcome.DURABLE
            }
        }

        sealEntered.await()
        // Concurrent ADD of "carol" WHILE the delete holds the monitor.
        val writerThread = thread(name = "writer") { repo.upsert(convo("carol")) }

        // The concurrent writer MUST be serialized behind the delete's monitor — poll for BLOCKED.
        var blocked = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (writerThread.state == Thread.State.BLOCKED) { blocked = true; break }
            Thread.sleep(2)
        }
        // Snapshot the live roster from INSIDE the delete's critical section: the add must not have
        // applied and the deletee must still be live in RAM (removed only after the seal).
        idsInsideSeal.set(repo.conversations.value.map { it.id }.toSet())
        releaseSeal.countDown()
        deleteThread.join(2_000)
        writerThread.join(2_000)

        assertTrue("the concurrent roster write is serialized behind the delete's monitor", blocked)
        assertEquals(
            "no interleaving: the add is not applied while the delete seals, and the deletee is still in RAM",
            setOf("alice", "bob"),
            idsInsideSeal.get(),
        )

        // Final state, in RAM: alice deleted (not resurrected), carol added (not lost), bob kept.
        assertEquals(setOf("bob", "carol"), repo.conversations.value.map { it.id }.toSet())
        // …and the SAME in the single sealed generation.
        assertFalse("deleted alice not resurrected in the sealed roster", store.sealedRoster!!.contains("alice"))
        assertTrue("concurrent add carol not lost from the sealed roster", store.sealedRoster!!.contains("carol"))
        assertTrue("tombstone recorded for alice", store.sealedTombstones!!.contains("alice"))
    }

    /**
     * The mirror case: a concurrent MUTATION (markConversationRead) of a DIFFERENT contact during
     * the delete is serialized too, so it can neither be lost nor re-seal the deletee.
     */
    @Test
    fun `a concurrent mutation of another contact during the delete is serialized`() {
        val store = FakeRosterStore()
        val repo = ConversationRepository(store, clock = { 1_000_000L })
        repo.upsert(convo("alice"))
        repo.upsert(convo("bob").copy(unreadCount = 5))

        val sealEntered = CountDownLatch(1)
        val releaseSeal = CountDownLatch(1)

        val deleteThread = thread(name = "delete") {
            repo.deleteContactDurably("alice", "alice", 1_000_000L) { rosterJson, tomb ->
                sealEntered.countDown()
                releaseSeal.await()
                rosterJson?.let { store.sealedRoster = it }
                store.sealedTombstones = tomb
                ContactDeleteOutcome.DURABLE
            }
        }

        sealEntered.await()
        val writerThread = thread(name = "writer") { repo.markConversationRead("bob") }
        var blocked = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (writerThread.state == Thread.State.BLOCKED) { blocked = true; break }
            Thread.sleep(2)
        }
        releaseSeal.countDown()
        deleteThread.join(2_000)
        writerThread.join(2_000)

        assertTrue("the concurrent markConversationRead is serialized behind the delete", blocked)
        // alice gone, bob kept with its unread cleared by the (post-delete) markRead — not lost.
        assertEquals(setOf("bob"), repo.conversations.value.map { it.id }.toSet())
        assertEquals(0, repo.conversations.value.first { it.id == "bob" }.unreadCount)
        assertFalse("alice not resurrected", store.sealedRoster!!.contains("alice"))
    }

    /**
     * The delete's in-memory reconcile runs for every APPLIED outcome — the contract the
     * seal-side containment (ZitroneApp catches a closed-runtime mutate throw and returns an
     * outcome, never lets it escape) relies on: APPLIED_UNCONFIRMED still removes the contact
     * from RAM, never a crash or a half-delete left in the roster.
     */
    @Test
    fun `an unconfirmed-durable seal still reconciles RAM and reports the outcome`() {
        val store = FakeRosterStore()
        val repo = ConversationRepository(store, clock = { 1_000_000L })
        repo.upsert(convo("alice"))
        repo.upsert(convo("bob"))

        val outcome = repo.deleteContactDurably("alice", "alice", 1_000_000L) { _, _ ->
            ContactDeleteOutcome.APPLIED_UNCONFIRMED
        }

        assertEquals(ContactDeleteOutcome.APPLIED_UNCONFIRMED, outcome)
        assertEquals("alice removed from RAM even when the flush is unconfirmed", setOf("bob"),
            repo.conversations.value.map { it.id }.toSet())
    }

    /**
     * Round 8: a NOT_APPLIED seal (closed-runtime teardown race — the mutate never touched live
     * state) must leave RAM consistent with reality: the contact is STILL PRESENT, so the
     * conversation row stays and the tombstone is taken back — otherwise the row vanishes for a
     * session while the caller's outcome gate (correctly) preserved its typing/receipt state,
     * and a live tombstone would drop-before-decrypt inbound from a contact that still exists.
     */
    @Test
    fun `a NOT_APPLIED seal keeps the conversation and takes back the tombstone`() {
        val store = FakeRosterStore()
        val repo = ConversationRepository(store, clock = { 1_000_000L })
        repo.upsert(convo("alice"))
        repo.upsert(convo("bob"))

        val outcome = repo.deleteContactDurably("alice", "alice", 1_000_000L) { _, _ ->
            ContactDeleteOutcome.NOT_APPLIED
        }

        assertEquals(ContactDeleteOutcome.NOT_APPLIED, outcome)
        assertEquals("alice kept — the delete did not take", setOf("alice", "bob"),
            repo.conversations.value.map { it.id }.toSet())
        assertFalse("the tombstone was taken back", repo.wasRecentlyDeleted("alice"))
    }
}
