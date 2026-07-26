I'll start by loading the project protocol and the Unit S diff so the review is grounded in this checkout only.Next I'll pull the full unit diff and the core vault/arming sources that implement slot 0.I'll read the arming implementation and the surrounding unlock/UI paths that enforce collision refusal and fail-closed reporting.Checking burn blast radius vs warning copy, collision edge cases, and atomic write semantics.Gradle home is blocked; I'll re-run the suite with a writable home and keep reviewing collision edge cases.Checking one more rotation/report path and whether Settings is session-gated only:# Independent Security Review — Unit S (Pucker Burn ARMING 0.9.3)

**Reviewer:** independent (this checkout only)  
**Range:** `main...HEAD` on `feat/0.9.3-unit-s-burn-arming`  
**Commits:** `22baf192` → `a6753486` → `32a530a6`  
**Sources read:** `VaultImageStore.armBurnSlot`, `tryPassphrase` / `sealSlotSelfVerifying`, `atomicWrite` / `renameIntoPlace`, `BurnSetupDialog`, Settings row, MainActivity wiring, `armBurnCredential`, gate additions, `ArmBurnSlotTest`, invariant table.

---

## Collision refusal (hardest attack) — VERDICT: COMPLETE for production writers

`tryPassphrase` records the **first** match by ascending index (`VaultSlots.kt` ~211–240). Slot 0 outranks every vault. `armBurnSlot` refuses when that first match is in `VAULT_SLOT_RANGE` (1..3):

```794:800:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
            // COLLISION SWEEP — see ArmBurn.CollidesWithVault. A match on slot 0 is the RE-ARM case and
            // is fine: the seal below overwrites it.
            tryPassphrase(passphrase, decoded.slots, ops, deriver)?.let { match ->
                val collides = match.slotIndex in VAULT_SLOT_RANGE
                wipe(match.vaultKey)
                if (collides) return ArmBurn.CollidesWithVault
            }
```

| Attack | Result |
|---|---|
| Candidate = existing vault passphrase | First match ∈ vault pool → `CollidesWithVault`. Covered by unit + gate tests. |
| Candidate matches nothing | Arms. |
| Re-arm same burn passphrase (slot 0 only) | First match is 0 → not in `VAULT_SLOT_RANGE` → overwrite. Intended. |
| Vault created **after** arming with burn passphrase | `attemptUnlockOrAdd` matches slot 0 first → `Burn`, never create. Cannot plant a colliding vault afterward. |
| Concurrent arm vs create / unlock / delete markers | All take `imageLock`; marker check + sweep + seal + write are one critical section → no TOCTOU with marker writers. |
| Sweep only vs `VAULT_SLOT_RANGE` | Correct: only vault slots unlock; slot 0 match is re-arm. |
| “Slot the sweep did not consider” | Sweep is full `tryPassphrase` over all `SLOT_COUNT` slots. |

**Residual (not reachable via live writers):** if slot 0 **and** a vault already shared a passphrase (impossible through current create/arm paths), re-arm with that passphrase would keep first-match on slot 0 and would **not** re-detect the vault collision. Defense-in-depth would be “scan all vault-pool slots, not only first match.” **DEFERRABLE.**

---

## Findings

### F1 — Stale “burn still unarmed” comment (honesty)
- **SEVERITY:** Low  
- **Where:** `MainActivity.kt:911–912`  
- **Defect:** Comment still says slot 0 is unarmed until burn-setup ships / not settable. This unit **ships** that setup.  
- **Why it matters:** Future readers will trust outdated threat model prose (same class of failure the comment itself warns about).  
- **Fix:** Delete or rewrite to “armed only after Settings setup; match path is live.”  
- **Boundary:** **DEFERRABLE** (comment only).

### F2 — Author claim “the REPORT must survive” rotation is overstated
- **SEVERITY:** Low–Medium (robustness / honesty)  
- **Where:** `MainActivity.kt:1172–1208` vs comment at 1180–1182  
- **Defect:** Arm runs on `container.scope` (good for the write), but outcomes are written into composition `remember { mutableStateOf(...) }` for `burnSetupOpen` / `busy` / `error`. Rotation recreates composition; those states reset; the continuation updates **dead** state. The store commit can survive; the UI report does **not**.  
- **Why it matters:** User can lose success/failure presentation mid-arm. Safe direction for false protection is mostly preserved (no success toast on failure), but the commit message / comment overclaims.  
- **Fix:** Hold arm result in a process-scoped holder (container / ViewModel) and re-bind on recomposition.  
- **Boundary:** **DEFERRABLE** (not “believes armed when unarmed” if success is never shown; not wrong wipe).

### F3 — Warning / Settings copy understates blast radius vs multi-vault wipe
- **SEVERITY:** Medium (informed consent)  
- **Where:** `BurnSetupDialog.kt:85–94`, `SettingsScreen.kt:270–271`; collision error at `MainActivity.kt:1196–1197`  
- **Defect:** Copy says “this vault.” Production burn is a **device-local fresh install** (all slots in the shared image, prefs, keystore, caches — as the gate asserts). With second vault shipped, “this vault” can be read as “only the session I’m in.”  
- **Why it matters:** Mis-scoped mental model for an irreversible control.  
- **Fix:** PD-safe and accurate: e.g. “returns Zitrone on this device to a fresh install” / “erases everything Zitrone holds here” — without counting vaults.  
- **Boundary:** **DEFERRABLE** product/consent residual (user did elect a wipe control; not a silent wrong-target wipe bug). Not elevated to BLOCKING because it does not create an unintended wipe path in code.

### F4 — `DeletePending` unit test only plants `vault.delete-intent`
- **SEVERITY:** Low (coverage)  
- **Where:** `ArmBurnSlotTest.kt:278–284` vs code checking **both** markers (`VaultImageStore.kt:787–788`)  
- **Defect:** `serverDeleted` branch untested.  
- **Fix:** Symmetric test with `vault.delete-confirmed` (or whatever the confirmed marker filename is).  
- **Boundary:** **DEFERRABLE**.

No other defects found that make arming silently ineffective when reported `Armed`, or that arm a colliding credential into a wipe-on-unlock state via live APIs.

---

## Explicit verdicts A–J

### A. No armed flag (P1) — **PASS**
- No API readback; `ArmBurn` has no “isArmed”.  
- Settings row permanent, fixed subtitle describing the feature, not state (`SettingsScreen.kt:264–273`).  
- UI state is composition-only open/busy/error — not durable.  
- No prefs / marker / log line for armed.  
- Structure: same size / slot count / payload sizes; only slot 0 salt+wrapped bytes change (tested).  
- Forensic residual: ciphertext bytes differ (uniform either way by design) — accepted.

### B. Arming in-place, format-stable — **PASS**
- No `IMAGE_VERSION` change in this unit (still 3).  
- Reuses existing DEK; encrypts full inner image; **payloads untouched** (`VaultImageStore.kt:813–815`).  
- Slot 0 region fixed-size seal via `sealSlotSelfVerifying`.

### C. Crash atomicity — **PASS**
- Path: full outer image → `atomicWrite` → `renameIntoPlace` (fsync file, `ATOMIC_MOVE`) → dirSync (`VaultImageStore.kt:1425–1477`, arm at 815–820).  
- Pre-rename throw: previous durable image intact.  
- Post-rename: either old or new whole image; no half-armed slot.  
- Marker for arming correctly unnecessary (would be an oracle).

### D. Fail-closed reporting — **PASS**
| Outcome | UI |
|---|---|
| `Armed` | Dialog closes only (`MainActivity.kt:1190`) |
| `CollidesWithVault` | Explicit error, not success |
| `DeletePending` | Retry error |
| `NotDurable` / other throw | `onFailure` → “Couldn't save…” (`1202–1205`) |

`Armed` only after `DirSyncResult.DURABLE` (`820–821`). Self-verify seal blocks “success with never-matching wrap.”

### E. The warning — **PASS with F3 residual**
Four points present and accurate on recoverability / third-party erase / consume / silent replace (`BurnSetupDialog.kt:91–94`).  
Confirm disabled until non-empty, match, **and** checkbox (`77`, `139`).  
No flow contradiction with “cannot check” (no success checkmark).  
Blast-radius wording: see **F3**.

### F. Key material — **PASS (with standing String residual)**
- `burnKey` wiped in `finally` (`822–824`), including throws.  
- `sealSlotSelfVerifying` wipes master key; collision path wipes match vault key.  
- Passphrase is a Kotlin `String` in Compose fields and the coroutine capture — immutable, not wipeable; same class as lock-screen entry. No logging of the credential. **DEFERRABLE** platform residual.

### G. Concurrency — **PASS (with F2 residual)**
- `imageLock` around marker check, sweep, seal, write.  
- Delete markers: proven-absence, same section as write.  
- Arm refuses while delete in flight.  
- Unlock / second-vault create / obliterate serialize on same lock.  
- Long Argon2 hold of `imageLock` is a latency residual, not a correctness hole.  
- UI report vs rotation: **F2**.

### H. The gate — **PASS (discriminating); standing `terminate={}` limit**
1. `the_armed_credential_burns_and_leaves_a_fresh_install` — arms via `container.armBurnCredential`, requires `UnlockOrAdd.Burn`, then wipe + fresh-install snapshot. **Silent no-op arm → Burn assert fails.** Discriminating for the trigger.  
2. `arming_refuses_a_credential_that_also_opens_a_vault` — production crypto collision. **Missing refusal → Armed, fails.**  
3. `terminate = {}` is weaker than production process death for the **wipe** tail (pre-existing, documented). Trigger path (arm → Burn) is real before that.

### I. Unit suite — **CONFIRMED (this run)**

```text
./gradlew :app:testDebugUnitTest --tests 'com.zitrone.app.*'
JAVA_TOOL_OPTIONS=-Djna.tmpdir=/tmp/jna
GRADLE_USER_HOME=/tmp/gradle-home

tests=562  failures=0  errors=0  skipped=3  passed=559
ArmBurnSlotTest: 10/10 passed
BUILD SUCCESSFUL
```

Matches claim **562 / 559 / 0 / 3**.

### J. Overstatements — **minor only**
- F1 stale unarmed comment.  
- F2 “report must survive” vs `remember` state.  
- Commit prose about suite/gate is otherwise consistent with code.  
No commit claim that invents a durability or collision guarantee the code lacks.

---

## Blocking boundary

| Item | Side of line |
|---|---|
| Collision refusal completeness (live writers) | Clean — no BLOCK |
| Fail-closed `Armed` / `NotDurable` | Clean — no BLOCK |
| Silent non-arm when user told armed | Not found |
| Accidental wipe-on-ordinary-unlock via arming | Not found |
| F1–F4 | All **DEFERRABLE** |

---

## READY TO MERGE?

**Yes — READY TO MERGE** from this reviewer’s reading of source and suite evidence.

The correctness core holds: collision refusal matches first-match / slot-0 precedence; arming is format-stable and whole-image atomic; success is durability-gated; P1 has no durable oracle; unit tests discriminate arming vs no-op and cover the hazard the invariant table named.

Ship residuals (do not block): F1 comment fix, F2 process-scoped arm result, F3 blast-radius copy, F4 second marker test, gate `terminate={}` standing limit, optional multi-match re-arm defense-in-depth.
