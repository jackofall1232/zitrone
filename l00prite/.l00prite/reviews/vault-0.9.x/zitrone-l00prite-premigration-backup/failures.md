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

## Blockers
- None blocking right now. D3/PR #48 awaits its independent-review reconciliation (Grok done,
  Codex pending) before any merge decision — not a blocker, a gate.
