OpenAI Codex v0.145.0
--------
workdir: /root/zitrone
model: gpt-5.6-sol
provider: openai
approval: never
sandbox: read-only
reasoning effort: none
reasoning summaries: none
session id: 019f945e-e82e-7201-9d13-afaa6f578ee9
--------
user
You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes, do NOT edit any file.

## Product & threat model
Zitrone: a production Signal-Protocol end-to-end encrypted messenger shipping to the Play Store, with a plausible-deniability second vault. Adversary has PHYSICAL DEVICE ACCESS and FORENSIC CAPABILITY; assume CRASH / PROCESS-DEATH at ANY instruction. This delta touches the CREATE path's persist sequence — the exact region where a prior 16-round hardening arc found most of its real defects and where this PR's own first round was rejected. **A small diff is NOT evidence of safety. Treat as guilty until proven otherwise.**

## What to review
The DELTA `296ebc6..8f4545d` ONLY, on branch `feat/0.9.2-vault-slotb-pr1` in this repo (/root/zitrone). Start with `git show 8f4545d` and `git diff 296ebc6..8f4545d`. Verify against ACTUAL SOURCE, not the summaries. The delta adds a create-path PAYLOAD self-verify to `attemptUnlockOrAdd` (closing the prior round's agreed non-blocking residual "G3").
- Primary source: apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt (`attemptUnlockOrAdd`, esp. the Created / markers-absent branch and its `openPayload(candKey, sealedGenesis)` + `MessageDigest.isEqual(...)` verify; the surrounding `try`/`catch`; the KDoc), apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultPayload.kt (`sealPayload`/`openPayload`/`unpad`), and the tests apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt (the parity test + `MisSealingPayloadOps`).
- Context only (do NOT re-litigate): /root/l00prite/pr1-fix-review-{codex,grok}.md (both PASSed `321b358..296ebc6`; G3 was their agreed residual, now closed). /root/l00prite/pr1-attemptUnlockOrAdd-spec.md §5 crypto-budget table.

## Verify specifically (binding — do not abbreviate)

1. CONTENT COMPARE, NOT DECRYPT-SUCCESS — Confirm the verify genuinely compares the RECOVERED PLAINTEXT against `genesisPayload` (not merely that `openPayload` returned non-null). Confirm `MessageDigest.isEqual` is used correctly: recovered plaintext and `genesisPayload` are the SAME length (trace `sealPayload` pad → `openPayload` unpad, so the round-trip returns exactly `genesisPayload.size` bytes), so the compare takes the constant-time equal-length path rather than a length-mismatch short-circuit that could leak. Confirm a self-consistent-but-WRONG-content AEAD result (valid box, decrypts fine, wrong bytes) actually FAILS the check (the `MisSealingPayloadOps` test models exactly this — verify it truly exercises the mismatch branch, not the null/did-not-open branch).

2. THROW-BEFORE-PERSIST — Confirm the verify runs and can throw BEFORE any persistence: before `encodeImage`, before `ops.aeadEncrypt(activeDek, …)`, before `atomicWrite`, before `canonical` advances, and without touching the DEK. On a verify failure NOTHING partial may reach disk or mutate in-memory canonical/dek. Confirm the throw propagates out of `attemptUnlockOrAdd` (via the outer catch) with the store state unchanged.

3. WIPE DISCIPLINE ON THE NEW SEAM — Confirm `verifyPt` (the decrypted plaintext copy) is wiped in a `finally` on EVERY path including the mismatch throw; confirm `candKey` is wiped by the outer F4 catch on the verify-throw path; confirm no new path strands key material or plaintext. Confirm the inner `finally` wipe of `verifyPt` and the outer catch's `wipe(candKey)` / `unlock?.vaultKey` do NOT combine into a use-after-wipe or a double-free-style hazard, and that a handed-off key on the SUCCESS path is never wiped.

4. PARITY UNCHANGED FOR NON-CREATE OUTCOMES — Confirm unlock, burn, ordinary reject, AND marker-present reject are each STILL exactly: 1 payload GCM, 6 wrapped-key GCM, 5 Argon2id. The successful-create path moving to 2 payload GCM is accepted/intended — verify it did NOT perturb any other outcome and that the marker-present reject still matches the ordinary reject budget EXACTLY (no accidental second payload GCM on the fail-closed path). Re-derive from source, not from the test names.

5. NEW DEFECTS from this delta — anything the added verify introduced: an exception type that a caller (future PR-2 router) would handle differently from `CorruptImage`/`NotDurable`; `openPayload` returning null vs throwing and whether both are handled; interaction with the F4 cleanup-var (does `verifyPt` need mirroring too? it is local to the else-branch — confirm no throw between its allocation and its own finally can strand it); any canonical/dek desync; any change to the create durability/atomicity ordering; timing/observability of the extra op relative to the outcome.

6. DOC/TEST ACCURACY — Confirm the updated `attemptUnlockOrAdd` KDoc and the spec §5 table now match ACTUAL behavior with NO stale surface remaining (a stale doc describing removed behavior was the prior round's G1 Low). Confirm the parity test asserts create=2 / others=1 payload GCM and that the test's method name and comments are accurate.

## Output format
A structured findings report. For EACH finding: SEVERITY (Critical / High / Medium / Low / Info), exact FILE + FUNCTION (+ line), DEFECT MECHANISM, and a concrete FAILURE/ATTACK SCENARIO. If an item is clean, say so explicitly and why. End with a one-line overall verdict. Report ONLY — no fixes.

codex
I’ll inspect the exact commit and range first, then trace the source-level control flow, crypto counters, cleanup, and the referenced review/spec documents. I’ll keep this read-only and report findings only.
exec
/bin/bash -lc 'git status --short --branch && git show --stat --oneline 8f4545d && git show --format=fuller --find-renames 8f4545d -- apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt && git diff --stat 296ebc6..8f4545d && git diff --find-renames 296ebc6..8f4545d' in /root/zitrone
 succeeded in 0ms:
## feat/0.9.2-vault-slotb-pr1
?? CLAUDE.md
8f4545d fix(vault): G3 — add create-path payload self-verify (constant-time content compare)
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 36 +++++++++++---
 .../java/com/zitrone/app/AttemptUnlockOrAddTest.kt | 56 +++++++++++++++++++---
 2 files changed, 79 insertions(+), 13 deletions(-)
commit 8f4545dc8933bac6c514fc74dd39041c8251f32d
Author:     jackofall1232 <jackofall1232@gmail.com>
AuthorDate: Fri Jul 24 13:10:58 2026 +0000
Commit:     jackofall1232 <jackofall1232@gmail.com>
CommitDate: Fri Jul 24 13:10:58 2026 +0000

    fix(vault): G3 — add create-path payload self-verify (constant-time content compare)
    
    Closes the remaining half of B2 (both fix-round reviewers flagged it as an
    accepted residual; user elected to close it). Worst failure shape for this
    feature: silent, surfaces only after process death, leaves a full working
    session over a vault that is then permanently unopenable.
    
    - attemptUnlockOrAdd Created branch (markers-absent): after sealing the genesis
      payload, openPayload(candKey, sealedGenesis) and CONSTANT-TIME-COMPARE the
      recovered plaintext to genesisPayload (MessageDigest.isEqual), then wipe the
      decrypted copy. Not merely "decryption succeeded" — a miscomputing AEAD that
      produces a self-consistent but wrong-content box must fail. Throws before any
      persist, exactly like B2's wrapped-key self-verify.
    - Create-only: one extra 256 KiB GCM inside the already-accepted create-persist
      residual (alongside the outer GCM + write). Touches no other outcome, so
      cross-outcome parity and the 5-Argon2id invariant are unchanged; the
      marker-present create still fails closed to the 1-payload reject budget.
    
    Tests: mis-sealing-payload provider (self-consistent, wrong content) → create
    throws IllegalStateException and persists nothing; parity test now asserts
    create=2 payload GCM and unlock/burn/reject/marker-reject=1 (all unchanged),
    5 Argon2id + 6 wrapped GCM across all outcomes. KDoc + spec crypto-budget table
    updated. Full unit suite + assembleRelease green.
    
    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01N81mnevbUZTv66x1impLU5

diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index c9e171c..73e0d32 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -607,7 +607,10 @@ class VaultImageStore internal constructor(
      *   - ONE unconditional candidate-slot seal ([sealSlotSelfVerifying]) — 1 more Argon2id + TWO
      *     wrapped-key GCM (the seal encrypt + a same-master-key verify decrypt, 0 extra Argon2id); real
      *     vault-B material on create, pure timing filler otherwise. Total: 5 Argon2id + 6 wrapped-key GCM;
-     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
+     *   - EXACTLY ONE 256 KiB payload GCM on EVERY outcome (open on a match, seal on create/reject). A
+     *     SUCCESSFUL create additionally does a SECOND payload GCM (a self-verify open, see DELETE MARKERS /
+     *     the create branch) — a create-only residual, alongside the outer GCM + write, not a per-outcome
+     *     distinguisher (the marker-present create fails closed to the single-payload-GCM reject budget).
      *
      * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
      * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
@@ -618,10 +621,11 @@ class VaultImageStore internal constructor(
      * (see DELETE MARKERS below), in which case it FAILS CLOSED to [UnlockOrAdd.Rejected]. With [create]
      * false it returns [UnlockOrAdd.Rejected] having written nothing.
      *
-     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
-     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
-     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
-     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
+     * TIMING RESIDUAL (documented, accepted): only the SUCCESSFUL create path additionally PERSISTS (a
+     * second payload GCM = the self-verify open, the ~1 MiB outer GCM, an atomic write + dir-fsync that
+     * gate a durable [UnlockOrAdd.Created]). That work is post-outcome and dwarfed by the SLOT_COUNT+1
+     * Argon2id; it is the same class as the payload-open asymmetry and is NOT a per-attempt KDF-level
+     * distinguisher. A marker-present create fails closed to the exact single-payload-GCM reject budget.
      *
      * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
      * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
@@ -725,8 +729,28 @@ class VaultImageStore internal constructor(
                             wipe(throwaway)
                             UnlockOrAdd.Rejected
                         } else {
-                            // The 1×256 KiB payload GCM for the create branch.
+                            // Payload GCM #1 (the seal). A SUCCESSFUL create is the one outcome that persists,
+                            // so it is also the one that gets a second, create-only payload GCM below — inside
+                            // the already-accepted create-persist residual (alongside the outer GCM + write),
+                            // touching no other outcome, so cross-outcome parity + the 5-Argon2id invariant hold.
                             val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
+                            // B2 PAYLOAD HALF (G3): prove the sealed PAYLOAD actually opens to genesisPayload
+                            // with candKey BEFORE persisting. The wrapped-key self-verify (sealSlotSelfVerifying)
+                            // covers the key; this covers the payload. It must CONSTANT-TIME-COMPARE the recovered
+                            // plaintext to genesisPayload — not merely confirm decryption succeeded — so a
+                            // miscomputing AEAD that produced a self-consistent-but-WRONG-content box is caught.
+                            // The failure it closes is the worst shape for this feature: silent, surfacing only
+                            // after process death, leaving a full working session over a vault that is then
+                            // permanently unopenable. Throws before ANY write, exactly like B2's wrapped verify.
+                            val verifyPt = openPayload(candKey, sealedGenesis, ops)
+                                ?: throw IllegalStateException("sealed payload failed self-verify (did not open)")
+                            try {
+                                check(java.security.MessageDigest.isEqual(verifyPt, genesisPayload)) {
+                                    "sealed payload failed self-verify (recovered plaintext mismatch)"
+                                }
+                            } finally {
+                                wipe(verifyPt)
+                            }
                             val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
                             val newPayloads =
                                 decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
diff --git a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
index f71023f..bdba29d 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
@@ -15,6 +15,7 @@ import com.zitrone.app.crypto.vault.KeyDeriver
 import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
 import com.zitrone.app.crypto.vault.LibsodiumVaultOps
 import com.zitrone.app.crypto.vault.OUTER_IMAGE_BYTES
+import com.zitrone.app.crypto.vault.PAYLOAD_AD
 import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
 import com.zitrone.app.crypto.vault.SLOT_AD
 import com.zitrone.app.crypto.vault.SLOT_COUNT
@@ -314,6 +315,25 @@ class AttemptUnlockOrAddTest {
         assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
     }
 
+    @Test
+    fun create_selfVerifiesThePayload_throwsAndPersistsNothing_onAMisSealingPayloadProvider() {
+        // G3: a miscomputing PAYLOAD aeadEncrypt producing a SELF-CONSISTENT but WRONG-content box (it
+        // decrypts fine, just not to genesisPayload) must be caught by the payload self-verify's
+        // CONSTANT-TIME CONTENT compare BEFORE anything is persisted — otherwise a full working session runs
+        // over a vault that is permanently unopenable after process death. A "decryption succeeded" check
+        // alone would NOT catch this.
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        val misSealing = MisSealingPayloadOps(realOps)
+        val s = store(dir, ops = misSealing)
+        s.open()
+        val before = bin(dir).readBytes()
+        assertThrows(IllegalStateException::class.java) {
+            s.attemptUnlockOrAdd("passB", genesis, create = true)
+        }
+        assertArrayEquals("a failed payload self-verify persists nothing", before, bin(dir).readBytes())
+    }
+
     // ─────────────────────────── durability ───────────────────────────
 
     @Test
@@ -332,12 +352,13 @@ class AttemptUnlockOrAddTest {
     // ─────────────────────────── crypto-budget PARITY (load-bearing) ───────────────────────────
 
     @Test
-    fun cryptoBudgetParity_5derivations_1payloadGcm_6wrappedGcm_acrossAllOutcomes() {
-        // Each outcome must issue IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal),
-        // exactly one 256 KiB payload GCM, and 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt +
-        // 1 candidate self-verify decrypt, B2). Only a SUCCESSFUL create additionally does one ~1 MiB
-        // outer GCM (the documented persist residual); the marker-present create FAILS CLOSED to the
-        // reject budget (no outer GCM), so it is indistinguishable from an ordinary wrong password.
+    fun cryptoBudgetParity_5argon2id_6wrappedGcm_acrossOutcomes_createAloneDoublesPayloadAndOuter() {
+        // Every outcome issues IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal) and
+        // 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt + 1 candidate self-verify decrypt, B2).
+        // Payload GCM is 1 on every outcome EXCEPT a SUCCESSFUL create, which does 2 (seal + a self-verify
+        // open, G3) and also the one ~1 MiB outer GCM — both create-only persist residuals. The
+        // marker-present create FAILS CLOSED to the exact reject budget (1 payload GCM, no outer), so it is
+        // indistinguishable from an ordinary wrong password.
         fun measure(outcome: String, prep: (File) -> Unit, call: (VaultImageStore) -> Unit) {
             val dir = tmp.newFolder()
             prep(dir)
@@ -348,9 +369,11 @@ class AttemptUnlockOrAddTest {
             counting.reset(); counter.calls = 0 // measure ONLY the attempt
             call(s)
             assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
-            assertEquals("$outcome: exactly one 256 KiB payload GCM", 1, counting.payloadOps)
             // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
             assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
+            // A successful create seals genesis AND self-verifies it (G3) = 2; every other outcome = 1.
+            val expectedPayload = if (outcome == "create") 2 else 1
+            assertEquals("$outcome: payload GCM (create seals+verifies=2, else 1)", expectedPayload, counting.payloadOps)
             val expectedOuter = if (outcome == "create") 1 else 0
             assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
         }
@@ -500,4 +523,23 @@ class AttemptUnlockOrAddTest {
             return out
         }
     }
+
+    /**
+     * Miscomputes ONLY the payload layer (`PAYLOAD_AD`): flips the first CONTENT byte of the plaintext (just
+     * past the 4-byte length prefix) before encrypting, so the box decrypts SUCCESSFULLY but to the wrong
+     * content. Every other AEAD op is the real byte path. Exercises the G3 payload self-verify's constant-
+     * time CONTENT compare — which a "decryption succeeded" check alone would not.
+     */
+    private class MisSealingPayloadOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
+        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
+        override fun randomBytes(length: Int) = inner.randomBytes(length)
+        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
+            inner.aeadDecrypt(key, box, associatedData)
+        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
+            if (!associatedData.contentEquals(PAYLOAD_AD)) return inner.aeadEncrypt(key, plaintext, associatedData)
+            val p = plaintext.copyOf() // don't mutate the caller's buffer
+            p[4] = (p[4].toInt() xor 0x01).toByte() // flip content[0] (index 4 = just past the length prefix)
+            return inner.aeadEncrypt(key, p, associatedData)
+        }
+    }
 }
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 36 +++++++++++---
 .../java/com/zitrone/app/AttemptUnlockOrAddTest.kt | 56 +++++++++++++++++++---
 2 files changed, 79 insertions(+), 13 deletions(-)
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index c9e171c..73e0d32 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -607,7 +607,10 @@ class VaultImageStore internal constructor(
      *   - ONE unconditional candidate-slot seal ([sealSlotSelfVerifying]) — 1 more Argon2id + TWO
      *     wrapped-key GCM (the seal encrypt + a same-master-key verify decrypt, 0 extra Argon2id); real
      *     vault-B material on create, pure timing filler otherwise. Total: 5 Argon2id + 6 wrapped-key GCM;
-     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
+     *   - EXACTLY ONE 256 KiB payload GCM on EVERY outcome (open on a match, seal on create/reject). A
+     *     SUCCESSFUL create additionally does a SECOND payload GCM (a self-verify open, see DELETE MARKERS /
+     *     the create branch) — a create-only residual, alongside the outer GCM + write, not a per-outcome
+     *     distinguisher (the marker-present create fails closed to the single-payload-GCM reject budget).
      *
      * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
      * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
@@ -618,10 +621,11 @@ class VaultImageStore internal constructor(
      * (see DELETE MARKERS below), in which case it FAILS CLOSED to [UnlockOrAdd.Rejected]. With [create]
      * false it returns [UnlockOrAdd.Rejected] having written nothing.
      *
-     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
-     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
-     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
-     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
+     * TIMING RESIDUAL (documented, accepted): only the SUCCESSFUL create path additionally PERSISTS (a
+     * second payload GCM = the self-verify open, the ~1 MiB outer GCM, an atomic write + dir-fsync that
+     * gate a durable [UnlockOrAdd.Created]). That work is post-outcome and dwarfed by the SLOT_COUNT+1
+     * Argon2id; it is the same class as the payload-open asymmetry and is NOT a per-attempt KDF-level
+     * distinguisher. A marker-present create fails closed to the exact single-payload-GCM reject budget.
      *
      * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
      * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
@@ -725,8 +729,28 @@ class VaultImageStore internal constructor(
                             wipe(throwaway)
                             UnlockOrAdd.Rejected
                         } else {
-                            // The 1×256 KiB payload GCM for the create branch.
+                            // Payload GCM #1 (the seal). A SUCCESSFUL create is the one outcome that persists,
+                            // so it is also the one that gets a second, create-only payload GCM below — inside
+                            // the already-accepted create-persist residual (alongside the outer GCM + write),
+                            // touching no other outcome, so cross-outcome parity + the 5-Argon2id invariant hold.
                             val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
+                            // B2 PAYLOAD HALF (G3): prove the sealed PAYLOAD actually opens to genesisPayload
+                            // with candKey BEFORE persisting. The wrapped-key self-verify (sealSlotSelfVerifying)
+                            // covers the key; this covers the payload. It must CONSTANT-TIME-COMPARE the recovered
+                            // plaintext to genesisPayload — not merely confirm decryption succeeded — so a
+                            // miscomputing AEAD that produced a self-consistent-but-WRONG-content box is caught.
+                            // The failure it closes is the worst shape for this feature: silent, surfacing only
+                            // after process death, leaving a full working session over a vault that is then
+                            // permanently unopenable. Throws before ANY write, exactly like B2's wrapped verify.
+                            val verifyPt = openPayload(candKey, sealedGenesis, ops)
+                                ?: throw IllegalStateException("sealed payload failed self-verify (did not open)")
+                            try {
+                                check(java.security.MessageDigest.isEqual(verifyPt, genesisPayload)) {
+                                    "sealed payload failed self-verify (recovered plaintext mismatch)"
+                                }
+                            } finally {
+                                wipe(verifyPt)
+                            }
                             val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
                             val newPayloads =
                                 decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
diff --git a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
index f71023f..bdba29d 100644
--- a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
+++ b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
@@ -15,6 +15,7 @@ import com.zitrone.app.crypto.vault.KeyDeriver
 import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
 import com.zitrone.app.crypto.vault.LibsodiumVaultOps
 import com.zitrone.app.crypto.vault.OUTER_IMAGE_BYTES
+import com.zitrone.app.crypto.vault.PAYLOAD_AD
 import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
 import com.zitrone.app.crypto.vault.SLOT_AD
 import com.zitrone.app.crypto.vault.SLOT_COUNT
@@ -314,6 +315,25 @@ class AttemptUnlockOrAddTest {
         assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
     }
 
+    @Test
+    fun create_selfVerifiesThePayload_throwsAndPersistsNothing_onAMisSealingPayloadProvider() {
+        // G3: a miscomputing PAYLOAD aeadEncrypt producing a SELF-CONSISTENT but WRONG-content box (it
+        // decrypts fine, just not to genesisPayload) must be caught by the payload self-verify's
+        // CONSTANT-TIME CONTENT compare BEFORE anything is persisted — otherwise a full working session runs
+        // over a vault that is permanently unopenable after process death. A "decryption succeeded" check
+        // alone would NOT catch this.
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        val misSealing = MisSealingPayloadOps(realOps)
+        val s = store(dir, ops = misSealing)
+        s.open()
+        val before = bin(dir).readBytes()
+        assertThrows(IllegalStateException::class.java) {
+            s.attemptUnlockOrAdd("passB", genesis, create = true)
+        }
+        assertArrayEquals("a failed payload self-verify persists nothing", before, bin(dir).readBytes())
+    }
+
     // ─────────────────────────── durability ───────────────────────────
 
     @Test
@@ -332,12 +352,13 @@ class AttemptUnlockOrAddTest {
     // ─────────────────────────── crypto-budget PARITY (load-bearing) ───────────────────────────
 
     @Test
-    fun cryptoBudgetParity_5derivations_1payloadGcm_6wrappedGcm_acrossAllOutcomes() {
-        // Each outcome must issue IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal),
-        // exactly one 256 KiB payload GCM, and 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt +
-        // 1 candidate self-verify decrypt, B2). Only a SUCCESSFUL create additionally does one ~1 MiB
-        // outer GCM (the documented persist residual); the marker-present create FAILS CLOSED to the
-        // reject budget (no outer GCM), so it is indistinguishable from an ordinary wrong password.
+    fun cryptoBudgetParity_5argon2id_6wrappedGcm_acrossOutcomes_createAloneDoublesPayloadAndOuter() {
+        // Every outcome issues IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal) and
+        // 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt + 1 candidate self-verify decrypt, B2).
+        // Payload GCM is 1 on every outcome EXCEPT a SUCCESSFUL create, which does 2 (seal + a self-verify
+        // open, G3) and also the one ~1 MiB outer GCM — both create-only persist residuals. The
+        // marker-present create FAILS CLOSED to the exact reject budget (1 payload GCM, no outer), so it is
+        // indistinguishable from an ordinary wrong password.
         fun measure(outcome: String, prep: (File) -> Unit, call: (VaultImageStore) -> Unit) {
             val dir = tmp.newFolder()
             prep(dir)
@@ -348,9 +369,11 @@ class AttemptUnlockOrAddTest {
             counting.reset(); counter.calls = 0 // measure ONLY the attempt
             call(s)
             assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
-            assertEquals("$outcome: exactly one 256 KiB payload GCM", 1, counting.payloadOps)
             // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
             assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
+            // A successful create seals genesis AND self-verifies it (G3) = 2; every other outcome = 1.
+            val expectedPayload = if (outcome == "create") 2 else 1
+            assertEquals("$outcome: payload GCM (create seals+verifies=2, else 1)", expectedPayload, counting.payloadOps)
             val expectedOuter = if (outcome == "create") 1 else 0
             assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
         }
@@ -500,4 +523,23 @@ class AttemptUnlockOrAddTest {
             return out
         }
     }
+
+    /**
+     * Miscomputes ONLY the payload layer (`PAYLOAD_AD`): flips the first CONTENT byte of the plaintext (just
+     * past the 4-byte length prefix) before encrypting, so the box decrypts SUCCESSFULLY but to the wrong
+     * content. Every other AEAD op is the real byte path. Exercises the G3 payload self-verify's constant-
+     * time CONTENT compare — which a "decryption succeeded" check alone would not.
+     */
+    private class MisSealingPayloadOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
+        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
+        override fun randomBytes(length: Int) = inner.randomBytes(length)
+        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
+            inner.aeadDecrypt(key, box, associatedData)
+        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
+            if (!associatedData.contentEquals(PAYLOAD_AD)) return inner.aeadEncrypt(key, plaintext, associatedData)
+            val p = plaintext.copyOf() // don't mutate the caller's buffer
+            p[4] = (p[4].toInt() xor 0x01).toByte() // flip content[0] (index 4 = just past the length prefix)
+            return inner.aeadEncrypt(key, p, associatedData)
+        }
+    }
 }

codex
The delta is confined to the stated store and test files. I’m now checking the full surrounding implementation—not just the patch—including ownership handoff, outer exception cleanup, payload framing/unpadding, persistence ordering, and every outcome’s source-derived operation count.
exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '560,790p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultPayload.kt | sed -n '1,240p'; nl -ba apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt | sed -n '280,440p'; nl -ba apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt | sed -n '440,565p'; rg -n \"attemptUnlockOrAdd|UnlockOrAdd|CorruptImage|NotDurable|canonical|dek\" apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt" in /root/zitrone
 succeeded in 0ms:
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
   607	     *   - ONE unconditional candidate-slot seal ([sealSlotSelfVerifying]) — 1 more Argon2id + TWO
   608	     *     wrapped-key GCM (the seal encrypt + a same-master-key verify decrypt, 0 extra Argon2id); real
   609	     *     vault-B material on create, pure timing filler otherwise. Total: 5 Argon2id + 6 wrapped-key GCM;
   610	     *   - EXACTLY ONE 256 KiB payload GCM on EVERY outcome (open on a match, seal on create/reject). A
   611	     *     SUCCESSFUL create additionally does a SECOND payload GCM (a self-verify open, see DELETE MARKERS /
   612	     *     the create branch) — a create-only residual, alongside the outer GCM + write, not a per-outcome
   613	     *     distinguisher (the marker-present create fails closed to the single-payload-GCM reject budget).
   614	     *
   615	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
   616	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
   617	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
   618	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
   619	     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
   620	     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created] — UNLESS a delete marker is present
   621	     * (see DELETE MARKERS below), in which case it FAILS CLOSED to [UnlockOrAdd.Rejected]. With [create]
   622	     * false it returns [UnlockOrAdd.Rejected] having written nothing.
   623	     *
   624	     * TIMING RESIDUAL (documented, accepted): only the SUCCESSFUL create path additionally PERSISTS (a
   625	     * second payload GCM = the self-verify open, the ~1 MiB outer GCM, an atomic write + dir-fsync that
   626	     * gate a durable [UnlockOrAdd.Created]). That work is post-outcome and dwarfed by the SLOT_COUNT+1
   627	     * Argon2id; it is the same class as the payload-open asymmetry and is NOT a per-attempt KDF-level
   628	     * distinguisher. A marker-present create fails closed to the exact single-payload-GCM reject budget.
   629	     *
   630	     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
   631	     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
   632	     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
   633	     * target, so duress protection survives even a full pool.
   634	     *
   635	     * DELETE MARKERS — FAIL-CLOSED, this method is a pure READER (never writes/clears a marker). Unlike
   636	     * [create], whose `require(!binFile.exists())` PROVES its markers orphaned, the add-path has a LIVE
   637	     * image and cannot tell a stale marker from a live one (intent = reconcile owed; confirmed = destroy
   638	     * owed). So if it cannot prove BOTH markers absent (`Files.notExists`), it does NOT create and does NOT
   639	     * touch any marker — it returns [UnlockOrAdd.Rejected] (NOT a throw — a throw would be an observable
   640	     * side channel while the device is mid-delete) after the SAME throwaway payload GCM every other outcome
   641	     * performs. A's delete-state machine is left untouched. The marker check is in the SAME [imageLock]
   642	     * critical section as the sweep and the write, and the marker writers take [imageLock] too, so no
   643	     * marker can appear between the check and the write (no TOCTOU). Disclosed in docs/SECURITY_MODEL.md.
   644	     *
   645	     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
   646	     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
   647	     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
   648	     *
   649	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
   650	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   651	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the create write is not confirmed
   652	     * durable; [IllegalStateException] if the candidate self-verify fails (a miscomputing AEAD provider).
   653	     */
   654	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   655	        imageLock.withLock {
   656	            val image = canonical ?: run { open(); canonical!! }
   657	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   658	            val decoded = decodeImage(image)
   659	
   660	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   661	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   662	
   663	            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
   664	            // the try below so a throw during its generation (native crypto failure, OOM,
   665	            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
   666	            // live matched vault key — neither is covered if candidate generation sits before the try.
   667	            var candKeyForCleanup: ByteArray? = null
   668	            try {
   669	                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
   670	                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
   671	                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
   672	                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
   673	                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
   674	                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
   675	                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
   676	                val candSlotIndex = randomVaultSlotIndex(ops)
   677	                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
   678	
   679	                return when {
   680	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   681	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   682	                        wipe(candKey)
   683	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   684	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   685	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   686	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   687	                            .getOrNull()?.let { wipe(it) }
   688	                        wipe(unlock.vaultKey)
   689	                        UnlockOrAdd.Burn
   690	                    }
   691	
   692	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   693	                    unlock != null -> {
   694	                        wipe(candKey)
   695	                        val pt = try {
   696	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   697	                        } catch (t: Throwable) {
   698	                            wipe(unlock.vaultKey)
   699	                            throw VaultImageException.CorruptImage()
   700	                        }
   701	                        if (pt == null) {
   702	                            wipe(unlock.vaultKey)
   703	                            throw VaultImageException.CorruptImage()
   704	                        }
   705	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   706	                    }
   707	
   708	                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
   709	                    create -> {
   710	                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
   711	                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
   712	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
   713	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
   714	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
   715	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
   716	                        // a throw is an observable side channel precisely when the device is mid-delete) after
   717	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
   718	                        // machine is left completely untouched. This marker check is in the SAME imageLock
   719	                        // critical section as the sweep and the write, and markDeleteIntent /
   720	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
   721	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
   722	                        val markersAbsent =
   723	                            Files.notExists(deleteIntentFile.toPath()) &&
   724	                                Files.notExists(serverDeletedFile.toPath())
   725	                        if (!markersAbsent) {
   726	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
   727	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
   728	                            wipe(candKey)
   729	                            wipe(throwaway)
   730	                            UnlockOrAdd.Rejected
   731	                        } else {
   732	                            // Payload GCM #1 (the seal). A SUCCESSFUL create is the one outcome that persists,
   733	                            // so it is also the one that gets a second, create-only payload GCM below — inside
   734	                            // the already-accepted create-persist residual (alongside the outer GCM + write),
   735	                            // touching no other outcome, so cross-outcome parity + the 5-Argon2id invariant hold.
   736	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   737	                            // B2 PAYLOAD HALF (G3): prove the sealed PAYLOAD actually opens to genesisPayload
   738	                            // with candKey BEFORE persisting. The wrapped-key self-verify (sealSlotSelfVerifying)
   739	                            // covers the key; this covers the payload. It must CONSTANT-TIME-COMPARE the recovered
   740	                            // plaintext to genesisPayload — not merely confirm decryption succeeded — so a
   741	                            // miscomputing AEAD that produced a self-consistent-but-WRONG-content box is caught.
   742	                            // The failure it closes is the worst shape for this feature: silent, surfacing only
   743	                            // after process death, leaving a full working session over a vault that is then
   744	                            // permanently unopenable. Throws before ANY write, exactly like B2's wrapped verify.
   745	                            val verifyPt = openPayload(candKey, sealedGenesis, ops)
   746	                                ?: throw IllegalStateException("sealed payload failed self-verify (did not open)")
   747	                            try {
   748	                                check(java.security.MessageDigest.isEqual(verifyPt, genesisPayload)) {
   749	                                    "sealed payload failed self-verify (recovered plaintext mismatch)"
   750	                                }
   751	                            } finally {
   752	                                wipe(verifyPt)
   753	                            }
   754	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   755	                            val newPayloads =
   756	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   757	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   758	                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   759	                            // unreachable by construction; the dek is already durable on disk from create().
   760	                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   761	                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   762	                            // rename landed, the result reporting the rename's durability.
   763	                            val sync = atomicWrite(binFile, outer)
   764	                            // Rename committed → advance canonical BEFORE the durability check so a later
   765	                            // splice/attempt never works from stale state even on the NotDurable throw.
   766	                            canonical = newInner
   767	                            if (sync != DirSyncResult.DURABLE) {
   768	                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   769	                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   770	                                // canonical, so a later single entry of its passphrase unlocks it via the
   771	                                // match path — or, if the rename did not survive a crash, it is simply absent
   772	                                // and re-creatable.
   773	                                wipe(candKey)
   774	                                throw VaultImageException.NotDurable()
   775	                            }
   776	                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   777	                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   778	                        }
   779	                    }
   780	
   781	                    // ── REJECT — no match, no create. Nothing written. ──
   782	                    else -> {
   783	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   784	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   785	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   786	                        wipe(candKey)
   787	                        wipe(throwaway)
   788	                        UnlockOrAdd.Rejected
   789	                    }
   790	                }
     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
     2	// Licensed under the GNU Affero General Public License v3.0 or later.
     3	// See the LICENSE file in the repository root for full license text.
     4	// SPDX-License-Identifier: AGPL-3.0-only
     5	
     6	package com.zitrone.app.crypto.vault
     7	
     8	/**
     9	 * The fixed-size payload layer — an exact Kotlin mirror of the payload codec in
    10	 * apps/web/src/lib/storage.ts. Every payload region, real or filler, is exactly
    11	 * SLOT_PAYLOAD_BYTES on disk. A real payload is pad-then-encrypted so its length
    12	 * prefix sits INSIDE the ciphertext: it is byte-for-byte indistinguishable from
    13	 * the uniformly random bytes that fill unused regions, and it never grows.
    14	 */
    15	
    16	/** Fixed size of every payload region, real or filler. Mirrors storage.ts. */
    17	const val SLOT_PAYLOAD_BYTES: Int = 256 * 1024
    18	
    19	/** Plaintext capacity of a payload region (AEAD adds a 12-byte nonce + 16-byte tag). */
    20	const val PAYLOAD_PLAINTEXT_BYTES: Int = SLOT_PAYLOAD_BYTES - NONCE_BYTES - AEAD_TAG_BYTES
    21	
    22	/** Big-endian length prefix width inside a padded payload. */
    23	private const val LEN_PREFIX_BYTES: Int = 4
    24	
    25	/**
    26	 * Associated data binding a payload to its purpose. Intentionally generic — it
    27	 * names nothing about slot position, vault count, or "decoy" status. Byte-for-byte
    28	 * equal to storage.ts PAYLOAD_AD = utf8("Zitrone-Vault-Payload-v1").
    29	 */
    30	val PAYLOAD_AD: ByteArray = "Zitrone-Vault-Payload-v1".toByteArray(Charsets.UTF_8)
    31	
    32	/**
    33	 * Seal content into a payload region: pad to full plaintext capacity, THEN
    34	 * encrypt. Output is ALWAYS exactly SLOT_PAYLOAD_BYTES. The order is
    35	 * load-bearing: padding after encryption would put a plaintext length prefix on
    36	 * disk, statistically distinguishing real payloads from random filler and
    37	 * leaking the vault count.
    38	 *
    39	 * THROWS if the content exceeds the region's plaintext capacity — it never grows
    40	 * the region, because a larger-than-fixed payload would leak that a real vault
    41	 * lives here (and how big it is).
    42	 */
    43	fun sealPayload(vaultKey: ByteArray, content: ByteArray, ops: VaultSodiumOps): ByteArray {
    44	    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
    45	    if (LEN_PREFIX_BYTES + content.size > PAYLOAD_PLAINTEXT_BYTES) {
    46	        throw IllegalArgumentException("content exceeds vault slot capacity")
    47	    }
    48	    val padded = padToCapacity(content, ops)
    49	    try {
    50	        val sealed = ops.aeadEncrypt(vaultKey, padded, PAYLOAD_AD)
    51	        check(sealed.size == SLOT_PAYLOAD_BYTES) { "sealed payload size mismatch" }
    52	        return sealed
    53	    } finally {
    54	        wipe(padded)
    55	    }
    56	}
    57	
    58	/**
    59	 * Open a payload region with an unlocked vault key. Returns the original content,
    60	 * or null on any AEAD failure (wrong key / tampering) or corrupt padding.
    61	 */
    62	fun openPayload(vaultKey: ByteArray, payload: ByteArray, ops: VaultSodiumOps): ByteArray? {
    63	    val padded = ops.aeadDecrypt(vaultKey, payload, PAYLOAD_AD) ?: return null
    64	    try {
    65	        return unpad(padded)
    66	    } catch (e: IllegalArgumentException) {
    67	        // A corrupt length prefix makes unpad throw — honor this function's
    68	        // "returns null on corrupt padding" contract rather than propagating,
    69	        // so unlockImage treats it as an unopenable payload, not a crash.
    70	        return null
    71	    } finally {
    72	        wipe(padded)
    73	    }
    74	}
    75	
    76	/** A filler payload region: CSPRNG bytes, indistinguishable from a sealed one. */
    77	fun randomPayload(ops: VaultSodiumOps): ByteArray = ops.randomBytes(SLOT_PAYLOAD_BYTES)
    78	
    79	// Exact-fit padding: len(4 BE) || content || random fill, always exactly
    80	// PAYLOAD_PLAINTEXT_BYTES. The fill sits INSIDE the AEAD plaintext; its only job
    81	// is to carry no recoverable structure. Same layout as storage.ts padToCapacity.
    82	private fun padToCapacity(content: ByteArray, ops: VaultSodiumOps): ByteArray {
    83	    val out = ByteArray(PAYLOAD_PLAINTEXT_BYTES)
    84	    out[0] = ((content.size ushr 24) and 0xff).toByte()
    85	    out[1] = ((content.size ushr 16) and 0xff).toByte()
    86	    out[2] = ((content.size ushr 8) and 0xff).toByte()
    87	    out[3] = (content.size and 0xff).toByte()
    88	    content.copyInto(out, LEN_PREFIX_BYTES)
    89	    val fillStart = LEN_PREFIX_BYTES + content.size
    90	    if (fillStart < out.size) ops.randomBytes(out.size - fillStart).copyInto(out, fillStart)
    91	    return out
    92	}
    93	
    94	// Recover the original content from a padded region. Mirrors padding.ts unpad.
    95	private fun unpad(padded: ByteArray): ByteArray {
    96	    require(padded.size >= LEN_PREFIX_BYTES) { "padded input too short" }
    97	    val len = ((padded[0].toInt() and 0xff) shl 24) or
    98	        ((padded[1].toInt() and 0xff) shl 16) or
    99	        ((padded[2].toInt() and 0xff) shl 8) or
   100	        (padded[3].toInt() and 0xff)
   101	    val length = len.toLong() and 0xffffffffL
   102	    require(length <= padded.size - LEN_PREFIX_BYTES) { "corrupt padding length" }
   103	    return padded.copyOfRange(LEN_PREFIX_BYTES, LEN_PREFIX_BYTES + length.toInt())
   104	}
   280	        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
   281	        assertTrue("intent marker is NOT cleared", File(dir, "vault.delete-intent").exists())
   282	        assertArrayEquals("nothing written on the fail-closed reject", before, bin(dir).readBytes())
   283	        // And passB did not create a vault: after retiring the marker, the pool is unchanged.
   284	        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
   285	    }
   286	
   287	    @Test
   288	    fun create_failsClosed_whenServerDeleteConfirmedPresent() {
   289	        // The confirmed marker is the sole authorization for boot-time auto-destroy; a create must never
   290	        // clear it (that would strand a server-deleted account's forensic image).
   291	        val dir = tmp.newFolder()
   292	        val s = store(dir)
   293	        s.create("passA", "A".toByteArray(Charsets.UTF_8))
   294	        s.markServerDeleteConfirmed()
   295	        val before = bin(dir).readBytes()
   296	        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
   297	        assertTrue("confirmed marker is NOT cleared", File(dir, "vault.delete-confirmed").exists())
   298	        assertArrayEquals(before, bin(dir).readBytes())
   299	    }
   300	
   301	    @Test
   302	    fun create_selfVerifiesTheSealedSlot_throwsAndPersistsNothing_onAMisSealingProvider() {
   303	        // B2: a miscomputing aeadEncrypt (size-correct, wrong-content wrapped key) must be caught by the
   304	        // candidate self-verify BEFORE anything is persisted — otherwise the new vault would be written
   305	        // durably yet be permanently unopenable after process death.
   306	        val dir = tmp.newFolder()
   307	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   308	        val misSealing = MisSealingWrappedKeyOps(realOps)
   309	        val s = store(dir, ops = misSealing)
   310	        s.open()
   311	        val before = bin(dir).readBytes()
   312	        assertThrows(IllegalStateException::class.java) {
   313	            s.attemptUnlockOrAdd("passB", genesis, create = true)
   314	        }
   315	        assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
   316	    }
   317	
   318	    @Test
   319	    fun create_selfVerifiesThePayload_throwsAndPersistsNothing_onAMisSealingPayloadProvider() {
   320	        // G3: a miscomputing PAYLOAD aeadEncrypt producing a SELF-CONSISTENT but WRONG-content box (it
   321	        // decrypts fine, just not to genesisPayload) must be caught by the payload self-verify's
   322	        // CONSTANT-TIME CONTENT compare BEFORE anything is persisted — otherwise a full working session runs
   323	        // over a vault that is permanently unopenable after process death. A "decryption succeeded" check
   324	        // alone would NOT catch this.
   325	        val dir = tmp.newFolder()
   326	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   327	        val misSealing = MisSealingPayloadOps(realOps)
   328	        val s = store(dir, ops = misSealing)
   329	        s.open()
   330	        val before = bin(dir).readBytes()
   331	        assertThrows(IllegalStateException::class.java) {
   332	            s.attemptUnlockOrAdd("passB", genesis, create = true)
   333	        }
   334	        assertArrayEquals("a failed payload self-verify persists nothing", before, bin(dir).readBytes())
   335	    }
   336	
   337	    // ─────────────────────────── durability ───────────────────────────
   338	
   339	    @Test
   340	    fun create_notDurable_throwsNotDurable_butCanonicalAdvanced() {
   341	        val dir = tmp.newFolder()
   342	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   343	        val s = store(dir, dirSync = { DirSyncResult.NOT_DURABLE })
   344	        s.open()
   345	        assertThrows(VaultImageException.NotDurable::class.java) {
   346	            s.attemptUnlockOrAdd("passB", genesis, create = true)
   347	        }
   348	        // canonical advanced (bytes are on disk): the new vault unlocks IN-MEMORY on the same store.
   349	        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
   350	    }
   351	
   352	    // ─────────────────────────── crypto-budget PARITY (load-bearing) ───────────────────────────
   353	
   354	    @Test
   355	    fun cryptoBudgetParity_5argon2id_6wrappedGcm_acrossOutcomes_createAloneDoublesPayloadAndOuter() {
   356	        // Every outcome issues IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal) and
   357	        // 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt + 1 candidate self-verify decrypt, B2).
   358	        // Payload GCM is 1 on every outcome EXCEPT a SUCCESSFUL create, which does 2 (seal + a self-verify
   359	        // open, G3) and also the one ~1 MiB outer GCM — both create-only persist residuals. The
   360	        // marker-present create FAILS CLOSED to the exact reject budget (1 payload GCM, no outer), so it is
   361	        // indistinguishable from an ordinary wrong password.
   362	        fun measure(outcome: String, prep: (File) -> Unit, call: (VaultImageStore) -> Unit) {
   363	            val dir = tmp.newFolder()
   364	            prep(dir)
   365	            val counting = CountingOps(realOps)
   366	            val counter = CountingDeriver(fast)
   367	            val s = VaultImageStore(dir, counting, cipher, counter.deriver)
   368	            s.open()
   369	            counting.reset(); counter.calls = 0 // measure ONLY the attempt
   370	            call(s)
   371	            assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
   372	            // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
   373	            assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
   374	            // A successful create seals genesis AND self-verifies it (G3) = 2; every other outcome = 1.
   375	            val expectedPayload = if (outcome == "create") 2 else 1
   376	            assertEquals("$outcome: payload GCM (create seals+verifies=2, else 1)", expectedPayload, counting.payloadOps)
   377	            val expectedOuter = if (outcome == "create") 1 else 0
   378	            assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
   379	        }
   380	        // Setup uses the real deriver-injected store; but prep must seal with the SAME `fast` deriver so
   381	        // matches work when the measured store re-derives. Build vaults with a helper store.
   382	        val vaultContent = "content".toByteArray(Charsets.UTF_8)
   383	        measure("unlock",
   384	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
   385	            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
   386	        measure("reject",
   387	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
   388	            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
   389	        measure("create",
   390	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
   391	            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
   392	        measure("burn",
   393	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() }; armBurnSlot(d, "burn-me") },
   394	            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
   395	        // B1 fail-closed: a create attempt while a delete marker is present must have the SAME budget as an
   396	        // ordinary reject (5 Argon2id + 1 payload GCM + 6 wrapped + NO outer GCM) — no timing side channel
   397	        // distinguishes "creation refused because a delete is pending" from a wrong password.
   398	        measure("marker-reject",
   399	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.markDeleteIntent(); it.close() } },
   400	            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
   401	    }
   402	
   403	    // ─────────────────────────── legacy (v2) image handling ───────────────────────────
   404	
   405	    @Test
   406	    fun v2Image_open_throwsLegacyImage_notCorruptImage() {
   407	        val dir = tmp.newFolder()
   408	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   409	        val inner = decodeOnDiskInner(dir)
   410	        inner[0] = LEGACY_IMAGE_VERSION.toByte() // downgrade the version byte to v2
   411	        rewriteInner(dir, inner)
   412	        assertThrows(VaultImageException.LegacyImage::class.java) { store(dir).open() }
   413	    }
   414	
   415	    @Test
   416	    fun isLegacyImage_trueForV2_falseForCurrent() {
   417	        val dir = tmp.newFolder()
   418	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   419	        assertFalse("current version is not legacy", store(dir).isLegacyImage())
   420	        val inner = decodeOnDiskInner(dir)
   421	        inner[0] = LEGACY_IMAGE_VERSION.toByte()
   422	        rewriteInner(dir, inner)
   423	        assertTrue("v2 is legacy", store(dir).isLegacyImage())
   424	    }
   425	
   426	    @Test
   427	    fun retireLegacyImage_deletesV2_butRefusesToTouchCurrent() {
   428	        // Refuses (and deletes nothing) on a CURRENT-version image.
   429	        val dir = tmp.newFolder()
   430	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   431	        assertThrows(IllegalStateException::class.java) { store(dir).retireLegacyImage() }
   432	        assertTrue("a current image survives a misrouted retire", bin(dir).exists() && dek(dir).exists())
   433	        // Retires a genuine v2 image.
   434	        val inner = decodeOnDiskInner(dir)
   435	        inner[0] = LEGACY_IMAGE_VERSION.toByte()
   436	        rewriteInner(dir, inner)
   437	        store(dir).retireLegacyImage()
   438	        assertFalse("v2 bin unlinked", bin(dir).exists())
   439	        assertFalse("v2 dek unlinked", dek(dir).exists())
   440	    }
   440	    }
   441	
   442	    // ─────────────────────────── test doubles ───────────────────────────
   443	
   444	    /** Fixed-key device cipher (host stand-in for the Keystore key). */
   445	    private class FakeDeviceKeyCipher2 : DeviceKeyCipher {
   446	        private val key = ByteArray(32) { (it * 7 + 1).toByte() }
   447	        private val g = LibsodiumVaultOps(SodiumJava())
   448	        override fun wrapDek(dek: ByteArray): ByteArray {
   449	            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
   450	            val c = Cipher.getInstance("AES/GCM/NoPadding")
   451	            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
   452	            val ct = c.doFinal(dek)
   453	            return nonce + ct
   454	        }
   455	        override fun unwrapDek(blob: ByteArray): ByteArray? = try {
   456	            val c = Cipher.getInstance("AES/GCM/NoPadding")
   457	            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
   458	            c.doFinal(blob, 12, blob.size - 12)
   459	        } catch (t: Throwable) { null }
   460	    }
   461	
   462	    /** Counts Argon2id (deriver) invocations. */
   463	    private class CountingDeriver(private val inner: KeyDeriver) {
   464	        var calls = 0
   465	        val deriver: KeyDeriver = { p, s -> calls++; inner(p, s) }
   466	    }
   467	
   468	    /** Classifies each AEAD op by size so the parity invariant is checkable. */
   469	    private class CountingOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
   470	        var wrappedOps = 0 // 60-byte wrapped-key seal/unwrap
   471	        var payloadOps = 0 // 256 KiB payload seal/open
   472	        var outerOps = 0   // ~1 MiB outer image seal/open
   473	        fun reset() { wrappedOps = 0; payloadOps = 0; outerOps = 0 }
   474	        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
   475	        override fun randomBytes(length: Int) = inner.randomBytes(length)
   476	        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
   477	            when (plaintext.size) {
   478	                VAULT_KEY_BYTES -> wrappedOps++          // sealSlot wraps a 32-byte vault key
   479	                PAYLOAD_PLAINTEXT_BYTES -> payloadOps++  // sealPayload pads to full plaintext capacity
   480	                IMAGE_BYTES -> outerOps++                // outer image encrypt
   481	            }
   482	            return inner.aeadEncrypt(key, plaintext, associatedData)
   483	        }
   484	        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray): ByteArray? {
   485	            when (box.size) {
   486	                WRAPPED_KEY_BYTES -> wrappedOps++        // tryPassphrase unwraps each 60-byte slot
   487	                SLOT_PAYLOAD_BYTES -> payloadOps++       // openPayload
   488	                OUTER_IMAGE_BYTES -> outerOps++          // outer image decrypt
   489	            }
   490	            return inner.aeadDecrypt(key, box, associatedData)
   491	        }
   492	    }
   493	
   494	    /**
   495	     * Forces every vault-pool placement to [targetPoolIndex] by intercepting the single 4-byte CSPRNG
   496	     * draw `randomIndex` uses (unique to index selection — salts/nonces/keys are 16/12/32 bytes). Returns
   497	     * bytes encoding (targetPoolIndex-1) so `randomVaultSlotIndex` = 1 + ((targetPoolIndex-1) % (N-1)).
   498	     */
   499	    private class ForceVaultIndexOps(private val inner: VaultSodiumOps, targetPoolIndex: Int) : VaultSodiumOps {
   500	        private val forced = byteArrayOf(0, 0, 0, (targetPoolIndex - 1).toByte())
   501	        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
   502	        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
   503	            inner.aeadEncrypt(key, plaintext, associatedData)
   504	        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
   505	            inner.aeadDecrypt(key, box, associatedData)
   506	        override fun randomBytes(length: Int) = if (length == 4) forced.copyOf() else inner.randomBytes(length)
   507	    }
   508	
   509	    /**
   510	     * Miscomputes ONLY the wrapped-key layer (`SLOT_AD`): returns a size-correct but bit-flipped wrapped
   511	     * blob so it no longer decrypts back to the vault key. Every other AEAD op (payload, outer image) is
   512	     * the real byte path, so the store opens/reads normally — the defect surfaces only at the candidate
   513	     * self-verify (B2).
   514	     */
   515	    private class MisSealingWrappedKeyOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
   516	        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
   517	        override fun randomBytes(length: Int) = inner.randomBytes(length)
   518	        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
   519	            inner.aeadDecrypt(key, box, associatedData)
   520	        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
   521	            val out = inner.aeadEncrypt(key, plaintext, associatedData)
   522	            if (associatedData.contentEquals(SLOT_AD)) out[out.size - 1] = (out[out.size - 1].toInt() xor 0x01).toByte()
   523	            return out
   524	        }
   525	    }
   526	
   527	    /**
   528	     * Miscomputes ONLY the payload layer (`PAYLOAD_AD`): flips the first CONTENT byte of the plaintext (just
   529	     * past the 4-byte length prefix) before encrypting, so the box decrypts SUCCESSFULLY but to the wrong
   530	     * content. Every other AEAD op is the real byte path. Exercises the G3 payload self-verify's constant-
   531	     * time CONTENT compare — which a "decryption succeeded" check alone would not.
   532	     */
   533	    private class MisSealingPayloadOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
   534	        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
   535	        override fun randomBytes(length: Int) = inner.randomBytes(length)
   536	        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
   537	            inner.aeadDecrypt(key, box, associatedData)
   538	        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
   539	            if (!associatedData.contentEquals(PAYLOAD_AD)) return inner.aeadEncrypt(key, plaintext, associatedData)
   540	            val p = plaintext.copyOf() // don't mutate the caller's buffer
   541	            p[4] = (p[4].toInt() xor 0x01).toByte() // flip content[0] (index 4 = just past the length prefix)
   542	            return inner.aeadEncrypt(key, p, associatedData)
   543	        }
   544	    }
   545	}
64:    class CorruptImage : VaultImageException("vault image is unreadable")
69:     * This is DISTINCT from [CorruptImage] on purpose and is SAFETY-CRITICAL: a v2 image
76:     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
90:     * The in-memory [VaultImageStore.canonical] has been ADVANCED to match disk (so no
94:     * [CorruptImage] — nothing is unreadable; only the rename's durability is unconfirmed.
96:    class NotDurable : VaultImageException("vault image write not confirmed durable")
142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
146:sealed interface UnlockOrAdd {
148:    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
155:    data object Burn : UnlockOrAdd
158:    data class Created(val open: VaultOpen) : UnlockOrAdd
161:    data object Rejected : UnlockOrAdd
166: * the on-disk canonical image and the envelope that protects it at rest; nothing
173: *  - `vault.dek` = the 32-byte DEK wrapped by the hardware device key = a constant
186: * hold independent [canonical] snapshots and silently revert each other's writes (the
236:    /** Serializes every read/write of the on-disk image and the in-memory canonical. */
245:    private var canonical: ByteArray? = null
249:    private var dek: ByteArray? = null
252:     * The canonical directory path this instance has registered in [OPEN_PATHS], or null
260:    private val dekFile: File get() = File(baseDir, DEK_FILE)
273:     * normal unlock → [CorruptImage] escalation, never to a retire). Does NOT alter store state.
279:     * Read `vault.bin` + `vault.dek`, unwrap the DEK, decrypt the outer layer, and
280:     * hold the validated inner image as [canonical]. A leftover `.tmp` from an
284:     * [VaultImageException.CorruptImage] when it is present but unreadable (outer
293:     * image reads as MissingImage, a gone DEK as CorruptImage.
296:     * store fully CLOSED: the DEK is wiped, the cached [canonical] is dropped, and the
301:     * [canonical] from disk.
312:                deleteLeftoverTmp(dekFile)
317:                if (!dekFile.exists()) throw VaultImageException.CorruptImage()
324:                // CorruptImage). A file that VANISHED between the existence check and the stat
328:                // expected constant is CorruptImage as before.
329:                val dekSize = try {
330:                    java.nio.file.Files.size(dekFile.toPath())
332:                    // A gone dek is always Corrupt (bin already passed its existence check).
333:                    throw VaultImageException.CorruptImage()
335:                if (dekSize != WRAPPED_KEY_BYTES.toLong()) throw VaultImageException.CorruptImage()
340:                    if (binFile.exists()) throw VaultImageException.CorruptImage()
343:                if (binSize != OUTER_IMAGE_BYTES.toLong()) throw VaultImageException.CorruptImage()
349:                // fault) — so recheck existence: a still-present dek/bin is Corrupt, a truly
350:                // gone bin is Missing (a gone dek is always Corrupt, bin already passed exists).
351:                val dekBlob = try {
352:                    dekFile.readBytes()
354:                    throw VaultImageException.CorruptImage()
359:                    if (binFile.exists()) throw VaultImageException.CorruptImage()
363:                val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: throw VaultImageException.CorruptImage()
369:                        ?: throw VaultImageException.CorruptImage()
370:                    if (inner.size != IMAGE_BYTES) throw VaultImageException.CorruptImage()
372:                    //  - current [IMAGE_VERSION] → fall through, install as canonical.
377:                    //  - any OTHER version → [CorruptImage] (unknown/tampered; escalate, never recreate).
382:                        throw VaultImageException.CorruptImage()
389:                // Success: install canonical + DEK, wiping any DEK we already held.
390:                dek?.let { wipe(it) }
391:                dek = unwrapped
392:                canonical = inner
396:                // re-open finds the disk Missing/Corrupt, retaining the stale canonical would
398:                // corruption / a rollback). So drop the DEK + canonical and release the
400:                dek?.let { wipe(it) }
401:                dek = null
402:                canonical = null
421:     * Only then does it write to disk under a DEK-FIRST DURABILITY BARRIER: it renames `vault.dek`
428:     * [VaultImageException.NotDurable]; there are NO rollback deletes.
432:     * recoverable, and NEVER a {bin-present, dek-absent} [VaultImageException.CorruptImage] brick:
433:     *  - no `vault.bin` (with or without a stray `vault.dek`) → [open] = [VaultImageException.MissingImage]
434:     *    → retry create(), which overwrites any stray dek.
437:     * The {bin-present, dek-absent} state is UNREACHABLE by construction: the DEK is durable before
444:     * before any in-memory state is installed, so a mid-write throw leaves canonical / dek exactly as
473:                    throw VaultImageException.NotDurable()
476:                // Ephemeral here: the persisted copy lives in `dek`. Wipe the local on EVERY
493:                    // write below throws — including the NotDurable rollback throw — wipe it so no
497:                        // DEK-FIRST DURABILITY BARRIER. Write vault.dek and CONFIRM its rename
500:                        // vault.dek absent} CorruptImage brick UNREACHABLE by construction: the DEK is
503:                        renameIntoPlace(dekFile, wrappedDek)
506:                            // vault.bin. At most a stray, possibly-not-durable vault.dek exists and NO
508:                            throw VaultImageException.NotDurable()
514:                            // → retry) or a COMPLETE, openable vault — never {bin, no-dek}. No rollback
516:                            throw VaultImageException.NotDurable()
523:                        // the in-memory canonical/dek to match the just-confirmed image.
524:                        dek?.let { wipe(it) }
525:                        dek = newDek.copyOf()
526:                        canonical = image
558:            val image = canonical ?: run { open(); canonical!! }
581:            val image = canonical ?: run { open(); canonical!! }
616:     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
617:     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
620:     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created] — UNLESS a delete marker is present
621:     * (see DELETE MARKERS below), in which case it FAILS CLOSED to [UnlockOrAdd.Rejected]. With [create]
622:     * false it returns [UnlockOrAdd.Rejected] having written nothing.
626:     * gate a durable [UnlockOrAdd.Created]). That work is post-outcome and dwarfed by the SLOT_COUNT+1
639:     * touch any marker — it returns [UnlockOrAdd.Rejected] (NOT a throw — a throw would be an observable
647:     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
650:     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
651:     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the create write is not confirmed
654:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
656:            val image = canonical ?: run { open(); canonical!! }
657:            val activeDek = dek ?: throw IllegalStateException("vault image not open")
689:                        UnlockOrAdd.Burn
699:                            throw VaultImageException.CorruptImage()
703:                            throw VaultImageException.CorruptImage()
705:                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
730:                            UnlockOrAdd.Rejected
758:                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
759:                            // unreachable by construction; the dek is already durable on disk from create().
761:                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
764:                            // Rename committed → advance canonical BEFORE the durability check so a later
765:                            // splice/attempt never works from stale state even on the NotDurable throw.
766:                            canonical = newInner
770:                                // canonical, so a later single entry of its passphrase unlocks it via the
774:                                throw VaultImageException.NotDurable()
777:                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
788:                        UnlockOrAdd.Rejected
804:     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
806:     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
812:     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
818:     *    directory channel — fails CLOSED here. [canonical] is ADVANCED to match disk (so a later splice
820:     *    [VaultImageException.NotDurable] is thrown so the caller does NOT ack. The session stays dirty and
827:            val current = canonical ?: throw IllegalStateException("vault image not open")
828:            val activeDek = dek ?: throw IllegalStateException("vault image not open")
831:            // is untouched, so nothing below can corrupt the live canonical.
834:            // atomicWrite throws ONLY pre-rename (nothing committed, canonical untouched); a
837:            // The rename committed → in-memory canonical now matches disk. Advance it BEFORE the
839:            canonical = spliced
843:                // fail CLOSED and throw — a flush-before-ack caller does NOT ack. canonical is
846:                throw VaultImageException.NotDurable()
852:     * Wipe the DEK and drop the canonical image. Store open/close is device-level
865:            dek?.let { wipe(it) }
866:            dek = null
867:            canonical = null
882:     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
898:            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
899:            dek?.let { wipe(it) }
900:            dek = null
901:            canonical = null
903:            dekFile.delete()
905:            deleteLeftoverTmp(dekFile)
908:            if (binFile.exists() || dekFile.exists() ||
909:                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
920:     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
927:        if (!binFile.exists() || !dekFile.exists()) return null
929:            val dekBlob = dekFile.readBytes()
930:            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
933:            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
949:     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
970:     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
1060:            dek?.let { wipe(it) }
1061:            dek = null
1062:            canonical = null
1074:            dekFile.delete()
1076:            deleteLeftoverTmp(dekFile)
1083:            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
1088:            if (binFile.exists() || dekFile.exists() ||
1089:                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
1095:            // restores. Without this fsync, a crash could resurrect vault.bin/vault.dek while the
1140:     * onConfirmedNotDurable) can vanish on a crash, dropping back to an intent-only reconcile that
1164:        val path = baseDir.canonicalFile.path
1267:        const val DEK_FILE = "vault.dek"
1284:         * Process-wide set of canonical baseDir paths with a live VaultImageStore, enforcing
1330:        // advances canonical, desyncing the in-memory canonical from disk (the exact hazard the
1342:        // advances canonical, desyncing the in-memory canonical from disk. Errors still propagate.

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '650,810p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultPayload.kt | sed -n '20,150p'; rg -n -C 5 \"crypto-budget|payload GCM|256 KiB|attemptUnlockOrAdd|G3\" /root/l00prite/pr1-attemptUnlockOrAdd-spec.md /root/l00prite/pr1-fix-review-codex.md /root/l00prite/pr1-fix-review-grok.md; git diff 296ebc6..8f4545d -- /root/l00prite/pr1-attemptUnlockOrAdd-spec.md; git ls-tree -r --name-only 8f4545d | rg 'attemptUnlockOrAdd-spec|SECURITY_MODEL'" in /root/zitrone
 succeeded in 0ms:
   650	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   651	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the create write is not confirmed
   652	     * durable; [IllegalStateException] if the candidate self-verify fails (a miscomputing AEAD provider).
   653	     */
   654	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   655	        imageLock.withLock {
   656	            val image = canonical ?: run { open(); canonical!! }
   657	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   658	            val decoded = decodeImage(image)
   659	
   660	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   661	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   662	
   663	            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
   664	            // the try below so a throw during its generation (native crypto failure, OOM,
   665	            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
   666	            // live matched vault key — neither is covered if candidate generation sits before the try.
   667	            var candKeyForCleanup: ByteArray? = null
   668	            try {
   669	                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
   670	                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
   671	                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
   672	                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
   673	                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
   674	                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
   675	                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
   676	                val candSlotIndex = randomVaultSlotIndex(ops)
   677	                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)
   678	
   679	                return when {
   680	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   681	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   682	                        wipe(candKey)
   683	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   684	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   685	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   686	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   687	                            .getOrNull()?.let { wipe(it) }
   688	                        wipe(unlock.vaultKey)
   689	                        UnlockOrAdd.Burn
   690	                    }
   691	
   692	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   693	                    unlock != null -> {
   694	                        wipe(candKey)
   695	                        val pt = try {
   696	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   697	                        } catch (t: Throwable) {
   698	                            wipe(unlock.vaultKey)
   699	                            throw VaultImageException.CorruptImage()
   700	                        }
   701	                        if (pt == null) {
   702	                            wipe(unlock.vaultKey)
   703	                            throw VaultImageException.CorruptImage()
   704	                        }
   705	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   706	                    }
   707	
   708	                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
   709	                    create -> {
   710	                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
   711	                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
   712	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
   713	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
   714	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
   715	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
   716	                        // a throw is an observable side channel precisely when the device is mid-delete) after
   717	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
   718	                        // machine is left completely untouched. This marker check is in the SAME imageLock
   719	                        // critical section as the sweep and the write, and markDeleteIntent /
   720	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
   721	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
   722	                        val markersAbsent =
   723	                            Files.notExists(deleteIntentFile.toPath()) &&
   724	                                Files.notExists(serverDeletedFile.toPath())
   725	                        if (!markersAbsent) {
   726	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
   727	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
   728	                            wipe(candKey)
   729	                            wipe(throwaway)
   730	                            UnlockOrAdd.Rejected
   731	                        } else {
   732	                            // Payload GCM #1 (the seal). A SUCCESSFUL create is the one outcome that persists,
   733	                            // so it is also the one that gets a second, create-only payload GCM below — inside
   734	                            // the already-accepted create-persist residual (alongside the outer GCM + write),
   735	                            // touching no other outcome, so cross-outcome parity + the 5-Argon2id invariant hold.
   736	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   737	                            // B2 PAYLOAD HALF (G3): prove the sealed PAYLOAD actually opens to genesisPayload
   738	                            // with candKey BEFORE persisting. The wrapped-key self-verify (sealSlotSelfVerifying)
   739	                            // covers the key; this covers the payload. It must CONSTANT-TIME-COMPARE the recovered
   740	                            // plaintext to genesisPayload — not merely confirm decryption succeeded — so a
   741	                            // miscomputing AEAD that produced a self-consistent-but-WRONG-content box is caught.
   742	                            // The failure it closes is the worst shape for this feature: silent, surfacing only
   743	                            // after process death, leaving a full working session over a vault that is then
   744	                            // permanently unopenable. Throws before ANY write, exactly like B2's wrapped verify.
   745	                            val verifyPt = openPayload(candKey, sealedGenesis, ops)
   746	                                ?: throw IllegalStateException("sealed payload failed self-verify (did not open)")
   747	                            try {
   748	                                check(java.security.MessageDigest.isEqual(verifyPt, genesisPayload)) {
   749	                                    "sealed payload failed self-verify (recovered plaintext mismatch)"
   750	                                }
   751	                            } finally {
   752	                                wipe(verifyPt)
   753	                            }
   754	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   755	                            val newPayloads =
   756	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   757	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   758	                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   759	                            // unreachable by construction; the dek is already durable on disk from create().
   760	                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   761	                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   762	                            // rename landed, the result reporting the rename's durability.
   763	                            val sync = atomicWrite(binFile, outer)
   764	                            // Rename committed → advance canonical BEFORE the durability check so a later
   765	                            // splice/attempt never works from stale state even on the NotDurable throw.
   766	                            canonical = newInner
   767	                            if (sync != DirSyncResult.DURABLE) {
   768	                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   769	                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   770	                                // canonical, so a later single entry of its passphrase unlocks it via the
   771	                                // match path — or, if the rename did not survive a crash, it is simply absent
   772	                                // and re-creatable.
   773	                                wipe(candKey)
   774	                                throw VaultImageException.NotDurable()
   775	                            }
   776	                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   777	                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   778	                        }
   779	                    }
   780	
   781	                    // ── REJECT — no match, no create. Nothing written. ──
   782	                    else -> {
   783	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   784	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   785	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   786	                        wipe(candKey)
   787	                        wipe(throwaway)
   788	                        UnlockOrAdd.Rejected
   789	                    }
   790	                }
   791	            } catch (t: Throwable) {
   792	                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
   793	                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
   794	                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
   795	                candKeyForCleanup?.let { wipe(it) }
   796	                unlock?.let { wipe(it.vaultKey) }
   797	                throw t
   798	            }
   799	        }
   800	    }
   801	
   802	    /**
   803	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   804	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   805	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   806	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   807	     *
   808	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   809	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   810	     * distinct because they leave DIFFERENT state:
    20	const val PAYLOAD_PLAINTEXT_BYTES: Int = SLOT_PAYLOAD_BYTES - NONCE_BYTES - AEAD_TAG_BYTES
    21	
    22	/** Big-endian length prefix width inside a padded payload. */
    23	private const val LEN_PREFIX_BYTES: Int = 4
    24	
    25	/**
    26	 * Associated data binding a payload to its purpose. Intentionally generic — it
    27	 * names nothing about slot position, vault count, or "decoy" status. Byte-for-byte
    28	 * equal to storage.ts PAYLOAD_AD = utf8("Zitrone-Vault-Payload-v1").
    29	 */
    30	val PAYLOAD_AD: ByteArray = "Zitrone-Vault-Payload-v1".toByteArray(Charsets.UTF_8)
    31	
    32	/**
    33	 * Seal content into a payload region: pad to full plaintext capacity, THEN
    34	 * encrypt. Output is ALWAYS exactly SLOT_PAYLOAD_BYTES. The order is
    35	 * load-bearing: padding after encryption would put a plaintext length prefix on
    36	 * disk, statistically distinguishing real payloads from random filler and
    37	 * leaking the vault count.
    38	 *
    39	 * THROWS if the content exceeds the region's plaintext capacity — it never grows
    40	 * the region, because a larger-than-fixed payload would leak that a real vault
    41	 * lives here (and how big it is).
    42	 */
    43	fun sealPayload(vaultKey: ByteArray, content: ByteArray, ops: VaultSodiumOps): ByteArray {
    44	    require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
    45	    if (LEN_PREFIX_BYTES + content.size > PAYLOAD_PLAINTEXT_BYTES) {
    46	        throw IllegalArgumentException("content exceeds vault slot capacity")
    47	    }
    48	    val padded = padToCapacity(content, ops)
    49	    try {
    50	        val sealed = ops.aeadEncrypt(vaultKey, padded, PAYLOAD_AD)
    51	        check(sealed.size == SLOT_PAYLOAD_BYTES) { "sealed payload size mismatch" }
    52	        return sealed
    53	    } finally {
    54	        wipe(padded)
    55	    }
    56	}
    57	
    58	/**
    59	 * Open a payload region with an unlocked vault key. Returns the original content,
    60	 * or null on any AEAD failure (wrong key / tampering) or corrupt padding.
    61	 */
    62	fun openPayload(vaultKey: ByteArray, payload: ByteArray, ops: VaultSodiumOps): ByteArray? {
    63	    val padded = ops.aeadDecrypt(vaultKey, payload, PAYLOAD_AD) ?: return null
    64	    try {
    65	        return unpad(padded)
    66	    } catch (e: IllegalArgumentException) {
    67	        // A corrupt length prefix makes unpad throw — honor this function's
    68	        // "returns null on corrupt padding" contract rather than propagating,
    69	        // so unlockImage treats it as an unopenable payload, not a crash.
    70	        return null
    71	    } finally {
    72	        wipe(padded)
    73	    }
    74	}
    75	
    76	/** A filler payload region: CSPRNG bytes, indistinguishable from a sealed one. */
    77	fun randomPayload(ops: VaultSodiumOps): ByteArray = ops.randomBytes(SLOT_PAYLOAD_BYTES)
    78	
    79	// Exact-fit padding: len(4 BE) || content || random fill, always exactly
    80	// PAYLOAD_PLAINTEXT_BYTES. The fill sits INSIDE the AEAD plaintext; its only job
    81	// is to carry no recoverable structure. Same layout as storage.ts padToCapacity.
    82	private fun padToCapacity(content: ByteArray, ops: VaultSodiumOps): ByteArray {
    83	    val out = ByteArray(PAYLOAD_PLAINTEXT_BYTES)
    84	    out[0] = ((content.size ushr 24) and 0xff).toByte()
    85	    out[1] = ((content.size ushr 16) and 0xff).toByte()
    86	    out[2] = ((content.size ushr 8) and 0xff).toByte()
    87	    out[3] = (content.size and 0xff).toByte()
    88	    content.copyInto(out, LEN_PREFIX_BYTES)
    89	    val fillStart = LEN_PREFIX_BYTES + content.size
    90	    if (fillStart < out.size) ops.randomBytes(out.size - fillStart).copyInto(out, fillStart)
    91	    return out
    92	}
    93	
    94	// Recover the original content from a padded region. Mirrors padding.ts unpad.
    95	private fun unpad(padded: ByteArray): ByteArray {
    96	    require(padded.size >= LEN_PREFIX_BYTES) { "padded input too short" }
    97	    val len = ((padded[0].toInt() and 0xff) shl 24) or
    98	        ((padded[1].toInt() and 0xff) shl 16) or
    99	        ((padded[2].toInt() and 0xff) shl 8) or
   100	        (padded[3].toInt() and 0xff)
   101	    val length = len.toLong() and 0xffffffffL
   102	    require(length <= padded.size - LEN_PREFIX_BYTES) { "corrupt padding length" }
   103	    return padded.copyOfRange(LEN_PREFIX_BYTES, LEN_PREFIX_BYTES + length.toInt())
   104	}
/root/l00prite/pr1-fix-review-grok.md-6-
/root/l00prite/pr1-fix-review-grok.md-7----
/root/l00prite/pr1-fix-review-grok.md-8-
/root/l00prite/pr1-fix-review-grok.md-9-## 1. B1 FAIL-CLOSED — CLEAN
/root/l00prite/pr1-fix-review-grok.md-10-
/root/l00prite/pr1-fix-review-grok.md:11:**Independent marker-touch enumeration inside `attemptUnlockOrAdd` (lines 642–768):**
/root/l00prite/pr1-fix-review-grok.md-12-
/root/l00prite/pr1-fix-review-grok.md-13-| Operation class | Hits in body |
/root/l00prite/pr1-fix-review-grok.md-14-|---|---|
/root/l00prite/pr1-fix-review-grok.md-15-| `clearBothMarkersDurably` / `clearDeleteIntent` / `writeDurableMarker` / `markDeleteIntent` / `markServerDeleteConfirmed` | **none** |
/root/l00prite/pr1-fix-review-grok.md-16-| `File.delete()` / `createNewFile()` on markers | **none** |
--
/root/l00prite/pr1-fix-review-grok.md-21-- Throwaway `sealPayload(candKey, ByteArray(0), ops)` runs on that path (715–717) — same shape as ordinary reject (753–756)
/root/l00prite/pr1-fix-review-grok.md-22-- No residual call chain reaches `clearBothMarkersDurably` / `clearDeleteIntent` from the add path
/root/l00prite/pr1-fix-review-grok.md-23-
/root/l00prite/pr1-fix-review-grok.md-24-**Writer set after fix (re-derived):**  
/root/l00prite/pr1-fix-review-grok.md-25-`markDeleteIntent`, `markServerDeleteConfirmed`, `destroy` (+ confirmed then clear both), `clearDeleteIntent`, `clearBothMarkersDurably` only via `create()` / `destroy()`, `create()` (under `!binFile.exists()`).  
/root/l00prite/pr1-fix-review-grok.md:26:**`attemptUnlockOrAdd` is not a writer.**
/root/l00prite/pr1-fix-review-grok.md-27-
/root/l00prite/pr1-fix-review-grok.md-28----
/root/l00prite/pr1-fix-review-grok.md-29-
/root/l00prite/pr1-fix-review-grok.md-30-## 2. TOCTOU — CLEAN (in-process)
/root/l00prite/pr1-fix-review-grok.md-31-
/root/l00prite/pr1-fix-review-grok.md:32:- Entire `attemptUnlockOrAdd` body is one `imageLock.withLock { ... }` (643–768)
/root/l00prite/pr1-fix-review-grok.md-33-- Marker `Files.notExists` ×2 (710–712) and create `atomicWrite` (731) share that section with **no** intermediate unlock
/root/l00prite/pr1-fix-review-grok.md-34-- `markDeleteIntent` / `markServerDeleteConfirmed` each take `imageLock` (962–967) → cannot interleave with the check→write window on another thread
/root/l00prite/pr1-fix-review-grok.md-35-- Nested `open()` (644) re-enters the same `ReentrantLock` (304); no alien callback / marker writer under the lock
/root/l00prite/pr1-fix-review-grok.md-36-- Fail-closed tristate: indeterminate stat → `Files.notExists == false` → treat as not-absent → `Rejected`
/root/l00prite/pr1-fix-review-grok.md-37-
--
/root/l00prite/pr1-fix-review-grok.md-47-- Equality: `MessageDigest.isEqual(recovered, vaultKey)` with both required/produced as `VAULT_KEY_BYTES` — platform constant-time compare over equal-length inputs
/root/l00prite/pr1-fix-review-grok.md-48-- Master key: derived once, held only in the `try` that covers encrypt + decrypt + compare; `finally { wipe(masterKey) }` on **every** exit including encrypt/decrypt/check throws
/root/l00prite/pr1-fix-review-grok.md-49-- `recovered` wiped in inner `finally` on every path after allocation
/root/l00prite/pr1-fix-review-grok.md-50-- Lifetime vs `sealSlot`: not stored longer; wall-clock is slightly longer (extra decrypt+compare) but not escaped — acceptable
/root/l00prite/pr1-fix-review-grok.md-51-
/root/l00prite/pr1-fix-review-grok.md:52:**Ordering vs persist in `attemptUnlockOrAdd`:**
/root/l00prite/pr1-fix-review-grok.md-53-- `sealSlotSelfVerifying` at 665 runs **before** `return when` and **unconditionally** before any create-branch write (721–731)
/root/l00prite/pr1-fix-review-grok.md-54-- Self-verify throw → outer catch (759–765) → no image mutation
/root/l00prite/pr1-fix-review-grok.md-55-
/root/l00prite/pr1-fix-review-grok.md-56-**Residual (Info, not a fail of the stated wrap-check):** self-verify covers the **wrapped-key** layer only. `sealPayload` / outer GCM are not round-tripped. `create()`’s `unlockImage` still checks more surface; this is the known KDF-budget tradeoff, not a regression of the wrap-only claim.
/root/l00prite/pr1-fix-review-grok.md-57-
--
/root/l00prite/pr1-fix-review-grok.md-63-
/root/l00prite/pr1-fix-review-grok.md-64-| Stage | Count | Where |
/root/l00prite/pr1-fix-review-grok.md-65-|---|---|---|
/root/l00prite/pr1-fix-review-grok.md-66-| Argon2id | **5** | `tryPassphrase` ×4 + candidate seal ×1 |
/root/l00prite/pr1-fix-review-grok.md-67-| Wrapped-key GCM | **6** | 4 sweep unwraps + 1 seal encrypt + 1 self-verify decrypt |
/root/l00prite/pr1-fix-review-grok.md:68:| 256 KiB payload GCM | **1** | open (unlock/burn) or seal (create success / marker-reject / ordinary reject) |
/root/l00prite/pr1-fix-review-grok.md-69-| Outer image GCM | **0 or 1** | **only** successful create path (markers absent + write) |
/root/l00prite/pr1-fix-review-grok.md-70-
/root/l00prite/pr1-fix-review-grok.md-71-**All four outcomes (source paths):**
/root/l00prite/pr1-fix-review-grok.md-72-- **Unlock** (681–693): 5 Argon2 + 6 wrap GCM + 1 payload open; no outer
/root/l00prite/pr1-fix-review-grok.md-73-- **Burn** (669–677): same heavy set + 1 payload open (discarded); no outer
--
/root/l00prite/pr1-fix-review-grok.md-100-`BiometricUnlockStore.load` rejects `slot !in VAULT_SLOT_RANGE` including slot 0 (45) → `isEnabled()` false, never reaches the require.  
/root/l00prite/pr1-fix-review-grok.md-101-Biometric enable uses `session.slotIndex` from a vault open; burn never becomes `Unlocked` / session.
/root/l00prite/pr1-fix-review-grok.md-102-
/root/l00prite/pr1-fix-review-grok.md-103-**(b) Legitimate A never on slot 0 (v3):**  
/root/l00prite/pr1-fix-review-grok.md-104-`createVaultSlots` places via `randomVaultSlotIndex` → `VAULT_SLOT_RANGE` only (`VaultSlots.kt` 141).  
/root/l00prite/pr1-fix-review-grok.md:105:`attemptUnlockOrAdd` create placement same (664).  
/root/l00prite/pr1-fix-review-grok.md-106-No legit A-bound wrap names slot 0 on a v3 image.
/root/l00prite/pr1-fix-review-grok.md-107-
/root/l00prite/pr1-fix-review-grok.md-108-**(c) 0.9.1 upgrade + biometric:**  
/root/l00prite/pr1-fix-review-grok.md-109-v2 may have A at slot 0; `open()` throws `LegacyImage` before slot interpretation.  
/root/l00prite/pr1-fix-review-grok.md-110-Re-onboard: `retireLegacyImage` then `create` (v3 pool placement).  
--
/root/l00prite/pr1-fix-review-grok.md-117-
/root/l00prite/pr1-fix-review-grok.md-118-## 7. GENERAL NEW DEFECTS
/root/l00prite/pr1-fix-review-grok.md-119-
/root/l00prite/pr1-fix-review-grok.md-120-### Finding G1 — **Low** — Security-critical KDoc still describes the **rejected** OQ3 behavior
/root/l00prite/pr1-fix-review-grok.md-121-
/root/l00prite/pr1-fix-review-grok.md:122:- **FILE + FUNCTION:** `VaultImageStore.kt` — `attemptUnlockOrAdd` KDoc, lines **607–609, 629–631, 639–640**
/root/l00prite/pr1-fix-review-grok.md-123-- **DEFECT MECHANISM:** Public contract text still claims (1) candidate seal is plain `sealSlot` with 1 wrapped GCM, (2) “create clears BOTH delete markers durably FIRST”, (3) `NotDurable` if “pre-create marker clear” fails. Implementation does the opposite on markers and uses `sealSlotSelfVerifying` (6 wrap GCMs).
/root/l00prite/pr1-fix-review-grok.md-124-- **FAILURE / ATTACK SCENARIO:** Future generator/reviewer trusts the KDoc over the body, re-introduces marker-clear-over-live-image (the defect this fix removed) or wrong parity accounting. Not a runtime vuln today; high recurrence risk given this surface’s history.
/root/l00prite/pr1-fix-review-grok.md-125-
/root/l00prite/pr1-fix-review-grok.md-126-### Finding G2 — **Info** — Parity test name/comment lag; marker-present budget untested
/root/l00prite/pr1-fix-review-grok.md-127-
/root/l00prite/pr1-fix-review-grok.md-128-- **FILE:** `AttemptUnlockOrAddTest.kt` — `cryptoBudgetParity_5derivations_1payloadGcm_5wrappedGcm_acrossAllFourOutcomes` (~335)
/root/l00prite/pr1-fix-review-grok.md-129-- **MECHANISM:** Asserts 6 wrap GCMs correctly, but method name still says `5wrappedGcm`; does not measure `create=true` + marker-present.
/root/l00prite/pr1-fix-review-grok.md-130-- **SCENARIO:** No wrong runtime outcome; documentation/test-hygiene only. Source re-derive covers marker-present.
/root/l00prite/pr1-fix-review-grok.md-131-
/root/l00prite/pr1-fix-review-grok.md:132:### Finding G3 — **Info** — B2 is wrap-only vs `create()` full re-open
/root/l00prite/pr1-fix-review-grok.md-133-
/root/l00prite/pr1-fix-review-grok.md-134-- **FILE + FUNCTION:** `VaultSlots.sealSlotSelfVerifying`; create branch `sealPayload` at 721
/root/l00prite/pr1-fix-review-grok.md-135-- **MECHANISM:** Mis-AEAD that corrupts only `PAYLOAD_AD` (or outer) still persists an unopenable vault after process death while in-memory `Created` used `genesisPayload.copyOf()`.
/root/l00prite/pr1-fix-review-grok.md-136-- **SCENARIO:** Broken/hostile `VaultSodiumOps` payload path → durable B that never unlocks post-restart. Same threat class B2 targeted for wraps; payload remains an accepted residual under the one-payload-GCM budget.
/root/l00prite/pr1-fix-review-grok.md-137-
--
/root/l00prite/pr1-fix-review-grok.md-160-| 2 TOCTOU check+write under one lock | **CLEAN** |
/root/l00prite/pr1-fix-review-grok.md-161-| 3 B2 constant-time self-verify + wipe + pre-persist | **CLEAN** (payload residual Info) |
/root/l00prite/pr1-fix-review-grok.md-162-| 4 Timing 5 / 1 / 6 all outcomes + positions | **CLEAN** |
/root/l00prite/pr1-fix-review-grok.md-163-| 5 F4 wipe / mirror | **CLEAN** |
/root/l00prite/pr1-fix-review-grok.md-164-| 6 F9 + biometric range | **CLEAN** |
/root/l00prite/pr1-fix-review-grok.md:165:| 7 General new defects | **G1 Low (stale KDoc)**; G2–G3 Info |
/root/l00prite/pr1-fix-review-grok.md-166-
/root/l00prite/pr1-fix-review-grok.md-167----
/root/l00prite/pr1-fix-review-grok.md-168-
/root/l00prite/pr1-fix-review-grok.md-169-## Overall verdict
/root/l00prite/pr1-fix-review-grok.md-170-
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:1:# PR-1 SPEC — `VaultImageStore.attemptUnlockOrAdd` (0.9.2-beta: second vault + Pucker Burn)
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-2-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-3-**Status:** APPROVED (user, 2026-07-24T11:52Z) — implementation authorized WITH the blocking §10.1
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-4-resolution (option (a), in-scope) and the two §9 review-scope amendments below.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-5-**Author:** claude, 2026-07-24 (REVISED — burn-aware; supersedes the earlier double-entry/25% spec).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-6-**Scope owner:** jackofall1232. **Decisions:** see vault-ledger top block (2026-07-24 REVISED).
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-29----
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-30-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-31-## 1. Slot model (NEW — burn changes placement, not the byte format)
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-32-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-33-`SLOT_COUNT = 4` (unchanged; raising to 8 rejected — see ledger). Byte format unchanged:
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:34:`version(1) ‖ 4×[salt(16)‖wrapped(60)] ‖ 4×payload(256 KiB)`. What changes is **placement semantics**:
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-35-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-36-```kotlin
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-37-const val BURN_SLOT_INDEX = 0                     // reserved for the Pucker Burn credential
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-38-val VAULT_SLOT_RANGE = 1 until SLOT_COUNT         // 1..3 — the vault pool
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:39:/** Blind vault placement — slot 0 is NEVER chosen. Used by create() AND attemptUnlockOrAdd. */
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-40-fun randomVaultSlotIndex(ops) = 1 + randomIndex(SLOT_COUNT - 1, ops)   // 1..3
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-41-```
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-42-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-43-- **Slot 0** is sealed byte-identically to any vault slot (same Argon2id, same structure, same timing).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-44-  Only its *contents* differ (a burn marker, not a VaultState). **Arm-state is stored nowhere** — "armed"
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-45-  simply means a passphrase can match slot 0, which is exactly what `tryPassphrase` already tests. An
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-46-  examiner cannot tell from structure/timing whether slot 0 is armed. Until burn is set up, slot 0 is
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-47-  random filler (indistinguishable), so it never matches.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-48-- **Slots 1–3** hold the vault pool: the everyday vault A (placed by `create()` at `randomVaultSlotIndex`)
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:49:  and any created vault B (placed by `attemptUnlockOrAdd` at `randomVaultSlotIndex`).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-50-- **Collision probability is ~1/3 (~33%)**, not 25% (OQ2 corrected): blind placement is over 3 slots.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-51-- **Full-pool overwrite:** if slots 1–3 all hold real vaults, any further creation overwrites one with
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-52-  certainty and no warning (ZK: the app cannot detect a full pool). Burn (slot 0) is never touched by
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-53-  vault creation, so duress protection survives even a full pool. Documented in SECURITY_MODEL (PR-3).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-54-
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-72-| `create()` (companion-changed) | bin+dek, fresh image, A placed **in 1–3** | writes new | clears BOTH first | leaves slot 0 as filler | placement changed |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-73-| `writeSealedPayload()` | ONE payload region (existing live slot, always 1–3) | reuse | none | never | no |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-74-| `markDeleteIntent` / `markServerDeleteConfirmed` | a marker | — | writes | — | no |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-75-| `destroy()` | confirmed marker, unlinks bin+dek, clears both | deletes | writes then clears | wipes slot 0 too (whole image) | no |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-76-| `clearDeleteIntent` / `clearBothMarkersDurably` | unlink marker(s) | — | clears | — | no |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:77:| **`attemptUnlockOrAdd()` — Created branch (NEW)** | **bin full re-encode: ONE new slot-table entry + ONE new payload at a 1–3 index; all else byte-identical** | **reuse (never touches dek)** | **clears BOTH first (durable), like `create()`** | **never writes slot 0** | **YES** |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:78:| `attemptUnlockOrAdd()` — Unlocked / **Burn** / Rejected | **nothing on disk** | — | none | reads slot 0 (sweep) but writes nothing | YES (no-op writers) |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-79-| **Pucker Burn SETUP (sibling PR)** | **slot 0's slot entry + payload (arms burn)** | reuse | none (TBD) | **writes slot 0** | future |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-80-| **Burn WIPE (sibling PR)** | whole-image destroy | deletes | TBD (open item 2) | — | future |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-81-| **`retireLegacyImage()` (NEW, §10.1(a))** | **unlinks bin+dek+tmps durably — ONLY after re-proving inner version == 2 under imageLock** | wipes RAM copy; deletes file | **none — format retirement is NOT an account delete; markers untouched** | n/a (whole image) | **YES** |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-82-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-83-### Reader change (NEW, §10.1(a)): `open()` version branch
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-131-}
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-132-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-133-/**
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-134- * Fused unlock / burn-detect / maybe-create. ALWAYS identical heavy crypto regardless of outcome:
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-135- * SLOT_COUNT (=4, incl. slot 0) Argon2id sweep + 1 unconditional candidate-seal Argon2id + exactly one
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:136: * 256 KiB payload GCM + one tiny wrapped-key GCM. A slot match (0..3) ALWAYS wins over [create]; a
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-137- * slot-0 match wins as Burn. CPU-heavy: caller MUST be off-main. Under imageLock; opens from disk if needed.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-138- *
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-139- * @param passphrase entered passphrase (never logged).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-140- * @param genesisPayload plaintext to seal into a NEW vault (VaultState.empty() encoded). Caller owns+wipes it.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-141- * @param create whether a no-match should CREATE a vault (router sets true only on the 3rd consecutive
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-142- *   identical non-matching, uninterrupted entry — PR-2). Ignored on ANY slot match (incl. slot 0).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-143- * @throws VaultImageException.MissingImage/CorruptImage from open() or an unreadable matched vault payload.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-144- * @throws VaultImageException.NotDurable create wrote but rename durability unconfirmed.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-145- * @throws VaultImageException.DestroyFailed the pre-create marker clear was not durable.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-146- */
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:147:fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-148-```
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-149-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-150----
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-151-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-152-## 4. Algorithm (exact crypto-op accounting)
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-234-**Per-call crypto budget by outcome:**
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-235-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-236-> **Correction (2026-07-24). Two updates since the original table.** (1) wrapped-key GCM is **6** per call,
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-237-> not 1 — 4 sweep unwrap attempts + 1 candidate `sealSlotSelfVerifying` encrypt + 1 self-verify decrypt
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-238-> (B2 fix; the original table counted only the candidate seal). (2) A **successful create does 2 payload
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:239:> GCM** — the genesis seal + a self-verify open (G3 fix, constant-time content compare) — a create-only
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-240-> residual alongside the outer GCM + write; every other outcome does 1. The parity test asserts exactly
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-241-> these counts; the marker-present create fails closed to the 1-payload reject budget.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-242-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:243:| Outcome | Argon2id | 256 KiB payload GCM | wrapped-key GCM (60 B) | ~1 MiB outer GCM + write |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-244-|---|---|---|---|---|
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-245-| Unlocked (slot 1–3) | 4 sweep + 1 candidate = **5** | 1 (openPayload) | 4 unwrap + 1 seal + 1 self-verify = **6** | none |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-246-| **Burn (slot 0)** | **5** | 1 (openPayload slot 0, discarded) | **6** | none |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-247-| Created (markers absent) | **5** | **2** (seal genesis + self-verify open) | **6** | **yes (persist)** |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-248-| Created→Rejected (marker present, fail-closed) | **5** | 1 (seal throwaway) | **6** | none |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-249-| Rejected (no match) | **5** | 1 (seal throwaway) | **6** | none |
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-250-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:251:**(a) 3-attempt parity — PASS.** Each ritual attempt issues the identical `attemptUnlockOrAdd` op as any
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:252:ordinary failed unlock: **5 Argon2id + 1×256 KiB GCM + 1 tiny GCM**, invariant across outcome and across
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-253-attempt position (1/2/3). The triple-entry counter + candidate compare live entirely in router RAM
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-254-(SHA-256 + constant-time `MessageDigest.isEqual`, ~µs, computed every attempt), never touching the KDF
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-255-budget; `create` only selects whether the post-outcome persist runs. So attempt 1 ≡ attempt 2 ≡ ordinary
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-256-wrong-password ≡ Rejected, byte-for-byte; attempt 3 does the same 5 Argon2id, then persists.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-257-
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-259-unchanged by burn (slot 0 was always 1 of 4). **Armed vs unarmed slot 0 is timing-identical**: either way
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-260-`tryPassphrase` runs one Argon2id over slot 0's salt and one GCM-unwrap of its 60-byte wrapped key; a
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-261-GCM auth success (armed + burn pass) vs failure (filler, or wrong pass) is the same constant-time-ish
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-262-verify, and there is no early exit — this is precisely the real-vs-filler slot indistinguishability the
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-263-whole scheme already rests on. **Burn-match and vault-match are pre-outcome timing-identical**: both do
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:264:5 Argon2id + one 256 KiB payload GCM; the wipe (burn) and the unlock (vault) are both *post-outcome*
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-265-observable events, exactly like unlock-vs-stay-locked today. No-match is likewise 5 Argon2id + one GCM.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-266-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-267-**Slot-0 exclusion from placement — no signature.** `candSlotIndex = 1 + randomIndex(3)` vs
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-268-`randomIndex(4)` both draw 4 CSPRNG bytes + one modulo; one extra add — sub-nanosecond, unmeasurable.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-269-**Write-IO reveals no slot:** Created re-encodes and writes the *entire* ~1 MiB image (all 4 slots + 4
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-328----
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-329-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-330-## 8. Tests required (host-JVM, injected counting deriver/ops)
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-331-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-332-1. **Crypto-budget parity (load-bearing):** counting deriver + ops → assert EXACTLY 5 deriver calls and
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:333:   EXACTLY one 256 KiB GCM + one 60 B GCM for EACH of {unlock(1–3), **burn(slot 0)**, create, reject}.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-334-2. **Burn detection:** an armed slot 0 + burn passphrase → `Burn`, nothing written, bin byte-identical.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-335-   Unarmed slot 0 (filler) + arbitrary passphrase never yields `Burn`.
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-336-3. **Placement excludes slot 0:** force `randomVaultSlotIndex` across its range → created index ∈ {1,2,3};
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-337-   a create never overwrites slot 0 (armed burn still matches after any number of creates).
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-338-4. **Functional:** vault match → `Unlocked`; create no-match → `Created`, fresh `open()`+`unlock(newpass)`
--
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-384-
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-385-1. **✅ RESOLVED (user, 2026-07-24): option (a), BLOCKING and IN-SCOPE for PR-1.** Bump `IMAGE_VERSION`
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-386-   2→3; a v2 image routes to fresh onboarding. `open()` gains a known-old-version branch (v2 →
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-387-   `LegacyImage`, distinct from `CorruptImage` which still escalates for unknown versions) — that
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-388-   read-path branch is part of PR-1's diff with its OWN test: a v2 image must route to onboarding, NOT
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md:389:   CorruptImage, NOT any slot-0 interpretation. attemptUnlockOrAdd's slot-0 semantics must not land
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-390-   before this — a v2 image with A at slot 0 would wipe on the user's own correct passphrase. Recorded:
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-391-   this ships despite 0.9.1 being fresh-install-only with no real users — "we happened to have no users"
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-392-   is not a safety property. Alternative (lazy in-place migration on first v2 unlock) considered +
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-393-   rejected: builds an explicitly-not-built migration path, adds a new writer + first-unlock write
/root/l00prite/pr1-attemptUnlockOrAdd-spec.md-394-   timing asymmetry, for test-device-only benefit. See §2's new writer (`retireLegacyImage`) + reader
--
/root/l00prite/pr1-fix-review-codex.md-15-## Product & threat model
/root/l00prite/pr1-fix-review-codex.md-16-Zitrone: a production Signal-Protocol end-to-end encrypted messenger shipping to the Play Store, with a plausible-deniability second vault and a "Pucker Burn" duress credential. Adversary has PHYSICAL DEVICE ACCESS and FORENSIC CAPABILITY; assume CRASH / PROCESS-DEATH at ANY instruction. This is a FIX ROUND: fixes are NOT lower-risk than original code — in this codebase, several prior rounds each introduced a defect only re-verification caught, one fix introduced a P1, and this PR's own first round was rejected. **Treat the delta as guilty until proven otherwise.**
/root/l00prite/pr1-fix-review-codex.md-17-
/root/l00prite/pr1-fix-review-codex.md-18-## What to review
/root/l00prite/pr1-fix-review-codex.md-19-The DELTA `321b358..9ab8cb0` on branch `feat/0.9.2-vault-slotb-pr1` in this repo (/root/zitrone). Start with `git show 9ab8cb0` and `git diff 321b358..9ab8cb0`. Verify against ACTUAL SOURCE — do NOT trust the implementation summary or the invariant table's conclusions.
/root/l00prite/pr1-fix-review-codex.md:20:- Primary source: apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt (`attemptUnlockOrAdd`, `unlockWithKey`, marker methods), VaultSlots.kt (`sealSlotSelfVerifying`, `sealSlot`, `randomVaultSlotIndex`), apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt, docs/SECURITY_MODEL.md, and the tests apps/android/app/src/test/java/com/zitrone/app/{AttemptUnlockOrAddTest,VaultImageStoreTest,BiometricUnlockStoreTest}.kt.
/root/l00prite/pr1-fix-review-codex.md-21-- Context only (do NOT trust): /root/l00prite/pr1-fix-marker-invariant-table.md, and the prior-round reports /root/l00prite/pr1-review-{codex,grok}.md.
/root/l00prite/pr1-fix-review-codex.md-22-
/root/l00prite/pr1-fix-review-codex.md-23-## Verify specifically (binding — do not abbreviate)
/root/l00prite/pr1-fix-review-codex.md-24-
/root/l00prite/pr1-fix-review-codex.md:25:1. B1 FAIL-CLOSED — Confirm `attemptUnlockOrAdd` is now genuinely a PURE READER of both delete markers: it writes/clears NEITHER `vault.delete-intent` nor `vault.delete-confirmed` on ANY path, including error/exception paths. INDEPENDENTLY re-derive that it is removed from the marker-writer set (enumerate every line that could touch a marker file), rather than accepting the invariant table. Confirm marker-present yields `Rejected` (NOT a throw), and that the throwaway 256 KiB payload GCM still runs on that path. Confirm no residual call to `clearBothMarkersDurably`/`clearDeleteIntent` remains reachable from the add path.
/root/l00prite/pr1-fix-review-codex.md-26-
/root/l00prite/pr1-fix-review-codex.md-27-2. TOCTOU — Confirm the marker-absent check (`Files.notExists` ×2) and the image write genuinely execute in ONE `imageLock` critical section with no release in between, and that `markDeleteIntent` / `markServerDeleteConfirmed` taking `imageLock` actually closes the interleave window. Hunt for any path where a marker could appear, or be observed stale, between the check and the write: lock reentrancy, a lock released mid-operation, a callback/alien code under the lock, or a reader deriving marker state outside the lock.
/root/l00prite/pr1-fix-review-codex.md-28-
/root/l00prite/pr1-fix-review-codex.md:29:3. B2 SELF-VERIFYING SEAL — In `sealSlotSelfVerifying`: confirm the equality check is genuinely constant-time (`MessageDigest.isEqual` over equal-length inputs); confirm the derived master key's lifetime is NOT widened beyond the verify (compare to `sealSlot`); confirm the `finally`-wipe of the master key (and the recovered key) fires on EVERY path including the throw paths; and confirm it throws BEFORE any persist can occur in `attemptUnlockOrAdd` (i.e. the candidate seal precedes the create branch's write, unconditionally).
/root/l00prite/pr1-fix-review-codex.md-30-
/root/l00prite/pr1-fix-review-codex.md:31:4. TIMING PARITY AT THE NEW COUNT — The wrapped-key GCM count moved 5→6 with the self-verify. Do NOT assume the prior parity verdict carries. Re-derive from source: exactly 5 Argon2id, exactly one 256 KiB payload GCM, and exactly 6 wrapped-key GCM (4 sweep unwrap + 1 candidate seal encrypt + 1 self-verify decrypt) across ALL FOUR outcomes (unlock / burn / create / reject) AND all three triple-entry attempt positions. Note any outcome-dependent divergence (e.g. the marker-present reject vs ordinary reject, or create's outer GCM/write residual).
/root/l00prite/pr1-fix-review-codex.md-32-
/root/l00prite/pr1-fix-review-codex.md-33-5. F4 WIPE DISCIPLINE — Confirm no throw path can strand `candKey` or a live `unlock.vaultKey` now that candidate generation moved inside the `try`. Check the cleanup-var mirror (`candKeyForCleanup`) for any path where the mirror and the real reference diverge (e.g. reassignment, a throw between allocation and mirror assignment, double-wipe correctness, or a successful-return path that wrongly wipes a handed-off key).
/root/l00prite/pr1-fix-review-codex.md-34-
/root/l00prite/pr1-fix-review-codex.md-35-6. F9 + THE UNREQUESTED BIOMETRIC CHANGE — Explicit scrutiny, not a nod. The fix guards `unlockWithKey` to `VAULT_SLOT_RANGE` (as requested) but ALSO tightened `BiometricUnlockStore`'s accepted range beyond the literal ask, so a tampered slot-0 wrap reads not-enabled rather than reaching a throw. OQ4 requires biometric stay slot-A-only. Verify: (a) does this preserve the A-only invariant in every case; (b) can it cause a LEGITIMATE A-bound biometric wrap to read as not-enabled under any condition (A is placed in slots 1..SLOT_COUNT-1 by `createVaultSlots` — confirm no legit A ever lands on slot 0); (c) does it alter behavior for any existing 0.9.1-era biometric state (a 0.9.1 install could have A at slot 0 and a biometric wrap naming slot 0 — trace what now happens on upgrade, in combination with the v2→LegacyImage retire path).
/root/l00prite/pr1-fix-review-codex.md-36-
--
/root/l00prite/pr1-fix-review-codex.md-62-    fix(vault): PR-1 review round — B1 fail-closed markers, B2 slot self-verify, F4 wipe, F9 slot-0 guard
/root/l00prite/pr1-fix-review-codex.md-63-    
/root/l00prite/pr1-fix-review-codex.md-64-    Addresses the two-blind-reviewer (Codex+Grok) findings on 321b358; both had
/root/l00prite/pr1-fix-review-codex.md-65-    rejected the marker-clear-over-a-live-image and the un-verified sealed slot.
/root/l00prite/pr1-fix-review-codex.md-66-    
/root/l00prite/pr1-fix-review-codex.md:67:    - B1 (Crit/High, both reviewers): attemptUnlockOrAdd no longer writes or clears
/root/l00prite/pr1-fix-review-codex.md-68-      ANY delete marker. If it cannot PROVE both markers absent (Files.notExists), the
/root/l00prite/pr1-fix-review-codex.md-69-      Created branch fails CLOSED — returns Rejected (not throw) after the same
/root/l00prite/pr1-fix-review-codex.md:70:      throwaway payload GCM every outcome does, leaving A's delete-state machine
/root/l00prite/pr1-fix-review-codex.md-71-      untouched. Reverses OQ3: create() may clear only because require(!binFile.exists())
/root/l00prite/pr1-fix-review-codex.md-72-      proves its markers orphaned; the add path has no such proof, so it never acts on
/root/l00prite/pr1-fix-review-codex.md-73-      a stale-vs-live distinction the code cannot make. This removes the add path from
/root/l00prite/pr1-fix-review-codex.md-74-      the delete-marker WRITER set entirely, so the rounds-13-16 state machine is
/root/l00prite/pr1-fix-review-codex.md-75-      preserved and no reader's assumption can be falsified. Check + write share one
/root/l00prite/pr1-fix-review-codex.md:76:      imageLock critical section (no TOCTOU); folds in F6 (no path skips the payload GCM).
/root/l00prite/pr1-fix-review-codex.md-77-      Disclosed in SECURITY_MODEL.md.
/root/l00prite/pr1-fix-review-codex.md-78-    - B2 (High/Med, both): new sealSlotSelfVerifying decrypt-and-constant-time-compares
/root/l00prite/pr1-fix-review-codex.md-79-      the wrapped key to the vault key under the SAME derived master key (0 extra
/root/l00prite/pr1-fix-review-codex.md-80-      Argon2id, +1 wrapped-key GCM; master key lifetime unchanged), throwing before a
/root/l00prite/pr1-fix-review-codex.md-81-      create can persist a size-correct-but-unopenable slot. Used unconditionally for
--
/root/l00prite/pr1-fix-review-codex.md-158-+                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
/root/l00prite/pr1-fix-review-codex.md-159-+                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
/root/l00prite/pr1-fix-review-codex.md-160-+                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
/root/l00prite/pr1-fix-review-codex.md-161-+                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
/root/l00prite/pr1-fix-review-codex.md-162-+                        // a throw is an observable side channel precisely when the device is mid-delete) after
/root/l00prite/pr1-fix-review-codex.md:163:+                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
/root/l00prite/pr1-fix-review-codex.md-164-+                        // machine is left completely untouched. This marker check is in the SAME imageLock
/root/l00prite/pr1-fix-review-codex.md-165-+                        // critical section as the sweep and the write, and markDeleteIntent /
/root/l00prite/pr1-fix-review-codex.md-166-+                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
/root/l00prite/pr1-fix-review-codex.md-167-+                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
/root/l00prite/pr1-fix-review-codex.md-168-+                        val markersAbsent =
/root/l00prite/pr1-fix-review-codex.md-169-                             Files.notExists(deleteIntentFile.toPath()) &&
/root/l00prite/pr1-fix-review-codex.md-170-                                 Files.notExists(serverDeletedFile.toPath())
/root/l00prite/pr1-fix-review-codex.md-171--                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
/root/l00prite/pr1-fix-review-codex.md-172--                            throw VaultImageException.NotDurable()
/root/l00prite/pr1-fix-review-codex.md-173--                        }
/root/l00prite/pr1-fix-review-codex.md:174:-                        // The 1×256 KiB payload GCM for this branch.
/root/l00prite/pr1-fix-review-codex.md-175--                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-176--                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-177--                        val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-178--                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-179--                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-191--                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
/root/l00prite/pr1-fix-review-codex.md-192--                            // canonical, so a later single entry of its passphrase unlocks it via the
/root/l00prite/pr1-fix-review-codex.md-193--                            // match path (no write needed) — or, if the rename did not survive a crash, it
/root/l00prite/pr1-fix-review-codex.md-194--                            // is simply absent and re-creatable.
/root/l00prite/pr1-fix-review-codex.md-195-+                        if (!markersAbsent) {
/root/l00prite/pr1-fix-review-codex.md:196:+                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
/root/l00prite/pr1-fix-review-codex.md-197-+                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-198-                             wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-199--                            throw VaultImageException.NotDurable()
/root/l00prite/pr1-fix-review-codex.md-200-+                            wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-201-+                            UnlockOrAdd.Rejected
/root/l00prite/pr1-fix-review-codex.md-202-+                        } else {
/root/l00prite/pr1-fix-review-codex.md:203:+                            // The 1×256 KiB payload GCM for the create branch.
/root/l00prite/pr1-fix-review-codex.md-204-+                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-205-+                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-206-+                            val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-207-+                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-208-+                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-261-+ * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
/root/l00prite/pr1-fix-review-codex.md-262-+ * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
/root/l00prite/pr1-fix-review-codex.md-263-+ * lifetime is identical to [sealSlot]'s.
/root/l00prite/pr1-fix-review-codex.md-264-+ *
/root/l00prite/pr1-fix-review-codex.md-265-+ * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
/root/l00prite/pr1-fix-review-codex.md:266:+ * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
/root/l00prite/pr1-fix-review-codex.md-267-+ * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
/root/l00prite/pr1-fix-review-codex.md-268-+ * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
/root/l00prite/pr1-fix-review-codex.md-269-+ * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
/root/l00prite/pr1-fix-review-codex.md-270-+ * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
/root/l00prite/pr1-fix-review-codex.md-271-+ * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
--
/root/l00prite/pr1-fix-review-codex.md-415-+                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
/root/l00prite/pr1-fix-review-codex.md-416-+                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
/root/l00prite/pr1-fix-review-codex.md-417-+                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
/root/l00prite/pr1-fix-review-codex.md-418-+                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
/root/l00prite/pr1-fix-review-codex.md-419-+                        // a throw is an observable side channel precisely when the device is mid-delete) after
/root/l00prite/pr1-fix-review-codex.md:420:+                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
/root/l00prite/pr1-fix-review-codex.md-421-+                        // machine is left completely untouched. This marker check is in the SAME imageLock
/root/l00prite/pr1-fix-review-codex.md-422-+                        // critical section as the sweep and the write, and markDeleteIntent /
/root/l00prite/pr1-fix-review-codex.md-423-+                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
/root/l00prite/pr1-fix-review-codex.md-424-+                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
/root/l00prite/pr1-fix-review-codex.md-425-+                        val markersAbsent =
/root/l00prite/pr1-fix-review-codex.md-426-                             Files.notExists(deleteIntentFile.toPath()) &&
/root/l00prite/pr1-fix-review-codex.md-427-                                 Files.notExists(serverDeletedFile.toPath())
/root/l00prite/pr1-fix-review-codex.md-428--                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
/root/l00prite/pr1-fix-review-codex.md-429--                            throw VaultImageException.NotDurable()
/root/l00prite/pr1-fix-review-codex.md-430--                        }
/root/l00prite/pr1-fix-review-codex.md:431:-                        // The 1×256 KiB payload GCM for this branch.
/root/l00prite/pr1-fix-review-codex.md-432--                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-433--                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-434--                        val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-435--                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-436--                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-448--                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
/root/l00prite/pr1-fix-review-codex.md-449--                            // canonical, so a later single entry of its passphrase unlocks it via the
/root/l00prite/pr1-fix-review-codex.md-450--                            // match path (no write needed) — or, if the rename did not survive a crash, it
/root/l00prite/pr1-fix-review-codex.md-451--                            // is simply absent and re-creatable.
/root/l00prite/pr1-fix-review-codex.md-452-+                        if (!markersAbsent) {
/root/l00prite/pr1-fix-review-codex.md:453:+                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
/root/l00prite/pr1-fix-review-codex.md-454-+                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-455-                             wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-456--                            throw VaultImageException.NotDurable()
/root/l00prite/pr1-fix-review-codex.md-457-+                            wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-458-+                            UnlockOrAdd.Rejected
/root/l00prite/pr1-fix-review-codex.md-459-+                        } else {
/root/l00prite/pr1-fix-review-codex.md:460:+                            // The 1×256 KiB payload GCM for the create branch.
/root/l00prite/pr1-fix-review-codex.md-461-+                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-462-+                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-463-+                            val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-464-+                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-465-+                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-518-+ * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
/root/l00prite/pr1-fix-review-codex.md-519-+ * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
/root/l00prite/pr1-fix-review-codex.md-520-+ * lifetime is identical to [sealSlot]'s.
/root/l00prite/pr1-fix-review-codex.md-521-+ *
/root/l00prite/pr1-fix-review-codex.md-522-+ * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
/root/l00prite/pr1-fix-review-codex.md:523:+ * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
/root/l00prite/pr1-fix-review-codex.md-524-+ * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
/root/l00prite/pr1-fix-review-codex.md-525-+ * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
/root/l00prite/pr1-fix-review-codex.md-526-+ * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
/root/l00prite/pr1-fix-review-codex.md-527-+ * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
/root/l00prite/pr1-fix-review-codex.md-528-+ * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
--
/root/l00prite/pr1-fix-review-codex.md-612-         val dir = tmp.newFolder()
/root/l00prite/pr1-fix-review-codex.md-613-         val s = store(dir)
/root/l00prite/pr1-fix-review-codex.md-614-         s.create("passA", "A".toByteArray(Charsets.UTF_8))
/root/l00prite/pr1-fix-review-codex.md-615-         s.markDeleteIntent()
/root/l00prite/pr1-fix-review-codex.md-616--        assertTrue(File(dir, "vault.delete-intent").exists())
/root/l00prite/pr1-fix-review-codex.md:617:-        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = true) is UnlockOrAdd.Created)
/root/l00prite/pr1-fix-review-codex.md-618--        assertFalse("create clears the stale intent marker", File(dir, "vault.delete-intent").exists())
/root/l00prite/pr1-fix-review-codex.md-619-+        val before = bin(dir).readBytes()
/root/l00prite/pr1-fix-review-codex.md:620:+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
/root/l00prite/pr1-fix-review-codex.md-621-+        assertTrue("intent marker is NOT cleared", File(dir, "vault.delete-intent").exists())
/root/l00prite/pr1-fix-review-codex.md-622-+        assertArrayEquals("nothing written on the fail-closed reject", before, bin(dir).readBytes())
/root/l00prite/pr1-fix-review-codex.md-623-+        // And passB did not create a vault: after retiring the marker, the pool is unchanged.
/root/l00prite/pr1-fix-review-codex.md:624:+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
/root/l00prite/pr1-fix-review-codex.md-625-+    }
/root/l00prite/pr1-fix-review-codex.md-626-+
/root/l00prite/pr1-fix-review-codex.md-627-+    @Test
/root/l00prite/pr1-fix-review-codex.md-628-+    fun create_failsClosed_whenServerDeleteConfirmedPresent() {
/root/l00prite/pr1-fix-review-codex.md-629-+        // The confirmed marker is the sole authorization for boot-time auto-destroy; a create must never
--
/root/l00prite/pr1-fix-review-codex.md-631-+        val dir = tmp.newFolder()
/root/l00prite/pr1-fix-review-codex.md-632-+        val s = store(dir)
/root/l00prite/pr1-fix-review-codex.md-633-+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
/root/l00prite/pr1-fix-review-codex.md-634-+        s.markServerDeleteConfirmed()
/root/l00prite/pr1-fix-review-codex.md-635-+        val before = bin(dir).readBytes()
/root/l00prite/pr1-fix-review-codex.md:636:+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
/root/l00prite/pr1-fix-review-codex.md-637-+        assertTrue("confirmed marker is NOT cleared", File(dir, "vault.delete-confirmed").exists())
/root/l00prite/pr1-fix-review-codex.md-638-+        assertArrayEquals(before, bin(dir).readBytes())
/root/l00prite/pr1-fix-review-codex.md-639-+    }
/root/l00prite/pr1-fix-review-codex.md-640-+
/root/l00prite/pr1-fix-review-codex.md-641-+    @Test
--
/root/l00prite/pr1-fix-review-codex.md-648-+        val misSealing = MisSealingWrappedKeyOps(realOps)
/root/l00prite/pr1-fix-review-codex.md-649-+        val s = store(dir, ops = misSealing)
/root/l00prite/pr1-fix-review-codex.md-650-+        s.open()
/root/l00prite/pr1-fix-review-codex.md-651-+        val before = bin(dir).readBytes()
/root/l00prite/pr1-fix-review-codex.md-652-+        assertThrows(IllegalStateException::class.java) {
/root/l00prite/pr1-fix-review-codex.md:653:+            s.attemptUnlockOrAdd("passB", genesis, create = true)
/root/l00prite/pr1-fix-review-codex.md-654-+        }
/root/l00prite/pr1-fix-review-codex.md-655-+        assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
/root/l00prite/pr1-fix-review-codex.md-656-     }
/root/l00prite/pr1-fix-review-codex.md-657- 
/root/l00prite/pr1-fix-review-codex.md-658-     // ─────────────────────────── durability ───────────────────────────
/root/l00prite/pr1-fix-review-codex.md-659-@@ -309,7 +347,8 @@ class AttemptUnlockOrAddTest {
/root/l00prite/pr1-fix-review-codex.md-660-             call(s)
/root/l00prite/pr1-fix-review-codex.md-661-             assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
/root/l00prite/pr1-fix-review-codex.md:662:             assertEquals("$outcome: exactly one 256 KiB payload GCM", 1, counting.payloadOps)
/root/l00prite/pr1-fix-review-codex.md-663--            assertEquals("$outcome: 5 wrapped-key GCM (4 unwrap + 1 seal)", 5, counting.wrappedOps)
/root/l00prite/pr1-fix-review-codex.md-664-+            // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
/root/l00prite/pr1-fix-review-codex.md-665-+            assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
/root/l00prite/pr1-fix-review-codex.md-666-             val expectedOuter = if (outcome == "create") 1 else 0
/root/l00prite/pr1-fix-review-codex.md-667-             assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
--
/root/l00prite/pr1-fix-review-codex.md-805- vaults are a **per-device** feature. Because each install is an independent
/root/l00prite/pr1-fix-review-codex.md-806-
/root/l00prite/pr1-fix-review-codex.md-807-codex
/root/l00prite/pr1-fix-review-codex.md-808-The delta’s core control flow is now visible. I’m tracing all marker filesystem references and biometric migration/call sites next; this is where a locally correct guard can still create an upgrade regression.
/root/l00prite/pr1-fix-review-codex.md-809-exec
/root/l00prite/pr1-fix-review-codex.md:810:/bin/bash -lc "rg -n \"deleteIntentFile|serverDeletedFile|vault\\.delete-(intent|confirmed)|clearBothMarkersDurably|clearDeleteIntent|markDeleteIntent|markServerDeleteConfirmed|attemptUnlockOrAdd|unlockWithKey|BiometricUnlockStore|createVaultSlots|LegacyImage|LEGACY_IMAGE_VERSION|VAULT_SLOT_RANGE|randomVaultSlotIndex\" apps/android/app/src/main/java apps/android/app/src/test/java docs/SECURITY_MODEL.md && nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '1,860p' && nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | sed -n '1,230p' && nl -ba apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt" in /root/zitrone
/root/l00prite/pr1-fix-review-codex.md-811- succeeded in 0ms:
/root/l00prite/pr1-fix-review-codex.md-812-apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:24:import com.zitrone.app.crypto.vault.VAULT_SLOT_RANGE
/root/l00prite/pr1-fix-review-codex.md-813-apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:185:    // ── 3. unlockWithKey (biometric / dual-wrap path) ────────────────────────────
/root/l00prite/pr1-fix-review-codex.md-814-apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:188:    fun unlockWithKey_rightKeyAndIndexOpens_wrongNull_inputUntouched_returnsCopy() {
/root/l00prite/pr1-fix-review-codex.md-815-apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:197:        val opened = store.unlockWithKey(keyInput, slotIndex)
--
/root/l00prite/pr1-fix-review-codex.md-844-apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:168:    fun `dual-wrap opens the slot via unlockWithKey, wrong key null, invalidated selects passphrase`() {
/root/l00prite/pr1-fix-review-codex.md-845-apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:183:        val reopened = store.unlockWithKey(recovered, open.slotIndex) ?: error("unlockWithKey failed")
/root/l00prite/pr1-fix-review-codex.md-846-apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:187:        assertNull(store.unlockWithKey(ByteArray(VAULT_KEY_BYTES) { 0x00 }, open.slotIndex))
/root/l00prite/pr1-fix-review-codex.md-847-apps/android/app/src/test/java/com/zitrone/app/VaultSlotALiveTest.kt:190:        // (it never reaches unlockWithKey). A passphrase unlock still opens the same slot.
/root/l00prite/pr1-fix-review-codex.md-848-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:15:import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
/root/l00prite/pr1-fix-review-codex.md:849:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:50: * PR-1 tests for [VaultImageStore.attemptUnlockOrAdd] (0.9.2 second vault + Pucker Burn) and the
/root/l00prite/pr1-fix-review-codex.md-850-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:51: * v2→[VaultImageException.LegacyImage] read-path branch + [VaultImageStore.retireLegacyImage].
/root/l00prite/pr1-fix-review-codex.md:851:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:100:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true) // create ignored — match wins
/root/l00prite/pr1-fix-review-codex.md:852:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:110:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
/root/l00prite/pr1-fix-review-codex.md:853:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:117:        assertTrue(fresh.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
/root/l00prite/pr1-fix-review-codex.md:854:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:126:        val r = s.attemptUnlockOrAdd("nope", genesis, create = false)
/root/l00prite/pr1-fix-review-codex.md:855:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:137:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true)
/root/l00prite/pr1-fix-review-codex.md:856:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:169:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = true))
/root/l00prite/pr1-fix-review-codex.md:857:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:179:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("random", genesis, create = false))
/root/l00prite/pr1-fix-review-codex.md:858:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:199:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = false))
/root/l00prite/pr1-fix-review-codex.md:859:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:218:            fresh.attemptUnlockOrAdd("passA", genesis, create = false)
/root/l00prite/pr1-fix-review-codex.md:860:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:233:            val r = s.attemptUnlockOrAdd("B$it", genesis, create = true) as UnlockOrAdd.Created
/root/l00prite/pr1-fix-review-codex.md:861:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:259:        assertTrue(s.attemptUnlockOrAdd("passA", genesis, create = false) is UnlockOrAdd.Unlocked)
/root/l00prite/pr1-fix-review-codex.md:862:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:260:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
/root/l00prite/pr1-fix-review-codex.md:863:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:263:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passA", genesis, create = false))
/root/l00prite/pr1-fix-review-codex.md:864:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:264:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
/root/l00prite/pr1-fix-review-codex.md-865-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:277:        s.markDeleteIntent()
/root/l00prite/pr1-fix-review-codex.md:866:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:279:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
/root/l00prite/pr1-fix-review-codex.md-867-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:280:        assertTrue("intent marker is NOT cleared", File(dir, "vault.delete-intent").exists())
/root/l00prite/pr1-fix-review-codex.md:868:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:283:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
/root/l00prite/pr1-fix-review-codex.md-869-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:293:        s.markServerDeleteConfirmed()
/root/l00prite/pr1-fix-review-codex.md:870:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:295:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
/root/l00prite/pr1-fix-review-codex.md-871-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:296:        assertTrue("confirmed marker is NOT cleared", File(dir, "vault.delete-confirmed").exists())
/root/l00prite/pr1-fix-review-codex.md:872:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:312:            s.attemptUnlockOrAdd("passB", genesis, create = true)
/root/l00prite/pr1-fix-review-codex.md:873:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:326:            s.attemptUnlockOrAdd("passB", genesis, create = true)
/root/l00prite/pr1-fix-review-codex.md:874:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:329:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
/root/l00prite/pr1-fix-review-codex.md:875:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:360:            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
/root/l00prite/pr1-fix-review-codex.md:876:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:363:            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
/root/l00prite/pr1-fix-review-codex.md:877:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:366:            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
/root/l00prite/pr1-fix-review-codex.md:878:apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:369:            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
/root/l00prite/pr1-fix-review-codex.md-879-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:375:    fun v2Image_open_throwsLegacyImage_notCorruptImage() {
/root/l00prite/pr1-fix-review-codex.md-880-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:379:        inner[0] = LEGACY_IMAGE_VERSION.toByte() // downgrade the version byte to v2
/root/l00prite/pr1-fix-review-codex.md-881-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:381:        assertThrows(VaultImageException.LegacyImage::class.java) { store(dir).open() }
/root/l00prite/pr1-fix-review-codex.md-882-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:385:    fun isLegacyImage_trueForV2_falseForCurrent() {
/root/l00prite/pr1-fix-review-codex.md-883-apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:388:        assertFalse("current version is not legacy", store(dir).isLegacyImage())
--
/root/l00prite/pr1-fix-review-codex.md-920-apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:1429:            // best-effort swallow). Until vault.delete-confirmed is durable, a crash would strand a
/root/l00prite/pr1-fix-review-codex.md-921-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:68:     * VALID but PRIOR format version ([LEGACY_IMAGE_VERSION] = v2, the 0.9.1 format).
/root/l00prite/pr1-fix-review-codex.md-922-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:75:     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
/root/l00prite/pr1-fix-review-codex.md-923-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:76:     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
/root/l00prite/pr1-fix-review-codex.md-924-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:81:    class LegacyImage : VaultImageException("vault image is a prior, retired format")
/root/l00prite/pr1-fix-review-codex.md:925:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
/root/l00prite/pr1-fix-review-codex.md-926-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:202: * the outer layer) and [unlockWithKey] does NO Argon2id at all (it opens one payload with
/root/l00prite/pr1-fix-review-codex.md-927-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:261:    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
/root/l00prite/pr1-fix-review-codex.md-928-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:262:    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)
/root/l00prite/pr1-fix-review-codex.md-929-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:268:     * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
/root/l00prite/pr1-fix-review-codex.md-930-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:270:     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
--
/root/l00prite/pr1-fix-review-codex.md-939-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:573:    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
/root/l00prite/pr1-fix-review-codex.md-940-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:578:            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
/root/l00prite/pr1-fix-review-codex.md-941-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:580:            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
/root/l00prite/pr1-fix-review-codex.md-942-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:615:     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
/root/l00prite/pr1-fix-review-codex.md-943-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:638:     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
/root/l00prite/pr1-fix-review-codex.md:944:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:642:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-945-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:664:                val candSlotIndex = randomVaultSlotIndex(ops)
/root/l00prite/pr1-fix-review-codex.md-946-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:707:                        // critical section as the sweep and the write, and markDeleteIntent /
/root/l00prite/pr1-fix-review-codex.md-947-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:708:                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
/root/l00prite/pr1-fix-review-codex.md-948-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:711:                            Files.notExists(deleteIntentFile.toPath()) &&
/root/l00prite/pr1-fix-review-codex.md-949-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:712:                                Files.notExists(serverDeletedFile.toPath())
--
/root/l00prite/pr1-fix-review-codex.md-981-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1241:        const val DELETE_INTENT_FILE = "vault.delete-intent"
/root/l00prite/pr1-fix-review-codex.md-982-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1246:         * [markServerDeleteConfirmed] / [serverDeleteConfirmed]. Existence is the only signal.
/root/l00prite/pr1-fix-review-codex.md-983-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1248:        const val SERVER_DELETED_FILE = "vault.delete-confirmed"
/root/l00prite/pr1-fix-review-codex.md-984-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:39:val VAULT_SLOT_RANGE: IntRange = (BURN_SLOT_INDEX + 1) until SLOT_COUNT
/root/l00prite/pr1-fix-review-codex.md-985-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:44: * ([createVaultSlots]) and blind second-vault creation
/root/l00prite/pr1-fix-review-codex.md:986:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:45: * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
/root/l00prite/pr1-fix-review-codex.md-987-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:49:fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
/root/l00prite/pr1-fix-review-codex.md-988-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:50:    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)
/root/l00prite/pr1-fix-review-codex.md:989:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:85: * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
/root/l00prite/pr1-fix-review-codex.md-990-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:126: * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
/root/l00prite/pr1-fix-review-codex.md-991-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:130:fun createVaultSlots(
/root/l00prite/pr1-fix-review-codex.md-992-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:141:        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
/root/l00prite/pr1-fix-review-codex.md-993-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:164: * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
/root/l00prite/pr1-fix-review-codex.md:994:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:165: * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
/root/l00prite/pr1-fix-review-codex.md-995-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:31: * distinct [VaultImageException.LegacyImage] (route to fresh onboarding + retire), NEVER
/root/l00prite/pr1-fix-review-codex.md-996-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:39:const val LEGACY_IMAGE_VERSION: Int = 2
/root/l00prite/pr1-fix-review-codex.md-997-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:113:    val created = createVaultSlots(passphrase, ops, deriver)
/root/l00prite/pr1-fix-review-codex.md-998-     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
/root/l00prite/pr1-fix-review-codex.md-999-     2	// Licensed under the GNU Affero General Public License v3.0 or later.
--
/root/l00prite/pr1-fix-review-codex.md-1134-   137	 * `internal` so the storage tests can inject a forced result to drive each branch.
/root/l00prite/pr1-fix-review-codex.md-1135-   138	 */
/root/l00prite/pr1-fix-review-codex.md-1136-   139	internal enum class DirSyncResult { DURABLE, NOT_DURABLE }
/root/l00prite/pr1-fix-review-codex.md-1137-   140	
/root/l00prite/pr1-fix-review-codex.md-1138-   141	/**
/root/l00prite/pr1-fix-review-codex.md:1139:   142	 * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
/root/l00prite/pr1-fix-review-codex.md-1140-   143	 * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
/root/l00prite/pr1-fix-review-codex.md-1141-   144	 * the CALLER learns only which of the four happened, never which slot or how many exist.
/root/l00prite/pr1-fix-review-codex.md-1142-   145	 */
/root/l00prite/pr1-fix-review-codex.md-1143-   146	sealed interface UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-1144-   147	    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
--
/root/l00prite/pr1-fix-review-codex.md-1601-   604	     * cases apart (the plausible-deniability + duress-credential timing contract):
/root/l00prite/pr1-fix-review-codex.md-1602-   605	     *
/root/l00prite/pr1-fix-review-codex.md-1603-   606	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
/root/l00prite/pr1-fix-review-codex.md-1604-   607	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
/root/l00prite/pr1-fix-review-codex.md-1605-   608	     *     (real vault-B material on create, pure timing filler otherwise);
/root/l00prite/pr1-fix-review-codex.md:1606:   609	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
/root/l00prite/pr1-fix-review-codex.md-1607-   610	     *
/root/l00prite/pr1-fix-review-codex.md-1608-   611	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
/root/l00prite/pr1-fix-review-codex.md-1609-   612	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
/root/l00prite/pr1-fix-review-codex.md-1610-   613	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
/root/l00prite/pr1-fix-review-codex.md-1611-   614	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
--
/root/l00prite/pr1-fix-review-codex.md-1634-   637	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
/root/l00prite/pr1-fix-review-codex.md-1635-   638	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
/root/l00prite/pr1-fix-review-codex.md-1636-   639	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
/root/l00prite/pr1-fix-review-codex.md-1637-   640	     * create write is not confirmed durable.
/root/l00prite/pr1-fix-review-codex.md-1638-   641	     */
/root/l00prite/pr1-fix-review-codex.md:1639:   642	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-1640-   643	        imageLock.withLock {
/root/l00prite/pr1-fix-review-codex.md-1641-   644	            val image = canonical ?: run { open(); canonical!! }
/root/l00prite/pr1-fix-review-codex.md-1642-   645	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
/root/l00prite/pr1-fix-review-codex.md-1643-   646	            val decoded = decodeImage(image)
/root/l00prite/pr1-fix-review-codex.md-1644-   647	
--
/root/l00prite/pr1-fix-review-codex.md-1697-   700	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
/root/l00prite/pr1-fix-review-codex.md-1698-   701	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
/root/l00prite/pr1-fix-review-codex.md-1699-   702	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
/root/l00prite/pr1-fix-review-codex.md-1700-   703	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
/root/l00prite/pr1-fix-review-codex.md-1701-   704	                        // a throw is an observable side channel precisely when the device is mid-delete) after
/root/l00prite/pr1-fix-review-codex.md:1702:   705	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
/root/l00prite/pr1-fix-review-codex.md-1703-   706	                        // machine is left completely untouched. This marker check is in the SAME imageLock
/root/l00prite/pr1-fix-review-codex.md-1704-   707	                        // critical section as the sweep and the write, and markDeleteIntent /
/root/l00prite/pr1-fix-review-codex.md-1705-   708	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
/root/l00prite/pr1-fix-review-codex.md-1706-   709	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
/root/l00prite/pr1-fix-review-codex.md-1707-   710	                        val markersAbsent =
/root/l00prite/pr1-fix-review-codex.md-1708-   711	                            Files.notExists(deleteIntentFile.toPath()) &&
/root/l00prite/pr1-fix-review-codex.md-1709-   712	                                Files.notExists(serverDeletedFile.toPath())
/root/l00prite/pr1-fix-review-codex.md-1710-   713	                        if (!markersAbsent) {
/root/l00prite/pr1-fix-review-codex.md:1711:   714	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
/root/l00prite/pr1-fix-review-codex.md-1712-   715	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-1713-   716	                            wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-1714-   717	                            wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-1715-   718	                            UnlockOrAdd.Rejected
/root/l00prite/pr1-fix-review-codex.md-1716-   719	                        } else {
/root/l00prite/pr1-fix-review-codex.md:1717:   720	                            // The 1×256 KiB payload GCM for the create branch.
/root/l00prite/pr1-fix-review-codex.md-1718-   721	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-1719-   722	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-1720-   723	                            val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-1721-   724	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-1722-   725	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-1743-   746	                        }
/root/l00prite/pr1-fix-review-codex.md-1744-   747	                    }
/root/l00prite/pr1-fix-review-codex.md-1745-   748	
/root/l00prite/pr1-fix-review-codex.md-1746-   749	                    // ── REJECT — no match, no create. Nothing written. ──
/root/l00prite/pr1-fix-review-codex.md-1747-   750	                    else -> {
/root/l00prite/pr1-fix-review-codex.md:1748:   751	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
/root/l00prite/pr1-fix-review-codex.md-1749-   752	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
/root/l00prite/pr1-fix-review-codex.md-1750-   753	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-1751-   754	                        wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-1752-   755	                        wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-1753-   756	                        UnlockOrAdd.Rejected
--
/root/l00prite/pr1-fix-review-codex.md-1897-    40	
/root/l00prite/pr1-fix-review-codex.md-1898-    41	/**
/root/l00prite/pr1-fix-review-codex.md-1899-    42	 * A uniformly-random index into the VAULT pool (never [BURN_SLOT_INDEX]). The SINGLE
/root/l00prite/pr1-fix-review-codex.md-1900-    43	 * source of truth for slot-0 reservation, used by BOTH the everyday-vault placement
/root/l00prite/pr1-fix-review-codex.md-1901-    44	 * ([createVaultSlots]) and blind second-vault creation
/root/l00prite/pr1-fix-review-codex.md:1902:    45	 * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
/root/l00prite/pr1-fix-review-codex.md-1903-    46	 * (plus one integer add), so it carries no timing/I-O signature distinct from ordinary
/root/l00prite/pr1-fix-review-codex.md-1904-    47	 * placement.
/root/l00prite/pr1-fix-review-codex.md-1905-    48	 */
/root/l00prite/pr1-fix-review-codex.md-1906-    49	fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
/root/l00prite/pr1-fix-review-codex.md-1907-    50	    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)
--
/root/l00prite/pr1-fix-review-codex.md-1937-    80	 * the SAME derived master key and constant-time-compares it to [vaultKey], then wipes the master key. This
/root/l00prite/pr1-fix-review-codex.md-1938-    81	 * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
/root/l00prite/pr1-fix-review-codex.md-1939-    82	 * lifetime is identical to [sealSlot]'s.
/root/l00prite/pr1-fix-review-codex.md-1940-    83	 *
/root/l00prite/pr1-fix-review-codex.md-1941-    84	 * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
/root/l00prite/pr1-fix-review-codex.md:1942:    85	 * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
/root/l00prite/pr1-fix-review-codex.md-1943-    86	 * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
/root/l00prite/pr1-fix-review-codex.md-1944-    87	 * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
/root/l00prite/pr1-fix-review-codex.md-1945-    88	 * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
/root/l00prite/pr1-fix-review-codex.md-1946-    89	 * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
/root/l00prite/pr1-fix-review-codex.md-1947-    90	 * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
--
/root/l00prite/pr1-fix-review-codex.md-2017-   160	 * known-occupied indices avoids clobbering a live vault.
/root/l00prite/pr1-fix-review-codex.md-2018-   161	 *
/root/l00prite/pr1-fix-review-codex.md-2019-   162	 * ⚠️ BURN-UNAWARE (0.9.2): this primitive picks freely over ALL slots incl.
/root/l00prite/pr1-fix-review-codex.md-2020-   163	 * [BURN_SLOT_INDEX], so it must NOT be wired into a creation path without excluding
/root/l00prite/pr1-fix-review-codex.md-2021-   164	 * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
/root/l00prite/pr1-fix-review-codex.md:2022:   165	 * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
/root/l00prite/pr1-fix-review-codex.md-2023-   166	 * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
/root/l00prite/pr1-fix-review-codex.md-2024-   167	 * as the web-mirrored primitive + tests only.
/root/l00prite/pr1-fix-review-codex.md-2025-   168	 */
/root/l00prite/pr1-fix-review-codex.md-2026-   169	fun addVaultSlot(
/root/l00prite/pr1-fix-review-codex.md-2027-   170	    slots: List<KeySlot>,
--
/root/l00prite/pr1-fix-review-codex.md-2165-    78	        const val KEY_BLOB = "biometric_vault_blob"
/root/l00prite/pr1-fix-review-codex.md-2166-    79	    }
/root/l00prite/pr1-fix-review-codex.md-2167-    80	}
/root/l00prite/pr1-fix-review-codex.md-2168-
/root/l00prite/pr1-fix-review-codex.md-2169-exec
/root/l00prite/pr1-fix-review-codex.md:2170:/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '430,780p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '840,1100p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt | sed -n '280,410p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt | sed -n '630,820p'; rg -n \"attemptUnlockOrAdd\\(\" apps/android/app/src/main/java -C 8" in /root/zitrone
/root/l00prite/pr1-fix-review-codex.md-2171- succeeded in 0ms:
/root/l00prite/pr1-fix-review-codex.md-2172-   430	     * CRASH-STATE GUARANTEE (the load-bearing invariant). Because the DEK is confirmed durable
/root/l00prite/pr1-fix-review-codex.md-2173-   431	     * BEFORE `vault.bin` is written, a crash at ANY point leaves one of only these states — all
/root/l00prite/pr1-fix-review-codex.md-2174-   432	     * recoverable, and NEVER a {bin-present, dek-absent} [VaultImageException.CorruptImage] brick:
/root/l00prite/pr1-fix-review-codex.md-2175-   433	     *  - no `vault.bin` (with or without a stray `vault.dek`) → [open] = [VaultImageException.MissingImage]
--
/root/l00prite/pr1-fix-review-codex.md-2346-   604	     * cases apart (the plausible-deniability + duress-credential timing contract):
/root/l00prite/pr1-fix-review-codex.md-2347-   605	     *
/root/l00prite/pr1-fix-review-codex.md-2348-   606	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
/root/l00prite/pr1-fix-review-codex.md-2349-   607	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
/root/l00prite/pr1-fix-review-codex.md-2350-   608	     *     (real vault-B material on create, pure timing filler otherwise);
/root/l00prite/pr1-fix-review-codex.md:2351:   609	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
/root/l00prite/pr1-fix-review-codex.md-2352-   610	     *
/root/l00prite/pr1-fix-review-codex.md-2353-   611	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
/root/l00prite/pr1-fix-review-codex.md-2354-   612	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
/root/l00prite/pr1-fix-review-codex.md-2355-   613	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
/root/l00prite/pr1-fix-review-codex.md-2356-   614	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
--
/root/l00prite/pr1-fix-review-codex.md-2379-   637	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
/root/l00prite/pr1-fix-review-codex.md-2380-   638	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
/root/l00prite/pr1-fix-review-codex.md-2381-   639	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
/root/l00prite/pr1-fix-review-codex.md-2382-   640	     * create write is not confirmed durable.
/root/l00prite/pr1-fix-review-codex.md-2383-   641	     */
/root/l00prite/pr1-fix-review-codex.md:2384:   642	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-2385-   643	        imageLock.withLock {
/root/l00prite/pr1-fix-review-codex.md-2386-   644	            val image = canonical ?: run { open(); canonical!! }
/root/l00prite/pr1-fix-review-codex.md-2387-   645	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
/root/l00prite/pr1-fix-review-codex.md-2388-   646	            val decoded = decodeImage(image)
/root/l00prite/pr1-fix-review-codex.md-2389-   647	
--
/root/l00prite/pr1-fix-review-codex.md-2442-   700	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
/root/l00prite/pr1-fix-review-codex.md-2443-   701	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
/root/l00prite/pr1-fix-review-codex.md-2444-   702	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
/root/l00prite/pr1-fix-review-codex.md-2445-   703	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
/root/l00prite/pr1-fix-review-codex.md-2446-   704	                        // a throw is an observable side channel precisely when the device is mid-delete) after
/root/l00prite/pr1-fix-review-codex.md:2447:   705	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
/root/l00prite/pr1-fix-review-codex.md-2448-   706	                        // machine is left completely untouched. This marker check is in the SAME imageLock
/root/l00prite/pr1-fix-review-codex.md-2449-   707	                        // critical section as the sweep and the write, and markDeleteIntent /
/root/l00prite/pr1-fix-review-codex.md-2450-   708	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
/root/l00prite/pr1-fix-review-codex.md-2451-   709	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
/root/l00prite/pr1-fix-review-codex.md-2452-   710	                        val markersAbsent =
/root/l00prite/pr1-fix-review-codex.md-2453-   711	                            Files.notExists(deleteIntentFile.toPath()) &&
/root/l00prite/pr1-fix-review-codex.md-2454-   712	                                Files.notExists(serverDeletedFile.toPath())
/root/l00prite/pr1-fix-review-codex.md-2455-   713	                        if (!markersAbsent) {
/root/l00prite/pr1-fix-review-codex.md:2456:   714	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
/root/l00prite/pr1-fix-review-codex.md-2457-   715	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-2458-   716	                            wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-2459-   717	                            wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-2460-   718	                            UnlockOrAdd.Rejected
/root/l00prite/pr1-fix-review-codex.md-2461-   719	                        } else {
/root/l00prite/pr1-fix-review-codex.md:2462:   720	                            // The 1×256 KiB payload GCM for the create branch.
/root/l00prite/pr1-fix-review-codex.md-2463-   721	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-2464-   722	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-2465-   723	                            val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-2466-   724	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-2467-   725	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-2488-   746	                        }
/root/l00prite/pr1-fix-review-codex.md-2489-   747	                    }
/root/l00prite/pr1-fix-review-codex.md-2490-   748	
/root/l00prite/pr1-fix-review-codex.md-2491-   749	                    // ── REJECT — no match, no create. Nothing written. ──
/root/l00prite/pr1-fix-review-codex.md-2492-   750	                    else -> {
/root/l00prite/pr1-fix-review-codex.md:2493:   751	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
/root/l00prite/pr1-fix-review-codex.md-2494-   752	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
/root/l00prite/pr1-fix-review-codex.md-2495-   753	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-2496-   754	                        wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-2497-   755	                        wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-2498-   756	                        UnlockOrAdd.Rejected
--
/root/l00prite/pr1-fix-review-codex.md-3109-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-637-     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
/root/l00prite/pr1-fix-review-codex.md-3110-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-638-     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
/root/l00prite/pr1-fix-review-codex.md-3111-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-639-     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
/root/l00prite/pr1-fix-review-codex.md-3112-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-640-     * create write is not confirmed durable.
/root/l00prite/pr1-fix-review-codex.md-3113-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-641-     */
/root/l00prite/pr1-fix-review-codex.md:3114:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:642:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-3115-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-643-        imageLock.withLock {
/root/l00prite/pr1-fix-review-codex.md-3116-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-644-            val image = canonical ?: run { open(); canonical!! }
/root/l00prite/pr1-fix-review-codex.md-3117-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-645-            val activeDek = dek ?: throw IllegalStateException("vault image not open")
/root/l00prite/pr1-fix-review-codex.md-3118-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-646-            val decoded = decodeImage(image)
/root/l00prite/pr1-fix-review-codex.md-3119-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-647-
--
/root/l00prite/pr1-fix-review-codex.md-3122-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-650-
/root/l00prite/pr1-fix-review-codex.md-3123-
/root/l00prite/pr1-fix-review-codex.md-3124-exec
/root/l00prite/pr1-fix-review-codex.md-3125-/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '642,780p'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '900,1060p'; rg -n \"fun (markDeleteIntent|markServerDeleteConfirmed|clearDeleteIntent|clearBothMarkersDurably|destroy)|DELETE_INTENT_FILE|SERVER_DELETED_FILE\" apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt" in /root/zitrone
/root/l00prite/pr1-fix-review-codex.md-3126- succeeded in 0ms:
/root/l00prite/pr1-fix-review-codex.md:3127:   642	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-3128-   643	        imageLock.withLock {
/root/l00prite/pr1-fix-review-codex.md-3129-   644	            val image = canonical ?: run { open(); canonical!! }
/root/l00prite/pr1-fix-review-codex.md-3130-   645	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
/root/l00prite/pr1-fix-review-codex.md-3131-   646	            val decoded = decodeImage(image)
/root/l00prite/pr1-fix-review-codex.md-3132-   647	
--
/root/l00prite/pr1-fix-review-codex.md-3185-   700	                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
/root/l00prite/pr1-fix-review-codex.md-3186-   701	                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
/root/l00prite/pr1-fix-review-codex.md-3187-   702	                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
/root/l00prite/pr1-fix-review-codex.md-3188-   703	                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
/root/l00prite/pr1-fix-review-codex.md-3189-   704	                        // a throw is an observable side channel precisely when the device is mid-delete) after
/root/l00prite/pr1-fix-review-codex.md:3190:   705	                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
/root/l00prite/pr1-fix-review-codex.md-3191-   706	                        // machine is left completely untouched. This marker check is in the SAME imageLock
/root/l00prite/pr1-fix-review-codex.md-3192-   707	                        // critical section as the sweep and the write, and markDeleteIntent /
/root/l00prite/pr1-fix-review-codex.md-3193-   708	                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
/root/l00prite/pr1-fix-review-codex.md-3194-   709	                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
/root/l00prite/pr1-fix-review-codex.md-3195-   710	                        val markersAbsent =
/root/l00prite/pr1-fix-review-codex.md-3196-   711	                            Files.notExists(deleteIntentFile.toPath()) &&
/root/l00prite/pr1-fix-review-codex.md-3197-   712	                                Files.notExists(serverDeletedFile.toPath())
/root/l00prite/pr1-fix-review-codex.md-3198-   713	                        if (!markersAbsent) {
/root/l00prite/pr1-fix-review-codex.md:3199:   714	                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
/root/l00prite/pr1-fix-review-codex.md-3200-   715	                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-3201-   716	                            wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-3202-   717	                            wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-3203-   718	                            UnlockOrAdd.Rejected
/root/l00prite/pr1-fix-review-codex.md-3204-   719	                        } else {
/root/l00prite/pr1-fix-review-codex.md:3205:   720	                            // The 1×256 KiB payload GCM for the create branch.
/root/l00prite/pr1-fix-review-codex.md-3206-   721	                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
/root/l00prite/pr1-fix-review-codex.md-3207-   722	                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
/root/l00prite/pr1-fix-review-codex.md-3208-   723	                            val newPayloads =
/root/l00prite/pr1-fix-review-codex.md-3209-   724	                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
/root/l00prite/pr1-fix-review-codex.md-3210-   725	                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
--
/root/l00prite/pr1-fix-review-codex.md-3231-   746	                        }
/root/l00prite/pr1-fix-review-codex.md-3232-   747	                    }
/root/l00prite/pr1-fix-review-codex.md-3233-   748	
/root/l00prite/pr1-fix-review-codex.md-3234-   749	                    // ── REJECT — no match, no create. Nothing written. ──
/root/l00prite/pr1-fix-review-codex.md-3235-   750	                    else -> {
/root/l00prite/pr1-fix-review-codex.md:3236:   751	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
/root/l00prite/pr1-fix-review-codex.md-3237-   752	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
/root/l00prite/pr1-fix-review-codex.md-3238-   753	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
/root/l00prite/pr1-fix-review-codex.md-3239-   754	                        wipe(candKey)
/root/l00prite/pr1-fix-review-codex.md-3240-   755	                        wipe(throwaway)
/root/l00prite/pr1-fix-review-codex.md-3241-   756	                        UnlockOrAdd.Rejected
--
/root/l00prite/pr1-fix-review-codex.md-3436-
/root/l00prite/pr1-fix-review-codex.md-3437-exec
/root/l00prite/pr1-fix-review-codex.md-3438-/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | sed -n '211,250p'; rg -n \"class VaultUnlockRouter|triple|create =|UnlockOrAdd\" apps/android/app/src/main/java/com/zitrone/app -g'*.kt' -C 6; git show 321b358:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | nl -ba | sed -n '110,160p'; git log --oneline --all -- apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | head -20" in /root/zitrone
/root/l00prite/pr1-fix-review-codex.md-3439- succeeded in 694ms:
/root/l00prite/pr1-fix-review-codex.md-3440-9ab8cb0 fix(vault): PR-1 review round — B1 fail-closed markers, B2 slot self-verify, F4 wipe, F9 slot-0 guard
/root/l00prite/pr1-fix-review-codex.md:3441:321b358 feat(vault): PR-1 — attemptUnlockOrAdd (second vault) + slot-0 burn reservation + v2 retire
/root/l00prite/pr1-fix-review-codex.md-3442-3c598ad feat(vault): PR-D2c — slot-A live over the vault (vault-only, fresh-install) (#46)
/root/l00prite/pr1-fix-review-codex.md-3443-1e8bc7e review(vault): PR-D2c round 3 — bot round 1 (flush-before-ack barrier absorbs D4, rotation routing, cancellation, biometric hardening)
/root/l00prite/pr1-fix-review-codex.md-3444-5157aa6 review(vault): PR-D2c round 1 — 88-agent adversarial findings (13 defects, 2 P1)
/root/l00prite/pr1-fix-review-codex.md-3445-92286e1 feat(vault): P1b-2 PR-D2c — slot A live over the vault (vault-only)
/root/l00prite/pr1-fix-review-codex.md-3446-399a9f0 feat(vault): P1a — plausible-deniability key-slot crypto primitive (isolated) (#37)
--
/root/l00prite/pr1-fix-review-codex.md-3578-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-173- * A, slot B, or nothing takes identical work). A SUCCESSFUL unlock additionally
/root/l00prite/pr1-fix-review-codex.md-3579-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-174- * opens one fixed-size payload; a wrong passphrase does not. So success and
/root/l00prite/pr1-fix-review-codex.md-3580-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-175- * failure are NOT equal-time — but this leaks nothing an observer doesn't
/root/l00prite/pr1-fix-review-codex.md-3581-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt-176- * already have: the app visibly unlocks (or doesn't) the instant it happens, so
/root/l00prite/pr1-fix-review-codex.md-3582---
/root/l00prite/pr1-fix-review-codex.md:3583:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-142- * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
/root/l00prite/pr1-fix-review-codex.md-3584-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-143- * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
/root/l00prite/pr1-fix-review-codex.md-3585-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-144- * the CALLER learns only which of the four happened, never which slot or how many exist.
/root/l00prite/pr1-fix-review-codex.md-3586-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-145- */
/root/l00prite/pr1-fix-review-codex.md-3587-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-146-sealed interface UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-3588-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:147:    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
--
/root/l00prite/pr1-fix-review-codex.md-3636-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-604-     * cases apart (the plausible-deniability + duress-credential timing contract):
/root/l00prite/pr1-fix-review-codex.md-3637-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-605-     *
/root/l00prite/pr1-fix-review-codex.md-3638-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:606:     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
/root/l00prite/pr1-fix-review-codex.md-3639-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-607-     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
/root/l00prite/pr1-fix-review-codex.md-3640-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-608-     *     (real vault-B material on create, pure timing filler otherwise);
/root/l00prite/pr1-fix-review-codex.md:3641:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-609-     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
/root/l00prite/pr1-fix-review-codex.md-3642-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-610-     *
/root/l00prite/pr1-fix-review-codex.md-3643-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:611:     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
/root/l00prite/pr1-fix-review-codex.md-3644-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-612-     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
/root/l00prite/pr1-fix-review-codex.md-3645-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:613:     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
/root/l00prite/pr1-fix-review-codex.md-3646-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-614-     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
--
/root/l00prite/pr1-fix-review-codex.md-3669-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:637:     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
/root/l00prite/pr1-fix-review-codex.md-3670-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-638-     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
/root/l00prite/pr1-fix-review-codex.md-3671-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-639-     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
/root/l00prite/pr1-fix-review-codex.md-3672-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-640-     * create write is not confirmed durable.
/root/l00prite/pr1-fix-review-codex.md-3673-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-641-     */
/root/l00prite/pr1-fix-review-codex.md:3674:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-642-    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-3675-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-643-        imageLock.withLock {
/root/l00prite/pr1-fix-review-codex.md-3676-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-644-            val image = canonical ?: run { open(); canonical!! }
/root/l00prite/pr1-fix-review-codex.md-3677-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-645-            val activeDek = dek ?: throw IllegalStateException("vault image not open")
/root/l00prite/pr1-fix-review-codex.md-3678-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-646-            val decoded = decodeImage(image)
/root/l00prite/pr1-fix-review-codex.md-3679-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt-647-
--
/root/l00prite/pr1-fix-review-codex.md-3825---
/root/l00prite/pr1-fix-review-codex.md-3826-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-81- * costs ZERO extra Argon2id (one 60-byte GCM decrypt) and the master key never outlives the verify — its
/root/l00prite/pr1-fix-review-codex.md-3827-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-82- * lifetime is identical to [sealSlot]'s.
/root/l00prite/pr1-fix-review-codex.md-3828-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-83- *
/root/l00prite/pr1-fix-review-codex.md-3829-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-84- * It proves the produced slot is actually openable with [vaultKey] BEFORE the caller persists it and hands
/root/l00prite/pr1-fix-review-codex.md:3830:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-85- * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
/root/l00prite/pr1-fix-review-codex.md-3831-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:86: * re-opening the persisted image the way [VaultImageStore.create] does (that would add SLOT_COUNT Argon2id
/root/l00prite/pr1-fix-review-codex.md-3832-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-87- * and break the plausible-deniability timing parity), so this is the parity-preserving substitute: it
/root/l00prite/pr1-fix-review-codex.md-3833-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-88- * catches a miscomputing [VaultSodiumOps] that returned a size-correct but wrong-content / wrong-key wrapped
/root/l00prite/pr1-fix-review-codex.md-3834-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-89- * blob (which would otherwise be written durably and leave the new vault permanently unopenable after
/root/l00prite/pr1-fix-review-codex.md-3835-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt-90- * process death). Throws [IllegalStateException] on a self-verify failure — a broken AEAD provider, which
--
/root/l00prite/pr1-fix-review-codex.md-3951-    73	        private const val MAX_BACKOFF_MS = 8_000L
/root/l00prite/pr1-fix-review-codex.md-3952-    74	    }
/root/l00prite/pr1-fix-review-codex.md-3953-    75	}
/root/l00prite/pr1-fix-review-codex.md-3954-
/root/l00prite/pr1-fix-review-codex.md-3955-exec
/root/l00prite/pr1-fix-review-codex.md:3956:/bin/bash -lc "rg -n \"attemptUnlockOrAdd|UnlockOrAdd|triple-entry|triple entry|consecutive\" apps/android/app/src/main/java -g'*.kt'; nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt | sed -n '95,160p'; git show 3c598ad:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | rg -n \"createVaultSlots|randomIndex\" -C 8" in /root/zitrone
/root/l00prite/pr1-fix-review-codex.md-3957- succeeded in 0ms:
/root/l00prite/pr1-fix-review-codex.md:3958:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:45: * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
/root/l00prite/pr1-fix-review-codex.md:3959:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:85: * a live session the in-memory key. The live [VaultImageStore.attemptUnlockOrAdd] add-path CANNOT verify by
/root/l00prite/pr1-fix-review-codex.md:3960:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:165: * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
/root/l00prite/pr1-fix-review-codex.md:3961:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
/root/l00prite/pr1-fix-review-codex.md-3962-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:146:sealed interface UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-3963-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:148:    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
/root/l00prite/pr1-fix-review-codex.md-3964-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:155:    data object Burn : UnlockOrAdd
/root/l00prite/pr1-fix-review-codex.md-3965-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:158:    data class Created(val open: VaultOpen) : UnlockOrAdd
/root/l00prite/pr1-fix-review-codex.md-3966-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:161:    data object Rejected : UnlockOrAdd
--
/root/l00prite/pr1-fix-review-codex.md-3969-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:616:     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
/root/l00prite/pr1-fix-review-codex.md-3970-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:617:     * [UnlockOrAdd.Rejected] having written nothing.
/root/l00prite/pr1-fix-review-codex.md-3971-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:620:     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
/root/l00prite/pr1-fix-review-codex.md-3972-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:633:     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
/root/l00prite/pr1-fix-review-codex.md-3973-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:635:     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
/root/l00prite/pr1-fix-review-codex.md:3974:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:642:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
/root/l00prite/pr1-fix-review-codex.md-3975-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:677:                        UnlockOrAdd.Burn
/root/l00prite/pr1-fix-review-codex.md-3976-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:693:                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
/root/l00prite/pr1-fix-review-codex.md-3977-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:718:                            UnlockOrAdd.Rejected
/root/l00prite/pr1-fix-review-codex.md-3978-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:745:                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
/root/l00prite/pr1-fix-review-codex.md-3979-apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:756:                        UnlockOrAdd.Rejected
--
/root/l00prite/pr1-fix-review-codex.md-3995-   110	            SecretKeySpec(key, "AES"),
/root/l00prite/pr1-fix-review-codex.md-3996-   111	            GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce),
/root/l00prite/pr1-fix-review-codex.md-3997-   112	        )
/root/l00prite/pr1-fix-review-codex.md-3998-   113	        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
/root/l00prite/pr1-fix-review-codex.md-3999-   114	        // Write straight into one pre-sized nonce||ciphertext||tag buffer — avoids
/root/l00prite/pr1-fix-review-codex.md:4000:   115	        // a second full-size copy on the 256 KiB payload path. JCE GCM appends the
/root/l00prite/pr1-fix-review-codex.md-4001-   116	        // 16-byte tag to the ciphertext (matching aead.ts's WebCrypto layout).
/root/l00prite/pr1-fix-review-codex.md-4002-   117	        val out = ByteArray(NONCE_BYTES + plaintext.size + AEAD_TAG_BYTES)
/root/l00prite/pr1-fix-review-codex.md-4003-   118	        nonce.copyInto(out, 0)
/root/l00prite/pr1-fix-review-codex.md-4004-   119	        cipher.doFinal(plaintext, 0, plaintext.size, out, NONCE_BYTES)
/root/l00prite/pr1-fix-review-codex.md-4005-   120	        return out
--
/root/l00prite/pr1-fix-review-codex.md-4019-   134	                SecretKeySpec(key, "AES"),
/root/l00prite/pr1-fix-review-codex.md-4020-   135	                // Nonce is the first NONCE_BYTES of box.
/root/l00prite/pr1-fix-review-codex.md-4021-   136	                GCMParameterSpec(AEAD_TAG_BYTES * 8, box, 0, NONCE_BYTES),
/root/l00prite/pr1-fix-review-codex.md-4022-   137	            )
/root/l00prite/pr1-fix-review-codex.md-4023-   138	            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
/root/l00prite/pr1-fix-review-codex.md:4024:   139	            // Decrypt directly from box at the nonce offset — no 256 KiB copyOfRange.
/root/l00prite/pr1-fix-review-codex.md-4025-   140	            cipher.doFinal(box, NONCE_BYTES, box.size - NONCE_BYTES)
/root/l00prite/pr1-fix-review-codex.md-4026-   141	        } catch (e: GeneralSecurityException) {
/root/l00prite/pr1-fix-review-codex.md-4027-   142	            // Tag failure: wrong key, a filler slot, or tampering — all reported
/root/l00prite/pr1-fix-review-codex.md-4028-   143	            // the same way, as "no match" (mirrors aead.ts aeadDecrypt throwing).
/root/l00prite/pr1-fix-review-codex.md-4029-   144	            // GeneralSecurityException (not just AEADBadTagException) because some
--
/root/l00prite/pr1-fix-review-codex.md-4114-# Findings report
/root/l00prite/pr1-fix-review-codex.md-4115-
/root/l00prite/pr1-fix-review-codex.md-4116-## Finding 1 — Stale security-critical API documentation
/root/l00prite/pr1-fix-review-codex.md-4117-
/root/l00prite/pr1-fix-review-codex.md-4118-**SEVERITY:** Low  
/root/l00prite/pr1-fix-review-codex.md:4119:**FILE / FUNCTION:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 607–640  
/root/l00prite/pr1-fix-review-codex.md-4120-**DEFECT MECHANISM:** The function contract still states that candidate sealing uses one wrapped-key GCM, that creation clears both delete markers, and that marker-clear failure can throw `NotDurable`. Actual code now performs two candidate wrapped-key GCM operations and never clears either marker.  
/root/l00prite/pr1-fix-review-codex.md-4121-**FAILURE / ATTACK SCENARIO:** A future maintainer relies on this contract while changing timing accounting or deletion handling and incorrectly restores marker clearing or five-GCM assumptions, weakening the reviewed invariants.
/root/l00prite/pr1-fix-review-codex.md-4122-
/root/l00prite/pr1-fix-review-codex.md-4123-## B1 fail-closed marker handling — Clean
/root/l00prite/pr1-fix-review-codex.md-4124-
/root/l00prite/pr1-fix-review-codex.md:4125:`attemptUnlockOrAdd` is a pure marker reader.
/root/l00prite/pr1-fix-review-codex.md-4126-
/root/l00prite/pr1-fix-review-codex.md-4127-Every marker-touching production line is:
/root/l00prite/pr1-fix-review-codex.md-4128-
/root/l00prite/pr1-fix-review-codex.md-4129-- `create`, lines 469–473: reads markers and may call `clearBothMarkersDurably`.
/root/l00prite/pr1-fix-review-codex.md:4130:- `attemptUnlockOrAdd`, lines 710–712: reads both markers only.
/root/l00prite/pr1-fix-review-codex.md-4131-- `markDeleteIntent`, lines 962–964: writes intent.
/root/l00prite/pr1-fix-review-codex.md-4132-- `markServerDeleteConfirmed`, lines 966–968: writes confirmed.
/root/l00prite/pr1-fix-review-codex.md-4133-- `clearDeleteIntent`, lines 977–986: deletes intent.
/root/l00prite/pr1-fix-review-codex.md-4134-- `clearBothMarkersDurably`, lines 997–1008: deletes both.
/root/l00prite/pr1-fix-review-codex.md-4135-- `writeDurableMarker`, lines 1012–1019: creates a supplied marker.
/root/l00prite/pr1-fix-review-codex.md-4136-- `destroy`, line 1038: writes confirmed through `writeDurableMarker`.
/root/l00prite/pr1-fix-review-codex.md-4137-
/root/l00prite/pr1-fix-review-codex.md:4138:No call to `clearBothMarkersDurably`, `clearDeleteIntent`, marker deletion, or marker creation is reachable from `attemptUnlockOrAdd`.
/root/l00prite/pr1-fix-review-codex.md-4139-
/root/l00prite/pr1-fix-review-codex.md-4140-When either `Files.notExists` check is false, lines 713–718 perform `sealPayload(candKey, ByteArray(0), ops)` and return `UnlockOrAdd.Rejected`; no exception is intentionally raised and neither marker is modified. Exceptions from the crypto provider may still propagate, as on ordinary rejection.
/root/l00prite/pr1-fix-review-codex.md-4141-
/root/l00prite/pr1-fix-review-codex.md-4142-## TOCTOU — Clean within the application concurrency model
/root/l00prite/pr1-fix-review-codex.md-4143-
/root/l00prite/pr1-fix-review-codex.md:4144:`attemptUnlockOrAdd` holds one uninterrupted `imageLock.withLock` from line 643 through return. The marker checks at lines 710–712 and image write at line 731 occur without lock release.
/root/l00prite/pr1-fix-review-codex.md-4145-
/root/l00prite/pr1-fix-review-codex.md-4146-`markDeleteIntent` and `markServerDeleteConfirmed` acquire the same lock. `create`, `destroy`, and marker-clearing operations also use that lock. `open()` is reentrant under the same `ReentrantLock`; it does not release it.
/root/l00prite/pr1-fix-review-codex.md-4147-
/root/l00prite/pr1-fix-review-codex.md-4148-No application callback derives marker state outside the lock. Crypto and injected filesystem callbacks execute under the lock but production implementations do not write marker files. Direct mutation by another process or external filesystem actor is outside this in-process lock guarantee.
/root/l00prite/pr1-fix-review-codex.md-4149-
--
/root/l00prite/pr1-fix-review-codex.md-4167-
/root/l00prite/pr1-fix-review-codex.md-4168-- Four Argon2id derivations and four wrapped-key decrypts in the complete `tryPassphrase` sweep.
/root/l00prite/pr1-fix-review-codex.md-4169-- One Argon2id derivation in `sealSlotSelfVerifying`.
/root/l00prite/pr1-fix-review-codex.md-4170-- One candidate wrapped-key encrypt.
/root/l00prite/pr1-fix-review-codex.md-4171-- One candidate wrapped-key verification decrypt.
/root/l00prite/pr1-fix-review-codex.md:4172:- Exactly one 256 KiB payload GCM.
/root/l00prite/pr1-fix-review-codex.md-4173-
/root/l00prite/pr1-fix-review-codex.md-4174-Totals: exactly five Argon2id and six wrapped-key GCM operations.
/root/l00prite/pr1-fix-review-codex.md-4175-
/root/l00prite/pr1-fix-review-codex.md-4176-Payload operation by outcome:
/root/l00prite/pr1-fix-review-codex.md-4177-
--
/root/l00prite/pr1-fix-review-codex.md-4213-
/root/l00prite/pr1-fix-review-codex.md-4214-Thus the range tightening does change old slot-0 biometric state to “not enabled,” but it does not cause additional access loss beyond the deliberate v2 retirement policy.
/root/l00prite/pr1-fix-review-codex.md-4215-
/root/l00prite/pr1-fix-review-codex.md-4216-## Router/triple-entry and general regression review — Clean
/root/l00prite/pr1-fix-review-codex.md-4217-
/root/l00prite/pr1-fix-review-codex.md:4218:Marker-present creation returns the same `Rejected` value as an ordinary failed attempt. `attemptUnlockOrAdd` holds no triple-entry state, so it cannot itself leak, retry, or loop.
/root/l00prite/pr1-fix-review-codex.md-4219-
/root/l00prite/pr1-fix-review-codex.md:4220:No production caller of `attemptUnlockOrAdd` currently exists in this source tree; only tests call it. Consequently, there is presently no end-to-end router behavior beyond the method’s uniform `Rejected` result to assess.
/root/l00prite/pr1-fix-review-codex.md-4221-
/root/l00prite/pr1-fix-review-codex.md-4222-The `when` restructure preserves precedence: burn match, vault match, requested create, then reject. Canonical state advances only after the image rename. The existing DEK is reused and not rewritten, so no new DEK/image desynchronization path was introduced.
/root/l00prite/pr1-fix-review-codex.md-4223-
/root/l00prite/pr1-fix-review-codex.md-4224-**Overall verdict: PASS with one Low-severity stale-contract documentation finding; the requested B1, TOCTOU, B2, timing, F4, and F9 invariants hold in actual source.**
/root/l00prite/pr1-fix-review-codex.md-4225-tokens used
--
/root/l00prite/pr1-fix-review-codex.md-4227-# Findings report
/root/l00prite/pr1-fix-review-codex.md-4228-
/root/l00prite/pr1-fix-review-codex.md-4229-## Finding 1 — Stale security-critical API documentation
/root/l00prite/pr1-fix-review-codex.md-4230-
/root/l00prite/pr1-fix-review-codex.md-4231-**SEVERITY:** Low  
/root/l00prite/pr1-fix-review-codex.md:4232:**FILE / FUNCTION:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 607–640  
/root/l00prite/pr1-fix-review-codex.md-4233-**DEFECT MECHANISM:** The function contract still states that candidate sealing uses one wrapped-key GCM, that creation clears both delete markers, and that marker-clear failure can throw `NotDurable`. Actual code now performs two candidate wrapped-key GCM operations and never clears either marker.  
/root/l00prite/pr1-fix-review-codex.md-4234-**FAILURE / ATTACK SCENARIO:** A future maintainer relies on this contract while changing timing accounting or deletion handling and incorrectly restores marker clearing or five-GCM assumptions, weakening the reviewed invariants.
/root/l00prite/pr1-fix-review-codex.md-4235-
/root/l00prite/pr1-fix-review-codex.md-4236-## B1 fail-closed marker handling — Clean
/root/l00prite/pr1-fix-review-codex.md-4237-
/root/l00prite/pr1-fix-review-codex.md:4238:`attemptUnlockOrAdd` is a pure marker reader.
/root/l00prite/pr1-fix-review-codex.md-4239-
/root/l00prite/pr1-fix-review-codex.md-4240-Every marker-touching production line is:
/root/l00prite/pr1-fix-review-codex.md-4241-
/root/l00prite/pr1-fix-review-codex.md-4242-- `create`, lines 469–473: reads markers and may call `clearBothMarkersDurably`.
/root/l00prite/pr1-fix-review-codex.md:4243:- `attemptUnlockOrAdd`, lines 710–712: reads both markers only.
/root/l00prite/pr1-fix-review-codex.md-4244-- `markDeleteIntent`, lines 962–964: writes intent.
/root/l00prite/pr1-fix-review-codex.md-4245-- `markServerDeleteConfirmed`, lines 966–968: writes confirmed.
/root/l00prite/pr1-fix-review-codex.md-4246-- `clearDeleteIntent`, lines 977–986: deletes intent.
/root/l00prite/pr1-fix-review-codex.md-4247-- `clearBothMarkersDurably`, lines 997–1008: deletes both.
/root/l00prite/pr1-fix-review-codex.md-4248-- `writeDurableMarker`, lines 1012–1019: creates a supplied marker.
/root/l00prite/pr1-fix-review-codex.md-4249-- `destroy`, line 1038: writes confirmed through `writeDurableMarker`.
/root/l00prite/pr1-fix-review-codex.md-4250-
/root/l00prite/pr1-fix-review-codex.md:4251:No call to `clearBothMarkersDurably`, `clearDeleteIntent`, marker deletion, or marker creation is reachable from `attemptUnlockOrAdd`.
/root/l00prite/pr1-fix-review-codex.md-4252-
/root/l00prite/pr1-fix-review-codex.md-4253-When either `Files.notExists` check is false, lines 713–718 perform `sealPayload(candKey, ByteArray(0), ops)` and return `UnlockOrAdd.Rejected`; no exception is intentionally raised and neither marker is modified. Exceptions from the crypto provider may still propagate, as on ordinary rejection.
/root/l00prite/pr1-fix-review-codex.md-4254-
/root/l00prite/pr1-fix-review-codex.md-4255-## TOCTOU — Clean within the application concurrency model
/root/l00prite/pr1-fix-review-codex.md-4256-
/root/l00prite/pr1-fix-review-codex.md:4257:`attemptUnlockOrAdd` holds one uninterrupted `imageLock.withLock` from line 643 through return. The marker checks at lines 710–712 and image write at line 731 occur without lock release.
/root/l00prite/pr1-fix-review-codex.md-4258-
/root/l00prite/pr1-fix-review-codex.md-4259-`markDeleteIntent` and `markServerDeleteConfirmed` acquire the same lock. `create`, `destroy`, and marker-clearing operations also use that lock. `open()` is reentrant under the same `ReentrantLock`; it does not release it.
/root/l00prite/pr1-fix-review-codex.md-4260-
/root/l00prite/pr1-fix-review-codex.md-4261-No application callback derives marker state outside the lock. Crypto and injected filesystem callbacks execute under the lock but production implementations do not write marker files. Direct mutation by another process or external filesystem actor is outside this in-process lock guarantee.
/root/l00prite/pr1-fix-review-codex.md-4262-
--
/root/l00prite/pr1-fix-review-codex.md-4280-
/root/l00prite/pr1-fix-review-codex.md-4281-- Four Argon2id derivations and four wrapped-key decrypts in the complete `tryPassphrase` sweep.
/root/l00prite/pr1-fix-review-codex.md-4282-- One Argon2id derivation in `sealSlotSelfVerifying`.
/root/l00prite/pr1-fix-review-codex.md-4283-- One candidate wrapped-key encrypt.
/root/l00prite/pr1-fix-review-codex.md-4284-- One candidate wrapped-key verification decrypt.
/root/l00prite/pr1-fix-review-codex.md:4285:- Exactly one 256 KiB payload GCM.
/root/l00prite/pr1-fix-review-codex.md-4286-
/root/l00prite/pr1-fix-review-codex.md-4287-Totals: exactly five Argon2id and six wrapped-key GCM operations.
/root/l00prite/pr1-fix-review-codex.md-4288-
/root/l00prite/pr1-fix-review-codex.md-4289-Payload operation by outcome:
/root/l00prite/pr1-fix-review-codex.md-4290-
--
/root/l00prite/pr1-fix-review-codex.md-4326-
/root/l00prite/pr1-fix-review-codex.md-4327-Thus the range tightening does change old slot-0 biometric state to “not enabled,” but it does not cause additional access loss beyond the deliberate v2 retirement policy.
/root/l00prite/pr1-fix-review-codex.md-4328-
/root/l00prite/pr1-fix-review-codex.md-4329-## Router/triple-entry and general regression review — Clean
/root/l00prite/pr1-fix-review-codex.md-4330-
/root/l00prite/pr1-fix-review-codex.md:4331:Marker-present creation returns the same `Rejected` value as an ordinary failed attempt. `attemptUnlockOrAdd` holds no triple-entry state, so it cannot itself leak, retry, or loop.
/root/l00prite/pr1-fix-review-codex.md-4332-
/root/l00prite/pr1-fix-review-codex.md:4333:No production caller of `attemptUnlockOrAdd` currently exists in this source tree; only tests call it. Consequently, there is presently no end-to-end router behavior beyond the method’s uniform `Rejected` result to assess.
/root/l00prite/pr1-fix-review-codex.md-4334-
/root/l00prite/pr1-fix-review-codex.md-4335-The `when` restructure preserves precedence: burn match, vault match, requested create, then reject. Canonical state advances only after the image rename. The existing DEK is reused and not rewritten, so no new DEK/image desynchronization path was introduced.
/root/l00prite/pr1-fix-review-codex.md-4336-
/root/l00prite/pr1-fix-review-codex.md-4337-**Overall verdict: PASS with one Low-severity stale-contract documentation finding; the requested B1, TOCTOU, B2, timing, F4, and F9 invariants hold in actual source.**
fatal: /root/l00prite/pr1-attemptUnlockOrAdd-spec.md: '/root/l00prite/pr1-attemptUnlockOrAdd-spec.md' is outside repository at '/root/zitrone'
docs/SECURITY_MODEL.md

exec
/bin/bash -lc 'rg -n "''^##? .*5|crypto-budget|Crypto budget|payload GCM|Payload GCM|successful create|marker-present" /root/l00prite/pr1-attemptUnlockOrAdd-spec.md | head -80; sed -n '"'180,300p' /root/l00prite/pr1-attemptUnlockOrAdd-spec.md; git show 8f4545d"'^:apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt >/dev/null; rg -n "attemptUnlockOrAdd'"\\(\" apps/android/app/src/main apps/android/app/src/test | head -100" in /root/zitrone
 succeeded in 0ms:
136: * 256 KiB payload GCM + one tiny wrapped-key GCM. A slot match (0..3) ALWAYS wins over [create]; a
228:## 5. TIMING-PARITY RE-VERIFICATION — 3-attempt model + burn slot 0
238:> (B2 fix; the original table counted only the candidate seal). (2) A **successful create does 2 payload
241:> these counts; the marker-present create fails closed to the 1-payload reject budget.
243:| Outcome | Argon2id | 256 KiB payload GCM | wrapped-key GCM (60 B) | ~1 MiB outer GCM + write |
264:5 Argon2id + one 256 KiB payload GCM; the wipe (burn) and the unlock (vault) are both *post-outcome*
344:7. **Marker interaction (OQ3 / invariant 6):** present intent cleared by a successful create; non-durable
354:## 9. Reviewer focus (lean pass — ≤5 agents, one skeptic; free Codex/Gemini for breadth)

    // ── VAULT MATCH (slot 1..3) WINS over create ───────────────────────────────
    unlock != null -> {
      wipe(candKey)
      val pt = try {
        openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)   // (3) the 1×256KiB GCM
      } catch (t: Throwable) { wipe(unlock.vaultKey); throw VaultImageException.CorruptImage() }
      if (pt == null) { wipe(unlock.vaultKey); throw VaultImageException.CorruptImage() }
      return Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
    }

    // ── CREATE a vault ─────────────────────────────────────────────────────────
    create -> {
      val absent = Files.notExists(deleteIntentFile.toPath()) &&
                   Files.notExists(serverDeletedFile.toPath())
      if (!absent && !clearBothMarkersDurably()) throw VaultImageException.NotDurable()

      val sealedGenesis = sealPayload(candKey, genesisPayload, ops)             // (3) the 1×256KiB GCM
      val newSlots    = decoded.slots.toMutableList().also    { it[candSlotIndex] = candSlot }
      val newPayloads = decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
      val newInner    = encodeImage(VaultImage(newSlots, newPayloads))
      val outer       = ops.aeadEncrypt(dek!!, newInner, VAULT_IMAGE_OUTER_AD)
      val sync        = atomicWrite(binFile, outer)         // throws pre-rename; returns DirSyncResult
      canonical       = newInner                            // advance BEFORE durability check (as writeSealedPayload)
      if (sync != DirSyncResult.DURABLE) { wipe(candKey); throw VaultImageException.NotDurable() }
      return Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))  // candKey handed to session
    }

    // ── REJECT ─────────────────────────────────────────────────────────────────
    else -> {
      val throwaway = sealPayload(candKey, EMPTY, ops)      // (3) the 1×256KiB GCM — LOAD-BEARING filler
      wipe(candKey); wipe(throwaway)
      return Rejected
    }
  }
} catch (t: Throwable) { wipe(candKey); throw t }           // defensive; double-wipe is a no-op
```

**Deliberate deviations from `create()` (forced by timing parity — §5):**
- **One `tryPassphrase`, not two.** Do NOT route create through `addVaultToImage`/`addVaultSlot` (they
  re-run `tryPassphrase` → 8 Argon2id on create vs 5 on reject). Reimplement inline from primitives; the
  collision check is unnecessary (the sweep already proved no match).
- **No verify-by-`unlockImage`** on create (+SLOT_COUNT derivations). Build the `VaultOpen` directly from
  the known `candKey` + `genesisPayload`; cheap non-KDF size checks (already `require`d by primitives)
  remain. **Reviewer: confirm dropping the full re-verify is acceptable.**

---

## 5. TIMING-PARITY RE-VERIFICATION — 3-attempt model + burn slot 0

**Question (user-mandated):** (a) does each of the 3 triple-entry attempts remain individually
indistinguishable from an ordinary single failed unlock, and (b) does slot 0's presence in `tryPassphrase`
introduce any timing asymmetry between "burn matched", "vault matched", and "no match"?

**Per-call crypto budget by outcome:**

> **Correction (2026-07-24). Two updates since the original table.** (1) wrapped-key GCM is **6** per call,
> not 1 — 4 sweep unwrap attempts + 1 candidate `sealSlotSelfVerifying` encrypt + 1 self-verify decrypt
> (B2 fix; the original table counted only the candidate seal). (2) A **successful create does 2 payload
> GCM** — the genesis seal + a self-verify open (G3 fix, constant-time content compare) — a create-only
> residual alongside the outer GCM + write; every other outcome does 1. The parity test asserts exactly
> these counts; the marker-present create fails closed to the 1-payload reject budget.

| Outcome | Argon2id | 256 KiB payload GCM | wrapped-key GCM (60 B) | ~1 MiB outer GCM + write |
|---|---|---|---|---|
| Unlocked (slot 1–3) | 4 sweep + 1 candidate = **5** | 1 (openPayload) | 4 unwrap + 1 seal + 1 self-verify = **6** | none |
| **Burn (slot 0)** | **5** | 1 (openPayload slot 0, discarded) | **6** | none |
| Created (markers absent) | **5** | **2** (seal genesis + self-verify open) | **6** | **yes (persist)** |
| Created→Rejected (marker present, fail-closed) | **5** | 1 (seal throwaway) | **6** | none |
| Rejected (no match) | **5** | 1 (seal throwaway) | **6** | none |

**(a) 3-attempt parity — PASS.** Each ritual attempt issues the identical `attemptUnlockOrAdd` op as any
ordinary failed unlock: **5 Argon2id + 1×256 KiB GCM + 1 tiny GCM**, invariant across outcome and across
attempt position (1/2/3). The triple-entry counter + candidate compare live entirely in router RAM
(SHA-256 + constant-time `MessageDigest.isEqual`, ~µs, computed every attempt), never touching the KDF
budget; `create` only selects whether the post-outcome persist runs. So attempt 1 ≡ attempt 2 ≡ ordinary
wrong-password ≡ Rejected, byte-for-byte; attempt 3 does the same 5 Argon2id, then persists.

**(b) burn / slot 0 parity — PASS.** The sweep is over `SLOT_COUNT = 4` including slot 0 — that count is
unchanged by burn (slot 0 was always 1 of 4). **Armed vs unarmed slot 0 is timing-identical**: either way
`tryPassphrase` runs one Argon2id over slot 0's salt and one GCM-unwrap of its 60-byte wrapped key; a
GCM auth success (armed + burn pass) vs failure (filler, or wrong pass) is the same constant-time-ish
verify, and there is no early exit — this is precisely the real-vs-filler slot indistinguishability the
whole scheme already rests on. **Burn-match and vault-match are pre-outcome timing-identical**: both do
5 Argon2id + one 256 KiB payload GCM; the wipe (burn) and the unlock (vault) are both *post-outcome*
observable events, exactly like unlock-vs-stay-locked today. No-match is likewise 5 Argon2id + one GCM.

**Slot-0 exclusion from placement — no signature.** `candSlotIndex = 1 + randomIndex(3)` vs
`randomIndex(4)` both draw 4 CSPRNG bytes + one modulo; one extra add — sub-nanosecond, unmeasurable.
**Write-IO reveals no slot:** Created re-encodes and writes the *entire* ~1 MiB image (all 4 slots + 4
payloads, fresh outer nonce) in one atomic rename, so an IO-pattern observer cannot tell which slot
changed. The only place slot-0 exclusion is observable is **at-rest multi-snapshot content diffing**
(slot 0's region never changes during vault use; only burn setup touches it) — and that is an
already-accepted, documented limitation that reveals only "a burn feature exists" (public), never
arm-state or vault count. Consistent with the recorded deniability rationale.

**Sole residual (unchanged by any of this):** Created persists synchronously — ~1 MiB outer-GCM +
`atomicWrite` + `dirSync`, ~tens of ms, *after* the outcome is determined, under ~1 s of KDF. Present in
any creation model; not introduced or worsened by triple-entry or by burn. Same class as the documented
payload-open asymmetry. **Lever if ever wanted:** gate every unlock/reject on a synchronous throwaway
write. Rejected — needless slowdown + a new failure surface on every wrong password.

**Load-bearing guarantee, stated precisely:** the Argon2id work — the only second-scale, memory-hard,
reliably-stopwatch-measurable component — is **identical (5×)** across all four outcomes (unlock / burn /
create / reject) and all three attempt positions. Sub-KDF GCM/write deltas on the create path are the
documented residual.

**Backoff (PR-2 context):** the existing RAM backoff fires on Rejected attempts (`recordFailure`), so a
triple-entry ritual looks like 3 fumbled passwords — reinforcing indistinguishability. `candidateCount`
(identical-string streak, reset on background/lock/process-death per the uninterrupted-sequence guard) is
SEPARATE from the backoff `failedAttempts`. A Burn outcome must reset neither into a create — it returns
before any counter logic.

---

## 6. Durability & failure semantics

- `open()` throw (Missing/Corrupt): propagates (lazy open), same as `unlock()`.
- Vault-slot match, unreadable payload: throw `CorruptImage` (a real vault's payload is damaged — image
  state, not a wrong guess), so the ritual counter never advances over a real-but-corrupt vault.
  **Reviewer: weigh vs returning `Rejected`/null.**
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:101:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true) // create ignored — match wins
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:111:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:118:        assertTrue(fresh.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:127:        val r = s.attemptUnlockOrAdd("nope", genesis, create = false)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:138:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:170:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:180:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("random", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:200:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:219:            fresh.attemptUnlockOrAdd("passA", genesis, create = false)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:234:            val r = s.attemptUnlockOrAdd("B$it", genesis, create = true) as UnlockOrAdd.Created
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:260:        assertTrue(s.attemptUnlockOrAdd("passA", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:261:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:264:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passA", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:265:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:280:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:284:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:296:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passB", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:313:            s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:332:            s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:346:            s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:349:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:385:            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:388:            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:391:            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:394:            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:400:            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:654:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {

codex
The core verify is correctly positioned before image construction and persistence, and its mismatch test does produce a valid decrypt with altered content of unchanged length. One documentation inconsistency has emerged in the external §5 prose despite the corrected table; I’m pinning exact lines and checking whether any other stale statement or exception/cleanup edge changes the severity.
exec
/bin/bash -lc "nl -ba /root/l00prite/pr1-attemptUnlockOrAdd-spec.md | sed -n '228,282p'; nl -ba apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt | sed -n '35,75p;300,410p'; rg -n \"val genesis|genesis =|ByteArray\\(0\\).*genesis\" apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt; rg -n \"Exactly one|EXACTLY ONE|1×256|one 256 KiB|one tiny wrapped|unchanged by any|not introduced|not worsened|payload-open asymmetry\" /root/l00prite/pr1-attemptUnlockOrAdd-spec.md" in /root/zitrone
 succeeded in 0ms:
   228	## 5. TIMING-PARITY RE-VERIFICATION — 3-attempt model + burn slot 0
   229	
   230	**Question (user-mandated):** (a) does each of the 3 triple-entry attempts remain individually
   231	indistinguishable from an ordinary single failed unlock, and (b) does slot 0's presence in `tryPassphrase`
   232	introduce any timing asymmetry between "burn matched", "vault matched", and "no match"?
   233	
   234	**Per-call crypto budget by outcome:**
   235	
   236	> **Correction (2026-07-24). Two updates since the original table.** (1) wrapped-key GCM is **6** per call,
   237	> not 1 — 4 sweep unwrap attempts + 1 candidate `sealSlotSelfVerifying` encrypt + 1 self-verify decrypt
   238	> (B2 fix; the original table counted only the candidate seal). (2) A **successful create does 2 payload
   239	> GCM** — the genesis seal + a self-verify open (G3 fix, constant-time content compare) — a create-only
   240	> residual alongside the outer GCM + write; every other outcome does 1. The parity test asserts exactly
   241	> these counts; the marker-present create fails closed to the 1-payload reject budget.
   242	
   243	| Outcome | Argon2id | 256 KiB payload GCM | wrapped-key GCM (60 B) | ~1 MiB outer GCM + write |
   244	|---|---|---|---|---|
   245	| Unlocked (slot 1–3) | 4 sweep + 1 candidate = **5** | 1 (openPayload) | 4 unwrap + 1 seal + 1 self-verify = **6** | none |
   246	| **Burn (slot 0)** | **5** | 1 (openPayload slot 0, discarded) | **6** | none |
   247	| Created (markers absent) | **5** | **2** (seal genesis + self-verify open) | **6** | **yes (persist)** |
   248	| Created→Rejected (marker present, fail-closed) | **5** | 1 (seal throwaway) | **6** | none |
   249	| Rejected (no match) | **5** | 1 (seal throwaway) | **6** | none |
   250	
   251	**(a) 3-attempt parity — PASS.** Each ritual attempt issues the identical `attemptUnlockOrAdd` op as any
   252	ordinary failed unlock: **5 Argon2id + 1×256 KiB GCM + 1 tiny GCM**, invariant across outcome and across
   253	attempt position (1/2/3). The triple-entry counter + candidate compare live entirely in router RAM
   254	(SHA-256 + constant-time `MessageDigest.isEqual`, ~µs, computed every attempt), never touching the KDF
   255	budget; `create` only selects whether the post-outcome persist runs. So attempt 1 ≡ attempt 2 ≡ ordinary
   256	wrong-password ≡ Rejected, byte-for-byte; attempt 3 does the same 5 Argon2id, then persists.
   257	
   258	**(b) burn / slot 0 parity — PASS.** The sweep is over `SLOT_COUNT = 4` including slot 0 — that count is
   259	unchanged by burn (slot 0 was always 1 of 4). **Armed vs unarmed slot 0 is timing-identical**: either way
   260	`tryPassphrase` runs one Argon2id over slot 0's salt and one GCM-unwrap of its 60-byte wrapped key; a
   261	GCM auth success (armed + burn pass) vs failure (filler, or wrong pass) is the same constant-time-ish
   262	verify, and there is no early exit — this is precisely the real-vs-filler slot indistinguishability the
   263	whole scheme already rests on. **Burn-match and vault-match are pre-outcome timing-identical**: both do
   264	5 Argon2id + one 256 KiB payload GCM; the wipe (burn) and the unlock (vault) are both *post-outcome*
   265	observable events, exactly like unlock-vs-stay-locked today. No-match is likewise 5 Argon2id + one GCM.
   266	
   267	**Slot-0 exclusion from placement — no signature.** `candSlotIndex = 1 + randomIndex(3)` vs
   268	`randomIndex(4)` both draw 4 CSPRNG bytes + one modulo; one extra add — sub-nanosecond, unmeasurable.
   269	**Write-IO reveals no slot:** Created re-encodes and writes the *entire* ~1 MiB image (all 4 slots + 4
   270	payloads, fresh outer nonce) in one atomic rename, so an IO-pattern observer cannot tell which slot
   271	changed. The only place slot-0 exclusion is observable is **at-rest multi-snapshot content diffing**
   272	(slot 0's region never changes during vault use; only burn setup touches it) — and that is an
   273	already-accepted, documented limitation that reveals only "a burn feature exists" (public), never
   274	arm-state or vault count. Consistent with the recorded deniability rationale.
   275	
   276	**Sole residual (unchanged by any of this):** Created persists synchronously — ~1 MiB outer-GCM +
   277	`atomicWrite` + `dirSync`, ~tens of ms, *after* the outcome is determined, under ~1 s of KDF. Present in
   278	any creation model; not introduced or worsened by triple-entry or by burn. Same class as the documented
   279	payload-open asymmetry. **Lever if ever wanted:** gate every unlock/reject on a synchronous throwaway
   280	write. Rejected — needless slowdown + a new failure surface on every wrong password.
   281	
   282	**Load-bearing guarantee, stated precisely:** the Argon2id work — the only second-scale, memory-hard,
    35	import org.junit.Assert.assertArrayEquals
    36	import org.junit.Assert.assertEquals
    37	import org.junit.Assert.assertFalse
    38	import org.junit.Assert.assertNull
    39	import org.junit.Assert.assertThrows
    40	import org.junit.Assert.assertTrue
    41	import org.junit.Rule
    42	import org.junit.Test
    43	import org.junit.rules.TemporaryFolder
    44	import java.io.File
    45	import java.security.MessageDigest
    46	import javax.crypto.Cipher
    47	import javax.crypto.spec.GCMParameterSpec
    48	import javax.crypto.spec.SecretKeySpec
    49	
    50	/**
    51	 * PR-1 tests for [VaultImageStore.attemptUnlockOrAdd] (0.9.2 second vault + Pucker Burn) and the
    52	 * v2→[VaultImageException.LegacyImage] read-path branch + [VaultImageStore.retireLegacyImage].
    53	 *
    54	 * Same conventions as [VaultImageStoreTest]: the AEAD + CSPRNG path is the REAL production byte path
    55	 * ([LibsodiumVaultOps] over SodiumJava); only Argon2id (→ a fast SHA-256 [fast] stand-in) and the
    56	 * Android Keystore device key (→ [FakeDeviceKeyCipher2]) are swapped for host testing.
    57	 */
    58	class AttemptUnlockOrAddTest {
    59	
    60	    @get:Rule
    61	    val tmp = TemporaryFolder()
    62	
    63	    private val realOps = LibsodiumVaultOps(SodiumJava())
    64	    private val cipher = FakeDeviceKeyCipher2()
    65	
    66	    /** Fast deterministic Argon2id stand-in: SHA-256(passphrase ‖ salt). */
    67	    private val fast: KeyDeriver = { passphrase, salt ->
    68	        val md = MessageDigest.getInstance("SHA-256")
    69	        md.update(passphrase.toByteArray(Charsets.UTF_8))
    70	        md.update(salt)
    71	        md.digest()
    72	    }
    73	
    74	    private fun store(dir: File, ops: VaultSodiumOps = realOps, dirSync: ((File?) -> DirSyncResult)? = null) =
    75	        if (dirSync == null) VaultImageStore(dir, ops, cipher, fast)
   300	
   301	    @Test
   302	    fun create_selfVerifiesTheSealedSlot_throwsAndPersistsNothing_onAMisSealingProvider() {
   303	        // B2: a miscomputing aeadEncrypt (size-correct, wrong-content wrapped key) must be caught by the
   304	        // candidate self-verify BEFORE anything is persisted — otherwise the new vault would be written
   305	        // durably yet be permanently unopenable after process death.
   306	        val dir = tmp.newFolder()
   307	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   308	        val misSealing = MisSealingWrappedKeyOps(realOps)
   309	        val s = store(dir, ops = misSealing)
   310	        s.open()
   311	        val before = bin(dir).readBytes()
   312	        assertThrows(IllegalStateException::class.java) {
   313	            s.attemptUnlockOrAdd("passB", genesis, create = true)
   314	        }
   315	        assertArrayEquals("a failed self-verify persists nothing", before, bin(dir).readBytes())
   316	    }
   317	
   318	    @Test
   319	    fun create_selfVerifiesThePayload_throwsAndPersistsNothing_onAMisSealingPayloadProvider() {
   320	        // G3: a miscomputing PAYLOAD aeadEncrypt producing a SELF-CONSISTENT but WRONG-content box (it
   321	        // decrypts fine, just not to genesisPayload) must be caught by the payload self-verify's
   322	        // CONSTANT-TIME CONTENT compare BEFORE anything is persisted — otherwise a full working session runs
   323	        // over a vault that is permanently unopenable after process death. A "decryption succeeded" check
   324	        // alone would NOT catch this.
   325	        val dir = tmp.newFolder()
   326	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   327	        val misSealing = MisSealingPayloadOps(realOps)
   328	        val s = store(dir, ops = misSealing)
   329	        s.open()
   330	        val before = bin(dir).readBytes()
   331	        assertThrows(IllegalStateException::class.java) {
   332	            s.attemptUnlockOrAdd("passB", genesis, create = true)
   333	        }
   334	        assertArrayEquals("a failed payload self-verify persists nothing", before, bin(dir).readBytes())
   335	    }
   336	
   337	    // ─────────────────────────── durability ───────────────────────────
   338	
   339	    @Test
   340	    fun create_notDurable_throwsNotDurable_butCanonicalAdvanced() {
   341	        val dir = tmp.newFolder()
   342	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   343	        val s = store(dir, dirSync = { DirSyncResult.NOT_DURABLE })
   344	        s.open()
   345	        assertThrows(VaultImageException.NotDurable::class.java) {
   346	            s.attemptUnlockOrAdd("passB", genesis, create = true)
   347	        }
   348	        // canonical advanced (bytes are on disk): the new vault unlocks IN-MEMORY on the same store.
   349	        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
   350	    }
   351	
   352	    // ─────────────────────────── crypto-budget PARITY (load-bearing) ───────────────────────────
   353	
   354	    @Test
   355	    fun cryptoBudgetParity_5argon2id_6wrappedGcm_acrossOutcomes_createAloneDoublesPayloadAndOuter() {
   356	        // Every outcome issues IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal) and
   357	        // 6 wrapped-key GCM (4 unwrap + 1 candidate seal encrypt + 1 candidate self-verify decrypt, B2).
   358	        // Payload GCM is 1 on every outcome EXCEPT a SUCCESSFUL create, which does 2 (seal + a self-verify
   359	        // open, G3) and also the one ~1 MiB outer GCM — both create-only persist residuals. The
   360	        // marker-present create FAILS CLOSED to the exact reject budget (1 payload GCM, no outer), so it is
   361	        // indistinguishable from an ordinary wrong password.
   362	        fun measure(outcome: String, prep: (File) -> Unit, call: (VaultImageStore) -> Unit) {
   363	            val dir = tmp.newFolder()
   364	            prep(dir)
   365	            val counting = CountingOps(realOps)
   366	            val counter = CountingDeriver(fast)
   367	            val s = VaultImageStore(dir, counting, cipher, counter.deriver)
   368	            s.open()
   369	            counting.reset(); counter.calls = 0 // measure ONLY the attempt
   370	            call(s)
   371	            assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
   372	            // 4 sweep unwraps + 1 candidate seal encrypt + 1 candidate self-verify decrypt = 6 (B2).
   373	            assertEquals("$outcome: 6 wrapped-key GCM (4 unwrap + 1 seal + 1 self-verify)", 6, counting.wrappedOps)
   374	            // A successful create seals genesis AND self-verifies it (G3) = 2; every other outcome = 1.
   375	            val expectedPayload = if (outcome == "create") 2 else 1
   376	            assertEquals("$outcome: payload GCM (create seals+verifies=2, else 1)", expectedPayload, counting.payloadOps)
   377	            val expectedOuter = if (outcome == "create") 1 else 0
   378	            assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
   379	        }
   380	        // Setup uses the real deriver-injected store; but prep must seal with the SAME `fast` deriver so
   381	        // matches work when the measured store re-derives. Build vaults with a helper store.
   382	        val vaultContent = "content".toByteArray(Charsets.UTF_8)
   383	        measure("unlock",
   384	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
   385	            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
   386	        measure("reject",
   387	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
   388	            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
   389	        measure("create",
   390	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
   391	            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
   392	        measure("burn",
   393	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() }; armBurnSlot(d, "burn-me") },
   394	            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
   395	        // B1 fail-closed: a create attempt while a delete marker is present must have the SAME budget as an
   396	        // ordinary reject (5 Argon2id + 1 payload GCM + 6 wrapped + NO outer GCM) — no timing side channel
   397	        // distinguishes "creation refused because a delete is pending" from a wrong password.
   398	        measure("marker-reject",
   399	            prep = { d -> store(d).also { it.create("passA", vaultContent); it.markDeleteIntent(); it.close() } },
   400	            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
   401	    }
   402	
   403	    // ─────────────────────────── legacy (v2) image handling ───────────────────────────
   404	
   405	    @Test
   406	    fun v2Image_open_throwsLegacyImage_notCorruptImage() {
   407	        val dir = tmp.newFolder()
   408	        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
   409	        val inner = decodeOnDiskInner(dir)
   410	        inner[0] = LEGACY_IMAGE_VERSION.toByte() // downgrade the version byte to v2
78:    private val genesis = "genesis-empty-state".toByteArray(Charsets.UTF_8)
136: * 256 KiB payload GCM + one tiny wrapped-key GCM. A slot match (0..3) ALWAYS wins over [create]; a
185:        openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)   // (3) the 1×256KiB GCM
197:      val sealedGenesis = sealPayload(candKey, genesisPayload, ops)             // (3) the 1×256KiB GCM
210:      val throwaway = sealPayload(candKey, EMPTY, ops)      // (3) the 1×256KiB GCM — LOAD-BEARING filler
252:ordinary failed unlock: **5 Argon2id + 1×256 KiB GCM + 1 tiny GCM**, invariant across outcome and across
264:5 Argon2id + one 256 KiB payload GCM; the wipe (burn) and the unlock (vault) are both *post-outcome*
276:**Sole residual (unchanged by any of this):** Created persists synchronously — ~1 MiB outer-GCM +
278:any creation model; not introduced or worsened by triple-entry or by burn. Same class as the documented
279:payload-open asymmetry. **Lever if ever wanted:** gate every unlock/reject on a synchronous throwaway
333:   EXACTLY one 256 KiB GCM + one 60 B GCM for EACH of {unlock(1–3), **burn(slot 0)**, create, reject}.

codex
# Findings report

## Finding 1 — Create-only payload GCM incorrectly described as non-distinguishing

**SEVERITY:** Low  
**FILE / FUNCTION:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 610–628  
**DEFECT MECHANISM:** The KDoc correctly records the second create-only payload GCM, but calls it “not a per-outcome distinguisher.” It is outcome-dependent work: successful create performs an additional 256 KiB decrypt and content comparison; every other outcome does not. The later “TIMING RESIDUAL” wording partially acknowledges this but does not cure the contradictory claim.  
**FAILURE / ATTACK SCENARIO:** A physical observer with high-resolution timing or power/performance traces can attribute the extra GCM work to successful creation, in addition to the already-accepted outer encryption and write signal. A future reviewer relying on the stated non-distinguisher property could incorrectly treat create and non-create post-KDF work as identical.

## Finding 2 — Spec §5 retains stale crypto-budget and residual prose

**SEVERITY:** Low  
**FILE / FUNCTION:** `/root/l00prite/pr1-attemptUnlockOrAdd-spec.md`, §5, lines 251–256 and 276–280; additional stale summary at line 333  
**DEFECT MECHANISM:** The corrected table at lines 243–249 is accurate, but adjacent prose still says every ritual attempt has “1×256 KiB GCM + 1 tiny GCM,” that attempt three merely performs the same work and then persists, and that the sole residual is unchanged outer encryption/write work. Actual successful creation performs two payload GCM operations and six wrapped-key GCM operations. Line 333 likewise retains the old “exactly one” payload and wrapped-key budget.  
**FAILURE / ATTACK SCENARIO:** Security analysis or PR-2 router work based on the prose rather than the table undercounts successful-create work and wrapped-key operations, producing an incorrect timing/observability model.

# Binding verification results

## 1. Content comparison — Clean

At `VaultImageStore.kt:745–752`, `openPayload` must return plaintext and `MessageDigest.isEqual(verifyPt, genesisPayload)` compares its contents, not merely decrypt success.

`sealPayload` writes the original `content.size` into the four-byte prefix (`VaultPayload.kt:82–91`). `openPayload` decrypts and `unpad` returns exactly that declared content range (`VaultPayload.kt:62–73, 95–103`). A correct round trip therefore gives `verifyPt.size == genesisPayload.size`, selecting the equal-length comparison path.

`MisSealingPayloadOps` (`AttemptUnlockOrAddTest.kt:527–543`) flips byte 4 while preserving the length prefix and valid AEAD authentication. With the non-empty 19-byte `genesis`, decryption and unpadding succeed to an equal-length but different plaintext. The test therefore reaches the mismatch check, not the null-open branch.

## 2. Throw before persistence — Clean

The verify completes at `VaultImageStore.kt:745–753`, before:

- `encodeImage` at line 757
- outer encryption with `activeDek` at line 760
- `atomicWrite` at line 763
- `canonical` advancement at line 766

It neither modifies nor wipes `activeDek`. On failure, no disk operation or canonical assignment has occurred. The outer catch at lines 791–798 cleans up and rethrows the original exception.

## 3. Wipe discipline — Clean

`verifyPt` is installed only after `openPayload` returns successfully and is immediately enclosed by `try/finally`; mismatch and successful comparison both wipe it at lines 747–752. If decryption/unpadding throws before assignment, `openPayload` wipes its padded plaintext in its own `finally`.

The outer catch wipes `candKey` on verify failure. Re-wiping an already-zeroed array is harmless; there is no deallocation or double-free semantic in Kotlin byte arrays. On successful creation the catch is not entered, so the key handed to `VaultOpen` remains live.

## 4. Non-create crypto parity — Clean

Source-derived totals are:

| Outcome | Argon2id | Wrapped-key GCM | Payload GCM |
|---|---:|---:|---:|
| Unlock | 5 | 6 | 1 open |
| Burn | 5 | 6 | 1 open/discard |
| Reject | 5 | 6 | 1 throwaway seal |
| Marker reject | 5 | 6 | 1 throwaway seal |
| Successful create | 5 | 6 | 2 seal/open |

The new verify is reachable only inside the markers-absent create branch. Marker rejection branches before it at lines 725–730 and exactly matches ordinary rejection’s payload budget.

## 5. New implementation defects — Clean

Both `openPayload` failure forms are handled: null becomes `IllegalStateException`; unexpected throws propagate through the cleanup catch. The KDoc accurately lists `IllegalStateException` for candidate self-verification failure. No production caller currently classifies it differently.

The local `verifyPt` does not require the F4 cleanup mirror because no instruction can execute after its successful allocation/return and before entry into its immediately following `try`. The delta does not alter canonical/DEK synchronization or durability ordering.

## 6. Test accuracy — Clean except Finding 2’s external spec prose

The parity test accurately asserts create payload count 2, all named non-create outcomes 1, wrapped-key count 6, Argon2id count 5, and outer encryption only for creation. Its method name and comments match those assertions. The new mismatch test accurately models self-consistent wrong content and verifies that disk bytes remain unchanged.

**Overall verdict: PASS with two Low-severity documentation-accuracy findings; no implementation security defect found in `296ebc6..8f4545d`.**
tokens used
52,465
# Findings report

## Finding 1 — Create-only payload GCM incorrectly described as non-distinguishing

**SEVERITY:** Low  
**FILE / FUNCTION:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 610–628  
**DEFECT MECHANISM:** The KDoc correctly records the second create-only payload GCM, but calls it “not a per-outcome distinguisher.” It is outcome-dependent work: successful create performs an additional 256 KiB decrypt and content comparison; every other outcome does not. The later “TIMING RESIDUAL” wording partially acknowledges this but does not cure the contradictory claim.  
**FAILURE / ATTACK SCENARIO:** A physical observer with high-resolution timing or power/performance traces can attribute the extra GCM work to successful creation, in addition to the already-accepted outer encryption and write signal. A future reviewer relying on the stated non-distinguisher property could incorrectly treat create and non-create post-KDF work as identical.

## Finding 2 — Spec §5 retains stale crypto-budget and residual prose

**SEVERITY:** Low  
**FILE / FUNCTION:** `/root/l00prite/pr1-attemptUnlockOrAdd-spec.md`, §5, lines 251–256 and 276–280; additional stale summary at line 333  
**DEFECT MECHANISM:** The corrected table at lines 243–249 is accurate, but adjacent prose still says every ritual attempt has “1×256 KiB GCM + 1 tiny GCM,” that attempt three merely performs the same work and then persists, and that the sole residual is unchanged outer encryption/write work. Actual successful creation performs two payload GCM operations and six wrapped-key GCM operations. Line 333 likewise retains the old “exactly one” payload and wrapped-key budget.  
**FAILURE / ATTACK SCENARIO:** Security analysis or PR-2 router work based on the prose rather than the table undercounts successful-create work and wrapped-key operations, producing an incorrect timing/observability model.

# Binding verification results

## 1. Content comparison — Clean

At `VaultImageStore.kt:745–752`, `openPayload` must return plaintext and `MessageDigest.isEqual(verifyPt, genesisPayload)` compares its contents, not merely decrypt success.

`sealPayload` writes the original `content.size` into the four-byte prefix (`VaultPayload.kt:82–91`). `openPayload` decrypts and `unpad` returns exactly that declared content range (`VaultPayload.kt:62–73, 95–103`). A correct round trip therefore gives `verifyPt.size == genesisPayload.size`, selecting the equal-length comparison path.

`MisSealingPayloadOps` (`AttemptUnlockOrAddTest.kt:527–543`) flips byte 4 while preserving the length prefix and valid AEAD authentication. With the non-empty 19-byte `genesis`, decryption and unpadding succeed to an equal-length but different plaintext. The test therefore reaches the mismatch check, not the null-open branch.

## 2. Throw before persistence — Clean

The verify completes at `VaultImageStore.kt:745–753`, before:

- `encodeImage` at line 757
- outer encryption with `activeDek` at line 760
- `atomicWrite` at line 763
- `canonical` advancement at line 766

It neither modifies nor wipes `activeDek`. On failure, no disk operation or canonical assignment has occurred. The outer catch at lines 791–798 cleans up and rethrows the original exception.

## 3. Wipe discipline — Clean

`verifyPt` is installed only after `openPayload` returns successfully and is immediately enclosed by `try/finally`; mismatch and successful comparison both wipe it at lines 747–752. If decryption/unpadding throws before assignment, `openPayload` wipes its padded plaintext in its own `finally`.

The outer catch wipes `candKey` on verify failure. Re-wiping an already-zeroed array is harmless; there is no deallocation or double-free semantic in Kotlin byte arrays. On successful creation the catch is not entered, so the key handed to `VaultOpen` remains live.

## 4. Non-create crypto parity — Clean

Source-derived totals are:

| Outcome | Argon2id | Wrapped-key GCM | Payload GCM |
|---|---:|---:|---:|
| Unlock | 5 | 6 | 1 open |
| Burn | 5 | 6 | 1 open/discard |
| Reject | 5 | 6 | 1 throwaway seal |
| Marker reject | 5 | 6 | 1 throwaway seal |
| Successful create | 5 | 6 | 2 seal/open |

The new verify is reachable only inside the markers-absent create branch. Marker rejection branches before it at lines 725–730 and exactly matches ordinary rejection’s payload budget.

## 5. New implementation defects — Clean

Both `openPayload` failure forms are handled: null becomes `IllegalStateException`; unexpected throws propagate through the cleanup catch. The KDoc accurately lists `IllegalStateException` for candidate self-verification failure. No production caller currently classifies it differently.

The local `verifyPt` does not require the F4 cleanup mirror because no instruction can execute after its successful allocation/return and before entry into its immediately following `try`. The delta does not alter canonical/DEK synchronization or durability ordering.

## 6. Test accuracy — Clean except Finding 2’s external spec prose

The parity test accurately asserts create payload count 2, all named non-create outcomes 1, wrapped-key count 6, Argon2id count 5, and outer encryption only for creation. Its method name and comments match those assertions. The new mismatch test accurately models self-consistent wrong content and verifies that disk bytes remain unchanged.

**Overall verdict: PASS with two Low-severity documentation-accuracy findings; no implementation security defect found in `296ebc6..8f4545d`.**
