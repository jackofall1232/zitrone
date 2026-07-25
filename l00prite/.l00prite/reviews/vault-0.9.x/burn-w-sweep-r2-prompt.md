You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 2 of a paired-blind review of the residue-sweep delta. You are blind to the other reviewer.

PRIMARY SCOPE — the round-1 FIX DELTA:
  git -C /root/zitrone show 98c0319
THE DELTA IT FIXES (both together are what would merge):
  git -C /root/zitrone show c144216
CUMULATIVE UNIT:
  git -C /root/zitrone diff main...HEAD
  # 645b8a8 mechanism · 764845e r1 · 813245b self-audit · 0dce2e6 r2 · b94d5a8 r3 · 40231c4 r4
  # · eadd7aa disclosure fix · c144216 sweep · 98c0319 sweep-round-1 fixes
  # (923fd37, 50b5277, 00fb5dc, 2212ada, c6f2082 are loop bookkeeping under l00prite/ — NO code)

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust commit messages, comments, or this prompt. This
unit's comments are extensive and have been WRONG repeatedly — including an invariant table that was
internally coherent and simply wrong about which component owned a state. Derive every safety
property from the code yourself.

## Four STANDING instructions (not per-round asks — apply them to everything below)

1. **PROVE ANY TABLE COMPLETE, NOT SELF-CONSISTENT.** Hunt the MISSING ROW. Checking the listed rows
   against each other is worthless — the last table was perfectly coherent and wrong.
2. **A GATE CAN BE WRONG BY BEING TOO NARROW.** Prove BOTH directions for every gate: what it
   wrongly lets through, AND what it wrongly STRANDS. "Another component owns this state" is a claim
   to verify against that component's real preconditions, never an assumption. Round 1 found a gate
   that protected nothing while permanently stranding a recoverable encrypted image.
3. **HUNT THIS NAMED PATTERN — it has produced a HIGH three times in this unit, each time inside the
   fix for the previous one:** *an authoritative result is computed, discarded, and a weaker one
   re-derived at the point of consumption.* It survives review because the weaker signal is nearly
   always right, so tests pass and behaviour looks correct; the divergence appears only in the narrow
   window the authoritative result exists to cover. For every safety verdict in this delta, ask: WHO
   CONSUMES THIS, and do they use THIS EXACT VALUE, or something cheaper they computed themselves?
   Treat any `runCatching { … }` whose result is discarded as a smell — it drops the value AND the
   error.
4. **A TEST THAT A VALUE IS COMPUTED IS NOT A TEST THAT IT IS USED.** Judge whether each safety
   verdict has coverage at its CONSUMPTION site, not merely at its production site. That seam is
   exactly how round 1's HIGH got in.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is UNARMED
(uniformly-random filler), so the wipe is unreachable in production — the unit ships the MECHANISM
only. Central invariant: post-burn ≡ fresh install, AND a burn that did NOT fully take must never
present that way. The sweep is a DESTRUCTIVE BOOT OPERATION: it unlinks files at cold start before
the user has authenticated anything.

## What round 1 found and what 98c0319 changed (VERIFY EACH FIX IS REAL, COMPLETE, AND SAFE)
- HIGH (reviewer A): the sweep's durability verdict was DISCARDED by the caller, which re-derived
  cleanliness from `vaultProvenAbsent()` — a fresh stat, true the instant a file is unlinked whether
  or not that survives a crash. Onboarding could be presented over residue a journal replay resurrects.
  Fix: `sweepOrphanedResidue()` now returns `ResidueSweepResult` (NO_MUTATION / SWEPT_DURABLE /
  SWEPT_NOT_DURABLE) with an explicit MUTATION POINT past which no exit may report NO_MUTATION,
  including a throw; the verdict is carried in the PROCESS-scoped `AppContainer.residueSweepHold` and
  consumed by a new pure `bootRoute(...)`. Boot reconciliation now runs once per PROCESS.
- HIGH (reviewer B): the gate on `vault.delete-intent` was TOO NARROW and the table's row 6 was FALSE.
  `destroy()` writes the CONFIRMED marker durably BEFORE it unlinks, so every real D2c unlink was
  already caught by the other gate — the intent gate protected nothing, while stranding
  `{no bin, residue, intent}`, which no healer could reach. Fix: intent gate dropped; row 6b added.
- LOW: the session collector still keyed on `hasVault()` while the delta claimed onboarding requires
  proven absence "everywhere". Fixed the code.

## FOCUS FOR THIS ROUND
A. IS THE DURABILITY VERDICT NOW CARRIED END-TO-END? Trace `ResidueSweepResult` from the store to
   every routing decision. Can `SWEPT_NOT_DURABLE` be LOST (dropped, overwritten, reset) or
   SPURIOUSLY SET (a hold that never clears, bricking onboarding on a healthy device)? Is the
   MUTATION POINT correct — is there any path past it that can still report NO_MUTATION? Is the
   total `catch (t: Throwable)` right, or does it mask something that should propagate?
B. IS DROPPING THE INTENT GATE SAFE IN EVERY STATE? This is a DESTRUCTIVE operation that now runs in
   strictly more situations than before. Enumerate the on-disk states independently and prove the
   corrected table (rows 1–3, 4–8, 6b, 9) COMPLETE. Is there any state where a `vault.delete-intent`
   with a proven-absent image legitimately accompanies residue that must be PRESERVED? Does sweeping
   under an intent break any D2c reader or the intent's own retry semantics?
C. PROCESS-SCOPED BOOT STATE. `bootReconciled` / `residueSweepHold` are `MutableStateFlow` on the
   container, with a once-per-process `tryBeginBootReconcile()` CAS. Verify: a composition created
   AFTER boot finished still observes the result; rotation does not re-run destructive work; process
   death resets correctly; no composition can route before `bootReconciled`. Can the once-guard strand
   boot if the claiming coroutine dies (cancellation, throw) before setting `bootReconciled`?
D. `bootRoute` precedence and its two consumers (the boot re-derive and `Splash.onFinished`). Do BOTH
   consume the same values, or does one still re-derive? Is `Splash` racing the boot effect?
E. Did 98c0319 introduce ANY new defect? Re-examine the session collector change, the removal of the
   `finished` gating on the re-derive, and `bootReconcileRest`'s ordering (sweep → interrupted burn →
   marker retire).
F. Re-verify the CUMULATIVE unit — do NOT assume earlier conclusions, including anything marked PASS:
   1. destroy() equivalence under keys-first unlink order.
   2. Marker clear STRICTLY after unlinks proven durable.
   3. The full set of boot healers as ONE coherent system: sweep, completeInterruptedBurn,
      reconcileOrphanedBurnMarkers. Overlap, contradiction, or a state no one owns?
   4. WRITER/READER invariants — durable signals AND in-flight verdicts (see standing instruction 3).
   5. REACHABILITY: slot 0 unarmed; wipe wired ONLY to lock-screen dispatch.
   6. Concurrency/lifecycle end-to-end.
   7. Fail-closed: can a partial burn present as success, or leave state worse than not burning?
G. NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
   pre-existing, inherited from destroy(), deliberately out of scope. Say if you disagree.
H. Test quality. Four mutations have been run across this delta and all were caught (dropping the
   confirmed gate; a fail-open `File.exists()` gate; `bootRoute` ignoring the hold; the sweep
   collapsing durability into success). One test is documented in-file as WEAK, with its limitation
   stated. Judge whether the suite holds the gate, what shape is STILL untested, and whether any test
   passes vacuously. The project has no Compose/instrumentation infrastructure.

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Give an explicit verdict on A, B, C, D, E, each of
F.1-F.7, G, H. State clearly whether this is READY TO MERGE. If it is correct, say so plainly and
briefly — do NOT invent findings to appear thorough. An honest clean pass is the expected outcome if
the delta holds. If you believe a previous round's accepted conclusion was WRONG, say so explicitly.
