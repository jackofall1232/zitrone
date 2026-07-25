Warning: True color (24-bit) support not detected. Using a terminal with true color enabled will result in a better visual experience.
YOLO mode is enabled. All tool calls will be automatically approved.
YOLO mode is enabled. All tool calls will be automatically approved.
Ripgrep is not available. Falling back to GrepTool.
(node:251801) [DEP0190] DeprecationWarning: Passing args to a child process with shell option true can lead to security vulnerabilities, as the arguments are not escaped, only concatenated.
(Use `node --trace-deprecation ...` to show where the warning was created)
This unit extraction successfully separates the verified residue-sweep and boot-reconciliation logic from the still-contentious duress-wipe mechanisms. The single-derivation fix in `acb5904` resolves the routing disagreement cleanly and safely.

Based on an independent review of the source code, test execution, and diffs on the writable checkout, here are the findings and verdicts for the focus items.

### Findings

**INFO: The `catch (Throwable)` block inside `sweepOrphanedResidue` remains entirely untested.**
- **File:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1196`
- **Defect:** `SweepOrphanedResidueTest.kt` tests a non-durable `dirSync`, but it lacks any test simulating a JVM/filesystem exception thrown *during* the file unlinks (e.g., `SecurityException` inside `dekFile.delete()`).
- **Why it matters:** The block correctly catches `Throwable` and fails closed to `SWEPT_NOT_DURABLE`, but without coverage, a future refactoring could inadvertently let the exception escape, which would unpublish the sweep verdict and permanently strand waiters on the lock screen.
- **Fix:** Add a test to `SweepOrphanedResidueTest` using an injected/mocked file pointing to a directory without write permissions, asserting that the sweep catches the throw and returns `SWEPT_NOT_DURABLE`.

### Focus Items (A-I)

**A0. Sibling Call Sites of Every Round-1 Fix: VERIFIED.**
All consumers of boot-routing state in `MainActivity.kt` (the splash/boot effect, unlocked-to-false effect, and account-delete completion callback) have been migrated to the single `deriveBootDecisionFromDisk()` wrapper. The legacy standalone effect (`if (vaultExists && container.session.value == null)`) was removed, and no other paths route on legacy without marker precedence. The row-7 test was restored, covering gate 2.

**A. Nothing Burn-Dependent Survived the Cut: VERIFIED.**
`MainActivity.kt`'s `onBurn` is byte-identical to `main` (a stub). I verified via `git grep` that none of the `burnVault`, `obliterateForBurn`, or `tryApplyBurnCompletion` symbols exist in the branch.

**B. The Coupling Line is Cleanly Severed: VERIFIED.**
`signalBurnCompleted(obliterated = burned)` is gone. There are no dangling callers or uninitialized states leftover from the split.

**C. Excluded Healers Unreachability Proof: VERIFIED.**
`completeInterruptedBurn` and `reconcileOrphanedBurnMarkers` are absent and unreferenced. The unreachability proof holds up strictly against `VaultImageStore.kt`:
1. `create()` writes `vault.dek` and dir-fsyncs it *before* ever writing `vault.bin`.
2. `destroy()` writes `vault.delete-confirmed` durably *before* unlinking any files.
Therefore, the states those healers targeted are unreachable without the duress wipe mechanism.

**D. W-A is Correct Standalone & Strictly Better than Main: VERIFIED.**
On `main`, the state `{bin absent, dek present}` relies on `hasVault()` (`binFile.exists()`), routing to `Onboarding` where a subsequent create blindly overwrites the `dek`. W-A's sweep explicitly identifies and safely clears this exact orphaned state *before* it can reach `Onboarding`, which is cleaner, safer, and structurally superior to a blind overwrite.

**E. The Sweep Gate: VERIFIED.**
The missing row is **6c (`{delete-intent, no bin, residue}`)**. It is reachable via a crash between `retireLegacyImage()` and `create()`. Sweeping it is correct because the image is already unlinked (it opens nothing); keeping it strands dead data. There is deliberately no `delete-intent` gate because `destroy()` writes the `delete-confirmed` marker *before* unlinking anything. Thus, a true account-deletion unlink in-flight always carries a `delete-confirmed` marker, which is properly caught by gate 2 (`!Files.notExists(serverDeletedFile.toPath())`). Adding a `delete-intent` gate would wrongly strand residue. 

**F. The Verdict is Carried, Not Re-Derived: VERIFIED.**
The routing logic no longer re-derives state at multiple levels. The consumers in `MainActivity.kt` use `deriveBootDecisionFromDisk()`, which passes the explicitly carried `residueSweepHold.value` into the pure `deriveBootDecision` function. All consumers wait for publication (e.g. `bootReconciled.first { it }` or post-teardown callbacks).

**G. `runBootReconcile`'s Contract: VERIFIED.**
1. **Once-only:** Uses `bootReconcileStarted.compareAndSet(false, true)`.
2. **Publication in finally:** `publish(verdict != ResidueSweepResult.SWEPT_DURABLE)` executes unconditionally in the `finally` block.
3. **Fail-closed:** The initial `verdict` is `SWEPT_NOT_DURABLE`.
4. **Cannot be stranded:** Because publication is in the `finally` block, wait states are correctly released even on a `CancellationException` or arbitrary throw.

**H. Independently Run the Test Suite: VERIFIED.**
Ran `testDebugUnitTest` in the provided writable checkout. Parsed Gradle XML reports output: **Total: 487. Passed: 484. Failures: 0. Errors: 0. Skipped: 3.** This matches the commit claims exactly.

**I. Test Quality:**
- **Vacuous passes / false claims:** Historically, `BootReconcileOwnerTest` contained a test that claimed to catch "publishing `done` before `hold`", but as newly corrected in the docstring, swapping those assignments left the tests green (because `StateFlow` conflates). Similarly, the row 6b test header falsely claimed the intent+absent image state was "unreachable". Both instances of misleading assertions were caught and corrected in this PR.
- **Copy of logic:** A prior iteration of `BootRouteTest` computed expectations using a formula that duplicated the `bootRoute` logic. This was rewritten to explicitly enumerate and filter all 32 boolean combinations.
- **Untested failure shape:** The `catch (t: Throwable)` block inside `sweepOrphanedResidue` (detailed in Findings).

### Item 1 (The Sole Real Risk): Unified Routing & Hold Masking

The unification of the delete path and collector through `deriveBootDecisionFromDisk` is robust and entirely safe.
- **Justification for superseding:** `destroy()` provides *strictly stronger* evidence of directory cleanliness than `sweepOrphanedResidue()`. `destroy()` forces a directory fsync and re-stats the directory to prove absence before retiring markers.
- **Post-Destroy State:** If an image miraculously survives a destroy, `serverDeleteConfirmed()` is true, and the derivation returns `DELETE_INCOMPLETE`. It will never route to the `LOCKED` gate post-destroy.
- **Safety of clearing the hold:** `destroySupersedesResidueHold` operates purely dynamically *inside* the derivation function. It **masks** the hold during route calculation; it does *not* mutate the underlying `residueSweepHold` `StateFlow` value itself. Therefore, it resolves the race condition cleanly without causing side effects for other consumers.

### Conclusion

**READY TO MERGE.** The extraction is clean, the test numbers are genuine, the `residueSweepHold` mask resolves the post-destroy race without state mutation, and the unreachability proofs hold against the canonical file paths.
