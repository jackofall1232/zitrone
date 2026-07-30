# ⛔ READ-ONLY. DO NOT MODIFY, CREATE, OR DELETE ANY FILE. DO NOT RUN MUTATING COMMANDS.

**You have repo access. Use it to READ ONLY.** Do not edit, write, format, stage, commit, or run any
build/test command that writes artifacts. Report your analysis as text. Any write is a protocol
violation — this tree is shared with another agent and the working tree you are judging must not be
mutated.

# Third lens — Zitrone 0.10.2 item 5: a COMPOSITION defect, and how to fix it

Repo: `/root/zitrone`. Branch: `feat/0.10.2-capacity-fixes`. Two blind reviewers (Codex, Grok) have
already reviewed this and **both independently rated the same P1**. You are a third lens brought in
for the remediation design, not to re-find the defect.

## What Zitrone is

Zero-knowledge, plausible-deniability encrypted messenger. **The relay is CONCEDED** in the threat
model — it sees cleartext sender/recipient ids and can drop, delay, lie, duplicate, reorder. Message
store is **RAM-only**. **R-U3-1 is absolute:** a real send is never blocked, failed, delayed,
reordered, or made less durable to produce cover traffic — **and a retry IS a real send.**

## The two fixes that compose badly

**Item 5a — one blob per message.** An attachment is encrypted outside the ratchet, uploaded to a
blind store, and the key+token travel inside the ratchet-encrypted control payload.
`blobId = sha256(token)`. Previously `AttachmentCrypto.encrypt` drew a fresh token per call, so every
retry deposited a NEW blob and orphaned the old one (N × up to 8 MiB, each held the full TTL). Fixed
by **memoising** `(token, key)` per message id in `MessagingCoordinator.attachmentDeposits`, so a
retry re-deposits under the **same** blobId. (Deriving the token was rejected: the message id is
cleartext to the relay, and the token is the redemption capability.)

**Item 5b — an abandon endpoint.** `POST /api/v1/blobs/abandon`, authenticated, token-keyed (the
blobId is public, so an id-keyed delete would hand destruction to a public value). Client calls it via
`MessagingCoordinator.abandonBlobQuietly` on route **(a)** a non-durable ratchet flush and route
**(c)** any throw. Fire-and-forget on `scope.launch`, failures swallowed.

## THE CONFIRMED P1 — read it, then design against it

`StoreBlob` is `INSERT … ON CONFLICT (blob_id) DO NOTHING`. Sequence both lenses traced:

1. Attempt 1 uploads blob `R`; its ratchet flush fails; abandon **A1** is launched (not awaited).
2. Retry 1 runs before A1 lands. Its upload conflicts on `R` and fails → abandon **A2** launched.
3. A1 executes, deleting `R`.
4. Retry 2 uploads a fresh box under `R`, flushes durably, and **publishes its envelope successfully.**
5. Delayed **A2** executes `DELETE WHERE blob_id = R` — deleting retry 2's live blob.
6. The recipient receives a real message whose attachment permanently 404s.

**5a is what makes 5b dangerous:** the stable blobId that killed retry amplification is exactly what
lets a stale abandon delete a later attempt's data. Neither fix is wrong alone. Both lenses concluded
the fix is **client-side**, because token and blobId cannot distinguish attempts, so no relay-side
conditional delete can work.

## What I want from you — reason, do not just agree

1. **Is "never abandon while the message is still retryable" sufficient and safe?** A message is
   retryable while FAILED (the UI offers retry only then). If abandon is deferred to the point the
   message stops being retryable — burned, removed, contact deleted, session cleared — does that close
   the race completely, or move it? Note the store is RAM-only, so process death loses the memo and
   the TTL is the only backstop for that path.
2. **Should route (a) abandon at all?** A non-durable flush leaves the message retryable by design, so
   arguably (a) should never abandon and only truly-terminal paths should. What is then left uncovered,
   and is that acceptable given TTL is 96 h?
3. **Is there a client-side generation/attempt scheme that would make abandon safe without a relay
   change?** Both lenses said token/blobId cannot distinguish attempts. Do you agree, or is there a
   construction they missed that keeps blobId stable for dedup while making deletion attempt-specific?
   If it needs a relay change, say what minimal change and what it costs in linkage.
4. **A second-order question neither lens raised:** retry 1's upload FAILS on conflict
   (`ON CONFLICT DO NOTHING` returns success server-side, but the client treats the deposit as
   needing to have stored *its* bytes). Is the conflict itself a defect independent of abandon — i.e.
   does a retry after a successful first deposit behave correctly at all?
5. **Does any option you prefer risk R-U3-1?** Anything that adds work, blocking, or suspension
   between the durability barrier and `ws.sendMessage` is forbidden — deferring the upload into that
   gap was already rejected for exactly this reason.

## Files to read

`apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt` (`deliverAttachment`,
`attachmentDeposits`, `releaseDeposit`, `abandonBlobQuietly`, `publishOutgoing`, `retry`),
`crypto/AttachmentCrypto.kt`, `net/ApiClient.kt`, `data/MessageRepository.kt`,
`server/internal/api/blobs.go`, `server/internal/db/store.go`.

## Output

Prose is fine. Be explicit about which of (1)–(5) you are answering, state your recommended design,
and state what it does NOT fix. **Say plainly if you think both prior lenses are wrong.**
