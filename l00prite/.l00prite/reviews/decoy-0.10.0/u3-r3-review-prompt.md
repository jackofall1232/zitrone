# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U3** — ROUND 3

Two independent, blind reviewers. You do not see the other's findings. **Review the WHOLE UNIT.**

## Trend, so you calibrate

Round 1: 4 P1, 2 P2, 4 P3 → design contradiction, maintainer ruled **real-frame-first**.
Round 2: 2 P1, 1 P2, 5 P3 — both P1s were the same shape: **cover traffic placed where it could
precede or outlive the real send.**

**The reviewers have been disjoint on the top finding in all five rounds so far.** Assume nothing is
covered by the other.

## What round 3 changed, and the claims to break

Round 2's structural claim (*"a process can only die at a suspension point"*) was **false** — a
coroutine only *suspends* there; the OS kills at any instruction. That false claim concealed a real
loss path. **So this round's structural claims are stated with mechanisms, and your first job is to
test them.**

1. **The seam can no longer be handed a real send.** `paired(cover, publish)` is deleted; the
   interface is `suspend fun cover(real: MessageEnvelope)`. Claim: *there is no parameter that could
   hold a real send, so no construction exists in which cover code runs before the handoff.* Pinned
   by a **reflection test**, because re-adding a `publish: () -> Unit` parameter would compile and
   pass every behavioural test. **Attack the claim and the tripwire.**
2. **A deliberate non-empty residual.** The publish tail (`publishOutgoing`/`publishReceipt`) was
   *kept* rather than inlined, because inlining would have silently retired the D2c compiler
   enforcement that no suspension exists between `contactExists` and `ws.sendMessage`. Claimed safe
   because those methods are members of the send path and would remain correct if cover traffic were
   deleted. **Is that carve-out sound, or is it the loss window returning under a justification?**
3. **Teardown owns the disconnect.** `stop(invalidateTransport)` performs the disconnect itself,
   last. Pairings are registered on admission; `stop()` emits every admitted frame **gaplessly while
   the socket is live**, waits bounded (100 ms) for any pairing still *building*, then invalidates in
   a `finally`. **Register membership is the right to emit** (an `emitted` flag was removed as
   unreachable-as-false). Claim: `ws.disconnect()` is reachable in the coordinator **only** as the
   argument to `stop`. *Verify that.*
4. **Exactly-once under the drain.** The drain releases its lock while waiting, so an already-emitted
   pairing can wake with the transport still valid. Attack that window.
5. **The bounded wait is claimed safe because `buildCover` cannot suspend** — no suspension point
   between admission and the built frame. Verify, and consider what a slow or throwing build does.
6. **A declared, unfixed residual — judge whether it is acceptable.** `ZitroneApp.applyTransportLocked`
   also disconnects (user toggling Tor) and does **not** drain. Narrower than teardown — not
   lock-correlated, reconnects immediately — but it is a second `disconnect` the register does not
   own. Deliberately not built this round: it needs a *non-terminal* quiesce, a new lifecycle state
   on a security-sensitive surface.
7. **Also declared:** step 1 of the four-step teardown lifecycle ("stop admitting new real sends")
   is claimed **not jointly satisfiable** with the rule that cover must never precede the real send —
   making it atomic would require registering under a lock a real send could queue on. The residual
   is a few instructions between `ws.sendMessage` returning and registration, with no suspension or
   I/O. **Is the impossibility argument sound, or is there a construction that satisfies both?**

## Everything from earlier rounds remains in scope
R-U3-1 absolute (a real send never blocked, failed, materially delayed, reordered or made less
durable); R-U3-3 (failure uniform, never intermittent — an unpaired frame is a *marked* frame); the
registration budget now that provisioning is reachable; deniability; teardown.

Note a third-lens reading now in §4.3: **"materially" modifies "delayed", not "made less durable"** —
there is no de minimis exception for durability.

## On the tests
28 tests in the pairing suite (was 20), driving a socket that really dies and the real teardown entry
point. **Two mutations survived their first form and were fixed rather than excused.** There are two
**source-level call-site tripwires** — unusual, and there because both round-2 P1s lived at the call
site rather than inside the class. Judge whether they actually pin what they claim.

## Contracts in scope
`DecoyEnvelopeBuilder` canonical for construction, `VaultState.kt` codec kdoc for the tag-write
trigger, `DecoyState` kdoc for the `TAG_DECOY` field set. **Nothing may restate them.** The stale
parallel-copy class has recurred **fourteen times** on this feature — check §4.3 and §5 against the code.

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
