# U1 — ROUND 2 adjudication (IN PROGRESS — Codex in, Grok still running)

Codex: `VERDICT: FINDINGS (1 P1, 3 P2, 2 P3)`. Grok pending — **do not act on this file as final.**

## The headline: round 1's fixes introduced three new defects

This is the sixteen-round arc reproducing exactly, and it is the reason `failures.md` says a fix is
not lower-risk than original code. Of Codex's six findings, **three did not exist before round 1**:

- **R2-F1 (P1)** is round-1 fix F2 (the stale-block check) interacting with round-1 fix F8
  (`clearAccount` resetting the mark). Neither is wrong alone; together they are a TOCTOU.
- **R2-F4 (P2)** is round-1 fix F4 (the capacity revert), which introduced a new writer that
  restores a snapshot taken *before* the network round-trip.
- **R2-F2 (P2)** is the round-1 durability fix not carried all the way through the credential path.

## Codex findings (pending Grok; severities not yet final)

| # | Sev | Defect | New in R1? |
|---|---|---|---|
| R2-F1 | **P1** | **TOCTOU counter regression.** Allocator reads `counterHighWater == 4`, pauses before incrementing; another thread runs `clearAccount()` + reprovisions with mark `0`; allocator resumes and issues `1`, then observes the stale block, reserves `[0,4)` and issues `0`. The replacement account emits **`1, 0`** — a cleartext regression, the exact fingerprint the reservation exists to prevent. `clearAccount()` does not take the allocator lock, so the staleness check is not atomic with the spend. | **YES** — F2 × F8 |
| R2-F2 | P2 | **"A flush throw means it never happened" is not honoured on the credential path.** Register succeeds, `mutate` succeeds, `flushBeforeAck()` throws → returns `false`, but the complete credentials sit in live state with `capacityExceeded == false`. A second `provisionIfNeeded()` returns **true** via `isProvisioned()`. Cover traffic may then send on credentials a crash erases. | partially |
| R2-F3 | P2 | **`clearAccount()` retains `accessToken`/`refreshToken`.** Live and scheduled bearer credentials survive for the supposedly cleared account: the access token works until expiry and the refresh token mints a new session. | no |
| R2-F4 | P2 | **`revertAndDefer(previous)` restores a stale snapshot** taken before the network round-trip. Any writer that touched the decoy section during registration is clobbered wholesale — including a concurrent counter reservation, which would restore an **older high-water mark** (regression again, by a second route). | **YES** — F4 |
| R2-F5 | P3 | Strict-v1 accepts **noncanonical** decoy encodings: any nonzero presence byte is truthy, an absent long may carry arbitrary ignored bytes, and **negative `counterHighWater`** is accepted and can be issued as negative counters. Decode→encode is not byte-stable. | no |
| R2-F6 | P3 | Decode-failure wipe **still not pinned.** Splitting out `wipePartialDecode()` made it directly testable, but nothing observes the production call at `VaultState.kt:476`; deleting it leaves both tests passing. Round 1 improved this without closing it. | no |

## Closed by this round

**Round-1 F7 (prekey privates) is VERIFIED, not merely asserted.** Codex independently inspected the
resolved `libsignal-client-0.46.0` jar and confirms `ECPrivateKey` exposes no `close()`/`destroy()`.
The implementer's refusal to "fix" it by zeroing was correct, and the residency mitigation stands.
No further action.

## Note for the round-2 fix brief

R2-F1 and R2-F4 are both **regressions introduced while fixing a regression risk**. The pattern to
name when briefing the fix: round 1 added two independent guards (stale-block check, capacity
revert) that each reason about the decoy section *outside* the lock that protects it. The fix must
address the shared root — what serializes access to `TAG_DECOY` across the allocator, the
provisioner, and `DecoyAuthStore` — rather than patching each interleaving. Patching interleavings
one at a time is the shape that took three rounds and a revert in 0.9.2 PR-3.
