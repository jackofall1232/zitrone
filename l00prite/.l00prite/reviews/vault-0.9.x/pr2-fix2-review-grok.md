I'll review the delta as an independent adversarial security reviewer: full functions around the claimed R1/R3 fixes, then verify every publish path and lock behavior against the source.Reading the full functions involved in publish, unlock, and ritual reset paths.Checking one more edge case: whether `published` can be true if `prepared` succeeds but `publish`/`afterPublish` never run, and lock identity for `@Synchronized`.# Adversarial Security Review — `7a7cb8d..a2e564f`

**Delta:** `VaultUnlockRouter.decideCreate` monitor scope; `AppContainer.publishSession` ritual reset in `finally`.  
**Scope verified against full functions:** `publishSession`, `attemptPassphrase`, `unlockWithBiometric`, `createVaultAndPublish`, `UnlockController.unlock`, `decideCreate` / `resetCandidate` / backoff / `NO_CANDIDATE`.

---

## Claimed fixes

### R1 — CLOSED

**Verified source:** `ZitroneApp.kt` `publishSession` L573–596; `UnlockController.unlock` L78–103.

| Path | Evidence |
|------|----------|
| Happy path | `prepared` sets `published = true` (L578 `.also`); `unlock` returns; `finally` → `resetCandidate()` (L593); then `setOnboardingDone` (L595). |
| `afterPublish` throws after session live | Order in `unlock`: `prepared` → `current = session` → `publish(session)` → `afterPublish()` (L95–102). `published` is set inside `prepared` **before** `afterPublish`. Exception leaves `unlock`; `finally` still runs; `if (published)` → `resetCandidate()`. |
| `setOnboardingDone` throws | Now **after** try/finally. On accepted unlock, `finally` already reset; throw cannot leave a live candidate. |
| REFUSED (`published == false`) | Early `return onRefused()` when `terminalWipe` / `current != null` — `prepared` never runs; `published` stays false; `finally` does **not** reset. `onRefused` still wipes `vaultKey` / `payloadPlaintext` (L580–583). |
| Build throw in `prepared` | `.also` does not run; `published == false`; `onRefused` + rethrow; no ritual reset (correct: no live session from this publish). |
| Exception propagation | `finally` does not catch; original throwable propagates after `finally`. |
| `setOnboardingDone` after reset | Ordering change only: onboarding flag after ritual clear. Independent of `candidateHash`; not a ritual hole. |
| Double-reset (passphrase) | `attemptPassphrase` L441/449 `resetCandidate()` then `publishSession` finally resets again. `resetCandidate` is idempotent (null/zero). Harmless. |
| Biometric / onboarding | `unlockWithBiometric` / `createVaultAndPublish` only clear ritual via `publishSession` finally on success — uniform coverage for the prior biometric gap. |

**`published` vs throw points:** set in `prepared` before `publish`/`afterPublish`. Any path that reaches a live session after a successful build has `published == true` when `finally` runs.

---

### R3 — CLOSED (still thread-safe)

**Verified source:** `VaultUnlockRouter.kt` L37–50, L87–124, L168–170.

| Check | Evidence |
|-------|----------|
| Hash outside lock | `val hash = sha256(passphrase)` (L91) before `synchronized(this)` (L92). |
| Compare+update under lock | L93–108 only under `synchronized(this)`. |
| Same monitor as `@Synchronized` | Kotlin `@Synchronized` on instance methods uses `this`. `resetCandidate` / `recordFailure` / `recordSuccess` / `backoffDelayMs` share that monitor with `decideCreate`’s block. |
| Shared fields | `candidateHash` / `candidateCount` only mutated under that monitor; `failedAttempts` only via `@Synchronized` methods. |
| Race from moved hash | `hash` is thread-confined; shared state touched only inside the lock. Concurrent `resetCandidate` between hash and lock is correct (session publish / background interrupt must win). |
| Logic unchanged | Always `isEqual` vs `pending ?: NO_CANDIDATE`; same → cap-increment + wipe local hash; else wipe old, install hash, `count = 1`; return `count >= CREATE_THRESHOLD` (3). `NO_CANDIDATE` remains fixed 32-byte zero digest, never written. |

---

## New defects from this delta

**None found** that open a new security hole on the R1/R3 surface.

Checked and clean:

- **Refuse wipe:** `onRefused` still invoked inside `unlock` on refuse/build-fail; try/finally does not skip it.
- **Key/VaultOpen stranding:** Accepted build: `VaultSession` copies then wipes caller arrays. Refuse: `onRefused` wipe. Build throw: `onRefused` + construction guard (pre-existing). No new leak path from the `finally` move.
- **Throw after publish before finally:** `finally` always runs; only action is idempotent `resetCandidate` — does not strand keys.
- **Lock order / deadlock:** `resetCandidate` runs **after** `unlock` releases `UnlockController.lock` (finally is outside `synchronized(lock)`). Router monitor ≠ unlock monitor; `afterPublish` (`onSessionPublished`) does not take the router monitor.
- **Behavioural regression (passphrase / biometric / onboarding):** Success/refuse outcomes and ritual keep-on-`Rejected` unchanged. Passphrase still pre-resets on match/create; publish finally is defense-in-depth.
- **D2c/D3 surface:** No edits to `VaultSession` ownership, lock teardown, terminal wipe, or `VaultLockManager` reset-on-stop.

**Non-findings (not defects):**

- Comment at L589 mentions “settings write below” as a post-publish throw covered by `finally`; `setOnboardingDone` is outside try/finally. Safety still holds because reset runs before that call on the success path. Documentation imprecision only.
- Pre-existing: if `afterPublish` / `setOnboardingDone` throws, callers may see failure while a session is already live (and may skip `recordSuccess`). Not introduced by this delta; ritual side is improved.

---

## Verdict

**R1 CLOSED. R3 CLOSED. No new defects in this delta.**
