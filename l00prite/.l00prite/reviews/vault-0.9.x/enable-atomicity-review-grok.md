I'll conduct an independent adversarial security review of the biometric enable-atomicity delta against INV-1 and the listed binding checks. Loading the protocol and full diff first.Next I'll read the full enable/unlock/GC paths and the docs' biometric claims against the code.# Adversarial security review — biometric enable atomicity (`main..9e69d58`)

## Findings

### HIGH — INV-1 false under disable/destroy ∥ in-flight enable (alias deleted, wrap still committed)

| | |
|---|---|
| **SEVERITY** | **HIGH** |
| **WHERE** | `AppContainer.disableBiometric` `ZitroneApp.kt:584-587`; `destroyVaultForAccountDeletion` `ZitroneApp.kt:620-622`; `MainActivity.startBiometricEnablePrompt` `MainActivity.kt:509-515`; `enableBiometricFromSession` save `ZitroneApp.kt:569-576`; `BiometricUnlockStore.save` `BiometricUnlockStore.kt:94-99` |
| **MECHANISM** | Enable creates `PREFIX+aliasId` first, then later seals and `save`s. Disable/account-delete do `clear()` then `deleteAllAliasesExcept(null)` with **no mutex / generation / ownership check against in-flight enables**. Any alias already created for a concurrent enable is deleted. The enable path can still `sealVaultKey` (cipher may remain usable after Keystore delete) and `save({slot, aliasId, blob})`, leaving a **present wrap whose named alias is gone**. |
| **SCENARIO** | (1) `startBiometricEnableFromSession` passes `isEnabled()==false`, `newEncryptCipher(A)` succeeds, BiometricPrompt succeeds. (2) Concurrent `disableBiometric()` / `destroyVaultForAccountDeletion()` runs `deleteAllAliasesExcept(null)` and removes `A`. (3) `enableBiometricFromSession` saves wrap bound to `A`. **INV-1 broken**: present wrap → missing key. Unlock: `cipherForDecrypt` → null → `UNAVAILABLE` → auto-clear (self-heals; not the old stuck-`FAILED` shape). Design table claim “disable ∥ enable … wrap, if present, references an existing alias ✓” is **false**. |

---

### HIGH — Cold-start GC can delete the alias the **current** wrap references (quiescence unenforced)

| | |
|---|---|
| **SEVERITY** | **HIGH** |
| **WHERE** | `AppContainer.init` GC launch `ZitroneApp.kt:723-725`; `reapStaleBiometricAliases` `ZitroneApp.kt:596-598`; `BiometricVaultKeyCipher.deleteAllAliasesExcept` `BiometricVaultKeyCipher.kt:135-142` |
| **MECHANISM** | GC is `scope.launch(Dispatchers.IO) { reap… }` at container construction — **async, unlocked**. `keep` is a **one-shot snapshot** of `boundAliasId()` then unrestricted `aliases()` filter/delete. Comments assert “quiescent / never concurrent with enable / never deletes live wrap alias”; **nothing enforces that**. Any enable that creates+persists an alias after `keep` is fixed (or that appears in the delete list when `keep==null`) can have its live alias reaped. |
| **SCENARIO** | **(d1)** Cold start, no wrap: GC `keep=null` → enable completes `newEncryptCipher(B)` + `save(wrap{B})` before/while enum → `B` ∈ toDelete → delete `B` → wrap present, key missing. **(d2)** Cold start, wrap `{A}`: GC snapshots `keep=A` → user disables (clears wrap, may delete keys) + re-enables `B` + saves `wrap{B}` → GC enumerates with stale keep `A` → deletes `B` → same orphan. Unlock self-heals via `UNAVAILABLE`, but **INV-1 does not hold** under arbitrary interleaving. |

---

### MEDIUM — Concurrent cross-slot first-enables: belt is TOCTOU; last writer can silently rebind slot

| | |
|---|---|
| **SEVERITY** | **MEDIUM** |
| **WHERE** | `startBiometricEnableFromSession` `MainActivity.kt:484`; `enableBiometricFromSession` belt `ZitroneApp.kt:566-568`; `biometricEnableAllowed` `VaultUnlockRouter.kt:172-173`; `save` `BiometricUnlockStore.kt:94-99` |
| **MECHANISM** | `isEnabled()` and `boundSlotIndex()==null` are checked without atomic compare-and-swap against the wrap write. Two first-enables can both observe “no wrap,” both seal, both `save`. The **later** `apply()` wins for slot+aliasId+blob. The belt only refuses if it **re-reads after** the first save. |
| **SCENARIO** | (a) Vault A (slot 1) and vault B (slot 2) both start enable while `isEnabled()==false`. Both pass `biometricEnableAllowed(null, ·)`. A saves `wrap{slot=1, aliasX}`; B then saves `wrap{slot=2, aliasY}`. Binding moves A→B with **no refuse**. INV-1 still holds for the final wrap (aliasY exists and sealed its blob), but **first-enable-wins / never-repoint under concurrency is not guaranteed** — contrary to the invariant table’s “second hits the belt → refused.” Same-slot double-enable: last save wins; both aliases exist; no wrong-key orphan. |

---

### MEDIUM — Docs / OQ-3 claim overstates failure surface; residual non-self-healing `FAILED` remains

| | |
|---|---|
| **SEVERITY** | **MEDIUM** |
| **WHERE** | `docs/SECURITY_MODEL.md` ~503-511; `docs/VAULT_ARCHITECTURE.md` ~114-118; unlock mapping `MainActivity.kt:865-881`; `openVaultKey` → false → `FAILED` `BiometricVaultKeyCipher.kt:106-118`, `unlockWithBiometric` `ZitroneApp.kt:534-537`, `startVaultBiometricPrompt` `MainActivity.kt:451-458` |
| **MECHANISM** | Approach B **does** eliminate the concurrent-enable **wrong-key** orphan (present key, AEAD fail from K1/K2 alias swap). Reader correctly uses `wrap.aliasId`. But unlock still maps AEAD/`unlockWithKey` null to **`FAILED` without clear**. Docs assert the *only* failures are missing/invalidated, both auto-clear, “no stuck state.” |
| **SCENARIO** | (1) Biometric-bound pool slot **blind-overwritten** by triple-entry create: unwrap succeeds, `unlockWithKey` returns null → `FAILED` forever until passphrase + manual disable. (2) Tampered wrap blob (forensics/prefs). (3) Key invalidated between `cipherForDecrypt` init and `doFinal` → null → `FAILED`, not `INVALIDATED`. Concurrent-enable orphan class is gone; **OQ-3 remains unnecessary for that class**; the shipped “only absent/invalidated / no manual recovery” wording is an **overclaim**. Docs also self-contradict (“always … existing key” vs “missing key e.g. superseded alias reaped”). |

---

### LOW — Pre-0.9.2 fixed Keystore alias never matched by GC prefix

| | |
|---|---|
| **SEVERITY** | **LOW** |
| **WHERE** | `PREFIX = "zitrone_vault_biometric_key_"` `BiometricVaultKeyCipher.kt:217`; pre-change alias was `"zitrone_vault_biometric_key"` (no trailing `_` + id) |
| **MECHANISM** | `deleteAllAliasesExcept` only deletes `startsWith(PREFIX)`. Old single alias does **not** start with `PREFIX` (underscore+hex). Missing `KEY_ALIAS_ID` → not-enabled (OK), but legacy key entry is never reaped. |
| **SCENARIO** | Upgrade/stale device with old Keystore entry: wrap ignored; biometric re-enroll creates new-style aliases; old `zitrone_vault_biometric_key` remains indefinitely (forensic/hygiene residue only; not an INV-1 wrap orphan). |

---

## Binding check outcomes (evidence only)

| Check | Result |
|---|---|
| **1 INV-1 under concurrency/crash** | **Fails** for disable/destroy∥enable and GC∥enable (HIGH). Interrupted enable after keygen before save: no wrap; existing binding intact (`newEncryptCipher` non-destructive) — OK. Two concurrent enables alone: no wrong-key orphan; wrap names own alias — OK for INV-1; cross-slot last-write belt TOCTOU — MEDIUM. |
| **2 `newEncryptCipher` non-destructive** | **Holds.** `newEncryptCipher` only `generateKey(aliasFor(aliasId))` (`BiometricVaultKeyCipher.kt:60-63`); failure `deleteKey(aliasId)` is single-alias (`MainActivity.kt:514-519`). |
| **3 Reader / orphan `FAILED`** | Concurrent-enable present-wrong-key orphan **gone**. Residual `FAILED` paths remain (MEDIUM). Missing/invalidated still auto-clear. |
| **4 Never-repoint / A-only / slot-agnostic UI** | Entrypoint `isEnabled()`, enroll predicate slot-free, belt present — **preserved in the serial case**. Concurrent cross-slot first-enables can both commit (MEDIUM). |
| **5 Format / aliasId hygiene** | **Holds** on load path: missing/malformed → not-enabled; `isValidAliasId` / `aliasFor` require; `newAliasId` 16-byte hex. Hostile prefs of valid shape cannot inject non-`PREFIX` aliases. Production enable only uses `newAliasId()`. |
| **6 Wipe / no key-material regression** | **Holds** on reviewed paths: unlock `finally wipe(vaultKey)`; enable `withVaultKey` wipe; GC/delete touch only Keystore aliases. |
| **7 Docs match code** | **Mismatch** on absolute INV-1 / “only” failure modes / disable∥enable safety (MEDIUM + HIGH mechanism). Atomic per-enable alias + non-destructive keygen claims match code. |

---

## Overall verdict

**NOT CLEAN — INV-1 does not hold under disable/account-delete or cold-start GC racing an in-flight enable (present wrap can reference a deleted alias); quiescence is comment-only.**
