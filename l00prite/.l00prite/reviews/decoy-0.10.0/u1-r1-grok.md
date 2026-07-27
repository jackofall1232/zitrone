I'll perform a full adversarial review of U1: required design docs first, then every in-scope source and test file against the listed invariants.Loading the design docs and the full U1 source set next.Reading all in-scope source and test files.Reading the tests and related vault runtime code next.Checking a few more crash/ordering edge cases against the capacity and latch logic.# Adversarial Security Review — U1 (decoy provisioning + `TAG_DECOY`)

**Branch:** `feat/0.10.0-decoy-u1-provisioning` @ `64ba97b3`  
**Scope:** full unit (sources + tests + WRITER/READER table), not a fix delta  
**Method:** source-traced against invariants; tests evaluated for “would this still pass if the pinned property broke?”

---

## Finding 1

**Severity:** P2  
**File:line:** `DecoyAccountProvisioner.kt:94–106`, `148–191`; `VaultRuntime.kt:119–129`

**Concrete failure**

Vault is within a few bytes of `MAX_PAYLOAD_CONTENT_BYTES` (same condition `VaultCapacityFixture` builds).

1. Session S1: `provisionIfNeeded()`  
   - `register` → 201, account **A** exists on the relay  
   - `createSession` succeeds  
   - `runtime.mutate` applies full credentials to live `VaultState`, then `encode` throws `VaultCapacityException`  
   - Runtime **retains** the mutation, sets `capacityExceeded = true`, does **not** call `session.update`  
   - `handedOff = true` → identity is **not** wiped (correct for live ownership)  
   - `provision()` returns `false`
2. Still in S1: call `provisionIfNeeded()` again  
   - `isProvisioned()` reads live state → `accountId != null && identityKeyPair != null` → **`true`**  
   - Returns **`true`** even though nothing was scheduled and `flushBeforeAck` would refuse durability
3. Lock / process death: unscheduled live state is discarded; disk still has no `TAG_DECOY` credentials  
4. Session S2…Sn (vault still near capacity): steps 1–3 repeat  

**Wrong outcomes**

- **Readiness lie:** after a failed durable commit, `isProvisioned()` / a second `provisionIfNeeded()` report success for non-durable credentials.  
- **Unbounded registration spend:** every new unlock that hits the same capacity wall successfully registers a **new** relay account, then fails to commit. No durable backoff is written on `VaultCapacityException` (only HTTP 429 calls `deferProvisioning()`). That is not a one-off orphan; it is **one registration per unlock** while the vault stays over the decoy-sized headroom, against the shared global `registerLimit` bucket.

Crash-after-register orphans are explicitly accepted. Sticky capacity is different: it is **systematic and unbounded** across sessions.

**Why tests miss it**

- `a commit that cannot be persisted still never splits the credential set` calls `provisionIfNeeded` **once**, asserts `false` + `capacityExceeded` + no dangling pair. It never calls a second time (would get `true`), and never opens a second session (would re-register).  
- No multi-session capacity test exists.

---

## Finding 2

**Severity:** P2  
**File:line:** `DecoyAccountProvisioner.kt:94–96`, `82`; contract kdoc at `87–89`

**Concrete failure** (same capacity sequence as Finding 1, step 2 only)

Kdoc: *“Returns true when the vault holds usable credentials after the call.”*

After capacity-failed commit:

| API | Result | Durable? |
|-----|--------|----------|
| First `provisionIfNeeded()` | `false` | no |
| `isProvisioned()` | `true` | no |
| Second `provisionIfNeeded()` | `true` | no |

Any U2/U3 caller that does:

```text
provisionIfNeeded()           // ignore or only log false
if (isProvisioned()) send…    // proceeds on RAM-only credentials
```

treats an unscheduled, non-flushable state as ready. Counter reservation on that state either throws capacity again or, if a later smaller mutate somehow schedules the whole live state, persists credentials that the first call reported as failed.

This is the same root as Finding 1 (capacity retain + readiness keyed only on live credential presence) but is a **separate contract defect**: readiness is not “credential pair present in live state,” it is “credential pair present **and** not solely as an unscheduled capacity overflow.” U1 never consults `capacityExceeded`.

**Why tests miss it**

Capacity test stops at first `false`. Nothing asserts `isProvisioned() == false` after a non-durable commit, or that a second `provisionIfNeeded` stays `false` until a successful `session.update`.

---

## Finding 3

**Severity:** P3  
**File:line:** `DecoyAccountProvisioner.kt:94–96`, `148–149`

**Concrete failure**

Durable deferral from a prior 429: `provisionNotBeforeMs = T`.

1. Unlock at `T - 1` (still inside window)  
2. `provisionIfNeeded()`: not provisioned → `attempted` CAS **true** → `isDeferred()` true → return `false` **without any relay call**  
3. Stay unlocked past `T`  
4. `provisionIfNeeded()` again → `attempted` already true → return `false`  

No registration is attempted until the **next** process/session instance, even though the durable window has expired mid-session.

Spec frames backoff as “across sessions,” so this is not a P2 protocol break, but the latch is documented as “one **attempt** per session.” Burning the latch on a pure local deferral check means zero attempts after the window opens in-session.

**Why tests miss it**

`a 429 defers provisioning ACROSS sessions…` uses a **new** provisioner when the window passes (`now = { notBefore }`). It never keeps one instance across the boundary.

---

## Finding 4

**Severity:** P3  
**File:line:** `DecoyIdentity.kt:76–91`

**Concrete failure**

On every `generate()`:

- 1× `Curve.generateKeyPair()` for the signed prekey — **private half never zeroed**  
- 100× `Curve.generateKeyPair()` for one-time prekeys — only public bytes encoded; **private halves dropped by GC only**

The unit is careful about `identityKeyPair` wipe on abandon/handoff/close (`provision` `handedOff`, `decodeDecoy` catch, `parsePlaintext` catch, `DecoyState.wipe`, `refreshTokens` finally). Prekey private material is the same class of secret and is left on the heap after every provision attempt (including failures before register).

Not durable, not a deniability oracle, but it violates the unit’s own wipe discipline for private key bytes.

**Why tests miss it**

No wipe assertions on prekey material; identity wipe tests only cover the serialized identity array.

---

## Finding 5

**Severity:** P3  
**File:line:** `DecoyAuthStore.kt:74–82`; `DecoyAccountProvisioner.kt:172–180`; `DecoyCounterReservation.kt:96–100`

**Concrete failure**

1. Provision account **A**, run reservation until `counterHighWater = 128`  
2. `clearAccount()` → zeros identity, nulls `accountId`, **leaves** `counterHighWater` (and tokens unless caller also `clearTokens`)  
3. New session: `isProvisioned() == false` → provision account **B** via `copy(...)` which **preserves** `counterHighWater = 128`  
4. First decoy counters issued for the new peer start at 128, not 0  

A real Double Ratchet with a **new** recipient starts `message_number` at 0. A fresh relay account id whose first observed counters jump from a previous sink’s high-water is a weak classification feature for the relay (counters are cleartext to the operator per spec §1). Not a device deniability break; bounded and only on the clearAccount→re-provision path (unwired today).

**Why tests miss it**

`clearAccount` test checks id/identity wipe only. No re-provision-after-clear test. Counter tests never rebind credentials.

---

## Finding 6 — tests that do not pin what they claim

**Severity:** P3  
**Files:** listed per case

| Claim | Gap | Would still pass if broken? |
|-------|-----|------------------------------|
| “commits the whole set **at once**” (`DecoyAccountProvisionerTest` ~110) | Only observes pre-register vault emptiness and final all-fields-present. No fault between two mutates. | **Yes** — two sequential mutates (id then key) that both succeed pass. |
| Capacity commit failure (`~218`) | No second `provisionIfNeeded`, no S2 re-register, no `isProvisioned()==false` after fail | **Yes** for Findings 1–2 |
| Decode-failure wipe (`VaultDecoySectionTest` ~230) | Explicitly only asserts throw; wipe “read in review” | **Yes** if catch stopped calling `decoy?.wipe()` |
| Restart skips counters (`DecoyCounterReservationTest` ~102) | Rebuilds `DecoyState(counterHighWater=persisted)` in RAM; does **not** re-open via `VaultStateCodec.decode(session payload)` | **Yes** if mutate never scheduled the mark but live read still saw it |
| “Worst-case” budget (`VaultDecoySectionTest` ~247) | Uses realistic JWT shape; comment says “68-byte” identity while wire form is ~65 — measurement uses real `serialize()`, so delta is fine; not a true adversarial max token length | Soft — server-fixed JWT keeps this OK; test name overclaims “worst case” |

---

## Finding 7 — invariant table defects

**Severity:** P3  
**File:** `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md`

| Issue | Why it matters |
|-------|----------------|
| Crash matrix row for capacity: “credentials lost on close” only | Omits **in-session** `isProvisioned == true` and unscheduled retain; that omission is exactly Findings 1–2 |
| Writers missing: `DecoyAuthStore.clearTokens` / `clearAccount` | Real mutators of `TAG_DECOY`; clearAccount changes readiness meaning |
| Spec §4 R4 still “present = ready”; table corrects it | Table is right; original spec row remains a landmine for later units if re-read without the table |
| No row that capacity failure must durable-backoff or not re-register | Leaves Finding 1 outside the written invariant set |

Positive: table correctly catches presence≠readiness for 429 (`provisionNotBeforeMs`), register-before-commit + staging store, wipe obligations, omit-when-empty for 0.9.x, and fixed sealed region. Those match the code.

---

## Invariants attacked — summary

| # | Claim | Verdict |
|---|--------|---------|
| 1 | Register-before-commit | **Holds** for the happy path and explicit failure points before mutate: staging store + single mutate + fail-closed `accountId` setter. Capacity/crash after register still orphan (accepted). **Gap:** non-durable post-register commit + re-register (Finding 1). |
| 2 | Counter skip, never regress | **Holds** under concurrent `next()`, restart-with-high-water, failed reserve (RAM cursor not advanced). Re-read of durable mark on each reserve is correct. |
| 3 | Key material zeroed | **Mostly holds** for section identity (encode buffers, decode failure, wipe, refresh copy). **Gap:** prekey privates (Finding 4). |
| 4 | Deniability | **Holds** for U1 surface: no device storage/logs/diagnostics; sealed region fixed `SLOT_PAYLOAD_BYTES` with pad-then-AEAD; section omit when empty; no slot labeling. Encrypted image size/shape independent of decoy content. |
| 5 | Strict-v1 codec | **Holds:** unknown tag throws; duplicate/trailing/truncated decoy rejected; empty holder omitted; round-trip fidelity for deferral-only and full section. |
| 6 | Capacity budget | **Holds** at measured ~640–643 B vs 1024 with large headroom under realistic JWT; overflow fail-closes via existing runtime. |
| 7 | Mutation / locking | **Holds** for U1: all durable writes via `mutate`; reservation lock outermost; no persist-sink reentrancy in these classes; credential snapshot copied under lock for refresh. |
| 8 | Presence ≠ readiness | **Holds** for 429 deferral and `isProvisioned`. **Broken** for capacity-retained live credentials (Finding 2). |
| 9 | Registration scarce | **Holds** lazy + one attempt/session + 429 durable jittered backoff + silent failure. **Broken** under sticky capacity (Finding 1). |

---

## What is solid (explicit non-findings)

- Register-before-commit ordering with RAM `StagingAuthStore` / `ApiClientDecoyRelay` is real and tested at the register observation point.  
- `DecoyAuthStore.accountId` setter correctly refuses splitting credentials.  
- Counter reservation: durable advance before spend; failed mutate does not advance RAM cursor; concurrent uniqueness holds.  
- Codec wipe paths for decoy identity on decode failure are present in source (`decodeDecoy` + `parsePlaintext`).  
- No decoy writes to SharedPreferences / diagnostics; no production log lines in the decoy package.  
- Fixed-size sealed payload path is unchanged and correct for deniability of section presence.

---

**VERDICT: FINDINGS (0 P1, 2 P2, 5 P3)**
