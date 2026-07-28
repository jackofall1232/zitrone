# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, **Unit U3** — ROUND 4

**READ-ONLY TASK. Do not create, edit or delete any file. Do not run any command that mutates state
(no git add/commit/checkout/stash, no writes, no builds that alter the tree).** You are judging this
code, not changing it. Read, grep, and reason freely.

You are one of two independent, blind reviewers. **Review the WHOLE UNIT, not a delta.**

## Trend, so you calibrate correctly

Round 1: 4 P1 → design contradiction, maintainer ruled real-frame-first.
Round 2: 2 P1 — cover placed where it could **precede or outlive** the real send.
Round 3: **4 P1 — severity went UP**; two were newly introduced by round 2's own fix.

**In all six rounds so far the two reviewers were disjoint on the top finding.** Assume nothing is
covered by the other reviewer.

## What round 4 changed — a composed fix, and one refuted argument

Round 3 argued that "stop admitting new real sends" was **not jointly satisfiable** with "cover never
precedes the real send." **That argument was refuted with a construction and the implementer has now
implemented it**: teardown need not be *atomic* with the handoff, only **serialised** against it — and
`MessagingCoordinator` already owns a serialisation point every send passes through, its
`limitedParallelism(1)` confined worker. Terminal teardown is enqueued there, so it runs strictly
before or strictly after a send's publish-then-pair slice, never inside it.

Attack all of the following:

1. **The confinement argument itself.** The claim is that with no suspension point between
   `ws.sendMessage` and `inFlight.add`, that slice is uninterruptible, so teardown cannot land inside
   it. **Verify there is genuinely no suspension point there**, and that teardown really is dispatched
   onto the same worker in every path (including `deleteAccountAndWipe`).
2. **A success signal now gates cover.** Both publish tails return "handed to the relay"; all three
   sites are `if (publish…) cover(…)`. This closes a P1 where a decoy could be emitted with **no real
   frame behind it** (contact deleted or socket refused, both of which the caller previously could not
   distinguish). Verify all three sites, and that the tails still cannot suspend.
3. **The wall clock is gone.** `cover()` now **builds then admits**, so the register only ever holds
   built pairings; the 100 ms deadline, wait loop, condition variable and `resolved` flag are deleted.
   Verify no timing dependence remains.
4. **`quiesce` — a NEW non-terminal lifecycle state.** Transport changes (Tor toggle) now drain and
   keep pairing over the new socket instead of disconnecting undrained. **This is new machinery on a
   security-sensitive surface — attack it hard**, especially correctness against a socket that
   reconnects mid-drain.
5. **A declared residual to judge.** `stop()` blocks on the confined worker for **≤250 ms**, then
   falls back to the calling thread. The bound is on *waiting for the worker*, not on cover work —
   because `UnlockController` closes the vault runtime the instant `stop()` returns, and **a lock that
   hangs without wiping keys is worse than any framing defect.** Is that trade right, and is 250 ms
   defensible?
6. **Re-entry.** `stop()` blocks on the worker while holding `transportLock`, and
   `deleteAccountAndWipe → lockIf` can re-enter from the worker. Handled by a `terminalTeardownDone`
   skip. **Verify that.**
7. **The tripwires were all rewritten** because none pinned what it claimed — the call-site one passed
   while a live P1 existed (adjacency ≠ dependence), the reflection one pinned a single shape, the
   disconnect one read one file with a deliberate carve-out. **Judge whether they now pin what they
   claim, or merely look stricter.**

## Standing requirements
- **R-U3-1 (absolute):** a real send is never blocked, failed, materially delayed, reordered or made
  less durable by cover traffic. Note the ruling in §4.3: **"materially" modifies "delayed", not "made
  less durable"** — there is no de minimis exception for durability.
- **R-U3-3:** failure must be **uniform, never intermittent** — an unpaired frame, or a lone decoy, is
  a *marked* frame.
- Registration budget (provisioning is reachable now), deniability surface, teardown.

## On the tests
716 tests; `DecoySendPairingTest` 28→35. **One mutation survived and was reported rather than hidden**
— reverting to admit-then-build leaves the suite green, correctly, because once teardown is confined
both orders are behaviour-equivalent; the *deadline* was the defect, not the order. **Assume other
survivors exist.** For each test: is the property it names what makes it pass, or is another guard
carrying it?

## Contracts in scope
`DecoyEnvelopeBuilder` canonical for construction, `VaultState.kt` codec kdoc for the tag-write
trigger, `DecoyState` kdoc for the `TAG_DECOY` field set. **Nothing may restate them.** The stale
parallel-copy class has recurred **fourteen times** on this feature, twice inside correction notes
written to fix it. Check §4.3 and §5 against the code.

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
