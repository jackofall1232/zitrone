I'll explore the codebase read-only to ground a design that makes attachment blob abandon safe without reintroducing the P1 race.Next I'll dig into the deposit/send path, abandon plumbing, and session teardown so the design matches how the tree actually works.# DESIGN — Zitrone 0.10.3: safe attachment blob reclaim

**Verdict:** Re-enable `ApiClient.abandonBlob`, but only under a **claim-before-network / never-after-handoff** rule. Do **not** abandon at session teardown. Keep the memoised token + `StoreBlob` `ON CONFLICT (blob_id) DO NOTHING` cap exactly as it is.

This is better than the current tree on reclaimable orphans, never worse, and does not recreate the confirmed P1.

---

## 1. The defect, restated as an invariant

Three client routes can leave a deposited blob that nothing will fetch:

| Route | Where | Today (abandon disabled) |
|-------|--------|---------------------------|
| **(a)** Non-durable `flushSendRatchet` after deposit | `deliverAttachment` | Orphan until TTL, unless user retry reuses the same row and eventually succeeds |
| **(b)** Contact gone at the publish tail | `publishOutgoing` → discard + `releaseDeposit` | Orphan until TTL; token is thrown away |
| **(c)** Any throw after a successful deposit | `deliverAttachment` `onFailure` | Same as (a) |

The dormant abandon path is unsafe because of one race:

1. Attempt 1 deposits under memoised `blobId = sha256(token)`.
2. Attempt 1 schedules **fire-and-forget** abandon and marks the bubble retryable.
3. Retry re-deposits the **same** id (`ON CONFLICT` / 409-as-success), then **publishes** the envelope.
4. The stale abandon arrives and `DELETE`s the row.
5. Recipient gets a real message; redeem 404s → terminal `attachmentUnavailable`. Sender’s RAM copy dies at the next lock. **Silent to both.**

The five rejected designs all tried some form of:

> *decide at teardown, using state the client cannot observe, with a call it cannot complete, after destroying the record that would let it try again.*

So the design must answer only questions the client **can** answer, with a call it **can** finish, without burning the only capability (the token) until reclaim is confirmed or deliberately given up in a way that cannot race publish.

---

## 2. What the client can and cannot know

### Observable (usable)

- Whether **this process** deposited under a memoised token (`attachmentDeposits`).
- Whether **this process** got a true return from `ws.sendMessage` inside `publishOutgoing` (enqueue receipt, not relay acceptance).
- Whether the local bubble is still retryable (`MessageState.FAILED` / still in `MessageRepository`).
- Whether the contact still exists at the non-suspending publish tail.

### Not observable (must not drive abandon)

- Whether the relay **stored** the envelope (`message.stored` may never arrive: disconnect makes delivery more likely and ack impossible; `coordinator.stop()` does not join in-flight sends).
- Whether a graceful close drained a queued frame after local discard.
- Whether a FAILED-from-timeout message was actually accepted (timeout is healed by late `markSent`, deliberately).
- Anything after `runTerminalConfined` returns: bearer dies with `clearTokens` / `VaultRuntime` close; post-teardown HTTP throws and is swallowed.
- State after `burn()` has flipped to `BURNING` (predicates on “still live” are dead on arrival).

**Therefore:** once `ws.sendMessage` has returned true for this token, **abandon is forbidden for the lifetime of that deposit.** TTL and recipient redeem remain the only reclaim paths for handed-off blobs.

---

## 3. The rule (one sentence)

> **Abandon only a blob this process deposited, only if it has never been handed to `ws.sendMessage`, and only after exclusively claiming the memo so no retry can publish under that token while the abandon HTTP call is in flight; release the claim only on confirmed success, or restore it on failure so retry keeps the DB one-row cap.**

That is the entire safety core. Everything else is wiring.

---

## 4. Mechanism

### 4.1 Keep (do not touch)

- `AttachmentCrypto.encrypt(..., reuseToken, reuseKey)` and memoisation in `attachmentDeposits`.
- `StoreBlob` `ON CONFLICT DO NOTHING` and client **409-as-success** on own memoised id.
- Blind store: abandon stays **token-keyed**, authenticated, 204 opaque (relay `AbandonBlob` as today).
- Durability barrier: nothing between confirmed `flushSendRatchet` and `ws.sendMessage` except the existing non-suspending publish tail.
- Per-call HTTP via `transport.client.newBuilder()` only (existing `ApiClient.execute` path).

### 4.2 Extend the memo entry

```text
AttachmentDeposit(
  token: ByteArray,
  key: ByteArray,
  handedOff: AtomicBoolean = false,  // or confinement-only var if all writers are confined
)
```

- **Set `handedOff = true` only inside `publishOutgoing`, and only when `ws.sendMessage(envelope)` returns true**, in the same non-suspending function, immediately after the true return (before `armSendTimeout` is fine).
- **Never clear `handedOff`.**
- Readable from any stack that can see `attachmentDeposits` (contact delete, failure path, tests) — **not** a coroutine-local. That is the distinction from the rejected “published flag as coroutine-local.”

`onMessageStored` / `onMessageDelivered` keep calling `releaseDeposit` (heap hygiene after terminal success). That is unrelated to abandon.

### 4.3 Claim-before-network helper (new)

Single internal suspend helper, e.g. `reclaimUnpublishedDeposit(messageId)`, used only from the attachment send path / contact-delete cleanup for unpublished deposits:

1. **Claim:** atomically take the entry for `messageId` **only if** it exists and `!handedOff`. If missing or handed off → no-op.
2. **Network:** `api.abandonBlob(b64(token))` (awaited, not fire-and-forget).
3. **On success (2xx/204):** drop the claimed secrets (zeroize if the project already zeros similar material; otherwise drop references). Do **not** put the entry back.
4. **On failure (any throw, including rate limit / 401 / cancel policy):**  
   `attachmentDeposits.putIfAbsent(messageId, claimed)` so a later retry **reuses the same token** and the ON CONFLICT cap still holds.  
   Swallow the error (same product rule as the old kdoc: cleanup must not worsen a failed send). Re-throw only `CancellationException` if the surrounding send should cancel.
5. **Ordering vs retry UI:** call this **before** `messages.markFailed` on paths that abandon, **or** ensure `retryable()` cannot succeed until reclaim returns. Preferred order:

   ```text
   claim → await abandon → (restore on fail) → markFailed
   ```

   So the user cannot tap retry during an in-flight abandon of the same token. That is the P1 kill switch.

**Compiler:** cannot enforce claim-before-network.  
**Test:** must pin it (see §8).  
**Comment:** state the invariant on the helper, not at every call site.

### 4.4 Call sites (re-enable only these)

| Path | Action |
|------|--------|
| **(a)** Non-durable flush after deposit | `reclaimUnpublishedDeposit` then `markFailed`; return. Do **not** publish. |
| **(b)** Contact deleted in `publishOutgoing` | Must stay **non-suspending**. Change: **do not** `releaseDeposit` here. Return a richer result (or a side channel the caller already owns) such as `PublishResult.ContactGone`. Caller (still on confined, **after** the tail) runs `reclaimUnpublishedDeposit` then leaves local discard as today. Socket-down branch already `markFailed`s; if deposit exists and not handed off, reclaim before or instead of leaving an orphan (same helper). |
| **(c)** `onFailure` after deposit | Only if this attempt never observed handoff. Track a **stack-local** `var deposited` / use memo presence + `!handedOff`. Never abandon if `handedOff` is true (e.g. cover-traffic throw after a real handoff — rare; must not delete the recipient’s blob). |

**Do not** call abandon from:

- `onMessageStored` / `onMessageDelivered` (success path; recipient needs the blob)
- send-timeout → FAILED (may still be stored; late heal is intentional)
- `burn` / `burnAll` for messages that may have been handed off
- `MessagingCoordinator.stop`, `UnlockController.lock`, idle auto-lock, logout, revoke, account delete, duress wipe
- any path that only has `messageId` from an unauthenticated WS frame as the trigger to abandon (rejected design)

### 4.5 Contact delete with FAILED unpublished attachments

`deleteContact` → `messages.burnAll(..., notifyPeer = false)` does **not** today touch `attachmentDeposits`. After this design:

- For each burned outbound message id (the set already captured as `burnIds`), call `reclaimUnpublishedDeposit(id)` on the confined worker **after** the delete is applied to live state (`DURABLE` / `APPLIED_UNCONFIRMED`), not on `NOT_APPLIED`.
- Handed-off deposits: no-op (blob may still be needed for an in-flight or stored envelope).
- Bound the work: sequential awaits are fine; shared `blobLimit` already self-limits. Do not spawn unbounded parallel abandons.

This is route **(b) generalized**: terminal local discard of an **unpublished** deposit.

### 4.6 Retry behaviour after reclaim

| Abandon result | Memo | Next retry |
|----------------|------|------------|
| Success | Gone | Fresh token/key → new `blobId` (old row already deleted) |
| Failure | Restored via `putIfAbsent` | Same token → same row (cap preserved) |
| Skipped (`handedOff`) | Kept until stored/delivered release | Same token; **must not** abandon |

Memoisation remains the default for “failure without successful reclaim,” which is the common offline/Tor blip case. The DB one-row-per-message cap is **not** replaced by best-effort cleanup; cleanup only **removes** a row the client has already decided will never be published, under exclusive claim.

---

## 5. Session-end matrix

| Session end | Abandon? | What happens to orphans |
|-------------|----------|-------------------------|
| **Lock / idle auto-lock** | No | `stop()` → `runTerminalConfined` non-suspending teardown; tokens/runtime die. In-flight send may finish deposit after ack is impossible. **TTL.** |
| **Logout** | No | Same; `clearTokens` makes authenticated abandon fail anyway. **TTL.** |
| **Revoke / forced 401** | No | Same. **TTL.** |
| **Account delete** | No | Server account may already be gone; local wipe must not wait on blob GC. **TTL** (and account row gone does not auto-delete blind blobs — by design of the blind store). |
| **Duress wipe (`burn` path)** | No | State is `BURNING` before hooks; UI-thread `burnAll` must not block on Tor HTTP. **TTL.** |
| **Process death / crash mid-send** | No | RAM memo gone; **TTL** (unchanged). |
| **Successful recipient redeem** | N/A | Fetch-and-burn deletes row (existing). |
| **Janitor** | N/A | `PurgeExpiredBlobs` at `BlobTTLHours` (existing backstop). |

Teardown abandon is explicitly **out of design**: it is the failure mode named in the brief. Accepting TTL there is how this stays **not worse** than doing nothing.

---

## 6. Comparison to the CURRENT TREE on reclaimable orphans

Current tree: abandon **disabled** everywhere. Routes (a)(b)(c) always leave the row for full blob TTL unless a later **successful** retry reuses the memoised id and the story continues (recipient redeem or eventual TTL).

| Scenario | Current tree | This design |
|----------|--------------|-------------|
| (a)/(c), reclaim succeeds | 1 orphan until TTL or successful retry reuse | **0** immediately |
| (a)/(c), reclaim fails (network) | 1 orphan; memo kept; retry reuses row | **Same** (memo restored) |
| (b) contact gone before handoff | 1 orphan; token dropped | **0** on success; else same as TTL |
| After handoff (any outcome) | No abandon (correct) | No abandon (correct) |
| Session end / crash | TTL | TTL (**equal**) |
| Retry storm without abandon | 1 row (memo + ON CONFLICT) | **Same** while reclaim fails or is not used |
| P1 race (stale abandon vs publish) | Impossible today only because abandon is dead | **Impossible** by claim-before-network + no abandon after handoff |

**Never worse** than the current tree: the only new “extra row” path would be “claim + successful abandon + later retry deposits a new id,” which is **zero** old orphans plus one live blob — not an orphan. Failed reclaim restores the memo, so we do not mint a second id on top of an unreclaimed first.

Three prior designs were rejected for being worse than doing nothing (e.g. dropping the memoised cap so every retry deposited a new orphan). This design **keeps the cap** and only deletes under exclusive claim.

---

## 7. Server / rate limit

**No server protocol change required** for correctness. `AbandonBlob` already token-keys, auth-gates, and returns opaque 204.

Optional non-blocking follow-ups (not required for 0.10.3 correctness):

- Separate limiter bucket for abandon vs deposit/redeem — only if reclaim starts competing with live traffic; today one shared 60/min bucket is accidentally self-limiting and that is acceptable.
- Do **not** key abandon on `message_id` or any unauthenticated WS signal.

---

## 8. Enforcement: compiler vs test vs comment

| Property | Enforced by |
|----------|-------------|
| No suspension between `contactExists` and `ws.sendMessage` | **Compiler** (`publishOutgoing` remains a non-suspending `fun`) |
| Handoff bit set only on true `sendMessage` | **Code structure** in that same function + **unit/source tests** |
| Claim before `abandonBlob` HTTP | **Tests** (ordering / race): cannot mark retryable while claim held; cannot reuse claimed token |
| No abandon when `handedOff` | **Tests** + helper guard |
| Memo restored on abandon failure | **Tests** |
| 409-as-success + encrypt reuse | **Existing** `AttachmentDepositReuseTest` / wiring tests (update wiring assertions for new helper and release count if contact-gone no longer calls `releaseDeposit` directly) |
| No teardown / lock / burn abandon call sites | **Source wiring test** (grep-style, same pattern as existing deposit wiring tests) + review |
| Invariant statement | **Kdoc on the helper** and a short note on `ApiClient.abandonBlob` replacing the stale “see call sites” story |

There is **no** honest compiler proof that every future contributor avoids a new fire-and-forget call site; the wiring tests and the single helper are the practical gate.

---

## 9. Concrete symbol surface (implementation sketch — not code)

**Touch (Android client primarily):**

- `MessagingCoordinator`: `AttachmentDeposit` + `reclaimUnpublishedDeposit`; wire (a)(b)(c); richer `publishOutgoing` result; contact-delete unpublished reclaim; remove “ABANDON DISABLED” comments by replacing with the invariant.
- `ApiClient.abandonBlob`: refresh kdoc (safe only via claim helper; not for teardown).
- Tests: new race/ordering tests for claim-before-network and handed-off non-abandon; update deposit wiring tests.

**Do not touch for this unit:** relay schema, `StoreBlob` conflict behaviour, blob TTL floor vs envelope TTL, ratchet flush barrier, `WsClient` enqueue semantics, web/desktop (no abandon client there today).

---

## 10. What this design does **NOT** fix

State these as accepted residuals (honest product surface):

1. **Crash or kill after deposit, before handoff** — no client left to reclaim; **TTL only**.
2. **Any session teardown** (lock, idle lock, logout, revoke, account delete, duress) — no authenticated completed abandon; **TTL only**.
3. **Handoff without `message.stored`** (relay drop, lie, reorder, disconnect-after-enqueue) — client must **not** abandon; blob may be orphaned until TTL even though no recipient will fetch. Preferring a rare disk orphan over a silent permanent 404 is mandatory.
4. **Send-timeout FAILED that later heals** — no abandon on timeout path.
5. **Recipient never opens / never redeems** — existing fetch-and-burn + TTL model.
6. **`ws.sendMessage` ≠ relay acceptance** — unchanged; SENT tick still waits for `message.stored`.
7. **`stop()` not joining in-flight sends** — unchanged; design does not add a terminal flush or post-`clearTokens` network.
8. **Blind store has no owner column** — account delete cannot cascade-delete blobs; intentional ZK property.
9. **Cross-process or multi-device** — memo is per-process; not a durable outbox (message store is RAM-only; a retry is a real send).
10. **Does not rebalance `blobLimit`** — reclaim still shares the bucket; under hard limit, reclaim fails closed to memo restore + TTL.

---

## 11. Why this is not a rejected design

| Rejected idea | This design |
|---------------|-------------|
| Reverse memoisation | Kept; still the DB-enforced one-row cap with no network |
| `published` as coroutine-local for foreign cleanup | `handedOff` on the **shared memo map**, set only at handoff |
| Terminal teardown flush / abandon | Explicitly forbidden |
| Retire registry entry on *decision* | Retire only after successful abandon; **restore** on failure |
| `messageId`-keyed cleanup from relay frames | Still token-keyed HTTP from depositor paths only |

It does not try to know what the relay stored. It only knows what **this process enqueued**, and it never lets an abandon share a token with a concurrent publish.

---

## 12. Implementation order (for the winning implementer)

1. Add `handedOff` + `reclaimUnpublishedDeposit` with tests for claim/restore/handoff guards (can be tested with a fake `ApiClient` if coordinator construction is heavy; otherwise extract a small pure “deposit registry” type host-tested without Android `Context`).
2. Wire route (a) and (c) with markFailed ordered after reclaim.
3. Change `publishOutgoing` result for contact-gone / socket-down; wire (b); stop releasing the memo on contact-gone without reclaim.
4. Contact-delete unpublished sweep over `burnIds`.
5. Wiring tests that abandon is not referenced from `stop` / lock / burn / account-delete paths.
6. Manual / integration: deposit → fail flush → confirm blob gone; deposit → handoff → confirm abandon no-ops even if forced; claim + slow abandon + ensure retry blocked until done.

---

## 13. Bottom line

**Re-enable abandon** as a **best-effort, pre-retry, pre-handoff, claim-scoped DELETE**, not as session GC.

- **Covered well:** (a)(b)(c) while the session is alive and the blob was never handed to the socket.  
- **Covered by TTL (honestly):** crash, teardown, duress, account delete, post-handoff uncertainty.  
- **Vs current tree:** strictly more reclaim when reclaim works; **equal** when it does not; **never** the silent recipient 404 P1.

That is the design I would implement.
