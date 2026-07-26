I'll review the delta as an independent adversarial security reviewer: first the protocol context, then the full diff and the surrounding functions you named.Next I'll read the full `attemptPassphrase` path and the router/caller reset sites against the claimed cancellation hole.Reading the full router and the unlock caller's failure path next.Checking the Compose/coroutine scope lifetime on rotation — whether cancellation actually fires.## Round-4 claimed finding

**CLOSED** — verified against `81def41` source.

The deferred `withContext` boundary hole is closed for a **single** cancelled attempt:

| Path | Mechanism | Reset? |
|------|-----------|--------|
| **(a) CE during store** | Inner `catch (CancellationException) { throw c }` (L421–424) rethrows → `finally { wipe(genesis) }` (L473–475) → outer `catch` `resetCandidate()` + rethrow (L477–481) | Yes |
| **(b) Deferred boundary CE** | Block finishes (`Rejected` keeps streak via L467–470) → `finally` wipes genesis → `withContext` discards result and throws CE at boundary → **outer** catch sees it (inner catch already returned) | Yes |

Re-run of the single-thread exploit (enter P → enter P while Argon2 runs → rotate → enter P): cancelled attempt’s advance is undone by the outer catch before a later entry can treat it as an uninterrupted count, so count cannot reach 3 from that cancelled step alone.

---

## Binding checks

### 1. CLOSURE (claimed finding) — **CLOSED**
Outer catch wraps the entire `withContext` (L414–482). Both (a) and (b) reach it. Single-attempt cancelled entry no longer contributes to a later CREATE.

### 2. NO SWALLOWED CANCELLATION — **HOLD**
- Inner CE handler is **before** `catch (t: Throwable)` and only rethrows (L421–424, L440–444).
- Outer catch rethrows after reset (L480–481).
- `MainActivity.onUnlockPassphrase` `onFailure` rethrows CE without mapping (L815–816).
- CE is never folded into `Rejected` / uniform failure.

### 3. GENESIS WIPE — **HOLD**
- `finally { wipe(genesis) }` is inside the `withContext` block (L473–475).
- Store-call CE: rethrow → `finally` runs → outer catch.
- Boundary CE: block already completed (including wipe) before `withContext` throws.
- No CE path leaves `genesis` allocated without wipe.

### 4. NO NEW DEFECT FROM RESTRUCTURE — **HOLD** (for the restructure itself)
- All `return@withContext` labels still target the same `withContext` lambda.
- Outer `try` is a transparent pass-through for Unlocked / Created / Burn / Rejected / Legacy / ImageUnreadable / Retry.
- **No double reset with MainActivity**: CE branch only `throw e` — no `resetCandidate()` (L815–816). Sole CE reset is outer catch (+ optional `VaultLockManager.onStop`, idempotent).
- `resetCandidate` is `@Synchronized` RAM-only (`VaultUnlockRouter.kt` L119–124) — safe on Main after `withContext` resumes.
- Non-cancellation outcomes unchanged vs pre-delta logic.

### 5. HOLISTIC (create with &lt; 3 uninterrupted lock-screen entries) — **NOT HOLD**

---

## Remaining finding

### HIGH — Concurrent ritual advance across rotation (composition-local single-flight)

| | |
|--|--|
| **SEVERITY** | **High** |
| **FILE + FUNCTION + LINE** | `MainActivity.kt` `onUnlockPassphrase` L777–826 (`unlocking` is `remember` state L613; `scope = rememberCoroutineScope()` L599); `ZitroneApp.kt` `attemptPassphrase` L416–417 (`decideCreate` side effect + latched `create` boolean); contrast process-scoped create single-flight L186–191 / `onCreateVault` L897–907 |
| **MECHANISM** | `decideCreate` advances the process-scoped triple-entry candidate **before** the uninterruptible store. Cancellation only resets when the cancelled coroutine later hits the outer catch (after Argon2). Lock-screen `unlocking` is **composition-local** and resets on Activity recreation while the cancelled job still holds the elevated `candidateCount` (and a later call can latch `create=true` into a local `val` before that reset). `imageLock` serializes store I/O but **not** `decideCreate`. Onboarding create already documents this rotation class of bug and uses process-scoped single-flight; passphrase unlock does not. |
| **SCENARIO** | 1) Enter P → complete `Rejected` → `count=1`. 2) Enter P again → `decideCreate` → `count=2`, Argon2 in flight. 3) Rotate: composition disposed, job cancelled, `unlocking` re-inits `false`; cancelled job still inside Argon2. 4) Immediately enter P on the new lock screen → backoff often still short (`failedAttempts` may still be 1 until the cancelled path hits `recordFailure`) → `decideCreate` sees live streak 2 → advances to 3 → **`create=true` latched**. 5) Cancelled job finishes `Rejected` (or boundary CE) and only then `resetCandidate()` — too late for the latched flag. 6) New attempt’s store runs with `create=true` → **second vault created** after only **two** uninterrupted completed entries (1st complete, 2nd interrupted, 3rd creates). |

Not introduced by the outer-catch restructure; **still open under the post-fix source** and in scope for holistic check (concurrency + cancellation + rotation).

---

## Checks with no C/H/M residual

- Biometric / `publishSession`: published session resets candidate (`ZitroneApp.kt` L607).
- Background: `VaultLockManager.onStop` → `resetRitual` → `resetCandidate`.
- Process death: RAM candidate cleared.
- Exception paths (Legacy/Corrupt/Missing/NotDurable/Throwable): reset (and failure recording where intended).
- Inner/outer CE: not swallowed; genesis wiped.

---

## Verdict

**NOT CLEAN — High: rotation re-entry races `decideCreate` while a cancelled attempt still holds the triple-entry streak (composition-local `unlocking` / no process-scoped unlock single-flight).**
