# U3 R1 — Adversarial Security Review (Grok, blind)

**Unit:** 0.10.0-beta decoy traffic — Unit U3 (send pairing)  
**Branch:** `feat/0.10.0-decoy-u3-pairing` @ `f57e8cec` (unit commit `ba5a6b9e`)  
**Base:** `main` @ `4438cd72`  
**Scope reviewed:** whole unit (`DecoySendPairing.kt`, `MessagingCoordinator` wiring, `ZitroneApp`/`SessionContainer` wiring, `SignalProtocolManager.localIdentitySerialized`, `DecoySendPairingTest.kt`), against spec §4.3 R-U3-1…R-U3-5 and the nine attack points. U1/U2 code # U3 R1 — Adversarial Security Review (Grok, blind)

Whole unit on `feat/0.10.0-decoy-u3-pairing` (`ba5a6b9e` / `f57e8cec`), against spec §4.3. Source-resolved only; the other reviewer's report was not read. Full write-up: `l00prite/.l00prite/reviews/decoy-0.10.0/u3-r1-grok.md`.

---

## What holds

- **Exactly-once real publish** via latched one-shot `real()` / `finally` — double-publish is prevented; cover failures do not swallow the real send for the throw types production uses.
- **`flushSendRatchet` still before `ws.sendMessage`** at all three sites; seam is after a successful flush.
- **Order/gap:** `SecureRandom` by type; closed-interval 5–50 ms; plan (vault read, build, RNG) runs **before** the pairing lock — removes the known flush-asymmetry timing leak.
- **R-U3-3 predicate:** `DecoyAuthStore.accountId` only; not flapping `canSend()`/`capacityExceeded`. Flush-before-seam argument is valid on the confined worker.
- **Every choke-point class paired** (text / attachment control / receipt); receipt-detector reasoning is sound.
- **Provisioning:** lazy, one job latch, silent degradation; registration spend still bounded by U1’s runtime gate.
- **R-U3-5 for this type:** no durable write, no logging/diagnostics, no slot naming; `stop()` cancels the provision job.
- **No parallel-copy** of `DecoyEnvelopeBuilder` / `TAG_DECOY` field set.

---

## Findings

### F1 — P2 — Decoy-first can steal the last `sendLimit` token and fail the real send (R-U3-1)

**Where:** `DecoySendPairing.kt:263–266`, `ZitroneApp.kt:1725`, `server/internal/ws/hub.go:158–162`, `MessagingCoordinator.kt:2120–2123`

**Sequence:**
1. `sendLimit` is 100/min per **authenticated** account; rate limiting defaults **on**.
2. Cover frames are sent **as the real account** on the **real** WebSocket → each decoy consumes one real-account token.
3. At count = 99, draw `decoyFirst = true`: decoy accepted (count → 100); real rejected with `rate_limited` (no `message.stored`).
4. Client only learns enqueue success (`ws.sendMessage` → true). `onServerError` is a no-op → real bubble can stay **SENDING forever**.

Without the decoy, that real frame would have been accepted. That is a real send **failed because cover traffic was attempted** — R-U3-1 absolute. Spec §6.3’s “humans won’t approach 100/min” is not a carve-out from R-U3-1.

**Why tests miss it:** fake `send` lambda; no server limiter; no assertion that cover must not consume a token the real frame needs.

---

### F2 — P3 — Gap floor does not match the kdoc claim under OkHttp

**Where:** `DecoySendPairing.kt:148–151, 367–370`; `WsClient.kt:211–212`

`sleep` is between **enqueues**, not wire writes. A blocked OkHttp writer (Tor/I2P) can collapse the wire gap to ~0 regardless of a 5 ms floor. The relevant observer surface is **TLS record sizes**, not TCP segment packing. Equal-length still comes from the builder + separate WS frames; the floor is an unmeasured heuristic.

**Why tests miss it:** gap tests never observe the socket/TLS/writer.

---

### F3 — P3 — “Waits at most `GAP_MAX_MS`” is false under multi-waiter load

**Where:** `DecoySendPairing.kt:173–176, 234`; confined `limitedParallelism(1)` allows other sends to queue on `window` during sleep.

Wait for the last of N concurrent pairings is up to **~N × 50 ms**, not 50 ms. Bulk multi-contact receipts / concurrent sends can push tail latency into “material delay” territory under R-U3-1’s own framing.

**Why tests miss it:** concurrent test only checks order for two sends, not a bound.

---

### F4 — P3 — Cancel-while-waiting-for-lock emits **outside** `window`

**Where:** `DecoySendPairing.kt:261–284`

If B is cancelled while acquiring `window` while A holds it mid-gap, B’s `finally` runs `real`/`decoy` **without** the lock → interleaving and real-send reorder, the hazards the lock claims to prevent.

**Partial production mitigation:** vault lock does `ws.disconnect()` before `scope.cancel()`, so the socket is usually dead and nothing hits the wire. The path is still wrong for cancel-without-disconnect (and for any future per-send cancel).

**Why tests miss it:** only cancel-during-gap is tested, not cancel-during-acquire.

---

### F5 — P3 — Latent: `emit` rethrows `CancellationException` after latching → can skip `real()` in decoy-first `finally`

**Where:** `DecoySendPairing.kt:256–259, 334–341, 277–279`

If cover `send` ever throws `CancellationException` on the decoy-first `finally` path, `decoyDone` is already true and `real()` never runs. Today `WsClient.send` only returns a boolean — **latent**, not live.

**Why tests miss it:** throwing-socket test uses `IOException` (swallowed).

---

### F6 — P3 — §4.2 synthetic delete still unimplemented; U3 made registration spend reachable

**Where:** `ZitroneApp.kt:1726–1731`; `deleteAccountAndWipe` has no synthetic cleanup; spec §4.2

U1 was deliberately unwired; U3 wires `provisionIfNeeded`. Orphan synthetic accounts on real-account delete are now a live residual (spec-allowed if documented/fail-open; not yet done).

**Why tests miss it:** no delete×decoy composition test.

---

### F7 — P3 — Gate tests kill mutations but leave R-U3-1 edges uncovered

M14/M15 fixed two undiscriminated tests — good. Still no discriminator for F1, F3, F4, F5, coordinator integration (a forgotten `coverTraffic.paired` site would pass the unit suite), or TLS non-coalescing.

---

## VERDICT: FINDINGS (0 P1, 1 P2, 6 P3)

Core pairing structure is sound for R-U3-2/3/4/5. The absolute rule **R-U3-1** fails at the shared `sendLimit` boundary when the decoy goes first (F1). Everything else is bounded correctness, documentation, or test-coverage debt.
 0-delay enqueues usually produce separate WebSocket frames / TLS records. The threat that matters to a passive observer is **TLS record sizes**, not TCP segment packing; the kdoc argues at the wrong layer.

**Outcome:** the equal-length property still comes from the builder + separate WS frames; the floor is not a demonstrated defense against “one double-length frame.” It is an unmeasured heuristic. Not an order-bit leak by itself.

**Why tests do not catch it**

- Gap tests inject `sleep = { drawn = it }` / virtual `delay` and never observe the socket, TLS records, or OkHttp writer.

---

### F3 — P3 — Pairing-lock wait bound is wrong under multi-waiter load (R-U3-1 “materially delayed”)

**File:line**
- `DecoySendPairing.kt:173–176` (“a concurrent send waits at most `GAP_MAX_MS`”)
- `DecoySendPairing.kt:234` (`window = Mutex()`)
- `MessagingCoordinator.kt:328` (`confined = Dispatchers.IO.limitedParallelism(1)` — suspension in `paired` lets other confined work run)

**Concrete failure**

1. Send A acquires `window`, decoy-first, sleeps up to 50 ms.
2. While A is suspended, B and C (other outbound `message.send`s) reach `paired` and queue on `window`.
3. Wait for the last waiter is the **sum** of remaining hold times ≈ up to `N × GAP_MAX_MS`, not `GAP_MAX_MS`.

Bulk multi-contact receipt flushes or concurrent text/attachment sends can push tail latency into hundreds of ms. Spec R-U3-1 forbids **material** delay; the unit’s own defense is “≤50 ms,” which is false under concurrency.

**Why tests do not catch it**

- `a pairing in flight delays a concurrent send but never overtakes it` uses **two** sends and only asserts order, not a bound.

---

### F4 — P3 — `finally` emits the pair **without** `window` after cancel-during-lock-wait (lock invariant incomplete)

**File:line**
- `DecoySendPairing.kt:261–284` (`withLock` in `try`; unpaired `decoy()`/`real()` in `finally`)
- Claim in class kdoc / commit message that cancel-while-waiting still publishes exactly once (true) **and** that the lock prevents interleaving (false on this path)

**Concrete failure**

1. A holds `window`, mid-gap.
2. B is suspended in `window.withLock` acquire.
3. B is cancelled → `withLock` throws `CancellationException` → B’s `finally` runs `real()` then `decoy()` (or the reverse) **without** holding `window`.
4. Wire (if the socket is still live): B’s frames can land between A’s decoy and A’s real, and B’s real can overtake A’s real.

That is exactly the interleaving/reorder hazard the lock exists to prevent.

**Production mitigation (partial):** `UnlockController.lockCurrent` runs `coordinator.stop()` → `ws.disconnect()` **before** `sessionScope.cancel()`. After disconnect, `send` returns `false` and nothing hits the wire, so the reorder is usually not network-visible on vault lock. The path remains wrong for any cancel that does not kill the socket first (tests cancel without disconnect; any future per-send cancellation would inherit the bug).

**Why tests do not catch it**

- Cancel test only cancels **inside the gap** of a single pairing (`cancellation inside the drawn gap…`).
- Concurrent test never cancels the waiter.

---

### F5 — P3 — Latent R-U3-1: `emit` rethrows `CancellationException` after latching, which can skip `real()` in `finally`

**File:line**
- `DecoySendPairing.kt:256–259` (latch `decoyDone` then `emit`)
- `DecoySendPairing.kt:334–341` (`catch (c: CancellationException) throw c`)
- `DecoySendPairing.kt:277–279` (decoy-first `finally`: `decoy()` then `real()`)

**Concrete failure**

1. Cancel while waiting for the lock; `finally` runs with `decoyFirst == true`.
2. `decoy()` sets `decoyDone = true`, then `emit` → if `send` ever throws `CancellationException`, it is rethrown.
3. `real()` in the same `finally` never runs. The real publish is lost; `realDone` stays false and nothing retries.

Today `WsClient.send` does not throw `CancellationException` (boolean return). This is a **latent** footgun: the latch-before-call pattern that protects against double-publish also prevents a second attempt, and CE is the one throwable `emit` does not swallow. A one-line future change to a suspending/cancellable send would turn this into a live R-U3-1 defect.

**Why tests do not catch it**

- Throwing-socket test throws `IOException`, which is swallowed.
- No test makes `send` throw `CancellationException` on the cover frame in the decoy-first `finally` path.

---

### F6 — P3 — §4.2 synthetic-account delete still unimplemented; U3 made registration spend reachable

**File:line**
- Spec §4.2 (ruling: best-effort synthetic delete, or drop it and document residual)
- `ZitroneApp.kt:1726–1731` (first production call site of `provisionIfNeeded`)
- `MessagingCoordinator.deleteAccountAndWipe` — no decoy/synthetic cleanup (verified by absence)

**Concrete failure**

U1 shipped provisioning deliberately unwired. U3 wires it. A vault that sends will register a synthetic account. Account delete/wipe still only deletes the **real** relay account. Spec allows orphan as residual **if** documented and not entangled with the delete state machine; the residual is now live on this branch without the U6 honesty line and without a best-effort synthetic delete.

Not a deniability break (relay already conceded; orphan is inert). Process/gate debt elevated from “before U3” to “owed on this branch.”

**Why tests do not catch it**

- U3 suite never exercises account delete.
- U1 tests cover provisioner isolation, not delete composition.

---

### F7 — P3 — Test suite blind spots (mutations killed tests, not all properties)

**File:line** — `DecoySendPairingTest.kt` (whole gate)

| Named property | Would a break fail a test? |
|---|---|
| Uniform independent order | **Yes** (fraction + runs test). |
| Gap bounds / uniform / independent of order | **Yes.** |
| Default `SecureRandom` not a fixed stream | **Yes** (weak: only two instances disagree). |
| Equal frame length via real builder | **Yes** for fixtures used. |
| All envelope classes paired | **Yes** at unit seam; **not** at `MessagingCoordinator` (a site that forgot `coverTraffic.paired` would still pass). |
| Build refusal / missing identity / unreadable section / socket throw → real still once | **Yes.** |
| Cancel mid-gap → both frames | **Yes.** |
| Lock prevents overtake (happy path) | **Yes** for two concurrent sends. |
| Provision once then cover | **Yes** against the injected lambda; **not** against real `DecoyAccountProvisioner` + relay budget. |
| `stop` cancels provision job | **Yes.** |
| `NONE` publishes once | **Yes** (M14). |
| Dead socket false ≠ error | **Yes** (M15). |
| **F1 rate-limit token steal** | **No.** |
| **F3 multi-waiter delay bound** | **No.** |
| **F4 cancel-during-lock-wait** | **No.** |
| **F5 CE from cover `send`** | **No.** |
| **flush→send ordering in the real coordinator** | **No** integration test. |
| **TCP/TLS non-coalescing** | **No.** |

M14/M15 fixing undiscriminated tests is real hygiene; it does not imply the property list is complete. Several R-U3-1 edge paths have no discriminator.

---

## Attack-point map (short)

1. **Real send every path:** holds for production throw types tested; latent F5; rate-limit F1 fails “never failed by cover.”
2. **Timing residue:** pre-window planning removes the known flush asymmetry; residual `contactExists` cost is only on the real leg (µs-scale map lookup — not raised as a finding). Wire gap ≠ drawn gap under blocked writer (F2).
3. **Order predictability:** SecureRandom + statistical tests hold; reasoning about LCG recovery holds.
4. **Gap floor:** claim not established (F2).
5. **Pairing lock:** happy-path reorder prevention holds; cancel-during-wait unlocks emission (F4); wait bound overstated (F3).
6. **Uniformity predicate:** hasAccount choice holds; flush-before-seam argument holds on confined.
7. **Every envelope / sendLimit:** pairing scope holds; doubled volume vs `sendLimit` creates F1.
8. **Provisioning budget:** lazy, one attempt, silent — holds; §4.2 residual F6.
9. **Deniability:** no durable/log/diag in U3 path; locked vault: stop disconnects then cancels — holds for practical teardown.

---

## Verdict

**VERDICT: FINDINGS (0 P1, 1 P2, 6 P3)**

The unit’s core structure (latched one-shot publish, plan-before-window, SecureRandom order/gap, hasAccount predicate, all choke-point sites, silent fire-and-forget provision) is sound against R-U3-2/3/4/5 as implemented. The absolute rule R-U3-1 is broken at the shared `sendLimit` boundary when the decoy goes first (F1). Remaining items are bounded correctness, documentation, and test-coverage gaps.

---

*Reviewer: Grok (blind R1). Source tree only. 2026-07-27.*
