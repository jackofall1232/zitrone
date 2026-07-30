# Plan: Linux Desktop ↔ Android/iOS Interop (libsignal)

*Compiled 2026-07-30 from `research/docs/linux-resolve.md` plus a full codebase map of
`apps/android`, `apps/ios`, `apps/desktop`, `apps/web`, `packages/*`, and `server/`.*

---

## 0. Verdict

A Linux desktop client that talks to the shipped Android client (and the in-repo iOS
client) is achievable **with zero server changes** — but the path is *not* the one the
research brief's headline suggests. The brief's #1 risk (mandatory PQXDH/Kyber) applies to
**current stock libsignal / Signal's own apps**. Zitrone's mobile clients are
**classic-X3DH-only**:

- Android pins `org.signal:libsignal-client` **0.46.0** (`apps/android/gradle/libs.versions.toml:21,57-58`)
  and builds 7-arg `PreKeyBundle`s with no Kyber params (`apps/android/.../crypto/SignalProtocolManager.kt:312-324`).
- iOS pins signalapp/libsignal **from 0.56.0** (`apps/ios/project.yml:34-42`) and states
  "Zitrone v1 bundles are Curve25519-only" (`apps/ios/Sources/Crypto/SignalManager.swift:205-226`).
- The relay **rejects any key that isn't exactly 32 bytes** (`server/internal/api/handlers.go:196,201,222,377,388`)
  — a 1568-byte Kyber1024 public key gets `400`. There are no Kyber fields in the schema,
  handlers, or protocol types.

So the desktop must join the **existing classic-X3DH libsignal family**, replicating the
mobile wire convention exactly. Publishing Kyber prekeys today would fail at the server
boundary, and a Kyber-only handshake couldn't talk to the shipped clients anyway.

> **The real version risk is the mirror image of the brief's:** the brief notes that
> *current* libsignal rejects pre-Kyber handshakes for new sessions. Therefore the desktop
> must pin a libsignal tag **old enough to still process classic X3DH** (0.46.x-era), not
> the latest release. "Pin the newest tag" — the brief's default advice — would break
> interop here. This flips §3 of the research doc from "verify latest" to "verify the
> newest tag that still accepts classic X3DH, and no newer."

---

## 1. The wire contract the desktop must replicate

Everything below is how Android/iOS behave **today**; the desktop Rust core must match it
byte-for-byte. The server treats any client that follows it as indistinguishable from
Android.

### 1.1 Identity & addressing

- Identity key: Curve25519 (Montgomery) `IdentityKeyPair.generate()`; XEdDSA for all
  signatures. No Ed25519 key anywhere in this family.
- Published identity key: **raw 32 bytes, base64** — no `0x05` DJB type prefix
  (`SignalProtocolManager.kt:97-111`; server enforces `len == 32`, `handlers.go:195-198`).
- The 33-byte `serialize()` form appears only inside `PreKeySignalMessage`, as the
  signed-prekey signature message, and in the envelope's `ephemeral_key` field.
- `ProtocolAddress` = (lowercase server-assigned account UUID, device_id **1**)
  (`SignalProtocolManager.kt:411-412`; iOS `SignalManager.swift:289-290,463`).
- Registration ID: random in **1..16380**, sent at register but ignored by the server;
  both mobile clients hardcode `registrationId = 1, deviceId = 1` when reconstructing a
  peer bundle (`ApiClient.kt:219-224`; `APIClient.swift:340-346`). Desktop: same.

### 1.2 Prekeys

- Signed EC prekey: Curve25519; signature = **XEdDSA over the 33-byte `serialize()` form**
  of the public key (`SignalProtocolManager.kt:157-160`; `SignalManager.swift:425-433`);
  upload carries the raw 32-byte key. 7-day client-side rotation.
- One-time EC prekeys: batch of **100**, replenish below 20 (server pushes `prekey.low`
  over WS), per-account-unique IDs. Optional in a fetched bundle — X3DH proceeds without.
- Kyber: none. Implement the store trait only because libsignal requires it.

### 1.3 REST API surface

Reference implementation: `apps/android/.../net/ApiClient.kt`.

- `POST /api/v1/register` → `{identity_key, registration_id, signed_prekey{id,public_key,signature}, one_time_prekeys[]}`
  → `201 {account_id}`. Server verifies the signed-prekey signature with a try-both
  Ed25519/XEdDSA verifier (`handlers.go:148-168`); the XEdDSA branch is ours.
  **Do not call `/api/v1/register/challenge`** — registration PoW was removed server-side
  (`handlers.go:56-62`); Android tolerates its 404.
- `POST /api/v1/session` — login: XEdDSA-sign the exact byte string
  `sublemonable-login:<account_id>:<unix_ts>` (frozen legacy contract, `server/internal/auth/jwt.go:81-84`),
  ±5 min skew. Returns 15-min RS256 JWT + 7-day refresh token; refresh rotates on every
  use (`POST /api/v1/session/refresh`, atomic consume).
- `GET /api/v1/users/:id/prekey` — fetch peer bundle; server atomically consumes the OTP
  and serves only the latest signed prekey. Response has **no** registration_id/device_id.
- `POST /api/v1/prekeys` (top-up + optional signed-prekey rotation; signature re-verified),
  `GET /api/v1/prekeys/count`.

### 1.4 Messaging (WebSocket only — no REST message endpoint)

- `GET /ws`, JWT in the **`Sec-WebSocket-Protocol` header** (server echoes it as the
  selected subprotocol, `server/cmd/server/main.go:144-161`); `?token=` fallback.
  One connection per account; second login revokes the first (`session.revoked`).
- Frames: flat JSON — outbound `{"type":"message.send","envelope":{...}}`; inbound
  `message.deliver`, `message.stored`, `message.delivered`, `message.burned`, `prekey.low`,
  `session.revoked`, `error`. Client also sends `message.ack` (**server deletes the envelope
  on ack** — ack only after decrypt is durably flushed), `message.received`, `message.burn`,
  `typing.*`, `presence.update`, `contact.info`.
- Envelope v1 (snake_case JSON, `packages/protocol/src/envelope.ts:23-48`; Kotlin mirror
  `apps/android/.../data/MessageEnvelope.kt:47-82`): `{id, sender_id, recipient_id,
  ciphertext, ephemeral_key?, prekey_id?, message_number, previous_chain_length, timestamp,
  ttl_seconds?, burn_on_read, media_type, version:"1"}`. The server parses only
  `{id, sender_id, recipient_id}` and stores the rest verbatim as opaque bytes.
- **Ciphertext-type discriminator is `ephemeral_key != null`** ⟺ `PreKeySignalMessage`;
  there is no explicit type field (`MessagingCoordinator.kt:2173`;
  `SignalManager.swift:584-596`). The libsignal-serialized message is self-typed, but the
  envelope field is what both mobile clients branch on — replicate it.
- `ephemeral_key` = base64 of `baseKey.serialize()` — the **33-byte tagged form** (the one
  field that uses it). `previous_chain_length` is **always 0** from both mobile clients.
  `message_number`: from the ciphertext counter, starting 0.
- **Plaintext padding before encryption**: 4-byte big-endian length + plaintext + random
  fill to a 256-byte multiple (`apps/android/.../crypto/MessagePadding.kt:43-58`);
  receivers fall back to unpadded legacy text. Must match byte-for-byte.
- Contact add is out-of-band: QR payload `{"version":"1","account_id","identity_key"}`;
  the fetched bundle's identity key is **pinned against the QR key before first send** —
  mismatch refuses the send (`MessagingCoordinator.kt:1273-1283`). Desktop must implement
  the same pinned-identity check and safety numbers (`apps/android/.../crypto/SafetyNumber.kt`
  — must stay byte-identical cross-platform).

### 1.5 Server changes required

**None.** The relay has zero crypto-family awareness; a Curve25519+XEdDSA registration is
treated exactly as a mobile peer. (Adding PQXDH later would be the thing that forces
server/schema changes — new bundle fields, endpoints, tables — and must be additive.)

---

## 2. Desktop architecture

`apps/desktop` is already a Tauri v2 shell over the `apps/web` React frontend, with a real
Rust backend (`apps/desktop/src-tauri/`): `transport.rs` (cert-pinned rustls + WS),
`keystore.rs` (opaque-blob vault storage), `i2p.rs`, `tor.rs`, `pinning.rs` — **all
crypto-free**, and all survive this change untouched. No libsignal dep exists yet
(`src-tauri/Cargo.toml`).

### 2.1 Rust crypto core (new module in `src-tauri`)

- Add `libsignal-protocol` as a **git dependency pinned by tag** (never the internal
  `0.1.0` crate version) — the tag chosen per §3 step 1. Protocol-crate-only native build:
  no BoringSSL/networking needed; toolchain `clang libclang-dev cmake make
  protobuf-compiler python3` (research doc §8).
- **Protocol worker pattern** (research doc §7): the store traits use
  `#[async_trait(?Send)]` — their futures are not `Send`, but Tauri commands want `Send`.
  Run a dedicated worker on one OS thread with a current-thread Tokio runtime owning all
  stores; Tauri commands send *owned* request data over a channel and await an owned
  response. Only the channel handle goes into `.manage(...)` state. This also serializes
  all session mutation — no concurrent ratchet advance.
- New Tauri commands (owned `String`/`Vec<u8>` args only), mirroring the mobile managers'
  surface: `crypto_create_identity`, `crypto_signed_prekey_generate`,
  `crypto_one_time_prekeys_generate`, `crypto_process_bundle`,
  `crypto_encrypt(recipient, plaintext) -> {ciphertext, ephemeral_key?, prekey_id?,
  message_number}`, `crypto_decrypt(sender, ciphertext, is_prekey) -> plaintext`,
  `crypto_safety_number(peer)`, `crypto_destroy_contact(peer)`,
  plus store load/save hooks.
- **Padding applies to the plaintext before `crypto_encrypt`** (port `MessagePadding`
  semantics into the Rust core so mobile and desktop pad identically).

### 2.2 Store: spike vs production

- **Spike**: `InMemSignalProtocolStore` is fine to prove interop (research doc §5).
- **Production**: implement the five store traits over durable records, persisted as an
  opaque serialized blob through the existing `keystore.rs` vault commands (which already
  store opaque bytes under Secret Service / 0600 file fallback). Mirror the mobile
  semantics (`apps/android/.../crypto/EncryptedSignalProtocolStore.kt`,
  `apps/ios/.../SignalManager.swift:68-282`): TOFU `isTrustedIdentity`, key-change
  detection on `saveIdentity`, one-time EC prekey deletion on use, per-contact
  `destroyContactCrypto` teardown, monotonic prekey-ID counters.
- The vault payload schema (`packages/crypto/src/keystore.ts`,
  `apps/web/src/lib/serialization.ts`) holds the *old* custom-ratchet session shape and
  must be **versioned** (bump `version`, no migration — the project already reserves the
  right to require fresh installs pre-beta, README "Release maturity").

### 2.3 Frontend changes (`apps/web`, used as desktop frontend)

The envelope/bundle/event TS types in `packages/protocol` already describe the libsignal
wire format — they stay. What changes is where crypto happens when `isTauri()`:

- Replace the `@zitrone/crypto` call sites in `apps/web/src/store.ts` — `createAccount`,
  `addContact` (X3DH initiate), `respondToX3DH`, `buildAndSend`, the `message.deliver`
  decrypt path, session reset — with Tauri `invoke()` calls to the Rust core, behind the
  same kind of lazy-import boundary as `apps/web/src/lib/nativeTransport.ts`.
- Keep browser behavior on the existing libsodium stack for now; the libsignal path is
  desktop-only. (Whether web eventually follows is a separate decision — it can't use the
  native crate.)
- Transport (`api.ts`, `ws.ts`, `nativeTransport.ts`), UI, receipts, TTL/burn, decoy
  scheduler, vault framing: unchanged.

---

## 3. Execution phases

### Phase 0 — Verifications (before any code)

1. **Pin the desktop libsignal tag.** Find the newest signalapp/libsignal tag whose
   `rust/protocol` still (a) builds classic `PreKeyBundle` sessions without Kyber and
   (b) accepts inbound pre-Kyber `PreKeySignalMessage`s. Start from the 0.46.x–0.56.x era
   the mobile clients pin; read `rust/protocol/src/storage/traits.rs` and
   `rust/protocol/tests/` *at that tag* for the real signatures (research doc §3: never
   trust quoted signatures).
2. **Confirm the exact mobile pins at build time**: `apps/android/gradle/libs.versions.toml`
   (0.46.0) and `apps/ios/project.yml` (0.56.0). These are the interop targets — the
   desktop tag must round-trip with *them*, not with "latest."
3. Confirm the pinned tag's Rust toolchain/edition from its CI config.

### Phase 1 — Spike (throwaway, nothing lands in the repo yet)

Adapted from research doc §9, corrected for classic X3DH:

1. Rust harness with `InMemSignalProtocolStore`: generate identity + signed prekey + OTPs;
   build and consume bundles in the **Zitrone mobile convention** (raw-32 wire keys,
   `0x05`-prefix on decode, XEdDSA-over-serialize signatures, reg id 1, device 1).
2. **Interop vectors through the real bindings, both directions**: Rust↔Kotlin/JVM
   (0.46.0) and Rust↔Swift (0.56.0, macOS). Assert: `process_prekey_bundle` succeeds;
   message 0 decrypts; reply is an ordinary `SignalMessage`; ≥25 messages each direction
   with shuffled delivery; OTP consumed exactly once.
3. **Negative controls**: corrupt EC signature, wrong address/device, swapped identity
   post-session, and — critical — confirm the pinned Rust tag still *accepts* a
   Kyber-less bundle (this is the whole ballgame; if it rejects, pin older).
4. Test the byte-level conventions explicitly: raw-32 vs 33-byte forms in each envelope
   field, XEdDSA signed-prekey verification against the server's Go verifier semantics
   (`server/internal/auth/xeddsa.go`), padding round-trip vs `packages/crypto/src/padding.ts`.

### Phase 2 — Desktop Rust core

1. Add the pinned git dependency; stand up the protocol worker + channel commands (§2.1).
2. Port `MessagePadding`; implement register/session-login/prekey flows against a local
   relay; WS messaging with ack-after-durable-decrypt.
3. Production store persisted via `keystore.rs`; vault schema version bump.
4. Self-test against the relay with an Android emulator/device: contact QR exchange →
   pinned-identity check → bidirectional messaging → burn/TTL → session teardown.

### Phase 3 — Frontend integration & hardening

1. Desktop-only crypto boundary in `apps/web` (Tauri invokes replacing `@zitrone/crypto`
   session/message paths); keep web on the old stack.
2. Safety-number UI parity; identity-change surfacing (today Android *swallows*
   `UntrustedIdentityException` in a `runCatching` — decide deliberately what desktop does).
3. Cross-platform soak: Android↔desktop, iOS↔desktop, all three pairwise; cover-traffic
   and lemon-drop paths verified unaffected.
4. Update `docs/SECURITY_MODEL.md` platform-interop matrix and README platform table when
   it ships.

---

## 4. Risk ledger

| Risk | Severity | Mitigation |
|------|----------|------------|
| Pinned Rust tag rejects Kyber-less bundles (upstream removed classic X3DH) | **High** — kills the plan as stated | Phase 0 step 1 + Phase 1 negative control; worst case pin 0.46.x exactly and accept no upstream fixes |
| libsignal upstream drift (external use unsupported; APIs change without notice) | Medium | Pin by tag; we own tracking upstream (research doc §10) |
| Signature/serialization form mismatches (raw-32 vs 33-byte) | Medium | Phase 1 byte-level vector tests; server dual-verifier is the oracle |
| Non-Send store futures breaking Tauri commands | Medium | Worker pattern from day one (§2.1) — don't try to await store futures in commands |
| Vault schema churn on existing desktop installs | Low | Desktop isn't distributed; version bump with no migration is already policy |
| `previous_chain_length` semantics diverge (mobile always 0; libsignal carries pn internally) | Low | Send 0 like mobile; never read it on the libsignal path |
| Registration-PoW 404 / other stale client assumptions | Low | Don't call the challenge endpoint; Android's 404-tolerance documents the contract |

## 5. Explicit decisions made (revisit if scope changes)

- **Target is the Zitrone mobile family, not the Signal network.** No transport envelopes,
  sealed sender, ACI/PNI, or service compatibility (research doc §6 — confirmed:
  Zitrone-only transport).
- **Classic X3DH, no PQXDH**, because that's what the shipped clients and the relay speak.
  PQXDH is a future additive migration (server + all clients), not part of this plan.
- **iOS↔Android already interoperate** (verified: matching conventions, no breaking
  divergences); this plan extends the same family to Linux. The browser client stays on the
  libsodium family until a separate decision.
