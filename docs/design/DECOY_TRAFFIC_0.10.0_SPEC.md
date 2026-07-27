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
| Short text or batched read receipt (≤252 B) | 256 | **821 B** |
| Text 253–508 B | 512 | **1161 B** |
| Attachment control payload (always 286 B) | 512 | **1161 B** |
| X3DH first message, short text | 256 | **860 B** (+39 B: `ephemeral_key`, `prekey_id` non-null) |

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
carries non-null `ephemeral_key` and `prekey_id` (+39 B, two fields flipping non-null); every later
one has them null. The synthetic conversation must show the same shape: **emit well-formed-looking
values exactly once at setup, null thereafter.** A random 32-byte value (base64) for
`ephemeral_key` is indistinguishable from a real one to anybody without the key, which is everybody.

> **BINDING FOR U2 — `prekey_id` must be drawn from the range the real path actually emits, verified
> against source, not guessed.** A value outside that range is a fingerprint. It would be the
> existing web generator's defect reintroduced one field over — a constant-or-implausible value where
> real traffic varies — and it would defeat the entire point of the synthetic-account approach. Read
> the real prekey-id assignment before choosing the draw.

U1 already registers a genuine prekey bundle for the synthetic account (so the relay's view of that
account is an ordinary one) while discarding the private halves, which is exactly the right
groundwork for this and requires no rework.

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
decrypt it.** Therefore the decoy ciphertext is `random(32) ‖ random(12) ‖ random(N·256 + 16)` —
byte-shaped identically to a genuine `ratchet_pub ‖ nonce ‖ AEAD(ct+tag)` blob and
computationally indistinguishable from one to anybody without the key, which includes everybody.

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
| W1 | `DecoyAccountProvisioner.provision()` | First unlocked session in which decoys are enabled and no synthetic account exists | Account id, identity keypair, initial tokens, and `provisionNotBeforeMs = null` (a success is the only thing that retires the back-off). **The counter reservation is NOT written here** — `counterHighWater` stays 0 until `DecoyCounterReservation.next()` first reserves a block (W3). **Dead-air next-fire is written `null`** — the distribution is U5's to settle (§3.2 re-framed the ping from wall-clock to in-session, so a durable wall-clock next-fire is of questionable meaning). The field exists and round-trips. | **DONE (U1)** |
| W1b | `DecoyAccountProvisioner.reserveBackoff()` — the **write-ahead back-off** | **Before any relay contact**, on every attempt that gets past the deferral check | `provisionNotBeforeMs` only — the cross-session back-off deadline, `mutate` + `flushBeforeAck`. If it cannot be written, **no registration is spent at all** | **DONE (U1 R2)** |
| W2 | `DecoyAccountProvisioner.refreshTokens()` | Synthetic session token refresh (7-day refresh-token TTL, `auth/jwt.go:26`) | Tokens only; all other fields untouched | **this unit (U1)** |
| W3 | `DecoyCounterReservation` | Counter reservation exhausted (once per 64 decoys) | High-water mark only, monotonically increasing | **allocator DONE (U1)**; the `DecoySender` that spends the values is U2 |
| W4 | `DeadAirPinger.rearm()` | After each dead-air ping fires | Next-fire time only | **this unit (U5)** |
| W5 | `VaultRuntime.mutate` (existing) | Every write above, without exception | Re-encodes whole `VaultState` under `stateLock` and **SCHEDULES** a reseal — **it is not durable**, see §2.3's correction | existing |
| W6 | `VaultRuntime.flushBeforeAck` (existing) | W1, W3, and both back-off writes — every value that must survive process death | Forces the scheduled payload to disk synchronously. **A throw means the value was never issued / never recorded** | existing — **added 2026-07-27 (U1 R1)** |

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
> `TAG_DECOY` appears **only in a vault that has set up cover traffic.** A user whose vault never
> does keeps one that opens fine on 0.9.x.
>
> Option (a) still stands and the ruling is unchanged; only its scope is smaller than priced. **The
> disclosure in §4.1 is narrowed accordingly** — an overstated disclosure is its own dishonesty, and
> scaring every user about a break most of them will never hit is not caution, it is inaccuracy in
> the direction that happens to feel safe.
>
> **[U1 round 3] The trigger is "set up", not "generated".** U1 writes a durable back-off *before*
> contacting the relay, so the section appears the moment provisioning is attempted rather than when
> the first decoy goes out. The two coincide in practice — U3 provisions lazily, from the first
> session that actually needs a decoy — but they are not identical: a vault that registers and then
> never sends still carries the tag. An attempt that fails **before** spending a registration now
> retires its deferral, which empties the holder and puts the vault back in the omitted case, so a
> failed offline first attempt does not cost the downgrade path. Wording below adjusted to match.

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
> **What 0.10.0-beta specifically changes:** once a vault has **set up cover traffic** — which
> happens the first time it sends any — it can no longer be opened by 0.9.x; downgrading will present
> that vault as corrupt. A vault that has never used cover traffic is unaffected and still opens on
> 0.9.x.

*(Narrowed 2026-07-27 after U1. The first draft said flatly that "vaults created by 0.10.0 cannot be
opened by 0.9.x", which is false: the tag is written only once cover traffic has actually been
generated. Corrected rather than left overbroad — the deliver-then-claim rule cuts both ways, and a
disclosure that overstates harm is as inaccurate as one that understates it.)*

> **✅ ADJUSTED AFTER U1 REVIEW ROUND 3 — RE-RATIFIED BY THE MAINTAINER 2026-07-27. This wording is
> FINAL.** The parenthetical is load-bearing and deliberate: it closes the gap for a reader who would
> otherwise have no way to know whether their own vault is affected. The
> narrowing above was an explicit maintainer ruling, so this change is flagged rather than made
> quietly. "Generated cover traffic" became **false** in U1: the provisioner records a durable
> back-off *before* it contacts the relay, so the tag lands when a vault **sets up** cover traffic,
> which is earlier than its first sent decoy. Round 3 fixed the half of that which was a real defect
> — an attempt that fails before spending a registration now retires the deferral, and the emptied
> section is omitted again, so a vault whose only brush with cover traffic was a failed offline
> attempt keeps its 0.9.x readability. What remains is genuinely broader than the ruled wording: a
> vault that registers and then never sends still carries the tag. The wording above is the minimal
> honest widening. **An overstated disclosure is its own dishonesty — which is why the maintainer
> narrowed it — but an understated one is worse, so this could not simply be left as it stood.**

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
| **U1** ✅ | Synthetic account provisioning + `TAG_DECOY` codec section. Lazy registration, credential storage, token refresh, capacity budget, counter-reservation allocator. **Built, deliberately UNWIRED** — nothing constructs it, so the branch cannot spend a registration. | **DONE** on `feat/0.10.0-decoy-u1-provisioning`. 645 tests / 0 failures, `assembleDebug` exit 0, both re-verified independently. Capacity measured: 640–643 B worst case against a 1024 B budget. **Paired-blind review of the WHOLE unit still owed before merge** — review the unit, not the delta. |
| **U2** | Decoy envelope builder. Random-ciphertext blob at a requested block count; field population mirroring the real send path; the one-time X3DH-shaped first envelope. *(Counter reservation moved to U1.)* | Byte-level test asserting a decoy frame is indistinguishable field-for-field from a real frame of the same block count, *including* that no field is a constant where a real message varies. **`prekey_id` drawn from the real path's actual range, verified against source — see the binding constraint in §2.2.** Must **not** call `SessionBuilder.process` (§2.3 ruling). |
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
     written and flushed BEFORE any relay contact, and only a successful commit retires it** (in the
     same mutate that stores the credentials). If the smallest decoy write the client can make does
     not fit, no registration is spent at all. Two consequences, both deliberate: *every* failure
     defers, not only a 429 (a crash between register and commit, an offline challenge fetch, a dead
     session mint), and a purely local failure therefore costs a 60–90 minute wait. For a background
     nicety measured against a worldwide bucket, that is the right direction. The failed commit must
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
