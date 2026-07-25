Warning: True color (24-bit) support not detected. Using a terminal with true color enabled will result in a better visual experience.
YOLO mode is enabled. All tool calls will be automatically approved.
YOLO mode is enabled. All tool calls will be automatically approved.
Ripgrep is not available. Falling back to GrepTool.
(node:225467) [DEP0190] DeprecationWarning: Passing args to a child process with shell option true can lead to security vulnerabilities, as the arguments are not escaped, only concatenated.
(Use `node --trace-deprecation ...` to show where the warning was created)
Error executing tool grep_search: Path is not a directory: /tmp/claude-0/-root/841da8fa-3e71-412a-a3f2-5b7acd02c6e9/scratchpad/wt-gemini/apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt
Error executing tool grep_search: Invalid regular expression pattern provided: (?i)row [0-9]. Error: Invalid regular expression: /(?i)row [0-9]/: Invalid group
An independent security review of Unit W-A (commit `a98677f`) has been completed. The extraction cleanly severs the cold-start boot routing and residue sweep from the deferred duress-wipe mechanism. The core cryptographic hygiene and state machine correctness are extremely sound.

**VERDICT: READY TO MERGE** (with minor test additions and UI-layer deduplication).

### Focus Item Verdicts

**A. NOTHING BURN-DEPENDENT SURVIVED THE CUT: PASS**
Checked the entire tree for `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, and `tryApplyBurnCompletion`. None survived. `onBurn` in `MainActivity.kt` is a fail-closed stub identical to its state on `main` (`lockError = VaultUnlockRouter.UNIFORM_FAILURE`).

**B. THE COUPLING LINE IS CLEANLY SEVERED: PASS**
`signalBurnCompleted(obliterated = burned)` is completely severed. The `onBurn` stub is cleanly isolated and no dangling state remains.

**C. EXCLUDED HEALERS LEFT NO DANGLING CALLERS: PASS**
`completeInterruptedBurn()` and `reconcileOrphanedBurnMarkers()` are completely absent and unreferenced. The logical claim holds: `create()` writes DEK durably before `binFile`, and `destroy()` writes the confirmed marker durably before unlinking.

**D. W-A IS CORRECT STANDALONE: PASS**
On `main`, `{bin absent, dek present}` incorrectly triggers the first-run onboarding screen over a potentially recoverable encrypted image (`vault.bin.tmp`). W-A durably unlinks this orphaned residue, proves it's gone by re-stat, and ensures the UI correctly stalls at a lock screen if the unlink is not crash-durable. This strictly improves upon `main` without requiring the duress-wipe unit.

**E. THE SWEEP GATE: PASS (Logic), FINDING (Tests)**
- Gate 1 accurately prevents touching a live vault or failing open on unstattable files (`!Files.notExists`).
- **The MISSING ROW:** `{delete-intent present, no bin, no confirmed marker}`. This state is omitted from the WRITER/READER table in `VaultImageStore.kt`. While it is correctly handled by the code (it falls through both gates and is SWEPT, preventing a dead lock screen for a vault with no image and no confirmed destruction), it is structurally missing from the kdoc's exhaustive list.
- Furthermore, **Row 7 (`{delete-confirmed present, ...}`) is missing from the test suite** (see Finding 1).

**F. THE VERDICT IS CARRIED, NOT RE-DERIVED: PASS**
All 3 consumers of the routing state in `MainActivity.kt` (Splash finish, cold-start fallback, and session-nulling flow) call the pure function `bootRoute()` and explicitly pass `vaultProvenAbsent = container.vaultProvenAbsent()`. The state is correctly carried and no fresh stat is evaluated at decision time.

**G. `runBootReconcile`'s CONTRACT: PASS**
Verified against `ZitroneApp.kt`. `claim()` operates as a CAS. The `SWEPT_NOT_DURABLE` default ensures failure-closure. The `publish()` invocation correctly resides in a `finally` block, ensuring even a `CancellationException` releases waiters with a safe hold.

**H. INDEPENDENTLY RUN THE TEST SUITE: PASS**
Run inside the worktree (`./gradlew testDebugUnitTest`). My observed numbers precisely matched the commit claim: **Total: 475 / Failures: 0 / Skipped: 3 / Errors: 0 / Passed: 472**.

**I. TEST QUALITY: FINDINGS**
- **Tested against a copy of the logic:** `BootRouteTest.kt` passes vacuously for onboarding combinations (see Finding 2).
- **Untested failure shape:** `MainActivity.kt` duplicates the input derivation for `bootRoute` three times. Because Compose routing lacks automated test infrastructure, a divergence between these blocks will silently fail (see Finding 3).

---

### Findings

#### 1. Missing Test for Row 7 of the WRITER/READER Table
- **SEVERITY:** HIGH (Testing Defect)
- **File:Line:** `apps/android/app/src/test/java/com/zitrone/app/SweepOrphanedResidueTest.kt:70`
- **Defect:** The test suite asserts that it walks the kdoc WRITER/READER table row by row. It defines a helper `private fun confirmed(dir: File) = File(dir, "vault.delete-confirmed")`, but contains no test for Row 7. Gate 2 (`!Files.notExists(serverDeletedFile)`) is never functionally proven in the suite to refuse the sweep when the confirmed marker is present.
- **Why it matters:** A destructive gate protecting a pending D2c deletion is completely untested. A refactor could accidentally remove Gate 2, allowing the sweep to obliterate state owned by `Route.DeleteIncomplete`, and the test suite would stay green.
- **Concrete Fix:** Add a test explicitly for Row 7 in `SweepOrphanedResidueTest.kt` that writes `confirmed(dir)`, runs the sweep, and asserts `ResidueSweepResult.NO_MUTATION`.

#### 2. Test Passes Vacuously by Reimplementing Production Logic
- **SEVERITY:** LOW
- **File:Line:** `apps/android/app/src/test/java/com/zitrone/app/BootRouteTest.kt:225`
- **Defect:** The test `onboarding is reachable from exactly the expected input combinations` tests against a literal copy of the target logic: `val expected = all.filter { (c, i, h, p, l) -> !c && (l || (!i && !h && p)) }`.
- **Why it matters:** Testing by copying logic (rather than defining expected behavior) masks regressions. If a developer incorrectly mutates `bootRoute` and copies the flawed logic into the test to "make it pass," the suite fails to catch the regression. 
- **Concrete Fix:** Enumerate the expected onboarding input tuples as an explicit, hardcoded list (as done in `full truth table`), rather than dynamically filtering `all` through a logical formula.

#### 3. Untested Failure Shape: Duplicated Input Derivation for Routing
- **SEVERITY:** MEDIUM
- **File:Line:** `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:652` (and lines 699, 835)
- **Defect:** The extraction of the 5 arguments to `bootRoute()`—specifically the ~1 MiB legacy image derivation `val legacy = if (present && !confirmed) runCatching { container.isLegacyImage() }.getOrDefault(false) else false`—is identically copy-pasted across three distinct recomposition/lifecycle event handlers. 
- **Why it matters:** This project relies on inspection for UI logic because it has no Compose UI test infrastructure. If one caller is eventually updated (e.g., adding a new parameter or changing how `legacy` is derived), the others will silently diverge, breaking the routing guarantee at runtime with no test to catch it.
- **Concrete Fix:** Extract the input derivations into a single, testable helper method in the `AppContainer` or as a top-level pure function (e.g., `deriveBootDecision`) that returns the `BootDecision` object.
