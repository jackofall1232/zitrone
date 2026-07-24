<!--
  Zitrone — Copyright (C) 2026 Zitrone contributors
  Licensed under the GNU Affero General Public License v3.0 or later.
  See the LICENSE file in the repository root for full license text.
  SPDX-License-Identifier: AGPL-3.0-only
-->

# Zitrone — Plausible-Deniability Vault Architecture

**Status of this document:** Locked design specification. This is the authoritative
architecture reference for the plausible-deniability vault feature. Where the code
disagrees with this document, that is a bug (same convention as `SECURITY_MODEL.md`).

**Implementation status (be honest — read this before citing the feature as shipped):**

| Layer | State |
| --- | --- |
| Crypto primitive (key-slot vaults, timing parity) — web/desktop | **Built** — `packages/crypto/src/vault.ts`, unit-tested incl. timing-parity |
| Crypto primitive — **Android** (Argon2id + no-early-exit `tryPassphrase` + fixed-size blind payload/image) | **Built + wired** — `apps/android/.../crypto/vault/` (`VaultSodiumOps`, `VaultSlots`, `VaultPayload`, `VaultImage`), byte-mirrored from the web reference, unit-tested (no-early-exit, wipe discipline, NIST AES-GCM KAT). As of **0.9.1-beta** it backs the live storage — no longer isolated. |
| Notification-parity structure (single-id, content-free, extra-free intent, teardown hook) | **Built** on Android as of the notification re-fire work (0.9.0-beta) — see §7 |
| Android vault RUNTIME — **everyday (single) vault**: session-over-vault unlock (biometric + PIN/passphrase fallback via `VaultUnlockRouter`), per-slot stores/coordinator, flush-before-ack durability, atomic contact delete, two-marker no-remanence account delete, idle auto-lock (`VaultLockManager`) | **Built as of 0.9.1-beta** (the P1b-2 / PR-D arc). The app runs over the vault image; onboarding sets a passphrase and the ordinary lock screen opens it. |
| Android vault RUNTIME — **second (decoy) vault**: user path to CREATE a second slot | **Built as of 0.9.2-beta.** Vault B is created through the PIN/passphrase router itself (no setup wizard) — the **triple-entry** ceremony (§3.3): three consecutive identical entries of a never-before-used passphrase create and open slot B. Blind placement over the vault pool (slots 1..`SLOT_COUNT`-1; slot 0 reserved for the Pucker Burn credential). Biometric stays bound to a single vault and can never be repointed (0.9.2 A-only guard). So plausible deniability is now a usable guarantee on Android, subject to the documented limits (blind-overwrite, triple-entry consequences, biometric A-only) — see `SECURITY_MODEL.md`. |
| Android vault RUNTIME — second-vault **per-vault destruction**, and Pucker Burn **setup + wipe** | **NOT built yet.** Whole-image account delete exists, but there is no primitive to destroy one vault's slot alone (§3.4). The Pucker Burn duress credential's slot (slot 0) is reserved and the store is burn-*aware*, but the burn setup UX and wipe execution are separate future PRs. |
| Migration from a pre-vault Android install into the vault format | **Dropped — not built.** 0.9.1 is a **fresh-install-only** cut; there is no in-place migration and no commitment to storage-format stability yet (wipe-on-breaking-change is disclosed in the release notes). |
| Decoy traffic (§8) | Deferred to a later release (0.10.0-beta) — specced adjacent, not built |

> **Documentation-accuracy note (updated 0.9.2-beta).** The Android everyday-vault runtime
> (0.9.1-beta) and now the **second-vault creation path** (0.9.2-beta, the silent triple-entry
> router of §3.3) are both built and live. Android can therefore create and reveal a second
> (decoy) vault, so plausible deniability is a **usable** guarantee here — bounded by the
> limitations documented in `SECURITY_MODEL.md` (blind-overwrite on creation, the triple-entry
> gate's consequences, biometric bound to a single vault). What is **not** yet built: per-vault
> destruction (whole-image delete only) and the Pucker Burn setup/wipe UX (§3.4). Do not describe
> those as shipped. `SECURITY_MODEL.md` and `README.md` are reconciled to this status.

---

## 1. Why this document exists

Plausible deniability is the hardest problem on Zitrone's roadmap. Existing "hidden vault" /
"duress mode" features in other apps fail one of two ways:

- They require a **distinct, discoverable** way to reach the hidden content (a secret gesture,
  a menu item, a button). The control's mere existence — findable by decompilation, by a
  thorough search under duress, or by noticing an unexplained UI element — is proof the feature
  exists.
- They do not attempt real deniability at all (a PIN-locked folder any competent adversary
  knows to demand access to).

Zitrone avoids both by making the **existing, ordinary PIN-fallback UI double as the vault
router**, adding **zero** new discoverable surface. This document captures that design in full.

## 2. Core principle — there is no button for the second vault

**There cannot be one.** Any UI element whose only purpose is "reveal the hidden vault" is, by
definition, evidence a hidden vault exists. True plausible deniability requires vault access to
be **indistinguishable from ordinary use of a feature that already has an innocent
explanation.**

Zitrone already has that feature: the lock screen's biometric prompt with a **"Use PIN"**
fallback. That fallback exists today for mundane reasons (wet hands, sensor failure, personal
preference); it needs no new justification and raises no questions. The entire architecture is
built on it.

## 3. Vault model

### 3.1 Structural symmetry

- Every install **always** has structural capacity for two vaults, in every build, for every
  user. There is **no** "enable vault" setting, toggle, or feature flag anywhere in UI, Settings,
  or code paths that a decompiler could correlate to "vault feature on/off".
- Both vaults are **fully independent identities** — each its own identity keypair, contacts,
  message store, relay account, and (once decoy traffic ships) its own dummy pinned account.
  Internally they are **vault slot A** and **vault slot B** — never labeled "real" / "decoy" in
  UI copy, code, string resources, comments, or logs. There is no canonical "which is real": it
  is defined only by which one the user treats as theirs.
- Both vaults derive their unlock keys with **identical Argon2id parameters and timing**, so no
  local side-channel — timing, memory-access pattern, storage layout, UI latency — can
  distinguish "correct password for A" from "for B" from "wrong entirely". This mirrors
  `vault.ts`'s `tryPassphrase`, which derives-and-attempts **every** slot with no early exit.
- A hidden vault's contents must not be constrained to "sensitive" material only. If vault B
  only ever held high-stakes conversations, its *contents* become the tell the moment anyone
  gains access. Both vaults hold an ordinary mix; deniability comes from the vault's *existence*
  being unprovable, not from its contents being boring by construction.

### 3.2 Unlock flow (the router)

The lock screen is **visually and structurally unchanged** — no new screen, button, or copy.

- **Biometric (fingerprint/face) → always routes to vault slot A, unconditionally.** Biometrics
  cannot encode a distinct secret the way a typed passphrase can, so no attempt is made to make
  biometric unlock ambiguous. This is an intentional, accepted asymmetry: slot A is the only
  vault reachable by biometric convenience, serving the majority who never touch vault B.
- **"Use PIN" (the existing fallback) → is the vault router.** The entered passphrase is checked
  **locally** against the derived key for *both* slots:
  - matches slot A's derivation → unlock into A;
  - matches slot B's derivation → unlock into B;
  - matches neither → access denied, with **identical failure behavior and timing** regardless
    of which vaults exist or which was "closer".
- To any external observer — watching an unlock, or forcing one under duress — nothing
  distinguishes these three outcomes: same screen, same flow, same apparent behavior every time.

### 3.3 Setup

- Vault A's passphrase is **suggested** to match the device lock-screen credential for
  memorability, but the app derives and stores its **own independent key** — it does not defer
  to or depend on the OS credential store. This keeps A and B symmetric in implementation (same
  mechanism, same code path, same guarantees) rather than A being OS-backed and B app-backed.
- Vault B is created through the **PIN/passphrase router itself — there is no setup wizard, and
  there must not be one** (a dedicated "create second vault" flow would be exactly the
  discoverable tell §2 forbids). The entire ceremony (0.9.2-beta, Android) is: at the ordinary
  lock screen, enter the **same never-before-used passphrase three times, consecutively and
  uninterrupted**. The third consecutive identical entry of a passphrase that matches no existing
  slot creates vault B and unlocks straight into it — indistinguishable, to any observer, from a
  user who mistyped twice and got in on the third try.
  - **Uninterrupted** is enforced: backgrounding the app, the lock cycle, or process death resets
    the streak (`VaultLockManager.onStop` / the RAM-only candidate in `VaultUnlockRouter`), so a
    stray sequence cannot accumulate across sessions.
  - There is **intentionally no confirmation dialog and no warning copy** — a "you are creating a
    hidden vault, its passphrase is unrecoverable" prompt would itself be the tell. The
    non-recoverability is inherent (no reset, no account recovery, no support path) and is
    disclosed here and in `SECURITY_MODEL.md`, not in an in-flow dialog that would out the feature.
  - Consequence to accept (see `SECURITY_MODEL.md`): because the gate triggers on three *identical
    consecutive* entries of a never-matching passphrase, a coercer who forces you to type one
    chosen wrong passphrase three times in a row will create an (empty) vault; conversely,
    systematic enumeration of *different* wrong guesses never creates one (any differing entry
    resets the streak). Slot 0 is reserved for the Pucker Burn duress credential and is never a
    creation target; blind placement is over the vault pool (slots 1..`SLOT_COUNT`-1) only.

### 3.4 Destruction

**Status (0.9.2-beta): per-vault destruction is NOT built.** This subsection is a locked design
for a future phase, not shipped behavior. What ships today is whole-image destruction only
(account delete removes the entire device image — all vaults, all identities — via the two-marker
no-remanence delete state machine); there is no primitive that overwrites *one* vault's slot while
leaving the others intact, so a user cannot yet destroy vault B alone. `destroy()` stays
whole-image and is documented as such. The per-vault design below stands until that primitive and
its adversarial review land.

- There is no "disable vault" toggle — the capability is structural and always present (§3.1),
  so there is nothing to disable.
- The real, supportable action (future) is **destroying a specific vault's contents and identity
  entirely** — held to the same rigor already established for contact deletion (0.8.4–0.8.6):
  - explicit confirmation (irreversible, destructive);
  - full cryptographic teardown — identity key, all sessions, all message keys, roster, and (once
    it exists) the decoy dummy account — never a soft "hide";
  - the same multi-round adversarial review contact deletion received, since it is the same class
    of bug risk (partial deletion, resurrection after restart, teardown races). The Android
    contact-deletion machinery (durable fail-abort teardown, persisted tombstones, single-worker
    confinement) is the template.

## 4. Vault switching — lock, then unlock (teardown-on-switch)

There is **no dedicated "switch vault" control**, and there must never be one — that would
violate §2 exactly as a "reveal vault 2" button would. Switching is not a distinct mechanism at
all; it is **"lock, then unlock with a different passphrase"**, built entirely on infrastructure
that must exist regardless of vault count:

- An ordinary, unremarkable **"lock now"** action (standard in security-conscious apps — Signal,
  banking apps — requiring no special justification) returns the user to the existing lock
  screen: the same biometric/PIN entry point as any cold launch.
- Whatever passphrase is entered next routes into a vault per the §3.2 router.
- **Auto-lock-on-backgrounding** (standard hygiene, independent of vaults) means many switches
  happen naturally without the user ever touching an explicit control.

**Teardown-on-switch (locked decision).** Locking is the teardown trigger. The moment lock is
invoked (via "lock now" **or** auto-lock-on-background), the currently-live vault's session is
**fully torn down before any re-unlock**:

- all in-memory keys zeroed;
- the relay WebSocket dropped;
- **all notification re-fire timers cancelled** (`NotificationScheduler.cancelAll()`, §7);
- all per-vault runtime state released.

This makes "can two vaults be live/notifying simultaneously" **structurally impossible** rather
than a runtime condition to defend against. A lingering background session would be an
open-ended side-channel (e.g. notification-arrival timing while the user is visibly in the other
vault) — exactly what this architecture exists to prevent. A reconnect delay on switch is an
accepted, bounded cost.

**Friction is intentional.** Someone using a hidden vault is optimizing for undetectability, not
switching convenience. A full re-authentication to move between vaults is an **accepted and
expected** cost of the property. No mechanism that eases switching at the cost of weakening the
authentication boundary is permitted (no shortened switch-PIN, no biometric shortcut into vault
B, no "remember me" window). Any such idea is a tradeoff for the maintainer to decide, never
built by default.

## 5. Zero-knowledge boundary — hard invariant

**Vault unlock and vault routing are 100% local, with no exceptions, forever.**

The relay must never see, store, verify, or be able to infer:

- how many vaults exist on a device;
- which passphrase corresponds to which vault;
- any verifier, hash, or challenge related to vault unlock.

This was already true for the single-vault model (Argon2id derivation and verification are
entirely on-device) and does not change with a second vault. Each vault is just an
independently-pinned identity to the relay — indistinguishable from any two unrelated users'
accounts. **This is a permanent invariant. It must be re-stated in `SECURITY_MODEL.md`** so that
a future convenience feature (e.g. any form of passphrase-recovery assistance) cannot quietly
introduce server involvement in vault unlock without recognizing it breaks this guarantee.

## 6. Threat model & accepted limits

- **Single disk snapshot / compelled disclosure (the target scenario):** unprovable. Fixed-size
  storage image, identical timing, no stored vault count, blind-overwrite on creation — nothing
  distinguishes one identity from two.
- **Multi-snapshot diffing** (adversary images the disk at two times): can see which slot's
  payload region changed, revealing *that* slot is live. Same bound VeraCrypt hidden volumes
  accept; documented, not solved.
- **Blind overwrite on vault creation:** creating a vault into an existing image picks a random
  slot and can destroy a vault whose passphrase is not currently entered (as with a VeraCrypt
  outer volume). Deliberate, documented risk.
- **Biometric → A asymmetry (§3.2):** accepted. A compelled biometric unlock only ever opens A.
- **Compromised device / OS keylogger / second camera:** outside any app's power. Not claimed.

## 7. Notification parity (permanent security requirement)

Notifications are the most likely accidental leak of vault existence, because they fire from
background delivery independent of the unlock UI. Parity is a **security property, not a UX
preference.**

### 7.1 Requirements

1. A notification from a message arriving in **either** vault must be **100% identical in every
   observable way** — same content format, sound, vibration pattern, channel, priority, icon,
   tap behavior, timing behavior. **No** observable difference, however subtle. A notification
   that reveals (through content, timing, sound, or any signal) which vault produced it — or that
   a second vault exists at all — is a **security failure**.
2. Tapping a notification must **not** deep-link into any vault's chat. It opens the app to the
   normal lock screen (the §3.2 entry point) — the same screen as any cold launch. It must never
   bypass unlock or reveal, pre-unlock, which vault (or that a specific vault) has a new message.
3. Each vault's unread/notification state is tracked **completely independently** — separate
   cooldown timers, separate counters, **no** shared state through which one vault's timing could
   be inferred from the other's.
4. If both vaults are independently eligible to fire at the same instant, they must still look
   identical — never combined into a single notification with a merged count (which would itself
   imply how many identities exist). (Under teardown-on-switch, §4, only one vault is ever live,
   so this simultaneity cannot actually occur — but the rendering invariant holds regardless.)
5. A third party — or an automated diff of the notification payload/behavior — must not be able to
   tell which vault produced which notification from the notification alone.
6. This is **permanent and structural** — it holds regardless of future changes to notification
   content, styling, or behavior. It is flagged in code comments at the notification trigger site
   so a future change cannot silently break parity.

### 7.2 How the current implementation satisfies it (0.9.0-beta notification work)

The notification re-fire rework (`NotificationScheduler`, shipped in the same release) was built
parity-ready from day one:

- **Content-free, single fixed notification id.** Every notification is the literal "New message"
  (no count, sender, or preview) under one fixed id — no per-conversation or per-vault ids. This
  is *load-bearing* for parity: there is nothing in a notification that varies by conversation or
  identity. (`MessagingNotifications`.)
- **Extra-free tap intent, no bypass.** The tap `PendingIntent` targets `MainActivity` with **no
  extras** and no `ACTION_VIEW`, so it carries zero conversation/vault identifier and lands on the
  ordinary gate — satisfying requirement 2 today. (Verified: the notification tap is a no-op for
  the deep-link handler, which only acts on `ACTION_VIEW`.)
- **Per-instance, independent timing.** All rate-limit/re-fire state is keyed to the
  `NotificationScheduler` **instance**. A second vault runs a second coordinator + scheduler
  instance with **separate** timers and counters and no shared state — satisfying requirement 3
  structurally. Under teardown-on-switch only one instance is ever live at a time.
- **Teardown hook.** `NotificationScheduler.cancelAll()` cancels every timer; it is invoked on
  every coordinator teardown, so a vault switch (§4) leaves no timer able to fire for the vault
  that was just locked.
- **Slot-agnostic everywhere.** No string, comment, log/diagnostic line, or notification field
  names or reveals a slot. A decompiler reading the notification path learns nothing about vault
  structure.
- **Invariant comments** at the scheduler and at `showNewMessage` state requirement 6 explicitly,
  so a future edit that would break parity is caught in review.

**What remains gated on the Android vault runtime (not yet built):** the *verification* of
cross-vault parity — firing a notification from vault A, then vault B, and confirming an automated
diff cannot distinguish them (requirement 5) — cannot be executed until a second vault/coordinator
exists. When the vault runtime lands, that test becomes: instantiate both, fire from each, assert
byte-identical notification construction and behavior. The structure above makes that assertion
hold by construction; the test is the proof.

## 8. Decoy traffic (adjacent; separate release — 0.10.0-beta)

Specced alongside vaults because they share structure; shipped later. Summary of the locked
design (full spec is out of scope for this document):

- **Paired with real sends**, not independently scheduled. Every real send triggers a paired
  decoy send in random order (decoy-then-real or real-then-decoy) separated by a small random
  delay, so decoys inherit real human timing for free rather than modeling a pattern that could
  itself fingerprint.
- **Daily idle ping (1–2×/day, randomly timed)** covers idle periods so total silence is not a
  signal. It carries little unlinkability burden; sizing/pattern for the standalone ping (lacking
  paired real traffic as cover) is an open question.
- **Per-vault / per-active-identity**, not global — only the currently-unlocked vault (which is
  the only one with real traffic, per §4) generates decoys, addressed to that vault's synthetic
  dummy pinned account and burned near-instantly (~30 ms) so no real contact needs
  decoy-recognition logic.
- **Open questions:** whether the decoy envelope must be size/structure-indistinguishable from a
  real encrypted message (packet-size analysis could otherwise defeat pairing regardless of
  timing); idle-ping sizing.
- **User-facing indicator** (proposed 🍋‍🟩) signals only that the client-side decoy logic *ran* —
  documented, in-app and in docs, as a **mechanism-status indicator, not proof of unlinkability**
  against a real adversary. Security-conscious users verify the send/pairing logic in the
  open-source code instead. This two-audience split is intentional, not a "dummy light".

## 9. Cross-references & required doc reconciliation

- `SECURITY_MODEL.md` — the "Plausible deniability (key-slot vaults)" section is the security
  promise; this document is the implementation architecture behind it. The §5 zero-knowledge
  invariant and the §7 notification-parity requirement must be re-stated in `SECURITY_MODEL.md`.
  The present/near-tense "being built for the current Android release" language should be
  reconciled to the honest state in this document's status table (design locked; crypto primitive
  built on web; Android runtime pending) rather than implying a shipped Android vault.
- `packages/crypto/src/vault.ts` — the key-slot crypto primitive (web/desktop) the Android
  runtime must mirror (fixed-size image, `SLOT_COUNT`, `tryPassphrase` timing parity,
  blind-overwrite placement).
- `NotificationScheduler` + `MessagingNotifications` (Android) — the parity-ready notification
  layer described in §7.
