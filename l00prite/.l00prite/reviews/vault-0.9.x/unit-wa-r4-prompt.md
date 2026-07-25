You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 4 of a blind multi-reviewer review. You are reviewing the ROUND-3 FIX DELTA. Several reviewers run independently on
this same commit; you are blind to all of them. Report only what YOU derive from source.

YOU HAVE A PRIVATE, WRITABLE CHECKOUT. Read anything you need — git, grep, whole files — and you MAY
build and run tests here. It is a disposable worktree; nothing you do affects anyone else. NOTHING is
inlined in this brief and nothing has been trimmed. If a verdict depends on source, go read it; do not
caveat a verdict as unverifiable.

SCOPE — the whole unit as it would merge:
  git diff main...HEAD          (a98677f + 0d348b4 + 54a41bf + acb5904)
  git show acb5904              (the fix delta under primary review)

## What this unit is, and why it exists as its own unit
Unit W-A is an EXTRACTION. A larger unit ("Unit W") combined a duress-wipe mechanism, its
post-wipe presentation layer, and this residue sweep. That unit ran six adversarial review rounds and
reached its cap WITHOUT clean convergence: each fix was locally correct and wrong one layer out, all of
the same family — *an authoritative result exists and a consumer uses something weaker*. The maintainer
judged the unit under-DESIGNED rather than under-reviewed and split it. This is the half that every
lens had independently cleared; the duress-wipe mechanism and its presentation layer are deferred to a
separate unit that is being redesigned.

**THEREFORE: the prior rounds reviewed this code IN A DIFFERENT CONTEXT. You are reviewing the
EXTRACTION.** Extraction can introduce defects that no earlier round could have seen. Do not treat any
earlier conclusion as carrying over.

## What the unit does
The vault directory can legitimately hold a `vault.dek` / `vault.bin.tmp` / `vault.dek.tmp` with NO
`vault.bin`. Two ordinary interruptions produce it: an interrupted `create()` (DEK written durably
before the image) and an interrupted `retireLegacyImage()` (unlinks image, then DEK). Boot routing
keyed on `vault.bin` alone read that as "no vault" and presented first-run onboarding — while
`vault.bin.tmp` stages a COMPLETE outer image. The unit adds a cold-start sweep that deletes the
orphan, plus fail-closed boot routing that consumes the sweep's durability verdict.

DO NOT MODIFY the canonical repository. Building and testing inside your own worktree is expected.

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust comments, kdoc, or the commit message. In the parent
unit, comments were wrong repeatedly and each was caught only by re-derivation: an invariant table
internally coherent but wrong about ownership; a kdoc asserting a wait that did not happen; a kdoc
claiming `create()` "refuses" when it CLEARS; two test headers naming mutations they could not catch.

## What round 3 found, and what acb5904 changed

**THE THREE ITEMS IN THIS DELTA ARE NOT EQUALLY RISKY. WEIGHT YOUR ATTENTION ACCORDINGLY.**

**ITEM 1 — THE SOLE REAL RISK. Spend most of your effort here.** A routing change in the
ACCOUNT-DELETE surface, historically the highest defect-density area of this codebase.
`lockIf` publishes `session=null`, which wakes the session collector — so the account-delete
completion callback and that collector decide the SAME routing moment. They previously read the same
two stats and a comment asserted "the two cannot disagree", which was true then. The extraction made
it false: the collector got the carried `residueSweepHold`, the delete path stayed on `hasVault()` +
`serverDeleteConfirmed()`. With a hold raised, collector says LOCKED, delete path says Onboarding,
both write `route`, last writer wins.
Now unified through `deriveBootDecisionFromDisk`, and a COMPLETED destroy first clears the hold via
the new pure `destroySupersedesResidueHold(vaultProvenAbsent, serverDeleteConfirmed)`.
**Attack specifically:** is clearing the hold on a completed destroy actually justified, or is it
convenient? Is there a state where destroy "completes" without superseding the sweep's uncertainty?
Does routing the delete path through `bootRoute` change behaviour in ANY reachable post-destroy
state — the claim is that a surviving image implies the markers were not retired, so
`serverDeleteConfirmed` is still set and the result is DELETE_INCOMPLETE, never the lock gate, and
that {image survives, confirmed absent} cannot occur. Verify or refute that. Does the collector still
race this callback in any way now that both use one derivation? Does clearing a process-scoped hold
from the delete path affect any OTHER consumer of that hold?

**ITEMS 2 AND 3 — near-trivial; verify and move on.**
- `runBootReconcile` now wraps `afterPublish` in `runCatching`, because a throwing lambda propagated
  out of the launch and could kill the process. Confirm it cannot affect the published verdict.
- The row-6b test docstring's false intent-gate proof was corrected to match the store's table.

## Binding focus items
A0. **SIBLING CALL SITES OF EVERY ROUND-1 FIX.** The single-derivation change (`deriveBootDecision`)
   touched all three consumers at once — verify each now passes the FULL input set and that none was
   left on an older shape. The removed legacy effect: confirm no OTHER path still routes on legacy
   without the confirmed-marker precedence. The restored row-7 test: confirm no OTHER gate is
   uncovered.
A. **NOTHING BURN-DEPENDENT SURVIVED THE CUT.** The duress-wipe mechanism (`burnVault`,
   `obliterateForBurn`, `wipeBiometricMaterial`, `wipeAppLocalStateForBurn`) and its presentation
   layer (`BurnCompletion`, `postBurnRoute`, `signalBurnCompleted`, `tryApplyBurnCompletion`) are all
   supposed to be absent. `onBurn` in MainActivity is claimed to be UNCHANGED FROM MAIN (a stub that
   shows a uniform failure and destroys nothing) — verify that against `git show main:` yourself.
B. **THE COUPLING LINE IS CLEANLY SEVERED.** In the parent unit the two halves were coupled by exactly
   one line, `signalBurnCompleted(obliterated = burned)` inside `onBurn`. Confirm no residue of that
   coupling remains — no dangling caller, no half-removed state, no field that now has no writer.
C. **THE TWO EXCLUDED HEALERS LEFT NO DANGLING CALLERS OR STALE REFERENCES.**
   `completeInterruptedBurn()` and `reconcileOrphanedBurnMarkers()` were deliberately excluded because
   their trigger states are unreachable by construction without the duress wipe. Verify that claim
   independently: `create()` writes the DEK first, and `destroy()` writes `vault.delete-confirmed`
   durably before it unlinks. Then confirm nothing references them and no comment still assumes they
   run.
D. **W-A IS CORRECT STANDALONE, INCLUDING THE "STRICTLY BETTER THAN MAIN" CLAIM.** The unit claims
   that today (on main) `{bin absent, dek present}` routes to onboarding and is overwritten by a later
   create, whereas W-A clears it durably first — i.e. no state is made worse. Verify or refute.
E. **THE SWEEP GATE.** It is a DESTRUCTIVE BOOT OPERATION running before any authentication. Prove
   BOTH directions: what it wrongly DELETES and what it wrongly STRANDS. Prove the WRITER/READER table
   COMPLETE, not self-consistent — hunt the MISSING ROW. There is deliberately no `delete-intent` gate;
   verify that reasoning against `destroy()` and `create()` rather than accepting it.
F. **THE VERDICT IS CARRIED, NOT RE-DERIVED.** The sweep's durability result must reach the routing
   decision as a value, never be recomputed there from a fresh stat (a stat reports absence the instant
   a file is unlinked, durable or not). Enumerate EVERY consumer of boot-routing state and confirm each
   uses the carried verdict, is ordered after publication, and passes the FULL input set to `bootRoute`.
   This exact class produced six HIGHs in the parent unit — in four different forms: verdict discarded
   and recomputed; consumer running before publication and reading a default; a second code path
   deciding the same thing; and the same function called with fewer arguments than another caller
   passes.
G. **`runBootReconcile`'s CONTRACT:** once-only, publication in `finally` on every exit including
   cancellation, fail-closed default, and a claim that cannot be stranded. Verify against source, then
   against its tests.
H. **INDEPENDENTLY RUN THE TEST SUITE.** Do not trust any reported figure. Run it in your worktree
   (`cd apps/android && ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest`) and report the
   numbers YOU observed. The commit claims 487 total / 0 failures / 484 passed / 3 skipped.
I. **TEST QUALITY.** Does any test pass vacuously? Does any header claim a mutation it cannot catch?
   Is anything tested against a COPY of the logic rather than the logic itself? Name the failure shape
   that is still untested.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give explicit verdicts on A–I. State clearly whether this
is READY TO MERGE. An honest clean pass is a real and expected outcome if the code holds — do NOT
invent findings to appear thorough.
