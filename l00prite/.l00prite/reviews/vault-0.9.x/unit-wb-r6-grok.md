I'll conduct an independent security review of Unit W-B (Pucker Burn duress wipe), starting from protocol docs and the round-5 fix delta, then verifying each binding focus item against source.HEAD includes the round-6 prompt commit; I'll scope the unit merge to `main...87282ff` and dig into the round-5 fix delta next.Reading core burn-path sources and launching the unit suite in parallel.Gradle home is unwritable; I'll re-run the suite with a local home and keep reading burn-path sources.Unit suite is still running; I'll keep reading the burn path, gate, and key consumers.Tests were UP-TO-DATE from cache; forcing a full re-run and continuing source review.Unit suite finished; extracting real counts and checking a few remaining verifier edges.Checking one more stale kdoc cluster and whether the false-postcondition gap is absolute:# Unit W-B Round 6 — Independent Security Review

**Scope reviewed:** production + tests at `87282ff` (unit as-if-merge); fix delta `9bf1f1e..87282ff`.  
**Method:** source read/grep only; no other reviewers’ reports. Unit suite run locally. Instrumented gate not run here (CI claims only).

**Unit suite (this sandbox):** **549 total / 546 passed / 0 failures / 3 skipped**  
(`./gradlew testDebugUnitTest --rerun-tasks` with writable `GRADLE_USER_HOME` + `JAVA_TOOL_OPTIONS=-Djna.tmpdir=…`; XML aggregation under `app/build/test-results/testDebugUnitTest/`). Matches the evidence claim.

---

## READY TO MERGE?

**No.** The wipe path’s round-5 *code* repairs largely hold, but this round’s job is the checking layer and claim honesty — and two high-weight members of the unit’s recurring “confident prose / non-discriminating check” class are still live. Round 7 can clear them mechanically; no redesign required.

**Convergence in one more round:** reachable **if** (1) `SECURITY_MODEL.md` + the `burnPlan` kdoc are re-derived to AFTER_IMAGE preferences (not soft-worded), (2) every “pinned by X” claim passes `grep X for the symbol`, and (3) a unit test fails when `runBurnPlan` succeeds on action but `verify()` is false. If those three land and still leave a false pin or a non-discriminating gate control, stop/rescope — the process is not converging.

---

## Findings

### F1 — HIGH — `SECURITY_MODEL.md` + `burnPlan` kdoc reassert the fixed prefs ordering  
**Where:** `docs/SECURITY_MODEL.md:590–613`; `ZitroneApp.kt:443–445`  
**Defect:** Code moved `vault-use-preferences` to `BurnPhase.AFTER_IMAGE` (`ZitroneApp.kt:508–530`). Authoritative prose still says non-cryptographic cleanups including **preferences** run **BEFORE** the image, and even presents reset settings on a surviving vault as a *deliberate, innocuous* consequence of that order.  
**Why it matters:** That is exactly the round-5 BLOCKING oracle (intact unlockable vault + every setting wiped). A future change that “restores documented ordering” reopens the feature failure. Same class as “claim false the day it was written.”  
**Concrete fix:** Rewrite both sites to: BEFORE_IMAGE = diagnostics / cache / notifications only; preferences are AFTER_IMAGE because their interruption is a durable tell; drop the “reset settings on working vault is deliberate” paragraph (that state is no longer produced by the prefs step).  
**Boundary:** **DEFERRABLE** for runtime post-burn≡fresh (code is correct); **process-blocking for merge honesty** on this unit.

### F2 — HIGH — Round-5’s primary repair is unpinned by any unit test  
**Where:** `BurnPlan.kt:158–185` vs `BurnPlanTest.kt` (all `runBurnPlan` call sites)  
**Defect:** `runBurnPlan` now calls `verify()` after every `action()` and throws `DestroyFailed` on false/throwing verify. **No test** couples `runBurnPlan` to `verify = { false }` after a successful action. Boot-side re-verify is tested; the burn path is not.  
**Wrong implementation that still passes the suite:** restore  
`steps.filter { it.phase == phase }.forEach { it.action() }`  
— phase-order tests, empty-plan test, and boot completion tests all stay green.  
**Why it matters:** This was the highest-weight round-5 finding (“primary consumer never read postconditions”). Shipping it without a discriminator is how non-verifying verifiers recur.  
**Concrete fix:** One test: action sets a flag / no-op success, `verify` returns false → `runBurnPlan` throws; later phases do not run. Optional: verify-throwing → same.  
**Boundary:** **DEFERRABLE** (code correct today); regression of a closed deniability path is the real risk.

### F3 — MEDIUM — “Pinned by `BootReconcileOwnerTest`” is still false at the production call site  
**Where:** `ZitroneApp.kt:606`  
**Defect:** Mechanical check from the round-5 commit itself:  
`grep foldBootMutators|completeInterruptedCleanup BootReconcileOwnerTest.kt` → **0 hits**.  
The fold’s kdoc (`ZitroneApp.kt:1600–1618`) admits the old claim was false and points at the lambda API; the live call-site comment was not updated.  
**Why it matters:** Identical failure mode one hop up from the fix that claimed to close it.  
**Concrete fix:** Name `BurnCleanupOrderingTest` / `foldBootMutators`, or delete the pin claim.  
**Boundary:** **DEFERRABLE**.

### F4 — MEDIUM — Ordering pin test is only partially discriminating  
**Where:** `BurnPlanTest.kt` (`BurnCleanupOrderingTest`), ~lines 241–258  
**Defect:**  
`sweepResult = ResidueSweepResult.NO_MUTATION.also { order += "sweep" }`  
records “sweep” at **argument evaluation**, not when production’s sweep runs. Any function body that invokes the gate lambda after arg eval gets `gateReadAt == 1`. Call-site early capture  
`val absent = imageBearingProvenAbsent(); /* sweep */; fold(..., { absent })`  
is **not** caught.  
**What still holds:** API forces a lambda; production sequences sweep before `foldBootMutators`; incomplete cleanup → `SWEPT_NOT_DURABLE` is tested.  
**Concrete fix:** Pin production order with a harness that records real sweep-then-gate disk reads, or stop claiming the synthetic `.also` models the sweep.  
**Boundary:** **DEFERRABLE**.

### F5 — LOW — `DestroyFailed` message still wrong for six of seven steps  
**Where:** `VaultImageStore.kt:109` (`"a file survives"`); naming log at `BurnPlan.kt:181`  
**Defect:** Log correctly names all seven steps. The exception string remains image-centric for prefs/Keystore/notifications/etc.  
**Fix:** Carry step name into the exception, or use a neutral message.  
**Boundary:** **DEFERRABLE** (diagnostics, not deniability).

### F6 — INFO — Gate section of `SECURITY_MODEL` omits the notifications domain  
**Where:** `docs/SECURITY_MODEL.md:624–629` vs `BurnByteForByteGateTest` snapshot  
**Defect:** Gate compares `activeNotifications`; the model’s compared-domain list does not.  
**Boundary:** **DEFERRABLE**.

---

## Binding verdicts A–J

### A. WB-3 — one durability owner, three producers — **HOLDS**
- Single field: `AppContainer.durabilityHold` (`ZitroneApp.kt:326–354, 371–372`).
- Raised before obliterate: `runBurnWipe` (`1831–1840`); tested in `BurnDurabilityHoldTest`.
- Boot fold: reconcilers’ `MUTATED_NOT_DURABLE` + incomplete cleanup → hold (`562–616`, `1620–1631`).
- Routing only tests the boolean (`bootRoute` `1921`).
- No consumer needs a producer discriminator.
- **Fourth semantic producer hunt:** `completeInterruptedCleanup` is an additional *publisher path* into the same meaning, not a second field — design intact. No missing mutator that should raise and does not on the burn/boot paths reviewed.
- Failed-but-clean burn (unlinks + failed dirSync) still throws with hold raised (`BurnDurabilityHoldTest`) — **closure holds**.

### B. `destroy()` deviations — **ACCEPT both; keep downstream guard**
1. **Keys-first:** `obliterateLocked` S1 DEK then S2 bin (`VaultImageStore.kt:1170–1193`). Account-delete writes confirmed marker first (`1101–1122`) so crash re-enters idempotent destroy. Burn uses marker-free path; interrupted keys-first is completed by `completeInterruptedBurn` on `{bin present, dek proven absent}` (`1406–1420`). Argument holds; no need for `keysFirst` parameter.
2. **S4 proven absence:** `imageBearingFilesProvenAbsent()` / `Files.notExists` — fail-closed. Makes `{image survives, confirmed absent}` unreachable through this path.
3. **MainActivity defence-in-depth** (`1279–1309`): **must not** be deleted as dead code. Correctness here must not depend on S4 remaining strict three layers up.

### C. “Exists only if feature was used” — **no new member found in source**
Enumerated from production:
| Domain | Created when | Burn |
|---|---|---|
| Device-key alias | lazy `wrapDek` | wiped + `containsAlias` verify |
| Biometric aliases (`PREFIX*` + `LEGACY_ALIAS`) | enable | shared `isBiometricAlias` wipe/verify |
| `_androidx_security_master_key_` | startup every install | **left** (not an oracle) |
| Prefs keys in `zitrone_settings` | use | reset in place |
| Lazy prefs files (signal/auth/contacts) | session / `wipeLegacyPrefs` | proven unlink |
| Diagnostics log | boot record | erase + `isErased` |
| `cacheDir` | attachments/QR | `deleteTreeDurably` |
| Active notifications | `showNewMessage` | cancel + `noneActive` |
| Notification **channels** | `Application.onCreate` | **not** vault-use oracle; user channel prefs excluded honestly in gate |
| Databases / WorkManager / WebView | **none** in app source | gate asserts DB empty |

No WorkManager jobs, Room/SQLite, or extra Keystore families found. Residual class remains open **in principle** for future lazy artifacts — gate cannot close it; source enumeration remains required.

### D. Gate (`BurnByteForByteGateTest`) — **materially discriminating; residual limits stated honestly**
- Provisions via `createVaultAndPublish`; seeds per domain; `assertProvisioned` requires presence; per-domain negative controls; flush barrier; notification grant + seed; unreadable notifications → sentinel not `emptyMap()`.
- `@After` unconditional `burnVault` (not gated on `hasVault`) — correct for cross-test baseline.
- **Databases:** “assert empty” is honest coverage for “app creates none”; fires if a DB appears — not a vacuous equality.
- **Weakness:** green gate still ≠ complete oracle class (gate kdoc states this). Seeds that can silent-fail are largely caught by `assertProvisioned` (diagnostics/cache/prefs/notification).
- Negative controls would still discriminate if burn wiped *more*; if burn wiped *less* of a domain, main comparison should fail if seed is present.

### E. WB-1 — uniform failure + hold — **HOLDS**
- Failure: `lockError = UNIFORM_FAILURE` (`MainActivity.kt:984`); hold not lowered on throw (`runBurnWipe`).
- Success: process death; failure does not terminate (`BurnDurabilityHoldTest`).
- No path found that surfaces a distinguishable burn-failure UI message.

### F. WB-2 — `NonCancellable` — **HOLDS**
- Wipe on process scope with `NonCancellable + Dispatchers.IO` (`MainActivity.kt:950–958`). Composition cancel cannot abort mid-wipe.

### G. WB-7 — mutator ordering — **HOLDS for the three; fourth is ordered**
- 64-state enumeration includes `vault.dek.tmp` (`BurnReconcilerTriggersTest`).
- Reconcilers return `MUTATED_NOT_DURABLE` (not boolean false conflation).
- Fourth mutator after sweep via `foldBootMutators` — intent correct; pin quality see F3/F4.

### H. `vaultExists` initial `false` — **HOLDS for routing**
- Splash waits for `bootReconciled` then assigns (`MainActivity.kt:663–676`).
- Early readers (`biometricUnlockAvailable`, veil) only hide affordances when false — safe. Documented recreation edge is UI misclassification, not fresh-install-over-residue.

### I. Unit suite — **549 / 546 passed / 0 failures / 3 skipped** (reproduced). Gate not run here.

### J. Other / enumerations
- Prefs four-store enumeration matches `KeyStoreManager` companion constants — complete vs factory surface.
- Round-2 “complete enumeration” claims still accurate for stores; **phase placement claims in docs are not** (F1).
- Commit messages for 6a7f70f/9bddc89/87282ff match code for verify/Keystore/prefs move/wait; residual overclaim is docs and the still-wrong BootReconcileOwnerTest pin string.

---

## Round-6 focus items 1–8

| # | Verdict |
|---|---|
| **1** `runBurnPlan` + `verify()` | **Code correct.** `runCatching { verify }.getOrDefault(false)` fails closed; throw → DestroyFailed, not success. **No pin test** (F2). Boot and burn share the same `BurnStep.verify` — they agree on “done.” |
| **2** Keystore verifiers | **Repairs hold.** Shared `isBiometricAlias`; `keyMaterialExists` uses `containsAlias` + `getOrDefault(true)`; wipe returns postcondition. No third burn-path probe with the old usability/`existingKey` shape. (`keyExists` still uses `existingKey` for enable — not a burn postcondition.) |
| **3** Prefs → AFTER_IMAGE | **Code correct.** BEFORE_IMAGE remaining: diagnostics (user can clear / OS reinstall), cache (OS eviction), notifications (user dismiss) — innocuous-if-interrupted holds. **Docs still describe old order** (F1). |
| **4** Step naming | Log uses `step.name` for all seven. Exception text still generic (F5). |
| **5** 3s wait in `cancelAll` | **Fail-open:** wait expires → `noneActive()` reports truth → burn fails. **`noneActive` not weakened.** Max ~3s delay on that step only; acceptable for correctness; small duress UX cost if cancel lags. |
| **6** Gate notifications | Seed + grant + assert + control + sentinel look sound in source. Other seeds largely fail loud via `assertProvisioned`. |
| **7** Ordering pin | Partially repaired; call-site claim still false (F3); test weak (F4). Other “asserted by” claims checked: `BurnReconcilerTriggersTest` really asserts the three mutators. |
| **8** Invalidated invariants | **Yes:** SECURITY_MODEL + `burnPlan` kdoc prefs-before-image left standing after the phase move (F1). Sibling of prior born-wrong claims. |

### Seven postconditions — residue that still reports clean?

| Step | Verify | Surviving residue that still looks clean? |
|---|---|---|
| boot-diagnostics | memory empty ∧ file `notExists` | None material; IO fault fail-closed |
| plaintext-cache | top-level `listFiles` empty | None if tree empty; unreadable → fail |
| active-notifications | `noneActive` | None; unreadable → fail |
| vault-image | image-bearing proven absent | None (temps included) |
| biometric-material | `noAliasesRemain` / shared predicate | None of PREFIX* / LEGACY |
| vault-use-preferences | lazy files absent ∧ settings `all` empty | None of the four-store table |
| device-key | `!containsAlias` | None for that alias |

No residual verifier defects of the round-5 Keystore shape found in these seven.

---

## Summary table

| ID | Sev | BLOCKING? | One-line |
|---|---|---|---|
| F1 | HIGH | DEFERRABLE* | Docs reassert prefs-before-image after fix |
| F2 | HIGH | DEFERRABLE | Burn-path `verify()` unpinned by unit test |
| F3 | MEDIUM | DEFERRABLE | Call-site still “pinned by BootReconcileOwnerTest” (false) |
| F4 | MEDIUM | DEFERRABLE | Ordering pin test models arg-eval, not sweep |
| F5 | LOW | DEFERRABLE | DestroyFailed message still “a file survives” |
| F6 | INFO | DEFERRABLE | SECURITY_MODEL gate list omits notifications |

\*DEFERRABLE under the **post-burn ≡ fresh install** boundary (runtime property holds). Still **not** a clean merge for a security unit that has repeatedly failed on false claims.

---

## Bottom line

**Production wipe/verifier repairs in this delta are real and largely correct** (per-step verify, shared biometric predicate, `containsAlias`, wipe returns postcondition, prefs AFTER_IMAGE, fail-open notification wait, fail-closed DestroyFailed on false verify).  

**The checking/claim layer still exhibits the unit’s signature defect:** documentation and pins that do not track the code, and a highest-weight fix without a test that fails if reverted.  

**Not ready to merge** until F1–F3 are closed in round 7 (terminal). After that, an honest clean pass is plausible without further architecture change.
