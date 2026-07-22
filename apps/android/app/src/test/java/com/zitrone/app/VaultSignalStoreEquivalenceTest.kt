// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.VaultSignalProtocolStore
import com.zitrone.app.crypto.vault.AEAD_TAG_BYTES
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.EmptyCoroutineContext

/**
 * THE load-bearing PR-C deliverable: a REAL libsignal handshake driven through
 * [VaultSignalProtocolStore], persisted through a REAL [VaultRuntime] + [VaultSession] +
 * [VaultImageStore] to a real temp file, then RESUMED from disk by a FRESH store/runtime —
 * a simulated process restart. If the restored Alice can continue the Double Ratchet and
 * decrypt a new message, then PR-A (session) + PR-B (image store) + PR-C (codec + runtime +
 * signal facade) compose end to end and preserve exact ratchet state across a restart.
 *
 * Alice runs on the vault-backed store; Bob is a plain libsignal [InMemorySignalProtocolStore]
 * peer (the same pattern as ContactDeleteFreshHandshakeTest). Only the Argon2id KDF is swapped
 * for a fast SHA-256 stand-in and the Keystore device key for a fixed-key javax.crypto fake —
 * every other byte path (AEAD, CSPRNG, DEFLATE, libsignal X3DH/ratchet, real file I/O) is real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultSignalStoreEquivalenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }
    private val cipher = FakeDeviceKeyCipher()
    private val passphrase = "correct horse battery staple"

    private val deviceId = 1
    private val bobAddress = SignalProtocolAddress("bob-account", deviceId)
    private val aliceAddress = SignalProtocolAddress("alice-account", deviceId)

    private fun newStore(dir: File) = VaultImageStore(dir, ops, cipher, fast)

    @Test
    fun `full stack preserves ratchet state across a simulated process restart`() = runTest {
        val dir = tmp.newFolder()

        // ── genesis: create the vault image around an empty encoded keystore ──
        val store = newStore(dir)
        val open = store.create(passphrase, VaultStateCodec.encode(VaultState.empty()))
        // Decode the initial state BEFORE the session takes ownership of open.payloadPlaintext.
        val initialState = VaultStateCodec.decode(open.payloadPlaintext)
        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = store::writeSealedPayload, // THE composition point (PR-A ↔ PR-B)
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
        )
        val runtime = VaultRuntime(session, initialState)
        val aliceStore = VaultSignalProtocolStore(runtime)

        // Alice's long-term identity lives in the vault.
        val aliceIdentity = IdentityKeyPair.generate()
        aliceStore.setLocalIdentity(aliceIdentity, SecureRandom().nextInt(16380) + 1)

        // Bob: a plain in-memory peer publishing a one-time prekey bundle.
        val bob = makeParty()

        // ── real X3DH + a message each way (advances Alice's ratchet, all via the vault) ──
        SessionBuilder(aliceStore, bobAddress).process(bundleFor(bob))
        val a1 = SessionCipher(aliceStore, bobAddress).encrypt("m1-alice-to-bob".toByteArray(Charsets.UTF_8))
        assertEquals("first message is a PreKey (X3DH) message", CiphertextMessage.PREKEY_TYPE, a1.type)
        assertArrayEquals(
            "m1-alice-to-bob".toByteArray(Charsets.UTF_8),
            decryptInbound(SessionCipher(bob.store, aliceAddress), a1),
        )
        val b1 = SessionCipher(bob.store, aliceAddress).encrypt("m2-bob-to-alice".toByteArray(Charsets.UTF_8))
        assertArrayEquals(
            "m2-bob-to-alice".toByteArray(Charsets.UTF_8),
            decryptInbound(SessionCipher(aliceStore, bobAddress), b1),
        )

        // ── persist durably, then tear down: the process ends ──
        runtime.flushBeforeAck() // force flush-before-ack: the whole keystore to disk
        runtime.close() // final flush + wipe
        store.close() // release the single-instance registration (a real restart ends the process)

        // ── simulated restart: a FRESH store + runtime reading ONLY from disk ──
        val freshStore = newStore(dir)
        val reopened = freshStore.unlock(passphrase)
        assertNotNull("fresh store must unlock from disk", reopened)
        val restoredState = VaultStateCodec.decode(reopened!!.payloadPlaintext)
        val session2 = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialPayload = reopened.payloadPlaintext,
            initialVaultKey = reopened.vaultKey,
            slotIndex = reopened.slotIndex,
            persist = freshStore::writeSealedPayload,
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
        )
        val runtime2 = VaultRuntime(session2, restoredState)
        val aliceStore2 = VaultSignalProtocolStore(runtime2)

        // Alice's identity + session survived the seal → disk → unseal round trip.
        assertArrayEquals(
            "identity survived restart",
            aliceIdentity.serialize(),
            aliceStore2.getIdentityKeyPair().serialize(),
        )
        assertTrue("session with Bob survived restart", aliceStore2.containsSession(bobAddress))

        // ── CONTINUE the ratchet: Bob sends another message; RESTORED Alice decrypts it ──
        val b2 = SessionCipher(bob.store, aliceAddress).encrypt("m3-after-restart".toByteArray(Charsets.UTF_8))
        assertArrayEquals(
            "PR-A+B+C compose end to end: the ratchet resumes across a restart",
            "m3-after-restart".toByteArray(Charsets.UTF_8),
            decryptInbound(SessionCipher(aliceStore2, bobAddress), b2),
        )

        runtime2.close()
        freshStore.close()
    }

    private fun decryptInbound(cipher: SessionCipher, message: CiphertextMessage): ByteArray =
        when (message.type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(message.serialize()))
            else -> cipher.decrypt(SignalMessage(message.serialize()))
        }

    // ── Bob-side libsignal helpers (mirror ContactDeleteFreshHandshakeTest) ──────────

    private data class Party(
        val identity: IdentityKeyPair,
        val registrationId: Int,
        val store: InMemorySignalProtocolStore,
        val signedPreKey: SignedPreKeyRecord,
        val oneTimePreKey: PreKeyRecord,
    )

    private fun makeParty(): Party {
        val identity = IdentityKeyPair.generate()
        val registrationId = SecureRandom().nextInt(16380) + 1
        val store = InMemorySignalProtocolStore(identity, registrationId)
        val signedKeyPair = Curve.generateKeyPair()
        val signedSig = Curve.calculateSignature(identity.privateKey, signedKeyPair.publicKey.serialize())
        val signed = SignedPreKeyRecord(1, System.currentTimeMillis(), signedKeyPair, signedSig)
        store.storeSignedPreKey(1, signed)
        val otp = PreKeyRecord(1, Curve.generateKeyPair())
        store.storePreKey(1, otp)
        return Party(identity, registrationId, store, signed, otp)
    }

    private fun bundleFor(party: Party): PreKeyBundle = PreKeyBundle(
        party.registrationId,
        deviceId,
        party.oneTimePreKey.id,
        party.oneTimePreKey.keyPair.publicKey,
        party.signedPreKey.id,
        party.signedPreKey.keyPair.publicKey,
        party.signedPreKey.signature,
        party.identity.publicKey,
    )

    /** Fixed-key javax.crypto AES-256-GCM stand-in for the Keystore device key (host-testable). */
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
            } catch (e: GeneralSecurityException) {
                null
            }
        }
    }
}
