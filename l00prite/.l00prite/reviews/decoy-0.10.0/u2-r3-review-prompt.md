# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U2** — ROUND 3

Two independent, blind reviewers. You do not see the other's findings. **Review the WHOLE UNIT.**

## Trend, so you calibrate correctly

Round 1: 2 P1, 1 P2, 7 P3. Round 2: 0 P1, 1 P2, 4 P3. **The reviewers have been disjoint on the top
finding all three rounds** — each round's most consequential defect was found by one reviewer and
missed by the other. Do not assume the other reviewer has covered anything.

Findings are falling, which is when rubber-stamping becomes the risk. It is also when manufacturing
P3s to look diligent becomes the risk. **`VERDICT: CLEAN` is a legitimate and useful outcome.**

## What round 3 changed

**The headline fix (G2-A).** A real first message may carry `ephemeral_key` set with **`prekey_id`
null** — signed-prekey-only X3DH, reached when the peer's one-time prekeys are exhausted. The builder
could not represent it: a `require` asserted the two fields appear together or not at all, and the
whole first-shaped path assumed protobuf field 1 was present.

Measured against libsignal 0.46.0 before changing anything: a no-OPK first ciphertext is
`0x34, 0x12, 0x21, 0x05…` at **402 B**; OPK-present is `0x34, 0x08, id, 0x12, 0x21, 0x05…` at
**404 B**. Field 1 is skipped entirely and the base key starts at offset **3** rather than 5.

Four sites changed: the `require` became the implication `preKeyId != null ⇒ ephemeralKey != null`;
`preKeyWrapperFixedBytes` charges nothing for an absent field; `preKeySignalMessageBytes` omits it;
`baseKeyOffset` shifts. **Attack all four**, and attack the boundary between them — off-by-two in an
offset is exactly the kind of thing that passes a length check and fails a byte-diff.

**Also:** the gate test now varies `mediaType` (including `file`, the same width as `text`),
`previousChainLength` and `version`, which it previously never did. The `require` at
`DecoyEnvelopeBuilderTest.kt:676-678` was **deliberately relaxed** — `real.copy(preKeyId = null)` no
longer throws, because the builder mirrors the *cleartext* envelope and never decodes the ciphertext
by design. **Judge whether that relaxation is right**, or whether a real guard was lost.

## Specific things to press

1. **The no-OPK path end to end.** Is it byte-identical to a real no-OPK envelope, or merely
   length-equal? Construct one through a genuine `PreKeyBundle(…, -1, null, …)` and diff.
2. **The two shapes' interaction with frame equality.** OPK-present and no-OPK differ by 2 bytes.
   Cross-product: real no-OPK covered by a decoy built for OPK-present, and vice versa.
3. **`preKeyId` present but `ephemeralKey` null.** Claimed unreachable from any encoder, therefore
   tested via a mutated fixture. Is it genuinely unreachable? If a real path can produce it, the
   guard is wrong in the other direction.
4. **Everything from earlier rounds still applies**: byte-level equivalence against real
   `SessionCipher` output, varint transitions at 128/16384, `prekey_id` value, the section lock's
   remaining callers, the five-field codec, deniability surface.

## Contracts are in scope, and one was found to be the ROOT of a code defect

Round 3 established that the false claim behind G2-A **originated in the spec** —
`DECOY_TRAFFIC_0.10.0_SPEC.md` §2.2 stated that a real first envelope carries both fields non-null.
The code was faithfully implementing a false document. It has been struck at the source.

`DecoyState`'s kdoc in `VaultState.kt` is now **canonical** for `TAG_DECOY`'s field set; the invariant
table and spec §4 are derived and say "on disagreement this file wins". The invariant table was
corrected in place for the round-2 deletions. **Check for any remaining claim, anywhere, that
contradicts the code** — this class has recurred nine times in this feature.

## On sizes

Any figure measured after DEFLATE over freshly generated key material is a **distribution, not a
value** — three fresh runs gave 635/641/645 B, and a previously recorded "640–643 B" interval was
already outside it. If you cite a size, say whether it is deterministic (raw body) or sampled.

## Project

Zitrone is a production Signal-Protocol E2E messenger whose headline guarantee is a
**plausible-deniability second vault**: two independent vaults (slot A / slot B) behind one
ordinary PIN/passphrase unlock screen, plus a "Pucker Burn" duress credential. The adversary to
assume throughout:

- **Physical device + forensics + many forced/observed unlocks.** May compare an A-session against a
  B-session looking for ANY distinguisher — on disk, in timing, in prompts, in logs, in file sizes.
- **A hostile relay operator** who sees every message envelope's cleartext fields.
- **A passive network observer** who sees TLS frame sizes and timings only.
- Assume **crash, process death, or rotation at ANY instruction**.

The vault's durable state is one sealed, **fixed-size** AEAD region per slot. Its plaintext is a
single `VaultState` encoded as TLV-over-DEFLATE. If anything about the encrypted image varies with
what a vault *contains*, deniability is broken.

## What U1 is

0.10.0-beta adds **decoy (cover) traffic**. Each vault gets its own **synthetic relay account** that
decoys are addressed to, so no real contact needs decoy-recognition logic. U1 is the first unit: it
provisions that synthetic account and stores its credentials in a **new `TAG_DECOY = 0x06` section**
of `VaultState`. **U1 is deliberately UNWIRED** — nothing constructs it yet; sending is U2/U3.

**Branch: `feat/0.10.0-decoy-u1-provisioning` (checked out). Base: `a4f118df` on main.**
See the whole unit with: `git diff a4f118df..HEAD -- apps/`

## SCOPE — read this carefully

**Review the WHOLE UNIT, not a delta.** A previous release shipped a real security defect precisely
because reviewers scoped themselves to a fix diff and never re-read the original unit. Every line of
these files is in scope, including code that was not the "point" of the change:

- `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultState.kt` (the codec — `TAG_DECOY`, `DecoyState`, encode/decode/wipe)
- `apps/android/app/src/main/java/com/zitrone/app/data/DecoyAuthStore.kt`
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyIdentity.kt`
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyRelayApi.kt`
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyAccountProvisioner.kt`
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyCounterReservation.kt`
- All five test files under `apps/android/app/src/test/java/com/zitrone/app/` added by this unit.

**Also in scope: the tests themselves.** A test that passes while asserting nothing is a defect. Ask
of each: *would this test still pass if the behaviour it claims to pin were broken?*

## Required reading before you judge

1. `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md` — the approved spec. §2.3 (counter reservation),
   §4 (the WRITER/READER invariant table), §4.2 (account deletion), §6.2a (registration budget).
2. `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md` — the WRITER/READER table built
   before the code. **Attack this too.** If a row is wrong, or a writer/reader is missing from it,
   that is a finding.
3. `docs/VAULT_ARCHITECTURE.md` §3–§8 for the deniability model.

## The invariants to attack

Do not treat this as a checklist to confirm. Treat each as a claim to falsify.

1. **Register-before-commit ordering.** The synthetic account must be registered on the relay
   *before* its credentials are committed to `VaultState`. A crash or failure anywhere must leave an
   **orphaned relay account** (inert, acceptable) and never a `VaultState` referencing an account
   that does not exist, and never a persisted account id with no usable signing key. Enumerate every
   crash point and say what state each leaves.
2. **Counter reservation: skip, never regress.** `message_number` values are reserved 64 ahead and
   spent from RAM. A crash may skip values; it may **never** reuse or regress one, because a real
   Double Ratchet never does and a regression is a fingerprint. Can you construct a sequence — crash,
   concurrent mutate, session close, re-unlock, reservation exhaustion at a boundary — that reissues
   or regresses a counter?
3. **Key material.** The section holds a **raw private key**. Every path must *zero* it, not merely
   drop the reference — including on decode failure, on encode failure, on capacity overflow, on
   OOM, and on close. Is there any path where key bytes survive in the heap, or where a buffer is
   grown/copied leaving an un-zeroable original?
4. **Deniability — the highest-severity class.** Nothing about decoy state may be observable outside
   the sealed region. No device-level storage (`SharedPreferences`, `SettingsRepository`,
   `DeviceSettings`), no logging, no diagnostics, no slot/vault-index naming, no timing or size
   difference between a vault that has decoy state and one that does not. **Does the encrypted image
   change size or shape based on decoy content?** Does anything let an adversary count vaults or
   distinguish A from B?
5. **Strict-v1 codec correctness.** An unknown tag throws by design (never skipped). The section is
   *omitted entirely* when empty, so that a vault which never generates cover traffic stays readable
   by 0.9.x. Is `isEmpty` correct for every partially-populated state? Can a section be written that
   round-trips to something different, or that a decoder accepts as valid but means something else?
   Duplicate tags, truncation, length overruns, integer overflow in bounds checks, trailing bytes.
6. **Capacity.** Encoding must not exceed `MAX_PAYLOAD_CONTENT_BYTES`. Overflow sets
   `capacityExceeded`, which fail-closes `flushBeforeAck` — so an overflow is a **durability** bug,
   not a cosmetic one. Is the measured budget (claimed 640–643 B worst case against a 1024 B budget)
   actually worst-case? What input maximizes it?
7. **Mutation discipline and locking.** All durable writes go through `VaultRuntime.mutate`. Lock
   order is `runtime.stateLock → session locks → storage lock`, and a runtime method must never be
   called from inside a persist sink. Any reentrancy, any lock inversion, any escaping reference to
   a live-state array handed out beyond the lock (a use-after-wipe class that has bitten this
   codebase before)?
8. **Presence ≠ readiness.** A durable 429 back-off field means the section can exist while holding
   no credentials. Every reader must key on the credential pair, never on section presence. Find any
   reader that gets this wrong.
9. **Registration is a scarce shared global resource.** `registerLimit` is 300/hour keyed on a
   single global bucket. Provisioning must be lazy, must back off across sessions on 429, and must
   degrade **silently** to decoys-off — never blocking onboarding, never surfacing an error implying
   a fault. Can it hot-loop, retry tightly, or spend more than one registration per vault?

## Output format

For each finding:

- **Severity**: P1 (data loss / key leak / deniability break / unauthorized destroy) · P2 (real
  defect, bounded blast radius) · P3 (correctness nit, doc/test gap)
- **File:line**
- **The concrete failure**: exact inputs, interleaving, or crash point → the wrong outcome. Not "this
  could be racy" — show the sequence.
- **Why the existing tests do not catch it.**

End with an explicit verdict line: `VERDICT: CLEAN` or `VERDICT: FINDINGS (n P1, n P2, n P3)`.

**Resolve every claim against the source in this tree.** Do not report what the code probably does.
If you cannot verify something, say so rather than asserting it. A confident wrong finding costs
more than a missed one, because it burns a fix round.
