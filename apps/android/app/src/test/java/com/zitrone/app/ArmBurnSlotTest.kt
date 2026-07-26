// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.ArmBurn
import com.zitrone.app.crypto.vault.BURN_SLOT_INDEX
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.DirSyncResult
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.SLOT_COUNT
import com.zitrone.app.crypto.vault.UnlockOrAdd
import com.zitrone.app.crypto.vault.VAULT_IMAGE_OUTER_AD
import com.zitrone.app.crypto.vault.VaultImageException
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.VaultSodiumOps
import com.zitrone.app.crypto.vault.decodeImage
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ARMING THE PUCKER BURN CREDENTIAL (0.9.3 Unit S) — `VaultImageStore.armBurnSlot`.
 *
 * This is the first writer ever to put a meaningful value in slot 0, and the WRITER/READER table
 * (`reviews/vault-0.9.x/unit-s-invariant-table.md`) found exactly one interaction with an existing
 * reader. That interaction is the first test below, and it is a CORRECTNESS property:
 *
 * **`tryPassphrase` records the FIRST match by ASCENDING slot index, and slot 0 is index 0**, so an
 * armed slot 0 outranks every vault slot. A burn credential that also opens a vault would mean the
 * user's next ordinary unlock WIPES THE DEVICE instead of opening that vault.
 *
 * The DoD for 0.9.3 is "the burn works", so these tests assert the round trip — arm, then enter the
 * credential and observe [UnlockOrAdd.Burn] — rather than merely that a write happened.
 */
class ArmBurnSlotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val realOps = LibsodiumVaultOps(SodiumJava())

    /**
     * Real AES-GCM, matching the production blob shape (`nonce(12) ‖ ct+tag`). A hand-rolled
     * concatenation fake produces a size the store correctly rejects as "malformed wrapped key" — the
     * store's shape check is doing its job, so the fake has to be honest rather than the check relaxed.
     */
    private class FixedKeyCipher : DeviceKeyCipher {
        private val key = ByteArray(32) { (it * 7 + 1).toByte() }
        override fun wrapDek(dek: ByteArray): ByteArray {
            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce),
            )
            return nonce + c.doFinal(dek)
        }
        override fun unwrapDek(blob: ByteArray): ByteArray? = try {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12),
            )
            c.doFinal(blob, 12, blob.size - 12)
        } catch (t: Throwable) { null }
    }

    /** Fast deterministic Argon2id stand-in: SHA-256(passphrase ‖ salt). */
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    private val cipher = FixedKeyCipher()

    private fun store(dir: File, dirSync: ((File?) -> DirSyncResult)? = null): VaultImageStore =
        if (dirSync == null) VaultImageStore(dir, realOps, cipher, fast)
        else VaultImageStore(dir, realOps, cipher, fast, dirSync)

    private val genesis = "genesis-empty-state".toByteArray(Charsets.UTF_8)

    /**
     * Decode the on-disk inner image, the same way the other store tests do — deliberately NOT via a
     * test-only accessor on [VaultImageStore]. Reading the real file also makes the structural
     * assertions below statements about what a forensic examiner would see, not about RAM.
     */
    private fun onDiskInner(dir: File, cipher: DeviceKeyCipher): ByteArray {
        val d = cipher.unwrapDek(File(dir, "vault.dek").readBytes())!!
        return realOps.aeadDecrypt(d, File(dir, "vault.bin").readBytes(), VAULT_IMAGE_OUTER_AD)!!
    }

    private fun freshVault(dir: File, passphrase: String = VAULT_PASS): VaultImageStore =
        store(dir).also { it.create(passphrase, genesis).also { open -> open.vaultKey.fill(0) } }

    // ── the hazard the invariant table caught ────────────────────────────────────────────────

    /**
     * **THE CORRECTNESS TEST.** A burn credential that also opens an occupied vault slot must be
     * REFUSED, because slot 0 wins the first-match race and the user's next unlock of that vault
     * would wipe the device instead.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the collision sweep, or narrowing it to slot 0 only.
     */
    @Test
    fun `a credential that also opens a vault slot is refused`() {
        val dir = tmp.newFolder("collide")
        val s = freshVault(dir)

        // The SAME passphrase the vault uses — the exact collision that would flip unlock into wipe.
        assertEquals(ArmBurn.CollidesWithVault, s.armBurnSlot(VAULT_PASS))
    }

    /**
     * And the refusal must not be a side effect of refusing everything: a DIFFERENT passphrase arms.
     * Without this, a sweep that rejected unconditionally would pass the test above.
     */
    @Test
    fun `a non-colliding credential arms`() {
        val dir = tmp.newFolder("arm")
        val s = freshVault(dir)

        assertEquals(ArmBurn.Armed, s.armBurnSlot(BURN_PASS))
    }

    // ── the DoD: the burn actually works ─────────────────────────────────────────────────────

    /**
     * **THE 0.9.3 DEFINITION OF DONE, AS A TEST.** Arm, then enter the credential the way the lock
     * screen does, and observe [UnlockOrAdd.Burn]. Before arming the same entry must NOT burn —
     * otherwise the test would pass on a build where everything burns.
     */
    @Test
    fun `an armed credential triggers Burn, and an unarmed one does not`() {
        val dir = tmp.newFolder("roundtrip")
        val s = freshVault(dir)

        // BEFORE: slot 0 is filler; this passphrase matches nothing.
        assertTrue(
            "precondition: an unarmed install must not burn — otherwise the assertion below proves nothing",
            s.attemptUnlockOrAdd(BURN_PASS, genesis, create = false) is UnlockOrAdd.Rejected,
        )

        assertEquals(ArmBurn.Armed, s.armBurnSlot(BURN_PASS))

        assertTrue(
            "AFTER arming, the credential must reach the burn path",
            s.attemptUnlockOrAdd(BURN_PASS, genesis, create = false) is UnlockOrAdd.Burn,
        )
    }

    /** Arming must not disturb the vault it shares an image with. */
    @Test
    fun `the ordinary vault still unlocks after arming`() {
        val dir = tmp.newFolder("coexist")
        val s = freshVault(dir)
        s.armBurnSlot(BURN_PASS)

        val out = s.attemptUnlockOrAdd(VAULT_PASS, genesis, create = false)
        assertTrue("the everyday vault must be unaffected by arming", out is UnlockOrAdd.Unlocked)
        (out as UnlockOrAdd.Unlocked).open.vaultKey.fill(0)
    }

    /** The credential SURVIVES a process restart — it is durable state, not RAM. */
    @Test
    fun `an armed credential survives reopening the store`() {
        val dir = tmp.newFolder("persist")
        // close() first: VaultImageStore holds a single-instance-per-baseDir contract, so a second
        // store over the same directory is refused. Closing is what makes this a genuine reopen
        // rather than a second handle onto warm in-memory state.
        freshVault(dir).also { it.armBurnSlot(BURN_PASS) }.close()

        val reopened = store(dir)
        assertTrue(
            "arming must be durable — a credential that dies with the process is not a duress credential",
            reopened.attemptUnlockOrAdd(BURN_PASS, genesis, create = false) is UnlockOrAdd.Burn,
        )
    }

    /** Re-arming replaces the credential: the old one stops working, the new one starts. */
    @Test
    fun `re-arming replaces the previous credential`() {
        val dir = tmp.newFolder("rearm")
        val s = freshVault(dir)
        s.armBurnSlot(BURN_PASS)
        assertEquals(ArmBurn.Armed, s.armBurnSlot(BURN_PASS_2))

        assertTrue(
            "the replaced credential must no longer burn",
            s.attemptUnlockOrAdd(BURN_PASS, genesis, create = false) is UnlockOrAdd.Rejected,
        )
        assertTrue(
            "the new credential must burn",
            s.attemptUnlockOrAdd(BURN_PASS_2, genesis, create = false) is UnlockOrAdd.Burn,
        )
    }

    // ── structural properties: no armed flag, nothing else disturbed ─────────────────────────

    /**
     * **AN ARMED INSTALL MUST NOT BE STRUCTURALLY DISTINGUISHABLE.** Same file size, same slot count,
     * same payload sizes — only slot 0's `{salt, wrapped}` bytes differ, and those are uniformly
     * random either way. This is invariant P1: a size or shape difference IS the discoverable
     * armed-flag the design forbids.
     */
    @Test
    fun `arming changes no observable structure`() {
        val dir = tmp.newFolder("shape")
        val s = freshVault(dir)
        val bin = File(dir, "vault.bin")
        val sizeBefore = bin.length()
        val payloadsBefore = decodeImage(onDiskInner(dir, cipher)).payloads.map { it.size }

        s.armBurnSlot(BURN_PASS)

        assertEquals("the image must not change size", sizeBefore, bin.length())
        val after = decodeImage(onDiskInner(dir, cipher))
        assertEquals("slot count must not change", SLOT_COUNT, after.slots.size)
        assertEquals("payload sizes must not change", payloadsBefore, after.payloads.map { it.size })
        assertEquals(
            "slot 0's payload must stay untouched filler",
            payloadsBefore[BURN_SLOT_INDEX],
            after.payloads[BURN_SLOT_INDEX].size,
        )
    }

    /** The bytes DO change — otherwise the shape test above would pass over a no-op arm. */
    @Test
    fun `arming actually rewrites slot 0`() {
        val dir = tmp.newFolder("bytes")
        val s = freshVault(dir)
        val before = decodeImage(onDiskInner(dir, cipher)).slots[BURN_SLOT_INDEX]

        s.armBurnSlot(BURN_PASS)

        val after = decodeImage(onDiskInner(dir, cipher)).slots[BURN_SLOT_INDEX]
        assertNotEquals(
            "slot 0's wrapped key must actually change, or nothing was armed",
            before.wrapped.toList(),
            after.wrapped.toList(),
        )
    }

    // ── fail-closed paths ────────────────────────────────────────────────────────────────────

    /**
     * A write that landed but could not be proven durable must FAIL, not report success. Telling a
     * user their duress credential is set when it might not survive a crash is the worst possible
     * lie for this feature.
     */
    @Test
    fun `a non-durable write throws rather than reporting armed`() {
        val dir = tmp.newFolder("notdurable")
        freshVault(dir).close()
        val s = store(dir) { DirSyncResult.NOT_DURABLE }

        val thrown = runCatching { s.armBurnSlot(BURN_PASS) }.exceptionOrNull()
        assertTrue(
            "an unconfirmed write must surface as NotDurable, never as Armed",
            thrown is VaultImageException.NotDurable,
        )
    }

    /** Arming is refused while an account deletion is in flight — the delete machine owns the image. */
    @Test
    fun `arming is refused while a delete is pending`() {
        val dir = tmp.newFolder("deleting")
        val s = freshVault(dir)
        File(dir, "vault.delete-intent").writeBytes(ByteArray(1))

        assertEquals(ArmBurn.DeletePending, s.armBurnSlot(BURN_PASS))
    }

    private companion object {
        const val VAULT_PASS = "everyday vault passphrase"
        const val BURN_PASS = "duress credential one"
        const val BURN_PASS_2 = "duress credential two"
    }
}
