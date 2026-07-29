I'll conduct a full-unit adversarial review of U4 (read-only). Starting with the protocol, §4.4 requirements, and the implementation/test surface.Reading §4.4 requirements and the full U4 implementation.Continuing through tests, tripwires, and sink/disclosure paths.Checking one more edge: whether a shared `OkHttpClient` or real-socket diagnostics can observe the synthetic connection, and whether `isSyntheticSender` has a null-id hole after vault teardown.# FINDINGS

*(none)*

# CONFIRM-OR-REFUTE

### 1. R-U4-1 — can a cover frame become a message? — **REFUTE** (no path found)

**Guard placement.** In `MessagingCoordinator.onMessageDeliver`, `isSyntheticSender(envelope.senderId)` runs first, before the deleted-contact check and before `signal.decrypt` (`MessagingCoordinator.kt:1915–1938`). On match it bare-acks and `return@runCatching` — no store, roster, unread, or notification path.

**Why before decrypt matters.** Documented residual matches the code path: `isPreKeyMessage = envelope.ephemeralKey != null`; PreKey decrypt can TOFU before MAC rejection. Send-backs from `buildReply` are established-session shape (`ephemeralKey = null`), so a *failed* guard would usually throw without TOFU — but a prekey-shaped hostile frame still needs the pre-decrypt drop. Placement is structural.

**Wiring.** Production does not leave the default `{ false }`:

```1912:1914:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
                isSyntheticSender = { senderId ->
                    DecoyAuthStore(rt).accountId?.let { it == senderId } == true
                },
```

Per-envelope read; mid-session provisioning is covered.

**Synthetic socket path.** Cover envelopes to the synthetic account hit `DecoyInboundSession.onCoverDelivered` only (ack / burn / optional send-back). No decrypt, no store.

**Null-id / torn-down timings.**  
- No synthetic id yet → no synthetic socket → no send-backs; guard answers false for all senders (correct).  
- Guard throws (runtime closed) → outer `runCatching` → no decrypt.  
- `acceptingDeliveries = false` after `stop()` still only applies *after* decrypt for non-synthetic mail; synthetic mail is still dropped first while the id is readable.  
- Spec residual stands: a hostile relay can label a real message with the synthetic id and force a drop — same power as dropping the message outright.

**Bare ack vs `ackDurable`.** Sound. Tombstone branch needs durability for a *real* message that may return after crash-restore. Cover must never surface; early relay drop is the desired outcome. Crash before `TAG_DECOY` is durable loses the id *and* the already-acked envelope — intentional, not a real-message loss.

**Tests.** R-U4-1 is pinned by source tripwires only (position, return, production wiring, silence). No behavioural `MessagingCoordinator` test injects `isSyntheticSender` and asserts drop — residual of the test harness, not a live hole in production.

---

### 2. Changed U3 tripwires / `"disconnect"` ban — **REFUTE** (no defect; residual is the declared one)

**App-wide scan** (`DecoySendPairingTest.kt:1315–1416`): every `disconnect()`, bans `::disconnect` and `"disconnect"`. Receiver-typed exemption for `socket.` / `ws.` in the two U4 files only.

**Mis-fire.** No `"disconnect"` literal in `app/src/main` today. Comments use the word without quotes. Legitimate UI copy using the exact quoted token would fail the test — deliberate strictness, not a green-path false positive on current code.

**Hiding a real disconnect while green.** Still blocked for: direct calls, callable refs, reflective name literal. **Declared residual:** concatenated/computed method names. No *new* undeclared class found that is both practical in these files and free of banned tokens (`javaClass`, `::class`, `getMethod`, `MethodHandles`, …). JNI / generated accessors / adversary-with-commit-access tricks stay outside what a lexical guard can close — same residual class, restated.

---

### 3. R-U4-4 — the yield — **REFUTE** (exemption still sound; one shared meter)

**Send-backs yield; acks/burns do not.** `onCoverDelivered` always acks; burns schedule without pressure; `sendBack` checks `pressure.yieldingSendBack()` after the reply delay (`DecoyInboundSession.kt:225–267`).

**Hostile flood.** Unlimited acks (by design — shed acks ⇒ relay retries ⇒ load becomes durable). Burns/replies capped at `MAX_OUTSTANDING_WORK = 64`. Cover that goes quiet / unburned envelopes on TTL is degradation, not disclosure.

**Both queues, one meter (still true after round 5):**

```1832:1835:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
            val coverPressure = CoverPressure(
                queuedBytes = {
                    wsClient.outboundQueueBytes() + (syntheticSocket?.outboundQueueBytes() ?: 0L)
                },
```

One `CoverPressure(` in production; pairing and inbound share `coverPressure`. Synthetic `rate_limited` → `syntheticRateLimited()` only (not the shared off-window).

---

### 4. Lifecycle — **REFUTE** (no outlive / permanent-kill / bad race found)

| Event | Behaviour |
|---|---|
| Vault lock | `coverTraffic.stop` via `bindTo` → synthetic `stop()` **before** pairing drain → then real `ws.disconnect()` |
| Transport swap | `applyTransportLocked` re-points both clients; `applyTransport` redials synthetic **outside** the real socket’s DISCONNECTED gate |
| `quiesce` | Not wrapped by `bindTo` — session survives; redial is the caller’s job |
| `stop` then `reconnect` | Terminal; reconnect no-ops |
| Lazy provision | `start()` no-ops without account/token; provisioning path calls `inbound?.start()`; unlock also starts if already provisioned |
| Concurrent start/stop | Dial under same monitor as `stop`’s disconnect (round-1 P1) |
| Concurrent start/reconnect | `Mutex` across token read (round-1 P2) |

Synthetic cannot outlive the real session through the stop seam. A transient failed token read on reconnect can leave cover quiet until the next `start()` (send/provision/re-unlock) — **degradation**, allowed by R-U4-6.

---

### 5. R-U4-2 / R-U4-3 (reworded) — **REFUTE** (satisfied in production)

**R-U4-2.** `DecoyInboundSession` constructor: no `SignalProtocolManager`, no vault mutate API. Delivery uses only `id` / `senderId`. Tripwire forbids crypto/durable tokens in U4 sources (comments stripped).

**R-U4-3 reworded.** Round-5 production state:

- `WsSyntheticSocket` has **no** `diag` (or other logging) parameter.
- Construction: `WsClient(wsUrl, httpClient, scope)` → default `diag = {}`.
- `ZitroneApp` no longer passes `bootDiagnostics.record`.
- Construction-block tripwire bans `diag` / `Diagnostics` / `Log.` / `println` / `record(`.
- U4 files ban bare `diag` and common sinks.
- `onRateLimited` → in-memory `CoverPressure.syntheticRateLimited` only.
- Shared `OkHttpClient` from `CertificatePinning`: **no** `EventListener`, interceptors, or HTTP logging.
- Real socket alone keeps the durable diag path (`ZitroneApp.kt:1761–1764`).

**(a) Remaining sink routes?** No live production route found from U4 → durable sink. Residual (not live): re-add a trailing/positional lambda on the *internal* `WsClient(...)` line with a custom file write that avoids banned tokens — requires editing the fixed file; same honesty class as “lexical scans bound honest mistakes.”

**(b) Deleting `diag` break anything?** No production UX reads synthetic lifecycle lines. Tests construct `WsSyntheticSocket` without `diag` (`WsSyntheticSocketTest`). Real connectivity UX is on the real `WsClient` only.

---

### 6. `buildReply` — **REFUTE** (matches R-U4-3 and plausibility claims)

- Always established-session: `ephemeralKey = null`, `preKeyId = null`.
- Ciphertext length mirrors received; frame shorter without prekey fields (true of real replies).
- Mirrors TTL / burn / media / version / timestamp width.
- Fail-closed on empty ids, wrong recipient, negative counter, ciphertext too short for a padded block.
- In-memory `replyCounter` restarts at 0 with process — matches kdoc / real post-ratchet-turn appearance.
- Declined builds drop silently in `sendBack`.

---

### 7. Anything else — **REFUTE** (no additional defect upheld)

Checked: lock ordering (`connecting` vs `lock`), bounded `pending`, exceptions swallowed in `launchTracked` / `runCatching`, send-back not on real-send path, pressure fail-closed toward yield, no second `CoverPressure`, `bindTo` stop-before-delegate, no exception path from U4 into marking a real send FAILED.

**Requirements in §4.4 (including reworded R-U4-3):** not unsatisfiable; rewording correctly closes the “existing sink” hole that rounds 4–5 found. None is so weak that *current* production code can satisfy the letter while leaving a real P1/P2. Named residuals (relay labels `sender_id`; expired synthetic JWT quiet; control channel uncovered; lexical tripwire residual on computed reflection names) stay residuals, not open defects.

---

# MISSING CONTEXT

| Gap | What would settle it |
|---|---|
| No device/integration run of dual-socket unlock + Tor toggle + cover reply delivery | Manual or instrumented session: confirm no `boot-diagnostics.log` lines for a second handshake, synthetic redial after DISCONNECTED real socket, send-back dropped by guard |
| No behavioural unit test that constructs `MessagingCoordinator` with `isSyntheticSender` and asserts pre-decrypt drop | One confined test with a fake `signal` / message store |
| OkHttp platform logging at fine level | Outside app durable sinks; not required for R-U4-3 as written |

---

**VERDICT: CLEAN** (0 P1, 0 P2, 0 P3)

**What was checked for this CLEAN (absence of finding, not proof of correctness):** full §4.4 R-U4-1…6 against whole-unit code (not only the round-5 diff); all four round-5 fixes (diag deletion + construction wiring, reworded R-U4-3, brace-only redial tripwire, reflection/`"disconnect"` bans); production paths for sinks (params, `WsClient` default, `onRateLimited`, shared `OkHttpClient` hooks, exception swallowing); R-U4-1 path including null-id/teardown/bare-ack; yield asymmetry and shared meter; lifecycle/start/reconnect/stop/bindTo/transport; `buildReply`; U3 disconnect-ownership scan breadth; requirement soundness including the round-5 reword. Residual classes above are named and not elevated without a concrete live sequence in this tree.
