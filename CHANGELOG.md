# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Web / Linux desktop: full contact deletion (cryptographic teardown, not soft-delete).**
  Long-press, context-menu, or × on a conversation → confirm to burn known local
  messages (and best-effort peer burn signals), zero Double Ratchet session
  material, drop the verified-identity pin, remove the roster entry, and
  persist a TTL-bounded tombstone so straggler envelopes cannot resurrect the
  contact after a restart. Durable fail-abort: if the vault write fails, the
  contact is kept. Re-adding the same person requires a fresh X3DH handshake.
  Not a server bulk-purge: undelivered relay envelopes still expire via the
  standard TTL window (same model as Android — see `docs/SECURITY_MODEL.md`).

### Fixed

- **Android: permanent R8 compat mode (`android.enableR8.fullMode=false`)** to clear a
  Google Play Protect “harmful app” false positive that tracked full-mode DEX layout on
  the 0.8.4 release APK (same signing cert and app logic; only optimization shape differed).
  Documented tradeoff and do-not-revert note in `docs/RELEASING_ANDROID.md`. Play Protect
  appeal remains a maintainer-side follow-up (not in-repo).

## [0.8.4-beta] - 2026-07-21

### Added

- **Android: full contact deletion (cryptographic teardown, not soft-delete).**
  Long-press a conversation → confirm to burn every local message (and signal the
  peer to burn its copies, best-effort), then irreversibly destroy the Double
  Ratchet session, the peer's remote identity record, and any sender keys, and
  remove the roster entry. The teardown is a single synchronous, durable commit
  that aborts (keeping the contact intact) if it cannot reach disk, and the
  roster removal is durable too. Re-adding the same person starts a completely
  fresh X3DH handshake — zero reuse of prior key material. A persisted,
  time-bounded tombstone drops straggler messages from a deleted contact (even
  across an app restart, within the relay's undelivered window) without blocking
  genuine first-time inbound senders.
- **Android: local contact rename.** Tap the name in the chat header to edit the
  on-device display label. Local only — never touches sessions, keys, pinned
  identities, or safety numbers, and never leaves the device.

### Changed

- **Android messaging is confined to a single-worker dispatcher** so contact
  deletion cannot race an in-flight send or delivery — no post-delete envelope
  is deposited and a deleted contact cannot be resurrected by a straggler.

## [0.8.3-beta] - 2026-07-21

### Changed

- **Compose bar: attachment lives inside the input pill** on web/desktop and
  Android (paperclip on the leading edge of the field, ≥44px hit target). Burn /
  TTL stay outside the pill on Android so the field does not become a toolbar.
  iOS has no free-standing attach control today (ephemeral menu only) — left as-is.
- **Lemon-drop create droplet is Settings-gated (default off).** Settings → Privacy
  has “Lemon-drop compose button”; when off, the droplet is not shown in chat.
  Opening a drop via paste-link in Settings is unchanged. Long-press on send remains
  dead-drop only (not lemon-drop create).

### Fixed

- **Lemon-drop creation no longer dies as a generic "Couldn't seal the drop" when
  the relay is stale.** Root cause was deploy drift: production (and local Docker)
  still ran a pre–PR #3 server without `POST /api/v1/qr-drops`, so deposit 404'd
  after a successful client-side seal + PoW. Clients now surface a distinct
  "stale server build / redeploy the relay" message on that 404 (told apart from a
  handler `not_found` 404, which means the recipient is gone, not a stale relay);
  web also distinguishes identity-key change, oversize, rate limit, and offline. Web
  create rejects oversize drafts before the difficulty-20 PoW (parity with
  Android). `scripts/verify-relay-build.sh` and `docs/RELEASING_RELAY.md` check
  lemon-drop routes so the next missed redeploy fails the smoke test.

## [0.8.2-beta] - 2026-07-21

### Added

- **Android can now create lemon drops, not just open them.** The compose bar's lemon-drop
  button seals a message to a contact with a one-shot X3DH + proof-of-work and deposits it to the
  relay, then shows the QR to copy, save for printing, or share — the same one-way, session-less
  dead-drop the web client creates. Creation never establishes a session or writes a contact
  record. A drop only appears for a contact whose identity key you already hold. See
  `docs/SECURITY_MODEL.md`.

### Fixed

- **A lemon drop from an Android sender now opens for a web recipient who hasn't added them.**
  Previously such a drop decrypted but then failed to render while the client tried to set up an
  ordinary — and impossible — cross-family session. The message now renders and burns, and the
  sender's identity is pinned, without attempting a session that can't exist.
- The watermark fingerprint renders slightly larger for legibility; every other aspect of the
  treatment is unchanged.

### Known limitations

- Only clients on 0.8.1-beta or newer can open an Android-created drop. On older clients it
  degrades safely to "not for you". iOS still cannot open drops.

## [0.8.1-beta] - 2026-07-20

### Added

- **The chat surface is now security paper.** A faint, always-on, tiled pattern of the viewer's
  own identity fingerprint renders behind every chat surface on web, desktop, Android, and iOS —
  including Android's lemon-drop reveal screen — with message bubbles slightly translucent so the
  mark reads through the conversation. It identifies whose screen a photographed conversation
  came from. Deterrence, not a guarantee, and deliberately without an off switch — see
  `docs/SECURITY_MODEL.md` ("Fingerprint watermark").
- **The lemon-drop button is discoverable.** The compose bar's QR-drop action has its own droplet
  identity, distinct from the send button, plus a one-time explainer the first time you meet it.
- **Lemon drops can be printed.** The QR-drop modal saves a print-grade PNG (full quiet zone,
  burn-by caption) on web and desktop, so a sticker can be physically placed — set it and forget
  it. The saved file contains the drop link; the modal says to treat it like the printed sticker.

### Fixed

- Desktop saves write natively behind the OS save dialog — the WebView never supplies a
  filesystem path — and the print mark renders under the packaged app's content-security policy.
- The watermark's invisible leak-attribution layer now survives high-DPI displays (device-pixel
  carrier), and a deleted account's fingerprint can no longer outlive the account on screen.

## [0.8.0-beta] - 2026-07-20

### Fixed

- **A dead lemon-drop sticker can never be re-armed.** Burn and TTL expiry now crypto-shred the
  drop's ciphertext and burn hash in place and keep the `qr_id` as a permanent tombstone, so
  re-depositing under a used id is rejected forever — closing the review finding where a
  sticker's creator could make a "dead" sticker silently deliver again. The honest cost (the
  relay retains one 16-byte random id + expiry timestamp per drop ever minted, linking to no
  identity) is documented in `docs/SECURITY_MODEL.md`.
- **First replies from drop-created contacts now arrive.** The two-independent-initiators
  deadlock — the drop's creator held one session, the redeeming recipient created another, and
  the first reply silently failed to decrypt — is fixed by a guarded session reset in the web
  receive path: only on decrypt failure, only for envelopes carrying an X3DH initial-message
  header, and keyed strictly on the *pinned* contact identity key, so only that key's holder
  can get a reset accepted. Also repairs the pre-existing mutual-add collision. Covered by new
  session-reset crypto tests (recovery, convergence, forger rejection).
- **Lemon-drop scans on Android are outcome-honest.** The advocacy screen now distinguishes a
  live sealed drop, a claimed-or-expired one, and a fetch that never completed — still exactly
  one blind fetch per scan. (When this first landed no decrypt was attempted; superseded in
  this same release by the Android bridge under *Added*, which opens drops for their true
  recipient and keeps these advocacy outcomes for everyone else.)
- **The sticker's no-app fallback is real.** `zitrone.app/d/{id}` now serves the ordinary
  marketing page (it previously 404'd), and `.well-known/assetlinks.json` ships with the site
  carrying the release signer's fingerprint, so installed apps intercept `/d/*` links once
  Android's verification propagates.

### Added

- **Lemon drops now open on Android.** A web/desktop-created drop can be addressed to an
  Android account and read there: the creator side verifies Android-family (XEdDSA-signed)
  prekey bundles with the same try-both logic the relay has always applied — ported to
  TypeScript and tested against the identical real-libsignal signature vectors — and uses the
  verified family to drive the DH and sealed-box key handling; the Android side gains a
  self-contained one-shot X3DH responder (isolated from ordinary libsignal messaging) that
  mirrors the web stack's bytes exactly, proven by a committed cross-stack fixture a JVM test
  decrypts end to end. A decrypted drop renders only after an explicit biometric unlock, and
  delivery consumes the one-time prekey and burns the relay's copy; every other outcome still
  lands on the advocacy screen. Lemon drops remain **one-way by design** — no reply path, no
  conversation; delivery or expiry are the only two exits, both destroying the drop. The
  cross-family path is scoped to drop creation only — ordinary web↔mobile messaging is still
  refused (a session would exchange undecryptable ciphertext). Android and iOS bundles are
  wire-indistinguishable, so a drop addressed to an iOS contact deposits but simply expires
  unopened (no leak); iOS has no opener yet. New dependency, pinned: `lazysodium` (libsodium
  binding) for the sealed-box open.
- **QR dead drops — "lemon drops."** Seal a message to one chosen contact as a printable QR
  sticker: the code carries only a `zitrone.app/d/{id}` pointer at a sealed blob on the relay,
  encrypted once at creation via one-shot X3DH (no live session touched). The relay is a blind,
  **non-destructive** shelf — it serves the same opaque blob to any scanner, and only the true
  recipient's device can open it; a recovered burn token then shreds the drop on claim, and a
  creator-chosen TTL (24 h / 48 h / 72 h / 1 week / 2 weeks) shreds the unclaimed. Web/desktop
  ship the full create + redeem flows (lemon-slice-branded QR at error-correction H, paste-link
  redeem, warm "part of the network" screens for not-for-this-device and expired scans); Android
  gains true recipient redemption in this same release (see "Lemon drops now open on Android"
  above), plus link interception and the advocacy screen for every non-recipient scan. **Unlike
  `/drops` this variant is recipient-targeted, not anonymous — and non-recipient scanners
  transiently receive ciphertext they cannot decrypt**; both properties are disclosed in
  `docs/SECURITY_MODEL.md`. App-Link verification (`assetlinks.json`) and the `/d/*` website
  fallback page shipped once the flow was verified end-to-end — see the Fixed entries above.

### Known limitations

- **iOS cannot yet be a lemon-drop recipient.** Android and iOS publish wire-indistinguishable
  libsignal (Curve25519/XEdDSA) prekey bundles and the zero-knowledge relay stores no platform
  tag, so the creator cannot refuse an iOS recipient up front. A drop addressed to an iOS
  contact is still sealed to that recipient's key and deposited — it simply **expires unopened
  and is shredded at its TTL; no content leaks** (only that recipient could ever open it, and
  iOS has no opener yet). A wire-level capability signal to refuse iOS at creation time is
  deferred follow-up work.
- **Read-once rests on the burn, not the crypto, when no one-time prekey was available.** A
  reading device deletes the one-time prekey a drop consumed, so a re-scan of an OTP-bearing
  drop fails closed. But if the creator's fetched bundle had no one-time prekey left (the
  recipient's stock was exhausted), the drop is decryptable from the identity + signed prekey
  alone — so until the best-effort burn lands or the TTL fires, the *intended recipient* can
  re-open their own already-read message on a re-scan. This is a protocol property shared with
  web/desktop, not Android-specific and not a confidentiality loss (the drop stays sealed to the
  one recipient throughout); keeping the client's prekey stock replenished makes it rare.

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
