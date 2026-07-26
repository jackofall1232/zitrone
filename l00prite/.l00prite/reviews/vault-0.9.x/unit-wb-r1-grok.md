I'll conduct an independent security review of Unit W-B (Pucker Burn) by reading the protocol, the unit range, and source — verifying every focus item A–J against code, not comments.Reading core burn, durability-hold, and destroy paths from source.Examining the reconciler unproven-logic defect more carefully, plus `vaultExists` consumers and oracle surfaces.Investigating the mass test failures — likely environment, not product code.JNA can't extract into `/tmp` (permission denied). Retrying with an executable temp directory.# Unit W-B Security Review (Round 1, Independent)

**Scope:** `main...HEAD` @ `1c31ce1`  
**Verdict: NOT READY TO MERGE** — one durability-fold defect is load-bearing and wrong in source; several post-burn residual/oracle gaps remain.

---

## Findings

### F1 — `reconcileUnproven` is dead / inverted (boot reconcilers never raise the hold on failure)

| | |
|---|---|
| **Severity** | **HIGH** |
| **Location** | `ZitroneApp.kt:414–423` |
| **Boundary** | **BLOCKING** (failed wipe / failed completion can present as fresh install within-process) |

**Defect.** The fold that is supposed to turn “reconciler fired but could not prove durability” into `SWEPT_NOT_DURABLE` is:

```421:423:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
                val reconcileUnproven =
                    (burnCompleted || markersCleared) && !imageStore.imageBearingProvenAbsent()
                if (reconcileUnproven) ResidueSweepResult.SWEPT_NOT_DURABLE else sweepResult
```

- `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers` return `true` only after a full success path that already requires proven image-bearing absence (`obliterateLocked` S4 / gate). So `(success) && !provenAbsent` is structurally unreachable.
- On the actual failure path of interest (unlinks landed, `dirSync` not durable → `isSuccess == false`, disk stats clean): both flags are `false`, `sweepOrphanedResidue` returns `NO_MUTATION` (already clean), **hold is published `false`**, `bootRoute` → **ONBOARDING**.

The comment claims re-derivation from “precondition still holds.” The code does not re-check preconditions; it uses success booleans. The boolean return type also cannot distinguish “did not fire” from “fired and unproven,” which the comment itself admits.

**Why it matters.** This is the same failed-but-clean shape the round-6 HIGH described, for the **boot** half of producers 2. Runtime `runBurnWipe` does raise the hold first; the boot reconcilers do not get an equivalent path. WB-3’s “three producers” claim is incomplete in source for producer 2.

**Fix.** Return a tri-state from both reconcilers (same shape as `ResidueSweepResult`), or after each call re-derive:

- if the interrupted-burn signature still holds → hold  
- if success was false **and** image is now proven-absent while pre-state required work → hold  

Do not use `(success) && !absent`.

---

### F2 — `boot-diagnostics.log` survives every burn (lazy residual oracle)

| | |
|---|---|
| **Severity** | **HIGH** |
| **Location** | `BootDiagnostics.kt:41,110`; burn path `ZitroneApp.kt:376–395` (no wipe of diagnostics) |
| **Boundary** | **BLOCKING** when the feature has been used |

**Defect.** Diagnostics writes `filesDir/boot-diagnostics.log` on first `record()`. `burnVault()` never deletes it. A used install can retain a multipage boot log; a never-used fresh install does not create that file until something records.

**Why it matters.** Direct break of post-burn ≡ fresh install. Gate *would* catch it if the instrumented path exercised messaging/boot diagnostics; create+burn alone may not write the file — classic “gate can’t see what the scenario never creates.”

**Fix.** Include diagnostics clear inside the burn obliterate region (and fail the wipe if the file survives a re-stat). Optionally clear on successful account destroy for hygiene.

---

### F3 — `wipeBiometricMaterial()` does not prove Keystore aliases gone

| | |
|---|---|
| **Severity** | **HIGH** |
| **Location** | `ZitroneApp.kt:892–906`; `BiometricVaultKeyCipher.kt:137–147` |
| **Boundary** | **BLOCKING** if aliases remain (post-burn oracle) |

**Defect.** WB-4 / burn path claim: burn **consumes** the boolean and fails closed. Source:

- `deleteAllAliasesExcept` swallows enumeration failures (`return` with no signal).
- `deleteAlias` swallows delete failures.
- No post-condition `!containsAlias` / “no `PREFIX*` remain.”
- `wipeBiometricMaterial` returns `true` unless an exception escapes the try (rare).

Contrast `KeystoreDeviceKeyCipher.deleteKeyMaterial()` which re-checks absence.

**Why it matters.** Orphaned biometric aliases are exactly the “something was here” residue class. Contract text overstates the boolean’s meaning.

**Fix.** After wipe: enumerate aliases; return `false` unless zero `PREFIX*` / `LEGACY` remain (same prove-gone discipline as the device key).

---

### F4 — Gate coverage is narrower than `SECURITY_MODEL` / DoD claims

| | |
|---|---|
| **Severity** | **MEDIUM** |
| **Location** | `BurnByteForByteGateTest.kt:62–71,110–118`; `docs/SECURITY_MODEL.md:572–593` |
| **Boundary** | **DEFERRABLE** for hardening the gate; residual oracles may be **BLOCKING** if present in product |

**Defects in the gate itself:**

| Snapshot half | What it actually compares | Gap |
|---|---|---|
| `files` | path → **length** under `filesDir` | Not content; not `cacheDir` |
| `prefs` | **filenames** under `shared_prefs` only | Not keys/values (`onboarding_done`, tor flags, etc. survive burn by design of device settings and are invisible to the gate) |
| `databases` | filenames | OK if no DBs used |
| Keystore | full alias set | Strong; negative test now names `PREFIX + "gatenegative"` |
| Boot verdict | hold + route | Good for ruling E |
| Notification channels | **not covered** | `messages_v2` created lazily (`MessagingNotifications.kt:67–81`) |
| WorkManager | none in source | N/A |

**Negative test soundness (D):** After the device-key fix, naming the planted alias **does** discriminate “caught my residue” vs “caught unrelated residue.” It would still discriminate after plausible wipe expansions **as long as** the planted alias stays outside the wipe set for that test (`burnObliterate` only + plant). If someone later changes the negative test to call full `burnVault()`, the planted alias would be wiped and the negative test would go vacuous — worth a comment/guard.

**Empty-coverage risk:** Positive path only exercises create+burn. Lazy artifacts never created in the test (diagnostics log after messaging, camera cache, notification channel, biometric enable) can leave production residue while the gate stays green.

**Fix.** Compare prefs **key sets** (or clear device settings keys that encode “used”); snapshot `cacheDir`; optionally notification channels; expand scenarios (enable biometric, write diagnostics, touch cache). Document honestly what filename-only prefs means.

---

### F5 — Burn failure is not UI-uniform with wrong passphrase (timing / `unlocking`)

| | |
|---|---|
| **Severity** | **MEDIUM** |
| **Location** | `MainActivity.kt:935–969, 1001–1007` |
| **Boundary** | **DEFERRABLE** until slot 0 is armed (latent); **BLOCKING for deniability** once burn is reachable |

**Defect.** `Rejected` sets `unlocking = false` and `lockError = UNIFORM_FAILURE` immediately. `Burn` calls `onBurn()` and leaves `unlocking == true` until the wipe finishes and the completion coordinator applies. Failed burn then sets the same string — after a long spinner.

**Why it matters.** WB-1’s message half is shared (`UNIFORM_FAILURE`); the **presentation** half is not (duration / busy state). A coercer watching the UI can distinguish burn-in-progress / burn-failed from a quick wrong passphrase.

**Fix.** Either keep a uniform short busy window for all outcomes, or surface failure only after a minimum constant delay matching reject crypto time (harder). At minimum: do not leave a multi-second exclusive spinner only on the burn path without a matching reject path.

---

### F6 — Stale honesty claims left next to live code / in SECURITY_MODEL

| | |
|---|---|
| **Severity** | **LOW** |
| **Location** | `MainActivity.kt:897–900` (stub claim four lines above real `onBurn`); `docs/SECURITY_MODEL.md:564–568` (“wipe is a fail-closed stub” / “do not describe … working Pucker Burn as shipped”) vs `:570–605` (gate proves wipe) |
| **Boundary** | **DEFERRABLE** (docs/process; not a wipe bug) |

Step 8’s honesty pass fixed one section and left the older “stub” block. Exact failure class the brief warned about.

**Fix.** Rewrite the status paragraph: wipe **wired**, slot 0 **unarmed**, setup UX not shipped.

---

### F7 — WB-7 enumeration omits `vault.dek.tmp`

| | |
|---|---|
| **Severity** | **LOW** |
| **Location** | `BurnReconcilerTriggersTest.kt:78–105` (5 bits = 32 states); image-bearing set includes `dek.tmp` (`VaultImageStore.kt:1352–1356`) |
| **Boundary** | **DEFERRABLE** |

Exclusivity still looks true under manual extension, but the suite’s “proof over the enumerated state space” is incomplete relative to the predicates that mention all image-bearing paths. Non-vacuity guard is real and useful.

**Fix.** Add `dekTmp` as a sixth bit (64 states) or justify why temps are collapsed.

---

## Explicit verdicts A–J

### A — WB-3 one owner, three producers
- **Single field, no discriminator:** holds. Routing only tests `durabilityHold` (`bootRoute` arm at `ZitroneApp.kt:1581`). No consumer needs “which producer.”
- **Producer 3 (runtime burn):** **closed** via `runBurnWipe` raise-before-obliterate (`ZitroneApp.kt:1493–1500`, `BurnDurabilityHoldTest`).
- **Producer 2 (boot reconcilers):** **not closed** — F1. Claim “a reconciler that mutated without proving durability raises the hold” is **false** for the failed-but-clean path.
- **Fourth producer hunt:** No additional mutator should publish into this field and does not (account-delete uses confirmed marker → `DELETE_INCOMPLETE`, not onboarding). Cache clear is best-effort hygiene, not a hold producer. **No missing fourth producer** beyond the broken fold for producer 2.

### B — `destroy()` / `obliterateLocked` deviations
1. **Keys-first:** Accept. Crash continuity for account-delete is the confirmed marker + idempotent `destroy`, not unlink order. For burn, keys-first yields cryptographically dead intermediate state completed by `completeInterruptedBurn`. Fallback `keysFirst` parameter not required.
2. **S4 = `Files.notExists` / proven absence:** Accept. Fail-closed; closes the old `exists()` hole that allowed `{image survives, confirmed absent}` through destroy.  
3. **MainActivity post-destroy defence-in-depth (`MainActivity.kt:1263–1293`):** **Keep.** Routing fail-closed on unproven absence must not be deleted as dead code because S4 currently makes the bad state unreachable. Defence in depth is correct here.

### C — “Exists only if the feature was used”
| Artifact | Lazy? | Wiped by burn? | Oracle? |
|---|---|---|---|
| `zitrone_vault_device_key` | Yes (`wrapDek`) | Yes (`deleteKeyMaterial`) | Fixed for post-burn |
| Biometric `PREFIX*` aliases | Yes (enable) | Intended yes; prove-gone weak (F3) | Residual risk |
| `_androidx_security_master_key_` | Startup | Not wiped (correct) | Not an oracle |
| Vault files / markers | On use | Yes via obliterate | OK if durable |
| `boot-diagnostics.log` | On first `record` | **No (F2)** | **Yes** |
| `cacheDir` (camera, dropshare) | On use | Not in burn; boot `retryPlaintextCacheClearIfNoVault` only if proven absent | Window until next cold start |
| Notification channel `messages_v2` | On first notify setup | No | Mild (OS channel list) |
| Prefs keys (`onboarding_done`, etc.) | On settings use | Device settings kept | Forensic “app configured,” not vault-specific |
| WorkManager job names | None found | — | — |

Device-key fix is real. The class is **not** closed.

### D — Gate soundness
- Negative test **now names its artifact** — good; would still discriminate after further wipe fixes if the plant stays outside that test’s wipe.
- Prefs/filename-only and unexercised lazy paths can make the **positive** test pass while proving less than `SECURITY_MODEL` implies (F4).
- Boot-decision half is a real strengthening.

### E — WB-1 uniform failure + hold
- **Message:** Failed burn → `VaultUnlockRouter.UNIFORM_FAILURE` (`MainActivity.kt:968`), same as reject.
- **Hold:** Raised before mutation on runtime burn (`runBurnWipe`); survives throw (`BurnDurabilityHoldTest`).
- **Gap:** UI busy-state timing (F5). Message+hold alone are not the full presentation surface.

### F — WB-2 NonCancellable
- Wipe runs on **process** `AppContainer.scope` (`SupervisorJob` + Default), inside `withContext(NonCancellable + Dispatchers.IO)` (`MainActivity.kt:940–943`).
- Activity/composition cancellation does not cancel process scope; `NonCancellable` is correct defence-in-depth.
- Process kill is out of band (expected). **Holds.**

### G — WB-7 boot mutator ordering
- Triggers as coded are pairwise exclusive on the 32-state grid the test materializes; non-vacuity asserts all three fire somewhere.
- Best-effort `false` is **not** correctly re-derived into a hold (F1) — ordering proof ≠ durability proof.
- Incomplete bit for `dek.tmp` (F7).

### H — `vaultExists` initial `false`
- Initializer is no longer a Main-thread disk read (`MainActivity.kt:642`).
- Route stays `Splash` until `splashFinished && bootDone`; assignment at `:661` before leaving Splash.
- Consumers: `biometricUnlockAvailable`, lemon-drop veil. Lock screen is not composed on Splash, so no unlock routing off pre-recon state.
- Edge: composition with **already-live session** seeds `ChatList` and may leave `vaultExists == false` until a later disk derivation — pre-existing style issue, not Splash regression. **Splash claim holds.**

### I — Unit suite (this environment)
| | Claim | **This run** |
|---|---|---|
| Total | 524 | **524** |
| Passed | 521 | **521** |
| Failures | 0 | **0** |
| Skipped | 3 | **3** |
| Result | green | **BUILD SUCCESSFUL** |

Note: first attempt failed 177× with JNA `Permission denied` extracting `libjnidispatch.so` under default tmp; re-run with `JAVA_TOOL_OPTIONS=-Djna.tmpdir=…` / writable tmp under the worktree. Instrumented gate **not** run here; CI run 30171035588 treated as unauthenticated claim.

### J — Other
- Commit “round-6 HIGH closed structurally” is **overstated** for the boot-reconciler fold (F1).
- SECURITY_MODEL / MainActivity stub prose overstates “not shipped” / understates wipe (F6).

---

## Summary table

| ID | Sev | BLOCKING? | One-line |
|---|---|---|---|
| F1 | HIGH | **YES** | Boot reconciler durability fold never raises hold on failed-but-clean |
| F2 | HIGH | **YES** | `boot-diagnostics.log` not wiped |
| F3 | HIGH | **YES** if residual | Biometric wipe boolean does not prove aliases gone |
| F4 | MED | Gate: defer; residue: case-by-case | Gate coverage / prefs-name-only / lazy scenarios |
| F5 | MED | When armed | Burn spinner / unlocking tell vs reject |
| F6 | LOW | No | Stale stub claims in docs + comment |
| F7 | LOW | No | 32-state proof omits `dek.tmp` |

---

## READY TO MERGE?

**No.**

Minimum before merge:

1. Fix F1 (reconciler durability → hold) with a test that forces `dirSync = NOT_DURABLE` on `completeInterruptedBurn` and asserts `bootRoute`/hold withhold onboarding.  
2. Wipe (or durable-delete) `boot-diagnostics.log` on burn and prove absence.  
3. Make biometric wipe prove alias absence (F3).

F4–F7 can track after those three if explicitly accepted as residuals; F2/F1 are feature-purpose failures, not hardening layers.

I would re-review a delta that only addresses F1–F3 without reopening B’s S4 / MainActivity defence-in-depth.
