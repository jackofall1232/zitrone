I'll review the orphaned-blob fix on the send path read-only: memoized deposits (5a), the abandon endpoint (5b), and the abandon-vs-retry race. Starting from the protocol docs and the listed files.Pulling the attachment crypto, coordinator deposit/abandon paths, and server blob handlers next.Reading MessagingCoordinator deposit/abandon/retry paths and the related tests.Checking one more detail: whether `abandonBlobQuietly` is truly non-suspending (so route (b) could call it).# FINDINGS

### P1-1 — Abandon-vs-retry race: late `DELETE` can kill the retry’s deposit after the envelope is published

**Where:** `MessagingCoordinator.abandonBlobQuietly` (427–432), route (a) (1452–1458), route (c) (1470–1475); `store.AbandonBlob` (318–320); `DepositBlob` conflict (115–118).

**Concrete sequence:**
1. Attempt 1 deposits under `blobId = sha256(token)`, then fails after deposit (non-durable flush → (a), or post-deposit throw → (c)).
2. `markFailed` runs; `abandonBlobQuietly` does **`scope.launch { … }` without `confined` and without await** — HTTP DELETE starts on the default dispatcher.
3. User taps retry (common on Tor after “!”).
4. Attempt 2 reuses the memoized token/key (5a). If the blob is still present → `uploadBlob` gets **409** `blob_exists` → `onFailure` → **another** fire-and-forget abandon for the **same** token.
5. Attempt 3 (or a later attempt after a slow abandon finally cleared the row) deposits **fresh ciphertext under the same `blobId`**, flush succeeds, `publishOutgoing` hands the envelope to the relay.
6. A **late** abandon from step 2/4 reaches Postgres: `DELETE FROM blobs WHERE blob_id = $1` (unconditional). The row that attempt 3 just inserted is removed.
7. Recipient decrypts the control payload, redeems → 404 → permanently “unavailable” attachment. Sender still shows a successful/SENDING→SENT path once `message.stored` arrives.

**Wrong outcome:** A real message is delivered with a permanently dead attachment. That is P1 under the calibration.

**Why the author’s “cannot strand” claim is false:** Lines 1456–1457 assert stranding is impossible because 5a re-deposits under the same id. Same id is exactly what makes a late abandon lethal — it is not namespaced per attempt.

**Why tests miss it:** No coordinator harness; no ordering/race test; abandon store path is not unit-tested; wiring tripwire only greps three `releaseDeposit` strings.

**Where the fix belongs (order of preference):**
1. **Client (best):** Do **not** abandon while the send is still retryable (`FAILED` + memo held). Abandon only on **terminal non-retryable** outcomes (discard / burn / delete after the bubble is gone). That matches 5a’s “one blob, keep it for retries.”
2. **Client alternative:** Treat **409 as success** when memoized secrets are in use (blob already present under this id with a key that opens it), and still do not abandon on retryable failure.
3. **Relay alone is weak:** Conditional delete needs a generation/epoch the client does not send; token-keyed unconditional `DELETE` cannot distinguish “orphan from attempt 1” vs “live deposit from attempt 3.”
4. **Design:** Abandon on (a)/(c) while 5a reuses the same `blobId` is self-contradictory; prefer not abandoning on (a) at all if retries remain possible.

---

### P2-1 — 5a’s “same row / harmless re-deposit” does not hold: 409 is a hard client failure

**Where:** `DepositBlob` → 409 (blobs.go 115–118); `ApiClient.execute` throws on any non-2xx (462–477); `uploadBlob` always throws on conflict (262–268); `deliverAttachment` always re-uploads (1418–1420).

**Approved design** (`todos.md` 1624–1626): memoize artifacts so retry re-uploads under the same id and **`ON CONFLICT DO NOTHING` is a harmless no-op**.

**What shipped:** Memo is only `(token, key)`; box is re-sealed with a fresh nonce (fine cryptographically); but conflict is **not** a no-op on the client — it is `ApiException(409)`.

**Reachable case (no abandon on first failure):**
1. Deposit succeeds → `flushSendRatchet` OK → `publishOutgoing` → `ws.sendMessage` false (socket down) → `markFailed` **with no abandon** (486–494).
2. User retries → same `blobId` → **409** → markFailed → abandon launched.
3. First retry **always fails** after a successful prior deposit unless abandon has already wiped the row. Second retry may work if abandon completed, or re-enter P1-1 if a late abandon is still in flight.

**Wrong outcome:** Fix does not hold for the primary post-deposit failure path without relying on unordered best-effort DELETE. If abandon is rate-limited/network-fails (swallowed), retries stay 409 until TTL (96 h).

**Tests miss it:** Crypto tests only check stable `blobId`/openable boxes; nothing asserts “retry after successful deposit proceeds.”

---

### P2-2 — Route (b) residual rests on a false premise; abandon placement is inverted

**Where:** `publishOutgoing` contact-deleted branch (477–482); residual claim in 5b commit / todos (1647–1650); `abandonBlobQuietly` (427–432).

**Claimed reason for not covering (b):** `publishOutgoing` is non-suspending, so abandon cannot be called there.

**Fact:** `abandonBlobQuietly` is a **non-suspending** `fun` that only `scope.launch`es the HTTP call. It is already used from the same send path without introducing a suspension point. Route (b) could do:

```text
token = attachmentDeposits[messageId]?.token
abandonBlobQuietly(b64(token))  // non-suspending
releaseDeposit(messageId)
```

without breaking D2c’s non-suspending tail.

**Worse:** Abandon is installed on **retryable** failures (a)/(c) — where it races retries — and **omitted** on (b), the one path where the message is discarded and **no retry can re-deposit** (the safe place to reclaim).

**Wrong outcome:** Orphan route left open for up to 8 MiB / 96 h when the same fire-and-forget tool could cover it safely; residual classing is not “correctly residual,” it is an incomplete fix justified by a false constraint.

**Tests miss it:** Residual is only in prose; no test that contact-delete-mid-send reclaims (or intentionally does not).

---

### P3-1 — `releaseDeposit` incomplete vs “every terminal / non-retryable outcome”

**Where:** Claims at 405–408, 398; releases only at 481, 2285, 2297; `MessageRepository.burn` / `burnAll` / `clearAll` never notify the coordinator; wiring test freezes count `== 3` (AttachmentDepositReuseTest 149–158).

**Reachable:** FAILED attachment → user burns conversation / `clearAll` on logout → memo (~96 B) stays until process death. Not an 8 MiB leak, but the invariant “released the moment the send stops being retryable” / “or it burned” is false.

**Tests miss it:** Tripwire would **fail** if a correct fourth release were added without updating the magic `3`.

---

### P3-2 — Safety comment on route (a) is actively wrong

**Where:** MessagingCoordinator.kt 1454–1457.

Documents the false invariant that enabled P1-1. Hygiene, but load-bearing.

---

# CONFIRM-OR-REFUTE

### 5a hazard 1 — token must not be relay-computable  
**Holds.** `blobId = sha256(token)`; token is the redemption capability; `messageId` is cleartext for routing. Memoized `SecureRandom` draw is correct. Do not derive from message id.

### 5a hazard 2 — key must be reused (not token-only); box need not be held  
**Holds cryptographically.**  
- AES-GCM with a **fresh 96-bit nonce per seal** under a reused key is standard; multi-target / tag issues are not opened by retry-scale reuse.  
- Forward secrecy of the attachment key was already “blob destroyed on redeem or TTL”; memo does not worsen that.  
- Half-reuse is correctly refused (`require` at AttachmentCrypto 105–109).  
- Holding only 96 bytes is correct **if** the stored box is opened with the memoized key (either attempt’s box works — tested).  
- Forcing byte-identical boxes would require deterministic nonce over random padding → catastrophic GCM misuse; **avoided correctly.**

### 5a memo hazards (wrong message / leak / unbounded)  
- **Wrong message:** keyed by `messageId`; new sends use new UUIDs. OK.  
- **Leak of capability:** token/key only in process RAM next to attachment bytes already retained for retry. OK for deniability model.  
- **Unbounded growth:** intended release on stored/delivered/discard; **burn/clear incomplete** (P3-1). ~96 B, not 8 MiB.

### 5b token-keying vs id-keying  
**Holds under attack.** Blob id is presented on deposit and is not a secret; id-keyed delete + any auth would make “know id ⇒ destroy” for any account that learns ids (logs, shoulder, compromised client of another party). Token-keying matches redeem power: only a party that could already redeem-and-burn can abandon.  
**Sending the token to the relay:** same preimage the redeem path already reveals when burning; authenticated abandon adds request-time account↔blob linkage the **conceded** relay could already log on deposit. No new cryptographic capability. 204-or-not-found opacity is correct.

### Abandon-vs-retry race  
**Confirmed real (P1-1).** Fire-and-forget + same `blobId` + unconditional DELETE + multi-retry 409→re-abandon makes late DELETE after a successful re-deposit a reachable permanent-dead-attachment path. Comment claiming otherwise is wrong.

### R-U3-1  
**Not weakened for send admission/ordering/durability:** abandon runs after `markFailed`, is not awaited, does not block, delay, or fail a real send or a retry’s encrypt/publish for cover reasons.  
**Side effect:** the race can destroy the blob under a **successful** real send’s envelope — that is a delivery integrity P1, not an R-U3-1 cover violation. Swallowing abandon errors is correct so cleanup cannot crash or delay the “!” (markFailed precedes launch).

### Defer-upload rejection  
**Sane.** Putting an 8 MiB Tor upload inside the post-flush pre-publish gap re-opens the durability hole U3 emptied; orphan ≺ lost message. Not disputed.

---

# RESIDUAL CLASSING

| Declared residual | Judgment |
|---|---|
| Route (b) uncovered; (b)+crash TTL-bounded 96 h | **Misclassed.** Crash→TTL is a true residual. **(b) is not** justified by non-suspending `publishOutgoing` — `abandonBlobQuietly` is already non-suspending. Leaving (b) open while abandoning retryable (a)/(c) is inverted (P2-2). |
| Store-touching abandon path not unit-tested | **Fair residual** given `*db.Store` harness cost; still means P1-1/P2-1 had no safety net. |
| Coordinator 5a wiring asserted, not behavioural | **Fair residual**; explains why 409/race/socket-down paths were invisible. Tripwire is necessary but insufficient (and freezes incomplete release count). |

---

# MISSING CONTEXT

- No live Tor timing traces; race is argued from concurrency structure (default-dispatcher abandon vs confined retry, multi-abandon, unconditional DELETE), not measured packet order.
- Whether production clients auto-retry attachments (vs only manual “!”) would change how often step 4–5 of P1-1 fires; manual retry is already enough.
- Peer reviewers’ findings not visible (blind pass).

---

```
VERDICT: FINDINGS (1 P1, 2 P2, 2 P3)
```
