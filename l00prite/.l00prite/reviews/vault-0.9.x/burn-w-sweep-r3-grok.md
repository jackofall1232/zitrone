I'll perform an independent security review of the residue-sweep delta, verifying every claim against source. Starting with the protocol files and the three commits plus cumulative unit.Next I'll pull the three commit diffs and the cumulative unit, then read the consumers of the boot-reconcile path.I'll read the current sources for boot routing, reconcile ownership, and the sweep gate.# Round 3 — Independent security review (residue-sweep unit)

**Scope verified against source:** `5e02b2e`, `98c0319`, `c144216`, and the cumulative code under `main...HEAD` (excluding `l00prite/`).

**Verdict: READY TO MERGE.**  
The two round-2 defects are fixed for real. I did not find a new CRITICAL/HIGH/MEDIUM. One LOW hygiene race and one INFO leftover. Round-1 conclusions on the intent-gate removal and carried durability hold hold up under independent re-derivation.

---

## Round-2 fix verification (claims vs source)

| Claim | Real? | Complete? | Safe? |
|---|---|---|---|
| Splash no longer routes from `onFinished` without the boot verdict | **Yes** — `SplashScreen(onFinished = { splashFinished = true })` only; decision is `LaunchedEffect(splashFinished, bootDone)` | **Yes** for the residue path | **Yes** — uses carried `residueSweepHold` after `bootDone` |
| CAS owned by process-scoped work, not composition | **Yes** — `AppContainer.startBootReconcile()` + `scope.launch` | **Yes** — CAS claim and work share process lifetime | **Yes** — `finally` publishes fail-closed |
| Session collector uses hold | **Yes** — routes through `bootRoute(...)` with `residueSweepHold` | **Yes** for that consumer | **Yes** (still only after unlock→lock cycle) |
| `create()` kdoc corrected | **Yes** | n/a | n/a |
| `bootReconcileRest` removed | **Yes** — logic only in `startBootReconcile` | **Yes** — single ordering source | **Yes** |

---

## A. Is the consumption path sealed? — **PASS**

### Consumers of `ResidueSweepResult` / `residueSweepHold` / `bootReconciled`

| Consumer | Site | Uses carried value? | Ordered after publication? |
|---|---|---|---|
| **Publisher** | `ZitroneApp.kt:847–869` `startBootReconcile` | Produces hold from exact `sweep == SWEPT_NOT_DURABLE` | Publishes hold **before** `bootReconciled = true` |
| **Splash decision** | `MainActivity.kt:709–739` | `residueSweepHold.value` into `bootRoute` | Requires `bootDone` (from `bootReconciled`) **and** `splashFinished` |
| **Boot re-derive** | `MainActivity.kt:742–768` | same | `bootReconciled.first { it }` then reads hold |
| **Session collector** | `MainActivity.kt:859–895` | same via `bootRoute` | Does **not** await boot; only fires when `unlocked && session→null` (after a live unlock cycle → boot long finished) |
| **Tests** | `BootRouteTest`, `SweepOrphanedResidueTest` | decision + production of enum | n/a |

No other production readers of these three signals (grep across `apps/android`).

`ResidueSweepResult` itself is only converted to the hold inside `startBootReconcile` (plus tests / thin wrapper `sweepOrphanedVaultResidue()`). No discard-and-re-derive at a routing site.

### One-way re-derive / early Onboarding

Re-derive only promotes `Locked → Onboarding` (`MainActivity.kt:765`). It cannot demote a bad early Onboarding.

**Paths that can set Onboarding without the hold:**

| Path | Hold? | Residue-safe? |
|---|---|---|
| Splash after boot | yes | yes |
| Boot re-derive | yes | yes |
| Session collector | yes | yes |
| `postBurnRoute` success | uses burn’s own `obliterated` + proven-absent | yes (different signal, correct for burn) |
| Legacy image / `LegacyImage` outcome | no | image **present**; create retires it |
| Account-delete / DeleteIncomplete success | uses `hasVault` + confirmed | destroy’s own verify; not the cold-start residue gap |

Nothing in the residue-relevant cold-start path can reach Onboarding **before** publication and stay there. The one-way re-derive is fine because Splash no longer takes a premature decision off the hold’s default `false`.

**Unmentioned consumer from earlier rounds:** none beyond the three above. Session collector was the third and is fixed.

---

## B. `startBootReconcile()` — **PASS**

Source: `ZitroneApp.kt:847–872`.

1. **`finally` publishes on every exit**  
   Non-suspending `MutableStateFlow` assignments. Runs on success, throw, and cancellation after the `try` body. Process death kills waiters too — no cross-process strand.

2. **Fail-closed initial `sweep`**  
   Starts at `SWEPT_NOT_DURABLE`. Only a completed return value can change it.  
   Hold formula: `hold = (sweep == SWEPT_NOT_DURABLE)`.  
   - Partial/cancelled before assignment → hold **true** (withhold onboarding).  
   - `SWEPT_DURABLE` / `NO_MUTATION` → hold **false** (correct: no non-durable mutation claimed).  
   - A partial run cannot “lower” a durable success into a silent fail-open: durable success is only assigned after the store returns it.

3. **CAS cannot strand**  
   Work runs on process-scoped `scope` (`SupervisorJob` + `Default`), not a composition `LaunchedEffect`. Rotation cancels composition jobs; it does not cancel this job. Losers of the CAS just observe `bootReconciled`.

4. **No new race with burn / account-delete on `container.scope`**  
   - Splash cannot leave until boot publishes → lock screen / burn only after boot.  
   - Account delete needs a session → only after unlock → after boot.  
   - Disk work serializes on `imageLock` inside the store.  
   - Cache hygiene runs **after** the gate opens and is gated on proven image absence.

Hold is written **before** `bootReconciled = true`, so any waiter that sees boot done then reads hold cannot observe the pre-publication default.

---

## C. Splash gate — **PASS** (one LOW hygiene note)

```709:739:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
LaunchedEffect(splashFinished, bootDone) {
    if (!splashFinished || !bootDone) return@LaunchedEffect
    if (route != Route.Splash) return@LaunchedEffect
    val (confirmed, present, provenAbsent) = withContext(Dispatchers.IO) { ... }
    ...
    residueSweepHold = container.residueSweepHold.value,
    ...
}
```

| Question | Answer |
|---|---|
| Fire twice? | Keys stable after both true; second pass would need key change. After first decision `route != Splash`, a restart would early-return. |
| Fire never? | Animation never finishes → stuck Splash (fail-closed). Boot never publishes → only process death (composition dies too). |
| Stale inputs? | Hold is process-scoped and published before `bootDone`. Disk triple taken after boot. |
| `route != Splash` guard? | Correct to avoid stomping post-decision routes **if** still Splash when the effect **starts**. |
| Composition recreated after animation, before boot? | `splashFinished` resets (`remember`); Splash re-animates; `startBootReconcile` is idempotent; decision waits for both again. |

### LOW — missing re-check after suspend

**Severity:** LOW  
**Site:** `MainActivity.kt:711–739`  
**Defect:** `route != Splash` is checked only **before** `withContext(IO)`. During the suspend, the legacy-image effect can set `Route.Onboarding`; the Splash effect then overwrites with `bootRoute` (typically `Locked` if the image is present).  
**Why it matters:** Upgrade UX can briefly (or until passphrase entry) land on the lock screen for a v2 image; not a residue fail-open — `LegacyImage` unlock still routes to onboarding (`MainActivity.kt:1082–1088`).  
**Fix:** After `withContext`, `if (route != Route.Splash) return@LaunchedEffect` before assigning.

Not a merge blocker for this unit’s security property.

---

## D. New defects from `5e02b2e`? — **No security defect**

- Session-collector change: closes the third weaker consumer correctly.  
- Removal of `bootReconcileRest`: eliminates a second copy of boot ordering; good.  
- Dead import `ResidueSweepResult` in `MainActivity.kt:54` — **INFO** only.  
- LOW race above is the only behavioral nit.

---

## E. Sweep gate (independent) — **PASS; intent removal safe**

Gate (current code `VaultImageStore.kt:1390–1441`):

1. Image **proven** absent: `Files.notExists(binFile)` — present or indeterminate → `NO_MUTATION`  
2. Confirmed marker **not** present/indeterminate: `!Files.notExists(serverDeletedFile)` → refuse  
3. Already clean → `NO_MUTATION`  
4. Unlink → re-stat all image-bearing → durable `dirSync` → `SWEPT_DURABLE` / else `SWEPT_NOT_DURABLE`  
5. Past mutation point, never `NO_MUTATION` (including catch)

### Intent gate removal — every relevant state

| State | Who owns it | Safe without intent gate? |
|---|---|---|
| D2c in flight, image present | gate 1 | refuse |
| D2c unlinking | `destroy()` writes **confirmed durably before any unlink** (`VaultImageStore.kt:1117–1118`) | gate 2 |
| `{no bin, residue, intent, no confirmed}` | **nobody** with intent gate; **sweep + then** `reconcileOrphanedBurnMarkers` | must sweep — row 6b |
| Intent only, already clean | marker retire after sweep no-op | yes |
| Interrupted create | create clears markers **before** DEK write (`create()` `487–514`) | residue has no intent |

Premise that create “refuses while markers present” was false; real premise is destroy’s confirmed-before-unlink + create’s clear-before-write. Conclusion (drop intent gate) is correct.

### WRITER/READER table completeness (derived, not trusted)

| # | Disk | Writer | Gate |
|---|---|---|---|
| 1 | dek, no bin, no markers | partial create / partial burn | SWEEP |
| 2 | dek.tmp only | rename crash | SWEEP |
| 3 | bin.tmp (± dek), no bin | partial create / burn | SWEEP |
| 4 | bin present | live vault | REFUSE (1) |
| 5 | bin indeterminate | FS fault | REFUSE (1) |
| 6 | intent + bin | D2c in flight | REFUSE (1) |
| 6b | intent + residue, no bin, no confirmed | partial burn during outstanding intent | **SWEEP** (unblocks marker retire) |
| 7 | confirmed present | D2c incomplete destroy | REFUSE (2) → DeleteIncomplete |
| 8 | confirmed indeterminate | FS fault | REFUSE (2) |
| 9 | nothing | clean / full burn | NO-OP |

**Missing-row hunt:** intent+confirmed+residue → still refuse on confirmed (DeleteIncomplete). Intent-only clean directory → NO_MUTATION, retire owns marker. No ownerless residue state remains that I can construct.

Too broad? Would need to unlink with bin present or under confirmed — both gated.  
Too narrow? Was the intent clause; fixed in round 1.

---

## F. Cumulative unit (assume nothing)

### F.1 destroy() equivalence under keys-first — **PASS**
`obliterateLocked` unlinks DEK (+temps) then bin (+temps). `destroy()` only adds durable confirmed **before** that. Crash mid-unlink → DeleteIncomplete → idempotent retry. Keys-first is safe for destroy because of that bridge.

### F.2 Marker clear strictly after durable unlinks — **PASS**
Steps (2) verify absence, (3) `dirSync == DURABLE`, (4) `clearBothMarkersDurably()` or throw (`1144–1199`).

### F.3 Boot healers as one system — **PASS**
| Healer | Preconditions | Role |
|---|---|---|
| `sweepOrphanedResidue` | bin proven absent, no confirmed, residue present | cold-start orphan |
| `completeInterruptedBurn` | bin present, dek proven absent, no confirmed | finish keys-first burn |
| `reconcileOrphanedBurnMarkers` | all image-bearing proven absent, no confirmed, intent present | retire orphan intent |

Order in boot: sweep → completeInterruptedBurn → reconcile. Sweep unblocks retire (6b). No contradictory ownership; no state both claim destructively.

### F.4 WRITER/READER (durable + in-flight) — **PASS**
Durable: markers, image files, temps.  
In-flight: `ResidueSweepResult` → process-scoped hold; `BurnCompletion.obliterated`; `bootReconciled` gate.  
Routing consumers of cold-start cleanliness use the carried hold, not a post-unlink re-stat alone.

### F.5 Reachability — **PASS**
- Slot 0: `createVaultSlots` only places vault in `randomVaultSlotIndex` (1..n-1); slot 0 remains random filler (`VaultSlots.kt`).  
- Wipe UI: `PassphraseOutcome.Burn → onBurn()` only from lock-screen passphrase dispatch (`MainActivity.kt:1081`). Store returns `Burn` without wiping; wipe is app-layer only there.

### F.6 Concurrency / lifecycle end-to-end — **PASS**
Burn and boot on process scope; exclusive terminal wipe; imageLock; Splash waits for boot; burn completion process-scoped with outcome, not hasVault re-derive.

### F.7 Fail-closed — **PASS**
Partial burn → `postBurnRoute` holds Locked unless `obliterated && provenAbsent`. Non-durable sweep → hold → Locked, not Onboarding. Failed burn can leave app-local cleanups done while image survives (documented, deliberate ordering) — worse hygiene, not false success presentation.

---

## G. `File.exists()` inside `obliterateLocked` — **agree out of scope**

Pre-existing, inherited by burn and destroy. Fail direction on indeterminate stat tends to throw `DestroyFailed` (treat present) rather than claim success. Not introduced by this delta; do not expand scope here.

---

## H. Test quality / host reachability of round-2 lifecycle — **PASS with nuance**

Commit is honest that round-2 has **no new tests** and is inspection-verified.

| Behaviour | Host-testable today? | Notes |
|---|---|---|
| `bootRoute` consumes hold | **Yes** | `BootRouteTest` (consumption site) |
| Sweep returns tri-state / durability | **Yes** | `SweepOrphanedResidueTest` |
| `startBootReconcile` CAS + `finally` fail-closed on cancel | **Yes on host JVM if extracted** | Project already has `kotlinx-coroutines-test` (`runTest`, `UnconfinedTestDispatcher` in `LemonDropVeilControllerTest`). Inject `CoroutineScope` + sweep lambda + two `MutableStateFlow`s; cancel mid-work; assert hold + `bootReconciled`. **Not device-only.** Full `AppContainer` under Robolectric fails on AndroidKeyStore (documented in `BurnAppLocalStateTest`) — that is a construction barrier, not a “must be on device” property of the CAS/`finally` logic. |
| Splash `(splashFinished, bootDone)` ordering | **Compose-only as written** | No `compose.ui.test` dependency. Could still extract a tiny pure “both ready → decide” helper, but effect lifetime is UI-framework. |

**Judgement of the “no test” call:** Acceptable for merge **if** stated as residual risk (it is). Not fully accurate that “nothing is host-testable”: the **process-scope ownership** half is a natural `runTest` unit without building Compose infra. Worth a small follow-up extraction; **not** a reason to block this PR or to stand up full instrumentation solely for Splash ordering.

Mutation story for earlier rounds (store + `bootRoute`) remains sound. Round-2 is the gap.

---

## Findings summary

| Sev | Finding | Block merge? |
|---|---|---|
| — | No CRITICAL / HIGH / MEDIUM | — |
| **LOW** | Splash effect does not re-check `route == Splash` after `withContext` (`MainActivity.kt:711–739`); can stomp legacy upgrade Onboarding → Locked | No |
| **INFO** | Unused `ResidueSweepResult` import in `MainActivity.kt` | No |
| **INFO** | Round-2 CAS/`finally` is host-testable with a thin extraction + `runTest`; claim of “device-only / no infra” overstates for that half | No |

---

## Explicit checklist

| Item | Verdict |
|---|---|
| **A** consumption sealed | **PASS** |
| **B** `startBootReconcile` | **PASS** |
| **C** Splash gate | **PASS** (+ LOW re-check) |
| **D** new defects in `5e02b2e` | **none security-relevant** |
| **E** sweep gate + intent removal | **PASS** |
| **F.1–F.7** | **all PASS** |
| **G** exists-in-obliterate | **agree out of scope** |
| **H** tests | **acceptable residual; CAS half host-testable** |

## READY TO MERGE: **YES**

Round-2 fixed the lifecycle form of the discard/race pattern (Splash racing the hold default; composition-owned CAS strand). Verified against source; comments were not trusted. No invented blockers. Optional post-merge: re-check Splash route after IO; host `runTest` for `startBootReconcile` fail-closed publish; drop dead import.
