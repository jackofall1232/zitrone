# U2 — ROUND 1 adjudication (Codex + Grok, paired-blind)

Codex: `FINDINGS (2 P1, 0 P2, 2 P3)` · Grok: `FINDINGS (0 P1, 1 P2, 6 P3)`
Union after dedup: **2 P1, 1 P2, 7 P3.**

## Disjoint again — and Grok has now false-negatived a P1 twice

Grok's attack table says **"Byte-level shape vs real `SessionCipher` — Holds"** and does not mention
the random-key defect at all. Codex found two P1s; I verified both, one by measurement.

This is the **second** time in this feature that Grok has certified sound a property Codex correctly
flagged as P1 (the first: U1 round 1, "durable advance before spend"). Recorded as calibration, not
as grounds to drop a reviewer — because in the *same* round Grok found a distinguisher neither Codex
nor the architect saw (G-C below). The pair remains worth its cost; what is not safe is treating
either one's "Holds" as evidence.

## CONFIRMED — must fix

| # | Src | Sev | Defect |
|---|---|---|---|
| **G-A** | Codex | **P1** | **Decoy shape follows the DECOY's counter, not the real message it covers.** After decoy counter 0, a real X3DH first message (976 B) is paired with an ordinary decoy (829 B) — the observer reads off which is real immediately. Reverse pairing fails identically. **`build()` accepts only a block count, so U3 structurally cannot correct this.** Interface defect; must be fixed here. |
| **G-B** | Codex | **P1** | **`typeTaggedRandomKey()` emits `0x05 ‖ random(32)`, which is not a valid Curve25519 public-key encoding.** Genuine keys have bit 255 clear; random bytes set it ~50% of the time. **Architect-measured: 0 of 200 real `Curve.generateKeyPair()` publics had bit 255 set.** So ~50% of subsequent decoys, and ≥75% of first envelopes (two keys each), carry an impossible encoding visible to the relay. The tests exclude all 32 key bytes from the structural diff, which is why nothing caught it. |
| **G-C** | Grok | **P2** | **`message_number` is a JSON number — its DIGIT WIDTH changes the frame size.** Same block count, different frame length (`5` vs `128` differs by 2 bytes). Neither the architect nor Codex identified this. It falsifies §2.2's normative promise of "two identical-size frames" independently of G-A. |
| G-D | both | P3 | Stale numbers throughout the spec: `+39 B`, 821, 1161 still stated at `:128`, `:190-193`, `:280`, `:342-348`, and §3.3 still mandates 821 B for the dead-air ping. Corrected in the table, not swept. **The paraphrase class, on the architect's own document, for the eighth time.** |
| G-E | Grok | P3 | A kdoc names a non-existent `DecoyIdentityTest`. |
| G-F | both | P3 | Evidence claims "14 gate tests"; there are 13 `@Test` methods (and the suite delta 678→691 is 13). |
| G-G | Grok | P3 | `blockCount` unbounded — Int overflow can wrong-size the body. |
| G-H | Grok | P3 | `registrationId` allows `0`; the real generator emits `[1, 16380]`. |

## Confirmed sound (do not re-litigate)

`prekey_id = 1` — both reviewers verified independently against `ConsumeOneTimePrekey`
(`ORDER BY prekey_id LIMIT 1`) and the 1..100 batch. The 323 B / 432-char / single-`=` figures, the
counter varint transitions at 128 and 16384, the +81 B PreKey wrapper and +147 B frame delta: all
confirmed by Grok's independent measurement. No counter reuse or regression path found. Deniability
surface clean.

## ARCHITECT'S RULING on G-A + G-C — the interface, and where to absorb the difference

They are one problem: **the builder is being told the wrong thing.** `build(blockCount)` cannot
produce a frame that matches a real one, because frame length depends on shape *and* on counter
magnitude, neither of which a block count carries.

**Ruling 1 — the interface changes.** `build()` must take **the real envelope it is covering** (or a
descriptor carrying every size-affecting property of it) and mirror that. Block count alone is
insufficient and no amount of care inside the builder can compensate for an input that lacks the
information.

**Ruling 2 — compensate for digit-width in the random ciphertext.** The decoy's own counter must stay
monotonic and unrepeatable, so its digit width cannot be chosen freely. Absorb the difference by
adjusting the length of the random ciphertext.

The justification matters, because it looks wrong at first glance: **a network observer sees only the
total TLS frame length. It cannot see the internal split between `ciphertext` and the other JSON
fields.** So "ciphertext length is plausible for this counter" is *unobservable* to the threat model
this feature defends against, while "total frame length matches" is *directly* observable. When the
two conflict, the observable one wins.

**The cost, stated honestly:** the relay *can* see the split and could notice a ciphertext whose
length is implausible for its counter. That is acceptable and already in scope — §1 concedes decoys
do not defend against the relay operator, for reasons far more fundamental than this (cleartext
`sender_id`/`recipient_id`). It must nonetheless be **written down in §2.4** alongside the
control-channel gap, not left implicit.

## The pattern this unit has now demonstrated three times — put it in `failures.md`

**"Random bytes are indistinguishable" is true only where the real field is uniformly distributed.**
It has failed three times in this feature, each time in a different field:

1. **Length** — a random-length blob gave a base64 padding signature (`==` vs `=`).
2. **`previous_chain_length`** — real traffic is always `0`; anything else is the tell.
3. **Key material** — real Curve25519 encodings have bit 255 clear; random bytes are invalid half
   the time.

Before substituting random bytes for any field, **characterise the real field's distribution first**.
The instinct is only sound for genuinely opaque, uniformly-distributed values — and in an envelope
built for a structured protocol, almost nothing is.
