# U2 (decoy envelope builder) — WRITER/READER invariant table: **NOT BUILT, and why**

> # ⚠️ SUPERSEDED IN PART BY FIX ROUND 1 (2026-07-27). READ THIS FIRST.
>
> This document records U2 **as it was built before paired-blind review round 1**. Two of its
> load-bearing claims are no longer true of the code, and it is left in place as the measurement
> record rather than rewritten, because its NUMBERS are still the measurement and were confirmed by
> an independent reviewer. Its DESIGN NARRATIVE is not current. What changed:
>
> 1. **U2 no longer touches `TAG_DECOY.counterHighWater` at all, and no longer calls
>    `DecoyCounterReservation`.** The paired decoy mirrors the covered envelope's `message_number`.
>    The reason is in spec §2.3 and is arithmetic, not taste: a padded base64 field's length is always
>    a multiple of 4, so the `ciphertext` field cannot absorb a 1–3 byte decimal-width difference in
>    `message_number`, and a monotonic counter cannot be steered to an arbitrary real counter's
>    width. The allocator's consumer moves to U5's dead-air ping. **So the conclusion of this
>    document — that no invariant table is warranted — is now MORE true than when it was written:
>    U2 touches no durable signal whatsoever.**
> 2. **"Exactly once" is no longer derived from the decoy's counter 0.** The X3DH shape is emitted
>    exactly when the covered envelope carries one, because `build()` now takes the real envelope and
>    mirrors it (review finding G-A). The "interrupted session can skip counter 0" residual recorded
>    below is therefore withdrawn along with the mechanism that created it.
>
> The frame-size table below (§2, "821 → 829") is the ORIGINAL measurement record and states the old
> numbers deliberately, as the before-and-after it was written to be. **Spec §2.1's table is the
> single canonical statement of frame sizes; nothing here overrides it.**

The standing rule is: *any change to a durable multi-reader signal gets its writers, its readers, and
what each reader assumes the signal MEANS at the moment it reads, enumerated first.* The rule has a
precondition. **U2 does not meet it, and performing the ritual anyway would be worse than skipping
it** — a table that enumerates nothing new teaches the next unit that the ceremony is the point.

## What U2 touches

| Durable signal | U2's relationship to it |
|---|---|
| `VaultState` TLV section `TAG_DECOY` (0x06) | **No new field, no new writer, no changed field meaning.** |
| `TAG_DECOY.counterHighWater` | **Read and spent, through U1's `DecoyCounterReservation` only.** U2 adds no second path to the mark; it calls `next()`. The allocator is W3 in the U1 table and its contract is unchanged. |
| Every other `TAG_DECOY` field | Untouched. Pinned by a test (`building cover traffic writes no Signal record and moves nothing but the counter mark`). |
| `VaultState.signalRecords` | **Untouched — this is the §2.3 ruling in code.** No `SessionBuilder.process`, no `SessionCipher`, no ratchet session for the synthetic peer. |
| Device-level storage | None. No diagnostics sink, no log line, no string resource. |

U2 is a **pure shaper plus one call into an existing allocator**. `DecoyEnvelopeBuilder` holds no
state of its own beyond its collaborators, and the envelope it returns is a value.

## The one thing that IS new, and why it is inside the existing table rather than beside it

U2 is the concrete instantiation of **reader R2** ("`DecoySender.send()` — these counter values have
never been issued before"), which the U1 table already carries. It adds one derivation on top of
that reader, and the derivation is worth writing down even though it needs no table:

> **The X3DH-shaped first envelope is the one issued counter `0`.**

"Exactly once" therefore needs no new durable flag: `counterHighWater` already makes "the value 0 has
been issued" durable, monotonic and unrepeatable, which is exactly R2's stated meaning. The
alternative — a `firstEnvelopeSent` boolean in `TAG_DECOY` — would have been a genuinely new durable
field written on the send path, inside a fixed-size region, and would have needed the full table.
It was rejected for that reason and not for convenience.

**Residual, stated rather than hidden.** An interrupted session can leave counter 0 reserved but
unspent, and the reservation contract SKIPS rather than reissues. Such a vault's synthetic
conversation then begins mid-chain with no first-message envelope ever sent. That is relay-visible
only (§1 concedes the relay in full), it is a one-off per vault, and it is strictly cheaper than the
durable field it replaces. Recorded here so a later unit does not rediscover it as a defect.

## Scope boundary — U2 stays UNWIRED, like U1

Nothing constructs `DecoyEnvelopeBuilder` in production. U3 supplies the call site at the send choke
point. So this branch cannot emit cover traffic on any real device, exactly as U1's branch could not
spend a registration.

---

# SPEC CORRECTIONS — facts in `DECOY_TRAFFIC_0.10.0_SPEC.md` that are wrong, MEASURED at U2

All three were measured against real libsignal 0.46.0 output on this machine, by encrypting genuine
`MessagePadding`-padded plaintext through a real `SessionCipher` over in-memory stores. **None was
estimated.** The R7 block's own instruction — *measure it, do not estimate* — is what produced them.

## 1. §2.3's ciphertext formula is WRONG, and wrong in the same way the `ephemeral_key` error was

> §2.3: "the decoy ciphertext is `random(32) ‖ random(12) ‖ random(N·256 + 16)` — byte-shaped
> identically to a genuine `ratchet_pub ‖ nonce ‖ AEAD(ct+tag)` blob"

That describes a generic AEAD framing. It is **not** what libsignal serializes.

> **⭐ The CANONICAL wire layout lives in `DecoyEnvelopeBuilder`'s wire-shaping section, next to the
> code that emits it and pinned by a byte-diff against real libsignal output on every test run. The
> block below is the MEASUREMENT RECORD that produced the correction — it is not a second contract,
> and a later change must move the code and its test, not this paragraph.**

A real `SignalMessage` measured at U2:

```
0x34                                   version byte (message version 3, ciphertext version 4)
0x0A 0x21 <33>                         field 1, sender ratchet key — 0x05 type tag + 32-byte point
0x10 <varint>                          field 2, counter
0x18 <varint>                          field 3, previous_counter
0x22 <varint> <N·256 + 16>             field 4, the AEAD body
<8>                                    truncated HMAC
```

For N = 1 with a small counter that is **323 bytes**, not the formula's 316. And the miss is not
merely seven bytes: **323 mod 3 = 2 and 316 mod 3 = 1**, so the formula's blob base64s to 424
characters ending `==` where a real one gives 432 ending `=`. It is the *exact* defect the R7 block
caught in `ephemeral_key`, in the field next to it, and it would have shipped a perfect
one-field discriminator on **every** decoy rather than only on first ones.

**Additionally, the length is not a function of the block count alone.** `counter` is a protobuf
varint: 127 costs one byte, 128 costs two, 16 384 costs three. `message_number` rides in the
**cleartext**, so a decoy sized from any fixed formula is checkably short from its 128th envelope
onward. U2 encodes the real varint; `the counter VARINT boundary is honoured` pins it against real
libsignal output at 126/127/128/129/16 383/16 384.

## 2. §2.1's frame table is understated, and the first-message row is understated by ~4×

Measured through the production `MessageEnvelope.toJson()` + `WsClient.messageSendFrame`:

| §2.1 says | Measured | Note |
|---|---|---|
| Short text → **821 B** | **829 B** | 825 B when `ISO_INSTANT` trims the fractional second |
| Text 253–508 B / attachment → **1161 B** | **1169 B** | same +8 |
| X3DH first message → **860 B (+39 B)** | **976 B (+147 B)** | the R7 block predicted this row was wrong; this is the number |

The +39 B figure counted only the two JSON fields. The `PreKeySignalMessage` wrapper itself costs
**81 bytes** on the wire (version, pre-key id, 33-byte base key, 33-byte identity key, the inner
message's own length header, registration id, signed pre-key id) which becomes ~108 base64
characters on top of the two fields.

**Consequence for U5, flagged now:** §3.3 fixes the dead-air ping at "a single 256-byte block
(821 B frame)". The frame is 829 B (825 B for a whole-second timestamp) — the *design* (match the
mode, one block) is unaffected, but the number in the text is not the number on the wire, and
`SECURITY_MODEL.md` must not inherit it.

**Also worth knowing, because it is pre-existing real behaviour and not a decoy artefact:**
`DateTimeFormatter.ISO_INSTANT` trims trailing zeros in the fractional second, so real frames already
vary by up to 4 bytes on timestamp alone. The decoy uses the same formatter and the same clock, so it
inherits the variation identically rather than pinning a width — which would itself have been a tell.

## 3. §2.2's "emit well-formed values exactly once, null thereafter" is not what libsignal does — but it is still the right instruction

Stated for the record, because a future round will otherwise "correct" it. libsignal emits
`PREKEY_TYPE` for **every** message until the peer's reply completes the ratchet, not for one. So a
real conversation can show several first-shaped envelopes replaying one `prekey_id`.

The spec's rule is nonetheless right, for a reason the spec does not give: U4's synthetic side
replies within ~30 ms, so exactly one first-shaped message is precisely what a real conversation with
that peer would produce — and a decoy that stayed first-shaped would be **81 bytes larger than the
real message it mirrors**, turning the pair into {X, X+108-ish} and identifying the real one by size.
The rule is load-bearing for the pairing observable. Keep it; the justification is different from
the one written down.

### The residual this creates, which is U4's and the spec's, not U2's

A real client resets `message_number` to 0 on **every inbound ratchet turn**. The reservation is
monotonic by §2.3's deliberate choice and never resets. While the synthetic side only acks and burns
this is invisible; once U4 makes the exchange bidirectional, a relay comparing inbound and outbound
sees a counter climbing through replies that should have reset it. **Cleartext field, relay-visible
only**, and §1 concedes the relay — but it should be a *stated* residual in §2.4's list rather than
something U4 discovers.

*(The protobuf's own `previous_counter` is NOT part of this problem, and was measured rather than
reasoned about: libsignal writes the last COUNTER of the previous chain, not its length, so a client
whose one-message first chain was answered emits 0 for the whole next chain. U2 emits 0, which is
what a real client emits.)*

## 4. `prekey_id`'s source is reachable, but NOT from anything durable — flagged, not papered over

The U2 brief said: *verify the id set is actually reachable from what U1 persisted; if it is not,
stop and report.* The honest answer is **"derivable, but not persisted"**:

- `DecoyIdentity.generateBundle` uploads one-time prekey ids `1..100` **unconditionally**, so every
  synthetic account this codebase has ever registered published exactly that set;
- **nothing in `TAG_DECOY` records it.** The section holds account id, identity keypair, tokens,
  counter mark, dead-air fire, deferral — and no prekey ids.

So the id set is a property of the *generator's source code*, not of the vault. That is reachable
enough to act on, and it is not a gap worth a durable field (100 ids is 400 bytes against a 1024 B
section budget, for a value that is constant). But it is a **cross-file assumption**, so it was made
checked rather than left implicit: `DecoyIdentity.ONE_TIME_PREKEY_IDS` is now the single declaration
that `generateBundle` iterates and the builder draws from, and a test asserts the generated bundle's
ids are exactly that range. A future change to the allocation now fails a test instead of silently
stranding already-provisioned accounts whose real batch the range would then misdescribe.

**The id emitted is `1`, not a random member of the range**, and that is the specific answer rather
than a convenient one: `Store.ConsumeOneTimePrekey` pops `ORDER BY prekey_id LIMIT 1`, and the
synthetic account has consumed none, so 1 is the id the relay would actually issue on a first fetch.
A random draw would be wrong 99 times in 100 against the query that decides it.

**Residual that cannot be closed here:** nothing ever fetches this account's bundle, so the relay can
see that the named id was never consumed. Closing it needs a real bundle fetch and a real session,
which §2.3 rules out. Relay-visible only.

---

# THE U2 TESTS, AND THE MUTATION EACH WAS CHECKED AGAINST

Same discipline as U1's F9/G9/H/J rounds, same reason: the standing failure mode is a test that
passes whether or not the property holds. **Sixteen mutations were applied to the real source, the
suite run, and the failure observed; each mutation was then reverted.** The harness is
`scratchpad/mutate.py` (patch → run → restore, one mutation live at a time).

| # | Mutation | Result |
|---|---|---|
| M1 | `ephemeral_key` emitted as 32 bytes — **the spec's original wording** | FAILED |
| M2 | ciphertext built from **§2.3's `random(32)‖random(12)‖random(N·256+16)` formula** | FAILED |
| M3 | the counter written as a fixed one-byte field instead of a varint | FAILED |
| M4 | the X3DH first-message shape emitted on every envelope | FAILED |
| M5 | `prekey_id` drawn from outside the account's uploaded batch | FAILED |
| M5b | `prekey_id` from inside the batch but not the id the relay would issue | FAILED |
| M6 | `ephemeral_key` drawn independently of the base key inside the ciphertext | FAILED |
| M7 | `ttl_seconds`/`burn_on_read` pinned to constants (**the web generator's own defect**) | FAILED |
| M8 | a reservation throw swallowed and counter 0 used instead | FAILED |
| M9 | `previous_chain_length` emitted as 1 | FAILED |
| M10 | the inner identity key random instead of the sender's own | FAILED |
| M11 | `registration_id` emitted as 0 | FAILED |
| M12 | the trailing 8-byte MAC omitted | FAILED |
| M13 | `previous_counter` written as 1 instead of the measured 0 | **PASSED first — see below** |
| M14 | version byte 0x33 instead of the measured 0x34 | FAILED |
| M15 | counter and previous_counter emitted in the wrong field order | FAILED |

## M13 did not discriminate, and which guard was carrying it: none — it was a genuine blind spot

**Reported plainly, because "which guard was carrying it" is usually the answer and this time it was
not.** Nothing was carrying it. `previous_counter` is a one-byte varint whatever its value, so no
length test can see it, and libsignal's Java `SignalMessage` exposes `getCounter()` but **not**
`getPreviousCounter()`, so no parse-back assertion could reach it either. The twelve tests that
existed at that point were all length, shape or parse assertions, and the field is invisible to all
three.

The fix is not another assertion about that one field. It is a test that makes the class of defect
unrepresentable:

> **`the cover ciphertext is byte-identical to a real one everywhere it is not random`** — for the
> same parameters, every byte of the cover blob equals the real blob's except inside regions that
> are supposed to carry random content (the ratchet/base key value minus its `0x05` type tag, the
> AEAD body, the MAC). The regions are derived from the layout rather than hand-counted, so a layout
> change moves them with it.

M13, M14 and M15 all fail against it. **A subsequent message has only eleven structural bytes**, so
the test carries an explicit guard that the "fixed" set is not empty — set at 11 rather than a round
number, because a round number would have silently passed a vacuous comparison on the smaller of the
two shapes. That guard fired on the first run and is the reason the threshold is where it is.

The generalizable lesson, added to `failures.md`: **a field that cannot change the length and is not
exposed by the parser is invisible to length tests and to parse-back tests both.** Reach for a
structural byte-diff against real output, not for one more assertion.

## A METHODOLOGY FAILURE IN THE HARNESS ITSELF, recorded because it nearly became a false finding

After the mutation sweep, the full suite failed once with `cover[36] = 0x18` where a real message has
`0x10`. That signature is **exactly M15** (the two fields swapped). The mutation harness restores the
source in a `finally` block but **never re-runs Gradle afterwards**, so the compiled classes left on
disk were the last mutation's, and the next invocation's up-to-date check did not rebuild them.

It reproduced zero times in isolation and zero times in a 400-iteration determinism stress of the
builder; a clean `--rerun-tasks` full run passed. **The lesson is not "it was flaky" — it is that a
mutation harness which leaves mutated artifacts behind will hand the next run a defect that does not
exist**, and the natural response (chase a phantom race in the production code) costs more than the
sweep saved. Any future harness must run once more after the final revert, or force a rebuild.

## Not claimed

- **The relay's own view is not tested**, and cannot be from a unit test. Every claim here is about
  bytes the client emits. That is the right scope — the passive observer of §1 sees only frame sizes
  and timings, and those are what the frame-length assertions cover — but it is not a claim that the
  relay cannot tell. §1 already concedes that it can.
- **`android.util.Base64` is not exercised.** It is not loadable off-device, so the equivalence with
  `java.util.Base64` (`NO_WRAP` = RFC 4648 basic alphabet, padded, unwrapped) rests on the flag's
  documented semantics. What IS pinned is the property the equivalence is used for: the emitted
  strings match `^[A-Za-z0-9+/]+={0,2}$` and are a whole number of quanta.
