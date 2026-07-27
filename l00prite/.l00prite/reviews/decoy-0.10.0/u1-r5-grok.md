I'll conduct a full adversarial security review of U1 against the invariants and required docs, not just the round-4 delta. Starting with the protocol docs and the whole unit source.Reading the full unit sources and the required design contracts next.Continuing with the rest of the codec, the design contracts, and the test suite.Examining §4.1 wording and the key tests next, especially J1 discrimination and credential-pair enforcement.Checking a few more contract-vs-code mismatches that match this unit’s recurring failure class:I have enough source-verified evidence. Drafting the final adversarial review.

---

# ADVERSARIAL SECURITY REVIEW — U1 Round 5

**Scope:** whole unit at `feat/0.10.0-decoy-u1-provisioning` vs base `a4f118df`  
**Method:** read every production file and test file in scope against the nine invariants, the crash matrix, §4 / §4.1 / §6.2a, and `u1-invariant-table.md`. Pressed the three R4 changes and the two implementer-flagged judgments against the source, not the surrounding prose.

---

## Press of round-4 changes

### 1. `registrationSpent` placement

Current order in `DecoyAccountProvisioner.provision()`:

1. `reserveBackoff()` (durable intent)
2. local identity + challenge + PoW
3. `val bundle = bundleFactory(identity)` — **above the flag**
4. `registrationSpent = true`
5. `relay.register(bundle, powProof)` …

| Path | Flag | Deferral | Outcome |
|---|---|---|---|
| `bundleFactory` throws | false | retired (W1d) | no tag, no spend |
| cancel before step 4 | false | retired | same |
| `register` throws / 429 / lost response | true | kept | orphan OK |
| `createSession` throws | true | kept | orphan OK |
| capacity at commit, revert OK | true | kept (restored) | orphan OK |
| flush throws (non-capacity) | true | kept; `credentialsUnconfirmed` | live+scheduled, no send |
| success | true | cleared in same mutate | durable credentials |

No path leaves a vault reference without a key. No path charges a pre-`register` local fault as a spend. Setting the flag *before* the call (not after 201) is the correct conservative rule for “may have created the account.”

### 2. `requireDecoyCredentialsPaired`

Writers checked:

- **W1** commits id+key+tokens together  
- **W2** → `storeTokensForAccount` (account must still match under section lock)  
- **`storeTokens`** no-ops if no account id  
- **W2b** nulls tokens only  
- **W2c** nulls id+key+tokens together after `wipe()`  
- **W1b / W1d / W3** never half-set credentials  

No production writer can encode an unpaired state. On decode, rejection is fail-closed for corrupt/crafted images only — it does **not** turn a previously legitimate vault into a failed unlock. Encode-side `require` is an assertion on an unreachable live state; it does not sit on a recoverable “old code succeeded” path for honest writers.

### 3. Documentation sweep

§4.1 truth table, §6.2a retirement rule, W1d, codec kdoc four-row trigger, and provisioner comments match the code **except** residual inventory drift in the invariant table (findings below). Spec §4.1 user sentence: see judgment.

---

## Implementer-flagged judgments

### §4.1 first clause

Release-note sentence:

> once a vault has **set up cover traffic** — which happens the first time it sends any, and is complete as soon as its cover-traffic account is registered

**Decision: sentence as a whole is true enough; not a finding.**

- Operative completion criterion (“complete as soon as … registered”) matches code and the four-path table.
- “Unaffected” sentence is correct: registered-never-sent is **not** claimed unaffected; offline-before-`register` is.
- First appositive (“happens the first time it sends any”) is the **product trigger** under planned U3 lazy wiring, not the durability trigger. A user who only reads “sends” and ignores “registered” could still be mildly misled about a 429-at-register case — that residual looseness is already admitted and marked PENDING RE-RATIFICATION. Another rewrite without a behaviour change would continue the oscillation. I am **not** filing it.

### J1 test

`the LAST LOCAL step before register is still spent-nothing - the flag sits below it`

**Decision: the test pins what it names.**

- Mechanism is “any pre-`register` throw retires the deferral” — true, and not a defect.
- Discriminator is **where** the throw is injected: `bundleFactory`, i.e. the last local step. Without that seam the step is unfailable; with it, re-inlining the bundle as `register`’s argument (R3 shape) is the mutation that fails this test alone.
- The opposite boundary (flag too late / clear after spend) is pinned by `crash BETWEEN register and commit` + the 429 durability test asserting the deferral **stands**.

Not a non-discriminating test.

---

## Invariants (falsification attempt)

| # | Result |
|---|---|
| 1 Register-before-commit | Holds. Staging store + one mutate + flush; crash matrix matches code. |
| 2 Counter skip/never regress | Holds. Section lock; flush before RAM advance; stale-block abandon; `clearAccount` reset under same lock. |
| 3 Key material zeroing | Holds for `ByteArray` paths (wipe on abandon, decode failure via `PartialDecode`, `VaultState.wipe`, grow-wipe). Prekey private halves: documented libsignal finalization residual, same as real account. |
| 4 Deniability | Holds for U1. No device-level storage/log/diagnostics sinks. Fixed sealed region. Unwired. |
| 5 Strict-v1 codec | Holds. Unknown/duplicate/trailing/truncation/negative mark/noncanonical longs/half-sets rejected. Empty omitted. |
| 6 Capacity | Holds. Budget test with realistic max; overflow → `capacityExceeded` → fail-closed flush. |
| 7 Locking | Holds. `DecoySectionLock → stateLock → session → storage`. No persist-sink reentry. |
| 8 Presence ≠ readiness | Holds. `hasAccount` / `canSend` / `isProvisioned` key on credential pair. |
| 9 Registration scarcity | Holds. Lazy, one latch per runtime, write-ahead backoff, silent degrade, retire only when unspent. |

---

## Findings

### F1 — P3 — Invariant table W2 still names the pre-H4 write path

**File:** `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md:76` (and field table line 52)

**Concrete failure**

- **Code** (`DecoyAccountProvisioner.kt:289–293`): `refreshTokens` writes via `DecoyAuthStore.storeTokensForAccount(...)`.
- **Table W2:** still says `via DecoyAuthStore.storeTokens`.
- **Field table** for `accessToken` / `refreshToken`: writers listed as `W1, W2, W2b` — omits **W2c (clear)**, which R2 G6 made load-bearing (tokens nulled with the account).

H4’s fix narrative in the same document correctly describes `storeTokensForAccount`; the WRITER inventory was not updated with it. That is the same “prose lagged the code” class as J5 / round 4.

**Why tests don’t catch it:** no test reads the invariant table.

**Blast radius:** a future unit wiring refresh from the WRITER row alone can call `storeTokens`, which writes whatever account is current under the lock — not “tokens for the account we refreshed.” That re-opens a clearAccount / re-provision interleaving H4 closed. Inventory error, not a live U1 runtime bug (production path is already correct).

---

### F2 — P3 — Counter-invariant summary still teaches pre-R1 durability

**File:** `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md:231–233, 238–239`

**Concrete failure**

The summary still says:

> Session start: RAM `next = limit = counterHighWater`  
> … only on a successful **mutate** do the RAM `next`/`limit` advance  
> … RAM advance is conditional on the **mutate** succeeding

**Code** (`DecoyCounterReservation.kt:100–105, 141–157`):

- Cursor starts at `next = 0`, `limit = 0` (not at the durable mark); first `next()` re-reads and reserves.
- Advance happens only **after** `flushBeforeAck()` returns. Mutate success alone leaves the cursor untouched.

W3’s row (line 79) is correct. This summary section is not. It is exactly the F1 misconception (“mutate = durable”) in compressed form — still present four fix rounds later because the detailed row was fixed and this abstract block was not.

**Why tests don’t catch it:** tests assert the code; they do not assert the table.

**Blast radius:** doc-only today; high leverage for a future reader of “THE COUNTER INVARIANT” who skips the W3 row.

---

## What I explicitly am not filing

- **§4.1 user sentence** — residual first-clause looseness; operative clauses and truth table accurate; pending maintainer ratification. See judgment above.
- **J1 “trivial pass”** — test is discriminating at the named seam.
- **`require` on encode/decode** — no honest writer path broken; corrupt images fail closed correctly.
- **Latch burned when `reserveBackoff` fails** — necessary for single-flight; not the F6 “deferral check burns latch” bug. Mid-session capacity recovery stays decoys-off until next unlock; acceptable silent degrade.
- **Spec R5 645 B vs measured 640–643 B** — within documented DEFLATE run-to-run spread; not a contract falsehood.
- **Manufactured nits** — no padding.

---

## Test suite (spot-checks of discrimination)

| Claim | Discriminates? |
|---|---|
| J1 flag below bundle | Yes (bundleFactory + flag-placement mutation) |
| Encoder/decoder half-set independently | Yes (round 4 separate mutations) |
| Register-before-commit + durable commit | Yes (observe-at-register + generations) |
| Half credential never sealed | Yes (zero cooldown multi-gen) |
| Counter durable before issue / flush fail issues nothing | Yes |
| clearAccount vs allocator TOCTOU | Yes (section lock) |
| hasAccount vs canSend split | Yes (fresh runtime + capacity window) |
| Gate runtime scope (latch + unconfirmed) | Yes |
| refresh vs clearAccount | Yes (`storeTokensForAccount`) |
| Spent-nothing retires / spent keeps deferral | Yes (paired tests) |

No new non-discriminating production test found.

---

## Verdict

**`VERDICT: FINDINGS (0 P1, 0 P2, 2 P3)`**

**Substance:** production code for U1 looks solid under full-unit re-read. Round 4’s structural fixes (flag placement, codec pairing, Gate scope, section lock, write-ahead backoff with spend-aware retirement) hold. Both findings are **invariant-table inventory / summary drift** — the class that has now recurred across this unit — not live security defects in the Kotlin.

**Merge posture (reviewer opinion only):** code is mergeable on security grounds; clear the two table rows (and maintainer-ratify §4.1 if desired) before treating the unit’s contracts as authoritative for U2.
