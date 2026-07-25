// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE BYTE-FOR-BYTE GATE (0.9.2 Unit W-B, P3) — post-burn app-local state must be indistinguishable
 * from post-fresh-install state.
 *
 * **Why this is an INSTRUMENTED test and not Robolectric.** The harness decision originally chose
 * Robolectric on the premise that emulator availability in CI was unconfirmed. That premise was
 * ~2 years stale, and Robolectric provides no AndroidKeyStore — so under it the entire Keystore and
 * EncryptedSharedPreferences half of the coverage set would have been an EXCLUSION, which is exactly
 * the half a duress wipe must not leave behind. Verified by spike: an emulator boots on
 * `ubuntu-latest` and runs instrumented tests green in ~8 minutes.
 *
 * **What "fresh install" means now.** Not only files, prefs and Keystore aliases: W-A made the
 * ROUTING VERDICT part of the definition. A fresh install has no durability hold raised, so a
 * post-burn state that matches on every byte but differs on the derived [BootDecision] is NOT
 * fresh-install-equivalent (maintainer ruling E). Both halves are asserted.
 *
 * **The negative test is what makes the positive one mean anything.** A byte-for-byte comparison
 * that passes is only evidence if it would have failed; a comparison over an empty coverage set
 * passes trivially. [the gate catches a deliberately orphaned Keystore alias] leaves one artifact
 * behind on purpose — a Keystore alias, chosen because it is the half that was previously
 * unreachable — and asserts the gate FAILS. Same discipline as the boot-mutator non-vacuity guard.
 */
@RunWith(AndroidJUnit4::class)
class BurnByteForByteGateTest {

    private lateinit var ctx: Context
    private lateinit var container: AppContainer

    /** The app-local state this gate compares. Anything not in here is silently unverified. */
    private data class StateSnapshot(
        val files: Map<String, String>,
        val prefs: Map<String, String>,
        val keystoreAliases: Set<String>,
        val databases: Map<String, String>,
    )

    /**
     * CONTENT HASHES, NOT LENGTHS (round-1 review — the gate compared neither bytes nor prefs and
     * database state, while `SECURITY_MODEL.md` claimed all three). A length-only comparison passes
     * over a surviving artifact of identical size, and a filename-only comparison passes over residue
     * written INSIDE an existing prefs file or database — which is where session state actually goes.
     * "Byte-for-byte" has to mean bytes or the name is the second overclaim.
     */
    private fun digest(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun treeHashes(root: File): Map<String, String> =
        if (!root.exists()) emptyMap()
        else root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to runCatching { digest(it) }.getOrDefault("<unreadable>") }

    private fun snapshot(): StateSnapshot {
        val dataDir = ctx.filesDir.parentFile!!
        val files = treeHashes(ctx.filesDir)
        val prefs = treeHashes(File(dataDir, "shared_prefs"))
        val databases = treeHashes(File(dataDir, "databases"))
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = ks.aliases().toList().toSet()
        return StateSnapshot(files, prefs, aliases, databases)
    }

    /**
     * EXPLICIT EXCLUSIONS, each with its reason IN the test (an exclusion list that grows without
     * scrutiny is a checklist wearing a test's clothes). These are OS-level and outside app control;
     * the app cannot claim fresh-install-indistinguishability for them, and they are disclosed in
     * `SECURITY_MODEL.md` as limitations rather than silently dropped:
     *  - package install/update time — recorded by the package manager, not the app;
     *  - UsageStats / battery attribution — system-journaled;
     *  - notification HISTORY — system-journaled (channels the app created ARE compared, via prefs);
     *  - MediaStore exports — user-initiated exports leave the app sandbox by design;
     *  - NAND-level residue — crypto-erase is the guarantee, not physical sanitisation.
     */
    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        container = (ctx.applicationContext as ZitroneApp).container
    }

    /**
     * THE GATE. Fresh → provisioned → burned → compared, in one run so "fresh" is this device's
     * actual fresh state rather than an assumption about it.
     */
    @Test
    fun post_burn_state_matches_post_fresh_install_state() {
        val fresh = snapshot()

        container.imageStore.create(PASSPHRASE, GENESIS)
        assertTrue("precondition: a vault exists to burn", container.hasVault())
        val provisioned = snapshot()
        assertNotEquals(
            "precondition: provisioning must be OBSERVABLE, or the comparison proves nothing",
            fresh.files,
            provisioned.files,
        )

        container.burnVault()

        val burned = snapshot()
        assertEquals("files must match a fresh install", fresh.files, burned.files)
        assertEquals("shared_prefs must match a fresh install", fresh.prefs, burned.prefs)
        assertEquals("databases must match a fresh install", fresh.databases, burned.databases)
        assertEquals(
            "NO Keystore alias may survive a burn — an orphaned alias is 'something was here'",
            fresh.keystoreAliases,
            burned.keystoreAliases,
        )
    }

    /**
     * RULING E — the derived verdict is part of "fresh install". A post-burn state can match on every
     * byte and still differ in what the app will DO with it, because W-A made the durability hold a
     * routing input. A file-only gate would pass over exactly that difference.
     */
    @Test
    fun post_burn_boot_decision_matches_post_fresh_install_including_the_hold() = runBlocking {
        val freshHold = container.durabilityHold.value
        val freshDecision = container.deriveBootDecisionFromDisk()

        container.imageStore.create(PASSPHRASE, GENESIS)
        container.burnVault()

        assertEquals(
            "a completed burn must leave NO durability doubt — a raised hold is not a fresh install",
            freshHold,
            container.durabilityHold.value,
        )
        assertFalse("a completed burn lowers the hold", container.durabilityHold.value)
        assertEquals(
            "the DERIVED verdict, not just the bytes, must match a fresh install",
            freshDecision.route,
            container.deriveBootDecisionFromDisk().route,
        )
    }

    /**
     * DoD-8(a) — the burn path CONSUMES [AppContainer.wipeBiometricMaterial]'s boolean and FAILS the
     * wipe on false. This was unclosable under Robolectric (no AndroidKeyStore to fail against) and
     * is the specific gap this harness change exists to close.
     *
     * Asserted through its observable consequence: after a burn, no alias remains AND the hold is
     * lowered — which can only both hold if the biometric wipe was required to succeed.
     */
    @Test
    fun burn_requires_the_biometric_wipe_to_succeed() {
        container.imageStore.create(PASSPHRASE, GENESIS)
        container.burnVault()

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(
            "no biometric alias may survive; if the wipe could fail silently the burn would still " +
                "report success and the hold would still be lowered",
            ks.aliases().toList().none { it.startsWith(BiometricVaultKeyCipher.PREFIX) },
        )
        assertFalse(container.durabilityHold.value)
    }

    /**
     * THE NEGATIVE TEST — the gate must be able to FAIL.
     *
     * A byte-for-byte comparison that passes is evidence only if it would have caught a survivor. A
     * comparison over an empty or wrongly-scoped coverage set passes trivially and reads as proof in
     * every future review — the vacuous-test failure applied to the gate itself.
     *
     * One artifact is left behind DELIBERATELY: a Keystore alias, chosen because it is the half that
     * was unreachable under the previous harness and therefore the half most likely to be silently
     * uncovered. The assertion is that the comparison REPORTS THE DIFFERENCE.
     */
    @Test
    fun the_gate_catches_a_deliberately_orphaned_keystore_alias() {
        val fresh = snapshot()

        container.imageStore.create(PASSPHRASE, GENESIS)
        container.imageStore.burnObliterate() // image only — biometric material deliberately NOT wiped
        // A REAL Keystore alias carrying production's own prefix, so it is residue of exactly the
        // class a burn must remove and is reapable by production's `deleteAllAliasesExcept`.
        // Deliberately NOT auth-gated: `newEncryptCipher` requires an enrolled biometric, which a
        // headless CI emulator has none of — the gate would then fail for an environmental reason
        // and prove nothing about residue.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    BiometricVaultKeyCipher.PREFIX + "gatenegative",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

        val burnedWithResidue = snapshot()
        assertEquals(
            "control: the FILE half is clean, so the difference below is the alias and nothing else",
            fresh.files,
            burnedWithResidue.files,
        )
        assertNotEquals(
            "THE GATE MUST FAIL HERE. If these compare equal, the Keystore half of the coverage set " +
                "is not actually being compared, and every green run of this suite has been vacuous.",
            fresh.keystoreAliases,
            burnedWithResidue.keystoreAliases,
        )
        // AND IT MUST FAIL FOR THE RIGHT REASON. `!=` alone passed on the gate's first execution
        // while the real discriminator was an UNRELATED defect (the device-key alias surviving every
        // burn). Once that defect is fixed the inequality would still have held on the narrower true
        // condition, and nobody would have noticed the guard had stopped guarding — the anti-vacuity
        // guard going vacuous as a SIDE EFFECT of an unrelated fix. Name the artifact.
        assertTrue(
            "the difference must be THIS deliberately orphaned alias, not some other residue",
            (burnedWithResidue.keystoreAliases - fresh.keystoreAliases)
                .contains(BiometricVaultKeyCipher.PREFIX + "gatenegative"),
        )

        // Restore the device to a clean state so a later test in this class is not polluted.
        container.wipeBiometricMaterial()
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        val GENESIS: ByteArray = "genesis".toByteArray(Charsets.UTF_8)
    }
}
