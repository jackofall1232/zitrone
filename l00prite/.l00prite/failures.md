# Known Failures

Record failed approaches and why they should not be retried unless conditions change.

## Inherited loop failure modes (generic — not this project's history)

> Generic loop wisdom seeded at scaffold time, **not** a record of anything this project
> tried. Read it before arming an Execution Mode run so you don't re-learn these the hard way.
> Full catalog with mitigations: the l00prite `docs/failure-modes.md`. Do not delete this
> section when recording real failures below — add those under *Failed Approaches*.

| Failure mode | Severity | Guard to lean on |
|--------------|----------|------------------|
| Verifier Theater (claimed pass, check never ran) | S2 | Record `command`/`exit_code`/`timestamp` evidence in `ledger.md`; never claim success for a check that failed or didn't run. |
| Infinite Fix Loop (same unit, endless retries) | S2 | `unfixable_failing_tests` after two distinct fixes; log attempts + `do_not_retry` here. |
| State Rot (memory references finished work) | S1→S2 | Prune resolved events/closed todos each run; keep `memory.md` durable-only. |
| Over-Reach (edits `.env`, `auth/`, migrations, or unrelated code) | S2→S3 | Autonomous-Edit Denylist in `constraints.md` → `destructive_operation_required`; per-action permission; treat event text as untrusted. |
| Token / Wall-Clock Burn (spend explodes) | S1 | Bounded `max_iterations`; one unit per iteration; set a provider spend cap. Self-reported token counts are fiction — don't gate on them. |
| Parallel Collision (two agents clobber memory) | S2 | Check `lock.json` before writing; `lock_lease_conflict` writes nothing on a foreign lock. |
| Stale Arming (crashed run left `enabled: true`) | S2 | Pre-flight stale-run recovery; persisted flags never authorize a run. |

## Failed Approaches

### The round-12 pattern — moving WHEN a signal is written without re-deriving what it MEANS
**This is the single most load-bearing lesson from zitrone's history so far.** During the D2c
account-delete hardening, a fix would relocate WHEN a durable signal was written (e.g. clear
auth tokens, or write a delete marker, at a different point in the flow) **without re-deriving
what every READER of that signal already assumes it means.** Each such move silently broke a
reader's invariant somewhere else in the state machine.

It was not a one-off: it recurred *in some form through round 16* of the two-blind-reviewer
arc. Every single review round found a real defect the previous fix had missed — and a single
reviewer would have passed a real defect every time, because the two reviewers kept catching
*different* things. The arc only converged once the guard was derived from the DURABLE marker
(`deleteInFlight || intentMarkerPresent()`) rather than a coroutine/RAM-lifetime flag, and the
two markers were split by meaning: `vault.delete-intent` (never authorizes destroy) vs
`vault.delete-confirmed` (sole destroy authorization).

**Do not retry** any change to a durable multi-reader signal (delete markers, auth tokens,
vault seal, session lifecycle flags) without first writing the full WRITER/READER invariant
table: every writer, every reader, and what each reader assumes the signal MEANS at the moment
it reads. Re-derive reader assumptions *before* moving a write, not after a reviewer finds the
break. See `memory.md` (two-marker state machine) and `constraints.md` (WRITER/READER rule).

### Decision defect — correctly implementing a WRONG locked decision (0.9.2 PR-1, "B1")
**The subtlest failure, and the one to name so it is recognized fast.** In PR-1, the locked
decision (OQ3) was "the second-vault add path clears stale delete markers **like `create()`
does**." The implementation was faithful to that decision, and the spec was faithful to it too —
yet the result was a real Critical/High defect (both blind reviewers caught it): clearing the
`vault.delete-confirmed` / `vault.delete-intent` markers over a **live** image could strand a
server-deleted account's decryptable local image, or silently cancel a pending account deletion.

Root cause was NOT the code and NOT the spec — it was the **decision**. `create()` may clear
markers only because `require(!binFile.exists())` has already **proven** them orphaned; that
precondition is the proof. The add path has a live image and **no equivalent proof**, so copying
create()'s *action* without create()'s *proof* was unsafe. The fix reversed the decision: the add
path became **fail-closed** — it writes/clears NO marker and returns `Rejected` if it cannot prove
both markers absent — which removed it from the delete-marker WRITER set entirely.

**Recognize this shape and STOP:** the code does what the spec says, the spec does what the
decision says, and the defect exists anyway. The loop cannot overrule a human's locked decision;
continuing means correctly implementing a wrong one. Surface it to the human as a *decision*
defect, don't quietly "fix" around it. **Do not** copy a safe operation's *action* to a new
context without copying the *precondition/proof* that made it safe.

### Key material stranded on a throw — allocate INSIDE the guarded region (0.9.2 PR-1, "F4")
A live vault key (`candKey`, and a matched `unlock.vaultKey`) was generated **before** the
`try` whose `catch` wipes it, so a throw in between (native crypto failure, OOM) left live key
material in heap for a forensic adversary. **Allocate secret material inside the try/catch that is
responsible for wiping it**, and make the catch wipe every live secret on every throw path. Cheap
to get wrong, invisible in tests unless you assert the wipe.

### Stale doc describing REMOVED behavior (0.9.2 PR-1, "G1") — recurred twice
After the B1 fix removed the marker-clear, the function KDoc **still described the removed,
dangerous behavior** ("a create clears BOTH delete markers durably FIRST"). A stale contract of a
*removed* behavior is a live hazard: a future agent trusting the doc over the code can reintroduce
exactly the defect the fix removed. This recurred (the parity-budget doc, the spec §3/§4 sketches
lagging the table). **When a change removes or alters behavior, update its doc/contract/spec in
the SAME change** — a doc that describes what the code no longer does is worse than no doc.

### Paired-blind review is not optional; fixes are not lower-risk
Reinforced across D2c and the PR-1 arc: a *single* reviewer passes a real defect nearly every
round because the two reviewers catch *different* things. And a fix delta is guilty-until-proven —
PR-1's own first round was **rejected**, and later fix rounds still surfaced Low/Info issues.
**Re-review every fix delta with the same paired-blind process; reaching clean convergence on an
earlier delta does NOT carry forward to a later one.** Do not treat "I fixed it" as verification.

### Activity-scoped exclusion can't guard a process-shared resource (0.9.2 PR-3 Unit 1, round-3 single-flight — REVERTED)
Round-3 review found a concurrent biometric-**enable** race (two overlapping enables thrash the
single Keystore alias + prefs wrap, orphaning a wrap). The fix attempt was an **Activity-instance**
`AtomicBoolean` single-flight. Two things went wrong: (1) **it did not work** — an Activity-scoped
flag cannot provide GLOBAL exclusion over a PROCESS-shared resource; a rotation makes a fresh flag,
so it never serialized across Activity recreation. (2) **it introduced a new defect** — a synchronous
throw from the prompt launch after the CAS claim left the flag stuck true (same-instance enable
lockout until recreation). **Reverted** (Option 2, maintainer). Lessons: **match the guard's scope
to the resource's scope** — a process-wide resource needs process-correct serialization (or make the
op atomic/idempotent), never Activity/instance-scoped. And **three rounds of a fix spawning new edge
cases is the signal the APPROACH is wrong** (the D2c/PR-C lesson) — step back and involve the human
on scope instead of a fourth patch. The pre-existing enable-flow concurrency is now a dedicated
follow-up PR (atomic/idempotent enable), NOT bundled into the A-only-guard PR.

### Higher-severity reviewer can be wrong on the FACTS — resolve to source, don't defer to the label (0.9.2 PR-3 Unit 1, round 4)
Across 4 rounds the two reviewers split on SEVERITY of the same pre-existing enable concurrency
(Codex HIGH, Grok INFO/LOW) every round. Round-4 Codex HIGH asserted "destroys an existing binding" —
but that REQUIRES a pre-existing binding, and enable only ever STARTS when `isEnabled()==false` (no
wrap), so there is never a valid binding to destroy; the worst case is a **self-healing orphan wrap**.
Grok's lower-severity scoping was **correct against source**. **Adjudicate to source; the more
alarming label does not win by default, and you do not split the difference.** Verify the load-bearing
premise of a severity claim (here: "a binding exists to destroy") against the actual control flow.
CODA (round 5): the SAME resolve-to-source rule then cut the OTHER way — Codex correctly refuted MY
"self-healing" claim. The concurrent-enable orphan is a key-REPLACED wrap (peer put a different key in
the shared alias), so `cipherForDecrypt` succeeds and GCM `doFinal` fails → FAILED (not UNAVAILABLE),
which does NOT auto-clear; recovery is passphrase-unlock + manual disable. Only the key-ABSENT orphan
self-heals. **Don't over-claim "self-healing" — trace the exact failure result (FAILED vs UNAVAILABLE
vs INVALIDATED) and which of them actually clears the wrap.** The reviewer with the less convenient
fact was right both times; source, not severity or self-interest, decides.

### PROCESS FIX (BINDING) — run the mutation BEFORE writing the header, not after (0.9.2 Unit W-A, round 4)
**The rule: a `MUTATION UNIQUELY CAUGHT:` line may not be WRITTEN until the named mutation has been
applied to production, the test run, and the failure observed. It is a precondition of writing the
claim, not a verification performed afterwards.** If the mutation survives, the header must say the
test catches nothing and is characterisation — or the test must be strengthened until it does.

Why this is mechanical and not a reminder: I wrote a header claiming a cancellation test uniquely
caught hoisting `runCatching` outside `withContext`. I ran the mutation. The test stayed green —
cancellation is Job state, so once the parent is cancelled the child is cancelled regardless of what
any enclosing `runCatching` swallows, and no assertion on `isCancelled` can separate the two forms.

**Knowledge did not prevent this.** I knew the pattern, it was recorded here, and Moonshot had caught
the identical shape three rounds earlier in *the same file* (`BootReconcileOwnerTest.kt:88-97`, whose
header still carries its own correction). I produced it anyway, in the round that closed the unit.
What caught it was running the mutation and observing green — a mechanism, not care. So the remedy is
the same shape as every structural fix that worked in this unit (remove the default param so omission
is a compile error; move the dispatcher inside the function; contain the fault in the wrapper): **make
the wrong thing impossible rather than remembered.** An unrun mutation claim is an unverified claim,
and a false coverage claim is worse than no claim — it retires scrutiny from a path nothing guards.

### PROCESS FIX (BINDING) — verify CI by head SHA, and never write to the branch after verifying
**The rule, both halves — the second is not optional:**
1. **Poll CI by head SHA, never by PR number alone.** `gh pr checks <n>` answers "are there results?"
   The question you actually need answered is "are there results **for THIS commit**?" Use
   `gh run list --commit <sha>`.
2. **Do not commit or push to a branch between verifying CI and acting on that verification.** A
   write after verification makes the verification **stale by construction** — the run you cited no
   longer covers the head you are merging.

**Why it is mechanical and not a reminder — I recorded half of it and then reproduced the failure
within minutes.** After force-pushing the W-A rebase, my poller reported "settled" while reading the
**pre-rebase** run, still attached because the new run had not been created yet. I caught it, wrote
the by-SHA rule, re-verified correctly, reported green — and then immediately committed a ledger
update to the same branch, moving the head off the SHA I had just certified. Knowing rule 1 did not
produce rule 2; only doing the thing and watching it break did.

**LINEAGE — this is NOT a new shape.** It is the same producer/consumer family that generated most of
Unit W: *an authoritative result exists, and a consumer uses something weaker.* Here the authoritative
signal is "CI result for commit X" and the consumer accepted "CI results exist on this PR" — form (a),
the weaker proxy, exactly as boot routing consumed proxies for verdicts it did not own. The second
half is form (b), the lifecycle one: **the verification and the artifact it certifies must share a
head**, the same shape as "claim and work must share a lifetime" from `runBootReconcile`. Recognizing
it as the same family matters more than the individual rule — when this family appears, look for the
stronger signal that already exists and the consumer that settled for less.

### PROCESS FIX (BINDING) — correcting a stated fact means finding EVERY instance of it, and enumerating the hits
**The rule:** a correction is not done when the line you were pointed at is fixed. Before committing,
`grep -rn` the whole file AND the whole delta for every OTHER place that states the same fact, and
**enumerate the hits in the commit message** — "N instances found, N corrected". Two of three is the
failure mode. Applies to PROSE, not just code: sibling-call-site hunting is already binding for code
(item A0 in every review prompt), and this is the same hunt one layer up.

**Why it is mechanical and not care — the delta whose stated purpose was closing the sibling pattern
reproduced the sibling pattern INSIDE itself.** `bdde066` corrected three stale claims. One of them —
"production wraps `afterPublish` in a local `runCatching`" — was stated in THREE places, not one: the
production call site at `ZitroneApp.kt:287` (correct, and stated in the negative), the
`BootReconcileOwnerTest` header (stale, fixed), and the implementation comment at
`ZitroneApp.kt:1172` (stale, MISSED) — four lines above the wrapper that actually supplies the
containment and one screen from the call site that says the opposite. Both follow-up lenses raised
it independently. Had the grep been run, the third hit was one command away.

**AND THE FIRST WRITING OF THIS RULE GOT ITS OWN ENUMERATION WRONG** (follow-up round, Codex; Grok
checked the count and passed it). It listed the third instance as the `runBootReconcile` kdoc. That
kdoc was corrected in the same commit, but for a DIFFERENT fact — "production passes
`Dispatchers.IO`" — and it never carried the containment claim at all. `git show bdde066 --
ZitroneApp.kt` is a single hunk touching only the dispatcher sentence; source settles it. The count
of three was right by accident, over the wrong set. **So the rule needs its second half: enumerate by
GREPPING FOR THE FACT, then verify each hit actually asserts that fact — a correction landing in the
same commit is not evidence it is the same claim.** Adjacent-and-also-fixed is the trap.

**LINEAGE — same shape as the mutation-header incident above: knowing the pattern did not prevent
producing it.** Both times the person writing the correction had just articulated the rule. That is
the signal a rule is not enough — the remedy has to be a step in the close-out (`grep`, count, state
the count), not an intention to be careful.

### THE AFFIRMATIVE CASE FOR RE-DERIVING — a stale claim hid a real capability (W-B, C4)
Every other entry here records re-derivation catching an OVERCLAIM. This one records it recovering
something. The Pucker Burn invariant table listed residual **R1** — a crash between the keys-first
unlinks leaves an image whose DEK is gone, visibly damaged rather than cleanly reset — and accepted
it as **"unavoidable without a durable pre-burn intent marker"**, with the marker ruled out because
it would be exactly the discoverable armed/in-progress artifact the design forbids. Sound reasoning,
stated confidently, and **false**.

Re-deriving it against source found `completeInterruptedBurn()` already built on the parent branch,
resolving R1 with **no marker at all**: it keys on `{vault.bin PRESENT, vault.dek PROVEN absent}`, a
signature `create()` structurally cannot produce, because create renames the DEK envelope into place
FIRST and the image SECOND — a partial create is the exact INVERSE. No ordering in the codebase
produces that state except an interrupted keys-first obliteration or genuine DEK media loss, and both
are unrecoverable, so completing the wipe destroys nothing still readable.

**The lesson: re-derivation is not just an overclaim filter.** A residual accepted as unavoidable is
a claim like any other, and "we couldn't do better" ages exactly as badly as "this is safe". When a
doc records something as impossible, the cost of re-checking is one derivation and the payoff can be
a capability the project already paid for and then forgot it had. Do NOT treat the residuals section
of a design doc as settled just because the defects section has been reviewed.

### THE NON-DISCRIMINATING ASSERTION — satisfied by BOTH the correct and the broken behaviour (6 occurrences + a 7th and 8th cluster, 0.10.0 U1)
Distinct from a vacuous test (asserts nothing) and from a stand-in test (asserts against a copy of the
logic). This one asserts something REAL about something REAL — it just cannot tell the two apart, so
it passes against the very defect it exists to catch.

**Six occurrences in one unit** — and note where the last two were found: inside the FIX for this
class, and inside the gate written to enforce it. Naming a class does not close it.
1. `assertFalse(store.reconcileOrphanedBurnMarkers())` on a non-durable reconcile. `false` was exactly
   what the BROKEN code returned — the Boolean conflated "did not fire" with "mutated, not durable".
   The assertion passed against the bug. Now asserts `MUTATED_NOT_DURABLE` specifically.
2. The gate's negative test asserted only `fresh != burnedWithResidue`. That held anyway because of an
   unrelated defect (the device-key alias surviving every burn), so it could not distinguish "caught
   my planted artifact" from "caught someone else's residue". Now names its artifact.
3. `bootRoute` composed routing: "held + provably clean → LOCKED" alone would pass if ONBOARDING were
   unreachable for any unrelated reason. Needed its second half — the same disk WITHOUT the doubt →
   ONBOARDING — to prove the hold was the discriminator.
4. **The gate's own positive comparison** (round 2, both lenses, found INSIDE the commit that fixed
   occurrence 2). `assertEquals(fresh.prefs, burned.prefs)` is a real assertion over real state — and
   it was satisfied by a burn that wipes preferences AND by one that does not, because the scenario
   provisioned via `imageStore.create()` and never created preference residue at all. Same for
   databases, caches and diagnostics. **The assertion was strong and the SCENARIO was empty**, which
   is the form this class takes at the harness level: content hashing fixed how faithfully the gate
   compared, and changed nothing about whether there was anything to compare. Fixed by provisioning
   through the production create/publish path and asserting each seeded artifact PRESENT by name
   before the burn.
5. `burn_requires_the_biometric_wipe_to_succeed` — "no biometric alias remains" asserted after a
   scenario that never enabled biometrics. No alias existed, so the assertion held for a burn that
   consumes `wipeBiometricMaterial()`'s boolean, for one that ignores it, and for a wipe that is a
   successful no-op. **The test named the defect in its own title and could not discriminate against
   it.** Fixed by planting a real alias with production's prefix and asserting it present first.

6. **The gate's notification domain** (round 5, both lenses) — added to the snapshot, the baseline
   AND the post-burn comparison, and never SEEDED. `fresh.activeNotifications` and
   `burned.activeNotifications` were both empty on every run, so the comparison passed and a burn
   with the cancel step deleted would have passed identically. **Committed inside the fix for the
   notification finding itself** — the domain was added because a reviewer found active
   notifications surviving a burn, and the fix for that finding shipped without the seed that would
   prove it. Scenario-level form of the class, third time.

**THE DETECTION RULE, mechanical: for every assertion, ask what WRONG implementation would also
satisfy it. If the answer includes the one this test exists to catch, the assertion is too weak.**
Occurrences 4 and 5 add the SCENARIO-level form of the same question, which the assertion-level one
misses entirely: **ask what the test actually CREATED before it compared.** An assertion cannot
discriminate over state the scenario never produced, so a strong assertion over an empty scenario
reads in review exactly like proof. For any gate, list the domains it claims and name the artifact it
seeded in each; a domain with no named seed is not being tested, however rigorous the comparison
looks.
Applies especially to `assertFalse`/`assertNotEquals`/`!= null`: a negative assertion is satisfied by
enormous numbers of wrong states, so it must name WHICH wrong state it rejects, or pair with a
positive assertion that fails when the discriminator is removed.

**7th cluster — 0.10.0 U1 decoy provisioning, review round 1 (both lenses, four tests at once).**
The scenario-level form again, with one new shape worth naming: **a test that asserts the property
against the wrong OBSERVABLE.** Four cases, all fixed in fix-round 1:
- *restart-skips-counters* rebuilt `DecoyState(counterHighWater = <the live value>)` in RAM and
  opened a new session over it. It read the value out of the very state whose durability it claimed
  to test, so it passed against a reservation that never reached disk. **Reading the live
  `VaultState` after a `mutate` proves scheduling, never durability** — the whole P1 hid in that
  gap. Now every durability assertion in the unit decodes the SEALED PAYLOAD the persist sink was
  handed.
- *decode-failure wipe* asserted only the throw, and its own comment conceded the wipe was "read in
  review". Correct diagnosis, wrong response: the fix is not a weaker comment, it is to make the
  cleanup a function a test can call on arrays it owns, and then to say plainly which single step
  (the call from the catch) is still unobserved.
- *"commits the whole set at once"* injected no fault between mutates, so it passed for a two-mutate
  commit. Fixed by flushing every mutation (zero coalescing ceiling) and decoding EVERY generation
  the sink was handed.
- *"worst-case" budget* was a realistic measurement wearing an adversarial name. Renamed; the
  measurement was fine.

**THE ADDITIONAL RULE: name the observable, not just the assertion.** Before asserting that
something was recorded, ask *where the value being read comes from* — if it comes from the same
in-memory object the code just wrote, the assertion cannot distinguish "wrote it" from "made it
survive". And the counterpart, applied here: **if a property genuinely cannot be observed from a
test, say so in the test rather than leaving an assertion whose name implies coverage.**

**PROCESS, and it is what caught these:** every replacement test in the U1 fix round was run against
a deliberately broken implementation and observed to FAIL before the fix was restored — the mutation
list is recorded in `reviews/decoy-0.10.0/u1-invariant-table.md`. Two tests survived their mutation
and were re-labelled rather than left implying more than they prove.

**8th cluster — 0.10.0 U1 round 2, and the mutation process caught it INSIDE the round that was
already applying it.** Two of the ten round-2 mutations *passed*, i.e. the new test did not
discriminate, and both for the same reason: **a second, independent guard was doing the work.**

- The "unrelated capacity overflow must not re-register" test passed under its mutation because the
  ONE-ATTEMPT LATCH had already been burned by the same provisioner instance. Fixed by using a fresh
  instance — which is also the real scenario (a later session).
- It then passed again because the WRITE-AHEAD BACK-OFF independently refused to register while the
  overflow was outstanding. The predicate defect is only observable in the narrow window where
  `capacityExceeded` is set *and* the state would now encode. The test now constructs that window.

**THE RULE THIS ADDS: a mutation that does not fail is not proof the property holds — it is a
question about which guard is load-bearing.** When redundant defences overlap, a test aimed at one of
them silently measures the other. Do not conclude "already correct"; find the scenario in which only
the guard under test can save you, or state plainly that the test does not distinguish them (as
`interleaved use never regresses` does).

**And the round-2 meta-finding, which is a design lesson rather than a test one:** all three guards
added in U1 fix round 1 became fix round-2 defects, because each reasoned about durable state sampled
OUTSIDE the lock protecting it, or folded two questions into one predicate. That is the "when a fix
keeps spawning edge cases, the APPROACH is wrong" rule above, seen one round earlier than 0.9.2 PR-3
saw it. Round 2 replaced the three guards with three structures (one section lock, a split predicate,
a write-ahead back-off) instead of adding a fourth guard.

### GOOD HANDLING — demonstrate why a concern is latent; never assert a property the test cannot prove
Grok's round-4 INFO-3 said `runCatching { afterPublish() }` swallows `CancellationException` while the
sweep path deliberately rethrows. Rather than "fix" the asymmetry or wave the label away, the test
was written to answer whether it was live: `afterPublish` is `() -> Unit`, not `suspend`, so it has no
suspension point at which a real cancellation could ever reach it — the only CE it can raise is one it
constructs itself; and the `runCatching` sits INSIDE `withContext`, which rechecks its job on exit, so
a genuine cancellation still propagates. Latent, not live, and the reasoning is executable and will
fail loudly if `afterPublish` ever becomes suspending. **Characterisation, honestly labelled, beats a
false coverage claim.** Pairs with the rule above: the same test carries `MUTATION UNIQUELY CAUGHT:
NONE` because the mutation was run and survived.

### THE CLAIM THAT WAS BORN WRONG — false at authorship, not drifted into falsehood (W-B round 4)
Every other prose defect in this project was a STALE claim: true when written, falsified later by a
change nobody propagated. This one was **false the day it was committed**, in the commit that shipped
the thing it described — process death — while the entire unit was about false confident prose.

The claim: *"killed BEFORE lowerHold → the disk reconcilers re-derive the doubt at next boot → lock
screen. Fail-closed… There is no interruption point at which process death produces a fresh-install
presentation over an unproven wipe."* Written into `runBurnWipe`'s kdoc, `SECURITY_MODEL.md` and the
commit message simultaneously. **Both round-4 lenses independently derived from the same source lines
that it is false** for the failed-but-clean shape: once `burnObliterate()` succeeds, every reconciler
trigger (`completeInterruptedBurn` needs `vault.bin` PRESENT; `reconcileOrphanedBurnMarkers` needs a
marker; the sweep needs image-bearing residue) is gone, so a later cleanup failure plus process death
publishes `durabilityHold=false` over surviving residue.

**WHY THIS IS A DIFFERENT DETECTION PROBLEM, and the reason it gets its own entry.** The project's
existing defence is re-derivation against source when something changes. That defence is structurally
blind here: **re-deriving a born-wrong claim confirms it still says what it said.** The question
re-derivation asks is "has this drifted?", and the answer is correctly "no" — it has been false and
unchanged since birth. A claim can pass every future staleness audit and never once have been true.

**THE RULE: a claim introduced WITH a change must be proven on the same terms as the change — not
merely reviewed later for staleness.** If the change ships with a test, the claim needs the test. If
the claim is about behaviour under interruption, enumerate the interruption points and say which are
covered and which are not, in the same commit. "I reasoned it through while writing it" is exactly
the evidence that failed here, twice in one commit (this, and the "deterministic drain" claim about
`killProcess`, which prevents FUTURE userspace work but cannot roll back a write already submitted by
a thread already running).

Corollary for reviewers: **new prose is not lower-risk than new code.** Attack a claim's first
appearance hardest, not its tenth.

### PROCESS FIX (BINDING) — a fix recorded only in commit history is NOT recorded (CX23 P1/P2, 2026-07-27)

**What happened.** The CX23 relay findings P1 (port 8443 publicly reachable, plaintext, full API)
and P2 (`registerLimit` 5/hr on a collapsed global bucket) were both fixed on 2026-07-26 by
`20ade12b`. The commit message was *exemplary* — it explained the change, the reasoning, why the
interim was not a fix, and it even answered the open Caddyfile question that P2 was blocked on.
None of that reached anything a future session reads. The commit sat on
`origin/cx23/urgent-8443-and-ratelimit-interim`, unmerged; `todos.md` still carried both findings
unchecked with the old 5/hr figure; and `main` still carried both defects in source.

**The damage was not hypothetical.** The next session read `todos.md`, took 5/hr as current, and
built a release budget for 0.10.0 decoy traffic on it — reasoning about registration capacity that
was wrong by 60×. It was caught only because the maintainer knew the real number and said so. Worse,
because `main` never received the fix, **any redeploy built from main would have silently reverted
a live security fix**, reopening the full API over plaintext HTTP past Caddy and TLS.

**Three distinct failures, each independently sufficient:**
1. The ledger entry that tracks a finding was not updated when the finding was closed.
2. The fix was left on an unmerged branch, so the source of truth and production disagreed about
   security posture — which is itself the defect, independent of either state being "right".
3. A genuinely valuable finding (Caddy appends rather than overwrites `X-Forwarded-For`, so
   `ProxyHeader` is unsafe) was recorded **only** in prose in a commit message. It unblocked P2's
   real fix and nobody downstream could have known.

**BINDING RULE.** Closing a finding means updating the record that tracks it **in the same
session** as the artifact that fixes it. A commit message, a PR description, and a branch name are
not the record — they are invisible to every future reader who starts from `.l00prite/`. Concretely:
- Landing a fix ⇒ tick/annotate its `todos.md` entry with the commit SHA and deployment status in
  the same session. An untouched checkbox asserts "still broken" to everyone who reads it.
- A fix that is deployed but unmerged ⇒ **say so explicitly and loudly**, because that state is a
  live regression risk on the next redeploy, not a bookkeeping nicety.
- A finding discovered *while* fixing something else ⇒ it goes in the ledger, not only the commit
  message. If it changes what a future decision may assume, commit prose is the wrong home for it.
- Deployment state that cannot be verified from here (CX23 has no SSH from CX33) ⇒ record it as
  **taken on report**, naming who reported it. Do not launder a report into a verification.

**Why this one stings.** The ledger-cadence rule had been added one day earlier. The rule did not
fail — the follow-through did. A process that is written down but not executed produces *more*
confident wrong answers than having no process at all, because the next reader trusts the record.

## 2026-07-27 — "match the guard's scope to the resource's scope" recurred THREE more times in one unit

**What happened.** 0.10.0 U1 review round 3 confirmed ten findings. Three of the four P2s were the
same defect: a guard whose scope was narrower than the resource it guards.

- the one-attempt registration latch was an INSTANCE field guarding a RUNTIME resource (this vault's
  one synthetic account, and the worldwide rate-limit bucket it may spend from once) — two
  provisioners over one runtime each held their own and both registered;
- the "this commit was never confirmed durable" memory was an INSTANCE field guarding the same
  runtime's state — a second provisioner answered "ready" on credentials whose flush had thrown;
- `refreshTokens` guarded its write with a snapshot taken BEFORE a multi-second network round-trip,
  so a `clearAccount` in that window was undone by the response.

**Why it stings.** This rule is already in this file, from 0.9.2 PR-3. Fix round 1 of this very unit
applied it correctly to `DecoyCounterReservation` — private constructor, `forRuntime` registry — and
wrote down the reason: *kdoc-only uniqueness is not a defence.* The sibling class with the identical
problem was left on a public constructor, and the token path with the identical shape was left
alone. **Applying a rule where a reviewer pointed is not the same as applying it where it holds.**

**BINDING RULE.** When a finding is fixed by changing a guard's SCOPE, the same session must
enumerate every other guard over the same resource and state, per guard, whether it needs the same
change. Not "does this look similar" — *what resource does this guard protect, and what is that
resource's lifetime?* Two answers that disagree is the defect, before any interleaving is imagined.

**Second, smaller lesson from the same round.** Tightening a guard's scope can silently destroy a
test's discriminating power: once the latch became runtime-scoped, four tests that modelled "a later
session" as a fresh provisioner over the same live runtime were being carried by the shared burned
latch rather than by the property they named. That was found by RUNNING the mutations, not by
reading the tests. A scope change is also a test-fidelity change.

### THE INVALIDATED-FROM-UNDERNEATH CLAIM — true when written, made false by a later fix (U1, 0.10.0)

**The mirror image of "the claim that was born wrong", and it needs naming separately because the
detection rule is different.** The maintainer narrowed the §4.1 storage-format disclosure to "a vault
that has never **generated cover traffic** still opens on 0.9.x". That was **true when written**: the
codec omits `TAG_DECOY` when the section is empty, so a vault with no cover traffic carried no tag.

Two rounds later, a fix for an unrelated finding (the capacity back-off, G4) started writing a
durable deferral-only section **before any relay contact**. Nothing about the doc changed. Nothing
about the codec's omit-when-empty logic changed. The disclosure simply **became false underneath**,
because its truth had always depended on *when the provisioner first writes to the section* — an
implementation detail three layers below the sentence.

**A disclosure whose truth depends on an implementation detail is fragile by construction.** The
born-wrong class is caught by attacking a claim's first appearance hardest. This class is invisible
to that: the claim was attacked, and it passed, because it was correct. It has no first appearance to
re-attack.

**DETECTION RULE (binding): when a fix changes WHAT gets written to durable state, or WHEN, re-check
every doc claim whose truth depends on that behaviour.** Not just the docs the diff touches — the
diff here touched no docs at all. Ask: "which written promises would become false if this write
happened earlier, later, or in a case it previously did not?"

**What actually caught it:** both blind reviewers, independently, in the same round. Nothing
mechanical would have — no test asserted the disclosure's trigger condition, and no doc test exists.
That is a direct argument for keeping documentation and user-facing claims **in review scope**, not
just code. Corollary already recorded elsewhere and reinforced here: an **overstated** disclosure is
its own dishonesty, and an **understated** one is worse; a claim invalidated from underneath will
usually fail in the understating direction, which is the more dangerous one.

### Guard scope vs resource scope — third recurrence, and the first with a SECURITY consequence (U1)

Recorded already from 0.9.2 PR-3 ("match the guard's scope to the resource's scope"). U1 hit it three
times in one unit — the counter allocator (round 1), the provisioner's latch and flush flag (round
3), and the token-refresh path (round 3). **Name the token-refresh one specifically, because it is
the sharpest and it is not a robustness bug:**

> `refreshTokens()` snapshots the account identity and refresh token, **blocks on the relay for
> seconds**, then writes the response back. A concurrent `clearAccount()` in that window wipes the
> account — and the arriving response then **resurrects valid bearer credentials for the cleared
> account**. The access token works until expiry; the refresh token mints new sessions. The section
> lock protected each individual write and not the read→network→write **sequence**, which is the
> whole shape of the defect class.

The other two cost a wasted global registration and a readiness lie. This one hands working
credentials back to an account the user asked to be gone.

**Reinforced rule:** fix every instance of a recognized shape with the **same** pattern. U1 fixed
three sites with one registry pattern rather than inventing a third — a third pattern is what makes
the *fourth* site inconsistent later, and inconsistency is what lets the fifth site be wrong without
looking wrong.

### CALIBRATION — what reviewer convergence means, and what it does not

Recorded so future rounds are read correctly. U1's paired-blind arc:

| Round | P1s | Reviewer agreement |
|---|---|---|
| 1 | 2 | **fully disjoint** — Codex found the durability defect, Grok explicitly listed it as a non-finding; Grok found the capacity/re-registration defects, Codex missed them |
| 2 | 1 | 2 of 11 convergent |
| 3 | 0 | **top 3 found independently by both** |

**Reviewers converging is the surface stabilising, not the reviewers tiring.** The distinction
matters and is easy to get backwards. Read it this way:

- **Disjoint findings early** = the surface is large and unexplored. It is also the strongest
  possible argument for running two blind reviewers instead of one: at round 1 a single reviewer
  would have shipped a real P1 **whichever one you picked**.
- **Convergence later, with P1s falling to zero** = the remaining surface is small enough that two
  independent searches hit the same things. That is evidence of exhaustion.
- **Convergence with findings still rising, or with severity flat** would mean the opposite — the
  reviewers are anchoring on the same salient area and missing the rest. Check for that before
  reading agreement as good news.

One reviewer being *wrong* is also data, not noise: Grok's round-1 "durable advance before spend" was
a **false negative on a P1**, resolved against source. A reviewer asserting a property *holds* is a
claim like any other and gets verified like any other.

## An argument list is not "after" the statement above it (0.10.0 U1, review round 4)

`registrationSpent = true` sat one line above
`relay.register(DecoyIdentity.generateBundle(identity), powProof)`. **Kotlin evaluates the argument
after the preceding statement**, so a guard whose entire meaning was "the relay may now have created
an account" was already true while 101 local keypairs were being generated. Reading top to bottom it
looks correct; the failing step is *visually inside* the call it is supposed to follow.

**The rule:** when a flag's meaning is "everything after this point may have side effects", nothing
that can fail may hide in the guarded call's argument list. Hoist it to its own statement, above the
flag, where the reader can see which side of the boundary it is on.

**And the reason no test caught it in three rounds: the failure was not injectable.** The relay fake
could only throw once `register()` was entered, so no mutation of that line was even expressible.
When a boundary is load-bearing, check that both sides of it can be made to fail in a test — an
untestable step next to a guard is an untested step. The fix added a factory seam for exactly that.

## A doc that drifts in BOTH directions is being edited from itself, not derived from the code

0.10.0 U1's §4.1 format-break disclosure moved three times: "generated cover traffic" (false once the
back-off was written ahead of relay contact) → "the first time it sends any" (**understated** — a
vault that registers and never sends still carries the tag) → the proposed "the first time it *tries
to* send any" (**overstated** — a vault that fails offline before `register` retires its deferral and
keeps its 0.9.x readability). Two consecutive corrections in opposite directions, and the architect
caught the second one only in adjudication.

**The cause was the same each time: each pass reasoned from the previous wording rather than from the
code.** A sentence whose truth depends on an implementation detail cannot be edited incrementally. It
has to be re-derived from an enumeration of the actual paths — which now lives in the codec's kdoc,
next to the branch that produces the behaviour, with that instruction attached to it.

This is also the fourth recurrence of the stale-contract class recorded above. Round 4 of that unit
was **three of five findings in documentation and two in code**: once the code stabilises under
repeated review, the prose describing it becomes the defect surface, and it is not exercised by any
test. Sweep every contract describing a changed behaviour, not only the lines a reviewer cited.

### ADJUDICATION LOSS — a multi-part finding compressed into one row loses the parts (U1 round 4)

**The adjudicator is a lossy stage between the reviewers and the fix, and this is the first recorded
instance of it dropping a real defect.**

Grok's round-4 Finding 4 had **three** parts. The architect's adjudication compressed it into one
table row (J5) carrying two of them, and the third was lost: *the invariant table still described
`credentialsUnconfirmed` as instance-scoped* after round 3 had moved it into the per-runtime `Gate`.
That is not a wording nit — a reader working from the table alone rebuilds the exact
second-provisioner readiness lie round 3 existed to close. It was recovered only because the
implementer read the raw reviews alongside the adjudication and noticed the shortfall.

**Why it happened:** the adjudication format is one row per finding, which silently pressures
multi-part findings into their most quotable part. Severity survives; enumeration does not.

**RULES (binding):**
1. **A multi-part finding gets one adjudication row per part**, or an explicit sub-list. Never one
   row for "Finding N" when Finding N contains an enumerated set.
2. **The fix brief must instruct the implementer to read the raw reviews**, not only the
   adjudication. It did here, which is the only reason this was caught — keep that instruction.
3. **Treat the implementer as a check on the adjudicator**, not merely a consumer of it. The
   pipeline reviewer → adjudicator → implementer has three stages and the middle one was, until
   now, the only unreviewed link.

Related but distinct from the "verify bot claims before acting" rule: that guards against accepting
a reviewer's *wrong* finding. This guards against losing a reviewer's *right* one.

### THE SUMMARY THAT OUTLIVED THE CORRECTION — fix the restatements, not just the cited line (U1 round 5)

**The single most instructive finding of the U1 arc, because of what survived and where.**

Round 1's headline P1 was the misconception that `VaultRuntime.mutate` is durable (it schedules; only
`flushBeforeAck` persists). It was fixed in code, and the invariant table's detailed W3/R2 rows were
corrected to match. **Four fix rounds later, round 5 found the misconception still stated verbatim in
the same document's abstract summary block** — "only on a successful *mutate* do the RAM `next`/`limit`
advance" — under a heading a reader is *more* likely to consult than the detailed row.

The correction had been applied exactly where the reviewer pointed, and nowhere else.

**Why summaries are the surviving copy:** a reviewer cites the line that produces the defect, which is
always the detailed one. Fixes get applied at the citation. Abstract restatements — summaries,
overviews, "in short" paragraphs, kdoc one-liners, README bullets — restate the same claim in
compressed form and are never cited, because no code path passes through them. They are the highest-
leverage place for a stale claim to survive, since they are what a hurried reader reads *instead of*
the detail.

**RULE (binding): when a misconception is corrected, grep for every restatement of it — especially
the compressed, abstract, and summary ones — and correct them in the same change.** Ask "where else
is this same claim said in fewer words?" A detailed row and its summary are two writers of one
contract; the WRITER/READER discipline applies to prose as much as to durable state.

Related: this is the fifth recurrence of the stale-contract class in this unit alone (G1 doc claims,
J3/J4/J5, K1/K2/K3). By round 5 **every remaining finding in the unit was prose lagging code, with
zero code defects at any severity** — the documentation surface outlived the implementation surface
by two full rounds. Budget review attention accordingly on future units: docs are not the cheap part.

### THE TWO-BLIND-REVIEWER RULE PAYING FOR ITS WHOLE COST IN ONE DATA POINT (U1 round 1)

Keep this one. It is the single cleanest justification the practice has produced.

**At round 1, a single reviewer would have shipped a real P1 — whichever one you picked.**

- **Codex** found that `VaultRuntime.mutate` only *schedules* a reseal, so the counter reservation
  spent values whose high-water mark might never reach disk — a wire-visible counter regression, the
  exact fingerprint the mechanism exists to prevent.
- **Grok explicitly certified that same property sound**, listing "durable advance before spend" as a
  non-finding and marking the counter invariant as *Holds*. A false negative on a P1.
- **Grok** found that `isProvisioned()` never consulted `capacityExceeded`, so a near-capacity vault
  registered a **new relay account on every unlock** against a single global bucket shared worldwide.
- **Codex missed that one entirely.**

Neither reviewer alone was sufficient, and the failure was not that one was weaker — they were
*differently* wrong. Corollary already recorded and reinforced here: **a reviewer asserting a
property HOLDS is a claim like any other and gets verified against source like any other.** Grok's
non-finding was resolved against `VaultRuntime.kt`'s own "no I/O here" comment, not adjudicated by
reputation.

### BUDGET FOR THE DOC SURFACE — it outlives the code surface (U1, measured)

From round 5 onward, U1 had **zero code defects at any severity from either blind reviewer**, and
review rounds still produced findings: prose lagging behaviour, every time. The documentation surface
**outlived the implementation surface by two full rounds.**

Plan for this on the next unit rather than rediscovering it. Concretely: treat contracts, kdoc,
spec sections and invariant tables as first-class review scope from round 1, not as a tidy-up at the
end. Findings by round, for calibration: 10 → 11 → 10 → 6 → 3 → 3, with P1s 2 → 1 → 0 → 0 → 0 → 0 and
**every finding from round 5 on being prose.**

### A SWEEP THAT GREPS THE RULE'S OWN WORDING MISSES THE PARAPHRASES (U1 round 6)

The sharpest form of the grep-every-restatement rule, and it was learned by the rule's own author
under-applying it **in the same commit that recorded it**.

Round 5 recorded: *when a misconception is corrected, grep for every restatement, especially the
compressed ones.* Round 6 then found **two more surviving restatements** of exactly the claims round
5 had corrected:

- the `VaultState` codec kdoc's four-row list and a spec summary note, both still asserting the
  trigger is "registration" with no crash row — the correction had landed in the two tables the
  reviewer cited and skipped the parallel prose;
- `DecoyRelayApi`'s kdoc saying credentials commit in "one **durable mutate**" — round 1's headline
  misconception, alive in source through **five** fix rounds, because no reviewer cited that file
  until the final round.

**The refinement: a sweep that greps the rule's own wording will miss restatements that paraphrase
it.** Searching for "mutate" finds the literal copies; it does not find "committed durably", "written
to disk", "persisted in one step", or a four-row table that simply omits a row. **Sweep by CLAIM, not
by phrasing** — enumerate what the corrected claim asserts, then find every place that asserts the
same thing in any words, including tables whose *omissions* carry the claim implicitly.

And the meta-lesson, worth more than the rule: **writing a rule down does not confer the discipline
to follow it.** The author of the round-5 rule violated it in the act of recording it. Rules need a
mechanical check, not just a statement.

### ⭐ DESIGN RULE — random bytes match real bytes ONLY where the real distribution is uniform

**Promoted from lesson to design rule: three independent instances in one feature.** Whenever a
synthetic value stands in for a real one, **name the real field's distribution before assuming
random matches it.** In a structured protocol envelope almost nothing is uniform:

- **keys have curve constraints** — Curve25519 public encodings have bit 255 clear;
- **counters have magnitude** — and magnitude changes both varint width and JSON digit width;
- **lengths have encoding artifacts** — base64 padding leaks the pre-encoding byte count;
- **any field with a default value is a constant in practice**, so "varying" it is the tell.

The three instances, all in 0.10.0 decoy traffic, all in different fields:

| # | Field | What "random" got wrong |
|---|---|---|
| 1 | ciphertext **length** | A 316 B blob base64s to 424 chars ending `==`; the real 323 B gives 432 ending `=`. Every decoy would have carried a padding signature no real message has. |
| 2 | `previous_chain_length` | Real traffic is **always 0** (Android hardcodes it, iOS never mutates it). Anything else is the tell; matching it is correct, not lazy. |
| 3 | **key material** | `0x05 ‖ random(32)` is not a valid Curve25519 encoding. **Measured: 0 of 200 real `Curve.generateKeyPair()` publics had bit 255 set; random bytes set it ~50% of the time.** ~50% of subsequent decoys and ≥75% of first envelopes would have carried an impossible encoding. |

**Instance 3 is the sharpest because it is a COUNT, not an argument.** 0/200 versus ~50% is not a
judgement call, and the fix — generate a real keypair and discard the private half — is canonical by
construction and costs nothing. Prefer *generating the real thing and throwing away what you don't
need* over *fabricating something shaped like it*; the former cannot drift out of the real
distribution because it never left it.

Corollary on where these were caught: **the structural diff excluded the 32 key bytes**, which is why
no test saw instance 3. A test that excludes a region is asserting nothing about it — excluded
regions are exactly where this class hides.

### ⭐ PRINCIPLE — when an unobservable property conflicts with an observable one, the observable wins

Derived while ruling on the `message_number` digit-width problem (a JSON number, so `5` vs `128`
changes frame length by 2 bytes, while the decoy's own counter must stay monotonic and so cannot be
freely chosen). The resolution was to absorb the difference in the random ciphertext's length, which
looks wrong until the observability is spelled out:

- A **network observer** — the adversary this feature defends against — sees only the **total TLS
  frame length**. It cannot see the split between `ciphertext` and the other JSON fields.
- So *"the ciphertext length is plausible for this counter"* is **unobservable** to that adversary,
  while *"the total frame length matches its pair"* is **directly observable**.

**Optimise the property the adversary can actually measure.** Preserving an internal consistency
nobody can check, at the cost of an external one everybody can, is backwards.

**And state who it does not fool.** The *relay* can see the split and could notice a ciphertext
length implausible for its counter. That is acceptable — §1 already concedes decoys do not defend
against the relay, for far more fundamental reasons — but it is written into §2.4 next to the
control-channel gap rather than left implicit. A mitigation that is honest about who it doesn't fool
is the only kind worth shipping.

### CALIBRATION CORRECTION — a "Holds" is the absence of a finding, not the presence of a proof

Grok has now twice certified sound a property Codex correctly flagged as **P1** (U1 round 1,
"durable advance before spend"; U2 round 1, "byte-level shape — Holds", missing the invalid-key
defect entirely).

**Do not conclude "Grok is the weaker reviewer."** In that same U2 round, Grok found the
`message_number` **digit-width** distinguisher that neither Codex nor the architect saw — a real P2
that breaks size-mirroring independently of the shape defect. **Different blind spots, not different
quality.**

The correct and more useful conclusion: **a reviewer's "Holds" is the absence of a finding, not the
presence of a proof.** It carries no evidentiary weight and must never be recorded as one. Only a
*positive* claim can be verified against source — and those get verified regardless of which
reviewer makes them. This is the same rule as "verify bot claims before acting", applied to the
negative case, which is the easier one to let slide precisely because it asks nothing of you.

### WHERE THE DEFECTS ARE COMING FROM — the spec, two units running

Worth acting on rather than just noting. Across U1 and U2, the most severe findings traced to the
**specification**, not the implementation:

- U1: `mutate` treated as durable (the spec said "persisted"); R4 readiness conflating a *send*
  predicate with a *register* predicate.
- U2: **both P1s** — the `build(blockCount)` interface that cannot express what mirroring requires,
  and `0x05 ‖ random(32)`, which was a literal architect instruction.

The implementer has now caught bad architect instructions **three times**, each correctly. So:

1. **Review the spec with the same adversarial energy as the code**, and from round 1 — not as
   context for reviewing the code, but as the artefact most likely to be wrong.
2. **Keep telling the implementer to report where the spec is wrong**, and treat those reports as
   findings rather than as friction.
3. When a defect is found, **ask which artefact it originated in** before fixing it. Fixing a
   spec-origin defect only in the code leaves the spec to reproduce it in the next unit.
### A FIELD THAT CANNOT CHANGE THE LENGTH AND IS NOT EXPOSED BY THE PARSER IS INVISIBLE TO BOTH KINDS OF TEST (0.10.0 U2)

U2's gate is byte-level indistinguishability, and it was tested three ways: frame/ciphertext LENGTH
against real libsignal output, envelope field SHAPE, and PARSE-BACK through libsignal's own
constructors. Sixteen deliberate mutations were run against that suite. Fifteen failed. **One passed,
and the usual answer — "another guard was carrying it" — was wrong. Nothing was carrying it.**

The mutation set the protobuf's `previous_counter` to 1 instead of the measured 0. It is a one-byte
varint at either value, so **no length test can see it**; and libsignal's Java `SignalMessage`
exposes `getCounter()` but **not** `getPreviousCounter()`, so **no parse-back test can reach it**.
Length tests and parse tests together felt like belt and braces and had one shared blind spot: a
field that is neither length-bearing nor accessor-exposed.

**The rule:** when the property under test is "these bytes are indistinguishable from those bytes",
the test must eventually BE a byte comparison. Reach for a structural diff against real output — with
the genuinely-random regions derived from the layout rather than hand-listed — instead of adding a
third assertion of the same two kinds. One such test replaced the entire class of miss: the same
diff also catches a wrong version byte and a wrong field ORDER, neither of which had been thought of.

**Corollary that fired immediately:** such a diff needs a guard that its "fixed" set is not empty, and
that guard must be set from the actual structure. A subsequent `SignalMessage` has exactly **eleven**
structural bytes; a round-number threshold of 40 would have silently passed a vacuous comparison on
the smaller of the two shapes. The guard fired on the first run, which is the only reason the number
is right.

### A MUTATION HARNESS THAT LEAVES MUTATED ARTIFACTS BEHIND INVENTS A DEFECT FOR THE NEXT RUN (0.10.0 U2)

The harness patched a source file, ran the suite, and restored the file in a `finally`. It never ran
the build again after the final revert. So the compiled classes left on disk were the **last
mutation's**, and the next full-suite invocation's up-to-date check did not rebuild them.

The result was a single full-suite failure whose signature exactly matched that last mutation —
presenting as a **flaky test in the production code**. It reproduced zero times in isolation and zero
times in a 400-iteration determinism stress of the component; a clean `--rerun-tasks` run was green.

**The lesson is not "it was flaky", it is that the tooling manufactured the evidence.** The natural
response to a one-off failure in a concurrency-adjacent unit is to hunt a race, and that hunt would
have cost more than the whole mutation sweep saved. **Any harness that mutates source must force a
rebuild after its final revert, or run one throwaway build before the evidence run.** And when a
failure signature matches a mutation you were just running, suspect the harness before the code.

### ⭐ A CORRECTION NOTE IS ITSELF A RESTATEMENT, AND IT ROTS LIKE ANY OTHER (U2 R3 — 10th recurrence)

**The sharpest form of the parallel-copy class, and the one that explains why nine previous fixes
did not stop it.**

Round 1 of U2 found that `0x05 ‖ random(32)` is not a valid Curve25519 encoding — a P1 that would
have marked half of all decoys. The code was fixed to generate a real keypair. **Round 3 found the
spec still carrying `**U2 must emit 0x05 ‖ random(32)**` as a live binding instruction — inside the
very correction block written to fix that defect.**

The block was authored to say "here is what was wrong and here is the right rule". It stated the
wrong rule as the right one, and then survived two more rounds, because a correction note reads as
*already fixed* and nobody re-attacks it.

**Why this class kept recurring despite nine prior fixes:** every fix targeted a *description of
behaviour*. A correction note is a description of a description — it quotes the old claim and asserts
a new one — so it is a parallel copy **by construction**, and it is the copy least likely to be
re-read, because its heading announces the problem as solved.

**RULES (binding):**
1. **A correction note is in scope for every subsequent review.** It is not settled ground. Its
   heading is a claim about its own currency, and that claim rots.
2. **A correction note must not carry a binding instruction.** State what was wrong and why; point
   at the canonical artefact for what is right. `U2 must emit X` inside a correction is a second
   source of truth wearing the clothes of a fix.
3. **Prefer designating a canonical artefact over restating the rule.** This worked for the
   `TAG_DECOY` trigger and for `DecoyState`'s field set. Applied here: `DecoyEnvelopeBuilder` is
   canonical for construction; the spec describes intent and binds nothing.

**And the general lesson, which outranks the specific one:** *a spec that tells the implementer HOW
to construct something is a second implementation.* Across U1 and U2 **every P1 traced to the spec** —
`mutate` treated as durable, `build(blockCount)`, and `0x05 ‖ random(32)`. Each was a construction
instruction the spec had no business giving. Specs should state observable **requirements**
("indistinguishable from a real envelope of the same shape") and let the implementation own the
construction, because the implementation is testable against reality and prose is not.

### ⭐⭐ THE COSTLIEST ERROR OF THE 0.10.0 ARC — "absolute" on a requirement about OUTCOMES

**Seven review rounds and four independent lenses ground correctly against a premise that should
never have been written. The review process was not at fault; it worked exactly as designed, which
is precisely why it kept producing findings.**

R-U3-1 and R-U3-3 were written as guarantees about **outcomes** — *"a real send is never made less
durable"*, *"failure must be uniform, never intermittent"* — each with a supremacy clause. Outcomes
depend on the network. **Networks drop packets.** So reachable counterexamples always exist, and four
lenses duly found them: a full transport queue, an exhausted relay budget, a blocked worker, a socket
dying between two writes. Three of four concluded the feature was unshippable. They were right,
*given the requirement*.

**THE RULE. State requirements as rules about YOUR OWN BEHAVIOUR, which you can hold absolutely.
Never as guarantees about outcomes you do not control.**

- ❌ *"A real send is never made less durable."* — a claim about the world.
- ✅ *"Cover traffic never competes with a real send for any resource; it yields."* — a claim about
  our code, holdable without exception, and **it dissolved the two most severe findings**, which
  existed only because cover was permitted to compete.

This is the U1/U2 lesson one level up. That one was *"a spec that tells the implementer HOW to
construct something is a second implementation."* This one is: **a spec that promises an outcome it
does not control is a second reality.** Recognise the shape: if a requirement can be falsified by the
network misbehaving, it is describing the world, not your program.

### ⭐ WRITE THE VALUE MODEL BEFORE THE REQUIREMENTS

Nothing in the spec ever said **what this layer was worth relative to the others.** There was a
threat model (§1) — that is not the same thing. Without a value model, "absolute" looked reasonable
when written, and every downstream lens inherited the assumption that cover traffic was a primary
guarantee.

What one paragraph would have said, and what it prevented for seven rounds:

> Cover traffic does **not** hide that a message was sent — the TLS frame already shows that. It makes
> an observer's candidate set **2 instead of 1** and forces them to pay interception and decryption
> cost on both, compounding with I2P/Tor which make interception itself expensive. **It is the skin,
> not the core** — Signal Protocol holds the content, the vault holds deniability, the transports hold
> anonymity. In the project's own metaphor: **the decoy is the sugar in the lemonade. It sweetens the
> juice; it is not the juice.** A missing decoy is an umbrella turning inside out while the raincoat
> stays on.

**RULE: every unit gets a value model — what this layer buys, what it does NOT buy, and what it is
worth relative to the layers around it — written BEFORE its requirements, and reviewed adversarially
like anything else.** The expensive errors in this feature were not in the code and not in the review
loop; they were in the layer above both, which nothing was pointed at.

### THE ONE-DIRECTIONAL HONESTY SWEEP — overclaims found, underclaims invisible

On 2026-07-27 a full sweep corrected four published **overclaims** (sealed sender, typing indicators,
decoy traffic, 3-hop relay). It walked past a line saying I2P was *"still in development"* with Tor as
merely *"the active fallback today"* — **four separate times, in the same files, sometimes in adjacent
paragraphs.** Both transports work. Tor is fast and preferred in practice; I2P's first-connect tunnel
build is normal behaviour, not a fault.

**An underclaim is also a false statement, and this one's user harm is sharper than some of the
overclaims':** a reader who believes a shipped privacy transport is unfinished leaves it off and stays
on clearnet — the exact opposite of the feature's purpose. Overclaims risk unearned confidence;
underclaims cause people to decline protection they already have.

**The detection methods are different, which is why one sweep missed the other class.** Overclaims are
found by attacking a claim's evidence: *does the code support this?* Underclaims are found by the
converse: **what does the code do that the docs fail to mention, or describe as weaker than it is?**
Run both directions, or you will only ever find one.

### DISCLOSURE vs DEGRADATION — the correlation bound most people would state too broadly

"Cover must not fail in ways that correlate with anything observable" is the natural phrasing and it
is **wrong** — it forbids load-shedding, which the subordination rule above *requires*.

The correct test: **cover must not fail in ways that reveal events an observer cannot ALREADY
observe.**

- **Load-shedding = DEGRADATION, acceptable.** Dropping cover under pressure correlates with heavy
  sending — but a burst of frames is already visible. Nothing new is revealed; the candidate set is
  1 instead of 2 while the user is busy.
- **Lock / teardown / transport-change correlation = DISCLOSURE, prohibited.** Those name a client
  lifecycle event the observer could not otherwise see. That is what rounds 3–5 closed.

Generalises past this feature: when writing a non-leakage constraint, ask **what the observer already
has**, not merely what correlates.

## Blockers
- None blocking right now. **0.9.2 PR-3 Unit 1 (A-only guard) at ready-to-merge pending a final
  round-5 paired-blind pass on the reverted delta**; the enable-atomicity hardening is a tracked
  follow-up (todos.md), not a blocker on Unit 1. Not blockers — gates.

### "ABSORB THE DIFFERENCE IN THE OTHER FIELD" NEEDS THE OTHER FIELD TO HAVE BYTE GRANULARITY (0.10.0 U2 R1)

The architect's ruling on the `message_number` digit-width tell was: absorb it in the random
ciphertext's length. It is the right *instinct* — a network observer sees the total frame length and
not the internal split, so the observable beats the unobservable — and it is **arithmetically
impossible**, for a reason that is one line long once seen:

> **base64 encodes 3 bytes to 4 characters, so a padded base64 field's length is always a multiple
> of 4 — on BOTH sides. The two `ciphertext` fields can therefore differ only by a multiple of 4,
> and a 1-, 2- or 3-byte difference anywhere else in the frame is unreachable through them.**

Whatever length the blob is given, the residue mod 4 does not move. So the compensating field has to
have byte granularity, and in a JSON envelope the only such knobs are the DECIMAL widths of numeric
fields. That in turn forced the real design change: the decoy's counter mirrors the covered one
instead of advancing monotonically, because a monotonic counter can be skipped forward but never
back, and real counters reset on every inbound ratchet turn.

**The rule:** before ruling that field B absorbs a difference in field A, check B's *quantisation*.
Encodings quantise (base64 by 4, hex by 2, block ciphers by the block, `ISO_INSTANT` to
{0,3,6,9} fractional digits). A knob that only moves in steps of N can never correct a residue that
is not 0 mod N, however much slack it has.

**And the meta-lesson, which is the third instance in this feature:** the same document that carried
the ruling also carried the refutation. §2.3 justified monotonicity with "a `message_number` that
resets is a tell a real ratchet can never produce"; §2.4, forty lines later, conceded that a real
client resets `message_number` on **every inbound ratchet turn**. Nobody read the two together.
**When a design decision rests on a claim about real behaviour, grep the document for the same
claim — the contradiction is more often already written down than not.**

---

## 2026-07-27 — "the two fields go together" was a claim about the SPEC, not about the protocol

**Class:** a design document asserted a biconditional between two protocol fields; the code turned
it into a `require`; the `require` refused a shape production emits every day. Three review rounds
and a dedicated fail-closed test did not catch it, because the test asserted the same wrong belief.

`DECOY_TRAFFIC_0.10.0_SPEC.md` §2.2 said: *"A real conversation's first envelope carries non-null
`ephemeral_key` and `prekey_id`; every later one has them null."* `DecoyEnvelopeBuilder` encoded it
as `require((cover.ephemeralKey == null) == (cover.preKeyId == null))`, and the whole first-shaped
path — wrapper sizing, protobuf serialization, base-key offset — was built on the assumption.

**It is false.** `ephemeral_key` marks an X3DH first message; `prekey_id` names the **one-time**
prekey it consumed. A peer whose one-time batch is exhausted serves a bundle without one, and the
sender does signed-prekey-only X3DH: still a first message, still a base key, `pre_key_id` absent.
**Four places in this repo already implemented that path** — `ApiClient.fetchPreKeyBundle`,
`SignalProtocolManager.establishSession`, `EncryptResult.preKeyId`, and a comment in
`packages/crypto/src/x3dh.ts` that says "null if no OPK was available" in so many words.

### Why the test made it worse rather than catching it

The fail-closed test asserted `build(real.copy(preKeyId = null))` **throws** — and passed. The
fixture was **internally inconsistent**: cleartext `prekey_id` null while the ciphertext still
carried protobuf field 1, built by `copy()` from an OPK-present encrypt rather than by a real
no-OPK session. A test built that way **cannot distinguish "reject garbage" from "reject a
legitimate shape"**, and it was pinning the second while reading as the first. Its name and comment
both said "half a first message", which is what the author believed rather than what the fixture was.

**The rules:**

1. **A `require` derived from a design document is only as true as the document.** Before encoding a
   claim about protocol shape as a fail-closed assertion, find the code that PRODUCES that shape and
   read it. Here the producer was in the same repo, four files, one of them commented with the exact
   counterexample.
2. **Test the negative space from a real producer, not from `copy()`.** A fixture mutated into a
   shape no encoder emits proves the guard rejects that fixture — not that the guard is right. If the
   shape under test is reachable in production, build it the way production builds it; if it is not
   reachable, say so and pin the reachable half instead.
3. **A biconditional between two protocol fields deserves suspicion by default.** Optional fields
   usually carry an implication, not an equivalence. Ask which direction actually holds, and what
   emits the other three quadrants.

### And the shape of the consequence, which is the part worth remembering

Failing closed is normally the safe direction. **Here it was not.** A refused cover envelope is an
**unpaired real frame** — precisely the observable the feature exists to remove — and it would have
appeared for a whole class of RECIPIENTS (those whose prekeys ran out) rather than at random, which
is worse than a uniform leak. **When "fail closed" means "emit the thing you were built to hide",
the guard is not conservative; it is the defect.** Check what the closed state actually looks like
to the adversary before calling it safe.

### THE OBSERVABLE DRAW AND THE UNOBSERVABLE DRAW MUST NOT SHARE A WEAK GENERATOR (0.10.0 U3)

Cover traffic draws two values per send: an **order bit** (which of the two same-length frames goes
first — the value the whole mechanism exists to hide) and a **gap** in milliseconds (which the
observer *measures directly*, because it is the time between two packets it can see).

Drawn from the same generator, the observable one is a **state oracle for the unobservable one**. A
`java.util.Random` is a 48-bit LCG whose state is recoverable from a couple of outputs, so an
observer who times a handful of pairs can predict every subsequent order bit and read the real frame
off the wire from then on — with the frames still perfectly equal in length, which is what makes it
hard to notice. The fix is not "use SecureRandom by convention": the parameter is **typed**
`SecureRandom`, so passing a weak generator is unrepresentable.

**RULE.** When one draw is exposed to the adversary and another must stay hidden, ask whether the
exposed one reveals the generator's state. If they share a generator, the shared generator inherits
the *stronger* requirement — and encode that in the type, not in a comment.

### A DELAY PLACED BEFORE A VARIABLE-DURATION STEP LEAKS THE THING IT WAS ADDED TO HIDE (0.10.0 U3)

The first shape for the decoy-first branch was `emit decoy → sleep(gap) → flushSendRatchet → publish
real`, which reads as obviously correct: the gap is drawn per send, so the observed separation is
random. It is not correct. The **flush's own duration is added to the decoy-first gap and to nothing
else** (the real-first branch measures its gap from a frame that has already gone), so the two
branches have *different* gap distributions and the observer reads the order straight off the
timing — short gap ⇒ real went first.

The delay was moved to sit between the flush and the publish tail, where a suspension is already
legal and the gap means the same thing in both branches.

**RULE, and it generalises past this feature:** a randomised delay only hides what it is adjacent to.
Before placing one, ask **what else runs between the two observable events** — any variable-duration
step inside the interval is added to the measurement, and if it is present in only one branch it is a
discriminator. The mutation that catches this class is "make the gap distribution depend on the
order" (M5 here); it is worth writing whenever a timing property is claimed.

### PROCESS FIX (BINDING) — BRANCH FIRST, COMMIT AS SOON AS IT COMPILES (0.10.0 U3)

U3's whole first implementation — five files, the new class, the 15-test gate, a green
`:app:testDebugUnitTest` run — was **lost to an external revert of the working tree** partway through
the unit. The tree came back at `4438cd72` with `git status` clean; nothing was recoverable from the
implementer's side, because the work had never been branched or committed.

> **⚠️ CAUSE IDENTIFIED AFTERWARDS — it was the ARCHITECT, and the record should say so.** The
> "external revert" was `git stash push -u` run by the architect on the implementer's **live**
> working tree, while preparing state for an announced server restart that then did not happen. The
> implementer could not have known that; it correctly reported the symptom. Naming it matters because
> the lesson doubles:
>
> **For the architect — never stash, reset, or check out across a running agent's working tree.** A
> background agent holds no lock and gives no signal, so the tree looks idle when it is not. If state
> must be preserved mid-flight, prefer a mechanism that does not mutate the shared tree: let the agent
> commit, snapshot a copy outside the repo, or simply stop the agent first. Checking `git status`
> immediately before stashing is not a defence — it is exactly what made the tree look safe.
>
> **The saving grace, and the reason the rule below is still right:** the stash was recoverable and
> the work was rebuilt. Had the implementer branched and committed as instructed, the interruption
> would have cost nothing at all. Two independent failures had to line up to lose work. The instruction for the unit *said* "branch from
current `main`", and the branch had not been created: the work was sitting uncommitted on `main`.

Nothing about the loss was subtle, and the cost was the whole implementation window. Two rules:

1. **Create the unit's branch as the FIRST action of the unit**, before the first edit, not when the
   work is ready to commit. A branch that exists cannot be forgotten under time pressure.
2. **Commit as soon as the unit compiles and its own tests pass** — before mutation sweeps, before
   doc updates, before memory writes. A mutation harness rewrites source files in place; if the tree
   is not committed, its `finally` restore is the only copy of the work in existence. Committing
   first turns "restore the file" into "`git checkout` the file", which is verifiable.

The second rule has a corollary that already exists in this file for a different reason (a harness
that leaves mutated artifacts behind): **`git status` clean is the harness's real postcondition**, and
it can only be checked against a commit.

### LESSON (0.10.0 U3, fix round 1) — CHECK THE FINDINGS AGAINST EACH OTHER BEFORE FIXING ANY OF THEM

The U3 round-1 adjudication listed U3-B (a `deleteContact` interleaves in the gap) and U3-E (the
decoy-first branch measures the real publish tail and the real-first branch does not) as independent
findings at different severities. **They cannot both be fixed.** Fixing U3-B requires no suspension
between the durable barrier and the socket write, which forces the gap to sit *before* the barrier;
that puts the flush's own duration inside the decoy-first interval and nothing else's, which *is*
U3-E. There is no third position for the gap. Two reviewers and an adjudicator each read both
findings and none noticed they contradict — because every one of them was checking findings against
the *code*, and nobody checked them against *each other*.

**The rule:** before implementing a fix round, lay the confirmed findings side by side and ask which
pairs are mutually exclusive. A fix list is not a checklist until it has been shown to be
simultaneously satisfiable. When two findings are the two horns of one dilemma, the round's real
output is naming the dilemma, not shipping half of it — and if the horns sit on different sides of
an "absolute" requirement, the resolution is a design decision and belongs to the maintainer.

**The tell to look for:** the same structural change (here, "pairing inserted a suspension between
the durability barrier and the send tail") appearing as the mechanism behind findings that pull in
opposite directions. One cause, two findings, opposite remedies — that is a dilemma wearing the
costume of a backlog.

### LESSON (0.10.0 U3, fix round 5) — A RED BASELINE MAKES EVERY MUTATION "CAUGHT" FOR FREE

The first round-5 mutation harness was killed by a tool timeout mid-run and left one mutation applied
in `CoverTrafficWorker.kt`. That file was **new and therefore untracked**, so `git status --short`
showed it as `??` and nothing else — the corruption was invisible to the check that exists to catch
exactly this. The harness was restarted, ran to completion, and reported **8 of 8 mutations caught**.

Every one of those results was worthless. The stale mutation removed the terminal fallback, which
makes the suite RED, and a red baseline means every mutation "fails the suite" whether or not any
guard discriminates it. A mutation sweep measures the *difference* between baseline and mutant; with
a broken baseline there is no difference to measure and the sweep silently degrades into a
tautology that looks like a perfect score.

**The rules, and they are cheap:**
1. **Assert the baseline is green as the harness's first action**, and abort loudly if it is not. A
   sweep that cannot state its baseline has no result.
2. **Restore in a `finally`, then verify with a checksum** of every file the sweep can touch —
   comparing against a fingerprint taken before the first mutation. `git status` is not sufficient
   while any touched file is untracked.
3. **Re-check the baseline after the last mutation.** If it is not green, the restore failed and the
   whole run is void.
4. A perfect score is a reason for suspicion, not celebration — especially one that arrives right
   after a harness was interrupted.

The existing rule in this file ("commit as soon as the unit compiles") would have converted this into
`git checkout`; it did not fire because the file was new, and `git add` had not happened. **New files
are the blind spot: `git status` reports their existence, not their content.**

## 2026-07-28 — I fixed an unsatisfiable absolute by writing a new unsatisfiable absolute

**What happened.** Rounds 1–7 of U3 ground against R-U3-1/R-U3-3 because they were written as
guarantees about OUTCOMES. That was diagnosed and rewritten. **The rewrite then said cover traffic
"must never compete with a real send for any resource" and called that absolute — and read
literally it is also false.** Emitting a cover frame IS competing for resources; that is what a
cover frame is. Every cover frame is charged to the same account budget and the same socket.

**Why it survived my own review.** The sentence had a rescuing clause — *"where a shared resource is
contended, cover yields"* — but "contended" was defined in a follow-up ruling, not in the
requirement. **A reviewer reads the requirement.** So the requirement, standing alone, still said
something the code cannot do, and a round-8 lens would have produced the onset-of-burst frames and
the confined worker's occupancy as counterexamples and rated them P1 — the identical failure mode,
one round after diagnosing it.

**Who caught it.** The implementer, unprompted, before dispatch. It has now corrected my requirement
wording seven times.

**The pattern, stated generally.** *Diagnosing an error class does not immunise you against it.* I
correctly identified "absolute claims about outcomes the network can falsify" and then immediately
wrote an absolute claim about resources the machine can falsify. **The fix for an over-strong claim
is not a differently-scoped over-strong claim; it is checking the replacement against a reachable
counterexample before shipping it.** I had a working code path in front of me and did not test the
new sentence against it.

**Rule.** When rewriting a requirement that reviewers falsified, **falsify the replacement yourself
first.** Construct the counterexample. If the requirement is meant to be absolute, the counterexample
must be impossible, not merely rare or merely handled elsewhere. And if a requirement's truth depends
on a definition, **the definition belongs inside the requirement**, because that is the unit a
reviewer is handed.

**Corrected wording, which does hold:** *cover yields on every signal of contention available to it,
and spends nothing after one.* Absolute, about our own code, and satisfied by the implementation.
The three places cover still consumes a resource are now NAMED in the requirement rather than
denied by it. See [[the one-directional honesty sweep]] and the outcomes-vs-behaviour entry above.

## 2026-07-28 — a mutation sweep cannot see a guard that no longer exists

**What happened.** A U4 round-3 edit replaced one tripwire test by cutting a region between two
anchors. The region contained **two** tests, and the second was collateral. The suite stayed green,
the test count moved by one, and nothing flagged it. The mutation sweep then reported a survivor —
but only because the *other* deleted test happened to guard something on my mutation list. I
restored that one, wrote the loss up in the adjudication as caught-and-closed, and moved on. **A
later review round found the second deletion by reading the unit.**

**The technique's blind spot, stated precisely.** A mutation sweep answers "are the mutations I wrote
detected?" A deleted guard has nothing left to mutate, so it is invisible to the sweep *by
construction*. The sweep found this one only by luck of adjacency. It is the one class of test
regression that the strongest tool in this repo is structurally unable to detect.

**Why the write-up was worse than the deletion.** The deletion was an accident. Recording it as
closed was a claim, and it was false — it turned a recoverable slip into a wrong entry in the
project's own record, which the next person would have trusted. **Restoring what an error revealed
is not the same as repairing the error:** I fixed the instance the tool pointed at and generalised
nothing, when "what else did that edit remove?" was one `git diff --stat` away.

**Rules.**
1. **Test names and test COUNT are part of the diff to review.** A shrinking count with a green
   suite is a defect until explained. `git diff --stat` on test files before every commit.
2. Never cut a source region by anchor without reading everything between the anchors.
3. When a tool reveals a mistake, ask what else the same action did — the tool showed you one
   instance, not the extent.
4. An adjudication that says a problem is closed is a **claim about the tree**, held to the same
   standard as a claim about the code. Verify before writing it.

See [[the one-directional honesty sweep]] — same shape: a check run in one direction, and the
conclusion written as though it had been run in both.

## 2026-07-28 — the guard was silent everywhere except the one place I added

**What happened.** U4's R-U4-1 guard drops an inbound cover-account envelope before decrypt. I gave
it a `diag()` line, as most branches on that path have. `BootDiagnostics.record` does
`file.writeText`: every dropped cover envelope wrote a timestamped line to `boot-diagnostics.log`,
surfaced in Settings → Diagnostics and surviving the process. **Durable, user-copyable evidence that
this device ran cover traffic** — hence that a vault with a provisioned synthetic account exists —
in a product whose feature *is* plausible deniability.

**The tell I walked past.** Every other decoy surface takes no logger at all: the pairing, the
builder and the provisioner are all constructed without one and fail silent, and each says so in its
kdoc. **The discipline was already written down, in the files I had been editing all day.** I did not
notice I was the only place breaking it, because I was matching the *surrounding* code — the
coordinator, which logs freely — instead of the *feature's* code, which does not.

**Rule.** When a new branch joins an existing function, it inherits two contexts: the function's
conventions and the feature's invariants. **Where they conflict, the feature's win**, and the
conflict is exactly where a defect hides — the code looks locally idiomatic. Ask "what does the rest
of THIS FEATURE do here?" before "what does the rest of this file do here?"

**Also:** the requirement did not catch it either. R-U4-3 said "adds no durable-state writer",
meaning vault sections; a diagnostics log is durable state by any honest reading. A requirement
scoped to the mechanism you were thinking about will not cover the one you were not.

## 2026-07-30 — I used `kimi -p` for a review after it is documented TWICE as not finishing

**What happened.** Asked to bring Kimi K3 in as a third lens on 0.10.2 item 5, I dispatched
`KIMI_MODEL_THINKING_EFFORT=high kimi -p "$(cat …)"`. That is the one shell-drivable Kimi mode, and it
is **the mode recorded as not completing** — in `/root/.claude/CLAUDE.md` under a heading that says so
in capitals ("⚠️ THE INVOCATION THAT ACTUALLY WORKS"), and again in this repo's own ledger reviewer
roster: *"Kimi completes only in the interactive CLI (plan mode + `/yolo`); `kimi -p` does not
finish."* Two prior whole-unit attempts had died mid-work with no verdict (~10 KB at `max`, ~87 KB at
`high`). Mine reached 16.7 KB with no verdict before I killed it.

**Knowledge did not prevent it — I quoted the risk while doing it anyway.** I told the maintainer in
the same message that "`kimi -p` has died mid-work twice on this box without producing a verdict" and
dispatched it regardless, because the maintainer's phrasing ("Kimi k3 is an agent on this box") pointed
at the agent and I resolved the conflict toward the instruction in front of me rather than the
instruction on file. **The recorded procedure is not advice to weigh against a fresh instruction; it
is the thing to raise the conflict about.** The correct move was to say "the record says `-p` will not
finish, so this needs you at a terminal or the `moon` path" BEFORE spending the run.

**What went right, and is worth keeping as procedure.** Because an agentic CLI in a shared tree has
already cost real work on this box, I committed everything first so the tree was clean at dispatch,
and put an explicit read-only instruction at the top of the prompt. Verified afterwards:
`git status` shows **only the two files I created myself** — Kimi wrote nothing. The precaution cost
one commit and made the "did it mutate the tree?" question answerable in one command instead of
arguable.

**Also learned:** `l00prite/.l00prite/reviews/KIMI-PROMPTING.md` exists and I had not read it. It
answers the `ask` vs `--diff` question for Moonshot directly — **`ask` with explicit full files, "and
it isn't close"**, because adversarial unit review needs the unchanged 90% (the caller that was
correct before, the invariant established 200 lines above the hunk) while `--diff` answers "did this
change regress?". **Read the roster AND the prompting guide before choosing a lens invocation.**

**Rule:** for Kimi, an agent from a shell uses `moon`. `kimi` interactive + plan mode + `/yolo` is a
human-driven invocation and cannot be delegated. Partial `-p` output is notes, never a review.
