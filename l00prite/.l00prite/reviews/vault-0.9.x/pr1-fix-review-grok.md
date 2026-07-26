I'll review the delta adversarially against the actual source—starting with the commit range and primary vault/marker files, then re-deriving each binding check independently.Next I'll re-derive the binding checks from full source for the primary vault paths, not from the commit summary.Checking a few remaining edge cases: lock reentrancy from nested `open()`, and whether the outer catch can wipe a handed-off key.# Independent Adversarial Security Review — `321b358..9ab8cb0`

**Scope:** `feat/0.9.2-vault-slotb-pr1` fix commit `9ab8cb0`  
**Role:** findings only (no fixes, no edits)  
**Method:** re-derived from post-change source; invariant table / prior reports used as context only

---

## 1. B1 FAIL-CLOSED — CLEAN

**Independent marker-touch enumeration inside `attemptUnlockOrAdd` (lines 642–768):**

| Operation class | Hits in body |
|---|---|
| `clearBothMarkersDurably` / `clearDeleteIntent` / `writeDurableMarker` / `markDeleteIntent` / `markServerDeleteConfirmed` | **none** |
| `File.delete()` / `createNewFile()` on markers | **none** |
| Marker path references | **only** `Files.notExists(deleteIntentFile)` + `Files.notExists(serverDeletedFile)` at 710–712 |

**Reader-only paths:**
- Markers not both proven absent → `UnlockOrAdd.Rejected` (718), **not** a throw
- Throwaway `sealPayload(candKey, ByteArray(0), ops)` runs on that path (715–717) — same shape as ordinary reject (753–756)
- No residual call chain reaches `clearBothMarkersDurably` / `clearDeleteIntent` from the add path

**Writer set after fix (re-derived):**  
`markDeleteIntent`, `markServerDeleteConfirmed`, `destroy` (+ confirmed then clear both), `clearDeleteIntent`, `clearBothMarkersDurably` only via `create()` / `destroy()`, `create()` (under `!binFile.exists()`).  
**`attemptUnlockOrAdd` is not a writer.**

---

## 2. TOCTOU — CLEAN (in-process)

- Entire `attemptUnlockOrAdd` body is one `imageLock.withLock { ... }` (643–768)
- Marker `Files.notExists` ×2 (710–712) and create `atomicWrite` (731) share that section with **no** intermediate unlock
- `markDeleteIntent` / `markServerDeleteConfirmed` each take `imageLock` (962–967) → cannot interleave with the check→write window on another thread
- Nested `open()` (644) re-enters the same `ReentrantLock` (304); no alien callback / marker writer under the lock
- Fail-closed tristate: indeterminate stat → `Files.notExists == false` → treat as not-absent → `Rejected`

No path was found where an in-process marker writer can insert a marker between the gate and the bin write, or where marker state is decided outside the lock for this gate.

*(External FS writers outside the process are not serialized by `imageLock`; that is pre-existing and not introduced by this delta.)*

---

## 3. B2 SELF-VERIFYING SEAL — CLEAN (with residual note)

**`VaultSlots.sealSlotSelfVerifying` (93–118):**
- Equality: `MessageDigest.isEqual(recovered, vaultKey)` with both required/produced as `VAULT_KEY_BYTES` — platform constant-time compare over equal-length inputs
- Master key: derived once, held only in the `try` that covers encrypt + decrypt + compare; `finally { wipe(masterKey) }` on **every** exit including encrypt/decrypt/check throws
- `recovered` wiped in inner `finally` on every path after allocation
- Lifetime vs `sealSlot`: not stored longer; wall-clock is slightly longer (extra decrypt+compare) but not escaped — acceptable

**Ordering vs persist in `attemptUnlockOrAdd`:**
- `sealSlotSelfVerifying` at 665 runs **before** `return when` and **unconditionally** before any create-branch write (721–731)
- Self-verify throw → outer catch (759–765) → no image mutation

**Residual (Info, not a fail of the stated wrap-check):** self-verify covers the **wrapped-key** layer only. `sealPayload` / outer GCM are not round-tripped. `create()`’s `unlockImage` still checks more surface; this is the known KDF-budget tradeoff, not a regression of the wrap-only claim.

---

## 4. TIMING PARITY AT COUNT 6 — CLEAN (source re-derived)

`SLOT_COUNT = 4`.

| Stage | Count | Where |
|---|---|---|
| Argon2id | **5** | `tryPassphrase` ×4 + candidate seal ×1 |
| Wrapped-key GCM | **6** | 4 sweep unwraps + 1 seal encrypt + 1 self-verify decrypt |
| 256 KiB payload GCM | **1** | open (unlock/burn) or seal (create success / marker-reject / ordinary reject) |
| Outer image GCM | **0 or 1** | **only** successful create path (markers absent + write) |

**All four outcomes (source paths):**
- **Unlock** (681–693): 5 Argon2 + 6 wrap GCM + 1 payload open; no outer
- **Burn** (669–677): same heavy set + 1 payload open (discarded); no outer
- **Create success** (719–745): same + 1 payload seal + 1 outer (documented residual)
- **Reject** (750–756): same + 1 throwaway payload seal; no outer

**Marker-present create** (713–718): identical heavy crypto to ordinary reject (throwaway seal, no outer, `Rejected`). Not covered by the unit parity harness (which measures unlock/reject/create/burn only) but **source-equivalent**.

**`create` flag / triple-entry positions:** sweep + self-verifying seal always run **before** the `when`; `create` is consulted only after no match. Match/burn ignore `create`. No outcome-dependent divergence in the 5/1/6 budget; only the documented create-persist residual differs (and marker-present create deliberately **avoids** that residual, matching reject).

---

## 5. F4 WIPE DISCIPLINE — CLEAN

- `candKey` allocated inside `try` with `.also { candKeyForCleanup = it }` (663) — if `randomBytes` throws, mirror stays null
- `candKeyForCleanup` and `candKey` always alias the same array after allocation; no reassignment of either
- Catch (759–765): wipes candidate (if any) **and** `unlock?.vaultKey` on any throw
- Successful **Unlocked** / **Created**: `return when` exits the function without entering `catch` → handed-off keys not wiped
- Failure paths wipe then throw (`CorruptImage`, `NotDurable`) → catch re-wipe is zero-fill no-op
- Self-verify throw before handoff: both candidate and any matched unlock key cleaned

No strand path for `candKey` or live `unlock.vaultKey` on throw; no successful-return wipe of a handed-off key.

---

## 6. F9 + BIOMETRIC RANGE TIGHTEN — CLEAN (explicit)

**(a) A-only invariant:**  
`unlockWithKey` requires `slotIndex in VAULT_SLOT_RANGE` (1..3) (580).  
`BiometricUnlockStore.load` rejects `slot !in VAULT_SLOT_RANGE` including slot 0 (45) → `isEnabled()` false, never reaches the require.  
Biometric enable uses `session.slotIndex` from a vault open; burn never becomes `Unlocked` / session.

**(b) Legitimate A never on slot 0 (v3):**  
`createVaultSlots` places via `randomVaultSlotIndex` → `VAULT_SLOT_RANGE` only (`VaultSlots.kt` 141).  
`attemptUnlockOrAdd` create placement same (664).  
No legit A-bound wrap names slot 0 on a v3 image.

**(c) 0.9.1 upgrade + biometric:**  
v2 may have A at slot 0; `open()` throws `LegacyImage` before slot interpretation.  
Re-onboard: `retireLegacyImage` then `create` (v3 pool placement).  
`createVaultAndPublish` **keeps** `PREFS_SETTINGS` (including biometric wrap).  
If old wrap stored slot 0: **new** load → not-enabled (silent disable), no throw, no burn open.  
If old wrap stored slot 1–3: still “enabled” but key material is for the **retired** vault → `unlockWithKey` AEAD fails → false (pre-existing keep-prefs hygiene; not caused by excluding slot 0).  
Net: slot-0 tighten does not brick a live v3 A biometric; it only demotes an obsolete slot-0 wrap safely.

---

## 7. GENERAL NEW DEFECTS

### Finding G1 — **Low** — Security-critical KDoc still describes the **rejected** OQ3 behavior

- **FILE + FUNCTION:** `VaultImageStore.kt` — `attemptUnlockOrAdd` KDoc, lines **607–609, 629–631, 639–640**
- **DEFECT MECHANISM:** Public contract text still claims (1) candidate seal is plain `sealSlot` with 1 wrapped GCM, (2) “create clears BOTH delete markers durably FIRST”, (3) `NotDurable` if “pre-create marker clear” fails. Implementation does the opposite on markers and uses `sealSlotSelfVerifying` (6 wrap GCMs).
- **FAILURE / ATTACK SCENARIO:** Future generator/reviewer trusts the KDoc over the body, re-introduces marker-clear-over-live-image (the defect this fix removed) or wrong parity accounting. Not a runtime vuln today; high recurrence risk given this surface’s history.

### Finding G2 — **Info** — Parity test name/comment lag; marker-present budget untested

- **FILE:** `AttemptUnlockOrAddTest.kt` — `cryptoBudgetParity_5derivations_1payloadGcm_5wrappedGcm_acrossAllFourOutcomes` (~335)
- **MECHANISM:** Asserts 6 wrap GCMs correctly, but method name still says `5wrappedGcm`; does not measure `create=true` + marker-present.
- **SCENARIO:** No wrong runtime outcome; documentation/test-hygiene only. Source re-derive covers marker-present.

### Finding G3 — **Info** — B2 is wrap-only vs `create()` full re-open

- **FILE + FUNCTION:** `VaultSlots.sealSlotSelfVerifying`; create branch `sealPayload` at 721
- **MECHANISM:** Mis-AEAD that corrupts only `PAYLOAD_AD` (or outer) still persists an unopenable vault after process death while in-memory `Created` used `genesisPayload.copyOf()`.
- **SCENARIO:** Broken/hostile `VaultSodiumOps` payload path → durable B that never unlocks post-restart. Same threat class B2 targeted for wraps; payload remains an accepted residual under the one-payload-GCM budget.

### Rejected-on-marker × router / triple-entry — CLEAN (interface)

- Returns `Rejected` (same as wrong passphrase), not throw → no exception side channel mid-delete (improves on pre-fix `NotDurable`)
- Timing matches ordinary reject (no outer GCM)
- Store does not loop; a future triple-entry create ritual that gets three `Rejected`s fails closed while markers live (disclosed in `SECURITY_MODEL.md` 455–464)
- Match path still unlocks A for reconcile; markers not consulted on unlock/burn (unchanged, correct for intent-only)

### Other surfaces — CLEAN

- **canonical/dek:** NotDurable still advances `canonical` after rename; DEK reused; no new desync shape
- **`create()` / `unlock`:** unchanged by this delta
- **`unlockWithKey` callers:** only behavioral change is hard-reject slot 0 (intended F9)
- **`when` restructure:** create branch `if (!markersAbsent) Rejected else { write… }` — no fall-through bug; all arms return `UnlockOrAdd`
- **Key use-after-wipe:** not found on success or throw paths (see §5)

---

## Binding checklist summary

| Item | Verdict |
|---|---|
| 1 B1 fail-closed pure reader | **CLEAN** |
| 2 TOCTOU check+write under one lock | **CLEAN** |
| 3 B2 constant-time self-verify + wipe + pre-persist | **CLEAN** (payload residual Info) |
| 4 Timing 5 / 1 / 6 all outcomes + positions | **CLEAN** |
| 5 F4 wipe / mirror | **CLEAN** |
| 6 F9 + biometric range | **CLEAN** |
| 7 General new defects | **G1 Low (stale KDoc)**; G2–G3 Info |

---

## Overall verdict

**PASS — no Critical/High/Medium runtime defects in `321b358..9ab8cb0`; B1/TOCTOU/B2/parity/F4/F9 hold under adversarial re-derivation. Only non-blocking residue: stale marker-clear KDoc (Low) plus Info test/docs residuals.**
