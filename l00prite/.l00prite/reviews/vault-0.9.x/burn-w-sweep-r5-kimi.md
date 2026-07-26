
  Let me quickly confirm SECURITY_MODEL.md disclosure and publishSession's Unlocked→Rejected mapping. Then write the report.

• Two last checks: the SECURITY_MODEL disclosure and the `publishSession` refuse mapping.

• All verified. publishSession-refused → Rejected ✓. SECURITY_MODEL.md has the disclosures ✓.

  One more thing to close focus A "decidable": I should double-check there are no `route`/`vaultExists` writes I missed due to multiline formatting, e.g. `route =\n Route.X`. grep pattern `route\s*=` — `\s*` in ripgrep without multiline matches only same-line whitespace. A line break between `route` and `=` would be missed. Unusual in Kotlin style here. Quick check: grep for `route$` lines ending with "route"... Actually simpler: grep for `vaultExists$|route$` with -n. Let me do one quick grep to close it. Also `route by remember` delegate allows `route = ...` only. Let me run the check.No hidden split assignments. Focus A is decidable and complete.

  Now write the report. Verdicts:

  **A. Route/vaultExists site enumeration** — complete list (from grep + context reads):

  | Site | Line | Ordered after bootReconciled? | Uses carried hold? | Full input set incl legacy? |
  1. Initial seed `route` (623-624) — from session presence; not a disk decision. OK.
  2. Initial `vaultExists = hasVault()` (631) — seeded pre-reconcile; consumed only by post-publication decisions + veil cosmetics. Note: not gated, but nothing safety-bearing consumes it pre-publication.
  3. onRetryDestroy (646-656): D2c retry, disk-truth (`!hasVault() && !serverDeleteConfirmed()`), safe because marker retire is strictly post-verify inside obliterateLocked.
  4. Splash decision effect (704-753): gated on splashFinished && bootDone; full inputs incl legacyImage; carried hold. ✓
  5. Boot re-derive effect (755-812): awaits bootReconciled.first{it}; full inputs. ✓ (minor: DELETE_INCOMPLETE applied unconditionally after a session-null check taken before the suspend — unreachably small race, INFO)
  6. Burn observer (816-865): postBurnRoute with carried `obliterated` + live disk reads; not boot-gated but doesn't need to be (published only after a burn; reads are live disk truth).
  7. Session collector unlocked arm (901): ChatList. ✓
  8. Session collector locked arm (906-938): full bootRoute inputs incl legacyNow. ✓
  9. onUnlockSuccess (969): ChatList. ✓
  10. onBurn Main.immediate (1075-1107): postBurnRoute, carried `burned`. ✓
  11. LegacyImage arm (1131-1133): pre-existing; sets Onboarding without consulting confirmed marker — unreachable from a confirmed-marker state because every boot consumer routes that to DeleteIncomplete first; pre-dates delta. INFO.
  12. onCreateVault (1252-1267): create outcomes. ✓
  13. onDeleteAccount onConfirmed finally (1373-1382): D2c disk truth; safe per obliterateLocked ordering. ✓
  14. unlockFromVeil (1446), BackHandler (1483), onNavigate (1547): session navigation, not disk decisions. ✓

  No further site exists — decidable, stated positively.

  **B. bootRoute + runBootReconcile** — verified all five contract properties. Legacy arm precedence correct (confirmed > legacy > present > hold > proven > locked). bootRoute's default param `legacyImage: Boolean = false` — the default exists; two boot callers pass it explicitly; third consumer too. The default is a hazard only for future callers.

  **C. sweep gate** — proven safe per-state; WRITER/READER table complete (rows 1-9 + 6b; I hunted for missing rows: bin.tmp-only-no-dek unreachable by create's DEK-first barrier; intent-indeterminate states safe because sweep doesn't touch markers and reconcile's gates are tristate). Premises verified: destroy() writes confirmed marker durably before unlinks (1096-1120); create() clears both markers durably before writing DEK (494-514); DEK-first barrier (537-557).

  **D. cumulative** — destroy equivalence ✓ (BurnObliterateTest A pins it). Boot healers as one system: sweep/completeInterruptedBurn/reconcileOrphanedBurnMarkers partition the state space without overlap; every state has an owner. Reachability ✓ (slot 0 filler; single dispatch site; Burn only from onUnlockPassphrase). Concurrency ✓ (imageLock serializes; tryBeginTerminalWipe exclusive; unlock refused during wipe). Fail-closed ✓ (partial burn → uniform failure + Locked; burnCompletion carries outcome; cold-start healers).

  One design observation (INFO): reconcileOrphanedBurnMarkers retires an intent over an absent image — for a non-burn media-loss case this abandons the DELETE retry, but the retry is impossible anyway (auth is vault-backed and gone with the image). Consistent with ratified row 6b.

  Findings:

  1. **LOW** — stale `burnCompletion` forces a spurious LOCKED-arm application on later compositions. MainActivity.kt:816-865 (observer). After a successful burn → re-onboard → lock, any Activity recreation re-fires the observer (burnCompletion is process-lifetime RAM, never cleared); postBurnRoute(false, true, false) = LOCKED → writes `lockError = VaultUnlockRouter.UNIFORM_FAILURE` (+ vaultExists=true, route=Locked) over a healthy lock screen — the user sees a "wrong passphrase"-class error they didn't earn. Not a safety failure (route/vaultExists land correctly; fail-closed direction), but it's a visible anomaly — and under the unit's own deniability discipline, an unexplained error is noise. Fix: only apply the LOCKED arm when the completion is "fresh" (e.g. track last-applied generation per composition), or clear/consume burnCompletion once applied, or only set lockError when route actually transitions INTO Locked from a burn-in-flight state. Alternatively re-derive `obliterated` semantics: skip the observer entirely when the current disk state has a vault present (vaultProvenAbsent false + hasVault true → successor exists → ignore).

  Wait, check more carefully whether route could be stomped somewhere harmful in this stale-fire: session null, user mid-onboarding BEFORE create: observer LOCKED arm? provenAbsent true (no vault yet) → ONBOARDING arm → route=Onboarding (already there), vaultExists=false, lockError=null. Fine. User on Onboarding AFTER failed burn? Can't be — failed burn routes Locked. User on lock screen of surviving vault after failed burn: observer stale-fire → postBurnRoute(false, false, ...) = LOCKED → same arm, same lockError — idempotent. OK. So the only visible artifact is the spurious lockError on rotation of a successor-vault lock screen (and, pre-successor, repeated harmless applications). LOW stands. Also note: it sets `unlocking = false` — could that clobber an in-flight unlock attempt's spinner? Rotation already resets the composition (unlock attempt was on composition scope → cancelled). Fine.

  2. **INFO** — boot re-derive effect applies DELETE_INCOMPLETE without re-checking session/route after the IO suspend (MainActivity.kt:795-800), unlike the Splash effect which re-checks `route != Route.Splash` (line 77's analog). Window requires a session publish mid-stat with a confirmed marker durable — practically unreachable (no UI path to unlock while confirmed marker present). Fix if desired: mirror the Splash re-check.

  3. **INFO/E** — still-untested failure shapes (named, per instruction):
     - The call-site input-set parity: no test can catch a future edit dropping `legacyImage` (or the hold) at ONE of the three bootRoute call sites — the pure function is exhaustively tested, the call sites are inspection-only (no Compose test infra; disclosed).
     - `AppContainer.burnVault()` orchestration (tolerated cleanups first, obliterate NOT tolerated, second cache pass always authoritative) has no host test — E1 blocks AppContainer instantiation; the exact fail-open aggregation round 1 closed (`completed && burnObliterationComplete()` in onBurn) is untested at any level.
     - Honestly self-declared by the suite: unremovable-file cache delete; EncryptedSharedPreferences clear path; publish-before-hold ordering not constrainable.
     
     Test headers: I checked the "MUTATION UNIQUELY CAUGHT" claims in BootRouteTest (both verified by mental mutation — they hold), BootReconcileOwnerTest (the corrected claim is now accurate — the publish-injection means ordering genuinely can't be constrained there; the current header says exactly that). BurnAppLocalStateTest's vacuous test was renamed and discloses the gap. No test passes vacuously that I found. SweepOrphanedResidueTest row-5/8 ENOTDIR test honestly self-reports as weak (mutation-verified note) with the ELOOP test as the load-bearing one — and the ELOOP test's mutation claim is plausible (gate 1 swapped to File.exists() → proceeds → deletes dek → assertion fails). 

     One check on `a consumer released by the done signal never observes a stale hold`: does it uniquely catch publish(false)? If publish ignored the sweep result and always passed false, hold.value=false at release → assertion `true` fails ✓. Catches it.

     And `cancellation after a durable sweep does not invent a hold` — rest throws CancellationException; publish in finally with result=SWEPT_DURABLE → hold=false ✓; released ✓. Catches "always hold on cancellation".

  **E verdict**: suite is unusually honest; coverage at consumption sites is as good as host-JVM allows; named gap above.

  **Ready to merge?** No CRITICAL/HIGH/MEDIUM found. One LOW, two INFO. Yes — READY TO MERGE, with the LOW as a follow-up.

  Also address the NOTE: File.exists() verify inside obliterateLocked step (2) — pre-existing, inherited; agree out of scope (tristate would be more consistent — a present-but-unstattable file would read as gone → not fail-closed on that axis — but it's pre-existing and inherited from destroy(), and step (3) dirSync + marker retire re-stat are tristate; I agree with the exclusion, though I'll note the asymmetry in one sentence).

  Actually wait — let me think about step (2) once more since I get to read it in full: `if (binFile.exists() || ...) throw`. A file that survives but can't be stat'd (I/O fault) → exists() false → passes verify → dirSync likely NOT_DURABLE on a sick FS → throw anyway. If dirSync succeeds but the stat fault was transient... a surviving dek with a healthy dir fsync: exists() on dek fails transiently but file present → markers retired → vault.bin gone, dek present, markers gone. Burn "succeeded" (no throw) → burnObliterationComplete() uses Files.notExists — tristate — a transient fault there returns false → provenAbsent false → burned=false → LOCKED. So the outer proof catches what the inner verify misses. Defense in depth holds. Agree out of scope.

  Also double-check burnCompletion generation: `signalBurnCompleted` reads `.value?.generation` then assigns — not atomic, but called from the single burn worker (exclusive gate) — fine.

  Also check Splash effect's `decided` application: vaultExists = decided.present && !decided.legacy — note when BootRoute.DELETE_INCOMPLETE with legacy image: present=true, legacy=true → vaultExists=false. DeleteIncomplete screen doesn't use vaultExists for its retry. Fine.

  One more potential issue in the Splash effect: it's keyed `LaunchedEffect(splashFinished, bootDone)` — fires when both true. If bootDone was already true and splashFinished flips → re-runs. Good. If route left Splash before both true, guard returns. Good.

  Report time. Keep it structured but not bloated. Include file:line cites.

• All verification complete. Writing the report.

• # Independent Security Review — Unit W cumulative (645b8a8…91e7c4d, actual HEAD `91e7c4d`)

  Scope read in full: `MainActivity.kt` (1873 lines), `ZitroneApp.kt` (1500), `VaultImageStore.kt` (1711), `UnlockController.kt`, `MessagingNotifications.kt`, `VaultSlots.kt`, `SettingsRepository.kt` diff, all 7 test files, and `docs/SECURITY_MODEL.md` disclosures. Earlier-round caveats are closed below against the actual source.

  ## Findings

  **LOW — Stale `burnCompletion` re-applies the LOCKED arm on unrelated later compositions**
  `MainActivity.kt:816-865` (the `LaunchedEffect(burnCompletion)` observer). `burnCompletion` is process-lifetime RAM and is never consumed or cleared. Sequence: burn succeeds → user re-onboards (successor vault created) → locks → rotates. The recreated composition fires the observer; `session == null`; `burnObliterationComplete()` is now false (successor image present); `postBurnRoute(false, obliterated=true, false)` = LOCKED → the observer writes `lockError = VaultUnlockRouter.UNIFORM_FAILURE`, `vaultExists = true`, `route = Route.Locked` over a healthy lock screen. Route and `vaultExists` land correctly and the direction is fail-closed, so this is not a safety defect — but the user sees a wrong-passphrase-class error they did not earn, which is exactly the kind of anomalous UI state this unit's deniability discipline tries to avoid. Fix: track the last-applied generation per composition and no-op on an already-seen completion, or skip the observer when `hasVault()` is true (a successor exists and the old burn is moot), or only set `lockError` when transitioning into Locked from a burn-in-flight state.

  **INFO — Boot re-derive effect applies DELETE_INCOMPLETE without a post-suspend re-check**
  `MainActivity.kt:795-800`. The Splash effect re-checks `route != Route.Splash` after its `withContext` (`MainActivity.kt:77`, round-3 fix); the boot re-derive effect checks `container.session.value == null` *before* the IO and then unconditionally applies `route = Route.DeleteIncomplete`. Closing the window requires a session publish mid-stat while a confirmed marker is durable — no UI path reaches that (every boot consumer routes confirmed-marker states to DeleteIncomplete before any lock screen exists). Practically unreachable; mirror the Splash re-check for structural symmetry.

  **INFO — `PassphraseOutcome.LegacyImage` arm routes to Onboarding without consulting the confirmed marker**
  `MainActivity.kt:1127-1134`. Same B1 shape the round-3 fix eliminated, but pre-existing (not in this delta) and unreachable from a confirmed-marker state for the reason above: no consumer can place the tree on the lock screen while `vault.delete-confirmed` is durable, so `attemptPassphrase` cannot run there. Worth folding into `bootRoute` when next touched, not a merge blocker.

  On the NOTE: I agree the `File.exists()` verify inside `obliterateLocked` step (2) (`VaultImageStore.kt:1168-1172`) is out of scope. It is inherited verbatim from `destroy()`, and the residual asymmetry is backstopped: a surviving-but-unstattable file makes the step-(3) `dirSync` fail on a sick filesystem, and `burnObliterationComplete()` re-proves with tristate `Files.notExists` before anything presents success.

  ## Verdicts

  **A — every `route`/`vaultExists` site, decidable: no further site remains.** Full enumeration (grep for `\b(route|vaultExists)\s*=` plus a line-break-variant sweep, each site read in context):

  - `MainActivity.kt:623-631` — initial seeds (session presence; `hasVault()`). Pre-publication, but no safety decision consumes them: both boot consumers re-derive from disk after `bootReconciled`.
  - `646-656` (`onRetryDestroy`) — D2c retry; disk truth `!hasVault() && !serverDeleteConfirmed()`. Sound because the marker retire is strictly post-verify inside `obliterateLocked` (step 4 after steps 2/3), so marker-absent implies full proven unlink.
  - `704-753` (Splash decision) — gated on `splashFinished && bootDone`; carried `residueSweepHold`; full input set **including `legacyImage`**; post-suspend route re-check present. ✓
  - `755-812` (boot re-derive) — calls `startBootReconcile()`, awaits `bootReconciled.first { it }`, then full input set including `legacyImage`. ✓ (INFO above.)
  - `816-865` (burn observer) — uses carried `completion.obliterated`, never re-derives success from `hasVault()`; `postBurnRoute` with live disk reads. ✓ (LOW above.)
  - `894-941` (session collector) — locked arm passes the full `bootRoute` input set including `legacyNow`. ✓
  - `992-1110` (`onBurn`) — exclusive `tryBeginTerminalWipe`, process scope, `burned` published in `finally` after gate release, single routing authority via `postBurnRoute`. ✓
  - `1127-1147` (LegacyImage arm) — INFO above. `1231-1276` (`onCreateVault`), `1369-1383` (delete `onConfirmed` finally), `641-658`, `1446`, `1483`, `1547` — session/D2c navigation, consistent with the durable signals they consume. The "next site" does not exist; this is now positively decidable.

  **B — contract verified.** `runBootReconcile` (`ZitroneApp.kt:1345-1387`): once-only CAS; `publish` in `finally` on every exit including cancellation (re-thrown `CancellationException` publishes the fail-closed default, not a success); default `SWEPT_NOT_DURABLE`; dispatcher injected; `afterPublish` back on the IO dispatcher post-publication. Publication order in `startBootReconcile` (`ZitroneApp.kt:847-874`) writes `residueSweepHold` before `bootReconciled = true`, and every consumer reads the hold only after awaiting the done signal. `bootRoute` precedence (confirmed > legacy > present > hold > proven-absent > locked) is correct in both directions: it admits nothing without proven absence or a legacy image, and it strands nothing — every state another owner holds (confirmed marker, present image, legacy) routes to that owner.

  **C — sweep gate proven safe in every state; premises verified, not trusted.** `destroy()` writes `vault.delete-confirmed` REQUIRED-DURABLE before any unlink (`VaultImageStore.kt:1117-1118`), so every D2c unlink state is caught by gate 2. `create()` clears both markers durably *before* writing the DEK (`VaultImageStore.kt:494-514`) and its DEK-first durability barrier (537-557) makes `{bin, no-dek}` unreachable from create — the exact inverse of the burn signature. The intent-gate removal is sound: `{no bin, residue, intent}` is only burn-produced, and sweeping unblocks `reconcileOrphanedBurnMarkers`, which retires the intent. Missing-row hunt: `{bin.tmp, no dek, no bin}` is unreachable (dek durable before bin write begins); intent-indeterminate states are safe because the sweep never touches markers and the reconcile's gates are tristate. The kdoc table (rows 1–9 + 6b) is complete.

  **D — the unit holds as one system.** Keys-first `obliterateLocked` + `destroy()` equivalence is pinned by test; marker retire strictly after proven-durable unlinks; the three boot healers partition the state space (bin present / bin absent+residue / fully absent+intent) with no overlap and no orphan state; reachability is structural (`createVaultSlots` leaves slot 0 as random filler, `VaultSlots.kt:139-142`; `attemptPassphrase` has exactly one caller, the lock screen, `MainActivity.kt:1119`); concurrency is serialized by `imageLock` and the exclusive wipe gate; a partial burn presents as a mistyped passphrase and every cold start re-heals. `reconcileOrphanedBurnMarkers` retiring an intent over an absent image is consistent with ratified row 6b — the DELETE retry is impossible anyway once the vault (and the vault-backed auth) is gone.

  **E — test quality: good; no vacuous test and no false mutation claim found.** I re-derived every "MUTATION UNIQUELY CAUGHT" header: both `BootRouteTest` claims hold (hoisting the legacy arm fails `:118`; moving it below image-present fails `:155`); the corrected `BootReconcileOwnerTest:86-100` header is now accurate (publish is injected, so internal ordering is genuinely unconstrainable there); the `BurnAppLocalStateTest:126-135` vacuous test was honestly renamed; the weak ENOTDIR test self-discloses and the ELOOP test is the load-bearing one. Consumption-site coverage exists for both pure decision functions, including the 32-combination onboarding-reachability sweep.
  The failure shapes **still untested**, stated plainly: (1) call-site input-set parity — no test can catch a future edit dropping `legacyImage` or the hold at one of the three `bootRoute` call sites (no Compose test infra; disclosed); (2) `AppContainer.burnVault()` orchestration — tolerated-cleanups-first, obliterate-not-tolerated, always-run second cache pass — has no host test (exclusion E1 blocks `AppContainer` construction), so the exact fail-open aggregation round 1 closed (`completed && burnObliterationComplete()` at `MainActivity.kt:1041-1042`) is verified by inspection only; (3) the stale-`burnCompletion` behaviour in the LOW above.

  ## Merge verdict

  **READY TO MERGE.** No CRITICAL/HIGH/MEDIUM findings. One LOW (spurious lock error from the never-cleared `burnCompletion`) and two INFOs, all suitable as follow-ups. The comments in this unit are now accurate where I checked them against the code — including the ones that replaced the four false claims called out in the brief.

To resume this session: kimi -r session_19cd8676-e2a7-47a5-900c-d6fb91787906
=== EXIT: 0 ===
