I'll review commit `321b358` as an independent adversarial security reviewer: start with the full diff, then verify each mandatory focus item against the primary source.Continuing through the rest of the store, app routing, markers, and tests.# Independent Adversarial Security Review — `321b358`

**Scope:** `feat/0.9.2-vault-slotb-pr1` @ `321b358`  
**Role:** findings only (no fixes, no edits)  
**Threat model:** physical device + forensic capability; crash/process-death at any instruction; durable vault image writers guilty-until-proven

---

## Focus 1 — INVARIANT 6 (delete markers)

### Full writer enumeration (post-change)

| Writer | Files | When | New in this commit? |
|---|---|---|---|
| `markDeleteIntent()` | creates `vault.delete-intent` + dir-fsync | before server DELETE | no |
| `markServerDeleteConfirmed()` | creates `vault.delete-confirmed` + dir-fsync | after definite server gone (2xx/404) | no |
| `destroy()` | ensures confirmed, unlinks bin/dek/tmps, then `clearBothMarkersDurably()` | account-delete completion | no |
| `clearDeleteIntent()` | unlinks intent only + re-stat + dir-fsync | explicit intent retire | no |
| `clearBothMarkersDurably()` (private) | unlinks **both** + re-stat + dir-fsync | callee of create / destroy / **Created** | shared |
| `create()` | **clears both first**, then writes bin+dek | **only when `!binFile.exists()`** | no (precondition load-bearing) |
| **`attemptUnlockOrAdd` Created** | **clears both first**, then rewrites **existing** `vault.bin` (DEK reused) | no-match + `create=true` | **YES** |
| `retireLegacyImage()` | unlinks bin/dek only | deliberate v2 retire | YES — **markers untouched** |

Unlocked / Burn / Rejected: no marker I/O.

### Full reader enumeration + assumptions

| Reader | Predicate | Assumption (what the bit is taken to mean) |
|---|---|---|
| `serverDeleteConfirmed()` | `vault.delete-confirmed` exists | Server account **provably gone** → **only** auth for `Route.DeleteIncomplete` auto-destroy |
| `deleteIntentPending()` | intent present **and** confirmed absent | Delete initiated, server outcome unknown → **must** post-unlock reconcile; **never** destroy |
| `hasDeleteIntentMarker()` | intent present-or-indeterminate (`!Files.notExists`) | Auth-protection lifetime: do not strip vault-backed tokens while true |
| Splash / session-null route | confirmed → `DeleteIncomplete`; else image → `Locked` / onboard | Confirmed cannot sit under a normal lock/create UI |
| `LaunchedEffect(session)` reconcile | `vaultDeleteIntentPending()` → `onDeleteAccount()` | Intent survives until destroy (or intentional clear) so crash recovery can finish DELETE |
| MessagingCoordinator auth guards | `hasDeleteIntentMarker` | Same auth-protection lifetime |

### Mid-write / post-crash states involving the **new** writer

Created branch order (`VaultImageStore.attemptUnlockOrAdd`, ~684–718):

1. `clearBothMarkersDurably()` (if not both `Files.notExists`)
2. seal / encode / outer-GCM
3. `atomicWrite(bin)` → advance `canonical` → durability check

| Crash / stop point | On-disk markers | Image | Reader outcome |
|---|---|---|---|
| Before clear | prior state | prior | unchanged |
| After both unlinked, before/without durable dir-fsync | indeterminate / possibly resurrected | prior | fail-closed paths vary; pre-existing clear semantics |
| **After durable clear, before `atomicWrite`** | **both absent** | **prior image still fully present** | **confirmed gone → no DeleteIncomplete; intent gone → no reconcile** |
| After rename, NotDurable throw | both absent | new image (B installed or partial) | session not handed `VaultOpen`; disk may hold B |
| Successful Created | both absent | image with B (and possible A survival / overwrite) | markers permanently retired |

### Findings

#### F1 — **High** — Confirmed-marker clear over a **live** image cancels auto-destroy (store invariant break)

- **FILE / FUNCTION:** `VaultImageStore.kt` — `attemptUnlockOrAdd` Created branch (~684–691) calling `clearBothMarkersDurably()` (~968–979)
- **DEFECT MECHANISM:** `create()` may clear markers only after `require(!binFile.exists())`, so confirmed never coexists with a successor **and** never gets wiped while a recoverable image remains. **Created has no such precondition**: it clears **both** markers while `vault.bin` is open and live, then may crash before the image write or successfully install B. That is not a mirror of `create()`; it is a new writer shape.
- **Reader assumptions broken:**
  - `serverDeleteConfirmed()` / Splash / session-null: “confirmed means finish local destroy; never treat as normal unlock/create surface.”
  - After durable clear + surviving image: `serverDeleteConfirmed()==false` and `hasVault()==true` → **Locked / normal create path**, not `DeleteIncomplete`.
- **FAILURE / ATTACK SCENARIO:**
  1. Account delete: server gone → `vault.delete-confirmed` durable; `destroy()` starts; crash mid-unlink → `{confirmed present, vault.bin still present}` (the state DeleteIncomplete exists to heal).
  2. Any caller invokes `attemptUnlockOrAdd(passB, genesis, create=true)` against that open image (miswired PR-2/3 router, test harness, future feature, or any path that does not re-check confirmed under the same lock as create).
  3. Durable clear runs; crash before `atomicWrite` **or** B is written.
  4. **Wrong outcome:** server account is gone; local full-crypto image (A material and/or B) **survives**; auto-destroy authorization is **gone permanently**; boot is lock/onboard, not forced destroy. No-remanence account-delete DoD fails.

App Splash currently routes confirmed → `DeleteIncomplete` (not lock). That is **routing**, not a store proof. This is the same “reader assumes marker meaning that a new writer can falsify” shape as rounds 12/15. **Do not accept “confirmed never appears at lock screen” as a store safety proof.**

#### F2 — **High** — Intent clear cancels A-side delete-reconcile (including when A still exists)

- **FILE / FUNCTION:** same Created branch; readers `deleteIntentPending()` (~1069–1070), `MainActivity` reconcile `LaunchedEffect` (~1055–1058), `hasDeleteIntentMarker()` (~1092–1093)
- **DEFECT MECHANISM:** Created unconditionally retires intent (and confirmed). Reconcile is keyed only on `deleteIntentPending()`. There is no other durable “delete owed” signal.
- **FAILURE / ATTACK SCENARIO (intent-only — realistic at lock screen):**
  1. User starts delete on vault A → durable `vault.delete-intent`; crash/failure before server confirm.
  2. Boot → Splash → **Locked** (intent-only by design, round 14).
  3. Triple-entry create of B (future PR-2) → Created → markers cleared.
  4. With probability ~2/3 (pool size 3, one occupied), B lands on a free pool slot → **A remains fully unlockable**.
  5. **Wrong outcome:** next unlock of A does **not** reconcile; A’s server account may remain forever; `hasDeleteIntentMarker` false so auth-protection lifetime ends even though a delete was initiated and not completed. Spec’s “fail toward no destruction” does **not** preserve “finish the delete the user started.”

#### F3 — **Medium** — Intent clear + blind overwrite of A → local A gone, server A may live, no retry marker

- **FILE / FUNCTION:** Created + `randomVaultSlotIndex` placement
- **DEFECT MECHANISM:** Same marker clear plus ~1/3 clobber of A’s slot.
- **SCENARIO:** Intent pending; B creation overwrites A; markers cleared. Local A destroyed; server A may still exist; no intent left to drive authenticated DELETE/404 reconcile. Orphaned server identity + silent local destruction of A.

#### F4 — **Info** — Spec’s “only intent-only at lock screen” is app policy, not an invariant of the durable state machine

Enumerating mid-destroy states shows `{confirmed, image present}` and `{intent, image present}` are both legal durable states. Only the latter is UI-reachable today; the former remains a legal store input to the new writer.

**Focus 1 verdict:** **NOT CLEAN.** New writer falsifies confirmed- and intent-reader assumptions; crash-between-clear-and-write is a concrete remanence/cancel path.

---

## Focus 2 — Dropped `unlockImage` re-verify

#### F5 — **Medium** — Remaining non-KDF checks do **not** prove the persisted slot is passphrase-openable before session handoff

- **FILE / FUNCTION:** `VaultImageStore.attemptUnlockOrAdd` Created (~693–718) vs `create()` (~487–491)
- **What remains:**
  - `sealSlot`: key-size require; `aeadEncrypt` output size enforced by `KeySlot` init (`WRAPPED_KEY_BYTES`)
  - `sealPayload`: capacity check; sealed size `== SLOT_PAYLOAD_BYTES`
  - `encodeImage` / outer encrypt structural checks
  - **No** decrypt of `candSlot.wrapped` under the just-derived master key  
  - **No** `openPayload(candKey, sealedGenesis)`  
  - **No** full-image `unlockImage(passphrase, newInner)`
- **DEFECT MECHANISM:** `VaultOpen` is built from in-memory `candKey` + `genesisPayload.copyOf()`, not from bytes that will be (or were) read back. A mis-seal (wrong AD, wrong key length handling in a bad `VaultSodiumOps`, swap of salt/wrapped, encode index bug, etc.) can still produce **size-correct** ciphertext.
- **FAILURE SCENARIO:**
  1. Created returns success; live session works entirely on heap `candKey` + copied genesis.
  2. Process death.
  3. Re-open + passphrase: `tryPassphrase` fails or payload AEAD fails → vault B **permanently unopenable**, while disk was marked durable Created.
  4. Under a compromised/buggy AEAD encrypt that does not invert under decrypt: same outcome without needing a full software implant narrative — the point is the durable write is not self-checked.

**EXPLICIT VERDICT:** **Insufficient** to guarantee the persisted slot is openable with `candKey` / passphrase before a live session is built over it. Size checks only bound layout, not seal invertibility or index placement correctness.

**Cheapest verify that preserves 5× Argon2id parity:** retain the master key inside the candidate `sealSlot` path long enough to run **one** 60-byte GCM **decrypt** of `candSlot.wrapped` and constant-time-compare to `candKey` (0 extra Argon2id). Optionally add **one** `openPayload(candKey, sealedGenesis)` (extra 256 KiB GCM on create only — same residual class as the already-accepted create persist work). Do **not** re-run `tryPassphrase`/`unlockImage` (would add 4 Argon2id and break parity).

---

## Focus 3 — Corrupt-payload asymmetry

**Implementation verified:**

| Match | Payload unreadable | Code | Outcome |
|---|---|---|---|
| Slot 0 (burn) | AEAD fail / throw | `runCatching { openPayload(...) }` then still `Burn` (~656–664) | **Burn** (deliberate) |
| Vault slot 1..3 | `null` or throw | wipe key → `CorruptImage` (~668–679) | **CorruptImage** |
| No slot match | n/a | create/reject | not treated as match |

- Matched vault + bad payload **cannot** fall through to create/reject: `unlock != null` is decided solely by wrapped-key match in `tryPassphrase`; payload handling is inside that branch.
- No crash path: vault maps throw→`CorruptImage`; burn swallows via `runCatching`.
- `openPayload` maps AEAD fail → `null` and corrupt pad → `null` (does not throw for normal crypto failures).

**Focus 3 verdict:** **CLEAN** (as specified; burn-on-damaged-marker not relitigated).

---

## Focus 4 — Timing parity

### Crypto budget (implementation)

`SLOT_COUNT = 4`.

| Step | Always? | Argon2id | Wrapped GCM | Payload GCM |
|---|---|---|---|---|
| `tryPassphrase` sweep | yes | 4 | 4 decrypts | 0 |
| `sealSlot` candidate | yes | 1 | 1 encrypt | 0 |
| Branch payload op | yes (all four outcomes) | 0 | 0 | 1 |
| **Total per call** | | **5** | **5** | **1** |

Create-only residual: ~1 MiB outer GCM + `atomicWrite` + `dirSync` (and marker clear I/O when markers not confirmed absent).

### Spec vs test discrepancy

- Spec §5 table / §8 text: **“one 60-byte wrapped-key GCM per call.”**
- `tryPassphrase` + `sealSlot` + test `cryptoBudgetParity_...`: **5** wrapped-key GCM (4 unwrap + 1 seal).
- **Resolution:** **test and implementation are correct**; the spec table under-counts by omitting the sweep unwraps. **Parity still holds** across unlock/burn/create/reject because all four run the same sweep + candidate seal.

### Armed vs unarmed slot 0

`tryPassphrase` always derives and unwraps every slot with no early exit; arm-state is only whether GCM auth succeeds. No branch on “slot 0 real vs filler” before the outcome `when`. **Timing-identical at the KDF layer** (pre-existing micro-asymmetry of GCM success vs fail remains, same as any real slot).

### Slot-0 exclusion

`randomVaultSlotIndex` = `1 + randomIndex(3)` — pure CSPRNG + modulo; no I/O. Create rewrites the **whole** outer image. No slot-index leak via write size/path.

### Additional residual (not in §5 “sole residual”)

#### F6 — **Low** — Marker-clear failure skips the mandatory payload GCM

- **FILE / FUNCTION:** Created (~687–691) throws `NotDurable` **before** `sealPayload` (~694)
- **DEFECT MECHANISM:** On non-durable marker clear, the call has done 5 Argon2id + 5 wrapped GCM but **not** the 1×256 KiB payload op that other outcomes always do.
- **SCENARIO:** Markers present; dir-fsync fails on clear → throw. Stopwatch/crypto-counter distinguishes this path from Rejected. Observable mainly as an error, not a silent oracle; still a broken “exactly one payload GCM across all paths” claim for the exception path.

#### F7 — **Info** — Create also does marker stat/clear I/O when markers may exist

Documented residual focuses on outer image write; marker clear is an extra create-only I/O side channel when markers are not both `notExists`.

**Focus 4 verdict:** **KDF + payload + wrapped counts are implemented as 5 / 1 / 5 and match across the four success outcomes.** Spec’s “1 wrapped GCM” is wrong; test is right. Minor exception-path residual (F6).

---

## Focus 5 — §10.1 legacy-image fix

### Structural block on slot-0 interpretation

| Path | Gate | Can reach `tryPassphrase` / slot-0 burn logic on v2? |
|---|---|---|
| `open()` | version byte after outer decrypt; v2 → `LegacyImage` **before** `canonical` install (~379–382) | **No** — failed open clears state/unregisters |
| `attemptUnlockOrAdd` | `canonical ?: open()` | **No** — open throws first |
| `unlock` | same | **No** |
| `unlockWithKey` | same | **No** |
| Direct `decodeImage` / `unlockImage` | `require(version == IMAGE_VERSION)` (v3) | **No** — throws, no sweep |

App routing (`isLegacyImage` LaunchedEffect, `onUnlockPassphrase` LegacyImage branch, `createVaultAndPublish` retire) is **backstop only**. Store structure holds even if UI is wrong.

### `isLegacyImage` / `retireLegacyImage` cannot delete v3

- `isLegacyImage`: `readInnerVersionOrNull() == 2` only (~275–276, ~865–882); corrupt/missing → false (no retire).
- `retireLegacyImage`: re-proves `version == LEGACY_IMAGE_VERSION` under `imageLock` **before** any unlink (~832–836); v3/null → `IllegalStateException`, files untouched.
- TOCTOU between peek and retire: second acquisition re-proves. Entire retire holds `imageLock`. External root rewrite between check and unlink is outside the app threat model for app-private storage; within-process concurrent store writers are serialized.

**Focus 5 verdict:** **CLEAN.**

---

## Focus 6 — General new defects

#### F8 — **Low** — Burn-unaware `addVaultSlot` / `addVaultToImage` remain callable

- **FILE / FUNCTION:** `VaultSlots.addVaultSlot` (~126–153), `VaultImage.addVaultToImage` (~214–232)
- **DEFECT MECHANISM:** Free index can be `BURN_SLOT_INDEX` unless caller puts 0 in `occupied`. Live path is `attemptUnlockOrAdd`, but primitives are still public/internal API surface.
- **SCENARIO:** Future wiring or test helper calls `addVaultToImage` with empty `occupied` → clobbers burn credential; duress pass stops matching.

#### F9 — **Low** — `unlockWithKey` still accepts `slotIndex == 0`

- **FILE / FUNCTION:** `VaultImageStore.unlockWithKey` (~573–592)
- **DEFECT MECHANISM:** Treats slot 0 as a normal vault payload open if a key is supplied (e.g. future biometric wrap of burn material).
- **SCENARIO:** Sibling burn/biometric work dual-wraps slot 0 → biometric path “unlocks” burn payload as a vault instead of wipe. Not wired in this commit; latent footgun.

#### F10 — **Info** — Same passphrase on burn and a vault slot → permanent Burn preference

- **FILE / FUNCTION:** `tryPassphrase` first-match + burn branch checks `slotIndex == 0` first among matches (index order)
- **SCENARIO:** User sets burn pass equal to vault A pass → every attempt returns Burn; A unreachable by passphrase. Product/setup issue for sibling burn PR.

#### F11 — **Info** — `randomBytes(4)` / modulo placement is **not** a uniqueness mechanism

Collisions are expected (~1/3). No bug; naming/assumption only. No integer overflow for `SLOT_COUNT=4`.

### Wipe / lock / durability notes (no extra finding if clean)

| Item | Assessment |
|---|---|
| `candKey` wipe on Unlocked/Burn/Rejected/NotDurable/throw; handoff on Created | Correct |
| Burn: wipe `unlock.vaultKey` + optional payload | Correct |
| Vault corrupt: wipe before `CorruptImage` | Correct |
| Outer `catch { wipe(candKey) }` | Correct (Created success does not enter catch) |
| `genesisPayload` caller-owned | Documented; Created uses `copyOf()` |
| `sealPayload`/`openPayload` do not wipe caller key | Confirmed in `VaultPayload.kt` |
| Lock order | Only `imageLock`; no `VaultSession` nesting |
| DEK reuse on Created | No `{bin, no-dek}` brick; stronger than create’s DEK-first barrier for this path |
| NotDurable + canonical advanced | Matches `writeSealedPayload` discipline; B may unlock in-memory / after durable rename |

No separate key-material Critical found beyond F5’s durable self-consistency gap.

---

## Focus-item scorecard

| # | Item | Result |
|---|---|---|
| 1 | INVARIANT 6 markers | **FAIL** — F1, F2, F3 |
| 2 | Dropped unlockImage verify | **FAIL** — F5 (verdict: insufficient) |
| 3 | Corrupt-payload asymmetry | **PASS** |
| 4 | Timing parity | **PASS** for happy-path 5/1/5; spec table wrong on wrapped count; F6 exception residual |
| 5 | §10.1 legacy / no v2→burn | **PASS** |
| 6 | General | F8–F11 lows/info; no additional Critical wipe/lock bugs |

---

## Overall verdict

**Not merge-clean for a production vault-image writer:** the Created branch’s dual-marker clear over a live image breaks the rounds-13–16 delete-marker reader contract (especially confirmed → auto-destroy and intent → reconcile), and Created still hands out a session without a zero-KDF invertibility check on the sealed slot.
