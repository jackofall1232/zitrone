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
reseal-rate reasoning intact; ~~counter reservation at 64~~; ~~the in-session dead-air reframe with
`VAULT_ARCHITECTURE.md` §8 **amended** rather than quietly diverging; a single-block unpaired ping
(§2.1's first row)~~ **— the ping was CUT outright on 2026-07-27 (§3.0), taking the counter
reservation with it**; the control-channel gap declared as a known residual.

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
> **§3.3 inherited this** and said 821 B in three more places until [U2 R1, G-D]; it now names no
> byte count at all and points here. The design is unaffected — match the mode, one block — but U5
> and `SECURITY_MODEL.md` must not carry the old number. Full measurement record:
> `l00prite/.l00prite/reviews/decoy-0.10.0/u2-invariant-table-decision.md`.

Padding does **not** by itself produce uniformity. Three residual size/structure tells exist
independently of decoys: block count is visible; the attachment control payload is 286 B so it
*always* lands one block bigger than a short text; and the X3DH first message is larger by the
first-message row of the table above, with **`ephemeral_key` flipping non-null**. **[R11]** ~~with two
fields flipping non-null~~ — `prekey_id` may stay **null** on a real first message
(signed-prekey-only X3DH, when the peer's one-time prekeys are exhausted), so "two fields" is the
same false pair model struck in §2.2. The size claim holds; the field count did not.

> **⭐ CANONICAL: every frame size in this document is the table above. No other section states
> one.** [U2 R1, G-D] Frame sizes were corrected in the table and then left standing in their old
> form in four other sections — the eighth recurrence of the paraphrase class on this document. The
> fix is structural rather than another sweep: §2.2, §2.4, §3.3 and §5 now *point here* instead of
> restating, exactly as `VaultStateCodec`'s kdoc is the single canonical statement of the `TAG_DECOY`
> land-on-disk trigger. A number that appears in only one place cannot drift out of agreement with
> itself. **If you are about to write a byte count for a `message.send` frame anywhere else in this
> file, don't — link to this table.**

### 2.2 Resolution — size mirroring, and structure by instantiation

**Structure: the decoy is indistinguishable from a real envelope in every field the relay can read.**

*(Amended 2026-07-27 after U1. This paragraph previously said the decoy "is a real envelope … over a
session that was genuinely established with one X3DH first message", which read as requiring a real
`SessionBuilder.process`. It does not — see §2.3, which governs. The requirement is on the
**observable**, not on the machinery behind it.)*

It is addressed to a genuinely registered account, and every cleartext field is populated the way
the real send path populates it, ~~with monotonically advancing counters~~ **(amended twice: the
counter is MIRRORED from the covered envelope — §2.3's R1 ruling — and the monotonic allocator that
would have advanced one was deleted at R2, §3.0)**. There is no field whose
value is a constant that a real message's value varies over — which is precisely the defect in the
existing web generator.

**The X3DH first-message observable, and how to satisfy it.** ~~A real conversation's first envelope
carries non-null `ephemeral_key` and `prekey_id`; every later one has them null.~~ ~~The synthetic
conversation must show the same shape: emit well-formed-looking values exactly once at setup, null
thereafter.~~

**[R10] Both sentences above are struck. The rule is: MIRROR THE COVERED ENVELOPE — do not construct
a shape from a description.** A real first envelope carries `ephemeral_key` non-null and `prekey_id`
**either set or null** (null is signed-prekey-only X3DH, when the peer's one-time prekeys are
exhausted). "Emit both, once" was the false model that produced G2-A. **`DecoyEnvelopeBuilder` is
canonical for construction; this section describes intent only and binds nothing.**

> **⚠️ CORRECTION [U2 R3, 2026-07-27] — A FIRST ENVELOPE MAY CARRY `prekey_id` NULL, AND THE
> SENTENCE ABOVE IS THE ORIGIN OF A P2.** The two fields are not a pair. `ephemeral_key` marks an
> X3DH first message; `prekey_id` names the **one-time** prekey it consumed, and a peer whose
> one-time batch is exhausted serves a bundle without one. The sender then does signed-prekey-only
> X3DH: still `PREKEY_TYPE`, still a base key, `pre_key_id` simply absent from the protobuf. The
> whole path is in production — `ApiClient.fetchPreKeyBundle` returns a null `one_time_prekey`,
> `SignalProtocolManager.establishSession` passes libsignal's `-1` sentinel with a null key,
> `EncryptResult.preKeyId` comes back null, and `packages/crypto/src/x3dh.ts:35-36` documents
> "null if no OPK was available".
>
> **The correct rule is an implication, not a biconditional: `prekey_id` present ⇒ `ephemeral_key`
> present.** U2 shipped the biconditional as a `require`, which refused an ordinary send and — once
> U3 wires the pairing — would have left a **real frame with no cover at all**, the exact observable
> this feature exists to remove, for a whole class of RECIPIENTS rather than at random. Measured
> cost of the absent field: a no-OPK first ciphertext is **402 B where the OPK-present one is 404 B**
> (tag + varint), absorbed by the random body like any other unmirrorable width; the cleartext
> `prekey_id` is null on both sides, so the JSON side matches too.
>
> **Both variants are now in U2's gate cross-product**, built from genuine no-OPK sessions rather
> than from a `copy(preKeyId = null)` of an OPK-present fixture — an internally inconsistent fixture
> (cleartext null, ciphertext still carrying field 1) could not tell "reject garbage" from "reject a
> production shape", and it was the latter.

> **⚠️ [R7] THREE CORRECTIONS, from source research done before U2 started. The first would have
> shipped a fingerprint.**
>
> 1. **`ephemeral_key` is 33 bytes, NOT 32.** This spec said "a random 32-byte value (base64)".
>    Wrong. The real field is `ECPublicKey.serialize()` — a **`0x05` type tag + 32-byte Curve25519
>    point**, `KEY_SIZE = 33` confirmed in libsignal 0.46.0 bytecode. The tell is in the encoding:
>    **33 bytes base64 to exactly 44 characters with NO padding, while 32 bytes produce 44
>    characters ending in `=`.** A decoy built to this spec's original wording would have carried a
>    trailing `=` that no real first message ever has — a perfect one-field discriminator, in the
>    exact field added to defeat discrimination. ~~**U2 must emit `0x05 ‖ random(32)`.**~~
>    **[R10] STRUCK — that instruction was itself defective and shipped a P1.** `0x05 ‖ random(32)`
>    is **not a valid Curve25519 encoding**: genuine public keys have bit 255 clear and random bytes
>    set it ~50% of the time (measured: 0 of 200 real keys). **The rule is
>    `Curve.generateKeyPair().publicKey.serialize()`, private half discarded** — canonical by
>    construction. See `DecoyEnvelopeBuilder.coverPublicKey()`, which is canonical.
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

**Size: the paired decoy mirrors THE REAL ENVELOPE, not its block count.**

This is the whole resolution and it is worth stating plainly: do **not** randomize decoy size, and
do **not** always send a single block. Mirror. Whatever row of §2.1's table the real send lands on,
the decoy lands on the same one. The observer then sees two identical-size frames a few
milliseconds apart in an order they cannot predict, and has no size-based way to say which was
real. Randomizing instead would create pairs where an attachment-shaped frame is immediately
identifiable as the real one whenever the user's actual message was short.

> **⚠️ [U2 R1, RULING — G-A + G-C] "Mirrors the block count" was not enough, and the interface said
> so.** The frame depends on the block count *and* on the message's shape (X3DH first vs ordinary —
> two different rows of §2.1's table, 147 B apart) *and* on the decimal width of `message_number`
> (`5` and `128` are two bytes apart in the JSON) *and* on the rendered width of `timestamp` and
> `ttl_seconds`. A builder handed only a block count cannot produce a matching frame, and U3 cannot
> repair it downstream because the information never reached the call.
>
> **The binding form of the requirement is therefore:** the builder takes **the real envelope it is
> covering** and mirrors every size-affecting property of it, and it **measures both frames and
> refuses to return a decoy whose frame is not exactly the same length**. "Two identical-size
> frames" is now a checked postcondition rather than a promise made in prose. See
> `DecoyEnvelopeBuilder.build` and the cross-product gate test.
>
> The two properties this costs are declared in §2.4: the decoy's counter mirrors the covered one
> rather than advancing monotonically, and the random body absorbs blob-internal differences and so
> is not always a padded-block multiple. Both are relay-visible only.

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

~~**What must still be durable is the counter**~~ **— FULLY RETIRED 2026-07-27, see the two callouts
below.** ~~because a `message_number` that resets or regresses
is a tell a real ratchet can never produce. Handled by **reservation**: reserve a block of 64
counter values, make the new high-water mark durable, then spend the block from RAM and reserve
again when it is exhausted. A crash therefore *skips* counter values (invisible — a real ratchet
skips too, on any dropped message) but can never *regress* them. One durable write per 64 decoys
instead of one per decoy.~~

> **⚠️ [U2 R1 — SUPERSEDED FOR THE PAIRED PATH; the mechanism is intact and moves to U5.]** The
> paragraph above is the reason the allocator exists, and its premise does not survive contact with
> §2.2's frame-matching requirement or with §2.4's own text.
>
> *The premise is false as written.* "A `message_number` that resets is a tell a real ratchet can
> never produce" — but §2.4 below already concedes the opposite: **a real client resets
> `message_number` to 0 on every inbound ratchet turn**, and the monotonic counter that never resets
> was itself declared there as the residual. Resetting is what real traffic does; climbing forever is
> what does not.
>
> *And it is arithmetically incompatible with §2.2.* `message_number` is a JSON number, so its
> DECIMAL width is part of the frame. A base64 field's length is always a multiple of four, on both
> sides, so the `ciphertext` field cannot absorb a difference of one, two or three bytes in any other
> field — it can only move the frame in steps of four. The only byte-granular knob in the envelope is
> the decimal width of a numeric field, and a monotonic counter cannot be steered to an arbitrary
> real counter's width: it can be skipped forward, never back, while real counters reset. **So
> "monotonic decoy counter" and "the two frames are the same size" cannot both hold.**
>
> **Ruling applied (U2 R1): the paired decoy's `message_number` MIRRORS the covered envelope's.** The
> observable wins over the unobservable, which is the same rule §2.4 applies to the ciphertext body.
> The cost is in §2.4. ~~The allocator itself is unchanged and still correct; its consumer is now U5's
> dead-air ping, the one decoy with no envelope to mirror (§3.3).~~
>
> **[U2 R2, 2026-07-27] AND THEN THE ALLOCATOR WENT TOO.** The ping was cut (§3.0), which was its
> last candidate consumer, so `DecoyCounterReservation` and `TAG_DECOY.counterHighWater` are
> **deleted**. Nothing in the decoy path allocates a counter: the builder reads one off the envelope
> it covers, and that is the whole mechanism. The paragraph above the callout — "what must still be
> durable is the counter" — is therefore **fully retired**, premise and mechanism both. This finding
> is what made the ping decidable: with the paired path mirroring, the ping was the allocator's only
> consumer, and a mechanism that exists for one consumer is a fair thing to weigh against that
> consumer's own merits.

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
> That covered the counter reservation (whose RAM cursor advanced only after the flush returned;
> **the allocator is deleted as of 2026-07-27, §3.0** — the rule is unchanged, it simply has one
> fewer subject), the credential commit (which reports readiness, and had spent a scarce global
> registration), and both back-offs (§6.2a's "back off across sessions" is a durability claim). It does NOT cover the
> session tokens, which stay coalesced because they are re-mintable from the stored identity key —
> the same exception `VaultAuthStore` makes.
>
> §4's R4 reader row and the U1 WRITER/READER table inherited the same error and are corrected in
> `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md`. **U2–U6 inherit the corrected
> rule, not this paragraph's original wording.**

### 2.4 The uncovered channel — declared, not silently ignored

`typing.start/stop` (72 B), `message.ack` (74 B), `message.burn` (124 B), `message.received`
(128 B) are plaintext control frames carrying `peer_id`/`message_id` in the clear. They are
trivially separable from any `message.send` (an order of magnitude larger — §2.1's table) by size
alone, and **this scheme generates no cover for them.** A real conversation also produces inbound receipt traffic from the peer that a
decoy exchange does not naturally produce.

> **⚠️ [U2, WITHDRAWN AT R1 — the monotonic-counter residual, and what replaced it.]** This entry
> used to declare that a monotonic decoy counter never resets while a real client resets
> `message_number` to 0 on **every inbound ratchet turn**, so U4 would let a relay see a counter
> climbing through replies that should have reset it. **The paired decoy no longer has a counter of
> its own** — it mirrors the covered envelope's, per the R1 ruling recorded in §2.3 — so that
> particular residual is gone, and the frames match instead. What the mirror costs is below.
>
> *(The protobuf's own `previous_counter` was measured, not reasoned about: libsignal writes the last
> COUNTER of the previous chain rather than its length, so a client whose one-message first chain was
> answered emits 0 for its whole next chain — which is what a cover blob emits.)*

> **⚠️ [U2 R1] THE THREE RESIDUALS THE FRAME-MATCHING REQUIREMENT BUYS. All relay-visible only, and
> all bought with the same coin: a network observer sees the total frame length and NOTHING of the
> internal split, so a property the relay alone can check is worth less than a byte on the wire.**
> §1 concedes the relay in full, for reasons far more fundamental than any of these (cleartext
> `sender_id` and `recipient_id` on every envelope). They are written down because "we did not think
> of it" and "we priced it and paid it" must not look the same in six months.
>
> 1. **The random body is not always a padded-block multiple.** A real ciphertext body is exactly
>    `blocks · 256 + 16` bytes. A cover blob is built to the covered ciphertext's exact byte length,
>    and two fields inside it cannot be mirrored: `signed_pre_key_id` (a cover message must name the
>    synthetic account's own, not the real peer's) and `previous_counter` (mirroring it would mean
>    parsing the real ciphertext, which the builder deliberately never does). Both are varints, so
>    the cover body absorbs a one-to-three-byte difference. **A relay that parses the blob could see
>    a body length that is not a block multiple, and could call it implausible for the counter it
>    carries.** In the ordinary case — an established-session message with a previous chain shorter
>    than 128 — there is nothing to absorb and the body is exact.
>
> 2. **The synthetic conversation's `message_number` repeats.** Mirroring the covered counter means
>    the synthetic conversation reproduces the covered conversation's counter sequence, resets and
>    all. Each envelope is individually well-formed and internally consistent — which the discarded
>    alternative (letting the cleartext counter disagree with the counter inside the blob) would not
>    have been, at one parse of one envelope. What a relay tracking the synthetic conversation over
>    time can see is a counter that resets without an inbound ratchet turn to justify it. U4's
>    send-backs make that *less* visible, not more.
>
> 3. **`prekey_id` may name an id the synthetic account never published.** §2.2 binds the field to
>    the account's own uploaded batch (`1..100`). The covered id is used verbatim when it is in that
>    batch, and otherwise the widest in-batch id of the same DECIMAL width is used — because the
>    field's decimal width is part of the frame and, per §2.3's arithmetic, nothing else can absorb a
>    difference in it. A covered id of four or more digits (a long-lived peer's allocator) has no
>    in-batch counterpart at all and is mirrored verbatim. The relay could see that this account
>    never published that id — and can already see that it never *consumed* the one it does name,
>    which `DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID` has declared since U1.
>
> 4. **[U2 R3] A cover of a no-OPK first message claims a one-time batch that was never exhausted.**
>    When the covered envelope carries `ephemeral_key` set and `prekey_id` null, the cover mirrors
>    that shape — asserting, to anyone parsing it, that the sender found no one-time prekey left on
>    the synthetic account. That account uploads a full batch of 100 and has none of them consumed,
>    because nothing ever fetches its bundle. Same family as residual 3 and bounded the same way:
>    **relay-visible only**, and the relay already knows this account's bundle was never served.
>    Not mirroring the shape is strictly worse — it costs the covered send its cover entirely.

> **⚠️ [U3 RULING 2026-07-27] THE FRAME ORDER IS FIXED AND PUBLIC — the real frame is always first.**
> Placed here because the R-U3-2 ruling says it belongs here and the ruling commit did not carry it
> across. Random ordering bought exactly one thing: against an observer watching **both ends** of the
> network, 5–50 ms of ambiguity about which half of a pair was the real send. That is now conceded.
> It is the cheapest residual in this section — a one-sided observer sees two equal-length opaque
> frames either way, and the two-sided observer it did defend against is, in every realistic case,
> the relay, which reads `sender_id` and `recipient_id` in cleartext on both envelopes and has never
> needed the order. It was traded for making all four R-U3-1 violations *structurally* impossible
> rather than *checked* for; see the ruling in §4.3 for why no decoy-first implementation exists.
>
> **Second-order consequence, so it is not discovered later:** because the order is fixed, pairs from
> concurrent sends may interleave on the wire (nothing serialises them any more). That reveals
> nothing — the halves are associable by length regardless, and which one is real is now public.

Partial mitigation is in scope (§5, U4): the synthetic side acks and burns, and occasionally sends
back, so a decoy exchange produces control frames of its own rather than being a conspicuously
one-directional flow. Full coverage of the control channel is **explicitly out of scope for
0.10.0** and must be listed as a known residual in `SECURITY_MODEL.md`. Per the standing rule about
silently-capped coverage: this gap is written down, not left to be discovered.

---

## 3. OPEN QUESTION 2 — idle-ping sizing. **MOOT — THE PING IS CUT.**

### 3.0 ⛔ CUT 2026-07-27 (maintainer decision). Everything below §3.0 is HISTORICAL.

**The idle ping is removed from the design, not deferred.** `VAULT_ARCHITECTURE.md` §8 is amended
visibly to match; this is the second amendment to that locked design.

**The reasoning is §8's own argument turned on itself.** Pairing was chosen over scheduling because
decoys *"inherit real human timing for free rather than modeling a pattern that could itself
fingerprint."* A standalone ping has **no real traffic to inherit timing from**, so it must invent a
schedule — precisely the modelled pattern that reasoning rejects. An adversary can recognise it and
filter it, after which it contributes nothing while still costing infrastructure; and being
recognisable, it advertises that the client runs cover traffic at all.

So the open question below — *how do you size a decoy that has no cover to mirror?* — has no good
answer, and that is the finding. §8 already conceded the ping "carries little unlinkability burden".
**The honest resolution is that no sizing is right, because the defect is the schedule, not the size.**

**Dead-air periods are therefore not covered.** That is an accepted, documented limit — see §2.4 —
not a gap to be filled with something ineffective. Paired decoys remain the entire mechanism, and
they beat any algorithm modelling real message behaviour because they *are* real message behaviour,
borrowed.

**Consequences:** U5 is cut. `DecoyCounterReservation` (U1) has no consumer — paired decoys mirror
the covered envelope's `message_number` — and is removed along with `TAG_DECOY.deadAirNextFireAtMs`
and writer W4. §2.3's counter-reservation rationale is fully retired.

**Do not confuse this with the earlier ruling on the 24/7 daemon**, which was rejected on different
grounds (no background execution; a locked vault holds no keys). That narrowed the ping to
in-session. **This removes it.**

---

### (HISTORICAL, superseded by §3.0) OPEN QUESTION 2 — idle-ping sizing

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
**Always emit a single 256-byte block — the first row of §2.1's table.**

The reasoning is that we cannot sample the real distribution even if we wanted to: message content
is **RAM-only and never persisted** (`MessagingCoordinator.kt:2343`; `MessageRepository` has no
persistence layer), so there is no history to draw from, and a guessed distribution that is wrong
is itself a fingerprint. The single-block frame is the modal real frame by a wide margin — every
short text and every batched read receipt is one. An observer seeing frames of that size during a
quiet period sees exactly what "the user sent a short message" looks like. Matching the mode exactly
beats inventing a spread.

> **⚠️ [U2 R1, G-D] This paragraph and the callout at §2.1 both used to state 821 B.** The number
> was wrong (829 B) and, more importantly, restating it here is what let it rot. U5 takes its size
> from §2.1's table, and states no byte count of its own.
>
> ~~**U5 also inherits the counter allocator.**~~ **[2026-07-27] BOTH ARE CUT.** `DecoyCounterReservation`
> (U1) had no consumer on the paired path — a paired decoy mirrors the covered envelope's
> `message_number`, per §2.4 — and the dead-air ping was its only remaining candidate. The ping is
> cut (§3.0), so the allocator was **deleted** rather than kept for a unit that no longer exists.

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
**account id + identity keypair + session tokens**, ~~the **counter reservation high-water mark**,
the **dead-air schedule next-fire**,~~ **(both REMOVED 2026-07-27 with the ping — see §3.0)** and —
*added by U1* — a **durable provisioning back-off deadline**
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
| W1 | `DecoyAccountProvisioner.provision()` | First unlocked session in which decoys are enabled and no synthetic account exists | Account id, identity keypair, initial tokens, and `provisionNotBeforeMs = null` — ~~a success is the only thing that retires the back-off~~ **[U1 R3/R4] success is not the only retirement path; see W1d.** It is the only one that retires the deferral *while writing something*, which is why it rides in this same mutate: there is no window where the credentials are durable and the deferral is not. ~~**The counter reservation is NOT written here** — `counterHighWater` stays 0 until `DecoyCounterReservation.next()` first reserves a block (W3). **Dead-air next-fire is written `null`.**~~ **[2026-07-27] Neither field exists any more (§3.0); this writer's field set is account id, identity keypair, tokens, and the deferral retirement.** | **DONE (U1)** |
| W1b | `DecoyAccountProvisioner.reserveBackoff()` — the **write-ahead back-off** | **Before any relay contact**, on every attempt that gets past the deferral check | `provisionNotBeforeMs` only — the cross-session back-off deadline, `mutate` + `flushBeforeAck`. If it cannot be written, **no registration is spent at all** | **DONE (U1 R2)** |
| W1d | `DecoyAccountProvisioner.clearBackoff()` — the **retirement of a deferral that protected nothing** | An attempt that fails **before** `register` is entered: offline challenge fetch, DNS failure, failed proof-of-work, a local crypto fault, a cancelled scope | `provisionNotBeforeMs` → null, `mutate` + `flushBeforeAck`, **compare-and-clear**: only the deadline *this* attempt wrote is retired, checked under the section lock, so a deferral another writer put there meanwhile is left alone. Emptying the holder is what removes `TAG_DECOY` entirely and restores 0.9.x readability | **DONE (U1 R3)** — **added to this table U1 R4; it was a real durable writer the inventory omitted** |
| W2 | `DecoyAccountProvisioner.refreshTokens()` | Synthetic session token refresh (7-day refresh-token TTL, `auth/jwt.go:26`) | Tokens only; all other fields untouched | **this unit (U1)** |
| ~~W3~~ | ~~`DecoyCounterReservation`~~ | **REMOVED — the ping is cut (§3.0), and paired decoys mirror the covered envelope's `message_number`, so nothing allocates a counter.** `counterHighWater` has no writer and is deleted from `TAG_DECOY`; the class and its test are deleted. **The `DecoySectionLock` this writer forced into existence SURVIVES** — W1/W1b/W1d and W2 are read-modify-write sequences in their own right. | — | ~~DONE (U1)~~ **DELETED (U2 R2, 2026-07-27)** |
| ~~W4~~ | ~~`DeadAirPinger.rearm()`~~ | **REMOVED — the ping is cut (§3.0).** `deadAirNextFireAtMs` has no writer and is deleted from `TAG_DECOY`. | — | — |
| W5 | `VaultRuntime.mutate` (existing) | Every write above, without exception | Re-encodes whole `VaultState` under `stateLock` and **SCHEDULES** a reseal — **it is not durable**, see §2.3's correction | existing |
| W6 | `VaultRuntime.flushBeforeAck` (existing) | W1, ~~W3,~~ and **all three** back-off writes — W1b, W1d, and W1's retirement — every value that must survive process death | Forces the scheduled payload to disk synchronously. **A throw means the value was never issued / never recorded** | existing — **added 2026-07-27 (U1 R1)**; W1d added R4 |

### READERS, and what each assumes `TAG_DECOY` MEANS

| # | Reader | Assumes `TAG_DECOY` means | Still true after W1–W4? |
|---|---|---|---|
| R1 | `VaultStateCodec.decode` | "a section tag I recognize; an unrecognized tag is corruption" | **NO for old builds — see hazard below.** YES for builds carrying the tag. |
| ~~R2~~ | ~~`DecoySender.send()`~~ | ~~"a provisioned synthetic account exists and these counters have never been issued before"~~ | **RETIRED 2026-07-27.** There is no counter to have issued before: `DecoyEnvelopeBuilder` reads `message_number` off the envelope it covers. What remains of this reader — "a provisioned synthetic account exists" — is R4's `canSend()`. |
| ~~R3~~ | ~~`DeadAirPinger`~~ | ~~"next-fire is in this vault's own timeline, not the device's"~~ | **RETIRED 2026-07-27 — the ping is cut (§3.0) and `deadAirNextFireAtMs` is deleted.** |
| R4 | provisioning entry point (`DecoyAccountProvisioner.provisionIfNeeded`) | ~~"absent section = decoys not yet provisioned; present = ready"~~ ~~"ready = credential pair present"~~ ~~"ready = credential pair present **and** `capacityExceeded` clear"~~ **CORRECTED A THIRD TIME (U1 review round 2) — there is no single "ready". TWO predicates:** `hasAccount()` = the credential pair is present, **and nothing else**, which gates REGISTRATION; `canSend()` = `hasAccount()` **and** this session's credential flush confirmed **and** `VaultRuntime.capacityExceeded` clear, which gates COVER TRAFFIC. | **NO as originally written. Three independent falsifiers.** (i) A back-off creates a section that is PRESENT and NOT ready. (ii) An over-capacity `mutate` **RETAINS** a complete credential pair in the LIVE state that was never scheduled and that `flushBeforeAck` refuses. (iii) **The corrected single predicate was itself wrong** — see below. Absence is still the valid initial state; presence never means ready. |
| R5 | Capacity guard `VaultRuntime.capacityExceeded` | "encoded state fits `MAX_PAYLOAD_CONTENT_BYTES`" | YES — **re-measured U2 R2 after the two fields were removed:** raw worst-case section body **717 B → 700 B** (deterministic, asserted exactly). The *encoded* delta is **not** a single number — it is measured after DEFLATE over a freshly generated identity keypair and spans **636–646 B** run to run, before and after the change alike, because the removed fields were the section's most compressible bytes. `DECOY_SECTION_BUDGET_BYTES` stays **1024 B** as a bound. |
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

## 4.3 U3 — pairing. Stated as REQUIREMENTS, deliberately, and not as instructions

**Written this way on purpose.** Every P1 in U1 and U2 traced to this spec telling the implementer
*how to build* something — `mutate` treated as durable, `build(blockCount)`, `0x05 ‖ random(32)`.
Each was a construction instruction the spec had no business giving, and each was wrong in a way only
the code could discover. **So this section states what must be observably true and names nothing about
mechanism.** Where a construction detail matters, the canonical artefact owns it
(`DecoyEnvelopeBuilder`), and where a choice is genuinely open, it is named as open rather than
guessed at.

> ## ⚖️ REQUIREMENTS REWRITTEN 2026-07-28 — the word "absolute" was a category error
>
> **Maintainer ruling: there are no absolutes in security. Security is layers.** That is the
> project's own model — the lemon: zest, pith, flesh, pulp, and only then the juice. A requirement
> that demands perfection from one layer misdescribes how the whole thing is meant to work.
>
> **What went wrong.** R-U3-1 and R-U3-3 were written as guarantees about **outcomes** — *"a real
> send is never made less durable"*, *"failure must be uniform, never intermittent"*. Outcomes depend
> on the network, and the network drops packets. Seven review rounds and four independent lenses then
> correctly found reachable counterexamples, because reachable counterexamples to an outcome
> guarantee always exist. Three of four concluded the feature was unshippable. **They were reasoning
> correctly from a requirement that should never have been written that way.**
>
> **The fix is to state rules about OUR OWN BEHAVIOUR, which we can hold absolutely**, and to state
> outcomes as what they are — best-effort, layered, and honestly bounded.
>
> ### R-U3-1 (rewritten) — COVER TRAFFIC IS SUBORDINATE. This rule is absolute; the outcome is not.
>
> **No cover-specific work may precede a real frame's transport handoff, and cover traffic yields on
> every signal of contention available to it, and spends nothing after one.** Cover is the
> discardable half of the pair by construction.
>
> **[R8 CORRECTION — the previous wording was still unsatisfiable, and would have cost another
> round.]** It read *"cover must never compete with a real send for any resource"* and called that
> absolute. **Read literally it is false: emitting a cover frame IS competing for resources — that is
> what a cover frame is.** Every cover frame is charged to the same account budget and the same
> socket. A reviewer applying the literal text would produce the onset-of-burst frames and the
> confined worker's occupancy during a build as counterexamples and rate them P1 — **exactly as
> rounds 1–7 did against the earlier "absolute outcome" wording.** The same failure mode in miniature,
> found by the implementer before round 8 was dispatched.
>
> The rescuing clause was a conditional (*"where a resource is contended"*) whose key term was defined
> only in a follow-up ruling. The wording above binds them: **yield on every available signal, spend
> nothing after one.** That is genuinely absolute, genuinely about our own code, and is what the
> implementation actually holds.
>
> **Named residuals where cover still consumes a resource, which this wording admits rather than
> denies:** ~20 cover frames at the *onset* of a burst before the meter trips (closing it would
> require predicting a limit the relay never states); the confined worker's occupancy for the
> duration of a cover build (the build is on that worker precisely to keep admission atomic against
> teardown — moving it reinstates the rounds 4–5 P1s); and the 5–50 ms between the pressure check and
> the emit.
>
> *This is a rule about our code and it holds without exception.* It does **not** promise that a real
> send always succeeds: the network can fail with or without cover traffic. It promises that **cover
> traffic is never the cause.**
>
> ### R-U3-3 (rewritten) — PAIRING IS BEST-EFFORT, AND THE BOUND IS CORRELATION, NOT RATE
>
> **A missing cover frame is acceptable. A *patterned* missing cover frame is not.** Cover must not
> fail in ways that correlate with user or client events an observer can name — vault lock,
> backgrounding, a transport change, teardown. Uncorrelated failures (a socket dying mid-gap) are an
> accepted residual.
>
> **Why rate is the wrong axis.** The earlier rationale — *"intermittent cover is worse than no
> cover"* — is false as stated. An unpaired send costs exactly this: for that one message, the
> adversary's candidate set is 1 instead of 2. It reveals no content, no identity, no contact, and
> nothing about vault existence — those are held by layers that never depended on cover traffic. Only
> a **correlated** gap leaks something new, because the pattern names the event. Persistent inability
> to cover must therefore turn cover **uniformly off** rather than stutter.
>
> **Lone decoys and pairs split across a TLS boundary by application-controlled transport changes
> remain prohibited** — those are patterned by construction.
>
> ### The correlation bound, stated precisely: DISCLOSURE vs DEGRADATION
>
> **Cover must not fail in ways that reveal events an observer cannot ALREADY observe.** That is the
> test, and it is narrower than "must not correlate with anything" — which would have forbidden the
> load-shedding R-U3-1 now requires.
>
> - **Load-shedding is DEGRADATION, and is fine.** Dropping cover under pressure correlates with
>   heavy sending — but a burst of frames is *already* visible to anyone watching the connection.
>   The observer learns nothing new; the candidate set is simply 1 instead of 2 while the user is
>   busy. Protection thins exactly when the pipe is full, which is the right trade.
> - **Lock- / teardown- / transport-correlated failure is DISCLOSURE, and is not.** Those name a
>   client lifecycle event the observer could **not** otherwise see. That is what rounds 3–5 closed,
>   and it is why they were worth closing.
>
> ### Drop tolerance: BE GENEROUS (maintainer ruling)
>
> **Err toward dropping cover.** Do not compute exact remaining capacity or try to spend the last
> safe slot — drop on any signal of pressure (queue depth above a low watermark, a recent send
> failure, a recent `rate_limited`, a high recent send rate), and stay off for a window rather than
> stutter. A conservative threshold is simpler, more robust, and costs almost nothing given what this
> layer is worth.
>
> The maintainer's framing, which is the value model this whole section rests on: **it is more
> important that the user's message actually arrives than that it temporarily keeps a light layer of
> security. The decoy is not a pillar — it is a head fake.**
>
> ### What decoy traffic actually buys, stated plainly
>
> It does **not** hide that a message was sent; the TLS frame already shows that. It makes an
> observer's candidate set **2 instead of 1**, so they must intercept and attempt both to learn which
> carried the payload — compounding with I2P or Tor, which make interception itself expensive.
> **It is the skin, not the core.** Signal Protocol holds the content; the vault holds deniability;
> the transports hold anonymity. An occasional missing decoy is an umbrella turning inside out while
> the raincoat stays on.
>
> **Owed to U6 (user-facing):** *"There will be times when cover traffic does not fire — a dropped
> connection, a device under load. When that happens your message is still end-to-end encrypted and
> still carried over your chosen anonymous transport; what you lose is one layer of ambiguity, not
> your protection."*

### (superseded 2026-07-28) R-U3-1 — A real send is never harmed by cover traffic. **Absolute.**
No real send may be blocked, failed, materially delayed, reordered, or made less durable because
cover traffic was attempted or could not be produced. The `flushSendRatchet` durability barrier and
its ordering relative to `ws.sendMessage` must be unchanged. **If satisfying any other requirement
here would violate this one, this one wins and the other is abandoned for that send.**

> **⚖️ ROUND-3 CLARIFICATION (2026-07-27), from a third-lens ruling on a severity dispute — binding.**
>
> **"Materially" modifies "delayed", not "made less durable."** It stops insignificant scheduling
> overhead counting as a prohibited delay. It creates **no de minimis exception for reduced
> durability.** If the baseline process-death window between the durable ratchet advance and
> `ws.sendMessage` is `K`, cover traffic that adds instructions there makes it `K ∪ C`. The
> pre-existing window is not cover's doing; enlarging it is, and the supremacy clause above forecloses
> trading it away because `C` is small.
>
> The reductio that some materiality threshold must exist fails: **code independently required for
> the real send is not added "because cover traffic was attempted".** Cover-specific work can always
> be ordered *after* the socket handoff, so `C` can be empty — and if an implementation cannot
> integrate cover traffic without putting cover-dependent work in front of the handoff, the absolute
> requirement demands restructuring or a formal spec change, not an unwritten exception.
>
> **What this cost the implementation, recorded because a false structural claim is what triggered
> the ruling.** Fix round 2 argued the window away with *"a process can only die at a suspension
> point"*. That is false — a coroutine may only *suspend* at a suspension point; the OS can kill the
> process at any instruction, which §1's threat model assumes. Entering the cover seam was itself
> cover-specific work in the window. Fix round 3 inverts the call: the coordinator publishes through
> its own non-suspending `publishOutgoing` / `publishReceipt` and *then* calls `CoverTraffic.cover`.
>
> **~~Declared residual, because ordering alone cannot remove it.~~ CLOSED in fix round 4.** Round 3
> declared: "between `ws.sendMessage` returning and the pairing registering itself with teardown
> there are a handful of instructions; closing that would mean registering *before* the publish —
> cover work in front of the handoff, and a lock a real send could queue on — which this requirement
> forbids." **The impossibility argument was unsound and was refuted with a construction** (round-4
> reviewer, adopted): the window does not need to be *atomic* with the handoff, it needs to be
> *serialised* against teardown, and the coordinator already owns a serialisation point every send
> goes through — its single confined worker. Terminal teardown is now **enqueued on that worker**, so
> it runs strictly before or strictly after a send's slice and never inside it; and because there is
> no suspension point between the publish tail and the pairing's admission, that slice is
> uninterruptible. **No lock and no cover-side instruction was added in front of any real send.**
>
> The same construction retires the other half of R-U3-5 step 1: "stop admitting new real sends" is a
> plain volatile flag read at the top of each send coroutine, thousands of instructions and several
> suspension points before the durability barrier — nowhere near the `K` window, and not
> cover-specific.
>
> **What ordering did move:** the cover frame is now BUILT before the pairing is admitted rather than
> after. The build is still strictly after `ws.sendMessage`, so `K` is byte-for-byte the pre-U3 one.
> See R-U3-5 for what that bought.

### R-U3-1 SUBORDINATION — how it is implemented. **FIX ROUND 6 APPLIED 2026-07-28.**

The rewritten R-U3-1 has two halves. The first — *no cover-specific work precedes a real frame's
transport handoff* — was settled in rounds 3–5 by ordering and confinement. The second — ***cover
never competes for a contended resource; where it is contended, cover yields*** — was **not**
implemented, and review round 7 found the two places it was violated. Both were **failed real
sends**: a message that would have gone out without cover traffic did not, because cover took the
resource.

| Resource | How cover competed | How cover now yields |
|---|---|---|
| **The transport's outbound queue** | `WsClient.sendMessage` hands the frame to OkHttp's asynchronous writer, which buffers it, refuses once the buffer would pass 16 MiB, and **closes the connection** when it refuses. With a stalled writer, a decoy consumed capacity and the *next* real `sendMessage` returned false. | `CoverPressure` reads `WsClient.outboundQueueBytes()` (OkHttp's own `queueSize`) before any cover work and sheds cover above an 8 KiB watermark — 0.05% of the cap, ~8 frames, against a healthy socket's 0. |
| **The relay's per-account send budget** | `sendLimit` (100/min) is charged to the **authenticated** account and the cover frame rides the same socket, so a covered send costs two permits and the account exhausted at ~50 real sends. | Cover sheds on the relay's own `rate_limited` (now routed from `MessagingCoordinator.onServerError` to the seam) and on this session's own recent frame rate — 40 frames, both halves counted, in a trailing minute. |

**Both of these were previously written down as accepted residuals. Under the rewritten requirement
they are defects, and they are fixed.** Two claims that supported the old reading have been struck
wherever they appeared: *"a human sender will not approach `sendLimit`"* (§6.3 item 3) and *"no
client-side headroom policy is sound, so cross-send preemption is a relay-side item"*
(`DecoySendPairing` kdoc, `DecoySendPairingTest`, §6.3).

> **⚖️ WHY THE CLIENT-SIDE DEFENCE IS SOUND, having been ruled unsound.** The earlier ruling was that
> `sendLimit` is a server constant the relay never communicates, so a client assuming 100/min against
> a relay configured lower inverts the priority it claims to guarantee. **That is correct, and it
> kills a HEADROOM policy** — one that computes remaining capacity — because a headroom policy must
> *predict* the limit. **It does not touch a REACTIVE one.** Yielding on a signal of pressure needs no
> number at all: the queue depth is read from the socket, `rate_limited` is an event the relay sends,
> and the frame rate is our own. Nothing in `CoverPressure` knows or assumes any limit. This is also
> why the fix does not depend on the relay learning to carry a message id on `rate_limited` — that
> remains worth doing, and remains grouped for the CX23 trip, but it is no longer the only route.

**The generosity rule, applied.** Per the maintainer ruling, no threshold here tries to spend the
last safe slot: cover yields three orders of magnitude before the queue could refuse anything, and at
a frame rate that leaves at least 60 of the relay's nominal 100 free for real sends. A trip turns
cover off for a **60-second window**, one width of the relay's own bucket, rather than for one send —
R-U3-3 asks for a consistent state, not a stutter.

**The disclosure line is where the yield stops.** `stop()` and `quiesce()` drain every admitted
pairing **unconditionally**; the drain does not consult pressure and a tripwire fails if it starts
to. Shedding under load is *degradation* — a burst of frames is already visible to anyone watching
the connection — while a cover frame missing because the vault locked or the transport changed is
*disclosure*, and that is the class rounds 3–5 closed.

**What remains, stated rather than implied.** The ~20 cover frames emitted at the **onset** of a
burst, before the rate meter trips, are still charged to the account; if that same minute then
carries more than 80 real frames, the real sends at its tail lose permits those cover frames spent,
and only `rate_limited` closes it after the fact. Eliminating that would require predicting a limit
the relay never states — which this section has just ruled out — and shrinking it further would mean
shedding cover during ordinary conversation, which is the feature. The meter is also in-memory only
(R-U3-5 forbids storing it), so a lock/unlock inside one minute resets it while the relay's bucket
does not.

**And a THIRD contended resource, named here because the requirement says "any resource" and this one
is not closed: the confinement worker itself.** The cover frame is built on `MessagingCoordinator`'s
single confined dispatcher — the same worker every real send runs on — so a real send dispatched
while a build is in progress waits for that build. It is milliseconds of CPU and a vault read, the
drawn gap *suspends* rather than holding the worker, and cover's yield takes the build out entirely
under pressure. **It is not closed, and it must not be**: the build sits on that worker with no
suspension point in it precisely because that is what makes a pairing's admission atomic against
teardown, which is what retired the drain's 100 ms deadline (round 4) and closed the split-pair class
(rounds 4–5). Moving the build off the worker to remove a few milliseconds of scheduling would
reinstate two P1s. **Recorded as a priced trade, not an oversight** — and the earlier claim that *"the
delay cover traffic adds to a real send is not small, it is none"* was true of the class's lock and
false of the worker; it has been corrected in `DecoySendPairing`'s kdoc.

### R-U3-2 — ~~A covered send is two frames an observer cannot tell apart~~ **AMENDED: REAL-FRAME-FIRST, ALWAYS**

> **⚖️ MAINTAINER RULING 2026-07-27 — random ordering is CONCEDED. The real frame always goes first.**
>
> **This is a ruling, not a preference, and the exhaustion proof is why.** On a decoy-first send there
> are exactly **three** places the drawn gap can sit relative to the durability barrier and the atomic
> `contactExists → ws.sendMessage` tail, and **all three break something**:
>
> | Gap position | Breaks |
> |---|---|
> | After the barrier | Widens the process-death loss window and the `deleteContact` race that was ~0 ms wide |
> | Before the barrier | The flush's own duration lands inside the decoy-first interval and nothing else's — the asymmetry already found and removed once, reintroduced larger |
> | Inside the tail | Ciphertext to a contact deleted during the gap (breaks D2c directly) |
>
> There is no fourth position. **Decoy-first has no correct implementation, not merely a worse one.**
>
> **Structural beats guarded.** Real-first makes all four R-U3-1 violations *impossible by
> construction* rather than *prevented by a check* — the real frame is committed to the socket before
> any cover code runs.
>
> **The traded property is near-worthless against the targeted adversary.** Order randomness bought
> 5–50 ms of correlation ambiguity, and only against an observer watching **both ends** — who already
> reads `recipient_id` in cleartext on both envelopes. A one-sided observer sees two equal-length
> frames either way. Recorded as a residual in §2.4, not as a defeat.

**The amended requirement:** a covered send is two frames of the **same serialized length**, the real
one first, separated by a per-send gap. What must still hold: the two frames are indistinguishable
*by length*, the gap is drawn per send, and nothing about the pair reveals which conversation the
real frame belonged to.

### (superseded) R-U3-2 — A covered send is two frames an observer cannot tell apart
Same serialized length; order not predictable; separated by a small delay drawn per send. Ordering
must be **uniformly** random — pinned by a statistical test over many sends, not by reading the code.
Whether a fixed delay distribution is right is **open**: the only stated constraint is that neither
frame's position nor the gap may be predictable from anything the observer already knows.

### R-U3-3 — Failure is uniform, never intermittent. **This is the subtle one.**
**Intermittent cover is worse than no cover.** If 100 sends are paired and one is not, that one is
marked — the gap is more informative than the absence would have been. So a condition that prevents
cover must produce a *consistent* state for as long as it lasts, not a stutter.

Consequence: a **persistent** cause (no synthetic account provisioned, capacity exhausted) yields
uniformly-off cover, which is an acceptable degradation. A **per-envelope** cause is different — it
produces exactly the stutter this requirement forbids, and **U2 made essentially every real shape
mirrorable**, so a per-envelope failure should be treated as **a defect to fix, not a runtime path to
handle**. If U3 finds a real envelope the builder cannot mirror, that is a finding to report, not a
case to swallow.

### R-U3-4 — RULING on `build()` throwing: the real send proceeds, uncovered
`build()` throws rather than return a mismatched decoy. **The real send still goes.** Blocking it
would be a functional regression caused by a privacy feature, and — worse — a denial-of-service
vector: anything that induces build failures would silence the user. Between one unpaired frame and
a message that does not send, the unpaired frame is strictly less harmful.

**This is a real, accepted cost and it belongs in §2.4 with the others**, not buried here: an
unpaired real frame is precisely the observable the feature exists to eliminate. It is accepted only
because the alternative is worse, and only because R-U3-3 makes it rare by construction.

### R-U3-5 — Nothing survives the vault
No device-level storage, no logging, no diagnostics, no slot or vault-index naming, and every timer,
job or coroutine torn down with the session — the same teardown hook that cancels notifications.
A vault that is locked emits nothing.

**TEARDOWN ORDER IS PART OF THIS REQUIREMENT, and it is not satisfied by moving one statement**
(round-3 third-lens constraint, binding). Round 2's teardown disconnected the socket first and then
cancelled the provisioning job, which owns no pairings — so every vault lock that landed inside a
drawn gap put **a lone real frame and then a TLS close** on the wire: a deterministic, recognisable
class of unpaired real sends correlated with lock, teardown and backgrounding, which R-U3-3 calls
worse than no cover at all. The lifecycle must:

1. stop admitting new real sends and new pairings,
2. stop provisioning,
3. **cancel, complete or drain the pairings already admitted**, and
4. only then invalidate or close the transport.

Reordering alone is insufficient because step 3 requires *ownership* of in-flight pairings, which the
round-2 `stop()` did not have. In the implementation the transport invalidation is passed **into**
`CoverTraffic.stop`, so steps 3 and 4 cannot be separated by editing a caller.

**ROUND-4 ADDITIONS — three things this list did not say, each of which was a P1.**

1. **Step 1 has two halves and round 3 built neither for real sends.** "Stop admitting new real
   sends" is the coordinator's `acceptingSends` gate, checked at the top of every send path before
   any crypto or durable write. Round 3 argued it was not jointly satisfiable with R-U3-1; it is.
2. **Steps 3 and 4 must be SERIALISED against the send path, not merely ordered after it.** Terminal
   teardown runs on the coordinator's confined worker (see R-U3-1's closed residual). Everything
   else in this list follows from that.
3. **A drain must not have a wall clock.** Round 3's drain waited up to 100 ms for a pairing that was
   admitted but not yet built and then **abandoned it**, on the reasoning that the build is
   non-suspending. *Non-suspending bounds suspension, not time* — slow cryptographic generation,
   scheduler starvation or a stalled vault read all overrun it — so the backstop produced exactly the
   deterministically unpaired, teardown-correlated real frame the drain exists to prevent. The
   register now admits a pairing only once its frame is built, so the drain has nothing to wait for
   and no deadline exists.

**A decoy with no real frame behind it is the same defect with the sign flipped (round 4).** The
publish tail returned `Unit`, so "contact deleted, envelope discarded", "socket refused the frame"
and "handed to the relay" were indistinguishable to the caller and cover ran in all three. Two of
them emitted a lone decoy — a frame the user never generated. The tail now returns whether the relay
took the frame and cover is guarded on it.

**~~Declared residual (round 3)~~ — FIXED in round 4.** `ZitroneApp.applyTransportLocked` also
disconnected the socket on a user-initiated transport change (Tor/I2P toggle) without draining. The
third lens ruled this **P1** on a distinction neither reviewer made: **a SPLIT pair is a stronger
signal than a missing cover frame.** A missing frame is one low-grade anomaly plausibly attributable
to jitter; a split pair is two identical-length frames milliseconds apart straddling a TLS teardown
and reconnect, which (a) lets an observer link frames *across connection boundaries*, defeating the
unlinkability the padding exists to provide, (b) binds the marked frame to an independently
observable infrastructure event, and (c) correlates it with "the user just changed their anonymity
transport". The swap now runs through `CoverTraffic.quiesce` — the same drain, **non-terminal**, so
pairing resumes over the new socket — dispatched on the same confined worker. The source tripwire
that used to *exclude* this path now reads both disconnect owners; the deliberate carve-out is gone.

**ROUND-5 ADDITION — the fix above was made through a REUSED PRIMITIVE, and the reuse re-opened the
class it closed.** Both round-4 reviewers converged on this independently, for the first time in
seven rounds, and the third lens ruled it **P1**. Round 4 dispatched the swap onto the worker with the
*same* helper terminal teardown uses — including its 250 ms **caller-thread fallback**. That fallback
is safe for `stop()` and only for `stop()`, because `stop()` invalidates the transport and every late
admission is refused. **`quiesce` deliberately does the opposite**: it leaves the register open, which
is precisely what lets pairing resume over the new socket. So when the fallback fired it drained an
empty register on the calling thread, replaced the socket, and left a send still inside its slice on
the worker free to admit a pairing and emit its cover frame on the NEW connection while its real frame
had gone out on the old one. **No coroutine suspension is needed for that interleave** — the
"uninterruptible slice" argument holds only against teardown running *on the worker*, and the fallback
has just taken teardown off it. The fallback did not merely have an unjustified bound; it structurally
defeated the confinement argument, exactly when it fired.

**The fix is at the LOCK BOUNDARY, not at the fallback**, because lengthening or removing the bound
reinstates a verified five-step deadlock (`applyTransport` holds `transportLock` → blocking reconnect
waits on `confined` → `deleteAccountAndWipe` runs there → its `onConfirmed` calls `lockIf` →
`stopSession` takes `transportLock`). `ZitroneApp.applyTransport` now resolves and installs the new
endpoints and captures the live session **under** `transportLock`, **releases the lock**, and only then
requests the reconnect — which is therefore free to be confined to the worker with **no caller-thread
fallback and no wait at all**. `CoverTrafficWorker` owns the three entry points and keeps them
separate: on-worker terminal (account delete), dispatched-and-bounded terminal (`stop()`), and
dispatched-only non-terminal (transport swap). The swap is skipped if terminal teardown has begun or
completed, and queued swaps are coalesced by generation so one user action produces one reconnect.

**Residuals that remain, stated plainly.**

1. **The terminal fallback.** `MessagingCoordinator.stop()` waits on the confined worker for at most
   `CoverTrafficWorker.TERMINAL_TEARDOWN_WAIT_MS` (250 ms, **per wait — both waits are bounded as of
   round 5**; round 4 left the second one unbounded, which silently reinstated the hang the bound
   exists to prevent) and then runs teardown on the calling thread. The bound is on *waiting for the
   worker to become free*, not on any cover-side work, and it exists because the alternative is a
   vault lock that can hang and never reach `runtime.close()` — a session outliving its own lock is
   worse than any framing defect. **What it costs, now measured rather than asserted:** the real frame
   of a send caught mid-slice goes out **unpaired**. It is never a lone decoy (admission is refused)
   and never a split pair (the transport is invalidated). A test executes this branch.
2. **A transport swap now WAITS for the worker instead of pre-empting it.** With no fallback, a swap
   queued behind a worker that is blocked (not suspended) is delayed for as long as that block lasts.
   The *endpoints* were already re-pointed under `transportLock`, so every new dial — including
   `WsClient`'s own reconnect backoff — already uses the new transport; what lingers is the one live
   socket. This is a latency residual, not a framing one, and it is the price of never splitting a
   pair. The coordinator's blocking work is millisecond-scale disk commits (the registration
   proof-of-work runs on `Dispatchers.Default`, not on this worker).
3. **Natural socket death inside the drawn gap** — re-declared here because round 4 struck it by
   accident. The round-3 residual paragraph that this section replaced also carried the sentence
   accepting "the socket dies between the two writes… already accepted for ordinary network loss",
   and the strike-through took it along with the teardown residual it was adjacent to. The behaviour
   is still live and inherent: if the connection dies naturally during the 5–50 ms gap, `emit` returns
   false and the cover frame is silently dropped, leaving a lone real frame. Nothing can do better —
   the frame it would pair with is already gone — and it is uncorrelated with lock, teardown or
   transport change, which is what distinguishes it from the classes this section closes.

### Open, and to be decided by evidence rather than by this document
- The delay distribution and its bounds (R-U3-2).
- Whether pairing applies to *every* envelope through the choke point, or only to user-visible
  messages. Receipts and attachment control payloads also traverse it. **Name the choice and its
  observable consequence; do not assume the answer.**

## 5. Implementation units — Rule of 6, hard cap at 6

Each unit is independently reviewable, adversarially reviewed to convergence, and merged before the
next begins. No version bump, no push, nothing merged without explicit maintainer approval.

| Unit | Scope | Gate to clear before the next unit |
|---|---|---|
| **U1** ✅ | Synthetic account provisioning + `TAG_DECOY` codec section. Lazy registration, credential storage, token refresh, capacity budget, ~~counter-reservation allocator~~ **(deleted 2026-07-27 with the ping — §3.0)**. ~~**Built, deliberately UNWIRED**~~ — **WIRED as of U3 (2026-07-27): `DecoySendPairing` constructs the provisioner and is the first thing in the tree that can spend a registration.** | **DONE** on `feat/0.10.0-decoy-u1-provisioning`. **678 tests / 0 failures** after fix round 4, `assembleDebug` exit 0, re-verified independently each round. Capacity measured **[U2 R2]**: raw section body 717 B → 700 B (deterministic, asserted exactly); the *encoded* figure is run-to-run DEFLATE noise at 636–646 B either side of the change, so the budget stands at 1024 B as a bound. ~~640–643 B~~ was the pre-U2 measurement and is superseded. **Paired-blind review of the WHOLE unit: SIX rounds complete** (findings 10 → 11 → 10 → 6 → … → clean, with a third-lens tiebreak at round 6); fixes applied and mutation-verified each round. **MERGED**, along with U2. Re-ratification of §4.1's third-pass wording is still owed. |
| **U2** ✅ | Decoy envelope builder. **[R1] `build()` takes the real envelope it covers** and mirrors every size-affecting property of it — shape, ciphertext byte length, counter, timestamp width, TTL, burn, media type — then measures both frames and refuses to return a decoy whose frame is not exactly as long. *(Counter reservation moved to U1, at R1 out of the paired path entirely, and at R2 **deleted outright** — see §2.3/§3.0.)* | **BUILT on `feat/0.10.0-decoy-u2-envelope-builder`; ~~deliberately UNWIRED~~ WIRED as of U3, which pairs every outbound envelope through it. MERGED.** `DecoyEnvelopeBuilder` + **18 gate tests** (16 before R3, 13 before R1; the count of 14 recorded here before R1 was wrong — G-F). ~~694 tests~~ **(see the round-3 count at the end of this cell)**, `assembleDebug` exit 0, `--rerun-tasks`. **Fix round 1 of 6 applied: 18 mutations run, 17 discriminated**, the survivor a deliberate probe of a defence-in-depth check (recorded). **No `SessionBuilder.process`, no Signal record written** — now a fact about the type, which has no vault access at all. **Round-1 P1s fixed:** shape followed the decoy's own counter rather than the covered message (G-A); `0x05 ‖ random(32)` is not a valid Curve25519 encoding, keys are now generated and the private half dropped (G-B). **Round-1 ruling deviation, argued in §2.3:** the digit-width difference (G-C) cannot be absorbed in the ciphertext — a base64 field's length is always a multiple of 4 — so the counter is mirrored instead. **Three spec corrections from U2 still PENDING RATIFICATION — §2.1's table, §2.3's ciphertext formula, §2.4's residual list.** **Fix round 2 of 6 applied (2026-07-27) — NOT review-driven: it implements the maintainer's §3.0 cut.** `DecoyCounterReservation` + its 14 tests deleted; `TAG_DECOY.counterHighWater` (W3) and `deadAirNextFireAtMs` (W4) removed from the codec on both sides; `DecoySectionLock` **kept**, argued from its surviving callers. Codec-canonicity coverage retargeted onto `provisionNotBeforeMs` rather than dropped, plus a new offset tripwire and a deterministic raw-body-length assertion. **Paired-blind review round 2 complete and adjudicated (0 P1, 1 P2, 4 P3); FIX ROUND 3 OF 6 APPLIED (2026-07-27).** The P2 was **G2-A: a first message may carry `ephemeral_key` set and `prekey_id` NULL** — ordinary signed-prekey-only X3DH — and the builder refused it in four places, so a real send to a peer whose one-time prekeys were exhausted would have got **no cover at all** once U3 wires the pairing. Fixed; §2.2's sentence that seeded it is struck; §2.4 gains a fourth residual. Also: the gate fixtures now VARY `media_type`/`version`/`previous_chain_length` (they only ever compared defaults, and `"file"` is the same width as `"text"`); the U1 WRITER/READER invariant table corrected in place with `DecoyState`'s kdoc made the canonical field-set pointer; the provisioner's stale allocator-based lock justification rewritten. **681 tests / 3 skipped / 0 failures**, `assembleDebug` exit 0, `--rerun-tasks`; **7 mutations, 7 discriminated**. Review round 3 dispatched and adjudicated; unit merged. |
| **U3** | Pairing at the send choke point. ~~Random order (decoy-first / real-first)~~ **REAL FRAME FIRST, ALWAYS (ruling 2026-07-27 — see R-U3-2)**, few-ms stagger, and the **real envelope handed to `DecoyEnvelopeBuilder.build` as the thing to mirror** — not a block count (§2.2 R1). Insertion inside `MessagingCoordinator`'s confined worker, **after** `ws.sendMessage` — see the round-3 correction in R-U3-1. It also WIRES U1 and U2: `DecoySendPairing` is the first construction of `DecoyAccountProvisioner` in the tree. | ~~Ordering is uniformly random — pinned by a statistical test~~ **superseded by the ruling: the order test is now absolute (one decoy-first send is a defect), and only the stagger stays statistical.** Stagger drawn per-send, bounded, uniform, and independent between sends. **`build()` throws rather than return a mismatched decoy; U3 owns what happens then, and must not let it fail the real send.** **THE ROUND-3 GATE, added because rounds 1–2 both missed a side of it:** cover traffic is strictly *subordinate* to the real send at BOTH ends — no cover-specific instruction may precede the socket handoff (R-U3-1), and no admitted pairing may outlive the transport it needs (R-U3-3/R-U3-5). Fix round 3 of 6 applied 2026-07-27: the publish tail moved back to the call site so the seam cannot be handed a real send at all, and `CoverTraffic.stop` now owns the transport invalidation and drains the pairings it admitted before running it. **Fix round 4 of 6 applied 2026-07-28** — severity had gone UP in round 3 (2 P1 -> 4 P1), and the composed repair is: the publish tail returns whether the relay took the frame and cover is guarded on it (no lone decoys); terminal teardown and the transport swap are dispatched onto the coordinator's **confined worker**, which closes the declared R-U3-1 residual, retires the drain's 100 ms deadline, and makes the Tor-toggle disconnect a drained non-terminal `quiesce`; `ensureProvisioning` holds the teardown lock across check->CAS->assign. *(The "35 pairing tests" claimed here after round 4 was wrong — the file held 34; corrected at round 5, which is the sort of number other reviewers calibrate mutation accounting against.)* **Fix round 5 of 6 applied 2026-07-28** — round 4 was the FIRST round in seven where the two blind reviewers converged on the same top finding, with severity falling, which the calibration rule reads as the surface being exhausted. That finding: round 4's confined dispatch was a **reused primitive**, and its 250 ms caller-thread fallback is terminal-safe only for `stop()`; on the non-terminal `quiesce` path it re-opened the split-pair class it had just closed. Ruled P1 on tie-break, **fixed at the lock boundary rather than at the fallback** (`ZitroneApp` releases `transportLock` before requesting a reconnect that is confined, unbounded-free and fallback-free), because lengthening or dropping the bound under the lock reinstates a verified five-step deadlock. The dispatch is now a separate production class, `CoverTrafficWorker`, **because round 5's second finding was that nothing tested it**: both round-4 "confinement" tests built their own executor, production dispatch was pinned only by source strings, and the fallback branch was never executed by anything. **Fix round 6 applied 2026-07-28 — the REQUIREMENTS were the defect, and this is the fix that followed from rewriting them.** Seven rounds and four lenses kept finding reachable counterexamples to R-U3-1/R-U3-3 because both were written as guarantees about *outcomes*; three of four concluded the feature was unshippable. The rewrite (78fd0f89, bed38595) states rules about our own behaviour instead. Two of round 7's four findings then stopped being residuals and became defects: cover consuming the OkHttp outbound-queue capacity a later real send needed, and cover doubling consumption of the relay's per-account `sendLimit`. **Both were failed real sends caused by cover traffic.** The fix is `CoverPressure`, a production yield policy the seam consults at the top of every send: it sheds cover on queue depth over a low watermark, on the relay's `rate_limited` (newly routed through `onServerError`, which was empty), and on this session's own recent frame rate — then stays off for a 60 s window rather than stuttering. Generous by ruling: no threshold computes remaining capacity, and the drain deliberately does **not** consult it, because a cover frame missing at a vault lock is *disclosure* while one missing under load is *degradation*. **This also reverses the earlier ruling that a client-side budget defence is unsound** — that reasoning assumed the client must predict `sendLimit`; yielding reactively predicts nothing. **48 pairing tests + 12 pressure tests + 33 provisioner tests; round-6 mutations: 12 applied, 12 discriminated.** **Reviews: 7 rounds dispatched, all adjudicated (rounds 3, 4 and 5 with third-lens rulings); round 6 not yet dispatched. NOT merged, no version bump.** |
| **U4** | Synthetic-side receive: second WS connection for the synthetic account, deliver → ack → burn at ~30 ms, occasional send-back so the exchange is bidirectional. | Decoys never surface in UI, notifications, or unread counts. Notification parity §7 re-verified with decoys active. |
| ~~**U5**~~ | ~~Dead-air ping within a session~~ **CUT 2026-07-27 by maintainer decision — see §3.0.** No unit, no follow-up gate. `DecoyCounterReservation` (U1) and `TAG_DECOY.deadAirNextFireAtMs` lose their only consumer and are removed with it. | **REMOVAL DONE (U2 fix round 2, 2026-07-27)** — allocator, both fields and their tests are out of the tree; `DecoySectionLock` survives on its other callers. |
| **U6** | 🍋‍🟩 indicator + docs. `SECURITY_MODEL.md` honest framing, `VAULT_ARCHITECTURE.md` §8 amendments (both), the §1 overclaim corrections, **and the dead-air disclosure (§3.0) — see the gate.** | Ships **with** the feature, per deliver-then-claim. Not after. **HARD GATE: the indicator must not imply continuous cover.** Cutting the ping made "dead-air periods are NOT covered" a permanent, user-visible limit. A 🍋‍🟩 that reads as "cover traffic is on" — rather than "cover traffic was generated for your last message" — is a *worse* overclaim than the four corrected in `96982421`, because it would be introduced by this release rather than inherited. U6 must state, in `SECURITY_MODEL.md` and in-app: cover traffic exists **only alongside real sends**; a silent client sends nothing. |

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
     whatever the section gained during the seconds of network I/O — ~~up to and including a counter
     high-water mark~~ **(the counter mark is gone as of 2026-07-27, §3.0; the rule is unchanged and
     its remaining subjects are the token writes and another attempt's back-off)**.
   - **A failed or deferred provision must degrade silently to "decoys off"** — never block
     onboarding, never surface an error that implies a fault, and never let the 🍋‍🟩 indicator claim
     the mechanism fired when it did not.

   **Sequencing recommendation:** the interim 300 absorbs 0.10.0's added load at current beta
   volumes, so this does not block the spec. But non-IP registration keying (CX23 P2) should land
   before any announcement that grows onboarding volume, since decoys make the shared bucket
   saturate 33% sooner.

3. **Send rate limit. ~~Noted, no action.~~ ACTIONED IN U3 FIX ROUND 6 (2026-07-28).** `sendLimit`
   is 100/min per account (`main.go:51`, `hub.go:159`) and is charged to the AUTHENTICATED account,
   so a covered send costs two permits. "A human sender will not approach it" was the wrong reading:
   pairing halves the volume a human sender *can* reach, which is a real send failing because of
   cover traffic — an R-U3-1 defect under the rewritten requirement, not a note.
   **Cover now yields**: `CoverPressure` sheds it on the relay's own `rate_limited` and on this
   session's recent frame rate, so cover can contribute at most ~20 frames to any minute and at
   least 60 of the nominal 100 stay free for real sends. See R-U3-1 in §4.3 for why this is sound
   despite `sendLimit` being a constant the relay never states — the client yields *reactively* and
   never predicts the limit. The relay-side half (exempting or raising the budget for cover frames,
   and carrying the message id on `rate_limited`) is still worth doing and is grouped for CX23; it is
   no longer the only way to fix this.
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
