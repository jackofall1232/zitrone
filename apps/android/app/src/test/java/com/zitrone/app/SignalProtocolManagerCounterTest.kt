// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.SharedPreferences
import com.zitrone.app.crypto.EncryptedSignalProtocolStore
import com.zitrone.app.crypto.SignalProtocolManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * PR-D2a: SignalProtocolManager was made store-agnostic — it reads/writes the
 * prekey / signed-prekey id counters and the signed-prekey timestamp THROUGH the
 * [com.zitrone.app.crypto.ZitroneSignalStore] accessors instead of reaching into
 * `prefs(PREFS_SIGNAL_STORE)` itself. The manager kept ONLY the wrap-and-increment
 * id logic.
 *
 * These drive the manager over the REAL legacy [EncryptedSignalProtocolStore]
 * (via its [SharedPreferences] seam — [FakeSharedPreferences] stands in for the
 * EncryptedSharedPreferences file), so the store's counter accessors are the
 * actual code under test. The RAW prefs keys and defaults are asserted
 * literally ("next_prekey_id" / "next_signed_prekey_id" /
 * "signed_prekey_created_at", defaults 1 / 1 / 0L) — the exact on-disk contract
 * the pre-refactor SignalProtocolManager wrote, so a key or default regression
 * that would corrupt existing installs' id sequences on upgrade fails here.
 */
class SignalProtocolManagerCounterTest {

    private val prefs: SharedPreferences = FakeSharedPreferences()
    private val store = EncryptedSignalProtocolStore(prefs)
    private val manager = SignalProtocolManager(store)

    @Test
    fun `fresh store reads the legacy counter defaults`() {
        assertEquals(1, store.nextPreKeyId())
        assertEquals(1, store.nextSignedPreKeyId())
        assertEquals(0L, store.signedPreKeyCreatedAt())
    }

    @Test
    fun `one-time prekey ids start at 1 and increment by one across batches`() {
        val first = manager.generateOneTimePreKeys(count = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), first.map { it.id })
        assertEquals("counter advances past the last issued id", 6, store.nextPreKeyId())
        // The RAW on-disk contract — same key + int type the old manager wrote.
        assertEquals(6, prefs.getInt("next_prekey_id", -1))

        // A second batch continues the sequence — never reuses an id.
        val second = manager.generateOneTimePreKeys(count = 3)
        assertEquals(listOf(6, 7, 8), second.map { it.id })
        assertEquals(8, store.countOneTimePreKeys())
    }

    @Test
    fun `signed prekey ids sequence independently and stamp the creation time`() {
        store.setLocalIdentity(IdentityKeyPair.generate(), 1)

        val before = System.currentTimeMillis()
        val first = manager.generateSignedPreKey()
        val after = System.currentTimeMillis()

        assertEquals(1, first.id)
        assertEquals(2, store.nextSignedPreKeyId())
        // The stamp written to the store equals the DTO timestamp AND is a real
        // wall-clock value (same `System.currentTimeMillis()` the old code used),
        // under the same raw key + long type the old manager wrote.
        assertEquals(first.timestampMs, store.signedPreKeyCreatedAt())
        assertEquals(first.timestampMs, prefs.getLong("signed_prekey_created_at", -1L))
        assertTrue(first.timestampMs in before..after)

        val second = manager.generateSignedPreKey()
        assertEquals(2, second.id)
        assertEquals(3, store.nextSignedPreKeyId())
        assertEquals(3, prefs.getInt("next_signed_prekey_id", -1))

        // The one-time-prekey counter is a SEPARATE counter — untouched here.
        assertEquals(1, store.nextPreKeyId())
    }

    @Test
    fun `prekey id wraps back to 1 at the 24-bit ceiling`() {
        // Seed the RAW key an existing install would carry — the store must
        // pick it up (an accessor key typo would read the default 1 instead
        // and silently reset every existing install's sequence on upgrade).
        prefs.edit().putInt("next_prekey_id", 0xFFFFFF).apply()

        val ids = manager.generateOneTimePreKeys(count = 2).map { it.id }
        assertEquals(listOf(0xFFFFFF, 1), ids)
        assertEquals(2, store.nextPreKeyId())
    }

    @Test
    fun `rotateSignedPreKeyIfNeeded honours the 7-day threshold`() {
        store.setLocalIdentity(IdentityKeyPair.generate(), 1)

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
