You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 3 of a paired-blind review of the residue-sweep delta. You are blind to the other reviewer.

PRIMARY SCOPE — the round-2 FIX DELTA:
  git -C /root/zitrone show 5e02b2e
THE DELTAS IT BUILDS ON (all three are what would merge):
  git -C /root/zitrone show c144216   # the sweep
  git -C /root/zitrone show 98c0319   # round-1 fixes
CUMULATIVE UNIT:
  git -C /root/zitrone diff main...HEAD
  # (commits under l00prite/ are loop bookkeeping — NO code, ignore them)

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust commit messages, comments, or this prompt. This
unit's comments have been WRONG repeatedly — including an invariant table that was internally
coherent but wrong about which component owned a state, and a kdoc that asserted "Splash blocks on
bootReconciled" when it did not. Derive every safety property from the code yourself.

## Five STANDING instructions — apply to everything below
1. **PROVE ANY TABLE COMPLETE, NOT SELF-CONSISTENT.** Hunt the MISSING ROW.
2. **A GATE CAN BE WRONG BY BEING TOO NARROW.** Prove BOTH directions: what it wrongly admits AND
   what it wrongly STRANDS. "Another component owns this" is a claim to verify against that
   component's real preconditions.
3. **HUNT THIS PATTERN — it has produced a HIGH FOUR times in this unit, each inside the fix for the
   previous one:** *an authoritative result exists, and a consumer uses something weaker.* It has two
   forms. **Data-flow:** the verdict is discarded and recomputed from a cheaper signal.
   **Lifecycle:** the verdict is carried correctly, but a consumer runs BEFORE it is published and
   reads the field's default. For every safety verdict here, ask BOTH: who consumes this and do they
   use THIS EXACT VALUE — and is every consumer ORDERED AFTER publication, by awaiting it rather than
   by being usually-slower? A default meaning "safe" is the trap.
4. **A CLAIM AND THE WORK IT CLAIMS MUST HAVE THE SAME LIFETIME.** A once-per-process CAS whose owner
   can die before publishing strands every waiter forever.
5. **A TEST THAT A VALUE IS COMPUTED IS NOT A TEST THAT IT IS USED.** Judge coverage at the
   CONSUMPTION site, not the production site.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is UNARMED
(random filler) so the wipe is unreachable in production; this unit ships the MECHANISM only. Central
invariant: post-burn ≡ fresh install, AND a burn that did NOT fully take must never present that way.
The sweep is a DESTRUCTIVE BOOT OPERATION — it unlinks files at cold start before any authentication.

## What round 2 found and what 5e02b2e changed (VERIFY EACH FIX IS REAL, COMPLETE, AND SAFE)
- HIGH (both reviewers): Splash routed WITHOUT waiting for the boot verdict — `onFinished` read
  `residueSweepHold` at its default `false` and re-stat'd files the sweep had just unlinked, so a
  non-durable sweep could still present onboarding. Fix: Splash now only records that its animation
  ended; a separate effect keyed on `(splashFinished, bootReconciled)` decides once, from the carried
  hold.
- HIGH/MEDIUM (both): the once-per-process CAS was owned by a COMPOSITION `LaunchedEffect`, so a
  rotation could cancel it after the claim and before publication — CAS held, every later composition
  waiting forever. Fix: `AppContainer.startBootReconcile()` runs on the process-scoped `scope` with a
  `finally` publishing on EVERY exit; `sweep` starts at `SWEPT_NOT_DURABLE` so a run that dies
  releases waiters FAIL-CLOSED.
- LOW: the session collector had proven-absence but not the hold; now routes through `bootRoute`.
- INFO: a kdoc claimed `create()` "refuses to run while either marker is present" — false, it CLEARS
  them. Corrected in place; the intent-gate conclusion rests on `destroy()` writing the confirmed
  marker before any unlink, which is real.

## FOCUS FOR THIS ROUND
A. IS THE CONSUMPTION PATH NOW SEALED? Enumerate EVERY consumer of `residueSweepHold` /
   `bootReconciled` / `ResidueSweepResult` and prove each (i) uses the carried value and (ii) cannot
   run before publication. Is there a consumer neither round 2 nor this prompt mentions? Note the
   route re-derive only promotes Locked→Onboarding — can any path now reach Onboarding EARLY such
   that nothing demotes it?
B. `startBootReconcile()` — verify the `finally` publishes on every exit including cancellation at
   process death; that `sweep`'s fail-closed initial value cannot be lowered by a partial run; that
   the CAS can no longer strand; and that running on `container.scope` introduces no NEW race with
   burn or account-delete, which share that scope.
C. The Splash gate: `LaunchedEffect(splashFinished, bootDone)`. Can it fire twice, fire never, or
   decide on stale inputs? Is `route != Route.Splash` the right guard? What if the composition is
   recreated after the animation finished but before boot published?
D. Did 5e02b2e introduce ANY new defect? Include the session-collector change and the removal of the
   now-dead `bootReconcileRest`.
E. THE SWEEP GATE ITSELF, re-verified independently: it is now `image PROVEN absent AND no
   delete-confirmed` — the delete-intent clause was REMOVED in round 1. Prove that removal is safe in
   EVERY state, and prove the corrected WRITER/READER table COMPLETE. This is a destructive boot
   operation; the failure to hunt is a gate too broad OR too narrow.
F. Re-verify the CUMULATIVE unit — assume NOTHING from earlier rounds, including anything marked PASS:
   1. destroy() equivalence under keys-first unlink order.
   2. Marker clear STRICTLY after unlinks proven durable.
   3. All boot healers as ONE system: sweep, completeInterruptedBurn, reconcileOrphanedBurnMarkers.
      Overlap, contradiction, or a state no one owns?
   4. WRITER/READER invariants — durable signals AND in-flight verdicts.
   5. REACHABILITY: slot 0 unarmed; wipe wired ONLY to lock-screen dispatch.
   6. Concurrency/lifecycle end-to-end.
   7. Fail-closed: can a partial burn present as success, or leave state worse than not burning?
G. NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
   pre-existing, inherited from destroy(), deliberately out of scope. Say if you disagree.
H. Test quality. Six mutations have been run across this delta, all caught. NO tests were added for
   the round-2 fixes: both are lifecycle (coroutine ownership, effect ordering) and this project has
   no Compose/instrumentation infrastructure, so they are inspection-verified only — stated plainly
   rather than covered by a test that asserts nothing. Judge that call. **Specifically: is any part of
   the round-2 lifecycle behaviour reachable at a SEAM that could be tested on the host JVM or under
   Robolectric — or is it genuinely device-only?** Be concrete; this feeds a decision about whether
   the missing test infrastructure is worth building as its own unit.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give an explicit verdict on A, B, C, D, E, each of
F.1-F.7, G, H. State clearly whether this is READY TO MERGE. If it is correct, say so plainly and
briefly — do NOT invent findings to appear thorough. An honest clean pass is the expected outcome if
the delta holds. If you believe a previous round's accepted conclusion was WRONG, say so explicitly.
