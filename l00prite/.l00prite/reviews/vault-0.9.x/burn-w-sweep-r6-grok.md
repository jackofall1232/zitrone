I'll perform an independent Round 6 security review of the cumulative unit: read the protocol, inspect the diff and key sources, and re-run the test suite myself.Next I'll examine the round-5 delta and the core vault/burn source files.Reading the core decision functions, burn lifecycle, and apply-once machinery in source.Checking a critical apply-once race: claim-before-suspend can consume a completion without delivering UI.Gradle hit a permissions error — retrying with a writable Gradle home and continuing source analysis.Cached test results aren't enough — forcing a full re-run for independent counts.Investigating the test environment failure and collecting accurate pass/fail numbers.# Round 6 Independent Security Review — Unit W (Pucker Burn wipe)

**Branch/HEAD:** `feat/0.9.2-burn-unit-w-wipe` @ `587282e` (code delta under review: `800d7ab` + cumulative `main...HEAD` excluding `l00prite/`)  
**Scope:** cumulative unit as it would merge  
**Method:** source-derived only; tests re-run independently  

---

## Verdict

**NOT READY TO MERGE**

One **HIGH** fail-open remains in the apply-once / recreation path. Round-5 fixes largely land and the pure decision tables are sound, but apply-once + Splash re-seed reintroduces the unit’s core forbidden presentation for a specific failed-burn shape.

---

## Findings

### F1 — HIGH — Failed-but-clean burn presents as fresh install after Activity recreation

**Where:**  
- `MainActivity.kt` ~623–631 (composition seeds `Route.Splash` when `session == null`)  
- `MainActivity.kt` ~830–883 (single burn applier + `tryApplyBurnCompletion` before UI write)  
- `MainActivity.kt` ~705–752 (Splash → `bootRoute`)  
- `ZitroneApp.kt` ~1509–1516 (`postBurnRoute`)  
- `ZitroneApp.kt` ~1459–1480 (`bootRoute`)  
- `VaultImageStore.kt` ~1173–1198 (`obliterateLocked` throws *after* unlinks on non-durable `dirSync` / failed marker retire)

**Defect:**  
`postBurnRoute` correctly keeps a **failed** burn on `LOCKED` even when image-bearing files are already gone:

```text
burnReportedSuccess=false && imageBearingProvenAbsent=true → LOCKED
```

That is the fail-closed arm for:

- `dirSync != DURABLE` after full unlink  
- marker retire failure after durable unlink  

(`obliterateLocked` unlinks first, then durability/markers; throw ⇒ `burned=false`, files may still stat absent.)

The process-scoped applier applies that once via generation CAS. On a later Activity recreation:

1. `tryApplyBurnCompletion` returns **false** (already claimed).  
2. Composition re-seeds `route = Splash` (no session).  
3. Splash / boot re-derive call **`bootRoute` only** — no `burnReportedSuccess`.  
4. With `vaultImagePresent=false`, `residueSweepHold=false`, `vaultProvenAbsent=true` → **`BootRoute.ONBOARDING`**.

So the **authoritative** failed-burn result is discarded, and a **weaker** cold-start function presents **fresh install** over a burn that did **not** fully take.

This is pattern (a)+(c)+(e) from the standing instructions: authoritative `BurnCompletion.obliterated` exists; Splash is a second authority with an incomplete input set; the sibling path is not generation-aware.

**Why it matters:**  
Central invariant: *a burn that did not fully take must never present as post-burn ≡ fresh install.*  
This path does exactly that after an ordinary rotation (or any recreation) following that failure shape. Slot 0 is unarmed today, but this is the mechanism under review.

**Concrete fix (any one of):**

1. **Process-scoped hold** (preferred, mirrors `residueSweepHold`): when publishing/applying a failed completion with proven absence, set e.g. `burnFailureHold=true` for process lifetime; add it to `bootRoute` ahead of `vaultProvenAbsent → ONBOARDING`.  
2. **Feed `burnCompletion` into boot consumers** on recreation: if a non-null completion exists and `!obliterated`, force `LOCKED` (or re-run `postBurnRoute` with carried `obliterated`).  
3. **Do not apply-once away failure holds**: apply-once may suppress re-painting `UNIFORM_FAILURE` after a *successor* vault, but must not suppress the failed-clean hold when disk still stats empty.

Also move `tryApplyBurnCompletion` to **after** IO and **immediately before** non-suspending UI writes (see F2), so cancel cannot consume a generation without delivery.

---

### F2 — MEDIUM — Apply-once claim runs before a cancellable suspend (consume without deliver)

**Where:** `MainActivity.kt` ~846–850  

```kotlin
if (!container.tryApplyBurnCompletion(completion.generation)) return@LaunchedEffect
val (confirmed, provenAbsent) = withContext(Dispatchers.IO) { ... }
// UI writes only after this
```

**Defect:**  
Kdoc claims the generation is claimed “immediately before a LIVE composition writes.” In source it is claimed **before** `withContext(IO)`. Cancellation at that suspend (recreation mid-stat) marks the generation applied with **no** UI write.

**Why it matters:**  
Same class as round-3 “outcome published to a disposed tree,” inverted through the guard meant to prevent it. Success is often rescued by Splash; the failed-clean case is **not** (F1).  

**Fix:** Perform disk reads first; `tryApply` only in the non-suspending window immediately before mutating `route` / `vaultExists` / `lockError`. Optionally release claim on cancel (harder than reorder).

---

### F3 — LOW — Apply-once is tested as a duplicated stand-in, not at the consumption site

**Where:** `BurnApplyOnceTest.kt` (private `Claimer` copy of CAS semantics); production call only at `MainActivity.kt:846`.

**Defect:**  
Suite proves CAS uniqueness, not that the applier calls `AppContainer.tryApplyBurnCompletion`, not claim-vs-suspend ordering, not Splash interaction after apply. “A test that a value is computed is not a test that it is used.”

**Fix:** Host-JVM test of a small extracted “apply burn completion” function that takes `(claim, readDisk, applyUi)` and asserts cancel-after-claim does not strand; or Robolectric composition test for recreation after failed-clean burn.

---

### I — Note (not a new defect)

`File.exists()` inside `obliterateLocked` verify is pre-existing, inherited from `destroy()`, and out of scope as stated. **Agree — not counted.**  
Sibling tristate discipline (`Files.notExists`) is correctly used on the sweep/reconcile paths that this unit added.

---

## Explicit verdicts A–I

| Item | Verdict |
|------|---------|
| **A. Sibling call sites** | **Pass for the round-5 surface, with one related miss.** Dispatcher no longer writes UI / no `if/else` over `PostBurnRoute`. Sole `postBurnRoute` consumer uses exhaustive `when`. Three `bootRoute` consumers all pass the full five-arg set; all three set `vaultExists = present && !legacy`. **Sibling gap:** Splash / boot re-derive is still a **second routing authority** for “may we show onboarding?” after a burn, without `burnReportedSuccess` (F1). |
| **B. Single-applier invariant** | **Mostly holds; incomplete under cancel/recreation.** Dispatcher: no UI writes after `signalBurnCompleted` (verified ~1086–1096). Only observer applies burn UI. `tryApplyBurnCompletion` is process-scoped CAS. **Defeat modes:** (1) claim-before-suspend (F2); (2) successful apply of LOCKED then recreation → Splash overrides with weaker rule (F1). Completion can be consumed and not delivered; or delivered once then **undone** by Splash. |
| **C. Default-parameter removal** | **Pass.** `bootRoute(..., legacyImage: Boolean)` and `postBurnRoute(...)` have **no** boolean defaults. No other safety-decision function on this surface re-introduces omit-to-default for those inputs. (`runBootReconcile`’s `afterPublish: () -> Unit = {}` is hygiene, not a safety input set.) |
| **D. Table completeness** | **Pass for residual states the sweep gates on.** Writers of `{dek/tmp residue, no bin}`: interrupted create (DEK-first), partial burn (verify after keys-first unlinks), `retireLegacyImage` (bin-then-dek) — row **1b** present. Temps: `renameIntoPlace` crash windows. Live image / confirmed-delete / indeterminate: gates refuse. `completeInterruptedBurn` owns inverse `{bin, no dek}`. No additional production writer found that the gate wrongly sweeps or wrongly strands. |
| **E. Test suite (I ran)** | **Observed after env fix (writable `user.home` + `jna.tmpdir`; Gradle via extracted 8.7 + `GRADLE_USER_HOME=/tmp/gradle-home`):** **529 tests, 0 failures, 0 errors, 3 skipped, 526 passed.** `BUILD SUCCESSFUL`. Matches commit claim. Initial bare runs failed on read-only JNA/Robolectric homes in this container — environmental, not product regressions. Unit W suites: BurnObliterate 26, Sweep 13, BootRoute 10, BootReconcile 8, PostBurn 8, BurnApplyOnce 5, UnlockController 20 — all green. |
| **F. Stated fixes landed** | **Pass.** `tryApplyBurnCompletion` defined and **called** at applier; session collector uses `vaultExists = imagePresent && !legacyNow`; dispatcher UI removed; `IGNORE_STALE` deleted; defaults removed; row 1b in sweep kdoc. |
| **G. Cumulative unit re-check** | **destroy/burn obliterate:** keys-first unlinks; markers only after durable absence — holds. **Boot healers:** sweep → completeInterruptedBurn → reconcileOrphanedBurnMarkers on process scope; fail-closed default hold — holds. **Slot 0 unarmed** in `createVaultSlots`; wipe only via lock-screen `PassphraseOutcome.Burn → onBurn` — holds. **Fail-closed hole:** F1 (partial/non-durable burn can present success after recreation). Concurrency: exclusive `tryBeginTerminalWipe` — holds. |
| **H. Testability** | **Host-JVM / Robolectric (already deps):** pure `bootRoute`/`postBurnRoute`; store obliterate/sweep/reconcile; `runBootReconcile` lifecycle; CAS apply-once primitive; unlock gate races; app-local wipe with Robolectric (`BurnAppLocalStateTest`). **Needs small extraction (still host-JVM):** claim-then-cancel vs claim-after-IO; “failed-clean burn + recreation routing” if Splash decision is folded into a pure function taking `burnCompletion`. **Genuinely needs Compose UI test:** `LaunchedEffect(burnCompletion)` vs rotation disposing mid-`withContext`; Crossfade route seed Splash vs ChatList; spinner/`lockError` composition-local reset; veil over `vaultExists`. `compose-ui-test-junit4` not declared. |
| **I. exists() in obliterateLocked** | **Agree out of scope** (pre-existing `destroy` inheritance). |

---

## Round-5 claim audit (F)

| Claim in `800d7ab` | Landed? |
|--------------------|---------|
| Generation apply-once CAS | Yes (`ZitroneApp.kt` ~840–857; called ~846) |
| One applier; dispatcher no UI | Yes |
| `IGNORE_STALE` removed | Yes |
| Session collector `vaultExists && !legacy` | Yes (~949) |
| Defaults removed on safety params | Yes |
| Table row 1b `retireLegacyImage` | Yes (kdoc only; behavior already correct) |

---

## What is solid

- Fail-closed **success** path: onboarding only if `burnReportedSuccess && imageBearingProvenAbsent` (truth table tested).  
- Dispatcher success proof: `burnVault()` success **and** `burnObliterationComplete()` — not `hasVault()`.  
- Orphan sweep gates + row 6b (intent alone does not strand residue).  
- Boot ownership on process scope + Splash waits on `bootReconciled`.  
- Reachability: mechanism only; slot 0 filler; wipe not on create path.

---

## READY TO MERGE?

### **No.**

Blocker: **F1 (HIGH)** — apply-once + Splash second authority can present a non-successful burn as fresh install after recreation. That is the unit’s defining fail-closed property.

**F2** should be fixed in the same change (claim ordering). **F3** is coverage debt, not a merge blocker alone.

I am explicitly saying a previous-round acceptance of “generation apply-once fully solves stale completion” is **incomplete**: it fixes success-replay tells and failed-burn *error repaint*, but **opens** failed-clean → onboarding on the Splash sibling path.
