I'll review HEAD and the round-4 delta as an adversarial security pass — findings only, no fixes.Reading the full biometric enable path and related guards at HEAD.# PR-3 Unit 1 — Round 5 adversarial review (HEAD `80639de`)

**Scope:** `dfba539..80639de` (material: `5cbb292` revert of Activity-scoped enable single-flight). Docs-only `80639de` ignored. Guilty-until-proven on A-only guard + residual concurrency blast radius.

---

## 1. Lockout GONE (round-3 single-flight reverted)

| Check | Result |
|--------|--------|
| `biometricEnabling` / `AtomicBoolean` on enable path | **ABSENT** at HEAD |
| Diff `dfba539..5cbb292` | Removes field + `compareAndSet` claim + `release` wrapper; restore direct `onResult` |

**Current enable entry** — `MainActivity.startBiometricEnableFromSession` L472–496:

1. `if (container.biometricStore.isEnabled()) return onResult(false)` (L484) — no claim flag  
2. `lifecycleScope.launch` → `newEncryptCipher()` → `startBiometricEnablePrompt(..., onResult)`  
3. No `compareAndSet` / no deferred `release`

**Synchronous throw after claim:** impossible — there is no claim. A throw from `authenticateCrypto` / prompt launch fails that attempt only; next call re-enters with a clean slate. Pre-revert stuck-`true` same-instance lockout is gone.

---

## 2. A-only guard INTACT

### (a) Never-repoint belt — `AppContainer.enableBiometricFromSession` L551–572

```565:567:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
        if (!unlockRouter.biometricEnableAllowed(biometricStore.boundSlotIndex(), session.slotIndex)) {
            return false
        }
```

`biometricEnableAllowed` L172–173: `boundSlot == null || boundSlot == sessionSlot`.  
On false: no `sealVaultKey`, no `save`. Sole production `biometricStore.save` call site is this function (L570).

### (b) Slot-agnostic pre-keygen gate — `startBiometricEnableFromSession` L484–490

`isEnabled()` (`load() != null`, store L61) is global. When any valid wrap exists, enable returns `onResult(false)` **before** `newEncryptCipher()` (which `deleteKey()`s at cipher L57–58). Refuse is identical for A and B sessions.

### (c) Slot-free enroll predicate — `biometricEnrollOffered` L158–162

`offerPending && sessionPresent && !alreadyEnabled` — no slot parameter.  
Call site L1088–1090: `isEnabled()` as `alreadyEnabled`. Settings toggle L891–896 / L123–134: `biometricEnabled` mirrors global `isEnabled()`. Lock affordance L840: `vaultExists && biometricEnabled && canAuthenticateStrong` — no slot.

**First-enable-wins / same-slot-after-clear:** `boundSlot == null` allows any session; after `clear()`, `boundSlotIndex()` is null → fresh bind (store tests L142–145).

---

## 3. Security invariants (source proof)

| Invariant | Proof |
|-----------|--------|
| **(a) Single wrap never repointed** | Write path only via `enableBiometricFromSession`; belt refuses `boundSlot != sessionSlot`. Settled wrap ⇒ `isEnabled()==true` ⇒ second enable never reaches `save` with another slot. |
| **(b) Disallowed enable side-effect-free** | Cross-session refuse at L484 before keygen when wrap present. Belt refuse returns false without `save`. Prompt failure path deletes the **in-flight** key only (L510, L514). |
| **(c) No destroy of pre-existing valid binding via enable** | `newEncryptCipher` only after `isEnabled()==false`. A settled wrap+key is never the start state of enable. |
| **(d) No A/B distinguisher on biometric surfaces** | Enroll / Settings / lock / enable-entry all keyed on global wrap presence or slot-free predicates; A-only is write-only. |

---

## 4. Residual enable-flow concurrency — blast radius

**Reachable (robustness, pre-existing, out of Unit-1 scope):**

- Overlapping enables both pass `isEnabled()==false` (double-tap / offer∥Settings / interrupted enable).  
- Later `newEncryptCipher` can delete the key that an earlier in-flight enable just bound → **orphan wrap** (prefs wrap present, key absent/mismatched).  
- Disable racing in-flight enable: `deleteKey` / `clear` can leave the same orphan class.  
- Failed mid-flight enable: key deleted, no wrap (or orphan if peer saved).

**Self-heal (source):**  
`startVaultBiometricUnlock` L413–417: wrap present but `cipherForDecrypt` → null ⇒ `UNAVAILABLE` → L868–873 `disableBiometricThen` → `clear` + `deleteKey`, re-offer note. Passphrase path untouched.

| Claimed non-reachability | Assessment |
|--------------------------|------------|
| **(i) Repoint wrap to another slot** | **Not reachable for a settled binding.** Entry gate blocks enable when wrap is visible; belt blocks cross-slot at seal. Only one live session; lock closes `withVaultKey` (`VaultSession` L252–254). No interleaving found that overwrites a committed, readable A wrap with B without an intervening `clear`. |
| **(ii) Destroy pre-existing valid binding** | **Not via enable start on a settled bind** (`isEnabled()==true`). Concurrent enables from empty can orphan each other’s *just-created* bind — not a pre-existing settled binding. |
| **(iii) A/B distinguisher** | Orphan / fail paths are slot-agnostic (global wrap, uniform UNAVAILABLE/clear/re-offer). |
| **(iv) Brick vault/passphrase** | No path ties passphrase open to biometric wrap integrity. |

**No blocking finding on (i)–(iv).** Residual is real robustness debt; security blast radius matches maintainer claim.

---

## 5. Tests pin guard invariants

| Suite | Coverage |
|-------|----------|
| `VaultUnlockRouterTest` L121–130 | never-repoint truth table: null→allow; same slot→allow; different→refuse |
| `VaultUnlockRouterTest` L134–150 | enroll predicate slot-free; `alreadyEnabled=true` hides offer |
| `BiometricUnlockStoreTest` L101–122 | `boundSlotIndex` null for absent/burn/malformed/cleared |
| `BiometricUnlockStoreTest` L126–145 | store∘router composition; clear-then-B is fresh bind, not repoint |

---

## Findings

**No blocking findings.**

| ID | Severity | Status |
|----|----------|--------|
| Round-3 enable lockout (`biometricEnabling` stuck true) | — | **Remediated by revert** |
| A-only never-repoint / slot-free surfaces | — | **Intact at HEAD** |
| Residual enable concurrency (orphan wrap) | N/A (tracked follow-up) | Bounded: self-heal; not (i)–(iv) |

---

## Verdict

**CLEAN** — round-3 single-flight fully reverted (lockout gone); A-only guard and security invariants hold at `80639de`; residual enable races stay non-security / self-healing.
