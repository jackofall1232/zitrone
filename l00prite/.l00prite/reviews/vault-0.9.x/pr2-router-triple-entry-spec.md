# PR-2 SPEC — router fusion + triple-entry gate + uninterrupted-sequence guard (0.9.2-beta)

**Status:** SPEC ONLY. No implementation until the user reviews it (same gate as PR-1).
**Author:** claude, 2026-07-24. **Scope owner:** jackofall1232. **Depends on:** PR-1 (`attemptUnlockOrAdd`,
merged/merging). **Decisions:** OQ1 triple-entry + uninterrupted-sequence guard (vault ledger, 2026-07-24 REVISED).

---

## 0. Scope, non-goals, sequencing

**In scope (PR-2):** wire the passphrase entry path through PR-1's `attemptUnlockOrAdd`, with a RAM-only
**triple-entry gate** deciding the `create` flag; the **uninterrupted-sequence guard**; the outcome mapping
(Unlocked / Created / Rejected / Burn / errors); the NotDurable-on-create UX; genesis encode+wipe at the
call site.

**Out of scope:** MainActivity's *routing/screen* changes beyond what the passphrase handler needs (the
broader PR-3 UI reconciliation); biometric A-only guard (PR-3); the Pucker Burn **setup** and **wipe**
(sibling PRs) — PR-2 only routes the `Burn` outcome to a handler interface (§6.4).

**SEQUENCING CONSTRAINT (recorded, binding):** PR-3's MainActivity no-match→create wiring MUST NOT land
before PR-2. Post-PR-1 the store's `create=true` path has NO caller (nothing reachable). Wiring a no-match
branch to create WITHOUT the triple-entry gate would make creation reachable on a SINGLE unrecognized
passphrase — exactly the behavior the OQ1 triple→ revision removed. **PR-2 (this) introduces the ONLY
caller that ever passes `create=true`, and only via the gate. PR-2 then PR-3, never the reverse.**

**Burn sequencing note:** `attemptUnlockOrAdd` returns `Burn` only when slot 0 is *armed*, which only the
Pucker Burn **setup** PR can do. So `Burn` is UNREACHABLE until that lands. PR-2 must still handle it
(route to an `onBurn` interface); until the burn-wipe PR exists, `onBurn` is a documented fail-closed stub
that behaves as a uniform failure (deniable no-op). See §6.4 — flagged for the user.

---

## 1. WRITER / READER invariant table — candidate/count state (built FIRST)

The gate state is **RAM-only, never persisted** (persisting it would be a footgun and a storage tell). It
lives in `VaultUnlockRouter` (the existing composable-free unlock-decision holder), alongside — but
SEPARATE from — the existing backoff `failedAttempts`.

State: `candidateHash: ByteArray?` (SHA-256 of the last non-matching passphrase's UTF-8, or null),
`candidateCount: Int` (consecutive identical-non-matching streak, 0 when no candidate).

### Writers

| Writer | Effect | When |
|---|---|---|
| `decideCreate(passphrase)` (per attempt, BEFORE the store call) | if SHA-256(passphrase) == candidateHash (constant-time): `candidateCount++`; else `candidateHash = hash`, `candidateCount = 1`. Returns `candidateCount >= 3`. | every passphrase entry |
| `onNonMatch()` (after a `Rejected` outcome) | no-op — KEEPS the candidate/count set by `decideCreate` (the streak stands) | after Rejected |
| `resetCandidate()` (discard) | `candidateHash` wiped→null, `candidateCount = 0` | after Unlocked / Created / Burn; on NotDurable-create; **on the lifecycle guard (§3)** |
| process death | RAM cleared implicitly | app killed |

### Readers

| Reader | Reads | Assumption |
|---|---|---|
| `decideCreate` itself | `candidateHash`, `candidateCount` | the streak is valid ONLY if uninterrupted (proven below) |
| (nothing else) | — | no persistence, no cross-component read; the store never sees this state (it only gets the resulting `create` boolean) |

### The one invariant + proof

**INVARIANT: `decideCreate` returns `true` (→ `create=true`) ONLY on the 3rd consecutive `attemptUnlockOrAdd`
call whose passphrase (a) hashes identically to the two immediately preceding calls AND (b) had no
intervening `resetCandidate()` — i.e. no different passphrase, no successful match, no create, no app
background, no lock cycle, no process death between them.**

*Proof.* `candidateCount` reaches 3 only by two successive `candidateCount++`, each requiring the incoming
hash to equal `candidateHash`. Any of the following forces `candidateCount` back to ≤1 before the next
increment: a differing hash (`decideCreate` sets count=1); a match/create/burn (`resetCandidate` sets
count=0, then the next entry sets count=1); a lifecycle reset (§3, count=0); process death (RAM gone). A
`Rejected` is the ONLY outcome that preserves the streak, and only for the SAME hash. Therefore three
identical hashes with no reset in between is necessary and sufficient. ∎

**Corollary (rapid background/foreground cannot defeat it):** every `onStop` calls `resetCandidate()`
(§3), so inserting a background between attempts can only DROP the streak, never advance it. Cycling makes
creation strictly harder, never reachable in fewer than 3 uninterrupted foreground entries.

---

## 2. Triple-entry state machine (exact reset semantics — OQ1)

Per attempt, in order:

1. `create = router.decideCreate(passphrase)` — computes SHA-256 + constant-time compare, updates
   `candidateHash`/`candidateCount`, returns whether this is the 3rd identical (count≥3).
2. `outcome = attemptUnlockOrAdd(passphrase, genesis, create)` (off-main; §6).
3. Map the outcome, and update gate state:

| Outcome | Gate state | Backoff (`failedAttempts`) | UI |
|---|---|---|---|
| `Unlocked` (slot 1..N-1 matched) | `resetCandidate()` | `recordSuccess()` | route to chat |
| `Burn` (slot 0 matched) | `resetCandidate()` | untouched (NOT a failure, NOT a success) | `onBurn()` (§6.4) |
| `Created` (3rd identical, markers absent) | `resetCandidate()` | `recordSuccess()` | route to chat (into the new vault) |
| `Rejected` (no match; or create refused: !3rd, or marker-present) | KEEP (streak stands) | `recordFailure()` | uniform failure + backoff |
| throw `NotDurable` (create write unconfirmed) | `resetCandidate()` | `recordFailure()` | generic retry (§6.5) |
| throw `CorruptImage`/`MissingImage`/`LegacyImage` | `resetCandidate()` | untouched | image-unreadable / onboarding (existing handling) |
| throw `IllegalStateException` (self-verify / broken provider) | `resetCandidate()` | `recordFailure()` | uniform failure |

Notes:
- A **match wins over create** in the store (PR-1), so even if `decideCreate` returned `true`, a passphrase
  that unlocks an existing slot yields `Unlocked`/`Burn` and resets the streak — a real vault passphrase can
  never accumulate a ritual (the first match resets it).
- The **marker-present create** returns `Rejected` (PR-1 B1) and thus KEEPS the streak. So a triple-entry
  while an account delete is pending simply keeps failing closed (disclosed in SECURITY_MODEL.md) — the
  ritual can complete once the delete resolves. This is correct and needs no special gate handling.

---

## 3. Uninterrupted-sequence guard (lifecycle hooks)

`resetCandidate()` must fire on **app backgrounding, lock cycle, and process death**.

- **App background:** `VaultLockManager.onStop` (already an app-wide `ProcessLifecycleOwner` observer,
  D3) calls `router.resetCandidate()` **UNCONDITIONALLY** — BEFORE / independent of the auto-lock decision
  (which is gated on `sessionLive`; at the lock screen there is no session, so the auto-lock path is
  `None`, but the ritual reset must still happen). This is the load-bearing hook: the ritual runs at the
  lock screen (no session), so the guard cannot depend on session state.
- **Lock cycle:** `UnlockController.lock()` also calls `router.resetCandidate()` (belt-and-suspenders — a
  transition from an unlocked session to the lock screen; the candidate is normally already empty there,
  but resetting is free and closes any ordering gap). Covers explicit "lock now" and auto-lock-fire.
- **Process death:** RAM is cleared; nothing to do (the state is deliberately not persisted).

**Rapid-cycle safety:** proven in §1 corollary — every `onStop` resets, so backgrounding between the 2nd
and 3rd entry drops the streak; no cycling pattern can accumulate a streak faster than 3 uninterrupted
foreground entries.

**Wiring:** `VaultLockManager` gains a `resetRitual: () -> Unit` lambda (injected, mirroring its other
lambdas for host-testability) invoked at the top of `onStop`. `AppContainer` wires it to
`unlockRouter::resetCandidate`. `UnlockController.lock()` calls a similarly-injected reset (or the
`AppContainer` composes it into the existing `lock` lambda). **Open detail (§9):** whether to add the
reset inside `UnlockController` or compose it at the `AppContainer` seam — the latter keeps
`UnlockController` free of router knowledge.

---

## 4. Separation from the backoff counter

`candidateCount` (identical-string streak) and `failedAttempts` (any-failure streak, drives the existing
`backoffDelayMs`) are DISTINCT RAM fields with DIFFERENT lifecycles:
- `failedAttempts`: `recordFailure()` on any Rejected/error-failure; `recordSuccess()` (=0) on any
  unlock/create; drives `backoffDelayMs = min(500ms×failedAttempts, 8s)`.
- `candidateCount`: advances only on identical-non-matching; resets on differing string / match / create /
  lifecycle.
- A **Burn** outcome touches NEITHER as a failure NOR as a create input: it `resetCandidate()`s (so a burn
  entry can't be mistaken for the 3rd of a ritual) and does NOT `recordFailure()` (it's a match, not a
  wrong password) — the app is being wiped, so backoff is moot, but the invariant is stated to prevent a
  future refactor from feeding Burn into either counter.
- The backoff delay is applied BEFORE `decideCreate`/the store call on every attempt (as today), so a
  triple-entry ritual looks exactly like 3 fumbled passwords from the backoff's perspective — reinforcing
  indistinguishability.

---

## 5. Timing (no new distinguisher)

- `decideCreate` runs SHA-256(passphrase UTF-8) + a constant-time `MessageDigest.isEqual` of two 32-byte
  digests on EVERY attempt, regardless of outcome or streak position — ~µs, computed before the store call
  unconditionally. It never branches the heavy work.
- The store op is 5 Argon2id + 1 payload GCM + 6 wrapped GCM every outcome (PR-1); `create` only adds the
  post-outcome persist residual (already reviewed/accepted). So the gate adds NO KDF-level or per-attempt
  distinguisher — an observer times three ~1s attempts identically whether the 3rd creates or rejects.
- The transient SHA-256 input (passphrase UTF-8 bytes) is wiped after hashing; `candidateHash` holds only
  the digest (not the passphrase), wiped on reset. (A digest is not the passphrase, but it is
  RAM-only and wiped — strictly better than retaining plaintext across attempts.)

---

## 6. Router fusion — the fused passphrase flow

### 6.1 Replace `ZitroneApp.unlockWithPassphrase`

Today: `imageStore.unlock(passphrase) ?: return false; publishSession(open)`. New: a fused method that
returns a richer result the UI maps:

```kotlin
sealed interface PassphraseOutcome { Unlocked; Created; Burn; Rejected; ImageUnreadable; Retry }

suspend fun attemptPassphrase(passphrase: String): PassphraseOutcome = withContext(Dispatchers.Default) {
    val create = unlockRouter.decideCreate(passphrase)     // §2 step 1 (cheap, constant-time)
    val genesis = VaultStateCodec.encode(VaultState.empty())
    val result = try {
        imageStore.attemptUnlockOrAdd(passphrase, genesis, create)
    } catch (t: Throwable) {
        // map NotDurable / Corrupt / Missing / Legacy / IllegalState per §2; reset ritual as specified
        ...
    } finally {
        wipe(genesis)                                      // §7: caller owns+wipes genesis
    }
    when (result) {
        is Unlocked -> { unlockRouter.resetCandidate(); publishSession(result.open); Unlocked }
        is Created  -> { unlockRouter.resetCandidate(); publishSession(result.open); Created }
        Burn        -> { unlockRouter.resetCandidate(); Burn }   // UI calls onBurn (§6.4)
        Rejected    -> { /* keep streak */ Rejected }
    }
}
```

- `publishSession` (PR-1/D2c) consumes-or-wipes the `VaultOpen` synchronously in the same off-main block —
  a Created vault publishes exactly like an Unlocked one (same `UnlockController.unlock(prepared)` path;
  teardown-on-switch is inherited because the lock screen is only shown when `current == null`).
- `recordSuccess`/`recordFailure` (backoff) applied per §2 table.

### 6.2 genesis on every attempt

`genesis = VaultStateCodec.encode(VaultState.empty())` is encoded on every attempt (cheap, non-crypto) and
wiped in `finally`. The store consumes it only on the create branch (copies it into the returned VaultOpen)
and never wipes the caller's copy; on all other outcomes it is untouched and wiped here. (Mirrors
`createVaultAndPublish`'s genesis handling.)

### 6.3 MainActivity `onUnlockPassphrase`

Maps `PassphraseOutcome`: `Unlocked`/`Created` → `onUnlockSuccess()`; `Rejected` → `recordFailure` +
`UNIFORM_FAILURE`; `Burn` → `onBurn()`; `ImageUnreadable` → `IMAGE_UNREADABLE_NOTE`; `Retry` (NotDurable)
→ a generic retry message. The backoff pre-delay stays as today. (This is the ONLY MainActivity change PR-2
makes; the broader routing reconciliation is PR-3.)

### 6.4 Burn handling (interface point + sequencing)

PR-2 routes `Burn` to an `onBurn: () -> Unit` provided by `AppContainer`. Because slot 0 is unarmed until
the Pucker Burn **setup** PR, `Burn` is unreachable in PR-2 alone. **Open decision (§9):** until the
burn-**wipe** PR lands, `onBurn` is a fail-closed stub that surfaces `UNIFORM_FAILURE` (a deniable no-op) —
OR PR-2 is sequenced after the burn-wipe PR so `onBurn` actually wipes. Recommend the stub + a prominent
TODO, so PR-2 is independent and the router is correct-by-construction when the wipe lands.

### 6.5 NotDurable-on-create UX

`attemptUnlockOrAdd` throws `NotDurable` when a create's write is on disk but not confirmed durable
(canonical advanced). Router: `resetCandidate()` (the ritual is spent), `recordFailure()`, surface a
generic retry. Note (PR-1 semantics): the new vault IS in `canonical`, so a subsequent single entry of the
same passphrase now MATCHES → `Unlocked` (no re-ritual needed) — the retry naturally recovers.

---

## 7. Memory / wipe discipline

- `genesis`: encoded + wiped per attempt at the call site (§6.2).
- `candidateHash`: a digest, RAM-only, wiped on `resetCandidate`.
- transient passphrase UTF-8 bytes inside `decideCreate`'s SHA-256: wiped after hashing (the passphrase
  `String` itself is unwipeable — a JVM limit, unchanged from today).
- `VaultOpen` on Unlocked/Created is consumed-or-wiped by `publishSession` (PR-1).

---

## 8. Tests (host-JVM)

`VaultUnlockRouter` gate (pure, no Android):
1. Three identical non-matching entries → 3rd returns create=true; 1st/2nd false.
2. Different string on the 2nd → count resets to 1, no create even on a later 3rd of the ORIGINAL.
3. `resetCandidate()` (lifecycle) between the 2nd and 3rd → 3rd does not create.
4. Backoff `failedAttempts` advances independently of `candidateCount` (different-string entries bump
   backoff but reset the candidate).
5. Constant-time compare used; `decideCreate` computes a hash on every call including the first.

Fused flow (`attemptPassphrase`, with the PR-1 store + fakes):
6. 3 identical unknown passphrases → Created + session published; a fresh reopen unlocks the new vault.
7. A matching passphrase at any ritual position → Unlocked, candidate discarded (no create).
8. Marker-present → 3 identical unknowns all Rejected, nothing created (fail-closed), streak preserved.
9. NotDurable-on-create → Retry mapping + ritual reset; a subsequent single entry Unlocks the now-present vault.
10. genesis wiped after every attempt.

Lifecycle (`VaultLockManager` with the injected `resetRitual`):
11. `onStop` invokes `resetRitual` unconditionally (even with no live session).

---

## 9. Open questions / decisions for the user

1. **`resetCandidate` placement (§3):** inside `UnlockController.lock()` (couples it to router knowledge)
   vs composed at the `AppContainer` seam (keeps `UnlockController` clean). Recommend the AppContainer seam
   + the unconditional `VaultLockManager.onStop` hook as the primary reset.
2. **Burn handling until burn-wipe exists (§6.4):** fail-closed uniform-failure stub (recommend, keeps PR-2
   independent) vs sequence PR-2 after the burn-wipe PR.
3. **`PassphraseOutcome` shape:** confirm a sealed result (vs the current Boolean) is acceptable — needed to
   distinguish Burn / Retry / ImageUnreadable at the UI.

## 10. Review intensity (recommendation)

Lighter than PR-1's store surface but NOT trivial: PR-2 introduces the ONLY `create=true` caller and the
gate is a new RAM state machine with a security-relevant invariant (§1). It writes NO durable state and
adds no writer to the delete/token/image surface (it only reads outcomes + toggles RAM counters), so it is
closer to D3 than D2c. Recommend: the WRITER/READER table (above) + one focused adversarial pass on the
gate invariant + the timing (SHA-256 on every attempt) + the lifecycle-guard completeness (rapid cycling),
lean per budget (≤5 agents / the free Codex+Grok bots). No durable-signal surface to re-verify.
