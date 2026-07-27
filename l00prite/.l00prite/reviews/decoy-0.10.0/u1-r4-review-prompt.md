# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, Unit U1 — **ROUND 4**

Two independent, blind reviewers. You do not see the other's findings.

## State of this unit

Round 1: 10 defects. Round 2: 11 more, three introduced by round 1's own fixes. Round 3: 10 more,
**zero P1s**, and for the first time both blind reviewers independently found the same top three.
Round 3's fixes are now in front of you.

The structural design has held for two rounds — the per-runtime section lock and the predicate split
were probed by both reviewers in round 3 and neither broke them. **That is exactly when to be most
suspicious**: the remaining defects will not be design flaws, they will be places the pattern was
applied incompletely, contracts that drifted from behaviour, or tests that stopped discriminating
when something else changed. Standing rule: **a fix is not lower-risk than original code.**

**Review the WHOLE UNIT, not the delta.**

### What round 3 changed, and where to press

1. **`DecoyAccountProvisioner` constructor is now private; `forRuntime()` is the only entry.** The
   one-attempt latch and `credentialsUnconfirmed` moved into a per-runtime `Gate` in a weakly-keyed
   registry (the **third** such registry, alongside allocators and section monitors).
   *Press:* three parallel weak registries keyed on the same runtime — lifetime, collection, and
   whether they can disagree about which runtime is live. Can a `Gate` outlive or under-live its
   runtime? Can two callers get different gates?
2. **DELIBERATE DEVIATION, flagged by the implementer for your judgment:** `forRuntime` returns a
   **new instance sharing the runtime's gate**, rather than a cached instance the way the allocator
   does. The argument: the provisioner's collaborators are per-attempt (per-attempt staging store,
   injected clock), so a cached instance would bind a later caller to an earlier attempt's staging
   store and clock. *Judge whether sharing guard state but not collaborators actually gives the
   uniqueness guarantee*, or whether it leaves a gap the allocator's caching would have closed.
3. **The pre-network back-off is now retired on failures that spent nothing.** `reserveBackoff()`
   returns the deadline it wrote; `clearBackoff(deadline)` compare-and-clears it under the section
   lock. *Press:* the compare-and-clear — can it clear a deferral written by someone else, or fail
   to clear its own? What happens across a crash between write and clear, or between register and
   the decision not to clear?
4. **The spent/not-spent discriminator is set immediately BEFORE `register`**, on the reasoning that
   a `register` that throws may still have created the account, so "may have spent" counts as spent.
   *Press this hard* — it is the hinge of the whole registration-budget argument. Is there any path
   that spends a registration while the discriminator says otherwise, or vice versa?
5. **`storeTokensForAccount(accountId, …)`** re-reads and compares the account id under the section
   lock and refuses a mismatch. *Press:* is the compare actually atomic with the write, and can a
   token-only section still be materialized by any route?
6. Smaller: version check moved inside `parsePlaintext`'s `try`; `require(counterHighWater >= 0)` in
   the encoder; `provisionNotBeforeMs` kdoc rewritten; two test assertions corrected.

### Contracts and docs are in scope, not just code

Round 3 found a **false disclosure** in `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md` §4.1 and a stale
kdoc that described removed behaviour — a class this repo records as having recurred three times.
§4.1 now carries wording marked **"ADJUSTED — PENDING MAINTAINER RE-RATIFICATION"**. Read it against
what the code actually does now and say whether it is true. A disclosure that overstates harm is as
much a defect as one that understates it.

### On the tests — a specific warning from round 3

When the latch moved from instance scope to runtime scope, **four tests silently stopped
discriminating**: they modelled "a later session" as a fresh provisioner over the same *live*
runtime, which now shares the burned latch, so the latch — not the property each test named — was
carrying them. They were rebuilt to open a genuinely new runtime from the on-disk image.

Assume more of the suite has this shape. For every test: **is the property it names what actually
makes it pass, or is some other guard carrying it?** The implementer reports 12 mutations all
observed failing; treat that as a starting point, not a guarantee.

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
