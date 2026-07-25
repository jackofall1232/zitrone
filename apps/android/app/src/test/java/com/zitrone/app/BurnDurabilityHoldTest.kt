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
import com.zitrone.app.crypto.vault.VaultImageException
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
 * A FAILED-BUT-CLEAN BURN MUST NOT PRESENT AS A FRESH INSTALL (0.9.2 Unit W-B).
 *
 * **The blocking invariant of this unit — post-burn ≡ fresh install is the feature's PURPOSE, so a
 * break here is not a hardening gap.** This was the round-6 HIGH: the durability hold covered the
 * boot sweep but NOT the burn's own obliterate, so a burn whose unlinks landed while its `dirSync`
 * failed left a directory that STATS CLEAN — `vaultProvenAbsent` true, no hold — and the next boot
 * routed to ONBOARDING over a wipe that was never proven durable and that a journal replay can undo.
 *
 * Closed STRUCTURALLY: one durability owner, three producers (sweep, boot reconcilers, and the burn
 * itself). Not a second hold field beside the first.
 *
 * The two halves are tested where each actually lives:
 *  - the STATE is real (store level): a non-durable burn throws, having already unlinked;
 *  - the ORDER makes it safe ([runBurnWipe]): the hold is raised BEFORE the mutation and survives the
 *    throw, so the clean-looking directory can never authorise onboarding.
 */
class BurnDurabilityHoldTest {

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
    private val passphrase = "correct horse battery staple"
    private val genesis = "genesis".toByteArray(Charsets.UTF_8)

    private fun bin(dir: File) = File(dir, "vault.bin")
    private fun dek(dir: File) = File(dir, "vault.dek")

    /**
     * THE FAILED-BUT-CLEAN STATE IS REAL, not hypothetical — this is what the hold exists for.
     * Unlinks land, `dirSync` reports non-durable, the wipe throws, and the directory now stats
     * CLEAN. A stat-based routing decision taken here would say "fresh install".
     *
     * MUTATION UNIQUELY CAUGHT: dropping the `dirSync` durability gate from `obliterateLocked`.
     */
    @Test
    fun `a non-durable burn throws with the directory already stat-clean`() {
        val dir = tmp.newFolder()
        VaultImageStore(dir, ops, cipher, fast).create(passphrase, genesis)
        assertTrue("precondition: a real vault exists", bin(dir).exists())

        val store = VaultImageStore(dir, ops, cipher, fast) { DirSyncResult.NOT_DURABLE }
        var threw = false
        try {
            store.burnObliterate()
        } catch (e: VaultImageException.DestroyFailed) {
            threw = true
        }

        assertTrue("a wipe that cannot prove durability must FAIL, never report success", threw)
        assertFalse("and yet the image is gone from the namespace", bin(dir).exists())
        assertFalse("as is the DEK", dek(dir).exists())
        assertTrue(
            "so a fresh stat now says 'provably clean' — exactly the reading that must not " +
                "authorise onboarding on its own",
            store.imageBearingProvenAbsent(),
        )
    }

    /**
     * THE ORDER IS THE FIX. The hold is raised BEFORE the first destructive mutation, so a process
     * death mid-obliterate still leaves the doubt recorded.
     *
     * MUTATION UNIQUELY CAUGHT: moving `raiseHold()` after `obliterate()`.
     */
    @Test
    fun `the hold is raised strictly before the wipe`() {
        val order = mutableListOf<String>()
        runBurnWipe(
            raiseHold = { order += "raise" },
            obliterate = { order += "obliterate" },
            lowerHold = { order += "lower" },
        )
        assertEquals(listOf("raise", "obliterate", "lower"), order)
    }

    /**
     * A THROWING WIPE LEAVES THE HOLD RAISED. This is the assertion that closes the round-6 HIGH: the
     * directory stats clean, and the hold is what withholds the fresh-install presentation anyway.
     *
     * MUTATION UNIQUELY CAUGHT: wrapping `obliterate()` in `runCatching`, or lowering the hold in a
     * `finally` instead of on the success path.
     */
    @Test
    fun `a failed wipe leaves the hold raised`() {
        var held = false
        var threw = false
        try {
            runBurnWipe(
                raiseHold = { held = true },
                obliterate = { throw VaultImageException.DestroyFailed() },
                lowerHold = { held = false },
            )
        } catch (e: VaultImageException.DestroyFailed) {
            threw = true
        }

        assertTrue("the failure must propagate — a swallowed burn failure is a false success", threw)
        assertTrue("THE INVARIANT: the doubt survives the failure", held)
    }

    /** Only a wipe that proved itself durable may lower the hold. */
    @Test
    fun `a proven-durable wipe lowers the hold`() {
        var held = false
        runBurnWipe(raiseHold = { held = true }, obliterate = {}, lowerHold = { held = false })
        assertFalse(held)
    }

    /**
     * THE COMPOSED INVARIANT, end to end at the routing layer: with the hold raised, a directory that
     * is PROVABLY clean still does not reach ONBOARDING.
     *
     * This is the assertion the round-6 HIGH would have failed. It deliberately re-states the disk
     * facts of the failed-but-clean burn above (proven absent, no markers) rather than assuming them.
     *
     * MUTATION UNIQUELY CAUGHT: removing the `residueSweepHold`/`durabilityHold` arm from `bootRoute`.
     */
    @Test
    fun `a held boot cannot present a fresh install over a provably clean directory`() {
        val decision = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = false,
            durabilityHold = true,
            vaultProvenAbsent = true,
            isLegacyImage = { false },
        )
        assertEquals(
            "a clean stat plus an unproven wipe must route to LOCKED, never ONBOARDING",
            BootRoute.LOCKED,
            decision.route,
        )

        // And the same disk WITHOUT the doubt is ordinary onboarding — so the test proves the hold is
        // doing the work, not that onboarding is unreachable generally.
        assertEquals(
            BootRoute.ONBOARDING,
            deriveBootDecision(
                serverDeleteConfirmed = false,
                imagePresent = false,
                durabilityHold = false,
                vaultProvenAbsent = true,
                isLegacyImage = { false },
            ).route,
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
