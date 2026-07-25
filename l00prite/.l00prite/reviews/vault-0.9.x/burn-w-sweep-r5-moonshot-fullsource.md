moon: asking kimi-k3...
# Independent Security Review — Zitrone Pucker Burn / Boot Reconcile (Full Repass)

## Preliminary: the bundle does not contain what the brief claims

The brief states: *"You can now read destroy(), create() and obliterateLocked in full"* and asks me to judge test quality. **Neither `VaultImageStore` (destroy/create/obliterateLocked/sweepOrphanedResidue), nor `AppContainer`, nor any test file is present in what I received.** I have: `runBootReconcile`, `BootDecision`, `bootRoute` (pasted twice verbatim — see F8), `postBurnRoute`, and `MainActivity.kt` complete.

I will not fabricate verification of files I cannot see. Focus C and E are therefore answered honestly as *still unverifiable*, and the merge verdict is conditioned on that. Where a MainActivity consumer's safety depends on a `destroy()`/`obliterateLocked` premise, I say so explicitly rather than taking it on trust.

---

## A. Every `route` / `vaultExists` assignment site (complete enumeration — decidable, and I state positively this is the full set)

I searched every `route =` and `vaultExists =` occurrence in the file. **Boot-sensitive sites** (must await `bootReconciled`, use the carried hold, pass full inputs):

| # | Site | After `bootReconciled`? | Carried `residueSweepHold`? | Full input set incl. `legacyImage`? |
|---|------|--------------------------|------------------------------|--------------------------------------|
| 1 | Splash decision effect `LaunchedEffect(splashFinished, bootDone)` | ✅ gated on `bootDone`, re-checked after suspend | ✅ `container.residueSweepHold.value` | ✅ all five args |
| 2 | Boot re-derive `LaunchedEffect(Unit)` (after `startBootReconcile`) | ✅ `bootReconciled.first { it }` | ✅ | ✅ all five args |
| 3 | Session collector, null-session arm | ✅ (only fires on session→null, which post-dates boot) | ✅ | ✅ `legacyNow` included |
| 4 | Burn observer `LaunchedEffect(burnCompletion)` | N/A (burn is post-boot) | N/A — uses `postBurnRoute` with carried `completion.obliterated` | ✅ (but see **F3** — different 3rd input than the other caller) |
| 5 | `onBurn` direct routing arm | N/A | carried `burned` | ✅ (but see **F3**) |

**Runtime sites** (post-auth / user-action; boot reconciliation irrelevant — verified each is genuinely post-boot):

- initial `remember` seeds (`route`, `vaultExists`) — Splash holds routing until bootDone, so the seed cannot leak a decision;
- `onRetryDestroy` (`vaultExists = false`, `route = Onboarding`) — see **F2**;
- `onUnlockSuccess` → ChatList; `onCreateVault` success/failure arms; `onUnlockPassphrase` LegacyImage arm (see **F6**);
- `onForcedLogout` → Locked (see **F5**);
- `onDeleteAccount` finally (`vaultExists = container.hasVault()`, route from disk truth) — see **F2**;
- `BackHandler`, `unlockFromVeil`, `SessionUi.onNavigate` — pure runtime navigation.

**Answer to "find the next one":** the site previous rounds' model most plausibly lacked is **`onForcedLogout`** — it assigns `route` *and* disarms the only re-derivation that could correct it (**F5**). After it, I state positively: no further unaccounted assignment sites remain in this file.

---

## Findings

### F1 — MEDIUM — `onDeleteAccount` uses non-exclusive `beginTerminalWipe()` while `onBurn` documents exactly why that's unsafe
**Site:** `onDeleteAccount` first line: `container.unlockController.beginTerminalWipe()`.
`onBurn` uses `tryBeginTerminalWipe()` with this rationale, verified in source: *"plain beginTerminalWipe() lets a second caller become a silent co-owner, and the first to finish reopens session creation while the other is still destroying — so a successor vault created in that window would be obliterated by the straggler."* That reasoning is not burn-specific. `onDeleteAccount` has **two entry points**: the Settings button and `LaunchedEffect(session) { if (session != null && container.vaultDeleteIntentPending()) onDeleteAccount() }`. The round-14 reconcile firing while the user taps Delete in Settings produces two co-owned `deleteAccountAndWipe` invocations under one non-exclusive gate; the first finisher's `endTerminalWipe()` (in each callback) reopens the gate while the other is mid-`destroyVault`.
**Fix:** use `tryBeginTerminalWipe()` and return early (silently — a delete is already in flight) on refusal, mirroring `onBurn`.

### F2 — MEDIUM — Delete-path success is derived from `hasVault()` — the vault.bin-only signal round 4 proved insufficient for burn
**Sites:** `onRetryDestroy`: `!container.hasVault() && !container.serverDeleteConfirmed()`; `onDeleteAccount` finally: `vaultExists = container.hasVault()` then `if (!vaultExists && !container.serverDeleteConfirmed()) Route.Onboarding`.
Round 4's burn fix (verified in the `onBurn` comment) established that `hasVault()` keys on `vault.bin` alone and that `vault.bin.tmp` *"stages a COMPLETE outer image"*; the burn path was moved to tristate proven-absence (`burnObliterationComplete`) for exactly this reason. The delete path still exits `DeleteIncomplete → Onboarding` on the weak signal. This is instruction 3(a) in its pure form: an authoritative result (tristate proven absence) exists in the same unit, and this consumer uses the cheaper one. Whether it is a *live* defect hinges on a premise I cannot check: does `destroy()` unlink/verify `vault.bin.tmp` before retiring the confirmed marker (`destroy()` not provided — see preamble)? If the marker is retired only after all image-bearing files are confirmed gone, the `hasVault()` check is redundant-but-safe. If `destroy()` verifies only `vault.bin`+`vault.dek`, a surviving `.tmp` exits to Onboarding over a recoverable image — the round-4 burn shape in the delete path.
**Fix:** confirm `destroy()`'s verify set covers `vault.bin.tmp`; until then, derive the exit from the same tristate check the burn path uses.

### F3 — LOW/MEDIUM — The two `postBurnRoute` callers pass different proven-absence predicates
**Sites:** `onBurn` direct arm: `imageBearingProvenAbsent = container.vaultProvenAbsent()`; burn observer: `container.burnObliterationComplete()`.
Round 5 (comment verified) made both arms go through `postBurnRoute` specifically to eliminate two writers deciding by different rules — but they still supply differently-*named* third inputs. If these are two functions rather than aliases, a composition that survives the burn (direct arm, later write wins) and a recreated composition (observer only) can land on different routes. `AppContainer` is not in the bundle, so I cannot prove them equal.
**Fix:** one predicate, one name, both callers. Also note the observer runs first (flow emission in `finally` precedes the direct routing block), so on disagreement the direct arm wins on a surviving composition — currently benign only because both are believed equal.

### F4 — LOW — Main-thread disk IO in the session collector, and another false comment
**Sites:** session collector null arm calls `runCatching { container.isLegacyImage() }` and `container.vaultProvenAbsent()` directly in the collector (Main); Splash and the boot re-derive wrap the identical calls in `withContext(Dispatchers.IO)`. The file's own comment describes `isLegacyImage()` as *"a ~1 MiB outer decrypt."* Separately, the `onDeleteAccount` finally comment claims its Main-thread disk reads run *"as they already do from Splash routing"* — **false**; Splash routing does them inside `withContext(Dispatchers.IO)`. This is the wrong-comment class again, asserting parity that doesn't exist.
**Fix:** wrap the collector arm's reads in `withContext(Dispatchers.IO)`; correct the comment.

### F5 — LOW — `onForcedLogout` clears `unlocked` before `lockIf`, silently disarming the session collector's re-derivation
**Site:** `onForcedLogout = { unlocked = false; route = Route.Locked; container.unlockController.lockIf(live) }`.
When `lockIf` publishes `session = null`, the collector's else-branch is gated `else if (unlocked)` — already false — so **no re-derivation ever runs for a forced logout**; `route = Locked` is the only and final write. Today this appears unreachable-as-harmful (a confirmed-delete marker implies teardown already happened, so no live session remains to force-logout), but the structural backstop the rest of the file relies on ("the collector reconciles both directions") is quietly off for exactly one path, and nothing documents that.
**Fix:** either let the collector own null-session routing (call `lockIf` first, leave `unlocked` for the collector), or derive the route in `onForcedLogout` via `bootRoute` with the full input set like every other null-session consumer.

### F6 — LOW/INFO — `onUnlockPassphrase`'s LegacyImage arm is a residual second routing authority
**Site:** `PassphraseOutcome.LegacyImage -> { vaultExists = false; route = Route.Onboarding }` — no `serverDeleteConfirmed` consult. This is the same precedence class removed in round 3 (legacy preempting DeleteIncomplete, whose `create()` clears both markers). I attempted to construct reachability and could not: reaching the lock screen with a legacy image *and* a durable confirmed marker requires the marker to land while the user sits on Locked, but the confirmed flow tears the session down and routes to DeleteIncomplete immediately. Believed unreachable; not structurally enforced.
**Fix (defense-in-depth):** route this arm through `bootRoute(...)` with the full inputs, or check `serverDeleteConfirmed()` first.

### F7 — INFO — `runBootReconcile`: `rest()` faults are not contained
A throw from `rest()` (after a durable sweep) propagates: `finally` publishes `hold=false` (correct for the *sweep* verdict), `afterPublish` is skipped, and the exception escapes `launch` on the process scope to the default handler. The hold semantics only cover sweep durability, so this is internally consistent — but the kdoc's "on EVERY exit" framing understates that a `rest()` fault publishes a clean verdict and then possibly crashes. Also note `onBurn`'s `runCatching { container.burnVault() }` swallows `CancellationException` (fail-closed here, so benign on a dying process scope, but it contradicts the unit's own documented rationale for avoiding `runCatching`).
**Fix:** document `rest()`'s contract, or wrap it like `sweep()`; use the explicit try/catch-rethrow-CE pattern in `onBurn` for consistency.

### F8 — INFO — `bootRoute` appears twice, verbatim, in the ZitroneApp.kt excerpt
A duplicate top-level definition would not compile; near-certainly a bundle paste artifact. Flagging only because this unit's history is replete with "the transcript lied."

---

## Verdicts

**A — Decidable, answered.** Complete table above; all three `bootRoute` consumers are ordered after publication, use the carried hold, and pass the full five-argument input set. The newly-surfaced site is `onForcedLogout` (F5). No sites remain unaccounted for.

**B — PASS.** Precedence (confirmed → legacy → present → hold → proven-absent → Locked) is correct in both directions: legacy cannot preempt a confirmed delete (nothing wrongly admitted to Onboarding), and a legacy image cannot fall through to a dead lock screen (nothing wrongly stranded). `runBootReconcile`: once-only claim ✅, `finally` publication on every exit ✅, fail-closed default ✅, CE re-thrown not folded ✅, dispatcher injected ✅. Residual: F7.

**C — CANNOT CLOSE.** `sweepOrphanedResidue`, `destroy()`, `create()`, `obliterateLocked` are not in the bundle despite the brief's assertion. What I *can* verify from call sites is consistent with the ratified design (burn path is fail-closed on tristate absence; markers cleared only inside the wipe). The WRITER/READER table for the markers cannot be proven complete from one side of the interface. The "premises taken on trust" caveat from the trimmed round is **not** closed — the files needed to close it were not delivered.

**D — PASS with conditions, for the code visible.** Wiring invariant holds: `onBurn` is invoked only from `PassphraseOutcome.Burn`, and `burnVault()` appears only inside `onBurn` — verified by reading every call site in the file. Exclusive gate ✅, process-scope survival + `burnCompletion` observer ✅, outcome-carrying publication ✅, fail-closed uniform-failure presentation ✅, both routing arms through `postBurnRoute` ✅. Conditions: F1, F2, F3.

**E — CANNOT JUDGE.** No tests were provided; any claim about vacuous tests or header accuracy would be invented. Based on consumption sites, the shapes I would *demand* tests for (and suspect are the still-untested ones): **(1)** F2 — delete-path Onboarding exit over a surviving `vault.bin.tmp` with retired marker; **(2)** F3 — divergent `postBurnRoute` inputs across surviving vs recreated compositions; **(3)** F5 — forced logout stranding `route` without re-derivation. Each is a test at the *consumption* site, not the computation site — per standing instruction 5.

**On the out-of-scope note:** agreed — the `File.exists()` verify inside `obliterateLocked` is pre-existing and inherited; not counted.

## READY TO MERGE? **No — conditionally.**

On the code actually visible there are **no CRITICAL or HIGH defects**, and the boot/burn architecture has converged: the recurring "authoritative result vs. weaker consumer" pattern is genuinely closed in the three boot consumers. But (a) the repass bundle omits the very files (`destroy`/`create`/`obliterateLocked`/`sweepOrphanedResidue`, tests) it claims to include, so C and E remain unverified by any honest accounting; (b) F1 and F2 should be fixed or affirmatively disproven — F2 requires exactly the `destroy()` source that wasn't delivered. Supply the missing sources, resolve F1–F3, and this merges.
