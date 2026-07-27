I'll conduct a full adversarial security review of U1 (whole unit, not just the round-1 fix delta). Starting with the protocol docs and the unit source.Reading the full unit source and vault mutation/flush paths next.Digging into the highest-risk areas: re-provision under `capacityExceeded`, revert races, libsignal wipe API, and test discrimination.Checking the absolute-capacity bare-revert path and whether `provisionIfNeeded` can re-enter after a failed flush:Checking one more durability edge on the success path comment versus `isProvisioned` short-circuit:# U1 Adversarial Security Review — Round 2 (blind)

Scope: whole unit on `feat/0.10.0-decoy-u1-provisioning` vs `a4f118df`, plus the invariant table and the durability claim. Round-1 fixes treated as unreviewed pressure-code.

**Libsignal prekey claim (round-1 F7): verified, not a finding.**  
`javap` on `libsignal-client` 0.46.0 `ECPrivateKey`: public API is constructor/`serialize`/`calculateSignature`/`calculateAgreement`/`unsafeNativeHandleWithoutGuard`/`publicKey`; `finalize()` calls `Native.ECPrivateKey_Destroy`. No `close()`/`destroy()`. Manual `Destroy` via the unsafe handle would double-free on finalization. Claim holds.

---

## Finding 1 — **P2**

**File:line:** `DecoyAccountProvisioner.kt:123-145`, `:233-240`

**Concrete failure**

1. Vault has no decoy state. `provisionIfNeeded()` runs.
2. `register` + `createSession` succeed (global registration spent).
3. `mutate` succeeds → credentials are **scheduled** (`dirty=true`), `capacityExceeded` clear, `handedOff=true`.
4. `flushBeforeAck()` throws (persist `IOException`, or close-during-flush).
5. Catch returns `false` (correct for this call). Credentials remain live + scheduled; identity not wiped (by design at `:236-238`).
6. **`isProvisioned()` is now `true`** (`accountId`+key present ∧ `!capacityExceeded`).
7. Second `provisionIfNeeded()` hits `if (isProvisioned()) return true` and returns **`true` without ever completing a durable flush**.

Contract at `:129`: *“Returns true when the vault holds **durable** usable credentials.”* After step 7 that is false. Crash before the coalescing ceiling / `close()` final reseal loses the scheduled payload → next unlock re-registers; a caller that gates sends on `isProvisioned()` or on a second `provisionIfNeeded()` can treat non-durable credentials as ready.

Round 1 closed the *capacity-retain* readiness lie; it did not close the *flush-failed / scheduled-only* readiness lie. Same root concept: live presence ≠ durable.

**Why tests miss it**

No provisioner test injects a failing persist sink after a successful credential `mutate`. Counter tests do (`a reservation whose durable write FAILS issues nothing`); provisioner durability tests only remove `flushBeforeAck` or assert the happy-path sealed image.

---

## Finding 2 — **P2**

**File:line:** `DecoyAccountProvisioner.kt:123-145`, `:195-257`  
**Invariant table R4 / author note at `:115-119`**

**Concrete failure**

1. Vault already has durable synthetic credentials (prior successful provision).
2. Unrelated `runtime.mutate` (e.g. large signal record) overflows → `capacityExceeded = true`; live decoy credentials still present.
3. U3 calls `provisionIfNeeded()` before send.
4. `isProvisioned()` → `true && false` → **`false`** (author calls this “conservative”).
5. Not deferred → latch taken → **`provision()` runs a full relay registration** for a *second* account.
6. Outcomes:
   - Still over cap: commit throws → `revertAndDefer(previous)` restores old credentials (+ backoff if it fits) → **one wasted global registration**, orphan on relay.
   - Cap clears between steps: commit may succeed → **old durable account replaced**, extra orphan.

The kdoc at `:115-119` only justifies *refusing cover traffic*. It does not justify *re-entering the register path*. `provisionIfNeeded` conflates “ready to send” with “needs registration.”

**Why tests miss it**

`an already-provisioned vault does no network at all` never sets `capacityExceeded`.  
`a failed capacity commit does NOT report the vault as provisioned` starts from an *unprovisioned* full vault (no durable credentials).

---

## Finding 3 — **P2**

**File:line:** `DecoyAccountProvisioner.kt:274-297` (bare-revert branch `:285`)

**Concrete failure (F4 incomplete at absolute capacity)**

`revertAndDefer` tries, in one mutate, `previous + provisionNotBeforeMs`. On failure it bare-restores `previous` **with no back-off**.

Sequence with a vault at the fixture boundary used elsewhere (`VaultCapacityFixture.stateFilledToCap()`, ≤8 B free):

1. Unlock → empty decoy, state already at cap.
2. `provisionIfNeeded` → register succeeds → credential `mutate` overflows.
3. Revert-with-deferral overflows (deferral-only section is tens of bytes).
4. Bare revert `state.decoy = previous` (null) **succeeds** → `capacityExceeded` cleared, **no `provisionNotBeforeMs` on disk**.
5. Session ends. Next unlock: not deferred, not provisioned → **register again**.
6. Repeat every unlock.

Documented residual (invariant table / R1 notes) is “one registration per 60–90 min” via durable back-off. On this path the residual is **one registration per unlock**, i.e. round-1 F4 for the absolute-cap edge. The durable-backoff test uses `stateWithSlack(200, 400)` so the deferral always fits and never exercises the bare path.

**Why tests miss it**

`a capacity failure backs off DURABLY` only uses the 200–400 B slack band.  
`stateFilledToCap` scenarios assert no half-set / not provisioned / latch, never “next session spent zero registrations.”

---

## Finding 4 — **P2**

**File:line:** `DecoyAccountProvisioner.kt:199`, `:274-279`

**Concrete failure**

`previous` is a **point-in-time reference** to the decoy holder, taken before challenge/PoW/register (seconds of wall time). Capacity recovery restores that snapshot and drops every concurrent decoy write in between.

Sequence using only public U1 APIs:

1. `previous = runtime.read { it.decoy }` → `null` (or `counterHighWater = 0`).
2. PoW / network in progress (no runtime lock held).
3. Concurrent `DecoyCounterReservation.forRuntime(runtime).next()` → durable mark advanced to 64, value `0` issued (allocator does not require credentials).
4. Provision credential `mutate` overflows (`VaultCapacityException`).
5. `revertAndDefer(previous)` writes `DecoyState(provisionNotBeforeMs = …)` or bare `previous` → **`counterHighWater` back to 0**.
6. Later `next()` re-reserves from 0 → **reissues 0** after it was already handed out → cleartext `message_number` regression to a relay observer.

Success-path commit uses *current* `state.decoy` and would preserve the mark; only the capacity-revert path uses the stale snapshot. Same clobber applies to any concurrent token/backoff write in that window.

**Why tests miss it**

No concurrent provision × reservation test. Capacity tests are single-threaded.

---

## Finding 5 — **P3**

**File:line:** `DecoyAccountProvisionerTest.kt:356-372` vs `DecoyAccountProvisioner.kt:123-124`

**Concrete failure (test does not pin the property it names)**

After W1c, a capacity failure on first provision with `stateFilledToCap` takes the bare-revert path to `decoy = null` (Finding 3). Live state has **no** credential pair; `isProvisioned()` is false whether or not `&& !capacityExceeded` is present.

Claimed mutation check (“drop `capacityExceeded` from readiness → fail”) does **not** fail on this fixture after a successful bare revert. The capacityExceeded half of R4 is unpinned; Finding 2 is therefore untested.

**Why this matters**

Same recurring non-discriminating-test class as round-1 F9.

---

## Finding 6 — **P3**

**File:line:** `DecoyAccountProvisioner.kt:139-145`

**Concrete failure**

Two concurrent `provisionIfNeeded()` on one instance:

1. Both see not provisioned / not deferred.
2. A wins `compareAndSet`, runs multi-second provision to success.
3. B loses CAS → **`return false`** even though the vault is now provisioned.

Silent decoys-off for B for the rest of that call chain. Fix shape would be `if (!CAS) return isProvisioned()` (still racy without waiting, but not a false negative after A completes). Unlikely if U3 always serializes; still a real API footgun.

**Why tests miss it**

No concurrent provisioner test.

---

## Invariant table attack

| Row | Issue |
|-----|--------|
| **R4** | Equates “ready” with `credentials ∧ !capacityExceeded`. That is a *send* predicate, not a *register* predicate. As written it **causes Finding 2** when used by `provisionIfNeeded`. Missing: scheduled-but-unflushed (Finding 1). |
| **W1c** | States durable back-off on capacity. Omits bare-revert subpath with **no** back-off (Finding 3). Crash matrix “W1c reverts + defers” is incomplete. |
| **W1 / crash matrix** | “after mutate, before flushBeforeAck → false; credentials scheduled” is right for the first return, but does not say a later `isProvisioned`/`provisionIfNeeded` can flip to true without a successful flush (Finding 1). |
| Spec §4 W1 | Still says first provision writes `counter reservation = 64`. Code leaves `counterHighWater` at 0 until first `next()`. Table U1 correctly does not claim W1 writes 64. Spec drift, not a code defect. |

---

## Invariants (attack summary)

| # | Claim | Result |
|---|--------|--------|
| 1 | Register-before-commit | **Holds** for the commit itself (one mutate, staging store, no half-set on disk). Capacity path does not leave durable dangling refs. |
| 2 | Counter skip, never regress | **Holds** for the single-allocator happy path + flush-before-spend. **Broken** under Finding 4 (revert clobber). clearAccount→next reissues 0 by design for a new peer; kdoc “cannot produce a reissue” is overstated relative to the test. |
| 3 | Key material zeroed | **Holds** for vault-owned `ByteArray` identity (wipe / decode-fail / clearAccount). Prekey privates: claim verified. |
| 4 | Deniability | **Holds** for U1 surfaces (no device storage/log sinks; fixed sealed region; empty section omitted). |
| 5 | Strict-v1 codec | **Holds** for unknown/duplicate/trailing/truncation. Decoder does not require accountId↔key pairing (unreachable via honest encoder; AEAD-bound). |
| 6 | Capacity budget | **Holds** as “largest relay-shaped section”; test no longer claims adversarial worst case. |
| 7 | Mutation / locking | Reservation outermost + flush releases `stateLock` before disk: **OK**. Holding reservation lock across flush is latency, not inversion. |
| 8 | Presence ≠ readiness | **Holds** for deferral-only sections. **Fails** for flush-failed scheduled credentials (Finding 1) and misuses capacity flag for registration (Finding 2). |
| 9 | Registration scarce | **Mostly holds** (lazy, latch, 429 durable backoff). **Fails** Finding 2 (extra reg), Finding 3 (per-unlock at absolute cap). |

---

## Durability claim

Writers that pair `mutate` + `flushBeforeAck` and treat flush throw as failure: credential success path (returns false — good for *that* call), W3 reservation (no issue on throw — good), 429/capacity back-off via `flushBestEffort` (lost backoff → extra attempt — documented for 429).

Gaps: Findings **1** and **3** (second-call true without durable flush; bare revert with no backoff). Tokens correctly non-flushed.

---

## Non-findings (checked)

- Round-1 counter flush-before-spend and single-allocator factory: sound under single-writer use; tests read sealed payloads.
- `WeakHashMap` + `allocatorsLock`: construction race closed.
- Latch placement before relay (not before deferral check): correct; mid-session expiry test pins it.
- Section omit-when-empty / strict unknown tag: correct for 0.9.x compatibility.
- Libsignal prekey wipe impossibility: verified against 0.46.0 bytecode.

---

**VERDICT: FINDINGS (0 P1, 4 P2, 2 P3)**
