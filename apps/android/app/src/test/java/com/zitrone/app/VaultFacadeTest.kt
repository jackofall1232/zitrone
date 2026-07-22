// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.VaultSignalProtocolStore
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.data.VaultAuthStore
import com.zitrone.app.data.VaultRosterStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Facade-semantics tests for the store facades over a [VaultRuntime]: durability mapping
 * ([VaultRosterStore.writeBlobDurably] false-on-flush-failure, [VaultSignalProtocolStore.destroyContactCrypto]
 * prefix removal + durable, [VaultRosterStore.writeTombstonesBlob] durable), and cross-thread
 * safety of [VaultAuthStore]. Real AEAD + DEFLATE byte path; only the persist sink is faked.
 */
class VaultFacadeTest {

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun runtimeOf(
        state: VaultState = VaultState.empty(),
        persist: (Int, ByteArray) -> Unit = { _, _ -> },
    ): VaultRuntime {
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 },
            slotIndex = 0,
            persist = persist,
            cooldownMs = 60_000L,
            flushContext = Dispatchers.IO,
        )
        return VaultRuntime(session, state)
    }

    // ── VaultRosterStore.writeBlobDurably ────────────────────────────────────────

    @Test
    fun `writeBlobDurably returns true on a durable flush and false when the flush fails`() {
        val okRoster = VaultRosterStore(runtimeOf())
        assertTrue("durable write returns true", okRoster.writeBlobDurably("""[{"id":"a"}]"""))
        assertEquals("""[{"id":"a"}]""", okRoster.readBlob())

        val failingRoster = VaultRosterStore(runtimeOf(persist = { _, _ -> throw IOException("disk full") }))
        assertFalse("a failed durable flush returns false", failingRoster.writeBlobDurably("""[{"id":"b"}]"""))
        // The blob is still updated in memory (the mutate ran before the flush attempt).
        assertEquals("""[{"id":"b"}]""", failingRoster.readBlob())
    }

    // ── VaultSignalProtocolStore.destroyContactCrypto ────────────────────────────

    @Test
    fun `destroyContactCrypto removes only the target contact's prefixes and commits durably`() {
        val persisted = AtomicInteger(0)
        val runtime = runtimeOf(persist = { _, _ -> persisted.incrementAndGet() })
        // Seed the three contact-scoped families for bob + carol, plus one of our own prekeys.
        runtime.mutate { state ->
            state.signalRecords["session:bob-account:1"] = byteArrayOf(1)
            state.signalRecords["remote_identity:bob-account:1"] = byteArrayOf(2)
            state.signalRecords["sender_key:bob-account:1:uuid-b"] = byteArrayOf(3)
            state.signalRecords["session:carol-account:1"] = byteArrayOf(4)
            state.signalRecords["remote_identity:carol-account:1"] = byteArrayOf(5)
            state.signalRecords["prekey:5"] = byteArrayOf(6)
        }
        val signalStore = VaultSignalProtocolStore(runtime)

        assertTrue("durable teardown returns true", signalStore.destroyContactCrypto("bob-account"))
        assertTrue("teardown forced a durable flush", persisted.get() >= 1)

        runtime.read { state ->
            // Bob's session / identity / sender key are all gone.
            assertFalse(state.signalRecords.containsKey("session:bob-account:1"))
            assertFalse(state.signalRecords.containsKey("remote_identity:bob-account:1"))
            assertFalse(state.signalRecords.containsKey("sender_key:bob-account:1:uuid-b"))
            // Carol (unrelated) and our own prekey survive.
            assertTrue(state.signalRecords.containsKey("session:carol-account:1"))
            assertTrue(state.signalRecords.containsKey("remote_identity:carol-account:1"))
            assertTrue(state.signalRecords.containsKey("prekey:5"))
        }
    }

    @Test
    fun `destroyContactCrypto returns false when the durable flush fails`() {
        val runtime = runtimeOf(persist = { _, _ -> throw IOException("disk full") })
        runtime.mutate { it.signalRecords["session:bob-account:1"] = byteArrayOf(1) }
        val signalStore = VaultSignalProtocolStore(runtime)

        assertFalse("a failed durable flush returns false", signalStore.destroyContactCrypto("bob-account"))
        // The removal still happened in memory.
        assertFalse(runtime.read { it.signalRecords.containsKey("session:bob-account:1") })
    }

    // ── VaultRosterStore.writeTombstonesBlob (always durable) ────────────────────

    @Test
    fun `writeTombstonesBlob forces a durable flush`() {
        val persisted = AtomicInteger(0)
        val roster = VaultRosterStore(runtimeOf(persist = { _, _ -> persisted.incrementAndGet() }))

        assertNull("no tombstones initially", roster.readTombstonesBlob())
        roster.writeTombstonesBlob("""{"bob-account":123}""")
        assertEquals("tombstone write forced a durable flush", 1, persisted.get())
        assertEquals("""{"bob-account":123}""", roster.readTombstonesBlob())
    }

    // ── VaultAuthStore cross-thread safety ───────────────────────────────────────

    @Test
    fun `auth writes from a foreign thread never expose a torn token pair`() {
        val runtime = runtimeOf()
        val authStore = VaultAuthStore(runtime)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val n = 1_000

        // Writer: pairs "a<i>" / "r<i>" set atomically by the facade.
        val writer = Thread {
            try {
                repeat(n) { authStore.storeTokens("a$it", "r$it") }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        // Reader: an atomic snapshot must always carry a MATCHING pair (same suffix).
        val reader = Thread {
            try {
                repeat(n) {
                    val snap = runtime.read { it.auth }
                    val at = snap.accessToken
                    val rt = snap.refreshToken
                    if (at != null && rt != null) {
                        assertEquals("torn token pair observed", at.substring(1), rt.substring(1))
                    }
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        // A third thread mutating a different store field (roster) adds cross-store pressure.
        val rosterMutator = Thread {
            try {
                repeat(n) { runtime.mutate { it.rosterJson = "roster-$it" } }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        writer.start(); reader.start(); rosterMutator.start()
        writer.join(); reader.join(); rosterMutator.join()

        assertTrue("no torn state / no exceptions across threads: $errors", errors.isEmpty())
        val finalAuth = runtime.read { it.auth }
        assertEquals("final token pair is consistent", finalAuth.accessToken?.substring(1), finalAuth.refreshToken?.substring(1))
    }
}
