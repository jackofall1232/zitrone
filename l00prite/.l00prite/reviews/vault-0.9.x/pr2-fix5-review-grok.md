I'll review the delta adversarially against the round-5 rotation re-entry race — source only, no fixes. Reading protocol context and the full `81def41..30a6c33` change set.Reading the full critical functions and call sites next.# Adversarial security review — `81def41..30a6c33` (PR-2 triple-entry, round 5)

**Scope:** process-scoped `unlockInFlight` single-flight in `AppContainer.attemptPassphrase` only (1 file, +35).  
**Method:** full functions (not hunk-only); exploit re-walk against source; no fixes proposed.

---

## Round-5 finding status: **CLOSED**

**Claimed defect:** composition-local `unlocking` resets on Activity recreation; cancelled attempt B still holds uninterruptible store work after `decideCreate` advanced the process-scoped streak; recreated screen C could `decideCreate` and latch `create=true` before B’s outer CE `resetCandidate`.

**Closure proof (exploit re-run against source):**

| Step | State | Source |
|------|--------|--------|
| P complete (A) | `decideCreate` → count=1; store Rejected keeps streak; outer `finally` → `endUnlock()` | `ZitroneApp.kt:447–501, 513–516` |
| P again (B) | `tryBeginUnlock()` CAS true; `decideCreate` → count=2; `attemptUnlockOrAdd` under `imageLock` (uninterruptible) | `:434, :447, :451` + `VaultImageStore.kt:656–657` |
| Rotate | Composition `rememberCoroutineScope` cancels B; composition `unlocking` reseeds `false` (`MainActivity.kt:613, 777–781`). B still holds `unlockInFlight` until its outer `finally`. | |
| C enters P | `tryBeginUnlock()` → `compareAndSet(false,true)` fails → **`return Rejected` at line 434** — **no `decideCreate`**, streak unchanged | `:434` before `:447` |
| B settles | Store finishes; boundary/inner CE → outer `catch` `resetCandidate()` (count→0) **then** `finally` `endUnlock()` | `:508–516` |
| D later | Flight free; `decideCreate` sees settled count **0** → fresh count=1 | `:447` + `VaultUnlockRouter.resetCandidate:120–124` |

**No concurrent streak advance:** the only production caller of `decideCreate` is inside the claimed region (`:447`). CAS admits one holder; busy path never reaches `:447`. `imageLock` still serializes the store only; serialization of the streak is now the process flag, as intended.

---

## Binding checks

### 1. CLOSURE — **CLOSED**
See table above. Concurrent C cannot call `decideCreate` while B holds the flight through store + streak settle + `endUnlock`.

### 2. ORDERING — **OK**
```text
return try {
    withContext { decideCreate → store → when (reset|keep) }
} catch (CancellationException) {
    resetCandidate()   // first on cancel path
    throw c
} finally {
    endUnlock()        // always after try/catch body
}
```
Kotlin/JVM: `finally` runs after the `catch` body and after normal `when` settlement, before leave. No release before streak commit (Rejected keep) or rollback (match/create/burn/CE/exception resets).

### 3. NO LOCKOUT / NO LEAK — **OK**
| Path | Flight release |
|------|----------------|
| Happy / Rejected keep / Created / Burn / mapped exceptions | outer `finally` `:513–516` |
| CE (inner rethrow or boundary) | `catch` then `finally` |
| Busy refuse | never claims (`:434` before `try`) |
| Process death | RAM `AtomicBoolean` gone |

Busy path: no genesis alloc/wipe, no `decideCreate`, no `recordFailure` / backoff advance inside `attemptPassphrase`.

### 4. NO NEW DEFECT (security-material)

| Question | Result |
|----------|--------|
| Busy `Rejected` vs wrong-password `Rejected` | **UI same** (`UNIFORM_FAILURE`, `MainActivity.kt:806–811`). **Timing differs** (µs vs full Argon2 sweep) — concurrency/liveness oracle only; not a passphrase-correctness or vault-existence oracle beyond “an attempt still held the flight,” which the operator already induced. **Info residual**, not High/Medium gate bypass. |
| Legitimate triple-entry in one Activity | Composition `unlocking` still serializes submits; `endUnlock` runs before return, so next submit sees free flight. Process flag does **not** double-block sequential entries. |
| Deadlock / re-enter flight | `unlockInFlight` is non-blocking refuse, not a mutex waiters block on. Holders take router monitor + `imageLock` + `publishSession`/`UnlockController`; none call `tryBeginUnlock`/`attemptPassphrase`. `VaultLockManager.onStop` → `resetCandidate` only (no flight). No lock-order cycle with the flag. |
| `AtomicBoolean` vs `vaultCreating` `MutableStateFlow` | Unlock spinner remains composition-local; reject-based busy path needs no observation for correctness. Asymmetry is intentional; missing post-rotation “still unlocking” UI is UX/Info, not a streak bypass. |

### 5. HOLISTIC (round-4 outer CE reset + round-5 single-flight)

**Can a second vault still be created with fewer than 3 consecutive identical uninterrupted lock-screen entries?**

**No**, against production call graph:

| Vector | Why not &lt;3 |
|--------|----------------|
| Rotation re-entry race | Closed by `unlockInFlight` before `decideCreate` |
| Boundary CE keeping streak | Closed by outer `catch` `resetCandidate` (`:508–512`) |
| `imageLock`-only serialization | Superseded for streak by process flight |
| Biometric interleave | No `decideCreate`; `publishSession` may `resetCandidate` (`:642`) — interrupts, does not advance create gate |
| Background / true ON_STOP | Unconditional `resetRitual` (`VaultLockManager.kt:107–111`) |
| Process death | RAM streak + flight cleared |
| Exceptions in store/publish mapping | Reset streak (`:456–475`, match/create/burn arms) |
| Onboarding `createVaultAndPublish` | Separate first-image path + `vaultCreating`; not lock-screen triple-entry B |
| Concurrent `decideCreate` | Single production site, gated by CAS |

**Not a &lt;3 bypass (by design / residual):** idle streak after *completed* entries can survive a config-change that ProcessLifecycleOwner debounces (no ON_STOP); a later third identical entry still creates — that is still **3** completed entries, not the cancelled-overlap race.

---

## Findings (this delta)

### Critical / High / Medium
**None.**

### Info (non-blocking residuals)

**I1 — Busy-reject timing side channel**  
- **SEVERITY:** Info  
- **FILE+FUNCTION+line:** `ZitroneApp.kt` / `AppContainer.attemptPassphrase` / **434**  
- **MECHANISM:** Concurrent refuse returns `PassphraseOutcome.Rejected` without Argon2; wrong-password `Rejected` always pays full `attemptUnlockOrAdd` cost.  
- **SCENARIO:** Force rotation mid-attempt B; re-submit P immediately on recreated lock screen → uniform failure UI in ~µs (+ any UI backoff delay), distinguishable from a normal reject. Does not advance streak or latch create.

**I2 — Post-rotation no process-scoped unlock busy UI**  
- **SEVERITY:** Info (UX / observability; security of gate OK)  
- **FILE+FUNCTION+line:** `ZitroneApp.kt:207–212` (flag not collected) vs `MainActivity.kt:613` (`unlocking` reseeds false)  
- **MECHANISM:** Unlike `vaultCreating` `MutableStateFlow`, in-flight unlock after recreation shows idle lock screen while B still holds the flight; submits busy-reject until B’s `finally`.  
- **SCENARIO:** Rotate during Argon2; user re-types and taps Unlock → instant uniform failure until B ends; then normal ceremony.

---

## Round-5 finding (explicit)

| Finding | Status |
|---------|--------|
| ROTATION RE-ENTRY RACE (High, both reviewers, round 5) | **CLOSED** (verified against source) |

---

## Overall verdict

**CLEAN** — round-5 rotation re-entry race closed; no new Critical/High/Medium; only Info timing/UX residuals.
