# Unit S (Pucker Burn SETUP / arming) — WRITER/READER invariant table

Built BEFORE implementation, per the standing rule: any change to a durable multi-reader signal gets
its writers, its readers, and **what each reader assumes the signal MEANS at the moment it reads**
enumerated first. The durable signal here is **slot 0's salt + wrapped-key region** of `vault.bin`.

Source-verified against `VaultImageStore.kt` / `VaultSlots.kt` at `d97e584e`.

## The signal

Slot 0 (`BURN_SLOT_INDEX = 0`) occupies the same fixed-size `{salt, wrapped-key}` region as every
other slot. Its payload region stays filler/empty-genesis and is **sized identically**. There is no
armed flag anywhere on disk, by design (P1) — an armed install and an unarmed one must be
byte-indistinguishable in structure, differing only in bytes that are uniformly random either way.

## WRITERS

| # | Writer | When | What it writes into slot 0 | Status |
|---|---|---|---|---|
| W1 | `create()` | fresh onboarding only (`require(!binFile.exists())`) | uniformly-random FILLER (unarmed burn) | existing |
| W2 | **`armBurnSlot()` — NEW** | user completes burn-password setup | a real sealed credential (`sealSlotSelfVerifying` over the same region) | **this unit** |
| W3 | `attemptUnlockOrAdd()` | every passphrase entry | **NOTHING — structurally excluded.** `randomVaultSlotIndex` draws from `VAULT_SLOT_RANGE` (1..N-1) | existing, must stay true |
| W4 | `obliterateLocked()` | burn / account delete | destroys the whole image incl. slot 0 | existing |

**W2 is the only new writer, and it is the first writer ever to put a MEANINGFUL value in slot 0.**
Every reader below was written when slot 0 could only be filler.

## READERS, and what each assumes slot 0 MEANS

| # | Reader | Assumes slot 0 means | Still true after W2? |
|---|---|---|---|
| R1 | `tryPassphrase()` via `attemptUnlockOrAdd` | "if this matches, the duress credential was entered" → `UnlockOrAdd.Burn` | **YES — and this is the point of the unit.** Before arming it cannot match (filler); after arming it matches the burn password. The reader's meaning does not change; its reachability does. |
| R2 | `unlockWithKey()` | "slot 0 is NEVER an openable vault" — `require(slotIndex in VAULT_SLOT_RANGE)` | YES. Unaffected: arming never makes slot 0 biometric-openable. |
| R3 | `BiometricUnlockStore` | slot index is validated into `VAULT_SLOT_RANGE`; a tampered slot-0 wrap reads not-enabled | YES. Unaffected. |
| R4 | `create()`'s `require(!binFile.exists())` | "no image exists, so any markers are orphaned" | YES. Arming never runs on a nonexistent image; it is a session-time operation over a live one. |
| R5 | boot routing / reconcilers | slot contents are opaque; they key on FILE presence and marker state | YES. Arming changes bytes inside an existing `vault.bin`, never its presence or the markers. |

## THE HAZARD THIS TABLE EXISTS TO CATCH

**`tryPassphrase` records the FIRST match by ASCENDING SLOT INDEX (`VaultSlots.kt:217-230`), and
slot 0 is index 0 — so slot 0 outranks every vault slot.** It does not break early (timing parity is
preserved), but `found` keeps the lowest index.

Consequence: **if the burn password also opens an occupied vault slot, entering it WIPES instead of
UNLOCKING.** A user who set their burn password equal to (or colliding with) an existing vault
password would destroy that vault on their next ordinary unlock.

→ **W2 MUST reject a candidate that matches any occupied VAULT-pool slot (1..SLOT_COUNT-1)** before
sealing. Rejecting against slot 0 itself is unnecessary — a re-arm deliberately overwrites it (P1).

This is the single interaction between the new writer and an existing reader, and it is why the
collision sweep is a correctness requirement rather than a convenience.

## CRASH ATOMICITY — verified, not assumed

Slot writes persist through `atomicWrite(binFile, outer)`: the WHOLE image is re-encrypted under the
existing DEK and atomically replaced (temp + rename + dir-fsync). There is no partial in-place slot
write. So a crash mid-arm leaves either:

- the OLD image — slot 0 still filler, burn unarmed; or
- the NEW image — slot 0 armed.

Both are valid, structurally identical states. **The failure mode "half-armed slot 0" does not
exist**, which is what makes arming safe without a marker. (The spec asked for this to be confirmed
for an in-place single-slot write rather than assumed from whole-image commits — it is whole-image.)

## WHAT ARMING MUST NOT DO

1. **No armed flag, anywhere** — no pref, no marker, no file, no differing length. The settings entry
   is permanent and identical in both states (P1); there is no "is it set?" readback by design.
2. **No DEK write** — arming reuses the existing DEK (Q3, no format change, no `IMAGE_VERSION` bump).
3. **No payload change** — slot 0's payload region stays filler/empty-genesis, sized identically.
4. **Never widen `randomVaultSlotIndex`** — blind placement must continue to exclude slot 0 (W3), or
   an ordinary second-vault create could clobber the burn credential.
5. **Refuse while a delete is pending** (`deleteIntentPending()`) and hold `imageLock`, because
   arming rewrites the shared image.
