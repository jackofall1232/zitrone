I'll verify the 0.9.2 second-vault docs against shipped code only: pull the delta, read full surrounding prose, then check each corrected claim against the cited implementation.Next I'll read the biometric, unlock, placement, and status-table code paths and the full surrounding doc paragraphs for claims 1–6.Reading the biometric belt, unlock/create path, placement helpers, and residual §3.2 language that may still say “A only.”## Round-2 documentation accuracy review (`c1748ea..d2ad583`)

Verified against shipped Android code on this branch (not the prose’s own reasoning).

---

### 1. Biometric first-enable-wins — **CONFIRMED-ACCURATE**

| Doc claim | Code |
|---|---|
| Exactly one wrap at a time | `BiometricUnlockStore` single `{slot, blob}`; `isEnabled()` ⇔ `load() != null` |
| Never repointed **while it exists** | `biometricEnableAllowed(bound, session) = bound == null \|\| bound == session` (`VaultUnlockRouter` ~172–173); `enableBiometricFromSession` fail-closes on false (`ZitroneApp` ~565–567) |
| First-enable-wins when no wrap | `boundSlot == null` allows any session slot; entrypoint `isEnabled()` refuses enable while a wrap exists (`MainActivity` ~484) |
| Disable frees binding | `disableBiometric()` → `biometricStore.clear()` + key delete (`ZitroneApp` ~576–578); test: clear then enable slot 2 allowed (`BiometricUnlockStoreTest` ~142–145) |
| Only one biometric-openable; others passphrase-only | Single wrap; unlock loads that slot only |
| Enrollment UI slot-agnostic | `biometricEnrollOffered(offer, session, alreadyEnabled)` — no slot param; gated by global `isEnabled()` (`MainActivity` ~1083–1089) |

§3.2 / §3.3-adjacent limits / banner / `SECURITY_MODEL` biometric bullet / CHANGELOG / README first-enable wording agree. “Never repointed while it exists” is **not** understated: cross-slot enable is refused while a valid wrap is present.

---

### 2. Create-vs-unlock timing — **CONFIRMED-ACCURATE**

- **Shared success UI:** `PassphraseOutcome.Unlocked` and `Created` both call `onUnlockSuccess()` (`MainActivity` ~800).
- **Shared KDF budget:** every `attemptUnlockOrAdd` path does `tryPassphrase` (full `SLOT_COUNT` Argon2id) + always `sealSlotSelfVerifying` (+1 Argon2id) before the `when` (`VaultImageStore` ~662–679).
- **Create-only residual (success path only):** payload seal → self-verify open/compare → outer `aeadEncrypt` → `atomicWrite` → dir-sync durability (`~734–779`). Reject and unlock do not persist.
- Residual is **not** claimed for reject/unlock. Correctly scoped.

---

### 3. Pending-delete timing — **CONFIRMED-ACCURATE**

Create + markers present (`create` branch, `!markersAbsent`): same throwaway `sealPayload` + `Rejected` as plain reject (`~724–732` vs `~783–790`).  
UI: both → `PassphraseOutcome.Rejected` → `UNIFORM_FAILURE` (`MainActivity` ~816–820).  
Heavy budget matches: sweep + candidate seal + one 256 KiB payload GCM.  
Extra work only on pending-delete create: two `Files.notExists` checks (`~724–726`) — docs correctly **do not** claim wall-clock identity for that.

---

### 4. Placement pseudorandom / mod-3 bias — **CONFIRMED-ACCURATE**

```49:50:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)
```

- Pool = `1 until SLOT_COUNT` → slots **1..3** (`VAULT_SLOT_RANGE`, `SLOT_COUNT = 4`).
- `randomIndex`: CSPRNG u32 **mod n**, no rejection sampling (~254–261) → negligible mod-3 bias.
- No occupancy check in create path → **~1/3** overwrite chance for any given existing vault; full pool → **certain** overwrite. Matches CHANGELOG / `SECURITY_MODEL`.

---

### 5. “Up to three live vaults” — **PARTIALLY FIXED; residual dual-vault framing**

**Confirmed correct (core claim):**  
`SLOT_COUNT = 4`, `BURN_SLOT_INDEX = 0`, vault pool size 3 → up to **three** live vaults. `SECURITY_MODEL` banner + blind-overwrite bullet match. No remaining “expandable to four.”

**Residual inconsistencies (not introduced as “four”, but still false/understated vs code):**

| SEVERITY | FILE + location | Claim | Code | Correct wording |
|---|---|---|---|---|
| **MEDIUM** | `docs/VAULT_ARCHITECTURE.md` §3.1 (~70–71) | “structural capacity for **two** vaults” | Pool is 3 slots; triple-entry can create a 3rd vault the same way | capacity for **up to three** live vaults (pool `1..SLOT_COUNT-1`) |
| **MEDIUM** | `docs/VAULT_ARCHITECTURE.md` §3.2 (~101–106) | passphrase checked against “*both* slots” A and B only | `tryPassphrase` over **all** `SLOT_COUNT` slots; matches on any of 1..3 unlock | every slot / any vault-pool match |
| **LOW** | `README.md` (~67) | “**two** separate vaults behind two passphrases” | same 3-slot pool | “two or more (up to three on Android)” — or point at `SECURITY_MODEL` |

Not a safety **overclaim** (understates capacity / dual-vault design leftover). No residual “four.”

---

### 6. Not-shipped honesty — **CONFIRMED-ACCURATE**

- Per-vault destroy: **not** present; only whole-image `VaultImageStore.destroy()`; docs + CHANGELOG present-tense “NOT shipped / only whole-image.”
- Pucker Burn: slot 0 reserved / burn-aware (`UnlockOrAdd.Burn`); setup/wipe **not** user-settable — stated as not shipped; burn permanence not present-tense as working.

---

### 7. NEW inaccuracies from this delta / internal contradictions — **No blocking overclaim; one non-blocking residual class**

| Check | Result |
|---|---|
| Biometric §3.2 vs `SECURITY_MODEL` vs CHANGELOG/README | Aligned on first-enable-wins + never-repointed-while-exists + disable-then-rebind |
| Create residual vs match-vs-reject parity | No clash: match/reject KDF parity kept; create disk residual separately scoped |
| Understatement of never-repointed? | No — “while it exists” is exact; disable→clear is real |
| Timing residual overclaim? | No — residual limited to successful create (and named marker-stat residual for pending-delete) |
| New contradiction | Only residual **two-vs-three** framing (#5): `SECURITY_MODEL` “three live vaults” vs VAULT_ARCH §3.1 “capacity for two” / README “two vaults” |

§6 still says “biometric unlock only ever opens **A**” after §3.2 redefines “A” as the bound **role**, not a fixed slot — consistent if read with §3.2; not a code contradiction.

---

### Overall verdict

**CLEAN** — no blocking (HIGH) security-property overclaim remains; corrected biometric, create-timing, pending-delete, placement, and not-shipped claims match shipped code. Residual non-blocking issue: dual-vault “two” capacity/framing still present in `VAULT_ARCHITECTURE.md` §3.1/§3.2 and README headline vs `SECURITY_MODEL`’s accurate three-slot pool.
