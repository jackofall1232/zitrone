// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.crypto.VaultSignalProtocolStore
import com.zitrone.app.crypto.vault.AEAD_TAG_BYTES
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import com.zitrone.app.crypto.vault.openPayload
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.VaultAuthStore
import com.zitrone.app.data.VaultRosterStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * D2c §6: the slot-A wiring proved end to end on the host JVM — real AEAD + DEFLATE byte
 * path, a real temp-dir [VaultImageStore], only Argon2id and the Keystore device key faked.
 *
 *  - the full lifecycle round-trips byte-faithfully (create → facades → flush → close →
 *    reopen → unlock → decode);
 *  - the biometric dual-wrap path opens the slot via [VaultImageStore.unlockWithKey], with
 *    wrong-key → null and an invalidated (unwrap-null) wrap selecting the passphrase fallback;
 *  - the vault contact-delete is ONE atomic mutate: records + roster + tombstone land (or fail)
 *    together in a single sealed generation.
 */
class VaultSlotALiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ops = LibsodiumVaultOps(SodiumJava())

    /** Fast deterministic Argon2id stand-in (SHA-256), the package's host-test convention. */
    private val fast: KeyDeriver = { passphrase, salt ->
        MessageDigest.getInstance("SHA-256").apply {
            update(passphrase.toByteArray(Charsets.UTF_8))
            update(salt)
        }.digest()
    }

    private val cipher = FakeDeviceKeyCipher()
    private val passphrase = "correct horse battery staple"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newStore(dir: File) = VaultImageStore(dir, ops, cipher, fast)

    // ── #1 end-to-end slot-A lifecycle round-trip ────────────────────────────────

    @Test
    fun `slot-A state round-trips byte-faithfully across close and reopen`() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, VaultStateCodec.encode(VaultState.empty()))

        // Decode a COPY before the session consumes the arrays (the SessionContainer contract).
        val decoded = VaultStateCodec.decode(open.payloadPlaintext.copyOf())
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = store::writeSealedPayload,
            cooldownMs = 60_000L,
        )
        val runtime = VaultRuntime(session, decoded)
        val signalStore = VaultSignalProtocolStore(runtime)
        val signalManager = SignalProtocolManager(signalStore)
        val authStore = VaultAuthStore(runtime)
        val rosterStore = VaultRosterStore(runtime)

        // Drive every facade: Signal identity + prekeys + counters, auth tokens, roster blob.
        signalManager.ensureIdentity()
        signalManager.generateSignedPreKey()
        signalManager.generateOneTimePreKeys()
        authStore.accountId = "acct-1"
        authStore.storeTokens(access = "access-tok", refresh = "refresh-tok")
        val rosterJson = """[{"id":"peer-1","name":"Peer"}]"""
        rosterStore.writeBlob(rosterJson)

        val expectedIdentity = signalStore.getIdentityKeyPair().serialize()
        val expectedRegId = signalStore.getLocalRegistrationId()
        val expectedPreKeyCount = signalStore.countOneTimePreKeys()
        val expectedNextPreKeyId = signalStore.nextPreKeyId()
        assertTrue("prekeys were generated", expectedPreKeyCount > 0)

        // Durable flush, then close (final reseal + wipe) and release the directory.
        runtime.flushBeforeAck()
        runtime.close()
        store.close()

        // Reopen from disk and unlock with the passphrase — no in-memory carry-over.
        val store2 = newStore(dir)
        val open2 = store2.unlock(passphrase) ?: error("passphrase unlock failed after reopen")
        val state2 = VaultStateCodec.decode(open2.payloadPlaintext.copyOf())
        val session2 = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = open2.payloadPlaintext,
            initialVaultKey = open2.vaultKey,
            slotIndex = open2.slotIndex,
            persist = store2::writeSealedPayload,
            cooldownMs = 60_000L,
        )
        val runtime2 = VaultRuntime(session2, state2)
        val signalStore2 = VaultSignalProtocolStore(runtime2)
        val authStore2 = VaultAuthStore(runtime2)
        val rosterStore2 = VaultRosterStore(runtime2)

        assertTrue("identity survived", signalStore2.hasLocalIdentity())
        assertArrayEquals("identity bytes are byte-faithful", expectedIdentity, signalStore2.getIdentityKeyPair().serialize())
        assertEquals(expectedRegId, signalStore2.getLocalRegistrationId())
        assertEquals(expectedPreKeyCount, signalStore2.countOneTimePreKeys())
        assertEquals(expectedNextPreKeyId, signalStore2.nextPreKeyId())
        assertEquals("acct-1", authStore2.accountId)
        assertEquals("access-tok", authStore2.accessToken)
        assertEquals("refresh-tok", authStore2.refreshToken)
        assertEquals(rosterJson, rosterStore2.readBlob())

        runtime2.close()
        store2.close()
    }

    // ── #2 biometric dual-wrap: unlockWithKey path ───────────────────────────────

    @Test
    fun `dual-wrap opens the slot via unlockWithKey, wrong key null, invalidated selects passphrase`() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, VaultStateCodec.encode(VaultState.empty()))
        // Keep independent copies — the VaultOpen would normally be consumed by a session.
        val vaultKey = open.vaultKey.copyOf()
        val payload = open.payloadPlaintext.copyOf()

        val biometric = FakeBiometricKeyCipher()
        // Enable = wrap a COPY of the vault key under the (fake) auth-gated key.
        val blob = biometric.wrap(vaultKey.copyOf())
        assertEquals("constant 60-byte blob", WRAPPED_KEY_BYTES, blob.size)

        // Unlock = unwrap the blob, then open the slot with the recovered key (no Argon2id).
        val recovered = biometric.unwrap(blob) ?: error("unwrap failed")
        val reopened = store.unlockWithKey(recovered, open.slotIndex) ?: error("unlockWithKey failed")
        assertArrayEquals("same payload as the passphrase open", payload, reopened.payloadPlaintext)

        // Wrong key → an indistinguishable null (no throw).
        assertNull(store.unlockWithKey(ByteArray(VAULT_KEY_BYTES) { 0x00 }, open.slotIndex))

        // Invalidated key = unwrap returns null → the caller must fall back to the passphrase
        // (it never reaches unlockWithKey). A passphrase unlock still opens the same slot.
        val tampered = blob.copyOf().also { it[NONCE_BYTES] = (it[NONCE_BYTES] + 1).toByte() }
        assertNull("a tampered/invalidated wrap unwraps to null", biometric.unwrap(tampered))
        val viaPass = store.unlock(passphrase) ?: error("passphrase fallback failed")
        assertArrayEquals(payload, viaPass.payloadPlaintext)

        store.close()
    }

    // ── #5 vault contact-delete: single-mutate atomicity ─────────────────────────

    @Test
    fun `contact delete removes records + roster + tombstone in ONE sealed generation`() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, VaultStateCodec.encode(VaultState.empty()))
        val slot = open.slotIndex
        val vaultKey = open.vaultKey.copyOf() // to decode the sealed generation the sink receives

        // Decode a COPY before the session consumes (wipes) the arrays.
        val decoded = VaultStateCodec.decode(open.payloadPlaintext.copyOf())
        // Capture the LAST sealed payload the sink is handed, so we can prove one generation.
        var lastSealed: ByteArray? = null
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = slot,
            persist = { i, sealed -> store.writeSealedPayload(i, sealed); lastSealed = sealed.copyOf() },
            cooldownMs = 60_000L,
        )
        val runtime = VaultRuntime(session, decoded)
        val signalStore = VaultSignalProtocolStore(runtime)
        val rosterStore = VaultRosterStore(runtime)
        val conversations = ConversationRepository(rosterStore)

        // Seed a contact: crypto records + a roster entry.
        runtime.mutate { state ->
            state.signalRecords["session:peer-1:1"] = byteArrayOf(1, 2, 3)
            state.signalRecords["remote_identity:peer-1:1"] = byteArrayOf(4, 5)
            state.signalRecords["sender_key:peer-1:1:uuid"] = byteArrayOf(6)
            state.signalRecords["session:keep-me:1"] = byteArrayOf(9)
        }
        conversations.upsert(
            com.zitrone.app.data.Conversation(id = "peer-1", contactId = "peer-1", displayName = "Peer"),
        )
        conversations.upsert(
            com.zitrone.app.data.Conversation(id = "keep-me", contactId = "keep-me", displayName = "Keep"),
        )
        runtime.flushBeforeAck()

        // The atomic delete via the single-monitor path: crypto records + roster entry + tombstone
        // seal in ONE mutate + ONE flush, run inside the repo's deleteContactDurably (under its
        // monitor). destroyContactCrypto is NEVER called standalone.
        val at = 1_000_000L
        val durable = conversations.deleteContactDurably("peer-1", "peer-1", at) { rosterJson, tombstonesJson ->
            runtime.mutate { state ->
                signalStore.removeContactCryptoRecords(state, "peer-1")
                rosterJson?.let { state.rosterJson = it }
                state.tombstonesJson = tombstonesJson
            }
            runtime.flushBeforeAck()
            true
        }
        assertTrue("the single-mutate delete confirmed durable", durable)

        // Decode the single sealed generation the sink was handed: all three changes present.
        val sealedState = VaultStateCodec.decode(openPayload(vaultKey, lastSealed!!, ops)!!)
        assertFalse("session record gone", sealedState.signalRecords.containsKey("session:peer-1:1"))
        assertFalse("remote identity gone", sealedState.signalRecords.containsKey("remote_identity:peer-1:1"))
        assertFalse("sender key gone", sealedState.signalRecords.containsKey("sender_key:peer-1:1:uuid"))
        assertTrue("the other contact's crypto is untouched", sealedState.signalRecords.containsKey("session:keep-me:1"))
        assertFalse("roster entry gone", sealedState.rosterJson!!.contains("peer-1"))
        assertTrue("roster keeps the other contact", sealedState.rosterJson!!.contains("keep-me"))
        assertTrue("tombstone recorded", sealedState.tombstonesJson!!.contains("peer-1"))

        runtime.close()
        store.close()
        com.zitrone.app.crypto.vault.wipe(vaultKey)
    }

    @Test
    fun `a delete whose flush fails is applied in memory but not acked`() {
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(VaultState.empty()),
            initialVaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 },
            slotIndex = 0,
            persist = { _, _ -> throw IOException("disk full") },
            cooldownMs = 60_000L,
        )
        val state = VaultState.empty().apply {
            signalRecords["session:peer-1:1"] = byteArrayOf(1)
            rosterJson = """[{"id":"peer-1"}]"""
        }
        val runtime = VaultRuntime(session, state)
        val signalStore = VaultSignalProtocolStore(runtime)

        runtime.mutate { s ->
            signalStore.removeContactCryptoRecords(s, "peer-1")
            s.rosterJson = "[]"
            s.tombstonesJson = """{"peer-1":1000000}"""
        }
        // The durable flush fails → DO NOT ACK (throws).
        assertThrows(IOException::class.java) { runtime.flushBeforeAck() }
        // …but the removal is applied in memory (never rolled back).
        assertFalse(runtime.read { it.signalRecords.containsKey("session:peer-1:1") })
        assertEquals("[]", runtime.read { it.rosterJson })

        // close()'s final reseal also hits the failing sink and rethrows after wiping — expected.
        runCatching { runtime.close() }
    }

    // ── fakes ────────────────────────────────────────────────────────────────────

    /** Fixed-key AES-256-GCM stand-in for the Keystore device key (60-byte blob shape). */
    private class FakeDeviceKeyCipher : DeviceKeyCipher {
        private val key = ByteArray(MASTER_KEY_BYTES) { (0xA0 + it).toByte() }
        private val rng = SecureRandom()

        override fun wrapDek(dek: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce))
            return nonce + c.doFinal(dek)
        }

        override fun unwrapDek(blob: ByteArray): ByteArray? {
            if (blob.size != WRAPPED_KEY_BYTES) return null
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AEAD_TAG_BYTES * 8, blob, 0, NONCE_BYTES))
                c.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Fixed-key AES-256-GCM stand-in for [com.zitrone.app.crypto.vault.BiometricVaultKeyCipher]
     * (the real one binds Android Keystore). Wraps the 32-byte vault key into the same
     * `nonce(12) ‖ ct(32) ‖ tag(16)` = 60-byte blob and returns null on a tampered blob.
     */
    private class FakeBiometricKeyCipher {
        private val key = ByteArray(MASTER_KEY_BYTES) { (0x10 + it).toByte() }
        private val rng = SecureRandom()

        fun wrap(vaultKey: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce))
            return nonce + c.doFinal(vaultKey)
        }

        fun unwrap(blob: ByteArray): ByteArray? {
            if (blob.size != WRAPPED_KEY_BYTES) return null
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AEAD_TAG_BYTES * 8, blob, 0, NONCE_BYTES))
                c.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
            } catch (e: Exception) {
                null
            }
        }
    }
}
