I'll perform an independent adversarial security review of the biometric-guard delta only—read source and report findings, no fixes.Checking `authenticateCrypto`, call sites, and cancellation/lifecycle interactions.# Adversarial Security Review — PR-3 Unit 1, Round 4  
**Range:** `7fbcd89..dfba539` · **Branch:** `feat/0.9.2-vault-pr3-unit1-biometric-guard` · **Tip:** `dfba539`  
**Scope:** Biometric enable single-flight only (`MainActivity.kt`). No fixes proposed. No files edited.

---

## Round-3 finding status

### Concurrent-enable race — **CLOSED**

**Mechanism (source):**  
`startBiometricEnableFromSession` claims `biometricEnabling` with `compareAndSet(false, true)` **before** `isEnabled()`, `newEncryptCipher()`, prompt, seal, or save (`MainActivity.kt` ~489–512). A second concurrent call returns `onResult(false)` at ~489 without touching the Keystore alias or wrap store.

Only one production caller runs `newEncryptCipher()` (`startBiometricEnableFromSession`). `enableBiometricFromSession` only seals/saves a caller-supplied cipher. `MainActivity` is `singleTask` — one live Activity instance under normal use; the flag is instance-scoped on that Activity.

**Terminal release coverage (claimed paths):**

| Terminal path | Releases? |
|---|---|
| Single-flight reject (CAS fail) | N/A — never claimed; `onResult(false)` only (~489) |
| `isEnabled()==true` refuse | `release(false)` (~501) |
| Keygen `catch (Exception)` | `release(false)` (~509) — includes `CancellationException` |
| Prompt success | `onResult(ok)` bound to `release` (~528) |
| Prompt error/cancel | `onResult(false)` bound to `release` (~532) |

**Race outcome:** Two overlapping enables cannot both run `newEncryptCipher` / seal / save on the same live Activity. Round-3 alias thrash + mutual `deleteKey` orphan is closed.

---

## Findings

### F1 — **LOW** · incomplete release if prompt launch throws after claim  
**FILE+FUNCTION+line:** `MainActivity.kt` · `startBiometricEnableFromSession` ~505–512 · `startBiometricEnablePrompt` / `authenticateCrypto` ~516–408  

**MECHANISM:** After CAS, the only `try/catch` wraps `newEncryptCipher()`. `startBiometricEnablePrompt` → `authenticateCrypto` → `prompt.authenticate(...)` sits **outside** that try. An exception thrown there (e.g. `IllegalStateException` from BiometricPrompt when the host is not in a valid state) aborts the coroutine **without** calling `release`. `biometricEnabling` remains `true` on that **same live instance**.

**SCENARIO:** Enable starts → keygen succeeds (alias deleted+regenerated) → `authenticate(...)` throws → all further enable attempts (enroll offer / Settings toggle) hit CAS fail → immediate `onResult(false)` until Activity recreation (rotation) or process death. No wrap is saved; no existing binding is destroyed (entry `isEnabled()` was false). Not an A/B distinguisher (slot-agnostic lockout). **New defect class introduced by the single-flight flag** (prior code had no sticky gate).

---

### F2 — **INFO** · residual same-instance strand if BiometricPrompt never delivers a terminal callback  
**FILE+FUNCTION+line:** `MainActivity.kt` · `biometricEnabling` ~122; `authenticateCrypto` ~387–394; enable holds flag across prompt ~512→528/532  

**MECHANISM:** After keygen, the coroutine ends; the flag stays true until `onAuthenticationSucceeded` / `onAuthenticationError`. Soft fail (`onAuthenticationFailed`) correctly leaves the flag held (prompt stays open). If a same-instance path drops the terminal callback without destroy (OEM / framework edge), the live instance stays locked out of enable.

**SCENARIO:** Prompt dismissed or host stuck without `onError` while Activity survives. Recreation clears the instance field (design intent; comment ~117–119). Process death clears it. Rotation/process-death **cannot** strand a *new* instance. Lifecycle cancellation during keygen does **not** strand: `CancellationException` ⊆ `Exception` → `release(false)` (~508–509).

**Not a reopening of the concurrent-enable race.**

---

## Binding checks (source)

### 1. Race CLOSED  
**Yes.** CAS admits exactly one; concurrent attempt does not call `newEncryptCipher`. See table above.

### 2. No stranding / lockout  
| Case | Result |
|---|---|
| Rotation / process-death mid-prompt | New Activity → fresh `false` flag. Old instance callbacks only touch dead instance. **OK** |
| `lifecycleScope` cancel during keygen | CE caught → `release(false)`. **OK** |
| Same-instance, claim then no `release` | **Possible:** F1 (throw after claim); F2 (missing terminal callback). |

### 3. Belt guard  
With enable serialized on one Activity, a second enable cannot install a wrap mid-flight. Per-slot belt in `enableBiometricFromSession` (`ZitroneApp.kt` ~565–567) remains defense-in-depth for session/slot change between entry gate and seal.

**Disable ∥ in-flight enable** (Settings OFF vs prompt still open; `disableBiometricThen` does not consult `biometricEnabling`):  
- clear/deleteKey vs seal/save race → either disabled, or wrap present with key deleted → unlock fails → invalidation/unavailable cleanup path.  
- **No repoint** (save always uses current `session.slotIndex` under belt; disable does not write another slot).  
- **No destruction of a pre-existing valid binding** via enable: enable only starts when `isEnabled()==false`.

### 4. A/B identity + never-repoint  
- `biometricEnabling`: no slot term; CAS reject is slot-agnostic.  
- Enroll offer / Settings / lock surfaces unchanged this delta; offer still `biometricEnrollOffered` (slot-free).  
- A-only rule still only on write path (`biometricEnableAllowed` + entry `isEnabled()`).  
- First-enable-wins / same-slot-after-clear unchanged.  
- Single wrap never repointed: belt + global `isEnabled()` before destructive keygen intact.

### 5. No new Critical/High defect on other surfaces  
| Surface | Notes |
|---|---|
| Onboarding enable / Settings / re-offer | Same entrypoint + single-flight; second concurrent enable fails closed (intended). |
| Disable / unlock / account-delete | Separate paths; no coupling to `biometricEnabling`. |
| Hold flag across interactive prompt | Serializes enable only; not a deadlock with unlock (`unlocking` / `unlockInFlight` are independent). Legitimate second enable waits until first terminal path — or is refused while in flight (UX, not vault integrity). |
| Interaction with PR-2 unlock single-flight | None in source. |

### 6. Holistic @ `dfba539`

| Invariant | Verdict |
|---|---|
| (a) never-repoint | **Holds** — write-path belt + pre-keygen `isEnabled()` |
| (b) disallowed / interrupted / concurrent enable destroy or orphan **existing** binding | **No** for concurrent enables (CLOSED). Disallowed enable side-effect-free at entry. Interrupt after keygen may leave an **orphan key without wrap** when no binding existed (pre-existing exception path; not an existing wrap wipe). F1 can lock out re-enable until recreation. |
| (c) A-vs-B distinguisher on biometric surfaces | **No new tell** from this delta — flag and gates are slot-agnostic; enroll/settings/lock identity unchanged |

**Remaining Critical / High / Medium:** none.  
**Remaining Low / Info:** F1 (LOW), F2 (INFO).

---

## Round-3 reopen check

| Prior finding | Status |
|---|---|
| Concurrent-enable race (alias thrash / orphan via overlapping enable) | **CLOSED** — not reopened |
| Different-slot belt-refuse timing (addressed prior via global `isEnabled` before keygen) | **Not reopened** — single-flight does not reintroduce slot-conditional work before the global gate |

---

## Overall verdict

**CLEAN** — round-3 concurrent-enable race is closed in source; no Critical/High/Medium remaining; residual F1/F2 are same-instance enable availability under throw/missing-callback, not a real-vs-decoy distinguisher and not a never-repoint break.
