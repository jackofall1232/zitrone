I'll verify each documentation claim against the shipped code on this branch—starting with the delta and the cited sections, then the implementation.Next I'll read the cited doc sections and the code that should back them: `tryPassphrase`, timing-parity tests, Android `destroy()`, and related status blocks.I'll pull the full doc sections and the matching implementation for unlock, destroy, biometric, and timing-parity.I'll read the core implementations next—`tryPassphrase`, timing tests, biometric guard, and destroy paths—to check each claim against code.I'll verify web/desktop single-slot destroy, Android whole-image destroy, biometric, and pending-delete against the implementation.## 1. Timing parity (derivation-count guarantee; wall-clock as consequence)

**CONFIRMED-ACCURATE**

- Code: `packages/crypto/src/vault.ts` `tryPassphrase` (lines 167–188) and Android `VaultSlots.kt` `tryPassphrase` (lines 211–241) derive+unwrap **every** slot with no break on match.
- Tests pin **deriver call count** only: `onion-vault.test.ts` (matched/missed both `SLOT_COUNT`); Android `VaultPrimitiveTest.kt` (`tryPassphrase_derivesEverySlot_*` asserts `counter.calls == SLOT_COUNT`). No wall-clock/stopwatch measurement.
- Docs (§3.1, §3.2, `SECURITY_MODEL` Timing-parity bullet) state that tests pin equal per-slot derivation count; wall-clock constancy is the practical consequence of Argon2id dominance, not a separately measured guarantee.
- Does **not** understate a real tested guarantee: tests only pin operation count; equal unwrap work is real in code and is described as part of the sweep, not as a separate measured wall-clock claim.

---

## 2. Per-slot delete scoped (web/desktop vs Android)

**CONFIRMED-ACCURATE** (contradiction with Android single-slot delete is resolved)

- Web/desktop: `apps/web/src/lib/storage.ts` `destroyVaultSlot` overwrites one slot+payload with random filler; image size unchanged.
- Android: `VaultImageStore.destroy()` deletes whole image (`vault.bin`/`vault.dek` + temps); `destroyVaultForAccountDeletion()` only calls that — no single-slot path.
- Status: Android rows / implementation-status: per-vault / single-slot destroy **not** shipped; whole-image only. Matches on-disk bullet scoping.

---

## 3. Remaining claims across the four files

**REAL residuals (overclaim / internal contradiction):**

| SEVERITY | FILE+line | Claim | What code/docs actually do |
|---|---|---|---|
| **BLOCKING** | `docs/VAULT_ARCHITECTURE.md` ~L307 | “Android vault runtime **(not yet built)**” gates cross-vault notification verification | Android everyday vault runtime is built (0.9.1) and second-vault create is built (0.9.2); contradicts same-file status table (L21–22). |
| **BLOCKING** | `docs/VAULT_ARCHITECTURE.md` ~L343–345 | Honest state is “crypto primitive built on web; **Android runtime pending**” / do not imply shipped Android vault | Same contradiction: status table and body already claim Android runtime + second vault shipped. |

**Otherwise checked (item 3 list) — CONFIRMED-ACCURATE:**

- **Capacity up to three:** Android `SLOT_COUNT=4`, pool `1..3` (`BURN_SLOT_INDEX=0`); `SECURITY_MODEL` Android-scoped.
- **Biometric first-enable-wins / never repointed / others passphrase-only:** `VaultUnlockRouter.biometricEnableAllowed`; `enableBiometricFromSession` fail-closed write path.
- **Create-persistence residual:** `attemptUnlockOrAdd` create branch persists (seal, outer GCM, atomic write); docs do not claim wall-clock identity with unlock.
- **Fail-closed pending-delete:** create path requires both markers absent via `Files.notExists`; else `UnlockOrAdd.Rejected` after same throwaway payload GCM.
- **Not-shipped:** per-vault destroy (Android whole-image only); Pucker Burn setup/wipe (`onBurn` fail-closed stub; no setup UX); no present-tense burn permanence.

No understatement of a stronger real guarantee on those topics.

---

## Overall verdict

**BLOCKING:** `docs/VAULT_ARCHITECTURE.md` still states Android vault runtime is “not yet built” / “pending” (§7 ~L307, §9 ~L343–345), contradicting the same file’s status table and shipped code.
