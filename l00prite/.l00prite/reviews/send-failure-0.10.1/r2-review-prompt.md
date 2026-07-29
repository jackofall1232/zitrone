# Adversarial review — Zitrone 0.10.1, send-failure surfacing, round 2

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already
been found. Repo root: `/root/zitrone`. Branch: `feat/0.10.1-send-failure-surfacing`.

## Review the WHOLE UNIT, not the round-1 delta

A prior release shipped a real defect because review was scoped to a fix diff and the original unit
went unexamined. **This round the unit is larger than the diff you might diff** — a send timeout was
added after round 1 and is part of what you are reviewing.

## What Zitrone is

A zero-knowledge, plausible-deniability encrypted messenger. **The relay is CONCEDED in the threat
model** — it sees cleartext `sender_id`/`recipient_id` on every envelope and can drop, delay, lie,
duplicate, or reorder. Cover traffic (0.10.0) defends against a *network observer*, never against
the relay. Android is the security reference client. The message store is **RAM-only** — no
database, no file cache; process death takes every message.

## The defect being fixed

`onServerError` surfaced nothing: every server rejection of a send was silently swallowed, so a
rejected message stayed displayed as **`SENDING` forever** — not failed, not retried, no error. The
user's only recovery affordance is a tap-to-retry that **only appears on FAILED**.

## ⚠️ WHAT CHANGED SINCE ROUND 1 — THE RELAY HALF IS NOW READABLE

In round 1 the relay half was deployed but unpushed, so its contract was a *claim*. **It is now
merged into `main`** and you can and should read it: `server/internal/ws/hub.go`, the
`handleSend` path. Verify the client against the actual relay rather than against this prompt.

Points worth checking yourself: when `MessageID` is populated vs empty; whether `rate_limited`
precedes the parse-error branch; that `msgID` is only set for a well-formed UUID; and
`json:"message_id,omitempty"` on the `serverEvent` struct.

**A question that is now answerable and was not before:** the relay echoes
`uuid.Parse(x).String()`, which **canonicalises** the id. The client mints ids with
`UUID.randomUUID().toString()` and matches by **exact string equality** in
`MessageRepository.update`. Does that coupling hold for every id the client can produce, and what
happens (silently) if it ever does not?

## Round 1 found four defects, all upheld and fixed. ATTACK THE FIXES.

Round 1: **Codex 1 P1, 1 P2, 1 P3; Grok 0 P1, 2 P2, 2 P3** — both lenses independently found the
same top defect.

1. **(P1) A relay-attributed failure could permanently falsify a send that SUCCEEDED.** `markFailed`
   accepted `SENT`, and `markSent`/`markDelivered` both *rejected* `FAILED` — so a spurious,
   duplicated, or stale error marked a STORED message failed, no receipt could heal it, and the only
   recovery (retry under the same id) genuinely double-delivered. **Fixed two ways:** a new
   `markFailedByRelay` accepting **SENDING only**, and receipts now **heal** (`markSent` /
   `markDelivered` accept `FAILED`).
   **Attack:** is the healing direction safe? Can a stale/duplicated/hostile receipt now resurrect
   or mis-state something it should not — a burned, removed, retried, or TTL-expired message? Is
   there any interleaving of {error, stored, delivered, retry, burn, TTL} that ends in a state the
   user is shown wrongly? Does `retryable` interact correctly with healing?
2. **(P2) A null id left the bubble SENDING with no path out.** **Fixed by a SEND TIMEOUT** — see
   the dedicated section below.
3. **(P3) Comments claimed an ownership bound the code does not implement.** There is no `isMine`
   check; what makes incoming mail unreachable is that `addIncoming` forces `DELIVERED`. Comments in
   `MessagingCoordinator` and `WsClient` were rewritten to say so.
   **Attack:** are they now accurate, or still overclaiming? Is the "production call graph, not the
   type" argument actually true — can any path put a not-ours message into SENDING/SENT?
4. **(P3) The ordering tripwire proved source order, not the property.** An early
   `if (messageId == null) return` defeated it while staying green. **Fixed** with an added
   assertion that nothing may `return` ahead of the yield.
   **Attack:** is it now sufficient, and is it over-constraining to the point of blocking a
   legitimate refactor?

## THE SEND TIMEOUT — new since round 1 and the largest new surface

`MessageRepository.scheduleSendTimeout` / `cancelSendTimeout`, `SEND_TIMEOUT_MS = 90_000`.

An outgoing message awaiting the relay's `message.stored` is failed after 90 s. Armed in
`addOutgoing`, re-armed in `retryable`, disarmed on every path that moves the message off SENDING
(`markSent`, `markDelivered`, `markFailed`, `markFailedByRelay`, `burn`, `remove`). It fires through
a **SENDING-only CAS**, so a receipt that wins the race no-ops it.

Design claims to attack, each independently:

- **It times the relay's RECEIPT, not delivery.** Once SENT, the relay has the message and it may
  wait indefinitely for an offline peer without being failed. **Is that actually what the code
  does**, on every path, including `markDelivered` arriving without a preceding `markSent`?
- **It needs no relay cooperation** — which is why it was chosen over shipping the gap. Verify it
  cannot be defeated or starved by the relay.
- **90 s is claimed safe for the slowest transport** (fresh Tor circuit / I2P tunnel). Is failing a
  merely-slow send here a real risk? A false failure invites a retry, and a retry under the same id
  can double-deliver — that is the harm to weigh, not user annoyance.
- **An early fire is claimed self-correcting** because a late `message.stored` heals the bubble.
  Check that interaction end to end, including a retry racing the heal.
- **Leaks and lifecycle:** can a timeout job outlive its message, leak a coroutine or a map entry,
  double-fire, fire after `burn`/`remove`, survive a vault lock, or fire against a *different*
  message that later reuses the id? Note `retryable` reuses the SAME id.
- **Attachments:** an attachment send does a blob upload first. Does the 90 s window start too
  early for a large attachment on a slow circuit?

## A DECLARED redundancy — rule on whether the reasoning is sound

The mutation sweep found that **dropping the `markSent` cancel** and **widening the timer's CAS**
**each survived alone**; only removing **both** failed a test. Both were **kept**, with the argument
that they are not equivalent under concurrency: the cancel is the common path but can lose the race
to a job already past its `delay`, and the CAS is then the last line. A comment says so and forbids
being upgraded into a correctness claim.

**Is that argument correct?** If the redundancy is genuinely unreachable, say so — an unreachable
guard whose test cannot fail is the exact defect round 0 removed (an `isMine` clause) and keeping one
here would be inconsistent. If it IS reachable, is there a test that could discriminate it?

## THE OPEN QUESTION THE LENSES SPLIT ON — please rule explicitly

**No test constructs `MessagingCoordinator`.** Its constructor needs `Context`,
`NotificationScheduler`, `SignalProtocolManager` and more, which is Robolectric-scale. So the
*attribution wiring* is pinned only by a **source tripwire**, while the substrate (wire
normalisation, repository CAS, timeout behaviour, synthetic socket) is tested behaviourally.

Round 1: **one lens called this a merge blocker; the other called it an acceptable residual.** It
was left unadjudicated because item 2 blocked merge anyway. **It no longer does.** So:

**Is the missing coordinator harness a merge blocker for this change, or an acceptable residual with
a follow-up?** Answer plainly and give the reasoning. If you think there is a cheaper seam than a
full application harness that would make the wiring behaviourally testable, name it.

## Files

- `apps/android/.../net/WsClient.kt` — `Listener.onServerError` (+`messageId`), the `"error"` dispatch
- `apps/android/.../MessagingCoordinator.kt` — `onServerError`, `retry`, `deliverText`/`deliverAttachment`, `publishOutgoing`
- `apps/android/.../data/MessageRepository.kt` — `markSent`, `markDelivered`, `markFailed`, **`markFailedByRelay`**, `retryable`, **`scheduleSendTimeout`/`cancelSendTimeout`**, `burn`, `remove`, `update`
- `apps/android/.../decoy/WsSyntheticSocket.kt` — accepts and ignores the id; `rate_limited` → `CoverPressure`
- `apps/android/.../decoy/DecoySendPairing.kt` — the `onRelayRateLimited` kdoc (rewritten in round 1)
- `server/internal/ws/hub.go` — **the relay half, now merged and readable**
- Tests: `WsClientFrameTest`, `MessageRepositoryTest`, `WsSyntheticSocketTest`, `DecoySendPairingTest` (the tripwire), `DecoyU4SourceTripwireTest`

## Do not let 0.10.0's guarantees regress

`R-U3-1` is absolute: **a real send is never blocked, failed, delayed, reordered, or made less
durable to produce cover.** A **retry is a real send.** The cover-traffic yield must fire on the
*code* even when the rejection is unattributable — it must not become conditional on the id. Confirm
none of this unit's changes weaken it.

## Calibration

- **P1** — a real message is lost, corrupted, double-delivered, or made undeliverable; the user is
  shown a false state; a decoy surfaces to the user; or the client discloses something an observer
  could not otherwise see.
- **P2** — the fix does not fix the defect in some reachable case, or cover traffic degrades.
- **P3** — a guard that does not guard what it claims, a doc/comment inaccuracy, hygiene.

Do not report style. Every finding needs: file and line, a **concrete reachable sequence**, the
wrong outcome, and why the tests miss it.

## Output

```
# FINDINGS
(one section per finding: ID, severity, file:line, the sequence, the outcome, why tests miss it)

# CONFIRM-OR-REFUTE
(the four round-1 fixes, the send timeout's design claims, the declared redundancy, and R-U3-1)

# HARNESS RULING
(merge blocker or acceptable residual — and why; name a cheaper seam if one exists)

# MISSING CONTEXT
(anything you could not verify, and what would settle it)

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```

A CLEAN verdict is the absence of a finding, not a proof of correctness — say what you checked.
