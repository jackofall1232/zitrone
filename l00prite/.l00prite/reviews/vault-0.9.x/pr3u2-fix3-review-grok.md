I'll verify the 0.9.2 second-vault docs against shipped code: read the delta and surrounding doc paragraphs, then confirm each round-3 claim against the unlock/create paths.Next I'll read the full surrounding doc paragraphs and the unlock/create code paths they claim to describe.Reading the unlock/create implementation and biometric guards against the five round-3 claims.Checking the fused unlock/create path and pending-delete marker checks in the create branch.Checking remaining absolute-timing claims, burn/not-shipped language, and biometric write-path enforcement.## Round-3 confirm (docs vs shipped code)

### 1. §3.1 / §3.2 timing parity (sweep-scoped)
**CONFIRMED-ACCURATE**

- Sweep: `tryPassphrase` derives + unwraps every slot, records first match, **no break** — `packages/crypto/src/vault.ts` L167–188; Android `VaultSlots.kt` L211–241; fused path always runs that sweep — `VaultImageStore.attemptUnlockOrAdd` L662–663.
- Success branch inherent: match keeps key + `openPayload` → `Unlocked`; miss → `Rejected` — L694–707, L783–790.
- A/B match symmetry at unlock: same sweep + same unlock UI path; contents after open called out as inherent — matches code/docs.
- Create disk residual outside ordinary unlock — create branch L734–779.

No remaining claim that the sweep hides match-vs-miss outcome or opened contents.

### 2. SECURITY_MODEL “Timing parity” bullet
**CONFIRMED-ACCURATE**

- Wall-clock identity scoped to KDF+unwrap **sweep** only — `tryPassphrase` as above; structural test: equal `SLOT_COUNT` deriver calls match vs miss — `packages/crypto/src/onion-vault.test.ts` L80–94; Android `VaultPrimitiveTest` same shape.
- Residuals named outside sweep: post-decrypt parse (“one residue” L448–450) and Android create persist (L484–487, create branch write path) — both real and outside the sweep.
- Does **not** understate tested parity: tests prove equal sweep work, not whole-attempt wall-clock identity including create I/O.

### 3. Pending-delete `Files.notExists` / `&&`
**CONFIRMED-ACCURATE**

Create branch only:

```724:726:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
                        val markersAbsent =
                            Files.notExists(deleteIntentFile.toPath()) &&
                                Files.notExists(serverDeletedFile.toPath())
```

Kotlin `&&` short-circuits → 1 or 2 stats (“up to two”). Plain reject path has no marker stats (L783–790). Fail-closed → `Rejected` with same heavy crypto budget (L727–732; test L410–415).

### 4. README headline / §6 compelled-disclosure wording
**CONFIRMED-ACCURATE**

- README: “fixed no-early-exit unlock-attempt work budget” — matches no-early-exit sweep, not absolute identical full-attempt timing.
- §6: same work-budget phrasing; “nothing **in the image** distinguishes one identity from two” correctly scopes the snapshot claim (fixed image, no stored count, blind overwrite).

### 5. Remaining claims (capacity / biometric / residuals / not-shipped / understatement)
**CONFIRMED-ACCURATE** — no remaining real overclaim; no understatement of a real guarantee.

| Topic | Code |
| --- | --- |
| Capacity up to three | `SLOT_COUNT = 4`, `VAULT_SLOT_RANGE` = 1..3 (`KeySlot.kt` L37, `VaultSlots.kt` L36–39) |
| Biometric first-enable-wins; never repointed while wrap exists; others passphrase-only | `biometricEnableAllowed` (`VaultUnlockRouter.kt` L172–173); write-path refuse (`ZitroneApp.enableBiometricFromSession` L555–567); single wrap |
| Create-persistence residual | Create branch outer GCM + `atomicWrite` + dirsync; docs do not claim wall-clock identity to unlock |
| Timing = sweep budget only | Consistent after round 3; Android still documents full **heavy crypto** parity for marker-reject vs wrong-pw without claiming marker-stat wall-clock parity |
| Fail-closed pending-delete | Marker-present create → `Rejected`, no clear/write (L712–732) |
| Not shipped | Per-vault destroy absent (§3.4 / status table); Pucker Burn setup/wipe not user-settable / wipe stub (SECURITY_MODEL L539–544); no present-tense burn permanence |

---

**Overall verdict: CLEAN**
