I'll perform an independent adversarial review of the delta only — starting from the commit range and actual source, no fixes.Next I'll read the create-path code, payload seal/open/unpad, and the parity/mis-seal tests against the live sources.# Independent Adversarial Security Review — `296ebc6..8f4545d`

**Scope:** G3 create-path payload self-verify only (`8f4545d` on `feat/0.9.2-vault-slotb-pr1`)  
**Role:** findings only (no fixes, no edits)  
**Method:** re-derived from post-change source + `VaultPayload` round-trip + test doubles; prior PASSes / residual G3 used as context only

---

## 1. CONTENT COMPARE, NOT DECRYPT-SUCCESS — **CLEAN**

**Source:** `VaultImageStore.attemptUnlockOrAdd` L745–750; `VaultPayload.sealPayload` / `openPayload` / `padToCapacity` / `unpad` L43–104; test `MisSealingPayloadOps` L533–543.

| Claim | Re-derivation |
|---|---|
| Compare is on recovered **plaintext** | After `openPayload` non-null: `MessageDigest.isEqual(verifyPt, genesisPayload)` — not “opened ≠ null” alone. Null-open is a **separate** fail-closed arm (`?: throw … did not open`). |
| Equal-length constant-time path | Honest seal: `padToCapacity` stores `content.size` BE in 4 bytes → encrypt full `PAYLOAD_PLAINTEXT_BYTES` → decrypt → `unpad` returns `copyOfRange` of **exactly** that length → `verifyPt.size == genesisPayload.size`. OpenJDK/Android `MessageDigest.isEqual` then walks equal-length buffers (no length-mismatch early exit on this path). |
| Wrong-content self-consistent AEAD fails | `MisSealingPayloadOps` flips **only** `PAYLOAD_AD` encrypt plaintext at index 4 (first content byte **after** length prefix); `aeadDecrypt` is real. Length prefix intact → open succeeds → content differs → `isEqual` false → `check` → `IllegalStateException`. That is the **mismatch** branch, not the null branch. Test genesis (`"genesis-empty-state"`, L78) is non-empty, so index 4 is real content. |

**Why not merely decrypt-success:** a null-only check would accept this provider; the content compare rejects it.

---

## 2. THROW-BEFORE-PERSIST — **CLEAN**

**Order on markers-absent create (L736–777):**

1. `sealPayload` (in-RAM ciphertext only)  
2. `openPayload` + `isEqual` / possible throw (L745–753)  
3. **then** `encodeImage` (L757)  
4. **then** `ops.aeadEncrypt(activeDek, …)` (L760)  
5. **then** `atomicWrite` (L763)  
6. **then** `canonical = newInner` (L766)

On verify failure:

- No `encodeImage`, no outer GCM, no `atomicWrite`, no `canonical` write, no DEK touch (`activeDek` first used at L760).  
- Exception leaves the `when` into outer `catch` (L791–797) → wipe cleanup → `throw t` out of `attemptUnlockOrAdd`.  
- Test asserts bin bytes unchanged (L331–334).

**In-memory-only intermediates on fail:** `candKey` / `candSlot` / `sealedGenesis` exist only in the call frame; store fields `canonical` / `dek` unchanged.

---

## 3. WIPE DISCIPLINE ON THE NEW SEAM — **CLEAN**

| Material | Path | Behavior |
|---|---|---|
| `verifyPt` | mismatch throw | `check` throws → **inner `finally` `wipe(verifyPt)`** (L751–752) always runs |
| `verifyPt` | success | same `finally` wipes before persist; session uses `genesisPayload.copyOf()`, **not** `verifyPt` (L777) |
| `verifyPt` | null-open | never allocated (`?: throw` before `val` bind); nothing to wipe |
| `candKey` | any verify throw | outer F4: `candKeyForCleanup?.let { wipe(it) }` (L795) |
| `unlock?.vaultKey` | create branch | `unlock` is null (match arms already taken); wipe arm is a no-op |
| Handed-off key | `Created` success | `return` from `try` **does not** enter outer catch; `candKey` not wiped (L776–777) |

**Use-after-wipe / double-wipe:**

- After success, `verifyPt` is never read again.  
- `wipe` is `bytes.fill(0)` (`VaultSlots.kt` L244–246) — re-wipe is a no-op, not free/double-free.  
- No coupling that would wipe a handed-off key on the success path.  
- `verifyPt` does **not** need an F4 mirror: it is allocated immediately into a `try/finally` with **no** intervening statements that can throw and strand it. Throws from `openPayload` before assignment are covered by outer catch for `candKey` only (no plaintext allocated).

---

## 4. PARITY UNCHANGED FOR NON-CREATE OUTCOMES — **CLEAN** (source re-derived)

`SLOT_COUNT = 4`. Heavy work before outcome branch is **unconditional** (sweep + `sealSlotSelfVerifying`). Second payload GCM is **only** in `create && markersAbsent` else-arm (L736–746).

| Outcome | Argon2id | Payload GCM | Wrapped GCM | Outer |
|---|---|---|---|---|
| Unlock (L693–705) | 5 | 1 (`openPayload`) | 6 | 0 |
| Burn (L681–689) | 5 | 1 (`openPayload` slot 0) | 6 | 0 |
| Reject (L782–788) | 5 | 1 (throwaway seal) | 6 | 0 |
| Marker-present create (L725–730) | 5 | 1 (throwaway seal) | 6 | 0 |
| Successful create (L731–777) | 5 | **2** (seal + self-verify open) | 6 | 1 |

Marker-present create is **source-identical** in budget to ordinary reject (throwaway seal, no outer, no second open). Intended create residual only.

Parity test (`cryptoBudgetParity_…`, L355–400) asserts create=2 / others=1 payload, 5 Argon2id, 6 wrapped, outer only on create — including explicit `marker-reject`.

---

## 5. NEW DEFECTS FROM THIS DELTA — **no Critical/High/Medium**

Checked surfaces:

| Surface | Verdict |
|---|---|
| Exception type vs `CorruptImage` / `NotDurable` | Payload verify throws `IllegalStateException`, same family as `sealSlotSelfVerifying` / KDoc L652. Fail-closed for broken AEAD, not a soft `Rejected`. Distinct from durability/corrupt-image; **not a new class** beyond B2 wrap. |
| `openPayload` null vs throw | Null → explicit ISE. `unpad` IAE → swallowed to null inside `openPayload`. Unexpected throw from ops → outer catch wipes `candKey`, rethrows. Both handled. |
| F4 / `verifyPt` stranding | See §3 — clean. |
| Canonical / DEK desync | Verify cannot advance `canonical` or touch DEK. Durability ordering after verify unchanged. |
| Create durability/atomicity | Unchanged post-verify sequence (`atomicWrite` then `canonical`, `NotDurable` still advances canonical). |
| Timing / observability | Extra 256 KiB GCM only on markers-absent create path — **documented** create residual extension (KDoc L610–628). Marker-reject still single-payload. |
| Outer-image AEAD still not self-verified | Pre-existing create residual; G3 closed the **payload** half only. Same-provider colluding encrypt+decrypt cannot be defeated by encrypt-then-decrypt with that provider — fundamental limit, not a regression of this delta. |

### Finding G3-I1 — **Info** — outer layer still outside self-verify budget

- **FILE + FUNCTION:** `VaultImageStore.attemptUnlockOrAdd` L760–763 (`aeadEncrypt` / `atomicWrite` of outer image).  
- **MECHANISM:** G3 proves payload open(candKey, sealedGenesis) ≡ genesis; wrapped key already proven by B2. Outer DEK seal is still “encrypt once, write.”  
- **SCENARIO:** Hostile/broken outer encrypt writes a durable bin that fails later outer open — different brick class than unopenable *slot* after process death; not introduced by G3, but still outside the new check.

### Finding G3-I2 — **Info** — null-open arm untested (mismatch arm is)

- **FILE + FUNCTION:** test `create_selfVerifiesThePayload_…` L318–335; production null arm L745–746.  
- **MECHANISM:** Harness proves content-mismatch + no persist; does not inject a payload encrypt that fails auth (null open). Production path is still fail-closed.  
- **SCENARIO:** Coverage gap only — no evidence the null arm is wrong.

---

## 6. DOC / TEST ACCURACY

### Production KDoc (`attemptUnlockOrAdd` L600–652) — **CLEAN**

Matches actual behavior: one payload GCM on every outcome; successful create + second payload self-verify open; marker-present create stays on single-payload reject budget; throws `IllegalStateException` on candidate self-verify failure (wrap + payload).

### Parity test + method name — **CLEAN**

- Name `…_createAloneDoublesPayloadAndOuter` matches create=2 payload + outer=1, others=1/0.  
- Comments match measured outcomes including marker-reject.  
- `MisSealingPayloadOps` comments accurately describe content-flip / self-consistent wrong box.

### Spec §5 **table** (`/root/l00prite/pr1-attemptUnlockOrAdd-spec.md` L236–249) — **CLEAN**

Correction note + table row Created = 2 payload GCM matches code and tests.

### Finding G3-L1 — **Low** — stale surfaces remain outside the §5 table (same class as prior G1)

- **FILE:** `/root/l00prite/pr1-attemptUnlockOrAdd-spec.md`  
  - §3 sketch KDoc L134–136: still “exactly one 256 KiB payload GCM + one tiny wrapped-key GCM”  
  - §4 algorithm L163–167 / L197–205: still `sealSlot` (not self-verifying), **no** payload self-verify, still shows marker-clear create (pre-B1)  
  - §5 prose L252: still “1 tiny GCM” (contradicts corrected wrapped count of 6)  
  - §8 test requirement L332–333: still “EXACTLY one 256 KiB GCM … for EACH of {…, **create**, …}” — **false** after G3  
- **MECHANISM:** Table was updated for G3; adjacent normative sketches / acceptance criteria were not. A later implementer or reviewer trusting §8 / §4 over the table reintroduces the wrong budget or omits the payload self-verify.  
- **SCENARIO:** Same failure mode as prior-round G1 (stale doc of removed/changed behavior): incorrect future parity work or incomplete re-verify against a wrong “exactly one payload GCM including create” checklist.

*(Spec is outside the git delta but is explicitly in binding check 6.)*

---

## Binding checklist summary

| # | Item | Result |
|---|---|---|
| 1 | Content compare + equal-length CT path + MisSealing hits mismatch | **CLEAN** |
| 2 | Throw before encode / outer encrypt / write / canonical / DEK | **CLEAN** |
| 3 | `verifyPt` finally wipe; `candKey` outer wipe; no UAF / success key wipe | **CLEAN** |
| 4 | Non-create + marker-reject still 1/6/5; create alone 2 payload | **CLEAN** |
| 5 | New runtime defects | **None Critical/High/Medium**; Info I1–I2 only |
| 6 | KDoc + tests + §5 table vs leftover stale spec | KDoc/tests/table **CLEAN**; **Low** L1 on residual stale spec surfaces |

---

**Verdict: PASS — no Critical/High/Medium defects in `296ebc6..8f4545d`; G3 content self-verify is real, pre-persist, wipe-safe, and parity-preserving for non-create outcomes. Only non-blocking residue: Low stale-spec surfaces outside the corrected §5 table (G3-L1), plus Info outer-unverified / null-arm coverage gaps.**
