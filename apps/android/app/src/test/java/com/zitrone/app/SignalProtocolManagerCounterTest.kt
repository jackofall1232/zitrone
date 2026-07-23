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

        // UNCONFIRMED batch: the relay never acknowledged it, so the next call RE-SERVES the
        // same stored batch (upload retry, round 8) instead of generating — no fresh ids, no
        // orphaned private halves piling into the store.
        val reserved = manager.generateOneTimePreKeys(count = 3)
        assertEquals(listOf(1, 2, 3, 4, 5), reserved.map { it.id })
        assertEquals(5, store.countOneTimePreKeys())

        // Confirmed → a second batch continues the sequence — never reuses an id.
        manager.confirmOneTimePreKeysUploaded()
        val second = manager.generateOneTimePreKeys(count = 3)
        assertEquals(listOf(6, 7, 8), second.map { it.id })
        assertEquals(8, store.countOneTimePreKeys())
    }

    @Test
    fun `an ATTEMPTED batch is never re-served — a fresh batch is generated instead`() {
        // Round 9 (Codex): once the upload REQUEST left the device, a lost response cannot
        // distinguish "relay never got it" from "relay committed it and a peer consumed an id" —
        // and the relay re-inserts a consumed id (ON CONFLICT DO NOTHING + consume-by-DELETE).
        // So an attempted-but-unconfirmed batch must NOT be re-served; its privates stay in the
        // store (a peer may hold a bundle against them) and a fresh batch takes over.
        val first = manager.generateOneTimePreKeys(count = 3)
        assertEquals(listOf(1, 2, 3), first.map { it.id })
        manager.markOneTimePreKeyUploadAttempted()

        val second = manager.generateOneTimePreKeys(count = 3)
        assertEquals("fresh ids, not the attempted batch", listOf(4, 5, 6), second.map { it.id })
        assertEquals("attempted privates retained for in-flight bundles", 6, store.countOneTimePreKeys())

        // The fresh batch reset the attempted flag: an (unattempted) retry re-serves IT.
        assertEquals(listOf(4, 5, 6), manager.generateOneTimePreKeys(count = 3).map { it.id })
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

        // UNCONFIRMED upload: re-serves the SAME stored record on every call (upload retry,
        // round 8 — the age gate alone would never retry, createdAt was already bumped). The
        // rebuild must be byte-identical to the original DTO: same id, key, signature, stamp.
        val reserved = manager.rotateSignedPreKeyIfNeeded()
        assertEquals(rotated, reserved)

        // Confirmed + inside the window → no rotation.
        manager.confirmSignedPreKeyUploaded()
        assertNull(manager.rotateSignedPreKeyIfNeeded())

        // Backdate past the max age → rotates again with the next id.
        store.setSignedPreKeyCreatedAt(
            System.currentTimeMillis() - SignalProtocolManager.SIGNED_PREKEY_MAX_AGE_MS - 1,
        )
        val rotatedAgain = manager.rotateSignedPreKeyIfNeeded()
        assertNotNull(rotatedAgain)
        assertEquals(2, rotatedAgain!!.id)
    }

    @Test
    fun `a pending id whose record vanished clears itself instead of failing forever`() {
        store.setLocalIdentity(IdentityKeyPair.generate(), 1)
        val generated = manager.generateSignedPreKey()
        // Simulate a wiped/repaired store that lost the record but kept the marker.
        store.removeSignedPreKey(generated.id)
        assertNull("no phantom re-serve from a missing record", manager.pendingSignedPreKeyUpload())
        assertEquals("the stale marker was cleared", 0, store.pendingSignedPreKeyUploadId())
    }
}
