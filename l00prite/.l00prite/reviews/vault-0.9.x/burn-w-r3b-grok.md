I'll perform an independent security review of the round-2 fix delta and cumulative unit, verifying every claim against source.Reviewing the fix delta and core burn/obliterate paths against source.Checking one more lifecycle edge case around burn completion after composition recreation:# Independent Security Review — Unit W Round 3 (blind)

Verified against source for `0dce2e6` and cumulative `main...HEAD` (`645b8a8` · `764845e` · `813245b` · `0dce2e6`). Commit messages and the prompt were not trusted.

---

## Findings

### MEDIUM — Burn completion does not re-derive route on a recreated composition

**Where:** `MainActivity.kt:842–874` (claim + write-only completion), `MainActivity.kt:630` (one-shot seed), `MainActivity.kt:701–721` (boot reconciler only), `MainActivity.kt:750–771` (session collector does not cover burn)

**Defect:** `onBurn` runs the wipe on process-scoped `container.scope` (good), then updates **composition-local** `vaultExists` / `route` / `unlocking` on the composition that started the burn. The comment asserts the opposite of what the code does:

```842:845:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
        // container.scope (process-scoped SupervisorJob), NOT the composition scope: a rotation mid-burn
        // must not cancel a half-finished destruction. UI state is marshaled to Main.immediate, exactly
        // as the account-delete wipe does; a composition recreated mid-burn re-derives its route from
        // disk truth on its own, so a write to a disposed composition is harmless.
```

A recreated composition only seeds `vaultExists` once (`remember { container.hasVault() }`). There is no process-scoped burn-completion signal. Account-delete is rescued by the session collector (`session → null` while `unlocked`); burn has **no session**, so that path never fires. `completeInterruptedBurn()` only re-routes when *it* finished a wipe at composition start — not when a concurrent in-flight burn finishes later.

**Reachable sequence (once slot 0 is armed):**
1. Duress burn starts; obliteration in flight on `container.scope`.
2. Activity recreation (rotation) → new composition seeds `vaultExists = true` (image still present).
3. Burn finishes; UI writes hit the **disposed** composition.
4. New composition: Splash → Locked over an **absent** vault. Unlock → `MissingImage` → `ImageUnreadable`. Stuck until process death (cold start seeds `hasVault()==false` → Onboarding).

**Why it matters:** Not a false-success wipe, not successor-vault destruction, gate is still released in `finally`. It **does** break post-burn ≡ fresh-install presentation under a realistic lifecycle event, and the code’s own safety comment is wrong. Keys are gone; the UI looks broken rather than first-run — a deniability residual.

**Not introduced by `0dce2e6`** — latent from the original Unit W wiring. Round-2 correctly fixed concurrent burn **ownership**; it did not fix completion **observation**.

**Concrete fix:** Process-scoped disk-truth signal (e.g. `MutableStateFlow` / shared `vaultPresent` updated after burn success/failure, collected by every live composition), or after `endTerminalWipe()` re-read `hasVault()` + `obliterationComplete()` on Main for the live tree — same pattern as the session collector. Splash `onFinished` should also prefer `container.hasVault()` over a stale `vaultExists` snapshot.

---

### INFO — `clearAllForWipe(): Boolean` is still discarded by the burn path

**Where:** `SettingsRepository.kt:110–118`, `ZitroneApp.kt:763`

Return value is honest; `tolerateCleanup { clearAllForWipe() }` still ignores it. Consistent with best-effort policy and SECURITY_MODEL. No defect — API truthfulness only.

---

### INFO — `BurnResult.plaintextCacheCleared` computed, discarded at call site

**Where:** `ZitroneApp.kt:145–157, 720–721`, `MainActivity.kt:858–859`

Intentional (duress tell). Documented. Accept.

---

### NOTE D — `File.exists()` verify inside `obliterateLocked`

**Where:** `VaultImageStore.kt:1128–1131` (same pattern `retireLegacyImage` `910–912`)

Agree: inherited from pre-unit `destroy()`, tightening is a D2c behaviour change. Fail-open direction for verify is “stat failed → treat as absent → may clear markers / report success while a file still exists under a broken filesystem.” Out of scope for this unit as stated; worth a tracked D2c follow-up, not a Unit W blocker.

---

## A — Exclusive-gate fix: **CORRECT AND COMPLETE**

| Question | Verdict |
|---|---|
| Exclusive claim real? | **Yes.** `tryBeginTerminalWipe()` is CAS-under-lock (`UnlockController.kt:182–186`). Second caller gets `false`. |
| Gate stranding? | **No practical strand.** Winner releases in coroutine `finally` (`MainActivity.kt:861–865`) — runs on success, failure, and cancellation. Gate is process-RAM only; process death clears it. `container.scope` is a long-lived `SupervisorJob` (`ZitroneApp.kt:161`), not composition-scoped. |
| Account-delete interaction? | **Safe under current reachability.** Delete uses non-exclusive `beginTerminalWipe()` but requires a live session (`MainActivity.kt:1068`). While burn holds the gate, `unlock()` is refused → no new session → delete cannot start. If delete already holds the gate, burn’s `tryBegin` fails → refused path, no work, no release. Neither flow can steal the other’s claim **as wired today**. Residual: if something later called `beginTerminalWipe()` without a session, co-ownership would return for that caller — not present now. |
| Refused claimant? | **Correct.** Uniform failure, `unlocking = false`, early return, no `endTerminalWipe`, no `burnVault` (`MainActivity.kt:834–840`). |
| 4 new tests? | **Meaningful, not vacuous.** Single-winner, refused cannot unlock while held, re-claim after release, 16-thread contention (`UnlockControllerTest.kt:275–333`). They prove the primitive; they do not integration-test `onBurn` wiring (that is inspectable and correct). |

Round-2 HIGH is fully closed.

---

## B — Round-2 introduced new defects? **NO**

| Fix | Source | Assessment |
|---|---|---|
| Exclusive burn gate | `UnlockController.kt:182–186`, `MainActivity.kt:834–865` | Correct; no stranding; no steal under current callers |
| `clearCacheDir` `Files.notExists` | `ZitroneApp.kt:1180–1194` | Real fail-closed; indeterminate falls through to list/delete/re-list |
| Post-obliteration cache always runs | `ZitroneApp.kt:714–721` | Short-circuit removed; second pass is sole `BurnResult` evidence |
| Docs + `clearAllForWipe` return | `SECURITY_MODEL.md:598–609`, `SettingsRepository.kt:110–118` | Honest; burn still tolerates settings failure by design |
| Vacuous test rename | `BurnAppLocalStateTest.kt:126–144` | Honest rename; gap stated |

No new security defect from the fix commit.

---

## C — Cumulative unit end-to-end

### C.1 destroy() equivalence / keys-first — **ACCEPT**

Pre-unit: `bin` then `dek`. Now shared `obliterateLocked()` is **dek then bin** (`VaultImageStore.kt:1110–1116`) after durable `vault.delete-confirmed` (`1056–1078`).

Crash mid-unlink always leaves `delete-confirmed` present → `Route.DeleteIncomplete` → idempotent `destroy()` retry. `completeInterruptedBurn` **defers** when confirmed is present (`1268–1273`, test at `BurnObliterateTest.kt:474–483`). Keys-first is strictly better crypto-erasure for burn; for destroy, recovery is marker-driven regardless of unlink order. **`keysFirst` param unnecessary.**

### C.2 Marker clear strictly after durable unlinks — **HOLD**

Order in `obliterateLocked`: wipe RAM → unlink keys-first → unregister → `exists()` verify → `dirSync` DURABLE → `clearBothMarkersDurably()` last (`1104–1159`). Durability-fail test keeps intent marker (`BurnObliterateTest.kt:239–250`). No path clears markers over proven-live image.

### C.3 Boot reconciliation + `completeInterruptedBurn` — **HOLD**

| Path | Guard | Safe? |
|---|---|---|
| `reconcileOrphanedBurnMarkers` | All image-bearing **proven** absent; confirmed **not** present (fail-closed); intent present | Yes — cannot clear intent over live vault |
| `completeInterruptedBurn` | Confirmed proven absent; DEK proven absent; bin **not** proven absent | Yes — inverse of create order (DEK first, bin second: `503–510`); interrupted create cannot match; D2c deferred |

Media loss of DEK alone also matches the signature → wipe of unopenable image. Acceptable (already unrecoverable; better than bricked lock). Cold-start every launch is safe: both are fail-closed no-ops on healthy vaults.

### C.4 WRITER/READER invariants (burn-touched durable signals)

| Signal | Writers | Readers | Burn behaviour |
|---|---|---|---|
| `vault.bin` / `vault.dek` / temps | create / attemptUnlockOrAdd create / obliterate / destroy | exists, open, obliterationComplete, boot paths | Obliterate unlinks; success only if proven absent |
| `vault.delete-intent` | markDeleteIntent; clear on obliterate/destroy/reconcile | deleteIntentPending, hasDeleteIntentMarker, B1 create fail-closed | Burn may clear orphaned intent after proven absence only |
| `vault.delete-confirmed` | markServerDeleteConfirmed / destroy prefix; clear last | serverDeleteConfirmed, DeleteIncomplete, completeInterruptedBurn defer | Burn **never writes**; may clear only after proven image absence |
| Device settings / cache / biometric / notifs | burn best-effort | UI / cold-start retry | Not success-gating |
| `terminalWipe` (RAM) | begin/tryBegin/end | unlock refuse, auto-lock skip | Exclusive for burn |

No invariant break found beyond the UI re-derive gap in C.6.

### C.5 Reachability — **HOLD**

- Slot 0 left filler by `createVaultSlots` (`VaultSlots.kt:141`); proven `BurnObliterateTest.kt:499–518`.
- Wipe wired only via `PassphraseOutcome.Burn` → `onBurn` (`MainActivity.kt:909`); `attemptPassphrase` is the only production caller.
- `attemptUnlockOrAdd` also returns `Burn` on slot-0 match for create=true (second-vault path) — store-correct; only UI maps Burn → wipe. Unarmed ⇒ structurally unreachable.

### C.6 Concurrency / lifecycle — **ONE GAP** (finding above)

Gate exclusivity: fixed. Account-delete co-ownership with burn: safe as wired.  
**Composition recreation mid-burn UI reconcile: broken** (MEDIUM). Disk destruction itself remains correct on process-scoped scope.

### C.7 Fail-closed — **HOLD** for crypto; honest residuals for app-local

Success requires `burnVault()` no-throw **and** `burnObliterationComplete()` (`MainActivity.kt:858–859`). Partial image destruction cannot present as onboarding. App-local cleanups before image can leave biometric/settings cleared while image survives — documented, retryable, not reported as success. Cache residual after successful key destruction — policy, disclosed.

---

## E — Still-untested failure shapes

1. **Rotation mid-burn → stale Locked** (the MEDIUM) — no test.
2. **`onBurn` refused path** never calls `endTerminalWipe` (wiring by inspection only).
3. **Undeletable cache file** (stated untestable under Robolectric) — still open.
4. **`File.exists()` verify fail-open** under stat fault inside `obliterateLocked` (D, out of scope).
5. **End-to-end `AppContainer.burnVault` + gate** under concurrent dispatch (primitive tested; orchestration not).
6. **`clearAllForWipe` commit()==false** behaviour.

Round-2’s four gate tests and the cache/obliterate suites are solid for what they claim.

---

## Verdict table

| Item | Result |
|---|---|
| **A** Exclusive gate | **PASS** — correct, complete, non-stranding, safe vs delete |
| **B** Round-2 regressions | **PASS** — no new defect |
| **C.1** destroy keys-first | **PASS** |
| **C.2** Marker ordering | **PASS** |
| **C.3** Boot / completeInterruptedBurn | **PASS** |
| **C.4** WRITER/READER | **PASS** |
| **C.5** Reachability | **PASS** (unarmed + lock-screen-only wipe) |
| **C.6** Concurrency/lifecycle | **FAIL** — one MEDIUM presentation/lifecycle gap |
| **C.7** Fail-closed | **PASS** (crypto); residuals disclosed |
| **D** exists() in obliterate | **Agree out of scope** |
| **E** Test gaps | Listed; not merge-blocking alone |

---

## READY TO MERGE?

**No — not a clean merge of the cumulative unit.**

Round-2 (`0dce2e6`) does what it claims: exclusive burn gate is real and complete; cache fail-opens closed; docs match behaviour. Those fixes are solid.

The cumulative mechanism still has **one MEDIUM**: burn completion is not composition-safe under Activity recreation, contradicting its own comment and breaking post-burn ≡ onboarding presentation. Disk wipe and the gate remain sound; this is lifecycle observation, not crypto fail-open.

**Merge bar recommendation:** fix the process-scoped route re-derive (small, localized), then re-check C.6 only. Slot 0 unarmed means production cannot fire the gap today — that lowers urgency, not correctness. Do not arm Unit S until this is fixed.

I am not inventing further findings. Aside from that MEDIUM, the unit holds under independent re-verification.
