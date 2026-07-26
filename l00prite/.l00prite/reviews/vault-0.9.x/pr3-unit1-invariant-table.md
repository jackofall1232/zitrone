# PR-3 Unit 1 — WRITER/READER invariant table: the single biometric wrap

Built BEFORE code (per standing rule for any durable multi-reader signal). The signal is the ONE
biometric wrap record `{slotIndex, blob}` persisted by `BiometricUnlockStore` in the settings
SharedPreferences (`KEY_SLOT` = plaintext int, validated to the VAULT pool 1..SLOT_COUNT-1;
`KEY_BLOB` = base64 of the constant-size auth-gated ciphertext). Exactly ONE record exists at a time.

## Actors

| Actor | Path | Reads | Writes | Auth to read slot? |
|---|---|---|---|---|
| `load()` / `isEnabled()` | `BiometricUnlockStore` | `KEY_SLOT` (int), `KEY_BLOB` (b64) | — | NO — slot is plaintext metadata; blob is opaque until BiometricPrompt |
| `boundSlotIndex()` **(NEW)** | `BiometricUnlockStore` | `KEY_SLOT` via `load()?.slotIndex` | — | NO |
| `unlockWithBiometric` (reader) | `AppContainer` | `load().slotIndex` → `unlockWithKey(key, slot)` | — | blob needs BiometricPrompt; slot does not |
| `enableBiometricFromSession` (WRITER) | `AppContainer` | `boundSlotIndex()` **(NEW read)** | `save({session.slotIndex, blob})` | — |
| `disableBiometric` / invalidation / account-delete | `AppContainer` | — | `clear()` | — |

## Invariants (MUST hold after Unit 1)

1. **Single wrap, NEVER repointed (OQ4, in the user's words "one wrap, never repointed").**
   `enableBiometricFromSession` may write ONLY when: (a) no wrap exists (`boundSlotIndex()==null`) →
   first-enable binds it (OQ-A(i) first-enable-wins), OR (b) an existing wrap is bound to the SAME
   slot as the session (`boundSlotIndex()==session.slotIndex`) → a same-vault re-enable/refresh.
   A write from a session whose slot ≠ the bound slot is REFUSED fail-closed: return false, write
   NOTHING, do not seal, do not repoint. (Defense-in-depth: unreachable via current UI, which only
   surfaces "enable" when no wrap exists — but the invariant is enforced at the write layer, not
   assumed from UI.)

2. **No new durable artifact.** `boundSlotIndex()` reads the slot that `load()`/`unlockWithBiometric`
   already read; it adds NO new persisted field and NO plaintext beyond what the wrap already implies.

3. **A/B render-identical (Unit-1 refinement, user).** The A-only restriction lives ONLY in the
   write path (invariant 1). NO enroll surface is gated on slot/session identity: the post-onboarding
   offer, the post-invalidation re-offer, and the Settings toggle are all pure functions of GLOBAL
   state (`isEnabled()`, `canAuthenticateStrong`, the transient offer flag, `session != null`) — none
   takes a slot. An A-session and a B-session with identical global state render IDENTICALLY at every
   enroll surface. The only observable A/B difference is on the write path, never in the UI.

4. **Clearing is always allowed, slot-agnostic.** `clear()` (disable / invalidation / account-delete)
   is unchanged and never gated — a wrong wrap must always be removable.

5. **Reader unchanged.** `unlockWithBiometric` still opens exactly the slot the wrap names; PR-3
   changes no unlock/invalidation/disable behavior.

## Consequence checked
- First-enable-wins means a disable→enable cycle in a B session legitimately re-binds to B (clear
  then fresh bind — not a repoint; invariant 1 permits it because `boundSlotIndex()==null` at write).
  Accepted per OQ-A(i): "A" is not an intrinsic slot property; storing a real/decoy pointer is exactly
  the distinction the architecture refuses to make.
