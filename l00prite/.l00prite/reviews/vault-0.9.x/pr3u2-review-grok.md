I'll review the docs delta against shipped code only—starting with the protocol context, the `main..c1748ea` diff, and the implementation paths those claims name.Next I'll pull the full docs diff and locate the vault/biometric implementation paths those claims depend on.Pulling the implementation files that back each claimed property so we can check them against the docs line by line.Checking one more nuance on create-vs-unlock timing residual and whether pre-existing docs already disclose it.# Docs accuracy review — `main..c1748ea` (PR-3 Unit 2)

Verified against shipped code on `main` (PR-1 `attemptUnlockOrAdd`, PR-2 triple-entry router, PR-3 Unit 1 biometric A-only). Docs-only delta; no file edits.

---

## 1. Triple-entry

### CONFIRMED-ACCURATE (core ceremony)

| Claim | Code |
| --- | --- |
| Same passphrase ×3 consecutive → create | `VaultUnlockRouter.CREATE_THRESHOLD = 3`; `decideCreate` advances on same-hash, returns true at threshold (`VaultUnlockRouter.kt` 88–109, 195–196) |
| Differing entry resets streak | else-branch sets `candidateCount = 1` (`VaultUnlockRouter.kt` 104–108) |
| No stored attempt count | RAM-only `candidateHash` / `candidateCount`; wiped on `resetCandidate` (`VaultUnlockRouter.kt` 59–67, 119–124) |
| Uninterrupted via backgrounding | `VaultLockManager.onStop` always calls `resetRitual` → `unlockRouter.resetCandidate()` (`VaultLockManager.kt` 107–111; `ZitroneApp.kt` 351–356) |
| Process death resets | RAM-only state; process death clears it (no persistence) |
| Match wins over create | store prefers vault match when present (`VaultImageStore.kt` 617–619, 694–707); `attemptPassphrase` runs `decideCreate` then store (`ZitroneApp.kt` 447–451) |
| UI: create ≡ unlock | `PassphraseOutcome.Unlocked, Created → onUnlockSuccess()` (`MainActivity.kt` 800) |

### FINDING — **HIGH (blocking)**

- **FILE+line:** `docs/SECURITY_MODEL.md` ~472–473  
- **Claim:** “a creating third entry is indistinguishable, in **behaviour and timing**, from an ordinary unlock.”  
- **Code:** Successful create is **not** timing-identical to unlock. Every outcome shares the sweep + candidate seal; **only** durable `Created` adds self-verify open + outer AEAD + atomic write + dir-fsync — the store’s own documented create-persist residual (`VaultImageStore.attemptUnlockOrAdd` kdoc ~610–630, create branch ~734–779). Code states that residual “already reveal[s] that a create happened.”  
- **UI behaviour:** same (`onUnlockSuccess`) — accurate.  
- **Correct wording:** UI/session outcome identical; KDF work matched; successful create has an accepted post-outcome disk-persist residual (not a KDF-level distinguisher), already accepted in store comments. Do not claim full timing indistinguishability with unlock without that residual.

### FINDING — **LOW**

- **FILE+line:** `docs/VAULT_ARCHITECTURE.md` ~116–117  
- **Claim:** “backgrounding …, **the lock cycle**, or process death” resets the streak.  
- **Code:** Explicit reset is `onStop` (background), match/create/burn/cancel/publish, and process death (RAM). `UnlockController.lock()` does not call `resetCandidate`. Ritual only runs at the lock screen (no session).  
- **Correct wording:** “backgrounding (`VaultLockManager.onStop`), process death (RAM-only state), or any successful session publish.”

---

## 2. Blind overwrite quantification

### CONFIRMED-ACCURATE

| Claim | Code |
| --- | --- |
| `SLOT_COUNT = 4` | `KeySlot.kt` 38 |
| Pool `1..SLOT_COUNT-1` (3 slots); slot 0 never a target | `BURN_SLOT_INDEX = 0`; `VAULT_SLOT_RANGE = 1 until SLOT_COUNT`; `randomVaultSlotIndex` (`VaultSlots.kt` 36–50) |
| Uniform over those 3 | `VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)` |
| No occupancy check | create uses `randomVaultSlotIndex` then overwrites that index (`VaultImageStore.kt` 678, 756–758); empty occupied set (`~632–635`) |
| No full-pool refuse | no free-slot / full-pool branch; full pool still creates |
| ~1/3 chance to overwrite a given vault; certain overwrite if all 3 occupied | uniform over 3 slots → 1/3 for any fixed occupied slot; if all 3 live, any draw hits a vault |

---

## 3. Triple-entry coercion consequence

### CONFIRMED-ACCURATE

| Claim | Code |
| --- | --- |
| Same wrong passphrase ×3 creates | `decideCreate` same-hash streak → `create=true` on 3rd; no match → create branch |
| Different wrong guesses never create | different hash → `candidateCount = 1` |
| Created vault empty | `genesis = VaultStateCodec.encode(VaultState.empty())` (`ZitroneApp.kt` 448); `VaultState.empty()` empty signal/roster/auth (`VaultState.kt` 96–102) |

---

## 4. Biometric A-only

### CONFIRMED-ACCURATE (hard properties)

| Claim | Code |
| --- | --- |
| One wrap | single prefs blob (`BiometricUnlockStore`) |
| Never repointed (write path) | `enableBiometricFromSession`: `biometricEnableAllowed(boundSlotIndex(), session.slotIndex)` fail-closed (`ZitroneApp.kt` 551–567); `biometricEnableAllowed` = null or same slot (`VaultUnlockRouter.kt` 172–173) |
| Entrypoint gate | `isEnabled()` refuses before keygen (`MainActivity.kt` 484) |
| Enroll UI slot-agnostic | `biometricEnrollOffered` takes no slot (`VaultUnlockRouter.kt` 158–162; `MainActivity.kt` 1083–1089) |
| Biometric opens bound vault only | wrap stores `slotIndex`; `unlockWithBiometric` / `unlockWithKey(vaultKey, wrap.slotIndex)` |

### FINDING — **MEDIUM**

- **FILE+line:** `docs/SECURITY_MODEL.md` ~476–477; `CHANGELOG.md` ~26  
- **Claim:** opens “the everyday vault”; “biometric unlocks only the everyday vault, so a second vault is passphrase-only.”  
- **Code:** first-enable-wins on **whatever session** first enables (`biometricEnableAllowed(null, sessionSlot)`). Settings toggle can enable from any live session (`MainActivity.kt` 891–893 → `startBiometricEnableFromSession`). Skip biometric at onboarding, create vault B, enable from B → wrap binds to B; A is passphrase-only.  
- **Correct wording:** “always opens the vault that first enabled biometric (first-enable-wins); the other vault(s) are passphrase-only. Never repointed.”

---

## 5. Fail-closed on pending delete

### CONFIRMED-ACCURATE

- Markers: `vault.delete-intent`, `vault.delete-confirmed` (`VaultImageStore.kt` 1275–1282).  
- Create branch: `markersAbsent = Files.notExists(deleteIntent) && Files.notExists(serverDeleted)`; if false → throwaway payload GCM + `UnlockOrAdd.Rejected` (no write, no throw) (`VaultImageStore.kt` 724–732).  
- Mapped to `PassphraseOutcome.Rejected` → uniform failure UI (`ZitroneApp.kt` 498–501; `MainActivity.kt` 816–821).  
- Same single payload-GCM budget as ordinary reject (store kdoc ~614–615, 727–731 vs 784–790).

---

## 6. NOT-shipped claims honesty

### CONFIRMED-ACCURATE

| Topic | Docs | Code |
| --- | --- | --- |
| Per-vault destroy not shipped | status tables / §3.4 banner | `destroy()` wipes DEK, deletes whole `vault.bin` + `vault.dek` (`VaultImageStore.kt` 1056–1114) — whole-image only |
| Pucker Burn setup/wipe not shipped | explicit “NOT built / not user-settable / fail-closed stub” | slot 0 reserved (`VaultSlots.kt` 36–39); store returns `Burn` (`VaultImageStore.kt` 682–691); wipe is stub: `onBurn` → `UNIFORM_FAILURE` (`MainActivity.kt` 778–785) |
| Burn permanence not present-tense | no “burn permanently wipes” as shipped | — |

---

## 7. Other present-tense / consistency issues

### FINDING — **MEDIUM** (internal contradiction with untouched design text)

- **FILE+line:** `docs/SECURITY_MODEL.md` ~419 area (untouched): “Two (**expandable to four**) completely separate encrypted vaults…”  
- **Conflict with delta:** Android creation pool is **3** vault slots (`1..SLOT_COUNT-1` at `SLOT_COUNT=4`, slot 0 burn). Max live vaults under shipped placement is 3, not 4.  
- **Correct wording (status / Android):** “up to three vaults in the pool (slots 1–3); slot 0 reserved for burn.” Align or qualify the “expandable to four” line.

### FINDING — **LOW**

- **FILE+line:** `docs/VAULT_ARCHITECTURE.md` status table / §3.3 ~114: “create and open **slot B**”  
- **Code:** placement is random pool index, not a fixed “B” index.  
- **Correct wording:** “creates vault B (blind-placed in a random pool slot).”

### No additional timing-parity breakage found

- Untouched `tryPassphrase` no-early-exit claim still matches code (`VaultSlots.kt` 211–240).  
- Fail-closed-vs-wrong-passphrase timing claim is accurate (item 5).  
- Only the **create-vs-unlock** timing claim (item 1) is the new overclaim.

### Cross-file consistency of shipped/not-shipped

CHANGELOG, README, `SECURITY_MODEL`, `VAULT_ARCHITECTURE` agree: second vault creatable; per-vault destroy and Pucker Burn setup/wipe not shipped. No present-tense burn-permanence claim.

---

## Overall verdict

**BLOCKING — `docs/SECURITY_MODEL.md` claims create-vs-unlock full timing indistinguishability; shipped `attemptUnlockOrAdd` documents a create-only disk-persist residual that breaks that claim.**

Secondary non-blocking: biometric “always everyday vault” (MEDIUM); “expandable to four” vs 3-slot pool (MEDIUM); “lock cycle” / “slot B” wording (LOW).
