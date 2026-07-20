# Zitrone Security Model

This document describes the full technical security model for users and auditors. It is the
authoritative reference — if the code disagrees with this document, that's a bug (see
[SECURITY.md](../SECURITY.md)).

## Architecture overview

Zitrone is a zero-knowledge, store-and-forward message relay. The server never sees, stores,
or logs plaintext message content under any circumstances — not by policy, but by construction.

```
┌──────────────┐        encrypted envelope         ┌──────────────┐
│  Sender       │ ────────────────────────────────▶ │  Server       │
│  device       │                                   │  (relay only) │
│               │   plaintext NEVER leaves device   │               │
│  • keys       │                                   │  • public     │
│  • encrypt    │                                   │    prekeys    │
│  • decrypt    │ ◀──────────────────────────────── │  • opaque     │
└──────────────┘        encrypted envelope          │    envelopes  │
                                                    └──────┬───────┘
                                                           │ deleted on
                                                           │ delivery ack
                                                    ┌──────▼───────┐
                                                    │  Recipient    │
                                                    │  device       │
                                                    └──────────────┘
```

The server's role is reduced to three functions:

1. Distributing **public** prekey bundles for X3DH key agreement
2. Relaying opaque encrypted envelopes between devices
3. Deleting envelopes the moment delivery is acknowledged

## Signal Protocol implementation

- **Key agreement:** X3DH (Extended Triple Diffie-Hellman) on first contact
- **Session encryption:** Double Ratchet — a new message key for every message, with DH ratchet
  steps providing forward secrecy and post-compromise security
- **Cipher:** AES-256-GCM per-message keys, discarded after use
- **Libraries:** `libsodium.js` (web, wrapped by `packages/crypto`), `libsignal-client` (iOS Swift
  Package and Android Maven)

### Key types

| Key | Curve | Lifetime | Notes |
| --- | --- | --- | --- |
| Identity key | Curve25519 | Long-term | Generated on device; **never leaves the device** |
| Signed prekey | Curve25519 | Rotated every 7 days | Signed by the identity key |
| One-time prekeys | Curve25519 | Single use | Batch of 100 public keys uploaded; consumed once |
| Session keys | — | Per session | Derived via X3DH, advanced by Double Ratchet |
| Message keys | AES-256-GCM | Single message | Derived per message, discarded after use |

#### Identity-key signing scheme differs by platform (server accepts both)

The X25519 (Curve25519) public key used for X3DH's Diffie-Hellman step is
consistent everywhere, but **how the identity key signs the signed prekey and
the login challenge currently differs by platform**, because two different
crypto stacks are in use (see "Libraries" above):

- **Android/iOS** (`libsignal-client`): a single Curve25519 keypair is
  generated (`IdentityKeyPair.generate()`); the same private scalar signs
  directly via **XEdDSA**
  (https://moderncrypto.org/mail-archive/curves/2014/000205.html), libsignal's
  Curve25519-native signing scheme. No separate Ed25519 keypair ever exists.
- **Web/desktop** (`libsodium.js`, `packages/crypto/src/keys.ts`): a genuine
  **Ed25519** keypair is generated first (`crypto_sign_keypair`); its X25519
  form is derived separately, only for the X3DH DH step
  (`crypto_sign_ed25519_pk_to_curve25519`). Signing uses standard Ed25519
  (`crypto_sign_detached`) over the identity key's own Ed25519 form directly.

The published `identity_key` is therefore a Curve25519 u-coordinate from
mobile clients but a genuine Ed25519 point from web/desktop — and the two
platforms sign different byte strings for a signed prekey (mobile signs
libsignal's 33-byte type-tagged `serialize()` form; web/desktop signs the raw
32-byte prekey directly). The server verifies both conventions
(`server/internal/auth/xeddsa.go`'s `VerifyXEdDSA`, tried alongside plain
`ed25519.Verify` in `Register`/`UploadPrekeys`/`VerifyLogin`) rather than
picking one, so neither platform's client needs to change. Since the lemon-drop
Android bridge, the **web/desktop client applies the same try-both logic to
fetched bundles** (`packages/crypto/src/xeddsa.ts` + `classifyBundleIdentity`,
validated against the same real libsignal signature vectors as the server's
port): which scheme verifies decides the identity key's family — and with it
the DH/sealed-box handling — and a bundle verifying under neither is rejected. This split was
discovered while investigating a registration bug that affected mobile only
(web/desktop's Ed25519 path was — and still is — correct); see
`.l00prite/ledger.md` Run 12–14 for the full investigation and the reasoning
for accepting both instead of converging on one. Converging every platform on
a single scheme remains open (tracked in `.l00prite/todos.md`) but is a
separate, larger change, not required for correctness today.

## Key generation and storage per platform

- **Web:** Keys live inside the multi-vault image — a single fixed-size record in IndexedDB (see
  the plausible-deniability section below for the on-disk layout). Each vault's keystore is padded
  to a constant payload size and encrypted with AES-256-GCM under that vault's random key; the
  vault key is unwrapped from a key slot whose per-slot master key is derived from the user's
  passphrase via Argon2id (memory 65536 KB, iterations 3). Note: `libsodium.js` uses an
  internal Argon2id parallelism of 1 and exposes no lane parameter, so the web client cannot honor
  the spec's `parallelism: 4` literally; the value is applied consistently across all web
  derivations, and native clients use the spec value. Keys exist in plaintext only in memory while
  the app is unlocked.
- **iOS:** Identity key in the Secure Enclave where available; all key material in the Keychain,
  biometric-protected (Face ID / Touch ID).
- **Android:** Android Keystore System, hardware-backed where the device supports it; remaining
  local data in EncryptedSharedPreferences.
- **Linux:** Keys stored via the Secret Service API (GNOME Keyring on GNOME desktops, KWallet on
  KDE) using the secret-service Rust crate. If no Secret Service daemon is running, an
  Argon2id+AES-256-GCM encrypted file is used at $XDG_DATA_HOME/zitrone/vault.bin. The
  encryption is performed by packages/crypto (libsodium.js) before the vault blob reaches the Rust
  storage layer — Rust is a storage adapter only.

## What the server stores — and provably cannot store

**Stored:**

- User account ID (UUID — not a username)
- Public identity key (Curve25519)
- Public prekeys (one-time and signed)
- Encrypted message envelopes (opaque blob only)
- Encrypted attachment blobs (opaque, keyed by a token hash — no owner column; see the
  attachments section below)
- Delivery receipts (hash of message ID only)
- Account creation timestamp

**Never stored:**

- Plaintext messages or message content of any kind
- IP addresses
- Device identifiers
- Contact lists
- Read receipts linked to identity
- Any logs that identify users

Messages are store-and-forward only: an envelope is deleted immediately when the recipient
acknowledges delivery, and undelivered envelopes are purged after 72 hours (the sender is
notified). Access logs are disabled; application logs cover errors and system events only and are
purged after 7 days.

## Transport security

- **Protocol:** WSS (WebSocket Secure) over TLS 1.3 for messaging; HTTPS REST for auth/registration
- **Certificate pinning:** NSURLSession pinned SHA-256 hash (iOS), OkHttp `CertificatePinner`
  (Android). **Web:** true certificate pinning is not available in browsers — HPKP was removed from
  every major browser and Service Workers cannot access the TLS certificate chain — so the web client
  relies on CA-chain validation plus HSTS preload. Users who require hard pinning should use the
  native iOS or Android client.
- **Auth:** JWT (RS256, 15-minute expiry) with refresh tokens (7 days, rotated on every use)
- **Headers:** HSTS with preload, strict CSP, `X-Frame-Options: DENY`, `Referrer-Policy:
  no-referrer`, locked-down Permissions-Policy

## Screenshot protection per platform

| Platform | Mechanism | Strength |
| --- | --- | --- |
| Android | `WindowManager.LayoutParams.FLAG_SECURE` on every Activity with message content | OS-level hard block — captures show black |
| iOS | `UIScreen.capturedDidChangeNotification` → instant blur overlay; `userDidTakeScreenshotNotification` → warning banner | Real-time blur for recording; detection (not prevention) for stills |
| Web | `visibilitychange` + window blur → `filter: blur(24px) grayscale(1)` on the message container within 120 ms | Best-effort — full OS-level prevention is out of scope in a browser |
| Linux (Wayland & X11) | Focus-loss blur overlay (same mechanism as the browser) | Best-effort — no compositor-agnostic API exists on Linux to hard-block screen capture |

The web client additionally embeds an **invisible watermark** (canvas steganography encoding
`recipient_id` + timestamp into message backgrounds) so a leaked screenshot can be attributed to
the recipient who leaked it.

**Watermark tradeoff (deliberate).** The watermark cuts against the rest of the metadata-minimization
design, and we keep it anyway — with eyes open:

- It embeds the viewing account's UUID, the conversation peer's account UUID, and a timestamp into
  the chat background — one watermark per conversation view, not per message. The encoding is
  public (this is open source), so _anyone_ holding a lossless capture — not just the sender — can
  extract **both** parties' account UUIDs and bind the two accounts to one conversation at a point
  in time. That is identifying, linking material deliberately added to otherwise identifier-free
  content: a leaked capture is evidence of the very account-to-account association the rest of the
  design denies the server.
- It only survives lossless captures: LSB steganography is destroyed by JPEG recompression, resizing,
  or re-photographing a screen. It deters casual screenshot leaks; it does not stop a determined
  leaker, who can trivially strip it.
- The exposure is bounded in one dimension only: account UUIDs are pseudonymous (no phone/email/name
  behind them), and they appear only in captures of content the leaking party could already see.

We judge leak attribution — a sender being able to prove _which_ counterparty's screen a capture
came from — worth that exposure. Users for whom any embedded identifier, or any capturable proof
that two accounts converse, is unacceptable should weigh this before relying on the web client for
content they may be compelled to defend.

### Image reveal-and-burn (received photos)

Received images render **covered** — the decrypted bytes are never drawn to the screen — until the
recipient taps to reveal. The tap uncovers the image and starts a **hard 10-second timer**
(wall-clock, not idle-reset: backgrounding the app does not pause it). When it elapses the image
re-covers and the message **burns on both ends** via the ordinary `message.burn` signal — the same
mechanism as burn-on-read text, with no new wire message and no server involvement (the relay
already destroyed the blob at first redemption — see [Attachments](#attachments-encrypted-sideloaded-blobs--070-beta)).

The 10-second window is a per-image lifetime, **not** a screenshot control. What actually resists
capture is platform-specific, and we do **not** imply parity across platforms:

| Platform | What reveal-and-burn actually gets you |
| --- | --- |
| Android | The image renders **inside** the `FLAG_SECURE` activity window — it inherits the app-wide flag because it is drawn in the existing Compose tree, NOT in a Dialog or a separate window (which would not inherit it). So the OS hard-blocks screenshots and screen recording of the revealed image, and the bytes leave memory ~10 s after reveal. **This is the only platform with real capture prevention.** |
| Linux desktop (Tauri) | **No OS-level screenshot prevention.** The desktop app renders the web frontend in a WebView; on X11 any client can read another window's pixels, and on Wayland captures are compositor-mediated but the app cannot set a "secure surface" flag. Reveal-and-burn bounds how long the image is on screen and wipes it from memory — it does **not** stop a screenshot taken during the 10 s window. |
| Web (browser) | **No screenshot prevention at all** — browsers expose no API to block capture. Reveal-and-burn is a time-bound deterrent plus a genuine memory-lifetime guarantee (bytes are unrendered until tap, dropped on burn), not a capture control. The browser screenshot caveats above (best-effort focus-blur, watermark) still apply. |

The guarantee reveal-and-burn makes **uniformly**, on every platform, is a **memory-lifetime** one: an
un-revealed image is never drawn, and a revealed one is destroyed on both devices within ~10 s of the
tap **while both apps are running**. Two honest caveats: (a) if the recipient's app or tab dies
mid-window, its copy dies with the process but **no `message.burn` is sent**, so the sender's copy
persists until its own TTL (or a manual burn); (b) browsers throttle background-tab timers, so a
backgrounded web tab may fire the burn late. Capture resistance *during* the reveal window exists
only where the OS provides it (Android).

## Metadata minimization

- No phone number, email, or name required — discovery is by QR code or direct link
- Routing uses opaque UUIDs never exposed to other users directly
- Typing indicators and read receipts are sent as **encrypted signals** — the server can't read them
- Delivery receipts store only a hash of the message ID
- Account deletion is a full, irreversible purge: prekeys, pending envelopes, account record

## Threat model

**Protected against:**

- Server compromise — messages are encrypted before leaving the device
- Man-in-the-middle — certificate pinning + TLS 1.3
- Forward secrecy breach — Double Ratchet key rotation per message
- Screenshot leaks — platform-specific prevention and detection
- Metadata surveillance — minimal metadata, optional Tor routing
- Replay attacks — message nonces and timestamp validation
- Brute force — Argon2id key derivation for all passwords

**Out of scope:**

- A compromised device (OS-level keyloggers)
- Rubber-hose cryptanalysis
- Full OS-level screenshot prevention in a browser or on Linux desktop (Linux exposes no
  compositor-agnostic hard-block API; the desktop app falls back to the same best-effort blur as
  the browser)

## Tor routing

In v1.0, Tor is opt-in, not default. Mobile clients integrate with Orbot; browser users can reach
the deployment's `.onion` address via Tor Browser. The server ships an optional nginx + tor hidden
service configuration (`docker-compose.tor.yml`). **As of v1.5 this is inverted — an anonymous
transport is the default and clearnet is a flagged fallback, along a fixed hierarchy: I2P is the
primary relay transport, Tor is the fallback when I2P is unavailable; see the transport hierarchy
section below.**

On Linux desktop, the app attempts Tor routing by default via a local tor daemon (port 9050) or Tor
Browser (port 9150). For full Tor routing without a running tor daemon, launch via: `torsocks
zitrone`. The connection-mode badge shows Tor status — a yellow dot indicates clearnet fallback
is active.

## Contact verification

Contacts verify each other by comparing Safety Numbers — a SHA-512 fingerprint of both identity
keys — rendered in JetBrains Mono and as a QR code. In-person verification is recommended for
high-security contacts. A changed key triggers a prominent warning until re-verified.

## v1.5 — the security onion

v1.5 adds five layers on top of the v1 zero-knowledge core. The guiding principle is that **each
layer assumes the one beneath it has already failed**: a break in any single layer must not expose
the others.

```
        ┌─────────────────────────────────────────────────────────────┐
        │ Layer 1 — Physical                                           │
        │   panic wipe · duress PIN · plausible-deniability vaults ·   │
        │   FLAG_SECURE · biometric lock · background blur             │
        │ ┌───────────────────────────────────────────────────────┐   │
        │ │ Layer 2 — Network                                      │   │
        │ │   TLS 1.3 · cert pinning · I2P-first · 3-hop relay ·   │   │
        │ │   decoy traffic · obfs4                                │   │
        │ │ ┌───────────────────────────────────────────────────┐ │   │
        │ │ │ Layer 3 — Identity                                │ │   │
        │ │ │   no phone/email · UUID routing · Sealed Sender · │ │   │
        │ │ │   dead-drop mode · QR-only exchange               │ │   │
        │ │ │ ┌───────────────────────────────────────────────┐ │ │   │
        │ │ │ │ Layer 4 — Message                             │ │ │   │
        │ │ │ │   Signal Protocol · Double Ratchet ·          │ │ │   │
        │ │ │ │   256-byte padding · burn-on-read · TTL ·     │ │ │   │
        │ │ │ │   zero server logs                            │ │ │   │
        │ │ │ │ ┌───────────────────────────────────────────┐ │ │ │   │
        │ │ │ │ │ Layer 5 — Storage                         │ │ │ │   │
        │ │ │ │ │   Argon2id (identical timing) · PD vaults │ │ │ │   │
        │ │ │ │ │   AES-256-GCM at rest · Secure Enclave /  │ │ │ │   │
        │ │ │ │ │   Keystore · memory zeroing · secure del. │ │ │ │   │
        │ │ │ │ └───────────────────────────────────────────┘ │ │ │   │
        │ │ │ └───────────────────────────────────────────────┘ │ │   │
        │ │ └───────────────────────────────────────────────────┘ │   │
        │ └───────────────────────────────────────────────────────┘   │
        └─────────────────────────────────────────────────────────────┘
```

### Plausible deniability (key-slot vaults)

Two (expandable to four) completely separate encrypted vaults sit behind two different passphrases.
There is no cryptographic evidence that a second vault exists.

- **Key slots.** Every disk image holds a fixed `SLOT_COUNT` slots, each a 16-byte salt plus an
  AES-256-GCM-wrapped 32-byte vault key. Unused slots hold uniformly random bytes that are
  byte-for-byte indistinguishable from a real wrapped key. The integer number of vaults is never
  stored anywhere; a slot that fails to decrypt is indistinguishable from a wrong passphrase.
- **Timing parity.** `tryPassphrase` derives a key for, and attempts to unwrap, **every** slot with
  no early exit. The wall-clock time is identical whether a passphrase matches slot 0, slot 1, or
  nothing — a stopwatch cannot distinguish a decoy unlock from a real one. (See the timing-parity
  test in `packages/crypto`.)
- **Independence.** Each vault has its own random vault key and its own server account, identity key,
  and prekey bundle. The server cannot link them. Decrypted vault contents live in memory only and
  are zeroed on background.
- **On-disk image.** Everything at rest is ONE fixed-size byte image stored under a single
  IndexedDB key (or handed as one opaque blob to the desktop keystore adapter):
  `version(1) ‖ SLOT_COUNT × [salt(16) ‖ wrapped key(60)] ‖ SLOT_COUNT × payload(256 KiB)`. Every
  payload region is exactly the same size whether it holds a real vault or filler. A real payload
  is the vault's keystore padded to the region's full plaintext capacity and **then** encrypted
  (pad-then-encrypt — the length prefix sits inside the AEAD ciphertext, so no plaintext structure
  ever reaches disk); a filler payload is uniform CSPRNG output, indistinguishable from ciphertext.
  The image size is a compile-time constant regardless of vault count. Deleting a vault overwrites
  its slot and payload with fresh random bytes — the image never shrinks, moves, or records that a
  vault was ever there. Because every payload region is the same size, unlocking any vault performs
  identical cryptographic work (per-slot Argon2id and a constant-size payload decrypt), preserving
  the timing-parity contract. The one residue: post-decrypt JSON parsing of the winning vault scales
  with its contents — low single-digit milliseconds against seconds of fixed KDF work, and it occurs
  only after the vault is already being opened for display.

This mirrors the VeraCrypt hidden-volume legal model: a user compelled to reveal passphrase A opens
a real, working profile while revealing nothing about whether passphrase B exists.

Two VeraCrypt-analogous caveats apply, and are accepted deliberately:

- **Multi-snapshot diffing.** An adversary who images the disk at two points in time can see which
  slot's payload region changed between snapshots, revealing that _that slot_ is live. A single
  snapshot — the compelled-disclosure scenario the design targets — reveals nothing. This is the
  same bound VeraCrypt hidden volumes accept.
- **Blind overwrite on vault creation.** Which slots hold live vaults is unknowable from storage —
  that is the point — so creating a new vault into an existing image picks a random slot and can
  destroy a vault whose passphrase is not currently entered, exactly as writing to a VeraCrypt
  outer volume without mounting the hidden one can. Creating a vault on a device that may hold
  others is a deliberate, documented risk.

### Transport hierarchy (I2P primary, Tor fallback)

An anonymous transport is now the **default**; clearnet is a fallback shown with a visible warning
indicator (a yellow dot on the connection-mode badge — informative, not alarming). The relay
transport hierarchy is **fixed, not user-selectable**: I2P is the primary relay transport, Tor is
the fallback when I2P is unavailable, and clearnet is the last resort. This replaced the earlier
v1.5 `tor_first`/`i2p_first` user-choice model. Mobile clients integrate **external router
apps** rather than embedding routers: Orbot for Tor (opt-in), and on Android the i2pd router app
for I2P (auto-detected; primary transport when present, 0.7.0-beta). In-process embedding was
considered and rejected — no maintained embeddable I2P artifact exists, and bundling routers cuts
against the project's dependency philosophy. Browser clients auto-detect an `.onion`
host. Only v3 onion addresses are used. Full rationale for I2P-first is in
[`docs/TOR_ARCHITECTURE.md`](TOR_ARCHITECTURE.md) §6.

Transport anonymity and message confidentiality are independent: clearnet fallback affects
anonymity only — it never weakens encryption. Messages are Signal Protocol end-to-end encrypted
regardless of which transport carries them.

### Tor architecture (three hidden services)

The server runs **three** separate Tor v3 hidden services on the same box, sharing one Go binary and
one internal port and distinguished by the request `Host` header:

- **Public download mirror** — published; serves the static no-JS APK mirror.
- **Secret resilience mirror** — unpublished, word-of-mouth; identical mirror content, separate
  `.onion`, so it survives a targeted takedown of the public address.
- **Relay onion** — unpublished, baked into the app binary; serves the API only (no mirror), giving
  clients anonymity when messaging.

The honest anonymity claim is **client anonymity, not server anonymity**: the relay onion hides the
*client's* IP from the server, but the server's Hetzner IP is publicly associated with the service
via clearnet DNS. `HiddenServiceNonAnonymousMode` is never set, and no `Onion-Location` header is
ever emitted (it would auto-advertise the secret mirror).

The transport fallback chain is **I2P (primary) → Tor (fallback) → clearnet (last resort,
warned)** — fixed, not user-selectable. Clearnet fallback can be disabled in Settings → Network, in
which case the app refuses to connect rather than going clearnet. Full detail, including the
threat model and key backup, is in [`docs/TOR_ARCHITECTURE.md`](TOR_ARCHITECTURE.md).

| Threat | Protected? | Notes |
| --- | --- | --- |
| Client IP exposed to relay | ✅ via I2P or Tor | I2P is primary relay transport: live on server, Linux desktop (REST + WS, verified 2026-07-02), and Android via the external i2pd router app (0.7.0-beta; live-network verification pending); skeleton on iOS/browser — chain falls to Tor which hides client IP via the relay onion |
| Server location hidden | ❌ | Hetzner IP is public; this is honest and documented |
| APK distribution takedown | Partial ✅ | Two mirrors (public + secret), more nodes planned |
| Clearnet traffic analysis | ⚠️ Fallback only | Clearnet is last resort with explicit warning; message confidentiality is unaffected — only anonymity |

### Dead-drop mode

Asynchronous, anonymous deposit with no direct channel between the two parties:

- A drop is a capability. A 256-bit one-time **token** is shared out of band; the relay stores the
  envelope under `drop_id = SHA-256(token)` and never sees the token until redemption.
- Deposit requires **no account** — a hashcash proof-of-work bound to the drop ID stands in for
  auth, so anonymous deposit costs CPU instead of being free to spam.
- The drop table has **no sender column**, by construction. Redemption presents the token, returns
  the envelope, and destroys the drop in one operation. A replayed token returns 404. Uncollected
  drops are purged at their 72-hour TTL.

### QR dead drops — "lemon drops" (0.8.0)

A second dead-drop variant with a deliberately **different property set** from the anonymous
`/drops` primitive above: a lemon drop is **recipient-targeted by design, not anonymous**. The
creator picks one existing contact, the message is encrypted **once, at creation time**, to that
contact via a one-shot X3DH against their published prekey bundle (no live session on either
side), and the entire envelope — sender, recipient, ratchet header, plus a fresh **burn token** —
is sealed to the recipient's identity key. The QR sticker encodes only
`https://zitrone.app/d/{qr_id}`: a pointer at the sealed blob on the relay, never the ciphertext
itself.

- **The relay is a blind, non-destructive shelf.** It stores an opaque sealed box under a
  16-byte creator-random `qr_id` with no sender or recipient column; deposit is unauthenticated
  (hashcash proof-of-work is the only admission, so the deposit request itself carries no
  account); it serves the **same blob to anyone** who presents the id, with no identity check
  and no key-matching — all recipient-matching happens on the scanning device, by whether the
  sealed box opens. Fetch deliberately does **not** destroy the drop: the relay cannot know
  whether a decrypt succeeded, so destroying on first fetch would let a wrong-recipient scan
  burn the message out from under the intended recipient.
- **Honest limit — deposit adjacency.** Creating a drop requires fetching the recipient's
  prekey bundle, and that fetch is authenticated. A relay watching its own traffic can
  therefore correlate the authenticated bundle request with the anonymous deposit that follows
  moments later on the same connection, and infer **who likely created a drop for whom** —
  the same class of metadata the ordinary send path already exposes, but worth stating because
  the deposit alone would otherwise look unlinkable. The sealed content, the wrong-scanner
  blindness, and the burn capability are unaffected. Fetching prekeys on an unlinkable
  schedule (decoupled in time and transport from deposits) is tracked follow-up work, not a
  property of the current implementation. The same adjacency exists on redemption when the
  sender is not yet a contact (an authenticated bundle fetch follows the anonymous blob
  fetch).
- **Honest disclosure — read this one plainly:** because the relay serves the blob to any
  scanner, **non-recipient devices transiently receive ciphertext that was meant for someone
  else.** They cannot decrypt it — the seal is to the recipient's identity key, and opening it
  fails on any other device — and they cannot burn it, but they do briefly hold the sealed
  bytes. This is inherent to a publicly scannable sticker backed by a blind relay, and we state
  it rather than soften it.
- **Burn-on-claim.** The 32-byte burn token rides *inside* the encrypted payload; the relay
  stores only its SHA-256. Only a device that successfully decrypted the drop learns the
  preimage, and its client then presents it (unauthenticated) so the relay shreds the blob —
  wrong scanners can fetch but can never burn. The burn is a courtesy shred, not a correctness
  requirement: unclaimed drops are crypto-shredded at their creator-chosen TTL (**24 h, 48 h,
  72 h, 1 week, or 2 weeks** — a fixed bucket allowlist; arbitrary lifetimes would fingerprint
  a drop, and there is deliberately no 1-month option). Missing, expired, and burned drops are
  all the same 404 — a prober learns nothing — and after expiry or claim the physical sticker
  permanently degrades into a harmless pointer: scans fall through to the marketing site or the
  in-app "not for this device" screen, with no lingering security exposure.
  - *Read-once is enforced by the burn, not the crypto, when no one-time prekey was used.* A
    reading device deletes the one-time prekey the drop consumed, so a re-scan of an
    OTP-bearing drop can no longer reconstruct the responder session (it fails closed).
    But when the creator's fetched bundle had **no** one-time prekey left (the recipient's
    stock was exhausted), the drop is decryptable from the identity + signed prekey alone —
    so until the best-effort burn lands or the TTL fires, the *intended recipient* can
    re-open their own already-read message on a re-scan. This is a property of the protocol
    (identical on web/desktop), not a confidentiality loss — the drop stays sealed to that
    one recipient throughout — and the TTL is the hard backstop. Keeping the client's stock
    replenished (the low-water-mark upload) makes the no-OTP case rare.
- **A dead sticker stays dead — the tombstone tradeoff.** Burn and expiry do not delete the
  drop's row; they crypto-shred its ciphertext and burn hash and keep the `qr_id` forever as a
  tombstone, so no one — including the sticker's creator — can ever deposit a new drop under a
  used id. This closes the "sticker re-arming" hole (a dead sticker silently delivering again).
  The honest cost: the relay permanently retains one 16-byte random identifier plus its expiry
  timestamp per drop ever minted. A tombstone links to no account, names no sender or recipient,
  and holds no content — but it is retention on an otherwise shred-everything store, and we state
  that plainly rather than hide it.
- **Sender identity is claimed, then verified.** Opening the seal proves who a drop was
  addressed *to*, not who wrote it. The payload's claimed sender identity key is cross-checked
  before anything renders: against the stored key when the sender is already a contact, or
  against a freshly fetched prekey bundle when not — any mismatch and the message is refused.
- **Replies to a drop-created contact — the guarded session reset.** A contact created at
  redemption starts a second, independent session, and the drop's creator previously decrypted
  replies only against its original one — so a first reply was silently undeliverable. The
  receive path now performs a deliberately narrow recovery: when the stored session fails to
  decrypt AND the envelope carries an X3DH initial-message header, the client responds to that
  handshake keyed on the **pinned** contact identity key (never a freshly fetched bundle) and
  replaces the stored session only if the envelope then decrypts. The pinned key is mixed into
  the X3DH secret, so only the holder of that key's private half can produce an envelope the
  reset accepts; ordinary decrypt failures carry no handshake header and are dropped exactly as
  before. Known residual corner, stated plainly: replaying a contact's original initial message
  is inert whenever it consumed a one-time prekey (deleted on first use), but if it was built
  without one — the recipient's stock had run out — a replay can wind the session back and wedge
  the conversation until either side re-establishes. That is a denial-of-service corner for a
  relay-level adversary, not a confidentiality loss.
- **One-way by design — a drop is not a conversation.** A lemon drop has exactly two exits:
  delivered to its one true recipient, or expired unclaimed — both destroy it. There is **no
  reply path, no session continuation, and no expectation of one**: the one-shot X3DH session
  is discarded on both ends, and a sender learned from a drop is **not** a conversation partner
  until a separate, ordinary contact/session establishment happens through the normal
  add-contact flow. (On web/desktop, redeeming does additionally spin up an ordinary outbound
  session for convenience; on Android it deliberately does not — see below — and cross-family
  conversations remain unsupported either way, so adding an Android contact from web enables
  addressing *drops* to them, not chatting with them.)
- **Cross-family addressing (the Android bridge).** Web/desktop identity keys are Ed25519;
  Android/iOS (libsignal) identity keys are Curve25519 — the same X25519 DH underneath, but
  different published point encodings and different prekey-signature schemes. The creator side
  is now **family-aware by verification, never by guessing**: it verifies a fetched bundle
  under plain Ed25519 (raw prekey) *or* XEdDSA (33-byte type-tagged form) — the same try-both
  logic the relay has always applied, ported client-side (`packages/crypto/src/xeddsa.ts`,
  tested against the identical real-libsignal signature vectors as the server's verifier) —
  and whichever scheme verified decides how the identity key enters the DH and the sealed box.
  A bundle that verifies under neither scheme is rejected outright. This cross-family path is
  **scoped to lemon-drop creation only** (`x3dhInitiate`'s `allowCrossFamily`): ordinary
  messaging still refuses a mobile bundle, because a web↔mobile *session* would exchange
  ciphertext neither ratchet can parse — a drop escapes that only because it is a one-shot
  sealed payload with a matching one-shot opener, not an ongoing session.
- **Android vs iOS is indistinguishable on the wire — the honest gap.** Both mobile platforms
  publish the same Curve25519/XEdDSA bundle, and the zero-knowledge server stores nothing that
  says which. So the creator cannot programmatically tell an Android recipient (has a lemon-drop
  opener) from an iOS one (has none yet). A drop addressed to an iOS contact is still sealed to
  their real key and deposited — **it simply expires unopened and is shredded at its TTL; no
  content leaks** (only that recipient could ever open it, and their client has no opener).
  Because the creator hands a physical sticker to a specific person they know, the platform is
  human-known in practice; a wire-level capability signal that would let the software refuse an
  iOS recipient up front is deferred follow-up work, not part of this release.
- **Platform status, honestly.** Web and Linux desktop have the full flow (create and redeem).
  Android cannot create drops, but it can now **be a true recipient**: a scan performs one
  fetch (network-indistinguishable from any other scanner) and one open attempt in a
  **self-contained one-shot responder** (`LemonDropOneShot`) that mirrors the web stack's
  bytes exactly and is deliberately separate from ordinary libsignal messaging — it never
  touches a session, and ordinary message decryption is unreachable from it. Two honest costs,
  stated plainly: it needs raw private scalars from the encrypted key store (a narrow,
  documented exception to the "private key bytes never leave the store" invariant, confined to
  one private bridge), and it adds libsodium via the pinned `lazysodium` binding for the
  sealed-box open. A decrypted drop renders only after an explicit biometric unlock — the
  pre-unlock veil holds no plaintext — and delivery then consumes the one-time prekey and
  burns the relay's copy; dismissing before unlock burns nothing, leaving the drop
  re-scannable. Every non-delivery outcome (not ours, malformed, sender cross-check failed,
  no identity on device) collapses into the same warm advocacy screen a wrong scanner has
  always seen. iOS has none of this yet. `assetlinks.json` ships with the marketing site and
  the site serves the ordinary marketing page at `/d/{id}`, so an unverified or app-less scan
  lands on the homepage (see `docs/RELEASING_ANDROID.md` for verification propagation, which
  can take days).

### Attachments (encrypted sideloaded blobs — 0.7.0-beta)

Images and files never ride inside a message envelope. The sender encrypts the attachment
under a **fresh random AES-256-GCM key**, pads the ciphertext to **64 KiB buckets** (so the
stored size reveals only a bucket count), and uploads it to a **blind blob store** on the
relay; the message then carries only a small control payload — token, key, hash, size,
type — inside its ordinary ratchet-encrypted plaintext.

- **The wire stays uniform.** The envelope's cleartext `media_type` field remains `"text"`
  for attachment messages — the reserved `"image"`/`"file"` values are deliberately never
  emitted, because labeling an envelope would hand the relay per-message attachment
  presence. Like read receipts, attachments are recognized only after decryption; the
  256-byte envelope padding (and decoy-traffic indistinguishability) is unaffected.
- **The blob store is blind by the dead-drop construction.** A blob is stored under
  `SHA-256(token)` with no sender, recipient, or account column; upload is
  JWT-authenticated purely as spam control, while **redemption is unauthenticated** — the
  token is the capability, so the relay cannot link a fetch to an account. Redemption
  atomically returns and destroys the blob (fetch-and-burn; single-use; a replay
  returns 404), and unredeemed blobs are purged at a 1-week fallback TTL.
- **Integrity is sender-bound.** The control payload carries the plaintext's SHA-256 and
  length; the recipient verifies both after decryption and rejects any mismatch, so
  neither the relay nor a blob-ID guesser can substitute content.
- **Metadata hygiene.** Images are downscaled and re-encoded on the sending device, which
  strips EXIF (location, camera identifiers) before encryption; image filenames are never
  transmitted. Size cap 8 MiB.
- **At rest.** Decrypted attachment bytes follow each platform's message-storage policy —
  on Android that means memory only, never a database, file cache, or disk; saving a
  received file is an explicit user action through the system file picker, the same
  sanctioned path as the user copying text.
- **Unknown control payloads never render.** A payload shaped like a control message that
  a client does not recognize (a newer client's feature, or an attachment that failed
  validation) renders as a generic "unsupported message" placeholder — never as raw text,
  which could paint key material into a chat bubble.

### Decoy (cover) traffic

A background generator emits fake encrypted envelopes at Poisson-distributed intervals so that a
network observer cannot tell when a real message is sent — active and idle are indistinguishable. A
decoy is byte-for-byte the same size as a real message (both padded to 256-byte blocks), uses the
same submission path, and is addressed to a random UUID that resolves nowhere. Intensity is
selectable (off / low / medium / high) and auto-reduces on low battery.

### Multi-hop relay

Messages can be onion-routed through three relay nodes. Each layer is a sealed box to one relay's
Curve25519 key; a relay peels exactly one layer, learning only the next hop — never both ends of the
path. Path selection forbids two hops in the same Autonomous System and prefers geographic
diversity; circuits rotate after 100 messages or 10 minutes, and the guard (first) hop rotates only
weekly. An adversary must compromise all three relays *and* correlate timing — and decoy traffic
defeats the timing correlation.

### Connection modes

Three user-selectable bundles compose the network layer:

| Mode | Tor | Relay hops | Decoy traffic | Dead drop |
| --- | --- | --- | --- | --- |
| **Standard** | yes | 1 | off | no |
| **Stealth** | yes | 3 | medium | no |
| **Ghost** | yes | 3 | high | yes (every message) |

### Privacy view & platform warning (UI layer)

Two UI-only defenses that never touch the crypto or the envelope:

- **Privacy view** blurs message content behind a frosted lemon overlay, revealed only while you
  actively interact (hold-to-reveal, tap-timed, or tap-toggle). On a browser screenshot, the blurred
  state is what gets captured.
- **Platform warning** honestly tells a user when a participant is on a browser, where OS-level
  screenshot protection is unavailable — a dismissible lemon-yellow note, never a modal.

### Fingerprint watermark — "security paper" (0.8.1)

Every chat surface (chat, conversation list, and Android's lemon-drop reveal veil) renders over a
faint, tiled, diagonal pattern of the **viewer's own** identity-key fingerprint — the same 60-hex
value shown in Settings — with message bubbles slightly translucent so the pattern reads through
the conversation at any scroll position. **It identifies whoever's screen a photographed
conversation came from, not the sender.**

- **This is a deterrence layer, not a forensic-grade anti-leak guarantee.** The goal is that a
  person pointing a camera at the screen consciously registers "this capture is marked as mine"
  and hesitates. The mark is faint by design, does not survive deliberate removal, cropping to a
  blank region, or heavy re-editing, and we make no stronger claim.
- **Always-on by design — there is no setting to turn it off.** A deterrent that anyone can
  disable in Settings is a checkbox, not a deterrent; its value is precisely that it is never
  negotiable. This is the one UI-layer defense that is not user-configurable, and we state that
  plainly rather than hide the absence of a toggle.
- **Local-only.** The fingerprint is already known to the device (it is the identity key's
  display form); rendering it touches no network, no crypto path, and no key material beyond the
  public key's existing display derivation.
- **On web/desktop the visible pattern and the invisible leak-attribution watermark are one
  image.** The pre-existing steganographic layer (viewer id + timestamp in pixel LSBs) is embedded
  into the visible tile's own pixels — composed, not layered — so a screenshot carries both. The
  carrier renders at device-pixel resolution so the hidden layer survives high-DPI displays on
  integer scale factors; on fractional scales it is best-effort. **Honest limit —** the invisible
  layer does not survive lossy re-encoding or scaling of the captured image; the visible layer is
  the deterrent, the invisible one is corroboration when a capture is shared pristine.

### Saving a lemon-drop sticker for printing (0.8.1, web/desktop)

The QR-drop modal can save a print-grade PNG of the sticker (full quiet zone, burn-by caption) so
a drop can be physically placed — the intended dead-drop workflow. **Honest cost, stated in the
modal itself:** the saved file contains the drop link, persisted to disk by the user's own choice.
The app treats it exactly like the printed sticker — it does not track, manage, or delete it. On
desktop the file write happens natively behind the OS save dialog; the WebView never supplies a
filesystem path.

## Audit history

See [AUDIT.md](../AUDIT.md). No third-party audits have been completed yet — treat the
implementation accordingly.
