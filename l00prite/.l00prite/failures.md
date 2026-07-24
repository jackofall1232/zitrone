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

## Blockers
- None blocking right now. **0.9.2 PR-3 Unit 1 (A-only guard) at ready-to-merge pending a final
  round-5 paired-blind pass on the reverted delta**; the enable-atomicity hardening is a tracked
  follow-up (todos.md), not a blocker on Unit 1. Not blockers — gates.
