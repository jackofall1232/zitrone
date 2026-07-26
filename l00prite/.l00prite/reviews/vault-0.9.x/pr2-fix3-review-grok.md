I'll perform an independent adversarial security review of the triple-entry router delta and surrounding fixes. Starting from the protocol context and the exact git range.Reading the full router and the surrounding call sites that must still hold the already-verified fixes.Checking the store's create gate and any residual interleaving or cancel paths that could under-count.## Independent adversarial security review — 0.9.2 PR-2 triple-entry router (confirming pass)

**Range:** `a2e564f..021b19f` · **Branch:** `feat/0.9.2-vault-pr2-router` · **HEAD:** `021b19f`  
**Delta surface:** only `VaultUnlockRouter.kt` (`decideCreate` re-synchronized whole-method)

---

### 1. `decideCreate` atomicity & gate logic

| Check | Result |
|--------|--------|
| Whole method `@Synchronized` on `this` | **PASS** — L87–110 |
| Same monitor as `resetCandidate` / backoff | **PASS** — all `@Synchronized` on instance |
| SHA-256 inside monitor (one atomic op) | **PASS** — L93 under method sync; no pre-lock hash window |
| Always-run `MessageDigest.isEqual(hash, pending ?: NO_CANDIDATE)` | **PASS** — L95–98 |
| Create on 3rd consecutive identical | **PASS** — count 1→2→3; `CREATE_THRESHOLD = 3` |
| Differing string → count 1 | **PASS** — L104–107 |
| Cap at threshold | **PASS** — L102 |
| `resetCandidate` → null/0 | **PASS** — L120–123 |

**Reset-vs-hash interleaving:** **CLOSED.** Hash, compare, and counter mutation share one monitor section with `resetCandidate`. No remaining interleave between digest and state update.

---

### 2. Prior fixes intact at HEAD (outside delta; not disturbed)

| Fix | Location | Status |
|-----|----------|--------|
| `attemptPassphrase` resets on `CancellationException` | `ZitroneApp.kt` `attemptPassphrase` L412–417 | **INTACT** |
| `publishSession` ritual reset in `finally { if (published) … }` | `ZitroneApp.kt` `publishSession` L586–593 | **INTACT** (exception-safe; covers biometric/onboarding publish) |
| `VaultLockManager.onStop` resets unconditionally | `VaultLockManager.kt` L107–111 | **INTACT** |
| `resetRitual` required (no default) | `VaultLockManager.kt` L97 | **INTACT** (wired L335) |
| `recordFailure` in `onUnlockPassphrase` `onFailure` | `MainActivity.kt` L815–823 | **INTACT** |

Delta touches only `VaultUnlockRouter.kt`; call-site fixes unchanged.

---

### 3. Holistic: create with fewer than 3 consecutive identical uninterrupted entries?

**Answer: NO** — not via any path proven against source.

| Path | Why it cannot under-count |
|------|---------------------------|
| Gate math | Each `decideCreate` increments by at most 1; create only when `candidateCount >= 3` after that update |
| Store | `attemptUnlockOrAdd(..., create)` creates only if `create == true` and no match (`VaultImageStore.kt` L710–791); no cross-call create state |
| Differing passphrase | Resets streak to 1 |
| Match / Burn / Created | Caller `resetCandidate` before/on success |
| Rejected | Keeps streak but does not create (`create` still false until 3rd) |
| Cancellation | CE after store entry → `resetCandidate` (conservative wipe, not keep) |
| Background | `onStop` → `resetRitual` → `resetCandidate` between entries |
| Biometric success | `publishSession` `finally` resets if published |
| Concurrency | UI `unlocking` gate serializes submits; router methods serialized on `this` |
| Process death | RAM state gone |
| Onboarding `createVaultAndPublish` | First-vault path by design; not the lock-screen second-vault gate |

**Latched `create=true` mid-flight:** third entry may finish create if `onStop` fires *after* `decideCreate` returned true and *during* Argon2. Still requires three prior consecutive identical `decideCreate` advances — not fewer than three. Not a C/H/M under-count.

**Remaining C / H / M on triple-entry gate:** **none.**

---

### 4. New defects from the revert itself

| Severity | Finding |
|----------|---------|
| — | **None security-relevant.** Revert restores whole-method atomicity; gate predicates unchanged vs pre-experiment body. |
| Info (pre-documented) | SHA-256 holds `this` (~µs typical); called from off-main `attemptPassphrase`. Not C/H/M; not a create under-count. |

---

### Findings (C / H / M)

**None.**

---

### Verdict

**CLEAN** — triple-entry gate is atomic on `this`; prior fixes intact; no create path with fewer than three consecutive identical uninterrupted lock-screen entries; revert introduces no new C/H/M defect.
