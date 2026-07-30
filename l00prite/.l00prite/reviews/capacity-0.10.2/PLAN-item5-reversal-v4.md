# 0.10.2 item 5 — DESIGN PLAN v4. Built on SINGLE-WRITER ABANDON. v1, v2, v3 all DO NOT SHIP.

Branch `feat/0.10.2-capacity-fixes` @ `2efa220b`. No item-5 code written to any of these designs.

## ⚠️ NO LINE NUMBERS IN THIS PLAN, DELIBERATELY

v3's `MessagingCoordinator` citations were **all 8 lines low** (correct for `c781d2ac`, two commits
before its own base), v2 asserted file:line facts that were false, and **two of v3's step-8 deletion
ranges would have destroyed live code.** Three passes wasted reviewer effort re-deriving my offsets.
**v4 cites SYMBOLS only.** Every line number is derived at implementation time, against the commit
being edited, and any deletion range is confirmed by reading the range before deleting it.

## What survived three rejections, and what did not

**The per-token / per-attempt registry SHAPE is right — every lens in the v3 pass said keep it.** What
was wrong was three mechanisms inside it:

1. **`HANDED_OFF` asserted a fact the client cannot observe.** `ws.sendMessage` is an OkHttp **enqueue**
   receipt, and the tree says so in its own words in three places (`WsClient`'s "not yet written", and
   `MessagingCoordinator`'s own comment that the delivery receipt — *"not ws-enqueue"* — is the first
   honest proof). Relay ownership is `message.stored`. So "the relay owns it now, never abandon"
   permanently stranded a blob on **the most frequent failure in the product**: the socket drops, the
   frame is discarded unwritten, and — because **no `markFailed` exists on any disconnect path** — the
   bubble sits at SENDING with `retryable`'s CAS refusing a retry.
2. **`RESERVED`-no-abandon made v3 WORSE than the tree it replaces.** Today the memo is written *before*
   the upload and the catch-all route abandons unconditionally, so a response lost after the relay
   committed the row **is** reclaimed. v3 stopped reclaiming it — and since v3 deletes the token memo, k
   retries leave k blobs where `ON CONFLICT (blob_id) DO NOTHING` leaves one. My own deposit
   `callTimeout` makes that route *more* frequent.
3. **The messageId-keyed cleanup sweep falsified the dissolution property the design was argued from**,
   and handed the abandon trigger to an unauthenticated, unratcheted relay frame (`message.burned`).
   What actually protected a later attempt was *guards*, not dissolution — the exact reasoning error v1
   was rejected for.

## MECHANISM 1 — SINGLE-WRITER ABANDON. This is the design.

**The only code that may destroy a blob is the code that created it.** That is a structural property,
unlike v3's claimed one.

- **Exactly two callers of `abandonBlob` exist in the process.** (a) The depositing coroutine, at
  whichever exit it reaches. (b) The **terminal reclaim flush** (mechanism 2).
  **They are NOT mutually exclusive in time — that claim was checked at source and is FALSE.**
  `coordinator.stop()` sets `acceptingSends = false`, cancels only `linkJob`, and then dispatches the
  terminal task onto the confined worker, blocking until *that task* runs. It **stop-accepts-new; it
  does not join in-flight send coroutines** (the file's only `join()` belongs to `onAuthExpired`'s
  relink latch). And it cannot: `limitedParallelism(1)` serialises execution **slices**, not
  coroutines, so a send suspended in `uploadBlob` has yielded the worker and the terminal task runs
  in a slice beside it. **Cancel would not help either** — a cancelled coroutine still runs its
  `finally`, so an exit path that abandons is still a live writer. Only a join is exclusion, and
  there is none.
- **THE SAFETY ARGUMENT IS THEREFORE ATOMIC REMOVAL, NOT TEMPORAL EXCLUSION.** Every decision is a
  single atomic `remove`-and-decide, so **exactly one party ever receives a given entry**. Two live
  writers cannot both act on it: not double-abandon, and — because `CONFIRMED` is excluded and only
  the receiver of the entry may act — not destroy-a-live-blob. This is the property to test; the
  time-ordering story was decoration and is deleted.
- **Every foreign-stack cleanup MARKS and returns no-abandon.** Burn, remote burn, TTL burn, read-burn,
  reveal burn, discard, contact delete, `clearAll` — each atomically sets a `cleanupRequested` bit on
  matching entries and abandons **nothing**. One bit, not a lifecycle state.
- **The owner reads its own mark at its own exit** and acts.

**Five v3 findings dissolve at once, and this is why the mechanism is worth the rewrite:** the named
window disappears (no foreign abandon can exist inside it); a relay-forged `message.burned` can destroy
nothing; the abandon-oracle finding is gone; and the design stops claiming dissolution while relying on
guards.

## MECHANISM 2 — RECLAIM INSIDE THE TEARDOWN ORDER, NOT OUTSIDE THE SCOPE

v3 asked *"which scope survives the lock?"* — the wrong question. `UnlockController.lockCurrent` calls
`stopSession` **before** cancelling the session scope, `stopSession` closes the `VaultRuntime` in a
`finally`, and `ApiClient` reads the bearer **live on every call** — so a closed runtime makes every
later abandon throw `IllegalStateException` and be swallowed. **The binding constraint is vault closure,
not scope cancellation**, and v3's `NonCancellable` process scope addressed the wrong one. It was
deterministically inert on its own headline case.

**The right question is "what runs while the vault is still open?" — and the tree already answers it.**
`coordinator.stop()` runs first, inside `stopSession`, and already contains the exact pattern: a bounded
dispatch-and-block on the confined worker (as used for cover teardown), with `lockCurrent` already
carrying a bounded drain. A terminal reclaim flush riding that existing bound needs **no
`NonCancellable`, no process scope, no captured credential, and no duress gate** — which also disposes
of the duress-gating complexity entirely, because nothing uncancellable is ever created.

**Consequence to state plainly: the duress asymmetry disappears as a mechanism.** It is not that duress
is specially handled; it is that reclaim is part of an ordered, bounded teardown that a duress wipe
simply does not run. **Verify that claim against the Pucker Burn path before relying on it.**

## MECHANISM 3 — STATES NAME OBSERVABLES, AND "NEVER ABANDON" IS CONDITIONAL

Each state asserts **only what the client can actually observe**, and the abandon rule follows from the
observable rather than from an assumption about the relay:

| state | the observable | may the owner abandon? |
|---|---|---|
| `UPLOADING` | token drawn; deposit outcome **unknown** | **YES.** "Unknown" means abandon — `AbandonBlob` is an unconditional `DELETE` answering 204 either way, so abandoning a token whose row may not exist **costs nothing and reveals nothing.** This is v3's blocking-3 fix. |
| `STORED` | the deposit returned success (**or 409 — our own 256-bit token, so our bytes**) | **YES** |
| `ENQUEUED` | `send()` returned true: a frame naming this token **entered a buffer we cannot recall**. NOT relay acceptance | **CONDITIONAL** — see below |
| `CONFIRMED` | `message.stored` arrived for this message | **NO.** The recipient may redeem; destruction here is unrepairable (redemption is one-shot, unavailability is terminal) |

**`ENQUEUED` is the whole subtlety, and v3 got it wrong by making it permanent.** A frame we cannot
recall *may* still reach the relay, so the owner must not abandon immediately. But it may equally have
been discarded unwritten on a socket drop, and **nothing currently marks that message failed**, so
waiting forever is what stranded the blob. So: `ENQUEUED` blocks abandon **until the send is known to
have failed or the session ends** — at which point the terminal reclaim flush (mechanism 2) resolves it
while the vault is open. `CONFIRMED` is the only permanent no.

**And a gap this exposes that is bigger than item 5, recorded not fixed here:** there is **no
`markFailed` on any disconnect path**, so an enqueued-then-dropped frame leaves a message stuck at
SENDING with retry refused by the CAS — independent of attachments, and a worse user-facing bug than the
orphan. **Do not fold it into item 5;** it needs its own change, and item 5's design must not depend on
it being fixed.

## THE STEPS

1. **Reversal + test deletion + registry, ONE commit.** Deleting `reuseToken`/`reuseKey` from
   `AttachmentCrypto.encrypt` breaks `AttachmentDepositReuseTest`'s **compilation**, so a split commit
   leaves later steps with zero automated signal. Same commit: delete the reuse params and wiring,
   delete both test classes in that file, introduce the registry with `UPLOADING` before the deposit and
   `STORED`/abandon-on-failure after. Fresh-secrets-per-attempt becomes **compiler-enforced** by the
   parameters not existing. **Gate: suite green, `assembleDebug`, and the attachment retry path verified
   working.**
2. **Single-writer abandon.** `abandonBlob` callable from exactly the two places in mechanism 1;
   everything else marks. Add the `cleanupRequested` bit. **Gate: prove by construction — a test or a
   source pin that no other call site exists.**
3. **`ENQUEUED` at `send() == true`**, in the position v3 chose (which the review confirmed is the only
   correct one), and **`CONFIRMED` on `message.stored`**. Note that `markFailed` currently accepts
   `SENDING || SENT`, so it can flip a relay-ACKed message — constrain it, or `CONFIRMED` is reachable
   for a message that then gets retried.
4. **Terminal reclaim flush inside `coordinator.stop()`**, riding the existing bounded
   dispatch-and-block. Resolves `UPLOADING`/`STORED`/`ENQUEUED` entries; never touches `CONFIRMED`.
5. **Mark-only cleanup hooks** on every message-removal path. The existing `onMessageBurned` hook is
   **`notifyPeer`-gated** and the vault contact-delete path — the default on shipped installs — passes
   `notifyPeer = false`, so a new hook independent of that flag is required, plus explicit iteration in
   `clearAll`. **No state predicate**: `burn` flips to BURNING before invoking hooks, so every
   state-based predicate is dead on arrival.
6. **A bounded existence accessor.** `MessageRepository.exists` is a **flatten-and-scan over every
   message in RAM**, not O(1), and returns `Boolean` so `present && state != BURNING` **cannot be
   expressed through it** — widening it would silently change its one live caller (the incoming
   duplicate staleness gate). Add a new indexed accessor. **Restate the constraint correctly: the rule
   governing the publish tail is NO SUSPENSION between check and send, compiler-enforced by the
   non-suspending `fun` — R-U3-1 is the cover-traffic subordination rule and citing it here was wrong.**
7. **Bound and parallelise abandon.** `abandonBlob` has **no `callTimeout`** while both clients set
   `readTimeout(0)`, and `execute`'s only reaper for a half-open circuit is cancellation-driven — so a
   single wedged cleanup never returns. Give it a per-call deadline via `Call.timeout()` (**never a
   standalone `OkHttpClient`** — the transport client carries Tor SOCKS, the I2P socket factory, the
   loopback DNS override and the pinner; a fresh client egresses over the default network, which is a
   deanonymisation defect) and use **bounded concurrency, not a single worker.**
8. **Comment deletions, ranges re-verified by reading them first.** The route-(a) inversion, the
   `AttachmentDeposit` kdoc, the "ONE BLOB PER MESSAGE" inline, `sendAttachment`'s "1 week" (actual
   96 h), `AbandonBlob`'s three-route undercount, and the `ApiClient` kdoc claiming contact-delete is
   covered.

## WHAT THIS DOES NOT FIX

- **Crash / process death** between a deposit and any exit — TTL only. Inherent to the RAM-only store.
- **Duress wipe** does not run the reclaim flush, **by design and by construction** — the blob waits out
  its TTL. Not folded into "TTL-bounded": it is a deliberate silence.
- **An abandon that cannot reach the network** — TTL.
- **Nothing bounds upload→send.** A frozen process can resume a continuation much later.
- **Relay malice** — conceded. This removes only *self-inflicted* deletion.
- **No `markFailed` on disconnect paths** — recorded above, needs its own change, and item 5 must not
  depend on it.
- **Flush-then-late-deposit orphan (new, from verifying the drain claim).** Because `stop()` does not
  join in-flight sends, the terminal flush can atomically take an `UPLOADING` entry and abandon it
  (correct — unknown means abandon), after which the still-suspended upload completes and deposits
  bytes that **no registry entry covers**. TTL-bounded, and **not** data loss: the envelope for that
  attempt was never handed off. Listed because it is reachable, it is created by this design, and the
  honest comparison against the current tree has to include it.

**The claim, narrow and checkable:** every orphan route the client knows about at the moment it fails is
reclaimed by the party that created it, and the residue is crash, duress silence, unreachable network,
and the unbounded upload→send gap. **v4 must be measured against the CURRENT TREE, not against v3** —
v3's blocking-3 finding was that it lost a reclaim the tree already performs, and that comparison is the
one that matters.

## THE SEQUENCE I AM LEAST CONFIDENT ABOUT

*(The previous entry here — that the two abandon callers are mutually exclusive in time — was checked
at source and is FALSE. It has been corrected in mechanism 1 rather than left as an open question,
and the orphan it creates is now in the residual list.)*

**Whether atomic removal is sufficient once BOTH writers are known to be live.** The argument is that
a single `remove`-and-decide hands each entry to exactly one party, so no entry is acted on twice. But
the two writers observe **different** things: the owner knows what its own upload did; the flush knows
only the recorded state. So a flush that wins the race for an `ENQUEUED` entry decides on
"enqueued-but-unconfirmed" **without knowing whether the owner is about to learn the send failed** — and
mechanism 3 makes `ENQUEUED` conditional precisely because that distinction matters.
**Is "exactly one party receives the entry" enough when that party may be the less-informed one, or does
the flush need to defer to a still-live owner — and if so, how does it know one exists, given there is
no join?**
