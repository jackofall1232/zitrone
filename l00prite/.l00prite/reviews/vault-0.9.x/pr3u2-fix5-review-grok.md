I'll verify each round-5 claim against the shipped code on this branch, starting with the protocol docs and the exact doc delta.Next I'll verify second-vault creation and the biometric enable path against the Android code.Checking the FAILED path once more to pin the recovery overclaim.## 1. VAULT_ARCHITECTURE §7 cross-vault parity (~L307–313)
**CONFIRMED-ACCURATE.** Second vault is creatable on shipped code: `VaultUnlockRouter.decideCreate` (`CREATE_THRESHOLD = 3`) → `AppContainer.attemptPassphrase` → `VaultImageStore.attemptUnlockOrAdd(..., create)` → `UnlockOrAdd.Created` / `PassphraseOutcome.Created` (wired in `MainActivity`). Doc does **not** assert a dedicated cross-vault notification parity test exists (“should be added if not already present”). Consistent with status table (second-vault **creation** Built; no claim that parity test ships).

## 2. VAULT_ARCHITECTURE §9 (~L344–348)
**CONFIRMED-ACCURATE.** Matches code + status table: everyday runtime (0.9.1) + triple-entry second-vault creation (0.9.2); no single-slot destroy primitive (`destroy()` whole-image only); Pucker Burn setup unbuilt, wipe is fail-closed stub (`MainActivity.onBurn` → uniform failure; slot 0 reserved, store burn-aware only). No remaining “Android runtime pending” in present-tense vault status text.

## 3. SECURITY_MODEL biometric “known robustness gap” (~L503–510)
**BLOCKING OVERCLAIM (recovery half of disclosure).**

| | |
|---|---|
| **SEVERITY** | Blocking overclaim |
| **FILE+line** | `docs/SECURITY_MODEL.md` ~L506–507 |
| **Claim** | Overlapping first-enables can orphan the wrap **“until the next biometric unlock is retried and the user re-enrolls.”** |
| **What code does** | Concurrent first-enable race is real: `startBiometricEnableFromSession` has **no** single-flight; `newEncryptCipher()` deletes/replaces the shared alias. **Never-repoint / no destroy of pre-existing binding / no which-vault tell are accurate:** entry refuses when `isEnabled()`; belt is `biometricEnableAllowed` (`boundSlot == null \|\| boundSlot == sessionSlot`) in `enableBiometricFromSession`. But the **key-replaced** orphan (wrap under key A, alias now key B) makes `cipherForDecrypt` succeed and `openVaultKey`/`doFinal` fail → `VaultBiometricResult.FAILED`, which only sets `UNIFORM_FAILURE` and **does not** clear the wrap or re-offer enroll (`MainActivity` L875–878). Auto clear + re-enroll is only `INVALIDATED`/`UNAVAILABLE` (key absent / invalidated). Recovery for the concurrent-enable orphan is passphrase unlock + **manual** disable/re-enable, not “retry biometric unlock → re-enroll.” |

Never-repoint / first-enable-wins / only-one-biometric-openable remain accurate **as security invariants**; the disclosure **overstates self-heal** for the race it describes.

## 4. Remaining overclaim / contradiction in the four files
**Same blocking issue only** (item 3). No other present-tense overclaim/contradiction found on capacity (3 = pool `1..SLOT_COUNT-1` at `SLOT_COUNT=4`), biometric never-repoint belt, timing parity (fixed derivation count), create-persistence residual, fail-closed pending-delete (`Files.notExists` both markers), or not-shipped per-vault destruction / Pucker Burn setup+wipe. No stale present-tense “Android not built/pending” in vault status (CHANGELOG 0.9.0 historical note is release-scoped). CHANGELOG/README omit the enable-race disclosure but do not falsely claim self-heal.

---

**Overall: BLOCKING — `SECURITY_MODEL.md` L506–507 overclaims that the concurrent first-enable orphan self-heals via biometric unlock retry + re-enroll; key-replaced orphan yields `FAILED` with no auto-clear.**
