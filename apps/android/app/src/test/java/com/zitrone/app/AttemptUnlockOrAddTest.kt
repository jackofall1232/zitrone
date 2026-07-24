// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.BURN_SLOT_INDEX
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.DirSyncResult
import com.zitrone.app.crypto.vault.IMAGE_BYTES
import com.zitrone.app.crypto.vault.IMAGE_VERSION
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.OUTER_IMAGE_BYTES
import com.zitrone.app.crypto.vault.PAYLOAD_AD
import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
import com.zitrone.app.crypto.vault.SLOT_AD
import com.zitrone.app.crypto.vault.SLOT_COUNT
import com.zitrone.app.crypto.vault.SLOT_PAYLOAD_BYTES
import com.zitrone.app.crypto.vault.UnlockOrAdd
import com.zitrone.app.crypto.vault.VAULT_IMAGE_OUTER_AD
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultImage
import com.zitrone.app.crypto.vault.VaultImageException
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.VaultSodiumOps
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import com.zitrone.app.crypto.vault.decodeImage
import com.zitrone.app.crypto.vault.encodeImage
import com.zitrone.app.crypto.vault.sealPayload
import com.zitrone.app.crypto.vault.sealSlot
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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PR-1 tests for [VaultImageStore.attemptUnlockOrAdd] (0.9.2 second vault + Pucker Burn) and the
 * v2→[VaultImageException.LegacyImage] read-path branch + [VaultImageStore.retireLegacyImage].
 *
 * Same conventions as [VaultImageStoreTest]: the AEAD + CSPRNG path is the REAL production byte path
 * ([LibsodiumVaultOps] over SodiumJava); only Argon2id (→ a fast SHA-256 [fast] stand-in) and the
 * Android Keystore device key (→ [FakeDeviceKeyCipher2]) are swapped for host testing.
 */
class AttemptUnlockOrAddTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val realOps = LibsodiumVaultOps(SodiumJava())
    private val cipher = FakeDeviceKeyCipher2()

    /** Fast deterministic Argon2id stand-in: SHA-256(passphrase ‖ salt). */
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    private fun store(dir: File, ops: VaultSodiumOps = realOps, dirSync: ((File?) -> DirSyncResult)? = null) =
        if (dirSync == null) VaultImageStore(dir, ops, cipher, fast)
        else VaultImageStore(dir, ops, cipher, fast, dirSync)

    private val genesis = "genesis-empty-state".toByteArray(Charsets.UTF_8)

    private fun bin(dir: File) = File(dir, "vault.bin")
    private fun dek(dir: File) = File(dir, "vault.dek")

    private fun decodeOnDiskInner(dir: File): ByteArray {
        val d = cipher.unwrapDek(dek(dir).readBytes())!!
        return realOps.aeadDecrypt(d, bin(dir).readBytes(), VAULT_IMAGE_OUTER_AD)!!
    }

    private fun rewriteInner(dir: File, inner: ByteArray) {
        val d = cipher.unwrapDek(dek(dir).readBytes())!!
        bin(dir).writeBytes(realOps.aeadEncrypt(d, inner, VAULT_IMAGE_OUTER_AD))
    }

    // ─────────────────────────────── functional ───────────────────────────────

    @Test
    fun match_returnsUnlocked_withThePayload() {
        val dir = tmp.newFolder()
        val s = store(dir)
        val content = "vault A keystore".toByteArray(Charsets.UTF_8)
        s.create("passA", content)
        val r = s.attemptUnlockOrAdd("passA", genesis, create = true) // create ignored — match wins
        assertTrue(r is UnlockOrAdd.Unlocked)
        assertArrayEquals(content, (r as UnlockOrAdd.Unlocked).open.payloadPlaintext)
    }

    @Test
    fun create_true_noMatch_createsANewVault_reopenableFromDisk() {
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
        assertTrue(r is UnlockOrAdd.Created)
        assertArrayEquals(genesis, (r as UnlockOrAdd.Created).open.payloadPlaintext)
        // Reopen fresh from disk: the new vault unlocks.
        s.close()
        val fresh = store(dir)
        fresh.open()
        assertTrue(fresh.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
    }

    @Test
    fun reject_noMatch_createFalse_writesNothing() {
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        val before = bin(dir).readBytes()
        val r = s.attemptUnlockOrAdd("nope", genesis, create = false)
        assertEquals(UnlockOrAdd.Rejected, r)
        assertArrayEquals("reject writes nothing — bin byte-identical", before, bin(dir).readBytes())
    }

    @Test
    fun matchWinsOverCreate_existingVaultUnlocked_noWrite() {
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        val before = bin(dir).readBytes()
        val r = s.attemptUnlockOrAdd("passA", genesis, create = true)
        assertTrue(r is UnlockOrAdd.Unlocked)
        assertArrayEquals(before, bin(dir).readBytes())
    }

    // ─────────────────────────────── burn (slot 0) ───────────────────────────────

    /** Arm slot 0 on the on-disk image with [burnPass] (a real sealed slot + payload). */
    private fun armBurnSlot(dir: File, burnPass: String) {
        val inner = decodeOnDiskInner(dir)
        val img = decodeImage(inner)
        val burnKey = realOps.randomBytes(VAULT_KEY_BYTES)
        val slots = img.slots.toMutableList().also {
            it[BURN_SLOT_INDEX] = sealSlot(burnPass, burnKey, realOps, fast)
        }
        val payloads = img.payloads.toMutableList().also {
            it[BURN_SLOT_INDEX] = sealPayload(burnKey, "burn-marker".toByteArray(Charsets.UTF_8), realOps)
        }
        rewriteInner(dir, encodeImage(VaultImage(slots, payloads)))
    }

    @Test
    fun burnPassphrase_matchesSlot0_returnsBurn_writesNothing() {
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        s.close()
        armBurnSlot(dir, "burn-me")
        val fresh = store(dir)
        fresh.open()
        val before = bin(dir).readBytes()
        // create=true too: burn wins over create.
        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = true))
        assertArrayEquals("burn writes nothing", before, bin(dir).readBytes())
    }

    @Test
    fun unarmedSlot0_neverBurns() {
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8)) // slot 0 is filler
        // No armed burn slot → an arbitrary non-matching passphrase rejects, never Burn.
        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("random", genesis, create = false))
    }

    @Test
    fun corruptBurnPayload_stillFiresBurn() {
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        s.close()
        armBurnSlot(dir, "burn-me")
        // Corrupt slot 0's payload region: the wrapped-key MATCH still confirms the burn credential,
        // so a damaged marker must NOT suppress the wipe.
        val inner = decodeOnDiskInner(dir)
        val img = decodeImage(inner)
        val payloads = img.payloads.toMutableList().also {
            it[BURN_SLOT_INDEX] = realOps.randomBytes(SLOT_PAYLOAD_BYTES) // random ≠ a valid sealed payload
        }
        rewriteInner(dir, encodeImage(VaultImage(img.slots, payloads)))
        val fresh = store(dir)
        fresh.open()
        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = false))
    }

    @Test
    fun corruptVaultPayload_onAMatchedVaultSlot_throwsCorruptImage() {
        val dir = tmp.newFolder()
        val s = store(dir)
        val open = s.create("passA", "A".toByteArray(Charsets.UTF_8))
        s.close()
        // Corrupt the matched vault's own payload region.
        val inner = decodeOnDiskInner(dir)
        val img = decodeImage(inner)
        val payloads = img.payloads.toMutableList().also {
            it[open.slotIndex] = realOps.randomBytes(SLOT_PAYLOAD_BYTES)
        }
        rewriteInner(dir, encodeImage(VaultImage(img.slots, payloads)))
        val fresh = store(dir)
        fresh.open()
        assertThrows(VaultImageException.CorruptImage::class.java) {
            fresh.attemptUnlockOrAdd("passA", genesis, create = false)
        }
    }

    // ─────────────────────────── placement / blind overwrite ───────────────────────────

    @Test
    fun createPlacement_neverLandsOnSlot0() {
        // Over many creates, every new vault's slot ∈ 1..SLOT_COUNT-1 (slot 0 reserved), and the pool
        // is actually reachable (covers >1 distinct index).
        val seen = HashSet<Int>()
        repeat(40) {
            val dir = tmp.newFolder()
            val s = store(dir)
            s.create("A", "A".toByteArray(Charsets.UTF_8))
            val r = s.attemptUnlockOrAdd("B$it", genesis, create = true) as UnlockOrAdd.Created
            assertTrue("created slot must be in the vault pool 1..${SLOT_COUNT - 1}", r.open.slotIndex in 1 until SLOT_COUNT)
            seen.add(r.open.slotIndex)
            s.close()
        }
        assertFalse("slot 0 is never a create target", 0 in seen)
        assertTrue("the vault pool is reachable", seen.size >= 2)
    }

    @Test
    fun createCompanion_placesEverydayVaultInThePool_neverSlot0() {
        repeat(30) {
            val dir = tmp.newFolder()
            val open = store(dir).create("A$it", "A".toByteArray(Charsets.UTF_8))
            assertTrue("create() places A in 1..${SLOT_COUNT - 1}", open.slotIndex in 1 until SLOT_COUNT)
        }
    }

    @Test
    fun blindOverwrite_forcedOntoExistingVaultSlot_destroysIt() {
        // Force EVERY placement to the same pool slot (slot 1): A is created there, then B is created
        // there too, overwriting A — the accepted VeraCrypt-model ~1/3 collision, made deterministic.
        val dir = tmp.newFolder()
        val forced = ForceVaultIndexOps(realOps, targetPoolIndex = 1)
        val s = store(dir, ops = forced)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        assertTrue(s.attemptUnlockOrAdd("passA", genesis, create = false) is UnlockOrAdd.Unlocked)
        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
        assertTrue(r is UnlockOrAdd.Created)
        // A is gone (overwritten); B unlocks.
        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passA", genesis, create = false))
        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
    }

    // ─────────────────────────── delete-marker interaction (OQ3) ───────────────────────────

    @Test
    fun create_failsClosed_whenDeleteIntentPresent_markerUntouched_nothingWritten() {
        // B1 (reversal of OQ3): a create over an image carrying a delete marker must NOT create and must
        // NOT clear the marker — it returns Rejected (like a wrong password), leaving A's delete-state
        // machine intact. The old behavior (clearing the marker) cancelled A's account-delete reconcile.
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        s.markDeleteIntent()
        val before = bin(dir).readBytes()
        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
        assertTrue("intent marker is NOT cleared", File(dir, "vault.delete-intent").exists())
        assertArrayEquals("nothing written on the fail-closed reject", before, bin(dir).readBytes())
        // And passB did not create a vault: after retiring the marker, the pool is unchanged.
        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
    }

    @Test
    fun create_failsClosed_whenServerDeleteConfirmedPresent() {
        // The confirmed marker is the sole authorization for boot-time auto-destroy; a create must never
        // clear it (that would strand a server-deleted account's forensic image).
        val dir = tmp.newFolder()
        val s = store(dir)
        s.create("passA", "A".toByteArray(Charsets.UTF_8))
        s.markServerDeleteConfirmed()
        val before = bin(dir).readBytes()
        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
        assertTrue("confirmed marker is NOT cleared", File(dir, "vault.delete-confirmed").exists())
        assertArrayEquals(before, bin(dir).readBytes())
    }

    @Test
    fun create_selfVerifiesTheSealedSlot_throwsAndPersistsNothing_onAMisSealingProvider() {
        // B2: a miscomputing aeadEncrypt (size-correct, wrong-content wrapped key) must be caught by the
        // candidate self-verify BEFORE anything is persisted — otherwise the new vault would be written
        // durably yet be permanently unopenable after process death.
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        val misSealing = MisSealingWrappedKeyOps(realOps)
        val s = store(dir, ops = misSealing)
        s.open()
        val before = bin(dir).readBytes()
        assertThrows(IllegalStateException::class.java) {
            s.attemptUnlockOrAdd("passB", genesis, create = true)
        }
        assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
    }

    @Test
    fun create_selfVerifiesThePayload_throwsAndPersistsNothing_onAMisSealingPayloadProvider() {
        // G3: a miscomputing PAYLOAD aeadEncrypt producing a SELF-CONSISTENT but WRONG-content box (it
        // decrypts fine, just not to genesisPayload) must be caught by the payload self-verify's
        // CONSTANT-TIME CONTENT compare BEFORE anything is persisted — otherwise a full working session runs
        // over a vault that is permanently unopenable after process death. A "decryption succeeded" check
        // alone would NOT catch this.
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        val misSealing = MisSealingPayloadOps(realOps)
        val s = store(dir, ops = misSealing)
        s.open()
        val before = bin(dir).readBytes()
        assertThrows(IllegalStateException::class.java) {
            s.attemptUnlockOrAdd("passB", genesis, create = true)
        }
        assertArrayEquals("a failed payload self-verify persists nothing", before, bin(dir).readBytes())
    }

    @Test
    fun create_selfVerifiesThePayload_throwsOnNonAuthenticatingBox_persistsNothing() {
        // The OTHER arm of the payload self-verify (the "did not open" path): a payload box that does not
        // AUTHENTICATE (openPayload returns null) must also fail closed with a throw before any persist.
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        val s = store(dir, ops = CorruptPayloadBoxOps(realOps))
        s.open()
        val before = bin(dir).readBytes()
        assertThrows(IllegalStateException::class.java) {
            s.attemptUnlockOrAdd("passB", genesis, create = true)
        }
        assertArrayEquals("a non-authenticating payload box persists nothing", before, bin(dir).readBytes())
    }

    // ─────────────────────────── durability ───────────────────────────

    @Test
    fun create_notDurable_throwsNotDurable_butCanonicalAdvanced() {
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        val s = store(dir, dirSync = { DirSyncResult.NOT_DURABLE })
        s.open()
        assertThrows(VaultImageException.NotDurable::class.java) {
            s.attemptUnlockOrAdd("passB", genesis, create = true)
        }
        // canonical advanced (bytes are on disk): the new vault unlocks IN-MEMORY on the same store.
        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
    }

    // ─────────────────────────── crypto-budget PARITY (load-bearing) ───────────────────────────

    @Test
    fun cryptoBudgetParity_5argon2id_6wrappedGcm_acrossOutcomes_createAloneDoublesPayloadAndOuter() {
        // Every outcome issues IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal) and
        // 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt + 1 candidate self-verify decrypt, B2).
        // Payload GCM is 1 on every outcome EXCEPT a SUCCESSFUL create, which does 2 (seal + a self-verify
        // open, G3) and also the one ~1 MiB outer GCM — both create-only persist residuals. The
        // marker-present create FAILS CLOSED to the exact reject budget (1 payload GCM, no outer), so it is
        // indistinguishable from an ordinary wrong password.
        fun measure(outcome: String, prep: (File) -> Unit, call: (VaultImageStore) -> Unit) {
            val dir = tmp.newFolder()
            prep(dir)
            val counting = CountingOps(realOps)
            val counter = CountingDeriver(fast)
            val s = VaultImageStore(dir, counting, cipher, counter.deriver)
            s.open()
            counting.reset(); counter.calls = 0 // measure ONLY the attempt
            call(s)
            assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
            // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
            assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
            // A successful create seals genesis AND self-verifies it (G3) = 2; every other outcome = 1.
            val expectedPayload = if (outcome == "create") 2 else 1
            assertEquals("$outcome: payload GCM (create seals+verifies=2, else 1)", expectedPayload, counting.payloadOps)
            val expectedOuter = if (outcome == "create") 1 else 0
            assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
        }
        // Setup uses the real deriver-injected store; but prep must seal with the SAME `fast` deriver so
        // matches work when the measured store re-derives. Build vaults with a helper store.
        val vaultContent = "content".toByteArray(Charsets.UTF_8)
        measure("unlock",
            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
        measure("reject",
            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
        measure("create",
            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
        measure("burn",
            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() }; armBurnSlot(d, "burn-me") },
            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
        // B1 fail-closed: a create attempt while a delete marker is present must have the SAME budget as an
        // ordinary reject (5 Argon2id + 1 payload GCM + 6 wrapped + NO outer GCM) — no timing side channel
        // distinguishes "creation refused because a delete is pending" from a wrong password.
        measure("marker-reject",
            prep = { d -> store(d).also { it.create("passA", vaultContent); it.markDeleteIntent(); it.close() } },
            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
    }

    // ─────────────────────────── legacy (v2) image handling ───────────────────────────

    @Test
    fun v2Image_open_throwsLegacyImage_notCorruptImage() {
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        val inner = decodeOnDiskInner(dir)
        inner[0] = LEGACY_IMAGE_VERSION.toByte() // downgrade the version byte to v2
        rewriteInner(dir, inner)
        assertThrows(VaultImageException.LegacyImage::class.java) { store(dir).open() }
    }

    @Test
    fun isLegacyImage_trueForV2_falseForCurrent() {
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        assertFalse("current version is not legacy", store(dir).isLegacyImage())
        val inner = decodeOnDiskInner(dir)
        inner[0] = LEGACY_IMAGE_VERSION.toByte()
        rewriteInner(dir, inner)
        assertTrue("v2 is legacy", store(dir).isLegacyImage())
    }

    @Test
    fun retireLegacyImage_deletesV2_butRefusesToTouchCurrent() {
        // Refuses (and deletes nothing) on a CURRENT-version image.
        val dir = tmp.newFolder()
        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
        assertThrows(IllegalStateException::class.java) { store(dir).retireLegacyImage() }
        assertTrue("a current image survives a misrouted retire", bin(dir).exists() && dek(dir).exists())
        // Retires a genuine v2 image.
        val inner = decodeOnDiskInner(dir)
        inner[0] = LEGACY_IMAGE_VERSION.toByte()
        rewriteInner(dir, inner)
        store(dir).retireLegacyImage()
        assertFalse("v2 bin unlinked", bin(dir).exists())
        assertFalse("v2 dek unlinked", dek(dir).exists())
    }

    // ─────────────────────────── test doubles ───────────────────────────

    /** Fixed-key device cipher (host stand-in for the Keystore key). */
    private class FakeDeviceKeyCipher2 : DeviceKeyCipher {
        private val key = ByteArray(32) { (it * 7 + 1).toByte() }
        private val g = LibsodiumVaultOps(SodiumJava())
        override fun wrapDek(dek: ByteArray): ByteArray {
            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val ct = c.doFinal(dek)
            return nonce + ct
        }
        override fun unwrapDek(blob: ByteArray): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
            c.doFinal(blob, 12, blob.size - 12)
        } catch (t: Throwable) { null }
    }

    /** Counts Argon2id (deriver) invocations. */
    private class CountingDeriver(private val inner: KeyDeriver) {
        var calls = 0
        val deriver: KeyDeriver = { p, s -> calls++; inner(p, s) }
    }

    /** Classifies each AEAD op by size so the parity invariant is checkable. */
    private class CountingOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
        var wrappedOps = 0 // 60-byte wrapped-key seal/unwrap
        var payloadOps = 0 // 256 KiB payload seal/open
        var outerOps = 0   // ~1 MiB outer image seal/open
        fun reset() { wrappedOps = 0; payloadOps = 0; outerOps = 0 }
        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
        override fun randomBytes(length: Int) = inner.randomBytes(length)
        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            when (plaintext.size) {
                VAULT_KEY_BYTES -> wrappedOps++          // sealSlot wraps a 32-byte vault key
                PAYLOAD_PLAINTEXT_BYTES -> payloadOps++  // sealPayload pads to full plaintext capacity
                IMAGE_BYTES -> outerOps++                // outer image encrypt
            }
            return inner.aeadEncrypt(key, plaintext, associatedData)
        }
        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray): ByteArray? {
            when (box.size) {
                WRAPPED_KEY_BYTES -> wrappedOps++        // tryPassphrase unwraps each 60-byte slot
                SLOT_PAYLOAD_BYTES -> payloadOps++       // openPayload
                OUTER_IMAGE_BYTES -> outerOps++          // outer image decrypt
            }
            return inner.aeadDecrypt(key, box, associatedData)
        }
    }

    /**
     * Forces every vault-pool placement to [targetPoolIndex] by intercepting the single 4-byte CSPRNG
     * draw `randomIndex` uses (unique to index selection — salts/nonces/keys are 16/12/32 bytes). Returns
     * bytes encoding (targetPoolIndex-1) so `randomVaultSlotIndex` = 1 + ((targetPoolIndex-1) % (N-1)).
     */
    private class ForceVaultIndexOps(private val inner: VaultSodiumOps, targetPoolIndex: Int) : VaultSodiumOps {
        private val forced = byteArrayOf(0, 0, 0, (targetPoolIndex - 1).toByte())
        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
            inner.aeadEncrypt(key, plaintext, associatedData)
        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
            inner.aeadDecrypt(key, box, associatedData)
        override fun randomBytes(length: Int) = if (length == 4) forced.copyOf() else inner.randomBytes(length)
    }

    /**
     * Miscomputes ONLY the wrapped-key layer (`SLOT_AD`): returns a size-correct but bit-flipped wrapped
     * blob so it no longer decrypts back to the vault key. Every other AEAD op (payload, outer image) is
     * the real byte path, so the store opens/reads normally — the defect surfaces only at the candidate
     * self-verify (B2).
     */
    private class MisSealingWrappedKeyOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
        override fun randomBytes(length: Int) = inner.randomBytes(length)
        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
            inner.aeadDecrypt(key, box, associatedData)
        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val out = inner.aeadEncrypt(key, plaintext, associatedData)
            if (associatedData.contentEquals(SLOT_AD)) out[out.size - 1] = (out[out.size - 1].toInt() xor 0x01).toByte()
            return out
        }
    }

    /**
     * Miscomputes ONLY the payload layer (`PAYLOAD_AD`): flips the first CONTENT byte of the plaintext (just
     * past the 4-byte length prefix) before encrypting, so the box decrypts SUCCESSFULLY but to the wrong
     * content. Every other AEAD op is the real byte path. Exercises the G3 payload self-verify's constant-
     * time CONTENT compare — which a "decryption succeeded" check alone would not.
     */
    private class MisSealingPayloadOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
        override fun randomBytes(length: Int) = inner.randomBytes(length)
        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
            inner.aeadDecrypt(key, box, associatedData)
        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            if (!associatedData.contentEquals(PAYLOAD_AD)) return inner.aeadEncrypt(key, plaintext, associatedData)
            val p = plaintext.copyOf() // don't mutate the caller's buffer
            p[4] = (p[4].toInt() xor 0x01).toByte() // flip content[0] (index 4 = just past the length prefix)
            return inner.aeadEncrypt(key, p, associatedData)
        }
    }

    /**
     * Corrupts the CIPHERTEXT (tag byte) of the payload layer (`PAYLOAD_AD`) so the box no longer
     * authenticates: `openPayload` returns null. Exercises the "did not open" arm of the G3 payload verify.
     */
    private class CorruptPayloadBoxOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
        override fun randomBytes(length: Int) = inner.randomBytes(length)
        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
            inner.aeadDecrypt(key, box, associatedData)
        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val out = inner.aeadEncrypt(key, plaintext, associatedData)
            if (associatedData.contentEquals(PAYLOAD_AD)) out[out.size - 1] = (out[out.size - 1].toInt() xor 0x01).toByte()
            return out
        }
    }
}
