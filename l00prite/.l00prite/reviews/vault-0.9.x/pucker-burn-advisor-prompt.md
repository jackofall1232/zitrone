You are an INDEPENDENT SECURITY ADVISOR for a plausible-deniability messenger. This is an ADVISORY round — do NOT write a spec, do NOT write code, do NOT recommend an overall direction. Answer the five questions with reasoned positions and honest tradeoffs. You are BLIND to the other advisors; give your own independent analysis. Where a question says "verify against source," do so if you can read the repo (/root/zitrone) — inspect the actual code, don't assume; state what source shows.

## Product
Zitrone: a zero-knowledge, plausible-deniability E2E messenger. The relay stores only opaque ciphertext and can prove no linkage. The Android client hosts a plausible-deniability vault (multi-slot image; a passphrase can match a slot to unlock a vault). Standing principles: zero-knowledge relay; deliver-then-claim (never claim a property the platform can't deliver); no discoverable artifact that reveals vault count or armed/unarmed state; platform-honesty tiers.

## Feature under advisement: Pucker Burn (a duress "burn" credential) — setup + wipe
### Locked design decisions (do NOT relitigate these; reason WITHIN them, and flag if one is flawed under Q5):
- Slot 0 is reserved for the burn credential, excluded from blind vault placement.
- Slot 0 is sealed byte-identically to any vault slot, so an examiner can't tell armed from unarmed.
- `tryPassphrase` sweeps ALL slots including 0 (timing parity).
- A slot-0 match triggers a WIPE instead of an unlock.
- Works from the lock screen.
- Settings entry "Pucker Burn Password Setup" sits above "Delete Account" and DISAPPEARS once set.
- The burn credential is permanent and unchangeable once set, behind an actively-acknowledged warning.

### Already shipped in 0.9.2 (context):
- PR-1 store writer `attemptUnlockOrAdd` is burn-AWARE — it returns a `Burn` outcome on a slot-0 match but does NOT arm burn and performs NO wipe (the wipe is unbuilt; today `onBurn` is a fail-closed stub).
- IMAGE_VERSION 3; PR-2 triple-entry second-vault router; PR-3 biometric A-only guard; honest docs; biometric-enable atomicity.

### The D2c account-delete state machine (hardened over review rounds 13–16):
- Two-marker design: `vault.delete-intent` then `vault.delete-confirmed`.
- Outcomes: CONFIRMED_GONE / DEFINITE_FAILURE / AMBIGUOUS.
- Crash-durable marker retirement; `destroy()` is a whole-image delete (unlink `vault.bin` + `vault.dek` + fsync, wipe RAM DEK, biometric key/wrap removal).

### Verified source facts (grounding — same for all advisors; repo-capable advisors may re-verify):
- `IMAGE_VERSION = 3`; the on-disk BYTE LAYOUT is "unchanged from v2" (`crypto/vault/VaultImage.kt:26`).
- Slot 0 (`BURN_SLOT_INDEX = 0`) is already a full slot in the v3 image; `createVaultSlots` leaves slot 0 as uniformly-random FILLER on a fresh onboarding, "indistinguishable from any other slot" (`crypto/vault/VaultSlots.kt:127-128`); "armed simply means a passphrase can match slot 0, exactly what tryPassphrase already tests" (`VaultSlots.kt:27-28`).
- `attemptUnlockOrAdd` already returns `UnlockOrAdd.Burn` on a slot-0 match (`crypto/vault/VaultImageStore.kt:151`); the burn WIPE itself is not implemented.
- Repo-capable advisors: also read `docs/SECURITY_MODEL.md` and `docs/VAULT_ARCHITECTURE.md` for the current stated model.

## THE FIVE QUESTIONS (answer all five):

**Q1 — BURN-WIPE SCOPE.** What should a burn actually destroy? Local slots only (all vaults + slot 0 itself), or also the relay account(s)? Consider: a relay-account deletion is visible to the relay and creates a server-side event time-correlated with the wipe; it requires network, which a duress scenario may not have; and it partially collides with the hardened account-delete state machine. Argue for a position and state what it trades away.

**Q2 — BURN ↔ DELETE-STATE-MACHINE INTERACTION.** Should the wipe reuse the D2c delete machinery (markers, `destroy()`, teardown) or stay fully separate? Burn presumably wants most of what whole-image `destroy()` already does, but reusing that machinery means a NEW writer to state that took sixteen review rounds to stabilize. Argue for a position.

**Q3 — IMAGE_VERSION IMPLICATION.** Does arming slot 0 require any format change, or is it purely writing a slot within the existing v3 layout? Verify against source rather than assuming. If any change is needed, say exactly what and why.

**Q4 — POST-BURN APPEARANCE.** What should the app look like after a burn? "Factory install" is the stated goal, but: does it look like a genuinely fresh install (onboarding, no trace) or like an app that was wiped? These differ forensically; under coercion the first is what you'd want. What is actually achievable on Android given OS-level artifacts outside app control (backup state, package install time, account-manager entries, media, notification history, usage stats)? Be honest about what CANNOT be hidden — do not claim a property the platform can't deliver.

**Q5 — WHAT ARE WE MISSING.** Independent of Q1–Q4: what risk, edge case, design flaw, or unstated assumption in the locked Pucker Burn design would you flag that nobody has raised yet? Deliberately open-ended. Answer even if you have nothing alarming.

## Output
For EACH question Q1–Q5: your position + reasoning + tradeoff. For Q3, cite source. For Q5, list every distinct concern (a concern only you see still matters). Be concise but complete. Report ONLY your analysis.
