I'll conduct a read-only adversarial review of the four capacity/leak fixes. Starting with the protocol docs and the listed files.Next I'll trace refresh-token rotation, envelope/blob TTL paths, and the client `flushSendRatchet` retry path that bounds item 2.# FINDINGS

### F1 — P3 — `server/internal/config/config.go:66–88,136–141` + `config_test.go:55–69`
**Invariant is asserted, not held.**

- **Sequence:** Operator (or a future “tidy”) sets `BLOB_TTL_HOURS=72` or `24`. `Load()` accepts any positive value; the only clamp is `<= 0 → 96`. Deposit at \(T_0\) sets `expires_at = T_0 + 24h`. Send at \(T_0+\varepsilon\). Recipient is offline until \(T_0+30h\), reconnects, receives the envelope (`created_at` still inside 72h), calls `RedeemBlob` → 404.
- **Outcome:** Deliverable control payload with a permanently dead attachment — the exact failure the invariant comment warns about — under a *reachable* env config that tests treat as valid.
- **Why tests miss it:** `TestLoadKeepsPositiveBlobValues` explicitly requires `BLOB_TTL_HOURS=24` to pass through unchanged. Nothing asserts `BlobTTLHours >= MessageTTLUndeliveredHours + …`. Clamp tests only cover non-positive values.

### F2 — P3 — `server/internal/config/config.go:85–87,118–119,139`
**Stale “1 week / 168h” claims after the default moved to 96.**

- **Sequence:** Reader uses the field comment (“Default 1 week (168h)”) or the clamp comment (“secure default (1 week)”) while the code default/clamp is 96.
- **Outcome:** Wrong operational mental model; someone “restoring” 168 or thinking the clamp restores a week is misled. Not runtime data loss by itself.
- **Why tests miss it:** Tests only check numeric clamp targets (`96`), not comment consistency.

### F3 — P3 — `server/internal/db/queries.sql:6–8,52–53` vs `store.go:178–188,255–268`
**Claimed SQL source of truth does not mirror the runtime queries.**

- **Sequence:** `queries.sql` still has `PendingEnvelopes` without a cutoff and has no `PurgeExpiredRefreshTokens`. Package header says store.go “mirrors these queries exactly.” Runtime correctly uses the new SQL in `store.go` only.
- **Outcome:** A later `sqlc generate` / sync-from-queries pass can reintroduce unfiltered pending delivery or drop the token purge. Today’s binary is fine; the dual-maintenance contract is broken.
- **Why tests miss it:** No test that `queries.sql` matches `store.go`; hub tests use a fake that ignores cutoff.

---

# CONFIRM-OR-REFUTE

### Item 1 — Reap expired `refresh_tokens` — **CONFIRMED (fix holds)**

| Attack | Result |
|--------|--------|
| Purge deletes a token a concurrent rotation is about to use | **No for still-valid tokens.** `ConsumeRefreshToken` requires `expires_at > now()` (DB); purge deletes `expires_at <= $goNow`. At exact expiry, consume already fails and purge is correct. Overlap only if Go clock ≫ DB clock by enough to put `expires_at` between them — same-host Docker skew is ms-level, not a practical kill of a live session. |
| Race the rotation query | Both are single-statement `DELETE`s; one row, one winner. Consume-first → purge no-ops that hash. Purge-first on an expired row → consume returns no row → `unauthorized` (correct). New token from `issueTokens` has `expires_at = now+7d` and is not purged. |
| Failing purge breaks other janitor passes | **No.** `janitor.go:37–42` logs and continues; drops/blobs/QR purges are independent in the same tick. |

Accumulation path is closed on the existing 10‑minute cadence (first fire after one period, same as other purges).

### Item 2 — `BLOB_TTL_HOURS` 168→96; invariant arithmetic — **CONFIRMED under coded paths; margin is not “consumed” by `flushSendRatchet`**

**Arithmetic as stated:**  
`96 − 72 − 10min ≈ 23.8h` left for `upload → send`.

**What actually sits in that gap (traced):**

1. **Blob anchor:** `DepositBlob` sets `expires_at = time.Now()+BlobTTLHours` at upload (`blobs.go:114–115`).
2. **Envelope anchor:** `created_at` default at `StoreEnvelope` / send time.
3. **Order:** `deliverAttachment` uploads first, then `flushSendRatchet`, then non-suspending `publishOutgoing` (`MessagingCoordinator.kt` ~1416–1467).
4. **`flushSendRatchet`:** `FLUSH_MAX_ATTEMPTS=3`, backoff `50ms * attempt` → **~150ms** of intentional delay, not hours (`MessagingCoordinator.kt:2505–2614`). Capacity/closed fail closed and **do not send**.
5. **Session lock:** held for encrypt / prekey **before** upload, not in the upload→publish gap.
6. **0.10.1 retry re-deposit:** each attempt drew a new token/blob → **new `expires_at`**. Orphans are a capacity problem; they do not stretch one blob’s TTL across retries.
7. **Device backgrounded / process death:** messages are RAM-only; process kill drops the bubble and leaves an orphan until TTL. A multi-day *freeze without kill* mid-send is the only way to burn tens of hours of margin; that is not a bound the app code schedules.

**Verdict on the invariant:** The **direction** is right (blob TTL must exceed envelope TTL because anchors differ + RedeemBlob is strict). The **~24h margin is an engineering buffer**, not a measured max delay implied by `flushSendRatchet` (which contributes milliseconds). Under every code-bounded path, 96h holds with a large surplus. It is **asserted rather than enforced** at config load (F1).  

**Branch note (item 5a, same branch, not in the items 1–4 file list):** memoized `blob_id` + `ON CONFLICT DO NOTHING` + `uploadBlob` treating 409 as hard failure means a successful first deposit + failed publish (socket-down path has no abandon) can brick retries without refreshing `expires_at`. That is an item‑5 interaction, not a refutation of the 96h default for the 0.10.1 single-attempt model.

### Item 3 — `PendingEnvelopes` TTL cutoff — **CONFIRMED (no deliverable-message drop under nominal TTL)**

| Attack | Result |
|--------|--------|
| Boundary vs janitor | Purge: `created_at < cutoff` (`store.go:214`). Deliver: `created_at >= cutoff` (`store.go:187–188`). Same cutoff formula: `now - MessageTTLUndeliveredHours` from `main.go` for both hub and janitor. Equality → deliver, not purge. **No both; “neither” only for already-expired rows waiting for the next sweep (intentional).** |
| Drop message still inside 72h | Not with colocated Go/PG clocks. Effective age is `Go_now - PG_created_at`; same process for deliver and purge. Large Go≫PG skew could shorten TTL; not realistic on the CX23 compose layout. |
| Offline slightly under TTL | Still selected (`created_at >= now-72h`). |
| Different `now()` transactions | Cutoff is a Go `time.Time` bind param, not a second SQL `now()` inside the SELECT; one value per call. Live `handleSend` path has no TTL filter because it only runs for **just-stored** envelopes. |

Closing the old “deliver until janitor” window (up to ~10 minutes past nominal expiry) is a **correctness fix**, not a regression against the stated 72h product TTL.

### Item 4 — `effective_cache_size` 2.5 GiB via compose `command:` — **CONFIRMED (entrypoint OK; override OK; value is planner-only)**

| Attack | Result |
|--------|--------|
| Breaks image entrypoint / `POSTGRES_*` / initdb | **No.** Official image: `ENTRYPOINT=docker-entrypoint.sh`, compose `command` replaces `CMD` only → `docker-entrypoint.sh postgres -c effective_cache_size=2560MB`. Entrypoint still runs env setup, initdb, `/docker-entrypoint-initdb.d`, then `exec "$@"` (verified on `postgres:16-alpine`). Continuity overlay does not clear `command`. |
| CLI overrides `postgresql.auto.conf` | **Yes.** PostgreSQL precedence: command-line options override config files including `ALTER SYSTEM` / `postgresql.auto.conf`. |
| 2.5 GiB defensible on 3.73 GiB host with sidecars | **As a planner estimate, yes-ish.** `effective_cache_size` does **not** allocate RAM (unlike `shared_buffers`). The compose comment’s “leaving room for server/sidecars” reads as if this setting reserved memory; it only tells the planner how much cache to *assume*. 2560MB ≈ 2/3 of host RAM is within common PG guidance if page cache is large; still slightly optimistic with Tor/I2P/server on the box, but far better than the false 4 GiB claim. Not a start-failure or corruption risk. |

---

# MISSING CONTEXT

- Live CX23 RSS for postgres/server/tor/i2p (to validate planner cache assumption vs real free page cache).
- Whether production will set `BLOB_TTL_HOURS` / `MESSAGE_TTL_UNDELIVERED_HOURS` via env (unenforced invariant).
- Client/item **5a/5b** interaction with deposit reuse, 409 handling, and which failure paths call `abandon` (affects long-gap reuse of one blob’s `expires_at`; out of scope for pure 1–4 file list but on this branch).
- No integration test that a row with `created_at` just inside/outside the cutoff is delivered vs suppressed vs purged.
- No test that janitor continues after a forced `PurgeExpiredRefreshTokens` error.
- `docs/RELEASING_RELAY.md` / `blobs.go` comments still mention 168h in places (docs lag).

**Checked (read-only):** items 1–4 commit `2f9e0de1` and current tree for listed files; refresh consume/insert path; envelope purge/deliver SQL; blob deposit/redeem anchors; `flushSendRatchet` bounds; postgres:16-alpine entrypoint; compose merge with continuity overlay; absence of purge/pending tests.

---

**VERDICT: FINDINGS (0 P1, 0 P2, 3 P3)**
