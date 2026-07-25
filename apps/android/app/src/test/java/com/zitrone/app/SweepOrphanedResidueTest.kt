// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.AEAD_TAG_BYTES
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.DirSyncResult
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.ResidueSweepResult
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * COLD-START ORPHAN SWEEP (0.9.2 Unit W-A).
 *
 * The sweep is a DESTRUCTIVE BOOT OPERATION — it unlinks files before any authentication — so the bar
 * here is not "it deletes the orphan" but **it deletes NOTHING ELSE**. A gate that is too broad
 * destroys a live vault's key; a gate that is too narrow strands a recoverable image no other path can
 * reach. Both directions are asserted. These tests walk the WRITER/READER table in
 * [VaultImageStore.sweepOrphanedResidue]'s kdoc row by row.
 *
 * The gap being closed: `{vault.bin absent, dek-or-temp present}` had no cold-start recovery, and boot
 * routing keyed on `vault.bin` alone read it as "no vault" and presented ONBOARDING — while
 * `vault.bin.tmp` can hold a COMPLETE outer image. Two writers produce that state with no duress-wipe
 * involved: an interrupted `create()` (DEK written durably before the image) and an interrupted
 * `retireLegacyImage()` (unlinks the image, then the DEK).
 */
class SweepOrphanedResidueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ops = LibsodiumVaultOps(SodiumJava())

    /** Fast, deterministic stand-in for Argon2id — the real KDF is not under test here. */
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    private val cipher = FakeDeviceKeyCipher()
    private val passphrase = "correct horse battery staple"
    private val genesis = "genesis".toByteArray(Charsets.UTF_8)

    private fun newStore(dir: File) = VaultImageStore(dir, ops, cipher, fast)
    private fun newStore(dir: File, dirSync: (File?) -> DirSyncResult) =
        VaultImageStore(dir, ops, cipher, fast, dirSync)

    private fun bin(dir: File) = File(dir, "vault.bin")
    private fun dek(dir: File) = File(dir, "vault.dek")
    private fun binTmp(dir: File) = File(dir, "vault.bin.tmp")
    private fun dekTmp(dir: File) = File(dir, "vault.dek.tmp")
    private fun intent(dir: File) = File(dir, "vault.delete-intent")
    private fun confirmed(dir: File) = File(dir, "vault.delete-confirmed")

    // ─────────────────────────── rows 1-3: the genuine orphan — SWEEP ───────────────────────────

    /** Row 1: `{dek, no bin, no markers}` — an interrupted create. The DEK opens nothing. */
    @Test
    fun `row 1 - sweeps a stray dek with no image`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })

        assertEquals(
            "the sweep must report a DURABLE sweep",
            ResidueSweepResult.SWEPT_DURABLE,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertFalse("the orphaned dek must be gone", dek(dir).exists())
    }

    /** Row 2: `{dek.tmp, no bin, no markers}` — a crash inside renameIntoPlace(dekFile). */
    @Test
    fun `row 2 - sweeps a stray dek temp`() {
        val dir = tmp.newFolder()
        dekTmp(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 3 })

        assertEquals(ResidueSweepResult.SWEPT_DURABLE, newStore(dir).sweepOrphanedResidue())
        assertFalse(dekTmp(dir).exists())
    }

    /**
     * Row 3 — THE ONE THAT MATTERS. `vault.bin.tmp` stages a COMPLETE outer image, so this is the
     * state where onboarding-over-residue meant a fresh-install screen over a recoverable vault.
     */
    @Test
    fun `row 3 - sweeps a surviving bin temp holding a complete image`() {
        val dir = tmp.newFolder()
        // Build a real vault, then move its image aside as a leftover temp with the image absent —
        // exactly the shape a crash between write-tmp and rename leaves.
        val store = newStore(dir)
        store.create(passphrase, genesis)
        val realImage = bin(dir).readBytes()
        assertTrue("precondition: a real image was written", realImage.isNotEmpty())
        bin(dir).delete()
        binTmp(dir).writeBytes(realImage)
        dek(dir).delete()

        assertEquals(ResidueSweepResult.SWEPT_DURABLE, newStore(dir).sweepOrphanedResidue())
        assertFalse("a complete outer image must not survive as a temp", binTmp(dir).exists())
        assertTrue("the directory must now be provably clean", newStore(dir).imageBearingProvenAbsent())
    }

    // ──────────────────── rows 4-8: states another owner holds — REFUSE ────────────────────

    /** Row 4: a LIVE vault. The single most important refusal — never touch a real image's key. */
    @Test
    fun `row 4 - refuses while a live vault image is present`() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        store.create(passphrase, genesis)

        assertEquals(
            "a present image must refuse the sweep",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertTrue("the live image survives", bin(dir).exists())
        assertTrue("and CRITICALLY, so does its DEK", dek(dir).exists())
    }

    /**
     * Row 6: a delete IS in flight — but what makes that state live is the IMAGE, not the intent
     * marker. Gate 1 covers it.
     */
    @Test
    fun `row 6 - refuses while a delete is in flight over a live image`() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        store.create(passphrase, genesis)
        intent(dir).writeBytes(ByteArray(1))

        assertEquals(ResidueSweepResult.NO_MUTATION, newStore(dir).sweepOrphanedResidue())
        assertTrue("the in-flight delete's image survives", bin(dir).exists())
        assertTrue("and its DEK", dek(dir).exists())
    }

    /**
     * Row 6b — an intent marker must NOT strand residue.
     *
     * There is deliberately no gate on `vault.delete-intent`. `destroy()` writes the CONFIRMED marker
     * durably BEFORE it unlinks anything, so every real account-delete unlink already carries the
     * confirmed marker and is caught by the other gate. An intent gate would therefore protect
     * nothing against a deletion in flight, while it could only STRAND residue.
     *
     * PROOF CORRECTED (round 3, Codex). An earlier version of this docstring claimed "an intent alone
     * never accompanies an absent image in a legitimate state" — and that is FALSE.
     * `createVaultAndPublish` calls `retireLegacyImage()`, which unlinks the image, BEFORE `create()`
     * clears the markers, so a crash between them leaves exactly an intent standing over an absent
     * image. The same false claim was corrected in the store's own table as row 6c; it survived HERE,
     * in the sibling docstring, which is this unit's recurring shape: fix one site, miss its twin.
     *
     * What makes sweeping safe is NOT that the state is unreachable — it is that whatever produced it
     * has already destroyed the only openable image, so the residue opens nothing and keeping it would
     * strand dead data. A gate can be wrong by being too narrow, and here that would be worse than the
     * over-deletion such a gate is written to prevent.
     */
    @Test
    fun `row 6b - an intent marker does not strand recoverable residue`() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        store.create(passphrase, genesis)
        val realImage = bin(dir).readBytes()
        bin(dir).delete()
        binTmp(dir).writeBytes(realImage)
        intent(dir).writeBytes(ByteArray(1))

        assertEquals(
            "an intent marker must NOT strand recoverable residue",
            ResidueSweepResult.SWEPT_DURABLE,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertFalse("the stranded complete image must be gone", binTmp(dir).exists())
        assertFalse("and the stray dek", dek(dir).exists())
        assertTrue("the directory is now provably clean", newStore(dir).imageBearingProvenAbsent())
    }

    /**
     * Row 7: a CONFIRMED server delete owns this state — `Route.DeleteIncomplete` must finish it.
     *
     * THIS TEST WAS DELETED BY AN EARLIER REWRITE and restored in round 1 (Grok, Gemini). Gate 2 is
     * the ownership bar for an in-flight account deletion, and while it was missing, REMOVING gate 2
     * entirely would not have failed this suite — a destructive gate with no coverage, under a header
     * still claiming the table was walked row by row.
     *
     * MUTATION UNIQUELY CAUGHT: deleting the `serverDeletedFile` gate.
     */
    @Test
    fun `row 7 - refuses while a delete-confirmed marker is present`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })
        confirmed(dir).writeBytes(ByteArray(1))

        assertEquals(
            "a confirmed account delete owns this directory — the sweep must not touch it",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertTrue("and the residue it owns must survive", dek(dir).exists())
    }

    /**
     * Row 8, THE LOAD-BEARING VERSION — gate 2's tristate, by CONSEQUENCE (round-2 review, Grok).
     *
     * Gate 1 had an ELOOP test proving an indeterminate IMAGE stat refuses; gate 2 had only a
     * present-marker case and the admittedly-weak ENOTDIR one. Verified by mutation: downgrading gate
     * 2 from `!Files.notExists(...)` to `File.exists()` broke NOTHING — so the confirmed marker's
     * fail-closed reading was uncovered while the image's was covered. Symmetry gap, closed here.
     *
     * A self-referential symlink at `vault.delete-confirmed` yields ELOOP: `File.exists()` reads false
     * (indistinguishable from absent — the fail-open) while `Files.notExists()` is ALSO false
     * (correctly: not proven absent). The assertion is on the DAMAGE — the DEK of a directory whose
     * deletion status cannot be determined must survive.
     *
     * MUTATION UNIQUELY CAUGHT: `!Files.notExists(serverDeletedFile)` → `serverDeletedFile.exists()`.
     */
    @Test
    fun `row 8 - an unstattable confirmed marker must not cost the residue`() {
        val dir = tmp.newFolder()
        val marker = confirmed(dir).toPath()
        java.nio.file.Files.createSymbolicLink(marker, marker.fileName)
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })

        assertEquals(
            "an indeterminate confirmed-marker stat must refuse — a pending deletion may own this",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertTrue(
            "and MUST NOT have deleted the residue on the way to refusing",
            dek(dir).exists(),
        )
    }

    /**
     * Row 5/8: an INDETERMINATE stat, constructed for real (ENOTDIR — the store's baseDir is a regular
     * file). Every gate uses `Files.notExists`, true ONLY on a proven absence, so a failing filesystem
     * refuses rather than sweeping blind.
     *
     * HONEST LIMIT — this case is WEAK on its own and is kept only for coverage of the shape. When the
     * baseDir itself is unstattable there is nothing inside it to delete, so a fail-OPEN gate
     * (`!binFile.exists()`) also ends up returning false, just for a different reason. Verified by
     * mutation: swapping gate 1 to `File.exists()` does NOT fail this test. The test below is the one
     * that actually holds gate 1.
     */
    @Test
    fun `rows 5 and 8 - refuses when the base directory cannot be stat'd`() {
        val notADir = File(tmp.newFolder(), "base-is-a-regular-file")
        notADir.writeText("so <it>/vault.bin cannot be stat'd")

        assertEquals(
            "an unstattable directory must never authorise destructive work",
            ResidueSweepResult.NO_MUTATION,
            newStore(notADir).sweepOrphanedResidue(),
        )
    }

    /**
     * Row 5, THE LOAD-BEARING VERSION: the IMAGE's stat is indeterminate while its siblings are
     * perfectly deletable. A self-referential symlink at `vault.bin` yields ELOOP, so `File.exists()`
     * reads false (indistinguishable from absence — the fail-open) while `Files.notExists()` is also
     * false (correctly: NOT proven absent). A real `vault.dek` sits beside it in an ordinary directory.
     *
     * This is the only test that separates the two gate implementations by CONSEQUENCE rather than by
     * return value: a fail-open gate proceeds and unlinks the DEK of a vault whose image it merely
     * failed to stat — destroying the key to a possibly-live vault on a flaky filesystem. So the
     * assertion that matters is that the dek SURVIVES, not that the call returned false. Confirmed by
     * mutation: `File.exists()` in gate 1 fails this test and no other.
     */
    @Test
    fun `row 5 - an unstattable image must not cost a live vault its DEK`() {
        val dir = tmp.newFolder()
        val binPath = bin(dir).toPath()
        java.nio.file.Files.createSymbolicLink(binPath, binPath.fileName)
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })

        assertEquals(
            "an indeterminate image stat must refuse",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertTrue(
            "and MUST NOT have deleted the DEK on the way to refusing — the image was never proven " +
                "absent, so this key may belong to a live vault",
            dek(dir).exists(),
        )
    }

    /** Row 9: the ordinary cold start. Nothing to do, and it must not claim it did anything. */
    @Test
    fun `row 9 - is a silent no-op on an already-clean directory`() {
        val dir = tmp.newFolder()
        assertEquals(
            "a clean directory is not 'swept' — claiming work here would be a false positive",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
    }

    // ─────────────────────────── durability + idempotence ───────────────────────────

    /**
     * The unlinks must be proven DURABLE before the sweep claims success. Without this a journal
     * replay could resurrect a temp AFTER routing had already presented onboarding — the exact
     * failure the sweep exists to prevent, reintroduced one layer down.
     */
    @Test
    fun `a non-durable dirSync fails the sweep rather than claiming a clean directory`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })

        val store = newStore(dir) { DirSyncResult.NOT_DURABLE }
        assertEquals(
            "a non-durable sweep must report SWEPT_NOT_DURABLE — not NO_MUTATION, which would tell " +
                "the caller nothing happened, and not DURABLE, which would authorise onboarding",
            ResidueSweepResult.SWEPT_NOT_DURABLE,
            store.sweepOrphanedResidue(),
        )
    }

    /**
     * A file that SURVIVES its unlink must fail the sweep. `File.delete()` reports failure by
     * returning false, not by throwing, so without the post-unlink re-stat the sweep would walk
     * straight into `dirSync` and report SWEPT_DURABLE over residue that is still on disk — a
     * fresh-install screen presented over a surviving DEK, which is the exact failure this gate
     * exists to prevent. That the DEK opens nothing on its own does not make the claim honest.
     *
     * Round-4 review (Kimi): the re-stat branch was uncovered — only the `dirSync` branch below it
     * had a test, so deleting the re-stat left the suite green.
     *
     * The vector is a `vault.dek` that is a NON-EMPTY DIRECTORY, so the unlink fails with ENOTEMPTY.
     * That shape is not itself a reachable production state; it is simply the one way to make
     * `delete()` fail that does not depend on uid (these tests run as root, where a read-only parent
     * directory would not refuse the unlink) or on a SecurityManager. What is under test is the
     * branch, not the shape.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the post-unlink `imageBearingFilesProvenAbsent()` re-stat.
     */
    @Test
    fun `residue that survives its unlink fails the sweep instead of claiming durable success`() {
        val dir = tmp.newFolder()
        val undeletable = dek(dir)
        undeletable.mkdirs()
        File(undeletable, "occupant").writeBytes(byteArrayOf(1))

        val store = newStore(dir) { DirSyncResult.DURABLE }
        assertEquals(
            "the unlink failed, so absence was never proven — SWEPT_DURABLE would authorise " +
                "onboarding over residue that is still there, and NO_MUTATION would deny the sweep " +
                "even tried",
            ResidueSweepResult.SWEPT_NOT_DURABLE,
            store.sweepOrphanedResidue(),
        )
        assertTrue("and the survivor is still on disk — the verdict is not cosmetic", undeletable.exists())
    }

    /**
     * The total `catch` around the mutation is the sweep's last fail-closed backstop: if any step
     * between the first unlink and the durability proof throws, the honest answer is "mutated, not
     * proven durable". An escaping throw would instead unwind into `runBootReconcile`, which reaches
     * the same verdict only by accident of a second, distant catch — and would take any future caller
     * that lacks one down with it.
     *
     * Round-4 review (Gemini): the block was entirely uncovered — nothing in the suite drove a throw
     * past the mutation point. `dirSync` is the only injectable step inside the `try`, so it is the
     * vector; the branch is what matters, not which step raises.
     *
     * MUTATION UNIQUELY CAUGHT: removing the `catch` (the throw escapes `sweepOrphanedResidue`).
     */
    @Test
    fun `a throwing step after the unlinks fails the sweep closed instead of escaping`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })

        val store = newStore(dir) { throw IOException("fsync faulted after the unlinks landed") }
        assertEquals(
            "a fault after the mutation point must report SWEPT_NOT_DURABLE — the residue was " +
                "already touched, so NO_MUTATION would be a lie, and durability was never proven",
            ResidueSweepResult.SWEPT_NOT_DURABLE,
            store.sweepOrphanedResidue(),
        )
        assertFalse("the unlink that did land is not rolled back", dek(dir).exists())
    }

    /** Safe to run on every cold start: a second pass finds nothing and reports nothing. */
    @Test
    fun `is idempotent across repeated cold starts`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })

        assertEquals(ResidueSweepResult.SWEPT_DURABLE, newStore(dir).sweepOrphanedResidue())
        assertEquals(
            "a second boot must be a no-op",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
        assertEquals(
            "a third, too",
            ResidueSweepResult.NO_MUTATION,
            newStore(dir).sweepOrphanedResidue(),
        )
    }

    /**
     * The whole point, stated as one assertion: after the sweep the directory satisfies the SAME
     * fail-closed proof that authorises a fresh-install presentation. Before it, it did not.
     */
    @Test
    fun `converts a not-provably-clean directory into a provably clean one`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })
        binTmp(dir).writeBytes(ByteArray(128) { 9 })

        assertFalse(
            "precondition: residue means onboarding is NOT authorised",
            newStore(dir).imageBearingProvenAbsent(),
        )
        assertEquals(ResidueSweepResult.SWEPT_DURABLE, newStore(dir).sweepOrphanedResidue())
        assertTrue(
            "after the sweep, and only then, onboarding is authorised",
            newStore(dir).imageBearingProvenAbsent(),
        )
    }

    /** One fixed device key — same shape as the sibling vault suites' per-suite fake. */
    private class FakeDeviceKeyCipher : DeviceKeyCipher {
        private val key = ByteArray(MASTER_KEY_BYTES) { (0xA0 + it).toByte() }
        private val rng = SecureRandom()

        override fun wrapDek(dek: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce),
            )
            return nonce + c.doFinal(dek)
        }

        override fun unwrapDek(blob: ByteArray): ByteArray? {
            if (blob.size != WRAPPED_KEY_BYTES) return null
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(AEAD_TAG_BYTES * 8, blob, 0, NONCE_BYTES),
                )
                c.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
            } catch (e: GeneralSecurityException) {
                null
            }
        }
    }
}
