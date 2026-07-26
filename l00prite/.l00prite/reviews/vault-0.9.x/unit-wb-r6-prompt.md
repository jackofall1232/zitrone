You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability
messenger. This is ROUND 6 of a blind multi-reviewer review of Unit W-B (the Pucker Burn duress wipe).
Several reviewers run independently on this same range; you are blind to all of them. Report only what
YOU derive from source.

YOU HAVE A PRIVATE, WRITABLE CHECKOUT. Read anything — git, grep, whole files — and you MAY build and
run tests. Nothing is inlined here and nothing has been trimmed. If a verdict depends on source, go
read it; do not caveat a verdict as unverifiable.

SCOPE — the unit as it would merge:
  git diff main...HEAD          (HEAD = 87282ff)
  git log --oneline main..HEAD
The ROUND-5 FIX DELTA specifically, which is what this round exists to attack:
  git diff 9bf1f1e..HEAD        (6a7f70f = the eight fixes; 9bddc89 + 87282ff = two gate-red repairs)

**THE CAP WAS EXTENDED ONCE, BY THE MAINTAINER, TO SEVEN. ROUND 7 IS TERMINAL — there is no further
extension.** The reason for the extension is directly relevant to what you should attack: round 5
found that THE VERIFIERS WERE NOT VERIFYING, and stopping with those repairs unreviewed was judged
the worst available outcome. So the repairs to the checking layer are the point of this round.

If you believe convergence is not reachable in one more round, say so explicitly WITH the specific
thing that would have to be true for it to converge. That is a finding, not a hedge, and it feeds a
real stop-or-rescope decision.

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

## ROUND 6 — THE VERIFIER LAYER IS THE PRIME SUSPECT

Findings by round: 1 → three HIGH. 2 → three more, two inside round 1's fixes. 3 → four, three inside
round 2's fixes and one inside the gate rebuilt to catch them. 4 → three HIGH + a blocking MEDIUM,
including a claim FALSE THE DAY IT WAS WRITTEN. 5 → eight, four blocking, **three of which were
CHECKS THAT DID NOT CHECK**.

**ASSUME EVERY CHECK IS GUILTY UNTIL SHOWN TO DISCRIMINATE.** For each one ask the question that
caught the last three: *what wrong implementation would still satisfy this?* The verifier layer is
now the part of this unit with the worst track record, and it is the part that was most recently
rewritten.

1. **`runBurnPlan` NOW CALLS `verify()` AFTER EVERY STEP** (it previously called `action()` only, so
   the registry's primary consumer never read the postconditions — "one enumeration, three consumers"
   while the burn used none of them).
   - Does re-verifying actually fail the burn on every step, or can a postcondition throw and be
     swallowed somewhere?
   - **Check all SEVEN postconditions individually.** For each: what surviving residue would it still
     report as clean? Two were provably wrong last round; assume more are.
   - Does boot's `completeInterruptedCleanup` and the burn now agree on what "done" means for each
     step, or can they disagree?

2. **THE TWO KEYSTORE VERIFIERS, REPAIRED.** `noAliasesRemain()` now shares ONE predicate with the
   wiper (`isBiometricAlias`, covering `PREFIX*` and `LEGACY_ALIAS`); `keyMaterialExists()` now uses
   `containsAlias` rather than `existingKey()` (which tested USABILITY and swallowed its own
   exception, defeating a `getOrDefault(true)` labelled fail-closed). `wipeBiometricMaterial()` now
   returns the postcondition rather than "nothing threw". Verify each repair, and hunt for a THIRD
   probe with the same shape anywhere in the burn path.

3. **PREFERENCES MOVED TO `AFTER_IMAGE`.** The "innocuous if interrupted" argument was false for that
   step — resetting Tor/I2P/read-receipts/TTL/burn-on-read/auto-lock on a surviving vault is a
   durable user-visible tell. Verify the move is correct AND that the three steps REMAINING in
   `BEFORE_IMAGE` (diagnostics, cache, notifications) genuinely pass the same test: would an
   interruption after each of them be something the OS or user produces routinely anyway?

4. **THE BURN NOW NAMES ITS FAILING STEP** before throwing (`DestroyFailed` carries a fixed
   "a file survives" message that is wrong for six of seven steps). **Confirm the naming is correct
   for ALL SEVEN steps, not just the one that motivated it.**

5. **THE 3s BOUNDED WAIT IN `cancelAll`.** The notification cancel is a cross-process binder call and
   `activeNotifications` is system_server's view, so the read-back lagged and the postcondition
   failed over a cancel that had worked. The wait was put in the ACTION.
   - Confirm it is **FAIL-OPEN**: an expired wait must report the truth and let the burn fail closed
     on it, never mask a survivor.
   - Confirm **`noneActive()` was NOT weakened** to tolerate a lingering notification. The fix for a
     flaky verifier must not be a verifier that cannot fail.
   - Is a 3s bounded wait acceptable inside a duress wipe at all? Is there a path where it delays the
     burn observably?

6. **THE GATE'S NOTIFICATION DOMAIN, NOW SEEDED.** Round 5 added the domain to the snapshot, baseline
   and comparison and never seeded it — empty ≡ empty passed on every run. It now posts a real
   notification (needing a `POST_NOTIFICATIONS` grant, without which `showNewMessage` silently
   no-ops), asserts it present before the burn, and has its own negative control; an unreadable
   snapshot yields a SENTINEL rather than `emptyMap()`. Verify the seed genuinely lands and the
   control genuinely discriminates. **Are any OTHER gate domains seeded in a way that can silently
   fail to land?**

7. **THE ORDERING PIN, MADE TRUE.** "Pinned by `BootReconcileOwnerTest`" was FALSE (zero references
   to the symbol). `foldBootMutators` now takes the image-absence gate as a LAMBDA so a test can
   observe WHEN it is evaluated. Verify the new pin actually pins, and **apply the same check to
   every other "pinned by"/"asserted by" claim in this unit — grep the named test for the named
   symbol.**

8. **ANY OTHER INVARIANT THIS DELTA INVALIDATED WITHOUT RE-DERIVING.** That failure mode has now
   happened twice in this unit, and no staleness review catches it.

## EVIDENCE STATUS
- Unit suite claim: **549 / 546 passed / 0 failures / 3 skipped**. Round 5's Grok reproduced the
  then-current numbers using `JAVA_TOOL_OPTIONS=-Djna.tmpdir=<writable>`; try that if the suite will
  not start, and report NO numbers rather than adopting the claim if you cannot run it.
- Instrumented gate: **GREEN on 87282ff, run 30183560801, 5 tests, BUILD SUCCESSFUL in 5m19s** — but
  it took THREE runs. The two reds are evidence, not noise: `30182993737` caught the notification
  seed never posting (the negative control reporting its own domain unobservable), and
  `30183276996` caught the new per-step verify racing its own action. Treat all three as CI claims.
