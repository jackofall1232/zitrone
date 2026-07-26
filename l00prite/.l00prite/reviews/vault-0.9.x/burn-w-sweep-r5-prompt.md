You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 5 of a blind multi-reviewer review. Four reviewers run independently on this same
commit; you are blind to all of them. Report only what YOU derive from source.

YOU HAVE DIRECT ACCESS TO THE REPOSITORY AT /root/zitrone. Read whatever you need yourself — git,
grep, whole files. NOTHING is inlined in this brief and nothing has been trimmed for you. If a verdict
depends on source, go read it; do not caveat a verdict as unverifiable.

SCOPE — the cumulative unit as it would merge:
  git -C /root/zitrone diff main...HEAD
Most recent delta (the round-5 lens-1 fix):
  git -C /root/zitrone show 5ef5f77
Commits touching only l00prite/ are loop bookkeeping with NO code — ignore them entirely.
Key files: apps/android/app/src/main/java/com/zitrone/app/{ZitroneApp,MainActivity}.kt,
.../crypto/vault/VaultImageStore.kt, and the tests in app/src/test/java/com/zitrone/app/.

DO NOT MODIFY, CREATE OR DELETE ANY FILE. Report findings only.

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust comments, kdoc, or commit messages. This unit's
comments have been wrong repeatedly and each was caught only by re-derivation: an invariant table
internally coherent but wrong about which component owned a state; a kdoc asserting "Splash blocks on
bootReconciled" when it did not; a kdoc claiming create() "refuses to run while either marker is
present" when it CLEARS them; a test kdoc naming a mutation it provably cannot catch.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is UNARMED
(uniformly-random filler), so the wipe is unreachable in production — this unit ships the MECHANISM
only. Central invariant: post-burn ≡ fresh install, AND a burn that did NOT fully take must never
present that way. The residue sweep is a DESTRUCTIVE BOOT OPERATION: it unlinks files at cold start,
before any authentication.

## Five standing instructions
1. PROVE ANY TABLE COMPLETE, NOT SELF-CONSISTENT. Hunt the MISSING ROW.
2. A GATE CAN BE WRONG BY BEING TOO NARROW. Prove BOTH directions: what it wrongly admits AND what it
   wrongly STRANDS.
3. HUNT THIS PATTERN — it has produced a HIGH six times in this unit, each inside the fix for the
   previous one: *an authoritative result exists, and a consumer uses something weaker.* Four forms so
   far: (a) DATA-FLOW — verdict discarded, recomputed from a cheaper signal; (b) LIFECYCLE — verdict
   carried, but a consumer runs BEFORE publication and reads a default; (c) SECOND AUTHORITY — a
   separate code path decides the same thing; (d) INCOMPLETE INPUT SET — the same decision function
   called with fewer arguments than another caller passes. For every safety verdict ask: who consumes
   it, do they use THIS EXACT VALUE, are they ORDERED AFTER publication, is there ANOTHER writer, and
   does EVERY caller pass the FULL input set?
4. A CLAIM AND THE WORK IT CLAIMS MUST HAVE THE SAME LIFETIME.
5. A TEST THAT A VALUE IS COMPUTED IS NOT A TEST THAT IT IS USED. Judge coverage at the CONSUMPTION
   site. Two false claims have already been found in test headers in this unit — look for more.

## Focus
A. Enumerate EVERY site assigning `route` or `vaultExists` in MainActivity.kt. For each state whether
   it is ordered after `bootReconciled`, whether it uses the carried `residueSweepHold`, and whether
   it passes the FULL input set to its decision function. A previous lens concluded this is now
   positively decidable and that no further site exists — verify or refute that independently.
B. `PostBurnRoute.IGNORE_STALE`, added in 5ef5f77 and evaluated FIRST. `BurnCompletion` is
   process-lifetime and never cleared, so the observer re-fires on later compositions. Verify: does
   the stale arm ever swallow a case that MUST act — above all a FAILED burn, which by definition
   leaves an image present? Can it mask a confirmed-delete state that D2c must finish? Is
   `burnReportedSuccess && vaultImagePresent` the correct and complete staleness predicate, or is
   there a state where a successor vault exists and the completion is NOT stale?
C. `bootRoute` precedence including the `legacyImage` arm; `runBootReconcile`'s contract (once-only,
   publication in `finally` on every exit, fail-closed default, cancellation cannot strand the claim,
   dispatcher injection changing no production behaviour).
D. `sweepOrphanedResidue`'s gate — `image PROVEN absent AND no delete-confirmed`. The delete-intent
   clause was removed and that removal is RATIFIED by the maintainer. Read destroy(), create() and
   obliterateLocked in full and verify the PREMISES that ratification rests on: that destroy() writes
   the confirmed marker durably BEFORE any unlink, and that create() clears both markers durably
   before writing the DEK. Prove the WRITER/READER table COMPLETE.
E. The cumulative unit: destroy() equivalence under keys-first unlinks; marker clear strictly after
   unlinks proven durable; all boot healers as ONE system (overlap, contradiction, or a state no one
   owns); WRITER/READER invariants for durable signals AND in-flight verdicts; reachability (slot 0
   unarmed, wipe wired only to lock-screen dispatch); concurrency/lifecycle; fail-closed — can a
   partial burn present as success, or leave state worse than not burning?
F. Test quality: does any test pass vacuously? Does any test header claim a mutation it does not
   catch? Name the failure shape that is STILL untested.

NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
pre-existing, inherited from destroy(), and deliberately out of scope. Say if you disagree.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give explicit verdicts on A-F. State clearly whether this
is READY TO MERGE. If it is correct, say so plainly and briefly — do NOT invent findings to appear
thorough. An honest clean pass is the expected outcome if the code holds. If you believe a previous
round's accepted conclusion was WRONG, say so explicitly.
