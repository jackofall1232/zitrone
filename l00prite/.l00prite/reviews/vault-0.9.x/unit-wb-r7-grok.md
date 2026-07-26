I'll conduct an independent Round 7 security review of Unit W-B from source only. Starting with protocol context, the merge range, and the round-6 fix delta.Focusing on the round-6 fix commit and core burn/durability sources next.Reading core production sources and running the unit suite in parallel.Gradle wrapper cache is permission-blocked; I'll re-run with a writable Gradle home and dig into the burn/durability path.Forcing a clean unit-test run and continuing the source audit of destroy, lock, vaultExists, and the gate.Tests are still running; continuing source review of destroy, MainActivity burn path, UnlockController, and the gate.Continuing the deep source audit: gate discrimination, prefs enumeration, reconcilers, and session lock safety.Extracting real test counts and continuing critical path analysis.Checking one remaining claim residual on the fold pin, then finalizing the report.# Unit W-B Round 7 — Independent Security Review

**Scope reviewed:** `main...af60d508` (unit as merge); round-6 delta `87282ff..af60d508` attacked first.  
**Blind to all other reviewers.** Every claim below was checked against source I read.

---

## Verdict

# **READY TO MERGE**

Round 6’s stop condition is met: the six claim/check repairs land, and I did **not** find a false pin, a non-discriminating gate control, or a post-burn ≢ fresh-install defect.

An honest clean pass on functional security: **no BLOCKING findings.** Residual notes below are documentation / test-fidelity INFO only, and are DEFERRABLE against the stated boundary.

---

## Findings

### F1 — INFO (DEFERRABLE) — Stale “returns true” kdoc on tri-state reconcilers

- **Where:** `VaultImageStore.kt:1420–1421`, `1457–1458`
- **Defect:** Both still say “Returns true iff …” while the functions return `ReconcileResult`, not `Boolean`.
- **Why it matters:** This unit’s recurring failure mode is confident prose that outlives the code. Functional behavior is correct (`MUTATED_DURABLE` / `MUTATED_NOT_DURABLE` / `NO_MUTATION` are what callers fold).
- **Fix:** Replace with “Returns a `ReconcileResult` …”
- **Boundary:** DEFERRABLE — does not break post-burn ≡ fresh install.

### F2 — INFO (DEFERRABLE) — Gate still omits production’s new `lock()` quiesce

- **Where:** `BurnByteForByteGateTest.kt:439–447` vs `MainActivity.kt:945–960`
- **Defect:** Production `onBurn` now does `beginTerminalWipe()` **then** `unlockController.lock()`. The gate only does `beginTerminalWipe()` before `burnVault`, while still provisioning a published session.
- **Why it matters:** Gate fidelity lag, not a production wipe hole. Production path is correct; process death remains the in-process-writer backstop. A green gate still only certifies the scenario it runs.
- **Fix:** Mirror `lock()` (and keep `terminate = {}` / process-alive semantics).
- **Boundary:** DEFERRABLE.

No CRITICAL / HIGH / MEDIUM / LOW functional findings.

---

## Binding focus A–J

### A. WB-3 — One durability owner, three producers — **HOLDS**

Read in `ZitroneApp.kt`:

| Producer | How it publishes |
|---|---|
| Cold-start sweep | `foldBootMutators` → `publish(hold)` |
| Boot reconcilers | `MUTATED_NOT_DURABLE` → `reconcileUnproven` → same fold |
| Burn obliterate | `runBurnWipe` raises hold **before** mutation; lowers only on success |

`completeInterruptedCleanup` incomplete also raises via the same fold (boot completion of burn residue), not a parallel field.

Consumers (`bootRoute`, `deriveBootDecision`) only see a boolean. No producer discriminator. No fourth mutator that **should** publish and does not: account-delete failures stay on markers (`delete-confirmed`), not the RAM hold.

Round-6 HIGH closure (failed-but-clean burn) is real: `BurnDurabilityHoldTest` exercises stat-clean + hold + `LOCKED`, and `runBurnWipe` order is raise → obliterate → lower → terminate.

### B. `destroy()` deviations — **ACCEPT both; keep downstream DID**

1. **Keys-first (dek then bin)** — Accept. Shared `obliterateLocked()`; account-delete has confirmed marker first so crash re-enters idempotent destroy; burn uses `{bin present, dek absent}` → `completeInterruptedBurn`. Intermediate “image without DEK” is cryptographically dead; reverse order is not safer. `keysFirst` parameter unnecessary.
2. **S4 = `Files.notExists` / proven absence** — Accept. Fail-closed; `{image survives, confirmed absent}` is unreachable through this path.
3. **MainActivity post-destroy routing guard** (`MainActivity.kt:1293–1318`) — Agree it is **defence in depth**, not dead code. Do not delete because S4 “makes it unreachable.”

### C. “Exists only if the feature was used” — **No new open member found in source**

Enumerated from production:

| Domain | Lazy / use-only? | Burn treatment |
|---|---|---|
| `zitrone_vault_device_key` | Yes | Step `device-key` + `containsAlias` probe |
| Biometric `PREFIX*` + legacy alias | Yes | Step `biometric-material` + `noAliasesRemain` |
| `_androidx_security_master_key_` | Startup, every install | Deliberately left |
| Lazy prefs files (signal/auth/contacts) | Yes | Unlink + proven absence |
| Settings **keys** | Yes (inside always-present file) | Reset in place |
| Diagnostics log | Yes | Erase memory+disk+fsync |
| `cacheDir` (attachments, QR share, camera) | Yes | `deleteTreeDurably` |
| Active notifications | Yes | Cancel + read-back |
| Databases / WorkManager jobs | App creates none / no WM | Tripwire / N/A |
| Notification **channels** | Created every `onCreate` | Existence not an oracle; user channel prefs excluded honestly |

Green gate ≠ complete enumeration; source enumeration found no new burn-missed oracle of this class.

### D. Gate attack — **Discriminating for what it claims; databases honestly tripwired**

- Negative controls plant named artifacts and assert the domain’s diff **names** them; cleanup restores baseline — still discriminate if burn wipe set changes (they test **snapshot** discrimination, not burn completeness).
- No empty-coverage path for wipe domains: `assertProvisioned` requires seeded presence first.
- Seeds land where burn looks (filesDir image/diagnostics, prefs, Keystore, cacheDir, system notifications).
- **Databases** correctly labelled TRIPWIRE (gate + `SECURITY_MODEL`) — “empty” is app-surface proof, not wipe coverage.
- `@After`: unconditional `burnVault` + `lock()` — no `hasVault()` skip that would freeze later-stage residue into the next “fresh” baseline.
- Snapshot domains match SECURITY_MODEL (files, prefs, databases, cache, active notifications, Keystore, boot verdict). Channel user-settings remain disclosed exclusions.

### E. WB-1 — **HOLDS**

- Failure UI: `BurnCompletion.Failed` → `VaultUnlockRouter.UNIFORM_FAILURE` (same string as wrong passphrase).
- Hold: raised before wipe; failure leaves it raised; process is **not** killed on failure (`BurnDurabilityHoldTest` + `onBurn` success-only terminate).
- No burn-failure path that surfaces a distinct message.

### F. WB-2 — **HOLDS**

Wipe body: `withContext(NonCancellable + Dispatchers.IO)` on process `container.scope`. Activity cancellation cannot abort mid-wipe. `beginTerminalWipe()` blocks successor unlocks.

### G. WB-7 — **HOLDS**

- `BurnReconcilerTriggersTest`: 64 states (bin, dek, binTmp, **dekTmp**, intent, confirmed); at most one of the three image-bearing mutators; non-vacuity that all three fire.
- Reconcilers return tri-state; `MUTATED_NOT_DURABLE` is re-derived from failed prove, not trusted `true`.
- Fourth mutator (`completeInterruptedCleanup`) is order-dependent by design; pinned via `foldBootMutators`.

### H. `vaultExists` initial `false` — **HOLDS for routing**

Initializer is pre-reconciliation `false`. Splash waits for `splashFinished && bootDone` before assigning from derivation. Non-routing readers (`biometricUnlockAvailable`, lemon-drop veil) are safe when false. Activity-recreation UI lag is already documented as non-residue — not a fresh-install-over-residue path.

### I. Unit suite — **I ran it**

```
GRADLE_USER_HOME=/tmp/gradle-home
JAVA_TOOL_OPTIONS=-Djna.tmpdir=/tmp/jna-tmp
ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest --rerun-tasks
```

**Results from JUnit XML:** **552 tests / 549 passed / 0 failures / 0 errors / 3 skipped.**  
Matches the claim. Instrumented gate not run here (CI claim only).

### J. Other / enumerations / commit honesty

- Round-6 commit message matches what the delta actually does (prose + pins + session quiesce + tripwire label + `DestroyFailed.step`).
- Preference-store enumeration (four stores) matches `KeyStoreManager` constants and all `prefs(` call sites.
- Burn plan steps match wipe surface (diagnostics, cache, notifications, image, biometric, prefs, device-key).
- Only active “Pinned by `…`” production claim names `BurnCleanupOrderingTest`; that class **does** reference `foldBootMutators`. Historical false pin to `BootReconcileOwnerTest` is documented as corrected, not re-asserted.

---

## Round 7 exit test (six repairs)

| # | Repair | Verdict |
|---|---|---|
| **1** | Ordering prose | **PASS.** `SECURITY_MODEL.md`, `CHANGELOG.md`, `burnPlan` kdoc: BEFORE_IMAGE = diagnostics/cache/notifications; preferences **AFTER** image. No fourth site still claiming prefs-before-image. |
| **2** | “Pinned by” mechanical check | **PASS.** Live claim: `BurnCleanupOrderingTest` + `foldBootMutators` — both true. No remaining false pin. |
| **3** | `runBurnPlan` verify pin | **PASS.** Three tests: false postcondition → `DestroyFailed`; throwing postcondition → `DestroyFailed`; later phases don’t run. Reverting to `.forEach { it.action() }` fails all three; they do not pass for unrelated reasons. |
| **4** | `foldBootMutators` owns sweep | **PASS for production.** Sweep is a lambda invoked inside the fold; test asserts `["sweep","gate","cleanup"]`. Production re-reads `imageBearingProvenAbsent()` in the lambda. Residual (INFO): a call site could still close over a precomputed boolean inside a lambda and pass the order test — not done in production; not BLOCKING. |
| **5** | Live session quiesced | **PASS.** `lock()` → `stopSession` → `coordinator.stop()` → `notificationScheduler.cancelAll()`; then scope cancel + bounded drain. Reseal-on-teardown runs while the image still exists (lock before wipe) — safe. Straggler residual after 2s drain remains possible; notification burn step + process death still cover the visible surface. Production lock-screen path usually has no session; call is still correct. |
| **6** | Database = tripwire | **PASS.** Gate + SECURITY_MODEL agree; no other domain carries a coverage claim it cannot support. |

**`DestroyFailed.step`:** step name in exception, not `Log`. Burn-path actions that throw (`deleteTreeDurably` IOException, etc.) still fail the burn via `runCatching { burnVault }.isSuccess` without a distinct UI message. No new Log-between-check-and-throw on the burn runner.

---

## Ready-to-merge statement

**This unit is READY TO MERGE.**

Round 6 was real convergence on the wipe path, not a moved defect frontier I could open from source. The round-6 delta repairs checks and claims that previously overstated correct code; those repairs hold under independent re-derivation.

Ship Unit W-B as-is. Track F1/F2 only if you want residual doc/gate hygiene; neither blocks the feature’s purpose.
