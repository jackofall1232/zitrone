# INDEPENDENT SECURITY REVIEW — Unit S round 2 (Pucker Burn ARMING, 0.9.3)

You are one of two reviewers working **blind to each other**. Judge **this checkout only**.
Verify every claim below against SOURCE. If a claim here and the code disagree, **the code wins** —
say so plainly and treat my description as the defect.

## Scope — the ROUND-1 FIX ONLY

Review commit `d3680570` on branch `feat/0.9.3-unit-s-burn-arming`.

```
git show d3680570
git diff 32a530a6..d3680570
```

The unit as a whole (`22baf192`, `a6753486`, `32a530a6`) was reviewed in round 1. **Do not re-review
it from scratch.** Round 2 asks one question: *does this fix actually close the round-1 blocking
finding, without introducing a new one?* Regressions the fix could plausibly have caused in the
surrounding unit ARE in scope.

## What round 1 found, and what I did about it

**BLOCKING (Codex HIGH; Grok saw the same mechanism as F2 but rated it deferrable).**
`burnSetupOpen` / `burnSetupBusy` / `burnSetupError` were composition-local `remember` in
`MainActivity`, while the Argon2id arm ran on `container.scope`. An Activity recreation (rotation,
dark-mode toggle, font-size change, split-screen) reset them and dismissed the dialog, while the
continuation wrote its outcome into a dead composition.

The reason that is not cosmetic: a successful arm is signalled **only** by the dialog closing —
there is no success toast — so a recreation-induced dismissal was indistinguishable from success. A
`CollidesWithVault` / `DeletePending` / `NotDurable` arm therefore read as an armed one, leaving the
user believing they hold a duress credential they do not have.

**I adjudicated against source that Codex was right and Grok's reason for deferring ("no success
toast on failure") is false — there is no success toast at all.** Tell me if that adjudication is
wrong.

**The fix:** state moved to `AppContainer.burnArm`, a process-scoped `MutableStateFlow<BurnArmUi>`,
mirroring the existing `vaultCreating`. `burnArmOutcome()` and `beginBurnArm()` were extracted to
top-level so the fail-closed invariant is testable. New `BurnArmStateTest`; `ArmBurnSlotTest` gained
the missing `vault.delete-confirmed` case; warning copy re-scoped from "this vault" to "everything
Zitrone holds on this device".

## Answer these explicitly

**A. Is the blocking finding actually closed?** Trace a recreation mid-arm through the new code. Can
any interleaving still (i) dismiss the dialog while an arm is in flight, (ii) lose a terminal
failure, or (iii) present a failed arm as success? Is `BurnArmUi.Closed` reachable from anything but
`ArmBurn.Armed`?

**B. Did the fix introduce a NEW defect?** Specifically: the CAS loop in `beginBurnArm`; whether
`tryBeginBurnArm` can be starved or livelock; whether a stale continuation from a *previous* arm can
publish over a *newer* one (an ABA on the flow); whether `closeBurnSetup()` racing a landing outcome
can drop a failure the user must see, or conversely resurrect a dialog they dismissed.

**C. Is invariant P1 (no armed flag) still intact?** `burnArm` is new observable state. Prove it is
RAM-only and reflects only an attempt in the current session — never whether a credential exists.
Does it survive process death? Is it reachable from any durable store, log, or backup? Does the
Settings row still render identically armed vs unarmed?

**D. Is the passphrase handling unchanged or worse?** The candidate is now captured by a lambda
passed to `finishBurnArm`'s call site. Confirm no new retention: is the credential reachable from
`burnArm` state, from `BurnArmUi.Rejected`, or from anything that outlives the arm?

**E. Copy accuracy (F3).** Does "everything Zitrone holds on this device / returns the app to a
fresh install" match what the burn ACTUALLY does per `obliterate()` / the byte-for-byte gate? Is it
now over-claiming in the other direction? Does it leak how many vaults exist (a PD break)?

**F. Do the new tests discriminate, or do they merely pass?** For each test in `BurnArmStateTest`,
name a realistic mutation it would catch. Name anything about the fix that is NOT covered.

**G. F1 comment accuracy.** The `MainActivity` comment about slot 0 was rewritten. Is it now true?

## Evidence I am claiming — challenge it

- Suite `573 tests / 570 passed / 0 failures / 0 errors / 3 skipped` (was 562/559 pre-fix);
  `BurnArmStateTest` 10/10; `ArmBurnSlotTest` 11/11; `:app:assembleDebug` green. JDK 17.
- Mutation check: changing `DeletePending -> Closed` in `burnArmOutcome` turned
  `a pending delete is reported, never silently closed` RED; reverted and re-verified green.

These numbers are MINE. You are not required to reproduce them, and **you do not need to run Gradle
for this review** — it is a source question. If you do run it, use
`GRADLE_USER_HOME=/mnt/volume-l00prite-cx33/gradle-reviewers` (39G free). Do NOT let Gradle default
into `/tmp` or the repo: reviewer runs have twice filled the 38G root disk, and ENOSPC surfaces as
phantom test failures — one past reviewer reported "164 failures" that were purely disk. If you
cannot run it, say so and review the source; do not adopt or invent numbers.

## Rules

- **Severity discipline.** Label each finding CRITICAL / HIGH / MEDIUM / LOW / INFO, and state
  plainly whether it is **BLOCKING** or **DEFERRABLE**. Blocking means: it can make a user believe a
  duress credential is armed when it is not, arm a credential that wipes on an ordinary unlock, or
  destroy data unexpectedly. Do not inflate to be safe, and do not soften a real one.
- Every finding needs file:line, a concrete failure scenario, and a concrete fix.
- If you find nothing blocking, say **READY TO MERGE** explicitly. Do not manufacture findings to
  look thorough.
- End with a one-line verdict: **READY TO MERGE** or **NOT READY TO MERGE**.
