I'll review the delta against shipped code: first the range diff, then the docs and the biometric enable/GC paths they claim.Next I'll read the enable/disable/GC implementation and the auto-clear path the docs rely on.There's a mismatch between grep and the earlier file read — re-reading `reapStaleBiometricAliases` at HEAD.Checking one more detail: whether `deleteKey` outside the lock can create a missing-key wrap the docs miss, and confirming the exact shipped KDoc text.## Review: `33dcfdb..8748d8a` (shipped; doc/comment-only)

### 1. Softened crash-atomicity claim (SECURITY_MODEL + VAULT_ARCHITECTURE §3.2)
**CONFIRMED-ACCURATE**

| Sub-claim | Code |
|---|---|
| (a) prefs `save`/`clear` use async `apply()` | `BiometricUnlockStore.save` / `clear` — `.apply()` at lines 103–108, 112–114 |
| (b) Keystore delete is synchronous | `deleteAlias` → `keyStore.deleteEntry(alias)` (sync binder), `BiometricVaultKeyCipher.kt` 149–156 |
| (c) crash → **missing-key**, not wrong-key; Approach B blocks wrong-key orphans | Enable: unique `aliasId` + `newEncryptCipher` does not delete others (`BiometricVaultKeyCipher.kt` 50–63); commit under lock: never-repoint belt + `keyExists(aliasId)` then `save` (`ZitroneApp.kt` 581–589). Disable/destroy: `clear()` then `deleteAllAliasesExcept(null)` under same lock (598–602, 646–649). Kill after sync deletes, before `clear()`’s `apply()` durability → wrap on disk, alias gone = **absent** key; wrap still names its own `aliasId` (never another enable’s key). |
| (d) missing-key auto-clears | `cipherForDecrypt` → `null` if absent (`BiometricVaultKeyCipher.kt` 74–75) → unlock maps to `UNAVAILABLE` (`MainActivity.kt` 417–418) → `disableBiometricThen` → `disableBiometric()` (872–877, 598–602) |

No understatement that a **wrong-key** orphan is possible: both docs still assert concurrent/interrupted/disable-racing enable cannot leave a wrong-key binding; residual is only self-clearing missing-key.

---

### 2. Concurrency KDocs (`deleteAllAliasesExcept` / `reapStaleBiometricAliases` / cold-start GC)
**FINDING (blocking)**

| Site | Status |
|---|---|
| `BiometricVaultKeyCipher.deleteAllAliasesExcept` | **CONFIRMED-ACCURATE** — KDoc updated; callers hold `biometricWriteLock` (disable / reap / destroy); enable re-checks `keyExists` under same lock (`ZitroneApp.kt` 581–585, 598–601, 617–618, 646–648). |
| Cold-start GC comment | **CONFIRMED-ACCURATE** — updated; matches lock + `keyExists` abort. |
| `AppContainer.reapStaleBiometricAliases` **function KDoc** | **NOT fixed in `8748d8a`** |

**MEDIUM — `ZitroneApp.kt` ~606–611 (at `8748d8a`)**  
**Claim:** GC is “at a **QUIESCENT** point” and “**Never runs concurrently** with an in-flight enable, so it can never delete the live wrap’s alias.”  
**Code:** GC is `scope.launch(Dispatchers.IO) { reapStaleBiometricAliases() }` at init; enable can race. Safety is the lock + enable `keyExists` abort — which the **body** comment and the other two fixed sites already state.  
Commit message claims this KDoc was rewritten; **diff does not change it**. (Uncommitted worktree has the fix; **out of range**.)

---

### 3. New inaccuracy / invariants unchanged
**FINDING (same as §2)** — shipped tree contradicts itself in one function: KDoc = quiescent-only; body + cold-start + `deleteAllAliasesExcept` = concurrent-safe.  

**Invariants (INV-1, never-repoint, serialized mutations, `load()` hardening, legacy GC): unchanged and still hold** — delta is docs/comments only; no behavior change in `33dcfdb..8748d8a`.

---

### Verdict
**BLOCKING:** `AppContainer.reapStaleBiometricAliases` KDoc still claims quiescent-only / never-concurrent-with-enable at `8748d8a` (`ZitroneApp.kt` ~606–611); false vs code and incomplete vs the round-2 intent.
