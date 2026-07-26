You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes.

## Context
Zitrone: production Signal-Protocol E2E messenger with a plausible-deniability second vault. Adversary: physical device + forensics + many forced unlocks; assume CRASH/exception/rotation at ANY instruction. This is the SECOND fix round for the 0.9.2 PR-2 triple-entry router. **Guilty-until-proven — a fix can introduce a new defect.**

## Delta to review
`7a7cb8d..a2e564f` on branch `feat/0.9.2-vault-pr2-router` (/root/zitrone). `git diff 7a7cb8d..a2e564f`. Read the FULL functions, not just hunks:
- `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` — `publishSession` (now resets the ritual in a `finally`-if-published), and its callers `attemptPassphrase`, `unlockWithBiometric`, `createVaultAndPublish`. Also `UnlockController.unlock` (`UnlockController.kt`) — where the session is published (before `afterPublish`).
- `apps/android/app/src/main/java/com/zitrone/app/VaultUnlockRouter.kt` — `decideCreate` (SHA-256 now OUTSIDE the `synchronized(this)` block), `resetCandidate`, backoff methods (`@Synchronized`), `NO_CANDIDATE`.

## The two findings this delta claims to close
- **R1** (prior round, confirmed by both reviewers): `publishSession` reset the ritual only after `unlock()` returned, so a soft exception in `afterPublish`/`setOnboardingDone` (after the session went live) left a mid-ritual candidate alive over a published session. FIX: `resetCandidate()` moved into a `finally { if (published) ... }`.
- **R3** (prior round, Info): the router monitor was held across `sha256(passphrase)`. FIX: hash computed outside `synchronized(this)`; only the compare+update take the lock.

## Verify specifically (binding)
1. **R1 CLOSED?** Prove `resetCandidate()` now runs whenever a session is published, on EVERY path: happy path, and when `afterPublish` throws inside `unlock()` (session already live, `published==true`), and when `setOnboardingDone` throws. Confirm `published` is set (inside `prepared`) before any throw point, so the `finally` sees it true. Confirm a REFUSED build (`published==false`) does NOT reset. Confirm the `finally` does not swallow or alter the propagating exception, and that moving `setOnboardingDone` to after the try/finally introduced no ordering bug (e.g. onboarding flag now set after the reset — harmless?). Confirm no DOUBLE-reset hazard (passphrase path resets in `attemptPassphrase` AND now in `publishSession` — harmless idempotent?).
2. **R3 CLOSED + still thread-safe?** Confirm `decideCreate` computes `hash` outside the lock, then `synchronized(this) { compare + update }`, and that `this` is the SAME monitor as the `@Synchronized` `resetCandidate`/`backoff` methods (so all `candidateHash`/`candidateCount`/`failedAttempts` access is still mutually exclusive). Confirm the moved hash introduces no race (the `hash` local is thread-confined; only the synchronized block touches shared fields). Confirm the logic is unchanged (always-compare, cap, reset-to-1, create on 3rd).
3. **NO NEW DEFECTS** from this delta: any exception-handling change in `publishSession` that leaks a `VaultOpen` or key (does the `onRefused` wipe still fire on a refused build? does a throw between publish and the finally strand anything?); any behavioural change to the biometric/onboarding/passphrase flows; any lock-order/deadlock from the `finally` reset; any regression to the D2c/D3 hardened surface.

## Output
Structured findings (SEVERITY, FILE+FUNCTION+line, MECHANISM, concrete SCENARIO). State CLOSED/NOT-CLOSED for R1 and R3 explicitly, verified against source. One-line overall verdict. Report ONLY.
