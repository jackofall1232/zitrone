# Unit W (Pucker Burn wipe) — WRITER/READER invariant table

Built BEFORE code, per standing discipline (round-12 lesson). Every durable signal the burn path
touches, its complete writer set after the change, its complete reader set, and the crash-point
analysis. Source-verified against `VaultImageStore.kt`, `ZitroneApp.kt`, `MainActivity.kt`,
`MessagingCoordinator.kt` @ `c3e4038`.

---

## 0. The bounding fact (derived, load-bearing for the whole unit)

**A burn can only ever fire while `vault.delete-confirmed` is ABSENT.**

Proof from source:
- Burn fires only from `PassphraseOutcome.Burn` (`MainActivity.kt:805`), reachable only from
  `Route.Locked` (the lock screen).
- Boot routing reaches `Route.Locked` only when `serverDeleteConfirmed()` is FALSE — both at Splash
  (`MainActivity.kt:1183`: `serverDeleteConfirmed() -> Route.DeleteIncomplete` precedes
  `vaultExists -> Route.Locked`) and in the session-null collector (`MainActivity.kt:736`, same order).
- `markServerDeleteConfirmed()` is called only from the account-delete flow, which requires a LIVE
  session; the lock screen has none.

**Consequences (both are relied on below):**
1. Burn never observes, writes, or must reason about a live `delete-confirmed`.
2. Therefore burn can never produce the state `image-absent + confirmed-present`. The only producer of
   that state is D2c's own `destroy()` crash window, which already self-heals (§4.3) — unchanged.

A `delete-intent` CAN be present when burn fires (Splash routes intent-only to `Route.Locked` by
design — `MainActivity.kt:1184-1189`). This is the case the new reconciliation exists for.

---

## 1. `vault.bin` (the vault image)

| | |
|---|---|
| **Writers (after change)** | `create()` (atomicWrite), `attemptUnlockOrAdd()` create-branch (atomicWrite), `retireLegacyImage()`, **`obliterateLocked()` — unlinks (2nd)** |
| **Readers** | `exists()` → `hasVault()` → boot routing (`vaultExists`), `open()`, `isLegacyImage()`, `destroy()`/`obliterate` verify re-stat |
| **Burn's effect** | Unlinked, SECOND (after the DEK). Verified absent by re-stat; a survivor throws `DestroyFailed`. |
| **Invariant** | Post-burn `exists()` == false ⇒ `hasVault()` == false ⇒ boot routes to `Onboarding` (given confirmed absent, §0). |

## 2. `vault.dek` (the wrapped DEK envelope)

| | |
|---|---|
| **Writers (after change)** | `create()` (dek write), `retireLegacyImage()`, **`obliterateLocked()` — unlinks FIRST (keys-first)** |
| **Readers** | `open()` (DEK unwrap; a gone DEK maps to `CorruptImage`), verify re-stat |
| **Burn's effect** | Unlinked FIRST, before the image. |
| **Invariant (the point of keys-first)** | At every instant after step 1, the on-disk state is either (a) both present, (b) **image-without-DEK = cryptographically erased**, or (c) both gone. State (b) is unrecoverable by design; the reverse (DEK-without-image) is never observable. |

## 3. `vault.bin.tmp` / `vault.dek.tmp` (interrupted-write temps)

| | |
|---|---|
| **Writers** | `atomicWrite()` staging, `deleteLeftoverTmp()`, **`obliterateLocked()`** |
| **Readers** | `open()` (deletes a leftover first), verify re-stat |
| **Burn's effect** | Both unlinked alongside their primaries; **included in the verify** — round-8 lesson: `renameIntoPlace` stages a COMPLETE outer image in `vault.bin.tmp`, so under a failing FS an encrypted image copy could survive as a temp while the primaries are gone. A surviving temp is a FAILED wipe. |

## 4. `vault.delete-intent`

| | |
|---|---|
| **Writers (after change)** | `markDeleteIntent()`, `clearDeleteIntent()`, `clearBothMarkersDurably()` (via `create()` F2 and `destroy()` F4), **`obliterateLocked()` (via `clearBothMarkersDurably`)**, **`reconcileOrphanedIntentMarker()` (NEW)** |
| **Readers** | `deleteIntentPending()` (routing/reconcile), **`hasDeleteIntentMarker()` — the AUTH-PROTECTION guard** (`ZitroneApp.kt:724` → `MessagingCoordinator` `intentMarkerPresent`) |
| **Burn's effect** | Cleared, STRICTLY AFTER the image+DEK unlinks are proven durable (§6). |
| **Auth-guard interaction** | `hasDeleteIntentMarker()` gates token-clearing (`onSessionRevoked` must NOT strip vault-backed tokens while a reconcile may need them). Burn destroys the whole image and the tokens with it, and runs with no live session (§0) — so there is no post-burn consumer of that guard. **Review item:** confirm terminal exclusion (§7) means no live session can read a just-cleared marker mid-burn. |

## 5. `vault.delete-confirmed`

| | |
|---|---|
| **Writers (after change)** | `markServerDeleteConfirmed()`, **`destroy()` (crash-bridge — UNCHANGED)**, `clearBothMarkersDurably()`. **BURN NEVER WRITES IT.** |
| **Readers** | `serverDeleteConfirmed()` → the ONLY authorization for `Route.DeleteIncomplete` auto-destroy |
| **Burn's effect** | Never written. Cleared by `clearBothMarkersDurably()` only in the (per §0 unreachable-for-burn) case where it was already present. |
| **Invariant (the core Q2 fix)** | Burn asserts NO false "server account confirmed gone" fact, and can never authorize a `DeleteIncomplete` auto-destroy or provoke a later real network DELETE. |

## 6. RAM DEK / `canonical` / `OPEN_PATHS` registration

| | |
|---|---|
| **Writers** | `open()`, `create()`, `close()`, `destroy()`, **`obliterateLocked()`** |
| **Readers** | every crypto op; `register()`/`unregister()` single-instance contract |
| **Burn's effect** | DEK wiped + `canonical` dropped FIRST (before any path that can throw); registration released via `unregister()` so a re-onboard can re-open the same dir in the SAME process. |

## 7. Keystore biometric aliases + `biometricStore` prefs

| | |
|---|---|
| **Writers** | `enableBiometricFromSession()`, `destroyVaultForAccountDeletion()`, **`wipeBiometricMaterial()` (factored, NEW shared helper)** |
| **Readers** | `BiometricUnlockStore.isEnabled()`, `boundSlotIndex()`, lock-screen affordance |
| **Burn's effect** | Cleared under `biometricWriteLock` (same lock as enable-commit, so a racing in-flight enable can't re-persist a wrap — it aborts on its `keyExists` check). |
| **Invariant** | No orphaned Keystore alias survives a burn ("something was here" residue → the byte-for-byte gate catches it). |

---

## 8. Ordering + crash-point analysis (`obliterateLocked()`)

Sequence (BINDING — the marker clear is strictly last):

```
S0  wipe RAM DEK; canonical = null                    [no durable effect]
S1  unlink vault.dek + vault.dek.tmp                  [KEYS FIRST]
S2  unlink vault.bin + vault.bin.tmp
S3  unregister()                                      [no durable effect]
S4  verify all four absent (re-stat)                  → throw DestroyFailed on any survivor
S5  dirSync(baseDir) DURABLE                          → throw DestroyFailed if not
S6  clearBothMarkersDurably()                         → throw DestroyFailed if not
```

| Crash at | On-disk state | Next boot | Safe? |
|---|---|---|---|
| before S1 | image + DEK intact, markers as-was | normal (Locked / intent-reconcile) | ✅ vault survives; burn simply didn't happen |
| between S1 and S2 | **image without DEK**, markers as-was | `exists()`==true → `Route.Locked`; any unlock attempt → `open()` → DEK gone → `CorruptImage` → `ImageUnreadable` escalation | ✅ **cryptographically erased** — this is the keys-first payoff. Data unrecoverable. ⚠ leaves a *visible* damaged-image state (see §9 residual R1) |
| between S2 and S6 | image + DEK gone, markers possibly present | `hasVault()`==false, `serverDeleteConfirmed()`==false (§0) → **`Route.Onboarding`** ✅; a surviving `delete-intent` is swept by `reconcileOrphanedIntentMarker()` | ✅ the window review item #3 names |
| after S6 | clean | `Route.Onboarding` | ✅ terminal success |

**Why the marker clear MUST be last (S6, never earlier):** clearing markers while the image still
exists reproduces PR-1's B1 failure state — markers say "nothing pending" over a live vault, so a
genuine in-flight account-delete would lose its reconcile signal (and, for `delete-confirmed`, its
auto-destroy authorization) while the vault is still on disk. Because S4/S5 prove the image ABSENT
and DURABLY so first, the markers at S6 are **orphaned by definition** — the same precondition that
makes `create()`'s F2 clear safe (`require(!binFile.exists())`).

## 9. Boot reconciliation (`reconcileOrphanedIntentMarker()`) — scope is deliberately surgical

Fires only on: **image absent AND `delete-confirmed` absent AND `delete-intent` present** → clear both
markers durably.

- It does **not** touch the `confirmed`-present case. That state (image-absent + confirmed-present) is
  produced only by D2c's own `destroy()` crash window, and already self-heals today: boot →
  `Route.DeleteIncomplete` → `onRetryDestroy` → idempotent `destroy()` → markers retired → `Onboarding`
  (`MainActivity.kt:1204-1211`, `636-658`). Touching it would be unreviewed scope creep into D2c.
- It does **not** touch the image-PRESENT case: a `delete-intent` over a live vault is a genuine
  pending reconcile (round-14 F1 — Splash must never clear it).

**Answer to review item #3** ("an image-absent state can never route into `DeleteIncomplete` under any
crash point"): for **burn-produced** states this holds unconditionally, because burn never writes
`delete-confirmed` (§0/§5) and `serverDeleteConfirmed()` is the sole `DeleteIncomplete` authorization.
The one image-absent state that DOES route to `DeleteIncomplete` is pre-existing D2c behavior, is
self-healing, and is untouched by this unit — stated here explicitly rather than silently.

## 10. Documented residuals (not defects — disclose, don't hide)

- **R1 — interrupted-burn visible damaged state.** A crash between S1 and S2 leaves an image whose DEK
  is gone: data is cryptographically erased (the security property holds), but the app shows an
  unreadable-image escalation rather than clean onboarding, which is a *tell* that something was
  destroyed. Unavoidable without a durable pre-burn intent marker — and a burn-intent marker would be
  exactly the discoverable armed/in-progress artifact the design forbids. Window is two unlinks wide.
  → discloses to `SECURITY_MODEL.md`.
- **R2 — post-burn wall-clock.** The uniform KDF sweep hides *which* outcome occurred, but the
  subsequent unlink + Keystore + prefs teardown is stopwatch-observable after the outcome. Accepted
  residual (same class as the create-persist residual). → `SECURITY_MODEL.md`.
- **R3 — `destroy()` unlink-order change.** See the named review item: `destroy()` goes bin-then-dek →
  dek-then-bin. End state identical; the confirmed-marker crash-bridge makes re-destroy idempotent at
  any crash point regardless of order. This is an argument, not a proof — reviewers evaluate it, and
  the `keysFirst` param fallback is the landing spot if a reviewer rejects the shared ordering.
