# Adversarial review — Zitrone 0.10.2, items 1–4 (relay capacity/leak fixes), round 1

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already been
found. Repo root: `/root/zitrone`. Branch: `feat/0.10.2-capacity-fixes`.

## Context — why these four, and what the capacity analysis actually found

**The ACCOUNT dimension is not the risk; the BLOB dimension is.** One blob is up to **8,454,180 B** —
about **545 accounts' worth of disk** — and **~2,079 orphans exhaust all 16.37 GiB free on CX23.**
Each item below closes an accumulation path or removes waste. **Nothing here is deployed.**

Zitrone is a zero-knowledge, plausible-deniability messenger. **The relay is CONCEDED** in the threat
model. It does **no request logging by design**, so incidents are undiagnosable after the fact — do
not propose logging as a fix.

## The four changes

**Item 1 — reap expired `refresh_tokens`.** Nothing reclaimed them: deleted only on USE (gated
`expires_at > now()`) or at account teardown, so a token that expired unused was never collected.
**118 of 150 prod rows (79%) were expired-and-stuck, oldest 2026-07-02.** New
`Store.PurgeExpiredRefreshTokens` in the janitor's existing 10-minute pass.
**Attack:** can it delete a token that a concurrent rotation is about to use, or race the rotation
query? Is `expires_at <= now()` the right boundary — could a token be valid at check time and deleted
before use? Does a failing purge break the other janitor passes (they share a loop iteration)?

**Item 2 — `BLOB_TTL_HOURS` 168 → 96, deliberately NOT 72.** Equalising blob and envelope TTL would
introduce a bug: blob `expires_at` is anchored at **upload**, envelope TTL at **send** (`created_at`),
and upload strictly precedes send, so at equal TTLs the blob always dies first — with
`flushSendRatchet`'s suspending retry backoff sitting in that gap. Enforcement is also asymmetric:
`RedeemBlob` requires `expires_at > now()` while the janitor is periodic.
**The invariant is recorded in `config.go`: `BLOB_TTL_HOURS ≥ envelope TTL + janitor period + max
upload→send delay`.**
**Attack this arithmetically, do not accept it.** Envelope TTL is 72 h and the janitor period is
10 min, so the invariant leaves ~23.8 h for `upload → send`. **Is that bound actually true?** Trace
the real worst case: `flushSendRatchet`'s retry/backoff behaviour, a device backgrounded mid-send, a
0.10.1 retry re-deposit, session lock and unlock between upload and publish. If the true worst case
can exceed it, 96 h is wrong and the invariant is being asserted rather than held.

**Item 3 — `PendingEnvelopes` gained a TTL cutoff**, threaded from the same
`MessageTTLUndeliveredHours` the janitor purges by (`NewHub` now takes it). It previously delivered
envelopes past nominal expiry until the next sweep.
**Attack:** is the boundary consistent with the janitor's (`created_at < cutoff` purge vs
`created_at >= cutoff` delivery) — any window where a row is neither delivered nor purged, or both?
**Can this now DROP an envelope a recipient should have received** — a client offline slightly under
the TTL, clock skew between app and database, or `now()` evaluated in different transactions? Losing a
deliverable message is far worse than delivering a stale one.

**Item 4 — `effective_cache_size` 4 GiB → 2.5 GiB, moved INTO `docker-compose.yml`** as a
command-line setting. It was a live hand-edit claiming more cache than the 3.73 GiB host has.
**Attack:** does adding `command:` to the postgres service break the image's entrypoint contract
(init scripts, `POSTGRES_*` env handling, first-boot initdb)? Is 2.5 GiB defensible given the server
and overlay sidecars share the box? Does a command-line setting actually override
`postgresql.auto.conf` as claimed?

## Files

`server/internal/janitor/janitor.go`, `server/internal/db/store.go`
(`PurgeExpiredRefreshTokens`, `PendingEnvelopes`), `server/internal/config/config.go` (the invariant
comment + defaults + clamp), `server/internal/ws/hub.go` (`Hub.envelopeTTL`, `NewHub`),
`server/cmd/server/main.go`, `docker-compose.yml`, `server/internal/config/config_test.go`.

## Calibration

- **P1** — a deliverable message or a live blob is destroyed or made unfetchable; data loss; the relay
  fails to start or corrupts state.
- **P2** — an accumulation path stays open, or a fix does not hold in a reachable case.
- **P3** — a comment/invariant stated but not held, hygiene, a guard that does not guard its claim.

Do not report style. Every finding needs file and line, a **concrete reachable sequence**, the wrong
outcome, and why the tests miss it.

## Output

```
# FINDINGS
(ID, severity, file:line, sequence, outcome, why tests miss it)

# CONFIRM-OR-REFUTE
(the four items, and the item-2 invariant arithmetic specifically)

# MISSING CONTEXT

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```

A CLEAN is the absence of a finding, not a proof — say what you checked.
