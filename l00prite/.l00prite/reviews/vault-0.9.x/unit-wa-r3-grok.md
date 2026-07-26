I'll review the ROUND-2 fix delta as an independent security reviewer: first the project protocol and the full unit diff, then each claimed fix against source.Next I'll pull the full unit diff and the fix commit, then map every call site and consumer against source.Reading the core implementation and tests in full to verify every fix and focus item against source.Verifying create/destroy/retireLegacyImage order, consumers of boot state, and tests next.I found a possible hold-vs-delete routing race; verifying it against `completeTerminalWipe` and then running the full unit test suite.Disk is full — clearing disposable build caches so we can run the tests.The 483 count matches, but many failures look environmental (native/init). Checking whether the W-A suites themselves passed.Failures are JNA temp extraction, not test logic. Re-running with a writable `java.io.tmpdir`.Confirming one more detail on the delete/session collector race, then finalizing the report.# Unit W-A Round-3 Security Review (blind) — fix delta `54a41bf` + unit `main...HEAD`

**Scope reviewed from source:** `git diff main...HEAD` (a98677f + 0d348b4 + 54a41bf) and `git show 54a41bf`, plus the production call graph and WRITER/READER writers (`create`, `destroy`, `retireLegacyImage`, `createVaultAndPublish`).

**Suite (this worktree):** after fixing JNA tmp extraction (`jna.tmpdir` / `java.io.tmpdir` → writable dir — pure environment), XML aggregate:

| Metric | Observed |
|--------|----------|
| Total | **483** |
| Failures | **0** |
| Passed | **480** |
| Skipped | **3** (I2P live: `probeReportsReady`, `healthzReturnsOkThroughTunnel`, `webSocketHandshakeReachesServer`) |

Matches the commit claim.

---

## Round-2 fix verification (each claimed fix)

| Claim | Real? Complete? Safe? |
|-------|------------------------|
| **Dispatcher structural fix** — `deriveBootDecisionFromDisk` is `suspend` + internal `withContext(IO)` | **Yes.** `ZitroneApp.kt:248–256`. All three callers are bare suspend calls (`MainActivity.kt:646`, `:667`, `:783`). No other callers. Nothing reimplements the five-input assembly. |
| **`DeriveBootDecisionTest`** | **Yes, for the pure wrapper.** Tests probe suppression (confirmed / absent), fail-closed throw, hold passthrough, precedence — not a re-run of `bootRoute` alone. Does **not** instrument `deriveBootDecisionFromDisk`’s disk wiring (residual gap under I). |
| **Row 8 — gate-2 ELOOP** | **Yes.** Symlink on `vault.delete-confirmed` + DEK must survive (`SweepOrphanedResidueTest.kt:232–248`). Catches `!Files.notExists` → `File.exists()` on gate 2. Gate 1 already had row 5 ELOOP. |
| **Row 6c justification** | **Partially.** Production kdoc corrected (`VaultImageStore.kt:1381–1405`). **Test header for row 6b still carries the false proof** (see finding L1). |
| **Cancellation test actually cancels** | **Yes.** `BootReconcileOwnerTest.kt:242–248` throws `CancellationException` from `sweep`. |
| **Dead wrapper / rename / doc reattach** | **Yes.** No `sweepOrphanedVaultResidue` / `obliterationComplete` remain; `deleteLeftoverTmp` has its doc line. |

No silent coverage deletion found in 54a41bf (row 8 + 6 DeriveBootDecision tests + real cancellation added; renames only).

---

## Findings

### MEDIUM — second routing authority after session teardown can disagree on `residueSweepHold`

**Where:**  
- Session collector: `MainActivity.kt:771–790` (uses full `deriveBootDecisionFromDisk`, including hold)  
- Account-delete finally: `MainActivity.kt:1099–1112` (uses `hasVault()` + `serverDeleteConfirmed()` only)  
- Comment asserting they cannot disagree: `MainActivity.kt:1096–1098`

**Defect:** After W-A, a session→null is routed with the **carried** `residueSweepHold`, while a successful account-delete path still decides Onboarding from two fresh stats and **ignores** the hold. The delete handler’s own comment claims “the two cannot disagree.”

**Reachable disagreement:**  
1. Cold start with residue, sweep returns `SWEPT_NOT_DURABLE` → `residueSweepHold=true`, route `LOCKED` (`bootRoute` hold arm).  
2. User creates a vault mid-process from the lock screen (triple-entry / `attemptPassphrase` — Onboarding is withheld by hold, but create from lock still works).  
3. User account-deletes. `lockIf` nulls the session → collector suspends into `deriveBootDecisionFromDisk` → with hold and no image → **`LOCKED`**. Delete finally → **`ONBOARDING`**. Last writer wins; collector can clobber a successful delete onto the lock screen for the rest of the process.

**Why it matters:** Same family the parent unit burned rounds on: *two consumers of the same routing moment, one with a weaker/different input set*. Not vault remanence (destroy still ran), but a process-lifetime wrong post-delete surface and a falsified invariant in source.

**Concrete fix (pick one, keep a single authority):**  
1. **Preferred:** On confirmed durable destroy (files gone, markers retired), clear `residueSweepHold.value = false` (destroy’s own dirSync supersedes a prior non-durable orphan sweep), **and** route delete success through `deriveBootDecisionFromDisk` (or the pure `bootRoute` with the same five inputs).  
2. Or: session collector, on unlocked→null, must not re-apply cold-start hold when a just-completed destroy already proved durable absence.  
3. Delete the “cannot disagree” comment only after the code makes it true.

---

### LOW — row 6b test kdoc still states the false intent-gate proof

**Where:** `SweepOrphanedResidueTest.kt:163–167`

**Defect:** Still claims “an intent alone never accompanies an absent image in a legitimate delete state” and leans on `create()` clearing markers before the DEK. Production table (row 6c, `VaultImageStore.kt:1381–1405`) correctly admits the state **is** reachable via `createVaultAndPublish` → `retireLegacyImage()` **before** `create()`.

**Why it matters:** This unit’s history is false justifications surviving mechanical fixes. Behaviour of 6b (sweep intent+residue) is right; the **proof text** is still wrong.

**Fix:** Replace 6b’s unreachability claim with the 6c reasoning (retirement already destroyed the only openable image; sweep because image is gone, not because the state cannot occur). Optionally add an explicit `row 6c` test name.

---

### INFO — residual coverage / comment nits (not merge blockers)

1. **`deriveBootDecisionFromDisk` disk wiring untested** — `DeriveBootDecisionTest` exercises the pure function only; a constant `residueSweepHold = false` inside the suspend wrapper would not fail that suite.  
2. **Splash re-check comment** still says “before `withContext`” (`MainActivity.kt:647–648`) after the outer `withContext` was removed; the re-check is still valid (suspend is inside the helper).  
3. **`BootReconcileOwnerTest` already documents** that hold-before-done assignment order is not uniquely forced by tests (honest; production sets hold then done).

---

## Explicit verdicts A–I

### A0 — Sibling call sites of round-1 fixes
**Pass.** One derivation, three bare callers, full five-input set, no defaults. Standalone legacy `LaunchedEffect` is gone (main had it; HEAD does not). Gate 2 present + ELOOP covered (rows 7–8). No other unstattable tristate gate on the sweep (only gates 1–2 + clean check).

### A — Nothing burn-dependent survived the cut
**Pass.** Grep over Android sources: no `burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`, `BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion`.  

`onBurn` on HEAD is byte-identical to main (`UNIFORM_FAILURE` + `unlocking = false` only) — verified with `git show main:…MainActivity.kt`.

### B — Coupling line cleanly severed
**Pass.** No `signalBurnCompleted`, no half-removed burn-completion state, no writer-less burn fields.

### C — Excluded healers leave no dangling refs
**Pass.** No references to `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers`.  

Independently: `create()` writes DEK only after markers are proven clear and DEK-first before bin (`VaultImageStore.kt:505–546`). `destroy()` writes confirmed marker durably **before** unlinks (`:1108–1111`). Trigger states for those healers are not constructed by W-A writers.

### D — W-A standalone and strictly better than main
**Pass (with MEDIUM caveat above, which is worse UX in a niche case, not worse residue safety).**  

On main, Splash routes `!vaultExists` → Onboarding with `hasVault()` = bin-only, so `{bin absent, dek/tmp present}` onboards over residue. W-A sweeps (or holds) first. No ordinary path is made less safe than main for residual ciphertext.

### E — Sweep gate (both directions) + table completeness
**Pass for mechanism; LOW for stale test proof.**

| Direction | Result |
|-----------|--------|
| Wrongly **deletes** | Live vault refused (gate 1 + row 4/5). Confirmed-delete ownership refused (gate 2 + rows 7/8). Intent alone does not refuse (by design; 6b). |
| Wrongly **strands** | Genuine orphans swept (rows 1–3). Intent does not strand complete `bin.tmp` (6b). |

**No delete-intent gate:** verified against `destroy()` (confirmed before unlink) and `create()` (clears markers before vault bytes). Intent gate would only strand. Row 6c correctly records the retire-before-create crash; action was always sweep.

No additional missing **behavioural** row found beyond the already-documented 6c.

### F — Verdict carried, not re-derived (boot consumers)
**Pass for the three boot consumers; MEDIUM gap on account-delete success.**

| Consumer | After `bootReconciled`? | Full inputs / carried hold? |
|----------|-------------------------|-----------------------------|
| Splash decision (`:643–656`) | Yes (`bootDone`) | Yes via `deriveBootDecisionFromDisk` |
| Post-boot re-derive (`:658–681`) | Yes (`first { it }`) | Yes |
| Session collector (`:771–790`) | Boot already done before unlock | Yes (includes hold) |
| Delete success / `onRetryDestroy` | N/A (post-auth destroy) | **No** — stats only (finding M1) |

Splash no longer routes on animation end. No second legacy authority.

### G — `runBootReconcile` contract
**Pass against source and tests.**

- Once-only CAS (`:1144`, test “second start…”)  
- Fail-closed default `SWEPT_NOT_DURABLE` (`:1147`)  
- `publish` in `finally` (`:1161–1165`) including cancellation  
- Claim not re-run after cancel (test “retry after a cancelled run…”)  
- Durable verdict not overwritten (test)  

`afterPublish` is outside `finally` (hygiene only; not routing) — acceptable.

### H — Test suite (independent run)
**Pass — observed 483 / 0 failures / 480 passed / 3 skipped** (I2P), after environment tmpdir fix for JNA. Count matches claim.

### I — Test quality
**Mostly solid; residual gaps.**

- Not vacuous: ELOOP rows assert DEK survival; owner tests drive real waiters; cancellation test cancels.  
- Row 6b header claims unreachability it does not prove (L1).  
- `DeriveBootDecisionTest` tests pure logic, not disk assembly (INFO).  
- Untested failure shape still open: **session-null vs delete-finally disagreement under `residueSweepHold=true`** (M1); Compose delivery of boot decision across recreation (already admitted in SECURITY_MODEL).

---

## READY TO MERGE?

### **No — not while M1 is open.**

Round-2 fixes are real, structural, and did not silently gut coverage. Extraction boundaries (burn cut, healers, onBurn stub, carried boot verdict for Splash/re-derive/session) hold. Suite is green at the claimed numbers.

The unit still has **one MEDIUM of the exact family this split was meant to stop shipping**: *an authoritative hold exists, and a parallel post-session route decides without it*, under a comment that says the two paths cannot disagree. Fix M1 (clear hold on durable destroy **and/or** one shared decision for delete success), fix the stale 6b kdoc, re-run the suite — then this is merge-ready.

I would not invent further findings; the rest of the extraction and 54a41bf hold under re-derivation from source.
