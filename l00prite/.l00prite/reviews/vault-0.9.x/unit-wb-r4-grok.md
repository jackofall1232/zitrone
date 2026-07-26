I'll conduct an independent security review of Unit W-B (Pucker Burn duress wipe), starting from the protocol docs and the scoped git range, then attacking the ROUND-3 fix delta against source.Focusing on the production code in the fix delta and core burn/durability paths.Checking whether a live session can rewrite state after the wipe, and whether RAM-hold re-derivation is complete.Tests were cached UP-TO-DATE — forcing a real re-run and reading the report for counts.Checking one more potential hole: whether a mid-obliterate crash after a durable image wipe leaves preference residue that boot presents as a fresh install.# Unit W-B Round 4 — Independent Security Review

**Scope reviewed:** `git diff main...HEAD` with focus on fix delta `62bb0fd..2146cee` (code) + memory commits through `1e9a755`.  
**Method:** source-only; comments/kdocs/commit messages treated as claims to attack. Unit suite executed in this sandbox (see **I**).

---

## READY TO MERGE: **YES**, with residual non-blocking findings

The round-3 fix delta holds under source review. The four named blockers are fixed in code, process-death is ordered correctly for the property it actually guarantees, and I did not find a new **BLOCKING** break of post-burn ≡ fresh install on the success path. Residuals below are real but sit on the deferrable side of the boundary (or are intentional, documented tradeoffs).

---

## Findings

### F1 — LOW — WB-7 state enumeration still omits `vault.dek.tmp`
**Where:** `BurnReconcilerTriggersTest.kt` ~79–119 (`State` has 5 bits → 32 states; `dekTmp` helper exists but is unused in the enumeration).  
**Defect:** Round 2 flagged this; still open. The mutual-exclusion proof is over `{bin, dek, binTmp, intent, confirmed}` only.  
**Why it matters:** Incomplete “proof by enumeration” is the unit’s signature process failure. I re-checked predicates against source: `completeInterruptedBurn` requires bin present; `sweep` requires bin proven absent; `reconcile` requires all image-bearing paths absent (includes `dek.tmp`). A missing `dekTmp` bit does **not** currently create a dual-fire state — but the suite still claims completeness it does not have.  
**Fix:** Add `dekTmp` to `State` / `materialize` / `allStates` (64 states) and keep the non-vacuity guard.  
**Boundary:** **DEFERRABLE** (ordering irrelevance still holds for current predicates; residual is proof hygiene).

### F2 — LOW — Process-death safety prose overstates what reconcilers re-derive
**Where:** `ZitroneApp.kt` `runBurnWipe` property 4 (~1638–1646); `burnVault` kdoc; `CHANGELOG.md` ~21–23; `BurnDurabilityHoldTest` ~103–104 (“process death mid-obliterate still leaves the doubt recorded”).  
**Defect:** The hold is an in-RAM `MutableStateFlow` (`ZitroneApp.kt` ~348). Process death destroys it. Reconcilers re-derive only **image/marker disk signatures** (`completeInterruptedBurn`, `reconcileOrphanedBurnMarkers`, `sweepOrphanedResidue`). They do **not** re-derive “doubt” for clean image + leftover Keystore/prefs/diagnostics after a crash between `burnObliterate()` and later steps.  
**Narrow claim that still holds:** death does not produce fresh-install routing over an **unproven image wipe** (the journal/re-stat argument), which is what the property-4 sentence actually states.  
**Why it matters:** Confident universal wording (“every interruption point”, “doubt recorded”) is the same defect class this unit keeps shipping.  
**Fix:** Rewrite to: (1) success path = lowerHold then kill; (2) mid-wipe image partials = reconcilers; (3) RAM hold covers same-process failed-but-clean only; (4) non-image residue after a durable image step is a separate residual (see F3).  
**Boundary:** **DEFERRABLE** (prose / future-maintainer hazard, not a success-path functional break).

### F3 — MEDIUM — Mid-wipe crash after durable image step can leave exists-only-if-used oracles under ONBOARDING
**Where:** `AppContainer.burnVault` obliterate order (`ZitroneApp.kt` ~417–463): `burnObliterate()` → biometric → device key → diagnostics → cache → prefs.  
**Defect:** If the process dies after a durable `burnObliterate()` and before later steps finish, next boot sees image-bearing proven absence → reconcilers `NO_MUTATION` → no hold → `bootRoute` → **ONBOARDING**, while device-key alias / lazy prefs / diagnostics / cache may still exist.  
**Why it matters:** That is indistinguishable from a fresh install **in routing**, but not in forensics — the demonstrated “exists only if the feature was used” class, in a crash window the gate never runs. Account-delete deliberately leaves the device-key alias (`KeystoreDeviceKeyCipher.deleteKeyMaterial` kdoc), so a naive “alias without image ⇒ hold” boot rule would collide with that path.  
**Fix (pick one, don’t half-do it):**
1. Boot reconciler that finishes non-image burn cleanups when image is proven absent **and** account-delete is also taught to wipe the same oracle set; or  
2. Document as an accepted mid-wipe residual next to the process-death section (honest limit, not “closed by reconcilers”).  
**Boundary:** **DEFERRABLE** as a crash-window residual of multi-step wipe + process-scoped hold (not success-path post-burn after `terminate`). Track explicitly; do not claim it is closed.

### F4 — LOW — Gate baseline does not assert settings **keys**
**Where:** `BurnByteForByteGateTest.assertFreshBaseline` (~245–260).  
**Defect:** Baseline checks vault/hold/lazy prefs **files**/cache/diagnostics/keystore/databases. It does **not** assert `zitrone_settings` is free of app keys (`onboarding_done`, etc.).  
**Why it matters:** Cross-test contamination of settings keys alone can survive baseline; if wipe were also broken the same way, comparison can match for the wrong reason. Within a single well-ordered test this is weak; harness completeness is the issue.  
**Fix:** Assert settings content equals a known fresh digest or `prefs.all` app-keys empty via the same snapshotter.  
**Boundary:** **DEFERRABLE** (gate harness quality).

### F5 — INFO — Success closes app; failure stays open (intentional distinguisher)
**Where:** `MainActivity.onBurn` ~951–958; `killThisProcess` ~1814; `SECURITY_MODEL.md` ~570–595.  
**Defect:** None as a coding bug — this **is** a coercer-visible asymmetry (vanish ⇒ burn succeeded; uniform error ⇒ not).  
**Weighing:** Better than resting safety on unconfirmed `SharedPreferences` queue semantics. Worse than a silent same-screen failure for pure deniability of the **attempt**. Previous success path (onboarding animation) was also visible. Net: **acceptable and correctly documented**; slightly better for wipe durability, mixed for in-the-moment deniability. I do **not** recommend reverting process death.  
**Boundary:** **DEFERRABLE** (product tradeoff, documented).

### F6 — INFO — `terminate = {}` makes the gate weaker than production
**Where:** `BurnByteForByteGateTest` (~228, ~377, ~549–551).  
**Defect:** None hidden — the test states it. Anything that is only true because the process stays alive (or only true because it dies) is unproven by the gate. Canary correctly exercises the weaker arrangement and labels itself presence-of-bug only.  
**Boundary:** **DEFERRABLE** (tracked next-launch assertion is the right follow-up).

---

## Explicit verdicts A–J

### A — WB-3, one durability owner, three producers — **HOLDS**
Producers publish into one `durabilityHold` (`ZitroneApp.kt` ~320–348, ~414–466, ~485–506). Routing uses only the boolean (`bootRoute` ~1742). No consumer needs a discriminator. Round-6 failed-but-clean case is closed **same-process** by raise-before-mutate + no lower on throw (`runBurnWipe` ~1652–1661; `BurnDurabilityHoldTest`).  
No fourth **current** producer is missing for the image/dirSync class. Boot does not re-publish for non-image oracles (F3) — that is a different residual, not a missing hold discriminator.

### B — `destroy()` two deliberate deviations — **ACCEPT both; keep downstream guard**
1. **Keys-first (dek then bin):** Sound. Account-delete writes confirmed marker before `obliterateLocked` (`VaultImageStore.kt` ~1110–1122); crash re-enters idempotent destroy. Burn uses the same primitive; interrupted keys-first is completed by `completeInterruptedBurn`. Keys-first is strictly safer than bin-then-dek. No need for `keysFirst` parameter unless you want account-delete to freeze old order for archaeology — not required for safety.  
2. **S4 = `Files.notExists` / proven absence:** Correct fail-closed (`~1185–1188`, `imageBearingFilesProvenAbsent` ~1361–1365). Makes `{image survives, confirmed absent}` unreachable through this path.  
**MainActivity post-destroy comment (~1278–1309):** Agree — routing guard is **defence in depth** and must **not** be deleted as dead code. Correctness here must not depend on S4 remaining strict three layers up.

### C — “Exists only if the feature was used” — **hunted from source; no new unnamed production member found**
| Domain | Source | Burn treatment |
|--------|--------|----------------|
| Vault image/DEK/temps/markers | `VaultImageStore` | `burnObliterate` / reconcilers |
| Device-key alias | `KeystoreDeviceKeyCipher.DEFAULT_ALIAS` | `deleteKeyMaterial` gated |
| Biometric aliases | `BiometricVaultKeyCipher.PREFIX` (+ legacy) | `wipeBiometricMaterial` gated |
| `_androidx_security_master_key_` | ESP at startup | deliberately kept |
| Settings keys | `SettingsRepository` | `resetToFreshInstallDefaults` |
| Lazy prefs files (+ `.bak`) | signal/auth/contacts | `wipeLazyPrefsFilesProven` |
| Diagnostics log | `BootDiagnostics` | `erase()` gated |
| Cache (attachments, QR, camera) | `cacheDir` writers | `deleteTreeDurably` |
| Databases | none in app | none; gate asserts empty |
| WorkManager / jobs | none found | n/a |
| Notification channels | `MessagingNotifications.ensureChannel` at every `Application.onCreate` | existence not an oracle; **user-modified channel settings** still survive — disclosed exclusion, not fixed |
| WebView / noBackup / external | not used | n/a |

Gate cannot see lazy create→correct wipe oracles in the pre-burn window — that limit is stated honestly in the gate and `SECURITY_MODEL.md`. Mid-wipe crash residue is F3.

### D — Gate attack — **materially discriminating; residual harness limits only**
- Provisioning via `createVaultAndPublish` + per-domain seeds + `assertProvisioned` — sound.  
- Negative controls per domain (including prefs **file** and prefs **key**) — discriminate.  
- Seeds are on paths the burn actually touches (filesDir vault/diagnostics, shared_prefs, Keystore, cacheDir).  
- `databases` empty assertion is a **coverage tripwire**, not a wipe proof — correctly framed in the test.  
- Teardown unconditional + same-snapshotter baseline — fixes the round-3 contamination hole.  
- Baseline gap on settings keys: F4.  
- `terminate = {}`: F6.  
- Snapshot domains match what `SECURITY_MODEL` claims (files, prefs, DBs, cache, Keystore, boot verdict); notification channels honestly excluded.

### E — WB-1 — **HOLDS on the failure path as coded**
Failure: hold stays raised (`runBurnWipe`); UI sets `UNIFORM_FAILURE` only (`MainActivity` ~983–984); process does **not** terminate (`BurnDurabilityHoldTest` failed-wipe test; `terminate` only after successful obliterate).  
Success: process dies before any distinct success UI; next launch is the fresh-install presentation. Asymmetry of **success vs failure** is F5, not a WB-1 break of the failure half.

### F — WB-2 NonCancellable — **HOLDS**
`onBurn` runs wipe under `withContext(NonCancellable + Dispatchers.IO)` on the **process** scope (`MainActivity` ~950–958). Composition cancellation cannot abort it. `beginTerminalWipe` blocks new unlocks and auto-lock, not the wipe itself.

### G — WB-7 boot mutator ordering — **HOLDS with F1 residual**
Triggers exclusive for enumerated predicates; false from reconcilers is tri-state (`MUTATED_NOT_DURABLE` vs `NO_MUTATION`), folded into hold (`ZitroneApp.kt` ~499–502). `vault.dek.tmp` still missing from the 32-state grid (F1).

### H — `vaultExists` initial `false` — **HOLDS (narrow claim)**
Initializer is `false` (`MainActivity` ~651). Consumers **do** read it early (biometric affordance, lemon-drop veil) but treat false safely; **routing** waits on Splash + `bootDone` (~663–676). Comment at ~641–650 already records the corrected, source-true claim. No fresh-install-over-residue path from the initializer.

### I — Unit suite — **executed; product-green numbers not corroborated here**
| Run | Result |
|-----|--------|
| `./gradlew testDebugUnitTest --rerun-tasks` | **536 completed, 179 failed, 3 skipped** — all sampled failures are `java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native` via `lazysodium` / `SodiumJava` |
| Non-sodium subset (`BootRouteTest`, `BurnCompletionCoordinatorTest`, `DeriveBootDecisionTest`, `VaultUsePrefsWipeTest`, `SettingsFreshInstallResetTest`, `AutoLockDecisionTest`) | **BUILD SUCCESSFUL** |
| Claim 536/533/0/3 | **Not adopted** as my product evidence (same environmental JNA class other R3 reviewers hit) |

Instrumented gate: not run here; CI claim 30180579742 treated as external only.

### J — Other / enumerations
**`2146cee` six-cleanup table** checked against `burnVault` obliterate body:

| Cleanup | gated? | durable? | clears memory? |
|---------|--------|----------|----------------|
| `burnObliterate` | Y (throw) | Y (dirSync) | Y (RAM DEK/canonical) |
| `wipeBiometricMaterial` | Y | Keystore transactional | Y (`biometricStore.clear`) |
| `deleteKeyMaterial` | Y | Keystore | n/a |
| `bootDiagnostics.erase` | Y | Y (dir fsync + notExists) | Y (memory first) |
| `deleteTreeDurably(cache)` | Y | Y (per-dir fsync) | n/a |
| `wipeVaultUsePreferences` | Y | Y (commit + file delete + dirSync) | Y (settings StateFlow reload; `forget` handles) |

Table is complete for those three axes on the six steps. **Third axis already in the table (memory)** was the round-2 miss; no additional unnamed axis found in this delta that ships a new blocking hole. Standing disclosed residual: notification channel **user** settings (not in table, tracked).

Commit/process claims: suite “all green” not re-verified productively here; process-death “every interruption → lock if before lowerHold” is over-broad (F2).

---

## Round-4 attack items (1–7) — short answers

1. **Process death**  
   - Order `raise → obliterate → lower → terminate` is correct for success.  
   - Only success reaches `terminate` (throw skips it).  
   - `killProcess(myPid())` is the right primitive (no shutdown hooks / no flush chance); `exitProcess`/`finishAndRemoveTask` would be weaker for the live-writer problem.  
   - Between `lowerHold` and `terminate`: wipe already proven; next boot onboarding is correct; window is tiny.  
   - RAM hold vs boot re-derive: equivalent for **image** partials / crash recovery; not a full substitute for non-image residue (F2/F3).  
   - Asymmetry: real tradeoff; I judge **net acceptable** (F5).

2. **`BootDiagnostics.erase()`** — Memory-first under the same lock as `record()` is sufficient against concurrent `record()` resurrecting **pre-burn** lines. Fsync of `filesDir` (parent of the log) is correct. `clear()` fail-open UI wrapper does not weaken the burn (burn calls `erase()` and consumes the boolean).

3. **`deleteTreeDurably`** — Post-order, one fsync per directory after children are removed, then prove empty: sound. Subdirectory emptied+fsynced then unlinked; parent fsync makes rmdir durable. Boolean `clearCacheDir` wrapper is fail-closed→boolean for cold-start retry only; does not reintroduce a non-durable burn path (burn uses throwing path via `runCatching` → `DestroyFailed`). Symlink-escape / deep-tree stack risk: LOW robustness only.

4. **Gate changes** — Unconditional teardown + same-snapshotter baseline are correct fixes. Baseline can still miss settings-key contamination (F4). `terminate = {}` weaker than production (F6).

5. **Canary** — Labelling is honest (presence-of-bug, weaker arrangement). Keeping it is right as an OEM/API tripwire; it is not production’s safety proof.

6. **Non-discriminating assertions** — Main gate path creates production residue before compare; negative controls plant then require the diff to name the artifact. Databases empty check is a tripwire (OK). Order/orchestration tests name mutations they catch.

7. **Enumerations** — Six cleanups complete on gated/durable/memory axes (see J). Preference-store enumeration still matches the only `KeyStoreManager.prefs` factory (four names).

---

## Merge recommendation

| Question | Answer |
|----------|--------|
| **READY TO MERGE?** | **YES** |
| Blockers in R3 fix delta? | **None found** that break completed-burn ≡ fresh install |
| Must-fix before merge? | No |
| Should track? | F1 (dek.tmp in WB-7 grid), F2 (tone down process-death prose), F3 (mid-wipe non-image residue / boot hygiene), F4 (baseline settings keys), notification-channel reset (already tracked), next-launch gate assertion |

**Bottom line:** Round 3’s four code fixes (`erase`, `deleteTreeDurably`, unconditional gate teardown/baseline, process death as drain) are source-correct for their stated defects. Process death is a sound success-path architecture change; its documentation slightly overclaims re-derivation universality, and mid-wipe non-image residue remains a standing residual — neither is a reason to reject this merge if those limits are tracked honestly rather than declared closed.
