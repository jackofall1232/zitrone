# 0.10.0-beta — Decoy traffic: SPEC

**Status: ✅ APPROVED by maintainer 2026-07-27, with three rulings recorded below. U1 may begin.**
Architect: Fable. Implementation: Opus. Research lanes: Sonnet (3, complete).

### Maintainer rulings (2026-07-27)

1. **Doc corrections pulled out and shipped ahead of U1 — DONE.** Commit `96982421`. The published
   overclaims were corrected in place, visibly rather than silently, same handling as the burn
   relay-account correction. A full sweep for *every* instance found **four** claims, not the three
   flagged: sealed sender, typing indicators, decoy traffic, **and 3-hop onion relay** (design and
   code exist; no client routes messages through it). Website and onion site swept — clean.
   **Residual, tracked as U0 (code, not docs):** the same claims persist in client string constants
   — `packages/protocol/src/connection.ts:55`, `apps/android/.../ConnectionMode.kt:48`,
   `apps/ios/.../ConnectionMode.swift:80`, `apps/web/src/screens/Settings.tsx:152-165`. Only the web
   client renders any of them and it is undeployed, so nothing user-visible currently shows a false
   claim. U0 folds into U6's doc work or lands earlier at Opus's discretion.
2. **Format break: option (a) RATIFIED.** One-way format bump, disclosed exactly as 0.9.1's
   fresh-install-only decision was. (b) is rejected on the recorded grounds: it cannot rescue builds
   already in the field and pays for its safety by loosening a deliberately chosen invariant.
   **The storage-format-stability gate is answered in §4.1 — not deferred a third time.**
3. **Threat model ships in the docs in this spec's own words.** Partially landed already in
   `96982421` (the "Decoy traffic" section of `SECURITY_MODEL.md` now carries the
   passive-observer-yes / relay-operator-no framing and the mechanism-status-only indicator wording
   ahead of the feature). U6 completes it and must not weaken it.

**Approved as specified, no changes:** size mirroring rather than randomization, with the honest
consequence that block class still leaks; random ciphertext rather than a real ratchet, with the
reseal-rate reasoning intact; counter reservation at 64; the in-session dead-air reframe with
`VAULT_ARCHITECTURE.md` §8 **amended** rather than quietly diverging; 821 B single block for the
unpaired ping; the control-channel gap declared as a known residual.

Design is **not re-derived here**. It is locked in `docs/VAULT_ARCHITECTURE.md` §8 (lines 324–346)
and this spec builds on it verbatim. What this document adds is (1) resolution of the two open
questions §8 recorded, (2) the source-verified facts that constrain them, (3) the WRITER/READER
invariant table for the new durable signal, and (4) a unit breakdown.

---

## 0. Executive summary — what changed once the code was read

Three findings reshape the spec relative to what §8 could assume. None of them contradict the
locked design; two of them *strengthen* it, one narrows what it can honestly claim.

1. **The relay was already built for this.** `server/internal/db/schema.sql:34-40` deliberately has
   **no foreign key** on `envelopes.recipient_id`, with a comment naming decoy traffic as the
   reason. Send-to-anyone is accepted, stored, pushed, and acked identically. **No server change of
   any kind is required.** The blind-transport constraint is satisfied by construction, not by
   effort.

2. **The synthetic-pinned-account decision buys indistinguishability by instantiation.** The
   existing web decoy generator (`packages/relay-client/src/decoy.ts`) is statistically
   distinguishable *today* — it pins `message_number: 0`, `previous_chain_length: 0`,
   `ttl_seconds: null`, `burn_on_read: false` on every decoy, and addresses nowhere-UUIDs that are
   never acked, so each decoy sits in the relay's `envelopes` table for the full 72 h TTL while
   real messages are acked and deleted within seconds. A decoy addressed to a **real, registered,
   connected, acking** account has none of those tells. This is the strongest argument for the
   settled design and it is now evidence-backed.

3. **Decoy traffic does not hide anything from the relay, and cannot be claimed to.**
   `sender_id` and `recipient_id` ride the envelope in **cleartext**, and `ws/hub.go:166` rejects
   any envelope whose `sender_id` does not match the authenticated connection. "Sealed Sender"
   exists in the codebase (`packages/crypto/src/sealedbox.ts`) but is wired only to dead-drop and
   lemon-drop, never to ordinary messaging. The 3-hop onion path is likewise config-only — no
   client calls `buildCircuit` or `POST /relay/forward` for a message send.
   **Therefore: decoys defend against a passive network observer who sees only TLS frame sizes and
   timings. They do not defend against the relay operator.** The spec is written to that threat
   model and §7 requires `SECURITY_MODEL.md` to say so in those words.

---

## 1. Threat model — stated before the mechanism

| Adversary | What they see | Does decoy traffic help? |
|---|---|---|
| **Passive network observer** (ISP, Wi-Fi, hostile exit, traffic-analysis at scale) | TLS record sizes and timings only. Cannot read any envelope field. | **YES — this is the target.** A paired decoy makes "user sent a message" indistinguishable from "user sent nothing of consequence," and doubles the candidate set for any timing correlation against a peer's receive event. |
| **Hostile / compromised relay operator** | Cleartext `sender_id`, `recipient_id`, `timestamp`, `ttl_seconds`, `burn_on_read`, ratchet counters. Can trivially learn that account *S* only ever transacts with account *A*. | **NO, and the docs must not imply otherwise.** Closing this requires sealed sender or onion routing for ordinary sends — both unbuilt. Out of scope for 0.10.0. |
| **Forensic adversary with the device** | Whatever is durable. | **Neutral by requirement.** Every vault gets exactly one synthetic account; a locked vault's slot is indistinguishable from random. The mechanism must not become a vault-count oracle — see §4. |

**Existing doc overclaims found, which block an honest §7 and must be corrected as part of this
release** (they are pre-existing, not introduced here):
- `docs/SECURITY_MODEL.md:1032` — "decoy traffic defeats the timing correlation," stated
  unconditionally and about a mechanism that does not exist on the shipped client.
- `docs/SECURITY_MODEL.md:318` — claims typing indicators are encrypted signals. They are
  plaintext control frames carrying `peer_id` in the clear (`WsClient.kt:369-371`, `hub.go:145`).
- `docs/SECURITY_MODEL.md:379` — "Sealed Sender" listed for standard messaging; not implemented
  for that path.

---

## 2. OPEN QUESTION 1 — envelope size and structure indistinguishability. **RESOLVED.**

### 2.1 The measured baseline

Padding is real, correct, and byte-identical across platforms (`packages/crypto/src/padding.ts`,
`MessagePadding.kt` — `len(4,BE) ‖ plaintext ‖ random-fill`, rounded up to 256 B, applied
**before** encryption). Computed frame sizes:

| Content | Padded block | Full `message.send` frame |
|---|---|---|
| Short text or batched read receipt (≤252 B) | 256 | **829 B** |
| Text 253–508 B | 512 | **1169 B** |
| Attachment control payload (always 286 B) | 512 | **1169 B** |
| X3DH first message, short text | 256 | **976 B** (+147 B over a subsequent one) |

> **⚠️ [U2, MEASURED — applied, pending ratification] The four numbers above were corrected.** They
> previously read 821 / 1161 / 1161 / 860, the last with the gloss "+39 B: `ephemeral_key`,
> `prekey_id` non-null". Every one was low, and the first-message row was low by roughly 4×: the
> `PreKeySignalMessage` **wrapper** costs 81 bytes on the wire (version, pre-key id, a 33-byte base
> key, a 33-byte identity key, the inner message's own length header, registration id, signed
> pre-key id) on top of the two JSON fields the old gloss counted — which is exactly what R7's third
> correction predicted and told U2 to measure. Measured through the production
> `MessageEnvelope.toJson()` + `WsClient.messageSendFrame`, not computed.
>
> Also pre-existing and worth knowing, because it is real behaviour rather than a decoy artefact:
> `DateTimeFormatter.ISO_INSTANT` trims trailing zeros in the fractional second, so **real frames
> already vary by up to 4 bytes on the timestamp alone** (a whole-second timestamp makes row 1
> 825 B). Cover traffic uses the same formatter and inherits the variation identically; pinning a
> width would itself have been a tell.
>
> **§3.3 inherits this:** the dead-air ping's "single 256-byte block (821 B frame)" is an 829 B
> frame. The design is unaffected — match the mode, one block — but U5 and `SECURITY_MODEL.md` must
> not carry the old number. Full measurement record:
> `l00prite/.l00prite/reviews/decoy-0.10.0/u2-invariant-table-decision.md`.

Padding does **not** by itself produce uniformity. Three residual size/structure tells exist
independently of decoys: block count is visible; the attachment control payload is 286 B so it
*always* lands one block bigger than a short text; and the X3DH first message is +39 B with two
fields flipping non-null.

### 2.2 Resolution — size mirroring, and structure by instantiation

**Structure: the decoy is indistinguishable from a real envelope in every field the relay can read.**

*(Amended 2026-07-27 after U1. This paragraph previously said the decoy "is a real envelope … over a
session that was genuinely established with one X3DH first message", which read as requiring a real
`SessionBuilder.process`. It does not — see §2.3, which governs. The requirement is on the
**observable**, not on the machinery behind it.)*

It is addressed to a genuinely registered account, and every cleartext field is populated the way
the real send path populates it, with monotonically advancing counters. There is no field whose
value is a constant that a real message's value varies over — which is precisely the defect in the
existing web generator.

**The X3DH first-message observable, and how to satisfy it.** A real conversation's first envelope
carries non-null `ephemeral_key` and `prekey_id`; every later one has them null. The synthetic
conversation must show the same shape: **emit well-formed-looking values exactly once at setup, null
thereafter.**

> **⚠️ [R7] THREE CORRECTIONS, from source research done before U2 started. The first would have
> shipped a fingerprint.**
>
> 1. **`ephemeral_key` is 33 bytes, NOT 32.** This spec said "a random 32-byte value (base64)".
>    Wrong. The real field is `ECPublicKey.serialize()` — a **`0x05` type tag + 32-byte Curve25519
>    point**, `KEY_SIZE = 33` confirmed in libsignal 0.46.0 bytecode. The tell is in the encoding:
>    **33 bytes base64 to exactly 44 characters with NO padding, while 32 bytes produce 44
>    characters ending in `=`.** A decoy built to this spec's original wording would have carried a
>    trailing `=` that no real first message ever has — a perfect one-field discriminator, in the
>    exact field added to defeat discrimination. **U2 must emit `0x05 ‖ random(32)`.**
> 2. **`previous_chain_length` is NOT a web-generator tell.** §0 lists it among that generator's
>    distinguishers. It is not: Android hardcodes the field to `0` on every send
>    (`MessagingCoordinator.kt:924,1159,1315` — libsignal's Java API does not expose it) and iOS
>    likewise never mutates its counter. **Real traffic always emits 0**, so the generator matching
>    it is correct behaviour, not a defect. The other three items in that list stand.
> 3. **A first message's ciphertext is structurally LARGER**, and §2.1's frame table understates it.
>    A `PreKeySignalMessage` carries `registrationId`, `preKeyId`, `signedPreKeyId`, a 33-byte
>    `baseKey` and a 33-byte `identityKey` *on top of* the inner `SignalMessage`. The table's "+39 B"
>    counts only the two JSON fields. **U2 must size the synthetic first envelope's ciphertext to a
>    real `PreKeySignalMessage`, not to a subsequent-message blob** — today's web generator only ever
>    produces the subsequent shape, so there is no prior art to copy here.

> **BINDING FOR U2 — `prekey_id`. RESOLVED FROM SOURCE; the constraint is now specific.** It is the
> **RECIPIENT's** one-time prekey id, not the sender's: the sender fetches the peer's bundle, and
> libsignal replays that consumed id on every message until the peer's reply completes the ratchet
> (`SignalProtocolManager.kt:299-329`, `ApiClient.kt:215-231`, `store.go:143-157`). Ids are
> **sequential from 1, +1 per allocation, wrapping at `0xFFFFFF`**, issued in batches of 100
> (`SignalProtocolManager.kt:406-413`).
>
> **This makes the decoy case easy and exact:** the "recipient" is our own synthetic account, whose
> prekey ids *we* generated at registration. **U2 draws from that account's own uploaded batch** —
> not from a guessed range, and not at random. A value outside it is a fingerprint.

U1 already registers a genuine prekey bundle for the synthetic account (so the relay's view of that
account is an ordinary one) while discarding the private halves — which turns out to be exactly what
makes the `prekey_id` constraint satisfiable, since the ids in that bundle are the legitimate draw.

**Size: the paired decoy mirrors the block count of the real message it is paired with, exactly.**

This is the whole resolution and it is worth stating plainly: do **not** randomize decoy size, and
do **not** always send a single block. Mirror. A real 1161 B attachment send emits a 1161 B decoy;
a real 821 B text emits an 821 B decoy. The observer then sees two identical-size frames a few
milliseconds apart in an order they cannot predict, and has no size-based way to say which was
real. Randomizing instead would create pairs like {821, 1161} where the attachment-shaped frame is
immediately identifiable as the real one whenever the user's actual message was short.

Consequence to accept honestly and document: mirroring **preserves** the block-count signal (an
observer still learns "an attachment-sized thing was sent"). It hides *which transmission was the
real one*, not *what class of thing was sent*. That is the correct scope for a paired scheme and it
must not be described as more.

### 2.3 The ciphertext does not need to be a real ratchet output — and should not be

The synthetic account is our own and the decoy is burned on delivery, so **nothing ever needs to
decrypt it.** Therefore the decoy ciphertext is **random bytes laid out in libsignal's real
serialized-message form** — byte-shaped identically to a genuine `SignalMessage` (or, for the first
envelope, a `PreKeySignalMessage`) and computationally indistinguishable from one to anybody without
the key, which includes everybody.

> **⚠️ [U2, MEASURED — applied, pending ratification] This paragraph previously specified the blob as
> `random(32) ‖ random(12) ‖ random(N·256 + 16)`, "byte-shaped identically to a genuine
> `ratchet_pub ‖ nonce ‖ AEAD(ct+tag)`". That is a generic AEAD framing and NOT what libsignal
> serializes.** A real one-block `SignalMessage` is **323 bytes**, not the formula's 316 — and the
> miss is not merely seven bytes: 323 base64s to 432 characters ending `=` while 316 gives 424
> ending `==`. **It is the identical defect R7 caught in `ephemeral_key`, in the field next to it,
> and it would have marked every decoy rather than only first ones.**
>
> Two further facts the formula cannot express, both measured: **the counter is a protobuf varint, so
> `message_number` changes the ciphertext LENGTH** (127 costs one byte, 128 two, 16 384 three) — and
> `message_number` rides in the cleartext, so a decoy sized from any fixed formula is checkably short
> from its 128th envelope onward. And the `PreKeySignalMessage` wrapper is 81 bytes, per §2.1's
> corrected table.
>
> **⭐ CANONICAL: the wire layout lives in `DecoyEnvelopeBuilder`'s wire-shaping section**, next to
> the code that emits it and pinned on every test run by a byte-diff against real `SessionCipher`
> output. **It is deliberately not restated here** — a shape written down in three places has three
> chances to rot, which is the failure this document has already recorded seven times about a
> different claim. Measurement record:
> `l00prite/.l00prite/reviews/decoy-0.10.0/u2-invariant-table-decision.md`.

This is a deliberate rejection of "run a real Double Ratchet with the synthetic peer," for a
specific and load-bearing reason: **every real ratchet advance is a durable `VaultState` mutation,
so a real-ratchet decoy would double the vault reseal rate.** That is battery cost, capacity
pressure against `MAX_PAYLOAD_CONTENT_BYTES`, and — worst — new write traffic through the exact
`VaultSession` flush machinery that 0.9.1 spent eleven review rounds hardening. Random ciphertext
buys the same observable at none of that cost.

> **RULING 2026-07-27 (U1 raised the conflict; §2.3 governs). DO NOT call
> `SessionBuilder.process` for the synthetic peer.** §2.2 as originally written could be read as
> requiring a genuinely established X3DH session. It is not required and is now amended. Running a
> real session establishment would write a durable ratchet session into the **real** vault's
> `signalRecords` — a cost the §4 capacity budget does not cover — to buy an observable that random
> bytes satisfy identically. The one field that genuinely must look real on the first envelope is
> `prekey_id`; see the binding constraint in §2.2.

**What must still be durable is the counter**, because a `message_number` that resets or regresses
is a tell a real ratchet can never produce. Handled by **reservation**: reserve a block of 64
counter values, make the new high-water mark durable, then spend the block from RAM and reserve
again when it is exhausted. A crash therefore *skips* counter values (invisible — a real ratchet
skips too, on any dropped message) but can never *regress* them. One durable write per 64 decoys
instead of one per decoy.

> **CORRECTION (2026-07-27, U1 review round 1 — the architect's error, not the implementer's).**
> This paragraph originally read "reserve a block of 64 counter values **in `VaultState`** … persist
> a new reservation when exhausted", which specified the right invariant against the wrong
> mechanism. **Writing to `VaultState` is not persistence.** `VaultRuntime.mutate` applies the block
> to the live state, encodes it, and hands the bytes to `VaultSession.update`, which snapshots,
> marks the session dirty and returns — "Non-blocking by session contract: it copies + schedules, no
> I/O here" (`VaultRuntime.kt:132`). The write lands later, when the ≤2 s coalescing ceiling fires.
> A crash inside that window loses the high-water mark, and the next session reissues the whole
> block — precisely the regression this mechanism exists to prevent.
>
> **The durable step is `VaultRuntime.flushBeforeAck()` → `VaultSession.flushNow()`, and its throw
> means the value was never issued.** The rule generalizes past the counter, and every U1 writer was
> re-audited against it: **anything whose correctness depends on surviving process death must
> `mutate` AND `flushBeforeAck`, and must treat a throw from the flush as "it never happened".**
> That covers the counter reservation (the RAM cursor advances only after the flush returns), the
> credential commit (which reports readiness, and had spent a scarce global registration), and both
> back-offs (§6.2a's "back off across sessions" is a durability claim). It does NOT cover the
> session tokens, which stay coalesced because they are re-mintable from the stored identity key —
> the same exception `VaultAuthStore` makes.
>
> §4's R4 reader row and the U1 WRITER/READER table inherited the same error and are corrected in
> `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md`. **U2–U6 inherit the corrected
> rule, not this paragraph's original wording.**

### 2.4 The uncovered channel — declared, not silently ignored

`typing.start/stop` (72 B), `message.ack` (74 B), `message.burn` (124 B), `message.received`
(128 B) are plaintext control frames carrying `peer_id`/`message_id` in the clear. They are
trivially separable from any `message.send` (821 B+) by size alone, and **this scheme generates no
cover for them.** A real conversation also produces inbound receipt traffic from the peer that a
decoy exchange does not naturally produce.

> **⚠️ [U2] A SECOND declared residual, in a cleartext field, which U4 makes real.** A real client
> resets `message_number` to 0 on **every inbound ratchet turn**. The counter reservation is
> monotonic by §2.3's deliberate choice and never resets. While the synthetic side only acks and
> burns this is invisible. **Once U4 makes the exchange bidirectional, a relay comparing inbound and
> outbound sees a counter climbing through replies that should have reset it.** Relay-visible only,
> so §1's threat model already covers it — but it is written down here rather than left for U4 to
> discover, per the same rule as the paragraph above. It is not a reason to abandon monotonicity: a
> counter that REGRESSES is a tell no real ratchet can produce at all, which is the worse of the two.
>
> *(The protobuf's own `previous_counter` is not part of this and was measured, not reasoned about:
> libsignal writes the last COUNTER of the previous chain rather than its length, so a client whose
> one-message first chain was answered emits 0 for its whole next chain — which is what U2 emits.)*

Partial mitigation is in scope (§5, U4): the synthetic side acks and burns, and occasionally sends
back, so a decoy exchange produces control frames of its own rather than being a conspicuously
one-directional flow. Full coverage of the control channel is **explicitly out of scope for
0.10.0** and must be listed as a known residual in `SECURITY_MODEL.md`. Per the standing rule about
silently-capped coverage: this gap is written down, not left to be discovered.

---

## 3. OPEN QUESTION 2 — idle-ping sizing. **RESOLVED, and the premise is corrected.**

### 3.1 The premise correction — this is the finding that most changes §8

**The app has no background execution of any kind.** Verified: `AndroidManifest.xml` declares no
service and no receiver; there are zero matches across the entire Android source for
`WorkManager`, `AlarmManager`, `JobScheduler`, `FirebaseMessaging`, or `startForeground`; the only
permissions are `INTERNET`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `CAMERA`. `VaultLockManager.kt:69`
states it as design: *"There is no push stack: messages only arrive over the live WebSocket while
the app is unlocked."* Network clients exist only inside a live `SessionContainer`, which exists
only between `unlock()` and `lock()`.

So a literal "1–2× per day, randomly timed, covering dead-air" daemon **cannot be built as
specified** without introducing background infrastructure this app has deliberately never had. And
it should not be: the synthetic account's credentials live inside the vault, so a locked-state ping
would require either holding vault-derived secrets outside the vault — a direct deniability
violation — or a background service that wakes and can produce no traffic, which is worse than
nothing.

### 3.2 Resolution — reframe as in-session dead-air cover, and say so

Ship it as **dead-air cover within an unlocked session**: an unpaired decoy fires when a live
session has been quiet for a randomized interval, targeting a rate of 1–2 per equivalent
unlocked-day rather than per wall-clock day. Everything else about it is unchanged from §8.

This delivers what §8 actually wanted it to deliver — "total silence is not a signal" — for every
period the app can transmit at all, and is honest about the rest. §8 already assigned it little
unlinkability burden, so narrowing it costs the design nothing. **`VAULT_ARCHITECTURE.md` §8 must
be amended to this** rather than shipping something that quietly differs from the recorded design.

If a true 24/7 idle ping is later wanted, it is a separate release with its own gate: it needs a
foreground service, a persistent notification, and a fresh deniability analysis of what runs while
locked. Recorded as a follow-up, not smuggled in here.

### 3.3 Sizing — match the mode, do not sample a distribution

The standalone ping has no paired real message to mirror, so §2.2's mechanism does not apply.
**Always emit a single 256-byte block (821 B frame).**

The reasoning is that we cannot sample the real distribution even if we wanted to: message content
is **RAM-only and never persisted** (`MessagingCoordinator.kt:2343`; `MessageRepository` has no
persistence layer), so there is no history to draw from, and a guessed distribution that is wrong
is itself a fingerprint. The 821 B single block is the modal real frame by a wide margin — every
short text and every batched read receipt is one. An observer seeing 821 B frames during a quiet
period sees exactly what "the user sent a short message" looks like. Matching the mode exactly beats
inventing a spread.

---

## 4. Durable state — WRITER/READER invariant table

Built **before** implementation, per the standing rule: any change to a durable multi-reader signal
gets its writers, its readers, and what each reader assumes the signal MEANS at the moment it reads
enumerated first. The durable signal here is **`VaultState` TLV section `TAG_DECOY = 0x06`**.

Source-verified against `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultState.kt`
(tags `0x01`–`0x05` at lines 158–162; strict-v1 unknown-tag rejection at line 285) and
`crypto/vault/VaultRuntime.kt` (single mutation gate, lines 119–144) at current `main`.

### The signal

A new optional TLV section in the per-vault sealed payload holding: the synthetic account's
**account id + identity keypair + session tokens**, the **counter reservation high-water mark**, the
**dead-air schedule next-fire**, and — *added by U1* — a **durable provisioning back-off deadline**
(`provisionNotBeforeMs`; originally scoped to 429 only, generalized by U1 R2 to a write-ahead
deadline covering every attempt), which has no other legal home because cross-session back-off must
be durable and durable decoy state may not be device-level. It lives inside the vault region
and nowhere else. Nothing about decoy traffic may be written to device-level storage
(`SettingsRepository`, `DeviceSettings`, any `SharedPreferences`) — a device-level record of how
many synthetic accounts exist is a vault-count oracle and destroys the deniability §3 of
`VAULT_ARCHITECTURE.md` establishes. The section is written by exactly one component and the
fixed-size sealed region does not grow, so its presence or absence is not observable from the
encrypted image.

### WRITERS

| # | Writer | When | What it writes into `TAG_DECOY` | Status |
|---|---|---|---|---|
| W1 | `DecoyAccountProvisioner.provision()` | First unlocked session in which decoys are enabled and no synthetic account exists | Account id, identity keypair, initial tokens, and `provisionNotBeforeMs = null` — ~~a success is the only thing that retires the back-off~~ **[U1 R3/R4] success is not the only retirement path; see W1d.** It is the only one that retires the deferral *while writing something*, which is why it rides in this same mutate: there is no window where the credentials are durable and the deferral is not. **The counter reservation is NOT written here** — `counterHighWater` stays 0 until `DecoyCounterReservation.next()` first reserves a block (W3). **Dead-air next-fire is written `null`** — the distribution is U5's to settle (§3.2 re-framed the ping from wall-clock to in-session, so a durable wall-clock next-fire is of questionable meaning). The field exists and round-trips. | **DONE (U1)** |
| W1b | `DecoyAccountProvisioner.reserveBackoff()` — the **write-ahead back-off** | **Before any relay contact**, on every attempt that gets past the deferral check | `provisionNotBeforeMs` only — the cross-session back-off deadline, `mutate` + `flushBeforeAck`. If it cannot be written, **no registration is spent at all** | **DONE (U1 R2)** |
| W1d | `DecoyAccountProvisioner.clearBackoff()` — the **retirement of a deferral that protected nothing** | An attempt that fails **before** `register` is entered: offline challenge fetch, DNS failure, failed proof-of-work, a local crypto fault, a cancelled scope | `provisionNotBeforeMs` → null, `mutate` + `flushBeforeAck`, **compare-and-clear**: only the deadline *this* attempt wrote is retired, checked under the section lock, so a deferral another writer put there meanwhile is left alone. Emptying the holder is what removes `TAG_DECOY` entirely and restores 0.9.x readability | **DONE (U1 R3)** — **added to this table U1 R4; it was a real durable writer the inventory omitted** |
| W2 | `DecoyAccountProvisioner.refreshTokens()` | Synthetic session token refresh (7-day refresh-token TTL, `auth/jwt.go:26`) | Tokens only; all other fields untouched | **this unit (U1)** |
| W3 | `DecoyCounterReservation` | Counter reservation exhausted (once per 64 decoys) | High-water mark only, monotonically increasing | **allocator DONE (U1)**; the `DecoySender` that spends the values is U2 |
| W4 | `DeadAirPinger.rearm()` | After each dead-air ping fires | Next-fire time only | **this unit (U5)** |
| W5 | `VaultRuntime.mutate` (existing) | Every write above, without exception | Re-encodes whole `VaultState` under `stateLock` and **SCHEDULES** a reseal — **it is not durable**, see §2.3's correction | existing |
| W6 | `VaultRuntime.flushBeforeAck` (existing) | W1, W3, and **all three** back-off writes — W1b, W1d, and W1's retirement — every value that must survive process death | Forces the scheduled payload to disk synchronously. **A throw means the value was never issued / never recorded** | existing — **added 2026-07-27 (U1 R1)**; W1d added R4 |

### READERS, and what each assumes `TAG_DECOY` MEANS

| # | Reader | Assumes `TAG_DECOY` means | Still true after W1–W4? |
|---|---|---|---|
| R1 | `VaultStateCodec.decode` | "a section tag I recognize; an unrecognized tag is corruption" | **NO for old builds — see hazard below.** YES for builds carrying the tag. |
| R2 | `DecoySender.send()` | "a provisioned synthetic account exists and these counters have never been issued before" | YES **only with §2.3's correction** — the mark must be FLUSHED, not merely mutated, before any value in the block is spent |
| R3 | `DeadAirPinger` | "next-fire is in this vault's own timeline, not the device's" | YES — per-vault, torn down at lock |
| R4 | provisioning entry point (`DecoyAccountProvisioner.provisionIfNeeded`) | ~~"absent section = decoys not yet provisioned; present = ready"~~ ~~"ready = credential pair present"~~ ~~"ready = credential pair present **and** `capacityExceeded` clear"~~ **CORRECTED A THIRD TIME (U1 review round 2) — there is no single "ready". TWO predicates:** `hasAccount()` = the credential pair is present, **and nothing else**, which gates REGISTRATION; `canSend()` = `hasAccount()` **and** this session's credential flush confirmed **and** `VaultRuntime.capacityExceeded` clear, which gates COVER TRAFFIC. | **NO as originally written. Three independent falsifiers.** (i) A back-off creates a section that is PRESENT and NOT ready. (ii) An over-capacity `mutate` **RETAINS** a complete credential pair in the LIVE state that was never scheduled and that `flushBeforeAck` refuses. (iii) **The corrected single predicate was itself wrong** — see below. Absence is still the valid initial state; presence never means ready. |
| R5 | Capacity guard `VaultRuntime.capacityExceeded` | "encoded state fits `MAX_PAYLOAD_CONTENT_BYTES`" | YES — measured by U1: worst-case section delta **645 B** against a 1024 B budget (realistic state 929 B of 262 112 B) |
| R6 | `VaultState.wipe()` | **NEW (U1):** "every secret in this section is zeroed, not merely dereferenced" | The section carries a **raw private key** — dereferencing leaves it in the heap |
| R7 | `VaultStateCodec.parsePlaintext` decode-failure catch | **NEW (U1):** "everything decoded so far is wiped on a mid-parse throw" | Previously wiped only the partial signal map; had to extend to the decoy section's keypair |

**R4 FALSIFIED THREE TIMES — and this is the spec-first discipline working, not a spec failure.**

*First falsifier, found by implementation.* U1 needed a durable 429 back-off deadline
(`provisionNotBeforeMs`), because "back off across sessions" means durable and the no-device-storage
rule leaves the section as its only legal home. That makes the section a **sixth** field where this
table said three, and it breaks R4 directly: a section can be *present* while holding nothing but a
deferral.

*Second falsifier, found by review round 1 (Grok).* Even a **complete credential pair** in the live
state does not mean ready: when `mutate` overflows the fixed region it **retains** the mutation
unscheduled and sets `capacityExceeded`, so a reader keying on the pair alone reports ready for
credentials no reader will ever find on disk. Readiness must consult the capacity flag too.

*Third falsifier, found by review round 2 (Grok) — and this one is the ARCHITECT'S, not the
implementer's.* The correction above is a **send** predicate, and `provisionIfNeeded()` was gating
**registration** on it. Those are different questions and one predicate cannot answer both. When an
**unrelated** write overflows the region on a vault that already holds durable synthetic
credentials, a capacity-aware "ready" returns false, the one-attempt latch is taken, and the
provisioner **registers a second relay account** — spending a rate-limit bucket shared by every
client worldwide, and replacing a perfectly good durable account if the overflow clears mid-flight.

Refusing to *send* cover traffic during an overflow is correct. Refusing to *acknowledge an account
that already exists* is not: it re-enters the one path that spends a shared global resource. The
implementer documented the capacity-aware readiness as "conservative in the right direction". It was
not conservative; it was harmful. **So R4 is now two rows in one:**

| Predicate | Reads | Gates | Must NOT read |
|---|---|---|---|
| `hasAccount()` | `accountId != null && identityKeyPair != null` | registration | `capacityExceeded`, or any other transient runtime condition |
| `canSend()` | `hasAccount()` ∧ this session's credential flush confirmed ∧ `!capacityExceeded` | cover traffic | — |

Worth recording plainly, because it is the argument for both gates at once: **the table was wrong,
the first error was caught by implementation rather than by review two rounds later, the second was
caught by review rather than shipping — and the third was a correction the architect ratified into
the spec that review then falsified in turn.** That is the round-12 pattern (changing what a durable
signal MEANS) surfacing at the cheapest available moments, including once *after* the spec had
already been "fixed". R6 and R7 are the same story from a third direction: obligations this table
simply missed, found by writing code against it. A table that survives implementation unchanged has
usually not been tested; one that gets corrected has done its job.

### THE HAZARD THIS TABLE EXISTS TO CATCH

**`VaultStateCodec` is strict-v1: an unknown tag throws, it is never skipped** (`VaultState.kt:285`,
comment "an unknown tag is corruption / a wrong version, never skipped"). So a vault written by a
0.10.0 build carrying `TAG_DECOY` and then opened by a 0.9.x build — downgrade, sideload of an
older APK, an A/B test rollback — **does not degrade gracefully. It reads as a corrupt vault.**
Depending on how the corrupt path is handled that is anything from a failed unlock to a wiped
image, on a build whose whole purpose is deniable storage.

This is the specific interaction the table exists to surface, and it is the single highest-risk item
in the release. It must be resolved before U1 writes a line of code. Options, for the maintainer to
rule on:
- **(a)** Accept and gate: 0.10.0 is a one-way format bump, disclosed in release notes exactly as
  the 0.9.1 fresh-install-only decision was disclosed. Cheapest, consistent with the standing
  storage-format-stability gate still being open.
- **(b)** Make the decoder forward-tolerant for *unknown high tags only* first, as a separate
  prerequisite unit, shipped one release ahead so a downgrade target exists. Correct, but it
  weakens a strictness property that was chosen deliberately, and it cannot help downgrades to any
  build already in the field.
- **(a)** is the recommendation, because (b) does not actually rescue existing installs and buys
  its safety by loosening a deliberate invariant.

**RULING: option (a).** One-way format bump, disclosed as 0.9.1's fresh-install-only decision was.

> **⚠️ BLAST RADIUS NARROWED BY U1 — the break is NOT universal.** The hazard above is written as
> though every 0.10.0 vault becomes unreadable by 0.9.x. **It does not.** U1's codec omits the
> section entirely when the decoy state is empty — `state.decoy?.takeUnless { it.isEmpty }` — so
> `TAG_DECOY` is omitted whenever there is nothing to record, so a large class of vaults keeps full
> 0.9.x readability. A user whose vault never uses cover traffic keeps one that opens fine.
>
> Option (a) still stands and the ruling is unchanged; only its scope is smaller than priced. **The
> disclosure in §4.1 is narrowed accordingly** — an overstated disclosure is its own dishonesty, and
> scaring every user about a break most of them will never hit is not caution, it is inaccuracy in
> the direction that happens to feel safe.
>
> **⭐ For exactly when the tag lands on disk, see the CANONICAL list in `VaultState.kt`'s codec
> kdoc. It is not restated here, deliberately.** This block previously carried its own paraphrase
> ("the trigger is setup that REACHES THE RELAY"), which went stale when round 5 added the crash
> path — the seventh time a paraphrase of this claim was found rotten. **[R7]** Restating it in a
> second place buys nothing and guarantees a future mismatch; §4.1's user-facing sentence is
> deliberately written as a possibility claim so that it does *not* depend on that list.

### 4.1 Storage-format-stability gate — ANSWERED, not deferred a third time

The standing gate (`[[zitrone-storage-format-stability-gate]]`) says: before external testers,
either commit to storage-format stability or disclose wipe-on-breaking-change. It has now been
deferred twice, and 0.10.0 is the second breaking change. **Answering it is in scope for this
release.**

**The answer is DISCLOSE, and it cannot honestly be anything else right now.** Committing to
stability means promising that a future release will not require a wipe. Migrations are not built,
no migration framework exists, and 0.10.0 is itself proof that the format is still moving. A
stability promise made today would be a promise the project has no mechanism to keep — which is the
precise failure mode the deliver-then-claim rule exists to prevent.

So, shipping **with** 0.10.0, in release notes and in `SECURITY_MODEL.md`:

> **Your vault format is not yet stable.** Zitrone is in beta and the on-disk vault format is still
> changing. A future release may require a fresh install, which **erases every vault on the device
> and everything in them** — contacts, sessions, settings. There is no migration and no export. Do
> not keep anything in Zitrone that you cannot afford to lose.
>
> **What 0.10.0-beta specifically changes:** any vault on which cover traffic has ever been enabled
> or attempted — even once, even if the attempt failed, was interrupted, or never completed — **may**
> no longer be readable by 0.9.x, and downgrading may present that vault as corrupt. A vault on which
> cover traffic was **never enabled** is unaffected. If you are unsure, assume the vault is affected.

> **✅ SIXTH PASS — RATIFIED BY THE MAINTAINER 2026-07-27. THIS WORDING IS FINAL.** Arrived at by
> third-lens tie-break; the reasoning is preserved below because the *process* that produced it is
> the reusable part. The paired
> reviewers **disagreed** on version five: one held it still false in the crash window, the other
> held it sound. The architect resolved it in favour of "sound" — and the maintainer identified the
> gap in that resolution: the architect had argued the exempting clause did not apply to a vault
> whose setup began, but after the H1/H5 fix such a vault leaves **no tag at all**, so the clause
> *does* apply cleanly. The resolution had picked the reviewer who agreed with the already-written
> sentence.
>
> **The third lens was called and ruled for "still false".** Its decisive argument was one neither
> paired reviewer made: *the hedge does not fail because it is weak, it fails because it addresses
> the wrong thing.* "If you are unsure, assume it did" answers **epistemic uncertainty** — but the
> crash-path user is not unsure, they are **certain and wrong**, because the sentence's own
> definitional clauses ("sends", "used", "set up") placed them in the exempt category. A hedge
> against doubt does nothing for a reader the text has actively miscategorised. It further held that
> "has set up" is present-perfect and reads as *completed* action, so a user whose provisioning
> crashed will truthfully report "I never set up cover traffic".
>
> **Version six inverts the structure** to an invariant that does not depend on *when* the marker is
> written: **no attempt of any kind ⇒ guaranteed unaffected; any attempt ⇒ *may* be affected.** The
> "may" is doing deliberate work — it avoids overstating, since a cleanly-retired attempt genuinely
> is unaffected — and a possibility claim on the safe side of a format break is the correct place to
> be imprecise. This is the first version whose truth does not move if U2/U3 change the write timing.
>
> **The full history, kept because the pattern is the lesson.** Six versions, and every one before
> this was falsified by a later review round, in a different direction each time:
>
> 1. *"vaults created by 0.10.0 cannot be opened by 0.9.x"* — **too broad.** The tag is only written
>    once there is something to record.
> 2. *"the first time it sends any"* — **understating.** Registration alone installs the tag.
> 3. *"tries to send"* — **overstating.** The architect's own proposal; a pre-`register` failure
>    retires the deferral and keeps 0.9.x readability.
> 4. *"…and is complete once its account is registered"* — **false under crash-at-any-instruction.**
> 5. *"…if you are unsure, assume it did"* — **still misleading**, per the third-lens ruling above:
>    it hedges doubt for a reader the text had already miscategorised as exempt.
> 6. **This version** — inverts to a possibility claim keyed on *any attempt*, which is the first
>    formulation independent of write timing.
>
> Versions 1–5 all shared one root error: **each was edited from the previous wording rather than
> re-derived from the code's behaviour.** That is the `failures.md` entry *the
> invalidated-from-underneath claim* in its most concentrated form — and it took a third independent
> lens to break out of it, because both paired reviewers and the architect were by then reasoning
> about the sentence instead of about the paths.
>
> **The precision lives in the internal truth table
> below, which is where it belongs.**

*(Narrowed 2026-07-27 after U1. The first draft said flatly that "vaults created by 0.10.0 cannot be
opened by 0.9.x", which is false: the tag is omitted whenever there is nothing to record. Corrected
rather than left overbroad — the deliver-then-claim rule cuts both ways, and a disclosure that
overstates harm is as inaccurate as one that understates it. **[R7] This note previously said the
tag is written "only once cover traffic has actually been generated" — itself a stale paraphrase of
the trigger, teaching the wrong rule inside an explanation of an earlier wrong rule. See the
CANONICAL list in `VaultState.kt`.**)*

> **[R7] PROCESS BANNER CORRECTED — the sentence above is the SIXTH pass and is RATIFIED FINAL.**
> This block previously still announced itself as the "THIRD pass … PENDING RE-RATIFICATION", three
> versions out of date, sitting directly beneath a sentence marked ratified — a process-stale banner
> is as misleading as a stale technical claim, because a reader trusts it to tell them whether the
> thing above is settled. The table below is current and correct (it carries the crash row); only
> its banner had rotted. Kept as the enumerated trigger, cross-checked against the CANONICAL list in
> `VaultState.kt`:
>
> | Path | `TAG_DECOY` on disk? |
> |---|---|
> | Never attempts provisioning | no |
> | Fails **before** `register` (offline, DNS, failed PoW, local crypto fault) — deferral retired **and the retirement flushed** | no — the emptied holder is omitted |
> | Fails before `register`, but **the process dies after the write-ahead flush**, or the retirement's own flush fails | **yes** — nothing can run to retire it. *(Added round 5: the crash model requires this row; its omission is what made §4.1 false.)* |
> | **Reaches `register`** (including a 429, or a lost response) | **yes** |
> | Succeeds, never sends a decoy | **yes** |
>
> So the trigger is **setup that reaches relay registration, plus any interrupted setup that could
> not retire its own write-ahead deferral** — not a completed send, and not a send *attempt* either.
> "Tries to send" would have told a user who failed offline that they had lost their downgrade path
> when they had not. *(Corrected round 6: this note previously said "accurate on all four rows" and
> omitted the crash row, which is the same residual-restatement failure recorded as round 5's K3 —
> the correction landed in the table the reviewer cited and this parallel summary survived it.)*
>
> **Why it keeps drifting, recorded so the next pass does not repeat it:** the sentence's truth
> depends on an implementation detail that three rounds of review have each moved. It must be
> re-derived from the code on any change to the provisioning failure paths, never edited from its own
> previous version.
>
> **Applied now rather than left standing while it waits**, because an understated format-break
> disclosure is the more dangerous direction and the previous wording was understated. The
> narrowing this sentence descends from was an explicit maintainer ruling, so every subsequent
> movement is flagged rather than made quietly. **An overstated disclosure is its own dishonesty —
> which is why the maintainer narrowed it — but an understated one is worse.**

**And the condition under which the promise flips**, so this is a commitment and not an indefinite
disclaimer: **stability is committed to when a migration path exists and has been exercised across
at least one real format change.** Until that lands, every release carrying a format change repeats
the disclosure. The gate is answered — the answer is "disclose, and here is what would change it" —
and it should now be closed in `todos.md` rather than carried forward a fourth time.

**Sequencing note:** the disclosure is a *precondition* for external testers, not for this release's
merge. But 0.10.0 must not ship without it, because 0.10.0 is the release that makes the second
break real.

### 4.2 Account deletion and the synthetic account — RULED 2026-07-27 (raised by U1)

`deleteAccountAndWipe` deletes the real relay account and obliterates the vault image. A provisioned
synthetic account survives on the relay, because nothing today knows to delete it.

**RULING: delete it too — best-effort, fail-open, and silent.**

The binding constraint is not the deletion, it is what the deletion may not touch:

> **The synthetic delete must never block, delay, or complicate the real account's delete path.**
> That path is the two-marker no-remanence state machine that took **sixteen review rounds** to
> harden, and every one of those rounds found a real defect. A decoy cleanup is not worth one unit
> of added risk to it. Concretely: the synthetic delete may not gate the real delete, may not extend
> the real delete's critical section, may not introduce a new failure mode into it, and may not add
> a durable marker of its own. If the two cannot be sequenced without entangling them, **drop the
> synthetic delete** — the residual is inert.

**Failure is silent and the orphan is a documented accepted residual.** Fail-open is correct here
for a specific reason, not as a convenience: an unused registered account is **inert**. It is an
`accounts` row holding an identity public key and nothing else. The relay does no request logging
(by design), envelopes are deleted on ack, and `delivery_receipts` carry only `SHA-256(message_id)`
with no account linkage. There is no history attached to it and nothing on the wiped device points
at it. So a failed synthetic delete leaks nothing beyond what §1 already concedes the relay
knows — and §1 already concedes the relay knows everything that matters here.

Document the residual in `SECURITY_MODEL.md` with the feature (U6), in one honest line: deleting
your account removes it from the relay, and best-effort removes the cover-traffic account it
created; if that second removal fails it leaves an empty account behind that is linked to nothing.

### CRASH ATOMICITY — to be verified, not assumed

`TAG_DECOY` is written only through `VaultRuntime.mutate`, which re-encodes the entire state under
one lock and schedules one atomic reseal. There is no multi-write sequence and therefore no partial
state to reason about: a crash either leaves the previous whole state or the new whole state.
**(U1 R1: atomic ≠ durable. `mutate` guarantees that whatever lands is whole; it does not guarantee
that anything lands. See §2.3's correction for which writes must additionally flush.)** The
one ordering constraint that must be enforced in code and pinned by test: **the synthetic account
must be registered on the relay *before* its credentials are committed to `VaultState`, and a
commit failure must leave an orphaned relay account rather than a `VaultState` referencing an
account that does not exist.** An orphan is harmless (an unused registered account); a dangling
reference breaks every subsequent decoy send. U1's test matrix must cover crash-between-register-
and-commit explicitly.

### WHAT THIS WRITE MUST NOT DO

1. Must not write anything decoy-related to device-level storage. Vault-scoped or nowhere.
2. Must not make the sealed region's size vary with decoy state — the region is fixed-size and
   stays so.
3. Must not be a device-global singleton. One instance per live `SessionContainer`, per
   `NotificationScheduler` parity invariant 3.
4. Must not survive teardown. Every decoy component gets a `cancelAll()`-equivalent hook wired into
   `MessagingCoordinator.stop()` alongside the existing notification teardown.
5. Must not name a slot, vault index, or "real/decoy" anything in code, logs, diagnostics, or
   string resources — the slot-agnostic discipline of `crypto/vault/*` applies unchanged.
6. Must not push `VaultState` near `MAX_PAYLOAD_CONTENT_BYTES`. U1 delivers a measured byte budget
   for the section and a test asserting headroom, since R5 depends on it.

---

## 5. Implementation units — Rule of 6, hard cap at 6

Each unit is independently reviewable, adversarially reviewed to convergence, and merged before the
next begins. No version bump, no push, nothing merged without explicit maintainer approval.

| Unit | Scope | Gate to clear before the next unit |
|---|---|---|
| **U1** ✅ | Synthetic account provisioning + `TAG_DECOY` codec section. Lazy registration, credential storage, token refresh, capacity budget, counter-reservation allocator. **Built, deliberately UNWIRED** — nothing constructs it, so the branch cannot spend a registration. | **DONE** on `feat/0.10.0-decoy-u1-provisioning`. **678 tests / 0 failures** after fix round 4, `assembleDebug` exit 0, re-verified independently each round. Capacity measured: 640–643 B worst case against a 1024 B budget. **Paired-blind review of the WHOLE unit: four rounds complete** (findings 10 → 11 → 10 → 6; P1s 2 → 1 → 0 → 0), fixes applied and mutation-verified each round. **Merge still owed an explicit maintainer decision**, plus re-ratification of §4.1's third-pass wording. |
| **U2** ✅ | Decoy envelope builder. Random-ciphertext blob at a requested block count; field population mirroring the real send path; the one-time X3DH-shaped first envelope. *(Counter reservation moved to U1.)* | **BUILT on `feat/0.10.0-decoy-u2-envelope-builder`, deliberately UNWIRED** — nothing constructs it, so the branch cannot emit cover traffic. `DecoyEnvelopeBuilder` + 14 gate tests. **691 tests / 0 failures / 3 skipped**, `assembleDebug` exit 0. **16 mutations run, 16 discriminated** (M13 needed a new byte-diff test first — recorded). **No `SessionBuilder.process`, no Signal record written**, pinned by test. `prekey_id` = the id the relay would issue (`ORDER BY prekey_id LIMIT 1`) from the account's own uploaded batch, with the batch declaration made the single source both the generator and the builder read. **Three spec corrections found and applied — §2.1's table, §2.3's ciphertext formula, §2.4's counter residual.** Independent paired-blind review NOT yet run. |
| **U3** | Pairing at the send choke point. Random order (decoy-first / real-first), few-ms stagger, block-count mirroring. Insertion inside `MessagingCoordinator`'s confined worker, above `ws.sendMessage`. | Ordering is uniformly random and stagger is drawn per-send — pinned by a statistical test, not by inspection. Real-send latency and the `flushSendRatchet` durability barrier provably unaffected. |
| **U4** | Synthetic-side receive: second WS connection for the synthetic account, deliver → ack → burn at ~30 ms, occasional send-back so the exchange is bidirectional. | Decoys never surface in UI, notifications, or unread counts. Notification parity §7 re-verified with decoys active. |
| **U5** | Dead-air ping within a session (§3.2), single block, per-vault schedule. | Fires only in a live session; torn down at lock with everything else. |
| **U6** | 🍋‍🟩 indicator + docs. `SECURITY_MODEL.md` honest framing, `VAULT_ARCHITECTURE.md` §8 amendment, the §1 overclaim corrections. | Ships **with** the feature, per deliver-then-claim. Not after. |

**Third lens blind at the cap.** If any unit reaches the review cap without convergence, a third
reviewer is dispatched blind per `[[zitrone-review-cli-invocation]]`, and work stops for maintainer
adjudication regardless of that reviewer's verdict.

### The indicator (U6) — exact framing

The 🍋‍🟩 indicator fires when the paired decoy for the most recent real send was successfully handed
to `WsClient`. That is *all* it asserts. Required wording, in-app and in `SECURITY_MODEL.md`:

> This shows that cover traffic was generated for your last message. It is a **mechanism-status
> indicator, not proof of unlinkability** — it tells you the feature ran, not that an adversary was
> defeated. Cover traffic protects against an observer watching your network connection. It does
> **not** hide your conversation partner from the relay operator, who sees sender and recipient on
> every message. If you need to verify the mechanism itself, read the send-pairing code.

The two-audience split is deliberate and is documented as such: average users get honest
reassurance that a feature is working; security-conscious users are pointed at the source. It is not
a dummy light, and the copy earns that by naming what it does not cover.

---

## 6. Dependencies and interactions the maintainer must rule on

1. **Registration PoW × synthetic accounts. — CORRECTED 2026-07-27 by U1; the original text was
   wrong about the client.** It said `regpow` is "not in this tree". That is true only of the
   **relay** (`handlers.go` `Register` still has no PoW check on `main`). On the **client** it
   shipped in 0.9.4-beta: `apps/android/.../crypto/RegistrationPow.kt` is on `main` and wired into
   `MessagingCoordinator.bootstrapLoop()`, with `ApiClient.registrationChallenge()` /
   `register(powProof=)` alongside it. The error came from generalizing a server-only research pass
   to both sides.

   Consequence, and it **answers §6.2a's "decide before U1"**: the synthetic registration mirrors
   the real path — fetch a challenge, treat a 404 as "this relay predates PoW, register proofless",
   otherwise solve — and the solve is **background, with no progress UI and silent failure**. The
   pitcher screen is foreclosed by the hard constraint "never block onboarding, never surface an
   error implying a fault". **Deliberately not `RegistrationPowSolveRecorder`**, which writes
   device-level telemetry and would violate the no-device-storage rule. *(Resolved and built in U1.)*
2. **The register limiter — registration volume is a SHARED GLOBAL RESOURCE, not per-client
   headroom.** `registerLimit` was widened 5/hour → **300/hour** on 2026-07-26 in `20ade12b`
   (maintainer-verified rebuilt, redeployed, and live on CX23; not independently verifiable from
   CX33, which has no SSH to the box). **300 is an interim number, not a fix.** The key is still
   `c.IP()`, which is still Caddy's socket address, so it is still **one global bucket shared by
   every client worldwide** — clearnet behind Caddy and every Tor/I2P client via the sidecars.

   The commit message also closes the question CX23 P2 was gated on: Caddy's `reverse_proxy` has
   **no `header_up` override, so it appends rather than overwrites `X-Forwarded-For`.** Trusting
   that header would let clients spoof their own bucket — strictly worse than the collapse.
   **`ProxyHeader` is therefore confirmed unsafe as-is**, and the real fix (non-IP keying) remains
   open as CX23 P2.

   **Two corrections that were owed when this was written — both now CLOSED (2026-07-27):**
   - ~~`20ade12b` is not merged to main~~ → **merged** (`0370710f`, `go build`/`go vet` clean, pushed).
     `main` now reads `ratelimit.New(300, time.Hour, cfg.RateLimitEnabled)` at `handlers.go:54`, and
     the 8443 publish is bound to `127.0.0.1`. The "a redeploy from main silently reverts it"
     warning no longer applies.
   - ~~`todos.md` still records P2 unchecked at 5/hour~~ → **reconciled** (`1dee76f0`), with the
     pattern recorded in `failures.md` as a binding process fix: *a fix recorded only in commit
     history is not recorded.*

   **Unchanged and still open:** the `c.IP()` keying (`handlers.go:166`), so the bucket is **still
   one global bucket worldwide** and CX23 P2 remains open. All the budget arithmetic below stands.

   **Why this constrains 0.10.0.** Because the bucket is global, decoy provisioning does not spend
   a client's own headroom — it spends everyone's. Budget in §6.2a.
2a. **Registration budget — explicit arithmetic.**

   **Per-device cost.** Onboarding today is **2 registrations**. Decoy traffic adds **one synthetic
   account per vault that has decoys active**, provisioned when that vault first runs a decoy
   session:

   | Configuration | Registrations | On-device PoW cost at D=5 |
   |---|---|---|
   | Today, any config | 2 | ~5.6 s expected |
   | Single vault + decoys | **3** | ~8.4 s expected, ~24 s at the 5% tail |
   | Two vaults, decoys in both | **4** | ~11.2 s expected, spread across two unlock sessions |

   Solve time is geometrically distributed — ~37% of solves exceed the expectation and ~5% exceed
   3× — so the tail figures are the ones the UX must tolerate, not the mean. The synthetic
   provisioning solve must not be presented as a second onboarding wait; U1 decides between reusing
   the existing solver's progress UI or provisioning in the background with a defined failure path.

   **Global cost — the number that actually matters.** At a shared 300/hour bucket, adding one
   registration per onboarding drops worldwide onboarding capacity from **150 devices/hour to 100
   devices/hour, a 33% reduction**, before counting second vaults. Decoy provisioning must therefore
   be treated as spending a scarce shared resource:
   - **Provision lazily**, on the first session that actually sends a decoy — never eagerly at vault
     creation. A vault that never sends never spends a registration.
   - **Back off and retry across sessions on 429**, never in a tight loop. A 429 is contention with
     other users worldwide, not a client fault. **(U1 R1: "across sessions" is a durability claim —
     the deferral must be FLUSHED, not merely mutated, or a crash inside the coalescing window loses
     it and the next unlock walks straight back into the bucket. See §2.3's correction.)**
   - ~~**Back off the same way when the vault cannot STORE the account [U1 R1].**~~
     **SUPERSEDED — WRITE THE BACK-OFF FIRST [U1 R2].** Writing the deferral *in response to* a
     failure leaves an edge with no answer: a vault so full that even `previous + deferral` will not
     encode bare-reverts with **nothing on disk saying it tried**, which is one registration per
     unlock — precisely the defect the R1 rule was added to close, surviving on the boundary.
     Inverting the order removes the edge instead of patching it: **`provisionNotBeforeMs` is
     written and flushed BEFORE any relay contact.** If the smallest decoy write the client can make
     does not fit, no registration is spent at all.
   - **RETIREMENT — SUPERSEDES THE ABOVE ON ITS SECOND HALF [U1 R3].** R2 ruled that "only a
     successful commit retires it", so *every* failure deferred and a purely local failure cost a
     60–90 minute wait. **That is no longer the rule and must not be restored.** It was wrong in a
     way R2 could not see from here: the deferral is the *whole content* of `TAG_DECOY` on a failed
     first attempt, so an offline challenge fetch did not merely cost 60–90 minutes of a background
     nicety — it cost that vault its 0.9.x downgrade path (§4.1), permanently, for an attempt that
     protected nothing. The rule now turns on **what was spent, not on whether it succeeded:**
      - **A failure BEFORE `register` is entered retires the deferral** — offline challenge fetch,
        DNS failure, failed proof-of-work, a local crypto fault, a cancelled scope. None of them can
        have touched the shared bucket, the emptied holder is omitted by the codec, and the next
        session gets its attempt immediately.
      - **A failure from `register` onwards keeps it**, whatever the cause — a 429, a crash between
        register and commit, a dead session mint, a capacity failure at commit. A `register` that
        threw may still have created the account, and "may have spent" counts as spent.
      - The discriminator is a flag set **between** bundle generation and the `register` call, and
        it must stay there: **[U1 R4]** it sat one line earlier, above an inlined
        `generateBundle(...)` argument that Kotlin evaluates after it, so a purely local failure was
        being charged as a possible spend.
     The failed commit must
     still be reverted so a cover-traffic write never leaves the vault unable to flush-before-ack a
     real inbound message — and the revert may only restore state read under the **same lock** the
     revert runs under (see the section-lock note in the U1 invariant table), or it clobbers
     whatever the section gained during the seconds of network I/O, up to and including a counter
     high-water mark.
   - **A failed or deferred provision must degrade silently to "decoys off"** — never block
     onboarding, never surface an error that implies a fault, and never let the 🍋‍🟩 indicator claim
     the mechanism fired when it did not.

   **Sequencing recommendation:** the interim 300 absorbs 0.10.0's added load at current beta
   volumes, so this does not block the spec. But non-IP registration keying (CX23 P2) should land
   before any announcement that grows onboarding volume, since decoys make the shared bucket
   saturate 33% sooner.

3. **Send rate limit.** `sendLimit` is 100/min per account (`main.go:51`, `hub.go:159`). Pairing
   doubles outbound volume; a human sender will not approach it. Noted, no action.
4. **Two concurrent WS connections from one device.** Permitted — the one-connection-per-account
   rule (`hub.go:55-63`) is per account id. The correlation cost is real and is covered by the §1
   threat model: the relay can already identify the synthetic account regardless.
5. **Web `DecoyScheduler` reconciliation.** `packages/relay-client/src/decoy.ts` implements the
   standing-Poisson-cadence model that §8 deliberately rejected, wired only in the undeployed web
   client. Recommendation: leave the code, add a doc note that it is not the 0.10.0 design and is
   known-distinguishable. Do not extend it.
6. **`ConnectionMode.kt` dead fields.** `decoyTraffic`, `decoyIntensity`, `cadenceSeconds()` exist
   on Android with **zero consumers**. The paired design has no intensity knob. Recommendation:
   reduce to a single on/off in U3 and delete the cadence machinery rather than wire a concept the
   design rejected.
7. **Storage-format stability gate** — see §4. Must be answered, not deferred.

---

## 7. Out of scope for 0.10.0 — stated so it is not mistaken for coverage

- Cover for the plaintext control-frame channel (typing, ack, burn, received). §2.4.
- Any defense against a hostile relay. Requires sealed sender or onion routing for ordinary sends;
  both are unbuilt config-only today. §1.
- A true 24/7 background idle ping. Requires background infrastructure the app has never had. §3.2.
- iOS, desktop, web. Android only, per-active-vault.

## 8. Still open from 0.9.4, tracked, not blocking

- Onion mirror serves a stale APK while the website advertises v0.9.4's checksum; needs CX23
  access. Must clear before the project is announced.
- 0.9.4 shipped without its independent branch review — a deliberate recorded call; the review is
  still owed.
