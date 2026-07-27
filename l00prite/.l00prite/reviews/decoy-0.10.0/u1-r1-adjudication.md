# U1 — ROUND 1 adjudication (Codex + Grok, paired-blind)

Codex: `VERDICT: FINDINGS (2 P1, 1 P2, 1 P3)` · Grok: `VERDICT: FINDINGS (0 P1, 2 P2, 5 P3)`

Every finding below was **re-verified against source by the architect** before being accepted.
Neither reviewer's severity was taken on trust.

## The round's headline: they contradicted each other on the most severe item, and source settles it

**Codex C1 says the counter reservation is not durable. Grok explicitly lists "durable advance
before spend" as a non-finding and marks invariant #2 as *Holds*.** Both cannot be right.

**Source: Codex is correct.**
- `VaultRuntime.mutate` (`VaultRuntime.kt:119-143`) — *"encode the whole state and **schedule** a
  reseal via `VaultSession.update`"*, and at the call site: *"**Non-blocking by session contract:
  it copies + schedules, no I/O here**."*
- `VaultSession.update` (`VaultSession.kt:273+`) — takes `stateLock`, swaps the payload, marks
  dirty, returns. No disk write.
- The synchronous durable path is `VaultRuntime.flushBeforeAck()` → `VaultSession.flushNow()`.

So `mutate` returning successfully means *scheduled*, not *durable*. **Grok's non-finding is a
false negative on a P1.** This is precisely the value of running the pair blind: a single reviewer
would have passed either this defect or Grok's capacity findings.

**This also falsifies my own spec.** §2.3 says the reservation is "persisted" by writing to
`VaultState` — it specified the correct invariant (skip, never regress) against the wrong mechanism.
The invariant table's R2/W3 inherited the same error. Both must be corrected, not just the code.

## CONFIRMED — must fix

| # | Src | Sev | Defect | Verified |
|---|---|---|---|---|
| F1 | Codex | **P1** | `DecoyCounterReservation.reserveLocked()` spends counters after `mutate`, which only schedules. Crash inside the ≤2 s coalescing window loses the high-water mark; reopening **reissues** counters. Breaks skip-never-regress, which is the whole point of the reservation. | `VaultRuntime.kt:119-143`, `VaultSession.kt:273+` |
| F2 | Codex | **P1** | The reservation lock is **per allocator instance**, not per runtime. Two allocators over one runtime interleave `0, 64, 1` — a counter **regression** on the wire, the exact fingerprint this mechanism exists to prevent. Nothing structural enforces the "one instance per session" kdoc. | `DecoyCounterReservation.kt:54,63`; no constructor guard, no main-source construction |
| F3 | Grok | **P2** | On `VaultCapacityException`, `mutate` **retains** the live mutation unscheduled and sets `capacityExceeded`. `isProvisioned()` reads live state only, so it returns **true** for credentials that were never scheduled and that `flushBeforeAck` would refuse. A second `provisionIfNeeded()` returns true. Readiness lie. | `VaultRuntime.kt:126-131`, `DecoyAccountProvisioner.kt:82` |
| F4 | Grok | **P2** | Same root, worse consequence: the unscheduled state is discarded at lock/process death, so **every subsequent unlock registers a NEW relay account** while the vault stays near capacity. No durable back-off is written on capacity (only HTTP 429 defers). One registration per unlock against a **single global bucket** — systematic and unbounded, not an accepted one-off orphan. | `DecoyAccountProvisioner.kt:94-106,148-191` |
| F5 | Codex | **P2** | `provisionNotBeforeMs` (the 429 back-off) is written the same non-durable way. Crash before flush loses it; next unlock immediately re-hits the shared bucket. Defeats the "back off **across sessions**" requirement. | `DecoyAccountProvisioner.kt:209` |
| F6 | Grok | P3 | The one-attempt-per-session latch is burned by a **pure local deferral check**. If the 429 window expires mid-session, zero attempts are made until the next session — the latch is documented as one *attempt*, not one *check*. | `DecoyAccountProvisioner.kt:94-96,148-149` |
| F7 | Grok | P3 | `DecoyIdentity.generate()` leaves **prekey private halves** on the heap — 1 signed prekey + 100 one-time prekeys, private bytes dropped to GC only. Same class of secret as `identityKeyPair`, which the unit wipes meticulously. Violates the unit's own discipline. | `DecoyIdentity.kt:76-91` |
| F8 | Grok | P3 | `clearAccount()` leaves `counterHighWater` intact, so a re-provisioned account's **first** counters start at 128 rather than 0. A real ratchet with a new recipient starts at 0; counters are cleartext to the relay. Weak classifier. Unwired today. | `DecoyAuthStore.kt:74-82` |
| F9 | both | P3 | **Non-discriminating tests** — pass whether or not the property holds. Decode-failure wipe (asserts only the throw; its own comment concedes the wipe is "read in review"); restart-skips-counters (rebuilds `DecoyState` in RAM instead of decoding the persisted payload — it *assumes* the durability it claims to test); "commits the whole set at once" (no fault injected between mutates); "worst-case" budget is realistic, not adversarial. **This is a named recurring class here — `failures.md` records six prior occurrences.** | `VaultDecoySectionTest`, `DecoyCounterReservationTest`, `DecoyAccountProvisionerTest` |
| F10 | Grok | P3 | Invariant-table defects: crash matrix omits the in-session capacity-retain state (which *is* F3/F4); `DecoyAuthStore.clearTokens`/`clearAccount` missing from WRITERS; no row requiring durable back-off on capacity failure. | `u1-invariant-table.md` |

## REJECTED / no action

- **Grok invariant #2 "Holds"** — false negative, see above. Recorded because a reviewer asserting a
  property *holds* is a claim like any other, and this one was wrong.
- **Grok F6 budget row** ("worst case" overclaims): the measurement uses real `serialize()` and the
  JWT shape is server-fixed. Rename the test; no code change. Folded into F9.

## Root cause, stated once

**Three of the five most severe findings (F1, F4, F5) are one defect: `mutate` was treated as
durable.** It is not. Anything whose correctness depends on surviving process death must call
`flushBeforeAck()` and treat its throw as "the value was never issued / the state was never
recorded". F3 is the same misconception from the read side — readiness was keyed on live state
without consulting `capacityExceeded`.

Fixing the three call sites without fixing the *concept* in the spec and the invariant table would
leave the next unit to rediscover it. Correct all three surfaces.
