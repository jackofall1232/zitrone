I'll review the delta adversarially against the locked OQ4 invariants—read-only, findings only, no fixes.Reading the full enable path and related guards next.Tracing enroll surfaces, all `save` callers, and the full enable/disable/invalidation paths.# Adversarial security review — PR-3 Unit 1 round 2 (`7670d00..c2d8a3c`)

**Scope:** biometric A-only enable guard (OQ4 / OQ-A(i)). Findings only. No fixes proposed.

---

## Round-1 closure (source-verified)

### F1 — MEDIUM — side-effect-free refuse — **CLOSED**

**Mechanism (proved):**

Call chain order in `MainActivity.startBiometricEnableFromSession` (lines 472–490):

1. `container.biometricEnableAllowedNow()` (line 478)  
2. **only if true** → `lifecycleScope.launch` → `biometricCipher.newEncryptCipher()` (line 484)  
3. then `startBiometricEnablePrompt`  

`AppContainer.biometricEnableAllowedNow()` (ZitroneApp.kt:560–563):

- `session.value?.slotIndex ?: return false` (no-session fail-closed)
- `unlockRouter.biometricEnableAllowed(biometricStore.boundSlotIndex(), slot)`

`BiometricVaultKeyCipher.newEncryptCipher()` (lines 57–58) always `deleteKey()` then keygen — but it is **not** reached when the pre-check is false.

**Disallowed enable (bound slot ≠ session slot):** returns `onResult(false)` at line 478. Touches: no Keystore, no prefs, no seal, no prompt. Existing Keystore key + prefs wrap both survive.

**Belt still fail-closes:** `enableBiometricFromSession` (ZitroneApp.kt:578–579) re-runs the same predicate; on false: return false, no `sealVaultKey`, no `save`.

**`onError` / `!ok` `deleteKey()` (MainActivity.kt:504, 508):** reachable only after a **true** pre-check and after `newEncryptCipher()` has already replaced the alias. Those deletes target the **freshly generated** enable key (cleanup of an allowed attempt that later failed / was cancelled / hit belt after session change) — **not** a path that runs on a pure cross-slot refuse. No remaining ordering where refuse is preceded by `deleteKey`/keygen on the disallowed path.

---

### F2 — LOW — refuse reachable via desync — **CLOSED (destructive aspect)**

A reachable cross-slot refuse is now a clean no-op (same evidence as F1).  
Residual (not a reopen of F2’s destroy bug): when refuse is reachable, **action** still differs by session (instant `onResult(false)` vs prompt). That is write-path-only design, not a new Keystore/wrap destroy.

---

### F3 — LOW — `save()` unguarded public primitive — **CLOSED (as claimed: doc + sole guarded caller)**

`BiometricUnlockStore.save` KDoc (lines 72–78) states invariant is **not** enforced inside `save`.

**Production `save` callers (grep):** only  
`AppContainer.enableBiometricFromSession` → `biometricStore.save(...)` (ZitroneApp.kt:583), after `biometricEnableAllowed` (578).

Tests call `save` directly (host tests only).

Doc claim matches current source. Residual footgun if a future production caller skips the guard — not a live defect in this delta.

---

### F4 — INFO — weak / tautological tests — **CLOSED**

- **Added:** `BiometricUnlockStoreTest` composition test — real store `boundSlotIndex()` + `VaultUnlockRouter.biometricEnableAllowed`: no wrap allow; bound-1 same-slot allow / other-slot refuse; clear→B allow. Not pure-predicate-only; not tautological.
- **Removed:** double-assert of the same boolean in `VaultUnlockRouterTest` enroll visibility test; truth table of the slot-free predicate remains.

**Gap (INFO only):** no test asserts **entrypoint ordering** (`biometricEnableAllowedNow` before `newEncryptCipher`). That invariant is source-proved, not automated.

---

## Binding checks

### 1. Side-effect-free refuse

| Claim | Verdict |
|--------|---------|
| Pre-check before `newEncryptCipher` | **Yes** (MainActivity.kt:478 before 484) |
| Disallowed touches nothing | **Yes** |
| `biometricEnableAllowedNow` uses current session + `boundSlotIndex`; false on no-session | **Yes** |
| Belt still fail-closes | **Yes** (enableBiometricFromSession:578–579) |
| Any destructive step before refuse on disallowed path? | **No** |
| Prompt `deleteKey` only after allowed pre-check? | **Yes** |

### 2. A/B render-identical

| Surface | Slot in visibility? |
|---------|---------------------|
| `biometricEnrollOffered(offerPending, sessionPresent)` | No slot param (VaultUnlockRouter.kt:154–155) |
| Offer UI (MainActivity.kt:1080–1088) | Slot-free predicate only |
| Settings toggle | `biometricEnabled` / platform availability — global wrap presence, not session slot |

Pre-check is on **tap** only. Disallowed path: same `onResult(false)` as other enable failures; no slot-specific copy in this delta. Enroll visibility **unchanged** by the delta.

### 3. First-enable-wins / same-slot re-enable

`biometricEnableAllowed(bound, session) = bound == null \|\| bound == sessionSlot` (VaultUnlockRouter.kt:165–166).

| Case | Pre-check |
|------|-----------|
| No wrap | **Allowed** (any session slot) |
| Same-slot re-enable | **Allowed** (key regen + wrap refresh) |
| Clear then enable in B | **Allowed** (fresh bind; not repoint) |
| Bound A, session B | **Refused** |

Legitimate enables are not blocked by the pre-check.

### 4. No new defect from the fix

| Concern | Assessment |
|---------|------------|
| TOCTOU session change after pre-check | Belt refuses seal; `!ok` → `deleteKey` of **new** key. Can orphan a wrap if pre-check was same-slot re-enable then session flipped (key already deleted by `newEncryptCipher`). **No repoint.** Pre-existed on the allowed path; not introduced as a cross-slot destroy. |
| Onboarding enable / Settings / invalidation re-offer / disable / unlock / account-delete | No delta on those paths except shared entrypoint pre-check; disable still `clear`+`deleteKey`; destroy still clears wrap+key |
| `save()` sole production caller | **Confirmed** |
| Tests assert invariants | Composition yes; entrypoint ordering no (INFO) |

### 5. Holistic (Unit 1 @ `c2d8a3c`)

| Question | Answer (from source) |
|----------|----------------------|
| Can the single wrap be **repointed** to another slot? | **No** — only `save` after `biometricEnableAllowed`; cross-slot cannot pass. Clear→enable is a new bind. |
| Can a **disallowed** enable have destructive side effects? | **No** — refuse before `newEncryptCipher`. |
| A-vs-B distinguisher on **biometric enroll visibility**? | **No** for the gated affordances reviewed (slot-free enroll offer; Settings mirrors global `isEnabled`). |
| Action-path success vs refuse when wrap still bound? | Yes if that path is reached (write-path-only OQ4 by design); normal flow offers enable when no wrap / after clear. |

**Remaining Critical / High / Medium in this delta:** **none**.

---

## Residual notes (below Medium; not blocking)

**INFO — action timing under desync**  
If wrap remains bound to slot X and enable is somehow offered while session is Y ≠ X (e.g. invalidation cleanup failed yet UI re-offered), X gets a BiometricPrompt path; Y gets immediate `onResult(false)`. Side-effect-free; residual oracle shape of write-path-only policy (pre-fix was post-auth success/fail). Not a wrap repoint or Keystore destroy.

**INFO — F4 coverage hole**  
No automated assertion that refuse runs before `deleteKey` inside `newEncryptCipher`.

---

## Overall verdict

**CLEAN** — F1–F4 closed against source; no remaining Critical/High/Medium; wrap never repointable; disallowed enable is side-effect-free.
