I'll review the whole send-failure-surfacing unit read-only: protocol docs first, then the wire path, coordinator, repository CAS, decoy socket, and tests.Examining the core send-failure path and related surfaces next.Reading the core implementations and tests in full.Checking one more state-machine interaction and whether unattributable failures have any recovery path:# FINDINGS

### F1 — P2 — `MessageRepository.markFailed` accepts `SENT`; success acks ignore `FAILED`
**File:line:** `apps/android/app/src/main/java/com/zitrone/app/data/MessageRepository.kt:140-142` (precondition), `:81-86` (`markSent`), `:104-109` (`markDelivered`); wired from `MessagingCoordinator.kt:2362`

**Sequence (reachable with a conceded / buggy relay):**
1. Client sends envelope `id=X`; bubble is `SENDING`.
2. Relay stores it and emits `message.stored` → `markSent` → `SENT`.
3. Relay later emits `{"type":"error","code":"store_failed"|"rate_limited"|…,"message_id":"X"}` (hostile lie, redeploy mismatch, or duplicated/late frame).
4. `onServerError` → `markFailed(X)` succeeds because precondition is `SENDING || SENT` → `FAILED`.
5. A subsequent real `message.stored` / `message.delivered` is a **no-op** (`markSent` requires `SENDING`; `markDelivered` requires `SENDING|SENT` — not `FAILED`).
6. UI shows `!` / “Tap to retry” (`MessageBubble.kt:199-228`). User retries → re-encrypts under **same** `X` → second live ciphertext. Peer decrypts a new ratchet message; `addIncoming` **upserts** by id (`MessageRepository.kt:379-393`).

**Wrong outcome:** User is shown **FAILED for a message the relay already stored**; success receipts cannot heal the bubble; retry can double-deliver. This is **strictly worse than a drop after store** (which would leave honest `SENT`). The coordinator comment at `MessagingCoordinator.kt:2359-2361` understates this as “fail a send it could equally have dropped.”

**Why tests miss it:** `MessageRepositoryTest` exercises `markFailed` from default `SENDING` only; no test does `markSent` then `markFailed`, nor proves that `markSent`/`markDelivered` after `FAILED` stay stuck. No coordinator-level race test.

---

### F2 — P2 — Unattributable rejection still leaves `SENDING` with **no user path out**
**File:line:** `MessagingCoordinator.kt:2349-2362`; UI only retries `FAILED` (`MessageBubble.kt:199-221`, `ChatScreen.kt:133`); in-repo relay still rate-limits **before** parse (`server/internal/ws/hub.go:158-161`) with **no** `MessageID` on errors (`:160,:165,:171,:178`)

**Sequence:**
1. Client hands off a real send; bubble `SENDING` (“…”).
2. Relay returns `rate_limited` with **absent/empty** `message_id` (normalised to `null` at `WsClient.kt:359-362`), **or** production is redeployed from this repo’s `main` so **no** error carries an id.
3. Cover yield still runs (if code is `rate_limited`); **`markFailed` is skipped**.
4. Bubble stays `SENDING` forever: no timeout, no tap-to-retry (only `FAILED` is clickable), no other recovery short of process death (RAM-only store).

**Wrong outcome:** The original defect remains for the **most common** rejection class whenever the id is missing. Falling back to “pre-0.10.1” is not a safe equilibrium for the user — it is still a silent stuck send. In this repo’s server, `rate_limited` cannot carry an id without reordering/parsing changes that are **not** present here.

**Why tests miss it:** Wire tests only check normalisation to null (`WsClientFrameTest`); nothing asserts user-visible recovery when `messageId == null`. No coordinator behavioural test.

---

### F3 — P3 — Ownership claims in comments/kdoc are stronger than the code
**File:line:** `MessagingCoordinator.kt:2359-2360` (“SENDING/SENT **and ours only**”); `WsClient.kt:137-139` (“must check the id against sends it **actually owns**”); `MessageRepository.kt:134-140` (removed `isMine`)

**Sequence / outcome:** Production writers never put `isMine=false` into `SENDING`/`SENT` (`addIncoming` forces `DELIVERED`; outgoing paths set `isMine=true`). So removing `isMine` is **not** a production security hole today. But the **comments still claim an ownership bound the CAS does not implement**. `addOutgoing` will happily store `Message(isMine=false, state=SENDING)` (default state is `SENDING` in `Models.kt:24`) — an API-level path the “unreachable” claim does not type-enforce.

**Why tests miss it:** The test that replaced `isMine` only uses `addIncoming` for “theirs”; nothing asserts `isMine` filtering or documents the production-writer invariant as a real check.

---

### F4 — P3 — Source tripwire is both defeatable and brittle
**File:line:** `DecoySendPairingTest.kt:1549-1558` (pins exact normalised substrings inside `onServerError`)

**Sequence / outcome:**
- **Defeat while green:**  
  ```kotlin
  override fun onServerError(...) {
      return  // or if (!feature) return
      if (code == ERROR_RATE_LIMITED) coverTraffic.onRelayRateLimited()
      if (messageId != null) messages.markFailed(messageId)
  }
  ```
  Both needles remain in order; behaviour is dead.
- **Over-constrain:** Legitimate  
  `if (messageId != null) { messages.markFailed(messageId) }`  
  fails the exact needle `if(messageId != null) messages.markFailed(messageId)` after normalisation (braces break the substring).

**Why tests miss it:** The tripwire *is* the test; it does not execute the callback. Declared absence of a `MessagingCoordinator` harness leaves this as the only attribution guard.

---

# CONFIRM-OR-REFUTE

### 1. Cover rejection never surfaces / id collision
**CONFIRM (structurally for production).**  
Cover envelopes get a fresh `UUID.randomUUID()` (`DecoyEnvelopeBuilder.kt:204,316`) and never call `addOutgoing`/`addIncoming`. `markFailed` no-ops on unknown ids (`MessageRepository.kt:413-416`; tested at `MessageRepositoryTest.kt:227-238`). Cover frames that ride the **real** socket still only name cover ids on an honest relay; synthetic socket **ignores** `messageId` (`WsSyntheticSocket.kt:72-80`). UUID collision with an in-flight real id is not a practical attack. Hostile relay echoing a **real** id is attack surface (see F1), not “decoy surfaces.”

### 2. Retry does not resurrect R-U3-1
**CONFIRM.**  
`retry` runs on the confined worker (`MessagingCoordinator.kt:1418-1419`, `confined` at `:379`), `retryable` → `SENDING`, then `deliverText` / `deliverAttachment` with `existing=true` (`:1432-1452`). Cover is only after a successful handoff: `if (publishOutgoing(...)) coverTraffic.cover(envelope)` (`:1185`, `:1397`) — same choke point as first send; cover cannot precede the real frame.

### 3. Cover yield not conditional on attribution
**CONFIRM in source; tripwire is only partial.**  
```kotlin
if (code == ERROR_RATE_LIMITED) coverTraffic.onRelayRateLimited()
if (messageId != null) messages.markFailed(messageId)
```
(`MessagingCoordinator.kt:2343-2362`). Yield is first and independent of `messageId`. Tripwire pins form/order (`DecoySendPairingTest.kt:1527-1558`) but can stay green under dead code (F4) and is not a behavioural proof.

### 4. `store_failed` fails the message
**CONFIRM when `message_id` is present.**  
Attribution is **not** code-filtered: any `error` frame with non-empty `message_id` calls `markFailed` (`:2362`). So `store_failed` with an id works. **If** id is absent (in-repo server never sets it on errors — `hub.go:178`), behaviour falls through to F2 (stuck `SENDING`).

### 5. Echoed id as attack surface
**CONFIRM worst case is broader than the design comment admits.**  
| Echo | Effect |
|------|--------|
| Unknown / cover id | no-op (good) |
| Incoming id | no-op (`DELIVERED` not in CAS) |
| Our `SENDING` | `FAILED` — correct if truly rejected; false if already stored / will store |
| Our `SENT` | `FAILED` + success acks ignored + retry can duplicate (**F1**) |
| Repeated / late | same; can fail mid-retry while publish still succeeds |
| Other conversation’s id | only if that id is still `SENDING`/`SENT` in **this** process — fails that bubble |

Not worse than a total compromise of the relay for confidentiality, but **worse than pure drop** for integrity of send-state and duplicate risk after `SENT`. Incoming mail and burned/removed messages are protected by the CAS.

### 6. Removed `isMine` guard
**REFUTE production counterexample.**  
No production path puts not-ours into `SENDING`/`SENT`: only `addIncoming` (forces `DELIVERED`) and outgoing constructors with `isMine = true` (incl. LemonDrop `SENT` + `isMine=true`). No message restore-from-disk.  
**Caveat (F3):** unreachability is a **writer convention**, not a repository invariant; `addOutgoing` does not force `isMine`/state. Removal is not a live P1/P2 defect; comments that say “ours only” are still wrong.

### Threading / extras (attack “anything else”)
**CONFIRM `markFailed` is safe on the socket thread:** `StateFlow.update` CAS (`MessageRepository.kt:405-428`); class kdoc already claims multi-thread writers (`:34-38`).  
**CONFIRM yield is socket-thread-safe:** `CoverPressure.relayRateLimited` is a `@Volatile` write (`CoverPressure.kt:139-171`; pairing notes inbound thread at `DecoySendPairing.kt:601-605`).  
**No exception surface** in the new path under normal conditions.  
**Kdoc overclaim** at `WsClient.kt:137-139` (F3).

---

# MISSING CONTEXT

| Gap | What would settle it |
|-----|----------------------|
| Deployed relay commit `1c63e8c` not in this repo | Read production `handleSend`: when is `MessageID` set on `rate_limited` / `store_failed` / `bad_envelope`? Does budget check still precede any header parse? |
| Whether production ever emits error **after** `message.stored` for the same id | Server tests / logs on the box |
| End-to-end rate-limit with real WS | Integration test: send → force 100/min → assert bubble becomes `FAILED` vs stuck `SENDING` |
| Coordinator wiring | Robolectric (or extracted pure handler) test: fake `onServerError` → repository state + cover yield call order |

---

## Known gap (harness): acceptable, **not** a merge blocker

**Position:** Acceptable residual for this unit, **not** a merge blocker, **if** F1/F2 are accepted as known residuals or fixed.

Reasoning: wire normalisation, repository CAS, and synthetic ignore are behaviourally tested; coordinator change is two sequential, pure calls with structural bounds (`markFailed` no-op / state CAS). The tripwire is weak (F4) but the attribution line is simple. A full `MessagingCoordinator` harness is the right **follow-up** to pin null-id policy and fail-after-`SENT` races — it should not block solely because the constructor is heavy, but it **should** exist before treating send-path correctness as proven.

---

## What was checked (CLEAN is not a proof)

Whole unit on `feat/0.10.1-send-failure-surfacing` (`faf6aa07`): `WsClient` error dispatch, `MessagingCoordinator.onServerError` / `retry` / `deliverText` / `publishOutgoing`, `MessageRepository` CAS + upsert, `WsSyntheticSocket`, cover id generation, in-repo `hub.go` error paths (no `MessageID` on errors), UI retry only on `FAILED`, and the new/updated tests (`WsClientFrameTest`, `MessageRepositoryTest`, `WsSyntheticSocketTest`, `DecoySendPairingTest` tripwire).

**VERDICT: FINDINGS (0 P1, 2 P2, 2 P3)**
