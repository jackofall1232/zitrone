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

## Blockers
- None blocking right now. **0.9.2 PR-3 Unit 1 (A-only guard) at ready-to-merge pending a final
  round-5 paired-blind pass on the reverted delta**; the enable-atomicity hardening is a tracked
  follow-up (todos.md), not a blocker on Unit 1. Not blockers — gates.
