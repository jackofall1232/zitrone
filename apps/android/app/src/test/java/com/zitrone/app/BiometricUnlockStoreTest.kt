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

        val w = wrap(1) // a VAULT-POOL slot; slot 0 is the burn credential, not biometric-wrappable (F9)
        s.save(w)
        assertTrue(s.isEnabled())
        val loaded = s.load()!!
        assertEquals(1, loaded.slotIndex)
        assertArrayEquals(w.blob, loaded.blob)
    }

    @Test
    fun `a tampered out-of-range slot reads as not-enabled and never reaches unlockWithKey`() {
        // A corrupted/tampered prefs int (slot >= SLOT_COUNT, negative, OR slot 0 = the burn credential)
        // must read as "not enabled" here, NOT be handed to unlockWithKey's require(slotIndex in
        // VAULT_SLOT_RANGE) where it would crash the unlock coroutine.
        val prefs = FakeSharedPreferences()
        val s = BiometricUnlockStore(prefs)
        s.save(wrap(1))
        assertTrue(s.isEnabled())

        // Tamper the persisted slot to an out-of-range value.
        prefs.edit().putInt("biometric_vault_slot", SLOT_COUNT).apply()
        assertFalse("out-of-range slot is not enabled", s.isEnabled())
        assertNull("out-of-range slot loads null (no crash downstream)", s.load())

        prefs.edit().putInt("biometric_vault_slot", -1).apply()
        assertFalse(s.isEnabled())
        assertNull(s.load())

        // Slot 0 (burn) is not a biometric-wrappable vault slot (F9): tampering to it reads not-enabled.
        prefs.edit().putInt("biometric_vault_slot", 0).apply()
        assertFalse("slot 0 (burn) is not enabled", s.isEnabled())
        assertNull("slot 0 loads null (never reaches unlockWithKey)", s.load())
    }

    @Test
    fun `a present but malformed blob reads as not-enabled (no dead unlock button)`() {
        // isEnabled() now validates the wrap (load() != null), so a blob that is present with an
        // in-range slot but does NOT decode to a BLOB_BYTES array must read as NOT enabled — else
        // the lock screen advertises a biometric button that load() resolves to null and can never
        // drive. Two shapes: non-base64 junk, and valid base64 of the wrong length.
        val prefs = FakeSharedPreferences()
        val s = BiometricUnlockStore(prefs)
        s.save(wrap(1))
        assertTrue(s.isEnabled())

        // Corrupt the blob to non-base64 junk while the slot stays in range.
        prefs.edit().putString("biometric_vault_blob", "!!! not base64 !!!").apply()
        assertFalse("malformed base64 blob is not enabled", s.isEnabled())
        assertNull(s.load())

        // Valid base64 but the wrong length (decodes to fewer than BLOB_BYTES bytes).
        val shortBlob = java.util.Base64.getEncoder().encodeToString(ByteArray(8))
        prefs.edit().putString("biometric_vault_blob", shortBlob).apply()
        assertFalse("wrong-length blob is not enabled", s.isEnabled())
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

    @Test
    fun `boundSlotIndex reports the bound slot, null when absent or malformed`() {
        // The read that the A-bound single-wrap enable guard (OQ4) uses: it must return the slot a
        // VALID wrap names, and null in every not-enabled case (no wrap, out-of-range/burn slot,
        // malformed blob) — so the guard treats a corrupt wrap as "no binding" (first-enable-wins),
        // never as a binding to a bogus slot.
        val prefs = FakeSharedPreferences()
        val s = BiometricUnlockStore(prefs)
        assertNull("no wrap → no binding", s.boundSlotIndex())

        s.save(wrap(2))
        assertEquals(2, s.boundSlotIndex())

        // Tracks load(): a tampered out-of-range/burn slot or malformed blob reads as no binding.
        prefs.edit().putInt("biometric_vault_slot", 0).apply()
        assertNull("burn slot 0 is not a valid binding", s.boundSlotIndex())
        prefs.edit().putInt("biometric_vault_slot", 2).apply()
        prefs.edit().putString("biometric_vault_blob", "!!! not base64 !!!").apply()
        assertNull("malformed blob is not a valid binding", s.boundSlotIndex())

        s.save(wrap(3))
        s.clear()
        assertNull("cleared wrap → no binding", s.boundSlotIndex())
    }

    @Test
    fun `enable decision composes the real store binding with the never-repoint guard`() {
        // The end-to-end enable DECISION (as the entrypoint's pre-check and the writer both compute it):
        // VaultUnlockRouter.biometricEnableAllowed(store.boundSlotIndex(), sessionSlot). Exercises the
        // two components together against a REAL store, not just the predicate in isolation (round-1 F4).
        val router = VaultUnlockRouter()
        val s = store()

        // No wrap → first-enable-wins: allowed for any session slot.
        assertTrue(router.biometricEnableAllowed(s.boundSlotIndex(), 2))
        assertTrue(router.biometricEnableAllowed(s.boundSlotIndex(), 1))

        // Wrap bound to slot 1: same-slot re-enable allowed; a DIFFERENT slot is refused (never repoint).
        s.save(wrap(1))
        assertTrue("same-slot re-enable", router.biometricEnableAllowed(s.boundSlotIndex(), 1))
        assertFalse("cross-slot enable refused against the real binding", router.biometricEnableAllowed(s.boundSlotIndex(), 2))

        // Disable → enable in a B (slot-2) session: cleared binding → allowed as a FRESH bind, not a
        // silent A→B repoint (the wrap was cleared first; boundSlotIndex() is null at the write).
        s.clear()
        assertTrue("clear then enable in B is a fresh bind, not a repoint", router.biometricEnableAllowed(s.boundSlotIndex(), 2))
    }
}
