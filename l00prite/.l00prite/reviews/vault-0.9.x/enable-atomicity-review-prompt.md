You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes.

## Product & threat model
Zitrone: production Signal-Protocol E2E messenger with a plausible-deniability second vault. Adversary: PHYSICAL DEVICE + FORENSICS + many forced/observed unlocks. Assume crash / process-death / rotation and ARBITRARY INTERLEAVING at any instruction. **Guilty-until-proven.** This change makes biometric ENABLE atomic to eliminate a previously-disclosed orphan-wrap gap (Approach B: per-enable unique Keystore alias; the wrap records which alias sealed it).

## Delta to review
`main..9e69d58` on branch `feat/0.9.2-vault-enable-atomicity` (/root/zitrone). `git diff main..9e69d58`. Read the FULL functions:
- `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/BiometricVaultKeyCipher.kt` — `newEncryptCipher(aliasId)` (NON-destructive — creates only its own alias), `cipherForDecrypt(aliasId, nonce)`, `deleteKey(aliasId)`, `deleteAllAliasesExcept(keep)`, `newAliasId`/`isValidAliasId`/`aliasFor`, `generate`/`existingKey`; `BiometricWrappedKey{slotIndex, aliasId, blob}`.
- `apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt` — `load`/`save`/`clear`/`boundSlotIndex`/`boundAliasId` (new `KEY_ALIAS_ID`; missing/malformed aliasId → not-enabled).
- `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` — `enableBiometricFromSession(…, aliasId)`, `disableBiometric`, `reapStaleBiometricAliases` + its cold-start `init` launch, `unlockWithBiometric`, `destroyVaultForAccountDeletion`.
- `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt` — `startBiometricEnableFromSession` (fresh aliasId → `newEncryptCipher(aliasId)`), `startBiometricEnablePrompt(…, aliasId)`, `startVaultBiometricUnlock` (`cipherForDecrypt(wrap.aliasId, …)`).
- Tests: `BiometricUnlockStoreTest.kt`.
- Docs (must MATCH shipped code): `docs/SECURITY_MODEL.md` + `docs/VAULT_ARCHITECTURE.md` §3.2 biometric sections.

## The CORE invariant this change claims (INV-1)
**The persisted wrap, when present, always references an existing Keystore alias whose key sealed its blob** — so no orphan (present-wrap → unopenable) can form under concurrent/interrupted/disable-parallel enable.

## Verify specifically (binding)
1. **INV-1 holds under concurrency/crash.** Prove no interleaving of two enables, an interrupted enable, or disable-∥-enable can leave a persisted wrap that references a MISSING or WRONG-KEY alias. Cover: (a) two concurrent first-enables (same slot; different slots — where the belt refuses the second); (b) an enable interrupted after `newEncryptCipher` but before `save`; (c) `disableBiometric`/account-delete racing an in-flight enable; (d) the cold-start `reapStaleBiometricAliases` GC racing an enable — can GC ever delete the alias the current wrap references? Confirm GC runs only at quiescent points and keeps `boundAliasId()`.
2. **newEncryptCipher is truly non-destructive.** Confirm it creates ONLY `PREFIX+aliasId` and deletes nothing else, so an interrupted/concurrent enable cannot destroy an EXISTING binding (the round-4 MEDIUM this closes). Confirm `deleteKey(aliasId)` on enable failure deletes only THIS enable's alias, never a live binding's.
3. **Reader correctness.** `cipherForDecrypt(wrap.aliasId, …)` uses the wrap's OWN alias, so a present key always opens it. Confirm the only unlock failures are absent key (→ UNAVAILABLE) or invalidated (→ INVALIDATED), both of which auto-clear + re-offer (`MainActivity` result mapping) — i.e. the non-self-healing `FAILED` orphan is genuinely GONE, and OQ-3's clear-on-AEAD-fail is correctly UNNECESSARY (a present-key AEAD failure can no longer be a recoverable orphan).
4. **Never-repoint / A-only / slot-agnostic UI preserved.** The Unit-1 guards (`isEnabled()` gate, `biometricEnableAllowed` belt, slot-free enroll predicate) still hold; enabling from a second vault is still governed the same way; no A/B distinguisher introduced.
5. **Format change + aliasId hygiene.** `aliasId` is validated to a fixed hex shape before it EVER becomes a Keystore alias (`aliasFor` require / `isValidAliasId`) — a tampered prefs `aliasId` cannot inject an arbitrary alias or crash. A missing aliasId (pre-0.9.2 wrap) reads as not-enabled (graceful re-enroll, no migration). `newAliasId` entropy (16 bytes) makes collision negligible. Any way a malformed/hostile `KEY_ALIAS_ID` reaches Keystore or throws uncaught?
6. **No new key-material / wipe regression.** vaultKey wipe on the unlock path, seal/`withVaultKey` wipe on enable, unchanged. GC/enable never leak or double-free.
7. **Docs match code.** Do the updated SECURITY_MODEL + VAULT_ARCHITECTURE biometric claims exactly match this delta's behavior (enable atomic; no orphan; failures absent/invalidated → auto-clear; no manual recovery)? Any residual overclaim/understatement.

## Output
Structured findings (SEVERITY, FILE+FUNCTION+line, MECHANISM, concrete SCENARIO). One-line overall verdict (CLEAN or the specific blocking finding). Report ONLY.
