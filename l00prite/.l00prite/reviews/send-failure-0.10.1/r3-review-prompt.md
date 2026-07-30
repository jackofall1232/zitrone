# Adversarial review — Zitrone 0.10.1, send-failure surfacing, round 3 of a HARD-CAPPED 6

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already
been found. Repo root: `/root/zitrone`. Branch: `feat/0.10.1-send-failure-surfacing` (PR 64).

## SCOPE — rule against the ROUND-2 FIXES, not the round-1 code

Round 1 and round 2 are adjudicated and fixed. **This round judges the fixes.** They are described
below so you know where to look — **not so you can accept them.**

> **In this review stream, EVERY fix delta has produced a finding.** Round 1's fix introduced round
> 2's P1. Round 2's own fix broke a U3 tripwire and needed a relaxation. **Treat these fixes as
> guilty until independently proven otherwise, and verify against source rather than against this
> prompt.** A CLEAN is the absence of a finding, not a proof — say what you checked.

The diff is clean of an unrelated CVE dependency bump (`golang.org/x/text`, fixed separately on main
as `c8b5de3f` and merged in), so what you see is the send path and nothing else.

## What Zitrone is

Zero-knowledge, plausible-deniability encrypted messenger. **The relay is CONCEDED in the threat
model** — it sees cleartext `sender_id`/`recipient_id`, and can drop, delay, lie, duplicate, reorder.
Cover traffic defends against a *network observer*, never the relay. The message store is
**RAM-only**: no database, no file cache, process death takes everything. Android is the security
reference client.

**R-U3-1 is absolute:** a real send is never blocked, failed, delayed, reordered, or made less
durable to produce cover. **A retry IS a real send.**

## The five round-2 fixes, and what each must be attacked on

**1. The send timeout moved from bubble creation to the socket handoff.** It was armed in
`addOutgoing`, which for an attachment is *before* an unbounded blob upload (OkHttp's `writeTimeout`
is per-write, not whole-body, so a slow ~11 MiB body is never cut off). The timer fired mid-upload,
flipped the bubble to FAILED, offered retry on a still-live send, and a user taking it produced two
independently encrypted envelopes under one id — rejected on the `envelopes.id` UUID primary key
**unless** the first was already delivered, acked and its row deleted, at which point the peer
genuinely receives the message twice. Arming now happens inside `publishOutgoing`'s
`ws.sendMessage` success branch.
**Attack:** does the window now contain **no local work at all**, on every path? Is
`publishOutgoing` genuinely the single point both the text and attachment paths cross — or is there
a send path that reaches the socket without it, and so never arms? What happens to a send that is
handed off but whose arming throws? Does anything still arm at `addOutgoing` or `retryable`?

**2. `clearAll` now disarms send timeouts.** It cancelled `ttlJobs`, `readBurnJobs` and `revealJobs`
but not `sendTimeoutJobs`, so a timer outlived vault lock, logout, revocation and confirmed deletion
by up to 90 s.
**Attack:** is the disarm complete on **every** teardown path, not just `clearAll`? Vault lock,
logout, session revoke, account delete, Pucker Burn, scope cancellation, process-level teardown.

**3. The fired job's self-removal is now conditional** (`remove(key, value)` on its own handle). It
removed unconditionally, so a retry re-arming between the old job's CAS and that line had its
replacement handle deleted — leaving a live timer nothing could cancel.
**Attack:** does the guard hold? And **is the race it protects genuinely reachable under real
threading, or unreachable by construction?** This distinction decides whether the guard belongs:
round 0 deleted an `isMine` clause precisely because it was unreachable, while round 1 KEPT a
cancel-vs-CAS redundancy because it was reachable. The claim here is reachable — this class is
documented as hit from the main thread and several dispatchers. **Rule on that claim.**

**4. The `markSent` / `markDelivered` kdocs said receipts "can never resurrect a FAILED message"** —
the opposite of round 1's healing fix, which their bodies implement.
**Attack:** does **any** comment, kdoc or test name now contradict behaviour anywhere in this unit?
Specifically: could someone "restoring monotonicity" from a comment reintroduce round 1's P1 (a
spurious error latching a STORED message as failed forever, with retry double-delivering)?

**5. Comments described the PRE-MERGE relay**, claiming the budget is checked before the envelope is
parsed so `rate_limited` "frequently" carries no id. The merged `handleSend` parses the header
**first**, then rate-limits, so a normal rate-limited send **does** carry its id.
**Attack:** are the corrected comments now accurate against `server/internal/ws/hub.go` as merged?
Is the send timeout's justification still sound now that the common `rate_limited` case IS
attributable — or was the timeout justified by a case that mostly does not occur?

## THE TRIPWIRE RELAXATION — verify it pins the real invariant

Moving the arming into `publishOutgoing` broke the U3 tripwire that pinned
`if(ws.sendMessage(envelope)) { return true` as one adjacent token run. It now brace-walks the branch
(`bodyOf`) and asserts the single `return true` lives **inside** it. The argument is that **adjacency
was never the property — ownership was.**

**Attack:** is that argument correct, or was the tripwire relaxed to accommodate the fix? Does the
relaxed form still catch what the original caught? And **is R-U3-1 untouched** — is the arming
strictly *after* `ws.sendMessage` returns, with nothing added ahead of any real handoff?

## THE HARNESS SPLIT — settle it, and note THE EVIDENCE HAS MOVED

In round 2 you split. One lens called the missing `MessagingCoordinator` harness a **merge blocker**,
on the evidence that its absence is what let round 2's P1 through. The other called it an
**acceptable residual**. Both independently proposed the same remedy, and neither wanted Robolectric.

**Two things changed since, and you should rule with them in front of you rather than re-litigating
on round 2's evidence:**

- **The extraction landed.** `routeServerError` is now a pure internal function
  (`app/src/main/java/com/zitrone/app/ServerErrorRouter.kt`) with five behavioural tests
  (`ServerErrorRouterTest.kt`) and no Android framework; two mutations (folding the yield inside the
  attribution, swapping the order) are caught by name. The old tripwire is reduced to **wiring**:
  that `onServerError` delegates, that the cover seam is passed, and that `failByRelay` is
  `markFailedByRelay` rather than `markFailed`.
- **A THIRD instance of the same gap appeared**, in separate 0.10.2 work you cannot see from this
  branch (stated for context, not for you to verify): a mutation deleting the coordinator's
  blob-reuse wiring — restoring the original defect outright — **SURVIVED**, because nothing in the
  suite can construct a `MessagingCoordinator`. Same gap, same shape, third occurrence.

**The honest current state is "the wiring is asserted, not tested."** Rule plainly on whether
**asserted is enough**, or whether a constructible-coordinator harness is now required before this
merges. If you think there is a cheaper seam still unexploited, name it.

## Files

- `app/src/main/java/com/zitrone/app/ServerErrorRouter.kt` — **new**, the extracted routing
- `app/src/main/java/com/zitrone/app/MessagingCoordinator.kt` — `onServerError` (delegates now), `publishOutgoing` (arms the timeout), `retry`, `deliverText`/`deliverAttachment`
- `app/src/main/java/com/zitrone/app/data/MessageRepository.kt` — `markSent`, `markDelivered`, `markFailed`, `markFailedByRelay`, `retryable`, `armSendTimeout`, `cancelSendTimeout`, `clearAll`, `burn`, `remove`, `update`
- `app/src/main/java/com/zitrone/app/net/WsClient.kt` — `Listener.onServerError`, the `"error"` dispatch
- `app/src/main/java/com/zitrone/app/decoy/WsSyntheticSocket.kt`, `decoy/DecoySendPairing.kt`
- `server/internal/ws/hub.go` — the merged relay half, readable
- Tests: `ServerErrorRouterTest`, `MessageRepositoryTest`, `WsClientFrameTest`, `WsSyntheticSocketTest`, `DecoySendPairingTest` (the reduced tripwire)

## Calibration

- **P1** — a real message is lost, corrupted, double-delivered or made undeliverable; the user is
  shown a false state; a decoy surfaces to the user; or the client discloses something an observer
  could not otherwise see.
- **P2** — the fix does not fix the defect in some reachable case, or cover traffic degrades.
- **P3** — a guard that does not guard what it claims, a doc/comment inaccuracy, hygiene.

Do not report style. Every finding needs: file and line, a **concrete reachable sequence**, the wrong
outcome, and why the tests miss it.

## Output

```
# FINDINGS
(one section per finding: ID, severity, file:line, the sequence, the outcome, why tests miss it)

# CONFIRM-OR-REFUTE
(the five round-2 fixes, the tripwire relaxation, and R-U3-1)

# HARNESS RULING
(asserted-is-enough, or harness required before merge — and why, with the moved evidence weighed)

# MISSING CONTEXT
(anything you could not verify, and what would settle it)

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```
