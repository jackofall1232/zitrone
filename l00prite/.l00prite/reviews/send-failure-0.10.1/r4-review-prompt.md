# Adversarial review — Zitrone 0.10.1, send-failure surfacing, round 4 of a HARD-CAPPED 6

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already been
found. Repo root: `/root/zitrone`. Branch: `feat/0.10.1-send-failure-surfacing` (PR 64).

## SCOPE — the ROUND-3 DELTA is the primary target, but read the WHOLE UNIT

Round 3 produced **0 P1 / 0 P2 from both lenses** and one shared P3, now fixed. **That is the least
informative state this review can be in**, and it is exactly when a unit ships a defect: the previous
round found nothing structural, so the temptation is to confirm. Resist it.

> **Every fix delta in this stream has produced a finding.** Round 1's fix caused round 2's P1. Round
> 2's fix broke a U3 tripwire. Round 3's fix corrected comments — **and round 3's finding was itself a
> stale comment that round 2 had claimed to fix and missed, propagated into a file written the same
> day.** Verify against source, never against this prompt. A CLEAN is the absence of a finding, not a
> proof — say precisely what you checked.

**The round-3 delta, in full** (`git show 8764de78` if useful — 4 files, +36/−11):

1. **`net/WsClient.kt`** — the `onServerError` kdoc's null-id explanation was rewritten. It had said
   the budget is checked before the envelope is parsed; merged `handleSend` parses **first**, so an
   ordinary rate-limited send **does** carry its id. **Attack: is the replacement now accurate against
   `server/internal/ws/hub.go` as merged, or has it traded one wrong claim for another?** Does it still
   correctly describe when a caller sees null?
2. **`MessageRepositoryTest.kt`** — the send-timeout test's rationale comment, same correction.
   **Attack: does the stated justification still hold?** If the common `rate_limited` now carries its
   id, is the timeout justified by cases that actually occur, or is its rationale now thin?
3. **`ServerErrorRouterTest.kt`** — the unattributable-yield test's comment, same correction.
   **Attack: does the comment now match what the test asserts?** A test whose prose and assertion
   disagree is the class this stream keeps producing.
4. **`DecoySendPairingTest.kt`** — a NEW source pin: inside the brace-walked `ws.sendMessage` success
   branch of `publishOutgoing`, assert `messages.armSendTimeout(messageId)` is present. This was added
   on one lens's round-3 suggestion, to pin *where* arming lives — which a repository test cannot see.
   **Attack this hardest, it is the newest code:** can arming be moved somewhere this pin stays green
   but the round-2 P1 returns? Can it be satisfied while arming is also duplicated elsewhere,
   double-arming a message? Is it over-constraining — would a legitimate refactor fail it? Does it
   belong in a decoy test file at all, and does its presence there create a false dependency?

## What Zitrone is

Zero-knowledge, plausible-deniability encrypted messenger. **The relay is CONCEDED** — it sees
cleartext `sender_id`/`recipient_id` and can drop, delay, lie, duplicate, reorder. Cover traffic
defends against a *network observer*, never the relay. The message store is **RAM-only**. Android is
the security reference client.

**R-U3-1 is absolute:** a real send is never blocked, failed, delayed, reordered, or made less durable
to produce cover. **A retry IS a real send.**

## The unit, for whole-unit review

A rejected send used to display `SENDING` forever — `onServerError` swallowed every server rejection.
Now: the relay echoes `message_id` on `rate_limited` / `store_failed` / `bad_envelope` (merged, and
readable at `server/internal/ws/hub.go`); `WsClient` normalises absent/empty to null at the wire
boundary; `routeServerError` yields cover on the **code** and fails the message on the **id**, neither
nested in the other; `markFailedByRelay` accepts **SENDING only** so a receipt outranks a
contradicting error; receipts **heal** FAILED; and a **90 s send timeout** armed at the socket handoff
bounds the null-id case.

**Re-attack the load-bearing claims yourself, do not inherit them:**

- Can a **cover** frame's rejection surface to a user? (The claim is structural: a cover envelope owns
  no `Message` row.)
- Can a **hostile or buggy relay** falsify state — fail a message it stored, fail one from another
  conversation, replay, or induce a duplicate delivery?
- Can the **retry** path double-deliver, or resurrect the R-U3-1 class?
- Can the **timeout** fire against local work, outlive its session, double-fire, or fire against a
  message whose id was reused?
- Is there **any** send path reaching `ws.sendMessage` that does not arm, and so hangs forever?

## THE HARNESS QUESTION — round 3 left it split, and one premise was refuted

One lens ruled **harness required before merge** (three same-shaped escapes ⇒ lexical assertions are
not an adequate gate). The other ruled **asserted-is-enough, harness is residual debt**, and refuted
the first's evidential premise: round 2's P1 was arm-at-`addOutgoing` timing, `MessageRepository` **is**
constructible, and the extraction would not have caught it either.

**That refutation was verified against the project's own record and holds:** round 2's mutation was
caught by a `MessageRepository` test with no coordinator harness involved. So the harness may still be
owed on *pattern* grounds — three escapes — but not on the grounds originally argued.

**Rule again, with that in front of you.** Since round 3, a source pin for the arming wiring was added
(delta item 4). State plainly whether the remaining gap gates **this** merge or is debt to schedule,
and if you think a cheaper seam is still unexploited, name it. **Do not simply restate your round-3
position** — say whether the refutation and the new pin change it, and why or why not.

## Files

- `app/src/main/java/com/zitrone/app/ServerErrorRouter.kt`, `MessagingCoordinator.kt` (`onServerError`, `publishOutgoing`, `retry`, `deliverText`/`deliverAttachment`), `data/MessageRepository.kt` (`markSent`, `markDelivered`, `markFailed`, `markFailedByRelay`, `retryable`, `armSendTimeout`, `cancelSendTimeout`, `clearAll`, `burn`, `remove`, `update`), `net/WsClient.kt`, `decoy/WsSyntheticSocket.kt`, `decoy/DecoySendPairing.kt`
- `server/internal/ws/hub.go` — the merged relay half
- Tests: `ServerErrorRouterTest`, `MessageRepositoryTest`, `WsClientFrameTest`, `WsSyntheticSocketTest`, `DecoySendPairingTest`

## Declared residuals — judge whether each is correctly classed, do not re-report as new

- No test constructs `MessagingCoordinator`; coordinator wiring is **asserted, not tested**.
- The conditional-removal race and the cancel-vs-CAS redundancy need a controllable dispatcher with a
  barrier; a single-threaded virtual clock cannot express either. Both guards were **kept** as
  reachable-under-real-threading; round 0 **deleted** an `isMine` clause as unreachable-by-construction.
- Live `deliverAttachment` + slow body + timer interaction is untested end to end.

## Calibration

- **P1** — a real message is lost, corrupted, double-delivered or made undeliverable; the user is shown
  a false state; a decoy surfaces to the user; or the client discloses something an observer could not
  otherwise see.
- **P2** — the fix does not fix the defect in some reachable case, or cover traffic degrades.
- **P3** — a guard that does not guard what it claims, a doc/comment inaccuracy, hygiene.

Do not report style. Every finding needs: file and line, a **concrete reachable sequence**, the wrong
outcome, and why the tests miss it.

## Output

```
# FINDINGS
(one section per finding: ID, severity, file:line, the sequence, the outcome, why tests miss it)

# CONFIRM-OR-REFUTE
(the four round-3 delta items, the load-bearing claims, and R-U3-1)

# HARNESS RULING
(gates this merge, or debt to schedule — and whether the refutation/new pin moved your round-3 view)

# RESIDUAL CLASSING
(is each declared residual correctly classed?)

# MISSING CONTEXT
(anything you could not verify, and what would settle it)

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```
