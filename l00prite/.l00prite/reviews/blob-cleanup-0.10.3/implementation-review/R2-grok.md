I'll do a read-only claim audit of `f730cc48` against source — starting with the protocol docs and the commit itself, then verifying each corrected claim.Next I'll pull the full commit diffs and trace each corrected claim to its source sites.The DepositBlob linkage claim looks shaky against the server comments — verifying the store schema and the remaining claim sites.Working tree and commit may disagree on the DepositBlob claim — checking what's actually on the branch tip.Checking one more residual: the “two routes” reclaim claim and whether `releaseDeposit`’s “ONLY here” is still in the commit.# Round-2 review: `f730cc48` claim-correction delta

**Scope:** commit `f730cc48` only (read-only).  
**Note:** the working tree has **uncommitted** edits to `ApiClient.abandonBlob` and `settleAttachment` that already rewrite the DepositBlob-linkage residual. Those are **not** in the commit under review; findings below are against **`f730cc48` / HEAD**.

---

## Verdict

The design/safety story for `settleDecision` is still sound. Several round-1 corrections **check out**. The commit also **introduces a new false security claim** (DepositBlob “already links account → blob id”) in the very residual kdoc it rewrote, and leaves other stale wiring claims untouched. **Not clean.**

---

## Findings (ranked)

### P2 — False residual: “`DepositBlob` already links the account to the blob id”

**Where:** `ApiClient.abandonBlob` kdoc; `MessagingCoordinator.settleAttachment` NonCancellable residual; also the `f730cc48` commit message itself.

**Claim (committed):** Abandon token disclosure on 429/401 is tolerable because AbandonBlob is authenticated **and DepositBlob already links account to blob id**, so the relay “learns no new linkage” / “gains no capability.”

**What source says:**

| Source | Fact |
|--------|------|
| `server/internal/api/blobs.go` `DepositBlob` | Account used **only to gate admission** and is **“never associated with the stored blob.”** |
| `server/internal/db/store.go` `StoreBlob` | `INSERT INTO blobs (blob_id, ciphertext, expires_at)` — **no account id.** |
| `server/internal/db/schema.sql` `blobs` | `blob_id` PK; **“no sender field, by design.”** |
| `server/cmd/server/main.go` | `POST /blobs` and `POST /blobs/abandon` both `RequireAuth` — auth ≠ durable linkage. |

**Trigger:** Any reader of the corrected residual (or the commit message) treats blind deposit as already linked.

**User-visible / security consequence:** This is the same class of defect round-1 hunted: a confident false invariant. A later change can reintroduce account↔blob persistence “because we already link,” or understate abandon as “no new disclosure.” Abandon on an authenticated channel **is** more disclosive than deposit: token rides with a JWT, so a logging/compromised relay can form a **transient** account → token → `sha256(token)` association the store was built not to keep.

**What remains true in that paragraph:** AbandonBlob is authenticated; 429 path does not delete; relay can drop rows at will. The **linkage** justification is the false link.

---

### P2 — Stale wiring claim: abandon “reclaims on non-durable flush **and** contact-deleted”

**Where:** `ApiClient.abandonBlob` kdoc (“the two routes the client actually knows about: a non-durable ratchet flush, and a contact deleted mid-send”).

**What source says:**

- Only production call into the settle/abandon path is `publishOutgoing` → `settleAttachment` on the **contact-deleted** branch.
- On non-durable flush after deposit (`deliverAttachment`), abandon is **explicitly disabled** with a multi-line “ABANDON DISABLED HERE — the confirmed P1” comment; the path only `markFailed`s and returns.

**Trigger:** Contributor wires or documents as if route (a) already reclaims.

**Consequence:** Docs claim a reclaim that never runs; orphans on non-durable flush still wait out blob TTL (up to multi‑MiB). f730 “corrected every claim” but left this one.

---

### P2 — `releaseDeposit` kdoc still overclaims its sites and exclusivity

**Where:** `MessagingCoordinator.releaseDeposit`.

**False bits:**

1. **“Called on every terminal outcome … local copy was discarded, or it burned.”**  
   Actual `releaseDeposit(messageId)` sites: `onMessageStored`, `onMessageDelivered`, and `settleAttachment`’s `RELEASE_ONLY` branch. Discard goes through `settleAttachment` (not a direct release). **Burn does not** release the memo/handoff maps.

2. **“Safe to clear [the handoff bit] here and ONLY here alongside the memo.”**  
   `settleAttachment` also does `handedOffMessages.remove` **before** `RELEASE_ONLY`/`ABANDON` handling — a second clearer of the bit.

**Trigger:** Someone skips burn/settle wiring “because releaseDeposit already runs on burn.”

**Consequence:** Memo/handoff set can outlive burn until process death; false exclusivity hides the ABANDON two-step clear.

---

### P3 — “Bit and memo only ever cleared together” is almost right; the pointer is incomplete

**Where:** `handedOffMessages` kdoc; `settleDecision` kdoc (“see `releaseDeposit`”).

**Enumeration of clears (commit):**

| Site | Memo | Bit |
|------|------|-----|
| `releaseDeposit` | `attachmentDeposits.remove` | `handedOffMessages.remove` |
| `settleAttachment` non-SKIP | later `releaseDeposit` or `attachmentDeposits.remove` | **always** `handedOffMessages.remove` first |
| `stop` / `messages.clearAll` / `conversations.clearAll` | neither | neither |

**ABANDON window:** bit remove and memo remove are **two statements**. Between them there is no suspend; work is specified to run on `confined`. For true ABANDON, `handedOff` was already false, so the bit remove is a no-op. For `RELEASE_ONLY`, there is a brief “bit gone, memo still present” gap; under single-worker confinement and a single settle call site, nothing else re-enters `settleDecision` for that id in the gap. **No user-visible race found**, but the kdoc’s “see `releaseDeposit`” under-describes the ABANDON path (which never calls `releaseDeposit` for the memo).

---

### P3 — Wiring tripwire still weak / brittle

**Where:** `AttachmentDepositWiringTest` (`the memo is released on every terminal outcome…`).

| Assertion | Pins? | Failure mode |
|-----------|--------|----------------|
| `releaseDeposit(messageId)` count == 3 | Rough total only | Still cannot tell “moved” from “lost” if one site is deleted and another added |
| `"settleAttachment(messageId)" in code` | **Existence**, not the contact-deleted site | False green: call moved off contact-delete, string kept elsewhere; false alarm: rename arg / break across lines |
| `RELEASE_ONLY) { releaseDeposit(messageId)` regex | Local shape of one branch | False alarm on harmless reformat/comments; can miss a semantic-preserving extract |

Better than bare count-3, still not a site pin of “contact-deleted mid-send must settle.”

---

### P3 — Decision table is strong; `expected()` is a near-clone, not an independent oracle

**Where:** `AttachmentSettleDecisionTest.expected`.

- Old vacuity/tautology defects are **fixed**: literal `14` / `6` pin enum growth; full (state × handedOff) equality; separate no-memo and anti-vacuity tests.
- `expected()` is essentially `settleDecision` with `hasMemo=true` fixed — same branch order and outcomes. Maintenance-independent (edit one, fail), **not** “cannot agree by construction.” Same author can write the same wrong rule twice.

**Mutations the new table still would not catch well:**

- Author-synchronized wrong rule in both `settleDecision` and `expected` (e.g. treat `READ` as retryable in both).
- Pure refactors that preserve the function’s input→output map.
- Call-site / ordering bugs outside the pure function (by design; coordinator unconstructible).
- `hasMemo=false` regressions if that separate test were deleted (table itself only exercises `hasMemo=true`).

Named mutants claimed in the commit message (`handedOff→SKIP`, `handedOff→ABANDON`, FAILED not skipped, no-memo→ABANDON, else→SKIP / feature dead) **are** pinned.

---

## Claim-by-claim verification

### 1. Fresh-token proof chain — **TRUE** (checked)

| Link | Evidence |
|------|----------|
| Fresh token when no memo | `AttachmentCrypto.encrypt`: `reuseToken ?: ByteArray(BLOB_TOKEN_BYTES).also(random::nextBytes)` |
| `blobId = sha256(token)` client | same function: `val blobId = sha256(token)` |
| Same on server | `AbandonBlob` / `RedeemBlob`: `blobID := sha256.Sum256(token)` |
| Memo only reuses when present | sole production call: `AttachmentCrypto.encrypt(bytes, memo?.token, memo?.key)` then store only if `memo == null` |
| After clear, next encrypt is fresh | cleared map ⇒ `memo == null` ⇒ no reuseToken ⇒ new random token ⇒ new blob id |

So: **memo present ∧ handoff bit absent ⇒ no envelope naming the *current* blob was enqueued** holds, provided token is never derived/reused across clearings (as the kdoc warns).

### 2. Bit and memo cleared together — **MOSTLY TRUE** (see P3)

All clear sites enumerated above. Pairing is intentional; ABANDON is two statements; exclusivity via only `releaseDeposit` is overstated.

### 3. Per-process bound — **TRUE**

`stop()` clears linking flags, cover teardown, notifications, `pendingPostAck` — **not** `attachmentDeposits` / `handedOffMessages`.  
`MessageRepository.clearAll` / `ConversationRepository.clearAll` do not touch coordinator maps. Bound = process lifetime × outstanding ids. Confirmed.

### 4. 429 / auth / capability — **MIXED**

| Subclaim | Result |
|----------|--------|
| Rate-limit **before** `BodyParser` in `AbandonBlob` | **TRUE** (`blobLimit.Allow` then parse) |
| 429 ⇒ no delete | **TRUE** |
| 401 / dropped response can leave token seen without delete | **TRUE** (auth middleware before handler; client swallows failures; no restore) |
| AbandonBlob authenticated | **TRUE** (`RequireAuth` on route) |
| DepositBlob already links account→blob | **FALSE** → **P2** |

### 5. Contact-deleted after handoff across retries — **TRUE**

Trace is real:

1. `publishOutgoing` success → `handedOffMessages.add`  
2. 90s `armSendTimeout` → `FAILED`  
3. Retry → `deliverAttachment` reuses memo → `publishOutgoing` again  
4. Contact gone → `discard` then `settleAttachment`  
5. `stateOf` is `null` after discard; `handedOff=true` ⇒ `RELEASE_ONLY`, not blind abandon  

Call-site comment correctly refuses “provably never handed off.”

### 6. `NonCancellable` still runs when `scope` is cancelled — **TRUE** (semantics)

`scope.launch(confined + NonCancellable)` puts `NonCancellable` in as the **Job** element, so the child is not parented to the cancelled scope job and is not immediately cancelled with the session. Residual (401 after bearer death with token in body) is real; its **justification** in this commit is partly false (P2).

### 9. Behaviour change in `f730cc48`? — **NONE (confirmed)**

Filtering the production patch to non-comment lines: **0** executable deltas under `apps/android/.../main/`. Only kdoc/comments + tests. Commit’s “no behaviour changes” claim holds.

---

## Merge readiness (`fix/0.10.3-blob-settle` as 0.10.3 bugfix)

| Item | Status |
|------|--------|
| Core settle rule + handoff bit + exclusive claim | Present from `afee7d5f` |
| Claim-correction commit behaviour-neutral | Yes |
| Claim-correction **truthfulness** | **Incomplete** — new false DepositBlob-linkage residual; stale abandon “two routes”; stale `releaseDeposit` contract |
| Wiring breadth | **One** settle site (contact-deleted); non-durable flush / upload-throw / burn / lock still do not reclaim — **documented as intentional** in `afee7d5f`, not a silent gap, but not “full orphan reclaim” |
| Tests | Decision table improved; coordinator still unconstructible; wiring pins fragile |
| Server kdoc | `blobs.go` AbandonBlob still “same breath” — uncorrected relative to client residual honesty |
| Records | Judging verdict present; `todos.md` still describes removed `abandonBlobQuietly` paths — drift |
| Dirty tree | Uncommitted WIP already rewrites the DepositBlob residual — suggests the P2 was found mid-flight; **not committed** |

**Block merge of the claim-correction story until** the DepositBlob-linkage false residual is fixed **in a commit** (WT draft is a start, not the branch tip).  
**Do not block** on deferred multi-route reclaim if 0.10.3’s stated scope remains “one proven site + decision extraction” — but then `ApiClient.abandonBlob` must not claim the disabled non-durable route.

---

## Explicit CLEAN items

- Fresh-token / `blobId=sha256(token)` / no old-token memo re-create after clear  
- Contact-deleted × prior-handoff reachability  
- `stop` / `clearAll` leave both maps (per-process bound as stated)  
- Relay rate-limit-before-parse for abandon; AbandonBlob auth  
- NonCancellable Job replacement behaviour  
- No production behaviour change in `f730cc48`  
- Decision-table vacuity/tautology defects from round 1 are addressed  

A CLEAN is absence of a finding, not a proof.
