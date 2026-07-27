# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, Unit U1 — **ROUND 2 (fix delta + whole unit)**

You are one of two **independent, blind** reviewers. You do not see the other's findings.

## Read this before anything else

Round 1 found **ten confirmed defects** in this unit. They have been fixed. **Your job is not to
confirm the fixes — it is to break them.**

Standing rule in this repository, learned across a sixteen-round arc: **a fix is not lower-risk than
original code, and every single round of that arc found a real defect the previous fix had missed.**
Clean convergence on an earlier delta does not carry forward. Treat every round-1 fix as a fresh,
unreviewed change by an author who was under pressure to make a failing property pass.

**Review the WHOLE UNIT, not the fix delta.** A previous release shipped a real security defect
because reviewers scoped to a fix diff and never re-read the original unit. Round-1 fixes are listed
below only so you know where new risk was introduced — not to bound your scope.

### What round 1 changed, and the new risk each change carries

The root cause was that `VaultRuntime.mutate` only **schedules** a reseal; `flushBeforeAck()` is the
durable path. Fixes:

1. **Counter reservation now flushes before spending** (`mutate` → `flushBeforeAck` → advance RAM
   cursor). *New risk:* a disk-bound flush now runs while the allocator's own lock is held. Lock
   ordering, blocking, reentrancy, and what happens when the flush throws.
2. **Single-allocator-per-runtime enforced structurally** via a `forRuntime(runtime)` factory backed
   by a weak map, plus a per-call staleness check that abandons a block if the durable mark no longer
   equals the block's end. *New risk:* map thread-safety under concurrent construction; whether the
   staleness check can churn reservations unboundedly or skip so aggressively the counter space is
   burned; whether "abandon the block" can ever *reissue* rather than skip.
3. **`isProvisioned()` now also requires `!runtime.capacityExceeded`.** *New risk:* the flag is
   runtime-wide, so an unrelated overflow reports "not provisioned" while durable credentials exist.
   The author judged this the safe direction — decide whether it is.
4. **Capacity failure now REVERTS the retained mutation and writes a durable back-off in one
   mutate.** *New risk:* this is a new writer performing a revert. Does it restore exactly the prior
   state? Can it revert or clobber a concurrent unrelated mutation? Is the back-off itself durable?
5. **The one-attempt latch moved** to immediately before the relay sequence. *New risk:* can the
   latch now be taken and then abandoned without an attempt, or taken twice?
6. **`clearAccount()` now resets `counterHighWater`.** *New risk:* interaction with a live allocator
   holding a RAM block across the reset.
7. **Prekey private halves: NOT fixed**, with the claim that libsignal holds them in Rust-owned
   memory with no `close()`/`destroy()`, so they cannot be zeroed from Kotlin. **Verify or refute
   this claim against the actual libsignal API** — if it is wrong, that is a P2.
8. **Tests rewritten** to decode the persisted sealed payload rather than live state. *New risk:*
   do they now pin the property, or merely look like they do? The author claims each was run against
   a deliberately broken implementation and observed to fail — check whether the assertions actually
   discriminate.

Also verify: **the durability claim itself.** Does every path whose correctness depends on surviving
process death now actually flush, and does each treat a flush throw as "it never happened"?

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
