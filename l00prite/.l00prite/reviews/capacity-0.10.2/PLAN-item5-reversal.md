# 0.10.2 item 5 — DESIGN PLAN (reversal of 5a). Review this as a DESIGN, before code.

Branch `feat/0.10.2-capacity-fixes` @ `cf86a377`. Nothing merged, pushed, or deployed.

## THE THREE LOAD-BEARING QUESTIONS — answered from source, not memory

**Q1. Does the initial send run on the confined worker? YES — and this is what the design rests on.**
`sendAttachment` (`MessagingCoordinator.kt:1278`) launches at `:1288` on `confined`;
`retry` (`:1492`) launches at `:1493` on the same `confined` dispatcher
(`Dispatchers.IO.limitedParallelism(1)`, `:379`). **So attempts for one message are mutually
exclusive by construction** — a retry cannot overlap the initial send, and two retries cannot overlap
each other. Kimi's Q3 reasoning depended on this and it holds.

**Q2. Can `flushSendRatchet` throw, or block the worker? It cannot throw ordinary failures; it blocks
BOUNDEDLY.** It catches `Throwable`, rethrows only `CancellationException`, and otherwise calls
`onNotDurable()` and returns `false` (`:2596-2619`). Backoff is `delay(FLUSH_RETRY_BASE_MS * attempt)`
with `FLUSH_RETRY_BASE_MS = 50` and `FLUSH_MAX_ATTEMPTS = 3` — **worst case ~150 ms** of suspension on
the confined worker. Not head-of-line blocking worth designing around.
**Consequence that sharpens the plan:** route (c) cannot be reached *from the flush* except by
cancellation. So a route-(c) throw comes from `AttachmentCrypto.encrypt`, `api.uploadBlob`, or
`coverTraffic.cover` — which makes **Kimi's `cover`-throw finding the live post-handoff case**, not a
theoretical one.

**Q3. `blobLimit` is shared across deposit/redeem/abandon — and yes, an abandon burst can 429 a real
upload. ⚠️ THIS CHANGES THE DESIGN.** Same limiter object at `blobs.go:93` (deposit), `:152` (redeem),
`:172` (abandon), at **60/minute per client key** (`handlers.go:83`). Under the reversed design
abandon becomes *more* frequent (every failed attempt abandons, rather than reusing an id), so cleanup
can consume the budget a **real upload** needs. That is the same principle as R-U3-1 — cleanup must
never harm a real send — so **the plan below gives abandon its own limiter** rather than accepting it.

## THE DESIGN

**Step 1 — FIX 409 HANDLING FIRST (independent regression, blocks everything else).**
`StoreBlob` is `INSERT … ON CONFLICT (blob_id) DO NOTHING`, which succeeds server-side while storing
nothing. Under 5a's stable id, a retry after a successful first deposit therefore silently uploads
into a no-op — **the attachment retry path is already dead** independent of abandon. Fix: after the
reversal (step 2) a conflict can only mean an id collision, which is a 2^-256 event, so `DepositBlob`
should treat a conflict as a **server-side error** (`409 blob_exists`) rather than silent success, and
the client must surface it rather than assume its bytes landed. **Verify the retry path actually works
after this step, before proceeding.**

**Step 2 — DROP 5a's memoisation: fresh token, key and nonce per attempt.** Remove
`reuseToken`/`reuseKey` from `AttachmentCrypto.encrypt` and delete the coordinator wiring that passes
them. Attempts become distinguishable again, which is what dissolves the composition defect: a stale
abandon can only ever name **its own** attempt's blob.

**Step 3 — `published` guard + abandon on the failure routes.** Replace `AttachmentDeposit` with a
latest-attempt memo `{ token, published: Boolean }`, retained only to power cleanup.
- Set `published = true` **immediately** when `publishOutgoing` returns true.
- Route (a) non-durable flush → abandon (safe now: it names only this attempt's blob).
- Route (b) contact-deleted → abandon from `publishOutgoing`'s discard branch. `scope.launch` does not
  suspend, so this is D2c-safe and was uncovered for no good reason.
- Route (c) `onFailure` → **consult `published`**: post-handoff, never `markFailed` and never abandon.
  This is Kimi's finding — today a `cover` throw marks FAILED and abandons a blob whose envelope
  reached the relay, and `markSent`'s SENDING guard means the later `message.stored` cannot repair it,
  so the bubble sticks at FAILED while the relay holds the message. Also move `coverTraffic.cover(...)`
  out of the shared `runCatching` (or wrap it separately) so cover can never fail a real send —
  **R-U3-1**.
- Terminal cleanup: abandon the memoised token only when a **FAILED, never-published** message burns /
  is removed / the session clears. On `published`, release **without** abandoning — the recipient's
  redemption and the TTL own the blob, and abandoning a SENT message's blob on a local burn-all would
  race the recipient.

**Step 4 — abandon gets its own rate limiter** (`abandonLimit`, separate from `blobLimit`), per Q3.

**Step 5 — re-point the tripwire.** `AttachmentDepositWiringTest` currently pins **stable-id reuse**,
the thing being removed; left as-is it would pass against the reversed design. Invert it: pin *fresh*
secrets per attempt, the abandon call sites, and the `published` guard.

**Step 6 — fix `releaseDeposit`'s premature fire.** It runs even when `markSent`'s SENDING guard
**rejected** the transition (a late ack after a route-(c) `markFailed`), dropping the memo of a
still-FAILED message and defeating terminal cleanup. `markSent` must report whether it applied.

**Step 7 — enumerate EVERY path that removes a message**, not the four named. Burn, conversation burn,
burn-all, remote burn, TTL burn, contact delete, discard, `clearAll`, session teardown. Where no
coordinator seam exists, accept TTL **and say so**.

**Step 8 — delete the actively-backwards comment** at `MessagingCoordinator.kt:1454-1457`, which says
route (a) cannot strand a later attempt *because* 5a memoises the token — the exact inversion, since
5a is what enabled the stranding. Fix the `ApiClient` kdoc claiming route (b) is covered.

**Step 9 — `messages.exists(messageId)` in `publishOutgoing`'s guard** (synchronous RAM read, no
suspension, R-U3-1-clean): today a burn-during-retry publishes an envelope for a message the user
already destroyed.

## WHAT THIS DOES NOT FIX — stated, not implied

- **Crash/process-death orphans: TTL only.** Inherent to the RAM-only store; unchanged by any option.
- **The upload→send bound does not exist.** A frozen Android process can resume a send continuation a
  day later, so the 96/72 arithmetic has **no** client-side bound behind it. Under this design the
  honest statement is: **nothing bounds it**, and the mitigation is that a resumed attempt now deposits
  a *fresh* blob rather than relying on an aged one. The enforced `Load()` floor (envelope TTL + 24 h)
  covers the *config* hole only.
- **Relay malice:** conceded. It can delete or 404 any blob; this removes only self-inflicted deletion.
- Abandons that cannot reach the network leave an orphan until TTL — bounded, and small because the
  deposit succeeded moments earlier.

## THE SEQUENCE I AM LEAST CONFIDENT ABOUT — break this specifically

**Step 3's `published` flag is set on the confined worker, but route (c)'s `onFailure` and the terminal
cleanup paths may not be.** If `published` is read outside `confined`, or if a terminal cleanup races
`publishOutgoing` setting it, then either a published message gets abandoned (recipient loses a live
attachment) or an unpublished one does not (orphan to TTL). **Is `@Volatile` sufficient, does it need
to live in the memo under the same confinement, or is there an ordering here I have not seen?**
