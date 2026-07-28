# U3 — ROUND 6 adjudication. **HARD CAP REACHED. STOPPED FOR THE MAINTAINER.**

Grok: `VERDICT: CLEAN` · Codex: `VERDICT: FINDINGS (4 P1, 0 P2, 0 P3)`

**Maximal divergence, at the decision point. But they do not disagree about the facts.**

Both traced the same four mechanisms and describe them identically. They disagree about one thing:

> **Can a declared, tested residual satisfy a requirement declared ABSOLUTE?**

Codex: no — a categorical violation is a violation however well documented.
Grok: yes — these "re-file declared residuals / inherent costs rather than new reachability."

That is not a review question. **It is a design question, and it is the maintainer's.**

## The four mechanisms (agreed by both)

| # | Mechanism | Codex | Grok |
|---|---|---|---|
| 1 | A decoy consumes OkHttp's bounded outbound queue (16 MiB); the next **real** send returns false | P1 | same class as the declared `sendLimit` residual; needs a stalled writer + multi-MB backlog |
| 2 | Worker blocked >250 ms mid-build → `stop()` falls back to caller → `transportInvalid` → the later admission fails → **unpaired real frame** | P1 | declared, tested, and the lesser evil against a hung lock that skips the key wipe |
| 3 | Cover halves the relay send budget; the 51st real frame is rejected where 50 permits would remain | P1 | inherent 2× shared-budget effect, already ruled **relay-side only** |
| 4 | Natural socket death mid-gap → cover fails | P1 | **unpaired, not split** — auto-reconnect backoff ≥1 s vs a ≤50 ms gap |

## THE STRUCTURAL FINDING — the requirement may be unsatisfiable

All four are **"something can go wrong between frame one and frame two."** None is an ordering defect.
**None can be eliminated by any implementation**, because they are properties of the transport and of
shared resources, not of the pairing code. No implementation can make a network incapable of failing
between two writes, or make a shared budget not shared.

So **R-U3-3, as an absolute requirement, is not satisfiable on this transport.** Codex made this
concrete: the implementer declared residuals *and wrote a test asserting the forbidden outcome*
(`DecoySendPairingTest.kt:1380`). You cannot declare your way out of an absolute requirement — either
it admits bounded residuals, or cover traffic cannot ship as specified.

**And R-U3-3's own logic turns on the feature.** It says *intermittent cover is worse than none* —
one unpaired frame among a hundred is *marked*. If unpaired frames are **unavoidable at some rate**,
then cover traffic marks exactly the sends that hit a transport failure, where no cover marks nothing.

**Whether that makes the feature net-negative depends entirely on the residual rate — which nobody
has measured.** That is the number the decision needs and does not have.

Grok's counter is real and cuts the other way: every residual it accepts is an **unpaired real frame
— never a lone decoy, never a split pair** — on paths requiring a blocked worker or a stalled writer.
If that rate is ~0, cover traffic is clearly net-positive and the residuals are noise.

## An error of mine, found by the reviewers

My round-6 prompt asserted that a natural socket death mid-gap "can still **split** a pair." **Two of
three lenses say that is overstated** — Grok on `WsClient.kt:337-340` (`BASE_BACKOFF_MS = 1000` vs a
≤50 ms gap), and Kimi independently in round 4 (not reachable). Codex hedged it to "if reconnection
completes unusually quickly."

I put an overstatement into the prompt and it propagated into the finding severity. Reviewers
correcting the adjudicator's framing is the process working — but the framing was mine to get right.

## What Codex refuted that had been believed

Hypotheses **2, 3 and 4** — teardown *can* enter a send's slice via the caller-thread fallback; a
mid-build pairing *can* be lost by the bounded fallback. Grok agrees on the mechanism for #2 and #4
and classifies them as declared rather than defects. **The mechanisms are not in dispute.**

## STOPPED — what the maintainer must decide

Per the standing rule, **round 6 is the hard cap and the loop stops here regardless of outcome.**
Three decisions, none of which the loop may take:

1. **Is R-U3-3 absolute, or does it admit bounded residuals?** Everything else follows from this.
2. **If it admits residuals — what is the acceptable rate, and who measures it?** No number exists
   today. The feature's value is a function of it.
3. **Codex cannot tie-break here** — it is one of the two disagreeing parties this round (it was used
   as a main reviewer because Kimi is not drivable non-interactively). A tie-break would need Kimi,
   which the maintainer can drive interactively, and it would need explicit authorisation because it
   is past the cap.

**Nothing merged, pushed, or version-bumped.** Branch `feat/0.10.0-decoy-u3-pairing` @ `7ae06e8f`,
723 tests / 0 failures / exit 0, independently re-verified.
