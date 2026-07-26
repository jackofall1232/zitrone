I'll perform an independent adversarial security review of the biometric enable-atomicity delta. Reading protocol context and the full functions under review.Reading the full functions under review for interleaving analysis.Checking one crash-window detail and whether unlock paths match the FAILED/auto-clear docs.# Adversarial security review — round 2 (Approach B + `biometricWriteLock`)

**Range:** `9e69d58..33dcfdb` · `feat/0.9.2-vault-enable-atomicity`  
**Scope:** biometric enable-atomicity serialization; INV-1 under arbitrary interleaving / crash.

---

## Round-1 findings — disposition

| # | Severity | Claim | Status |
|---|----------|--------|--------|
| R1-H1 | HIGH | disable/account-delete ∥ enable orphan wrap | **CLOSED** |
| R1-H2 | HIGH | cold-start GC ∥ enable orphan wrap | **CLOSED** |
| R1-M1 | MEDIUM | cross-slot first-enable belt TOCTOU | **CLOSED** |
| R1-M2 | MEDIUM | docs overclaim (all failures auto-clear) | **CLOSED** |
| R1-L1 | LOW | `load` ClassCastException crash | **CLOSED** |
| R1-L2 | LOW | legacy alias never reaped | **CLOSED** |

None reopened by this delta.

---

## INV-1 exploit re-runs (locked code)

Mutations that touch wrap/alias set: `enableBiometricFromSession` commit, `disableBiometric`, `destroyVaultForAccountDeletion` biometric block, `reapStaleBiometricAliases` — all take `biometricWriteLock` (`ZitroneApp.kt` ~174, 581–590, 598–602, 617–619, 646–649). Sole production `save` is inside that critical section after belt + `keyExists`.

| | Scenario | Outcome under lock |
|---|----------|-------------------|
| **(a)** | disable deletes alias X, then enable commits | Commit sees `keyExists(X)==false` → abort; no `save`; no orphan wrap |
| **(b)** | GC deletes X (created, not yet bound), then enable commits | Same abort; no persist |
| **(c)** | enable saves wrap{X}, then GC/disable | GC `keep = boundAliasId()==X`; disable clears wrap then deletes all aliases under one lock — no wrap left pointing at a missing key from these paths |
| **(d)** | two cross-slot first-enables | Later commit’s belt sees earlier wrap → refuse; no second binding |

**Seal-outside-lock:** blob is sealed before the monitor; commit re-checks belt + `keyExists(aliasId)` under the lock. Stale seal after concurrent reap/disable → abort, local blob dropped, no persistence. Same-slot concurrent re-enable last-writer-wins still binds an existing alias (INV-1). Cross-slot cannot both commit.

**No remaining interleaving** (among app threads that use these APIs) leaves a **saved** wrap that references a missing/wrong alias, or two cross-slot wraps/rebinds.

---

## Verify items (binding)

### 1. INV-1 under all interleavings
**Holds** for concurrent enable/disable/GC/account-delete as coded. See table above.

### 2. Lock-order / deadlock / reentrancy
- `withVaultKey` takes `VaultSession.stateLock` only to copy the key, then **releases** before the block; `biometricWriteLock` is acquired only inside the block → order is never `biometricWriteLock → stateLock`.
- Nothing under `biometricWriteLock` re-enters enable/disable/GC or takes another app lock in reverse order.
- Monitor is cross-thread-correct (main enable `onSuccess` vs IO GC/disable).
- **Non-security:** holding the monitor across Keystore `keyExists` / `deleteAllAliasesExcept` on main can stall if IO holds it for multi-alias delete (ANR risk only).

### 3. `keyExists` abort path
- Returns `false`, no `save`; sealed blob is stack-local only.
- `MainActivity` `onSuccess`: `if (!ok) deleteKey(aliasId)` — idempotent if already reaped.
- `withVaultKey` `finally` still `wipe`s the vault-key copy on abort → **no plaintext key-material regression**.

### 4. Docs vs code
- `SECURITY_MODEL.md` / `VAULT_ARCHITECTURE.md` §3.2 match: enable unique alias + serialized mutations + `keyExists` before persist; missing/invalidated → auto-clear (`INVALIDATED`/`UNAVAILABLE` → `disableBiometric`); corrupted blob / invalidation-between-init-and-use / blind-overwritten slot → `FAILED` → passphrase, wrap left (deliberate); never-repoint + slot-agnostic UI preserved.
- No security-model self-contradiction found.
- **Residual (non-blocking):** `BiometricVaultKeyCipher.deleteAllAliasesExcept` / `reapStaleBiometricAliases` KDocs still say “quiescent only / never concurrent with enable” — false under the new design (concurrency is safe **because** of the lock). Misleading for maintainers, not a behavioral defect.

### 5. `load()` try/catch + legacy GC
- `load()` wraps `loadUnsafe()`; any `Exception` from typed prefs getters (hostile type on slot/aliasId/blob string) → `null` (not enabled). Errors still propagate.
- Fail-closed; does not mask Keystore/crypto bugs (prefs-only).
- `LEGACY_ALIAS = "zitrone_vault_biometric_key"` is only in the delete filter; `keep` is always `PREFIX + hexId` or null → legacy is **never** a keep target. New-format `PREFIX*` path unchanged.

### 6. MainActivity enable path
Unchanged this round: `newAliasId()` → `newEncryptCipher(aliasId)` → `enableBiometricFromSession(…, aliasId)` → on failure/error `deleteKey(aliasId)` only. Confirmed.

---

## New findings this round

**None at Critical / High / Medium.**

No INV-1 break, never-repoint break, A/B tell, key-material regression, or docs/code security mismatch introduced by the lock + `keyExists` abort + legacy GC + `load` hardening.

*(Pre-existing multi-resource crash window: prefs `apply()` + Keystore delete are not one durable transaction; a kill mid-disable can leave disk wrap with keys already gone until next biometric attempt auto-clears. Not introduced or worsened by this delta; self-heals via `UNAVAILABLE` path. Not scored as a round-2 blocker.)*

---

## Verdict

**CLEAN** — all six round-1 findings closed; no new Critical/High/Medium; INV-1 holds under concurrent interleaving for Approach B + `biometricWriteLock`.
