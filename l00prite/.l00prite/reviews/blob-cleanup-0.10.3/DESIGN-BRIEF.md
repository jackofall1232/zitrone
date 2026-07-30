# DESIGN BRIEF — Zitrone 0.10.3: make attachment blob cleanup safe

**READ-ONLY. Do not modify any file. Produce a DESIGN, not code.**

Repo `/root/zitrone`, branch `main`. You are ONE OF THREE independent designers (Grok, Codex,
Kimi K3) working blind. Your design will be judged against the others by five adversarial agents,
and the winner implemented. **Propose the design YOU think is right — do not hedge toward a
consensus you cannot see.**

## The product

Zitrone: zero-knowledge, plausible-deniability encrypted messenger. **THE RELAY IS CONCEDED** — it
sees cleartext sender/recipient ids and can drop, delay, lie, duplicate, reorder. The message store
is **RAM-only** (`MessageRepository`): no database, no file cache, process death takes everything.
**A retry IS a real send.** Nothing may add work, blocking or suspension between the durability
barrier (`flushSendRatchet`) and `ws.sendMessage`; work AFTER `ws.sendMessage` returns is fine.

## The defect to fix

An attachment is encrypted outside the ratchet and uploaded to a blind store BEFORE its envelope is
published. `blobId = sha256(token)`, the token is **memoised per message** (`attachmentDeposits`),
and the relay's `StoreBlob` is `INSERT … ON CONFLICT (blob_id) DO NOTHING`.

Three routes leave a blob nothing will fetch: (a) a non-durable ratchet flush, (b) contact deleted
mid-send, (c) any throw. There is an `AbandonBlob` endpoint and an `ApiClient.abandonBlob`, but
**the client call sites are currently DISABLED** because of a confirmed P1:

> Attempt 1's abandon is fire-and-forget. A retry re-deposits under the SAME memoised id and
> publishes its envelope. The stale abandon then DELETES the blob underneath it. The recipient
> receives a real message whose attachment permanently 404s (`attachmentUnavailable` is terminal),
> and the sender's RAM-only copy dies at the next lock. **Silent to both parties.**

## Constraints and hard-won facts — these are verified, build on them

- **`ws.sendMessage` is an OkHttp ENQUEUE receipt, not relay acceptance.** Relay ownership is
  `message.stored`.
- **`WsClient.disconnect()` closes GRACEFULLY** (queued frames ARE written) **and** nulls the socket,
  after which the `onMessage` identity guard rejects `message.stored`. **Teardown makes delivery MORE
  likely and acknowledgement IMPOSSIBLE.**
- **`coordinator.stop()` does NOT join in-flight sends.** `limitedParallelism(1)` serialises execution
  SLICES, not coroutines — anything that suspends frees the worker. Cancel is not exclusion either (a
  cancelled coroutine still runs its `finally`).
- **`runTerminalConfined`'s body is NON-SUSPENDING** and returns while teardown continues, into a
  `finally` that closes the `VaultRuntime`; `ApiClient` reads the bearer LIVE per call, so any
  post-teardown network call throws and is swallowed.
- **`blobLimit` is ONE 60/min bucket** shared by deposit, redeem AND abandon, degrading to one GLOBAL
  bucket behind the overlay sidecars. It is currently **accidentally self-limiting**.
- **The memoised token + `ON CONFLICT` is a DB-ENFORCED one-row-per-message cap** needing no network
  call, no bearer, no limiter budget and no surviving session. **Do not propose deleting it** — five
  prior designs did and all five were rejected for trading it for best-effort client cleanup.
- Per-call HTTP options must come from `transport.client.newBuilder()` — **never a standalone
  OkHttpClient**, which would egress over the default network and leak the device IP (Tor SOCKS, the
  I2P socket factory, the loopback DNS override and the pinner all live on the transport client).
- `burn()` flips state to BURNING **before** invoking its `notifyPeer`-gated hook, so state-based
  cleanup predicates are dead on arrival; `burnAll` is called from the UI thread.

## Five prior designs were REJECTED. Their shared failure, named:

> **The client tried to DECIDE, at teardown, using state it CANNOT OBSERVE, with a call it CANNOT
> COMPLETE, after DESTROYING the record that would let it try again.**

Rejected specifics, so you do not re-propose them: reversing the memoisation (loses the DB cap); a
`published` flag as a coroutine-local (cleanup runs on foreign stacks and cannot read it); a
terminal teardown flush (cannot complete, cannot suspend, is unauthenticated after `clearTokens`,
never runs on account-delete); retiring a registry entry on *decision* rather than on confirmed 204
(a swallowed abandon failure leaves a blob with no token anywhere); a messageId-keyed cleanup sweep
(hands the abandon trigger to an unauthenticated relay frame).

## What to produce

A design that makes abandon safe to re-enable, or a reasoned argument that it should NOT be
re-enabled and what to do instead. Be concrete about: which routes are covered, what happens at each
session end (lock, idle auto-lock, logout, revoke, account delete, duress wipe), what is enforced by
the compiler vs a test vs a comment, and **what your design does NOT fix**.

**State plainly how your design compares to the CURRENT TREE on reclaimable orphans** — three prior
designs were rejected for being worse than doing nothing.

Prose is fine. Be specific about symbols; do not cite line numbers.
