I'll conduct a full-unit adversarial review of U4 (read-only). Starting with the spec §4.4 and the core implementation files.Next I'll read the wiring, guards, builder, pressure policy, and the changed U3 tripwires.# FINDINGS

## U4-R4-1 — Round-3 fix deleted the redial-independence tripwire; the survivor does not pin the load-bearing property

**Severity:** P3  
**Where:** `DecoyU4SourceTripwireTest.kt` (missing test; weak remainder at ~152–162); production still correct at `ZitroneApp.kt:1528–1557`

**Sequence**

1. Round 1 fixed a real defect: when the real socket was `DISCONNECTED`, transport swap skipped the synthetic redial and left cover on the old endpoints (Codex P1).
2. That was pinned by `the synthetic redial is not gated on the real socket's connection state`, which required the synthetic `reconnect()` call to sit **outside** the real socket’s connection-state `if`.
3. Commit `684a6143` (round-3 type fix) **deleted that test** while restoring only the adjacent `rate_limited` tripwire. Adjudication notes the silent deletion problem but only records restoring the rate-limit pin.
4. What remains is substring presence only:

```152:162:apps/android/app/src/test/java/com/zitrone/app/DecoyU4SourceTripwireTest.kt
    fun `a transport swap re-points and redials the synthetic socket too`() {
        val app = read("ZitroneApp.kt")
        // ...
            app.contains("live?.decoySocket?.updateTransport(httpClient, ws)"),
        // ...
            app.contains("live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }"),
```

5. Re-nest the redial inside the real gate:

```kotlin
if (live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {
    live.coordinator.reconnectTransport { /* real */ }
    live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }
}
// synthetic reconnect removed from here
```

Both `contains` checks still pass. Full unit suite stays green.

**Wrong outcome**

On Tor/I2P toggle while the real socket is down: `updateTransport` updates the *next* dial’s endpoints, but the live synthetic WebSocket is never disconnected/redialled, so cover control frames keep flowing on the transport the user just left — the disclosure round 1 already named.

**Why tests miss it**

The position/ordering assertion was deleted; the replacement only checks that two tokens appear somewhere in the file.

---

## U4-R4-2 — Disconnect-ownership exemption is structural for `WsSyntheticSocket` itself, but the tripwire carve-out is still lexical

**Severity:** P3  
**Where:** `DecoySendPairingTest.kt:1349–1364`; production type at `WsSyntheticSocket.kt:48–97`

**Sequence**

1. Round 3 correctly removed the `WsClient` injection point. `WsSyntheticSocket` constructs its own client; the real socket cannot be assigned into that type.
2. U3’s ownership tripwire still exempts by **file name + receiver spelling**:

```1363:1364:apps/android/app/src/test/java/com/zitrone/app/DecoySendPairingTest.kt
                if (name == "DecoyInboundSession.kt" && precedes(code, at, "socket.")) continue
                if (name == "WsSyntheticSocket.kt" && precedes(code, at, "ws.")) continue
```

3. Next evasion (tests stay green): in `WsSyntheticSocket.kt` add a same-file helper that is not the production wrapper type:

```kotlin
internal fun disconnectClient(ws: WsClient) = ws.disconnect()
```

4. From e.g. `applyTransport` / any non-owner: `disconnectClient(live.wsClient)` — no `disconnect()` token at the call site; the only `disconnect()` is `ws.disconnect()` inside the exempted file.

**Wrong outcome**

A real-socket disconnect can again sit outside `coverTraffic.stop` / `quiesce` / `reconnectTransport`, reopening the split-pair class the ownership guard exists to prevent — while every tripwire remains green.

**Why tests miss it**

The structural fix pins “this class cannot *be handed* the real client.” The tripwire still pins “any `ws.disconnect()` in this *file*,” which is a wider and still lexical set. Production today does not take the evasion; the guard does not guard what its comment claims for the whole file.

---

# CONFIRM-OR-REFUTE

### 1. R-U4-1 — can a cover frame become a message?

**REFUTE (as a production path into message UX / crypto).**

- Guard is first in `onMessageDeliver`, before `isDeletedContact` and before `signal.decrypt` (`MessagingCoordinator.kt:1901–1925`).
- Production wiring reads the id per envelope: `DecoyAuthStore(rt).accountId?.let { it == senderId } == true` (`ZitroneApp.kt:1911–1913`); null id ⇒ false, not a permanent open capture.
- Send-backs are established-session shape (`ephemeralKey == null`), so a missed guard would hit non-PreKey decrypt and fail without TOFU — but the guard’s placement is still what blocks PreKey-shaped forgeries labelled with the synthetic id.
- **Vault closed mid-delivery:** `runtime.read` throws; `runCatching` fails before decrypt; `classifyRecvFailure` → `SWALLOW`; no store/roster/notification path.
- **Bare ack vs `ackDurable`:** argument holds. This branch keys on “sender is cover account,” not on a RAM-only tombstone that could roll back into a real contact. The envelope is cover; losing the relay copy cannot lose a real message. Named residual: hostile relay can mislabel a real message with the synthetic id and get it dropped — same power as dropping it outright (spec §4.4).
- **Id collision with a real contact:** relay-assigned UUIDs; not a realistic honest-path collision. Not treated as a code defect.

### 2. The two changed U3 tripwires

**CONFIRM residual weakness (see findings); REFUTE that production currently mis-wires them.**

| Tripwire | Production | Test |
|---|---|---|
| Disconnect ownership + synthetic exemption | Structural: wrapper owns the only client; `decoySocket: WsSyntheticSocket?` exposes no raw client to steal | Still file+`ws.` lexical — **U4-R4-2** |
| Pressure wiring after hoist | One `CoverPressure`, queue body is exact sum of both sockets, `pressure = coverPressure` to pairing/inbound, synthetic `rate_limited` → `syntheticRateLimited` | Exact body pin is strong; hard to hide always-0 supplier |

### 3. R-U4-4 — the yield

**REFUTE as a real-send harm; exemption reasoning is sound.**

- Send-back uses `pressure.yieldingSendBack()` after the delay (`DecoyInboundSession.kt:246`).
- Ack/burn deliberately do not yield; unacked cover would make load durable and observable on the relay.
- Shared uplink: queues are **summed** (`ZitroneApp.kt:1831–1834`); synthetic congestion can shed pairing cover (discardable half), not delay the real `ws.sendMessage` path.
- Two-budget split: `recordSyntheticFrame` / `syntheticRateLimited` only gate send-backs; `yielding()` stays false under pure synthetic load (tests in `DecoyInboundSessionTest`).
- `MAX_OUTSTANDING_WORK = 64` bounds burns/replies; acks remain unbounded by design under inbound flood. That is contention **degradation**, not disclosure, and the relay is already conceded for direct DoS.

### 4. Lifecycle

**REFUTE permanent kill / lock-outlive / broken transport follow in current production.**

- `bindTo` stops synthetic **before** pairing drain (`DecoyInboundSession.kt:361–364`); terminal `stop` only.
- `quiesce` is not wrapped — transport toggle does not permanently kill U4 (`bindTo` kdoc + test).
- `applyTransport`: endpoints under lock on both sockets; synthetic `reconnect()` always scheduled; real redial gated alone (`ZitroneApp.kt:1528–1557`).
- `start`/`reconnect` serialised by `connecting` mutex; dial under same monitor as `stop` disconnect (R1 fix).
- Lazy provision calls `inbound?.start()`; unlock also `scope.launch { start() }`; idempotent when connected.
- **Residual:** independence of synthetic redial from real `DISCONNECTED` is **unmonitored** after round 3 (**U4-R4-1**). Code is currently correct.

### 5. R-U4-2 / R-U4-3 — no crypto, no durable writer

**CONFIRM.**

Constructor dependencies of `DecoyInboundSession`: scope, id/token lambdas, `SyntheticSocket`, `CoverPressure`, builder, random, sleep. No `SignalProtocolManager`, `VaultRuntime`, `runtime.mutate`, `DecoySectionLock`, `storeTokensForAccount`. `buildReply` takes no `Sender` and needs no `registration_id`. Source tripwire strips comments and forbids those symbols in U4 files.

### 6. `buildReply`

**REFUTE malformation as a silent emit; residuals are declared.**

- Always established-session shape; mirrors ciphertext **byte** length, timestamp width, TTL/burn/media/version.
- Full `message.send` frame is shorter than a prekey-shaped cover it answers — correct for real X3DH replies too (kdoc at `DecoyEnvelopeBuilder.kt:361–365`).
- Short ciphertext fails closed (`bodyLengthFor`); `sendBack` drops via `runCatching`.
- In-memory `replyCounter` restart at 0 is intentional (ratchet-turn shape); same family as §2.3 residual 2, not a new durable leak.

### 7. Anything else (round-3 fix surface)

| Topic | Result |
|---|---|
| `coverPressureRef` late bind | Safe in production: assigned before any `start()`/dial; object not published mid-init. `?.` silently drops `rate_limited` only if order regresses (degradation). |
| Own `WsClient` vs U3 disconnect model | Does not break pairing ownership; synthetic disconnect still cannot split a real/cover pair. Construction-failure path never dials before throw; `runtime.close()` still runs. |
| `internal val listener` | Test seam; `ws` stays private. Same-module misuse can only *invoke* the installed listener, not substitute the socket. Not a ship defect. |
| Exemption structural? | **Yes for the wrapper type.** **No for the tripwire carve-out** (U4-R4-2). |
| §4.4 requirements after 3 rounds | Still written as behaviour rules with in-doc falsifications; none found unsatisfiable or so weak they bless a real-message harm. R-U4-4’s ack exemption is part of the requirement, not a contradiction. |

---

# MISSING CONTEXT

- Did not re-run the full unit suite or mutation sweep this round (read-only review of source + git history).
- Did not instrument OkHttp’s shared task runner under a synthetic ack flood; residual cross-socket scheduler contention is reasoned, not measured.
- Did not prove UUID uniqueness properties of the relay’s account allocator beyond ordinary registration assumptions.

**Checked (production + tests, whole unit):** §4.4 R-U4-1…6; `DecoyInboundSession`, `WsSyntheticSocket` (round-3 shape), `buildReply`, `CoverPressure` two-budget/summed queue, `MessagingCoordinator.onMessageDeliver` guard + bare-ack argument, `SessionContainer` init/`coverPressureRef`/`bindTo`, `applyTransport`/`applyTransportLocked`, U3 tripwire edits, U4 source tripwires, inbound session/reply/socket tests, lock teardown order (`coordinator.stop` then `runtime.close`).

**VERDICT: FINDINGS (0 P1, 0 P2, 2 P3)**
