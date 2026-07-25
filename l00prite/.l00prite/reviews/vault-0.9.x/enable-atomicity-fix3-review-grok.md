I'll review the KDoc delta against the shipped biometric code and docs, starting with the protocol files and the exact diff.Reviewing the biometric surface and docs against the new KDoc and the stated invariants.Checking docs §3.2 and the MainActivity unlock/enable paths against the stated invariants.## 1. `reapStaleBiometricAliases` KDoc — CONFIRMED-ACCURATE

At HEAD (`eb979db`), KDoc no longer claims quiescence / non-concurrency. It correctly states concurrency safety via lock + `keyExists` abort.

Code:

```605:617:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
    /**
     * Reap stale biometric Keystore aliases (called once at cold-start container init, off-main):
     * ... SAFE to run concurrently with an in-flight enable: under [biometricWriteLock]
     * it reads the live wrap's alias and deletes the others atomically, so a concurrent enable either
     * has its just-saved wrap's alias kept (it is `keep`) or aborts at its own `keyExists` re-check
     * under the same lock — it can never delete the alias the current wrap references (INV-1).
     */
    fun reapStaleBiometricAliases() {
        synchronized(biometricWriteLock) {
            biometricCipher.deleteAllAliasesExcept(biometricStore.boundAliasId())
        }
    }
```

Supporting shipped behavior:

- Cold-start async: `scope.launch(Dispatchers.IO) { runCatching { reapStaleBiometricAliases() } }` (`ZitroneApp.kt:749–752`)
- Enable-commit under same lock + `keyExists` abort (`ZitroneApp.kt:581–589`)
- Lock serializes enable/disable/account-delete/GC (`ZitroneApp.kt:167–174`)

## 2. Surface sweep for stale concurrent/quiescence claims — CONFIRMED-ACCURATE

Repo-wide: **no** remaining `quiescent` / `QUIESCENT` / `never concurrent` / `Never runs concurrent` matches under `apps/android` or `docs/`.

Checked and consistent:

| Surface | Concurrency story |
|---|---|
| `BiometricVaultKeyCipher.kt:127–134` | GC safe under concurrent enable (callers hold lock; enable re-checks `keyExists`) |
| `BiometricUnlockStore.kt` | no quiescence claim |
| `ZitroneApp.kt` biometric methods + init | lock + async GC; no non-concurrency claim |
| `MainActivity.kt` enable path | INV-1 / non-destructive alias create (`:479–487`) |
| `docs/SECURITY_MODEL.md:503–522` | atomic enable = unique alias + lock + alias-exists; concurrent races cannot leave **wrong-key** orphan; process-kill missing-key window acknowledged |
| `docs/VAULT_ARCHITECTURE.md` §3.2 (`:114–122`) | same model; no quiescence |

“Enable is atomic” in docs is **not** absolute prefs+Keystore atomicity; it is the lock/`keyExists`/unique-alias INV-1 claim, with the missing-key crash window explicit.

## 3. Behavioral invariants + doc match — CONFIRMED-ACCURATE

| Invariant | Code | Docs |
|---|---|---|
| INV-1 no wrong-key orphan under concurrency | unique alias `newEncryptCipher` (`BiometricVaultKeyCipher.kt:51–62`); non-destructive; GC keeps `boundAliasId` | SECURITY_MODEL / VAULT §3.2 |
| Serialized wrap mutations | `biometricWriteLock` on enable-commit / disable / destroy / GC | same |
| `keyExists` commit abort | `ZitroneApp.kt:585` | same |
| never-repoint | `biometricEnableAllowed` under lock (`:582–583`); `isEnabled()` gate (`MainActivity.kt:484`) | first-enable-wins / never-repointed |
| missing/invalidated → auto-clear | `cipherForDecrypt` null / `KeyPermanentlyInvalidatedException` → `UNAVAILABLE`/`INVALIDATED` → `disableBiometricThen` (`MainActivity.kt:417–421`, `872–878`) | SECURITY_MODEL:512–513; VAULT:120 |
| corrupted/tampered/blind-overwrite → FAILED, not auto-cleared | `openVaultKey` null / unlock false → `FAILED` only (`MainActivity.kt:451–458`, `879–881`) | SECURITY_MODEL:513–519; VAULT:120–122 |
| `load()` hardening | try/catch; slot ∈ `VAULT_SLOT_RANGE`; missing/malformed `aliasId` → null (`BiometricUnlockStore.kt:39–68`) | matches “missing/invalid” as not-enabled |
| legacy GC | `LEGACY_ALIAS` included in reap (`BiometricVaultKeyCipher.kt:139–142`, `223–224`) | upgrade residue cleaned |

## Verdict

**CLEAN**
