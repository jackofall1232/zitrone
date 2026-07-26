I'll review the biometric A-only guard delta as an adversarial security reviewer: full functions (not just hunks), binding invariants, and A/B render distinguishers.Next I'll read the full enable/unlock/disable paths and every enroll UI surface for slot-identity leaks.Investigating whether the write-refuse path is UI-reachable and whether enable can destroy an existing wrap via Keystore key rotation.Checking one more edge: whether a live B session can invoke enable while an A wrap exists without going through clear.# Adversarial Security Review — PR-3 Unit 1 (biometric A-only guard)

**Delta:** `374bd44..7670d00` on `feat/0.9.2-vault-pr3-unit1-biometric-guard`  
**Scope:** write-path never-repoint; first-enable-wins; A/B render-identical enroll surfaces; no new artifact; no regression; test pin

---

## Findings

### F1 — MEDIUM — End-to-end refuse is not side-effect-free (Keystore destroyed before guard)

| | |
|---|---|
| **Where** | `MainActivity.startBiometricEnableFromSession` ~L472–486; `BiometricVaultKeyCipher.newEncryptCipher` L57–60; then `AppContainer.enableBiometricFromSession` L564–566 |
| **Mechanism** | The only production enable entry always runs `newEncryptCipher()` **before** the never-repoint check. `newEncryptCipher()` begins with `deleteKey()`, wiping the sole auth-gated Keystore key that any existing wrap decrypts with. Only afterward does `biometricEnableAllowed(boundSlot, sessionSlot)` run. On refuse, `enableBiometricFromSession` correctly skips `withVaultKey` / `sealVaultKey` / `save`, then the prompt path calls `deleteKey()` again. Prefs wrap is left intact → `isEnabled()==true` with a dead Keystore key. |
| **Scenario** | Valid wrap bound to slot 1. Enable is invoked from a slot-2 session **without** a prior successful `clear()` (defense-in-depth / desync / leftover-wrap path — see F2). Key for slot-1 wrap is destroyed; guard returns false; prefs still advertise biometric on; biometric unlock fails until passphrase + re-enroll. Writer-local claim “seal nothing, write nothing” holds; **call-stack** fail-closed does not — it **destroys** the existing binding’s crypto root. Same-slot re-enable still works only because the post-guard seal rewrites the wrap. |

---

### F2 — LOW — “UI only offers enable when no wrap exists” is assumed, not structural

| | |
|---|---|
| **Where** | `MainActivity` offer setters L761, L920; enroll render L1076–1086; `VaultUnlockRouter.biometricEnrollOffered` L154–155; Settings `onToggleBiometric` L881–887 |
| **Mechanism** | Enroll visibility is `offerPending && sessionPresent` only — **no** `!isEnabled()` / no bound-slot term. Offer is armed with `if (canAuthenticateStrong) offerBiometricEnroll = true` (create) and invalidation re-offer after `disableBiometricThen` (clear is best-effort `runCatching`). Settings enable runs whenever the controlled switch fires `true` (i.e. local `biometricEnabled==false`), not when `biometricStore.isEnabled()` is re-checked at tap time. |
| **Scenario** | Residual wrap present (`isEnabled()==true`) while UI thinks enable is available: (a) create/onboarding offer after incomplete account-delete clear; (b) invalidation path where `clear()` fails but reconcile still sets `biometricEnabled=false` and `reofferBiometric=true`; (c) local `biometricEnabled` desynced false while prefs wrap remains. Then F1’s refuse path is reachable. Under **synced** normal use (wrap present → toggle ON; second-vault `Created` does **not** arm the enroll offer), cross-slot refuse stays hard to hit — but that is convention of call sites, not a structural gate. |

---

### F3 — LOW — Never-repoint is enforced only on one caller; `save()` stays an unguarded public write

| | |
|---|---|
| **Where** | `BiometricUnlockStore.save` L73–78; sole prod writer `AppContainer.enableBiometricFromSession` L567–569; `AppContainer.biometricStore` is public L164 |
| **Mechanism** | Invariant lives only in `enableBiometricFromSession`. `save()` does not consult `boundSlotIndex` / `biometricEnableAllowed`. Any future (or reflective) `biometricStore.save(...)` repoints without the guard. |
| **Scenario** | Today: no other prod caller (grep-confirmed). Residual: invariant is procedural, not store-structural. Not exploitable by UI alone in this delta. |

---

### F4 — INFO — Tests pin pure predicates; writer integration and call-stack fail-closed untested

| | |
|---|---|
| **Where** | `VaultUnlockRouterTest` L120–151; `BiometricUnlockStoreTest` L100–123 |
| **Mechanism** | Covered: `biometricEnableAllowed` truth table (null / same / different); `boundSlotIndex` null on absent / burn-0 / bad base64 / after `clear`; `biometricEnrollOffered` 2×2. **Not** covered: `enableBiometricFromSession` short-circuit (no `withVaultKey` / no `save` on refuse); clear→enable rebind as an explicit allowed cycle; that enroll UI does not pass slot into the offer predicate beyond signature; Keystore-before-guard ordering (Android-only). Enroll “A and B identical” asserts the **same** boolean twice — tautological, not a two-session render check. |
| **Scenario** | Strongest host-testable gap left: compose `boundSlotIndex()` + `biometricEnableAllowed` + a fake store around a test double of the writer ordering (refuse must not call `save`). `sealVaultKey` / `newEncryptCipher` remain device/inspection-only. |

---

### F5 — INFO — Cross-slot refuse vs same-slot success is a latent operational distinguisher (reachability-limited)

| | |
|---|---|
| **Where** | `enableBiometricFromSession` L564–571; enroll callback L1079–1082; Settings callback L883 |
| **Mechanism** | No slot-specific error copy (good). Outcomes still differ if refuse is hit with wrap bound to the other slot: same-slot enable refreshes wrap and biometric works; other-slot refuse (after F1) leaves biometric broken / enable false while offer is still dismissed (`offerBiometricEnroll=false` unconditionally). |
| **Scenario** | Only material if F2 residual makes enable runnable while wrap exists for the other slot. Normal synced UI: both vaults with no wrap → first-enable succeeds identically; both with wrap → toggle ON identically, enable not offered. |

---

## Verify checklist (binding)

### 1. Never-repoint invariant — **HELD (prefs write path)**

- `biometricEnableAllowed(bound, session)` is `bound == null || bound == session` (`VaultUnlockRouter` L165–166).
- `enableBiometricFromSession` returns `false` **before** `withVaultKey` / `sealVaultKey` / `save` when disallowed (L564–566).
- Sole production `biometricStore.save` is that function after the guard.
- `boundSlotIndex()` = `load()?.slotIndex` (L70): burn / OOR / malformed → `null` → first-enable-wins, never a bogus binding.
- **Caveat:** F1 — Keystore key may already be deleted by the enable entrypoint before this guard runs.

### 2. First-enable-wins (OQ-A(i)) — **HELD**

- No wrap → any `sessionSlot` allowed (tested).
- Same-slot re-enable allowed (tested).
- `clear()` then enable in B: `boundSlotIndex()==null` at write → B bind is a **fresh** bind, not a silent A→B overwrite without clear.
- No durable real/decoy label introduced; “A-only” in comments is equality-to-bound-slot, not a fixed real-slot id.

### 3. A/B render-identical — **HELD on reachable enroll surfaces**

| Surface | Slot/session identity in visibility? |
|---|---|
| Post-onboarding / post-create offer | No — `canAuthenticateStrong` + transient `offerBiometricEnroll`; render via `biometricEnrollOffered(offer, session!=null)` |
| Invalidation re-offer | No — `reofferBiometric` after global clear; same offer predicate |
| Settings toggle | No — `biometricEnabled` ← `isEnabled()`; `biometricAvailable` / `canAuthenticateStrong`; enable/disable callbacks ignore slot |
| Lock biometric affordance | Unchanged — `vaultExists && biometricEnabled && canAuthenticateStrong` |

Write-refuse under **normal synced** UI is not how B “tries enable while A is bound” (toggle already ON; second-vault create does not arm enroll offer). Disable→enable in B rebinds by design and is **not** a render distinguisher of A vs B with identical global state.

### 4. No new artifact / no leak — **HELD**

- `boundSlotIndex()` reads existing plaintext `KEY_SLOT` via `load()`; no new prefs key; no biometric auth (required for the write guard).
- No logging of slot/blob in this delta.

### 5. No regression — **HELD**

- `unlockWithBiometric` still opens `wrap.slotIndex` (unchanged).
- `disableBiometric` / `clear` still slot-agnostic and unrestricted.
- Invalidation re-offer and account-delete wipe paths unchanged.
- Guard does not block null-bound first enable or same-slot re-enable.

### 6. Test adequacy — **PARTIAL** (see F4)

---

## Overall verdict

**CLEAN** for locked OQ4 never-repoint (prefs), OQ-A(i) first-enable-wins, and A/B enroll render-identity on reachable UI — **with residual MEDIUM F1** (Keystore deleted before the write guard on the enable call chain).
