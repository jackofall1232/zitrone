I'll review commit `d3680570` against source only, focused on whether the round-1 recreation/dialog finding is closed and whether the fix introduces new defects.Pulling the full fix diff and the key source files for a recreation/outcome trace.Reading the process-scoped state, finish path, dialog wiring, and obliterate semantics in full.# Independent security review — Unit S round 2 (`d3680570`)

**Checkout reviewed:** `feat/0.9.3-unit-s-burn-arming` @ `d3680570`  
**Method:** source-only against `git show d3680570` / `git diff 32a530a6..d3680570`. Gradle was **not** run; suite counts below are **not adopted**.

---

## Adjudication (round-1 toast claim)

**Your adjudication is correct.**

There is no success toast (or snackbar) on arm. Success is only “dialog goes away.” Failure surfaces via `Rejected` error strings. A recreation that dismissed the dialog was therefore **indistinguishable from success**. Deferring that on “no success toast on failure” is the wrong reason; the harm is false success on a failed (or unknown) arm.

---

## A. Is the blocking finding closed?

**Yes — for Activity recreation mid-arm, the false-success class is closed.**

### Trace (recreation mid-arm)

| Step | State |
|------|--------|
| User confirms | `tryBeginBurnArm()` CAS → `BurnArmUi.Arming`; coroutine on `container.scope` |
| Rotation / config change | Composition dies; `AppContainer.burnArm` **keeps** `Arming` |
| New composition | `collectAsState()` → dialog shown, `busy = true` (spinner, non-dismissible) |
| Terminal outcome | `finishBurnArm(...)` writes into the **same** process flow |

Concrete paths after landing:

- `ArmBurn.Armed` → `Closed` → dialog gone = real success  
- `CollidesWithVault` / `DeletePending` / any throw → `Rejected(...)` → dialog **stays** with error  

### (i) Dismiss dialog while arm in flight?

**Not via the UI.** While `Arming`:

- `busy = burnArm is BurnArmUi.Arming`  
- `onDismissRequest = { if (!busy) onDismiss() }`  
- dismiss button `enabled = !busy`  

So user dismiss cannot force `closeBurnSetup()` during the flight. Recreation no longer forces a dismiss either (process-scoped state).

### (ii) Lose a terminal failure?

**Not across recreation.** Failure is a durable-in-process `Rejected` value on `burnArm`, not composition-local `remember`. A new observer re-reads it.

### (iii) Present a failed arm as success?

**Not on the recreation path.** Closing still means success, but only if state becomes `Closed`. Failures become `Rejected`, not `Closed`.

### Is `BurnArmUi.Closed` reachable from anything but `ArmBurn.Armed`?

**Yes — by design, from more than the Armed path:**

| Source | File:line | Meaning |
|--------|-----------|---------|
| Initial / default | `ZitroneApp.kt:320` | Dialog not open |
| `closeBurnSetup()` | `ZitroneApp.kt:326–328` | User cancel after `Open` / after seeing `Rejected` |
| Mapping `ArmBurn.Armed` | `MainActivity.kt:1190` (and `burnArmOutcome` at `ZitroneApp.kt:182`) | Real success |

So `Closed` is overloaded as “not showing” **and** “success after arm.” That is intentional and was true pre-fix (`burnSetupOpen = false`). What matters: **no failure mapping writes `Closed`.** Failures go to `Rejected`.

**Verdict A:** Blocking recreation false-success is **closed**. No remaining interleaving on the normal UI path still does (i)+(iii) for mid-arm recreation.

---

## B. Did the fix introduce a NEW defect?

### CAS / `tryBeginBurnArm` starvation

`beginBurnArm` (`ZitroneApp.kt:196–201`):

```kotlin
while (true) {
    val current = state.value
    if (current is BurnArmUi.Arming) return false
    if (state.compareAndSet(current, BurnArmUi.Arming)) return true
}
```

- Concurrent second claim while `Arming` → false (single-flight).  
- CAS loss against `open`/`close`/`finish` → reread; if not `Arming`, retry.  
- **No livelock/starvation** under normal writers; loop is not “spin forever waiting for Arming to clear.”

### ABA / stale continuation over a newer arm

Under the intended invariant “at most one arm coroutine while `Arming`”:

- Second `tryBeginBurnArm` fails while first is `Arming`.  
- First must `finishBurnArm` before a retry can claim.  
- So a **previous** arm cannot start after a **newer** one under that invariant.

`finishBurnArm` is an **unconditional** assign (`ZitroneApp.kt:338–340`) — **no generation / expect-`Arming` CAS.** That is safe **only while nothing demotes `Arming` without ending the flight.**

### `openBurnSetup` / `closeBurnSetup` vs `Arming` (real structural gap)

```322:328:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
    fun openBurnSetup() {
        burnArm.value = BurnArmUi.Open
    }

    fun closeBurnSetup() {
        burnArm.value = BurnArmUi.Closed
    }
```

Neither refuses `Arming`. If either ran mid-flight:

1. `closeBurnSetup()` → `Closed` → dialog gone while wipe/arm work continues.  
2. On failure, `finishBurnArm(Rejected)` **resurrects** the dialog (failure not lost — good).  
3. On success, stays `Closed` while credential is armed — looks like cancel/success ambiguity.  
4. `openBurnSetup()` mid-`Arming` → demotes to `Open` → `busy=false` → second `tryBeginBurnArm` can succeed → **two** `armBurnCredential` coroutines → last `finishBurnArm` wins (classic ABA on the flow).

**Reachability today:** dismiss is busy-gated; `openBurnSetup` is only Settings (`MainActivity.kt:1540`) behind a modal `AlertDialog`. So this is **hard to hit from the current UI**, not proven impossible under every Android dialog/back/multitask edge.

### Finding B1 — unconditional open/close/finish around `Arming`

| | |
|--|--|
| **Severity** | **LOW** (UI-busy gate + modal dialog make the bad interleaving impractical) |
| **Blocking?** | **DEFERRABLE** |
| **Where** | `ZitroneApp.kt:322–340` |
| **Scenario** | Anything that forces `openBurnSetup`/`closeBurnSetup` during `Arming` (future non-modal entry, automated call, busy-gate regression) enables dual-flight or silent success-after-“cancel.” |
| **Fix** | `closeBurnSetup` / `openBurnSetup`: no-op or refuse if `value is Arming`. `finishBurnArm`: CAS `Arming → outcome` only (or generation token). |

### Finding B2 — production path does not call `burnArmOutcome`

| | |
|--|--|
| **Severity** | **MEDIUM** (correctness-of-tests / future drift, **not** a live false-success today) |
| **Blocking?** | **DEFERRABLE** |
| **Where** | `MainActivity.kt:1186–1200` duplicates the fold; `burnArmOutcome` at `ZitroneApp.kt:178–188` is **only** used by tests |
| **Scenario** | Someone “tightens” tests’ helper or “fixes” only one copy; suite stays green while production maps a failure to `Closed` again. Commit message claims the invariant is asserted on the extracted function — that is only half true for the ship path. |
| **Fix** | `container.finishBurnArm(burnArmOutcome(outcome))` in `MainActivity`. Optionally make `finishBurnArm` private to that mapping. |

**No new BLOCKING defect found** on the reachable arming path.

---

## C. Invariant P1 (no armed flag)

| Claim | Source |
|-------|--------|
| RAM-only | `MutableStateFlow` field on `AppContainer` (`ZitroneApp.kt:320`); not prefs, not vault image, not keystore |
| Attempt-only | States: `Closed` / `Open` / `Arming` / `Rejected(Reason)` — no “credential exists” bit |
| Process death | New process → new `AppContainer` → `Closed` |
| Durable store / backup | No serialization of `BurnArmUi`; grep shows only app + unit test |
| Settings row | Still permanent, identical copy (`SettingsScreen.kt:264–275`); no armed/unarmed branch |

`burnArm != Closed` only while the user is in a setup attempt this process — not a durable oracle.

**P1 intact.**

---

## D. Passphrase handling

| Location | Holds secret? |
|----------|----------------|
| `onConfirmBurnPassword` lambda | Transient `candidate` into coroutine → `armBurnCredential` (same as before) |
| `burnArm` / `BurnArmUi` / `Rejected` | **No** — only enum `Reason` |
| Dialog fields | Composition-local `remember` (`BurnSetupDialog.kt:73–75`); **reset on recreation** (good: no process-scoped password retention) |

Not worse than pre-fix; process-scoped state is **not** a new retention sink for the credential.

---

## E. Copy accuracy (F3)

Burn plan (`ZitroneApp.kt:624–709`) wipes, in order:

- boot diagnostics, plaintext cache, notifications  
- **entire vault image** (`burnObliterate` / all slots)  
- biometric material, vault-use prefs, device key  

Then process death; next launch is fresh-install shaped. Copy:

- “everything Zitrone holds on **this device**”  
- “returns the app to a **fresh install**”  

matches **device-local** obliterate, not server account deletion (burn intentionally does not network-delete). That is accurate, not an over-claim of “wipe the relay account.”

Does **not** count vaults or say “all N vaults” → **no PD leak of vault cardinality.**

Slight honesty limit (pre-existing, not introduced): residual platform files that a fresh install also has (`EncryptedSharedPreferences` file / master key) are deliberately kept; “fresh install” is behavioral/state indistinguishability, not “zero bytes under the package.” Copy is still directionally right and better than “this vault.”

---

## F. Do the new tests discriminate?

| Test | Realistic mutation it catches |
|------|-------------------------------|
| `only a real arm closes the dialog` | Armed → Rejected or non-Closed |
| `a vault collision is reported…` | CollidesWithVault → Closed |
| `a pending delete is reported…` | DeletePending → Closed (**your mutation claim is consistent with source**) |
| `a non-durable write is reported…` | NotDurable → Closed / Armed |
| `an unexpected failure is reported…` | fail-open on unknown throw |
| `a failure landing after the composition is gone…` | Publishing outcome only into discarded composition state (structural intent of the fix) |
| `a recreation mid-arm still sees an arm in flight` | begin doesn’t leave Arming / resets to Closed |
| `a second arm cannot start while one is running` | remove single-flight / always return true |
| `a retry after a rejection is allowed` | fixed expect-`Open` CAS that rejects `Rejected` |
| `a reopened dialog starts clean` | reopen keeps prior Rejected |
| ArmBurnSlot `server-confirmed delete` | drop `vault.delete-confirmed` half of refuse check |

### Not covered (or weakly covered)

1. **Production `MainActivity` fold** (B2) — largest gap relative to the commit’s claim.  
2. `finishBurnArm` without expect-`Arming` / dual-flight ABA.  
3. `openBurnSetup`/`closeBurnSetup` during `Arming`.  
4. Real Compose recreation (`collectAsState` + dialog).  
5. Concurrent CAS under threads (only sequential unit tests).

Tests are **not** empty ceremony; fail-closed mapping + single-flight + “flow survives” are real. They do **not** fully pin the ship path.

---

## G. F1 comment accuracy

`MainActivity.kt:909–913` now says wipe landed in **0.9.2** and arming in **0.9.3**, path is **live**, and claims are version-scoped. That matches this checkout (Settings arming + lock-screen burn). **Comment is true.**

---

## Evidence claims (yours)

Not re-run. Mutation story for `DeletePending → Closed` matches what `BurnArmStateTest` asserts. Treat **573/570** as author evidence, not this review’s.

---

## Findings summary

| ID | Severity | Gate | One-liner |
|----|----------|------|-----------|
| — | — | **Closed** | Recreation false-success of failed arm |
| B1 | LOW | DEFERRABLE | open/close/finish don’t fence `Arming` |
| B2 | MEDIUM | DEFERRABLE | Production duplicates `burnArmOutcome`; tests don’t pin ship path |

No CRITICAL/HIGH/BLOCKING findings on this fix.

---

## Verdict

**READY TO MERGE**
