# U3 — ROUND 7 (past the cap, on explicit maintainer authorisation). FOUR LENSES.

| Lens | Verdict | Position on the design question |
|---|---|---|
| Codex | `FINDINGS (4 P1, 0 P2, 0 P3)` | **A** — "the test proves reachability, not compliance" |
| Grok | `FINDINGS (0 P1, 0 P2, 3 P3)` | **A on the meta-rule, B on disposition** |
| Gemini | `FINDINGS (1 P1, 1 P2, 0 P3)` | **A** — "normalizing a broken constraint invalidates the constraint" |
| Kimi | `FINDINGS (6 P1, 0 P2, 2 P3)` | **A** — "documented, tested, and preferable while still violating" |

## UNANIMOUS — a declared residual cannot satisfy an ABSOLUTE requirement

All four, independently. Grok included: *"documentation does not fulfill an absolute requirement; it
only proves the authors noticed the conflict."* On the shipped test that asserts the forbidden
outcome, Grok: *"good engineering honesty about a trade, terrible compliance with an absolute rule —
those two roles cannot be the same sentence."* Kimi: *"until the specification is amended, that test
encodes the contradiction; it does not resolve it."*

**Therefore: the requirements must be rewritten before U3 can ship. That is not in dispute.**
Grok's formulation of the only certainty: **shipping under absolute wording unchanged is the only
option that is always wrong.**

## KIMI REFUTES THE DISSENT ON FACTS, not on principle

Grok's case for shipping rests on: *every residual is an **unpaired real frame** — never a lone
decoy, never a split pair.* **Two of the four are not unpaired frames at all:**

| Mechanism | Requirement | Actual shape |
|---|---|---|
| OkHttp queue saturation | **R-U3-1** | a later real send returns `false` **where the no-cover implementation would have accepted it** |
| Terminal-teardown fallback | R-U3-3 **+ R-U3-1** | unpaired frame, **and** a long cover build delays subsequent sends on the confined worker |
| Shared relay budget | **R-U3-1** | a real send is **rejected earlier** than without cover |
| Socket death mid-gap | R-U3-3 | unpaired frame |

**Mechanisms 1 and 3 are failed real sends — a delivery/durability failure, not a marking failure.**
Grok's "merely unpaired" defence does not describe them. This is a factual correction to the
dissenting position, and it is why 3 of 4 conclude the feature is not shippable as written.

## GROK'S REFINEMENT SURVIVES, AND IT IS THE MOST USEFUL THING IN THE ROUND

I had framed the open question as *"what residual rate is acceptable?"* **Wrong axis.**

> **Client-controlled, sensitive-event-correlated, or adversary-inducible intermittency is worse than
> no cover. Uncorrelated residual at low rate is not.**

Uncorrelated singles are no more informative than "the socket died." Correlated singles cluster on
user and infrastructure events. **Rounds 3–5 already closed the correlated cases** — that is what six
rounds bought. Mechanism 4 alone is genuinely uncorrelated **within a passive threat model.**

## THE ROOT CAUSE IS IN §1, AND IT IS THE ARCHITECT'S

Gemini attacked Grok's refinement: mechanism 4 **is inducible via TCP RST**, and *"a residual becomes
net-negative the moment it is adversary-inducible"* — an attacker who can strip cover on demand turns
the feature into an **unmasking oracle.** Codex independently: inducible exhaustion "can become a
marking or denial oracle." Kimi concurs.

**They are not contradicting Grok. They are pointing at a hole in the threat model.**

**§1 enumerates exactly three adversaries: passive network observer, hostile relay operator, forensic
adversary with the device. There is NO ACTIVE NETWORK ADVERSARY anywhere in it.**

Within the stated model Grok is right and the residuals are benign. But every one of the four
mechanisms is inducible by an adversary who can *act* on the network — RST injection, queue
saturation, CPU starvation — and that is a standard capability, not an exotic one. **I wrote §1
before the failure modes were understood, and it omits the adversary class this feature is most
fragile against.**

## A concern with Gemini's proposed remedy

Its wording requires that a failed pair **terminate the connection immediately**. That appears to
invert into a **denial-of-service amplifier**: an attacker able to force one unpaired frame can then
force a full disconnect, and repeated disconnects are themselves observable and disruptive. Its other
clause — **shared resource limits must account for pairs as indivisible units** — is sound and
directly addresses mechanisms 1 and 3, though the budget half needs relay cooperation.

## THREE DECISIONS FOR THE MAINTAINER

1. **Does the decoy threat model include an ACTIVE network adversary?** Everything follows from this.
   *Yes* → mechanisms 1–4 are exploitable, the feature needs real answers, and Gemini/Codex/Kimi's
   reading governs. *No* → the exclusion must be stated explicitly in §1 **and** `SECURITY_MODEL.md`,
   because a reader will otherwise assume cover traffic protects against an attacker who can touch
   the network.
2. **Rewrite R-U3-1 and R-U3-3.** Unanimous. Codex and Gemini both supplied draft wording; Codex's is
   the more complete (enumerated exceptions, *measured and disclosed*, must not be represented as an
   absolute non-interference guarantee, and a threshold with teeth — *exceeding it disables the
   feature or blocks release*).
3. **Mechanisms 1 and 3 are R-U3-1 failures — failed real sends.** These are not marking residuals
   and cannot be dispositioned as such. The relay-side fix already grouped for the CX23 trip covers
   the budget half; the OkHttp queue half is client-side and unaddressed.

**Nothing merged, pushed, or version-bumped.** Branch `feat/0.10.0-decoy-u3-pairing` @ `7ae06e8f`,
723 tests / 0 failures / exit 0.
