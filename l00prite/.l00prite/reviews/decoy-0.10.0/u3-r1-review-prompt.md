# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U3 (send pairing)** — ROUND 1

Two independent, blind reviewers. You do not see the other's findings. **Guilty until proven
innocent.** Review the **WHOLE UNIT**, not a delta.

## Why U3 is the highest-stakes unit so far

U1 (provisioning) and U2 (envelope builder) both shipped **deliberately unwired** — no cover traffic
could be emitted and no registration could be spent. **U3 wires it.** After this unit a real device
emits real cover traffic and can spend a registration from a rate limiter shared by every user
worldwide. Everything U1 and U2 got right is only worth something if U3 is right.

It also modifies **`MessagingCoordinator`**, the send path 0.9.x spent weeks hardening, and inserts
into the tail of every outbound send.

Branch `feat/0.10.0-decoy-u3-pairing`, based on `main` @ `4438cd72`. `git diff main..HEAD`.

## What it must be observably true of (spec §4.3 is authoritative — read it)

- **R-U3-1, ABSOLUTE:** a real send is never blocked, failed, materially delayed, **reordered**, or
  made less durable by cover traffic. `flushSendRatchet` and its ordering vs `ws.sendMessage`
  unchanged. Every other requirement loses to this one.
- **R-U3-2:** a covered send is two frames of the **same serialized length**, in an order the observer
  cannot predict, separated by a per-send gap.
- **R-U3-3:** failure must be **uniform, never intermittent** — one unpaired frame among a hundred
  paired ones is *marked*, so a stuttering condition is worse than being uniformly off.
- **R-U3-4:** when the builder throws, the real send **proceeds uncovered**.
- **R-U3-5:** nothing durable, nothing logged, nothing surviving teardown.

## Attack these specifically

1. **Does the real send survive every path?** Build refusal, socket throw, cancelled scope,
   cancellation *while waiting for the pairing lock*, teardown mid-pair, process death mid-pair. The
   claim is a `finally` with latched one-shot emitters makes the real publish happen **exactly once
   on every path**. Find a path where it happens twice, or zero times.
2. **Timing side channels — the implementer already found one.** The naive shape leaked the order,
   because a flush between decoy and real added its own duration to one branch only, so a short gap
   implied real-first. **Look for the residue of that class**: anything whose duration differs between
   the two orderings — flush, lock acquisition, allocation, first-use lazy init, GC pressure from the
   cover blob.
3. **Order predictability.** Claim: per-send `SecureRandom` bit, and `SecureRandom` is a *security*
   requirement because the gap is directly observable while the bit is not — a `java.util.Random`
   would let an observer recover the LCG state from measured gaps and predict every subsequent order
   bit. **Verify that reasoning and the implementation.** Is the bit uniform? Is it independent
   across sends? Is any *other* observable correlated with it?
4. **The gap: uniform 5–50 ms.** Floor claimed to stop the two writes coalescing into one TCP segment
   (which would present the pair as a single double-length frame). Ceiling under perceptibility and
   under median RTT. **Is the floor actually sufficient?** Nagle, `TCP_NODELAY`, TLS record batching,
   OkHttp's writer thread.
5. **The pairing lock.** Claim: without it, cover traffic **reorders real sends** (categorically
   forbidden) and a foreign frame landing between a pair would itself reveal the order. Check the
   lock's scope, and whether holding it can violate R-U3-1 by delaying an unrelated real send.
6. **The uniformity predicate (R-U3-3).** Claim: the only per-send condition is "does this vault have
   a synthetic account id" — durable, flips once per session, never flaps. And `canSend()` is
   **deliberately not** used because it folds in transient `capacityExceeded`, which would stutter.
   **Verify that a send reaching the seam has necessarily already flushed**, which is the argument for
   why the transient state is unobservable here.
7. **Pairing applies to EVERY envelope** — text, attachment control payloads, read receipts. The
   argument: receipts are deliberately built byte-indistinguishable from text, so pairing only
   user-visible messages would sort the single visible size class into paired/unpaired halves and
   **create a receipt detector that does not exist today.** Verify that reasoning, and verify the
   doubled `message.send` volume against `sendLimit` (100/min per account).
8. **Provisioning is now reachable.** Check it against §6.2a's registration-budget constraints: lazy,
   at most one attempt per session, silent degradation, durable back-off. A device must not be able to
   spend more than one registration per vault.
9. **Deniability, unchanged:** no device-level storage, no logging, no diagnostics, no slot naming;
   everything torn down at lock; a locked vault emits nothing.

## On the tests

15 mutations, all reported killed — **but only after the first thirteen left two tests
undiscriminated**, which is why M14 and M15 exist. Assume more blind spots. For every test: would it
fail if the property it names were broken, or is another guard carrying it?

## Contracts are in scope

`DecoyEnvelopeBuilder` is canonical for construction; `VaultState.kt`'s codec kdoc for the tag-write
trigger; `DecoyState`'s kdoc for the `TAG_DECOY` field set. **Nothing may restate them.** The
parallel-copy class has recurred eleven times on this feature. Also check §4.3's requirements against
what the code actually does.

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
