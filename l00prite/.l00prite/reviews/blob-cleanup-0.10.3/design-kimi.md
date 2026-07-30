moon: asking kimi-k3...
# Zitrone 0.10.3 — Attachment blob cleanup: design proposal

## 0. The reframe

The P1 is usually stated as "the abandon is fire-and-forget, so it races the retry." That is a symptom. The disease is that **abandon was issued at points where the client cannot know whether an envelope carrying that blobId has been, or will be, published.** Routes (a) and (c) mark the message FAILED *for retry* — the client itself intends to re-deposit and re-publish under the same memoised id. Abandoning there was never a cleanup decision; it was a bet on the future, placed over a delayable network, against a conceded relay.

So the design is built on one invariant, and everything else is machinery to make the invariant enforceable:

> **An abandon may be issued only at a point where the client can *prove* that no envelope carrying this blobId was ever handed to any socket, and none ever can be.**

Every rejected prior design violated this in one direction (deciding at teardown with unobservable state) or tried to enforce it with a mechanism that cannot work (coroutine-locals, terminal flushes, decision-time retirement). The 0.10.2 code violated it directly: routes (a)/(c) are *pre-retry* states, not terminal ones.

My answer to the brief's question: **do not re-enable the two disabled call sites. Delete them.** Re-enable `abandonBlob` at *different* points where the invariant is provable by construction. This also turns out to fix a second, un-named defect in the current tree, which I deal with first because my design depends on the fix.

---

## 1. The 409 wall: the current tree cannot retry a deposited-then-failed attachment at all

`StoreBlob` is `INSERT … ON CONFLICT (blob_id) DO NOTHING` **plus** `RowsAffected()==0 → ErrBlobExists`, and `DepositBlob` maps that to **409 blob_exists**. `ApiClient.uploadBlob` surfaces any non-2xx as `ApiException`, and `deliverAttachment`'s `onFailure` marks the send failed.

Now trace a retry under the memoised token (0.10.2 item 5a) with abandons disabled (the current tree):

1. Attempt 1 uploads the blob (row created), then fails post-upload — route (a) non-durable flush, or a route (c) throw after `uploadBlob`. Message → FAILED, memo retained.
2. User taps retry. `deliverAttachment(existing=true)` re-encrypts under the memoised token/key and calls `api.uploadBlob` with the **same blobId**.
3. The row still exists → 409 → `ApiException` → `markFailed`. **Every retry fails until the blob TTL expires.**

The memoisation comment ("a retry re-deposits under the SAME blob id (`ON CONFLICT DO NOTHING`)") describes intent the server's `RowsAffected` check silently negates. In the 0.10.2-enabled tree this was masked: the fire-and-forget abandons on routes (a)/(c) usually deleted the row before the user's next tap, so the retry's INSERT landed — the abandon was *load-bearing for liveness*, not just cleanup. Disabling the call sites didn't just lose reclamation; it bricked retries of exactly the failure modes that deposit a blob. (And when the abandon hadn't landed yet, the retry 409'd and spawned a *second* abandon — which is the actual landmine mechanics of the P1: one abandon per failed attempt, all unordered, only the first one "needed.")

**Fix, client-side only, no server change, tolerant of old relays (a supported scenario per the codebase's own kdoc):** in `deliverAttachment`, treat a 409 from `uploadBlob` as *stored* and proceed:

```
catch (e: ApiException) if (e.code == 409) { diag("blob row already present — reusing"); } else throw
```

Why this is sound, and must stay sound (this needs a kdoc, not just a test):

- A 409 means a row exists under `sha256(token)`. The token is a random 256-bit value that has never left the client in cleartext (abandons, under this design, are only ever sent for *dead* ids — see §3). No honest third party can have created that row; it is our own earlier deposit.
- Whichever attempt's bytes the relay kept, they carry their own nonce inside the box, and the memo holds the stable key — the envelope's control payload opens either version. That is the memoisation design's own argument; 409-as-stored is what makes it true on the wire.
- A 409 on a *first* attempt (fresh random token) is a 2⁻²⁵⁶ collision or a lying relay. A relay that fabricates 409 for a deposit it never stored is indistinguishable from a relay that accepts and drops the blob — a capability the conceded relay already has. Accept-and-proceed is therefore honest: availability of attachments is best-effort against the relay by definition, and this leaks nothing new. The prover learns no liveness oracle either: under my design the *client* collapses 409/201, so the server keeps its current opaque behavior.

With 409-as-stored, **a retry never needs the row to be absent.** That severs the liveness dependency on abandon ordering — which is the only reason the fire-and-forget race existed at all.

(Considered and rejected: skipping the re-upload on retry when the memo exists. We cannot know the row survived — the relay can purge early — and a skipped upload against an absent row turns relay malice from "drop a blob" into a *free* recipient-side 404. Re-upload every time: idempotent, self-healing, costs one ON CONFLICT round-trip.)

---

## 2. The publish tail becomes the single handoff point — and records handoffs

All handoffs already funnel through `publishOutgoing` (non-suspending, on the confined worker, compiler-enforced — that structure is kept exactly). Three changes:

**(a) Record the handoff.** New coordinator field alongside `attachmentDeposits`:

```kotlin
private val handedOffEnvelopes = ConcurrentHashMap.newKeySet<String>()
```

`publishOutgoing` adds `messageId` to it **before** `ws.sendMessage(envelope)` on the success path. Entries are removed in `onMessageStored` / `onMessageDelivered` (the same two places `releaseDeposit` already runs — same lifecycle, same bound: unacked outgoing messages), and by the settle path (§3). RAM-only, dies with the process, bounded exactly like the deposit memo.

This ledger is **load-bearing, not hygiene**: the send-timeout (`armSendTimeout`, 90 s) and `markFailedByRelay` both move a *handed-off* message to FAILED. A user who then burns that FAILED bubble has a message whose envelope may still be sitting in the relay, deliverable at any time. Without the ledger, a burn-time cleanup cannot distinguish "FAILED because never sent" (safe to abandon) from "FAILED after handoff, outcome unknowable" (abandoning re-creates the P1 through a side door).

**(b) Guard the handoff on local liveness and state.** Today `publishOutgoing` checks only `contactExists`. Add: the local message must exist **and be in `MessageState.SENDING`** at handoff. This closes a real hole that my cleanup otherwise widens: `burn()` / `burnAll` (UI thread) and `discard` can remove or flip a message to BURNING while its `deliverAttachment` is suspended in `flushSendRatchet`'s backoff; without the guard, the tail would wake up and hand an envelope to the relay for a message the user just burned — and any cleanup that ran at burn time would strand its blob. The check is two in-memory reads inside an already-non-suspending function, so the compiler keeps enforcing "no suspension between barrier and send" at every caller.

The guard creates a new tail branch, which is one of the two safe-abandon points:

```kotlin
private fun publishOutgoing(...): Boolean {
    if (!contactExists(contactId)) {
        messages.discard(messageId)
        settleAfterTerminalDrop(messageId)   // route (b): envelope provably never handed off
        return false
    }
    val local = messages.find(messageId)
    if (local == null || local.state != MessageState.SENDING) {
        // Burned/discarded mid-send: no handoff now or ever — the retryable() CAS
        // requires FAILED and the repository CAS already took this message past it.
        settleAfterTerminalDrop(messageId)   // same proof shape as route (b)
        return false
    }
    if (ws.sendMessage(envelope)) { handedOffEnvelopes.add(messageId); return true }
    messages.markFailed(messageId)
    return false        // socket down: retryable, same-id re-deposit, 409-as-stored. NO abandon.
}
```

(`publishOutgoing` already receives `messageId`; `messages.find` needs to be exposed or an `existsWithState` helper added — mechanical.)

**(c) Routes (a) and (c) abandon sites: deleted.** The non-durable-flush branch and the `onFailure` handler now do *only* `markFailed` (+ diagnostics). The message stays FAILED-retryable with its memo; a retry re-deposits the same id and 409-as-stored makes that a success path. If the user never retries, the blob waits out its TTL — **identical to the current tree** — and §3 covers the user *discarding* it. Note what this buys: across N retries of one message there is now exactly **one** blob row and **zero** abandons, where 0.10.2-enabled had up to N abandons and a landmine, and 0.10.1 had N rows.

---

## 3. The settle point: abandon only the provably-never-published

There remain two ways a blob becomes a true orphan *with the invariant provable*:

1. **Route (b) / mid-send drop** — inside the tail itself (above). The branch is the handoff point; not handing off *there*, on that thread, in that non-suspending slice, *is* the proof.
2. **Terminal local removal of a never-handed-off FAILED/SENDING attachment** — user burns the "!" bubble, `burnAll` from the chat header, contact/conversation deletion discarding its outgoing messages.

For (2), the decision must not be made on the burn hook's thread: the hook (repository scope / UI thread) checking `handedOffEnvelopes` races the confined tail adding to it, with no total order. So the hook never decides — it **defers to the confined worker**, where the decision is serialised against every handoff and every retry:

```kotlin
private fun settleAttachment(messageId: String) {   // called ONLY on `confined`
    val memo = attachmentDeposits[messageId] ?: return          // already settled / acked
    val msg = messages.find(messageId)
    if (msg != null && (msg.state == SENDING || msg.state == FAILED)) return
    // A live SENDING has an in-flight tail that owns the decision; a live FAILED is
    // retryable, and retry() runs on THIS dispatcher — it cannot interleave with this
    // non-suspending slice. Either way: not ours to decide right now.
    releaseDeposit(messageId)                                    // consume the memo FIRST
    if (handedOffEnvelopes.remove(messageId)) {
        return   // envelope was handed off; relay may still deliver it. Blob stays; TTL backstop.
    }
    abandonBlobQuietly(b64(memo.token))                          // invariant holds: never published, never can be
}
```

Triggers (all enqueue onto `confined`):

- The coordinator's `onMessageBurned` hook, for every own attachment message it hears about (`burn`, `burnAll` — both `notifyPeer=true`, so the notifyPeer-gated hook fires; the burn()-flips-BURNING-first constraint is irrelevant because the predicate reads the *repository*, not the message's prior state).
- Remote-burn/TTL paths need no trigger: a peer can only burn what reached them (handed off ⇒ memo long released or ledger-protected), and TTL jobs only exist for delivered messages (memo released at the delivered ack).
- Contact/conversation deletion: for each outgoing message it discards (see MISSING CONTEXT — wherever `deleteContact` wipes the conversation's `MessageRepository` entries; that code already runs on `confined` per the deletion-confinement kdoc, so `settleAttachment` inlines directly).
- The two tail branches from §2 (route (b) and the dropped-mid-send branch) call it inline — they're already the proof point.

`abandonBlobQuietly` is unchanged in shape — fire-and-forget `scope.launch`, failures swallowed, TTL backstop — and that is now *safe*, because staleness no longer matters: a delayed, duplicated, or reordered abandon can only land on an id that no envelope references and no retry will ever re-deposit (the memo is consumed; the message is gone or terminal; the `retryable()` CAS requires FAILED and is serialised against the decision). The relay delaying it arbitrarily changes nothing. Idempotence is structural: first decider consumes the memo, every later decider reads null and no-ops. No abandon is ever issued twice for one id — which also removes the 0.10.2 design's drain on the single shared 60/min `blobLimit` bucket (abandons per retry → abandons per genuine terminal drop).

One subtlety, stated for the record: an abandon *reveals the token* to the relay (the endpoint hashes it). Under this design that only ever happens after the blob is provably unreferenced, so the relay gaining the redemption capability on a dead blob grants nothing — the server endpoint's own rationale.

### Why the serialisation argument holds

Every handoff (`publishOutgoing` true-branch), every abandon decision (`settleAttachment`), and every retry admission (`retry` → `retryable()` → `deliverAttachment` → the same tail) executes on `confined`, and each decision phase is **non-suspending** — so on the single worker each runs to completion with no interleaving, re-reading fresh repository/ledger/memo state at decision time. The burn CAS happens on a foreign thread but only *feeds* these points; it never decides. The two possible orderings of {burn, in-flight tail}:

- tail first: message still SENDING → handoff, ledger entry → settle (later, on the same thread) sees the ledger → **no abandon**. Envelope live, blob live. ✓
- burn/settle first: message BURNING, no ledger entry → abandon, memo consumed → tail (later) finds the message non-SENDING → no handoff, its own settle no-ops on the consumed memo. ✓

There is no third ordering, and no network observation is involved in either. This is the exact property the named shared failure said was missing: the design **never decides from state it cannot observe** — it defers every decision to the one thread that owns the state, and it **never destroys the record before the decision** (the memo survives until the settle point; retirement happens *at* the proof point, and the HTTP 204 is not tracked at all because nothing depends on it).

---

## 4. Route and session-end matrix

| Route | Behavior | Orphan? |
|---|---|---|
| (a) non-durable flush | `markFailed`, memo kept, **no abandon**. Retry re-deposits same id, 409-as-stored | None across retries; TTL only if never retried (= current tree) |
| (b) contact deleted mid-send | tail branch: discard + `settleAttachment` inline → **abandon** | **Reclaimed immediately** (current tree: full TTL) |
| (c) throw (pre-upload, post-upload, transport) | `markFailed`, memo kept, **no abandon**. Pre-upload memo names a non-existent row harmlessly | Same as (a) |
| Socket down at handoff (`false`) | `markFailed`, no ledger entry, no abandon | Same as (a) |
| Send-timeout / `markFailedByRelay` after handoff | ledger entry → settle can never abandon | Blob protected while deliverable; TTL if relay lied (= current tree) |
| Burn / burnAll of FAILED-never-handed-off | hook → `confined` settle → **abandon** | **Reclaimed** (current tree: TTL) |
| Burn / burnAll of handed-off (SENDING, SENT, timeout-FAILED) | settle: ledger → release memo, **no abandon** | TTL (= current tree, deliberately) |
| Burn of in-flight SENDING | §3 ordering: exactly one of {handoff, abandon} | Safe either way |
| **Lock / idle auto-lock** | Nothing. Scope cancelled, settle never runs, **no abandons**. Graceful `disconnect()` may still flush a queued envelope — which is precisely why abandoning here would be wrong | TTL (= current tree) |
| **Logout / session revoked** | Nothing — bearer cleared, `ApiClient` reads it live, any call throws into the void. TTL | TTL (= current tree) |
| **Account delete** | Nothing — account gone, unauthenticated cleanup impossible, and the record is destroyed before any worker could run | TTL (= current tree) |
| **Duress wipe** | Nothing — fastest possible teardown; network calls are out of the question | TTL (= current tree) |
| **Process death** | Nothing possible | TTL (= current tree) |

The teardown column is deliberately empty. That is the lesson of the five rejections applied *affirmatively*: `runTerminalConfined` cannot suspend, the bearer dies with the vault, `coordinator.stop()` does not join in-flight sends, and graceful disconnect makes post-teardown delivery *more* likely while acks become impossible — every one of those facts says a teardown-time abandon is simultaneously uncompleteable and potentially fatal. The server-side `BlobTTLHours` janitor is the only mechanism that owns those routes, and it already exists.

---

## 5. What is enforced by the compiler, by a test, by a comment

**Compiler.** `publishOutgoing` stays a non-suspending `private fun`; the liveness/state guard and ledger write live inside it, so "no suspension between barrier and send, no handoff of a dead message" is enforced at every caller by the type system, as the D2c-round-6 tail already is. `settleAttachment` is non-suspending — the decision slice physically cannot interleave on `confined`. `AttachmentCrypto.encrypt`'s `require` on paired token/key reuse stays.

**Tests.** (1) Property-style unit test with a fake `WsClient`/repository: for all interleavings of {burn, retry, stored-ack, timeout, contact-delete}, *abandon issued ⇒ no handoff ever recorded for that id*. (2) Retry-after-post-upload-failure succeeds against a fake relay returning 409 (regression for the 409 wall). (3) Route (b) issues exactly one abandon; a duplicate settle is a no-op. (4) Send-timeout-then-burn issues no abandon (ledger protection). (5) No `ws.sendMessage` after local burn/discard mid-send (tail guard).

**Comments (load-bearing).** The 409-as-stored kdoc must carry the preimage argument ("only we can have created a row under `sha256(our token)`; the relay can already do worse"), or a future dev will "restore correct error handling" and re-brick retries. The settle kdoc must state the invariant and why the decision may not move off `confined`. The memo kdoc gains one line: an abandon consumes the memo; a published id's memo is released only at ack or terminal-settle.

---

## 6. Comparison to the current tree on reclaimable orphans

The current tree reclaims **nothing** early — every route waits out the TTL — *and* it bricks retries of deposited-then-failed attachments (§1), which is arguably worse than the orphan. This design:

- **reclaims strictly more**: route (b) and user-discard/burn of never-published failures are collected in seconds instead of a week;
- **creates zero new orphan routes** and zero landmines (abandons only for provably-unpublished ids);
- **never abandons on retryable states**, so it cannot strand a retry *and* saves the rate-limit budget and upload bandwidth 0.10.2-enabled spent re-abandoning per attempt;
- is **identical to the current tree** on every teardown/process-death route (TTL backstop);
- restores retry liveness the current tree lacks.

It is not worse than doing nothing on any axis, and it does not trade the DB-enforced one-row-per-message cap for anything: the memoisation is untouched on every path — the cap now actually holds (one row per message *ever*, since retries reuse the row instead of fighting it).

## 7. What this design does NOT fix

- **All six session-end routes and process death**: orphans there live to the TTL. Bounded by `BlobTTLHours`; if the disk-exhaustion model (~2,079 orphans) says that's too slow, the honest lever is the TTL/janitor cadence server-side, not client cleanup — no client mechanism can own those routes, and pretending otherwise is the named failure.
- **Handed-off-then-failed blobs** (timeout, relay-attributed error, burn after handoff): kept to TTL even when probably undeliverable. This is the price of the conceded relay: "handed off, then silent" is indistinguishable from "handed off, queued for an offline peer."
- **Relay malice against availability** generally: a relay can drop stored blobs, 409-lie, or eat abandons. The design only guarantees the client never *helps*.
- The residual heap cost of memos for long-lived FAILED messages (96 B each, bounded by the RAM-only message count, reclaimed at settle/ack/teardown-death).

---

## MISSING CONTEXT

1. **`deleteContact` / conversation deletion**: does it remove the conversation's messages from `MessageRepository`, on which dispatcher, and is there a per-message hook? My design needs its discard loop to call `settleAttachment` per outgoing message (assumed on `confined` per the deletion-confinement kdoc).
2. **Single-message delete**: does the UI offer deleting one own message without burning? If so, it needs the same settle trigger.
3. **`onMessageBurned` wiring**: the coordinator's exact hook implementation and whether `burnAll`'s per-message burns all reach it (`notifyPeer=true` assumed).
4. **`ApiException` shape**: exact field name/type for the HTTP status (`code` assumed from the `redeemBlob` kdoc's "code 404").
5. **`MessageState`**: full enum (assumed SENDING/SENT/DELIVERED/READ/FAILED/BURNING) and whether `find` is or can be exposed on `MessageRepository`.
6. **Teardown internals**: full `runTerminalConfined` body, `coordinator.stop()` (scope cancellation assumed), idle-auto-lock and duress-wipe call paths — needed to confirm no settle trigger can leak into a dying scope (design assumes cancelled scope ⇒ enqueued settles silently never run, which must be verified).
7. **`WsClient.sendMessage`**: exact false-semantics (assumed: frame never enqueued when false).
8. **Server deployability**: whether a server-side change (collapsing 409→201-on-conflict) would be acceptable later as a cleanup; my design deliberately does not require it.
