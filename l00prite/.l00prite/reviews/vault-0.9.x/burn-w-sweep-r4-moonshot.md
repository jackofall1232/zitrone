moon: asking kimi-k3...
# Round 4 Independent Review — delta 00f65b6

Scope note: I only have the inlined delta plus the inlined `bootRoute`/`runBootReconcile`/`BootDecision`/`sweepOrphanedResidue` source. Where a verdict depends on source I was not given (`destroy()`, `obliterateLocked`, `AppContainer.scope`'s dispatcher, slot-0 wiring, the rest of `MainActivity`), I say so explicitly rather than inherit earlier rounds' conclusions.

---

## A. Routing authorities — VERDICT: single authority within visible source; one unverifiable enumeration caveat

Sites assigning `route` / `vaultExists` **visible in this delta**:

1. **The Splash `LaunchedEffect(splashFinished, bootDone)`** (MainActivity.kt:705+) — the sole surviving boot authority. Ordered: requires `splashFinished && bootDone`, pre-checks `route == Route.Splash`, re-checks `route == Route.Splash` after the `withContext`. Uses the **carried** `container.residueSweepHold.value`, never re-derives it. Takes one disk snapshot (`confirmed`, `present`, `legacy`, `provenAbsent`) and applies a single `BootDecision`. Conforms.
2. **The legacy-image effect** — deleted. Verified gone in the diff; the `ResidueSweepResult` import removal confirms no other use of that type in this file.
3. The `route = when (decided.route)` and `vaultExists = decided.present && !decided.legacy` are the *same* site as (1), applied atomically after the re-check.

Caveat I cannot close from the inline: I cannot enumerate all of `MainActivity.kt`. Interactive post-decision navigation (unlock → conversations, onboarding create → main) is not a *boot* authority, and the post-suspend re-check structurally neutralizes any such writer racing the decision. I also cannot verify from the inline that `bootDone` derives from `bootReconciled` — the guard `if (!splashFinished || !bootDone) return` shows the *ordering intent*, and prior rounds established the derivation, but I'm flagging it as an assumption, not a re-verified fact. The pre-fix comment "the routing decision below also routes LegacyImage to onboarding as a backstop" referred to something I can't see; post-fix `bootRoute` handles legacy, so if that "backstop" text described a *second* legacy handler elsewhere in the file, it would be worth one grep — but nothing in the delta suggests it exists.

**No additional routing authority found in the visible source.**

## B. `bootRoute` precedence — VERDICT: correct in every reachable combination

Order: `confirmed → legacy → present → hold → provenAbsent → else LOCKED`.

- **confirmed before legacy**: correct and load-bearing. `confirmed=true` forces `legacy=false` at the call site anyway (`if (present && !confirmed)`), so the arm order is doubly defended. The onboarding `create()` clearing both markers over a durable `vault.delete-confirmed` was the HIGH; it is now structurally unreachable via boot routing.
- **legacy before present**: correct — a legacy image *is* present and would otherwise fall to a dead lock screen.
- **legacy before hold**: the one arm ordering worth interrogating. `{legacy=true, hold=true}` routes ONBOARDING, skipping the hold. Is that state reachable? The sweep mutates only when `vault.bin` is proven absent (GATE 1); a legacy image satisfies `hasVault()==true`, i.e. `vault.bin` present. Within one boot the sweep runs before any authentication and nothing writes `vault.bin` between the sweep and the Splash decision, so `{legacy, hold}` is an **unreachable input pair**, not a masked LOCKED. The test asserting `bootRoute(false, present=true, hold=true, legacy=true) == ONBOARDING` covers an input the system cannot produce; harmless.
- **Failed legacy probe** (`runCatching{}.getOrDefault(false)`) biases to LOCKED, not ONBOARDING — fail-closed direction. Correct.

No combination found where legacy masks a state that should be LOCKED.

## C. `runBootReconcile` — VERDICT: contract holds; one behavior change in `afterPublish` (see Finding 1)

- **Once-only**: `if (!claim()) return` with the CAS in production. ✓
- **Publication in `finally` on every exit**: the `try/finally` wraps the entire `withContext`. Normal exit, non-CE fault from `rest()`, and CE propagating from `sweep()`/`withContext` all reach `publish`. `publish` is non-suspending, so it runs during cancellation. ✓ Rethrowing CE from `sweep()` introduces **no** publish-skipping path — the CE travels *through* the `finally`.
- **Fail-closed initial verdict**: `var result = SWEPT_NOT_DURABLE`; only a returned `SWEPT_DURABLE` lowers it. A cancelled-before-verdict run publishes `hold=true`; the test asserting no invented hold after a durable verdict (`rest` throws CE) closes the over-correction direction. ✓
- **Dispatcher injection**: `sweep`/`rest` ran on hard-coded `Dispatchers.IO` before, run on `ioDispatcher` defaulting to `Dispatchers.IO` now — production unchanged **for the reconcile body**. Not so for `afterPublish` — Finding 1 below.
- Minor, not a defect: `afterPublish()` is correctly *skipped* when the coroutine is cancelled (CE propagates past it). That matches the old code, where the hygiene `withContext` was likewise unreached on cancellation.

## D. New defects in 00f65b6 — VERDICT: none in the decision logic; one behavior change in the extraction

The single-snapshot `BootDecision` is the right fix shape — decide-then-apply with the post-suspend re-check, no re-read of disk after the decision. `vaultExists = decided.present && !decided.legacy`: on `DELETE_INCOMPLETE`, `legacy` is forced false so this reduces to the old `vaultExists = present`; on legacy it yields `false` so onboarding's `create()` retires the image. Consistent.

**Finding 1 — LOW — ZitroneApp.kt (`startBootReconcile`/`runBootReconcile`): `afterPublish` lost its `Dispatchers.IO` confinement.**
Old code: `withContext(Dispatchers.IO) { runCatching { retryPlaintextCacheClearIfNoVault() } }`.
New code: `afterPublish: () -> Unit` invoked bare on `scope.launch`'s dispatcher; production passes `{ runCatching { retryPlaintextCacheClearIfNoVault() } }`. Because the lambda type is `() -> Unit` (not `suspend`), `retryPlaintextCacheClearIfNoVault` is provably a **blocking, non-suspend** function — and its only IO confinement was deleted. If `AppContainer.scope`'s dispatcher is Main (not shown in the inline), a self-described "slow cache clear" now does disk IO on the main thread at every cold start. The commit's own comment says it "must not hold the splash" — it now potentially does worse than hold it.
**Fix**: wrap the invocation — either `afterPublish: suspend () -> Unit` with the production lambda doing `withContext(Dispatchers.IO) { ... }`, or `scope.launch(ioDispatcher) { afterPublish() }` — or confirm in source that `scope` is already off-main and `retryPlaintextCacheClearIfNoVault` self-dispatches. This is a performance/UI defect, not a safety defect: it runs *after* publication and cannot affect routing.

**Finding 2 — LOW — BootReconcileOwnerTest.kt:~140: the ordering test passes vacuously against its named mutation.**
`a consumer released by the done signal never observes a stale hold` claims to uniquely catch "publishing `done` before `hold`". It cannot. `Harness.publish` is **synchronous**, and `StateFlow` conflates: the waiter (`h.done.first { it }; observedAtRelease = h.hold.value`) is only rescheduled by the test scheduler after `publish` returns, and it then reads `hold.value`'s *final* value. Under the mutation (done-then-hold) the waiter still observes `true`. The kdoc's mutation claim is false — the exact failure mode this unit keeps re-learning, now in a test header. Production impact is approximately nil (any consumer doing `done.first{it}; hold.value` is equally conflation-protected, so the textual order inside `publish` genuinely doesn't matter for `.value` readers), which makes this a *test-integrity* finding, not a safety one — but per standing instruction 5, coverage must be judged at the consumption site, and this test covers nothing its header claims.
**Fix**: make `publish` suspend with a `yield()` between the two assignments (then the mutation is observable), or have the waiter collect `hold` as a flow and assert the observed sequence, or delete the false mutation claim from the kdoc.

## E. Sweep gate — VERDICT: safe in every state; table complete within visible source

- GATE 1: `Files.notExists(binFile)` — `notExists` returns `false` when the file exists **or its existence is indeterminate** (IOException → false). So `!notExists` refuses on present-or-unknown. Fail-closed. ✓
- GATE 2: `!Files.notExists(serverDeletedFile)` refuses when the confirmed marker is present **or indeterminate**. Fail-closed. ✓
- No intent gate: the ratified reasoning is internally consistent *given* the premises (destroy writes confirmed durably before unlinks; create clears both markers durably before writing the DEK). Those premises live in `destroy()`/`create()`, not in the inline — I verified the gate logic, not the premises; prior rounds verified the premises and I found nothing contradicting them here.
- State walk: `{bin present}` → refuse (any markers); `{bin absent/indeterminate, confirmed present/indeterminate}` → refuse; `{bin absent, no confirmed, residue}` → sweep → verify-proven-absent → dirSync durable, else `SWEPT_NOT_DURABLE`; `{clean}` → `NO_MUTATION`, claims nothing; `{bin absent, residue, intent}` → sweep, then `reconcileOrphanedBurnMarkers` retires the intent — no orphaned state. Post-mutation exits cannot report `NO_MUTATION`; the catch-all returns `SWEPT_NOT_DURABLE`. Synchronous body, so the no-CE-flows-here comment is accurate. Writer/reader table: residue writers (destroy, create tmp staging, sweep) and readers (both gates, the carried hold) are all accounted for in the visible source. ✓

## F. Cumulative unit

- **F.1** — Conditional pass: the sweep's unlink order (DEK → dek.tmp → bin.tmp) with verify-then-dirSync mirrors the described `obliterateLocked` steps (2)/(3); `destroy()` itself is not in the inline, so equivalence of *destroy* under keys-first is not independently re-verified by me.
- **F.2** — Conditional pass: the marker-ordering premises are asserted in comments I've been warned about; nothing in the delta violates them, but I did not read `destroy()`/`create()`.
- **F.3** — Pass: the three healers have disjoint preconditions (sweep: bin absent; completeInterruptedBurn: bin present + DEK absent; reconcile: all image-bearing proven absent, unblocked by the sweep) and a deliberate execution order. The `{no bin, residue, intent}` hole from round 1 is closed. Indeterminate stats refuse everywhere — degrading to hold/LOCKED, never to onboarding.
- **F.4** — Pass with Finding 2 noted: the carried hold is consumed at exactly one site; publication ordering is structurally sound; the *test* of that ordering is the weak point, not the mechanism.
- **F.5** — Not verifiable from the inline. Commit states slot 0 unarmed, no version bump; I can neither confirm nor refute.
- **F.6** — Pass with Finding 1 noted: CAS once-only, `imageLock` in the sweep, single-snapshot decision, post-suspend re-check. The only lifecycle regression candidate is the `afterPublish` dispatcher.
- **F.7** — Pass: partial burn → sweep refuses or reports `SWEPT_NOT_DURABLE` → hold → LOCKED. No path in the visible source converts a partial burn into a fresh-install presentation, and a failed burn leaves state strictly better-informed than not burning (residue unlinked, hold raised).

## G. `File.exists()` inside `obliterateLocked` — VERDICT: agree, out of scope

Pre-existing, inherited, and this delta doesn't touch it. Not counted. One sentence for the backlog: `File.exists()` is fail-*open* on error (returns false), which for a verify-deleted check biases toward "deleted" — worth migrating to `Files.notExists`-style fail-closed semantics eventually, but it is not a round-4 finding.

## H. Test quality — VERDICT: strong suite, one vacuous-by-conflation test, one named untested shape

`BootReconcileOwnerTest` otherwise asserts on damage (waiter released, sweep run counts, observed hold) rather than on mechanism — the once-only, fail-closed, cancelled-claimant, no-invented-hold, and no-re-sweep tests are all real. `BootRouteTest`'s three new tests pin the legacy precedence from both directions, and the 16-row truth table with `legacy=false` still holds against the defaulted parameter. The commit's four new mutation claims check out against the tests as written **except** Finding 2 (the done/hold ordering test cannot observe its named mutation — and note the commit message's mutation list does *not* include that reorder, so the suite's actual mutation coverage is consistent; it's the test kdoc that overclaims).

**Failure shape still untested**: the Compose-side integration of the decision — that the Splash effect applies `BootDecision` only when `route` is still Splash after the `withContext`, and that `vaultExists = present && !legacy` is what onboarding/lock actually consume. The pure function and the owner contract are tested; the wiring between them (the exact place the round-3 HIGH lived) is not, and the suite's own header concedes rotation-through-recomposition needs Compose UI testing the project lacks.

---

## Merge verdict

**READY TO MERGE, conditional on a one-line verification**: confirm `retryPlaintextCacheClearIfNoVault` runs off-main under the new `afterPublish` call path (or restore the `Dispatchers.IO` wrapper — Finding 1). The round-3 HIGH fix is real, structural, and correctly ordered; `runBootReconcile`'s four-property contract holds on every exit path; the sweep gate is fail-closed in every state I can construct. Findings are two LOWs (one behavior-change question, one vacuous test header) — neither blocks the safety properties this unit exists to protect. I found no instance of the named weaker-signal pattern surviving in the visible source, and no reason to overturn any prior round's accepted conclusion.
