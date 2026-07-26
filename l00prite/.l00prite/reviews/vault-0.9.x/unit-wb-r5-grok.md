I'll perform an independent Round-5 security review of Unit W-B (Pucker Burn), starting from the project protocol and the fix delta at HEAD.I'll dig into the structural fix delta next: BurnPlan, the fourth boot mutator, and the gate rebuild.Gradle home is unwritable in this sandbox; I'll reroute the suite through a local home and keep digging into ordering claims.Tests reported UP-TO-DATE from cache — forcing a clean re-run for real counts.Checking whether the burn runner invokes `verify`, and confirming a few remaining edges.I have independent test counts and enough source-backed defects to close the review.

---

# Unit W-B Round 5 — Independent Security Review (Grok)

**Scope reviewed:** `2146cee..9bf1f1e` (structural fix delta) against production at `9bf1f1e`, with full-unit context from `main...HEAD` as needed.  
**Suite (I ran):** `547` tests / `544` passed / `0` failures / `3` skipped (`testDebugUnitTest`, forced re-run). Matches the claim.  
**Instrumented gate:** not run here (no emulator). CI claim noted as external only.

## READY TO MERGE: **NO**

The structural rewrite (burn table + fourth boot mutator) is the right shape for the round-4 defect, but the table’s load-bearing half is incomplete: **`runBurnPlan` never consults `verify()`**, so two steps can report success without proving their end state. That is the same “enumeration as comfort” failure mode this unit has repeated. One of those gaps leaves a **post-burn Keystore oracle** on upgraded devices.

The structural change is **one round old** and still needs another fix-and-re-review pass on the table runner and biometric postcondition before a hard cap at six. That is a finding, not a hedge.

---

## Findings

### F1 — HIGH · BLOCKING  
**`runBurnPlan` never runs `verify()`; biometric postcondition also misses `LEGACY_ALIAS`**

**Where**
- `BurnPlan.kt:130-134` — runner only invokes `action()`
- `ZitroneApp.kt:512-518` — biometric step
- `BiometricVaultKeyCipher.kt:141-144` — `noAliasesRemain()`
- `BiometricVaultKeyCipher.kt:146-157, 231-234` — wipe deletes `LEGACY_ALIAS`; probe does not

**Defect**
The table’s contract says each step carries a postcondition and that the burn “must never report success it cannot prove.” Source:

```130:134:apps/android/app/src/main/java/com/zitrone/app/burn/BurnPlan.kt
internal fun runBurnPlan(steps: List<BurnStep>) {
    require(steps.isNotEmpty()) { "an empty burn plan would report success having wiped nothing" }
    BurnPhase.entries.forEach { phase ->
        steps.filter { it.phase == phase }.forEach { it.action() }
    }
}
```

`verify` is used only by `completeInterruptedCleanup` (boot). The live burn path trusts actions alone.

Concrete break on biometrics:
- `PREFIX = "zitrone_vault_biometric_key_"`
- `LEGACY_ALIAS = "zitrone_vault_biometric_key"` (no trailing `_`)
- `deleteAllAliasesExcept` deletes `PREFIX*` **and** `LEGACY_ALIAS`
- `noAliasesRemain()` only checks `startsWith(PREFIX)` → **true while LEGACY survives**
- `wipeBiometricMaterial()` returns true if nothing throws; `deleteAlias` swallows exceptions; no re-stat

So a successful burn can leave the pre-0.9.2 alias, and boot completion will also treat the biometric step as already clean.

**Why it matters**  
Post-burn ≡ fresh install is the feature purpose. An orphaned Keystore alias is the same “exists only if used” class that already bit this unit twice. This is not hardening; it is a deniability break for upgrade-path devices.

**Fix**
1. After each step’s `action()`, `runBurnPlan` must require `verify()` (fail closed / throw).
2. `noAliasesRemain()` must treat `LEGACY_ALIAS` the same as `PREFIX*` (export or share the predicate with the wiper).
3. `wipeBiometricMaterial()` should return `noAliasesRemain()` (full set), not “no exception.”

**Boundary:** **BLOCKING**

---

### F2 — MEDIUM · DEFERRABLE (gate / DoD-8)  
**Notification domain added to the gate without seed or negative control**

**Where:** `BurnByteForByteGateTest.kt` — domain in snapshot (~175-180), baseline (~287-290), post-burn equality (~428-433); **missing** from `provisionThroughProduction`, `assertProvisioned`, and `the_snapshot_discriminates_in_every_domain_it_claims`.

**Defect**  
Round 4 fixed production (`active-notifications` step) and added a snapshot domain, then compared `fresh.activeNotifications` to `burned.activeNotifications` without ever posting a notification. Empty ≡ empty always passes. Wrong implementation that never cancels also passes.

Commit `09cda915` claims a “snapshot domain, and a baseline check” closed the notification HIGH. Baseline emptiness is not discrimination. The negative-control suite still has no notifications case.

**Why it matters**  
This is the exact non-discriminating-gate class rounds 2–3 already paid for. Production cancel looks correct (`NotificationManagerCompat.from(context).cancelAll()` is package-scoped; `noneActive` fail-closes), but DoD-8 / SECURITY_MODEL mechanical coverage does not prove the step.

**Fix**  
In provision: `showNewMessage` (or plant a status-bar notification), assert present in `assertProvisioned`, assert absent after burn, and add `assertDiscriminates` for `activeNotifications`.

**Boundary:** **DEFERRABLE** for production correctness if F1’s re-verify lands for this step; still **DoD-8 incomplete**.

---

### F3 — MEDIUM · DEFERRABLE  
**WB-7 “ordering pinned by test” is false in source**

**Where**
- `ZitroneApp.kt:585-594` claims pin by `BootReconcileOwnerTest`
- `unit-wb-invariant-table.md:135-136` same claim
- `BurnPlanTest.kt` `BurnCleanupOrderingTest` (lines 200-237)
- `BootReconcileOwnerTest.kt` — **zero** references to `completeInterruptedCleanup` / `burnPlan`

**Defect**  
`BurnCleanupOrderingTest` only passes `imageProvenAbsent = true|false` into the pure function. It does **not** read `startBootReconcile`’s call order. Moving `completeInterruptedCleanup` above `sweepOrphanedResidue` in production leaves every unit test green.

The **dependency argument in production is sound** (I re-derived it: gate is `imageBearingProvenAbsent()`, and the sweep is what can flip that in the same boot). The **proof claim is not**.

**Why it matters**  
This unit’s recurring defect is confident, checkable-looking prose over unpinned load-bearing structure. Shipping another “pinned by test” falsehood into the final rounds is process regression, not nitpicking.

**Fix**  
Pin production order with a real owner test (injectable mutator sequence, or source-structure test over the lambda body that fails if cleanup is hoisted). Correct the invariant table and call-site comment.

**Boundary:** **DEFERRABLE** (order is currently correct; the pin is not)

---

### F4 — LOW · DEFERRABLE  
**`Durability.KeystoreTransactional` on `active-notifications` is a typed lie**

**Where:** `ZitroneApp.kt:488-493`; type design in `BurnPlan.kt:86-95`.

**Defect**  
`Durability` deliberately has no “N/A.” Notifications have no Keystore transaction and no directory fsync. Stuffing them into `KeystoreTransactional` reintroduces the escape hatch the type was written to forbid, under a stronger-sounding name.

**Fix**  
Add an honest variant (e.g. `SystemServiceBestEffort` / `BinderSynchronous`) with kdoc that boot/re-verify is the durability story, not Keystore.

**Boundary:** **DEFERRABLE**

---

### F5 — LOW · DEFERRABLE  
**Stale “disk reconcilers re-derive the doubt” claim survived the round-4 correction**

**Where:** `ZitroneApp.kt:1766-1767` (`runBurnWipe` kdoc); also `BurnDurabilityHoldTest.kt:123-125`.

Sibling `burnVault` kdoc correctly retracts that claim and points at `completeInterruptedCleanup`. `runBurnWipe` still says mid-wipe process death is safe because “the disk reconcilers re-derive the doubt.” That was the born-wrong claim round 4 fixed.

**Fix**  
Rewrite to name `completeInterruptedCleanup` + residue signature, same as `burnVault`.

**Boundary:** **DEFERRABLE**

---

### F6 — INFO  
**Structural change is one-round-old and still under-exercised**

The burn-as-table + fourth mutator is the correct response to RAM-only hold + image-keyed reconcilers. In one round it already produced: F1 (runner ignores verify), F2 (gate domain without seed), F3 (false pin), F4 (type misuse). That is not “needs infinite review”; it is “do not hard-cap over an unfixed structural runner.”

---

## Binding verdicts A–J

### A. WB-3 — one durability owner, three producers  
**HOLDS in spirit; producer count prose is stale.**

- Hold means “some destructive mutation did not prove durable.”
- Routing uses only the boolean (`bootRoute` hold arm).
- No consumer needs a discriminator — **do not add one**.
- Round-6 HIGH is closed for the image obliterate path: raise-before-mutate in `runBurnWipe`, burn is a producer, failed-but-clean state covered by `BurnDurabilityHoldTest`.
- **Fourth mutator** folds into the sweep lambda’s `SWEPT_NOT_DURABLE` when `CleanupCompletion.INCOMPLETE` — still one field. Kdoc still says “THREE PRODUCERS” while cleanup is a fourth mutation source that can raise the hold. Update the count; do not add a discriminator.

**Hunt for missing publisher:** no fourth *field* needed. Incomplete cleanups that only touch non-image residue are now published via the fourth mutator — that was the point of the fix.

### B. `destroy()` two deliberate deviations  
**1. Keys-first (dek-then-bin): ACCEPT.**  
Confirmed marker is written before `obliterateLocked()`; destroy is idempotent under `DeleteIncomplete`. Keys-first never leaves DEK-without-image; image-without-DEK is cryptographically dead and completed by `completeInterruptedBurn`. Fallback `keysFirst` parameter is unnecessary unless someone insists on bin-first for account-delete only — shared primitive with keys-first is strictly safer.

**2. S4 proven absence (`Files.notExists`): ACCEPT.**  
Strict fail-closed. `{image survives, confirmed absent}` is unreachable through this path.

**Downstream MainActivity post-destroy routing: KEEP as defence in depth.**  
The comment at `MainActivity.kt:1279-1309` is correct: correctness here must not be coupled to S4 three layers up. **Do not delete as dead code.**

### C. “Exists only if the feature was used”  
**Class still open; F1 is a live member.**

Source enumeration of app-local durable state:

| Domain | Result |
|---|---|
| Prefs stores (4 via `KeyStoreManager`) | Enumerated; lazy three deleted; settings keys reset |
| Vault image / DEK / temps / markers | Obliterate + reconcilers |
| Device-key alias | Wiped + self-proving `deleteKeyMaterial` |
| Biometric aliases | Wiped; **verify incomplete (LEGACY)** — F1 |
| Boot diagnostics | Erased with memory-first |
| cacheDir | `deleteTreeDurably` |
| Active notifications | Cancel step present; gate unproven — F2 |
| Notification **channels** | Deliberately not reset (startup creates; user mods disclosed) |
| Databases / WebView / WorkManager | No app create sites found |
| `_androidx_security_master_key_` | Deliberately retained (startup every install) |

Gate green ≠ complete enumeration. Lazily created then correctly wiped artifacts remain lifetime oracles between create and burn — disclosed correctly in SECURITY_MODEL.

### D. Gate attack  
- Negative controls: solid for files / prefs (file + key) / keystore / databases / caches; **missing notifications** (F2).
- Databases “assert empty”: legitimate anti-vacuity, not fake coverage.
- `@After` unconditional `burnVault(terminate={})`: correct; avoids residue on both sides of next baseline.
- Baseline now checks settings content + notifications emptiness: good for contamination; still can pass over never-posted notifications.
- `terminate={}`: weaker than production (stated). Standing limit.
- SECURITY_MODEL gate section still lists files/prefs/db/cache/keystore; does not claim notification comparison as gated — good. The **test itself** overclaims via empty≡empty.

### E. WB-1  
**HOLDS.** Failed burn → `BurnCompletion.Failed` → `UNIFORM_FAILURE`; hold raised before obliterate and not lowered on throw (`runBurnWipe`). Success kills process (no distinguishable onboarding animation). Failure does not terminate.

### F. WB-2  
**HOLDS.** Wipe runs on process `container.scope` under `NonCancellable + Dispatchers.IO`. Composition cancellation cannot abort mid-flight. Coercer-controlled rotation cannot cancel the wipe.

### G. WB-7  
**Revised claim mostly holds; pin does not (F3).**  
- Three image-bearing mutators: exclusivity over **64** states including `dek.tmp` — re-derived from predicates and test materialization. Non-vacuity guard present.  
- Reconcilers return tri-state; caller folds `MUTATED_NOT_DURABLE` without trusting a bare `false`.  
- Fourth mutator ordered last, co-fires with sweep by design — **correct in source**, **not test-pinned**.

### H. `vaultExists` initial `false`  
**HOLDS for routing.** Splash waits on `splashFinished && bootDone` before assigning. Comment correctly admits non-routing readers (`biometricUnlockAvailable`, lemon-drop veil) observe `false` safely. Rotation-with-live-session UI misclassification is tracked, not a fresh-install-over-residue path.

### I. Unit suite  
**I ran it.**  
`547` total / `544` passed / `0` failures / `3` skipped.  
Command used local Gradle home + `JAVA_TOOL_OPTIONS=-Djna.tmpdir=…`.

### J. Other / enumerations / commit honesty  
- `09cda915` “gate asserts over [the table]” — overstated; gate does not iterate `burnPlan`.  
- “Pinned by BootReconcileOwnerTest” — **false** (F3).  
- “Notification … snapshot domain and baseline check” closes the bug in production, **not** in gate discrimination (F2).  
- Interruption-safety enumeration of seven steps: phase placement is sound for crypto vs non-crypto; “innocuous as OS cache eviction” is **overstated for preferences** (settings reset is a real tell). SECURITY_MODEL now discloses that — good. Tradeoff accepted: better than bricking the vault or leaving post-image residue without boot completion.  
- WB-3 “three producers” not re-derived after fourth mutator — minor prose drift.

---

## Round-5 structural attack (items 1–8)

| # | Verdict |
|---|---|
| **1. Burn table / phase order** | Phase order argument **sound for Keystore vs image**. BEFORE_IMAGE for diagnostics/cache/notifications is correct. Prefs-before-image is a deliberate tell (reset settings on intact vault), disclosed — prefer over post-image residue blindness. `active-notifications` BEFORE_IMAGE: correct (no crypto brick risk). Several `verify`s characterise end state well **when used**; burn path ignores them (F1). `KeystoreTransactional` on notifications is dishonest (F4). |
| **2. Fourth boot mutator** | Marker-free signature is **mostly sound**. `{image proven absent ∧ residue}` also arises after **account delete** / partial create hygiene — cleanup then deletes diagnostics/cache/prefs/aliases. Acceptable: user was told (delete) or never had a vault; aligns with fresh-install baseline. Worst case of wrong **true** `imageProvenAbsent`: would delete against live vault — mitigated by `Files.notExists` fail-closed gate. Worst case of wrong **false**: skip cleanup; routing still withholds onboarding unless proven absence (Residence / bootRoute). Interacts OK with delete markers (`reconcileOrphanedBurnMarkers` still refuses confirmed-present). **Order dependency correct; pin claim false (F3).** |
| **3. Notifications** | `cancelAll` is this-app only. `activeNotifications` for own package needs no listener permission (API 23+; min 26). `noneActive` fail-closes. Production step is real. Gate does not prove it (F2). |
| **4. Narrowed claims** | Process-death wording in `burnVault` / SECURITY_MODEL is now accurate (userspace queue, not kernel). “Defence in depth, not the proof” is accurate **given** ordering + `completeInterruptedCleanup`. Stale sibling in `runBurnWipe` kdoc (F5). |
| **5. `dek.tmp` / 64 states** | Enumeration complete for the six presence bits as written; exclusivity holds over the doubled space with non-vacuity. |
| **6. Gate changes** | Baseline settings content + notifications: good contamination checks. Notification discrimination still vacuous (F2). `terminate={}` limit unchanged and stated. |
| **7. Non-discriminating assertions** | F2 is the live instance in this delta. F1’s `noAliasesRemain` is a non-discriminating **postcondition** (passes with LEGACY present). |
| **8. Enumerations / invariant table** | WB-7 revised honestly in the table body; the “pinned by test” sentence is still false. WB-3 producer count not revised. No other invalidated invariant found that still claims the pre-round-4 world. |

---

## Summary table

| ID | Sev | Blocking? | One-line |
|---|---|---|---|
| F1 | HIGH | **BLOCKING** | Burn runner ignores `verify`; biometric LEGACY can survive a “successful” burn and boot completion |
| F2 | MEDIUM | Deferrable | Gate notification domain empty≡empty |
| F3 | MEDIUM | Deferrable | Fourth-mutator order claimed pinned; tests don’t pin production |
| F4 | LOW | Deferrable | Notification durability typed as KeystoreTransactional |
| F5 | LOW | Deferrable | Stale re-derive claim in `runBurnWipe` / hold test kdoc |
| F6 | INFO | — | Structural delta needs fix+re-review of runner/postconditions |

---

## Merge decision

**NOT READY TO MERGE.**

Fix F1 before any claim that the burn table closed interruption safety. Re-verify after action is what makes the table a control structure rather than documentation-with-lambdas. F2–F5 can ship as follow-ups only after F1; I would still land F2 in the same fix delta because this unit has already shipped “green gate over missing seed” twice.

**What holds (do not re-litigate without new evidence):** keys-first destroy, S4 proven absence + keep downstream defence-in-depth, WB-1 uniformity, WB-2 NonCancellable, raise-before-mutate hold, 64-state exclusivity for the three image mutators, marker-free fourth mutator *idea*, process-death wording as userspace drain, `vaultExists` false initializer for routing.
