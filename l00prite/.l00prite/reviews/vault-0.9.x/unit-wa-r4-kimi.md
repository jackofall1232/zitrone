• Let me start by understanding the repo state. This is a big review task. Let me look at the git state and the diff.

• I'll start by examining the git state and the fix delta.acb5904 Unit W-A round-3 fixes — one post-destroy authority; contain afterPublish; correct the sibling proof
54a41bf Unit W-A round-2 fixes — enforce the dispatcher, cover the derivation and gate 2
0d348b4 Unit W-A round-1 fixes — one legacy authority, gate 2 covered, one derivation
96a2608 l00prite: W-A extracted; round-1 findings are all extraction defects
a98677f 0.9.2 Unit W-A — cold-start orphan residue sweep + fail-closed boot routing
c3e4038 CI pipeline security — close release-apk.yml shell-injection + make SAST actually gate (#59)
e32f0aa 0.9.2-beta — biometric-enable atomicity (closes the disclosed orphan gap) (#57)
e203560 Update README.md
HEAD detached at refs/heads/feat/0.9.2-unit-wa-residue-sweep
nothing to commit, working tree clean
commit acb59045aca15b1520090b5de2ce0401e83ee96e
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 13:15:53 2026 +0000

    Unit W-A round-3 fixes — one post-destroy authority; contain afterPublish; correct the sibling proof
    
    Four lenses on 54a41bf: three READY, one NOT READY on a single MEDIUM. All three
    findings fixed here.
    
    MEDIUM (Grok; Kimi concurring at INFO) — TWO POST-DESTROY ROUTING AUTHORITIES.
    `lockIf` publishes session=null, which wakes the session collector, so the
    account-delete completion callback and that collector decide the SAME routing
    moment. On main both read the same two stats and a comment asserted "the two
    cannot disagree" — TRUE at the time. W-A made it FALSE: the extraction gave the
    collector the carried `residueSweepHold` and left the delete path on `hasVault()`
    + `serverDeleteConfirmed()`. With a hold raised earlier in the process the
    collector computes LOCKED while the delete path computes Onboarding, both write
    `route`, last writer wins — pinning a successfully deleted account to a lock
    screen for the rest of the process.
    
    That is this unit's signature failure class, and my extraction created it by
    strengthening one consumer and not its twin. I sided with Grok's MEDIUM over
    Kimi's INFO: the mitigations are real (conservative fail direction, compound
    state) but the whole purpose of the W-A/W-B split was to stop shipping this
    family.
    
    Fixed by unifying: the delete path now goes through the same
    `deriveBootDecisionFromDisk`, and a COMPLETED destroy clears the hold first —
    justified, not convenient. Destroy proves image-bearing absence with its own
    REQUIRED dirSync and retires both markers only after that proof, which is
    strictly stronger evidence than the sweep's unproven unlink. Leaving the hold
    raised would withhold onboarding over a directory the delete just proved durably
    clean. The mapping matches the previous explicit semantics in every reachable
    state: a surviving image implies the markers were not retired, so
    `serverDeleteConfirmed` is still set and bootRoute yields DELETE_INCOMPLETE,
    never the lock gate; {image survives, confirmed absent} cannot occur because
    destroy throws before the retire when absence is unproven.
    
    That supersede rule is the delta's one behavioural change and it sits in the
    account-delete surface, so it is extracted as the pure
    `destroySupersedesResidueHold` and tested. Mutation-verified: dropping the
    `!serverDeleteConfirmed` conjunct fails exactly the test naming it.
    
    INFO→REAL (Gemini) — no test ever passed an `afterPublish` lambda. The test I
    wrote to close that FAILED against the existing code: a throwing `afterPublish`
    propagated out of the launch, and on Android an uncaught coroutine exception
    reaches the default handler and takes the process down. Production was safe only
    because its own lambda wrapped itself — protecting today's caller and no future
    one. `runBootReconcile` now contains faults there by contract and the production
    lambda dropped its local guard. Third structural fix of this kind, after removing
    parameter defaults and internalising the dispatcher.
    
    LOW (Codex, Grok, Kimi — three lenses) — the row-6b docstring still asserted the
    false intent-gate proof that round 2 corrected in the store's table. I fixed one
    site and missed its twin, for the fourth time in this unit. Both now state that
    sweeping is safe BECAUSE retirement already destroyed the only openable image,
    not because the state is unreachable.
    
    Tests: 487 total (+3), 0 failures, 484 passed, 3 skipped (I2P, pre-existing).
    No version bump.

 .../src/main/java/com/zitrone/app/MainActivity.kt  | 53 ++++++++++++++++------
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 32 ++++++++++++-
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 35 ++++++++++++++
 .../java/com/zitrone/app/DeriveBootDecisionTest.kt | 53 ++++++++++++++++++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 19 +++++---
 5 files changed, 169 insertions(+), 23 deletions(-)


commit acb59045aca15b1520090b5de2ce0401e83ee96e
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 13:15:53 2026 +0000

    Unit W-A round-3 fixes — one post-destroy authority; contain afterPublish; correct the sibling proof
    
    Four lenses on 54a41bf: three READY, one NOT READY on a single MEDIUM. All three
    findings fixed here.
    
    MEDIUM (Grok; Kimi concurring at INFO) — TWO POST-DESTROY ROUTING AUTHORITIES.
    `lockIf` publishes session=null, which wakes the session collector, so the
    account-delete completion callback and that collector decide the SAME routing
    moment. On main both read the same two stats and a comment asserted "the two
    cannot disagree" — TRUE at the time. W-A made it FALSE: the extraction gave the
    collector the carried `residueSweepHold` and left the delete path on `hasVault()`
    + `serverDeleteConfirmed()`. With a hold raised earlier in the process the
    collector computes LOCKED while the delete path computes Onboarding, both write
    `route`, last writer wins — pinning a successfully deleted account to a lock
    screen for the rest of the process.
    
    That is this unit's signature failure class, and my extraction created it by
    strengthening one consumer and not its twin. I sided with Grok's MEDIUM over
    Kimi's INFO: the mitigations are real (conservative fail direction, compound
    state) but the whole purpose of the W-A/W-B split was to stop shipping this
    family.
    
    Fixed by unifying: the delete path now goes through the same
    `deriveBootDecisionFromDisk`, and a COMPLETED destroy clears the hold first —
    justified, not convenient. Destroy proves image-bearing absence with its own
    REQUIRED dirSync and retires both markers only after that proof, which is
    strictly stronger evidence than the sweep's unproven unlink. Leaving the hold
    raised would withhold onboarding over a directory the delete just proved durably
    clean. The mapping matches the previous explicit semantics in every reachable
    state: a surviving image implies the markers were not retired, so
    `serverDeleteConfirmed` is still set and bootRoute yields DELETE_INCOMPLETE,
    never the lock gate; {image survives, confirmed absent} cannot occur because
    destroy throws before the retire when absence is unproven.
    
    That supersede rule is the delta's one behavioural change and it sits in the
    account-delete surface, so it is extracted as the pure
    `destroySupersedesResidueHold` and tested. Mutation-verified: dropping the
    `!serverDeleteConfirmed` conjunct fails exactly the test naming it.
    
    INFO→REAL (Gemini) — no test ever passed an `afterPublish` lambda. The test I
    wrote to close that FAILED against the existing code: a throwing `afterPublish`
    propagated out of the launch, and on Android an uncaught coroutine exception
    reaches the default handler and takes the process down. Production was safe only
    because its own lambda wrapped itself — protecting today's caller and no future
    one. `runBootReconcile` now contains faults there by contract and the production
    lambda dropped its local guard. Third structural fix of this kind, after removing
    parameter defaults and internalising the dispatcher.
    
    LOW (Codex, Grok, Kimi — three lenses) — the row-6b docstring still asserted the
    false intent-gate proof that round 2 corrected in the store's table. I fixed one
    site and missed its twin, for the fourth time in this unit. Both now state that
    sweeping is safe BECAUSE retirement already destroyed the only openable image,
    not because the state is unreachable.
    
    Tests: 487 total (+3), 0 failures, 484 passed, 3 skipped (I2P, pre-existing).
    No version bump.

diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 108f947..5573c72 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -1090,25 +1090,48 @@ private fun ZitroneRoot(
                 // completeTerminalWipe's outermost finally, so the unlock gate is not stranded.
             } finally {
                 // This callback runs on the coordinator's background (confined) dispatcher, so the
-                // Compose-state reconcile is marshaled to Main (round 12d, Gemini). Main.immediate
-                // + container.scope so a rotation mid-wipe cannot cancel it. The disk-truth reads
-                // (hasVault / vaultDestroyPending — fast stats under imageLock) run on Main here,
-                // as they already do from Splash routing. The session→route reconciler is the
-                // parallel main-thread backstop: lockIf published session=null above, so it also
-                // derives the same route from the same disk truth — the two cannot disagree.
+                // Compose-state reconcile is marshaled to Main. Main.immediate + container.scope so a
+                // rotation mid-wipe cannot cancel it.
+                //
+                // ONE ROUTING AUTHORITY (round-3 review, Grok; Kimi concurring). `lockIf` publishes
+                // session=null above, which also wakes the session collector — so this callback and
+                // that collector decide the SAME routing moment. They used to read the same two
+                // stats, and a comment here asserted "the two cannot disagree". W-A made that comment
+                // FALSE: the collector was given the carried `residueSweepHold` and this path was
+                // left on `hasVault()` + `serverDeleteConfirmed()`. With a hold raised earlier in the
+                // process, the collector computes LOCKED while this computes Onboarding, both write
+                // `route`, and the last writer wins — pinning a successfully deleted account to a
+                // lock screen for the rest of the process. That is this unit's signature failure
+                // class, reintroduced by strengthening one consumer and not its twin.
+                //
+                // Both now go through the same derivation with the same inputs.
                 container.scope.launch(Dispatchers.Main.immediate) {
                     identityFingerprint = null
                     unlocked = false
                     lockError = null
-                    vaultExists = container.hasVault()
-                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
-                        // Destroy CONFIRMED (files gone, both markers retired) → fresh-install state.
-                        Route.Onboarding
-                    } else {
-                        // The image (or the server-delete-confirmed marker) survives: the server
-                        // account IS gone, so the only honest route is "finish deleting" with a
-                        // direct retry — NEVER the lock gate (see Route.DeleteIncomplete).
-                        Route.DeleteIncomplete
+                    // A COMPLETED destroy supersedes an earlier non-durable orphan sweep: it proved
+                    // image-bearing absence with its OWN required dirSync and retired both markers
+                    // only after that proof. Leaving a stale boot-time hold raised would withhold
+                    // onboarding over a directory this delete has just proven durably clean.
+                    if (destroySupersedesResidueHold(
+                            vaultProvenAbsent = container.vaultProvenAbsent(),
+                            serverDeleteConfirmed = container.serverDeleteConfirmed(),
+                        )
+                    ) {
+                        container.residueSweepHold.value = false
+                    }
+                    val snap = container.deriveBootDecisionFromDisk()
+                    vaultExists = snap.present && !snap.legacy
+                    // The mapping matches the previous explicit semantics in every REACHABLE
+                    // post-destroy state: a surviving image implies the markers were N
OT retired
+                    // (destroy retires them only after proving absence), so `serverDeleteConfirmed`
+                    // is still set and bootRoute yields DELETE_INCOMPLETE — never the lock gate.
+                    // {image survives, confirmed absent} cannot occur: destroy throws before the
+                    // retire when absence is unproven.
+                    route = when (snap.route) {
+                        BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
+                        BootRoute.ONBOARDING -> Route.Onboarding
+                        BootRoute.LOCKED -> Route.Locked
                     }
                 }
             }
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index 60ead70..47506b7 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -284,7 +284,8 @@ class AppContainer(private val app: Application) {
             },
             afterPublish = {
                 // Non-routing hygiene AFTER the gate opens — a slow cache clear must not hold splash.
-                runCatching { retryPlaintextCacheClearIfNoVault() }
+                // No local runCatching: runBootReconcile contains faults here by contract.
+                retryPlaintextCacheClearIfNoVault()
             },
         )
     }
@@ -1163,7 +1164,12 @@ internal fun runBootReconcile(
             // the coroutine is being cancelled.
             publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)
         }
-        withContext(ioDispatcher) { afterPublish() }
+        // CONTAINED (round-3 review, Gemini). This runs AFTER the verdict is published, so it can
+        // never affect routing — but an uncaught throw here propagates out of the launch and, on
+        // Android, reaches the default handler and takes the process down. Production's lambda wraps
+        // itself, which protects today's caller and no future one; the guarantee belongs in the
+        // wrapper. A fault in post-publication hygiene must not be able to kill the app.
+        withContext(ioDispatcher) { runCatching { afterPublish() } }
     }
 }
 
@@ -1207,6 +1213,28 @@ internal fun deriveBootDecision(
     )
 }
 
+/**
+ * Does a completed account destroy SUPERSEDE an earlier residue-sweep hold?
+ *
+ * The hold exists because a cold-start orphan sweep unlinked residue without being able to prove the
+ * unlink crash-durable. A destroy that completed proves image-bearing absence with its OWN required
+ * `dirSync`, and retires both delete markers only after that proof — so once it has completed, the
+ * earlier uncertainty is resolved by strictly stronger evidence. Leaving the hold raised would
+ * withhold onboarding over a directory this delete has just proven durably clean, for the rest of the
+ * process.
+ *
+ * Both conditions are required. [vaultProvenAbsent] alone is not enough — a fresh stat reports absence
+ * the instant a file is unlinked — and `!`[serverDeleteConfirmed] is what says the destroy actually
+ * reached its marker retire rather than throwing part-way.
+ *
+ * Extracted as a pure predicate so the decision is testable: it is the one behavioural change in an
+ * otherwise-documentation delta, and it sits in the account-delete surface.
+ */
+internal fun destroySupersedesResidueHold(
+    vaultProvenAbsent: Boolean,
+    serverDeleteConfirmed: Boolean,
+): Boolean = vaultProvenAbsent && !serverDeleteConfirmed
+
 /** Where a composition must route out of Splash on a cold start — see [bootRoute]. */
 internal enum class BootRoute { DELETE_INCOMPLETE, LOCKED, ONBOARDING }
 
diff --git a/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt b/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
index 8699968..beb9d41 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
@@ -305,4 +305,39 @@ class BootReconcileOwnerTest {
         assertTrue(h.done.value)
         assertFalse("nothing was mutated, so nothing to withhold", h.hold.value)
     }
+
+    /**
+     * `afterPublish` runs AFTER the verdict is published, so a fault in it must not be able to affect
+     * the verdict or the release of waiters (round-3 review, Gemini: no test passed an `afterPublish`
+     * lambda at all, so the wrapper's behaviour around it was entirely uncovered).
+     *
+     * Production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`, so it cannot throw
+     * today — this pins the ordering guarantee for any future caller that is less careful.
+     *
+     * MUTATION UNIQUELY CAUGHT: moving `afterPublish()` ahead of the `finally` that publishes.
+     */
+    @Test
+    fun `a throwing afterPublish cannot unpublish the verdict`() = runTest {
+        val io = StandardTestDispatcher(testScheduler)
+        val h = Harness()
+        var released = false
+        launch {
+            h.done.first { it }
+            released = true
+        }
+
+        runBootReconcile(
+            scope = this,
+            claim = h::claim,
+            sweep = { ResidueSweepResult.SWEPT_DURABLE },
+            publish = h::publish,
+            afterPublish = { error("post-publication hygiene failed") },
+            ioDispatcher = io,
+        )
+        advanceUntilIdle()
+
+        assertTrue("the verdict must already be published before afterPublish runs", h.done.value)
+        assertTrue("and its waiters released", released)
+        assertFalse("a durable verdict must survive a later failure", h.hold.value)
+    }
 }
diff --git a/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt b/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt
index 0a59ad6..5644551 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt
@@ -155,3 +155,56 @@ class DeriveBootDecisionTest {
         assertEquals(BootRoute.DELETE_INCOMPLETE, d.route)
     }
 }
+
+/**
+ * DOES A COMPLETED DESTROY SUPERSEDE A RESIDUE-SWEEP HOLD? (0.9.2 Unit W-A, round 3.)
+ *
+ * The account-delete completion path and the session collector decide the SAME routing moment. Before
+ * this, the collector consumed the carried `residueSweepHold` and the delete path did not, so a hold
+ * raised earlier in the process made them disagree — collector LOCKED, delete path Onboarding, last
+ * writer wins, pinning a successfully deleted account to a lock screen for the rest of the process.
+ *
+ * Unifying them is only safe if a completed destroy genuinely supersedes the hold. It does: destroy
+ * proves image-bearing absence with its own required `dirSync` and retires both markers only after
+ * that proof — strictly stronger evidence than the sweep's unproven unlink. This pins that reasoning.
+ */
+class DestroySupersedesResidueHoldTest {
+
+    /** The whole point: a completed destroy clears a stale hold. */
+    @Test
+    fun `a completed destroy supersedes the hold`() {
+        assertTrue(
+            destroySupersedesResidueHold(vaultProvenAbsent = true, serverDeleteConfirmed = false),
+        )
+    }
+
+    /**
+     * A destroy that threw before retiring its markers has NOT proven anything — the confirmed marker
+     * is still present, and the hold must stand.
+     *
+     * MUTATION UNIQUELY CAUGHT: dropping the `!serverDeleteConfirmed` conjunct.
+     */
+    @Test
+    fun `a destroy that did not reach its marker retire does not supersede`() {
+        assertFalse(
+            "a surviving confirmed marker means the destroy never completed — the hold stands",
+            destroySupersedesResidueHold(vaultProvenAbsent = true, serverDeleteConfirmed = true),
+        )
+    }
+
+    /**
+     * Absence that is not proven is not absence. Without proven image-bearing absence there is no
+     * stronger evidence to supersede the hold with.
+     *
+     * MUTATION UNIQUELY CAUGHT:
 dropping the `vaultProvenAbsent` conjunct.
+     */
+    @Test
+    fun `an unproven directory never supersedes the hold`() {
+        assertFalse(
+            destroySupersedesResidueHold(vaultProvenAbsent = false, serverDeleteConfirmed = false),
+        )
+        assertFalse(
+            destroySupersedesResidueHold(vaultProvenAbsent = false, serverDeleteConfirmed = true),
+        )
+    }
+}
diff --git a/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt b/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
index f22befc..a2decaa 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
@@ -162,13 +162,20 @@ class SweepOrphanedResidueTest {
      *
      * There is deliberately no gate on `vault.delete-intent`. `destroy()` writes the CONFIRMED marker
      * durably BEFORE it unlinks anything, so every real account-delete unlink already carries the
-     * confirmed marker and is caught by the other gate — while an intent alone never accompanies an
-     * absent image in a legitimate delete state (an intent is written while the image is still
-     * present, and `create()` clears both markers durably before writing the DEK).
+     * confirmed marker and is caught by the other gate. An intent gate would therefore protect
+     * nothing against a deletion in flight, while it could only STRAND residue.
      *
-     * An intent gate would therefore protect nothing and could only STRAND a recoverable outer image
-     * that no other path reaches. A gate can be wrong by being too narrow, and here that would be
-     * worse than the over-deletion such a gate is written to prevent.
+     * PROOF CORRECTED (round 3, Codex). An earlier version of this docstring claimed "an intent alone
+     * never accompanies an absent image in a legitimate state" — and that is FALSE.
+     * `createVaultAndPublish` calls `retireLegacyImage()`, which unlinks the image, BEFORE `create()`
+     * clears the markers, so a crash between them leaves exactly an intent standing over an absent
+     * image. The same false claim was corrected in the store's own table as row 6c; it survived HERE,
+     * in the sibling docstring, which is this unit's recurring shape: fix one site, miss its twin.
+     *
+     * What makes sweeping safe is NOT that the state is unreachable — it is that whatever produced it
+     * has already destroyed the only openable image, so the residue opens nothing and keeping it would
+     * strand dead data. A gate can be wrong by being too narrow, and here that would be worse than the
+     * over-deletion such a gate is written to prevent.
      */
     @Test
     fun `row 6b - an intent marker does not strand recoverable residue`() {
• Now let me look at the whole unit diff (main...HEAD) and the key source files. This is a big review. Let me plan:

  1. Read the full diff main...HEAD (could be large).
  2. Read ZitroneApp.kt (the container, boot routing, sweep, runBootReconcile, deriveBootDecisionFromDisk, etc.)
  3. Read MainActivity.kt around the session collector and delete path.
  4. Verify Item A (burn stuff absent, onBurn unchanged from main).
  5. Verify Item C (healers absent, no dangling references).
  6. Run tests.

  Let me start the test suite in background while I read the code. First check the SDK and gradle.

• This is a substantial multi-part review. Let me set up a plan and kick off the test suite in the background while I read source.

SDK_OK
apps/android/gradlew
openjdk version "21.0.11" 2026-04-21
 .../src/main/java/com/zitrone/app/MainActivity.kt  | 178 ++++++---
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 264 +++++++++++++
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 190 +++++++++-
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 343 +++++++++++++++++
 .../src/test/java/com/zitrone/app/BootRouteTest.kt | 256 +++++++++++++
 .../java/com/zitrone/app/DeriveBootDecisionTest.kt | 210 +++++++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 414 +++++++++++++++++++++
 docs/SECURITY_MODEL.md                             |  36 ++
 l00prite/.l00prite/ledger.md                       |  54 +++
 9 files changed, 1886 insertions(+), 59 deletions(-)
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index 250555f..47506b7 100644
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
@@ -225,6 +226,84 @@ class AppContainer(private val app: Application) {
     /** Routing truth: a vault image is present → UNLOCK, absent → SETUP (onboarding). */
     fun hasVault(): Boolean = imageStore.exists()
 
+    /**
+     * FAIL-CLOSED routing truth: "there is provably nothing here", the only state that may present as
+     * a fresh install. [hasVault] is NOT a substitute — it keys on `vault.bin` alone, so a surviving
+     * `vault.dek` or `vault.bin.tmp` (which stages a COMPLETE outer image) reads as "no vault" and
+     * would route ONBOARDING over recoverable ciphertext.
+     */
+    fun vaultProvenAbsent(): Boolean = imageStore.imageBearingProvenAbsent()
+
+    /**
+     * Read the four disk facts and produce ONE boot decision — the single derivation every routing
+     * consumer uses.
+     *
+     * SUSPEND, and it moves itself to IO (round-2 review, Gemini). This was a plain function with a
+     * kdoc saying "call off the main thread", and one of its three callers — the session collector —
+     * called it bare on the composition dispatcher, running a ~1 MiB decrypt on the main thread. A
+     * requirement stated in a comment is a requirement that will eventually be violated by one call
+     * site; the dispatcher move belongs INSIDE, where no caller can get it wrong. Callers now simply
+     * `deriveBootDecisionFromDisk()`.
+     */
+    internal suspend fun deriveBootDecisionFromDisk(): BootDecision = withContext(Dispatchers.IO) {
+        deriveBootDecision(
+            serverDeleteConfirmed = serverDeleteConfirmed(),
+            imagePresent = hasVault(),
+            residueSweepHold = residueSweepHold.value,
+            vaultProvenAbsent = vaultProvenAbsent(),
+            isLegacyImage = { isLegacyImage() },
+        )
+    }
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
+                // No local runCatching: runBootReconcile contains faults here by contract.
+                retryPlaintextCacheClearIfNoVault()
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
@@ -1035,3 +1114,188 @@ internal fun sealDurableOrFalse(seal: () -> Unit): Boolean =
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
+        // CONTAINED (round-3 review, Gemini). This runs AFTER the verdict is published, so it can
+        // never affect routing — but an uncaught throw here propagates out of the launch and, on
+        // Android, reaches the default handler and takes the process down. Production's lambda wraps
+        // itself, which protects today's caller and no future one; the guarantee belongs in the
+        // wrapper. A fault in post-publication hygiene must not be able to kill the app.
+        withContext(ioDispatcher) { runCatching { afterPublish() } }
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
+/**
+ * Does a completed account destroy SUPERSEDE an earlier residue-sweep hold?
+ *
+ * The hold exists because a cold-start orphan sweep unlinked residue without being able to prove the
+ * unlink crash-durable. A destroy that completed proves image-bearing absence with its OWN required
+ * `dirSync`, and retires both delete markers only after that proof — so once it has completed, the
+ * earlier uncertainty is resolved by strictly stronger evidence. Leaving the hold raised would
+ * withhold onboarding over a directory this delete has just proven durably clean, for the rest of the
+ * process.
+ *
+ * Both conditions are required. [vaultProvenAbsent] alone is not enough — a fresh stat reports absence
+ * the instant a file is unlinked — and `!`[serverDeleteConfirmed] is what says the destroy actually
+ * reached its marker retire rather than throwing part-way.
+ *
+ * Extracted as a pure predicate so the decision is testable: it is the one behavioural change in an
+ * otherwise-documentation delta, and it sits in the account-delete surface.
+ */
+internal fun destroySupersedesResidueHold(
+    vaultProvenAbsent: Boolean,
+    serverDeleteConfirmed: Boolean,
+): Boolean = vaultProvenAbsent && !serverDeleteConfirmed
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
+ *  1. **A CONFIRMED server delete outbids everything** — `Route.Delet
eIncomplete` owns that state.
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
+    // Re-list to PROVE the clear rather than trusting deleteRecursively's bool (false on I/O error).
+    val remaining = cacheDir.listFiles() ?: return false
+    return remaining.isEmpty()
+}
• Note: `runBootReconcile` still has default parameters `afterPublish: () -> Unit = {}` and `ioDispatcher = Dispatchers.IO` — but the commit message says "Third structural fix of this kind, after removing parameter defaults and internalising the dispatcher." Yet the defaults are still there. Hmm — "removing parameter defaults" referred to bootRoute ("No parameter carries a default"). But runBootReconcile still has defaults for afterPublish and ioDispatcher. The kdoc of bootRoute says no defaults. But round-2 "internalising the dispatcher" — but runBootReconcile still takes ioDispatcher with a default. Wait the commit message of round-2 says "enforce the dispatcher, cover the derivation and gate 2" — the dispatcher internalisation was for deriveBootDecisionFromDisk. OK.

  But hold on — the production caller startBootReconcile passes both afterPublish and ioDispatcher? Let me check: startBootReconcile passes scope, claim, sweep, publish, afterPublish — but NOT ioDispatcher. So production uses Dispatchers.IO default. That's fine, though the "no defaults" philosophy was only applied to bootRoute. Actually wait — the comment in runBootReconcile kdoc says "production passes the process-scoped AppContainer.scope and Dispatchers.IO" — but production doesn't pass Dispatchers.IO explicitly; it uses the default. Minor doc inaccuracy. Actually it says "[scope] and [ioDispatcher] are injected precisely so a test can drive cancellation deterministically in virtual time; production passes the process-scoped [AppContainer.scope] and `Dispatchers.IO`." Production does use Dispatchers.IO via the default, so effectively true. INFO at most.

  Now the key files. Let me read ZitroneApp.kt fully (the relevant parts), VaultImageStore.kt diff, MainActivity.kt diff. Let me get the rest of the diff.

• Test suite is running in the background. Now let me read the rest of the unit diff.diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 9cd57e4..17da060 100644
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
@@ -1256,10 +1292,162 @@ class VaultImageStore internal constructor(
         return dirSync(target.parentFile)
     }
 
-    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
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
+     * Public fail-closed proof that the vault directory holds nothing image-bearing.
+     *
+     * Named for what it asserts, not for a wipe (round-2 review, Grok): this unit has no destructive
+     * wipe, and a name carrying that vocabulary invites a reader to assume a mechanism that is not
+     * here. `hasVault()` keys on `vault.bin` alone and would call a directory empty while a surviving
+     * DEK or temp still held a recoverable vault, which is why routing must not use it.
+     */
+    fun imageBearingProvenAbsent(): Boolean = imageLock.withLock { imageBearingFilesProvenAbsent() }
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
+     *                         
                                                 codebase's policy:
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
+     *  6c {delete-intent, no bin, residue}         a crash between            SWEEP. MISSING ROW,
+     *                                               retireLegacyImage() and     found in round 2
+     *                                               create() — the retire       (Codex). Retirement
+     *                                               unlinks the image, only     has ALREADY destroyed
+     *                                               create() clears markers     the only usable image,
+     *                                                                           so the residue opens
+     *                                                                           nothing and retaining
+     *                                                                           it would strand dead
+     *                                                                           data. Swept because
+     *                                                                           the image is gone —
+     *                                                                           NOT because the state
+     *                                                                           is unreachable.
+     *
+     * There is deliberately NO gate on `vault.delete-intent`. [destroy] writes the CONFIRMED marker
+     * durably BEFORE it unlinks anything, so every real D2c unlink already carries the confirmed
+     * marker and is caught by gate 2. An intent gate would therefore protect nothing against a
+     * deletion in flight — and it could only STRAND residue.
+     *
+     * A PREVIOUS VERSION OF THIS PROOF WAS WRONG (round 2, Codex) and is corrected here rather than
+     * quietly reworded: it claimed an intent "never accompanies an absent image in a legitimate
+     * state". Row 6c is exactly that state, and it is reachable — `createVaultAndPublish` calls
+     * [retireLegacyImage] (which unlinks the image) BEFORE [create] (which clears the markers), so a
+     * crash between them leaves an intent standing over an absent image. The sweep's ACTION was
+     * always right; the JUSTIFICATION was not. What makes 6c safe is that retirement has already
+     * destroyed the only openable image, not that nothing can produce the state.
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
 
+    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
     private fun deleteLeftoverTmp(target: File) {
         leftoverTmp(target).delete()
     }


• Now the MainActivity diff. Let me see the whole main...HEAD diff for MainActivity.diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 21e6d9b..5573c72 100644
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
+        val decided = container.deriveBootDecisionFromDisk()
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
+            val snap = container.deriveBootDecisionFromDisk()
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
@@ -1039,25 +1090,48 @@ private fun ZitroneRoot(
                 // completeTerminalWipe's outermost finally, so the unlock gate is not stranded.
             } finally {
                 // This callback runs on the coordinator's background (confined) dispatcher, so the
-                // Compose-state reconcile is marshaled to Main (round 12d, Gemini). Main.immediate
-                // + container.scope so a rotation mid-wipe cannot cancel it. The disk-truth reads
-                // (hasVault / vaultDestroyPending — fast stats under imageLock) run on Main here,
-                // as they already do from Splash routing. The session→route reconciler is the
-                // parallel main-thread backstop: lockIf published session=null above, so it also
-                // derives the same route from the same disk truth — the two cannot disagree.
+                // Compose-state reconcile is marshaled to Main. Main.immediate + container.scope so a
+                // rotation mid-wipe cannot cancel it.
+                //
+                // ONE ROUTING AUTHORITY (round-3 review, Grok; Kimi concurring). `lockIf` publishes
+                // session=null above, which also wakes the session collector — so this callback and
+                // that collector decide the SAME routing moment. They used to read the same two
+                // stats, and a comment here asserted "the two cannot disagree". W-A made that comment
+                // FALSE: the collector was given the carried `residueSweepHold` and this path was
+                // left on `hasVault()` + `serverDeleteConfirmed()`. With a hold raised earlier in the
+                // process, the collector computes LOCKED while this computes Onboarding, both write
+                // `route`, and the last writer wins — pinning a successfully deleted account to a
+                // lock screen for the rest of the process. That is this unit's signature failure
+                // class, reintroduced by strengthening one consumer and not its twin.
+                //
+                // Both now go through the same derivation with the same inputs.
                 container.scope.launch(Dispatchers.Main.immediate) {
                     identityFingerprint = null
                     unlocked = false
                     lockError = null
-                    vaultExists = container.hasVault()
-                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
-                        // Destroy CONFIRMED (files gone, both markers retired) → fresh-install state.
-                        Route.Onboarding
-                    } else {
-                        // The image (or the server-delete-confirmed marker) survives: the server
-                        // account IS gone, so the only honest route is "finish deleting" with a
-                        // direct retry — NEVER the lock gate (see Route.DeleteIncomplete).
-                        Route.DeleteIncomplete
+                    // A COMPLETED destroy supersedes an earlier non-durable orphan sweep: it proved
+                    // image-bearing absence with its OWN required dirSync and retired both markers
+                    // only after that proof. Leaving a stale boot-time hold raised would withhold
+                    // onboarding over a directory this delete has just proven durably clean.
+                    if (destroySupersedesResidueHold(
+                            vaultProvenAbsent = container.vaultProvenAbsent(),
+                            serverDeleteConfirme
d = container.serverDeleteConfirmed(),
+                        )
+                    ) {
+                        container.residueSweepHold.value = false
+                    }
+                    val snap = container.deriveBootDecisionFromDisk()
+                    vaultExists = snap.present && !snap.legacy
+                    // The mapping matches the previous explicit semantics in every REACHABLE
+                    // post-destroy state: a surviving image implies the markers were NOT retired
+                    // (destroy retires them only after proving absence), so `serverDeleteConfirmed`
+                    // is still set and bootRoute yields DELETE_INCOMPLETE — never the lock gate.
+                    // {image survives, confirmed absent} cannot occur: destroy throws before the
+                    // retire when absence is unproven.
+                    route = when (snap.route) {
+                        BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
+                        BootRoute.ONBOARDING -> Route.Onboarding
+                        BootRoute.LOCKED -> Route.Locked
                     }
                 }
             }
@@ -1174,23 +1248,11 @@ private fun ZitroneRoot(
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
Build successful. Now get the test counts from the test reports.total=487 failures+errors=0 skipped=3 passed=484
diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
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
Test suite: 487 total, 0 failures, 484 passed, 3 skipped — matches the claim exactly. Item H verified.

  Now, deep-dive into source. Key things to read:

  1. MainActivity.kt current full state around the touched regions — session collector, the delete completion callback, lockIf, onBurn.
  2. ZitroneApp.kt current state — container, isLegacyImage, serverDeleteConfirmed, create/destroy paths.
  3. VaultImageStore.kt current state — create(), destroy(), retireLegacyImage(), dirSync, renameIntoPlace, open()'s leftover temp deletion.
  4. Verify burn-related symbols absent.
  5. Verify onBurn vs main.
  6. Grep for completeInterruptedBurn / reconcileOrphanedBurnMarkers.

  Let me grep for the burn symbols and healers first.

• Test numbers confirmed: 487/0/484/3 — matches the claim. Now the deep source verification.NO MATCHES
837:    val onBurn: () -> Unit = {
856:                        PassphraseOutcome.Burn -> onBurn()
1420:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
---
 .../src/main/java/com/zitrone/app/MainActivity.kt  | 178 ++++++++++++++-------
 1 file changed, 120 insertions(+), 58 deletions(-)
---
app/src/main/java/com/zitrone/app/MainActivity.kt:639:    val bootDone by container.bootReconciled.collectAsState()
app/src/main/java/com/zitrone/app/MainActivity.kt:646:        val decided = container.deriveBootDecisionFromDisk()
app/src/main/java/com/zitrone/app/MainActivity.kt:662:        container.startBootReconcile()
app/src/main/java/com/zitrone/app/MainActivity.kt:665:        container.bootReconciled.first { it }
app/src/main/java/com/zitrone/app/MainActivity.kt:667:            val snap = container.deriveBootDecisionFromDisk()
app/src/main/java/com/zitrone/app/MainActivity.kt:727:    // routing authority: it set Route.Onboarding on its own, without awaiting `bootReconciled`,
app/src/main/java/com/zitrone/app/MainActivity.kt:728:    // without the carried `residueSweepHold`, and without consulting `serverDeleteConfirmed()` — so
app/src/main/java/com/zitrone/app/MainActivity.kt:732:    // decision; see bootRoute's `legacyImage` arm, which orders it AFTER the confirmed marker and
app/src/main/java/com/zitrone/app/MainActivity.kt:783:                val snap = container.deriveBootDecisionFromDisk()
app/src/main/java/com/zitrone/app/MainActivity.kt:1100:                // FALSE: the collector was given the carried `residueSweepHold` and this path was
app/src/main/java/com/zitrone/app/MainActivity.kt:1116:                    if (destroySupersedesResidueHold(
app/src/main/java/com/zitrone/app/MainActivity.kt:1121:                        container.residueSweepHold.value = false
app/src/main/java/com/zitrone/app/MainActivity.kt:1123:                    val snap = container.deriveBootDecisionFromDisk()
app/src/main/java/com/zitrone/app/MainActivity.kt:1128:                    // is still set and bootRoute yields DELETE_INCOMPLETE — never the lock gate.
app/src/main/java/com/zitrone/app/ZitroneApp.kt:246:     * `deriveBootDecisionFromDisk()`.
app/src/main/java/com/zitrone/app/ZitroneApp.kt:248:    internal suspend fun deriveBootDecisionFromDisk(): BootDecision = withContext(Dispatchers.IO) {
app/src/main/java/com/zitrone/app/ZitroneApp.kt:252:            residueSweepHold = residueSweepHold.value,
app/src/main/java/com/zitrone/app/ZitroneApp.kt:261:     * [bootReconciled] gates the fresh-install presentation: no route may be derived from disk until
app/src/main/java/com/zitrone/app/ZitroneApp.kt:262:     * boot reconciliation has finished, because the sweep MUTATES what disk says. [residueSweepHold]
app/src/main/java/com/zitrone/app/ZitroneApp.kt:270:    val bootReconciled = MutableStateFlow(false)
app/src/main/java/com/zitrone/app/ZitroneApp.kt:271:    val residueSweepHold = MutableStateFlow(false)
app/src/main/java/com/zitrone/app/ZitroneApp.kt:275:    /** Start boot reconciliation ONCE PER PROCESS; later callers no-op and observe [bootReconciled]. */
app/src/main/java/com/zitrone/app/ZitroneApp.kt:276:    fun startBootReconcile() {
app/src/main/java/com/zitrone/app/ZitroneApp.kt:282:                residueSweepHold.value = hold
app/src/main/java/com/zitrone/app/ZitroneApp.kt:283:                bootReconciled.value = true
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1179: * `bootRoute` inputs themselves.
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1192:    residueSweepHold: Boolean,
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1206:        route = bootRoute(
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1209:            residueSweepHold = residueSweepHold,
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1233:internal fun destroySupersedesResidueHold(
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1238:/** Where a composition must route out of Splash on a cold start — see [bootRoute]. */
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1273:internal fun bootRoute(
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1276:    residueSweepHold: Boolean,
app/src/main/java/com/zitrone/app/ZitroneApp.kt:1283:    residueSweepHold -> BootRoute.LOCKED


• Burn symbols: no matches anywhere in app/src — but wait, `PassphraseOutcome.Burn -> onBurn()` exists. That's fine, onBurn is a stub. Let me verify onBurn is unchanged from main. Also check for burn symbols on main to see whether they exist on main at all (they're deferred, so main shouldn't have them either — actually the duress wipe is being redesigned, so main presumably has `onBurn` stub and `PassphraseOutcome.Burn`).    // Passphrase unlock (§2): ALWAYS available. Enforce the RAM backoff BEFORE the off-main
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
                            // Wrong passphrase / no match / fail-closed create (Rejected), or a create whose
                            // durability was unconfirmed (Retry — a re-entry unlocks the now-present vault).
                            // Both surface the same uniform failure so neither is an oracle.
                            lockError = VaultUnlockRouter.UNIFORM_FAILURE
                            unlocking = false
                        }
                    }
                },
                onFailure = { e ->
fatal: path 'apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt' exists, but not 'app/src/main/java/com/zitrone/app/MainActivity.kt'
hint: Did you mean 'main:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt' aka 'main:./app/src/main/java/com/zitrone/app/MainActivity.kt'?
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
        unl
onBurn is byte-identical to main (same comment + same body). Verify by diffing the onBurn regions exactly — visually they match. Let me do a precise check comparing the two snippets programmatically. Actually the extracted text matches exactly. Also verify no `signalBurnCompleted` anywhere, already done (NO MATCHES across app/src). Also check the whole repo (not just app) for the burn symbols to be thorough. And check l00prite ledger mentions (docs, not code — fine).

  Now read current MainActivity.kt around lines 620-820 (session collector) and 1040-1160 (delete completion callback). Also ZitroneApp.kt current state fully around container functions: hasVault, serverDeleteConfirmed, isLegacyImage, create/destroy (where are they — probably in a coordinator). Let me find destroy() and create() in VaultImageStore, plus serverDeleteConfirmed, retireLegacyImage, createVaultAndPublish.

• `onBurn` is byte-identical to main. Now let me read the current state of the key regions.app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:286:    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:287:    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:311:    fun isLegacyImage(): Boolean =
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:483:    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:506:                    Files.notExists(deleteIntentFile.toPath()) &&
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:507:                        Files.notExists(serverDeletedFile.toPath())
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:761:                            Files.notExists(deleteIntentFile.toPath()) &&
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:762:                                Files.notExists(serverDeletedFile.toPath())
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:929:    fun retireLegacyImage() {
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1022:     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1025:     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1033:        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1037:        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1041:     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1052:            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1053:            deleteIntentFile.delete()
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1054:            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1068:        deleteIntentFile.delete()
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1069:        serverDeletedFile.delete()
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1077:            Files.notExists(deleteIntentFile.toPath()) &&
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1078:            Files.notExists(serverDeletedFile.toPath())
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1092:    fun destroy() {
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1102:            // means the server account is confirmed gone, so write `vault.delete-confirmed`
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1108:            writeDurableMarker(serverDeletedFile)
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1159:    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1169:        imageLock.withLock { deleteIntentFile.exists() && !serverDeletedFile.exists() }
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1172:     * True while the DURABLE delete-intent marker is present — from its durable write until a
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1192:        imageLock.withLock { !Files.notExists(deleteIntentFile.toPath()) }
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1367:     *  6  {delete-intent present, bin present}   D2c delete in flight          REFUSE (gate 1 — the
app
/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1371:     *  7  {delete-confirmed present, ...}        D2c, account provably gone;   REFUSE (gate 2).
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1381:     *  6c {delete-intent, no bin, residue}         a crash between            SWEEP. MISSING ROW,
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1394:     * There is deliberately NO gate on `vault.delete-intent`. [destroy] writes the CONFIRMED marker
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1420:            if (!Files.notExists(serverDeletedFile.toPath())) {
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1463:        const val DELETE_INTENT_FILE = "vault.delete-intent"
app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1470:        const val SERVER_DELETED_FILE = "vault.delete-confirmed"
app/src/main/java/com/zitrone/app/ZitroneApp.kt:314:    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
app/src/main/java/com/zitrone/app/ZitroneApp.kt:323:    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()
app/src/main/java/com/zitrone/app/ZitroneApp.kt:339:    /** Durable auth-protection signal: the delete-intent marker is present (round 16, R15-P2). */
app/src/main/java/com/zitrone/app/ZitroneApp.kt:460:    suspend fun createVaultAndPublish(passphrase: String): Boolean = withContext(Dispatchers.Default) {
app/src/main/java/com/zitrone/app/ZitroneApp.kt:719:    fun destroyVaultForAccountDeletion() {


• Item 1 is the meat. I need to verify:

  1. The destroy path: `destroy()` in VaultImageStore — does it prove image-bearing absence with a REQUIRED dirSync before retiring markers? Read destroy() fully (lines ~1092-1160).
  2. Where is the delete completion callback in MainActivity (the finally at ~1090)? Need full context — what is `completeTerminalWipe`? Read MainActivity 1000-1160.
  3. `destroySupersedesResidueHold` — is `vaultProvenAbsent` at that point the right check? Note that `container.vaultProvenAbsent()` = imageStore.imageBearingProvenAbsent() which checks bin, dek, bin.tmp, dek.tmp via Files.notExists — fail-closed (indeterminate → false).
  4. The claim "destroy throws before the retire when absence is unproven" — verify in destroy() source.
  5. Does clearing the hold affect other consumers? residueSweepHold consumers: deriveBootDecisionFromDisk reads it. Clearing it to false when destroy completed means subsequent routing uses hold=false. Who else reads residueSweepHold? Only deriveBootDecisionFromDisk per grep (ZitroneApp.kt:252). And publish writes it. OK.
  6. Race: session collector vs delete callback — both now call deriveBootDecisionFromDisk. The collector reads residueSweepHold via derive; the delete callback clears the hold BEFORE deriving. Order: the delete callback runs on Main.immediate via container.scope; the collector is a LaunchedEffect(session) on the composition. If the collector runs first (before the callback clears the hold), it derives LOCKED (hold raised) and sets route=Locked; then the callback clears hold and derives ONBOARDING, sets route=Onboarding. Last writer wins — callback wins if it runs last. If callback runs first: clears hold, sets Onboarding; then collector derives ONBOARDING too (hold now false) — agrees. But is there an interleaving where collector runs between the hold-clear and the derive? The callback does hold-clear and derive in the same Main.immediate coroutine — but deriveBootDecisionFromDisk suspends (withContext(Dispatchers.IO))! So between clearing the hold and deriving, the coroutine suspends, and the collector (also on Main) could run. But the collector would derive with hold already cleared → ONBOARDING. Consistent.

  Wait, more subtle: collector derives BEFORE callback clears hold: collector gets LOCKED → route=Locked. Then callback: clears hold, derives ONBOARDING → route=Onboarding. Final state Onboarding. Good.

  But what if the collector runs AFTER the callback's derive but the callback hasn't applied route yet? Callback: clear hold → suspend on IO → derive → resume on Main → set route. Collector on Main could run during the IO suspension: derives with hold=false → ONBOARDING → route=Onboarding. Then callback resumes → route=Onboarding. Same. OK.

  What about the collector's mapping when session==null: `route = when (snap.route) {...}` — in the session collector at line ~771-790, the mapping for LOCKED is Route.Locked, ONBOARDING → Onboarding, DELETE_INCOMPLETE → DeleteIncomplete. That's the `unlocked` else-branch. Need to read it.

  Actually wait — there are two session-related effects: the second LaunchedEffect(Unit) at line 660-680 (boot reconcile consumer), and LaunchedEffect(session) at ~765. Let me read the current MainActivity around 620-800 and 1040-1160.

  Also need to check: does the delete callback's clear of residueSweepHold matter for the BOOT gate? bootReconciled is already true at that point (session existed). The hold is process-scoped; clearing it post-destroy is fine.

  But — a sharper question from the brief: "Is there a state where destroy 'completes' without superseding the sweep's uncertainty?" The destroySupersedesResidueHold check is `vaultProvenAbsent && !serverDeleteConfirmed`. Note: `serverDeleteConfirmed()` uses `serverDeletedFile.exists()` — File.exists(), NOT Files.notExists-based. So `!serverDeleteConfirmed()` is true when the file is absent OR the stat fails. Hmm — an I/O fault on stat → exists() returns false → !serverDeleteConfirmed = true → could clear the hold even though the marker might still be present. But combined with vaultProvenAbsent (fail-closed proven absence of all four image-bearing files). If stat faults are happening, vaultProvenAbsent would also likely be false. Edge case: marker stat fails but image-bearing stats succeed. Then hold cleared, and deriveBootDecision: serverDeleteConfirmed()=false (fault), imagePresent=hasVault()=binFile.exists()=false (proven absent), hold=false, vaultProvenAbsent=true → ONBOARDING while the confirmed marker might actually still be on disk. Next boot: gate 2 refuses sweep, bootRoute serverDeleteConfirmed → DELETE_INCOMPLETE → DeleteIncomplete route → auto-destroy retries. So worst case is one process lifetime of onboarding over a directory whose marker survived, self-correcting at next boot. This mirrors pre-existing main behavior though — main's delete path also used `!container.serverDeleteConfirmed()` with exists(). So no regression vs main.

  Also: is `vaultProvenAbsent` at callback time actually "proven by destroy's dirSync"? No — it's a fresh Files.notExists check at callback time. The destroy did its own proof earlier. The callback re-checks. Fine — fail-closed.

  Now, the claim "a surviving image implies the markers were not retired" — verify destroy(): retires markers only after proving image-bearing absence with required dirSync; throws otherwise. Read destroy() source.

  Also check `completeTerminalWipe` — what calls this callback. Let me read MainActivity around 1000-1160.

• Now the core of Item 1 — I need to read `destroy()` in full and the delete-completion callback context.

• Now the destroy() source is verified: it writes the confirmed marker durably BEFORE unlinking, unlinks bin+dek+tmps, verifies all four gone (via exists() — note: exists()==false conflates absent with stat fault! A stat fault would read "gone" and NOT throw... wait line 1126-1130: `if (binFile.exists() || ...) throw`. So a stat fault returns false → treated as gone → no throw. Hmm, that's fail-open on the verify? But the comment at 1124 says "exists()==false (already-absent) does NOT throw, keeping destroy() idempotent". Then the required dirSync at 1137. Then retire markers. So the claim "destroy throws before the retire when absence is unproven" — the proof uses exists(), not Files.notExists. A stat fault would not throw at 1126. But then dirSync must be DURABLE. A failing filesystem would likely fail dirSync too. Edge: stat fault but dirSync durable → markers retired while image-bearing file possibly survives. Then {image survives, confirmed absent} COULD occur in that pathological case. But this is pre-existing main code (destroy is on main), not part of this delta. The claim in the MainActivity comment "destroy throws before the retire when absence is unproven" is about destroy's design, and the verify uses exists() which is slightly weaker than notExists. Is this in scope? The comment is in the delta (acb5904). The destroy code is from main (round 8 etc.). The comment's claim "{image survives, confirmed absent} cannot occur: destroy throws before the retire when absence is unproven" — with exists()-based verify, an indeterminate stat is treated as absent, so strictly "unproven absence" (in the tristate sense) does NOT throw at that point; only a proven-present throws. However the subsequent required dirSync provides a second barrier. This is a docstring precision issue at most — LOW/INFO. Worth noting: the codebase itself elsewhere (clearDeleteIntent, clearBothMarkersDurably) uses Files.notExists for exactly this reason, while destroy's verify uses exists(). That's pre-existing on main though. Let me check if destroy() differs from main.

  Actually wait — is destroy() modified by this unit? git diff main...HEAD for VaultImageStore showed only additions of sweep etc. The destroy section wasn't in the diff, so it's main's code. The comment in MainActivity is new but describes pre-existing behavior. I'll note the exists() vs notExists asymmetry as INFO since it slightly weakens the "cannot occur" proof, but the practical consequence: if {image survives, markers retired} somehow occurred, the new delete-path routing would give... serverDeleteConfirmed=false, imagePresent=true (bin exists → hasVault true), legacy computed (image present && !confirmed → run isLegacyImage), route: not confirmed, maybe legacy→ONBOARDING or LOCKED via vaultImagePresent. Old code: !vaultExists(false→ vaultExists=hasVault()=true) → else branch → DeleteIncomplete. New code with a surviving image and markers retired: LOCKED (or ONBOARDING if legacy). So in that pathological unreachable state, behavior differs: old said DeleteIncomplete, new says Locked/Onboarding. The commit claims the mapping matches "in every REACHABLE post-destroy state" — the qualifier "reachable" covers this. And if the image survives with markers retired, a lock screen over an image whose account is gone... but that state is genuinely unreachable modulo filesystem faults. OK.

  More important: the destroy success path. After successful destroy: bin/dek/tmps gone AND durable, markers retired AND durable. vaultProvenAbsent → true (Files.notExists all four). serverDeleteConfirmed → false. So supersede → hold cleared. derive: confirmed=false, imagePresent=false, hold=false, provenAbsent=true → ONBOARDING. Matches old behavior (old: vaultExists=false, !confirmed → Onboarding). 

  Failed destroy (file survives): markers NOT retired (throw before retire... wait — order: unlinks, verify, dirSync, THEN retire. If verify fails → markers still present (confirmed marker was written at the start!). So serverDeleteConfirmed=true → supersede=false → hold stays. derive: confirmed=true → DELETE_INCOMPLETE → Route.DeleteIncomplete. Old code: vaultExists=hasVault(); if image survived → true → else branch → DeleteIncomplete. If dek survived but bin gone: old code vaultExists=false, confirmed=true → DeleteIncomplete. New: confirmed → DELETE_INCOMPLETE. Same. 

  dirSync non-durable: throw, markers present → DELETE_INCOMPLETE both. Same.

  Now: what about destroy that succeeded in unlinking but throw at retire (clearBothMarkersDurably false)? markers: deleteIntentFile.delete() and serverDeletedFile.delete() were attempted; dirSync or re-stat failed → throw. Possible state: confirmed marker actually deleted but re-stat indeterminate → throw. Callback: vaultProvenAbsent=true (files gone durably — dirSync at 1137 was DURABLE before retire). serverDeleteConfirmed(): exists() — marker actually deleted → false → supersede=TRUE → hold cleared. derive: confirmed=false, imagePresent=false, hold=false, provenAbsent=true → ONBOARDING. But destroy threw! Was Onboarding right? The files are gone durably, markers gone (or indeterminate). If marker actually survived (re-stat fault), onboarding presented while a confirmed marker survives. Next boot: gate 2 refuses sweep; serverDeleteConfirmed=true → DELETE_INCOMPLETE → auto-destroy retries idempotent destroy over empty dir → succeeds. So worst case self-heals next boot. Old code in same state: vaultExists=false, serverDeleteConfirmed()=false (marker actually deleted) → Onboarding. Same. OK — mapping matches.

  Now the collector race. Read the session collector (LaunchedEffect(session)) current form, lines ~745-800. Already saw diff. In the else-branch (session==null && unlocked): now derives via deriveBootDecisionFromDisk. Both the collector and callback launch on Main (collector is composition-scoped LaunchedEffect on Main; callback uses container.scope Main.immediate). They both write route. Do they now always agree? 

  Case: hold raised at boot (SWEPT_NOT_DURABLE), then user... wait — with hold raised at boot, route = Locked (hold → LOCKED). User can't unlock (no vault — residue was swept). Hmm, actually with hold raised, bin absent. Lock screen with no vault. The session is null. How would a delete happen in that process at all? A delete requires a live session. With hold raised, there's no vault (swept) → can't unlock → no session → no delete. So the disagreement scenario requires: hold raised at boot, but a session exists anyway. How? The hold is raised only when residue was swept (bin absent) — no vault to unlock. Unless... the sweep result was SWEPT_NOT_DURABLE but the vault image itself existed? No — gate 1 refuses when bin present, returns NO_MUTATION → hold=false. So hold=true implies bin was proven absent. No vault → no session → onDeleteAccount unreachable (requires `session ?: return`). Hmm! So how could the collector/callback disagreement ever manifest? 

  Wait — session could be created AFTER boot via create (onboarding). With hold raised, route=Locked... user is stuck at lock screen with no vault — can't create. Actually can they? Locked route with vaultExists=false... hmm, what does the Locked screen show when vaultExists=false? Not sure. But also ONBOARDING requires !hold. So with hold raised, process is stuck at Locked for the rest of the boot — by design (fail-closed). So onDeleteAccount can't run in a held process... unless a session exists from before? The hold is raised at boot before any session. So in a held boot, no session ever exists. Then the delete callback never runs in a held process, and destroySupersedesResidueHold's clear is dead code in practice?

  Hmm wait — is that right? residueSweepHold=true requires SWEPT_NOT_DURABLE: residue existed, was unlinked, durability unproven. bin proven absent. So no image. No unlock possible. No session. So the entire round-3 MEDIUM scenario (hold raised + completed delete in same process) seems unreachable... unless a session can exist without a vault image? Or a create happens: route=Locked though. Onboarding unreachable with hold. So create can't run either. So the supersede path is defense-in-depth for a state that can't currently be reached? That would make the fix harmless but the original bug also unreachable. Not my job to relitigate severity; the fix is safe regardless. Actually wait — could the hold be raised and THEN a vault created in the same process? To create, you need onboarding. Hold → LOCKED. Stuck. So no. OK, the whole thing is belt-and-braces. Fine.

  But there's another subtle consumer interplay I should check: the callback clears `residueSweepHold` — a process-scoped MutableStateFlow. Other consumers: deriveBootDecisionFromDisk (all three call sites). Clearing hold affects future derivations in this process — but only the delete path clears it, and only when destroy completed, after which disk is durably clean, so future derivations are correct either way. No other writer of residueSweepHold except publish (boot) and this clear. bootReconciled unaffected. Good.

  Now — a critical check on the callback: `container.residueSweepHold.value = false` happens on Main.immediate, but `deriveBootDecisionFromDisk()` then reads `residueSweepHold.value` inside withContext(Dispatchers.IO) — reads after the clear, fine.

  Race between callback and collector: collector (LaunchedEffect(session)) — let me read its exact current form to check ordering and the re-check. Also verify the collector only routes when `unlocked` was true. Let me read MainActivity 740-800.

  Also Item 2: afterPublish wrapped in runCatching inside withContext — can't affect verdict (published in finally before). One subtlety: runCatching catches Throwable including CancellationException! If the coroutine is cancelled during afterPublish, runCatching swallows the CancellationException. But that's AFTER publication, so no stranding — the claim/publication contract holds. Swallowing cancellation at the very end of the coroutine body: the coroutine completes normally instead of cancelled — harmless here since nothing follows. Fine.

  Another subtlety on runBootReconcile: `withContext(ioDispatcher) { runCatching { afterPublish() } }` — if cancelled while switching to ioDispatcher before runCatching runs, CancellationException propagates — but publication already happened in finally. Fine.

  Item G contract: claim once-only ✓ (CAS in production; claim() called synchronously before launch — good, no race). publish in finally ✓. Fail-closed default: result starts SWEPT_NOT_DURABLE; sweep throwing → SWEPT_NOT_DURABLE → hold=true. CancellationException rethrown → finally publishes hold=true (result still default) → waiters released withholding onboarding ✓. Cannot be stranded ✓.

  Wait — one thing: if claim() returns true and scope.launch itself... the launch happens after claim. If scope is dead (process scope can't die). OK.

  Another check: publish is called with `result == ResidueSweepResult.SWEPT_NOT_DURABLE` — hold=true only when not durable. NO_MUTATION and SWEPT_DURABLE → hold=false. Correct: NO_MUTATION means gates refused or already clean — disk unchanged, routing falls to other facts. Hmm — but wait: NO_MUTATION when gate 1 refuses because bin is PRESENT — fine, lock screen. When gate 2 refuses (confirmed marker) — DELETE_INCOMPLETE owns it. When already clean — proven absent → ONBOARDING. All fine.

  But subtle: NO_MUTATION on gate-2-refusal where the confirmed marker is INDETERMINATE (stat fault) — `!Files.notExists` true → refuse → NO_MUTATION → hold=false. Then derive: serverDeleteConfirmed() uses exists() → fault → false! imagePresent: hasVault → exists → maybe false. hold=false. vaultProvenAbsent: Files.notExists all — if bin stat faults → false → LOCKED (else branch). Fail-closed, good. If only the marker stat faults but everything else stats fine: confirmed=false (exists conflates), provenAbsent=true → ONBOARDING while a confirmed marker may be present. Next boot same fault... hmm, persistent fault on one file's stat is exotic. Pre-existing: serverDeleteConfirmed() is main's code using exists(). On main, Splash routed via serverDeleteConfirmed() too, same behavior. Not a regression. 

  Now item E's writer/reader table completeness. Hunt the missing row. Files in vault dir: vault.bin, vault.dek, vault.bin.tmp, vault.dek.tmp, vault.delete-intent, vault.delete-confirmed. States with bin absent + (dek|bin.tmp|dek.tmp) present:

  Writers of dek without bin:
  - create(): DEK-first barrier. Need to read create() to confirm order: clears both markers durably first? Lines 483-530. Let me read create() and retireLegacyImage().

  Row 6c: {delete-intent, no bin, residue} — crash between retireLegacyImage (unlinks bin, then dek) and create() clearing markers. Wait — but retireLegacyImage unlinks bin THEN dek. If it crashed between, dek still present → residue = dek. And delete-intent present (from a prior delete flow?). Hmm, the scenario: user initiated delete (intent marker), then... how does retireLegacyImage run during a delete? Actually row 6c says: createVaultAndPublish calls retireLegacyImage() BEFORE create() clears markers. So the state is: legacy image present + delete-intent marker present (leftover from a crashed delete that was never confirmed — intent never cleared except by destroy or create). User re-onboards (legacy → onboarding). createVaultAndPublish: retireLegacyImage() unlinks bin (crash!) → {intent present, bin absent, dek present}. Boot: gate 1 passes (bin absent), gate 2 passes (no confirmed marker), sweep deletes dek. Is that safe? The dek belonged to the retired legacy image whose bin is already gone — dead data. Yes safe.

  But wait — what about {delete-intent, no bin, residue} from a different writer: destroy() writes CONFIRMED first, so any destroy unlink carries confirmed → gate 2. markDeleteIntent then crash before server confirm: no unlink happens (bin still present). So intent + absent bin only via retireLegacyImage or create's DEK barrier. Covered by 6c.

  What about {confirmed marker INDETERMINATE + bin absent + residue} → gate 2 refuses (row 8). ✓.

  Missing row hunt: what about `vault.dek.tmp` present with bin present? Gate 1 refuses (bin present) — row 4. ✓. What about residue + intent + confirmed both? Gate 2 refuses — row 7 ✓.

  What about the biometric wrap file? create() writes biometric material? There's BiometricVaultKeyCipher — does it store a wrapped DEK file in the vault dir? Let me check what files exist. The brief says files: vault.dek, vault.bin.tmp, vault.dek.tmp, vault.bin, markers. But there might be a biometric wrap file (e.g., vault.dek.bio?). If biometric enrollment writes a file keyed off the DEK and the sweep doesn't touch it, then after sweep + re-create, a stale biometric wrap could exist. Let me grep for biometric file names. Actually e32f0aa "biometric-enable atomicity (closes the disclosed orphan gap)" — there's a biometric wrap orphan concept. Let me check the store's file set.

  Also item C: verify create() writes DEK first and destroy() writes confirmed marker durably before unlinking (verified above: writeDurableMarker(serverDeletedFile) at line 1108 before unlinks at 1111 ✓). The healers completeInterruptedBurn/reconcileOrphanedBurnMarkers — no references anywhere (grep NO MATCHES across app/src; let me also grep whole repo). Their trigger states unreachable: burn never runs (onBurn stub). ✓ — need to confirm nothing in comments still assumes they run. Grep for "interrupted burn" mentions.

  Item D: on main, {bin absent, dek present} → hasVault false → onboarding → create overwrites dek. W-A sweeps durably first, or withholds onboarding if not durable. Strictly better — need to confirm main's Splash routing: main routes Onboarding when !vaultExists && !serverDeleteConfirmed. Yes (from main's SplashScreen onFinished in the diff). And create() would then retireLegacyImage? On main with dek present and bin absent, create writes new DEK over the orphan (DEK-first barrier). So main overwrites; W-A deletes first. No state made worse: W-A deletes an orphan dek that opens nothing. ✓ but verify create() handles {bin absent, dek present} — create clears markers, writes dek durably, then bin.

  Item F: enumerate consumers of boot-routing state:
  1. Splash LaunchedEffect(splashFinished, bootDone) — waits bootDone ✓, uses deriveBootDecisionFromDisk ✓ full inputs ✓.
  2. LaunchedEffect(Unit) post-boot re-derive — awaits bootReconciled.first{it} ✓, session==null check with re-check after suspend ✓.
  3. Session collector LaunchedEffect(session) — else-branch uses deriveBootDecisionFromDisk ✓. But does it await bootReconciled? The session collector fires when session changes — session can't be non-null before boot reconcile? Actually session starts null; the collector fires on composition with session=null and unlocked=false → neither branch. When does the else-branch (session==null && unlocked) fire? After unlock→lock transition, which is way after boot. OK.
  4. Delete completion callback — uses deriveBootDecisionFromDisk + supersede ✓.
  5. Any other readers of hasVault() for routing? Line 631: `var vaultExists by remember { mutableStateOf(container.hasVault()) }` — initial seed, pre-reconcile. It's used where? vaultExists gates... the initial value is used for nothing routing-wise until Splash decision overwrites? The Splash effect sets vaultExists = decided.present && !decided.legacy. But between composition start and the decision, vaultExists holds a raw hasVault() value — used by Locked screen rendering? Route starts at Splash so Locked isn't composed. Probably fine but check where vaultExists is read. Let me grep.

  Also — the initial `route` value: Route.Splash presumably. And what about `container.hasVault()` other callers? Grep.

  Let me also read create() and retireLegacyImage(), and the session collector current text, and BootReconcileOwnerTest + BootRouteTest + DeriveBootDecisionTest + SweepOrphanedResidueTest for item I. That's a lot of reading; let me do it in parallel batches.

• `destroy()` verified. Now let me read `create()`, `retireLegacyImage()`, and the current session collector.

• The session collector now uses `deriveBootDecisionFromDisk` — same as the callback. Both agree except for the hold clear, which only the callback does. Since the callback clears the hold before deriving, and the collector derives with the hold value at its run time:

  Scenario (hold somehow raised + completed destroy):
  - Callback: clears hold → derive → ONBOARDING → route=Onboarding.
  - Collector: derives with whatever hold is at its execution time. If before clear: LOCKED → route=Locked; but then callback later writes Onboarding (collector ran first). If after clear: ONBOARDING. Final always Onboarding IF callback runs last, or both agree ONBOARDING if callback runs first.

  Wait — can the collector run AFTER the callback completes? Both are woken by session=null. The collector is a composition LaunchedEffect collecting container.session; the callback is launched on container.scope Main.immediate from the coordinator's confined dispatcher. The session=null publish happens inside lockIf (in finishUi, before destroyVault runs — actually lockIf runs in completeTerminalWipe's finishUi, then destroyVault, then finally launches the callback). So the collector wakes potentially BEFORE destroy() completes — at that point files still present, confirmed marker present → derive → DELETE_INCOMPLETE → route=DeleteIncomplete. Then callback after destroy: hold cleared (if supersede) → ONBOARDING → route=Onboarding. Last writer = callback. Good — callback always runs after the collector's immediate wake because it's launched after the whole wipe completes. Could the collector wake a second time after the callback? session flow already null; StateFlow conflation — collector already processed null. No second wake. So final = callback's decision. And both derive from the same function, so if they ever process the same disk state they agree. The race is settled.

  But subtle: collector's derive runs on composition context (Main) and suspends on IO. Callback's too. If collector's IO work is slow (legacy probe ~1MiB decrypt) and resumes AFTER the callback wrote Onboarding... The collector woke at session=null (before destroy finished). It derives: at that moment, confirmed marker present (destroy writes it first, and even after success... wait after successful destroy markers are retired — timing matters). Timeline:

  t0: lockIf publishes session=null. Collector wakes on Main, calls deriveBootDecisionFromDisk → suspends to IO.
  t1: destroyVault runs on coordinator dispatcher: unlinks files, retires markers.
  t2: callback launched on Main.immediate → clears hold (reads disk: absent, confirmed=false) → derive → suspends to IO.
  t3: collector's IO resumes: reads disk NOW (not at t0!) — deriveBootDecisionFromDisk reads all facts inside withContext(IO) at execution time. So the collector reads post-destroy disk: confirmed=false, absent, hold — was hold cleared by then? The callback clears hold on Main before its derive. Collector's IO read happens whenever the IO dispatcher gets to it — could be before or after the Main-resident hold clear.

  Case A: collector's derive executes before callback's hold-clear: reads hold=true (if a hold was raised — but as established, hold can't coexist with a session... skip), confirmed=false, absent=true → ONBOARDING (if hold false) → route=Onboarding. Callback → Onboarding. Agree.

  Case B: after clear: same ONBOARDING.

  If no hold: everything agrees trivially.

  The only disagreement requires hold=true with a live session, which requires a vault to unlock while hold is raised. hold=true → sweep unlinked residue and bin was proven absent at boot → no vault at boot. A session requires create or unlock. Create requires route=Onboarding, which requires hold=false. So unreachable in-process. Conclusion: the fix is correct and the race is closed; the supersede is defense-in-depth for an unreachable compound state. Fine — safe either way.

  Wait, one more: is there any OTHER writer of `route` post-destroy that could stomp? The second LaunchedEffect(Unit) (post-boot re-derive) runs once per composition after bootReconciled. On a rotation mid-delete: new composition → LaunchedEffect(Unit) → bootReconciled already true → session==null → derive → DELETE_INCOMPLETE (marker present mid-destroy) → route=DeleteIncomplete. Then callback writes final. If destroy completed before rotation's re-derive: ONBOARDING... but wait the re-derive's mapping: `BootRoute.ONBOARDING -> if (route == Route.Locked) route = Route.Onboarding` — only moves stale Locked forward. And DELETE_INCOMPLETE → always set. And it checks session==null with re-check. OK. And the re-derive does NOT clear the hold — but if destroy completed, hold might still be true (if callback hasn't run): derive → hold=true → LOCKED → mapping does nothing (Unit). Then callback clears hold → ONBOARDING. Fine.

  Now, does the delete callback's hold-clear race the rotation re-derive in a bad way? Re-derive reads hold=true → LOCKED → no-op (doesn't move route). Callback → Onboarding. Fine. Reverse order: callback first (Onboarding), re-derive: hold=false → ONBOARDING → only if route==Locked — route is Onboarding already, no-op. Fine.

  Now Item 1 remaining verifications:
  - "Does clearing a process-scoped hold from the delete path affect any OTHER consumer of that hold?" Consumers of residueSweepHold: deriveBootDecisionFromDisk only. Clearing it when destroy completed is semantically right. Also Splash's LaunchedEffect(splashFinished, bootDone) — bootDone already true, won't refire (splashFinished stays true, route != Splash guard). OK.

  - Check `destroyVaultForAccountDeletion` in ZitroneApp (line 719) — what it does, whether it also wipes biometric (BiometricVaultKeyCipher) and whether that interacts with the sweep. Let me read it.

  Also `createVaultAndPublish` (line 460) — calls retireLegacyImage before create; verify row 6c claim.

  Also check `attemptPassphrase`/`onUnlockSuccess` etc. for any other route writes keyed on legacy. And check for any remaining caller of hasVault() in routing. Let me grep hasVault and vaultExists usages.

• Now let me check remaining consumers of routing state and the container's destroy/create wrappers.619:    // Splash → Locked (which keys on hasVault() and would fake-lock a live, unlocked session and
631:    var vaultExists by remember { mutableStateOf(container.hasVault()) }
650:        vaultExists = decided.present && !decided.legacy
673:            vaultExists = snap.present && !snap.legacy
699:                !container.hasVault() && !container.serverDeleteConfirmed()
703:                vaultExists = false
785:                vaultExists = snap.present && !snap.legacy
861:                            vaultExists = false
895:    val biometricUnlockAvailable = vaultExists && biometricEnabled && canAuthenticateStrong
959:    // re-derive hasVault() and route to unlock — never blindly re-call create() (which would throw
982:                    vaultExists = true
993:                    if (container.hasVault()) {
996:                        vaultExists = true
1012:    // on disk). hasVault() is then false, so route to Onboarding (fresh-install state), never
1101:                // left on `hasVault()` + `serverDeleteConfirmed()`. With a hold raised earlier in the
1124:                    vaultExists = snap.present && !snap.legacy
1185:        lemonDropVeilState is LemonDropVeil.Locked && !vaultExists
1192:            !vaultExists -> Unit // Locked veil is not composed pre-vault


• Important: `createVaultAndPublish` — `if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()` then `imageStore.create(...)`. So the retire happens BEFORE create() clears markers — confirming row 6c's claim: a crash between retireLegacyImage (bin unlinked) and create (markers cleared) leaves {delete-intent present, no bin, residue}. ✓ (the docstring correction is accurate).

  Note the biometric wrap: `biometricStore.clear()` — biometric wrap is stored in prefs (PREFS_SETTINGS), not in the vault dir, per comment at ZitroneApp:482. So no biometric file in vault dir for the sweep to miss. Good — but let me double check there's no other file in baseDir. Check what files VaultImageStore writes: binFile, dekFile, tmps, markers. BiometricVaultKeyCipher — where does it persist the wrap? "the biometric wrap" in PREFS_SETTINGS prefs. OK.

  Line 699: `!container.hasVault() && !container.serverDeleteConfirmed()` — what is this? Let me read around 690-710. It's in the legacy/intent reconcile effect probably. Let me check — could be an ONBOARDING-deciding consumer not using the carried verdict (item F!). Read lines 690-710.

• Line 699 has a raw `hasVault()` routing read — let me inspect that context.

• Line 699: `onRetryDestroy` — the DeleteIncomplete retry. It judges success by `!hasVault() && !serverDeleteConfirmed()` — this is the OLD explicit semantics, not the unified derivation. Is that a problem for item F/A0? This path decides "destroy retry succeeded → Onboarding". It doesn't consult residueSweepHold. Scenario: hold raised at boot... but wait, with hold raised at boot, route=LOCKED, never DeleteIncomplete? No — bootRoute precedence: serverDeleteConfirmed → DELETE_INCOMPLETE comes BEFORE residueSweepHold. So at boot with {confirmed marker, hold raised}: DELETE_INCOMPLETE. User retries destroy, succeeds: files gone, markers retired. `!hasVault() && !serverDeleteConfirmed()` → true → route=Onboarding. But the hold is still raised! The collector/callback now would say... well, this path doesn't touch route via the derivation. It sets Onboarding directly while residueSweepHold=true. Is that a violation of "onboarding requires the carried verdict"?

  Hmm! This is exactly the sibling-call-site question (A0). onRetryDestroy routes to Onboarding WITHOUT clearing or consulting residueSweepHold. Compare: the delete completion callback now clears the hold via destroySupersedesResidueHold before routing Onboarding. onRetryDestroy — a successful destroy retry, the SAME semantic event (completed destroy, disk durably clean) — does NOT clear the hold and does NOT consult it.

  Consequences: after onRetryDestroy success → route=Onboarding. User creates a vault. Fine. The stale hold remains true for the process. Does the raised hold affect anything later? residueSweepHold is read only in deriveBootDecisionFromDisk. Later derivations: session collector on lock (unlocked→session null): derive with hold=true, image now present (new vault created) → vaultImagePresent → LOCKED. Hold irrelevant when image present. If the user then deletes the account again: callback clears hold via supersede anyway. So the stale hold is mostly harmless BUT:

  Scenario where it bites: hold=true (boot sweep not durable) + confirmed marker present (DELETE_INCOMPLETE at boot). Retry destroy succeeds → Onboarding. Rotation → new composition → the post-boot re-derive LaunchedEffect(Unit): bootReconciled true, session==null → derive: confirmed=false, imagePresent=false, hold=TRUE → LOCKED → mapping: LOCKED → Unit (no-op, doesn't move route). OK no-op. Splash effect: route != Splash. So rotation keeps Onboarding (route var re-seeded? No — route is composition state, rotation recreates it... route seed: what's the initial route on a new composition? Let me check — probably Route.Splash always, then bootDone=true, splashFinished after animation → Splash decision effect derives → hold=true → LOCKED → route=Locked! 

  Wait: on a fresh composition after rotation, route seeds to... let me check. `var route by remember { mutableStateOf(Route.Splash) }` presumably. Then splashFinished eventually true, bootDone true → derive → hold=true, no vault, no marker → LOCKED → the user who just successfully retried a destroy and was on Onboarding gets rotated into a LOCK screen with no vault. Stuck (no vault to unlock). That's a real user-facing wedge for the rest of the process — exactly the class the round-3 fix addressed in the delete callback, but the retry path (onRetryDestroy) was NOT given the supersede.

  Hold on — but is the compound state {hold=true, confirmed marker} reachable? hold=true requires sweep swept residue non-durably at boot. Confirmed marker present → gate 2 refuses → NO_MUTATION → hold=false! Gate 2: if confirmed marker present-or-indeterminate → refuse → NO_MUTATION → publish(false). So hold can never be true when a confirmed marker exists at boot. The sweep either refuses (marker present) or sweeps (marker absent). So {hold=true, serverDeleteConfirmed at boot} is impossible. 

  Could the marker appear AFTER the sweep in the same boot while hold=true? The marker is written by markServerDeleteConfirmed/destroy, which require a live session/delete flow. With hold=true there's no vault (swept), no session, onboarding withheld → no delete flow. So unreachable again. Phew.

  But wait — one more: DeleteIncomplete at boot with hold=false. Retry succeeds → Onboarding, hold=false. Rotation → derive → ONBOARDING (provenAbsent=true after destroy's durable retire) → mapping in Splash effect → route=Onboarding. Fine.

  So onRetryDestroy not consulting the hold: given hold=true implies no confirmed marker at boot and no session possible, onRetryDestroy is only reachable via Route.DeleteIncomplete, which requires serverDeleteConfirmed (boot) or the delete flow (session). Both imply hold=false. So onRetryDestroy never runs with hold=true. It's safe, though it routes Onboarding on `!hasVault()` rather than proven absence — after a destroy that reported success via re-stat (hasVault false) and marker retired. Note: it uses hasVault() (bin only) not vaultProvenAbsent — after destroy(), if dek survived but bin gone, hasVault()=false, marker retired → "confirmed" → Onboarding while a dek orphan survives. But destroy() throws DestroyFailed if dek survives (verify at 1126 checks dekFile.exists()), and the markers are retired only after the verify. So marker-retired implies all four verified gone + dirSync durable. OK safe.

  Edge: destroy threw before retiring markers but after unlinking files? Then serverDeleteConfirmed()=true → confirmed=false → deleteRetryFailed=true → retry again. Fine.

  Now — A0 also asks: "The removed legacy effect: confirm no OTHER path still routes on legacy without the confirmed-marker precedence." onUnlockPassphrase's LegacyImage branch (line 857-862): sets vaultExists=false, route=Onboarding WITHOUT checking serverDeleteConfirmed. Is that reachable with a confirmed marker? To attempt a passphrase unlock you must be on the lock screen. With a confirmed marker, boot routes DELETE_INCOMPLETE, never Locked. Session-collector: confirmed → DeleteIncomplete. So the lock screen with a legacy image + confirmed marker: unreachable? The unlock-time backstop fires when open() throws LegacyImage during attemptUnlockOrAdd. With confirmed marker present and legacy image: boot → DeleteIncomplete → auto-destroy runs destroy() → destroys the legacy image. So no unlock attempt happens. OK — and this branch is unchanged from main (it's in main too? The diff didn't touch it. Yes, main has it). Not a regression.

  Now item F: enumerate every consumer:
  1. Splash decision effect (646): waits bootDone ✓ carried verdict via derive ✓ re-check after suspend ✓.
  2. Post-boot re-derive (660): awaits bootReconciled ✓ session check + re-check ✓ full inputs ✓.
  3. Session collector (783): full derivation ✓. Doesn't await bootReconciled — but session transitions null→non-null→null can't happen before boot reconcile? Actually could a session exist before bootReconciled? Session created via unlock/create, which requires leaving Splash, which requires bootDone. Process-scoped session restored? Is there any session restore at startup that doesn't go through UI? container.session starts null (MutableStateFlow(null) presumably). So first collector wake with live!=null happens only after an unlock, which is after bootDone. ✓.
  4. Delete callback (1116-1135): supersede + full derivation ✓.
  5. onRetryDestroy: analyzed — reachable only with hold=false; uses disk truth post-destroy. It routes Onboarding on bin-absence rather than proven absence, but destroy's own verify+dirSync+marker-retire makes bin-absent+marker-absent imply proven-clean. Acceptable; arguably INFO.

  One more consumer-ish: `vaultExists` seed at 631 `mutableStateOf(container.hasVault())` — raw read at composition time, before bootDone. Where is vaultExists consumed for routing? Line 895 biometricUnlockAvailable (UI affordance, not routing), 1185/1192 veil. The Locked screen uses vaultExists? On a fresh composition mid-boot route=Splash, so no Locked screen. After bootDone, vaultExists is overwritten by the decision. OK.

  What about Route.Locked screen composability when vaultExists=false (hold case)? Route.Locked with no vault — the lock screen shows passphrase entry; attempts fail as wrong passphrase (uniform failure). Acceptable fail-closed UX.

  Now, what seeds `route` initially? Check around 600-632.

  Also item D verification: main's behavior for {bin absent, dek present}: main Splash onFinished: serverDeleteConfirmed? no → vaultExists (hasVault=false) → else → Onboarding. Then create overwrites stray dek (create kdoc line 469-470). W-A: sweeps first. Strictly better ✓. Also {bin absent, bin.tmp present}: main → Onboarding while a complete outer image sits in tmp; create() then... does create clear leftover tmps? renameIntoPlace(dekFile) — overwrites dek.tmp; renameIntoPlace(binFile) overwrites bin.tmp. So create overwrites. But showing onboarding over a recoverable vault is the bug W-A fixes; the sweep deletes it instead. "No state is made worse": the residue opens nothing without... wait, bin.tmp IS a complete outer image + dek wrapped by device key — recoverable in principle. On main it gets overwritten by create anyway. W-A deletes it at boot. Both lose it; W-A loses it earlier but presents honestly. OK.

  Item E remaining: writer/reader table — hunt the missing row beyond 6c. Files: bin, dek, bin.tmp, dek.tmp, intent, confirmed. States with bin proven absent and residue (dek/bin.tmp/dek.tmp):

  Writers of residue:
  1. create() crash after DEK barrier, before bin rename: {dek}, possibly {dek, dek.tmp?} no — dek.tmp renamed into dek. {dek} or {dek, bin.tmp?} no — bin.tmp created only during renameIntoPlace(binFile) which is crash-mid-rename: crash during renameIntoPlace(binFile) leaves {dek, bin.tmp} (row 3 ✓) or {dek} (row 1 ✓). Crash during renameIntoPlace(dekFile): {dek.tmp} (row 2 ✓).
  2. retireLegacyImage crash: unlinks bin THEN dek: {dek} (row 1b ✓); crash before bin unlink: bin present → gate 1. Also retireLegacyImage deletes tmps. What if crash between bin unlink and dek unlink — covered 1b.
  3. destroy(): always writes confirmed marker first → gate 2 (rows 7, 8 ✓).
  4. Biometric enable/disable: writes to prefs, not vault dir (biometric wrap in PREFS_SETTINGS). Let me verify biometricStore doesn't write into baseDir. Check BiometricVaultKeyCipher / biometricStore.
  5. reseal (VaultRuntime.close / signalStore reseal): writes via renameIntoPlace? If a reseal crashes mid-write with... bin exists (it's a reseal over existing vault) → gate 1. But reseal writes bin.tmp then renames — crash leaves {bin, bin.tmp} → gate 1 refuses, and open() deletes leftover temps per row 3 note ("[open] deletes leftover temps"). ✓.
  6. What about `open()` itself deleting leftover temps — does open() delete dek.tmp/bin.tmp when bin present? Row 3 says open deletes leftover temps. Fine.
  7. Signal store files? ZitroneSignalStore — where does it live? If signal store files live in the vault dir... they'd not be image-bearing. The sweep only deletes dek + tmps. Fine.
  8. What about a crash during sweep itself: sweep unlinks dek, crashes before dirSync → next boot: residue maybe absent (unlink survived) → NO_MUTATION (already clean) → hold=false → ONBOARDING. But the prior boot's hold was process-scoped; new boot re-sweeps. If the unlink didn't survive (journal replay), residue back → sweep again. ✓ idempotent.

  Missing-row candidates: {dek.tmp only} row 2 ✓. {bin.tmp only, no dek}? Writer: crash in renameIntoPlace(binFile) after dek renamed? That yields {dek, bin.tmp}. {bin.tmp, no dek, no bin}: destroy deletes dekFile before... destroy has confirmed marker. retireLegacyImage: deletes bin, dek, then tmps — crash between dek delete and tmp deletes with bin already gone: {bin.tmp, dek.tmp} residue, no dek, no markers → sweep ✓ (table doesn't have an explicit row for tmp-only-after-retire but rows 2/3 cover temps generically; gate logic doesn't care about writers anyway — the proof obligation is that sweeping any residue with bin proven absent + no confirmed marker is safe. The remaining writer of temps is renameIntoPlace during create (rows 2,3) and reseal (bin present → gate 1) and destroy (confirmed → gate 2). retireLegacyImage temps: it deletes tmps after dek; crash leaves tmps over absent bin — swept; the image is already destroyed. Safe ✓.

  What about the delete-intent marker + residue + no bin, from destroy? destroy writes confirmed first — intent without confirmed can't accompany destroy unlinks. ✓ (row 6c reasoning verified against source: markDeleteIntent at 1032, server request, markServerDeleteConfirmed only after confirm, destroy writes confirmed before unlinks at 1108.)

  Biometric check: let me quickly verify biometricStore location. Grep.

  Then read the tests (BootReconcileOwnerTest, BootRouteTest, DeriveBootDecisionTest, SweepOrphanedResidueTest) for items G and I. And check initial route seed. Also check ZitroneApp.kt line ~314 isLegacyImage wrapper and serverDeleteConfirmed wrapper. Also confirm `dirSync` implementation exists and NOT_DURABLE semantics (main code). Let me batch reads.

• Let me verify the biometric wrap location, the initial route seed, and then read the test files.623:    var route by remember {
app/src/main/java/com/zitrone/app/ZitroneApp.kt:165:    val biometricStore = BiometricUnlockStore(keyStoreManager)
app/src/main/java/com/zitrone/app/ZitroneApp.kt:482:            // users exist). PREFS_SETTINGS (device settings + the biometric wrap) is deliberately
40: * keeping the happy path (`open()` returns Unit) clean. It is deliberately DISTINCT
339:    fun open() {
485:            // Claim the single-instance registration BEFORE any work (mirrors open()); a
520:                    // instead of persisting and bricking the next open().
543:                            // vault.bin → open() = MissingImage → a retried create() overwrites it.
347:                deleteLeftoverTmp(binFile)
348:                deleteLeftoverTmp(dekFile)
942:            deleteLeftoverTmp(binFile)
943:            deleteLeftoverTmp(dekFile)
947:                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
1113:            deleteLeftoverTmp(binFile)
1114:            deleteLeftoverTmp(dekFile)
1127:                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
1307:            Files.notExists(leftoverTmp(binFile).toPath()) &&
1308:            Files.notExists(leftoverTmp(dekFile).toPath())


• Good. Now the test files — items G and I. Read the three test files plus BootRouteTest. They're sizable (343+256+210+414 lines). Let me read them, focusing on whether tests test the real logic and whether headers match mutations. I can read all four in parallel but that's ~1200 lines. Given the importance, read them. Actually — to save context, I could delegate the test-quality audit to an explore subagent... but item I is a binding focus item; I should read at least the new/changed tests myself. The delta tests (DestroySupersedesResidueHoldTest, the afterPublish test, row-6b) I've already seen in the diff. Let me check the BootReconcileOwnerTest harness and BootRouteTest coverage of gate 2 and row-7, plus SweepOrphanedResidueTest row 6b/6c tests. Read the test files.

• Now the test files for items G and I.

• BootReconcileOwnerTest is solid. One note: the new afterPublish test's docstring says "Production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`" — that's now STALE: acb5904 removed the local runCatching from production's lambda. The test docstring claims production's lambda wraps itself, but production no longer does. Doc-only inaccuracy, introduced by this very delta — ironic given the unit's history. LOW/INFO. Also "MUTATION UNIQUELY CAUGHT: moving afterPublish() ahead of the finally" — does the test actually catch that? If afterPublish ran before publish... with a throwing afterPublish before the finally: the throw would propagate... wait if afterPublish is moved ahead of the finally's publish, the throw would go... hmm, actually in the mutated code the afterPublish throw would happen before publish, then finally publishes anyway (finally still runs on throw). Then the coroutine throws — but in runTest with StandardTestDispatcher, an uncaught exception in a launched child... would fail the test. Actually in the real mutated code the throw would propagate out of launch → test framework catches → test fails. So yes caught. And "cannot unpublish" — done/hold asserted. OK.

  But wait — one gap: the test asserts ordering via outcome, but would it catch "runCatching removed" (afterPublish throw escapes)? In runTest, an uncaught exception in a child coroutine of the test scope fails the test at advanceUntilIdle/end. Yes it would fail. Fine.

  Now read BootRouteTest and DeriveBootDecisionTest and SweepOrphanedResidueTest. Also check BootRouteTest covers gate precedence incl. row-7 (confirmed beats legacy).BootRouteTest is strong. Now DeriveBootDecisionTest and SweepOrphanedResidueTest.A few remaining verifications:

  1. Row 6b test: the test creates state {binTmp with a REAL image, intent marker, dek deleted}. Note: the test at line 184-188 creates a real vault, deletes bin, writes binTmp, writes intent. dek still exists (created by store.create). Sweep sweeps. But note the sweep in this state: a REAL wrapped DEK + real outer image in bin.tmp — the residue is genuinely recoverable (dek + bin.tmp = complete vault). The sweep deletes it. Justification: retirement destroyed the only openable image... wait, in the test the bin was deleted manually (simulating retireLegacyImage's unlink). But the dek + bin.tmp together ARE a complete recoverable vault (open() would... no — open() keys on binFile; bin.tmp is deleted as leftover. So app-level it's unopenable; forensically it's ciphertext without... the dek is device-key wrapped, so a device owner could unwrap. It's "recoverable" in principle. The stated policy (row 3): "the main file is the last durable state" — temps are treated as dead. Consistent with open() deleting leftover temps. OK — this is a policy choice, consistently applied.

  2. The row-6b docstring correction — verify claim: "createVaultAndPublish calls retireLegacyImage() BEFORE create() clears the markers" — verified at ZitroneApp.kt:465-468: `if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()` then `imageStore.create(...)`; create clears markers at line 505-510. ✓. And intent marker over an absent image: the scenario requires a legacy image + an intent marker standing. Intent marker from a prior crashed delete (0.9.1 was fresh-install only — can a legacy image have an intent marker? delete flow is 0.9.2; a 0.9.2 vault is v3. Hmm: can {legacy image, intent marker} coexist? The delete flow requires a session on a v3 vault. If user has v3 vault + intent marker (crashed delete), then... the image is v3, not legacy. retireLegacyImage only runs on legacy. So row 6c's exact scenario {intent, no bin, residue} via retireLegacyImage requires a legacy image coexisting with an intent marker. How? A 0.9.1 install upgraded to 0.9.2 has a v2 image and never had an account-delete (0.9.1 fresh-install only, no account delete? Actually 0.9.1 was fresh-install-only per the kdoc at 924-925: "a retired v2 image has no server account this device is responsible for deleting (0.9.1 was fresh-install-only)"). So a legacy image should never have an intent marker...

  Wait, but the marker could exist from a PRIOR 0.9.2 v3 vault that was deleted (crashed mid-delete, intent only), then... the v3 vault still exists (delete not confirmed). Then the v2/v3 situation — a legacy image plus intent: user had 0.9.1 (v2 image), upgraded to 0.9.2, app routes legacy → onboarding → but before creating, does anything write an intent? No. Hmm, alternatively: v3 vault exists, delete initiated (intent written), crash. Boot: intent-only → normal unlock. User unlocks v3... the image is v3, not legacy — retireLegacyImage won't run. So {intent + legacy image} seems hard to construct. But regardless, row 6c's state {intent, no bin, residue} — the docstring's crash window: createVaultAndPublish with a legacy image AND a standing intent. If unreachable for legacy+intent, what about the general claim "a crash between retireLegacyImage and create leaves an intent standing over an absent image"? Only if an intent was standing. Is there another path where create() clears markers with residue + intent and no bin, without retire? create() itself: `require(!binFile.exists())`. create clears markers FIRST, then writes dek. So crash in create after marker-clear: markers gone. Not intent-standing.

  Hmm, so is row 6c actually reachable? The docstring says createVaultAndPublish calls retireLegacyImage before create() clears markers. For an intent to be standing while a legacy image exists... Actually wait: can a v2 image receive an account delete? 0.9.2 upgrade with v2 image → routed to onboarding (can't unlock). No session → no delete. So no intent. Alternatively the intent marker file could linger from... any other writer of delete-intent? markDeleteIntent only from the delete flow. So {legacy image + intent} may be unreachable, making row 6c's writer attribution shaky. BUT the table's claim is about what the gate DOES with the state, and the corrected docstring explicitly says the state IS reachable via that crash window. If the state is actually unreachable, the corrected proof is again wrong in its reachability claim — but the safety reasoning ("sweeping is safe because the image is already destroyed") still holds for any state of that shape however produced. This is docstring-semantics territory; not a code defect. And honestly, can I construct {intent, no bin, dek}? Another writer: markDeleteIntent written, then destroy... no, destroy writes confirmed. What about: delete flow on v3: markDeleteIntent; server confirm; markServerDeleteConfirmed; crash before destroy. State: {intent, confirmed, v3 image}. Boot: DELETE_INCOMPLETE → auto-destroy → destroy() unlinks bin, dek, crash mid-unlink after bin unlink: {intent, confirmed, dek} → gate 2 refuses ✓ (row 7). Then DeleteIncomplete retries destroy → completes → markers retired. Fine.

  What about onRetryDestroy path... covered.

  OK so row 6c reachability hinges on legacy+intent coexisting. There's one more possibility: a v3 vault deleted-account flow, intent standing; user then... no way to get a v2 image. OR: downgrade attack? Install 0.9.1 over 0.9.2? Android doesn't downgrade. I'll note it as an INFO observation that row 6c's concrete writer may itself be hard to reach, but the gate action is safe regardless, and the corrected docstring's core point (safety rests on image-destruction, not unreachability) is the right framing. Actually — hold on. There IS a reachable path to {intent, no bin, residue}: the delete flow on a v3 vault where destroy() was never reached: markDeleteIntent (durable), server delete confirmed... no. What about onNotConfirmed → keeps session and intent; user then deletes account AGAIN? Same flow.

  What about {intent, no bin, dek} via retireLegacyImage where the image is v3?? retireLegacyImage refuses non-legacy (check at 933). So no.

  Alternatively: isLegacyImage true while intent stands → user taps create → retireLegacyImage unlinks bin → CRASH → {intent, no bin, dek, bin.tmp?}. This needs legacy+intent. I've argued that's near-unreachable. Fine — INFO.

  3. `onboarding is reachable from exactly...` test — destructuring `val (c, i, h, p, l)` on a List<Boolean> — List destructuring supports up to component5. ✓.

  4. Item G — the BootReconcileOwnerTest suite covers the contract. One more check on runBootReconcile: `scope.launch` — if `claim()` succeeds but `scope.launch` itself throws (scope cancelled)? In production, container.scope is process-scoped (SupervisorJob?). Check container.scope definition. If the scope were cancelled, launch throws... actually launching on a cancelled scope doesn't throw; the coroutine just doesn't run — then publish never happens → waiters stranded forever. Is container.scope ever cancelled? Check ZitroneApp for scope definition. Process scope with SupervisorJob — lives for process lifetime. Fine.

  5. Item F — one more consumer to check: `onUnlockSuccess` (line ~895?) — routes to ChatList presumably, session-driven, fine. And Route.Locked screen's biometric gate uses vaultExists seeded from hasVault pre-boot — cosmetic only.

  6. Check ZitroneApp.kt: the session flow declaration and scope. Also verify `bootReconciled`/`residueSweepHold` are only written where expected (grep .value =). Earlier grep showed writers: ZitroneApp 282-283 (publish) and MainActivity 1121 (clear). ✓.

  7. Item 2 verify: production afterPublish = retryPlaintextCacheClearIfNoVault which internally has runCatching around clearCacheDir but `imageStore.primaryImageProvenAbsent()` could throw? withLock doesn't throw normally. Anyway contained now. The stale docstring in BootReconcileOwnerTest line 314: "Production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`" — FALSE after this delta; production passes it bare and the wrapper contains. Doc bug introduced by acb5904. LOW.

  8. Check scope definition + session flow in ZitroneApp quickly.

  9. Item C: confirm healers not referenced anywhere in repo (whole repo grep, including docs/l00prite ledger — ledger mentions are history, fine). Code grep: NO MATCHES in app/src. Also confirm no comment assumes they run — grep "InterruptedBurn\|orphaned burn" case-insensitive in app/src.

  10. Item D: verify main's routing for {bin absent, dek present} → onboarding (from the removed main Splash onFinished in the diff: `container.serverDeleteConfirmed() -> DeleteIncomplete; vaultExists -> Locked; else -> Onboarding` ✓). And create() overwrites stray dek (kdoc 469-470, and renameIntoPlace overwrites) ✓. W-A strictly better ✓.

  11. A0: "confirm no OTHER path still routes on legacy without the confirmed-marker precedence" — onUnlockPassphrase LegacyImage branch (main's code, unchanged): routes Onboarding without checking confirmed. Reachable only from lock screen; lock screen unreachable with confirmed marker (bootRoute precedence + collector). Acceptable — and it's pre-existing main behavior. Note as INFO.

  Also "restored row-7 test: confirm no OTHER gate is uncovered": gates 1 and 2 both have load-bearing mutation-verified tests (row 5, row 8 ELOOP). The imageBearingFilesProvenAbsent early-exit (already-clean check): covered by row 9 + idempotence. The post-mutation re-stat failure branch (`!imageBearingFilesProvenAbsent()` after deletes → SWEPT_NOT_DURABLE): is that covered? Tests: non-durable dirSync test covers the dirSync branch. The re-stat-fail branch (a file survives delete) — hard to simulate; not covered. Minor. The catch-all Throwable → SWEPT_NOT_DURABLE: not directly covered but simple.

  12. Verify `leftoverTmp` naming: `File(target.parentFile, "${target.name}$TMP_SUFFIX")` — TMP_SUFFIX=".tmp" presumably. And sweep deletes dekFile, dek.tmp, bin.tmp but NOT binFile (gate 1 proved absent). ✓.

  13. One thing in runBootReconcile: `afterPublish: () -> Unit = {}` and `ioDispatcher = Dispatchers.IO` still have defaults, while bootRoute's kdoc touts "no parameter carries a default". The unit's own philosophy (round-2 "enforce the dispatcher") internalized the dispatcher for deriveBootDecisionFromDisk but runBootReconcile still defaults ioDispatcher to Dispatchers.IO and production relies on the default. Also the kdoc says "production passes ... Dispatchers.IO" — production does NOT pass it; it uses the default. Minor doc slip, INFO. Not a defect: default is correct for production.

  14. Now Item 1 final attack: "Is clearing the hold on a completed destroy actually justified?" destroy() completes → files unlinked + verify + dirSync DURABLE + markers retired durably. The sweep's uncertainty was "residue unlinked without proven durability; journal replay could resurrect residue". After destroy's durable unlink of ALL image-bearing files (same directory, dirSync after unlink), the directory is durably clean — any prior non-durable unlink by the sweep is superseded because destroy re-unlinked (delete() on possibly-resurrected files? wait — if journal replay resurrected residue AFTER the sweep but BEFORE destroy, destroy's binFile.delete()/dekFile.delete()/deleteLeftoverTmp would unlink them again, then dirSync durable. But destroy only runs when a session existed... and hold implies no vault... unreachable anyway). The predicate uses fresh stats (vaultProvenAbsent via Files.notExists fail-closed; serverDeleteConfirmed via exists()). If destroy completed, both read correctly. Justified ✓. The only soft spot: `serverDeleteConfirmed()` uses File.exists() — an indeterminate marker stat reads "absent" and would clear the hold + allow ONBOARDING over a possibly-surviving confirmed marker. But that mirrors main's pre-existing delete-path semantics (main used the same exists()-based check), and the next boot heals via gate 2 → DELETE_INCOMPLETE. INFO at most.

  Also: does clearing the hold from the delete path affect any other consumer? residueSweepHold consumers: deriveBootDecisionFromDisk only (boot effects + collector). Post-destroy, clearing is correct for all of them since disk is durably clean. And nobody else writes it. ✓.

  Race check between callback's hold-clear and collector: both on Main; the callback's clear happens synchronously before its derive suspends. The collector reads the hold inside withContext(IO) — whenever that runs, it sees either pre-clear (true) or post-clear (false). If it reads true while the callback later sets Onboarding: collector computes LOCKED and writes route=Locked — but WHEN? Collector woke at session=null (t0, before destroy finished — actually the destroy happens after lockIf inside completeTerminalWipe; session=null published during finishUi before destroy). Collector's derive at t0+delta reads: confirmed marker present (written by destroy at start, retired only at end) → DELETE_INCOMPLETE regardless of hold. So the collector essentially always sees the confirmed marker during the window and routes DeleteIncomplete; the callback then makes the final decision after destroy completes. The callback is launched after destroy completes, on Main.immediate. Could the collector's IO-resume land AFTER the callback's write? Collector derive reads disk at IO-execution time — if delayed past destroy completion: confirmed=false, absent=true, hold possibly still true (callback may not have cleared yet — callback clears on Main before its own IO derive). Interleaving: collector resumes on Main with LOCKED (hold true) and writes route=Locked AFTER callback wrote Onboarding? For that: callback must fully complete (clear + derive + write Onboarding) before the collector's IO finishes. The callback was launched at t2 (after destroy). Collector started IO at t0. IO dispatcher processes both; collector's IO likely runs first (FIFO-ish, started earlier) but no guarantee. If collector's IO is somehow delayed (thread busy with legacy probe ~1MiB decrypt — note: imagePresent false post-destroy → probe skipped → fast), callback's IO could finish first → callback writes Onboarding at t3; collector resumes Main at t4 with a decision computed from post-destroy disk: hold was cleared by callback at Main-time before callback's IO... order on Main: callback launch (t2, Main.immediate) runs clear synchronously at first frame. Collector's resume is a Main dispatch queued later. So by the time collector reads hold (in IO), the clear has happened (clear is synchronous on Main before callback's withContext). Wait — the collector reads hold inside ITS withContext(IO) block, which runs on IO threads. The read `residueSweepHold.value` happens whenever the IO lambda executes. If collector's IO lambda runs before t2's Main clear → reads true → LOCKED. When does it write route? On Main after IO. If its IO ran early (pre-clear) but its Main resume happens after callback's write... possible: collector IO at t1 (pre-destroy-complete: confirmed marker still present → DELETE_INCOMPLETE → writes DeleteIncomplete — fine, that's the pre-callback state, and callback writes after? Not necessarily!

  Ugh, precise interleaving where collector writes LAST with a STALE-ISH decision:
  - t0: session=null; collector starts; IO reads disk: destroy not complete yet → confirmed=true → DELETE_INCOMPLETE.
  - t1: destroy completes on coordinator thread.
  - t2: callback launched Main.immediate: clears hold (confirmed now false, provenAbsent true), derives → ONBOARDING, writes route=Onboarding.
  - t3: collector resumes on Main, writes route=DeleteIncomplete (from its t0 read).

  Is t3 > t2 possible? Collector's IO completed at ~t0+ε (fast: marker present, no probe). Its Main resume is queued immediately — likely runs BEFORE destroy even completes (destroy does file I/O + fsyncs on the coordinator dispatcher, taking much longer than a Main dispatch). So collector writes DeleteIncomplete early; callback writes Onboarding last. The dangerous ordering requires the collector's Main resume to be delayed past the entire destroy + callback — Main.immediate callback vs queued collector resume: if Main is free, collector's resume executes within a frame of its IO completing. Destroy takes milliseconds at least (fsync). So practically impossible, and even if it happened: route=DeleteIncomplete after a successful delete → DeleteIncomplete screen offers retry; retry succeeds → Onboarding. Not a wedge. And this race existed identically on main (collector vs callback both reading stats at different times). Not a regression; pre-existing. INFO.

  15. Item I: name an untested failure shape. Candidates:
  - The Compose wiring (Splash gating on bootDone, rotation) is untested (acknowledged in SECURITY_MODEL).
  - The delete-callback unification (MainActivity) is not host-testable — destroySupersedesResidueHold is tested, but the callback's actual use (clear-then-derive ordering) is not. The scenario the round-3 MEDIUM fixed (collector vs callback disagreement with hold raised) has no test — only the pure predicate is pinned. That's the honest untested shape: the interplay itself.
  - The sweep's post-mutation re-stat failure branch (file survives delete → SWEPT_NOT_DURABLE) is not covered.
  - runBootReconcile: afterPublish throwing CancellationException-swallow — fine.

  16. Check `completeTerminalWipe` and `unlockController.lockIf` — do they publish session=null synchronously? lockIf is main's code. Skip.

  17. Confirm container.scope is a SupervisorJob process scope — quick grep.

  Also double-check `bootReconciled` first{it} consumer: LaunchedEffect(Unit) — runs every composition; if composition created after boot, bootReconciled already true → proceeds. ✓.

  One more A0 item: "each now passes the FULL input set" — all three consumers call deriveBootDecisionFromDisk() with no args (it assembles all five internally). ✓. And BootRouteTest's truth table + 32-combination sweep ✓.

  Quick greps to finish: container.scope definition, session declaration, any other `route =` writers I haven't accounted for.

• Let me finish with a few remaining checks: container scope/session definitions, all `route =` writers, and repo-wide healer references.136:    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
955:        val session = VaultSession(
651:        route = when (decided.route) {
676:                    if (route != Route.DeleteIncomplete) route = Route.DeleteIncomplete
678:                BootRoute.ONBOARDING -> if (route == Route.Locked) route = Route.Onboarding
704:                route = Route.Onboarding
769:                    route = Route.ChatList
786:                route = when (snap.route) {
803:                route = Route.Locked
821:        route = Route.ChatList
862:                            route = Route.Onboarding
988:                        route = Route.Locked
997:                        route = Route.Locked
1131:                    route = when (snap.route) {
1199:                route = Route.Locked
1236:        route = when (val current = route) {
1290:                    route = current,
1296:                    onNavigate = { route = it },
=== done ===


• No healer/burn references anywhere outside the l00prite ledger (which is history — and even that showed nothing outside .l00prite). Wait, the grep excluded l00prite/.l00prite — let me check whether the ledger mentions them (it's a ledger of review history, fine). Actually output was empty entirely. Good — but the grep for *.md also found nothing, including docs. Good.

  Check session flow declaration in ZitroneApp and the remaining route writers:
  - 704: onRetryDestroy (analyzed).
  - 769: collector session != null → ChatList ✓.
  - 803: onForcedLogout → Locked ✓ (main).
  - 821: onUnlockSuccess → ChatList presumably ✓.
  - 862: LegacyImage unlock backstop (main, INFO noted).
  - 988/997: create result routing ✓ (main).
  - 1199: ? Let me check line 1199 context — probably Locked veil reveal. It's `route = Route.Locked` — revealLockScreenKeepingLemonDropScan related (line 1185-1199). That's a user action from the veil; fine.
  - 1290/1296: navigation within session screens. Fine.

  Session flow: check where container.session is declared.375:    val session: StateFlow<SessionContainer?> = _session.asStateFlow()
955:        val session = VaultSession(
                // Reveal the passphrase lock screen while KEEPING the queued scan (D2b invariant:
                // "the scan stays queued; the first unlock drains it" via onSessionPublished /
                // onUnlocked) — do NOT dismiss it, which would drop the scanned /d/ drop.
                container.revealLockScreenKeepingLemonDropScan()
                route = Route.Locked
            }
        }
    }
All checks done. Let me finalize the todo list and write the report.

  Summary of findings:

  **Item 1 (the real risk):** VERIFIED CLEAN, with two INFO observations.
  - `destroy()` source (VaultImageStore.kt:1092-1151): writes `vault.delete-confirmed` durably FIRST (1108), unlinks all four image-bearing files, verifies gone, requires DURABLE dirSync (1137), retires markers only after that proof, throws on any failure. So a completed destroy is strictly stronger evidence than the sweep's unproven unlink — clearing the hold is justified, not convenient.
  - `destroySupersedesResidueHold` requires BOTH proven absence (fail-closed Files.notExists ×4) and marker retired. A part-way destroy (marker still present) does not clear.
  - Reachable post-destroy states map identically to the old explicit semantics (success → ONBOARDING; any failure → marker still present → DELETE_INCOMPLETE). Verified case by case.
  - The collector/callback race: collector now reads the same derivation; during the delete window the confirmed marker is present so any collector derive in that window yields DELETE_INCOMPLETE; the callback runs after destroy completes and writes last in every realistic interleaving. Even the pathological delayed-resume case lands on DeleteIncomplete (retryable), not a wedge — and is no worse than main.
  - Hold-clear affects only deriveBootDecisionFromDisk consumers; post-destroy disk is durably clean so all future derivations are correct with hold=false. No other writer.
  - Bonus observation: the disagreement scenario itself (hold raised + live session) is essentially unreachable — hold ⇒ bin proven absent ⇒ no vault ⇒ no session ⇒ no delete. The fix is correct defense-in-depth.
  - INFO: `serverDeleteConfirmed()` (VaultImageStore.kt:1159) uses `File.exists()`, so an indeterminate marker stat reads "not confirmed"; combined with a successful proven-absence read, the supersede could clear the hold and route ONBOARDING while a confirmed marker survives an I/O fault. Same semantic as main's old delete path (`!hasVault() && !serverDeleteConfirmed()`), and next boot self-heals (gate 2 → DELETE_INCOMPLETE). Not a regression.
  - INFO: the claim "{image survives, confirmed absent} cannot occur: destroy throws before the retire when absence is unproven" (MainActivity.kt:1129-1130) is slightly overstated — destroy's survival verify (1126) uses `exists()` (proven-present only), not `Files.notExists`; an indeterminate stat is treated as absent. The required dirSync is the real second barrier. Comment precision, not code.

  **Item 2:** VERIFIED. `runCatching { afterPublish() }` runs strictly after the `finally` that publishes, inside `withContext(ioDispatcher)`. Cannot affect the verdict; swallowing a throw there is the contract. Cancellation swallowed by runCatching happens after publication, so no stranding. Test `a throwing afterPublish cannot unpublish the verdict` drives a real waiter and would fail against the pre-fix code (uncaught error propagates in runTest).

  **Item 3:** VERIFIED. Row-6b docstring now matches the store table's row 6c. The underlying ordering claim is accurate: ZitroneApp.kt:465 retires before create() clears markers (VaultImageStore.kt:505-510). INFO: the docstring's concrete writer (legacy image + standing intent) is itself hard to reach — 0.9.1 was fresh-install-only so a legacy image should never carry a delete-intent — but the safety argument ("the image is already destroyed, so residue opens nothing") is correct for any producer of that shape, and the docstring's point (safety does not rest on unreachability) is the right frame.
  - LOW: the new afterPublish test's docstring (BootReconcileOwnerTest.kt:314) says "Production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`" — false after this very delta; production now passes it bare and the wrapper contains. This is exactly the unit's recurring "fix one site, miss its twin" shape, in a docstring. Doc-only.

  **A0:** All three consumers (Splash effect 646, post-boot re-derive 667, session collector 783) call the arg-less `deriveBootDecisionFromDisk()` which assembles all five inputs internally — no consumer can pass a partial set. The delete callback (1123) too. The removed legacy effect has no sibling left routing on legacy without the confirmed-marker precedence — the only other legacy route is `onUnlockPassphrase`'s LegacyImage backstop (856-862, unchanged from main), reachable only from the lock screen, which is unreachable with a confirmed marker (bootRoute precedence + collector). Row-7 gate restored AND gate 2 has a mutation-verified tristate test (row 8 ELOOP) — both destructive gates have consequence-based coverage. `onRetryDestroy` (692-708) still routes Onboarding on `!hasVault() && !serverDeleteConfirmed()` without consulting the hold — analyzed: reachable only via Route.DeleteIncomplete, which requires the confirmed marker, and gate 2 means the hold can never be raised when the confirmed marker is present at boot, and no delete flow can run in a held boot (no vault → no session). Safe. INFO.

  **A:** No burn symbols anywhere in the tree (grep over all *.kt/*.md). `onBurn` byte-identical to main. PASS.
  **B:** No coupling residue — no dangling caller, no writerless field. `residueSweepHold` has exactly two writers (publish; the destroy-supersede clear) and both are intentional. PASS.
  **C:** Healers absent, zero references, no comment assumes they run. Trigger states unreachable: create() writes DEK first (533-545), destroy() writes the confirmed marker durably before unlinking (1101-1108), onBurn is a no-op stub. PASS.
  **D:** On main, {bin absent, dek/tmp present} → Splash else-branch → Onboarding, create() overwrites the stray (kdoc 466-475 verified). W-A sweeps durably first or withholds onboarding. No state made worse; the bin.tmp case (complete outer image) is strictly better handled. PASS.
  **E:** Gates verified fail-closed both directions (rows 4/5/7/8 refuse; 1/1b/2/3/6b/6c sweep). Writer hunt beyond the table: biometric wrap lives in PREFS_SETTINGS not the vault dir (ZitroneApp.kt:482); reseal temps coexist with a present bin (gate 1); destroy unlinks always carry the confirmed marker (gate 2); open() deletes leftover temps when bin present. No missing writer found. The no-intent-gate reasoning verified against destroy() (confirmed-first) and create() (markers cleared before DEK write). PASS.
  **F:** Carried verdict: publish writes hold then done; every routing consumer either awaits bootReconciled or runs only after a session existed (which implies boot completed); all use deriveBootDecisionFromDisk, never a fresh re-derivation of cleanliness. vaultExists seed at 631 is pre-boot but only feeds cosmetic affordances before the decision overwrites it. PASS.
  **G:** Contract holds: CAS claim before launch; fail-closed default; publish in finally (non-suspending); afterPublish contained. Tests drive real waiters and name accurate mutations — including one header that corrects its own prior false claim (line 88-97), verified honest. Production scope is SupervisorJob+Default (ZitroneApp.kt:136) so the launch can't be silently dropped. PASS. INFO: runBootReconcile's kdoc says "production passes ... Dispatchers.IO" but production relies on the parameter default; harmless.
  **H:** 487 total / 0 failures / 484 passed / 3 skipped — I observed exactly these numbers from the JUnit XML in my own run. PASS.
  **I:** Tests assert on consequences (files survive/gone, waiters released), several mutation-verified, headers mostly honest (two self-corrections in-line). Untested failure shapes to name:
    - The collector-vs-callback unification itself: only the pure predicate is tested; the Compose-side interleaving (clear-then-derive ordering in the callback, last-writer-wins vs the collector) has no host test — acknowledged limitation (no Compose test infra), and I verified it by interleaving analysis instead.
    - The sweep's post-mutation re-stat-failure branch (a file surviving `delete()` → SWEPT_NOT_DURABLE) is not directly covered (the dirSync-failure branch is).
    - No test exercises `destroySupersedesResidueHold`'s CALLER (that the callback clears before deriving) — the predicate is pinned, the wiring is not.

  Verdict: READY TO MERGE. Findings: 1 LOW (stale production-shape claim in the new test's docstring), a handful of INFO.

  Write the report.

• All verification is complete. Let me finalize and write the review.

• # ROUND 4 INDEPENDENT REVIEW — Unit W-A fix delta (acb5904)

  **VERDICT: READY TO MERGE.** One LOW (a stale docstring introduced by this very delta), a few INFOs. I re-derived every claim against source and ran the suite myself.

  ---

  ## ITEM 1 — post-destroy routing unification (the real risk): HOLDS

  **Is clearing the hold justified, or convenient? Justified.** `destroy()` (VaultImageStore.kt:1092-1151) writes `vault.delete-confirmed` durably *first* (line 1108), unlinks all four image-bearing files, re-stats them gone, requires `dirSync == DURABLE` (1137), and only then retires both markers via `clearBothMarkersDurably()` (1147), throwing on any failure. A completed destroy is therefore strictly stronger evidence than the sweep's unproven unlink — and `destroySupersedesResidueHold` (ZitroneApp.kt:1233) requires BOTH fail-closed proven absence (`Files.notExists` ×4) AND a retired confirmed marker, so a destroy that threw part-way (marker still present) does not clear. The commit's claim checks out.

  **Does the mapping match the old semantics in every reachable post-destroy state? Yes** — I enumerated: success → marker retired, files proven gone → ONBOARDING (old code: same). Any failure → marker still present (retire is last) → `bootRoute` arm 1 → DELETE_INCOMPLETE (old code: same, via either branch). The unreachable-in-practice residue case also matches because destroy throws before the retire.

  **Does the collector still race the callback? No decision-level race remains.** Both now call the same `deriveBootDecisionFromDisk()`. Timeline analysis: the collector wakes at `session=null` while destroy is still running, so any derive in that window sees the confirmed marker (written before unlinks) → DELETE_INCOMPLETE; the callback runs after destroy completes and writes last in every realistic interleaving. The pathological case (collector's Main resume delayed past the entire destroy + callback) would leave DeleteIncomplete, whose only exit is a successful idempotent retry — a retryable screen, not a wedge, and no worse than main.

  **Does clearing the process-scoped hold affect other consumers?** `residueSweepHold` has exactly two writers (the boot `publish`, ZitroneApp.kt:282; the supersede clear, MainActivity.kt:1121) and one reader (`deriveBootDecisionFromDisk`). Post-destroy the disk is durably clean, so all future derivations are correct with `hold=false`. No other consumer exists.

  One structural observation that defuses the whole scenario: a raised hold requires gate 1 to have proven `vault.bin` absent at boot → no vault → no unlock → no session → `onDeleteAccount` can never run in a held boot. The fix is correct defense-in-depth for a compound state that is currently unreachable; I found no hole in it regardless.

  - **INFO** — VaultImageStore.kt:1159: `serverDeleteConfirmed()` uses `File.exists()`, so an *indeterminate* marker stat reads "not confirmed" and could let the supersede clear the hold over a marker that survived an I/O fault. Identical semantics to main's old delete path, and next boot self-heals (gate 2 refuses the sweep → DELETE_INCOMPLETE → idempotent retry). Not a regression.
  - **INFO** — MainActivity.kt:1129-1130: the comment "{image survives, confirmed absent} cannot occur: destroy throws before the retire when absence is unproven" slightly overstates — destroy's survival verify (VaultImageStore.kt:1126) is `exists()`-based (proven-*present* only), so an indeterminate stat is treated as absent; the required `dirSync` is the real second barrier. Code fine; comment imprecise.

  ## ITEM 2 — afterPublish containment: HOLDS

  `withContext(ioDispatcher) { runCatching { afterPublish() } }` (ZitroneApp.kt:1172) runs strictly after the `finally` that publishes, so it cannot affect the verdict; the swallowed-cancellation case also occurs only after publication, so no stranding. The new test (`a throwing afterPublish cannot unpublish the verdict`) drives a real waiter and genuinely fails against the pre-fix code (the `error()` propagates out of the launch in `runTest`).

  - **LOW** — BootReconcileOwnerTest.kt:314: the new test's docstring says *"Production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`, so it cannot throw today"* — **false after this delta**: acb5904 removed that local `runCatching` (ZitroneApp.kt:287-289 now passes it bare, by design, because the wrapper contains). The test's premise sentence describes the pre-fix shape. Doc-only, but it is this unit's signature "fix one site, miss its twin" shape recurring inside the delta's own test. Fix: reword to "production passes the bare call; the wrapper contains faults by contract."

  ## ITEM 3 — row-6b docstring: HOLDS

  The corrected text now matches the store table's row 6c, and the underlying ordering claim is accurate: `createVaultAndPublish` retires the legacy image (ZitroneApp.kt:465) *before* `create()` clears the markers (VaultImageStore.kt:505-510). INFO: the docstring's concrete writer (a legacy image carrying a standing delete-intent) is itself hard to reach — 0.9.1 was fresh-install-only, so a v2 image should never bear an intent — but the safety argument ("retirement already destroyed the only openable image") is producer-independent and correct, which is exactly the docstring's point.

  ## Binding items

  - **A — PASS.** `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion`: zero matches tree-wide (`*.kt`, `*.md`). `onBurn` (MainActivity.kt:837-840) is byte-identical to `git show main:` — uniform-failure stub, destroys nothing.
  - **B — PASS.** No coupling residue: no dangling caller, no half-removed state, no writerless field.
  - **C — PASS.** `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers`: zero references anywhere, no comment assumes they run. Unreachability independently confirmed: `create()` writes the DEK durably before the image (533-545), `destroy()` writes the confirmed marker durably before any unlink (1108), and the burn trigger is the inert stub.
  - **D — PASS.** On main, `{bin absent, dek/tmp present}` → Splash else-branch → Onboarding, and `create()` overwrites the stray (verified kdoc 466-475 + `renameIntoPlace`). W-A sweeps durably first or withholds onboarding; nothing routes somewhere worse, and the `bin.tmp`-holds-a-complete-image case is handled strictly better.
  - **E — PASS.** Both directions proven by consequence-asserting tests (rows 4/5/7/8 refuse — including the mutation-verified ELOOP tristate tests; 1/1b/2/3/6b sweep). Missing-writer hunt: biometric wrap lives in PREFS_SETTINGS, not the vault dir (ZitroneApp.kt:482); reseal temps coexist with a present bin (gate 1); destroy's unlinks always carry the confirmed marker (gate 2); `open()` deletes leftover temps when bin is present. No missing row found beyond 6c. The no-intent-gate reasoning verified against `destroy()` (confirmed-first) and `create()` (markers cleared before the DEK write).
  - **F — PASS.** Consumers enumerated: Splash decision (646, awaits `bootDone`, re-checks after suspend), post-boot re-derive (667, awaits `bootReconciled.first{it}`, session re-check), session collector (783), delete callback (1123). All four call the arg-less `deriveBootDecisionFromDisk()` — a partial input set is no longer expressible. `onRetryDestroy` (699) routes on raw stats but is reachable only via Route.DeleteIncomplete, which requires the confirmed marker — and gate 2 makes `{hold raised, confirmed marker}` impossible at boot, while a held boot admits no session. Safe. The `vaultExists` seed at 631 is pre-boot but feeds only cosmetic affordances before the decision overwrites it.
  - **G — PASS.** Claim-before-launch CAS, fail-closed default, non-suspending `publish` in `finally`, contained `afterPublish`; production scope is `SupervisorJob + Default` (ZitroneApp.kt:136) so the launch can't be silently dropped. The tests drive real waiters and their mutation claims are honest — including one header (lines 88-97) that corrects its own prior false claim. INFO: `runBootReconcile`'s kdoc says "production passes … `Dispatchers.IO`" but production relies on the parameter default.
  - **H — PASS.** My own run: `cd apps/android && ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest` → BUILD SUCCESSFUL; parsed JUnit XML: **487 total, 0 failures/errors, 484 passed, 3 skipped** — exactly the claimed figures.
  - **I — PASS with named gaps.** Tests assert on damage (files survive/gone, waiters released), several are mutation-verified, and the two self-correcting headers are now accurate. Untested failure shapes: (1) the collector↔callback unification itself — only the pure predicate is pinned; the callback's clear-then-derive ordering and last-writer-wins interplay have no host test (I covered it by interleaving analysis above; the lack of Compose infra is honestly disclosed in SECURITY_MODEL.md); (2) the sweep's post-mutation re-stat-failure branch (file survives `delete()` → SWEPT_NOT_DURABLE) is uncovered — the dirSync-failure branch is; (3) the *wiring* of `destroySupersedesResidueHold` into the callback (clear before derive) is untested — the predicate is, its call site isn't.

  ## Bottom line

  The sole behavioural change (`destroySupersedesResidueHold` + unified derivation) is justified by destroy's own durability proof, matches the prior semantics in every reachable state, closes the collector/callback disagreement, and is mutation-tested. The extraction left no burn residue, no dangling healer, and no stale routing authority. **READY TO MERGE**, with the one LOW docstring fix (BootReconcileOwnerTest.kt:314) recommended but not blocking.

To resume this session: kimi -r session_bc95615a-1680-4c3d-9863-6cf99b6b4a06
