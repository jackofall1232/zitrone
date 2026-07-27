# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, Unit U1 — **ROUND 3**

Two independent, blind reviewers. You do not see the other's findings.

## Read this first

Round 1 found 10 defects. They were fixed. **Round 2 then found 11 more — and three of those were
introduced by round 1's own fixes.** Round 2's fixes are now in front of you.

Standing rule in this repo, learned over a sixteen-round arc: **a fix is not lower-risk than original
code, and every round of that arc found a real defect the previous fix had missed.** Your job is to
break round 2's work, not to confirm it. **Review the WHOLE UNIT, not the delta** — a previous
release shipped a real defect because reviewers scoped to a fix diff.

### What round 2 changed, and where the new risk sits

Round 2 was told to fix roots, not interleavings, because all three round-1 guards had become
defect sources. It did two structural things:

1. **NEW: `crypto/vault/DecoySectionLock.kt`** — one `ReentrantLock` per live `VaultRuntime`, weakly
   keyed, now taken by `DecoyCounterReservation`, all three `DecoyAuthStore` writers, and the
   provisioner's commit. Declared order: `decoy section lock → runtime.stateLock → session locks →
   storage lock`. *Attack:* deadlock and lock-order inversion against every existing holder of
   `stateLock`; reentrancy; whether the weak keying can hand two callers different locks for the
   same runtime, or leak/collect one while held; whether single reads that deliberately skip the
   lock are actually safe; and the **deliberate** decision to leave multi-second network I/O outside
   the lock — what can change under that window now.
2. **Predicate split**: `isProvisioned()` is gone, replaced by `hasAccount()` (gates registration,
   ignores capacity) and `canSend()` (`hasAccount()` ∧ this session's credential flush confirmed ∧
   `!capacityExceeded`). Flush confirmation is an **instance-scoped** `credentialsUnconfirmed` flag,
   on the argument that anything decoded from disk is durable by definition. *Attack:* is
   instance scope right? A second provisioner instance in the same session, or a caller holding an
   older instance, sees a different answer.

### Two behaviour changes round 2 introduced that NOBODY ASKED FOR — judge them

The implementer flagged both. Treat them as findings-in-waiting, not as settled:

3. **The capacity back-off was INVERTED: it is now written and flushed BEFORE any relay contact, and
   retired only by a successful commit.** The bare-revert branch was deleted. *Consequences to
   judge:* **every** failed attempt now defers 60–90 minutes — offline, a dead session mint, a crash
   between register and commit — not just a 429. Is that acceptable, or does it strand cover traffic
   for an hour on a transient blip? And what happens if the pre-flight back-off write itself fails?
4. **A `TAG_DECOY` section now appears on disk BEFORE any relay contact**, so the 0.9.x-downgrade
   trigger moves from "this vault generated cover traffic" to "this vault *tried* to provision."
   The maintainer explicitly ruled §4.1's disclosure be **narrowed** to "vaults that have generated
   cover traffic". Read §4.1 and decide whether that disclosure is still honest.

### Smaller round-2 changes to attack

5. Codec strictness added: presence byte must be 0/1, absent longs must carry zero, negative
   `counterHighWater` rejected. Can this reject a state an honest earlier 0.10.0 encoder produced?
6. `parsePlaintext` now accumulates into a caller-supplied `PartialDecode` so the catch's wipe is
   observable. Is the accumulator itself always wiped, on every path, including its own failure?
7. `clearAccount()` now nulls both tokens with the id and key in one mutate.
8. The CAS loser in `provisionIfNeeded()` now returns `canSend()` instead of `false`.

### On the tests

Round 2 mutation-tested ten changes and reports all ten observed to fail. **Two required a second
attempt to discriminate** — the first versions passed because a *different* guard was doing the
work. Assume more of the suite has this property. For each test: would it fail if the property it
names were broken, or is some other guard carrying it?

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
