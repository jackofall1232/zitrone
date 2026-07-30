# Review: `fix/0.10.3-blob-settle` vs `main` — `/root/zitrone` (apps/android)

Read-only review. Diff scope: `apps/android` — 5 files, +282/−6
(`AttachmentSettleDecision.kt` new, `MessagingCoordinator.kt`, `MessageRepository.kt`,
`ApiClient.kt`, `AttachmentSettleDecisionTest.kt` new).
Surrounding files read in full or to the depth the call graph required:
`MessagingCoordinator.kt` (2,834 lines), `MessageRepository.kt`, `ApiClient.kt`,
`WsClient.kt`, `AttachmentCrypto.kt`, `AttachmentDepositReuseTest.kt`, plus the
burn/deleteContact/teardown paths reached through `MessageRepository.burn`, `burnAll`,
`discard`, `clearAll`, `MessagingCoordinator.deleteContact`, `stop()`, and the
`WsClient` socket-listener identity guard.

**Verdict up front: the core safety claim holds.** I could not construct an interleaving
in which `settleAttachment` abandons a blob whose envelope reached a socket, nor one in
which a retry can still name an abandoned blob. No P1 and no P2 below. The findings are
P3: one materially misleading comment, one real test gap, one half-true premise in the
review request itself (Q10), and coverage notes. Details and the proof chain follow.

---

## Findings (ranked)

### P3-1 — The wired call site's comment claims a cross-retry guarantee the site does not have
- **File/symbol:** `MessagingCoordinator.publishOutgoing`, contact-deleted-mid-send branch
  (the comment above the new `settleAttachment(messageId)` call).
- **What it says:** "we are BEFORE the ws.sendMessage below, so no envelope naming this
  blob ever reached a socket."
- **Why it is wrong:** true only *within one invocation*. Across retries it is false:
  attempt 1 hands off (`handedOffMessages.add` in the success branch), the 90 s
  `armSendTimeout` flips the message to FAILED, the user retries (`retry` →
  `retryable()` CAS FAILED→SENDING → `deliverAttachment(existing = true)`), and if the
  contact is deleted mid-retry, this branch discards and calls `settleAttachment` for a
  message whose envelope *was* handed to a socket on attempt 1. The code is safe because
  `settleDecision` then returns RELEASE_ONLY (the monotone handoff bit is still set) —
  but the comment asserts a call-site property that does not exist.
- **Trigger:** handoff → send timeout → FAILED → retry tap → delete the contact while the
  retry is between `retryable()` and its own `ws.sendMessage`.
- **User-visible consequence:** none today (the decision function re-proves the
  condition, exactly as the last sentence of the comment says). The risk is to the next
  contributor: this is the one wired example of how to call `settleAttachment`, and its
  justification text teaches a false precondition ("position before sendMessage is
  sufficient"). Someone wiring the burn or contact-deletion paths later may trust the
  position argument instead of the re-proof. Fix the comment, or drop the first claim
  and keep "settleAttachment re-proves it".

### P3-2 — `AttachmentSettleDecisionTest` never pins handedOff ⇒ RELEASE_ONLY
- **File/symbol:** `AttachmentSettleDecisionTest` —
  `ABANDON is never returned for a handed-off message, in any state` asserts only
  `action != SettleAction.ABANDON`; the cross-product test likewise only constrains
  where ABANDON may appear.
- **Mutation it would not catch:** changing the branch `handedOff -> RELEASE_ONLY` to
  `handedOff -> SKIP`. The whole suite stays green. In the coordinator that mutation
  changes `settleAttachment`'s RELEASE_ONLY path (drop the memo, keep the blob) into a
  no-op, so the memo and the handoff bit are never released for a handed-off message —
  the exact process-lifetime heap growth the new `handedOffMessages.remove` in
  `releaseDeposit` was added to prevent. No attachment is destroyed (fail-safe
  direction), which is why this is P3 and not P2 — but the one branch whose *positive*
  behaviour matters is unpinned.
- **User-visible consequence (of the mutation, if it shipped):** none within a session
  of normal size; slow heap growth in deposit secrets + handoff bits for handed-off
  messages. One line fixes the gap:
  `assertEquals(SettleAction.RELEASE_ONLY, settleDecision(true, <non-retryable state>, true))`.

### P3-3 — Q10's premise is half-true: `deleteContact` does not uniformly pass `notifyPeer = false`
- **File/symbol:** `MessagingCoordinator.deleteContact` — the vault path calls
  `messages.burnAll(conversationId, notifyPeer = false)`; the legacy (non-vault) path
  calls `messages.burnAll(conversationId, notifyPeer = true)`.
- The claim "onMessageBurned is notifyPeer-gated" is exactly true
  (`MessageRepository.burn`: `if (notifyPeer) onMessageBurned?.invoke(burning)`, fired
  once after the CAS to BURNING). The claim "deleteContact passes notifyPeer = false"
  holds only for the vault path. Consequence for *this* change: none — neither path
  calls `settleAttachment`, and the repository callback only sends peer burn frames.
  Flagged because the review brief stated it as a uniform fact; anyone extending reclaim
  into `deleteContact` must reason about both paths.

### P3-4 — Reclaim coverage is exactly one narrow path (by design, but state it plainly)
- **Wired:** only the contact-deleted-mid-send branch of `publishOutgoing`.
- **Unwired, still orphaning a never-handed-off blob until relay TTL, and still leaking
  the memo + handoff-bit entries until process death:**
  - `MessagingCoordinator.deleteContact` → `burnAll` (both paths) of a FAILED,
    never-handed-off outgoing attachment;
  - burn routes (`MessageRepository.burn` via TTL / `onRemoteBurn` / `revealAttachment`
    timer / `burnAll`) of such a message — `burn`'s only precondition is
    `state != BURNING`, so FAILED messages burn freely;
  - `MessageRepository.clearAll` (logout / session revoked);
  - `MessagingCoordinator.stop` (vault lock / account wipe) — clears neither
    `attachmentDeposits` nor `handedOffMessages`.
- The commit message says this is deliberate ("Broader reclaim (contact deletion, burn)
  is deliberately NOT wired here"). Verified accurate. Two notes for whoever wires it
  later: (a) `settleDecision` SKIPs SENDING and FAILED, so a settle wired *before* the
  removal in `burn` would never reach ABANDON — it must be called with the post-removal
  state (null), as the current call site does via `discard`-then-`settleAttachment`;
  (b) the memo/bit leak under `stop()`/`clearAll()` is bounded by session message count
  and dies with the process — acceptable, but it means the new `handedOffMessages.remove`
  in `releaseDeposit` does not fully deliver the "no unbounded heap growth" property for
  handed-off-but-never-acked messages either (that set is intentionally monotone, so the
  bound is per-process message count — fine, worth one sentence in the kdoc).

### Not a finding — existing source-pinning test survives
`AttachmentDepositWiringTest.the memo is released on every terminal outcome` asserts
exactly 3 occurrences of `releaseDeposit(messageId)` in `MessagingCoordinator.kt`. The
diff removes one (the old call in the contact-deleted branch) and adds one (the
RELEASE_ONLY path of `settleAttachment`): count stays 3, and the two substring pins
(memoised encrypt call, deposit store) are untouched. Suite unaffected by the diff.

---

## Q1 — Can `settleAttachment` ever abandon a blob whose envelope reached a socket?

**No — I could not construct it, and here is the proof chain, each link checked at
source.**

For an abandon to fire, all of these must hold: decision inputs read as
`hasMemo = true`, state ∉ {SENDING, FAILED}, `handedOff = false`; then the atomic
`attachmentDeposits.remove(messageId)` claim must return non-null.

1. **Handoff implies the bit, atomically.** The handoff bit is set in
   `publishOutgoing`'s success branch, in the same non-suspending tail as
   `ws.sendMessage`, on `confined` (`Dispatchers.IO.limitedParallelism(1)`). Because the
   tail never suspends, no other confined work — including any `settleAttachment` — can
   interleave between "frame enqueued" and "bit set". So any envelope that reached a
   socket has its bit set before any observer can run.
2. **Bit and memo are only ever cleared together.** The removers are `releaseDeposit`
   (removes both, called from `onMessageStored`, `onMessageDelivered`, and
   `settleAttachment`'s RELEASE_ONLY path) and `settleAttachment` itself (ABANDON path:
   bit, then memo via the claim). No path clears the bit while leaving the memo.
3. **A re-created memo always names a different blob.** `AttachmentCrypto.encrypt`:
   `val token = reuseToken ?: ByteArray(BLOB_TOKEN_BYTES).also(random::nextBytes)` — when
   the memo was cleared, the re-deposit draws a *fresh random* token, so
   `blobId = sha256(token)` differs from any blob an earlier handed-off envelope named.
   Therefore `hasMemo = true ∧ handedOff = false` can only mean: this memo was created
   after the last coupled clear, and no envelope naming *its* blob was ever handed off.
   The old handed-off blob is not the one the claim will remove — `remove` returns the
   *current* memo.
4. **The claim loses safely.** Between the decision read and the claim, `settleAttachment`
   does not suspend, so the only concurrent remover is a reader-thread `releaseDeposit`
   (`onMessageStored`/`onMessageDelivered` run inline on the OkHttp reader thread — the
   identity guard in `WsClient.onMessage` only *blocks* late callbacks after teardown;
   live-socket callbacks do race the confined worker). If that remover wins, the claim's
   `remove` returns null and `settleAttachment` returns without transmitting. And note
   what a reader-thread `releaseDeposit` *means*: the relay stored or the peer received
   the envelope — i.e., the race is lost precisely in the cases where abandoning would
   have been the defect.
5. **"None ever can be."** After an ABANDON decision the message is gone from
   `MessageRepository` (`discard` ran first at the only wired call site), so
   `retryable()`'s FAILED-only CAS can never resurrect it, and new sends get fresh
   UUIDs. The claimed memo is removed, so a later incarnation cannot inherit the token.

The adversarial case that *looks* closest — handed off on attempt 1, bit cleared by a
late `message.stored`, retry re-deposits, contact deleted mid-retry — abandons the
retry's fresh blob (never handed off), while the attempt-1 blob (which the relay holds
and the recipient may redeem) is untouched. Correct.

## Q2 — Is decide → clear-handoff → claim ordering wrong (bit cleared before a claim that may lose)?

**No.** The bit is only ever *consulted* in conjunction with a memo (`!hasMemo -> SKIP`
is the first branch). Every remover of the memo also removes the bit, so after a lost
claim the bit is gone anyway — `settleAttachment`'s early `handedOffMessages.remove` is
never the sole clearer. The one asymmetry (bit cleared, claim lost, memo gone) is
indistinguishable from the winner having cleared both, which is the state every path
already produces. Moving the bit-clear after the claim would buy nothing and would
re-open a window where a RELEASE_ONLY could observe a stale bit.

## Q3 — Is `handedOffMessages` genuinely monotone across a retry?

Traced: attempt hands off (bit set, `armSendTimeout` armed) → 90 s timeout CASes
SENDING→FAILED → `retry()` → `retryable()` CAS FAILED→SENDING → `deliverAttachment`
re-uploads under the memoised token → `publishOutgoing` succeeds → bit re-added (no-op,
still set), timeout re-armed. Teardown (`stop()` → `coverWorker.runTerminalConfined(::coverTeardown)`
→ graceful `ws.disconnect()`) touches neither the bit nor the memo: the queued frame is
flushed during the graceful close (OkHttp `close(1000)` transmits already-enqueued
frames), the relay's `message.stored` is then dropped by the identity guard
(`if (webSocket !== this@WsClient.webSocket) return` — the field is nulled), and the
bit stays set for the process's life. So: monotone in the only direction that matters —
no incarnation can "un-hand-off" a message. The single clearer of the bit,
`releaseDeposit`, fires only on relay/peer acknowledgement, where dropping monotonicity
is safe because the memo dies with it and any later memo carries a fresh token (Q1,
links 2–3).

## Q4 — `releaseDeposit` clears both maps off the confined worker — safe? Can it flip RELEASE_ONLY to ABANDON?

Reachability confirmed: `WsClient.onMessage` → `dispatchFrame` →
`MessagingCoordinator.onMessageStored`/`onMessageDelivered` run **inline on the OkHttp
reader thread**, no dispatcher hop, so `releaseDeposit` genuinely races the confined
worker. Safety:

- Both removals are idempotent `ConcurrentHashMap` operations; a double-remove between
  reader thread and `settleAttachment` is a no-op.
- The flip scenario: `settleAttachment` reads `hasMemo = true`, then the reader thread's
  `releaseDeposit` removes memo+bit, then `settleAttachment` reads `handedOff = false`
  and state (gone or terminal) → decides ABANDON → claim `remove` returns **null** →
  return. The would-be RELEASE_ONLY becomes *nothing*, not an ABANDON. The claim is
  doing exactly the job the kdoc says it does.
- The claim can only *succeed* when the memo still exists, and per Q1 link 3, a memo
  that exists while the bit is absent names a blob no handed-off envelope references.

## Q5 — Is the single wired call site reachable only when nothing was ever handed off?

**No — reachable after a handoff, across retries** (the interleaving in P3-1: handoff →
timeout → FAILED → retry → contact deleted before the retry's own `ws.sendMessage`).
`contactExists` is checked non-suspendingly on the confined worker within
`publishOutgoing`, so within one invocation the guarantee holds; across invocations it
does not, and the design does not need it to — `settleDecision` returns RELEASE_ONLY for
that case (bit still set; verified against the test's cross-product). The comment's
first sentence overclaims; the mechanism is sound. This distinction matters because the
brief's own invariant is stated as "abandon only when no envelope naming the blob was
ever handed to a socket" — that property lives entirely inside `settleDecision` +
`handedOffMessages`, not at the call site.

## Q6 — `scope.launch(confined + NonCancellable)`: lifetime, ordering, cancelled scope

- **Lifetime:** `NonCancellable` replaces the Job element of the child context, so the
  coroutine is **not** a child of `scope`'s job. If `scope` is already cancelled
  (teardown is the commonest reason `settleAttachment` runs at all), the launch still
  executes; a plain `scope.launch(confined)` would have produced an immediately-cancelled
  coroutine and a silent leak of exactly the blob the settle exists to reclaim. Correct
  choice.
- **Dispatcher:** still queued on the single confined worker. It suspends on the network
  call, freeing the worker for teardown or sends; it is bounded by
  `BLOB_ABANDON_TIMEOUT_MS` once started. If the process dies first (vault lock →
  process teardown), the orphan goes to the relay TTL — the documented backstop.
- **Ordering vs a concurrent retry:** the claim (`attachmentDeposits.remove`) happens
  *before* the launch, in the non-suspending section. A retry after the claim finds no
  memo and re-deposits under a **fresh random token** (Q1 link 3), so the in-flight
  abandon names a blob the retry will never name. A retry *before* the claim implies
  state SENDING/FAILED at decision time → SKIP. No interleaving lets the abandon and a
  live retry name the same blob.
- One caveat, not a defect: a *retry* requires FAILED, and a discarded message (state
  null) can never be retried, so at the single wired call site the "concurrent retry"
  is hypothetical anyway; the ordering argument matters for the future burn/contact
  wiring.

## Q7 — `MessageRepository.stateOf`: lock-order/aliasing? Is the SENDING/FAILED skip sufficient?

- **Locking:** there are no locks to order. `MessageRepository` has no Mutex and no
  `synchronized`; all mutation goes through `update()`, a CAS loop over
  `MutableStateFlow.update { }` with the precondition evaluated inside the CAS, and job
  registries are `ConcurrentHashMap`. `stateOf`/`find` read the `_messages.value`
  snapshot lock-free. No lock-order problem can exist; no aliasing problem either, since
  `stateOf` copies out the enum and never hands the caller the retained `Message`
  (plaintext/attachment bytes) — the kdoc's stated reason for not widening `find`, and a
  good one.
- **Snapshot semantics:** `stateOf` is a point-in-time read, but `settleAttachment`'s
  whole decision+claim is non-suspending on the confined worker, and every writer that
  could make the snapshot stale on the confined worker is serialised behind it; the only
  cross-thread writer reachable mid-settle is reader-thread `releaseDeposit`, covered by
  the claim (Q4).
- **Coverage of retryable conditions:** complete. The only entry into a re-send of an
  existing id is `retry()`, gated by `retryable()`'s CAS which accepts **only** FAILED;
  SENDING means an attempt is live (addOutgoing sets it; `markFailed` accepts
  SENDING||SENT but any SENT-then-FAILED transition still lands in FAILED, which is
  skipped). No other path calls `deliverAttachment`/`deliverText` with `existing = true`.
  Note the subtle one: `armSendTimeout`'s timer CAS is SENDING-only, so a SENT message
  cannot silently become FAILED via the timer.

## Q8 — Is `BLOB_ABANDON_TIMEOUT_MS` a genuine per-call timeout? Is 30s defensible? Sound failure state?

- **Genuine:** `ApiClient.execute(req, callTimeoutMs)` applies
  `call.timeout().timeout(callTimeoutMs, MILLISECONDS)` — OkHttp's per-call *whole-call*
  deadline on the `Call` instance, not a rebuilt client. This matters twice over here:
  both shared clients set `readTimeout(0)` (for the WebSocket), so without it a
  half-open circuit would park the `suspendCancellableCoroutine` forever; and building a
  separate client would shed the Tor/I2P transport and the pinner — the kdoc's
  deanonymisation warning is correct and the `Call.timeout()` route avoids it.
- **30 s:** defensible. The body is one base64 token — a single round trip, no 8 MiB
  upload to clear, over an already-established circuit. Erring short is safe in the
  direction this call fails (orphan to TTL), unlike the deposit's 10 min, where erring
  short strands a message. Over Tor, 30 s is still generous for an established circuit;
  a brand-new circuit build could eat most of it, but the failure mode is benign.
- **Failure state without restore:** sound. The claim already consumed the memo, so a
  timed-out/failed abandon leaves exactly one orphan the relay janitor collects at TTL,
  and no path can re-attempt a double-settle. Restoring the memo would re-open the claim
  for a message that can never be settled again — the kdoc's reasoning checks out.
- Disclosure-invariant note: a timeout *after* the relay parsed the body concedes the
  token while the blob still lives — the invariant's "every non-deleting outcome breaks
  the trade" case. Here it is consequence-free *because of where abandon is called from*:
  the blob is provably unreferenced at that point, so the conceded capability fetches a
  ciphertext the relay already stores. The invariant only has teeth if a future call
  site abandons a blob an envelope may still name — which is why the kdoc's "never call
  this speculatively" sentence is the load-bearing one.

## Q9 — Anything tautological in `AttachmentSettleDecisionTest`? Mutations it would not catch?

- **Not tautological:** the first three tests pin the three load-bearing rules (handed
  off ⇒ never ABANDON; no memo ⇒ SKIP; SENDING/FAILED ⇒ SKIP) across the full
  cross-product of `MessageState?` × handoff.
- **Partially tautological:** the final test re-runs `settleDecision` over the
  cross-product and asserts properties of its own output. Its teeth are real but narrow:
  the size assertion fails the build when a new `MessageState` is added, forcing an
  explicit classification — that is its stated purpose and it works. The two property
  assertions below it would only catch a regression that changed *where* ABANDON can
  appear, which the first and third tests already pin more directly.
- **Mutations it would NOT catch:**
  1. `handedOff -> RELEASE_ONLY` weakened to `handedOff -> SKIP` (P3-2) — passes the
     entire suite.
  2. Reordering the `!hasMemo` guard after the SENDING/FAILED guard — SKIP either way,
     untestable and harmless, but the kdoc makes a point of "stated first so it cannot
     be reordered away", which the tests do not actually enforce.
  3. Every wiring-level mutation: `settleAttachment`'s decide→clear→claim ordering, the
     pre-launch claim, the `NonCancellable`, the placement of `handedOffMessages.add`
     after enqueue — the coordinator is not constructible in unit tests (by the module's
     own admission), so the entire safety argument of Q1/Q4/Q6 rests on source pinning
     (`AttachmentDepositWiringTest`-style) and review, not on this suite.
  4. Individual positive classifications for SENT/DELIVERED/READ/BURNING with
     `handedOff = false` (all currently ABANDON via the else-branch) are asserted only
     negatively; a future state-guard that moved e.g. BURNING to SKIP would pass.

## Q10 — Wiring coverage and the notifyPeer claims, verified at source

- **"settleAttachment is wired at only ONE call site": true.** Sole call:
  `publishOutgoing`'s contact-deleted-mid-send branch. `releaseDeposit` is called from
  `onMessageStored`, `onMessageDelivered`, and `settleAttachment` — nothing else touches
  either map.
- **"contact-deletion and burn reclaim paths unwired": true.** `deleteContact` (both
  vault and legacy paths, via `burnAll`), all burn routes (TTL, remote, reveal, manual),
  `clearAll`, and `stop()` reclaim nothing; never-handed-off blobs there wait out the
  relay TTL and the memo/bit entries wait out the process (P3-4).
- **"onMessageBurned is notifyPeer-gated": true** for the repository callback
  (`MessageRepository.burn`: `if (notifyPeer) onMessageBurned?.invoke(burning)`).
  (Separately, `WsClient.Listener.onMessageBurned` — the inbound handler — exists and
  funnels into `onRemoteBurn` → `burn(notifyPeer = false)`; two symbols, one name.)
- **"deleteContact passes notifyPeer = false": half-true** — vault path yes, legacy path
  passes `true` (P3-3).
- **Real reclaim coverage:** exactly the sends that die because their contact vanished
  mid-flight. Everything else that orphans a blob — burns of unsent attachments, contact
  deletion proper, logout/revocation, lock/teardown, and crashes (which can never call
  anything) — is still TTL-only. That is a deliberate scoping decision, accurately
  described in the commit message, and the unwired paths fail in the safe direction
  (disk, not destroyed attachments).

---

## What I verified vs. what I cannot

Verified against source on this branch: the diff itself; the full decide/claim/settle
logic in `MessagingCoordinator`; the deposit memoisation and fresh-token draw in
`AttachmentCrypto.encrypt`; the retry path (`retry`, `retryable`, `armSendTimeout`,
`markFailed` guards); the socket layer's enqueue/graceful-close/identity-guard semantics
(`WsClient.sendMessage`, `disconnect`, `onMessage`); the per-call timeout plumbing in
`ApiClient.execute`; `MessageRepository`'s lock-free CAS design; both test files
(including that the count-pinning `AttachmentDepositWiringTest` still passes the diff);
and the burn/deleteContact/teardown coverage claims.

Not verified (stated, not assumed): relay-side behaviour (`AbandonBlob` semantics, TTL
janitor, `StoreBlob ON CONFLICT`) was taken from the brief and in-code comments — no
relay source was in scope of this diff; and I did not run the test suite (read-only
review; no mutating commands), so "the suite passes" above is a source-level count
argument, not an executed result.
