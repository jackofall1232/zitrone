I'll perform an independent security review of unit W-A from source only. Starting with the project protocol and the full `main...HEAD` diff.Pulling the full unit diff and reading every changed source file against main.Reading the production sources in full and comparing critical paths to main.Checking `create`/`destroy` ordering, residual burn references, and every onboarding route path.Checking whether the leftover legacy effect can still bypass `bootRoute`, and how onboarding create handles legacy images.Investigating test failures — likely environment/setup rather than unit logic.# Independent Security Review — Unit W-A (Round 1)

**Commit:** `a98677f` — cold-start orphan residue sweep + fail-closed boot routing  
**Scope:** `git diff main...HEAD` (7 files; production + tests + SECURITY_MODEL)  
**Method:** source-only re-derivation; tests run in this worktree

---

## Verdict: **NOT READY TO MERGE**

The sweep, durability carrying, and pure `bootRoute` decision are sound. Extraction left one cold-start path that still bypasses the new decision function — the same “second path / weaker inputs” family the parent unit kept regenerating. That blocks a clean merge.

---

## Findings

### F1 — HIGH — `MainActivity.kt:761–778`

**Defect:** A pre-existing cold-start `LaunchedEffect` still routes to `Route.Onboarding` on legacy detection **without** waiting for boot reconciliation, **without** reading `residueSweepHold`, and **without** calling `bootRoute` / checking `serverDeleteConfirmed`.

```761:778:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
    // 0.9.2 upgrade safety: a PRIOR-format (v2 / 0.9.1) image is unsafe to unlock ...
    LaunchedEffect(Unit) {
        if (vaultExists && container.session.value == null) {
            val legacy = withContext(Dispatchers.IO) {
                runCatching { container.isLegacyImage() }.getOrDefault(false)
            }
            if (legacy && (route == Route.Splash || route == Route.Locked)) {
                vaultExists = false
                route = Route.Onboarding
            }
        }
    }
```

**Why it matters:** This is exactly the Round-3 defect that `BootRouteTest` claims is fixed by putting legacy precedence *inside* `bootRoute`:

> “Legacy detection used to live in a SEPARATE effect that set `Route.Onboarding` on its own, without awaiting boot and without consulting the confirmed marker: with `{v2 image + vault.delete-confirmed}` it preempted `DeleteIncomplete`…”  
> (`BootRouteTest.kt:111–117`)

The pure function and the three wired consumers *do* use full inputs. This fourth path does not. That is the parent unit’s recurring class: *an authoritative result exists and a consumer uses something weaker* / *a second path decides the same thing*.

**Mitigation observed:** the post-boot re-derive (`MainActivity.kt:709–711`) can still force `DeleteIncomplete` even from Onboarding. That usually heals the confirmed+legacy case after publication — but the race window and the second path remain, and the suite’s claim that the defect is structural is false while this effect lives.

**Concrete fix:** Delete this `LaunchedEffect`. Splash / re-derive / session collector already pass `legacyImage` into `bootRoute`; unlock still has `PassphraseOutcome.LegacyImage` as a backstop.

---

### F2 — MEDIUM — `SweepOrphanedResidueTest.kt` (suite header vs coverage)

**Defect:** The suite claims to walk the WRITER/READER table “row by row” (`SweepOrphanedResidueTest.kt:39–40`), but **row 7** (`{delete-confirmed present, …}` → REFUSE via gate 2) has **no test**. Rows 1–6, 6b, 5/8, 9, durability, and idempotence are covered; confirmed-marker refuse is not.

**Why it matters:** Gate 2 is the D2c ownership bar (`VaultImageStore.kt:1398–1401`). Removing it would not fail the current suite. That matches the brief’s warning about headers claiming mutations they cannot catch.

**Concrete fix:** Add a test: write `vault.delete-confirmed` + a stray `vault.dek` (bin absent) → expect `NO_MUTATION` and **DEK still present**.

---

### F3 — INFO — stale Unit-W naming in new tests

`BootRouteTest.kt:12` and `BootReconcileOwnerTest.kt:25` still say “PUCKER BURN Unit W”. Not a security defect; signals incomplete extraction hygiene.

---

### F4 — INFO — Compose delivery untested (already disclosed)

`docs/SECURITY_MODEL.md` correctly states that Compose wiring / Activity recreation is inspection-only. Owner lifecycle *is* tested via injected `runBootReconcile`. Residual gap is real but acknowledged.

---

## Explicit verdicts A–I

### A. Nothing burn-dependent survived the cut — **PASS**

Repo-wide search under `apps/android` + `docs` for  
`burnVault`, `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`,  
`BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion`,  
`completeInterruptedBurn`, `reconcileOrphanedBurnMarkers` → **no hits**.

`onBurn` body vs `git show main:` — **byte-identical**:

```kotlin
val onBurn: () -> Unit = {
    lockError = VaultUnlockRouter.UNIFORM_FAILURE
    unlocking = false
}
```

(`MainActivity.kt:893–896` vs main `786–789`)

---

### B. Coupling line cleanly severed — **PASS**

No `signalBurnCompleted`, no `obliterated =`, no half-removed burn completion state writers/readers in this unit.

---

### C. Excluded healers left no dangling refs — **PASS**

No references to `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers`.

Independently verified trigger states are unreachable without duress wipe:

| Writer | Ordering (source) |
|--------|-------------------|
| `create()` | Clears markers first (`VaultImageStore.kt:505–510`), then DEK durable **before** bin (`533–546`) → `{bin absent, dek present}` has **no** markers |
| `destroy()` | `writeDurableMarker(serverDeletedFile)` **before** any unlink (`1108–1114`) → every real delete unlink carries **confirmed** |

No comments still assume those healers run.

---

### D. W-A standalone + “strictly better than main” — **PASS** (with F1 caveat on legacy path)

**Main Splash** (`git show main:…MainActivity.kt`):

```
confirmed → DeleteIncomplete
vaultExists (hasVault/bin) → Locked
else → Onboarding
```

So `{bin absent, dek/tmp present}` → **Onboarding**, later `create()` overwrites DEK.  

**W-A:** sweep deletes residue when gates pass; onboarding only if `!hold && vaultProvenAbsent` (`bootRoute` `ZitroneApp.kt:1193–1199`). Residue no longer presents as a clean install. No main state is made worse for the residue case.

F1 does not reverse the residue win; it is a leftover parallel path for **legacy+bin-present** routing.

---

### E. Sweep gate (both directions + no intent gate) — **PASS**

**Implementation** (`VaultImageStore.kt:1394–1424`):

1. Gate 1: `!Files.notExists(bin)` → refuse (present **or** indeterminate)  
2. Gate 2: `!Files.notExists(serverDeletedFile)` → refuse (confirmed present **or** indeterminate)  
3. Already clean → `NO_MUTATION`  
4. Else mutate → re-stat all image-bearing files → `dirSync` must be `DURABLE` → else `SWEPT_NOT_DURABLE`  
5. Catch-all past mutation point → `SWEPT_NOT_DURABLE` (never false `NO_MUTATION`)

**Writer/reader (re-derived, not only self-consistent):**

| # | State | Writer | Gate |
|---|--------|--------|------|
| 1 / 1b | `{dek, no bin, no markers}` | interrupted `create` / `retireLegacyImage` | SWEEP |
| 2 | `{dek.tmp, no bin}` | crash in `renameIntoPlace(dek)` | SWEEP |
| 3 | `{dek, bin.tmp, no bin}` | crash after DEK barrier | SWEEP (`bin.tmp` = complete outer image) |
| 4 | bin present | live vault | REFUSE (gate 1) |
| 5 | bin indeterminate | FS fault | REFUSE (`Files.notExists` only true if proven absent) |
| 6 | intent + bin | D2c in flight | REFUSE (image, not intent) |
| 6b | intent + residue, no bin | constructed / non-D2c | SWEEP (no intent gate) |
| 7 | confirmed present | D2c unfinished | REFUSE (gate 2) — **untested (F2)** |
| 8 | confirmed indeterminate | FS fault | REFUSE (gate 2) |
| 9 | empty | fresh install | NO-OP |

**No `delete-intent` gate — verified against writers, not accepted from kdoc:**

- `destroy()` writes confirmed durably before unlinks → real unlinks already hit gate 2  
- Intent is written while image still exists; `create()` clears markers before DEK  
- An intent-only + residue state is not a legitimate D2c mid-unlink; an intent gate would only strand `bin.tmp` (row 6b test asserts this)

**Wrongly deletes:** only proven-no-bin, no-confirmed residue (orphans / never-completed vaults). Live vaults and D2c-confirmed unfinished deletes are refused.  
**Wrongly strands:** refuse paths leave residue → `vaultProvenAbsent=false` → `LOCKED` (not onboarding). Acceptable fail-closed.

---

### F. Verdict carried, not re-derived — **FAIL** (F1)

**Sound core:**

```1143:1147:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
        } finally {
            publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)
        }
```

`publish` sets `residueSweepHold` then `bootReconciled` (`ZitroneApp.kt:263–266`). Hold is the durability bit a fresh stat cannot recover.

**Consumers that use full `bootRoute` after publication:**

| Consumer | Site | Inputs | After `bootDone`? |
|----------|------|--------|-------------------|
| Splash decision | `MainActivity.kt:643–677` | full 5 args, `residueSweepHold.value` | yes (`splashFinished && bootDone`) |
| Post-boot re-derive | `679–717` | full 5 | yes (`bootReconciled.first { it }`) |
| Session null collector | `815–846` | full 5 | uses carried hold (process-scoped) |

No defaults on `bootRoute` parameters (compile-time completeness).

**Consumer that does not:** legacy `LaunchedEffect` (F1) — fails F.

Other Onboarding setters (`onRetryDestroy`, account-delete finally, `PassphraseOutcome.LegacyImage`) are post-auth / post-destroy paths with their own disk criteria, not cold-start residue consumers.

---

### G. `runBootReconcile` contract — **PASS**

Verified in `ZitroneApp.kt:1118–1149` and `BootReconcileOwnerTest` (8/8 green):

| Property | Source | Test |
|----------|--------|------|
| Once-only | `claim()` CAS before launch | second start does not re-sweep |
| Publish in `finally` | including cancel | cancelled claimant releases waiter |
| Fail-closed default | `result = SWEPT_NOT_DURABLE` | throw / cancel → hold true |
| Claim not stranded | publish always runs | waiter released after cancel |
| Durable → no hold | | durable / `NO_MUTATION` publish no hold |
| Verdict carried into hold | | waiter observes hold true for non-durable |

Honest gap (documented in test): production `hold`-before-`done` ordering inside the injected `publish` lambda is not uniquely forced by the suite (`BootReconcileOwnerTest.kt:88–96`). For `.value` readers after `done`, final values are fine either way.

---

### H. Test suite (this worktree) — **PASS counts match claim**

First run failed 160× on `com.sun.jna.Native` (environment tmp). Re-run with writable JNA/java tmp:

```
./gradlew testDebugUnitTest
→ BUILD SUCCESSFUL
→ 475 tests completed, 0 failed, 3 skipped
→ passed=472, failed=0, skipped=3  (XML parse)
```

W-A suites: **BootRouteTest 10**, **BootReconcileOwnerTest 8**, **SweepOrphanedResidueTest 12** (= 30), all green.

Matches commit claim: **475 / 0 failures / 472 passed / 3 skipped**.

---

### I. Test quality — **PARTIAL**

| Check | Result |
|-------|--------|
| Vacuous tests? | No strong vacuous cases; owner tests assert waiter damage, not only CAS |
| Header claims mutation it cannot catch? | **Yes (F2):** “row by row” skips row 7; one owner test honestly retracts a prior false ordering claim |
| Logic copy vs real logic? | **No** — tests call production `bootRoute`, `runBootReconcile`, `sweepOrphanedResidue` |
| Untested failure shapes | (1) Gate 2 / confirmed refuse (F2); (2) leftover legacy path still in production (F1); (3) Compose delivery (disclosed); (4) integration “publish → consumer reads hold” end-to-end in UI |

---

## Summary table

| ID | Sev | Location | Issue |
|----|-----|----------|--------|
| F1 | **HIGH** | `MainActivity.kt:761–778` | Legacy cold-start effect bypasses `bootRoute` / confirmed / boot gate |
| F2 | **MEDIUM** | `SweepOrphanedResidueTest` | Claims full WRITER/READER table; row 7 (confirmed refuse) untested |
| F3 | INFO | Test headers | Stale “Unit W / Pucker Burn” labels |
| F4 | INFO | Compose path | Delivery untested (disclosed) |

**A–E, G–H:** pass  
**F:** fail (F1)  
**I:** partial (F2 + disclosed gaps)

---

## Merge decision

**NOT READY TO MERGE** until **F1** is fixed (delete or fully fold the legacy `LaunchedEffect` into the boot-gated `bootRoute` consumers). **F2** should land in the same pass so gate 2 cannot regress silently.

An honest clean pass was expected if the extraction were complete; the sweep + carried-verdict design holds. The blocker is extraction incompleteness on cold-start routing, not the sweep primitive itself.
