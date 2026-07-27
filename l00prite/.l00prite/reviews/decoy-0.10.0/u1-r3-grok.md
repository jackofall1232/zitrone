I'll run a full-unit adversarial review of U1 Round 3 — whole unit, not the delta — starting with the protocol docs, design invariants, and every in-scope source file.Loading the full U1 sources, design docs, and invariant table in parallel.Reviewing the design docs, invariant table, VaultRuntime locking, and the test suite next.Checking one more lock-reentrancy path and whether provisioner uniqueness is mentioned as required:# Adversarial Security Review — U1 Round 3 (blind)

Whole unit reviewed against source on `feat/0.10.0-decoy-u1-provisioning` @ `15ca33a0`, base `a4f118df`. Spec §4.1, invariant table, and all in-scope production + test files were read in full.

---

## Finding 1 — P2

**File:line:**  
`DecoyAccountProvisioner.kt:259–263, 363–370` (write-ahead back-off) ·  
`VaultState.kt:263–271, 398–402` (blast-radius / omit claims) ·  
`docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:386–424` (§4.1 disclosure)

**Concrete failure:**

1. Vault has never held cover-traffic credentials and has never contacted the relay.
2. Caller invokes `provisionIfNeeded()` (U3 will; tests already do).
3. `reserveBackoff()` runs **before any relay I/O**: `mutate` + `flushBeforeAck` of `provisionNotBeforeMs` only.
4. Relay is offline / challenge throws → `provision()` returns `false`.
5. On disk: a non-empty `TAG_DECOY` section (deferral only). `isEmpty` is false because `provisionNotBeforeMs != null`.
6. A 0.9.x build opens the vault → strict-v1 unknown tag → unlock refused as corruption.

§4.1 currently says:

> once a vault has **generated cover traffic**, it can no longer be opened by 0.9.x … A vault that has **never generated cover traffic** is unaffected and still opens on 0.9.x.

That is false for this tree. The break attaches to **“called `provisionIfNeeded` once”**, including pure local/offline failure with zero cover traffic and zero registration. Codec kdoc still claims a vault that “never generates cover traffic never carries the tag” / “never pays for the break” — same false claim.

The implementer flagged this; the maintainer’s narrowed §4.1 was **not** updated to match R2. An overstated or understated disclosure is still a honesty defect under the project’s deliver-then-claim rule.

**Why tests do not catch it:** No test installs/simulates a 0.9.x decoder against a write-ahead-only image, and none asserts the §4.1 trigger condition. Tests such as `a failure BEFORE register leaves no credentials — only the write-ahead back-off` **confirm** the tag is written early; they do not treat that as a disclosure mismatch.

---

## Finding 2 — P2

**File:line:**  
`DecoyAccountProvisioner.kt:124–153, 132–133, 152–153, 197–207`  
(contrast `DecoyCounterReservation.kt:160–204` private ctor + `forRuntime`)

**Concrete failure A — double registration (two instances, one runtime):**

```
R = VaultRuntime(...)
P1 = DecoyAccountProvisioner(R, relay, ...)
P2 = DecoyAccountProvisioner(R, relay, ...)   // public ctor; no registry

// concurrent provisionIfNeeded on P1 and P2:
T1/T2: hasAccount() == false
T1/T2: isDeferred() == false          // neither has written back-off yet
T1: P1.attempted CAS true
T2: P2.attempted CAS true             // different AtomicBoolean
T1: reserveBackoff(); register → account A
T2: reserveBackoff(); register → account B
T1/T2: both commit under section lock → last writer wins; one orphan + two global-bucket spends
```

“One RELAY attempt per session” is enforced **per provisioner instance**, not per vault/runtime. Round 1 already ruled that kdoc-only uniqueness is not a defense (`DecoyCounterReservation` was fixed structurally for that reason). The provisioner was not.

**Concrete failure B — `credentialsUnconfirmed` is instance-scoped (G2 incomplete):**

```
P1.provisionIfNeeded():
  reserveBackoff OK
  register + session OK
  mutate credentials OK  → live has account
  flushBeforeAck throws  → credentialsUnconfirmed = true on P1
  returns false

P2 = DecoyAccountProvisioner(R, ...)   // fresh instance, flag defaults false
P2.hasAccount() == true
P2.canSend()    == true               // !credentialsUnconfirmed && !capacityExceeded
```

G2’s “flush throw means not ready” holds only for the instance that saw the throw. A second instance (or any holder of an older/other instance) answers ready on live-but-unconfirmed credentials and may send; a crash before a later durable flush leaves an orphan and forces a second registration next unlock.

**Why tests do not catch it:**  
`a credential commit whose flush THROWS…` and `the loser of the one-attempt latch…` use **one** provisioner. `an unrelated capacity overflow…` builds a fresh instance but against an already-durable account (`hasAccount` true → no latch path). Nothing constructs two provisioners over one runtime and races them, or checks `canSend()` on a second instance after a flush throw.

---

## Finding 3 — P3

**File:line:** `VaultState.kt:162–168` (`DecoyState.provisionNotBeforeMs` kdoc)

**Concrete failure:** Field kdoc still says the deferral is “Set only when the relay answers a registration with 429”. After R2 it is written **before any relay contact**, on every attempt that passes the deferral check, and retired only by successful commit. Readers/reviewers using the field comment will reason about the wrong writer set (e.g. assume offline failures leave no section).

**Why tests do not catch it:** No doc/contract test; production behavior is covered elsewhere under different names.

---

## Finding 4 — P3

**File:line:** `DecoyAccountProvisionerTest.kt:386–391`

**Concrete failure:** Comment claims “The next unlock over the **SAME image** spends nothing either”. Code builds:

```kotlin
val reopened = Vault(VaultCapacityFixture(ops).stateFilledToCap())
```

— a **new** near-full fixture, not `Vault(vault.durableState() ?: …)` from the first run. Absolute capacity independently blocks both runs, so the test stays green even if the first attempt left a durable image that a true reopen would handle differently. Same class of multi-guard pass the suite already recorded for G3.

**Why tests do not catch it:** The test is the defect; it asserts the right outcome for the wrong construction.

---

## Judged R2 behaviour changes (prompt items 3–4)

| Change | Judgment |
|---|---|
| **Write-ahead back-off; every failure defers 60–90 min** | Conforms to amended §6.2a / invariant table R2. Not a code/spec divergence. Cost is real (transient offline strands cover traffic for an hour), but it is the ratified tradeoff for “cannot record intent ⇒ do not spend the global bucket”. **Not filed as a separate defect.** |
| **`TAG_DECOY` before relay contact** | Same mechanism as above; **disclosure did not move with the code** → **Finding 1**. |

---

## Claims attacked and not falsified (this pass)

| Claim | Result |
|---|---|
| Register-before-commit; no durable half credential set | Holds for single provisioner; staging + one mutate + flush. Crash matrix in invariant table matches code. |
| Counter skip-not-regress under U1 writers + section lock | Holds: allocator / `DecoyAuthStore` / provisioner commit share `DecoySectionLock`; stale block abandoned under that lock. |
| Key zeroing on decode failure | `PartialDecode` + `decodeDecoy` catch + test through real `parsePlaintext` path pin wipe. Success path must not wipe (ownership to `VaultState`) — correct. |
| Deniability of sealed image size | Region remains fixed `SLOT_PAYLOAD_BYTES`; section rides inside compressed plaintext. No device-level decoy storage in U1 classes. |
| Strict-v1 codec / R2 canonical longs | Honest pre-R2 encoder already wrote presence 0/1 and zeroed absent longs; negative high-water unreachable from encoder. Strictness does not reject honest earlier 0.10.0 encodings. |
| Capacity budget ~640 B / 1024 | Test measures relay-realistic max and asserts delta > 0 and ≤ budget; not an adversarial max, and it says so. |
| Lock order decoy → stateLock → session → storage | U1 writers take section lock then `mutate`/`flushBeforeAck`. `flushBeforeAck` releases `stateLock` before disk I/O. No U1 path takes `stateLock` then section lock. |
| Presence ≠ readiness | `hasAccount` / `isProvisioned` key on credential pair; back-off-only section is not provisioned. |
| Registration scarcity (single instance) | Latch + durable write-ahead back-off; no tight retry loop on one instance. Broken only under Finding 2 multi-instance. |
| `clearAccount` nulls tokens with id/key | Verified; test pins it. Coalesced (not flushed) remains a documented U1 residual for U2+. |
| CAS loser returns `canSend()` | Correct for one instance; does not fix multi-instance. |

**Section lock residual (not raised to a finding):** the lock is cooperative and reentrant. A same-thread `flushNow` → persist sink → `DecoyAuthStore`/`next()` re-entry would silently break the sequences the lock exists for. Production image persist should not do that (existing “no runtime from persist sink” rule). Not a current U1 path.

**Invariant table:** R2 corrections match the code for W1/W1b/section lock/predicate split. Residual doc debt is §4.1 / codec blast-radius language (Finding 1) and the field kdoc (Finding 3). Counter “session start: `next = limit = highWater`” text is slightly wrong (RAM starts at 0/0; first `next()` re-reads and reserves) but behavior is correct.

**Tests (discrimination):** Round-2 mutations listed in the invariant table look sound for what they name. Known soft spots: `interleaved use never regresses` (acknowledged non-discriminating between defences), Finding 4’s “same image” construction, and **no** coverage for multi-instance provisioner or §4.1 trigger honesty.

---

## VERDICT: FINDINGS (0 P1, 2 P2, 2 P3)
