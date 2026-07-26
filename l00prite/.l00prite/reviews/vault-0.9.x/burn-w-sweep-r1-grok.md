I'll conduct an independent security review of the residue-sweep delta against source, starting with the commit and cumulative unit, then verifying every safety property from the code.The patch output was empty — I'll pull the delta and the key source files directly.I'll read the sweep implementation and every related path in the source.Checking every Onboarding route for the claimed `vaultProvenAbsent()` requirement:# Security Review — Unit W residue sweep (`c144216`) — Round 1 (blind)

**Scope reviewed against source:** `VaultImageStore.sweepOrphanedResidue` / `obliterateLocked` / boot healers, `MainActivity` boot + Splash + burn arms, `AppContainer.attemptPassphrase` / `postBurnRoute`, `SweepOrphanedResidueTest`. Cumulative burn path re-derived from the same sources (not prior PASS notes).

**Verdict: NOT READY TO MERGE** — one HIGH ownership hole in the sweep gate; remaining items are LOW/INFO.

---

## A. THE SWEEP GATE — **FAIL (too narrow on one ownership cell; not too broad on live vaults)**

### Gate as implemented

```1331:1349:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
    fun sweepOrphanedResidue(): Boolean =
        imageLock.withLock {
            // GATE 1 — the image must be PROVEN absent. Present or indeterminate both refuse.
            if (!Files.notExists(binFile.toPath())) return@withLock false
            // GATE 2/3 — no delete may be pending or confirmed. `!Files.notExists` is true when the
            // marker is present OR indeterminate, so a failing stat refuses rather than sweeping
            // state that D2c owns.
            if (!Files.notExists(deleteIntentFile.toPath())) return@withLock false
            if (!Files.notExists(serverDeletedFile.toPath())) return@withLock false
            // Already clean — the ordinary cold start. Do no destructive work and claim nothing.
            if (imageBearingFilesProvenAbsent()) return@withLock false

            dekFile.delete()
            deleteLeftoverTmp(dekFile)
            deleteLeftoverTmp(binFile)

            // PROVE it, then make it durable — same discipline as obliterateLocked's steps (2)/(3).
            if (!imageBearingFilesProvenAbsent()) return@withLock false
            dirSync(baseDir) == DirSyncResult.DURABLE
        }
```

### Live-vault / create / burn readings — over-deletion

| State | Gate | Correct? |
|---|---|---|
| Live `{bin present}` | G1 refuse | Yes |
| Bin stat indeterminate (ELOOP self-symlink verified: `File.exists()==false`, `Files.notExists()==false`) | G1 refuse | Yes |
| Interrupted create `{dek, no bin}` / `{dek, bin.tmp}` | Sweep | Yes — `create()` DEK-first; `open()` is MissingImage; retry overwrites |
| Partial burn `{no bin, dek/tmp}` no markers | Sweep | Yes — orphan unreachable under both burn and create readings |
| Mid-update live vault | Never loses `vault.bin` under `ATOMIC_MOVE` while holding `imageLock` | Yes |
| Confirmed delete mid-unlink | G3 refuse → `DeleteIncomplete` | Yes |

**Central claim (delete orphan under both interrupted-create and partial-burn):** holds for marker-free residue. No third reading produces a live vault without a proven-present `vault.bin` under this codebase’s writers (`create` DEK-first, `writeSealedPayload` atomic replace, `destroy` confirmed-then-obliterate).

**Concurrency:** every vault-file writer takes `imageLock` (`create`, `writeSealedPayload`, `destroy`/`obliterateForBurn`, marker writes, healers). Sweep takes the same lock. Sufficient within the single-store-per-dir contract.

**`{bin present}` as live signature:** yes for this store. Legacy v2 still has `vault.bin`. Mid-rename never publishes “no bin” for an existing vault. Temp alone is never treated as the live image by `open()` (temps deleted; main file authoritative).

### HIGH — table/gate wrong on delete-intent + residue

**Finding 1 — HIGH**  
**Where:** `VaultImageStore.kt:1338` (gate 2) + kdoc rows 6 / ownership claim (~1308–1312)  
**Defect:** Gate 2 refuses whenever `vault.delete-intent` is present/indeterminate, including when `vault.bin` is **proven absent** and residue (`vault.dek` / `*.tmp`) remains. The table says “D2c owns it.” That is false for this shape.

Legitimate D2c intent-in-flight still has a live image: destroy only unlinks after `vault.delete-confirmed` is durable. So **intent + present bin** is already refused by gate 1. Gate 2 does not protect any additional live-vault state.

Reachable bad state (mechanism, once slot 0 is armed):

1. Account delete writes `vault.delete-intent` (bin still present) → lock screen still offered.  
2. Duress burn runs `obliterateLocked` (keys-first).  
3. Partial failure after `bin` unlinked with `dek` and/or `vault.bin.tmp` left (verify throws; markers not retired).  
4. Disk: `{no bin, residue, intent present, no confirmed}`.

Who owns it then?

| Healer | Gate | Result |
|---|---|---|
| `sweepOrphanedResidue` | intent present | **refuses** |
| `completeInterruptedBurn` | needs bin present | **no** |
| `reconcileOrphanedBurnMarkers` | needs all image-bearing proven absent | **no** (residue) |
| Splash / re-derive | `vaultProvenAbsent()==false` | Locked (not success) |

**No path deletes the residue.** If `vault.dek` + `vault.bin.tmp` both survive, that is a **recoverable outer image** left on disk forever (fail-closed UI, failed wipe completeness). Prompt bar: *a sweep that fails to fire is a bug*.

**Why it matters:** Closes the advertised cold-start gap only for marker-free residue. Co-presence of a pre-existing intent re-opens the exact failure (recoverable ciphertext after “burn”) with no self-heal.

**Concrete fix:** Drop gate 2 (intent), keep gate 1 + gate 3 (confirmed). Intent without a proven-present image is not a live D2c unlink; after residue is gone, existing step `(b) reconcileOrphanedBurnMarkers()` can retire the orphan intent. Add a test: `{dek or bin.tmp, no bin, intent present, no confirmed}` → sweep deletes residue, dek/tmp gone; optionally assert reconcile then clears intent.

---

## B. FAIL-CLOSED-NESS OF THE GATE — **PASS** (with one claim niggle)

Every admission check uses `Files.notExists` / `!Files.notExists` correctly:

- G1: proceeds only on proven bin absence; present **or** indeterminate refuse.  
- G2/G3: `!Files.notExists(marker)` → present **or** indeterminate refuse.  
- Post-unlink: `imageBearingFilesProvenAbsent()` before success.  
- Unlink order does not touch `binFile` (already proven absent) — no “delete the live image” path.

**dirSync claim:** success requires `dirSync == DURABLE`, but **routing ignores the sweep return value** and only re-checks `vaultProvenAbsent()` (current re-stat). So dirSync does **not** gate onboarding; it only affects the boolean return. After crash + journal replay, cold start re-runs the sweep and Splash is still fail-closed on residue — so real-world safety holds; the kdoc slightly oversells dirSync as the routing barrier.

**INFO (not merge-blocking):** Document that durability is defense-in-depth for the return value; routing’s real fail-closed is `vaultProvenAbsent()` + re-boot.

---

## C. ORDERING — **PASS**

Boot order in `MainActivity` (~703–722): `(a0) sweep → (a) completeInterruptedBurn → (b) reconcile markers → (c) cache retry`, then unconditional re-derive if `session == null`.

Re-derive (~729–738):

- `confirmed` → `DeleteIncomplete` only  
- `provenAbsent` → **only** `Locked → Onboarding`  
- Does **not** stomp `ChatList`, live session, `DeleteIncomplete`, or in-flight create  

Splash can finish **before** `(a0)` completes; concurrent path is fail-closed: `!vaultProvenAbsent() → Locked` (~1432). Comment that no composition can route off half-cleaned disk is slightly strong; half-clean routes to Locked, not Onboarding.

Process-scoped burn observer and success arm both use `postBurnRoute` with success proof + proven absence + confirmed precedence — consistent.

Session collector (`else → Onboarding` on `!hasVault()`, ~849) is a weaker pattern, but with burn residue `unlocked` is already false so that branch does not re-open the cold-start hole in the burn path.

---

## D. `MissingImage` → uniform failure — **PASS**

```536:551:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
                    } catch (e: VaultImageException.CorruptImage) {
                        unlockRouter.resetCandidate()
                        return@withContext PassphraseOutcome.ImageUnreadable
                    } catch (e: VaultImageException.MissingImage) {
                        // ...
                        unlockRouter.resetCandidate()
                        unlockRouter.recordFailure()
                        return@withContext PassphraseOutcome.Rejected
```

- Absent image after partial burn must not say “damaged” (prior-use tell).  
- `recordFailure()` matches `Rejected` backoff — correct for indistinguishability.  
- `CorruptImage` keeps honest `ImageUnreadable` — present-but-unreadable is real device state.  
- No remaining caller needs `ImageUnreadable` for missing primary image; taxonomy in `open()` already splits Missing vs Corrupt.

---

## E. New defects from this delta — **one HIGH (A); rest clean**

- `vaultProvenAbsent()` on Splash + boot re-derive: correct.  
- Success arm via `postBurnRoute`: correct drift-proofing.  
- Failure arm `vaultExists = true` + `Locked` (~1013–1017): correct hold over residue.  
- Claim *“Onboarding requires proven absence everywhere”* is **overstated** (session collector / some delete paths still key on `hasVault()`), but those paths are not the cold-start burn hole this delta closed. **LOW** documentation/completeness, not a second burn fail-open under the reachable burn UI.

---

## F. Cumulative unit re-verify

| Item | Verdict |
|---|---|
| **F.1** destroy ≡ keys-first unlink | **PASS** — `obliterateLocked` deletes dek → dek.tmp → bin → bin.tmp (~1126–1129); `destroy` prefixes confirmed then same primitive. |
| **F.2** marker clear strictly after durable unlinks | **PASS** — verify + `dirSync` then `clearBothMarkersDurably()` (~1141–1172). |
| **F.3** boot set coherent | **FAIL** — marker-free residue closed; `{intent + residue + no bin}` owned by no one (Finding 1). Otherwise a0/a/b partition cleanly (bin absent vs present vs all-absent). |
| **F.4** WRITER/READER for burn signals | **PASS** with Finding 1 caveat on intent. Confirmed, intent, image-bearing absence, burn completion (`BurnCompletion.obliterated`) are otherwise consistent. |
| **F.5** reachability | **PASS** — slot 0 left random filler in `createVaultSlots`; wipe only via lock-screen `PassphraseOutcome.Burn` → `onBurn`. |
| **F.6** concurrency/lifecycle | **PASS** — `imageLock`, process-scoped burn completion, session gate on re-derive. |
| **F.7** fail-closed partial burn | **PASS** for presentation (no false Onboarding on residue without markers). **Incomplete wipe completeness** when intent co-present (Finding 1). Non-durable dirSync still throws in `obliterateLocked` (burn success refuses). |

---

## G. `File.exists()` inside `obliterateLocked` verify — **agree out of scope**

Pre-existing, inherited, weaker than `Files.notExists` on indeterminate stats. Not introduced by this delta. No objection to leaving it out of this round’s bar.

---

## H. Test quality — **PASS with known weak spots honestly labeled**

- Rows 1–4, 6–7, 9, durability return, idempotence, convert-to-`obliterationComplete` are real.  
- ELOOP row-5 test is load-bearing (consequence: DEK survives); matches real `Files.notExists` semantics (probed).  
- Weak baseDir-unstattable test documents vacuity — good.  
- **Missing test for Finding 1** (intent + residue).  
- Non-durable test only asserts `return false`, not “routing must not treat as clean” (files are already unlinked).  
- No Compose/instrumentation — acceptable; pure store + `postBurnRoute` coverage is the right layer.

(Unit tests could not be executed here: Gradle wrapper lock permission denied on this host.)

---

## Summary table

| ID | Sev | Location | Issue |
|---|---|---|---|
| 1 | **HIGH** | `VaultImageStore.kt:1338` (+ kdoc row 6) | Intent gate blocks sweep of recoverable residue; no other healer owns `{no bin, residue, intent}` |
| 2 | LOW | `MainActivity` session collector ~849; commit/docs “everywhere” | Onboarding not universally `vaultProvenAbsent()`; latent, not the cold-start burn path |
| 3 | INFO | Sweep kdoc / boot wiring | `dirSync` does not gate route; re-stat + re-boot do |

---

## READY TO MERGE?

**No.**

The destructive boot operation is **not** too broad against live vaults: G1/G3 and `imageLock` are sound; interrupted create vs partial burn is a valid dual reading for marker-free orphans; MissingImage remap and burn routing unification are correct.

It **is** too narrow (fails to fire) when `vault.delete-intent` coexists with image-bearing residue and no `vault.bin` — and the 9-row table incorrectly assigns that cell to D2c. Fix gate 2 (and add the row test) before merge. After that, an honest clean pass is plausible; I would not invent further blockers from this read of the code.
