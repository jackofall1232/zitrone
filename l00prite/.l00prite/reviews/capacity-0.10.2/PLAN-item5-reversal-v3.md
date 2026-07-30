# 0.10.2 item 5 — DESIGN PLAN v3. Built AROUND a per-token registry. v1 and v2 were both DO NOT SHIP.

Branch `feat/0.10.2-capacity-fixes` @ `612b6bd2`. No item-5 code written to this design yet.

## Why v3 is a different shape, not another round of patches

v1 and v2 both tried to make **`messageId`** the unit of cleanup bookkeeping. Every failure followed
from that: a single-slot memo keyed by message meant a retry orphaned its predecessor (v1 B1), and a
`published` flag scoped to one attempt's coroutine could not be read by cleanup running on a foreign
stack (v2 B1, all five lenses). **The unit of bookkeeping must be the ATTEMPT, because the thing being
cleaned up is a blob, and each attempt deposits its own.**

**Two things are already fixed and are NOT in this plan** — done independently because neither needed
the design: the blob deposit now has a per-call `callTimeout` via `Call.timeout()` (`612b6bd2`,
addressing v2 B5), and the two stale confinement comments are corrected (`dc145ad5`). v2 B3's
standalone-client deanonymisation risk **never existed in the tree** — it was a plan-only step, now a
recorded rule: per-call options come from `transport.client.newBuilder()`, never a fresh client.

## THE MECHANISM

A single `ConcurrentHashMap<String, DepositEntry>` **keyed by the blob token** (per attempt), value
carrying `messageId` and a state:

```
RESERVED   — token drawn, upload not yet known to have landed
DEPOSITED  — the relay accepted the bytes; this blob is reclaimable and MUST be reclaimed on failure
HANDED_OFF — ws.sendMessage returned true for the envelope naming this token; the relay owns it now
```

**Three rules, and every one of them is why a previous plan failed:**

1. **Every state transition and every removal is a single atomic `remove`/`compute` on the map, and
   the ABANDON DECISION IS THE RETURN VALUE of that operation.** Never read-then-act: that is v2 B4's
   stale-read (route (c) abandoning the shared memo's token after suspension) and v1 B1's
   overwrite-loses-predecessor in one stroke.
2. **`RESERVED → DEPOSITED → HANDED_OFF` is performed by the sending coroutine itself**, and
   `HANDED_OFF` is set immediately **after** `ws.sendMessage` returns true. R-U3-1 forbids work
   *between* the durability barrier and `ws.sendMessage`, **not after it** — so this is legal, and it
   is the only place that knows the handoff happened.
3. **Cleanup abandons exactly the entries it atomically removed that were `DEPOSITED`.** `RESERVED`
   means nothing landed, so nothing to abandon. `HANDED_OFF` means the relay owns the blob and the
   recipient may still redeem it — **abandoning one is the data-loss defect v2 B1 identified**.

**Why this dissolves rather than guards:** a stale cleanup from attempt N can only ever name attempt
N's own token, and that token's entry is either already gone (someone else removed it atomically) or
is `HANDED_OFF` (excluded). It is structurally incapable of touching attempt N+1's blob.

## THE STEPS

**Step 1 — reversal + test deletion + registry, ONE COMMIT (v2 B4 requires this).** Deleting
`reuseToken`/`reuseKey` from `AttachmentCrypto.encrypt` breaks `AttachmentDepositReuseTest.kt`'s
**compilation** (`:42,:57,:70,:84,:87`), so a separate commit would land later steps with **zero
automated signal**. In one commit: delete the reuse parameters and their wiring; delete both test
classes in that file; introduce the registry with `RESERVED` set before the upload and `DEPOSITED` on
success. Fresh-secrets-per-attempt becomes **compiler-enforced** by the parameters not existing —
stronger than any grep pin, which is why the deleted tests are not replaced with source assertions.
Also delete `AttachmentCrypto.kt:74-99`'s reuse rationale and the "ON CONFLICT DO NOTHING keeps
whichever attempt landed first" comments, which encode v1 B2's false premise.
**Gate:** unit suite green, `assembleDebug`, and **verify the attachment retry path actually works**.

**Step 2 — `HANDED_OFF` at the handoff, and one cleanup contract on EVERY `publishOutgoing` exit.**
Set `HANDED_OFF` immediately after `ws.sendMessage` returns true. Then enumerate **all five** exits —
including **route (d)**, the socket-down exit at `MessagingCoordinator.kt:484-494` which returns
`false` **without throwing** (this is what killed v1: `runCatching` completes normally and route (c)
never fires), and **route (e)**, coroutine cancellation, which route (c) currently rethrows at `:1469`
**before** any cleanup. Every one of the five removes this attempt's entry atomically and abandons iff
it was `DEPOSITED`.

**Step 3 — cleanup runs on a `NonCancellable` process-scope issuer, gated off for duress.**
`abandonBlobQuietly` currently uses the per-unlock session scope, which `UnlockController.lockCurrent()`
cancels (`UnlockController.kt:134-135`) — so today every queued cleanup is **silently discarded at the
next lock**, and idle auto-lock (`ZitroneApp.kt:983-996`) is the highest-frequency teardown in the
product, adversary-free, orphaning up to 8 MiB on every in-flight photo send. Cleanup must outlive the
session scope. **But it must NOT run during duress teardown** — a burst of abandons after a Pucker Burn
is a deniability signal. So: `NonCancellable` on a process scope, **explicitly disabled for duress /
revoke / account-delete**, enabled for lock / logout.

**Step 4 — a terminal-cleanup HOOK that exists (v2 B7).** Naming routes is not enough:
`MessageRepository.onMessageBurned` fires only `if (notifyPeer)` (`:289`), and TTL burn (`:354`),
remote burn (`:307`) and the **vault contact delete** (`MessagingCoordinator.kt:1727`) all pass
`notifyPeer = false` — and the vault path is the **default on every shipped install**. `clearAll`
(`:311-319`) swaps in `emptyMap()` and iterates nothing. So: add a cleanup call in `burn`/`remove`
**independent of `notifyPeer`**, and explicit iteration in `clearAll`. Cleanup for a messageId means
"remove every registry entry whose `messageId` matches, abandon those that were `DEPOSITED`" — no
message-state predicate, because `burn()` flips to **BURNING before** invoking any hook
(`MessageRepository.kt:284-289`), so every state-based predicate v2 proposed was dead on arrival.

**Step 5 — constrain `markFailed`, and stop relay frames removing entries (v2 B2).**
`MessageRepository.markFailed` accepts `SENDING || SENT`, so it will flip a **relay-ACKed** message to
FAILED, after which `retryable` arms a second envelope and a second blob. And `onMessageStored` /
`onMessageDelivered` run on the **OkHttp reader thread** with no proof we sent that id. Registry
removal is keyed by **token**, so a relay frame carrying only a `messageId` **cannot** remove an
attempt's entry — that is the fix, and it must stay that way. Additionally: `markSent` must report
whether it applied, so nothing releases on a rejected transition.

**Step 6 — `messages.exists(messageId)` in `publishOutgoing`'s guard**, specified as
`present && state != BURNING`, O(1), synchronous RAM read (R-U3-1-clean). Its `false` exit obeys
step 2's contract.

**Step 7 — `abandonLimit`, separate from `blobLimit`.** One 60/min limiter currently serves deposit,
redeem **and** abandon (`blobs.go:93,152,172`), so cleanup can starve a real upload. Give abandon its
own bucket with headroom; record that this does **not** fix deposit/redeem sharing or overlay bucket
collapse; add a client-side single-flight cleanup queue. **No standalone OkHttp client** — per-call
options via `transport.client.newBuilder()` only (v2 B3).

**Step 8 — delete every stale or backwards comment**, widened: `MessagingCoordinator.kt:1454-1457`
(states the exact inversion), the `AttachmentDeposit` kdoc (`:380-398`), the inline "ONE BLOB PER
MESSAGE (0.10.2 item 5a)" (`:1330-1334`), `sendAttachment`'s "1 week" (actual **96 h**),
`store.go:307-317`'s `AbandonBlob` rationale naming **three** routes (the same undercount as v1 B1),
and the `ApiClient` kdoc claiming route (b) is covered.

**Step 9 — the 409 contract.** With fresh-per-attempt tokens a conflict cannot be an id collision from
*our* side, but it **is** reachable without one: both clients set `retryOnConnectionFailure(true)`
(`CertificatePinning.kt:77,133`) so a reset after the body is written can replay the POST and 409 on our
own just-stored id. **Check whether the deposit `RequestBody` is one-shot** (if so, OkHttp will not
replay). Since the token carries 256 bits of our own entropy, **a 409 means our bytes are stored →
treat it as `DEPOSITED`, not as failure.** Delete the 2^-256 argument. Server unchanged.

## WHAT THIS DOES NOT FIX — and the bound is honest this time (v2 B8)

**A fully client-side bounded-orphan argument is not available, so this plan does not claim one.** What
remains reachable, exhaustively:

- **Process death / crash** between `DEPOSITED` and cleanup — TTL only. Inherent to the RAM-only store.
- **Cancellation before the `NonCancellable` dispatch lands** — a narrow window, TTL.
- **An abandon that cannot reach the network** (offline, 429, overlay down) — TTL.
- **Duress / revoke / account-delete teardown** — cleanup deliberately **disabled**; TTL. The
  deniability reason is stated, and it applies to those paths **only**, not to lock/logout.
- **Nothing bounds upload→send.** A frozen process can resume a continuation much later. v1's claimed
  mitigation was struck as false; the `Load()` floor closes only the config hole.
- **Relay malice** — conceded; it can delete or 404 any blob. This removes only *self-inflicted*
  deletion.

**So the honest claim is narrow and checkable: the registry eliminates every orphan route the client
KNOWS about at the moment it fails, and reduces the residue to crash, cancellation-race, unreachable
network, and deliberate duress silence — all TTL-bounded at 96 h.** That is materially better than
today (where the abandon can delete a *live* blob) and better than v1 (which multiplied orphans), but
it is not "no orphans" and must not be written as such.

## THE SEQUENCE I AM LEAST CONFIDENT ABOUT — break this specifically

**Step 2's `HANDED_OFF` transition versus step 4's terminal cleanup, when a burn lands between
`ws.sendMessage` returning true and the `compute` that sets `HANDED_OFF`.** In that window the entry is
still `DEPOSITED`, so cleanup will atomically remove it and **abandon a blob whose envelope the relay
has already accepted** — the exact data-loss shape v2 B1 rejected, reintroduced through a narrower
window. Both operations are atomic *individually*, which is precisely why this may not be enough.
**Is setting `HANDED_OFF` before `ws.sendMessage` (accepting an orphan if the send then fails) the
correct trade, or does the entry need a fourth state, or must the handoff and the transition be one
atomic step — and is that even possible given `ws.sendMessage` is the non-suspending publish tail?**
