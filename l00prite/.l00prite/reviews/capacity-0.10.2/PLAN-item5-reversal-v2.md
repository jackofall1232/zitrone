# 0.10.2 item 5 — DESIGN PLAN v2. Review as a DESIGN. v1 verdict was DO NOT SHIP.

Branch `feat/0.10.2-capacity-fixes` @ `f2bc2a1c`. No code written. Nothing merged/pushed/deployed.

## Why v1 failed — do not re-inherit these premises

- **v1 regressed the metric item 5 exists to bound.** `publishOutgoing`'s socket-down exit
  (`MessagingCoordinator.kt:484-494`) returns `false` **without throwing**, so `deliverAttachment`'s
  `runCatching` completes **normally** and route (c) never fires. v1 never enumerated that route. With
  fresh-per-attempt secrets and a **single-slot** memo, attempts 1…N−1 became unreclaimable by any
  path — CX23 exhaustion inside the legitimate 60/min deposit ceiling in ~35 min.
- **v1's "fix first, blocks everything" step was a no-op.** 409 handling is already correct:
  `store.go:283-293` → `ErrBlobExists`, `blobs.go:115-120` → 409 `blob_exists`, pinned in
  `blobs_test.go`, documented at `ApiClient.kt:258`.
- **`limitedParallelism(1)` serialises execution SLICES, not coroutines.** Anything that suspends frees
  the worker. `deliverAttachment` suspends at the prekey fetch, `uploadBlob`, and the flush backoff.
  **What prevents same-message overlap is `MessageRepository.retryable`'s FAILED→SENDING CAS
  (`:142`), not confinement.** Confinement protects nothing about `attachmentDeposits`.
- **`flushSendRatchet`'s ~150 ms is a FLOOR, not a bound** — it omits blocking `flushBeforeAck` calls
  (`VaultRuntime.kt:168-186`), unbounded, worst exactly when retry fires. And the `cover`-throw case is
  **latent, not live**.

## THE STEPS — reordered. v1's order was wrong at both ends.

**Step 1 — reversal + test deletion, ONE commit.** Delete `reuseToken`/`reuseKey` from
`AttachmentCrypto.encrypt`; delete the coordinator wiring; **delete both test classes in
`AttachmentDepositReuseTest.kt` in the same commit** — they call `encrypt(plain, first.token,
first.key)`, so leaving them breaks the **test source set's compilation** and every later step lands
with zero automated signal. Fresh-secrets-per-attempt then becomes **compiler-enforced**, which beats
any grep pin. Delete `AttachmentCrypto.kt:74-99`'s reuse rationale and the "ON CONFLICT DO NOTHING
keeps whichever attempt landed first" comments. **Gate: unit suite green + `assembleDebug`, and verify
the attachment retry path actually works.**

**Step 2 — decide and record the 409 contract. No server diff.** Both OkHttp clients set
`retryOnConnectionFailure(true)` with **no `callTimeout`** (`CertificatePinning.kt:77,133`), so a reset
after the body is written can re-send the POST and 409 on a **non-colliding** id — the 2^-256 argument
is wrong and is deleted. **First check whether the deposit `RequestBody` is one-shot** (if it is,
OkHttp will not replay). Then: since the token carries 256 bits of *our own* entropy, a 409 means *our*
bytes are stored, so **treat it as idempotent success** — or state explicitly why a hard failure is
accepted despite transport replay. Update `ApiClient.uploadBlob`'s kdoc.

**Step 3 — ONE cleanup contract on EVERY `publishOutgoing` exit. This step decides whether the plan
meets its own goal.** Enumerate **route (d)** (`:484-494`) alongside (a), (b), (c). Every `false`
return abandons **this attempt's token, captured in a `val` declared before `runCatching`** — never
read from the map after suspension — and releases the memo. **A new attempt abandons the prior
attempt's token before overwriting the memo, or the memo holds a per-attempt set rather than a slot.**
Do not merge without a **written bounded-orphan argument**.

**Step 4 — `published` as a coroutine-local `var`, not memo state.** The failure axis is **lifetime,
not visibility**: `onMessageStored` and `onMessageDelivered` release the memo **unconditionally**
(`:2280-2298`) from the **OkHttp reader thread** (`WsClient.kt:258,302`), deleting the flag out from
under its own guard. A local in `deliverAttachment`'s frame is the same coroutine as `onFailure`, needs
no barrier, and no other path can delete it. Failure predicate reads **memo-absent ⇒ do nothing**,
never "unpublished". Wrap `coverTraffic.cover` in its **own** swallowing `runCatching` at **all three**
call sites (`:1241`, `:1467`, `:1623`) — mandatory. Also fix `releaseDeposit` firing when `markSent`'s
guard **rejected** the transition, and extend that to `onMessageDelivered`. Note
`MessageRepository.markFailed` accepts **SENDING || SENT**, so it will flip a relay-ACKed message to
FAILED and `retryable` will then arm a second envelope and a second blob — constrain it.

**Step 5 — state-free terminal-cleanup predicate: memo present && !published ⇒ abandon.** State-based
predicates are dead on arrival: `burn()` flips to **BURNING before** invoking the hook
(`MessageRepository.kt:284-289`), so `state == FAILED` never holds on any burn path, and the
deposit→handoff window is SENDING, which a FAILED predicate also excludes — that is the **largest**
orphan window (seconds to minutes over Tor at 8 MiB). Enumerate: burn, `burnAll` (called straight from
the UI thread, `MainActivity.kt:1677`, bypassing the coordinator), remote burn, TTL burn, contact
delete, discard, `clearAll`, teardown. **Move teardown / lock / logout / revoke / account-delete /
duress into "does not fix": TTL only, no abandon** — `api.clearTokens()` runs *before* the queued
`messages.clearAll()` (`:2372` vs `:2383`), so every abandon 401s and is swallowed; and abandoning
during duress teardown is a **deniability regression**. State that reason.

**Step 6 — `messages.exists(messageId)` in `publishOutgoing`'s guard**, re-specified as
`present && state != BURNING`, O(1), synchronous RAM read (R-U3-1-clean). Its `false` exit obeys
step 3's cleanup contract.

**Step 7 — `abandonLimit` with a number.** `blobLimit` is one 60/min limiter shared across deposit,
redeem and abandon (`blobs.go:93,152,172`). Set `abandonLimit ≥ 60/min` + headroom; record the
**overlay bucket collapse** and the **fixed-window 2× burst** caveats; add a client-side
**single-flight cleanup queue** with a real `callTimeout` on a **non-shared** client. Decide and
record: retry a 429'd abandon, or accept the TTL orphan. **A separate limiter does not fix
redeem/deposit sharing** — say so.

**Step 8 — delete every backwards or stale comment**, widened from v1: `:1454-1457` (states the exact
inversion — route (a) "cannot strand a later attempt *because* 5a memoises the token"), the
`AttachmentDeposit` kdoc (`:380-398`), the inline "ONE BLOB PER MESSAGE (0.10.2 item 5a)"
(`:1330-1334`), `sendAttachment`'s "1 week" (actual **96 h**), `AttachmentCrypto.kt:74-99`,
`store.go:307-317`'s `AbandonBlob` rationale naming **three** routes (the same undercount as B1), and
the `ApiClient` kdoc claiming route (b) is covered.

## WHAT THIS DOES NOT FIX — corrected from v1

- **Crash/process-death orphans: TTL only.** Inherent to the RAM-only store.
- **Nothing bounds upload→send.** A frozen process can resume a continuation a day later. **v1's
  "mitigation" that a resumed attempt deposits a fresh blob is STRUCK — it was false.** The `Load()`
  floor closes only the config hole.
- **Teardown/lock/logout/revoke/account-delete/duress: no abandon, TTL only** (401 ordering +
  deniability).
- **Relay malice:** conceded — it can delete or 404 any blob; this removes only self-inflicted deletion.
- **A 429'd abandon leaves an orphan until TTL.** (v1 stated this causality backwards.)
- **`abandonLimit` does not address deposit/redeem sharing or overlay bucket collapse.**

## THE SEQUENCE I AM LEAST CONFIDENT ABOUT — break this specifically

**Step 3's "abandon the prior attempt's token before overwriting the memo" versus step 5's terminal
cleanup, when both can run for one message.** If a retry's pre-overwrite abandon races a terminal
cleanup triggered by a burn, either the same token is abandoned twice (harmless) **or** the memo is
released between the two so the *current* attempt's token is never abandoned at all (orphan to TTL) —
**or worse, the new attempt's token is abandoned while its envelope is in flight.** `abandonBlobQuietly`
is `scope.launch`, so ordering between these is not guaranteed by anything I can see. **Is a
per-attempt set sufficient, does cleanup need to be single-flight per message, or is there an ordering
I have not seen?**

## AND ONE QUESTION BEYOND THIS FIX

The `limitedParallelism(1)` misunderstanding — that it serialises coroutines rather than execution
slices — may be **load-bearing elsewhere**. Sweep the codebase for other places whose correctness
argument rests on `confined` providing mutual exclusion **across suspension points**, and report any
found. U3's cover-traffic confinement reasoning is the first place to look.
