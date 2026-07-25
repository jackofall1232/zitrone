I'll perform an independent security review of commit `645b8a8` — starting with the project protocol and the full commit diff, then verifying every claim against source.Pulling the full diff and reading the changed production files carefully.Reading the production wipe, marker, unlock, and burn wiring in full.Checking one more edge case on the disk-truth path and whether `destroy` tests miss the new intermediate state:# Security Review: `645b8a8` — Pucker Burn Unit W (wipe mechanism)

Independent review against source only. Claims in the commit message were treated as hypotheses and checked in code.

---

## Findings

### 1. HIGH — Burn “success” ignores obliterate failure; non-durable wipe can present as success

**Where:** `MainActivity.kt:817–822`

```817:822:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
            val burned = try {
                withContext(Dispatchers.IO) {
                    runCatching { container.burnVault() }
                    // DISK TRUTH, not the call's return value — the same standard the account-delete
                    // path uses. The burn succeeded iff the image is actually gone.
                    !container.hasVault()
```

**What is wrong:** `runCatching` discards any throw from `burnVault()` / `obliterateForBurn()`. Success is **only** `!hasVault()` (`binFile.exists()` false).

`obliterateLocked` intentionally throws **after** unlinks when durability or marker retire fails (`VaultImageStore.kt:1139–1158`). In those cases the namespace may already show no `vault.bin`, so:

| Failure point | Primitive | UI |
|---|---|---|
| `dirSync != DURABLE` after unlinks | throws `DestroyFailed` | `hasVault()==false` → **Onboarding “success”** |
| `clearBothMarkersDurably()` fails after durable unlinks | throws `DestroyFailed` | same **false success** |

Account-delete does **not** have this hole: success is `!hasVault() && !serverDeleteConfirmed()` (`MainActivity.kt:647`, `1113–1120`). The confirmed marker still present forces `DeleteIncomplete`. Burn has no second durable signal, so the comment “same standard as account-delete” is false.

**Why it matters:** Project’s own round-8 invariant is that a non-durable unlink can be resurrected by journal replay after a crash/power loss. Under duress, presenting ordinary onboarding while a crash can restore the vault is a fail-open presentation.

`BurnObliterateTest` only asserts the primitive throws on `NOT_DURABLE` (`BurnObliterateTest.kt:204–209`); nothing asserts the UI treats that as failure.

**Concrete fix:**

```kotlin
val result = runCatching { container.burnVault() }
val burned = result.isSuccess && !container.hasVault()
```

Optionally also require markers proven absent (`Files.notExists` on intent + confirmed). Do **not** treat “bin gone, call threw” as success.

---

### 2. HIGH — `reconcileOrphanedBurnMarkers` uses fail-open `File.exists()` before clearing markers (B1 risk)

**Where:** `VaultImageStore.kt:1204–1209`

```1204:1209:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
    fun reconcileOrphanedBurnMarkers(): Boolean =
        imageLock.withLock {
            if (binFile.exists()) return@withLock false
            if (!Files.notExists(serverDeletedFile.toPath())) return@withLock false
            if (Files.notExists(deleteIntentFile.toPath())) return@withLock false
            runCatching { clearBothMarkersDurably() }.getOrDefault(false)
        }
```

**What is wrong:** Marker readers/writers elsewhere refuse to trust `File.exists()==false` (e.g. `hasDeleteIntentMarker` / `clearBothMarkersDurably` use `Files.notExists`). This **new** marker writer uses weak `binFile.exists()` as the sole proof that the image is gone before calling `clearBothMarkersDurably()`.

If `binFile.exists()` is false while the image is still present (I/O/stat fault — the exact failure mode D2c hardened against), and `vault.delete-intent` is present (genuine pending D2c delete, routed to the lock screen by design), this clears the intent over a **live** vault → B1: markers say nothing-pending over live state; post-unlock D2c reconcile (`MainActivity.kt:1137–1140`) never fires.

Confirmed is correctly fail-closed (`!Files.notExists(serverDeletedFile)`). Only the **image-absent** gate is wrong.

**Concrete fix:**

```kotlin
// Prove image absent (and ideally dek/temps too); present OR indeterminate → do not clear.
if (!Files.notExists(binFile.toPath())) return@withLock false
if (!Files.notExists(dekFile.toPath())) return@withLock false
// ... same confirmed/intent checks ...
```

---

### 3. HIGH — Plaintext cache clear is fail-open and non-gating on “successful” burn

**Where:**  
- `clearCacheDir` `ZitroneApp.kt:1127–1130`  
- `wipeAppLocalStateForBurn` `ZitroneApp.kt:711–716`  
- `burnVault` `ZitroneApp.kt:680–688`

```1127:1130:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
internal fun clearCacheDir(cacheDir: java.io.File?): Boolean {
    if (cacheDir == null || !cacheDir.exists()) return true
    cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    return cacheDir.listFiles()?.isEmpty() ?: true
}
```

**What is wrong:**

1. `listFiles() == null` (I/O failure) is treated as **success** (`?: true`).
2. Per-entry failures are swallowed; return value of `clearCacheDir` is **ignored**.
3. The whole step is inside `tolerateCleanup`, so it cannot fail the burn.
4. Comments/docs call this “the most load-bearing” plaintext cleanup and claim the burn destroys `cameracapture`/`dropshare`. That is not enforced.

Staging paths are real: `AttachmentLoader.CAMERA_CAPTURE_DIR`, `QrDropDialogs` → `cache/dropshare`.

**Why it matters:** Image obliteration can succeed while plaintext photos/QR payloads remain. That is a worse duress residual than a failed vault open.

**Concrete fix:**

- Treat `listFiles() == null` as failure (`return false`), not success.
- After obliteration success, re-run cache clear; if `cameracapture`/`dropshare` (or cache root) still non-empty, either fail closed for burn presentation **or** document residual honestly and retry on next boot.
- At minimum: do not claim absolute destruction of plaintext cache while the implementation is best-effort fail-open.

---

### 4. MEDIUM — Failed burn leaves worse app state than “not burned”; failure copy is wrong

**Where:** `burnVault` `ZitroneApp.kt:680–688`; failure UX `MainActivity.kt:838–844`

Order: `wipeBiometricMaterial()` → `wipeAppLocalStateForBurn()` (settings, prefs, diagnostics, notifications, cache) → **then** `obliterateForBurn()`.

If obliteration fails with the image still present (verify path, `DestroyFailed` with files surviving):

- Biometric wrap/aliases gone  
- `onboarding_done` and other device settings cleared  
- Notifications/channels cleared  

Failure UI says: *“The vault is still on disk and still unlockable”* — incomplete. Unlockable by passphrase may still hold; biometric path and settings parity do not.

**Concrete fix:** Prefer image-first, then app-local (accept that residual app-local is cleaned on retry); or on failure, avoid claiming full unlockability; or split “must succeed” cleanups from cosmetic ones and only run cosmetic after image proof.

---

### 5. MEDIUM — Keys-first crash window has no burn self-heal (destroy does)

**Where:** `obliterateLocked` `VaultImageStore.kt:1113–1116`; `open()` `VaultImageStore.kt:317–318`; reconcile only when `!binFile.exists()`.

Keys-first crash after DEK unlink, before bin unlink:

- `hasVault()` true (bin-keyed)  
- `open()` → `CorruptImage` (bin present, dek absent)  
- No `delete-confirmed` → Splash → `Locked`, not `DeleteIncomplete`  
- `reconcileOrphanedBurnMarkers` no-ops (image “present”)

Destroy mid-crash with confirmed present still self-heals via `Route.DeleteIncomplete`. Burn mid-crash does not.

**Why it matters:** Review item 7 — partial burn can be worse than not burning: everyday passphrase hits `ImageUnreadable`; only re-entering an armed burn credential (or reinstall) finishes cleanup. Acceptable crypto-erasure tradeoff if intentional, but there is no boot completion for `{bin, !dek}`.

**Concrete fix:** Boot path: if `Files.notExists(dek)` and bin present (or `CorruptImage` of that shape), finish `obliterateLocked` — state is already cryptographically dead; safe to complete wipe without a passphrase.

---

### 6. LOW — Keys-first order not proven against `obliterateLocked` itself

**Where:** `BurnObliterateTest.kt:258–268`

Test manually deletes `vault.dek` and asserts `open()` fails. That proves the brick shape, not that `obliterateLocked` unlinks DEK before bin (e.g. instrumented/fake `File` or ordered spy).

**Fix:** Order-sensitive test (fault injection after first unlink, or ordered delete hooks).

---

### 7. INFO — Timing residual (documented, inherent)

SECURITY_MODEL correctly notes wipe work after a burn match is wall-clock visible. No extra distinguisher found beyond that once Burn is reachable. With slot 0 unarmed, path is unreachable today.

---

## Binding review items

### 1. `destroy()` equivalence — **ACCEPTABLE (with intermediate difference, not end-state regression)**

**Verified against source:**

- Old: `bin` then `dek` then temps → verify → `dirSync` → clear markers.  
- New: `destroy` still writes confirmed first (`VaultImageStore.kt:1077–1078`), then `obliterateLocked` does **dek → bin** (`1113–1116`).

**Author’s argument, evaluated:**

- End state of a successful `destroy()` is unchanged: no bin/dek/temps, both markers gone.  
- Confirmed-marker-first crash bridge is preserved (`writeDurableMarker` before unlinks; `BurnObliterateTest` “crash bridge” test).  
- Idempotent re-entry via `Route.DeleteIncomplete` still covers mid-unlink crashes.  
- Keys-first intermediate `{bin, !dek, confirmed}` is **different** from old `{!bin, dek, confirmed}` but still routes to DeleteIncomplete and re-destroy; not worse for destroy semantics.  
- Keys-first is **better** for a pure crash mid-unlink (ciphertext without key vs key without ciphertext).

**No case found where shared keys-first ordering is unacceptable for `destroy()`.** A `keysFirst` boolean is unnecessary for safety of this refactor. External success/failure of account-delete still keys off confirmed + hasVault.

---

### 2. Obliterate ordering — **PASS inside `obliterateLocked`; FAIL on new reconcile writer**

Within `obliterateLocked` (`1104–1159`):

1. RAM wipe  
2. Unlink keys-first  
3. `unregister`  
4. Re-stat verify (any survivor → throw)  
5. `dirSync` durable (else throw)  
6. `clearBothMarkersDurably` **last** (else throw)

No early return clears markers. Throws before step 6 leave markers intact (covered by test at `BurnObliterateTest.kt:239–250`).

**Gap:** `reconcileOrphanedBurnMarkers` is a separate path that can clear markers without that proof discipline (Finding 2).

---

### 3. Boot reconciliation — **PARTIALLY CORRECT**

| Claim | Verdict |
|---|---|
| (a) Crash between unlinks and marker clear covered | Yes for image-absent + intent-only + confirmed-absent; LaunchedEffect calls it (`MainActivity.kt:701–704`) |
| (b) Image-absent never routes to `DeleteIncomplete` from burn-only state | Yes: burn never writes confirmed; Splash uses only `serverDeleteConfirmed()` (`1243`) |
| (c) Cannot clear marker D2c still needs | **Intent over live image:** intended no; **implementation:** weak `exists()` (Finding 2). **Confirmed mid-heal:** correctly preserved via `!Files.notExists(serverDeletedFile)` |

Burn-produced crash after unlinks with **no** prior intent leaves no marker to clear; routing is still Onboarding via `!hasVault` — fine.

---

### 4. Writer/reader invariants — **ONE NEW WRITER; otherwise consistent**

| Signal | Writers (after change) | Issue |
|---|---|---|
| `vault.delete-confirmed` | still `markServerDeleteConfirmed` / `destroy` prefix; burn does **not** write | Good |
| Marker **clear** | `create` F2, `destroy`/`obliterateLocked`, **`reconcileOrphanedBurnMarkers` (NEW)** | New writer needs proven image absence |
| `hasDeleteIntentMarker` / auth protection | readers unchanged | Orphan intent after partial burn keeps auth guard until clear — harmless over dead image |

No new writer of `delete-confirmed`. Burn correctly avoids D2c confirmed fact.

---

### 5. Reachability — **PASS (structurally unarmed; wipe wiring is lock-screen only)**

- `createVaultSlots` places vault in pool only; slot 0 stays `randomSlot` filler (`VaultSlots.kt:140–142`).  
- No arming API in this diff.  
- `attemptUnlockOrAdd` returns `UnlockOrAdd.Burn` only on slot-0 match (`VaultImageStore.kt:683–691`); store does not wipe.  
- Only consumer of `PassphraseOutcome.Burn` → wipe is lock-screen `onBurn` (`MainActivity.kt:865`, `807–848`).  
- Onboarding create uses `createVaultAndPublish` → `imageStore.create`, never Burn.  
- Second-vault add goes through `attemptPassphrase`; Burn still only hits `onBurn` on the lock screen (correct if ever armed: burn wins over create).

Test `slot 0 is unarmed after create` (`BurnObliterateTest.kt:383–402`) actually exercises the production path — not vacuous.

---

### 6. Concurrency / lifecycle — **PASS**

- `beginTerminalWipe` before mutations; `endTerminalWipe` in outermost `finally` (`MainActivity.kt:811–828`) — gate not stranded on throw/cancel.  
- Process-scoped `container.scope` survives rotation.  
- `UnlockController.unlock` refuses while `terminalWipe` (`UnlockController.kt:80`).  
- Process death clears the RAM gate; disk truth re-routes.  
- No session resurrect path after successful image destroy (publish requires unlock; image gone → onboarding create).

---

### 7. Fail-closed — **FAIL at UI / residual layers; primitive mostly OK**

| Layer | Verdict |
|---|---|
| `obliterateLocked` throws on survivor / non-durable / marker fail | Correct |
| UI success = `!hasVault` after swallowed exception | **Fail-open** (Finding 1) |
| Partial burn worse than no burn? | **Yes** — `{bin,!dek}` brick; app-local wipe before failed image (Findings 4–5) |
| Failure UX claims vault fully unlockable | Overstated (Finding 4) |

---

## Also checked (no extra defect, or lesser notes)

**Tolerated vs not for image:** Image destroy is not inside `tolerateCleanup`; cleanups cannot skip it. Boundaries are right for *not masking* image failure, wrong for *guaranteeing* plaintext/cache destruction (Finding 3).

**Attachment cache scope:** Clears entire `cacheDir` contents (includes `cameracapture` and `dropshare`) — correct breadth when clear works; FileProvider paths are under cache.

**Oracles:** No new KDF-level unlock oracle. Burn match still runs full slot sweep first. Post-match wipe cost is documented.

**Tests:**  
- Strong: marker-not-cleared on non-durable; burn never writes confirmed; byte-for-byte directory gate; unarmed slot 0; reconcile surgical cases (when `exists()` is honest).  
- Weak/vacuous: UI fail-closed never tested; cache failure path untested; keys-first order not observed on the primitive; EncryptedSharedPreferences path explicitly excluded (honest).

---

## Summary

| Severity | Count | Top issue |
|---|---|---|
| CRITICAL | 0 | — |
| HIGH | 3 | False burn success; reconcile `exists()` B1 risk; plaintext cache fail-open |
| MEDIUM | 2 | Degraded failure state; keys-first no self-heal |
| LOW/INFO | 2 | Test gap; timing residual |

**Ship-blocking before arming slot 0 (Unit S):** Findings 1–3. The wipe primitive’s internal ordering is largely sound; the **presentation and boot reconcile** layers reintroduce fail-open patterns D2c spent many rounds removing. Slot 0 unarmed limits exploitability today but does not make the mechanism safe to trigger later.
