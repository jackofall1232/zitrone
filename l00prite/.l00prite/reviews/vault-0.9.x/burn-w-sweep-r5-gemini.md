Warning: True color (24-bit) support not detected. Using a terminal with true color enabled will result in a better visual experience.
Ripgrep is not available. Falling back to GrepTool.
I have reviewed the cumulative unit diff and the specific files (`VaultImageStore.kt`, `MainActivity.kt`, and `ZitroneApp.kt`) directly from the source. The security review has uncovered multiple broken contracts, an incomplete invariant table, and a lifecycle bug that restomps the UI. 

**This unit is NOT READY TO MERGE.**

Here are the findings, followed by the explicit verdicts for A-F.

### Finding 1: Failed burn completion restomps UI on device rotation
**SEVERITY:** HIGH
**File:** `MainActivity.kt`, lines ~401 (`LaunchedEffect(burnCompletion)`) and `postBurnRoute`
**Defect:** The `IGNORE_STALE` arm in `postBurnRoute` (`burnReportedSuccess && vaultImagePresent`) correctly ignores a *successful* burn that has a successor vault. However, a FAILED burn (`burnReportedSuccess = false`, `vaultImagePresent = true`) is NOT caught by `IGNORE_STALE`. Because `burnCompletion` is a process-lifetime `StateFlow`, any Activity recreation (e.g., screen rotation) while the user is on the lock screen will re-fire the observer on the new composition. The stale failed burn falls through to `PostBurnRoute.LOCKED` and forces `lockError = VaultUnlockRouter.UNIFORM_FAILURE`.
**Why it matters:** A user who attempts a burn and fails (e.g., wrong password) will unlock their vault normally. If they later lock the vault and rotate the device, the lock screen will mysteriously present a wrong-password error. A stale event is re-applied because "failed" was not considered a state that can become stale. The Kdoc claiming "A FAILED burn is untouched... which is what keeps the fail-closed LOCKED arm intact" is flawed because it ignores that the fail-closed arm is restomped on every rotation.
**Concrete fix:** A `StateFlow` is the wrong primitive for a consumed event. Change `burnCompletion` to a `SharedFlow` with `replay = 0`, or expose a `consumeBurnCompletion()` method on the container to null it out after the UI acts on it.

### Finding 2: Incomplete input set on the session logout path
**SEVERITY:** HIGH (Incomplete Input Set / Second Authority)
**File:** `MainActivity.kt`, inside the `container.session.collect` observer.
**Defect:** The comment claims: *"THE SAME decision function and THE SAME carried inputs as Splash and the boot re-derive"*. While it correctly passes the inputs to `bootRoute`, it assigns `vaultExists = container.hasVault()` DIRECTLY, completely failing to apply the `&& !legacyNow` correction that the Splash and Boot re-derive observers apply!
**Why it matters:** If a legacy image is present during session logout, `bootRoute` correctly returns `BootRoute.ONBOARDING`. But because `vaultExists` remains `true` (since `container.hasVault()` checks only `vault.bin`), the UI will render the biometric prompt or locked veil CTA on top of the fresh-install onboarding screen. This is the exact pattern warned about: a verdict discarded and recomputed from a cheaper signal at the consumption site.
**Concrete fix:** In the `session.collect` observer, change the assignment to `vaultExists = container.hasVault() && !legacyNow`. (`legacyNow` must be computed *before* this assignment).

### Finding 3: `afterPublish` stranded on coroutine cancellation
**SEVERITY:** MEDIUM
**File:** `ZitroneApp.kt`, in `runBootReconcile`.
**Defect:** The function contract explicitly claims *"cancellation cannot strand the claim"*. However, if the first `withContext(ioDispatcher)` is cancelled, `CancellationException` is caught and re-thrown. The `finally` block executes `publish`, but the exception then continues propagating OUT of the `try...finally` block, completely skipping the `withContext(ioDispatcher) { afterPublish() }` call at the bottom of the `launch` block.
**Why it matters:** If the scope is cancelled, `afterPublish` (which executes `completeInterruptedBurn()` and `reconcileOrphanedBurnMarkers()`) is permanently stranded and never runs. The contract is false.
**Concrete fix:** Move `afterPublish()` inside a `withContext(NonCancellable)` block within the `finally` block, or swallow the `CancellationException` before it escapes the `try` block.

### Finding 4: Incomplete WRITER/READER Invariant Table for `sweepOrphanedResidue`
**SEVERITY:** INFO (Missing Row)
**File:** `VaultImageStore.kt`, Kdoc for `sweepOrphanedResidue`.
**Defect:** The invariant table claims that state 1 (`{dek, no bin, no markers}`) is written ONLY by an *"interrupted create... OR a partial burn"*. This is PROVABLY INCOMPLETE. `retireLegacyImage()` deletes `binFile` first, then `dekFile`. A crash exactly between these two unlinks leaves EXACTLY the state `{dek, no bin, no markers}`.
**Why it matters:** The maintainer's ratification rests on a table that missed a writer. The code behavior is actually SAFE (sweeping a dead legacy DEK is correct and harmless), but the documentation/proof is incomplete as tested by Instruction 1.
**Concrete fix:** Add "interrupted retireLegacyImage" as a writer for row 1.

---

### Explicit Verdicts

**A. Enumerate EVERY site assigning `route` or `vaultExists` in MainActivity.kt.**
I refute the previous lens. `MainActivity.kt` contains assignments that do NOT pass the full input set and establish a second authority. Specifically:
1. `onDeleteAccount.onConfirmed` manually implements routing (`route = if (!vaultExists && !container.serverDeleteConfirmed()) Route.Onboarding else Route.DeleteIncomplete`) completely bypassing `bootRoute` and `residueSweepHold`.
2. The session logout observer uses an incomplete assignment for `vaultExists`, dropping the `legacyNow` correction (see Finding 2).

**B. PostBurnRoute.IGNORE_STALE evaluated first in 5ef5f77.**
`IGNORE_STALE` is correct for a successful burn that leaves a successor vault. However, it is dangerously wrong for a failed burn, which is swallowed by the `StateFlow` and restomps the UI with a `UNIFORM_FAILURE` upon device rotation (see Finding 1).

**C. `bootRoute` precedence and `runBootReconcile` contract.**
`bootRoute` precedence is correct (legacy images do not mask `DELETE_INCOMPLETE`). However, `runBootReconcile`'s contract is broken because cancellation strands `afterPublish` (see Finding 3).

**D. `sweepOrphanedResidue`'s gate and WRITER/READER table COMPLETE?**
The removal of the delete-intent clause is safe, but the premise that `destroy` and `create` are the only writers is false. `retireLegacyImage` is a missing row in the table, producing `{dek, no bin}` (see Finding 4).

**E. The cumulative unit.**
The keys-first unlinks and marker clear strictly after unlinks proven durable are structurally sound. However, the state management around the UI and `StateFlow` events leaks state across lifecycles, and the fail-closed UI behavior is compromised by re-firing observers.

**F. Test quality.**
The test coverage is lacking at the consumption site for `vaultExists` in the session logout observer. A test that a value is computed is not a test that it is used: the unit tests assert that `bootRoute` computes the correct route, but fail to catch that the `vaultExists` state is updated incompletely at the consumption site.
