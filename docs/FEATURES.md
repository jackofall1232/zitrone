# Zitrone — feature reference

**Purpose:** a reference for the 0.11.0 polish pass. Every entry below was verified against source in
`apps/android/app/src/main/java/com/zitrone/app` at `v0.10.3-beta` (vc23). Nothing here is aspirational —
if a feature is planned but not built, it is not in this document.

**Scope:** the Android reference client, which is the only shipped client. The Next.js website is a
download/landing surface, not a client. A web client exists in the repo but is **not deployed**.

---

## 1. Identity and vault

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Passphrase vault** | All keys and messages live inside a single encrypted vault image sealed under a passphrase. The passphrase cannot be recovered — losing it loses the vault. | First launch → "Create your passphrase". Minimum length enforced. |
| **Unlock** | Opens the vault. Until it is unlocked, keys stay sealed and the app shows only the lock screen. | App launch, or after auto-lock → Lock screen. |
| **Biometric unlock** | Unlock with fingerprint/face instead of typing the passphrase. | Settings → Security → *Biometric unlock*. On by default. |
| **Auto-lock when backgrounded** | Re-locks the vault after a chosen idle period in the background. | Settings → Security → *Auto-lock when backgrounded*. Options: immediate, 1 min, 5 min, 15 min. Default 5 min. |
| **Identity key fingerprint** | Shows this device's own identity fingerprint so contacts can compare it against what they see for you. | Settings → Security → *Your identity key fingerprint*. |

**Verified and worth knowing:** the vault image contains a fixed number of slots, and the slot holding
a given vault is chosen at random. This exists so the *number of vaults leaks nothing* — an unoccupied
slot is uniformly random filler, byte-indistinguishable from an occupied one. Second vaults are real
and user-creatable; see §2.

---

## 2. Second vault (plausible deniability)

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Create a second vault** | Creates an additional, fully independent vault with its own passphrase, messages and identity, blind-placed in a random slot. It unlocks straight into the new vault, following the ordinary unlock success path. | At the **normal lock screen**, enter the *same never-before-used passphrase three times, consecutively and uninterrupted*. The third identical entry creates it. |
| **Open a second vault** | Ordinary unlock. Which vault opens depends only on which passphrase you type — the app makes no distinction between them. | Lock screen → type that vault's passphrase. |

**There is deliberately no button, wizard, confirmation dialog, or warning copy for this**, and there
must not be. `VAULT_ARCHITECTURE.md` §2 is titled "there is no button for the second vault" — a
dedicated flow, or a "you are creating a hidden vault" prompt, would itself be the discoverable tell
that defeats the feature. To an observer, the ceremony looks like a user who mistyped twice and got in
on the third try.

**Rules verified in `VaultUnlockRouter` (`CREATE_THRESHOLD = 3`):**
- Only a passphrase matching **no existing slot** can accumulate a streak. A real passphrase always
  unlocks instead — a store match beats create, so you cannot accidentally ritual your way into a new
  vault with a passphrase you already use.
- **Uninterrupted is enforced.** Backgrounding the app (including auto-lock), any session publish
  (a biometric unlock, onboarding), a cancelled create, or process death resets the streak. A stray
  sequence cannot accumulate across sessions.
- Only a SHA-256 digest of the candidate is held between attempts, never the passphrase, compared in
  constant time and wiped on reset.

**Documented limits to carry into polish** (full detail in `SECURITY_MODEL.md`):
- **Blind-overwrite on creation** — nothing in the image distinguishes one identity from two, so a
  create can land on a slot in use.
- **Coercion consequence** — because the gate fires on three identical entries, an adversary who
  compels three repetitions of a passphrase learns the ceremony's shape.
- **Create-persistence timing residual** — a successful create writes to disk; a plain unlock does
  not. It shares the UI path and KDF budget with an unlock, but is *not* claimed to be wall-clock
  identical.
- **Biometric is first-enable-wins** — it binds to one vault at a time and is never repointed while
  it exists. Biometric only ever opens that one vault.
- **Non-recoverability is inherent and undisclosed in-flow** — no reset, no recovery, no support path,
  and deliberately no dialog saying so.

---

## 3. Pucker Burn (duress password)

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Pucker Burn password** | A *separate* password that, when entered at the lock screen, erases everything Zitrone holds on this device — every slot in the vault image, prefs, keystore entries and caches. | Settings → Account → *Pucker Burn password*. |

**Design constraints that are deliberate, not gaps:**
- There is **no readback anywhere** — the app cannot tell you whether one is set. A readback would itself
  be the artifact proving a duress credential exists.
- Consequently, **forgetting it is unrecoverable**, and the setup dialog says so.
- It occupies slot 0, which is excluded from the vault pool. An unarmed slot 0 is uniformly random
  filler, indistinguishable from an armed one.

---

## 4. Messaging

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Encrypted 1:1 chat** | Signal-protocol (Double Ratchet + X3DH) end-to-end encrypted messaging. | Chat list → tap a conversation. |
| **Start a new chat** | Opens the add-contact flow. | Chat list → lemon FAB ("New encrypted chat"). |
| **Search conversations** | Filters the conversation list. | Chat list → search pill at the top. |
| **Delivery states** | Per-message state shown as: `…` sending, `✓` relay stored it, `✓✓` recipient received it, `✓✓` in accent = read, `!` failed. | Chat → message bubbles. |
| **Retry a failed send** | Re-sends a failed message under the same message id, reusing retained in-memory content. | Chat → tap a message showing `!`. |
| **Read receipts** | Sends an encrypted read signal. The relay never learns read status. | Settings → Privacy → *Send read receipts*. On by default. |
| **Typing indicators** | Shows when the peer is typing. | Chat, automatic. Not configurable. |
| **Rename a contact** | A local-only display name. Never sent or synced. | Chat → tap the contact name → *Edit name*. |
| **Unread reminders** | Repeats a notification while new messages keep arriving in an unread chat. | Settings → Notifications → *Repeat unread reminders*. On by default. |
| **Notification sound** | Plays the Zitrone tone; the user can pick another. | Settings → Notifications → *Notification sound* → Change. |

---

## 5. Attachments

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Send a photo (camera)** | Captures in-app through a secure capture activity and sends it encrypted. | Chat → attach (paperclip) → *Take photo*. |
| **Send a photo (library)** | Picks an existing image. Uses the Android photo picker, images only. | Chat → attach → *Photo library*. |
| **Send a file** | Picks an arbitrary document via `OpenDocument` — **not** restricted to images. | Chat → attach → *File*. |
| **Reveal-and-burn images** | A received image stays covered until tapped; tapping reveals it and arms a hard burn timer. | Chat → tap a received image. |

**How it works underneath:** the file is encrypted with its own key and uploaded to the relay as a
*blind blob* — the relay stores it under `sha256(token)` and never sees the token or the key, both of
which travel inside the ratchet-encrypted message. The relay cannot decrypt an attachment.

---

## 6. Disappearing and burning

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Default disappearing timer** | Applies a TTL to new messages. | Settings → Privacy → *Default disappearing timer*. Options: off, 30s, 1m, 5m, 1h, 1d, 7d. Default off. |
| **Per-message timer** | Overrides the timer for the message being composed. | Chat → timer icon in the compose bar. |
| **Burn on read by default** | New messages destroy themselves after the first open. | Settings → Privacy → *Burn on read by default*. Off by default. |
| **Burn a whole chat** | Destroys every message in the conversation. | Chat → burn icon in the top bar ("Burn every message in this chat"). |
| **Burn animation** | Messages visibly dissolve into particles as they burn. | Automatic when a message burns. |

---

## 7. Contacts and verification

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Add by QR** | Shows your contact code as a QR for someone to scan. The code contains only your contact ID. | Chat list → FAB → *Add contact* → your code is displayed. |
| **Scan a contact** | Scans someone's QR, then names them before adding. | Add contact → scan. Rejects your own code with a clear message. |
| **Safety-number verification** | Shows a safety number to compare with the contact over a channel you already trust. | Chat → contact name → *Verify*, or the security badge. |
| **Delete a contact** | Irreversible: removes the conversation, its messages, and the contact's crypto records, and writes a tombstone. | Chat list → **long-press** a conversation → confirmation dialog. |

⚠️ **Polish-relevant:** contact deletion is immediate and permanently irreversible. There is a
confirmation dialog, but the permanence guarantee is not yet stated in user-facing docs — this is a
tracked disclosure item for 0.11.0.

---

## 8. Lemon drops (QR dead drops)

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Seal a QR drop** | Encrypts a message into a one-time QR "drop" hosted on the relay, which burns if unclaimed by a deadline the sender picks. Sealing solves a deposit proof-of-work. | Chat → droplet button in the compose bar. **Off by default** — enable via Settings → Privacy → *Lemon-drop compose button*. |
| **Save/share the sealed drop** | Produces a QR image containing the drop link, to print or send. The image *is* the capability. | After sealing → the sealed-drop screen. |
| **Scan a drop** | Opens a drop sealed for this device. | Chat list → scan icon in the top bar. |
| **One-time open** | Opening consumes the drop; the app then asks the relay to destroy it. | Automatic on open. |
| **Advocacy veil** | When a scanned drop is not for this device (or is unknown), shows an explanatory screen rather than an error. | Automatic on scanning a foreign/unknown drop. |

---

## 9. Network and transport

| Feature | What it does | How the user reaches it |
|---|---|---|
| **I2P routing** | Routes through the official I2P app's local HTTP proxy when present. | Settings → Network → *Use I2P when available*. **On by default**, but inert without the I2P app. |
| **Tor routing** | Routes through Orbot's local SOCKS proxy. Slower, more private. | Settings → Network → *Route through Tor*. Off by default; requires Orbot. |
| **Install prompts** | Direct links to get the I2P app or Orbot, including F-Droid links. | Shown inline in Settings → Network when the app is missing. |
| **Connection status** | States the live transport: I2P, Tor, clearnet fallback, or offline — and warns that clearnet exposes your IP. | Settings → Network → *Connection*. |

**Transport preference order** resolves I2P → Tor → clearnet based on availability and these settings.

---

## 10. Account

| Feature | What it does | How the user reaches it |
|---|---|---|
| **Account ID** | Shows your relay account identifier, or an honest status while registration is still in flight. | Settings → Account → *Account ID*. |
| **Delete account** | Purges every key, prekey and pending envelope. Irreversible. | Settings → Account → *Delete account*. |
| **Interrupted-delete recovery** | If the server delete succeeded but the local vault unlink did not verify, the app routes to a dedicated screen whose only exit is a confirmed destroy. It deliberately never returns to the lock gate. | Automatic; not user-invoked. |
| **Connection diagnostics** | On-device log of registration, connection and send attempts. | Settings → Diagnostics → *Connection diagnostics*. |

---

## 11. Always-on security posture (no user control, by design)

| Behaviour | What it does |
|---|---|
| **Screenshot blocking** | `FLAG_SECURE` is set before any content exists, so screenshots and screen recording are blocked app-wide. Never conditional. |
| **Root warning** | Warns that root access can expose decrypted messages in memory. Dismissible; does **not** block use — blocking would punish power users. |
| **Dark theme only** | There is no light mode. Settings states this explicitly rather than offering a dead toggle. |
| **Cover traffic** | The client emits synthetic traffic so real sends are not distinguishable by timing. **Deliberately has no UI and no setting** — no toggle, no indicator, nothing in Settings. A real send is never delayed, reordered, blocked, or made less durable to produce cover. |
| **Identity Watermarking** | Paints a faint, tiled lattice of the VIEWER'S OWN 60-hex identity fingerprint behind the chat surfaces. A deterrent: anyone photographing the screen is reminded that what they capture is marked as theirs. Always on, no toggle — a toggle would turn a deterrent into a checkbox nobody finds. **Renders locally and reports nothing to anyone.** |
| **Registration proof-of-work** | The client solves a PoW to register, throttling mass account creation. Surfaced as a progress screen during first setup. |

---

## 12. Known gaps and inconsistencies found while writing this

These are polish candidates, verified in source — not speculation.

1. **The attach button's accessibility label says "Attach a photo or file"** while the menu it opens has
   three distinct options (Take photo / Photo library / File). The label is not wrong, but it describes
   the menu rather than the action, and screen-reader users get no hint that a camera option exists.
2. **Reclaim of unsent attachment data covers one route only.** Deleting a contact mid-send reclaims the
   uploaded blob; burns, logout, app teardown and interrupted sends do not, and those blobs wait out the
   relay's expiry. Documented in CHANGELOG for 0.10.3.
3. **Contact-deletion permanence is not disclosed in user-facing docs** (see §7).
4. **`Route.Diagnostics` is reachable only from Settings.** The diagnostics screen is a development aid
   that ships to users; whether it should remain is a tracked 0.11.0 rescope decision.
5. **No light theme** is a stated design choice, but the Settings row is non-interactive — worth
   confirming it does not read as a broken control.

---

## 13. Marketing-mockup audit (checked 2026-07-30)

A feature-list mockup was checked against source. **12 of 14 items verify as shipped in the Android
client.** Two do not, and both are the kind that read as available when they are not.

| Mockup item | Status | Evidence |
|---|---|---|
| Signal Protocol | ✅ Shipped | `libsignal-android`, Double Ratchet + X3DH |
| End-to-end Encryption | ✅ Shipped | §4 |
| No Phone Number | ✅ Shipped | Account IDs only; no phone field anywhere in the client |
| Ephemeral Messages | ✅ Shipped | §6, TTL timers |
| Burn After Read | ✅ Shipped | §6 |
| Zero-Knowledge Server | ✅ Shipped | Blind blob store; relay holds ciphertext it cannot decrypt |
| Screenshot Protection | ✅ Shipped | `FLAG_SECURE`, unconditional (§11) |
| Tor Support | ✅ Shipped | §9, Orbot SOCKS |
| I2P Support | ✅ Shipped | §9, I2P app HTTP proxy |
| Dead-Drop Messaging | ✅ Shipped | §8, lemon drops |
| Plausible Deniability Vault | ✅ Shipped | §2, triple-entry ceremony |
| Decoy / Cover Traffic | ✅ Shipped | §11, since 0.10.0 |
| **Multi-Hop Relay** | ⚠️ **Server only — not in the shipped client** | see below |
| ~~Invisible Watermarking~~ → **Identity Watermarking** | ✅ Shipped, **RENAMED** 2026-07-30 | see below |

### Multi-Hop Relay — real, but not reachable by a user today

The relay implements it (`server/internal/relay/onion.go`: peels exactly one layer, learns only the
next hop, never logs the previous one) and the web carrier has the onion encryption
(`packages/crypto/src/onion.ts`). **The Android client never calls `/relay/forward`.** It is also
gated server-side on `RELAY_PRIVATE_KEY`, which is blank in `.env.example`.

`data/ConnectionMode.kt` describes STANDARD / STEALTH / GHOST modes — STEALTH as *"Tor routing +
3-hop onion relay + decoy traffic"* — and has **zero references anywhere outside its own file**. No
UI, no settings, nothing in `MainActivity`. A user on 0.10.3 gets a single relay hop regardless of
anything they can touch.

**Tracked as a production-release blocker in `todos.md`** (maintainer decision, 2026-07-30): multi-hop
must be finished before production. Beyond the client work, it needs **at least one independently
hosted hop** — multi-hop across one operator's single box is theatre.

Two follow-ups for the polish pass:
- Decide whether `ConnectionMode` is scaffolding for 0.11.0 or abandoned. While it sits unreferenced
  it will keep generating claims like this one.
- Its STANDARD description also claims **Sealed Sender**, which is **not implemented anywhere in the
  repo** — not client, not server. That string is the only place the feature "exists".

### Identity Watermarking — renamed 2026-07-30 (was "Invisible Watermarking")

The watermark is **deliberately visible**. From `FingerprintWatermark.kt`: *"a faint,
toroidally-tiled diagonal lattice of the VIEWER'S OWN 60-hex identity fingerprint… anyone
photographing the screen is consciously reminded that what they capture is marked as theirs."* It is
a deterrent, always on, with no toggle by design.

A deterrent nobody can see deters nobody. Calling it "invisible" does not oversell it so much as
describe a different feature — and invites the reading that Zitrone steganographically marks message
*content*, which it does not do. **Renamed to "Identity Watermarking" by maintainer decision.**

**This name is load-bearing on two live website claims that are false** (tracked in `todos.md`):
`Features.tsx` says *"if something leaks, we know who did it"* — the watermark renders locally with
no telemetry, so there is no "we" and nothing is reported; and `security/page.tsx` says it encodes
*"the recipient and timestamp"* — it encodes the **viewer's own fingerprint**, no timestamp.

---

## 14. What does NOT exist (so it is not mistaken for a gap)

- **No *setup flow* for the second vault** — and that absence is the feature, not a gap (§2). The
  `decoy/` package is cover traffic and is unrelated to vaults.
- **No per-vault cover-traffic isolation** and no multi-vault features beyond create/open (see
  `VAULT_ARCHITECTURE.md` for what is explicitly not built yet).
- **No group chats.** 1:1 only.
- **No voice or video calls.**
- **No message editing.**
- **No cloud backup, sync, or multi-device.** The vault is local; `MessageRepository` is RAM-only, so a
  crash takes undelivered local state with it.
- **No light mode.**
- **No deployed web or desktop client.** Android is the only shipped client.
- **No Play Store distribution.** Sideloaded APK via GitHub Releases and the Tor onion mirror.
