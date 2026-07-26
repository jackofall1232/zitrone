moon: asking kimi-k3...
# Independent Advisory — Pucker Burn (Setup + Wipe)

**Repo access note:** I do not have live file-read capability in this session, so for Q3 I reason from the cited source facts and quoted lines (`VaultImage.kt:26`, `VaultSlots.kt:27-28, 127-128`, `VaultImageStore.kt:151`) and explicitly enumerate what a repo-capable reviewer must confirm. I do not assert verification I didn't perform.

---

## Q1 — BURN-WIPE SCOPE

**Position: local-only wipe. Do not touch relay accounts in the burn path.**

**Reasoning:**

1. **Duress is the worst-case network environment.** A burn triggered under coercion may have no connectivity, throttled connectivity, or an adversary-controlled access point. Any network-dependent step in the wipe path is either (a) blocking, which can hang the wipe at the exact moment it must be fast and silent, or (b) best-effort-and-silent-failure, which means you shipped a property you can't deliver — a direct deliver-then-claim violation.

2. **The deletion event is the leak, not the account.** Under the zero-knowledge relay model, a live account holding opaque ciphertext reveals almost nothing. A *deleted* account creates a server-side event time-correlated with a device wipe. That correlation is visible to the relay operator, to anyone who later compels relay logs, and to an adversary watching account liveness. You've converted "device wiped, nothing to see" into "account vanished at 14:32, why?" The deletion is more evidentiary than the existence.

3. **The real confidentiality win is local anyway.** Destroying the DEKs (`vault.dek` unlink + RAM wipe + keystore wrap removal) renders all relay-held ciphertext permanently undecryptable. Orphan ciphertext on the relay is indistinguishable from any other stored blob. Key destruction, not account removal, is the security property.

4. **Collision with D2c is gratuitous.** D2c's two-marker machine exists to make a *multi-phase network round-trip* crash-durable. Burn has no network phase. Injecting burn as a third writer into a state machine that took sixteen rounds to stabilize buys nothing and risks everything (see Q2).

**Tradeoff:** Abandoned relay accounts persist, and counterparties will notice silence. A sophisticated examiner who can query the relay learns "an account existed and went quiet" — but learns nothing about *when or why the device state changed*, which is the correlation that matters. If relay-account deletion is desired, it belongs in the existing, deliberate, network-available D2c flow — or as an optional, explicitly-separate "dead-man's deletion" configured at setup with its own honest claims — never in the duress path.

---

## Q2 — BURN ↔ DELETE-STATE-MACHINE INTERACTION

**Position: share the destruction primitive (`destroy()`); do NOT touch the marker state machine. One audited teardown, two invocation paths, zero new marker writers.**

**Reasoning:**

- The D2c two-marker design (`delete-intent` → `delete-confirmed`, CONFIRMED_GONE/DEFINITE_FAILURE/AMBIGUOUS) exists to solve a problem burn does not have: crash-durability across an asynchronous server round-trip. Burn is a single synchronous local operation. Reusing the markers adds a forensic artifact with no compensating benefit — a leftover `vault.delete-intent` file on a supposedly fresh device actively contradicts the post-burn appearance goal (Q4).
- The correct unit of reuse is the *whole-image destruction itself*: unlink `vault.bin` + `vault.dek`, fsync, RAM DEK wipe, biometric key/wrap removal. That logic should exist exactly once. Two divergent implementations of "make the vault cease to exist" will drift, and the less-reviewed one will be the one that runs under duress.
- The "new writer to stabilized state" concern is specifically about the *marker files and outcome transitions*. Burn writes no markers and transitions no D2c state, so it is not a new writer in the sense that matters. The burn path should invoke the destruction routine directly from the `UnlockOrAdd.Burn` outcome, without routing through the account-delete controller at all.

**Critical ordering requirement:** burn must destroy keys *first* — unlink `vault.dek`, wipe RAM DEK, drop keystore wraps — *then* remove `vault.bin`, then app state, then fsync. This makes even a crash/power-loss-interrupted burn cryptographically complete: ciphertext without the DEK is as destroyed as ciphertext unlinked, and in this design it's also indistinguishable from the random filler every slot already contains. D2c's crash-ambiguity was about *server* state; burn's crash failure mode fails *closed toward more destruction*, which is the safe direction.

**Tradeoff:** You accept a small boot-time reconciliation obligation: if the app starts and finds `vault.bin` present but `vault.dek` absent (or vice versa), it should silently complete the teardown. That's a read-and-complete check that writes nothing and leaves no marker — cheap insurance, but it is one more code path to audit.

---

## Q3 — IMAGE_VERSION IMPLICATION

**Position: no format change, no version bump. Arming is a data-plane write of one slot within the existing v3 layout.**

**Source-grounded reasoning (from cited facts; I could not re-read the files directly):**

1. The v3 byte layout is explicitly "unchanged from v2" (`crypto/vault/VaultImage.kt:26`), and slot 0 already exists as a full slot in that layout, filled with uniformly-random filler on fresh onboarding that is "indistinguishable from any other slot" (`VaultSlots.kt:127-128`).
2. Arming is defined as making "a passphrase match slot 0, exactly what `tryPassphrase` already tests" (`VaultSlots.kt:27-28`) — i.e., the detection machinery needs no new field, header flag, or occupancy bit.
3. `attemptUnlockOrAdd` already returns `UnlockOrAdd.Burn` on a slot-0 match (`VaultImageStore.kt:151`). The dispatch outcome exists; only the wipe handler is stubbed.

Therefore arming = "seal the burn credential into slot 0 using the same slot-sealing as any vault slot." Nothing structural changes, so `IMAGE_VERSION` stays 3. **Moreover, a version bump would itself be a leak**: any new header field or version value correlated with burn capability/arming violates the byte-identical / no-discoverable-artifact principle. The absence of a format change is not just sufficient, it's required.

**What a repo-capable reviewer must confirm before relying on this (these are the only places a hidden format implication could live):**

- **Slot-count derivation:** whether the number of slots is implicit in the v3 layout/image size rather than a header field. If a header slot-count exists, it must already account for slot 0 in all images (armed and unarmed) — per the filler facts, it does.
- **No occupancy bitmap:** verify there is no per-slot occupied/filler metadata anywhere (header, per-slot tag, sidecar). Random filler vs. AEAD-sealed slot bytes must be statistically indistinguishable *and* structurally identical in length and offset.
- **Writer reachability:** the existing slot-seal writer must be callable against slot index 0 explicitly — the blind-placement path *excludes* slot 0 by design, so arming needs a dedicated targeted writer, and that writer must use the same atomic commit discipline as normal vault writes. A crash mid-arm must leave slot 0 as garbage-bytes-indistinguishable-from-filler (it will, since filler is already random — this fails safe, but confirm the atomicity property holds for in-place slot writes, not just whole-image commits).
- **"Burn is set" persistence must NOT go in the image** — see Q5, concern #1. Any temptation to record armed-state in the image header is a format change that must be rejected.

---

## Q4 — POST-BURN APPEARANCE

**Position: claim "app-local state indistinguishable from a fresh install." Do not claim "device looks like the app was never used." The second is undeliverable on Android and claiming it violates deliver-then-claim.**

**What the app controls and must destroy:** `vault.bin`, `vault.dek`, all SharedPreferences, all databases (messages, contacts/handle mappings, attachment index), attachment/media stores under app-private storage, cache, WebView data, notification channels the app created, Android Keystore keys and wraps, any AccountManager entries the app registered, and in-memory state — followed by returning to byte-identical onboarding with no "wiped" screen, no log line, no toast. Post-burn behavior must be boring: onboarding, nothing else.

**What the app CANNOT control, honestly enumerated:**

- **PackageManager metadata:** `firstInstallTime` / `lastUpdateTime` survive every app-local wipe.
- **UsageStats:** system-level app-usage history (last-used timestamps, usage duration buckets) is outside the app's reach and will show months of use followed by "never set up."
- **Backup:** if Auto Backup ever ran, pre-burn app data may sit in the user's cloud backup entirely outside the app's reach (see Q5 — this must be *prevented at build time*, not wiped at burn time, because burn can't reach it).
- **Notification history / launcher logs** on devices where enabled; notification channels are deletable but their past existence may be journaled.
- **Media Store:** anything the user exported or saved to shared storage persists, with timestamps.
- **Play Store install record, battery stats, connectivity/app-ops logs, Device Health Services data.**
- **Filesystem level:** `unlink` does not erase NAND. On F2FS/ext4 with journaling and flash wear-leveling, stale blocks and journal metadata (filenames, timestamps of `vault.bin`) may survive chip-off analysis. Mitigating factor unique to this design: the recoverable blocks are ciphertext indistinguishable from the random filler the format already writes — so physical residue degrades to "some random-looking blocks existed," not to plaintext or to slot structure. The DEK destruction (Q2 ordering) is what makes this safe; the unlink is housekeeping, not the security boundary.

**Honest tier statement for docs:** burn delivers *confidentiality* (vault contents unrecoverable by anyone, including forensic imaging, because keys are destroyed) and *app-local deniability* (the app presents as fresh). It does not and cannot erase evidence that the app was *installed and used* — OS-level artifacts will show that. Under coercion, the supportable claim is "I installed it once, never really used it" — not "it was never there." The docs should say exactly this.

**Tradeoff:** Users may over-trust the feature. The mitigation is documentation honesty (already a shipped principle), not engineering — the engineering gap is closable only by the OS vendor.

---

## Q5 — WHAT WE ARE MISSING

Concerts flagged, roughly by severity:

1. **The disappearing settings entry contradicts a standing principle — flag as a flaw in the locked decisions.** "The entry DISAPPEARS once set" requires a persistent armed-flag stored *somewhere* (SharedPreferences, database, image header). Every one of those is a discoverable artifact that reveals armed state — precisely what "no discoverable artifact that reveals armed/unarmed state" forbids. It also gets uploaded by Auto Backup if backup isn't excluded, leaking armed-state to the cloud. And it creates asymmetric deniability: an examiner comparing two devices (armed vs. not) sees the settings difference. **Recommended resolution: the entry never disappears.** An unarmed user and an armed user must present byte-identical settings screens; "Pucker Burn Password Setup" sitting permanently above "Delete Account" on every install is itself the deniability-preserving state. If the locked decision stands, the flag's storage location must be chosen and justified as an accepted leak — but I see no storage location that doesn't violate the principle.

2. **`UnlockOrAdd.Burn` is returned by a *general-purpose* store operation — wiring hazard.** `attemptUnlockOrAdd` is also the natural call site for add-slot duplicate checking and any future change-passphrase flow. If the wipe is wired to the `Burn` outcome naively, then *any* code path that tests a candidate passphrase against slots — including onboarding's add-slot collision check — becomes a wipe trigger. That's a self-DoS: creating a second vault with an unlucky candidate destroys everything. The wipe must be wired exclusively to the **unlock-from-lock-screen dispatch**; every other consumer of the `Burn` outcome must treat it as "collision, reject candidate."

3. **Setup-time collision check against all existing slots is mandatory and unmentioned.** When arming, the candidate burn passphrase must be tested against *every* occupied vault slot (and rejected on match). Otherwise a user can arm a burn credential equal to their vault passphrase, and every subsequent unlock attempt wipes. Exact-match checking is possible (tryPassphrase does it); similarity can't be checked, which should be stated in the setup warning.

4. **Auto Backup can defeat the entire feature.** If `android:allowBackup` is enabled and `vault.bin`/`vault.dek` aren't excluded, a pre-burn cloud backup restores the vault — burn wipes the device, the adversary (or a restore) resurrects it. This must be handled at manifest/backup-rules level *before* burn ever matters. Verify the manifest; this is a ship-blocker if not already excluded.

5. **Post-burn timing and UI transition leak the event.** Timing parity covers the crypto sweep, not what happens after a match. A burn necessarily takes longer than a failed-unlock and ends in onboarding instead of a lock screen — an observer watching the screen sees it. Unavoidable in the limit, but: do all destruction synchronously *before* any UI transition, and make the post-burn landing screen the ordinary onboarding flow, not a distinct state. Never show a "wiped" indicator, a crash, or a hang (the current fail-closed stub must be replaced such that a wipe-path exception still tears down UI to onboarding rather than stranding the user on a lock screen with an intact vault).

6. **Push channel survives.** After burn, the FCM token and relay-side push registration persist; a message arriving post-burn could surface a system notification on a "fresh" device — a live contradiction of the deniability story. Burn can't reliably unregister without network (Q1's argument applies), so at minimum the app on post-burn startup should ensure no notification channel can display anything, and docs should note residual push registration as a known artifact. Ideally the relay protocol's token TTL makes this self-healing.

7. **Permanence is a permanent remote-kill credential.** "Unchangeable once set" means anyone who ever observes or coerces the burn passphrase out of the user can destroy the vault at any future time by typing it — and the user cannot rotate. That's an accepted-risk property of the locked design, but it should be in the setup warning text explicitly ("this can never be changed; anyone who learns it can erase this vault forever"), because users will model it as a password and passwords are rotatable.

8. **Burn is whole-image — all vaults die.** `destroy()` deletes the entire image, so a burn destroys the decoy vaults along with the real one. That's presumably intended (it's the only cryptographically clean option), but it means there is no "sacrifice one slot, keep a clean vault" story. If any design document or user expectation implies selective burn, correct it now.

9. **Crash-durability without markers needs a boot-time reconciliation pass.** Per Q2: on startup, inconsistent vault state (image present/DEK absent or vice versa) should silently complete teardown. Without this, a power-cut mid-burn (plausible under duress — think battery pull) leaves a half-state that is maximally forensically interesting: exactly the "app that was wiped" appearance Q4 says to avoid.

10. **Biometric and slot 0:** PR-3's A-only biometric guard presumably already prevents binding biometrics to slot 0 — confirm slot 0 can never receive a biometric wrap (a biometric path that bypasses the passphrase sweep would bypass burn *and* create a slot-0-specific artifact, breaking byte-identity at the keystore level). Also confirm `destroy()`'s biometric key removal covers the burn path so no orphaned keystore entries remain post-burn as "something was here" residue.

11. **Re-arming after re-onboarding is unspecified.** Post-burn, slot 0 is filler again; the feature is silently unarmed. If docs say "permanent," users may believe protection persists across the burn it just executed. State clearly: a burn consumes the credential; a fresh vault requires fresh arming.

---

**Summary of positions:** local-only wipe (Q1); shared `destroy()` primitive, no marker reuse, keys-first ordering (Q2); no format change, `IMAGE_VERSION` stays 3, with four enumerated source checks (Q3); claim app-local fresh-install only, document OS-level residue honestly (Q4); and eleven flagged concerns, of which **#1 (disappearing entry leaks armed-state — recommend reversing that locked decision), #2 (Burn-outcome wiring), and #4 (Auto Backup)** are ship-blockers in my assessment.
