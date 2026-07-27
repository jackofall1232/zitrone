# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, Unit U1 — **ROUND 5 (final paired-blind round)**

Two independent, blind reviewers. You do not see the other's findings.

## State of this unit — read before you calibrate

| Round | Findings | P1 | Reviewer agreement |
|---|---|---|---|
| 1 | 10 | 2 | fully disjoint |
| 2 | 11 | 1 | 2 of 11 |
| 3 | 10 | 0 | top 3 independently |
| 4 | 6 | 0 | top 2 independently, same remedy |

**This is round 5 of a hard cap of 6.** After this round the unit either goes to a maintainer merge
decision or to a third-lens tie-break. Your verdict carries more weight than in earlier rounds.

Two opposite failure modes to avoid:
- **Rubber-stamping.** Findings are falling, so the temptation is to confirm. Every round so far has
  found real defects, including three introduced by the previous round's own fixes. A fix is not
  lower-risk than original code.
- **Manufacturing.** Do not invent P3s to look diligent. A confident wrong finding costs a whole fix
  round. **`VERDICT: CLEAN` is a legitimate and useful outcome if the unit is clean** — say so
  plainly rather than padding.

**Review the WHOLE UNIT, not the delta.**

### What round 4 changed

1. **`registrationSpent = true` hoisted above its own argument list.** Was
   `registrationSpent = true; relay.register(generateBundle(identity), proof)` — Kotlin evaluates
   the argument after the flag, so a purely local bundle failure counted as a spent registration.
   Now the bundle is a separate statement above the flag. A `bundleFactory` seam was injected to
   make the failure testable at all.
   *Press:* is the flag now correct on **every** path — cancellation, a `register` that throws after
   partial transmission, a lost response, retry?
2. **`requireDecoyCredentialsPaired` in both `encodeDecoy` and `decodeDecoy`** — rejects id-without-key,
   key-without-id, tokens-without-id. Claimed to be an assertion, not a repair, on the basis that
   every writer was verified unable to produce an unpaired state.
   *Press:* find a writer that can. Also: can this `require` now throw somewhere the old code
   succeeded, turning a recoverable state into a failed unlock or a failed flush?
3. **A broad documentation sweep** — spec §4.1, §4 blast-radius block, §4 WRITER table (new W1d),
   §6.2a, the invariant table's field/writer columns and crash matrix, the `VaultState` codec kdoc,
   `DecoyAccountProvisioner` comments, `DecoyState` kdoc.
   *Press:* **read the contracts against the code, not against each other.** Three of five findings
   last round were prose that had drifted from behaviour, and the same class has now recurred four
   times in this unit. Any remaining doc that describes behaviour the code no longer has is a
   finding.

### Specific things the implementer flagged for your judgment — do not take these on trust

- **§4.1's first clause is admitted to be loose.** The sentence reads "once a vault has set up cover
  traffic — which happens the first time it sends any, and is complete as soon as its cover-traffic
  account is registered". The implementer notes the *operative* clause is the second one, and that a
  vault which attempts a first send and reaches `register` gets the tag without ever sending.
  **Decide whether the sentence as a whole is true**, and whether a user could be misled by the
  first clause. This sentence has now been rewritten three times; each rewrite was itself found
  wrong by a later round, once for understating and once for overstating.
- **The J1 test passes for a trivial reason.** It is claimed to pass against a correct
  implementation because *any* pre-`register` throw retires the deferral; the discriminating
  mutation is the flag placement, not "make the bundle throw". Judge whether the test pins what it
  names.

### On the tests

Round 4 ran three mutations, each observed failing exactly one test. Earlier rounds found tests that
stopped discriminating when something *else* changed scope. **For every test: is the property it
names what actually makes it pass, or is another guard carrying it?**

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
