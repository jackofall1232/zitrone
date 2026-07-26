You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 3 of a paired-blind review. You are blind to the other reviewer.

PRIMARY SCOPE — the round-2 FIX DELTA (fixes are NOT lower-risk than original code; treat as guilty
until independently proven otherwise):
  git -C /root/zitrone show 0dce2e6
CUMULATIVE UNIT as it would merge (verify the whole thing still holds):
  git -C /root/zitrone diff main...HEAD
  # 645b8a8 mechanism · 764845e round-1 fixes · 813245b self-audit · 0dce2e6 round-2 fixes

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust commit messages, comments, or this prompt.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is reserved
for it and is currently UNARMED (uniformly-random filler), so the wipe is unreachable in production —
this unit ships the MECHANISM only, deliberately, so the destructive path could be reviewed before
anything can trigger it. Physical destruction was factored out of `VaultImageStore.destroy()` into a
marker-free `obliterateLocked()`, shared by `destroy()` (which prefixes a `vault.delete-confirmed`
crash-bridge) and `obliterateForBurn()` (which must NOT write it).

D2c background (hardened over 16 rounds): `vault.delete-intent` (delete initiated, server outcome
unknown; ALSO the auth-protection guard) and `vault.delete-confirmed` (server account provably gone —
the ONLY authorization for the `Route.DeleteIncomplete` auto-destroy). Marker discipline is tristate
`Files.notExists` + required dirSync, fail-closed: `File.delete()`'s bool and `File.exists()==false`
are both untrustworthy because an I/O/stat fault is indistinguishable from absence.

## What round 2 found, and what 0dce2e6 changed (VERIFY EACH FIX IS REAL, COMPLETE, AND SAFE)
- HIGH: `beginTerminalWipe()` was set-true, not exclusive, so two burn workers co-owned the gate and
  the first to finish reopened session creation while the other was still obliterating — the straggler
  could then destroy a SUCCESSOR vault the user had just created. Fix: new
  `UnlockController.tryBeginTerminalWipe(): Boolean`; only the winner runs work, only the winner
  releases; a refused claimant returns early and touches nothing.
- MEDIUM: `clearCacheDir` fail-opened on `!exists()` (stat-failed read as absent). Fix: `Files.notExists`.
- MEDIUM: the post-obliteration cache pass short-circuited when the first pass succeeded. Fix: it now
  always runs and is authoritative.
- MEDIUM: docs overclaimed which cleanups are guaranteed. Fix: SECURITY_MODEL now states every
  non-image cleanup is best-effort and only image+DEK+temps is hard. `clearAllForWipe` returns commit().
- LOW: a vacuous test (named for a failure case, asserted success) renamed; gap stated.
- INFO: `BurnResult.plaintextCacheCleared` computed then discarded — recorded as intentional (a UI/log
  distinction between "burned cleanly" and "burned with residual" would be a duress tell).

## FOCUS FOR THIS ROUND
A. Is the exclusive-gate fix CORRECT AND COMPLETE? Specifically:
   - Can the gate now be STRANDED (held forever) on any path — throw, cancellation, process death,
     early return, or a winner that dies before its `finally`? A permanently-held gate blocks ALL future
     unlocks AND session publication, which would brick the app.
   - Does the account-delete flow (which still calls the non-exclusive `beginTerminalWipe()`) interact
     safely with a burn that uses `tryBeginTerminalWipe()`? Can one steal or release the other's claim?
   - Is the refused-claimant path correct (surfaces uniform failure, releases nothing, leaves no state)?
   - Are the 4 new tests meaningful, or do they pass vacuously?
B. Did the round-2 fixes introduce ANY new defect? Re-examine `clearCacheDir`, `burnVault`'s ordering,
   `retryPlaintextCacheClearIfNoVault`, and `SettingsRepository.clearAllForWipe`'s new return value.
C. Re-verify the CUMULATIVE unit end-to-end — do not assume earlier rounds' conclusions:
   1. destroy() EQUIVALENCE. The refactor changed destroy()'s unlink order (bin-then-dek ->
      dek-then-bin, keys-first). Rounds 1 and 2 accepted the argument that this is safe because the
      confirmed marker is durable before either unlink. EVALUATE IT YOURSELF. If unacceptable, a
      `keysFirst` param is the fallback.
   2. Marker clear STRICTLY after unlinks are proven durable; no path clears markers over live state.
   3. Boot reconciliation + `completeInterruptedBurn()` (a NO-CREDENTIAL path that deletes a PRESENT
      vault image when the DEK is proven absent). Can any state reach it that is not an interrupted
      burn? Is running it on every cold start safe?
   4. WRITER/READER invariants for every durable signal the burn touches.
   5. REACHABILITY in the shipped diff: slot 0 unarmed; wipe wired ONLY to the lock-screen dispatch.
      (`attemptUnlockOrAdd` returns the Burn outcome and is ALSO the second-vault collision path.)
   6. Concurrency/lifecycle end-to-end.
   7. Fail-closed: can a partial burn present as success, or leave state worse than not burning?
D. NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
   pre-existing code inherited verbatim from destroy() (same pattern in `retireLegacyImage`),
   deliberately out of scope because tightening it changes D2c behaviour. Say if you disagree.
E. Test quality across the unit: what failure shape is STILL untested?

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give an explicit verdict on A, B, each of C.1–C.7, D, E.
State clearly whether this is READY TO MERGE. If it is correct, say so plainly and briefly — do NOT
invent findings to appear thorough. An honest clean pass is the expected outcome if the fixes hold.
