I'll conduct an independent security review of the round-4 fix delta and the cumulative unit, verifying every claim against source. Starting with protocol context and the actual commits.Reading the burn dispatcher, obliteration path, and all `vaultExists` consumers next.Investigating cold-start routing for partial-burn disk states and every `vaultExists` consumer.Checking a few more edge cases: Splash race after recreation, and whether `open()` clears temps before missing-image.# Independent Security Review — Unit W Round 5 (blind)

**Scope reviewed:** `40231c4` + cumulative `main...HEAD` against source only.  
**Tests run:** `PostBurnRouteTest` — 8/8 passed (`BUILD SUCCESSFUL`). Full suite not re-run (Gradle environment constraint); store-level burn tests were read, not re-executed.

---

## Findings

### 1. HIGH residual (pre-existing; disclosure incomplete) — cold-start still fail-opens partial burns

**Where:** `MainActivity.kt:630`, `1367–1380`; `VaultImageStore.kt:265`, `1217–1287`; `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers` gates.

**Defect:** After process death, routing seeds from `hasVault()` (`vault.bin` only). Neither boot healer covers:

| Disk state | Boot path |
|---|---|
| `{bin absent, dek present}` | `hasVault()=false` → Splash → **Onboarding** |
| `{bin absent, vault.bin.tmp present}` (complete outer image) | same → **Onboarding** |
| `{bin present, dek absent}` | `completeInterruptedBurn` heals ✓ |

`completeInterruptedBurn` requires bin **present** + dek **absent**.  
`reconcileOrphanedBurnMarkers` requires **all** image-bearing files proven absent.

**Why it matters:** Same class as the round-4 HIGH, after process death. Surviving `vault.bin.tmp` under a false fresh-install is a recoverable vault. The commit’s residual text claims this “presents honestly as a lock screen” — that is **only true while the process lives and `burnCompletion` is still in RAM**.

**Concrete fix (before Unit S arming; preferred before merge of a “fail-closed complete” claim):**

1. Boot routing for vault-less presentation must key on `obliterationComplete()` / `imageBearingFilesProvenAbsent()`, not `hasVault()`.
2. Widen self-heal: if any image-bearing residue exists without a full live pair, finish obliteration (or a dedicated residue sweep) without treating it as onboarding.
3. Update residual disclosure to state cold-start explicitly.

**Worse than pre-40231c4?** No for cold start (same). Better in-process. Not introduced by `40231c4`.

---

### 2. MEDIUM — “honest but stuck” unlock path is a duress tell (and can delete residue)

**Where:** `MainActivity.kt:776–781` (LOCKED arm); `ZitroneApp.kt:536–541`; `MainActivity.kt:1003–1007`; `VaultImageStore.kt:323–329`.

**Defect:** Failed burn → `PostBurnRoute.LOCKED` with uniform failure once. Any later passphrase hits `open()` → `MissingImage` → `PassphraseOutcome.ImageUnreadable` → distinct copy: *“This vault couldn't be opened — the stored image may be damaged.”* That is not uniform with a wrong passphrase.

Additionally, `open()` **deletes leftover temps first**, then throws MissingImage. A burn that “failed” because `vault.bin.tmp` survived can lose that residual on the next unlock attempt, then brick with a tell — never presenting fresh install.

**Why it matters:** Under duress, distinct error + permanent brick is worse than “wrong password, try again.” Honest failure is right for *not* claiming wipe success; the *shape* of honesty still leaks.

**Concrete fix:** On lock-screen unlock when image-bearing proof is incomplete/partial, either (a) map MissingImage after a burn-shaped residual to uniform failure and offer no create path, or (b) complete obliteration in the boot/unlock path then route to real onboarding only when proof is complete. Do not leave ImageUnreadable as the steady state for known partial-burn shapes.

**Assess residual C:** “Honest but stuck” is acceptable **only** as a temporary unarmed-mechanism residual if documented fully (including cold start + ImageUnreadable tell). It is a **brick that must be fixed before arming**. It is not worse than pre-fix *false success*, but it is worse than “mistyped passphrase” deniability.

---

### 3. LOW — dual success writers; local Main path bypasses `postBurnRoute`

**Where:** `MainActivity.kt:952–959` vs `748–783`.

**Defect:** Surviving composition on success sets Onboarding from `burned` alone (no `serverDeleteConfirmed` / re-proof). Observer uses full `postBurnRoute`. Today they agree when `burned==true` (markers were cleared inside `obliterateLocked` or burn threw). Drift risk if one path is edited later.

**Fix:** Route **only** through the observer (or call `postBurnRoute` in both places with the same three inputs). Main path can set spinner/error only.

---

### 4. INFO — stale comment still names removed API

**Where:** `MainActivity.kt:914` — still says `[AppContainer.burnsCompleted]`.

**Fix:** Rename to `burnCompletion` / `signalBurnCompleted`.

---

### 5. INFO — `File.exists()` verify inside `obliterateLocked`

**Agree out of scope.** Inherited from `destroy()`, same verify block. Not a `40231c4` defect. Tristate discipline elsewhere is stronger; leave for a dedicated durability pass if desired.

---

## Claim verification (round-4 fix — derived from code, not comments)

| Claim | Verdict |
|---|---|
| `BurnCompletion(generation, obliterated)` published | **True** — `ZitroneApp.kt:258–269`, `1236` |
| `burned` outside try; finally publishes outcome | **True** — `MainActivity.kt:919–950` |
| Success requires no-throw ∧ `burnObliterationComplete()` | **True** — `931–932` |
| Observer no longer uses `hasVault()` for success | **True** — uses `completion.obliterated` + `burnObliterationComplete()` |
| `postBurnRoute` precedence | **True** — `1264–1272`; tests match |
| LOCKED sets `vaultExists=true` not from `hasVault()` | **True** — `776–777` |
| Residual cold-start “honest lock screen” | **False / incomplete** — see Finding 1 |

---

## Explicit verdicts

### A — Fail-closed proof end-to-end (in-process)

**PASS for the process-scoped path.**

Trace:

1. `burned = false` outside `try`
2. `try { burned = withContext { runCatching { burnVault() }.isSuccess && burnObliterationComplete() } }`
3. `finally { endTerminalWipe(); signalBurnCompleted(obliterated = burned) }`
4. Observer: `postBurnRoute(serverDeleteConfirmed, completion.obliterated, burnObliterationComplete())`
5. Onboarding only if `!confirmed && obliterated && provenAbsent`

**Can `obliterated` publish `true` when burn did not fully take?**  
Only if both `burnVault()` returned and `burnObliterationComplete()` was true at that instant. `burnVault()` only returns after `obliterateForBurn()` (throws on any of the four post-unlink failures). Not via `hasVault()`.

**Spurious failure?**  
Yes possible if proof fails after a successful obliterate (indeterminate stat) or if the job is cancelled in awkward windows — presents LOCKED, not Onboarding. Fail-closed, not fail-open.

**Kotlin exits:**  
- Normal: assigns `burned`, `finally` publishes.  
- Throw from `withContext`: assignment skipped, `burned` stays `false`, `finally` publishes failure.  
- Cancellation: `finally` still runs; default remains fail-closed. (`runCatching` swallows `CancellationException` inside the block — pre-existing smell, not a new open.)

**`postBurnRoute` precedence:** Correct. No fourth arm required: confirmed delete owns the state; proven success needs both flags; else lock.

### B — `vaultExists = true` over absent `vault.bin`

**Defensible for routing.** Consumers checked:

| Consumer | Behavior with forced `true` / missing bin |
|---|---|
| Splash | Prefers Locked if `vaultExists` (good vs Onboarding) |
| Biometric affordance | May show if `biometricEnabled` still true; wipe already ran best-effort; unlock fails closed |
| Lemon-drop veil | Composes lock veil (not pre-onboarding skip) |
| LegacyImage effect | `isLegacyImage` false if unreadable/missing |
| Session collector | Only rewrites `vaultExists` when `unlocked` goes true→false (burn starts locked; no session) |

Does not reintroduce onboarding over residue **while this composition and `burnCompletion` live**. Cold start still re-seeds from `hasVault()` (Finding 1).

### C — Known residual

See Findings 1–2. **Not hidden, but under-specified.** In-process: honest lock, stuck, ImageUnreadable tell. Cold start: **still false onboarding**. Fix before arming; do not treat “honest lock screen” as process-lifetime-complete.

### D — New defects from `40231c4`?

**No HIGH/CRITICAL introduced.** Dual disk reads in one `withContext` are fine. `LaunchedEffect(burnCompletion)` on a data class with bumping `generation` re-runs correctly; recreation re-applies last completion. Session collector does not fight burn (no session). Boot reconciler is `LaunchedEffect(Unit)` once — orthogonal. Dual Main/observer writers = LOW drift risk only.

### E — Cumulative unit

| # | Topic | Verdict |
|---|---|---|
| **E.1** | `destroy()` ≡ keys-first via `obliterateLocked` | **PASS** — confirmed marker first, then shared primitive; crash restarts DeleteIncomplete |
| **E.2** | Marker clear strictly after durable unlinks | **PASS** — verify → `dirSync` DURABLE → `clearBothMarkersDurably` last |
| **E.3** | Boot recon + `completeInterruptedBurn` | **PASS** for `{bin, !dek}`; **GAP** for inverse / temps (Finding 1) |
| **E.4** | WRITER/READER for burn signals | **PASS** in-process (`burnCompletion` carries proof); cold start readers still use weaker `hasVault` |
| **E.5** | Reachability | **PASS** — slot 0 unarmed after create; wipe only `PassphraseOutcome.Burn` → `onBurn` |
| **E.6** | Concurrency / lifecycle | **PASS** — `tryBeginTerminalWipe` exclusive; process-scoped completion; `container.scope` |
| **E.7** | Fail-closed partial burn | **PASS in-process after 40231c4**; **FAIL cold-start** for bin-gone residue |

### F — `File.exists()` in `obliterateLocked`

**Agree out of scope.**

### G — Tests

`PostBurnRouteTest` is **meaningful, non-vacuous**: explicit truth table (not re-implemented rule), dedicated round-4 case, single onboarding combination assertion. Mutation claim is credible.

**Still untested (disclosed honestly if stated this way):**

- Plumb of `burned` → `signalBurnCompleted` → Compose observer  
- Main vs observer dual path  
- `vaultExists=true` side effects  
- Cold-start residual  
- Cancellation around `finally`

`SECURITY_MODEL.md` post-burn paragraph is **accurate for the pure decision + Compose-wiring gap**; it does **not** adequately document the cold-start residual (Finding 1).

---

## READY TO MERGE?

### Round-4 delta (`40231c4`): **YES — fixes are real, complete, and safe for their stated bugs.**

The HIGH (success re-derived from `hasVault`) and the delete-confirmed precedence hole are closed in source, not only in comments. I derived that from the dispatcher/`finally`/observer/`postBurnRoute` code, not from kdoc.

### Cumulative Unit W as production-armed wipe: **NO.**

Cold-start partial-burn still presents as success; residual disclosure overclaims “honest lock screen.”

### Cumulative Unit W as **unarmed mechanism only** (slot 0 filler, wipe unreachable in prod): **YES, with residuals tracked as must-fix-before-Unit-S.**

That matches the unit’s own reachability claim. Do not arm slot 0 until Findings 1–2 are closed.

---

**Bottom line:** An honest pass on `40231c4` itself. No invented defects in the fix plumbing. Remaining risk is the **known residual class**, which is **more severe on cold start than the commit text admits**, and the stuck-lock **ImageUnreadable** tell. Fix those before the wipe is reachable; merge of the unarmed mechanism is acceptable if that bar is explicit.
