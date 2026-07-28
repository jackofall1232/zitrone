# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U3 (send pairing)** — ROUND 2

Two independent, blind reviewers. You do not see the other's findings. **Review the WHOLE UNIT.**

## What happened since round 1, and why this round is different

Round 1 found **4 P1s, 2 P2s, 4 P3s** — every P1 a violation of the absolute requirement that a real
send is never harmed by cover traffic. The analysis then reached a **design contradiction** and the
implementer stopped: on a decoy-first send there are exactly three places the timing gap can sit, and
**all three break something**. There is no fourth position — decoy-first had no correct
implementation, not merely a worse one.

**The maintainer ruled: REAL-FRAME-FIRST, ALWAYS. Random ordering is conceded.** Round 2 implemented
that as a **deletion**, not a repair. The whole mechanism is now:

```kotlin
publish()                                   // first statement: no try, no guard, no suspension before it
val decoy = coverFor(cover) ?: return
try { sleep(gapMs()) } finally { emit(decoy) }
```

**Deleted:** the pairing mutex (its only caller was `paired`), the order bit, three `decoyFirst`
branches, two completion latches, and round 1's nested `finally`.

**This is a removal round, and removals are where reviewers under-look.** A deleted guard can silently
un-assert a property that was never the thing being deleted. Attack the deletions at least as hard as
you would attack new code.

## The claims to attack

1. **"Impossible by construction" — verify or refute each.** The claim is that four round-1 P1s are
   now *structurally* impossible, not merely unlikely:
   - **process death mid-pair** — because a coroutine can only die at a suspension point, and there is
     now exactly one, strictly *after* the socket handoff;
   - **a `deleteContact` interleaving** — because no suspension exists between the durability flush and
     the send tail any more;
   - **self-preemption of the send rate limit** — because the real frame is enqueued first;
   - **cancellation skipping the real publish** — because `publish()` precedes every `try`.
   **"Merely unlikely" instead of "impossible" is a finding.** That distinction is what the ruling
   turned on.
2. **The lock's removal.** Both its justifications are claimed to have been decoy-first artefacts, and
   `paired` its only caller. **Verify it has no remaining caller and that nothing depended on it** —
   ordering between concurrent sends, exclusion against teardown, anything. A newly-stated claim:
   *"the true bound on a concurrent send's wait is now zero."*
3. **Concurrent pairs now interleave on the wire.** Nothing serialises them. Declared harmless.
   Is it? Consider two sends whose gaps overlap, and what an observer sees.
4. **`SecureRandom` is now load-bearing differently.** The gap is the *only* drawn quantity left and is
   directly observable. The claim: a weak PRNG would become a fingerprint capable of **linking two
   vaults' traffic** — a deniability break. Verify that reasoning and the implementation.
5. **The coalescing question, demoted with a derivation.** The 5 ms floor separates two *calls*;
   OkHttp owns the writes. The new argument is that with a fixed order a coalesced pair is one record
   of exactly 2× the frame length, which "says what two frames say and names no conversation" — the
   equal-length property is about the halves being indistinguishable *from each other*, and a
   coalesced pair has no halves. **Cosmetic, not a leak.** Is that derivation sound?
6. **The `finally` that survived.** Kept on the argument that an unpaired real frame is a *marked*
   frame, and cancellation (vault lock, teardown, backgrounding) is frequent enough that dropping the
   cover frame would mark a recognisable class of sends. Verify.
7. **Everything from round 1 still in scope:** R-U3-1 absolutely; the registration budget now that
   provisioning is reachable; deniability surface; teardown.

## Explicitly OUT of scope
- **Cross-send `sendLimit` preemption** — ruled relay-side only; a client-side defence was shown
  *unsound* because `sendLimit` is a server constant the relay never communicates.
- **`onServerError` being empty** — a live defect in shipped code, tracked separately.

## On the tests

15 mutations run, all killed, and **all 20 tests killed by at least one** — so nothing is inert. A new
lag-1 autocorrelation assertion catches gap reuse that passes support, bound and mean. **Assume blind
spots remain**: for every test, is the property it names what makes it pass, or is another guard
carrying it?

## Contracts are in scope
`DecoyEnvelopeBuilder` is canonical for construction; `VaultState.kt`'s codec kdoc for the tag-write
trigger; `DecoyState`'s kdoc for the `TAG_DECOY` field set. **Nothing may restate them.** This class
of defect — a stale parallel copy of a claim — has now recurred **thirteen times** on this feature,
twice in the ruling commit itself. Check §4.3 and §5 against what the code actually does.

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
