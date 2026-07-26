You are an INDEPENDENT SECURITY REVIEWER acting as a TIE-BREAKER for Zitrone, a zero-knowledge
plausible-deniability messenger. Two blind reviewers examined the same delta, identified THE SAME
defect, and classified it OPPOSITELY. You are adjudicating that one question. You are NOT told which
reviewer said what.

You have a private read-only checkout at /root/zitrone. Verify against source.

## CONTEXT
A duress "Pucker Burn" wipe. The guarantee: **post-burn state is indistinguishable from a fresh
install.** A byte-for-byte instrumented gate (`BurnByteForByteGateTest`) is the load-bearing
mechanical proof of that guarantee and runs on a CI emulator.

This is the FINAL round of a review loop that was already extended once. There is no further round:
whatever you conclude, the outcome goes to a human for a merge-or-rescope decision.

## THE AGREED FACTS (verify them, then adjudicate)
The previous round added a live-session quiesce to production. `MainActivity.onBurn` now does:

    beginTerminalWipe() -> unlockController.lock() -> burnVault(...)

The gate does:

    beginTerminalWipe() -> burnVault(terminate = {})

The gate provisions a REAL published session (`createVaultAndPublish`) and then burns WITHOUT the
`lock()` call that production added specifically to quiesce that session. Therefore **deleting
`unlockController.lock()` from production would leave the gate green.**

Both reviewers agree production behaviour is CORRECT and converged. The disagreement is only about
what this gap means.

## THE TWO POSITIONS
- **DEFERRABLE:** this is gate-fidelity lag, not a production wipe hole. The production path is
  correct; a green gate only ever certifies the scenario it runs, which is already documented. No
  functional defect exists, so nothing blocks merge.
- **BLOCKING:** the load-bearing gate does not discriminate removal of the very repair it exists to
  validate. The round's own exit test was "if this round still leaves a false pin or a
  NON-DISCRIMINATING GATE CONTROL, the process is not converging." A gate that cannot fail when the
  repair is reverted is a non-discriminating control by definition.

## QUESTIONS
1. **Which classification is correct, and against WHICH standard?** Note the project's stated
   blocking boundary is "anything that breaks post-burn ≡ fresh install BLOCKS; robustness residuals
   may be deferred and tracked." Distinguish clearly between (a) the functional boundary and (b) the
   round's exit test, and say whether they give different answers here. If they do, say which should
   govern a merge decision and why.
2. **Is this a NEW instance or a FAILED repair?** The previous round fixed several
   "repair-not-mirrored-into-its-verifier" defects. Is this the same class recurring — which would
   argue the process is not converging — or an ordinary new gap of the kind any change produces?
3. **How large is the fix, honestly?** Options on the table: call `lock()` in every gate burn path
   and assert the session is null before `burnVault`; or extract the terminal burn orchestration into
   one callable used by both `MainActivity` and the gate. Would either introduce risk to the
   production path, or are they contained test-side changes? Is there a reason the gate deliberately
   should NOT lock (note it passes `terminate = {}` so the process survives for assertions)?
4. **Given this is terminal:** is the honest recommendation (a) fix it and merge, (b) merge and track
   it, or (c) the process is not converging and the unit should be re-scoped? Answer plainly.

## OUTPUT
Per question: verdict, source you actually read (file:line), reasoning. Then one line: BLOCKING or
DEFERRABLE. Do not invent additional findings — this is a targeted adjudication. Saying "both
reviewers are partly right" is allowed only if you then say which answer governs the merge decision.
