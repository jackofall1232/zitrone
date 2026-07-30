I'll perform a read-only adversarial review of the round-2 fixes against source. Starting with protocol context and the scoped files.Source is under `apps/android/`. Reading the round-2 fix surfaces and related tests.Reading the critical functions and tests in full.Checking one more edge: whether the timeout can still fire during post-handoff work that reintroduces double-send, and whether `publishOutgoing` arming is pinned by any test.# FINDINGS

## F1 — P3 — Fix #5 incomplete: production kdoc still describes the PRE-MERGE relay

**File:line:** `apps/android/app/src/main/java/com/zitrone/app/net/WsClient.kt:132–135`  
(echoed in `apps/android/app/src/test/java/com/zitrone/app/MessageRepositoryTest.kt:327–329`)

**Sequence**

1. Reader opens `Listener.onServerError` — the wire-boundary contract for every error implementor.
2. Kdoc states: *“the send budget is checked before the envelope is parsed, so a `rate_limited` frame may carry no id at all.”*
3. Against merged `server/internal/ws/hub.go:158–186`, `handleSend` unmarshals the header **first**, then rate-limits, then echoes `MessageID` when the id is a well-formed UUID. Server test `TestHandleSend_RateLimited_CarriesMessageID` pins the normal case: `rate_limited` **carries** the id.
4. Round 2 claimed this class of comment was corrected (`MessageRepository.armSendTimeout`, `DecoySendPairing`, coordinator prose). **`WsClient` was never updated.** The same false “budget before parse” claim remains in the timeout unit test’s setup comment.

**Wrong outcome**

Not a runtime bug today — behaviour is correct. The damage is the same class as round 2’s upheld P3: a future “cleanup” guided by this kdoc can reintroduce a wrong model (e.g. treating attributable `rate_limited` as normally id-less, or undoing timeout/attribution assumptions). This is the **authoritative API comment** on the path, not a private note.

**Why tests miss it**

No test asserts comment accuracy. `ServerErrorRouterTest` / hub tests assert **behaviour** of attribution; they never fail when the kdoc lies about order of operations.

---

# CONFIRM-OR-REFUTE

### 1. Timeout armed at socket handoff — **CONFIRMED (fix holds)**

Checked against source:

| Claim | Evidence |
|---|---|
| Single choke point | Only two `ws.sendMessage` sites in `MessagingCoordinator`: `publishOutgoing` (arms) and `publishReceipt` (receipts; correctly no arm). Text + attachment both call `publishOutgoing` after encrypt/upload/flush. |
| Nothing arms at bubble / retryable | `addOutgoing` is upsert-only; `retryable` is FAILED→SENDING only with an explicit “no timeout here” comment. Sole production `armSendTimeout` call: `MessagingCoordinator.kt:447`. |
| Window has no pre-handoff local work | Attachment `uploadBlob` and ratchet flush complete **before** `publishOutgoing`. Arm is the first statement in the `ws.sendMessage` success branch. |
| R-U3-1 | Arm runs only after `sendMessage` returns true — nothing moved ahead of a real handoff. |
| Arm throw after handoff | `armSendTimeout` is map ops + `scope.launch` (effectively non-throwing). If it did throw, `publishOutgoing` would abort before `return true`, cover would not run (unpaired real — R-U3-4 class), and `onFailure` would `markFailed`; a later `message.stored` still heals via `markSent`. No practical path found. |

LemonDrop `addOutgoing` uses `state = SENT` for a blind drop already accepted by the relay — out of scope for send-timeout; correct.

**Residual (not a defect in current code):** nothing tripwires or behaviourally asserts that `publishOutgoing` **calls** `armSendTimeout`. Deleting that one line keeps every `MessageRepositoryTest` green (they call `armSendTimeout` themselves) and keeps the U3 ownership tripwire green. Named under HARNESS / MISSING CONTEXT.

### 2. `clearAll` disarms send timeouts — **CONFIRMED (disarm complete on real teardowns)**

`clearAll` cancels + clears `sendTimeoutJobs` first (`MessageRepository.kt:387–393`). Also `cancelSendTimeout` from `markSent` / `markDelivered` / `markFailed` / `markFailedByRelay` / `burn` / `remove`.

| Teardown | How timers die |
|---|---|
| Vault lock / logout | `UnlockController.lock` → `stopSession` → **session `scope.cancel()`** (MessageRepository is built on that scope). Jobs cancelled even though `stop()` does not call `clearAll`. |
| Session revoke | `clearAll` on confined + `onForcedLogout` → `lockIf` → scope cancel |
| Account delete | `clearAll` then `onConfirmed` → lock |
| Pucker Burn | Locks via `unlockController.lock()` → same scope cancel |
| Process death | RAM + jobs gone |

Comment that clearAll alone is what fixes vault lock is slightly overstated (scope cancel is the lock path), but the **invariant “no live send timer outlives the session”** holds. Not a functional gap.

### 3. Conditional self-removal — **CONFIRMED (guard correct; reachability claim defensible)**

```kotlin
coroutineContext[Job]?.let { sendTimeoutJobs.remove(messageId, it) }
```

**Race (reachable under real threading, not on a single-threaded virtual clock):**

1. Job A past `delay`, CAS SENDING→FAILED succeeds (StateFlow publishes FAILED).
2. OS preempts A’s thread before `remove`.
3. UI shows FAILED; user retries → `retryable` + full send → `armSendTimeout` installs Job B (and cancels A’s handle).
4. A resumes; **unconditional** `remove(messageId)` would drop B’s handle → live timer, uncancelable.

That is the same *class* as cancel-vs-CAS (multi-dispatcher, non-suspending tail after cancel), not round-0 `isMine` (unreachable by construction). Window is narrow (needs preemption + user retry) but not imaginary. Guard is correct; sweep cannot express it — as declared. **Keep.**

### 4. markSent / markDelivered kdocs — **CONFIRMED (no contradictory production wording)**

Production kdocs now state FAILED is accepted **deliberately** for healing, and why reversing that reintroduces round-1 double-delivery. Bodies match: FAILED in preconditions. Tests: healing + monotonicity for non-FAILED states. No remaining “can never resurrect FAILED” claim as current behaviour.  
(`FAILED is terminal until retryable` on `markFailed` is about *local* terminal-until-retry, not receipts — consistent.)

### 5. PRE-MERGE relay comments — **PARTIALLY REFUTED (fix incomplete → F1)**

| Location | Status |
|---|---|
| `MessageRepository.armSendTimeout` kdoc | Corrected — no “budget before parse” |
| `DecoySendPairing.onRelayRateLimited` | Corrected — separation outlived id-less reason |
| `ServerErrorRouter` | Accurate (unattributable = non-UUID / omitempty) |
| **`WsClient.Listener.onServerError` kdoc** | **Still PRE-MERGE (F1)** |
| `MessageRepositoryTest` timeout setup comment | Still PRE-MERGE |

**Timeout still justified** after the merge: silent drops, older relays, malformed-header `rate_limited` with empty id (`omitempty`), lost frames, non-attributable codes. Justification is “bounds unattributable / silent failure without relay cooperation,” not “rate_limited usually has no id.” That part of the design is sound; only the leftover docs are not.

### Tripwire relaxation — **CONFIRMED (ownership was the property; R-U3-1 intact)**

Before: adjacent token run `if(ws.sendMessage(envelope)) { return true` — forbade any success-branch statement, including legitimate post-handoff arming.

After: brace-walk `bodyOf` + “exactly one `return true`” + that token lives **inside** the handoff branch + `"if(ws.sendMessage(envelope))"` present.

| What original caught | Relaxed still catches? |
|---|---|
| `return true` outside handoff / second success path | Yes (count + containment) |
| Cover without depending on handoff boolean | Separate tripwire on `if(publish…) cover` |
| Extra statements **after** handoff inside branch | Allowed — correct (adjacency was never R-U3-1) |
| Arm **before** `sendMessage` | **Not** caught (see residual) — but that is not what adjacency was protecting either |

Arming is strictly after `ws.sendMessage` returns. **R-U3-1 untouched.** Relaxation was required by the real fix, not a weak accommodation that drops the ownership property.

---

# HARNESS RULING

**Asserted-is-enough for this merge — not a merge blocker. Full constructible-`MessagingCoordinator` harness is residual debt, not a gate on 0.10.1.**

Weighed with the moved evidence:

1. **Extraction landed.** `routeServerError` is pure, JVM-tested (order, unattributable yield, non-rate-limited attribution, no-op internal). Two named mutations (fold yield into attribution; swap order) are caught. That closes the specific surface both lenses named for error routing.

2. **Wiring is asserted, not behaviourally executed.** Tripwire pins `routeServerError(`, `yieldCover = { coverTraffic.onRelayRateLimited() }`, `failByRelay = messages::markFailedByRelay`. That is weaker than a live coordinator, but the dangerous *decisions* are no longer only textual.

3. **Round 2’s P1 does not prove “no coordinator harness ⇒ P1 ships.”** That P1 was arm-at-`addOutgoing` timing relative to blob upload. `MessageRepository` is constructible; the missing piece was a design/test that “bubble ≠ handoff.” ServerErrorRouter extraction would not have caught it. What would: the now-present `addOutgoing` does-not-arm test, and/or a pin that the handoff branch calls `armSendTimeout`.

4. **Third instance (0.10.2 blob-reuse, stated)** is real pattern evidence that **asserted-only coordinator guts will keep bleeding.** It argues for scheduling a harness or more extractions seams — not for blocking this PR after the named extraction and repository tests already cover this unit’s load-bearing behaviour.

5. **Cheaper unexploited seam:** a source tripwire (same style as the reduced `onServerError` one) that `bodyOf(publishOutgoing)` / the `ws.sendMessage` success branch contains `armSendTimeout(` — pins the round-2 P1 fix’s wiring without Robolectric or constructing the coordinator.

---

# MISSING CONTEXT

| Gap | What would settle it |
|---|---|
| Live `deliverAttachment` + slow body + timer interaction | Instrumentation or a test double for `uploadBlob` + virtual time through coordinator (not available on this branch) |
| Conditional `remove(key,value)` under preemption | Controllable dispatcher with a barrier between CAS and remove (declared residual) |
| Whether any non-Android client shares the stale “budget before parse” story | Out of Android scope |
| 0.10.2 blob-reuse third instance | Stated only; not on this branch |

**What I checked (for CLEAN claims):**  
`publishOutgoing` / both deliver paths / `retry` / sole `armSendTimeout` call site / `clearAll` + cancel sites / `UnlockController` scope cancel / `markSent`·`markDelivered` preconditions+kdocs / `hub.go` handleSend order + rate_limited tests / U3 tripwire before vs after / `ServerErrorRouter` + wiring tripwire / no other `ws.sendMessage` send path.

---

**VERDICT: FINDINGS (0 P1, 0 P2, 1 P3)**
