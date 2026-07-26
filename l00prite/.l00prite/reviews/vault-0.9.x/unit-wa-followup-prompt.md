You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is the FOLLOW-UP review of Unit W-A. You are reviewing ONE delta: the follow-up commit that lands
AFTER the round-4 clean convergence. Several reviewers run independently on this same commit; you are
blind to all of them. Report only what YOU derive from source.

YOU HAVE A PRIVATE, WRITABLE CHECKOUT. Read anything you need — git, grep, whole files — and you MAY
build and run tests here. It is a disposable worktree; nothing you do affects anyone else. NOTHING is
inlined in this brief and nothing has been trimmed. If a verdict depends on source, go read it; do not
caveat a verdict as unverifiable.

SCOPE — the delta under review:
  git diff aa380c1..HEAD        (HEAD = bdde066, the follow-up delta)
  git show bdde066
Context if you need it: the reviewed-and-converged unit is `git diff main...aa380c1`.

## What this delta is
Unit W-A adds a cold-start sweep that deletes orphaned vault residue (`vault.dek` /
`vault.bin.tmp` / `vault.dek.tmp` with NO `vault.bin`) plus fail-closed boot routing that consumes the
sweep's durability verdict. The hazard being fixed: boot routing keyed on `vault.bin` alone read that
residue as "no vault" and presented first-run ONBOARDING while `vault.bin.tmp` staged a COMPLETE outer
image. That unit reached clean convergence at round 4 (acb5904 / aa380c1).

This delta was deliberately HELD OUT of the convergence commit so that commit stayed the reviewed one.
It batches three things:
  1. The round-4 INFO tests (+4, suite 487 -> 491): the two post-mutation branches of
     `sweepOrphanedResidue` that were uncovered (residue that SURVIVES its unlink — `File.delete()`
     reports failure by returning false, not by throwing; and a step that THROWS after the unlinks),
     plus two `afterPublish` cancellation tests. One of the latter is labelled
     "MUTATION UNIQUELY CAUGHT: NONE" — the claimed mutation was run and survived, so it is
     characterisation, not coverage.
  2. `onRetryDestroy` — the LAST consumer still judging success by `!hasVault() &&
     !serverDeleteConfirmed()` while the other four went through the single derivation. Now routes
     through `deriveBootDecisionFromDisk()`. This is the SOLE BEHAVIOURAL CHANGE in the delta.
  3. Three stale docstring/comment claims corrected in place.

## VERIFY EVERY CLAIM AGAINST SOURCE
Do not trust comments, kdoc, or the commit message. In this unit's history, comments were wrong
repeatedly and each was caught only by re-derivation: an invariant table internally coherent but wrong
about ownership; a kdoc asserting a wait that did not happen; a kdoc claiming `create()` "refuses" when
it CLEARS; two test headers naming mutations they could not catch. The commit message of THIS delta is
itself a claim to be checked, including its stale-claim corrections — a correction can be wrong, and a
correction can be incomplete.

## Binding focus items — give an explicit verdict on each

A. **`onRetryDestroy` — THE SOLE BEHAVIOURAL CHANGE. Spend most of your effort here.** This is the
   account-delete surface, historically the highest defect-density area of this codebase. The new
   criterion is claimed to be STRICTLY STRONGER: `hasVault()` keys on `vault.bin` alone, so a retry
   that left a stray DEK or temp behind previously reported SUCCESS and routed to onboarding over
   recoverable residue. ONBOARDING now additionally requires `vaultProvenAbsent` (`Files.notExists`
   over all four image-bearing files).
   - Build the FULL post-retry state map yourself and give the OLD verdict and the NEW verdict for
     every row. Name every row whose behaviour CHANGES, and say for each whether the change is a fix
     or a regression.
   - No hold supersede was added here. The justification: the hold cannot be raised on this path
     (reachable only via `Route.DeleteIncomplete`, which requires the confirmed marker, and a held
     boot admits no session). Attack that — is it actually unreachable, or merely convenient?
   - Is the user ever left with NO in-app exit? Under what filesystem state, and is that state
     reachable?

B. **EXISTING COVERAGE WAS NOT WEAKENED.** Confirm no existing test was deleted, defanged, or stripped
   of `@Test`. Count `@Test` annotations at `aa380c1` and at HEAD yourself and report both numbers.

C. **TEST HONESTY.** Does any new test pass vacuously? Does any header claim a mutation it cannot
   catch? Independently RUN the two claimed sweep mutations (remove the post-unlink re-stat; remove the
   total catch) and report whether the tests actually fail. Is the "MUTATION UNIQUELY CAUGHT: NONE"
   label honest, or is it covering for a test that proves nothing?

D. **THE MainActivity POST-DESTROY COMMENT.** The old comment claimed `{image survives, confirmed
   absent}` cannot occur "because destroy throws before the retire when absence is unproven". The
   delta asserts that is FALSE — `destroy()`'s verify is `exists()`-based, true only on a PROVEN
   PRESENCE, so an indeterminate stat reads as absent and passes; if dirSync then reports DURABLE the
   markers are retired, and the state IS reachable on a pathological filesystem. It further asserts
   what makes it safe is the ROUTING: that same indeterminate stat leaves `vaultProvenAbsent` false,
   so `bootRoute` falls through to LOCKED. Verify BOTH halves against source, and check the new comment
   for the same defect it replaced.

E. **THE OTHER DOCSTRING CORRECTIONS.** Two more: the `BootReconcileOwnerTest` header that described a
   local `runCatching` production no longer has, and the `runBootReconcile` kdoc that said production
   "passes `Dispatchers.IO`" when production relies on the parameter default. Verify each correction is
   itself accurate. **AND: check whether the SAME stale fact survives anywhere ELSE in the file or the
   delta** — a correction that fixes two of three instances is the failure mode this unit has produced
   before.

F. **INDEPENDENTLY RUN THE TEST SUITE.** Do not trust any reported figure. Run it in your worktree
   (`cd apps/android && ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest`) and report the
   numbers YOU observed. The commit claims 491 total / 488 passed / 0 failures / 3 skipped.

G. **ANY OTHER DELTA ISSUE.** Anything else in this diff — including whether the commit message
   overstates what the code does.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give explicit verdicts on A–G. State clearly whether this
is READY TO MERGE. An honest clean pass is a real and expected outcome if the code holds — do NOT
invent findings to appear thorough.
