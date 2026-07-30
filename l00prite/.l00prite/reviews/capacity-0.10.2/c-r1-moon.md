moon: asking kimi-k3...
# ANSWERS

## Q1 — Is "never abandon while still retryable" sufficient and safe?

**Necessary, not sufficient — and under the stable-blobId design it mostly moves the race rather than closing it.**

Three problems remain after adopting it:

1. **Terminal-vs-in-flight is still a race.** `burn()`'s only precondition is `state != BURNING` (MessageRepository), so `burnAll` / remote burn can flip a FAILED→SENDING message to BURNING *while a retry is mid-flight* on the confined worker. If abandon fires at `remove()`, it can delete the blob the in-flight retry just deposited — the P1 shape, re-rooted. Closing it requires an "attempt in flight" flag set/cleared on `confined` and consulted at the terminal-abandon decision point, since only the confined dispatcher gives you totality against `retry`. That is real machinery the proposal quietly needs.

2. **It is load-bearing on the Q4 fix.** Under stable blobId, a route-(a) failure leaves the blob in place (no abandon while retryable). The retry then re-deposits under the same blobId and gets **409 → ApiException → route (c) → markFailed** [L1468-1479]. Unless 409 is treated as success, "never abandon while retryable" converts every post-upload failure into a permanently unretryable message. The two fixes are not independent.

3. **Stale expiry.** `ON CONFLICT (blob_id) DO NOTHING` keeps the *first* attempt's row including its original `expires_at` (store.go [L283-294]). A retry at hour 95 of 96 that treats 409 as success publishes an envelope referencing a blob that expires in an hour. The recipient gets UNAVAILABLE through no abandon at all.

On the RAM-only point: process death loses the memo under every option on the table; the 96 h TTL is the only backstop there, and that is acceptable because it is inherent, not a defect introduced by the deferral.

**Verdict:** as the *stable-id* fix, it is the right floor but requires (1) in-flight tracking, (2) 409-as-success, and (3) acceptance of stale-expiry — three additions to close a race that a different construction (Q3) eliminates by making it unrepresentable.

## Q2 — Should route (a) abandon at all?

**Under stable blobId: no.** The comment at [L1454-1457] is the inverted reasoning at the root of the P1: "Abandoning cannot strand a later attempt, because item 5a memoises the token — a retry re-deposits under the SAME blob id" gets the causality exactly backwards. The shared blobId is precisely the mechanism by which the abandon strands a later attempt. Route (a) leaves the message FAILED *by design* so the user can retry; the blob sitting on the relay is the retry's asset, not its waste.

What is then left uncovered (still under stable-id):

- route (a) non-durable flush,
- route (c) throws after a successful deposit,
- the socket-down branch of `publishOutgoing` [L490-494] — note this route **never had an abandon even in 5b as shipped**,

each costing ≤ 1 blob × ≤ 96 h per failed-never-retried attachment, capped by 5a at one per message. Bounded, self-inflicted only, TTL-collected. Acceptable as a floor — but it guts most of 5b's stated purpose, and the failure routes above are far more common operationally than the crash window that remains under the per-attempt design. So "acceptable at 96 h" is true but should be the fallback position, not the goal.

**Under the Q3 construction: yes, route (a) should abandon — and safely can**, because the abandon targets a blob no subsequent attempt will ever reference.

## Q3 — Is there a construction the lenses missed?

**Yes. I dissent from "token and blobId cannot distinguish attempts."** That claim holds only inside the 5a invariant (memoised token ⇒ stable blobId). The invariant is not load-bearing:

- Same-message attempts are **already mutually exclusive** without dedup: `retryable()` is a CAS FAILED→SENDING (only one retry wins), the initial send uses `existing=false` so it cannot overlap a retry, and the send path runs on the `confined` single worker. Two attempts of one message can never deposit concurrently, so `ON CONFLICT DO NOTHING` dedup has no work to do.

**The construction: version the capability per attempt.** Draw a fresh random `token_n` per attempt (exactly 0.10.1 behaviour); `blobId_n = sha256(token_n)` is then attempt-unique, and each attempt's control payload — which is already re-serialized and re-ratcheted per attempt [L1343-1388] — carries `token_n`. Now an abandon launched on attempt *n*'s failure can only ever delete `blob_n`, which **no published envelope references**, regardless of how it is delayed, duplicated, or reordered by the conceded relay. Re-trace the P1: A1 deletes `blob_1`; retry 1 deposits `blob_2` (no conflict possible); publishes an envelope carrying `token_2`; A1 lands whenever it lands; the recipient redeems `blob_2`. Correct under every interleaving, with zero ordering assumptions, zero in-flight tracking, zero relay change, and zero stored linkage. This is the standard ABA fix: stop reusing names.

The invariant becomes: *an abandon is launched only for an attempt that definitively never published.* That requires one guard (Q5/route-(c) below), not a protocol.

**If stable blobId must be kept** (I see no forcing reason), the minimal relay change is: (1) deposits become UPSERT — replace ciphertext, reset `expires_at`, store a client-supplied random per-attempt `deposit_nonce`; (2) `abandon(token, nonce)` deletes only when the stored nonce matches. Linkage cost: one random per-row column in a table that has no account column — no new correlation, and blob-overwrite is safe because blobIds are visible only to the (conceded) relay. But it costs a schema change, client/relay lockstep, and still needs the client to track the latest nonce correctly. Strictly more machinery for the same correctness the client-only construction gives free.

## Q4 — Is retry-after-successful-first-deposit broken independently of abandon?

**Yes, and this is the sharper finding: 5a as shipped breaks attachment retry even with 5b deleted entirely.**

Chain: retry reuses the memoised token → same blobId → `StoreBlob` returns `ErrBlobExists` → relay 409 `blob_exists` (blobs.go) → `execute` surfaces `ApiException` (ApiClient [L460-472]) → route (c) → `messages.markFailed` + (today) abandon of a perfectly good, decryptable blob [L1471-1475]. The ApiClient kdoc at [L258-259] — "A 409 ... surfaces as an ApiException so the send fails cleanly" — was written when tokens were fresh per attempt and 409 meant an impossible collision. Post-5a, **409 is the expected retry path**. Any attachment whose attempt 1 reached the upload and then failed for any reason (flush, socket, throw) can never be retried: every retry fails at the upload stage. The P1's step 2 is not merely an ingredient of the race; it is a freestanding functional regression, and the prior lenses' trace walked through it without flagging it.

Under the per-attempt construction this defect also disappears (conflicts are impossible again; 409 reverts to meaning genuine anomaly). If stable-id is kept, 409-with-memoised-token must be treated as deposit-success and the send must proceed — with the stale-expiry caveat from Q1.

## Q5 — R-U3-1 exposure of the preferred option

**No exposure, with one noted caveat.** Under the per-attempt design, abandons are launched only:

- on route (a) — *after* the flush failed and the function returns without publishing,
- on route (c) — *after* a throw, guarded so never post-handoff,
- on route (b) — inside `publishOutgoing`'s contact-deleted branch, where **no real send occurs on that branch** (and `abandonBlobQuietly` is `scope.launch`, legal in the non-suspending tail),
- on terminal transitions of never-published FAILED messages — no send involved.

Nothing — no suspension, no launch, no allocation beyond the failure path itself — is inserted between `flushSendRatchet` and `ws.sendMessage` on any path where a real send happens. The success path carries no abandon logic at all. Caveat: a fire-and-forget abandon shares the Tor circuit with a subsequent real send; it is a ~100-byte JSON POST against 8 MiB uploads, and the fire-and-forget basis was already accepted in 5b. Compliant.

# RECOMMENDED DESIGN

Ship the **per-attempt capability** construction:

1. **Drop the reuse memo as a dedup mechanism.** Fresh random `(token, key)` per `encrypt` call (remove `reuseToken`/`reuseKey`, or leave the params unused and delete the wiring). Replace `AttachmentDeposit` with a latest-attempt memo `{ token, published: Boolean }` retained only to power cleanup.
2. **Abandon on the failure routes, which are now safe by construction:** route (a) and route (b) (the latter via the discard branch of `publishOutgoing` — non-suspending-safe and currently uncovered for no good reason, since `scope.launch` does not suspend).
3. **Guard route (c) on "not handed off."** Set `published = true` immediately when `publishOutgoing` returns true; `onFailure` must consult it: post-handoff, never `markFailed`, never abandon. This also fixes the latent `coverTraffic.cover(...)`-throw defect at [L1467], where a cover-traffic throw today marks FAILED and abandons a blob whose envelope *was* handed to the relay (and the subsequent `message.stored` can't advance it — `markSent` is guarded to SENDING, so the bubble sticks at FAILED while the relay holds the message). Better: move `cover(...)` outside the `runCatching` or wrap it in its own.
4. **Terminal cleanup:** abandon the memoised latest token only when a FAILED, never-`published` message burns / is removed / the session clears; release without abandoning on `published` (recipient redemption and TTL own the blob from there — abandoning a SENT message's blob on local burn-all would race the recipient's redemption). Where no coordinator seam exists yet for burn/remove/logout, accept TTL and land the seam incrementally.
5. **Add `messages.exists(messageId)` to `publishOutgoing`'s guard** (synchronous RAM read, no suspension — R-U3-1-clean): today a burn-during-retry publishes an envelope for a message the user has already destroyed.
6. **Keep everything else of 5b:** fire-and-forget, swallowed failures, 204 opacity, TTL backstop. No relay change.
7. **Rewrite the wiring tripwire** (`AttachmentDepositWiringTest`): it currently pins the reuse wiring — invert it to pin *fresh* secrets per attempt, the abandon call sites, and the `published` guard, or it will pass against the regression.

**What this does not fix:** process-death/crash orphans (TTL only — inherent to the RAM-only store, unchanged from every alternative); relay malice (conceded: the relay can delete or 404 any blob at will — this design only removes *self-inflicted* deletion); possible duplicate envelopes if `ws.sendMessage` throws after a partial handoff (pre-existing, untouched); orphans from abandons that genuinely can't reach the network (bounded, TTL-collected — and note the deposit succeeding milliseconds earlier makes this window small).

# DISSENT

Both lenses are wrong on one fact and one inference:

- **Fact:** "token and blobId cannot distinguish attempts" is true only given the memoised-token invariant, and that invariant is doing no work — dedup has no concurrent-attempt case to dedup (Q3). There is a purely client-side construction; the conclusion "client-side fix because attempts are indistinguishable" should have been "client-side fix *by making* attempts distinguishable." Both lenses treated 5a's invariant as a constraint of the problem rather than a choice of the solution.
- **Inference:** the P1 trace's step 2 (retry 1's upload conflicting and failing) is not a neutral race ingredient — it is the independent functional regression of Q4. Any remediation analysis that accepts "retry fails on conflict" as given is reasoning over a system whose retry path is already dead. Fix ordering matters: even if the lenses' stable-id remediation is chosen over mine, the 409 handling must change first or the remediated system still cannot retry attachments.

I also dissent from the code comment at [L1454-1457] (route (a) "cannot strand a later attempt *because* of 5a") — it states the exact inversion of the defect mechanism and should be deleted regardless of which fix ships, because the next reader will believe it.

# MISSING CONTEXT

1. **`sendAttachment` call site and dispatcher** — I confirmed `retry` runs on `confined` [L1493] but not the initial send. My Q3 claim that same-message attempts are mutually exclusive rests on `retryable()`'s CAS plus confinement; if the initial send runs off-`confined`, verify no overlap with a same-id retry. Defect class: concurrent deposits of one message.
2. **`flushSendRatchet` implementation** — can it throw (route (c) with a live deposit at `stage="ws-send"`)? Does its backoff suspend *on* the confined worker, stalling all sends behind one flush? Defect class: route-(c) misattribution; confined-worker head-of-line blocking.
3. **`coverTraffic.cover` body** — can it throw or suspend? This determines whether the post-handoff route-(c) misfire at [L1467] is live or theoretical. Defect class: abandon-after-publish / markFailed-after-handoff.
4. **`ws.sendMessage` contract** — throw semantics and partial-send behaviour. Defect class: duplicate envelopes on retry; FAILED marking after an actual handoff.
5. **Logout / session-teardown path in the coordinator** (where `clearAll` is triggered) — needed to hook terminal abandon of outstanding FAILED memos. Defect class: orphan burst at logout; abandons racing teardown.
6. **Burn entry points into the coordinator** — `onMessageBurned` wiring, `burnAll` / remote-burn handlers. Needed to place the `published`-gated terminal abandon. Defect class: abandoning a SENT blob ahead of the recipient's redemption; burn-during-in-flight-retry.
7. **All `discard`/`remove` callers beyond `publishOutgoing`** — the burn-animation completion path calls `remove` internally; confirm whether any other route deletes a FAILED message without a coordinator-visible event. Defect class: missed terminal cleanup (TTL-acceptable, but should be enumerated).
8. **Relay 409 vs client parsing** — `errJSON(c, 409, "blob_exists")` vs how `ApiException` exposes the code (status only, or parsed body code?). Needed only if stable-id is kept and 409-as-success is implemented. Defect class: mis-routed conflict handling.
9. **Rate-limiter keying** — `h.blobLimit` is shared by deposit, redeem, and abandon (blobs.go). Defect class: a burst of abandons 429-ing a subsequent real upload (R-U3-1-adjacent).
10. **`blobs_test.go` (manifest-listed, not attached)** — verify abandon idempotence/opacity tests exist and pin 204-whether-or-not; they should also pin that redeem of attempt *n*'s token is unaffected by abandon of attempt *n−1*'s (currently untestable since blobIds were shared).
11. **Whether `onMessageStored` can learn the transition result** — `markSent` discards `update`'s return; `releaseDeposit` at [L2285] fires even when the SENDING guard rejected the transition (e.g., late ack after a route-(c) markFailed), dropping the memo of a still-FAILED message and defeating terminal cleanup. Defect class: premature memo release → orphan until TTL.
