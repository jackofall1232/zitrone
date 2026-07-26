• This is a substantial security review task. Let me start by looking at the diff and key files. Given the scale, I could delegate exploration, but the review requires me to actually verify claims against source — subagent results are only visible to me, which is fine. However, the critical judgment must be mine. Let me do the main reading myself but potentially delegate the test run.

  Plan:
  1. `git -C /root/zitrone log --oneline` and `git show 800d7ab` and `git diff main...HEAD --stat`
  2. Read key files: ZitroneApp.kt, MainActivity.kt, VaultImageStore.kt, tests
  3. Run test suite in background (it may take a while) while I read code.

  Let me start.

• I'll start by kicking off the test suite in the background while I read the diff and source.587282e l00prite: record the sibling-call-site class and review-artifact hygiene
800d7ab Unit W round-5 fixes — apply-once by generation; one applier; no defaulted safety params
e1b0f2e l00prite: focus item A closed by enumeration; kimi installed; round-5 position
5ef5f77 Unit W: a stale burn completion must apply nothing (round-5 lens 1, Kimi)
91e7c4d Unit W sweep round-4 fixes — full input set at every consumer; test claims corrected
ba25b11 l00prite: round-3 adjudication + round-4 four-lens dispatch
0333100 l00prite: record the Robolectric correction; DoD item 3 now met, gap narrowed
00f65b6 Unit W sweep round-3 fixes — one routing authority; boot contract now tested
de4baf5 l00prite: ratify the sweep-gate deviation; separate it from decision_defect
9064c2e l00prite: record the binding-spec deviation + the stated-requirement failure mode
fdf7c0d l00prite: sweep round 2 — reviewers converged; the pattern's lifecycle form
5e02b2e Unit W sweep round-2 fixes — own boot on the process scope; Splash must wait
b57e341 l00prite: binding Definition of Done for the residue-sweep delta
c6f2082 l00prite: sweep round 1 — name the recurring discard-and-re-derive pattern
98c0319 Unit W sweep round-1 fixes — carry the durability verdict; stop stranding residue
2212ada l00prite: record the authorized cap reset + three sweep-delta lessons
c144216 Unit W: cold-start orphan residue sweep — close the partial-burn fresh-install gap
00fb5dc l00prite: Unit W round 5 — record the cold-start HIGH and STOP for design decision
eadd7aa Unit W: correct the residual disclosure — cold start, not "honest but stuck"
50b5277 l00prite: record Unit W round-4 adjudication + four lessons
40231c4 Unit W round-4 fixes — publish the burn RESULT, not just completion
923fd37 l00prite: record Unit W round-3 adjudication + reviewer calibration
b94d5a8 Unit W round-3 fixes — process-scoped burn completion + tristate cache-retry gate
0dce2e6 Unit W round-2 fixes — exclusive burn gate + close remaining cache fail-opens
813245b Unit W — harden the new no-credential completion path (self-audit)
764845e Unit W round-1 review fixes — close three fail-open paths
645b8a8 0.9.2-beta Pucker Burn Unit W — the duress wipe mechanism (slot 0 stays unarmed)
---
 apps/android/app/build.gradle.kts                  |   7 +
 .../src/main/java/com/zitrone/app/MainActivity.kt  | 374 +++++++++++--
 .../main/java/com/zitrone/app/UnlockController.kt  |  22 +
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 507 ++++++++++++++++-
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 464 ++++++++++++++--
 .../com/zitrone/app/data/SettingsRepository.kt     |  27 +-
 .../app/notifications/MessagingNotifications.kt    |  21 +
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 298 ++++++++++
 .../src/test/java/com/zitrone/app/BootRouteTest.kt | 242 ++++++++
 .../java/com/zitrone/app/BurnAppLocalStateTest.kt  | 243 +++++++++
 .../test/java/com/zitrone/app/BurnApplyOnceTest.kt | 124 +++++
 .../java/com/zitrone/app/BurnObliterateTest.kt     | 607 +++++++++++++++++++++
 .../test/java/com/zitrone/app/PostBurnRouteTest.kt | 180 ++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 369 +++++++++++++
 .../java/com/zitrone/app/UnlockControllerTest.kt   |  72 +++
 apps/android/gradle/libs.versions.toml             |   2 +
 docs/SECURITY_MODEL.md                             | 122 ++++-
 l00prite/.l00prite/failures.md                     | 237 ++++++++
 l00prite/.l00prite/ledger.md                       | 461 ++++++++++++++++
 l00prite/.l00prite/state.json                      |   6 +-
 l00prite/.l00prite/todos.md                        | 167 +++++-
 21 files changed, 4453 insertions(+), 99 deletions(-)


commit 800d7abc99140f157fe194e058ebf2937923e468
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 05:29:49 2026 +0000

    Unit W round-5 fixes — apply-once by generation; one applier; no defaulted safety params
    
    FOUR source-capable lenses (Codex, Grok, Gemini, Kimi). Every real finding is
    mine, and the shape is the worst yet: the round-5 fix for Kimi's LOW
    REINTRODUCED the same tell it was fixing, at the sibling call site.
    
    UNANIMOUS (Grok F1, Gemini F1, Kimi F1) — the stale predicate was incomplete.
    `burnReportedSuccess && vaultImagePresent` covers only SUCCESSES. A FAILED burn
    leaves an image present BY DEFINITION, so it replayed unconditionally: user
    unlocks, locks, rotates, and the observer repaints UNIFORM_FAILURE on a lock
    screen they never failed at. Identical tell, other branch. Grok named the remedy:
    generation-scoped apply-once.
    
    `obliterated` was never a staleness test. "Has this completion already been
    applied?" is — and that is exactly what `generation` was added for in round 4 and
    never used. Fix: `AppContainer.tryApplyBurnCompletion(generation)`, a CAS loop on
    a PROCESS-scoped counter (composition-scoped would reset on the very rotation
    that triggers the replay). Claimed by the APPLIER immediately before a live
    composition writes — never by the burn worker, which could consume a completion
    and then write to a disposed tree: the round-3 defect reintroduced through the
    guard meant to prevent its replay.
    
    HIGH (Codex) — `IGNORE_STALE` was swallowed at the sibling call site. The
    dispatcher consumed PostBurnRoute with `if / else if / else`, so a newly added
    enum value fell into `else` and hit the LOCKED arm. Root cause was TWO consumers
    of one verdict, each applying by its own rules; patching the chain would have left
    that intact. THE DISPATCHER NO LONGER WRITES UI AT ALL. There is now exactly one
    applier — the process-scoped observer, which round 3 established is the one
    guaranteed to run on a live composition. Publishing the completion is the
    hand-off. `IGNORE_STALE` is deleted; staleness left the routing function entirely.
    
    That also dissolves a genuine reviewer disagreement rather than adjudicating it:
    Codex said staleness-before-confirmed was wrong precedence; Kimi said it was sound
    because other writers own the confirmed state. Both were right on their own terms.
    With staleness moved out of `postBurnRoute`, there is no precedence question left.
    
    HIGH (Gemini F2, Kimi INFO) — the session collector passed `legacyImage` to
    bootRoute but still assigned `vaultExists = hasVault()` RAW, so a legacy image
    routed to Onboarding while vaultExists stayed true and the lock veil could compose
    over it. Round 4 fixed exactly this at the boot re-derive and I left it here:
    half-applying a fix is its own failure mode.
    
    INFO→STRUCTURAL (Grok F5), the highest-leverage finding of the round: default
    parameter values on safety-decision functions silently re-enable the
    incomplete-input-set defect at the LANGUAGE level. `legacyImage: Boolean = false`
    and `vaultImagePresent: Boolean = false` are both gone. Omitting an input is now a
    COMPILE ERROR — the invariant is compiler-enforced instead of discipline-enforced,
    and discipline has now failed at it twice.
    
    INFO (Gemini F4) — the WRITER/READER table's row 1 listed only interrupted-create
    and partial-burn as writers of `{dek, no bin, no markers}`. `retireLegacyImage`
    unlinks binFile THEN dekFile, so a crash between them is a THIRD writer of that
    exact state. Behaviour was already correct; the TABLE the ratification rests on
    was incomplete — self-consistent and wrong, which is what "prove it COMPLETE" is
    for. Row 1b added.
    
    DOWNGRADED — Gemini's MEDIUM that `afterPublish` cancellation strands
    completeInterruptedBurn/reconcileOrphanedBurnMarkers: those are in `rest`, inside
    the try. `afterPublish` is only the cache 
retry, which retries next boot.
    Moonshot independently called it non-defect and pre-existing. No action.
    
    Superseded IGNORE_STALE tests removed rather than left asserting a deleted arm.
    
    Tests: 529 total (+5 net), 0 failures, 526 passed, 3 skipped (I2P). New
    BurnApplyOnceTest asserts on the DAMAGE — how many appliers can act on one
    completion — including the failed-burn direction the last fix got wrong, and a
    16-thread race. Mutation-verified: a non-atomic read-then-write guard fails the
    race test and only that test. Fifteen mutations across this delta, all caught.
    
    CLOSE-OUT CHECK CAUGHT TWO FIXES THAT DID NOT LAND: an edit script aborted on a
    bad anchor and wrote nothing, leaving tryApplyBurnCompletion defined but never
    called and the session collector untouched. Both re-applied and re-verified. This
    is the third time that check has paid for itself.
    
    No version bump. Slot 0 stays unarmed.

 .../src/main/java/com/zitrone/app/MainActivity.kt  |  98 ++++++----------
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    |  61 +++++-----
 .../zitrone/app/crypto/vault/VaultImageStore.kt    |  20 ++++
 .../src/test/java/com/zitrone/app/BootRouteTest.kt |   8 +-
 .../test/java/com/zitrone/app/BurnApplyOnceTest.kt | 124 +++++++++++++++++++++
 .../test/java/com/zitrone/app/PostBurnRouteTest.kt |  68 -----------
 6 files changed, 220 insertions(+), 159 deletions(-)
===
commit 800d7abc99140f157fe194e058ebf2937923e468
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 05:29:49 2026 +0000

    Unit W round-5 fixes — apply-once by generation; one applier; no defaulted safety params
    
    FOUR source-capable lenses (Codex, Grok, Gemini, Kimi). Every real finding is
    mine, and the shape is the worst yet: the round-5 fix for Kimi's LOW
    REINTRODUCED the same tell it was fixing, at the sibling call site.
    
    UNANIMOUS (Grok F1, Gemini F1, Kimi F1) — the stale predicate was incomplete.
    `burnReportedSuccess && vaultImagePresent` covers only SUCCESSES. A FAILED burn
    leaves an image present BY DEFINITION, so it replayed unconditionally: user
    unlocks, locks, rotates, and the observer repaints UNIFORM_FAILURE on a lock
    screen they never failed at. Identical tell, other branch. Grok named the remedy:
    generation-scoped apply-once.
    
    `obliterated` was never a staleness test. "Has this completion already been
    applied?" is — and that is exactly what `generation` was added for in round 4 and
    never used. Fix: `AppContainer.tryApplyBurnCompletion(generation)`, a CAS loop on
    a PROCESS-scoped counter (composition-scoped would reset on the very rotation
    that triggers the replay). Claimed by the APPLIER immediately before a live
    composition writes — never by the burn worker, which could consume a completion
    and then write to a disposed tree: the round-3 defect reintroduced through the
    guard meant to prevent its replay.
    
    HIGH (Codex) — `IGNORE_STALE` was swallowed at the sibling call site. The
    dispatcher consumed PostBurnRoute with `if / else if / else`, so a newly added
    enum value fell into `else` and hit the LOCKED arm. Root cause was TWO consumers
    of one verdict, each applying by its own rules; patching the chain would have left
    that intact. THE DISPATCHER NO LONGER WRITES UI AT ALL. There is now exactly one
    applier — the process-scoped observer, which round 3 established is the one
    guaranteed to run on a live composition. Publishing the completion is the
    hand-off. `IGNORE_STALE` is deleted; staleness left the routing function entirely.
    
    That also dissolves a genuine reviewer disagreement rather than adjudicating it:
    Codex said staleness-before-confirmed was wrong precedence; Kimi said it was sound
    because other writers own the confirmed state. Both were right on their own terms.
    With staleness moved out of `postBurnRoute`, there is no precedence question left.
    
    HIGH (Gemini F2, Kimi INFO) — the session collector passed `legacyImage` to
    bootRoute but still assigned `vaultExists = hasVault()` RAW, so a legacy image
    routed to Onboarding while vaultExists stayed true and the lock veil could compose
    over it. Round 4 fixed exactly this at the boot re-derive and I left it here:
    half-applying a fix is its own failure mode.
    
    INFO→STRUCTURAL (Grok F5), the highest-leverage finding of the round: default
    parameter values on safety-decision functions silently re-enable the
    incomplete-input-set defect at the LANGUAGE level. `legacyImage: Boolean = false`
    and `vaultImagePresent: Boolean = false` are both gone. Omitting an input is now a
    COMPILE ERROR — the invariant is compiler-enforced instead of discipline-enforced,
    and discipline has now failed at it twice.
    
    INFO (Gemini F4) — the WRITER/READER table's row 1 listed only interrupted-create
    and partial-burn as writers of `{dek, no bin, no markers}`. `retireLegacyImage`
    unlinks binFile THEN dekFile, so a crash between them is a THIRD writer of that
    exact state. Behaviour was already correct; the TABLE the ratification rests on
    was incomplete — self-consistent and wrong, which is what "prove it COMPLETE" is
    for. Row 1b added.
    
    DOWNGRADED — Gemini's MEDIUM that `afterPublish` cancellation strands
    completeInterruptedBurn/reconcileOrphanedBurnMarkers: those are in `rest`, inside
    the try. `afterPublish` is only the cache 
retry, which retries next boot.
    Moonshot independently called it non-defect and pre-existing. No action.
    
    Superseded IGNORE_STALE tests removed rather than left asserting a deleted arm.
    
    Tests: 529 total (+5 net), 0 failures, 526 passed, 3 skipped (I2P). New
    BurnApplyOnceTest asserts on the DAMAGE — how many appliers can act on one
    completion — including the failed-burn direction the last fix got wrong, and a
    16-thread race. Mutation-verified: a non-atomic read-then-write guard fails the
    race test and only that test. Fifteen mutations across this delta, all caught.
    
    CLOSE-OUT CHECK CAUGHT TWO FIXES THAT DID NOT LAND: an edit script aborted on a
    bad anchor and wrote nothing, leaving tryApplyBurnCompletion defined but never
    called and the session collector untouched. Both re-applied and re-verified. This
    is the third time that check has paid for itself.
    
    No version bump. Slot 0 stays unarmed.

diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index a5b0af2..15f856b 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -832,26 +832,29 @@ private fun ZitroneRoot(
         // a fresh composition that has never seen one).
         val completion = burnCompletion ?: return@LaunchedEffect
         if (container.session.value != null) return@LaunchedEffect
+        // APPLY-ONCE (round-5 review — Grok, Gemini and Kimi, independently). The completion is
+        // process-lifetime and never cleared, so this effect re-fires on EVERY later composition;
+        // without this guard a rotation replays the outcome onto an unrelated screen. Round 5's first
+        // attempt keyed staleness on `success && imagePresent`, which covered only SUCCESSES — a
+        // FAILED burn replayed identically, repainting UNIFORM_FAILURE on a lock screen the user
+        // never failed at. `obliterated` was never a staleness test; "has this already been applied?"
+        // is — and that is exactly what `generation` was added for in round 4 and never used.
+        //
+        // Claimed HERE, by the applier, immediately before a LIVE composition writes — never by the
+        // burn worker, which could consume the completion and then write to a disposed tree: the
+        // round-3 defect reintroduced through the guard meant to prevent its replay.
+        if (!container.tryApplyBurnCompletion(completion.generation)) return@LaunchedEffect
         // Both disk reads off-main and together, so the decision is taken over ONE observation.
-        val snap = withContext(Dispatchers.IO) {
-            Triple(
-                container.serverDeleteConfirmed(),
-                container.burnObliterationComplete(),
-                container.hasVault(),
-            )
+        val (confirmed, provenAbsent) = withContext(Dispatchers.IO) {
+            container.serverDeleteConfirmed() to container.burnObliterationComplete()
         }
-        val (confirmed, provenAbsent, imagePresent) = snap
         when (
             postBurnRoute(
                 serverDeleteConfirmed = confirmed,
                 burnReportedSuccess = completion.obliterated,
                 imageBearingProvenAbsent = provenAbsent,
-                vaultImagePresent = imagePresent,
             )
         ) {
-            // Round-5 review, Kimi: a successful completion re-fired on a later composition over a
-            // SUCCESSOR vault. Apply nothing rather than repaint a healthy lock screen.
-            PostBurnRoute.IGNORE_STALE -> Unit
             // D2c owns this state — finish the delete, never onboarding (round-4 review, BOTH).
             PostBurnRoute.DELETE_INCOMPLETE -> {
                 unlocked = false
@@ -918,7 +921,7 @@ private fun ZitroneRoot(
             } else if (unlocked) {
                 unlocked = false
                 identityFingerprint = null
-                vaultExists = container.hasVault()
+                val imagePresent = container.hasVault()
                 // THE SAME decision function and THE SAME carried inputs as Splash and the boot
                 // re-derive (sweep-delta round 2, Grok). Round 1 gave this arm proven-absence but
                 // NOT the durability hold — a third consumer still deriving cleanliness its own way,
@@ -933,15 +936,21 @@ private fun ZitroneRoot(
                 // review, Gemini). Practically unreachable — a legacy image cannot produce a live
                 // session to log out OF — but "every consumer passes the full input set" is either
                 // an invariant or it is a habit, and an omitted argument is how the last one hid.
-                val legacyNow = if (vaultExists && !container.serverDeleteConfirmed()) {
+                val legacyNow = if (imagePresent && !container.serverDeleteConfirmed()) {
                     runCatching { container.isLegacyImage() }.getOrDefault(false)
                 } else {
                     false
                 }
+                // AND the same `&& !legacy` correction the other two consumers apply (round-5
+                // review, Gemini + Kimi). Round 4 fixed this at the boot re-derive and LEFT IT HERE:
+                // the argument was passed to bootRoute but `vaultExists` was still assigned RAW, so a
+                // legacy image routed to Onboarding while vaultExists stayed true and the lock veil
+                // could compose over the onboarding screen. Half-applying a fix is its own failure.
+                vaultExists = imagePresent && !legacyNow
                 route = when (
                     bootRoute(
                         serverDeleteConfirmed = container.serverDeleteConfirmed(),
-                        vaultImagePresent = vaultExists,
+                        vaultImagePresent = imagePresent,
                         residueSweepHold = container.residueSweepHold.value,
                         vaultProvenAbsent = container.vaultProvenAbsent(),
                         legacyImage = legacyNow,
@@ -1074,56 +1083,17 @@ private fun ZitroneRoot(
                 // presented as a completed wipe. Never re-derive this.
                 container.signalBurnCompleted(obliterated = burned)
             }
-            // SINGLE ROUTING AUTHORITY (round-5 review, Grok). This arm used to route to Onboarding
-            // from `burned` alone while the process-scoped observer used the full precedence — two
-            // writers deciding the same thing by different rules. They agree today (a successful burn
-            // clears both markers inside obliterateLocked), so this is drift-proofing, not a live bug:
-            // one of the two could be edited later and the disagreement would be silent. Both now go
-            // through postBurnRoute with the same three inputs.
-            val decided = withContext(Dispatchers.IO) {
-                postBurnRoute(
-                    serverDeleteConfirmed = container.serverDeleteConfirmed(),
-                    burnReportedSuccess = burned,
-                    imageBearingProvenAbsent = container.vaultProvenAbsent(),
-                    // FULL input set at BOTH call sites — the round-4 lesson. Unreachable here (this
-                    // runs the instant the burn ends, before any successor can exist), passed anyway
-                    // so the two callers cannot drift.
-                    vaultImagePresent = container.hasVault(),
-                )
-            }
-            withContext(Dispatchers.Main.immediate) {
-                if (decided == PostBurnRoute.DELETE_INCOMPLETE) {
-                    // A confirmed server delete outbids the burn's own presentation — D2c owns it.
-                    unlocking = false
-                    route = Route.DeleteIncomplete
-                } else if (decided == PostBurnRoute.ONBOARDING) {
-                    // Fresh-install presentation (P2, visible reset): the ORDINARY onboarding route —
-                    // no "wiped" screen, no toast, no erro
r. Identical to what a first launch shows.
-                    vaultExists = false
-                    lockError = null
-                    unlocking = false
-                    route = Route.Onboarding
-                } else {
-                    // FAIL-CLOSED: a burn that did not take must never present as one that did. Surface
-                    // the SAME uniform failure a wrong passphrase gives — deniable (indistinguishable
-                    // from a mistyped password) and retryable.
-                    //
-                    // HONEST SCOPE (round-1 review, Grok): this does NOT mean the device is untouched.
-                    // The app-local cleanups run BEFORE the image destruction, so a failed burn can
-                    // leave the biometric wrap, device settings and notification channel already
-                    // cleared while the image survives. Passphrase unlock still works; biometric
-                    // unlock will not, and settings are back at defaults. That ordering is deliberate —
-                    // the cleanups must never pre-empt or mask the image destruction's signal — and a
-                    // retry re-runs every step idempotently.
-                    lockError = VaultUnlockRouter.UNIFORM_FAILURE
-                    unlocking = false
-                    // Hold the lock screen over residue: with vault.bin gone but a temp or the dek
-                    // surviving, hasVault() would say "no vault" and a later derivation could route
-                    // this tree to onboarding over a recoverable image.
-                    vaultExists = true
-                    route = Route.Locked
-                }
-            }
+            // NO UI WRITE HERE (round-5 review — Codex, Grok). This arm used to apply the outcome
+            // itself, via an `if / else if / else` chain over PostBurnRoute. When round 5 added a new
+            // enum value, that `else` silently swallowed it into the LOCKED arm and repainted
+            // UNIFORM_FAILURE — the exact prior-use tell the new value existed to prevent,
+            // reintroduced at the sibling call site BY the fix for it.
+            //
+            // The root cause was TWO consumers of one verdict, each applying it by its own rules;
+            // patching the chain would have left that intact. There is now exactly ONE applier: the
+            // process-scoped observer above, which is guaranteed to run on a LIVE composition (that
+            // is why it exists — round 3) and is apply-once by generation. Publishing the completion
+            // IS the hand-off; this worker's job ends there.
         }
         Unit
     }
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index e754645..1ed8a5c 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -823,6 +823,40 @@ class AppContainer(private val app: Application) {
     val bootReconciled = MutableStateFlow(false)
     val residueSweepHold = MutableStateFlow(false)
 
+    /**
+     * APPLY-ONCE for burn completions (round-5 review — Grok, Gemini and Kimi independently).
+     *
+     * [BurnCompletion] is process-lifetime and never cleared, so `LaunchedEffect(burnCompletion)`
+     * re-fires on every later composition. Round 5's first fix keyed staleness on
+     * `burnReportedSuccess && vaultImagePresent`, which covers only SUCCESSES: a FAILED burn
+     * (`obliterated == false`, image present by definition) replayed unconditionally, repainting
+     * UNIFORM_FAILURE on a lock screen the user never failed at — the identical prior-use tell, on
+     * the other branch. `obliterated` was never a staleness test.
+     *
+     * The real question is "has this completion already been applied?", which is exactly what
+     * [BurnCompletion.generation] was added for in round 4 and never used. PROCESS-scoped, because a
+     * composition-scoped marker would reset on the very rotation that triggers the replay.
+     */
+    private val lastAppliedBurnGeneration = java.util.concurrent.atomic.AtomicInteger(0)
+
+    /**
+     * Claim the right to apply [generation]'s UI outcome. True exactly once per completion, for the
+     * FIRST live composition that gets there; every later composition — including one created by the
+     * rotation that caused the replay — sees false and applies nothing.
+     *
+     * Claimed by the APPLIER, never by the burn worker: a worker that claimed and then wrote to a
+     * disposed composition would consume the completion without delivering it, which is the round-3
+     * defect (an outcome published to a tree that has gone away) reintroduced through the guard meant
+     * to prevent its replay.
+     */
+    fun tryApplyBurnCompletion(generation: Int): Boolean {
+        while (true) {
+            val seen = lastAppliedBurnGeneration.get()
+            if (generation <= seen) return false
+            if (lastAppliedBurnGeneration.compareAndSet(seen, generation)) return true
+        }
+    }
+
     private val bootReconcileStarted = java.util.concurrent.atomic.AtomicBoolean(false)
 
     /**
@@ -1427,7 +1461,7 @@ internal fun bootRoute(
     vaultImagePresent: Boolean,
     residueSweepHold: Boolean,
     vaultProvenAbsent: Boolean,
-    legacyImage: Boolean = false,
+    legacyImage: Boolean,
 ): BootRoute = when {
     serverDeleteConfirmed -> BootRoute.DELETE_INCOMPLETE
     // LEGACY comes second — after the confirmed marker, before "an image is present" (a legacy image
@@ -1447,22 +1481,7 @@ internal fun bootRoute(
 }
 
 /** Where a composition must route once a burn has completed — see [postBurnRoute]. */
-internal enum class PostBurnRoute {
-    DELETE_INCOMPLETE,
-    ONBOARDING,
-    LOCKED,
-
-    /**
-     * The completion is STALE — apply nothing (round-5 review, Kimi). [BurnCompletion] is
-     * process-lifetime and never cleared, so `LaunchedEffect(burnCompletion)` re-fires on every later
-     * composition. After a successful burn the user re-onboards and locks; a rotation then re-applied
-     * the LOCKED arm over that healthy successor lock screen, painting a
-     * [VaultUnlockRouter.UNIFORM_FAILURE] the user never earned. Route and `vaultExists` still landed
-     * correctly, so it was not a safety failure — but an unexplained wrong-passphrase error is a
-     * PRIOR-USE TELL, in exactly the scenario this unit exists to protect.
-     */
-    IGNORE_STALE,
-}
+internal enum class PostBurnRoute { DELETE_INCOMPLETE, ONBOARDING, LOCKED }
 
 /**
  * The post-burn route decision, extracted as a PURE function (0.9.2 Unit W, round-4 review, Codex)
@@ -1491,15 +1510,7 @@ internal fun postBurnRoute(
     serverDeleteConfirmed: Boolean,
     burnReportedSuccess: Boolean,
     imageBearingProvenAbsent: Boolean,
-    vaultImagePresent: Boolean = false,
 ): PostBurnRoute = when {
-    // STALE FIRST — before any arm can paint UI. A completion that reported SUCCESS while an image is
-    // now present can only mean a SUCCESSOR vault was created after the burn, so this completion has
-    // already been acted on and must not be re-applied. Stateless by construction: no
-    // "last-applied generation" to keep in composition state, which would reset on the very rotation
-    // that triggers the replay. A FAILED burn is untouched (`burnReportedSuccess` is false there),
-    // which is what keeps the fail-closed LOCKED arm intact.
-    burnReportedSuccess && vaultImagePresent -> PostBurnRoute.IGNORE_STALE
     serverDeleteConfirmed -> PostBurnRoute.DELETE_INCOMPLETE
     burnReportedSuccess && imageBearingProvenAbsent -> PostBurnRoute.ONBOARDING
     else -> PostBurnRoute.LOCKED
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 54da7ec..d517e84 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/c
rypto/vault/VaultImageStore.kt
@@ -1311,6 +1311,26 @@ class VaultImageStore internal constructor(
      *                                                                               A create retry
      *                                                                               overwrote it
      *                                                                               anyway.
+     *  1b {dek, no bin, no markers}                      interrupted                SWEEP. MISSING
+     *                                                    retireLegacyImage — it     WRITER, found in
+     *                                                    unlinks binFile THEN       round 5 (Gemini).
+     *                                                    dekFile, so a crash        Row 1 listed only
+     *                                                    between them lands here    create/burn; this
+     *                                                                               is a third writer
+     *                                                                               of the identical
+     *                                                                               state. Behaviour
+     *                                                                               was already
+     *                                                                               correct (a legacy
+     *                                                                               DEK with no image
+     *                                                                               is dead data), but
+     *                                                                               the TABLE the
+     *                                                                               ratification rests
+     *                                                                               on was incomplete
+     *                                                                               — self-consistent
+     *                                                                               and wrong, which
+     *                                                                               is exactly what
+     *                                                                               "prove it COMPLETE"
+     *                                                                               is for.
      *  2  {dek.tmp, no bin, no markers}                  crash inside               SWEEP. Never a
      *                                                    renameIntoPlace(dekFile)   complete key for
      *                                                                               a live image.
diff --git a/apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt b/apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
index 830bac7..b746086 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt
@@ -32,6 +32,7 @@ class BootRouteTest {
                 vaultImagePresent = false,
                 residueSweepHold = false,
                 vaultProvenAbsent = true,
+                legacyImage = false,
             ),
         )
     }
@@ -52,6 +53,7 @@ class BootRouteTest {
                 residueSweepHold = true,
                 // TRUE — this is the whole point. A stat cannot tell durable from not.
                 vaultProvenAbsent = true,
+                legacyImage = false,
             ),
         )
     }
@@ -66,6 +68,7 @@ class BootRouteTest {
                 vaultImagePresent = false,
                 residueSweepHold = false,
                 vaultProvenAbsent = false,
+                legacyImage = false,
             ),
         )
     }
@@ -82,6 +85,7 @@ class BootRouteTest {
                     vaultImagePresent = true,
                     residueSweepHold = hold,
                     vaultProvenAbsent = false,
+                legacyImage = false,
                 ),
             )
         }
@@ -96,7 +100,7 @@ class BootRouteTest {
                     assertEquals(
                         "present=$present hold=$hold proven=$proven",
                         BootRoute.DELETE_INCOMPLETE,
-                        bootRoute(true, present, hold, proven),
+                        bootRoute(true, present, hold, proven, legacyImage = false),
                     )
                 }
             }
@@ -194,7 +198,7 @@ class BootRouteTest {
             assertEquals(
                 "bootRoute(confirmed=$confirmed, present=$present, hold=$hold, proven=$proven)",
                 want,
• Let me see the rest of the diff for 800d7ab and then read the full key files. Let me continue viewing the show output.                 want,
-                bootRoute(confirmed, present, hold, proven),
+                bootRoute(confirmed, present, hold, proven, legacyImage = false),
             )
         }
         assertEquals("the table must cover every combination", 16, expected.size)
diff --git a/apps/android/app/src/test/java/com/zitrone/app/BurnApplyOnceTest.kt b/apps/android/app/src/test/java/com/zitrone/app/BurnApplyOnceTest.kt
new file mode 100644
index 0000000..c4a549a
--- /dev/null
+++ b/apps/android/app/src/test/java/com/zitrone/app/BurnApplyOnceTest.kt
@@ -0,0 +1,124 @@
+// Zitrone — Copyright (C) 2026 Zitrone contributors
+// Licensed under the GNU Affero General Public License v3.0 or later.
+// See the LICENSE file in the repository root for full license text.
+// SPDX-License-Identifier: AGPL-3.0-only
+
+package com.zitrone.app
+
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertTrue
+import org.junit.Test
+import java.util.concurrent.CountDownLatch
+import java.util.concurrent.Executors
+import java.util.concurrent.TimeUnit
+import java.util.concurrent.atomic.AtomicInteger
+
+/**
+ * PUCKER BURN Unit W — APPLY-ONCE for burn completions (0.9.2, round 5).
+ *
+ * `BurnCompletion` is process-lifetime and never cleared, so `LaunchedEffect(burnCompletion)` re-fires
+ * on every later composition. Round 5's FIRST fix keyed staleness on
+ * `burnReportedSuccess && vaultImagePresent` — which covers only SUCCESSES. A **failed** burn leaves
+ * an image present by definition, so it replayed unconditionally: after the user unlocked, locked, and
+ * rotated, the observer repainted `UNIFORM_FAILURE` on a lock screen they never failed at. The same
+ * prior-use tell, on the other branch. Three reviewers found it independently.
+ *
+ * `obliterated` was never a staleness test. The question is "has this completion already been
+ * applied?", which is what `generation` was added for in round 4 and never used.
+ *
+ * This suite pins the primitive. It is deliberately a HOST-JVM test of the claim function rather than
+ * of the Compose wiring, and it asserts on the DAMAGE — how many appliers can act on one completion —
+ * not on a return value in isolation.
+ */
+class BurnApplyOnceTest {
+
+    /** Stand-in with the identical CAS-loop semantics as `AppContainer.tryApplyBurnCompletion`. */
+    private class Claimer {
+        private val lastApplied = AtomicInteger(0)
+        fun tryApply(generation: Int): Boolean {
+            while (true) {
+                val seen = lastApplied.get()
+                if (generation <= seen) return false
+                if (lastApplied.compareAndSet(seen, generation)) return true
+            }
+        }
+    }
+
+    /**
+     * MUTATION UNIQUELY CAUGHT: dropping the guard entirely (every composition applies). Asserts on
+     * the damage — the number of compositions that would have written UI for ONE burn.
+     */
+    @Test
+    fun `one completion is applied by exactly one composition however many re-fire`() {
+        val c = Claimer()
+        val applied = (1..25).count { c.tryApply(1) }
+        assertEquals("a single completion must be applied exactly once", 1, applied)
+    }
+
+    /**
+     * THE ROUND-5 DEFECT, AS A TEST — the direction the first fix missed. A FAILED burn
+     * (`obliterated == false`) is exactly as stale on replay as a successful one; nothing about the
+     * outcome makes it re-appliable. Generation, not outcome, is the discriminator.
+     */
+    @Test
+    fun `a failed burn completion is equally single-apply`() {
+        val c = Claimer()
+        assertTrue("the first live composition applies it", c.tryApply(1))
+        assertFalse(
+            "a rotation must NOT re-apply a failed burn — that repaints UNIFORM_FAILURE on a lock " +
+                "screen the user never failed at, which is the same tell as the success case",
+            c.tryApply(1),
+        )
+    }
+
+    /** A genuinely NEW burn later in the same process must still be applied. */
+    @Test
+    fun `a later burn is not swallowed by the guard`() {
+        val c = Claimer()
+        assertTrue(c.tryApply(1))
+        assertFalse(c.tryApply(1))
+        assertTrue("generation 2 is a different burn and must apply", c.tryApply(2))
+        assertFalse(c.tryApply(2))
+    }
+
+    /** Out-of-order or replayed older generations never re-apply. */
+    @Test
+    fun `an older generation never re-applies`() {
+        val c = Claimer()
+        assertTrue(c.tryApply(3))
+        assertFalse("generation 1 is stale", c.tryApply(1))
+        assertFalse("generation 2 is stale", c.tryApply(2))
+        assertFalse("generation 3 already applied", c.tryApply(3))
+    }
+
+    /**
+     * Concurrent compositions racing the same completion — a rotation lands while the outgoing
+     * composition is still in its effect. Exactly one may win.
+     *
+     * MUTATION UNIQUELY CAUGHT: a non-atomic read-then-write guard (`if (gen > last) { last = gen;
+     * true }`), which lets two racing appliers both observe the stale value and both write.
+     */
+    @Test
+    fun `concurrent compositions cannot both apply the same completion`() {
+        repeat(50) {
+            val c = Claimer()
+            val threads = 16
+            val start = CountDownLatch(1)
+            val done = CountDownLatch(threads)
+            val winners = AtomicInteger(0)
+            val pool = Executors.newFixedThreadPool(threads)
+            repeat(threads) {
+                pool.execute {
+                    start.await()
+                    if (c.tryApply(1)) winners.incrementAndGet()
+                    done.countDown()
+                }
+            }
+            start.countDown()
+            assertTrue(done.await(10, TimeUnit.SECONDS))
+            pool.shutdownNow()
+            assertEquals("exactly one applier may win the race", 1, winners.get())
+        }
+    }
+}
diff --git a/apps/android/app/src/test/java/com/zitrone/app/PostBurnRouteTest.kt b/apps/android/app/src/test/java/com/zitrone/app/PostBurnRouteTest.kt
index 1ea4f9e..7b8f91b 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/PostBurnRouteTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/PostBurnRouteTest.kt
@@ -174,75 +174,7 @@ class PostBurnRouteTest {
         )
     }
 
-    /**
-     * THE ROUND-5 FINDING, AS A TEST (Kimi). `BurnCompletion` is process-lifetime and never cleared,
-     * so `LaunchedEffect(burnCompletion)` re-fires on every later composition. After a successful burn
-     * the user re-onboards and locks; a rotation then re-applied the LOCKED arm over that healthy
-     * successor lock screen, painting a uniform-failure error the user never earned. Route and
-     * `vaultExists` still landed correctly — not a safety failure — but an unexplained
-     * wrong-passphrase error is a PRIOR-USE TELL, in exactly the scenario this unit protects.
-     *
-     * MUTATION UNIQUELY CAUGHT: removing the `burnReportedSuccess && vaultImagePresent` arm.
-     */
-    @Test
-    fun `a successful completion over a successor vault is stale and applies nothing`() {
-        assertEquals(
-            "a successor vault means this completion was already acted on — repainting the lock " +
-                "screen with a failure the user never earned is a prior-use tell",
-            PostBurnRoute.IGNORE_STALE,
-            postBurnRoute(
-                serverDeleteConfirmed = false,
-                burnReportedSuccess = true,
-                imageBearingProvenAbsent = false,
-                vaultImagePresent = true,
-            ),
-        )
-    }
 
-    /**
-     * The other side, so "ignore whenever an image is present" cannot pass as the fix: a FAILED burn
-     * leaves an image present BY DEFINITION, and it must still reach the fail-closed LOCKED arm.
-     * This is the assertion that keeps the stale guard from swallowing the case the unit exists for.
-     */
-    @Test
-    fun `a failed burn over a surviving image is NOT stale and still locks`() {
-        assertEquals(
-            "a failed burn must never be treated as stal
e — that is the fail-closed path",
-            PostBurnRoute.LOCKED,
-            postBurnRoute(
-                serverDeleteConfirmed = false,
-                burnReportedSuccess = false,
-                imageBearingProvenAbsent = false,
-                vaultImagePresent = true,
-            ),
-        )
-    }
 
-    /** A genuine fresh success — no image left — is not stale and still presents onboarding. */
-    @Test
-    fun `a successful burn with no image left is not stale`() {
-        assertEquals(
-            PostBurnRoute.ONBOARDING,
-            postBurnRoute(
-                serverDeleteConfirmed = false,
-                burnReportedSuccess = true,
-                imageBearingProvenAbsent = true,
-                vaultImagePresent = false,
-            ),
-        )
-    }
 
-    /** Staleness outranks even a confirmed delete: applying nothing is always safe. */
-    @Test
-    fun `staleness is evaluated before the confirmed-delete arm`() {
-        assertEquals(
-            PostBurnRoute.IGNORE_STALE,
-            postBurnRoute(
-                serverDeleteConfirmed = true,
-                burnReportedSuccess = true,
-                imageBearingProvenAbsent = false,
-                vaultImagePresent = true,
-            ),
-        )
-    }
 }
Now I need to read the full current source files. Key files:
  - MainActivity.kt (ZitroneRoot, the burn dispatcher arm, observer)
  - ZitroneApp.kt (AppContainer, tryApplyBurnCompletion, bootRoute, postBurnRoute, boot reconcile, sweep)
  - VaultImageStore.kt (obliterateLocked, sweep gate, writer table, retireLegacyImage)
  - Tests.

  These are large files. MainActivity.kt diff shows ~1100 lines in ZitroneRoot area. Let me read the relevant sections. Given context budget, I should delegate some bulk reading? Actually, I need to verify carefully myself. Let me read the key regions.

  First, find the relevant regions via grep.

• Now let me read the current state of the key source files. First, locating the relevant regions.  1862 MainActivity.kt
  1534 ZitroneApp.kt
   203 UnlockController.kt
  1731 crypto/vault/VaultImageStore.kt
  5330 total


• The test task was UP-TO-DATE — meaning it didn't actually re-run. Focus item E says run it myself and report numbers I observed. I need to force re-run and get the test counts. Let me re-run with `--rerun-tasks` or at least get the test report XML numbers. Re-running the whole thing may take a while; let me run with `--rerun-tasks` in the background, or cleanTest testDebugUnitTest. Also check test result XMLs for totals.

  Let me force rerun in background while reading the code.

• The test task came back UP-TO-DATE — no tests actually executed. Re-running with forced rerun in the background while I read the source.

• Now I have the observer and the burn worker. Next: read ZitroneApp.kt key regions — signalBurnCompleted, bootRoute, postBurnRoute, boot reconcile, sweep, and VaultImageStore.kt's obliterateLocked / sweep gate / retireLegacyImage / marker handling. Also check for any second writer of UI on burn completion (single-applier invariant): grep for `signalBurnCompleted` callers, and for any other place that mutates `route`, `vaultExists`, `lockError` on burn.

  Let me read ZitroneApp.kt around lines 240-300 (burn completion), 800-1000 (boot reconcile), 1340-1534 (BurnCompletion, bootRoute, postBurnRoute).cleanTest was UP-TO-DATE and testDebugUnitTest still UP-TO-DATE — Gradle didn't rerun. The XML results are from the previous run (529 tests, 0 failures, 3 skipped — matching the commit message's claimed numbers). But focus item E says run it yourself. Let me force with `--rerun-tasks` or delete the test-results and use `-Prerun`. Use `./gradlew testDebugUnitTest --rerun-tasks` — that reruns everything including compile, might take a few minutes. Or delete the test results dir and binary results: `rm -rf app/build/test-results app/build/reports/tests` then run — Gradle checks inputs/outputs; deleting outputs forces rerun. Let me do that in background.

  Meanwhile continue reading code. Next: VaultImageStore.kt — obliterateLocked, sweep, retireLegacyImage, writer table, markers. Also check remaining MainActivity regions (Splash boot derivation ~700-800), and ZitroneApp boot reconcile call site (~800-1000).

• Gradle still reported UP-TO-DATE — the XML counts are from the previous run. Forcing a real rerun by deleting outputs, in background, while I continue reading source.

• Now VaultImageStore.kt. Key parts: obliterateLocked, sweep gate (sweepOrphanedResidue), retireLegacyImage, marker retire ordering, writer/reader table, burnObliterationComplete, vaultProvenAbsent, ResidueSweepResult. Let me find them.Test suite actually re-ran this time: 529 tests, 0 failures, 0 errors, 3 skipped, 61 suites. Matches the commit message's claimed numbers (529 total, 526 passed, 3 skipped).

  Let me check which tests are skipped (claimed I2P).

  Now continue reading VaultImageStore.kt from 1299 onward (sweep gate), then 1400-1540.app/build/test-results/testDebugUnitTest/TEST-com.zitrone.app.I2pLiveIntegrationTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.zitrone.app.I2pLiveIntegrationTest.xml:name="com.zitrone.app.I2pLiveIntegrationTest" tests="3" skipped="3"
Good. Skipped = 3 I2P tests, as claimed.

  Now let me continue with VaultImageStore — rest of the file (register/unregister, renameIntoPlace, create's DEK-first ordering). And check `create()` around line 487-596 for marker clearing and ordering. Also check `open()`. Then ZitroneApp startBootReconcile and the boot healer ordering (completeInterruptedBurn, reconcileOrphanedBurnMarkers, sweep, cache clear). Let me read ZitroneApp 800-1000 region and find startBootReconcile.Now I have most of the picture. Remaining verification items:

  1. **A (sibling call sites)**: Check every consumer of `postBurnRoute` (only one now — the observer at MainActivity.kt:852). Check consumers of `bootRoute` (three: Splash decision, boot re-derive, session collector — all pass full input set, verified above). Check the observer's `when` over PostBurnRoute — exhaustive over 3 enum values. Since the dispatcher no longer consumes PostBurnRoute, no sibling. Also check other `when` over PostBurnRoute/BootRoute: BootRoute consumers at MainActivity 796-802 (boot re-derive: `when(decided)` with three arms — DELETE_INCOMPLETE, ONBOARDING, LOCKED — exhaustive, no else). Splash at 743-752 exhaustive. Session collector at 950-962 exhaustive. Good.

     But wait — the boot re-derive `when` at 796: BootRoute.DELETE_INCOMPLETE -> sets route; ONBOARDING -> only moves stale Locked forward; LOCKED -> Unit. Note it doesn't handle... it's exhaustive (3 arms, no else needed since enum). Fine.

  2. **B (single applier)**: The burn worker no longer writes UI. Check no other writer of `route`/`vaultExists`/`lockError` related to burn. The observer claims `tryApplyBurnCompletion` BEFORE the disk reads and before applying. Potential issue: **claim before the disk read and before the when — if the effect is cancelled between claim and apply (e.g., disposed mid-`withContext`), the completion is consumed but never delivered**. The prompt B asks: "Can a completion be consumed but never delivered?" Look at MainActivity 830-884: claim at 846, then `withContext(Dispatchers.IO)` (suspension point), then `when` applies. If the composition is disposed (rotation) during the `withContext`, the LaunchedEffect is cancelled → claim consumed, no UI write, and no other composition will ever claim generation N. Is the completion then lost?

     Wait — but the new composition created after rotation will fire `LaunchedEffect(burnCompletion)` with the same non-null completion, call tryApplyBurnCompletion(N) → false (since N <= lastApplied). So it applies nothing. The state vars in the new composition are seeded from scratch: route seeded as Splash (no session) or... let's trace: after rotation post-burn, new composition seeds `route = Splash` (session null), `vaultExists = container.hasVault()`. If burn succeeded: hasVault false → vaultExists false. Splash runs, bootDone already true, so splash decision routes to Onboarding via bootRoute (serverDeleteConfirmed=false, present=false, hold=false, provenAbsent=true → ONBOARDING). Good — lands on onboarding. If burn failed (image present): hasVault true → seeds Locked eventually via bootRoute (present → LOCKED). But the lockError UNIFORM_FAILURE would NOT be repainted — actually that's fine: the user sees a lock screen without an error. Hmm, but the original delivery (UNIFORM_FAILURE) was lost. Is that a defect? The design says: completion consumed but never delivered means the user doesn't see the failure message. The rotation race window: the effect claimed, then disposed mid-withContext. The recreated composition seeds from disk truth, which is correct routing (Locked for failed burn, Onboarding for success). The only loss is the lockError cosmetic message... but wait, actually is there a subtle worse case?

     Consider: rotation happens mid-burn (before completion published). Composition A disposed; composition B created, seeds vaultExists = hasVault() = true (image still present while burn in flight), route Splash → Locked. Burn completes on container.scope, publishes completion gen 1. Composition B's observer fires: session null, tryApply(1) → true, applies LOCKED arm with UNIFORM_FAILURE. Fine.

     Consider: composition B claims, then rotation disposes B mid-withContext(IO). Composition C created: seeds from disk. If burn succeeded, disk: all absent → C routes Splash → Onboarding. Correct end state, no delivery needed. If burn failed with image present → C routes Locked, without the error message. Acceptable degradation — routing correct. If burn failed with image absent but residue present (dek/tmp surviving): hasVault false, vaultProvenAbsent false → bootRoute: present=false, hold?, provenAbsent=false → else → LOCKED. But vaultExists seeded from hasVault = false. Route Locked with vaultExists=false — lock screen with vaultExists false... The observer's LOCKED arm would have set vaultExists=true. In composition C, the observer doesn't fire (claim consumed). So we get a lock screen with vaultExists=false. What does that affect? `biometricUnlockAvailable = vaultExists && ...` false → no biometric affordance (fine). Does the lock screen render the passphrase field if vaultExists false? Need to check how route/vaultExists drive rendering. Actually routing to Locked with vaultExists=false may present... let me check what Route.Locked renders and whether vaultExists=false changes it. Also the next boot: sweep cleans residue, then reconcile; fine.

     This is a narrow race (rotation during a sub-millisecond IO stat window). Severity LOW. The lost delivery is the UNIFORM_FAILURE paint; routing still lands fail-closed via boot derivation. Also, claim-before-read is deliberate per the kdoc (claimed by applier on a live composition). But strictly, between claim and write there are suspension points, so "claimed by the applier immediately before a LIVE composition writes" is not quite airtight: the composition can die after claiming. However the consequence is bounded: the replacement composition re-derives from disk truth and routes fail-closed. The burn worker publishes once; completion is RAM-only. I should verify that the re-derivation in composition C doesn't require the observer: route seeds Splash; splashFinished & bootDone (bootDone already true) → decision from disk → Onboarding or Locked. Yes.

     One more: session check `container.session.value != null` return — no session exists for a burn. OK.

     Also cancellation within the `when` arms: they're plain assignments, non-suspending. So the only cancellation window is the `withContext(Dispatchers.IO)`. Confirmed LOW.

  3. **C (default params)**: Check all safety-decision functions in touched surface for defaults: bootRoute (all required now), postBurnRoute (3 params, no defaults — verified at 1509-1517). runBootReconcile has `afterPublish: () -> Unit = {}` and `ioDispatcher = Dispatchers.IO` defaults — are these safety-decision inputs? afterPublish default is benign hygiene; ioDispatcher default is production-correct. The instruction says "no other safety-decision function in the touched surface still carries a default that could re-enable an incomplete input set". BootRoute/postBurnRoute calls all pass all args (verified: Splash 722-731, re-derive 784-790, session collector 951-957, observer 852-856). Also check BurnAppLocalState functions / other decision functions e.g. in UnlockController or SettingsRepository touched by the unit. Let me grep for `= false` / `= true` defaults in the touched files' decision functions. Also check `PassphraseOutcome` mapping etc. I'll grep ZitroneApp.kt and MainActivity.kt for default Boolean params.

  4. **D (table completeness)**: Enumerate writers of dek/temp-without-bin states:
     - create(): writes dek first, then bin — interrupted create → row 1/2/3. Need to verify create's actual order.
     - obliterateLocked: keys-first unlink — partial burn → {bin present, dek absent} (handled by completeInterruptedBurn) or {dek, no bin} after both unlinks... wait obliterateLocked unlinks dek then bin; crash between → {bin present, dek absent} → that's completeInterruptedBurn's signature (bin present → sweep gate 1 refuses; completeInterruptedBurn completes). Crash after both unlinks but before dirSync → residue {no bin, no dek, maybe temps} → row 9-ish or sweep with nothing? Temps deleted before... order: dekFile.delete, deleteLeftoverTmp(dekFile), binFile.delete, deleteLeftoverTmp(binFile). Crash between deleteLeftoverTmp(dekFile) and binFile.delete → {bin present, dek absent, dek.tmp absent} → completeInterruptedBurn. OK.
     - retireLegacyImage: binFile.delete() THEN dekFile.delete() — crash between → {dek, no bin} → row 1b. Also it deletes leftover tmps after both. Crash between binFile.delete and dekFile.delete leaves {dek present, bin absent} → sweep handles (row 1b). Note: retireLegacyImage deletes bin FIRST, so it can never leave {bin, no dek}. Good.
     - renameIntoPlace: stages tmp then renames — crash inside renameIntoPlace(dekFile) → {dek.tmp} row 2. Crash between dek rename and bin rename → {dek, bin.tmp} row 3.
     - What about `writeSealedPayload` / reseal during session? Reseal writes to a live image (bin present) — gate 1 refuses. Row 4.
     - destroy(): writes confirmed marker first → gate 2 refuses. Row 7.
     - What about `open()` deleting leftover temps — that's a deleter not a writer.
     - Any other writer of vault.bin.tmp/vault.dek.tmp? renameIntoPlace is used by create and reseal (writeSealedPayload?). Reseal happens only with live image.
     - What about {dek.tmp only} etc. — row 2.
     - What about interrupted destroy (D2c) — marker present, gate 2 refuses. Row 7.
     - What about a burn while a delete-intent outstanding → row 6b.
     - What about {bin indeterminate} → row 5.
     - Missing row hunt: what about `{dek, bin.tmp}` written by **reseal on a live image where bin was deleted externally**? Not in scope. What about `writeSealedPayload` creating tmp for an existing image — bin present.
     
     One thing to check: does the sweep delete `dekFile` but the row-6b case had intent present — sweep has no intent gate, so it sweeps, then reconcileOrphanedBurnMarkers retires the intent. Boot ordering: sweep first (a0), then completeInterruptedBurn (a), then reconcileOrphanedBurnMarkers (b). Correct.

     Also: does `create()` still clear markers before writing DEK? Check create around line 487. Also confirm claim about DEK-first.

  5. **F (fix landed)**: Verified in diff & current source: tryApplyBurnCompletion defined AND called at MainActivity.kt:846. Session collector fix at 949. Defaults removed at ZitroneApp 1464, 1509-1512. IGNORE_STALE removed from enum. Dispatcher UI-write removed (1086-1097). Table row 1b added. Tests: BurnApplyOnceTest exists (124 lines), PostBurnRouteTest IGNORE_STALE tests removed. All landed.

     But wait — commit message says "Tests: 529 total (+5 net)". I observed 529. Good.

  6. **G (cumulative re-verification)**: 
     - destroy() equivalence under keys-first: destroy writes confirmed marker then obliterateLocked — keys-first OK because marker bridges. Verified.
     - Marker clear strictly after unlinks durable: obliterateLocked step 4 after dirSync. Verified.
     - Boot healers as one system: sweep → completeInterruptedBurn → reconcileOrphanedBurnMarkers, then publish hold, then afterPublish cache retry. Ordering correct.
     - WRITER/READER for durable signals & in-flight verdicts: burnCompletion RAM-only; residueSweepHold process-scoped; bootReconciled gates.
     - Reachability: slot 0 unarmed — verify createVaultSlots writes random filler at slot 0 and nothing arms it. Check attemptUnlockOrAdd Burn outcome wired only to lock-screen dispatch. onBurn called only from PassphraseOutcome.Burn at line 1115. Check where PassphraseOutcome.Burn is produced (UnlockController / VaultUnlockRouter). Let me check UnlockController.kt and attemptPassphrase.
     - Concurrency: tryBeginTerminalWipe exclusive gate. Check UnlockController.
     - Fail-closed: partial burn → postBurnRoute LOCKED; sweep hold → bootRoute LOCKED. Verified.

  7. **H (testability)**: Answer based on what I see: runBootReconcile extracted & host-testable (BootReconcileOwnerTest exists). bootRoute/postBurnRoute pure & tested. tryApplyBurnCompletion testable but BurnApplyOnceTest tests a STAND-IN Claimer, not AppContainer.tryApplyBurnCompletion itself! Wait — the test class says "Stand-in with the identical CAS-loop semantics as AppContainer.tryApplyBurnCompletion". That's a copy of the code, not the real function. Instruction 5: a test that a value is computed is not a test that it is used. The stand-in tests the algorithm but not the actual AppContainer method — if someone edits AppContainer.tryApplyBurnCompletion, no test fails. The commit message claims "Mutation-verified: a non-atomic read-then-write guard fails the race test and only that test" — but that mutation was presumably applied to the stand-in? Mutating the real function would fail no test. That's a real coverage gap worth a LOW (test-quality) finding: the apply-once primitive is tested via a duplicate, and the consumption site (observer LaunchedEffect) is untested. Actually, is AppContainer host-instantiable? AppContainer takes an Application — needs Robolectric. tryApplyBurnCompletion itself doesn't touch Android; could be called on a Robolectric-built container or extracted. Given robolectric is declared, they could test the real one. So LOW finding: BurnApplyOnceTest pins a stand-in, not the shipped function; a mutation to AppContainer.tryApplyBurnCompletion (e.g., `>=` to `>`, or removing the CAS) fails no test. Hmm wait, `generation <= seen` mutation to `<`... whatever — the point stands: no test references the real function. Let me grep tests for tryApplyBurnCompletion usage.

     Also check PostBurnRouteTest still covers the current 3-arm function; BootRouteTest passes legacyImage everywhere.

  8. **I**: File.exists() verify inside obliterateLocked — lines 1168-1172 use `binFile.exists() || dekFile.exists() || ...`. This is the fail-closed direction (throw if file seems present). exists()==true on stat failure? File.exists() returns false on I/O error — so a stat failure reads as absent → could report success over a surviving file. But the subsequent dirSync + caller-side `burnObliterationComplete()` (tristate Files.notExists) provides the actual proof — the dispatcher requires `completed && container.burnObliterationComplete()` which is tristate. So the exists() inside obliterateLocked is backstopped. Agree with INFO/out-of-scope.

  Also item 2 in the sweep gate: `sweepOrphanedResidue` gate 1 uses Files.notExists(binFile) — tristate good.

  Now potential deeper issues to hunt:

  **(a) The observer claim ordering vs session check**: `if (container.session.value != null) return@LaunchedEffect` BEFORE the claim. Scenario: burn completes, then user creates a new vault + unlocks (session non-null) without the observer ever applying (e.g., observer fires first, applies ONBOARDING; user creates; session live; rotation → observer re-fires, session != null → returns before claim — fine). But scenario: burn completes → observer claims & applies ONBOARDING. Fine. Scenario: burn completion published while a session is live? A burn requires the lock screen; no session. OK.

  Edge: completion claimed by composition, applied ONBOARDING, user creates new vault, locks. Rotation: observer fires, session null now (locked), tryApply(gen) → false → returns. Good — no stomping the successor lock screen. This is exactly what the guard is for.

  Edge: **two burns in one process**. After first burn applied, user re-onboards, locks, burns again (structurally unreachable in production but mechanism-wise). signalBurnCompleted gen=2 > 1 → applies. Good.

  **(b) signalBurnCompleted read-modify-write race**: `(burnCompletion.value?.generation ?: 0) + 1` then assign — not atomic. Two concurrent burns? tryBeginTerminalWipe is exclusive, so only one burn runs at a time. But signalBurnCompleted is called in finally after endTerminalWipe — wait, order: endTerminalWipe() THEN signalBurnCompleted. So gate released before publishing. Could a second burn start and finish its signal before the first burn's signal? First burn: endTerminalWipe, then signal (gen 1). A second onBurn could begin after gate release, run whole burn, signal gen... if first burn's signal hasn't executed yet (thread scheduling between the two statements), second burn signals gen 1 (reads null), then first burn signals gen 1 again — same generation, two completions, second overwrites first. Both gen 1 → apply-once applies one. But outcomes could differ (first failed, second succeeded). The second burn's completion would be overwritten by the first's — WRONG outcome published. Realistically: both calls are on container.scope coroutines; the window is tiny but real. More importantly can a second burn even start? onBurn requires PassphraseOutcome.Burn from a passphrase attempt at the lock screen; after first burn, if failed, user retries... The second burn's tryBeginTerminalWipe succeeds after first released. Then second burn runs (takes time, Argon2? no — burn is just file deletes; fast). The interleaving needed: first burn coroutine preempted between endTerminalWipe and signalBurnCompleted while the entire second unlock attempt + burn completes. Extremely unlikely but the read-modify-write is non-atomic; a safer impl would use a single AtomicInteger counter. Severity: LOW/INFO — mechanism unreachable in production (slot 0 unarmed), and requires pathological scheduling. Worth noting: make generation its own AtomicInteger. Actually also `burnCompletion.value?.generation` read — MutableStateFlow updates are serialized for value assignment, but the read+compute+write isn't CAS. I'll flag LOW.

  **(c) The burn worker used to set `vaultExists=true` on failure within the dispatcher; now the observer's LOCKED arm sets vaultExists=true. But the dispatcher's composition might be the one that... fine, observer covers.**

  **(d) burnCompletion published before or after endTerminalWipe?** finally: endTerminalWipe() then signalBurnCompleted. Observer applies → ONBOARDING → user can create → publishSession requires gate released. Gate released first. Good ordering.

  **(e) Observer claim happens before checking session?** No: session check at 834, claim at 846. If session non-null, no claim. Scenario: burn completes, observer applies ONBOARDING (claimed gen1), user creates & unlocks, session live, locks (session null), rotate → observer re-fires, claim false → no-op. Correct.

  But scenario: completion published while ANOTHER composition's session is live? Burn requires no session... `onBurn` from lock screen only. OK.

  **(f) What if the observer's postBurnRoute returns ONBOARDING but user already started creating a new vault between claim and apply?** The withContext window: user would have to be on onboarding and tap create within the millisecond window after a burn... The apply sets vaultExists=false, route=Onboarding — same state. Harmless.

  **(g) `vaultExists` seeded `container.hasVault()` in remember — after a successful burn within the same process, a recreated composition seeds vaultExists=false. Good. After a FAILED burn with image absent but residue: seeds false but bootRoute → LOCKED (provenAbsent false). Lock screen with vaultExists=false — need to check what the lock screen renders when vaultExists false. If Route.Locked renders regardless, fine. Let me check the render logic for Route.Locked and whether vaultExists=false breaks it. Grep for "Route.Locked ->" rendering.

  **(h) residueSweepHold: is it ever reset?** It's process-lifetime, set once at publish. Fine.

  **(i) Check the sweep's catch clause**: `catch (t: Throwable)` inside imageLock.withLock, synchronous, no CancellationException. OK.

  **(j) completeInterruptedBurn: fires when dek absent AND bin present — but ALSO requires confirmed marker absent. What about {bin present, dek absent, delete-intent present}? destroy always writes confirmed first, so intent+bin+no-dek can arise only from burn-during-intent. completeInterruptedBurn runs obliterateLocked which clears BOTH markers at step 4 — clearing a GENUINE delete-intent! Wait: sequence — user initiates account delete (intent written), server request in flight; app crashes; user... no wait, burn requires lock screen. Session live during delete... Actually can a burn run while a delete-intent is outstanding? Boot routes intent+image → normal unlock. User unlocks (session), the post-unlock reconcile retries DELETE. User locks, enters duress credential → burn. obliterateLocked clears both markers at step 4. The intent was genuine (server outcome unknown). After burn, the account may still exist on the server but the device image is gone — post-burn ≡ fresh install; the intent marker clearing is consistent with burn semantics (burn wipes everything, no markers). Row 6b covers partial burn with intent; completeInterruptedBurn's kdoc says it "DEFERS TO D2c" only for confirmed. For intent: it proceeds and obliterateLocked retires the intent marker durably. Is that correct? The intent's purpose is to retry the server DELETE on next session. If burn completed fully, the vault is gone; the orphaned intent over absent image is exactly what reconcileOrphanedBurnMarkers retires anyway. So clearing is correct — the server account may still exist, but a burn deliberately abandons server-side deletion (local-only by design). Documented. OK.

  But wait — there's an asymmetry: completeInterruptedBurn checks `dekFile` absent and `binFile` present, then calls obliterateLocked, which at step 4 clears markers — including the intent. That's the burn-completion semantics. Fine.

  **(k) Row hunt in the table — the state {bin present, dek absent, temps?}**: completeInterruptedBurn handles {bin, no dek}. What about {bin present, dek present, dek.tmp present}? That's a live vault interrupted mid-reseal — open() deletes leftover temps. Gate 1 refuses (bin present). Row 4 covers. What about {bin.tmp present, dek present, no bin}? Row 3. {dek.tmp, bin.tmp, no bin, no dek}? Rows 2/3 variants — sweep deletes all three. Fine.

  What about **writeSealedPayload** (line 867) — writes to the image during a live session; if it uses renameIntoPlace, crash → tmp leftover with bin present → row 4 / open() cleans. OK.

  **(l) The `vaultExists` LOCKED arm in observer sets vaultExists=true even when image is absent but residue present — deliberate (comment at 872-875). Good.**

  **(m) Grep for other places referencing burnCompletion or applying burn outcome — the single applier check.** Already did: only MainActivity 830. Also check `onBurn` label no other UI writes — verified 1086-1097.

  **(n) Check UnlockController for tryBeginTerminalWipe.**

  Let me also verify slot-0 unarmed claim: createVaultSlots writes random filler slot 0, and attemptUnlockOrAdd → Burn only from VaultUnlockRouter/attemptPassphrase mapping. Check UnlockController.kt and where UnlockOrAdd.Burn is mapped to PassphraseOutcome.Burn.

  Also check open() deletes leftover temps (claim in table row 3).

  Also check create() ordering (DEK-first barrier + marker clearing before writing).

  Let me read the needed VaultImageStore sections: 292-360 (exists, primaryImageProvenAbsent, isLegacyImage, open), 487-600 (create), 1588-1731 (renameIntoPlace, atomicWrite, leftoverTmp, defaultFsyncDir). And UnlockController.kt fully (203 lines).

• Now the remaining verification reads: `create()` ordering, `open()`, `renameIntoPlace`, and `UnlockController`.

• UnlockController verified: tryBeginTerminalWipe exclusive, unlock refused while terminalWipe. Note: `onBurn`'s worker publishes signalBurnCompleted in finally after endTerminalWipe — the ordering concern I noted (read-modify-write of generation) requires two burns; second burn can only begin after endTerminalWipe. Tiny non-atomic window: worker1 endTerminalWipe → preempted → worker2 (from a second passphrase attempt... but wait, the UI: after burn fails, lockError shown; user re-enters duress credential → onBurn → tryBeginTerminalWipe succeeds → worker2 runs on container.scope → obliterate (fast) → finally: endTerminalWipe → signalBurnCompleted reads burnCompletion.value. If worker1 still hasn't run its signalBurnCompleted, both read null → both publish gen 1; last writer wins. Outcomes: if worker1 failed and worker2 succeeded, and worker1's signal lands last → publishes FAILED after a SUCCESSFUL burn → observer applies LOCKED with UNIFORM_FAILURE over... vaultExists=true set → lock screen over an absent vault → unlock attempts escalate as unreadable image. Hmm, but also postBurnRoute with burnReportedSuccess=false → LOCKED. That's a fail-closed direction (never presents a failed burn as success), but it strands: vault actually gone, user sees lock screen. Next cold start fixes it. Also note: the observer consumed gen 1 once; if worker1's publish lands after worker2's, both are gen 1 — StateFlow emits both (distinct object instances? BurnCompletion is a data class — equal values get conflated by StateFlow! If both are BurnCompletion(1, false) identical, StateFlow dedups. If different obliterated, second emit differs → LaunchedEffect re-fires (new key) → tryApply(1) false → swallowed. So a wrong (stale) outcome could be applied first and the correct one swallowed by apply-once. Window requires worker1 preempted between endTerminalWipe and signalBurnCompleted for the entire duration of a second passphrase entry + burn. Practically impossible (user interaction takes seconds; the preempted coroutine would resume within ms). Still, the ordering could be hardened: publish BEFORE endTerminalWipe, or use an AtomicInteger generation counter. Given mechanism unreachable in production, LOW/INFO.

  Actually wait — is it even possible for a second burn to be dispatched while worker1 is between endTerminalWipe and signal? The user would have to re-enter the duress passphrase on the lock screen. The failed burn's UI (UNIFORM_FAILURE) is only painted by the observer AFTER signalBurnCompleted. Before that, the lock screen shows... the first onBurn set nothing (dispatcher no longer writes UI; on the attempt, `unlocking=true` was set by onUnlockPassphrase, and... hmm, who resets `unlocking` for the burn path now? Look: onUnlockPassphrase sets unlocking=true, launches attempt; outcome Burn → onBurn(). onBurn claims gate, launches worker on container.scope, returns Unit. Nothing sets unlocking=false in the burn path until the observer applies (all three arms set unlocking=false). So the lock screen spinner shows until the observer applies. If the completion is consumed-but-never-delivered (claim-then-dispose race), unlocking stays true FOREVER in the new composition? No — `unlocking` is composition-local `remember`, so the NEW composition seeds unlocking=false. Fine.

  But in the same composition, if the observer... no, the observer always applies once claimed, unless cancelled mid-withContext.

  OK. Now remaining checks:

  1. Read open() (343+) to verify leftover-temp cleanup claim and LegacyImage throw.
  2. Read renameIntoPlace/leftoverTmp (1588-1660).
  3. Grep for remaining default params on decision functions: `= false` / `= true` in ZitroneApp.kt/MainActivity.kt function signatures (C).
  4. Grep tests for `tryApplyBurnCompletion` to confirm the stand-in situation (test pins a copy).
  5. Check slot-0 unarmed: createVaultSlots / attemptUnlockOrAdd Burn wiring.
  6. Check how Route.Locked renders with vaultExists=false (the consumed-but-undelivered edge).
  7. Look at test files quickly (BootReconcileOwnerTest, SweepOrphanedResidueTest, BurnObliterateTest, BurnAppLocalStateTest, PostBurnRouteTest, BootRouteTest, UnlockControllerTest) — judge coverage at consumption sites.
  8. Check docs/SECURITY_MODEL.md changes briefly for the cold-start residual claim.

  Let me parallelize: read open() region, renameIntoPlace region, and grep tests.Confirmed: BurnApplyOnceTest tests a stand-in copy, never the real `AppContainer.tryApplyBurnCompletion`. Nothing in the test tree references the real function. A mutation to the real function fails no test. The commit message's "Mutation-verified: a non-atomic read-then-write guard fails the race test" can only have been verified against the stand-in. That's a coverage finding (standing instruction 5: a test that a value is computed is not a test that it is used — judge coverage at the consumption site). Also note instruction: "two test headers naming mutations they provably could not catch" was a prior pattern — here the header says "MUTATION UNIQUELY CAUGHT" for the Claimer mutation, which is true for the stand-in but the shipped code is untested. Severity LOW (test-quality; behavior itself verified correct by reading).

  Also check: the race window in the observer — claim before `withContext(Dispatchers.IO)`. Also, `primaryImageProvenAbsent` at line 304 — check it. And attemptUnlockOrAdd Burn production & slot-0 unarmed. Let me look at 292-342 and 696-870. Also check where slot 0 filler is written (createImage / createVaultSlots in another file? ops.createImage — in crypto package). Grep for SLOT 0 / burnSlot.Note at line 805: the second-vault create path writes a new vault.bin via atomicWrite → renameIntoPlace stages `vault.bin.tmp` — with bin present, so sweep gate 1 refuses. Row 4 covers.

  Now, the attemptUnlockOrAdd create path: a crash between dek... no, it reuses existing DEK, only writes bin. Crash inside renameIntoPlace(binFile) → {bin, bin.tmp} → bin present → gate 1 refuses; open() deletes leftover tmp. Fine.

  Remaining checks:
  1. Slot-0 unarmed: where is slot 0 filled with random? createImage is in another file — grep for BURN_SLOT_INDEX and createImage. Check the crypto package.
  2. attemptPassphrase → PassphraseOutcome.Burn mapping (VaultUnlockRouter) — confirm Burn only surfaced to onBurn at lock screen.
  3. Grep for defaults on safety functions in touched files (C).
  4. Check Route.Locked rendering with vaultExists=false (consumed-but-never-delivered edge).
  5. Check SettingsRepository and MessagingNotifications changes (part of the cumulative diff).
  6. Check BurnObliterateTest / SweepOrphanedResidueTest / BootReconcileOwnerTest briefly for consumption-site coverage.
  7. docs/SECURITY_MODEL.md changes.

  Let me parallelize greps.The grep for defaults found none in the touched safety surface (bootRoute, postBurnRoute, sweep, etc.) — remaining defaults are unrelated UI/net/padding helpers. Also check defaults with `= false`/`= true` inside ZitroneApp.kt function signatures — the regex above covered all files in the package; none in ZitroneApp.kt, MainActivity.kt, VaultImageStore.kt, UnlockController.kt. runBootReconcile has `afterPublish: () -> Unit = {}` and `ioDispatcher = Dispatchers.IO` — lambda/dispatcher defaults, not safety inputs (not booleans that drop an input set). Fine for C.

  Now:
  1. Check Route.Locked rendering with vaultExists=false (edge: consumed-but-never-delivered failed burn + rotation). Grep MainActivity for `Route.Locked ->`.
  2. Skim test files — BurnObliterateTest, SweepOrphanedResidueTest, BootReconcileOwnerTest, BurnAppLocalStateTest, PostBurnRouteTest, BootRouteTest, UnlockControllerTest — focus on consumption-site coverage and whether headers claim mutations they can't catch.
  3. SECURITY_MODEL.md diff (docs claims).
  4. Also verify no other caller of `signalBurnCompleted` and no other writer to `route`/`vaultExists` from burn path (already done via grep of burnCompletion — only one observer).

  Also double check `wipeLegacyPrefs`, SettingsRepository.clearAllForWipe, MessagingNotifications.clearAllForWipe (cumulative diff). Let me look at the cumulative diff for those two files briefly.diff --git a/apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt b/apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt
index 2a6f942..de4b004 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/data/SettingsRepository.kt
@@ -14,10 +14,16 @@ import kotlinx.coroutines.flow.asStateFlow
  * User preferences, persisted via EncryptedSharedPreferences only.
  * All defaults follow the master spec: Tor OFF (opt-in), biometric gate ON,
  * burn-on-read OFF, no default TTL.
+ *
+ * The [prefs] constructor is the seam under test; the [KeyStoreManager] convenience constructor is
+ * what production wires (the same PREFS_SETTINGS file the biometric wrap uses). Mirrors
+ * [BiometricUnlockStore]'s existing split — the production EncryptedSharedPreferences path binds
+ * AndroidKeyStore, which no host JVM (Robolectric included) can provide.
  */
-class SettingsRepository(keyStoreManager: KeyStoreManager) {
+class SettingsRepository(private val prefs: android.content.SharedPreferences) {
 
-    private val prefs = keyStoreManager.prefs(KeyStoreManager.PREFS_SETTINGS)
+    constructor(keyStoreManager: KeyStoreManager) :
+        this(keyStoreManager.prefs(KeyStoreManager.PREFS_SETTINGS))
 
     data class Settings(
         val onboardingDone: Boolean = false,
@@ -94,6 +100,23 @@ class SettingsRepository(keyStoreManager: KeyStoreManager) {
         _settings.value = load()
     }
 
+    /**
+     * Clear EVERY device setting back to first-run defaults, file AND in-RAM snapshot (0.9.2 Unit W).
+     * Used by the Pucker Burn wipe: `onboarding_done` staying true over a destroyed vault would be an
+     * app-controlled forensic tell ("this install completed onboarding, then its vault vanished"), and
+     * the user's chosen transport/auto-lock values are themselves prior-use evidence. `commit()` (not
+     * `apply()`) so the clear is on disk before the burn's verification reads it.
+     */
+    fun clearAllForWipe(): Boolean {
+        @Suppress("ApplySharedPref")
+        // commit()'s result is RETURNED, not discarded (round-2 review): it reports whether the clear
+        // actually reached disk, and a burn that silently failed to reset `onboarding_done` leaves
+        // app-controlled prior-use evidence behind.
+        val committed = prefs.edit().clear().commit()
+        _settings.value = load()
+        return committed
+    }
+
     private fun load(): Settings = Settings(
         onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
         biometricRequired = prefs.getBoolean(KEY_BIOMETRIC, true),
diff --git a/apps/android/app/src/main/java/com/zitrone/app/notifications/MessagingNotifications.kt b/apps/android/app/src/main/java/com/zitrone/app/notifications/MessagingNotifications.kt
index 21449e4..37d4bbb 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/notifications/MessagingNotifications.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/notifications/MessagingNotifications.kt
@@ -137,6 +137,27 @@ object MessagingNotifications {
         NotificationManagerCompat.from(context).cancelAll()
     }
 
+    /**
+     * Remove EVERY notification artifact this app created — posted notifications AND the channel
+     * itself (0.9.2 Unit W, Pucker Burn). A fresh install has no channel until [ensureChannel] first
+     * runs, so a `messages_v2` entry sitting in system notification settings is prior-use evidence
+     * that survives deleting the vault; and a posted "New message" notification on a device that
+     * presents first-run onboarding is a live contradiction of the same story.
+     *
+     * Deletes the LEGACY ids too, so an install old enough to predate the custom-sound channel bump
+     * doesn't leave the older entry behind.
+     *
+     * NOTE: Android may retain a system-level record that a channel once existed (notification
+     * history / logs are outside app control) — this removes what the app owns, which is the honest
+     * bound. See docs/SECU
RITY_MODEL.md.
+     */
+    fun clearAllForWipe(context: Context) {
+        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
+        NotificationManagerCompat.from(context).cancelAll()
+        manager.deleteNotificationChannel(CHANNEL_ID)
+        LEGACY_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
+    }
+
     /**
      * Opens the system's per-channel notification settings for the messages
      * channel, where the user can pick ANY sound (a system ringtone or their
Check the LockScreen call at 1517 — what parameters depend on vaultExists? Let me read 1490-1560.LockScreen doesn't take vaultExists — it renders the passphrase field regardless. So a Route.Locked with vaultExists=false still renders a functional lock screen (no biometric affordance). The consumed-but-never-delivered edge degrades to a normal lock screen without the error message. Fail-closed, acceptable. LOW at most.

  Now skim the test files. Priorities:
  - PostBurnRouteTest (current state, does it cover all arms with the 3-arg function)
  - BootRouteTest (16-combination table)
  - BurnObliterateTest (607 lines — check it tests consumption: burnVault wiring, fail-closed)
  - SweepOrphanedResidueTest (369)
  - BootReconcileOwnerTest (298)
  - BurnAppLocalStateTest (243)
  - UnlockControllerTest (72)

  I'll skim headers + test names rather than full bodies. Let me grep for `fun \`` test names in each file.== BurnApplyOnceTest.kt
53:    fun `one completion is applied by exactly one composition however many re-fire`() {
65:    fun `a failed burn completion is equally single-apply`() {
77:    fun `a later burn is not swallowed by the guard`() {
87:    fun `an older generation never re-applies`() {
103:    fun `concurrent compositions cannot both apply the same completion`() {
== BootReconcileOwnerTest.kt
66:    fun `a second start does not re-run the destructive sweep`() = runTest {
102:    fun `a consumer released by the done signal never observes a stale hold`() = runTest {
136:    fun `a sweep that throws releases waiters fail-closed`() = runTest {
167:    fun `a claimant cancelled mid-work does not strand a waiter`() = runTest {
204:    fun `cancellation after a durable sweep does not invent a hold`() = runTest {
232:    fun `a retry after a cancelled run does not re-sweep`() = runTest {
261:    fun `a durable sweep publishes no hold`() = runTest {
281:    fun `an untouched disk publishes no hold`() = runTest {
== BootRouteTest.kt
27:    fun `a provably clean directory boots to onboarding`() {
46:    fun `a non-durable sweep withholds onboarding even though the directory stats clean`() {
63:    fun `unswept residue holds the lock screen`() {
78:    fun `a present image is always a lock screen`() {
96:    fun `a confirmed server delete outbids every other input`() {
122:    fun `a confirmed server delete outbids a legacy image`() {
139:    fun `a legacy image routes to onboarding when no delete is confirmed`() {
159:    fun `legacy outranks image-present but not a confirmed delete`() {
176:    fun `full truth table`() {
213:    fun `onboarding is reachable from exactly the expected input combinations`() {
== BurnAppLocalStateTest.kt
75:    fun `burn clears plaintext attachment and QR-drop staging from the cache`() {
92:    fun `cache clear leaves the directory itself present and empty, as a fresh install has it`() {
100:    fun `cache clear is a no-op on an absent or already-empty directory`() {
114:    fun `cache clear FAILS CLOSED when the directory cannot be listed`() {
137:    fun `cache clear empties nested plaintext staging directories`() {
152:    fun `burn deletes the notification channel the app created`() {
168:    fun `burn deletes legacy notification channels too`() {
179:    fun `notification wipe is idempotent and safe when nothing was ever created`() {
190:    fun `burn clears the boot diagnostics log`() {
210:    fun `burn resets every device setting to first-run defaults`() {
== BurnObliterateTest.kt
102:    fun `destroy still removes image, dek and temps and retires both markers`() {
122:    fun `destroy writes the confirmed marker BEFORE unlinking - crash bridge preserved`() {
137:    fun `destroy is idempotent`() {
150:    fun `burn destroys image, dek and temps`() {
167:    fun `burn NEVER writes the delete-confirmed marker`() {
181:    fun `burn clears a pre-existing delete-intent so post-burn equals fresh install`() {
195:    fun `burn is idempotent`() {
204:    fun `burn FAILS CLOSED when the unlinks cannot be made durable`() {
213:    fun `burn releases the single-instance registration so a re-onboard can create in-process`() {
239:    fun `markers are NOT cleared when the unlink durability proof fails`() {
259:    fun `image without its DEK is unrecoverable - the keys-first crash payoff`() {
276:    fun `reconcile clears an orphaned intent marker over an absent image`() {
289:    fun `reconcile does NOT touch an intent marker while the image still exists`() {
300:    fun `reconcile does NOT touch markers when delete-confirmed is present`() {
316:    fun `reconcile is a no-op when there is nothing to reconcile`() {
333:    fun `GATE - post-burn directory is byte-for-byte identical to a never-used directory`() {
358:    fun `GATE - post-burn state matches a fresh install that never created a vault`() {
380:    fun `obliterationComplete is FALSE while a dek or temp survives, even with vault-bin gone`() {
402:    fun `obliterationComplete is TRUE after a real burn`() {
414:    fun `completeInterruptedBurn finishes a wipe crashed between the keys-first unlinks`() {
429:    fun `completeInterruptedBurn does NOT touch a healthy vault`() {
439:    fun `completeInterruptedBurn is a no-op when the image is already gone`() {
454:    fun `completeInterruptedBurn cannot fire on an interrupted fresh create`() {
474:    fun `completeInterruptedBurn defers to D2c when delete-confirmed is present`() {
499:    fun `slot 0 is unarmed after create - burn is unreachable until Unit S arms it`() {
536:    fun `primaryImageProvenAbsent is tristate - indeterminate stat is not absence`() {
== PostBurnRouteTest.kt
35:    fun `only a proven-complete obliteration presents as onboarding`() {
52:    fun `failed burn never presents as onboarding even when vault bin is already gone`() {
72:    fun `reported success without proven absence is still a lock screen`() {
85:    fun `proven absence without reported success is still a lock screen`() {
103:    fun `a confirmed server delete outbids a successful burn`() {
117:    fun `a confirmed server delete outbids a failed burn`() {
134:    fun `full truth table`() {
162:    fun `onboarding is reachable from exactly one input combination`() {
== SweepOrphanedResidueTest.kt
80:    fun `row 1 - sweeps a stray dek with no image`() {
94:    fun `row 2 - sweeps a stray dek temp`() {
107:    fun `row 3 - sweeps a surviving bin temp holding a complete image`() {
129:    fun `row 4 - refuses while a live vault image is present`() {
148:    fun `row 6 - refuses while a delete is in flight over a live image`() {
175:    fun `row 6b - sweeps residue left by a burn while a delete-intent was outstanding`() {
203:    fun `row 7 - refuses while a delete-confirmed marker is present`() {
224:    fun `rows 5 and 8 - refuses when the base directory cannot be stat'd`() {
248:    fun `row 5 - an unstattable image must not cost a live vault its DEK`() {
268:    fun `row 9 - is a silent no-op on an already-clean directory`() {
285:    fun `a non-durable dirSync fails the sweep rather than claiming a clean directory`() {
300:    fun `is idempotent across repeated cold starts`() {
322:    fun `converts a not-provably-clean directory into a provably clean one`() {
== UnlockControllerTest.kt
74:    fun `unlock builds and publishes once, a second unlock is a no-op`() {
84:    fun `afterPublish runs once, after the session is published`() {
91:    fun `lock stops the session, cancels its scope, then publishes null`() {
107:    fun `lock with no live session is a no-op`() {
115:    fun `unlock, lock, unlock builds a fresh session on a fresh scope`() {
129:    fun `each build reads the CURRENT external state, not a construction-time capture`() {
151:    fun `lockIf tears down only the expected session`() {
171:    fun `unlock is refused while a terminal wipe is in progress and works after`() {
182:    fun `lock waits for the cancelled session scope to drain`() {
210:    fun `the drain wait is bounded — a stuck coroutine cannot hang lock`() {
238:    fun `an unlock in progress serializes a concurrent lock`() {
275:    fun `tryBeginTerminalWipe grants the claim to exactly one caller`() {
287:    fun `a refused claimant must not be able to release the winner's gate`() {
307:    fun `the claim is reusable after release, so a retried burn can proceed`() {
315:    fun `concurrent claimants - exactly one wins under real thread contention`() {
Test coverage is broad at the store and pure-function level. Notable coverage observations:

  1. BurnApplyOnceTest pins a stand-in Claimer, not `AppContainer.tryApplyBurnCompletion`. The shipped function is not referenced by any test. The header claims "Stand-in with the identical CAS-loop semantics" — true today, but nothing keeps them in sync. Standing instruction 5: coverage judged at the consumption site — the consumption site (MainActivity observer) is untested and the primitive itself is tested only by proxy. LOW.

  2. The observer's claim-then-`withContext` window: a composition cancelled after claiming consumes the completion without delivering. Consequence bounded (replacement composition re-derives from disk truth; boot decision covers routing). The lost item is only the UNIFORM_FAILURE paint / vaultExists=true forcing in the partial-burn-residue case... wait, let me re-check the residue case: failed burn with image absent but dek surviving (obliterateLocked threw at step 3 or 4 after unlinks). Composition C (after rotation during observer's withContext): seeds vaultExists=hasVault()=false; route seeds Splash; bootDone already true; splash decision: present=false, provenAbsent=obliterationComplete()=false (dek survives) → bootRoute → else → LOCKED. Route Locked rendered with vaultExists=false → LockScreen still renders passphrase field (doesn't take vaultExists), biometric off. Unlock attempts: attemptPassphrase → open() → MissingImage → Rejected → UNIFORM_FAILURE. Deniable and honest. Next cold start: sweep removes dek. OK — bounded. LOW.

  Actually wait — one more scenario for the claim-then-cancel: successful burn; observer claims; cancelled mid-withContext; new composition: hasVault false → vaultExists false; boot decision: provenAbsent true → ONBOARDING. Correct. Fine.

  Also the CANCEL can only occur on composition disposal (rotation) or if burnCompletion changes (new burn) — a new burn can't start that fast. OK.

  3. The generation RMW race in signalBurnCompleted: non-atomic read-then-write. Requires two burns in one process with a coroutine preempted between endTerminalWipe and signalBurnCompleted for the full duration of a second duress entry+burn. Practically unreachable; and slot 0 unarmed in production anyway. LOW/INFO. Also both-completions-same-generation: the StateFlow would emit two BurnCompletion(1, x) values; if x differs, observer re-fires but tryApply(1) fails → second swallowed. Fix: dedicated AtomicInteger for generation, or publish before releasing the gate. INFO given production unreachability; but it's cheap to fix and this is the unit whose whole lesson is "process-scoped counters must be atomic". I'll call it LOW.

  4. Item B verification: single applier — confirmed: only one `LaunchedEffect(burnCompletion)` consumer; dispatcher publishes only. No other writes of burn outcome to UI. The apply-once guard can't be defeated by rotation (process-scoped) or cancellation (guard is claimed on live composition... except the claim-then-cancel window above). "Can a completion be consumed but never delivered?" — YES, in the narrow window above, with bounded consequences. That's the honest answer.

  5. Item D: writers enumeration:
     - create(): DEK-first → {dek, no bin} (row 1), {dek.tmp} (row 2), {dek, bin.tmp} (row 3)
     - attemptUnlockOrAdd create path: reuses dek; writes bin.tmp over live bin → bin present (row 4)
     - writeSealedPayload: bin present (row 4)
     - obliterateLocked (burn): keys-first → {bin, no dek} (completeInterruptedBurn, not sweep) → after both unlinks {nothing} or residue covered; markers cleared last
     - destroy(): confirmed marker first → row 7
     - retireLegacyImage: bin then dek → {dek, no bin} (row 1b — added this round)
     - sweep itself: deletes residue; its own interruption → still residue states (idempotent)
     Any others? `deleteLeftoverTmp` in open() — deleter. `markDeleteIntent/markServerDeleteConfirmed` write markers only. So all writers covered: rows 1,1b,2,3,4,6,6b,7,8,9. Row 5 is stat fault. What about `{dek.tmp, dek, no bin}` — subsumed under row 1/2 (sweep deletes both). What about `{bin.tmp only}` — row 3 variant (dek absent): sweep deletes tmp. Covered by gate logic (any image-bearing file present). The table lists representative rows; gate logic is: bin absent + confirmed absent + any of {dek, dek.tmp, bin.tmp} present → sweep. Complete.
     
     One more possible writer: `wipeLegacyPrefs` / cache — not image-bearing. OK. Table complete as of this round.

  6. Item F: all claimed fixes landed (verified: tryApplyBurnCompletion called at MainActivity.kt:846; session collector corrected at 949; defaults removed; IGNORE_STALE deleted; dispatcher UI write removed; row 1b added; tests removed/added). Test count matches (529, 0 failures, 3 skipped I2P).

  7. Check remaining: docs/SECURITY_MODEL.md diff — quick skim to make sure claims match code. And check BurnObliterateTest line 499 "slot 0 is unarmed after create" — verify it actually asserts no passphrase maps to slot 0. Also check BootReconcileOwnerTest tests the real runBootReconcile (yes, extracted top-level function). And BurnAppLocalStateTest — tests real functions? SettingsRepository(prefs) seam — yes host test. MessagingNotifications.clearAllForWipe — Android NotificationManager; host JVM test? It uses context.getSystemService — probably Robolectric. Fine.

  Let me quickly check the slot-0 test and a couple of headers for the "test headers naming mutations they provably could not catch" pattern. Also skim SECURITY_MODEL diff.diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
index 6310c12..eb348bb 100644
--- a/docs/SECURITY_MODEL.md
+++ b/docs/SECURITY_MODEL.md
@@ -412,9 +412,11 @@ the others.
 > creates an empty vault); fail-closed while a delete is pending; **a successful create carries an
 > accepted disk-persistence timing residual** (it is not wall-clock identical to an unlock); and
 > biometric binds to **one vault at a time on a first-enable-wins basis** (never repointed while it
-> exists — not a fixed "everyday-vault-only" property). **Not yet shipped:** per-vault destruction (whole-image account delete only) and the
-> Pucker Burn setup/wipe UX — do not rely on those. See the "Implementation status" note at the
-> end of this section and [`docs/VAULT_ARCHITECTURE.md`](VAULT_ARCHITECTURE.md).
+> exists — not a fixed "everyday-vault-only" property). **Not yet shipped:** per-vault destruction
+> (whole-image account delete only) and the Pucker Burn credential **setup** UX — do not rely on
+> those. The burn **wipe mechanism** is built, but slot 0 is unarmed, so the burn cannot be
+> triggered by anyone yet. See the "Implementation status" note at the end of this section and
+> [`docs/VAULT_ARCHITECTURE.md`](VAULT_ARCHITECTURE.md).
 
 Two or more completely separate encrypted vaults sit behind different passphrases — up to **three
 live vaults** in the Android pool (`SLOT_COUNT = 4`: slots 1–3 are the vault pool; **slot 0 is
@@ -561,11 +563,117 @@ while a delete is pending, self-verifying seal), the silent **triple-entry** rou
 (the single wrap is never repointed). An Android user can therefore create and reveal a second
 vault, and plausible deniability is a **usable** guarantee here, within the limits above. **What
 is NOT built yet:** per-vault destruction (whole-image account delete only — there is no
-single-slot destroy primitive) and the **Pucker Burn** setup/wipe UX (slot 0 is reserved and the
-store is burn-*aware*, but the credential is not yet user-settable and the wipe is a fail-closed
-stub). Those, plus the full dual-slot destruction design, remain a **locked design** in
+single-slot destroy primitive) and the **Pucker Burn credential setup UX**. The burn **wipe
+mechanism** is built (see below), but **slot 0 is still unarmed and the burn is therefore
+unreachable** — no passphrase can match it, so nothing can trigger the wipe until the setup UX
+ships. Those, plus the full dual-slot destruction design, remain a **locked design** in
 [`docs/VAULT_ARCHITECTURE.md`](VAULT_ARCHITECTURE.md) §3.4, landing as their own adversarially-
-reviewed PRs. **Do not describe per-vault destruction or a working Pucker Burn as shipped.**
+reviewed PRs. **Do not describe per-vault destruction, or a user-triggerable Pucker Burn, as
+shipped.**
+
+#### Pucker Burn — what the wipe mechanism does and does not guarantee
+
+The duress wipe destroys **local state only**. It never contacts the relay: a duress scenario may
+be offline, and a relay-side deletion would emit a server event time-correlated with the wipe. The
+honest claim is **"this device can no longer recover the accounts"** — *not* "the relay has no
+record they existed." Relay accounts, public keys, queued ciphertext, and account-creation records
+survive; contacts may keep sending to identities whose keys are now unrecoverable.
+
+What the burn destroys: the whole vault image (`vault.bin`), the DEK envelope (`vault.dek`) and any
+interrupted-write temps, the in-RAM DEK, the biometric wrap and its Keystore aliases, every device
+setting (including `onboarding_done`), the orphaned legacy prefs, the boot-diagnostics log, the
+notification channel this app created plus any posted notification, and the **plaintext attachment
+cache** (`cameracapture`, `dropshare` — the only unencrypted user content the app writes to disk).
+The DEK is unlinked **before** the image, so a crash mid-wipe leaves ciphertext without its key —
+cryptographic erasure — never the reverse.
+
+These two guarantees have deliberately **different strengths**, and the difference is disclosed
+rather than blurred:
+
+- **Image destruction is fail-closed and mandatory.** The burn reports success only when the image,
+  the DEK envelope, and both interrupted-write temps are *proven* absent (a tristate re-stat: present
+  **or indeterminate** both count as failure). A surviving temp is treated as a surviving vault,
+  because a temp stages a complete encrypted image. A burn that does not fully take presents exactly
+  like a mistyped passphrase and can be retried.
+- **Every non-image cleanup is best-effort, and none of them is a guarantee.** That covers the device
+  settings, the biometric wrap and its Keystore aliases, the legacy prefs, the boot-diagnostics log,
+  the notification channel — and the plaintext attachment cache. Each is attempted, and a failure in
+  any of them is deliberately *tolerated* so it can neither mask nor pre-empt the image destruction's
+  success/failure signal. The consequence, stated plainly: **a burn can complete — keys genuinely
+  destroyed — while one of these app-local artifacts survives.** The cache in particular is retried
+  immediately after the wipe and again on every vault-less cold start, but if a staged file cannot be
+  deleted or the cache cannot even be listed, plaintext staged for sending may survive a burn.
+  Refusing to destroy the keys because one photo is locked would leave everything readable under
+  duress, which is strictly worse — so the keys always die and the residual is disclosed here rather
+  than claimed away. **The only hard, verified guarantee is the destruction of the vault image, its
+  DEK, and both temps.**
+
+A burn interrupted between the two unlinks (image present, DEK gone) is already cryptographically
+dead; the app completes that wipe on next start, so an interrupted burn does not leave a permanently
+unreadable-but-present vault.
+
+The mirror state — `{vault.bin absent, vault.dek or vault.bin.tmp present}` — is swept on the next
+cold start. It had no recovery at all before 0.9.2: `completeInterruptedBurn()` requires the image to
+be *present* and `reconcileOrphanedBurnMarkers()` requires every image-bearing file to be proven
+absent, so neither healed it, and boot routing keyed on `vault.bin` alone would show ordinary
+onboarding while `vault.bin.tmp` — which stages a **complete** outer image — still held a recoverable
+vault. Two changes close it:
+
+- **A cold-start orphan sweep.** Before any routing decision reads disk state, an orphaned
+  `vault.dek` / `vault.bin.tmp` / `vault.dek.tmp` is deleted, proven gone by re-stat, and made
+  durable. It runs *only* when the image is **proven** absent (`Files.notExists`, so an unstattable
+  image refuses) **and** neither delete marker is present or indeterminate — so it can never touch a
+  live vault's key or state that an in-flight account deletion owns.
+  Deliberately **no** durable "a burn happened" marker was added: such a marker would itself be
+  prior-use evidence, which is what this feature exists to avoid. The sweep needs none, because its
+  correctness does not depend on telling an interrupted *burn* from an interrupted *create* — those
+  are byte-identical on disk (`create()` writes the DEK first), and under both readings the orphan is
+  unreachable data that is correct to delete.
+- **Onboarding requires proven absence.** Every path that can present the fresh-install screen now
+  requires the whole directory to be provably clean, not merely "no `vault.bin`". If the sweep cannot
+  complete — an I/O fault, a refused gate — the lock screen is held instead of claiming a wipe that
+  did not happen.
+
+A failed burn's lock screen is also uniform: an *absent* image now answers a passphrase with the same
+wrong-passphrase failure and the same backoff as any other miss, rather than "the stored image may be
+damaged", which both misdescribed the state and signalled that something had been there. A
+*present-but-unreadable* image keeps that honest error — it is real device state worth reporting.
+
+Honest limits, stated as precisely as the c
apability:
+
+- **It protects the DATA, not the FACT that data existed.** The post-burn app presents ordinary
+  first-run onboarding, with no "wiped" screen — but a coercer watching the screen sees the reset
+  and knows something was destroyed. Burn does not, and cannot, hide that a wipe occurred.
+- **"Indistinguishable from a fresh install" is an APP-LOCAL claim only.** Package install/update
+  time, UsageStats, battery/network stats, notification *history*, media the user exported, and
+  filesystem/NAND remnants are outside the app sandbox and survive. A forensic examiner can still
+  see that this app was installed and used.
+- **Cryptographic erasure, not media sanitization.** Unlinking a file does not erase it from
+  wear-levelled flash. The defensible property is that the DEK is destroyed, so surviving blocks are
+  ciphertext indistinguishable from the random filler the image format already writes.
+- **Arming is single-snapshot indistinguishable only.** (Applies once the setup UX ships.) A
+  before/after forensic or backup comparison can reveal that slot 0 changed.
+- **It defends "unlock this phone", not "seized and imaged".** An adversary who images the device
+  before compelling a passphrase analyses the copy; the burn wipes only the live device.
+- **A burn consumes the credential.** (Applies once the setup UX ships.) After a burn, slot 0 is
+  filler again and the feature is unarmed until re-armed.
+- **Auto-Backup cannot resurrect a burned vault**: `allowBackup=false`, `fullBackupContent=false`,
+  and every domain is excluded in `res/xml/data_extraction_rules.xml`, so no pre-burn copy exists.
+- **Wipe timing is observable.** The passphrase sweep itself is timing-uniform across every outcome,
+  but the destruction that follows a burn match is wall-clock visible — a stopwatch can distinguish
+  a burn from a wrong password *after* the uniform crypto phase. Accepted residual.
+- **Two test-coverage gaps, stated rather than implied.** First, the device-settings clear is verified
+  at the `SharedPreferences` seam, not through the production `EncryptedSharedPreferences` path,
+  because that path requires the `AndroidKeyStore` provider which no host JVM (Robolectric included)
+  implements. The clear-and-reload logic is proven; that the *encrypted file on a real device* is
+  rewritten by it is not covered by an automated test. Second, the **post-burn UI reconciliation** is
+  only partly covered. The *route decision* — which state presents as a fresh install, which finishes
+  an interrupted account delete, and which stays on the lock screen — is a pure function and is
+  exhaustively unit-tested, including the case that matters most: a burn that failed with `vault.bin`
+  already unlinked must present as a lock screen, never as onboarding. What is **not** covered is the
+  Compose wiring around it — this project has no Compose UI or instrumentation test infrastructure, so
+  "rotate the device during a burn and confirm the recreated screen shows the right thing" has no
+  automated equivalent. The decision is proven; its delivery to the screen is inspection-verified.
 
 Two invariants from that architecture are restated here because they are permanent
 security properties, not implementation details:
The SECURITY_MODEL doc is honest and matches the code. One doc claim to double check: "neither delete marker is present or indeterminate" for the sweep — but the code deliberately has NO gate on delete-intent (only the confirmed marker). The doc says "and neither delete marker is present or indeterminate" — hmm. Actually re-read: "It runs *only* when the image is **proven** absent (`Files.notExists`, so an unstattable image refuses) **and** neither delete marker is present or indeterminate". The code gates only on serverDeletedFile (confirmed), NOT on intent — that was the round-1 Grok correction (row 6b). So the doc sentence is WRONG about the intent marker: the sweep runs even when a delete-intent is present (row 6b). This is exactly the "comments have been wrong repeatedly" class — a doc/code mismatch. Severity: LOW (documentation, behavior correct; the code comment at 1417-1435 explains the no-intent-gate decision at length). Actually wait, let me re-read the doc sentence once more: "It runs *only* when the image is proven absent ... and neither delete marker is present or indeterminate — so it can never touch a live vault's key or state that an in-flight account deletion owns." Yes — this asserts a gate on the intent marker that does not exist. The code refuses only when the confirmed marker is present/indeterminate. A delete-intent present with residue IS swept (row 6b, tested by `row 6b - sweeps residue left by a burn while a delete-intent was outstanding`). So doc contradicts code and the unit's own test. LOW (doc), worth reporting because this unit's history shows wrong comments read as coverage.

  Also, the same doc paragraph's justification "so it can never touch ... state that an in-flight account deletion owns" — the design rationale stands via the confirmed marker, but the stated mechanism is wrong.

  Now item H (testability). Let me assess concretely:

  Remaining untested lifecycle behavior:
  1. The observer LaunchedEffect (claim → IO reads → postBurnRoute → apply). What's testable:
     - `AppContainer.tryApplyBurnCompletion` — pure JVM; directly testable on host if extracted or via Robolectric AppContainer... AppContainer constructor takes Application and does heavy init (httpClient, transportResolver...). Robolectric is declared — could construct AppContainer under Robolectric? It builds CertificatePinning client etc. Maybe feasible. But simpler: tryApplyBurnCompletion touches no Android state — could be extracted like completeTerminalWipe/runBootReconcile were. Currently tested only via a copied stand-in — the seam EXISTS (the function is public on AppContainer) but instantiation cost is what pushed the test to a stand-in. Fix: extract the claimer to a top-level class (like Claimer) and have AppContainer delegate — then the test tests the real code. HOST-JVM reachable with a small extraction.
     - The claim→deliver sequence ("claimed by applier, not worker") and the consumed-but-never-delivered window: needs Compose coroutine environment; with kotlinx-coroutines-test + a factored "observer body" suspend function taking (container-fake, state-writer), the routing/apply logic could be host-tested. The Compose LaunchedEffect wiring itself (keying, cancellation on dispose) genuinely needs Compose UI testing (compose-ui-test-junit4, not declared) or Robolectric+Compose (still needs compose test artifacts).
  2. Rotation-mid-burn reconciliation (process-scoped observer fires on recreated composition): the non-Compose core — "a completion published before any composition exists is applied by the first composition that collects" — is StateFlow behavior, host-testable with kotlinx-coroutines-test if the observer body is extracted. The Compose wiring genuinely needs Compose UI test.
  3. Boot sequencing (Splash waits for bootReconciled; re-check after suspend): runBootReconcile contract is host-tested (BootReconcileOwnerTest). The composition wiring (splashFinished && bootDone gate) needs Compose.
  4. Session-collector logout arm with full input set: logic extractable (it calls bootRoute, already pure); the collector wiring needs Compose.
  5. tryBeginTerminalWipe exclusivity: host-tested (UnlockControllerTest). attemptPassphrase single-flight: UnlockController-level parts host-tested; the router wiring is in AppContainer — Robolectric seam maybe.

  So for H: host-JVM/Robolectric reachable: the real tryApplyBurnCompletion (small extraction or Robolectric), the observer's decision-and-apply body (extraction of a suspend fun), signalBurnCompleted generation monotonicity (same seam), boot reconciliation (already done), clearCacheDir (done), SettingsRepository clear (done via prefs seam). Genuinely Compose-only: the LaunchedEffect keying/re-firing behavior, disposal-cancels-claim delivery window, veil/screen rendering states (lock veil over onboarding), Crossfade routing — i.e., "what the user sees after rotation".

  Now, also double-check one thing about the observer: `container.burnCompletion.collectAsState()` at line 829 — this collects at composition; when a NEW composition is created after a burn, burnCompletion is non-null → LaunchedEffect(burnCompletion) fires → applies if not yet claimed. Good.

  One more possible sibling-call-site check (item A): are there other `when` over enums related to this unit where a new arm could be swallowed? The session collector's `when (bootRoute(...))` at 950-962 — exhaustive, no else. Splash decision `when (decided.route)` 743-752 — exhaustive. Boot re-derive `when (decided)` 796-802 — exhaustive (3 arms). Observer `when (postBurnRoute(...))` 851-883 — exhaustive. PassphraseOutcome when at 1113-1137 — exhaustive over sealed class with Rejected, Retry combined. UnlockOrAdd when in attemptPassphrase 565-591 — exhaustive. ResidueSweepResult consumers: publish uses `result == SWEPT_NOT_DURABLE` — if a 4th enum value were added, it would be treated as "no hold" — hmm! `publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)`: any new result value would silently read as "no hold" → fail-OPEN for the hold. The sibling-call-site class: a new enum arm swallowed by an equality check. If someone adds e.g. SWEPT_PARTIAL, hold=false → onboarding over residue. That's a latent fragility of the same shape the unit keeps hitting. Is it a defect today? No — three values, all handled: NO_MUTATION → false, SWEPT_DURABLE → false, SWEPT_NOT_DURABLE → true. Correct for all three. It's a "wrong by future edit" note, INFO. The fail-safe would be `result != SWEPT_DURABLE && result != NO_MUTATION` or a when. Actually better: hold should be true unless proven clean: `result !in setOf(NO_MUTATION, SWEPT_DURABLE)`. Current code: hold = (result == SWEPT_NOT_DURABLE). A future new value → hold=false. Given the enum is internal and small, INFO note.

  Also check ResidueSweepResult definition at 152. Let me look quickly. Also the BurnResult class and how dispatcher uses plaintextCacheCleared — burnVault returns BurnResult(plaintextCacheCleared) but the dispatcher does `runCatching { container.burnVault() }.isSuccess` and discards the BurnResult! `completed && container.burnObliterationComplete()`. So the cache-clear verdict is computed and discarded at the dispatch site... but disclosed in SECURITY_MODEL as best-effort, and retried at boot. Is the returned value consumed anywhere? grep BurnResult. It might be used in tests. If production discards it, that's fine per the disclosed design (best-effort), but why return it? For testability. OK.

  Let me check ResidueSweepResult enum and BurnResult usage quickly.

• Two small verifications left: the `ResidueSweepResult` consumers and `BurnResult` usage.

• BurnResult.plaintextCacheCleared — comment at ZitroneApp.kt:150 says "DELIBERATELY NOT SURFACED AT RUNTIME". Let me quickly read that comment block (139-160) to verify the claim — it says deliberately not surfaced, with boot retry as the healer. That matches the design. Let me read it to be sure.That checks out — deliberate and documented.

  One more item from my earlier analysis to verify before writing the report: the dispatcher's burn path — after `onBurn` launches the worker, `unlocking` stays true until the observer applies. In the LOCKED arm, `unlocking=false` is set. On DELETE_INCOMPLETE arm too. ONBOARDING arm too. Good.

  But wait — one subtle path: the observer at line 834 `if (container.session.value != null) return@LaunchedEffect` — returns WITHOUT claiming. If a completion exists and a session is live, no claim consumed. Later when session ends (lock), a recomposition... actually LaunchedEffect(burnCompletion) doesn't re-fire when session changes — the effect's key is burnCompletion, which didn't change. The session check reads `container.session.value` once when the effect runs. Scenario: burn completes (no session, can't have session during burn)... could a session exist when a completion is published? onBurn is only reachable from the lock screen, which requires no session. But — the session collector publishes null asynchronously; could a session still be non-null at the moment the observer fires after a burn? Path: user unlocked (session live) → user locks (session null published) → lock screen → burn. lockIf/lock() publishes null synchronously under the monitor before the lock screen shows? The forced logout path: coordinator.onForcedLogout → unlockController.lockIf(live) → publishes null synchronously. The manual lock path presumably similar. So by the time a passphrase can be entered at the lock screen, session is null. OK.

  But consider: burn completion published; observer fires in composition; session.value != null — return, never claims. Then session goes null (user locks). The effect does NOT re-run (key unchanged) — completion never applied! When could session be non-null while an unapplied completion exists? Requires burn → then a session built (re-onboard create → publishSession) → without the observer ever running with session==null. Sequence: burn completes; observer fires immediately (session null → claims, applies). For the completion to be unclaimed when a session exists, the observer must not have run between publish and session creation — the publish and the onboarding create both happen on the main thread; the observer's LaunchedEffect is scheduled on the composition... Actually the completion is published from a container.scope coroutine (Dispatchers.Default). The StateFlow update is instant; collectAsState re-composes; LaunchedEffect launches. Meanwhile the user is still on the Locked screen with the spinner (unlocking=true, since dispatcher no longer writes UI). The user CANNOT create a vault until the observer applies ONBOARDING — there's no UI path to onboarding except through the observer (route stays Locked). So session creation can't precede the apply. What about DELETE_INCOMPLETE arm? Route.DeleteIncomplete → onRetryDestroy → onboarding → create → session. The observer claimed already. OK — so the session-check ordering is sound: the only way to get a session after a burn is through a route the observer itself set after claiming. Good — no stranded-completion path there.

  Wait, actually one more: what if a burn completion is published while route is ChatList (session live)? Burn requires lock screen; lock screen requires no session. Structurally impossible. OK.

  Now, one more possible issue: the observer claims BEFORE the IO read, and the arms write composition state. If between claim and write the user... no user interaction possible mid-effect. Fine.

  Let me also double check `signalBurnCompleted` ordering vs endTerminalWipe — flagged LOW. And check BootReconcileOwnerTest tests the real runBootReconcile (it imports the real function — yes, tests call runBootReconcile). Good.

  Item G — "all boot healers as ONE system": startBootReconcile runs sweep, then completeInterruptedBurn, then reconcileOrphanedBurnMarkers, publishes hold, then afterPublish cache retry. What about a state {bin present, dek absent} + residue hold? completeInterruptedBurn finishes the wipe. Routing: bootRoute with present=hasVault()... after healers, hasVault false. OK. Note that bootRoute is computed AFTER bootReconciled (Splash waits). Good.

  One question: completeInterruptedBurn fires when dek proven absent and bin present — but what if {bin present, dek absent} was caused by a genuine MEDIA LOSS of the dek on a live vault? Then it destroys the remaining image — but that state is unrecoverable anyway (kdoc says this). OK.

  Another check (item G concurrency): sweep runs at cold start before auth, on process scope; any concurrent create()? create requires UI interaction post-boot; boot reconcile runs during Splash; create() can't run before Splash finishes. And both take imageLock anyway. OK.

  Item I: File.exists() verify inside obliterateLocked (step 2, lines 1168-1172). Pre-existing, inherited from destroy(). Direction: fail-closed on "file present"; fail-open on stat fault (exists()==false on I/O error → passes verify while file survives). Backstopped by: (a) dirSync requirement, (b) the dispatcher's additional `burnObliterationComplete()` tristate re-stat, (c) for destroy(): DeleteIncomplete retry flow. So the residual is only: a stat fault on all four files AND durable dirSync → burn reported success while a file survives → then observer's postBurnRoute checks imageBearingProvenAbsent (tristate) → provenAbsent false → LOCKED arm → does NOT present onboarding. So even in that case the UI stays fail-closed, but `burnReportedSuccess` would be true... and `obliterated=true` published. Then observer: postBurnRoute(confirmed?, true, provenAbsent=false) → LOCKED. Fine. And next cold start the sweep cleans it. Agree with out-of-scope INFO.

  Now also double check the MainActivity diff region 780-805 (boot re-derive) that I saw: `when (decided)` handles DELETE_INCOMPLETE (set route), ONBOARDING (only moves stale Locked forward), LOCKED (Unit). Hmm — this boot re-derive effect runs after bootReconciled in EVERY composition (line 755-804). Note it does NOT re-check route after the withContext suspend! The Splash decision at 739 does `if (route != Route.Splash) return@LaunchedEffect` after the suspend; the re-derive at 795-802 does not re-check. It guards implicitly: DELETE_INCOMPLETE → `if (route != Route.DeleteIncomplete) route = ...` — sets route unconditionally (except same). ONBOARDING → only if route == Locked. Could this stomp? Scenario: re-derive runs while route == ChatList (session live)? Guard at 763: `if (container.session.value == null)` — read BEFORE the withContext suspend. If a session is published during the IO suspend, route moves to ChatList (session collector), then re-derive resumes: decided could be DELETE_INCOMPLETE → sets route = DeleteIncomplete, stomping ChatList with session live! Hmm. When can session become non-null between the check at 763 and the apply at 796? The re-derive effect fires once per composition after bootReconciled. If session.value == null at check, user is at some lock/onboarding screen. For a session to appear during the (brief) IO window, the user must complete an unlock/create within milliseconds — implausible but not structurally impossible. Also DELETE_INCOMPLETE requires serverDeleteConfirmed — a confirmed marker, image present. If user simultaneously unlocks... the unlock gate: a confirmed marker present — boot routed to DeleteIncomplete, never the lock screen, so no unlock UI available. Actually if serverDeleteConfirmed() is true, Splash routes to DeleteIncomplete, so there's no path to a live session. If confirmed is false, decided ∈ {ONBOARDING → only moves Locked forward, LOCKED → Unit}. Can ONBOARDING stomp a live session? route==Locked required; with a live session route would be ChatList. So the only stomp requires route==Locked while a session is live — session collector sets route=ChatList when session non-null and !unlocked... The re-check-after-suspend pattern was applied at Splash (739) but NOT here (796). Round-3 Grok re-check comment at 734-739 says "the re-check is the structural guarantee rather than an argument about who else exists" — for Splash. The sibling effect (this re-derive) was left without the re-check. Is that the sibling-call-site class (item A)? It's the same shape: guard-then-suspend-then-apply. Practical reachability: needs session published during the IO window while route==Locked/DeleteIncomplete... DELETE_INCOMPLETE arm: `if (route != Route.DeleteIncomplete) route = Route.DeleteIncomplete` — this would stomp ChatList. For confirmed to be true while a live session exists: confirmed marker written only after a server delete confirmed, which happens in the account-delete flow over a live session! Sequence: user unlocked (session live, route ChatList) → rotates device → new composition → re-derive effect: bootReconciled already true → first { it } returns immediately → session.value != null → skip. OK guarded. Hmm, so to hit it: composition created while session null, user unlocks... user must be ON a screen that allows unlock (route Locked requires image present, no confirmed marker; DeleteIncomplete has no unlock). During the IO window the user can't complete an Argon2 unlock in ms. Structurally: session publication requires either onUnlockSuccess (composition callback on main thread — could interleave! onUnlockSuccess sets unlocked=true, route=ChatList, and publishSession happened off-main before). The re-derive's withContext and the unlock callback both run on main. The interleave: re-derive checks session==null (main), suspends to IO; concurrently the passphrase attempt (running on Default) finishes, publishes session; session collector (main) sets route=ChatList; re-derive resumes on main, applies decision computed from... serverDeleteConfirmed read during IO — if false, decided ∈ {LOCKED→Unit, ONBOARDING→ only if route==Locked (now ChatList, skip), DELETE_INCOMPLETE→ stomp}. DELETE_INCOMPLETE requires confirmed true — meaning a confirmed marker existed on disk while the user unlocked. Could a user unlock with a confirmed marker present? Boot routes confirmed → DeleteIncomplete (no unlock field). But mid-session a delete-confirmed could be written: the account-delete flow writes the confirmed marker over the live session, then destroys. During that window (delete in flight), route is ChatList; user rotates → new composition: session.value != null → seed route=ChatList; re-derive skips (session non-null). Then delete completes → session null → collector arm fires (line 921) → routes via bootRoute with full inputs → DeleteIncomplete. OK.

  So the missing re-check at 796 requires: session==null at check; a session published during the IO window; AND decided==DELETE_INCOMPLETE (confirmed marker present). A confirmed marker on disk + user unlocking a vault: boot would have routed DeleteIncomplete, no lock screen — unreachable to unlock. UNLESS the marker appeared after boot: marker writes happen only in the delete flow, which requires a live session — but session was null at the check. So confirmed can't become true during the window without a session having existed before. Contradiction → unreachable. And ONBOARDING arm is guarded by route==Locked, and with a live session route would have been moved to ChatList by the session collector... but ORDER: the session collector and the re-derive both on main; if re-derive resumes BEFORE the collector processes the new session, route is still Locked (composition-local unlocked=false seeded false), session live. Then ONBOARDING arm: route==Locked → route=Onboarding. Then collector fires: session live, !unlocked → route=ChatList. Final state correct — collector wins because it reacts to the session flow. Hmm, but could re-derive run AFTER the collector set ChatList? Then route==ChatList, ONBOARDING arm skips. Either order lands ChatList eventually... wait if re-derive sets Onboarding after collector set ChatList — no: collector sets ChatList only when it processes non-null session; it will process it eventually (flow). If re-derive sets Onboarding after, collector already ran and won't re-run (session value unchanged). Order: session published at T. Collector processes at T+ε1 (main). Re-derive resumes at T+ε2. If ε2 > ε1: route already ChatList → ONBOARDING arm skips (route!=Locked). If ε2 < ε1: re-derive sets route=Onboarding (was Locked); then collector at ε1 sets ChatList. Either way final = ChatList. DELETE_INCOMPLETE arm is the only unconditional one, shown unreachable above. OK — so no live defect; the asymmetry with Splash's re-check is a robustness note only. INFO. Given item A asks to hunt siblings left behind — this is one worth naming as INFO (not a defect today, proven unreachable by the marker-requires-session argument).

  Also — the re-derive at 763 checks `container.session.value == null` — and the session collector exists as the session's own authority. OK.

  Now item H and the doc mismatch. Also verify the claim in 800d7ab message: "Tests: 529 total (+5 net)". Previous commit claimed 524? e1b0f2e or 5ef5f77? Not critical; observed 529.

  Also verify no other safety-decision defaults (C): checked — the grep over the whole main package found only unrelated defaults. Additionally, `runBootReconcile(afterPublish: () -> Unit = {})` — a behavioral default: omitting afterPublish means no cache retry; not a safety input (routing hold is unaffected). `drainTimeoutMs: Long = 2_000` in UnlockController — not safety decision input. OK.

  Also for B: "no path where the dispatcher still mutates UI state" — verified: onBurn's UI writes are only `lockError = UNIFORM_FAILURE; unlocking = false` in the tryBeginTerminalWipe refusal branch (lines 1030-1032). That's not the burn outcome, it's a busy-reject — uniform failure paint, fine. And the `onBurn` initial claim failure branch. The dispatcher proper (worker) publishes only. Good.

  "Composition created after the burn" — collectAsState reads current value → effect fires → claims (if unclaimed) → applies. Good. "Rotation" — process-scoped counter survives. "Cancellation" — the claim-then-cancel window: completion consumed, never delivered (bounded; re-derived by next composition from disk truth; only the failure paint is lost, and in the partial-residue case vaultExists stays false while route=Locked — LockScreen doesn't take vaultExists, renders fine, unlock attempt → MissingImage → uniform failure; next cold start sweeps residue). So: YES, a completion can be consumed but never delivered — narrow, bounded, fail-closed. Report as LOW with the precise mechanics.

  Actually wait — should double check one detail in that scenario: observer claim happens BEFORE withContext. If cancelled during withContext, the `when` never runs, so vaultExists stays at its seeded value. For a failed burn where image fully absent but dek survives (obliterateLocked threw at dirSync step 3 — unlinks done): seeded vaultExists = hasVault() = false. Route: Splash → bootRoute(present=false, provenAbsent=false) → LOCKED. So Locked + vaultExists=false. LockScreen renders (no vaultExists param). biometricUnlockAvailable=false. Passphrase attempt → MissingImage → Rejected → UNIFORM_FAILURE. Honest, deniable. The residue is swept next boot. Bounded — LOW confirmed. Also note the window is microscopic (two tristate stat calls on IO dispatcher). And it requires rotation at exactly that instant.

  Now — is there any path where the consumed-undelivered completion leaves state WORSE? If burn SUCCEEDED and observer claimed then cancelled: next composition: vaultExists=false, Splash → bootRoute → provenAbsent=true → ONBOARDING. Correct. No.

  One more check on generation monotonicity across... lastAppliedBurnGeneration starts 0, generations start 1. Good.

  Let me also spot check BootRouteTest full truth table covers legacy — 16 combos (2^4 with confirmed,present,hold,proven) + legacy separately. And `the table must cover every combination` 16 — with legacyImage fixed false. Plus legacy-specific tests. OK.

  I think I've also covered: item A sibling check for IGNORE_STALE — enum arm deleted entirely, so no consumer can swallow it; the remaining consumers use exhaustive `when`. The one remaining if/else chain of the same shape: `publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)` equality — INFO as noted.

  Also double check `PassphraseOutcome` — new arms? It's a sealed interface; the when at 1113 handles Unlocked, Created, Burn, LegacyImage, ImageUnreadable, Rejected+Retry — exhaustive. OK.

  Also verify MainActivity's `onBurn` label usage — `Unit` at the end of the launched block (line 1098) — the lambda's last expression; harmless.

  Now let me also glance at BootReconcileOwnerTest cancellation test & the `afterPublish` placement (round-5 message said afterPublish cancellation strands nothing since it's only cache retry — verified: afterPublish is separate withContext after publish, outside try/finally; if cancelled, publish already happened. Good).

  One more: item F — "Superseded IGNORE_STALE tests removed rather than left asserting a deleted arm" — verified in PostBurnRouteTest diff (4 tests removed). Note the diff leaves trailing blank lines (cosmetic).

  Also the round-5 message claims "15 mutations across this delta, all caught" — I can't verify mutation testing claims; the BurnApplyOnce mutation claim applies to the stand-in only. I've flagged that.

  Now assemble the report. Findings:

  1. LOW — BurnApplyOnceTest pins a stand-in copy, not the shipped `AppContainer.tryApplyBurnCompletion` (test file line 36-49; real fn ZitroneApp.kt:852). No test references the real function; a mutation to the real CAS (e.g., `<` vs `<=`, non-atomic RMW) fails no test. Commit message's "mutation-verified" claim was verified against the duplicate. Fix: extract the claimer to a top-level/internal class (mirroring runBootReconcile/completeTerminalWipe extractions) and have AppContainer delegate, then test the real one; or construct via Robolectric.

  2. LOW — consumed-but-never-delivered window: claim at MainActivity.kt:846 precedes a suspending `withContext` (848); disposal in between consumes the completion without applying. Consequences bounded (replacement composition re-derives routing from disk truth; only the UNIFORM_FAILURE paint / vaultExists=true forcing is lost; LockScreen renders without vaultExists). Fix option: claim after the IO read, immediately before the `when` writes (still on the live composition, narrower window but not eliminated); or accept + document. Given fail-closed landing, LOW.

  3. LOW — non-atomic generation RMW in `signalBurnCompleted` (ZitroneApp.kt:267-270) ordered after `endTerminalWipe` (MainActivity.kt:1072-1084): two sequential burns can interleave so both read the same predecessor generation; the later-published (stale) outcome wins the StateFlow, and equal generations make the apply-once guard swallow the correct one. Requires two duress entries within one process and a pathological preemption; mechanism unreachable in production today. Fix: dedicated `AtomicInteger` for generation (or publish before releasing the gate — but then the observer could apply ONBOARDING while gate still up? The gate blocks unlock(); a create from onboarding → publishSession → refused... Actually ordering publish before gate release could strand: user creates vault while gate up → unlock refused → onRefused wipe → create appears to fail. Current order (release then publish) is deliberate; just make the counter atomic.)

  4. LOW (documentation) — docs/SECURITY_MODEL.md sweep description says the sweep runs only when "neither delete marker is present or indeterminate" — false for the intent marker: there is deliberately NO intent gate (VaultImageStore.kt:1417-1435, row 6b, and the test `row 6b - sweeps residue left by a burn while a delete-intent was outstanding`). The doc contradicts the code and the unit's own test suite.

  5. INFO — `publish(result == SWEPT_NOT_DURABLE)` (ZitroneApp.kt:1412 / runBootReconcile): a future fourth ResidueSweepResult value would default to hold=false (fail-open for the fresh-install presentation). Same swallowed-new-arm shape as the round-5 sibling defect. Prefer `result !in setOf(NO_MUTATION, SWEPT_DURABLE)`.

  6. INFO — boot re-derive effect (MainActivity.kt:755-804) lacks the re-check-after-suspend its Splash sibling got at line 739; proven unreachable today (DELETE_INCOMPLETE requires a confirmed marker, which requires a prior live session, contradicting the session==null entry guard; the other arms are route-guarded), but it is the same guard-suspend-apply shape one call site over.

  7. INFO (I) — agree: File.exists() verify inside obliterateLocked is pre-existing, backstopped by the dirSync requirement, the dispatcher's tristate obliterationComplete re-stat, and the observer's tristate provenAbsent input. Even a wrong "success" there cannot present onboarding.

  Verdicts A–I and READY TO MERGE. Severity check: all LOW/INFO, no CRITICAL/HIGH/MEDIUM. Ready to merge: yes, with the two LOWs worth fixing (test seam + doc line) but none blocking.

  Let me also double-check the test for BootRouteTest header claims quickly? I've seen names; fine.

  Also confirm the claim "526 passed" → 529 - 3 skipped = 526 passed. Matches.

  Write the report now. Keep it structured but reasonably concise.

• All verification is complete. Writing the final report.

• # Round 6 — Final Independent Review, Unit W (Pucker Burn / residue sweep)

  Commit reviewed: `800d7ab` on top of the cumulative `main...HEAD` unit. I read every file named in the brief in full at the relevant regions, re-ran the test suite myself, and re-derived every claim in the round-5 commit message against source. Findings first, then verdicts A–I.

  ## Findings

  ### F1 — LOW — The apply-once test pins a stand-in copy, not the shipped function
  `apps/android/app/src/test/java/com/zitrone/app/BurnApplyOnceTest.kt:36-49` defines a private `Claimer` class that *duplicates* the CAS loop, and every assertion runs against it. Nothing in the test tree references `AppContainer.tryApplyBurnCompletion` (`apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:852`) — I grepped. A mutation to the real function (drop the CAS to a read-then-write, flip `<=` to `<`) fails **no** test. The commit message's "Mutation-verified: a non-atomic read-then-write guard fails the race test and only that test" is true only of the duplicate. This unit has already twice shipped "comments/tests that read as coverage while providing none"; this is the same shape one turn further out, and standing instruction 5 says coverage is judged at the consumption site. The shipped code itself is correct — I verified the CAS loop by reading — so this is a coverage defect, not a behavior defect.
  *Fix:* extract the claimer as a top-level/internal class (the same extraction pattern this unit already used for `runBootReconcile` and `completeTerminalWipe`) and have `AppContainer` delegate to it, then point the test at the real code. Host-JVM reachable, no new dependencies.

  ### F2 — LOW — A completion can be consumed but never delivered (narrow, fail-closed)
  `MainActivity.kt:846` claims the generation **before** the suspending `withContext(Dispatchers.IO)` at line 848. A composition disposed in that window (rotation landing mid-stat) consumes the completion; every later composition sees `tryApplyBurnCompletion(gen) == false` and applies nothing. I traced each outcome of that loss:
  - Successful burn: replacement composition seeds `vaultExists = hasVault() = false`, Splash decision → `bootRoute(present=false, provenAbsent=true)` → Onboarding. Correct end state.
  - Failed burn, image present: → Locked. Only the `UNIFORM_FAILURE` paint is lost.
  - Failed burn, image gone but dek/temp survives: `hasVault()=false` seeds `vaultExists=false`, `bootRoute` → `else` → Locked. `LockScreen` (MainActivity.kt:1517) takes no `vaultExists`; the passphrase field renders, an attempt hits `MissingImage` → uniform `Rejected` (ZitroneApp.kt:540-552), and the next cold start sweeps the residue. Honest and deniable.

  So the invariant "a burn that did not fully take never presents as one that did" survives; what is lost is one cosmetic error paint, and the answer to the brief's question "can a completion be consumed but never delivered?" is **yes**, in this window. The kdoc ("claimed … immediately before a LIVE composition writes") overstates the guarantee by one suspension point.
  *Fix:* move the claim to after the IO read, immediately before the `when` (still on the live composition — narrows the window to non-suspending code but cannot eliminate it), or leave as-is and correct the kdoc. Either is acceptable; the fail direction is safe.

  ### F3 — LOW — `signalBurnCompleted` generation is a non-atomic read-modify-write, published after the gate release
  `ZitroneApp.kt:267-270`: `(burnCompletion.value?.generation ?: 0) + 1` then assignment — not a CAS. It is called in the `finally` **after** `endTerminalWipe()` (MainActivity.kt:1072-1084), so a second burn can begin before the first burn's signal lands. Two sequential burns interleaved so both read the same predecessor generation publish the same generation twice; `MutableStateFlow` conflates equal values and the apply-once guard then swallows the second emit — a stale *outcome* can win over the correct one. Reaching it requires the first worker to be preempted between gate release and publish for the entire duration of a second duress entry + wipe — practically impossible, and the trigger is unarmed in production regardless. But this is the unit whose own lesson is "process-scoped counters must be atomic."
  *Fix:* back generation with its own `AtomicInteger` (`incrementAndGet()`), keeping the release-then-publish order, which is deliberate (a create must not run into a still-raised gate).

  ### F4 — LOW — SECURITY_MODEL.md describes a sweep gate that does not exist
  `docs/SECURITY_MODEL.md` (new sweep paragraph) states the sweep runs only when "neither delete marker is present or indeterminate". The code has **deliberately no gate on `vault.delete-intent`** — only on the confirmed marker (`VaultImageStore.kt:1417-1437`, with a long comment explaining why, and row 6b plus the test `row 6b - sweeps residue left by a burn while a delete-intent was outstanding` proving the intent-present state *is* swept). The doc contradicts both the code and the suite. Behavior is correct; the document is wrong in exactly the way this unit keeps getting bitten by.
  *Fix:* reword to "the image is proven absent and no *confirmed* delete marker is present or indeterminate", and mention the intent case is swept then reconciled.

  ### F5 — INFO — Same swallowed-new-arm shape at the sweep-verdict consumer
  `ZitroneApp.kt:1412` (`runBootReconcile`): `publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)`. A future fourth `ResidueSweepResult` value would silently read as "no hold" — fail-open for the fresh-install presentation. This is precisely the `if/else`-swallows-new-enum shape the round-5 fix eliminated at the dispatcher, surviving at a sibling. Today all three values are handled correctly (`NO_MUTATION`/`SWEPT_DURABLE` → false, `SWEPT_NOT_DURABLE` → true), so it is latent, not live. Prefer `result !in setOf(NO_MUTATION, SWEPT_DURABLE)`.

  ### F6 — INFO — Boot re-derive lacks the re-check-after-suspend its Splash sibling has
  The Splash decision re-checks `route != Route.Splash` after its `withContext` (MainActivity.kt:739, added round 3); the boot re-derive effect (MainActivity.kt:755-804) has the same guard-suspend-apply shape with no re-check. I could not construct a reachable bad outcome: the unconditional arm (`DELETE_INCOMPLETE`) requires a confirmed marker, which can only be written over a live session — contradicting the effect's `session.value == null` entry guard; the other two arms are route-guarded, and the session collector lands the final state correctly under either interleaving. Unreachable today; worth the same structural guard for symmetry, not a merge blocker.

  ## Verdicts on the binding items

  **A — Sibling call sites: PASS.** `PostBurnRoute.IGNORE_STALE` is *deleted from the enum* (ZitroneApp.kt:1484), so no consumer can swallow it; the dispatcher no longer consumes the verdict at all (MainActivity.kt:1086-1097 publishes only). I enumerated every `when` over `PostBurnRoute`, `BootRoute`, `PassphraseOutcome`, and `UnlockOrAdd` — all exhaustive, no `else`. The two survivors of the *shape* are F5 and F6 above, both non-live.

  **B — Single applier: PASS with one bounded caveat.** Exactly one writer of burn outcome to UI exists: the process-scoped observer at MainActivity.kt:830-884. The dispatcher's only remaining UI writes are the busy-reject on a lost terminal-wipe claim (lines 1030-1032), which is not the outcome. The guard cannot be defeated by rotation (process-scoped counter) or by a composition created after the burn (`collectAsState` reads the current value and fires). The caveat is F2: cancellation between claim and apply can consume without delivering — bounded and fail-closed. The session check at line 834 cannot strand a completion: after a burn the only routes that exist are the ones the observer itself sets, so no session can be built before the claim.

  **C — Default parameters: PASS.** `bootRoute` (ZitroneApp.kt:1459-1465) and `postBurnRoute` (1509-1513) take all inputs explicitly; all four call sites pass the full set (MainActivity.kt:722-731, 784-790, 852-856, 951-957). I grepped the whole main package for defaulted parameters: remaining defaults are unrelated helpers (`burnAll(notifyPeer)`, `buildClient(torEnabled)`, `pad(block)`, UI modifiers) plus `runBootReconcile`'s `afterPublish`/`ioDispatcher` and `UnlockController.drainTimeoutMs` — none are safety-decision inputs.

  **D — Table completeness: PASS.** I enumerated every writer of dek/temp-without-proven-bin states independently: interrupted `create()` (DEK-first barrier, lines 537-557) → rows 1/2/3; interrupted `obliterateLocked` keys-first → `{bin, no dek}`, which is `completeInterruptedBurn`'s signature, not the sweep's; `destroy()` → confirmed marker first → row 7; `retireLegacyImage` (bin then dek, lines 944-945) → row 1b, added this round and correctly; `attemptUnlockOrAdd`'s create-path and `writeSealedPayload` write `vault.bin.tmp` only over a live image → row 4; burn-during-outstanding-intent → row 6b. No missing writer found. Rows 5/8 (indeterminate stats) fail closed via `Files.notExists`.

  **E — Test suite, independently run: PASS.** First invocation returned `UP-TO-DATE` (no tests executed); I deleted `app/build/test-results` and re-ran, forcing real execution: **61 suites, 529 tests, 0 failures, 0 errors, 3 skipped**, the 3 skips being `I2pLiveIntegrationTest` exactly as claimed. Matches the commit message's "529 total, 526 passed, 3 skipped (I2P)".

  **F — Fixes actually landed: PASS.** Every claim in `800d7ab`'s message verified in source: `tryApplyBurnCompletion` defined *and called* (MainActivity.kt:846); session collector corrected to `vaultExists = imagePresent && !legacyNow` (line 949) with `bootRoute` receiving `imagePresent` raw; both defaults removed; `IGNORE_STALE` gone from the enum and its four superseded tests removed from `PostBurnRouteTest.kt`; dispatcher UI-write block deleted; table row 1b added; `BurnApplyOnceTest.kt` added (124 lines, present). No silently-unapplied fix this round.

  **G — Cumulative re-verification: PASS.** `destroy()` under keys-first unlinks is safe because the confirmed marker is durable before any unlink (lines 1105-1118). Marker retire is strictly after verify + required dirSync (obliterateLocked steps 2-4). The three boot healers run as one ordered system (sweep → `completeInterruptedBurn` → `reconcileOrphanedBurnMarkers`, ZitroneApp.kt:885-898) with the fail-closed default verdict and `finally` publication. Slot 0 is unarmed (random filler; `BurnObliterateTest.kt:499` proves no passphrase reaches `Burn`); the wipe fires only from lock-screen dispatch (`PassphraseOutcome.Burn → onBurn`, MainActivity.kt:1115). The terminal-wipe gate is exclusive (`tryBeginTerminalWipe`, UnlockController.kt:182-186, tested under 16-thread contention). A partial burn cannot present as success: dispatcher requires `burnVault()` non-throw **and** tristate `burnObliterationComplete()`; the observer independently requires `imageBearingProvenAbsent`; boot requires proven absence plus no sweep hold.

  **H — Testability assessment.** Host-JVM / Robolectric-reachable as the code stands or with a small extraction: the real `tryApplyBurnCompletion` (F1's extraction — pure JVM, zero Android deps); `signalBurnCompleted` generation monotonicity (same seam); the observer's *decision body* if extracted as a suspend function taking fakes — inputs, `postBurnRoute` call, and the state writes — since `kotlinx-coroutines-test` can drive the `withContext` and cancellation deterministically, including F2's claim-then-dispose window; the full boot-reconcile contract (already done via `runBootReconcile`). Genuinely Compose-only: the `LaunchedEffect` keying/re-firing semantics themselves, disposal-cancels-the-effect as an *observed* behavior, the lock-veil-over-onboarding rendering states, and "rotate mid-burn, confirm the recreated screen" end-to-end — these need `compose-ui-test-junit4` (not declared) or instrumentation; no honest host seam exists for them.

  **I — `File.exists()` inside `obliterateLocked`: AGREE, out of scope.** Pre-existing, inherited from `destroy()`, fail-open only on a stat fault of all four files *plus* a durable dirSync — and even then the dispatcher's tristate `burnObliterationComplete()` and the observer's tristate `imageBearingProvenAbsent` keep the UI from presenting onboarding.

  ## Prior-round conclusions

  I found no accepted conclusion from earlier rounds that is wrong. The round-4 "generation added but never used" observation was accurate and is now properly consumed.

  ## Verdict

  **READY TO MERGE.** No CRITICAL, HIGH, or MEDIUM findings. Four LOWs (test-stand-in coverage F1, the consumed-undelivered window F2, the non-atomic generation counter F3, the doc/code mismatch F4) and two INFOs, none of which violate the unit's central invariant — post-burn ≡ fresh install, and a burn that did not fully take never presents that way. F1 and F4 are cheap, mechanical fixes worth taking before or immediately after merge; F2/F3 are unreachable in production while slot 0 is unarmed and should be fixed before Unit S arms the credential.

To resume this session: kimi -r session_210f7a5c-d0d2-426b-bef1-a238e5214fbd
