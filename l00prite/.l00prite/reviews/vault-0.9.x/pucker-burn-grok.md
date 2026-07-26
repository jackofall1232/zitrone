I'll inspect the vault, burn, delete-state-machine, and security docs in the repo so the five answers rest on what the code actually does.Reading the vault layout, destroy path, burn stub, and security docs next.# Pucker Burn — Independent Security Advisory

Source grounding: `VaultImage.kt`, `VaultSlots.kt`, `VaultImageStore.kt` (`attemptUnlockOrAdd`, `destroy`, delete markers), `ZitroneApp.destroyVaultForAccountDeletion`, `MainActivity.onBurn` stub, `docs/SECURITY_MODEL.md`, `docs/VAULT_ARCHITECTURE.md`.

---

## Q1 — Burn-wipe scope

**Position: local whole-image wipe only (all vault slots + slot 0 + DEK envelope + biometric wrap/Keystore hygiene + in-RAM secrets). Do not delete relay account(s) as part of burn.**

**Reasoning**

Duress burn’s job is **immediate, offline-capable destruction of recoverable local crypto**. Source already defines the no-remanence local primitive as whole-image unlink (`vault.bin` + `vault.dek` + tmp leftovers, RAM DEK wipe, single-instance release) plus biometric wrap/key removal in `destroyVaultForAccountDeletion()`. That is the right *physical* scope for burn: every vault identity on the device lives in that image; there is no shipped single-slot destroy.

Relay-account deletion is a **different threat**:
- It is **visible to the relay** as a server event (or a correlated multi-account disappearance if several vaults share the install).
- It **requires network**. Under seizure/coercion, connectivity may be blocked, captive, or adversarial. A burn that “fails open” because offline is worse than a burn that always succeeds locally.
- It **collides with D2c**: intent → server DELETE → confirmed → `destroy()` is a multi-round crash-durable machine with AMBIGUOUS / DEFINITE_FAILURE / reconcile. Burn wants a single terminal local act from the lock screen with no session, no auth tokens necessarily live, and no “retry next unlock” UX.

Leaving relay rows alive trades **server-side residual ciphertext** (opaque envelopes under independent identities the user can no longer open) for **no time-correlated server signal** and **no network dependency**. That is the right coercion trade: examiner with the phone cannot recover keys; remote adversary with relay logs still sees accounts that stop talking—which is already true for many innocent reasons—without a sharp “delete at T0” signature tied to the device event.

**Tradeoff**

- **Gains:** always-offline wipe; no relay correlation; no entanglement with D2c outcomes; simpler fail-closed semantics (“local crypto is gone or throw”).
- **Gives up:** remote shred of server ciphertext; peers may still hold history; accounts may sit until janitor TTL / inactivity; a sophisticated adversary who *already* links device install time to relay account activity is not helped by server delete either if they have logs before the burn.

Optional later “best-effort remote shred after local wipe” would reintroduce correlation and partial failure modes; it should not be part of the *definition* of burn success.

---

## Q2 — Burn ↔ delete-state-machine interaction

**Position: reuse the *local destruction primitives* (`destroy()` / biometric teardown / RAM zeroing / route-to-onboarding disk truth), but keep burn *fully separate* from the D2c marker state machine and `deleteAccountAndWipe` orchestration. Do not write `vault.delete-intent` / `vault.delete-confirmed` for burn.**

**Reasoning**

What burn needs from existing code:
- Whole-image unlink + verify-unlink + dir-fsync discipline in `destroy()`
- Biometric wrap/Keystore cleanup (`destroyVaultForAccountDeletion`)
- Session teardown if any session is live (usually burn is from lock screen with no session)
- Post-condition: `hasVault() == false` → onboarding route (same disk-truth routing account delete already uses)

What burn must **not** reuse:
- Intent/confirmed markers. Those mean “account deletion was initiated / server confirmed gone.” `destroy()` today **writes** `vault.delete-confirmed` before unlinks because account-delete boot routing depends on it. If burn writes those markers, a crash mid-wipe can land boot in `DeleteIncomplete` (“finish deleting your account”)—a **discoverable, non-fresh-install** state that undercuts post-burn appearance and is semantically false (no server delete).
- Network DELETE, reconcile-on-next-unlock, auth-token retention rules, `deleteInFlight` guards.

So the engineering principle is **share the shovel, not the house**. Extract or call a pure local “image + biometric gone” path that does **not** assume server confirmation and does **not** leave account-delete markers. That may mean a thin burn-specific wrapper around the same file-unlink/verify/fsync logic, or a parameterised destroy that skips marker writes—not a new writer into the D2c *protocol*.

Reusing D2c as-is would be a new durable-signal writer against a machine that took rounds 13–16 to stabilize, and would invent false “server confirmed” state during pure local coercion wipes.

**Tradeoff**

- **Gains:** D2c invariants stay exclusive to voluntary account delete; burn boot path stays “no image → onboarding”; no AMBIGUOUS/DEFINITE_FAILURE surface under duress; no auth-token/reconcile coupling.
- **Gives up:** single unified “all destruction goes through one function” story; some duplication or a carefully split primitive (marker-aware account destroy vs marker-free local obliterate). That split is cheaper than reopening sixteen rounds of marker semantics.

---

## Q3 — IMAGE_VERSION implication

**Position: arming slot 0 requires no format change. It is pure slot write within the existing v3 layout.**

**Source**

- `VaultImage.kt:25–36`: v3 is “slot 0 reserved for Pucker Burn… **BYTE layout is unchanged from v2** — only the placement CONVENTION changed”; `IMAGE_VERSION = 3` already.
- Layout remains `version(1) ‖ SLOT_COUNT × [salt‖wrapped] ‖ SLOT_COUNT × payload` (`VaultImage.kt:12–16, 41–46`).
- `VaultSlots.kt:23–29, 127–128`: slot 0 is a full slot; unarmed = CSPRNG filler; **“armed simply means a passphrase can match slot 0”** — exactly what `tryPassphrase` tests.
- `VaultImageStore.attemptUnlockOrAdd` already treats slot-0 match as `UnlockOrAdd.Burn` without writing (`VaultImageStore.kt:682–691`); burn setup is therefore: seal slot 0 under the chosen passphrase (same `sealSlot` / payload seal as any vault), persist the image under the existing DEK envelope, then never expose a “armed” bit anywhere.

**What is not needed**

- No version bump to arm.
- No extra header field, flag, or arm-state bit (that would violate “no discoverable artifact of armed/unarmed”).
- No layout migration for burn setup on an already-v3 image.

**Caveats (not format changes)**

- Setup must re-seal/persist the **whole** image (same as any slot mutation), under existing durability rules.
- Burn payload content can be empty/marker garbage; source already opens and discards on match for timing parity (`attemptUnlockOrAdd` burn branch). Payload need only be structurally valid enough for that parity open (or the existing `runCatching` path).
- v2→v3 retire path already exists for *legacy* images; burn arming does not re-open that.

**Tradeoff**

- **Gains:** no migration tax; examiner still sees only fixed-size image; arming is cryptographic, not structural.
- **Gives up:** nothing structural. The cost is operational (setup UX, permanent credential, wipe implementation)—not encoding.

---

## Q4 — Post-burn appearance

**Position: aim for *app-local* “never onboarded” appearance (onboarding UI, no vault image, no biometric wrap, no delete markers, no vault-scoped prefs/auth). Do not claim OS-level “factory install” or “this device never ran Zitrone productively.” Deliver-then-claim applies hard here.**

**What is achievable inside app control**

After a successful local wipe (marker-free destroy + biometric hygiene + wipe of vault-scoped EncryptedSharedPreferences / auth / device settings that encode “had an account”):
- `hasVault()` false → `Route.Onboarding` (already the account-delete success path in `MainActivity`)
- No lock screen, no delete-incomplete UI
- No app-owned ciphertext image or DEK file
- No biometric dual-wrap prefs / Keystore aliases for the vault

That is **indistinguishable from a first launch of this package version on this user data directory**, not from a never-installed APK.

**What cannot be hidden (platform honesty)**

| Artifact | Why burn cannot erase it |
| --- | --- |
| Package install / update time, version history | PackageManager / installer; outside app sandbox |
| App UID, data-dir inode age, ART/profile/odex caches | System-owned; may rebuild but timestamps differ from virgin install |
| Backup / Auto Backup / cloud backup blobs | If ever backed up; restore can resurrect pre-burn state unless backup is excluded (policy, not burn) |
| UsageStats, battery history, network stats | System aggregates; “app was active for months” survives |
| Notification history / ranking | System UI history where retained |
| Media / Downloads / Screenshots outside app storage | User or OS media store |
| AccountManager entries if any were created | Only if the app registered accounts; wipe must clear *app-created* ones, not others |
| Play integrity / SafetyNet-style install signals | Not under app control |
| Forensic residual of deleted files | Flash wear-leveling / unallocated space — app unlink is not secure erase of the medium |
| Relay-side account lifetime | Out of scope if Q1 is local-only |
| Peer devices’ message history | Independent of this handset |

**Forensically**

- **Best honest claim:** “Local vault crypto and app identity material are gone; the app presents first-run onboarding.”
- **Dishonest claim:** “Looks like a factory-reset phone / never-used install.” Examiner with `adb`, usage stats, or package metadata will often distinguish “long-lived app data wiped” from “fresh sideload yesterday.”

**UX nuance**

Routing to pure onboarding is correct. Avoid any post-burn “your data was wiped” / DeleteIncomplete / special splash—those are tells. A wipe that *fails* must not look like success (same verify-unlink discipline as `destroy()`).

**Tradeoff**

- **Gains:** matches coerced “open the app” demo; no app-level armed/unarmed residue if setup UI already disappeared.
- **Gives up:** resistance to competent mobile forensics and OS telemetry; any marketing language stronger than “app-local remanence cleared” violates deliver-then-claim.

---

## Q5 — What we are missing

Distinct concerns not fully forced by Q1–Q4:

1. **Settings entry is a structural tell before arming.** Locked design: “Pucker Burn Password Setup” above Delete Account, then disappears. Before arming, the *menu item itself* proves the feature exists (decompile + live UI). After arming, *absence* of the item can distinguish armed from never-set **if** the examiner knows the catalog of menu items for this APK version. That tensions with “no discoverable artifact of armed/unarmed state.” The crypto of slot 0 is fine; the **settings surface is not slot-symmetric**. Flag as design-flaw risk under platform-honesty / deniability of *feature use*, even if not of vault count.

2. **Permanent, unchangeable burn credential is a single-point lifetime hazard.** Compromised, forgotten, coerced-to-setup, or typed under stress years later cannot be rotated. There is no recovery path by design. That increases accidental-wipe rate and may pressure users *not* to arm—leaving the settings tell present longer.

3. **Setup race vs live sessions / multi-vault.** Arming rewrites the shared image. Concurrent unlock, flush, or second-vault create can race the burn seal. Account-delete markers already fail-closed create; burn setup needs the same imageLock discipline and a clear rule if delete-intent is pending (probably refuse setup).

4. **First-match ordering in `tryPassphrase`.** Loop records the **first** match and continues (`VaultSlots.kt:224–227`). If a user ever reuses the burn passphrase as a vault passphrase (or vice versa), slot 0 wins and **wipes instead of unlocking**. Permanent credential + no change path makes accidental collision catastrophic. Setup must reject passphrases that already match any slot; docs must stress non-reuse—but enforcement only works at setup time for *current* slots.

5. **Triple-entry creation vs burn passphrase.** A coercer who learns or guesses the burn string once triggers wipe. Separately, three identical *wrong* strings create an empty vault (accepted). If burn setup UX or muscle memory collides with “type the same secret thrice,” user error modes multiply. Not a protocol bug, but a human-factors minefield next to permanent wipe.

6. **Burn from lock screen without session: incomplete non-image remanence.** `destroy()` clears image files; account-delete also tears down notifications, message repos, etc., because a live session exists. From lock screen, vault-scoped EncryptedSharedPreferences, auth tokens, roster repair blobs, boot diagnostics files, notification channels, WorkManager jobs, WebView/cookie caches (if any), and logcat-backed app logs may **outlive** image delete unless the burn path enumerates them. Partial wipe is worse than honest failure: onboarding UI with residual prefs can look “used.”

7. **`destroy()`’s confirmed-marker write is unsafe to call unmodified for burn** (bridges Q2). Using stock `destroy()` after a burn would plant `vault.delete-confirmed` and risk DeleteIncomplete after crash—**an armed-looking post-wipe state**. This is a concrete implementation footgun already in tree.

8. **Timing residual of wipe itself.** `attemptUnlockOrAdd` keeps KDF parity through Burn return; the *subsequent* wipe (MB-scale unlink, Keystore ops, prefs clear) is wall-clock obvious. That is fine under “you entered the burn password and the app reset,” but a stopwatch during coercion can distinguish burn from wrong password **after** the uniform crypto phase. Document as accepted residual, same class as create-persist residual—not as timing-hidden wipe.

9. **Fail-closed stub today masks future behavior change.** Current `onBurn` looks like wrong password (`MainActivity.kt:786–789`). Shipping real wipe changes lock-screen semantics for the same credential class. Any pre-release testing or leaked APK that armed slot 0 via tests would change behavior across versions—version narrative for examiners (“this build wipes”) matters for honesty.

10. **Blind overwrite does not touch slot 0; burn is immortal until whole-image destroy.** Documented as intentional. Consequence: the only way to remove burn is full wipe or re-onboard after destroy. There is no “disarm.” Combined with permanent credential, the device is forever one passphrase away from total local loss once armed—including by the user under panic or by anyone who observed setup.

11. **Biometric path cannot trigger burn (good) but can undermine coercion story.** Compelled biometric opens the bound vault only. Coercer who forces biometric never hits burn; coercer who forces “the password” may get vault A, vault B, burn, create, or reject. Users may arm burn and still open real vault under biometrics when stressed—training/UX assumption unstated.

12. **No cryptographic binding of burn setup acknowledgment.** “Actively-acknowledged warning” is UI-only. It does not change storage. That is fine, but legal/forensic narratives (“user knowingly armed wipe”) rest entirely on UI logs that you will not keep—and should not keep.

13. **Relay identity linkage across vaults after local-only burn.** If the user had multiple vaults, those relay accounts remain. If the user later re-onboards on the same device, new accounts appear. An adversary with relay logs + device continuity (install id, Tor/I2P behavior, timing) may correlate “old accounts silent + new registration from similar client.” Not solvable by burn alone; unstated assumption that “local factory look” implies “no network history.”

14. **Secure-deletion myth on flash.** Unlink + fsync is correct for *app* remanence guarantees already used by account delete; it is not media sanitization. Do not let burn marketing drift into “forensically unrecoverable from NAND.”

15. **Locked decision tension: settings disappearance vs examiner APK knowledge.** Even with byte-identical slot 0, *product* state “setup menu present/absent” is an arm-state oracle for anyone who knows the version’s settings tree. Mitigations (always show disabled grey item, never show item and use triple-entry-style setup, etc.) would re-litigate locked UX—but the tension should be on the record.

---

**Summary stance:** local whole-image obliteration without relay delete or D2c markers; no image version change to arm; post-burn = honest app-local first-run only; biggest unique flags are the **settings-menu arm oracle**, **unmodified `destroy()` marker coupling**, **lock-screen incomplete remanence inventory**, and **permanent unchangeable credential + first-match wipe collisions**.
