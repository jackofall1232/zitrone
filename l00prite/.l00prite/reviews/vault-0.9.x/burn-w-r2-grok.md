I'll independently review the cumulative burn-unit changes against source, starting with the protocol docs and the two commits plus the full main...HEAD diff.There's a third self-audit commit on the branch; I'll review the full main...HEAD unit as mergeable, with focus on the two named commits and the round-1 fix claims.Next I'll read the core burn/obliteration paths and boot reconciliation against the full source, not just the commit messages.Working tree has uncommitted edits (including a `tryBeginTerminalWipe` claim). I'll review committed `main...HEAD` as the merge unit, and separately inspect the WIP to avoid confusing the two.Checking one more concurrency detail on the committed terminal-wipe path, then finalizing the verdict.# Round 2 Independent Security Review — Unit W (Pucker Burn wipe)

**Scope reviewed:** committed `main...HEAD` on `feat/0.9.2-burn-unit-w-wipe`  
(`645b8a8` + `764845e` + `813245b` self-audit). Claims checked against source, not commit messages.

**Note:** The working tree has uncommitted edits (exclusive terminal-wipe claim, cache tristate, etc.) labeled “round-2 review.” Those are **not** part of the merge unit reviewed below.

---

## Findings

### 1. HIGH — Concurrent burn workers co-own `terminalWipe`; first finisher can strand a second obliteration over a successor vault

**Where:**  
`MainActivity.kt` committed `onBurn` (`beginTerminalWipe()` then `container.scope.launch { … endTerminalWipe() }`)  
`UnlockController.kt` committed `beginTerminalWipe()` / `endTerminalWipe()` (assignment, not exclusive claim)

**Defect:**  
`beginTerminalWipe()` is set-true, not an exclusive claim. A second caller becomes a silent co-owner. Each worker’s `finally` calls `endTerminalWipe()`, so the **first** to finish reopens session creation while the **second** may still be inside `obliterateForBurn()`.

Reachable sequence once slot 0 is armed:

1. Burn match → worker A starts (gate up).  
2. Activity recreation resets composition-local `unlocking` (`remember { false }`).  
3. Second burn entry → worker B starts; `beginTerminalWipe()` is a no-op.  
4. A finishes → `endTerminalWipe()` → UI Onboarding.  
5. User creates a fresh vault.  
6. B’s `obliterateLocked()` deletes that vault — self-inflicted total wipe of a post-burn successor.

`attemptPassphrase`’s single-flight does **not** cover this: it is released before `onBurn()` runs. Account-delete was safe with one live session; burn is lock-screen, multi-dispatchable after rotation.

**Why it matters:**  
Unit W ships the wipe mechanism. D.6 requires a non-stranded, rotation-safe terminal gate. This fails that bar and becomes live as soon as Unit S arms slot 0.

**Fix:**  
Exclusive claim (`tryBeginTerminalWipe(): Boolean`); only the winner starts work and only the winner `endTerminalWipe()`s. Losers surface uniform failure and must not release a gate they do not own. (WIP in the tree sketches this; it must land and be tested before merge.)

---

### 2. MEDIUM — Fix (3) incomplete: `clearCacheDir` still fail-opens on unstattable path via `!exists()`

**Where:** `ZitroneApp.kt` `clearCacheDir` (committed):

```kotlin
if (cacheDir == null) return true
if (!cacheDir.exists()) return true  // ← fail-open
val entries = cacheDir.listFiles() ?: return false
// re-list prove…
```

**Defect:**  
Round-1 fixed `listFiles() == null → true`. The early `!exists()` still treats “stat failed / indeterminate” like “absent” and returns success **without deleting or re-listing**. That is the same fail-open class as (3), one branch earlier.

**Why it matters:**  
Does not abort the burn (policy: cache is non-gating), but it can report `BurnResult.plaintextCacheCleared = true` over surviving plaintext and skip the documented “could not clear” residual honesty for that run. Cold-start retry still re-attempts, so residual is not permanent by design — but the success signal remains wrong.

**Fix:**  
`Files.notExists(cacheDir.toPath())` for proven absence only; otherwise fall through to list/delete/re-list fail-closed path.

---

### 3. LOW — Post-obliteration cache pass short-circuits when the first pass returns true

**Where:** committed `burnVault()`:

```kotlin
val plaintextCleared = wipeAppLocalStateForBurn()
imageStore.obliterateForBurn()
val plaintextClearedNow = plaintextCleared || clearCacheDir(app.cacheDir)
```

**Defect:**  
If the pre-obliteration pass is `true`, the post-obliteration pass never runs. SECURITY_MODEL / `BurnResult` kdoc claim a retry **after** obliteration; the implementation only retries when the first pass failed.

**Why it matters:**  
On the lock-screen path there is no live session writing cache, so practical impact is small. Still a policy/code mismatch and weaker evidence for the residual flag.

**Fix:**  
Always run the post-obliteration clear; make that result authoritative for `BurnResult`.

---

### 4. LOW — Test claims failure-on-survive but only asserts success

**Where:** `BurnAppLocalStateTest`  
`` `cache clear reports failure when content survives the delete pass` ``

**Defect:**  
Creates nested content, calls `clearCacheDir`, asserts `true` and empty. Name/comment describe a failure shape that is never produced. Vacuous relative to its claim.

**Why it matters:**  
Reviewers and future arming work may believe undeletable-file reporting is covered. It is not (Robolectric permission limits are real; the honest move is rename + document the gap).

**Fix:**  
Rename to the success path it actually tests; state that “delete fails, content remains → false” is still untested without a filesystem seam.

---

### 5. INFO — `BurnResult.plaintextCacheCleared` is computed then discarded

**Where:** `MainActivity` success decision:

```kotlin
val completed = runCatching { container.burnVault() }.isSuccess
completed && container.burnObliterationComplete()
```

**Defect / note:**  
Runtime never reads `BurnResult`. Residual disclosure is documentation-only (`SECURITY_MODEL.md`), which is correct under duress (no UI tell). The type documents policy; it does not enforce observability. Acceptable if intentional; otherwise wire diagnostics-only logging off the critical path.

---

### Not counted as new defects (per brief / verified)

- **`File.exists()` verify inside `obliterateLocked`** — pre-existing destroy pattern; burn path additionally requires `obliterationComplete()` (`Files.notExists` ×4), so live burn success is stricter than the inherited verify alone.  
- **Slot 0 unarmed** — `createVaultSlots` leaves slot 0 as random filler; production Burn match is unreachable until Unit S.  
- **`completeInterruptedBurn` vs mid-create** — create is DEK-then-bin (inverse signature); self-audit defers when `delete-confirmed` present. Safe on every cold start for recoverable vaults.

---

## Verdicts

### A. Round-1 fixes (1)(2)(3) complete?

| Fix | Verdict |
|-----|---------|
| **(1) Burn success** | **Complete.** `runCatching{burnVault()}.isSuccess && burnObliterationComplete()`; `obliterationComplete` tristate-proves bin, dek, both temps. Surviving dek/temp cannot present as success. |
| **(2) Reconciler** | **Complete.** `imageBearingFilesProvenAbsent()` + marker tristate; live image cannot have intent cleared. |
| **(3) Cache clear** | **Partial.** `listFiles()==null` and discarded result fixed; early `!exists()` still fail-open (Finding 2); post-oblit short-circuit (Finding 3). |

No remaining fail-open in **image** burn success or **reconciler**. Remaining fail-open is limited to **cache residual reporting**.

### B. New defects from the fixes?

| Item | Verdict |
|------|---------|
| **`completeInterruptedBurn()`** | **Safe for recoverable vaults.** Trigger: confirmed proven absent, DEK proven absent, bin present. Mid-create is inverse. Confirmed-present defers to D2c. Media-loss of DEK only is already unrecoverable. Safe on every cold start. |
| **`BurnResult` / retry cache** | Retry gated on `!exists()` vault; vault-less only. No state leak. Short-circuit / exists fail-open (Findings 2–3). Flag discarded at UI (INFO). |
| **Boot `LaunchedEffect`** | Re-routes only when completion returned true and session is null. No conflict with confirmed → DeleteIncomplete. Brief Locked→Onboarding flash possible; not a security fail-open. **Does not fix** dual-burn gate (Finding 1). |

### C. Cache non-abort policy

**Agree.** Under duress, refusing key destruction because a staged photo is locked leaves the entire vault readable — strictly worse. Keys-first / fail-closed image proof is the hard guarantee; cache is best-effort with post-wipe + cold-start retry; SECURITY_MODEL discloses residual honestly and sufficiently **for the image vs cache split**. Broaden wording slightly: **all** pre-image app-local cleanups are tolerated (biometric/settings/etc.), so a “successful” burn can leave non-image residue — already partially described; cache is the only unencrypted user content.

### D. Round-1 binding items (re-verified)

| # | Verdict |
|---|---------|
| **D.1 destroy equivalence / keys-first** | **Accept.** Confirmed marker is durable before unlinks; every crash re-enters DeleteIncomplete → idempotent destroy. Keys-first mid-window is cryptographically better for burn and safe for destroy. `keysFirst` param unnecessary. |
| **D.2 Marker clear after durable unlinks** | **Hold.** `obliterateLocked` order: unlink → exists verify → dirSync → `clearBothMarkersDurably`. Reconcile only when image-bearing files proven absent. No clear over live image. |
| **D.3 Boot reconciliation** | **Hold.** Intent orphan clear only when image-bearing absent + confirmed absent + intent present. Image-absent never routes to DeleteIncomplete without confirmed. Keys-first hole covered by `completeInterruptedBurn` (with D2c deferral). |
| **D.4 WRITER/READER** | **Hold.** Burn never writes confirmed/intent; only clears after proven gone. Routing readers for auto-destroy still require confirmed. Auth-protection `hasDeleteIntentMarker` unchanged in bias. |
| **D.5 Reachability / wiring** | **Hold for current product.** Slot 0 unarmed; wipe only `PassphraseOutcome.Burn → onBurn` from lock-screen passphrase path. `createVaultAndPublish` does not use Burn. **Wiring correctness under concurrency fails D.6** (Finding 1) once armed. |
| **D.6 Concurrency/lifecycle** | **Fail (Finding 1).** Gate not exclusive; rotation-safe claim false for dual burn. Gate not stranded on single worker (`finally`). No session resurrection of burned keys via normal unlock while gate held; risk is **successor** obliteration by straggler. |
| **D.7 Fail-closed overall** | **Image path yes.** Partial burn cannot present as success after (1). App-local cleanups before image can leave worse-than-untouched on failed burn (documented; passphrase still works). Dual-burn is a distinct fail-closed failure mode (destroys too much later). |

### E. `File.exists()` inside `obliterateLocked`

**Agree with scoping.** Tightening changes D2c destroy behavior. Burn adds `obliterationComplete()` tristate, so burn success is not solely dependent on the inherited verify. Do not count as a new Unit W defect; track separately if desired.

### F. Test quality

| Covered well | Gap |
|--------------|-----|
| Destroy/burn end state, no confirmed on burn, marker order, keys-first unreadable, reconcile surgical cases, obliterationComplete dek/temp, completeInterruptedBurn + create inverse + D2c defer, slot 0 unarmed | Vacuous “survive delete” test (Finding 4) |
| Unlistable dir fail-closed | `!exists()` early return untested |
| | Dual-burn / exclusive gate untested |
| | MainActivity success compound (`isSuccess && obliterationComplete`) not integration-tested |
| | EncryptedSharedPreferences production path (disclosed E1) |
| | Truly undeletable file residual |

---

## READY TO MERGE?

# **NO — not on committed `HEAD`.**

Round-1 fixes **(1)** and **(2)** are real and complete. **(3)** is incomplete. The dual-burn terminal-wipe co-ownership race is a mechanism defect that violates D.6 and will matter the moment slot 0 is armed; it should not land as “reviewed mechanism.”

**Before merge:**

1. **Required:** exclusive terminal-wipe claim for burn (Finding 1) + a test that a second `onBurn` does not start work / does not release another’s gate.  
2. **Required:** tristate absence in `clearCacheDir` (Finding 2).  
3. **Should:** always run post-obliteration cache clear (Finding 3); fix vacuous test naming (Finding 4).

Uncommitted WIP appears to start (1)–(2); treat that as incomplete until committed, reviewed, and tested — not as part of this merge verdict.
