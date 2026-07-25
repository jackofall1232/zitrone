I'll review the delta as an independent adversarial security reviewer — findings only, no fixes. Reading the protocol and the full functions in the claimed fix range.Reading the full production functions and related biometric store paths.Checking the `!ok` delete path and whether any slot term remains on Settings/lock surfaces.# Independent adversarial security review — PR-3 Unit 1 @ `7fbcd89`

**Range:** `c2d8a3c..7fbcd89` on `feat/0.9.2-vault-pr3-unit1-biometric-guard`  
**Scope:** findings only (no fixes)

---

## Round-2 claim verification

### HIGH — A/B enable-action timing oracle → **CLOSED**

**Source:** `MainActivity.startBiometricEnableFromSession` L484; prior slot pre-check `biometricEnableAllowedNow()` removed from `AppContainer`.

| Global state | A-session | B-session |
|---|---|---|
| `isEnabled()==true` (valid wrap) | `onResult(false)` **before** `newEncryptCipher` / prompt | identical |
| `isEnabled()==false` (no valid wrap) | IO keygen → `BiometricPrompt` → seal (first-enable-wins) | identical |

**Why closed:** refuse condition is `biometricStore.isEnabled()` = `load() != null` — process-global prefs, not `session.slotIndex`. Cross-slot refuse-while-wrap-exists can no longer be the sync-no-prompt vs allowed-prompt branch; both slots share the same branch.

**Action surfaces checked:**
- **Offer** (`onEnable` → `startBiometricEnable`): offer hidden when `alreadyEnabled` (below); if desynced and tapped, same sync refuse.
- **Settings** (`onToggleBiometric` L891–893): enable → same entrypoint; result callback re-reads `isEnabled()` (L893, L1095).
- **Invalidation re-offer** (L868–873 → L771): after clear, `isEnabled` false in both sessions; enable path identical.

**No remaining A/B-differing enable ACTION** for the same global wrap/platform state on prompt-vs-no-prompt, timing of that gate, error copy (none slot-specific), or toggle resync. Mid-flight belt refuse is only reachable after a prompt already shown under `isEnabled()==false` (first-enable path); that is not a cross-slot pre-prompt timing oracle.

---

### MEDIUM — destructive interrupted re-enable → **CLOSED**

**Source:** sole production `newEncryptCipher()` call at `MainActivity` L490; implementation `BiometricVaultKeyCipher.newEncryptCipher` L57–60 (`deleteKey()` then generate).

**Reachability:** L484 gates before L490. `isEnabled()==true` iff a **valid** wrap exists (`BiometricUnlockStore` L61). Therefore `deleteKey()` inside `newEncryptCipher` does not run while a working binding (valid wrap + its auth key) is the protected asset.

**Belt + `!ok` cleanup:**
- `enableBiometricFromSession` L565–567: per-slot never-repoint; returns `false` without `save`.
- `startBiometricEnablePrompt` L508–510: on `!ok`, `deleteKey()` only — clears the **fresh** key from this attempt’s `newEncryptCipher`, not a pre-existing binding (that binding could not have been present at the entrypoint gate when this keygen ran).
- Cancel path L513–515: same — deletes only the in-flight key.

**Scenario that previously destroyed a live binding** (re-enable while wrap exists → `newEncryptCipher` nukes key mid-prompt) is unreachable under the new gate.

---

### F2 — non-structural offer gate → **CLOSED (structural)**

**Source:** `VaultUnlockRouter.biometricEnrollOffered` L158–162:

```text
offerPending && sessionPresent && !alreadyEnabled
```

**Call site:** `MainActivity` L1088–1090 passes `container.biometricStore.isEnabled()`.

- No slot / session-identity parameter.
- Wrap present → offer hidden in **both** A and B.
- Tests assert hide-when-enabled: `VaultUnlockRouterTest` L149–150.

**Re-offer / onboarding not broken:**
- Invalidation: `disableBiometric()` clears wrap first (L576–578) → `isEnabled` false → re-offer after passphrase unlock (L771, L1088) still shows.
- Post-onboarding: create path sets `offerBiometricEnroll=true` with no wrap (L930) → shown.

---

## Binding checks (1–6)

### 1. Timing-oracle CLOSED
Proven above. Remaining enable behaviours for fixed global state do not branch on slot.

### 2. Destructive-refuse CLOSED
`newEncryptCipher` only after `isEnabled()==false`. Belt refuse does not seal; `!ok` deletes only the attempt’s key.

### 3. Never-repoint still holds

| Path | Effect |
|---|---|
| Wrap present, any session | Entrypoint refuse (L484) — no seal, no repoint |
| Mid-flight wrap + different session slot | Belt refuse (L565–567) — no `save` |
| Clear → enable | `boundSlotIndex()==null` → first-enable-wins (fresh bind) |
| Same-slot re-enable **without** clear | Entrypoint blocks (stricter than belt); only after clear/invalidation |

No production path repoints the single wrap to another slot without an intervening clear.

### 4. A/B render-identical
- Enroll offer: slot-free predicate + global `alreadyEnabled`.
- Settings toggle: `biometricEnabled` / `biometricAvailable` only (`SettingsScreen` L131–133) — no slot term.
- Lock affordance: `biometricUnlockAvailable = vaultExists && biometricEnabled && canAuthenticateStrong` (L840) — global.
- Hiding offer when enabled is slot-agnostic; post-invalidation and post-onboarding still work (see F2).

### 5. No new Critical/High/Medium defect

| Residual | Sev | Notes |
|---|---|---|
| Invalidation `clear()` fails → `isEnabled` stays true → offer hidden + entrypoint refuse → cannot re-enroll until wrap cleared | **LOW** | Soft biometric re-enroll lockout; passphrase remains. In-place same-slot overwrite (old path) is intentionally gone with MEDIUM fix. `disableBiometric` clears prefs before `deleteKey`; normal SP `apply` failure is rare. Safe-degraded, not vault lockout. |
| Entrypoint stricter than belt (same-slot refresh while wrap exists dead via UI) | **INFO** | Intentional; belt remains mid-flight / defense-in-depth only. |
| Host tests assert `alreadyEnabled` truth table; **no** test of `MainActivity` `isEnabled()` entrypoint vs prompt | **INFO** | Predicate covered; entrypoint wiring not host-tested. |
| Concurrent double-tap enable while `isEnabled==false`: two `newEncryptCipher` + `!ok`→`deleteKey` can delete the winner’s key while wrap remains | **INFO** (pre-existing) | Not introduced by this delta; not an A/B distinguisher; outside the claimed MEDIUM (re-enable-with-existing-wrap). |

TOCTOU on `isEnabled` between render/tap/seal on a single-user device: wrap can only appear via another enable completion; outcome remains slot-agnostic. Not an A/B oracle.

### 6. Holistic @ `7fbcd89`

| Question | Answer |
|---|---|
| (a) Can single wrap be repointed to another slot? | **No** (entrypoint + belt; clear-then-enable is fresh bind). |
| (b) Can disallowed/interrupted enable destroy an **existing** binding? | **No** for the round-2 MEDIUM case (no `newEncryptCipher` while valid wrap). Residual concurrent double-enable only (INFO, pre-existing). |
| (c) Any A-vs-B distinguisher on biometric surfaces (visibility / timing / behaviour / error)? | **No** on enable offer, enable action, Settings toggle, or lock affordance for the same global wrap/platform state. |

**Remaining Critical / High / Medium on this unit’s claims:** none.

---

## Findings list (adversarial)

No new **Critical / High / Medium** findings against `7fbcd89` Unit 1.

**LOW — failed invalidation clear soft-blocks re-enroll**  
- **Where:** interaction of `MainActivity` invalidation reconcile (L868–873) + `startBiometricEnableFromSession` L484 + `biometricEnrollOffered` `!alreadyEnabled` L162  
- **Mechanism:** if `biometricStore.clear()` does not drop a valid wrap, `isEnabled()` stays true → offer suppressed and enable entrypoint always returns false; same-slot overwrite no longer possible  
- **Scenario:** biometric key permanently invalidated; `disableBiometric()` fails to clear prefs; user gets re-enroll note but cannot complete re-enable until wrap is gone  
- **Class:** safe degraded biometric state (passphrase OK), not vault lockout  

**INFO — concurrent first-enable race can orphan wrap** (pre-existing `startBiometricEnablePrompt` L510)  
- **Where:** L484–496 + L506–511  
- **Mechanism:** two enables pass `isEnabled()==false`; later `deleteKey` on failed attempt can remove the key that backs a wrap just saved by the other  
- **Scenario:** double-tap enable before either seal completes  
- **Not** an A/B oracle; not a reopen of round-2 MEDIUM  

---

## Overall verdict

**CLEAN** — round-2 HIGH, MEDIUM, and F2 are **CLOSED** against source; never-repoint and A/B surface identity hold; no remaining Critical/High/Medium on Unit 1 @ `7fbcd89`.
