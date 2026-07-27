# U1 — ROUND 2 adjudication (Codex + Grok, paired-blind) — **NOT CONVERGED**

Codex: `FINDINGS (1 P1, 3 P2, 2 P3)` · Grok: `FINDINGS (0 P1, 4 P2, 2 P3)`
Union after dedup: **1 P1, 7 P2, 6 P3.** Supersedes `u1-r2-adjudication-partial.md`.

## Both reviewers again caught things the other missed

Convergent (2): the flush-throw readiness lie, and the stale-snapshot revert.
**Codex-only (4)**, including the sole P1. **Grok-only (4)**, including the finding that refutes the
implementer's own stated reasoning. A single reviewer would have shipped either the P1 or the
re-registration defect. Third consecutive round where this holds.

## The architectural finding — this one is a SPEC defect, and it is the architect's

**Grok F2: R4 as I corrected it is a *send* predicate being used as a *register* predicate.**

Round 1 made `isProvisioned()` = `credentials present && !capacityExceeded`, and I ratified that in
the spec. But `provisionIfNeeded()` gates *registration* on the same predicate. So when an
**unrelated** write overflows capacity on a vault that already holds durable synthetic credentials,
`isProvisioned()` returns false, the latch is taken, and the provisioner **registers a second relay
account** — spending the scarce global bucket and potentially replacing a good durable account.

The implementer called this direction "conservative" and documented it as such. **It is not
conservative; it is harmful**, and the review found what the reasoning missed. Refusing to *send*
cover traffic during an overflow is correct. Refusing to *acknowledge an account that already
exists* is not — it re-enters the one path that spends a shared worldwide resource.

**Required fix shape: split the predicate.** `hasAccount()` (does a synthetic account exist at all —
must NOT consult `capacityExceeded`) gates registration. `canSend()` (durable credentials AND no
capacity overflow AND flush confirmed) gates cover traffic. One predicate cannot serve both, and my
spec §4 R4 must say so.

## CONFIRMED — must fix

| # | Src | Sev | Defect | New in R1? |
|---|---|---|---|---|
| G1 | Codex | **P1** | **TOCTOU counter regression.** Allocator reads mark `4`, pauses before incrementing; another thread runs `clearAccount()` + reprovisions with mark `0`; allocator resumes → issues `1`, then sees a stale block, reserves `[0,4)` → issues `0`. Emits **`1, 0`**. `clearAccount()` never takes the allocator lock, so the staleness check is not atomic with the spend. | **YES** — R1 F2 × F8 |
| G2 | both | P2 | **Flush-throw readiness lie.** Register + `mutate` succeed, `flushBeforeAck()` throws → returns `false`, but credentials sit live with `capacityExceeded` clear, so the **next** `isProvisioned()`/`provisionIfNeeded()` returns **true** on never-flushed bytes. Round 1 closed the capacity-retain lie and left the flush-failed one. | partially |
| G3 | Grok | P2 | **Capacity flag misused as a register predicate** — see the architectural finding above. Costs a wasted global registration, or replaces a durable account if capacity clears mid-flight. | **YES** — R1 F3 |
| G4 | Grok | P2 | **Bare-revert branch writes no back-off.** When even `previous + deferral` will not encode, the code bare-restores `previous` and clears `capacityExceeded` — with **no `provisionNotBeforeMs` on disk**. At absolute cap that is **one registration per unlock**, exactly the defect R1 F4 was supposed to close, surviving on the edge case. | **YES** — R1 F4 incomplete |
| G5 | both | P2 | **`revertAndDefer(previous)` restores a snapshot taken before the network round-trip** (seconds of PoW + HTTP). Any concurrent decoy write in that window is clobbered wholesale — including a counter reservation, restoring an **older high-water mark**. Grok gives the full repro: reissues `0` after it was handed out. Second independent route to a cleartext counter regression. | **YES** — R1 F4 |
| G6 | Codex | P2 | **`clearAccount()` retains `accessToken`/`refreshToken`.** Live, scheduled bearer credentials survive for a supposedly cleared account: the access token works until expiry, the refresh token mints a new session. | no |
| G7 | Codex | P3 | Strict-v1 accepts **noncanonical** decoy encodings — any nonzero presence byte is truthy, absent longs may carry arbitrary ignored bytes, and **negative `counterHighWater`** is accepted and issuable as negative counters. Decode→encode is not byte-stable. | no |
| G8 | Codex | P3 | **Decode-failure wipe still unpinned.** `wipePartialDecode()` is now directly testable, but nothing observes the production call at `VaultState.kt:476`; deleting it leaves both tests green. | no |
| G9 | Grok | P3 | **The test that claims to pin the `capacityExceeded` half of readiness does not.** On `stateFilledToCap` the bare revert leaves no credential pair, so readiness is false with or without the flag — the claimed mutation check does not fail. G3 is therefore untested. **Same non-discriminating class as R1 F9; third occurrence in this unit.** | no |
| G10 | Grok | P3 | Two concurrent `provisionIfNeeded()` on one instance: the CAS loser returns `false` even after the winner succeeds. Silent decoys-off. API footgun. | no |
| G11 | Grok | P3 | **Spec drift (architect's):** §4 W1 still says first provision writes "counter reservation = 64". The code leaves `counterHighWater` at 0 until the first `next()`. The invariant table is right; the spec is wrong. | no |

## Closed

- **Prekey privates (R1 F7): VERIFIED by both** against `libsignal-client-0.46.0` bytecode —
  `ECPrivateKey` exposes no `close()`/`destroy()`. Refusing to "fix" it was correct. No action.
- **Lock ordering of the new flush-under-allocator-lock: sound.** Grok: holding the reservation lock
  across the flush is *latency, not inversion*. Accepted; no action.
- **`WeakHashMap` + `allocatorsLock` construction race: closed.** Both agree.
- **Latch placement: correct**, and pinned by a test that genuinely discriminates.

## The pattern to name in the round-3 brief

Round 1 added three guards — the stale-block check, the capacity revert, and the capacity-aware
readiness flag. **All three are now sources of round-2 defects**, and all three share one shape:
each reasons about `TAG_DECOY` state sampled *outside* the lock that protects it, or conflates two
different questions into one predicate.

Round 3 must not patch G1/G5 as separate interleavings. It must fix **what serializes access to the
decoy section** across the allocator, the provisioner, and `DecoyAuthStore`, and **split the
readiness predicate** per the architectural finding. Patching interleavings one at a time is the
shape that took three rounds and ended in a revert in 0.9.2 PR-3 — `failures.md` records it as
"when a fix keeps spawning edge cases, the APPROACH is wrong."
