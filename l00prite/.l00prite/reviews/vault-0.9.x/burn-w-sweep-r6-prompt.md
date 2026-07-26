You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 6 — the FINAL round. Several reviewers run independently on this same commit; you are
blind to all of them. Report only what YOU derive from source.

YOU HAVE DIRECT ACCESS TO THE REPOSITORY AT /root/zitrone. Read whatever you need yourself — git,
grep, whole files. NOTHING is inlined in this brief and nothing has been trimmed for you. If a verdict
depends on source, go read it; do not caveat a verdict as unverifiable.

SCOPE — the cumulative unit as it would merge:
  git -C /root/zitrone diff main...HEAD
Most recent delta (the round-5 fixes):
  git -C /root/zitrone show 800d7ab
Commits touching only l00prite/ are loop bookkeeping with NO code — ignore them entirely.
Key files: apps/android/app/src/main/java/com/zitrone/app/{ZitroneApp,MainActivity}.kt,
.../crypto/vault/VaultImageStore.kt, and the tests in app/src/test/java/com/zitrone/app/.

DO NOT MODIFY, CREATE OR DELETE ANY FILE. Report findings only.

This is the LAST round: the loop stops after it regardless of outcome, and a human takes the decision.
That means a missed defect ships to that decision unreviewed. Scrutiny should be HEAVIER here, not
lighter — do not let "it has been through five rounds" soften the pass. Equally, do not invent
findings to appear thorough: an honest clean pass is a real and expected outcome if the code holds.

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust comments, kdoc, or commit messages. This unit's
comments have been wrong repeatedly and every one was caught only by re-derivation: an invariant table
internally coherent but wrong about ownership; a kdoc asserting "Splash blocks on bootReconciled" when
it did not; a kdoc claiming create() "refuses to run while either marker is present" when it CLEARS
them; two test headers naming mutations they provably could not catch.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is UNARMED
(uniformly-random filler), so the wipe is unreachable in production — this unit ships the MECHANISM
only. Central invariant: post-burn ≡ fresh install, AND a burn that did NOT fully take must never
present that way. The residue sweep is a DESTRUCTIVE BOOT OPERATION: it unlinks files at cold start,
before any authentication.

## Standing instructions
1. PROVE ANY TABLE COMPLETE, NOT SELF-CONSISTENT. Hunt the MISSING ROW.
2. A GATE CAN BE WRONG BY BEING TOO NARROW. Prove BOTH directions: what it wrongly admits AND what it
   wrongly STRANDS.
3. HUNT THIS PATTERN — it has produced a HIGH six times in this unit, each inside the fix for the
   previous one: *an authoritative result exists, and a consumer uses something weaker.* Forms seen:
   (a) DATA-FLOW — verdict discarded, recomputed from a cheaper signal; (b) LIFECYCLE — verdict
   carried, but a consumer runs BEFORE publication and reads a default; (c) SECOND AUTHORITY — a
   separate code path decides the same thing; (d) INCOMPLETE INPUT SET — the same decision function
   called with fewer arguments than another caller passes; (e) SIBLING CALL SITE — see A below.
4. A CLAIM AND THE WORK IT CLAIMS MUST HAVE THE SAME LIFETIME.
5. A TEST THAT A VALUE IS COMPUTED IS NOT A TEST THAT IT IS USED. Judge coverage at the CONSUMPTION
   site.

## Binding focus items for this round
A. THE SIBLING-CALL-SITE CLASS. The round-5 defect was a fix that REINTRODUCED THE EXACT TELL IT WAS
   FIXING, one call site over: a new enum arm was handled exhaustively at one consumer and swallowed
   by an `if/else` chain at its sibling. For EVERY fix in this delta, do not merely check that the
   fixed instance is correct — check whether any SIBLING shares its shape and was left behind.
B. THE SINGLE-APPLIER INVARIANT. The burn dispatcher no longer writes UI; exactly one applier is
   claimed to exist (the process-scoped observer). Verify that in source: no second writer, no path
   where the dispatcher still mutates UI state, and the apply-once guard
   (`AppContainer.tryApplyBurnCompletion`) cannot be defeated by rotation, cancellation, or a
   composition created after the burn. Can a completion be consumed but never delivered?
C. DEFAULT-PARAMETER REMOVAL. `legacyImage` and `vaultImagePresent` defaults were removed from
   safety-decision functions so that omitting an input is a COMPILE ERROR. Verify no other
   safety-decision function in the touched surface still carries a default that could re-enable an
   incomplete input set.
D. TABLE COMPLETENESS, AGAIN. A previous round found `retireLegacyImage` to be a THIRD writer of
   `{dek, no bin, no markers}` that the table omitted. Enumerate EVERY writer of EVERY state the sweep
   gate reasons about and confirm none is missing. This instruction has already caught two real gaps
   in this delta — do not soften it.
E. INDEPENDENTLY RE-RUN THE TEST SUITE. Do not trust the commit message or any summary. Run it
   yourself (`cd apps/android && ./gradlew testDebugUnitTest`, ANDROID_HOME=/opt/android-sdk) and
   report the numbers YOU observed.
F. VERIFY EVERY STATED FIX ACTUALLY LANDED IN THE DIFF. An edit script aborted on a bad anchor in an
   earlier round, leaving a function defined but never called. A fix that silently does not apply
   means the ledger says fixed and the code is not. Check each claim in `git show 800d7ab`'s message
   against the actual code.
G. Re-verify the CUMULATIVE unit, assuming NOTHING from earlier rounds: destroy() equivalence under
   keys-first unlinks; marker clear strictly after unlinks proven durable; all boot healers as ONE
   system; WRITER/READER invariants for durable signals AND in-flight verdicts; reachability (slot 0
   unarmed, wipe wired only to lock-screen dispatch); concurrency/lifecycle; fail-closed — can a
   partial burn present as success, or leave state worse than not burning?
H. TESTABILITY ASSESSMENT (feeds a maintainer decision, answer concretely). `kotlinx-coroutines-test`
   and `robolectric` are already declared in app/build.gradle.kts with
   `unitTests.isIncludeAndroidResources = true`; `compose-ui-test-junit4` is NOT. Of the remaining
   untested lifecycle behaviour, what is reachable at a HOST-JVM or ROBOLECTRIC seam as the code
   stands or with a small extraction, and what GENUINELY requires Compose UI testing? Name specific
   behaviours on each side.
I. NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
   pre-existing, inherited from destroy(), and deliberately out of scope. Say if you disagree.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give explicit verdicts on A–I. State clearly whether this
is READY TO MERGE. If you believe a previous round's accepted conclusion was WRONG, say so explicitly.
