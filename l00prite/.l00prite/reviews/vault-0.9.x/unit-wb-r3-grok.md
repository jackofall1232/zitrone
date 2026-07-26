I'll conduct an independent Round-3 security review of Unit W-B from source only. Starting with protocol context, then the unit range and round-2 fix delta.Pulling the unit and fix-delta diffs, then reading the burn/wipe implementation and gate tests.Reading the burn path, wipe helpers, gate, and durability machinery from source.Digging into resurrection risks, reconcilers, consumers of `vaultExists`, and residual artifact enumeration.Running the unit suite and checking a few remaining residual sources.# Unit W-B Round 3 — Independent Security Review

**Scope:** `main...HEAD` (HEAD content at `62bb0fd` for product code; review artifacts after). Fix delta `4cf1db5..62bb0fd` attacked in depth.  
**Verdict: NOT READY TO MERGE** — one **HIGH / BLOCKING** defect in the round-2 `clearProven()` fix; remaining items are DEFERRABLE or clean.

I read production sources, unit tests, the gate, `SECURITY_MODEL.md`, and disassembled `EncryptedSharedPreferences` 1.1.0-alpha06. I did **not** trust comments or commit messages without source checks.

---

## Findings

### F1 — HIGH / **BLOCKING** — `BootDiagnostics.clearProven()` is not a complete proven wipe

**Where:** `apps/android/app/src/main/java/com/zitrone/app/diagnostics/BootDiagnostics.kt:100-103`  
**Consumed at:** `ZitroneApp.kt:410` (`if (!bootDiagnostics.clearProven()) throw …`)

**Defect (two halves):**

```100:103:apps/android/app/src/main/java/com/zitrone/app/diagnostics/BootDiagnostics.kt
    fun clearProven(): Boolean = synchronized(lock) {
        runCatching { file.delete() }
        java.nio.file.Files.notExists(file.toPath())
    }
```

Compared with `clear()` immediately below (lines 105–115), which truncates, deletes, zeros `_entries`, and sets `loaded = true`.

1. **In-memory residual (same process).** `clearProven()` never clears `_entries` and never updates `loaded`. After a real vault use, `record()` has already set `loaded = true` and populated the flow. Post-burn, Settings → Diagnostics still shows the prior boot log via `entries`, while the file is gone. That is distinguishable from a fresh install **without leaving the app**. Worse: a later `record()` path rewrites **old lines + new line** to disk (`record` uses in-memory list as source of truth when `loaded` is true), resurrecting the oracle after the burn “proved” absence and lowered the hold.

2. **No directory durability.** Image obliterate and lazy-prefs wipe both require `dirSync` after unlinks. `clearProven` only stats `Files.notExists`. An unlink that lands in the namespace but is not journal-durable can reappear after crash; the hold is already lowered. Same defect *class* as the round-6 durability HIGH, applied to a different artifact.

**Why it matters:** Post-burn ≡ fresh install is the feature’s purpose. A surviving diagnostics log (RAM, UI, or post-crash disk) is vault-use residue a never-used device does not have.

**Concrete fix:** Make `clearProven()` do what `clear()` does for memory, then prove disk:

- Truncate then delete (or delete after truncate).
- `_entries.value = emptyList()`; `loaded = true`.
- `Files.notExists(file.toPath())`.
- `dirSync(filesDir)` (or parent of the log) required for `true`, matching `wipeLazyPrefsFilesProven`.
- Unit-test: after `record` → `clearProven`, `entries` empty and a subsequent `record` does not rehydrate old lines; non-durable dirSync returns false.

**Boundary:** **BLOCKING** (breaks post-burn ≡ fresh install).

---

### F2 — LOW / DEFERRABLE — WB-7 exclusivity proof still omits `vault.dek.tmp`

**Where:** `BurnReconcilerTriggersTest.kt:79-106` (5 bits → 32 states); predicates at `VaultImageStore.kt:1406-1454`.

State bits: `bin`, `dek`, `binTmp`, `intent`, `confirmed`. **`vault.dek.tmp` is not a free variable.** Round 2 flagged this; still open.

I re-checked the three triggers against source with a hypothetical sixth bit: they still look pairwise exclusive for reachable shapes (e.g. `dek.tmp` alone falls to the sweep; `bin+dek-absent` still only fires `completeInterruptedBurn`). So this is an incomplete *proof*, not a demonstrated dual-fire bug.

**Fix:** Add `dekTmp` to the enumeration (64 states) or document why `dek.tmp` is not independent of `dek` under `imageBearingFilesProvenAbsent`.

**Boundary:** DEFERRABLE (proof completeness / process residual).

---

### F3 — LOW / DEFERRABLE — Gate `databases` domain is a canary, not provisioned coverage

**Where:** `BurnByteForByteGateTest.kt:300-305`, `323`; commit claims “assert empty rather than compare empty set”.

`assertProvisioned` never requires a DB artifact. Fresh empty → burned empty passes if the app never creates DBs (true today). Negative control plants `gate-negative.db` and proves the *diff* works. That is honest canary design, not a false green over wipe logic—but it is weaker than the other domains’ “seed then remove” pattern. If a future DB is created only mid-session and wiped incorrectly while fresh stays empty, the main test still catches a burned≠fresh mismatch; the canary only fires if residue exists at *test start*.

**Boundary:** DEFERRABLE (gate completeness).

---

### F4 — INFO — Preferential wipe ordering is statement order only

**Where:** `ZitroneApp.kt:388` then `423`; comment at `975-978`.

Biometric wrap lives in `zitrone_settings`. Order is wipe biometric → … → `wipeVaultUsePreferences`. Nothing structurally enforces order (no type, no test that reorders). Today’s `wipeBiometricMaterial` returns false only on exception, not on “wrap absent,” so the comment slightly overclaims load-bearing status of the boolean vs. wrap presence. Not currently broken: later `resetToFreshInstallDefaults()` also removes wrap keys with `commit()`.

**Boundary:** DEFERRABLE process/hardening.

---

### F5 — INFO — Round-2 commit assertions largely hold; one overclaim on diagnostics “proven”

`882da6c` four-store enumeration: **complete** against source. Only factory is `KeyStoreManager.prefs`; four name constants; no other `EncryptedSharedPreferences.create` / `getSharedPreferences` in `app/src/main`.

`EncryptedSharedPreferences` clear preserves keysets: **confirmed** via bytecode of 1.1.0-alpha06 — `clearKeysIfNeeded` iterates `getAll()` (skips reserved keys) and re-checks `isReservedKey` before remove. In-place clear is the right approach for content-hash parity.

`c1d5cb0` claim that diagnostics cleanup is “PROVEN” is **overstated** — see F1 (stat-only, no memory clear, no dirSync).

`62bb0fd` “gate defect not burn defect” for the apply race: **holds for the production burn path** (see D/J below), with residual platform-cache notes.

---

## Binding focus items A–J

### A. WB-3 — One durability owner, three producers — **HOLDS**

Producers:

1. Cold-start sweep (`sweepOrphanedResidue` folded in boot).
2. Boot reconcilers (`completeInterruptedBurn` / `reconcileOrphanedBurnMarkers` → `MUTATED_NOT_DURABLE`).
3. Burn via `runBurnWipe` (`raiseHold` before obliterate; `lowerHold` only after normal return).

Consumers (`bootRoute`, `deriveBootDecisionFromDisk`) only test the boolean. No consumer needs a discriminator.

Failed-but-clean burn: `BurnDurabilityHoldTest` / `obliterateLocked` S5 — unlinks then non-durable `dirSync` → `DestroyFailed`, hold remains. Closed structurally.

**No missing fourth producer** among local destructive mutators in the burn path: biometric, device-key, diagnostics, cache, prefs all throw into the obliterate lambda and keep the hold. (Diagnostics *proof* is incomplete — F1 — but it *does* publish failure by throwing when `clearProven` returns false.)

### B. `destroy()` two deliberate deviations — **ACCEPT both; keep defence-in-depth**

1. **Keys-first (`dek` then `bin`).** Shared `obliterateLocked` (`VaultImageStore.kt:1170-1194`). Account-delete writes confirmed marker *before* obliterate; crash re-runs idempotent destroy. Mid-crash state is cryptographically dead image (bin without DEK); `completeInterruptedBurn` finishes it. Keys-first is strictly safer; I do **not** require a `keysFirst` parameter.

2. **S4 `Files.notExists`.** Fail-closed; `{image survives, confirmed absent}` is unreachable through this path. **MainActivity post-destroy routing (`~1264-1294`) must remain** as defence-in-depth: it does not depend on S4 staying correct. Do not delete as dead code.

### C. “Exists only if the feature was used” — re-hunted from source

| Domain | Result |
|--------|--------|
| Prefs stores | Four only; settings reset + three unlinked+proven (`wipeVaultUsePreferences` / `LAZY_PREFS_STORES`) |
| Keystore | Device key wiped (`deleteKeyMaterial`); biometric family wiped (`wipeBiometricMaterial`); `_androidx_security_master_key_` deliberately kept (startup on every install) |
| Diagnostics file | Wiped but incomplete proof (**F1**) |
| Cache | `clearCacheDir` fail-closed |
| Databases | None created |
| WorkManager | None |
| Notification channels | `ensureChannel` in `Application.onCreate` — present on fresh install too, not an oracle |
| noBackup / external / EncryptedFile | No app writers found beyond vault + diagnostics + cache |

**Lazy lifetime oracle (pre-burn):** still true for device-key, biometric aliases, lazy prefs files, diagnostics — gate cannot see that window. Documented in `SECURITY_MODEL.md`; not a burn-path miss except F1 post-burn.

### D. Gate + flush barrier — **mostly sound; not a free pass on coverage**

| Question | Verdict |
|----------|---------|
| Negative controls discriminate? | Yes for six controls / five domains; prefs key control is what caught the apply race. Future wipe-set changes can still leave controls planting artifacts the burn no longer considers “residue” while the burn still “passes” — residual design risk, not a current fail. |
| Vacuous assertions? | Largely fixed: `assertProvisioned` + per-domain controls. `databases` empty canary is the weakest (F3). `burn_requires_the_biometric_wipe_to_succeed` now plants a real alias first. |
| Seeds reached by burn? | vault image, diagnostics, settings keys, three lazy prefs, device-key, biometric alias, cache file — all on burn path. |
| Teardown? | `@After` locks then burns; controls clean themselves. Residual risk if burn fails mid-suite (runCatching). |
| Snapshot vs SECURITY_MODEL? | Aligns: files, prefs, DBs, cache, keystore aliases + hold/boot verdict. |
| **Empty `commit()` barrier** | On EncryptedSharedPreferences → underlying `SharedPreferencesImpl`, `commit()` awaits its disk write; AOSP queues apply/commit per store (minSdk 26). Barrier only opens **existing** stores — will not create post-burn lazy files. **Does not** barrier non-prefs domains (not needed: diagnostics/cache seeds are synchronous `writeText`). |
| **Queued apply after proven-absent unlink?** | Burn: clear+`commit()` then unlink for lazy stores; settings clear+`commit()`. For the **same** `SharedPreferences` instance, commit drains prior applies. Production burn is from the **lock screen** (session normally null); live session uses vault-backed stores, not legacy lazy prefs. Claim that resurrection is a gate bug not a burn bug is **credible for the production path**. Residual: platform `Context` SP cache + a retained handle writing after unlink — not exercised by lock-screen burn; still worth not claiming metaphysical certainty. |

A green gate proves the **scenario it runs**, not enumeration completeness (as the test kdoc correctly states).

### E. WB-1 — **HOLDS**

- Failure: `BurnCompletion.Failed` → `UNIFORM_FAILURE` only (`MainActivity.kt:968-969`).
- Hold: `runBurnWipe` raises before obliterate; throw skips `lowerHold` (`ZitroneApp.kt:1599-1606`, `380-427`).
- No distinct burn-failure UI string found.

### F. WB-2 — **HOLDS**

`onBurn` launches on `container.scope` (process), wipe under `withContext(NonCancellable + Dispatchers.IO)`. Composition cancel does not cancel process-scope + NonCancellable work. `beginTerminalWipe` blocks successor unlocks during wipe.

### G. WB-7 — **HOLDS with open LOW (F2)**

Tri-state reconcilers; fold uses `MUTATED_NOT_DURABLE` directly (not re-trust of ambiguous `false`). Exclusivity tested over 32 states with non-vacuity; `dek.tmp` bit still missing.

### H. `vaultExists` initial `false` — **HOLDS**

Initializer at `MainActivity.kt:642`. Consumers (`biometricUnlockAvailable`, veil unlock) only gate affordances; cold start stays on Splash until `bootReconciled` + assignment (`654-667`, `676-691`). No route decision treats pre-assignment `false` as “proven no vault.”

### I. Unit suite — **MY numbers (this sandbox)**

| Run | Result |
|-----|--------|
| Full `testDebugUnitTest` | **534 total / 177 failed / 3 skipped / 354 passed** — BUILD FAILED |
| Cause of mass failure | `java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native` (lazysodium/JNA native init). Environment, not product assertion failures. |
| W-B pure-JVM suites (no SodiumJava) | All green: `VaultUsePrefsWipeTest` 7, `SettingsFreshInstallResetTest` 3, `BootRouteTest` 10, `BurnCompletionCoordinatorTest` 6, `BootReconcileOwnerTest` 11, `DeriveBootDecisionTest` 6, `ResidenceTest` 9, `DeleteRetryOwnerTest` 5 |
| Sodium-backed W-B suites | Fail at class init: `BurnDurabilityHoldTest`, `BurnReconcilerTriggersTest`, `SweepOrphanedResidueTest`, etc. |

**I do not adopt 534/531/0/3.** That claim is consistent with a healthy host; this sandbox cannot load JNA/sodium. Instrumented gate not run here (no emulator); CI green/red claims treated as external only.

### J. Other / enumerations

- Preference four-store enumeration: **complete**.
- Burn cleanup fail-closed enumeration: diagnostics **intended** gated but **incompletely implemented** (F1).
- Six negative controls / five domains: **matches source**.
- No fifth preference store found.
- Session left published after burn (gate comment): acceptable for lock-screen production path (session null); gate provisions with live session deliberately.

---

## Round-3 “guilty until proven” items

| Fix | Verdict |
|-----|---------|
| **1. `clearProven()` (c1d5cb0)** | **GUILTY** — F1. Consumed on burn path; not fully proven; memory/UI residual; no dirSync. |
| **2. Preference wipe (882da6c)** | **Mostly clean.** Four-store enum complete; keyset preservation matches androidx bytecode; lazy unlink + dirSync + empty-list anti-vacuity sound; skip dirSync when `shared_prefs` absent is sound. Resurrection claim OK for lock-screen burn. Ordering comment slightly soft. |
| **3. Gate + flush barrier (2bd7af0 / 62bb0fd)** | **Mostly clean** for what it claims. Barrier design correct for prefs race; production burn `commit` ordering credible; cannot certify completeness of coverage set (by design). |
| **4. Non-discriminating assertions** | Major R2 instances fixed. Remaining weak shape: databases empty canary (F3). No new “assertFalse over uncreated state” as bad as pre-R2 biometric test. |

---

## READY TO MERGE?

### **NO**

Blocker: **F1** — `clearProven()` does not establish post-burn ≡ fresh install for diagnostics (process memory / UI, possible log rewrite, missing dirSync). That is the feature purpose, not hardening.

After F1 is fixed and re-verified (unit test for memory+rewrite; burn still fails closed on non-durable delete; re-run instrumented gate), the rest of the unit looks mergeable from this pass, with F2/F3 tracked as residuals.

---

## Explicit clean passes (not invented)

- WB-1 / WB-2 / WB-3 core structure  
- Keys-first + proven-absence S4 + defence-in-depth routing  
- Preference store enumeration completeness  
- Lazy prefs proven unlink + dirSync  
- Settings in-place reset design (keyset-preserving clear)  
- Gate rebuild architecture (production provision, seeds, per-domain negatives, flush barrier)  
- `vaultExists` pre-reconciliation initializer  
- No fifth prefs store / no WorkManager / no app databases / notification channel not an oracle
