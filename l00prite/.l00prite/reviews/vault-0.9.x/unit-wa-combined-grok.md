I'll review the combined Unit W-A delta independently against source: read the protocol, inspect the commit range, and verify each binding claim A–H from the code and tests.Next I'll pull the full diffs and the production sources those claims describe.I'll read the routing, destroy, and sweep paths next, then check whether 157c1f6 is truly comment-only.Gradle hit a permission error on the shared wrapper cache; I'll rerun tests with an isolated `GRADLE_USER_HOME`.Waiting for the test suite to finish, then verifying a few more source details.The suite reported 164 failures — likely native/environment init, not product logic. Digging into the root cause and rechecking the claimed numbers.The working env uses `JAVA_TOOL_OPTIONS` for JNA tmpdirs. Re-running the suite with that.Disk is full — freeing space, then re-running the suite.# Independent Security Review — Unit W-A Follow-up Combined Delta  
**Range:** `aa380c1..HEAD` (`bdde066` + `157c1f6`)  
**Reviewer posture:** blind to other lenses; all claims checked against source in this worktree.

---

## Verdict: **READY TO MERGE**

No CRITICAL/HIGH/MEDIUM defects. Corrections in `157c1f6` hold up against code. Sole behavioural change (`onRetryDestroy` → single derivation) is sound fail-closed routing. Residual strand is real, remote, restart-recoverable, and correctly tracked — not a merge blocker.

---

## A. Is `157c1f6` really comment-only?

**Verdict: YES.**

Diff of `apps/android/app/src/main` for `157c1f6` was filtered for non-comment `+/-` lines: **0 hits**. Changes are only `//` and kdoc-style prose in:

- `MainActivity.kt` (onRetryDestroy comment block)
- `ZitroneApp.kt` (afterPublish containment comment)

**`onRetryDestroy` executable body is byte-identical to `bdde066`:**

```kotlin
if (deleteRetrying) return@retry
deleteRetrying = true
deleteRetryFailed = false
scope.launch {
    withContext(Dispatchers.IO) { runCatching { container.destroyVaultForAccountDeletion() } }
    val snap = container.deriveBootDecisionFromDisk()
    deleteRetrying = false
    if (snap.route == BootRoute.ONBOARDING) {
        vaultExists = false
        route = Route.Onboarding
    } else {
        deleteRetryFailed = true
    }
}
```

(Python strip-comments compare: IDENTICAL.)

**Behavioural change in the combined range lives only in `bdde066`:** success criterion moved from `!hasVault() && !serverDeleteConfirmed()` to `snap.route == BootRoute.ONBOARDING`.

---

## B. Are the corrections true?

### B.1 Production passes a BARE `afterPublish`; wrapper is only containment  
**TRUE.**

Production call site (`ZitroneApp.kt` ~285–289):

```kotlin
afterPublish = {
    // No local runCatching: runBootReconcile contains faults here by contract.
    retryPlaintextCacheClearIfNoVault()
},
```

Wrapper (`ZitroneApp.kt` ~1180):

```kotlin
withContext(ioDispatcher) { runCatching { afterPublish() } }
```

Comment at ~1172–1176 now matches code.

### B.2 Idempotent destroy: retry is SAFE, not guaranteed to SUCCEED  
**TRUE.**

`VaultImageStore.destroy` kdoc/code: idempotent unlinks; verify can still throw `DestroyFailed` if anything survives (`exists()` check ~1126). Persistent unlink/stat fault → destroy never completes markers-retired path → `bootRoute` stays off ONBOARDING → every retry sets `deleteRetryFailed = true`, no other in-app exit on that screen. Accurate.

### B.3 Old “held boot admits no session” justification is FALSE  
**TRUE.**

`bootRoute` (`ZitroneApp.kt` ~1287–1293):

```kotlin
serverDeleteConfirmed -> DELETE_INCOMPLETE
legacyImage -> ONBOARDING
vaultImagePresent -> LOCKED      // before hold
residueSweepHold -> LOCKED
vaultProvenAbsent -> ONBOARDING
else -> LOCKED
```

Hold + present image (no confirmed delete, not legacy) → **LOCKED via image arm** → unlock → session → in-session delete. Old coexistence claim was false.

### B.4 Coexistence only via fail-closed default? Attack the containment  
**TRUE as a practical claim; cancelled boot is the only realistic path.**

Hold is written only here:

```kotlin
// ZitroneApp.kt startBootReconcile publish
residueSweepHold.value = hold  // hold iff result == SWEPT_NOT_DURABLE
```

Cleared only by `destroySupersedesResidueHold` on the delete-completion path (`MainActivity.kt` ~1156–1161).

| Path to `SWEPT_NOT_DURABLE` | Image present? |
|---|---|
| Sweep returns `SWEPT_NOT_DURABLE` | **No** — only after gate 1 (`Files.notExists(bin)`), which refuses present/indeterminate bin (`VaultImageStore.kt` ~1416–1439) |
| Sweep throws, outer catch in `runBootReconcile` | Gate 1 returns `NO_MUTATION` without throwing for present bin; throw-before-gate-1 is not a real branch in current code (lock + `Files.notExists` don’t throw) |
| **Cancelled boot** (result stays default `SWEPT_NOT_DURABLE`) | **Yes** — hold published with whatever disk already has |

**No other production assignment** raises the hold with a live image. Containment claim stands; “throw escaping before gate 1” is belt-and-suspenders language, not a live second path.

### B.5 “Stronger on absence proof” vs formal strengthening; legacy arm  
**TRUE.**

State map of old success (`!present && !confirmed`) vs new (`route == ONBOARDING`):

| Divergence | Old | New | Meaning |
|---|---|---|---|
| `!present, !confirmed, !hold, !proven` | success | LOCK | fail-closed indeterminate / unproven absence — **hazard closed** |
| `!present, !confirmed, hold, *` | success | LOCK | hold strand |
| `present, !confirmed, legacy` | fail | ONB | legacy arm — **weaker than old on this abstract input** |

So not a formal strengthening over all five inputs. Post-destroy product: destroy of a modern vault does not leave a legacy image; `onRetryDestroy` is entered from `DeleteIncomplete` (confirmed marker). Legacy ONBOARDING is not a post-destroy product of this path. Claim is accurate; it does not undermine the fix.

### B.6 Net effect: one pathological stuck state added, one unsafe onboarding removed  
**ACCURATE for product semantics.**

- **Unsafe onboarding removed:** old success over `vault.bin` absent + markers retired while residue/temps/indeterminate still possible (because `hasVault()` is bin-only and destroy verify is `exists()`-based, proven-presence only). New requires `vaultProvenAbsent` (`Files.notExists` × four files) and no hold.
- **Stuck class already existed:** confirmed marker and/or surviving `vault.bin` already forced DeleteIncomplete.
- **Added pathological process-stuck case:** successful destroy/retry while `residueSweepHold` still true → not ONBOARDING → reported failure until process restart (see D).

---

## C. Enumeration claim

**Verdict: COUNTS CHECK OUT; “left alone” subjects are different claims.**

Grepped live tree:

| Fact | Claimed | Observed |
|---|---|---|
| production wraps / bare `afterPublish` / local runCatching | 3 instances, 3 correct | (1) call site ~287 “No local runCatching”, (2) wrapper comment ~1172 BARE, (3) `BootReconcileOwnerTest` header ~316 BARE — all consistent |
| “held boot admits no session” | 2 live, both corrected | `MainActivity` ~731–732 (labelled FALSE); `todos.md` ~105–106 (labelled FALSE). Historical quotes in ledger/todos checklist are refutations, not live false claims |
| “strictly stronger” (onRetry claim) | 1 corrected; 2 different left alone | MainActivity corrected. Remaining: `destroySupersedesResidueHold` kdoc ~1230 and `DestroySupersedesResidueHoldTest` ~169 — **destroy dirSync vs sweep’s unproven unlink** — accurate, different subject |
| “self-heals” (retry claim) | 1 corrected; others different | MainActivity no longer claims self-heal. Left: cache-clear kdoc ~294; marker-retire comment ~1146 — different subjects, not the same overclaim |

Enumeration rule in `failures.md` matches what this commit did. **No miscount finding.**

---

## D. The strand (end-to-end)

**Verified against source. Severity: LOW / tracked. Does not block.**

Chain:

1. **Hold + image:** cancelled boot → default `SWEPT_NOT_DURABLE` → `residueSweepHold=true` while `vault.bin` present.  
2. **Route LOCKED** (image arm) → unlock → session.  
3. **In-session delete** → first `destroy` fails → confirmed still set → `DELETE_INCOMPLETE`; `destroySupersedesResidueHold` does **not** clear hold (needs `vaultProvenAbsent && !serverDeleteConfirmed`).  
4. **`onRetryDestroy` has no hold supersede** (by design, deferred to 0.9.3).  
5. Successful retry: files gone, markers retired, but `hold` still true →  
   `bootRoute(..., residueSweepHold=true, vaultProvenAbsent=true) → LOCKED` →  
   `snap.route != ONBOARDING` → `deleteRetryFailed = true`.

**Restart-recoverable:** `residueSweepHold` is process-scoped `MutableStateFlow(false)`; next process re-runs reconcile over clean disk → no hold → ONBOARDING.

**Severity:** UX stuck after successful cleanup until force-stop/restart. Fail-closed (no unsafe onboarding). Remote precondition. Correctly tracked, not release-blocking.  

**Minor prose precision (INFO only):** comment says success is “reported as FAILURE … because the stale hold routes it to LOCKED.” Internally `snap.route` is LOCKED; Compose `route` stays `DeleteIncomplete` with a failure flag — user is not navigated to the lock screen. Effect is the same (no exit to onboarding).

---

## E. Uncovered behavioural change

**Verdict: Real residual gap; disclosure is honest; “would only duplicate bootRoute” is half rationalisation.**

- Sole behaviour change: `onRetryDestroy` consumer wiring.  
- `bootRoute` / derivation rows are well covered (`BootRouteTest` full table + 32-combo onboarding set).  
- Reverting this site to `!hasVault() && !serverDeleteConfirmed()` **would leave those tests green**.

An honest non-false-coverage test exists: assert the **consumer success predicate** on one **diverging** row, e.g.  
`(confirmed=false, present=false, hold=true, proven=true, legacy=false)` → must **not** treat as success.  
That uniquely catches reversion without re-tabling `bootRoute`.

**Severity: INFO** (disclosed; pure routing already tested; not a security hole).

---

## F. Nothing else moved

| Commit | `@Test` count (`apps/android/app/src/test`) |
|---|---|
| `aa380c1` | **487** |
| `bdde066` / HEAD | **491** |

Delta: **+4** `@Test` (two in `BootReconcileOwnerTest`, two in `SweepOrphanedResidueTest`).  
No tests deleted, no `@Test` stripped, no files removed under test tree.

---

## G. Suite (this worktree)

Command (env needed here for JNA extract):

```bash
JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/tmp/java-tmp -Djna.tmpdir=/tmp/jna-tmp' \
  ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest
```

**Observed:**  
**491 total / 488 passed / 0 failures / 3 skipped** — BUILD SUCCESSFUL  

(Initial bare runs failed 164× on `Could not initialize class com.sun.jna.Native` until `jna.tmpdir` pointed at a writable extract dir — environment, not product.)

W-A classes all green: `BootRouteTest` 10, `BootReconcileOwnerTest` 11, `SweepOrphanedResidueTest` 16, `DeriveBootDecisionTest` 6.

---

## H. Other issues in the combined range

| Item | Severity | Notes |
|---|---|---|
| `bdde066` sole behaviour change | — | Correct fail-closed unification of last dual authority |
| `bdde066` message still says “STRICTLY STRONGER” / “self-heals” | INFO | Message immutable; body corrected in `157c1f6` |
| Residual strand + no in-app exit on persistent fault | LOW | Tracked in `todos.md`; product/support, not routing weaken |
| Direct test for `onRetryDestroy` wiring | INFO | Disclosed; see E |
| “routes to LOCKED” wording | INFO | See D — snap vs Compose route |

No overclaim in `157c1f6` that reversed under source review. No production statement/expression/signature change hidden in the “comment-only” commit.

---

## Findings summary

| Sev | Location | Defect | Fix |
|---|---|---|---|
| INFO | `MainActivity.kt` onRetryDestroy comments | “routes … to LOCKED” slightly overstates UI (`route` stays DeleteIncomplete; `snap.route` is LOCKED) | Optional: say “snap is LOCKED so success check fails; UI remains DeleteIncomplete” |
| INFO | `onRetryDestroy` | No direct test of consumer wiring | Optional: one diverging-row predicate test (hold+proven+clean) before 0.9.3 fold |
| LOW | Strand / persistent fault | Tracked product issues, not regressions from this delta | Keep on 0.9.3 / product track — do **not** weaken proven-absence |

---

## Explicit A–H

| Item | Verdict |
|---|---|
| **A** | Comment-only confirmed; `onRetryDestroy` body identical to `bdde066` |
| **B.1–B.6** | All stated corrections verified true from source |
| **C** | Enumeration counts correct; declined hits are different claims |
| **D** | Strand real, remote, restart-recoverable; severity right; not blocking |
| **E** | Gap real but disclosed; argument half-honest; optional targeted test exists |
| **F** | 487 → 491 `@Test`; none deleted/defanged |
| **G** | **491 / 488 passed / 0 fail / 3 skip** (this worktree) |
| **H** | No material overclaim left standing in source |

---

## **READY TO MERGE**

Clean pass on the combined unit. Merge gate for W-A itself remains whatever GitHub PR process already requires; this follow-up range does not introduce a new security or routing defect.
