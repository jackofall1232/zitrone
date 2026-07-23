// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.Conversation
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.OrphanContact
import com.zitrone.app.data.RosterStore
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2c: the vault contact-delete seal lambda ([ZitroneApp.deleteContactAtomically]) rethrows
 * CancellationException BEFORE its catch(Throwable){false}, so cooperative cancellation (a forced
 * logout / revocation tearing down the session scope mid-delete) unwinds instead of being folded
 * into an "unconfirmed durable" false.
 *
 * The property the fix depends on is that [ConversationRepository.deleteContactDurably] does NOT
 * swallow a throw from its `seal` — it propagates, so the lambda's rethrow reaches the coroutine
 * machinery. That contract is exercised here against the real repo over a fake roster store. The
 * lambda's own 3-line try/catch (rethrow c, then catch t -> false) needs a live SessionContainer to
 * reach, so it is verified by inspection; this guards the seam it relies on.
 */
class DeleteSealCancellationTest {

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

    @Test
    fun `deleteContactDurably propagates a CancellationException from the seal (never folds to false)`() {
        val repo = ConversationRepository(FakeRosterStore(), clock = { 1_000_000L })
        repo.upsert(Conversation(id = "alice", contactId = "alice", displayName = "alice"))

        assertThrows(CancellationException::class.java) {
            repo.deleteContactDurably("alice", "alice", 1_000_000L) { _, _ ->
                // Stand in for the seal lambda rethrowing on a scope teardown: mutate/flush would
                // have unwound with a CancellationException, which the lambda rethrows.
                throw CancellationException("session scope cancelled mid-delete")
            }
        }

        // The in-memory reconcile (the line AFTER seal in deleteContactDurably) is skipped because
        // the throw propagated — proof it was not swallowed. On a real teardown the whole session is
        // going away, so the roster entry staying is inert.
        assertTrue("the entry is retained because the throw propagated past the reconcile",
            repo.conversations.value.any { it.id == "alice" })
    }
}
