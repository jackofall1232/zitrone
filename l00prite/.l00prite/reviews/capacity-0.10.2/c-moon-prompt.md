# OUTPUT CONTRACT — read this first, answer in exactly these sections

```
# ANSWERS
(one numbered section per question Q1–Q5 below; answer each explicitly, or say "no view")

# RECOMMENDED DESIGN
(the design you would ship, and WHAT IT DOES NOT FIX)

# DISSENT
(state plainly if you think either prior lens is wrong, and on what fact)

# MISSING CONTEXT
(every file, symbol, or call site you needed to inspect but were not given, and the defect
class you would have checked for there — this becomes a work item, so be specific)
```

**You have no repo access and no tools.** You see exactly the files attached, nothing else. Do not
speculate about code you were not given — put it in MISSING CONTEXT instead. This is a design
consultation, not a full unit review: two blind reviewers have already found the defect and agree on
its severity. **I want your reasoning on the remediation, and your dissent if you have it.**

# MANIFEST of attached files

1. `MessagingCoordinator.kt` — the unit. `deliverAttachment` (the attachment send path),
   `attachmentDeposits` + `AttachmentDeposit` + `releaseDeposit` + `abandonBlobQuietly` (the memo and
   cleanup, both new), `publishOutgoing` (the non-suspending publish tail — the socket handoff),
   `retry`, `onMessageStored`/`onMessageDelivered` (where the memo is released).
2. `AttachmentCrypto.kt` — blob encryption. `encrypt(plain, reuseToken, reuseKey)` is the 5a change.
3. `ApiClient.kt` — `uploadBlob`, `abandonBlob` (new).
4. `MessageRepository.kt` — RAM-only message store and the state machine
   (`markSent`/`markDelivered`/`markFailed`/`markFailedByRelay`/`retryable`/`burn`/`remove`/`clearAll`),
   plus the send timeout. **This is the lifecycle owner** and defines when a message is "retryable".
5. `blobs.go` — relay side: `DepositBlob`, `RedeemBlob`, `AbandonBlob` (new).
6. `store.go` — `StoreBlob` (`ON CONFLICT (blob_id) DO NOTHING`), `RedeemBlob`, `AbandonBlob`.
7. `AttachmentDepositReuseTest.kt` — the 5a tests, including a source-only wiring tripwire.
8. `blobs_test.go` — relay blob tests.

# Context

Zitrone: zero-knowledge, plausible-deniability messenger. **The relay is CONCEDED** — sees cleartext
sender/recipient ids, can drop, delay, lie, duplicate, reorder. **Message store is RAM-only**: process
death loses everything, so a retry only ever happens inside one process lifetime.

**R-U3-1 is absolute:** a real send is never blocked, failed, delayed, reordered, or made less durable
to produce cover traffic — **and a retry IS a real send.** Nothing may add work, blocking, or
suspension between the durability barrier (`flushSendRatchet`) and `ws.sendMessage`. Deferring the
blob upload into that gap was already rejected for exactly this reason: an 8 MiB Tor upload there turns
a process death into a lost message whose ratchet already advanced.

## The two changes that compose badly

**5a — one blob per message.** `blobId = sha256(token)`; the token is the redemption capability and
travels inside the ratchet-encrypted control payload. Previously a fresh token per `encrypt` call meant
**every retry deposited a NEW blob and orphaned the old one** (N × up to 8 MiB, each held the full
96 h TTL; one blob ≈ 545 accounts' worth of disk). Fixed by **memoising `(token, key)` per message id**
so a retry re-deposits under the **same** blobId. Deriving the token was rejected: the message id is
cleartext to the relay, so a relay-computable token would hand the relay redemption.

**5b — abandon endpoint.** Token-keyed (blobId is public), authenticated, fire-and-forget via
`scope.launch`, failures swallowed. Called on route **(a)** non-durable ratchet flush and route **(c)**
any throw. Route **(b)** contact-deleted-mid-send is knowingly uncovered because the check lives inside
non-suspending `publishOutgoing`.

## THE CONFIRMED P1 (both prior lenses, independently)

1. Attempt 1 uploads blob `R`; ratchet flush fails; abandon **A1** launched, not awaited.
2. Retry 1 runs before A1 lands; its upload conflicts on `R`; that failure launches abandon **A2**.
3. A1 executes, deleting `R`.
4. Retry 2 uploads a fresh box under `R`, flushes durably, **publishes its envelope successfully**.
5. Delayed **A2** deletes retry 2's live blob.
6. Recipient gets a real message whose attachment permanently 404s.

**5a is what makes 5b dangerous:** the stable blobId that killed retry amplification is exactly what
lets a stale abandon delete a later attempt's data. Both lenses concluded the fix is **client-side**,
because token and blobId cannot distinguish attempts.

# QUESTIONS

**Q1.** Is *"never abandon while the message is still retryable"* sufficient and safe? A message is
retryable while FAILED (the UI offers retry only then). If abandon is deferred until the message stops
being retryable — burned, removed, contact deleted, session cleared — does that close the race
completely or merely move it? Note the RAM-only store: process death loses the memo and the 96 h TTL is
the only backstop for that path.

**Q2.** Should route (a) abandon at all? A non-durable flush leaves the message retryable *by design*,
so arguably only truly-terminal paths should abandon. What is then left uncovered, and is that
acceptable at a 96 h TTL?

**Q3.** Is there a client-side attempt/generation scheme that makes deletion attempt-specific while
keeping blobId stable for dedup? Both lenses said token/blobId cannot distinguish attempts — **do you
agree, or is there a construction they missed?** If it requires a relay change, name the minimal one
and what it costs in stored linkage (the relay must not learn who deposited what).

**Q4.** A second-order question neither lens raised: retry 1's upload *fails* on conflict.
`ON CONFLICT DO NOTHING` succeeds server-side, but does the client treat a conflicting deposit
correctly? **Is retry-after-successful-first-deposit broken independently of abandon?**

**Q5.** Does your preferred option risk R-U3-1 anywhere?
