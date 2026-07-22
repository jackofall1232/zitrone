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
import com.zitrone.app.data.VaultScopedSettings
import com.zitrone.app.data.VaultSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyRecord
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
    fun `destroyContactCrypto rolls back and returns false when the durable flush fails`() {
        // Atomicity: a transient flush failure must ROLL BACK the removal (all-or-nothing, like the
        // legacy commit()), so `false` means nothing was persisted AND the contact's crypto survives
        // intact — never a retained contact whose session/identity vanished.
        val runtime = runtimeOf(persist = { _, _ -> throw IOException("disk full") })
        runtime.mutate { state ->
            // Seed all three contact-scoped families for bob, plus an unrelated contact + own prekey.
            state.signalRecords["session:bob-account:1"] = byteArrayOf(1, 2, 3)
            state.signalRecords["remote_identity:bob-account:1"] = byteArrayOf(4, 5)
            state.signalRecords["sender_key:bob-account:1:uuid-b"] = byteArrayOf(6, 7, 8, 9)
            state.signalRecords["session:carol-account:1"] = byteArrayOf(10)
            state.signalRecords["prekey:5"] = byteArrayOf(11)
        }
        val signalStore = VaultSignalProtocolStore(runtime)

        assertFalse("a failed durable flush returns false", signalStore.destroyContactCrypto("bob-account"))

        // ROLLBACK verified: every removed record is restored with its EXACT original bytes, and the
        // untouched records are unchanged — the map is byte-for-byte what it was before the destroy.
        runtime.read { state ->
            assertArrayEquals("bob session restored", byteArrayOf(1, 2, 3), state.signalRecords["session:bob-account:1"])
            assertArrayEquals("bob identity restored", byteArrayOf(4, 5), state.signalRecords["remote_identity:bob-account:1"])
            assertArrayEquals("bob sender key restored", byteArrayOf(6, 7, 8, 9), state.signalRecords["sender_key:bob-account:1:uuid-b"])
            assertArrayEquals("carol untouched", byteArrayOf(10), state.signalRecords["session:carol-account:1"])
            assertArrayEquals("own prekey untouched", byteArrayOf(11), state.signalRecords["prekey:5"])
        }
    }

    // ── signal scalar fidelity (load-bearing for the PR-E verbatim migration) ─────

    @Test
    fun `signal scalar records keep fixed BE widths and survive a codec round-trip exactly`() {
        val runtime = runtimeOf()
        val store = VaultSignalProtocolStore(runtime)
        // Counters are written by the facade's fixed-width encoders.
        store.setNextPreKeyId(0x0A0B0C0D)
        store.setNextSignedPreKeyId(42)
        store.setSignedPreKeyCreatedAt(0x0102030405060708L)
        store.markKyberPreKeyUsed(7)
        // registration_id is written alongside the identity by setLocalIdentity; seed its raw
        // 4-byte BE form directly (its writer shares the same encoder the counters exercise).
        runtime.mutate { it.signalRecords["registration_id"] = byteArrayOf(0, 0, 0x2a, 0x10) }

        // Byte-level widths — the migration copies these verbatim under identical keys.
        runtime.read { s ->
            assertArrayEquals("next_prekey_id is 4-byte BE", byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), s.signalRecords.getValue("next_prekey_id"))
            assertArrayEquals("next_signed_prekey_id is 4-byte BE", byteArrayOf(0, 0, 0, 42), s.signalRecords.getValue("next_signed_prekey_id"))
            assertArrayEquals("signed_prekey_created_at is 8-byte BE", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), s.signalRecords.getValue("signed_prekey_created_at"))
            assertArrayEquals("registration_id is 4-byte BE", byteArrayOf(0, 0, 0x2a, 0x10), s.signalRecords.getValue("registration_id"))
            assertArrayEquals("kyber_prekey_used is a 1-byte flag", byteArrayOf(1), s.signalRecords.getValue("kyber_prekey_used:7"))
        }

        // Round-trip the whole state through the codec and re-read via a fresh store: exact.
        val decoded = VaultStateCodec.decode(runtime.read { VaultStateCodec.encode(it) })
        val reStore = VaultSignalProtocolStore(runtimeOf(decoded))
        assertEquals(0x0A0B0C0D, reStore.nextPreKeyId())
        assertEquals(42, reStore.nextSignedPreKeyId())
        assertEquals(0x0102030405060708L, reStore.signedPreKeyCreatedAt())
        assertEquals(0x2a10, reStore.getLocalRegistrationId())
        assertArrayEquals("kyber-used flag survives", byteArrayOf(1), decoded.signalRecords.getValue("kyber_prekey_used:7"))
    }

    // ── kyber-used marker: fresh array per write (no shared-constant aliasing) ────

    @Test
    fun `a wiped kyber-used marker never aliases a later marker to a zero byte`() {
        // Old bug: every marker shared ONE static byteArrayOf(1); a close()/wipe() zeroed that
        // shared array in place, so every SUBSEQUENT marker silently encoded byteArrayOf(0).
        val first = runtimeOf()
        val store1 = VaultSignalProtocolStore(first)
        store1.markKyberPreKeyUsed(1)
        store1.markKyberPreKeyUsed(2)
        first.read { assertArrayEquals(byteArrayOf(1), it.signalRecords.getValue("kyber_prekey_used:1")) }
        first.close() // wipes the state — would have zeroed a shared marker array in place

        // A brand-new runtime + store writes another marker; with per-write fresh arrays it is 1.
        val second = runtimeOf()
        val store2 = VaultSignalProtocolStore(second)
        store2.markKyberPreKeyUsed(3)
        second.read {
            assertArrayEquals(
                "fresh marker is 1, not aliased to a wiped 0",
                byteArrayOf(1),
                it.signalRecords.getValue("kyber_prekey_used:3"),
            )
        }
        second.close()
    }

    @Test
    fun `overwriting a record zeroes the superseded backing array and keeps the new value`() {
        // Deterministic proof of the superseded-record wipe: the counter setter's bytes are fully
        // controlled, so we can capture the stored array and assert putRecord zeroed it on replace.
        val runtime = runtimeOf()
        val store = VaultSignalProtocolStore(runtime)
        store.setNextPreKeyId(0x01020304)
        val supersededArray = runtime.read { it.signalRecords.getValue("next_prekey_id") }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), supersededArray)

        store.setNextPreKeyId(0x05060708) // replaces the record → putRecord wipes the old array
        assertArrayEquals(
            "new value is stored and reads back",
            byteArrayOf(5, 6, 7, 8),
            runtime.read { it.signalRecords.getValue("next_prekey_id") },
        )
        assertArrayEquals("superseded record bytes were zeroed in place", byteArrayOf(0, 0, 0, 0), supersededArray)
        assertEquals(0x05060708, store.nextPreKeyId())
        runtime.close()
    }

    // ── round-3: record reads parse UNDER the runtime lock (no raw map bytes escape) ──

    @Test
    fun `a loaded record is parsed under the lock and is independent of a later overwrite`() {
        // Regression for the round-2 use-after-wipe: a read must CONSTRUCT the libsignal record
        // INSIDE runtime.read, so the returned object owns fully-parsed state, not a live map
        // array a later putRecord could zero mid-parse. Deterministic proxy for that contract:
        // load R, overwrite the SAME key with a DIFFERENT record (putRecord wipes the displaced
        // array), then assert R still deserializes to the ORIGINAL value.
        val runtime = runtimeOf()
        val store = VaultSignalProtocolStore(runtime)
        val original = PreKeyRecord(7, Curve.generateKeyPair())
        store.storePreKey(7, original)

        val loaded = store.loadPreKey(7)

        store.storePreKey(7, PreKeyRecord(7, Curve.generateKeyPair())) // wipes the displaced array

        assertArrayEquals(
            "the earlier read holds the original bytes, untouched by the overwrite's wipe",
            original.serialize(),
            loaded.serialize(),
        )
        assertEquals("original prekey id preserved", 7, loaded.id)
        runtime.close()
    }

    @Test
    fun `concurrent loads and overwrites of one record never observe a wiped array`() {
        // With the record CONSTRUCTED under the lock, a loadPreKey either sees the intact old
        // array or runs strictly after the store — never a half-wiped one (read and mutate are
        // mutually exclusive on the runtime's single lock). Pre-fix, loadPreKey parsed the escaped
        // array AFTER the lock, so a racing storePreKey could wipe it mid-parse (crash / torn record).
        val runtime = runtimeOf()
        val store = VaultSignalProtocolStore(runtime)
        store.storePreKey(9, PreKeyRecord(9, Curve.generateKeyPair()))
        val replacement = PreKeyRecord(9, Curve.generateKeyPair())

        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val n = 3_000
        val writer = Thread {
            try {
                repeat(n) { store.storePreKey(9, replacement) } // each store wipes the displaced array
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        val reader = Thread {
            try {
                repeat(n) {
                    val r = store.loadPreKey(9) // must never parse a wiped / torn array
                    assertEquals("loaded record keeps its id", 9, r.id)
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        writer.start(); reader.start()
        writer.join(); reader.join()

        assertTrue("no crash / torn parse across threads: $errors", errors.isEmpty())
        runtime.close()
    }

    // ── VaultSettingsStore StateFlow publication ─────────────────────────────────

    @Test
    fun `a settings setter updates the StateFlow to the new value`() {
        val runtime = runtimeOf()
        val settingsStore = VaultSettingsStore(runtime)
        assertEquals("seeded from the vault's current (default) settings", VaultScopedSettings(), settingsStore.settings.value)

        settingsStore.setDefaultTtlSeconds(3600)
        assertEquals("setter re-emits the updated ttl", 3600, settingsStore.settings.value.defaultTtlSeconds)
        settingsStore.setReadReceipts(false)
        assertFalse("setter re-emits the updated boolean", settingsStore.settings.value.readReceipts)

        // Flow value equals the sealed state — publication is ordered under the same runtime lock.
        assertEquals(runtime.read { it.settings }, settingsStore.settings.value)
        runtime.close()
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
