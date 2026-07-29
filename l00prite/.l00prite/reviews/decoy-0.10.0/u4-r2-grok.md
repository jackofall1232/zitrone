I'll conduct a full-unit adversarial review of U4 (read-only). Starting with §4.4 of the decoy traffic spec, then the implementation and tests.Next I'll read the R-U4-1 guard, wiring, envelope builder, and the U3/U4 tests.The queue wiring may have changed — re-reading the current `SessionContainer` init and related production code.Uncommitted changes exist on the branch. Re-reading the modified files (and not the other lens’s review).Checking one more edge case on the pressure tripwire and whether `recordFrame` for send-backs is tested.# FINDINGS

## F1 — P2 — Weakened U3 pressure-wiring tripwire after the U4 hoist/sum rewrite
**Where:** `DecoySendPairingTest.kt` ~1453–1464 (working tree); production wiring `ZitroneApp.kt` ~1807–1810

**Sequence**
1. U3 pinned the queue reading as a single method-reference argument:  
   `CoverPressure(queuedBytes = wsClient::outboundQueueBytes)`.
2. U4 hoisted the meter, then (working tree) rewrote the supplier to sum both sockets.
3. The tripwire was rewritten to two independent substring checks:
   - `"wsClient.outboundQueueBytes()" in app`
   - `"syntheticSocket?.outboundQueueBytes()" in app`
4. Neither assertion requires that expression to be the **return value** of `queuedBytes`.

**Concrete evasion (tests stay green):**
```kotlin
val coverPressure = CoverPressure(
    queuedBytes = {
        wsClient.outboundQueueBytes()
        syntheticSocket?.outboundQueueBytes()
        0L   // last expression — meter always sees an empty queue
    },
)
```
Both call sites appear in source; single `CoverPressure(` remains; `pressure = coverPressure` still matches.

**Wrong outcome**  
Outbound-queue yield is disabled while every tripwire is green. Cover can fill OkHttp’s buffer again — the failure mode this tripwire was invented to catch (U3 round 5). Real sends are not automatically broken, but the guard no longer prevents the path that can break them.

**Why tests miss it**  
The tripwire checks presence of tokens, not dataflow into `CoverPressure`’s answer. No behavioural test builds the production `SessionContainer` graph.

---

## F2 — P2 — Synthetic-account budget signals silence real-path cover (fix 5 + send-back `recordFrame`)
**Where:**  
- `WsSyntheticSocket.kt` ~58–60 + `ZitroneApp.kt` ~1818 (`coverPressure::relayRateLimited`)  
- `DecoyInboundSession.kt` ~266 (`pressure.recordFrame()` after synthetic `send`)  
- Shared consumer: `CoverPressure.relayRateLimited` / `recordFrame` / `yielding` (~132–186)

**Sequence (rate_limited lever — no real-path pressure required)**
1. Synthetic socket is live; user is not near the real account’s send limit.
2. Relay emits `error` / `rate_limited` on the **synthetic** connection (busy synthetic sends, or a single hostile frame — relay is free to emit it).
3. `WsSyntheticSocket.onServerError` → `coverPressure.relayRateLimited()` → `offUntil = now + 60s`.
4. Next real send’s `DecoySendPairing.cover` calls `pressure.yielding()` → true → **no paired cover**.
5. U4 send-backs also yield. Real frames go out uncovered for a full `OFF_WINDOW_MS`.

**Sequence (rate meter via send-backs)**
1. `CoverPressure.RATE_FRAMES` (40) and its kdoc are calibrated for the **real** account’s ~100/min bucket (real + paired cover on one socket).
2. Working-tree U4 also does `pressure.recordFrame()` on accepted **synthetic** send-backs (`DecoyInboundSession.kt:266`).
3. Those frames charge the **synthetic** relay account, not the real one, but they still fill the shared ring.
4. A hostile or busy relay can deliver cover-shaped envelopes to the synthetic account; ~1/4 draw send-backs; 40 recorded send-backs in 60s arm the same off-window without any real user send.
5. Subsequent real sends are unpaired for up to 60s.

**Wrong outcome**  
Cover on the real send path is defeated by contention (or a single error code) on a **different** per-account budget. Real messages are not delayed or failed (not P1), but the cover layer is switched off in a way the real socket’s own load does not justify.

**Why this is a defect rather than “R-U4-4 as written”**  
R-U4-4 correctly demands **one** yield policy and shared uplink awareness (queue sum is the right direction). It does **not** require that the synthetic account’s `rate_limited` / send-count arm the real account’s cover blackout. `CoverPressure`’s own kdoc still claims counted frames “charge the same per-account relay bucket” (`CoverPressure.kt` ~124–130, ~217–221) — false once synthetic send-backs call `recordFrame`.

**Why tests miss it**  
- Wiring tripwires only assert the method reference exists (`DecoyU4SourceTripwireTest` rate_limited test; production string match).
- No test that a synthetic `rate_limited` (or N synthetic `recordFrame`s) leaves a later real `cover()` uncovered while the real queue and real send rate are quiet.
- `DecoyInboundSessionTest` never asserts `recordFrame` side effects.

**Disclosure vs degradation**  
Degradation only (observer sees unpaired reals). Acceptable under load on the **real** path; here the blackout is driven by the **synthetic** path. Relay is conceded and could drop cover anyway — but this makes the **client** stop generating cover for a full minute after a synthetic-only signal, which is a sharper, more consistent mark than intermittent drops.

---

## F3 — P3 — `applyTransportLocked` kdoc still describes the pre-fix-1 contract
**Where:** `ZitroneApp.kt` ~1560–1566 vs body ~1568–1579

**Sequence / mismatch**  
Round-1 fix correctly made `applyTransportLocked` always return the live session (endpoints updated for both sockets; redial decided per socket in `applyTransport`). The kdoc still says the return is null “when … its socket is already down — a down socket redials itself through WsClient’s own backoff”.

**Wrong outcome**  
Reviewers and later editors re-learn the **buggy** contract. The redial tripwire pins code shape, not this comment, so the lie survives.

**Why tests miss it**  
Source tripwires do not assert kdoc text.

---

## F4 — P3 — `CoverPressure.recordFrame` contract text is false under U4
**Where:** `CoverPressure.kt` ~124–130; caller `DecoyInboundSession.kt` ~261–266

**Mismatch**  
Kdoc: frames recorded are real or cover halves that “charge the same per-account relay bucket”.  
U4: synthetic send-backs record into that meter but authenticate as the synthetic account.

**Wrong outcome**  
The rate threshold’s “≥60 permits left for real sends” story (`RATE_FRAMES` kdoc) is no longer a statement about one bucket. Maintenance will re-tune thresholds under a false model.

**Why tests miss it**  
No contract test; behavioural tests never inspect the shared meter after a send-back.

---

# CONFIRM-OR-REFUTE

### 1. R-U4-1 — can a cover frame become a message?
**REFUTE (as a shippable path under normal lifecycle).**

Evidence:
- Guard is first in `onMessageDeliver`, before `isDeletedContact` and before `signal.decrypt` (`MessagingCoordinator.kt` ~1901–1925).
- Production wiring reads id per envelope: `DecoyAuthStore(rt).accountId?.let { it == senderId } == true` (`ZitroneApp.kt` ~1882–1884) — not a captured null.
- Only decrypt site for WS delivers is this path; tripwire pins order (`DecoyU4SourceTripwireTest`).
- Teardown order is `coordinator.stop()` (sets `acceptingDeliveries = false`, then `coverTraffic.stop` → synthetic stop) **before** `runtime.close()` (`ZitroneApp.kt` stopSession ~964–969), so the id is still readable while deliveries are drained/stopped.
- Send-backs are established-session shape (`ephemeralKey == null`); even a fail-open decrypt would take the `SignalMessage` / no-session path, not PreKey TOFU. PreKey TOFU risk is why the guard sits first — and for honest U4 traffic the blob never carries `ephemeral_key`.
- **Bare ack:** argument holds for this branch. Tombstone needs `ackDurable` because a real message might still be wanted after crash-restore; a synthetic-sender envelope is cover by construction. Hostile relay labelling a real message with the synthetic id is the named residual (same power as dropping it). UUID collision with a real contact is not a practical path.
- Residual only: if `accountId` is null the predicate is false (fail-open). No production `DecoyAuthStore.clearAccount` mid-session caller found; wipe path closes the session first.

### 2. The two changed U3 tripwires
**CONFIRM partial weakening on pressure; REFUTE practical real-socket disconnect hide.**

- **Disconnect ownership:** receiver-typed exemption for `socket.` / `ws.` is sound if injection is pinned. Injection is pinned to `syntheticWs` from `syntheticSocket?.let` plus single `ws` property in `WsSyntheticSocket` (working-tree tripwire). Hiding a **real** `disconnect()` elsewhere still fails the global owner walk. Residual: all of this is still lexical.
- **Pressure wiring:** **CONFIRM weakened** — see F1. After the hoist/sum rewrite you can keep every test green with a meter that always yields “queue empty”.

### 3. R-U4-4 — yield; ack/burn exempt
**CONFIRM exemption reasoning is sound; CONFIRM residual contention; CONFIRM fix-5 lever (F2).**

- Shedding acks would leave the relay retrying cover deliveries → durable observable of load. Exempting ack/burn matches R-U3 disclosure-vs-degradation.
- Send-back yields after delay via shared `CoverPressure` (`DecoyInboundSession.kt` ~244–246).
- Acks/burns do not yield; under a **relay** inbound flood they consume shared uplink. Bound on burns/replies is `MAX_OUTSTANDING_WORK = 64`; acks unbounded by design. Relay is conceded for DoS.
- Shared meter + synthetic `rate_limited` / `recordFrame` → F2 (real-path cover off without real-path pressure).
- Queue **sum** (working tree) correctly reflects shared uplink; that part of R-U4-4 is improved, not regressed.

### 4. Lifecycle (start / reconnect / stop / bindTo / transport)
**REFUTE permanent kill / outlive-lock; CONFIRM fix-1 redial split holds.**

- `bindTo.stop` stops synthetic **before** pairing drain (`DecoyInboundSession.kt` ~356–364); tests pin order.
- `quiesce` is not wrapped — transport toggle does not terminal-stop U4; `applyTransport` always `reconnect()`s synthetic outside the real socket’s DISCONNECTED gate (`ZitroneApp.kt` ~1527–1557).
- Endpoints installed under `transportLock` for both clients (`applyTransportLocked` ~1573–1577).
- `start` check+dial under same monitor as `stop`’s disconnect; concurrent dial test with real threads.
- `connecting` Mutex serialises start/reconnect across suspending token read (R1 P2 fix).
- Lock order is only `connecting → lock` or `lock` alone; completion handlers take `lock` outside `stop`’s cancel loop — no deadlock found with `connecting`.
- Lazy provision calls `inbound?.start()`; unlock also launches `start()`; both idempotent under `connecting`.
- Residual: expired synthetic JWT → quiet until rebuild (declared R-U4-6). `connected` not cleared on `stop` but `stopped` is terminal and never cleared.

### 5. R-U4-2 / R-U4-3 — no crypto, no durable writer
**CONFIRM.**

- `DecoyInboundSession` constructor: scope, id lambdas, token, `SyntheticSocket`, `CoverPressure`, builder, random, sleep — no `SignalProtocolManager`, no `VaultRuntime`, no section lock.
- Forbidden-symbol tripwire on U4 files (comments stripped).
- `buildReply` only; no `builder.build(`.
- No `runtime.mutate` / `DecoySectionLock` / `storeTokensForAccount` in U4 types.
- In-memory `replyCounter` only (R-U4-3).

### 6. `buildReply`
**REFUTE malformation as a ship path; residual size/counter notes only.**

- Always established-session shape; tests pin it.
- Ciphertext length matched; fails closed if too short for a padded block.
- Requires `replyingAccountId == received.recipientId`.
- Frame may be shorter than a prekey-shaped received envelope (omitted fields) — intentional and true of real replies.
- Counter restarts at 0 per process — same class as a ratchet turn; §2.3 residual, not a new leak unique to U4.
- Does not measure full JSON frame equality (unlike `build`) — acceptable for a temporally separate reply.

### 7. Anything else (deadlock, growth, exceptions into real send, kdoc lies)
**CONFIRM F3–F4 kdoc issues; REFUTE deadlock / unbounded jobs / exception into real send.**

- Outstanding burns/replies capped at 64; set deregisters on completion.
- Ordinary traffic (paired covers, 20–40 ms burns, rare 1.5–7.5 s replies) does not approach the cap; flood only with non-ordinary inbound rates.
- `bindTo.cover` / `onRelayRateLimited` are pure delegates; U4 failures are `runCatching` / silent job catch — cannot mark a real send FAILED.
- Round-1 dial-vs-stop fix: lock-order-safe against completion handlers (cancel outside the disconnect monitor).

---

# MISSING CONTEXT

- Did not run the Android unit suite (read-only review); tripwire evasion was reasoned from source, not executed.
- Did not verify relay `rate_limited` emission rules in `server/internal/ws/hub.go` (whether acks/burns can trigger it, or only `message.send`).
- Did not measure OkHttp dual-socket queue behaviour under ack storms on a device.
- Working tree has **uncommitted** edits to `ZitroneApp.kt`, `DecoyInboundSession.kt`, and two test files (queue sum, send-back `recordFrame`, tripwire rewrites). Findings target that tree; HEAD alone would instead re-open “meter blind to synthetic queue” as an open R-U4-4 gap (now addressed in WIP, with F1 as the cost of the tripwire rewrite).
- Did not read sibling round-2 review artefacts (blind).

**Checked without finding P1:** R-U4-1 placement and wiring; bare-ack rationale vs tombstone; constructor dependency surface for R-U4-2/3; `bindTo` / transport redial split; start↔stop dial race fix; connecting Mutex; MAX_OUTSTANDING_WORK vs ordinary rates; lock order; send-back yield; buildReply shape/size fail-closed; disconnect-injection pins.

---

**VERDICT: FINDINGS (0 P1, 2 P2, 2 P3)**
