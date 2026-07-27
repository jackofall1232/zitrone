# U2 — ROUND 2 adjudication (Codex + Grok, paired-blind)

Codex: `FINDINGS (0 P1, 0 P2, 2 P3)` · Grok: `FINDINGS (0 P1, 1 P2, 2 P3)`
Union after dedup: **0 P1, 1 P2, 4 P3.** (Round 1 was 2 P1 + 1 P2 + 7 P3.)

Severity floored at zero P1 and the count halved. But the reviewers were **disjoint on the top
finding for the third consecutive round** — Grok's P2 does not appear anywhere in Codex's report.

## CONFIRMED — must fix

| # | Src | Sev | Defect |
|---|---|---|---|
| **G2-A** | Grok | **P2** | **A real first message can carry `ephemeral_key` set and `prekey_id` NULL — and the builder cannot represent it.** That is ordinary signed-prekey-only X3DH, reached whenever the peer's one-time prekeys are exhausted. Production already models it end to end: `ApiClient.kt:218-230` omits `one_time_prekey`, `establishSession` passes `preKeyId ?: -1`, `EncryptResult.preKeyId` comes back null, and `x3dh.ts:35-36` documents "null if no OPK was available". The builder's `require` at `:234-236` asserts the two fields appear "together or not at all" — **false about real envelopes** — and the first-shaped branch then compounds it: `requireNotNull(cover.preKeyId)`, protobuf field 1 always written, wrapper sized as `1 + varintLength(preKeyId)`, and `baseKeyOffset` assuming field 1 present. **Consequence once U3 wires it: a real send whose peer ran out of one-time prekeys gets no cover at all — an unpaired real frame — or the send fails.** |
| G2-B | Codex | P3 | **The gate test asserts its own fixtures, not the property it names.** `"no cleartext field is a CONSTANT where a real message varies"` only ever compares default values. Hard-code `previousChainLength = 0`, `mediaType = "text"` or `version = "1"` and every test stays green. Production mirrors all three correctly — the coverage does not prove it. Sharpest instance: **`mediaType = "file"` is the same width as `"text"`**, so the frame-equality postcondition passes while a relay-visible field differs. |
| **G2-C** | **both** | P3 | **The mandated WRITER/READER invariant table still documents deleted state — 18 references.** `counterHighWater`, `deadAirNextFireAtMs`, W3, W4, `DecoyCounterReservation`, allocator uniqueness and locking, the counter reset in `clearAccount`, and the old sampled "640–643 B". |
| G2-D | Grok | P3 | `DecoyAccountProvisioner.kt:329-330` still justifies the unlocked network window by "would stall the counter allocator on the send path". The allocator is gone. The *conclusion* remains correct for other reasons (token writers, provisioner sequences, send-path latency); the *reason given* names a deleted component. |

## Confirmed sound

Remaining lock coverage, five-field codec ordering and strictness, register-before-commit, the
measured frame-length postcondition, and the retargeted nullable-long and stale-snapshot tests — all
verified still discriminating, by Codex, including the specific mutation that the stale-snapshot
retarget still catches the original defect.

## Why G2-C is worse than its severity suggests, and my ruling on it

It is P3 by today's blast radius — nothing reads that table at runtime. But **the table is the
artefact this project's process *mandates* consulting before implementing against durable state.**
A stale paragraph in prose misleads a reader who happens to read it. A stale row in a required
artefact is an **instruction**: U3 and U4 are unwritten, and an implementer following the table
correctly would reconstruct the allocator, re-add `counterHighWater`, and reinstate writers W3/W4 —
precisely what round 2 deliberately removed.

**Ruling: correct the table, do not merely mark it superseded.** The `u2-invariant-table-decision.md`
doc got a supersession header and that was right for a *decision record*, which is historical by
nature. The invariant table is not historical; it is the live contract for the next unit. Strike the
removed fields and writers in place, with the reason, the way the spec's own W3/W4 rows were struck.

**This is the ninth recurrence of the stale-contract class in this feature.** Recorded again because
the pattern is now unambiguous: *the corrections keep landing where the reviewer pointed, and the
parallel copy survives.* The canonical-pointer device fixed it for the tag-write trigger. The
invariant table needs the same discipline — or the same treatment.

## Note on G2-A for the fix

The fix is not just relaxing the `require`. The **whole first-shaped path** assumes field 1 is
present — sizing, offsets, serialization. And the test at `DecoyEnvelopeBuilderTest.kt:676-678`
pins the *wrong* property with an **internally inconsistent fixture** (cleartext `preKeyId = null`
while the ciphertext still carries field 1), so it cannot distinguish "reject garbage" from "reject a
real shape". Both branches need to be constructible from a genuine no-OPK session, not from a
`copy()` of an OPK-present one.
