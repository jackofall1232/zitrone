I'll conduct an independent security review of the Pucker Burn unit from source only—starting with the protocol, the cumulative diff, and the key files named in the brief.Next I'll read the key source files and the cumulative diff for the burn/routing paths.I'll enumerate every `route`/`vaultExists` assignment and read the decision functions and vault lifecycle in full.Checking one more interaction: failed-burn replay vs. session gate, and whether consumption-site tests exist.# Independent Security Review — Unit W / Pucker Burn (Round 5, Lens Independent)

**Scope:** `main...HEAD` code (l00prite ignored). Primary delta: `5ef5f77`.  
**Method:** Direct source read of `MainActivity.kt`, `ZitroneApp.kt`, `VaultImageStore.kt`, slot wiring, and host tests. Comments/kdoc/commit messages not trusted.

---

## Verdict

**NOT READY TO MERGE.**

Safety fail-closed for a partial wipe presenting as success still holds. Boot healers and sweep-gate premises check out. The round-5 `IGNORE_STALE` arm is real and correctly protects the **successful-burn → successor** rotation path, but it is a **too-narrow staleness gate**: the same process-lifetime completion replay still paints an unearned `UNIFORM_FAILURE` after a **failed** burn once the user recovers. That is the same deniability-tell class `5ef5f77` was merged to close. Test headers again overclaim exhaustiveness after a new input was added — the exact defect `BootRouteTest` already corrected for `legacyImage`.

---

## Findings

### F1 — HIGH — `IGNORE_STALE` is an incomplete staleness predicate

**Where:** `ZitroneApp.kt` `postBurnRoute` (~1495–1505); consumer `MainActivity.kt` burn observer (~830–879).

**Defect (derived from source, not comments):**

```1495:1505:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
): PostBurnRoute = when {
    burnReportedSuccess && vaultImagePresent -> PostBurnRoute.IGNORE_STALE
    serverDeleteConfirmed -> PostBurnRoute.DELETE_INCOMPLETE
    burnReportedSuccess && imageBearingProvenAbsent -> PostBurnRoute.ONBOARDING
    else -> PostBurnRoute.LOCKED
}
```

`BurnCompletion` is process-lifetime and never cleared (`signalBurnCompleted` only increments generation). `LaunchedEffect(burnCompletion)` therefore re-runs on every later composition while a completion is non-null.

| Replay situation | Predicate | Result |
|---|---|---|
| Success, then successor vault, rotate | `success && image` | `IGNORE_STALE` — correct fix |
| **Failure, image still present, user unlocks, locks, rotate** | `success=false`, `image=true` | **`LOCKED` again** → sets `lockError = UNIFORM_FAILURE` |

Failed-burn re-fire path (observer):

```873:878:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
            PostBurnRoute.LOCKED -> {
                vaultExists = true
                unlocked = false
                lockError = VaultUnlockRouter.UNIFORM_FAILURE
                unlocking = false
                route = Route.Locked
```

Session gate only skips while `session != null`. After a successful unlock post-failed-burn, `lockError` is cleared; a later lock leaves `lockError == null`; rotation re-fires the completion and **reintroduces** the wrong-passphrase string with no new attempt.

**Why it matters:** Standing instruction 2 — a gate wrong by being too narrow. The commit’s claim that “a FAILED burn is untouched … keeps the fail-closed LOCKED arm intact” is true for **first** application (must not swallow fail-closed). It is **false** as a complete replay story: “untouched by IGNORE_STALE” means every later composition **re-applies** LOCKED, including the error paint. That is the same prior-use tell class as the success/successor bug `5ef5f77` fixed.

**Does not swallow a must-act failed burn on first observe:** first composition after a failed burn still gets LOCKED. Mid-burn rotation rescue still works. D2c with `image absent + confirmed` still hits `DELETE_INCOMPLETE` (`success && image` is false when bin is gone).

**Staleness predicate completeness:** `burnReportedSuccess && vaultImagePresent` is **not** a complete “already acted” detector. It only encodes “successful burn + something that looks like a successor.” A successor after a **failed** burn is indistinguishable on disk from the initial fail-closed state, so a pure disk heuristic cannot fix both sides. Process-scoped “last applied generation” (or consume-once with care for mid-burn recreation) is the shape that matches the actual lifetime of `BurnCompletion`.

**Concrete fix:** Track `lastAppliedBurnGeneration` on the process-scoped container; observer (and dispatcher) apply only when `completion.generation != lastApplied`, then record it. Keep fail-closed LOCKED for first application of a failed completion. Optionally retain `IGNORE_STALE` as defense-in-depth, not as the sole anti-replay mechanism.

**Previous conclusion refuted:** Round-5 acceptance that `IGNORE_STALE` fully closes process-lifetime completion replay is **wrong**. It closes only the success/successor arm.

---

### F2 — MEDIUM — Test headers claim exhaustiveness they do not have

**Where:** `PostBurnRouteTest.kt` `full truth table` (~128–155), `onboarding is reachable from exactly one input combination` (~157–175).

After `vaultImagePresent` was added, these still:

- enumerate only the old **3** booleans (8 rows),
- call `postBurnRoute(c, s, p)` so `vaultImagePresent` defaults to `false`,
- assert `"the table must cover every combination"` with size **8**,
- assert onboarding uniqueness over that **subspace**.

`BootRouteTest` already documents and fixed this exact failure mode when `legacyImage` landed (32-way sweep). Round-5 reintroduced the pattern on `postBurnRoute`.

**Why it matters:** Instruction 5 / “false claim in test header.” A regression that widens ONBOARDING or breaks IGNORE_STALE only when `vaultImagePresent=true` is partially covered by four new tests, but the suite still **asserts completeness it does not have**.

**Concrete fix:** Expand to 16 combinations (or 4 named tables). Mirror `BootRouteTest`’s “all N inputs” onboarding sweep. Update kdoc so claims match arity.

---

### F3 — LOW — Two consumers of `postBurnRoute` apply results differently

**Where:** Observer (~852–854) vs burn dispatcher (~1095–1125).

| Decision | Observer | Dispatcher (`onBurn`) |
|---|---|---|
| `IGNORE_STALE` | `Unit` | falls into `else` → LOCKED + `UNIFORM_FAILURE` + `vaultExists=true` |
| `LOCKED` | same | same |
| `ONBOARDING` / `DELETE_INCOMPLETE` | aligned | aligned |

At the burn instant, `burned && obliterationComplete()` implies image-bearing files were proven absent, so `hasVault()` should be false and `IGNORE_STALE` is practically unreachable. Still a second, weaker application mapping: if the enum is ever reachable there, the dispatcher **re-paints failure** instead of no-op.

**Fix:** `when (decided)` with an explicit `IGNORE_STALE -> Unit` arm at the dispatcher, same as the observer.

---

### F4 — LOW / INFO — Stale kdoc on `postBurnRoute` precedence

**Where:** `ZitroneApp.kt` ~1472–1488 still says confirmed delete is **first**. Source evaluates `IGNORE_STALE` first. Same unit’s recurring “comments lie” class.

**Fix:** Rewrite precedence to match the `when` order.

---

### F5 — INFO — `vaultImagePresent: Boolean = false` default

Default re-enables incomplete call sites (round-4 lesson was full input set at every consumer). Both production call sites pass it explicitly today. Prefer no default, or force named args only.

---

### Note (not a new defect)

`File.exists()` verify inside `obliterateLocked` (~1168–1171): pre-existing, inherited from `destroy()`, tristate-inconsistent with later marker/absence checks. **Agree out of scope** for this unit; not re-filed as a Unit W finding.

---

## Explicit verdicts A–F

### A — Every `route` / `vaultExists` site in `MainActivity.kt`

| Site | After `bootReconciled`? | Uses `residueSweepHold`? | Full decision inputs? |
|---|---|---|---|
| Initial `route` / `vaultExists` seed (~624–631) | No (seed) | N/A | Snapshot only |
| `onRetryDestroy` success (~652–653) | N/A | N/A | Disk: `!hasVault && !confirmed` |
| **Splash decision** (~705–752) | **Yes** (`bootDone`) | **Yes** (carried) | **Full `bootRoute` + legacy** |
| **Boot re-derive** (~755–803) | **Yes** (`first { it }`) | **Yes** | **Full `bootRoute` + legacy** |
| **Burn observer** (~830–879) | No (burn authority) | N/A | **Full `postBurnRoute` (4 args)** |
| Session collector unlock (~916) | N/A | N/A | Session live → ChatList |
| **Session null / logout** (~921–953) | Not gated (boot already published in-process) | **Yes** | **Full `bootRoute` + legacy** |
| Forced logout (~966) | N/A | N/A | Locked |
| `onUnlockSuccess` (~984) | N/A | N/A | ChatList |
| **Burn dispatcher** (~1095–1125) | N/A | N/A | Full `postBurnRoute` decision; **apply** incomplete for `IGNORE_STALE` (F3) |
| LegacyImage outcome (~1150–1151) | N/A | N/A | Direct Onboarding |
| Create success/fail (~1271–1286) | N/A | N/A | Create path |
| Account-delete finally (~1392–1400) | N/A | No | Delete-specific disk truth |
| Veil / BackHandler (~1465, 1502) | N/A | N/A | UX navigation |

**Boot-routing authority:** The three consumers that decide cold-start cleanliness (**Splash, boot re-derive, session-null**) all call `bootRoute` with the **full** set including carried `residueSweepHold` and `legacyImage`, ordered after publication for Splash/re-derive. **I confirm the prior “positively decidable / no missing boot consumer” conclusion for those three.** Remaining assign sites are other domains (create/delete/unlock/burn), not a fourth incomplete `bootRoute` call.

**Not** “no further site exists” for **burn completion application** — see F1/F3.

---

### B — `PostBurnRoute.IGNORE_STALE`

| Question | Verdict |
|---|---|
| Swallow a **failed** burn that must lock? | **No** on first observe (`burnReportedSuccess` false → LOCKED). |
| Mask confirmed-delete that D2c must finish? | **Not if boot/session paths run:** `image absent + confirmed` still → `DELETE_INCOMPLETE`. `success && image && confirmed` → IGNORE no-op; Splash/boot re-derive still force `DeleteIncomplete` via `bootRoute`. Weakens burn-observer defense-in-depth only. |
| Predicate complete? | **No** — see F1. Successor after **failed** burn is not treated as stale. |
| Successor without “stale” completion? | Under consistent disk, `obliterated=true` required proven absence; later `hasVault()==true` implies create/restore after that proof — stale for re-apply of that completion. |

---

### C — `bootRoute` / `runBootReconcile`

**`bootRoute` precedence (source order):** confirmed → legacy → image present → residue hold → proven absent → else LOCKED. Legacy arm correctly cannot preempt D2c or fall through to a dead lock screen.

**`runBootReconcile` contract (source):**  
1. Once-only via `claim()` CAS.  
2. `publish` in `finally` on every exit.  
3. Fail-closed default `SWEPT_NOT_DURABLE`.  
4. Cancellation rethrown from sweep, still publishes in `finally`.  
5. `ioDispatcher` injection — production default `Dispatchers.IO`; tests inject scheduler.  
6. `afterPublish` on IO after publish — non-routing.  

Host tests in `BootReconcileOwnerTest` exercise claim, fail-closed throw, cancel-release, no double-sweep, durable/no-hold. **Holds.**

---

### D — `sweepOrphanedResidue` gate + destroy/create premises

**Gate (source):**  
1. `Files.notExists(bin)` — present or indeterminate → `NO_MUTATION`.  
2. `Files.notExists(confirmed)` — present or indeterminate → `NO_MUTATION`.  
3. If already `imageBearingFilesProvenAbsent` → `NO_MUTATION`.  
4. Else unlink residue; prove; `dirSync` → DURABLE / NOT_DURABLE.

**Premises (read in full):**

| Claim | Source | Holds? |
|---|---|---|
| `destroy()` writes confirmed **durably before** unlinks | `writeDurableMarker(serverDeletedFile)` then `obliterateLocked()` (~1117–1118) | **Yes** |
| `create()` clears both markers durably **before** DEK/image write | marker clear (~509–514) before `newDek` / `renameIntoPlace` (~515–550) | **Yes** |
| Intent gate removal safe | D2c unlinks only after confirmed; intent+no-bin+residue is burn/partial, not D2c unlink | **Yes** (row 6b) |

**Writer/reader (residue without proven bin):** sweep owns orphan residue; `completeInterruptedBurn` owns bin-present/dek-absent; `reconcileOrphanedBurnMarkers` owns clean image + orphan intent; confirmed → D2c. Boot order: sweep → completeInterrupted → reconcile. **No missing owner for the residue class this unit targets.** Table rows 1–9 match the gate behavior I re-derived.

---

### E — Cumulative unit

| Topic | Verdict |
|---|---|
| destroy ≡ obliterate under keys-first | Yes; destroy = durable confirmed + shared `obliterateLocked` |
| Marker clear strictly after durable unlinks | `obliterateLocked` steps (2)/(3) then (4) `clearBothMarkersDurably` |
| Boot healers one system | Complementary; confirmed defers to D2c; sweep unblocks reconcile |
| Slot 0 unarmed | `createVaultSlots` places real vault in `randomVaultSlotIndex` (1..n-1); slot 0 filler |
| Wipe wiring | `PassphraseOutcome.Burn` → `onBurn()` only from lock-screen `onUnlockPassphrase` |
| Partial burn as success | Requires `burned = wipe.ok && obliterationComplete()`; `hasVault` alone not used for success |
| Fail-closed residual after failed burn | App-local wipe before image (documented); passphrase may still work; not false success |
| Concurrency | `tryBeginTerminalWipe` exclusive; burn on process scope; completion process-scoped |

**Open gap:** F1 process-lifetime completion replay after failed burn (deniability, not false wipe success).

---

### F — Test quality

| Item | Verdict |
|---|---|
| Vacuous tests in this lens? | No pure no-ops found in the burn/boot suites reviewed |
| False header claims? | **Yes — F2** (`full truth table` / “every combination” still 3-input) |
| Still untested failure shape | **(1)** Failed-burn completion re-fire after recover+lock+rotate paints unearned `UNIFORM_FAILURE` (consumption site; pure function cannot see “already applied”). **(2)** Dispatcher application of `IGNORE_STALE` (F3). **(3)** Full 4-input truth table for `postBurnRoute`. |

---

## Merge recommendation

| Bar | Result |
|---|---|
| Wipe safety / no false fresh-install over residue | Largely met |
| Deniability bar used to justify `5ef5f77` | **Not met** — residual process-lifetime replay tell on failed burns (F1) |
| Test honesty | **Not met** — F2 |

**NOT READY TO MERGE** until F1 is fixed (generation-scoped apply-once) or explicitly accepted as a known residual with the same severity rationale as the success-path tell; and F2’s false exhaustiveness claims are corrected so the suite cannot green-wash a 4th-input regression.

No CRITICAL wipe fail-open found in this pass. The unit’s mechanism and boot sweep design are otherwise coherent when re-derived from source.
