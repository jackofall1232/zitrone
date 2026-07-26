You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 4 of a paired-blind review. You are blind to the other reviewer.

PRIMARY SCOPE — the round-3 FIX DELTA (fixes are NOT lower-risk than original code; treat as guilty
until independently proven otherwise):
  git -C /root/zitrone show b94d5a8
CUMULATIVE UNIT as it would merge (verify the whole thing still holds):
  git -C /root/zitrone diff main...HEAD
  # 645b8a8 mechanism · 764845e round-1 fixes · 813245b self-audit · 0dce2e6 round-2 fixes
  # · b94d5a8 round-3 fixes · 923fd37 (loop bookkeeping under l00prite/, NO code — ignore it)

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust commit messages, comments, or this prompt.
Round 3 found a comment that confidently asserted a safety property the code did not have, and that
false claim plausibly caused two earlier rounds to skip the check. Treat every safety claim in a
comment — including the ones ADDED by b94d5a8, which are extensive — as an assertion to verify, not
as information.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is reserved
for it and is currently UNARMED (uniformly-random filler), so the wipe is unreachable in production —
this unit ships the MECHANISM only, deliberately, so the destructive path could be reviewed before
anything can trigger it. The unit's CENTRAL invariant is post-burn ≡ fresh install: after a burn the
app must present ordinary first-run onboarding. A screen that is anomalous in any way is a prior-use
tell in the exact scenario the feature exists for.

## What round 3 found, and what b94d5a8 changed (VERIFY EACH FIX IS REAL, COMPLETE, AND SAFE)
- MEDIUM (reviewer B), escalated on adjudication: burn completion was not composition-safe. The burn
  runs on `container.scope` (process-scoped) but wrote its UI result only to the composition that
  STARTED it. `MainActivity` has no `android:configChanges`, so an Activity recreation mid-burn
  disposes that composition; the new one seeds `vaultExists` from a plain `remember { hasVault() }`
  while the image is still present, and nothing re-derived afterwards (the session collector is gated
  on `unlocked` and a burn has NO session; the boot reconciler only re-routes when IT completed a
  wipe). Result: a recreated tree on Locked over an ABSENT vault, every unlock escalating as an
  unreadable image, stuck until process death — a functional brick AND a prior-use tell.
  Fix: `AppContainer.burnsCompleted`, a process-scoped `MutableStateFlow<Int>` bumped in `onBurn`'s
  `finally` on BOTH outcomes; a `LaunchedEffect(burnGeneration)` in the composition re-derives
  `vaultExists` from DISK and routes to Onboarding when the vault is gone.
- MEDIUM (reviewer A), confirmed as fact but DOWNGRADED to LOW: `retryPlaintextCacheClearIfNoVault`
  gated destructive cache clearing on `imageStore.exists()` (`File.exists()`-backed), so an
  indeterminate stat read as absence. Downgraded because the fail direction is toward over-clearing
  an OS-evictable cache at cold start — it can never leave plaintext behind after a burn. Fixed
  anyway for consistency: new `VaultImageStore.primaryImageProvenAbsent()` (`Files.notExists`).
- The false comment at the old `MainActivity.kt:842-845` was deleted and replaced.

## FOCUS FOR THIS ROUND
A. THE LOAD-BEARING QUESTION: does the `burnsCompleted` signal actually CLOSE the recreated-
   composition window, or merely NARROW it? A fix that shrinks a race looks correct and passes casual
   review while retiring the finding, which is worse than no fix. Specifically:
   - Is there ANY interleaving where a burn completes and NO live composition ends up re-deriving —
     e.g. bump lands between compositions, no composition is active at bump time, the effect is
     cancelled by recomposition, or `LaunchedEffect` keying drops a bump?
   - `collectAsState` on a `MutableStateFlow<Int>` conflates: two burns bumping 1→2 with no
     composition alive in between yields ONE observed value. Is that safe here, or can a bump be
     lost in a way that matters?
   - Was rejecting "re-read `hasVault()` in `Splash.onFinished`" as insufficient correct? The stated
     reason: if Splash finishes while the burn is in flight the image is still present, so it routes
     to Locked and the completion write still hits a disposed composition. Verify or refute.
B. Can the new observer STOMP routing it should not own?
   - A SUCCESSOR vault created after a burn: the counter stays non-zero forever, so every later
     composition runs the effect with a non-zero generation. Prove it cannot drag a live successor
     vault back to Onboarding. The guards are a `container.session.value != null` early return and
     re-deriving `vaultExists` from disk instead of caching `false` — are BOTH correct and sufficient?
     What about the window after a vault is created but before its session is published?
   - A FAILED burn (bumped on both outcomes): does it correctly stay on the lock screen?
   - Interaction with account-delete routing (`Route.DeleteIncomplete`, `serverDeleteConfirmed()`)
     and with the D2c marker paths — can the effect route to Onboarding over a state that D2c owns?
   - The effect calls `hasVault()` inside `withContext(Dispatchers.IO)`; the surrounding state writes
     are Compose state. Is the threading correct, and is there a torn-state window?
C. Is bumping inside `onBurn`'s `finally` correct? It is ordered AFTER `endTerminalWipe()`. Can the
   bump be SKIPPED on any path (throw from `endTerminalWipe`, cancellation, process death), and what
   is the consequence of a skipped bump versus a doubled one?
D. `primaryImageProvenAbsent()` — correct, and correctly used? Is `exists()` still used anywhere that
   DELETES or otherwise acts destructively on "no vault"? Is the new test meaningful (it constructs a
   real indeterminate stat via an ENOTDIR baseDir) or does it pass vacuously?
E. Re-verify the CUMULATIVE unit end-to-end — do NOT assume earlier rounds' conclusions, including
   the ones this prompt reports as settled:
   1. destroy() equivalence under the keys-first unlink order.
   2. Marker clear STRICTLY after unlinks are proven durable.
   3. Boot reconciliation + `completeInterruptedBurn()` (no-credential path).
   4. WRITER/READER invariants for every durable signal the burn touches.
   5. REACHABILITY: slot 0 unarmed; wipe wired ONLY to the lock-screen dispatch.
   6. Concurrency/lifecycle end-to-end, including the exclusive `tryBeginTerminalWipe()` gate.
   7. Fail-closed: can a partial burn present as success, or leave state worse than not burning?
F. NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
   pre-existing code inherited verbatim from destroy() (same pattern in `retireLegacyImage`),
   deliberately out of scope because tightening it changes D2c behaviour. Say if you disagree.
G. Test quality: what failure shape is STILL untested? Note that this project has NO Compose or
   instrumentation test infrastructure, so the burn UI reconciliation is inspection-verified only and
   is disclosed as such in docs/SECURITY_MODEL.md. Judge whether that disclosure is honest and
   sufficient, or whether some part of it IS testable at a seam and should be.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give an explicit verdict on A, B, C, D, each of E.1-E.7,
F, G. State clearly whether this is READY TO MERGE. If it is correct, say so plainly and briefly — do
NOT invent findings to appear thorough. An honest clean pass is the expected outcome if the fixes
hold. If you believe a previous round's accepted conclusion was WRONG, say so explicitly.
