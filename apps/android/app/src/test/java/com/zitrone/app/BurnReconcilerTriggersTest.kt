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
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
import com.zitrone.app.crypto.vault.ResidueSweepResult
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE THREE BOOT-TIME MUTATORS ARE MUTUALLY EXCLUSIVE (0.9.2 Unit W-B).
 *
 * `runBootReconcile` runs three durable mutators in sequence: [VaultImageStore.completeInterruptedBurn],
 * [VaultImageStore.reconcileOrphanedBurnMarkers], and [VaultImageStore.sweepOrphanedResidue]. The
 * tempting justification for their ordering is "their triggers are mutually exclusive, so the order is
 * not observable" — and that is an INSTANCE-level claim about today's predicates, exactly the shape of
 * argument that failed twice in this unit's history.
 *
 * **This suite converts it to a proof.** Over the enumerated state space, AT MOST ONE trigger is true
 * in any state. Ordering is then irrelevant by construction, and if a future change WIDENS a trigger
 * this fails loudly instead of the ordering silently beginning to matter.
 *
 * The predicates under test (each verified against source, not restated from a comment):
 *  - `completeInterruptedBurn`  : confirmed PROVEN absent ∧ dek PROVEN absent ∧ bin PRESENT
 *  - `reconcileOrphanedBurnMarkers` : all image-bearing PROVEN absent ∧ confirmed PROVEN absent ∧ intent PRESENT
 *  - `sweepOrphanedResidue`     : bin PROVEN absent ∧ confirmed PROVEN absent ∧ NOT all image-bearing absent
 */
class BurnReconcilerTriggersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ops = LibsodiumVaultOps(SodiumJava())

    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    private val cipher = FakeDeviceKeyCipher()

    private fun newStore(dir: File) = VaultImageStore(dir, ops, cipher, fast)
    private fun newStore(dir: File, dirSync: (File?) -> DirSyncResult) =
        VaultImageStore(dir, ops, cipher, fast, dirSync)

    private fun bin(dir: File) = File(dir, "vault.bin")
    private fun dek(dir: File) = File(dir, "vault.dek")
    private fun binTmp(dir: File) = File(dir, "vault.bin.tmp")
    private fun dekTmp(dir: File) = File(dir, "vault.dek.tmp")
    private fun intent(dir: File) = File(dir, "vault.delete-intent")
    private fun confirmed(dir: File) = File(dir, "vault.delete-confirmed")

    /** One enumerated on-disk state. Five independent presence bits. */
    private data class State(
        val bin: Boolean,
        val dek: Boolean,
        val binTmp: Boolean,
        val intent: Boolean,
        val confirmed: Boolean,
    )

    private fun materialize(dir: File, s: State) {
        if (s.bin) bin(dir).writeBytes(ByteArray(64) { 1 })
        if (s.dek) dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 2 })
        if (s.binTmp) binTmp(dir).writeBytes(ByteArray(64) { 3 })
        if (s.intent) intent(dir).writeBytes(ByteArray(1))
        if (s.confirmed) confirmed(dir).writeBytes(ByteArray(1))
    }

    private fun allStates(): List<State> = buildList {
        for (b in listOf(true, false)) {
            for (d in listOf(true, false)) {
                for (bt in listOf(true, false)) {
                    for (i in listOf(true, false)) {
                        for (c in listOf(true, false)) add(State(b, d, bt, i, c))
                    }
                }
            }
        }
    }

    /**
     * THE PROOF. Each state is materialized on a FRESH directory and each trigger evaluated against a
     * FRESH store, so no mutator's effect can influence another's reading. At most one may fire.
     *
     * MUTATION UNIQUELY CAUGHT: widening any trigger predicate so two can fire in one state — e.g.
     * dropping `bin PRESENT` from `completeInterruptedBurn`, or `all image-bearing absent` from
     * `reconcileOrphanedBurnMarkers`.
     */
    @Test
    fun `at most one boot mutator fires in any state`() {
        val states = allStates()
        assertEquals("the enumeration must cover all 32 states", 32, states.size)

        val fired = mutableMapOf<State, List<String>>()
        for (s in states) {
            val names = mutableListOf<String>()

            // Each trigger gets its own pristine directory: this asks "would it fire HERE?", never
            // "does it still fire after another mutator already ran?".
            val d1 = tmp.newFolder()
            materialize(d1, s)
            if (newStore(d1).completeInterruptedBurn()) names += "completeInterruptedBurn"

            val d2 = tmp.newFolder()
            materialize(d2, s)
            if (newStore(d2).reconcileOrphanedBurnMarkers()) names += "reconcileOrphanedBurnMarkers"

            val d3 = tmp.newFolder()
            materialize(d3, s)
            // NO_MUTATION means the sweep declined; anything else means it mutated (or tried to).
            if (newStore(d3).sweepOrphanedResidue() != ResidueSweepResult.NO_MUTATION) {
                names += "sweepOrphanedResidue"
            }

            if (names.isNotEmpty()) fired[s] = names
        }

        val conflicts = fired.filterValues { it.size > 1 }
        assertTrue(
            "ordering must be irrelevant BY PROOF: these states fire more than one boot mutator — $conflicts",
            conflicts.isEmpty(),
        )
        // Guard against the test passing vacuously because nothing fires anywhere.
        assertTrue("the enumeration must exercise every mutator at least once",
            fired.values.flatten().toSet().size == 3)
    }

    /** The interrupted-keys-first signature: image present, DEK gone. Completing it destroys nothing readable. */
    @Test
    fun `completeInterruptedBurn finishes the wipe on bin-present dek-absent`() {
        val dir = tmp.newFolder()
        bin(dir).writeBytes(ByteArray(64) { 9 })

        assertTrue("the signature must be recognised", newStore(dir).completeInterruptedBurn())
        assertFalse("the cryptographically dead image must be gone", bin(dir).exists())
    }

    /**
     * A partial CREATE is the exact INVERSE signature `{dek present, bin absent}` — create renames the
     * DEK in first and the image second. It must never be mistaken for an interrupted burn.
     *
     * MUTATION UNIQUELY CAUGHT: inverting the bin/dek conditions in `completeInterruptedBurn`.
     */
    @Test
    fun `completeInterruptedBurn refuses a partial create`() {
        val dir = tmp.newFolder()
        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 4 })

        assertFalse(newStore(dir).completeInterruptedBurn())
        assertTrue("a partial create's dek must survive for the sweep to own", dek(dir).exists())
    }

    /** DEFERS TO D2c: a confirmed marker means the account-delete crash window owns this state. */
    @Test
    fun `completeInterruptedBurn defers to a confirmed delete`() {
        val dir = tmp.newFolder()
        bin(dir).writeBytes(ByteArray(64) { 9 })
        confirmed(dir).writeBytes(ByteArray(1))

        assertFalse(newStore(dir).completeInterruptedBurn())
        assertTrue("D2c's self-heal must keep its image", bin(dir).exists())
        assertTrue("and its authorisation", confirmed(dir).exists())
    }

    /** The S2→S6 window: image durably gone, intent marker still present. */
    @Test
    fun `reconcileOrphanedBurnMarkers clears an orphaned intent over an absent image`() {
        val dir = tmp.newFolder()
        intent(dir).writeBytes(ByteArray(1))

        assertTrue(newStore(dir).reconcileOrphanedBurnMarkers())
        assertFalse("post-burn must carry no marker — fresh-install parity", intent(dir).exists())
    }

    /**
     * A `delete-intent` over a LIVE vault is a GENUINE pending reconcile (round-14 F1). Clearing it
     * would be the B1 state: markers say "nothing pending" over a live image.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the image-absence precondition.
     */
    @Test
    fun `reconcileOrphanedBurnMarkers never clears an intent over a live image`() {
        val dir = tmp.newFolder()
        bin(dir).writeBytes(ByteArray(64) { 5 })
        intent(dir).writeBytes(ByteArray(1))

        assertFalse(newStore(dir).reconcileOrphanedBurnMarkers())
        assertTrue("a genuine pending reconcile must survive", intent(dir).exists())
    }

    /**
     * Clearing a `delete-confirmed` here would strip D2c's auto-destroy authorisation mid-heal.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the confirmed-absence precondition.
     */
    @Test
    fun `reconcileOrphanedBurnMarkers never touches a confirmed delete`() {
        val dir = tmp.newFolder()
        intent(dir).writeBytes(ByteArray(1))
        confirmed(dir).writeBytes(ByteArray(1))

        assertFalse(newStore(dir).reconcileOrphanedBurnMarkers())
        assertTrue(confirmed(dir).exists())
    }

    /**
     * A reconciler that mutates but cannot prove the mutation durable must NOT report success — the
     * caller turns that into the fail-closed durability verdict.
     *
     * MUTATION UNIQUELY CAUGHT: reporting success without consulting dirSync.
     */
    @Test
    fun `a non-durable reconcile reports failure`() {
        val dir = tmp.newFolder()
        intent(dir).writeBytes(ByteArray(1))

        val store = newStore(dir) { DirSyncResult.NOT_DURABLE }
        assertFalse("a non-durable marker clear is not a success", store.reconcileOrphanedBurnMarkers())
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
