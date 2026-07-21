// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.security.SecureRandom

/**
 * Item 4 of contact-deletion: after destroying session + identity for a peer,
 * re-adding them MUST complete a completely fresh X3DH handshake with zero
 * reuse of prior session or key material.
 *
 * Exercises the same libsignal primitives production uses
 * ([SessionBuilder.process] + [SessionCipher]), with an in-memory store that
 * models [com.zitrone.app.crypto.EncryptedSignalProtocolStore.destroyContactCrypto]
 * (deleteAllSessions + forget remote identity) so the property is proven off-device.
 */
class ContactDeleteFreshHandshakeTest {

    private val deviceId = 1
    private val bobAddress = SignalProtocolAddress("bob-account", deviceId)
    private val aliceAddress = SignalProtocolAddress("alice-account", deviceId)

    private data class Party(
        val identity: IdentityKeyPair,
        val registrationId: Int,
        val store: InMemorySignalProtocolStore,
        var signedPreKey: SignedPreKeyRecord,
        var oneTimePreKey: PreKeyRecord,
    )

    /**
     * In-memory store with an explicit forget-identity path so we can model
     * production's deleteRemoteIdentity without EncryptedSharedPreferences.
     */
    private class TeardownStore(
        identity: IdentityKeyPair,
        registrationId: Int,
    ) : InMemorySignalProtocolStore(identity, registrationId) {
        private val forgotten = mutableSetOf<String>()

        override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
            forgotten.remove(address.name)
            return super.saveIdentity(address, identityKey)
        }

        override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
            if (address.name in forgotten) return null
            return super.getIdentity(address)
        }

        override fun isTrustedIdentity(
            address: SignalProtocolAddress,
            identityKey: IdentityKey,
            direction: org.signal.libsignal.protocol.state.IdentityKeyStore.Direction,
        ): Boolean {
            // After teardown, TOFU again — same as an empty remote_identity slot.
            if (address.name in forgotten) return true
            return super.isTrustedIdentity(address, identityKey, direction)
        }

        /** Production [EncryptedSignalProtocolStore.destroyContactCrypto]. */
        fun destroyContact(name: String) {
            deleteAllSessions(name)
            forgotten.add(name)
        }
    }

    private fun makeParty(): Party {
        val identity = IdentityKeyPair.generate()
        val registrationId = SecureRandom().nextInt(16380) + 1
        val store = InMemorySignalProtocolStore(identity, registrationId)
        val signed = generateSignedPreKey(store, identity, id = 1)
        val otp = generateOneTimePreKey(store, id = 1)
        return Party(identity, registrationId, store, signed, otp)
    }

    private fun generateSignedPreKey(
        store: InMemorySignalProtocolStore,
        identity: IdentityKeyPair,
        id: Int,
    ): SignedPreKeyRecord {
        val keyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(identity.privateKey, keyPair.publicKey.serialize())
        val record = SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        store.storeSignedPreKey(id, record)
        return record
    }

    private fun generateOneTimePreKey(store: InMemorySignalProtocolStore, id: Int): PreKeyRecord {
        val keyPair = Curve.generateKeyPair()
        val record = PreKeyRecord(id, keyPair)
        store.storePreKey(id, record)
        return record
    }

    private fun bundleFor(party: Party): PreKeyBundle =
        PreKeyBundle(
            party.registrationId,
            deviceId,
            party.oneTimePreKey.id,
            party.oneTimePreKey.keyPair.publicKey,
            party.signedPreKey.id,
            party.signedPreKey.keyPair.publicKey,
            party.signedPreKey.signature,
            party.identity.publicKey,
        )

    private fun establishOutbound(
        store: InMemorySignalProtocolStore,
        peer: SignalProtocolAddress,
        bundle: PreKeyBundle,
    ) {
        SessionBuilder(store, peer).process(bundle)
    }

    @Test
    fun `delete then re-add produces a fully fresh X3DH session — no key reuse`() {
        val aliceIdentity = IdentityKeyPair.generate()
        val aliceStore = TeardownStore(aliceIdentity, SecureRandom().nextInt(16380) + 1)
        val bob = makeParty()

        // ── first handshake ────────────────────────────────────────────────
        establishOutbound(aliceStore, bobAddress, bundleFor(bob))
        assertTrue("first X3DH must create a session", aliceStore.containsSession(bobAddress))
        val sessionAfterFirst = aliceStore.loadSession(bobAddress)!!.serialize()
        val identityAfterFirst = aliceStore.getIdentity(bobAddress)
        assertNotNull("first handshake must pin Bob's identity", identityAfterFirst)
        assertArrayEquals(bob.identity.publicKey.serialize(), identityAfterFirst!!.serialize())

        // Prove the session works end-to-end before teardown.
        val aliceCipher1 = SessionCipher(aliceStore, bobAddress)
        val ciphertext1 = aliceCipher1.encrypt("hello-first".toByteArray(Charsets.UTF_8))
        assertTrue(
            "first encrypt should be a PreKey message",
            ciphertext1.type == CiphertextMessage.PREKEY_TYPE,
        )
        val bobPlain1 = SessionCipher(bob.store, aliceAddress)
            .decrypt(PreKeySignalMessage(ciphertext1.serialize()))
        assertArrayEquals("hello-first".toByteArray(Charsets.UTF_8), bobPlain1)

        val sessionAfterUse = aliceStore.loadSession(bobAddress)!!.serialize()

        // ── cryptographic teardown ─────────────────────────────────────────
        aliceStore.destroyContact(bobAddress.name)
        assertFalse("session must be gone after destroy", aliceStore.containsSession(bobAddress))
        assertNull("identity must be forgotten after destroy", aliceStore.getIdentity(bobAddress))

        // ── re-add: Bob issues fresh prekeys; Alice runs X3DH again ────────
        bob.signedPreKey = generateSignedPreKey(bob.store, bob.identity, id = 2)
        bob.oneTimePreKey = generateOneTimePreKey(bob.store, id = 2)

        establishOutbound(aliceStore, bobAddress, bundleFor(bob))
        assertTrue("re-add must create a new session", aliceStore.containsSession(bobAddress))
        val sessionAfterSecond = aliceStore.loadSession(bobAddress)!!.serialize()
        val identityAfterSecond = aliceStore.getIdentity(bobAddress)
        assertNotNull(identityAfterSecond)

        // Core property: serialized Double Ratchet state differs — root key,
        // chain keys, and any skipped-message-key map live inside SessionRecord.
        assertFalse(
            "new session must not equal the first post-X3DH session",
            sessionAfterSecond.contentEquals(sessionAfterFirst),
        )
        assertFalse(
            "new session must not equal the post-use (ratcheted) session",
            sessionAfterSecond.contentEquals(sessionAfterUse),
        )
        assertNotEquals(
            sessionAfterFirst.toList(),
            sessionAfterSecond.toList(),
        )

        // Bob's long-term identity key is the same person (expected). Session
        // material above is what must not be reused.
        assertArrayEquals(
            bob.identity.publicKey.serialize(),
            identityAfterSecond!!.serialize(),
        )

        // New session encrypts as a PreKey message again (fresh X3DH initiator
        // state), not a continuing whisper chain from the destroyed session.
        val ciphertext2 = SessionCipher(aliceStore, bobAddress)
            .encrypt("hello-second".toByteArray(Charsets.UTF_8))
        assertTrue(
            "re-add encrypt must be a PreKey (fresh X3DH) message, type=${ciphertext2.type}",
            ciphertext2.type == CiphertextMessage.PREKEY_TYPE,
        )
        // Ephemeral base keys of the two PreKey messages must differ — proves
        // the second handshake generated new X3DH ephemeral material.
        val eph1 = PreKeySignalMessage(ciphertext1.serialize()).baseKey.serialize()
        val eph2 = PreKeySignalMessage(ciphertext2.serialize()).baseKey.serialize()
        assertFalse(
            "X3DH ephemeral base keys must differ across re-add",
            eph1.contentEquals(eph2),
        )
    }

    @Test
    fun `destroyContact is scoped — unrelated peer sessions survive`() {
        val aliceStore = TeardownStore(IdentityKeyPair.generate(), 1)
        val bob = makeParty()
        val carol = makeParty()
        val carolAddress = SignalProtocolAddress("carol-account", deviceId)

        establishOutbound(aliceStore, bobAddress, bundleFor(bob))
        establishOutbound(aliceStore, carolAddress, bundleFor(carol))
        aliceStore.destroyContact(bobAddress.name)

        assertFalse(aliceStore.containsSession(bobAddress))
        assertNull(aliceStore.getIdentity(bobAddress))
        assertTrue("unrelated peer session must survive", aliceStore.containsSession(carolAddress))
        assertNotNull(aliceStore.getIdentity(carolAddress))
    }
}
