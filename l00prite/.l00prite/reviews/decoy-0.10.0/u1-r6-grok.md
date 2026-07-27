I'll conduct a blind adversarial review of U1 as the final confirm round: first the architect's storage-format and invariant-table doc corrections against the code, then a whole-unit pass on the listed sources and tests.Next I'll read the corrected §4.1 storage-format text and the invariant table, then verify both against the source.Reading the full source files against the claimed invariants.Verifying §4.1 against code paths and scanning for residual `mutate` = durable misconceptions.Checking `VaultRuntime` durability semantics and a few edge cases that could still regress counters.Checking one residual in the codec kdoc against the code paths, then finalizing.# U1 Adversarial Review — Round 6 (Final Confirm)

**Scope:** whole unit vs `a4f118df`, plus the round-5 doc corrections. Judgment from source, not from the prose’s self-description.

---

## Focus: architect’s round-5 doc corrections

### §4.1 user-facing disclosure (fifth version) — **TRUE**

Verified against `DecoyAccountProvisioner.provision()`:

| Code path | Durable `TAG_DECOY`? |
|---|---|
| Never attempts | no (`isEmpty` → omit) |
| Pre-`register` failure + `clearBackoff` mutates and flushes | no (empty holder omitted) |
| Crash after `reserveBackoff` flush, or retirement flush fails | **yes** (deferral stays) |
| Reaches `register` (429 / lost response included) | **yes** |
| Success, never sends | **yes** |

The user-facing text does **not** claim a precise attach boundary. It says setup can leave a vault marked either way and **“if you are unsure … assume it did.”** That is true under crash-at-any-instruction, and it stays true if U2/U3 move the attach point. No false precise claim.

### Internal truth table crash row — **CORRECT** (spec §4.1 + invariant table)

Both tables now include the crash / failed-retirement row. Matches `reserveBackoff` → (crash) and `clearBackoff` catch leaving the deferral standing.

### Invariant table W2 / W2c — **CORRECT**

- **W2** names `DecoyAuthStore.storeTokensForAccount` (code at `DecoyAccountProvisioner.refreshTokens` → `storeTokensForAccount`).
- Token field writers include **W2c (clear)**; `clearAccount` nulls tokens and resets `counterHighWater` under the section lock.

### Counter-invariant summary — **CORRECTED**; no live `mutate = durable` teaching

Active summary now advances the RAM cursor only after `flushBeforeAck()`. Struck-through text is explicitly labeled as the old F1 error. Grep of unit sources + kdoc + the invariant table found **no active restatement** that treats a successful `mutate` as durability. Code paths that need durability (`W1`, `W1b`, `W1d`, `W3`) all pair `mutate` with `flushBeforeAck` and treat a flush throw as “never happened.”

---

## Finding

### F1 — P3 — Residual incomplete `TAG_DECOY` attach table (K1 not fully grepped)

**Files:**
- `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultState.kt:288–304`
- `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:471–473` (round-4 historical note under the corrected table)

**Concrete failure (derived from code, not from the sentence):**

1. `reserveBackoff()` mutates + `flushBeforeAck()` → `provisionNotBeforeMs` durable → section non-empty → `TAG_DECOY` on disk.
2. Process dies before `register` (or before `clearBackoff` can flush).
3. Outcome: tag present, relay never contacted.

**What the residual prose still says:**

- Codec kdoc: *“failed before `register` → no tag”* and *“the durable trigger is therefore provisioning that reaches relay registration”*, claiming that is *“stated exactly”* and *“the one spec §4.1 states.”*
- Spec note under the (now five-row) table: *“So the trigger is setup that reaches relay registration”* and *“accurate on all four rows.”*

Both omit the crash / failed-retirement row that round 5 added to the internal tables. The user-facing §4.1 is fine; these are leftover **precise** restatements that are false under the unit’s crash model.

**Why tests do not catch it:** documentation only. No behavioral assertion.

**Why it matters at the cap:** same pattern as round-5 K3 — correction landed where the finding pointed (spec/invariant tables); a parallel restatement survived. Not a code defect; not merge-blocking on its own.

---

## Whole-unit pass (code)

Attacked each invariant against source. **No P1/P2 code defects found.**

| Invariant | Result |
|---|---|
| **1. Register-before-commit** | Staging store + one credential `mutate` + flush. Crash matrix leaves orphan or complete set; codec rejects half-sets on encode/decode. |
| **2. Counter skip-never-regress** | Section lock wraps re-read → reserve → flush → cursor advance; singleton allocator; stale block abandoned. Cannot construct a wire regression. |
| **3. Key material wipe** | `DecoyState.wipe` / `VaultState.wipe`; decode-failure path via `PartialDecode`; encode uses `WipeableBuffer` with wipe-on-grow. Accepted residual: libsignal native prekey halves (documented). |
| **4. Deniability** | No device-level storage/log/diagnostics sinks; fixed sealed region; section inside compressed plaintext. Encrypted image size/shape does not vary with decoy *content* at the AEAD boundary. |
| **5. Strict-v1 codec** | Unknown tag throws; empty omitted; pairing enforced; negative high-water refused both sides; trailing/dup/truncation covered by tests. |
| **6. Capacity** | Budget 1024 B; measured path ~640–643 B; overflow → `capacityExceeded` → flush refuse. |
| **7. Mutation / locking** | Writers go through `mutate`; order `section → stateLock → session → storage`; flush releases `stateLock` before disk. No reentrancy from persist sink into decoy components. |
| **8. Presence ≠ readiness** | `hasAccount` vs `canSend`; deferral-only is not provisioned. |
| **9. Registration scarce** | Lazy, one latch per runtime gate, write-ahead back-off, silent `false`, pre-register clear. No hot-loop; ≤1 register attempt per runtime. |

**Tests:** durability assertions read sealed payloads, not live state; half-set generation check uses zero coalescing; decode wipe observed through real `parsePlaintext` + `PartialDecode`. Spot-checked shapes would fail if the pinned property were broken.

**Unit remains deliberately unwired** — nothing constructs the provisioner in production.

---

## Explicit non-findings (not padded)

- Spec W2 is coarser than the invariant table (`refreshTokens` without naming `storeTokensForAccount`) — incomplete inventory, not a false claim about live code.
- `DecoyState` kdoc *“must only ever increase”* for `counterHighWater` is slightly loose under `clearAccount` reset — not a behavioral bug.
- Prekey private halves in native memory — already accepted and documented.

---

## Verdict

**`VERDICT: FINDINGS (0 P1, 0 P2, 1 P3)`**

- **Code: clean** (confirms round-5 convergence).
- **Round-5 corrections on the user-facing disclosure, crash row, W2/W2c, and counter summary: correct.**
- **One residual P3:** incomplete precise attach table still in `VaultStateCodec` kdoc and a stale §4.1 historical sentence — same “restatement not grepped” pattern as K3, applied to K1.

Maintainer can merge on code with confidence; the P3 is a one-paragraph prose sweep, not another implementation round.
