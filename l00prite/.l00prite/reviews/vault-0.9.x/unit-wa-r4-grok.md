I'll conduct an independent security review of the ROUND-3 fix delta. Starting with the project protocol and the full unit diff against main.I'll dig into the production sources that implement routing, residue sweep, and destroy — those are the critical surfaces for Item 1.The `onRetryDestroy` path still looks weaker than the unified derivation — I'll verify that against destroy/sweep semantics next.Mass environmental failures look like a native/classpath issue, not W-A logic. I'll isolate the unit's tests and the initializer error.# Independent Security Review — Unit W-A Round-3 Fix Delta (`acb5904`)

**Scope reviewed:** `git diff main...HEAD` (a98677f + 0d348b4 + 54a41bf + acb5904), with primary attention on `acb5904`.  
**Verdict:** **READY TO MERGE**  
**Findings:** no CRITICAL/HIGH/MEDIUM; a few INFO notes only.

---

## Item 1 — Account-delete routing unification (sole real risk)

### What changed (verified in source)

Delete completion (`MainActivity.kt` ~1108–1136) no longer does:

```text
!hasVault() && !serverDeleteConfirmed() → Onboarding | else → DeleteIncomplete
```

It now:

1. Optionally clears `residueSweepHold` via pure `destroySupersedesResidueHold(vaultProvenAbsent, serverDeleteConfirmed)`
2. Routes through `deriveBootDecisionFromDisk()` → same `bootRoute` as Splash / post-boot re-derive / session collector

### Is clearing the hold justified or convenient?

**Justified.** `VaultImageStore.destroy()` (lines 1092–1149):

1. Writes `vault.delete-confirmed` **durably first** (`writeDurableMarker`)
2. Unlinks bin/dek/temps
3. Re-stats; any survivor → `DestroyFailed`
4. **Requires** `dirSync == DURABLE` or throws (markers kept)
5. Only then `clearBothMarkersDurably()` or throws

A **completed** destroy is exactly `{ image-bearing absence proven + confirmed marker retired }` =  
`vaultProvenAbsent && !serverDeleteConfirmed`. That is stronger evidence than the sweep’s unproven unlink: destroy’s own required `dirSync` landed before marker retire.

A destroy that threw mid-flight still has `serverDeleteConfirmed == true` → supersede is **false** → hold stands → `bootRoute` still yields `DELETE_INCOMPLETE` first. The pure predicate tests pin both conjuncts.

### Reachable post-destroy routing (claim check)

| Post-destroy disk | Old path | New path (`bootRoute`) |
|---|---|---|
| Success: files gone, markers retired | Onboarding | ONBOARDING |
| Image (or residue) survives | DeleteIncomplete (`confirmed` still set) | DELETE_INCOMPLETE |
| Files gone, markers not retired | DeleteIncomplete | DELETE_INCOMPLETE |

`{image survives, confirmed absent}` cannot occur: destroy throws before retire if unlinks are unproven. **Claim holds.**

`bootRoute` can yield `LOCKED` (e.g. unproven absence without confirmed marker). That is **not** reachable after a successful destroy on a normal FS (`destroy` already required absence + durable sync). Under pathological indeterminate stats it fail-closes to lock rather than onboarding — conservative, not a downgrade of delete safety.

### Collector race after unification

`lockIf` still publishes `session=null` before `destroyVault` finishes; collector and delete callback still both write `route`.

They now share **one derivation**. Agreement still depends on reading the **same** `residueSweepHold` snapshot.

**Reachability of the hold+delete compound state (derived from source, not comments):**

- Hold is set only once at boot (`publish(result == SWEPT_NOT_DURABLE)`), only when the sweep **mutated** with bin proven absent.
- That routes to `LOCKED` with **no** `vault.bin`.
- First vault creation is onboarding `create()` / `createVaultAndPublish`, not lock-screen `attemptUnlockOrAdd` (which `open()`s an existing image; `MissingImage` → `ImageUnreadable`, resets triple-entry).
- Hold withholds onboarding → **no vault can be created while hold is raised** in-process.
- Therefore **account delete with `residueSweepHold=true` is unreachable** under current writers.

So the dual-authority bug the fix targets is real as a structural class, but the hold+delete pin-to-lock scenario is not reachable today. Unifying consumers is still correct defense-in-depth; supersede is correct if that reachability ever changes (e.g. create-from-empty lock screen).

### Other consumers of the hold

Writers: boot `publish` (once); delete path clear (new).  
Readers: only via `deriveBootDecisionFromDisk` → `residueSweepHold.value`.  
Clearing the hold does not affect any third authority.

`onRetryDestroy` still uses `!hasVault() && !serverDeleteConfirmed()` and does not clear the hold. On DeleteIncomplete, session is already null and `unlocked` is false, so it does not race the collector. With hold unreachable on that path, behaviour is fine. Asymmetry only.

---

## Item 2 — `afterPublish` containment

```1162:1172:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
        } finally {
            publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)
        }
        withContext(ioDispatcher) { runCatching { afterPublish() } }
```

Publication stays in `finally` **before** `afterPublish`. A throw in hygiene cannot unpublish or flip the hold. Production correctly dropped its local `runCatching`. Test `a throwing afterPublish cannot unpublish the verdict` passes.

---

## Item 3 — Row-6b docstring

Test docstring now matches store row 6c: intent+absent-image **is** reachable (`retireLegacyImage` before `create` marker clear); sweeping is safe because the openable image is already gone. Corrected.

---

## Binding focus verdicts

### A0 — Sibling call sites of single derivation

| Consumer | Full input set via `deriveBootDecisionFromDisk`? |
|---|---|
| Splash decision (`MainActivity` ~646) | Yes |
| Post-boot re-derive (~667) | Yes |
| Session collector (~783) | Yes |
| Account-delete finally (~1123) | Yes (round-3) |

`bootRoute(...)` has **no defaults**; only production call is inside `deriveBootDecision`. Legacy standalone effect is gone; unlock-time `PassphraseOutcome.LegacyImage` remains a non-boot backstop. Row-7 confirmed-marker gate test is present and mutation-named.

### A — Nothing burn-dependent survived

Compared `git show main:` for `onBurn`: **identical stub** (uniform failure, no destroy).  
No `burnVault` / `obliterateForBurn` / `wipeBiometricMaterial` / `wipeAppLocalStateForBurn` / `BurnCompletion` / `postBurnRoute` / `signalBurnCompleted` / `tryApplyBurnCompletion` in Kotlin sources.

### B — Coupling line cleanly severed

No `signalBurnCompleted` in code; only historical mention in `l00prite` ledger. No half-removed burn completion state.

### C — Excluded healers

No references to `completeInterruptedBurn` / `reconcileOrphanedBurn`.  
Independently: `create()` DEK-first durability barrier (lines 533–553); `destroy()` confirmed marker before unlinks (1101–1108). Intent alone never authorises D2c unlinks.

### D — Strictly better than main

Main Splash (`git show main:`): `!vaultExists` → Onboarding with `vaultExists = hasVault()` only → `{bin absent, dek/tmp present}` onboards over recoverable outer residue.  
W-A: destructive sweep + carried durability hold + `vaultProvenAbsent` for onboarding. No security-relevant state is made worse. Fail-closed hold can strand UX on lock until process death — intentional, stricter than main.

### E — Sweep gate (both directions)

**Wrongly deletes (by design):** orphan DEK / temps from interrupted `create` or `retireLegacyImage`; complete image in `vault.bin.tmp` with no primary (same policy as `open` leftover cleanup).  
**Wrongly strands:** live vault DEK (gate 1 refuses); D2c-owned residue under confirmed marker (gate 2); indeterminate stats (tristate).  

**Missing row 6c** is documented and tested (intent + residue, no bin → sweep). No intent gate: destroy always durable-confirms before unlink; intent gate would strand 6c and not protect in-flight delete (image still present → gate 1). Reasoning matches source.

### F — Verdict carried, not re-derived

| Consumer | Waits publication? | Uses carried hold? | Full inputs? |
|---|---|---|---|
| Splash | `bootDone` | yes | yes |
| Post-boot re-derive | `bootReconciled.first` | yes | yes |
| Session collector | n/a (session event; hold already published) | yes | yes |
| Delete finally | after destroy; may clear hold first | yes | yes |

No consumer re-stats “was the sweep durable?”

### G — `runBootReconcile` contract

Once-only CAS; `publish` in `finally`; fail-closed default `SWEPT_NOT_DURABLE`; afterPublish contained. Tests cover second-start, throw, cancel-no-strand, durable/no-mutation, afterPublish throw. **Holds.**

### H — Test suite (this worktree)

First run failed massively on `UnsatisfiedLinkError` (JNA temp extract permission) — environment, not product. After `TMPDIR` / `jna.tmpdir` fix:

| Metric | Observed |
|---|---|
| Total | **487** |
| Failures | **0** |
| Passed | **484** |
| Skipped | **3** |

Matches the commit claim. W-A suites: BootRoute 10, BootReconcile 9, DeriveBoot 6, DestroySupersedes 3, Sweep 14 — all green.

### I — Test quality

- Pure `destroySupersedesResidueHold` is tested; **wiring** in `MainActivity` (clear-then-derive) is not integration-tested — residual untested failure shape: drop the clear in production, pure tests stay green. Mitigated by hold+delete unreachability today.
- afterPublish test mutation claim is slightly loose (publish still in `finally` if only reordered inside try/finally).
- Stale test comment says production still wraps `afterPublish` in local `runCatching`; production no longer does (wrapper owns it).
- No vacuous truth-table copy of `bootRoute` (expectations enumerated).
- Sweep tests assert damage (DEK survives), not only return codes, for gates 1/2.

---

## Findings

### INFO-1 — Stale production claim in afterPublish test header  
**File:** `BootReconcileOwnerTest.kt` ~314  
**Defect:** Header still says production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`; production now relies on wrapper containment only.  
**Why it matters:** Same comment/code drift class this unit keeps fixing.  
**Fix:** Update the comment to match `ZitroneApp.startBootReconcile`.

### INFO-2 — `onRetryDestroy` still a weaker sibling  
**File:** `MainActivity.kt` ~697–704  
**Defect:** Still `!hasVault() && !serverDeleteConfirmed()`; no hold supersede / no `deriveBootDecisionFromDisk`.  
**Why it matters:** Structural family residual; not a reachable dual-writer bug today (no session/`unlocked` race on that path).  
**Fix (optional):** Route retry success through the same supersede + derivation for uniformity.

### INFO-3 — `runCatching` can swallow `CancellationException`  
**File:** `ZitroneApp.kt` ~1172  
**Defect:** Unlike the sweep path, `runCatching { afterPublish() }` catches `CancellationException`. Non-suspending production lambda is fine; a future suspending `afterPublish` would break structured cancellation.  
**Fix (optional):** Re-throw CE like the sweep’s try/catch.

---

## Round-3 delta assessment

| Item | Assessment |
|---|---|
| 1. One post-destroy authority + supersede | Correct; supersede justified by `destroy()`; dual-writer class closed for reachable states |
| 2. Contain `afterPublish` | Correct; cannot affect published verdict |
| 3. Row-6b docstring | Corrected to match store table |

---

## READY TO MERGE: **YES**

No security defect found that should block merge. Item 1’s behavioural change is sound against `destroy()`’s actual control flow; the extraction’s signature failure class (authoritative result exists, consumer uses something weaker) is not present in reachable post-destroy routing after this delta.
