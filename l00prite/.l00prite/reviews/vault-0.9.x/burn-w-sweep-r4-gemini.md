Warning: True color (24-bit) support not detected. Using a terminal with true color enabled will result in a better visual experience.
Ripgrep is not available. Falling back to GrepTool.
Error executing tool invoke_agent: Tool execution denied by policy. You are in Plan Mode with access to read-only tools. Execution of scripts (including those from skills) is blocked.
(node:47320) [DEP0190] DeprecationWarning: Passing args to a child process with shell option true can lead to security vulnerabilities, as the arguments are not escaped, only concatenated.
(Use `node --trace-deprecation ...` to show where the warning was created)
This is NOT READY TO MERGE. The delta introduced a new UI-state race, left a rogue routing authority in the account-delete path, and exposed a critical sequencing flaw where an incomplete account deletion presents as a fresh install.

Here are the independent findings from source.

### Findings

**1. CRITICAL: Premature Marker Deletion & Fail-Open Routing in Account Delete**
* **File:line:** `VaultImageStore.kt:1290` (inside `destroyVaultForAccountDeletion`) and `MainActivity.kt:onDeleteAccount finally block`
* **Defect:** `destroyVaultForAccountDeletion` calls `deleteIntentFile.delete()` and `serverDeletedFile.delete()` **before** it evaluates `if (!imageBearingFilesProvenAbsent()) throw DestroyFailed()`. If `obliterateLocked()` fails to unlink a file (e.g., a surviving `vault.dek.tmp`), the markers are permanently destroyed before the failure is caught. Compounding this, the `onDeleteAccount` finally block in `MainActivity` routes based on `!container.hasVault()` (which only checks `vault.bin`) rather than `container.vaultProvenAbsent()`.
* **Why it matters:** If an account deletion partially fails (e.g., `vault.bin` deleted, but `vault.dek` survives), `destroyVaultForAccountDeletion` throws `DestroyFailed`. The finally block runs, sees `serverDeleteConfirmed()` is `false` (because the marker was prematurely deleted) and `hasVault()` is `false` (because `bin` is gone). It evaluates `if (!vaultExists && !container.serverDeleteConfirmed())` as `true` and routes to `Route.Onboarding`. **A partial wipe presents as a complete success**, violating the core fail-closed invariant.
* **Concrete fix:** In `VaultImageStore.kt`, move the marker deletions strictly *after* the `throw DestroyFailed()` check. In `MainActivity.kt`, update the `onDeleteAccount` finally block to use `vaultProvenAbsent()` instead of `!vaultExists`.

**2. HIGH: 00f65b6 Introduced a State-Stomping UI Race (Second Routing Authority)**
* **File:line:** `MainActivity.kt:LaunchedEffect(Unit)` (the boot fallback effect calling `bootReconciled.first { it }`)
* **Defect:** When 00f65b6 moved `legacyImage` detection into Splash, it forgot to update the boot fallback effect. That effect calls `bootRoute` without passing `legacyImage` (defaulting to `false`) and unconditionally executes `vaultExists = container.hasVault()`.
* **Why it matters:** On a legacy image, Splash correctly computes `vaultExists = false` and routes to `Onboarding`. But the fallback effect runs immediately after (or concurrently) and unconditionally stomps `vaultExists` back to `true` (since `hasVault()` is true for legacy images). This corrupted state makes `biometricUnlockAvailable` true, which enables the `LemonDropVeil.Locked` CTA to render over the Onboarding screen. If a user taps it, they are routed to `Route.Locked` for a legacy image, trapping them in an unlock/throw loop.
* **Concrete fix:** Remove the redundant `vaultExists` and `route` assignments from the boot fallback effect, or force it to compute and pass `legacyImage`.

**3. MEDIUM: Session Logout Reconciler Ignores Legacy Image (Third Routing Authority)**
* **File:line:** `MainActivity.kt:LaunchedEffect` (the `session.collect` observer)
* **Defect:** When `session` becomes null, this effect derives the route using `bootRoute` but omits `legacyImage`.
* **Why it matters:** While practically unexploitable (a legacy image cannot generate a live session to log out of), this violates the single-authority rule. It is a third code path deciding the boot route with weaker/incomplete inputs.

---

### Explicit Verdicts

**A. IS THERE STILL MORE THAN ONE ROUTING AUTHORITY?**
**Yes.** There are three rogue authorities remaining in `MainActivity.kt` that bypass or misuse the unified `bootRoute`:
1. The **Boot Fallback effect** (uses `residueSweepHold` but omits `legacyImage`, unconditionally stomps `vaultExists`).
2. The **Session Logout effect** (uses `residueSweepHold` but omits `legacyImage`).
3. The **`onDeleteAccount` finally block** (bypasses `bootRoute` entirely, ignores `residueSweepHold`, uses `!hasVault()` instead of `vaultProvenAbsent()`).

**B. `bootRoute` precedence WITH the new `legacyImage` arm:**
**Correct.** Confirmed-before-legacy-before-present is safe. A legacy image *cannot* mask a state that should be LOCKED due to residue sweep, because if a legacy image is present, `binFile` exists. `sweepOrphanedResidue` Gate 1 sees `binFile` and immediately returns `NO_MUTATION`, meaning `residueSweepHold` is correctly guaranteed to be `false`.

**C. `runBootReconcile`:**
**Correct.** It enforces once-only via CAS and the initial verdict is fail-closed. Rethrowing `CancellationException` from `sweep()` **does not** skip `publish`. The `finally` block is attached to the outer `try` (outside `withContext`), and Kotlin guarantees `finally` runs during cancellation propagation. The injected dispatcher does not break production behavior.

**D. Did 00f65b6 introduce ANY new defect?**
**Yes.** (See Finding 2). By removing the standalone legacy effect but leaving the Boot Fallback effect un-updated, it created a state-stomping race where `vaultExists` is forced back to `true` for a legacy image. 

**E. THE SWEEP GATE, re-verified independently:**
**Safe.** Removing the `delete-intent` clause was correct. If a duress burn interrupts an account delete, the `bin` is already gone but an intent remains. Sweeping the residue (DEK, temps) is completely safe because the vault is already dead. `reconcileOrphanedBurnMarkers` runs next and safely removes the outstanding intent.

**F. Re-verify the CUMULATIVE unit:**
1. **destroy() equivalence:** Pass.
2. **Marker clear STRICTLY after unlinks proven durable: FAIL (CRITICAL).** Markers are cleared before `imageBearingFilesProvenAbsent()` is evaluated.
3. **All boot healers as ONE system:** Pass.
4. **WRITER/READER invariants: FAIL (HIGH).** The UI layer contains rogue writers re-deriving weaker verdicts.
5. **REACHABILITY:** Pass.
6. **Concurrency/lifecycle end-to-end:** Pass.
7. **Fail-closed: FAIL (CRITICAL).** The `onDeleteAccount` completion path routes to `Onboarding` on a partial wipe.

**G. `File.exists()` verify INSIDE `obliterateLocked`:**
Agreed, deliberately out of scope as it is inherited behavior.

**H. Test quality:**
The tests pass **vacuously** for the UI routing invariants. `BootReconcileOwnerTest` successfully covers the host-JVM lifecycle of the `runBootReconcile` contract. However, the failure shapes we are finding (state stomping, ignoring `legacyImage`, rogue routing logic) exist entirely inside Compose `LaunchedEffect` blocks and UI-layer coroutines in `MainActivity.kt`. These are fundamentally unexercised by the host-JVM unit tests.
