You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes, do NOT edit any file.

## Product & threat model
Zitrone: a production Signal-Protocol E2E messenger with a plausible-deniability second vault + a "Pucker Burn" duress credential. Adversary has PHYSICAL DEVICE ACCESS + FORENSIC CAPABILITY and may observe/force many unlock attempts; assume CRASH / PROCESS-DEATH + Activity-recreation (rotation) at ANY instruction. This is a FIX ROUND for the 0.9.2 PR-2 router (triple-entry creation gate). **Fixes are NOT lower-risk than original code — treat the delta guilty-until-proven.**

## What to review
The DELTA `7348c53..7a7cb8d` on branch `feat/0.9.2-vault-pr2-router` in this repo (/root/zitrone). Start with `git diff 7348c53..7a7cb8d`. Verify against ACTUAL SOURCE (read the full functions, not just the diff hunks):
- `apps/android/app/src/main/java/com/zitrone/app/VaultUnlockRouter.kt` — `decideCreate`, `resetCandidate`, `sha256`, `NO_CANDIDATE`, `CREATE_THRESHOLD`, the `@Synchronized` annotations, `candidateHash`/`candidateCount`, the backoff methods.
- `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` — `attemptPassphrase` (the CancellationException reset), `publishSession` (the new `resetCandidate` on publish), the `VaultLockManager` construction, `unlockWithBiometric`, `createVaultAndPublish`.
- `apps/android/app/src/main/java/com/zitrone/app/VaultLockManager.kt` — `resetRitual` now required; `onStop`.
- `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt` — `onUnlockPassphrase` `onFailure` (restored `recordFailure`), `onBurn`, the biometric unlock path (`startVaultBiometricUnlock` / `onUnlockBiometric`).
- Test: `apps/android/app/src/test/java/com/zitrone/app/AutoLockDecisionTest.kt` (the new onStop-reset test).

## The prior-round findings these fixes claim to close (verify EACH is closed, and NONE reopened)
- (High, Grok F1) The uninterrupted-sequence guard was incomplete: a BIOMETRIC unlock never reset the ritual, so a mid-ritual candidate could survive a full unlock + a non-`onStop` re-lock and complete on ONE lock-screen entry. Fix: `publishSession` now `resetCandidate()`s on ANY publish.
- (High/Med, Codex#1/Grok F3) `candidateHash`/`candidateCount` were unsynchronized across the Default worker and the main thread. Fix: `@Synchronized`.
- (Med, Codex#2) A cancelled attempt kept the streak. Fix: reset on CancellationException.
- (Low, Codex#3) The compare short-circuited on the first attempt. Fix: always run `MessageDigest.isEqual` vs a fixed all-zero digest.
- (Low, Grok F4) The unexpected-throw path stopped bumping backoff. Fix: restored `recordFailure`.
- (Low, Grok F5) `candidateCount` could overflow. Fix: cap the increment at the threshold.
- (Info, both) `resetRitual` default-no-op footgun. Fix: made it required.

## Verify specifically (binding)

1. F1 CLOSURE — Prove that after this change NO unlock path leaves a mid-ritual candidate alive: `publishSession` `resetCandidate()`s on `published`, and it is the SINGLE point through which the passphrase router, the BIOMETRIC path (`unlockWithBiometric`), and onboarding (`createVaultAndPublish`) all publish. Re-run Grok's exploit (enter P twice → biometric unlock → foreground re-lock → one P) against the fixed code and confirm it no longer creates. Is there ANY publish or unlock path that bypasses `publishSession`? Does a `resetCandidate` on the reset happening on the not-published (refused) branch matter (it does not reset there — is that correct)?

2. THREAD-SAFETY — Confirm `@Synchronized` on `decideCreate`/`resetCandidate`/`backoffDelayMs`/`recordFailure`/`recordSuccess` makes every read/write of `candidateHash`/`candidateCount`/`failedAttempts` mutually exclusive on the same monitor (`this`). Is there any REMAINING unsynchronized access to those fields anywhere? Is there a LOCK-ORDERING / re-entrancy / deadlock risk now that `publishSession` (called from the `attemptPassphrase` worker, and from the biometric coroutine) calls the synchronized `resetCandidate` — does any code hold the router monitor across a long/blocking call (e.g. the Argon2id store call, or `unlockController.unlock`)? Confirm `decideCreate` does NOT hold the monitor across the store call (it returns the boolean first).

3. CANCELLATION — `attemptPassphrase` resets the candidate on `CancellationException` BEFORE rethrow, and still does not swallow it. `genesis` is still wiped in `finally` on this path. Confirm.

4. ALWAYS-COMPARE — `decideCreate` now runs `MessageDigest.isEqual(hash, pending ?: NO_CANDIDATE)` unconditionally. Confirm the logic is still correct (a null `pending` still yields the else/new-candidate branch — `NO_CANDIDATE` is all-zero so a real SHA-256 never equals it), the compare is constant-time and equal-length (both 32 bytes), and `NO_CANDIDATE` is never mutated (it is a shared constant — confirm nothing wipes it).

5. OVERFLOW CAP — `if (candidateCount < CREATE_THRESHOLD) candidateCount++`. Confirm the state machine is unchanged for the real cases (create on the 3rd identical; a 4th+ identical still returns create=true; a differing string still resets to 1) and that capping cannot cause a MISSED create.

6. NO NEW DEFECTS — from any fix: a new use-after-wipe (does `resetCandidate` wiping `candidateHash` race/￼double-free with the fresh `hash` in `decideCreate` under the lock?), a broken construction from the now-required `resetRitual`, a behavioural regression in the biometric or onboarding flow from the `publishSession` reset (does resetting the ritual on onboarding-create or biometric-unlock harm anything?), and whether `recordFailure` in `onFailure` now double-counts with anything.

## Output format
Structured findings; each with SEVERITY, exact FILE+FUNCTION(+line), DEFECT MECHANISM, concrete FAILURE SCENARIO. State explicitly for EACH prior finding whether it is CLOSED (verified against source). End with a one-line overall verdict. Report ONLY — no fixes.
