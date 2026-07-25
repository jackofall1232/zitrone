You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is ROUND 2 of a paired-blind review. You are blind to the other reviewer.

Review the CUMULATIVE change of TWO commits on branch `feat/0.9.2-burn-unit-w-wipe` in /root/zitrone:
  git -C /root/zitrone show 645b8a8    # Unit W: the Pucker Burn duress wipe mechanism
  git -C /root/zitrone show 764845e    # round-1 review fixes
  git -C /root/zitrone diff main...HEAD  # the whole unit as it would merge

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust commit messages, comments, or this prompt.

## Context
Pucker Burn is a DURESS credential: entered at the lock screen, it wipes the vault. Slot 0 of the
multi-slot image is reserved for it and is currently UNARMED (random filler), so the wipe is not
reachable in production yet — this unit ships the MECHANISM only. The physical destruction was factored
out of `VaultImageStore.destroy()` into a marker-free `obliterateLocked()`, shared by `destroy()` (which
prefixes a `vault.delete-confirmed` crash-bridge) and `obliterateForBurn()` (which must NOT write it).

D2c background (hardened over 16 rounds): two durable markers, `vault.delete-intent` (delete initiated,
server outcome unknown; also an AUTH-PROTECTION guard via `hasDeleteIntentMarker()`) and
`vault.delete-confirmed` (server account provably gone — the ONLY authorization for the
`Route.DeleteIncomplete` auto-destroy). Marker discipline is tristate `Files.notExists` + required
dirSync, fail-closed: `File.delete()`'s bool and `File.exists()==false` are both untrustworthy because
an I/O/stat fault is indistinguishable from absence.

## What round 1 found and what was changed (VERIFY THE FIXES ARE REAL AND COMPLETE)
Three HIGH fail-open defects, fixed in 764845e:
 (1) Burn success was decided by `runCatching{burnVault()}` + `!hasVault()`. `hasVault()` keys on
     `vault.bin` alone, so a surviving `vault.dek` or `vault.bin.tmp` (a temp stages a COMPLETE outer
     image) read as success. Now: no-throw AND `obliterationComplete()` tristate-proves all four files
     absent.
 (2) `reconcileOrphanedBurnMarkers` used `binFile.exists()` (fail-open) — an indeterminate stat could
     clear a genuine delete-intent over a LIVE vault (the "B1" state). Now tristate.
 (3) `clearCacheDir` returned true when `listFiles()` returned null, and its result was discarded inside
     `tolerateCleanup`. Now fail-closed + result retained in `BurnResult`.
Plus: `completeInterruptedBurn()` for the keys-first `{image present, DEK absent}` crash window, which
previously had no self-heal; corrected failure copy.

## FOCUS FOR THIS ROUND
A. Are fixes (1)(2)(3) actually complete and correct, or partial//circumventable? Any REMAINING
   fail-open path in the burn success decision, the reconciler, or the cache clear?
B. Did the fixes introduce NEW defects? Specifically:
   - `completeInterruptedBurn()` is a NEW no-credential path that DELETES a present vault image whenever
     the DEK is proven absent. Can any state reach it that is NOT an interrupted burn — a partially
     written fresh create, a restore, an unusual crash, a hostile file layout? Could it destroy a
     recoverable vault? Is running it on EVERY cold start safe?
   - `BurnResult` / the retry-on-boot cache logic: any way it runs when it shouldn't, or leaks state?
   - The MainActivity boot LaunchedEffect now mutates route/vaultExists. Race with the session collector,
     Splash routing, or the legacy-image check?
C. THE STATED POLICY, which you should challenge if wrong: a plaintext cache that cannot be cleared does
   NOT abort the burn — the keys are destroyed anyway, the cache is retried after obliteration and on
   every vault-less cold start, and any residual is DISCLOSED (SECURITY_MODEL) rather than claimed away.
   The reasoning: refusing to destroy keys because a staged photo is locked leaves everything readable
   under duress. Is that the right call? Is the disclosure honest and sufficient?
D. Still binding from round 1 — re-verify independently:
   1. destroy() EQUIVALENCE. The refactor changed destroy()'s unlink order (bin-then-dek -> dek-then-bin,
      keys-first). Both round-1 reviewers accepted the argument that this is safe because the confirmed
      marker is durable before either unlink so every crash re-runs the idempotent destroy. EVALUATE IT
      YOURSELF; don't inherit that conclusion. If unacceptable, a `keysFirst` param is the fallback.
   2. Marker clear STRICTLY after the unlinks are proven durable — no path clears markers over live state.
   3. Boot reconciliation: crash window covered; image-absent can never route to DeleteIncomplete.
   4. WRITER/READER invariants for every durable signal burn touches.
   5. REACHABILITY in the shipped diff: slot 0 unarmed, wipe wired ONLY to the lock-screen dispatch.
      (`attemptUnlockOrAdd` returns the Burn outcome and is ALSO the second-vault collision path — a
      wiring that wipes anywhere else would make an unlucky create a self-inflicted total wipe.)
   6. Concurrency/lifecycle: terminal-exclusion gate never stranded; rotation-safe; no resurrection.
   7. Fail-closed overall: can a partial burn still present as success, or leave state worse than not
      burning?
E. NOTE: the `File.exists()` verify INSIDE `obliterateLocked` is pre-existing code inherited verbatim
   from destroy() (same pattern also in `retireLegacyImage`), deliberately NOT changed in this unit
   because tightening it alters D2c behaviour. Say whether you agree with that scoping, but do not count
   it as a new defect.
F. Test quality: do the new tests prove what they claim or pass vacuously? What failure shape is STILL
   untested?

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it matters,
concrete fix. Cite source you actually read. Then give an explicit verdict on A, B, C, each of D.1-D.7,
E and F. State clearly whether this is READY TO MERGE. If correct, say so briefly — do not invent
findings to seem thorough.
