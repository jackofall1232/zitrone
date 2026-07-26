I'll conduct an independent Round-2 security review of Unit W-B from source only. Starting with the protocol files and the unit's diff against main.Focusing on the W-B code path and the round-1 fix delta next.Reading the core burn/durability implementation and the gate next.Gradle cache hit a permission error — retrying with a local Gradle home, and continuing the oracle-class artifact hunt.Retrial with the working Gradle cache — the isolated home hit environment failures, not product defects.# Unit W-B Round-2 Independent Security Review

**Scope reviewed:** `main...HEAD` at `4cf1db5` (W-B steps + round-1 fixes), source-only, blind to other reviewers.  
**Verdict: NOT READY TO MERGE**

Round-1’s tri-state fold and content-hash gate are real improvements. They do **not** close post-burn ≡ fresh install. A used vault still leaves durable prefs residue a never-used install does not have, and the load-bearing gate still cannot see that class under its current provisioning.

---

## Findings

### F1 — HIGH — BLOCKING  
**`ZitroneApp.kt:378-410` vs `982`, `1007-1010`; `SettingsRepository.kt:70-90`, `97-108`**

**Defect.** A successful `burnVault()` does not reset device-level EncryptedSharedPreferences content to a never-used baseline.

Producers of “exists only if a vault session existed”:

| Artifact | When created | Burn? |
|---|---|---|
| `onboarding_done=true` | every successful `publishSession` (`ZitroneApp.kt:982`) | **not reset** |
| Tor / I2P / auto-lock / other device prefs | Settings writes via `SettingsRepository` | **not reset** |
| Biometric wrap keys in `zitrone_settings` | enable path | cleared by `wipeBiometricMaterial` only |
| `zitrone_signal_store` / `zitrone_auth` / `zitrone_contacts` files | `wipeLegacyPrefs()` on onboarding `createVaultAndPublish` (opens + `.clear()` → files still exist) | **not deleted** |

Round-1 deliberately keeps the **prefs file** and `_androidx_security_master_key_` because a fresh install has them (`ZitroneApp.kt:402-404`). That is correct for *file presence*. It does **not** authorize leaving *non-default keys* or *extra prefs files* created only after vault use.

**Why it matters.** Post-burn state is distinguishable from a fresh install by encrypted prefs content (and, for the three legacy files, by filename presence). A coercer with filesystem access—or even network side-effects if Tor stayed enabled—sees “this device ran a vault,” which is the feature failing at its purpose.

**Concrete fix.** Inside the burn’s guarded region (after image obliterate, with fail-closed checks):

1. Reset `PREFS_SETTINGS` keys to factory defaults **or** clear all non-startup-required keys and re-seed defaults; refresh `SettingsRepository`’s in-memory `StateFlow`.
2. Delete (or prove absent) the three legacy prefs files if present; do not leave empty “cleared” shells that a never-opened install lacks.
3. Prove prefs snapshots match a never-used baseline (or re-open prefs and assert defaults), same posture as Keystore/device-key delete.

**Boundary:** **BLOCKING** — breaks post-burn ≡ fresh install.

---

### F2 — HIGH — BLOCKING (gate soundness; enables F1 to ship green)  
**`BurnByteForByteGateTest.kt:109-133` (and sibling tests); `docs/SECURITY_MODEL.md:572-578`**

**Defect.** The positive gate provisions only:

```text
imageStore.create → burnVault
```

It never:

- calls `publishSession` / `setOnboardingDone`
- mutates device settings
- records `BootDiagnostics`
- populates `cacheDir`
- runs `createVaultAndPublish` / `wipeLegacyPrefs`

So `assertEquals(fresh.prefs, burned.prefs)` can pass while production post-use burn leaves F1 residue. Content hashing fixed the length/filename defect; it cannot detect residue the test never creates.

**Negative test** (`the_gate_catches_a_deliberately_orphaned_keystore_alias`, lines 195-239): now names the planted alias — that half is sound for Keystore.

**`burn_requires_the_biometric_wipe_to_succeed` (169-181):** asserts “no biometric alias” + hold lowered after a path that never enabled biometrics. A burn that **ignored** `wipeBiometricMaterial()`’s boolean would still pass this test whenever no biometric alias existed. Wrong implementation includes the defect the name claims to guard.

**Why it matters.** DoD-8 and `SECURITY_MODEL.md` treat this gate as load-bearing. A green run is non-discriminating for the prefs/diagnostics/cache class—the same failure shape this unit’s history documents.

**Concrete fix.**

1. Provision a realistic used state: `publishSession` (or full unlock path), set at least one non-default setting, `bootDiagnostics.record(...)`, write a cache file, optional biometric enable on emulator.
2. Assert content equality after burn.
3. Negative tests that plant **prefs keys** and **diagnostics file**, not only Keystore aliases.
4. Strengthen the biometric test by forcing a wipe failure (or a planted alias that only the burn path removes) and asserting `DestroyFailed` + hold raised.

**Boundary:** **BLOCKING** for merge while the gate is cited as proof of post-burn ≡ fresh; the production defect is F1.

---

### F3 — MEDIUM — BLOCKING  
**`ZitroneApp.kt:405`; `BootDiagnostics.kt:92-102`**

**Defect.** `bootDiagnostics.clear()` is best-effort (swallows IO, does not return success, does not prove absence). The burn still lowers the hold and reports success. Cache clear is fail-closed; diagnostics is not.

**Why it matters.** If `boot-diagnostics.log` survives (or is emptied but not unlinked), post-burn ≠ fresh. Same “exists only if used” class F1; partial class-fix.

**Concrete fix.** After clear, require `Files.notExists(diagnosticsFile)` (or equivalent) and throw `DestroyFailed` on failure—mirror `deleteKeyMaterial` / `clearCacheDir`.

**Boundary:** **BLOCKING** if residue can remain after a reported success (it can).

---

### F4 — LOW — DEFERRABLE  
**`MainActivity.kt:897-900`; `docs/SECURITY_MODEL.md:564-566`; `VaultImageStore.kt:1403-1404`, `1440`; `ZitroneApp.kt:433-436`**

**Defect.** Stale / contradictory prose next to live code:

- Comment still calls the handler a “FAIL-CLOSED STUB” and says wipe has not landed; `onBurn` immediately below calls real `burnVault()`.
- `SECURITY_MODEL.md` still says wipe is a “fail-closed stub” four lines above the W-B gate section that documents a real wipe + CI gate.
- Reconciler kdocs still say “Returns true iff…” while the type is `ReconcileResult`.
- Fold comment still describes Boolean `false` conflation as current behavior after the tri-state fix (historical text left in place).

**Why it matters.** This unit’s history is wrong confident prose. Not a runtime break; trains the next change incorrectly.

**Fix.** Delete/update stub claims; document “wipe wired, credential unarmed”; fix kdocs to match `ReconcileResult`.

**Boundary:** **DEFERRABLE** (honesty/process), not post-burn ≡ fresh by itself.

---

### F5 — INFO — DEFERRABLE  
**`BurnReconcilerTriggersTest.kt:79-106`**

Enumeration is 5 bits (bin, dek, binTmp, intent, confirmed) = 32 states. On-disk image-bearing also includes `vault.dek.tmp`. Pairwise exclusivity still holds by inspection for `dekTmp`, and non-vacuity fires all three mutators—but the “proof over all on-disk states” claim is slightly short of the real 6-bit space.

**Fix.** Add `dekTmp` to the enumeration (64 states) or state explicitly that temps are collapsed under “any image-bearing residual.”

---

## Binding focus verdicts (A–J)

### A. WB-3 — one durability owner, three producers — **PASS (with fold fixed)**

Producers publishing into `durabilityHold`:

1. Cold-start sweep (`sweepOrphanedResidue` via fold)  
2. Boot reconcilers (`completeInterruptedBurn`, `reconcileOrphanedBurnMarkers`)  
3. Runtime burn (`runBurnWipe` / `raiseDurabilityHold`)

Routing consumes only the boolean (`bootRoute` / `deriveBootDecision`). No producer discriminator escapes the owner.

Round-1 tri-state: `ReconcileResult` has exactly three values; fold raises hold on any `MUTATED_NOT_DURABLE` (`ZitroneApp.kt:443-446`). The prior TRUE-only re-derivation hole is closed in source.

No fourth producer of **image durability** found that should publish and does not. Account-delete uses markers + `DELETE_INCOMPLETE`, not onboarding-over-unproven-wipe. Prefs residue (F1) is a deniability defect, not a fourth durability-hold producer.

### B. `destroy()` deviations — **ACCEPT both; keep downstream defence**

1. **Keys-first.** Confirmed marker is written before `obliterateLocked` on account-delete; burn uses the same core without that marker and relies on `completeInterruptedBurn` for `{bin present, dek absent}`. Crash windows have reconcilers. Keys-first is strictly safer cryptographically. Fallback `keysFirst` parameter remains available if policy wants parity with historical bin-first; not required for safety.

2. **S4 `Files.notExists`.** Fail-closed; `{image survives, confirmed absent}` unreachable through obliterate. **MainActivity’s post-destroy routing defence (`MainActivity.kt:1263-1293`) must stay**—it does not depend on S4 and must not be deleted as dead code. Agree with the in-tree argument.

### C. “Exists only if used” class — **FAIL (F1, F3)**

Fixed: device-key alias, biometric material (consumed boolean), diagnostics/caches (partial).  
**Missed from source:** device prefs keys (`onboarding_done`, transport/auto-lock, etc.), legacy prefs **files** from `wipeLegacyPrefs`, fail-open diagnostics clear.  
No WorkManager job names found. Notification channel `messages_v2` is created on every `Application.onCreate`—not vault-oracle. Cache paths under `cacheDir` are wiped; gate still doesn’t exercise them.

### D. Gate attack — **FAIL (F2)**

- Content hashes: good; matches what `SECURITY_MODEL` claims for trees it snapshots.  
- Coverage vs real use: **not** sufficient (F2).  
- Negative Keystore test: now names its artifact — good.  
- Biometric “requires wipe” test: can pass while proving little.  
- Cache not in snapshot: OK if unclaimed; still a hole relative to production wipe surface.

### E. WB-1 uniform failure + hold — **PASS**

- Failure UI: `BurnCompletion.Failed → VaultUnlockRouter.UNIFORM_FAILURE` (`MainActivity.kt:968`), same string as wrong passphrase (`VaultUnlockRouter.kt:177`).  
- Hold: raised before obliterate (`runBurnWipe`); failure leaves hold raised (`BurnDurabilityHoldTest` design; `runBurnWipe` has no `finally` lower).  
- No distinct burn-failure message path found.

### F. WB-2 `NonCancellable` — **PASS**

Wipe runs on process `container.scope` under `withContext(NonCancellable + Dispatchers.IO)` (`MainActivity.kt:940-942`). Composition cancellation cannot abort mid-wipe. Process death is a different threat (disk reconcilers + pre-raised hold).

### G. WB-7 mutator exclusivity — **PASS with F5 caveat**

Triggers as implemented are pairwise exclusive; test uses tri-state ≠ `NO_MUTATION` and has non-vacuity. Fold does not trust Boolean conflation. Enumeration missing `dekTmp` (F5)—not a demonstrated dual-fire bug.

### H. `vaultExists` initial `false` — **PASS**

Consumers: biometric affordance, lemon-drop pre-onboarding gate. Both are safe when false (hide biometric; treat as pre-vault). Assignment occurs after boot decision on Splash (`MainActivity.kt:654-666`) and sibling re-derives. No consumer routes “no vault” to onboarding while an image exists based on this field alone—Splash holds until boot publishes.

### I. Unit suite — **MY numbers (not the claim)**

| Run | Result |
|---|---|
| Claim | 524 total / 521 passed / 0 failures / 3 skipped |
| **This environment** | **524 total / 344 passed / 177 failed / 3 skipped** |

All 177 failures are `java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native` (lazysodium/JNA host init)—environment, not assertion failures in W-B logic. Non-sodium W-B tests that did run green: `BootRouteTest` (10), `DeriveBootDecisionTest` (6), `BurnCompletionCoordinatorTest` (6), `BootReconcileOwnerTest` (11), `DeleteRetryOwnerTest` (5), `ResidenceTest` (9). Sodium-backed W-B tests (`BurnDurabilityHoldTest`, `BurnReconcilerTriggersTest`, `SweepOrphanedResidueTest`) did not execute product assertions here.

**I cannot corroborate 521/0 from this checkout.** Instrumented gate not run (no emulator); CI run 30171035588 treated as claim only.

### J. Other

- Stale stub docs/comments (F4).  
- Commit messages for round-1 fixes accurately describe the tri-state and content-hash changes; they overclaim “class-fix” for exists-only-if-used while prefs remain.

### Round-2 fix-delta guilt

| Fix | Verdict |
|---|---|
| 1. `ReconcileResult` tri-state + fold | **Sound.** Complete enum; fold covers `MUTATED_NOT_DURABLE`; routing still boolean-only; WB-3 preserved. |
| 2. Gate content hashes | **Hashes correct; coverage set still wrong for real use** (F2). |
| 3. Diagnostics/cache wipe | **Partial.** Cache fail-closed; diagnostics not; prefs class missed (F1/F3). |
| 4. Non-discriminating assertions | Keystore negative fixed; biometric-require and positive gate still weak (F2). |

---

## READY TO MERGE?

# **NO**

**Must fix before merge (BLOCKING):**

1. **F1** — reset/delete vault-use prefs residue so post-burn prefs match never-used.  
2. **F2** — re-provision the gate so a green run would fail on F1.  
3. **F3** — fail-closed diagnostics wipe.

**May defer:** F4 stale prose, F5 enumeration completeness.

An honest clean pass was possible for durability/routing (A, B, E, F, G, H largely hold). The unit still fails its purpose on the exists-only-if-used axis the round-1 delta claimed to close as a class.
