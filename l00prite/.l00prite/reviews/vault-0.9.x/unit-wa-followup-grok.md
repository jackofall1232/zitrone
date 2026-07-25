I'll independently review the W-A follow-up delta against source—starting with the protocol files and the exact `aa380c1..HEAD` diff.Pulling the full delta and the production paths it touches for `onRetryDestroy` and boot routing.Reading the production routing code and the surrounding consumers of the boot decision.Checking the hold-coexistence claim, destroy verify details, and production call sites for the docstring claims.Running claimed mutations and the full unit-test suite.That failure looks environmental, not an assertion catch. Checking the report and restoring source before re-running clean.Baseline passes. Applying both mutations and re-running the claimed tests.Both mutations fail as claimed. Restoring source and running the full suite.Checking one more detail on the hold path and whether `onRetryDestroy` can strand after a successful destroy:# Independent Security Review — Unit W-A Follow-up (`aa380c1..bdde066`)

**Reviewer posture:** blind, source-derived only. Worktree HEAD `bdde066`.  
**Verdict: READY TO MERGE** — sole behavioural change is a correct fail-closed strengthening on the account-delete retry surface; findings below are LOW/INFO documentation residue, not merge blockers.

---

## Binding focus verdicts

### A. `onRetryDestroy` — sole behavioural change

**Source (old → new):**

```kotlin
// OLD (aa380c1)
!container.hasVault() && !container.serverDeleteConfirmed()

// NEW (HEAD, MainActivity.kt:697–726)
withContext(Dispatchers.IO) { runCatching { container.destroyVaultForAccountDeletion() } }
val snap = container.deriveBootDecisionFromDisk()
if (snap.route == BootRoute.ONBOARDING) { /* success → Onboarding */ } else { deleteRetryFailed = true }
```

**Predicates (ordinary FS, `exists`/`notExists` coherent):**

| Symbol | Meaning |
|--------|---------|
| **C** | `serverDeleteConfirmed` (`vault.delete-confirmed` via `exists()`) |
| **B** | `hasVault()` / `imagePresent` (`vault.bin` via `exists()`) |
| **R** | residual image-bearing file other than bin (dek / bin.tmp / dek.tmp) |
| **P** | `vaultProvenAbsent` = `Files.notExists` over all four (`!B ∧ !R` when stats are coherent) |
| **H** | `residueSweepHold` |
| **L** | legacy image (only probed when `B ∧ !C`; forced false when `C`) |

**OLD success** = `!B ∧ !C`  
**NEW success** = `route == ONBOARDING` =
```
C → DELETE_INCOMPLETE
L → ONBOARDING
B → LOCKED
H → LOCKED
P → ONBOARDING
else → LOCKED
```
i.e. `(!C ∧ L) ∨ (!C ∧ !B ∧ !H ∧ P)`  
(`bootRoute` / `deriveBootDecision` in `ZitroneApp.kt:1193–1289`)

#### Full post-retry state map

| # | C | B | R | H | L | OLD | NEW route | NEW success | Δ |
|---|---|---|---|---|---|-----|-----------|-------------|---|
| 1 | 0 | 0 | 0 | 0 | — | ✓ | ONBOARDING | ✓ | same (clean success) |
| 2 | 0 | 0 | 0 | 1 | — | ✓ | LOCKED | ✗ | **CHANGE** — hold now blocks |
| 3 | 0 | 0 | 1 | 0 | — | ✓ | LOCKED | ✗ | **CHANGE — FIX** (residue) |
| 4 | 0 | 0 | 1 | 1 | — | ✓ | LOCKED | ✗ | **CHANGE — FIX** |
| 5 | 0 | 1 | * | * | 0 | ✗ | LOCKED | ✗ | same |
| 6 | 0 | 1 | * | * | 1 | ✗ | ONBOARDING | ✓ | **CHANGE** (legacy arm) |
| 7 | 1 | 0 | 0 | * | — | ✗ | DELETE_INCOMPLETE | ✗ | same |
| 8 | 1 | 0 | 1 | * | — | ✗ | DELETE_INCOMPLETE | ✗ | same |
| 9 | 1 | 1 | * | * | * | ✗ | DELETE_INCOMPLETE | ✗ | same |

**Rows whose behaviour changes:**

| Row | Assessment |
|-----|------------|
| **3, 4** | **FIX.** Core W-A hazard: OLD treated “no `vault.bin` + no confirmed” as success while dek/tmp residue remained. NEW requires `vaultProvenAbsent`. |
| **2** | **Fail-closed intentional, not a residue fix.** Same clean disk; hold withholds ONBOARDING. Only matters if hold coexists (below). |
| **6** | **Not a regression on this path.** Legacy is not a post-destroy product; destroy unlinks the image before marker retire. Theoretically NEW ⊈ OLD because of this arm, so “STRICTLY STRONGER” is **not** a formal set inclusion over all five inputs — it **is** a strict strengthening on the ordinary non-legacy post-retry surface that matters. |

**Pathological tristate** (`exists()` false / `notExists()` false on a surviving file): destroy verify is `exists()`-based (`VaultImageStore.kt:1126–1129`) so it can pass; markers may retire; NEW → LOCKED (fail-closed); OLD → SUCCESS → onboarding over unproven absence. **FIX.**

#### Hold supersede — attack the “unreachable” claim

Justification in comments: *DeleteIncomplete requires confirmed; held boot admits no session; therefore hold and this path cannot coexist.*

| Path | Hold raised? | Session / DeleteIncomplete? |
|------|--------------|------------------------------|
| Cold boot with **confirmed** | Sweep gate 2 → `NO_MUTATION` → **H=false** | DeleteIncomplete, no hold |
| Residue sweep non-durable | **H=true**, requires **no bin** at sweep | LOCKED over no image → **no unlock → no session → no in-session delete** |
| Boot cancel default (`result` stays `SWEPT_NOT_DURABLE`) while vault **present** | **H=true** possible | Unlock possible → session → delete → if first destroy fails → DeleteIncomplete **with H still true** |

`AppContainer.scope` is `SupervisorJob() + Dispatchers.Default` (`ZitroneApp.kt:136`) — not cancelled in normal process life. The cancel-default co-existence is **architecturally open, operationally remote**. The comment’s slogan “held boot admits no session” is **incomplete** (false for cancel-default-with-vault); the **residue** path really is session-free.

Without supersede on retry: successful destroy with stale **H** → NEW stays non-ONBOARDING → `deleteRetryFailed` forever while disk is clean. Process restart clears H. **onConfirmed** already supersedes; this path deliberately does not.

**Hold-unreachability verdict:** not fully airtight, but not a live dual-writer bug on ordinary boots. Omitting supersede is an acceptable deferral to 0.9.3 **if** the residual risk is accepted; the written justification should not claim absolute unreachability.

#### No in-app exit?

| State | UI | Exit |
|-------|-----|------|
| Confirmed stuck, files refuse delete | DeleteIncomplete + retry | Retry (self-heal if FS recovers) |
| Clean destroy, **H** still true | DeleteIncomplete + error (route **not** rewritten to Locked) | **No in-app exit** until process restart |
| Pathological unstattable residue, C=0 | Same | Process restart → still LOCKED cold-boot; external clear |

Stuck-on-DeleteIncomplete with clean disk + hold is the only “successful destroy, reported failure, only retry” corner. Severity LOW given process-scoped scope lifetime.

**A overall: PASS (fix is correct; claim language slightly overstated).**

---

### B. Existing coverage not weakened

| Ref | `@Test` count (`git grep '@Test' … \| wc -l` on `apps/android/app/src/test`) |
|-----|--------------------------------------------------------------------------------|
| `aa380c1` | **487** |
| `HEAD` | **491** |

Diff on tests: **+4** only (`BootReconcileOwnerTest` ×2, `SweepOrphanedResidueTest` ×2).  
No `-@Test`, no removed `fun \`` test methods, no stripped annotations.

**B: PASS.**

---

### C. Test honesty

| Test | Vacuous? | Mutation claim | Independent run |
|------|----------|----------------|-----------------|
| `residue that survives its unlink…` | No — asserts `SWEPT_NOT_DURABLE` + survivor still exists | Drop post-unlink re-stat | **FAILS** as claimed: `expected SWEPT_NOT_DURABLE but was SWEPT_DURABLE` |
| `a throwing step after the unlinks…` | No — asserts result + dek unlinked | Remove total `catch` | **FAILS** as claimed: `IOException` escapes at assert call site |
| `synthetic cancellation from afterPublish…` | No — asserts boot completed, not cancelled, verdict published | Remove `runCatching` | Claim structure is sound (synthetic CE) |
| `real cancellation during afterPublish…` | Non-vacuous characterisation | **NONE** (explicit) | Label is **honest** — asserts job cancellation after `parent.cancel()`; survives containment mutations by design |

**C: PASS.**

---

### D. MainActivity post-destroy comment

**Half 1 — old impossibility claim was false:**

- Verify is `binFile.exists() \|\| dekFile.exists() \|\| tmp…` (`VaultImageStore.kt:1126–1129`).
- `File.exists()` is true only on proven presence; indeterminate/fault → false → verify passes.
- Then required `dirSync` DURABLE → `clearBothMarkersDurably()` → markers retired.
- So `{image-ish survival under bad stats, confirmed absent}` is reachable on a pathological FS.

**Half 2 — safety is routing:**

- Same indeterminate → `vaultProvenAbsent` false (`Files.notExists` proven-absence only, lines 1304–1308) and `imagePresent` false → with `!C`, `bootRoute` falls to **LOCKED** (else arm), not ONBOARDING.

**New comment:** both halves match source; does not reintroduce “impossible state” fiction. Line reference “~1126” is accurate.

**D: PASS.**

---

### E. Other docstring corrections + leftovers

| Correction | Accurate? | Leftover same fact? |
|------------|-----------|---------------------|
| `BootReconcileOwnerTest` ~314: production passes **bare** `retryPlaintextCacheClearIfNoVault()` | **Yes** — `ZitroneApp.kt:285–288` | See below |
| `runBootReconcile` kdoc: production does **not** pass `ioDispatcher` | **Yes** — call site only passes `scope`, `claim`, `sweep`, `publish`, `afterPublish` | No other “passes Dispatchers.IO” claim found |

**Leftover stale claim (same family as bare-`afterPublish`):**

```1170:1175:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
        // ... Production's lambda wraps
        // itself, which protects today's caller and no future one; the guarantee belongs in the
        // wrapper. ...
        withContext(ioDispatcher) { runCatching { afterPublish() } }
```

Production’s lambda is bare; the wrapper is the only containment. This body comment is the **same wrong fact** the test header just fixed, left one screen away. Pre-existed the delta, not introduced by it — but focus E asked to hunt leftovers.

**E: PASS on the three claimed corrections; LOW leftover body comment.**

---

### F. Independent suite run

```
ANDROID_HOME=/opt/android-sdk  + extracted Gradle 8.7
(worktree: JNA/java tmp under /tmp/*-tmp — host /tmp JNA extract was broken)

XML aggregate: total=491  failures=0  errors=0  skipped=3  passed=488
BUILD SUCCESSFUL
```

Matches commit claim **491 / 488 / 0 / 3**.

**F: PASS.**

---

### G. Any other delta issue

1. **Commit message “STRICTLY STRONGER”** — true for ordinary post-retry (requires `P` and respects `H`); not a pure subset once the legacy ONBOARDING arm is included. Overstatement, not a code defect. **INFO.**
2. **“self-heals on next attempt”** — true for residue / incomplete destroy; **not** for stale hold without supersede. Comment scopes it to residue; OK. **INFO.**
3. **No unit test of `onRetryDestroy` itself** — behavioural change is integration-shaped; covered only by shared `bootRoute` / derivation tests. Acceptable for this UI lambda; residual gap. **INFO.**
4. **`failures.md` process note** — non-security, out of product surface. **INFO.**
5. **Stale body comment** at `ZitroneApp.kt:1172–1174` (above). **LOW.**

No silent behavioural regressions elsewhere in the diff; no dual-writer reintroduction on the four already-unified consumers.

**G: no merge-blocking issues.**

---

## Findings (severity-ordered)

### 1. LOW — Stale “production wraps itself” body comment  
**Where:** `ZitroneApp.kt:1172–1174`  
**Defect:** Claims production’s `afterPublish` lambda still locally `runCatching`-wraps; production is bare (`ZitroneApp.kt:285–288`). Same drift class this delta’s test-header correction just closed.  
**Why it matters:** Future reader “optimises away” wrapper containment thinking production is self-protected.  
**Fix:** Align with test header: production is bare; wrapper owns containment.

### 2. INFO — Hold unreachability overclaimed  
**Where:** `MainActivity.kt:714–717` (and commit message)  
**Defect:** Residue+confirmed paths cannot raise hold into DeleteIncomplete; cancel-default + present vault can leave H true into a later DeleteIncomplete retry.  
**Why it matters:** Missing supersede can strand “destroy succeeded, UI reports failure” until process death.  
**Fix:** Soften comment to “ordinary residue/confirmed boots cannot raise hold here”; or add the same `destroySupersedesResidueHold` clear as `onConfirmed` (cheap, closes the strand). Optional for merge.

### 3. INFO — “Strictly stronger” is ordinary-path language  
**Where:** commit message / `MainActivity.kt:706–712`  
**Defect:** Formal predicate is not a pure strengthening of `!hasVault() && !confirmed` (legacy arm). Residue strengthening claim is correct.  
**Fix:** Prefer “stronger on absence proof (`vaultProvenAbsent`); full `bootRoute` for one authority.”

---

## Summary table (A–G)

| Item | Verdict |
|------|---------|
| **A** `onRetryDestroy` | **PASS** — residue false-success closed; hold edge remote; no ordinary no-exit trap |
| **B** Coverage not weakened | **PASS** — 487 → 491; no deletions |
| **C** Test honesty | **PASS** — both sweep mutations independently fail; NONE label honest |
| **D** Post-destroy comment | **PASS** — both halves verified against source |
| **E** Docstring corrections | **PASS** + one leftover stale body comment |
| **F** Suite | **PASS** — 491 / 488 / 0 / 3 observed |
| **G** Other | **PASS** — INFO/LOW claim nits only |

---

## READY TO MERGE: **YES**

The behavioural delta does the right thing on the historically weakest account-delete sibling: success is no longer `!hasVault() && !confirmed`, but the same fail-closed derivation every other consumer uses. Tests that claim mutations catch them. Doc leftovers are not security defects. I would not invent a block for thoroughness — the code holds.
