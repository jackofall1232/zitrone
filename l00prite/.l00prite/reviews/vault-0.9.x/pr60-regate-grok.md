I'll re-gate PR #60 from the current head by reading the full `main...1b5f5e0` scope, verifying each binding claim against source, and running the unit tests.Reading production sources and the full diff for the critical routing paths.Running the mandatory unit-test suite and checking a few remaining edge cases.Environment contention caused native/Kotlin-daemon failures. Fixing writable paths and retrying once; checking whether W-A tests passed.Confirming suite numbers and one last production-path detail, then writing the gate verdict.**MERGE**

Re-derived from `main...1b5f5e0` (head `1b5f5e0`). The prior HIGH is closed at source; the `legacyImage && vaultImagePresent` claim holds for production. Suite observed green after environment recovery.

---

### Findings

No merge-blocking findings. Non-blocking notes only:

**INFO** — `VaultImageStore.kt:1159` (`serverDeleteConfirmed`)  
Still uses `File.exists()`. Under an indeterminate marker stat this is **fail-open for delete ownership** (`false` → not confirmed), while image-bearing absence still uses `Files.notExists`. Not introduced by W-A; tracked in `Residence.kt` kdoc / `todos.md` as next of class. Does not present first-run onboarding over unproven vault material on the ordinary path (present image → `LOCKED`; residue → not proven absent → `LOCKED`).

**INFO** — Compose delivery of `bootRoute`  
`SECURITY_MODEL.md` and inspection: splash waits for `{splashFinished, bootDone}` and applies `deriveBootDecisionFromDisk()`, but there is no Compose UI test that a rotation mid-reconcile cannot strand the screen. Pure owner tests cover the extracted lifecycle; the last mile is inspection-only. Not a product-logic defect in the reviewed wiring.

**INFO** — `MainActivity.kt:746–760` / delete-retry hold supersede omission  
A stale process-scoped hold can make a successful post-destroy retry report failure and stay on `DeleteIncomplete` (route stays DeleteIncomplete; not rewritten to Locked). Reachability is remote (fail-closed boot default over a present image). Restart-recoverable; tracked for 0.9.3. Safer than main’s onboarding-over-residue.

---

### Binding gate items

#### A. Sweep as destructive pre-auth boot op — **PASS**

**Wrongly deletes?** Gate 1 requires `Files.notExists(vault.bin)` (present *or* indeterminate refuse). Gate 2 refuses when confirmed marker is present *or* indeterminate (`!Files.notExists`). Live vault DEK is protected (row 5 ELOOP test). No `delete-intent` gate: `destroy()` writes `vault.delete-confirmed` **before** unlinks (`VaultImageStore.kt:1101–1108`), so in-flight D2c unlink is always gate-2 owned.

**Wrongly strands?** Intent-only + residue (row 6c: crash between `retireLegacyImage` and `create`) is swept — correct, image already gone. Confirmed-incomplete destroy is refused and owned by `DeleteIncomplete`. Non-durable unlink → `SWEPT_NOT_DURABLE` → hold, not onboarding.

Writer/reader table (rows 1–9 + 6c) matches source gates and tests in `SweepOrphanedResidueTest`.

#### B. Verdict carried, not re-derived — **PASS**

`runBootReconcile` publishes `hold = (result == SWEPT_NOT_DURABLE)` then `bootReconciled=true`. Durability is **not** re-stated from disk.

| Consumer | Path | Full `bootRoute` inputs via `deriveBootDecision` |
|---|---|---|
| Splash (`MainActivity.kt:643–655`) | after `bootDone` | yes |
| Post-boot re-derive (`658–681`) | after `bootReconciled.first` | yes |
| Session collector (`815–845`) | `deriveBootDecisionFromDisk` | yes |
| `onRetryDestroy` (`746–752`) | `runDeleteRetry` → derive | yes (prior HIGH closed) |
| Delete `onConfirmed` finally (`1161–1202`) | supersede hold then derive | yes |

Sole production `bootRoute` call site: `deriveBootDecision` (`ZitroneApp.kt:1233`). No second authority, no fewer-arg call.

#### C. `runBootReconcile` contract — **PASS**

Source (`ZitroneApp.kt:1159–1200`) + `BootReconcileOwnerTest` (11/11):

1. Once-only CAS  
2. `publish` in `finally` (cancel still releases)  
3. Default `SWEPT_NOT_DURABLE` (fail-closed)  
4. Claim cannot strand waiters  
5. `afterPublish` contained post-publish  

#### D. Fail-closed precedence in `bootRoute` — **PASS**

Order (`ZitroneApp.kt:1331–1345`): confirmed → legacy∧present → present → hold → proven-absent → else LOCKED.

- Confirmed never yields ONBOARDING (including over legacy).  
- ONBOARDING only for present legacy (create retires it) or proven absence with no hold.  
- Hold outranks a clean-looking stat after non-durable sweep.  
- Indeterminate / residue → LOCKED, never first-run over unproven material.

**`legacyImage && vaultImagePresent` claim:** **Confirmed.**  
`deriveBootDecision` sets `legacy` only when `imagePresent && !serverDeleteConfirmed` (`1225–1228`). `isLegacyImage` itself requires `binFile.exists()`. Production path is unchanged; three pure-function rows that onboarded with `legacy=true` over absent image are now LOCKED (defence). Only `deriveBootDecision` calls `bootRoute` in main.

#### E. Tristate discipline — **PASS (with tracked remainder)**

| Input | Probe | Indeterminate behaviour |
|---|---|---|
| Sweep gate 1 (bin) | `Files.notExists` | refuse (fail-closed destructive) |
| Sweep gate 2 (confirmed) | `!Files.notExists` | refuse |
| `vaultProvenAbsent` / image-bearing | `Files.notExists` ×4 | false → no ONBOARDING |
| `imagePresent` / `hasVault` | `File.exists` | false → not treated present; pairs with proven-absent |
| Cache clear gate | `primaryImageProvenAbsent` | no delete on indeterminate |
| **`serverDeleteConfirmed`** | **`File.exists`** | **fail-open for delete ownership** (tracked) |
| Destroy verify | `File.exists` | pre-existing; routing still withholds ONBOARDING without proven absence |

`Residence` + identity mapping in `deriveBootDecisionFromDisk` (Present ⇔ hasVault, ProvenAbsent ⇔ mayRoute; **not** `treatAsPresent` for imagePresent) is correct and tested.

#### F. Nothing burn-dependent survived — **PASS**

- `onBurn` identical to main (uniform failure stub only).  
- No `signalBurn` / duress-wipe / presentation symbols in the product diff.  
- Sweep kdoc/tests frame interrupted create / `retireLegacyImage` only.

#### G. Strictly better than main — **PASS**

Main Splash: `vaultExists = hasVault()` → absent bin → ONBOARDING over DEK/tmp residue; create overwrites.  
W-A: sweep first; ONBOARDING only after proven durable clean (or present legacy). Hold withholds non-durable cleans. Live vault never swept. No state class is made less safe; worst new UX is fail-closed lock / stuck DeleteIncomplete on pathological FS — restart-recoverable, safer than residue onboarding.

#### H. Test quality + suite run — **PASS**

- No vacuous “compute but don’t use” gap for the hold (BootRoute + Derive + DeleteRetry diverging row).  
- Mutation headers that were wrong were corrected in-tree (`BootReconcileOwnerTest` publish-order / afterPublish CE characterisation).  
- Logic tested directly (`bootRoute`, `runBootReconcile`, `runDeleteRetry`, `sweepOrphanedResidue`), not a parallel copy.  
- Untested shapes (honest): Compose last-mile delivery; `serverDeleteConfirmed` indeterminate marker.

**Suite (this box, observed):**

```
505 total / 502 passed / 0 failures / 3 skipped
```

W-A suites: BootRoute 10, BootReconcile 11, DeleteRetry 5, DeriveBoot 6, Residence 9, DestroySupersedes 3, Sweep 16 — all pass.  
Matches claim. First attempts hit shared-cache / JNA native extract contention (`UnsatisfiedLinkError` / `NoClassDefFoundError: Native`); retry with private `GRADLE_USER_HOME` + `JAVA_TOOL_OPTIONS` jna boot path recovered — environment, not product.

#### I. Anything else — **PASS**

Diff is Android vault/boot + `docs/SECURITY_MODEL.md` + l00prite process memory. No non-Android product surface. Docs match source on sweep gates and hold semantics.

---

### Prior HIGH re-check

`onRetryDestroy` no longer uses `!hasVault() && !serverDeleteConfirmed()`. It goes through `runDeleteRetry` → `deriveBootDecisionFromDisk()` → full `bootRoute` (proven absence + hold). Residue after destroy is failure, not onboarding. **Closed.**

---

**Verdict: MERGE.** Authorises merge of PR #60 at `1b5f5e0`.
