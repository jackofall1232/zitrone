I'll conduct a full adversarial security review of U1, starting with the protocol docs, the approved invariants, and then the entire unit source and tests.Loading the full unit sources, required reading, and the WRITER/READER invariant table.Pressing the round-3 hinges next: gate lifetime, spent/not-spent discriminator, compare-and-clear backoff, and whether the tests still discriminate.Checking one more edge on the spent discriminator and whether the invariant-table crash matrix still matches the code.# Adversarial Security Review — U1 Round 4

**Unit:** decoy synthetic-account provisioning + `TAG_DECOY`  
**Branch:** `feat/0.10.0-decoy-u1-provisioning`  
**Scope:** whole unit (sources + tests + contracts), not the round-3 delta  

---

## Round-3 hinges (probed; not findings)

### 1. Three weak registries keyed on `VaultRuntime`

| Registry | Key | Value |
|---|---|---|
| `DecoySectionLock` | weak `VaultRuntime` | strong `ReentrantLock` |
| `DecoyCounterReservation` | weak `VaultRuntime` | **weak** `WeakReference<allocator>` |
| `Gate` | weak `VaultRuntime` | strong `Gate` |

- All three use identity keys (`VaultRuntime` does not override `equals`). `getOrPut` is under a dedicated lock in each case → two callers cannot get different gates/locks for the same live runtime.
- A `Gate` cannot under-live a strongly held runtime: the map only drops the entry when the key is collectible.
- A `Gate` cannot usefully outlive its runtime either: provisioners hold both `runtime` and `gate`; once the runtime is gone, every entry evaporates. Nothing durable or device-level is recorded.
- The allocator’s weak *value* is the intentional difference: a collected allocator is recreated with an empty cursor; the staleness check + section lock make that a **skip**, never a regression. That is weaker caching than Gate/Lock, but it is the documented, correct residual.

**No disagreement that breaks uniqueness or deniability was constructible.**

### 2. Deliberate deviation: new instance, shared `Gate`

Judgment: **correct.**

The uniqueness that matters is guard state (`attempted`, `credentialsUnconfirmed`), not collaborator identity. Caching a provisioner would pin a later caller to an earlier attempt’s `StagingAuthStore` / clock / relay — a real trap. Sharing the gate closes the H2/H3 double-spend and readiness-lie holes without that trap. The allocator caches because its *cursor* is the unique resource; here the collaborators are per-attempt by design.

### 3. `clearBackoff(deadline)` compare-and-clear

Under `DecoySectionLock`: read → equality check → mutate → flush.

- Another writer of `provisionNotBeforeMs` also takes the section lock → cannot interleave a foreign deadline under the compare.
- Counter reservation / token writes preserve `provisionNotBeforeMs` via `copy`.
- Only one attempt per runtime can hold the latch, so two provisioners cannot race two different write-ahead deadlines on the same runtime.
- Crash between write and clear → spurious ≤90 min deferral: **accepted**, documented.
- Failure to clear one’s own deadline only if the live value no longer equals the returned one (another writer changed it under the same lock) — then leaving it alone is the right rule.

### 4. `storeTokensForAccount`

Compare and write are one sequence under the section lock. `storeTokens` refuses a null account id (no token-only materialisation). No residual route in this unit reintroduces a token-only section.

### 5. Structural design (section lock, predicate split)

Probed again via clearAccount×allocator, capacity×register, concurrent provisioners. Both still hold.

---

## Findings

### Finding 1 — **P2**  
**File:line:** `DecoyAccountProvisioner.kt:330–331`

**The concrete failure**

The spent/not-spent discriminator is set *before* a **local** call that cannot have spent a registration:

```kotlin
registrationSpent = true
val accountId = relay.register(DecoyIdentity.generateBundle(identity), powProof)
```

In Kotlin, arguments are evaluated before the callee runs. So the order is:

1. `registrationSpent = true`
2. `DecoyIdentity.generateBundle(identity)` — pure local crypto (101 keypairs, signatures); **no network**
3. only then `relay.register(...)`

**Sequence:**

1. `reserveBackoff()` succeeds → durable `provisionNotBeforeMs = D` on disk (`TAG_DECOY` present).
2. Challenge + PoW succeed (or challenge is null).
3. `registrationSpent = true`.
4. `generateBundle` throws (OOM while allocating the one-time batch, crypto provider failure, etc.) — **zero bytes to the relay**.
5. Catch path: `registrationSpent == true` → **`clearBackoff(D)` is skipped**.
6. Outcome: 60–90 min cover-traffic silence, durable deferral-only section (0.9.x downgrade break), **and nothing was spent from the global bucket**.

That is exactly the H5 / H1 class R3 claimed to close for “failures that spent nothing,” applied incompletely: challenge/PoW/cancellation sit *before* the flag; `generateBundle` sits *after* it only because it was inlined as an argument.

The hinge comment itself says the flag must be set “before the register call” because **register** may have created the account. `generateBundle` is not register.

Correct shape:

```kotlin
val bundle = DecoyIdentity.generateBundle(identity) // local; free to abandon
registrationSpent = true
val accountId = relay.register(bundle, powProof)
```

**Why tests miss it**

The suite’s spent-nothing path injects only `FakeRelay.Stage.CHALLENGE`. Failures at `REGISTER` / `SESSION` correctly keep the deferral. Nothing forces `generateBundle` (or any pre-network step after the flag) to throw. Mutating “clearBackoff unconditional” fails other tests; mutating the flag placement relative to `generateBundle` is untested.

---

### Finding 2 — **P3**  
**File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:425–428` (§4.1 user-facing disclosure)

**The concrete failure**

Re-ratified text:

> once a vault has **set up cover traffic** — **which happens the first time it sends any** — it can no longer be opened by 0.9.x  
> A vault that has never used cover traffic is unaffected

Code truth (after R3):

| Path | `TAG_DECOY` durable? |
|---|---|
| Never calls `provisionIfNeeded` | no |
| Fails before `register`, deferral retired | no (empty holder omitted) |
| Reaches `register` (incl. 429 / lost response) | **yes** (deferral and/or credentials) |
| Succeeds, never sends a decoy | **yes** |

So the tag attaches to **setup that reaches registration**, not to a completed send. A vault that hits 429 (or any post-register failure) and never successfully sends still breaks 0.9.x. The parenthetical re-equates “set up” with “sends,” which **understates** the blast radius the adjust note itself admits:

> a vault that registers and then never sends still carries the tag.

The adjust block is largely honest; the **shipping disclosure sentence is not**. Understatement of a format break is a disclosure defect of the same class round 3 already burned once.

**Why tests miss it**  
No test asserts disclosure wording against codec behaviour (the suite correctly pins “spent-nothing ⇒ no tag,” not the §4.1 sentence).

---

### Finding 3 — **P3**  
**File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:628–633` (§6.2a)

**The concrete failure**

§6.2a still states R2 semantics as current law:

- “**only a successful commit retires**” the write-ahead deferral  
- “***every* failure defers** … **an offline challenge fetch**”  
- “a purely local failure therefore costs a 60–90 minute wait”

R3 code does the opposite for pre-register failures: `clearBackoff` retires the deferral when `!registrationSpent` (`DecoyAccountProvisioner.kt:389–393`, `433–469`). An offline challenge fetch **must not** leave a 60–90 min deferral or a permanent `TAG_DECOY`.

A later unit (or a “fix” that re-aligns code to §6.2a) will reintroduce H5. This is the same stale-contract class the brief says has recurred three times.

**Why tests miss it**  
Tests match the *code*, not §6.2a. The suite would stay green with a false §6.2a forever.

---

### Finding 4 — **P3**  
**File:line:** `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md:72`, `:201`

**The concrete failure**

Two rows attack the table itself:

1. **W1 durability column (line 72):** still says an **“instance-scoped `credentialsUnconfirmed`”** flag. R3 moved it into the per-runtime `Gate` (H3). A reader of the table alone rebuilds the second-provisioner readiness lie.

2. **Crash matrix “before `register`” (line 201):** claims durable W1b deferral and “retry **after the back-off window**.” After R3 the deferral is **retired**; the next unlock may attempt immediately (pinned by `a failure BEFORE register RETIRES the deferral`). The matrix teaches the wrong retry policy.

W1 also still says success is “the only thing that retires” W1b — false once `clearBackoff` exists.

Round 3’s “FIX ROUND 3” appendix describes the new behaviour; the authoritative W1/crash rows were not fully rewritten. **A row that is wrong is a finding** under the review brief.

**Why tests miss it**  
Table is not executable.

---

## Invariants (falsification summary)

| # | Claim | Result |
|---|---|---|
| 1 | Register-before-commit | **Holds.** Staging store + one credential mutate + flush. Crash matrix (modulo Finding 4’s stale “before register” row) lands orphan or full set, never id-without-key. |
| 2 | Counter skip, never regress | **Holds.** Flush-before-spend; section lock makes staleness check atomic with spend; `clearAccount` cannot land mid-sequence. |
| 3 | Key material zeroing | **Holds** for `ByteArray` identity paths (codec PartialDecode, VaultState.wipe, abandon wipe, refresh copy wipe). Prekey private halves: documented libsignal finalize residual, residency shortened. |
| 4 | Deniability | **Holds** for sealed size (fixed region). No device-level sinks. Process-wide weak maps hold no vault content. |
| 5 | Strict-v1 codec | **Holds.** Unknown/duplicate tags throw; negative high-water refused both ways; noncanonical nullable longs rejected; empty holder omitted. |
| 6 | Capacity budget | **Holds** as measured max the relay can produce (640–643 B / 1024 B); not an adversarial stretch of server-fixed JWT shape — test says so. |
| 7 | Mutation / locking | **Holds.** Section lock → stateLock; no runtime call from persist sinks in this unit. |
| 8 | Presence ≠ readiness | **Holds** in code: `hasAccount` / `canSend` / `isProvisioned`. |
| 9 | Registration budget | **Holds** except Finding 1’s false-positive “spent” (over-backs-off without spending). Latch is runtime-scoped; write-ahead + keep-on-maybe-spent is otherwise sound. |

---

## Tests — discrimination (sample of pressure)

Round-3 restructures for runtime-scoped latch look real: later sessions reopen from `durableState()`, not a second provisioner on the same live runtime.

Still honest gaps:

| Test | Risk |
|---|---|
| `a failure BEFORE register RETIRES the deferral` | Only `Stage.CHALLENGE` — does not pin the post-flag / pre-network gap (Finding 1). |
| `one attempt per session` | Same instance; runtime-scope is pinned elsewhere (`two provisioners…`). |
| `interleaved use never regresses` | Documented non-discriminating between the two allocator defences (still true). |
| Implementer’s “12 mutations” | Necessary but not sufficient; none mutates flag placement relative to `generateBundle`. |

No test was found that **passes while asserting nothing** in the H9/`join.let { true }` sense; that class looks cleaned up.

---

## §4.1 vs code (explicit judgment)

- **“Set up cover traffic” as the trigger for the format break:** true enough if “set up” means “provisioning that leaves non-empty decoy state.”
- **“Which happens the first time it sends any”:** **false** (Finding 2).
- **Spent-nothing offline attempt keeps 0.9.x readability:** **true** in code; good R3 fix.
- **Register-then-never-send still carries the tag:** **true**; disclosure must not imply otherwise.

---

## Deliberate deviation (final)

Sharing guard state without caching the provisioner **does** give the uniqueness guarantee for registration and readiness. It does **not** leave a gap the allocator’s instance cache would have closed for this class’s responsibilities. No finding.

---

**VERDICT: FINDINGS (0 P1, 1 P2, 3 P3)**
