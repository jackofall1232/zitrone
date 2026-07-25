I'll conduct an independent adversarial security review of the delta only—findings, no fixes. Starting with the protocol context and the full `7348c53..7a7cb8d` diff against source.Reading the full functions and call paths to verify each prior finding against actual source.Reading the critical call paths in ZitroneApp, MainActivity, and VaultLockManager.# Adversarial security review — delta `7348c53..7a7cb8d`  
**Branch:** `feat/0.9.2-vault-pr2-router`  
**Scope:** PR-2 router fix round (triple-entry creation gate). Findings only — no fixes.

---

## Prior findings — closure status

| Prior | Claimed fix | Status |
|--------|-------------|--------|
| **(High) Grok F1** — biometric unlock never reset ritual | `publishSession` → `resetCandidate()` on `published` | **CLOSED** on the intended exploit path (verified below). **Low residual** under non-fatal post-publish exceptions (new finding R1). |
| **(High/Med) Codex#1 / Grok F3** — unsync `candidateHash`/`candidateCount` | `@Synchronized` on mutators + readers | **CLOSED** |
| **(Med) Codex#2** — cancel kept streak | `resetCandidate` on `CancellationException` before rethrow | **CLOSED** |
| **(Low) Codex#3** — compare short-circuit on first attempt | Always `MessageDigest.isEqual` vs `NO_CANDIDATE` | **CLOSED** for the digest compare (control-flow after compare remains data-dependent — residual R2, Info) |
| **(Low) Grok F4** — unexpected throw skipped backoff | `recordFailure()` restored in `onFailure` | **CLOSED** (no double-count with in-router failure accounting) |
| **(Low) Grok F5** — `candidateCount` overflow | Cap increment at `CREATE_THRESHOLD` | **CLOSED** (no missed create) |
| **(Info) both** — `resetRitual` default no-op | Parameter required | **CLOSED** |

---

## Binding checks

### 1. F1 CLOSURE — mid-ritual survivor after unlock

**Single publish funnel.** Vault session publish only goes through `AppContainer.publishSession` (`ZitroneApp.kt` ~573–594). Callers:

| Path | Function | Calls `publishSession`? |
|------|----------|-------------------------|
| Passphrase match/create | `attemptPassphrase` | Yes (`Unlocked` / `Created`) |
| Biometric | `unlockWithBiometric` | Yes (only success path) |
| Onboarding create | `createVaultAndPublish` | Yes |

`UnlockController.unlock(prepared)` is only invoked from `publishSession`. The no-arg `unlock()` is wired to `error("vault install builds sessions via unlock(prepared)")` and is unused for vault open.

**Reset on publish (delta):**

```584:592:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
        if (published) {
            settingsRepository.setOnboardingDone(true)
            // ...
            unlockRouter.resetCandidate()
        }
```

**Grok exploit re-run (fixed code):**

1. Enter `P` → `decideCreate` streak 1, `Rejected`, streak kept.  
2. Enter `P` → streak 2.  
3. Biometric success → `unlockWithBiometric` → `publishSession` → `published == true` → **`resetCandidate()`** → streak 0.  
4. Non-`onStop` re-lock (e.g. forced logout via `unlockController.lockIf`) → lock screen; candidate already empty.  
5. Enter `P` once → new candidate at streak 1 → **`create == false`**. Does not create.

Passphrase unlock already called `resetCandidate()` before `publishSession` (`attemptPassphrase` Unlocked/Created/Burn); publish reset is redundant there. Biometric previously had **no** router reset; that was the F1 hole — closed on the success path.

**Bypass of `publishSession`?** No vault unlock that yields a live session bypasses it. Failed biometric (`openVaultKey` / `unlockWithKey` null) and refused publish (`published == false`) do not reset — correct: no successful session publish. Passphrase already resets before publish on match/create, so refuse after match still clears the ritual.

**Refused branch not resetting:** Correct. Ritual must not be cleared by a non-publish. Passphrase match/create still clears via the pre-`publishSession` `resetCandidate()`.

**F1:** **CLOSED** for the stated exploit. Residual under soft exceptions: **R1** below.

---

### 2. THREAD-SAFETY

`@Synchronized` is on:

- `backoffDelayMs`, `recordFailure`, `recordSuccess`
- `decideCreate`, `resetCandidate`

All read/write of `failedAttempts`, `candidateHash`, `candidateCount` is only inside those methods (fields are private). Same monitor: instance `this`. No remaining unsynchronized access found in production or tests (tests use only the public synchronized API).

**Lock order / deadlock:**

- `decideCreate` holds the router monitor only for SHA-256 + compare + field updates, then returns. Store / Argon2id runs **after** the lock is released in `attemptPassphrase` (`decideCreate` then `attemptUnlockOrAdd`).
- `publishSession` holds `UnlockController`’s lock inside `unlock()`, **then** after that returns calls `resetCandidate()` (router). Locks are sequential, not nested.
- `onStop` → `resetRitual` → `resetCandidate` (router only); `lock()` is scheduled asynchronously afterward.

No path holds the router monitor across Argon2id, `unlockController.unlock`, or other long work. No Lock-order cycle Router ↔ UnlockController.

**Verdict:** Codex#1/F3 **CLOSED**. Minor residual: monitor held across unbounded-length SHA-256 (**R3**).

---

### 3. CANCELLATION

```412:417:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
            } catch (c: CancellationException) {
                unlockRouter.resetCandidate()
                throw c
            }
```

- Reset runs **before** rethrow; CE is not swallowed.  
- Outer `finally { wipe(genesis) }` still runs on this path.  
- `MainActivity` `onFailure` rethrows CE without `recordFailure`.

**Codex#2:** **CLOSED**.

---

### 4. ALWAYS-COMPARE

```88:106:apps/android/app/src/main/java/com/zitrone/app/VaultUnlockRouter.kt
    fun decideCreate(passphrase: String): Boolean {
        val hash = sha256(passphrase)
        val pending = candidateHash
        val same = MessageDigest.isEqual(hash, pending ?: NO_CANDIDATE)
        if (pending != null && same) {
            ...
        } else {
            candidateHash?.fill(0)
            candidateHash = hash
            candidateCount = 1
        }
        return candidateCount >= CREATE_THRESHOLD
    }
```

- `NO_CANDIDATE = ByteArray(32)` — equal length to SHA-256 (32).  
- Null `pending` → compare vs zeros; `pending != null && same` is false → else branch (new candidate). Real SHA-256 equals all-zero only with negligible probability; even then null-pending still takes else.  
- `NO_CANDIDATE` is never assigned into `candidateHash`, never `fill`’d; only used as compare RHS. Shared constant is not mutated by this code.

**Codex#3:** **CLOSED** for always running `isEqual`. Residual data-dependent branches after compare: **R2**.

---

### 5. OVERFLOW CAP

`if (candidateCount < CREATE_THRESHOLD) candidateCount++` with `CREATE_THRESHOLD = 3`:

| Event | Count after | `create` |
|--------|-------------|----------|
| 1st identical | 1 | false |
| 2nd | 2 | false |
| 3rd | 3 | true |
| 4th+ identical | stays 3 | true |
| Different string | 1 | false |

Capping cannot skip the 2→3 transition. **Grok F5:** **CLOSED**.

---

### 6. NO NEW DEFECTS (fix-induced)

| Concern | Result |
|---------|--------|
| Use-after-wipe / race on `hash` vs `resetCandidate` | Under one monitor; either wipe local `hash` or install it as `candidateHash`. No concurrent wipe of the live candidate without the lock. JVM `fill(0)` is not a double-free. |
| Required `resetRitual` | Only production construction: `AppContainer` wires `resetRitual = { unlockRouter.resetCandidate() }`. Test supplies it. No silent no-op. |
| Ritual reset on biometric / onboarding publish | Intended; does not break create/unlock (session is live; ritual is lock-screen-only). |
| `recordFailure` double-count | In-router expected failures already `recordFailure` and return outcomes → `onSuccess`. `onFailure` only for escaped throws (e.g. build throw after match). No double-count with `Rejected`/`Retry`. |
| `onBurn` | Still only UI; `attemptPassphrase` already `resetCandidate()` on `Burn`. No publish (correct). |

---

## New / residual findings (this delta)

### R1 — Low — F1 reset not exception-safe on biometric/onboarding-only path

- **Where:** `AppContainer.publishSession` (`ZitroneApp.kt` ~584–592); interaction with `UnlockController.unlock` (`UnlockController.kt` ~78–103).  
- **Mechanism:** `resetCandidate()` runs only after `unlock()` returns and after `setOnboardingDone(true)`. If `afterPublish()` throws **after** `publish(session)` (session already live, `published` already true inside `prepared`), or if `setOnboardingDone` throws before `resetCandidate()`, the biometric path never pre-resets (unlike passphrase). Mid-ritual candidate can survive a successful session publish.  
- **Failure scenario:** Streak 2 at lock → biometric publish succeeds → soft exception in `onSessionPublished` / onboarding flag write → process stays up, session live, candidate uncleared → non-`onStop` re-lock (forced logout) → one more identical `P` → create. Process death still clears RAM (helps); this is non-fatal exception only.  
- **Does not reopen happy-path F1** (return `true` from `publishSession` implies reset completed).

### R2 — Info — Post-compare control flow still data-dependent

- **Where:** `VaultUnlockRouter.decideCreate` ~95–104.  
- **Mechanism:** `isEqual` always runs; branch still depends on `pending != null` / `same` (assign vs increment, different wipe patterns). Not the original short-circuit defect; residual micro-timing only, dwarfed by Argon2id.

### R3 — Info — Router monitor held across unbounded SHA-256

- **Where:** `decideCreate` `@Synchronized` + `sha256(passphrase)`; UI `LockScreen` has no max length.  
- **Mechanism:** Introduced by synchronizing the gate. A huge passphrase holds `this` during hash; main-thread `resetCandidate` / `backoffDelayMs` / `recordFailure` can block. Availability/ANR under adversarial input, not a vault-break. Pre-fix had races instead.

### R4 — Info — Test gap for the actual F1 fix surface

- **Where:** New test only covers `VaultLockManager.onStop` → `resetRitual` (`AutoLockDecisionTest.kt` ~31–47).  
- **Mechanism:** No host test that biometric/`publishSession(published=true)` clears a mid-ritual candidate, nor CE reset in `attemptPassphrase`. Does not reintroduce a bug; weakens regression detection.

---

## Other notes (not regressions of claimed fixes)

- **Forced logout / `lockIf` still does not `resetCandidate`.** Guard depends on unlock-time / `onStop` / death. Acceptable once F1 happy path resets on biometric publish.  
- **`failedAttempts` still uncapped** (pre-existing; not in this fix set).  
- **Passphrase path double-`resetCandidate`** (pre-publish + publish): harmless.

---

## Overall verdict

**All seven prior findings are CLOSED on the intended paths against current source; residual risk is Low (R1 exception-ordering on biometric publish reset) plus Info-level timing/monitor/test gaps — no High reopen of the triple-entry gate in the Grok F1 happy-path exploit.**
