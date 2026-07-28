You are a THIRD, INDEPENDENT lens brought in to settle **two severity disputes** between two blind reviewers of a security feature. Rule on each. You are not told which reviewer held which position; judge on the merits.

## The system

Zitrone is an end-to-end encrypted messenger. The feature under review is **decoy (cover) traffic**: every real outbound message is paired with a synthetic "cover" frame of identical serialized length, sent to a synthetic relay account the client registered for itself. The two frames go out in a random order separated by a small random gap (5–50 ms), so a passive network observer — who sees only TLS frame sizes and timings — cannot tell which frame carried the real message.

The governing requirement, written into the approved spec as **absolute**, is:

> **R-U3-1:** A real send is never blocked, failed, materially delayed, reordered, or made less durable because cover traffic was attempted or could not be produced. **If satisfying any other requirement would violate this one, this one wins.**

The rationale is that cover traffic is a *privacy* feature, and a privacy feature that damages the product's core function (delivering messages) is not an acceptable trade at any privacy benefit.

Both disputes are about **how severe** a defect is, not whether it exists. Both defects are confirmed real by source inspection. The severity scale:

- **P1** — data loss, key leak, deniability break, unauthorized destruction, or a categorical violation of a stated absolute requirement.
- **P2** — a real defect with bounded blast radius.
- **P3** — correctness nit, doc or test gap.

---

## DISPUTE 1 — cover frames consume the real account's send rate limit

**The facts, verified in source.** The relay enforces a send rate limit of **100 messages per minute**, charged to the **authenticated sending account** — not to the recipient. Both the real frame and its cover frame are sent over the real account's own WebSocket connection. Therefore each covered send costs **two** permits instead of one, halving effective send capacity.

The failure mode: with exactly one permit remaining and the random order placing the **cover frame first**, the cover frame consumes the last permit. The real frame is then rejected by the relay with `rate_limited`. The rejection is a generic server error that **carries no message id**, so the client cannot associate it with the specific message; the message remains displayed to the user as `SENDING` indefinitely. It is not marked failed, not retried, and not surfaced as an error.

A human user is unlikely to reach 50 covered sends per minute in normal use.

- **Position A: this is P1.** A real message is silently and unrecoverably lost, and the direct cause is cover traffic. That is precisely the categorical violation R-U3-1 names. Rarity is not a severity reduction when the outcome is silent data loss and the user is shown a false "sending" state.
- **Position B: this is P2.** A real defect, but with bounded blast radius: it requires an extreme send rate a human will not reach, and the underlying condition (hitting the relay's rate limit) can already occur without cover traffic. Cover traffic halves the threshold; it does not create the failure mode.

---

## DISPUTE 2 — a cancellation path can skip the real send entirely

**The facts, verified in source.** The pairing logic emits the two frames through a helper that "latches" each emitter so it fires at most once. The real send is additionally guarded by a `finally` block, so that on any abnormal exit the real frame is still published.

The defect: the emit helper **rethrows `CancellationException` after latching**. In the cover-frame-first ordering, this can propagate out of the `finally` block before the real frame's emitter is invoked — so the real message is **never handed to the socket at all**. The durable ratchet advance for that message has already been committed, so the message is consumed from the sender's perspective but never transmitted.

Reaching it requires cancellation to arrive in a specific window during a covered send. Cancellation in this app occurs on vault lock, session teardown, and app backgrounding — i.e. it is routine, not exotic, though the window is narrow.

- **Position A: this is P1.** The real send does not happen, the ratchet has already advanced, and the message is lost. It is the same categorical R-U3-1 violation as any other lost message; a narrow window is a probability statement, not a severity one.
- **Position B: this is P3, "latent".** The path is theoretical and narrow, the code already has a `finally` guard demonstrating the intent, and no test or observation has produced it in practice. It should be fixed, but it is a robustness gap rather than a live data-loss defect.

---

## What to rule

For **each** dispute independently:

1. **P1, P2, or P3?** State the verdict plainly.
2. The reasoning that decides it — specifically, whether the **rarity or narrowness** of the trigger should reduce the severity of an outcome that is **silent, unrecoverable message loss**.
3. Whether the existence of an **absolute** requirement (R-U3-1) changes how severity should be assigned compared to a normal requirement — i.e. does "categorical violation of a stated absolute" warrant P1 independently of blast radius?

Answer briefly and decisively. Verdict first, then reasoning. Do not hedge to the middle; if a position is wrong, say so.
