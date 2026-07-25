I'll conduct an independent security review of the residue-sweep delta by verifying every claim against source, starting with protocol context and the fix/delta commits.Reading the current source for the durability path, boot routing, and healers.# Independent Security Review — Residue Sweep Round 2  
**Reviewer:** blind (no other reviewer’s findings)  
**Scope:** `98c0319` + `c144216`, cumulative Unit W through HEAD  
**Method:** source only — commit messages and kdoc treated as claims to disprove

---

## Executive verdict

**NOT READY TO MERGE.**

Round 1’s two code-level fixes are real: the store returns a tristate durability verdict, and the intent gate that stranded row 6b is gone. The pure `bootRoute` consumer is correct.

The durability fix is **not** end-to-end. The same structural failure mode this unit has already hit three times is back one layer out: an authoritative result is computed on a **composition-scoped** coroutine, then can be discarded before the process-scoped hold is published — while Splash routes without waiting for that publish.

---

## Findings

### Finding 1 — HIGH  
**`MainActivity.kt:728–744`, `ZitroneApp.kt:823–829`**

**Defect.** Boot reconciliation is claimed once per process (`tryBeginBootReconcile` CAS) but executed inside a **composition** `LaunchedEffect`. Publishing `residueSweepHold` / `bootReconciled` is **not** in `NonCancellable` and **not** on `container.scope` (unlike burn / account-delete).

```728:744:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
        if (container.tryBeginBootReconcile()) {
            val sweep = withContext(Dispatchers.IO) {
                val result = runCatching { container.sweepOrphanedVaultResidue() }
                    .getOrDefault(ResidueSweepResult.SWEPT_NOT_DURABLE)
                bootReconcileRest(container)
                result
            }
            container.residueSweepHold.value = sweep == ResidueSweepResult.SWEPT_NOT_DURABLE
            container.bootReconciled.value = true
```

On Activity recreation while `withContext(IO)` is in flight:

1. CAS has already flipped → no later composition may re-run the sweep.
2. The IO block can finish (non-suspending store work runs to completion).
3. Cancellation discards the return at the composition boundary → **hold is never written**.
4. `bootReconciled` stays `false` → every later composition blocks forever on `bootReconciled.first { it }`.

**Why it matters.** This is standing instruction 3 again: authoritative `ResidueSweepResult` computed, discarded, weaker signal used at the consumer. After a non-durable sweep, disk already re-stats clean (`vaultProvenAbsent()==true`) while `residueSweepHold` remains `false` → onboarding over residue a journal replay can resurrect — the exact round‑1 HIGH, under rotation mid-boot.

The unit already fixed this class for burn by moving work to `container.scope` + publishing outcome in `finally`. Boot did not get that treatment.

**Concrete fix.**

- Run claim + sweep + hold publish on `container.scope` (process-scoped), or wrap publish in `withContext(NonCancellable)`.
- On failure to publish, either publish fail-closed (`hold=true`, `bootReconciled=true`) or reset the CAS so another composition can retry.
- Never CAS-claim without a guaranteed publish path.

---

### Finding 2 — HIGH (pairs with #1)  
**`MainActivity.kt:729–730` (false claim), `1449–1483` (Splash), `SplashScreen.kt:48–58`**

**Defect.** kdoc/asserted invariant:

> “Splash blocks on `bootReconciled` below”

Splash does **not** wait for `bootReconciled`. `SplashScreen` finishes on a fixed animation timeline and calls `onFinished()` with no boot gate. Splash then reads `residueSweepHold` and `vaultProvenAbsent()` directly:

```1472:1481:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
                        else -> when (
                            bootRoute(
                                serverDeleteConfirmed = false,
                                vaultImagePresent = false,
                                residueSweepHold = container.residueSweepHold.value,
                                vaultProvenAbsent = container.vaultProvenAbsent(),
                            )
                        ) {
                            BootRoute.ONBOARDING -> Route.Onboarding
                            else -> Route.Locked
                        }
```

Only the boot re-derive effect waits on `bootReconciled` (`:752`). That re-derive is **one-way**:

```768:769:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
                BootRoute.ONBOARDING -> if (route == Route.Locked) route = Route.Onboarding
                BootRoute.LOCKED -> Unit
```

It never demotes `Onboarding → Locked` when the hold appears late or was lost.

**Why it matters.**

- With Finding 1 (lost hold): Splash routes with `hold=false` + post-unlink `provenAbsent=true` → **ONBOARDING**, and nothing pulls it back.
- Without Finding 1, normal splash latency (~1.8s) usually finishes after boot — so this is latent under happy timing, load-bearing under cancel/missed publish.
- Standing instruction 4: pure `bootRoute` is tested; **wiring that Splash actually waits for the hold** is not.

**Concrete fix.**

- Splash `onFinished` (or a wrapper) must `bootReconciled.first { it }` before any fresh-install decision.
- Re-derive should also apply fail-closed demotion: if decided `LOCKED` and route is `Onboarding` with no session, move to `Locked` (or never leave Splash until boot finishes).

---

### Finding 3 — LOW  
**`MainActivity.kt:875–888` (session collector)**

**Defect.** Session-null → Onboarding still keys on `vaultProvenAbsent()` only — **no** `residueSweepHold`.

```881:887:apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
                    !container.vaultProvenAbsent() -> Route.Locked
                    else -> Route.Onboarding
```

**Why it matters.** Claim “onboarding requires proven absence everywhere” is still incomplete for the durability half. Burn has no session (collector gated on `unlocked`), so this is not the burn path — but it is another consumer that re-derives cleanliness without the carried verdict.

**Concrete fix.** Route session-null through `bootRoute(..., residueSweepHold = container.residueSweepHold.value, ...)`.

---

### Finding 4 — INFO  
**`VaultImageStore.kt:1401–1403` (and commit message)**

**Defect (documentation only).** Claim: `create()` “refuses to run while either marker is present.”

Source: `create()` **clears** markers when not both proven absent (`:509–514`), it does not refuse. The intent-gate safety argument does **not** need this claim — it rests on `destroy()` writing confirmed **before** unlinks (`:1117–1118`), which is real.

No functional change required for B; correct the kdoc so the next table is not built on a false row premise.

---

## Focus verdicts

### A. Durability verdict end-to-end — **FAIL**

| Checkpoint | Status |
|---|---|
| Store returns tristate; mutation point forbids `NO_MUTATION` after unlinks | **PASS** (`:1417–1436`) |
| Total `catch (Throwable) → SWEPT_NOT_DURABLE` past mutation | **PASS** (fail-closed; sync path, no real CE) |
| Hold set only for `SWEPT_NOT_DURABLE`; sticky for process | **PASS** (not spuriously set on healthy durable path) |
| Hold / reconciled lost on composition cancel after CAS | **FAIL** (Finding 1) |
| Splash consumes hold without waiting for publish | **FAIL** (Finding 2) |
| Re-derive cannot correct premature Onboarding | **FAIL** (Finding 2) |
| Session collector omits hold | **LOW** (Finding 3) |

`bootRoute` pure function is correct. The **consumption path is not sealed**.

### B. Dropping the intent gate — **PASS**

Independent state enumeration (image-bearing residue, no proven-present bin):

| # | State | Owner / action | Gate |
|---|---|---|---|
| 1–3 | residue, no markers | unreachable data (partial create **or** burn) | **SWEEP** |
| 4 | bin present | live vault | gate 1 refuse |
| 5 | bin stat indeterminate | fail-closed | gate 1 refuse |
| 6 | intent + bin present | D2c in flight | gate 1 refuse |
| **6b** | intent + no bin + residue | partial burn while intent outstanding — **not** D2c unlink | **SWEEP** then reconcile |
| 7 | confirmed present | `DeleteIncomplete` / `destroy` | gate 2 refuse |
| 8 | confirmed indeterminate | fail-closed | gate 2 refuse |
| 9 | nothing present | already clean | `NO_MUTATION` |

**Proof intent gate was pure stranding:** `destroy()` writes confirmed durably **before** `obliterateLocked()` (`:1117–1118`). Every real D2c unlink already carries confirmed → gate 2. Intent alone + absent bin is not a legitimate D2c mid-unlink state.

**Preserve residue under intent?** No D2c reader needs residual dek/tmp without a bin: with confirmed, DeleteIncomplete re-runs full destroy; without confirmed + bin present, unlock/reconcile still has the image. Sweep under intent does not break intent retry: boot order is sweep → completeInterruptedBurn → reconcile, so row 6b unblocks marker retire (test asserts this).

No missing owner-row found that the corrected table omits for this scope.

### C. Process-scoped boot state — **FAIL**

| Property | Verdict |
|---|---|
| Hold/reconciled on container (survive rotation if published) | intended PASS |
| Once-guard prevents re-running destructive work | PASS when publish succeeded |
| Composition after boot observes result | PASS if `bootReconciled` became true |
| Process death resets | PASS (new process, fresh flows) |
| No route before `bootReconciled` | **FAIL** — Splash does not wait |
| Once-guard strands if claimer dies before publish | **FAIL** (Finding 1) |

### D. `bootRoute` precedence + two consumers — **PARTIAL FAIL**

Precedence in pure function: confirmed → image → hold → proven absent → else Locked. **PASS.**

| Consumer | Same decision function? | Same inputs? |
|---|---|---|
| Boot re-derive (`:758–763`) | yes `bootRoute` | yes, after `bootReconciled` |
| Splash `onFinished` (`:1472–1478`) | yes `bootRoute` | **no wait** for hold publish; races Finding 1 |

Splash is not a pure re-derive of the post-boot snapshot; it is a concurrent consumer of possibly pre-publish state.

### E. New defects from `98c0319` — **YES**

| Change | Assessment |
|---|---|
| Tristate + hold + `bootRoute` | correct direction |
| Drop intent gate + row 6b | correct and necessary |
| Session collector → `vaultProvenAbsent` | good partial fix; still missing hold |
| Unconditional re-derive (vs only after interrupted burn) | correct |
| `bootReconcileRest` order sweep→burn→markers | correct and load-bearing for 6b |
| Once-per-process CAS on composition effect | **new HIGH** (Findings 1–2) |
| Cache retry after `bootReconciled` | good (does not delay gate) |

### F. Cumulative unit

| Item | Verdict |
|---|---|
| **F.1** destroy ≡ keys-first unlink | **PASS** — shared `obliterateLocked` DEK→temps→bin→temps; destroy only prefixes confirmed |
| **F.2** marker clear strictly after durable unlinks | **PASS** — verify → dirSync → `clearBothMarkersDurably` |
| **F.3** healers as one system | **PASS** after 6b — disjoint signatures; boot order unblocks reconcile; no owned-by-nobody residue state found |
| **F.4** WRITER/READER + in-flight verdicts | **FAIL** at boot hold publish/consume (A/C/D); burn `obliterated` carry **PASS** |
| **F.5** reachability | **PASS** — slot 0 filler (`createVaultSlots`); wipe only `PassphraseOutcome.Burn` → lock-screen `onBurn` |
| **F.6** concurrency/lifecycle | **FAIL** on boot (Finding 1); burn exclusive gate + `container.scope` **PASS** |
| **F.7** fail-closed burn presentation | **PASS** for image (`burned && obliterationComplete` / `postBurnRoute`); pre-image app-local wipe ordering is disclosed pre-existing tradeoff |

### G. `File.exists()` inside `obliterateLocked` verify  
**Agree out of scope** — inherited from destroy; fail-open only on unstattable survivors, not the residue-sweep consumption bug. Do not block merge on G alone.

### H. Test quality — **PARTIAL**

**Holds:**

- Store gates (incl. ELOOP consequence test, non-durable → `SWEPT_NOT_DURABLE`, row 6b + reconcile unblock)
- Pure `bootRoute` 16-row table + single ONBOARDING combination
- Mutations cited for store + pure function are the right *shape* for those layers

**Still untested (and how round‑1 got in):**

- Hold is **published** after sweep and **survives** composition cancel
- Splash / re-derive **refuse** Onboarding when hold is true in the real wiring (not only in `BootRouteTest`)
- Once-guard + cancelled claimer does not strand `bootReconciled` or drop hold
- Session collector vs hold

**Vacuous risk:** `BootRouteTest` is excellent for the pure function and would stay green while Findings 1–2 ship — same “computed ≠ used” seam, one layer out.

Documented weak unstattable-baseDir test: limitation stated; load-bearing ELOOP test is the real gate — acceptable.

Tests not re-run here (Gradle wrapper lock permission denied in this environment); conclusions are from source.

---

## Round‑1 fix verification (claimed → source)

| Claimed fix | Real? | Complete? | Safe? |
|---|---|---|---|
| Carry durability via `ResidueSweepResult` + hold + `bootRoute` | **Yes** at store + pure route | **No** at publish/Splash | Unsafe under cancel |
| Drop intent gate; row 6b | **Yes** | **Yes** for that stranding | **Yes** |
| Session collector proven absence | **Yes** for presence | Hold still missing | Partial |

I do **not** reverse round 1’s acceptance of those two defects; I reverse any implication that `98c0319` fully closed the durability consumption hole.

---

## READY TO MERGE?

# **NO**

**Blockers:** Findings 1 and 2 (both HIGH) — process-scoped once-guard with composition-scoped execution, Splash not gated on `bootReconciled`, one-way re-derive.

**After fix, re-check:** rotation mid-boot with injected `SWEPT_NOT_DURABLE` still holds Locked; second composition observes hold; Splash cannot open Onboarding until `bootReconciled`; CAS cannot strand forever without a fail-closed publish.
