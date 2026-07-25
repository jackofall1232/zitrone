I'll independently verify the 0.9.2 second-vault docs against shipped code: inspect the delta, then cross-check each claim in `tryPassphrase`, slot constants, create/delete paths, and biometric guards.Next I'll pull the full surrounding doc paragraphs and the code that backs each claim.Pulling the implementation of `tryPassphrase`, create/pending-delete, and the biometric first-enable-wins guard.Checking residual capacity wording, SECURITY_MODEL parity claims, and biometric enable guard for contradictions.Checking residual contradictions in SECURITY_MODEL timing language and CHANGELOG, and verifying the web `tryPassphrase` mirror.## Round-3 documentation accuracy review  
**Scope:** `d2ad583..04e72e6` on `feat/0.9.2-vault-pr3-unit2-docs` @ `04e72e6`  
**Verified against shipped Android code** (`tryPassphrase`, `attemptUnlockOrAdd`, slot constants, biometric write guard). Report only.

---

### 1. Timing-parity precision — **CONFIRMED-ACCURATE**

**Claim (§3.1 L81–90, §3.2 L110–121):** Crypto work (timing / memory-access / per-slot access) is identical for match-A / match-B / reject via no-early-exit sweep; outcome (open vs denied) is visible and does not prove a hidden vault; A/B successes are mutually indistinguishable; create adds a disk-persist residual.

**Code:**
- `VaultSlots.kt` `tryPassphrase` (L211–241): loops `slots.indices`, records first match, **does not break**; always derives+unwraps every slot.
- `VaultImageStore.attemptUnlockOrAdd` (L662–663, L671–679, L694–708, L783–790): always `tryPassphrase` over all slots + unconditional candidate seal; match → one payload open; reject → one throwaway payload seal; create success → extra seal/verify + outer encrypt + `atomicWrite` (documented residual).
- Same no-early-exit contract in `packages/crypto/src/vault.ts` `tryPassphrase` (L163–188).

**Scope check:** No residual “success wall-clock-indistinguishable from rejection” overclaim. A/B indistinguishability is stated (not understated). Create residual correctly carved out.

---

### 2. Up-to-three capacity — **CONFIRMED-ACCURATE**

**Claim:** Up to three vaults in pool `1..SLOT_COUNT-1` at `SLOT_COUNT=4`; slot 0 burn; passphrase checked against every vault slot (not just two); README “two (up to three)”.

**Code:**
- `KeySlot.kt` L37: `SLOT_COUNT = 4`
- `VaultSlots.kt` L36–39: `BURN_SLOT_INDEX = 0`; `VAULT_SLOT_RANGE = 1 until SLOT_COUNT` → slots **1,2,3** (three)
- `tryPassphrase` sweeps all image slots; vault matches are pool slots via `attemptUnlockOrAdd` (`Unlocked` only for non-burn matches; placement via `randomVaultSlotIndex`)

**Wording check:** No leftover absolute “only two” / “both slots” / “four vaults” as capacity claims. A/B framing is explicitly the decoy model with pool capacity three (§3.1 L72–74). §3.2 L113 names “A, B, or a third pool vault”.

---

### 3. Pending-delete wording — **CONFIRMED-ACCURATE**

**Claim (`SECURITY_MODEL.md` L497–512):** Same rejection UI + same heavy crypto budget as wrong passphrase; **not** wall-clock identical because of two `Files.notExists` checks; their timing not claimed identical or negligible; parity is over heavy crypto only; outcome is uniform failure; no absolute “leaks nothing” / no “sub-microsecond”.

**Code (`VaultImageStore.attemptUnlockOrAdd`):**
- Create branch (L724–732): `Files.notExists(deleteIntentFile)` ∧ `Files.notExists(serverDeletedFile)`; if either not proven absent → throwaway `sealPayload` → `Rejected` (same single payload-GCM budget as plain reject).
- Plain reject branch (L783–790): throwaway seal only — **no** marker stats.
- Markers: `vault.delete-intent` / `vault.delete-confirmed` (`DELETE_INTENT_FILE` / `SERVER_DELETED_FILE`).

No residual “sub-microsecond” or “leaks nothing”.

---

### 4. §6 biometric asymmetry — **CONFIRMED-ACCURATE**

**Claim (§6 L240–242; consistent with §3.2 L100–109):** Compelled biometric opens only the single biometric-bound vault (first-enable-wins role “A”, never repointed while wrap exists); other vaults passphrase-only.

**Code:**
- `VaultUnlockRouter.biometricEnableAllowed` L172–173: `boundSlot == null || boundSlot == sessionSlot`
- `ZitroneApp.enableBiometricFromSession` L555–567: fail-closed refuse on false (no seal/write/repoint)
- `BiometricUnlockStore.boundSlotIndex` + comments: first-enable-wins / never-repoint write-path invariant

Matches §3.2 and the shipped guard.

---

### 5. New inaccuracies from this delta / remaining cross-file contradictions — **CONFIRMED-ACCURATE (no blocking issues)**

| Topic | Cross-file status |
|--------|-------------------|
| Capacity (three) | §3.1, SECURITY_MODEL (pool 1–3), README “two (up to three)”, CHANGELOG “3-slot pool” — aligned |
| Biometric first-enable-wins | §3.2, §6, SECURITY_MODEL, README, CHANGELOG — aligned with write guard |
| Timing parity (crypto-work only) | §3.1/§3.2 correctly scoped; create residual in §3.1/§3.2/§3.3/SECURITY_MODEL/CHANGELOG |
| Create residual | Stated; not contradicted by corrected sections |
| Not-shipped (destruction / Pucker Burn setup) | README L76–77, CHANGELOG L31–33 — consistent |
| Understatement of real guarantees? | No — A/B success indistinguishability and crypto-work parity are affirmed, not weakened |

**Non-blocking residual (not introduced as a new overclaim, not a safety-property contradiction with code):** README L67–68 still says bare “identical unlock-attempt timing” without the crypto-work vs outcome / create-residual precision of §3.1. Deeper docs own the precise contract; this is marketing compression, not a regression of the round-2 fixes.

---

### Overall verdict

**CLEAN** — all five round-2 fixes are accurate against shipped code; no blocking overclaim or new security-property inaccuracy.
