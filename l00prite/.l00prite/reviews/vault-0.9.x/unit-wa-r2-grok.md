I'll act as an independent security reviewer for this extraction unit and fix delta: first load the project protocol, then verify each round-1 fix and focus items A–I against source.Next I'll pull the full unit and fix-delta diffs and map the boot-routing / residue-sweep surface.Reading the core boot-routing and sweep implementations, then the three consumers and writer paths.Running the unit-test suite and checking remaining call sites for weaker boot routing.Gradle wrapper can't write its lock under `/root/.gradle`; retrying with a writable Gradle home.Failures look like a native/init environment issue, not product logic. I'll isolate the root cause and re-run the W-A suites.JNA can't extract into the default temp dir; I'll point `java.io.tmpdir` at a writable executable path and re-run.Sibling worktrees got a green suite with the shared Gradle home. Retrying with that setup.Suite is green. Parsing exact totals and reverting the local build.gradle workaround.# Independent Security Review — Unit W-A Round 2 (fix delta `0d348b4`)

**Scope reviewed:** `main...HEAD` (`a98677f` extraction + `0d348b4` fixes), primary focus on `0d348b4`.  
**Method:** Full source re-derivation (git, whole files, writer/reader paths, consumer enumeration). Suite re-run in this worktree. No other reviewers’ reports consulted.

---

## READY TO MERGE: **YES**

No CRITICAL or HIGH defects found. Round-1 fixes are real, complete, and do not reintroduce the parent-unit failure family. Residual items are LOW/INFO only and are not merge blockers.

---

## Round-1 fix verification

| Claimed fix | Verdict | Evidence |
|---|---|---|
| Delete standalone legacy `LaunchedEffect` | **REAL** | Main still has the effect (`git show main:…MainActivity.kt` ~679–692). HEAD has only a comment at `MainActivity.kt:726–734`; no remaining `LaunchedEffect` that sets `Route.Onboarding` from `isLegacyImage()`. |
| Restore row-7 confirmed-refuse test | **REAL** | `SweepOrphanedResidueTest.kt:203–215` has `@Test` + asserts `NO_MUTATION` and surviving DEK. Suite executed green. |
| Single derivation `deriveBootDecision` | **REAL** | Production `bootRoute(` only from `deriveBootDecision` (`ZitroneApp.kt:1194`). All three consumers call `deriveBootDecisionFromDisk()` (`MainActivity.kt:646, 667, 783`). |
| Post-boot session re-check | **REAL** | `MainActivity.kt:672` after `withContext`. |
| Enumerated onboarding expectations | **REAL** | `BootRouteTest.kt:236–247` explicit set, not formula. |
| Rename durable-verdict test | **REAL** | `BootReconcileOwnerTest.kt:206` name matches behavior (no fake cancellation). |
| SECURITY_MODEL overclaims | **REAL** | `docs/SECURITY_MODEL.md:929–936` matches mechanism (legacy arm; refuse = `NO_MUTATION`). |

---

## Findings

### LOW — session collector calls `deriveBootDecisionFromDisk` on the main thread

**Where:** `MainActivity.kt:783` (session `collect` branch)  
**Defect:** `deriveBootDecision` kdoc (`ZitroneApp.kt:1175–1176`) requires off-main because `isLegacyImage()` does ~1 MiB outer decrypt. Splash (`:646`) and post-boot (`:667`) use `withContext(Dispatchers.IO)`; the session collector does not.  
**Why it matters:** ANR risk / contract inconsistency after the “one derivation” fix. Routing still passes the full input set including carried `residueSweepHold` — not a weaker-authority bug.  
**Fix:**  
```kotlin
val snap = withContext(Dispatchers.IO) { container.deriveBootDecisionFromDisk() }
```
inside that branch (and re-check session/unlocked if needed after suspend).

### INFO — gate 2 lacks a load-bearing indeterminate test (gate 1 has one)

**Where:** `VaultImageStore.kt:1398–1401` vs `SweepOrphanedResidueTest.kt:228–268`  
**Defect:** Gate 1 has ELOOP-on-`vault.bin` mutation separation (`row 5`). Gate 2 (confirmed marker) is only covered by present-marker (`row 7`) and weak ENOTDIR-on-baseDir (`rows 5 and 8`), which the suite itself admits does not separate fail-open from fail-closed.  
**Why it matters:** Code is correct (`!Files.notExists(serverDeletedFile)`), but deleting gate 2’s tristate would not be uniquely caught by an indeterminate-marker case.  
**Fix:** ELOOP (or similar) on `vault.delete-confirmed` with a survivable DEK; assert DEK survives and `NO_MUTATION`.

### INFO — burn vocabulary leftover name only

**Where:** `VaultImageStore.obliterationComplete()` (`:1317`), used as `vaultProvenAbsent()`  
**Defect:** Name suggests wipe/burn; behavior is “image-bearing files proven absent.” No burn mechanism attached.  
**Fix (optional):** rename to match `vaultProvenAbsent` / `imageBearingFilesProvenAbsent` in a hygiene commit.

---

## Explicit verdicts A–I

### A0 — Sibling call sites of every round-1 fix

**PASS.**  
- **Derivation:** all three consumers → `deriveBootDecisionFromDisk()` → full five-input `bootRoute`. No leftover hand-rolled weaker assembly.  
- **Legacy effect:** no other cold-start path sets onboarding from legacy alone. Unlock-time `PassphraseOutcome.LegacyImage` (`MainActivity.kt:857–863`) remains a backstop; with `serverDeleteConfirmed` + v2, `bootRoute` returns `DELETE_INCOMPLETE` first, so that screen is not presented via boot.  
- **Gates:** row 7 restores gate 2 present-marker coverage; gate 1 has ELOOP coverage; no other destructive boot gate is untested at the present-state level (see INFO for gate-2 indeterminate).

### A — Nothing burn-dependent survived the cut

**PASS.**  
Repo-wide grep for `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion`, `completeInterruptedBurn`, `reconcileOrphanedBurnMarkers`: **zero hits**.

`onBurn` is **byte-identical** to main:
```kotlin
val onBurn: () -> Unit = {
    lockError = VaultUnlockRouter.UNIFORM_FAILURE
    unlocking = false
}
```
(verified by extracting both from `git show main:` and HEAD).

### B — Coupling line cleanly severed

**PASS.** No `signalBurnCompleted`, no half-removed burn state fields, no dangling writers/readers for burn completion. Residue-sweep hold is self-contained (`residueSweepHold` / `bootReconciled` published only by `runBootReconcile`).

### C — Excluded healers leave no dangling callers

**PASS.**  
- **Unreachable without duress wipe (independent of comments):**  
  - `create()` writes DEK durably **before** `vault.bin` (`VaultImageStore.kt:533–546`) → `{dek, no bin}` without burn.  
  - `destroy()` calls `writeDurableMarker(serverDeletedFile)` **before** unlinks (`:1108–1114`) → every real account-delete unlink already carries confirmed.  
- No references to `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers` in source or docs under this unit.

### D — W-A correct standalone; “strictly better than main”

**PASS (claim holds).**  

Main Splash (`git show main:…`):
```text
serverDeleteConfirmed → DeleteIncomplete
vaultExists (hasVault = bin only) → Locked
else → Onboarding
```
So `{bin absent, dek/tmp present}` → **Onboarding** while residue (including a complete image in `vault.bin.tmp`) remains.

W-A: sweep first; onboarding only if `vaultProvenAbsent` and not `residueSweepHold` (`bootRoute` `:1245–1251`). Non-durable sweep → **LOCKED**, never onboarding over resurrectable residue. No state is made worse for the claimed hazard.

### E — Sweep gate (both directions + no intent gate)

**PASS.**

| Direction | Result |
|---|---|
| Wrongly deletes | Gate 1: live / unstattable image → `NO_MUTATION` (ELOOP test asserts DEK survives). Gate 2: confirmed present → refuse (row 7). |
| Wrongly strands | Intent-only + residue → **swept** (row 6b), not stranded. |
| No `delete-intent` gate | Justified by source: `destroy()` confirms before unlink; `create()` clears both markers durably before DEK write. Intent+absent-image is not a legitimate D2c mid-unlink shape. |

**WRITER/READER table completeness (hunted missing rows):** rows 1–9 / 6b match writers I re-derived. No missing ownership row that would authorize onboarding over recoverable ciphertext or sweep D2c-owned state. (Biometric wrap / prefs are out of this file set by design.)

### F — Verdict is carried, not re-derived

**PASS for cold-start consumers.**

| Consumer | Ordered after publish? | Full inputs? | Uses carried hold? |
|---|---|---|---|
| Splash decision (`:643–656`) | Yes (`bootDone`) | Yes via `deriveBootDecisionFromDisk` | Yes |
| Post-boot re-derive (`:665–680`) | Yes (`bootReconciled.first`) | Yes | Yes |
| Session collector (`:771–790`) | Not gated on boot (only fires after prior unlock; hold is process-scoped) | Yes | Yes (`residueSweepHold.value`) |
| SplashScreen itself | N/A — only sets `splashFinished` (`:1232`) | — | — |

No consumer re-stats “clean” as a substitute for `SWEPT_NOT_DURABLE`. Hold is set **before** `bootReconciled` in `publish` (`ZitroneApp.kt:276–278`).

Non-boot onboarding paths (`onRetryDestroy`, account-delete `finally`, `LegacyImage` unlock) use disk/session outcomes of those operations, not cold-start residue claims — appropriate and pre-existing patterns.

### G — `runBootReconcile` contract

**PASS against source and tests.**

| Contract | Source | Test |
|---|---|---|
| Once-only claim | CAS in `startBootReconcile` / `claim()` | second start; retry after cancel |
| Publish in `finally` | `:1155–1158` | cancel mid-work releases waiter |
| Fail-closed default | `SWEPT_NOT_DURABLE` init `:1141` | throw → hold true |
| Durable verdict not overwritten | `publish(result == SWEPT_NOT_DURABLE)` | durable → hold false |
| Cancellation propagates | rethrows `CancellationException` | stranding test |

### H — Independent test suite run

**Observed in this worktree** (after local-only JNA tmpdir workaround for sandbox `Permission denied` extracting `libjnidispatch.so`; product sources unchanged for the run result):

| Metric | Observed |
|---|---|
| Total | **476** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **3** |
| Passed | **473** |

Matches the commit claim. W-A suites: BootRoute **10/10**, BootReconcileOwner **8/8**, SweepOrphanedResidue **13/13**.

First raw run without JNA tmpdir: 161 failures, all `Could not initialize class com.sun.jna.Native` (environment), not product assertions.

### I — Test quality

**Mostly strong; residual gaps are honest.**

- Not vacuous: sweep tests assert DEK/image survival, not only return codes; owner tests drive real waiters.  
- Headers that overclaimed were fixed in round 1 (durable-verdict rename; hold-before-done honesty note at `:84–96`).  
- Logic tested against production `bootRoute` / `sweepOrphanedResidue` / injected `runBootReconcile`, not copies.  
- **Still untested failure shapes:**  
  1. Compose delivery of the carried hold across Activity recreation (acknowledged in `SECURITY_MODEL.md:940–942`).  
  2. Gate-2 indeterminate marker (INFO above).  
  3. Session-collector off-main dispatch (LOW above).

---

## Merge recommendation

**READY TO MERGE.**

Unit W-A stands alone without burn coupling. Round-1 closed the extraction HIGH (second legacy authority) and the gate-2 coverage hole, and collapsed three safety derivations into one owner. The destructive sweep is gated fail-closed; the durability verdict is carried into routing. Remaining items are LOW/INFO and do not block merge.
