254	     * WHY A PARTIAL CREATE CANNOT BE MISTAKEN FOR THIS (verified against [create]): create renames the
  1255	     * DEK envelope into place FIRST and the image SECOND, so a crash mid-create leaves
  1256	     * `{dek present, bin absent}` — the exact INVERSE of this signature. There is no ordering in the
  1257	     * codebase that produces `{bin present, dek absent}` except an interrupted keys-first obliteration
  1258	     * or genuine media loss of the DEK, and both are unrecoverable. Leftover temps are separately
  1259	     * cleared by [open].
  1260	     *
  1261	     * DEFERS TO D2c: if `vault.delete-confirmed` is present this does nothing. That state belongs to the
  1262	     * account-delete self-heal ([Route.DeleteIncomplete] → the idempotent [destroy]), which is the
  1263	     * correct handler and preserves the confirmed-marker semantics. Completing the wipe here would clear
  1264	     * that marker out from under it.
  1265	     *
  1266	     * Best-effort and silent; never throws. Returns true iff it completed a wipe.
  1267	     */
  1268	    fun completeInterruptedBurn(): Boolean =
  1269	        imageLock.withLock {
  1270	            if (!Files.notExists(serverDeletedFile.toPath())) return@withLock false
  1271	            if (!Files.notExists(dekFile.toPath())) return@withLock false
  1272	            if (Files.notExists(binFile.toPath())) return@withLock false
  1273	            runCatching { obliterateLocked() }.isSuccess
  1274	        }
  1275	
  1276	    /**
  1277	     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
  1278	     * local image must be destroyed. The ONLY authorisation for the unlink-only
  1279	     * [Route.DeleteIncomplete] auto-destroy. (Replaces the round-12 `destroyPending`, which
  1280	     * conflated intent with confirmation — the P1-A/P1-1 root.)
diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 48bc804..a0b02d1 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -699,8 +699,25 @@ private fun ZitroneRoot(
     // belong to D2c's own reconcile/DeleteIncomplete paths. See
     // VaultImageStore.reconcileOrphanedBurnMarkers.
     LaunchedEffect(Unit) {
-        withContext(Dispatchers.IO) {
+        val finished = withContext(Dispatchers.IO) {
+            // (a) Finish a burn interrupted BETWEEN the keys-first unlinks (round-1 review, Grok).
+            // {image present, DEK proven absent} is already cryptographically dead but reports
+            // hasVault()==true, so without this the device sits on a lock screen whose every unlock
+            // escalates as an unreadable image — a visibly bricked state and a tell. Unlike destroy(),
+            // a burn writes no marker, so it had no self-heal. Completing it destroys nothing readable.
+            val completed = runCatching { container.completeInterruptedBurn() }.getOrDefault(false)
+            // (b) Sweep an orphaned delete-intent left by a crash between the unlinks and the marker
+            // retire.
             runCatching { container.reconcileOrphanedBurnMarkers() }
+            // (c) Retry a plaintext-cache clear that failed during a burn (best-effort, see BurnResult).
+            runCatching { container.retryPlaintextCacheClearIfNoVault() }
+            completed
+        }
+        // A completed interrupted burn removes the image, so the route must be re-derived — otherwise
+        // this composition sits on Locked over a vault that no longer exists.
+        if (finished && container.session.value == null) {
+            vaultExists = container.hasVault()
+            if (!vaultExists && route != Route.Onboarding) route = Route.Onboarding
         }
     }
 
@@ -816,10 +833,17 @@ private fun ZitroneRoot(
         container.scope.launch {
             val burned = try {
                 withContext(Dispatchers.IO) {
-                    runCatching { container.burnVault() }
-                    // DISK TRUTH, not the call's return value — the same standard the account-delete
-                    // path uses. The burn succeeded iff the image is actually gone.
-                    !container.hasVault()
+                    // FAIL-CLOSED on BOTH counts (round-1 review, both reviewers). The previous
+                    // `runCatching { burnVault() }; !container.hasVault()` was fail-OPEN twice over: it
+                    // discarded the DestroyFailed, and hasVault() keys on `vault.bin` ALONE — a routing
+                    // signal, not a wipe proof. A burn that unlinked vault.bin but left vault.dek or
+                    // (worse) vault.bin.tmp — which stages a COMPLETE outer image — would have reported
+                    // success and routed to onboarding with the encrypted vault still on disk.
+                    //
+                    // Now: the wipe must not throw AND every image-bearing file must be PROVEN absent by
+                    // tristate re-stat (present or indeterminate both fail).
+                    val completed = runCatching { container.burnVault() }.isSuccess
+                    completed && container.burnObliterationComplete()
                 }
             } finally {
                 // OUTERMOST finally: never leave unlock gated. On success the user must be able to
@@ -837,9 +861,16 @@ private fun ZitroneRoot(
                     route = Route.Onboarding
                 } else {
                     // FAIL-CLOSED: a burn that did not take must never present as one that did. Surface
-                    // the SAME uniform failure a wrong passphrase gives — honest (claims no
-                    // destruction), deniable (indistinguishable from a mistyped password), and
-                    // retryable. The vault is still on disk and still unlockable.
+                    // the SAME uniform failure a wrong passphrase gives — deniable (indistinguishable
+                    // from a mistyped password) and retryable.
+                    //
+                    // HONEST SCOPE (round-1 review, Grok): this does NOT mean the device is untouched.
+                    // The app-local cleanups run BEFORE the image destruction, so a failed burn can
+                    // leave the biometric wrap, device settings and notification channel already
+                    // cleared while the image survives. Passphrase unlock still works; biometric
+                    // unlock will not, and settings are back at defaults. That ordering is deliberate —
+                    // the cleanups must never pre-empt or mask the image destruction's signal — and a
+                    // retry re-runs every step idempotently.
                     lockError = VaultUnlockRouter.UNIFORM_FAILURE
                     unlocking = false
                 }
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index 7481696..46c3633 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -130,6 +130,24 @@ sealed interface PassphraseOutcome {
     data object Retry : PassphraseOutcome
 }
 
+/**
+ * Outcome of a Pucker Burn wipe (0.9.2 Unit W). Separates the two guarantees, which have deliberately
+ * DIFFERENT strengths — round-1 review raised that conflating them let a fail-open cache clear present
+ * as a complete burn.
+ *
+ * The IMAGE destruction is mandatory and fail-closed: [AppContainer.burnVault] throws if it does not
+ * fully take, and the caller additionally proves it via [VaultImageStore.obliterationComplete].
+ *
+ * The PLAINTEXT CACHE is best-effort-with-retry, and this flag reports honestly whether it took. POLICY
+ * (explicit, so it can be reviewed rather than inferred): a cache that cannot be cleared does NOT abort
+ * the burn. Refusing to destroy the keys because a staged photo is locked would leave the entire vault
+ * readable under duress — strictly worse than destroying the keys and retrying the cache. So the keys
+ * always die; the cache is retried immediately after obliteration and again on every vault-less cold
+ * start ([AppContainer.retryPlaintextCacheClearIfNoVault]); and where it still cannot be cleared, that
+ * is DISCLOSED as a residual rather than claimed as destroyed.
+ */
+data class BurnResult(val plaintextCacheCleared: Boolean)
+
 class AppContainer(private val app: Application) {
 
     val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
@@ -677,17 +695,39 @@ class AppContainer(private val app: Application) {
      * failed burn never presents as a successful one). After this call [hasVault] is false → the app
      * routes to Onboarding, indistinguishable from a fresh install at the app level.
      */
-    fun burnVault() {
+    fun burnVault(): BurnResult {
         // TOLERATED cleanups first, load-bearing image destruction last — the same discipline as
         // [destroyVaultForAccountDeletion], so a hiccup in best-effort hygiene can neither mask nor
         // PRE-EMPT the image obliteration's success/failure signal.
         wipeBiometricMaterial()
-        wipeAppLocalStateForBurn()
+        val plaintextCleared = wipeAppLocalStateForBurn()
         // NOT tolerated: a DestroyFailed (a surviving file) MUST reach the caller so a burn that did
         // not take is never presented as one that did.
         imageStore.obliterateForBurn()
+        // Second cache pass AFTER the image is gone: the first pass ran while a session teardown could
+        // still have been writing, and this one cannot be pre-empted by an obliteration failure.
+        val plaintextClearedNow = plaintextCleared || clearCacheDir(app.cacheDir)
+        return BurnResult(plaintextCacheCleared = plaintextClearedNow)
+    }
+
+    /**
+     * Retry the plaintext-cache clear on a cold start (0.9.2 Unit W, round-1 review). A burn whose cache
+     * clear failed leaves no marker to find — by design, a burn writes nothing — so this simply re-runs
+     * the clear whenever no vault exists. Cheap (a directory list), silent, and self-healing.
+     *
+     * Deliberately gated on "no vault": with a live vault the cache is legitimately in use.
+     */
+    fun retryPlaintextCacheClearIfNoVault(): Boolean {
+        if (imageStore.exists()) return false
+        return runCatching { clearCacheDir(app.cacheDir) }.getOrDefault(false)
     }
 
+    /** Fail-closed proof that a burn fully took — see [VaultImageStore.obliterationComplete]. */
+    fun burnObliterationComplete(): Boolean = imageStore.obliterationComplete()
+
+    /** Finish a burn interrupted mid-unlink — see [VaultImageStore.completeInterruptedBurn]. */
+    fun completeInterruptedBurn(): Boolean = imageStore.completeInterruptedBurn()
+
     /**
      * Clear the app-local state that lives OUTSIDE the vault image (0.9.2 Unit W). The image carries
      * every session store — signal, auth, roster and settings are all vault-backed
@@ -708,12 +748,16 @@ class AppContainer(private val app: Application) {
      *
      * Every step is individually tolerated: none may pre-empt the image obliteration that follows.
      */
-    private fun wipeAppLocalStateForBurn() {
+    private fun wipeAppLocalStateForBurn(): Boolean {
         tolerateCleanup { settingsRepository.clearAllForWipe() }
         tolerateCleanup { wipeLegacyPrefs() }
         tolerateCleanup { bootDiagnostics.clear() }
         tolerateCleanup { MessagingNotifications.clearAllForWipe(app) }
-        tolerateCleanup { clearCacheDir(app.cacheDir) }
+        // The cache result is RETAINED (round-1 review): it was previously discarded inside
+        // tolerateCleanup, so unreachable plaintext could not be distinguished from a clean wipe.
+        var cleared = false
+        tolerateCleanup { cleared = clearCacheDir(app.cacheDir) }
+        return cleared
     }
 
     /**
@@ -1125,7 +1169,15 @@ internal fun sealDurableOrFalse(seal: () -> Unit): Boolean =
  * convention [completeTerminalWipe] follows.
  */
 internal fun clearCacheDir(cacheDir: java.io.File?): Boolean {
-    if (cacheDir == null || !cacheDir.exists()) return true
-    cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
-    return cacheDir.listFiles()?.isEmpty() ?: true
+    if (cacheDir == null) return true
+    if (!cacheDir.exists()) return true
+    // FAIL-CLOSED on an unreadable directory (round-1 review, both reviewers): the previous
+    // `listFiles()?.isEmpty() ?: true` reported SUCCESS when listFiles() returned null — which happens
+    // on an I/O error or a permission fault, i.e. exactly when plaintext is most likely still sitting
+    // there. A directory we cannot read is a directory we cannot claim to have emptied.
+    val entries = cacheDir.listFiles() ?: return false
+    entries.forEach { runCatching { it.deleteRecursively() } }
+    // Re-list to PROVE the clear rather than trusting deleteRecursively's bool (false on I/O error too).
+    val remaining = cacheDir.listFiles() ?: return false
+    return remaining.isEmpty()
 }
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 28a20af..ebb865a 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -1203,12 +1203,64 @@ class VaultImageStore internal constructor(
      */
     fun reconcileOrphanedBurnMarkers(): Boolean =
         imageLock.withLock {
-            if (binFile.exists()) return@withLock false
+            // TRISTATE, fail-closed (round-1 review, both reviewers): `File.exists()==false` conflates
+            // "confirmed absent" with "stat could not be determined". Treating an indeterminate stat as
+            // absence would let this clear a GENUINE delete-intent over a still-live vault — the B1
+            // state this function exists to prevent. Only a PROVEN absence may proceed.
+            if (!imageBearingFilesProvenAbsent()) return@withLock false
             if (!Files.notExists(serverDeletedFile.toPath())) return@withLock false
             if (Files.notExists(deleteIntentFile.toPath())) return@withLock false
             runCatching { clearBothMarkersDurably() }.getOrDefault(false)
         }
 
+    /**
+     * True ONLY when every image-bearing file is PROVEN absent — image, DEK envelope, and BOTH
+     * interrupted-write temps. Fail-closed: present OR indeterminate yields false.
+     *
+     * The temps are load-bearing, not incidental: `renameIntoPlace` stages the COMPLETE outer image in
+     * `vault.bin.tmp` (and the wrapped DEK in `vault.dek.tmp`), so a surviving temp is a surviving
+     * encrypted vault. Checking only `vault.bin` (as [exists] does, correctly, for ROUTING) would call a
+     * burn successful while a full image sat in a temp.
+     */
+    private fun imageBearingFilesProvenAbsent(): Boolean =
+        Files.notExists(binFile.toPath()) &&
+            Files.notExists(dekFile.toPath()) &&
+            Files.notExists(leftoverTmp(binFile).toPath()) &&
+            Files.notExists(leftoverTmp(dekFile).toPath())
+
+    /**
+     * Public fail-closed proof that a wipe fully took (0.9.2 Unit W, round-1 review). The burn's success
+     * decision MUST use this, never [exists] — `hasVault()` is a ROUTING signal keyed on `vault.bin`
+     * alone, so a surviving DEK or temp would read as a completed burn and route to onboarding as if the
+     * device were freshly installed.
+     */
+    fun obliterationComplete(): Boolean = imageLock.withLock { imageBearingFilesProvenAbsent() }
+
+    /**
+     * Finish a burn interrupted between the keys-first unlinks (0.9.2 Unit W, round-1 review — Grok).
+     *
+     * The keys-first order means a crash after the DEK unlink but before the image unlink leaves
+     * `{vault.bin present, vault.dek absent}`. That state is already CRYPTOGRAPHICALLY DEAD — the image
+     * cannot be opened without its DEK envelope — but [exists] reports true, so boot routes to the lock
+     * screen and every unlock attempt escalates as an unreadable image. Unlike [destroy], whose confirmed
+     * marker self-heals through [Route.DeleteIncomplete], a burn writes no marker and so had NO boot
+     * completion path: the device was left visibly bricked, which is both a poor duress outcome and a
+     * tell that something was destroyed.
+     *
+     * Fires ONLY on the unambiguous signature: DEK PROVEN absent while the image is PRESENT. It requires
+     * no credential because the state is unrecoverable regardless — completing the unlink destroys
+     * nothing that was still readable. A partially-written FRESH create cannot be confused with this: it
+     * writes the DEK before the image is renamed into place, and any leftover temp is cleared by [open].
+     *
+     * Best-effort and silent; never throws. Returns true iff it completed a wipe.
+     */
+    fun completeInterruptedBurn(): Boolean =
+        imageLock.withLock {
+            if (!Files.notExists(dekFile.toPath())) return@withLock false
+            if (Files.notExists(binFile.toPath())) return@withLock false
+            runCatching { obliterateLocked() }.isSuccess
+        }
+
     /**
      * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
      * local image must be destroyed. The ONLY authorisation for the unlink-only

codex
## Findings

### HIGH — concurrent burn workers can erase a successor vault

[MainActivity.kt:824](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:824), [UnlockController.kt:162](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/UnlockController.kt:162)

`beginTerminalWipe()` merely sets a shared Boolean; it neither rejects a second owner nor reference-counts owners. Across rotation, a second passphrase attempt can match slot 0 before the first worker acquires `imageLock`, launching a second burn. The first worker can finish and call `endTerminalWipe()`, reopening session creation while the second worker is still running. The second worker can then obliterate the newly created successor vault.

This violates terminal exclusion and creates a self-inflicted total wipe.

Fix: make terminal-wipe acquisition atomic and exclusive, e.g. `tryBeginTerminalWipe(): Boolean`; only its successful owner may launch work and release the gate. Ideally put burn single-flight ownership in process-scoped `AppContainer`, with ownership-token or CAS-based release semantics. Add a rotation/concurrent-double-burn test that pauses both workers around `imageLock`, creates a successor after the first finishes, and proves the second cannot erase it.

### MEDIUM — cache clear still has a fail-open stat branch

[ZitroneApp.kt:1171](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1171)

`clearCacheDir()` fixed `listFiles()==null`, but still returns success when `cacheDir.exists()` is false. As elsewhere in this codebase, `File.exists()==false` conflates confirmed absence with an I/O/stat failure. Thus `BurnResult.plaintextCacheCleared` can claim success over inaccessible surviving plaintext.

Additionally, [ZitroneApp.kt:709](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:709) uses short-circuit `plaintextCleared || clearCacheDir(...)`; when the first pass succeeds, the documented post-obliteration retry does not actually run.

Fix: use `Files.notExists()` for confirmed absence; otherwise require a successful directory listing. Always execute the post-obliteration pass, and derive the result from that final proof.

### MEDIUM — successful burn overclaims non-vault cleanup

[ZitroneApp.kt:674](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:674), [ZitroneApp.kt:751](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:751), [SettingsRepository.kt:107](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt:107), [SECURITY_MODEL.md:582](/root/zitrone/docs/SECURITY_MODEL.md:582)

Every cleanup other than image destruction is swallowed by `tolerateCleanup`. `SettingsRepository.clearAllForWipe()` also ignores `commit()`’s Boolean. Nevertheless, the security model states that burn destroys settings, biometric material, diagnostics, and notification artifacts; only the cache is disclosed as best-effort.

A burn can therefore present onboarding while app-controlled prior-use evidence remains, contrary to the stated app-local fresh-install claim.

Fix: either track and retry every cleanup class and disclose all as best-effort, or make those necessary for the app-local parity claim verifiable. At minimum, check `commit()`, return a comprehensive cleanup result, and correct the documentation.

### LOW — cache survival test is vacuous

[BurnAppLocalStateTest.kt:126](/root/zitrone/apps/android/app/src/test/java/com/zitrone/app/BurnAppLocalStateTest.kt:126)

The test named “reports failure when content survives” performs an ordinary successful deletion and asserts success. It never creates surviving content and proves nothing about its stated failure condition.

Fix: inject filesystem operations or a `File` abstraction that can simulate indeterminate stat, unlistable directories, failed deletion, and content reappearing between passes.

## Explicit verdicts

- A: Fixes (1) and (2) are complete and correctly tristate-check all four image-bearing files. Fix (3) is partial because of `File.exists()` and the skipped second pass.
- B: `completeInterruptedBurn()` is safe for normal create states: create durably writes DEK before image, and the confirmed-marker guard defers to D2c. Boot routing mutations converge correctly with Splash, legacy inspection, and the session collector. However, the new burn lifecycle has the HIGH double-owner race above.
- C: Destroying keys despite cache failure is the correct policy. Refusing cryptographic erasure because plaintext staging is locked would be worse. The cache disclosure is directionally honest, but implementation and documentation overstate which cleanup succeeded.
- D.1: Destroy equivalence is acceptable. The confirmed marker is durable before keys-first unlinking, so crashes retain authorization and retry idempotently.
- D.2: Pass. Marker retirement occurs only after unlink verification and durable directory sync.
- D.3: Pass. Confirmed state routes to `DeleteIncomplete`; absent-image burn-marker reconciliation cannot authorize it.
- D.4: Image and marker durable-signal invariants pass. Auxiliary cleanup result invariants are incomplete.
- D.5: Pass. Slot 0 remains random filler, and wipe dispatch appears only at the lock-screen `PassphraseOutcome.Burn` branch.
- D.6: Fail. Terminal exclusion can be prematurely released by overlapping burn owners.
- D.7: Image success is fail-closed. Overall app-local cleanup can still be partially successful while presented as reset.
- E: I agree with leaving the inherited `File.exists()` verification inside `obliterateLocked()` out of this unit’s scope; it is pre-existing D2c behavior, not a new defect.
- F: Targeted tests pass (`BUILD SUCCESSFUL`, 14s). Missing coverage includes concurrent/rotation double burn, indeterminate cache stat, genuine surviving cache content, full `AppContainer`/`BurnResult` integration, and failures of settings/biometric/diagnostic cleanup.

**READY TO MERGE: NO.** The concurrent burn-owner race is merge-blocking.
tokens used
110,823
## Findings

### HIGH — concurrent burn workers can erase a successor vault

[MainActivity.kt:824](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:824), [UnlockController.kt:162](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/UnlockController.kt:162)

`beginTerminalWipe()` merely sets a shared Boolean; it neither rejects a second owner nor reference-counts owners. Across rotation, a second passphrase attempt can match slot 0 before the first worker acquires `imageLock`, launching a second burn. The first worker can finish and call `endTerminalWipe()`, reopening session creation while the second worker is still running. The second worker can then obliterate the newly created successor vault.

This violates terminal exclusion and creates a self-inflicted total wipe.

Fix: make terminal-wipe acquisition atomic and exclusive, e.g. `tryBeginTerminalWipe(): Boolean`; only its successful owner may launch work and release the gate. Ideally put burn single-flight ownership in process-scoped `AppContainer`, with ownership-token or CAS-based release semantics. Add a rotation/concurrent-double-burn test that pauses both workers around `imageLock`, creates a successor after the first finishes, and proves the second cannot erase it.

### MEDIUM — cache clear still has a fail-open stat branch

[ZitroneApp.kt:1171](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1171)

`clearCacheDir()` fixed `listFiles()==null`, but still returns success when `cacheDir.exists()` is false. As elsewhere in this codebase, `File.exists()==false` conflates confirmed absence with an I/O/stat failure. Thus `BurnResult.plaintextCacheCleared` can claim success over inaccessible surviving plaintext.

Additionally, [ZitroneApp.kt:709](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:709) uses short-circuit `plaintextCleared || clearCacheDir(...)`; when the first pass succeeds, the documented post-obliteration retry does not actually run.

Fix: use `Files.notExists()` for confirmed absence; otherwise require a successful directory listing. Always execute the post-obliteration pass, and derive the result from that final proof.

### MEDIUM — successful burn overclaims non-vault cleanup

[ZitroneApp.kt:674](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:674), [ZitroneApp.kt:751](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:751), [SettingsRepository.kt:107](/root/zitrone/apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt:107), [SECURITY_MODEL.md:582](/root/zitrone/docs/SECURITY_MODEL.md:582)

Every cleanup other than image destruction is swallowed by `tolerateCleanup`. `SettingsRepository.clearAllForWipe()` also ignores `commit()`’s Boolean. Nevertheless, the security model states that burn destroys settings, biometric material, diagnostics, and notification artifacts; only the cache is disclosed as best-effort.

A burn can therefore present onboarding while app-controlled prior-use evidence remains, contrary to the stated app-local fresh-install claim.

Fix: either track and retry every cleanup class and disclose all as best-effort, or make those necessary for the app-local parity claim verifiable. At minimum, check `commit()`, return a comprehensive cleanup result, and correct the documentation.

### LOW — cache survival test is vacuous

[BurnAppLocalStateTest.kt:126](/root/zitrone/apps/android/app/src/test/java/com/zitrone/app/BurnAppLocalStateTest.kt:126)

The test named “reports failure when content survives” performs an ordinary successful deletion and asserts success. It never creates surviving content and proves nothing about its stated failure condition.

Fix: inject filesystem operations or a `File` abstraction that can simulate indeterminate stat, unlistable directories, failed deletion, and content reappearing between passes.

## Explicit verdicts

- A: Fixes (1) and (2) are complete and correctly tristate-check all four image-bearing files. Fix (3) is partial because of `File.exists()` and the skipped second pass.
- B: `completeInterruptedBurn()` is safe for normal create states: create durably writes DEK before image, and the confirmed-marker guard defers to D2c. Boot routing mutations converge correctly with Splash, legacy inspection, and the session collector. However, the new burn lifecycle has the HIGH double-owner race above.
- C: Destroying keys despite cache failure is the correct policy. Refusing cryptographic erasure because plaintext staging is locked would be worse. The cache disclosure is directionally honest, but implementation and documentation overstate which cleanup succeeded.
- D.1: Destroy equivalence is acceptable. The confirmed marker is durable before keys-first unlinking, so crashes retain authorization and retry idempotently.
- D.2: Pass. Marker retirement occurs only after unlink verification and durable directory sync.
- D.3: Pass. Confirmed state routes to `DeleteIncomplete`; absent-image burn-marker reconciliation cannot authorize it.
- D.4: Image and marker durable-signal invariants pass. Auxiliary cleanup result invariants are incomplete.
- D.5: Pass. Slot 0 remains random filler, and wipe dispatch appears only at the lock-screen `PassphraseOutcome.Burn` branch.
- D.6: Fail. Terminal exclusion can be prematurely released by overlapping burn owners.
- D.7: Image success is fail-closed. Overall app-local cleanup can still be partially successful while presented as reset.
- E: I agree with leaving the inherited `File.exists()` verification inside `obliterateLocked()` out of this unit’s scope; it is pre-existing D2c behavior, not a new defect.
- F: Targeted tests pass (`BUILD SUCCESSFUL`, 14s). Missing coverage includes concurrent/rotation double burn, indeterminate cache stat, genuine surviving cache content, full `AppContainer`/`BurnResult` integration, and failures of settings/biometric/diagnostic cleanup.

**READY TO MERGE: NO.** The concurrent burn-owner race is merge-blocking.
