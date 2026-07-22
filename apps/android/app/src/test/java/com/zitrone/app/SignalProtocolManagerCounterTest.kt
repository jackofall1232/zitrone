// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.crypto.ZitroneSignalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * PR-D2a: SignalProtocolManager was made store-agnostic — it reads/writes the
 * prekey / signed-prekey id counters and the signed-prekey timestamp THROUGH the
 * [ZitroneSignalStore] accessors instead of reaching into `prefs(PREFS_SIGNAL_STORE)`
 * itself. The manager kept ONLY the wrap-and-increment id logic.
 *
 * This drives the manager over an in-memory [ZitroneSignalStore] whose counter
 * accessors are a FAITHFUL TWIN of the legacy [com.zitrone.app.crypto.EncryptedSignalProtocolStore]
 * — the SAME defaults the legacy prefs use (`getInt(_, 1)` for the two id
 * counters, `getLong(_, 0L)` for the timestamp) and plain get/set semantics. It
 * asserts the id sequences, the counter independence, the 24-bit wrap, and the
 * signed-prekey timestamp/rotation behave EXACTLY as before the refactor.
 * (The real legacy store is EncryptedSharedPreferences-backed and not host-JVM
 * instantiable; the byte-for-byte key/default equivalence is verified in the
 * store source and asserted structurally here.)
 */
class SignalProtocolManagerCounterTest {

    /**
     * libsignal's [InMemorySignalProtocolStore] for the [org.signal.libsignal.protocol.state.SignalProtocolStore]
     * surface, plus in-memory counters seeded with the legacy defaults (1 / 1 / 0L).
     */
    private class InMemoryZitroneSignalStore(
        identity: IdentityKeyPair = IdentityKeyPair.generate(),
        registrationId: Int = 1,
    ) : InMemorySignalProtocolStore(identity, registrationId), ZitroneSignalStore {

        private var nextPreKey = 1
        private var nextSignedPreKey = 1
        private var signedCreatedAt = 0L
        private val preKeyIds = mutableSetOf<Int>()

        override fun hasLocalIdentity(): Boolean = true
        override fun setLocalIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int) = Unit

        override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
            preKeyIds.add(preKeyId)
            super.storePreKey(preKeyId, record)
        }

        override fun removePreKey(preKeyId: Int) {
            preKeyIds.remove(preKeyId)
            super.removePreKey(preKeyId)
        }

        override fun countOneTimePreKeys(): Int = preKeyIds.size

        override fun nextPreKeyId(): Int = nextPreKey
        override fun setNextPreKeyId(value: Int) { nextPreKey = value }
        override fun nextSignedPreKeyId(): Int = nextSignedPreKey
        override fun setNextSignedPreKeyId(value: Int) { nextSignedPreKey = value }
        override fun signedPreKeyCreatedAt(): Long = signedCreatedAt
        override fun setSignedPreKeyCreatedAt(value: Long) { signedCreatedAt = value }

        override fun destroyContactCrypto(name: String): Boolean = true
        override fun knownRemoteContacts(): List<Pair<String, String?>> = emptyList()
        override fun wipe() = Unit
    }

    @Test
    fun `one-time prekey ids start at 1 and increment by one across batches`() {
        val store = InMemoryZitroneSignalStore()
        val manager = SignalProtocolManager(store)

        val first = manager.generateOneTimePreKeys(count = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), first.map { it.id })
        assertEquals("counter advances past the last issued id", 6, store.nextPreKeyId())

        // A second batch continues the sequence — never reuses an id.
        val second = manager.generateOneTimePreKeys(count = 3)
        assertEquals(listOf(6, 7, 8), second.map { it.id })
        assertEquals(8, store.countOneTimePreKeys())
    }

    @Test
    fun `signed prekey ids sequence independently and stamp the creation time`() {
        val store = InMemoryZitroneSignalStore()
        val manager = SignalProtocolManager(store)

        val before = System.currentTimeMillis()
        val first = manager.generateSignedPreKey()
        val after = System.currentTimeMillis()

        assertEquals(1, first.id)
        assertEquals(2, store.nextSignedPreKeyId())
        // The stamp written to the store equals the DTO timestamp AND is a real
        // wall-clock value (same `System.currentTimeMillis()` the old code used).
        assertEquals(first.timestampMs, store.signedPreKeyCreatedAt())
        assertTrue(first.timestampMs in before..after)

        val second = manager.generateSignedPreKey()
        assertEquals(2, second.id)
        assertEquals(3, store.nextSignedPreKeyId())

        // The one-time-prekey counter is a SEPARATE counter — untouched here.
        assertEquals(1, store.nextPreKeyId())
    }

    @Test
    fun `prekey id wraps back to 1 at the 24-bit ceiling`() {
        val store = InMemoryZitroneSignalStore()
        store.setNextPreKeyId(0xFFFFFF)
        val manager = SignalProtocolManager(store)

        val ids = manager.generateOneTimePreKeys(count = 2).map { it.id }
        assertEquals(listOf(0xFFFFFF, 1), ids)
        assertEquals(2, store.nextPreKeyId())
    }

    @Test
    fun `rotateSignedPreKeyIfNeeded honours the 7-day threshold`() {
        val store = InMemoryZitroneSignalStore()
        val manager = SignalProtocolManager(store)

        // Fresh store (createdAt == 0) always generates.
        val rotated = manager.rotateSignedPreKeyIfNeeded()
        assertNotNull(rotated)
        assertEquals(1, rotated!!.id)

        // Just generated → inside the window → no rotation.
        assertNull(manager.rotateSignedPreKeyIfNeeded())

        // Backdate past the max age → rotates again with the next id.
        store.setSignedPreKeyCreatedAt(
            System.currentTimeMillis() - SignalProtocolManager.SIGNED_PREKEY_MAX_AGE_MS - 1,
        )
        val rotatedAgain = manager.rotateSignedPreKeyIfNeeded()
        assertNotNull(rotatedAgain)
        assertEquals(2, rotatedAgain!!.id)
    }
}
