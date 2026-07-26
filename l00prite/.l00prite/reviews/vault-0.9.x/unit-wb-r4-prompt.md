You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability
messenger. This is ROUND 4 of a blind multi-reviewer review of Unit W-B (the Pucker Burn duress wipe).
Several reviewers run independently on this same range; you are blind to all of them. Report only what
YOU derive from source.

YOU HAVE A PRIVATE, WRITABLE CHECKOUT. Read anything — git, grep, whole files — and you MAY build and
run tests. Nothing is inlined here and nothing has been trimmed. If a verdict depends on source, go
read it; do not caveat a verdict as unverifiable.

SCOPE — the unit as it would merge:
  git diff main...HEAD          (HEAD = 1e9a755)
  git log --oneline main..HEAD
The ROUND-3 FIX DELTA specifically, which is what this round exists to attack:
  git diff 62bb0fd..HEAD        (2146cee is the code; 7fa9b0c and 1e9a755 are memory/docs)

## What this unit is
The DURESS WIPE. A "Pucker Burn" credential in reserved slot 0 triggers an irreversible local wipe.
Its purpose is that **post-burn state is indistinguishable from a fresh install** — a coerced user
hands over a device that looks like it never held a vault. Unit W-A (the cold-start orphan residue
sweep and fail-closed boot routing) shipped separately and is in `main...aa380c1`; this unit builds on
it. Slot 0 is UNARMED until a later unit, so `PassphraseOutcome.Burn` is structurally unreachable in
production today — deliberate sequencing so the riskiest durable-state change lands while nothing can
trigger it.

## VERIFY EVERY CLAIM AGAINST SOURCE
Do not trust comments, kdoc, or commit messages. This unit's history is a history of confident,
internally coherent, WRONG prose: an invariant table wrong about ownership; a kdoc asserting a wait
that never happened; a stale claim left four lines from the code it described; a design doc recording
a residual as "unavoidable" when a fix already existed; and a commit message declaring a defect class
CLOSED in the same commit that left two members of the class open. **The named invariants WB-1..WB-7
in `l00prite/.l00prite/reviews/vault-0.9.x/unit-wb-invariant-table.md` are claims to attack, not
premises.** The round-2 fix commits are unusually assertive in their messages; treat that as a reason
for suspicion, not comfort.

## BINDING FOCUS ITEMS — explicit verdict on each

A. **WB-3, ONE DURABILITY OWNER, THREE PRODUCERS.** `durabilityHold` means "some destructive mutation
   of local state did not prove durable" — producers are the cold-start sweep, the two boot
   reconcilers, and the burn's own obliterate. Routing must care only THAT it is raised, never WHICH
   producer raised it. **If any consumer needs to know which, the single-field design has broken down
   — report it as a finding rather than proposing a discriminator.** This closes a round-6 HIGH from
   the parent unit: a failed-but-clean burn (unlinks landed, `dirSync` failed) leaves a directory that
   STATS CLEAN, and without the hold the next boot presents a fresh install over an unproven wipe that
   a journal replay can undo. Verify the closure, and hunt for a fourth producer that should publish
   and does not.

B. **`destroy()` HAS TWO DELIBERATE DEVIATIONS. Neither may be accepted "by construction".**
   1. Unlink order flipped bin-then-dek → dek-then-bin (keys-first). The argument is that the
      confirmed marker is written first so a crash re-runs the idempotent destroy regardless of order.
      Evaluate it; the fallback is a `keysFirst` parameter (the landing spot if you reject the order
      change).
   2. The S4 verify moved from `exists()` to PROVEN absence (`Files.notExists`). Strictly
      fail-closed — and it makes `{image survives, confirmed absent}` unreachable through that path,
      which W-A's routing also guards downstream. **That downstream guard is DEFENCE IN DEPTH and must
      not be recommended for deletion as dead code**; say so if you disagree, but engage with the
      argument at `MainActivity`'s post-destroy comment.

C. **THE "EXISTS ONLY IF THE FEATURE WAS USED" DEFECT CLASS — DEMONSTRATED, NOT HYPOTHETICAL, AND
   RE-OPENED ONCE ALREADY.** The gate's FIRST EXECUTION found the vault device-key Keystore alias
   surviving every burn (created lazily on first `wrapDek`, absent on a device that never made a
   vault). Round 1 fixed that and declared the class closed. **Round 2 found two more members** —
   preference KEYS inside a file a fresh install also has, and three whole preference FILES a fresh
   install does not have — inside the commit that declared the class enumerated.
   **HUNT THE SAME SIGNATURE AGAIN, FROM SOURCE:** files, prefs keys, database tables, WorkManager job
   names, notification channels, cache directories, Keystore aliases. This is where a reviewer beats
   the gate: **the gate structurally CANNOT see an artifact that is created lazily and then correctly
   wiped, even though that artifact is an oracle for its entire lifetime between creation and burn.**
   A device seized in that window discloses the feature was used. Enumerate from source; a green gate
   is not an enumeration.

D. **ATTACK THE GATE ITSELF, NOT ONLY THE CODE UNDER IT — IT WAS JUST REBUILT, SO IT IS THE HIGHEST-
   RISK ARTIFACT IN THIS DELTA.** `BurnByteForByteGateTest` is load-bearing for DoD-8 and for a
   `SECURITY_MODEL.md` claim. Round 2 found it materially non-discriminating: it provisioned via
   `imageStore.create()` and therefore never created the residue it claimed to check, and `cacheDir`
   was not in the snapshot at all. It has now been rebuilt to provision through
   `createVaultAndPublish`, seed a named artifact per domain, assert each present before the burn, and
   carry a per-domain negative control. Ask:
   - would each negative control still DISCRIMINATE after plausible future changes to what burn wipes?
   - can ANY assertion in that file pass while proving nothing (empty coverage set, wrong-scoped
     snapshot, two things equal for an unrelated reason)?
   - **is the seeded set actually reached by the burn, or does a seed land somewhere the burn never
     looks — which would make the gate fail for a reason unrelated to the property it tests?**
   - does the `databases` domain's "assert it is empty" treatment hold, or is it a coverage claim
     wearing an assertion's clothes?
   - does the `@After` teardown genuinely restore a baseline, or can one test's residue corrupt the
     next test's "fresh" snapshot and make a later comparison pass for the wrong reason?
   - does the snapshot's coverage set actually cover what the `SECURITY_MODEL` section now claims?

E. **WB-1 — UNIFORM FAILURE AND THE DURABILITY HOLD ARE ONE INVARIANT.** A failed burn presents
   exactly as a wrong passphrase AND leaves the hold raised; neither half is safe alone. Verify both
   halves hold in source, and that no path reports a burn failure distinguishably.

F. **WB-2 — `NonCancellable` is a SECURITY property** (a wipe a rotation can interrupt is one a
   coercer can interrupt). Verify nothing above it can cancel the wipe mid-flight.

G. **WB-7 — BOOT MUTATOR ORDERING IS IRRELEVANT BY PROOF.** Three durable mutators run inside
   `runBootReconcile`. `BurnReconcilerTriggersTest` asserts at most one trigger fires across the
   enumerated states, with a non-vacuity guard. Verify the enumeration is complete for the predicates
   as written (round 2 flagged `vault.dek.tmp` as a missing bit — LOW, still open), and that the
   reconcilers' best-effort `false` is re-derived from disk rather than trusted.

H. **THE `vaultExists` INITIAL-VALUE CHANGE** (`MainActivity`, the `remember` initializer around line
   631). It was a disk stat on the Main thread; it is now `false`, the pre-reconciliation value,
   relying on the Splash gate to assign it before anything routes. **Verify NO consumer observes it
   before the Splash effect assigns it.**

I. **INDEPENDENTLY RUN THE UNIT SUITE** (`cd apps/android && ANDROID_HOME=/opt/android-sdk ./gradlew
   testDebugUnitTest`) and report YOUR numbers. Claim: 534 total / 531 passed / 0 failures / 3 skipped.
   The instrumented gate needs an emulator and is NOT runnable here. If you cannot run the suite in
   your sandbox, say so plainly and report NO numbers rather than adopting the claim.

   **GATE EXECUTION STATUS, so you are not guessing at it.** The rebuilt gate HAS been executed:
   - run 30178703899 on 2bd7af0 — **RED**, two failures, both in assertions the rebuild added
     (the seeded-artifact check and the prefs negative control). Cause: the snapshot raced
     production's async `apply()` writer.
   - run 30179007260 on 62bb0fd — **GREEN**, 4 tests started, 4 finished, BUILD SUCCESSFUL in 5m13s,
     after the flush barrier.
   Treat both as claims about a CI run, not as evidence you gathered, and note that a green gate is
   evidence about the scenario it runs — not about coverage completeness (see C and D).

J. **ANY OTHER DEFECT**, including whether any commit message overstates what the code does. The
   round-2 commits make strong process claims (a complete enumeration of preference stores; a complete
   enumeration of gated cleanups; six negative controls over five domains). **Check each enumeration
   for completeness against source** — an enumeration that is itself incomplete is worse than none,
   because it reads as having been checked.

## BLOCKING BOUNDARY — classify against this, not generic severity
Robustness residuals MAY be deferred and tracked. **Anything that breaks post-burn ≡ fresh install is
NOT a hardening layer — it is the feature failing at its purpose, and it BLOCKS.** Say explicitly, for
each finding, which side of that line it falls on.

## Output
Per finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters, concrete
fix, and BLOCKING-or-DEFERRABLE against the boundary above. Cite source you actually read. Give
explicit verdicts on A–J. State clearly whether this is READY TO MERGE. An honest clean pass is a real
and expected outcome if the code holds — do NOT invent findings to appear thorough.

## ROUND 4 — THE FIX DELTA IS GUILTY UNTIL PROVEN

Round 1 returned three HIGHs on a unit believed complete. Round 2 returned three more, two of them
inside round 1's own fixes. Round 3 returned four, three of them inside round 2's fixes, AND one
inside the gate rebuilt to catch exactly that. **Every fix round in this unit has surfaced something
new, and the newest code is the code most likely to be wrong.** Attack these specifically:

1. **PROCESS DEATH AT THE END OF A SUCCESSFUL BURN — the highest-risk item, an authorized
   ARCHITECTURE change, and new.** `runBurnWipe` gained a required `terminate` step that runs LAST and
   only on the success path; production passes `killThisProcess()` (`Process.killProcess(myPid())`).
   The reasoning: no in-process wipe is durable against a live writer, and the preference wipe's
   safety previously rested on a `commit()`-versus-queued-`apply()` ordering argument that three
   reviewers read three different ways and none could confirm — so the claim was replaced by a
   deterministic drain rather than argued. **Attack all of it:**
   - Is the ORDER right? `raiseHold → obliterate → lowerHold → terminate`. Is there any interruption
     point at which process death produces a FRESH-INSTALL presentation over an unproven wipe? Walk
     the crash windows, including death BETWEEN `lowerHold` and `terminate`.
   - The hold is an in-RAM `MutableStateFlow`. Killing the process destroys it. Is the disk-derived
     re-derivation at next boot ACTUALLY equivalent to the hold it replaces, on every path — or is
     there a state where the RAM hold said "doubt" and the boot reconcilers will say "clean"?
   - **WB-1 / deniability:** a successful burn now closes the app; a FAILED burn shows the uniform
     error and stays open. Is that asymmetry itself a distinguisher a coercer can read (app vanishes =
     burn succeeded; error = wrong password)? Weigh it against the previous behaviour (onboarding
     screen). Say plainly whether you think this is better or worse for the threat model — the
     in-tree comment claims it is a real tradeoff in BOTH directions and invites the challenge.
   - Is `killProcess(myPid())` the right primitive versus `exitProcess`/`finishAndRemoveTask`? Does
     ANYTHING legitimately need to run after a successful burn (WorkManager, a content provider, a
     `finally`, an unflushed durable write)? Note it deliberately does NOT run shutdown hooks.
   - Does anything OTHER than the success path reach `terminate`?

2. **`BootDiagnostics.erase()`** — replaced `clearProven()` + `clear()` with ONE body plus a
   fail-open UI wrapper. Memory (`_entries`, `loaded`) is cleared FIRST, under the same lock
   `record()` takes, then truncate, then `deleteIfExists`, then fsync of the parent, then
   `Files.notExists`. Verify: is memory-first actually sufficient against a concurrent `record()`, or
   is there an interleaving that still writes pre-burn lines? Is the fsync of `filesDir` the right
   directory? Does the fail-open `clear()` wrapper weaken anything the burn relies on?

3. **`deleteTreeDurably`** — replaced the Boolean `clearCacheDir`; returns `Unit` and throws;
   post-order recursion with ONE fsync per directory after its children are gone; fail-closed on an
   unreadable directory. Verify the durability argument: is one fsync per directory, post-order,
   correct — and is the claim that a subdirectory being deleted needs no fsync of its own actually
   sound? Is there an unbounded-work or symlink hazard? Does the retained Boolean wrapper
   (`clearCacheDir`, still used by the cold-start retry) reintroduce anything?

4. **THE GATE'S OWN CHANGES.** Teardown is now unconditional (it was `if (hasVault())`, which skipped
   cleanup after a partial burn and contaminated the next test's baseline), and `setUp` now asserts a
   fresh baseline derived from the SAME snapshotter the comparison uses. Attack: can the baseline
   assertion pass over a contaminated device? Does it duplicate or drift from the snapshot surface?
   Can a test still leak state past it? **And the standing limit: the gate passes `terminate = {}`,
   so it exercises a strictly WEAKER in-process arrangement than production ships — is anything now
   ONLY true in the test configuration?**

5. **THE CANARY.** `canary_a_queued_preference_write_does_not_resurrect_a_proven_absent_store` stages
   the race with a bounded 2s observation and is labelled as proving presence-of-bug, never absence.
   Is that labelling honest, or does the test still read as proof? Is it now testing an arrangement
   production no longer relies on — and if so, is keeping it right?

6. **THE NON-DISCRIMINATING ASSERTION** (failures.md, five occurrences, the last two found inside the
   fix for the class and inside the gate written to enforce it): for every assertion in the delta, ask
   what WRONG implementation would ALSO satisfy it, then the scenario-level form — **what did the test
   actually CREATE before it compared?**

7. **THE ENUMERATIONS.** `2146cee` claims a complete two-axis table over all six burn cleanups
   (gated? / durable? / clears memory?). **Check it against source for completeness on every axis**,
   and look for a THIRD axis nobody has named yet — the round-2 commit enumerated one axis correctly
   and shipped two blocking defects on axes it never mentioned.

## EVIDENCE STATUS, so you are not guessing
- Unit suite claim: **536 / 533 passed / 0 failures / 3 skipped**. NEITHER round-3 reviewer could
  corroborate this (one could not run Gradle at all; the other hit environmental JNA/sodium failures).
  Run it yourself if you can; report NO numbers rather than adopting the claim if you cannot.
- Instrumented gate: run **30180579742 GREEN on 2146cee — 5 tests started, 5 finished, BUILD
  SUCCESSFUL in 5m23s**, with the new baseline assertion active. Its first execution on the previous
  delta was RED in its own new assertions. Treat both as CI claims, not evidence you gathered, and
  remember a green gate is evidence about the SCENARIO it runs, never about coverage completeness.
