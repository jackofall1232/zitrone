// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
import com.zitrone.app.crypto.vault.KeystoreDeviceKeyCipher
import com.zitrone.app.notifications.MessagingNotifications
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * **What "fresh install" means here.** Not only files, prefs and Keystore aliases: W-A made the
 * ROUTING VERDICT part of the definition. A fresh install has no durability hold raised, so a
 * post-burn state that matches on every byte but differs on the derived [BootDecision] is NOT
 * fresh-install-equivalent (maintainer ruling E). Both halves are asserted.
 *
 * ─── WHAT ROUND 2 FOUND, AND WHAT THIS REBUILD CHANGES ──────────────────────────────────────────
 *
 * Both lenses found the same thing independently: the gate was **materially non-discriminating**.
 * It provisioned by calling `imageStore.create()` directly, which writes a vault image and NOTHING
 * ELSE — no `onboarding_done`, no device setting, no lazily-created prefs files, no diagnostics log,
 * no cache. `cacheDir` was not even in the snapshot. So the burn was compared over a state that
 * contained almost none of the residue it exists to remove, and these wrong implementations all
 * passed it: never wiping preferences, never clearing the cache, never clearing diagnostics, and
 * making `wipeBiometricMaterial()` a successful no-op. Round 1's content hashing fixed
 * REPRESENTATION; it could not fix COVERAGE, and a gate cannot detect residue its scenario never
 * creates. It certified whatever it happened to create.
 *
 * Four structural changes, in the order they matter:
 *
 *  1. **Provisioning runs the PRODUCTION path** — `createVaultAndPublish`, the same call onboarding
 *     makes. It writes `onboarding_done`, runs `wipeLegacyPrefs()` (which CREATES the three lazy
 *     prefs files), and publishes a real session. Residue now arrives the way it arrives in the
 *     field instead of being imagined by the test.
 *  2. **Every domain gets a NAMED seeded artifact, asserted PRESENT before the burn**
 *     ([assertProvisioned]). A domain whose seed is missing means the gate is not covering it —
 *     which the assertions now say out loud, rather than the comparison silently passing over an
 *     empty set.
 *  3. **Per-domain NEGATIVE CONTROLS** ([the_snapshot_discriminates_in_every_domain_it_claims]).
 *     Each domain is proven able to report a difference, by planting one and checking the comparison
 *     names it. Previously ONE domain (Keystore) had a control and the other four were trusted.
 *  4. **`cacheDir` is in the snapshot**, so the fail-closed cache clear is actually compared.
 *
 * ─── THE LIMIT OF THIS GATE, STATED RATHER THAN DISCOVERED ──────────────────────────────────────
 *
 * It cannot see an artifact that is created and then correctly wiped — that state is identical to
 * one never created. So a green run does NOT prove the coverage set is complete; it proves the burn
 * removes what this scenario produces. Completeness of the set is a SOURCE-ENUMERATION obligation
 * (`AppContainer.wipeVaultUsePreferences` carries the preference-store table), and a reviewer should
 * enumerate lazily-created artifacts in source rather than trusting a green run here. The seeded,
 * asserted-present artifacts in (2) exist to shrink that blind spot to the artifacts nobody named.
 */
@RunWith(AndroidJUnit4::class)
class BurnByteForByteGateTest {

    private lateinit var ctx: Context
    private lateinit var container: AppContainer

    /**
     * The app-local state this gate compares. **Anything not in here is silently unverified**, which
     * is why `caches` was added: the burn clears `cacheDir` fail-closed, and a wipe step whose result
     * no snapshot observes is a wipe step no test can defend.
     */
    private data class StateSnapshot(
        val files: Map<String, String>,
        val prefs: Map<String, String>,
        val keystoreAliases: Map<String, String>,
        val databases: Map<String, String>,
        val caches: Map<String, String>,
        /**
         * ACTIVE SYSTEM NOTIFICATIONS (round 4, Codex). Not a filesystem domain at all — this state
         * lives in system_server — which is exactly why every file-based check missed it while
         * `MessagingNotifications.cancelAll` sat in the tree with zero call sites. A posted
         * notification outlives both the burn and the process death that follows it, and a fresh
         * install has none.
         */
        val activeNotifications: Map<String, String>,
    )

    /**
     * CONTENT HASHES, NOT LENGTHS (round-1 review — the gate compared neither bytes nor prefs and
     * database state, while `SECURITY_MODEL.md` claimed all three). A length-only comparison passes
     * over a surviving artifact of identical size, and a filename-only comparison passes over residue
     * written INSIDE an existing prefs file — which is where session state actually goes, and where
     * round 2's `onboarding_done` defect lived.
     */
    private fun digest(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun treeHashes(root: File): Map<String, String> =
        if (!root.exists()) emptyMap()
        else root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to runCatching { digest(it) }.getOrDefault("<unreadable>") }

    /**
     * FLUSH BARRIER — found by this gate's own negative control, on its first execution.
     *
     * Production writes preferences with `apply()` ([SettingsRepository.put],
     * [BiometricUnlockStore]), which updates memory immediately and schedules the disk write on a
     * background thread. A snapshot taken straight after a seeded write therefore read the PREVIOUS
     * bytes, and the comparison reported "no difference" over residue that genuinely existed. The
     * first run failed on exactly that: the per-domain control for "a KEY inside the store a fresh
     * install also has" planted `onboarding_done` and saw nothing change.
     *
     * **That failure is the control doing its job.** What it caught is a gate comparing a racing
     * disk — the kind of gate that reports green over residue.
     *
     * **A CORRECTION, kept here because the wrong version was committed and reviewed.** This kdoc
     * used to argue that production was safe because "a `commit()` is ordered FIFO behind any
     * in-flight `apply()` on the same store". Two independent reviewers could neither refute nor
     * confirm that; a third read the platform differently again, holding that `commit()` does not
     * drain `QueuedWork` at all and that what actually discards a late write is
     * `SharedPreferencesImpl`'s disk-GENERATION guard. Three readings, no confirmation — so the
     * honest status of the original claim is "unproven", not "true". **Production no longer depends
     * on any of it:** a successful burn now ends in process death, and the queue dies with the
     * process (see `AppContainer.burnVault`). The barrier below remains necessary for the GATE
     * alone, which must read settled bytes in a process it deliberately keeps alive.
     *
     * An empty `commit()` is the barrier: it awaits its own write, and any earlier `apply()` to that
     * store is queued ahead of it. Only stores whose file ALREADY exists are opened — opening one
     * that a fresh install lacks would create it, and after a burn these three must stay absent.
     */
    private fun flushPendingPrefsWrites() {
        val prefsDir = File(ctx.filesDir.parentFile!!, "shared_prefs")
        ALL_PREFS_STORES.forEach { name ->
            if (File(prefsDir, "$name.xml").exists()) {
                runCatching { container.keyStoreManager.prefs(name).edit().commit() }
            }
        }
    }

    private fun snapshot(): StateSnapshot {
        flushPendingPrefsWrites()
        val dataDir = ctx.filesDir.parentFile!!
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return StateSnapshot(
            files = treeHashes(ctx.filesDir),
            prefs = treeHashes(File(dataDir, "shared_prefs")),
            // Aliases carry no comparable content; the map shape exists so every domain runs through
            // the SAME diff, and so a domain can never be compared by a weaker rule than its
            // neighbours without that being visible here.
            keystoreAliases = ks.aliases().toList().associateWith { "" },
            databases = treeHashes(File(dataDir, "databases")),
            caches = treeHashes(ctx.cacheDir),
            activeNotifications = runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.activeNotifications
                    .filter { it.packageName == ctx.packageName }
                    .associate { "id=${it.id}:tag=${it.tag}" to it.notification.channelId }
            }.getOrElse {
                // A SENTINEL, NOT emptyMap() (round 5, Codex). Defaulting an unreadable domain to
                // "empty" makes a snapshot failure indistinguishable from a clean device, so the
                // comparison would pass while observing nothing at all.
                mapOf("<unreadable>" to it.toString())
            },
        )
    }

    /** Names whose content changed, appeared, or vanished between two views of one domain. */
    private fun changed(before: Map<String, String>, after: Map<String, String>): Set<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.toSet()

    /**
     * EXPLICIT EXCLUSIONS, each with its reason IN the test (an exclusion list that grows without
     * scrutiny is a checklist wearing a test's clothes). These are OS-level and outside app control;
     * the app cannot claim fresh-install-indistinguishability for them, and they are disclosed in
     * `SECURITY_MODEL.md` as limitations rather than silently dropped:
     *  - package install/update time — recorded by the package manager, not the app;
     *  - UsageStats / battery attribution — system-journaled;
     *  - notification HISTORY — system-journaled;
     *  - **NOTIFICATION CHANNEL STATE — genuinely NOT compared, and the previous wording here was
     *    FALSE.** It claimed channels the app created "ARE compared, via prefs". They are not:
     *    channels live in `NotificationManager`, and [snapshot] covers files, `shared_prefs`,
     *    Keystore aliases, databases and `cacheDir` — no channel domain exists. Round 3 caught the
     *    claim, which is this unit's signature defect (confident prose the code never supported)
     *    appearing in the exclusion list of the test that exists to prevent it. What is TRUE:
     *    `MessagingNotifications.ensureChannel` runs in `Application.onCreate` on EVERY launch
     *    including a fresh install, so a channel's EXISTENCE is not a vault-use oracle. What remains
     *    uncovered: a user's own modifications to importance/sound/vibration survive a burn. Reset is
     *    tracked as follow-up rather than claimed here — an exclusion stated honestly is worth more
     *    than a coverage claim that is not true;
     *  - MediaStore exports — user-initiated exports leave the app sandbox by design;
     *  - NAND-level residue — crypto-erase is the guarantee, not physical sanitisation.
     */
    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        container = (ctx.applicationContext as ZitroneApp).container
        // Called here rather than as a second @Before: JUnit4 does not guarantee the ORDER of two
        // @Before methods in one class, and this one needs `container` already assigned. An ordering
        // the harness does not guarantee is exactly the kind of assumption this unit keeps being
        // wrong about.
        assertFreshBaseline()
    }

    /**
     * These tests share ONE device and ONE process, and each takes its "fresh" baseline at its own
     * start — so a test that leaks a live session or a vault image does not fail itself, it
     * corrupts the baseline of whichever test runs next. Teardown is therefore part of the harness's
     * correctness, not tidiness.
     *
     * `lock()` first: production's own burn leaves the session published (the composition routes to
     * onboarding rather than locking), and `createVaultAndPublish` REFUSES to build over a live one.
     * A post-burn reseal cannot resurrect the image — `obliterateLocked` nulls `canonical` and `dek`,
     * so the reseal throws and `lockCurrent` swallows it — but the session must still go for the
     * next unlock to succeed.
     */
    @After
    fun tearDown() {
        runCatching { container.unlockController.lock() }
        // UNCONDITIONAL, and that is the round-3 fix. This used to read
        // `if (container.hasVault()) …`, which is precisely wrong: the burn removes the IMAGE FIRST
        // and can then fail while clearing biometrics, diagnostics, caches or preferences. In that
        // state `hasVault()` is false, so teardown did nothing and left exactly the later-stage
        // residue — which the next test then snapshotted as "fresh", putting the residue on BOTH
        // sides of its comparison and making the load-bearing gate pass for the wrong reason.
        // The burn is idempotent, so running it over an already-clean device is free.
        runCatching { container.burnVault(terminate = {}) }
    }

    /**
     * THE BASELINE IS ASSERTED, NOT ASSUMED — and it is derived from the SAME snapshotter the gate
     * compares with, never a parallel checklist.
     *
     * Each test takes its own "fresh" reading at its start, so a test that leaks residue does not
     * fail itself: it corrupts whichever test runs next. A hand-maintained list of things to check
     * would drift from the snapshot surface within a quarter — that is a guarantee, not a risk — so
     * this walks [snapshot]'s own domains. One snapshotter, two consumers: the fresh-vs-burned
     * equivalence comparison, and this. Add a domain to the snapshot and it is covered here on the
     * next compile.
     *
     * Failing HERE rather than in the comparison is the point: a corrupted baseline is a harness
     * fault, and reporting it as a burn defect would send the next reader hunting in the wrong place.
     */
    private fun assertFreshBaseline() {
        val s = snapshot()
        assertFalse("baseline: a vault image survived a previous test", container.hasVault())
        assertFalse("baseline: a durability hold survived a previous test", container.durabilityHold.value)
        LAZY_PREFS.forEach {
            assertFalse("baseline: lazily-created prefs store $it survived a previous test", s.prefs.containsKey(it))
        }
        assertTrue("baseline: the plaintext cache is not empty", s.caches.isEmpty())
        assertFalse("baseline: a diagnostics log survived a previous test", s.files.containsKey(DIAGNOSTICS_LOG))
        assertTrue(
            "baseline: a vault-related Keystore alias survived a previous test",
            s.keystoreAliases.keys.none {
                it.startsWith(BiometricVaultKeyCipher.PREFIX) || it == KeystoreDeviceKeyCipher.DEFAULT_ALIAS
            },
        )
        assertTrue("baseline: databases must be empty", s.databases.isEmpty())
        // SETTINGS CONTENT, not just the lazy FILES (round 4, both lenses). The previous version
        // checked which prefs files existed and never what was inside the one that always exists —
        // so a leaked `onboarding_done` passed the baseline, and the claim that deriving this from
        // the snapshotter made it complete was an overclaim: using the snapshot's OUTPUT is not the
        // same as validating every domain in it.
        assertTrue(
            "baseline: the settings store still holds app keys from a previous test",
            container.vaultUsePreferencesAreFresh(),
        )
        // ACTIVE NOTIFICATIONS — the round-4 domain. A notification posted by an earlier test would
        // otherwise sit on the lock screen and be invisible to every file-based check here.
        assertTrue(
            "baseline: an active notification survived a previous test",
            MessagingNotifications.noneActive(ctx),
        )
    }

    /** Plant a REAL alias carrying production's biometric prefix — residue of exactly the reaped class. */
    private fun plantBiometricAlias(alias: String) {
        // Deliberately NOT auth-gated: `newEncryptCipher` requires an enrolled biometric, which a
        // headless CI emulator has none of — the gate would then fail for an environmental reason
        // and prove nothing about residue.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    /**
     * Drive the PRODUCTION create/publish path and then seed the domains production alone does not
     * reach on a headless emulator, each with a NAMED artifact.
     *
     * Which is which, so no reader has to guess how faithful this is:
     *  - PRODUCTION-GENERATED: the vault image + DEK, the device-key Keystore alias (lazily created
     *    by the first `wrapDek`), `onboarding_done`, and the three lazily-created prefs FILES
     *    (`wipeLegacyPrefs()` opens them during create).
     *  - SEEDED BY THIS TEST, with cause: a non-default device SETTING (a user action, not a boot
     *    side effect); the diagnostics log (production writes it during boot reconciliation, which
     *    has already happened for this process); a cache file (production fills `cacheDir` only from
     *    a live attachment/QR download, which needs a relay); and a biometric-prefixed alias
     *    (enabling biometrics needs an ENROLLED biometric, which CI emulators lack).
     */
    private fun provisionThroughProduction() {
        assertTrue(
            "precondition: the production create/publish path must succeed, or nothing below is " +
                "testing production",
            runBlocking { container.createVaultAndPublish(PASSPHRASE) },
        )
        container.settingsRepository.setTorEnabled(true)
        container.bootDiagnostics.record(DIAGNOSTIC_LINE)
        File(ctx.cacheDir, CACHE_ARTIFACT).writeText("plaintext attachment stand-in")
        plantBiometricAlias(BIOMETRIC_ALIAS)
        // A REAL posted notification (round 5, both lenses). The domain was added to the snapshot and
        // the baseline in round 4 and NEVER SEEDED, so `fresh.activeNotifications` and
        // `burned.activeNotifications` were both empty and compared equal on every run — a wrong
        // implementation that deleted the cancel step passed. Non-discriminating assertion, sixth
        // occurrence, committed in the very fix for the notification finding.
        MessagingNotifications.showNewMessage(ctx)
    }

    /**
     * Every domain's seed, asserted PRESENT before the burn and BY NAME.
     *
     * This is the assertion the old gate lacked, and its absence is why it certified whatever it
     * happened to create: a comparison over a domain the scenario never populated passes trivially,
     * and reads in review as proof. If a seed is missing here the gate FAILS LOUDLY as
     * mis-provisioned, instead of passing quietly with that domain empty.
     */
    private fun assertProvisioned(fresh: StateSnapshot, provisioned: StateSnapshot) {
        assertTrue(
            "files: the vault image must exist before a burn can be said to remove it",
            provisioned.files.containsKey(VAULT_IMAGE),
        )
        assertTrue(
            "files: the diagnostics log — the artifact whose ungated clear was a round-2 HIGH",
            provisioned.files.containsKey(DIAGNOSTICS_LOG),
        )
        assertNotEquals(
            "prefs: the settings store must now differ from fresh — `onboarding_done` and a " +
                "non-default setting are KEYS INSIDE an innocent-looking file, which is exactly " +
                "the residue class round 2 found and round 1's file-level reasoning missed",
            fresh.prefs[SETTINGS_PREFS],
            provisioned.prefs[SETTINGS_PREFS],
        )
        LAZY_PREFS.forEach {
            assertTrue(
                "prefs: $it must exist after production create — a never-used device has no such " +
                    "file, so its presence is the oracle the burn must remove",
                provisioned.prefs.containsKey(it),
            )
        }
        assertTrue(
            "keystore: the device-key alias is created LAZILY by the first wrapDek",
            provisioned.keystoreAliases.containsKey(KeystoreDeviceKeyCipher.DEFAULT_ALIAS),
        )
        assertTrue(
            "keystore: the biometric-prefixed alias must exist, or the burn's biometric wipe is " +
                "asserted against nothing",
            provisioned.keystoreAliases.containsKey(BIOMETRIC_ALIAS),
        )
        assertTrue(
            "cache: the plaintext cache artifact",
            provisioned.caches.containsKey(CACHE_ARTIFACT),
        )
        assertTrue(
            "notifications: a posted notification must be visible to the snapshot before the burn, " +
                "or the post-burn comparison is empty-equals-empty",
            provisioned.activeNotifications.isNotEmpty(),
        )
    }

    /**
     * THE GATE. Fresh → provisioned through production → burned → compared, in one run so "fresh" is
     * this device's actual fresh state rather than an assumption about it.
     */
    @Test
    fun post_burn_state_matches_post_fresh_install_state() {
        val fresh = snapshot()
        assertTrue(
            "the app creates no databases, so this domain is asserted EMPTY rather than compared " +
                "over content. If this fires, the app has gained a database and the gate has been " +
                "silently comparing an empty set — re-derive the coverage claim before deleting it.",
            fresh.databases.isEmpty(),
        )

        provisionThroughProduction()
        val provisioned = snapshot()
        assertProvisioned(fresh, provisioned)

        // Through production's own terminal exclusion, as MainActivity's `onBurn` does — a live
        // session must not be writing while the image is obliterated underneath it.
        container.unlockController.beginTerminalWipe()
        var terminated = 0
        try {
            container.burnVault(terminate = { terminated++ })
        } finally {
            container.unlockController.endTerminalWipe()
        }
        // PRODUCTION KILLS THE PROCESS HERE. The gate records the request instead — a test that
        // killed its own process could assert nothing about the state the burn left behind, which is
        // the whole point of this test. Stated as a LIMIT, not glossed: what follows verifies the
        // state at the moment of termination, and NOT that the process actually dies or that nothing
        // rewrites state afterwards. A next-launch assertion is tracked as follow-up.
        assertEquals("a successful burn must request process death exactly once", 1, terminated)

        val burned = snapshot()
        assertEquals("files must match a fresh install", fresh.files, burned.files)
        assertEquals("shared_prefs must match a fresh install", fresh.prefs, burned.prefs)
        assertEquals("databases must match a fresh install", fresh.databases, burned.databases)
        assertEquals("the plaintext cache must match a fresh install", fresh.caches, burned.caches)
        assertEquals(
            "NO Keystore alias may survive a burn — an orphaned alias is 'something was here'",
            fresh.keystoreAliases,
            burned.keystoreAliases,
        )
        assertEquals(
            "no active notification may survive a burn — it sits on the LOCK SCREEN, which is the " +
                "one surface a coercer is already looking at, and a fresh install has none",
            fresh.activeNotifications,
            burned.activeNotifications,
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

        provisionThroughProduction()
        container.burnVault(terminate = {})

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
     * wipe on false. This was unclosable under Robolectric (no AndroidKeyStore to fail against).
     *
     * **Round 2 rejected the previous version of this test, correctly.** It asserted "no biometric
     * alias remains" after a scenario that never ENABLED biometrics — so no alias existed to remove,
     * and a burn that ignored the wipe's boolean entirely (or whose wipe was a successful no-op)
     * passed it. The assertion was satisfied by both the correct and the incorrect implementation:
     * it named the defect it was written to catch and then failed to discriminate against it.
     *
     * The fix is a real alias, planted with production's own prefix, asserted PRESENT first. A no-op
     * wipe now leaves it behind and fails this test at the second assertion.
     */
    @Test
    fun burn_requires_the_biometric_wipe_to_succeed() {
        provisionThroughProduction()
        assertTrue(
            "precondition: there must BE biometric material, or 'none survived' is vacuous",
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .aliases().toList().any { it.startsWith(BiometricVaultKeyCipher.PREFIX) },
        )

        container.burnVault(terminate = {})

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(
            "the planted alias must be GONE: if the wipe could fail (or no-op) silently, the burn " +
                "would still report success and the hold would still be lowered",
            ks.aliases().toList().none { it.startsWith(BiometricVaultKeyCipher.PREFIX) },
        )
        assertFalse(container.durabilityHold.value)
    }

    /**
     * THE NEGATIVE CONTROLS — one per domain, because the gate must be able to FAIL in each of them.
     *
     * A byte-for-byte comparison that passes is evidence only if it would have caught a survivor,
     * and that has to be shown per DOMAIN: a comparison can be sound for files and structurally
     * blind for caches, and the aggregate green run looks identical either way. Round 2's finding was
     * exactly this shape — Keystore had a control, and the other four domains were trusted rather
     * than proven.
     *
     * Each control plants ONE named artifact, asserts the domain's comparison NAMES it, then removes
     * it and asserts the domain returned to its prior state — so a control cannot leave residue that
     * corrupts the next test's baseline.
     */
    @Test
    fun the_snapshot_discriminates_in_every_domain_it_claims() {
        val dataDir = ctx.filesDir.parentFile!!

        assertDiscriminates(
            domain = "files",
            artifact = "gate-negative-file",
            view = { it.files },
            plant = { File(ctx.filesDir, "gate-negative-file").writeText("residue") },
            cleanup = { File(ctx.filesDir, "gate-negative-file").delete() },
        )

        // Two controls for prefs, because prefs residue comes in two shapes and round 2's defect was
        // the SECOND one — a key written inside a file a fresh install also has.
        assertDiscriminates(
            domain = "prefs (a whole lazily-created store file)",
            artifact = "zitrone_auth.xml",
            view = { it.prefs },
            plant = {
                container.keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH)
                    .edit().putString("gate_negative", "residue").commit()
            },
            cleanup = {
                container.keyStoreManager.forget(KeyStoreManager.PREFS_AUTH)
                File(dataDir, "shared_prefs/zitrone_auth.xml").delete()
                File(dataDir, "shared_prefs/zitrone_auth.xml.bak").delete()
            },
        )
        assertDiscriminates(
            domain = "prefs (a KEY inside the store a fresh install also has)",
            artifact = SETTINGS_PREFS,
            view = { it.prefs },
            plant = { container.settingsRepository.setOnboardingDone(true) },
            cleanup = { container.settingsRepository.resetToFreshInstallDefaults() },
        )

        assertDiscriminates(
            domain = "keystore",
            artifact = BiometricVaultKeyCipher.PREFIX + "gatenegative",
            view = { it.keystoreAliases },
            plant = { plantBiometricAlias(BiometricVaultKeyCipher.PREFIX + "gatenegative") },
            cleanup = { container.wipeBiometricMaterial() },
        )

        assertDiscriminates(
            domain = "databases",
            artifact = "gate-negative.db",
            view = { it.databases },
            plant = {
                File(dataDir, "databases").mkdirs()
                File(dataDir, "databases/gate-negative.db").writeText("residue")
            },
            cleanup = { File(dataDir, "databases/gate-negative.db").delete() },
        )

        assertDiscriminates(
            domain = "notifications",
            artifact = "id=${MessagingNotifications.NOTIFICATION_ID}:tag=null",
            view = { it.activeNotifications },
            plant = { MessagingNotifications.showNewMessage(ctx) },
            cleanup = { MessagingNotifications.cancelAll(ctx) },
        )

        assertDiscriminates(
            domain = "caches",
            artifact = "gate-negative-cache.bin",
            view = { it.caches },
            plant = { File(ctx.cacheDir, "gate-negative-cache.bin").writeText("residue") },
            cleanup = { File(ctx.cacheDir, "gate-negative-cache.bin").delete() },
        )
    }

    /**
     * CANARY — not a proof, and the name says so.
     *
     * Stages the race that round 3 could not settle by argument: a preference write left IN FLIGHT
     * when the burn runs. The question was whether it can land AFTER the burn unlinked the store and
     * proved it gone, which would make post-burn state distinguishable from a fresh install.
     *
     * **What this test is NOT.** A bounded observation can only ever prove the PRESENCE of the bug,
     * never its absence — a scheduler that delayed the queued write past the window would pass this
     * and still be a defect. It is a tripwire that fires loudly if platform behaviour regresses (an
     * OEM build, an API bump), not the reason the production path is safe.
     *
     * **Why the production path is safe is elsewhere:** a real burn ends in process death, and the
     * `QueuedWork` queue dies with the process. This test deliberately passes a no-op `terminate` so
     * it can observe the window at all, which means it exercises the strictly WEAKER in-process
     * arrangement. Reading it as evidence about production would be reading it backwards.
     *
     * Tracked follow-up: a next-launch assertion (burn, relaunch, assert at boot) would test the
     * contract actually shipped. That needs multi-process orchestration this harness does not have.
     */
    @Test
    fun canary_a_queued_preference_write_does_not_resurrect_a_proven_absent_store() {
        val target = File(ctx.filesDir.parentFile!!, "shared_prefs/zitrone_auth.xml")
        provisionThroughProduction()
        assertTrue("precondition: the store must exist, or there is nothing to resurrect", target.exists())

        // Left deliberately in flight — the same shape as wipeLegacyPrefs()'s own writes.
        container.keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH)
            .edit().putString("in_flight_at_burn", "queued before the wipe").apply()

        container.burnVault(terminate = {})
        assertFalse("the burn must prove the store absent", target.exists())

        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            assertFalse(
                "a write queued BEFORE the burn recreated a store the burn had proved absent — " +
                    "post-burn state is distinguishable from a fresh install, and the proof of " +
                    "absence was only momentarily true",
                target.exists(),
            )
            Thread.sleep(25)
        }
    }

    private fun assertDiscriminates(
        domain: String,
        artifact: String,
        view: (StateSnapshot) -> Map<String, String>,
        plant: () -> Unit,
        cleanup: () -> Unit,
    ) {
        val before = view(snapshot())
        plant()
        val after = view(snapshot())
        try {
            assertTrue(
                "$domain: planting `$artifact` produced NO observable difference. This domain is " +
                    "not actually being compared, and every green run of this gate has been " +
                    "vacuous for it.",
                changed(before, after).contains(artifact),
            )
        } finally {
            cleanup()
        }
        assertEquals(
            "$domain: the control must leave no residue, or it corrupts the next test's baseline",
            before,
            view(snapshot()),
        )
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val VAULT_IMAGE = "vault.bin"
        const val DIAGNOSTICS_LOG = "boot-diagnostics.log"
        const val SETTINGS_PREFS = "zitrone_settings.xml"
        const val CACHE_ARTIFACT = "gate-seeded-attachment.bin"
        const val DIAGNOSTIC_LINE = "gate-seeded diagnostics entry"
        val BIOMETRIC_ALIAS = BiometricVaultKeyCipher.PREFIX + "gateseeded"
        val LAZY_PREFS = listOf(
            "zitrone_signal_store.xml",
            "zitrone_auth.xml",
            "zitrone_contacts.xml",
        )

        /** Every store [KeyStoreManager] can open — the flush barrier must cover all of them. */
        val ALL_PREFS_STORES = listOf(
            KeyStoreManager.PREFS_SETTINGS,
            KeyStoreManager.PREFS_SIGNAL_STORE,
            KeyStoreManager.PREFS_AUTH,
            KeyStoreManager.PREFS_CONTACTS,
        )
    }
}
