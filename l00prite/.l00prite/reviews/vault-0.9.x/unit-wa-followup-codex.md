# VERDICT: READY TO MERGE

No CRITICAL, HIGH, or MEDIUM findings. Two LOW findings.

## Findings

### LOW — `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:711`

**Defect:** The new comment says repeated destroy “self-heals,” but idempotence proves only that retrying is safe, not that retrying will eventually succeed. A persistent unlink or stat fault can keep residue present forever. The new test’s own non-empty `vault.dek` directory is a concrete example: every `destroy()` rewrites the confirmed marker, every `delete()` fails, and every retry remains on `DeleteIncomplete`. There is no in-app alternate exit.

**Why it matters:** The behavior change is correctly fail-closed and safer than the old onboarding verdict, but it introduces a possible permanent availability failure in a pathological/corrupt filesystem state. The source and commit message overstate recovery, and this sole behavioral change has no direct test.

**Concrete fix:** Replace the unconditional self-healing claim with “safe to retry; transient faults may self-heal,” add a direct retry-routing test covering leftover DEK/temp and marker-retired states, and provide or explicitly document the recovery path for a persistent fault (for example, app-data reset/support guidance). Do not weaken the proven-absence criterion.

### LOW — `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:1172`

**Defect:** Immediately below the corrected `runBootReconcile` KDoc, the implementation comment still says “Production's lambda wraps itself.” Production actually passes `retryPlaintextCacheClearIfNoVault()` bare at `ZitroneApp.kt:285-288`; containment is supplied by `runBootReconcile`.

**Why it matters:** The delta claims to correct this stale documentation fact, but leaves the opposite claim next to the code. This is especially misleading in security-sensitive cancellation/fault-containment logic.

**Concrete fix:** Update the implementation comment to state that production deliberately passes a bare lambda and relies on wrapper-level containment.

## A — `onRetryDestroy`

On this reachable path, `residueSweepHold` is false: `DeleteIncomplete` at boot requires a visible confirmed marker; the sweep then refuses before mutation and cannot raise the hold. A previously raised hold routes to `LOCKED`, admits no session, and therefore cannot reach the account-delete flow that would create `DeleteIncomplete`. Dropping hold supersede here is justified, not merely convenient.

Post-retry verdict map (C = confirmed marker as observed by `File.exists`; B = `vault.bin` as observed by `File.exists`; P = all four image-bearing paths proven absent by `Files.notExists`):

| Post-retry state | Old verdict | New verdict |
|---|---|---|
| C present, any image state | failure / stay `DeleteIncomplete` | `DELETE_INCOMPLETE` / stay |
| C absent, B present | failure / stay | `LOCKED` / stay |
| C absent, B absent, P true | `Onboarding` | `Onboarding` |
| C absent, B absent, P false (DEK/temp survives or any image stat is indeterminate) | `Onboarding` | `LOCKED` / stay `DeleteIncomplete` |
| C stat indeterminate (therefore `exists()==false`) | Treated as absent; otherwise as rows above | Same marker weakness; image proven-absence still distinguishes onboarding from locked |

The fourth row is strictly changed and can leave the user indefinitely stuck when the fault is persistent. That is safer than exposing onboarding over recoverable residue, but “self-heals” is not guaranteed (LOW finding above).

`destroy()` is retry-safe even after marker retirement: each call first recreates and durably syncs `vault.delete-confirmed`, then attempts all unlinks, verifies, syncs the directory, and only then retires both markers. A transient failure can therefore heal on retry. A persistent failure cannot.

## B — Existing coverage

No existing test was deleted, defanged, or stripped of `@Test`. Source annotation count is exactly 487 at `aa380c1` and 491 at HEAD. Test-file diff is +98/-2 in `BootReconcileOwnerTest` and +65/-0 in `SweepOrphanedResidueTest`; the two removed lines are only the stale docstring text. Exactly four test methods and four `@Test` annotations were added.

## C — Test honesty

The tests are honest:

- Surviving-unlink test asserts both `SWEPT_NOT_DURABLE` and that the residue remains on disk.
- Throw-after-unlinks test asserts the fail-closed verdict and that the unlink already landed.
- Synthetic-cancellation test asserts verdict publication, released/finished coroutine state, and no cancellation.
- Real-cancellation test asserts the callback ran, publication occurred, and the boot coroutine is cancelled.

I independently ran both claimed sweep mutations:

- Removed the post-unlink `imageBearingFilesProvenAbsent()` re-stat: the survivor test failed at line 370.
- Removed the total catch: the throwing-step test failed with the injected `IOException` at line 403.

The `MUTATION UNIQUELY CAUGHT: NONE` label is honest. The real-cancellation test is useful characterization of the current non-suspending callback/`withContext` behavior; there is no stronger containment mutation it can honestly claim to distinguish.

## D — MainActivity post-destroy comment

Both halves are true. `destroy()` verifies with `File.exists()` at `VaultImageStore.kt:1126-1127`; an indeterminate stat returns false, so a surviving file can pass that check. If directory sync succeeds, marker retirement can follow, making `{image survives, confirmed absent}` reachable on a pathological filesystem.

Routing is fail-closed: a later indeterminate image stat makes `imagePresent=false` and `vaultProvenAbsent=false`; with the confirmed marker retired, `bootRoute` falls through to `LOCKED`. If the stat becomes determinate while the file survives, `imagePresent=true` also yields `LOCKED`. No finding.

## E — Other docstring corrections

Both corrected docstrings are accurate. Production calls `runBootReconcile` with `scope`, `claim`, `sweep`, `publish`, and `afterPublish`, but omits `ioDispatcher`, relying on its `Dispatchers.IO` default. Production also passes the `afterPublish` cache-clear call bare. The separate stale adjacent implementation comment is reported above.

## F — Test suite

Command run independently:

`cd apps/android && ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest`

Result: exit 0; 491 total, 488 passed, 0 failures, 0 errors, 3 skipped. No JNA workaround was needed.

## G — Other delta issues

Only the two LOW findings above. `git diff --check aa380c1..HEAD` is clean. The commit message's +4 test and 491-total claims are correct; its unconditional self-healing wording is not.
