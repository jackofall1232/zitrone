I'll conduct a full-unit adversarial review of U4 (read-only): start with §4.4 of the decoy spec, then walk the listed implementation and tests.Reading the U4 requirements and core implementation next.The first read of `DecoyInboundSession` may be stale — re-reading the current file.Uncommitted R1 fixes are on disk; re-checking them against the residual `start`/`stop` race.Reviewed the **committed unit** at `e7e1a41b` / `3d655ba1` (R1 dispatch baseline). Spec §4.4 and the full U4 surface (inbound session, socket adapter, builder, coordinator guard, wiring, U3 context, tests) were read end-to-end. Uncommitted WIP on disk was not treated as the review baseline.

---

# FINDINGS

### F1 — P1 — Synthetic socket left on old transport when real socket is DISCONNECTED
**File:line:** `ZitroneApp.kt` `applyTransportLocked` (~1568–1570) + `applyTransport` (~1527–1546)

**Sequence:**
1. Live session has synthetic WS up; real WS is `DISCONNECTED` (backoff after a blip is enough).
2. User toggles Tor/I2P → `applyTransport` → `applyTransportLocked`.
3. Both clients get `updateTransport(...)` (fields only; open connections are unchanged).
4. Return is `live?.takeIf { real.connectionState != DISCONNECTED }` → **null**.
5. `applyTransport` returns without `reconnectTransport` **and without** `decoyInbound.reconnect()`.

**Wrong outcome:** Synthetic stays on the **old** connection/endpoints while the user believes that transport is off. Comments claim the opposite. Real eventually redials via `WsClient` backoff onto new endpoints; synthetic does not. That is the R-U4-6-class disclosure U4 wiring exists to prevent (cover attributable on a transport the user turned off).

**Why tests miss it:** `DecoyU4SourceTripwireTest` only asserts the strings `updateTransport` / `reconnect()` exist, not that redial is independent of real connection state. No behavioural test of “real DISCONNECTED + transport toggle.”

---

### F2 — P1 — `start()` can reconnect the synthetic socket after `stop()` (vault lock)
**File:line:** `DecoyInboundSession.kt` `start` (~133–145) / `stop` (~173–182); `WsClient.connect` (~159–163)

**Sequence:**
1. Unlock path or provision path: `scope.launch { session.start() }` (or mid-`start`).
2. `start` passes `if (stopped)`, claims latch / proceeds past post-token `if (stopped)`.
3. Concurrent vault lock: `coverTraffic.stop` → `DecoyInboundSession.stop()` sets `stopped=true`, nulls `onDeliver`, `socket.disconnect()` (`intentionallyClosed=true`).
4. `start` continues: `socket.onDeliver = ...`; `socket.connect(token)` → `intentionallyClosed=false` and a **new** open.
5. Real socket is already torn down by the same `stop` chain; session scope may cancel later, but OkHttp keeps the orphan socket alive.

**Wrong outcome:** Synthetic flow stays up across lock while the real flow is down — exactly the “disclose lock by contrast” case named in §4.4 / class kdoc. Auto-reconnect keeps it up after failures.

**Why tests miss it:** Teardown tests are single-threaded (`start` then `stop`). No concurrent `start`×`stop`. No assert that `connect` is impossible after terminal `stop`.

---

### F3 — P2 — `reconnect` races the `AtomicBoolean` latch and can double-dial
**File:line:** `DecoyInboundSession.kt` `start` / `reconnect` (committed latch version)

**Sequence:**
1. `start` CAS `starting=true`, parks in or past `accessToken()`.
2. Transport swap: `reconnect()` → `disconnect()`, `starting=false`, nested `start()` CAS succeeds and connects.
3. First `start` resumes and connects again.

**Wrong outcome:** Two handshakes for one transport change; flapping synthetic connection. Cover/control traffic looks wrong under a user-visible transport toggle (degradation / structure leak, not content).

**Why tests miss it:** `reconnect` tests are serial. No overlapping `start`+`reconnect`.

---

### F4 — P2 — Unbounded concurrent burn/reply work; acks never yield; pressure is real-socket-only
**File:line:** `DecoyInboundSession.onCoverDelivered` (~191–201), `launchTracked` (~238–252); `CoverPressure` wired to `wsClient::outboundQueueBytes` only; `WsSyntheticSocket.onServerError` drops `rate_limited`

**Sequence:**
1. Sustained `message.deliver` rate to the synthetic account (redelivery storm if acks fail, or high cover rate).
2. Each delivery: immediate `ack` (no pressure), plus burn job (~20–40 ms), sometimes reply job (1.5–7.5 s).
3. `pending` only shrinks on completion — **no admission cap** in committed code. Kdoc claims growth is bounded by deregistration; that bounds *leaked finished jobs*, not *concurrent* work.
4. `CoverPressure` sees only the **real** socket queue and frames recorded on the real send path; synthetic outbound and synthetic `rate_limited` are invisible.

**Wrong outcome:** Cover-side control traffic and coroutine load can contend for device uplink/CPU with real traffic under load. Send-backs may still yield when the *real* meter trips; acks/burns never do (by design). Exemption reasoning for acks is sound against redelivery-disclosure; it is **incomplete** as a shared-resource bound (no concurrent cap, no synthetic queue signal). That is a soft spot in R-U4-4 as implemented, not a false requirement.

**Why tests miss it:** Yield test checks one delivery under a tripped real queue. No flood / admission-cap test. Tripwires pin one shared `CoverPressure` instance, not synthetic-side bounds.

---

### F5 — P3 — Production `accessToken` never refreshes; kdoc claims it may
**File:line:** `DecoyInboundSession` kdoc on `accessToken` (~72–74); `ZitroneApp` wiring `accessToken = { DecoyAuthStore(rt).accessToken }` (~1794); `WsSyntheticSocket` drops `onAuthExpired`

**Sequence:** Synthetic JWT expires mid-session → socket auth fails → `onAuthExpired` no-ops → synthetic side stays quiet until session rebuild.

**Wrong outcome:** Silent cover degradation (allowed by R-U4-6), but the constructor contract is inaccurate. Residual is real; the “may refresh” wording is not.

**Why tests miss it:** No expiry/reconnect behavioural test for the synthetic socket.

---

# CONFIRM-OR-REFUTE

### 1. R-U4-1 — can a cover frame become a message?
**Mostly CONFIRM (satisfied as written); bare-ack argument holds.**

Evidence:
- Guard is first in `onMessageDeliver`, before `signal.decrypt` (`MessagingCoordinator.kt` ~1901–1905).
- Wired per-envelope via `DecoyAuthStore(rt).accountId` (`ZitroneApp.kt` ~1859–1861), not a captured null.
- `buildReply` is established-session shape only → even a failed-open guard + random blob takes non-PreKey decrypt (no TOFU); PreKey TOFU residual is the hostile-relay case the spec already names.
- Vault closed: `runtime.read` throws → `runCatching` → `SWALLOW` → no decrypt, no store/roster/notify.
- **Bare ack:** Correct for this branch: envelope must never be a user message; `ackDurable` exists to protect *real* messages keyed on still-RAM tombstones. Hostile relay forging `sender_id` = synthetic can drop a real message — **no new power** vs dropping it (spec residual). Crash before durable synthetic id after bare ack only loses cover (degradation).

No path found to message store / roster / unread / notification for synthetic-sender envelopes when the guard runs. Path to *miss* the guard (null id after crash) still does not surface content for established-session junk.

### 2. Changed U3 tripwires
**REFUTE as a ship-blocking weakened guard; residual remains on injection pin quality.**

- Disconnect exemption is **receiver-typed** (`socket.` / `ws.`), not a file carve-out; harm model (split pairing on **real** socket) is coherent.
- Safety of `WsSyntheticSocket.ws.disconnect` depends on decoy-only injection — pinned by `DecoyU4SourceTripwireTest` construction scan (`syntheticWs` only).
- Pressure tripwire rewrite still requires `CoverPressure(queuedBytes = wsClient::outboundQueueBytes)`, single construction app-wide, and `pressure = coverPressure`. Hiding a mis-wired second meter fails the count; always-0 queue fails the string.

Cannot keep all green while disconnecting the **real** socket outside allowed owners, or while wiring pressure to a no-op queue, without also defeating those pins. Residual: construction pin is string/AST-shallow (rename/`alsoDisconnect` games), same class as other source tripwires.

### 3. R-U4-4 yield
**CONFIRM requirement intent; PARTIAL REFUTE of completeness of the implementation (see F4).**

- Send-back consults shared `CoverPressure` after delay — correct.
- Ack/burn exemption is the right disclosure-vs-degradation trade for *redelivery artefacts*.
- Incomplete: no concurrent work cap (committed), pressure is real-socket-only, synthetic `rate_limited` dropped — shared uplink contention is not fully subordinated.

### 4. Lifecycle
**REFUTE “socket never outlives session” / “transport toggle always moves synthetic” as structural guarantees (F1, F2, F3).**

- `bindTo` teardown order (synthetic before drain) is correct and tested.
- `quiesce` not wrapping synthetic stop is correct (terminal `stop` would kill cover permanently).
- Failures: F1 transport gate; F2 start/stop race; F3 double-dial. Lazy provision `start` + unlock `start` idempotence is intentional; races with **terminal** stop are not closed.

### 5. R-U4-2 / R-U4-3
**CONFIRM.**

`DecoyInboundSession` constructor: scope, id/token lambdas, `SyntheticSocket`, `CoverPressure`, builder, random, sleep. No `SignalProtocolManager`, vault mutate, `DecoySectionLock`, `storeTokensForAccount`, `flushBeforeAck`. Tripwire forbids those substrings in U4 files. `buildReply` needs no registration id. In-memory `replyCounter` only.

### 6. `buildReply`
**CONFIRM plausible established-session reply; no durable leak from counter restart.**

- Always `ephemeralKey`/`preKeyId` null; ciphertext length matched to received; metadata mirrored; fails closed on short ciphertext / wrong account.
- Frame shorter than prekey-shaped received is intentional and protocol-correct.
- Counter restart at 0 matches ratchet-turn behaviour; §2.3 residual 2 is reduced, not introduced as a new durable writer.
- Does **not** assert full `sendFrameLength` equality (unlike `build`) — documented choice, not a silent defect.

### 7. Anything else
**Additional defects: F1–F5.** No exception path from U4 into a real publish tail found. `diag("recv: cover-account envelope…")` logs to logcat (on-device), not a network observer issue. Claim “set cannot grow without bound” is only true for completed-job leaks, not concurrent admission (F4).

---

# MISSING CONTEXT

- No device/integration run of transport toggle with real socket already DISCONNECTED.
- No concurrent stress of `start`×`stop` or `start`×`reconnect`.
- Did not re-measure libsignal wire sizes for `buildReply` (relied on builder maths + unit tests).
- **Baseline:** review is of **committed** unit (`e7e1a41b`). Working tree had uncommitted edits labelled as other-lens R1 fixes; those were not the reviewed artifact.

---

**What was checked for a non-CLEAN path:** R-U4-1 placement and wiring; bare vs durable ack; constructor dependencies; tripwire changes; pressure sharing; yield exemptions; start/reconnect/stop/bindTo/applyTransport; `buildReply`; U3 pairing/pressure context; U4 tests/tripwires.

**VERDICT: FINDINGS (2 P1, 2 P2, 1 P3)**
