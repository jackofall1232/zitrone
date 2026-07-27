# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U2** — ROUND 2

Two independent, blind reviewers. You do not see the other's findings. **Review the WHOLE UNIT, not
a delta.**

## Two rounds of change since you last saw this, and the second was a REMOVAL

**Round 1 (review-driven)** fixed two P1s: the builder now takes the real `MessageEnvelope` it covers
and mirrors every size-affecting property (frame equality is a **checked postcondition that throws**,
not prose); and `0x05 ‖ random(32)` was replaced with a real `Curve.generateKeyPair()` public, private
half discarded, because random bytes are not a valid Curve25519 encoding — genuine keys have bit 255
clear and random bytes set it ~50% of the time.

**Round 2 (scope-driven) DELETED code.** The maintainer cut the idle/dead-air ping from the design
entirely. Consequently `DecoyCounterReservation` and its 14 tests are gone, and `TAG_DECOY` lost
`counterHighWater` and `deadAirNextFireAtMs`. Paired decoys mirror the covered envelope's
`message_number`, so nothing allocates counters any more.

**Removals are where reviewers under-look.** A deleted test can silently un-assert a property that
was never the thing being deleted. Attack the removal as hard as you would an addition.

## Specific things to attack

1. **The frame-equality postcondition.** `build()` measures both `message.send` frames and throws on
   mismatch. Can you construct inputs where it passes but the frames still differ to an observer, or
   where it throws on a legitimate pair? Does it cover *every* size-affecting field, or only the ones
   round 1 happened to think of? Timestamp width, ttl, burn flag, media type, version, id widths.
2. **The claim that `DecoySectionLock` still earns its place.** It was introduced partly for the
   now-deleted allocator. The argument is that `DecoyAuthStore`'s four writers and the provisioner's
   compare-and-clear and read-commit-revert are genuine multi-call sequences needing it. **Verify
   that** — and check the lock's remaining callers are *complete*: any sequence that should take it
   and does not is a re-opened TOCTOU.
3. **What the deleted tests were also asserting.** Two negative-counter-mark tests are gone; the
   implementer declares the *encode/decode symmetry principle* they demonstrated now rests solely on
   the credential half-set pair. Is that sufficient, or did coverage silently narrow?
4. **Retargeted tests.** Two nullable-long canonicity tests moved from the counter field to
   `provisionNotBeforeMs`; a concurrency test's writer moved from the allocator to a direct section
   write. Do they still discriminate *for the property they name*, or only for the mechanics they
   happen to touch?
5. **Codec after field removal.** `TAG_DECOY` is now `accountId ‖ identityKeyPair ‖ accessToken ‖
   refreshToken ‖ provisionNotBefore`. Field-order, offsets, `isEmpty`, round-trip fidelity,
   truncation, duplicate tags, trailing bytes. `0x06` has never shipped so there is no migration —
   confirm nothing assumes otherwise.
6. **Everything from round 1's brief still applies**: byte-level equivalence against real
   `SessionCipher` output, varint length transitions at 128/16384, the X3DH first-envelope shape,
   `prekey_id`, deniability surface.

## A measurement caution, learned the hard way this round

The implementer reported that **an encoded-size figure measured after DEFLATE over freshly generated
key material is a distribution, not a value** — five runs spread 636–646 B, and previously-recorded
"640–643 B" and "645 B" point values were quoting that noise as precision. If you cite a size, say
whether it is deterministic (raw body) or sampled (post-compression).

## On the tests

Round 1 found a mutation that discriminated *nothing* — `previous_counter` set to 1, invisible to
length tests and unreachable through libsignal's Java API. Assume more blind spots of that shape.
For every test: would it fail if the property it names were broken, or is another guard carrying it?

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
