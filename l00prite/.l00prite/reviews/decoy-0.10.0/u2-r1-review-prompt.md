# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U2 (decoy envelope builder)** — ROUND 1

Two independent, blind reviewers. You do not see the other's findings. **Guilty until proven
innocent.** Review the **WHOLE UNIT**, not a delta — a previous release shipped a real defect because
reviewers scoped themselves to a fix diff.

## What U2 is

U1 (merged) provisions a synthetic relay account per vault and stores its credentials in a new
`TAG_DECOY` vault section. **U2 builds the decoy envelope**: given a block count, produce a
`message.send` envelope **byte-for-byte indistinguishable from a real one of the same block count**.

Branch `feat/0.10.0-decoy-u2-envelope-builder`, based on `main` @ `2cd82a2b`.
See it with `git diff main..HEAD`.

New/changed: `apps/android/.../decoy/DecoyEnvelopeBuilder.kt`, `DecoyIdentity.kt`, plus tests.
**U2 is deliberately UNWIRED** — nothing constructs the builder in production.

## THE ENTIRE POINT OF THIS UNIT

**Any field, length, or byte pattern in which a decoy differs from a real message is a total defeat
of the feature.** Cover traffic that is identifiable is worse than none — it marks precisely the
traffic it was meant to hide, and it tells an observer the user believed they were protected.

The implementation already found that the **architect's own spec formula was wrong in exactly this
way**: §2.3 specified a 316-byte ciphertext; a real single-block libsignal `SignalMessage` is
**323 bytes**. 316 base64s to 424 chars ending `==`; 323 to 432 ending `=`. Every decoy would have
carried a base64 padding signature no real message has. **Assume more of this class remains.**

## Attack these specifically

1. **Byte-level equivalence.** Construct a real envelope through the real path
   (`SignalProtocolManager` / `SessionCipher`) and a decoy of the same block count, and **diff them
   field by field and byte by byte.** Lengths, base64 padding, JSON key order, null-vs-absent,
   integer widths, version bytes, protobuf field order and varint widths.
2. **Length as a function of value.** The implementation reports that `message_number` is a protobuf
   **varint**, so the ciphertext length changes once the counter crosses 128 (and again at 16384).
   Does the builder track that, or does it produce a fixed length that diverges from real traffic as
   the counter grows? What about `prekey_id` and any other varint-encoded field?
3. **The X3DH first envelope.** Claimed `PreKeySignalMessage` wrapper overhead is **+147 B**, not the
   +39 B the spec said. Verify. Is the first envelope's size, shape and field set right?
4. **`prekey_id` = 1.** The claim: it is the RECIPIENT's one-time prekey id; the synthetic account
   uploaded ids 1..100 and has consumed none, so the relay would issue **1**. Verify that reasoning
   against `ConsumeOneTimePrekey` and the bundle-generation code. Is 1 correct, or is it a constant
   where real traffic varies — the exact defect class the existing web generator has?
5. **What the builder reads.** It spends counters through U1's `DecoyCounterReservation`. Any way it
   can reuse, regress, or skip-then-reuse a counter? Any way it can be called such that two decoys
   share a `message_number`?
6. **Deniability, unchanged from U1.** No device-level storage, no logging, no slot/vault-index
   naming, nothing decoy-related outside the sealed region.

## On the tests — read this before trusting any of them

The implementation ran 16 mutations and reports all 16 discriminating, **but only after one did not**:
setting `previous_counter` to 1 was caught by nothing, because it is a one-byte varint at either
value (no length test sees it) and libsignal's Java API exposes `getCounter()` but not
`getPreviousCounter()` (no parse-back test reaches it). It was fixed with a structural byte-diff.

**Assume more blind spots of that shape exist**: properties that are neither length-visible nor
reachable through the library's public API. For every test ask — *would this fail if the property it
names were broken, or is another guard carrying it?*

Also reported: the mutation harness restored source without rebuilding, once producing a phantom
failure from stale classes. Treat any claimed mutation result as a claim, not evidence.

## Contracts are in scope

U1's review found **seven consecutive rounds** of documentation drifting from behaviour. `VaultState.kt`'s
codec kdoc is now the **CANONICAL** statement of when `TAG_DECOY` lands on disk — check nothing
restates it. The spec corrections U2 applied are marked *pending ratification*; read them against the
code and say whether they are right.

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
