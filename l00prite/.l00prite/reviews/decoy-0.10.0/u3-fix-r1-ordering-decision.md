# U3 fix round 1 — STOPPED ON A DESIGN DECISION

**Branch** `feat/0.10.0-decoy-u3-pairing`. **Round 1 of a hard cap of 6.**

The adjudication reserved one outcome as the maintainer's: *if a randomly-ordered pair cannot
satisfy R-U3-1, then R-U3-1 and R-U3-2 are in genuine conflict and R-U3-1 wins.* **The analysis
leads there.** This file is the derivation, plus one correction to the adjudication that changes
what conceding R-U3-2 would actually buy.

---

## 1. Decoy-first has no legal position for the gap

Fix each P1 and ask where the drawn gap can sit on a **decoy-first** send. There are exactly three
positions relative to the durability barrier (`flushSendRatchet`) and the atomic publish tail
(`contactExists → ws.sendMessage`), and the tail's two halves cannot be separated — that is the D2c
contract, and it is what the `() -> Unit` tail type exists to enforce.

| Position | Decoy-first sequence | What breaks |
|---|---|---|
| **After the barrier** (today) | `flush · decoy · GAP · check · write` | **U3-A, U3-B.** The gap widens every window that exists between the durable ratchet advance and the socket handoff: process death loses a message whose ratchet already advanced, and a queued `deleteContact` lands in a window that pre-U3 was ~0 ms wide, discarding a message that would have been published. |
| **Before the barrier** | `decoy · GAP · flush · check · write` | **U3-E in its worst form.** The observable interval now contains the flush. Decoy-first measures `GAP + flush + tail`; real-first measures `GAP`. The flush is a durable disk commit with a millisecond-scale retry backoff — this is *precisely* the asymmetry the implementer already found and removed once, reintroduced larger. |
| **Inside the tail** | `flush · check · decoy · GAP · write` | Breaks the D2c contract directly: a suspension between `contactExists` and `ws.sendMessage`. Publishing ciphertext to a contact the user deleted during the gap. |

There is no fourth position. **Every placement of a decoy-first gap violates something the spec
calls absolute.** The residual asymmetry cannot be argued away either: in decoy-first the interval
necessarily contains all work needed to produce the real frame, and in real-first that work
necessarily precedes the interval. The two branches cannot be symmetrised without doing the *same*
work twice, and the largest term (the flush) cannot be done twice at all.

This is a stronger statement than the adjudication makes. **U3-B and U3-E are not independent
findings — they are the two horns of one dilemma, and no decoy-first implementation can satisfy
both.** Neither reviewer nor the adjudication noticed that they contradict.

## 2. Decoy-first spends a rate permit ahead of the real frame, and no *sound* policy prevents it

A cover frame enqueued before the real frame consumes a `sendLimit` permit before it. The only
client-side defence is a headroom policy ("emit cover only when the window has ≥ 2 permits left"),
and it is **unsound, not merely policy-shaped**: `sendLimit` is a server-side constant the relay
never communicates. A client that assumes 100/min against a relay configured lower silently
inverts the priority it claims to guarantee. Per the third lens's standard — structural, not
policy — this does not qualify.

## 3. …but conceding R-U3-2 does **not** restore R-U3-1 on the rate dimension

**This is where the adjudication is wrong, and it matters for what the ruling is worth.**

U3-C is stated as an ordering defect: *"one permit left + decoy-first ⇒ the decoy takes it."* That
framing implies real-first fixes it. **It does not.** Cover frames from *earlier* pairs consume the
permits *later* real frames need. Send N's cover frame is emitted 5–50 ms after send N's real
frame and can take the last permit that send N+1's real frame required. Ordering only removes
**self**-preemption within one pair; **cross-send** preemption is inherent to doubling the volume
on a shared per-account budget and survives every ordering choice.

So U3-C is not an ordering bug. It is: **cover traffic halves the account's effective real send
capacity, and a rate-limited real send is silently unrecoverable** — `hub.go` replies
`{"type":"error","code":"rate_limited"}` with **no message id**, and
`MessagingCoordinator.onServerError` is a no-op (verified: `MessagingCoordinator.kt:2120-2123`), so
the bubble sits in `SENDING` forever with nothing to retry. Only a relay-side answer closes it:

- exempt or raise the per-account `message.send` budget, **or**
- carry the message id on `rate_limited` so the client can mark and retry — which turns silent
  permanent loss into bounded delay and is worth doing regardless of this feature.

## 4. What the order requirement is actually worth — offered so the trade can be priced

Order randomness defends against neither adversary it appears to:

- **Hostile relay operator** — reads `recipient_id` in cleartext on both envelopes. Order tells it
  nothing it does not already have.
- **Passive network observer** — sees two equal-length opaque frames. The send *event* and its
  timing are identical either way; which blob is real changes no inference about counterparties.

What it does buy is narrow and real: against an observer watching **both ends**, randomising the
order adds 5–50 ms of ambiguity to the outbound→inbound correlation. That is the whole benefit,
and it is the thing being weighed against durability, delete-atomicity and rate-budget priority.

**Recommendation, explicitly not a decision:** rule real-frame-first. It makes all four P1s
structural rather than guarded — the real frame is committed to the socket before any cover code
runs, so nothing on the cover side *can* preempt it, and the `finally` guard becomes unnecessary
rather than merely correct. Then either concede R-U3-2's unpredictability as unattainable, or seek
it somewhere that costs nothing (e.g. jitter on the *cover* frame's offset, which is already there).
And open the relay-side item in §3 separately, because the ruling does not close it.

## 5. Landed this round anyway — U3-D, which is ruling-independent

`DecoySendPairing.paired`'s `finally` is the mechanism that makes "the real publish always escapes"
absolute, and `emit` rethrows `CancellationException` — the one throwable it does not swallow —
**from inside the region that guard protects**. On the decoy-first path the cover emitter runs
first, so that rethrow skipped the real publish entirely. Fixed by making the guard unconditional
(nested `finally`), not by adding a second guard around it.

This holds under either ruling, so it is landed now rather than held.

**Mutation evidence.** The new test
`a CancellationException out of the cover frame cannot skip the real publish` drives the exact path
the class kdoc advertises — a second send cancelled while *waiting for* `window`, so its `finally`
is the first place either emitter runs. Run against the unfixed source it **fails**
(`cover traffic swallowed a real send`, expected 1, got 0); after the fix the suite is green.
It also demonstrates **U3-G** live in passing: the cancelled waiter emits both of its frames while
another pair holds the window.

## 6. Not fixed, and why

U3-A, U3-B, U3-C, U3-E, U3-F, U3-G, U3-H all have fixes whose *shape* is decided by the ruling in
§1 — under real-first there is no gap after the barrier, no asymmetric interval, and no lock to
escape from, and most of them cease to exist rather than being repaired. Building them twice is the
waste the stop condition exists to prevent. U3-I (test coverage of the R-U3-1 edges) is owed in
full and one of its four named gaps — coordinator-confinement/cancel-during-acquire — is now
covered by the U3-D test.

U3-J (synthetic-account delete) is unchanged and still the merge gate.
