# Pucker Burn — CLAUDE advisory (written FIRST, un-anchored, before reading the other three)

## Q1 — Burn-wipe scope
**Position: LOCAL-ONLY is the guaranteed, synchronous burn; relay-account deletion must NOT be part of
the atomic burn (at most a decoupled, opt-in, best-effort afterthought — and I lean against even that).**
Reasoning: (a) **Network-independence** — a duress scenario is often offline (seized device, faraday,
airplane mode, dead SIM). A burn that *depends* on a relay round-trip fails exactly when it is needed;
the one thing a duress wipe must do reliably is destroy the LOCAL plaintext-accessible material (keys,
messages, identities), which is 100% on-device. (b) **Correlation tell** — a relay-account delete emits a
server-side event time-correlated with the wipe. An adversary who correlates relay logs with the seized
device can then *prove* "this device burned at time T," which undercuts the whole point (the on-device
state should look like nothing happened). (c) **Collision** — it partially duplicates the D2c
server-coordinated account-delete state machine (see Q2). Trade-away: the relay account (opaque,
zero-knowledge ciphertext) persists server-side until independently deleted or aged out; already-sent
messages are E2E so their plaintext is unaffected, but the *account's existence* + its opaque blobs
remain on the relay. Net: local-only trades "the relay account is also gone" for
"the burn is network-independent, synchronous, and emits no correlatable server event." Document the
relay residue honestly rather than pretend the burn erases the server side.

## Q2 — Burn ↔ delete-state-machine interaction
**Position: reuse the whole-image DESTROY PRIMITIVE (the unlink+fsync+keystore-wipe that is a pure
function of the image), but do NOT make burn a new writer to the D2c two-marker STATE MACHINE
(delete-intent / delete-confirmed).** The markers exist to reconcile a *deliberate, server-coordinated*
account deletion across crashes — they are the opposite of what burn wants. A `vault.delete-intent`
marker surviving a burn is a forensic TELL ("this device was mid-delete"), contradicting the factory-fresh
goal (Q4). Burn is local, immediate, and must leave **no delete-state trace**. So: call the destroy
image-unlink path directly (it is the load-bearing no-remanence step), skip the marker writes.
Trade-away: a new caller of the destroy primitive needs its own verification pass, but it avoids adding a
second, different-semantics writer to state that took 16 rounds to stabilize — which is the exact hazard
the question warns about. **Verify in impl:** that `destroyVaultForAccountDeletion` can be decomposed so
burn reuses the unlink+keystore-wipe WITHOUT the marker/reconciliation flow.

## Q3 — IMAGE_VERSION implication
**Position (verified vs source): NO format change, NO IMAGE_VERSION bump.** `IMAGE_VERSION=3` and the byte
layout is explicitly "unchanged from v2" (`VaultImage.kt:26`); slot 0 is already a full slot in v3, "left
as filler on a fresh onboarding … indistinguishable from any other slot" (`VaultSlots.kt:127-128`);
"armed simply means a passphrase can match slot 0, exactly what tryPassphrase already tests"
(`VaultSlots.kt:27-28`); and `attemptUnlockOrAdd` already returns `Burn` on a slot-0 match
(`VaultImageStore.kt:151`). So arming = sealing a real derived credential into slot 0's existing
salt+wrapped-key region (plus a byte-shaped payload — an empty/genesis seal keeps parity) INSTEAD of
filler. That is a slot write within the existing v3 layout. The one thing to confirm in impl: the burn
slot's PAYLOAD is sealed to the same fixed size as any vault payload (no new field, no size tell).

## Q4 — Post-burn appearance
**Position: the APP presents as un-onboarded (fresh onboarding, no in-app history) because the image is
unlinked → `hasVault()==false` → onboarding route. That is real and achievable. But "indistinguishable
from a genuinely fresh install/device" is NOT achievable on Android and must NOT be claimed.** What the
burn CAN destroy: the app's own sandboxed data (the sealed image, the Keystore keys, prefs). What it
CANNOT touch (outside app control, betrays prior use to a forensic examiner): package
`firstInstallTime`/`lastUpdateTime` (an old install time vs a "fresh" one), usage-access / battery /
data-usage history, any shared-storage / MediaStore artifacts the app previously wrote (e.g. images saved
to the gallery), AccountManager entries, system notification history, logcat, and — critically — **cloud
backup state** (if Android auto-backup is enabled, the pre-burn data may be restorable, which can *defeat*
the burn; verify `allowBackup`/backup-rules exclude the vault). Honest tier: **strong against a
casual/manual coercer who just opens the app (sees onboarding); weak against a forensic examiner** who
reads install time + usage stats and sees a long-installed app suddenly empty. Also: the burn's *transition*
is observable — a coercer watching the screen sees chat-app → (wipe delay) → onboarding, which itself
reveals a wipe happened (see Q5). Claim the achievable ("the app's own data is destroyed; it presents
un-onboarded"), not the unachievable ("looks never-used").

## Q5 — What we're missing (unprompted concerns)
1. **The burn is an untestable, irreversible data-loss footgun at the lock screen.** A single mistaken
   entry of the burn passphrase (fat-finger, misremember) destroys the real vault with no confirmation
   (a confirmation would be a tell), and the user can NEVER verify "did I set the burn credential to what
   I think" without triggering it. Set-and-forget a destructive credential you can't test = a live grenade.
2. **Forensic-image-first defeats any on-device burn.** A competent adversary images the device BEFORE
   making you unlock; the burn then wipes the live device but they analyze the copy. Burn defends "hand me
   your phone and unlock it," NOT "seized + forensically imaged." This bound must be stated (deliver-then-claim).
3. **The disappearing settings entry is a decompilable armed/unarmed ORACLE.** "Pucker Burn Password Setup"
   that disappears once set means: setting present ⇒ unarmed, setting absent ⇒ armed. An examiner who
   decompiles the app knows the entry exists and knows the visibility rule → the entry's ABSENCE reveals
   burn is armed. The slot-0 byte-parity makes the IMAGE not reveal armed/unarmed, but the UI does. The
   armed-state must not be derivable from any decompilable check (image, prefs, or UI-visibility logic).
4. **Whole-image burn = a conspicuously empty device.** Burn nukes ALL vaults (per-vault destroy isn't
   built). Under "hand me your phone," a suddenly-empty phone is itself suspicious → may provoke the
   coercer. A more deniable model (NOT in the locked design) would unlock into an empty DECOY while wiping
   the real vaults silently. The locked "wipe → visible onboarding reset" is the *least* deniable
   post-state under direct observation. Worth an explicit decision: is a visible factory-reset acceptable,
   or should burn present a plausible non-empty decoy?
5. **Backup/restore resurrection** (also in Q4): if auto-backup is on, burn may be undone by a cloud
   restore. Verify the app excludes vault data from backup; if not, that is a burn-defeating hole.
6. **Onboarding-after-burn must be byte-for-byte the SAME state as onboarding-after-fresh-install and
   onboarding-after-account-delete** — any residual prefs/marker/flag left by the burn path that a fresh
   install would not have is a tell (ties back to Q2's "no markers").
7. **Wipe durability under crash/kill mid-burn.** If the process is killed mid-burn (coercer yanks the
   device), is the image left partially-destroyed but resurrectable, or is the unlink ordered so a
   crash leaves it *more* destroyed, never *recoverable*? The D2c work solved this for delete; burn needs
   the same crash-order guarantee (unlink the key material first).
8. **The burn credential's KDF cost = the same Argon2id as a vault** (parity). Fine — but it means
   entering the burn under duress takes the same ~seconds; a coercer rushing you might interrupt before
   the sweep completes. Confirm an interrupted burn entry doesn't leave a half-state.
