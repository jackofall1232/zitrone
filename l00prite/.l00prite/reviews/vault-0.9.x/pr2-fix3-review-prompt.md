You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — no fixes.

## Context
Zitrone: production Signal-Protocol E2E messenger, plausible-deniability second vault. Adversary: physical device + forensics + many forced unlocks; assume crash/exception/rotation at any instruction. This is the CONFIRMING pass for the 0.9.2 PR-2 triple-entry router after three fix rounds. Guilty-until-proven.

## Delta to review
`a2e564f..021b19f` on branch `feat/0.9.2-vault-pr2-router` (/root/zitrone). `git diff a2e564f..021b19f`. This delta REVERTS one prior change: `VaultUnlockRouter.decideCreate` is now fully `@Synchronized` again (SHA-256 computed INSIDE the monitor, one atomic operation), reverting a round-2 "hash outside the lock" experiment. Read the full `VaultUnlockRouter.kt` and confirm the surrounding fixes are intact.

## Verify (binding)
1. `decideCreate` is now `@Synchronized` (whole method on `this`), so it is ONE atomic operation w.r.t. `resetCandidate` and the backoff methods (all on the same `this` monitor). Confirm there is no remaining reset-vs-hash interleaving concern and the gate logic is unchanged: always-run `MessageDigest.isEqual(hash, pending ?: NO_CANDIDATE)`; create on the 3rd consecutive identical; differing string → count 1; cap at threshold; `resetCandidate` clears to null/0.
2. Confirm the OTHER already-verified fixes remain intact at this HEAD (they are NOT in this delta but confirm they weren't disturbed): `attemptPassphrase` resets on `CancellationException`; `publishSession` resets the ritual in a `finally { if (published) ... }` (exception-safe, covers biometric/onboarding); `VaultLockManager.onStop` resets unconditionally; `resetRitual` is a required param; `recordFailure` in the `onUnlockPassphrase` `onFailure`.
3. HOLISTIC VERDICT on the current triple-entry gate: can a vault be created with fewer than 3 consecutive identical uninterrupted lock-screen entries, via ANY path (biometric interleave, cancellation, background, concurrency, exception)? Prove yes/no against source. Any remaining Critical/High/Medium?
4. Any NEW defect introduced by the revert itself.

## Output
Structured findings (SEVERITY, FILE+FUNCTION+line, MECHANISM, SCENARIO). State CLEAN or the specific blocking finding. One-line overall verdict. Report ONLY.
