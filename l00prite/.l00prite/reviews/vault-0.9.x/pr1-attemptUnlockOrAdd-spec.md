# PR-1 SPEC — `VaultImageStore.attemptUnlockOrAdd` (0.9.2-beta: second vault + Pucker Burn)

**Status:** APPROVED (user, 2026-07-24T11:52Z) — implementation authorized WITH the blocking §10.1
resolution (option (a), in-scope) and the two §9 review-scope amendments below.
**Author:** claude, 2026-07-24 (REVISED — burn-aware; supersedes the earlier double-entry/25% spec).
**Scope owner:** jackofall1232. **Decisions:** see vault-ledger top block (2026-07-24 REVISED).

> **⚠️ IMPLEMENTED BEHAVIOR SUPERSEDES THE SKETCHES BELOW (2026-07-24, both G3 reviewers, Low).** This was
> the pre-implementation plan. During implementation + three review rounds the algorithm CHANGED in ways the
> §3/§4 algorithm sketches, §5 prose, and §8 test list DO NOT reflect: (1) **B1 fail-closed** — the create
> branch NEVER clears a delete marker; if it can't prove both markers absent it returns `Rejected` (§4's
> marker-clear-create is REMOVED); (2) **B2** — the candidate seal is `sealSlotSelfVerifying` (2 wrapped-key
> GCM, 6 total per call, not "1 tiny"); (3) **G3** — a successful create does a SECOND payload self-verify
> GCM (create = 2 payload GCM; every other outcome = 1). **The authoritative current record is: the
> `attemptUnlockOrAdd` KDoc in source, the §5 crypto-budget TABLE below (which IS corrected), and the vault
> ledger.** Do NOT trust the §3/§4 sketches or the §8 "exactly one … for create" line for the budget or the
> marker handling — they are retained only as planning history.

---

## 0. Scope & non-goals

**In scope (PR-1):** one new `VaultImageStore` method fusing "try to unlock", "detect a burn match",
and "maybe create a vault" into a single, constant-crypto-work, `imageLock`-atomic operation — plus its
durability, delete-marker, wipe, and **slot-0 (burn) awareness**. Plus a companion change so `create()`
places the everyday vault in the 1–3 pool (never slot 0). This is the sole **new writer** to the durable
image in 0.9.2.

**Out of scope (later / sibling PRs):**
- Triple-entry gate state machine + uninterrupted-sequence guard + timing tests + router fusion → **PR-2**.
- MainActivity wiring, biometric A-only guard (OQ4), doc reconciliation (OQ5) → **PR-3**.
- **Pucker Burn SETUP UX** (settings entry, permanence ack) and **burn WIPE execution** → **sibling PRs**.
  PR-1 makes the store burn-AWARE (returns a `Burn` outcome on a slot-0 match) but does NOT arm burn or
  perform the wipe.
- Per-vault destruction → separate future phase; `destroy()` stays whole-image (OQ3).

**Framing:** `SECURITY_MODEL.md` is already honest (PR-F). 0.9.2 flips status to "two vaults creatable"
and adds the new burn/limitation disclosures.

---

## 1. Slot model (NEW — burn changes placement, not the byte format)

`SLOT_COUNT = 4` (unchanged; raising to 8 rejected — see ledger). Byte format unchanged:
`version(1) ‖ 4×[salt(16)‖wrapped(60)] ‖ 4×payload(256 KiB)`. What changes is **placement semantics**:

```kotlin
const val BURN_SLOT_INDEX = 0                     // reserved for the Pucker Burn credential
val VAULT_SLOT_RANGE = 1 until SLOT_COUNT         // 1..3 — the vault pool
/** Blind vault placement — slot 0 is NEVER chosen. Used by create() AND attemptUnlockOrAdd. */
fun randomVaultSlotIndex(ops) = 1 + randomIndex(SLOT_COUNT - 1, ops)   // 1..3
```

- **Slot 0** is sealed byte-identically to any vault slot (same Argon2id, same structure, same timing).
  Only its *contents* differ (a burn marker, not a VaultState). **Arm-state is stored nowhere** — "armed"
  simply means a passphrase can match slot 0, which is exactly what `tryPassphrase` already tests. An
  examiner cannot tell from structure/timing whether slot 0 is armed. Until burn is set up, slot 0 is
  random filler (indistinguishable), so it never matches.
- **Slots 1–3** hold the vault pool: the everyday vault A (placed by `create()` at `randomVaultSlotIndex`)
  and any created vault B (placed by `attemptUnlockOrAdd` at `randomVaultSlotIndex`).
- **Collision probability is ~1/3 (~33%)**, not 25% (OQ2 corrected): blind placement is over 3 slots.
- **Full-pool overwrite:** if slots 1–3 all hold real vaults, any further creation overwrites one with
  certainty and no warning (ZK: the app cannot detect a full pool). Burn (slot 0) is never touched by
  vault creation, so duress protection survives even a full pool. Documented in SECURITY_MODEL (PR-3).

**Companion change to `create()` / `createVaultSlots`:** placement changes from `randomIndex(SLOT_COUNT)`
(0..3) to `randomVaultSlotIndex` (1..3). Slot 0 becomes filler at onboarding (unarmed burn). This
diverges from the web reference `vault.ts` (which has no burn slot); acceptable — burn is Android-only
and the byte format is unchanged. **Included in PR-1** because it is the same slot-0-reservation invariant.

---

## 2. WRITER / READER invariant table (built FIRST, per standing discipline)

Durable state: `vault.bin` (image), `vault.dek`, `vault.delete-intent`, `vault.delete-confirmed`.
In-RAM: `canonical`, `dek`. **Auth tokens live INSIDE each slot's VaultState payload — per-slot, not a
device file. Burn arm-state is NOT stored — it is implicit in whether slot 0 is a real sealed slot.**

### Writers

| Writer | Writes | DEK | Markers | Slot 0? | New in 0.9.2? |
|---|---|---|---|---|---|
| `create()` (companion-changed) | bin+dek, fresh image, A placed **in 1–3** | writes new | clears BOTH first | leaves slot 0 as filler | placement changed |
| `writeSealedPayload()` | ONE payload region (existing live slot, always 1–3) | reuse | none | never | no |
| `markDeleteIntent` / `markServerDeleteConfirmed` | a marker | — | writes | — | no |
| `destroy()` | confirmed marker, unlinks bin+dek, clears both | deletes | writes then clears | wipes slot 0 too (whole image) | no |
| `clearDeleteIntent` / `clearBothMarkersDurably` | unlink marker(s) | — | clears | — | no |
| **`attemptUnlockOrAdd()` — Created branch (NEW)** | **bin full re-encode: ONE new slot-table entry + ONE new payload at a 1–3 index; all else byte-identical** | **reuse (never touches dek)** | **clears BOTH first (durable), like `create()`** | **never writes slot 0** | **YES** |
| `attemptUnlockOrAdd()` — Unlocked / **Burn** / Rejected | **nothing on disk** | — | none | reads slot 0 (sweep) but writes nothing | YES (no-op writers) |
| **Pucker Burn SETUP (sibling PR)** | **slot 0's slot entry + payload (arms burn)** | reuse | none (TBD) | **writes slot 0** | future |
| **Burn WIPE (sibling PR)** | whole-image destroy | deletes | TBD (open item 2) | — | future |
| **`retireLegacyImage()` (NEW, §10.1(a))** | **unlinks bin+dek+tmps durably — ONLY after re-proving inner version == 2 under imageLock** | wipes RAM copy; deletes file | **none — format retirement is NOT an account delete; markers untouched** | n/a (whole image) | **YES** |

### Reader change (NEW, §10.1(a)): `open()` version branch

`open()` today maps ANY unexpected inner version to `CorruptImage` (escalate, never recreate). PR-1
splits that reader: inner version == 2 (the KNOWN 0.9.1 format) → NEW `VaultImageException.LegacyImage`
(caller routes to fresh onboarding via `retireLegacyImage()` + create); any OTHER unknown version →
`CorruptImage` as today. The v2 image is NEVER slot-interpreted (no sweep, no slot-0 read) — the branch
fires before any slot material is decoded into use. `retireLegacyImage()` re-proves version==2 itself
(full open-style read under `imageLock`) so a misrouted call can never delete a valid v3 image;
retirement happens only on the explicit onboarding action, not silently at boot.

### Readers (unchanged): `open()`, `unlock()`/`unlockWithKey()`, marker readers, boot Route, `onSessionRevoked→clearTokens` (guarded).

### Invariants preserved (and how)

1. **No `{bin-present, dek-absent}` brick.** Created reuses the existing durable DEK and rewrites only
   `bin`; `dek` never written/deleted → present+durable throughout. Stronger than `create()`'s DEK-first
   barrier (no DEK write at all).
2. **A confirmed marker never coexists with a live successor image.** Created clears BOTH markers durably
   BEFORE writing (mirrors `create()` F2 / round-14); non-durable clear → throw, nothing written.
3. **A plain unlock/burn/reject writes no marker and clears no tokens.** Only Created writes.
4. **Tokens are per-slot, inside the payload.** Created writes only the new vault's (empty genesis)
   payload + its slot entry. It never reads/writes/clears another vault's payload or tokens. The only way
   it affects an existing vault is the accepted **~33% blind clobber** (a documented whole-slot destroy).
5. **Slot 0 (burn) is never a write target of vault creation.** `randomVaultSlotIndex` yields 1–3 only,
   so no created/everyday vault can land on slot 0, and the burn credential can never be clobbered by
   vault creation. (Duress protection survives even a full vault pool.)
6. **NEW INTERACTION — top review target.** Created clears the device-level `vault.delete-intent`, which
   **cancels a pending A-side delete-reconcile** (OQ3). Realistically only an intent-only marker is
   present at a lock screen during a triple-entry (a confirmed marker routes boot to auto-destroy before
   any lock screen). Fail-safes toward "no destruction." Flagged for the adversarial pass.
7. **Single-writer / lock order.** Entirely under `imageLock`; never calls a `VaultSession` → no reverse
   `flushLock→imageLock` nesting. Sweep + candidate + write are atomic vs other store ops.

---

## 3. Method contract

```kotlin
sealed interface UnlockOrAdd {
    /** An existing VAULT slot (1..3) matched — normal unlock. Router discards the triple-entry. */
    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
    /** Slot 0 matched — the Pucker Burn credential was entered. The APP performs the wipe (sibling PR);
     *  the store performs NO wipe here. Carries nothing (arm-state/contents are not exposed). */
    data object Burn : UnlockOrAdd
    /** No slot matched AND create==true — a new vault was created + persisted durably. */
    data class Created(val open: VaultOpen) : UnlockOrAdd
    /** No slot matched AND create==false — indistinguishable wrong-password. */
    data object Rejected : UnlockOrAdd
}

/**
 * Fused unlock / burn-detect / maybe-create. ALWAYS identical heavy crypto regardless of outcome:
 * SLOT_COUNT (=4, incl. slot 0) Argon2id sweep + 1 unconditional candidate-seal Argon2id + exactly one
 * 256 KiB payload GCM + one tiny wrapped-key GCM. A slot match (0..3) ALWAYS wins over [create]; a
 * slot-0 match wins as Burn. CPU-heavy: caller MUST be off-main. Under imageLock; opens from disk if needed.
 *
 * @param passphrase entered passphrase (never logged).
 * @param genesisPayload plaintext to seal into a NEW vault (VaultState.empty() encoded). Caller owns+wipes it.
 * @param create whether a no-match should CREATE a vault (router sets true only on the 3rd consecutive
 *   identical non-matching, uninterrupted entry — PR-2). Ignored on ANY slot match (incl. slot 0).
 * @throws VaultImageException.MissingImage/CorruptImage from open() or an unreadable matched vault payload.
 * @throws VaultImageException.NotDurable create wrote but rename durability unconfirmed.
 * @throws VaultImageException.DestroyFailed the pre-create marker clear was not durable.
 */
fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd
```

---

## 4. Algorithm (exact crypto-op accounting)

All under `imageLock`. `EMPTY = ByteArray(0)`.

```
image   = canonical ?: run { open(); canonical!! }         // may throw Missing/Corrupt
decoded = decodeImage(image)

// (1) SWEEP — ALWAYS. 4 Argon2id (slots 0..3), no early exit. Slot 0 included.
val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)

// (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny (60 B) wrapped-key GCM. Real vault B material on
//     create; pure timing filler otherwise. Placement is over the 1..3 pool (never slot 0).
val candKey       = ops.randomBytes(VAULT_KEY_BYTES)
val candSlotIndex = randomVaultSlotIndex(ops)              // 1..3 — slot 0 excluded (invariant 5)
val candSlot      = sealSlot(passphrase, candKey, ops, deriver)

try {
  when {
    // ── BURN (slot 0 match) WINS ───────────────────────────────────────────────
    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
      wipe(candKey)
      // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload, then discard.
      val pt = runCatching { openPayload(unlock.vaultKey, decoded.payloads[0], ops) }.getOrNull()
      pt?.let { wipe(it) }
      wipe(unlock.vaultKey)
      return Burn                                          // APP wipes (sibling PR); store writes nothing
    }

    // ── VAULT MATCH (slot 1..3) WINS over create ───────────────────────────────
    unlock != null -> {
      wipe(candKey)
      val pt = try {
        openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)   // (3) the 1×256KiB GCM
      } catch (t: Throwable) { wipe(unlock.vaultKey); throw VaultImageException.CorruptImage() }
      if (pt == null) { wipe(unlock.vaultKey); throw VaultImageException.CorruptImage() }
      return Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
    }

    // ── CREATE a vault ─────────────────────────────────────────────────────────
    create -> {
      val absent = Files.notExists(deleteIntentFile.toPath()) &&
                   Files.notExists(serverDeletedFile.toPath())
      if (!absent && !clearBothMarkersDurably()) throw VaultImageException.NotDurable()

      val sealedGenesis = sealPayload(candKey, genesisPayload, ops)             // (3) the 1×256KiB GCM
      val newSlots    = decoded.slots.toMutableList().also    { it[candSlotIndex] = candSlot }
      val newPayloads = decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
      val newInner    = encodeImage(VaultImage(newSlots, newPayloads))
      val outer       = ops.aeadEncrypt(dek!!, newInner, VAULT_IMAGE_OUTER_AD)
      val sync        = atomicWrite(binFile, outer)         // throws pre-rename; returns DirSyncResult
      canonical       = newInner                            // advance BEFORE durability check (as writeSealedPayload)
      if (sync != DirSyncResult.DURABLE) { wipe(candKey); throw VaultImageException.NotDurable() }
      return Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))  // candKey handed to session
    }

    // ── REJECT ─────────────────────────────────────────────────────────────────
    else -> {
      val throwaway = sealPayload(candKey, EMPTY, ops)      // (3) the 1×256KiB GCM — LOAD-BEARING filler
      wipe(candKey); wipe(throwaway)
      return Rejected
    }
  }
} catch (t: Throwable) { wipe(candKey); throw t }           // defensive; double-wipe is a no-op
```

**Deliberate deviations from `create()` (forced by timing parity — §5):**
- **One `tryPassphrase`, not two.** Do NOT route create through `addVaultToImage`/`addVaultSlot` (they
  re-run `tryPassphrase` → 8 Argon2id on create vs 5 on reject). Reimplement inline from primitives; the
  collision check is unnecessary (the sweep already proved no match).
- **No verify-by-`unlockImage`** on create (+SLOT_COUNT derivations). Build the `VaultOpen` directly from
  the known `candKey` + `genesisPayload`; cheap non-KDF size checks (already `require`d by primitives)
  remain. **Reviewer: confirm dropping the full re-verify is acceptable.**

---

## 5. TIMING-PARITY RE-VERIFICATION — 3-attempt model + burn slot 0

**Question (user-mandated):** (a) does each of the 3 triple-entry attempts remain individually
indistinguishable from an ordinary single failed unlock, and (b) does slot 0's presence in `tryPassphrase`
introduce any timing asymmetry between "burn matched", "vault matched", and "no match"?

**Per-call crypto budget by outcome:**

> **Correction (2026-07-24). Two updates since the original table.** (1) wrapped-key GCM is **6** per call,
> not 1 — 4 sweep unwrap attempts + 1 candidate `sealSlotSelfVerifying` encrypt + 1 self-verify decrypt
> (B2 fix; the original table counted only the candidate seal). (2) A **successful create does 2 payload
> GCM** — the genesis seal + a self-verify open (G3 fix, constant-time content compare) — a create-only
> residual alongside the outer GCM + write; every other outcome does 1. The parity test asserts exactly
> these counts; the marker-present create fails closed to the 1-payload reject budget.

| Outcome | Argon2id | 256 KiB payload GCM | wrapped-key GCM (60 B) | ~1 MiB outer GCM + write |
|---|---|---|---|---|
| Unlocked (slot 1–3) | 4 sweep + 1 candidate = **5** | 1 (openPayload) | 4 unwrap + 1 seal + 1 self-verify = **6** | none |
| **Burn (slot 0)** | **5** | 1 (openPayload slot 0, discarded) | **6** | none |
| Created (markers absent) | **5** | **2** (seal genesis + self-verify open) | **6** | **yes (persist)** |
| Created→Rejected (marker present, fail-closed) | **5** | 1 (seal throwaway) | **6** | none |
| Rejected (no match) | **5** | 1 (seal throwaway) | **6** | none |

**(a) 3-attempt parity — PASS.** Each ritual attempt issues the identical `attemptUnlockOrAdd` op as any
ordinary failed unlock: **5 Argon2id + 1×256 KiB GCM + 1 tiny GCM**, invariant across outcome and across
attempt position (1/2/3). The triple-entry counter + candidate compare live entirely in router RAM
(SHA-256 + constant-time `MessageDigest.isEqual`, ~µs, computed every attempt), never touching the KDF
budget; `create` only selects whether the post-outcome persist runs. So attempt 1 ≡ attempt 2 ≡ ordinary
wrong-password ≡ Rejected, byte-for-byte; attempt 3 does the same 5 Argon2id, then persists.

**(b) burn / slot 0 parity — PASS.** The sweep is over `SLOT_COUNT = 4` including slot 0 — that count is
unchanged by burn (slot 0 was always 1 of 4). **Armed vs unarmed slot 0 is timing-identical**: either way
`tryPassphrase` runs one Argon2id over slot 0's salt and one GCM-unwrap of its 60-byte wrapped key; a
GCM auth success (armed + burn pass) vs failure (filler, or wrong pass) is the same constant-time-ish
verify, and there is no early exit — this is precisely the real-vs-filler slot indistinguishability the
whole scheme already rests on. **Burn-match and vault-match are pre-outcome timing-identical**: both do
5 Argon2id + one 256 KiB payload GCM; the wipe (burn) and the unlock (vault) are both *post-outcome*
observable events, exactly like unlock-vs-stay-locked today. No-match is likewise 5 Argon2id + one GCM.

**Slot-0 exclusion from placement — no signature.** `candSlotIndex = 1 + randomIndex(3)` vs
`randomIndex(4)` both draw 4 CSPRNG bytes + one modulo; one extra add — sub-nanosecond, unmeasurable.
**Write-IO reveals no slot:** Created re-encodes and writes the *entire* ~1 MiB image (all 4 slots + 4
payloads, fresh outer nonce) in one atomic rename, so an IO-pattern observer cannot tell which slot
changed. The only place slot-0 exclusion is observable is **at-rest multi-snapshot content diffing**
(slot 0's region never changes during vault use; only burn setup touches it) — and that is an
already-accepted, documented limitation that reveals only "a burn feature exists" (public), never
arm-state or vault count. Consistent with the recorded deniability rationale.

**Sole residual (unchanged by any of this):** Created persists synchronously — ~1 MiB outer-GCM +
`atomicWrite` + `dirSync`, ~tens of ms, *after* the outcome is determined, under ~1 s of KDF. Present in
any creation model; not introduced or worsened by triple-entry or by burn. Same class as the documented
payload-open asymmetry. **Lever if ever wanted:** gate every unlock/reject on a synchronous throwaway
write. Rejected — needless slowdown + a new failure surface on every wrong password.

**Load-bearing guarantee, stated precisely:** the Argon2id work — the only second-scale, memory-hard,
reliably-stopwatch-measurable component — is **identical (5×)** across all four outcomes (unlock / burn /
create / reject) and all three attempt positions. Sub-KDF GCM/write deltas on the create path are the
documented residual.

**Backoff (PR-2 context):** the existing RAM backoff fires on Rejected attempts (`recordFailure`), so a
triple-entry ritual looks like 3 fumbled passwords — reinforcing indistinguishability. `candidateCount`
(identical-string streak, reset on background/lock/process-death per the uninterrupted-sequence guard) is
SEPARATE from the backoff `failedAttempts`. A Burn outcome must reset neither into a create — it returns
before any counter logic.

---

## 6. Durability & failure semantics

- `open()` throw (Missing/Corrupt): propagates (lazy open), same as `unlock()`.
- Vault-slot match, unreadable payload: throw `CorruptImage` (a real vault's payload is damaged — image
  state, not a wrong guess), so the ritual counter never advances over a real-but-corrupt vault.
  **Reviewer: weigh vs returning `Rejected`/null.**
- **Slot-0 (burn) match, unreadable payload:** still return `Burn`. A slot-0 wrapped-key match already
  confirms the burn credential; the payload open is parity filler (`runCatching`/discard), so a corrupt
  burn payload must NOT suppress the wipe. (Deliberate asymmetry vs the vault-match corrupt case — a
  duress wipe must fire even if the marker payload is damaged.) **Reviewer: confirm.**
- Pre-create marker clear not durable → `NotDurable`, nothing written.
- Create pre-rename IO failure → `atomicWrite` throws; `canonical` untouched; `candKey` wiped.
- Create rename landed, dir-fsync unconfirmed → `canonical` advanced (bytes on disk), `candKey` wiped,
  throw `NotDurable`. The new vault is now in `canonical`, so a later single entry of its passphrase
  MATCHES → Unlocked (no write needed); if the rename didn't survive a crash, it is simply absent and
  re-creatable. Mirrors the store's flush-before-ack `NotDurable` philosophy. Router UX (PR-2): failed
  create, reset the ritual, generic retry.

`Created` is returned ONLY on a confirmed-durable write.

---

## 7. Memory / wipe discipline

- `candKey`: wiped on Unlocked / Burn / Rejected; on Created it becomes `VaultOpen.vaultKey` (handed to
  the session, not wiped); wiped on every create failure; outer `catch` re-wipes defensively.
- Burn path: `unlock.vaultKey` (slot-0 key) and its opened payload are wiped; nothing retained.
- `throwaway` (reject filler): wiped immediately.
- Vault-match: `unlock.vaultKey` becomes `VaultOpen.vaultKey`; on corrupt payload it is wiped before throw.
- `sealedGenesis`/`newInner`/`outer`: ciphertext, dropped not wiped.
- `genesisPayload`: caller-owned+wiped (mirrors `create()`); `Created` carries an independent `copyOf()`.
- Assumption to confirm against `VaultPayload.kt`: `sealPayload`/`openPayload` do NOT wipe the caller's key.

---

## 8. Tests required (host-JVM, injected counting deriver/ops)

1. **Crypto-budget parity (load-bearing):** counting deriver + ops → assert EXACTLY 5 deriver calls and
   EXACTLY one 256 KiB GCM + one 60 B GCM for EACH of {unlock(1–3), **burn(slot 0)**, create, reject}.
2. **Burn detection:** an armed slot 0 + burn passphrase → `Burn`, nothing written, bin byte-identical.
   Unarmed slot 0 (filler) + arbitrary passphrase never yields `Burn`.
3. **Placement excludes slot 0:** force `randomVaultSlotIndex` across its range → created index ∈ {1,2,3};
   a create never overwrites slot 0 (armed burn still matches after any number of creates).
4. **Functional:** vault match → `Unlocked`; create no-match → `Created`, fresh `open()`+`unlock(newpass)`
   returns genesis; reject → `Rejected`, nothing written.
5. **Match wins over create** (both vault-slot and slot-0): `create=true` but a slot matches → `Unlocked`
   / `Burn`, no write.
6. **Blind overwrite (~33%, OQ2):** forced index onto an existing vault's slot → it's overwritten (old
   no longer unlocks; new does). Locks the accepted behavior.
7. **Marker interaction (OQ3 / invariant 6):** present intent cleared by a successful create; non-durable
   clear → `NotDurable`, nothing written.
8. **Durability:** forced `NOT_DURABLE` → `NotDurable`, `canonical` advanced, `candKey` wiped; pre-rename
   IO failure → throw, `canonical` untouched.
9. **Corrupt vault payload → `CorruptImage`; corrupt burn payload → still `Burn`.**
10. **No DEK write** across a create; **wipe discipline** (candKey/unlock.vaultKey zeroed on non-keep paths).
11. **`create()` companion:** onboarding places A ∈ {1,2,3}; slot 0 is filler.

---

## 9. Reviewer focus (lean pass — ≤5 agents, one skeptic; free Codex/Gemini for breadth)

**USER AMENDMENTS (2026-07-24, binding on the review round):**
- **Amendment 1 → item 1 is a FULL enumeration, not the abbreviated §2 argument.** The claim "only
  intent-only markers realistically appear at a lock screen" is exactly the
  reader-assumes-a-marker's-meaning shape that produced the round-12 and round-15 defects. Reviewers
  must enumerate EVERY writer of `vault.delete-intent` and `vault.delete-confirmed`, and every reader's
  assumption, and prove the assumption holds for every writer state INCLUDING mid-write crash — the
  rounds-13–16 discipline.
- **Amendment 2 → item 5 needs an explicit answered VERDICT, not a nod.** Building `VaultOpen` directly
  from `candKey` removes a round-trip that would catch a malformed sealed slot before handing a key to a
  session. The reviewer must STATE whether the remaining non-KDF size checks are actually sufficient,
  and if not, what the cheapest verify is that preserves timing parity.

1. **Invariant 6** — marker clear on create cancels A's pending delete-reconcile (full writer/reader
   enumeration per amendment 1).
2. **Timing parity §5** — the 5-Argon2id / 1-GCM invariant across all FOUR outcomes incl. burn; the
   armed-vs-unarmed slot-0 argument; the residual.
3. **Burn correctness §6** — burn fires even on a corrupt slot-0 payload; a slot-0 match can never be
   treated as Unlocked or feed the create/ritual path.
4. **Durability §6** — `NotDurable`-with-canonical-advanced; no brick (invariant 1).
5. **Deviations §4** — dropped second `tryPassphrase` + `unlockImage` verify; explicit sufficiency
   verdict per amendment 2.
6. **Wipe §7** — `candKey` hand-off on Created; no live key abandoned on any throw or on the burn path.
7. **§10.1 legacy path** — v2 → `LegacyImage` → onboarding, never CorruptImage, never any slot
   interpretation; `retireLegacyImage()` cannot delete a v3 image.

---

## 10. Open items — REQUIRE USER DECISION before/alongside PR-1

1. **✅ RESOLVED (user, 2026-07-24): option (a), BLOCKING and IN-SCOPE for PR-1.** Bump `IMAGE_VERSION`
   2→3; a v2 image routes to fresh onboarding. `open()` gains a known-old-version branch (v2 →
   `LegacyImage`, distinct from `CorruptImage` which still escalates for unknown versions) — that
   read-path branch is part of PR-1's diff with its OWN test: a v2 image must route to onboarding, NOT
   CorruptImage, NOT any slot-0 interpretation. attemptUnlockOrAdd's slot-0 semantics must not land
   before this — a v2 image with A at slot 0 would wipe on the user's own correct passphrase. Recorded:
   this ships despite 0.9.1 being fresh-install-only with no real users — "we happened to have no users"
   is not a safety property. Alternative (lazy in-place migration on first v2 unlock) considered +
   rejected: builds an explicitly-not-built migration path, adds a new writer + first-unlock write
   timing asymmetry, for test-device-only benefit. See §2's new writer (`retireLegacyImage`) + reader
   (`open()` version branch) rows.
2. **Burn wipe scope** (from the decision record): local slots only (all vaults + burn) vs also relay
   account(s); conspicuous vs not. Affects the sibling wipe PR, not PR-1's store contract.
3. **Burn ↔ D2c delete-state-machine:** does the wipe reuse/interact with the rounds-13–16 account-delete
   markers/teardown, or stay fully separate? Needs analysis (sibling PR); PR-1's `destroy()`/marker
   surface is unchanged, but the burn wipe design must not silently re-enter that state machine.

---

## 11. Deferred to PR-2 / PR-3 / sibling PRs

- PR-2: router `candidateHash`/`candidateCount` state machine + **uninterrupted-sequence guard** (reset
  on background / lock cycle / process death); `NotDurable`-on-create UX; genesis encode/wipe at call site.
- PR-3: MainActivity no-match branch; biometric A-only guard (OQ4); doc reconciliation + new disclosures.
- Sibling: burn SETUP UX (settings entry above Delete Account, disappears once set, actively-acked
  permanence warning: never-changeable / immediate-no-confirm-wipe / forgetting-locks-slot-0); burn WIPE
  execution (open items 2 & 3).
