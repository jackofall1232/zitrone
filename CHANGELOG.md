# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **QR dead drops — "lemon drops."** Seal a message to one chosen contact as a printable QR
  sticker: the code carries only a `zitrone.app/d/{id}` pointer at a sealed blob on the relay,
  encrypted once at creation via one-shot X3DH (no live session touched). The relay is a blind,
  **non-destructive** shelf — it serves the same opaque blob to any scanner, and only the true
  recipient's device can open it; a recovered burn token then shreds the drop on claim, and a
  creator-chosen TTL (24 h / 48 h / 72 h / 1 week / 2 weeks) shreds the unclaimed. Web/desktop
  ship the full create + redeem flows (lemon-slice-branded QR at error-correction H, paste-link
  redeem, warm "part of the network" screens for not-for-this-device and expired scans); Android
  ships link interception + the advocacy screen only — honestly, with no decrypt attempt, since
  no current drop can be addressed to an Android-family account (documented; real Android
  redemption is the follow-up, crypto-review-gated). **Unlike `/drops` this variant is
  recipient-targeted, not anonymous — and non-recipient scanners transiently receive ciphertext
  they cannot decrypt**; both properties are disclosed in `docs/SECURITY_MODEL.md`. App-Link
  verification (`assetlinks.json`) and `/d/*` website hosting are operator steps deferred until
  end-to-end verification, per the deliver-then-claim rule.

## [0.7.6-beta] - 2026-07-19

### Added

- **Image reveal-and-burn.** Received photos now render **covered** — the
  decrypted bytes are never drawn to the screen — until the recipient taps to
  reveal. The tap starts a **hard 10-second window**, after which the image
  re-covers and the message **burns on both ends**, reusing the existing
  `message.burn` signal (no new wire messages, no server logic — the relay
  already fetch-and-burns the blob at receive-time redeem). Unconditional for
  every received image. Screenshot resistance is documented **honestly
  per-platform** in `docs/SECURITY_MODEL.md`: Android hard-blocks capture via
  `FLAG_SECURE` (the image renders in-tree, inheriting the flag); Linux desktop
  and web browsers cannot prevent OS-level screen capture — reveal-and-burn is a
  memory-lifetime guarantee there, not a capture control.

## [0.7.5-beta] - 2026-07-19

### Changed

- **Attachment blob fetch-and-burn + 1-week unfetched fallback TTL.** Successful
  blob redemption already deleted the ciphertext in the same SQL statement
  (`DELETE … RETURNING`); this is now documented as the hard contract
  (fetch-and-burn). The fallback TTL for ciphertext that is *never* collected
  moves from 72 hours to **1 week** (`BLOB_TTL_HOURS=168`, matching the protocol
  constant), purged via the same janitor path as expired undelivered envelopes.
  The `<= 0` clamp default follows to 168h. No wire-format or schema change; the
  relay's blind-blob construction is unchanged.

## [0.7.4-beta] - 2026-07-19

### Fixed

- **Contacts no longer lost on app update (P0).** The contact roster was held only
  in memory and was silently wiped on every full process restart — an in-place APK
  update always triggers one, so contacts vanished on update. The roster now
  persists in EncryptedSharedPreferences (with the pinned identity key and verified
  state, so the anti-key-substitution guard survives), reseeds at boot, and never
  overwrites a stored roster it failed to read. Installs updating from an earlier
  build have their past contacts reconstructed from existing session/identity keys
  (as "Unnamed contact", re-nameable), then persist normally from here on.
- **Photo-picker attachments.** Attaching via the photo/camera-roll picker failed
  with "Couldn't attach that" for every image (a bounds-only image decode returns
  null on success, which the code treated as a failure). The file picker was
  unaffected. Both paths now work.
- **Relay robustness.** The server clamps a non-positive `BLOB_TTL_HOURS` /
  `BLOB_MAX_BYTES` to safe defaults (a `<= 0` TTL silently 404'd every attachment
  fetch), and `docs/SELF_HOSTING.md` now documents the reverse-proxy body-size limit
  (>= 12 MB) attachments require. Attachment delivery also requires the relay to run
  a build that includes the blob store — see `docs/RELEASING_RELAY.md`.

### Changed

- **Honest message send-state.** A sent message no longer shows a delivered tick the
  instant it is handed to the socket. Messages now progress SENDING -> SENT (the
  relay has stored it) -> DELIVERED (the recipient's device received it) -> READ, and
  a failed send shows a FAILED state with tap-to-retry instead of an indefinite
  spinner. The delivery receipt is recipient-originated and peer-routed, so the relay
  still never learns who sent a message (zero-knowledge preserved). Applies across
  server, Android, and web/desktop.

## [0.7.3-beta] - 2026-07-19

### Changed

- **Android I2P retargeted from i2pd to the official I2P app.** The Android relay
  transport now wires the **official I2P app** (`net.i2p.android` on Play /
  `net.i2p.android.router` on F-Droid) via its local **HTTP proxy** at
  `127.0.0.1:4444`, replacing the former i2pd SOCKS5 path (`127.0.0.1:4447`). One
  opaque HTTP `CONNECT <b32>:80` tunnel now carries both REST and WebSocket — the
  same mechanism the Linux desktop uses — so the proxy cannot see or rewrite the
  `Authorization` / `Sec-WebSocket-Protocol` headers. **Why:** real-device testing
  found i2pd failed to build tunnels reliably on a physical device, while the
  official I2P app warmed up and stayed healthy (~73s to first peers, ~13 active /
  513 known by 3 min, ~50% green by 5 min). i2pd is still *detected* only to hint
  that the official app is now the wired router. Readiness is a real HTTP CONNECT
  returning `HTTP/1.x 200` (an unreachable dest returns `504`); the background
  promotion-poll budget grew 15s -> 30s to cover a first-leaseset lookup (~19s
  measured). See `docs/TOR_ARCHITECTURE.md` §7. Chain-resolution and CONNECT wire
  encoding are unit-tested; an opt-in JVM integration test
  (`I2pLiveIntegrationTest`, gated on `I2P_TEST_DEST`) exercises the real proxy.

## [0.7.1-beta] - 2026-07-19

### Fixed

- **Android transport clarity + concurrency.** The Network settings toggle
  "Route through I2P" is renamed **"Use I2P when available"**, and its
  on-but-no-router state now says plainly that the normal connection is used —
  the old default-on "Route through I2P" label read as "routing is active" and
  produced a false "app defaults to I2P on fresh installs" report. (The
  transport resolver already defaulted a router-less fresh install to clearnet;
  this is a labeling fix, not a behavior change.)
- **Atomic transport swap.** `ApiClient` and `WsClient` held the OkHttp client
  and endpoint URL as two independently-updated fields; a transport
  promotion/demotion (I2P <-> clearnet) mid-request could pair a mismatched
  client and URL and fail spuriously. Both now hold the pair in a single
  immutable value swapped atomically.

### Note

- No functional change to registration or the I2P/Tor/clearnet resolver chain.
  A separately-observed relay `store_failed` during testing was root-caused to
  a full disk crashing the relay's Postgres (environmental), not a code defect.

## [0.7.0-beta] - 2026-07-18

### Added

- **I2P relay transport on Android** via an external i2pd router app (`org.purplei2p.i2pd`,
  SOCKS5 `127.0.0.1:4447`), Orbot-style — no bundled router. The fixed I2P -> Tor -> clearnet
  chain now resolves live on Android: real SOCKS5 tunnel-readiness probing (a listening proxy is
  not a ready router), background promotion while tunnels build (1-3 min), and demotion with
  automatic re-promotion if the router stops mid-session. The relay's `.b32.i2p` destination is
  baked at build time (`RELAY_I2P_DEST`); cleartext is permitted for `b32.i2p` subdomains only
  (the B32 address is the destination's identity — no TLS/pin over I2P). The official Java I2P
  app is detected (both package ids) for UI guidance but not wired in v1.
- **End-to-end encrypted image/file attachments** on web, desktop, and Android (iOS renders an
  honest placeholder; full iOS support is future work). Signal-style sideloading: each attachment
  is encrypted under a fresh random AES-256-GCM key, bucket-padded to 64 KiB, and stored in a new
  blind relay blob store (blob under SHA-256(token), no owner columns, unauthenticated single-use
  redemption, 72 h TTL — the dead-drop construction). The message carries only a small control
  payload inside its ordinary ratchet-encrypted plaintext; on the wire an attachment message is
  `media_type: "text"` (the reserved "image"/"file" values are deliberately never emitted — the
  field is relay-visible). Images are downscaled/re-encoded on-device, stripping EXIF before
  encryption; recipients verify size + SHA-256 after decryption. 8 MiB cap. Android renders
  attachments from memory only — never a cache dir. **Requires a relay running this release**
  (`/api/v1/blobs` endpoints); against an older relay, attachment sends fail cleanly and text
  messaging is unaffected.
- **Website**: new tagline "Privacy, with zest." sitewide, a one-time hero lemon entrance
  animation (droplet -> splash -> lemon -> cross-cut -> spin; respects `prefers-reduced-motion`),
  and the `/why-zitrone` brand-story page.

### Security

- Control-shaped message payloads that a client does not recognize (newer clients' features, or
  malformed attachment payloads) now render as an "unsupported message" placeholder on every
  platform — never as raw text, which could paint key material into a chat bubble.

## [0.6.0-beta] - 2026-07-17

### Changed

- **Zitrone rebrand — forked from sublemonable.** This repository is a fork of the
  `sublemonable` project at its 2026-07-17 state, renamed throughout (code identifiers, app IDs,
  package names, docs, marketing). Version numbering restarts at 0.6.0-beta; entries below this
  header are the pre-fork history with the old name swept to Zitrone in place. New app identifiers
  (`com.zitrone.app` / `org.zitrone.app`) mean Zitrone installs as a NEW app alongside any
  existing sublemonable install — identities and history do not carry over. Wire-protocol
  constants shared with the still-live sublemonable relay (login-challenge format, relay
  endpoint/pins, safety-number prefix) are deliberately NOT renamed — see the
  `TODO(zitrone-cutover)` markers; they change only in lockstep with the deferred server cutover.
  No Zitrone release artifact has been built yet from this repo.

## [1.5.5-beta] - 2026-07-17

### Fixed

- **Android: the three Settings toggles that did nothing now function.** "Default disappearing
  timer" and "Burn on read by default" were one-shot seeds of per-chat saveable compose-bar state
  that nothing re-read — a restored stale snapshot shadowed the setting and the send path never
  consulted it. The compose bar now derives live effective values (per-message override, else the
  current setting), so toggling a default actually changes what gets sent. "Send read receipts"
  toggled a stored preference no code consumed on either side of the wire — see Added.
- **Android: burn-on-read burned the instant a message rendered in an open chat.** It now stays
  readable for a 5-second grace window after first view, then burns and notifies the sender — the
  propagated burn doubling as the read confirmation is the design intent, so the delay applies on
  both devices.

### Added

- **Android: read receipts, server-blind.** Receipts ride INSIDE ordinary encrypted message
  envelopes as control payloads (`packages/protocol/src/receipts.ts`) — on the wire a receipt is
  indistinguishable from conversation text, so the relay never learns read status, not even that a
  receipt exists. Every plaintext (messages AND receipts) is padded to 256-byte blocks before
  encryption so ciphertext length can't fingerprint a receipt either. Gated on the "Send read
  receipts" setting, batched per chat-open, sent/read indicator on outgoing bubbles. Burn-on-read
  messages never produce a receipt — their propagated burn signal is the read confirmation. The
  web client recognizes and swallows receipt payloads; web-side receipt SENDING is deferred
  pending cross-client interop verification.
- **Android: branded notification sound with user override.**
- **iOS: port of the Android registration key-encoding and WebSocket fixes + on-device
  diagnostics** (raw 32-byte keys, `Sec-WebSocket-Protocol` auth, flat frames, plaintext padding,
  receipt swallowing, Settings → Connection diagnostics). Parse- and harness-verified only — NOT
  yet compiled with Xcode or tested on a device; no iOS release is cut from this.

## [1.5.4] - 2026-07-17

### Fixed

- **Android: messages never sent — the WebSocket handshake could not authenticate.** The v1.5.3
  Android client presented its JWT in an `Authorization: Bearer` header on the `/ws` upgrade, but
  the server's `/ws` middleware only reads `Sec-WebSocket-Protocol` (or a `?token=` query param) —
  so every handshake was rejected and the app sat on "Connecting…" forever, silently retrying.
  Verified against a local server build: the exact Android handshake fails, the subprotocol and
  query-param handshakes succeed. The client now sends the token via `Sec-WebSocket-Protocol`,
  matching the web client and the server contract.
- **Android: WebSocket frames used a nested `{type, payload}` shape the server has never spoken.**
  The server (server/internal/ws/hub.go) and packages/protocol/src/events.ts define FLAT frames —
  fields sit next to `type`. Even with a fixed handshake, every `message.send` earned
  `{"error":"bad_envelope"}` and every inbound `message.deliver` was dropped client-side (verified
  live against a local server build). All outbound frames are now flat (`message.burn` gained the
  required `peer_id`; typing events use `peer_id` not `recipient_id`) and inbound parsing reads
  flat fields. New unit tests pin the wire contract. Android's non-functional `presence.update`
  stub was removed outright rather than reshaped: the canonical event is an encrypted signal
  Android does not yet produce, and the server currently drops every presence frame from every
  client (its relay routes signals by a `peer_id` the presence event does not define), so a stub
  could only pin a dead, wrong shape. **iOS has the same two defects and is NOT fixed by this
  change** (tracked in `.l00prite/todos.md`).
- **Server: `/ws` auth failures returned 500 instead of 401.** The Fiber error handler flattened
  every error — including the `/ws` middleware's `fiber.ErrUnauthorized` — to
  `500 {"error":"internal"}`, so clients could not distinguish an expired/rejected token (fixable
  by re-authenticating) from a server fault, and blind-reconnected forever. Intentional 4xx
  statuses now pass through.
- **Server: `/ws` never echoed the `Sec-WebSocket-Protocol` back.** RFC 6455 §4.1 requires the
  echo when a client offers a subprotocol; browsers enforce it and drop the socket right after an
  otherwise-successful 101, so the web client's real-time delivery could never work in a browser.
  The middleware now echoes the offered subprotocol (and correctly does NOT when the token arrived
  via query param).

### Added

- **Android: send-path and socket-lifecycle diagnostics (Settings → Diagnostics).** The same
  privacy-safe logging that covered registration (v1.5.3) now covers what happens after: WebSocket
  handshake fired/connected/closed/failed (exception class + message + HTTP status), the token-
  rejected → re-auth hand-off, and every message-send stage (prekey-bundle fetch, X3DH session
  establishment, encrypt, socket hand-off) with stage name + exception metadata on failure. No
  message content, keys, tokens, or account IDs — fixed markers and exception metadata only. The
  send path previously swallowed every failure silently; a dead prekey fetch was indistinguishable
  from never having tapped send.

## [1.5.3] - 2026-07-15

### Added

- **Android: on-device connection diagnostics (Settings → Diagnostics).** A permanent,
  privacy-safe log of every registration/connection attempt — boot stage markers plus the
  transport error class and message on failure (e.g. a certificate-pinning rejection, a TLS
  handshake/version failure, or an unreachable relay) — written to an app-private file and shown
  as plain, selectable, copyable text. It requires no `adb`/`logcat` and no second machine, so a
  user stuck on “Connecting…” can read the exact failure and paste it into a bug report. The same
  lines still go to logcat under the `ZitroneBoot` tag when `adb` *is* available. The log is
  capped at the 50 most recent lines and is never backed up (`allowBackup=false`). It contains no
  message content, keys, tokens, or account IDs by construction.

## [1.5.2] - 2026-07-15

### Fixed

- **Android: opening Settings crashed the release build, every time.** The Settings route was the
  app's only user of `LifecycleResumeEffect` (lifecycle-runtime-compose). On Compose 1.6.x that API
  resolves its `LifecycleOwner` through a reflection shim into compose-ui's
  `AndroidCompositionLocals_androidKt`; R8 renamed that class in the minified v1.5.1 release APK
  (confirmed in the shipped dex), so the first composition of Settings threw
  `IllegalStateException: CompositionLocal LocalLifecycleOwner not present`. Debug builds — the only
  thing CI built — are not minified, so the crash never appeared there. The Orbot re-check on resume
  now uses compose-ui's `LocalLifecycleOwner` with a plain `DisposableEffect` observer (no
  reflection), the lifecycle-runtime-compose dependency is removed, a defensive keep rule pins the
  reflection target, and CI now also builds the minified release APK and asserts the kept class
  survives R8.

### Added

- **Android release-signing + publish pipeline.** `apps/android/app/build.gradle.kts` now wires a
  release `signingConfig` sourced from a gitignored `keystore.properties` (or env vars), falling back
  to an unsigned build when absent so keyless checkouts and CI still assemble. A new
  `.github/workflows/release-apk.yml` builds, signs (from opt-in GitHub Secrets behind a protected
  environment), verifies the signature, checksums, and publishes the APK as a GitHub Release —
  uploading an unsigned artifact plus offline-signing instructions when no keystore is configured.
  `docs/RELEASING_ANDROID.md` documents the local and CI paths, the signing-key continuity checks,
  and the website/mirror pointer flip.

## [1.5.1] - 2026-07-14

### Added

- **v1.5 — the security onion.** Five layered defenses, each assuming the one beneath it has failed:
  - **Plausible deniability**: key-slot vaults with a never-stored vault count and identical
    Argon2id timing on every passphrase path (`packages/crypto` `vault`).
  - **Dead-drop mode**: anonymous deposit under `SHA-256(token)` with no sender field, gated by a
    hashcash proof-of-work instead of an account; single-use redeem; 72-hour TTL purge.
  - **Decoy (cover) traffic**: Poisson-timed fake envelopes, padded to the same 256-byte block as
    real messages and sent over the same path, with low-battery back-off (`packages/relay-client`).
  - **Multi-hop relay**: 3-layer onion encryption with AS/geographic-diverse path selection, guard
    pinning, and 100-message / 10-minute circuit rotation; server `/relay/forward` peels one layer.
  - **Tor-first architecture**: Tor is the default transport; clearnet is a flagged fallback.
  - **Standard / Stealth / Ghost** connection modes composing the network layer.
  - **Privacy view**: frosted-lemon blur with hold / tap-timed / tap-toggle reveal modes.
  - **Platform warning**: honest, dismissible notice when a participant is on a browser.
  - 256-byte message padding, and an encrypted `contact.info` signal for real-time platform exchange.
- New `@zitrone/relay-client` package (decoy scheduler, circuit construction, path selection).
- UI reconciled from the `lemon-ui.jsx` brainstorm into the dark design system: progressive
  lemon-wheel fill, bouncing-drop typing indicator, and the squeeze send button.
- **Desktop certificate-pinned transport.** Because the Linux app's WebView cannot pin TLS, the
  native Tauri layer (`apps/desktop/src-tauri/src/transport.rs`) now routes all REST and WebSocket
  traffic through a rustls client with a custom SPKI-pinning verifier; `apps/web` uses it
  automatically under Tauri and plain `fetch`/`WebSocket` in the browser.

### Security

- **TLS certificate pinning across all clients.** iOS, Android, and desktop validate the CA chain
  **and** pin the server's leaf SubjectPublicKeyInfo (SHA-256), failing closed on any mismatch — a
  mis-issued or MITM certificate is rejected even when it chains to a trusted CA. Each client now
  carries a primary and an offline-backup pin so the key can be rotated without an app update.
- Self-hosting guide documents the Caddy reverse proxy (durable pin via `reuse_private_keys`),
  computing your pins, the desktop pinned-transport build, and the key/pin rotation runbook.

## [1.0.0]

### Added

- Initial release
