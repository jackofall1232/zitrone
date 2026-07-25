You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes, do NOT edit any file.

## Product & threat model
Zitrone: production Signal-Protocol E2E messenger with a plausible-deniability SECOND vault (slot B) + a "Pucker Burn" duress credential. Adversary: PHYSICAL DEVICE + FORENSICS + many forced/observed unlock attempts, and may COMPARE two vault sessions (A and B) to find a real-vs-decoy distinguisher. Assume crash / process-death / Activity-recreation (rotation) at ANY instruction. **Guilty-until-proven.**

Slot model: SLOT_COUNT=4; slot 0 = burn (reserved); slots 1..3 = vault pool. There is exactly ONE biometric wrap on the device: `{slotIndex, blob}` persisted by `BiometricUnlockStore` (slot = plaintext prefs int validated to the vault pool; blob = auth-gated ciphertext). Second-vault creation is ALREADY live on `main`.

## What this change is (PR-3 Unit 1 — biometric A-only guard, OQ4)
Locked decision OQ4: "one wrap, never repointed." Locked decision OQ-A(i): first-enable-wins (when no wrap exists, any session may bind it; there is deliberately NO durable real/decoy label anywhere). Unit-1 refinement: the A-only restriction must live ONLY on the write path — every biometric ENROLL UI surface must stay slot-agnostic so an A-session and a B-session render IDENTICALLY (a surface present in A but absent in B would itself be a distinguisher).

## Delta to review
Branch `feat/0.9.2-vault-pr3-unit1-biometric-guard` at commit `7670d00`, off `main` (`374bd44`). `git diff 374bd44..7670d00`. Read the FULL functions, not just hunks:
- `apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt` — new `boundSlotIndex()`; existing `load()`/`isEnabled()`/`save()`/`clear()`.
- `apps/android/app/src/main/java/com/zitrone/app/VaultUnlockRouter.kt` — new `biometricEnableAllowed(boundSlot, sessionSlot)` and `biometricEnrollOffered(offerPending, sessionPresent)`.
- `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` — `enableBiometricFromSession` (now fail-closes via `biometricEnableAllowed` before sealing); for context `unlockWithBiometric`, `disableBiometric`, and `SessionContainer.slotIndex`.
- `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt` — the enroll-offer render site (now routed through `biometricEnrollOffered`), plus the other enroll surfaces (Settings toggle `startBiometricEnable`/`disableBiometricThen`; the `offerBiometricEnroll`/`reofferBiometric` setters). Confirm NONE gate on slot/session identity.
- Tests: `VaultUnlockRouterTest.kt`, `BiometricUnlockStoreTest.kt`.

## Verify specifically (binding)
1. **Never-repoint invariant.** Prove `enableBiometricFromSession` can NEVER overwrite the single wrap to a DIFFERENT slot: `biometricEnableAllowed(boundSlot, sessionSlot)` returns false when `boundSlot != null && boundSlot != sessionSlot`, and the writer returns false BEFORE `withVaultKey`/`sealVaultKey`/`save` (seals nothing, writes nothing, wipes nothing to leak). Confirm no other caller writes the wrap without this guard. Confirm the guard reads `boundSlotIndex()` (a VALID wrap's slot) and that a malformed/out-of-range/burn-slot wrap reads as null → treated as "no binding" (first-enable-wins), never as a binding to a bogus slot.
2. **First-enable-wins correctness (OQ-A(i)).** No wrap → any session binds. Same-slot re-enable allowed (post-invalidation re-enroll). A disable(`clear()`)→enable cycle in a B session re-binds to B (clear then fresh bind, `boundSlotIndex()==null` at write) — confirm this is permitted and is NOT a repoint. Confirm nothing stores a durable real/decoy slot label.
3. **A/B render-identical (the distinguisher check).** Enumerate EVERY biometric enroll surface (post-onboarding offer; post-invalidation re-offer `reofferBiometric`→`offerBiometricEnroll`; Settings enable/disable toggle) and prove each is a pure function of GLOBAL state (`isEnabled()`, `canAuthenticateStrong`, the transient offer flag, `session != null`) with NO slot/session-identity term — so an A-session and a B-session with identical global state render IDENTICALLY. Is there ANY surface, timing, error text, or state readout that differs between an A-session and a B-session and could reveal which is the second vault? Consider: the write-refuse path returning false in a B session (is that reachable via UI, and if so is the outcome observably different from an A success?), the Settings toggle state, and the offer visibility.
4. **No new artifact / no leak.** `boundSlotIndex()` exposes only the slot that `load()`/`unlockWithBiometric` already read — confirm it adds no new persisted field and no plaintext beyond the existing wrap, and requires no biometric auth (it must not, by design). Confirm nothing logs the slot or blob.
5. **No regression.** `unlockWithBiometric` (opens the wrap's slot), `disableBiometric`/`clear()` (always allowed, slot-agnostic), invalidation re-offer, account-delete wipe — unchanged and correct. The guard must never BLOCK a legitimate same-vault enable or a first enable.
6. **Test adequacy.** Do the tests actually pin the invariants (bind/same/refuse truth table; boundSlotIndex null-cases; enroll-offer slot-independence)? What's the strongest UNtested path (e.g. the Keystore-backed `sealVaultKey`/`withVaultKey` seam is Android-only/inspection-verified — is anything host-testable left untested)?

## Output
Structured findings (SEVERITY, FILE+FUNCTION+line, MECHANISM, concrete SCENARIO). One-line overall verdict (CLEAN or the specific blocking finding). Report ONLY.
