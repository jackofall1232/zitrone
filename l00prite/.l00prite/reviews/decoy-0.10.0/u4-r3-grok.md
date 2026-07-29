I'll review U4 as a complete feature, starting from §4.4 of the decoy-traffic spec, then the implementation and the round-1/2 fix surfaces you flagged.Next I'll read the coordinator guard, envelope builder, app wiring, and the U3 tripwire tests that U4 changed.# FINDINGS

No findings. After whole-unit review of §4.4 and the implementation (including the round-1/2 fix surfaces), I did not identify a reachable P1/P2/P3 defect that meets the calibration bar.

What was checked is summarised under CONFIRM-OR-REFUTE and the closing note.

---

# CONFIRM-OR-REFUTE

### 1. R-U4-1 — can a cover frame become a message? — **CONFIRM (guard holds)**

**Code as written**

- Guard is the first branch in `MessagingCoordinator.onMessageDeliver`, before `isDeletedContact` and before `signal.decrypt` (`MessagingCoordinator.kt:1901–1905` vs decrypt at `:1920–1925`).
- Production wiring reads the id per envelope, not at construction (`ZitroneApp.kt:1886–1888`):
  `DecoyAuthStore(rt).accountId?.let { it == senderId } == true`.
- Tripwires pin position, early return + bare ack, and the production lambda (`DecoyU4SourceTripwireTest`).

**Paths examined**

| Path | Outcome |
|---|---|
| Send-back on real socket (`sender_id` = synthetic) | Dropped before decrypt; bare `ws.ackMessage` |
| Vault closed mid-delivery | `runtime.read` throws → outer `runCatching` swallows → no decrypt (no ack either; redelivery). Still satisfies “never becomes a message” |
| Synthetic id null (unprovisioned) | Predicate false; no send-backs exist without a synthetic account/socket |
| Real contact id equals synthetic | Only UUID collision; not a practical path. Hostile relay labelling a real message with the synthetic id is the **named residual** (no new power vs drop) |
| Bare ack vs `ackDurable` | Sound for this branch: there is no RAM-only tombstone whose crash-restore would resurrect a real message. The deleted-contact branch needs durability of the *tombstone*; here the envelope is cover by definition of the key |

**No path found** into `signal.decrypt`, `MessageRepository` add/update, roster bump, unread, or `notificationScheduler` for a synthetic-sender envelope.

---

### 2. Changed U3 tripwires — weakened guard? — **REFUTE**

**Disconnect ownership** (`DecoySendPairingTest.kt:1315–1385`)

- Exemption is receiver-typed (`socket.` in `DecoyInboundSession`, `ws.` in `WsSyntheticSocket`), not file-wide.
- `DecoyU4SourceTripwireTest` pins: single `WsSyntheticSocket(...)` construction, first arg `syntheticWs`, binding from `syntheticSocket?.let { syntheticWs -> }`, exactly one `ws` binding and one `: WsClient` in the wrapper.
- A real-socket disconnect hidden as `ws.disconnect()` inside the wrapper requires a second `WsClient` (or alias), which those pins fail.
- A disconnect elsewhere still needs an allowed owner (`coverTraffic.stop` / `quiesce` / `coordinator.reconnectTransport`).

**Pressure wiring** (`DecoySendPairingTest.kt:1444–1483`)

- Whole `queuedBytes` lambda body is pinned to the exact sum, not token presence.
- Single `CoverPressure(` in app sources; pairing gets `pressure = coverPressure`.
- Synthetic `rate_limited` is pinned to `syntheticRateLimited`, and `relayRateLimited` wiring is asserted absent (`DecoyU4SourceTripwireTest`).

**Limit (not a defect):** tripwires remain lexical; a rename/indirection can still evade them. That is the standing property recorded in round-2 adjudication, not a U4 regression. Behavioural tests cover the mechanisms the tripwires name.

---

### 3. R-U4-4 yield — ack/burn exempt; send-back yields — **CONFIRM (reasoning sound)**

**Code**

- Ack/burn: no `CoverPressure` check (`DecoyInboundSession.kt:229–233`).
- Send-back: `pressure.yieldingSendBack()` after delay (`:246`).
- `yieldingSendBack` = `yielding()` ∨ synthetic off-window ∨ synthetic rate (`CoverPressure.kt:208–219`).
- Budgets: `recordSyntheticFrame` / `syntheticRateLimited` / `syntheticOffUntil` do **not** gate `yielding()`.
- Queues: production sums both sockets (`ZitroneApp.kt:1811–1814`).

**Attack: unbounded inbound flood**

- Acks are unbounded by `MAX_OUTSTANDING_WORK` (only burn/reply jobs are capped at 64).
- That can fill the synthetic outbound queue; the **sum** then makes `yielding()` true and arms the **real** `offUntil` for 60s.
- That re-uses the shared-uplink path round 2 explicitly accepted (“suppressing pairing cover because the synthetic socket is congested is acceptable”).
- It is **not** F2 reintroduced via pure signal: one synthetic `rate_limited` no longer blacks out real cover; queue pressure implies real bytes on a shared uplink.
- Unacked covers under load would leave durable relay retry artefacts — the disclosure/degradation argument for keeping acks is sound.
- Relay-driven flood is already full DoS power under the threat model; the cap bounds *our* memory/CPU, not the relay.

**Send-back → real inbound:** send-backs are addressed to the real account and consume real inbound routing; they are dropped by R-U4-1 with bare ack. No crypto/store path. Latency under load is degradation, not a failed real send.

---

### 4. Lifecycle — **CONFIRM (no outlive-lock / permanent kill found)**

| Concern | Evidence |
|---|---|
| Socket outlives vault lock | `bindTo` stops synthetic **before** pairing drain (`DecoyInboundSession.kt:361–364`); `stop` is terminal (`stopped` never cleared); dial/disconnect share `lock` with start’s connect (`:167–171`, `:212–215`) |
| `start` after `stop` | No-op (`:155–157`, tests for reconnect-after-stop) |
| Transport swap leaves old endpoints | `applyTransportLocked` updates `decoyWsClient` (`ZitroneApp.kt:1581`); `applyTransport` always `reconnect()`s synthetic **outside** the real-socket DISCONNECTED gate (`:1538–1557`) — the round-1 P1 fix |
| Transport toggle permanently kills cover | `quiesce` not wrapped by `bindTo` (`:366`); only `stop` is terminal; reconnect is non-terminal (`:186–193`) |
| Lazy provision `start()` vs teardown | `start` re-checks `stopped` under `connecting` and under `lock` after token read; `accessToken` failures release without latching forever |
| Process-scoped `reconnect` after lock | `stopped` makes reconnect a no-op; token read is `runCatching` |

**Residual (declared, not a defect):** synthetic JWT expiry is quiet (`onAuthExpired` dropped); cover stays off until session rebuild (R-U4-6).

---

### 5. R-U4-2 / R-U4-3 — no crypto, no durable writer — **CONFIRM**

`DecoyInboundSession` constructor dependencies: scope, id/token lambdas, `SyntheticSocket`, `CoverPressure`, `DecoyEnvelopeBuilder`, `SecureRandom`, sleep.

- No `SignalProtocolManager`, vault store, `runtime.mutate`, `DecoySectionLock`, `storeTokensForAccount`, `flushBeforeAck`.
- `WsSyntheticSocket` only holds `WsClient` + callbacks; drops non-deliver events.
- `buildReply` needs no `Sender` / registration id / identity key (`DecoyEnvelopeBuilder.kt:374–411`).
- Forbidden-symbol tripwire on U4 sources (`DecoyU4SourceTripwireTest`).
- `replyCounter` is in-memory `AtomicInteger` only.

§4 WRITER/READER table unchanged by U4 as a dependency claim: **holds**.

---

### 6. `buildReply` — **CONFIRM (plausible; residuals declared)**

- Always established-session shape (`ephemeralKey`/`preKeyId` null) — correct X3DH reply shape and avoids durable `registration_id`.
- Ciphertext byte length mirrors received; fails closed on undersized blobs.
- Mirrors TTL, burn, media type, version, timestamp width.
- Full `message.send` frame length is **not** asserted (unlike `build()`); kdoc states the frame is shorter when the received cover was prekey-shaped — true of real replies too.
- In-process counter restart at 0 is intentional and matches ratchet-turn cleartext behaviour; not a durable leak.
- `previous_chain_length` fixed at 0: consistent with cover blobs / short first chains; residual class is relay-visible, already in §2.4.

---

### 7. Anything else — **no additional defect**

Checked and cleared for this unit:

- Lock order: `connecting` (mutex) → `lock`; `stop` only takes `lock`; no invert with `CoverPressure.meter`.
- `MAX_OUTSTANDING_WORK` bounds burn/reply jobs; acks stay unbounded by design.
- Exceptions in send-back path are swallowed (R-U4-6); cannot enter the real publish `runCatching` as FAILED.
- `yielding()` / `yieldingSendBack()` fail closed to yield on supplier throw.
- Synthetic `message.stored` / typing / etc. dropped; real-side `markSent`/`onRemoteBurn` for unknown cover ids are no-ops.
- Round-2 two-budget split: synthetic rate/off-window does not leak into `yielding()`; tests pin asymmetry (`DecoyInboundSessionTest`).

**Requirements themselves:** R-U4-1…6 remain satisfiable as behaviour-of-our-code rules. The two-budget model is a refinement of R-U4-4, not a contradiction. I did not find a requirement that is unsatisfiable or so weak that a real defect slips through unremarked.

---

# MISSING CONTEXT

| Gap | What would settle it |
|---|---|
| No behavioural (instrumented) test of `isSyntheticSender` on a live `MessagingCoordinator` inbound path | A unit test constructing the coordinator with a synthetic id and asserting no decrypt/store/notify |
| End-to-end double-socket against a real relay under Tor queue pressure | Measurement of whether synthetic ack floods can starve real `message.send` latency beyond CoverPressure’s cover-only yield (threat model may still call this out of scope) |
| Whether production always supplies `decoyRelay` on every unlock path | Only `buildVaultSession` was checked; it always passes a factory |

---

**VERDICT: CLEAN**

Checked: §4.4 R-U4-1…6 against constructor dependencies and call sites; R-U4-1 placement and bare-ack argument; two-budget `CoverPressure` isolation and summed queue; dial/stop monitor; transport dual-redial; `bindTo` teardown order; `buildReply` shape/size; both modified U3 tripwires and U4 construction pins; flood/cap/yield interactions. Absence of a finding is not a proof of correctness.
