# 0.10.1 send-failure surfacing — review round 3 adjudication

**Codex: 0 P1, 0 P2, 1 P3. Grok: 0 P1, 0 P2, 1 P3. SAME finding, independently.** First round in
this stream with **no P1 and no P2 from either lens**, and **all five round-2 fixes CONFIRMED against
source by both.**

| Round | Codex | Grok |
|---|---|---|
| 1 | 1 P1, 1 P2, 1 P3 | 0 P1, 2 P2, 2 P3 |
| 2 | 1 P1, 0 P2, 1 P3 | 1 P1, 0 P2, 2 P3 |
| 3 | **0 P1, 0 P2, 1 P3** | **0 P1, 0 P2, 1 P3** |

## The finding — UPHELD, and it was in code written the same day

Both lenses: **three places still described the PRE-MERGE relay**, claiming the send budget is checked
before the envelope is parsed so `rate_limited` "frequently"/"most likely" carries no id. Verified
against merged `hub.go:169-186`: `handleSend` unmarshals the header **first**, then rate-limits, and
the server's own `TestHandleSend_RateLimited_CarriesMessageID` pins that the ordinary case **does**
carry the id.

- `net/WsClient.kt:132-135` — **the authoritative API kdoc on the path**, and round 2 claimed this
  class of comment had been corrected. It corrected `MessageRepository`, `DecoySendPairing` and the
  coordinator prose, and **missed `WsClient` entirely.**
- `MessageRepositoryTest.kt` — the timeout test's own setup rationale.
- `ServerErrorRouterTest.kt` — **written today, in the same session that fixed this claim elsewhere.**
  The stale rationale was propagated into brand-new code while being removed from old code.

**Behaviour was never wrong** and the timeout remains justified (parse/UUID failures, lost frames,
older relays). What was wrong is calling the unattributable case the *likely* one — the
overclaim-frequency error, recurring. **All three fixed;** grep for the claim now returns zero.

## The five fixes — CONFIRMED by both, with two observations worth keeping

1. **Timeout at the handoff.** Both confirm `publishOutgoing` is the sole socket handoff for text,
   attachments and retries, arming only after `ws.sendMessage` returns true, with nothing arming at
   `addOutgoing` or `retryable`. Codex added a detail I had not written down: **read receipts
   deliberately use a separate path because they own no user bubble**, so their absence from the
   arming point is correct rather than a gap.
2. **`clearAll` disarm** — complete across lock, Pucker Burn, logout, revocation, deletion, and scope
   cancellation.
3. **Conditional self-removal — CONFIRMED REACHABLE**, which is the ruling that decides the guard
   belongs. Codex traced it: after the old timer flips SENDING→FAILED its thread can be descheduled;
   UI observation and retry run on other dispatchers, reach a new handoff, and replace the map entry
   before the old timer resumes, so `remove(messageId, ownJob)` preserves the replacement. **Not
   unreachable-by-construction** (round 0's deleted `isMine`), so keeping it is consistent.
4. **Healing docs** — no remaining comment or test name advocates FAILED monotonicity against
   receipts.
5. **Relay-order comments** — refuted in part; that refutation is the finding above.

**Tripwire relaxation: CONFIRMED appropriate by both.** Codex's framing is the honest one: the
adjacency form was *syntactically* stronger, but adjacency was never R-U3-1, brace-walking still
catches the return escaping the branch, and **the relaxed form does not behaviourally test timeout
wiring — but neither did the original.** So nothing real was lost. **R-U3-1 confirmed untouched**:
arming is strictly after `ws.sendMessage` returns; nothing was added ahead of any real handoff.

## THE HARNESS SPLIT PERSISTS — but one lens REFUTED the other's premise, and the refutation checks out

- **Codex: harness required before merge.** Grounds: three same-shaped escapes mean lexical
  assertions are not an adequate merge gate. It explicitly rejects "another pure routing function" as
  the answer.
- **Grok: asserted-is-enough for THIS merge; the harness is residual debt, not a gate on 0.10.1.**

**Grok's point 3 is the sharpest thing said across three rounds, and it directly refutes Codex's
evidential premise.** Codex's whole case rests on *"the missing harness is what let round 2's P1
through."* Grok shows that is not accurate: round 2's P1 was arm-at-`addOutgoing` timing relative to
blob upload; **`MessageRepository` is constructible**, and what was missing was the *design insight*
that bubble ≠ handoff, plus its test — **the `ServerErrorRouter` extraction would not have caught it
either.**

**Verified against this project's own record, not taken on trust:** round 2's mutation **M-r2a was
exactly that regression, and it was caught by `no timeout is armed before the send is handed off` — a
test on the constructible `MessageRepository`, no coordinator harness involved.** So Codex's premise
is false as stated. The harness is owed for *other* reasons (three escapes is real pattern evidence
that asserted-only coordinator guts keep bleeding), but **not because it was the only thing that could
have caught round 2's P1.**

**Not adjudicated as a merge decision — merge is the maintainer's.** Recorded as: **both lenses agree
more seams/harness are owed; they disagree only on whether it gates 0.10.1, and the evidential
argument for gating is now weaker than when it was made.**

## Acted on: the cheap seam Grok named as still unexploited

Grok's point 5 — pin that the handoff branch calls `armSendTimeout`, no Robolectric, no constructed
coordinator. Implemented beside the ownership assertion on the same brace-walked branch. It closes
the one wiring fact a repository test cannot reach: `MessageRepositoryTest` can prove `addOutgoing`
does *not* arm (and does), but only source can show **where arming moved to**.

**Mutation-verified, and this is the outcome that matters:** re-introducing round 2's P1 (arming back
at `addOutgoing`) is now caught by **three tests from two independent angles** — the behavioural
`addOutgoing`-does-not-arm and `clearAll` tests, *and* the new source pin. Before round 2 it was
caught by nothing.

## Evidence

`ci-gradle :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL, exit 0,
821 tests / 0 failures / 3 skipped.** Round-3 mutation: 1 applied (arming back at `addOutgoing`), **3
tests discriminated it.**

## Standing residuals (declared, unchanged)

- No test constructs `MessagingCoordinator`; coordinator wiring is **asserted, not tested**.
- The conditional-removal race and the cancel-vs-CAS redundancy need a controllable dispatcher with a
  barrier; a single-threaded virtual clock cannot express either.
- Live `deliverAttachment` + slow body + timer interaction is untested end to end.

**Three rounds remain before the cap.** Round 4 should attack the round-3 delta: the three comment
corrections and the new handoff pin.
