• This is a design advisory question — no code changes needed, just expert answers. I should answer directly with detailed guidance. The user wants Q0 at length, then Q1–Q5. No tools needed really; this is pure advisory. Maybe check the working directory files? There are files like `queued-write-resurrection-test.kt` and `src-diag.txt` that might be relevant to Q5 and Defect 1. The user pasted source verbatim, so I can answer from that. But reading `src-diag.txt` and `queued-write-resurrection-test.kt` could ground Q1 and Q5. It's cheap to peek. Let me read them quickly.

  Actually, the user said "I have staged a test that stages the race" — that's likely `queued-write-resurrection-test.kt`. Reading it would let me give concrete feedback on Q5. And `src-diag.txt` likely the BootDiagnostics source. Worth a quick read.

  Then write a long, concrete answer. Language: English.

  Let me read both files.

• I'll look at the two staged files first so my answers on the diagnostics wipe and the queued-write race are grounded in your actual code.

• Now write the advisory answer. This is a pure design-advice response — no file changes requested. The user wants Q0 at length, then Q1–Q5, with Kotlin sketches, disagreement where warranted, and six-month-regret flags.

  Let me think through each carefully as a senior Android security engineer.

  ## Q0 — structural enforcement of both properties

  The user's instinct: `sealed interface WipeOutcome { ProvenDurable; NotDurable; Failed }`. Critique: that tri-state is slightly wrong. What does a burn do with `NotDurable`? In a burn context, NotDurable == Failed — the hold stays raised either way. So a tri-state at the burn boundary collapses to binary and invites a call site to treat NotDurable as success. The better shape: make the type carry the *evidence*, and make durability non-optional in the type. Options:

  1. **Result type with proof payload**: `WipeOutcome` where success requires a `DurabilityProof` — but proof objects can be forged by any call site, so it's ceremony unless constructing the proof is privileged.

  2. **Combinator owns the steps** — I think this is right, and the user suggested it themselves. A `WipeStep` runner where the only way to register a cleanup in the burn is through a builder that forces you to declare the durability mechanism. Key idea: the *type system can't prove fsync happened* — only code structure can. So make the durable-delete primitive the ONLY primitive available inside the burn scope: a `BurnScope` receiver (context receiver / DSL marker) exposing `deleteFileDurably(f)`, `deleteTreeDurably(dir)`, `clearPrefsDurably(name)`, `destroyKeystoreAlias(alias)`, and NO raw File/delete access. Kotlin can't prevent a call site from just calling `File.delete()` inside the lambda — nothing can, short of detekt — so you pair it with a lint/arch-test rule. Be honest about that.

  3. Handling "durability not applicable" (Keystore): model it as a *different kind* of durability proof, not an exemption. Keystore keys are durable by construction (KeyStore mutations are transactional and persisted by keystore2 — KeyStore.deleteEntry is synchronously durable through the keystore daemon). So the taxonomy isn't "durable / not durable / failed", it's "durability mechanism": `FsyncDirectory(path)`, `KeystoreTransactional`, `CommitThenFsync(prefs)`. Each step returns `StepResult.Proven(mechanism)` or throws. "Not applicable" doesn't exist — every step must name its mechanism, and a wrong mechanism (claiming FsyncDirectory without fsyncing) is the same lie as before, but the combinator makes the mechanism the *implementation*, not the claim: `destroyKeystoreAlias` is written once, reviewed once, and call sites can't re-implement it.

  So my recommendation: **not** a tri-state result; a small closed set of primitives behind a `@DslMarker BurnScope`, each primitive returns `Unit` and throws `DestroyFailed` on any failure, each is implemented once with its durability mechanism in one `wipe/` file. The burn becomes a declarative list. The registry of steps is also enumerable by the test gate — the gate can reflect over the same list, keeping test and production from drifting (ties into Q3's stale-duplication concern).

  Cost: new cleanups must be added as a primitive or use an existing one — friction is the point. Routing around: mitigated by (a) primitives cover the real cases so routing around is more work than using them, (b) one arch-test/detekt rule: `File.delete|deleteRecursively|SharedPreferences.edit` forbidden outside `wipe/` package — trivially enforceable with a custom detekt rule or a simple grep-based unit test on the source tree. Also: make the list of steps data, so the count of steps is assertable ("the burn runs N registered steps") and the commit-message enumeration problem becomes a compile/run-time enumeration.

  Also worth stating plainly: no type can make "forgot to add a cleanup" unrepresentable — both defects were actually the second axis, but the meta-problem generalizes: the third axis (e.g., "does it clear in-memory state" — defect 1a!) will arrive the same way. Actually defect 1 has BOTH: didn't fsync AND didn't clear memory. So the registry should also support per-step invariants the gate asserts (postconditions). Consider: each step declares `verify: () -> Boolean` — a postcondition checked within the burn before lowering the hold AND reusable by the gate. That turns "reviewer noticed" into "the step carries its own check."

  Sketch:

  ```kotlin
  sealed interface Durability {
      val description: String
      class FsyncedDir(val dir: File) : Durability
      object KeystoreTransactional : Durability
      class PrefsCommitAndUnlink(val storeName: String) : Durability
  }

  class BurnStep(
      val name: String,
      val durability: Durability,
      val run: () -> Unit,        // throws DestroyFailed on failure
      val verify: () -> Boolean,  // postcondition, also used by the gate
  )
  ```

  And a registry `BurnPlan.steps: List<BurnStep>`; `runBurnWipe` iterates, runs, verifies, and only then lowers the hold. The gate iterates the same `BurnPlan` to build its baseline assertions (Q3).

  On "unrepresentable": the strongest version — `deleteFileDurably` does not return the file handle, doesn't return Boolean; failure paths all throw. There's no way to express "deleted, didn't fsync, returned success" *using the primitive*. That's achievable. The escape hatch is raw File APIs — close it with the arch rule, and say plainly that this is a social+lint boundary, not a type boundary.

  ## Q1 — one function or two?

  Yes, one function. `clearProven` and `clear` having divergent semantics four lines apart is the defect generator. Factor: single `erase(): EraseOutcome` (or throws) that does the complete, proven, durable erase; UI wrapper calls it and logs/swallows. The UI action "must not throw" is satisfied by the wrapper catching — but honestly: if the erase fails, the UI should show failure, not silently succeed; swallowing in `clear()` today is its own small lie (the screen shows an empty log that isn't empty on disk... actually it clears memory regardless).

  Order of operations (lock held, record can race):
  1. Clear memory first (`_entries.value = emptyList(); loaded = true`) — this kills the resurrection source: any record() racing *after* this point appends to an empty list and writes only post-burn lines. Order matters: if you delete the file first, a racing record() holding stale memory rewrites it. Memory-first is the key insight the current code got backwards (clearProven never touched memory).
  2. Truncate-or-delete the file: `Files.delete(path)` (use the throwing variant, catch and classify) — note writeText+delete double-write is pointless if you delete; truncate-then-delete matters when delete might fail *and you proceed anyway* (fail-open UI path) — for the burn, if delete fails you throw, so truncate-first is redundant **unless** you care about the window where the file still exists with content while you attempt other things. Actually there's an argument: on the fail-closed path you keep the hold raised, so residue doesn't matter (next boot locks). On the UI path (clear()), truncate-first is cheap defense — keep it there only, i.e., in the shared implementation: truncate, then delete, then fsync dir, then verify notExists. Cost of truncate is one write syscall; keep it, it makes the UI fail-open path safer and keeps ONE code path.
  3. fsync the parent directory (filesDir) — open dir with O_RDONLY, fsync fd. In Kotlin/JVM: `FileChannel.open(dir.toPath(), READ)` then `force(true)`? Directory fsync via NIO on Linux works opening the directory as a FileChannel read-only and calling force — actually `FileChannel.force` on a directory fd: works on Linux (fsync on directory fd is valid). Yes, opening a directory with `FileChannel.open(path, StandardOpenOption.READ)` succeeds on Linux and `force()` issues fsync. That's the standard trick.
  4. Verify `Files.notExists`.

  Note on ordering vs record race: do memory-clear while holding lock, and record() also holds lock, so once erased under lock, a later record() writes only new lines — but those new lines are POST-burn; is a new boot-diagnostics line after a burn a fresh-install distinguisher? No — fresh install also writes boot diagnostics on first boot. Post-burn process continuing to record is same-process residue; the guarantee is about post-burn state vs fresh install... in the same process the app is still running with burned state. Hmm — the burn presumably precedes showing onboarding in-process. A new record() after burn recreating the file: fresh install creates that file on boot too, so a *new* line is indistinguishable. The defect was resurrecting PRE-burn lines. Memory-first fixes that.

  Also: `record()` swallows disk errors — fine, but after erase, `_entries` empty + loaded=true is authoritative; consistent.

  One function sketch:

  ```kotlin
  fun erase(): Boolean = synchronized(lock) {
      _entries.value = emptyList()
      loaded = true                       // memory is now authoritative; a racing record() appends to empty
      runCatching { file.writeText("") }  // belt-and-braces for the fail-open UI path
      val deleted = runCatching {
          Files.deleteIfExists(file.toPath())
          fsyncDirectory(file.parentFile!!)
      }.getOrDefault(false)
      deleted && Files.notExists(file.toPath())
  }
  fun clear() { if (!erase()) Log.w(...) }  // UI wrapper
  ```

  Wait — should memory clear happen even if disk fails? For burn: fail-closed anyway. For UI: yes, screen should reflect empty. Keep memory-first unconditionally.

  Also mention: `record()`'s `runCatching { file.writeText(...) }` swallowing means after a burn with hold raised... fine.

  ## Q2 — durable recursive delete on Android

  Facts: unlink of a file is recorded in the parent directory's metadata; to make a set of deletions durable you must fsync **every directory whose entries you modified** — i.e., every directory you removed children from, including the root cacheDir and every subdirectory you emptied/removed. fsyncing only cacheDir is NOT sufficient: a removed subdirectory `cacheDir/a/` had entries deleted from it; the removal of `a` itself is recorded in cacheDir, but removal of `a/b` is recorded in `a`. If you delete a's contents then delete a, and only fsync cacheDir, a journal replay could bring back `a` containing `b`. In practice on ext4 with ordered journaling the metadata journal covers directory updates and fsync of one dir forces the journal commit that includes prior metadata operations... careful. ext4 fsync on a directory fd commits the journal transaction containing that directory's updates; earlier transactions are committed in order, so fsync of the last-modified directory tends to force commit of all prior journal transactions — this is why "fsync one dir" often appears to work on ext4. But f2fs semantics differ (its own checkpoint/roll-forward), and relying on journal ordering across directories is exactly the kind of platform-detail reasoning this project keeps getting bitten by (their own words in Q5). So the honest answer: **fsync every directory you removed entries from, post-order (children before parents)** — because removing a subdirectory entry from its parent must be durable *after* the subdirectory's own state was made durable. Actually since you're deleting the subdirectory itself, do you need to fsync it before rmdir? Once you rmdir it, you can't fsync it (no path); and its pending state is irrelevant once the rmdir is durable via parent fsync... but ordering: rmdir recorded in parent's journal, durability achieved by parent fsync. The subdirectory's own entry-deletions don't need independent durability if the directory itself is deleted. So: delete contents recursively, delete the dir itself, fsync the parent — bottom-up. Simplest correct structure: `Files.walkFileTree` with postVisitDirectory = { delete dir; } then fsync every parent... Cleaner: recursive function:

  ```kotlin
  fun deleteTreeDurably(dir: File) {
      dir.listFiles()?.forEach { child ->
          if (child.isDirectory) deleteTreeDurably(child)
          else if (!child.delete()) throw DestroyFailed(child)
      }
      if (!dir.delete()) throw DestroyFailed(dir)   // or keep root
      fsyncDirectory(dir.parentFile!!)              // parent recorded the rmdir/unlinks
  }
  ```

  Note fsyncDirectory(parent) per directory — the root call fsyncs cacheDir's parent... no wait, we keep cacheDir itself (fresh install has cacheDir existing but empty? Actually cacheDir exists on fresh install — created on demand. Deleting cacheDir itself vs emptying: match fresh install — keep the dir, empty it, then fsync cacheDir itself after emptying it). So: for the root, delete children (recursively deleting subdirs), then fsync cacheDir. Each recursive level fsyncs the parent of the removed subdir (= the dir being processed). Each directory gets exactly one fsync after its children are gone. That's O(dirs) fsyncs, correct and honest.

  Fail-closed on unreadable subdir: `listFiles()` returning null → throw DestroyFailed. Do NOT skip — an unreadable subdir may hold the plaintext you're there to destroy; the hold stays raised, next boot locks. Correct.

  Unbounded/slow: cache can be large (decrypted attachments). Honest bound: fsync count is per-directory not per-file, so cost is dominated by unlink syscalls, which you can't avoid — you must delete every file anyway. Options: (a) run on IO dispatcher with the hold raised — the burn is already a stop-the-world operation; (b) rename-then-delete trick: `cacheDir.renameTo(graveyard)` (atomic), fsync parent, recreate empty cacheDir, fsync parent, then delete the graveyard tree — the observable state is clean immediately and the slow deletion happens with the sensitive names already unlinked... but durability of the deletion still requires the full walk; the rename trick bounds the *blocking* window, not the total work, and plaintext persists in the graveyard until the walk finishes — that CONTRADICTS the burn's purpose (plaintext must be gone when the burn reports success, not eventually). So: don't do the graveyard trick for a duress wipe; do the full walk, fail-closed, hold raised. If wall-clock is the concern, cap the cache size in normal operation (you should anyway — unbounded decrypted plaintext cache is its own problem), which bounds the burn. That's the six-month-regret flag: the real fix is bounding what's in cacheDir, not engineering a faster wipe.

  Does cache durability actually matter? The "OS evicts it anyway" argument is wrong for the threat model: eviction is the OS's choice, presence is correlated with use, and the guarantee is byte-for-byte vs fresh install — a replay-restored plaintext file is the worst possible distinguisher (it IS vault content). fsync it. The narrow-the-claim alternative (exclude cacheDir from the gate) is dishonest here precisely because cache holds *decrypted vault content*, unlike channels (Q4).

  ## Q3 — teardown

  User instinct is right: unconditional idempotent cleanup + independent baseline assertion. Where to assert: `@Before`, not `@After` — assert at the point of use. An `@After` assertion attributes failure to the wrong test (test N's residue fails test N's teardown, but the residue may have been left by test N-1... actually @After of test N failing is fine attribution) — the real problem: a *passing* @After doesn't protect test N+1's snapshot taken in its @Before. Assert in @Before: "baseline verified before I snapshot it." Do cleanup in @After (leave things tidy) AND verify in @Before (trust nothing). Both. The @After cleanup is best-effort hygiene; the @Before assertion is the gate. Never let a comparison run over an unverified baseline — right.

  Avoid duplicating the gate's comparison logic: derive both from ONE source — the snapshot manifest. The gate already enumerates what it snapshots (files, shared_prefs, aliases, databases, cacheDir). Write `Baseline.assertReset(snapshot)` that takes the *same* snapshot function used for fresh-install and asserts each entry is absent/empty/default. Single snapshotter, two consumers: the equivalence comparison and the baseline assertion. If the manifest grows, both grow. That kills the stale-duplication failure mode. And per Q0: if the burn's step registry exposes each step's `verify()`, the baseline assertion can iterate the same verifies — one enumeration, three consumers (burn, gate, baseline).

  Per-test process isolation: not worth the wall clock IF the baseline assertion is strong. But note the limit: same-process residue that your snapshot doesn't cover (in-memory singletons, e.g. BootDiagnostics._entries!) is exactly defect-1-shaped. The baseline assertion must include in-memory state (session null, flows empty) or you need process death. Cheaper middle ground: `InstrumentationRegistry` can't kill the process per test cheaply... You can restart the process per *class* or use orchestrator (`Android Test Orchestrator` runs each test in its own process — `clearPackageData` option even wipes app data per test!). Honest answer: Orchestrator with clearPackageData gives you exactly "fresh install per test" and makes the whole baseline question moot — but it costs wall clock and removes the thing you're testing (burn vs fresh in the SAME environment)... no — the comparison is snapshot(burn) vs snapshot(fresh); orchestrator isolates tests from each other, the gate test itself still does both snapshots within its own process. Recommend: enable Orchestrator for the suite if wall clock tolerable; regardless, keep the asserted baseline because orchestrator doesn't protect a single test's internal two snapshots. Flag: orchestrator + `clearPackageData` between tests would have prevented defect 3 entirely; the @Before assertion is the cheap 90%.

  ## Q4 — channels

  Threat reasoning: the app is the vault; its mere installation discloses vault possession. Channel importance/sound changes disclose "the user interacted with notification settings of this app" — that's app-use metadata, not vault-use metadata, and installation already gives the adversary app-presence. BUT the guarantee as stated is "post-burn state indistinguishable from fresh install" — a user who set the channel to silent, burns, and later the device is inspected: channel is non-default → proves post-install modification ≠ fresh install. Does that disclose vault *use*? It discloses the app was *used beyond installation*. Fresh installs that were opened once also create... onCreate creates channel at default. A modified channel proves a human changed settings — evidence of engagement, weak but non-zero, and it IS a distinguisher from fresh install. Given the project's own standard ("anything that breaks indistinguishability is blocking"), option (a) — reset channels in the burn and compare them — is the consistent choice. Resetting channels: you can't reset importance programmatically; you CAN delete the channel (`deleteNotificationChannel`) and recreate it with defaults in the same burn (fresh install recreates on next onCreate anyway — deletion alone converges to fresh-install state since fresh install = no channel until first onCreate... actually a fresh install that has never launched has no channel; a fresh install that launched once has default channel. Post-burn: app has launched, onCreate will recreate default channel on next boot. So deleting all app channels in the burn converges exactly: next boot recreates defaults, matching a fresh install's post-first-boot state). And user settings (importance) are wiped by delete. So: delete channels in burn, add channel state to the gate snapshot (NotificationManager.getNotificationChannels — importance, sound, vibration), delete the false claim either way.

  Also flag: same class of omission likely exists for other NotificationManager-adjacent or system-held state: app-ops, battery optimizations, default-app roles, usage stats (`UsageStatsManager` — system-held, can't wipe, and DOES record app usage — if the threat model includes a forensic adversary with device access, usage stats disclose app use regardless; the project should state the boundary: indistinguishability w.r.t. app-local state, because system-held state (usage stats, install timestamp, PackageInstaller records) is unwipeable and already discloses installation/use). That's a six-month regret: the gate's claim must be scoped to app-controlled state or it will keep generating false-claim defects. Channel reset is still worth it because it's app-controlled.

  ## Q5 — apply() vs commit() ordering

  Their reasoning is actually correct on AOSP: SharedPreferencesImpl.commit() enqueues a disk write behind previously queued writes for the same store and awaits its own; QueuedWork processes in order per... commit posts the write to QueuedWork and waits; apply-posted writes go to the same handler queue (single-threaded serialized executor in modern AOSP — QueuedWork uses a single HandlerThread), so FIFO ordering holds: commit's write is processed after earlier applies to any store, and commit waits for completion. So after commit() returns, no earlier apply() is pending. BUT: the hole is not ordering of old writes — it's *new* apply() calls racing from other threads AFTER the commit and BEFORE/AFTER the unlink. wipeVaultUsePreferences commits then unlinks; a concurrent put() with apply() between commit and unlink rewrites the file — then unlink removes it, then... the apply write is to the unlinked fd? SharedPreferences writes to a temp file then renames over the target — a rename after unlink RECREATES the path. So the resurrection window is: commit returns → unlink → but a *new* apply queued between commit and the write completing renames mBackup→... hmm. The staged test queues the write BEFORE the burn, which the commit-ordering argument covers. The uncovered race is a write initiated DURING the burn. Their test stages the pre-burn case; worth noting the in-burn case is the harder one and is closed only by the hold (no new writes should happen post-burn because... the burn runs while app is active; SettingsRepository.put could race). Real fix that removes the platform dependency: after commit+unlink, recreate nothing; the strongest deterministic approach: stop using SharedPreferences for vault-use markers, or serialize all preference writes through a single-threaded executor the burn can drain and then *close the gate* (a `writesSealed` flag set before wipe; all puts check it). Deterministic drain from a test: `QueuedWork.waitToFinish()` is hidden; alternatives — reflection is blocklisted on API 34 for @hide? QueuedWork.waitToFinish is @UnsupportedAppUsage... on API 34 many greylist entries moved to blocklist. Options: (1) reflection try/catch — fragile; (2) the deterministic production-side fix: don't rely on it — make the burn unlink, then fsync the shared_prefs DIRECTORY, and make resurrection *harmless* by having the gate wait... no. Better: make every lazy store a `getSharedPreferences` singleton you hold; do `edit().clear().commit()`; then unlink; then fsync dir; then ALSO set the in-memory map... SharedPreferencesImpl keeps its in-memory map and will rewrite the file on the next apply from memory! If any code still holds the SharedPreferences instance and calls apply() after the burn, the file comes back (full map rewrite → rename recreates path). THAT is the real resurrection vector — not queued writes, but live instances. Fix: after wipe, tombstone the store wrappers (their `prefs()` accessor must return a sealed/no-op writer post-burn), i.e. production code stops depending on platform ordering entirely: the accessor checks the durability hold / burned flag. That answers "make production code not depend on the ordering": seal the writers. Then the bounded-wait test becomes a regression net, fine to keep, but the load-bearing claim no longer rests on AOSP FIFO details.

  Also: the 2s window + 25ms poll is fine as evidence; strengthen it by hammering — queue MANY applies with random payloads right up to burn start, run the test N times (parameterized loop), and also stage the *during-burn* write (a thread spamming apply() while burnVault runs). And note the honest limit they wrote is good; keep it.

  Test shape: with sealed writers, the test asserts post-burn `prefs(...).edit().putString(...)` is a no-op or throws — that's deterministic, no window needed.

  Also answer: is bounded-wait right shape? As a *test of a platform claim*, it's the only shape available without reflection; but the engineering answer is to stop needing the claim. Keep the test, demote it from "load-bearing proof" to "tripwire," and put the guarantee in code (writer sealing). Also mention `commit()` return value must be checked — commit returns false on failure; their wipe presumably checks; also note `commit()` waits for the disk write of the clear, and unlink after commit is then safe w.r.t. that store's queue (FIFO). The remaining platform dependency after sealing is nil for the burn path.

  Now Q0 length: user asked to answer most fully. Structure the reply:

  - Q0: disagree with tri-state; recommend closed primitive set + registry + postconditions + arch rule; explain why type can't carry it fully; sketch.
  - Q1: one function; memory-first ordering; sketch.
  - Q2: bottom-up per-directory fsync; walkFileTree vs manual recursion; fail-closed on unreadable; bound by bounding cache; durability DOES matter.
  - Q3: cleanup @After unconditional, assert @Before, single snapshot manifest drives both; orchestrator note.
  - Q4: reset (delete) channels in burn + compare; but scope the gate's claim to app-controlled state.
  - Q5: reasoning correct on AOSP but live-instance rewrites and during-burn writes are the real vectors; seal the writers; keep bounded test as tripwire; strengthen it.

  Six-month regrets to flag:
  - tri-state WipeOutcome would have been routed around / mis-collapsed.
  - graveyard rename trick contradicts burn semantics.
  - unbounded decrypted cache — bound it.
  - gate claim must be scoped to app-controlled state or false-claim defects recur.
  - sealed-writer flag must be in the same atomic region as the hold raise (set writersSealed before mutations; if process dies, hold raised anyway → lock screen → writers sealed on next boot too).

  Kotlin sketches: keep them tight. Format: markdown headers per question, prose + code. Length: this warrants a long reply — it's a design review deliverable. But keep it disciplined; no fluff.

• Read both. The staged test is honest about its limits; the diagnostics source confirms both reviewers. Here is the design review.

  ## Q0 — making both axes structural

  Your instinct is directionally right but the proposed shape is wrong, and I want to be specific about why, because the failure mode of the tri-state is subtle.

  **`{ ProvenDurable; NotDurable; Failed }` collapses to binary at the only place it matters.** Inside the burn, what do you do with `NotDurable`? You throw `DestroyFailed` — the hold stays raised, same as `Failed`. So the burn boundary is pass/fail, and the tri-state invites the exact mistake it was meant to prevent: a future call site that does `if (outcome != Failed)` and treats `NotDurable` as success, because the type told it those are different things and it picked the wrong branch. A type whose middle value has no legitimate consumer is not a proof; it's a trap with a name.

  The deeper problem: **no return type can prove an fsync happened.** A call site can construct `ProvenDurable` out of thin air. So the type-system approach caps out at "the reviewer has fewer things to notice," which is what you already had. What *can* be made structural is the **procedure**: delete + prove + fsync must live in one body that call sites cannot decompose. That points at your second option — the combinator — and I'd push it further than you framed it:

  **A closed set of primitives behind a burn scope, with a registry, with per-step postconditions.**

  ```kotlin
  // wipe/Durability.kt — the ONLY file allowed to perform destructive IO in the burn.
  sealed interface Durability {
      /** unlink(s) made durable by fsync of [dir] after the mutation */
      data class FsyncedDir(val dir: File) : Durability
      /** KeyStore mutations are transactionally persisted by keystore2 — no fsync exists or is needed */
      data object KeystoreTransactional : Durability
      /** clear().commit() (FIFO-drains queued applies for the store), then unlink, then fsync shared_prefs */
      data class PrefsStore(val name: String) : Durability
  }

  class BurnStep(
      val name: String,
      val durability: Durability,          // declared, and the declaration IS the implementation
      val verify: () -> Boolean,           // postcondition — checked in-burn AND reusable by the gate
      internal val action: () -> Unit,     // throws DestroyFailed on any failure
  )

  object BurnPlan {
      val steps: List<BurnStep> = listOf(
          BurnStep("image-store", FsyncedDir(imageDir), verify = { imageDir.isEmptyDir() }) {
              imageStore.burnObliterate()          // primitive owns delete+fsync internally
          },
          BurnStep("biometric-alias", KeystoreTransactional, verify = { !keystore.hasAlias(BIO_ALIAS) }) {
              wipeBiometricMaterialOrThrow()
          },
          BurnStep("boot-diagnostics", FsyncedDir(filesDir), verify = { diagnostics.isErased() }) {
              diagnostics.eraseOrThrow()             // Q1: memory + disk + fsync, one body
          },
          BurnStep("plaintext-cache", FsyncedDir(cacheDir), verify = { cacheDir.isEmptyDir() }) {
              deleteTreeDurably(cacheDir)            // Q2
          },
          // ...
      )
  }
  ```

  Three properties fall out of this that your tri-state doesn't give you:

  1. **"Deleted but didn't fsync" is unrepresentable *using the primitives*.** `deleteTreeDurably` returns `Unit` and throws. There is no overload that skips the fsync, no Boolean to misread. This is achievable; a proof-carrying type is not.
  2. **"Durability not applicable" stops being an escape hatch by being removed as a category.** Every step must name a mechanism. The Keystore case is not an exemption — `deleteEntry` is synchronously durable through the keystore daemon, which is a *stronger* guarantee than fsync, and `KeystoreTransactional` says so. The mechanism is written once, reviewed once, in `wipe/`. A step that claims `FsyncedDir` without fsyncing is a lie, but it's a lie in *one 20-line file* instead of at N call sites.
  3. **The registry is enumerable, which is what actually fixes your meta-problem.** Your failure was "I enumerated on one axis and declared the class closed." With `BurnPlan.steps` as data, the gate test can assert `steps.all { it.durability is ... }`, iterate the same `verify()` lambdas for its baseline (this answers Q3's staleness problem — one enumeration, three consumers: burn, gate, baseline), and assert the step *count* so a new cleanup added outside the registry fails CI.

  **What it costs.** New cleanup work must be expressed through a primitive or you write a new one. That friction is the point — the day adding a cleanup is as easy as calling `File.delete()`, the structure is decorative. Which brings me to the honest limit: **Kotlin cannot stop a call site inside the burn lambda from calling `file.delete()` directly.** This is a lint boundary, not a type boundary, and you should say so in the kdoc rather than overclaim. Close it with one arch rule — a detekt custom rule or a 15-line source-tree unit test that fails if `deleteRecursively|SharedPreferences.edit|Files.delete` appears outside `wipe/` and the gate. Crude, and it works forever.

  **How it doesn't become ceremony to route around:** the primitives must cover every real case the app actually has (file, tree, prefs, keystore alias, in-memory state) so the honest path is the short path. Ceremony gets routed around when it's *longer* than the sin, not when it's merely required.

  One more thing on the meta-problem, stated plainly: the third axis is already here. Defect 1 wasn't only missing fsync — it also didn't clear `_entries`. "Does it reset in-memory state" is a third column you enumerated no better than the second. The `verify()` postcondition per step is my answer to the general case: you will never enumerate all axes, so stop trying to enumerate axes and make each step carry its own proof of the end-state. The axes become checkable consequences instead of remembered properties.

  ## Q1 — one function, memory-first

  Yes, one function. Two cleanup functions with divergent strength four lines apart is not a factoring, it's a defect generator — you already have the empirical proof. The differing caller needs (UI must not throw, burn must fail closed) are a *wrapper* concern, not a semantics concern:

  ```kotlin
  /** Complete erase: memory, disk, durability. Returns false if disk state could not be proven. */
  fun erase(): Boolean = synchronized(lock) {
      // 1. Memory FIRST. This is the resurrection kill: any record() that runs after
      //    this point appends to an empty list and can only write POST-burn lines.
      _entries.value = emptyList()
      loaded = true
      // 2. Truncate before delete: on the fail-open UI path a failed delete then leaves
      //    an empty file, not stale content. Costs one write syscall; keeps ONE code path.
      runCatching { file.writeText("") }
      // 3. Delete + fsync the parent so the unlink survives journal replay.
      val durable = runCatching {
          Files.deleteIfExists(file.toPath())
          FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
      }.getOrDefault(false)
      // 4. Prove.
      durable && Files.notExists(file.toPath())
  }

  fun clear() { if (!erase()) Log.w(TAG, "diagnostics erase failed") }   // UI wrapper
  ```

  The ordering is the part the current code got backwards, and it's worth stating the invariant: **memory must be cleared before the disk proof, under the same lock `record()` takes.** If you delete-then-clear-memory, a `record()` interleaved between them rewrites the pre-burn buffer to disk — your defect (b). Memory-first under the lock makes the racing `record()` harmless by construction: it writes only its own new line, which is *post-burn* data, and a fresh install writes boot diagnostics on first boot too, so that line is not a distinguisher.

  Is truncate-before-delete still worth it when you fsync? On the burn path, no — a failed delete throws, the hold stays raised, residue is irrelevant. On the UI fail-open path, yes, absolutely: it's the difference between "clear silently failed" and "clear silently failed but left nothing behind." One shared body, one truncate, both callers served. Keep it.

  Also note `clearProven()`'s shape was lying in a second way: `runCatching { file.delete() }` then `notExists` returns true when the delete threw *for a reason other than absence* (e.g., EACCES on a file that then got removed by nothing — fine — but the pattern "swallow then check absence" conflates "deleted" with "never existed" and "delete failed but file vanished anyway"). `Files.deleteIfExists` throwing is information; classify it, don't `runCatching` it into the void on the fail-closed path.

  ## Q2 — durable recursive delete

  The mechanics, for ext4 and f2fs, minSdk 26:

  **You must fsync every directory you removed entries from, bottom-up.** An unlink of `cacheDir/a/b` is recorded in `a`'s directory metadata; the removal of `a` is recorded in `cacheDir`. fsyncing only `cacheDir` makes "a is gone" durable but says nothing about "b was gone from a." Now, the nuance: if you delete `a` *itself*, `a`'s internal state is moot — once the rmdir is durable via the parent's fsync, nothing can replay `b` back, because there is no `a` to contain it. So you do **not** need to fsync a subdirectory you're about to delete; you fsync each directory **once, after its children are removed**, which for a deleted subdirectory is its parent's fsync. That gives exactly one fsync per directory, post-order:

  ```kotlin
  /** Empties [root] durably; keeps root itself (fresh install has the dir, empty). Throws DestroyFailed. */
  fun deleteTreeDurably(root: File) {
      val children = root.listFiles() ?: throw DestroyFailed("unreadable: $root")
      for (child in children) {
          if (child.isDirectory) {
              deleteTreeDurably(child)                       // empties it, fsyncs child, then:
              if (!child.delete()) throw DestroyFailed(child) // rmdir, recorded in root
          } else if (!child.delete()) {
              throw DestroyFailed(child)
          }
      }
      fsyncDirectory(root)                                   // one fsync, after all mutations to root
  }
  ```

  I'd write it as explicit recursion rather than `Files.walkFileTree` — walkFileTree's visit-failed callback tempts people into `CONTINUE` on `IOException`, which is fail-open by default and exactly wrong here. With the recursive form there is no branch that skips.

  You may hear (or have already reasoned) that on ext4 with ordered journaling, fsync of the last-touched directory commits the journal transactions preceding it, so one fsync "works." It does, on ext4, today. f2fs has its own checkpoint/roll-forward semantics. Depending on cross-directory journal ordering is the same species of claim as your Q5 — correct on current AOSP, resting on platform internals, one filesystem migration away from being a silent lie. Per-directory fsync is O(directories), not O(files); the cost is a handful of syscalls. Pay it.

  **Unreadable subdirectory:** throw `DestroyFailed`. Never skip. An unreadable directory is precisely the one that might still contain the decrypted attachment you exist to destroy. The hold stays raised, next boot locks, and that is the design working, not failing.

  **Unbounded/slow:** the deletion walk is O(files) and irreducible — you must unlink every file regardless, so durability adds nothing to the asymptotic cost. If wall clock in the burn is a real concern, the honest lever is **bounding the cache during normal operation** (LRU cap on decrypted attachments), which you should do anyway: an unbounded on-disk plaintext cache is its own defect independent of the wipe. Do **not** reach for the rename-to-graveyard-then-delete-lazily trick — it makes the *visible* state clean quickly but leaves the plaintext on disk after the burn reported success, which is the exact guarantee the burn exists to provide. You'll be tempted by it in six months when someone profiles a 2GB cache burn. The answer is the cache cap, not the graveyard.

  **Does cache durability matter?** Yes, and the "OS evicts it anyway" argument is a category error. Eviction is the OS's prerogative *before* the burn; after the burn, a journal-replayed plaintext file is not metadata about vault use — it *is* vault content. This is the one place where the residue is the payload. fsync it, keep it in the gate, and don't narrow the claim here. (Q4 is where narrowing is legitimate.)

  ## Q3 — teardown: your instinct is right, with one correction of emphasis

  Cleanup in `@After` **unconditionally** (it's idempotent — "no vault" is a state the cleanup must be able to observe and pass, not a reason to skip), assertion in `@Before`. Both, and they are not interchangeable: the `@After` is hygiene, the `@Before` is the gate. The reason `@Before` is the load-bearing one is that the snapshot is taken there — a comparison is only as honest as the moment it reads its baseline. Assert at the point of use. "Fail loudly in setup, never compare over an unverified baseline" is correct and I'd put that sentence in the test class kdoc verbatim.

  On staleness — this is where Q0 pays off a second time. Do not write a parallel baseline checklist. You already have two enumerations of the same set: the snapshot manifest and (after Q0) `BurnPlan.steps[*].verify`. Drive the baseline assertion from **the same snapshotter the equivalence test uses**: snapshot the device, then assert every entry in that snapshot is absent/empty/default. One snapshot function, two consumers (the fresh-vs-burn comparison and the baseline assertion). If someone adds a store to the snapshot, the baseline assertion covers it on the next compile. A hand-maintained baseline list will be stale within a quarter; that is a guarantee, not a risk.

  One gap your baseline list must close: it currently reads as all *on-disk* state. Defect 1 was in-memory. The baseline assertion must include process state — `_entries` empty, session null, hold lowered — or same-process residue sails through a disk-only check.

  On per-test isolation: **Android Test Orchestrator with `clearPackageData` would have made Defect 3 impossible** — each test gets its own process and wiped app data, i.e., an actual fresh install per test. It costs real wall clock and it does not remove the need for the `@Before` assertion (the gate test itself takes two snapshots within one process). My call: keep the asserted baseline as the mechanism of record — it's cheap, fast, and tests the thing orchestrator can't — and adopt Orchestrator only if the suite's runtime is tolerable, as defense in depth against the *next* shared-process residue you haven't imagined. Don't buy process-per-test as a substitute for the assertion; buy it, if at all, as a net.

  ## Q4 — channels: fix (a), and then scope the whole claim

  Your threat reasoning is right as far as it goes: the app is the vault, installation already discloses possession, and a non-default channel importance discloses "a human engaged with this app's settings" — app-use metadata, not vault-use metadata. If the threat model stops at "does this reveal a vault existed," channels add almost nothing over the package's presence.

  But that is not the guarantee you wrote. The guarantee is **post-burn state indistinguishable from a fresh install**, and a user who set the channel to silent and then burns leaves the device in a state a fresh install never reaches. Under your own standard — "anything that breaks that is blocking" — this is blocking. Fix (a): in the burn, `deleteNotificationChannel` for every channel the app creates. Deletion is the correct operation, not "reset": you cannot programmatically reset importance, and you don't need to — fresh-install-after-first-launch state is *default channels recreated by `Application.onCreate`*, which is exactly what the next boot does after you delete them. Then put channel state (`getNotificationChannels()` → id, importance, sound, vibration) into the gate snapshot so the claim is executed, not prose. Either way, delete the false claim today — a false exclusion note in a security gate is worse than no note, because it reads as verified.

  The six-month flag, and it's the bigger deal: **channels are the first instance of a class — system-held state — that your indistinguishability claim cannot cover, and you need to draw that boundary explicitly or the gate will keep generating false-claim defects.** Usage stats, install timestamps, PackageInstaller records, app-ops: unwipeable, and several disclose app *use* regardless of what you do. The gate's guarantee must be scoped in writing to "app-controlled state," with the system-held set enumerated as explicit exclusions with reasons. Within app-controlled state, channels are controllable, so they don't get an exclusion — they get a wipe step and a comparison.

  ## Q5 — the ordering claim: right conclusion, wrong load-bearing member

  Your AOSP reasoning is correct. `commit()` enqueues its disk write on the same single-threaded `QueuedWork` handler as prior `apply()`s for that store and blocks until its own write lands; FIFO on a single thread means an earlier `apply()` cannot still be pending when `commit()` returns. Neither reviewer refuted it because it isn't refutable on current AOSP — as far as it goes.

  It doesn't go far enough, and your staged test stages the easy half of the race. Two vectors sit outside the commit-ordering argument:

  1. **A write initiated *during* the burn.** `commit()` drains what was queued *before* it. A `put().apply()` from another thread that lands between your commit and your unlink writes to a file you're about to delete — and SharedPreferences persists by writing a temp file and **renaming it over the target**. A rename after your unlink *recreates the path*. Your test queues the write before the burn, which is the case the ordering argument covers; it would not catch this one.
  2. **Live instances rewriting from memory.** Any code still holding the `SharedPreferences` instance after the burn holds the full in-memory map. Its next `apply()` rewrites the *whole map* via temp-file rename — resurrecting not a stray key but the entire pre-burn store, no queue involved. This is the same species as Defect 1(b): memory outliving a disk proof.

  So: the bounded-wait observation is the right *shape* for testing a platform scheduling claim (there is no deterministic drain — `waitToFinish()` is hidden, reflection is blocklist roulette on API 34, and you'd be trading one platform dependency for a worse one). But it should not be the proof. **Make the production code not depend on the ordering: seal the writers.** The `prefs()` accessor checks a process-wide `burned`/`writersSealed` flag — set atomically with the hold raise, before the first mutation — and after sealing it returns a no-op writer (or throws). Then resurrection is impossible not because AOSP schedules writes kindly, but because nothing in the process can issue one. The claim stops needing proof, which is what you asked for.

  Keep the staged test, but demote it in intent from "proof" to "tripwire," and harden it while it's cheap: loop the stage N times, queue *many* applies with distinct payloads right up to the burn call (not one), and add a second test with a spammer thread issuing `apply()` *concurrently with* `burnVault()` to cover vector 1. With sealed writers, the strongest test becomes deterministic and window-free: post-burn, `prefs(...).edit().putString(...)` is a no-op and the file never reappears — assert that, and the 2-second poll becomes backup evidence instead of the foundation.

  Two small things on the existing wipe while you're in there: check `commit()`'s return value (it reports disk failure; a false return on the fail-closed path must throw), and fsync the `shared_prefs` directory after the unlinks — you're already paying for it elsewhere, and it's the durability half of this same claim.

  ## Where I disagreed, collected

  - The tri-state `WipeOutcome` is wrong; use closed primitives + a registry + per-step `verify()`, and get the gate/baseline to consume the same enumeration.
  - Truncate-before-delete is not redundant under fsync — it protects the fail-open UI path; keep it in the one shared erase body.
  - fsync-only-`cacheDir` "works" on ext4 for journal-ordering reasons you should not rely on; per-directory post-order fsync is nearly free and honest.
  - The graveyard-rename optimization contradicts the burn's semantics; bound the cache in normal operation instead.
  - Assert the baseline in `@Before`, not `@After` — `@After` is hygiene; and drive the assertion from the shared snapshotter, not a parallel list.
  - Channels: wipe and compare (a), but the real fix is scoping the gate's guarantee to app-controlled state in writing.
  - Q5: your ordering argument is sound but covers the least dangerous vector; seal the writers in production and keep the race test as a tripwire.

To resume this session: kimi -r session_44ee4694-f0ee-4d4a-a562-68fb54ad6073
e asserted baseline after that. Orchestrator + shared-snapshot baseline assertion is the right spend. Skip per-test data wipes.

  ## Q4 — channels: reset them; it's cheap, and the "app is the vault" framing doesn't save you

  First, the threat analysis, because you asked. Channel *existence* discloses nothing (created on every launch, fresh installs have it). Channel *modification* discloses more than "the app was used" — it discloses *deliberate engagement*: someone opened notification settings for Zitrone and tuned it. Nobody does that to an app they installed and never meaningfully used. Yes, the app's presence on the device already discloses installation, so the marginal leak is "used" vs "installed" — but Zitrone's entire PD posture is precisely that gap ("I installed it; I never ran a vault"), so a user-tuned channel sits squarely on the wrong side of it. It's a weak oracle, but it's a real one, and your guarantee is stated absolutely.

  Now the decisive point: **option (a) costs almost nothing, so the weighing exercise is moot.** Deleting and recreating a channel resets user-modified importance/sound/vibration to defaults — AOSP's documented behavior is that a recreated channel resurrects the *deleted* settings only if the user never changed them (in which case they were defaults anyway), and comes back at defaults if the user did. That is exactly the semantics you want:

  ```kotlin
  val nm = context.getSystemService(NotificationManager::class.java)
  nm.deleteNotificationChannel(CHANNEL_ID)          // drops user modifications
  createNotificationChannel(nm)                     // same call Application.onCreate makes — defaults
  ```

  So: **do (a)** — reset in the burn as a `WipeStep` (durability: `KeystoreBacked`-style named mechanism, `NotificationManagerService` persists channel state in its own system-owned XML, written via its own handler — name the claim, note that system_server's durability is outside your fsync reach and that you accept it because the *mutation* is what matters, and if the system crashes mid-write the resurrection risk is the channel reverting to *modified*, i.e., the failure direction is detectable, not hidden) — and then **fix the false claim by making it true**: extend the snapshot to dump `notificationChannels` (id, importance, sound, vibration, lights) so channels genuinely "ARE compared," and the exclusion list entry becomes a comparison entry.

  The one honest exclusion you'll keep: if the user *blocked notifications for the app entirely* (app-level, not channel-level), that's `areNotificationsEnabled() == false` and **cannot be programmatically undone** — no API reverses it. That one gets a documented exclusion with the reason attached, and the exclusion list gains a rule that every entry carries a `reason` string the test prints on failure. Which leads to the meta-fix, same as Q0: the exclusion list's *claims* ("compared via prefs") should be mechanically checked where checkable. A one-line drift guard — the test asserts the snapshot surface and exclusion list together cover a hardcoded manifest of known storage surfaces — turns "the comment lied" into a failing test the day someone adds a surface.

  If a channel property ever proves genuinely unresettable, *then* narrow the claim for that property alone, in the exclusion list, with the reason. Don't preemptively narrow to (b) for something a two-line reset handles.

  ## Q5 — your ordering claim is wrong as stated; the fix is to stop needing it

  Read your staged test's framing again: "a `commit()` is ordered behind any `apply()` already queued for that store." That's the claim, and **as stated it's false**. `SharedPreferencesImpl.commit()` writes its own memory snapshot to disk *synchronously on the calling thread* and returns — it does **not** drain or wait for `apply()` writes already sitting in `QueuedWork`. Those runnables stay queued.

  What actually saves you on API 26+ is a different mechanism entirely: `SharedPreferencesImpl`'s **disk-generation guard**. Each `commitToMemory()` bumps a generation counter; when a queued `apply()` write finally runs, `writeToFile` compares the runnable's captured generation against the current one and **drops the write as stale** — your burn's `clear().commit()` bumped the generation past every write queued before it, so the late runnable discards itself instead of rewriting the file. So the conclusion you're testing ("queued apply can't resurrect the store") is probably *true on AOSP ≥ 8.0*, but the argument in the commit message and the test kdoc is not *why* it's true. That distinction matters: your reviewers balked at "rests on platform implementation details," and the fix isn't a better observation window — it's that you're citing the *wrong* platform detail. If you keep the in-process burn, the kdoc should name the generation guard, and the test should be understood as validating AOSP behavior you cannot see into (OEM forks exist), which brings me to the real point.

  **The deterministic fix is in the production code: the burn should end in process death.** You cannot make in-process file deletion durable against a *live writer* — as long as the process runs, live `SharedPreferencesImpl` singletons, `StateFlow`s, and any component can rewrite state after you proved absence (your Defect 1 is literally this, in-memory edition). And the guarantee "indistinguishable from fresh install" is only ever *evaluated at next boot* anyway. So the burn's terminal shape should be:

  ```
  raise hold (durable) → steps prove themselves → lower hold (durable, fsynced) → Process.killProcess()
  ```

  Process death is the only truly deterministic drain of `QueuedWork`: the queue dies with the process, no hidden API, no reflection, no OEM-fork exposure, no ordering claim at all. It also composes perfectly with your hold: death *before* the hold is lowered leaves the hold raised → lock screen; death after lowering is a proven wipe → onboarding. Fail-closed at every interruption point, and Q5's entire race class — plus Defect 1's resurrection race — stops existing rather than being argued away. (The app then restarts cold into whatever the hold dictates; from the user's side a burn that ends in an app restart is *more* plausible-deniability-shaped, not less — a still-running "fresh" app is itself odd.)

  On the test as staged: keep it, but be honest in its name and kdoc about what it is. A bounded-wait observation can only ever **prove the presence of the bug, never its absence** — your own "HONEST LIMIT" paragraph already says this, so make the test's *role* match: it's a canary that fails loudly if the platform behavior ever regresses (an OEM build, an API bump), run against the *real* burn path. Two concrete improvements: (1) if the burn gains process death, stage the race across it — stage the in-flight write, run the burn, *relaunch*, and assert at next boot; that tests the contract you actually ship rather than a 2s window in a process the real flow would have killed. (2) If you ever do need a deterministic in-process drain on the emulator, `QueuedWork.waitToFinish()` is reachable despite the blocklist: on a debuggable emulator image, drop the hidden-API enforcement for the test run (`settings put global hidden_api_policy 0` via orchestrator/adb before the test process starts) and reflect it. It works, it's deterministic for that classload — but treat it as scaffolding for the canary, not as a production load-bearing wall. The production claim should rest on process death, not on a reflection reach into `QueuedWork`.

  One more race your staged test doesn't cover and process death also fixes: a write *initiated after* the burn by the still-running app (some component lazily touches a store post-burn) recreates `shared_prefs/zitrone_auth.xml` with post-burn content. Not pre-burn data, but the *file existing* is itself drift from a true fresh install that hasn't written it yet. Bounded waits catch nothing there; killing the process does.

  ## What you'll regret in six months, collected

  - **A public `ProvenDurable` object** — the day someone returns it from a hand-rolled cleanup, your type system is decoration. Private constructor or nothing.
  - **`NotDurable` as a representable outcome** — it's a standing invitation to ship Defect 2 again with a type attached.
  - **Truncate-before-delete** — it will migrate into documentation as a remanence claim you can't keep on flash.
  - **An exclusion list whose claims aren't mechanically checked** — Defect 4's shape will recur on the next storage surface.
  - **A burn that leaves the process alive** — every "can a live writer resurrect X" question (Q5, Defect 1b, the next one) has to be re-argued per store, per API level, per OEM. Process death answers all of them at once.
  - **The hold's own durability** — verify the raise is `commit()` + fsync before the first mutation. If it isn't, everything above sits on an unproven foundation.

  The thread through all of it: your defects weren't fsyncs you forgot — they were *claims the code structure didn't force anyone to make*. The table-and-interpreter design, the shared snapshotter, and process death are all the same move: convert remembered invariants into structural ones. That's the right direction; just don't let the result type be the part of it that's only decorative.

To resume this session: kimi -r session_14390625-6562-4210-923a-8baac82668ea
