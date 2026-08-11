# Registry Resolution — client-side relay discovery (design + invariants)

**Authority:** `docs/MULTI_RELAY_ARCHITECTURE.md` §3 and §11. This document is the
implementation design for the client-side registry abstraction and the static signed
manifest — the ONE decision that cannot be retrofitted after real users exist.

**Scope of this unit:** manifest format (§1), signing + key handling (§2), Android client
resolver (§3), WRITER/READER invariant table (§4), hardcoded-address sweep with cutover
collisions (§5), Tor default flip (§6), activation checklist (§7).

**Explicitly NOT in this unit** (deferred per §11 of the authority doc): OPK-claim quorum,
Algorand anything, PoW admission, multi-hop wiring, relay health probing beyond the
existing connection machinery, more relays.

---

## 1. Manifest format

Two layers: a signed **envelope** whose signature covers exact bytes (no JSON
canonicalization anywhere), and the **manifest payload** those bytes decode to.

### 1.1 Envelope

```json
{
  "envelopeVersion": 1,
  "payload": "<base64url, no padding: the UTF-8 bytes of the manifest JSON>",
  "signatures": [
    { "keyId": "zitrone-registry-2026-k1",
      "algorithm": "ed25519",
      "signature": "<base64url, no padding: crypto_sign_detached over the DECODED payload bytes>" }
  ]
}
```

- The signature is verified over the **decoded payload bytes exactly as transmitted**.
  Verifiers never re-serialize JSON, so canonicalization bugs are impossible by
  construction. Tampering with a single payload byte fails verification.
- v1 threshold: **1-of-1** (one project registry key). `signatures[]` is an array so a
  future m-of-n threshold is an additive change, not a format break.
- Unknown `envelopeVersion` → reject (fail closed).

### 1.2 Payload

```json
{
  "schemaVersion": 1,
  "epoch": 1,
  "validFrom": "2026-08-11T00:00:00Z",
  "validUntil": "2026-11-11T00:00:00Z",
  "previousManifestHash": null,
  "relays": [
    {
      "id": "relay-cx23",
      "clearnet": { "apiBaseUrl": "https://relay.sublemonable.com",
                    "wsUrl": "wss://relay.sublemonable.com/ws" },
      "onion": null,
      "i2p": { "dest": "<b32.i2p destination, same value RELAY_I2P_DEST carries today>" }
    }
  ]
}
```

Field rules (client-enforced, all fail closed):

| Field | Rule |
|---|---|
| `schemaVersion` | must equal 1; unknown → reject |
| `epoch` | positive integer, strictly increasing across published manifests; the client refuses any manifest with `epoch` **lower than** the highest epoch it has ever accepted (rollback detection at the client, complementing `previousManifestHash`) |
| `validFrom` / `validUntil` | ISO 8601 UTC; the manifest is usable only inside the window. Clock skew tolerance: ±24 h on `validFrom` only (a device with a slightly slow clock must not reject a just-published manifest); `validUntil` is enforced without tolerance |
| `previousManifestHash` | SHA-256 (base64url) of the previous **envelope** file bytes; `null` only for epoch 1. Recorded for auditability and mirror cross-checking; the client checks only its shape (null at epoch 1, present after) — the client's own rollback guard is the epoch floor of §3.1 (persisted high-water mark + bootstrap epoch), which survives even when a client never saw the intermediate manifest |
| `relays[]` | non-empty; all relays are equal peers — order carries no primary/failover meaning. Each relay needs at least one of `clearnet` / `onion` / `i2p` |

**Validity window policy** (registry-side): 90-day windows, re-signed and re-published
well before expiry. The embedded bootstrap snapshot ships with the window current at
build time; an APK older than the window falls through to the network sources and then
to the cached snapshot (see §3.3 for the expired-everything case).

### 1.3 Hosting (v1 — deliberately boring)

A static signed JSON file. No database, no admin UI.

- **Primary (clearnet):** `https://www.zitrone.app/registry/v1/manifest.json` — a static
  asset on the existing Vercel site.
- **Tor mirror:** the relay's existing onion static-file surface (same mechanism as the
  APK mirror; the mirror is a code allowlist, so adding the manifest path is a relay
  change + redeploy — an ops item, not a client item).
- **I2P mirror:** same file served at the relay's I2P destination.

Mirror URLs are build-time configuration (`BuildConfig`, env-injected — the exact
pattern `RELAY_ONION_ADDRESS` / `RELAY_I2P_DEST` already use). An empty value means
"this source is skipped" — the resolver degrades gracefully.

The mirrors serve the **identical envelope bytes** — signature verification makes
mirror substitution detectable, which is the whole point of signing the manifest
rather than trusting the TLS connection to whichever endpoint answered.

## 2. Signing key handling

- **Algorithm:** Ed25519 (libsodium `crypto_sign_detached`). Already in the stack on
  every platform (lazysodium on Android, libsodium-wrappers in TS, Go stdlib
  `crypto/ed25519` if the server ever needs it).
- **Key generation and custody:** the registry signing keypair is generated OFFLINE with
  `scripts/registry/registry-sign.mjs keygen`, on a machine the maintainer controls. The
  secret key never touches the repo, CI, CX33, CX23, or CX-IS. Custody mirrors the
  Android release keystore: maintainer-held, offline, backed up the same way.
- **Key ID:** `zitrone-registry-<year>-k<n>` — carried in every signature block so
  rotation is observable.
- **Rotation:** publish a new manifest signed by the new key, ship the new public key in
  the next app update's BuildConfig, keep the old key's manifests valid until their
  `validUntil` passes. (v1 accepts a single trust root per build; multi-root grace
  windows are additive later if needed.)
- **Public key distribution:** `REGISTRY_PUBKEY_ED25519` env var at build time →
  `BuildConfig` (same mechanism as `RELAY_I2P_DEST`). It is the client's trust root.
  **Empty = registry resolution disabled entirely** and the client behaves exactly as
  before this unit (legacy constants). This keeps the unit shippable before the key
  ceremony has happened and makes activation an explicit, human decision.

### 2.1 Credentials inventory entry

No credentials inventory file exists in-repo (checked 2026-08-11), so the entry is
recorded here and must be mirrored into wherever the operational inventory lives:

| Credential | Type | Where it lives | Created | Notes |
|---|---|---|---|---|
| Zitrone registry signing key (`zitrone-registry-2026-k1`) | Ed25519 secret key | **NOT YET GENERATED** — maintainer offline machine, same custody tier as the Android release keystore | pending key ceremony (§7) | signs every registry manifest; compromise = attacker can publish relay lists to clients, bounded by pin fail-closed rule (§3.4) and epoch monotonicity |
| Registry public key | Ed25519 public key | `REGISTRY_PUBKEY_ED25519` env at APK build; recorded next to `RELAY_I2P_DEST` in the release build environment | pending | safe to publish; IS published in every APK |

## 3. Client resolver (Android)

### 3.1 Resolution sources, in order, first success wins

1. Signed bootstrap snapshot embedded at build time (`assets/registry/bootstrap.json`)
2. Primary registry endpoint (clearnet)
3. Registry mirror over Tor
4. Registry mirror over I2P
5. Last-known-good cached snapshot on device

**"Success" is defined**, not assumed: a source succeeds only if its envelope parses,
carries a valid signature from the build's trust root, is inside its validity window,
and has `epoch >=` the device's rollback floor. **The floor is the MAX of two
signals**: the persisted epoch high-water mark (table row 2) AND the verified
bootstrap's epoch. The bootstrap half closes the window the blind review found:
between bootstrap acceptance and the first successful refresh the persisted mark is
still 0, so without it a network-position attacker could replay a previously
published, validly signed manifest — older than the relay set the build shipped,
window still current — and the client would accept, cache, and ratify the downgrade.
The bootstrap half needs no write to work: the bootstrap rides the APK, so it
survives the burn exactly as a fresh install's copy does, and no startup write ever
dirties the settings store (a pre-vault app key in `zitrone_settings` reads as burn
residue to the boot reconciler's fresh-install postcondition — persisting the
bootstrap at startup, the naive form of this fix, would trip that wipe on every
pre-vault boot). Everything else is a miss and the resolver moves on. This definition
is what makes the ordering correct:

- Fresh install: the bootstrap snapshot is valid → wins immediately, no network needed.
- After a background refresh has cached epoch N > bootstrap's epoch: the bootstrap
  fails the epoch check → network sources are consulted → if all unreachable, the
  cached snapshot (epoch N) wins. A stale-but-validly-signed bootstrap can never roll
  a client back.
- Before the first refresh: a stale-but-validly-signed NETWORK manifest (epoch below
  the bootstrap's) fails the floor → refused, and nothing is cached. Rollback
  protection does not depend on a refresh having landed first.

### 3.2 Two-phase operation — what happens when

- **At startup (synchronous, local only):** resolve from sources 1 and 5 only — no
  network on the construction path. The winner supplies the relay endpoints the session
  is built against. If registry resolution is disabled (no trust root baked in) or both
  local sources miss, the client uses the legacy constants — current behavior,
  unchanged.
- **In the background (asynchronous):** fetch sources 2→3→4 through the app's CURRENT
  transport client (so a Tor/I2P user's registry fetch rides the same anonymity layer
  as everything else — the clearnet registry URL fetched through the Tor-proxied client
  is still a Tor-protected fetch), verify, and persist to the snapshot cache. The
  refreshed manifest is picked up at the next resolution (next app start / session
  build). v1 deliberately does NOT hot-swap a live session's relay mid-flight — the
  transport-swap machinery (`applyTransportLocked`) stays the only mid-session endpoint
  mover, and it moves transports, not relays.
- **Relay selection:** the client selects the first relay in manifest order that offers
  an endpoint usable by the resolved transport (clearnet/Tor need `clearnet`; I2P needs
  `i2p.dest`) AND passes the §3.4 pin gate. All relays are equal peers; with today's
  single-relay manifest, selection is trivial — the abstraction, not the selection
  policy, is this unit's deliverable. Retry/rotation across multiple relays rides the
  existing reconnect machinery and can be enriched registry-side later (§3 of the
  authority doc: prioritization is a registry-side ordering change).

### 3.3 Expired-everything degradation

If every source misses (e.g. an APK shelved for a year: bootstrap expired, no network,
no cache), the client falls back to the legacy constants rather than bricking. This is
a deliberate availability-over-freshness call for v1, stated here so review can
challenge it; it disappears at cutover when the constants do.

### 3.4 Certificate-pin fail-closed rule (v1)

Certificate pinning is host-scoped to `CertificatePinning.API_HOST` and is
cutover-protected. Until pins are carried in the manifest (a coordinated cutover
decision, NOT this unit), the resolver **rejects any relay whose clearnet host is not
exactly the pinned host**. A manifest naming an unpinned clearnet host contributes
nothing (fail closed) rather than producing an unpinned TLS connection. Onion/I2P
endpoints are not TLS-pinned today (transport-layer identity) and are exempt from this
gate, matching current behavior.

## 4. WRITER/READER invariant table (required before code — authority doc 2d)

Durable signals this unit touches or creates. For each: every writer, every reader, and
what the reader assumes the signal MEANS.

| # | Signal | Store | Writers | Readers | What the reader assumes it means | Burn/wipe behavior |
|---|---|---|---|---|---|---|
| 1 | `registry_snapshot` (envelope bytes, base64) | `zitrone_settings` EncryptedSharedPreferences (the SAME file `SettingsRepository` + `BiometricUnlockStore` share — deliberately NOT a new store) | `RegistrySnapshotStore.store()`, called only by the background refresh, only AFTER full verification (signature + window + epoch floor) | Startup resolver (source 5) | "A manifest that verified against this build's trust root at store time." The reader RE-VERIFIES on every read — the cache is treated as untrusted bytes, so a corrupted/tampered prefs value degrades to a miss, never to acceptance | Cleared by `resetToFreshInstallDefaults()`'s in-place key clear — the existing burn row for `zitrone_settings` covers it. Fresh-install baseline (key absent) is honest: a fresh install has no cache. CONCURRENCY (corrected after blind review — the earlier "zero change to the hardened wipe surface" claim was false under it): the writer is an app-scope coroutine the burn's session quiesce does NOT stop, and its fetch has no read timeout, so an un-gated `store()` could commit during or after the wipe — residue, or a write between the wipe's clear and its emptiness proof aborting the burn post-image. The gate: the burn (and the boot completion pass, which re-runs this step) sets a suppression flag BEFORE the wipe and holds a shared monitor (`registryWriteGateLock`) across it; `store()` checks the flag under that same monitor, so an admitted commit always finishes before the clear begins and no commit is admitted after. The wipe SEQUENCE — phases, steps, postconditions — is unchanged |
| 2 | `registry_epoch_high_water` (int) | same store | `RegistrySnapshotStore.store()` (monotonic: only raised, never lowered; suppressed during a wipe — row 1) | Every source evaluation (one half of the epoch floor in §3.1) | "The highest epoch this device has ever accepted over the network; anything lower is a rollback attempt or stale data." Absent key = 0 — but 0 is NOT the effective floor: the floor also includes the verified bootstrap's epoch (row 4), so a fresh install refuses anything older than its build's bootstrap, and a post-burn device does the same (rows 1+2 are written atomically in one editor commit, so they cannot diverge) | Same as row 1. NOTE: post-burn the PERSISTED mark is gone BY DESIGN — a burned device must be byte-indistinguishable from a fresh install, and a surviving mark would be a burn distinguisher. Rollback protection does not drop to zero, though: the bootstrap floor rides the APK, identical on burned and fresh devices, so post-burn ≡ fresh install holds AND nothing older than the build's bootstrap is ever accepted |
| 3 | `tor_enabled` (existing key — default flip only) | same store | `SettingsRepository.setTorEnabled()` (user toggle — the ONLY writer; nothing else writes this key, verified by grep) | `SettingsRepository.load()` → `DeviceSettings.torEnabled` → `TransportResolver` fallback + OkHttp client construction | "The user's Tor preference." KEY ABSENT = never expressed a preference = new default ON. KEY PRESENT = an explicit user choice = honored verbatim, including `false`. `SharedPreferences` gives this distinction for free: `getBoolean`'s default applies only when the key is absent, and the key is only ever written by the user's own toggle. NO migration write is performed — writing the new default would DESTROY the never-set/explicit distinction for every future default change | Burn clears the key → post-burn = fresh install = default ON. Correct: a burned device must not carry the pre-burn user's preference as a distinguisher |
| 4 | Bootstrap snapshot (`assets/registry/bootstrap.json`) | APK asset (read-only, not device state) | The release build process (copies the signed file in; absent in dev builds unless provided) | Startup resolver (source 1) — AND its verified epoch is the second half of the rollback floor (§3.1), read on every network acceptance | "The relay set current when this APK was built, signed by the registry key." Read-only by construction; no wipe interaction. An expired/tampered bootstrap contributes no floor — the persisted mark still applies | none (in-APK — which is exactly why its epoch can floor post-burn devices without becoming a burn distinguisher) |
| 5 | Legacy endpoint constants (`API_BASE_URL`, `WS_URL`, `RELAY_I2P_DEST`, pins) | source code / BuildConfig | — (cutover-protected, unchanged by this unit) | The legacy fallback path (§3.3) and the pin gate (§3.4) | "The known-good relay of record until cutover" | none |

**Invariant the table protects:** no reader of `zitrone_settings` gains a new meaning
for an existing key, and the two new keys are written atomically by exactly one writer
after verification. `wipeVaultUsePreferences` itself is untouched: its four-store
enumeration and the "no other prefs factory exists" claim both remain true — what the
blind review added is the write GATE around it (row 1), because sharing the wipe
target with an app-scope writer is only sound under mutual exclusion, not merely by
sharing the file.

## 5. Hardcoded relay addresses — sweep results (authority doc 2c)

Complete sweep of every client path, 2026-08-11, `main` @ `74157301`:

| Location | What | Action this unit | Cutover collision? |
|---|---|---|---|
| `apps/android/.../ZitroneApp.kt:1603-1604` `API_BASE_URL`/`WS_URL` | live relay endpoint | **UNCHANGED** (marked `TODO(zitrone-cutover)`). Live path now flows through the resolver; these constants remain as the disabled/degraded fallback (§3.3) and bootstrap parity check | **FLAGGED:** full removal (2c's literal goal) is blocked by the cutover marker. The constants stop being the primary source but stay in the binary until cutover. HoboJoe decides when they go |
| `apps/android/.../CertificatePinning.kt:28` `API_HOST` + pins | pinned host + SPKI pins | **UNCHANGED** (cutover-marked) | **FLAGGED:** pins are host-scoped, so the manifest cannot introduce a new clearnet host until pins move into the manifest or are re-scoped at cutover (§3.4 fail-closed gate is the interim rule) |
| `apps/android/.../ApiClient.kt:32` login constant `"sublemonable-login:..."` | wire-protocol login string | **UNCHANGED** (cutover-marked) | none from this unit — registry resolution never touches the wire contract |
| `apps/android` BuildConfig `RELAY_ONION_ADDRESS`, `RELAY_I2P_DEST` | env-injected endpoints | unchanged — build-time configuration, not source-hardcoded; the manifest's `i2p.dest` will carry the same value, and at cutover BuildConfig injection can retire | minor: two sources of the same value until cutover; bootstrap generation should consume the same env vars to prevent skew |
| `apps/ios/.../APIClient.swift:39`, `WebSocketClient.swift:112`, `PinnedSessionDelegate.swift`, `RelayConfig.swift` | hardcoded relay URL + pins | **NOT TOUCHED** — iOS is back-burner until after V1 Android testing (ruled); it does not ship at V1.0.0, so the V1.0.0 client contract does not yet bind it. Enumerated so nobody believes the sweep missed it | future: iOS must adopt the resolver before it ships |
| `apps/desktop/src-tauri/src/pinning.rs:37` `API_HOST` | hardcoded relay host | **NOT TOUCHED** — same back-burner ruling | same as iOS |
| `apps/web/src/config.ts`, `store.ts` (cutover-marked), `transportResolver.ts` | server URL config + login constant | **NOT TOUCHED** — web client undeployed (standing website-overclaim item); `store.ts` is cutover-marked | same as iOS |
| `packages/relay-client/src/*` | takes relay lists as input (registry-shaped already) | none needed — this is the §5 multi-hop layer, which is deferred; notably it already models exactly the "relays as data, not constants" contract | none |

**Net finding for 2c:** in the Android client path, the only hardcoded relay addresses
ARE the cutover-protected constants. The unit therefore removes them from the live
resolution path (the client contract) without deleting them (the cutover decision),
and flags rather than resolves — exactly as instructed.

## 6. Tor transport default: OFF → ON (unset preferences only)

`SettingsRepository` flips `torEnabled`'s default from `false` to `true` in exactly two
places (the `Settings` data-class default and `load()`'s `getBoolean` default). The
transport hierarchy (I2P → Tor → clearnet), the probe logic, and all wiring are
untouched — with Tor default-on, `TransportResolver.fallbackState()` now lands on TOR
instead of CLEARNET_FALLBACK for users who never touched the toggle AND have Orbot
installed; users without Orbot see no change at all.

**Never-set vs explicitly-disabled** (see table row 3): no migration write exists or is
needed. The preference key is written only by the user's own toggle, so key-absence IS
the "never set" signal, and `getBoolean(KEY_TOR, true)` applies the new default to
exactly that population. A user who explicitly disabled Tor has `tor_enabled=false` on
disk and keeps it. A migration that "stamped" the new default would have destroyed this
distinction — that is why there isn't one.

`zitrone-MASTER.json`'s `tor.enabled_by_default` / `opt_in` are synced to match (the
master spec is a claims document; it must not claim the old default).

## 7. Activation checklist (human, in order — nothing activates by default)

1. **Key ceremony** (maintainer, offline): `registry-sign.mjs keygen` → custody per §2.
   This is a credential creation — explicitly gated on HoboJoe.
2. Author the epoch-1 manifest (today: one relay, CX23's endpoints incl. I2P dest),
   sign it, verify with `registry-sign.mjs verify`.
3. Publish to `www.zitrone.app/registry/v1/manifest.json` (static file, Vercel).
4. Optional now / required later: add the manifest path to the onion mirror allowlist +
   redeploy (can ride the already-owed onion-mirror redeploy trip), and the I2P mirror.
5. Set `REGISTRY_PUBKEY_ED25519` (and mirror URL env vars) in the release build
   environment, drop the signed bootstrap at `assets/registry/bootstrap.json`.
   The bootstrap's epoch becomes the rollback floor for every device on that build
   (§3.1), so the embedded snapshot must be the CURRENT manifest, not a stale copy.
6. First release built with those set = registry resolution live. Until then the
   shipped behavior is bit-for-bit today's.

**Known residual, tracked as an activation blocker (blind review, P2 — accepted for
v1, must be resolved at cutover):** when every registry source misses, the client
falls back to the hardcoded `API_BASE_URL`/`WS_URL` (§3.3). While those constants
name the pinned live relay that is availability-preserving; but an attacker who can
merely DoS all registry sources holds the fleet on the legacy endpoint indefinitely,
and the fallback means the hardcoded address never actually becomes unreachable.
Step 6 of this checklist therefore carries a standing obligation: the constants'
removal (or a fail-closed degradation mode) is a cutover BLOCKER, not a footnote.
Tracked with the `TODO(zitrone-cutover)` markers in `ZitroneApp.kt`.
