OpenAI Codex v0.145.0
--------
workdir: /root/zitrone
model: gpt-5.6-sol
provider: openai
approval: never
sandbox: read-only
reasoning effort: none
reasoning summaries: none
session id: 019f942e-eb36-7650-99f3-07451d20ff7e
--------
user
You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes, do NOT edit any file.

## Product & threat model
Zitrone: a production Signal-Protocol end-to-end encrypted messenger shipping to the Play Store, with a plausible-deniability second vault and a "Pucker Burn" duress credential. Adversary has PHYSICAL DEVICE ACCESS and FORENSIC CAPABILITY; assume CRASH / PROCESS-DEATH at ANY instruction. This is a FIX ROUND: fixes are NOT lower-risk than original code — in this codebase, several prior rounds each introduced a defect only re-verification caught, one fix introduced a P1, and this PR's own first round was rejected. **Treat the delta as guilty until proven otherwise.**

## What to review
The DELTA `321b358..9ab8cb0` on branch `feat/0.9.2-vault-slotb-pr1` in this repo (/root/zitrone). Start with `git show 9ab8cb0` and `git diff 321b358..9ab8cb0`. Verify against ACTUAL SOURCE — do NOT trust the implementation summary or the invariant table's conclusions.
- Primary source: apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt (`attemptUnlockOrAdd`, `unlockWithKey`, marker methods), VaultSlots.kt (`sealSlotSelfVerifying`, `sealSlot`, `randomVaultSlotIndex`), apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt, docs/SECURITY_MODEL.md, and the tests apps/android/app/src/test/java/com/zitrone/app/{AttemptUnlockOrAddTest,VaultImageStoreTest,BiometricUnlockStoreTest}.kt.
- Context only (do NOT trust): /root/l00prite/pr1-fix-marker-invariant-table.md, and the prior-round reports /root/l00prite/pr1-review-{codex,grok}.md.

## Verify specifically (binding — do not abbreviate)

1. B1 FAIL-CLOSED — Confirm `attemptUnlockOrAdd` is now genuinely a PURE READER of both delete markers: it writes/clears NEITHER `vault.delete-intent` nor `vault.delete-confirmed` on ANY path, including error/exception paths. INDEPENDENTLY re-derive that it is removed from the marker-writer set (enumerate every line that could touch a marker file), rather than accepting the invariant table. Confirm marker-present yields `Rejected` (NOT a throw), and that the throwaway 256 KiB payload GCM still runs on that path. Confirm no residual call to `clearBothMarkersDurably`/`clearDeleteIntent` remains reachable from the add path.

2. TOCTOU — Confirm the marker-absent check (`Files.notExists` ×2) and the image write genuinely execute in ONE `imageLock` critical section with no release in between, and that `markDeleteIntent` / `markServerDeleteConfirmed` taking `imageLock` actually closes the interleave window. Hunt for any path where a marker could appear, or be observed stale, between the check and the write: lock reentrancy, a lock released mid-operation, a callback/alien code under the lock, or a reader deriving marker state outside the lock.

3. B2 SELF-VERIFYING SEAL — In `sealSlotSelfVerifying`: confirm the equality check is genuinely constant-time (`MessageDigest.isEqual` over equal-length inputs); confirm the derived master key's lifetime is NOT widened beyond the verify (compare to `sealSlot`); confirm the `finally`-wipe of the master key (and the recovered key) fires on EVERY path including the throw paths; and confirm it throws BEFORE any persist can occur in `attemptUnlockOrAdd` (i.e. the candidate seal precedes the create branch's write, unconditionally).

4. TIMING PARITY AT THE NEW COUNT — The wrapped-key GCM count moved 5→6 with the self-verify. Do NOT assume the prior parity verdict carries. Re-derive from source: exactly 5 Argon2id, exactly one 256 KiB payload GCM, and exactly 6 wrapped-key GCM (4 sweep unwrap + 1 candidate seal encrypt + 1 self-verify decrypt) across ALL FOUR outcomes (unlock / burn / create / reject) AND all three triple-entry attempt positions. Note any outcome-dependent divergence (e.g. the marker-present reject vs ordinary reject, or create's outer GCM/write residual).

5. F4 WIPE DISCIPLINE — Confirm no throw path can strand `candKey` or a live `unlock.vaultKey` now that candidate generation moved inside the `try`. Check the cleanup-var mirror (`candKeyForCleanup`) for any path where the mirror and the real reference diverge (e.g. reassignment, a throw between allocation and mirror assignment, double-wipe correctness, or a successful-return path that wrongly wipes a handed-off key).

6. F9 + THE UNREQUESTED BIOMETRIC CHANGE — Explicit scrutiny, not a nod. The fix guards `unlockWithKey` to `VAULT_SLOT_RANGE` (as requested) but ALSO tightened `BiometricUnlockStore`'s accepted range beyond the literal ask, so a tampered slot-0 wrap reads not-enabled rather than reaching a throw. OQ4 requires biometric stay slot-A-only. Verify: (a) does this preserve the A-only invariant in every case; (b) can it cause a LEGITIMATE A-bound biometric wrap to read as not-enabled under any condition (A is placed in slots 1..SLOT_COUNT-1 by `createVaultSlots` — confirm no legit A ever lands on slot 0); (c) does it alter behavior for any existing 0.9.1-era biometric state (a 0.9.1 install could have A at slot 0 and a biometric wrap naming slot 0 — trace what now happens on upgrade, in combination with the v2→LegacyImage retire path).

7. GENERAL NEW DEFECTS from the fix — key-material wipe/use-after-wipe, canonical/dek desync, durability/atomicity regressions introduced by the restructure, the `Rejected`-on-marker-present path's interaction with the router/triple-entry (does silently-failing create leak or loop), any behavioral change to `create()` or `unlock`/`unlockWithKey` callers, and anything the restructure of the `when` expression changed.

## Output format
A structured findings report. For EACH finding: SEVERITY (Critical / High / Medium / Low / Info), exact FILE + FUNCTION (+ line), DEFECT MECHANISM, and a concrete FAILURE/ATTACK SCENARIO (inputs/crash-point → wrong outcome). If an item is clean, say so explicitly and state why. End with a one-line overall verdict. Report ONLY — no fixes.

codex
I’ll inspect the commit and full relevant source/tests, then independently enumerate marker writers and re-derive every cryptographic operation count and upgrade path. I’ll report findings only and make no filesystem changes.
exec
/bin/bash -lc 'git show --stat --oneline --decorate 9ab8cb0 && git show --format=fuller --no-ext-diff 9ab8cb0 -- apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt docs/SECURITY_MODEL.md && git diff --no-ext-diff 321b358..9ab8cb0 -- apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt docs/SECURITY_MODEL.md apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt' in /root/zitrone
 succeeded in 0ms:
9ab8cb0 (HEAD -> feat/0.9.2-vault-slotb-pr1) fix(vault): PR-1 review round — B1 fail-closed markers, B2 slot self-verify, F4 wipe, F9 slot-0 guard
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 115 +++++++++++++--------
 .../com/zitrone/app/crypto/vault/VaultSlots.kt     |  43 ++++++++
 .../com/zitrone/app/data/BiometricUnlockStore.kt   |  11 +-
 .../java/com/zitrone/app/AttemptUnlockOrAddTest.kt |  67 +++++++++++-
 .../com/zitrone/app/BiometricUnlockStoreTest.kt    |  19 ++--
 .../java/com/zitrone/app/VaultImageStoreTest.kt    |  11 +-
 docs/SECURITY_MODEL.md                             |  10 ++
 7 files changed, 213 insertions(+), 63 deletions(-)
commit 9ab8cb0dd2c1307790c5b27c4e71f7d606499f63
Author:     jackofall1232 <jackofall1232@gmail.com>
AuthorDate: Fri Jul 24 12:48:55 2026 +0000
Commit:     jackofall1232 <jackofall1232@gmail.com>
CommitDate: Fri Jul 24 12:48:55 2026 +0000

    fix(vault): PR-1 review round — B1 fail-closed markers, B2 slot self-verify, F4 wipe, F9 slot-0 guard
    
    Addresses the two-blind-reviewer (Codex+Grok) findings on 321b358; both had
    rejected the marker-clear-over-a-live-image and the un-verified sealed slot.
    
    - B1 (Crit/High, both reviewers): attemptUnlockOrAdd no longer writes or clears
      ANY delete marker. If it cannot PROVE both markers absent (Files.notExists), the
      Created branch fails CLOSED — returns Rejected (not throw) after the same
      throwaway payload GCM every outcome does, leaving A's delete-state machine
      untouched. Reverses OQ3: create() may clear only because require(!binFile.exists())
      proves its markers orphaned; the add path has no such proof, so it never acts on
      a stale-vs-live distinction the code cannot make. This removes the add path from
      the delete-marker WRITER set entirely, so the rounds-13-16 state machine is
      preserved and no reader's assumption can be falsified. Check + write share one
      imageLock critical section (no TOCTOU); folds in F6 (no path skips the payload GCM).
      Disclosed in SECURITY_MODEL.md.
    - B2 (High/Med, both): new sealSlotSelfVerifying decrypt-and-constant-time-compares
      the wrapped key to the vault key under the SAME derived master key (0 extra
      Argon2id, +1 wrapped-key GCM; master key lifetime unchanged), throwing before a
      create can persist a size-correct-but-unopenable slot. Used unconditionally for
      the candidate → wrapped-GCM count is 6 across all four outcomes (parity clean).
    - F4 (Codex, Med): candKey and a matched unlock.vaultKey are now wiped on ANY throw
      — candidate generated inside the try with a cleanup mirror; catch wipes both.
    - F9 (Grok, latent): unlockWithKey now requires slotIndex in VAULT_SLOT_RANGE
      (rejects the burn slot 0); BiometricUnlockStore accepted range tightened to match
      so a tampered slot-0 wrap reads not-enabled and never reaches the guard as a throw.
    
    Tests: fail-closed on intent AND confirmed markers (marker untouched, nothing
    written); self-verify throws + persists nothing on a mis-sealing provider; parity
    updated to 6 wrapped GCM; unlockWithKey/biometric slot-0 rejection. Full app unit
    suite + assembleDebug + assembleRelease green. Marker WRITER/READER invariant table:
    l00prite pr1-fix-marker-invariant-table.md.
    
    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01N81mnevbUZTv66x1impLU5

diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index e6c90a0..2f38be2 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -572,7 +572,12 @@ class VaultImageStore internal constructor(
      */
     fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
         imageLock.withLock {
-            require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
+            // Only VAULT-POOL slots (1..SLOT_COUNT-1) are openable this way (F9 / Grok): slot 0 is the burn
+            // credential and must NEVER be opened as a vault — a future biometric dual-wrap that named slot
+            // 0 would otherwise surface the burn payload as an ordinary unlock instead of triggering a wipe.
+            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
+            // not-enabled and never reaches here; this require is the store-level backstop.
+            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
             val image = canonical ?: run { open(); canonical!! }
             val payload = decodeImage(image).payloads[slotIndex]
             // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
@@ -643,14 +648,22 @@ class VaultImageStore internal constructor(
             // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
             val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
 
-            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
-            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
-            //     POOL (never slot 0) so a create can never clobber the burn credential.
-            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
-            val candSlotIndex = randomVaultSlotIndex(ops)
-            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
-
+            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
+            // the try below so a throw during its generation (native crypto failure, OOM,
+            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
+            // live matched vault key — neither is covered if candidate generation sits before the try.
+            var candKeyForCleanup: ByteArray? = null
             try {
+                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
+                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
+                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
+                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
+                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
+                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
+                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
+                val candSlotIndex = randomVaultSlotIndex(ops)
+                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
+
                 return when {
                     // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
                     unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
@@ -680,42 +693,57 @@ class VaultImageStore internal constructor(
                         UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
                     }
 
-                    // ── CREATE a new vault into a vault-pool slot. ──
+                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
                     create -> {
-                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
-                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
-                        val markersConfirmedAbsent =
+                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
+                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
+                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
+                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
+                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
+                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
+                        // a throw is an observable side channel precisely when the device is mid-delete) after
+                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
+                        // machine is left completely untouched. This marker check is in the SAME imageLock
+                        // critical section as the sweep and the write, and markDeleteIntent /
+                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
+                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
+                        val markersAbsent =
                             Files.notExists(deleteIntentFile.toPath()) &&
                                 Files.notExists(serverDeletedFile.toPath())
-                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
-                            throw VaultImageException.NotDurable()
-                        }
-                        // The 1×256 KiB payload GCM for this branch.
-                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
-                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
-                        val newPayloads =
-                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
-                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
-                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
-                        // unreachable by construction; the dek is already durable on disk from create().
-                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
-                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
-                        // rename landed, the result reporting the rename's durability.
-                        val sync = atomicWrite(binFile, outer)
-                        // Rename committed → advance canonical BEFORE the durability check so a later
-                        // splice/attempt never works from stale state even on the NotDurable throw.
-                        canonical = newInner
-                        if (sync != DirSyncResult.DURABLE) {
-                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
-                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
-                            // canonical, so a later single entry of its passphrase unlocks it via the
-                            // match path (no write needed) — or, if the rename did not survive a crash, it
-                            // is simply absent and re-creatable.
+                        if (!markersAbsent) {
+                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
+                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
                             wipe(candKey)
-                            throw VaultImageException.NotDurable()
+                            wipe(throwaway)
+                            UnlockOrAdd.Rejected
+                        } else {
+                            // The 1×256 KiB payload GCM for the create branch.
+                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
+                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
+                            val newPayloads =
+                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
+                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
+                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
+                            // unreachable by construction; the dek is already durable on disk from create().
+                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
+                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
+                            // rename landed, the result reporting the rename's durability.
+                            val sync = atomicWrite(binFile, outer)
+                            // Rename committed → advance canonical BEFORE the durability check so a later
+                            // splice/attempt never works from stale state even on the NotDurable throw.
+                            canonical = newInner
+                            if (sync != DirSyncResult.DURABLE) {
+                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
+                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
+                                // canonical, so a later single entry of its passphrase unlocks it via the
+                                // match path — or, if the rename did not survive a crash, it is simply absent
+                                // and re-creatable.
+                                wipe(candKey)
+                                throw VaultImageException.NotDurable()
+                            }
+                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
+                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
                         }
-                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
-                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
                     }
 
                     // ── REJECT — no match, no create. Nothing written. ──
@@ -729,10 +757,11 @@ class VaultImageStore internal constructor(
                     }
                 }
             } catch (t: Throwable) {
-                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
-                // (return) path this is not reached; on every other path candKey was already wiped, and a
-                // re-wipe of zeroed bytes is a no-op.
-                wipe(candKey)
+                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
+                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
+                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
+                candKeyForCleanup?.let { wipe(it) }
+                unlock?.let { wipe(it.vaultKey) }
                 throw t
             }
         }
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
index 1878042..28efac4 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
@@ -75,6 +75,49 @@ fun sealSlot(
     }
 }
 
+/**
+ * Like [sealSlot] but SELF-VERIFYING: immediately after wrapping, it decrypts the wrapped key back under
+ * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
+ * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
+ * lifetime is identical to [sealSlot]'s.
+ *
+ * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
+ * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
+ * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
+ * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
+ * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
+ * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
+ * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
+ * would equally break every other slot operation; failing closed here is correct.
+ */
+fun sealSlotSelfVerifying(
+    passphrase: String,
+    vaultKey: ByteArray,
+    ops: VaultSodiumOps,
+    deriver: KeyDeriver = argon2idDeriver(ops),
+): KeySlot {
+    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
+    val salt = ops.randomBytes(SALT_BYTES)
+    val masterKey = deriver(passphrase, salt)
+    try {
+        val wrapped = ops.aeadEncrypt(masterKey, vaultKey, SLOT_AD)
+        val recovered = ops.aeadDecrypt(masterKey, wrapped, SLOT_AD)
+            ?: throw IllegalStateException("sealed slot failed self-verify (wrapped key did not unwrap)")
+        try {
+            // Constant-time equality (both are VAULT_KEY_BYTES) — MessageDigest.isEqual is the platform
+            // constant-time compare. A mismatch means the AEAD provider does not round-trip: fail closed.
+            check(java.security.MessageDigest.isEqual(recovered, vaultKey)) {
+                "sealed slot failed self-verify (recovered key mismatch)"
+            }
+        } finally {
+            wipe(recovered)
+        }
+        return KeySlot(salt = salt, wrapped = wrapped)
+    } finally {
+        wipe(masterKey)
+    }
+}
+
 /**
  * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
  * real vault sealed under [passphrase]. The rest are random filler. The returned
diff --git a/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt b/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
index 8674805..be7d3d1 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
@@ -11,7 +11,7 @@ package com.zitrone.app.data
 import android.content.SharedPreferences
 import com.zitrone.app.crypto.KeyStoreManager
 import com.zitrone.app.crypto.vault.BiometricWrappedKey
-import com.zitrone.app.crypto.vault.SLOT_COUNT
+import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
 import java.util.Base64
 
 /**
@@ -38,10 +38,11 @@ class BiometricUnlockStore(private val prefs: SharedPreferences) {
     fun load(): BiometricWrappedKey? {
         val encoded = prefs.getString(KEY_BLOB, null) ?: return null
         val slot = prefs.getInt(KEY_SLOT, -1)
-        // Validate the FULL slot range, not just >= 0: a corrupted/tampered prefs int must read as
-        // "not enabled" here, never reach unlockWithKey's require(slotIndex in 0 until SLOT_COUNT)
-        // and crash the unlock coroutine.
-        if (slot !in 0 until SLOT_COUNT) return null
+        // Validate against the VAULT POOL (1..SLOT_COUNT-1), not just >= 0: a corrupted/tampered prefs int
+        // — including slot 0, the burn credential, which is NOT a biometric-wrappable vault (F9) — must
+        // read as "not enabled" here, never reach unlockWithKey's require(slotIndex in VAULT_SLOT_RANGE)
+        // and crash the unlock coroutine. Biometric is A-only, and A always lives in the pool.
+        if (slot !in VAULT_SLOT_RANGE) return null
         val blob = try {
             Base64.getDecoder().decode(encoded)
         } catch (e: IllegalArgumentException) {
diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
index 5ef1b11..e41789a 100644
--- a/docs/SECURITY_MODEL.md
+++ b/docs/SECURITY_MODEL.md
@@ -452,6 +452,16 @@ Two VeraCrypt-analogous caveats apply, and are accepted deliberately:
   destroy a vault whose passphrase is not currently entered, exactly as writing to a VeraCrypt
   outer volume without mounting the hidden one can. Creating a vault on a device that may hold
   others is a deliberate, documented risk.
+- **Vault creation silently fails while an account deletion is pending (0.9.2, Android).** Account
+  deletion is a durable two-phase state machine (a `delete-intent` marker, then a `delete-confirmed`
+  marker). While either marker is present, attempting to create a new vault does nothing and is
+  reported exactly like a wrong passphrase — indistinguishable in behaviour and timing. This is a
+  deliberate fail-closed choice: with a live image on disk, nothing observable can tell a *stale*
+  marker (cleanup that did not finish) from a *live* one (a deletion still owed), so vault creation
+  never acts on that distinction rather than risk cancelling a real account deletion or stranding a
+  server-deleted account's local image. The condition is rare and transient (it clears when the
+  deletion completes or is retired), and it leaks nothing — an observer cannot distinguish it from an
+  ordinary failed unlock.
 
 **Per-device scope, and why Android-only is safe.** Plausible-deniability (decoy)
 vaults are a **per-device** feature. Because each install is an independent
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index e6c90a0..2f38be2 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -572,7 +572,12 @@ class VaultImageStore internal constructor(
      */
     fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
         imageLock.withLock {
-            require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
+            // Only VAULT-POOL slots (1..SLOT_COUNT-1) are openable this way (F9 / Grok): slot 0 is the burn
+            // credential and must NEVER be opened as a vault — a future biometric dual-wrap that named slot
+            // 0 would otherwise surface the burn payload as an ordinary unlock instead of triggering a wipe.
+            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
+            // not-enabled and never reaches here; this require is the store-level backstop.
+            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
             val image = canonical ?: run { open(); canonical!! }
             val payload = decodeImage(image).payloads[slotIndex]
             // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
@@ -643,14 +648,22 @@ class VaultImageStore internal constructor(
             // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
             val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
 
-            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
-            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
-            //     POOL (never slot 0) so a create can never clobber the burn credential.
-            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
-            val candSlotIndex = randomVaultSlotIndex(ops)
-            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
-
+            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
+            // the try below so a throw during its generation (native crypto failure, OOM,
+            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
+            // live matched vault key — neither is covered if candidate generation sits before the try.
+            var candKeyForCleanup: ByteArray? = null
             try {
+                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
+                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
+                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
+                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
+                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
+                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
+                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
+                val candSlotIndex = randomVaultSlotIndex(ops)
+                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
+
                 return when {
                     // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
                     unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
@@ -680,42 +693,57 @@ class VaultImageStore internal constructor(
                         UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
                     }
 
-                    // ── CREATE a new vault into a vault-pool slot. ──
+                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
                     create -> {
-                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
-                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
-                        val markersConfirmedAbsent =
+                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
+                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
+                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
+                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
+                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
+                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
+                        // a throw is an observable side channel precisely when the device is mid-delete) after
+                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
+                        // machine is left completely untouched. This marker check is in the SAME imageLock
+                        // critical section as the sweep and the write, and markDeleteIntent /
+                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
+                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
+                        val markersAbsent =
                             Files.notExists(deleteIntentFile.toPath()) &&
                                 Files.notExists(serverDeletedFile.toPath())
-                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
-                            throw VaultImageException.NotDurable()
-                        }
-                        // The 1×256 KiB payload GCM for this branch.
-                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
-                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
-                        val newPayloads =
-                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
-                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
-                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
-                        // unreachable by construction; the dek is already durable on disk from create().
-                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
-                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
-                        // rename landed, the result reporting the rename's durability.
-                        val sync = atomicWrite(binFile, outer)
-                        // Rename committed → advance canonical BEFORE the durability check so a later
-                        // splice/attempt never works from stale state even on the NotDurable throw.
-                        canonical = newInner
-                        if (sync != DirSyncResult.DURABLE) {
-                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
-                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
-                            // canonical, so a later single entry of its passphrase unlocks it via the
-                            // match path (no write needed) — or, if the rename did not survive a crash, it
-                            // is simply absent and re-creatable.
+                        if (!markersAbsent) {
+                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
+                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
                             wipe(candKey)
-                            throw VaultImageException.NotDurable()
+                            wipe(throwaway)
+                            UnlockOrAdd.Rejected
+                        } else {
+                            // The 1×256 KiB payload GCM for the create branch.
+                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
+                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
+                            val newPayloads =
+                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
+                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
+                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
+                            // unreachable by construction; the dek is already durable on disk from create().
+                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
+                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
+                            // rename landed, the result reporting the rename's durability.
+                            val sync = atomicWrite(binFile, outer)
+                            // Rename committed → advance canonical BEFORE the durability check so a later
+                            // splice/attempt never works from stale state even on the NotDurable throw.
+                            canonical = newInner
+                            if (sync != DirSyncResult.DURABLE) {
+                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
+                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
+                                // canonical, so a later single entry of its passphrase unlocks it via the
+                                // match path — or, if the rename did not survive a crash, it is simply absent
+                                // and re-creatable.
+                                wipe(candKey)
+                                throw VaultImageException.NotDurable()
+                            }
+                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
+                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
                         }
-                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
-                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
                     }
 
                     // ── REJECT — no match, no create. Nothing written. ──
@@ -729,10 +757,11 @@ class VaultImageStore internal constructor(
                     }
                 }
             } catch (t: Throwable) {
-                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
-                // (return) path this is not reached; on every other path candKey was already wiped, and a
-                // re-wipe of zeroed bytes is a no-op.
-                wipe(candKey)
+                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
+                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
+                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
+                candKeyForCleanup?.let { wipe(it) }
+                unlock?.let { wipe(it.vaultKey) }
                 throw t
             }
         }
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
index 1878042..28efac4 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
@@ -75,6 +75,49 @@ fun sealSlot(
     }
 }
 
+/**
+ * Like [sealSlot] but SELF-VERIFYING: immediately after wrapping, it decrypts the wrapped key back under
+ * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
+ * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
+ * lifetime is identical to [sealSlot]'s.
+ *
+ * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
+ * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
+ * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
+ * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
+ * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
+ * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
+ * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
+ * would equally break every other slot operation; failing closed here is correct.
+ */
+fun sealSlotSelfVerifying(
+    passphrase: String,
+    vaultKey: ByteArray,
+    ops: VaultSodiumOps,
+    deriver: KeyDeriver = argon2idDeriver(ops),
+): KeySlot {
+    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
+    val salt = ops.randomBytes(SALT_BYTES)
+    val masterKey = deriver(passphrase, salt)
+    try {
+        val wrapped = ops.aeadEncrypt(masterKey, vaultKey, SLOT_AD)
+        val recovered = ops.aeadDecrypt(masterKey, wrapped, SLOT_AD)
+            ?: throw IllegalStateException("sealed slot failed self-verify (wrapped key did not unwrap)")
+        try {
+            // Constant-time equality (both are VAULT_KEY_BYTES) — MessageDigest.isEqual is the platform
+            // constant-time compare. A mismatch means the AEAD provider does not round-trip: fail closed.
+            check(java.security.MessageDigest.isEqual(recovered, vaultKey)) {
+                "sealed slot failed self-verify (recovered key mismatch)"
+            }
+        } finally {
+            wipe(recovered)
+        }
+        return KeySlot(salt = salt, wrapped = wrapped)
+    } finally {
+        wipe(masterKey)
+    }
+}
+
 /**
  * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
  * real vault sealed under [passphrase]. The rest are random filler. The returned
diff --git a/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt b/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
index 8674805..be7d3d1 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt
@@ -11,7 +11,7 @@ package com.zitrone.app.data
 import android.content.SharedPreferences
 import com.zitrone.app.crypto.KeyStoreManager
 import com.zitrone.app.crypto.vault.BiometricWrappedKey
-import com.zitrone.app.crypto.vault.SLOT_COUNT
+import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
 import java.util.Base64
 
 /**
@@ -38,10 +38,11 @@ class BiometricUnlockStore(private val prefs: SharedPreferences) {
     fun load(): BiometricWrappedKey? {
         val encoded = prefs.getString(KEY_BLOB, null) ?: return null
         val slot = prefs.getInt(KEY_SLOT, -1)
-        // Validate the FULL slot range, not just >= 0: a corrupted/tampered prefs int must read as
-        // "not enabled" here, never reach unlockWithKey's require(slotIndex in 0 until SLOT_COUNT)
-        // and crash the unlock coroutine.
-        if (slot !in 0 until SLOT_COUNT) return null
+        // Validate against the VAULT POOL (1..SLOT_COUNT-1), not just >= 0: a corrupted/tampered prefs int
+        // — including slot 0, the burn credential, which is NOT a biometric-wrappable vault (F9) — must
+        // read as "not enabled" here, never reach unlockWithKey's require(slotIndex in VAULT_SLOT_RANGE)
+        // and crash the unlock coroutine. Biometric is A-only, and A always lives in the pool.
+        if (slot !in VAULT_SLOT_RANGE) return null
         val blob = try {
             Base64.getDecoder().decode(encoded)
         } catch (e: IllegalArgumentException) {
diff --git a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
index 522999b..3ddbabd 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
@@ -16,6 +16,7 @@ import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
 import com.zitrone.app.crypto.vault.LibsodiumVaultOps
 import com.zitrone.app.crypto.vault.OUTER_IMAGE_BYTES
 import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
+import com.zitrone.app.crypto.vault.SLOT_AD
 import com.zitrone.app.crypto.vault.SLOT_COUNT
 import com.zitrone.app.crypto.vault.SLOT_PAYLOAD_BYTES
 import com.zitrone.app.crypto.vault.UnlockOrAdd
@@ -266,14 +267,51 @@ class AttemptUnlockOrAddTest {
     // ─────────────────────────── delete-marker interaction (OQ3) ───────────────────────────
 
     @Test
-    fun create_clearsAStaleDeleteIntentMarker() {
+    fun create_failsClosed_whenDeleteIntentPresent_markerUntouched_nothingWritten() {
+        // B1 (reversal of OQ3): a create over an image carrying a delete marker must NOT create and must
+        // NOT clear the marker — it returns Rejected (like a wrong password), leaving A's delete-state
+        // machine intact. The old behavior (clearing the marker) cancelled A's account-delete reconcile.
         val dir = tmp.newFolder()
         val s = store(dir)
         s.create("passA", "A".toByteArray(Charsets.UTF_8))
         s.markDeleteIntent()
-        assertTrue(File(dir, "vault.delete-intent").exists())
-        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = true) is UnlockOrAdd.Created)
-        assertFalse("create clears the stale intent marker", File(dir, "vault.delete-intent").exists())
+        val before = bin(dir).readBytes()
+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
+        assertTrue("intent marker is NOT cleared", File(dir, "vault.delete-intent").exists())
+        assertArrayEquals("nothing written on the fail-closed reject", before, bin(dir).readBytes())
+        // And passB did not create a vault: after retiring the marker, the pool is unchanged.
+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
+    }
+
+    @Test
+    fun create_failsClosed_whenServerDeleteConfirmedPresent() {
+        // The confirmed marker is the sole authorization for boot-time auto-destroy; a create must never
+        // clear it (that would strand a server-deleted account's forensic image).
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        s.markServerDeleteConfirmed()
+        val before = bin(dir).readBytes()
+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
+        assertTrue("confirmed marker is NOT cleared", File(dir, "vault.delete-confirmed").exists())
+        assertArrayEquals(before, bin(dir).readBytes())
+    }
+
+    @Test
+    fun create_selfVerifiesTheSealedSlot_throwsAndPersistsNothing_onAMisSealingProvider() {
+        // B2: a miscomputing aeadEncrypt (size-correct, wrong-content wrapped key) must be caught by the
+        // candidate self-verify BEFORE anything is persisted — otherwise the new vault would be written
+        // durably yet be permanently unopenable after process death.
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        val misSealing = MisSealingWrappedKeyOps(realOps)
+        val s = store(dir, ops = misSealing)
+        s.open()
+        val before = bin(dir).readBytes()
+        assertThrows(IllegalStateException::class.java) {
+            s.attemptUnlockOrAdd("passB", genesis, create = true)
+        }
+        assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
     }
 
     // ─────────────────────────── durability ───────────────────────────
@@ -309,7 +347,8 @@ class AttemptUnlockOrAddTest {
             call(s)
             assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
             assertEquals("$outcome: exactly one 256 KiB payload GCM", 1, counting.payloadOps)
-            assertEquals("$outcome: 5 wrapped-key GCM (4 unwrap + 1 seal)", 5, counting.wrappedOps)
+            // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
+            assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
             val expectedOuter = if (outcome == "create") 1 else 0
             assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
         }
@@ -435,4 +474,22 @@ class AttemptUnlockOrAddTest {
             inner.aeadDecrypt(key, box, associatedData)
         override fun randomBytes(length: Int) = if (length == 4) forced.copyOf() else inner.randomBytes(length)
     }
+
+    /**
+     * Miscomputes ONLY the wrapped-key layer (`SLOT_AD`): returns a size-correct but bit-flipped wrapped
+     * blob so it no longer decrypts back to the vault key. Every other AEAD op (payload, outer image) is
+     * the real byte path, so the store opens/reads normally — the defect surfaces only at the candidate
+     * self-verify (B2).
+     */
+    private class MisSealingWrappedKeyOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
+        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
+        override fun randomBytes(length: Int) = inner.randomBytes(length)
+        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
+            inner.aeadDecrypt(key, box, associatedData)
+        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
+            val out = inner.aeadEncrypt(key, plaintext, associatedData)
+            if (associatedData.contentEquals(SLOT_AD)) out[out.size - 1] = (out[out.size - 1].toInt() xor 0x01).toByte()
+            return out
+        }
+    }
 }
diff --git a/apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt b/apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt
index 0ac4378..dd84d1c 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt
@@ -30,22 +30,22 @@ class BiometricUnlockStoreTest {
         assertFalse(s.isEnabled())
         assertNull(s.load())
 
-        val w = wrap(0)
+        val w = wrap(1) // a VAULT-POOL slot; slot 0 is the burn credential, not biometric-wrappable (F9)
         s.save(w)
         assertTrue(s.isEnabled())
         val loaded = s.load()!!
-        assertEquals(0, loaded.slotIndex)
+        assertEquals(1, loaded.slotIndex)
         assertArrayEquals(w.blob, loaded.blob)
     }
 
     @Test
     fun `a tampered out-of-range slot reads as not-enabled and never reaches unlockWithKey`() {
-        // A corrupted/tampered prefs int (slot >= SLOT_COUNT, or negative) must read as "not
-        // enabled" here, NOT be handed to unlockWithKey's require(slotIndex in 0 until SLOT_COUNT)
-        // where it would crash the unlock coroutine.
+        // A corrupted/tampered prefs int (slot >= SLOT_COUNT, negative, OR slot 0 = the burn credential)
+        // must read as "not enabled" here, NOT be handed to unlockWithKey's require(slotIndex in
+        // VAULT_SLOT_RANGE) where it would crash the unlock coroutine.
         val prefs = FakeSharedPreferences()
         val s = BiometricUnlockStore(prefs)
-        s.save(wrap(0))
+        s.save(wrap(1))
         assertTrue(s.isEnabled())
 
         // Tamper the persisted slot to an out-of-range value.
@@ -56,6 +56,11 @@ class BiometricUnlockStoreTest {
         prefs.edit().putInt("biometric_vault_slot", -1).apply()
         assertFalse(s.isEnabled())
         assertNull(s.load())
+
+        // Slot 0 (burn) is not a biometric-wrappable vault slot (F9): tampering to it reads not-enabled.
+        prefs.edit().putInt("biometric_vault_slot", 0).apply()
+        assertFalse("slot 0 (burn) is not enabled", s.isEnabled())
+        assertNull("slot 0 loads null (never reaches unlockWithKey)", s.load())
     }
 
     @Test
@@ -66,7 +71,7 @@ class BiometricUnlockStoreTest {
         // drive. Two shapes: non-base64 junk, and valid base64 of the wrong length.
         val prefs = FakeSharedPreferences()
         val s = BiometricUnlockStore(prefs)
-        s.save(wrap(0))
+        s.save(wrap(1))
         assertTrue(s.isEnabled())
 
         // Corrupt the blob to non-base64 junk while the slot stays in range.
diff --git a/apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt b/apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt
index 64ae663..58225c5 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt
@@ -7,6 +7,7 @@ package com.zitrone.app
 
 import com.goterl.lazysodium.SodiumJava
 import com.zitrone.app.crypto.vault.AEAD_TAG_BYTES
+import com.zitrone.app.crypto.vault.BURN_SLOT_INDEX
 import com.zitrone.app.crypto.vault.DeviceKeyCipher
 import com.zitrone.app.crypto.vault.DirSyncResult
 import com.zitrone.app.crypto.vault.IMAGE_BYTES
@@ -20,6 +21,7 @@ import com.zitrone.app.crypto.vault.SLOT_COUNT
 import com.zitrone.app.crypto.vault.SLOT_PAYLOAD_BYTES
 import com.zitrone.app.crypto.vault.VAULT_IMAGE_OUTER_AD
 import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
+import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
 import com.zitrone.app.crypto.vault.VaultImageException
 import com.zitrone.app.crypto.vault.VaultImageStore
 import com.zitrone.app.crypto.vault.VaultSession
@@ -201,12 +203,15 @@ class VaultImageStoreTest {
         keyInput.fill(0)
         assertFalse("returned key is an independent copy", opened.vaultKey.all { it == 0.toByte() })
 
-        // Wrong index (a filler region) → null; wrong key → null.
-        val wrongIndex = (0 until SLOT_COUNT).first { it != slotIndex }
+        // Wrong POOL index (a filler region) → null; wrong key → null.
+        val wrongIndex = VAULT_SLOT_RANGE.first { it != slotIndex }
         assertNull("wrong index yields null", store.unlockWithKey(open.vaultKey, wrongIndex))
         assertNull("wrong key yields null", store.unlockWithKey(ByteArray(VAULT_KEY_BYTES) { 0x42 }, slotIndex))
 
-        // Out-of-range index is a caller bug, not a corruption surface.
+        // Slot 0 (burn) is NOT a vault-open target (F9): a caller bug, never a corruption/open surface —
+        // so a future biometric wrap naming slot 0 can't surface the burn payload as a vault.
+        assertThrows(IllegalArgumentException::class.java) { store.unlockWithKey(open.vaultKey, BURN_SLOT_INDEX) }
+        // Out-of-range index is likewise a caller bug.
         assertThrows(IllegalArgumentException::class.java) { store.unlockWithKey(open.vaultKey, SLOT_COUNT) }
     }
 
diff --git a/docs/SECURITY_MODEL.md b/docs/SECURITY_MODEL.md
index 5ef1b11..e41789a 100644
--- a/docs/SECURITY_MODEL.md
+++ b/docs/SECURITY_MODEL.md
@@ -452,6 +452,16 @@ Two VeraCrypt-analogous caveats apply, and are accepted deliberately:
   destroy a vault whose passphrase is not currently entered, exactly as writing to a VeraCrypt
   outer volume without mounting the hidden one can. Creating a vault on a device that may hold
   others is a deliberate, documented risk.
+- **Vault creation silently fails while an account deletion is pending (0.9.2, Android).** Account
+  deletion is a durable two-phase state machine (a `delete-intent` marker, then a `delete-confirmed`
+  marker). While either marker is present, attempting to create a new vault does nothing and is
+  reported exactly like a wrong passphrase — indistinguishable in behaviour and timing. This is a
+  deliberate fail-closed choice: with a live image on disk, nothing observable can tell a *stale*
+  marker (cleanup that did not finish) from a *live* one (a deletion still owed), so vault creation
+  never acts on that distinction rather than risk cancelling a real account deletion or stranding a
+  server-deleted account's local image. The condition is rare and transient (it clears when the
+  deletion completes or is retired), and it leaks nothing — an observer cannot distinguish it from an
+  ordinary failed unlock.
 
 **Per-device scope, and why Android-only is safe.** Plausible-deniability (decoy)
 vaults are a **per-device** feature. Because each install is an independent

codex
The delta’s core control flow is now visible. I’m tracing all marker filesystem references and biometric migration/call sites next; this is where a locally correct guard can still create an upgrade regression.
exec
/bin/bash -lc "rg -n \"deleteIntentFile|serverDeletedFile|vault\\.delete-(intent|confirmed)|clearBothMarkersDurably|clearDeleteIntent|markDeleteIntent|markServerDeleteConfirmed|attemptUnlockOrAdd|unlockWithKey|BiometricUnlockStore|createVaultSlots|LegacyImage|LEGACY_IMAGE_VERSION|VAULT_SLOT_RANGE|randomVaultSlotIndex\" apps/android/app/src/main/java apps/android/app/src/test/java docs/SECURITY_MODEL.md && nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '1,860p' && nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | sed -n '1,230p' && nl -ba apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt" in /root/zitrone
 succeeded in 0ms:
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:24:import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:185:    // ── 3. unlockWithKey (biometric / dual-wrap path) ────────────────────────────
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:188:    fun unlockWithKey_rightKeyAndIndexOpens_wrongNull_inputUntouched_returnsCopy() {
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:197:        val opened = store.unlockWithKey(keyInput, slotIndex)
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:207:        val wrongIndex = VAULT_SLOT_RANGE.first { it != slotIndex }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:208:        assertNull("wrong index yields null", store.unlockWithKey(open.vaultKey, wrongIndex))
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:209:        assertNull("wrong key yields null", store.unlockWithKey(ByteArray(VAULT_KEY_BYTES) { 0x42 }, slotIndex))
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:213:        assertThrows(IllegalArgumentException::class.java) { store.unlockWithKey(open.vaultKey, BURN_SLOT_INDEX) }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:215:        assertThrows(IllegalArgumentException::class.java) { store.unlockWithKey(open.vaultKey, SLOT_COUNT) }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:912:        store.markDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:920:        store.markServerDeleteConfirmed()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:932:    fun markDeleteIntent_and_markServerDeleteConfirmed_throwWhenNotDurable() {
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:937:        assertThrows(VaultImageException.DestroyFailed::class.java) { store.markDeleteIntent() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:938:        assertThrows(VaultImageException.DestroyFailed::class.java) { store.markServerDeleteConfirmed() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:994:        File(dir, "vault.delete-confirmed").createNewFile()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1013:        store.markDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1021:        store.markServerDeleteConfirmed()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1054:        val marker = File(dir, "vault.delete-confirmed").also { it.mkdir() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1068:        File(dir, "vault.delete-confirmed").createNewFile()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1079:    fun clearDeleteIntent_throwsWhenNotDurable_andWhenTheMarkerSurvives() {
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1080:        // Round 14 (F3): clearDeleteIntent checks its dirSync result and re-stats the marker —
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1083:        File(d1, "vault.delete-intent").createNewFile()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1085:        assertThrows(VaultImageException.DestroyFailed::class.java) { s1.clearDeleteIntent() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1089:        val marker = File(d2, "vault.delete-intent").also { it.mkdir() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1092:        assertThrows(VaultImageException.DestroyFailed::class.java) { s2.clearDeleteIntent() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1095:        newStore(tmp.newFolder()).clearDeleteIntent()
apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt:14:import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt:32:class BiometricUnlockStore(private val prefs: SharedPreferences) {
apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt:43:        // read as "not enabled" here, never reach unlockWithKey's require(slotIndex in VAULT_SLOT_RANGE)
apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt:45:        if (slot !in VAULT_SLOT_RANGE) return null
apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:56: *  - the biometric dual-wrap path opens the slot via [VaultImageStore.unlockWithKey], with
apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:165:    // ── #2 biometric dual-wrap: unlockWithKey path ───────────────────────────────
apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:168:    fun `dual-wrap opens the slot via unlockWithKey, wrong key null, invalidated selects passphrase`() {
apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:183:        val reopened = store.unlockWithKey(recovered, open.slotIndex) ?: error("unlockWithKey failed")
apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:187:        assertNull(store.unlockWithKey(ByteArray(VAULT_KEY_BYTES) { 0x00 }, open.slotIndex))
apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:190:        // (it never reaches unlockWithKey). A passphrase unlock still opens the same slot.
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:15:import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:50: * PR-1 tests for [VaultImageStore.attemptUnlockOrAdd] (0.9.2 second vault + Pucker Burn) and the
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:51: * v2→[VaultImageException.LegacyImage] read-path branch + [VaultImageStore.retireLegacyImage].
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:100:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true) // create ignored — match wins
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:110:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:117:        assertTrue(fresh.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:126:        val r = s.attemptUnlockOrAdd("nope", genesis, create = false)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:137:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:169:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:179:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("random", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:199:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:218:            fresh.attemptUnlockOrAdd("passA", genesis, create = false)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:233:            val r = s.attemptUnlockOrAdd("B$it", genesis, create = true) as UnlockOrAdd.Created
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:259:        assertTrue(s.attemptUnlockOrAdd("passA", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:260:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:263:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passA", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:264:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:277:        s.markDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:279:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:280:        assertTrue("intent marker is NOT cleared", File(dir, "vault.delete-intent").exists())
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:283:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:293:        s.markServerDeleteConfirmed()
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:295:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:296:        assertTrue("confirmed marker is NOT cleared", File(dir, "vault.delete-confirmed").exists())
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:312:            s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:326:            s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:329:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:360:            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:363:            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:366:            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:369:            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:375:    fun v2Image_open_throwsLegacyImage_notCorruptImage() {
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:379:        inner[0] = LEGACY_IMAGE_VERSION.toByte() // downgrade the version byte to v2
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:381:        assertThrows(VaultImageException.LegacyImage::class.java) { store(dir).open() }
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:385:    fun isLegacyImage_trueForV2_falseForCurrent() {
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:388:        assertFalse("current version is not legacy", store(dir).isLegacyImage())
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:390:        inner[0] = LEGACY_IMAGE_VERSION.toByte()
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:392:        assertTrue("v2 is legacy", store(dir).isLegacyImage())
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:396:    fun retireLegacyImage_deletesV2_butRefusesToTouchCurrent() {
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:400:        assertThrows(IllegalStateException::class.java) { store(dir).retireLegacyImage() }
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:404:        inner[0] = LEGACY_IMAGE_VERSION.toByte()
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:406:        store(dir).retireLegacyImage()
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:466:     * bytes encoding (targetPoolIndex-1) so `randomVaultSlotIndex` = 1 + ((targetPoolIndex-1) % (N-1)).
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:10:import com.zitrone.app.data.BiometricUnlockStore
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:22:class BiometricUnlockStoreTest {
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:24:    private fun store() = BiometricUnlockStore(FakeSharedPreferences())
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:42:    fun `a tampered out-of-range slot reads as not-enabled and never reaches unlockWithKey`() {
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:44:        // must read as "not enabled" here, NOT be handed to unlockWithKey's require(slotIndex in
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:45:        // VAULT_SLOT_RANGE) where it would crash the unlock coroutine.
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:47:        val s = BiometricUnlockStore(prefs)
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:63:        assertNull("slot 0 loads null (never reaches unlockWithKey)", s.load())
apps/android/app/src/test/java/com/zitrone/app/BiometricUnlockStoreTest.kt:73:        val s = BiometricUnlockStore(prefs)
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:27:import com.zitrone.app.data.BiometricUnlockStore
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:132:    val biometricStore = BiometricUnlockStore(keyStoreManager)
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:168:     * cheap Argon2id-free peek (see [VaultImageStore.isLegacyImage]); call off-main. Safety does NOT
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:169:     * depend on this routing: [VaultImageStore.open] throws [VaultImageException.LegacyImage] before any
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:172:    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:192:    fun markVaultDeleteIntent() = imageStore.markDeleteIntent()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:195:    fun markServerDeleteConfirmed() = imageStore.markServerDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:314:        // fresh v3 vault can be created (create() requires no existing image). retireLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:317:        if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:366:     * [VaultOpen]. The recovered vault key is wiped in `finally` (unlockWithKey holds its own
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:378:            val open = imageStore.unlockWithKey(vaultKey, wrap.slotIndex) ?: return@withContext false
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:493:            persistDeleteIntent = imageStore::markDeleteIntent,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:494:            persistServerDeleteConfirmed = imageStore::markServerDeleteConfirmed,
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:663:    // depend on this — open() throws LegacyImage before any slot interpretation, and onUnlockPassphrase
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:664:    // below also routes LegacyImage to onboarding as a backstop — this just avoids showing a dead lock
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:670:                runCatching { container.isLegacyImage() }.getOrDefault(false)
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:791:                        e is com.zitrone.app.crypto.vault.VaultImageException.LegacyImage -> {
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:119:     * Persist the account-deletion INTENT durably (the `vault.delete-intent` marker) — the FIRST
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:127:     * Persist SERVER-DELETE-CONFIRMED durably (the `vault.delete-confirmed` marker) — written ONLY
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:132:     * [AppContainer.markServerDeleteConfirmed].
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:1429:            // best-effort swallow). Until vault.delete-confirmed is durable, a crash would strand a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:68:     * VALID but PRIOR format version ([LEGACY_IMAGE_VERSION] = v2, the 0.9.1 format).
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:75:     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:76:     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:81:    class LegacyImage : VaultImageException("vault image is a prior, retired format")
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:202: * the outer layer) and [unlockWithKey] does NO Argon2id at all (it opens one payload with
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:261:    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:262:    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:268:     * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:270:     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:275:    fun isLegacyImage(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:276:        imageLock.withLock { readInnerVersionOrNull() == LEGACY_IMAGE_VERSION }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:373:                    //  - [LEGACY_IMAGE_VERSION] (v2, the 0.9.1 format) → [VaultImageException.LegacyImage],
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:376:                    //    caller routes to fresh onboarding; retirement is deliberate ([retireLegacyImage]).
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:381:                        if (innerVersion == LEGACY_IMAGE_VERSION) throw VaultImageException.LegacyImage()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:470:                    Files.notExists(deleteIntentFile.toPath()) &&
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:471:                        Files.notExists(serverDeletedFile.toPath())
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:472:                if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:573:    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:578:            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:580:            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:615:     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:638:     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:642:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:664:                val candSlotIndex = randomVaultSlotIndex(ops)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:707:                        // critical section as the sweep and the write, and markDeleteIntent /
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:708:                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:711:                            Files.notExists(deleteIntentFile.toPath()) &&
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:712:                                Files.notExists(serverDeletedFile.toPath())
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:841:     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:848:     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:859:    fun retireLegacyImage() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:863:            check(version == LEGACY_IMAGE_VERSION) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:864:                "retireLegacyImage refused: not a legacy image (inner version=$version)"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:891:     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:892:     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:952:     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:955:     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:962:    fun markDeleteIntent() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:963:        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:966:    fun markServerDeleteConfirmed() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:967:        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:977:    fun clearDeleteIntent() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:982:            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:983:            deleteIntentFile.delete()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:984:            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:997:    private fun clearBothMarkersDurably(): Boolean {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:998:        deleteIntentFile.delete()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:999:        serverDeletedFile.delete()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1007:            Files.notExists(deleteIntentFile.toPath()) &&
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1008:            Files.notExists(serverDeletedFile.toPath())
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1032:            // means the server account is confirmed gone, so write `vault.delete-confirmed`
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1037:            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1038:            writeDurableMarker(serverDeletedFile)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1077:            if (!clearBothMarkersDurably()) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1084:     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1089:    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1099:        imageLock.withLock { deleteIntentFile.exists() && !serverDeletedFile.exists() }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1122:        imageLock.withLock { !Files.notExists(deleteIntentFile.toPath()) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1239:         * destruction — see [markDeleteIntent] / [deleteIntentPending]. Existence is the only signal.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1241:        const val DELETE_INTENT_FILE = "vault.delete-intent"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1246:         * [markServerDeleteConfirmed] / [serverDeleteConfirmed]. Existence is the only signal.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1248:        const val SERVER_DELETED_FILE = "vault.delete-confirmed"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:39:val VAULT_SLOT_RANGE: IntRange = (BURN_SLOT_INDEX + 1) until SLOT_COUNT
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:44: * ([createVaultSlots]) and blind second-vault creation
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:45: * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:49:fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:50:    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:85: * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:126: * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:130:fun createVaultSlots(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:141:        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:164: * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:165: * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:31: * distinct [VaultImageException.LegacyImage] (route to fresh onboarding + retire), NEVER
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:39:const val LEGACY_IMAGE_VERSION: Int = 2
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:113:    val created = createVaultSlots(passphrase, ops, deriver)
     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
     2	// Licensed under the GNU Affero General Public License v3.0 or later.
     3	// See the LICENSE file in the repository root for full license text.
     4	// SPDX-License-Identifier: AGPL-3.0-only
     5	
     6	// ⚠️ This implementation has not undergone third-party security audit.
     7	// See AUDIT.md in the repository root.
     8	
     9	package com.zitrone.app.crypto.vault
    10	
    11	import java.io.File
    12	import java.io.FileNotFoundException
    13	import java.io.FileOutputStream
    14	import java.io.IOException
    15	import java.nio.file.Files
    16	import java.nio.file.StandardCopyOption
    17	import java.util.concurrent.locks.ReentrantLock
    18	import kotlin.concurrent.withLock
    19	
    20	/**
    21	 * Associated data for the image's OUTER (device-key) layer. A fixed purpose-binding
    22	 * label — the SAME convention as [SLOT_AD] / [PAYLOAD_AD] — that ties the outer
    23	 * ciphertext to its role so an outer blob can never be authenticated under, or
    24	 * reinterpreted as, a different layer's ciphertext. It is a generic, slot-agnostic
    25	 * constant: it names only the layer ("outer"), never a slot, a vault, or real-vs-decoy,
    26	 * so it is byte-identical for every install and reveals nothing. `internal` so the
    27	 * storage tests can decrypt the on-disk blob to assert on inner regions without coupling
    28	 * to a private constant.
    29	 */
    30	internal val VAULT_IMAGE_OUTER_AD: ByteArray = "Zitrone-Vault-Outer-v1".toByteArray(Charsets.UTF_8)
    31	
    32	/**
    33	 * The distinct, non-silently-repaired outcomes of reading the on-disk vault image.
    34	 *
    35	 * A sealed EXCEPTION hierarchy (rather than a returned sealed state) is the cleaner
    36	 * fit for this package: the primitives already fail fast with `require` / `check`
    37	 * and throw, so a corrupt or missing image throws too — a returned state can be
    38	 * ignored, but "NEVER silently repair" must be self-enforcing, and a thrown,
    39	 * exhaustively-`when`-able type gives the caller distinct escalation branches while
    40	 * keeping the happy path (`open()` returns Unit) clean. It is deliberately DISTINCT
    41	 * from the `IllegalStateException` / `IllegalArgumentException` the store throws for
    42	 * caller bugs (writing before open, wrong sizes): those are programming errors,
    43	 * these are environmental/data states the caller must handle.
    44	 *
    45	 * SLOT-AGNOSTIC: the type distinguishes only device-level image presence vs.
    46	 * unreadability — never slot count, occupancy, or "real vs. decoy". The messages
    47	 * name nothing about slots.
    48	 */
    49	sealed class VaultImageException(message: String) : Exception(message) {
    50	    /**
    51	     * No vault image is present (`vault.bin` absent). The caller offers onboarding
    52	     * / creation — this is the fresh-install state, NOT corruption. A stray wrapped
    53	     * DEK with no image (a crash between the store's two writes) also reads as this:
    54	     * the DEK alone protects nothing and is overwritten on the next [VaultImageStore.create].
    55	     */
    56	    class MissingImage : VaultImageException("no vault image present")
    57	
    58	    /**
    59	     * The image is present but unreadable: the outer device-key layer failed to
    60	     * authenticate, the wrapped DEK is missing or unwrappable, or the decrypted
    61	     * inner image is the wrong size. The caller ESCALATES (surfaces an error / halts)
    62	     * — it MUST NOT recreate, which would destroy every real vault behind this image.
    63	     */
    64	    class CorruptImage : VaultImageException("vault image is unreadable")
    65	
    66	    /**
    67	     * The image is present, the outer layer authenticated, and the inner image is a
    68	     * VALID but PRIOR format version ([LEGACY_IMAGE_VERSION] = v2, the 0.9.1 format).
    69	     * This is DISTINCT from [CorruptImage] on purpose and is SAFETY-CRITICAL: a v2 image
    70	     * could hold the everyday vault at slot 0, which v3's burn-slot reservation would
    71	     * misread as a burn credential and WIPE on the user's own correct passphrase. So a v2
    72	     * image is NEVER unlocked, NEVER slot-interpreted, and NEVER auto-destroyed at boot —
    73	     * [open] throws this before any slot material is used, the caller routes to fresh
    74	     * onboarding, and the retirement of the old file happens only on the deliberate
    75	     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
    76	     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
    77	     * recreate). 0.9.1 was fresh-install-only with no real users, so the blast radius is
    78	     * test devices — but "we happened to have no users" is not a safety property, so this
    79	     * fail-closed distinction ships regardless.
    80	     */
    81	    class LegacyImage : VaultImageException("vault image is a prior, retired format")
    82	
    83	    /**
    84	     * A payload write's bytes ARE on disk (the atomic rename — the commit point —
    85	     * landed and its content was fsynced), but the directory-entry fsync that would
    86	     * make the rename itself crash-durable did NOT confirm success — either a real
    87	     * storage error (EIO on an opened directory channel) or a platform that could not
    88	     * open a directory channel at all. Only a confirmed successful directory fsync counts
    89	     * as durable; anything short of that fails CLOSED here rather than risk a false ack.
    90	     * The in-memory [VaultImageStore.canonical] has been ADVANCED to match disk (so no
    91	     * later splice works from stale state), yet the write is NOT confirmed durable — so it
    92	     * is thrown, never returned: a flush-before-ack caller must NOT ack. The session stays
    93	     * dirty and retries; a retry whose dir-fsync succeeds then acks. Distinct from
    94	     * [CorruptImage] — nothing is unreadable; only the rename's durability is unconfirmed.
    95	     */
    96	    class NotDurable : VaultImageException("vault image write not confirmed durable")
    97	
    98	    /**
    99	     * [VaultImageStore.destroy] deleted the files but a re-stat found one of them STILL on disk:
   100	     * [File.delete] returned false because of an I/O / filesystem error (not an already-absent
   101	     * file), so the full-crypto image — the account's identity keypair, ratchet records, and
   102	     * roster — SURVIVES. Account deletion MUST treat this as NOT-deleted: surface an error / retry,
   103	     * never route to Onboarding-as-success (which would tell the user "deleted" while the image
   104	     * remains recoverable). Distinct from the read outcomes above — nothing is unreadable; a
   105	     * removal we asked for did not take. Idempotent-safe: an ALREADY-absent file re-stats absent
   106	     * and does NOT throw, so a retried destroy() over a partially-succeeded one still completes.
   107	     */
   108	    class DestroyFailed : VaultImageException("vault image destruction failed — a file survives")
   109	}
   110	
   111	/**
   112	 * The exact on-disk size of `vault.bin`: the [IMAGE_BYTES] inner image plus the outer
   113	 * AES-256-GCM envelope's [NONCE_BYTES] nonce and [AEAD_TAG_BYTES] tag. A present
   114	 * `vault.bin` of any OTHER length is corruption (a tampered / truncated / inflated
   115	 * file), not a valid image — [VaultImageStore.open] length-checks against this constant
   116	 * BEFORE reading, so an inflated file can never force a giant allocation. `internal` so
   117	 * the storage tests can craft an off-size file to assert on.
   118	 */
   119	internal const val OUTER_IMAGE_BYTES: Int = IMAGE_BYTES + NONCE_BYTES + AEAD_TAG_BYTES
   120	
   121	/**
   122	 * The two durability outcomes of the post-rename directory fsync (see [VaultImageStore]
   123	 * `defaultFsyncDir`). The rename is the COMMIT point — the new image is already on disk and
   124	 * its content already fsynced before the dir-fsync runs — so this result reports only whether
   125	 * the rename's DIRECTORY ENTRY is confirmed crash-durable, never whether the write happened.
   126	 *
   127	 * A rename is NOT guaranteed crash-durable just because the file CONTENT was fsynced: only a
   128	 * successful directory fsync confirms the directory entry itself will survive a crash. So this
   129	 * type is deliberately binary — anything short of a confirmed successful directory fsync is
   130	 * [NOT_DURABLE], so the store can FAIL CLOSED (never falsely report durable) rather than risk a
   131	 * false flush-before-ack.
   132	 *  - [DURABLE]: the directory channel opened AND `force(true)` succeeded — the ONLY confirmed-durable
   133	 *    outcome.
   134	 *  - [NOT_DURABLE]: anything else — the directory channel could not be opened, `force(true)` threw
   135	 *    [IOException] (a real EIO), or there was no directory to sync. The rename's durability is
   136	 *    unconfirmed; the caller must not report the write durable / must not ack.
   137	 * `internal` so the storage tests can inject a forced result to drive each branch.
   138	 */
   139	internal enum class DirSyncResult { DURABLE, NOT_DURABLE }
   140	
   141	/**
   142	 * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
   143	 * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
   144	 * the CALLER learns only which of the four happened, never which slot or how many exist.
   145	 */
   146	sealed interface UnlockOrAdd {
   147	    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
   148	    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
   149	
   150	    /**
   151	     * Slot 0 ([BURN_SLOT_INDEX]) matched — the Pucker Burn duress credential was entered.
   152	     * The APP performs the wipe (a sibling feature); the store performs NO wipe here and
   153	     * exposes nothing about the burn slot's contents or arm-state.
   154	     */
   155	    data object Burn : UnlockOrAdd
   156	
   157	    /** No slot matched AND create was requested — a new vault was created + persisted durably. */
   158	    data class Created(val open: VaultOpen) : UnlockOrAdd
   159	
   160	    /** No slot matched AND create was not requested — an indistinguishable wrong passphrase. */
   161	    data object Rejected : UnlockOrAdd
   162	}
   163	
   164	/**
   165	 * The device-level storage layer for the plausible-deniability vault image. Owns
   166	 * the on-disk canonical image and the envelope that protects it at rest; nothing
   167	 * here knows or reveals how many slots are real.
   168	 *
   169	 * AT-REST ENVELOPE (approved D2, see [DeviceKeyCipher]):
   170	 *  - `vault.bin` = `nonce(12) ‖ AES-256-GCM_DEK(innerImage)` = [IMAGE_BYTES] + 28,
   171	 *    a CONSTANT size, fresh random nonce every write. The inner image is the exact
   172	 *    [IMAGE_BYTES] byte form from [encodeImage] (slot table + payload regions).
   173	 *  - `vault.dek` = the 32-byte DEK wrapped by the hardware device key = a constant
   174	 *    [WRAPPED_KEY_BYTES] (60). Exactly one per install that has an image — constant
   175	 *    evidence that reveals nothing about slot count.
   176	 *
   177	 * The DEK encrypts the ~1 MiB image in-process with the fast portable AES-256-GCM
   178	 * backend, so the hardware-gated Keystore crypto only ever touches the DEK's 32
   179	 * bytes (once per open/create), never the per-flush hot path.
   180	 *
   181	 * SINGLE INSTANCE PER baseDir (load-bearing). AT MOST ONE VaultImageStore per baseDir
   182	 * per process. The [imageLock] serializes calls WITHIN an instance; cross-instance
   183	 * safety is provided by this single-instance rule, which the owner (the app container)
   184	 * guarantees by constructing exactly one store per directory. A second instance opening
   185	 * the SAME directory throws [IllegalStateException] — without this, two stores would
   186	 * hold independent [canonical] snapshots and silently revert each other's writes (the
   187	 * same stale-snapshot hazard the PR-A/PR-B redesign exists to kill), mirroring the
   188	 * 'at most one live session per slot' contract on [VaultSession]. The registration is
   189	 * released by [close], so a new instance may open the directory afterwards.
   190	 *
   191	 * LOCK-ORDER INVARIANT (load-bearing). When composed with [VaultSession] the order
   192	 * is ALWAYS VaultSession.flushLock → [imageLock]: a flush seals under the session's
   193	 * flushLock and only THEN hands the region to [writeSealedPayload], which takes
   194	 * imageLock. NEVER invoke a VaultSession method while holding [imageLock] — that
   195	 * would nest the locks in the reverse order and can deadlock.
   196	 *
   197	 * THREADING. Every method takes [imageLock]; all are synchronous. The Argon2id-heavy
   198	 * methods are [create] — exactly SLOT_COUNT+1 derivations with the production deriver:
   199	 * one to seal the real slot, then SLOT_COUNT more for the verification [unlockImage] —
   200	 * and [unlock], exactly SLOT_COUNT (never fewer: the slot loop has no early exit). Both
   201	 * MUST run off a UI thread. [open] is NOT Argon2id-heavy (a single ~1 MiB AEAD decrypt of
   202	 * the outer layer) and [unlockWithKey] does NO Argon2id at all (it opens one payload with
   203	 * an already-derived key); still, run them off-main so the ~1 MiB decrypt never lands on
   204	 * the UI thread.
   205	 *
   206	 * SLOT-AGNOSTIC discipline: no logging, no strings that name slots / vaults / real /
   207	 * decoy, constant-size writes, and no early exit keyed on slot identity.
   208	 *
   209	 * This is an isolated storage unit: it is deliberately NOT wired into any real app
   210	 * coordinator, DI graph, or migration — that is a later sub-phase.
   211	 *
   212	 * @param baseDir directory the two image files live in (production: `context.filesDir`).
   213	 *   Taken as a bare [File] — no Context dependency — so it is host-unit-testable. baseDir MUST
   214	 *   be app-internal storage (production: `context.filesDir` — ext4/f2fs, where directory fsync is
   215	 *   supported). External/removable storage (FAT32/exFAT) is unsupported BY DESIGN: on filesystems
   216	 *   that cannot fsync a directory the store fails CLOSED (every write reads NOT_DURABLE) rather than
   217	 *   silently weakening the flush-before-ack durability guarantee.
   218	 */
   219	class VaultImageStore internal constructor(
   220	    private val baseDir: File,
   221	    private val ops: VaultSodiumOps,
   222	    private val deviceCipher: DeviceKeyCipher,
   223	    private val deriver: KeyDeriver = argon2idDeriver(ops),
   224	    // Injectable for tests (the package's inject-for-tests convention, as with [ops] /
   225	    // [deriver]): the post-rename directory fsync, factored out so both durability branches
   226	    // (DURABLE / NOT_DURABLE) are host-testable without a real EIO. Production uses
   227	    // [defaultFsyncDir]; tests pass a lambda returning a forced [DirSyncResult].
   228	    //
   229	    // The constructor is `internal` (not the public default) because this last parameter's
   230	    // type mentions the `internal` [DirSyncResult]: rather than leak that durability-only
   231	    // implementation type into the public API, construction is kept module-internal — which
   232	    // is where every caller already lives (the `:app` module's tests and, later, its app
   233	    // container). The class type itself stays public.
   234	    private val dirSync: (File?) -> DirSyncResult = ::defaultFsyncDir,
   235	) {
   236	    /** Serializes every read/write of the on-disk image and the in-memory canonical. */
   237	    private val imageLock = ReentrantLock()
   238	
   239	    /**
   240	     * The current INNER image bytes ([IMAGE_BYTES]: slot table + payload regions),
   241	     * held in memory after [open] / [create]. Ciphertext + salts only — it is NOT a
   242	     * slot's secret plaintext (the outer layer protects it at rest, not on the heap),
   243	     * so it is dropped, not wiped, on [close].
   244	     */
   245	    private var canonical: ByteArray? = null
   246	
   247	    /** The unwrapped 32-byte DEK. Live key material — wiped on [close] and on every
   248	     *  failure path that unwraps it. */
   249	    private var dek: ByteArray? = null
   250	
   251	    /**
   252	     * The canonical directory path this instance has registered in [OPEN_PATHS], or null
   253	     * when it holds no registration. Set by [register] on the first [open] / [create],
   254	     * cleared by [unregister] on [close]. Accessed only under [imageLock]. Enforces the
   255	     * single-instance-per-baseDir contract (see class kdoc).
   256	     */
   257	    private var registeredPath: String? = null
   258	
   259	    private val binFile: File get() = File(baseDir, IMAGE_FILE)
   260	    private val dekFile: File get() = File(baseDir, DEK_FILE)
   261	    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
   262	    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
   263	
   264	    /** True when a vault image is present on disk (`vault.bin`). */
   265	    fun exists(): Boolean = imageLock.withLock { binFile.exists() }
   266	
   267	    /**
   268	     * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
   269	     * signal to send a 0.9.1 install to fresh onboarding instead of the lock screen (see
   270	     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
   271	     * the outer layer and checks the inner version byte only. Returns false for a current-version image,
   272	     * a missing image, or anything unreadable (a corrupt image is NOT "legacy" — it routes through the
   273	     * normal unlock → [CorruptImage] escalation, never to a retire). Does NOT alter store state.
   274	     */
   275	    fun isLegacyImage(): Boolean =
   276	        imageLock.withLock { readInnerVersionOrNull() == LEGACY_IMAGE_VERSION }
   277	
   278	    /**
   279	     * Read `vault.bin` + `vault.dek`, unwrap the DEK, decrypt the outer layer, and
   280	     * hold the validated inner image as [canonical]. A leftover `.tmp` from an
   281	     * interrupted write is deleted first (the main file is the last durable state).
   282	     *
   283	     * Throws [VaultImageException.MissingImage] when no image is present and
   284	     * [VaultImageException.CorruptImage] when it is present but unreadable (outer
   285	     * auth failure, missing / unwrappable DEK, wrong inner size, or an unknown inner
   286	     * [IMAGE_VERSION]). NEVER silently recreates on corruption — that would destroy
   287	     * real vaults; the caller escalates.
   288	     *
   289	     * A raw [IOException] (a transient read error — a failing disk, an I/O fault) is
   290	     * deliberately NOT one of the sealed outcomes: it propagates unmapped so the caller
   291	     * can retry a read that may succeed later. Only a file that VANISHED between the
   292	     * existence check and the read (a TOCTOU race) is mapped into the taxonomy — a gone
   293	     * image reads as MissingImage, a gone DEK as CorruptImage.
   294	     *
   295	     * A FAILED open — including a failed RE-open of an already-open store — leaves the
   296	     * store fully CLOSED: the DEK is wiped, the cached [canonical] is dropped, and the
   297	     * single-instance registration is released. The previously cached image is NEVER
   298	     * served again once the disk has gone Missing/Corrupt, so a later persist can never
   299	     * overwrite a bad on-disk image with stale in-memory data (masking corruption / a
   300	     * rollback). A SUCCESSFUL re-open is unaffected — it is idempotent and re-installs
   301	     * [canonical] from disk.
   302	     */
   303	    fun open() {
   304	        imageLock.withLock {
   305	            // Claim the single-instance registration BEFORE any work so two instances
   306	            // racing on the same dir cannot both proceed. A re-open of THIS instance is
   307	            // idempotent (register() no-ops when we already hold the path).
   308	            register()
   309	            try {
   310	                // A leftover temp is an incomplete write; the main file is authoritative.
   311	                deleteLeftoverTmp(binFile)
   312	                deleteLeftoverTmp(dekFile)
   313	
   314	                // Key on the image file: a stray DEK with no image is the fresh-install /
   315	                // crash-between-writes state (MissingImage), not corruption.
   316	                if (!binFile.exists()) throw VaultImageException.MissingImage()
   317	                if (!dekFile.exists()) throw VaultImageException.CorruptImage()
   318	
   319	                // A PRESENT file of the wrong length is corruption (tampered / truncated /
   320	                // inflated), not "missing" — and length-checking BEFORE readBytes bounds the
   321	                // allocation so an inflated bin can never OOM the process. Use Files.size (which
   322	                // THROWS on a stat failure) rather than File.length (which silently returns 0L on a
   323	                // transient stat error, misreading a valid file as wrong-size → a permanent-looking
   324	                // CorruptImage). A file that VANISHED between the existence check and the stat
   325	                // (NoSuchFileException) is mapped like the readBytes FNF path; any OTHER IOException
   326	                // is a transient stat error and PROPAGATES raw for the caller to retry (same policy
   327	                // as the readBytes IOException path). A size that reads successfully but != the
   328	                // expected constant is CorruptImage as before.
   329	                val dekSize = try {
   330	                    java.nio.file.Files.size(dekFile.toPath())
   331	                } catch (e: java.nio.file.NoSuchFileException) {
   332	                    // A gone dek is always Corrupt (bin already passed its existence check).
   333	                    throw VaultImageException.CorruptImage()
   334	                }
   335	                if (dekSize != WRAPPED_KEY_BYTES.toLong()) throw VaultImageException.CorruptImage()
   336	                val binSize = try {
   337	                    java.nio.file.Files.size(binFile.toPath())
   338	                } catch (e: java.nio.file.NoSuchFileException) {
   339	                    // A truly-gone bin is Missing (bin-keyed); a present-but-unstattable bin is Corrupt.
   340	                    if (binFile.exists()) throw VaultImageException.CorruptImage()
   341	                    else throw VaultImageException.MissingImage()
   342	                }
   343	                if (binSize != OUTER_IMAGE_BYTES.toLong()) throw VaultImageException.CorruptImage()
   344	
   345	                // Map a file that vanished OR became unreadable between the checks and the read
   346	                // into the taxonomy; any OTHER IOException is a transient read error and
   347	                // propagates raw for the caller to retry (see kdoc). A FileNotFoundException is
   348	                // ambiguous — absent OR present-but-unreadable (a directory / a permission
   349	                // fault) — so recheck existence: a still-present dek/bin is Corrupt, a truly
   350	                // gone bin is Missing (a gone dek is always Corrupt, bin already passed exists).
   351	                val dekBlob = try {
   352	                    dekFile.readBytes()
   353	                } catch (e: FileNotFoundException) {
   354	                    throw VaultImageException.CorruptImage()
   355	                }
   356	                val binBytes = try {
   357	                    binFile.readBytes()
   358	                } catch (e: FileNotFoundException) {
   359	                    if (binFile.exists()) throw VaultImageException.CorruptImage()
   360	                    else throw VaultImageException.MissingImage()
   361	                }
   362	
   363	                val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: throw VaultImageException.CorruptImage()
   364	                // From here `unwrapped` is live key material: wipe it on EVERY failure path,
   365	                // and keep it ONLY on the success path (mirrors tryPassphrase's discipline).
   366	                val inner: ByteArray
   367	                try {
   368	                    inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD)
   369	                        ?: throw VaultImageException.CorruptImage()
   370	                    if (inner.size != IMAGE_BYTES) throw VaultImageException.CorruptImage()
   371	                    // Validate the inner VERSION too, not just the size. Three cases (order matters):
   372	                    //  - current [IMAGE_VERSION] → fall through, install as canonical.
   373	                    //  - [LEGACY_IMAGE_VERSION] (v2, the 0.9.1 format) → [VaultImageException.LegacyImage],
   374	                    //    thrown HERE before any slot is decoded/interpreted. SAFETY-CRITICAL: a v2 image
   375	                    //    may hold the everyday vault at slot 0, which v3 would misread as a burn wipe. The
   376	                    //    caller routes to fresh onboarding; retirement is deliberate ([retireLegacyImage]).
   377	                    //  - any OTHER version → [CorruptImage] (unknown/tampered; escalate, never recreate).
   378	                    // A future format bump MUST add its own branch here BEFORE changing [IMAGE_VERSION].
   379	                    val innerVersion = inner[0].toInt() and 0xff
   380	                    if (innerVersion != IMAGE_VERSION) {
   381	                        if (innerVersion == LEGACY_IMAGE_VERSION) throw VaultImageException.LegacyImage()
   382	                        throw VaultImageException.CorruptImage()
   383	                    }
   384	                } catch (t: Throwable) {
   385	                    wipe(unwrapped)
   386	                    throw t
   387	                }
   388	
   389	                // Success: install canonical + DEK, wiping any DEK we already held.
   390	                dek?.let { wipe(it) }
   391	                dek = unwrapped
   392	                canonical = inner
   393	            } catch (t: Throwable) {
   394	                // A failed open — including a failed RE-open of an already-open store — must
   395	                // FULLY invalidate, not just release a freshly-acquired registration. If a
   396	                // re-open finds the disk Missing/Corrupt, retaining the stale canonical would
   397	                // let a later persist overwrite the now-bad image with cached data (masking
   398	                // corruption / a rollback). So drop the DEK + canonical and release the
   399	                // registration UNCONDITIONALLY: the store is left CLOSED and re-openable.
   400	                dek?.let { wipe(it) }
   401	                dek = null
   402	                canonical = null
   403	                unregister()
   404	                throw t
   405	            }
   406	        }
   407	    }
   408	
   409	    /**
   410	     * Create a fresh vault image sealing [initialPayload] under [passphrase], write
   411	     * it durably, and return a live [VaultOpen] for it. Requires no image exists yet.
   412	     *
   413	     * Generates a random DEK, builds the image with the audited [createImage] primitive,
   414	     * and outer-encrypts it. It then VERIFIES the fresh image by [unlockImage]ing it —
   415	     * one extra Argon2id×SLOT_COUNT, one-time at onboarding / migration, reusing the
   416	     * audited primitive rather than adding a new create-and-open surface — BEFORE touching
   417	     * disk: [unlockImage] runs purely on the in-memory image and needs no disk state, so
   418	     * verifying first makes ANY failure in create() (bad wrapped-key size, encrypt /
   419	     * verify / IO failure) leave the disk UNTOUCHED and the whole call retryable.
   420	     *
   421	     * Only then does it write to disk under a DEK-FIRST DURABILITY BARRIER: it renames `vault.dek`
   422	     * into place and CONFIRMS that rename crash-durable (a directory fsync) BEFORE `vault.bin` is
   423	     * ever written; only once the DEK is confirmed durable does it rename `vault.bin` into place and
   424	     * CONFIRM that rename durable too. Success is returned ONLY when BOTH renames are confirmed
   425	     * durable — the IMAGE is fail-closed too, not silently trusted, because it may hold a
   426	     * just-migrated / freshly-generated identity that would be lost for good if a durable create()
   427	     * ack survived a crash that dropped `vault.bin`'s rename. Either confirming fsync failing THROWS
   428	     * [VaultImageException.NotDurable]; there are NO rollback deletes.
   429	     *
   430	     * CRASH-STATE GUARANTEE (the load-bearing invariant). Because the DEK is confirmed durable
   431	     * BEFORE `vault.bin` is written, a crash at ANY point leaves one of only these states — all
   432	     * recoverable, and NEVER a {bin-present, dek-absent} [VaultImageException.CorruptImage] brick:
   433	     *  - no `vault.bin` (with or without a stray `vault.dek`) → [open] = [VaultImageException.MissingImage]
   434	     *    → retry create(), which overwrites any stray dek.
   435	     *  - both present → a COMPLETE, valid, openable vault (the DEK is durable, so it cannot have been
   436	     *    lost) → [open] succeeds.
   437	     * The {bin-present, dek-absent} state is UNREACHABLE by construction: the DEK is durable before
   438	     * `vault.bin` exists, so it can never be lost while `vault.bin` survives — which is exactly why
   439	     * no rollback delete is needed to avoid the brick.
   440	     *
   441	     * CALLER CONTRACT: after a create() throw, re-run [open]. A complete-but-unconfirmed vault opens
   442	     * normally; otherwise [open] reads [VaultImageException.MissingImage] and create() may be
   443	     * retried. create() NEVER leaves a bricked state. Both renames + their confirming fsyncs land
   444	     * before any in-memory state is installed, so a mid-write throw leaves canonical / dek exactly as
   445	     * they were (null on a fresh store). CPU-heavy: caller MUST be off-main.
   446	     */
   447	    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
   448	        imageLock.withLock {
   449	            // Claim the single-instance registration BEFORE any work (mirrors open()); a
   450	            // failed create releases only what THIS call acquired so a retry can proceed.
   451	            val newlyRegistered = registeredPath == null
   452	            register()
   453	            try {
   454	                require(!binFile.exists()) { "vault image already exists" }
   455	                // Clear any STALE delete markers BEFORE writing the successor vault (round 14, F2).
   456	                // A marker resurrected by a journal replay from a PRIOR account's delete would
   457	                // otherwise route this fresh vault to DeleteIncomplete → auto-destroy. Done FIRST
   458	                // (no vault byte written yet) and VERIFIED by re-stat + required dirSync, so:
   459	                //  - a silent File.delete() failure (bool false, marker survives) FAILS create with
   460	                //    nothing on disk — never a successor vault coexisting with a live marker;
   461	                //  - the old post-write ordering window ("vault durable, marker-clear not yet
   462	                //    durable" → crash → successor auto-destroyed) is gone: the markers are proven
   463	                //    absent + durable BEFORE the vault exists.
   464	                // Decide whether to run the clear on a CONSERVATIVE check (round 15, R14-2): run it
   465	                // unless BOTH markers are CONFIRMED absent (Files.notExists). A File.exists()==false
   466	                // from an indeterminate stat must not skip the clear over a present-but-unstatable
   467	                // marker — that is exactly how a stale confirmed marker would coexist with the new
   468	                // vault. The clear itself proves absence via the same tristate + a required fsync.
   469	                val markersConfirmedAbsent =
   470	                    Files.notExists(deleteIntentFile.toPath()) &&
   471	                        Files.notExists(serverDeletedFile.toPath())
   472	                if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
   473	                    throw VaultImageException.NotDurable()
   474	                }
   475	                val newDek = ops.randomBytes(DEK_BYTES)
   476	                // Ephemeral here: the persisted copy lives in `dek`. Wipe the local on EVERY
   477	                // exit, incl. if createImage / encrypt / wrap / verify / write throws mid-way.
   478	                try {
   479	                    val image = createImage(passphrase, initialPayload, ops, deriver)
   480	                    val outer = ops.aeadEncrypt(newDek, image, VAULT_IMAGE_OUTER_AD)
   481	                    val wrappedDek = deviceCipher.wrapDek(newDek)
   482	                    // Store-level constant-blob check: reject a malformed wrapped key from ANY
   483	                    // DeviceKeyCipher impl BEFORE any write, so a bad blob fails create() loudly
   484	                    // instead of persisting and bricking the next open().
   485	                    check(wrappedDek.size == WRAPPED_KEY_BYTES) { "malformed wrapped key" }
   486	
   487	                    // Verify BEFORE writing: unlockImage operates on the in-memory image only, so
   488	                    // proving the fresh image opens before any disk write keeps a failed create()
   489	                    // fully retryable (disk untouched).
   490	                    val liveOpen = unlockImage(passphrase, image, ops, deriver)
   491	                        ?: throw IllegalStateException("freshly created image failed to open")
   492	                    // liveOpen now holds live key material (an independent vault-key copy). If a
   493	                    // write below throws — including the NotDurable rollback throw — wipe it so no
   494	                    // vault key / plaintext is abandoned on the heap (the wipe-on-every-failure
   495	                    // discipline the package keeps).
   496	                    try {
   497	                        // DEK-FIRST DURABILITY BARRIER. Write vault.dek and CONFIRM its rename
   498	                        // crash-durable BEFORE vault.bin is ever written; only then write vault.bin
   499	                        // and confirm ITS rename durable. This makes the {vault.bin present,
   500	                        // vault.dek absent} CorruptImage brick UNREACHABLE by construction: the DEK is
   501	                        // durable before the image exists, so it can never be lost while the image
   502	                        // survives. NO rollback deletes are needed (or performed).
   503	                        renameIntoPlace(dekFile, wrappedDek)
   504	                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   505	                            // The DEK's rename is not confirmed durable → throw BEFORE writing
   506	                            // vault.bin. At most a stray, possibly-not-durable vault.dek exists and NO
   507	                            // vault.bin → open() = MissingImage → a retried create() overwrites it.
   508	                            throw VaultImageException.NotDurable()
   509	                        }
   510	                        renameIntoPlace(binFile, outer)
   511	                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   512	                            // vault.bin's rename is not confirmed durable → throw. The DEK is already
   513	                            // durable, so the on-disk state is either {no bin} (open() = MissingImage
   514	                            // → retry) or a COMPLETE, openable vault — never {bin, no-dek}. No rollback
   515	                            // delete is needed.
   516	                            throw VaultImageException.NotDurable()
   517	                        }
   518	                        // Install in-memory state INSIDE the liveOpen-wipe scope so EVERY throw point
   519	                        // before the return — including the 32-byte newDek.copyOf() (an OOM at this
   520	                        // allocation) — wipes liveOpen.vaultKey / liveOpen.payloadPlaintext rather than
   521	                        // abandoning live key material + plaintext on the heap. The on-disk barrier has
   522	                        // already landed above, so this cannot desync disk from memory; it only advances
   523	                        // the in-memory canonical/dek to match the just-confirmed image.
   524	                        dek?.let { wipe(it) }
   525	                        dek = newDek.copyOf()
   526	                        canonical = image
   527	                        return liveOpen
   528	                    } catch (t: Throwable) {
   529	                        wipe(liveOpen.vaultKey)
   530	                        wipe(liveOpen.payloadPlaintext)
   531	                        throw t
   532	                    }
   533	                } finally {
   534	                    wipe(newDek)
   535	                }
   536	            } catch (t: Throwable) {
   537	                // A failed create must not leave a stale registration — release only what
   538	                // THIS call acquired (an already-registered instance keeps its ownership).
   539	                if (newlyRegistered) unregister()
   540	                throw t
   541	            }
   542	        }
   543	    }
   544	
   545	    /**
   546	     * Attempt [passphrase] against the current image (opening from disk first if
   547	     * needed). Returns a live [VaultOpen] on a match, or null on none — an
   548	     * indistinguishable wrong passphrase. The per-slot Argon2id work is identical
   549	     * whichever slot (or none) matches — the plausible-deniability parity inherited
   550	     * from [unlockImage] / [tryPassphrase]. A SUCCESSFUL unlock additionally opens one
   551	     * fixed-size payload region, so success and failure are not equal-time; that is the
   552	     * same accepted, documented asymmetry as [unlockImage] (it leaks no bit an observer
   553	     * lacks — the app visibly unlocks or not the instant it happens). CPU-heavy: caller
   554	     * MUST be off-main.
   555	     */
   556	    fun unlock(passphrase: String): VaultOpen? {
   557	        imageLock.withLock {
   558	            val image = canonical ?: run { open(); canonical!! }
   559	            return unlockImage(passphrase, image, ops, deriver)
   560	        }
   561	    }
   562	
   563	    /**
   564	     * Open one slot's payload directly with an already-unlocked [vaultKey] (the
   565	     * biometric / dual-wrap path — no passphrase, no Argon2id). Opening from disk
   566	     * first if needed, decrypts `payloads[slotIndex]` with a COPY of [vaultKey] and
   567	     * returns a [VaultOpen] holding that copy; returns null on AEAD failure.
   568	     *
   569	     * ⚠️ OWNERSHIP. The caller retains ownership of its [vaultKey] input and MUST
   570	     * wipe it itself — the store never wipes the caller's array. The returned
   571	     * [VaultOpen] holds an INDEPENDENT copy, so wiping the input does not disturb it.
   572	     */
   573	    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
   574	        imageLock.withLock {
   575	            // Only VAULT-POOL slots (1..SLOT_COUNT-1) are openable this way (F9 / Grok): slot 0 is the burn
   576	            // credential and must NEVER be opened as a vault — a future biometric dual-wrap that named slot
   577	            // 0 would otherwise surface the burn payload as an ordinary unlock instead of triggering a wipe.
   578	            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
   579	            // not-enabled and never reaches here; this require is the store-level backstop.
   580	            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
   581	            val image = canonical ?: run { open(); canonical!! }
   582	            val payload = decodeImage(image).payloads[slotIndex]
   583	            // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
   584	            // caller's input is never touched (it owns and wipes that itself).
   585	            val keyCopy = vaultKey.copyOf()
   586	            val plaintext = try {
   587	                openPayload(keyCopy, payload, ops)
   588	            } catch (t: Throwable) {
   589	                wipe(keyCopy)
   590	                throw t
   591	            }
   592	            if (plaintext == null) {
   593	                wipe(keyCopy)
   594	                return null
   595	            }
   596	            return VaultOpen(keyCopy, slotIndex, plaintext)
   597	        }
   598	    }
   599	
   600	    /**
   601	     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
   602	     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
   603	     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
   604	     * cases apart (the plausible-deniability + duress-credential timing contract):
   605	     *
   606	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
   607	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
   608	     *     (real vault-B material on create, pure timing filler otherwise);
   609	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
   610	     *
   611	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
   612	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
   613	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
   614	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
   615	     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
   616	     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
   617	     * [UnlockOrAdd.Rejected] having written nothing.
   618	     *
   619	     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
   620	     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
   621	     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
   622	     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
   623	     *
   624	     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
   625	     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
   626	     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
   627	     * target, so duress protection survives even a full pool.
   628	     *
   629	     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
   630	     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
   631	     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
   632	     *
   633	     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
   634	     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
   635	     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
   636	     *
   637	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
   638	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   639	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
   640	     * create write is not confirmed durable.
   641	     */
   642	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   643	        imageLock.withLock {
   644	            val image = canonical ?: run { open(); canonical!! }
   645	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   646	            val decoded = decodeImage(image)
   647	
   648	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   649	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   650	
   651	            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
   652	            // the try below so a throw during its generation (native crypto failure, OOM,
   653	            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
   654	            // live matched vault key — neither is covered if candidate generation sits before the try.
   655	            var candKeyForCleanup: ByteArray? = null
   656	            try {
   657	                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
   658	                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
   659	                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
   660	                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
   661	                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
   662	                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
   663	                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
   664	                val candSlotIndex = randomVaultSlotIndex(ops)
   665	                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
   666	
   667	                return when {
   668	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   669	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   670	                        wipe(candKey)
   671	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   672	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   673	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   674	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   675	                            .getOrNull()?.let { wipe(it) }
   676	                        wipe(unlock.vaultKey)
   677	                        UnlockOrAdd.Burn
   678	                    }
   679	
   680	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   681	                    unlock != null -> {
   682	                        wipe(candKey)
   683	                        val pt = try {
   684	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   685	                        } catch (t: Throwable) {
   686	                            wipe(unlock.vaultKey)
   687	                            throw VaultImageException.CorruptImage()
   688	                        }
   689	                        if (pt == null) {
   690	                            wipe(unlock.vaultKey)
   691	                            throw VaultImageException.CorruptImage()
   692	                        }
   693	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   694	                    }
   695	
   696	                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
   697	                    create -> {
   698	                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
   699	                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
   700	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
   701	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
   702	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
   703	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
   704	                        // a throw is an observable side channel precisely when the device is mid-delete) after
   705	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
   706	                        // machine is left completely untouched. This marker check is in the SAME imageLock
   707	                        // critical section as the sweep and the write, and markDeleteIntent /
   708	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
   709	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
   710	                        val markersAbsent =
   711	                            Files.notExists(deleteIntentFile.toPath()) &&
   712	                                Files.notExists(serverDeletedFile.toPath())
   713	                        if (!markersAbsent) {
   714	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
   715	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
   716	                            wipe(candKey)
   717	                            wipe(throwaway)
   718	                            UnlockOrAdd.Rejected
   719	                        } else {
   720	                            // The 1×256 KiB payload GCM for the create branch.
   721	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   722	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   723	                            val newPayloads =
   724	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   725	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   726	                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   727	                            // unreachable by construction; the dek is already durable on disk from create().
   728	                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   729	                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   730	                            // rename landed, the result reporting the rename's durability.
   731	                            val sync = atomicWrite(binFile, outer)
   732	                            // Rename committed → advance canonical BEFORE the durability check so a later
   733	                            // splice/attempt never works from stale state even on the NotDurable throw.
   734	                            canonical = newInner
   735	                            if (sync != DirSyncResult.DURABLE) {
   736	                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   737	                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   738	                                // canonical, so a later single entry of its passphrase unlocks it via the
   739	                                // match path — or, if the rename did not survive a crash, it is simply absent
   740	                                // and re-creatable.
   741	                                wipe(candKey)
   742	                                throw VaultImageException.NotDurable()
   743	                            }
   744	                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   745	                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   746	                        }
   747	                    }
   748	
   749	                    // ── REJECT — no match, no create. Nothing written. ──
   750	                    else -> {
   751	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   752	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   753	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   754	                        wipe(candKey)
   755	                        wipe(throwaway)
   756	                        UnlockOrAdd.Rejected
   757	                    }
   758	                }
   759	            } catch (t: Throwable) {
   760	                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
   761	                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
   762	                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
   763	                candKeyForCleanup?.let { wipe(it) }
   764	                unlock?.let { wipe(it.vaultKey) }
   765	                throw t
   766	            }
   767	        }
   768	    }
   769	
   770	    /**
   771	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   772	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   773	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   774	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   775	     *
   776	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   777	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   778	     * distinct because they leave DIFFERENT state:
   779	     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
   780	     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
   781	     *    the PREVIOUS state (the atomic rename replaces or not at all). Session stays dirty, no ack.
   782	     *  - POST-rename dir-fsync not confirmed ([DirSyncResult.NOT_DURABLE]): the new bytes ARE on
   783	     *    disk (the rename — the commit point — landed and its content was fsynced) but the rename's
   784	     *    own durability is unconfirmed. Only a confirmed successful directory fsync ([DirSyncResult.DURABLE])
   785	     *    is treated as durable; anything else — a real dir-fsync EIO OR a platform that could not open a
   786	     *    directory channel — fails CLOSED here. [canonical] is ADVANCED to match disk (so a later splice
   787	     *    never works from stale state — the write is on disk, just unconfirmed), and a
   788	     *    [VaultImageException.NotDurable] is thrown so the caller does NOT ack. The session stays dirty and
   789	     *    retries; a retry whose dir-fsync succeeds then acks.
   790	     *
   791	     * Never logs, and does identical work regardless of which slot is written.
   792	     */
   793	    fun writeSealedPayload(slotIndex: Int, sealedPayload: ByteArray) {
   794	        imageLock.withLock {
   795	            val current = canonical ?: throw IllegalStateException("vault image not open")
   796	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   797	            require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
   798	            // spliceImagePayload validates slotIndex and returns a NEW array — `current`
   799	            // is untouched, so nothing below can corrupt the live canonical.
   800	            val spliced = spliceImagePayload(current, slotIndex, sealedPayload)
   801	            val outer = ops.aeadEncrypt(activeDek, spliced, VAULT_IMAGE_OUTER_AD)
   802	            // atomicWrite throws ONLY pre-rename (nothing committed, canonical untouched); a
   803	            // RETURN means the rename landed, with the result telling the rename's durability.
   804	            val sync = atomicWrite(binFile, outer)
   805	            // The rename committed → in-memory canonical now matches disk. Advance it BEFORE the
   806	            // durability check so a later splice never works from stale state even on that throw.
   807	            canonical = spliced
   808	            if (sync != DirSyncResult.DURABLE) {
   809	                // On disk but durability NOT confirmed (real dir-fsync EIO, or a platform that
   810	                // could not open a dir channel): only a confirmed dir-fsync counts as durable, so
   811	                // fail CLOSED and throw — a flush-before-ack caller does NOT ack. canonical is
   812	                // already advanced (above), so the session stays dirty and retries; a retry that
   813	                // dir-fsyncs acks.
   814	                throw VaultImageException.NotDurable()
   815	            }
   816	        }
   817	    }
   818	
   819	    /**
   820	     * Wipe the DEK and drop the canonical image. Store open/close is device-level
   821	     * and independent of any vault's lock — the outer layer is not a slot's secret,
   822	     * so keeping the store open across vault locks is fine; this exists for tests /
   823	     * teardown. Idempotent.
   824	     *
   825	     * Also RELEASES this instance's single-instance registration (see class kdoc), so a
   826	     * new VaultImageStore may open the same directory afterwards. A real process restart
   827	     * ends the old process and drops the registration implicitly; a test simulating a
   828	     * restart within one JVM MUST close() the old instance first before constructing the
   829	     * next one on the same baseDir.
   830	     */
   831	    fun close() {
   832	        imageLock.withLock {
   833	            dek?.let { wipe(it) }
   834	            dek = null
   835	            canonical = null
   836	            unregister()
   837	        }
   838	    }
   839	
   840	    /**
   841	     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
   842	     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
   843	     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
   844	     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
   845	     * boot).
   846	     *
   847	     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
   848	     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
   849	     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
   850	     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
   851	     * release the single-instance registration.
   852	     *
   853	     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
   854	     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
   855	     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
   856	     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
   857	     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
   858	     */
   859	    fun retireLegacyImage() {
   860	        imageLock.withLock {
     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
     2	// Licensed under the GNU Affero General Public License v3.0 or later.
     3	// See the LICENSE file in the repository root for full license text.
     4	// SPDX-License-Identifier: AGPL-3.0-only
     5	
     6	package com.zitrone.app.crypto.vault
     7	
     8	/**
     9	 * Slot operations — an exact Kotlin mirror of the functions in
    10	 * packages/crypto/src/vault.ts. Every function is slot-agnostic: nothing is
    11	 * named "real" or "decoy", nothing is logged, and the code path for a filler
    12	 * slot is byte-for-byte the same as for a real one.
    13	 */
    14	
    15	/** Holder for a freshly created / added vault, mirroring vault.ts's return shapes. */
    16	class CreatedVault(
    17	    val slots: List<KeySlot>,
    18	    val vaultKey: ByteArray,
    19	    val slotIndex: Int,
    20	)
    21	
    22	/**
    23	 * Slot 0 is RESERVED for the Pucker Burn duress credential (0.9.2). It is sealed
    24	 * byte-identically to any vault slot — same Argon2id, same structure, same timing —
    25	 * so an examiner cannot tell from structure whether it is armed; only a MATCH on it
    26	 * triggers a wipe (handled by the store/app), never an unlock. Arm-state is stored
    27	 * NOWHERE: "armed" simply means a passphrase can match slot 0, exactly what
    28	 * [tryPassphrase] already tests, so an unarmed slot 0 is uniformly-random filler,
    29	 * indistinguishable from a real one.
    30	 *
    31	 * The reservation is a placement-only convention (the byte format is unchanged): no
    32	 * everyday vault and no created vault ever lands here, so vault creation can never
    33	 * clobber the burn credential. This is an ACCEPTED, documented disclosure — it reveals
    34	 * only that a burn FEATURE exists (public), never how many vaults slots 1..N-1 hold.
    35	 */
    36	const val BURN_SLOT_INDEX: Int = 0
    37	
    38	/** The vault pool — slots that may hold a real vault. Slot 0 (burn) is excluded. */
    39	val VAULT_SLOT_RANGE: IntRange = (BURN_SLOT_INDEX + 1) until SLOT_COUNT
    40	
    41	/**
    42	 * A uniformly-random index into the VAULT pool (never [BURN_SLOT_INDEX]). The SINGLE
    43	 * source of truth for slot-0 reservation, used by BOTH the everyday-vault placement
    44	 * ([createVaultSlots]) and blind second-vault creation
    45	 * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
    46	 * (plus one integer add), so it carries no timing/I-O signature distinct from ordinary
    47	 * placement.
    48	 */
    49	fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
    50	    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)
    51	
    52	/**
    53	 * A filler slot: a random salt and random bytes the exact length of a real
    54	 * wrapped key. Indistinguishable from an occupied slot. No passphrase will ever
    55	 * unwrap it (a random 16-byte tail is a valid GCM tag with probability 2^-128).
    56	 */
    57	fun randomSlot(ops: VaultSodiumOps): KeySlot =
    58	    KeySlot(salt = ops.randomBytes(SALT_BYTES), wrapped = ops.randomBytes(WRAPPED_KEY_BYTES))
    59	
    60	/** Wrap a vault key under a passphrase, producing a real, unlockable slot. */
    61	fun sealSlot(
    62	    passphrase: String,
    63	    vaultKey: ByteArray,
    64	    ops: VaultSodiumOps,
    65	    deriver: KeyDeriver = argon2idDeriver(ops),
    66	): KeySlot {
    67	    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
    68	    val salt = ops.randomBytes(SALT_BYTES)
    69	    val masterKey = deriver(passphrase, salt)
    70	    try {
    71	        val wrapped = ops.aeadEncrypt(masterKey, vaultKey, SLOT_AD)
    72	        return KeySlot(salt = salt, wrapped = wrapped)
    73	    } finally {
    74	        wipe(masterKey)
    75	    }
    76	}
    77	
    78	/**
    79	 * Like [sealSlot] but SELF-VERIFYING: immediately after wrapping, it decrypts the wrapped key back under
    80	 * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
    81	 * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
    82	 * lifetime is identical to [sealSlot]'s.
    83	 *
    84	 * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
    85	 * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
    86	 * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
    87	 * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
    88	 * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
    89	 * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
    90	 * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
    91	 * would equally break every other slot operation; failing closed here is correct.
    92	 */
    93	fun sealSlotSelfVerifying(
    94	    passphrase: String,
    95	    vaultKey: ByteArray,
    96	    ops: VaultSodiumOps,
    97	    deriver: KeyDeriver = argon2idDeriver(ops),
    98	): KeySlot {
    99	    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
   100	    val salt = ops.randomBytes(SALT_BYTES)
   101	    val masterKey = deriver(passphrase, salt)
   102	    try {
   103	        val wrapped = ops.aeadEncrypt(masterKey, vaultKey, SLOT_AD)
   104	        val recovered = ops.aeadDecrypt(masterKey, wrapped, SLOT_AD)
   105	            ?: throw IllegalStateException("sealed slot failed self-verify (wrapped key did not unwrap)")
   106	        try {
   107	            // Constant-time equality (both are VAULT_KEY_BYTES) — MessageDigest.isEqual is the platform
   108	            // constant-time compare. A mismatch means the AEAD provider does not round-trip: fail closed.
   109	            check(java.security.MessageDigest.isEqual(recovered, vaultKey)) {
   110	                "sealed slot failed self-verify (recovered key mismatch)"
   111	            }
   112	        } finally {
   113	            wipe(recovered)
   114	        }
   115	        return KeySlot(salt = salt, wrapped = wrapped)
   116	    } finally {
   117	        wipe(masterKey)
   118	    }
   119	}
   120	
   121	/**
   122	 * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
   123	 * real vault sealed under [passphrase]. The rest are random filler. The returned
   124	 * vaultKey is the random key the caller should use to encrypt the vault's data.
   125	 * The real slot is placed at a CSPRNG-random index IN THE VAULT POOL
   126	 * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
   127	 * stays reserved for the burn credential (see [BURN_SLOT_INDEX]). Slot 0 is left as
   128	 * filler on a fresh onboarding (unarmed burn), indistinguishable from any other slot.
   129	 */
   130	fun createVaultSlots(
   131	    passphrase: String,
   132	    ops: VaultSodiumOps,
   133	    deriver: KeyDeriver = argon2idDeriver(ops),
   134	): CreatedVault {
   135	    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
   136	    // On SUCCESS the caller owns (and later wipes) vaultKey; on ANY failure path
   137	    // after generation, wipe it here so no live key is abandoned in heap.
   138	    try {
   139	        val slots = ArrayList<KeySlot>(SLOT_COUNT)
   140	        for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
   141	        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
   142	        slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
   143	        return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
   144	    } catch (t: Throwable) {
   145	        wipe(vaultKey)
   146	        throw t
   147	    }
   148	}
   149	
   150	/**
   151	 * Seal a second (or third…) vault into a currently-unoccupied slot. The new
   152	 * vault gets its own independent random vault key — vaults share no key
   153	 * material. The slot chosen is a random currently-unoccupied one so the layout
   154	 * still reveals nothing. Throws if every slot is occupied.
   155	 *
   156	 * [occupied] is supplied by the caller because the stored material deliberately
   157	 * cannot reveal which slots hold real vaults (that is the whole point). Passing
   158	 * an empty set reproduces the web's overwrite-tolerant behavior (storage.ts
   159	 * createVault, the documented VeraCrypt outer-volume tradeoff); passing the
   160	 * known-occupied indices avoids clobbering a live vault.
   161	 *
   162	 * ⚠️ BURN-UNAWARE (0.9.2): this primitive picks freely over ALL slots incl.
   163	 * [BURN_SLOT_INDEX], so it must NOT be wired into a creation path without excluding
   164	 * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
   165	 * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
   166	 * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
   167	 * as the web-mirrored primitive + tests only.
   168	 */
   169	fun addVaultSlot(
   170	    slots: List<KeySlot>,
   171	    occupied: Set<Int>,
   172	    passphrase: String,
   173	    ops: VaultSodiumOps,
   174	    deriver: KeyDeriver = argon2idDeriver(ops),
   175	): CreatedVault {
   176	    // Reject a passphrase that already unlocks an existing vault: tryPassphrase
   177	    // returns only the FIRST matching slot, so a second seal under the same
   178	    // passphrase would shadow one vault and silently make it unreachable.
   179	    tryPassphrase(passphrase, slots, ops, deriver)?.let {
   180	        wipe(it.vaultKey)
   181	        throw IllegalArgumentException("passphrase already unlocks an existing vault")
   182	    }
   183	    val free = ArrayList<Int>()
   184	    for (i in slots.indices) if (i !in occupied) free.add(i)
   185	    if (free.isEmpty()) throw IllegalStateException("no free key slots")
   186	    val slotIndex = free[randomIndex(free.size, ops)]
   187	    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
   188	    try {
   189	        val next = slots.toMutableList()
   190	        next[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
   191	        return CreatedVault(slots = next, vaultKey = vaultKey, slotIndex = slotIndex)
   192	    } catch (t: Throwable) {
   193	        wipe(vaultKey)
   194	        throw t
   195	    }
   196	}
   197	
   198	/**
   199	 * Attempt a passphrase against all slots. Returns the unlocked vault key, or
   200	 * null if no slot matched (indistinguishable from a wrong passphrase).
   201	 *
   202	 * Derive+attempt EVERY slot, never break, so wall-clock timing is identical
   203	 * whether a passphrase matches slot 0, slot N, or nothing — a break here is a
   204	 * plausible-deniability side-channel. The first match is recorded but the loop
   205	 * runs to completion regardless; any later match's vault key is wiped, and every
   206	 * derived master key is wiped whether it matched or not.
   207	 *
   208	 * CPU-HEAVY: SLOT_COUNT × Argon2id (64 MiB each) with the production deriver.
   209	 * Callers on a UI thread MUST run this off the main thread.
   210	 */
   211	fun tryPassphrase(
   212	    passphrase: String,
   213	    slots: List<KeySlot>,
   214	    ops: VaultSodiumOps,
   215	    deriver: KeyDeriver = argon2idDeriver(ops),
   216	): VaultUnlock? {
   217	    var found: VaultUnlock? = null
   218	    try {
   219	        for (i in slots.indices) {
   220	            val slot = slots[i]
   221	            val masterKey = deriver(passphrase, slot.salt)
   222	            try {
   223	                val vaultKey = ops.aeadDecrypt(masterKey, slot.wrapped, SLOT_AD)
   224	                if (vaultKey != null) {
   225	                    // Record the first match but DO NOT break — every slot is
   226	                    // always derived and tried.
   227	                    if (found == null) found = VaultUnlock(vaultKey, i) else wipe(vaultKey)
   228	                }
   229	            } finally {
   230	                wipe(masterKey)
     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
     2	// Licensed under the GNU Affero General Public License v3.0 or later.
     3	// See the LICENSE file in the repository root for full license text.
     4	// SPDX-License-Identifier: AGPL-3.0-only
     5	
     6	// ⚠️ This implementation has not undergone third-party security audit.
     7	// See AUDIT.md in the repository root.
     8	
     9	package com.zitrone.app.data
    10	
    11	import android.content.SharedPreferences
    12	import com.zitrone.app.crypto.KeyStoreManager
    13	import com.zitrone.app.crypto.vault.BiometricWrappedKey
    14	import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
    15	import java.util.Base64
    16	
    17	/**
    18	 * Persistence for the biometric dual-wrap (posture B): the `{ slotIndex, wrappedVaultKey
    19	 * blob }` pair, in [KeyStoreManager.PREFS_SETTINGS] under new keys. The blob exists ONLY
    20	 * for a biometric-enabled install — its mere presence is the accepted evidence posture
    21	 * ("app biometric on"), and it reveals NOTHING about slot B; the slot index it stores is
    22	 * slot A's, the only real slot in D2c.
    23	 *
    24	 * The persisted blob is a constant [BiometricWrappedKey.BLOB_BYTES] (60), base64-wrapped;
    25	 * nothing here is ever logged. This class holds only the wrapped ciphertext, never a live
    26	 * vault key — the wrap/unwrap crypto lives in
    27	 * [com.zitrone.app.crypto.vault.BiometricVaultKeyCipher].
    28	 *
    29	 * The [prefs] constructor is the seam under test; the [KeyStoreManager] convenience
    30	 * constructor is what production wires (the same PREFS_SETTINGS file the device settings use).
    31	 */
    32	class BiometricUnlockStore(private val prefs: SharedPreferences) {
    33	
    34	    constructor(keyStoreManager: KeyStoreManager) :
    35	        this(keyStoreManager.prefs(KeyStoreManager.PREFS_SETTINGS))
    36	
    37	    /** The stored wrap, or null when biometric unlock is not enabled (or the blob is off-shape). */
    38	    fun load(): BiometricWrappedKey? {
    39	        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
    40	        val slot = prefs.getInt(KEY_SLOT, -1)
    41	        // Validate against the VAULT POOL (1..SLOT_COUNT-1), not just >= 0: a corrupted/tampered prefs int
    42	        // — including slot 0, the burn credential, which is NOT a biometric-wrappable vault (F9) — must
    43	        // read as "not enabled" here, never reach unlockWithKey's require(slotIndex in VAULT_SLOT_RANGE)
    44	        // and crash the unlock coroutine. Biometric is A-only, and A always lives in the pool.
    45	        if (slot !in VAULT_SLOT_RANGE) return null
    46	        val blob = try {
    47	            Base64.getDecoder().decode(encoded)
    48	        } catch (e: IllegalArgumentException) {
    49	            return null
    50	        }
    51	        if (blob.size != BiometricWrappedKey.BLOB_BYTES) return null
    52	        return BiometricWrappedKey(slot, blob)
    53	    }
    54	
    55	    /**
    56	     * True only when a VALID wrap is present (biometric unlock enabled). Delegates to [load] so a
    57	     * present-but-malformed blob (bad base64 / wrong length) or an out-of-range slot reads as NOT
    58	     * enabled — otherwise the lock screen would advertise a biometric button that [load] resolves
    59	     * to null and cannot actually drive (it would silently drop to the passphrase either way).
    60	     */
    61	    fun isEnabled(): Boolean = load() != null
    62	
    63	    /** Persist a fresh wrap (enable / re-enable). Constant-size; never logged. */
    64	    fun save(wrap: BiometricWrappedKey) {
    65	        prefs.edit()
    66	            .putInt(KEY_SLOT, wrap.slotIndex)
    67	            .putString(KEY_BLOB, Base64.getEncoder().encodeToString(wrap.blob))
    68	            .apply()
    69	    }
    70	
    71	    /** Drop the wrap (disable / invalidation). Idempotent. */
    72	    fun clear() {
    73	        prefs.edit().remove(KEY_SLOT).remove(KEY_BLOB).apply()
    74	    }
    75	
    76	    private companion object {
    77	        const val KEY_SLOT = "biometric_vault_slot"
    78	        const val KEY_BLOB = "biometric_vault_blob"
    79	    }
    80	}

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '430,780p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '840,1100p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt | sed -n '280,410p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt | sed -n '630,820p'; rg -n \"attemptUnlockOrAdd\\(\" apps/android/app/src/main/java -C 8" in /root/zitrone
 succeeded in 0ms:
   430	     * CRASH-STATE GUARANTEE (the load-bearing invariant). Because the DEK is confirmed durable
   431	     * BEFORE `vault.bin` is written, a crash at ANY point leaves one of only these states — all
   432	     * recoverable, and NEVER a {bin-present, dek-absent} [VaultImageException.CorruptImage] brick:
   433	     *  - no `vault.bin` (with or without a stray `vault.dek`) → [open] = [VaultImageException.MissingImage]
   434	     *    → retry create(), which overwrites any stray dek.
   435	     *  - both present → a COMPLETE, valid, openable vault (the DEK is durable, so it cannot have been
   436	     *    lost) → [open] succeeds.
   437	     * The {bin-present, dek-absent} state is UNREACHABLE by construction: the DEK is durable before
   438	     * `vault.bin` exists, so it can never be lost while `vault.bin` survives — which is exactly why
   439	     * no rollback delete is needed to avoid the brick.
   440	     *
   441	     * CALLER CONTRACT: after a create() throw, re-run [open]. A complete-but-unconfirmed vault opens
   442	     * normally; otherwise [open] reads [VaultImageException.MissingImage] and create() may be
   443	     * retried. create() NEVER leaves a bricked state. Both renames + their confirming fsyncs land
   444	     * before any in-memory state is installed, so a mid-write throw leaves canonical / dek exactly as
   445	     * they were (null on a fresh store). CPU-heavy: caller MUST be off-main.
   446	     */
   447	    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
   448	        imageLock.withLock {
   449	            // Claim the single-instance registration BEFORE any work (mirrors open()); a
   450	            // failed create releases only what THIS call acquired so a retry can proceed.
   451	            val newlyRegistered = registeredPath == null
   452	            register()
   453	            try {
   454	                require(!binFile.exists()) { "vault image already exists" }
   455	                // Clear any STALE delete markers BEFORE writing the successor vault (round 14, F2).
   456	                // A marker resurrected by a journal replay from a PRIOR account's delete would
   457	                // otherwise route this fresh vault to DeleteIncomplete → auto-destroy. Done FIRST
   458	                // (no vault byte written yet) and VERIFIED by re-stat + required dirSync, so:
   459	                //  - a silent File.delete() failure (bool false, marker survives) FAILS create with
   460	                //    nothing on disk — never a successor vault coexisting with a live marker;
   461	                //  - the old post-write ordering window ("vault durable, marker-clear not yet
   462	                //    durable" → crash → successor auto-destroyed) is gone: the markers are proven
   463	                //    absent + durable BEFORE the vault exists.
   464	                // Decide whether to run the clear on a CONSERVATIVE check (round 15, R14-2): run it
   465	                // unless BOTH markers are CONFIRMED absent (Files.notExists). A File.exists()==false
   466	                // from an indeterminate stat must not skip the clear over a present-but-unstatable
   467	                // marker — that is exactly how a stale confirmed marker would coexist with the new
   468	                // vault. The clear itself proves absence via the same tristate + a required fsync.
   469	                val markersConfirmedAbsent =
   470	                    Files.notExists(deleteIntentFile.toPath()) &&
   471	                        Files.notExists(serverDeletedFile.toPath())
   472	                if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
   473	                    throw VaultImageException.NotDurable()
   474	                }
   475	                val newDek = ops.randomBytes(DEK_BYTES)
   476	                // Ephemeral here: the persisted copy lives in `dek`. Wipe the local on EVERY
   477	                // exit, incl. if createImage / encrypt / wrap / verify / write throws mid-way.
   478	                try {
   479	                    val image = createImage(passphrase, initialPayload, ops, deriver)
   480	                    val outer = ops.aeadEncrypt(newDek, image, VAULT_IMAGE_OUTER_AD)
   481	                    val wrappedDek = deviceCipher.wrapDek(newDek)
   482	                    // Store-level constant-blob check: reject a malformed wrapped key from ANY
   483	                    // DeviceKeyCipher impl BEFORE any write, so a bad blob fails create() loudly
   484	                    // instead of persisting and bricking the next open().
   485	                    check(wrappedDek.size == WRAPPED_KEY_BYTES) { "malformed wrapped key" }
   486	
   487	                    // Verify BEFORE writing: unlockImage operates on the in-memory image only, so
   488	                    // proving the fresh image opens before any disk write keeps a failed create()
   489	                    // fully retryable (disk untouched).
   490	                    val liveOpen = unlockImage(passphrase, image, ops, deriver)
   491	                        ?: throw IllegalStateException("freshly created image failed to open")
   492	                    // liveOpen now holds live key material (an independent vault-key copy). If a
   493	                    // write below throws — including the NotDurable rollback throw — wipe it so no
   494	                    // vault key / plaintext is abandoned on the heap (the wipe-on-every-failure
   495	                    // discipline the package keeps).
   496	                    try {
   497	                        // DEK-FIRST DURABILITY BARRIER. Write vault.dek and CONFIRM its rename
   498	                        // crash-durable BEFORE vault.bin is ever written; only then write vault.bin
   499	                        // and confirm ITS rename durable. This makes the {vault.bin present,
   500	                        // vault.dek absent} CorruptImage brick UNREACHABLE by construction: the DEK is
   501	                        // durable before the image exists, so it can never be lost while the image
   502	                        // survives. NO rollback deletes are needed (or performed).
   503	                        renameIntoPlace(dekFile, wrappedDek)
   504	                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   505	                            // The DEK's rename is not confirmed durable → throw BEFORE writing
   506	                            // vault.bin. At most a stray, possibly-not-durable vault.dek exists and NO
   507	                            // vault.bin → open() = MissingImage → a retried create() overwrites it.
   508	                            throw VaultImageException.NotDurable()
   509	                        }
   510	                        renameIntoPlace(binFile, outer)
   511	                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   512	                            // vault.bin's rename is not confirmed durable → throw. The DEK is already
   513	                            // durable, so the on-disk state is either {no bin} (open() = MissingImage
   514	                            // → retry) or a COMPLETE, openable vault — never {bin, no-dek}. No rollback
   515	                            // delete is needed.
   516	                            throw VaultImageException.NotDurable()
   517	                        }
   518	                        // Install in-memory state INSIDE the liveOpen-wipe scope so EVERY throw point
   519	                        // before the return — including the 32-byte newDek.copyOf() (an OOM at this
   520	                        // allocation) — wipes liveOpen.vaultKey / liveOpen.payloadPlaintext rather than
   521	                        // abandoning live key material + plaintext on the heap. The on-disk barrier has
   522	                        // already landed above, so this cannot desync disk from memory; it only advances
   523	                        // the in-memory canonical/dek to match the just-confirmed image.
   524	                        dek?.let { wipe(it) }
   525	                        dek = newDek.copyOf()
   526	                        canonical = image
   527	                        return liveOpen
   528	                    } catch (t: Throwable) {
   529	                        wipe(liveOpen.vaultKey)
   530	                        wipe(liveOpen.payloadPlaintext)
   531	                        throw t
   532	                    }
   533	                } finally {
   534	                    wipe(newDek)
   535	                }
   536	            } catch (t: Throwable) {
   537	                // A failed create must not leave a stale registration — release only what
   538	                // THIS call acquired (an already-registered instance keeps its ownership).
   539	                if (newlyRegistered) unregister()
   540	                throw t
   541	            }
   542	        }
   543	    }
   544	
   545	    /**
   546	     * Attempt [passphrase] against the current image (opening from disk first if
   547	     * needed). Returns a live [VaultOpen] on a match, or null on none — an
   548	     * indistinguishable wrong passphrase. The per-slot Argon2id work is identical
   549	     * whichever slot (or none) matches — the plausible-deniability parity inherited
   550	     * from [unlockImage] / [tryPassphrase]. A SUCCESSFUL unlock additionally opens one
   551	     * fixed-size payload region, so success and failure are not equal-time; that is the
   552	     * same accepted, documented asymmetry as [unlockImage] (it leaks no bit an observer
   553	     * lacks — the app visibly unlocks or not the instant it happens). CPU-heavy: caller
   554	     * MUST be off-main.
   555	     */
   556	    fun unlock(passphrase: String): VaultOpen? {
   557	        imageLock.withLock {
   558	            val image = canonical ?: run { open(); canonical!! }
   559	            return unlockImage(passphrase, image, ops, deriver)
   560	        }
   561	    }
   562	
   563	    /**
   564	     * Open one slot's payload directly with an already-unlocked [vaultKey] (the
   565	     * biometric / dual-wrap path — no passphrase, no Argon2id). Opening from disk
   566	     * first if needed, decrypts `payloads[slotIndex]` with a COPY of [vaultKey] and
   567	     * returns a [VaultOpen] holding that copy; returns null on AEAD failure.
   568	     *
   569	     * ⚠️ OWNERSHIP. The caller retains ownership of its [vaultKey] input and MUST
   570	     * wipe it itself — the store never wipes the caller's array. The returned
   571	     * [VaultOpen] holds an INDEPENDENT copy, so wiping the input does not disturb it.
   572	     */
   573	    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
   574	        imageLock.withLock {
   575	            // Only VAULT-POOL slots (1..SLOT_COUNT-1) are openable this way (F9 / Grok): slot 0 is the burn
   576	            // credential and must NEVER be opened as a vault — a future biometric dual-wrap that named slot
   577	            // 0 would otherwise surface the burn payload as an ordinary unlock instead of triggering a wipe.
   578	            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
   579	            // not-enabled and never reaches here; this require is the store-level backstop.
   580	            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
   581	            val image = canonical ?: run { open(); canonical!! }
   582	            val payload = decodeImage(image).payloads[slotIndex]
   583	            // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
   584	            // caller's input is never touched (it owns and wipes that itself).
   585	            val keyCopy = vaultKey.copyOf()
   586	            val plaintext = try {
   587	                openPayload(keyCopy, payload, ops)
   588	            } catch (t: Throwable) {
   589	                wipe(keyCopy)
   590	                throw t
   591	            }
   592	            if (plaintext == null) {
   593	                wipe(keyCopy)
   594	                return null
   595	            }
   596	            return VaultOpen(keyCopy, slotIndex, plaintext)
   597	        }
   598	    }
   599	
   600	    /**
   601	     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
   602	     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
   603	     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
   604	     * cases apart (the plausible-deniability + duress-credential timing contract):
   605	     *
   606	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
   607	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
   608	     *     (real vault-B material on create, pure timing filler otherwise);
   609	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
   610	     *
   611	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
   612	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
   613	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
   614	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
   615	     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
   616	     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
   617	     * [UnlockOrAdd.Rejected] having written nothing.
   618	     *
   619	     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
   620	     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
   621	     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
   622	     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
   623	     *
   624	     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
   625	     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
   626	     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
   627	     * target, so duress protection survives even a full pool.
   628	     *
   629	     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
   630	     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
   631	     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
   632	     *
   633	     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
   634	     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
   635	     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
   636	     *
   637	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
   638	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   639	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
   640	     * create write is not confirmed durable.
   641	     */
   642	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   643	        imageLock.withLock {
   644	            val image = canonical ?: run { open(); canonical!! }
   645	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   646	            val decoded = decodeImage(image)
   647	
   648	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   649	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   650	
   651	            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
   652	            // the try below so a throw during its generation (native crypto failure, OOM,
   653	            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
   654	            // live matched vault key — neither is covered if candidate generation sits before the try.
   655	            var candKeyForCleanup: ByteArray? = null
   656	            try {
   657	                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
   658	                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
   659	                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
   660	                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
   661	                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
   662	                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
   663	                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
   664	                val candSlotIndex = randomVaultSlotIndex(ops)
   665	                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
   666	
   667	                return when {
   668	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   669	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   670	                        wipe(candKey)
   671	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   672	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   673	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   674	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   675	                            .getOrNull()?.let { wipe(it) }
   676	                        wipe(unlock.vaultKey)
   677	                        UnlockOrAdd.Burn
   678	                    }
   679	
   680	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   681	                    unlock != null -> {
   682	                        wipe(candKey)
   683	                        val pt = try {
   684	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   685	                        } catch (t: Throwable) {
   686	                            wipe(unlock.vaultKey)
   687	                            throw VaultImageException.CorruptImage()
   688	                        }
   689	                        if (pt == null) {
   690	                            wipe(unlock.vaultKey)
   691	                            throw VaultImageException.CorruptImage()
   692	                        }
   693	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   694	                    }
   695	
   696	                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
   697	                    create -> {
   698	                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
   699	                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
   700	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
   701	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
   702	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
   703	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
   704	                        // a throw is an observable side channel precisely when the device is mid-delete) after
   705	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
   706	                        // machine is left completely untouched. This marker check is in the SAME imageLock
   707	                        // critical section as the sweep and the write, and markDeleteIntent /
   708	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
   709	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
   710	                        val markersAbsent =
   711	                            Files.notExists(deleteIntentFile.toPath()) &&
   712	                                Files.notExists(serverDeletedFile.toPath())
   713	                        if (!markersAbsent) {
   714	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
   715	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
   716	                            wipe(candKey)
   717	                            wipe(throwaway)
   718	                            UnlockOrAdd.Rejected
   719	                        } else {
   720	                            // The 1×256 KiB payload GCM for the create branch.
   721	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   722	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   723	                            val newPayloads =
   724	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   725	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   726	                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   727	                            // unreachable by construction; the dek is already durable on disk from create().
   728	                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   729	                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   730	                            // rename landed, the result reporting the rename's durability.
   731	                            val sync = atomicWrite(binFile, outer)
   732	                            // Rename committed → advance canonical BEFORE the durability check so a later
   733	                            // splice/attempt never works from stale state even on the NotDurable throw.
   734	                            canonical = newInner
   735	                            if (sync != DirSyncResult.DURABLE) {
   736	                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   737	                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   738	                                // canonical, so a later single entry of its passphrase unlocks it via the
   739	                                // match path — or, if the rename did not survive a crash, it is simply absent
   740	                                // and re-creatable.
   741	                                wipe(candKey)
   742	                                throw VaultImageException.NotDurable()
   743	                            }
   744	                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   745	                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   746	                        }
   747	                    }
   748	
   749	                    // ── REJECT — no match, no create. Nothing written. ──
   750	                    else -> {
   751	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   752	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   753	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   754	                        wipe(candKey)
   755	                        wipe(throwaway)
   756	                        UnlockOrAdd.Rejected
   757	                    }
   758	                }
   759	            } catch (t: Throwable) {
   760	                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
   761	                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
   762	                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
   763	                candKeyForCleanup?.let { wipe(it) }
   764	                unlock?.let { wipe(it.vaultKey) }
   765	                throw t
   766	            }
   767	        }
   768	    }
   769	
   770	    /**
   771	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   772	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   773	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   774	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   775	     *
   776	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   777	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   778	     * distinct because they leave DIFFERENT state:
   779	     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
   780	     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
   840	    /**
   841	     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
   842	     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
   843	     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
   844	     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
   845	     * boot).
   846	     *
   847	     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
   848	     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
   849	     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
   850	     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
   851	     * release the single-instance registration.
   852	     *
   853	     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
   854	     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
   855	     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
   856	     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
   857	     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
   858	     */
   859	    fun retireLegacyImage() {
   860	        imageLock.withLock {
   861	            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
   862	            val version = readInnerVersionOrNull()
   863	            check(version == LEGACY_IMAGE_VERSION) {
   864	                "retireLegacyImage refused: not a legacy image (inner version=$version)"
   865	            }
   866	            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
   867	            dek?.let { wipe(it) }
   868	            dek = null
   869	            canonical = null
   870	            binFile.delete()
   871	            dekFile.delete()
   872	            deleteLeftoverTmp(binFile)
   873	            deleteLeftoverTmp(dekFile)
   874	            unregister()
   875	            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
   876	            if (binFile.exists() || dekFile.exists() ||
   877	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
   878	            ) {
   879	                throw VaultImageException.DestroyFailed()
   880	            }
   881	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   882	                throw VaultImageException.DestroyFailed()
   883	            }
   884	        }
   885	    }
   886	
   887	    /**
   888	     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
   889	     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
   890	     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
   891	     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
   892	     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
   893	     */
   894	    private fun readInnerVersionOrNull(): Int? {
   895	        if (!binFile.exists() || !dekFile.exists()) return null
   896	        return try {
   897	            val dekBlob = dekFile.readBytes()
   898	            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
   899	            val binBytes = binFile.readBytes()
   900	            if (binBytes.size != OUTER_IMAGE_BYTES) return null
   901	            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
   902	            try {
   903	                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
   904	                if (inner.size != IMAGE_BYTES) return null
   905	                inner[0].toInt() and 0xff
   906	            } finally {
   907	                wipe(unwrapped)
   908	            }
   909	        } catch (t: Throwable) {
   910	            null
   911	        }
   912	    }
   913	
   914	    /**
   915	     * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
   916	     * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
   917	     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
   918	     * interrupted-write `.tmp` leftovers), and RELEASE this instance's single-instance
   919	     * registration so a fresh [create] may re-open the directory in the same process.
   920	     *
   921	     * DISTINCT FROM [close] (load-bearing). [close] only wipes RAM and LEAVES the disk
   922	     * image intact — a lock, not a deletion: after close() [exists] stays true and the
   923	     * encrypted image (with the account's full crypto identity: keypair, ratchet records,
   924	     * roster) survives on disk, recoverable by a later unlock. destroy() is the ONLY path
   925	     * that removes the files, so after it [exists] is false and nothing is recoverable.
   926	     * Account deletion MUST use destroy(), never close()/reseal. When paired with a session
   927	     * teardown that reseals via VaultRuntime.close(), destroy() MUST run AFTER that reseal so
   928	     * no freshly-resealed image survives.
   929	     *
   930	     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
   931	     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
   932	     * are best-effort; even if one returns false the RAM state is still wiped and the
   933	     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
   934	     * never invokes a VaultSession, so it introduces no reverse lock nesting.
   935	     *
   936	     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
   937	     * filesystem error just as it does on an already-absent file, so its boolean cannot be
   938	     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
   939	     * either SURVIVES, the full-crypto image is still on disk, so it throws
   940	     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
   941	     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
   942	     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
   943	     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
   944	     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
   945	     */
   946	    /**
   947	     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
   948	     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
   949	     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
   950	     * (a crash/failure before the server delete auto-destroyed a still-live-account vault).
   951	     *
   952	     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
   953	     *    ONLY "a delete was initiated" — it NEVER triggers local destruction. A crash here leaves a
   954	     *    fully valid, unlockable vault whose server account may still exist.
   955	     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
   956	     *    `api.deleteAccount()` returns a definite gone (2xx / 404). ONLY this marker authorises the
   957	     *    unlink-only [Route.DeleteIncomplete] auto-destroy: when it is present, the server account
   958	     *    is provably gone, so destroying the local copy is always safe.
   959	     *
   960	     * Both are existence-signals made crash-durable with a dir-fsync (fail-closed on non-durable).
   961	     */
   962	    fun markDeleteIntent() {
   963	        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
   964	    }
   965	
   966	    fun markServerDeleteConfirmed() {
   967	        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
   968	    }
   969	
   970	    /**
   971	     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
   972	     * and the marker RE-STATTED absent — [File.delete] returns false on an I/O failure just like on
   973	     * an already-absent file, so its bool cannot be trusted. Throws [VaultImageException.DestroyFailed]
   974	     * if the marker survives (unlink failed) or the retire is not crash-durable; a no-op (already
   975	     * absent) succeeds.
   976	     */
   977	    fun clearDeleteIntent() {
   978	        imageLock.withLock {
   979	            // Tristate (round 15, R14-2): only a CONFIRMED absence (Files.notExists) is a no-op;
   980	            // present-or-indeterminate falls through to the durable clear + verify below. Using
   981	            // File.exists() here would skip clearing a present-but-unstatable marker.
   982	            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
   983	            deleteIntentFile.delete()
   984	            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
   985	                throw VaultImageException.DestroyFailed()
   986	            }
   987	        }
   988	    }
   989	
   990	    /**
   991	     * Delete BOTH delete markers and confirm the retire crash-durably by RE-STAT — never by
   992	     * trusting [File.delete]'s bool (false on an I/O failure too). Returns true iff both markers
   993	     * re-stat ABSENT AND the directory fsync is [DirSyncResult.DURABLE]. Idempotent (already-absent
   994	     * markers succeed). The single choke point for the marker-retirement discipline used by
   995	     * [create] (F2) and [destroy] (F4). Caller must hold [imageLock].
   996	     */
   997	    private fun clearBothMarkersDurably(): Boolean {
   998	        deleteIntentFile.delete()
   999	        serverDeletedFile.delete()
  1000	        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
  1001	        // TRISTATE re-stat (round 15, R14-2): File.exists()==false conflates "absent" with "stat
  1002	        // could not be determined" (I/O/permission failure), so trusting it would report a marker
  1003	        // that SURVIVED an unlink as gone. Files.notExists returns true ONLY when the path is
  1004	        // confirmed absent — present OR indeterminate both yield false, so the clear is proven
  1005	        // only on a definite absence (fail-closed).
  1006	        return durable &&
  1007	            Files.notExists(deleteIntentFile.toPath()) &&
  1008	            Files.notExists(serverDeletedFile.toPath())
  1009	    }
  1010	
  1011	    /** Create [file] + dir-fsync it; throw [VaultImageException.DestroyFailed] if not durable. */
  1012	    private fun writeDurableMarker(file: File) {
  1013	        val durable = runCatching {
  1014	            file.createNewFile()
  1015	            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
  1016	        }.getOrDefault(false)
  1017	        if (!durable) {
  1018	            throw VaultImageException.DestroyFailed()
  1019	        }
  1020	    }
  1021	
  1022	    fun destroy() {
  1023	        imageLock.withLock {
  1024	            // Wipe live key material + drop the cached image FIRST — before even the marker gate
  1025	            // can throw — so no DEK/plaintext-adjacent state is retained on ANY exit. A destroy
  1026	            // request is terminal for this store's usefulness regardless of outcome (the session
  1027	            // is already torn down); the retry path never needs the cached DEK.
  1028	            dek?.let { wipe(it) }
  1029	            dek = null
  1030	            canonical = null
  1031	            // CONFIRMED MARKER before the unlinks (crash/restart continuity): reaching destroy()
  1032	            // means the server account is confirmed gone, so write `vault.delete-confirmed`
  1033	            // durably BEFORE unlinking. A crash mid-unlink then restarts into
  1034	            // [Route.DeleteIncomplete] (the CONFIRMED marker is present) and re-runs this
  1035	            // idempotent destroy — never a lock gate over a gone account. REQUIRED-DURABLE: if it
  1036	            // can't be written+fsynced, ABORT with the vault files untouched (throw). Idempotent
  1037	            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
  1038	            writeDurableMarker(serverDeletedFile)
  1039	            // Remove BOTH persisted files and any interrupted-write temps. delete() is
  1040	            // best-effort and never throws on a missing file (returns false) — idempotent.
  1041	            binFile.delete()
  1042	            dekFile.delete()
  1043	            deleteLeftoverTmp(binFile)
  1044	            deleteLeftoverTmp(dekFile)
  1045	            // Release the single-instance registration so a fresh create() may re-open this
  1046	            // directory in the SAME process (re-onboard after account deletion).
  1047	            unregister()
  1048	            // VERIFY everything image-bearing is actually GONE (see kdoc): delete()'s bool is
  1049	            // false on an I/O error too, so re-stat instead. The TEMPS are part of the check
  1050	            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
  1051	            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
  1052	            // verify exists to catch, an encrypted image copy could survive as a temp while the
  1053	            // primaries are gone. A surviving file → destruction FAILED → throw; account-delete
  1054	            // treats this as NOT-deleted. exists()==false (already-absent) does NOT throw,
  1055	            // keeping destroy() idempotent.
  1056	            if (binFile.exists() || dekFile.exists() ||
  1057	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
  1058	            ) {
  1059	                throw VaultImageException.DestroyFailed()
  1060	            }
  1061	            // Make the unlinks CRASH-DURABLE before retiring the markers (round 8, Codex): the
  1062	            // exists() re-stat proves only the current namespace, not what a journal replay
  1063	            // restores. Without this fsync, a crash could resurrect vault.bin/vault.dek while the
  1064	            // markers' own (later) unlink survived — restarting into DeleteIncomplete over a
  1065	            // now-present image, the exact state the markers exist to signal. A non-durable sync
  1066	            // keeps the markers (throw → retry re-runs the idempotent destroy), never false success.
  1067	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
  1068	                throw VaultImageException.DestroyFailed()
  1069	            }
  1070	            // Unlinks confirmed durable — retire BOTH markers, verified by RE-STAT + a required
  1071	            // fsync (round 13 Grok P1-2 / round 14 F4): trusting File.delete()'s bool would let a
  1072	            // silent unlink failure leave a marker that a journal replay resurrects over a later
  1073	            // SUCCESSOR vault → DeleteIncomplete → auto-destroy of a valid re-onboarded vault. A
  1074	            // marker that survives the delete, or a non-durable retire, is a FAILED destroy (throw):
  1075	            // marker-present + files-absent is the safe stuck state (a retry re-stats the files
  1076	            // absent and re-runs the retire). Self-healing over the empty image, now also correct.
  1077	            if (!clearBothMarkersDurably()) {
  1078	                throw VaultImageException.DestroyFailed()
  1079	            }
  1080	        }
  1081	    }
  1082	
  1083	    /**
  1084	     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
  1085	     * local image must be destroyed. The ONLY authorisation for the unlink-only
  1086	     * [Route.DeleteIncomplete] auto-destroy. (Replaces the round-12 `destroyPending`, which
  1087	     * conflated intent with confirmation — the P1-A/P1-1 root.)
  1088	     */
  1089	    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
  1090	
  1091	    /**
  1092	     * True while a delete was INITIATED but the server delete is not confirmed (intent marker
  1093	     * present, confirmed absent) — a crash/failure mid-delete. The vault is still valid and the
  1094	     * server account may still exist, so boot routes to normal unlock (NOT auto-destroy) and, on the
  1095	     * next live session, RECONCILES by retrying the authenticated DELETE (round 14). It never
  1096	     * authorises destruction and is retired only by a confirmed [destroy] — never cleared on boot.
  1097	     */
  1098	    fun deleteIntentPending(): Boolean =
  1099	        imageLock.withLock { deleteIntentFile.exists() && !serverDeletedFile.exists() }
  1100	
   280	                }
   281	            }
   282	        },
   283	        afterPublish = ::onSessionPublished,
   284	    )
   285	
   286	    /**
   287	     * D3 idle auto-lock. Locks the vault through the SAME [unlockController] teardown after the
   288	     * user's device-level timeout once the app is backgrounded — it only LOCKS (reseal + teardown),
   289	     * never DELETES, so it adds no writer to the vault-delete / auth state. Registered on the
   290	     * process lifecycle at construction (on the main thread, in Application.onCreate).
   291	     */
   292	    val vaultLockManager = VaultLockManager(
   293	        scope = scope,
   294	        timeoutSeconds = { deviceSettings.autoLockTimeoutSeconds },
   295	        sessionLive = { _session.value != null },
   296	        terminalWipe = { unlockController.isTerminalWipe() },
   297	        lock = { unlockController.lock() },
   298	    ).also { it.register(androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle) }
   299	
   300	    // ── Vault unlock / create orchestration (all off-main; caller drives the UI) ──
   301	
   302	    /**
   303	     * Create a fresh vault sealing an EMPTY keystore under [passphrase], then PUBLISH its session
   304	     * (onboarding = first unlock) in the SAME off-main block — so a mid-work coroutine cancellation
   305	     * can never discard the freshly created [VaultOpen] unwiped: [publishSession] consumes-or-wipes
   306	     * it before this block returns, and the session it builds lives on the process scope, not the
   307	     * Activity. Returns true once the session is published. CPU-heavy (Argon2id×SLOT_COUNT+1). Wipes
   308	     * the orphaned legacy prefs at creation (the zero-users clean-break decision). Propagates
   309	     * [com.zitrone.app.crypto.vault.VaultImageException.NotDurable] so the caller can surface a
   310	     * retry (or re-derive [hasVault] and route to unlock) — creation NEVER bricks.
   311	     */
   312	    suspend fun createVaultAndPublish(passphrase: String): Boolean = withContext(Dispatchers.Default) {
   313	        // A prior-format (v2 / 0.9.1) image is RETIRED here, on the deliberate onboarding action, so a
   314	        // fresh v3 vault can be created (create() requires no existing image). retireLegacyImage()
   315	        // re-proves the image is v2 and refuses to touch a valid current-version vault, so this is a
   316	        // no-op unless an actual legacy image is present. Not silent: it happens only on create.
   317	        if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()
   318	        val initial = VaultStateCodec.encode(VaultState.empty())
   319	        val open = try {
   320	            imageStore.create(passphrase, initial)
   321	        } finally {
   322	            // The genesis plaintext held nothing but empty holders, but zero it anyway —
   323	            // create() does not consume its initialPayload.
   324	            wipe(initial)
   325	        }
   326	        // `open` now holds a LIVE vault key + genesis payload. publishSession consumes them on an
   327	        // accepted build and wipes them on a refused one; anything BEFORE that hand-off (the
   328	        // best-effort legacy cleanup, a cancellation) must not abandon them unwiped.
   329	        var handedOff = false
   330	        try {
   331	            // ZERO-USERS CLEAN BREAK (maintainer decision 2026-07-23): a pre-0.9.1 install
   332	            // upgrading routes to vault setup, and creating the vault WIPES the orphaned legacy
   333	            // signal/auth/contacts prefs — one-time hygiene, no data anyone owns (no accounts /
   334	            // users exist). PREFS_SETTINGS (device settings + the biometric wrap) is deliberately
   335	            // kept. Best-effort: a legacy-prefs error must NOT brick a fresh vault, so it is caught
   336	            // and ignored rather than thrown.
   337	            runCatching { wipeLegacyPrefs() }
   338	            publishSession(open).also { handedOff = true }
   339	        } finally {
   340	            // Wipe only if publishSession never returned (a throw/cancellation before the hand-off):
   341	            // once it returns it has already consumed-or-wiped the arrays, and re-wiping a key we
   342	            // DID hand off would corrupt the running session.
   343	            if (!handedOff) {
   344	                wipe(open.vaultKey)
   345	                wipe(open.payloadPlaintext)
   346	            }
   347	        }
   348	    }
   349	
   350	    /**
   351	     * Attempt [passphrase] against the vault (off-main; both slots, no early exit) and, on a
   352	     * match, PUBLISH the session — both in the SAME off-main block so a cancellation that fires as
   353	     * the block ends cannot strand the materialized [VaultOpen] unwiped ([publishSession] consumes
   354	     * or wipes it synchronously before the block returns). Returns whether a session was published
   355	     * (false on no match OR on a refused build). Never logs anything credential-shaped.
   356	     */
   357	    suspend fun unlockWithPassphrase(passphrase: String): Boolean = withContext(Dispatchers.Default) {
   358	        val open = imageStore.unlock(passphrase) ?: return@withContext false
   359	        publishSession(open)
   360	    }
   361	
   362	    /**
   363	     * Recover the vault key from [wrap] with an already-AUTHENTICATED [decryptCipher] (from a
   364	     * successful CryptoObject BiometricPrompt), open the slot with it off-main, and PUBLISH the
   365	     * session — the open+publish share one off-main block so cancellation can't strand the
   366	     * [VaultOpen]. The recovered vault key is wiped in `finally` (unlockWithKey holds its own
   367	     * independent copy — store contract :474-478). Returns whether a session was published (false
   368	     * on an AEAD failure / no match / refused build).
   369	     */
   370	    suspend fun unlockWithBiometric(
   371	        decryptCipher: javax.crypto.Cipher,
   372	        wrap: com.zitrone.app.crypto.vault.BiometricWrappedKey,
   373	    ): Boolean = withContext(Dispatchers.Default) {
   374	        // The whole body — including openVaultKey's Cipher.doFinal — runs off-main so no crypto ever
   375	        // executes on the caller (main) thread.
   376	        val vaultKey = biometricCipher.openVaultKey(decryptCipher, wrap.blob) ?: return@withContext false
   377	        try {
   378	            val open = imageStore.unlockWithKey(vaultKey, wrap.slotIndex) ?: return@withContext false
   379	            publishSession(open)
   380	        } finally {
   381	            wipe(vaultKey)
   382	        }
   383	    }
   384	
   385	    /**
   386	     * Enable biometric unlock over the LIVE [session] (spec §1): wrap a COPY of the running slot's
   387	     * vault key — obtained via the narrow [SessionContainer.withVaultKey], wiped in its `finally` —
   388	     * under the auth-gated biometric key with an already-AUTHENTICATED [encryptCipher], and persist
   389	     * the constant-size `{ slotIndex, blob }` wrap. Returns true on success. Used by BOTH the
   390	     * onboarding enable offer (post-publish) and the Settings toggle, so no live [VaultOpen] is ever
   391	     * held across a recomposition.
   392	     */
   393	    fun enableBiometricFromSession(
   394	        encryptCipher: javax.crypto.Cipher,
   395	        session: SessionContainer,
   396	    ): Boolean = session.withVaultKey { key ->
   397	        val blob = biometricCipher.sealVaultKey(encryptCipher, key)
   398	        biometricStore.save(com.zitrone.app.crypto.vault.BiometricWrappedKey(session.slotIndex, blob))
   399	        true
   400	    }
   401	
   402	    /** Disable biometric unlock: delete the persisted wrap AND the auth-gated Keystore key. */
   403	    fun disableBiometric() {
   404	        biometricStore.clear()
   405	        biometricCipher.deleteKey()
   406	    }
   407	
   408	    /**
   409	     * The account-deletion primitive (NO REMANENCE). DESTROYS every on-disk + in-RAM trace of the
   410	     * vault: [VaultImageStore.destroy] deletes `vault.bin` + `vault.dek` (+ tmp leftovers), wipes the
   630	        scope.launch {
   631	            val confirmed = withContext(Dispatchers.IO) {
   632	                runCatching { container.destroyVaultForAccountDeletion() }
   633	                !container.hasVault() && !container.serverDeleteConfirmed()
   634	            }
   635	            deleteRetrying = false
   636	            if (confirmed) {
   637	                vaultExists = false
   638	                route = Route.Onboarding
   639	            } else {
   640	                deleteRetryFailed = true
   641	            }
   642	        }
   643	    }
   644	    // The biometric-enable OFFER is shown over the LIVE session (post-publish), so it holds NO
   645	    // VaultOpen across recomposition — an Activity recreation drops only the offer (recoverable via
   646	    // Settings), never key material. Set after an onboarding create, and after a passphrase unlock
   647	    // that follows a biometric invalidation (the re-enable the invalidation note promises).
   648	    var offerBiometricEnroll by remember { mutableStateOf(false) }
   649	    var reofferBiometric by remember { mutableStateOf(false) }
   650	    // Real biometric-enabled state (mirrors biometricStore.isEnabled()); updated on enable/disable
   651	    // so the Settings toggle and the lock-screen affordance reflect the TRUE control, not a flag.
   652	    var biometricEnabled by remember { mutableStateOf(container.biometricStore.isEnabled()) }
   653	
   654	    // Whether the platform can authenticate BIOMETRIC_STRONG right now — the gate for both
   655	    // OFFERING enable (onboarding / Settings) and the lock-screen biometric affordance.
   656	    val canAuthenticateStrong =
   657	        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
   658	            BiometricManager.BIOMETRIC_SUCCESS
   659	
   660	    // 0.9.2 upgrade safety: a PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
   661	    // reservation, so route it to fresh onboarding instead of the lock screen. Computed ONCE, off-main
   662	    // (a ~1 MiB outer decrypt, no Argon2id), only at a cold start with no live session. Safety does not
   663	    // depend on this — open() throws LegacyImage before any slot interpretation, and onUnlockPassphrase
   664	    // below also routes LegacyImage to onboarding as a backstop — this just avoids showing a dead lock
   665	    // screen. Treat a legacy image as "no usable vault" (vaultExists=false) so onboarding proceeds; the
   666	    // create there retires the old image.
   667	    LaunchedEffect(Unit) {
   668	        if (vaultExists && container.session.value == null) {
   669	            val legacy = withContext(Dispatchers.IO) {
   670	                runCatching { container.isLegacyImage() }.getOrDefault(false)
   671	            }
   672	            if (legacy && (route == Route.Splash || route == Route.Locked)) {
   673	                vaultExists = false
   674	                route = Route.Onboarding
   675	            }
   676	        }
   677	    }
   678	
   679	    var identityFingerprint by remember { mutableStateOf<String?>(null) }
   680	    LaunchedEffect(session) {
   681	        val live = session
   682	        if (live != null && identityFingerprint == null) {
   683	            identityFingerprint = withContext(Dispatchers.Default) {
   684	                runCatching {
   685	                    live.signalManager.ensureIdentity()
   686	                    live.signalManager.localFingerprint()
   687	                }.getOrNull()
   688	            }
   689	        }
   690	    }
   691	
   692	    // Route reconciliation with the PROCESS-scoped session (round 11, Codex). The route vars
   693	    // above are composition-local: an Activity recreation during a slow vault operation seeds
   694	    // them from a one-time snapshot, and the operation's own completion callback then writes to
   695	    // the DISPOSED composition's state. Two stranded shapes: rotation during an Argon2
   696	    // unlock/create seeds Locked/Onboarding, the surviving worker publishes the session, and no
   697	    // live coroutine ever routes to ChatList (every further unlock is refused — a session is
   698	    // already live); rotation during the NonCancellable account delete seeds ChatList, the
   699	    // delete then nulls the session, and the replacement composes blank. This collector — one
   700	    // per LIVE composition — reconciles both directions. The locked-direction target derives
   701	    // from DISK TRUTH (destroy marker / image presence), the SAME derivation the delete
   702	    // handler's finally uses, so whichever writes last the result is identical — an observer
   703	    // deriving anything else would race that finally and could stomp DeleteIncomplete with a
   704	    // lock gate over a destroyed vault.
   705	    LaunchedEffect(Unit) {
   706	        container.session.collect { live ->
   707	            if (live != null) {
   708	                if (!unlocked) {
   709	                    unlocked = true
   710	                    unlocking = false
   711	                    lockError = null
   712	                    route = Route.ChatList
   713	                }
   714	            } else if (unlocked) {
   715	                unlocked = false
   716	                identityFingerprint = null
   717	                vaultExists = container.hasVault()
   718	                route = when {
   719	                    // Only a CONFIRMED server delete routes to the auto-destroy path (round 13).
   720	                    // A session going null never carries a mere delete-intent (onNotConfirmed keeps
   721	                    // the session live), so intent-only handling lives in Splash, not here.
   722	                    container.serverDeleteConfirmed() -> Route.DeleteIncomplete
   723	                    vaultExists -> Route.Locked
   724	                    else -> Route.Onboarding
   725	                }
   726	            }
   727	        }
   728	    }
   729	
   730	    // Session lifecycle — tied to the session INSTANCE, not the route, so it runs
   731	    // once per unlock cycle. A fresh unlock builds a new instance over the durable
   732	    // vault image (state reloads exactly as on a process restart).
   733	    session?.let { live ->
   734	        LaunchedEffect(live) { live.coordinator.start() }
   735	        DisposableEffect(live) {
   736	            live.coordinator.onForcedLogout = {
   737	                unlocked = false
   738	                route = Route.Locked
   739	                container.unlockController.lockIf(live)
   740	            }
   741	            onDispose { live.coordinator.onForcedLogout = null }
   742	        }
   743	    }
   744	
   745	    // Root detection: warn once per process, never block.
   746	    var rootWarningVisible by remember {
   747	        mutableStateOf(RootDetection.check(context).likelyRooted)
   748	    }
   749	
   750	    // Land on the chat list after a successful unlock (passphrase or biometric); clear the
   751	    // RAM backoff so the next lock cycle starts fresh.
   752	    val onUnlockSuccess: () -> Unit = {
   753	        lockError = null
   754	        unlocking = false
   755	        unlocked = true
   756	        route = Route.ChatList
   757	        container.unlockRouter.recordSuccess()
   758	        // A passphrase unlock that follows a biometric invalidation RE-OFFERS enablement over the
   759	        // now-live session — making BIOMETRIC_REENROLL_NOTE's "after a passphrase unlock" promise
   760	        // real, iff the platform can authenticate.
   761	        if (reofferBiometric && canAuthenticateStrong) offerBiometricEnroll = true
   762	        reofferBiometric = false
   763	    }
   764	
   765	    // Passphrase unlock (§2): ALWAYS available. Enforce the RAM backoff BEFORE the off-main
   766	    // attempt, then surface only a uniform generic failure (no per-slot / per-factor branch) —
   767	    // EXCEPT a damaged image, which escalates distinctly (it is not a passphrase guess).
   768	    val onUnlockPassphrase: (String) -> Unit = onUnlockPassphrase@{ pass ->
   769	        if (unlocking) return@onUnlockPassphrase
   770	        unlocking = true
   771	        lockError = null
   772	        scope.launch {
   773	            val backoff = container.unlockRouter.backoffDelayMs()
   774	            if (backoff > 0) delay(backoff)
   775	            runCatching { container.unlockWithPassphrase(pass) }.fold(
   776	                onSuccess = { published ->
   777	                    if (published) {
   778	                        onUnlockSuccess()
   779	                    } else {
   780	                        // No match (wrong passphrase) OR a refused build (which already wiped the
   781	                        // VaultOpen). Reporting success would land on a null session, so treat both
   782	                        // as a non-success: uniform failure + backoff.
   783	                        container.unlockRouter.recordFailure()
   784	                        lockError = VaultUnlockRouter.UNIFORM_FAILURE
   785	                        unlocking = false
   786	                    }
   787	                },
   788	                onFailure = { e ->
   789	                    when {
   790	                        e is kotlinx.coroutines.CancellationException -> throw e
   791	                        e is com.zitrone.app.crypto.vault.VaultImageException.LegacyImage -> {
   792	                            // A PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
   793	                            // reservation; open() threw BEFORE any slot was interpreted (never a burn
   794	                            // wipe). Route to fresh onboarding (the create there retires the old image).
   795	                            // Backstop for the cold-start precompute above; no backoff bump (not a guess).
   796	                            vaultExists = false
   797	                            route = Route.Onboarding
   798	                            unlocking = false
   799	                        }
   800	                        e is com.zitrone.app.crypto.vault.VaultImageException.CorruptImage ||
   801	                            e is com.zitrone.app.crypto.vault.VaultImageException.MissingImage -> {
   802	                            // A damaged/unreadable IMAGE is device state, NOT a passphrase guess —
   803	                            // surface a distinct honest error, never the wrong-passphrase uniform
   804	                            // failure (no oracle at stake), and do not bump the backoff.
   805	                            lockError = VaultUnlockRouter.IMAGE_UNREADABLE_NOTE
   806	                            unlocking = false
   807	                        }
   808	                        else -> {
   809	                            // Any other throw (a state decode/version failure from the build, a
   810	                            // transient IO error) → uniform failure; never leak the cause.
   811	                            container.unlockRouter.recordFailure()
   812	                            lockError = VaultUnlockRouter.UNIFORM_FAILURE
   813	                            unlocking = false
   814	                        }
   815	                    }
   816	                },
   817	            )
   818	        }
   819	    }
   820	
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-634-     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-635-     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-636-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-637-     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-638-     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-639-     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-640-     * create write is not confirmed durable.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-641-     */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:642:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-643-        imageLock.withLock {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-644-            val image = canonical ?: run { open(); canonical!! }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-645-            val activeDek = dek ?: throw IllegalStateException("vault image not open")
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-646-            val decoded = decodeImage(image)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-647-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-648-            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-649-            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-650-

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '642,780p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '900,1060p'; rg -n \"fun (markDeleteIntent|markServerDeleteConfirmed|clearDeleteIntent|clearBothMarkersDurably|destroy)|DELETE_INTENT_FILE|SERVER_DELETED_FILE\" apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt" in /root/zitrone
 succeeded in 0ms:
   642	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   643	        imageLock.withLock {
   644	            val image = canonical ?: run { open(); canonical!! }
   645	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   646	            val decoded = decodeImage(image)
   647	
   648	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   649	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   650	
   651	            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
   652	            // the try below so a throw during its generation (native crypto failure, OOM,
   653	            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
   654	            // live matched vault key — neither is covered if candidate generation sits before the try.
   655	            var candKeyForCleanup: ByteArray? = null
   656	            try {
   657	                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
   658	                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
   659	                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
   660	                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
   661	                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
   662	                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
   663	                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
   664	                val candSlotIndex = randomVaultSlotIndex(ops)
   665	                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
   666	
   667	                return when {
   668	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   669	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   670	                        wipe(candKey)
   671	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   672	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   673	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   674	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   675	                            .getOrNull()?.let { wipe(it) }
   676	                        wipe(unlock.vaultKey)
   677	                        UnlockOrAdd.Burn
   678	                    }
   679	
   680	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   681	                    unlock != null -> {
   682	                        wipe(candKey)
   683	                        val pt = try {
   684	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   685	                        } catch (t: Throwable) {
   686	                            wipe(unlock.vaultKey)
   687	                            throw VaultImageException.CorruptImage()
   688	                        }
   689	                        if (pt == null) {
   690	                            wipe(unlock.vaultKey)
   691	                            throw VaultImageException.CorruptImage()
   692	                        }
   693	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   694	                    }
   695	
   696	                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
   697	                    create -> {
   698	                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
   699	                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
   700	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
   701	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
   702	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
   703	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
   704	                        // a throw is an observable side channel precisely when the device is mid-delete) after
   705	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
   706	                        // machine is left completely untouched. This marker check is in the SAME imageLock
   707	                        // critical section as the sweep and the write, and markDeleteIntent /
   708	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
   709	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
   710	                        val markersAbsent =
   711	                            Files.notExists(deleteIntentFile.toPath()) &&
   712	                                Files.notExists(serverDeletedFile.toPath())
   713	                        if (!markersAbsent) {
   714	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
   715	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
   716	                            wipe(candKey)
   717	                            wipe(throwaway)
   718	                            UnlockOrAdd.Rejected
   719	                        } else {
   720	                            // The 1×256 KiB payload GCM for the create branch.
   721	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   722	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   723	                            val newPayloads =
   724	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   725	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   726	                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   727	                            // unreachable by construction; the dek is already durable on disk from create().
   728	                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   729	                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   730	                            // rename landed, the result reporting the rename's durability.
   731	                            val sync = atomicWrite(binFile, outer)
   732	                            // Rename committed → advance canonical BEFORE the durability check so a later
   733	                            // splice/attempt never works from stale state even on the NotDurable throw.
   734	                            canonical = newInner
   735	                            if (sync != DirSyncResult.DURABLE) {
   736	                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   737	                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   738	                                // canonical, so a later single entry of its passphrase unlocks it via the
   739	                                // match path — or, if the rename did not survive a crash, it is simply absent
   740	                                // and re-creatable.
   741	                                wipe(candKey)
   742	                                throw VaultImageException.NotDurable()
   743	                            }
   744	                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   745	                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   746	                        }
   747	                    }
   748	
   749	                    // ── REJECT — no match, no create. Nothing written. ──
   750	                    else -> {
   751	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   752	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   753	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   754	                        wipe(candKey)
   755	                        wipe(throwaway)
   756	                        UnlockOrAdd.Rejected
   757	                    }
   758	                }
   759	            } catch (t: Throwable) {
   760	                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
   761	                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
   762	                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
   763	                candKeyForCleanup?.let { wipe(it) }
   764	                unlock?.let { wipe(it.vaultKey) }
   765	                throw t
   766	            }
   767	        }
   768	    }
   769	
   770	    /**
   771	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   772	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   773	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   774	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   775	     *
   776	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   777	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   778	     * distinct because they leave DIFFERENT state:
   779	     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
   780	     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
   900	            if (binBytes.size != OUTER_IMAGE_BYTES) return null
   901	            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
   902	            try {
   903	                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
   904	                if (inner.size != IMAGE_BYTES) return null
   905	                inner[0].toInt() and 0xff
   906	            } finally {
   907	                wipe(unwrapped)
   908	            }
   909	        } catch (t: Throwable) {
   910	            null
   911	        }
   912	    }
   913	
   914	    /**
   915	     * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
   916	     * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
   917	     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
   918	     * interrupted-write `.tmp` leftovers), and RELEASE this instance's single-instance
   919	     * registration so a fresh [create] may re-open the directory in the same process.
   920	     *
   921	     * DISTINCT FROM [close] (load-bearing). [close] only wipes RAM and LEAVES the disk
   922	     * image intact — a lock, not a deletion: after close() [exists] stays true and the
   923	     * encrypted image (with the account's full crypto identity: keypair, ratchet records,
   924	     * roster) survives on disk, recoverable by a later unlock. destroy() is the ONLY path
   925	     * that removes the files, so after it [exists] is false and nothing is recoverable.
   926	     * Account deletion MUST use destroy(), never close()/reseal. When paired with a session
   927	     * teardown that reseals via VaultRuntime.close(), destroy() MUST run AFTER that reseal so
   928	     * no freshly-resealed image survives.
   929	     *
   930	     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
   931	     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
   932	     * are best-effort; even if one returns false the RAM state is still wiped and the
   933	     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
   934	     * never invokes a VaultSession, so it introduces no reverse lock nesting.
   935	     *
   936	     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
   937	     * filesystem error just as it does on an already-absent file, so its boolean cannot be
   938	     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
   939	     * either SURVIVES, the full-crypto image is still on disk, so it throws
   940	     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
   941	     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
   942	     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
   943	     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
   944	     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
   945	     */
   946	    /**
   947	     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
   948	     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
   949	     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
   950	     * (a crash/failure before the server delete auto-destroyed a still-live-account vault).
   951	     *
   952	     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
   953	     *    ONLY "a delete was initiated" — it NEVER triggers local destruction. A crash here leaves a
   954	     *    fully valid, unlockable vault whose server account may still exist.
   955	     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
   956	     *    `api.deleteAccount()` returns a definite gone (2xx / 404). ONLY this marker authorises the
   957	     *    unlink-only [Route.DeleteIncomplete] auto-destroy: when it is present, the server account
   958	     *    is provably gone, so destroying the local copy is always safe.
   959	     *
   960	     * Both are existence-signals made crash-durable with a dir-fsync (fail-closed on non-durable).
   961	     */
   962	    fun markDeleteIntent() {
   963	        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
   964	    }
   965	
   966	    fun markServerDeleteConfirmed() {
   967	        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
   968	    }
   969	
   970	    /**
   971	     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
   972	     * and the marker RE-STATTED absent — [File.delete] returns false on an I/O failure just like on
   973	     * an already-absent file, so its bool cannot be trusted. Throws [VaultImageException.DestroyFailed]
   974	     * if the marker survives (unlink failed) or the retire is not crash-durable; a no-op (already
   975	     * absent) succeeds.
   976	     */
   977	    fun clearDeleteIntent() {
   978	        imageLock.withLock {
   979	            // Tristate (round 15, R14-2): only a CONFIRMED absence (Files.notExists) is a no-op;
   980	            // present-or-indeterminate falls through to the durable clear + verify below. Using
   981	            // File.exists() here would skip clearing a present-but-unstatable marker.
   982	            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
   983	            deleteIntentFile.delete()
   984	            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
   985	                throw VaultImageException.DestroyFailed()
   986	            }
   987	        }
   988	    }
   989	
   990	    /**
   991	     * Delete BOTH delete markers and confirm the retire crash-durably by RE-STAT — never by
   992	     * trusting [File.delete]'s bool (false on an I/O failure too). Returns true iff both markers
   993	     * re-stat ABSENT AND the directory fsync is [DirSyncResult.DURABLE]. Idempotent (already-absent
   994	     * markers succeed). The single choke point for the marker-retirement discipline used by
   995	     * [create] (F2) and [destroy] (F4). Caller must hold [imageLock].
   996	     */
   997	    private fun clearBothMarkersDurably(): Boolean {
   998	        deleteIntentFile.delete()
   999	        serverDeletedFile.delete()
  1000	        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
  1001	        // TRISTATE re-stat (round 15, R14-2): File.exists()==false conflates "absent" with "stat
  1002	        // could not be determined" (I/O/permission failure), so trusting it would report a marker
  1003	        // that SURVIVED an unlink as gone. Files.notExists returns true ONLY when the path is
  1004	        // confirmed absent — present OR indeterminate both yield false, so the clear is proven
  1005	        // only on a definite absence (fail-closed).
  1006	        return durable &&
  1007	            Files.notExists(deleteIntentFile.toPath()) &&
  1008	            Files.notExists(serverDeletedFile.toPath())
  1009	    }
  1010	
  1011	    /** Create [file] + dir-fsync it; throw [VaultImageException.DestroyFailed] if not durable. */
  1012	    private fun writeDurableMarker(file: File) {
  1013	        val durable = runCatching {
  1014	            file.createNewFile()
  1015	            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
  1016	        }.getOrDefault(false)
  1017	        if (!durable) {
  1018	            throw VaultImageException.DestroyFailed()
  1019	        }
  1020	    }
  1021	
  1022	    fun destroy() {
  1023	        imageLock.withLock {
  1024	            // Wipe live key material + drop the cached image FIRST — before even the marker gate
  1025	            // can throw — so no DEK/plaintext-adjacent state is retained on ANY exit. A destroy
  1026	            // request is terminal for this store's usefulness regardless of outcome (the session
  1027	            // is already torn down); the retry path never needs the cached DEK.
  1028	            dek?.let { wipe(it) }
  1029	            dek = null
  1030	            canonical = null
  1031	            // CONFIRMED MARKER before the unlinks (crash/restart continuity): reaching destroy()
  1032	            // means the server account is confirmed gone, so write `vault.delete-confirmed`
  1033	            // durably BEFORE unlinking. A crash mid-unlink then restarts into
  1034	            // [Route.DeleteIncomplete] (the CONFIRMED marker is present) and re-runs this
  1035	            // idempotent destroy — never a lock gate over a gone account. REQUIRED-DURABLE: if it
  1036	            // can't be written+fsynced, ABORT with the vault files untouched (throw). Idempotent
  1037	            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
  1038	            writeDurableMarker(serverDeletedFile)
  1039	            // Remove BOTH persisted files and any interrupted-write temps. delete() is
  1040	            // best-effort and never throws on a missing file (returns false) — idempotent.
  1041	            binFile.delete()
  1042	            dekFile.delete()
  1043	            deleteLeftoverTmp(binFile)
  1044	            deleteLeftoverTmp(dekFile)
  1045	            // Release the single-instance registration so a fresh create() may re-open this
  1046	            // directory in the SAME process (re-onboard after account deletion).
  1047	            unregister()
  1048	            // VERIFY everything image-bearing is actually GONE (see kdoc): delete()'s bool is
  1049	            // false on an I/O error too, so re-stat instead. The TEMPS are part of the check
  1050	            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
  1051	            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
  1052	            // verify exists to catch, an encrypted image copy could survive as a temp while the
  1053	            // primaries are gone. A surviving file → destruction FAILED → throw; account-delete
  1054	            // treats this as NOT-deleted. exists()==false (already-absent) does NOT throw,
  1055	            // keeping destroy() idempotent.
  1056	            if (binFile.exists() || dekFile.exists() ||
  1057	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
  1058	            ) {
  1059	                throw VaultImageException.DestroyFailed()
  1060	            }
261:    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
262:    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
962:    fun markDeleteIntent() {
966:    fun markServerDeleteConfirmed() {
977:    fun clearDeleteIntent() {
997:    private fun clearBothMarkersDurably(): Boolean {
1022:    fun destroy() {
1241:        const val DELETE_INTENT_FILE = "vault.delete-intent"
1248:        const val SERVER_DELETED_FILE = "vault.delete-confirmed"

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | sed -n '211,250p'; rg -n \"class VaultUnlockRouter|triple|create =|UnlockOrAdd\" apps/android/app/src/main/java/com/zitrone/app -g'*.kt' -C 6; git show 321b358:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | nl -ba | sed -n '110,160p'; git log --oneline --all -- apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | head -20" in /root/zitrone
 succeeded in 694ms:
9ab8cb0 fix(vault): PR-1 review round — B1 fail-closed markers, B2 slot self-verify, F4 wipe, F9 slot-0 guard
321b358 feat(vault): PR-1 — attemptUnlockOrAdd (second vault) + slot-0 burn reservation + v2 retire
3c598ad feat(vault): PR-D2c — slot-A live over the vault (vault-only, fresh-install) (#46)
1e8bc7e review(vault): PR-D2c round 3 — bot round 1 (flush-before-ack barrier absorbs D4, rotation routing, cancellation, biometric hardening)
5157aa6 review(vault): PR-D2c round 1 — 88-agent adversarial findings (13 defects, 2 P1)
92286e1 feat(vault): P1b-2 PR-D2c — slot A live over the vault (vault-only)
399a9f0 feat(vault): P1a — plausible-deniability key-slot crypto primitive (isolated) (#37)
e3c5399 fix(vault): P1a review round-1 — exception-safe key wiping, robust AEAD, collision guard, doc accuracy
2593bf9 feat(android): plausible-deniability key-slot vault primitive (P1a, isolated)

exec
/bin/bash -lc 'rg -n "override fun aeadDecrypt|fun aeadDecrypt|interface VaultSodiumOps|SLOT_COUNT" apps/android/app/src/main/java/com/zitrone/app/crypto/vault -C 5; nl -ba apps/android/app/src/main/java/com/zitrone/app/VaultUnlockRouter.kt; rg -n "attempt.*Add|recordAttempt|triple|three|Created|UnlockOrAdd" apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt -C 10' in /root/zitrone
 exited 1 in 0ms:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-7-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-8-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-9- * The fixed-size on-disk image — an exact Kotlin mirror of the image codec in
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-10- * apps/web/src/lib/storage.ts. Layout:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-11- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:12: *   version(1) ‖ SLOT_COUNT × [salt(16) ‖ wrapped(60)] ‖ SLOT_COUNT × payload(SLOT_PAYLOAD_BYTES)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-13- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-14- * The image is a compile-time-constant IMAGE_BYTES long no matter how many
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:15: * vaults are real — zero, one, or SLOT_COUNT. Neither the size, the structure,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-16- * nor any byte of the stored material reveals the vault count.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-17- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-18- * This is the isolated PRIMITIVE only. It is deliberately NOT wired into any
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-19- * store, unlock flow, or persistence backend — that is a later phase.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-20- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-21-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-22-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-23- * On-disk image format version.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-24- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-25- * **v3 (0.9.2):** slot 0 is reserved for the Pucker Burn credential and vaults live in
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:26: * slots 1..SLOT_COUNT-1 (see [BURN_SLOT_INDEX]). The BYTE layout is unchanged from v2 —
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-27- * only the placement CONVENTION changed — but the version is bumped anyway because the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-28- * change is SAFETY-CRITICAL to distinguish: a v2 image (0.9.1) could hold the everyday
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-29- * vault at slot 0, which under v3's rules would be read as a BURN match and WIPE on the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-30- * user's own correct passphrase. So [VaultImageStore.open] treats a v2 inner image as a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-31- * distinct [VaultImageException.LegacyImage] (route to fresh onboarding + retire), NEVER
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-32- * as an unlockable image and NEVER slot-interpreted. v2 had no reserved slot (vaults at
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:33: * any index 0..SLOT_COUNT-1). Any FUTURE bump must add its migration/retire branch in
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-34- * [VaultImageStore.open] BEFORE changing this constant.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-35- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-36-const val IMAGE_VERSION: Int = 3
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-37-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-38-/** The immediately-prior format ([VaultImageStore] retires it to fresh onboarding). */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-39-const val LEGACY_IMAGE_VERSION: Int = 2
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-40-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-41-private const val HEADER_BYTES: Int = 1
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-42-private const val SLOT_ENTRY_BYTES: Int = SALT_BYTES + WRAPPED_KEY_BYTES
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:43:private const val SLOT_TABLE_BYTES: Int = SLOT_COUNT * SLOT_ENTRY_BYTES
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-44-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-45-/** Total image size — constant regardless of how many vaults are real. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:46:const val IMAGE_BYTES: Int = HEADER_BYTES + SLOT_TABLE_BYTES + SLOT_COUNT * SLOT_PAYLOAD_BYTES
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-47-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-48-/** The image in structured form. payloads[i] belongs to slots[i]. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-49-class VaultImage(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-50-    val slots: List<KeySlot>,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-51-    val payloads: List<ByteArray>,
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-58-    val payloadPlaintext: ByteArray,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-59-)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-60-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-61-/** Serialize a structured image to its fixed-size byte form. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-62-fun encodeImage(image: VaultImage): ByteArray {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:63:    require(image.slots.size == SLOT_COUNT && image.payloads.size == SLOT_COUNT) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:64:        "vault image must have exactly SLOT_COUNT slots"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-65-    }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-66-    val out = ByteArray(IMAGE_BYTES)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-67-    out[0] = IMAGE_VERSION.toByte()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:68:    for (i in 0 until SLOT_COUNT) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-69-        val slot = image.slots[i]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-70-        val payload = image.payloads[i]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-71-        require(payload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-72-        val entryOffset = HEADER_BYTES + i * SLOT_ENTRY_BYTES
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-73-        slot.salt.copyInto(out, entryOffset)
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-79-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-80-/** Parse a fixed-size image back into structured form. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-81-fun decodeImage(bytes: ByteArray): VaultImage {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-82-    require(bytes.size == IMAGE_BYTES) { "not a vault image" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-83-    require(bytes[0].toInt() and 0xff == IMAGE_VERSION) { "unsupported vault image version" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:84:    val slots = ArrayList<KeySlot>(SLOT_COUNT)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:85:    val payloads = ArrayList<ByteArray>(SLOT_COUNT)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:86:    for (i in 0 until SLOT_COUNT) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-87-        val entryOffset = HEADER_BYTES + i * SLOT_ENTRY_BYTES
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-88-        slots.add(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-89-            KeySlot(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-90-                salt = bytes.copyOfRange(entryOffset, entryOffset + SALT_BYTES),
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-91-                wrapped = bytes.copyOfRange(entryOffset + SALT_BYTES, entryOffset + SLOT_ENTRY_BYTES),
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-96-    }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-97-    return VaultImage(slots, payloads)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-98-}
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-99-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-100-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:101: * Build a fresh image sealed under [passphrase]: SLOT_COUNT slots, exactly ONE
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:102: * real (at a random index), the rest random filler, and SLOT_COUNT payload
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-103- * regions — the real slot's payload sealing [payloadPlaintext], every other
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-104- * region a fresh random filler. The number of real slots leaves no on-disk
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-105- * trace, and the returned image is always IMAGE_BYTES long.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-106- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-107-fun createImage(
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-113-    val created = createVaultSlots(passphrase, ops, deriver)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-114-    // The key is ephemeral here (the returned image holds the SEALED payload, not
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-115-    // the raw key), so wipe it on every exit — including if randomPayload or
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-116-    // encodeImage throws between generation and use.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-117-    try {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:118:        val payloads = ArrayList<ByteArray>(SLOT_COUNT)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:119:        for (i in 0 until SLOT_COUNT) payloads.add(randomPayload(ops))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-120-        payloads[created.slotIndex] = sealPayload(created.vaultKey, payloadPlaintext, ops)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-121-        return encodeImage(VaultImage(created.slots, payloads))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-122-    } finally {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-123-        wipe(created.vaultKey)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-124-    }
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-143-    image: ByteArray,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-144-    slotIndex: Int,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-145-    sealedPayload: ByteArray,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-146-): ByteArray {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-147-    require(image.size == IMAGE_BYTES) { "malformed vault image" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:148:    require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-149-    require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-150-    // Only THIS slot's payload region changes on a reseal; the version byte, the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-151-    // whole slot table, and every other slot's payload are carried through
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-152-    // byte-identical. Copy the image and overwrite just the target region in place
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-153-    // — no decode + re-encode, so a hot reseal path does not allocate and parse the
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-163- * Attempt [passphrase] against [image]. Runs [tryPassphrase] over every slot
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-164- * (no early exit — identical work regardless of which slot, if any, matches),
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-165- * then opens the matched slot's payload. Returns null when no slot matches (a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-166- * wrong passphrase) or the matched payload is corrupt.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-167- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:168: * CPU-HEAVY with the production deriver (SLOT_COUNT × 64 MiB Argon2id); the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-169- * future integration layer MUST call this off the main thread.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-170- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:171: * TIMING NOTE (deliberate, accepted): the SLOT_COUNT-way slot loop is
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-172- * content-independent, so it gives the required cross-slot parity (matching slot
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-173- * A, slot B, or nothing takes identical work). A SUCCESSFUL unlock additionally
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-174- * opens one fixed-size payload; a wrong passphrase does not. So success and
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-175- * failure are NOT equal-time — but this leaks nothing an observer doesn't
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-176- * already have: the app visibly unlocks (or doesn't) the instant it happens, so
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-142- * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-143- * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-144- * the CALLER learns only which of the four happened, never which slot or how many exist.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-145- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-146-sealed interface UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:147:    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-148-    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-149-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-150-    /**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-151-     * Slot 0 ([BURN_SLOT_INDEX]) matched — the Pucker Burn duress credential was entered.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-152-     * The APP performs the wipe (a sibling feature); the store performs NO wipe here and
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-193- * flushLock and only THEN hands the region to [writeSealedPayload], which takes
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-194- * imageLock. NEVER invoke a VaultSession method while holding [imageLock] — that
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-195- * would nest the locks in the reverse order and can deadlock.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-196- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-197- * THREADING. Every method takes [imageLock]; all are synchronous. The Argon2id-heavy
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:198: * methods are [create] — exactly SLOT_COUNT+1 derivations with the production deriver:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:199: * one to seal the real slot, then SLOT_COUNT more for the verification [unlockImage] —
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:200: * and [unlock], exactly SLOT_COUNT (never fewer: the slot loop has no early exit). Both
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-201- * MUST run off a UI thread. [open] is NOT Argon2id-heavy (a single ~1 MiB AEAD decrypt of
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-202- * the outer layer) and [unlockWithKey] does NO Argon2id at all (it opens one payload with
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-203- * an already-derived key); still, run them off-main so the ~1 MiB decrypt never lands on
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-204- * the UI thread.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-205- *
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-410-     * Create a fresh vault image sealing [initialPayload] under [passphrase], write
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-411-     * it durably, and return a live [VaultOpen] for it. Requires no image exists yet.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-412-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-413-     * Generates a random DEK, builds the image with the audited [createImage] primitive,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-414-     * and outer-encrypts it. It then VERIFIES the fresh image by [unlockImage]ing it —
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:415:     * one extra Argon2id×SLOT_COUNT, one-time at onboarding / migration, reusing the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-416-     * audited primitive rather than adding a new create-and-open surface — BEFORE touching
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-417-     * disk: [unlockImage] runs purely on the in-memory image and needs no disk state, so
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-418-     * verifying first makes ANY failure in create() (bad wrapped-key size, encrypt /
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-419-     * verify / IO failure) leave the disk UNTOUCHED and the whole call retryable.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-420-     *
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-570-     * wipe it itself — the store never wipes the caller's array. The returned
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-571-     * [VaultOpen] holds an INDEPENDENT copy, so wiping the input does not disturb it.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-572-     */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-573-    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-574-        imageLock.withLock {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:575:            // Only VAULT-POOL slots (1..SLOT_COUNT-1) are openable this way (F9 / Grok): slot 0 is the burn
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-576-            // credential and must NEVER be opened as a vault — a future biometric dual-wrap that named slot
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-577-            // 0 would otherwise surface the burn payload as an ordinary unlock instead of triggering a wipe.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-578-            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-579-            // not-enabled and never reaches here; this require is the store-level backstop.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-580-            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-601-     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-602-     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-603-     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-604-     * cases apart (the plausible-deniability + duress-credential timing contract):
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-605-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:606:     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-607-     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-608-     *     (real vault-B material on create, pure timing filler otherwise);
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-609-     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-610-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:611:     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-612-     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:613:     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-614-     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-615-     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-616-     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-617-     * [UnlockOrAdd.Rejected] having written nothing.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-618-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-619-     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-620-     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:621:     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-622-     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-623-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:624:     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-625-     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:626:     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-627-     * target, so duress protection survives even a full pool.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-628-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-629-     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-630-     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-631-     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-632-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-633-     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-634-     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-635-     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-636-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:637:     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-638-     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-639-     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-640-     * create write is not confirmed durable.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-641-     */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-642-    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-643-        imageLock.withLock {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-644-            val image = canonical ?: run { open(); canonical!! }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-645-            val activeDek = dek ?: throw IllegalStateException("vault image not open")
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-646-            val decoded = decodeImage(image)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-647-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:648:            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-649-            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-650-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-651-            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-652-            // the try below so a throw during its generation (native crypto failure, OOM,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-653-            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-657-                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-658-                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-659-                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-660-                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-661-                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:662:                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-663-                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-664-                val candSlotIndex = randomVaultSlotIndex(ops)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-665-                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-666-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-667-                return when {
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-675-                            .getOrNull()?.let { wipe(it) }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-676-                        wipe(unlock.vaultKey)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-677-                        UnlockOrAdd.Burn
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-678-                    }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-679-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:680:                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-681-                    unlock != null -> {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-682-                        wipe(candKey)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-683-                        val pt = try {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-684-                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-685-                        } catch (t: Throwable) {
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-16- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-17- * Two properties are load-bearing and non-negotiable, identical to the web
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-18- * reference:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-19- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-20- *  1. The integer number of vaults is never stored. Every disk image contains
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt:21: *     exactly SLOT_COUNT slots; unused slots hold uniformly random bytes that
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-22- *     are byte-for-byte indistinguishable from a real wrapped key. A slot that
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-23- *     fails to decrypt is indistinguishable from a wrong passphrase, and the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-24- *     count of real vaults is unknowable from the stored material.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-25- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-26- *  2. Every passphrase attempt does identical work. tryPassphrase derives a key
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-32- * "real" or "decoy", logs slot structure, or leaves anything a decompiler could
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-33- * read that would reveal how many slots are occupied or where.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-34- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-35-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-36-/** Fixed number of slots on every disk image. Real or random, the count is constant. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt:37:const val SLOT_COUNT: Int = 4
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-38-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-39-/** Argon2id salt length, bytes. Mirrors kdf.ts SALT_BYTES. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-40-const val SALT_BYTES: Int = 16
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-41-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-42-/** Derived-key / vault-key length, bytes. Mirrors kdf.ts MASTER_KEY_BYTES. */
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-89-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-90- * Pluggable key deriver — defaults to Argon2id (see [argon2idDeriver]). Injectable
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-91- * so timing-parity tests can substitute a fast, deterministic stand-in without
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-92- * weakening production behavior. Mirrors vault.ts `KeyDeriver`.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-93- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt:94: * NOTE: the production deriver runs SLOT_COUNT × 64 MiB Argon2id per unlock and
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-95- * is CPU-heavy; see [tryPassphrase] and [argon2idDeriver].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-96- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-97- * PASSPHRASE TYPE (deliberate): the passphrase is a `String`, mirroring the web
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-98- * reference (a JS string). A JVM `String` is immutable and unwipeable, so it can
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt-99- * linger in heap until GC — a known, modest memory-forensics weakness. The
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-216-        // bad slot index) at CONSTRUCTION — rather than letting the first flush throw
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-217-        // and be swallowed by the background job, which would leave the session
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-218-        // permanently dirty and unflushable. Validated BEFORE any copy or wipe, so a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-219-        // rejected construction allocates no sensitive copy and leaves the caller's
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-220-        // arrays intact to handle.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt:221:        require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-222-        require(initialVaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-223-        require(initialPayload.size <= MAX_PAYLOAD_CONTENT_BYTES) { "content exceeds vault slot capacity" }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-224-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-225-        // Copy into our owned buffers, then take ownership by wiping the caller's
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSession.kt-226-        // originals. The VaultOpen the caller discards after construction then holds
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-37- * Byte-compatibility with the web reference (packages/crypto): Argon2id with the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-38- * exact kdf.ts parameters, and both the wrapped-key layer (vault.ts) and the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-39- * payload layer (apps/web storage.ts) are AES-256-GCM with a 12-byte nonce. The
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-40- * goal is auditability against the reference, not cross-device image sharing.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-41- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:42:interface VaultSodiumOps {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-43-    /**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-44-     * Argon2id (crypto_pwhash, ALG_ARGON2ID13) with the exact kdf.ts params:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-45-     * 64 MiB memory, 3 iterations, 16-byte salt, 32-byte output. libsodium
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-46-     * fixes parallelism internally (=1); there is no lanes parameter to set.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-47-     *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-48-     * CPU-HEAVY: ~64 MiB and hundreds of milliseconds per call. A full unlock
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:49:     * runs this SLOT_COUNT times; callers on a UI thread MUST run it (and
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-50-     * [tryPassphrase]) off the main thread.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-51-     */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-52-    fun argon2idDeriveKey(password: ByteArray, salt: ByteArray): ByteArray
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-53-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-54-    /**
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-64-     * AES-256-GCM open of a nonce(12) || ciphertext || tag(16) box. Returns null
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-65-     * on any authentication failure — a wrong key, a filler slot, or tampering,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-66-     * all indistinguishable by design (mirrors aead.ts aeadDecrypt throwing,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-67-     * which vault.ts / storage.ts treat as "no match").
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-68-     */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:69:    fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray): ByteArray?
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-70-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-71-    /** Cryptographically random bytes from the platform CSPRNG. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-72-    fun randomBytes(length: Int): ByteArray
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-73-}
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-74-
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-118-        nonce.copyInto(out, 0)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-119-        cipher.doFinal(plaintext, 0, plaintext.size, out, NONCE_BYTES)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-120-        return out
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-121-    }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-122-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:123:    override fun aeadDecrypt(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-124-        key: ByteArray,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-125-        box: ByteArray,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-126-        associatedData: ByteArray,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-127-    ): ByteArray? {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-128-        require(key.size == MASTER_KEY_BYTES) { "AES-256-GCM key must be $MASTER_KEY_BYTES bytes" }
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-173-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-174- * The default production key deriver: UTF-8-encode the passphrase (matching the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-175- * web, which hands a JS string to sodium.crypto_pwhash) and run Argon2id.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-176- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-177- * CPU-HEAVY — see [VaultSodiumOps.argon2idDeriveKey]. tryPassphrase invokes this
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:178: * SLOT_COUNT times.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-179- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-180-fun argon2idDeriver(ops: VaultSodiumOps): KeyDeriver =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-181-    { passphrase, salt ->
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-182-        // The passphrase String itself is immutable and unwipeable (a JVM limit,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt-183-        // same as the web's JS string), but the transient UTF-8 ByteArray we
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-34- * only that a burn FEATURE exists (public), never how many vaults slots 1..N-1 hold.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-35- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-36-const val BURN_SLOT_INDEX: Int = 0
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-37-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-38-/** The vault pool — slots that may hold a real vault. Slot 0 (burn) is excluded. */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:39:val VAULT_SLOT_RANGE: IntRange = (BURN_SLOT_INDEX + 1) until SLOT_COUNT
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-40-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-41-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-42- * A uniformly-random index into the VAULT pool (never [BURN_SLOT_INDEX]). The SINGLE
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-43- * source of truth for slot-0 reservation, used by BOTH the everyday-vault placement
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-44- * ([createVaultSlots]) and blind second-vault creation
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-81- * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-82- * lifetime is identical to [sealSlot]'s.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-83- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-84- * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-85- * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:86: * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-87- * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-88- * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-89- * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-90- * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-91- * would equally break every other slot operation; failing closed here is correct.
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-117-        wipe(masterKey)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-118-    }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-119-}
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-120-
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-121-/**
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:122: * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-123- * real vault sealed under [passphrase]. The rest are random filler. The returned
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-124- * vaultKey is the random key the caller should use to encrypt the vault's data.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-125- * The real slot is placed at a CSPRNG-random index IN THE VAULT POOL
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:126: * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-127- * stays reserved for the burn credential (see [BURN_SLOT_INDEX]). Slot 0 is left as
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-128- * filler on a fresh onboarding (unarmed burn), indistinguishable from any other slot.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-129- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-130-fun createVaultSlots(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-131-    passphrase: String,
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-134-): CreatedVault {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-135-    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-136-    // On SUCCESS the caller owns (and later wipes) vaultKey; on ANY failure path
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-137-    // after generation, wipe it here so no live key is abandoned in heap.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-138-    try {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:139:        val slots = ArrayList<KeySlot>(SLOT_COUNT)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:140:        for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:141:        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-142-        slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-143-        return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-144-    } catch (t: Throwable) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-145-        wipe(vaultKey)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-146-        throw t
--
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-203- * whether a passphrase matches slot 0, slot N, or nothing — a break here is a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-204- * plausible-deniability side-channel. The first match is recorded but the loop
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-205- * runs to completion regardless; any later match's vault key is wiped, and every
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-206- * derived master key is wiped whether it matched or not.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-207- *
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:208: * CPU-HEAVY: SLOT_COUNT × Argon2id (64 MiB each) with the production deriver.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-209- * Callers on a UI thread MUST run this off the main thread.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-210- */
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-211-fun tryPassphrase(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-212-    passphrase: String,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-213-    slots: List<KeySlot>,
     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
     2	// Licensed under the GNU Affero General Public License v3.0 or later.
     3	// See the LICENSE file in the repository root for full license text.
     4	// SPDX-License-Identifier: AGPL-3.0-only
     5	
     6	package com.zitrone.app
     7	
     8	/**
     9	 * Composable-free unlock-router logic for a vault install (posture B). Holds ONLY the
    10	 * decisions that must be testable and constant across the passphrase / biometric paths:
    11	 * the client-side backoff schedule, the uniform failure message, and the
    12	 * biometric-availability gate. All I/O (the off-main `imageStore.unlock`, the
    13	 * BiometricPrompt) stays in the caller — this class touches no Android and no store, so
    14	 * it host-unit-tests directly.
    15	 *
    16	 * SLOT-AGNOSTIC + leak-free: it never sees a passphrase, a key, or a slot; the failure
    17	 * message is a single generic string (no per-slot branch); the backoff counter is RAM-only
    18	 * (cleared on process death and on any success), never persisted.
    19	 */
    20	class VaultUnlockRouter {
    21	
    22	    /**
    23	     * Consecutive failed passphrase attempts THIS process — RAM only, so a relaunch resets
    24	     * it (the store already guarantees identical work per attempt, so a persisted lockout
    25	     * would add nothing but a footgun). Reset on success.
    26	     */
    27	    private var failedAttempts: Int = 0
    28	
    29	    /**
    30	     * The delay to enforce BEFORE the next passphrase attempt is accepted, from the count of
    31	     * prior failures: 500 ms × attempts, capped at [MAX_BACKOFF_MS]. Zero on a fresh counter,
    32	     * so the first attempt is never delayed.
    33	     */
    34	    fun backoffDelayMs(): Long = (BACKOFF_STEP_MS * failedAttempts).coerceAtMost(MAX_BACKOFF_MS)
    35	
    36	    /** Record a failed passphrase attempt (advances the backoff). */
    37	    fun recordFailure() {
    38	        failedAttempts++
    39	    }
    40	
    41	    /** Clear the backoff after any successful unlock. */
    42	    fun recordSuccess() {
    43	        failedAttempts = 0
    44	    }
    45	
    46	    /**
    47	     * Whether to OFFER the biometric affordance: only when a wrap is enabled AND the platform
    48	     * can authenticate BIOMETRIC_STRONG right now. An invalidated key (a new enrollment) reads
    49	     * as not-enabled by the caller (its blob is cleared only after the next passphrase unlock),
    50	     * so this is the single availability gate — no per-slot logic.
    51	     */
    52	    fun biometricOffered(enabled: Boolean, canAuthenticateStrong: Boolean): Boolean =
    53	        enabled && canAuthenticateStrong
    54	
    55	    companion object {
    56	        /** Uniform, generic failure — never names a slot, a count, or which factor failed. */
    57	        const val UNIFORM_FAILURE = "Couldn't unlock. Check your passphrase and try again."
    58	
    59	        /** Honest note shown when a biometric key was invalidated by a new enrollment. */
    60	        const val BIOMETRIC_REENROLL_NOTE =
    61	            "Biometric unlock needs re-enabling after a passphrase unlock."
    62	
    63	        /**
    64	         * DISTINCT from [UNIFORM_FAILURE]: surfaced only when the vault IMAGE itself is
    65	         * damaged/unreadable (VaultImageException.CorruptImage / MissingImage), which is NOT a
    66	         * passphrase guess — so it must not be flattened into the wrong-passphrase oracle-avoiding
    67	         * uniform failure. Names no slot and no credential.
    68	         */
    69	        const val IMAGE_UNREADABLE_NOTE =
    70	            "This vault couldn't be opened — the stored image may be damaged."
    71	
    72	        private const val BACKOFF_STEP_MS = 500L
    73	        private const val MAX_BACKOFF_MS = 8_000L
    74	    }
    75	}

exec
/bin/bash -lc "rg -n \"attemptUnlockOrAdd|UnlockOrAdd|triple-entry|triple entry|consecutive\" apps/android/app/src/main/java -g'*.kt'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt | sed -n '95,160p'; git show 3c598ad:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | rg -n \"createVaultSlots|randomIndex\" -C 8" in /root/zitrone
 succeeded in 0ms:
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:45: * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:85: * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:165: * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:146:sealed interface UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:148:    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:155:    data object Burn : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:158:    data class Created(val open: VaultOpen) : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:161:    data object Rejected : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:612:     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:613:     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:616:     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:617:     * [UnlockOrAdd.Rejected] having written nothing.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:620:     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:633:     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:635:     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:642:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:677:                        UnlockOrAdd.Burn
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:693:                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:718:                            UnlockOrAdd.Rejected
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:745:                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:756:                        UnlockOrAdd.Rejected
    95	        return out
    96	    }
    97	
    98	    override fun aeadEncrypt(
    99	        key: ByteArray,
   100	        plaintext: ByteArray,
   101	        associatedData: ByteArray,
   102	    ): ByteArray {
   103	        require(key.size == MASTER_KEY_BYTES) { "AES-256-GCM key must be $MASTER_KEY_BYTES bytes" }
   104	        // Fresh random 12-byte nonce per call — nonce reuse under GCM is
   105	        // catastrophic, so the caller never supplies one.
   106	        val nonce = randomBytes(NONCE_BYTES)
   107	        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
   108	        cipher.init(
   109	            Cipher.ENCRYPT_MODE,
   110	            SecretKeySpec(key, "AES"),
   111	            GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce),
   112	        )
   113	        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
   114	        // Write straight into one pre-sized nonce||ciphertext||tag buffer — avoids
   115	        // a second full-size copy on the 256 KiB payload path. JCE GCM appends the
   116	        // 16-byte tag to the ciphertext (matching aead.ts's WebCrypto layout).
   117	        val out = ByteArray(NONCE_BYTES + plaintext.size + AEAD_TAG_BYTES)
   118	        nonce.copyInto(out, 0)
   119	        cipher.doFinal(plaintext, 0, plaintext.size, out, NONCE_BYTES)
   120	        return out
   121	    }
   122	
   123	    override fun aeadDecrypt(
   124	        key: ByteArray,
   125	        box: ByteArray,
   126	        associatedData: ByteArray,
   127	    ): ByteArray? {
   128	        require(key.size == MASTER_KEY_BYTES) { "AES-256-GCM key must be $MASTER_KEY_BYTES bytes" }
   129	        if (box.size < NONCE_BYTES + AEAD_TAG_BYTES) return null
   130	        return try {
   131	            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
   132	            cipher.init(
   133	                Cipher.DECRYPT_MODE,
   134	                SecretKeySpec(key, "AES"),
   135	                // Nonce is the first NONCE_BYTES of box.
   136	                GCMParameterSpec(AEAD_TAG_BYTES * 8, box, 0, NONCE_BYTES),
   137	            )
   138	            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
   139	            // Decrypt directly from box at the nonce offset — no 256 KiB copyOfRange.
   140	            cipher.doFinal(box, NONCE_BYTES, box.size - NONCE_BYTES)
   141	        } catch (e: GeneralSecurityException) {
   142	            // Tag failure: wrong key, a filler slot, or tampering — all reported
   143	            // the same way, as "no match" (mirrors aead.ts aeadDecrypt throwing).
   144	            // GeneralSecurityException (not just AEADBadTagException) because some
   145	            // providers surface a GCM tag mismatch as its parent BadPaddingException
   146	            // — a filler slot MUST decrypt-fail silently, never escape and crash
   147	            // tryPassphrase.
   148	            null
   149	        }
   150	    }
   151	
   152	    override fun randomBytes(length: Int): ByteArray {
   153	        val out = ByteArray(length)
   154	        sodium.randombytes_buf(out, length)
   155	        return out
   156	    }
   157	
   158	    private companion object {
   159	        /** kdf.ts ARGON2ID_PARAMS.iterations (opslimit). */
   160	        const val ARGON2ID_OPSLIMIT: Long = 3L
46-}
47-
48-/**
49- * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
50- * real vault sealed under [passphrase]. The rest are random filler. The returned
51- * vaultKey is the random key the caller should use to encrypt the vault's data.
52- * The real slot is placed at a CSPRNG-random index so position leaks nothing.
53- */
54:fun createVaultSlots(
55-    passphrase: String,
56-    ops: VaultSodiumOps,
57-    deriver: KeyDeriver = argon2idDeriver(ops),
58-): CreatedVault {
59-    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
60-    // On SUCCESS the caller owns (and later wipes) vaultKey; on ANY failure path
61-    // after generation, wipe it here so no live key is abandoned in heap.
62-    try {
63-        val slots = ArrayList<KeySlot>(SLOT_COUNT)
64-        for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
65:        val slotIndex = randomIndex(SLOT_COUNT, ops)
66-        slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
67-        return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
68-    } catch (t: Throwable) {
69-        wipe(vaultKey)
70-        throw t
71-    }
72-}
73-
--
95-    // passphrase would shadow one vault and silently make it unreachable.
96-    tryPassphrase(passphrase, slots, ops, deriver)?.let {
97-        wipe(it.vaultKey)
98-        throw IllegalArgumentException("passphrase already unlocks an existing vault")
99-    }
100-    val free = ArrayList<Int>()
101-    for (i in slots.indices) if (i !in occupied) free.add(i)
102-    if (free.isEmpty()) throw IllegalStateException("no free key slots")
103:    val slotIndex = free[randomIndex(free.size, ops)]
104-    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
105-    try {
106-        val next = slots.toMutableList()
107-        next[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
108-        return CreatedVault(slots = next, vaultKey = vaultKey, slotIndex = slotIndex)
109-    } catch (t: Throwable) {
110-        wipe(vaultKey)
111-        throw t
--
161-fun wipe(bytes: ByteArray) {
162-    bytes.fill(0)
163-}
164-
165-/**
166- * Uniform random index in [0, n) drawn from the CSPRNG. Reads 4 CSPRNG bytes as
167- * a big-endian unsigned 32-bit value and reduces mod n (no meaningful modulo
168- * bias for the small n used here). Byte-for-byte the same construction as
169: * vault.ts randomIndex.
170- */
171:fun randomIndex(n: Int, ops: VaultSodiumOps): Int {
172-    val buf = ops.randomBytes(4)
173-    val v = ((buf[0].toInt() and 0xff) shl 24) or
174-        ((buf[1].toInt() and 0xff) shl 16) or
175-        ((buf[2].toInt() and 0xff) shl 8) or
176-        (buf[3].toInt() and 0xff)
177-    val unsigned = v.toLong() and 0xffffffffL
178-    return (unsigned % n).toInt()
179-}

codex
# Findings report

## Finding 1 — Stale security-critical API documentation

**SEVERITY:** Low  
**FILE / FUNCTION:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 607–640  
**DEFECT MECHANISM:** The function contract still states that candidate sealing uses one wrapped-key GCM, that creation clears both delete markers, and that marker-clear failure can throw `NotDurable`. Actual code now performs two candidate wrapped-key GCM operations and never clears either marker.  
**FAILURE / ATTACK SCENARIO:** A future maintainer relies on this contract while changing timing accounting or deletion handling and incorrectly restores marker clearing or five-GCM assumptions, weakening the reviewed invariants.

## B1 fail-closed marker handling — Clean

`attemptUnlockOrAdd` is a pure marker reader.

Every marker-touching production line is:

- `create`, lines 469–473: reads markers and may call `clearBothMarkersDurably`.
- `attemptUnlockOrAdd`, lines 710–712: reads both markers only.
- `markDeleteIntent`, lines 962–964: writes intent.
- `markServerDeleteConfirmed`, lines 966–968: writes confirmed.
- `clearDeleteIntent`, lines 977–986: deletes intent.
- `clearBothMarkersDurably`, lines 997–1008: deletes both.
- `writeDurableMarker`, lines 1012–1019: creates a supplied marker.
- `destroy`, line 1038: writes confirmed through `writeDurableMarker`.

No call to `clearBothMarkersDurably`, `clearDeleteIntent`, marker deletion, or marker creation is reachable from `attemptUnlockOrAdd`.

When either `Files.notExists` check is false, lines 713–718 perform `sealPayload(candKey, ByteArray(0), ops)` and return `UnlockOrAdd.Rejected`; no exception is intentionally raised and neither marker is modified. Exceptions from the crypto provider may still propagate, as on ordinary rejection.

## TOCTOU — Clean within the application concurrency model

`attemptUnlockOrAdd` holds one uninterrupted `imageLock.withLock` from line 643 through return. The marker checks at lines 710–712 and image write at line 731 occur without lock release.

`markDeleteIntent` and `markServerDeleteConfirmed` acquire the same lock. `create`, `destroy`, and marker-clearing operations also use that lock. `open()` is reentrant under the same `ReentrantLock`; it does not release it.

No application callback derives marker state outside the lock. Crypto and injected filesystem callbacks execute under the lock but production implementations do not write marker files. Direct mutation by another process or external filesystem actor is outside this in-process lock guarantee.

## B2 self-verifying seal — Clean

**FILE / FUNCTION:** `VaultSlots.kt`, `sealSlotSelfVerifying`, lines 93–119

- Equality uses `MessageDigest.isEqual` at line 109.
- Production GCM decrypt of the freshly generated 60-byte wrapped key returns the original 32-byte plaintext, so both compared inputs are equal length.
- The same `masterKey` performs encryption and verification; no second derivation occurs.
- Its lifetime extends only through the immediate verification, then the outer `finally` wipes it at lines 116–117.
- A recovered key is wiped by the inner `finally`, including mismatch throws.
- Null recovery throws before a recovered array exists, while the outer `finally` still wipes `masterKey`.
- Candidate sealing completes at line 665 before branch selection and before any persistence at line 731.

No persist can precede successful self-verification.

## Timing parity at six wrapped-key GCM operations — Clean for normal outcomes

`SLOT_COUNT` is four. Every outcome and every matching-slot position executes:

- Four Argon2id derivations and four wrapped-key decrypts in the complete `tryPassphrase` sweep.
- One Argon2id derivation in `sealSlotSelfVerifying`.
- One candidate wrapped-key encrypt.
- One candidate wrapped-key verification decrypt.
- Exactly one 256 KiB payload GCM.

Totals: exactly five Argon2id and six wrapped-key GCM operations.

Payload operation by outcome:

- Unlock: one payload decrypt.
- Burn: one attempted payload decrypt.
- Create: one payload encrypt.
- Ordinary reject: one throwaway payload encrypt.
- Marker-present create rejection: one identical throwaway payload encrypt.

All three triple-entry positions therefore have the same counts. Create alone additionally performs outer-image GCM plus persistence; this existing residual remains outcome-dependent.

## F4 wipe discipline — Clean for key material

`candKey` is allocated inside the protected `try` and assigned to `candKeyForCleanup` through `also` at line 663. There is no reassignment or intervening operation between allocation return and mirror assignment.

Every throw after assignment reaches lines 763–764 and wipes both the candidate and any matched `unlock.vaultKey`. `tryPassphrase` independently wipes a partially found key if its own sweep throws.

Double wipes are harmless. Successful `Created` and `Unlocked` returns do not enter the catch, so handed-off keys remain live. Rejected and burn paths wipe their keys before returning.

## F9 and biometric range tightening — Clean, with explicit upgrade behavior

**Files / functions:**

- `VaultImageStore.kt`, `unlockWithKey`, lines 573–597
- `BiometricUnlockStore.kt`, `load`, lines 38–53
- `VaultSlots.kt`, `createVaultSlots`, lines 130–148

The A-only invariant is preserved: `enableBiometricFromSession` stores only the current session’s slot and wrap; current-format A is created exclusively through `randomVaultSlotIndex`, which returns slots `1..SLOT_COUNT-1`.

No legitimate v3 A-bound wrap can name slot 0.

A 0.9.1 v2 image could legitimately contain A in slot 0 and have a biometric wrap naming slot 0. On upgrade:

1. `BiometricUnlockStore.load()` now returns null, so biometric appears disabled.
2. Independently, the v2 image is never unlocked: `open()` raises `LegacyImage`.
3. UI routes to onboarding.
4. Creating the replacement v3 vault retires the complete v2 image.
5. Settings preferences, including the old biometric wrap, remain, but it stays invalid because it names slot 0 until re-enrollment overwrites it.

Thus the range tightening does change old slot-0 biometric state to “not enabled,” but it does not cause additional access loss beyond the deliberate v2 retirement policy.

## Router/triple-entry and general regression review — Clean

Marker-present creation returns the same `Rejected` value as an ordinary failed attempt. `attemptUnlockOrAdd` holds no triple-entry state, so it cannot itself leak, retry, or loop.

No production caller of `attemptUnlockOrAdd` currently exists in this source tree; only tests call it. Consequently, there is presently no end-to-end router behavior beyond the method’s uniform `Rejected` result to assess.

The `when` restructure preserves precedence: burn match, vault match, requested create, then reject. Canonical state advances only after the image rename. The existing DEK is reused and not rewritten, so no new DEK/image desynchronization path was introduced.

**Overall verdict: PASS with one Low-severity stale-contract documentation finding; the requested B1, TOCTOU, B2, timing, F4, and F9 invariants hold in actual source.**
tokens used
76,103
# Findings report

## Finding 1 — Stale security-critical API documentation

**SEVERITY:** Low  
**FILE / FUNCTION:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 607–640  
**DEFECT MECHANISM:** The function contract still states that candidate sealing uses one wrapped-key GCM, that creation clears both delete markers, and that marker-clear failure can throw `NotDurable`. Actual code now performs two candidate wrapped-key GCM operations and never clears either marker.  
**FAILURE / ATTACK SCENARIO:** A future maintainer relies on this contract while changing timing accounting or deletion handling and incorrectly restores marker clearing or five-GCM assumptions, weakening the reviewed invariants.

## B1 fail-closed marker handling — Clean

`attemptUnlockOrAdd` is a pure marker reader.

Every marker-touching production line is:

- `create`, lines 469–473: reads markers and may call `clearBothMarkersDurably`.
- `attemptUnlockOrAdd`, lines 710–712: reads both markers only.
- `markDeleteIntent`, lines 962–964: writes intent.
- `markServerDeleteConfirmed`, lines 966–968: writes confirmed.
- `clearDeleteIntent`, lines 977–986: deletes intent.
- `clearBothMarkersDurably`, lines 997–1008: deletes both.
- `writeDurableMarker`, lines 1012–1019: creates a supplied marker.
- `destroy`, line 1038: writes confirmed through `writeDurableMarker`.

No call to `clearBothMarkersDurably`, `clearDeleteIntent`, marker deletion, or marker creation is reachable from `attemptUnlockOrAdd`.

When either `Files.notExists` check is false, lines 713–718 perform `sealPayload(candKey, ByteArray(0), ops)` and return `UnlockOrAdd.Rejected`; no exception is intentionally raised and neither marker is modified. Exceptions from the crypto provider may still propagate, as on ordinary rejection.

## TOCTOU — Clean within the application concurrency model

`attemptUnlockOrAdd` holds one uninterrupted `imageLock.withLock` from line 643 through return. The marker checks at lines 710–712 and image write at line 731 occur without lock release.

`markDeleteIntent` and `markServerDeleteConfirmed` acquire the same lock. `create`, `destroy`, and marker-clearing operations also use that lock. `open()` is reentrant under the same `ReentrantLock`; it does not release it.

No application callback derives marker state outside the lock. Crypto and injected filesystem callbacks execute under the lock but production implementations do not write marker files. Direct mutation by another process or external filesystem actor is outside this in-process lock guarantee.

## B2 self-verifying seal — Clean

**FILE / FUNCTION:** `VaultSlots.kt`, `sealSlotSelfVerifying`, lines 93–119

- Equality uses `MessageDigest.isEqual` at line 109.
- Production GCM decrypt of the freshly generated 60-byte wrapped key returns the original 32-byte plaintext, so both compared inputs are equal length.
- The same `masterKey` performs encryption and verification; no second derivation occurs.
- Its lifetime extends only through the immediate verification, then the outer `finally` wipes it at lines 116–117.
- A recovered key is wiped by the inner `finally`, including mismatch throws.
- Null recovery throws before a recovered array exists, while the outer `finally` still wipes `masterKey`.
- Candidate sealing completes at line 665 before branch selection and before any persistence at line 731.

No persist can precede successful self-verification.

## Timing parity at six wrapped-key GCM operations — Clean for normal outcomes

`SLOT_COUNT` is four. Every outcome and every matching-slot position executes:

- Four Argon2id derivations and four wrapped-key decrypts in the complete `tryPassphrase` sweep.
- One Argon2id derivation in `sealSlotSelfVerifying`.
- One candidate wrapped-key encrypt.
- One candidate wrapped-key verification decrypt.
- Exactly one 256 KiB payload GCM.

Totals: exactly five Argon2id and six wrapped-key GCM operations.

Payload operation by outcome:

- Unlock: one payload decrypt.
- Burn: one attempted payload decrypt.
- Create: one payload encrypt.
- Ordinary reject: one throwaway payload encrypt.
- Marker-present create rejection: one identical throwaway payload encrypt.

All three triple-entry positions therefore have the same counts. Create alone additionally performs outer-image GCM plus persistence; this existing residual remains outcome-dependent.

## F4 wipe discipline — Clean for key material

`candKey` is allocated inside the protected `try` and assigned to `candKeyForCleanup` through `also` at line 663. There is no reassignment or intervening operation between allocation return and mirror assignment.

Every throw after assignment reaches lines 763–764 and wipes both the candidate and any matched `unlock.vaultKey`. `tryPassphrase` independently wipes a partially found key if its own sweep throws.

Double wipes are harmless. Successful `Created` and `Unlocked` returns do not enter the catch, so handed-off keys remain live. Rejected and burn paths wipe their keys before returning.

## F9 and biometric range tightening — Clean, with explicit upgrade behavior

**Files / functions:**

- `VaultImageStore.kt`, `unlockWithKey`, lines 573–597
- `BiometricUnlockStore.kt`, `load`, lines 38–53
- `VaultSlots.kt`, `createVaultSlots`, lines 130–148

The A-only invariant is preserved: `enableBiometricFromSession` stores only the current session’s slot and wrap; current-format A is created exclusively through `randomVaultSlotIndex`, which returns slots `1..SLOT_COUNT-1`.

No legitimate v3 A-bound wrap can name slot 0.

A 0.9.1 v2 image could legitimately contain A in slot 0 and have a biometric wrap naming slot 0. On upgrade:

1. `BiometricUnlockStore.load()` now returns null, so biometric appears disabled.
2. Independently, the v2 image is never unlocked: `open()` raises `LegacyImage`.
3. UI routes to onboarding.
4. Creating the replacement v3 vault retires the complete v2 image.
5. Settings preferences, including the old biometric wrap, remain, but it stays invalid because it names slot 0 until re-enrollment overwrites it.

Thus the range tightening does change old slot-0 biometric state to “not enabled,” but it does not cause additional access loss beyond the deliberate v2 retirement policy.

## Router/triple-entry and general regression review — Clean

Marker-present creation returns the same `Rejected` value as an ordinary failed attempt. `attemptUnlockOrAdd` holds no triple-entry state, so it cannot itself leak, retry, or loop.

No production caller of `attemptUnlockOrAdd` currently exists in this source tree; only tests call it. Consequently, there is presently no end-to-end router behavior beyond the method’s uniform `Rejected` result to assess.

The `when` restructure preserves precedence: burn match, vault match, requested create, then reject. Canonical state advances only after the image rename. The existing DEK is reused and not rewritten, so no new DEK/image desynchronization path was introduced.

**Overall verdict: PASS with one Low-severity stale-contract documentation finding; the requested B1, TOCTOU, B2, timing, F4, and F9 invariants hold in actual source.**
