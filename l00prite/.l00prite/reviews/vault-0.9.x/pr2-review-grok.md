I'll perform an independent adversarial security review of the PR-2 delta against the locked triple-entry spec—findings only, no fixes. Starting with the diff and the actual source.Reading the full source for the gate, fusion path, lock manager, and store contract.Checking race paths, session collector, publishSession throw handling, store create residual, and whether lock resets the ritual.Checking forced-logout and whether a live session can retain a mid-ritual candidate:# Adversarial Security Review — PR-2 Router Fusion (`b8eb652..7348c53`)

**Scope:** `feat/0.9.2-vault-pr2-router` delta vs actual source + locked spec `/root/l00prite/pr2-router-triple-entry-spec.md`  
**Threat model:** physical device + forensics + many forced unlocks + crash/death at any instruction  
**Method:** `git diff b8eb652..7348c53` + full-file verification of listed surfaces  
**Stance:** guilty-until-proven · findings only · no fixes

---

## Findings

### F1 — HIGH — Uninterrupted-sequence guard incomplete: lock cycle + biometric success do not clear ritual state

| | |
|---|---|
| **Where** | `VaultLockManager.onStop` (`VaultLockManager.kt:106–110`) is the *only* lifecycle reset; production wires it in `AppContainer` (`ZitroneApp.kt:324–336`). `UnlockController.lock` / `lockIf` (`UnlockController.kt:107–109`, `119–121`) never call `resetCandidate`. `unlockWithBiometric` (`ZitroneApp.kt:476–488`) never calls `resetCandidate`. Biometric UI success (`MainActivity.kt:850`) only calls `onUnlockSuccess` (backoff), not ritual reset. |
| **Mechanism** | Spec §3 requires `resetCandidate` on **app background**, **lock cycle**, and process death. This PR implements background (`onStop` → `resetRitual`) only. Comments claim a lock cycle “cannot interrupt a ritual because the ritual only runs at the lock screen” (`ZitroneApp.kt:333–334`) — false for *surviving state*: ritual RAM can be non-empty *across* a session. Passphrase `Unlocked`/`Created` reset the gate; **biometric unlock does not**. Any return to the lock screen that does **not** go through `ProcessLifecycleOwner.onStop` therefore preserves `candidateHash`/`candidateCount`. |
| **Failure / attack scenario** | (1) At lock screen, enter non-matching passphrase `P` twice → `candidateCount=2`. (2) Unlock with biometrics (session live; ritual still `P`@2). (3) Session ends via **forced logout** / `lockIf` while the app stays foreground (no `onStop`). (4) On the lock screen, enter `P` **once** → `decideCreate` advances to 3, `create=true` → store may **Created** a second vault. That is a single post-session entry completing a “triple” interrupted by a full unlock — violates the §1 invariant. Also enables **accidental** vault-B creation after two typos + bio unlock + foreground re-lock. |

---

### F2 — MEDIUM — Burn path is not behaviourally identical to wrong password (backoff oracle once burn is reachable)

| | |
|---|---|
| **Where** | `attemptPassphrase` Burn arm (`ZitroneApp.kt:452–455`): `resetCandidate()` only; **no** `recordFailure` / `recordSuccess`. UI stub `onBurn` (`MainActivity.kt:772–775`): same `UNIFORM_FAILURE` string, clears `unlocking`. Contrast Rejected (`ZitroneApp.kt:456–459`): `recordFailure()`. |
| **Mechanism** | Spec deliberately leaves Burn backoff untouched (match, not guess). Stub UI copies wrong-password text, but **client backoff delay** diverges: wrong passwords accumulate `failedAttempts` (500 ms × n, cap 8 s); Burn does not. |
| **Failure / attack scenario** | Once burn-setup arms slot 0: coerced entry of the duress credential shows the same string as a wrong password, but the **next** attempt has **no extra pre-delay**, while N wrong passwords do. A present adversary timing unlock attempts (or instrumentation) distinguishes duress match from fumbles **before** wipe behaviour is even considered. Today Burn is unreachable (slot 0 unarmed) — residual becomes live with burn-setup. |

---

### F3 — MEDIUM — Gate state is unsynchronized across threads (lifecycle reset vs `decideCreate`)

| | |
|---|---|
| **Where** | `VaultUnlockRouter.candidateHash` / `candidateCount` (`VaultUnlockRouter.kt:61–64`, writers `84–97`, `104–108`) — plain fields, no lock/`@Volatile`. Writers: `decideCreate` on `Dispatchers.Default` (`ZitroneApp.kt:406–407`); `resetCandidate` from main-thread `onStop` via `resetRitual` (`VaultLockManager.kt:110`, `ZitroneApp.kt:335`). |
| **Mechanism** | No happens-before between lifecycle reset and concurrent `decideCreate`/`candidateCount++`. Possible lost updates, torn reads, or `MessageDigest.isEqual` against a digest concurrently zeroed by `resetCandidate` (`pending` local ref + `fill(0)`). |
| **Failure / attack scenario** | User submits 3rd identical entry (Default) while app backgrounds (`onStop` reset on main). Interleaving can leave a non-spec streak (e.g. count advanced after a “reset”, or reset not observed). Not a reliable remote exploit; under the physical/forced-attempt model it weakens a load-bearing invariant that the PR treats as proven. |

---

### F4 — LOW — `publishSession` throw path no longer records backoff failure (regression vs pre-PR-2)

| | |
|---|---|
| **Where** | `attemptPassphrase` (`ZitroneApp.kt:436–450`): `publishSession` sits **outside** the store `catch`. `MainActivity.onUnlockPassphrase` `onFailure` (`815–820`) surfaces `UNIFORM_FAILURE` but **does not** `recordFailure`. Pre-delta `unlockWithPassphrase` failures hit MainActivity’s `else → recordFailure()`. |
| **Mechanism** | Build/refuse throws after a match/create leave ritual already reset; backoff unchanged. |
| **Failure / attack scenario** | Rare broken-build / terminal-wipe races: attacker or flaky state can retry without the progressive delay applied to ordinary rejects. Weak as an oracle; is a hardened-surface regression on the failure counter. |

---

### F5 — LOW — `candidateCount` is a signed `Int`; long fail-closed create streaks can stop requesting create

| | |
|---|---|
| **Where** | `decideCreate` (`VaultUnlockRouter.kt:88`, `96`): `candidateCount++` then `>= CREATE_THRESHOLD`. |
| **Mechanism** | After `Int.MAX_VALUE` identical Rejected creates (e.g. marker-present fail-closed), overflow to `MIN_VALUE` makes `>= 3` false until the counter wraps again. |
| **Failure / attack scenario** | Only after ~2³¹ uninterrupted identical attempts in one process — impractical for humans, relevant only to automated coercion + marker stuck for a very long time. 4th+ identical still correctly requests create before overflow (intended). |

---

### F6 — INFO — Default `resetRitual = {}` is a footgun for non-production constructors

| | |
|---|---|
| **Where** | `VaultLockManager` (`VaultLockManager.kt:96`): default no-op. Production `AppContainer` **does** wire `resetRitual = { unlockRouter.resetCandidate() }` (`ZitroneApp.kt:335`). Sole construction site in app code. |
| **Mechanism** | Any future/test host that forgets the lambda silently disables the uninterrupted-sequence guard while auto-lock still runs. |
| **Failure / attack scenario** | Mis-wired build: backgrounding no longer breaks rituals → create after 3 entries across background gaps. Not live in current production wiring. |

---

### F7 — INFO — RAM-resident SHA-256 candidate is a weak-passphrase offline target under live forensics

| | |
|---|---|
| **Where** | `candidateHash` (`VaultUnlockRouter.kt:61`, set in `decideCreate` 91–94); wiped in `resetCandidate` 105–106. Transient UTF-8 wiped in `sha256` finally (`111–117`). |
| **Mechanism** | Spec accepts digest-not-passphrase. Digest is still offline-bruteforceable for low-entropy vault-B choices if RAM is dumped mid-ritual. |
| **Failure / attack scenario** | Forensic RAM image between entry 1–2 of a weak planned vault-B password recovers the password without Argon2. Residual accepted by design; not a logic bug. |

---

### F8 — INFO — Spec §8 fused/lifecycle tests not present in this delta

| | |
|---|---|
| **Where** | Only pure gate tests in `VaultUnlockRouterTest.kt` (three-identical, reset mid-sequence, independence from backoff, 4th still create). No host tests for `attemptPassphrase` mapping, genesis wipe, `onStop`→`resetRitual`, marker-present streak keep, NotDurable→Retry, match-wins discard. |
| **Mechanism** | Security-critical fusion and lifecycle behaviour are review-only, not regression-locked. |
| **Failure / attack scenario** | Future edits can re-break F1-class gaps or outcome mapping without CI signal. |

---

## Binding checklist — explicit verdicts

### 1. GATE STATE MACHINE — **CLEAN (logic); see F1 for interruption completeness**

`decideCreate` (`VaultUnlockRouter.kt:84–97`):

- 1st identical → count=1, `false`
- 2nd identical → count=2, `false`
- 3rd identical → count=3, `true` (`>= CREATE_THRESHOLD` with `CREATE_THRESHOLD=3`)
- 4th+ identical → still `true` (marker-present fail-closed recovery — tested)
- Differing string → wipe old digest, count=1, new candidate (no create)
- `resetCandidate` → hash wiped/null, count=0; next entry is fresh count=1

No off-by-one: create on the 3rd identical call, not the 2nd or 4th-as-first. Non-identical cannot reach threshold without three matching hashes in a row **in this function**. Sufficiency of “no intervening reset” depends on all reset writers firing — **F1** shows that is not fully true outside pure `decideCreate`.

---

### 2. TIMING / SIDE-CHANNEL — **CLEAN for create-vs-reject distinguisher at the gate**

- Every attempt: `sha256` + (if pending non-null) `MessageDigest.isEqual(hash, pending)` — **not** `contentEquals` / `==`.
- Both sides are always 32-byte digests → `isEqual` is fixed-length constant-time.
- No passphrase branching that skips the hash on later attempts; first attempt skips `isEqual` only when `pending == null` (µs-class, not create-flag-based).
- `create` is a boolean into the store; store still does full Argon2id sweep + candidate seal + payload GCM every outcome; successful create’s extra persist is the documented post-outcome residual (store contract, pre-merged).
- Gate does not branch heavy work on passphrase content beyond fixed digest compare.

---

### 3. UNINTERRUPTED-SEQUENCE GUARD — **NOT FULLY CLEAN — F1, F3, F6**

| Claim | Verdict |
|---|---|
| `onStop` calls `resetRitual()` unconditionally before auto-lock decision | **Yes** (`VaultLockManager.kt:106–113`) |
| Rapid background/foreground cannot *advance* streak | **Yes for onStop path** — reset only drops |
| Process death clears state | **Yes** — RAM-only, never persisted |
| Mid-ritual candidate cannot survive other teardown/unlock paths | **No — F1** (bio unlock + non-`onStop` re-lock) |
| Production wires `resetRitual` | **Yes** (`ZitroneApp.kt:335`) |
| Default no-op hazard | **Info F6** — production OK |

---

### 4. FUSION OUTCOME MAPPING (`attemptPassphrase`) — **CLEAN vs §2 table (with notes)**

| Store / throw | Outcome | Ritual | Backoff | Notes |
|---|---|---|---|---|
| `Unlocked` + publish OK | `Unlocked` | reset | success | |
| `Unlocked` + publish refused | `Rejected` | reset | failure | Correct refuse-build mapping |
| `Created` + publish OK | `Created` | reset | success | |
| `Created` + publish refused | `Rejected` | reset | failure | Vault may already be durable; next match unlocks |
| `Burn` | `Burn` | reset | **untouched** | F2 oracle |
| `Rejected` | `Rejected` | **KEEP** | failure | |
| `LegacyImage` | `LegacyImage` | reset | untouched | |
| `CorruptImage` / `MissingImage` | `ImageUnreadable` | reset | untouched | |
| `NotDurable` | `Retry` | reset | failure | |
| other `Throwable` | `Rejected` | reset | failure | |
| `CancellationException` | rethrown | (left as after `decideCreate`) | untouched | Not swallowed (`412–413`); MainActivity rethrows (`816`) |
| `publishSession` throws | propagates | already reset on match/create arms | **not** failure (F4) | |

`CancellationException` is not mapped to a deniable outcome — correct propagation.

---

### 5. KEY / SECRET MATERIAL — **CLEAN on reviewed paths**

- `genesis` encoded per attempt; wiped in outer `finally` on all returns including `return@withContext` and CE rethrow (`ZitroneApp.kt:408–464`).
- Store copies genesis only on Created; caller wipe is appropriate.
- `VaultOpen` on Unlocked/Created goes through `publishSession` synchronously in the same non-suspending block (consume or `onRefused` wipe).
- Router holds only SHA-256 digest; UTF-8 buffer wiped after hash; digest wiped on `resetCandidate`.
- No path found that strands `genesis` after successful encode. Match-path temporary `candidateCount` bump is discarded by `resetCandidate` after store match.
- Residual: JVM `String` passphrase unwipeable (pre-existing); digest residual F7.

---

### 6. DENIABILITY / ORACLE — **Mostly clean; Burn residual F2**

| Check | Verdict |
|---|---|
| Marker-present create → `Rejected`, KEEP streak, `recordFailure`, UI `UNIFORM_FAILURE` | **Yes** — same as wrong password |
| `Retry` vs `Rejected` UI | **Same** `UNIFORM_FAILURE` (`MainActivity.kt:806–811`) — good (no distinct oracle). Ritual differs (Retry resets; Rejected keeps) — correct per durability semantics, not a slot oracle |
| `onBurn` stub | Deniable **string** no-op; **backoff differs** (F2); unreachable until burn-setup |
| Image unreadable / legacy | Distinct by design (device state, not guess) — preserved |

---

### 7. REGRESSION TO HARDENED SURFACE — **CLEAN with small notes**

| Check | Verdict |
|---|---|
| `attemptPassphrase` writers to delete-marker / auth-token / vault-seal | **None** — only `decideCreate`/`resetCandidate`/`record*` + `attemptUnlockOrAdd` + `publishSession` |
| `VaultLockManager` D3 auto-lock decision | **Unchanged** after additive `resetRitual()` |
| Legacy → onboarding; Corrupt/Missing → image-unreadable | **Preserved** via outcomes |
| `unlockWithPassphrase` callers | **None remain** (repo-wide) |
| Match wins over create; caller resets | **Yes** |

---

### 8. GENERAL NEW DEFECTS

| Item | Verdict |
|---|---|
| Use-after-wipe / double-wipe of genesis | Clean — wipe after store returns; store uses copy on create |
| Coroutine/CE in `attemptPassphrase` | CE not swallowed; no suspend between store return and publish (low strand risk for `VaultOpen`) |
| `unlocking` cleared on every outcome | **Yes** for all `PassphraseOutcome` arms + non-CE failure; Unlocked/Created also via `onUnlockSuccess` and session collector (`MainActivity.kt:705–713`) |
| CE leaves `unlocking=true` | Pre-existing pattern; composition cancellation usually drops state |
| `decideCreate` before match then reset | Correct discard |
| Integer threshold | F5 only |
| Double `recordSuccess` (router + `onUnlockSuccess`) | Harmless redundancy |

---

## Overall verdict

**Gate math, constant-time digest compare, Rejected/Retry UI uniformity, genesis wipe, and create=true exclusivity through the triple-entry path are sound; ship-blocking concern is F1 — the uninterrupted-sequence guard is only half-implemented (onStop only), so ritual state can survive biometric unlock and non-background re-lock and allow create on fewer than three uninterrupted lock-screen entries.**
