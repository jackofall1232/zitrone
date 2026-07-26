• Let me start by understanding the task. I'm reviewing a round-2 fix delta in an Android app (Zitrone). I need to:

  1. Look at git diff main...HEAD and git show 54a41bf
  2. Verify fixes A0, A-I
  3. Run the test suite
  4. Report findings

  Let me begin by exploring the git state and the diff. This is a big review task. I should delegate some of the reading to subagents? The instructions say delegating keeps context down. But careful verification is key — I need to read the diff myself for the core review. Let me start with git commands directly.

• I'll start by examining the git state and the changes under review.54a41bf Unit W-A round-2 fixes — enforce the dispatcher, cover the derivation and gate 2
0d348b4 Unit W-A round-1 fixes — one legacy authority, gate 2 covered, one derivation
96a2608 l00prite: W-A extracted; round-1 findings are all extraction defects
a98677f 0.9.2 Unit W-A — cold-start orphan residue sweep + fail-closed boot routing
---
HEAD detached at refs/heads/feat/0.9.2-unit-wa-residue-sweep
nothing to commit, working tree clean
---
 .../src/main/java/com/zitrone/app/MainActivity.kt  | 125 ++++---
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    | 236 ++++++++++++
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 190 +++++++++-
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt | 308 ++++++++++++++++
 .../src/test/java/com/zitrone/app/BootRouteTest.kt | 256 +++++++++++++
 .../java/com/zitrone/app/DeriveBootDecisionTest.kt | 157 ++++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    | 407 +++++++++++++++++++++
 docs/SECURITY_MODEL.md                             |  36 ++
 l00prite/.l00prite/ledger.md                       |  54 +++
 9 files changed, 1725 insertions(+), 44 deletions(-)


commit 54a41bf086e17a231c4298159230a32207a85ad4
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 12:53:57 2026 +0000

    Unit W-A round-2 fixes — enforce the dispatcher, cover the derivation and gate 2
    
    Four lenses on the round-1 fix delta. EVERY finding traces to a round-1 fix —
    fix deltas remain the densest source of defects in this unit, exactly as the
    loop's own rule says.
    
    MEDIUM (Gemini HIGH / Kimi MEDIUM / Grok LOW, three lenses) — the session
    collector ran `isLegacyImage()` (a ~1 MiB read + Keystore unwrap + AEAD decrypt)
    on the composition's main dispatcher, on EVERY lock/sign-out. Introduced by the
    extraction (main's collector did two fast stats) and preserved by the round-1
    fix: collapsing three consumers onto one derivation, I wrapped two in
    withContext(IO) and left the third bare. THIRD time in this unit I have applied a
    fix to three sites and got two right.
    
    The kdoc I wrote said "call OFF the main thread" — a requirement documented and
    then violated by one of its own three callers. So the fix is structural, not a
    third wrapper: `deriveBootDecisionFromDisk` is now `suspend` and moves itself to
    IO. All three callers are byte-identical bare calls and the dispatcher is no
    longer a decision any caller can make wrongly. Same principle as removing the
    default parameters so an omitted input became a compile error.
    
    MEDIUM (Codex) — `deriveBootDecision`, the authoritative layer introduced in
    round 1 to REMOVE duplication, had no coverage of its own. A corruption between
    the disk reads and `bootRoute` left every truth-table test green. Extract a
    decision so it CAN be tested, then don't test it — the unit's recurring shape one
    level up. New DeriveBootDecisionTest pins probe suppression on confirmed-delete
    and on absent-image, fail-closed on probe throw, precedence through the wrapper,
    and that every input reaches the decision unaltered.
    
    MEDIUM in effect (Grok, filed INFO) — gate 2's tristate was UNCOVERED. Verified
    by mutation rather than accepting the label: downgrading
    `!Files.notExists(serverDeletedFile)` to `File.exists()` — a real fail-open on an
    indeterminate stat — broke NOTHING. Gate 1 had an ELOOP test proving that exact
    property; gate 2 never got one because I wrote the idea for one gate and did not
    carry it to its sibling. Row 8 added with a self-referential symlink on the
    confirmed marker, asserting the DEK SURVIVES; the mutation now fails only that
    test.
    
    LOW (Codex) — the WRITER/READER table's intent-gate proof was FALSE. It claimed
    an intent can never accompany an absent image legitimately;
    `createVaultAndPublish` calls `retireLegacyImage()` (which unlinks the image)
    BEFORE `create()` (which clears markers), so a crash between them produces
    exactly that state. Row 6c records it, and says plainly that the previous proof
    was wrong and what actually makes the sweep safe there: retirement has already
    destroyed the only openable image.
    
    LOW (Kimi) — `a retry after a cancelled run does not re-sweep` performed NO
    cancellation. Removing the `rest` hook during extraction deleted its
    `throw CancellationException` argument, silently reducing it to a duplicate of
    its neighbour. Now genuinely cancels the first run and additionally asserts the
    cancelled claimant still released its waiters fail-closed.
    
    INFO (Kimi) — dead `sweepOrphanedVaultResidue` wrapper removed; an orphaned
    "Delete an incomplete-write temp" doc line that my insertion had stranded above a
    predicate that deletes nothing is reattached to `deleteLeftoverTmp`.
    INFO (Grok) — `obliterationComplete` renamed to `imageBearingProvenAbsent`:
    leftover wipe vocabulary in a unit that contains no wipe invites a reader to
    assume a mechanism that is not here.
    
    Three of these — the row-7 deletion, the dropped @Test, and now the deleted
    cancellation argument — are the same personal
 failure: a mechanical edit that
    silently removes coverage while leaving the file compiling and green.
    
    Tests: 483 total (+7), 0 failures, 480 passed, 3 skipped (I2P, pre-existing).
    No version bump.

 .../src/main/java/com/zitrone/app/MainActivity.kt  |   4 +-
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    |  30 ++--
 .../zitrone/app/crypto/vault/VaultImageStore.kt    |  43 ++++--
 .../java/com/zitrone/app/BootReconcileOwnerTest.kt |  17 ++-
 .../java/com/zitrone/app/DeriveBootDecisionTest.kt | 157 +++++++++++++++++++++
 .../com/zitrone/app/SweepOrphanedResidueTest.kt    |  41 +++++-
 6 files changed, 261 insertions(+), 31 deletions(-)
===
commit 54a41bf086e17a231c4298159230a32207a85ad4
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Sat Jul 25 12:53:57 2026 +0000

    Unit W-A round-2 fixes — enforce the dispatcher, cover the derivation and gate 2
    
    Four lenses on the round-1 fix delta. EVERY finding traces to a round-1 fix —
    fix deltas remain the densest source of defects in this unit, exactly as the
    loop's own rule says.
    
    MEDIUM (Gemini HIGH / Kimi MEDIUM / Grok LOW, three lenses) — the session
    collector ran `isLegacyImage()` (a ~1 MiB read + Keystore unwrap + AEAD decrypt)
    on the composition's main dispatcher, on EVERY lock/sign-out. Introduced by the
    extraction (main's collector did two fast stats) and preserved by the round-1
    fix: collapsing three consumers onto one derivation, I wrapped two in
    withContext(IO) and left the third bare. THIRD time in this unit I have applied a
    fix to three sites and got two right.
    
    The kdoc I wrote said "call OFF the main thread" — a requirement documented and
    then violated by one of its own three callers. So the fix is structural, not a
    third wrapper: `deriveBootDecisionFromDisk` is now `suspend` and moves itself to
    IO. All three callers are byte-identical bare calls and the dispatcher is no
    longer a decision any caller can make wrongly. Same principle as removing the
    default parameters so an omitted input became a compile error.
    
    MEDIUM (Codex) — `deriveBootDecision`, the authoritative layer introduced in
    round 1 to REMOVE duplication, had no coverage of its own. A corruption between
    the disk reads and `bootRoute` left every truth-table test green. Extract a
    decision so it CAN be tested, then don't test it — the unit's recurring shape one
    level up. New DeriveBootDecisionTest pins probe suppression on confirmed-delete
    and on absent-image, fail-closed on probe throw, precedence through the wrapper,
    and that every input reaches the decision unaltered.
    
    MEDIUM in effect (Grok, filed INFO) — gate 2's tristate was UNCOVERED. Verified
    by mutation rather than accepting the label: downgrading
    `!Files.notExists(serverDeletedFile)` to `File.exists()` — a real fail-open on an
    indeterminate stat — broke NOTHING. Gate 1 had an ELOOP test proving that exact
    property; gate 2 never got one because I wrote the idea for one gate and did not
    carry it to its sibling. Row 8 added with a self-referential symlink on the
    confirmed marker, asserting the DEK SURVIVES; the mutation now fails only that
    test.
    
    LOW (Codex) — the WRITER/READER table's intent-gate proof was FALSE. It claimed
    an intent can never accompany an absent image legitimately;
    `createVaultAndPublish` calls `retireLegacyImage()` (which unlinks the image)
    BEFORE `create()` (which clears markers), so a crash between them produces
    exactly that state. Row 6c records it, and says plainly that the previous proof
    was wrong and what actually makes the sweep safe there: retirement has already
    destroyed the only openable image.
    
    LOW (Kimi) — `a retry after a cancelled run does not re-sweep` performed NO
    cancellation. Removing the `rest` hook during extraction deleted its
    `throw CancellationException` argument, silently reducing it to a duplicate of
    its neighbour. Now genuinely cancels the first run and additionally asserts the
    cancelled claimant still released its waiters fail-closed.
    
    INFO (Kimi) — dead `sweepOrphanedVaultResidue` wrapper removed; an orphaned
    "Delete an incomplete-write temp" doc line that my insertion had stranded above a
    predicate that deletes nothing is reattached to `deleteLeftoverTmp`.
    INFO (Grok) — `obliterationComplete` renamed to `imageBearingProvenAbsent`:
    leftover wipe vocabulary in a unit that contains no wipe invites a reader to
    assume a mechanism that is not here.
    
    Three of these — the row-7 deletion, the dropped @Test, and now the deleted
    cancellation argument — are the same personal
 failure: a mechanical edit that
    silently removes coverage while leaving the file compiling and green.
    
    Tests: 483 total (+7), 0 failures, 480 passed, 3 skipped (I2P, pre-existing).
    No version bump.

diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 5548582..108f947 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -643,7 +643,7 @@ private fun ZitroneRoot(
     LaunchedEffect(splashFinished, bootDone) {
         if (!splashFinished || !bootDone) return@LaunchedEffect
         if (route != Route.Splash) return@LaunchedEffect
-        val decided = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
+        val decided = container.deriveBootDecisionFromDisk()
         // RE-CHECK AFTER THE SUSPEND: the guard above ran before `withContext`, and a decision taken
         // for a tree that has since left Splash must not be applied to it.
         if (route != Route.Splash) return@LaunchedEffect
@@ -664,7 +664,7 @@ private fun ZitroneRoot(
         // process-scoped result is available.
         container.bootReconciled.first { it }
         if (container.session.value == null) {
-            val snap = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
+            val snap = container.deriveBootDecisionFromDisk()
             // RE-CHECK AFTER THE SUSPEND (round-1 review, Kimi). The session was checked before
             // `withContext`; a session published while we were off-main must not then be pulled to
             // DeleteIncomplete by a decision taken for a tree that no longer has none. The Splash
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index cb6b87e..60ead70 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -232,22 +232,28 @@ class AppContainer(private val app: Application) {
      * `vault.dek` or `vault.bin.tmp` (which stages a COMPLETE outer image) reads as "no vault" and
      * would route ONBOARDING over recoverable ciphertext.
      */
-    fun vaultProvenAbsent(): Boolean = imageStore.obliterationComplete()
+    fun vaultProvenAbsent(): Boolean = imageStore.imageBearingProvenAbsent()
 
     /**
      * Read the four disk facts and produce ONE boot decision — the single derivation every routing
-     * consumer uses. Call OFF the main thread: the legacy probe reads and decrypts the outer layer.
+     * consumer uses.
+     *
+     * SUSPEND, and it moves itself to IO (round-2 review, Gemini). This was a plain function with a
+     * kdoc saying "call off the main thread", and one of its three callers — the session collector —
+     * called it bare on the composition dispatcher, running a ~1 MiB decrypt on the main thread. A
+     * requirement stated in a comment is a requirement that will eventually be violated by one call
+     * site; the dispatcher move belongs INSIDE, where no caller can get it wrong. Callers now simply
+     * `deriveBootDecisionFromDisk()`.
      */
-    internal fun deriveBootDecisionFromDisk(): BootDecision = deriveBootDecision(
-        serverDeleteConfirmed = serverDeleteConfirmed(),
-        imagePresent = hasVault(),
-        residueSweepHold = residueSweepHold.value,
-        vaultProvenAbsent = vaultProvenAbsent(),
-        isLegacyImage = { isLegacyImage() },
-    )
-
-    /** Cold-start orphan sweep — see [VaultImageStore.sweepOrphanedResidue]. */
-    fun sweepOrphanedVaultResidue(): ResidueSweepResult = imageStore.sweepOrphanedResidue()
+    internal suspend fun deriveBootDecisionFromDisk(): BootDecision = withContext(Dispatchers.IO) {
+        deriveBootDecision(
+            serverDeleteConfirmed = serverDeleteConfirmed(),
+            imagePresent = hasVault(),
+            residueSweepHold = residueSweepHold.value,
+            vaultProvenAbsent = vaultProvenAbsent(),
+            isLegacyImage = { isLegacyImage() },
+        )
+    }
 
     /**
      * PROCESS-scoped boot-reconciliation state.
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 5234224..17da060 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -1292,7 +1292,6 @@ class VaultImageStore internal constructor(
         return dirSync(target.parentFile)
     }
 
-    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
     /**
      * True ONLY when every image-bearing file is PROVEN absent — image, DEK envelope, and BOTH
      * interrupted-write temps. Fail-closed: present OR indeterminate yields false.
@@ -1309,12 +1308,14 @@ class VaultImageStore internal constructor(
             Files.notExists(leftoverTmp(dekFile).toPath())
 
     /**
-     * Public fail-closed proof that the vault directory holds nothing image-bearing. This is the ONLY
-     * predicate that may authorise a fresh-install presentation; `hasVault()` keys on `vault.bin`
-     * alone and would call a directory empty while a surviving DEK or temp still held a recoverable
-     * vault.
+     * Public fail-closed proof that the vault directory holds nothing image-bearing.
+     *
+     * Named for what it asserts, not for a wipe (round-2 review, Grok): this unit has no destructive
+     * wipe, and a name carrying that vocabulary invites a reader to assume a mechanism that is not
+     * here. `hasVault()` keys on `vault.bin` alone and would call a directory empty while a surviving
+     * DEK or temp still held a recoverable vault, which is why routing must not use it.
      */
-    fun obliterationComplete(): Boolean = imageLock.withLock { imageBearingFilesProvenAbsent() }
+    fun imageBearingProvenAbsent(): Boolean = imageLock.withLock { imageBearingFilesProvenAbsent() }
 
     /**
      * COLD-START ORPHAN SWEEP. Deletes an orphaned `vault.dek` / `vault.bin.tmp` / `vault.dek.tmp`
@@ -1377,12 +1378,31 @@ class VaultImageStore internal constructor(
      *  9  {nothing present}                      fresh install                 NO-OP (already proven
      *                                                                          clean).
      *
-     * There is deliberately NO gate on `vault.delete-intent`: [destroy] writes the CONFIRMED marker
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
      * durably BEFORE it unlinks anything, so every real D2c unlink already carries the confirmed
-     * marker and is caught by gate 2 — while an intent alone never accomp
anies an absent image in a
-     * legitimate D2c state (an intent is written while the image is still present, and [create]
-     * clears both markers durably before writing the DEK). An intent gate would protect nothing and
-     * could only strand residue.
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
      *
      * ── OTHER PROPERTIES ────────────────────────────────────────────────────────────────────────
      * Touches NO in-memory state (no [dek] wipe, no [canonical] drop, no [unregister]): gate 1 proves
@@ -1427,6 +1447,7 @@ class VaultImageStore internal constructor(
     private fun leftoverTmp(target: File): File =
         File(target.parentFile, "${target.name}$TMP_SUFFIX")
 
+    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
     private fun deleteLeftoverTmp(target: File) {
         leftoverTmp(target).delete()
     }
diff --git a/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt b/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
index 6569ca2..8699968 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/BootReconcileOwnerTest.kt
@@ -234,10 +234,18 @@ class BootReconcileOwnerTest {
         val io = StandardTestDispatcher(testScheduler)
         val h = Harness()
 
+        // The first run IS cancelled (round-2 review, Kimi). This test previously performed no
+        // cancellation at all — a `rest = { throw CancellationException(...) }` argument was removed
+        // during the extraction when the `rest` hook was dropped, silently reducing it to a duplicate
+        // of `a second start does not re-run the destructive sweep`. The point is that a CANCELLED
+        // claimant still holds the claim, so destructive work must not run again.
         runBootReconcile(
             scope = this,
             claim = h::claim,
-            sweep = { h.sweepRuns.incrementAndGet(); ResidueSweepResult.SWEPT_DURABLE },
+            sweep = {
+                h.sweepRuns.incrementAndGet()
+                throw CancellationException("recreation mid-reconcile")
+            },
             publish = h::publish,
             ioDispatcher = io,
         )
@@ -252,7 +260,12 @@ class BootReconcileOwnerTest {
         )
         advanceUntilIdle()
 
-        assertEquals("destructive boot work must never run twice", 1, h.sweepRuns.get())
+        assertEquals(
+            "the claim survives cancellation, so destructive boot work must never run twice",
+            1,
+            h.sweepRuns.get(),
+        )
+        assertTrue("and the cancelled run still released its waiters fail-closed", h.hold.value)
     }
 
     /** A healthy, durable boot must NOT hold — the hold has to be earned, not the default outcome. */
diff --git a/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt b/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt
new file mode 100644
index 0000000..0a59ad6
--- /dev/null
+++ b/apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt
@@ -0,0 +1,157 @@
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
+
+/**
+ * THE SINGLE BOOT DERIVATION (0.9.2 Unit W-A).
+ *
+ * WHY THIS SUITE EXISTS: round 1 found the five `bootRoute` inputs copy-pasted across all three
+ * routing consumers, and the fix collapsed them into one owner — [deriveBootDecision]. Round 2 then
+ * found that the new authoritative layer had NO coverage of its own: `BootRouteTest` pins the
+ * decision table, and nothing pinned the derivation that feeds it. A corruption between the disk
+ * reads and `bootRoute` would leave every truth-table test green.
+ *
+ * That is this unit's recurring shape one level up — extract a decision so it CAN be tested, then
+ * don't test it. The behaviour under test here is not "what does bootRoute decide" (that is
+ * `BootRouteTest`) but "are the right inputs assembled, and is the expensive probe correctly
+ * suppressed and fail-closed".
+ */
+class DeriveBootDecisionTest {
+
+    /**
+     * The legacy probe reads and decrypts ~1 MiB. It must not run when a confirmed delete already
+     * owns the state — that path routes to DeleteIncomplete regardless of what the probe would say.
+     *
+     * MUTATION UNIQUELY CAUGHT: dropping `!serverDeleteConfirmed` from the probe guard.
+     */
+    @Test
+    fun `a confirmed delete suppresses the legacy probe entirely`() {
+        var probed = false
+        val d = deriveBootDecision(
+            serverDeleteConfirmed = true,
+            imagePresent = true,
+            residueSweepHold = false,
+            vaultProvenAbsent = false,
+            isLegacyImage = { probed = true; true },
+        )
+        assertFalse("the probe must not run over a confirmed delete", probed)
+        assertFalse("and legacy must not be asserted", d.legacy)
+        assertEquals(BootRoute.DELETE_INCOMPLETE, d.route)
+    }
+
+    /**
+     * No image means nothing to probe — running a 1 MiB decrypt against an absent file is pure cost.
+     *
+     * MUTATION UNIQUELY CAUGHT: dropping `imagePresent` from the probe guard.
+     */
+    @Test
+    fun `an absent image suppresses the legacy probe entirely`() {
+        var probed = false
+        val d = deriveBootDecision(
+            serverDeleteConfirmed = false,
+            imagePresent = false,
+            residueSweepHold = false,
+            vaultProvenAbsent = true,
+            isLegacyImage = { probed = true; true },
+        )
+        assertFalse("the probe must not run with no image present", probed)
+        assertFalse(d.legacy)
+        assertEquals(BootRoute.ONBOARDING, d.route)
+    }
+
+    /**
+     * A probe that THROWS must fail closed to "not legacy" — never propagate, and never assert legacy
+     * on a failure. Asserting legacy would route a live vault to onboarding, where the create retires
+     * an image that was never proven legacy.
+     *
+     * MUTATION UNIQUELY CAUGHT: replacing the `runCatching{}.getOrDefault(false)` with `true`, or
+     * letting the throw escape.
+     */
+    @Test
+    fun `a failing legacy probe fails closed to not-legacy`() {
+        val d = deriveBootDecision(
+            serverDeleteConfirmed = false,
+            imagePresent = true,
+            residueSweepHold = false,
+            vaultProvenAbsent = false,
+            isLegacyImage = { error("simulated decrypt fault") },
+        )
+        assertFalse("a failed probe must never assert legacy", d.legacy)
+        assertEquals("and must route to the lock screen, not onboarding", BootRoute.LOCKED, d.route)
+    }
+
+    /** A genuine legacy image is detected and carried into both the decision and `present`/`legacy`. */
+    @Test
+    fun `a legacy image is detected and routed to onboarding`() {
+
        val d = deriveBootDecision(
+            serverDeleteConfirmed = false,
+            imagePresent = true,
+            residueSweepHold = false,
+            vaultProvenAbsent = false,
+            isLegacyImage = { true },
+        )
+        assertTrue(d.present)
+        assertTrue(d.legacy)
+        assertEquals(BootRoute.ONBOARDING, d.route)
+    }
+
+    /**
+     * THE POINT OF THE LAYER: every input must reach `bootRoute` unaltered. This pins the wiring, so a
+     * derivation that silently drops one — the round-1 defect, one level up — fails here even though
+     * BootRouteTest stays green.
+     *
+     * MUTATION UNIQUELY CAUGHT: passing a constant (e.g. `residueSweepHold = false`) instead of the
+     * argument.
+     */
+    @Test
+    fun `every input reaches the decision unaltered`() {
+        // The hold is the input most easily dropped: it is the only one not re-derivable from disk.
+        val held = deriveBootDecision(
+            serverDeleteConfirmed = false,
+            imagePresent = false,
+            residueSweepHold = true,
+            vaultProvenAbsent = true,
+            isLegacyImage = { false },
+        )
+        assertEquals(
+            "a non-durable sweep must withhold onboarding — if the hold is dropped this reads clean",
+            BootRoute.LOCKED,
+            held.route,
+        )
+
+        val notHeld = deriveBootDecision(
+            serverDeleteConfirmed = false,
+            imagePresent = false,
+            residueSweepHold = false,
+            vaultProvenAbsent = true,
+            isLegacyImage = { false },
+        )
+        assertEquals(BootRoute.ONBOARDING, notHeld.route)
+
+        // `present` is reported as observed, independent of the legacy verdict.
+        assertTrue(
+            deriveBootDecision(false, true, false, false, { false }).present,
+        )
+        )
+    }
+
+    /** Precedence is `bootRoute`'s, unchanged by the derivation: a confirmed delete outbids legacy. */
+    @Test
+    fun `confirmed outbids legacy through the derivation`() {
+        val d = deriveBootDecision(
+            serverDeleteConfirmed = true,
+            imagePresent = true,
+            residueSweepHold = false,
+            vaultProvenAbsent = false,
+            isLegacyImage = { true },
+        )
+        assertEquals(BootRoute.DELETE_INCOMPLETE, d.route)
+    }
+}
diff --git a/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt b/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
index d91fcdf..f22befc 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
@@ -120,7 +120,7 @@ class SweepOrphanedResidueTest {
 
         assertEquals(ResidueSweepResult.SWEPT_DURABLE, newStore(dir).sweepOrphanedResidue())
         assertFalse("a complete outer image must not survive as a temp", binTmp(dir).exists())
-        assertTrue("the directory must now be provably clean", newStore(dir).obliterationComplete())
+        assertTrue("the directory must now be provably clean", newStore(dir).imageBearingProvenAbsent())
     }
 
     // ──────────────────── rows 4-8: states another owner holds — REFUSE ────────────────────
@@ -187,7 +187,7 @@ class SweepOrphanedResidueTest {
         )
         assertFalse("the stranded complete image must be gone", binTmp(dir).exists())
         assertFalse("and the stray dek", dek(dir).exists())
-        assertTrue("the directory is now provably clean", newStore(dir).obliterationComplete())
+        assertTrue("the directory is now provably clean", newStore(dir).imageBearingProvenAbsent())
     }
 
     /**
@@ -214,6 +214,39 @@ class SweepOrphanedResidueTest {
         assertTrue("and the residue it owns must survive", dek(dir).exists())
     }
 
+    /**
+     * Row 8, THE LOAD-BEARING VERSION — gate 2's tristate, by CONSEQUENCE (round-2 review, Grok).
+     *
+     * Gate 1 had an ELOOP test proving an indeterminate IMAGE stat refuses; gate 2 had only a
+     * present-marker case and the admittedly-weak ENOTDIR one. Verified by mutation: downgrading gate
+     * 2 from `!Files.notExists(...)` to `File.exists()` broke NOTHING — so the confirmed marker's
+     * fail-closed reading was uncovered while the image's was covered. Symmetry gap, closed here.
+     *
+     * A self-referential symlink at `vault.delete-confirmed` yields ELOOP: `File.exists()` reads false
+     * (indistinguishable from absent — the fail-open) while `Files.notExists()` is ALSO false
+     * (correctly: not proven absent). The assertion is on the DAMAGE — the DEK of a directory whose
+     * deletion status cannot be determined must survive.
+     *
+     * MUTATION UNIQUELY CAUGHT: `!Files.notExists(serverDeletedFile)` → `serverDeletedFile.exists()`.
+     */
+    @Test
+    fun `row 8 - an unstattable confirmed marker must not cost the residue`() {
+        val dir = tmp.newFolder()
+        val marker = confirmed(dir).toPath()
+        java.nio.file.Files.createSymbolicLink(marker, marker.fileName)
+        dek(dir).writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 7 })
+
+        assertEquals(
+            "an indeterminate confirmed-marker stat must refuse — a pending deletion may own this",
+            ResidueSweepResult.NO_MUTATION,
+            newStore(dir).sweepOrphanedResidue(),
+        )
+        assertTrue(
+            "and MUST NOT have deleted the residue on the way to refusing",
+            dek(dir).exists(),
+        )
+    }
+
     /**
      * Row 5/8: an INDETERMINATE stat, constructed for real (ENOTDIR — the store's baseDir is a regular
      * file). Every gate uses `Files.notExists`, true ONLY on a proven absence, so a failing filesystem
@@ -331,12 +364,12 @@ class SweepOrphanedResidueTest {
 
         assertFalse(
   
          "precondition: residue means onboarding is NOT authorised",
-            newStore(dir).obliterationComplete(),
+            newStore(dir).imageBearingProvenAbsent(),
         )
         assertEquals(ResidueSweepResult.SWEPT_DURABLE, newStore(dir).sweepOrphanedResidue())
         assertTrue(
             "after the sweep, and only then, onboarding is authorised",
-            newStore(dir).obliterationComplete(),
+            newStore(dir).imageBearingProvenAbsent(),
         )
     }
 
• Now let me read the full current source of the key files. Let me read VaultImageStore.kt (relevant sections), ZitroneApp.kt, MainActivity.kt. These are large. Let me read the relevant parts. Also check burn-related absence (A, B, C), and run tests (H).

  Let me first read the full unit diff for the extraction commit (a98677f) to see the whole unit, but actually more efficient: read the current files. Let me start the test suite in background while reading source.

• I'll run the test suite in the background while reading the current source.  1273 apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
  1599 apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
  1535 apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
  4407 total


235:    fun vaultProvenAbsent(): Boolean = imageStore.imageBearingProvenAbsent()
246:     * `deriveBootDecisionFromDisk()`.
248:    internal suspend fun deriveBootDecisionFromDisk(): BootDecision = withContext(Dispatchers.IO) {
249:        deriveBootDecision(
250:            serverDeleteConfirmed = serverDeleteConfirmed(),
252:            residueSweepHold = residueSweepHold.value,
253:            vaultProvenAbsent = vaultProvenAbsent(),
254:            isLegacyImage = { isLegacyImage() },
261:     * [bootReconciled] gates the fresh-install presentation: no route may be derived from disk until
262:     * boot reconciliation has finished, because the sweep MUTATES what disk says. [residueSweepHold]
270:    val bootReconciled = MutableStateFlow(false)
271:    val residueSweepHold = MutableStateFlow(false)
275:    /** Start boot reconciliation ONCE PER PROCESS; later callers no-op and observe [bootReconciled]. */
277:        runBootReconcile(
280:            sweep = { imageStore.sweepOrphanedResidue() },
282:                residueSweepHold.value = hold
283:                bootReconciled.value = true
309:     * cheap Argon2id-free peek (see [VaultImageStore.isLegacyImage]); call off-main. Safety does NOT
313:    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
317:     * vault destroy is owed ([VaultImageStore.serverDeleteConfirmed]). The only valid route is
322:    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()
464:        if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()
1136:internal fun runBootReconcile(
1173: * `bootRoute` inputs themselves.
1175: * Round-1 review (Gemini): the derivation — including the ~1 MiB `isLegacyImage()` decrypt and its
1181: * MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer.
1183:internal fun deriveBootDecision(
1184:    serverDeleteConfirmed: Boolean,
1186:    residueSweepHold: Boolean,
1187:    vaultProvenAbsent: Boolean,
1188:    isLegacyImage: () -> Boolean,
1189:): BootDecision {
1192:    val legacy = if (imagePresent && !serverDeleteConfirmed) {
1193:        runCatching { isLegacyImage() }.getOrDefault(false)
1197:    return BootDecision(
1200:        route = bootRoute(
1201:            serverDeleteConfirmed = serverDeleteConfirmed,
1203:            residueSweepHold = residueSweepHold,
1204:            vaultProvenAbsent = vaultProvenAbsent,
1210:/** Where a composition must route out of Splash on a cold start — see [bootRoute]. */
1211:internal enum class BootRoute { DELETE_INCOMPLETE, LOCKED, ONBOARDING }
1217:internal data class BootDecision(
1220:    val route: BootRoute,
1236: *     unlinked, so [AppContainer.vaultProvenAbsent] says "clean" and would authorise a fresh install —
1245:internal fun bootRoute(
1246:    serverDeleteConfirmed: Boolean,
1248:    residueSweepHold: Boolean,
1249:    vaultProvenAbsent: Boolean,
1251:): BootRoute = when {
1252:    serverDeleteConfirmed -> BootRoute.DELETE_INCOMPLETE
1253:    legacyImage -> BootRoute.ONBOARDING
1254:    vaultImagePresent -> BootRoute.LOCKED
1255:    residueSweepHold -> BootRoute.LOCKED
1256:    vaultProvenAbsent -> BootRoute.ONBOARDING
1257:    else -> BootRoute.LOCKED
• Note line 1181: "MUST be called off the main thread" — but the plain function `deriveBootDecision` now... wait, the kdoc at 1175-1181 describes `deriveBootDecision` (the internal function taking inputs). It says "MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer." But now `deriveBootDecisionFromDisk` wraps itself in IO, so this kdoc still applies to `deriveBootDecision` since the probe is still invoked there. That's fine — the probe is a lambda passed in, and the wrapper calls from IO.

  Let me read the full sections: ZitroneApp.kt lines 200-330, 1100-1273.Now note something in `runBootReconcile`: `withContext(ioDispatcher) { afterPublish() }` at line 1166 — this runs AFTER the `try/finally`. If the coroutine was cancelled, `withContext` after the finally will immediately throw CancellationException (since coroutine is cancelled) — actually withContext on a cancelled coroutine throws immediately. So afterPublish won't run on cancellation, that's fine (non-routing hygiene). Good.

  Also note: the kdoc at line 1181 "MUST be called off the main thread" on `deriveBootDecision` — still correct since the probe executes there and callers call it from IO (the wrapper). But DeriveBootDecisionTest calls it directly on the main test thread... tests don't matter for the main-thread cost. OK.

  Wait — the `deriveBootDecisionFromDisk` wrapper passes `isLegacyImage = { isLegacyImage() }` — `isLegacyImage()` is the AppContainer method calling `imageStore.isLegacyImage()`. And `deriveBootDecision` runs it inside `runCatching`, fine.

  Now check MainActivity call sites. Three consumers:
  1. Splash decision LaunchedEffect (line ~643)
  2. Post-boot re-derive (line ~664)
  3. Session collector

  Let me grep MainActivity.

• Now MainActivity's three call sites and burn-related checks:639:    val bootDone by container.bootReconciled.collectAsState()
646:        val decided = container.deriveBootDecisionFromDisk()
652:            BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
653:            BootRoute.ONBOARDING -> Route.Onboarding
654:            BootRoute.LOCKED -> Route.Locked
662:        container.startBootReconcile()
665:        container.bootReconciled.first { it }
667:            val snap = container.deriveBootDecisionFromDisk()
675:                BootRoute.DELETE_INCOMPLETE ->
678:                BootRoute.ONBOARDING -> if (route == Route.Locked) route = Route.Onboarding
679:                BootRoute.LOCKED -> Unit
699:                !container.hasVault() && !container.serverDeleteConfirmed()
727:    // routing authority: it set Route.Onboarding on its own, without awaiting `bootReconciled`,
728:    // without the carried `residueSweepHold`, and without consulting `serverDeleteConfirmed()` — so
732:    // decision; see bootRoute's `legacyImage` arm, which orders it AFTER the confirmed marker and
783:                val snap = container.deriveBootDecisionFromDisk()
787:                    BootRoute.DELETE_INCOMPLETE -> Route.DeleteIncomplete
788:                    BootRoute.ONBOARDING -> Route.Onboarding
789:                    BootRoute.LOCKED -> Route.Locked
837:    val onBurn: () -> Unit = {
856:                        PassphraseOutcome.Burn -> onBurn()
1104:                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
1397:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
exit: 1


• Good — no burn-related residue (A, B clean so far). Now read MainActivity around lines 630-870 and the onBurn stub, and check `git show main:` for MainActivity onBurn comparison.786:    val onBurn: () -> Unit = {
805:                        PassphraseOutcome.Burn -> onBurn()
1358:                    onBurnAll = { session.messageRepository.burnAll(conversation.id) },
Let me set up a todo list and continue. I need to verify onBurn is unchanged from main. Compare the stub.

  Main's onBurn at line 786 vs HEAD's at 837. Let me diff the MainActivity between main and HEAD for the onBurn region. Actually `git diff main...HEAD` includes the MainActivity change of 125 lines. Let me look at what changed in MainActivity relative to main.Build succeeded. Now get test counts. Gradle XML results:

• Build passed. Let me extract the observed test counts:total: 483 failures+errors: 0 passed: 480 skipped: 3
diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index 21e6d9b..108f947 100644
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
+                // Same single derivation the two boot consumer
s use — see deriveBootDecision.
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


• Tests: 483 total / 0 failures / 480 passed / 3 skipped — matches the claim exactly. H verified.

  Now verify onBurn unchanged from main. Compare main's onBurn region with HEAD's. Main's at line 786, HEAD at 837. Let me diff those regions.

• Test counts match the claim exactly (H: 483/0/480/3). Now comparing `onBurn` against main:        if (reofferBiometric && canAuthenticateStrong) offerBiometricEnroll = true
        reofferBiometric = false
    }

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


• The second diff command returned empty after line 260 — meaning the MainActivity diff ended before 260 lines. Let me check the remaining diff part. Actually the first diff output ended at Route.Onboarding. Let me see the full remainder of the MainActivity diff.-                        // post-unlock reconcile (see the intent LaunchedEffect) retries the
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
So the full MainActivity diff is just those hunks. `onBurn` is byte-identical to main — the comment + stub. But wait, the diff shows MainActivity had 125 lines changed, and we've seen the import, boot routing effects, legacy effect removal, session collector change, Splash route. Also line 1104 region? The grep earlier showed line 1104 `route = if (!vaultExists && !container.serverDeleteConfirmed())`. Is that in the diff? Let me check if there are more hunks. The diff hunk count: import, two LaunchedEffects added, legacy effect removal, session collector, Splash. That's ~125 lines. Line 1104 might be pre-existing on main. Let me verify quickly.

  Also verify A0 remaining items: the removed legacy effect — is there any OTHER path that routes on legacy without the confirmed-marker precedence? `onUnlockPassphrase` PassphraseOutcome.LegacyImage → onboarding (line 857-864). That's an unlock-time backstop — user explicitly attempted unlock with passphrase; routing to onboarding there. Does it bypass confirmed-marker precedence? It happens only when the store threw LegacyImage during an unlock attempt. Could a confirmed-delete state co-exist? If serverDeleteConfirmed, boot routes to DeleteIncomplete, never the lock screen, so onUnlockPassphrase wouldn't run. Edge: marker written after boot routing while at lock screen? destroyVaultForAccountDeletion runs from DeleteIncomplete route. Probably fine — this existed on main too (need to check main's version).

  Let me check main's onUnlockPassphrase LegacyImage handling — likely identical.    // the offer whenever a wrap already exists (in BOTH sessions), so a cross-slot enable is never
    // tappable — closing the enable-action timing tell and the destructive re-enable (round-2).
    if (container.unlockRouter.biometricEnrollOffered(
            offerBiometricEnroll, session != null, container.biometricStore.isEnabled(),
        )
    ) {
        BiometricEnrollOffer(
            onEnable = {
                startBiometricEnable {
                    biometricEnabled = container.biometricStore.isEnabled()
                    offerBiometricEnroll = false
                }
            },
            onSkip = { offerBiometricEnroll = false },
        )
        return
    }

    // A Locked veil on a not-yet-created-vault install does NOT compose — normal routing
    // (Splash → Onboarding) runs instead, scan still queued; the first unlock drains it.
    val veilLockedPreOnboarding =
=== HEAD ===
                // derives the same route from the same disk truth — the two cannot disagree.
                container.scope.launch(Dispatchers.Main.immediate) {
                    identityFingerprint = null
                    unlocked = false
                    lockError = null
                    vaultExists = container.hasVault()
                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
                        // Destroy CONFIRMED (files gone, both markers retired) → fresh-install state.
                        Route.Onboarding
                    } else {
                        // The image (or the server-delete-confirmed marker) survives: the server
                        // account IS gone, so the only honest route is "finish deleting" with a
                        // direct retry — NEVER the lock gate (see Route.DeleteIncomplete).
                        Route.DeleteIncomplete
                    }
=== LegacyImage in main ===
806:                        PassphraseOutcome.LegacyImage -> {
807-                            // A PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
808-                            // reservation; the store threw before any slot was interpreted (never a burn
809-                            // wipe). Route to fresh onboarding (the create there retires the old image).
810-                            vaultExists = false
811-                            route = Route.Onboarding
812-                            unlocking = false
813-                        }
814-                        PassphraseOutcome.ImageUnreadable -> {
Line 1104 region — the delete-handler finally — uses `hasVault()` and `serverDeleteConfirmed()`, not the full derivation. Is that a second code path deciding the same thing (class F, form 3)? Let me look at the full context of that block in HEAD. It says "derives the same route from the same disk truth — the two cannot disagree." This is the account-delete destroy handler's finally. It routes after a destroy attempt: Onboarding if nothing present and no confirmed marker; else DeleteIncomplete. It doesn't use residueSweepHold or vaultProvenAbsent. Is that a problem? After a successful destroy, files are gone durably (destroy writes markers and unlinks durably). The sweep hold concerns boot-time residue; after an explicit destroy the verdict isn't needed. This is pre-existing code on main? Let me check main's version of that block.

  Also PassphraseOutcome.LegacyImage handling is identical to main — fine, it's unlock-time backstop, unchanged.

  Let me view the full delete-handler finally in HEAD and main.                    // wrap/key. Runs AFTER the reseal, in completeTerminalWipe's finally, so it can
                    // never be skipped. THROWS VaultImageException.DestroyFailed if a file survives.
                    destroyVault = { container.destroyVaultForAccountDeletion() },
                    releaseGate = { container.unlockController.endTerminalWipe() },
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // DestroyFailed (a surviving file) or an unexpected teardown throw — either way
                // the routing below derives from disk truth. releaseGate already ran in
                // completeTerminalWipe's outermost finally, so the unlock gate is not stranded.
            } finally {
                // This callback runs on the coordinator's background (confined) dispatcher, so the
                // Compose-state reconcile is marshaled to Main (round 12d, Gemini). Main.immediate
                // + container.scope so a rotation mid-wipe cannot cancel it. The disk-truth reads
                // (hasVault / vaultDestroyPending — fast stats under imageLock) run on Main here,
                // as they already do from Splash routing. The session→route reconciler is the
                // parallel main-thread backstop: lockIf published session=null above, so it also
                // derives the same route from the same disk truth — the two cannot disagree.
                container.scope.launch(Dispatchers.Main.immediate) {
                    identityFingerprint = null
=== main same region ===
1048-                container.scope.launch(Dispatchers.Main.immediate) {
1049-                    identityFingerprint = null
1050-                    unlocked = false
1051-                    lockError = null
1052-                    vaultExists = container.hasVault()
1053:                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
The delete-handler finally is unchanged from main — it routes Onboarding/DeleteIncomplete after an explicit destroy. It doesn't consult residueSweepHold. Is this a gap? After destroy completes, residue was destroyed durably by destroy() itself, so vaultProvenAbsent would be true and hold irrelevant. Onboarding after a successful destroy is fine. And the reconciler comment says "lockIf published session=null above, so it also derives the same route from the same disk truth" — the session collector now uses deriveBootDecisionFromDisk which includes residueSweepHold. Could the two disagree now? The finally sets Onboarding when `!hasVault() && !serverDeleteConfirmed()`. The session collector (when session→null and unlocked was true) sets route from snap.route which could be LOCKED if residueSweepHold is true, or ONBOARDING if vaultProvenAbsent. Both write to `route`; "whichever writes last". Hold is true only if the boot sweep was non-durable. After a successful destroy of everything... the hold from boot could still be true (it's process-lifetime). Scenario: boot sweep non-durable (hold=true), then user somehow gets a session, then account delete destroys everything. finally sets Onboarding; session collector also fires (session null) and computes: serverDeleteConfirmed now false (markers retired), imagePresent false, hold=true → LOCKED. These disagree! But wait — comment in session collector: "whichever writes last the result is identical". With the carried hold, they can now disagree in this edge. However: can hold=true co-exist with a live session and a successful destroy? Hold=true means the boot sweep unlinked residue without proven durability. Then routing: hold → LOCKED (not onboarding). To get a session, user must unlock — but the image was unlinked by the sweep... Actually the sweep only runs when no image present. If hold=true, there's no vault.bin; routing LOCKED. Lock screen with no vault — unlock would fail... LegacyImage? Hmm. If image present, sweep is NO_MUTATION (gate 1 refuses), result would be... let me check what sweepOrphanedResidue returns when gate refuses: NO_MUTATION. Is NO_MUTATION treated as durable? In runBootReconcile, hold = (result == SWEPT_NOT_DURABLE). NO_MUTATION → hold=false. So hold=true only when sweep actually unlinked something but durability wasn't proven. Then no image exists (gate 1 ensured image proven absent before sweeping... presumably). Then user is at LOCKED with no vault. Can they create a session? Triple-entry ritual creates a vault (PassphraseOutcome.Created) — that's how they get a session. Then account delete: destroy destroys the NEW vault durably. Finally sets Onboarding; collector computes hold=true → LOCKED. Disagreement. But which is wrong? Onboarding is arguably right (fresh vault just destroyed durably). The collector would set LOCKED if it writes last. That's a mild over-conservative route (lock screen with no vault — user can re-lock... actually stuck?). Route.Locked with no vault: user can do triple-entry create again. Minor UX, fail-closed direction. And this scenario requires a non-durable sweep followed by vault creation and account deletion in one process lifetime. Very low severity at most; also pre-existing tension from round-1, not the round-2 delta. Also the collector only runs `deriveBootDecisionFromDisk` when `unlocked` was true (session went null from live). The destroy path publishes session=null — yes. Hmm, but the finally runs `container.scope.launch(Main.immediate)` and the collector is in the composition's LaunchedEffect. Order not guaranteed. This is a LOW at most, arguably INFO, and it was introduced in round 1 (0d348b4), not this delta. But the review scope is the whole unit as it would merge, so I can note it as LOW/INFO.

  Actually wait — is it really a disagreement? The finally runs after destroy completes; the collector reacts to session=null which was published by lockIf BEFORE the destroy (in completeTerminalWipe presumably). So collector likely runs first (deriving while confirmed marker still present → DeleteIncomplete), then the finally overwrites with the post-destroy truth (Onboarding). The final state is the finally's — correct. The reverse order: finally runs first (Onboarding), then collector fires with post-destroy disk state: serverDeleteConfirmed=false (retired), imagePresent=false, hold=whatever from boot. If hold=true → LOCKED overwrites Onboarding. Possible but requires the collector's coroutine to be delayed past the whole destroy+finally. Collector dispatch: session.collect in LaunchedEffect on main; lockIf publishes null; collector resumes on main; the destroy's finally launches on Main.immediate via container.scope. Timing is genuinely racy. Fail direction is conservative (Locked over Onboarding = fail-closed). LOW/INFO. I'll note it as LOW? Actually given rarity and fail-closed direction, INFO-LOW. I'll file as LOW with note.

  Now, continue verification. Key remaining: VaultImageStore sweep logic (E), destroy()/create() ordering (C, D), BootReconcileOwnerTest and SweepOrphanedResidueTest full read (G, I), A0: all three call sites verified (lines 646, 667, 783 — all bare calls, full input via the single derivation). Nothing else calls bootRoute or deriveBootDecision off-IO? Let me grep for callers of bootRoute and deriveBootDecision across main source.

• Now the VaultImageStore sweep internals and writer/reader table:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:646:        val decided = container.deriveBootDecisionFromDisk()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:667:            val snap = container.deriveBootDecisionFromDisk()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:783:                val snap = container.deriveBootDecisionFromDisk()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:246:     * `deriveBootDecisionFromDisk()`.
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:248:    internal suspend fun deriveBootDecisionFromDisk(): BootDecision = withContext(Dispatchers.IO) {
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:249:        deriveBootDecision(
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1183:internal fun deriveBootDecision(
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1200:        route = bootRoute(
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1245:internal fun bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:30:            bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:50:            bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:66:            bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:83:                bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:103:                        bootRoute(true, present, hold, proven, legacyImage = false),
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:127:            bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:142:            bootRoute(
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:162:            bootRoute(false, vaultImagePresent = true, residueSweepHold = true, vaultProvenAbsent = false, legacyImage = true),
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:166:            bootRoute(true, vaultImagePresent = true, residueSweepHold = true, vaultProvenAbsent = false, legacyImage = true),
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:199:                "bootRoute(confirmed=$confirmed, present=$present, hold=$hold, proven=$proven)",
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:201:                bootRoute(confirmed, present, hold, proven, legacyImage = false),
apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:228:        val onboarding = all.filter { (c, i, h, p, l) -> bootRoute(c, i, h, p, l) == BootRoute.ONBOARDING }
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:38:        val d = deriveBootDecision(
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:58:        val d = deriveBootDecision(
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:80:        val d = deriveBootDecision(
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:94:        val d = deriveBootDecision(
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:117:        val held = deriveBootDecision(
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:130:        val notHeld = deriveBootDecision(
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:141:            deriveBootDecision(false, true, false, false, { false }).present,
apps/android/app/src/test/java/com/zitrone/app/DeriveBootDecisionTest.kt:148:        val d = deriveBootDecision(


• The sweep gate code:
  - Gate 1: `!Files.notExists(binFile)` → refuse. Present or indeterminate refuses. ✓
  - Gate 2: `!Files.notExists(serverDeletedFile)` → refuse. Present or indeterminate refuses. ✓ (Note the comment says "`!Files.notExists` is true when the marker is present OR indeterminate" — correct: notExists returns false if exists or if status unknown? Actually `Files.notExists` returns true only if the file is proven not to exist; returns false if it exists OR if the existence cannot be determined. So `!notExists` = exists OR indeterminate. ✓)
  - Then if imageBearingFilesProvenAbsent() → NO_MUTATION.
  - Mutation: delete dek, delete dek.tmp, delete bin.tmp. Then re-stat; if not proven absent → SWEPT_NOT_DURABLE. dirSync → DURABLE or SWEPT_NOT_DURABLE.
  - catch Throwable → SWEPT_NOT_DURABLE. Comment says "This function is synchronous, so no CancellationException flows here" — it's not a suspend function, correct.

  Note: catch (t: Throwable) catches Errors too (OOM etc.) and reports SWEPT_NOT_DURABLE — acceptable fail-closed.

  One thing: `dekFile.delete()` — File.delete() returns bool, ignores failure. If delete fails (IO), then re-stat `imageBearingFilesProvenAbsent()` will be false → SWEPT_NOT_DURABLE. Good — the failure is caught by the re-stat. If dek delete fails but temps deleted... re-stat false → NOT_DURABLE. Fine.

  Now, E asks: prove both directions — what it wrongly DELETES, what it wrongly STRANDS. And hunt the MISSING ROW in the WRITER/READER table.

  States with dek or tmp without bin:
  1. interrupted create (dek durable, bin not written) — sweep. OK, DEK opens nothing.
  1b. interrupted retireLegacyImage — sweep. OK.
  2. dek.tmp — sweep.
  3. dek + bin.tmp — sweep. Hmm — row 3: a crash between DEK barrier and bin's rename. bin.tmp holds a COMPLETE outer image (staged). Sweep deletes it — losing a never-completed vault. Policy per open(). But wait — is this vault actually "never completed"? create() writes DEK first, then writes bin via atomicWrite (tmp + rename). A crash after DEK but before bin rename leaves dek + bin.tmp. The vault was never durable as a whole, user never successfully unlocked. Deleting is the policy. OK.
  4. bin present → refuse.
  5. bin stat fault → refuse.
  6. intent + bin → refuse via gate 1.
  7. confirmed marker → refuse.
  8. confirmed indeterminate → refuse.
  9. nothing → no-op.
  6c. intent + no bin + residue → sweep.

  Missing rows to hunt:
  - {delete-intent present, no bin, no residue}? Gate passes (bin absent, confirmed absent), imageBearingProvenAbsent checks bin, dek, bin.tmp, dek.tmp — all absent → NO_MUTATION. Intent marker survives. That's fine — the sweep doesn't touch markers; routing handles intent (boot routes to unlock/onboarding; intent cleared elsewhere). Not a sweep concern.
  - {dek present, no bin, delete-CONFIRMED marker present} → gate 2 refuses. Route.DeleteIncomplete owns it: destroy retry deletes everything. ✓
  - What about a dek written by destroy() partially? destroy unlinks; no dek creation.
  - Biometric wrap file? `vault.dek` is the wrapped DEK... is there a separate biometric wrap file in the vault dir? Let me check for other files in the vault directory: are there files like `vault.bio` or similar that could be orphaned? The sweep only handles dek, dek.tmp, bin.tmp. If create() writes another file durably before bin (e.g., a biometric enrollment file), an interrupted create could orphan it, and imageBearingProvenAbsent wouldn't see it — sweep claims clean while a file remains. Let me check what files VaultImageStore and related writers put in baseDir. Also `create()` — need to read it to verify DEK-first claim and markers clearing (C, D).

  Also hunt: does anything else write into baseDir? Let me grep for `File(baseDir` and marker names.

• Now let me verify create()/destroy() ordering claims and hunt for other files written into the vault directory:206: * SINGLE INSTANCE PER baseDir (load-bearing). AT MOST ONE VaultImageStore per baseDir
237: * @param baseDir directory the two image files live in (production: `context.filesDir`).
238: *   Taken as a bare [File] — no Context dependency — so it is host-unit-testable. baseDir MUST
245:    private val baseDir: File,
280:     * single-instance-per-baseDir contract (see class kdoc).
284:    private val binFile: File get() = File(baseDir, IMAGE_FILE)
285:    private val dekFile: File get() = File(baseDir, DEK_FILE)
286:    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
287:    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
540:                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
547:                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
899:     * next one on the same baseDir.
951:            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
1054:            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
1070:        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
1085:            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
1137:            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
1195:     * Claim the single-instance registration for [baseDir] (see class kdoc). Idempotent
1202:        val path = baseDir.canonicalFile.path
1241:        // Defensive: production baseDir = filesDir always exists, so this is a no-op there,
1438:                if (dirSync(baseDir) != DirSyncResult.DURABLE) {
1456:        const val IMAGE_FILE = "vault.bin"
1457:        const val DEK_FILE = "vault.dek"
1463:        const val DELETE_INTENT_FILE = "vault.delete-intent"
1470:        const val SERVER_DELETED_FILE = "vault.delete-confirmed"
1474:         * Process-wide set of canonical baseDir paths with a live VaultImageStore, enforcing
1475:         * the single-instance-per-baseDir contract (see class kdoc). Synchronized so
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/BiometricVaultKeyCipher.kt
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
apps/android/app/src/main/java/com/zitrone/app/diagnostics/BootDiagnostics.kt
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt


290:    fun exists(): Boolean = imageLock.withLock { binFile.exists() }
300:    fun primaryImageProvenAbsent(): Boolean =
311:    fun isLegacyImage(): Boolean =
483:    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
929:    fun retireLegacyImage() {
1032:    fun markDeleteIntent() {
1036:    fun markServerDeleteConfirmed() {
1092:    fun destroy() {
1159:    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
1168:    fun deleteIntentPending(): Boolean =
• Verified key facts:
  - create(): markers cleared FIRST (verified durably) before any vault byte → then DEK written durably before bin. ✓ So interrupted create leaves {dek, no bin, no markers}. ✓ C claim: "create() writes the DEK first" ✓.
  - destroy(): writes confirmed marker durably BEFORE unlinking ✓ (C claim verified).
  - retireLegacyImage at 929 — need to read it to verify the "unlinks bin THEN dek" claim and row 6c.
  - BiometricVaultKeyCipher doesn't write files into baseDir (grep for File( returned nothing) — check where biometric wrap lives. It probably uses SharedPreferences. Quick check.

  Now let me read retireLegacyImage (line 899-960ish).retireLegacyImage: unlinks bin THEN dek ✓ (row 1b claim). It writes/clears NO markers ✓ (row 6c claim about markers surviving retirement: intent marker stands while bin is unlinked by retire → crash between retireLegacyImage and create leaves {delete-intent, no bin, possibly dek}. Wait — retireLegacyImage deletes bin AND dek. A crash between binFile.delete() and dekFile.delete() leaves {dek, no bin, intent?}. But where does the intent come from in the 6c scenario? Row 6c says "a crash between retireLegacyImage() and create()" leaves an intent standing over an absent image. Hmm — how did the intent marker get there before retirement? Scenario: user had a v2 vault, initiated account delete (intent marker written), then... 0.9.1 was fresh-install-only so v2 has no server account. Hmm, but the claim: `createVaultAndPublish` calls `retireLegacyImage()` BEFORE `create()`. If a delete-intent marker exists (stale from... what?), and retire unlinks the image, then crash before create() clears markers → {intent, no bin, residue?}. After retire completes fully, no residue either — retire deletes dek too. Crash between bin.unlink and dek.unlink inside retire leaves {intent, dek, no bin}. Crash after retire completes but before create clears markers leaves {intent, nothing} — sweep no-ops, markers handled by routing. So 6c's "residue" requires a crash INSIDE retire (between bin and dek unlinks) while an intent marker stands. Or crash between retire and create leaves {intent, no bin, no residue} — sweep no-op. Either way safe. The justification is plausible.

  Actually wait — one subtlety on row 6c's SWEEP verdict: is sweeping {intent, dek, no bin} correct? The intent marker means a server delete was initiated for the CURRENT account. But the image is already gone (retired). The dek is orphaned. The account-delete flow will reconcile via the intent (retry authenticated DELETE) — needs the vault to authenticate though! If the vault image is gone, how does the reconcile authenticate? That's a pre-existing concern of the delete flow, not the sweep. The dek is dead data. Sweep is fine.

  Now, missing row hunt (E). Files in baseDir: vault.bin, vault.dek, .tmp variants, two markers. What about the biometric wrap? BiometricVaultKeyCipher — grep showed no File( in it. Where's the biometric wrap stored? In ZitroneApp — BiometricUnlockStore, probably SharedPreferences. Let me verify quickly that nothing else writes image-bearing files into filesDir. Grep for `File(app.filesDir` or `File(baseDir`.

  Also check `open()` deletes leftover temps (policy reference). And check who calls sweepOrphanedResidue — only startBootReconcile. Also verify ResidueSweepResult enum and check ledger/docs.

  Also for A: confirm onBurn unchanged — the onBurn block including comment is byte-identical to main (main lines 779-789 vs HEAD 830-840 — both read the same). ✓ Also PassphraseOutcome.Burn still exists — that's main's code too. burnAll (message burn) is unrelated conversation feature, exists on main. ✓

  B: coupling line `signalBurnCompleted(obliterated = burned)` — grep found no signalBurnCompleted anywhere. ✓ No dangling state.

  C: completeInterruptedBurn/reconcileOrphanedBurnMarkers — no references anywhere ✓. Verify the unreachability claim: create() writes DEK first ✓ (line 539-546), destroy() writes confirmed marker durably before unlinks ✓ (line 1108). Also, do the ledger/docs still reference them? grep earlier covered main+test+SECURITY_MODEL only for those names; it found nothing. Let me grep the whole repo for these names to be thorough (excluding .git).

  D: strictly-better-than-main. On main, boot routing (Splash onFinished) keyed on vaultExists (hasVault → bin exists) and serverDeleteConfirmed. {bin absent, dek present} → Onboarding → create overwrites dek (create writes new dek via renameIntoPlace, overwriting). Actually create() renames into place, replacing the stray dek. So on main the orphan is overwritten by later create; not destroyed explicitly but replaced. W-A sweeps it durably first. Claim: no state is made worse. W-A routes {bin absent, dek present} → sweep deletes dek durably → onboarding. Same destination, but durable unlink first. If sweep is NOT durable → hold → LOCKED (a lock screen with no vault — user can triple-entry create? At lock screen, triple-entry ritual creates vault — need to check that works with no image; that's the plausible-deniability design: lock screen over nothing, three entries create). So the hold state degrades to Locked instead of Onboarding — arguably worse UX in a rare fault case, but fail-closed. Fine.

  But wait — one more check on D: does W-A's sweep handle the case where dek exists but is actually NEEDED? {bin absent, dek present} — dek opens nothing without bin. Correct to sweep.

  Row 3: {dek, bin.tmp} — sweep deletes a COMPLETE staged image. Is that worse than main? On main, that state → onboarding → create overwrites. Same. The staged image was never committed (rename not done). open() treats temps as deletable. Consistent policy.

  E both directions:
  - Wrongly DELETES: gate 1 requires bin proven absent; gate 2 requires confirmed marker proven absent. What about {dek, no bin} where a vault unlock is IN PROGRESS? Boot-time: no session. create() holds imageLock while writing DEK then bin — sweep also takes imageLock, so no interleaving mid-create. destroy() also under imageLock. retireLegacyImage under imageLock. So the sweep can't observe a transient state of another in-process writer. Cross-process: single instance per process; two processes? App is single-process presumably. OK.
  - What about the single-instance OPEN_PATHS: sweep is called on the same store instance.
  - Missing row: {dek.tmp present, bin present}? covered by gate 1 (bin present → refuse). {bin.tmp only, no dek}? Not in table! Row 2 is dek.tmp; row 3 is dek+bin.tmp. A crash inside renameIntoPlace(binFile) after DEK durable but... no: bin.tmp write happens in create() only after dek durable, so bin.tmp implies dek present in create flow. But destroy/retire don't create temps. What about `writeSealedPayload` (reseal during session)? atomicWrite(binFile) — stages bin.tmp then renames over existing bin. Crash inside → {bin present(old), bin.tmp} → gate 1 refuses. OK. Can {bin.tmp, no bin, no dek} exist? renameIntoPlace(bin) is only called when dek write already durably completed (create) or bin already exists (writeSealedPayload). So no. Fine — the table's row 3 covers the only bin.tmp-without-bin case.
  - What about markers + residue: {confirmed marker, dek, no bin} → gate 2 refuse ✓ row 7 covers "delete-confirmed present, ...".
  - {intent + confirmed both, no bin, residue} → gate 2 refuses ✓.
  - Row for {residue + intent only}: 6c ✓.

  So the table looks complete over the file set. One more: subdirectory or lock files? None.

  Now F — enumerate EVERY consumer of boot-routing state:
  1. Splash decision effect (643-656): awaits splashFinished && bootDone, uses deriveBootDecisionFromDisk (which reads residueSweepHold.value — carried verdict ✓, ordered after publication since bootDone=bootReconciled which is set after hold in publish ✓ — publish sets residueSweepHold.value = hold THEN bootReconciled.value = true; ordering correct ✓).
  2. Re-derive effect (658-682): awaits bootReconciled.first{it} ✓ then derives ✓.
  3. Session collector (762-793): does NOT await bootReconciled! It derives on session→null. If a session goes null before boot reconciliation completes... can a session be live before bootReconciled? Session requires unlock/create, which requires leaving Splash, which requires bootDone. At process start, session is null. unlocked flag starts as session!=null → false at cold start. So the collector's else-branch requires unlocked=true, which requires a session having been live, which required boot to have finished routing. Hmm — unless session live at composition start (rotation): then unlocked=true initially; if session goes null (auto-lock) while bootReconciled still false... bootReconciled publishes quickly at boot, before Splash finishes. On rotation after boot, bootReconciled is already true (process-scoped). So by construction the collector runs after publication in all reachable states. But it doesn't await explicitly — is that a "consumer running before publication and reading a default"? The hold default is false; if the collector derived with hold=false default when the true hold is true... unreachable as argued. Also residueSweepHold is read inside deriveBootDecisionFromDisk at call time, not a snapshot. Fine.

  Actually wait, one more scenario: process start, session==null, user... the collector's else-if(unlocked) branch only fires when session transitions null with unlocked==true. unlocked becomes true only via the collector's live!=null branch or onUnlockSuccess. All post-boot. ✓

  4. onRetryDestroy (692-709): uses hasVault() and serverDeleteConfirmed() — routes to Onboarding after successful destroy retry. Doesn't consult hold. After explicit destroy, files durably gone; hold only matters for boot-sweep residue. If hold=true (boot sweep non-durable) and then... DeleteIncomplete route requires confirmed marker; retry destroy succeeds; route Onboarding. The non-durable boot sweep residue was already unlinked; journal replay could resurrect a temp on crash — but that's a crash concern, not routing. Onboarding presentation over potentially-resurrectable temp... the hold exists precisely for that: "a crash could replay the journal and bring it back". But onRetryDestroy is reached only via DeleteIncomplete which required a confirmed marker — meaning a destroy was owed; residue sweep wouldn't have swept (gate 2 refuses when confirmed marker present!). Wait — can hold=true AND confirmed marker present? Sweep gate 2 refuses when confirmed marker present → NO_MUTATION → hold=false. So hold=true implies no confirmed marker at boot → DeleteIncomplete unreachable at boot. Later marker could be written (delete flow needs session...). Marginal. Fail direction conservative anyway.

  5. The delete-handler finally (1104): discussed above — unchanged from main; potential disagreement with collector now that collector carries hold (LOW at most).

  6. Line ~1104 veilLockedPreOnboarding and other hasVault() uses — routing §0 (Locked vs Onboarding compose) uses vaultExists which is now fed by decided.present && !decided.legacy at the two boot consumers and collector. ✓ full input set flows through BootDecision.

  Anything else calling vaultProvenAbsent/hasVault for routing? Let me grep MainActivity for hasVault and serverDeleteConfirmed uses.

• Verifying remaining consumers of boot-routing state and the biometric wrap location:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:619:    // Splash → Locked (which keys on hasVault() and would fake-lock a live, unlocked session and
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:631:    var vaultExists by remember { mutableStateOf(container.hasVault()) }
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:639:    val bootDone by container.bootReconciled.collectAsState()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:665:        container.bootReconciled.first { it }
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:699:                !container.hasVault() && !container.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:727:    // routing authority: it set Route.Onboarding on its own, without awaiting `bootReconciled`,
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:728:    // without the carried `residueSweepHold`, and without consulting `serverDeleteConfirmed()` — so
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:959:    // re-derive hasVault() and route to unlock — never blindly re-call create() (which would throw
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:993:                    if (container.hasVault()) {
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1012:    // on disk). hasVault() is then false, so route to Onboarding (fresh-install state), never
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1103:                    vaultExists = container.hasVault()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1104:                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
./l00prite/.l00prite/ledger.md:885:`signalBurnCompleted(obliterated = burned)` sits in `onBurn`, the mechanism's terminus, so shipping the
grep exit: 0


• The only `signalBurnCompleted` reference is in the l00prite ledger (review process documentation, not code) — that's expected, it's the ledger describing the extraction. No code references. A/B/C verified.

  Check line 993 context (create error path) — uses hasVault for re-derive after create failure; that's the onboarding create error handler, pre-existing. Line 631: vaultExists initial seed `container.hasVault()` — runs at composition before boot reconciliation! Seeding composition state from a pre-sweep stat. But route starts at Splash (unless session live), and the actual routing decision awaits bootDone. vaultExists seeded true/false affects... the initial value only matters when route leaves Splash, which only happens via the decision effect that overwrites vaultExists. Except: `veilLockedPreOnboarding` and Route.Locked composable use vaultExists — but route=Locked only set post-decision. OK.

  Now BootDiagnostics grep returned nothing for those — fine.

  Now read the tests: BootReconcileOwnerTest (full), SweepOrphanedResidueTest (full), BootRouteTest — for G and I. Let me read BootReconcileOwnerTest fully.

• Only a ledger doc mentions the burn coupling — code is clean. Now the test files for G and I:

• BootReconcileOwnerTest: 8 tests. The fixed cancellation test (233-269) genuinely cancels: sweep throws CancellationException after incrementing. Then a second call's claim returns false → no re-run. sweepRuns == 1 ✓. And hold.value == true (fail-closed publication from finally) ✓.

  Wait — one subtlety: in `a retry after a cancelled run does not re-sweep`, throwing CancellationException from `sweep` inside `withContext(ioDispatcher)` in a runTest scope... The CancellationException thrown inside the coroutine launched on `this` (the runTest scope). runBootReconcile catches CancellationException and rethrows (line 1155-1156), finally publishes, then the coroutine itself is "cancelled" — throwing CancellationException from a coroutine just completes it as cancelled, doesn't cancel the parent (launch children throwing CE are treated as normal cancellation). runTest's scope: a child coroutine cancelled with CE does not fail the test. ✓ advanceUntilIdle completes.

  Note also in runBootReconcile, after the finally, `withContext(ioDispatcher) { afterPublish() }` — in the cancelled case, the coroutine's job was cancelled by the CE propagating out of the inner withContext... Actually the CE propagates from sweep through the try/catch (rethrow c), out of withContext(ioDispatcher), into the outer try, finally publishes, then the CE continues to propagate → the `withContext(ioDispatcher) { afterPublish() }` line is never reached since exception propagates. Fine — afterPublish skipped on cancellation; production comment says non-routing hygiene. OK.

  Now G: contract — once-only ✓ (claim CAS tested), publication in finally on every exit ✓ (tested throw + cancellation), fail-closed default ✓ (tested), claim not stranded ✓. The test header at 84-97 honestly corrects a previously false mutation claim — good sign.

  One more check for G: production publish ordering — residueSweepHold.value = hold THEN bootReconciled.value = true ✓ (ZitroneApp 281-284).

  Now SweepOrphanedResidueTest — read fully for I (test quality) and E (rows coverage).Now I should verify the row-8 mutation claim myself: change gate 2 to `serverDeletedFile.exists()` and confirm only row 8 fails. Also verify the row-8 ELOOP symlink logic: a self-referential symlink at vault.delete-confirmed. `Files.notExists(path)` follows links → ELOOP → returns false (indeterminate). So `!notExists` = true → refuse. With the mutation (`exists()`), exists() follows link → ELOOP → false → gate passes → sweep proceeds → deletes dek → row 8 fails (dek deleted + SWEPT result). Also check row 7 with the mutation: marker present → exists() true → refuse. Still passes. And row 6b: intent only, no confirmed → exists() false → sweep → passes. So only row 8 catches it. Good — but I should actually run the mutation to be thorough since the brief emphasizes re-derivation. Let me do it: temporarily edit the source, run just SweepOrphanedResidueTest, observe failure, revert. It's a disposable worktree, expected.

  Also mutation for the DeriveBootDecision probe guard: dropping `imagePresent` from guard → `an absent image suppresses the legacy probe entirely` fails. And dropping `!serverDeleteConfirmed` → first test fails. And the hold-dropped mutation: pass constant residueSweepHold=false in deriveBootDecision → `every input reaches the decision unaltered` fails (held would read ONBOARDING instead of LOCKED). I can run these mutations quickly too. Let me do at least the two headline ones (gate 2 tristate; probe guards; hold wiring).

  Also check row 6b's header: it still says "an intent alone never accompanies an absent image in a legitimate delete state (an intent is written while the image is still present, and create() clears both markers durably before writing the DEK)" — the round-2 fix corrected the kdoc in VaultImageStore.kt but this test header at lines 160-172 still carries the OLD false proof! Let me re-read: lines 163-167: "There is deliberately no gate on `vault.delete-intent`. `destroy()` writes the CONFIRMED marker durably BEFORE it unlinks anything, so every real account-delete unlink already carries the confirmed marker and is caught by the other gate — while an intent alone never accompanies an absent image in a legitimate delete state (an intent is written while the image is still present, and `create()` clears both markers durably before writing the DEK)."

  Yes — this is the FALSE proof that 54a41bf corrected in VaultImageStore.kt's kdoc (row 6c admits the state IS reachable via retireLegacyImage + crash before create clears markers). The test header in SweepOrphanedResidueTest.kt still asserts the old claim verbatim. That's a stale comment — the exact class the brief warns about ("comments were wrong repeatedly"). The store kdoc was fixed; the test kdoc was not. Severity LOW (comment only; the test itself asserts correct behavior — sweep despite intent). But note the test's own state construction {bin deleted, binTmp, intent} IS row 6c's state essentially — and the test asserts SWEEP. So behavior right, justification stale. LOW finding.

  Wait, actually is it? Row 6b test: creates vault, deletes bin, writes binTmp, writes intent. Sweeps. This is exactly the 6c scenario (intent over absent image with residue) and it asserts sweep. Good behavior. But the header's reasoning "an intent alone never accompanies an absent image in a legitimate delete state" contradicts the store kdoc's corrected row 6c. Stale doc — LOW.

  Now BootRouteTest — skim for completeness of truth table. Let me read it.Wait — I spot a discrepancy. In `onboarding is reachable from exactly the expected input combinations`, expected includes:

  `listOf(false, false, true, true, true)` — confirmed=false, present=false, hold=true, proven=true, legacy=true.

  But hold=true is checked BEFORE vaultProvenAbsent but AFTER vaultImagePresent and legacyImage. bootRoute arms: confirmed → legacy → present → hold → proven → else. With legacy=true, route = ONBOARDING regardless of hold. So legacy=true gives ONBOARDING for all 8 combinations of (present, hold, proven) — the expected set lists 8 legacy combos + 1 non-legacy. Let me count: rows 238-245 = 8 combos with legacy=true: (t,t,t),(t,t,f),(t,f,t),(t,f,f),(f,t,t),(f,t,f),(f,f,t),(f,f,f) — yes 8. Plus (f,f,f,t,f) non-legacy. Total 9. bootRoute legacy arm → ONBOARDING for all 8. ✓ consistent.

  But hold=true + legacy → ONBOARDING: a non-durable sweep does NOT withhold onboarding when a legacy image is present. Is that a hole? If legacy image is present, the sweep REFUSED (gate 1: bin present → NO_MUTATION → hold=false in production). So hold=true + present=true is unreachable in production. But hold=true + present=false + legacy=true is contradictory too: legacy=true implies imagePresent (probe only runs when imagePresent). deriveBootDecision: legacy computed only if imagePresent. So legacy=true ⇒ present=true in every derived decision. hold=true requires the sweep mutated, which requires gate 1 passed (bin absent) → present=false. So hold=true + present=true + legacy=true unreachable. Fine — the truth-table rows are defensive.

  Hmm, but wait: there's a subtle interaction — sweep hold=true means residue was unlinked non-durably at boot; after that, no image present. A legacy image present at boot → sweep refuses → NO_MUTATION → hold=false. Consistent.

  Now: does `deriveBootDecision` probe suppression test correctly? `a confirmed delete suppresses the legacy probe entirely` — asserts probed==false and d.legacy==false and route DELETE_INCOMPLETE. Catches dropping `!serverDeleteConfirmed` from guard: then probe runs (probed=true) → assertFalse fails. ✓

  `an absent image suppresses the legacy probe entirely` — dropping imagePresent from guard: with serverDeleteConfirmed=false, probe runs → probed=true → fails. ✓

  `every input reaches the decision unaltered` — passing constant residueSweepHold=false: held case would compute ONBOARDING (proven absent, no hold) instead of LOCKED → assertEquals LOCKED fails. ✓. Passing constant vaultProvenAbsent=true: notHeld case... hmm — which test catches dropping vaultProvenAbsent? If derivation passed vaultProvenAbsent=true constant: the notHeld case (imagePresent=false, hold=false, proven=true) → ONBOARDING regardless. The held case → LOCKED regardless. present assert: deriveBootDecision(false,true,false,false,{false}).present — present comes from imagePresent directly, not bootRoute. If vaultProvenAbsent were dropped (constant false): notHeld → LOCKED instead of ONBOARDING → fails ✓. If imagePresent dropped (constant false)... probe suppressed, present=false → assertTrue(...present) fails ✓. If serverDeleteConfirmed dropped (constant false): the confirmed-precedence test `confirmed outbids legacy through the derivation` expects DELETE_INCOMPLETE → would get ONBOARDING → fails ✓. Good wiring coverage.

  Now let me run the mutations myself to verify. Three quick mutations:
  1. Gate 2: `!Files.notExists(serverDeletedFile.toPath())` → `serverDeletedFile.exists()` → expect only row-8 test fails.
  2. Probe guard: drop `!serverDeleteConfirmed` → expect DeriveBootDecisionTest first test fails.
  3. Hold wiring: pass constant false for residueSweepHold in deriveBootDecision → expect `every input reaches the decision unaltered` fails.

  Actually mutation 3: in deriveBootDecision, change `residueSweepHold = residueSweepHold` → `residueSweepHold = false`. Run DeriveBootDecisionTest.

  Let me do mutation 1 and 2+3. Run filtered tests with --tests.

• Test suites look honest. Now I'll re-run the key mutations myself rather than trust the headers:> Task :app:testDebugUnitTest FAILED
com.zitrone.app.SweepOrphanedResidueTest > row 8 - an unstattable confirmed marker must not cost the residue FAILED
14 tests completed, 1 failed
BUILD FAILED in 6s


• Mutation 1 verified: gate-2 fail-open fails ONLY row 8 (1 of 14). ✓

  Mutation 2 & 3 on deriveBootDecision:

• Gate-2 mutation fails exactly row 8, as claimed. Now the derivation wiring mutations:> Task :app:testDebugUnitTest FAILED
com.zitrone.app.DeriveBootDecisionTest > a confirmed delete suppresses the legacy probe entirely FAILED
6 tests completed, 1 failed
BUILD FAILED in 5s


> Task :app:testDebugUnitTest FAILED
com.zitrone.app.DeriveBootDecisionTest > every input reaches the decision unaltered FAILED
16 tests completed, 1 failed
BUILD FAILED in 3s
• All three mutations behave exactly as the test headers claim: gate-2 fail-open caught only by row 8; probe-guard mutation caught by the suppression test; dropped-hold wiring caught by the wiring test while BootRouteTest stays green (10 BootRouteTest tests passed — 16 total = 6 Derive + 10 BootRoute, only 1 failed). 

  Also verify the dropped-`imagePresent` mutation quickly? It's symmetric; the claim is plausible and I've verified the pattern. I'll also run one more: cancellation test mutation? Skip — verified structurally by reading.

  Remaining checks:
  - docs/SECURITY_MODEL.md changes (36 lines) — check for stale claims re: excluded healers or burn.
  - The ledger entries (96a2608) — informational.
  - Check ZitroneApp diff main...HEAD fully to see what else changed in the unit (236 lines) — I've read the current state of the key regions; let me view the full ZitroneApp diff to make sure I haven't missed a consumer or a change.
  - The stale test header in SweepOrphanedResidueTest row 6b (lines 163-167) still carries the round-2-corrected-FALSE proof ("an intent alone never accompanies an absent image in a legitimate delete state"). That's a real LOW finding: 54a41bf corrected the store kdoc but missed the test kdoc making the same claim.

  Also check: DeriveBootDecisionTest kdoc/header claims fine.

  Check SECURITY_MODEL.md diff.

• All three mutations fail exactly the tests their headers claim. Now the remaining diff surface — SECURITY_MODEL.md and the full ZitroneApp diff:diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
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


• The grep for removed lines in ZitroneApp diff returned nothing? That means the ZitroneApp changes are purely additive relative to main... wait, but the 54a41bf diff removed `sweepOrphanedVaultResidue` wrapper etc. — those were added in a98677f and removed in 54a41bf, net zero vs main. So vs main, ZitroneApp is +236/-0. Fine — I've read the current state of all the new code.

  SECURITY_MODEL.md: accurate. One line: "If a sweep unlinked residue but could not confirm the deletion durable, the lock screen is held for the rest of that boot" ✓. "A sweep that simply refuses ... changes nothing and reports nothing" ✓ (NO_MUTATION → no hold).

  Wait — one inaccuracy check: "it can never touch a live vault's key or state that an in-flight account deletion owns". In-flight deletion with intent only + image present: gate 1 refuses ✓. OK.

  Another: "the routing decision itself is exhaustively unit-tested" ✓ (BootRouteTest exhaustive).

  Now remaining items:
  - A0 row-7 test: confirmed restored and present (row 7 test exists at line 204). Gate coverage: gate 1 tristate (row 5 ELOOP test ✓), gate 2 present (row 7 ✓), gate 2 tristate (row 8 new ✓). All gates covered.
  - Check MainActivity line 993 area (create error re-derive) — pre-existing? Let me check main. It uses hasVault() to route after create failure — that's fine and pre-existing.
  - C: verify claims about excluded healers' trigger states unreachable: create writes DEK first ✓ (539), destroy writes confirmed marker durably before unlinks ✓ (1108). Also verify nothing references them ✓ (grep). Also ledger mention at ledger.md:885 — that's process documentation, acceptable.

  Let me quickly check MainActivity 955-1015 (create error path) and confirm it's unchanged from main.

  Also check the `isLegacyImage` usage in BootDiagnostics? grep found none. 

  Also F: "enumerate EVERY consumer of boot-routing state" — consumers of bootReconciled: MainActivity 639, 665. Consumers of residueSweepHold: only inside deriveBootDecisionFromDisk. Consumers of the routing decision: the three call sites + onRetryDestroy + delete-finally + vaultExists usages. I discussed onRetryDestroy and delete-finally. Also check Route.Locked / Route.Onboarding composables use vaultExists — let me look at lines 1010-1030 and the Locked branch.This create path is pre-existing main code (not in the unit diff — the MainActivity diff had no hunks here). Fine.

  One more check for A0: "The removed legacy effect: confirm no OTHER path still routes on legacy without the confirmed-marker precedence." Remaining legacy routes:
  1. onUnlockPassphrase PassphraseOutcome.LegacyImage → Onboarding (line 857-864) — unchanged from main. This is unlock-time: user at Lock screen with passphrase entered. Could a confirmed marker coexist? Boot would have routed DeleteIncomplete, so reaching Lock means no confirmed marker at boot. Could the marker appear while at the lock screen? The confirmed marker is written by the delete flow (requires session) or MessagingCoordinator's onConfirmed... During a live session the lock screen isn't showing. After session → null, the session collector re-derives with the full derivation (confirmed precedence ✓). So by the time the user is at Lock entering a passphrase, the collector would already have routed DeleteIncomplete if confirmed. Marginal window: session goes null → collector dispatches deriveBootDecisionFromDisk (now suspends on IO — slightly longer window) → user enters passphrase and hits Burn/Legacy arm before the collector applies? unlocking guard + attemptPassphrase... attemptPassphrase on a confirmed-delete vault? The delete flow's destroy would have removed the image; LegacyImage throw requires image present. If destroy hasn't run yet (marker present, image present), user unlock attempt with the LEGACY passphrase... a legacy image with confirmed marker: store throws LegacyImage → route Onboarding → create clears markers → erases the destroy authorisation! Hmm. But wait: can {confirmed marker + legacy image} exist? Legacy images are v2 (0.9.1), fresh-install-only, no server account — no delete flow could have run on them. The delete flow requires a session over the vault; a v2 image can never be unlocked (open throws LegacyImage). So no session → no delete intent/confirmed over a v2 image. Unreachable. OK — and this path is unchanged from main anyway.

  2. createVaultAndPublish's `if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()` — not routing; retirement guarded by re-proof. ✓

  Now the session-collector vs delete-finally disagreement I noted: introduced at round 1 (session collector uses full derivation incl. hold). Let me think once more about whether it can produce a wrong FINAL route:

  Delete flow: user initiates account delete → server confirms → completeTerminalWipe: lockIf publishes session=null (collector fires, derives: confirmed marker present → DeleteIncomplete ✓), then destroyVaultForAccountDeletion → destroy() unlinks everything, retires markers → finally on Main: vaultExists=hasVault()=false, serverDeleteConfirmed()=false → route=Onboarding ✓. Collector fires once per session change (null) — StateFlow conflation: it already fired for the null. It won't fire again. So final route = Onboarding from the finally. The collector's earlier firing would have set DeleteIncomplete (marker present at that time) — consistent. The race I worried about (collector delayed past destroy) requires the collector's IO derivation to be delayed across the entire destroy — theoretically possible but the collector would then compute: confirmed=false, present=false, hold=(boot-time value), proven=true → ONBOARDING unless hold. hold=true requires a non-durable boot sweep earlier in the process. If hold=true: collector sets LOCKED, finally sets ONBOARDING — whichever writes last wins; both are arguably defensible (fail-closed vs fresh state). This is a pre-existing-round-1 shape, fail direction safe, and requires a rare compound state. INFO at most. Actually — wait, with round 2's change the collector's derivation now suspends to IO, slightly widening the race window vs a bare call... no, the derivation always did IO work (isLegacyImage decrypt). Non-issue. INFO.

  Now let me also double check BootReconcileOwnerTest claim in its kdoc about build.gradle.kts — not important.

  Check `ResidueSweepResult` enum exists with 3 values, and dirSync injection in VaultImageStore constructor used by tests (newStore(dir, dirSync)) — exists ✓ (line 245-... constructor takes dirSync param presumably). Quick check.

  Also check ZitroneApp startBootReconcile's afterPublish: `runCatching { retryPlaintextCacheClearIfNoVault() }` — runs after gate opens, good.

  One more possible gap: `deriveBootDecisionFromDisk` is `internal suspend` — called from MainActivity composition. Fine.

  Check the ledger diff briefly (96a2608) — process docs, skip.

  I think I have everything. Findings:

  1. LOW — SweepOrphanedResidueTest.kt:163-167 (row 6b kdoc): still carries the false proof corrected in VaultImageStore.kt by this very commit ("an intent alone never accompanies an absent image in a legitimate delete state"). Row 6c in the store kdoc now says the opposite. The test's behavior is right; its justification is the stale, disproved one — the exact comment-drift class this unit keeps hitting, and this commit fixed it in one file while leaving it in the other.

  2. INFO — MainActivity delete-handler finally (1103-1112) routes on hasVault/serverDeleteConfirmed only, not the full derivation; with a process-lifetime residueSweepHold=true it can disagree with the session collector's derived route (both write `route`; ordering racy; fail direction is conservative). Pre-existing from round 1, unreachable without a compound rare state.

  Also verify enum ResidueSweepResult values quickly and the constructor dirSync param. And one more thing — check `git diff main...HEAD` for ZitroneApp fully additive claim; and check there are no other references to `sweepOrphanedVaultResidue` (removed wrapper) — grep found none ✓ (earlier grep included it, exit 1 overall but that grep included many patterns; let me confirm specifically).

• Two small confirmations before writing the verdict:grep: apps/android/app/build/kotlin/compileDebugKotlin/local-state/build-history.bin: binary file matches
exit=0
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:150:enum class ResidueSweepResult {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-151-    /** Nothing was unlinked: already provably clean, or a gate refused. The disk is UNCHANGED. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-152-    NO_MUTATION,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-153-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-154-    /** Residue was unlinked, proven absent, AND the unlink is crash-durable. Safe to route on. */
159:     * non-durable `dirSync`, residue that survived the unlink, or a throw past the mutation point).
259:    private val dirSync: (File?) -> DirSyncResult = ::defaultFsyncDir,
494:                // (no vault byte written yet) and VERIFIED by re-stat + required dirSync, so:
540:                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
547:                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {


• All verified. The only `sweepOrphanedVaultResidue` match is a stale build artifact in build/ (compiled history), not source. Write the final verdict.

• # Round-3 Independent Review — Unit W-A round-2 fix delta (54a41bf)

  Reviewed against source at HEAD (`54a41bf`), with mutations re-run in my own worktree. Verdicts on each focus item first, then findings.

  ## Verdicts on the round-2 fixes

  - **Dispatcher fix (MEDIUM ×3) — REAL, COMPLETE, STRUCTURAL as claimed.** `deriveBootDecisionFromDisk` is now `suspend` and wraps itself in `withContext(Dispatchers.IO)` (`ZitroneApp.kt:248-256`). All three consumers are byte-identical bare calls: `MainActivity.kt:646`, `:667`, `:783`. No other caller of `deriveBootDecision`/`bootRoute` exists in main source (grep-verified; only tests). No caller can choose the dispatcher anymore. The fix is strictly better than a third wrapper.
  - **`DeriveBootDecisionTest` — tests the WRAPPER, not `bootRoute` again.** It pins probe suppression on confirmed-delete and on absent-image, fail-closed probe (`runCatching{}.getOrDefault(false)`), precedence through the wrapper, and input wiring. I re-ran the key mutations: dropping `!serverDeleteConfirmed` from the probe guard fails exactly `a confirmed delete suppresses the legacy probe entirely`; substituting `residueSweepHold = false` in the derivation fails exactly `every input reaches the decision unaltered` **while all 10 BootRouteTest tests stay green** — the stated coverage gap is genuinely closed.
  - **Gate-2 tristate row 8 — catches the mutation, verified by running it.** I applied `!Files.notExists(serverDeletedFile.toPath())` → `serverDeletedFile.exists()` and ran the suite: 14 tests, **1 failed — only row 8**. The ELOOP symlink construction is sound (`Files.notExists` follows the link and returns false on ELOOP; `File.exists()` also false — the two implementations are separated by consequence: the DEK survives). No other tristate gate is uncovered: gate 1 has the row-5 ELOOP test, gate 2 now has row 8, and both have present-marker rows (4, 7).
  - **Row 6c corrected proof — verified against source.** `createVaultAndPublish` (`ZitroneApp.kt:464`) calls `retireLegacyImage()` before `create()`; `retireLegacyImage()` unlinks `binFile` then `dekFile` (`VaultImageStore.kt:940-941`) and touches no markers; only `create()` clears markers (`:505-510`). So a crash between them leaves an intent over an absent image — the old proof was indeed false, and the correction (swept because the image is already destroyed, not because the state is unreachable) is accurate. I hunted the other rows for false unreachability claims: rows 1, 1b, 2, 3, 4, 5, 6, 7, 8, 9 all check out against the actual writers (see E below). One related miss — finding 1.
  - **Cancellation test — genuinely cancels now.** `BootReconcileOwnerTest.kt:242-269`: the first run's sweep throws `CancellationException` after incrementing the counter; the second call's claim fails the CAS; asserts `sweepRuns == 1` and the fail-closed hold. Distinct from its neighbour, asserts on the damage.
  - **INFO items — all confirmed.** Dead `sweepOrphanedVaultResidue` wrapper is gone (no source reference; the one grep hit is a stale build artifact under `build/`). `obliterationComplete` → `imageBearingProvenAbsent` renamed everywhere including tests. The "Delete an incomplete-write temp" doc line sits on `deleteLeftoverTmp` (`VaultImageStore.kt:1450`).

  ## Verdicts on the binding focus items

  - **A0 — PASS.** All three consumers pass the full input set via the single derivation; none left on an older shape. No other legacy-routing path bypasses confirmed-marker precedence: the remaining legacy routes are `PassphraseOutcome.LegacyImage` (`MainActivity.kt:857`, byte-identical to main) and `createVaultAndPublish`'s re-proved retirement — and `{confirmed marker + v2 image}` is unreachable anyway (a v2 image can never be unlocked, so no session, so no delete flow over it). Gate coverage is complete (row 7 restored and present; rows 5/8 tristates; rows 4/7 present).
  - **A — PASS.** Grep across `apps/` source and docs: no `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion`. `onBurn` (`MainActivity.kt:837-840`) is byte-identical to `git show main:` — comment and stub, shows the uniform failure, destroys nothing. (`burnAll` at `:1397` is the pre-existing per-conversation feature on main.)
  - **B — PASS.** The one `signalBurnCompleted` reference repo-wide is in `l00prite/.l00prite/ledger.md:885` — process documentation describing the extraction, not code. No dangling caller, no half-removed state, no writerless field.
  - **C — PASS.** No references to `completeInterruptedBurn`/`reconcileOrphanedBurnMarkers` anywhere. The unreachability claims hold independently: `create()` writes the DEK durably before the image (`VaultImageStore.kt:539-546`) and `destroy()` writes the confirmed marker durably before any unlink (`:1108`, inside the lock, throwing if not durable).
  - **D — PASS.** On main, Splash routed on `vaultExists` (= `vault.bin` exists) + `serverDeleteConfirmed()` only — `{bin absent, dek present}` → onboarding, and a later `create()` overwrites the stray DEK via rename. W-A reaches the same destination but unlinks the residue with re-stat + dirSync first, and withholds onboarding (fail-closed) when durability is unproven. No state is made worse; the non-durable case degrades to a lock screen over nothing, which is conservative and recoverable.
  - **E — PASS.** Wrongly-DELETES direction: gate 1 requires *proven* image absence, gate 2 requires *proven* confirmed-marker absence; every in-process writer (`create`, `destroy`, `retireLegacyImage`, reseal) holds the same `imageLock`, so no transient state is observable; temps of a live vault (`{bin present, bin.tmp}` from a reseal crash) are refused by gate 1. Wrongly-STRANDS direction: rows 1/1b/2/3 swept, all dead data (a DEK opens nothing without an image; an uncommitted staged image follows `open()`'s own temp policy). Table completeness: I hunted the missing row — `{bin.tmp, no dek, no bin}` cannot exist (`renameIntoPlace(binFile)` only runs after the DEK barrier or over an existing image); `{intent, no bin, no residue}` no-ops correctly (sweep doesn't own markers); `{confirmed, residue}` is row 7. The table is complete over the actual file set (`vault.bin`, `vault.dek`, both `.tmp`s, two markers — the only files anything writes into `baseDir`; the biometric wrap lives in prefs, not the vault dir). The no-intent-gate reasoning is correct against `destroy()` (confirmed marker precedes unlinks) and `create()` (clears markers first).
  - **F — PASS.** Every consumer of boot-routing state: (1) the Splash decision (`:643-656`) awaits `splashFinished && bootReconciled`, uses the carried `BootDecision`, re-checks after the suspend; (2) the re-derive effect (`:658-682`) awaits `bootReconciled.first { it }`, re-checks the session after the suspend; (3) the session collector (`:762-793`) uses the same full derivation and is reachable only after boot (its `else` branch requires `unlocked`, which requires a live session, which requires leaving Splash). Publication order is `hold` then `done` (`ZitroneApp.kt:281-284`), so a released waiter never reads the default. No consumer recomputes durability from a stat. Two pre-existing, post-boot routers (`onRetryDestroy` `:699`, the delete-handler finally `:1103-1104`) route on `hasVault()/serverDeleteConfirmed()` after an explicit durable `destroy()` — correct without the hold (see finding 2 for a residual nit).
  - **G — PASS.** Once-only CAS (tested), `publish` in `finally` on every exit including cancellation (tested with a real waiter), fail-closed default `SWEPT_NOT_DURABLE` (tested both directions — throw holds, durable doesn't), claim survives cancellation (tested). The kdoc's four properties match the code. `afterPublish` is correctly outside the `finally` and correctly skipped on cancellation (non-routing hygiene only).
  - **H — PASS.** I ran `ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest` myself: **483 total, 0 failures/errors, 480 passed, 3 skipped** — counted from the JUnit XML, matching the commit's claim exactly. BUILD SUCCESSFUL.
  - **I — PASS with one doc nit (finding 1).** No vacuous test found; the suite is notably honest — two headers explicitly *retract* earlier overclaims (`BootReconcileOwnerTest.kt:88-97` admits a mutation claim was never run; `BootRouteTest.kt:213-218` and the ENOTDIR header at `SweepOrphanedResidueTest.kt:255-259` state their own limits). Nothing is tested against a copy of the logic — the truth tables are enumerated literals, not re-implementations. I re-ran three mutations rather than trusting headers; each failed exactly the test its header names. The failure shape still untested: rotation-through-recomposition of the Compose wiring (stated openly in `SECURITY_MODEL.md` — no Compose UI test infra exists).

  ## Findings

  **1. LOW — `SweepOrphanedResidueTest.kt:163-167` (row 6b kdoc): the false proof this commit corrected survives in the test file.**
  The row-6b header still asserts: *"an intent alone never accompanies an absent image in a legitimate delete state (an intent is written while the image is still present, and `create()` clears both markers durably before writing the DEK)"*. `54a41bf` corrected exactly this claim in `VaultImageStore.kt:1399-1405` (row 6c: the state IS reachable — crash between `retireLegacyImage()` and `create()`). The test's *behaviour* is right (it constructs precisely the 6c state and asserts SWEEP), but its justification is the disproved one — the same comment-drift class this unit has hit repeatedly, fixed in one file and left standing in the other. Fix: reword the 6b header to mirror row 6c (swept because retirement already destroyed the only openable image, not because the state is unreachable). Comment-only; no behavioural impact.

  **2. INFO — `MainActivity.kt:1103-1112` vs `:783`: two post-destroy routers can now disagree in a compound state.**
  The delete-handler finally routes on `hasVault()/serverDeleteConfirmed()` while the session collector routes on the full derivation including `residueSweepHold`. With `hold=true` (a non-durable boot sweep earlier in the process) plus a later successful account destroy, the finally computes Onboarding and the collector computes LOCKED; both write `route` and the ordering is racy. Introduced at round 1, not this delta; unreachable without that compound state; fail direction is conservative (Locked over Onboarding); and the collector's firing normally precedes the finally's post-destroy write. Not merge-blocking — noting it because this is the unit's signature failure class (two paths deciding the same thing), surviving in a weakened, post-boot form.

  ## Verdict

  **READY TO MERGE.** Every round-2 fix is real, complete, and safe — I re-derived each against source and re-ran the three load-bearing mutations myself; each fails exactly the test its header claims, and nothing else. No burn-dependent code, coupling residue, or dangling references survived the extraction. The test count is independently confirmed. The only new finding is a stale comment (finding 1, LOW) that this very commit's correction should have also touched; fix it here or as trivial follow-up — it does not block.

To resume this session: kimi -r session_a285edf9-b3e3-4b7c-9354-d30197929e0b
