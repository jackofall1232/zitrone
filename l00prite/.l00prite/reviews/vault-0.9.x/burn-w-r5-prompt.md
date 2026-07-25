You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 5 of a paired-blind review. You are blind to the other reviewer.

PRIMARY SCOPE — the round-4 FIX DELTA (fixes are NOT lower-risk than original code; treat as guilty
until independently proven otherwise — round 4 proved this the hard way, see below):
  git -C /root/zitrone show 40231c4
CUMULATIVE UNIT as it would merge:
  git -C /root/zitrone diff main...HEAD
  # 645b8a8 mechanism · 764845e r1 fixes · 813245b self-audit · 0dce2e6 r2 fixes
  # · b94d5a8 r3 fixes · 40231c4 r4 fixes   (923fd37 is loop bookkeeping, NO code — ignore)

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust commit messages, comments, or this prompt.

TWO PROCESS FACTS FROM ROUND 4 THAT SHOULD SHAPE HOW YOU READ THIS CODE:
1. The round-3 FIX INTRODUCED A HIGH. A fix delta is not safer than original code.
2. One round-4 reviewer verified a safety property by reading the CODE COMMENT that asserted it,
   and reported it "Verified" — the property did not hold. Comments in this unit are extensive and
   confident. Treat every one as an unproven assertion. Where a comment claims a fail-closed
   property, derive it from the code yourself or report it as unverified.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is UNARMED
(uniformly-random filler), so the wipe is unreachable in production — this unit ships the MECHANISM
only. The CENTRAL invariant is post-burn ≡ fresh install: after a burn the app presents ordinary
first-run onboarding, and — the round-4 lesson — a burn that did NOT fully take must never present
that way.

## What round 4 found and what 40231c4 changed (VERIFY EACH FIX IS REAL, COMPLETE, AND SAFE)
- HIGH: the round-3 observer published a bare completion COUNTER and re-derived success from
  `hasVault()` (`vault.bin` alone), which is weaker than the burn's own proof (`burnVault()` did not
  throw AND `burnObliterationComplete()` = bin + dek + both temps proven absent). `obliterateLocked()`
  unlinks keys-first and verifies afterwards, so all four of its throw paths leave `vault.bin` gone —
  a FAILED burn was routed to Onboarding.
  Fix: `AppContainer.burnCompletion: MutableStateFlow<BurnCompletion?>` carrying
  `(generation, obliterated)`, where `obliterated` is the dispatcher's own proof; `burned` moved
  outside the `try` so the `finally` publishes the outcome and stays false if the block threw.
- MEDIUM/LOW: the observer never consulted `serverDeleteConfirmed()`, so after any burn in the
  process a later incomplete account-delete could bypass `Route.DeleteIncomplete`.
  Fix: the decision is now the pure `postBurnRoute(serverDeleteConfirmed, burnReportedSuccess,
  imageBearingProvenAbsent)` in ZitroneApp.kt, precedence: confirmed delete → DeleteIncomplete;
  reported success AND proven absent → Onboarding; otherwise → Locked. 8 new tests in
  PostBurnRouteTest, exhaustive over all 8 input combinations.

## FOCUS FOR THIS ROUND
A. Is the fail-closed proof now COMPLETE and correctly plumbed end-to-end?
   - Trace `obliterated` from `burnVault()` through the `finally` to `postBurnRoute` to the route.
     Can it ever be published as `true` when the burn did not fully take? Can the `finally` publish a
     STALE or default `false` for a burn that actually succeeded (a spurious failure presentation)?
   - `burned` is now assigned inside the `try` and read in the `finally`. Verify the Kotlin semantics
     hold for every exit: normal return, throw, and coroutine cancellation.
   - Is `postBurnRoute`'s precedence right, or does some fourth state need its own arm?
B. The LOCKED arm sets `vaultExists = true` when the burn failed — deliberately NOT from
   `hasVault()`, because with `vault.bin` gone and a temp surviving `hasVault()` would route the tree
   to onboarding over a recoverable image. Is that defensible, or is writing a routing flag that
   contradicts disk truth going to break something else? Trace every consumer of `vaultExists`
   (biometric availability, the lemon-drop veil, Splash, LegacyImage handling) for a state where
   `vaultExists = true` over an absent `vault.bin` misbehaves.
C. KNOWN RESIDUAL, disclosed rather than fixed — assess it, do not treat it as hidden:
   `{vault.bin absent, vault.dek present}` has NO cold-start self-heal. `completeInterruptedBurn()`
   requires bin PRESENT + dek proven absent; `reconcileOrphanedBurnMarkers()` requires all
   image-bearing files proven absent. After 40231c4 this presents honestly as a lock screen rather
   than a false fresh install, but the user is stuck there (unlock → MissingImage → ImageUnreadable)
   until a reinstall. Is "honest but stuck" the right call for this unit, or is it a brick that must
   be fixed before merge? Is there a state where it is WORSE than the pre-fix behaviour?
D. Did 40231c4 introduce ANY new defect? Re-examine the observer's two disk reads
   (`serverDeleteConfirmed()` + `burnObliterationComplete()` in one `withContext(IO)`), the
   `LaunchedEffect(burnCompletion)` keying on a data class, and the interaction with the session
   collector and boot reconciler.
E. Re-verify the CUMULATIVE unit end-to-end — do NOT assume earlier rounds' conclusions, INCLUDING
   the ones this prompt reports as settled and including anything an earlier round marked PASS:
   1. destroy() equivalence under keys-first unlink order.
   2. Marker clear STRICTLY after unlinks are proven durable.
   3. Boot reconciliation + `completeInterruptedBurn()` (no-credential path).
   4. WRITER/READER invariants for every durable signal the burn touches.
   5. REACHABILITY: slot 0 unarmed; wipe wired ONLY to lock-screen dispatch.
   6. Concurrency/lifecycle, including the exclusive `tryBeginTerminalWipe()` gate and the
      process-scoped completion signal.
   7. Fail-closed: can a partial burn present as success, or leave state worse than not burning?
F. NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
   pre-existing, inherited verbatim from destroy(), deliberately out of scope. Say if you disagree.
G. Test quality. `PostBurnRouteTest` was mutation-checked (dropping `burnReportedSuccess` from the
   onboarding condition fails 3 of its 8). Is the suite meaningful or does some case pass vacuously?
   What failure shape is STILL untested? The project has no Compose/instrumentation infrastructure,
   so the wiring around the pure decision is inspection-only and disclosed as such in
   docs/SECURITY_MODEL.md — judge whether that disclosure is now accurate.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give an explicit verdict on A, B, C, D, each of E.1-E.7,
F, G. State clearly whether this is READY TO MERGE. If it is correct, say so plainly and briefly — do
NOT invent findings to appear thorough. An honest clean pass is the expected outcome if the fixes
hold. If you believe a previous round's accepted conclusion was WRONG, say so explicitly.
