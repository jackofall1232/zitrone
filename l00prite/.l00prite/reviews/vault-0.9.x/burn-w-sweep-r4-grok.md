I'll conduct an independent security review of the round-3 fix delta and the cumulative unit, deriving every safety property from source. Starting with the protocol context and the commits in scope.Reading the production routing and boot-reconcile code against source, not commit messages.Reading the full routing and boot surfaces in MainActivity and ZitroneApp.Checking whether `afterPublish` still runs on IO and verifying scope/dispatcher wiring.Checking one more potential issue: whether `afterPublish` lost its IO dispatcher, and whether the re-derive can stomp `vaultExists` after a correct Splash decision.# Independent Security Review — Round 4 (Blind)
**Unit:** Pucker Burn residue sweep + rounds 1–3 fixes  
**Primary delta:** `00f65b6`  
**Cumulative:** `main...HEAD` (code only; `l00prite/` ignored)  
**Method:** Source-derived only. Commit messages and kdocs treated as untrusted.

---

## Findings

### F1 — LOW — Boot re-derive omits `legacyImage` and can stomp `vaultExists`
**Where:** `MainActivity.kt:767–773` (re-derive), vs Splash at `742–743` / `722–730`

**Defect:** After `bootReconciled`, the re-derive always does:

```767:773:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
            vaultExists = container.hasVault()
            val decided = bootRoute(
                serverDeleteConfirmed = confirmed,
                vaultImagePresent = vaultExists,
                residueSweepHold = container.residueSweepHold.value,
                vaultProvenAbsent = provenAbsent,
            )
```

It never passes `legacyImage` (defaults `false`). For a present v2 image it therefore decides `LOCKED` while Splash correctly decides `ONBOARDING` and sets `vaultExists = present && !legacy`.

If re-derive runs **after** Splash on a legacy upgrade, it can set `vaultExists = true` while `route` stays `Onboarding`.

**Why it matters:** Not D2c erasure (confirmed still wins in re-derive). Not residue fail-open (hold is still carried). It is an incomplete second consumer of the same decision surface: wrong `vaultExists` can flip lemon-drop / biometric affordances (`vaultExists` gates `biometricUnlockAvailable` and the locked-veil pre-onboarding skip at `1399–1400`). Recoverable (create still retires legacy; unlock `LegacyImage` backstop still works).

**Fix:** Compute legacy in the re-derive the same way as Splash (only when `present && !confirmed`), pass it into `bootRoute`, and set `vaultExists = present && !legacy`. Same for the session collector’s `bootRoute` call if you want one rule everywhere.

---

### F2 — INFO — `afterPublish` no longer runs on `Dispatchers.IO`
**Where:** `ZitroneApp.kt:1380` + `869–872`

Pre-extraction:

```kotlin
withContext(Dispatchers.IO) { runCatching { retryPlaintextCacheClearIfNoVault() } }
```

Post-extraction: `afterPublish()` runs on `AppContainer.scope` (`SupervisorJob() + Dispatchers.Default`). Cache-dir deletes therefore run on Default, not IO.

**Why it matters:** Not a safety regression. Contradicts the claim that dispatcher injection changed no production behaviour for the full boot path.

**Fix:** Make `afterPublish` a `suspend () -> Unit` and call it under `withContext(ioDispatcher)`, or have production’s lambda hop to IO itself.

---

### F3 — INFO — `bootRoute` kdoc precedence list omits `legacyImage`
**Where:** `ZitroneApp.kt:1404–1413` vs arms at `1421–1436`

Numbered list still says “present image → lock screen” as step 2; code inserts legacy between confirmed and present. Same class of false/incomplete comment this unit has already hit. Code is correct; comments are not.

---

### F4 — INFO (not counted as defect) — `File.exists()` verify inside `obliterateLocked`
**Where:** `VaultImageStore.kt:1168–1171`

Agree with note G: pre-existing, inherited from `destroy()`, deliberately out of scope. Fail-closed direction (exists → throw) is the safe one. Tristate consistency would be a future hygiene pass, not this delta.

---

## Verdicts on focus questions

### A. Is there still more than one routing authority?

**Cold-start residue + D2c authorization: no independent second authority remains.**

| Site | Assigns | After `bootReconciled`? | Uses `residueSweepHold`? | Role |
|------|---------|-------------------------|--------------------------|------|
| Splash decision `705–753` | `route`, `vaultExists` | Yes (`bootDone`) | Yes (carried) | Primary cold-start exit from Splash |
| Boot re-derive `755–781` | `route` (one-way), `vaultExists` | Yes | Yes | Backstop; same `bootRoute`; **missing legacy** (F1) |
| Session collector `895–906` | `route`, `vaultExists` | N/A (post-session); hold published long before unlock | Yes | Same pure function |
| Burn observer / `onBurn` | via `postBurnRoute` | N/A | N/A (uses burn obliteration proof) | Event-driven, process-scoped |
| `LegacyImage` unlock `1095–1101` | `Onboarding` | Only reachable after boot put user on `Locked` | No | Unlock backstop; confirmed never lands on Locked via `bootRoute` |
| Delete / retry destroy | disk-truth after D2c | After destroy | N/A | D2c finish path |

The standalone legacy `LaunchedEffect` is **gone** (comment at `682–689`; no remaining assignment from that path). That was the round-3 HIGH; fix is real in source.

Remaining multi-writers are either (1) the same `bootRoute` after publication, or (2) event-driven over their own authoritative signals. None re-derive sweep durability from a fresh stat alone for onboarding.

**Positive statement:** I did not find a sixth independent authority that can set `Onboarding` over `{v2 + delete-confirmed}` or over a non-durable sweep. F1 is an incomplete sibling consumer, not a re-opened D2c race.

---

### B. `bootRoute` precedence with `legacyImage`

```1421:1436:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
): BootRoute = when {
    serverDeleteConfirmed -> BootRoute.DELETE_INCOMPLETE
    legacyImage -> BootRoute.ONBOARDING
    vaultImagePresent -> BootRoute.LOCKED
    residueSweepHold -> BootRoute.LOCKED
    vaultProvenAbsent -> BootRoute.ONBOARDING
    else -> BootRoute.LOCKED
}
```

| Combo | Result | Correct? |
|-------|--------|----------|
| confirmed + legacy | `DELETE_INCOMPLETE` | Yes — D2c wins; create cannot clear markers |
| legacy, no confirmed | `ONBOARDING` | Yes — unusable under v3; create retires |
| present v3, no legacy | `LOCKED` | Yes |
| hold, no image | `LOCKED` | Yes — withhold onboarding over non-durable absence |
| proven absent, no hold | `ONBOARDING` | Yes |

**Can legacy mask a state that should stay LOCKED?** Only if `isLegacyImage()` is true, which requires a readable present image with inner version = legacy. Residue-only / non-durable-sweep states do not produce that signal. Splash only computes legacy when `present && !confirmed` (`714–718`), so it cannot invent legacy over an absent image.

**Precedence is correct.**

---

### C. `runBootReconcile`

```1356:1380:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
    if (!claim()) return
    scope.launch {
        var result = ResidueSweepResult.SWEPT_NOT_DURABLE
        try {
            withContext(ioDispatcher) {
                result = try {
                    sweep()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    ResidueSweepResult.SWEPT_NOT_DURABLE
                }
                rest()
            }
        } finally {
            publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)
        }
        afterPublish()
    }
```

| Property | Status |
|----------|--------|
| Once-only | `claim()` CAS; second start returns immediately |
| Publication in `finally` | Yes — every exit including CE |
| Fail-closed initial verdict | `SWEPT_NOT_DURABLE` until proven otherwise; hold = that equality |
| CE rethrow skips `publish`? | **No** — `finally` still runs; hold stays true if sweep never assigned durable |
| CE after durable sweep | `result` already `SWEPT_DURABLE` → hold false (tested) |
| Dispatcher injection | Default `Dispatchers.IO` for sweep/rest — same as before for that work; **afterPublish** is the exception (F2) |

Production `startBootReconcile` (`847–873`) wires process `scope`, real sweep/healers, hold-then-`bootReconciled` publish order.

---

### D. New defects from `00f65b6`?

| Item | Assessment |
|------|------------|
| Legacy folded into `bootRoute`; effect deleted | Fix real and complete for the D2c race |
| Splash re-check after `withContext` | Real structural guard |
| `BootDecision` single snapshot | Correct — route and `vaultExists` from one observation |
| `vaultExists = present && !legacy` | Correct on Splash path |
| F1 re-derive incomplete legacy | LOW consistency, not the round-3 HIGH reopened |
| F2 afterPublish dispatcher | INFO behaviour change |

No CRITICAL/HIGH/MEDIUM introduced by this commit.

---

### E. Sweep gate (independent re-verify)

Gate in source (`1390–1418`):

1. `Files.notExists(bin)` — present or indeterminate refuse  
2. `Files.notExists(serverDeleted)` — present or indeterminate refuse  
3. Already clean → `NO_MUTATION`  
4. Else unlink residue → re-stat → durable `dirSync` → tristate result  

**Intent gate removed — ratified and safe:**

- `destroy()` writes confirmed **before** `obliterateLocked()` (`1117–1118`) — every legitimate D2c unlink carries confirmed → gate 2.  
- `create()` **clears** markers durably before writing DEK (`509–514`) — interrupted create does not leave intent-over-residue as a D2c state.  
- `{no bin, residue, intent only}` is reachable from partial burn + outstanding intent; sweeping unblocks `reconcileOrphanedBurnMarkers` (`1250–1253`), which then retires intent. Narrow intent gate stranded that state (no other healer owned it).

**WRITER / READER (gate completeness):**

| # | State | Owner | Gate |
|---|-------|-------|------|
| 1–3 | dek / dek.tmp / bin.tmp, no bin, no markers | orphan create or partial burn | SWEEP |
| 4 | bin present | live vault | refuse (1) |
| 5 | bin indeterminate | FS fault | refuse (1) |
| 6 | intent + bin present | D2c in flight | refuse (1) |
| 6b | intent + no bin + residue | partial burn + intent | SWEEP then marker reconcile |
| 7 | confirmed present | D2c incomplete | refuse (2) |
| 8 | confirmed indeterminate | FS fault | refuse (2) |
| 9 | nothing | clean / full burn | `NO_MUTATION` |

No missing row found that the gate wrongly admits or wrongly strands given destroy/create ordering above.

**Verdict carry:** `ResidueSweepResult` → `residueSweepHold` → `bootRoute` / Splash / re-derive / session collector. Not re-derived from post-unlink stats alone.

---

### F. Cumulative unit

| # | Verdict |
|---|---------|
| **F.1** destroy ≡ keys-first via shared `obliterateLocked` | **Hold.** DEK then bin/temps; D2c safety is confirmed marker durable before unlinks, so mid-crash → `DeleteIncomplete` → idempotent retry. |
| **F.2** Marker clear strictly after durable unlinks | **Hold.** Verify → `dirSync` DURABLE → `clearBothMarkersDurably`; failure throws, markers retained. |
| **F.3** Boot healers as one system | **Hold.** Order: sweep (no bin) → completeInterruptedBurn (bin, no dek) → reconcile markers (all image-bearing absent). No ownership hole on 6b; no contradiction with D2c confirmed path. |
| **F.4** WRITER/READER for durable signals + in-flight verdicts | **Hold** for residue hold, burn `BurnCompletion.obliterated`, confirmed marker. Consumers of hold use the carried flow after publication for cold-start paths that can present onboarding. |
| **F.5** Reachability | **Hold.** `createVaultSlots` places vault in `1..N-1`, slot 0 random filler (`VaultSlots.kt:141–142`). Wipe only from lock-screen `PassphraseOutcome.Burn` → `onBurn` (`1094`, `960+`). |
| **F.6** Concurrency / lifecycle | **Hold.** Boot and burn on process scope; CAS once; publish in `finally`; Splash waits both splash + boot; burn completion process-scoped. |
| **F.7** Fail-closed | **Hold.** Partial burn → uniform failure + `Locked` + `vaultExists=true`; non-durable sweep → hold → `Locked`; cannot present success without obliteration proof + proven absence. |

---

### G. `File.exists()` in `obliterateLocked`

**Agree — out of scope.** Not a reason to block this unit.

---

### H. Test quality

**Strong where extracted:**

- `BootRouteTest` — pure consumption of hold + confirmed-vs-legacy precedence; 16-way table for non-legacy; mutations named.  
- `BootReconcileOwnerTest` — once-only, publish-before-release, fail-closed default, CE strand, no invented hold, no re-sweep; asserts on **waiter damage**, not claim flags alone.  
- `SweepOrphanedResidueTest` — table rows including ELOOP / consequence-on-dek-survival.

**Not vacuous:** owner tests drive real waiters and sweep counts.

**Still untested failure shapes (highest value first):**

1. **Composition wiring:** that the live Splash effect actually reads `container.residueSweepHold` after `bootDone` (pure `bootRoute` is tested; the consumption site is not).  
2. **Splash re-check** after suspend (`route != Splash`).  
3. **`vaultExists = present && !legacy`** and re-derive stomp (F1).  
4. **Full 32-row** table with `legacyImage` × other inputs (only three dedicated legacy tests).  
5. **End-to-end** Splash ‖ re-derive race under virtual time (needs effect extraction or Compose test).

Round-2’s “no lifecycle infrastructure” claim was wrong; round-3 corrected that for the boot **owner**. The Splash **consumer** remains inspection-level.

---

## Round-3 claim verification

| Claim | Real? |
|-------|-------|
| Legacy effect was second authority; deleted | **Yes** — no remaining effect assignment |
| `legacyImage` arm after confirmed, before present | **Yes** — and tested |
| Splash re-check after suspend | **Yes** |
| `runBootReconcile` extract + host tests | **Yes** |
| CE no longer swallowed by `runCatching` on sweep | **Yes** |

I do **not** reverse a prior accepted conclusion that still holds. I do **not** re-find the round-3 HIGH: that path is sealed in the pure function and Splash path. F1 is a residual incomplete consumer, not that race reopened.

---

## READY TO MERGE?

**Yes — READY TO MERGE.**

No CRITICAL / HIGH / MEDIUM from this pass. The load-bearing properties for this unit hold in source:

- one cold-start decision function with confirmed > legacy > present > hold > proven-absent  
- carried durability verdict, published before waiters proceed  
- sweep gate safe without intent clause  
- burn fail-closed and process-scoped  
- slot 0 unarmed; wipe lock-screen-only  

Ship F1/F2 as follow-ups if desired; they are not merge blockers for the security invariant this unit owns.
