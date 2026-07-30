# 0.10.2 item 5 — DESIGN PLAN v5. Reclaim is a LIVE-SESSION activity. v1–v4 all DO NOT SHIP.

Branch `feat/0.10.2-capacity-fixes` @ `2409ca56`. **No item-5 code has been written to any design.**
**No line numbers in this plan** — symbols only. v3's were uniformly 8 lines low and two of its deletion
ranges would have destroyed live code; offsets are derived at implementation, against the commit edited.

## THE PATTERN FOUR ROUNDS SHARED — v5 attacks this, not its symptoms

> **The client tried to DECIDE, at teardown, using state it CANNOT OBSERVE, with a call it CANNOT
> COMPLETE, after DESTROYING the record that would let it try again.** Patch any one and the other three
> kill the plan.

v5 removes all four at once: it decides **during the live session** (not teardown), on **positive
evidence** (not inference), with a call that **can complete and be retried** (not swallowed), retiring
the record only on **confirmed success** (not on decision).

**What survives from v4 — every lens said keep these:** the per-attempt registry shape, single-writer
abandon, and states that name observables. **Mechanism 2 (the terminal teardown flush) does not
survive and is deleted.**

## M1 — CONFIRMED-REMOVAL, NOT DECIDED-REMOVAL

**A registry entry is retired when `abandonBlob` returns 204 — never when a party wins the `remove`.**

v4 retired on decision, and because `abandonBlobQuietly` swallows failures, a 429, a 401 or a dying link
left a blob with **no token anywhere in the process**. That converted the current tree's self-healing
one-row residual into **k permanent 8 MiB rows**, on precisely the failure the retry button exists for —
where the abandon's failure is *correlated* with the send's. It was the strongest finding of the v4 pass.

Until the 204 arrives the token stays and the abandon is **retryable and idempotent** — `AbandonBlob` is
an unconditional `DELETE` answering 204 either way, so retries are free and reveal nothing. **This is the
single change that makes fresh-secrets-per-attempt safe**: without it, deleting the stable `blobId` gives
up the `ON CONFLICT (blob_id) DO NOTHING` bound that caps a message at one row regardless of k retries
*and regardless of the network*. Deciding and retiring become different events, which preserves
single-writer's real property — **one decider per attempt** — without its fatal coupling.

## M2 — RECLAIM IS A LIVE-SESSION ACTIVITY. THE TEARDOWN FLUSH IS DELETED.

**Every** defect the v4 pass found in mechanism 2 followed from one decision: attempting authenticated
network I/O inside a synchronous vault-key-wipe teardown. The flush **could not complete or even
suspend** (`runTerminalConfined`'s body is non-suspending; it returns while teardown still runs, into a
`finally` that closes the runtime, after which every bearer read throws); it **never ran on account
delete** (the terminal CAS is already won) and was **unauthenticated on revoke** (tokens cleared first);
and the "duress by construction" claim was true only by a state accident on a path written assuming the
opposite.

**Replace it with a session-scoped reclaim queue that drains continuously while the session is live** —
against a live socket, a live bearer, an open vault and a real rate limit. An entry whose abandon fails
is **re-queued with backoff** and retried. Nothing runs during teardown; nothing needs `NonCancellable`,
a process scope, a captured credential, or a duress gate. **The duress question disappears for real this
time**, because there is no teardown-time network activity to gate.

**Consequence stated plainly:** an entry still unreclaimed when the session ends is **not** reclaimed —
it waits out the 96 h TTL. That is a residual, and it is the *same* residual the current tree has.

## M3 — `ENQUEUED` IS NEVER ABANDONED. THE RULING IS AN INVARIANT.

**Ruled by the v4 pass, and the "symmetric tradeoff" framing was wrong — provably, at source.**
`WsClient.disconnect()` closes **gracefully**, so queued frames *are* written; it then nulls the socket,
and the `onMessage` identity guard rejects the relay's `message.stored`. **The teardown therefore makes
delivery MORE likely and acknowledgement IMPOSSIBLE.** So `ENQUEUED` entries at teardown are
systematically ones the relay already owns which can *never* reach `CONFIRMED` — and abandoning them
destroys a blob whose envelope is store-and-forwarded to a recipient who then sees a terminal
UNAVAILABLE, while the sender's RAM-only copy dies at the lock. Silent to both parties. **Excluding
`CONFIRMED` excludes nothing, because the teardown manufactures the ambiguity.**

**So: `ENQUEUED` is never abandoned — the same class of invariant as `CONFIRMED`.** One branch is an
orphan; the other is data loss. There is no tradeoff to tune.

**If v5 ever wants to reclaim the enqueue-then-dropped route, the signal must be POSITIVE EVIDENCE OF
NON-DELIVERY** — the socket ended in `onFailure` with a non-empty outbound queue, snapshotted **per
socket epoch at `send() == true`**, recorded **while the socket is live**, and consumed by M2's queue.
**Absent that evidence: TTL.** And any wait for `message.stored` inside teardown would be a **new
explicit bound on the vault key wipe** and must be priced as such — never hidden inside "rides the
existing bound".

**Not in scope for v5:** the epoch-snapshot evidence channel. Recorded as the only sound way to close
that route, deliberately deferred.

## ORDER OF WORK — each item independently shippable

1. **NOW, needing none of this design: add the abandon to `publishOutgoing`'s contact-deleted branch,
   and correct `ApiClient.abandonBlob`'s kdoc** (it claims a route it does not cover). This is the one
   route where v4 was unambiguously better than the tree and it depends on nothing above.
2. **SERVER FIRST: a separate limiter for `AbandonBlob`.** It currently shares one 60/min bucket with
   deposit *and* redeem, which behind the overlay sidecars degrades to **one global bucket** — so an
   abandon burst can 429 the user's next upload **or a third party's redeem**, surfacing as terminal
   `attachmentUnavailable` for a live blob. v1 mandated this; v4 dropped it. **Nothing that bursts
   abandons may land before it.**
3. **Registry + reversal in ONE commit** (deleting `reuseToken`/`reuseKey` breaks the reuse tests'
   *compilation*, so a split leaves later steps with no automated signal), with **M1 confirmed-removal
   semantics and `ENQUEUED`/`CONFIRMED` present in the same commit** — v4's step boundary would have
   landed owner-exit abandon while only `UPLOADING`/`STORED` existed, destroying the blob of every
   *successful* send, and nothing in the suite can construct `MessagingCoordinator` to catch it.
4. **Enumerate every `publishOutgoing` exit inside that commit.** Two `return false` routes do **not**
   throw — contact-deleted and `sendMessage`-false — so the catch-all never sees them. Both were absent
   from v4. A `false` return is not a failure, and the owner rule must say so explicitly.
5. **The session-scoped reclaim queue** (M2), with bounded concurrency and a per-call deadline via
   `Call.timeout()` — **never a standalone `OkHttpClient`**: the transport client carries Tor SOCKS, the
   I2P socket factory, the loopback DNS override and the pinner, and a fresh one egresses over the
   default network, which is a deanonymisation defect.
6. **Mark-only cleanup hooks**, independent of `notifyPeer` (the existing hook is gated on it and the
   vault contact-delete path — the default on shipped installs — passes `false`), plus explicit
   iteration in `clearAll`. **No state predicate**: `burn` flips to BURNING before invoking hooks.
7. **Comment deletions, every range read before deleting.**

## WHAT THIS DOES NOT FIX

- **Crash / process death** — TTL. Inherent to the RAM-only store.
- **Session ends with entries still queued** — TTL. **Same as the current tree.**
- **Enqueue-then-dropped frames** — TTL, until and unless the M3 evidence channel is built.
- **Duress wipe** — no teardown network activity exists to run, so nothing is emitted. Not a gate; an
  absence.
- **Account delete / revoke** — the queue dies with the session like any other end. TTL.
- **Nothing bounds upload→send.**
- **Relay malice** — conceded; removes only *self-inflicted* deletion.

**The claim, and it must be measured against the CURRENT TREE, not against v4:** v5 reclaims strictly
more than the tree does (the contact-deleted route, plus every failed attempt whose abandon succeeds
during the session) and **never less**, because M1 keeps the token until a 204 and M3 forbids the one
destructive branch. **No route in v5 destroys a blob the tree would have kept.**

## THE SEQUENCE I AM LEAST CONFIDENT ABOUT

**M1's confirmed-removal versus M2's queue under a re-queue storm.** If an entry is retired only on 204,
and the abandon keeps failing — offline, 429 from the shared limiter before item 2 lands, or an overlay
outage — the queue holds it, retries with backoff, and **the registry grows without bound for the
session's lifetime**, holding 96-byte entries per failed attempt. Worse, the retry traffic is correlated
with failure, so the storm is exactly when the limiter is tightest. **Is a bounded queue with a drop
policy required — and if entries can be dropped, what retires them, given dropping is precisely the
decided-removal M1 forbids?**
