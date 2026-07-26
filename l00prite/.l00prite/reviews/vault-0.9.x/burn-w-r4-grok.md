I'll perform an independent security review of the round-3 fix delta and cumulative unit, verifying every claim against source.Reading the load-bearing source sections in full for independent verification.# Independent Security Review — Unit W Round 4 (blind)

**Scope:** `b94d5a8` fix delta + cumulative `main...HEAD` (code only; `923fd37` ignored).  
**Method:** Source inspection of `MainActivity.kt`, `ZitroneApp.kt`, `VaultImageStore.kt`, `UnlockController.kt`, `VaultSlots.kt`, tests, `SECURITY_MODEL.md`. Gradle could not run in this environment (wrapper lock immutable + native library failure); tests judged by construction and by a host check of `Files.notExists` ENOTDIR semantics.

---

## Verdict on A — does `burnsCompleted` CLOSE the window?

**YES — it closes the recreated-composition window; it does not merely narrow it.**

### Mechanism (verified)

| Piece | Source |
|--------|--------|
| Process-scoped counter | `AppContainer.burnsCompleted = MutableStateFlow(0)` — `ZitroneApp.kt:258-262` |
| Bump on both outcomes | `onBurn` `finally` → `signalBurnCompleted()` — `MainActivity.kt:899-909` |
| Observer | `collectAsState()` + `LaunchedEffect(burnGeneration)` — `MainActivity.kt:740-752` |
| Burn work outlives composition | `container.scope` = `SupervisorJob() + Dispatchers.Default` — `ZitroneApp.kt:161`, launch at `MainActivity.kt:884` |
| No `configChanges` | `MainActivity` in manifest has none — recreation is real |

### Interleavings checked

1. **Composition dies mid-burn, burn then completes**  
   Live composition observes `0→N`, effect re-reads disk, routes to Onboarding if absent. Closes the original brick.

2. **Bump with no composition alive, then a new tree**  
   `StateFlow` retains the value. New composition’s `collectAsState()` starts at current `N ≠ 0`; `LaunchedEffect(N)` still runs. This is what makes a counter (not only a transition) necessary — and it is implemented that way.

3. **`LaunchedEffect` cancelled mid-`withContext(IO)`**  
   Cancel only at suspension points; a new effect for the new key re-reads disk. Safe.

4. **Conflation (`1→2` with no live collector)**  
   One observation of `2`, one disk re-read. Safe: disk is truth, not the integer.

5. **Splash-only re-read is correctly rejected**  
   Splash seeds/uses composition `vaultExists` (`MainActivity.kt:1337`). If Splash finishes while the burn is still in flight, the image is still present → Locked; the later completion write still targets the composition that started the burn (may already be disposed). Re-reading only in `Splash.onFinished` does not help a tree that has already left Splash. Claim is correct.

6. **Session collector / boot reconciler cannot rescue a live burn**  
   Session collector only acts on `live != null` or `unlocked && session→null` (`MainActivity.kt:781-803`); a burn has neither. Boot reconciler re-routes only when **it** finished `completeInterruptedBurn()` (`MainActivity.kt:708-720`). Confirmed.

7. **Process death mid-burn**  
   Counter resets to 0; cold start uses `hasVault` + `completeInterruptedBurn` / marker reconcile. Correct residual.

**Conclusion A:** Load-bearing fix is real and complete for the reported defect.

---

## Verdict on B — can the observer stomp routes it should not own?

### Successor vault — **safe**

Guards:

- `if (container.session.value != null) return` — live session not dragged to Onboarding.
- Re-derive `vaultExists` from disk; only route to Onboarding when `!vaultExists`.

After burn → create → lock → recreate: seed + re-read see the successor image; effect does **not** force Onboarding.  
Window after create lands on disk but before session publish: `createVaultAndPublish` does create+publish in one off-main block (`ZitroneApp.kt:441-477`); if a recreation occurs with bin present and session null, effect keeps Locked/Splash rather than wiping a present vault. Session publish still drives ChatList via the session collector.

### Failed burn — **safe**

Bump on both outcomes; `if (!vaultExists)` only then re-routes. Vault still present → route unchanged (stays lock-screen path). Matches the composition-local failure arm (`MainActivity.kt:919-933`).

### D2c / `DeleteIncomplete` — **latent incompleteness (LOW)**

```740:752:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
    val burnGeneration by container.burnsCompleted.collectAsState()
    LaunchedEffect(burnGeneration) {
        if (burnGeneration == 0) return@LaunchedEffect
        if (container.session.value != null) return@LaunchedEffect
        vaultExists = withContext(Dispatchers.IO) { container.hasVault() }
        if (!vaultExists) {
            unlocked = false
            lockError = null
            unlocking = false
            route = Route.Onboarding
        }
    }
```

Account-delete disk truth (`MainActivity.kt:1201-1208`, session collector `794-800`) is:

- `!hasVault && !serverDeleteConfirmed` → Onboarding  
- else if confirmed (or image still there) → **DeleteIncomplete**

The burn observer **never** consults `serverDeleteConfirmed()`.  

**Reachable only after** (1) a burn has completed in this process (`burnsCompleted > 0`), then (2) a successor account-delete stuck with image gone + `vault.delete-confirmed` present, then (3) composition recreation. Then the effect can set **Onboarding over a D2c-owned incomplete-delete state**.

**Why not higher / not blocking this unit:**

- Slot 0 is unarmed → production cannot bump the counter today (`VaultSlots.kt:126-128`, test `slot 0 is unarmed after create…`).
- Fail direction is Onboarding, not lock-over-absent-vault; `create()` clears markers before writing; cold-start Splash still prefers `serverDeleteConfirmed()` → DeleteIncomplete.
- No crypto fail-open; self-heals on create / process death.

**Concrete fix (before Unit S arms slot 0):**

```kotlin
if (!vaultExists) {
    if (container.serverDeleteConfirmed()) {
        route = Route.DeleteIncomplete
    } else {
        unlocked = false
        lockError = null
        unlocking = false
        route = Route.Onboarding
    }
}
```

### Threading — **correct**

`LaunchedEffect` runs on Main; `hasVault()` under `withContext(IO)`; state writes resume on Main with no suspension between `vaultExists =` and `route =`. No torn multi-frame window of consequence.

---

## Verdict on C — bump in `onBurn`’s `finally`

**Correct.**

- Ordered **after** `endTerminalWipe()` (`MainActivity.kt:903-909`): gate release first (stranded gate bricks unlock), then signal. `endTerminalWipe` is a synchronized flag write (`UnlockController.kt:188-190`) — does not realistically throw.
- Runs for success, `DestroyFailed`, and other failures (same `finally`).
- **Skipped bump:** process death before `finally` → cold start path. Gate-throw before signal (theoretical) → no UI write either; residual only. Not a practical hole.
- **Doubled bump:** only one call site; exclusive `tryBeginTerminalWipe` prevents concurrent burn workers. Harmless if doubled (extra disk re-read).

---

## Verdict on D — `primaryImageProvenAbsent()`

**Correct and correctly used.**

```277:278:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
    fun primaryImageProvenAbsent(): Boolean =
        imageLock.withLock { Files.notExists(binFile.toPath()) }
```

Gate for destructive cache retry (`ZitroneApp.kt:773-775`): `if (!primaryImageProvenAbsent()) return false`.

`imageStore.exists()` / `hasVault()` remain **routing** only (not used for this DELETE). Host check of the test’s ENOTDIR construction:

| API | Result on `<file>/vault.bin` |
|-----|------------------------------|
| `File.exists()` | `false` (old gate would clear) |
| `Files.notExists()` | `false` (new gate refuses) |

**Test is meaningful, not vacuous** — cases 1–2 agreement, case 3 disagreement is real.

---

## Findings

### LOW — burn observer can stomp D2c `DeleteIncomplete` when `burnsCompleted > 0`

| | |
|--|--|
| **Where** | `MainActivity.kt:747-751` |
| **Defect** | On `!hasVault`, always sets `Route.Onboarding` without checking `serverDeleteConfirmed()`. |
| **Why it matters** | After arming + same-process burn then incomplete account-delete + recreation, D2c’s finish-delete route can be overwritten. Self-heals; not live while slot 0 is unarmed. |
| **Fix** | Mirror delete/session routing: confirmed → `DeleteIncomplete`, else Onboarding (snippet above). |

### INFO — pre-existing `File.exists()` verify inside `obliterateLocked` (F)

`VaultImageStore.kt:1141-1144` still uses `exists()` for “file survived”. Indeterminate stat is treated as absence → **theoretical fail-open** on verify (opposite of `Files.notExists` discipline). Same pattern as `retireLegacyImage` / historical `destroy()`. Accept deliberate out-of-scope for this unit; tightening would change D2c `DestroyFailed` behavior. Do **not** treat as a round-3 regression.

### INFO — no pure-JVM test for `signalBurnCompleted` / counter semantics

Compose wiring is untestable here; the counter itself is a one-liner JVM seam. Optional hygiene, not a defect.

---

## Explicit answers E.1–E.7, F, G

| Item | Verdict |
|------|---------|
| **E.1** destroy ≡ keys-first obliteration | **Holds.** Shared `obliterateLocked()`; destroy only prefixes durable confirmed marker (`1069-1092`, `1117-1172`). Tests cover destroy + burn paths. |
| **E.2** Marker clear after durable unlinks | **Holds.** Order: unlink → verify → `dirSync` DURABLE → `clearBothMarkersDurably()`. Failure of durability leaves markers (`test markers are NOT cleared…`). |
| **E.3** Boot + `completeInterruptedBurn` | **Holds.** Signature: confirmed absent, dek proven absent, bin present (`1281-1286`). Defers to D2c if confirmed. Wired in boot `LaunchedEffect` (`701-721`). |
| **E.4** WRITER/READER for burn-touched signals | **Holds.** Image/markers under `imageLock`; burn success via `obliterationComplete` (all image-bearing paths); `burnsCompleted` RAM-only writer `signalBurnCompleted` / reader composition; terminal-wipe exclusive claim. No durable “burn happened” marker (by design). |
| **E.5** Reachability | **Holds.** Slot 0 filler at create (`VaultSlots.kt:140-142`); wipe only `PassphraseOutcome.Burn → onBurn` (`MainActivity.kt:953`, `855-936`); store returns `Burn` only on slot-0 match and writes nothing (`696-704`). Structurally unarmed. |
| **E.6** Concurrency / lifecycle | **Holds.** `tryBeginTerminalWipe` exclusive (`UnlockController.kt:182-186`); process-scoped burn job; composition-safe completion via counter; dual-burn co-owner bug fixed in round 2 and still closed. |
| **E.7** Fail-closed | **Holds.** Success = `runCatching { burnVault() }.isSuccess && burnObliterationComplete()` (`896-897`). Partial burn → uniform failure, not Onboarding. Tolerated app-local cleanup-before-image residual disclosed (`SECURITY_MODEL.md` + comments). |
| **F** `exists()` in `obliterateLocked` | **Agree out of scope** for this unit; residual noted as INFO above. |
| **G** Test / disclosure quality | **Honest and sufficient.** Store/gate/unarmed/interrupt paths are JVM-tested; post-burn UI reconciliation is inspection-only and **stated** in `SECURITY_MODEL.md:638-647`. Gap is real; papering it with a vacuous test would be worse. Optional: unit-test `burnsCompleted` increment only. |

---

## Round-3 fix claims — verified or not

| Claim | Result |
|-------|--------|
| Composition-unsafe burn completion fixed by process-scoped counter | **Verified real and complete (A)** |
| Splash-only re-read insufficient | **Verified** |
| False “re-derives on its own” comment removed/replaced | **Verified** (only appears as historical correction at `:879`) |
| Successor vault not dragged to Onboarding | **Verified** |
| Failed burn stays on lock path | **Verified** |
| `primaryImageProvenAbsent` tristate + real ENOTDIR test | **Verified** |
| Comments asserting safety of the new signal | **Match code**, except D2c interaction was never claimed and is incomplete (LOW) |

No contradiction of prior rounds’ accepted core conclusions on keys-first, marker order, fail-closed success proof, exclusive wipe gate, or unarmed slot 0.

---

## READY TO MERGE?

**Yes — ready to merge.**

The round-3 load-bearing fix **closes** the mid-burn Activity-recreation brick/tell; the cache-gate tristate fix is correct and non-vacuously tested. One **LOW** latent D2c routing gap in the new observer should be closed before slot 0 is armed (Unit S), not as a hard block on this unarmed mechanism unit.

**Do not invent further findings:** the composition signal is not a partial race-narrowing patch; under inspection it is the right process-scoped observable for a terminal state with no session.
