### QUESTION 1 — SEVERITY
**Verdict:** BLOCKING
**Source:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt` (Reconcilers `completeInterruptedBurn` at ~L1357 and `reconcileOrphanedBurnMarkers` at ~L1390) and `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` (`burnVault` at ~L384 and `raiseDurabilityHold` at ~L362).
**Reasoning:** The boundary is explicit: "Anything that breaks post-burn ≡ fresh install is NOT a hardening layer — it is the feature failing at its purpose, and it BLOCKS." If a cleanup fails *after* `burnObliterate()` succeeds, the hold remains raised in RAM but dies with the process. At next boot, both boot reconcilers return `NO_MUTATION` because they strictly check for image-bearing files or delete markers—which are already gone. The `durabilityHold` evaluates to false, and the app presents as a fresh install (Onboarding). However, it still carries discoverable vault-use residue (plaintext caches, specific preferences, `BootDiagnostics`). To a forensic adversary, this residue proves a vault existed and was burned, completely defeating plausible deniability. The argument that this is "DEFERRABLE" because it is a pre-existing property of RAM-only holds is invalid; provenance does not excuse shipping a cryptographically broken security boundary. 

### QUESTION 2 — THE FIX AND CONSTRAINTS
**(a) Is the oracle objection FATAL to the durable-marker fix?**
**Verdict:** FATAL.
**Reasoning:** Writing a "burn in progress" marker *before* the first mutation creates a catastrophic failure state. If the app crashes immediately after writing the marker but before `burnObliterate()` executes, the device retains a fully intact, unlockable vault *and* a marker proving the duress password was entered. This is the exact discoverable oracle the design forbids, rendering the durable-marker fix unacceptable.

**(b) Is there a MARKER-FREE disk signature for "obliterate succeeded but later cleanup did not"?**
**Verdict:** Yes.
**Source:** `ZitroneApp.kt` (enumerated cleanups in `burnVault` at ~L416 and `wipeVaultUsePreferences` at ~L1019).
**Reasoning:** The residue itself *is* the signature. A fresh install does not have a `BootDiagnostics` file (`app.filesDir/diagnostics`), a plaintext cache tree (`app.cacheDir`), specific lazy preference files (`zitrone_signal_store.xml`, `zitrone_auth.xml`, `zitrone_contacts.xml`), or a Keystore device-key alias (which is created lazily). The signature is simply `{vault.bin PROVEN ABSENT ∧ residue PRESENT}`. A boot sweep can safely observe this state and wipe the orphaned residue without needing an explicit marker, exactly as `sweepOrphanedResidue` already handles orphaned DEKs.

**(d) Is there a third option neither reviewer proposed?**
**Verdict:** Yes: Reorder the non-cryptographic cleanups to run *before* `burnObliterate()`.
**Reasoning:** If `bootDiagnostics.erase()`, `deleteTreeDurably(app.cacheDir)`, and `wipeVaultUsePreferences()` are executed *first*, a crash during this phase leaves the vault entirely intact. Plausible deniability is preserved because the device just looks like a normal vault with cleared caches/preferences (which the OS can do natively). This eliminates the dangerous window where the image is gone but non-cryptographic residue remains. (Note: `deviceKeyCipher` and `biometricMaterial` must still be wiped *after* the image, as deleting them first would brick the vault and create a different oracle).

### QUESTION 3 — PROCESS DEATH CLAIM
**Verdict:** Both reviewers are partly right; the in-tree prose is an overclaim regarding OS-level I/O.
**Source:** `ZitroneApp.kt` (Process death documentation in `burnVault` at ~L378).
**Reasoning:** `Process.killProcess()` abruptly terminates all userspace queues (e.g., `SharedPreferencesImpl`'s `QueuedWork`), fulfilling the author's claim that it prevents *future* userspace work and stops pending `apply()` writes from initiating I/O. It guarantees that no lazily initialized component can recreate a file *after* the wipe. However, the reviewer is correct that it cannot roll back or stop a write from landing if a background thread has *already* executed the kernel system call (`write()`/`fsync()`). It is a "deterministic drain" of the userspace queue, but not of the kernel's block layer.

***

**Summary:** BLOCKING
