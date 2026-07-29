I'll conduct a full-unit adversarial review of U4 (synthetic receive), starting from §4.4 of the decoy-traffic spec and then reading the implementation, wiring, and tests read-only.Reading §4.4 and the U4 implementation files next — I'll stay blind to the other reviewer's notes.Next I'll inspect the R-U4-1 guard, app wiring, shared pressure/pairing code, and the tripwire tests.Digging into the ownership tripwires, bare-ack path, and remaining durable-sink surfaces.# FINDINGS

## F1 — P2 — Synthetic socket still writes durable diagnostics (round-4 logging ban incomplete)

**Where:** `ZitroneApp.kt:1813` wires production; sink is `BootDiagnostics.record` → `file.writeText` (`BootDiagnostics.kt:81–86`); sources are `WsClient` lifecycle `diag(...)` calls (`WsClient.kt:235,254,266,282,287`). Guard/tripwire only cover `MessagingCoordinator`’s R-U4-1 block and the two U4 files (`DecoyU4SourceTripwireTest.kt:94–117`).

**Sequence (reachable):**
1. Vault unlock with decoy relay; synthetic account already provisioned (or appears after first covered send).
2. `SessionContainer` builds `WsSyntheticSocket(..., diag = { line -> bootDiagnostics.record(line) })`.
3. `DecoyInboundSession.start()` → `WsClient.connect` → `diag("ws[…]: firing WS /ws handshake")` and `diag("ws: connected")`.
4. Each line is timestamped and appended to `boot-diagnostics.log`, and shown in Settings → Diagnostics.
5. Vault lock / transport swap: further `ws: closed` / reconnect lines for the **second** socket.

**Wrong outcome:** Durable, user-visible evidence of a second authenticated WebSocket lifecycle. That is the same deniability class round 4 fixed for the explicit `diag("cover-account…")` line: on-disk proof this device ran the dual-socket cover path. It also fails **R-U4-6** as written (“a failed … or **connection** is dropped **silently**… never surfaced”) for synthetic handshake failures (`ws: handshake/stream failed…`), which surface in Diagnostics with no synthetic UI need.

The real socket legitimately uses the same sink for connectivity UX. The synthetic account has no UX; the default `diag = {}` on `WsSyntheticSocket` is the correct silent sink, and production overrides it.

**Why tests miss it:** The round-4 tripwire bans `diag(`, `Log.`, `println`, `BootDiagnostics` only inside the R-U4-1 guard body and inside `DecoyInboundSession.kt` / `WsSyntheticSocket.kt` after comment strip. Production wiring lives in `ZitroneApp.kt`; the actual `diag(` call sites live in `WsClient.kt`. Parameter name `diag` without a call, and a `(String) -> Unit` sink, are invisible to the ban. No test asserts that the synthetic socket is constructed with a no-op diag.

**Related incomplete ban (same class, not separately scored):** an `import android.util.Log as L` / `L.w` or a generic sink would also slip the substring list; the structural hole is “U4 may accept a logging sink and hand it to `WsClient`.”

---

## F2 — P3 — Restored redial-position tripwire does not pin independence from the real socket’s gate

**Where:** `DecoyU4SourceTripwireTest.kt:166–189` (asserts `redial > gateEnd`); production currently correct at `ZitroneApp.kt:1538–1557`.

**Sequence (evasion that keeps the suite green):**
```kotlin
if (live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {
    live.coordinator.reconnectTransport {
        live.wsClient.disconnect()
        live.apiClient.accessToken?.let(live.wsClient::connect)
    }
}
// tokens still present; redial is after first gate's closing brace
if (live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {
    live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }
}
```
`gateEnd` is the first `\n        }` after the real connect line (end of the **first** gate). `redial > gateEnd` holds while the synthetic redial is again gated on the real socket being non-`DISCONNECTED` — exactly round 1’s P1 (synthetic left on old endpoints when the real socket is down and self-redials via backoff).

**Wrong outcome:** The tripwire claims to pin “not gated on the real socket’s connection state”; it only pins “lexically after one particular brace,” which is not that property.

**Why tests miss it:** Position-vs-token was an improvement over pure presence, but still a local string geometry check. No structural pin (e.g. that no `connectionState` read dominates the redial) and no behavioural test of “real DISCONNECTED + synthetic was CONNECTED + transport change ⇒ synthetic still redials.”

---

## F3 — P3 — R-U4-3 is still too weak as literally written (requirement defect)

**Where:** Spec §4.4 R-U4-3 (`DECOY_TRAFFIC_0.10.0_SPEC.md` ~1323–1339): “no new persisted field and no new writer to `TAG_DECOY` or any other section.”

**Why the requirement is the defect:** F1 is a durable U4-driven writer that is **not** a vault section field and is **not** `runtime.mutate` / `DecoySectionLock` / `storeTokensForAccount` — the only checks the requirement and the kdoc on `DecoyInboundSession` name. Round 4 already showed “no durable-state writer” was too narrow for a durable diagnostic; the rewritten requirement was not updated, so the same class (F1) remains permitted by the letter of R-U4-3 while violating product deniability and R-U4-6 silence.

**Wrong outcome:** Reviewers and tripwires that implement R-U4-3 as written can pass while durable cover-related evidence still lands on disk.

**Why tests miss it:** Forbidden-dependency tripwire (`DecoyU4SourceTripwireTest.kt:73–90`) matches crypto/vault writers only, not diagnostics sinks or production wiring.

---

# CONFIRM-OR-REFUTE

### 1. R-U4-1 — can a cover frame become a message? — **REFUTE** (as implemented; residuals named)

**Evidence:**
- Guard at `MessagingCoordinator.kt:1915–1918` is first inbound branch before `isDeletedContact` and before `signal.decrypt` (~1933); tripwire pins order (`DecoyU4SourceTripwireTest.kt:29–52`).
- Production wiring reads id per call (`ZitroneApp.kt:1911–1913`), not a captured null.
- `isSyntheticSender` throwing after `runtime.close()` is inside `runCatching` → no decrypt (fail closed for crypto; ack may be skipped — degradation).
- Confined single-thread worker serialises stop/teardown vs deliver for the non-suspending synthetic path.
- Bare `ws.ackMessage` vs `ackDurable`: correct for true cover (nothing real to durability-gate). Hostile relay relabelling a real envelope with the synthetic id can bare-ack it away — **named residual**, no new power (relay can drop). False positive would need synthetic `accountId` equal to a real contact’s id (server UUID collision / absurd provisioning) — not a practical path.
- Cover-shaped `message.stored` / `message.burned` on the real socket no-op on unknown ids (`MessageRepository.markSent` / `burn` CAS).

No path found from synthetic `sender_id` to decrypt, store, roster, unread, or notification under normal vault state.

### 2. Changed U3 tripwires (disconnect exemption + pressure wiring) — **REFUTE** (weakened-guard attacks failed)

**Disconnect exemption:** Receiver-typed (`socket.` / `ws.`) in `DecoySendPairingTest.kt:1349–1364`, plus `::disconnect` ban (`1376–1389`). `WsSyntheticSocket` constructs its only `WsClient` (`WsSyntheticSocket.kt:86`; tripwire `DecoyU4SourceTripwireTest.kt:227–258` forbids any `: WsClient` declaration and requires exactly one `WsClient(`). Cannot inject the real client without renaming the type property the compiler enforces. Residual: reflection / `Any` + `getMethod("disconnect")` could avoid both tokens — not used in tree; not a realistic maintenance footgun.

**Pressure wiring:** Exact lambda body sum of both queues (`DecoySendPairingTest.kt:1474–1480`); single `CoverPressure(` outside the class; `pressure = coverPressure` for pairing; synthetic `rate_limited` → `syntheticRateLimited` only. Hoist does not reintroduce a second meter.

### 3. R-U4-4 yield (ack/burn exempt) — **CONFIRM** reasoning is sound

**Evidence:** Send-back yields via `pressure.yieldingSendBack()` after delay (`DecoyInboundSession.kt:244–246`); ack/burn do not (`225–233`). Spec and class kdoc: unacked cover → relay redelivery → durable, load-disclosing artefact. Inbound work capped at `MAX_OUTSTANDING_WORK = 64` (`277–289`) so burns/replies cannot grow without bound. Acks remain unbounded on purpose; they share uplink with the real socket (bandwidth contention under a hostile flood) but do not fill the real socket’s OkHttp queue, and CoverPressure’s summed queue still sheds optional cover. Residual (by design): synthetic flood can contend for radio/uplink; not a silent real-message loss path.

### 4. Lifecycle — **REFUTE** outlive-lock / permanent-kill defects; residual noted

**Evidence:**
- `bindTo` stops synthetic **before** pairing drain (`DecoyInboundSession.kt:361–364`); `MessagingCoordinator.coverTeardown` → `coverTraffic.stop { ws.disconnect() }`.
- `stop` is terminal (`stopped` never cleared); `reconnect` non-terminal and used on transport swap (`ZitroneApp.kt:1557`); `quiesce` deliberately not wrapped (`366`).
- Endpoints updated under lock (`applyTransportLocked` `decoySocket?.updateTransport`); redial outside real-state gate in **current** code (`1557`).
- `start`/`stop` dial atomicity under shared monitor (round-1 fix); mutex across suspending token read (round-1 fix). Tests cover concurrent start/reconnect and stop-during-dial.
- Residual: redial is `AppContainer.scope.launch`, not session-scoped — may run after lock but hits `stopped` and no-ops. Residual: expired synthetic JWT quiet until rebuild (declared).

### 5. R-U4-2 / R-U4-3 (constructor dependencies) — **CONFIRM** for crypto/vault; **REFUTE** absolute “no durable writer” in product sense

**Evidence:** `DecoyInboundSession` constructor: scope, id lambdas, token, `SyntheticSocket`, `CoverPressure`, builder, RNG, sleep — no `SignalProtocolManager`, no `VaultRuntime`, no mutate/section lock. `buildReply` only. In-memory `replyCounter` only. **But** production synthetic socket path writes BootDiagnostics (F1) — outside the typed dependency list, so R-U4-3-as-written can hold while deniability does not.

### 6. `buildReply` — **CONFIRM** plausible established-session shape; residuals OK

**Evidence:** Always `ephemeralKey = null`, `preKeyId = null` (`DecoyEnvelopeBuilder.kt:396–411`); ciphertext byte length matched (`387–394`); refuses wrong account / short ciphertext (`DecoyReplyBuilderTest`). Frame intentionally shorter than prekey-shaped received (kdoc). Does **not** assert full `sendFrameLength` (unlike `build`) — fine for unpaired send-backs; counter decimal width can differ from received. `previousChainLength = 0` matches Android real sends. In-process counter restart at 0 is declared residual (matches post-ratchet real behaviour); not a wire distinguisher unique to decoys beyond residual 2.

### 7. Anything else — **no additional P1/P2**

Checked: lock ordering on `connecting`/`lock`; no deadlock with pairing drain; exceptions in tracked jobs swallowed (R-U4-6); cover path cannot throw into real send (`cover` after handoff); kdoc/spec claims about no crypto largely match; dual-socket pressure sum present; synthetic rate channel separated.

Round-4 fixes re-attacked: (1) logging ban incomplete → **F1**; (2) `::disconnect` ban sound for its claim; (3) position assertion incomplete → **F2**; (4) `WsClient` declaration ban sound for injection class.

---

# MISSING CONTEXT

- No end-to-end behavioural test of `MessagingCoordinator` + live `isSyntheticSender` against a synthetic-shaped deliver (only source tripwires). A wrong default wiring would be caught by the wiring tripwire; a subtle vault-read failure mode would not.
- Did not run the unit test suite (read-only review).
- Did not instrument a device to confirm dual `ws: connected` lines in a real Diagnostics dump (reasoning is from static wiring + `WsClient`/`BootDiagnostics` code).
- Relay behaviour for cover `message.stored` / burn relay to the real account assumed from client handlers (unknown id → no-op); server code not re-audited this round.
- Blind to the other lens and to any uncommitted review notes under `l00prite/.l00prite/reviews/`.

**What was checked:** Spec §4.4; `DecoyInboundSession`, `WsSyntheticSocket`, `DecoyEnvelopeBuilder.buildReply`, `CoverPressure`, `DecoySendPairing`/`CoverTraffic`, `MessagingCoordinator.onMessageDeliver` + stop/teardown, `ZitroneApp` session/transport wiring, `WsClient` diag/dispatch, `BootDiagnostics.record`, and the listed tests including both changed U3 tripwires and all U4 source tripwires. Focused attack on the four round-4 fixes and the seven mandated questions.

---

**VERDICT: FINDINGS (0 P1, 1 P2, 2 P3)**
