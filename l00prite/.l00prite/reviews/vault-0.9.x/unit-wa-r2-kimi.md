• Let me start by understanding the repository state. I need to look at the git diff, the commits, and the relevant source files.

  Let me plan: this is a substantial review task. I should:
  1. Look at git log, git diff main...HEAD, git show 0d348b4
  2. Read the key files (boot routing, sweep, tests)
  3. Run the test suite
  4. Verify claims A–I

  Let me start with git commands. I can do a lot myself, but this is large. Maybe I'll do the core reading myself since verdicts depend on details. Let me begin.

• I'll start by getting the lay of the land: git history, the diff, and the fix delta.0d348b4 Unit W-A round-1 fixes — one legacy authority, gate 2 covered, one derivation
96a2608 l00prite: W-A extracted; round-1 findings are all extraction defects
a98677f 0.9.2 Unit W-A — cold-start orphan residue sweep + fail-closed boot routing
c3e4038 CI pipeline security — close release-apk.yml shell-injection + make SAST actually gate (#59)
e32f0aa 0.9.2-beta — biometric-enable atomicity (closes the disclosed orphan gap) (#57)
e203560 Update README.md
956bae9 0.9.2-beta PR-3 Unit 2 — honest docs for the second vault (deliver-then-claim) (#56)
23c9bc4 0.9.2-beta PR-3 Unit 1 — biometric A-only guard (never repoint the single wrap) (#55)
374bd44 0.9.2-beta PR-2 — triple-entry router fusion + uninterrupted-sequence guard (#54)
4aeaca3 chore(l00prite): add security-review-loop prompt + record PR #52 merge (#53)
b8eb652 chore(l00prite): adopt the current l00prite scaffolding layout (tracked) (#52)
2de2bac feat(vault): PR-1 — attemptUnlockOrAdd (second vault) + slot-0 burn reservation + v2 retire (#51)
55540e3 release: 0.9.1-beta (vc17) + clearnet download pointer flip
b7e4b87 docs(0.9.1-beta): PR-F release notes + honest plausible-deniability status (#49)
0d1a3dc fix(security): override postcss to ^8.5.12 (CVE-2026-45623, HIGH) (#50)
---
HEAD detached at refs/heads/feat/0.9.2-unit-wa-residue-sweep
nothing to commit, working tree clean
---
 .../src/main/java/com/zitrone/app/MainActivity.kt  | 125 ++++---
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 230 +++++++++++++
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 167 +++++++++
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 295 ++++++++++++++++
 .../src/test/java/com/zitrone/app/BootRouteTest.kt | 256 ++++++++++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 374 +++++++++++++++++++++
 docs/SECURITY_MODEL.md                             |  36 ++
 l00prite/.l00prite/ledger.md                       |  54 +++
 8 files changed, 1494 insertions(+), 43 deletions(-)
commit 0d348b4f7860536016ce53b9b09dc3e9341b69b6
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 12:36:27 2026 +0000

    Unit W-A round-1 fixes — one legacy authority, gate 2 covered, one derivation
    
    Four source-capable lenses on the extraction (codex gpt-5.6-sol, grok grok-4.5,
    gemini 3.1-pro, kimi k3), each in its own writable worktree. Every finding is
    mine, and EVERY ONE is an extraction defect invisible to the six rounds the
    parent unit already spent — which is exactly why the extraction got its own round.
    
    HIGH (Codex + Grok, converged) — main's standalone legacy-image LaunchedEffect
    SURVIVED the port. Unit W's round 3 deleted that effect ON THE FEATURE BRANCH;
    W-A was cut from MAIN, which predates that fix, so the extraction reintroduced a
    SECOND legacy routing authority: it set Route.Onboarding on its own without
    awaiting bootReconciled, without the carried residueSweepHold, and without
    consulting serverDeleteConfirmed(). With {v2 image + durable delete-confirmed} it
    preempts Route.DeleteIncomplete, and create() on that screen clears both markers,
    erasing the sole authorisation for the account-delete auto-destroy.
    
    ADJUDICATED AGAINST A DISSENT: Kimi rated it INFO, arguing {legacy + confirmed}
    is unreachable because a legacy image cannot be unlocked under 0.9.2, so no
    session, so no confirmed delete. That misses the upgrade path — the marker is
    written under 0.9.1, BEFORE the image became "legacy". A 0.9.1 install that
    confirms a delete, crashes mid-unlink, then upgrades lands exactly there. Two
    lenses right, one wrong, resolved on a concrete reachability path.
    Effect deleted; legacy is an input to the single decision.
    
    MEDIUM (Grok + Gemini, converged) — the row-7 confirmed-refuse test had been
    DELETED. Rewriting row 6b for W-A sliced out the adjacent test, so gate 2 — the
    ownership bar for an in-flight account deletion — had ZERO coverage while the
    suite header still claimed it walked the table "row by row". Removing gate 2
    entirely would not have failed the suite. Restored and mutation-verified:
    deleting gate 2 now fails exactly that test.
    
    MEDIUM (Gemini) — the five bootRoute inputs, including the ~1 MiB isLegacyImage
    decrypt and its skip conditions, were copy-pasted across all three consumers.
    Three copies of a safety derivation drift silently. Now one owner:
    deriveBootDecision, called by all three via deriveBootDecisionFromDisk.
    
    LOW (Kimi) — the post-boot re-derive applied DELETE_INCOMPLETE without
    re-checking the session after its IO suspend, while the Splash consumer does.
    The asymmetry was the finding; re-check added.
    
    LOW (Gemini) — `onboarding is reachable…` computed its expectation with a formula
    mirroring the implementation. It does catch mutations, but a developer can make
    it pass by copying a flawed rule into it. Expectations are now enumerated.
    
    LOW (Codex) — a test named "cancellation after a durable sweep" performed no
    cancellation. Worse, that window does not exist in this shape: publish runs in a
    `finally` with no suspension between verdict and publication. Renamed to what it
    proves; the reachable cancellation case is covered by the stranding test.
    
    INFO (Grok) — two suites still carried "PUCKER BURN Unit W" headers naming a unit
    that no longer exists here.
    INFO (Kimi) — SECURITY_MODEL overreached twice: "every path requires proven
    absence" (the legacy arm presents onboarding with an image present, by design),
    and "a refused gate holds the lock screen" (a refused gate returns NO_MUTATION
    and holds nothing). Both corrected to match the mechanism.
    
    MY OWN CLOSE-OUT CHECK CAUGHT A SILENT COVERAGE LOSS while fixing these: the test
    count came back 475 when it should have been 476. My header rewrite had spanned
    from the doc comment to the `fun`, dropping the @Test annotation in between — the
    test still compiled, still 
existed, and silently stopped running. Same class as
    the row-7 deletion, committed while fixing the row-7 deletion. Restored.
    
    Two lenses independently re-ran the suite and reproduced the figures (Grok, and
    Kimi parsing the JUnit XMLs itself) — the disposable-worktree rule working, after
    a read-only sandbox blocked exactly this last round.
    
    Tests: 476 total (+1), 0 failures, 473 passed, 3 skipped (I2P, pre-existing).
    No version bump.

 .../src/main/java/com/zitrone/app/MainActivity.kt  | 98 +++++-----------------
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 52 ++++++++++++
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 20 +++--
 .../src/test/java/com/zitrone/app/BootRouteTest.kt | 24 ++++--
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 24 ++++++
 docs/SECURITY_MODEL.md                             | 12 ++-
 6 files changed, 138 insertions(+), 92 deletions(-)


• Let me read the key source files. The main files:
  - apps/android/src/main/java/com/zitrone/app/ZitroneApp.kt
  - apps/android/src/main/java/com/zitrone/app/MainActivity.kt
  - apps/android/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
  - Tests: BootReconcileOwnerTest.kt, BootRouteTest.kt, SweepOrphanedResidueTest.kt
  - docs/SECURITY_MODEL.md

  Also compare with main: git show main:... for MainActivity onBurn etc.

  This is a large review. I could delegate parts, but verdicts depend on detail. Let me read the files myself. First, find the exact paths.apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
docs/SECURITY_MODEL.md
l00prite/.l00prite/ledger.md
---
wc: apps/android/src/main/java/com/zitrone/app/ZitroneApp.kt: No such file or directory
wc: apps/android/src/main/java/com/zitrone/app/MainActivity.kt: No such file or directory
wc: apps/android/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt: No such file or directory
wc: apps/android/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt: No such file or directory
wc: apps/android/src/test/java/com/zitrone/app/BootRouteTest.kt: No such file or directory
wc: apps/android/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt: No such file or directory
0 total
  1267 apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
  1599 apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
  1514 apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
   295 apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
   256 apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
   374 apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
  5305 total
Big files. Let me set up a todo list and read the diff first (the delta), then the full relevant files. Let me read the diff of a98677f for VaultImageStore and ZitroneApp new code, and the full diff main...HEAD for MainActivity.

  Actually more efficient: read the new functions in the files directly, plus git diff to see what changed. Let me start with the full diff since it's 1494 lines.diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index 250555f..cb6b87e 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -16,6 +16,7 @@ import com.zitrone.app.crypto.ZitroneSignalStore
 import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
 import com.zitrone.app.crypto.vault.KeystoreDeviceKeyCipher
 import com.zitrone.app.crypto.vault.LibsodiumVaultOps
+import com.zitrone.app.crypto.vault.ResidueSweepResult
 import com.zitrone.app.crypto.vault.VaultImageStore
 import com.zitrone.app.crypto.vault.UnlockOrAdd
 import com.zitrone.app.crypto.vault.VaultImageException
@@ -225,6 +226,77 @@ class AppContainer(private val app: Application) {
     /** Routing truth: a vault image is present → UNLOCK, absent → SETUP (onboarding). */
     fun hasVault(): Boolean = imageStore.exists()
 
+    /**
+     * FAIL-CLOSED routing truth: "there is provably nothing here", the only state that may present as
+     * a fresh install. [hasVault] is NOT a substitute — it keys on `vault.bin` alone, so a surviving
+     * `vault.dek` or `vault.bin.tmp` (which stages a COMPLETE outer image) reads as "no vault" and
+     * would route ONBOARDING over recoverable ciphertext.
+     */
+    fun vaultProvenAbsent(): Boolean = imageStore.obliterationComplete()
+
+    /**
+     * Read the four disk facts and produce ONE boot decision — the single derivation every routing
+     * consumer uses. Call OFF the main thread: the legacy probe reads and decrypts the outer layer.
+     */
+    internal fun deriveBootDecisionFromDisk(): BootDecision = deriveBootDecision(
+        serverDeleteConfirmed = serverDeleteConfirmed(),
+        imagePresent = hasVault(),
+        residueSweepHold = residueSweepHold.value,
+        vaultProvenAbsent = vaultProvenAbsent(),
+        isLegacyImage = { isLegacyImage() },
+    )
+
+    /** Cold-start orphan sweep — see [VaultImageStore.sweepOrphanedResidue]. */
+    fun sweepOrphanedVaultResidue(): ResidueSweepResult = imageStore.sweepOrphanedResidue()
+
+    /**
+     * PROCESS-scoped boot-reconciliation state.
+     *
+     * [bootReconciled] gates the fresh-install presentation: no route may be derived from disk until
+     * boot reconciliation has finished, because the sweep MUTATES what disk says. [residueSweepHold]
+     * carries forward the one fact a later stat cannot recover — that residue was unlinked WITHOUT
+     * proven durability — and withholds onboarding for the rest of this boot.
+     *
+     * PROCESS-scoped, not composition-scoped, and deliberately so: composition state resets on an
+     * Activity recreation, and a rotation that cleared this hold would restore exactly the
+     * fresh-install-over-residue presentation it exists to prevent.
+     */
+    val bootReconciled = MutableStateFlow(false)
+    val residueSweepHold = MutableStateFlow(false)
+
+    private val bootReconcileStarted = java.util.concurrent.atomic.AtomicBoolean(false)
+
+    /** Start boot reconciliation ONCE PER PROCESS; later callers no-op and observe [bootReconciled]. */
+    fun startBootReconcile() {
+        runBootReconcile(
+            scope = scope,
+            claim = { bootReconcileStarted.compareAndSet(false, true) },
+            sweep = { imageStore.sweepOrphanedResidue() },
+            publish = { hold ->
+                residueSweepHold.value = hold
+                bootReconciled.value = true
+            },
+            afterPublish = {
+                // Non-routing hygiene AFTER the gate opens — a slow cache clear must not hold splash.
+                runCatching { retryPlaintextCacheClearIfNoVault() }
+            },
+        )
+    }
+
+    /**
+     * Retry the plaintext-cache clear on a cold start. Cheap (a directory list), silent, self-healing.
+     *
+     * TRISTATE GATE: this DELETES on "no vault", so the gate must be a PROVEN absence
+     * ([VaultImageStore.primaryImageProvenAbsent]) and not the `File.exists()`-backed routing signal —
+     * a stat/I/O fault read as "absent" would clear the cache out from under a LIVE vault. The fail
+     * direction is the safe one (over-clearing an OS-evictable cache at cold start), but the caller of
+     * a destructive operation must not use the looser test.
+     */
+    fun retryPlaintextCacheClearIfNoVault(): Boolean {
+        if (!imageStore.primaryImageProvenAbsent()) return false
+        return runCatching { clearCacheDir(app.cacheDir) }.getOrDefault(false)
+    }
+
     /**
      * Routing signal (0.9.2): a present image is the PRIOR (v2 / 0.9.1) format, which the burn-slot
      * reservation makes unsafe to unlock — route to fresh onboarding instead of the lock screen. A
@@ -1035,3 +1107,161 @@ internal fun sealDurableOrFalse(seal: () -> Unit): Boolean =
     } catch (t: Throwable) {
         false
     }
+
+
+/**
+ * The boot-reconciliation OWNER, extracted so its lifecycle contract is testable on the host JVM.
+ * Four properties, each of which is a real failure mode:
+ *
+ *  1. **Once only.** [claim] is the CAS; a second call does nothing.
+ *  2. **Publication ordering.** [publish] runs before any consumer is released — consumers await the
+ *     published verdict instead of reading a field's default.
+ *  3. **Fail-closed default.** The verdict starts at [ResidueSweepResult.SWEPT_NOT_DURABLE], so a run
+ *     that dies before proving the disk durably clean releases waiters WITHHOLDING the fresh-install
+ *     presentation. A permissive default would make the race invisible and wrong exactly when it
+ *     matters.
+ *  4. **Cancellation cannot strand the claim.** [publish] is in a `finally`, so a claimant cancelled
+ *     after claiming and before publishing still releases every waiter. Without this the CAS stays
+ *     true with no other writer and every later consumer blocks forever.
+ *
+ * [scope] and [ioDispatcher] are injected precisely so a test can drive cancellation deterministically
+ * in virtual time; production passes the process-scoped [AppContainer.scope] and `Dispatchers.IO`.
+ */
+internal fun runBootReconcile(
+    scope: CoroutineScope,
+    claim: () -> Boolean,
+    sweep: () -> ResidueSweepResult,
+    publish: (hold: Boolean) -> Unit,
+    afterPublish: () -> Unit = {},
+    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
+) {
+    if (!claim()) return
+    scope.launch {
+        // FAIL-CLOSED default: only a completed, proven-durable sweep may lower this.
+        var result = ResidueSweepResult.SWEPT_NOT_DURABLE
+        try {
+            withContext(ioDispatcher) {
+                // NOT `runCatching` — that swallows CancellationException too, turning a cancelled
+                // boot into a "successful" one. A cancellation must propagate to the `finally`, which
+                // publishes the fail-closed default; only a genuine fault degrades and continues.
+                result = try {
+                    sweep()
+                } catch (c: CancellationException) {
+                    throw c
+                } catch (t: Throwable) {
+                    ResidueSweepResult.SWEPT_NOT_DURABLE
+                }
+            }
+        } finally {
+            // On EVERY exit — normal, throw, or cancellation. Non-suspending, so it still runs while
+            // the coroutine is being cancelled.
+            publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)
+        }
+        withContext(ioDispatcher) { afterPublish() }
+    }
+}
+
+/**
+ * Derive a boot decision from disk in ONE place. All three consumers (the Splash decision, the
+ * post-boot re-derive, and the session collector) call this rather than each assembling the five
+ * `bootRoute` inputs themselves.
+ *
+ * Round-1 review (Gemini): the derivation — including the ~1 MiB `isLegacyImage()` decrypt and its
+ * skip conditions — was copy-pasted across all three call sites. Three copies of a safety derivation
+ * drift silently: change one and the others keep the old rule, with no test able to catch the
+ * divergence. One owner, one derivation. This is also why the legacy probe's cost and its
+ * "only when it can matter" guard live here rather than being restated three times.
+ *
+ * MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer.
+ */
+internal fun deriveBootDecision(
+    serverDeleteConfirmed: Boolean,
+    imagePresent: Boolean,
+    residueSweepHold: Boolean,
+    vaultProvenAbsent: Boolean,
+    isLegacyImage: () -> Boolean,
+): BootDecision {
+    // Computed only when it can matter: never over a confirmed delete (that state is owned elsewhere)
+    // and never with no image to inspect.
+    val legacy = if (imagePresent && !serverDeleteConfirmed) {
+        runCatching { isLegacyImage() }.getOrDefault(false)
+    } else {
+        false
+    }
+    return BootDecision(
+        present = imagePresent,
+        legacy = legacy,
+        route = bootRoute(
+            serverDeleteConfirmed = serverDeleteConfirmed,
+            vaultImagePresent = imagePresent,
+            residueSweepHold = residueSweepHold,
+            vaultProvenAbsent = vaultProvenAbsent,
+            legacyImage = legacy,
+        ),
+    )
+}
+
+/** Where a composition must route out of Splash on a cold start — see [bootRoute]. */
+internal enum class BootRoute { DELETE_INCOMPLETE, LOCKED, ONBOARDING }
+
+/**
+ * One boot decision plus the disk facts it was taken from, so a caller applies a SINGLE consistent
+ * snapshot instead of re-reading disk after the decision.
+ */
+internal data class BootDecision(
+    val present: Boolean,
+    val legacy: Boolean,
+    val route: BootRoute,
+)
+
+/**
+ * The cold-start route decision, extracted as a PURE function so the fail-closed precedence is
+ * unit-testable without Compose.
+ *
+ * PRECEDENCE:
+ *  1. **A CONFIRMED server delete outbids everything** — `Route.DeleteIncomplete` owns that state.
+ *  2. **A LEGACY (v2) image goes to onboarding** — present but unusable, and its create() retires it.
+ *     Ordered AFTER the confirmed marker (a legacy image must never preempt finishing a confirmed
+ *     delete, whose create() would clear the marker authorising it) and BEFORE "a present image is a
+ *     lock screen" (a legacy image IS present, so it would otherwise fall through to a lock screen the
+ *     user can never pass).
+ *  3. **A present image is a lock screen.**
+ *  4. **A non-durable sweep withholds onboarding for the rest of this boot.** The residue is currently
+ *     unlinked, so [AppContainer.vaultProvenAbsent] says "clean" and would authorise a fresh install —
+ *     but a crash could replay the journal and bring it back. Absence that is not durable is not
+ *     absence.
+ *  5. **Onboarding requires PROVEN absence**, never merely "no `vault.bin`".
+ *  6. Anything else is a lock screen.
+ *
+ * No parameter carries a default: omitting an input must be a COMPILE ERROR, not a silently weaker
+ * call.
+ */
+internal fun bootRoute(
+    serverDeleteConfirmed: Boolean,
+    vaultImagePresent: Boolean,
+    residueSweepHold: Boolean,
+    vaultProvenAbsent: Boolean,
+    legacyImage: Boolean,
+): BootRoute = when {
+    serverDeleteConfirmed -> BootRoute.DELETE_INCOMPLETE
+    legacyImage -> BootRoute.ONBOARDING
+    vaultImagePresent -> BootRoute.LOCKED
+    residueSweepHold -> BootRoute.LOCKED
+    vaultProvenAbsent -> BootRoute.ONBOARDING
+    else -> BootRoute.LOCKED
+}
+
+/**
+ * Clear a cache directory, FAIL-CLOSED. `!exists()` conflates "confirmed absent" with "stat failed",
+ * and `listFiles()` returning null on an I/O or permission fault is exactly when plaintext is most
+ * likely still present — a directory we cannot read is a directory we cannot claim to have emptied.
+ */
+internal fun clearCacheDir(cacheDir: java.io.File?): Boolean {
+    if (cacheDir == null) return true
+    if (java.nio.file.Files.notExists(cacheDir.toPath())) return true
+    val entries = cacheDir.listFiles() ?: return false
+    entries.forEach { runCatching { it.deleteRecursively() } }
+    // Re-list to PROVE the clear rather than
 trusting deleteRecursively's bool (false on I/O error).
+    val remaining = cacheDir.listFiles() ?: return false
+    return remaining.isEmpty()
+}
Now the VaultImageStore diff.diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 9cd57e4..5234224 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -138,6 +138,31 @@ internal const val OUTER_IMAGE_BYTES: Int = IMAGE_BYTES + NONCE_BYTES + AEAD_TAG
  */
 internal enum class DirSyncResult { DURABLE, NOT_DURABLE }
 
+/**
+ * Outcome of [VaultImageStore.sweepOrphanedResidue].
+ *
+ * Three states, not two, because a routing decision must tell "the directory is clean" from "the
+ * directory LOOKS clean but the unlink that made it so is not crash-durable". A boolean collapses
+ * those, and a caller then re-derives cleanliness from a fresh stat — which reports absence the
+ * instant a file is unlinked, durable or not. A journal replay could then resurrect residue AFTER the
+ * app had already presented the fresh-install screen.
+ */
+enum class ResidueSweepResult {
+    /** Nothing was unlinked: already provably clean, or a gate refused. The disk is UNCHANGED. */
+    NO_MUTATION,
+
+    /** Residue was unlinked, proven absent, AND the unlink is crash-durable. Safe to route on. */
+    SWEPT_DURABLE,
+
+    /**
+     * The sweep passed its gates and MAY have unlinked, but durability is not confirmed (a
+     * non-durable `dirSync`, residue that survived the unlink, or a throw past the mutation point).
+     * The fresh-install presentation must be withheld for the rest of this boot: a later stat will
+     * say "absent" and be wrong about whether that survives a crash.
+     */
+    SWEPT_NOT_DURABLE,
+}
+
 /**
  * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
  * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
@@ -264,6 +289,17 @@ class VaultImageStore internal constructor(
     /** True when a vault image is present on disk (`vault.bin`). */
     fun exists(): Boolean = imageLock.withLock { binFile.exists() }
 
+    /**
+     * TRISTATE absence of the primary image. [exists] is a ROUTING signal built on `File.exists()`,
+     * where a stat/I/O fault is indistinguishable from absence — fine for routing (an unstattable
+     * vault routes to the lock screen, which then fails honestly), but NOT a basis for DESTRUCTIVE
+     * work. Only a PROVEN absence is true here; present and indeterminate are both false.
+     *
+     * Callers that DELETE on "no vault" must use this, not [exists].
+     */
+    fun primaryImageProvenAbsent(): Boolean =
+        imageLock.withLock { Files.notExists(binFile.toPath()) }
+
     /**
      * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
      * signal to send a 0.9.1 install to fresh onboarding instead of the lock screen (see
@@ -1257,6 +1293,137 @@ class VaultImageStore internal constructor(
     }
 
     /** Delete an incomplete-write temp for [target], if any. Best-effort. */
+    /**
+     * True ONLY when every image-bearing file is PROVEN absent — image, DEK envelope, and BOTH
+     * interrupted-write temps. Fail-closed: present OR indeterminate yields false.
+     *
+     * The temps are load-bearing, not incidental: [renameIntoPlace] stages the COMPLETE outer image in
+     * `vault.bin.tmp` (and the wrapped DEK in `vault.dek.tmp`), so a surviving temp is a surviving
+     * encrypted vault. Checking only `vault.bin` (as [exists] does, correctly, for ROUTING) would call
+     * a directory clean while a full image sat in a temp.
+     */
+    private fun imageBearingFilesProvenAbsent(): Boolean =
+        Files.notExists(binFile.toPath()) &&
+            Files.notExists(dekFile.toPath()) &&
+            Files.notExists(leftoverTmp(binFile).toPath()) &&
+            Files.notExists(leftoverTmp(dekFile).toPath())
+
+    /**
+     * Public fail-closed proof that the vault directory holds nothing image-bearing. This is the ONLY
+     * predicate that may authorise a fresh-install presentation; `hasVault()` keys on `vault.bin`
+     * alone and would call a directory empty while a surviving DEK or temp still held a recoverable
+     * vault.
+     */
+    fun obliterationComplete(): Boolean = imageLock.withLock { imageBearingFilesProvenAbsent() }
+
+    /**
+     * COLD-START ORPHAN SWEEP. Deletes an orphaned `vault.dek` / `vault.bin.tmp` / `vault.dek.tmp`
+     * when no image is present and no account deletion is pending. Returns [ResidueSweepResult].
+     *
+     * ── WHY THIS EXISTS (0.9.2 Unit W-A) ────────────────────────────────────────────────────────
+     * `{vault.bin absent, dek-or-temp present}` is reachable and, before this, was never healed. Two
+     * writers produce it with no burn involved:
+     *  - an interrupted [create]: it writes the DEK durably BEFORE `vault.bin` (the DEK-FIRST
+     *    DURABILITY BARRIER), so a crash between the two leaves a stray DEK and no image;
+     *  - an interrupted [retireLegacyImage]: it unlinks `binFile` and THEN `dekFile`, so a crash
+     *    between those unlinks leaves exactly the same shape.
+     * Boot routing keys on `vault.bin` alone, so it read that state as "no vault" and presented
+     * ordinary ONBOARDING. `vault.bin.tmp` stages a COMPLETE outer image, so that could be a
+     * fresh-install screen shown over a recoverable encrypted vault.
+     *
+     * ── WRITER/READER INVARIANT TABLE ───────────────────────────────────────────────────────────
+     * Every legitimate state that can hold a dek or a temp without a proven-present `vault.bin`, and
+     * what this gate does with it. A boot sweep with a too-broad condition deletes something it must
+     * not; a too-narrow one strands a recoverable image that no other path can reach. Both directions
+     * are proven here.
+     *
+     *  #  on-disk state                          writer                        gate result
+     *  ── ────────────────────────────────────── ───────────────────────────── ───────────────────
+     *  1  {dek, no bin, no markers}              interrupted create (DEK       SWEEP. The dek opens
+     *                                            durable, bin not written)     nothing — no image
+     *                                                                          exists. A create retry
+     *                                                                          overwrites it anyway.
+     *  1b {dek, no bin, no markers}              interrupted retireLegacyImage SWEEP. Same shape,
+     *                                            (unlinks bin THEN dek)        third writer. A legacy
+     *                                                                          DEK with no image is
+     *                                                                          dead data.
+     *  2  {dek.tmp, no bin, no markers}          crash inside                  SWEEP. Never a
+     *                                            renameIntoPlace(dekFile)      complete key for a
+     *                                                                          live image.
+     *  3  {dek, bin.tmp, no bin, no markers}     crash between the DEK barrier SWEEP. Loses a
+     *                                            and bin's rename              never-completed vault
+     *                                                                          — already this
+     *                                                                          codebase's policy:
+     *                                                                          [open] deletes
+     *                                                                          leftover temps, "the
+     *                                                                          main file is the last
+     *                                                                          durable state".
+     *  4  {bin present, anything}                a LIVE vault                  REFUSE (gate 1).
+     *  5  {bin indeterminate (stat fault)}       a failing filesystem          REFUSE (gate 1 is
+     *                                                                          `Files.notExists`,
+     *                                                                          true ONLY on a proven
+     *                                                                          absence).
+     *  6  {delete-intent present, bin present}   D2c delete in flight          REFUSE (gate 1 — the
+     *                                                                          IMAGE is what makes
+     *                                                                          this live, not the
+     *                                                                          intent).
+     *  7  {delete-confirmed present, ...}        D2c, account provably gone;   REFUSE (gate 2).
+     *                                            unlink incomplete             Route.DeleteIncomplete
+     *                                                                          owns it.
+     *  8  {confirmed marker indeterminate}       a failing filesystem          REFUSE (gate 2 is
+     *                                                                          `!notExists`, so
+     *                                                                          present OR
+     *                                                                          indeterminate refuse).
+     *  9  {nothing present}                      fresh install                 NO-OP (already proven
+     *                                                                          clean).
+     *
+     * There is deliberately NO gate on `vault.delete-intent`: [destroy] writes the CONFIRMED marker
+     * durably BEFORE it unlinks anything, so every real D2c unlink already carries the confirmed
+     * marker and is caught by gate 2 — while an intent alone never accompanies an absent image in a
+     * legitimate D2c state (an intent is written while the image is still present, and [create]
+     * clears both markers durably before writing the DEK). An intent gate would protect nothing and
+     * could only strand residue.
+     *
+     * ── OTHER PROPERTIES ────────────────────────────────────────────────────────────────────────
+     * Touches NO in-memory state (no [dek] wipe, no [canonical] drop, no [unregister]): gate 1 proves
+     * there is no image, so this store cannot hold an open one, and a boot-time disk-hygiene pass must
+     * not double as a teardown. It proves the result by RE-STAT and requires a durable [dirSync] —
+     * without that a journal replay could resurrect a temp AFTER routing had already presented
+     * onboarding, which is the very failure this closes. Idempotent, silent, safe on every cold start.
+     */
+    fun sweepOrphanedResidue(): ResidueSweepResult =
+        imageLock.withLock {
+            // GATE 1 — the image must be PROVEN absent. Present or indeterminate both refuse.
+            if (!Files.notExists(binFile.toPath())) return@withLock ResidueSweepResult.NO_MUTATION
+            // GATE 2 — no CONFIRMED delete. `!Files.notExists` is true when the marker is present OR
+            // indeterminate, so a failing stat refuses rather than sweeping state D2c owns.
+            if (!Files.notExists(serverDeletedFile.toPath())) {
+                return@withLock ResidueSweepResult.NO_MUTATION
+            }
+            // Already clean — the ordinary cold start. Do no destructive work and claim nothing.
+            if (imageBearingFilesProvenAbsent()) return@withLock ResidueSweepResult.NO_MUTATION
+
+            // ── MUTATION POINT ───────────────────────────────────────────────────────────────────
+            // Past here the disk MAY have changed, so no exit below may report NO_MUTATION — a caller
+            // that believed "nothing happened" would authorise a fresh-install presentation over an
+            // unlink that is not yet crash-durable. The catch is deliberate and total for the same
+            // reason: if we cannot tell how far we got, the honest answer is "mutated, not proven
+            // durable". This function is synchronous, so no CancellationException flows here.
+            try {
+                dekFile.delete()
+                deleteLeftoverTmp(dekFile)
+                deleteLeftoverTmp(binFile)
+
+                if (!imageBearingFilesProvenAbsent()) return@withLock ResidueSweepResult.SWEPT_NOT_DURABLE
+                if (dirSync(baseDir) != DirSyncResult.DURABLE) {
+                    return@withLock ResidueSweepResult.SWEPT_NOT_DURABLE
+                }
+                ResidueSweepResult.SWEPT_DURABLE
+            } catch (t: Throwable) {
+                ResidueSweepResult.SWEPT_NOT_DURABLE
+            }
+        }
+
     private fun leftoverTmp(target: File): File =
         File(target.parentFile, "${target.name}$TMP_SUFFIX")
 
Now MainActivity diff. That's 125 lines changed. Let me view it.diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 21e6d9b..5548582 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -85,6 +85,7 @@ import com.zitrone.app.ui.theme.TextSecondary
 import com.zitrone.app.ui.theme.ZitroneTheme
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.delay
+import kotlinx.coroutines.flow.first
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
 import kotlinx.coroutines.launch
@@ -628,6 +629,57 @@ private fun ZitroneRoot(
     // Routing truth (§0): a vault image present → UNLOCK, absent → SETUP. Flips true the
     // instant a create succeeds; otherwise unchanged for the process lifetime.
     var vaultExists by remember { mutableStateOf(container.hasVault()) }
+
+    // ── COLD-START BOOT ROUTING (0.9.2 Unit W-A) ────────────────────────────────────────────────
+    // The orphan sweep is a DESTRUCTIVE boot operation: it unlinks residue before any authentication.
+    // Nothing may derive a route from disk until it has finished and published its verdict, and the
+    // verdict must be CARRIED to the decision rather than re-derived from a fresh stat there — a stat
+    // reports absence the instant a file is unlinked, whether or not that survives a crash.
+    var splashFinished by remember { mutableStateOf(false) }
+    val bootDone by container.bootReconciled.collectAsState()
+
+    // Whichever of {animation ended, boot published} lands second triggers the decision, so there is
+    // no window in which Splash can route off pre-reconciliation state.
+    LaunchedEffect(splashFinished, bootDone) {
+        if (!splashFinished || !bootDone) return@LaunchedEffect
+        if (route != Route.Splash) return@LaunchedEffect
+        val decided = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
+        // RE-CHECK AFTER THE SUSPEND: the guard above ran before `withContext`, and a decision taken
+        // for a tree that has since left Splash must not be applied to it.
+        if (route != Route.Splash) return@LaunchedEffect
+        vaultExists = decided.present && !decided.legacy
+        route = when (decided.route) {
+            BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
+            BootRoute.ONBOARDING -> Route.Onboarding
+            BootRoute.LOCKED -> Route.Locked
+        }
+    }
+
+    LaunchedEffect(Unit) {
+        // Started on the PROCESS scope, never owned by this composition: a rotation that cancelled
+        // the claiming coroutine after it won the CAS but before it published would leave every later
+        // composition waiting forever. Idempotent — later calls no-op.
+        container.startBootReconcile()
+        // Every composition — including one created after boot already finished — re-derives once the
+        // process-scoped result is available.
+        container.bootReconciled.first { it }
+        if (container.session.value == null) {
+            val snap = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
+            // RE-CHECK AFTER THE SUSPEND (round-1 review, Kimi). The session was checked before
+            // `withContext`; a session published while we were off-main must not then be pulled to
+            // DeleteIncomplete by a decision taken for a tree that no longer has none. The Splash
+            // consumer already re-checks; this one did not — the asymmetry was the finding.
+            if (container.session.value != null) return@LaunchedEffect
+            vaultExists = snap.present && !snap.legacy
+            when (snap.route) {
+                BootRoute.DELETE_INCOMPLETE ->
+                    if (route != Route.DeleteIncomplete) route = Route.DeleteIncomplete
+                // Only ever moves a STALE Locked forward; never pulls a live tree back.
+                BootRoute.ONBOARDING -> if (route == Route.Locked) route = Route.Onboarding
+                BootRoute.LOCKED -> Unit
+            }
+        }
+    }
     // OBSERVED from the container's process-scoped flow (round 11, Gemini): a rotation
     // mid-create re-attaches the spinner to the still-running create, and a create that fails
     // after the rotation releases it here too (a seeded snapshot would strand the spinner).
@@ -671,24 +723,15 @@ private fun ZitroneRoot(
         BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
             BiometricManager.BIOMETRIC_SUCCESS
 
-    // 0.9.2 upgrade safety: a PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
-    // reservation, so route it to fresh onboarding instead of the lock screen. Computed ONCE, off-main
-    // (a ~1 MiB outer decrypt, no Argon2id), only at a cold start with no live session. Safety does not
-    // depend on this — open() throws LegacyImage before any slot interpretation, and onUnlockPassphrase
-    // below also routes LegacyImage to onboarding as a backstop — this just avoids showing a dead lock
-    // screen. Treat a legacy image as "no usable vault" (vaultExists=false) so onboarding proceeds; the
-    // create there retires the old image.
-    LaunchedEffect(Unit) {
-        if (vaultExists && container.session.value == null) {
-            val legacy = withContext(Dispatchers.IO) {
-                runCatching { container.isLegacyImage() }.getOrDefault(false)
-            }
-            if (legacy && (route == Route.Splash || route == Route.Locked)) {
-                vaultExists = false
-                route = Route.Onboarding
-            }
-        }
-    }
+    // (The standalone legacy-image routing effect that used to live here is REMOVED. It was a SECOND
+    // routing authority: it set Route.Onboarding on its own, without awaiting `bootReconciled`,
+    // without the carried `residueSweepHold`, and without consulting `serverDeleteConfirmed()` — so
+    // with a v2 image over a durable `vault.delete-confirmed` it could preempt Route.DeleteIncomplete,
+    // and the create() on that onboarding screen CLEARS both markers, erasing the SOLE authorisation
+    // for the account-delete auto-destroy. Legacy detection is now an INPUT to the single boot
+    // decision; see bootRoute's `legacyImage` arm, which orders it AFTER the confirmed marker and
+    // BEFORE image-present. `onUnlockPassphrase` still routes PassphraseOutcome.LegacyImage to
+    // onboarding as an unlock-time backstop.)
 
     var identityFingerprint by remember { mutableStateOf<String?>(null) }
     LaunchedEffect(session) {
@@ -728,14 +771,22 @@ private fun ZitroneRoot(
             } else if (unlocked) {
                 unlocked = false
                 identityFingerprint = null
-                vaultExists = container.hasVault()
-                route = when {
-                    // Only a CONFIRMED server delete routes to the auto-destroy path (round 13).
-                    // A session going null never carries a mere delete-intent (onNotConfirmed keeps
-                    // the session live), so intent-only handling lives in Splash, not here.
-                    container.serverDeleteConfirmed() -> Route.DeleteIncomplete
-                    vaultExists -> Route.Locked
-                    else -> Route.Onboarding
+                // THE SAME decision function and THE SAME inputs as the two boot consumers. A
+                // session going null is not a cold start, but "onboarding requires the carried
+                // verdict" is either an invariant everywhere or it is a habit — and an omitted
+                // argument is how a weaker consumer hides.
+                //
+                // Only a CONFIRMED server delete routes to the auto-destroy path. A session going
+                // null never carries a mere delete-intent (onNotConfirmed keeps the session live),
+                // so intent-only handling lives in the boot decision, not here.
+                // Same single derivation the two boot consumers use — see deriveBootDecision.
+                val snap = container.deriveBootDecisionFromDisk()
+                // A legacy image is present but NOT usable.
+                vaultExists = snap.present && !snap.legacy
+                route = when (snap.route) {
+                    BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
+                    BootRoute.ONBOARDING -> Route.Onboarding
+                    BootRoute.LOCKED -> Route.Locked
                 }
             }
         }
@@ -1174,23 +1225,11 @@ private fun ZitroneRoot(
         when (current) {
             // Vault-only routing (§0): image present → unlock gate, absent → setup. NO
             // silent auto-unlock.
-            Route.Splash -> SplashScreen(
-                onFinished = {
-                    route = when {
-                        // SERVER delete CONFIRMED (round 13): the account is provably gone, so
-                        // resume FINISHING the local destroy — never the unlock gate over a vault
-                        // whose account no longer exists (see Route.DeleteIncomplete).
-                        container.serverDeleteConfirmed() -> Route.DeleteIncomplete
-                        // A mere delete-INTENT (crash mid-delete, server outcome unknown) does NOT
-                        // authorise destruction and is NOT abandoned here (round 14, F1): the vault
-                        // is valid and the account may still exist. Route to normal unlock; the
-                        // post-unlock reconcile (see the intent LaunchedEffect) retries the
-                        // authenticated DELETE. Splash never clears intent and never auto-destroys.
-                        vaultExists -> Route.Locked
-                        else -> Route.Onboarding
-                    }
-                },
-            )
+            // Splash ONLY records that its animation ended. It must not route: boot reconciliation
+            // MUTATES what disk says (the orphan sweep unlinks residue), so a decision taken here
+            // could read a half-swept directory, or read the durability hold while it still held its
+            // default. The decision lives in the effect above, which waits for BOTH signals.
+            Route.Splash -> SplashScreen(onFinished = { splashFinished = true })
 
             Route.Onboarding -> OnboardingScreen(
                 onCreateVault = onCreateVault,
Now let me read the diff of 0d348b4 in full to see the round-1 delta, then the test files. Also I need to check `onBurn` in MainActivity vs main, and search for burn-related symbols.

• Now the round-1 delta itself:commit 0d348b4f7860536016ce53b9b09dc3e9341b69b6
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 12:36:27 2026 +0000

    Unit W-A round-1 fixes — one legacy authority, gate 2 covered, one derivation
    
    Four source-capable lenses on the extraction (codex gpt-5.6-sol, grok grok-4.5,
    gemini 3.1-pro, kimi k3), each in its own writable worktree. Every finding is
    mine, and EVERY ONE is an extraction defect invisible to the six rounds the
    parent unit already spent — which is exactly why the extraction got its own round.
    
    HIGH (Codex + Grok, converged) — main's standalone legacy-image LaunchedEffect
    SURVIVED the port. Unit W's round 3 deleted that effect ON THE FEATURE BRANCH;
    W-A was cut from MAIN, which predates that fix, so the extraction reintroduced a
    SECOND legacy routing authority: it set Route.Onboarding on its own without
    awaiting bootReconciled, without the carried residueSweepHold, and without
    consulting serverDeleteConfirmed(). With {v2 image + durable delete-confirmed} it
    preempts Route.DeleteIncomplete, and create() on that screen clears both markers,
    erasing the sole authorisation for the account-delete auto-destroy.
    
    ADJUDICATED AGAINST A DISSENT: Kimi rated it INFO, arguing {legacy + confirmed}
    is unreachable because a legacy image cannot be unlocked under 0.9.2, so no
    session, so no confirmed delete. That misses the upgrade path — the marker is
    written under 0.9.1, BEFORE the image became "legacy". A 0.9.1 install that
    confirms a delete, crashes mid-unlink, then upgrades lands exactly there. Two
    lenses right, one wrong, resolved on a concrete reachability path.
    Effect deleted; legacy is an input to the single decision.
    
    MEDIUM (Grok + Gemini, converged) — the row-7 confirmed-refuse test had been
    DELETED. Rewriting row 6b for W-A sliced out the adjacent test, so gate 2 — the
    ownership bar for an in-flight account deletion — had ZERO coverage while the
    suite header still claimed it walked the table "row by row". Removing gate 2
    entirely would not have failed the suite. Restored and mutation-verified:
    deleting gate 2 now fails exactly that test.
    
    MEDIUM (Gemini) — the five bootRoute inputs, including the ~1 MiB isLegacyImage
    decrypt and its skip conditions, were copy-pasted across all three consumers.
    Three copies of a safety derivation drift silently. Now one owner:
    deriveBootDecision, called by all three via deriveBootDecisionFromDisk.
    
    LOW (Kimi) — the post-boot re-derive applied DELETE_INCOMPLETE without
    re-checking the session after its IO suspend, while the Splash consumer does.
    The asymmetry was the finding; re-check added.
    
    LOW (Gemini) — `onboarding is reachable…` computed its expectation with a formula
    mirroring the implementation. It does catch mutations, but a developer can make
    it pass by copying a flawed rule into it. Expectations are now enumerated.
    
    LOW (Codex) — a test named "cancellation after a durable sweep" performed no
    cancellation. Worse, that window does not exist in this shape: publish runs in a
    `finally` with no suspension between verdict and publication. Renamed to what it
    proves; the reachable cancellation case is covered by the stranding test.
    
    INFO (Grok) — two suites still carried "PUCKER BURN Unit W" headers naming a unit
    that no longer exists here.
    INFO (Kimi) — SECURITY_MODEL overreached twice: "every path requires proven
    absence" (the legacy arm presents onboarding with an image present, by design),
    and "a refused gate holds the lock screen" (a refused gate returns NO_MUTATION
    and holds nothing). Both corrected to match the mechanism.
    
    MY OWN CLOSE-OUT CHECK CAUGHT A SILENT COVERAGE LOSS while fixing these: the test
    count came back 475 when it should have been 476. My header rewrite had spanned
    from the doc comment to the `fun`, dropping the @Test annotation in between — the
    test still compiled, still 
existed, and silently stopped running. Same class as
    the row-7 deletion, committed while fixing the row-7 deletion. Restored.
    
    Two lenses independently re-ran the suite and reproduced the figures (Grok, and
    Kimi parsing the JUnit XMLs itself) — the disposable-worktree rule working, after
    a read-only sandbox blocked exactly this last round.
    
    Tests: 476 total (+1), 0 failures, 473 passed, 3 skipped (I2P, pre-existing).
    No version bump.

diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 9603378..5548582 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -643,28 +643,7 @@ private fun ZitroneRoot(
     LaunchedEffect(splashFinished, bootDone) {
         if (!splashFinished || !bootDone) return@LaunchedEffect
         if (route != Route.Splash) return@LaunchedEffect
-        val decided = withContext(Dispatchers.IO) {
-            val confirmed = container.serverDeleteConfirmed()
-            val present = container.hasVault()
-            // Computed only when it can matter — a ~1 MiB outer decrypt, so never on a
-            // confirmed-delete or an absent image, and never on the main thread.
-            val legacy = if (present && !confirmed) {
-                runCatching { container.isLegacyImage() }.getOrDefault(false)
-            } else {
-                false
-            }
-            BootDecision(
-                present = present,
-                legacy = legacy,
-                route = bootRoute(
-                    serverDeleteConfirmed = confirmed,
-                    vaultImagePresent = present,
-                    residueSweepHold = container.residueSweepHold.value,
-                    vaultProvenAbsent = container.vaultProvenAbsent(),
-                    legacyImage = legacy,
-                ),
-            )
-        }
+        val decided = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
         // RE-CHECK AFTER THE SUSPEND: the guard above ran before `withContext`, and a decision taken
         // for a tree that has since left Splash must not be applied to it.
         if (route != Route.Splash) return@LaunchedEffect
@@ -685,26 +664,12 @@ private fun ZitroneRoot(
         // process-scoped result is available.
         container.bootReconciled.first { it }
         if (container.session.value == null) {
-            val snap = withContext(Dispatchers.IO) {
-                val c = container.serverDeleteConfirmed()
-                val p = container.hasVault()
-                val l = if (p && !c) {
-                    runCatching { container.isLegacyImage() }.getOrDefault(false)
-                } else {
-                    false
-                }
-                BootDecision(
-                    present = p,
-                    legacy = l,
-                    route = bootRoute(
-                        serverDeleteConfirmed = c,
-                        vaultImagePresent = p,
-                        residueSweepHold = container.residueSweepHold.value,
-                        vaultProvenAbsent = container.vaultProvenAbsent(),
-                        legacyImage = l,
-                    ),
-                )
-            }
+            val snap = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
+            // RE-CHECK AFTER THE SUSPEND (round-1 review, Kimi). The session was checked before
+            // `withContext`; a session published while we were off-main must not then be pulled to
+            // DeleteIncomplete by a decision taken for a tree that no longer has none. The Splash
+            // consumer already re-checks; this one did not — the asymmetry was the finding.
+            if (container.session.value != null) return@LaunchedEffect
             vaultExists = snap.present && !snap.legacy
             when (snap.route) {
                 BootRoute.DELETE_INCOMPLETE ->
@@ -758,24 +723,15 @@ pr
ivate fun ZitroneRoot(
         BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
             BiometricManager.BIOMETRIC_SUCCESS
 
-    // 0.9.2 upgrade safety: a PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
-    // reservation, so route it to fresh onboarding instead of the lock screen. Computed ONCE, off-main
-    // (a ~1 MiB outer decrypt, no Argon2id), only at a cold start with no live session. Safety does not
-    // depend on this — open() throws LegacyImage before any slot interpretation, and onUnlockPassphrase
-    // below also routes LegacyImage to onboarding as a backstop — this just avoids showing a dead lock
-    // screen. Treat a legacy image as "no usable vault" (vaultExists=false) so onboarding proceeds; the
-    // create there retires the old image.
-    LaunchedEffect(Unit) {
-        if (vaultExists && container.session.value == null) {
-            val legacy = withContext(Dispatchers.IO) {
-                runCatching { container.isLegacyImage() }.getOrDefault(false)
-            }
-            if (legacy && (route == Route.Splash || route == Route.Locked)) {
-                vaultExists = false
-                route = Route.Onboarding
-            }
-        }
-    }
+    // (The standalone legacy-image routing effect that used to live here is REMOVED. It was a SECOND
+    // routing authority: it set Route.Onboarding on its own, without awaiting `bootReconciled`,
+    // without the carried `residueSweepHold`, and without consulting `serverDeleteConfirmed()` — so
+    // with a v2 image over a durable `vault.delete-confirmed` it could preempt Route.DeleteIncomplete,
+    // and the create() on that onboarding screen CLEARS both markers, erasing the SOLE authorisation
+    // for the account-delete auto-destroy. Legacy detection is now an INPUT to the single boot
+    // decision; see bootRoute's `legacyImage` arm, which orders it AFTER the confirmed marker and
+    // BEFORE image-present. `onUnlockPassphrase` still routes PassphraseOutcome.LegacyImage to
+    // onboarding as an unlock-time backstop.)
 
     var identityFingerprint by remember { mutableStateOf<String?>(null) }
     LaunchedEffect(session) {
@@ -823,23 +779,11 @@ private fun ZitroneRoot(
                 // Only a CONFIRMED server delete routes to the auto-destroy path. A session going
                 // null never carries a mere delete-intent (onNotConfirmed keeps the session live),
                 // so intent-only handling lives in the boot decision, not here.
-                val imagePresent = container.hasVault()
-                val legacyNow = if (imagePresent && !container.serverDeleteConfirmed()) {
-                    runCatching { container.isLegacyImage() }.getOrDefault(false)
-                } else {
-                    false
-                }
-                // A legacy image is present but NOT usable — same derivation the boot consumers use.
-                vaultExists = imagePresent && !legacyNow
-                route = when (
-                    bootRoute(
-                        serverDeleteConfirmed = container.serverDeleteConfirmed(),
-                        vaultImagePresent = imagePresent,
-                        residueSweepHold = container.residueSweepHold.value,
-                        vaultProvenAbsent = container.vaultProvenAbsent(),
-                        legacyImage = legacyNow,
-                    )
-                ) {
+                // Same single derivation the two boot consumers use — see deriveBootDecision.
+                val snap = container.deriveBootDecisionFromDisk()
+                // A legacy image is present but NOT usable.
+                vaultExists = snap.present && !snap.legacy
+                route = when (snap.route) {
                     BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
                     BootRoute.ONBOARDING -> Route.Onboarding
                     BootRoute.LOCKED -> Route.Locked
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/
main/java/com/zitrone/app/ZitroneApp.kt
index ac16e0d..cb6b87e 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -234,6 +234,18 @@ class AppContainer(private val app: Application) {
      */
     fun vaultProvenAbsent(): Boolean = imageStore.obliterationComplete()
 
+    /**
+     * Read the four disk facts and produce ONE boot decision — the single derivation every routing
+     * consumer uses. Call OFF the main thread: the legacy probe reads and decrypts the outer layer.
+     */
+    internal fun deriveBootDecisionFromDisk(): BootDecision = deriveBootDecision(
+        serverDeleteConfirmed = serverDeleteConfirmed(),
+        imagePresent = hasVault(),
+        residueSweepHold = residueSweepHold.value,
+        vaultProvenAbsent = vaultProvenAbsent(),
+        isLegacyImage = { isLegacyImage() },
+    )
+
     /** Cold-start orphan sweep — see [VaultImageStore.sweepOrphanedResidue]. */
     fun sweepOrphanedVaultResidue(): ResidueSweepResult = imageStore.sweepOrphanedResidue()
 
@@ -1149,6 +1161,46 @@ internal fun runBootReconcile(
     }
 }
 
+/**
+ * Derive a boot decision from disk in ONE place. All three consumers (the Splash decision, the
+ * post-boot re-derive, and the session collector) call this rather than each assembling the five
+ * `bootRoute` inputs themselves.
+ *
+ * Round-1 review (Gemini): the derivation — including the ~1 MiB `isLegacyImage()` decrypt and its
+ * skip conditions — was copy-pasted across all three call sites. Three copies of a safety derivation
+ * drift silently: change one and the others keep the old rule, with no test able to catch the
+ * divergence. One owner, one derivation. This is also why the legacy probe's cost and its
+ * "only when it can matter" guard live here rather than being restated three times.
+ *
+ * MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer.
+ */
+internal fun deriveBootDecision(
+    serverDeleteConfirmed: Boolean,
+    imagePresent: Boolean,
+    residueSweepHold: Boolean,
+    vaultProvenAbsent: Boolean,
+    isLegacyImage: () -> Boolean,
+): BootDecision {
+    // Computed only when it can matter: never over a confirmed delete (that state is owned elsewhere)
+    // and never with no image to inspect.
+    val legacy = if (imagePresent && !serverDeleteConfirmed) {
+        runCatching { isLegacyImage() }.getOrDefault(false)
+    } else {
+        false
+    }
+    return BootDecision(
+        present = imagePresent,
+        legacy = legacy,
+        route = bootRoute(
+            serverDeleteConfirmed = serverDeleteConfirmed,
+            vaultImagePresent = imagePresent,
+            residueSweepHold = residueSweepHold,
+            vaultProvenAbsent = vaultProvenAbsent,
+            legacyImage = legacy,
+        ),
+    )
+}
+
 /** Where a composition must route out of Splash on a cold start — see [bootRoute]. */
 internal enum class BootRoute { DELETE_INCOMPLETE, LOCKED, ONBOARDING }
 


• Now let me check several things in parallel:

  1. A: search for burn-related symbols across the repo (burnVault, obliterateForBurn, wipeBiometricMaterial, wipeAppLocalStateForBurn, BurnCompletion, postBurnRoute, signalBurnCompleted, tryApplyBurnCompletion).
  2. A: verify onBurn in MainActivity vs main.
  3. C: completeInterruptedBurn, reconcileOrphanedBurnMarkers references.
  4. Read the session collector context in MainActivity (the third consumer) — check it's on main thread? The session collector calls `container.deriveBootDecisionFromDisk()` directly in a collect — is that on the main thread? In the diff, the session collector (LaunchedEffect(session)?) — line ~771-790 in new file. It calls `container.deriveBootDecisionFromDisk()` WITHOUT withContext(Dispatchers.IO)! Look at the diff:

  ```
  } else if (unlocked) {
      unlocked = false
      ...
      val snap = container.deriveBootDecisionFromDisk()
  ```

  No `withContext(Dispatchers.IO)`! The doc says "MUST be called off the main thread — isLegacyImage() reads and decrypts the outer layer." The session collector runs in a LaunchedEffect on main. That's a potential finding: ~1 MiB decrypt on the main thread. But wait — is that collector inside a withContext already? Need to read the actual code. In the pre-fix version, the same block called container.hasVault(), isLegacyImage() etc. directly too — also without withContext. So this predates; but still, the doc now says "MUST be called off the main thread" and one of three consumers calls it on main. Let me read the actual context.

  Also check: the session collector — does it await bootReconciled before deriving? In the diff, the session-gone-null branch derives without checking bootReconciled. On a session going null mid-process, boot reconcile has long finished (boot happens before any session can exist... actually a session requires unlock which requires boot routing done). But bootReconciled is process-scoped and set at cold start before any unlock could happen. Could a session go null before bootReconciled? Session can only exist after unlock, which happens after routing, which awaits bootReconciled. Edge: process restoration? Fine.

  But wait — the collector reads `residueSweepHold` via deriveBootDecisionFromDisk — that's fine.

  Also check `unlocked` variable and the collector code in full.

  5. G: runBootReconcile contract — read tests.
  6. The third consumer ordering: in the session collector, `route = when (snap.route)` — it maps ONBOARDING to Route.Onboarding unconditionally? In diff: `BootRoute.ONBOARDING -> Route.Onboarding` — in the session-null collector. Since session just went null, tree is at some route; mapping is probably fine.

  Let me now read the relevant MainActivity sections in full and grep for burn symbols.

• Now checking burn-mechanism absence, the `onBurn` stub vs main, and the excluded healers:837:    val onBurn: () -> Unit = {
856:                        PassphraseOutcome.Burn -> onBurn()
1397:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
===
786:    val onBurn: () -> Unit = {
805:                        PassphraseOutcome.Burn -> onBurn()
1358:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },


    // Passphrase unlock (§2): ALWAYS available. Enforce the RAM backoff BEFORE the off-main
    // attempt, then surface only a uniform generic failure (no per-slot / per-factor branch) —
    // EXCEPT a damaged image, which escalates distinctly (it is not a passphrase guess).
    // Pucker Burn (slot 0) match handler. FAIL-CLOSED STUB (0.9.2 PR-2): the duress WIPE is a sibling
    // Pucker Burn PR and slot 0 is unarmed until burn-setup ships, so Burn is currently UNREACHABLE — and
    // until the wipe lands, a burn match is surfaced exactly like a wrong passphrase (uniform failure), a
    // deniable no-op. When the burn-wipe PR lands, this becomes the wipe trigger.
    val onBurn: () -> Unit = {
        lockError = VaultUnlockRouter.UNIFORM_FAILURE
        unlocking = false
    }

    val onUnlockPassphrase: (String) -> Unit = onUnlockPassphrase@{ pass ->
        if (unlocking) return@onUnlockPassphrase
        unlocking = true
        lockError = null
        scope.launch {
            val backoff = container.unlockRouter.backoffDelayMs()
            if (backoff > 0) delay(backoff)
            runCatching { container.attemptPassphrase(pass) }.fold(
                onSuccess = { outcome ->
                    // The router (attemptPassphrase) owns the triple-entry ritual + the backoff counter;
                    // this only maps the outcome to UI. Unlocked/Created publish a session → the session
                    // collector flips route/unlocking. Rejected is indistinguishable from a wrong password.
                    when (outcome) {
                        PassphraseOutcome.Unlocked, PassphraseOutcome.Created -> onUnlockSuccess()
                        PassphraseOutcome.Burn -> onBurn()
                        PassphraseOutcome.LegacyImage -> {
                            // A PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
                            // reservation; the store threw before any slot was interpreted (never a burn
                            // wipe). Route to fresh onboarding (the create there retires the old image).
                            vaultExists = false
                            route = Route.Onboarding
                            unlocking = false
                        }
                        PassphraseOutcome.ImageUnreadable -> {
                            // A damaged/unreadable IMAGE is device state, NOT a passphrase guess — a
                            // distinct honest error, never the wrong-passphrase uniform failure.
                            lockError = VaultUnlockRouter.IMAGE_UNREADABLE_NOTE
                            unlocking = false
                        }
=====MAIN=====
    // attempt, then surface only a uniform generic failure (no per-slot / per-factor branch) —
    // EXCEPT a damaged image, which escalates distinctly (it is not a passphrase guess).
    // Pucker Burn (slot 0) match handler. FAIL-CLOSED STUB (0.9.2 PR-2): the duress WIPE is a sibling
    // Pucker Burn PR and slot 0 is unarmed until burn-setup ships, so Burn is currently UNREACHABLE — and
    // until the wipe lands, a burn match is surfaced exactly like a wrong passphrase (uniform failure), a
    // deniable no-op. When the burn-wipe PR lands, this becomes the wipe trigger.
    val onBurn: () -> Unit = {
        lockError = VaultUnlockRouter.UNIFORM_FAILURE
        unlocking = false
    }

    val onUnlockPassphrase: (String) -> Unit = onUnlockPassphrase@{ pass ->
        if (unlocking) return@onUnlockPassphrase
        unlocking = true
        lockError = null
        scope.launch {
            val backoff = container.unlockRouter.backoffDelayMs()
            if (backoff > 0) delay(backoff)
            runCatching { container.attemptPassphrase(pass) }.fold(
                onSuccess = { outcome ->
                    // The router (attemptPassphrase) owns the triple-entry ritual + the backoff counter;
                    // this only maps the outcome to UI. Unlocked/Created publish a session → the session
                    // collector flips route/unlocking. Rejected is indistinguishable from a wrong password.
                    when (outcome) {
                        PassphraseOutcome.Unlocked, PassphraseOutcome.Created -> onUnlockSuccess()
                        PassphraseOutcome.Burn -> onBurn()
                        PassphraseOutcome.LegacyImage -> {
                            // A PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
                            // reservation; the store threw before any slot was interpreted (never a burn
                            // wipe). Route to fresh onboarding (the create there retires the old image).
                            vaultExists = false
                            route = Route.Onboarding
                            unlocking = false
                        }
                        PassphraseOutcome.ImageUnreadable -> {
                            // A damaged/unreadable IMAGE is device state, NOT a passphrase guess — a
                            // distinct honest error, never the wrong-passphrase uniform failure.
                            lockError = VaultUnlockRouter.IMAGE_UNREADABLE_NOTE
                            unlocking = false
                        }
                        PassphraseOutcome.Rejected, PassphraseOutcome.Retry -> {
• `onBurn` is identical to main (verbatim). Good — A and B confirmed so far. No burn symbols anywhere in apps/android. Let me also grep the whole repo for burn symbols to be thorough (packages, server). Also check `obliterat` and `burn` more broadly in the android sources.apps/android/app/src/main/java/com/zitrone/app/VaultUnlockRouter.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultState.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/LemonDropCreate.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/AttachmentCrypto.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/LemonDropOneShot.kt
apps/android/app/src/main/java/com/zitrone/app/ui/theme/Theme.kt
apps/android/app/src/main/java/com/zitrone/app/ui/theme/Color.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/BurnParticles.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/MessageBubble.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/QrDropDialogs.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/BurnTimer.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/ConversationList.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/LemonSliceMath.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/ComposeBar.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/LemonSlice.kt
apps/android/app/src/main/java/com/zitrone/app/ui/components/SecurityBadge.kt
apps/android/app/src/main/java/com/zitrone/app/ui/screens/LemonDropMessageScreen.kt
apps/android/app/src/main/java/com/zitrone/app/ui/screens/ChatScreen.kt
apps/android/app/src/main/java/com/zitrone/app/ui/screens/OnboardingScreen.kt
apps/android/app/src/main/java/com/zitrone/app/ui/screens/ChatListScreen.kt
apps/android/app/src/main/java/com/zitrone/app/ui/screens/SettingsScreen.kt
apps/android/app/src/main/java/com/zitrone/app/notifications/NotificationScheduler.kt
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
apps/android/app/src/main/java/com/zitrone/app/LemonDropVeilController.kt
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt
apps/android/app/src/main/java/com/zitrone/app/net/WsClient.kt
apps/android/app/src/main/java/com/zitrone/app/net/ApiClient.kt
apps/android/app/src/main/java/com/zitrone/app/data/DeviceSettings.kt
apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt
apps/android/app/src/main/java/com/zitrone/app/data/ControlPayload.kt
apps/android/app/src/main/java/com/zitrone/app/data/VaultScopedSettings.kt
apps/android/app/src/main/java/com/zitrone/app/data/LemonDropCreator.kt
apps/android/app/src/main/java/com/zitrone/app/data/Models.kt
apps/android/app/src/main/java/com/zitrone/app/data/LemonDropVeil.kt
apps/android/app/src/main/java/com/zitrone/app/data/MessageEnvelope.kt
apps/android/app/src/main/java/com/zitrone/app/data/ConversationRepository.kt
apps/android/app/src/main/java/com/zitrone/app/data/LemonDropRedeemer.kt
apps/android/app/src/main/java/com/zitrone/app/data/MessageRepository.kt
===
229:    // (rotation) recreates the Activity within the same authenticated session,
257:        // AwaitUnlock is reachable only over a live session (its probe ran on
258:        // one). If a forced logout tore the session down between that unlock and
262:        val redeemer = container.session.value?.lemonDropRedeemer ?: return
467:     * Biometric-ENABLE over the LIVE session (spec §1) — used by BOTH the onboarding enable offer
468:     * (shown AFTER the session is published) and the Settings toggle, so no live VaultOpen is ever
477:        // an A- and a B-session), so it is NOT a slot oracle and keeps the never-repoint-while-exists
480:        // keeps the per-slot never-repoint belt for a session that changed mid-flight. NOTE (0.9.2
511:                val session = container.session.value
512:                val ok = session != null &&
513:                    runCatching { container.enableBiometricFromSession(authenticatedCipher, session, aliasId) }.getOrDefault(false)
533: * ORDER IS LOAD-BEARING. [finishUi] runs FIRST: it tears the session down, and that teardown runs
605:    // session-derived flow moved into [SessionUi], composed only when the session
612:    val session by container.session.collectAsState()
617:    // On an Activity recreation (rotation) the process-scoped session survives, but this `remember`
618:    // re-runs from scratch: a LIVE session must route straight to the chat list, never back through
619:    // Splash → Locked (which keys on hasVault() and would fake-lock a live, unlocked session and
620:    // demand a redundant re-auth on every rotation). A genuine cold start (no session) still lands
622:    // stops hiding an already-live session behind a redundant gate.
624:        mutableStateOf<Route>(if (container.session.value != null) Route.ChatList else Route.Splash)
626:    var unlocked by remember { mutableStateOf(container.session.value != null) }
666:        if (container.session.value == null) {
668:            // RE-CHECK AFTER THE SUSPEND (round-1 review, Kimi). The session was checked before
669:            // `withContext`; a session published while we were off-main must not then be pulled to
672:            if (container.session.value != null) return@LaunchedEffect
710:    // The biometric-enable OFFER is shown over the LIVE session (post-publish), so it holds NO
737:    LaunchedEffect(session) {
738:        val live = session
749:    // Route reconciliation with the PROCESS-scoped session (round 11, Codex). The route vars
753:    // unlock/create seeds Locked/Onboarding, the surviving worker publishes the session, and no
754:    // live coroutine ever routes to ChatList (every further unlock is refused — a session is
756:    // delete then nulls the session, and the replacement composes blank. This collector — one
763:        container.session.collect { live ->
775:                // session going null is not a cold start, but "onboarding requires the carried
779:                // Only a CONFIRMED server delete routes to the auto-destroy path. A session going
780:                // null never carries a mere delete-intent (onNotConfirmed keeps the session live),
795:    // Session lifecycle — tied to the session INSTANCE, not the route, so it runs
798:    session?.let { live ->
824:        // now-live session — making BIOMETRIC_REENROLL_NOTE's "after a passphrase unlock" promise
852:                    // this only maps the outcome to UI. Unlocked/Created publish a session → the session
The "burn" hits are mostly message-burn (burnAll, BurnParticles, burn timers — the message self-destruct feature, unrelated to duress wipe). The duress-wipe symbols are absent. Good.

  Note: VaultUnlockRouter has PassphraseOutcome.Burn — that's pre-existing on main (burn-slot reservation from PR-1/PR-2, main already has it). Verify: `git show main:...VaultUnlockRouter.kt | grep Burn` — but the grep -l above shows these files contain "burn" — these exist on main too presumably. The unit diff only touched 6 files; burn references in other files are from main. Fine.

  Now read the session collector region in the current MainActivity fully (lines ~737-800) and the boot effects region (lines 605-740).Important observation at line 783: the session collector calls `container.deriveBootDecisionFromDisk()` **directly on the main thread** (inside `container.session.collect { }` in a `LaunchedEffect` — main dispatcher). The kdoc on `deriveBootDecisionFromDisk` says "Call OFF the main thread: the legacy probe reads and decrypts the outer layer." and `deriveBootDecision` says "MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer."

  This collector does a ~1 MiB decrypt on the main thread when a session goes null (e.g., after account delete or forced logout). That's a UI jank / potential ANR issue, and directly violates the function's own stated contract. Pre-round-1, this call site did the same thing (called hasVault/isLegacyImage directly on main), so it's not a regression introduced by 0d348b4 — but the delta's claim "all three consumers now call the single derivation" makes this more visible. Severity: LOW/MEDIUM (main-thread IO; ANR risk, no correctness failure). Actually wait — is there any correctness issue? StrictMode on Android could even crash in debug. But mainly jank. I'd call it LOW (pre-existing pattern, but now contradicts the new "MUST be called off the main thread" contract the fix itself wrote).

  Also check: does this collector await bootReconciled? No. But a session can only exist after an unlock, which happens after boot routing completed, which requires bootReconciled = true. Actually can a session exist before bootReconciled? `attemptPassphrase` → creates session — that requires user interaction on Locked screen, which requires routing, which requires bootDone. Process death/restart: session is process-scoped, so no. So fine.

  But wait: `residueSweepHold` — if hold is true (non-durable sweep), and a session later goes null (user locks?), the collector derives with hold=true → LOCKED when image present, fine; if image absent and hold true → LOCKED (holds onboarding) — correct fail-closed.

  Now — one more consumer question: the second effect (line 658) runs `container.startBootReconcile()` then awaits. On rotation, new composition: startBootReconcile no-ops, awaits bootReconciled (already true), re-derives. Fine.

  Consumer 1 (Splash decision): keyed on splashFinished && bootDone, route == Splash. Uses carried hold via residueSweepHold.value read inside deriveBootDecisionFromDisk on IO thread — hold is published before bootReconciled=true in publish(). Fine.

  Ordering: publish sets residueSweepHold.value = hold THEN bootReconciled.value = true. Consumers await bootReconciled then read hold. Correct.

  Now check `ZitroneApp.kt` around the container scope, `serverDeleteConfirmed`, `isLegacyImage`, and check the sweep call passes through. Also check VaultImageStore: create() DEK-first ordering, destroy() confirmed-marker-first ordering, retireLegacyImage order, open() deleting leftover temps, dirSync, deleteLeftoverTmp. And serverDeletedFile naming. Let me read relevant parts of VaultImageStore.

• Key observation noted at `MainActivity.kt:783` (main-thread call). Now the VaultImageStore internals — create/destroy ordering, temps, dirSync:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:286:    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:287:    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:311:    fun isLegacyImage(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:339:    fun open() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:347:                deleteLeftoverTmp(binFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:348:                deleteLeftoverTmp(dekFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:483:    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:506:                    Files.notExists(deleteIntentFile.toPath()) &&
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:507:                        Files.notExists(serverDeletedFile.toPath())
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:539:                        renameIntoPlace(dekFile, wrappedDek)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:546:                        renameIntoPlace(binFile, outer)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:761:                            Files.notExists(deleteIntentFile.toPath()) &&
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:762:                                Files.notExists(serverDeletedFile.toPath())
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:929:    fun retireLegacyImage() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:942:            deleteLeftoverTmp(binFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:943:            deleteLeftoverTmp(dekFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1033:        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1037:        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1052:            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1053:            deleteIntentFile.delete()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1054:            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1068:        deleteIntentFile.delete()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1069:        serverDeletedFile.delete()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1077:            Files.notExists(deleteIntentFile.toPath()) &&
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1078:            Files.notExists(serverDeletedFile.toPath())
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1092:    fun destroy() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1108:            writeDurableMarker(serverDeletedFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1113:            deleteLeftoverTmp(binFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1114:            deleteLeftoverTmp(dekFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1120:            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1159:    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1168:    fun deleteIntentPending(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1169:        imageLock.withLock { deleteIntentFile.exists() && !serverDeletedFile.exists() }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1176:     * idempotent 404. Deliberately NOT `&& !confirmed` (unlike [deleteIntentPending]): a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1192:        imageLock.withLock { !Files.notExists(deleteIntentFile.toPath()) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1240:    private fun renameIntoPlace(target: File, bytes: ByteArray) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1276:     * Durable atomic write of a SINGLE file: [renameIntoPlace] then a directory fsync so the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1280:     * [renameIntoPlace], with best-effort `.tmp` cleanup) — the target (previous durable file)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1289:        renameIntoPlace(target, bytes)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1300:     * The temps are load-bearing, not incidental: [renameIntoPlace] stages the COMPLETE outer image in
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1351:     *                                            renameIntoPlace(dekFile)      complete key for a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1400:            if (!Files.notExists(serverDeletedFile.toPath())) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1414:                deleteLeftoverTmp(dekFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1415:                deleteLeftoverTmp(binFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1430:    private fun deleteLeftoverTmp(target: File) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1440:         * destruction — see [markDeleteIntent] / [deleteIntentPending]. Existence is the only signal.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1442:        const val DELETE_INTENT_FILE = "vault.delete-intent"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1447:         * [markServerDeleteConfirmed] / [serverDeleteConfirmed]. Existence is the only signal.
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:242:        serverDeleteConfirmed = serverDeleteConfirmed(),
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:307:    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:311:     * vault destroy is owed ([VaultImageStore.serverDeleteConfirmed]). The only valid route is
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:316:    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:322:     * [VaultImageStore.deleteIntentPending].
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:324:    fun vaultDeleteIntentPending(): Boolean = imageStore.deleteIntentPending()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:453:    suspend fun createVaultAndPublish(passphrase: String): Boolean = withContext(Dispatchers.Default) {
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:712:    fun destroyVaultForAccountDeletion() {
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1178:    serverDeleteConfirmed: Boolean,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1186:    val legacy = if (imagePresent && !serverDeleteConfirmed) {
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1195:            serverDeleteConfirmed = serverDeleteConfirmed,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1240:    serverDeleteConfirmed: Boolean,


• create() confirmed: DEK-first barrier, clears markers first. Now destroy() and retireLegacyImage() and open():Confirmed:
  - `create()`: clears both markers durably first, then DEK-first durability barrier, then bin. So `{dek, no bin}` orphan reachable via interrupted create. ✓
  - `retireLegacyImage()`: unlinks bin THEN dek → interrupted leaves {dek, no bin}. ✓ (Note: the sweep's table row 1b says "unlinks bin THEN dek" — verified at lines 940-941.)
  - `destroy()`: writes confirmed marker durably BEFORE unlinks → gate 2 catches every real D2c unlink. ✓ Also no delete-intent gate needed: intent written while image present (markDeleteIntent before server request); create clears both markers. What about {delete-intent present, bin absent, dek present}? Could that arise legitimately? Intent is written before server delete; the vault is fully present at that point. For bin to be absent with intent present and confirmed absent... destroy() writes confirmed before unlinking, so any state with bin unlinked by destroy has confirmed. retireLegacyImage doesn't touch markers — but retireLegacyImage only runs on v2 images; could a v2 image have a delete-intent? Under 0.9.1 install, was there a delete-intent mechanism? The two-phase markers are "round 13" of the parent unit, i.e. 0.9.2. 0.9.1 was fresh-install-only (per kdoc line 925: "a retired v2 image has no server account this device is responsible for deleting (0.9.1 was fresh-install-only)"). Hmm, but the round-1 commit message's adjudication said "A 0.9.1 install that confirms a delete, crashes mid-unlink, then upgrades lands exactly there" — implying 0.9.1 DID have delete markers? That contradicts "0.9.1 was fresh-install-only". Actually wait, that adjudication is about {v2 image + durable delete-confirmed}: the marker written under 0.9.1 before the image became legacy. But if 0.9.1 was fresh-install-only with no account delete... Hmm, actually "fresh-install-only" might mean 0.9.1 only supported creating fresh vaults (no second vault), not that it lacked account deletion. The main branch already has the two-phase markers and Route.DeleteIncomplete (that's all pre-existing main code — the diff shows these exist in main). So main = 0.9.2 pre-W-A. The upgrade path: 0.9.1 → v2 image. Did 0.9.1 have delete-confirmed markers? The marker constants exist on main... I can't easily verify 0.9.1 behavior from this checkout (main is 0.9.2). Not critical for my verdicts — the HIGH fix (removing second legacy authority) is correct regardless: the single bootRoute orders confirmed-marker before legacy, which is strictly safer.

  - The "no delete-intent gate" reasoning: destroy() writes confirmed before unlinking ✓; create() clears both markers durably before writing DEK ✓. What about {intent present, no bin, dek/temp present}? Sources of {no bin, dek present}: interrupted create (create clears markers first, so no intent), interrupted retireLegacyImage (v2 image; could a v2 image have a delete-intent from 0.9.1? If 0.9.1 had single-marker delete... unknown, but intent-only never authorizes destruction anyway — the sweep deleting residue under an intent-only state: is that harmful? The intent means "delete initiated, outcome unknown". If the image is gone but a dek remains and intent exists... deleting the dek residue doesn't destroy anything recoverable (no image). The post-unlock reconcile retries the DELETE on next session — but there's no vault to unlock. Hmm, edge: {intent, no bin, dek present} → sweep deletes dek → onboarding → create clears markers. The account on the server may still exist and the intent to delete it is lost. But wait — without the sweep, main would also route onboarding (hasVault false) and create() clears markers anyway. So the sweep doesn't make this worse; and is this state even reachable? Intent is written while image present; then server delete; only on confirmed does destroy unlink. The image can't become absent without destroy (confirmed) or retireLegacyImage (v2 only, and retire checks version via reading the image). Could an interrupted destroy leave intent without confirmed? No — destroy writes confirmed FIRST, required-durable, aborts otherwise. So {intent, no bin} requires another writer of bin-absence: interrupted create (clears markers first) or retireLegacyImage (requires reading a v2 image present — but then intent+v2: v2 from 0.9.1; if 0.9.1 wrote an intent-like marker... the marker filename would have to match). Reasoning holds: no delete-intent gate is sound. ✓ (E)

  Now the sweep itself: gates look right. One check — gate ordering: gate 1 (bin proven absent), gate 2 (confirmed marker absent-or-refuse), then clean-check, then mutation: deletes dekFile, dek tmp, bin tmp. Re-stat all four; dirSync; SWEPT_DURABLE. Catch Throwable → SWEPT_NOT_DURABLE. 

  Note: sweep deletes dek/tmp files but NOT the delete-intent marker (by design). Fine.

  Subtle: sweep runs under imageLock. `obliterationComplete()` also under imageLock. `dirSync(baseDir)` — check its implementation. Also `deleteLeftoverTmp`. And check `serverDeletedFile` etc.

  One more check on the mutation point: `dekFile.delete()` — File.delete() on a nonexistent file returns false, no throw — fine. If dekFile.delete() returns false due to IO error (file survives), then imageBearingFilesProvenAbsent() re-stat → false → SWEPT_NOT_DURABLE. Good fail-closed.

  What if `imageBearingFilesProvenAbsent()` after deletes returns true but a file was actually resurrected... no, re-stat proves current namespace; dirSync proves durability. OK.

  Now potential missed writer for the table (E: "hunt the MISSING ROW"): writers of dek/tmp without bin:
  1. interrupted create ✓ (row 1)
  2. interrupted retireLegacyImage ✓ (row 1b)
  3. crash inside renameIntoPlace(dekFile) → dek.tmp ✓ (row 2)
  4. crash between DEK barrier and bin rename → {dek, bin.tmp} ✓ (row 3)
  5. crash inside renameIntoPlace(binFile) → {dek, bin.tmp} too (bin.tmp may be partial or complete, never renamed). Same row 3 shape.
  6. What about destroy()? destroy unlinks everything and writes confirmed marker → gate 2 ✓ (row 7).
  7. What about `open()`? open() deletes leftover temps (lines 347-348) — open() requires bin present presumably. Gate 1 refuses when bin present ✓.
  8. Biometric enable / rewrap paths — do they write dek.tmp? renameIntoPlace(dekFile) is also used when re-wrapping the DEK (biometric enable/disable changes the wrapped DEK?). Let me check other callers of renameIntoPlace(dekFile) or writeDurable(dekFile). If a crash during a biometric DEK rewrap leaves {bin present, dek.tmp} → bin present → gate 1 refuses, and open() deletes leftover temps. Fine.
  9. What about `vault.dek.tmp` present with bin present? covered by gate 1.

  Missing row candidate: {no bin, no dek, bin.tmp only} — crash between DEK barrier and bin rename can't produce bin.tmp without dek (dek renamed first, durable). Crash inside renameIntoPlace(binFile) after dek durable → {dek, bin.tmp} = row 3. So bin.tmp-only impossible from create. From destroy: confirmed marker present → gate 2. OK.

  Another: markDeleteIntent flow — {intent, bin present} → gate 1 ✓ (row 6 covers delete-intent with bin present).

  What about the confirmed marker present but everything else absent {confirmed only}? Gate 2 refuses → NO_MUTATION → routing: serverDeleteConfirmed → DELETE_INCOMPLETE → destroy re-runs, idempotent, clears markers. ✓ (row 7).

  Row 9: nothing present → NO-OP ✓.

  Seems complete. Now `leftoverTmp`, `deleteLeftoverTmp`, `dirSync` implementations and open() temp-deletion to confirm row 3's claim "open() deletes leftover temps".

• create/destroy/retire ordering all verified. Now the sweep helpers and `open()`:

• All verified. Note the stray duplicated kdoc line at 1295: "/** Delete an incomplete-write temp for [target], if any. Best-effort. */" is now orphaned above imageBearingFilesProvenAbsent's kdoc — a leftover from the diff insertion (cosmetic, INFO). The original `deleteLeftoverTmp` at 1430 lost its doc comment. Minor.

  Now ZitroneApp context: scope definition, `retryPlaintextCacheClearIfNoVault` callers on main? `clearPlaintextCache` usage. Also check `startBootReconcile` is only called from MainActivity. And where `sweepOrphanedVaultResidue` is used (public wrapper — used by tests?). Let me check remaining ZitroneApp bits: `scope`, `hasVault`, `isLegacyImage`, `serverDeleteConfirmed`. Also verify the claim in C: "create() writes the DEK first, destroy() writes vault.delete-confirmed durably before it unlinks" — done, verified.

  Check dirSync implementation quickly.apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:123: * `defaultFsyncDir`). The rename is the COMMIT point — the new image is already on disk and
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:252:    // [defaultFsyncDir]; tests pass a lambda returning a forced [DirSyncResult].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:259:    private val dirSync: (File?) -> DirSyncResult = ::defaultFsyncDir,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1291:        // [defaultFsyncDir]); the caller decides how to act on a NOT_DURABLE result.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1486:private fun defaultFsyncDir(dir: File?): DirSyncResult {
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:136:    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:281:                runCatching { retryPlaintextCacheClearIfNoVault() }
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:295:    fun retryPlaintextCacheClearIfNoVault(): Boolean {
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1259:internal fun clearCacheDir(cacheDir: java.io.File?): Boolean {
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:614:    val scope = rememberCoroutineScope()
dirSync is fine. Now the test files. Read all three.

• Source side verified. Now the three test suites:

• BootReconcileOwnerTest: 8 tests. Note test 6 "a retry after a cancelled run does not re-sweep" — actually it runs two successful sweeps, not a cancelled run then retry. The header says "a retry after a cancelled run" but both sweeps return SWEPT_DURABLE without cancellation. Hmm — the first run completes (durable), second no-ops via CAS. The name mentions "cancelled run" but no cancellation occurs. Is that a naming mismatch? The claim "the claim survives a cancelled run, so a later attempt must NOT re-run destructive work". But the test doesn't cancel. Compare with test above (`a claimant cancelled mid-work does not strand a waiter`) which does cancel. This test asserts no re-sweep after a *successful* run — which is also covered by test 1 (repeat 3). So test 6 is somewhat redundant and its name overclaims ("after a cancelled run" — no cancellation performed). That's exactly the class the round-1 LOW fix addressed (renaming a test that named a cancellation it never performed) — and here's another instance of the same shape that survived! Wait, let me re-read.

  Test `a retry after a cancelled run does not re-sweep`: kdoc says "The claim survives a cancelled run, so a later attempt must NOT re-run destructive work — the inverse damage of the test above". The body: two runBootReconcile calls with normal sweeps. No cancellation. So the name and kdoc claim a scenario (cancelled first run) that the body doesn't exercise. If the first run were cancelled (as in the stranding test), would a retry re-sweep? claim CAS stays true → second call no-ops → sweepRuns stays 1. That behavior is actually implied by combining test 4 (cancelled, publishes) + this CAS, but not directly tested with cancellation in this test. So: name/kdoc overclaim — LOW (test quality). Actually wait — is it the same as the round-1 Codex LOW? That was about a test named "cancellation after a durable sweep" renamed to `a durable verdict is never overwritten by the fail-closed default` (test at line 206). This is a different test. So yes, a residual instance of the same defect class: `a retry after a cancelled run does not re-sweep` performs no cancelled run. LOW finding for I.

  Hmm, but let me think about whether the cancelled-retry scenario IS covered: test 4 cancels and asserts release + hold. It doesn't then retry. So the specific composition "cancelled run → retry → no re-sweep" is untested as named; the test instead tests "successful run → retry → no re-sweep", which duplicates test 1. Minor.

  Also note line 50-51: trailing whitespace at line 51 (`        `) — trivial.

  Now BootRouteTest and SweepOrphanedResidueTest.BootRouteTest is solid — enumerated expectations, full truth table, 5-input sweep. Verify the expected set in `onboarding is reachable…`: onboarding when legacy=true & confirmed=false (8 combos: present/hold/proven vary) — bootRoute: confirmed false → legacy true → ONBOARDING regardless of others. ✓ 8 combos. Plus legacy=false: needs confirmed=false, present=false, hold=false, proven=true → 1 combo. Total 9. ✓ Matches.

  Now SweepOrphanedResidueTest.Sweep tests walk rows 1,2,3,4,5(ELOOP),6,6b,7,8/5-ENOTDIR,9, durability, idempotence, conversion. Row coverage: all rows in the kdoc table are covered (1,1b?,2,3,4,5,6,7,8,9). Row 1b (interrupted retireLegacyImage) — is there a test? I see rows 1, 2, 3, 4, 5, 6, 6b, 7, 5&8, 9. No explicit row 1b test. But row 1b is the same on-disk shape as row 1 ({dek, no bin}) — the sweep can't distinguish writers; coverage by shape is adequate. Fine.

  Note: the round-1 commit says gate 2 restored and "mutation-verified". I could verify by mutation myself — quick: delete gate 2 lines, run this one test. That's cheap. Maybe do it after the full suite run.

  Also check: is there a test that `sweepOrphanedResidue` throw past mutation point → SWEPT_NOT_DURABLE? Not directly, hard to construct. OK.

  Now run the test suite. That's the long pole — start it in the background while I continue checking remaining items (ZitroneApp context, SECURITY_MODEL diff, MainActivity `onUnlockPassphrase` LegacyImage backstop, BootReconcileOwner coverage of deriveBootDecision consumers?).

  Also check: are there tests for deriveBootDecision itself (the skip conditions — legacy not probed when confirmed)? Let me grep tests for deriveBootDecision.apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
===
`deriveBootDecision` has no direct unit test (only bootRoute, the pure function). The skip conditions in deriveBootDecision (legacy probe only when present && !confirmed; runCatching → false) are untested. Is that a coverage gap worth naming? The skip conditions are a performance guard mostly, but the `!serverDeleteConfirmed` skip is safety-relevant-ish (probing legacy under a confirmed delete is wasteful but harmless — bootRoute orders confirmed first anyway). The runCatching-default-false means a failing legacy probe → treated as non-legacy → LOCKED (safe direction). Untested failure shape: deriveBootDecision's legacy-probe guard and exception-to-false mapping. Minor (LOW/INFO).

  While tests run, check SECURITY_MODEL diff and the remaining claims:
  - C: no references to the two healers — grep whole repo.
  - D: verify main's behavior — on main, what routed {bin absent, dek present}? On main, Splash onFinished: serverDeleteConfirmed → DeleteIncomplete; vaultExists → Locked; else Onboarding. So yes, onboarding over residue, and a later create overwrites. Verify create() on main also wrote DEK first (DEK-first barrier is pre-existing — check git show main for the barrier). The barrier comment references "round" stuff from 0.9.2 PRs — main includes it (diff didn't touch create). ✓ So W-A strictly better: main routes onboarding without clearing; W-A sweeps durably first. Also confirm W-A doesn't make any state worse: sweep only deletes when bin proven absent && no confirmed marker; those files (dek/tmp) open nothing without bin. ✓

  - Check SECURITY_MODEL diff.

• While the suite runs, checking the SECURITY_MODEL delta and repo-wide healer references:diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
index 6310c12..b813abf 100644
--- a/docs/SECURITY_MODEL.md
+++ b/docs/SECURITY_MODEL.md
@@ -905,6 +905,42 @@ The app treats it exactly like the printed sticker — it does not track, manage
 desktop the file write happens natively behind the OS save dialog; the WebView never supplies a
 filesystem path.
 
+## Cold-start residue sweep (0.9.2 Unit W-A)
+
+The vault directory can legitimately end up holding a `vault.dek`, a `vault.bin.tmp` or a
+`vault.dek.tmp` with **no `vault.bin`**. Two ordinary interruptions produce that state:
+
+- an interrupted **create** — the DEK is written and fsynced *before* the image (the DEK-first
+  durability barrier, which makes a `{bin present, dek absent}` brick unreachable), so a crash between
+  the two leaves a stray DEK and no image;
+- an interrupted **legacy-image retirement** — it unlinks the image and *then* the DEK, so a crash
+  between those unlinks leaves the same shape.
+
+Boot routing keys on `vault.bin` alone, so it read that state as "no vault" and presented ordinary
+first-run onboarding. That matters because `vault.bin.tmp` stages a **complete** outer image: the
+first-run screen could be shown while a recoverable encrypted vault was still on disk.
+
+**What now happens.** Before any routing decision reads disk state, a cold-start sweep deletes that
+orphaned residue, proves it gone by re-stat, and makes the deletion durable. It runs **only** when the
+image is *proven* absent (`Files.notExists`, so an unstattable image refuses) **and** no
+`vault.delete-confirmed` marker is present or indeterminate — so it can never touch a live vault's key
+or state that an in-flight account deletion owns. It is idempotent and silent.
+
+**Onboarding over an empty directory requires proven absence.** Where the first-run screen is shown
+because the device appears to have no vault, it now requires the whole directory to be provably clean —
+not merely "no `vault.bin`". (Re-onboarding a prior-format image is the one first-run presentation that
+does not rest on that test: there the image is present and is retired by the create.) If a sweep
+unlinked residue but could not confirm the deletion durable, the lock screen is held for the rest of
+that boot instead of claiming a clean device — absence that is not durable is not absence. A sweep that
+simply refuses (a live image, a pending deletion, an unstattable path) changes nothing and reports
+nothing; the routing outcome then follows from the other disk facts.
+
+**Honest limits.** The sweep is cryptographic hygiene, not media sanitization: unlinking does not erase
+data from wear-levelled flash, and the guarantee remains that the DEK is destroyed so surviving blocks
+are ciphertext. The routing decision itself is exhaustively unit-tested; its *delivery to the screen*
+(the Compose wiring, and behaviour across an Activity recreation) is verified by inspection, because
+this project has no Compose UI test infrastructure — tracked as follow-up work.
+
 ## Audit history
 
 See [AUDIT.md](../AUDIT.md). No third-party audits have been completed yet — treat the
./l00prite/.l00prite/ledger.md:885:`signalBurnCompleted(obliterated = burned)` sits in `onBurn`, the mechanism's terminus, so shipping the


• SECURITY_MODEL looks accurate. The only remaining reference to the coupling line is in the l00prite ledger (review history, expected — not code). Let me check the ledger diff briefly for context, and verify no code references.

  Now, items to verify further:

  F: enumerate every consumer of boot-routing state:
  1. Splash decision effect (MainActivity.kt:643-656) — awaits splashFinished && bootDone, re-checks route, uses deriveBootDecisionFromDisk (carried hold). ✓
  2. Post-boot re-derive (658-682) — awaits bootReconciled.first{it}, session re-check before and after suspend. ✓
  3. Session collector (762-793) — calls deriveBootDecisionFromDisk on the MAIN thread (line 783), no withContext(IO). Contradicts "MUST be called off the main thread". Also doesn't gate on bootReconciled — but session-null-after-live implies boot long done. Hold is carried via flow value. ✓ except main-thread issue.
  4. `vaultExists` initial seed: `container.hasVault()` at line 631 — composition seed before boot reconcile. Route starts at Splash (or ChatList if session). Splash doesn't route until both signals. vaultExists seeded true/false affects... what reads vaultExists before the decision? Let me check usages of vaultExists. It's used in Locked screen rendering and onboarding? If vaultExists=false seed on a device with a vault → route Splash → decision corrects. But could anything destructive read the seed? onRetryDestroy sets vaultExists=false. The biometric affordance on lock screen keys on vaultExists? Cosmetic. The seed existed on main too. Fine.

  Also, is there any other caller of `bootRoute` besides deriveBootDecision? grep. And other callers of `hasVault()` that route? onRetryDestroy uses hasVault for success judgment (fine — that's a destroy verification, absence is the goal). Let me grep hasVault/bootRoute usages in main sources.

  G: runBootReconcile contract — verified against source: claim CAS before launch; publish in finally; fail-closed default SWEPT_NOT_DURABLE; CancellationException rethrown inside withContext, publish non-suspending in finally. One subtlety: `scope.launch` — if the scope is cancelled... production scope is process-scoped SupervisorJob, never cancelled. In the finally, publish runs. afterPublish runs in withContext(ioDispatcher) AFTER the finally — if the coroutine was cancelled, this withContext throws CancellationException immediately, afterPublish skipped, exception propagates to supervisor (cancelled child is normal). Fine.

  Edge: if `publish` itself throws? Then hold/done not set — but publish in production sets two StateFlow values, can't throw. In contract terms, not guarded — acceptable.

  Another: `claim()` returns false → return, no-op, callers observe bootReconciled. But what if the FIRST claimant's coroutine fails to launch... scope.launch always launches. OK.

  What about startBootReconcile being called from LaunchedEffect(Unit) — on the main thread, claim + launch on Dispatchers.Default scope. Fine.

  One more F check: `residueSweepHold` is read inside deriveBootDecisionFromDisk on whatever thread; StateFlow.value is thread-safe. Published before bootReconciled=true; consumers read after awaiting done. ✓ And the session collector doesn't await done but as argued can't run before. Actually — can the session collector's else-branch run before bootReconciled? It requires unlocked==true, which requires a live session observed earlier. Session only after unlock. Unlock only after leaving Splash/Locked... but wait: on a warm process (session live), a NEW Activity composition seeds unlocked=true and route=ChatList. If the session then nulls (account delete completing during the rotation window), the collector runs the else branch. bootReconciled: was it published? The process booted earlier (session exists → boot reconcile ran at process start... actually does it? startBootReconcile is called from ZitroneRoot's LaunchedEffect — which runs in the first composition. If the first composition had a live session (process can't have a live session without a prior composition...). Hmm: process start → first ZitroneRoot composition → LaunchedEffect starts reconcile. Session can only become live after user unlock in that process, which happens after routing, after bootReconciled. So yes, invariant holds. ✓

  But actually there's one more path: `createVaultAndPublish` from onboarding — also after boot. ✓

  Now the round-1 fix claims re-verify:
  1. HIGH legacy effect deleted — verified in current source (comment placeholder at 726-734). Any OTHER path routing on legacy without confirmed precedence? onUnlockPassphrase LegacyImage → onboarding (backstop). Is that a problem? It's unlock-time: user at lock screen enters passphrase, store throws LegacyImage. Can a confirmed marker exist there? {v2 image + confirmed marker}: boot routes DELETE_INCOMPLETE (precedence) so the user never reaches the lock screen via boot. Could they reach Locked via the session collector? snap.route would be DELETE_INCOMPLETE. So the backstop only fires when bootRoute said LOCKED, i.e., no confirmed marker. Onboarding there → create retires legacy + clears markers (none). Safe. ✓
  2. Row-7 test restored — verified present (line 204). Mutation-verify myself? The commit claims deleting gate 2 fails exactly that test. I can spot-check: gate 2 deletion → row 7 test would sweep the dek → returns SWEPT_DURABLE ≠ NO_MUTATION → fail. Also row 6b? No marker confirmed there. Yes obviously fails. I might run a quick mutation after the suite finishes (suite running now; don't run two gradle builds concurrently — could conflict in same project dir. I'll wait.)
  3. Single derivation — all three consumers call deriveBootDecisionFromDisk: lines 646, 667, 783. ✓ Full input set: the function reads all five. ✓ But consumer 3 calls it on main thread (the isLegacyImage 1MiB decrypt). Note: in consumer 3's context (session just went null), when would legacy probe run? imagePresent && !confirmed. E.g., user locks the app (close, not destroy) → session null → collector derives → image present, not confirmed → isLegacyImage() reads+decrypts 1 MiB outer layer ON MAIN THREAD. That's a real jank/ANR risk. Was it pre-existing? Pre-0d348b4 code at this site (a98677f version) also called container.isLegacyImage() directly on main. And on main (before W-A), the session collector called container.hasVault() and serverDeleteConfirmed() only — no legacy decrypt... wait let me check main's session collector. From the first diff (main...HEAD): main had:
  ```
  vaultExists = container.hasVault()
  route = when { container.serverDeleteConfirmed() -> DeleteIncomplete; vaultExists -> Locked; else -> Onboarding }
  ```
  No legacy probe. So W-A a98677f INTRODUCED a ~1 MiB main-thread decrypt on every session-null transition (every lock!). 0d348b4 kept it (now via deriveBootDecisionFromDisk). isLegacyImage reads dek + bin (1 MiB) and does one AEAD decrypt — on main thread. That's a perf defect introduced by the extraction, surviving the fix. Severity: LOW–MEDIUM. It contradicts the new kdoc "MUST be called off the main thread" written in 0d348b4 itself. Every app lock (session close) triggers it. I'll rate LOW (jank, no correctness failure; ~1MiB read + single AES-GCM is tens of ms, StrictMode would flag). Actually on a slow flash with 1MiB read + keystore unwrap (deviceCipher.unwrapDek hits Android Keystore — can be 50-200ms!) — readInnerVersionOrNull calls deviceCipher.unwrapDek which is a Keystore operation. On main thread, that's a real ANR-risk-adjacent jank. MEDIUM-leaning LOW. I'll say LOW with a note, or MEDIUM. Given it fires on every lock transition and violates the unit's own just-written contract, I'll call it MEDIUM? Hmm. It doesn't break correctness; user-visible jank possible. The brief says honest. I'll report LOW-to-MEDIUM, settle on LOW (perf, not correctness) but flag the contract violation explicitly. Actually let me reconsider — reviewers in this project tend to rate main-thread keystore IO as MEDIUM. I'll say MEDIUM? The impact: every lock/forced-logout/session-teardown does a 1MiB read + Keystore unwrap on the UI thread → frame drops, potential ANR on slow devices. I'll go MEDIUM.

  Wait — also check: does the session collector else-branch need the legacy value at all? It uses snap.legacy for vaultExists and bootRoute legacyImage → ONBOARDING. Fine semantically.

  4. LOW post-suspend session re-check — present at line 672. ✓
  5. Enumerated expectations — present in BootRouteTest. ✓ Verified the enumeration matches (9 combos).
  6. Test renamed — `a durable verdict is never overwritten by the fail-closed default`. ✓ But found a sibling: `a retry after a cancelled run does not re-sweep` never cancels. Same defect class remains. LOW.
  7. INFO suite headers — check current headers: BootReconcileOwnerTest header says "BOOT-OWNER LIFECYCLE CONTRACT (0.9.2 Unit W-A)" ✓; SweepOrphanedResidueTest "COLD-START ORPHAN SWEEP (0.9.2 Unit W-A)" ✓. No "PUCKER BURN Unit W" remains? grep.
  8. SECURITY_MODEL — corrected; verified wording matches mechanism. ✓
  9. The @Test restoration — all tests have @Test? Count @Test in the three suites: BootReconcileOwnerTest 8, BootRouteTest 10, SweepOrphanedResidueTest 13. Total 31. The commit claims 476 total, +1 = the restored row-7.

  Also A0: "confirm no OTHER gate is uncovered" — gates: gate 1 covered by rows 4/5; gate 2 covered by row 7. Clean-check (already clean → NO_MUTATION) row 9. Mutation point durability covered. ✓

  grep for "PUCKER BURN" in tests.apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:12: * COLD-START ROUTE DECISION (0.9.2 Unit W-A).
apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt:25: * BOOT-OWNER LIFECYCLE CONTRACT (0.9.2 Unit W-A).
apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt:34: * COLD-START ORPHAN SWEEP (0.9.2 Unit W-A).
===
apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt:8
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:10
apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt:13
===
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1313:     * predicate that may authorise a fresh-install presentation; `hasVault()` keys on `vault.bin`
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1317:    fun obliterationComplete(): Boolean = imageLock.withLock { imageBearingFilesProvenAbsent() }
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:619:    // Splash → Locked (which keys on hasVault() and would fake-lock a live, unlocked session and
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:631:    var vaultExists by remember { mutableStateOf(container.hasVault()) }
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:699:                !container.hasVault() && !container.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:728:    // without the carried `residueSweepHold`, and without consulting `serverDeleteConfirmed()` — so
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:959:    // re-derive hasVault() and route to unlock — never blindly re-call create() (which would throw
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:993:                    if (container.hasVault()) {
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1012:    // on disk). hasVault() is then false, so route to Onboarding (fresh-install state), never
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1103:                    vaultExists = container.hasVault()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1180:    residueSweepHold: Boolean,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1194:        route = bootRoute(
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1197:            residueSweepHold = residueSweepHold,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1239:internal fun bootRoute(
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1242:    residueSweepHold: Boolean,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1249:    residueSweepHold -> BootRoute.LOCKED
The suite finished. Read the output and the JUnit XMLs for real numbers.

• The suite finished — reading the result and parsing the JUnit XMLs myself:

total=476 failures=0 errors=0 skipped=3 passed=473
• 476/0/473/3 — matches the commit claim exactly. Verify the 3 skipped are the pre-existing I2P ones.TEST-com.zitrone.app.I2pLiveIntegrationTest.xml
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  <testcase name="probeReportsReady" classname="com.zitrone.app.I2pLiveIntegrationTest" time="0.002">
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  </testcase>
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  <testcase name="healthzReturnsOkThroughTunnel" classname="com.zitrone.app.I2pLiveIntegrationTest" time="0.001">
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  </testcase>
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  <testcase name="webSocketHandshakeReachesServer" classname="com.zitrone.app.I2pLiveIntegrationTest" time="0.0">
476 total, 0 failures, 473 passed, 3 skipped (I2P live integration — pre-existing). Matches the claim. Confirm the three new suites ran and their test counts:TEST-com.zitrone.app.BootRouteTest.xml tests="10"
TEST-com.zitrone.app.BootReconcileOwnerTest.xml tests="8"
TEST-com.zitrone.app.SweepOrphanedResidueTest.xml tests="13"
stash@{0}: On diag/playprotect-dex-relayout: docs: Linux desktop interop correction — awaiting commit approval (set aside for diag build)
All three suites ran with 10+8+13 = 31 tests, matching the @Test counts (8/10/13). Working tree clean (no diff output). 

  Now a mutation check of my own for the two load-bearing claims:
  1. Remove gate 2 → row 7 test must fail.
  2. Swap gate 1 to `binFile.exists()`-based fail-open → ELOOP test must fail.

  I can do these quickly with sed, run only SweepOrphanedResidueTest, then revert. Gradle test with filter: `./gradlew testDebugUnitTest --tests "com.zitrone.app.SweepOrphanedResidueTest"`.

  Also mutation: bootRoute precedence (legacy above confirmed) → BootRouteTest fails. That's obviously covered by tests; maybe skip. Do the two sweep mutations — they're the destructive-gate claims. Let me do mutation 1 and 2 sequentially in one script, restoring in between.0
> Task :app:testDebugUnitTest FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 7 - refuses while a delete-confirmed marker is present FAILED
13 tests completed, 1 failed
BUILD FAILED in 6s
Mutation 1 verified: deleting gate 2 fails exactly the row-7 test (and only it). Now mutation 2: fail-open gate 1.> Task :app:testDebugUnitTest FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 3 - sweeps a surviving bin temp holding a complete image FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 6 - refuses while a delete is in flight over a live image FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 6b - an intent marker does not strand recoverable residue FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 2 - sweeps a stray dek temp FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 4 - refuses while a live vault image is present FAILED
com.zitrone.app.SweepOrphanedResidueTest > is idempotent across repeated cold starts FAILED
com.zitrone.app.SweepOrphanedResidueTest > converts a not-provably-clean directory into a provably clean one FAILED
com.zitrone.app.SweepOrphanedResidueTest > a non-durable dirSync fails the sweep rather than claiming a clean directory FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 1 - sweeps a stray dek with no image FAILED
restored
Mutation 2: fail-open gate 1 fails 9 tests including the ELOOP row-5 test (which scrolled off — the grep showed only first 10 lines; rows 1,2,3,4,6,6b fail because the inverted logic breaks everything). Wait — actually this mutation inverts the gate's meaning for the normal case too (`!binFile.exists()` vs `!Files.notExists(...)` behave identically on normal files, differ only on indeterminate stat). Rows 1-4 failing is odd... oh wait no — `!Files.notExists(p)` = "present or indeterminate" → refuse. `!binFile.exists()` = "not exists" → refuse. For a normal present bin: notExists=false → !false=true → refuse ✓; exists()=true → !true=true → refuse. Same. For absent: notExists=true → proceed; exists()=false → proceed. Same. They differ ONLY on ELOOP/indeterminate. So why did rows 1-4 fail?

  Hmm! Unless my replacement hit a DIFFERENT line. `if (!Files.notExists(binFile.toPath())) return@withLock ResidueSweepResult.NO_MUTATION` — that's gate 1 in the sweep. Replacing with `!binFile.exists()`... should behave the same for rows 1-4. Unless... `binFile.exists()` under ELOOP symlink... no, rows 1-4 don't have symlinks.

  Wait, maybe the mutation broke compilation of something? No — 13 tests completed. Actually wait, look: rows 1,2,3,4,6,6b, idempotent, converts, non-durable all FAILED. That means the mutation changed behavior for ordinary files. How? OH — I see. The replaced text says `// MUTATION: fail-open` — no, that's a comment.

  Hmm, actually maybe the replace hit a different occurrence. Let me think: is `!Files.notExists(binFile.toPath())` present elsewhere with that exact string? The replacement included `return@withLock ResidueSweepResult.NO_MUTATION` — gate 1 only. 

  Wait, maybe I have the polarity backwards. `Files.notExists` returns true if file does not exist. `!notExists` = exists-or-indeterminate → refuse. `binFile.exists()` follows symlinks; ELOOP → false. `!exists()` = true when absent OR indeterminate → refuse. For row 1 (dek only, bin absent): gate1 `!notExists(bin)` = !true = false → don't refuse → proceed to sweep. With mutation: `!exists()` = !false = true → REFUSE → NO_MUTATION → row 1 test expects SWEPT_DURABLE → FAILS.

  I had the polarity inverted! The mutation as written refuses when the file is ABSENT — that's not "fail-open", it's broken polarity. The correct fail-open mutation is `if (binFile.exists()) return NO_MUTATION` — refuse only on a proven presence, proceed on absent-or-indeterminate. My mutation was wrong; it tested nothing about the ELOOP claim. Redo: replace `!Files.notExists(binFile.toPath())` with `binFile.exists()`. Then: row 1: exists()=false → proceed ✓ same as before. ELOOP row 5: exists()=false (ELOOP) → proceed → deletes dek → test fails. That's the true fail-open mutation.

• My mutation had inverted polarity — it tested nothing useful. Redoing it correctly (`exists()` as the refuse condition = fail-open on indeterminate stat):> Task :app:testDebugUnitTest FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 5 - an unstattable image must not cost a live vault its DEK FAILED
13 tests completed, 1 failed
BUILD FAILED in 3s
restored


• The correct fail-open mutation fails exactly the ELOOP row-5 test, as the test header claims. Both sweep-gate mutation claims independently verified. Working tree restored clean.

  One more mutation to spot-check the carried-verdict claim in BootRouteTest? The bootRoute ordering mutations are straightforwardly covered by the truth table. Skip.

  Now let me finish remaining verification:

  - ZitroneApp remaining context: lines 226-300 read already in diff. `scope` = SupervisorJob + Dispatchers.Default (line 136) — the reconcile launches on Default then withContext(IO). Fine.

  - C remaining: verify nothing references the healers and no comment assumes they run. Grep found only the ledger line (historical record). Let me also check the ledger diff added in this unit mentions them as excluded (that's documentation of the decision, fine). Also check docs/ and code comments for "heal" references... quick grep for "InterruptedBurn" etc. already done — nothing in apps/android or docs.

  - D: verify main's boot routing for {bin absent, dek present}. Check main's MainActivity Splash onFinished — from the main...HEAD diff, the removed Splash routing was:
  ```
  container.serverDeleteConfirmed() -> Route.DeleteIncomplete
  vaultExists -> Route.Locked
  else -> Route.Onboarding
  ```
  with vaultExists = hasVault() = binFile.exists(). So yes: {no bin, dek} → Onboarding, and a later create overwrites the dek (renameIntoPlace REPLACE_EXISTING) without durability sweep. W-A: sweeps durably first, then onboarding requires proven absence. Strictly better; no state made worse: sweep refuses whenever bin present/indeterminate or confirmed marker present/indeterminate. The only new destructive act is deleting dek/tmps when bin is proven absent — those bytes open nothing without an image (open() throws MissingImage before reading dek; and no other reader of vault.dek exists without vault.bin... biometric unwrap? BiometricUnlockStore? Let me check: is there any code that reads vault.dek alone — e.g., biometric unlock path reading the wrapped DEK while bin missing? biometric unlock needs to open the image too. If bin is absent there's nothing to unlock. Safe. ✓

  Actually, one more D-check: does anything on main currently HEAL {bin absent, dek present}? open() throws MissingImage → treated as no-vault. So the residue persisted forever on main. W-A clears it. ✓

  - B: coupling line — no signalBurnCompleted anywhere in code (grep found only ledger). onBurn identical to main. No half-removed state: grep for `obliterated`, `burned` in MainActivity/ZitroneApp? The PassphraseOutcome.Burn exists on main already (router triple-entry from PR-2 — pre-existing). Let me verify PassphraseOutcome.Burn exists on main too: main's MainActivity has `PassphraseOutcome.Burn -> onBurn()` (seen in git show main output above, line 805). ✓ Pre-existing, not extraction residue.

  - F consumers enumerated: 3 consumers + initial seed + onRetryDestroy + MainActivity:993,1103 hasVault uses. Check 993 and 1103 context quickly to ensure they're not boot-routing decisions that bypass the carried verdict.These are not cold-start boot routing — they're post-delete completion routing (line 1099) and create-failure recovery (993). Both are post-mutation verifications where a fresh stat IS the right tool (they just performed/attempted the mutation; they need current truth, not durability verdict). Note line 1104: `!vaultExists && !serverDeleteConfirmed()` → Onboarding — this is after a completed destroy, which itself made the unlinks durable (destroy requires dirSync DURABLE before retiring markers, else throws → DeleteIncomplete branch). So onboarding here is authorised by destroy's own durability proof, not by the stat. Fine.

  Line 1119+: "Reconcile an interrupted account deletion (round 14, F1). A delete-intent marker that…" — the intent LaunchedEffect. Let me read it briefly since the bootRoute comment says "intent-only handling lives in the boot decision, not here" — wait, actually the comment in the session collector says "intent-only handling lives in the boot decision". Hmm, but the removed Splash comment said "the post-unlock reconcile (see the intent LaunchedEffect) retries the authenticated DELETE". Does the current code still have that intent effect? Let me read on.The intent-reconcile effect is intact (post-unlock DELETE retry). Note: the session collector comment says "intent-only handling lives in the boot decision, not here" — slightly odd phrasing since intent handling lives in this LaunchedEffect(session), but the meaning is "boot routes intent-only to Locked, and post-unlock reconcile handles it" — bootRoute with intent-only: confirmed=false → present → LOCKED ✓. Consistent.

  Now check `deleteAccountAndWipe`/`destroyVaultForAccountDeletion` (ZitroneApp:712) briefly for the C-claim "destroy() writes vault.delete-confirmed durably before it unlinks" — already verified at VaultImageStore.destroy() line 1108. ✓

  Also verify C's claim about the healers' unreachability: they healed states produced by the burn mechanism (interrupted burn / orphaned burn markers) — without burnVault those states can't arise. And nothing references them. ✓ (grep clean except ledger).

  Remaining: check the ledger diff is just history (not required). Skip.

  ZitroneApp remaining: lines 226-302 already seen in diff. Also `hasVault()` kdoc at 228 unchanged. And check `isLegacyImage` implementation at 311 in VaultImageStore:`isLegacyImage` confirms the main-thread cost: reads dek + bin (~1 MiB), unwraps DEK via deviceCipher (Android Keystore in production), one AEAD decrypt. In the session collector (MainActivity.kt:783) this runs on the main thread on every session-null transition where an image is present and no delete is confirmed — i.e., every ordinary lock/sign-out/forced-logout. That's the one substantive finding.

  Let me double check that the session collector's collect runs on main. LaunchedEffect → composition's coroutineContext → main dispatcher. Yes. And prior to 0d348b4 (a98677f), the same site called `container.hasVault()`, `container.serverDeleteConfirmed()`, `container.isLegacyImage()` directly — also on main. So a98677f introduced it; 0d348b4 preserved it while adding the "MUST be called off the main thread" kdoc. It's in-scope for the fix delta review: the single-derivation fix consolidated all three call sites but left one violating the function's own threading contract. Also `deriveBootDecisionFromDisk`'s kdoc says "Call OFF the main thread".

  Also verify: `git show main:VaultImageStore.kt` — is isLegacyImage on main? It was added by PR-2 (0.9.2-beta) per routing comments... main includes it (triple-entry router fusion). Not important.

  One more possible check on F: `deriveBootDecisionFromDisk` reads four disk facts sequentially — between reads, disk could change (e.g., a create completing concurrently). Each consumer has re-checks after suspend. Race windows exist but fail directions are safe. Fine.

  Also G verification detail: in production `startBootReconcile`, `publish` sets hold THEN done — order correct. Claim via AtomicBoolean CAS. If process starts two Activities simultaneously? CAS handles. ✓

  Edge in runBootReconcile: if `sweep()` returns normally but the coroutine was cancelled during withContext... result assigned, finally publishes actual verdict. Fine.

  Another subtle point: `runBootReconcile` returns after `scope.launch` — if claim succeeded but scope is cancelled (SupervisorJob not cancelled in prod). Fine.

  Also: `afterPublish` runs `withContext(ioDispatcher) { afterPublish() }` AFTER the finally — if the coroutine was cancelled, withContext throws CancellationException → afterPublish skipped → cache clear skipped. Harmless (hygiene only, retried next boot).

  Wait — actually one subtle bug candidate: after a normal completion, `withContext(ioDispatcher) { afterPublish() }` — if afterPublish throws (it's wrapped in runCatching in production), exception would propagate to the launched coroutine → SupervisorJob → swallowed but crash handler? In production afterPublish = runCatching{...} so no throw. Contract-wise fine.

  Now BootRouteTest `full truth table` verify a couple of entries: (false,false,true,true) → hold → LOCKED ✓ (row list (false,false,true,true) maps to LOCKED — yes line 191). (false,false,false,true) → ONBOARDING ✓. All good.

  Test quality (I): 
  - `a retry after a cancelled run does not re-sweep` — never cancels; name/kdoc overclaim; and as written it duplicates `a second start does not re-run the destructive sweep` (both: successful run then retry, assert sweepRuns==1). Actually test 1 does 3 calls; test 6 does 2 calls. Effectively duplicate coverage under a mismatched name. LOW.
  - Untested failure shape: deriveBootDecision (the skip-guard + runCatching(false) mapping and the carried five inputs into bootRoute) has no direct unit test; and the Compose delivery is inspection-only (acknowledged in SECURITY_MODEL honest-limits). Also the cancellation-then-retry composition in BootReconcileOwnerTest as named. 
  - Do any tests pass vacuously? The ENOTDIR test header honestly admits its weakness. The ELOOP test is load-bearing and I mutation-verified it. BootReconcileOwnerTest tests are genuine (they drive real waiters). 

  One more check for I: does any test exercise that the SWEEP runs before routing in production wiring? No — Compose wiring untested (acknowledged). Fine.

  Now check A0 sibling: "no OTHER path still routes on legacy without the confirmed-marker precedence" — onUnlockPassphrase LegacyImage backstop: routes to Onboarding at unlock time. Could a user reach the lock screen with {legacy image + confirmed marker}? Boot routing: confirmed → DELETE_INCOMPLETE everywhere (all three consumers). DeleteIncomplete screen → retry destroy → destroys legacy image? destroyVaultForAccountDeletion → destroy() which unlinks regardless of version. So the lock screen is unreachable in that state via boot. But what about the post-delete finally at line 1104? That's after destroy completes. What about `onForcedLogout` → Route.Locked directly (line 803)! `live.coordinator.onForcedLogout = { unlocked = false; route = Route.Locked; container.unlockController.lockIf(live) }`. If a forced logout happens while {legacy image present}... session over a legacy image can't exist (can't unlock v2 under 0.9.2). So unreachable. ✓

  And `onUnlockSuccess` → ChatList. Fine.

  So the LegacyImage backstop in onUnlockPassphrase can only be reached when bootRoute already said LOCKED, i.e., no confirmed marker. Safe. ✓

  D final check — "no state is made worse": consider {bin absent, dek present, delete-intent present} (crash after intent, then... how would bin vanish? Only destroy (confirmed first) or retire (v2). v2 + intent: intent written under 0.9.2 only; a v2 image can't be unlocked under 0.9.2, so no session, so no delete flow, so no intent. Unreachable.) And 6b test asserts intent doesn't strand residue — matches design. The sweep deleting residue under intent-only is acceptable since residue is unreachable-dead and main's behavior (onboarding → create clears markers) is identical modulo durability. ✓

  One question for E completeness — the missing row hunt: what about {bin absent, dek present, delete-INTENT present, confirmed absent}? Table lacks an explicit row; covered implicitly by 6b reasoning (no intent gate). The table lists rows 1-9; row with intent + no bin + residue is exactly test 6b — the kdoc table doesn't have a numbered row for it, but the prose after the table covers it. Fine.

  What about {bin absent, dek.tmp present, confirmed present}? → gate 2 refuses ✓ (row 7 "...").

  What about vault.dek present with bin present but dek unstattable? Gate 1 refuses (bin present) ✓.

  What about a leftover from `atomicWrite` of sealed payloads — writeSealedPayload writes where? To baseDir as well? If sealed payload files (e.g. vault.sealed?) live in the same dir, sweep doesn't touch them — correct, they're not image-bearing. And obliterationComplete doesn't check them — routing only needs image-bearing absence. OK.

  Also — does anything else create files matching leftoverTmp names? TMP_SUFFIX=".tmp" appended to vault.bin/vault.dek only. ✓

  H done: 476/0/473/3 reproduced; skipped = I2pLiveIntegrationTest ×3.

  Now also confirm the row-7 claim "mutation-verified: deleting gate 2 now fails exactly that test" — verified by me: yes, exactly that one test failed.

  G vs tests: BootReconcileOwnerTest covers once-only, carried verdict, throw→fail-closed, cancel→release fail-closed, no-overwrite, retry-no-resweep, durable→no hold, NO_MUTATION→no hold. All 8 run (XML confirms 8). The publication-ordering sub-claim (hold before done) is honestly disclaimed in the corrected kdoc (test can't constrain injected publish). Production publish order verified by reading. ✓

  One more thing: `BootReconcileOwnerTest` line 50-51 has trailing whitespace — irrelevant.

  Also confirm that `sweepOrphanedVaultResidue()` public wrapper on AppContainer is used anywhere (or just API surface). grep:

• All verification is essentially complete. One last sweep for unused surface and the ledger delta:apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt:61:     * composition issues `startBootReconcile()`, so without the claim a rotation would re-run a
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:662:        container.startBootReconcile()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:281:                runCatching { retryPlaintextCacheClearIfNoVault() }
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:290:     * ([VaultImageStore.primaryImageProvenAbsent]) and not the `File.exists()`-backed routing signal —
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:296:        if (!imageStore.primaryImageProvenAbsent()) return false
===
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:297:        return runCatching { clearCacheDir(app.cacheDir) }.getOrDefault(false)


• `sweepOrphanedVaultResidue()` public wrapper on AppContainer (ZitroneApp.kt:252 area) has NO caller — dead public API (the sweep is invoked via the lambda in startBootReconcile directly on imageStore). Minor: dead wrapper. INFO. Actually wait, check: `sweep = { imageStore.sweepOrphanedResidue() }` — yes, startBootReconcile calls imageStore directly. So `AppContainer.sweepOrphanedVaultResidue()` is unused. INFO (dead code).

  Everything verified. Mark todos and write the report.

  Findings summary:

  1. MEDIUM — MainActivity.kt:783: session collector calls `deriveBootDecisionFromDisk()` on the main thread. The function's own kdoc (both on the container wrapper and `deriveBootDecision`, written in 0d348b4) says "MUST be called off the main thread" — it performs `readInnerVersionOrNull()`: a ~1 MiB read of vault.bin, a Keystore `unwrapDek`, and an AEAD decrypt (`VaultImageStore.kt:964-982`, `isLegacyImage` at :311). This fires on every session→null transition with a present image and no confirmed delete — i.e., every ordinary lock/sign-out — causing UI jank / ANR risk on slow storage, and it violates the contract the fix delta itself wrote. The two sibling consumers correctly wrap in `withContext(Dispatchers.IO)`. Introduced by a98677f (main's collector only did two fast stats), preserved by 0d348b4. Fix: wrap in withContext(Dispatchers.IO) with the same post-suspend re-check pattern (session still null / unlocked state unchanged) used by the other two consumers.

  2. LOW — BootReconcileOwnerTest.kt:232-256 `a retry after a cancelled run does not re-sweep`: no cancellation is performed; both runs are ordinary durable sweeps. Same defect class as the round-1 Codex LOW the delta fixed (a test naming a cancellation it never performs) — one instance survived. As written it duplicates `a second start does not re-run the destructive sweep`. Fix: either cancel the first run (sweep throws CancellationException) then retry and assert sweepRuns stays 1, or rename to what it proves.

  3. LOW — deriveBootDecision has no direct test: the skip guard (legacy probed only when present && !confirmed) and the runCatching→false mapping are untested. bootRoute itself is exhaustively tested, but a mutation in the wrapper (e.g. probing legacy over a confirmed delete, or defaulting probe failure to true) fails nothing. Defaulting to true on failure would route a corrupt/current image to onboarding → create() retires... wait, retireLegacyImage checks version and refuses non-legacy. create() requires !binFile.exists() → throws "already exists". Hmm — defaulting probe failure to `true` would send a present non-legacy image to ONBOARDING where create() throws. Not silent destruction, but a dead-end screen. Untested either way. LOW.

  4. INFO — ZitroneApp.kt:~252 `sweepOrphanedVaultResidue()` public wrapper is dead (no callers; startBootReconcile uses imageStore directly).

  5. INFO — VaultImageStore.kt:1295 orphaned doc line "Delete an incomplete-write temp for [target], if any. Best-effort." now floats above imageBearingFilesProvenAbsent's kdoc after the extraction insertion; deleteLeftoverTmp (:1430) lost its comment. Cosmetic.

  Untested failure shape still remaining (I): the Compose delivery (Splash gating, rotation) — acknowledged in SECURITY_MODEL honest limits; and the cancellation→retry composition named above.

  Verdicts A–I:
  - A0: three consumers all on deriveBootDecisionFromDisk with full input set (646, 667, 783); no other legacy-routing path (backstop at onUnlockPassphrase unreachable under confirmed marker since bootRoute precedence forces DELETE_INCOMPLETE); both sweep gates covered (rows 4/5 ELOOP, row 7). PASS with the threading caveat (finding 1).
  - A: PASS — no burn mechanism/presentation symbols anywhere in apps/android (grep); onBurn byte-identical to main (verified via git show main:).
  - B: PASS — signalBurnCompleted absent from all code; only reference is the historical ledger entry; no dangling caller/state.
  - C: PASS — completeInterruptedBurn/reconcileOrphanedBurnMarkers referenced nowhere; create() DEK-first verified (539-546), destroy() confirmed-marker-first verified (1108 before 1111-1114); unreachable-claim sound; no comment assumes they run.
  - D: PASS — main routes {no bin, dek} to Onboarding via hasVault()→false (verified main's Splash routing in diff); W-A sweeps durably first and onboarding requires proven absence. No state made worse: sweep only deletes when bin proven absent AND no confirmed marker; orphan dek/tmp opens nothing (open() keys on bin, MissingImage before dek read).
  - E: PASS — gates verified; both mutation directions tested by me: gate-2 deletion fails exactly row 7; fail-open gate 1 fails exactly the ELOOP row-5 test. Writer/reader table complete: create (DEK barrier), retireLegacyImage (bin-then-dek unlink order verified at 940-941), renameIntoPlace temps, destroy (confirmed marker first) — no missing writer found; no-delete-intent-gate reasoning verified against destroy() (confirmed written required-durable before any unlink) and create() (clears both markers durably first).
  - F: PASS with one caveat — all three consumers use the carried verdict via deriveBootDecisionFromDisk, published (hold then done) before any waiter is released; consumers await bootReconciled or can't precede it; no consumer recomputes from a fresh stat; no consumer passes a partial input set (it's a single no-default function). Caveat = finding 1 (threading, not verdict-carriage).
  - G: PASS — once-only CAS; publish in finally (non-suspending); fail-closed default SWEPT_NOT_DURABLE; CancellationException rethrown not swallowed; claim can't be stranded (finally publishes even on cancellation) — verified in source and covered by 8 running tests.
  - H: PASS — I ran it: 476 total, 0 failures, 0 errors, 473 passed, 3 skipped; skipped are I2pLiveIntegrationTest (pre-existing live-integration). Matches the claim exactly. New suites: BootRouteTest 10, BootReconcileOwnerTest 8, SweepOrphanedResidueTest 13, all present in JUnit XML.
  - I: mostly strong — enumerated expectations, real-waiter assertions, honest weak-test disclaimers that I confirmed accurate (ENOTDIR test admits gate-swap doesn't fail it — consistent with my mutation run where only the ELOOP test failed). Two quality findings (2 and 3 above). No vacuous passes found among the tests I traced.

  READY TO MERGE? Findings: 1 MEDIUM (main-thread keystore+1MiB IO on every lock), 2 LOW, 2 INFO. The MEDIUM is a real contract violation introduced by this unit's extraction commit and preserved by the fix — it's not a security/correctness break. I'd say: not quite ready — fix finding 1 (one-line withContext wrap + re-check) before merge; it's cheap and eliminates an every-lock jank on the UI thread. Or rate it acceptable-with-followup. Given the project's bar (six rounds over verdict-carriage discipline), I'll say: merge after fixing finding 1; findings 2–4 can ride along.

  Actually let me reconsider severity of finding 1 once more. unwrapDek via Android Keystore: KeystoreDeviceKeyCipher — check what unwrapDek does; if it's a Keystore operation it's binder IPC, can block tens of ms. Plus 1 MiB file read. On the main thread on each lock. That's a StrictMode violation and visible jank; ANR only in pathological cases. I'll keep MEDIUM with clear reasoning, recommending the simple fix.

  Let me quickly check KeystoreDeviceKeyCipher.unwrapDek to be accurate.

• One quick confirmation of what `unwrapDek` costs in production, to calibrate finding severity:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeystoreDeviceKeyCipher.kt:43:class KeystoreDeviceKeyCipher(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeystoreDeviceKeyCipher.kt-49-        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeystoreDeviceKeyCipher.kt-63-        val ct = cipher.doFinal(dek)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeystoreDeviceKeyCipher.kt-72-    override fun unwrapDek(blob: ByteArray): ByteArray? {


• Confirmed: production `unwrapDek` is an Android Keystore AES/GCM operation (binder IPC + possible gatekeeper involvement). On the main thread, on every lock. MEDIUM stands.

  Now write the final report. Update todos first.

• # Round-2 Independent Review — Unit W-A fix delta (0d348b4 over a98677f)

  I read both commits in full, the three touched source files end-to-end where relevant, all three test suites, `main`'s versions for comparison, and I ran the suite plus two mutation experiments in my worktree. Verdicts below; findings first.

  ## Findings

  **MEDIUM — `MainActivity.kt:783`: the session collector runs the single derivation on the main thread, violating the contract the fix itself wrote.**
  0d348b4 consolidated all three consumers onto `deriveBootDecisionFromDisk()`, whose kdoc (added in this same commit, `ZitroneApp.kt:237-240`, and on `deriveBootDecision`, `ZitroneApp.kt:1174`) states it "MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer." Two consumers comply (`MainActivity.kt:646`, `:667`, both `withContext(Dispatchers.IO)`). The third — the `container.session.collect` else-branch at `:783` — calls it bare inside a `LaunchedEffect`, i.e. on Main. Whenever a session goes null with an image present and no confirmed delete (every ordinary lock / forced logout / sign-out), this executes `readInnerVersionOrNull()` (`VaultImageStore.kt:964-982`): a ~1 MiB `vault.bin` read, an **Android Keystore** AES/GCM `unwrapDek` (`KeystoreDeviceKeyCipher.kt:72` — binder IPC), and an AEAD decrypt, on the UI thread. Introduced by a98677f (main's collector did only two fast stats); the round-1 consolidation preserved it. Not a correctness break — a per-lock jank / StrictMode violation / ANR-adjacent risk on slow storage. Fix: wrap in `withContext(Dispatchers.IO)` with the same post-suspend re-check the round-1 LOW fix added to the sibling consumer.

  **LOW — `BootReconcileOwnerTest.kt:232-256`: `a retry after a cancelled run does not re-sweep` performs no cancellation.**
  Both runs are ordinary durable sweeps; nothing is cancelled. This is the same defect class as the round-1 Codex LOW this delta fixed (a test naming a cancellation it never performs) — one instance survived, and as written the test duplicates `a second start does not re-run the destructive sweep` (:65). The named scenario (cancelled claimant → retry must not re-sweep) is real and worth testing. Fix: make the first sweep throw `CancellationException`, retry, assert `sweepRuns == 1` — or rename to what it proves.

  **LOW — `deriveBootDecision` (`ZitroneApp.kt:1177-1204`) has no direct test.**
  `bootRoute` is exhaustively tested, but the new wrapper's two behaviors are uncovered: the probe guard (legacy computed only when `imagePresent && !serverDeleteConfirmed`) and the `runCatching {…}.getOrDefault(false)` mapping. A mutation defaulting probe-failure to `true` routes a present *non-legacy* image to ONBOARDING (where `create()` then throws "already exists" — a dead-end screen) and fails no test. Fix: three small tests over `deriveBootDecision` with a counting/throwing probe lambda.

  **INFO — `ZitroneApp.kt:252`: `sweepOrphanedVaultResidue()` is dead public surface** — no callers; `startBootReconcile` invokes `imageStore.sweepOrphanedResidue()` directly.

  **INFO — `VaultImageStore.kt:1295`: orphaned doc line.** The extraction left `/** Delete an incomplete-write temp for [target], if any. Best-effort. */` floating above `imageBearingFilesProvenAbsent`'s kdoc; it belongs to `deleteLeftoverTmp` (:1430). Cosmetic.

  ## Verdicts on the binding items

  - **A0 — PASS, with the MEDIUM caveat.** All three consumers now call `deriveBootDecisionFromDisk` (full five-input set; `bootRoute` has no defaulted parameters, so a weaker call is a compile error — verified at `ZitroneApp.kt:1239-1253`). No other legacy-routing authority exists: the `onUnlockPassphrase` LegacyImage backstop (`MainActivity.kt:857-862`) is unreachable under a confirmed marker because every boot consumer's `bootRoute` precedence forces DELETE_INCOMPLETE there. Both sweep gates have coverage (rows 4/5, row 7).
  - **A — PASS.** Grep across `apps/android`: none of `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion` exists. `onBurn` is byte-identical to `main` (verified via `git show main:` — same stub, same uniform failure, destroys nothing). Remaining "burn" hits are the pre-existing message-TTL feature and `PassphraseOutcome.Burn`, both already on main.
  - **B — PASS.** The coupling line exists only as a historical quote in `l00prite/.l00prite/ledger.md:885`. No dangling caller, no half-removed state.
  - **C — PASS.** Neither healer is referenced anywhere in code or docs. The unreachability claim checks out: `create()` clears both markers durably first, then writes the DEK durably *before* `vault.bin` (`VaultImageStore.kt:505-553`); `destroy()` writes `vault.delete-confirmed` required-durable *before* any unlink (:1108 vs :1111-1114); `retireLegacyImage()` unlinks bin then dek (:940-941). No comment assumes the healers run.
  - **D — PASS.** Main's Splash routed `{bin absent, dek present}` to Onboarding via `hasVault()==false` (confirmed in the removed code of the diff), with the residue never healed. W-A deletes it only when `vault.bin` is *proven* absent (`Files.notExists`) and no confirmed marker is present-or-indeterminate, then proves the unlink by re-stat + dir-fsync. The orphan bytes open nothing (`open()` keys on bin, `MissingImage` before any DEK read, :350-352). Strictly better; nothing made worse.
  - **E — PASS, mutation-verified by me.** I ran two mutations against the real suite: (1) deleting gate 2 fails **exactly** `row 7 - refuses while a delete-confirmed marker is present`; (2) a fail-open gate 1 (`binFile.exists()` as the refuse condition) fails **exactly** `row 5 - an unstattable image must not cost a live vault its DEK` (ELOOP). The writer/reader table is complete — I hunted for a missing writer (create's DEK barrier, retire's unlink order, `renameIntoPlace` temps at :1240-1273, destroy's marker-first, biometric rewrap temps which always coexist with a present bin → gate 1) and found none. The no-`delete-intent`-gate reasoning holds: every real D2c unlink carries the confirmed marker by construction, and an intent-only state with absent image is unreachable (intent is written while the image is present; `create()` clears both markers).
  - **F — PASS.** The verdict is carried, never re-derived: `publish` sets `residueSweepHold` *then* `bootReconciled` (`ZitroneApp.kt:274-277`); every routing consumer either awaits `bootReconciled` or (the session collector) cannot execute before it, since a session can only exist post-unlock post-boot. No consumer stats for cleanliness. The post-delete `finally` at `MainActivity.kt:1103-1112` uses fresh stats correctly — that is post-mutation verification where `destroy()`'s own required-durable barrier authorises the outcome, not boot routing.
  - **G — PASS.** Once-only CAS before launch; fail-closed default `SWEPT_NOT_DURABLE`; `publish` in a non-suspending `finally` on every exit; `CancellationException` rethrown, not swallowed (`ZitroneApp.kt:1135-1163`). A cancelled claimant still publishes, so the claim cannot strand waiters. All eight contract tests exist and ran (JUnit XML confirms 8/8).
  - **H — PASS.** I ran `ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest` and parsed the JUnit XMLs myself: **476 total, 0 failures, 0 errors, 473 passed, 3 skipped** — the claim reproduces exactly. The 3 skips are `I2pLiveIntegrationTest` (pre-existing live-integration). New suites all ran: BootRouteTest 10, BootReconcileOwnerTest 8, SweepOrphanedResidueTest 13.
  - **I — mostly strong.** Expectations are enumerated, not re-derived (I re-checked the 9-combination onboarding set against `bootRoute` — exact). Waiter-based tests assert on the damage. The ENOTDIR test's honest-limit disclaimer is accurate — my fail-open mutation confirmed only the ELOOP test holds gate 1. No vacuous passes found. Remaining untested shapes: the `deriveBootDecision` wrapper (finding 3), the cancelled-then-retry composition (finding 2), and the Compose delivery itself — the last explicitly disclaimed in `SECURITY_MODEL.md`'s honest-limits paragraph, which I verified matches the mechanism.

  All round-1 fixes are real, complete, and safe: the legacy effect is genuinely gone (comment placeholder only, `MainActivity.kt:726-734`), the row-7 test is restored and mutation-holds, the derivation is singular, the post-suspend session re-check is present (:672), and the `onboarding is reachable…` expectations are enumerated. The round-1 commit's self-reported `@Test`-drop rescue is confirmed by the counts (31 `@Test` annotations = 31 executed tests across the three suites).

  ## Merge verdict

  **NOT QUITE — one fix short.** No security or correctness defect remains; the extraction is clean and every round-1 fix verified. But finding 1 is a genuine per-lock main-thread Keystore+1 MiB IO that contradicts the threading contract this very delta wrote into `deriveBootDecision`'s kdoc — a one-`withContext` fix with an already-established pattern two call sites away. Land that, and findings 2–4 (test name, wrapper tests, dead wrapper) can ride the same commit. After that, this is ready to merge.

To resume this session: kimi -r session_ad98ac1e-0a8c-45b8-8754-2e3ba85b7db5
