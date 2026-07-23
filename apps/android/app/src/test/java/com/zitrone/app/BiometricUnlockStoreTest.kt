// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.BiometricWrappedKey
import com.zitrone.app.crypto.vault.SLOT_COUNT
import com.zitrone.app.data.BiometricUnlockStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted biometric-wrap store (posture B): the slot-index bound and the disable revoke.
 * Host-JVM over the in-memory [FakeSharedPreferences] (no Android runtime).
 */
class BiometricUnlockStoreTest {

    private fun store() = BiometricUnlockStore(FakeSharedPreferences())
    private fun wrap(slot: Int) = BiometricWrappedKey(slot, ByteArray(BiometricWrappedKey.BLOB_BYTES) { it.toByte() })

    @Test
    fun `a valid wrap round-trips and reads enabled`() {
        val s = store()
        assertFalse(s.isEnabled())
        assertNull(s.load())

        val w = wrap(0)
        s.save(w)
        assertTrue(s.isEnabled())
        val loaded = s.load()!!
        assertEquals(0, loaded.slotIndex)
        assertArrayEquals(w.blob, loaded.blob)
    }

    @Test
    fun `a tampered out-of-range slot reads as not-enabled and never reaches unlockWithKey`() {
        // A corrupted/tampered prefs int (slot >= SLOT_COUNT, or negative) must read as "not
        // enabled" here, NOT be handed to unlockWithKey's require(slotIndex in 0 until SLOT_COUNT)
        // where it would crash the unlock coroutine.
        val prefs = FakeSharedPreferences()
        val s = BiometricUnlockStore(prefs)
        s.save(wrap(0))
        assertTrue(s.isEnabled())

        // Tamper the persisted slot to an out-of-range value.
        prefs.edit().putInt("biometric_vault_slot", SLOT_COUNT).apply()
        assertFalse("out-of-range slot is not enabled", s.isEnabled())
        assertNull("out-of-range slot loads null (no crash downstream)", s.load())

        prefs.edit().putInt("biometric_vault_slot", -1).apply()
        assertFalse(s.isEnabled())
        assertNull(s.load())
    }

    @Test
    fun `clear revokes the wrap (disable actually works)`() {
        val s = store()
        s.save(wrap(1))
        assertTrue(s.isEnabled())

        s.clear()
        assertFalse("disable must revoke the persisted wrap", s.isEnabled())
        assertNull(s.load())
    }
}
