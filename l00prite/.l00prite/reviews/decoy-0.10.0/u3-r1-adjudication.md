# U3 — ROUND 1 adjudication (Codex + Grok, paired-blind)

Codex: `FINDINGS (3 P1, 3 P2, 0 P3)` · Grok: `FINDINGS (0 P1, 1 P2, 6 P3)`
**Adjudicated union: 4 P1, 2 P2, 4 P3.** The heaviest round of the feature, on the unit that makes
cover traffic real.

Disjoint on the top findings for the fourth consecutive round: Codex found the two most severe and
Grok missed both; Grok found a fourth P1 Codex missed.

## THE ROOT — one mistake wearing four faces

**The implementation made cover traffic and real traffic *peers*. They are not.** R-U3-1 exists
precisely because cover must **yield** to real in every dimension — durability, ordering, rate
budget, and cancellation. Every P1 below is that same error.

And the mechanism behind all of them is one structural change: **pairing inserted a suspension
between the durability barrier and the send tail.** Before U3, `flush → contactExists → publish` had
no suspension point in it, so a whole class of interleavings was *structurally impossible*. U3 made
them possible without re-deriving what depended on their impossibility. That is the round-12 pattern
— moving *when* something happens without re-deriving what assumed the old timing — recurring on
control flow instead of on durable state.

## CONFIRMED — P1 (all four are R-U3-1 violations)

| # | Src | Defect |
|---|---|---|
| **U3-A** | Codex | **Process death in the gap loses a real message.** Decoy-first: the ratchet advance is durable, the real envelope has not reached the socket. Without cover it would have been handed off immediately after the flush. `finally` cannot run after process death. Cover traffic made a real send **less durable**. |
| **U3-B** | Codex | **A queued `deleteContact` interleaves in the gap.** On resume `contactExists` is false and the real message is **discarded**. Pre-U3 this was structurally impossible — there was no suspension between the completed flush and the tail. Cover traffic turned a successful send into a dropped one. |
| **U3-C** | Codex P1 / Grok P2 → **ruled P1** | **Cover frames spend the real account's `sendLimit`.** The relay charges the authenticated account, not the recipient, so both frames draw the same 100/min bucket. With one permit left and decoy-first, the decoy takes it and the real frame gets `rate_limited` — and the error carries **no message id**, so the client cannot mark or retry it; the message sits in `SENDING` forever. **Severity dispute resolved as P1**: the spec's own framing is that a real send failing because of cover traffic is the categorical violation, and this fails one silently and unrecoverably. |
| **U3-D** | Grok (F5) | **`emit` rethrows `CancellationException` after latching, which can skip `real()` in the decoy-first `finally`.** A path where the real send simply does not happen. Codex missed it. Grok filed it P3 as "latent"; it is a direct R-U3-1 violation and is ruled **P1** on the same basis as U3-C. |

## CONFIRMED — P2

| # | Src | Defect |
|---|---|---|
| U3-E | Codex | **The timing asymmetry has recurred in general form.** Decoy-first measures gap + the whole real publish tail (`contactExists`, repository/diagnostic branching, JSON construction, socket enqueue); real-first does that work *before* the first observed frame. Variance in roster lookup, allocation, serialization or GC is therefore correlated with the hidden order. **The implementer found and fixed one instance of this class — the flush — and the general case survived.** Fixing an instance is not fixing a class. |
| U3-F | both | **The 5 ms floor separates calls to OkHttp's async `send`, not actual socket writes.** A stalled writer thread can queue both frames and emit them back-to-back or inside one TLS record, presenting the pair as a single double-length frame. The floor's stated purpose is not achieved by the mechanism chosen. |

## CONFIRMED — P3

- **U3-G** (both): cancel-while-waiting-for-the-lock emits **outside** `window`, so a real frame can
  overtake an in-flight pair and frames can land inside it — the exact path claimed handled.
- **U3-H** (Grok): "waits at most `GAP_MAX_MS`" is false under multi-waiter load; the bound is per-hop,
  not total. R-U3-1's "materially delayed" needs a real bound.
- **U3-I** (Grok): gate tests kill mutations but leave R-U3-1 edges uncovered — no test drives the
  coordinator's confinement, a concurrent delete, the limiter boundary, or process death.
- **U3-J** (both): §4.2's synthetic-account delete is still unimplemented and **U3 made registration
  spend reachable.** This is the merge gate, not a code defect — see below.

## What I am NOT prescribing, and why

**I am not specifying the fix.** §4.3 was the first spec section in this feature with nothing wrong
in it, and the implementer's own assessment is that the requirements-not-instructions framing is why:
every construction decision that would previously have been mis-specified was decidable only from the
code. Prescribing a mechanism here would be repeating the mistake that produced every P1 in U1 and U2.

The requirement stands unchanged: **R-U3-1 is absolute and every other requirement loses to it.**

**But one possibility must be named, because it is a design decision and not an implementation one:**
if a randomly-ordered pair *cannot* satisfy R-U3-1 — if any ordering that sometimes places the decoy
first inherently widens a loss window, spends a permit ahead of the real frame, or admits an
interleaving — then **R-U3-1 and R-U3-2 are in genuine conflict, and R-U3-1 wins.** That would mean
the real frame always goes first, order is no longer random, and the unpredictability R-U3-2 requires
must come from somewhere else or be conceded as unattainable. **That is a maintainer decision, not an
implementer's.** If the analysis leads there, stop and say so rather than trading away durability.

## The merge gate (U3-J), restated for the maintainer

`todos.md` carried: *"account deletion / burn leaves the synthetic relay account registered — answer
before U3 wires provisioning."* **U3 wires provisioning**, so it is now reachable and still
unanswered. The Pucker Burn never contacts the relay at all, so a duress wipe leaves the synthetic
account alive server-side. Blocks U3's **merge**, not its review.

---

## THIRD-LENS RULING on the two severity disputes (2026-07-27)

Both disputes went to **two independent third lenses — Gemini and Kimi K3** — blind to which reviewer
held which position. **Both ruled P1 on both disputes**, confirming the adjudication above.

**U3-C** (cover frame takes the last `sendLimit` permit) — **P1**. "A human will not reach 50/min" is
a probability statement about *triggering*, not a bound on blast radius; per occurrence the loss is
total, and the UI shows a false `SENDING` state indefinitely. As one lens put it: *not an error, a
lie.* And the "rate limiting can already happen without cover traffic" argument conflates cause —
without cover, a rejected send is the user's own throughput meeting backpressure; here the **cover
frame itself consumes the permit that kills the real frame.**

**U3-D** (cancellation skips the real send) — **P1**. Cancellation fires on vault lock, teardown and
**app backgrounding** — among the most frequent lifecycle events on a mobile messenger. A narrow
window times a high-frequency trigger is not a theoretical path. And the `finally` guard cuts the
*opposite* way to Position B's argument: it exists to make "the real send always escapes" absolute,
so a helper that rethrows after latching **defeats the guard from inside the region it protects**.
That is a broken safety mechanism, not a missing nicety.

### The rule this establishes, which outlives these two findings

**Frequency and trigger-window width are inputs to remediation PRIORITY, not to severity CLASS.**
When the outcome is silent, unrecoverable loss of the product's core function, rarity does not
downgrade it. And against a requirement declared **absolute with an explicit supremacy clause**,
blast-radius arguments are a **category error** — they answer a question the spec has already closed.

### A better statement of the fix constraint than the one this adjudication gave

The adjudication said "cover must yield to real". The third lens improved on it:

> **Cover traffic must be structurally incapable of preempting the real send** — best-effort,
> droppable first, never permit-consuming ahead of the real frame, and never able to suppress the
> real emitter.

The difference is load-bearing: *"it does not, because we check"* is a policy; *"it cannot, because
the real frame is committed first"* is structural. Prefer the second. Corollary, and the reason U3-C
has an easy answer: **cover is the discardable half of the pair by definition** — a pair that cannot
be completed degrades to an *uncovered real send*, never to a failed one.

Relayed to the implementer mid-round rather than held for the next one.
