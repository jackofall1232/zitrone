# U1 — ROUND 5 adjudication (Codex + Grok, paired-blind) — **CODE CONVERGED CLEAN**

Codex: `FINDINGS (0 P1, 0 P2, 1 P3)` · Grok: `FINDINGS (0 P1, 0 P2, 2 P3)`
Union: **0 P1, 0 P2, 3 P3 — every one of them documentation.**

## Convergence reached

| Round | Findings | P1 | P2 | Agreement |
|---|---|---|---|---|
| 1 | 10 | 2 | 1 | fully disjoint |
| 2 | 11 | 1 | 7 | 2 of 11 |
| 3 | 10 | 0 | 6 | top 3 independently |
| 4 | 6 | 0 | 2 | top 2 independently, same remedy |
| 5 | **3** | **0** | **0** | **both clean on code** |

**Two independent adversarial reviewers, blind to each other, both find zero code defects at any
severity.** Grok additionally spot-checked ten tests for discrimination and found all ten
discriminating, with no new non-discriminating test. It also explicitly declined to file five
candidate items — including the §4.1 sentence — and stated it was not padding. That is a reviewer
resisting the manufacture pressure the round-5 prompt warned about, which makes the clean code
verdict more credible, not less.

**Kimi (third lens) is NOT called.** The standing rule is that it enters at round 6 *only if the pair
has not converged*. The pair has converged; spending a scarce resource to break a tie that does not
exist would be waste.

## The three findings — all prose lagging code

| # | Src | Sev | Defect |
|---|---|---|---|
| K1 | Codex | P3 | **The truth table and §4.1 are false under crash-at-any-instruction.** A crash between `reserveBackoff()`'s flush and `register` leaves `TAG_DECOY` durable having **never reached the relay**; likewise a caught pre-register failure whose `clearBackoff()` cannot flush. The documented row "fails before `register` → no tag" and §4.1's "or whose setup never reached the relay, is unaffected" hold only for catchable failures with a successful cleanup flush. Accurate boundary: **entering registration guarantees the tag; earlier paths remove it only if cleanup completes durably.** |
| K2 | Grok | P3 | **Invariant table W2 names the pre-H4 write path** — says `storeTokens`, code uses `storeTokensForAccount`. The field table's token writers omit **W2c (clear)**, which round 2's G6 made load-bearing. Blast radius is real though not a live bug: a future unit wiring refresh from the WRITER row alone calls `storeTokens`, which writes whatever account is current under the lock — **reopening the clearAccount/re-provision interleaving H4 closed.** |
| K3 | Grok | P3 | **The counter-invariant summary still teaches "mutate = durable"** — "only on a successful *mutate* do the RAM `next`/`limit` advance". That is round 1's F1 misconception verbatim, **still present four fix rounds later**, because the detailed W3 row was corrected and the abstract summary block above it was not. |

**K3 is the most instructive finding of the entire arc.** The single conceptual error that started
this whole review — treating `mutate` as durable — survived four rounds of fixes *in the summary
prose of the very document written to prevent it*. Detailed rows were corrected; the abstract
restatement was not. Recorded in `failures.md`: **when a misconception is corrected, grep for every
restatement of it, including the compressed/abstract ones — those are the copies that survive.**

## Ruling on K1 — stop chasing precision on this sentence

§4.1 has now been rewritten **four times**, and each version was found wrong by the next round in a
*different direction*: originally too broad, then understating, then overstating (the architect's
own proposal), now false-under-crash. Every rewrite was a good-faith attempt to state the exact
boundary, and every one failed, because the boundary depends on implementation details that keep
moving — precisely the fragility the maintainer named.

**The fix is to stop stating a precise boundary and give the reader something actionable instead.**
A disclosure's job is to let a user decide what to do, not to document a state machine. Final form:

> **What 0.10.0-beta specifically changes:** once a vault has **set up cover traffic**, it can no
> longer be opened by 0.9.x; downgrading will present that vault as corrupt. Setup begins the first
> time a vault sends cover traffic and is complete once its cover-traffic account is registered —
> and because an interrupted setup can leave the vault marked either way, **if you are unsure
> whether a vault got that far, assume it did.** A vault that has never used cover traffic is
> unaffected.

This is honest (it does not claim a boundary it cannot hold), covers the crash case without
enumerating it, and is **robust to future implementation drift** — U2/U3 can move when the tag is
written without falsifying it. It does not overstate harm; it tells a user how to resolve their own
uncertainty safely, which is the one thing the previous four versions never did.

Requires maintainer ratification — the fourth pass on a sentence they have already ratified once.
The internal truth table keeps its precision and **gains a crash row**; that is where exactness
belongs.

## Recommendation to the maintainer

**U1 is ready to merge once the three doc findings are corrected and confirmed.** The code has been
adversarially reviewed to clean convergence by two blind reviewers across five rounds. What remains
is prose accuracy in documents that do not ship in the APK.

Still owed before merge, and not this loop's to decide: the maintainer's merge decision itself, and
ratification of the §4.1 wording above.
