# Kimi K3 handoff — 0.10.3 attachment blob reclaim review

## How to run it (matters — see l00prite failures record)

```bash
cd /root/zitrone
KIMI_MODEL_THINKING_EFFORT=high kimi        # interactive, NOT -p
```

Then, inside the session, in this order:

1. Enter **plan mode** — this is what makes the session read-only.
2. Turn **`/yolo` on** — only so it stops stalling on tool-call approval prompts.
3. Paste the prompt below.

Do **not** use `kimi -p` (dies mid-review, no verdict — two prior attempts), and do **not** use
`--auto` or `/yolo` *without* plan mode (those auto-approve writes, and a lens must not mutate the
tree it is judging).

---

## The prompt — paste everything below this line

READ-ONLY review. Do not modify, create, or delete any file. Do not run mutating commands.

Repo: /root/zitrone. Review the branch `fix/0.10.3-blob-settle` against `main`:

    git diff main..fix/0.10.3-blob-settle -- apps/android

Read the surrounding files in full, not just the diff. Follow call sites into files the diff does
not name — several real defects in this codebase have hidden exactly there. That exploration is why
you are being asked rather than a diff-only reviewer.

### What the change is for

Zitrone is a zero-knowledge messenger. An attachment is uploaded to the relay as a **blind blob**
before its envelope is published:

- `blobId = sha256(token)`; the **token IS the redemption capability**. `RedeemBlob` is
  deliberately unauthenticated — anyone holding the token can fetch the ciphertext.
- `StoreBlob` is `INSERT ... ON CONFLICT (blob_id) DO NOTHING`, so re-depositing under a memoised
  token is idempotent and DB-capped at one row per message.
- A send that never completes leaves an **orphan blob** on relay disk until its TTL. About 2,079
  orphans exhaust the box's free space, so reclaiming them matters.

This change adds an `abandon` path that reclaims such a blob. **Five previous designs were
rejected**, each because it tried to *arbitrate* the race between a retry and a cleanup. This one
attempts to delete the race's precondition instead: abandon only when no envelope naming the blob
was ever handed to a socket, and none ever can be.

### Invariants it must not break

1. **Never abandon a blob if any envelope naming it reached a socket.** `ws.sendMessage` returning
   true is an OkHttp **enqueue receipt**, not relay acceptance; relay ownership is the
   `message.stored` frame. `WsClient.disconnect()` closes **gracefully** — queued frames ARE
   written — and then nulls the socket, after which the `onMessage` identity guard rejects
   `message.stored`. So teardown makes delivery MORE likely while making acknowledgement
   IMPOSSIBLE, and "handed off but unacknowledged" is systematically populated with blobs the
   recipient can still redeem. Abandoning one silently destroys a delivered attachment: a permanent
   `attachmentUnavailable` for the recipient, and the sender's RAM-only copy dies at the next lock.
   Silent to both parties.
2. **Never abandon while a retry can still name it.** A retry re-deposits under the SAME memoised
   token. SENDING and FAILED are both retryable (`retryable()`'s CAS accepts only FAILED).
3. **Disclosure invariant.** Sending a token to the relay concedes an unauthenticated redemption
   capability. Acceptable ONLY because the blob dies in the same breath. Any non-deleting outcome —
   429 parsed before the body, 401 after the bearer dies, dropped response — leaves a conceded
   relay holding a live capability for a blob that still exists.
4. **R-U3-1**: a real send is never blocked, failed, delayed, reordered, or made less durable. A
   retry IS a real send.
5. No unbounded heap growth; no state expected to outlive the process (`MessageRepository` is
   RAM-only — a crash takes the bubble with it).

### Concurrency facts that have produced wrong conclusions here before

- `Dispatchers.IO.limitedParallelism(1)` (the `confined` worker) serialises execution **slices**,
  not coroutines. **Anything that suspends frees the worker**, so two coroutines on `confined` can
  interleave across any suspension point.
- Cancellation is **not** exclusion — a cancelled coroutine still runs its `finally`. Only `join`
  is exclusion.
- `releaseDeposit` is reachable from `WsClient.onMessage -> dispatchFrame -> onMessageStored`,
  i.e. from a socket callback thread, **not** `confined`.
- Both shared OkHttp clients set `readTimeout(0)`.

### What I want

Find defects. Be specific and adversarial. For each: file and symbol (**symbols, not line numbers**
— line citations in this project have been wrong by a constant offset before), a concrete
interleaving or input that triggers it, and the user-visible consequence. Rank P1/P2/P3 by
user-visible impact.

Answer each of these explicitly, with evidence from source:

- **Q1.** Can `settleAttachment` ever issue an abandon for a blob whose envelope reached a socket?
  Construct the interleaving, or prove it cannot.
- **Q2.** `settleAttachment` reads its decision inputs, then clears the handoff record, then does an
  atomic `attachmentDeposits.remove` as its claim. Is that ordering wrong — in particular, the
  handoff record is cleared before a claim that may then LOSE. Does that matter?
- **Q3.** Is `handedOffMessages` genuinely monotone across a retry? Trace an attempt that hands off,
  fails, is retried, and is then torn down.
- **Q4.** `releaseDeposit` clears both the deposit memo and the handoff record and runs off the
  `confined` worker. Is that safe, and can it turn a would-be RELEASE_ONLY into an ABANDON?
- **Q5.** Is the single wired call site (the contact-deleted-mid-send branch of `publishOutgoing`)
  reachable only when nothing was ever handed off — including across retries?
- **Q6.** `settleAttachment` launches the network abandon in `scope.launch(confined + NonCancellable)`.
  Assess lifetime, ordering against a concurrent retry, and behaviour if the scope is already
  cancelled.
- **Q7.** Does `MessageRepository.stateOf` introduce a lock-order or aliasing problem, and is the
  SENDING/FAILED skip in `settleDecision` sufficient to cover every retryable condition?
- **Q8.** Is `BLOB_ABANDON_TIMEOUT_MS` applied as a genuine per-call timeout, and is 30s defensible?
  Given there is no restore-on-failure, does a timed-out or failed abandon leave a sound state?
- **Q9.** Anything tautological in `AttachmentSettleDecisionTest`, or any mutation of
  `settleDecision` it would NOT catch?
- **Q10.** The change wires `settleAttachment` at only ONE call site and leaves the contact-deletion
  and burn reclaim paths unwired. Separately, `onMessageBurned` is `notifyPeer`-gated while
  `deleteContact` passes `notifyPeer = false`. Verify both claims at source and say what the real
  reclaim coverage is.

If you think it is sound, say so plainly and state what you verified. Note that a "CLEAN" is the
absence of a finding, not the presence of a proof — only positive claims can be checked against
source.
