• Let me start by examining the diff and the relevant source files.

• I'll start by examining the commit and diff scope.a98677f 0.9.2 Unit W-A — cold-start orphan residue sweep + fail-closed boot routing
c3e4038 CI pipeline security — close release-apk.yml shell-injection + make SAST actually gate (#59)
e32f0aa 0.9.2-beta — biometric-enable atomicity (closes the disclosed orphan gap) (#57)
e203560 Update README.md
956bae9 0.9.2-beta PR-3 Unit 2 — honest docs for the second vault (deliver-then-claim) (#56)
Not currently on any branch.
nothing to commit, working tree clean
 .../src/main/java/com/zitrone/app/MainActivity.kt  | 145 +++++++--
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 178 +++++++++++
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 167 ++++++++++
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 287 +++++++++++++++++
 .../src/test/java/com/zitrone/app/BootRouteTest.kt | 242 ++++++++++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 350 +++++++++++++++++++++
 docs/SECURITY_MODEL.md                             |  32 ++
 7 files changed, 1376 insertions(+), 25 deletions(-)


• No output? Diff stat showed changes... maybe these are new files; `git diff` should still show them. Let me try again with explicit options.M	apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
M	apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
M	apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
A	apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
A	apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
A	apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
M	docs/SECURITY_MODEL.md
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index 250555f..ac16e0d 100644
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
@@ -225,6 +226,65 @@ class AppContainer(private val app: Application) {
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
@@ -1035,3 +1095,121 @@ internal fun sealDurableOrFalse(seal: () -> Unit): Boolean =
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
+ *     lock screen" (a legacy image IS present, so it would otherwise fall thr
ough to a lock screen the
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
+    // Re-list to PROVE the clear rather than trusting deleteRecursively's bool (false on I/O error).
+    val remaining = cacheDir.listFiles() ?: return false
+    return remaining.isEmpty()
+}
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
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
+     *  #  on-disk state                          writer       
                 gate result
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
 
diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 21e6d9b..9603378 100644
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
@@ -628,6 +629,92 @@ private fun ZitroneRoot(
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
+        val decided = withContext(Dispatchers.IO) {
+            val confirmed = container.serverDeleteConfirmed()
+            val present = container.hasVault()
+            // Computed only when it can matter — a ~1 MiB outer decrypt, so never on a
+            // confirmed-delete or an absent image, and never on the main thread.
+            val legacy = if (present && !confirmed) {
+                runCatching { container.isLegacyImage() }.getOrDefault(false)
+            } else {
+                false
+            }
+            BootDecision(
+                present = present,
+                legacy = legacy,
+                route = bootRoute(
+                    serverDeleteConfirmed = confirmed,
+                    vaultImagePresent = present,
+                    residueSweepHold = container.residueSweepHold.value,
+                    vaultProvenAbsent = container.vaultProvenAbsent(),
+                    legacyImage = legacy,
+                ),
+            )
+        }
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
+            val snap = withContext(Dispatchers.IO) {
+                val c = container.serverDeleteConfirmed()
+                val p = container.hasVault()
+                val l = if (p && !c) {
+                    runCatching { container.isLegacyImage() }.getOrDefault(false)
+                } else {
+                    false
+                }
+                BootDecision(
+                    present = p,
+                    legacy = l,
+                    route = bootRoute(
+                        serverDeleteConfirmed = c,
+                        vaultImagePresent = p,
+                        residueSweepHold = container.residueSweepHold.value,
+                        vaultProvenAbsent = container.vaultProvenAbsent(),
+                        legacyImage = l,
+                    ),
+                )
+            }
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
@@ -728,14 +815,34 @@ private fun ZitroneRoot(
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
+                val imagePresent = container.hasVault()
+                val legacyNow = if (imagePresent && !container.serverDeleteConfirmed()) {
+                    runCatching { container.isLegacyImage() }.getOrDefault(false)
+                } else {
+                    false
+                }
+                // A legacy image is present but NOT usable — same derivation the boot consumers use.
+                vaultExists = imagePresent && !legacyNow
+                route = when (
+                    bootRoute(
+                        serverDeleteConfirmed = container.serverDeleteConfirmed(),
+                        vaultImagePresent = imagePresent,
+                        residueSweepHold = container.residueSweepHold.value,
+                        vaultProvenAbsent = container.vaultProvenAbsent(),
+                        legacyImage = legacyNow,
+                    )
+                ) {
+                    BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
+                    BootRoute.ONBOARDING -> Route.Onboarding
+                    BootRoute.LOCKED -> Route.Locked
                 }
             }
         }
@@ -1174,23 +1281,11 @@ private fun ZitroneRoot(
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
Now let me read the full relevant source files: ZitroneApp.kt, VaultImageStore.kt, MainActivity.kt — particularly `create()`, `destroy()`, `retireLegacyImage()`, `onBurn`, and check for residue of burn-related functions. Also check main:MainActivity for onBurn stub comparison.

• Now let me read the full source files to verify the claims. Starting with focus items A–C.diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
index 6310c12..4e15702 100644
--- a/docs/SECURITY_MODEL.md
+++ b/docs/SECURITY_MODEL.md
@@ -905,6 +905,38 @@ The app treats it exactly like the printed sticker — it does not track, manage
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
+**Onboarding requires proven absence.** Every path that can present the first-run screen now requires
+the whole directory to be provably clean — not merely "no `vault.bin`". If the sweep cannot complete
+(an I/O fault, a refused gate), or completed without a durable sync, the lock screen is held for the
+rest of that boot instead of claiming a clean device. Absence that is not durable is not absence.
+
+**Honest limits.** The sweep is cryptographic hygiene, not media sanitization: unlinking does not erase
+data from wear-levelled flash, and the guarantee remains that the DEK is destroyed so surviving blocks
+are ciphertext. The routing decision itself is exhaustively unit-tested; its *delivery to the screen*
+(the Compose wiring, and behaviour across an Activity recreation) is verified by inspection, because
+this project has no Compose UI test infrastructure — tracked as follow-up work.
+
 ## Audit history
 
 See [AUDIT.md](../AUDIT.md). No third-party audits have been completed yet — treat the


• Good — no burn-related residue in apps/android. Check the whole repo for those symbols, and check onBurn vs main.---
893:    val onBurn: () -> Unit = {
912:                        PassphraseOutcome.Burn -> onBurn()
1453:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
786:    val onBurn: () -> Unit = {
805:                        PassphraseOutcome.Burn -> onBurn()
1358:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
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
A verified: onBurn is identical to main — same stub, no burn-wipe mechanism, no BurnCompletion symbols anywhere in the repo. No coupling residue (no `signalBurnCompleted` etc.). No `completeInterruptedBurn`/`reconcileOrphanedBurnMarkers` anywhere.

  Now verify C claims: `create()` writes DEK first, `destroy()` writes vault.delete-confirmed durably before unlinking. Need to read VaultImageStore.kt's create/retireLegacyImage/destroy. Let me read the full VaultImageStore.kt.  1514 apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
  1215 apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
  1655 apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
  4384 total
create() confirmed: DEK-first barrier, clears both markers durably first. Now read destroy(), retireLegacyImage(), markServerDeleteConfirmed, deleteLeftoverTmp, renameIntoPlace, dirSync, and open().destroy() writes confirmed marker durably before unlinking. Good — C verified regarding ordering.

  Now I need to check:
  - `deleteLeftoverTmp` implementation
  - `dirSync` 
  - `open()` behavior re leftover temps
  - The sweep itself once more — I already saw it in the diff.
  - MainActivity: enumerate ALL consumers of boot routing state, check other places that set `route =` — e.g. Route.Splash transitions, onboarding completion, DeleteIncomplete route, etc.

  Also key question for E: does anything else produce `{bin absent, dek/tmp present}` besides create/retireLegacyImage/destroy? E.g. biometric enable flow (PR-3 Unit 2 mentioned "second vault", biometric-enable atomicity e32f0aa). Check for other writers of dekFile / binFile / tmp: reseal (reseal writes image?), `reseal`? Let me search writers of binFile/dekFile.Writers of dekFile/binFile: create (539/546), retireLegacyImage (940-943), destroy (1111-1114), atomicWrite (1289 — who calls atomicWrite?). Let me check `reseal` — line 1289 is inside some function wrapping renameIntoPlace. Also check `open()` lines 339-400 regarding leftover temps. And find all callers of `atomicWrite` — e.g., a reseal on lock? That would write bin while vault is live (bin present — fine). But what about a reseal writing dek? Let me look at line 1280-1295 and search callers.Noticed: at line 1295 there's a stale orphaned comment — "/** Delete an incomplete-write temp for [target], if any. Best-effort. */" — followed immediately by another kdoc for `imageBearingFilesProvenAbsent`. Looking at the diff: the new code was inserted right after that comment line, which originally belonged to `deleteLeftoverTmp`. Now the comment "Delete an incomplete-write temp for [target], if any. Best-effort." dangles above `imageBearingFilesProvenAbsent`'s kdoc, and `deleteLeftoverTmp` at line 1430 has lost its kdoc. Minor cosmetic issue — stale/misplaced comment. Let me verify around line 1430.

  Also check who calls `atomicWrite` — `writeSealedPayload` — which writes what? A persist of an open vault (bin present). That only writes bin while vault is live; fine for the sweep gate.

  Now the writer/reader table (focus E). States with bin absent + dek/tmp present:
  1. interrupted create — covered.
  2. interrupted retireLegacyImage — covered.
  3. interrupted destroy — but destroy writes confirmed marker first, gate 2 refuses. Covered.
  4. open() deletes leftover temps at open time — a reader/healer but requires unlock. Not a boot issue.
  5. What about `writeSealedPayload` / reseal — writes bin while live. Interrupted reseal leaves bin.tmp + bin present — bin present, gate 1 refuses. Fine.
  6. What about crash between `dekFile.delete()` in the sweep itself? Sweep is idempotent.
  7. What about the biometric enable flow (0.9.2-beta biometric-enable atomicity e32f0aa)? Does it write dek? Search other writers of dekFile outside VaultImageStore — e.g., BiometricVaultKeyCipher? The wrapped DEK file is written only via renameIntoPlace(dekFile) in create per grep. Line 674-762 is attemptUnlockOrAdd add-path — does it write dekFile? Let me check lines 660-800 to see if the add-path writes dek/bin. Actually the grep shows renameIntoPlace only called at 539, 546, and 1289 (atomicWrite → writeSealedPayload). And atomicWrite target — who calls writeSealedPayload with which file? Let me check.

  Also need to check the delete-intent reasoning (E): "an intent is written while the image is still present" — markDeleteIntent writes intent first, before server request, while vault exists. destroy() writes confirmed before unlinks. create() clears both markers before writing DEK. So state {delete-intent present, bin absent, dek present} — could it arise legitimately? Interrupted destroy always has confirmed marker. Hmm — what about crash after destroy's unlinks but the markers: destroy clears both markers at the end. If crash between unlink of files and clearBothMarkersDurably: confirmed marker present → gate 2 refuses → DeleteIncomplete route re-runs destroy → clears markers. Fine.

  What about `markDeleteIntent` — written while image present. Could image become absent while only intent present? Only via destroy (writes confirmed first) or retireLegacyImage (writes/clears NO markers — but legacy image from 0.9.1 has no account, no markers... but could a delete-intent exist from a previous account that was never cleared? create() clears markers before creating. Hmm: account A deleted with intent only (crash mid-delete, server outcome unknown). Boot routes to unlock; reconcile retries DELETE; on confirmed → destroy. So intent-only + bin absent requires destroy, which writes confirmed first. So no legitimate {intent, no bin, dek} state... except: crash during destroy AFTER clearBothMarkersDurably? No — that's after files already gone and verified; state = everything absent. OK.

  But wait — one more: what if destroy() crashes between `writeDurableMarker(serverDeletedFile)` — marker present — and unlinks? Gate 2 refuses, DeleteIncomplete re-runs destroy. Fine.

  What about a legacy v2 image + delete-intent? 0.9.1 didn't have D2c, so no. And retireLegacyImage doesn't touch markers; if markers existed (shouldn't), gate 2 might refuse sweep of {dek, no bin} — but that's the safe direction.

  Edge: interrupted destroy that crashed during `writeDurableMarker` — marker present but maybe not durable? writeDurableMarker throws if not durable → destroy aborted with files untouched. If crash (power loss) mid-marker-write: marker may exist non-durably; gate 2 uses `!Files.notExists` → present → refuse → routes DeleteIncomplete → destroy re-runs. Fine.

  Now — the MISSING ROW hunt (E). What writers produce bin.absent + dek/tmp present?
  - create (row 1)
  - renameIntoPlace crash mid-dek-write (row 2)
  - crash between dek barrier and bin rename (row 3)
  - retireLegacyImage (row 1b)
  - destroy (row 7 via marker)
  - reseal/writeSealedPayload: writes bin while bin present. But wait — does writeSealedPayload write binFile only? If a crash happens during writeSealedPayload's renameIntoPlace, tmp leftover + bin present. open() deletes leftover temps. Fine, bin present anyway.

  Hmm — is there a writer that deletes bin but not dek without a marker? What about `clearBothMarkersDurably` — markers only. What about MainActivity/ZitroneApp-level file deletions? Search for other code touching vault files — e.g., ZitroneApp's retryPlaintextCacheClearIfNoVault deletes cacheDir, not vault dir. Also `wipeAppLocalState`? Not present (burn code removed). Search apps/android for "vault.dek" / "vault.bin" references outside VaultImageStore.

  Another candidate: biometric rewrap. BiometricVaultKeyCipher — does it store its wrapped key in vault.dek or a separate file? The dek file is the device-key-wrapped DEK. Biometric wrapped keys live elsewhere (BiometricUnlockStore). If biometric state references the DEK... the sweep deletes vault.dek when bin is absent — biometric wraps would be for a vault that's gone anyway.

  Now D: "strictly better than main" — on main, {bin absent, dek present} → hasVault() false → onboarding; a later create() overwrites the dek (require(!binFile.exists()) passes; create writes new dek). So on main the orphan dek is dead data overwritten by create. W-A sweeps it durably first. Also on main, {bin.tmp present, no bin} → onboarding; create → renameIntoPlace(bin) REPLACE_EXISTING overwrites; the tmp is overwritten by create's tmp write. So no state made worse. Verified once I confirm create() overwrites tmp — renameIntoPlace writes tmp fresh each time. Yes.

  One question: does the sweep's SWEPT_NOT_DURABLE → hold → LOCKED route make anything worse than main? On main, that state → onboarding. With W-A, user sees lock screen they can't pass (no vault). Hmm — the lock screen with no vault: what does the lock screen show? attemptPassphrase → store.open() → MissingImage → what outcome? Probably Rejected (fail-closed create?) Let me check attemptUnlockOrAdd behavior on MissingImage — the "fused unlock/maybe-create" — actually the lock screen may offer create. Let me check. Actually on the LOCKED route with no image, attemptPassphrase probably returns Rejected uniformly. The user would be stuck on a lock screen with no vault — but this only happens when the sweep couldn't prove durability (I/O fault) — fail-closed is the design intent. But is it "worse than main"? On main → onboarding → create overwrites residue. With W-A → user stuck at lock screen forever this boot, restart may fix. That's a deliberate fail-closed tradeoff, documented. INFO-level note maybe.

  Wait — actually, does Route.Locked with no vault offer a create path? attemptUnlockOrAdd is "unlock / burn-detect / maybe-create". If no image, maybe-create → it might create a vault from the lock screen! Let me read lines 660-800 to understand. If LOCKED route on no-vault lets the user create a vault (maybe-create), then the hold state isn't a dead-end. Let me read attemptUnlockOrAdd.

• Let me read the fused unlock/maybe-create path and the remaining parts of VaultImageStore.

• Interesting: `attemptUnlockOrAdd` — "reusing the EXISTING DEK — no dek write". The maybe-create path adds a vault into an EXISTING image. It doesn't write dek. But WAIT — line 694: `open()` throws MissingImage if no bin. So LOCKED route with no vault → attemptPassphrase → open() → MissingImage → probably mapped to some outcome in ZitroneApp.attemptPassphrase. Also `atomicWrite(binFile, outer)` — writes bin while live (bin present) — fine for the sweep gate.

  Hmm wait — but actually there's an important subtlety for the writer/reader table. `attemptUnlockOrAdd`'s create branch writes `atomicWrite(binFile, outer)` — an interrupted renameIntoPlace leaves `vault.bin.tmp` + bin present. open() cleans leftover tmp. Fine, bin present → gate 1 refuses sweep. OK.

  Now, the missing-row hunt: who else deletes `vault.bin` without the confirmed marker and without deleting dek? Search the whole codebase for deletes of binFile — only destroy and retireLegacyImage per grep (lines 940, 1111). And creates: only create(). Biometric flow: check BiometricVaultKeyCipher / BiometricUnlockStore — do they touch vault.dek? Search for "vault.dek" or DEK file usage elsewhere. Also check ZitroneApp for other file operations on the vault dir, and any "reseal" that rewrites dek? create writes dek via renameIntoPlace only once. Let me search.Only VaultImageStore touches bin/dek. The writer/reader table covers create, retireLegacyImage, destroy, renameIntoPlace crash, atomicWrite in attemptUnlockOrAdd (bin present during that write — gate 1 refuses). Good.

  Now check the misplaced comment issue at line 1295. Let me look at 1294-1300 and 1425-1440.Confirmed: at line 1295, the old kdoc `/** Delete an incomplete-write temp for [target], if any. Best-effort. */` — which belonged to `deleteLeftoverTmp` — was left stranded above the new `imageBearingFilesProvenAbsent` kdoc, while `deleteLeftoverTmp` (line 1430) now has no kdoc. Cosmetic only (LOW/INFO).

  Now F: enumerate every consumer of boot-routing state in MainActivity. Let me search for all `route =` assignments and `Route.Splash` references, and uses of `hasVault`, `vaultExists`, `serverDeleteConfirmed`, `isLegacyImage`, `bootRoute`.

• Now let me enumerate every routing consumer in MainActivity (focus F).

• Routing consumers found:
  1. Line 631: `vaultExists` initial value = `container.hasVault()` — a remember seed. This is read... where is `vaultExists` used in rendering? Line 619 comment mentions Splash → Locked keys on hasVault. Let me look at how vaultExists is used — e.g., LockedScreen shown when vaultExists. If initial route = Splash, vaultExists seed doesn't route anywhere directly until the boot decision effect. But are there rendering paths that use vaultExists before boot completes? Line 1288: Splash renders. Once route changes to Locked/Onboarding per decision, vaultExists was updated from the same snapshot. OK.

  2. Line 734: a LaunchedEffect — `!container.hasVault() && !container.serverDeleteConfirmed()` → vaultExists=false, route=Onboarding. What is this? Need to read context ~720-780. This looks like a pre-existing effect (maybe on session==null? or legacy detection). Line 734-739: a consumer that routes to Onboarding WITHOUT the carried verdict — this could be the "weaker consumer"! Need to check when it runs and whether it can fire before boot reconciliation. Let me read 720-780.

  3. Line 771-775: legacy detection → Onboarding. Fine (legacy present means present).

  4. Line 826-845: session-null handler — uses bootRoute with full inputs, but NOTE: it does NOT check bootReconciled! The session collector — if session.value becomes null before boot reconciliation finishes... At process start session.value == null initially. Does the session collector fire on the initial null? Need to read lines ~790-860. If it's a collector that skips the initial value or only reacts to transitions, fine. Let me read.

  5. Line 1159-1160: `vaultExists = container.hasVault(); route = if (!vaultExists && !serverDeleteConfirmed()) Onboarding...` — context? Line 1068 comment "hasVault() is then false, so route to Onboarding". Need to read around 1130-1170. This is likely the DeleteIncomplete auto-destroy completion path (after destroy() finishes, route Onboarding). In that context destroy has completed so residue... wait — after destroy, disk is clean, hasVault false. But residueSweepHold — if destroy succeeded durably, fine. But this consumer doesn't use bootRoute nor the carried verdict. However: it's the DeleteIncomplete path — destroy() completed → the account is gone and files deleted durably (destroy throws unless durable). Routing Onboarding there is keyed on destroy success, not on a fresh stat... but it re-stats hasVault() anyway. Also: could this effect fire pre-reconciliation? Read context.

  Let me read lines 600-880 and 1000-1250 carefully.Line 734 is inside onRetryDestroy (DeleteIncomplete retry) — gated on destroy completing, fine.

  Now read lines 740-880 (session collector, legacy effect, onUnlockSuccess), and 1000-1250.Consumer analysis so far:

  1. Splash decision effect (643-677): waits for splashFinished && bootDone, full inputs. ✓
  2. Recreation reconcile effect (679-717): waits for bootReconciled.first{it} — ordered after publication ✓; full inputs ✓.
  3. Legacy upgrade effect (768-778): runs on vaultExists seed + session==null. It does NOT wait for bootDone! It routes `route = Route.Onboarding` if legacy && route is Splash/Locked. Is this a consumer that runs before publication and routes off disk state? It routes to Onboarding only when a legacy image is PRESENT (isLegacyImage true requires bin+dek present). Onboarding over a present legacy image is the designed behavior (pre-existing, and bootRoute also maps legacy→ONBOARDING). But wait — it doesn't use the carried verdict. However, its precondition is image present, so residueSweepHold is irrelevant (hold only matters when image absent). Race: vaultExists seeded from hasVault() at composition. The sweep only deletes residue when bin is ABSENT — it never deletes a present legacy image. So this effect's condition (image present) can't be invalidated by the sweep. It can fire before bootDone, moving route Splash→Onboarding before the decision effect. Then the decision effect at line 645 returns early (route != Splash). Result: Onboarding — same as bootRoute would give (legacyImage → ONBOARDING). Consistent. OK — pre-existing behavior, and now consistent with bootRoute precedence 2.

     But subtle: this effect could fire before publication and set route=Onboarding. In the parent-unit failure class "consumer running before publication and reading a default" — here it doesn't read the verdict at all; it reads disk (legacy present), which the sweep cannot mutate (sweep refuses when bin present). Safe.

  4. Session collector (806-849): fires when session goes null while unlocked. `unlocked` starts true only if session non-null at composition; a cold start has unlocked=false, so the else-if branch can't fire before boot. A session going null mid-process (delete account, lock) — boot long done. It uses full inputs with carried verdict. ✓ Though: does it check bootReconciled? If session became null at a cold start... unlocked is false so the branch requires unlocked==true, meaning session was live. If a session was live, bootReconciled is presumably published (any session implies unlock after boot). Edge: rotation seeds unlocked=true from session non-null; session could go null immediately after (auto-lock?) while bootReconciled... if session was live, splash was skipped, but startBootReconcile still ran at composition; the sweep might still be in flight on rotation. Then the collector reads residueSweepHold.value — the DEFAULT false — before publication! Hmm. Is that reachable? Session live means an unlock happened, which means a previous composition completed boot (initial route was ChatList only if session survived from earlier composition; boot was started by the first composition). bootReconciled is process-scoped; could a session be live while bootReconciled is still false? The first composition starts reconcile immediately; to get a session the user must unlock (create/unlock), which requires leaving Splash, which requires bootDone... unless route was seeded ChatList from a live session — but a live session requires a previous unlock, which required leaving Splash, which required bootDone=true. So by the time any session exists, bootReconciled is true. Actually wait — could session be published without going through Splash? onCreateVault / biometric unlock paths all happen after routing away from Splash. And Route.ChatList seed requires session non-null. So transitively, session live ⟹ some earlier composition left Splash ⟹ bootDone was true. Except the very first process boot: session always null at process start (session is built on unlock). So the collector's null-branch with unlocked=true implies bootReconciled already true. Fine.

     Hmm, but actually there's an edge: `unlocked` seeded true on rotation from a live session; session goes null (account delete with NonCancellable) — boot was reconciled in the previous composition's lifetime... process-scoped, still true. ✓

  5. onRetryDestroy (727-744): post-destroy, keyed on destroy outcome. ✓ (destroys make disk clean durably or report failure).
  6. Line 859: onForcedLogout → Locked. Fine.
  7. Line 877: onUnlockSuccess → ChatList. Fine.
  8. Line 917-918: LegacyImage passphrase outcome → Onboarding. Present image, fine.
  9. Lines ~1010-1060: create completion → vaultExists=true → Locked. Fine.
  10. Lines 1130-1165: account-delete handler finally. Read that region.
  11. Line 1232: → Locked. Context needed.

  Also the second LaunchedEffect (679): note it does NOT check `route == Route.Splash` guard for DELETE_INCOMPLETE — it sets route to DeleteIncomplete from ANY route (if route != DeleteIncomplete). Could this stomp a live tree? Guard: only when session.value == null. If session is null, no live chat tree. But could route be Onboarding mid-create? container.session.value == null during create (session published at end). The effect runs once after bootReconciled.first{it}. If confirmed marker present, it routes DeleteIncomplete — correct, since a confirmed delete must finish regardless. ONBOARDING branch only moves Locked→Onboarding, never pulls back. What if route is Onboarding already and decision is LOCKED → Unit (no pull back). Hmm — boot decision effect (643) would have set route already on cold start; this second effect fires roughly the same time. On a cold start: route=Splash; both effects wait for bootDone. Decision effect requires splashFinished too. The second effect fires as soon as bootDone: if snap.route == LOCKED → Unit (route stays Splash, decision effect handles it). If DELETE_INCOMPLETE → sets route=DeleteIncomplete immediately, even before splashFinished. Then the decision effect returns early (route != Splash). Same destination. If ONBOARDING → only moves Locked→Onboarding; route is Splash → no-op; decision effect handles. Consistent.

  One more possible issue: the second effect (679-717) is LaunchedEffect(Unit) — fires once per composition. On a rotation AFTER boot (new composition), bootReconciled already true → runs immediately: session null (locked vault) → snapshot → vaultExists updated → route: if LOCKED → Unit (route seeded... on rotation with no session, route seeds to Splash! Then splashFinished=false until animation ends, then decision effect fires with bootDone=true → decides. OK consistent.)

  But wait — rotation seeds route = Splash when session==null, even if the user was mid-Onboarding creating a vault! Then decision effect fires (splash finished + bootDone) → bootRoute → if vault present now → Locked. Mid-create rotation: creating spinner re-attaches... this is pre-existing behavior (route seeded Splash on recreation). Not new to this unit.

  Now read 1000-1250 for remaining consumers.Line 1159-1160: onDeleteAccount's onConfirmed finally — routes Onboarding when `!hasVault() && !serverDeleteConfirmed()`. This runs after a completed destroy — which either succeeded durably or threw. If destroy threw (files survive) → hasVault true → DeleteIncomplete. If destroy succeeded → files durably gone + markers retired → Onboarding. This doesn't use the carried verdict — but the state transition is keyed on destroy()'s own durability proof (destroy throws unless re-stat + dirSync durable). The sweep hold is irrelevant: hold is only true when the boot sweep couldn't prove durability; but here destroy itself just proved durable deletion. Actually — wait: if residueSweepHold is true (sweep not durable at boot) and the user later deletes their account... for the user to delete an account they needed a vault and a session; the hold state implies no vault was proven... hold → LOCKED route → user can still unlock? No — hold with no vault means LOCKED screen with no vault, attemptPassphrase → MissingImage. Actually can a session exist while hold is true? hold=true means sweep may have unlinked residue without durability proof; vault image could still be present (hold only when gate passed with bin absent... no wait, hold is SWEPT_NOT_DURABLE, which requires gate 1 passed — bin proven absent). So hold=true ⟹ bin was proven absent at boot ⟹ no vault ⟹ no session possible (unlock impossible — open() throws MissingImage; maybe-create requires an existing image — attemptUnlockOrAdd.open() → MissingImage). So the session collector and delete handler never run under hold=true. Consistent.

  Hmm wait, but what does the LOCKED screen do when hold=true and no vault? User enters passphrase → attemptPassphrase → router → store.attemptUnlockOrAdd → open() throws MissingImage → mapped to what? Let me check ZitroneApp.attemptPassphrase / VaultUnlockRouter handling of MissingImage — outcome Rejected probably ("wrong passphrase"). The user is stuck but with an honest failure. This is the fail-closed design: hold the lock screen for the rest of the boot rather than show fresh install over possibly-resurrecting residue. Next boot: if journal replay resurrected nothing, sweep completes durably → ONBOARDING. Acceptable, documented. Note the user can NEVER create a vault while hold=true because routing pins LOCKED and Onboarding is unreachable — that's the intent.

  Edge: hold=true on EVERY boot due to a persistent dirSync failure (failing filesystem) → permanent lockout with no vault. Fail-closed availability cost, documented. INFO.

  Now line 631: vaultExists seeded from hasVault() at composition, used by veil logic (1218, 1225) and possibly LockedScreen rendering. Before boot completes, vaultExists could be true while route=Splash — veil Locked composes over? `lemonDropVeilState?.takeUnless { veilLockedPreOnboarding }` — if a Locked veil is queued and vaultExists seeded true (hasVault true), the veil could render during Splash? That's pre-existing lemon-drop behavior, not this unit's concern. But vaultExists seeded from hasVault (bin-keyed) — before sweep, could be true even when... vaultExists=true only means bin present; sweep never deletes bin. So the seed is never invalidated by the sweep. OK.

  Now what about `Route.Locked` rendering — does LockedScreen check vaultExists? Let me check the render section (1269-1330) and whether Route.Locked with no vault composes fine. Also check `Route.DeleteIncomplete` handler. And line 1653.

  Actually, more important for F: are there OTHER decision points that read `hasVault()` to route to Onboarding without the verdict? Line 1049-1053: create failure path routes Locked when hasVault — fine. Line 1159: post-destroy — keyed on destroy proof — fine.

  Now, the "second code path deciding the same thing" check: the legacy-upgrade effect at 768 routes Splash→Onboarding before bootDone. It's a real pre-publication consumer that CHANGES route based on disk state (isLegacyImage). Though its conclusion (legacy → Onboarding) matches bootRoute precedence 2, it bypasses the "nothing derives a route from disk until reconciliation finished" invariant in a benign case (bin present — sweep can't mutate it). Also it doesn't check bootDone at all. Is there a failure shape? isLegacyImage reads dek+bin and does outer decrypt — sweep refuses when bin present. Gate 1 is `Files.notExists(binFile)` proven absent. So no race with the sweep. And route move is Splash→Onboarding; decision effect then no-ops (route != Splash). But wait — what if the legacy check has a transient I/O error → getOrDefault(false) → no route change; decision effect later handles legacy→ONBOARDING itself. Consistent.

  Hmm, but one more: the legacy effect runs `if (vaultExists && session==null)` — vaultExists seeded true if bin present. During the window before sweep... bin present → sweep refuses. OK.

  Now line 1288-1330 render and DeleteIncomplete screen, line 1653. Also need to check `container.destroyVaultForAccountDeletion`, `container.createVaultAndPublish`, `retryPlaintextCacheClearIfNoVault` usage, and where startBootReconcile is called from besides the composition — e.g., ZitroneApp.onCreate? Search. Also who else calls sweepOrphanedResidue/obliterationComplete.

• Now let me check the render section and remaining container functions in ZitroneApp.

• All consumers enumerated. `sweepOrphanedVaultResidue()` (the public wrapper at ZitroneApp.kt:238) is never called by anyone — dead public wrapper? startBootReconcile uses `imageStore.sweepOrphanedResidue()` directly. The wrapper `sweepOrphanedVaultResidue()` has no callers — check tests. Also check whether `container.vaultProvenAbsent` is used anywhere else. The wrapper might be used in tests. Let me check.

  Now check `retryPlaintextCacheClearIfNoVault` — it runs afterPublish. Its gate: `primaryImageProvenAbsent()` (bin proven absent) — but that alone doesn't include dek/tmp... it clears the plaintext CACHE dir when bin absent. If bin absent but dek present (residue not yet swept — no wait, afterPublish runs after the sweep, and the sweep would have deleted dek/tmp or held). If sweep returned SWEPT_NOT_DURABLE (e.g., unlink of dek not durable), afterPublish still runs and clears the cache with bin proven absent. Is that OK? The cache is plaintext residue from prior sessions; clearing it when no vault image exists... but hold state means residue might resurrect after crash — vault.dek.tmp could resurrect, not vault.bin (bin was PROVEN absent before sweep — gate 1 — so bin can't resurrect unless... gate 1's notExists proves current absence, not durability; but nothing wrote bin recently, so no pending bin unlink to replay... actually a journal replay could resurrect vault.bin from destroy()'s unlink — but destroy writes confirmed marker first, gate 2 would refuse... hmm, gate 2 checks marker presence NOW; a crash could have happened between destroy's unlink and... destroy: marker durable → unlink → dirSync durable → clear markers. A crash between unlink and dirSync: marker present (durable) → gate 2 refuses. OK.)

  So clearing the plaintext cache when bin is proven-absent: is that fail-safe? The comment says "the fail direction is the safe one (over-clearing an OS-evictable cache)". If bin resurrects after crash (only via replay of a non-durable destroy unlink — but then marker also durable-present... unless markers were retired durably: destroy completed fully durably → files durably gone, no replay). What about retireLegacyImage: no markers! retireLegacyImage: bin.delete(); dek.delete(); verify; dirSync; throw if not durable. Crash between bin.delete() and dirSync where dirSync didn't happen → journal replay could resurrect bin! Boot: gate 1 — is bin present? After replay, bin present → sweep refuses → routes Locked → lock screen over a legacy v2 image → user enters passphrase → open() → LegacyImage → onboarding → retire. OK handled.

  But during the SAME boot before the crash: bin was deleted (unlink not durable), sweep ran, gate 1 passed, swept residue (dek), dirSync — if dirSync returned DURABLE then the unlink is durable, no replay. If NOT_DURABLE → hold → LOCKED. And afterPublish clears the plaintext cache of that possibly-resurrecting legacy vault! Hmm: cache clear happens even when hold=true. If crash → replay resurrects vault.bin (legacy image), but the plaintext cache was cleared. Is the cache needed? It's a cache — OS-evictable, regenerable. So no harm. The comment acknowledges over-clearing is the safe direction. OK.

  But wait — one real question: `retryPlaintextCacheClearIfNoVault` uses `primaryImageProvenAbsent` (bin only) not the full tristate. If bin is absent but dek present (e.g., sweep held but dek survived the unlink — SWEPT_NOT_DURABLE because imageBearingFilesProvenAbsent false after delete), clearing the cache while a stray dek exists — the dek alone opens nothing. Safe.

  Now check who calls `clearCacheDir` / whether there's a pre-existing plaintext-cache clear on main that this replaces/duplicates. Search clearCacheDir and plaintext cache in ZitroneApp.Interesting — ChatScreen.kt:247 writes camera captures to `context.cacheDir/CAMERA_CAPTURE_DIR` and QrDropDialogs.kt:482 writes to cacheDir "dropshare" — those are plaintext-adjacent files in cacheDir. `retryPlaintextCacheClearIfNoVault` clears the whole cacheDir when no vault. That's the "plaintext-cache clear" — wiping camera capture / dropshare residue when no vault exists. That's a hygiene op, and it's gated on proven bin absence. Fine.

  But note: `clearCacheDir(app.cacheDir)` deletes EVERYTHING in cacheDir — at cold start only (afterPublish after boot reconcile). If a vault EXISTS, gate refuses. OK.

  Now check the sweep race with in-flight camera capture? Cold start, before any UI — fine.

  Now let me look at the tests: BootReconcileOwnerTest, BootRouteTest, SweepOrphanedResidueTest. Then run the suite.

• Now let me read the three new test files.

• Note row 6b test: intent marker present, bin deleted, binTmp has real image, dek... wait — they create a vault, delete bin, write binTmp, intent present. dek still present (they didn't delete it). Sweep: gate 1 (bin absent ✓), gate 2 (confirmed absent ✓), residue present → deletes dek + tmps → SWEPT_DURABLE. Asserts binTmp gone, dek gone. OK.

  But hmm — the row 6b scenario: intent marker present + no bin + residue. Is this state actually legitimate in production? Per the design argument, intent+absent-bin never legitimately coexists, so sweeping is fine. The test constructs it artificially and asserts sweep proceeds. Fine.

  The VaultImageStore constructor with a custom dirSync — signature `VaultImageStore(dir, ops, cipher, fast, dirSync)`. OK, injectable dirSync used for the NOT_DURABLE test.

  One thing in row 5 test: symlink loop on vault.bin, dek present. Gate 1: Files.notExists(bin) — with ELOOP, notExists returns false (it returns false on IOException other than NoSuchFileException) → refuse NO_MUTATION, dek survives. Good.

  Now BootReconcileOwnerTest and BootRouteTest.Note: the test header at line 25 says "PUCKER BURN Unit W — the BOOT-OWNER LIFECYCLE CONTRACT (0.9.2, sweep-delta round 3)" — references Unit W / Pucker Burn, which is leftover naming from the parent unit. Minor staleness (INFO). Not a functional issue but the brief asks about stale references to the parent unit — this is a test header naming the parent unit. INFO.

  Also note the corrected claim in the second test — honest.

  One test-quality gap: `a claimant cancelled mid-work` — the sweep throws CancellationException from inside; the production code rethrows CE, then finally publishes hold=true. Test asserts released + hold. Good. But wait — in the test, the CancellationException is thrown by the sweep, not by actual coroutine cancellation; production code catches CE and rethrows → finally publishes → then the coroutine completes exceptionally with CE. In runTest, an uncaught CE in a child coroutine... `scope = this` (the runTest scope). A child launched coroutine throwing CancellationException — does that cancel the test? A CE in a launch child is treated as normal cancellation of that coroutine, not failing the test. OK.

  But production: `scope.launch` on AppContainer.scope — if the job is actually cancelled (scope cancelled), the withContext(ioDispatcher) raises CE inside; the inner try rethrows; finally publishes. But `publish` in production is non-suspending (StateFlow.value setters) — fine.

  One subtlety in runBootReconcile: after the `finally` publishes, `withContext(ioDispatcher) { afterPublish() }` runs — if the coroutine was cancelled, this withContext will throw CE immediately (non-suspending check) — afterPublish skipped. Fine.

  Another: if `sweep()` returns SWEPT_NOT_DURABLE due to exception, publish(hold=true) — good. If `publish` itself throws? Not handled — done never set. StateFlow setters don't throw. OK.

  Edge: runBootReconcile claim happens synchronously on caller thread; the sweep runs on the process scope. Fine.

  Now BootRouteTest.The last test computes `expected` by re-implementing the rule (`!c && (l || (!i && !h && p))`) — a test against a copy of the logic! Focus item I asks: "Is anything tested against a COPY of the logic rather than the logic itself?" Yes — `onboarding is reachable from exactly the expected input combinations` derives expected from a re-implementation. If both the implementation and the test's predicate drift together... well, it's a dual specification; a single mutation in bootRoute that widens onboarding would fail it unless mirrored in the test. But the previous test `full truth table` uses explicit expected values — that's fine. The 32-combination test is partially a copy-of-logic test. Its `expected` filter `!c && (l || (!i && !h && p))` — hmm, wait: is that predicate even right? bootRoute: onboarding when legacyImage (and !confirmed), or (!present && !hold && proven && !confirmed). Note legacy arm doesn't require !present. `l` with c=false → ONBOARDING regardless of i,h,p ✓. And `!i && !h && p` with l=false ✓. Correct copy — but still a copy. Note the prior test `full truth table` (16 combos, legacy=false) pins explicit values, and `a legacy image routes...` / `legacy outranks...` pin legacy cases explicitly. The 32-combo sweep adds coverage that a legacy-related widening would fail only if the copy isn't updated... it's a dual-implementation. MEDIUM/LOW test-quality note: the exhaustive test derives its expectation from a re-implementation of the rule rather than a fixed table; a refactor that changed BOTH (unlikely in a single edit) would pass silently. More precisely: the test fails on any single-sided change, so it still catches regressions in the function — it pins the function to the predicate. Actually as a regression test it works: any change to bootRoute that alters the onboarding set fails the test (since expected is computed by the frozen predicate). The copy lives in the test, frozen. So it's not vacuous — it pins behavior. The danger is only if someone edits both. It's acceptable but worth an INFO/LOW.

  Now — the earlier `full truth table` at line 187: `{confirmed=false, present=true, hold=true, proven=true}` → LOCKED. Wait: present=true, hold=true → LOCKED ✓ (present arm). Row `{false,false,false,true}` → ONBOARDING ✓.

  Check `a present image is always a lock screen` test at line 88: indentation glitch (cosmetic).

  Now, indentation oddity line 88 is trivial.

  Now run the test suite (H). Then check remaining items:

  - G: runBootReconcile contract verified against source and tests — done mostly; the tests inject scope/dispatcher, use StandardTestDispatcher.
  - D: strictly better than main — verify main's Splash routing. On main, SplashScreen onFinished routed: confirmed → DeleteIncomplete; vaultExists → Locked; else Onboarding. And vaultExists seeded from hasVault(). So {bin absent, dek present} → Onboarding on main. Then create() overwrites. Verify main MainActivity splash routing — I saw it in the diff (removed lines): yes, on main Splash's onFinished did that routing. ✓. Also on main there was no sweep, so {bin.tmp present, no bin} → onboarding; create would overwrite bin.tmp via renameIntoPlace's tmp write (tmp deleted/overwritten). Actually on main, create writes tmp then renames over... the leftover tmp gets overwritten by create's own tmp write. And dek overwritten. So no state made worse by W-A: W-A deletes residue durably at boot before onboarding — strictly better hygiene; and fail-closed LOCKED when durability unproven — availability cost vs main (main → onboarding). "No state is made worse" — in the non-durable case, W-A withholds onboarding where main would show it. Is that "worse"? It's fail-closed by design; the claim "strictly better" is about residue handling. I'd call the claim accurate with the documented availability tradeoff.

  One more check for E (missing row): biometric enable flow — does it write any file into the vault dir? BiometricUnlockStore — where does it persist wraps? Check its file location. If it stores a wrap file in the same baseDir (filesDir) — not in vault dir? The sweep only touches the 4 image-bearing files anyway. The question is whether some OTHER file in the vault directory could be image-bearing. e.g., BiometricVaultKeyCipher stores the biometric-wrapped vault key where? If in the same baseDir with a different name, the sweep wouldn't touch it (fine — it only deletes dek/bin/tmps), but `obliterationComplete` claims "the vault directory holds nothing image-bearing" — a biometric wrap of a vault key is arguably key material but not image-bearing (useless without... actually a biometric wrap + biometric auth could recover the vault key, but without vault.bin it's useless). Onboarding with a leftover biometric wrap: pre-existing behavior (destroy deletes biometric wrap; interrupted destroy leaves confirmed marker → DeleteIncomplete → retry destroys biometric too? Let me check destroyVaultForAccountDeletion — it deletes biometric wrap too. And the sweep doesn't touch biometric files. If create-interrupt leaves {dek, no bin} and a biometric wrap... biometric enable requires a live session post-create, so an interrupted create has no biometric wrap. OK.

  Where does the vault dir live — baseDir = ? Check VaultImageStore instantiation in ZitroneApp and whether biometric store shares the dir.

• Now let me check where the vault directory lives, the biometric wrap location, and ZitroneApp's container wiring.

• Biometric store persists via KeyStoreManager (Android Keystore + presumably prefs), not filesDir. So the vault dir (filesDir) image-bearing files are exactly the 4. But wait — other files live in filesDir too (Signal stores etc.? EncryptedSignalProtocolStore — "nothing key-shaped ever touches disk in plaintext" — where does it persist? Possibly in filesDir). `obliterationComplete` only checks the 4 image-bearing files, not the whole dir. The kdoc says "the vault directory holds nothing image-bearing" — the 4 files are the image-bearing set. Other encrypted stores are not image-bearing. Fine.

  Now `sweepOrphanedVaultResidue()` public wrapper — is it called anywhere? Grep showed only definition. Dead wrapper — LOW (unused public API). Check tests referencing it — tests call `newStore(dir).sweepOrphanedResidue()` on the store directly. So AppContainer.sweepOrphanedVaultResidue is dead code. Minor.

  Now run the test suite (H). That's a long gradle build — run in background.

• Source review is nearly complete. Now let me run the test suite independently (focus H) — this takes a while, so I'll run it in the background.

• While tests run, continue verification:

  - Check `git show main:` MainActivity splash routing for D claim (already saw removed lines in diff — main's Splash routed on vaultExists which was seeded from hasVault(); and the session collector on main). Confirmed from the diff's removed lines: main Splash onFinished: confirmed → DeleteIncomplete; vaultExists → Locked; else Onboarding. vaultExists seeded from hasVault() → bin-keyed. So {bin absent, dek present} → Onboarding on main. ✓ D verified.

  - G: verify runBootReconcile contract. Done reading. Contract: once-only via claim CAS ✓; publish in finally on every exit including cancellation ✓ (finally runs even on CE); fail-closed default SWEPT_NOT_DURABLE ✓; claim cannot be stranded — claim stays true forever, but publish always happens in finally so waiters released ✓. One edge: if `scope.launch` itself fails (scope cancelled) — then claim stays true and nothing publishes. AppContainer.scope is process-lifetime SupervisorJob; never cancelled in practice. Edge case: scope cancelled → launch returns a cancelled job, finally never runs, bootReconciled stays false → Splash waits forever. But process scope cancelled means process teardown. INFO at most.

    Another: `withContext(ioDispatcher)` — if the outer scope's job is cancelled before launch starts executing, the coroutine still runs? A launch on a cancelled scope: the coroutine starts in cancelled state; the `try` block's withContext throws CE immediately; finally publishes. Wait — does the code before `try` run? `var result = SWEPT_NOT_DURABLE` then try { withContext } finally { publish }. If coroutine is cancelled before first suspension, the body still executes until first suspension point — withContext throws CE → finally publishes ✓. Actually if job cancelled before starting, launch's block does NOT execute at all (undispatched cancellation: coroutine is cancelled, block never runs). Hmm — with default dispatch (Dispatchers.Default, undispatched start? launch default start is DEFAULT which dispatches; if the job is already cancelled, the coroutine is dispatched but completes without executing the block? Let me think: launch on a cancelled scope → the new coroutine's parent job is cancelled → child is cancelled immediately → the block... For `launch` with CoroutineStart.DEFAULT on a cancelled job, the block does not execute; the coroutine completes as cancelled without running. So publication wouldn't happen. But that requires AppContainer.scope cancelled — process death. Acceptable.

    In tests, cancellation is simulated by sweep throwing CE — the real rotation-cancellation path (composition cancelled) doesn't apply since reconcile is on the process scope. The comment says "a rotation that cancelled the claiming coroutine" — but the claiming coroutine lives on the PROCESS scope precisely so rotation can't cancel it. The finally still guards other cancellations. OK.

  - F deeper check: the second effect at 679 fires `container.bootReconciled.first { it }` — ordered after publication ✓. It uses residueSweepHold.value — read after publication ✓. The session collector reads residueSweepHold.value when a session goes null — boot long published ✓ (session implies boot done, argued earlier).

    Wait — actually one more check on the argument "session implies boot done": Route.ChatList seed at 624 requires session non-null. Can session be non-null at the FIRST composition of a process? container.session is built only via publishSession during unlock/create flows, which require user interaction on Onboarding/Locked screens, reachable only after route left Splash, which requires bootDone (decision effect) — or the legacy effect (768) which can move Splash→Onboarding BEFORE bootDone! There: legacy effect fires pre-publication, routes Onboarding, user creates a vault (retires legacy), session published — meanwhile startBootReconcile was called at composition (line 683) and the sweep ran on IO — probably completed, but no ordering guarantee? Actually the sweep and user creating a vault: user interaction takes seconds; sweep is ms. But formally: can create() race the sweep? create() requires !binFile.exists() — legacy image present → onboarding → OnboardingScreen's create calls createVaultAndPublish → does it call retireLegacyImage first? Yes, presumably (comment: "the create there retires it"). retireLegacyImage and sweep both take imageLock. Sweep gate 1: bin present (legacy) → refuses. No race. If retireLegacyImage completed first (bin+dek deleted), sweep could then run — find nothing → NO_MUTATION → publish no-hold. Fine either order. If sweep runs BETWEEN retireLegacyImage's bin.delete() and dek.delete() — both under imageLock, sweep takes imageLock → serialized. ✓

    And could the user reach Onboarding pre-publication via the legacy effect, create a vault, publish a session, then have the session collector... all fine because hold would be false anyway (sweep refused/no-op'd with image present).

    The dangerous pre-publication consumer would be one that reads residueSweepHold default=false and vaultProvenAbsent to authorize onboarding with bin ABSENT. The legacy effect only fires with bin present. ✓

  - Also verify there's no OTHER place that presents Onboarding: grep showed OnboardingScreen only at 1290. Route.Onboarding assignments: 675, 713, 739, 775, 845, 918, 1162. All checked.

  - Check `onUnlockBiometric` and Route.Locked handling — LockedScreen with no vault (hold state): attemptPassphrase on MissingImage. Let me check what attemptPassphrase returns for MissingImage — the outcome mapping (line ~927 comment "Rejected... fail-closed create"). Also biometric unlock with no vault: biometricUnlockAvailable = biometricEnabled && vaultExists? Check. If hold=true and a biometric wrap exists (vault was destroyed?) — biometric unlock over absent vault → open() MissingImage → honest failure. Let me quickly check attemptPassphrase mapping of MissingImage and biometricUnlockAvailable derivation.

• While the suite runs, let me verify the MissingImage outcome mapping and biometric gate in ZitroneApp.

• Build successful. Now get the actual test counts from the JUnit XML results.

• Build succeeded. Let me extract the actual test counts from the result XMLs.suites=57 tests=475 failures=0 errors=0 skipped=3 passed=472


• Matches the commit's claim: 475 total / 0 failures / 472 passed / 3 skipped. Which 3 are skipped? Check quickly, and also confirm the three new test classes ran.TEST-com.zitrone.app.BootReconcileOwnerTest.xml
TEST-com.zitrone.app.BootRouteTest.xml
TEST-com.zitrone.app.SweepOrphanedResidueTest.xml
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  <testcase name="probeReportsReady" classname="com.zitrone.app.I2pLiveIntegrationTest" time="0.001">
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml:    <skipped/>
--
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  <testcase name="healthzReturnsOkThroughTunnel" classname="com.zitrone.app.I2pLiveIntegrationTest" time="0.001">
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml:    <skipped/>
--
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml-  <testcase name="webSocketHandshakeReachesServer" classname="com.zitrone.app.I2pLiveIntegrationTest" time="0.0">
TEST-com.zitrone.app.I2pLiveIntegrationTest.xml:    <skipped/>
The 3 skipped are I2P live-integration tests (environment-gated, pre-existing). The three new suites ran: 8 + 9 + 11 = let me count: BootReconcileOwnerTest 8 tests, BootRouteTest 9, SweepOrphanedResidueTest 11. Verify counts from XML quickly. Then finish remaining verification: attemptPassphrase MissingImage mapping (line 497-540), and double-check the "verify every claim" bits:

  1. ZitroneApp kdoc line 1178 area — bootRoute kdoc — read fully to check for false claims.
  2. Verify BootReconcileOwner/BootRoute/Sweep test counts in XML.
  3. Also check `git show main:` for ZitroneApp to confirm retryPlaintextCacheClearIfNoVault is new and whether a prior cache-clear existed elsewhere (it's new; "retry" naming implies a prior clear exists — where? maybe in burn code that was cut. Grep main for clearCacheDir: nothing — the grep over current main source showed only new code. On main, was there a plaintext-cache clear at all? Search main for it. If none, "retry" naming is stale — INFO.

  Let me read attemptPassphrase mapping and bootRoute kdoc region.tests="8"
tests="10"
tests="12"
MissingImage → ImageUnreadable (honest distinct error) — so in the hold state the lock screen shows the "damaged image" note, which is slightly misleading for "no vault at all" but honest-ish. Actually IMAGE_UNREADABLE_NOTE for a device with no vault says the image is unreadable — in the hold state there IS no image. Hmm, minor UX honesty note, but the hold state is a fail-closed corner; acceptable. INFO at most.

  Test counts: BootReconcileOwnerTest=8, BootRouteTest=10, SweepOrphanedResidueTest=12. Matches the files (BootRouteTest has 10 @Test? Count: clean→onboarding, non-durable, unswept residue, present always locked, confirmed outbids, confirmed outbids legacy, legacy→onboarding, legacy outranks, full truth table, onboarding reachable = 10 ✓. Sweep: rows 1,2,3,4,6,6b,5/8,5,9,non-durable,idempotent,converts = 12 ✓. BootReconcile 8 ✓.)

  Now remaining checks:
  1. Read bootRoute kdoc + runBootReconcile in final source form (ZitroneApp.kt lines ~1100-1215) — I saw it in the diff. Verify comment claims: "publication in finally on every exit including cancellation" ✓ seen.
  2. Check stale kdoc on destroy() — there are TWO consecutive kdoc blocks at 984-1015 and 1016-1031 before markDeleteIntent. Wait — lines 984-1015 are the destroy kdoc, but it's placed BEFORE the two-phase marker kdoc + markDeleteIntent/markServerDeleteConfirmed/clearDeleteIntent/clearBothMarkersDurably/writeDurableMarker, and destroy() is at 1092. So the destroy kdoc (984-1015) is detached from destroy() — pre-existing layout? Check main to see if this diff moved things. The diff didn't touch those regions (the diff only added around 1293-1430 and 138-165, 289-301). Pre-existing; not this unit's problem. Skip.
  3. The misplaced comment at 1295 (from this diff) — confirmed the diff inserted the new kdoc between `/** Delete an incomplete-write temp... */` and `deleteLeftoverTmp`... actually the diff shows: the context line `/** Delete an incomplete-write temp for [target], if any. Best-effort. */` then the new `imageBearingFilesProvenAbsent` kdoc was inserted right after it, and `deleteLeftoverTmp` now sits at 1430 WITHOUT its kdoc. Yes — extraction artifact: deleteLeftoverTmp lost its kdoc; the stray kdoc now floats above an unrelated function. LOW.

  4. Dead public wrapper `AppContainer.sweepOrphanedVaultResidue()` — no callers. LOW/INFO.

  5. Test headers: BootReconcileOwnerTest header says "PUCKER BURN Unit W — ... (0.9.2, sweep-delta round 3)" and BootRouteTest header "PUCKER BURN Unit W — ... (sweep-delta round 1, Codex)". These name the parent unit W / Pucker Burn — stale naming in an extraction that is supposed to be W-A standalone. INFO (cosmetic).

  6. BootRouteTest `onboarding is reachable...` — expected derived from a re-implementation of the predicate. It pins behavior (single-sided changes fail) but it IS a copy-of-logic test. The focus item I asks to name it. Also `full truth table` is explicit. I'd call the 32-combo test acceptable but flag the dual-implementation. LOW/INFO.

  7. Vacuous tests? `rows 5 and 8 - refuses when the base directory cannot be stat'd` — the header itself honestly states it's weak and mutation-verified as NOT catching the fail-open gate; kept "for coverage of the shape". This is a test that passes vacuously w.r.t. gate-1 strictness (a `File.exists()` gate also passes it). The header discloses this and the load-bearing version exists (`row 5 - an unstattable image must not cost a live vault its DEK`). So disclosed, not hidden. INFO — could arguably be removed, but honest.

  8. Untested failure shape (I): What's still untested?
     - The Compose wiring (splash gate, carried verdict into the two boot effects) — no Compose UI test infra; docs disclose this. The decision-function is tested but delivery is inspection-verified.
     - The `obliterationComplete`/`primaryImageProvenAbsent` tristate under indeterminate stat? Row 5 covers sweep gate 1. `obliterationComplete` itself with indeterminate stat — not directly tested, but same Files.notExists idiom.
     - SWEPT_NOT_DURABLE when residue survives the unlink (delete returns false) — hard to simulate; not tested. The non-durable-dirSync path is tested. The "surviving residue → SWEPT_NOT_DURABLE" branch (line 1417) is untested — a File that can't be deleted (read-only dir would make delete fail... actually on Linux, delete in a read-only directory fails; test could chmod). Untested branch.
     - clearCacheDir: untested entirely (new internal function). Its fail-closed listFiles-null path untested.
     - retryPlaintextCacheClearIfNoVault gating: untested.
     - afterPublish ordering (hygiene after gate opens): untested.
     - runBootReconcile's afterPublish running after publish: untested.
     
     The most notable untested shape: the verdict-to-screen delivery (Compose) — disclosed in SECURITY_MODEL.md. And the sweep's "mutation happened but re-stat still sees residue" branch.

  9. Verify the claim in bootRoute kdoc "No parameter carries a default" ✓ (signature has no defaults).

  10. Verify E's "missing row" hunt more: are there writers that can leave {bin absent, dek/tmp present, delete-intent present (no confirmed)}? 
      - Interrupted create after a previous account's intent-only crash: create() clears BOTH markers durably FIRST, before writing dek. If crash between clearBothMarkersDurably success and dek rename: no markers, no bin, maybe dek.tmp/dek → rows 1-3. ✓ covered.
      - Crash DURING clearBothMarkersDurably (markers deleted, dirSync not durable): journal replay could resurrect markers + nothing else. Boot: gate 2 sees marker present (if replayed) → refuse → DeleteIncomplete route → auto-destroy → destroy re-runs idempotently on empty dir → clears markers. If not replayed: markers absent → sweep proceeds. Either way consistent. ✓
      - retireLegacyImage + pre-existing markers: a 0.9.1 install can't have D2c markers (0.9.1 had no delete markers — verify? markers introduced in 0.9.2 round 13; a 0.9.1 install upgrading has no markers; a 0.9.2 install that created then has v3 image, not legacy). Could a legacy image coexist with an intent marker? Only if... account created in 0.9.1, upgraded to 0.9.2, initiated delete before unlocking? Delete requires session → unlock → LegacyImage → can't unlock. So no. And even if: sweep refuses only on CONFIRMED marker; intent + no bin + residue → sweeps (row 6b test asserts this is desired). Is that SAFE though? Intent marker means "delete initiated, server outcome unknown; reconcile owed". If the sweep deletes a stray dek/tmp while an intent is pending and no bin — the vault is already gone (no bin); the residue being swept doesn't change the reconcile obligation (the intent marker survives; post-unlock reconcile needs a session, which needs a vault... with no vault, the intent reconcile can never run — but that was already true before the sweep). The intent marker itself is preserved by the sweep. Onboarding then requires proven absence of the 4 image-bearing files — markers don't block onboarding. Create() clears markers durably first. Hmm — wait: bootRoute with {intent present, no confirmed, no bin, clean dir} → not confirmed, not legacy, not present, not hold, provenAbsent → ONBOARDING. Onboarding → create → clears the intent marker. The orphaned server account (intent never reconciled) is abandoned — but that's pre-existing main behavior too (main also routed to onboarding on no-bin). ✓ not worse.

  11. D claim — also confirm main routed {bin absent, dek present} → onboarding: main's Splash onFinished used vaultExists (seeded hasVault) → false → Onboarding ✓ (from the diff removed lines).

  12. One more F check: `BootDecision.present` used to set vaultExists. In the second effect (679), vaultExists = snap.present && !snap.legacy — consistent.

  13. Check `obliterationComplete` kdoc claim "the ONLY predicate that may authorise a fresh-install presentation" — but bootRoute's ONBOARDING via legacy arm doesn't use it; fine, that's via present image.

  14. Check for any OTHER callers of bootRoute outside MainActivity — only the 3 sites ✓ (grep). All pass all five args ✓.

  15. `afterPublish` uses Dispatchers.IO via withContext AFTER the finally — but note: if the coroutine completing normally, afterPublish runs on ioDispatcher. In production, retryPlaintextCacheClearIfNoVault gates on primaryImageProvenAbsent — after a SWEPT_NOT_DURABLE hold, the cache clear still runs. Discussed; safe direction.

  One more potential issue in runBootReconcile: `publish(result == SWEPT_NOT_DURABLE)` — the publish lambda in production sets residueSweepHold THEN bootReconciled. Ordering hold-before-done ✓ (test header admits it can't test this but .value readers are safe due to publication before waiters released... actually for `.first { it }` waiters on bootReconciled: they resume after done=true is set, then read hold.value which was set before — ✓ correct ordering in production publish).

  But WAIT — one subtle StateFlow conflation issue: `bootReconciled.first { it }` in the second effect and `collectAsState` in the decision effect — both observe done=true only after hold is set. ✓

  16. Cancellation of the sweep's withContext while sweep() runs on Dispatchers.IO: sweep is synchronous non-cancellable (imageLock.withLock, file I/O) — a cancellation lands at the withContext boundary after sweep completes. Then CE propagates, finally publishes result (which was set!). Wait — if sweep() completed and assigned result=SWEPT_DURABLE, then withContext's boundary throws CE before returning? The assignment `result = try { sweep() } ...` — if CE is thrown at the boundary after sweep() returned, is the assignment complete? `result = try { sweep() } catch...` — the try expression's value is assigned after the try completes; a CE at the suspension boundary on return from withContext happens... Actually the withContext block: `result = try { sweep() } catch ...`. sweep() is non-suspending, so no cancellation check inside; assignment completes; withContext returns; the NEXT suspension point... there's none before finally. So cancellation lands at the outer withContext(afterPublish) — result already assigned, publish already ran in finally with the real verdict. Test `cancellation after a durable sweep does not invent a hold` covers the intent (sweep throws CE inside vs after). Fine.

  17. `runBootReconcile` publishes hold=true when sweep threw a genuine fault → LOCKED for rest of boot ✓ fail-closed.

  18. Is there any path where bootReconciled NEVER publishes in production (besides process-scope death)? startBootReconcile is called from the composition's LaunchedEffect(Unit) — if the composition never forms (e.g., app opened directly into an Activity that... MainActivity is the launcher; ZitroneRoot always composes). Also `container.session.value != null` seeds ChatList but startBootReconcile still runs (line 683 unconditionally). ✓

  19. Now double-check the kdoc claim in sweepOrphanedResidue: "Touches NO in-memory state (no dek wipe, no canonical drop, no unregister): gate 1 proves there is no image, so this store cannot hold an open one". Is that true? If this VaultImageStore instance holds dek/canonical in RAM while bin is absent... can that happen? destroy/retireLegacyImage wipe RAM. close() wipes RAM. A failed open() wipes. Could bin be unlinked externally while the store is open? External deletion isn't a scenario. But: create() failure AFTER dek durable barrier + bin rename landed but dirSync NOT_DURABLE → throw NotDurable — catch wipes liveOpen, but `dek`/`canonical` in-memory: were they installed? No — install happens only after both dirSyncs (line 560-562). Hmm wait, in the NotDurable-after-bin-rename case, bin EXISTS on disk and store has no RAM state. Fine.
      What about attemptUnlockOrAdd's create branch: atomicWrite(binFile) then canonical=newInner, then if NOT_DURABLE throw — canonical IS installed and bin exists on disk. bin present → gate 1 refuses. ✓
      So "this store cannot hold an open one when bin is proven absent" — one more path: reseal? Where's the reseal/persist that writes bin on lock? VaultRuntime.close reseals — writes bin via? Probably writeSealedPayload → atomicWrite(binFile) — bin present throughout. OK.
      Edge: TWO VaultImageStore instances? Single-instance contract via OPEN_PATHS. The sweep runs on the container's instance. ✓
      Also the sweep does NOT unregister — it's called at boot when the store was never opened; registeredPath null. ✓ claim holds.

  20. kdoc claim in sweep: "It proves the result by RE-STAT and requires a durable dirSync" ✓ matches code.

  21. kdoc claim row 2: "crash inside renameIntoPlace(dekFile)" — a crash inside renameIntoPlace leaves dek.tmp only if crash between tmp write and rename; but renameIntoPlace's catch deletes tmp on failure; a hard crash (power loss) mid-write leaves tmp ✓.

  22. Row 3 claim: "crash between the DEK barrier and bin's rename" leaves {dek durable, bin.tmp}: wait — crash between dek barrier (dek renamed durable) and bin's renameIntoPlace: bin.tmp may exist (staged, fsynced) but not renamed. So {dek, bin.tmp, no bin} ✓. The sweep deletes all → loses a never-completed vault. Policy consistent with open() deleting leftover temps ✓.

  23. E both directions: wrongly DELETES — is there ANY legitimate state with {bin proven absent, dek or tmp present, no confirmed marker} where the dek/tmp is still NEEDED? 
      - The biometric-enable atomicity commit (e32f0aa "closes the disclosed orphan gap") — biometric enable writes? BiometricVaultKeyCipher wraps the vault key and BiometricUnlockStore persists via KeyStoreManager — not filesDir. But wait — does biometric enable rewrite vault.bin (to add a dual-wrap slot)? Let me check enableBiometricFromSession — if it rewrites the image via atomicWrite(binFile), an interrupted one leaves bin.tmp + bin present (gate 1 refuses ✓).
      - What about a live vault whose bin was deleted by the OS or user (filesDir is app-private; no).
      - Reseal during session teardown: runtime.close() reseals — writes bin via atomicWrite while old bin present (REPLACE_EXISTING) — bin present throughout ✓.
      So no legitimate state is wrongly deleted. Row coverage: writers of dek/tmp without bin: create (1), retire (1b), renameIntoPlace crash (2), create crash between barrier and bin rename (3), destroy (7 via marker), atomicWrite-on-live-vault (bin present, row 4). Marker-indeterminate (8). Complete.
      
      Wrongly STRANDS: sweep refuses when bin present/indeterminate or confirmed marker present/indeterminate. Could residue be stranded that no other path heals? {bin present, bin.tmp present} — open() deletes leftover temps at unlock ✓ healed. {confirmed marker, residue} — DeleteIncomplete → destroy heals ✓. So stranding is covered by other owners. ✓

  24. The delete-intent gate reasoning — verified against destroy() (confirmed marker durable BEFORE unlinks, line 1108) and create() (clears both markers before dek write, line 505-510). One hole: markDeleteIntent is called, then crash, leaving {intent, bin present} — sweep refuses via gate 1 ✓. Intent + no bin legitimately? destroy always writes confirmed first; destroy's clearBothMarkersDurably clears BOTH at the end. So {intent only, no bin} reachable? destroy: intent present → confirmed written → unlinks → markers retired together. Crash after marker-retire of confirmed but... clearBothMarkersDurably deletes intent first (line 1068: deleteIntentFile.delete() then serverDeletedFile.delete()) — crash between those two deletes: {confirmed present, no intent, files gone} → gate 2 refuses → DeleteIncomplete → destroy re-runs, files already gone, re-stat passes, dirSync, clearBothMarkersDurably again → done ✓. Crash between unlink phase and marker phase leaves {intent + confirmed, no bin, maybe residue} → gate 2 refuses ✓. So {intent WITHOUT confirmed, no bin} is unreachable in the D2c machine ✓ — the no-intent-gate reasoning holds. EXCEPT: crash DURING destroy between writeDurableMarker(serverDeletedFile) fsync... if writeDurableMarker throws (not durable), destroy aborts with files untouched + intent present + maybe a non-durable confirmed marker that a journal replay could DROP: replay → {intent only, bin present} → gate 1 refuses ✓ fine.

      What about retireLegacyImage leaving {intent, no bin, dek}? retire doesn't touch markers; a legacy install has no markers. But consider: 0.9.2 install, delete intent written (bin present), then... no path removes bin without confirmed. ✓

      So the intent-gate omission is sound.

  25. G tests: BootReconcileOwnerTest — do they cover publication in finally on a genuine coroutine cancel (job.cancel()) rather than a CE from sweep? The CE-from-sweep simulates it adequately for the code path (both flow through the same finally). The true cancellation-during-withContext(IO) also hits the same finally. ✓

  26. One thing to double check — `runBootReconcile`'s `afterPublish` runs OUTSIDE the finally, so if publish throws... publish is StateFlow setters, won't throw. If afterPublish throws, it's inside withContext on the launched coroutine — uncaught exception on process scope (SupervisorJob) → logged, doesn't affect published state ✓ and production wraps afterPublish body in runCatching ✓.

  27. Check `git diff` for any remaining pieces of MainActivity I haven't reviewed: lines 85-88 import change (flow.first) ✓ used at 686.

  28. BootRouteTest line 88 indentation — cosmetic.

  29. One more possible defect: in the second effect (679-717), `if (container.session.value == null)` is checked BEFORE withContext, but the route application happens after the suspend — a session could publish during the IO snapshot (user unlocked... can't unlock while on Splash). If a session published concurrently (e.g., biometric unlock from veil? veil composes only... `lemonDropVeilState?.takeUnless{...}` — the veil CAN compose over Splash? Line 1237: veil composes whenever lemonDropVeilState non-null and not (Locked && !vaultExists). During Splash with vaultExists=true, a Locked veil can compose and offer biometric unlock! Then session publishes while route=Splash. Then the decision effect (643) fires (bootDone && splashFinished): route==Splash still → decides → Locked/... → route changes. Meanwhile session collector (806): live != null && !unlocked → route = ChatList ✓ reconciles. And the second effect's post-suspend application: ONBOARDING branch only moves Locked→Onboarding — if route is now ChatList, `route == Route.Locked` false → no-op ✓; DELETE_INCOMPLETE branch sets DeleteIncomplete unless already — if a session is live and marker confirmed... could stomp ChatList into DeleteIncomplete? Session live + confirmed marker: the session was just unlocked; if a confirmed marker is present, boot decision would have routed DeleteIncomplete and DeleteIncomplete's destroy would delete the vault... but the session unlocked BEFORE the decision? Timing: veil biometric unlock during Splash — session publishes; effect's snapshot: c=confirmed (true) → DeleteIncomplete → routes there even though session live → auto-destroy under a live session?? Hmm. Wait — the second effect checks `container.session.value == null` BEFORE the suspend; session publishes during withContext; after suspend it applies DeleteIncomplete without re-checking session. Then Route.DeleteIncomplete auto-runs onRetryDestroy → destroys the vault under a live session!
      
      Is this reachable? Preconditions: a CONFIRMED delete marker present AND a live vault AND a queued lemon-drop Locked veil AND biometric enabled AND user unlocks via veil within the ~1 MiB decrypt window. Confirmed marker + live vault is exactly DeleteIncomplete state (crash mid-destroy... but destroy deletes bin; confirmed + bin present means destroy crashed between marker write and bin unlink — reachable, that's what DeleteIncomplete exists for). Biometric unlock in that state: LockScreen/biometric would open the vault (it's valid). Actually even WITHOUT the veil: the decision effect routes to DeleteIncomplete; the session collector... The race window is tiny (the IO snapshot duration) and requires the veil-unlock to land precisely inside it. But note the session collector would ALSO fire: live != null, !unlocked → route = ChatList, racing the DeleteIncomplete write. Whichever writes last wins — genuinely racy. Compare with the decision effect (643): it re-checks `route != Route.Splash` after the suspend — but route is still Splash when the veil unlock happens (veil unlock sets unlocked=true, route=ChatList via onUnlockSuccess! onUnlockSuccess at 873 sets route = Route.ChatList). So after veil unlock, route==ChatList → decision effect no-ops ✓; session collector sets ChatList ✓; but the SECOND effect (679) has NO post-suspend route guard for DELETE_INCOMPLETE — it applies `if (route != Route.DeleteIncomplete) route = Route.DeleteIncomplete` regardless of route==ChatList and regardless of the now-live session. That would stomp ChatList → DeleteIncomplete → auto-destroy a vault whose session is live.

      Wait, but hold on: can the veil biometric unlock even happen during that window? veil composes at line 1237 — this is AFTER the `if (biometricEnrollOffer...) return` and the veil return at 1265. The veil composes regardless of route (it's an overlay rendered before the Crossfade). lemonDropVeilState Locked + vaultExists true → veil renders → user taps → onUnlockBiometric → session publishes → onUnlockSuccess → route=ChatList. The second effect started at composition, awaited bootReconciled.first{it} — fires right at publication — then does the IO snapshot (~ms, includes isLegacyImage only if present && !confirmed; with confirmed it's skipped). The race: user must tap biometric and complete unlock within the window between the snapshot's `container.session.value == null` check and the route application — that window is the withContext(IO) duration, milliseconds to tens of ms. Human tap can't land that fast after publication... but the biometric prompt could have been triggered BEFORE boot published (veil was already showing during Splash while sweep ran). Then unlock completes → onUnlockSuccess runs... onUnlockSuccess sets route=ChatList. Order matters: if session publish → ChatList happens BEFORE the second effect's snapshot, session.value != null → effect skips ✓. If it lands between the null-check and route application (a few ms), the effect stomps to DeleteIncomplete and starts an auto-destroy of a live-session vault. THEN the session collector fires (live != null, unlocked=false→ sets unlocked=true, route=ChatList) — could re-stomp back to ChatList, but destroy already launched via the DeleteIncomplete LaunchedEffect(Unit) { onRetryDestroy() }... destroyVaultForAccountDeletion deletes the vault under the live session. Session then operates on a deleted vault (reseal on close recreates?? runtime.close() reseals → writes bin again → vault resurrected with markers gone... messy).

      Likelihood: requires {confirmed marker + live vault} (crash mid-destroy state) AND biometric-enabled AND a queued lemon-drop Locked veil AND unlock completing in a ~ms window. Extremely narrow but it's precisely the parent unit's failure class (a consumer applying a stale pre-suspend decision without re-checking). The decision effect got the re-check (line 670); the second effect got a route guard only for the ONBOARDING arm, not for DELETE_INCOMPLETE. Severity: the preconditions are so narrow (biometric veil unlock during the ms-scale snapshot window in a crash-mid-destroy boot) that I'd rate LOW, maybe MEDIUM given the destructive consequence. Note the same stomp applies without the veil: can onUnlockBiometric run from Route.Locked during that window? Route is Splash at that point; LockScreen not composed. Only the veil path. Also onForcedLogout? Requires session. Also, can session publish from a process-scoped worker? createVaultAndPublish runs from onCreateVault (Onboarding screen — not composed yet). attemptPassphrase from LockScreen (not composed). So only the veil overlay. I'll report as LOW/MEDIUM with precise reasoning. Actually, let me double-check `onUnlockBiometric` — is biometricUnlockAvailable gated on route? Let me look at the veil + biometric path quickly.

  Also note: the second effect's DELETE_INCOMPLETE arm lacking a route guard is arguably intentional ("resume finishing the destroy outbids a stale route"), but with session live it's wrong. Given session.value == null was checked pre-suspend, a post-suspend re-check of session/route is missing. Let me verify biometricUnlockAvailable and the veil unlock path.

• The suite matches the claimed numbers. Now let me examine one race candidate in the second boot effect — the veil biometric unlock path.

• Confirmed: the veil overlay composes independent of route (line 1237 onward), and its Locked CTA calls onUnlockBiometric → onUnlockSuccess sets route=ChatList. The second boot effect (679-717) checked `session.value == null` BEFORE the IO suspend and applies DELETE_INCOMPLETE after the suspend with no re-check of session or route (unlike the decision effect which re-checks `route != Route.Splash` at line 670, and unlike its own ONBOARDING arm which only moves a stale Locked forward).

  Reachability: needs a boot into {confirmed marker + present vault} (crash between destroy()'s marker write and its unlinks — the exact state Route.DeleteIncomplete exists for), biometric enabled, a queued lemon-drop Locked veil, and the veil biometric unlock completing inside the ms-scale IO window. On success: session live + route=ChatList stomped to DeleteIncomplete → LaunchedEffect(Unit){ onRetryDestroy() } → destroyVaultForAccountDeletion() deletes vault.bin/dek under a live session. The vault is account-deleted server-side anyway (confirmed marker means the account is provably gone!), so destroying it under the live session is... actually semantically CORRECT for D2c! The confirmed marker means the server account is gone and local destroy is owed. The biometric veil unlock just unlocked a vault whose account is already deleted — the destroy under the live session is precisely what DeleteIncomplete does anyway, milliseconds later, via the decision effect.

  Wait — so even in the normal flow (no veil), the decision effect routes to DeleteIncomplete and onRetryDestroy destroys the vault. If a veil unlock lands first and creates a session, destroying under the live session: the session's runtime.close() reseal... onRetryDestroy calls container.destroyVaultForAccountDeletion() without tearing down the session first. destroy() wipes dek/canonical in the store, deletes files. The live session holds its own VaultSession with payload plaintext; a later reseal (runtime.close on lock) would re-encrypt and rewrite vault.bin — resurrecting a vault for a deleted account with markers gone → then boot: bin present, no markers → Locked. Account gone server-side, vault usable locally. That's a D2c integrity break but only in this vanishingly narrow race, and the "damage" is a locally-usable vault for a server-deleted account — arguably what the user wants anyway, but it violates the marker invariant (confirmed marker retired, vault present — the exact state destroy's ordering exists to prevent). Also the racing session collector would set route=ChatList concurrently — outcome nondeterministic.

  Severity assessment: the preconditions are extreme (crash-mid-destroy boot AND lemon-drop veil queued AND biometric enabled AND unlock lands in a ~ms window). Impact: invariant violation (vault resurrected for a deleted account), not a silent vault loss. I'll rate LOW (MEDIUM would be for plausible reachability). This IS in the parent unit's failure class (post-suspend decision applied without re-checking), worth reporting.

  Also note the symmetric point: the same stomp can happen via `onUnlockSuccess` from the veil while route==Splash in the DECISION effect — that one re-checks route != Splash post-suspend ✓ handled.

  Now also double check: in the second effect, the DELETE_INCOMPLETE arm has guard `if (route != Route.DeleteIncomplete)` — that's only idempotence, not a staleness guard. The ONBOARDING arm has `if (route == Route.Locked)`. The LOCKED arm no-ops. So the fix: post-suspend, re-check `container.session.value == null` (or apply DeleteIncomplete only when no live session). Report.

  Now check remaining: `git show main:` ZitroneApp to make sure nothing else was removed/changed beyond additions (the diff showed only additions in ZitroneApp except imports — from the diff hunk headers: only additions, yes).

  MainActivity diff: I reviewed all hunks. 

  SECURITY_MODEL.md additions: consistent with code? "It runs only when the image is proven absent and no vault.delete-confirmed marker is present or indeterminate" ✓. "If the sweep cannot complete (an I/O fault, a refused gate), or completed without a durable sync, the lock screen is held for the rest of that boot" — careful: a REFUSED gate (NO_MUTATION) does NOT hold the lock screen; NO_MUTATION → no hold → routing proceeds on vaultProvenAbsent. If gate refused because bin present → Locked (correct, not "held"); if gate refused because confirmed marker → DeleteIncomplete. "Cannot complete (I/O fault)" → sweep throws? sweep catches everything → SWEPT_NOT_DURABLE → hold ✓. Hmm "a refused gate" → hold is inaccurate: refused gates return NO_MUTATION (no hold) — but then vaultProvenAbsent is false (residue present) or confirmed present, so bootRoute → LOCKED/DELETE_INCOMPLETE anyway. Case: gate 2 refuses (marker present) → DeleteIncomplete, not lock screen. Case gate 1 refuses (bin indeterminate): NO_MUTATION, no hold; hasVault (File.exists) also false on indeterminate → present=false; vaultProvenAbsent false → LOCKED ✓ "lock screen held" ✓. So the doc sentence is roughly right in outcome though imprecise about mechanism ("refused gate" leads to LOCKED only via the other inputs, not via the hold). INFO nit, skip or mention briefly.

  Docs claim "Every path that can present the first-run screen now requires the whole directory to be provably clean — not merely 'no vault.bin'." — the legacy arm presents Onboarding WITHOUT proven absence (legacy image present). The doc says "every path ... requires proven absence" — legacy onboarding doesn't. Minor doc inaccuracy. Actually "fresh-install presentation" for legacy is a re-onboard; the claim overreaches slightly. LOW/INFO doc nit. Also onDeleteAccount's Onboarding route (post-destroy) requires destroy-confirmed-clean, which is stronger. And onRetryDestroy same. OK mention as INFO.

  Now focus I: name the untested failure shape. Candidates:
  1. Compose delivery of the verdict (disclosed in docs).
  2. The sweep branch "residue survives unlink → SWEPT_NOT_DURABLE" (line 1417) untested — no test makes dekFile.delete() fail. Could be tested with a read-only directory (POSIX). This is a real untested branch in the destructive op.
  3. clearCacheDir + retryPlaintextCacheClearIfNoVault entirely untested.
  4. afterPublish ordering untested.
  5. The stomp race above untested (Compose).

  Also test-quality: BootReconcileOwnerTest line 51 has trailing whitespace (trivial, ignore).

  BootRouteTest `onboarding is reachable...` derives expected from re-implemented predicate — flag as the "tested against a copy" item, though as a regression pin it still fails on single-sided change; the risk is an edit updating both in tandem, or the copy being wrong in the same way. Note the copy IS currently correct — I verified `!c && (l || (!i && !h && p))` matches bootRoute's semantics. Wait — check one subtle case: c=false, l=true, i=true,h=true,p=true → bootRoute: confirmed? no. legacy? yes → ONBOARDING. Copy: !c && l → included ✓. c=false,l=false,i=false,h=false,p=true → ONBOARDING ✓ both. c=false,l=false,i=false,h=true,p=true → LOCKED; copy excluded ✓. Matches.

  Now A re-verification — also check MainActivity for leftover references to burn in comments that assume the wiped mechanism: line 889-892 comment says "When the burn-wipe PR lands, this becomes the wipe trigger" — accurate (it's deferred). "slot 0 is unarmed until burn-setup ships" ✓ consistent. Search for comments mentioning burn that assume healers run: grep "completeInterruptedBurn|reconcileOrphanedBurnMarkers" — none anywhere ✓. Also check docs/ and other files for those symbols — the earlier repo-wide grep covered the whole worktree (no matches). Also check that no test references the removed symbols ✓ (grep covered apps/android; repo-wide grep found none).

  Also B: "no field that now has no writer" — residueSweepHold/bootReconciled have writers (publish) ✓. bootReconcileStarted CAS ✓. Check there's no leftover `burned` variable or `obliterated` in MainActivity: grep.

• Let me run a few final residue greps and verify the remaining doc/comment claims.apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt:25: * PUCKER BURN Unit W — the BOOT-OWNER LIFECYCLE CONTRACT (0.9.2, sweep-delta round 3).
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:12: * PUCKER BURN Unit W — the COLD-START route decision (0.9.2, sweep-delta round 1, Codex).
apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt:34: * COLD-START ORPHAN SWEEP (0.9.2 Unit W-A).


• All "Burn" references are either the message-TTL feature (burn-on-read, pre-existing) or the slot-0 Pucker Burn outcome plumbing that exists on main too (UnlockOrAdd.Burn, PassphraseOutcome.Burn — present on main? Verify PassphraseOutcome.Burn exists on main — yes, the diff didn't touch it; line 118 comment "The Pucker Burn slot matched — the app performs the duress wipe (a sibling feature)" — that comment exists on main? Check: git show main:ZitroneApp.kt | grep. Actually the comment "the app performs the duress wipe" — if on main, fine. Let me verify quickly.

  Also verify the kdoc at VaultImageStore:654 "(the app wipes; this method writes nothing..." — on main? These are pre-existing comments about future behavior, describing intent. Check main for both.sealed interface PassphraseOutcome {
    /** An existing vault slot matched — a session was published. Route to the chat. */
    data object Unlocked : PassphraseOutcome

    /** No slot matched, the triple-entry gate fired, a new vault was created + published. */
    data object Created : PassphraseOutcome

    /** The Pucker Burn slot matched — the app performs the duress wipe (a sibling feature). */
    data object Burn : PassphraseOutcome

    /** No match (or a refused build, or a fail-closed create): a wrong-passphrase equivalent. */
    data object Rejected : PassphraseOutcome

===
618:     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
Those comments are pre-existing on main — not extraction residue. Good.

  Now finalize. Remaining verifications done. Let me also double-check the diff didn't change `onUnlockBiometric`/veil logic (pre-existing) — the race I found at the second boot effect is NEW code (the LaunchedEffect at 679), though the stomp target involves pre-existing veil. The new effect's DELETE_INCOMPLETE arm applying a pre-suspend decision without re-check is introduced by this unit. Rate it.

  Reachability re-assessment for the race:
  - Preconditions: boot with {confirmed marker present, vault present} (crash in destroy between marker write and unlinks — a designed-for state), biometric enabled, lemon-drop Locked veil queued, and the user completes veil biometric unlock in the window between the effect's `session.value == null` check and its route write (~the duration of the IO snapshot; with confirmed=true the isLegacyImage decrypt is skipped, so the window is a few fast stats — sub-ms to a few ms).

  Actually wait — can the veil even be interacted with before boot publishes? The veil composes immediately (it doesn't wait for boot). The user could tap the veil CTA and complete biometric auth BEFORE bootDone; onUnlockSuccess sets route=ChatList, unlocked=true. Then when boot publishes, second effect: session.value != null → skips entirely ✓. The dangerous interleaving is only: effect's null-check passes, THEN unlock completes during the IO window. Window is tiny but the consequence (DeleteIncomplete → auto-destroy under a live session, racing the session collector) violates the marker invariant. LOW seems right; arguably MEDIUM because it's the exact reviewed failure class. I'll say LOW with the class noted.

  One more re-check of the same effect's guard for ONBOARDING: `if (route == Route.Locked) route = Route.Onboarding` — post-suspend, if route became ChatList (live session), no-op ✓. If route is Onboarding (mid-create) — no-op ✓. LOCKED arm: Unit ✓. Only DELETE_INCOMPLETE arm stomps.

  Also note: the decision effect re-checks only `route != Route.Splash`, not session — but if route==Splash then no unlock happened (unlock routes ChatList), and session can't be live while on Splash. ✓

  Now the D claim verification is complete. E complete. F: consumers enumerated: (1) decision effect 643 ✓ carried verdict, ordered after publication, full inputs; (2) recreation effect 679 ✓ carried verdict, ordered after publication (first{it}), full inputs — but the post-suspend staleness race on the DELETE_INCOMPLETE arm (LOW); (3) session collector 806 ✓ full inputs; session-null implies boot long published; (4) legacy effect 768 — pre-publication disk read but only fires with bin present (sweep can't mutate), conclusion identical to bootRoute; INFO note; (5) onRetryDestroy / onDeleteAccount finally — keyed on destroy()'s own durability proof, not the carried verdict — correct because destroy proves durability itself; (6) create/unlock paths route on their own operation outcomes. No consumer recomputes the durability verdict from a fresh stat. ✓ F passes with one LOW.

  G: contract verified; tests cover once-only, fail-closed default, cancellation-releases-waiter, no-hold-after-durable, no re-sweep. Untested: afterPublish ordering; real job cancellation (vs CE from sweep) — same code path though.

  H: 475/0/472/3 observed, matches claim; skipped = 3 I2P live-integration (env-gated, pre-existing).

  I: findings:
  - BootRouteTest 32-combo test derives expected from a re-implementation (`!c && (l || (!i && !h && p))`) — a copy of the logic; currently correct, still pins single-sided changes, but it's the shape the brief asks to name. LOW.
  - Sweep test `rows 5 and 8` is admittedly weak/vacuous w.r.t. gate implementation (self-disclosed; load-bearing version exists). INFO.
  - Untested: sweep branch `residue survives unlink → SWEPT_NOT_DURABLE` (VaultImageStore.kt:1417) — no test makes a delete fail. clearCacheDir/retryPlaintextCacheClearIfNoVault untested. afterPublish ordering untested. Compose delivery untested (disclosed in docs). The stomp race untested (no Compose infra).
  - Test headers in two of the three new suites still say "PUCKER BURN Unit W" — stale parent-unit naming in the extraction. INFO.
  - BootReconcileOwnerTest header's self-correction is honest and accurate.

  Other findings:
  - Stranded kdoc: VaultImageStore.kt:1295 `/** Delete an incomplete-write temp for [target], if any. Best-effort. */` now floats above `imageBearingFilesProvenAbsent`'s kdoc; `deleteLeftoverTmp` (1430) lost its doc. Extraction artifact. LOW.
  - Dead public wrapper `AppContainer.sweepOrphanedVaultResidue()` (ZitroneApp.kt:238) — no callers (startBootReconcile uses imageStore directly; tests use the store). LOW/INFO.
  - SECURITY_MODEL.md claim "Every path that can present the first-run screen now requires the whole directory to be provably clean" — the legacy-image arm presents Onboarding without proven absence (by design); the sentence overreaches. INFO.
  - The hold-state lock screen: MissingImage maps to ImageUnreadable ("damaged image" note) — in the hold state there is no image at all; the note is honest-ish but imprecise. INFO at most. Actually — the hold state with no vault: the user sees a lock screen; any passphrase entry → ImageUnreadable note. That is the intended fail-closed behavior; the message could confuse but no security impact. Skip or INFO.

  One more check on the sweep gate: gate 2 uses serverDeletedFile only. What about `deleteIntentPending` state {intent, bin absent} + residue → sweep proceeds (row 6b). Post-sweep: ONBOARDING requires proven absence of 4 files; intent marker remains; create() clears it. Pre-existing semantics ✓.

  Also confirm the sweep is only ever invoked via startBootReconcile (once per process) — ✓ and the public store method is also called directly in tests.

  Edge in the sweep: `dekFile.delete()` then `deleteLeftoverTmp(dekFile)` then `deleteLeftoverTmp(binFile)` — no fsync of individual unlinks before re-stat, but dirSync at the end covers the dir entries ✓. The re-stat uses Files.notExists on all four ✓.

  What about the sweep NOT deleting vault.delete-intent if present? Correct — markers owned by D2c/create.

  Also: `imageBearingFilesProvenAbsent` doesn't take imageLock itself but is called under lock everywhere (sweep, obliterationComplete wraps withLock) ✓.

  `obliterationComplete` name is a burn-flavored leftover name ("obliteration") but it's new in this unit; fine.

  Verdict: READY TO MERGE with LOW/INFO nits? The only substantive finding is the LOW race (F), plus test-quality items. No HIGH/CRITICAL. I'd say ready to merge; recommend the small fix for the DELETE_INCOMPLETE re-check and the stranded kdoc, non-blocking.

  Let me finalize the todo list and write the report.

• All verification is complete. Let me finalize and write the report.

• # Unit W-A — Independent Security Review (Round 1, reviewer: this instance)

  Commit `a98677f` on top of `main`, reviewed in a private worktree. Every claim below was re-derived from source; the suite was built and run here.

  ## Verdicts on the binding focus items

  **A — Nothing burn-dependent survived the cut: PASS.** Repo-wide grep for `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion` returns zero matches anywhere in the worktree. `onBurn` (MainActivity.kt:893) is byte-identical to `git show main:` — the same fail-closed stub setting `UNIFORM_FAILURE` and destroying nothing. Remaining `Burn` symbols (`UnlockOrAdd.Burn`, `PassphraseOutcome.Burn`, slot-0 plumbing) are pre-existing on main — verified via `git show main:` for both files.

  **B — Coupling line cleanly severed: PASS.** No `signalBurnCompleted(obliterated = burned)` residue; no dangling caller; no field without a writer. The new state (`bootReconciled`, `residueSweepHold`, `bootReconcileStarted`) all has exactly one writer path through `publish`.

  **C — Excluded healers left nothing dangling: PASS.** `completeInterruptedBurn()` / `reconcileOrphanedBurnMarkers()` appear nowhere. Their unreachability argument holds independently: `create()` clears both delete markers durably *before* writing the DEK (VaultImageStore.kt:505-510) and writes the DEK durably before `vault.bin` (539-546); `destroy()` writes `vault.delete-confirmed` durably before any unlink (1108). No comment still assumes the healers run — the sweep kdoc's reasoning references only create/retire/destroy, all verified.

  **D — Strictly better than main: VERIFIED.** On main, Splash's `onFinished` routed on `vaultExists` seeded from `hasVault()` (bin-keyed): `{bin absent, dek present}` → Onboarding, and a later `create()` blindly overwrote the residue. W-A sweeps the residue durably first and requires proven absence for onboarding. One honest caveat: in the non-durable case W-A shows an impassable lock screen where main showed onboarding — a deliberate fail-closed availability tradeoff, documented, not a regression of any *destructive* state.

  **E — Sweep gate: PASS, table complete.** I hunted the missing row by enumerating every writer of `vault.bin`/`vault.dek`/temps (grep over the whole module): `create` (rows 1-3), `retireLegacyImage` (row 1b), `destroy` (row 7, marker-gated), `renameIntoPlace`/`atomicWrite` on a live vault (bin present throughout — row 4), D2c markers (rows 6-8). No other writer exists; `BiometricUnlockStore` persists via `KeyStoreManager`, not `filesDir`. Wrongly-DELETES: none — every state the sweep deletes is one where the residue opens nothing or no other path reaches it. Wrongly-STRANDS: none — `{bin present, bin.tmp}` is healed by `open()`'s leftover-temp delete (line 347); `{confirmed marker, residue}` is owned by `Route.DeleteIncomplete` → destroy. The no-`delete-intent`-gate reasoning checks out: `{intent, no confirmed, no bin}` is unreachable in the D2c machine because destroy writes confirmed before unlinking and retires both markers together, and create clears both markers before any vault byte. Row 6b (intent must not strand residue) is the correct call.

  **F — Verdict carried, not re-derived: PASS with one LOW.** All `bootRoute` callers (3 sites: MainActivity.kt:659, 699, 835) pass all five arguments; no parameter has a default. Both boot consumers are ordered after publication (`bootDone` / `bootReconciled.first { it }`, and `publish` sets `hold` before `done`). No consumer re-derives durability from a fresh stat. The session collector's null-branch can only fire after a session existed, which transitively implies boot completed. `onRetryDestroy`/the delete handler route on `destroy()`'s *own* durability proof (re-stat + required dirSync, throws otherwise), which is at least as strong as the carried verdict. One defect — see Finding 1.

  **G — `runBootReconcile` contract: PASS.** Once-only via injected CAS (ZitroneApp.kt:1117); fail-closed default `SWEPT_NOT_DURABLE` (1123); `publish` in `finally` on every exit including cancellation (1137-1140); `CancellationException` rethrown, not swallowed (1131-1132). The claim cannot strand waiters: the CAS stays true but `publish` always runs, so waiters are released fail-closed. Tests drive real waiters and count real sweep executions — the header's self-correction about what the second test does *not* catch is accurate (I agree with its reasoning: `publish` is injected, so internal ordering is untestable there; production's hold-before-done order is correct by inspection).

  **H — Test suite run independently: PASS, numbers confirmed.** `ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest` → BUILD SUCCESSFUL. Parsed the JUnit XMLs myself: **57 suites, 475 tests, 0 failures, 0 errors, 472 passed, 3 skipped** — exactly the claimed figures. The 3 skips are the pre-existing environment-gated `I2pLiveIntegrationTest`. The three new suites ran: BootReconcileOwnerTest 8, BootRouteTest 10, SweepOrphanedResidueTest 12.

  **I — Test quality: mostly strong.** The ELOOP symlink test (row 5) separates the two gate implementations by *consequence* (the DEK must survive), and the header for the weak ENOTDIR test honestly discloses its own vacuity w.r.t. the gate mutation. Items below.

  ## Findings

  **1. LOW — `MainActivity.kt:709-711` (second boot effect): post-suspend decision applied without re-checking.** The effect checks `container.session.value == null` *before* the `withContext(Dispatchers.IO)` snapshot, then applies `DELETE_INCOMPLETE` unconditionally afterward (`if (route != Route.DeleteIncomplete) route = Route.DeleteIncomplete`). The sibling decision effect got the re-check (line 670); this one's ONBOARDING arm got a stale-route guard (713); the DELETE_INCOMPLETE arm got neither. A lemon-drop Locked veil overlays Splash and its biometric CTA publishes a session + `Route.ChatList` (968-974 → 873-877). If that lands inside the ms-scale snapshot window on a boot into `{confirmed marker + present vault}` (crash between destroy's marker write and its unlinks), this effect stomps ChatList → DeleteIncomplete, and `LaunchedEffect(Unit) { onRetryDestroy() }` (1300) destroys the vault under a live session — the session's later reseal can resurrect `vault.bin` for a server-deleted account with markers already retired, the exact state destroy's ordering exists to prevent. Preconditions are extreme (crash-mid-destroy boot + queued veil + biometric + a ~ms window), hence LOW, but it is precisely this unit's reviewed failure class. Fix: after the suspend, re-check `container.session.value == null` before applying, or apply DeleteIncomplete only when `route` is still a pre-session route.

  **2. LOW — `VaultImageStore.kt:1295`: stranded kdoc, extraction artifact.** The insertion placed the new `imageBearingFilesProvenAbsent` kdoc between `/** Delete an incomplete-write temp for [target], if any. Best-effort. */` and the function it documented — `deleteLeftoverTmp` (1430) now has no doc and the orphan comment floats above an unrelated function. Cosmetic; fix by moving it back down.

  **3. LOW — `ZitroneApp.kt:237-238`: dead public wrapper.** `AppContainer.sweepOrphanedVaultResidue()` has no callers — `startBootReconcile` uses `imageStore.sweepOrphanedResidue()` directly and tests use the store. Delete it or use it in `startBootReconcile`.

  **4. LOW — `BootRouteTest.kt:213-241`: expected output derived from a re-implementation of the rule.** `expected = all.filter { !c && (l || (!i && !h && p)) }` is a copy of `bootRoute`'s onboarding condition. I verified the copy is currently correct, and any single-sided change fails the test, so it is not vacuous — but it pins the function to a second statement of itself rather than to data; a tandem edit of both would pass silently. The adjacent explicit 16-row truth table is the right pattern. Consider materializing the 32 rows.

  **5. INFO — untested failure shapes.** (a) The sweep's "residue survives the unlink → `SWEPT_NOT_DURABLE`" branch (VaultImageStore.kt:1417) has no test making `delete()` fail (a read-only directory would do it on POSIX). (b) `clearCacheDir` / `retryPlaintextCacheClearIfNoVault` are entirely untested, including the fail-closed `listFiles()==null` path. (c) `afterPublish` ordering (hygiene after the gate opens) is untested. (d) Compose delivery of the verdict — disclosed honestly in SECURITY_MODEL.md as inspection-verified.

  **6. INFO — stale parent-unit naming in two test headers.** BootReconcileOwnerTest.kt:25 and BootRouteTest.kt:12 still read "PUCKER BURN Unit W"; SweepOrphanedResidueTest.kt:34 correctly says "Unit W-A". Cosmetic, but in an extraction whose hazard is stale context, the headers should say W-A.

  **7. INFO — doc overreach.** SECURITY_MODEL.md: "Every path that can present the first-run screen now requires the whole directory to be provably clean" — the legacy-image arm of `bootRoute` presents Onboarding with an image present (by design). And "If the sweep cannot complete … a refused gate … the lock screen is held" — a refused gate returns `NO_MUTATION` (no hold); the lock screen results from the other inputs. Outcomes are right; the mechanism description is imprecise.

  ## Merge verdict

  **READY TO MERGE.** No CRITICAL or HIGH findings. The extraction is clean: nothing burn-dependent survived, the coupling is severed with no dangling state, the sweep gate's writer/reader table is complete in both directions, the durability verdict is carried to every consumer, and the reported test figures reproduce exactly (475/0/472/3). Findings 1-4 are worth a small follow-up patch (the Finding 1 re-check is the only one with security semantics); none blocks this unit.

To resume this session: kimi -r session_5ced946c-c955-4780-beb2-02e43ccd87df
