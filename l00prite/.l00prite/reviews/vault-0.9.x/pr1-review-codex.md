OpenAI Codex v0.145.0
--------
workdir: /root/zitrone
model: gpt-5.6-sol
provider: openai
approval: never
sandbox: read-only
reasoning effort: none
reasoning summaries: none
session id: 019f9413-dca8-7ff1-80f1-c26d5726ab05
--------
user
You are an INDEPENDENT ADVERSARIAL SECURITY REVIEWER. Report findings only — do NOT propose or write fixes, do NOT edit any file.

## Product & threat model
Zitrone: a production Signal-Protocol end-to-end encrypted messenger shipping to the Play Store. This change adds a second plausible-deniability vault and a "Pucker Burn" duress credential. Assume an adversary with PHYSICAL DEVICE ACCESS and FORENSIC CAPABILITY, and assume CRASH / PROCESS-DEATH at ANY instruction. Treat this as guilty-until-proven: it is the FIRST new writer to the durable vault image since a 16-round hardening effort (rounds 13–16) on the account-delete state machine.

## What to review
Local git commit `321b358` on branch `feat/0.9.2-vault-slotb-pr1` in this repo (/root/zitrone). Start with:
- `git show 321b358 --stat` then `git show 321b358` for the full diff.
- Primary source (verify against THIS, do not trust the spec or summaries):
  - apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt — `attemptUnlockOrAdd`, `open`, `isLegacyImage`, `retireLegacyImage`, `readInnerVersionOrNull`, the `VaultImageException` hierarchy, `create`, `writeSealedPayload`, `destroy`, the marker methods (`markDeleteIntent`, `markServerDeleteConfirmed`, `clearDeleteIntent`, `clearBothMarkersDurably`, `deleteIntentPending`, `serverDeleteConfirmed`, `hasDeleteIntentMarker`).
  - apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt — `BURN_SLOT_INDEX`, `VAULT_SLOT_RANGE`, `randomVaultSlotIndex`, `createVaultSlots`, `addVaultSlot`, `tryPassphrase`, `sealSlot`.
  - apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt — `IMAGE_VERSION`, `LEGACY_IMAGE_VERSION`, `encodeImage`, `decodeImage`.
  - apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt — `isLegacyImage`, `createVaultAndPublish`, `hasVault`.
  - apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt — the legacy-image precompute LaunchedEffect and the `onUnlockPassphrase` failure handler (LegacyImage branch), boot Route derivation.
  - Test: apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt.
- Claimed intent (context only, do NOT trust): /root/l00prite/pr1-attemptUnlockOrAdd-spec.md and the top "0.9.2 REVISED" block of /root/l00prite/zitrone-vault-ledger.md.

## Mandatory focus items (binding — do not abbreviate any)

1. INVARIANT 6 — Build a FULL WRITER/READER enumeration of the two durable markers `vault.delete-intent` and `vault.delete-confirmed` AS THEY EXIST AFTER THIS CHANGE, including mid-write / post-crash states. `attemptUnlockOrAdd`'s Created branch clears BOTH markers (via `clearBothMarkersDurably`), which cancels a pending A-side delete-reconcile. The spec argues only intent-only markers realistically appear at a lock screen — DO NOT accept that argument. List every writer, every reader, and prove each reader's assumption holds for every possible writer state (including a crash between any two disk operations). This is the same reader-assumes-something-about-a-marker shape that produced the round-12 and round-15 P1 defects. Look specifically for: a second vault (B) created over an image that carries A's pending delete-intent or delete-confirmed; a crash between the marker clear and the image write; whether B can be auto-destroyed or A's account left undeleted.

2. DROPPED unlockImage RE-VERIFY — `attemptUnlockOrAdd`'s Created branch builds the returned `VaultOpen` directly from the freshly generated `candKey` + `genesisPayload`, with NO round-trip verify (unlike `create()`, which re-opens the fresh image via `unlockImage`). Justified by KDF-budget timing parity. Give an EXPLICIT VERDICT: are the remaining non-KDF checks sufficient to guarantee the persisted slot is actually openable with `candKey` before a live session is built over it? Consider a misbehaving `VaultSodiumOps`, a `sealSlot`/`sealPayload` that produces a wrong-size or unauthenticated blob, and whether a bad blob could be written to disk and handed to a session. If not sufficient, name the CHEAPEST verify that preserves the 5-Argon2id timing parity.

3. CORRUPT-PAYLOAD ASYMMETRY — Confirm the implementation: a corrupt/unreadable payload on a MATCHED VAULT slot throws `CorruptImage`; a corrupt payload on a matched SLOT 0 (burn) still returns `Burn`. Verify both are implemented as described (the burn-fires-on-damaged-marker behavior is deliberate and approved — verify, do not relitigate). Flag any path where a matched vault slot with a bad payload could instead be treated as no-match (and thus feed the create/ritual path) or crash.

4. TIMING PARITY — Verify the implementation ACTUALLY issues exactly 5 Argon2id derivations + exactly one 256 KiB payload GCM across all FOUR outcomes (unlock / burn / create / reject). NOTE a possible SPEC/TEST DISCREPANCY to resolve: the spec §5 table says "one 60-byte wrapped-key GCM per call," but the test asserts 5 wrapped-key GCM ops per call — determine which is correct by reading `tryPassphrase` + `sealSlot`, and whether the parity claim holds regardless. Also verify: armed-vs-unarmed slot 0 is genuinely timing-identical (no branch keyed on slot-0 being real vs filler before the outcome is observable); slot-0 exclusion from placement (`randomVaultSlotIndex`) leaks nothing through timing or I/O (whole-image rewrite on create); and the create-only persist work is the only residual.

5. §10.1 LEGACY-IMAGE FIX — Verify a v2 (`LEGACY_IMAGE_VERSION`) image can NEVER reach slot-0 (burn) interpretation under ANY path, EVEN IF the app-level routing (MainActivity/ZitroneApp) is wrong or absent. The claim is that `open()` throws `LegacyImage` before any slot is interpreted, structurally. Confirm that is actually true for `attemptUnlockOrAdd`, `unlock`, `unlockWithKey`, and any path reaching `tryPassphrase`. Separately, prove `isLegacyImage()` / `retireLegacyImage()` cannot be tricked into RETIRING (deleting) a CURRENT v3 image — check the re-prove logic and any TOCTOU between the version check and the unlink.

6. GENERAL NEW DEFECTS — Anything else this change introduces: key-material wipe gaps (candKey / unlock.vaultKey / opened payloads on every branch and every throw), use-after-wipe, canonical/dek desync, durability/atomicity regressions, lock-ordering (imageLock vs any VaultSession), the `randomBytes(4)`-uniqueness assumption behind `randomVaultSlotIndex`, integer/modulo issues, and the burn-UNAWARE `addVaultSlot`/`addVaultToImage` primitives being reachable.

## Output format
A structured findings report. For EACH finding: SEVERITY (Critical / High / Medium / Low / Info), exact FILE + FUNCTION (+ line if possible), the DEFECT MECHANISM, and a concrete FAILURE/ATTACK SCENARIO (inputs/crash-point → wrong outcome). If a focus item is clean, say so explicitly and state why. End with a one-line overall verdict. Report ONLY — no fixes, no code edits.

codex
I’ll inspect the commit and the post-change source as requested, then trace marker state transitions and crypto-operation counts. I’ll report findings only and leave the workspace untouched.
exec
/bin/bash -lc 'git show 321b358 --stat && git show --format=fuller 321b358' in /root/zitrone
 succeeded in 0ms:
commit 321b35819ea5b89e1d65464985a0de8c067c6a90
Author: jackofall1232 <jackofall1232@gmail.com>
Date:   Fri Jul 24 12:13:11 2026 +0000

    feat(vault): PR-1 — attemptUnlockOrAdd (second vault) + slot-0 burn reservation + v2 retire
    
    Fused, timing-parity passphrase op for the 0.9.2 second-vault router, burn-aware:
    
    - VaultImageStore.attemptUnlockOrAdd(passphrase, genesis, create): sealed
      UnlockOrAdd {Unlocked, Burn, Created, Rejected}. ALWAYS identical heavy crypto
      (SLOT_COUNT-slot sweep + 1 unconditional candidate seal = 5 Argon2id, + exactly
      one 256 KiB payload GCM + one 60 B wrapped GCM) regardless of outcome. A slot
      match wins over create; slot 0 -> Burn (app wipes; store writes nothing); no-match
      + create seals a new vault into a random VAULT-POOL slot (never slot 0) reusing the
      existing DEK, clearing stale delete markers first (mirrors create()); no-match +
      !create writes nothing. Reimplements create inline (one tryPassphrase, no
      unlockImage re-verify) to hold the KDF budget.
    - Slot 0 reserved for the Pucker Burn duress credential: BURN_SLOT_INDEX /
      VAULT_SLOT_RANGE / randomVaultSlotIndex (single source of truth); createVaultSlots
      now places the everyday vault in 1..SLOT_COUNT-1. addVaultSlot/addVaultToImage
      marked burn-UNAWARE (dormant primitives).
    - IMAGE_VERSION 2 -> 3 (placement-semantics change): open() maps a v2 inner image to
      the new VaultImageException.LegacyImage (distinct from CorruptImage) BEFORE any slot
      is interpreted, so a 0.9.1 image with a vault at slot 0 can never be misread as a
      burn wipe. isLegacyImage() peek + retireLegacyImage() (re-proves v2; refuses to
      touch a current image). App routes a v2 image to fresh onboarding + retires it at
      create; safety does not depend on that routing (open() is the guarantee).
    
    Tests: AttemptUnlockOrAddTest (crypto-budget parity across all 4 outcomes; burn incl.
    corrupt-marker + wins-over-create; placement excludes slot 0; forced blind overwrite;
    marker clear; NotDurable-with-canonical-advanced; v2 LegacyImage/isLegacy/retire).
    Full app unit suite + assembleDebug + assembleRelease green.
    
    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01N81mnevbUZTv66x1impLU5

 .../src/main/java/com/zitrone/app/MainActivity.kt  |  28 ++
 .../src/main/java/com/zitrone/app/ZitroneApp.kt    |  14 +
 .../com/zitrone/app/crypto/vault/VaultImage.kt     |  20 +-
 .../zitrone/app/crypto/vault/VaultImageStore.kt    | 288 +++++++++++++-
 .../com/zitrone/app/crypto/vault/VaultSlots.kt     |  44 ++-
 .../java/com/zitrone/app/AttemptUnlockOrAddTest.kt | 438 +++++++++++++++++++++
 6 files changed, 824 insertions(+), 8 deletions(-)
commit 321b35819ea5b89e1d65464985a0de8c067c6a90
Author:     jackofall1232 <jackofall1232@gmail.com>
AuthorDate: Fri Jul 24 12:13:11 2026 +0000
Commit:     jackofall1232 <jackofall1232@gmail.com>
CommitDate: Fri Jul 24 12:13:11 2026 +0000

    feat(vault): PR-1 — attemptUnlockOrAdd (second vault) + slot-0 burn reservation + v2 retire
    
    Fused, timing-parity passphrase op for the 0.9.2 second-vault router, burn-aware:
    
    - VaultImageStore.attemptUnlockOrAdd(passphrase, genesis, create): sealed
      UnlockOrAdd {Unlocked, Burn, Created, Rejected}. ALWAYS identical heavy crypto
      (SLOT_COUNT-slot sweep + 1 unconditional candidate seal = 5 Argon2id, + exactly
      one 256 KiB payload GCM + one 60 B wrapped GCM) regardless of outcome. A slot
      match wins over create; slot 0 -> Burn (app wipes; store writes nothing); no-match
      + create seals a new vault into a random VAULT-POOL slot (never slot 0) reusing the
      existing DEK, clearing stale delete markers first (mirrors create()); no-match +
      !create writes nothing. Reimplements create inline (one tryPassphrase, no
      unlockImage re-verify) to hold the KDF budget.
    - Slot 0 reserved for the Pucker Burn duress credential: BURN_SLOT_INDEX /
      VAULT_SLOT_RANGE / randomVaultSlotIndex (single source of truth); createVaultSlots
      now places the everyday vault in 1..SLOT_COUNT-1. addVaultSlot/addVaultToImage
      marked burn-UNAWARE (dormant primitives).
    - IMAGE_VERSION 2 -> 3 (placement-semantics change): open() maps a v2 inner image to
      the new VaultImageException.LegacyImage (distinct from CorruptImage) BEFORE any slot
      is interpreted, so a 0.9.1 image with a vault at slot 0 can never be misread as a
      burn wipe. isLegacyImage() peek + retireLegacyImage() (re-proves v2; refuses to
      touch a current image). App routes a v2 image to fresh onboarding + retires it at
      create; safety does not depend on that routing (open() is the guarantee).
    
    Tests: AttemptUnlockOrAddTest (crypto-budget parity across all 4 outcomes; burn incl.
    corrupt-marker + wins-over-create; placement excludes slot 0; forced blind overwrite;
    marker clear; NotDurable-with-canonical-advanced; v2 LegacyImage/isLegacy/retire).
    Full app unit suite + assembleDebug + assembleRelease green.
    
    Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01N81mnevbUZTv66x1impLU5

diff --git a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
index cce7000..c31a262 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt
@@ -657,6 +657,25 @@ private fun ZitroneRoot(
         BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
             BiometricManager.BIOMETRIC_SUCCESS
 
+    // 0.9.2 upgrade safety: a PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
+    // reservation, so route it to fresh onboarding instead of the lock screen. Computed ONCE, off-main
+    // (a ~1 MiB outer decrypt, no Argon2id), only at a cold start with no live session. Safety does not
+    // depend on this — open() throws LegacyImage before any slot interpretation, and onUnlockPassphrase
+    // below also routes LegacyImage to onboarding as a backstop — this just avoids showing a dead lock
+    // screen. Treat a legacy image as "no usable vault" (vaultExists=false) so onboarding proceeds; the
+    // create there retires the old image.
+    LaunchedEffect(Unit) {
+        if (vaultExists && container.session.value == null) {
+            val legacy = withContext(Dispatchers.IO) {
+                runCatching { container.isLegacyImage() }.getOrDefault(false)
+            }
+            if (legacy && (route == Route.Splash || route == Route.Locked)) {
+                vaultExists = false
+                route = Route.Onboarding
+            }
+        }
+    }
+
     var identityFingerprint by remember { mutableStateOf<String?>(null) }
     LaunchedEffect(session) {
         val live = session
@@ -769,6 +788,15 @@ private fun ZitroneRoot(
                 onFailure = { e ->
                     when {
                         e is kotlinx.coroutines.CancellationException -> throw e
+                        e is com.zitrone.app.crypto.vault.VaultImageException.LegacyImage -> {
+                            // A PRIOR-format (v2 / 0.9.1) image is unsafe to unlock under the burn-slot
+                            // reservation; open() threw BEFORE any slot was interpreted (never a burn
+                            // wipe). Route to fresh onboarding (the create there retires the old image).
+                            // Backstop for the cold-start precompute above; no backoff bump (not a guess).
+                            vaultExists = false
+                            route = Route.Onboarding
+                            unlocking = false
+                        }
                         e is com.zitrone.app.crypto.vault.VaultImageException.CorruptImage ||
                             e is com.zitrone.app.crypto.vault.VaultImageException.MissingImage -> {
                             // A damaged/unreadable IMAGE is device state, NOT a passphrase guess —
diff --git a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
index 0acf84f..a7f7901 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
@@ -162,6 +162,15 @@ class AppContainer(private val app: Application) {
     /** Routing truth: a vault image is present → UNLOCK, absent → SETUP (onboarding). */
     fun hasVault(): Boolean = imageStore.exists()
 
+    /**
+     * Routing signal (0.9.2): a present image is the PRIOR (v2 / 0.9.1) format, which the burn-slot
+     * reservation makes unsafe to unlock — route to fresh onboarding instead of the lock screen. A
+     * cheap Argon2id-free peek (see [VaultImageStore.isLegacyImage]); call off-main. Safety does NOT
+     * depend on this routing: [VaultImageStore.open] throws [VaultImageException.LegacyImage] before any
+     * slot is interpreted, so a v2 image can never be misread as a burn wipe even if it reaches unlock.
+     */
+    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
+
     /**
      * Routing truth OVERRIDING [hasVault]: the SERVER account is CONFIRMED gone and the local
      * vault destroy is owed ([VaultImageStore.serverDeleteConfirmed]). The only valid route is
@@ -301,6 +310,11 @@ class AppContainer(private val app: Application) {
      * retry (or re-derive [hasVault] and route to unlock) — creation NEVER bricks.
      */
     suspend fun createVaultAndPublish(passphrase: String): Boolean = withContext(Dispatchers.Default) {
+        // A prior-format (v2 / 0.9.1) image is RETIRED here, on the deliberate onboarding action, so a
+        // fresh v3 vault can be created (create() requires no existing image). retireLegacyImage()
+        // re-proves the image is v2 and refuses to touch a valid current-version vault, so this is a
+        // no-op unless an actual legacy image is present. Not silent: it happens only on create.
+        if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()
         val initial = VaultStateCodec.encode(VaultState.empty())
         val open = try {
             imageStore.create(passphrase, initial)
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt
index a678bba..323232a 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt
@@ -19,8 +19,24 @@ package com.zitrone.app.crypto.vault
  * store, unlock flow, or persistence backend — that is a later phase.
  */
 
-/** On-disk image format version. Mirrors storage.ts IMAGE_VERSION. */
-const val IMAGE_VERSION: Int = 2
+/**
+ * On-disk image format version.
+ *
+ * **v3 (0.9.2):** slot 0 is reserved for the Pucker Burn credential and vaults live in
+ * slots 1..SLOT_COUNT-1 (see [BURN_SLOT_INDEX]). The BYTE layout is unchanged from v2 —
+ * only the placement CONVENTION changed — but the version is bumped anyway because the
+ * change is SAFETY-CRITICAL to distinguish: a v2 image (0.9.1) could hold the everyday
+ * vault at slot 0, which under v3's rules would be read as a BURN match and WIPE on the
+ * user's own correct passphrase. So [VaultImageStore.open] treats a v2 inner image as a
+ * distinct [VaultImageException.LegacyImage] (route to fresh onboarding + retire), NEVER
+ * as an unlockable image and NEVER slot-interpreted. v2 had no reserved slot (vaults at
+ * any index 0..SLOT_COUNT-1). Any FUTURE bump must add its migration/retire branch in
+ * [VaultImageStore.open] BEFORE changing this constant.
+ */
+const val IMAGE_VERSION: Int = 3
+
+/** The immediately-prior format ([VaultImageStore] retires it to fresh onboarding). */
+const val LEGACY_IMAGE_VERSION: Int = 2
 
 private const val HEADER_BYTES: Int = 1
 private const val SLOT_ENTRY_BYTES: Int = SALT_BYTES + WRAPPED_KEY_BYTES
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 37b9c0d..e6c90a0 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -63,6 +63,23 @@ sealed class VaultImageException(message: String) : Exception(message) {
      */
     class CorruptImage : VaultImageException("vault image is unreadable")
 
+    /**
+     * The image is present, the outer layer authenticated, and the inner image is a
+     * VALID but PRIOR format version ([LEGACY_IMAGE_VERSION] = v2, the 0.9.1 format).
+     * This is DISTINCT from [CorruptImage] on purpose and is SAFETY-CRITICAL: a v2 image
+     * could hold the everyday vault at slot 0, which v3's burn-slot reservation would
+     * misread as a burn credential and WIPE on the user's own correct passphrase. So a v2
+     * image is NEVER unlocked, NEVER slot-interpreted, and NEVER auto-destroyed at boot —
+     * [open] throws this before any slot material is used, the caller routes to fresh
+     * onboarding, and the retirement of the old file happens only on the deliberate
+     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
+     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
+     * recreate). 0.9.1 was fresh-install-only with no real users, so the blast radius is
+     * test devices — but "we happened to have no users" is not a safety property, so this
+     * fail-closed distinction ships regardless.
+     */
+    class LegacyImage : VaultImageException("vault image is a prior, retired format")
+
     /**
      * A payload write's bytes ARE on disk (the atomic rename — the commit point —
      * landed and its content was fsynced), but the directory-entry fsync that would
@@ -121,6 +138,29 @@ internal const val OUTER_IMAGE_BYTES: Int = IMAGE_BYTES + NONCE_BYTES + AEAD_TAG
  */
 internal enum class DirSyncResult { DURABLE, NOT_DURABLE }
 
+/**
+ * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
+ * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
+ * the CALLER learns only which of the four happened, never which slot or how many exist.
+ */
+sealed interface UnlockOrAdd {
+    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
+    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
+
+    /**
+     * Slot 0 ([BURN_SLOT_INDEX]) matched — the Pucker Burn duress credential was entered.
+     * The APP performs the wipe (a sibling feature); the store performs NO wipe here and
+     * exposes nothing about the burn slot's contents or arm-state.
+     */
+    data object Burn : UnlockOrAdd
+
+    /** No slot matched AND create was requested — a new vault was created + persisted durably. */
+    data class Created(val open: VaultOpen) : UnlockOrAdd
+
+    /** No slot matched AND create was not requested — an indistinguishable wrong passphrase. */
+    data object Rejected : UnlockOrAdd
+}
+
 /**
  * The device-level storage layer for the plausible-deniability vault image. Owns
  * the on-disk canonical image and the envelope that protects it at rest; nothing
@@ -224,6 +264,17 @@ class VaultImageStore internal constructor(
     /** True when a vault image is present on disk (`vault.bin`). */
     fun exists(): Boolean = imageLock.withLock { binFile.exists() }
 
+    /**
+     * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
+     * signal to send a 0.9.1 install to fresh onboarding instead of the lock screen (see
+     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
+     * the outer layer and checks the inner version byte only. Returns false for a current-version image,
+     * a missing image, or anything unreadable (a corrupt image is NOT "legacy" — it routes through the
+     * normal unlock → [CorruptImage] escalation, never to a retire). Does NOT alter store state.
+     */
+    fun isLegacyImage(): Boolean =
+        imageLock.withLock { readInnerVersionOrNull() == LEGACY_IMAGE_VERSION }
+
     /**
      * Read `vault.bin` + `vault.dek`, unwrap the DEK, decrypt the outer layer, and
      * hold the validated inner image as [canonical]. A leftover `.tmp` from an
@@ -317,10 +368,19 @@ class VaultImageStore internal constructor(
                     inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD)
                         ?: throw VaultImageException.CorruptImage()
                     if (inner.size != IMAGE_BYTES) throw VaultImageException.CorruptImage()
-                    // Validate the inner VERSION too, not just the size: an unknown version reads
-                    // as CorruptImage for THIS build. A future format bump MUST add its migration
-                    // path here BEFORE [IMAGE_VERSION] changes, or existing images stop opening.
-                    if (inner[0].toInt() and 0xff != IMAGE_VERSION) throw VaultImageException.CorruptImage()
+                    // Validate the inner VERSION too, not just the size. Three cases (order matters):
+                    //  - current [IMAGE_VERSION] → fall through, install as canonical.
+                    //  - [LEGACY_IMAGE_VERSION] (v2, the 0.9.1 format) → [VaultImageException.LegacyImage],
+                    //    thrown HERE before any slot is decoded/interpreted. SAFETY-CRITICAL: a v2 image
+                    //    may hold the everyday vault at slot 0, which v3 would misread as a burn wipe. The
+                    //    caller routes to fresh onboarding; retirement is deliberate ([retireLegacyImage]).
+                    //  - any OTHER version → [CorruptImage] (unknown/tampered; escalate, never recreate).
+                    // A future format bump MUST add its own branch here BEFORE changing [IMAGE_VERSION].
+                    val innerVersion = inner[0].toInt() and 0xff
+                    if (innerVersion != IMAGE_VERSION) {
+                        if (innerVersion == LEGACY_IMAGE_VERSION) throw VaultImageException.LegacyImage()
+                        throw VaultImageException.CorruptImage()
+                    }
                 } catch (t: Throwable) {
                     wipe(unwrapped)
                     throw t
@@ -532,6 +592,152 @@ class VaultImageStore internal constructor(
         }
     }
 
+    /**
+     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
+     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
+     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
+     * cases apart (the plausible-deniability + duress-credential timing contract):
+     *
+     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
+     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
+     *     (real vault-B material on create, pure timing filler otherwise);
+     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
+     *
+     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
+     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
+     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
+     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
+     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
+     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
+     * [UnlockOrAdd.Rejected] having written nothing.
+     *
+     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
+     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
+     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
+     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
+     *
+     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
+     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
+     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
+     * target, so duress protection survives even a full pool.
+     *
+     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
+     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
+     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
+     *
+     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
+     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
+     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
+     *
+     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
+     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
+     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
+     * create write is not confirmed durable.
+     */
+    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
+        imageLock.withLock {
+            val image = canonical ?: run { open(); canonical!! }
+            val activeDek = dek ?: throw IllegalStateException("vault image not open")
+            val decoded = decodeImage(image)
+
+            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
+            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
+
+            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
+            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
+            //     POOL (never slot 0) so a create can never clobber the burn credential.
+            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
+            val candSlotIndex = randomVaultSlotIndex(ops)
+            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
+
+            try {
+                return when {
+                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
+                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
+                        wipe(candKey)
+                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
+                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
+                        // duress credential must never be suppressed by a damaged marker (spec §6).
+                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
+                            .getOrNull()?.let { wipe(it) }
+                        wipe(unlock.vaultKey)
+                        UnlockOrAdd.Burn
+                    }
+
+                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
+                    unlock != null -> {
+                        wipe(candKey)
+                        val pt = try {
+                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
+                        } catch (t: Throwable) {
+                            wipe(unlock.vaultKey)
+                            throw VaultImageException.CorruptImage()
+                        }
+                        if (pt == null) {
+                            wipe(unlock.vaultKey)
+                            throw VaultImageException.CorruptImage()
+                        }
+                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
+                    }
+
+                    // ── CREATE a new vault into a vault-pool slot. ──
+                    create -> {
+                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
+                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
+                        val markersConfirmedAbsent =
+                            Files.notExists(deleteIntentFile.toPath()) &&
+                                Files.notExists(serverDeletedFile.toPath())
+                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
+                            throw VaultImageException.NotDurable()
+                        }
+                        // The 1×256 KiB payload GCM for this branch.
+                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
+                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
+                        val newPayloads =
+                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
+                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
+                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
+                        // unreachable by construction; the dek is already durable on disk from create().
+                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
+                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
+                        // rename landed, the result reporting the rename's durability.
+                        val sync = atomicWrite(binFile, outer)
+                        // Rename committed → advance canonical BEFORE the durability check so a later
+                        // splice/attempt never works from stale state even on the NotDurable throw.
+                        canonical = newInner
+                        if (sync != DirSyncResult.DURABLE) {
+                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
+                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
+                            // canonical, so a later single entry of its passphrase unlocks it via the
+                            // match path (no write needed) — or, if the rename did not survive a crash, it
+                            // is simply absent and re-creatable.
+                            wipe(candKey)
+                            throw VaultImageException.NotDurable()
+                        }
+                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
+                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
+                    }
+
+                    // ── REJECT — no match, no create. Nothing written. ──
+                    else -> {
+                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
+                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
+                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
+                        wipe(candKey)
+                        wipe(throwaway)
+                        UnlockOrAdd.Rejected
+                    }
+                }
+            } catch (t: Throwable) {
+                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
+                // (return) path this is not reached; on every other path candKey was already wiped, and a
+                // re-wipe of zeroed bytes is a no-op.
+                wipe(candKey)
+                throw t
+            }
+        }
+    }
+
     /**
      * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
      * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
@@ -602,6 +808,80 @@ class VaultImageStore internal constructor(
         }
     }
 
+    /**
+     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
+     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
+     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
+     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
+     * boot).
+     *
+     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
+     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
+     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
+     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
+     * release the single-instance registration.
+     *
+     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
+     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
+     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
+     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
+     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
+     */
+    fun retireLegacyImage() {
+        imageLock.withLock {
+            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
+            val version = readInnerVersionOrNull()
+            check(version == LEGACY_IMAGE_VERSION) {
+                "retireLegacyImage refused: not a legacy image (inner version=$version)"
+            }
+            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
+            dek?.let { wipe(it) }
+            dek = null
+            canonical = null
+            binFile.delete()
+            dekFile.delete()
+            deleteLeftoverTmp(binFile)
+            deleteLeftoverTmp(dekFile)
+            unregister()
+            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
+            if (binFile.exists() || dekFile.exists() ||
+                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
+            ) {
+                throw VaultImageException.DestroyFailed()
+            }
+            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
+                throw VaultImageException.DestroyFailed()
+            }
+        }
+    }
+
+    /**
+     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
+     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
+     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
+     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
+     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
+     */
+    private fun readInnerVersionOrNull(): Int? {
+        if (!binFile.exists() || !dekFile.exists()) return null
+        return try {
+            val dekBlob = dekFile.readBytes()
+            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
+            val binBytes = binFile.readBytes()
+            if (binBytes.size != OUTER_IMAGE_BYTES) return null
+            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
+            try {
+                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
+                if (inner.size != IMAGE_BYTES) return null
+                inner[0].toInt() and 0xff
+            } finally {
+                wipe(unwrapped)
+            }
+        } catch (t: Throwable) {
+            null
+        }
+    }
+
     /**
      * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
      * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
index 1765529..1878042 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
@@ -19,6 +19,36 @@ class CreatedVault(
     val slotIndex: Int,
 )
 
+/**
+ * Slot 0 is RESERVED for the Pucker Burn duress credential (0.9.2). It is sealed
+ * byte-identically to any vault slot — same Argon2id, same structure, same timing —
+ * so an examiner cannot tell from structure whether it is armed; only a MATCH on it
+ * triggers a wipe (handled by the store/app), never an unlock. Arm-state is stored
+ * NOWHERE: "armed" simply means a passphrase can match slot 0, exactly what
+ * [tryPassphrase] already tests, so an unarmed slot 0 is uniformly-random filler,
+ * indistinguishable from a real one.
+ *
+ * The reservation is a placement-only convention (the byte format is unchanged): no
+ * everyday vault and no created vault ever lands here, so vault creation can never
+ * clobber the burn credential. This is an ACCEPTED, documented disclosure — it reveals
+ * only that a burn FEATURE exists (public), never how many vaults slots 1..N-1 hold.
+ */
+const val BURN_SLOT_INDEX: Int = 0
+
+/** The vault pool — slots that may hold a real vault. Slot 0 (burn) is excluded. */
+val VAULT_SLOT_RANGE: IntRange = (BURN_SLOT_INDEX + 1) until SLOT_COUNT
+
+/**
+ * A uniformly-random index into the VAULT pool (never [BURN_SLOT_INDEX]). The SINGLE
+ * source of truth for slot-0 reservation, used by BOTH the everyday-vault placement
+ * ([createVaultSlots]) and blind second-vault creation
+ * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
+ * (plus one integer add), so it carries no timing/I-O signature distinct from ordinary
+ * placement.
+ */
+fun randomVaultSlotIndex(ops: VaultSodiumOps): Int =
+    VAULT_SLOT_RANGE.first + randomIndex(VAULT_SLOT_RANGE.count(), ops)
+
 /**
  * A filler slot: a random salt and random bytes the exact length of a real
  * wrapped key. Indistinguishable from an occupied slot. No passphrase will ever
@@ -49,7 +79,10 @@ fun sealSlot(
  * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
  * real vault sealed under [passphrase]. The rest are random filler. The returned
  * vaultKey is the random key the caller should use to encrypt the vault's data.
- * The real slot is placed at a CSPRNG-random index so position leaks nothing.
+ * The real slot is placed at a CSPRNG-random index IN THE VAULT POOL
+ * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
+ * stays reserved for the burn credential (see [BURN_SLOT_INDEX]). Slot 0 is left as
+ * filler on a fresh onboarding (unarmed burn), indistinguishable from any other slot.
  */
 fun createVaultSlots(
     passphrase: String,
@@ -62,7 +95,7 @@ fun createVaultSlots(
     try {
         val slots = ArrayList<KeySlot>(SLOT_COUNT)
         for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
-        val slotIndex = randomIndex(SLOT_COUNT, ops)
+        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
         slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
         return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
     } catch (t: Throwable) {
@@ -82,6 +115,13 @@ fun createVaultSlots(
  * an empty set reproduces the web's overwrite-tolerant behavior (storage.ts
  * createVault, the documented VeraCrypt outer-volume tradeoff); passing the
  * known-occupied indices avoids clobbering a live vault.
+ *
+ * ⚠️ BURN-UNAWARE (0.9.2): this primitive picks freely over ALL slots incl.
+ * [BURN_SLOT_INDEX], so it must NOT be wired into a creation path without excluding
+ * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
+ * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
+ * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
+ * as the web-mirrored primitive + tests only.
  */
 fun addVaultSlot(
     slots: List<KeySlot>,
diff --git a/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
new file mode 100644
index 0000000..522999b
--- /dev/null
+++ b/apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
@@ -0,0 +1,438 @@
+// Zitrone — Copyright (C) 2026 Zitrone contributors
+// Licensed under the GNU Affero General Public License v3.0 or later.
+// See the LICENSE file in the repository root for full license text.
+// SPDX-License-Identifier: AGPL-3.0-only
+
+package com.zitrone.app
+
+import com.goterl.lazysodium.SodiumJava
+import com.zitrone.app.crypto.vault.BURN_SLOT_INDEX
+import com.zitrone.app.crypto.vault.DeviceKeyCipher
+import com.zitrone.app.crypto.vault.DirSyncResult
+import com.zitrone.app.crypto.vault.IMAGE_BYTES
+import com.zitrone.app.crypto.vault.IMAGE_VERSION
+import com.zitrone.app.crypto.vault.KeyDeriver
+import com.zitrone.app.crypto.vault.LEGACY_IMAGE_VERSION
+import com.zitrone.app.crypto.vault.LibsodiumVaultOps
+import com.zitrone.app.crypto.vault.OUTER_IMAGE_BYTES
+import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
+import com.zitrone.app.crypto.vault.SLOT_COUNT
+import com.zitrone.app.crypto.vault.SLOT_PAYLOAD_BYTES
+import com.zitrone.app.crypto.vault.UnlockOrAdd
+import com.zitrone.app.crypto.vault.VAULT_IMAGE_OUTER_AD
+import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
+import com.zitrone.app.crypto.vault.VaultImage
+import com.zitrone.app.crypto.vault.VaultImageException
+import com.zitrone.app.crypto.vault.VaultImageStore
+import com.zitrone.app.crypto.vault.VaultSodiumOps
+import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
+import com.zitrone.app.crypto.vault.decodeImage
+import com.zitrone.app.crypto.vault.encodeImage
+import com.zitrone.app.crypto.vault.sealPayload
+import com.zitrone.app.crypto.vault.sealSlot
+import org.junit.Assert.assertArrayEquals
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertNull
+import org.junit.Assert.assertThrows
+import org.junit.Assert.assertTrue
+import org.junit.Rule
+import org.junit.Test
+import org.junit.rules.TemporaryFolder
+import java.io.File
+import java.security.MessageDigest
+import javax.crypto.Cipher
+import javax.crypto.spec.GCMParameterSpec
+import javax.crypto.spec.SecretKeySpec
+
+/**
+ * PR-1 tests for [VaultImageStore.attemptUnlockOrAdd] (0.9.2 second vault + Pucker Burn) and the
+ * v2→[VaultImageException.LegacyImage] read-path branch + [VaultImageStore.retireLegacyImage].
+ *
+ * Same conventions as [VaultImageStoreTest]: the AEAD + CSPRNG path is the REAL production byte path
+ * ([LibsodiumVaultOps] over SodiumJava); only Argon2id (→ a fast SHA-256 [fast] stand-in) and the
+ * Android Keystore device key (→ [FakeDeviceKeyCipher2]) are swapped for host testing.
+ */
+class AttemptUnlockOrAddTest {
+
+    @get:Rule
+    val tmp = TemporaryFolder()
+
+    private val realOps = LibsodiumVaultOps(SodiumJava())
+    private val cipher = FakeDeviceKeyCipher2()
+
+    /** Fast deterministic Argon2id stand-in: SHA-256(passphrase ‖ salt). */
+    private val fast: KeyDeriver = { passphrase, salt ->
+        val md = MessageDigest.getInstance("SHA-256")
+        md.update(passphrase.toByteArray(Charsets.UTF_8))
+        md.update(salt)
+        md.digest()
+    }
+
+    private fun store(dir: File, ops: VaultSodiumOps = realOps, dirSync: ((File?) -> DirSyncResult)? = null) =
+        if (dirSync == null) VaultImageStore(dir, ops, cipher, fast)
+        else VaultImageStore(dir, ops, cipher, fast, dirSync)
+
+    private val genesis = "genesis-empty-state".toByteArray(Charsets.UTF_8)
+
+    private fun bin(dir: File) = File(dir, "vault.bin")
+    private fun dek(dir: File) = File(dir, "vault.dek")
+
+    private fun decodeOnDiskInner(dir: File): ByteArray {
+        val d = cipher.unwrapDek(dek(dir).readBytes())!!
+        return realOps.aeadDecrypt(d, bin(dir).readBytes(), VAULT_IMAGE_OUTER_AD)!!
+    }
+
+    private fun rewriteInner(dir: File, inner: ByteArray) {
+        val d = cipher.unwrapDek(dek(dir).readBytes())!!
+        bin(dir).writeBytes(realOps.aeadEncrypt(d, inner, VAULT_IMAGE_OUTER_AD))
+    }
+
+    // ─────────────────────────────── functional ───────────────────────────────
+
+    @Test
+    fun match_returnsUnlocked_withThePayload() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        val content = "vault A keystore".toByteArray(Charsets.UTF_8)
+        s.create("passA", content)
+        val r = s.attemptUnlockOrAdd("passA", genesis, create = true) // create ignored — match wins
+        assertTrue(r is UnlockOrAdd.Unlocked)
+        assertArrayEquals(content, (r as UnlockOrAdd.Unlocked).open.payloadPlaintext)
+    }
+
+    @Test
+    fun create_true_noMatch_createsANewVault_reopenableFromDisk() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
+        assertTrue(r is UnlockOrAdd.Created)
+        assertArrayEquals(genesis, (r as UnlockOrAdd.Created).open.payloadPlaintext)
+        // Reopen fresh from disk: the new vault unlocks.
+        s.close()
+        val fresh = store(dir)
+        fresh.open()
+        assertTrue(fresh.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
+    }
+
+    @Test
+    fun reject_noMatch_createFalse_writesNothing() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        val before = bin(dir).readBytes()
+        val r = s.attemptUnlockOrAdd("nope", genesis, create = false)
+        assertEquals(UnlockOrAdd.Rejected, r)
+        assertArrayEquals("reject writes nothing — bin byte-identical", before, bin(dir).readBytes())
+    }
+
+    @Test
+    fun matchWinsOverCreate_existingVaultUnlocked_noWrite() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        val before = bin(dir).readBytes()
+        val r = s.attemptUnlockOrAdd("passA", genesis, create = true)
+        assertTrue(r is UnlockOrAdd.Unlocked)
+        assertArrayEquals(before, bin(dir).readBytes())
+    }
+
+    // ─────────────────────────────── burn (slot 0) ───────────────────────────────
+
+    /** Arm slot 0 on the on-disk image with [burnPass] (a real sealed slot + payload). */
+    private fun armBurnSlot(dir: File, burnPass: String) {
+        val inner = decodeOnDiskInner(dir)
+        val img = decodeImage(inner)
+        val burnKey = realOps.randomBytes(VAULT_KEY_BYTES)
+        val slots = img.slots.toMutableList().also {
+            it[BURN_SLOT_INDEX] = sealSlot(burnPass, burnKey, realOps, fast)
+        }
+        val payloads = img.payloads.toMutableList().also {
+            it[BURN_SLOT_INDEX] = sealPayload(burnKey, "burn-marker".toByteArray(Charsets.UTF_8), realOps)
+        }
+        rewriteInner(dir, encodeImage(VaultImage(slots, payloads)))
+    }
+
+    @Test
+    fun burnPassphrase_matchesSlot0_returnsBurn_writesNothing() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        s.close()
+        armBurnSlot(dir, "burn-me")
+        val fresh = store(dir)
+        fresh.open()
+        val before = bin(dir).readBytes()
+        // create=true too: burn wins over create.
+        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = true))
+        assertArrayEquals("burn writes nothing", before, bin(dir).readBytes())
+    }
+
+    @Test
+    fun unarmedSlot0_neverBurns() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8)) // slot 0 is filler
+        // No armed burn slot → an arbitrary non-matching passphrase rejects, never Burn.
+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("random", genesis, create = false))
+    }
+
+    @Test
+    fun corruptBurnPayload_stillFiresBurn() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        s.close()
+        armBurnSlot(dir, "burn-me")
+        // Corrupt slot 0's payload region: the wrapped-key MATCH still confirms the burn credential,
+        // so a damaged marker must NOT suppress the wipe.
+        val inner = decodeOnDiskInner(dir)
+        val img = decodeImage(inner)
+        val payloads = img.payloads.toMutableList().also {
+            it[BURN_SLOT_INDEX] = realOps.randomBytes(SLOT_PAYLOAD_BYTES) // random ≠ a valid sealed payload
+        }
+        rewriteInner(dir, encodeImage(VaultImage(img.slots, payloads)))
+        val fresh = store(dir)
+        fresh.open()
+        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = false))
+    }
+
+    @Test
+    fun corruptVaultPayload_onAMatchedVaultSlot_throwsCorruptImage() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        val open = s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        s.close()
+        // Corrupt the matched vault's own payload region.
+        val inner = decodeOnDiskInner(dir)
+        val img = decodeImage(inner)
+        val payloads = img.payloads.toMutableList().also {
+            it[open.slotIndex] = realOps.randomBytes(SLOT_PAYLOAD_BYTES)
+        }
+        rewriteInner(dir, encodeImage(VaultImage(img.slots, payloads)))
+        val fresh = store(dir)
+        fresh.open()
+        assertThrows(VaultImageException.CorruptImage::class.java) {
+            fresh.attemptUnlockOrAdd("passA", genesis, create = false)
+        }
+    }
+
+    // ─────────────────────────── placement / blind overwrite ───────────────────────────
+
+    @Test
+    fun createPlacement_neverLandsOnSlot0() {
+        // Over many creates, every new vault's slot ∈ 1..SLOT_COUNT-1 (slot 0 reserved), and the pool
+        // is actually reachable (covers >1 distinct index).
+        val seen = HashSet<Int>()
+        repeat(40) {
+            val dir = tmp.newFolder()
+            val s = store(dir)
+            s.create("A", "A".toByteArray(Charsets.UTF_8))
+            val r = s.attemptUnlockOrAdd("B$it", genesis, create = true) as UnlockOrAdd.Created
+            assertTrue("created slot must be in the vault pool 1..${SLOT_COUNT - 1}", r.open.slotIndex in 1 until SLOT_COUNT)
+            seen.add(r.open.slotIndex)
+            s.close()
+        }
+        assertFalse("slot 0 is never a create target", 0 in seen)
+        assertTrue("the vault pool is reachable", seen.size >= 2)
+    }
+
+    @Test
+    fun createCompanion_placesEverydayVaultInThePool_neverSlot0() {
+        repeat(30) {
+            val dir = tmp.newFolder()
+            val open = store(dir).create("A$it", "A".toByteArray(Charsets.UTF_8))
+            assertTrue("create() places A in 1..${SLOT_COUNT - 1}", open.slotIndex in 1 until SLOT_COUNT)
+        }
+    }
+
+    @Test
+    fun blindOverwrite_forcedOntoExistingVaultSlot_destroysIt() {
+        // Force EVERY placement to the same pool slot (slot 1): A is created there, then B is created
+        // there too, overwriting A — the accepted VeraCrypt-model ~1/3 collision, made deterministic.
+        val dir = tmp.newFolder()
+        val forced = ForceVaultIndexOps(realOps, targetPoolIndex = 1)
+        val s = store(dir, ops = forced)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        assertTrue(s.attemptUnlockOrAdd("passA", genesis, create = false) is UnlockOrAdd.Unlocked)
+        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
+        assertTrue(r is UnlockOrAdd.Created)
+        // A is gone (overwritten); B unlocks.
+        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passA", genesis, create = false))
+        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
+    }
+
+    // ─────────────────────────── delete-marker interaction (OQ3) ───────────────────────────
+
+    @Test
+    fun create_clearsAStaleDeleteIntentMarker() {
+        val dir = tmp.newFolder()
+        val s = store(dir)
+        s.create("passA", "A".toByteArray(Charsets.UTF_8))
+        s.markDeleteIntent()
+        assertTrue(File(dir, "vault.delete-intent").exists())
+        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = true) is UnlockOrAdd.Created)
+        assertFalse("create clears the stale intent marker", File(dir, "vault.delete-intent").exists())
+    }
+
+    // ─────────────────────────── durability ───────────────────────────
+
+    @Test
+    fun create_notDurable_throwsNotDurable_butCanonicalAdvanced() {
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        val s = store(dir, dirSync = { DirSyncResult.NOT_DURABLE })
+        s.open()
+        assertThrows(VaultImageException.NotDurable::class.java) {
+            s.attemptUnlockOrAdd("passB", genesis, create = true)
+        }
+        // canonical advanced (bytes are on disk): the new vault unlocks IN-MEMORY on the same store.
+        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
+    }
+
+    // ─────────────────────────── crypto-budget PARITY (load-bearing) ───────────────────────────
+
+    @Test
+    fun cryptoBudgetParity_5derivations_1payloadGcm_5wrappedGcm_acrossAllFourOutcomes() {
+        // Each outcome must issue IDENTICAL heavy crypto: 5 Argon2id (4-slot sweep + 1 candidate seal),
+        // exactly one 256 KiB payload GCM, and 5 wrapped-key GCM (4 unwrap attempts + 1 candidate seal).
+        // Only CREATE additionally does one ~1 MiB outer GCM (the documented persist residual).
+        fun measure(outcome: String, prep: (File) -> Unit, call: (VaultImageStore) -> Unit) {
+            val dir = tmp.newFolder()
+            prep(dir)
+            val counting = CountingOps(realOps)
+            val counter = CountingDeriver(fast)
+            val s = VaultImageStore(dir, counting, cipher, counter.deriver)
+            s.open()
+            counting.reset(); counter.calls = 0 // measure ONLY the attempt
+            call(s)
+            assertEquals("$outcome: 5 Argon2id (4 sweep + 1 candidate)", 5, counter.calls)
+            assertEquals("$outcome: exactly one 256 KiB payload GCM", 1, counting.payloadOps)
+            assertEquals("$outcome: 5 wrapped-key GCM (4 unwrap + 1 seal)", 5, counting.wrappedOps)
+            val expectedOuter = if (outcome == "create") 1 else 0
+            assertEquals("$outcome: outer GCM only on create", expectedOuter, counting.outerOps)
+        }
+        // Setup uses the real deriver-injected store; but prep must seal with the SAME `fast` deriver so
+        // matches work when the measured store re-derives. Build vaults with a helper store.
+        val vaultContent = "content".toByteArray(Charsets.UTF_8)
+        measure("unlock",
+            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
+            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
+        measure("reject",
+            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
+            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
+        measure("create",
+            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() } },
+            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
+        measure("burn",
+            prep = { d -> store(d).also { it.create("passA", vaultContent); it.close() }; armBurnSlot(d, "burn-me") },
+            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
+    }
+
+    // ─────────────────────────── legacy (v2) image handling ───────────────────────────
+
+    @Test
+    fun v2Image_open_throwsLegacyImage_notCorruptImage() {
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        val inner = decodeOnDiskInner(dir)
+        inner[0] = LEGACY_IMAGE_VERSION.toByte() // downgrade the version byte to v2
+        rewriteInner(dir, inner)
+        assertThrows(VaultImageException.LegacyImage::class.java) { store(dir).open() }
+    }
+
+    @Test
+    fun isLegacyImage_trueForV2_falseForCurrent() {
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        assertFalse("current version is not legacy", store(dir).isLegacyImage())
+        val inner = decodeOnDiskInner(dir)
+        inner[0] = LEGACY_IMAGE_VERSION.toByte()
+        rewriteInner(dir, inner)
+        assertTrue("v2 is legacy", store(dir).isLegacyImage())
+    }
+
+    @Test
+    fun retireLegacyImage_deletesV2_butRefusesToTouchCurrent() {
+        // Refuses (and deletes nothing) on a CURRENT-version image.
+        val dir = tmp.newFolder()
+        store(dir).also { it.create("passA", "A".toByteArray(Charsets.UTF_8)); it.close() }
+        assertThrows(IllegalStateException::class.java) { store(dir).retireLegacyImage() }
+        assertTrue("a current image survives a misrouted retire", bin(dir).exists() && dek(dir).exists())
+        // Retires a genuine v2 image.
+        val inner = decodeOnDiskInner(dir)
+        inner[0] = LEGACY_IMAGE_VERSION.toByte()
+        rewriteInner(dir, inner)
+        store(dir).retireLegacyImage()
+        assertFalse("v2 bin unlinked", bin(dir).exists())
+        assertFalse("v2 dek unlinked", dek(dir).exists())
+    }
+
+    // ─────────────────────────── test doubles ───────────────────────────
+
+    /** Fixed-key device cipher (host stand-in for the Keystore key). */
+    private class FakeDeviceKeyCipher2 : DeviceKeyCipher {
+        private val key = ByteArray(32) { (it * 7 + 1).toByte() }
+        private val g = LibsodiumVaultOps(SodiumJava())
+        override fun wrapDek(dek: ByteArray): ByteArray {
+            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
+            val c = Cipher.getInstance("AES/GCM/NoPadding")
+            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
+            val ct = c.doFinal(dek)
+            return nonce + ct
+        }
+        override fun unwrapDek(blob: ByteArray): ByteArray? = try {
+            val c = Cipher.getInstance("AES/GCM/NoPadding")
+            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
+            c.doFinal(blob, 12, blob.size - 12)
+        } catch (t: Throwable) { null }
+    }
+
+    /** Counts Argon2id (deriver) invocations. */
+    private class CountingDeriver(private val inner: KeyDeriver) {
+        var calls = 0
+        val deriver: KeyDeriver = { p, s -> calls++; inner(p, s) }
+    }
+
+    /** Classifies each AEAD op by size so the parity invariant is checkable. */
+    private class CountingOps(private val inner: VaultSodiumOps) : VaultSodiumOps {
+        var wrappedOps = 0 // 60-byte wrapped-key seal/unwrap
+        var payloadOps = 0 // 256 KiB payload seal/open
+        var outerOps = 0   // ~1 MiB outer image seal/open
+        fun reset() { wrappedOps = 0; payloadOps = 0; outerOps = 0 }
+        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
+        override fun randomBytes(length: Int) = inner.randomBytes(length)
+        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
+            when (plaintext.size) {
+                VAULT_KEY_BYTES -> wrappedOps++          // sealSlot wraps a 32-byte vault key
+                PAYLOAD_PLAINTEXT_BYTES -> payloadOps++  // sealPayload pads to full plaintext capacity
+                IMAGE_BYTES -> outerOps++                // outer image encrypt
+            }
+            return inner.aeadEncrypt(key, plaintext, associatedData)
+        }
+        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray): ByteArray? {
+            when (box.size) {
+                WRAPPED_KEY_BYTES -> wrappedOps++        // tryPassphrase unwraps each 60-byte slot
+                SLOT_PAYLOAD_BYTES -> payloadOps++       // openPayload
+                OUTER_IMAGE_BYTES -> outerOps++          // outer image decrypt
+            }
+            return inner.aeadDecrypt(key, box, associatedData)
+        }
+    }
+
+    /**
+     * Forces every vault-pool placement to [targetPoolIndex] by intercepting the single 4-byte CSPRNG
+     * draw `randomIndex` uses (unique to index selection — salts/nonces/keys are 16/12/32 bytes). Returns
+     * bytes encoding (targetPoolIndex-1) so `randomVaultSlotIndex` = 1 + ((targetPoolIndex-1) % (N-1)).
+     */
+    private class ForceVaultIndexOps(private val inner: VaultSodiumOps, targetPoolIndex: Int) : VaultSodiumOps {
+        private val forced = byteArrayOf(0, 0, 0, (targetPoolIndex - 1).toByte())
+        override fun argon2idDeriveKey(password: ByteArray, salt: ByteArray) = inner.argon2idDeriveKey(password, salt)
+        override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
+            inner.aeadEncrypt(key, plaintext, associatedData)
+        override fun aeadDecrypt(key: ByteArray, box: ByteArray, associatedData: ByteArray) =
+            inner.aeadDecrypt(key, box, associatedData)
+        override fun randomBytes(length: Int) = if (length == 4) forced.copyOf() else inner.randomBytes(length)
+    }
+}

codex
The commit output was truncated, so I’m switching to line-numbered reads of the exact post-commit files and repository-wide marker/call-site searches. The marker audit will include ordering and crash points, not just steady states.
exec
/bin/bash -lc "rg -n \"vault\\.delete-(intent|confirmed)|markDeleteIntent|markServerDeleteConfirmed|clearDeleteIntent|clearBothMarkersDurably|deleteIntentPending|serverDeleteConfirmed|hasDeleteIntentMarker|attemptUnlockOrAdd|retireLegacyImage|isLegacyImage|addVaultSlot|addVaultToImage|tryPassphrase|unlockWithKey|unlock\\(\" apps/android/app/src/main -g '*.kt'
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '1,920p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt" in /root/zitrone
 succeeded in 0ms:
apps/android/app/src/main/java/com/zitrone/app/data/BiometricUnlockStore.kt:42:        // "not enabled" here, never reach unlockWithKey's require(slotIndex in 0 until SLOT_COUNT)
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:168:     * cheap Argon2id-free peek (see [VaultImageStore.isLegacyImage]); call off-main. Safety does NOT
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:172:    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:176:     * vault destroy is owed ([VaultImageStore.serverDeleteConfirmed]). The only valid route is
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:181:    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:187:     * [VaultImageStore.deleteIntentPending].
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:189:    fun vaultDeleteIntentPending(): Boolean = imageStore.deleteIntentPending()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:192:    fun markVaultDeleteIntent() = imageStore.markDeleteIntent()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:195:    fun markServerDeleteConfirmed() = imageStore.markServerDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:198:    fun hasVaultDeleteIntentMarker() = imageStore.hasDeleteIntentMarker()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:262:        // The vault path always builds via unlock(prepared) with a resolved VaultOpen; a
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:264:        buildSession = { error("vault install builds sessions via unlock(prepared)") },
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:314:        // fresh v3 vault can be created (create() requires no existing image). retireLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:317:        if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:358:        val open = imageStore.unlock(passphrase) ?: return@withContext false
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:366:     * [VaultOpen]. The recovered vault key is wiped in `finally` (unlockWithKey holds its own
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:378:            val open = imageStore.unlockWithKey(vaultKey, wrap.slotIndex) ?: return@withContext false
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:466:        unlockController.unlock(
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:493:            persistDeleteIntent = imageStore::markDeleteIntent,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:494:            persistServerDeleteConfirmed = imageStore::markServerDeleteConfirmed,
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:495:            intentMarkerPresent = imageStore::hasDeleteIntentMarker,
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:633:                !container.hasVault() && !container.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:670:                runCatching { container.isLegacyImage() }.getOrDefault(false)
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:722:                    container.serverDeleteConfirmed() -> Route.DeleteIncomplete
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1031:                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1153:                        container.serverDeleteConfirmed() -> Route.DeleteIncomplete
apps/android/app/src/main/java/com/zitrone/app/UnlockController.kt:65:    fun unlock() = unlock(buildSession)
apps/android/app/src/main/java/com/zitrone/app/UnlockController.kt:78:    fun unlock(prepared: (CoroutineScope) -> S, onRefused: () -> Unit = {}) {
apps/android/app/src/main/java/com/zitrone/app/UnlockController.kt:144:        // callers are background threads and an unlock() racing this blocks on
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:75:     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:202: * the outer layer) and [unlockWithKey] does NO Argon2id at all (it opens one payload with
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:270:     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:275:    fun isLegacyImage(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:365:                // and keep it ONLY on the success path (mirrors tryPassphrase's discipline).
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:376:                    //    caller routes to fresh onboarding; retirement is deliberate ([retireLegacyImage]).
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:472:                if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:550:     * from [unlockImage] / [tryPassphrase]. A SUCCESSFUL unlock additionally opens one
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:556:    fun unlock(passphrase: String): VaultOpen? {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:573:    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:601:     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:637:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:644:            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:690:                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:830:    fun retireLegacyImage() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:835:                "retireLegacyImage refused: not a legacy image (inner version=$version)"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:862:     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:863:     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:923:     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:926:     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:933:    fun markDeleteIntent() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:937:    fun markServerDeleteConfirmed() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:948:    fun clearDeleteIntent() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:968:    private fun clearBothMarkersDurably(): Boolean {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1003:            // means the server account is confirmed gone, so write `vault.delete-confirmed`
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1008:            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1048:            if (!clearBothMarkersDurably()) {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1055:     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1060:    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1069:    fun deleteIntentPending(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1077:     * idempotent 404. Deliberately NOT `&& !confirmed` (unlike [deleteIntentPending]): a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1092:    fun hasDeleteIntentMarker(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1210:         * destruction — see [markDeleteIntent] / [deleteIntentPending]. Existence is the only signal.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1212:        const val DELETE_INTENT_FILE = "vault.delete-intent"
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1217:         * [markServerDeleteConfirmed] / [serverDeleteConfirmed]. Existence is the only signal.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1219:        const val SERVER_DELETED_FILE = "vault.delete-confirmed"
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:119:     * Persist the account-deletion INTENT durably (the `vault.delete-intent` marker) — the FIRST
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:127:     * Persist SERVER-DELETE-CONFIRMED durably (the `vault.delete-confirmed` marker) — written ONLY
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:132:     * [AppContainer.markServerDeleteConfirmed].
apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:1429:            // best-effort swallow). Until vault.delete-confirmed is durable, a crash would strand a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:28: * [tryPassphrase] already tests, so an unarmed slot 0 is uniformly-random filler,
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:45: * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:122: * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:123: * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:126:fun addVaultSlot(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:133:    // Reject a passphrase that already unlocks an existing vault: tryPassphrase
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:136:    tryPassphrase(passphrase, slots, ops, deriver)?.let {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:168:fun tryPassphrase(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:50:     * [tryPassphrase]) off the main thread.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:147:            // tryPassphrase.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSodiumOps.kt:177: * CPU-HEAVY — see [VaultSodiumOps.argon2idDeriveKey]. tryPassphrase invokes this
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt:26: *  2. Every passphrase attempt does identical work. tryPassphrase derives a key
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt:95: * is CPU-heavy; see [tryPassphrase] and [argon2idDeriver].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:136: * key's OWN keystore payload in place, unlike [addVaultToImage], which seals a
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:163: * Attempt [passphrase] against [image]. Runs [tryPassphrase] over every slot
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:188:    val unlock = tryPassphrase(passphrase, decoded.slots, ops, deriver) ?: return null
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:212: * [addVaultSlot].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:214:fun addVaultToImage(
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:223:    val added = addVaultSlot(decoded.slots, occupied, passphrase, ops, deriver)
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
   575	            require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
   576	            val image = canonical ?: run { open(); canonical!! }
   577	            val payload = decodeImage(image).payloads[slotIndex]
   578	            // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
   579	            // caller's input is never touched (it owns and wipes that itself).
   580	            val keyCopy = vaultKey.copyOf()
   581	            val plaintext = try {
   582	                openPayload(keyCopy, payload, ops)
   583	            } catch (t: Throwable) {
   584	                wipe(keyCopy)
   585	                throw t
   586	            }
   587	            if (plaintext == null) {
   588	                wipe(keyCopy)
   589	                return null
   590	            }
   591	            return VaultOpen(keyCopy, slotIndex, plaintext)
   592	        }
   593	    }
   594	
   595	    /**
   596	     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
   597	     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
   598	     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
   599	     * cases apart (the plausible-deniability + duress-credential timing contract):
   600	     *
   601	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
   602	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
   603	     *     (real vault-B material on create, pure timing filler otherwise);
   604	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
   605	     *
   606	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
   607	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
   608	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
   609	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
   610	     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
   611	     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
   612	     * [UnlockOrAdd.Rejected] having written nothing.
   613	     *
   614	     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
   615	     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
   616	     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
   617	     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
   618	     *
   619	     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
   620	     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
   621	     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
   622	     * target, so duress protection survives even a full pool.
   623	     *
   624	     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
   625	     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
   626	     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
   627	     *
   628	     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
   629	     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
   630	     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
   631	     *
   632	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
   633	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   634	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
   635	     * create write is not confirmed durable.
   636	     */
   637	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   638	        imageLock.withLock {
   639	            val image = canonical ?: run { open(); canonical!! }
   640	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   641	            val decoded = decodeImage(image)
   642	
   643	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   644	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   645	
   646	            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
   647	            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
   648	            //     POOL (never slot 0) so a create can never clobber the burn credential.
   649	            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
   650	            val candSlotIndex = randomVaultSlotIndex(ops)
   651	            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
   652	
   653	            try {
   654	                return when {
   655	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   656	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   657	                        wipe(candKey)
   658	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   659	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   660	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   661	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   662	                            .getOrNull()?.let { wipe(it) }
   663	                        wipe(unlock.vaultKey)
   664	                        UnlockOrAdd.Burn
   665	                    }
   666	
   667	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   668	                    unlock != null -> {
   669	                        wipe(candKey)
   670	                        val pt = try {
   671	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   672	                        } catch (t: Throwable) {
   673	                            wipe(unlock.vaultKey)
   674	                            throw VaultImageException.CorruptImage()
   675	                        }
   676	                        if (pt == null) {
   677	                            wipe(unlock.vaultKey)
   678	                            throw VaultImageException.CorruptImage()
   679	                        }
   680	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   681	                    }
   682	
   683	                    // ── CREATE a new vault into a vault-pool slot. ──
   684	                    create -> {
   685	                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
   686	                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
   687	                        val markersConfirmedAbsent =
   688	                            Files.notExists(deleteIntentFile.toPath()) &&
   689	                                Files.notExists(serverDeletedFile.toPath())
   690	                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
   691	                            throw VaultImageException.NotDurable()
   692	                        }
   693	                        // The 1×256 KiB payload GCM for this branch.
   694	                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   695	                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   696	                        val newPayloads =
   697	                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   698	                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   699	                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   700	                        // unreachable by construction; the dek is already durable on disk from create().
   701	                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   702	                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   703	                        // rename landed, the result reporting the rename's durability.
   704	                        val sync = atomicWrite(binFile, outer)
   705	                        // Rename committed → advance canonical BEFORE the durability check so a later
   706	                        // splice/attempt never works from stale state even on the NotDurable throw.
   707	                        canonical = newInner
   708	                        if (sync != DirSyncResult.DURABLE) {
   709	                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   710	                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   711	                            // canonical, so a later single entry of its passphrase unlocks it via the
   712	                            // match path (no write needed) — or, if the rename did not survive a crash, it
   713	                            // is simply absent and re-creatable.
   714	                            wipe(candKey)
   715	                            throw VaultImageException.NotDurable()
   716	                        }
   717	                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   718	                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   719	                    }
   720	
   721	                    // ── REJECT — no match, no create. Nothing written. ──
   722	                    else -> {
   723	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   724	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   725	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   726	                        wipe(candKey)
   727	                        wipe(throwaway)
   728	                        UnlockOrAdd.Rejected
   729	                    }
   730	                }
   731	            } catch (t: Throwable) {
   732	                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
   733	                // (return) path this is not reached; on every other path candKey was already wiped, and a
   734	                // re-wipe of zeroed bytes is a no-op.
   735	                wipe(candKey)
   736	                throw t
   737	            }
   738	        }
   739	    }
   740	
   741	    /**
   742	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   743	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   744	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   745	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   746	     *
   747	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   748	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   749	     * distinct because they leave DIFFERENT state:
   750	     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
   751	     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
   752	     *    the PREVIOUS state (the atomic rename replaces or not at all). Session stays dirty, no ack.
   753	     *  - POST-rename dir-fsync not confirmed ([DirSyncResult.NOT_DURABLE]): the new bytes ARE on
   754	     *    disk (the rename — the commit point — landed and its content was fsynced) but the rename's
   755	     *    own durability is unconfirmed. Only a confirmed successful directory fsync ([DirSyncResult.DURABLE])
   756	     *    is treated as durable; anything else — a real dir-fsync EIO OR a platform that could not open a
   757	     *    directory channel — fails CLOSED here. [canonical] is ADVANCED to match disk (so a later splice
   758	     *    never works from stale state — the write is on disk, just unconfirmed), and a
   759	     *    [VaultImageException.NotDurable] is thrown so the caller does NOT ack. The session stays dirty and
   760	     *    retries; a retry whose dir-fsync succeeds then acks.
   761	     *
   762	     * Never logs, and does identical work regardless of which slot is written.
   763	     */
   764	    fun writeSealedPayload(slotIndex: Int, sealedPayload: ByteArray) {
   765	        imageLock.withLock {
   766	            val current = canonical ?: throw IllegalStateException("vault image not open")
   767	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   768	            require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
   769	            // spliceImagePayload validates slotIndex and returns a NEW array — `current`
   770	            // is untouched, so nothing below can corrupt the live canonical.
   771	            val spliced = spliceImagePayload(current, slotIndex, sealedPayload)
   772	            val outer = ops.aeadEncrypt(activeDek, spliced, VAULT_IMAGE_OUTER_AD)
   773	            // atomicWrite throws ONLY pre-rename (nothing committed, canonical untouched); a
   774	            // RETURN means the rename landed, with the result telling the rename's durability.
   775	            val sync = atomicWrite(binFile, outer)
   776	            // The rename committed → in-memory canonical now matches disk. Advance it BEFORE the
   777	            // durability check so a later splice never works from stale state even on that throw.
   778	            canonical = spliced
   779	            if (sync != DirSyncResult.DURABLE) {
   780	                // On disk but durability NOT confirmed (real dir-fsync EIO, or a platform that
   781	                // could not open a dir channel): only a confirmed dir-fsync counts as durable, so
   782	                // fail CLOSED and throw — a flush-before-ack caller does NOT ack. canonical is
   783	                // already advanced (above), so the session stays dirty and retries; a retry that
   784	                // dir-fsyncs acks.
   785	                throw VaultImageException.NotDurable()
   786	            }
   787	        }
   788	    }
   789	
   790	    /**
   791	     * Wipe the DEK and drop the canonical image. Store open/close is device-level
   792	     * and independent of any vault's lock — the outer layer is not a slot's secret,
   793	     * so keeping the store open across vault locks is fine; this exists for tests /
   794	     * teardown. Idempotent.
   795	     *
   796	     * Also RELEASES this instance's single-instance registration (see class kdoc), so a
   797	     * new VaultImageStore may open the same directory afterwards. A real process restart
   798	     * ends the old process and drops the registration implicitly; a test simulating a
   799	     * restart within one JVM MUST close() the old instance first before constructing the
   800	     * next one on the same baseDir.
   801	     */
   802	    fun close() {
   803	        imageLock.withLock {
   804	            dek?.let { wipe(it) }
   805	            dek = null
   806	            canonical = null
   807	            unregister()
   808	        }
   809	    }
   810	
   811	    /**
   812	     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
   813	     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
   814	     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
   815	     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
   816	     * boot).
   817	     *
   818	     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
   819	     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
   820	     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
   821	     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
   822	     * release the single-instance registration.
   823	     *
   824	     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
   825	     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
   826	     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
   827	     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
   828	     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
   829	     */
   830	    fun retireLegacyImage() {
   831	        imageLock.withLock {
   832	            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
   833	            val version = readInnerVersionOrNull()
   834	            check(version == LEGACY_IMAGE_VERSION) {
   835	                "retireLegacyImage refused: not a legacy image (inner version=$version)"
   836	            }
   837	            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
   838	            dek?.let { wipe(it) }
   839	            dek = null
   840	            canonical = null
   841	            binFile.delete()
   842	            dekFile.delete()
   843	            deleteLeftoverTmp(binFile)
   844	            deleteLeftoverTmp(dekFile)
   845	            unregister()
   846	            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
   847	            if (binFile.exists() || dekFile.exists() ||
   848	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
   849	            ) {
   850	                throw VaultImageException.DestroyFailed()
   851	            }
   852	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   853	                throw VaultImageException.DestroyFailed()
   854	            }
   855	        }
   856	    }
   857	
   858	    /**
   859	     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
   860	     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
   861	     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
   862	     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
   863	     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
   864	     */
   865	    private fun readInnerVersionOrNull(): Int? {
   866	        if (!binFile.exists() || !dekFile.exists()) return null
   867	        return try {
   868	            val dekBlob = dekFile.readBytes()
   869	            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
   870	            val binBytes = binFile.readBytes()
   871	            if (binBytes.size != OUTER_IMAGE_BYTES) return null
   872	            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
   873	            try {
   874	                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
   875	                if (inner.size != IMAGE_BYTES) return null
   876	                inner[0].toInt() and 0xff
   877	            } finally {
   878	                wipe(unwrapped)
   879	            }
   880	        } catch (t: Throwable) {
   881	            null
   882	        }
   883	    }
   884	
   885	    /**
   886	     * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
   887	     * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
   888	     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
   889	     * interrupted-write `.tmp` leftovers), and RELEASE this instance's single-instance
   890	     * registration so a fresh [create] may re-open the directory in the same process.
   891	     *
   892	     * DISTINCT FROM [close] (load-bearing). [close] only wipes RAM and LEAVES the disk
   893	     * image intact — a lock, not a deletion: after close() [exists] stays true and the
   894	     * encrypted image (with the account's full crypto identity: keypair, ratchet records,
   895	     * roster) survives on disk, recoverable by a later unlock. destroy() is the ONLY path
   896	     * that removes the files, so after it [exists] is false and nothing is recoverable.
   897	     * Account deletion MUST use destroy(), never close()/reseal. When paired with a session
   898	     * teardown that reseals via VaultRuntime.close(), destroy() MUST run AFTER that reseal so
   899	     * no freshly-resealed image survives.
   900	     *
   901	     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
   902	     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
   903	     * are best-effort; even if one returns false the RAM state is still wiped and the
   904	     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
   905	     * never invokes a VaultSession, so it introduces no reverse lock nesting.
   906	     *
   907	     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
   908	     * filesystem error just as it does on an already-absent file, so its boolean cannot be
   909	     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
   910	     * either SURVIVES, the full-crypto image is still on disk, so it throws
   911	     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
   912	     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
   913	     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
   914	     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
   915	     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
   916	     */
   917	    /**
   918	     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
   919	     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
   920	     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
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
    79	 * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
    80	 * real vault sealed under [passphrase]. The rest are random filler. The returned
    81	 * vaultKey is the random key the caller should use to encrypt the vault's data.
    82	 * The real slot is placed at a CSPRNG-random index IN THE VAULT POOL
    83	 * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
    84	 * stays reserved for the burn credential (see [BURN_SLOT_INDEX]). Slot 0 is left as
    85	 * filler on a fresh onboarding (unarmed burn), indistinguishable from any other slot.
    86	 */
    87	fun createVaultSlots(
    88	    passphrase: String,
    89	    ops: VaultSodiumOps,
    90	    deriver: KeyDeriver = argon2idDeriver(ops),
    91	): CreatedVault {
    92	    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
    93	    // On SUCCESS the caller owns (and later wipes) vaultKey; on ANY failure path
    94	    // after generation, wipe it here so no live key is abandoned in heap.
    95	    try {
    96	        val slots = ArrayList<KeySlot>(SLOT_COUNT)
    97	        for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
    98	        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
    99	        slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
   100	        return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
   101	    } catch (t: Throwable) {
   102	        wipe(vaultKey)
   103	        throw t
   104	    }
   105	}
   106	
   107	/**
   108	 * Seal a second (or third…) vault into a currently-unoccupied slot. The new
   109	 * vault gets its own independent random vault key — vaults share no key
   110	 * material. The slot chosen is a random currently-unoccupied one so the layout
   111	 * still reveals nothing. Throws if every slot is occupied.
   112	 *
   113	 * [occupied] is supplied by the caller because the stored material deliberately
   114	 * cannot reveal which slots hold real vaults (that is the whole point). Passing
   115	 * an empty set reproduces the web's overwrite-tolerant behavior (storage.ts
   116	 * createVault, the documented VeraCrypt outer-volume tradeoff); passing the
   117	 * known-occupied indices avoids clobbering a live vault.
   118	 *
   119	 * ⚠️ BURN-UNAWARE (0.9.2): this primitive picks freely over ALL slots incl.
   120	 * [BURN_SLOT_INDEX], so it must NOT be wired into a creation path without excluding
   121	 * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
   122	 * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
   123	 * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
   124	 * as the web-mirrored primitive + tests only.
   125	 */
   126	fun addVaultSlot(
   127	    slots: List<KeySlot>,
   128	    occupied: Set<Int>,
   129	    passphrase: String,
   130	    ops: VaultSodiumOps,
   131	    deriver: KeyDeriver = argon2idDeriver(ops),
   132	): CreatedVault {
   133	    // Reject a passphrase that already unlocks an existing vault: tryPassphrase
   134	    // returns only the FIRST matching slot, so a second seal under the same
   135	    // passphrase would shadow one vault and silently make it unreachable.
   136	    tryPassphrase(passphrase, slots, ops, deriver)?.let {
   137	        wipe(it.vaultKey)
   138	        throw IllegalArgumentException("passphrase already unlocks an existing vault")
   139	    }
   140	    val free = ArrayList<Int>()
   141	    for (i in slots.indices) if (i !in occupied) free.add(i)
   142	    if (free.isEmpty()) throw IllegalStateException("no free key slots")
   143	    val slotIndex = free[randomIndex(free.size, ops)]
   144	    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
   145	    try {
   146	        val next = slots.toMutableList()
   147	        next[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
   148	        return CreatedVault(slots = next, vaultKey = vaultKey, slotIndex = slotIndex)
   149	    } catch (t: Throwable) {
   150	        wipe(vaultKey)
   151	        throw t
   152	    }
   153	}
   154	
   155	/**
   156	 * Attempt a passphrase against all slots. Returns the unlocked vault key, or
   157	 * null if no slot matched (indistinguishable from a wrong passphrase).
   158	 *
   159	 * Derive+attempt EVERY slot, never break, so wall-clock timing is identical
   160	 * whether a passphrase matches slot 0, slot N, or nothing — a break here is a
   161	 * plausible-deniability side-channel. The first match is recorded but the loop
   162	 * runs to completion regardless; any later match's vault key is wiped, and every
   163	 * derived master key is wiped whether it matched or not.
   164	 *
   165	 * CPU-HEAVY: SLOT_COUNT × Argon2id (64 MiB each) with the production deriver.
   166	 * Callers on a UI thread MUST run this off the main thread.
   167	 */
   168	fun tryPassphrase(
   169	    passphrase: String,
   170	    slots: List<KeySlot>,
   171	    ops: VaultSodiumOps,
   172	    deriver: KeyDeriver = argon2idDeriver(ops),
   173	): VaultUnlock? {
   174	    var found: VaultUnlock? = null
   175	    try {
   176	        for (i in slots.indices) {
   177	            val slot = slots[i]
   178	            val masterKey = deriver(passphrase, slot.salt)
   179	            try {
   180	                val vaultKey = ops.aeadDecrypt(masterKey, slot.wrapped, SLOT_AD)
   181	                if (vaultKey != null) {
   182	                    // Record the first match but DO NOT break — every slot is
   183	                    // always derived and tried.
   184	                    if (found == null) found = VaultUnlock(vaultKey, i) else wipe(vaultKey)
   185	                }
   186	            } finally {
   187	                wipe(masterKey)
   188	            }
   189	        }
   190	    } catch (t: Throwable) {
   191	        // A later derivation failing (e.g. OOM under memory pressure) must not
   192	        // abandon an already-matched vault key in heap — the caller never
   193	        // received it to wipe.
   194	        found?.let { wipe(it.vaultKey) }
   195	        throw t
   196	    }
   197	    return found
   198	}
   199	
   200	/** Overwrite key material in place. Call the moment a key is no longer needed. */
   201	fun wipe(bytes: ByteArray) {
   202	    bytes.fill(0)
   203	}
   204	
   205	/**
   206	 * Uniform random index in [0, n) drawn from the CSPRNG. Reads 4 CSPRNG bytes as
   207	 * a big-endian unsigned 32-bit value and reduces mod n (no meaningful modulo
   208	 * bias for the small n used here). Byte-for-byte the same construction as
   209	 * vault.ts randomIndex.
   210	 */
   211	fun randomIndex(n: Int, ops: VaultSodiumOps): Int {
   212	    val buf = ops.randomBytes(4)
   213	    val v = ((buf[0].toInt() and 0xff) shl 24) or
   214	        ((buf[1].toInt() and 0xff) shl 16) or
   215	        ((buf[2].toInt() and 0xff) shl 8) or
   216	        (buf[3].toInt() and 0xff)
   217	    val unsigned = v.toLong() and 0xffffffffL
   218	    return (unsigned % n).toInt()
   219	}
     1	// Zitrone — Copyright (C) 2026 Zitrone contributors
     2	// Licensed under the GNU Affero General Public License v3.0 or later.
     3	// See the LICENSE file in the repository root for full license text.
     4	// SPDX-License-Identifier: AGPL-3.0-only
     5	
     6	package com.zitrone.app.crypto.vault
     7	
     8	/**
     9	 * The fixed-size on-disk image — an exact Kotlin mirror of the image codec in
    10	 * apps/web/src/lib/storage.ts. Layout:
    11	 *
    12	 *   version(1) ‖ SLOT_COUNT × [salt(16) ‖ wrapped(60)] ‖ SLOT_COUNT × payload(SLOT_PAYLOAD_BYTES)
    13	 *
    14	 * The image is a compile-time-constant IMAGE_BYTES long no matter how many
    15	 * vaults are real — zero, one, or SLOT_COUNT. Neither the size, the structure,
    16	 * nor any byte of the stored material reveals the vault count.
    17	 *
    18	 * This is the isolated PRIMITIVE only. It is deliberately NOT wired into any
    19	 * store, unlock flow, or persistence backend — that is a later phase.
    20	 */
    21	
    22	/**
    23	 * On-disk image format version.
    24	 *
    25	 * **v3 (0.9.2):** slot 0 is reserved for the Pucker Burn credential and vaults live in
    26	 * slots 1..SLOT_COUNT-1 (see [BURN_SLOT_INDEX]). The BYTE layout is unchanged from v2 —
    27	 * only the placement CONVENTION changed — but the version is bumped anyway because the
    28	 * change is SAFETY-CRITICAL to distinguish: a v2 image (0.9.1) could hold the everyday
    29	 * vault at slot 0, which under v3's rules would be read as a BURN match and WIPE on the
    30	 * user's own correct passphrase. So [VaultImageStore.open] treats a v2 inner image as a
    31	 * distinct [VaultImageException.LegacyImage] (route to fresh onboarding + retire), NEVER
    32	 * as an unlockable image and NEVER slot-interpreted. v2 had no reserved slot (vaults at
    33	 * any index 0..SLOT_COUNT-1). Any FUTURE bump must add its migration/retire branch in
    34	 * [VaultImageStore.open] BEFORE changing this constant.
    35	 */
    36	const val IMAGE_VERSION: Int = 3
    37	
    38	/** The immediately-prior format ([VaultImageStore] retires it to fresh onboarding). */
    39	const val LEGACY_IMAGE_VERSION: Int = 2
    40	
    41	private const val HEADER_BYTES: Int = 1
    42	private const val SLOT_ENTRY_BYTES: Int = SALT_BYTES + WRAPPED_KEY_BYTES
    43	private const val SLOT_TABLE_BYTES: Int = SLOT_COUNT * SLOT_ENTRY_BYTES
    44	
    45	/** Total image size — constant regardless of how many vaults are real. */
    46	const val IMAGE_BYTES: Int = HEADER_BYTES + SLOT_TABLE_BYTES + SLOT_COUNT * SLOT_PAYLOAD_BYTES
    47	
    48	/** The image in structured form. payloads[i] belongs to slots[i]. */
    49	class VaultImage(
    50	    val slots: List<KeySlot>,
    51	    val payloads: List<ByteArray>,
    52	)
    53	
    54	/** Result of a successful [unlockImage]. slotIndex is for caller bookkeeping only. */
    55	class VaultOpen(
    56	    val vaultKey: ByteArray,
    57	    val slotIndex: Int,
    58	    val payloadPlaintext: ByteArray,
    59	)
    60	
    61	/** Serialize a structured image to its fixed-size byte form. */
    62	fun encodeImage(image: VaultImage): ByteArray {
    63	    require(image.slots.size == SLOT_COUNT && image.payloads.size == SLOT_COUNT) {
    64	        "vault image must have exactly SLOT_COUNT slots"
    65	    }
    66	    val out = ByteArray(IMAGE_BYTES)
    67	    out[0] = IMAGE_VERSION.toByte()
    68	    for (i in 0 until SLOT_COUNT) {
    69	        val slot = image.slots[i]
    70	        val payload = image.payloads[i]
    71	        require(payload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
    72	        val entryOffset = HEADER_BYTES + i * SLOT_ENTRY_BYTES
    73	        slot.salt.copyInto(out, entryOffset)
    74	        slot.wrapped.copyInto(out, entryOffset + SALT_BYTES)
    75	        payload.copyInto(out, HEADER_BYTES + SLOT_TABLE_BYTES + i * SLOT_PAYLOAD_BYTES)
    76	    }
    77	    return out
    78	}
    79	
    80	/** Parse a fixed-size image back into structured form. */
    81	fun decodeImage(bytes: ByteArray): VaultImage {
    82	    require(bytes.size == IMAGE_BYTES) { "not a vault image" }
    83	    require(bytes[0].toInt() and 0xff == IMAGE_VERSION) { "unsupported vault image version" }
    84	    val slots = ArrayList<KeySlot>(SLOT_COUNT)
    85	    val payloads = ArrayList<ByteArray>(SLOT_COUNT)
    86	    for (i in 0 until SLOT_COUNT) {
    87	        val entryOffset = HEADER_BYTES + i * SLOT_ENTRY_BYTES
    88	        slots.add(
    89	            KeySlot(
    90	                salt = bytes.copyOfRange(entryOffset, entryOffset + SALT_BYTES),
    91	                wrapped = bytes.copyOfRange(entryOffset + SALT_BYTES, entryOffset + SLOT_ENTRY_BYTES),
    92	            ),
    93	        )
    94	        val payloadOffset = HEADER_BYTES + SLOT_TABLE_BYTES + i * SLOT_PAYLOAD_BYTES
    95	        payloads.add(bytes.copyOfRange(payloadOffset, payloadOffset + SLOT_PAYLOAD_BYTES))
    96	    }
    97	    return VaultImage(slots, payloads)
    98	}
    99	
   100	/**
   101	 * Build a fresh image sealed under [passphrase]: SLOT_COUNT slots, exactly ONE
   102	 * real (at a random index), the rest random filler, and SLOT_COUNT payload
   103	 * regions — the real slot's payload sealing [payloadPlaintext], every other
   104	 * region a fresh random filler. The number of real slots leaves no on-disk
   105	 * trace, and the returned image is always IMAGE_BYTES long.
   106	 */
   107	fun createImage(
   108	    passphrase: String,
   109	    payloadPlaintext: ByteArray,
   110	    ops: VaultSodiumOps,
   111	    deriver: KeyDeriver = argon2idDeriver(ops),
   112	): ByteArray {
   113	    val created = createVaultSlots(passphrase, ops, deriver)
   114	    // The key is ephemeral here (the returned image holds the SEALED payload, not
   115	    // the raw key), so wipe it on every exit — including if randomPayload or
   116	    // encodeImage throws between generation and use.
   117	    try {
   118	        val payloads = ArrayList<ByteArray>(SLOT_COUNT)
   119	        for (i in 0 until SLOT_COUNT) payloads.add(randomPayload(ops))
   120	        payloads[created.slotIndex] = sealPayload(created.vaultKey, payloadPlaintext, ops)
   121	        return encodeImage(VaultImage(created.slots, payloads))
   122	    } finally {
   123	        wipe(created.vaultKey)
   124	    }
   125	}
   126	
   127	/**
   128	 * Replace ONE slot's payload region in [image] with an ALREADY-SEALED payload,
   129	 * re-encoding the fixed-size image with every other region (the header, the whole
   130	 * slot table, and every OTHER payload region) carried over byte-for-byte
   131	 * unchanged. The result is always the same constant [IMAGE_BYTES] length.
   132	 *
   133	 * This is the reseal splice the STORAGE LAYER (the vault image store, a later
   134	 * sub-phase) performs when a live session hands it a (slotIndex, sealedPayload)
   135	 * pair — the session itself no longer touches the image. It re-encrypts the vault
   136	 * key's OWN keystore payload in place, unlike [addVaultToImage], which seals a
   137	 * NEW vault under a new passphrase into a free slot. It is deliberately
   138	 * slot-agnostic and constant-length — it takes a caller-supplied [sealedPayload]
   139	 * of exactly [SLOT_PAYLOAD_BYTES] and does not know or reveal whether the slot is
   140	 * real or filler.
   141	 */
   142	internal fun spliceImagePayload(
   143	    image: ByteArray,
   144	    slotIndex: Int,
   145	    sealedPayload: ByteArray,
   146	): ByteArray {
   147	    require(image.size == IMAGE_BYTES) { "malformed vault image" }
   148	    require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
   149	    require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
   150	    // Only THIS slot's payload region changes on a reseal; the version byte, the
   151	    // whole slot table, and every other slot's payload are carried through
   152	    // byte-identical. Copy the image and overwrite just the target region in place
   153	    // — no decode + re-encode, so a hot reseal path does not allocate and parse the
   154	    // full (multi-hundred-KiB) image on every flush. The target offset mirrors
   155	    // encodeImage()'s payload layout exactly.
   156	    val out = image.copyOf()
   157	    val payloadOffset = HEADER_BYTES + SLOT_TABLE_BYTES + slotIndex * SLOT_PAYLOAD_BYTES
   158	    sealedPayload.copyInto(out, payloadOffset)
   159	    return out
   160	}
   161	
   162	/**
   163	 * Attempt [passphrase] against [image]. Runs [tryPassphrase] over every slot
   164	 * (no early exit — identical work regardless of which slot, if any, matches),
   165	 * then opens the matched slot's payload. Returns null when no slot matches (a
   166	 * wrong passphrase) or the matched payload is corrupt.
   167	 *
   168	 * CPU-HEAVY with the production deriver (SLOT_COUNT × 64 MiB Argon2id); the
   169	 * future integration layer MUST call this off the main thread.
   170	 *
   171	 * TIMING NOTE (deliberate, accepted): the SLOT_COUNT-way slot loop is
   172	 * content-independent, so it gives the required cross-slot parity (matching slot
   173	 * A, slot B, or nothing takes identical work). A SUCCESSFUL unlock additionally
   174	 * opens one fixed-size payload; a wrong passphrase does not. So success and
   175	 * failure are NOT equal-time — but this leaks nothing an observer doesn't
   176	 * already have: the app visibly unlocks (or doesn't) the instant it happens, so
   177	 * the payload-open duration reveals no extra bit. The web reference has the same
   178	 * property. The router (P1b) MUST NOT introduce a NEW timing branch that varies
   179	 * with which slot matched or whether a second vault exists.
   180	 */
   181	fun unlockImage(
   182	    passphrase: String,
   183	    image: ByteArray,
   184	    ops: VaultSodiumOps,
   185	    deriver: KeyDeriver = argon2idDeriver(ops),
   186	): VaultOpen? {
   187	    val decoded = decodeImage(image)
   188	    val unlock = tryPassphrase(passphrase, decoded.slots, ops, deriver) ?: return null
   189	    // On success the caller owns unlock.vaultKey; on ANY failure (payload returns
   190	    // null OR openPayload throws on corrupt padding/version) wipe it here.
   191	    val plaintext = try {
   192	        openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   193	    } catch (t: Throwable) {
   194	        wipe(unlock.vaultKey)
   195	        throw t
   196	    }
   197	    if (plaintext == null) {
   198	        wipe(unlock.vaultKey)
   199	        return null
   200	    }
   201	    return VaultOpen(unlock.vaultKey, unlock.slotIndex, plaintext)
   202	}
   203	
   204	/**
   205	 * Seal a second (or further) vault into [image] at a random currently-free slot,
   206	 * sealing [payloadPlaintext] into that slot's payload region. Every OTHER slot
   207	 * and payload region is carried over byte-for-byte unchanged. The result is a
   208	 * new image of the same constant IMAGE_BYTES length.
   209	 *
   210	 * [occupied] names the slots already holding real vaults the caller wishes to
   211	 * preserve — the stored image cannot reveal them (that is the point). See
   212	 * [addVaultSlot].
   213	 */
   214	fun addVaultToImage(
   215	    image: ByteArray,
   216	    occupied: Set<Int>,
   217	    passphrase: String,
   218	    payloadPlaintext: ByteArray,
   219	    ops: VaultSodiumOps,
   220	    deriver: KeyDeriver = argon2idDeriver(ops),
   221	): ByteArray {
   222	    val decoded = decodeImage(image)
   223	    val added = addVaultSlot(decoded.slots, occupied, passphrase, ops, deriver)
   224	    // Ephemeral key (the returned image holds the sealed payload) — wipe on every
   225	    // exit, incl. if the list copy or encodeImage throws.
   226	    try {
   227	        val payloads = decoded.payloads.toMutableList()
   228	        payloads[added.slotIndex] = sealPayload(added.vaultKey, payloadPlaintext, ops)
   229	        return encodeImage(VaultImage(added.slots, payloads))
   230	    } finally {
   231	        wipe(added.vaultKey)
   232	    }
   233	}

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '250,760p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '760,1120p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt | sed -n '1,230p'" in /root/zitrone
 succeeded in 0ms:
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
   575	            require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
   576	            val image = canonical ?: run { open(); canonical!! }
   577	            val payload = decodeImage(image).payloads[slotIndex]
   578	            // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
   579	            // caller's input is never touched (it owns and wipes that itself).
   580	            val keyCopy = vaultKey.copyOf()
   581	            val plaintext = try {
   582	                openPayload(keyCopy, payload, ops)
   583	            } catch (t: Throwable) {
   584	                wipe(keyCopy)
   585	                throw t
   586	            }
   587	            if (plaintext == null) {
   588	                wipe(keyCopy)
   589	                return null
   590	            }
   591	            return VaultOpen(keyCopy, slotIndex, plaintext)
   592	        }
   593	    }
   594	
   595	    /**
   596	     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
   597	     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
   598	     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
   599	     * cases apart (the plausible-deniability + duress-credential timing contract):
   600	     *
   601	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
   602	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
   603	     *     (real vault-B material on create, pure timing filler otherwise);
   604	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
   605	     *
   606	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
   607	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
   608	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
   609	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
   610	     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
   611	     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
   612	     * [UnlockOrAdd.Rejected] having written nothing.
   613	     *
   614	     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
   615	     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
   616	     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
   617	     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
   618	     *
   619	     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
   620	     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
   621	     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
   622	     * target, so duress protection survives even a full pool.
   623	     *
   624	     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
   625	     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
   626	     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
   627	     *
   628	     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
   629	     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
   630	     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
   631	     *
   632	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
   633	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   634	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
   635	     * create write is not confirmed durable.
   636	     */
   637	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   638	        imageLock.withLock {
   639	            val image = canonical ?: run { open(); canonical!! }
   640	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   641	            val decoded = decodeImage(image)
   642	
   643	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   644	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   645	
   646	            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
   647	            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
   648	            //     POOL (never slot 0) so a create can never clobber the burn credential.
   649	            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
   650	            val candSlotIndex = randomVaultSlotIndex(ops)
   651	            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
   652	
   653	            try {
   654	                return when {
   655	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   656	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   657	                        wipe(candKey)
   658	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   659	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   660	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   661	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   662	                            .getOrNull()?.let { wipe(it) }
   663	                        wipe(unlock.vaultKey)
   664	                        UnlockOrAdd.Burn
   665	                    }
   666	
   667	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   668	                    unlock != null -> {
   669	                        wipe(candKey)
   670	                        val pt = try {
   671	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   672	                        } catch (t: Throwable) {
   673	                            wipe(unlock.vaultKey)
   674	                            throw VaultImageException.CorruptImage()
   675	                        }
   676	                        if (pt == null) {
   677	                            wipe(unlock.vaultKey)
   678	                            throw VaultImageException.CorruptImage()
   679	                        }
   680	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   681	                    }
   682	
   683	                    // ── CREATE a new vault into a vault-pool slot. ──
   684	                    create -> {
   685	                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
   686	                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
   687	                        val markersConfirmedAbsent =
   688	                            Files.notExists(deleteIntentFile.toPath()) &&
   689	                                Files.notExists(serverDeletedFile.toPath())
   690	                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
   691	                            throw VaultImageException.NotDurable()
   692	                        }
   693	                        // The 1×256 KiB payload GCM for this branch.
   694	                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   695	                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   696	                        val newPayloads =
   697	                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   698	                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   699	                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   700	                        // unreachable by construction; the dek is already durable on disk from create().
   701	                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   702	                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   703	                        // rename landed, the result reporting the rename's durability.
   704	                        val sync = atomicWrite(binFile, outer)
   705	                        // Rename committed → advance canonical BEFORE the durability check so a later
   706	                        // splice/attempt never works from stale state even on the NotDurable throw.
   707	                        canonical = newInner
   708	                        if (sync != DirSyncResult.DURABLE) {
   709	                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   710	                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   711	                            // canonical, so a later single entry of its passphrase unlocks it via the
   712	                            // match path (no write needed) — or, if the rename did not survive a crash, it
   713	                            // is simply absent and re-creatable.
   714	                            wipe(candKey)
   715	                            throw VaultImageException.NotDurable()
   716	                        }
   717	                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   718	                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   719	                    }
   720	
   721	                    // ── REJECT — no match, no create. Nothing written. ──
   722	                    else -> {
   723	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   724	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   725	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   726	                        wipe(candKey)
   727	                        wipe(throwaway)
   728	                        UnlockOrAdd.Rejected
   729	                    }
   730	                }
   731	            } catch (t: Throwable) {
   732	                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
   733	                // (return) path this is not reached; on every other path candKey was already wiped, and a
   734	                // re-wipe of zeroed bytes is a no-op.
   735	                wipe(candKey)
   736	                throw t
   737	            }
   738	        }
   739	    }
   740	
   741	    /**
   742	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   743	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   744	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   745	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   746	     *
   747	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   748	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   749	     * distinct because they leave DIFFERENT state:
   750	     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
   751	     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
   752	     *    the PREVIOUS state (the atomic rename replaces or not at all). Session stays dirty, no ack.
   753	     *  - POST-rename dir-fsync not confirmed ([DirSyncResult.NOT_DURABLE]): the new bytes ARE on
   754	     *    disk (the rename — the commit point — landed and its content was fsynced) but the rename's
   755	     *    own durability is unconfirmed. Only a confirmed successful directory fsync ([DirSyncResult.DURABLE])
   756	     *    is treated as durable; anything else — a real dir-fsync EIO OR a platform that could not open a
   757	     *    directory channel — fails CLOSED here. [canonical] is ADVANCED to match disk (so a later splice
   758	     *    never works from stale state — the write is on disk, just unconfirmed), and a
   759	     *    [VaultImageException.NotDurable] is thrown so the caller does NOT ack. The session stays dirty and
   760	     *    retries; a retry whose dir-fsync succeeds then acks.
   760	     *    retries; a retry whose dir-fsync succeeds then acks.
   761	     *
   762	     * Never logs, and does identical work regardless of which slot is written.
   763	     */
   764	    fun writeSealedPayload(slotIndex: Int, sealedPayload: ByteArray) {
   765	        imageLock.withLock {
   766	            val current = canonical ?: throw IllegalStateException("vault image not open")
   767	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   768	            require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
   769	            // spliceImagePayload validates slotIndex and returns a NEW array — `current`
   770	            // is untouched, so nothing below can corrupt the live canonical.
   771	            val spliced = spliceImagePayload(current, slotIndex, sealedPayload)
   772	            val outer = ops.aeadEncrypt(activeDek, spliced, VAULT_IMAGE_OUTER_AD)
   773	            // atomicWrite throws ONLY pre-rename (nothing committed, canonical untouched); a
   774	            // RETURN means the rename landed, with the result telling the rename's durability.
   775	            val sync = atomicWrite(binFile, outer)
   776	            // The rename committed → in-memory canonical now matches disk. Advance it BEFORE the
   777	            // durability check so a later splice never works from stale state even on that throw.
   778	            canonical = spliced
   779	            if (sync != DirSyncResult.DURABLE) {
   780	                // On disk but durability NOT confirmed (real dir-fsync EIO, or a platform that
   781	                // could not open a dir channel): only a confirmed dir-fsync counts as durable, so
   782	                // fail CLOSED and throw — a flush-before-ack caller does NOT ack. canonical is
   783	                // already advanced (above), so the session stays dirty and retries; a retry that
   784	                // dir-fsyncs acks.
   785	                throw VaultImageException.NotDurable()
   786	            }
   787	        }
   788	    }
   789	
   790	    /**
   791	     * Wipe the DEK and drop the canonical image. Store open/close is device-level
   792	     * and independent of any vault's lock — the outer layer is not a slot's secret,
   793	     * so keeping the store open across vault locks is fine; this exists for tests /
   794	     * teardown. Idempotent.
   795	     *
   796	     * Also RELEASES this instance's single-instance registration (see class kdoc), so a
   797	     * new VaultImageStore may open the same directory afterwards. A real process restart
   798	     * ends the old process and drops the registration implicitly; a test simulating a
   799	     * restart within one JVM MUST close() the old instance first before constructing the
   800	     * next one on the same baseDir.
   801	     */
   802	    fun close() {
   803	        imageLock.withLock {
   804	            dek?.let { wipe(it) }
   805	            dek = null
   806	            canonical = null
   807	            unregister()
   808	        }
   809	    }
   810	
   811	    /**
   812	     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
   813	     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
   814	     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
   815	     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
   816	     * boot).
   817	     *
   818	     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
   819	     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
   820	     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
   821	     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
   822	     * release the single-instance registration.
   823	     *
   824	     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
   825	     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
   826	     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
   827	     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
   828	     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
   829	     */
   830	    fun retireLegacyImage() {
   831	        imageLock.withLock {
   832	            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
   833	            val version = readInnerVersionOrNull()
   834	            check(version == LEGACY_IMAGE_VERSION) {
   835	                "retireLegacyImage refused: not a legacy image (inner version=$version)"
   836	            }
   837	            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
   838	            dek?.let { wipe(it) }
   839	            dek = null
   840	            canonical = null
   841	            binFile.delete()
   842	            dekFile.delete()
   843	            deleteLeftoverTmp(binFile)
   844	            deleteLeftoverTmp(dekFile)
   845	            unregister()
   846	            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
   847	            if (binFile.exists() || dekFile.exists() ||
   848	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
   849	            ) {
   850	                throw VaultImageException.DestroyFailed()
   851	            }
   852	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   853	                throw VaultImageException.DestroyFailed()
   854	            }
   855	        }
   856	    }
   857	
   858	    /**
   859	     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
   860	     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
   861	     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
   862	     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
   863	     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
   864	     */
   865	    private fun readInnerVersionOrNull(): Int? {
   866	        if (!binFile.exists() || !dekFile.exists()) return null
   867	        return try {
   868	            val dekBlob = dekFile.readBytes()
   869	            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
   870	            val binBytes = binFile.readBytes()
   871	            if (binBytes.size != OUTER_IMAGE_BYTES) return null
   872	            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
   873	            try {
   874	                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
   875	                if (inner.size != IMAGE_BYTES) return null
   876	                inner[0].toInt() and 0xff
   877	            } finally {
   878	                wipe(unwrapped)
   879	            }
   880	        } catch (t: Throwable) {
   881	            null
   882	        }
   883	    }
   884	
   885	    /**
   886	     * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
   887	     * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
   888	     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
   889	     * interrupted-write `.tmp` leftovers), and RELEASE this instance's single-instance
   890	     * registration so a fresh [create] may re-open the directory in the same process.
   891	     *
   892	     * DISTINCT FROM [close] (load-bearing). [close] only wipes RAM and LEAVES the disk
   893	     * image intact — a lock, not a deletion: after close() [exists] stays true and the
   894	     * encrypted image (with the account's full crypto identity: keypair, ratchet records,
   895	     * roster) survives on disk, recoverable by a later unlock. destroy() is the ONLY path
   896	     * that removes the files, so after it [exists] is false and nothing is recoverable.
   897	     * Account deletion MUST use destroy(), never close()/reseal. When paired with a session
   898	     * teardown that reseals via VaultRuntime.close(), destroy() MUST run AFTER that reseal so
   899	     * no freshly-resealed image survives.
   900	     *
   901	     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
   902	     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
   903	     * are best-effort; even if one returns false the RAM state is still wiped and the
   904	     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
   905	     * never invokes a VaultSession, so it introduces no reverse lock nesting.
   906	     *
   907	     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
   908	     * filesystem error just as it does on an already-absent file, so its boolean cannot be
   909	     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
   910	     * either SURVIVES, the full-crypto image is still on disk, so it throws
   911	     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
   912	     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
   913	     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
   914	     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
   915	     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
   916	     */
   917	    /**
   918	     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
   919	     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
   920	     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
   921	     * (a crash/failure before the server delete auto-destroyed a still-live-account vault).
   922	     *
   923	     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
   924	     *    ONLY "a delete was initiated" — it NEVER triggers local destruction. A crash here leaves a
   925	     *    fully valid, unlockable vault whose server account may still exist.
   926	     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
   927	     *    `api.deleteAccount()` returns a definite gone (2xx / 404). ONLY this marker authorises the
   928	     *    unlink-only [Route.DeleteIncomplete] auto-destroy: when it is present, the server account
   929	     *    is provably gone, so destroying the local copy is always safe.
   930	     *
   931	     * Both are existence-signals made crash-durable with a dir-fsync (fail-closed on non-durable).
   932	     */
   933	    fun markDeleteIntent() {
   934	        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
   935	    }
   936	
   937	    fun markServerDeleteConfirmed() {
   938	        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
   939	    }
   940	
   941	    /**
   942	     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
   943	     * and the marker RE-STATTED absent — [File.delete] returns false on an I/O failure just like on
   944	     * an already-absent file, so its bool cannot be trusted. Throws [VaultImageException.DestroyFailed]
   945	     * if the marker survives (unlink failed) or the retire is not crash-durable; a no-op (already
   946	     * absent) succeeds.
   947	     */
   948	    fun clearDeleteIntent() {
   949	        imageLock.withLock {
   950	            // Tristate (round 15, R14-2): only a CONFIRMED absence (Files.notExists) is a no-op;
   951	            // present-or-indeterminate falls through to the durable clear + verify below. Using
   952	            // File.exists() here would skip clearing a present-but-unstatable marker.
   953	            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
   954	            deleteIntentFile.delete()
   955	            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
   956	                throw VaultImageException.DestroyFailed()
   957	            }
   958	        }
   959	    }
   960	
   961	    /**
   962	     * Delete BOTH delete markers and confirm the retire crash-durably by RE-STAT — never by
   963	     * trusting [File.delete]'s bool (false on an I/O failure too). Returns true iff both markers
   964	     * re-stat ABSENT AND the directory fsync is [DirSyncResult.DURABLE]. Idempotent (already-absent
   965	     * markers succeed). The single choke point for the marker-retirement discipline used by
   966	     * [create] (F2) and [destroy] (F4). Caller must hold [imageLock].
   967	     */
   968	    private fun clearBothMarkersDurably(): Boolean {
   969	        deleteIntentFile.delete()
   970	        serverDeletedFile.delete()
   971	        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
   972	        // TRISTATE re-stat (round 15, R14-2): File.exists()==false conflates "absent" with "stat
   973	        // could not be determined" (I/O/permission failure), so trusting it would report a marker
   974	        // that SURVIVED an unlink as gone. Files.notExists returns true ONLY when the path is
   975	        // confirmed absent — present OR indeterminate both yield false, so the clear is proven
   976	        // only on a definite absence (fail-closed).
   977	        return durable &&
   978	            Files.notExists(deleteIntentFile.toPath()) &&
   979	            Files.notExists(serverDeletedFile.toPath())
   980	    }
   981	
   982	    /** Create [file] + dir-fsync it; throw [VaultImageException.DestroyFailed] if not durable. */
   983	    private fun writeDurableMarker(file: File) {
   984	        val durable = runCatching {
   985	            file.createNewFile()
   986	            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
   987	        }.getOrDefault(false)
   988	        if (!durable) {
   989	            throw VaultImageException.DestroyFailed()
   990	        }
   991	    }
   992	
   993	    fun destroy() {
   994	        imageLock.withLock {
   995	            // Wipe live key material + drop the cached image FIRST — before even the marker gate
   996	            // can throw — so no DEK/plaintext-adjacent state is retained on ANY exit. A destroy
   997	            // request is terminal for this store's usefulness regardless of outcome (the session
   998	            // is already torn down); the retry path never needs the cached DEK.
   999	            dek?.let { wipe(it) }
  1000	            dek = null
  1001	            canonical = null
  1002	            // CONFIRMED MARKER before the unlinks (crash/restart continuity): reaching destroy()
  1003	            // means the server account is confirmed gone, so write `vault.delete-confirmed`
  1004	            // durably BEFORE unlinking. A crash mid-unlink then restarts into
  1005	            // [Route.DeleteIncomplete] (the CONFIRMED marker is present) and re-runs this
  1006	            // idempotent destroy — never a lock gate over a gone account. REQUIRED-DURABLE: if it
  1007	            // can't be written+fsynced, ABORT with the vault files untouched (throw). Idempotent
  1008	            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
  1009	            writeDurableMarker(serverDeletedFile)
  1010	            // Remove BOTH persisted files and any interrupted-write temps. delete() is
  1011	            // best-effort and never throws on a missing file (returns false) — idempotent.
  1012	            binFile.delete()
  1013	            dekFile.delete()
  1014	            deleteLeftoverTmp(binFile)
  1015	            deleteLeftoverTmp(dekFile)
  1016	            // Release the single-instance registration so a fresh create() may re-open this
  1017	            // directory in the SAME process (re-onboard after account deletion).
  1018	            unregister()
  1019	            // VERIFY everything image-bearing is actually GONE (see kdoc): delete()'s bool is
  1020	            // false on an I/O error too, so re-stat instead. The TEMPS are part of the check
  1021	            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
  1022	            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
  1023	            // verify exists to catch, an encrypted image copy could survive as a temp while the
  1024	            // primaries are gone. A surviving file → destruction FAILED → throw; account-delete
  1025	            // treats this as NOT-deleted. exists()==false (already-absent) does NOT throw,
  1026	            // keeping destroy() idempotent.
  1027	            if (binFile.exists() || dekFile.exists() ||
  1028	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
  1029	            ) {
  1030	                throw VaultImageException.DestroyFailed()
  1031	            }
  1032	            // Make the unlinks CRASH-DURABLE before retiring the markers (round 8, Codex): the
  1033	            // exists() re-stat proves only the current namespace, not what a journal replay
  1034	            // restores. Without this fsync, a crash could resurrect vault.bin/vault.dek while the
  1035	            // markers' own (later) unlink survived — restarting into DeleteIncomplete over a
  1036	            // now-present image, the exact state the markers exist to signal. A non-durable sync
  1037	            // keeps the markers (throw → retry re-runs the idempotent destroy), never false success.
  1038	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
  1039	                throw VaultImageException.DestroyFailed()
  1040	            }
  1041	            // Unlinks confirmed durable — retire BOTH markers, verified by RE-STAT + a required
  1042	            // fsync (round 13 Grok P1-2 / round 14 F4): trusting File.delete()'s bool would let a
  1043	            // silent unlink failure leave a marker that a journal replay resurrects over a later
  1044	            // SUCCESSOR vault → DeleteIncomplete → auto-destroy of a valid re-onboarded vault. A
  1045	            // marker that survives the delete, or a non-durable retire, is a FAILED destroy (throw):
  1046	            // marker-present + files-absent is the safe stuck state (a retry re-stats the files
  1047	            // absent and re-runs the retire). Self-healing over the empty image, now also correct.
  1048	            if (!clearBothMarkersDurably()) {
  1049	                throw VaultImageException.DestroyFailed()
  1050	            }
  1051	        }
  1052	    }
  1053	
  1054	    /**
  1055	     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
  1056	     * local image must be destroyed. The ONLY authorisation for the unlink-only
  1057	     * [Route.DeleteIncomplete] auto-destroy. (Replaces the round-12 `destroyPending`, which
  1058	     * conflated intent with confirmation — the P1-A/P1-1 root.)
  1059	     */
  1060	    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
  1061	
  1062	    /**
  1063	     * True while a delete was INITIATED but the server delete is not confirmed (intent marker
  1064	     * present, confirmed absent) — a crash/failure mid-delete. The vault is still valid and the
  1065	     * server account may still exist, so boot routes to normal unlock (NOT auto-destroy) and, on the
  1066	     * next live session, RECONCILES by retrying the authenticated DELETE (round 14). It never
  1067	     * authorises destruction and is retired only by a confirmed [destroy] — never cleared on boot.
  1068	     */
  1069	    fun deleteIntentPending(): Boolean =
  1070	        imageLock.withLock { deleteIntentFile.exists() && !serverDeletedFile.exists() }
  1071	
  1072	    /**
  1073	     * True while the DURABLE delete-intent marker is present — from its durable write until a
  1074	     * confirmed [destroy] retires it, spanning every not-confirmed exit AND process restart (round
  1075	     * 16, R15-P2). This is the auth-protection lifetime: while it holds, no auth-clearing path may
  1076	     * strip the vault-backed tokens, because a future reconcile may need them to reach the
  1077	     * idempotent 404. Deliberately NOT `&& !confirmed` (unlike [deleteIntentPending]): a
  1078	     * confirmed marker that was created but not fsync-durable ([MessagingCoordinator]'s
  1079	     * onConfirmedNotDurable) can vanish on a crash, dropping back to an intent-only reconcile that
  1080	     * still needs auth — so auth is protected while the intent file is present, regardless of the
  1081	     * confirmed marker (harmlessly true through the brief confirmed→destroy window, where auth is
  1082	     * about to be destroyed anyway).
  1083	     *
  1084	     * FAIL-CLOSED re-stat (round 16, R16-R2 / Codex): this is an auth-PROTECTION read — the guard
  1085	     * skips clearing tokens when this is true — so an indeterminate stat must protect, not expose.
  1086	     * `File.exists()==false` conflates "absent" with "stat could not be determined", which would
  1087	     * fail OPEN (permit the token clear) on an I/O fault while the intent is present. `!Files.notExists`
  1088	     * is true when the marker is present OR indeterminate (`Files.notExists` is true ONLY on a proven
  1089	     * absence), so auth is protected unless the intent is provably gone. (This is the opposite bias
  1090	     * from the routing readers, where an indeterminate false correctly withholds auto-destroy.)
  1091	     */
  1092	    fun hasDeleteIntentMarker(): Boolean =
  1093	        imageLock.withLock { !Files.notExists(deleteIntentFile.toPath()) }
  1094	
  1095	    /**
  1096	     * Claim the single-instance registration for [baseDir] (see class kdoc). Idempotent
  1097	     * for THIS instance (a re-open no-ops); throws [IllegalStateException] if a DIFFERENT
  1098	     * instance already holds the directory. The compound check-then-add is atomic under
  1099	     * the [OPEN_PATHS] monitor so two instances racing on the same directory cannot both
  1100	     * acquire it. Always called under [imageLock].
  1101	     */
  1102	    private fun register() {
  1103	        val path = baseDir.canonicalFile.path
  1104	        synchronized(OPEN_PATHS) {
  1105	            if (registeredPath == path) return // idempotent: this instance already owns it
  1106	            check(path !in OPEN_PATHS) { "a VaultImageStore is already open for this directory" }
  1107	            OPEN_PATHS.add(path)
  1108	            registeredPath = path
  1109	        }
  1110	    }
  1111	
  1112	    /** Release this instance's single-instance registration, if any. Idempotent; always
  1113	     *  called under [imageLock]. */
  1114	    private fun unregister() {
  1115	        val path = registeredPath ?: return
  1116	        OPEN_PATHS.remove(path)
  1117	        registeredPath = null
  1118	    }
  1119	
  1120	    /**
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
    79	 * Initialize a fresh set of slots: SLOT_COUNT slots, exactly one of which is the
    80	 * real vault sealed under [passphrase]. The rest are random filler. The returned
    81	 * vaultKey is the random key the caller should use to encrypt the vault's data.
    82	 * The real slot is placed at a CSPRNG-random index IN THE VAULT POOL
    83	 * ([randomVaultSlotIndex], slots 1..SLOT_COUNT-1) so position leaks nothing AND slot 0
    84	 * stays reserved for the burn credential (see [BURN_SLOT_INDEX]). Slot 0 is left as
    85	 * filler on a fresh onboarding (unarmed burn), indistinguishable from any other slot.
    86	 */
    87	fun createVaultSlots(
    88	    passphrase: String,
    89	    ops: VaultSodiumOps,
    90	    deriver: KeyDeriver = argon2idDeriver(ops),
    91	): CreatedVault {
    92	    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
    93	    // On SUCCESS the caller owns (and later wipes) vaultKey; on ANY failure path
    94	    // after generation, wipe it here so no live key is abandoned in heap.
    95	    try {
    96	        val slots = ArrayList<KeySlot>(SLOT_COUNT)
    97	        for (i in 0 until SLOT_COUNT) slots.add(randomSlot(ops))
    98	        val slotIndex = randomVaultSlotIndex(ops) // 1..SLOT_COUNT-1 — slot 0 reserved for burn
    99	        slots[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
   100	        return CreatedVault(slots = slots, vaultKey = vaultKey, slotIndex = slotIndex)
   101	    } catch (t: Throwable) {
   102	        wipe(vaultKey)
   103	        throw t
   104	    }
   105	}
   106	
   107	/**
   108	 * Seal a second (or third…) vault into a currently-unoccupied slot. The new
   109	 * vault gets its own independent random vault key — vaults share no key
   110	 * material. The slot chosen is a random currently-unoccupied one so the layout
   111	 * still reveals nothing. Throws if every slot is occupied.
   112	 *
   113	 * [occupied] is supplied by the caller because the stored material deliberately
   114	 * cannot reveal which slots hold real vaults (that is the whole point). Passing
   115	 * an empty set reproduces the web's overwrite-tolerant behavior (storage.ts
   116	 * createVault, the documented VeraCrypt outer-volume tradeoff); passing the
   117	 * known-occupied indices avoids clobbering a live vault.
   118	 *
   119	 * ⚠️ BURN-UNAWARE (0.9.2): this primitive picks freely over ALL slots incl.
   120	 * [BURN_SLOT_INDEX], so it must NOT be wired into a creation path without excluding
   121	 * slot 0 (add 0 to [occupied], or use [randomVaultSlotIndex]). The live Android
   122	 * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
   123	 * over the vault pool and does NOT call this; this and [addVaultToImage] are retained
   124	 * as the web-mirrored primitive + tests only.
   125	 */
   126	fun addVaultSlot(
   127	    slots: List<KeySlot>,
   128	    occupied: Set<Int>,
   129	    passphrase: String,
   130	    ops: VaultSodiumOps,
   131	    deriver: KeyDeriver = argon2idDeriver(ops),
   132	): CreatedVault {
   133	    // Reject a passphrase that already unlocks an existing vault: tryPassphrase
   134	    // returns only the FIRST matching slot, so a second seal under the same
   135	    // passphrase would shadow one vault and silently make it unreachable.
   136	    tryPassphrase(passphrase, slots, ops, deriver)?.let {
   137	        wipe(it.vaultKey)
   138	        throw IllegalArgumentException("passphrase already unlocks an existing vault")
   139	    }
   140	    val free = ArrayList<Int>()
   141	    for (i in slots.indices) if (i !in occupied) free.add(i)
   142	    if (free.isEmpty()) throw IllegalStateException("no free key slots")
   143	    val slotIndex = free[randomIndex(free.size, ops)]
   144	    val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
   145	    try {
   146	        val next = slots.toMutableList()
   147	        next[slotIndex] = sealSlot(passphrase, vaultKey, ops, deriver)
   148	        return CreatedVault(slots = next, vaultKey = vaultKey, slotIndex = slotIndex)
   149	    } catch (t: Throwable) {
   150	        wipe(vaultKey)
   151	        throw t
   152	    }
   153	}
   154	
   155	/**
   156	 * Attempt a passphrase against all slots. Returns the unlocked vault key, or
   157	 * null if no slot matched (indistinguishable from a wrong passphrase).
   158	 *
   159	 * Derive+attempt EVERY slot, never break, so wall-clock timing is identical
   160	 * whether a passphrase matches slot 0, slot N, or nothing — a break here is a
   161	 * plausible-deniability side-channel. The first match is recorded but the loop
   162	 * runs to completion regardless; any later match's vault key is wiped, and every
   163	 * derived master key is wiped whether it matched or not.
   164	 *
   165	 * CPU-HEAVY: SLOT_COUNT × Argon2id (64 MiB each) with the production deriver.
   166	 * Callers on a UI thread MUST run this off the main thread.
   167	 */
   168	fun tryPassphrase(
   169	    passphrase: String,
   170	    slots: List<KeySlot>,
   171	    ops: VaultSodiumOps,
   172	    deriver: KeyDeriver = argon2idDeriver(ops),
   173	): VaultUnlock? {
   174	    var found: VaultUnlock? = null
   175	    try {
   176	        for (i in slots.indices) {
   177	            val slot = slots[i]
   178	            val masterKey = deriver(passphrase, slot.salt)
   179	            try {
   180	                val vaultKey = ops.aeadDecrypt(masterKey, slot.wrapped, SLOT_AD)
   181	                if (vaultKey != null) {
   182	                    // Record the first match but DO NOT break — every slot is
   183	                    // always derived and tried.
   184	                    if (found == null) found = VaultUnlock(vaultKey, i) else wipe(vaultKey)
   185	                }
   186	            } finally {
   187	                wipe(masterKey)
   188	            }
   189	        }
   190	    } catch (t: Throwable) {
   191	        // A later derivation failing (e.g. OOM under memory pressure) must not
   192	        // abandon an already-matched vault key in heap — the caller never
   193	        // received it to wipe.
   194	        found?.let { wipe(it.vaultKey) }
   195	        throw t
   196	    }
   197	    return found
   198	}
   199	
   200	/** Overwrite key material in place. Call the moment a key is no longer needed. */
   201	fun wipe(bytes: ByteArray) {
   202	    bytes.fill(0)
   203	}
   204	
   205	/**
   206	 * Uniform random index in [0, n) drawn from the CSPRNG. Reads 4 CSPRNG bytes as
   207	 * a big-endian unsigned 32-bit value and reduces mod n (no meaningful modulo
   208	 * bias for the small n used here). Byte-for-byte the same construction as
   209	 * vault.ts randomIndex.
   210	 */
   211	fun randomIndex(n: Int, ops: VaultSodiumOps): Int {
   212	    val buf = ops.randomBytes(4)
   213	    val v = ((buf[0].toInt() and 0xff) shl 24) or
   214	        ((buf[1].toInt() and 0xff) shl 16) or
   215	        ((buf[2].toInt() and 0xff) shl 8) or
   216	        (buf[3].toInt() and 0xff)
   217	    val unsigned = v.toLong() and 0xffffffffL
   218	    return (unsigned % n).toInt()
   219	}

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '530,750p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '810,1060p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt | sed -n '150,210p;295,405p;470,510p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt | sed -n '610,740p;760,825p;1000,1060p;1125,1170p'
nl -ba apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt | sed -n '116,150p;1390,1470p'" in /root/zitrone
 succeeded in 0ms:
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
   575	            require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
   576	            val image = canonical ?: run { open(); canonical!! }
   577	            val payload = decodeImage(image).payloads[slotIndex]
   578	            // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
   579	            // caller's input is never touched (it owns and wipes that itself).
   580	            val keyCopy = vaultKey.copyOf()
   581	            val plaintext = try {
   582	                openPayload(keyCopy, payload, ops)
   583	            } catch (t: Throwable) {
   584	                wipe(keyCopy)
   585	                throw t
   586	            }
   587	            if (plaintext == null) {
   588	                wipe(keyCopy)
   589	                return null
   590	            }
   591	            return VaultOpen(keyCopy, slotIndex, plaintext)
   592	        }
   593	    }
   594	
   595	    /**
   596	     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
   597	     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
   598	     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
   599	     * cases apart (the plausible-deniability + duress-credential timing contract):
   600	     *
   601	     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
   602	     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
   603	     *     (real vault-B material on create, pure timing filler otherwise);
   604	     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
   605	     *
   606	     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
   607	     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
   608	     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
   609	     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
   610	     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
   611	     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
   612	     * [UnlockOrAdd.Rejected] having written nothing.
   613	     *
   614	     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
   615	     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
   616	     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
   617	     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
   618	     *
   619	     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
   620	     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
   621	     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
   622	     * target, so duress protection survives even a full pool.
   623	     *
   624	     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
   625	     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
   626	     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
   627	     *
   628	     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
   629	     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
   630	     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
   631	     *
   632	     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
   633	     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
   634	     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
   635	     * create write is not confirmed durable.
   636	     */
   637	    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
   638	        imageLock.withLock {
   639	            val image = canonical ?: run { open(); canonical!! }
   640	            val activeDek = dek ?: throw IllegalStateException("vault image not open")
   641	            val decoded = decodeImage(image)
   642	
   643	            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
   644	            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
   645	
   646	            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
   647	            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
   648	            //     POOL (never slot 0) so a create can never clobber the burn credential.
   649	            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
   650	            val candSlotIndex = randomVaultSlotIndex(ops)
   651	            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
   652	
   653	            try {
   654	                return when {
   655	                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
   656	                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
   657	                        wipe(candKey)
   658	                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
   659	                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
   660	                        // duress credential must never be suppressed by a damaged marker (spec §6).
   661	                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
   662	                            .getOrNull()?.let { wipe(it) }
   663	                        wipe(unlock.vaultKey)
   664	                        UnlockOrAdd.Burn
   665	                    }
   666	
   667	                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
   668	                    unlock != null -> {
   669	                        wipe(candKey)
   670	                        val pt = try {
   671	                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
   672	                        } catch (t: Throwable) {
   673	                            wipe(unlock.vaultKey)
   674	                            throw VaultImageException.CorruptImage()
   675	                        }
   676	                        if (pt == null) {
   677	                            wipe(unlock.vaultKey)
   678	                            throw VaultImageException.CorruptImage()
   679	                        }
   680	                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
   681	                    }
   682	
   683	                    // ── CREATE a new vault into a vault-pool slot. ──
   684	                    create -> {
   685	                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
   686	                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
   687	                        val markersConfirmedAbsent =
   688	                            Files.notExists(deleteIntentFile.toPath()) &&
   689	                                Files.notExists(serverDeletedFile.toPath())
   690	                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
   691	                            throw VaultImageException.NotDurable()
   692	                        }
   693	                        // The 1×256 KiB payload GCM for this branch.
   694	                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
   695	                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
   696	                        val newPayloads =
   697	                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
   698	                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
   699	                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
   700	                        // unreachable by construction; the dek is already durable on disk from create().
   701	                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
   702	                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
   703	                        // rename landed, the result reporting the rename's durability.
   704	                        val sync = atomicWrite(binFile, outer)
   705	                        // Rename committed → advance canonical BEFORE the durability check so a later
   706	                        // splice/attempt never works from stale state even on the NotDurable throw.
   707	                        canonical = newInner
   708	                        if (sync != DirSyncResult.DURABLE) {
   709	                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
   710	                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
   711	                            // canonical, so a later single entry of its passphrase unlocks it via the
   712	                            // match path (no write needed) — or, if the rename did not survive a crash, it
   713	                            // is simply absent and re-creatable.
   714	                            wipe(candKey)
   715	                            throw VaultImageException.NotDurable()
   716	                        }
   717	                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
   718	                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
   719	                    }
   720	
   721	                    // ── REJECT — no match, no create. Nothing written. ──
   722	                    else -> {
   723	                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
   724	                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
   725	                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
   726	                        wipe(candKey)
   727	                        wipe(throwaway)
   728	                        UnlockOrAdd.Rejected
   729	                    }
   730	                }
   731	            } catch (t: Throwable) {
   732	                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
   733	                // (return) path this is not reached; on every other path candKey was already wiped, and a
   734	                // re-wipe of zeroed bytes is a no-op.
   735	                wipe(candKey)
   736	                throw t
   737	            }
   738	        }
   739	    }
   740	
   741	    /**
   742	     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
   743	     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
   744	     * (every other region byte-unchanged), outer-encrypts the result with a fresh
   745	     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
   746	     *
   747	     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
   748	     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
   749	     * distinct because they leave DIFFERENT state:
   750	     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
   810	
   811	    /**
   812	     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
   813	     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
   814	     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
   815	     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
   816	     * boot).
   817	     *
   818	     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
   819	     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
   820	     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
   821	     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
   822	     * release the single-instance registration.
   823	     *
   824	     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
   825	     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
   826	     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
   827	     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
   828	     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
   829	     */
   830	    fun retireLegacyImage() {
   831	        imageLock.withLock {
   832	            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
   833	            val version = readInnerVersionOrNull()
   834	            check(version == LEGACY_IMAGE_VERSION) {
   835	                "retireLegacyImage refused: not a legacy image (inner version=$version)"
   836	            }
   837	            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
   838	            dek?.let { wipe(it) }
   839	            dek = null
   840	            canonical = null
   841	            binFile.delete()
   842	            dekFile.delete()
   843	            deleteLeftoverTmp(binFile)
   844	            deleteLeftoverTmp(dekFile)
   845	            unregister()
   846	            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
   847	            if (binFile.exists() || dekFile.exists() ||
   848	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
   849	            ) {
   850	                throw VaultImageException.DestroyFailed()
   851	            }
   852	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
   853	                throw VaultImageException.DestroyFailed()
   854	            }
   855	        }
   856	    }
   857	
   858	    /**
   859	     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
   860	     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
   861	     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
   862	     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
   863	     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
   864	     */
   865	    private fun readInnerVersionOrNull(): Int? {
   866	        if (!binFile.exists() || !dekFile.exists()) return null
   867	        return try {
   868	            val dekBlob = dekFile.readBytes()
   869	            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
   870	            val binBytes = binFile.readBytes()
   871	            if (binBytes.size != OUTER_IMAGE_BYTES) return null
   872	            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
   873	            try {
   874	                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
   875	                if (inner.size != IMAGE_BYTES) return null
   876	                inner[0].toInt() and 0xff
   877	            } finally {
   878	                wipe(unwrapped)
   879	            }
   880	        } catch (t: Throwable) {
   881	            null
   882	        }
   883	    }
   884	
   885	    /**
   886	     * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
   887	     * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
   888	     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
   889	     * interrupted-write `.tmp` leftovers), and RELEASE this instance's single-instance
   890	     * registration so a fresh [create] may re-open the directory in the same process.
   891	     *
   892	     * DISTINCT FROM [close] (load-bearing). [close] only wipes RAM and LEAVES the disk
   893	     * image intact — a lock, not a deletion: after close() [exists] stays true and the
   894	     * encrypted image (with the account's full crypto identity: keypair, ratchet records,
   895	     * roster) survives on disk, recoverable by a later unlock. destroy() is the ONLY path
   896	     * that removes the files, so after it [exists] is false and nothing is recoverable.
   897	     * Account deletion MUST use destroy(), never close()/reseal. When paired with a session
   898	     * teardown that reseals via VaultRuntime.close(), destroy() MUST run AFTER that reseal so
   899	     * no freshly-resealed image survives.
   900	     *
   901	     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
   902	     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
   903	     * are best-effort; even if one returns false the RAM state is still wiped and the
   904	     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
   905	     * never invokes a VaultSession, so it introduces no reverse lock nesting.
   906	     *
   907	     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
   908	     * filesystem error just as it does on an already-absent file, so its boolean cannot be
   909	     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
   910	     * either SURVIVES, the full-crypto image is still on disk, so it throws
   911	     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
   912	     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
   913	     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
   914	     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
   915	     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
   916	     */
   917	    /**
   918	     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
   919	     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
   920	     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
   921	     * (a crash/failure before the server delete auto-destroyed a still-live-account vault).
   922	     *
   923	     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
   924	     *    ONLY "a delete was initiated" — it NEVER triggers local destruction. A crash here leaves a
   925	     *    fully valid, unlockable vault whose server account may still exist.
   926	     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
   927	     *    `api.deleteAccount()` returns a definite gone (2xx / 404). ONLY this marker authorises the
   928	     *    unlink-only [Route.DeleteIncomplete] auto-destroy: when it is present, the server account
   929	     *    is provably gone, so destroying the local copy is always safe.
   930	     *
   931	     * Both are existence-signals made crash-durable with a dir-fsync (fail-closed on non-durable).
   932	     */
   933	    fun markDeleteIntent() {
   934	        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
   935	    }
   936	
   937	    fun markServerDeleteConfirmed() {
   938	        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
   939	    }
   940	
   941	    /**
   942	     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
   943	     * and the marker RE-STATTED absent — [File.delete] returns false on an I/O failure just like on
   944	     * an already-absent file, so its bool cannot be trusted. Throws [VaultImageException.DestroyFailed]
   945	     * if the marker survives (unlink failed) or the retire is not crash-durable; a no-op (already
   946	     * absent) succeeds.
   947	     */
   948	    fun clearDeleteIntent() {
   949	        imageLock.withLock {
   950	            // Tristate (round 15, R14-2): only a CONFIRMED absence (Files.notExists) is a no-op;
   951	            // present-or-indeterminate falls through to the durable clear + verify below. Using
   952	            // File.exists() here would skip clearing a present-but-unstatable marker.
   953	            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
   954	            deleteIntentFile.delete()
   955	            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
   956	                throw VaultImageException.DestroyFailed()
   957	            }
   958	        }
   959	    }
   960	
   961	    /**
   962	     * Delete BOTH delete markers and confirm the retire crash-durably by RE-STAT — never by
   963	     * trusting [File.delete]'s bool (false on an I/O failure too). Returns true iff both markers
   964	     * re-stat ABSENT AND the directory fsync is [DirSyncResult.DURABLE]. Idempotent (already-absent
   965	     * markers succeed). The single choke point for the marker-retirement discipline used by
   966	     * [create] (F2) and [destroy] (F4). Caller must hold [imageLock].
   967	     */
   968	    private fun clearBothMarkersDurably(): Boolean {
   969	        deleteIntentFile.delete()
   970	        serverDeletedFile.delete()
   971	        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
   972	        // TRISTATE re-stat (round 15, R14-2): File.exists()==false conflates "absent" with "stat
   973	        // could not be determined" (I/O/permission failure), so trusting it would report a marker
   974	        // that SURVIVED an unlink as gone. Files.notExists returns true ONLY when the path is
   975	        // confirmed absent — present OR indeterminate both yield false, so the clear is proven
   976	        // only on a definite absence (fail-closed).
   977	        return durable &&
   978	            Files.notExists(deleteIntentFile.toPath()) &&
   979	            Files.notExists(serverDeletedFile.toPath())
   980	    }
   981	
   982	    /** Create [file] + dir-fsync it; throw [VaultImageException.DestroyFailed] if not durable. */
   983	    private fun writeDurableMarker(file: File) {
   984	        val durable = runCatching {
   985	            file.createNewFile()
   986	            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
   987	        }.getOrDefault(false)
   988	        if (!durable) {
   989	            throw VaultImageException.DestroyFailed()
   990	        }
   991	    }
   992	
   993	    fun destroy() {
   994	        imageLock.withLock {
   995	            // Wipe live key material + drop the cached image FIRST — before even the marker gate
   996	            // can throw — so no DEK/plaintext-adjacent state is retained on ANY exit. A destroy
   997	            // request is terminal for this store's usefulness regardless of outcome (the session
   998	            // is already torn down); the retry path never needs the cached DEK.
   999	            dek?.let { wipe(it) }
  1000	            dek = null
  1001	            canonical = null
  1002	            // CONFIRMED MARKER before the unlinks (crash/restart continuity): reaching destroy()
  1003	            // means the server account is confirmed gone, so write `vault.delete-confirmed`
  1004	            // durably BEFORE unlinking. A crash mid-unlink then restarts into
  1005	            // [Route.DeleteIncomplete] (the CONFIRMED marker is present) and re-runs this
  1006	            // idempotent destroy — never a lock gate over a gone account. REQUIRED-DURABLE: if it
  1007	            // can't be written+fsynced, ABORT with the vault files untouched (throw). Idempotent
  1008	            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
  1009	            writeDurableMarker(serverDeletedFile)
  1010	            // Remove BOTH persisted files and any interrupted-write temps. delete() is
  1011	            // best-effort and never throws on a missing file (returns false) — idempotent.
  1012	            binFile.delete()
  1013	            dekFile.delete()
  1014	            deleteLeftoverTmp(binFile)
  1015	            deleteLeftoverTmp(dekFile)
  1016	            // Release the single-instance registration so a fresh create() may re-open this
  1017	            // directory in the SAME process (re-onboard after account deletion).
  1018	            unregister()
  1019	            // VERIFY everything image-bearing is actually GONE (see kdoc): delete()'s bool is
  1020	            // false on an I/O error too, so re-stat instead. The TEMPS are part of the check
  1021	            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
  1022	            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
  1023	            // verify exists to catch, an encrypted image copy could survive as a temp while the
  1024	            // primaries are gone. A surviving file → destruction FAILED → throw; account-delete
  1025	            // treats this as NOT-deleted. exists()==false (already-absent) does NOT throw,
  1026	            // keeping destroy() idempotent.
  1027	            if (binFile.exists() || dekFile.exists() ||
  1028	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
  1029	            ) {
  1030	                throw VaultImageException.DestroyFailed()
  1031	            }
  1032	            // Make the unlinks CRASH-DURABLE before retiring the markers (round 8, Codex): the
  1033	            // exists() re-stat proves only the current namespace, not what a journal replay
  1034	            // restores. Without this fsync, a crash could resurrect vault.bin/vault.dek while the
  1035	            // markers' own (later) unlink survived — restarting into DeleteIncomplete over a
  1036	            // now-present image, the exact state the markers exist to signal. A non-durable sync
  1037	            // keeps the markers (throw → retry re-runs the idempotent destroy), never false success.
  1038	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
  1039	                throw VaultImageException.DestroyFailed()
  1040	            }
  1041	            // Unlinks confirmed durable — retire BOTH markers, verified by RE-STAT + a required
  1042	            // fsync (round 13 Grok P1-2 / round 14 F4): trusting File.delete()'s bool would let a
  1043	            // silent unlink failure leave a marker that a journal replay resurrects over a later
  1044	            // SUCCESSOR vault → DeleteIncomplete → auto-destroy of a valid re-onboarded vault. A
  1045	            // marker that survives the delete, or a non-durable retire, is a FAILED destroy (throw):
  1046	            // marker-present + files-absent is the safe stuck state (a retry re-stats the files
  1047	            // absent and re-runs the retire). Self-healing over the empty image, now also correct.
  1048	            if (!clearBothMarkersDurably()) {
  1049	                throw VaultImageException.DestroyFailed()
  1050	            }
  1051	        }
  1052	    }
  1053	
  1054	    /**
  1055	     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
  1056	     * local image must be destroyed. The ONLY authorisation for the unlink-only
  1057	     * [Route.DeleteIncomplete] auto-destroy. (Replaces the round-12 `destroyPending`, which
  1058	     * conflated intent with confirmation — the P1-A/P1-1 root.)
  1059	     */
  1060	    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
   150	     * composition-local guard would let a second tap start a concurrent create — and a plain
   151	     * seeded bool would strand the recreated spinner if the create then failed. The UI collects
   152	     * this; [tryBeginVaultCreate] claims (compare-and-set), [endVaultCreate] releases.
   153	     */
   154	    val vaultCreating = MutableStateFlow(false)
   155	
   156	    fun tryBeginVaultCreate(): Boolean = vaultCreating.compareAndSet(expect = false, update = true)
   157	
   158	    fun endVaultCreate() {
   159	        vaultCreating.value = false
   160	    }
   161	
   162	    /** Routing truth: a vault image is present → UNLOCK, absent → SETUP (onboarding). */
   163	    fun hasVault(): Boolean = imageStore.exists()
   164	
   165	    /**
   166	     * Routing signal (0.9.2): a present image is the PRIOR (v2 / 0.9.1) format, which the burn-slot
   167	     * reservation makes unsafe to unlock — route to fresh onboarding instead of the lock screen. A
   168	     * cheap Argon2id-free peek (see [VaultImageStore.isLegacyImage]); call off-main. Safety does NOT
   169	     * depend on this routing: [VaultImageStore.open] throws [VaultImageException.LegacyImage] before any
   170	     * slot is interpreted, so a v2 image can never be misread as a burn wipe even if it reaches unlock.
   171	     */
   172	    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()
   173	
   174	    /**
   175	     * Routing truth OVERRIDING [hasVault]: the SERVER account is CONFIRMED gone and the local
   176	     * vault destroy is owed ([VaultImageStore.serverDeleteConfirmed]). The only valid route is
   177	     * "finish the deletion" (retry [destroyVaultForAccountDeletion]) — never the unlock gate. This
   178	     * is the ONLY authorisation for the auto-destroy route: a mere delete-INTENT (server outcome
   179	     * unknown) does NOT set it (round 13 — the round-12 conflation was the P1).
   180	     */
   181	    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()
   182	
   183	    /**
   184	     * A delete was INITIATED but the server delete is not confirmed (a crash/failure mid-delete).
   185	     * The vault is still valid and the account may still exist, so boot routes to normal unlock and
   186	     * clears this stale intent — it NEVER authorises destruction. See
   187	     * [VaultImageStore.deleteIntentPending].
   188	     */
   189	    fun vaultDeleteIntentPending(): Boolean = imageStore.deleteIntentPending()
   190	
   191	    /** Persist the delete INTENT durably (throws if not durable; caller fails closed). */
   192	    fun markVaultDeleteIntent() = imageStore.markDeleteIntent()
   193	
   194	    /** Persist SERVER-DELETE-CONFIRMED durably — the auto-destroy authorisation. */
   195	    fun markServerDeleteConfirmed() = imageStore.markServerDeleteConfirmed()
   196	
   197	    /** Durable auth-protection signal: the delete-intent marker is present (round 16, R15-P2). */
   198	    fun hasVaultDeleteIntentMarker() = imageStore.hasDeleteIntentMarker()
   199	
   200	    // @Volatile so the transport apply-loop (running on Dispatchers.Default) and
   201	    // the construction thread publish/read the current client consistently.
   202	    @Volatile
   203	    private var httpClient =
   204	        CertificatePinning.buildClient(torEnabled = deviceSettings.torEnabled)
   205	
   206	    private val transportInputs: StateFlow<TransportResolver.Inputs> =
   207	        deviceSettings.transportInputs
   208	            .stateIn(
   209	                scope,
   210	                SharingStarted.Eagerly,
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
   470	            onRefused = {
   471	                wipe(vaultOpen.vaultKey)
   472	                wipe(vaultOpen.payloadPlaintext)
   473	            },
   474	        )
   475	        if (published) settingsRepository.setOnboardingDone(true)
   476	        return published
   477	    }
   478	
   479	    private fun buildVaultSession(sessionScope: CoroutineScope, vaultOpen: VaultOpen): SessionContainer {
   480	        val (client, apiBase, ws) = transportEndpoints(transportResolver.state.value)
   481	        httpClient = client
   482	        return SessionContainer(
   483	            app = app,
   484	            scope = sessionScope,
   485	            bootDiagnostics = bootDiagnostics,
   486	            settings = settingsRepository,
   487	            httpClient = httpClient,
   488	            apiBaseUrl = apiBase,
   489	            wsUrl = ws,
   490	            vaultOps = vaultOps,
   491	            vaultOpen = vaultOpen,
   492	            persist = imageStore::writeSealedPayload,
   493	            persistDeleteIntent = imageStore::markDeleteIntent,
   494	            persistServerDeleteConfirmed = imageStore::markServerDeleteConfirmed,
   495	            intentMarkerPresent = imageStore::hasDeleteIntentMarker,
   496	        )
   497	    }
   498	
   499	    /** Clear the orphaned legacy stores at vault creation (see [createVaultAndPublish]). */
   500	    private fun wipeLegacyPrefs() {
   501	        keyStoreManager.prefs(KeyStoreManager.PREFS_SIGNAL_STORE).edit().clear().apply()
   502	        keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH).edit().clear().apply()
   503	        keyStoreManager.prefs(KeyStoreManager.PREFS_CONTACTS).edit().clear().apply()
   504	    }
   505	
   506	    private fun onSessionPublished() {
   507	        synchronized(transportLock) {
   508	            applyTransportLocked(transportResolver.state.value)
   509	        }
   510	        lemonDropVeilController.onUnlocked()
   610	    }
   611	    var unlocked by remember { mutableStateOf(container.session.value != null) }
   612	    var lockError by remember { mutableStateOf<String?>(null) }
   613	    var unlocking by remember { mutableStateOf(false) }
   614	    // Routing truth (§0): a vault image present → UNLOCK, absent → SETUP. Flips true the
   615	    // instant a create succeeds; otherwise unchanged for the process lifetime.
   616	    var vaultExists by remember { mutableStateOf(container.hasVault()) }
   617	    // OBSERVED from the container's process-scoped flow (round 11, Gemini): a rotation
   618	    // mid-create re-attaches the spinner to the still-running create, and a create that fails
   619	    // after the rotation releases it here too (a seeded snapshot would strand the spinner).
   620	    val creating by container.vaultCreating.collectAsState()
   621	    var createError by remember { mutableStateOf<String?>(null) }
   622	    // Route.DeleteIncomplete retry plumbing: single-flight destroy retry off the main thread.
   623	    // Success is judged by disk truth (files gone + marker retired), same as the delete handler.
   624	    var deleteRetrying by remember { mutableStateOf(false) }
   625	    var deleteRetryFailed by remember { mutableStateOf(false) }
   626	    val onRetryDestroy: () -> Unit = retry@{
   627	        if (deleteRetrying) return@retry
   628	        deleteRetrying = true
   629	        deleteRetryFailed = false
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
   821	    // Biometric availability for the lock-screen affordance and the veil CTA.
   822	    val biometricUnlockAvailable = vaultExists && biometricEnabled && canAuthenticateStrong
   823	
   824	    // Biometric unlock (§2): BIOMETRIC_STRONG CryptoObject only. Invalidation (a new
   825	    // enrollment) drops to the passphrase field with an honest note, clears the dead wrap, and
  1000	                        // file deletion still covers that case.
  1001	                        runCatching { live.signalStore.wipe() }
  1002	                        // Synchronous session teardown: runtime.close() reseals the image one last
  1003	                        // time. destroyVault (below) then deletes it — ordering is load-bearing.
  1004	                        container.unlockController.lockIf(live)
  1005	                    },
  1006	                    // The load-bearing no-remanence step: delete vault.bin/vault.dek + biometric
  1007	                    // wrap/key. Runs AFTER the reseal, in completeTerminalWipe's finally, so it can
  1008	                    // never be skipped. THROWS VaultImageException.DestroyFailed if a file survives.
  1009	                    destroyVault = { container.destroyVaultForAccountDeletion() },
  1010	                    releaseGate = { container.unlockController.endTerminalWipe() },
  1011	                )
  1012	            } catch (c: kotlinx.coroutines.CancellationException) {
  1013	                throw c
  1014	            } catch (t: Throwable) {
  1015	                // DestroyFailed (a surviving file) or an unexpected teardown throw — either way
  1016	                // the routing below derives from disk truth. releaseGate already ran in
  1017	                // completeTerminalWipe's outermost finally, so the unlock gate is not stranded.
  1018	            } finally {
  1019	                // This callback runs on the coordinator's background (confined) dispatcher, so the
  1020	                // Compose-state reconcile is marshaled to Main (round 12d, Gemini). Main.immediate
  1021	                // + container.scope so a rotation mid-wipe cannot cancel it. The disk-truth reads
  1022	                // (hasVault / vaultDestroyPending — fast stats under imageLock) run on Main here,
  1023	                // as they already do from Splash routing. The session→route reconciler is the
  1024	                // parallel main-thread backstop: lockIf published session=null above, so it also
  1025	                // derives the same route from the same disk truth — the two cannot disagree.
  1026	                container.scope.launch(Dispatchers.Main.immediate) {
  1027	                    identityFingerprint = null
  1028	                    unlocked = false
  1029	                    lockError = null
  1030	                    vaultExists = container.hasVault()
  1031	                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
  1032	                        // Destroy CONFIRMED (files gone, both markers retired) → fresh-install state.
  1033	                        Route.Onboarding
  1034	                    } else {
  1035	                        // The image (or the server-delete-confirmed marker) survives: the server
  1036	                        // account IS gone, so the only honest route is "finish deleting" with a
  1037	                        // direct retry — NEVER the lock gate (see Route.DeleteIncomplete).
  1038	                        Route.DeleteIncomplete
  1039	                    }
  1040	                }
  1041	            }
  1042	            },
  1043	        )
  1044	    }
  1045	
  1046	    // Reconcile an interrupted account deletion (round 14, F1). A delete-intent marker that
  1047	    // survived a crash means a delete was INITIATED but never durably confirmed — the account may
  1048	    // or may not be gone server-side. On the first LIVE session after such a boot (auth is
  1049	    // retained, since round 14 no longer clears it early), retry the authenticated DELETE via the
  1050	    // same handler: an idempotent 404 (already gone) → confirm + destroy; a success → confirm +
  1051	    // destroy; any not-confirmed outcome surfaces + KEEPS the intent for the next unlock. Keyed on
  1052	    // the session instance so it runs once per unlock; a confirmed reconcile tears the session
  1053	    // down (won't re-fire). The boot path does NOT assume auth is present — a token-less DELETE
  1054	    // returns 401 → DEFINITE_FAILURE → surfaced, not silently abandoned.
  1055	    LaunchedEffect(session) {
  1056	        if (session != null && container.vaultDeleteIntentPending()) {
  1057	            onDeleteAccount()
  1058	        }
  1059	    }
  1060	
  1125	                    identityFingerprint = identityFingerprint,
  1126	                )
  1127	        }
  1128	        return
  1129	    }
  1130	
  1131	    BackHandler(enabled = route !is Route.ChatList && unlocked) {
  1132	        route = when (val current = route) {
  1133	            is Route.Verify -> Route.Chat(current.conversationId)
  1134	            is Route.Diagnostics -> Route.Settings
  1135	            else -> Route.ChatList
  1136	        }
  1137	    }
  1138	
  1139	    Crossfade(
  1140	        targetState = route,
  1141	        animationSpec = tween(Motion.DurationBaseMs, easing = Motion.EasingDefault),
  1142	        label = "rootNavigation",
  1143	    ) { current ->
  1144	        when (current) {
  1145	            // Vault-only routing (§0): image present → unlock gate, absent → setup. NO
  1146	            // silent auto-unlock.
  1147	            Route.Splash -> SplashScreen(
  1148	                onFinished = {
  1149	                    route = when {
  1150	                        // SERVER delete CONFIRMED (round 13): the account is provably gone, so
  1151	                        // resume FINISHING the local destroy — never the unlock gate over a vault
  1152	                        // whose account no longer exists (see Route.DeleteIncomplete).
  1153	                        container.serverDeleteConfirmed() -> Route.DeleteIncomplete
  1154	                        // A mere delete-INTENT (crash mid-delete, server outcome unknown) does NOT
  1155	                        // authorise destruction and is NOT abandoned here (round 14, F1): the vault
  1156	                        // is valid and the account may still exist. Route to normal unlock; the
  1157	                        // post-unlock reconcile (see the intent LaunchedEffect) retries the
  1158	                        // authenticated DELETE. Splash never clears intent and never auto-destroys.
  1159	                        vaultExists -> Route.Locked
  1160	                        else -> Route.Onboarding
  1161	                    }
  1162	                },
  1163	            )
  1164	
  1165	            Route.Onboarding -> OnboardingScreen(
  1166	                onCreateVault = onCreateVault,
  1167	                creating = creating,
  1168	                createError = createError,
  1169	            )
  1170	
   116	     */
   117	    private val flushBeforeAck: suspend () -> Unit = {},
   118	    /**
   119	     * Persist the account-deletion INTENT durably (the `vault.delete-intent` marker) — the FIRST
   120	     * step of [deleteAccountAndWipe], before the server-delete request leaves the device. Means
   121	     * ONLY "a delete was initiated"; it NEVER authorises local destruction (round 13). MUST THROW
   122	     * if it cannot be made durable — the delete then aborts without touching the server. Production
   123	     * supplies [AppContainer.markVaultDeleteIntent]; default no-op for the legacy path (no vault).
   124	     */
   125	    private val persistDeleteIntent: () -> Unit = {},
   126	    /**
   127	     * Persist SERVER-DELETE-CONFIRMED durably (the `vault.delete-confirmed` marker) — written ONLY
   128	     * after [ApiClient.deleteAccount] returns [ApiClient.AccountDeleteResult.CONFIRMED_GONE], and
   129	     * REQUIRED-durable (round 14, F1): it MUST throw if it cannot be made durable so the caller
   130	     * never tears down / clears auth over an un-recorded confirmation. This is the ONLY marker that
   131	     * authorises the unlink-only DeleteIncomplete auto-destroy. Production supplies
   132	     * [AppContainer.markServerDeleteConfirmed].
   133	     */
   134	    private val persistServerDeleteConfirmed: () -> Unit = {},
   135	    /**
   136	     * Whether the DURABLE delete-intent marker is present (production:
   137	     * [AppContainer.hasVaultDeleteIntentMarker]). This is the durable auth-protection signal that
   138	     * [onSessionRevoked] honors (round 16, R15-P2): its true-window equals the intent marker's
   139	     * on-disk lifetime — spanning not-confirmed exits AND process restart — which the process-local
   140	     * [deleteInFlight] flag alone could not. Reads a file stat under the image lock; called only on
   141	     * the rare revoke path.
   142	     */
   143	    private val intentMarkerPresent: () -> Boolean = { false },
   144	) : WsClient.Listener {
   145	
   146	    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
   147	    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()
   148	
   149	    /**
   150	     * True while the app is unlocked and EXPECTS to be connected — set in
  1390	          // finally on EVERY exit so a throw can never latch it on. Set BEFORE the DELETE and held
  1391	          // through destroy() (which removes auth with the vault, after which a clear is moot).
  1392	          deleteInFlight = true
  1393	          try {
  1394	            // 1. Persist the deletion INTENT durably FIRST — before api.deleteAccount() can leave
  1395	            // the device. It means ONLY "delete initiated" and NEVER authorises destruction, so a
  1396	            // crash here leaves a fully valid, unlockable vault (round 13). If it can't be made
  1397	            // durable, ABORT untouched.
  1398	            val intentDurable = try {
  1399	                persistDeleteIntent()
  1400	                true
  1401	            } catch (c: CancellationException) {
  1402	                throw c
  1403	            } catch (_: Throwable) {
  1404	                false
  1405	            }
  1406	            if (!intentDurable) {
  1407	                onIntentNotDurable()
  1408	                return@launch
  1409	            }
  1410	            // 2. Server delete, session STILL FULLY LIVE (so a not-confirmed outcome can resume
  1411	            // normally, and a retry stays authenticated). Returns a DEFINITE result — never a
  1412	            // swallowed throw.
  1413	            val result = try {
  1414	                api.deleteAccount()
  1415	            } catch (c: CancellationException) {
  1416	                throw c
  1417	            } catch (_: Throwable) {
  1418	                ApiClient.AccountDeleteResult.AMBIGUOUS
  1419	            }
  1420	            if (result != ApiClient.AccountDeleteResult.CONFIRMED_GONE) {
  1421	                // NOT gone: destroy NOTHING and KEEP the intent marker — never silently abandon
  1422	                // (round 14, F1). A DEFINITE failure is an auth/permission problem (the account
  1423	                // still exists); an AMBIGUOUS outcome is offline/5xx. Either way the session stays
  1424	                // live and the intent persists, so the next unlock's reconcile repeats the DELETE.
  1425	                onNotConfirmed(result == ApiClient.AccountDeleteResult.DEFINITE_FAILURE)
  1426	                return@launch
  1427	            }
  1428	            // 3. CONFIRMED gone: record the confirmation REQUIRED-durable (round 14, F1 — no more
  1429	            // best-effort swallow). Until vault.delete-confirmed is durable, a crash would strand a
  1430	            // gone-server-account against a live vault with no auto-destroy authorization. On a
  1431	            // non-durable write, tear down NOTHING and clear NO auth: keep the session + the intent
  1432	            // marker so the next unlock's reconcile repeats the (idempotent-404) DELETE and records
  1433	            // confirmation. Auth is NOT cleared anywhere in this flow now — it is vault-backed and
  1434	            // destroyed with the vault by destroy(), which also keeps it available for the reconcile.
  1435	            val confirmedDurable = try {
  1436	                persistServerDeleteConfirmed()
  1437	                true
  1438	            } catch (c: CancellationException) {
  1439	                throw c
  1440	            } catch (_: Throwable) {
  1441	                false
  1442	            }
  1443	            if (!confirmedDurable) {
  1444	                onConfirmedNotDurable()
  1445	                return@launch
  1446	            }
  1447	            // 4. Confirmation is durable — only now tear the session/RAM down. From here the server
  1448	            // account is gone AND recorded, so destruction is unconditionally safe and recoverable.
  1449	            acceptingDeliveries = false
  1450	            _linking.value = false
  1451	            linkJob?.cancel()
  1452	            ws.disconnect()
  1453	            messages.clearAll()
  1454	            conversations.clearAll()
  1455	            // Teardown hook: no re-fire job or fire state survives the wipe.
  1456	            notificationScheduler.cancelAll()
  1457	            onConfirmed()
  1458	          } finally {
  1459	            deleteInFlight = false
  1460	          }
  1461	        }
  1462	    }
  1463	
  1464	    // -- inbound WebSocket events ---------------------------------------------
  1465	
  1466	    override fun onMessageDeliver(envelope: MessageEnvelope) {
  1467	        scope.launch(confined) {
  1468	            runCatching {
  1469	                // A straggler from a DELETED contact must not be decrypted:
  1470	                //  - a normal (non-PreKey) message has no session and would throw

exec
/bin/bash -lc "nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/KeySlot.kt
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultPayload.kt 2>/dev/null || true
nl -ba apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt | sed -n '900,1055p'
rg -n \"clearDeleteIntent\\(|markDeleteIntent\\(|markServerDeleteConfirmed\\(|serverDeleteConfirmed\\(|deleteIntentPending\\(|hasDeleteIntentMarker\\(\" --glob '*.kt' apps/android/app/src | sort
rg -n \"addVaultSlot\\(|addVaultToImage\\(\" --glob '*.kt' --glob '"'!**/test/**'"' --glob '"'!**/androidTest/**'"' ." in /root/zitrone
 succeeded in 0ms:
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
    11	/**
    12	 * Key-slot architecture for the plausible-deniability storage layer — an exact
    13	 * Kotlin mirror of packages/crypto/src/vault.ts. This file holds the constants
    14	 * and the two data holders (KeySlot, VaultUnlock); the slot operations live in
    15	 * VaultSlots.kt.
    16	 *
    17	 * Two properties are load-bearing and non-negotiable, identical to the web
    18	 * reference:
    19	 *
    20	 *  1. The integer number of vaults is never stored. Every disk image contains
    21	 *     exactly SLOT_COUNT slots; unused slots hold uniformly random bytes that
    22	 *     are byte-for-byte indistinguishable from a real wrapped key. A slot that
    23	 *     fails to decrypt is indistinguishable from a wrong passphrase, and the
    24	 *     count of real vaults is unknowable from the stored material.
    25	 *
    26	 *  2. Every passphrase attempt does identical work. tryPassphrase derives a key
    27	 *     for and attempts to unwrap ALL slots with no early exit, so the
    28	 *     wall-clock time is the same whether the passphrase matches slot 0, slot
    29	 *     N, or nothing.
    30	 *
    31	 * SLOT-AGNOSTIC everywhere: nothing here (or in the sibling files) names a slot
    32	 * "real" or "decoy", logs slot structure, or leaves anything a decompiler could
    33	 * read that would reveal how many slots are occupied or where.
    34	 */
    35	
    36	/** Fixed number of slots on every disk image. Real or random, the count is constant. */
    37	const val SLOT_COUNT: Int = 4
    38	
    39	/** Argon2id salt length, bytes. Mirrors kdf.ts SALT_BYTES. */
    40	const val SALT_BYTES: Int = 16
    41	
    42	/** Derived-key / vault-key length, bytes. Mirrors kdf.ts MASTER_KEY_BYTES. */
    43	const val MASTER_KEY_BYTES: Int = 32
    44	
    45	/** Length of the vault key the slots protect. */
    46	const val VAULT_KEY_BYTES: Int = 32
    47	
    48	/** AEAD nonce length for both layers (AES-256-GCM). Mirrors aead.ts NONCE_BYTES. */
    49	const val NONCE_BYTES: Int = 12
    50	
    51	/** AES-256-GCM authentication tag length, bytes. */
    52	const val AEAD_TAG_BYTES: Int = 16
    53	
    54	/**
    55	 * Length of a wrapped vault key: nonce(12) + ciphertext(32) + GCM tag(16) = 60.
    56	 * Same expression as vault.ts WRAPPED_KEY_BYTES (12 + MASTER_KEY_BYTES + 16).
    57	 */
    58	const val WRAPPED_KEY_BYTES: Int = NONCE_BYTES + MASTER_KEY_BYTES + AEAD_TAG_BYTES
    59	
    60	/**
    61	 * Associated data binding a wrapped key to its purpose. Intentionally generic —
    62	 * it names nothing about vault ordering, count, or "decoy" status. Byte-for-byte
    63	 * equal to vault.ts SLOT_AD = utf8("Zitrone-Vault-Slot-v1").
    64	 */
    65	val SLOT_AD: ByteArray = "Zitrone-Vault-Slot-v1".toByteArray(Charsets.UTF_8)
    66	
    67	/**
    68	 * One key slot as it sits on disk: a salt and a wrapped key. Both fields are
    69	 * always present and always the same size, whether the slot is real or filler.
    70	 */
    71	class KeySlot(
    72	    /** 16-byte Argon2id salt. */
    73	    val salt: ByteArray,
    74	    /** AES-256-GCM(masterKey, vaultKey): nonce || ciphertext || tag. */
    75	    val wrapped: ByteArray,
    76	) {
    77	    init {
    78	        require(salt.size == SALT_BYTES) { "slot salt must be $SALT_BYTES bytes" }
    79	        require(wrapped.size == WRAPPED_KEY_BYTES) { "wrapped key must be $WRAPPED_KEY_BYTES bytes" }
    80	    }
    81	}
    82	
    83	/** Result of a successful unlock. slotIndex is for the caller's bookkeeping only. */
    84	class VaultUnlock(
    85	    val vaultKey: ByteArray,
    86	    val slotIndex: Int,
    87	)
    88	
    89	/**
    90	 * Pluggable key deriver — defaults to Argon2id (see [argon2idDeriver]). Injectable
    91	 * so timing-parity tests can substitute a fast, deterministic stand-in without
    92	 * weakening production behavior. Mirrors vault.ts `KeyDeriver`.
    93	 *
    94	 * NOTE: the production deriver runs SLOT_COUNT × 64 MiB Argon2id per unlock and
    95	 * is CPU-heavy; see [tryPassphrase] and [argon2idDeriver].
    96	 *
    97	 * PASSPHRASE TYPE (deliberate): the passphrase is a `String`, mirroring the web
    98	 * reference (a JS string). A JVM `String` is immutable and unwipeable, so it can
    99	 * linger in heap until GC — a known, modest memory-forensics weakness. The
   100	 * *derived* secrets (master keys, vault keys, and the transient UTF-8 bytes) ARE
   101	 * wiped. Moving the whole passphrase path to `CharArray` for wipeability is an
   102	 * available hardening, but it is an API-wide change and is capped by Android's
   103	 * input stack (Compose `TextField` hands you a `String` regardless); that
   104	 * tradeoff is a P1b/router-layer decision, not made here.
   105	 */
   106	typealias KeyDeriver = (passphrase: String, salt: ByteArray) -> ByteArray
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
   900	     *
   901	     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
   902	     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
   903	     * are best-effort; even if one returns false the RAM state is still wiped and the
   904	     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
   905	     * never invokes a VaultSession, so it introduces no reverse lock nesting.
   906	     *
   907	     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
   908	     * filesystem error just as it does on an already-absent file, so its boolean cannot be
   909	     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
   910	     * either SURVIVES, the full-crypto image is still on disk, so it throws
   911	     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
   912	     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
   913	     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
   914	     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
   915	     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
   916	     */
   917	    /**
   918	     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
   919	     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
   920	     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
   921	     * (a crash/failure before the server delete auto-destroyed a still-live-account vault).
   922	     *
   923	     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
   924	     *    ONLY "a delete was initiated" — it NEVER triggers local destruction. A crash here leaves a
   925	     *    fully valid, unlockable vault whose server account may still exist.
   926	     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
   927	     *    `api.deleteAccount()` returns a definite gone (2xx / 404). ONLY this marker authorises the
   928	     *    unlink-only [Route.DeleteIncomplete] auto-destroy: when it is present, the server account
   929	     *    is provably gone, so destroying the local copy is always safe.
   930	     *
   931	     * Both are existence-signals made crash-durable with a dir-fsync (fail-closed on non-durable).
   932	     */
   933	    fun markDeleteIntent() {
   934	        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
   935	    }
   936	
   937	    fun markServerDeleteConfirmed() {
   938	        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
   939	    }
   940	
   941	    /**
   942	     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
   943	     * and the marker RE-STATTED absent — [File.delete] returns false on an I/O failure just like on
   944	     * an already-absent file, so its bool cannot be trusted. Throws [VaultImageException.DestroyFailed]
   945	     * if the marker survives (unlink failed) or the retire is not crash-durable; a no-op (already
   946	     * absent) succeeds.
   947	     */
   948	    fun clearDeleteIntent() {
   949	        imageLock.withLock {
   950	            // Tristate (round 15, R14-2): only a CONFIRMED absence (Files.notExists) is a no-op;
   951	            // present-or-indeterminate falls through to the durable clear + verify below. Using
   952	            // File.exists() here would skip clearing a present-but-unstatable marker.
   953	            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
   954	            deleteIntentFile.delete()
   955	            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
   956	                throw VaultImageException.DestroyFailed()
   957	            }
   958	        }
   959	    }
   960	
   961	    /**
   962	     * Delete BOTH delete markers and confirm the retire crash-durably by RE-STAT — never by
   963	     * trusting [File.delete]'s bool (false on an I/O failure too). Returns true iff both markers
   964	     * re-stat ABSENT AND the directory fsync is [DirSyncResult.DURABLE]. Idempotent (already-absent
   965	     * markers succeed). The single choke point for the marker-retirement discipline used by
   966	     * [create] (F2) and [destroy] (F4). Caller must hold [imageLock].
   967	     */
   968	    private fun clearBothMarkersDurably(): Boolean {
   969	        deleteIntentFile.delete()
   970	        serverDeletedFile.delete()
   971	        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
   972	        // TRISTATE re-stat (round 15, R14-2): File.exists()==false conflates "absent" with "stat
   973	        // could not be determined" (I/O/permission failure), so trusting it would report a marker
   974	        // that SURVIVED an unlink as gone. Files.notExists returns true ONLY when the path is
   975	        // confirmed absent — present OR indeterminate both yield false, so the clear is proven
   976	        // only on a definite absence (fail-closed).
   977	        return durable &&
   978	            Files.notExists(deleteIntentFile.toPath()) &&
   979	            Files.notExists(serverDeletedFile.toPath())
   980	    }
   981	
   982	    /** Create [file] + dir-fsync it; throw [VaultImageException.DestroyFailed] if not durable. */
   983	    private fun writeDurableMarker(file: File) {
   984	        val durable = runCatching {
   985	            file.createNewFile()
   986	            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
   987	        }.getOrDefault(false)
   988	        if (!durable) {
   989	            throw VaultImageException.DestroyFailed()
   990	        }
   991	    }
   992	
   993	    fun destroy() {
   994	        imageLock.withLock {
   995	            // Wipe live key material + drop the cached image FIRST — before even the marker gate
   996	            // can throw — so no DEK/plaintext-adjacent state is retained on ANY exit. A destroy
   997	            // request is terminal for this store's usefulness regardless of outcome (the session
   998	            // is already torn down); the retry path never needs the cached DEK.
   999	            dek?.let { wipe(it) }
  1000	            dek = null
  1001	            canonical = null
  1002	            // CONFIRMED MARKER before the unlinks (crash/restart continuity): reaching destroy()
  1003	            // means the server account is confirmed gone, so write `vault.delete-confirmed`
  1004	            // durably BEFORE unlinking. A crash mid-unlink then restarts into
  1005	            // [Route.DeleteIncomplete] (the CONFIRMED marker is present) and re-runs this
  1006	            // idempotent destroy — never a lock gate over a gone account. REQUIRED-DURABLE: if it
  1007	            // can't be written+fsynced, ABORT with the vault files untouched (throw). Idempotent
  1008	            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
  1009	            writeDurableMarker(serverDeletedFile)
  1010	            // Remove BOTH persisted files and any interrupted-write temps. delete() is
  1011	            // best-effort and never throws on a missing file (returns false) — idempotent.
  1012	            binFile.delete()
  1013	            dekFile.delete()
  1014	            deleteLeftoverTmp(binFile)
  1015	            deleteLeftoverTmp(dekFile)
  1016	            // Release the single-instance registration so a fresh create() may re-open this
  1017	            // directory in the SAME process (re-onboard after account deletion).
  1018	            unregister()
  1019	            // VERIFY everything image-bearing is actually GONE (see kdoc): delete()'s bool is
  1020	            // false on an I/O error too, so re-stat instead. The TEMPS are part of the check
  1021	            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
  1022	            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
  1023	            // verify exists to catch, an encrypted image copy could survive as a temp while the
  1024	            // primaries are gone. A surviving file → destruction FAILED → throw; account-delete
  1025	            // treats this as NOT-deleted. exists()==false (already-absent) does NOT throw,
  1026	            // keeping destroy() idempotent.
  1027	            if (binFile.exists() || dekFile.exists() ||
  1028	                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
  1029	            ) {
  1030	                throw VaultImageException.DestroyFailed()
  1031	            }
  1032	            // Make the unlinks CRASH-DURABLE before retiring the markers (round 8, Codex): the
  1033	            // exists() re-stat proves only the current namespace, not what a journal replay
  1034	            // restores. Without this fsync, a crash could resurrect vault.bin/vault.dek while the
  1035	            // markers' own (later) unlink survived — restarting into DeleteIncomplete over a
  1036	            // now-present image, the exact state the markers exist to signal. A non-durable sync
  1037	            // keeps the markers (throw → retry re-runs the idempotent destroy), never false success.
  1038	            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
  1039	                throw VaultImageException.DestroyFailed()
  1040	            }
  1041	            // Unlinks confirmed durable — retire BOTH markers, verified by RE-STAT + a required
  1042	            // fsync (round 13 Grok P1-2 / round 14 F4): trusting File.delete()'s bool would let a
  1043	            // silent unlink failure leave a marker that a journal replay resurrects over a later
  1044	            // SUCCESSOR vault → DeleteIncomplete → auto-destroy of a valid re-onboarded vault. A
  1045	            // marker that survives the delete, or a non-durable retire, is a FAILED destroy (throw):
  1046	            // marker-present + files-absent is the safe stuck state (a retry re-stats the files
  1047	            // absent and re-runs the retire). Self-healing over the empty image, now also correct.
  1048	            if (!clearBothMarkersDurably()) {
  1049	                throw VaultImageException.DestroyFailed()
  1050	            }
  1051	        }
  1052	    }
  1053	
  1054	    /**
  1055	     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1031:                    route = if (!vaultExists && !container.serverDeleteConfirmed()) {
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1153:                        container.serverDeleteConfirmed() -> Route.DeleteIncomplete
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:633:                !container.hasVault() && !container.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:722:                    container.serverDeleteConfirmed() -> Route.DeleteIncomplete
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:181:    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:189:    fun vaultDeleteIntentPending(): Boolean = imageStore.deleteIntentPending()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:192:    fun markVaultDeleteIntent() = imageStore.markDeleteIntent()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:195:    fun markServerDeleteConfirmed() = imageStore.markServerDeleteConfirmed()
apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:198:    fun hasVaultDeleteIntentMarker() = imageStore.hasDeleteIntentMarker()
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1060:    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1069:    fun deleteIntentPending(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:1092:    fun hasDeleteIntentMarker(): Boolean =
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:933:    fun markDeleteIntent() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:937:    fun markServerDeleteConfirmed() {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:948:    fun clearDeleteIntent() {
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:273:        s.markDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1006:        assertFalse("no marker before any delete", store.hasDeleteIntentMarker())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1008:        store.markDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1009:        assertTrue("present after intent write", store.hasDeleteIntentMarker())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1010:        assertTrue("deleteIntentPending too (confirmed absent)", store.deleteIntentPending())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1013:        // STILL present, so the auth guard stays true — but deleteIntentPending() (intent && !confirmed)
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1016:        store.markServerDeleteConfirmed()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1017:        assertTrue("intent marker STILL present through the confirmed window", store.hasDeleteIntentMarker())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1018:        assertFalse("deleteIntentPending is now false (confirmed present)", store.deleteIntentPending())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1022:        assertFalse("intent marker gone after destroy", store.hasDeleteIntentMarker())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1036:        assertFalse("a confirmed destroy leaves no marker", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1080:        assertThrows(VaultImageException.DestroyFailed::class.java) { s1.clearDeleteIntent() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1087:        assertThrows(VaultImageException.DestroyFailed::class.java) { s2.clearDeleteIntent() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:1090:        newStore(tmp.newFolder()).clearDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:875:        assertTrue("serverDeleteConfirmed survives the failed unlink", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:882:        assertFalse("marker retired after the confirmed destroy", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:897:        assertTrue("confirmed marker survives — deletion is not complete", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:907:        store.markDeleteIntent()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:908:        assertTrue("intent pending", store.deleteIntentPending())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:909:        assertFalse("intent does NOT authorise destroy", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:914:        // deleteIntentPending() reports false (confirmed supersedes intent).
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:915:        store.markServerDeleteConfirmed()
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:916:        assertTrue("confirmed authorises destroy", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:917:        assertFalse("intent superseded by confirmed", store.deleteIntentPending())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:921:        assertFalse("destroy retired confirmed", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:922:        assertFalse("destroy retired intent", store.deleteIntentPending())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:932:        assertThrows(VaultImageException.DestroyFailed::class.java) { store.markDeleteIntent() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:933:        assertThrows(VaultImageException.DestroyFailed::class.java) { store.markServerDeleteConfirmed() }
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:964:        assertTrue("confirmed marker kept until the unlinks are DURABLE", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:993:        assertFalse("stale confirmed marker cleared by create()", store.serverDeleteConfirmed())
apps/android/app/src/test/java/com/zitrone/app/VaultImageStoreTest.kt:994:        assertFalse("no lingering intent either", store.deleteIntentPending())
./apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:214:fun addVaultToImage(
./apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImage.kt:223:    val added = addVaultSlot(decoded.slots, occupied, passphrase, ops, deriver)
./apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:126:fun addVaultSlot(

exec
/bin/bash -lc "rg -n \"attemptUnlockOrAdd|UnlockOrAdd\" apps/android/app/src/main apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt
sed -n '1,240p' /root/l00prite/pr1-attemptUnlockOrAdd-spec.md
rg -n \"INVARIANT 6|0\\.9\\.2 REVISED|delete-intent|delete-confirmed|attemptUnlockOrAdd\" /root/l00prite/zitrone-vault-ledger.md | head -80
git diff 321b358"'^ 321b358 -- apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt' in /root/zitrone
 succeeded in 0ms:
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:21:import com.zitrone.app.crypto.vault.UnlockOrAdd
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:49: * PR-1 tests for [VaultImageStore.attemptUnlockOrAdd] (0.9.2 second vault + Pucker Burn) and the
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:56:class AttemptUnlockOrAddTest {
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:99:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true) // create ignored — match wins
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:100:        assertTrue(r is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:101:        assertArrayEquals(content, (r as UnlockOrAdd.Unlocked).open.payloadPlaintext)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:109:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:110:        assertTrue(r is UnlockOrAdd.Created)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:111:        assertArrayEquals(genesis, (r as UnlockOrAdd.Created).open.payloadPlaintext)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:116:        assertTrue(fresh.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:125:        val r = s.attemptUnlockOrAdd("nope", genesis, create = false)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:126:        assertEquals(UnlockOrAdd.Rejected, r)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:136:        val r = s.attemptUnlockOrAdd("passA", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:137:        assertTrue(r is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:168:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = true))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:178:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("random", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:198:        assertEquals(UnlockOrAdd.Burn, fresh.attemptUnlockOrAdd("burn-me", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:217:            fresh.attemptUnlockOrAdd("passA", genesis, create = false)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:232:            val r = s.attemptUnlockOrAdd("B$it", genesis, create = true) as UnlockOrAdd.Created
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:258:        assertTrue(s.attemptUnlockOrAdd("passA", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:259:        val r = s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:260:        assertTrue(r is UnlockOrAdd.Created)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:262:        assertEquals(UnlockOrAdd.Rejected, s.attemptUnlockOrAdd("passA", genesis, create = false))
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:263:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:275:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = true) is UnlockOrAdd.Created)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:288:            s.attemptUnlockOrAdd("passB", genesis, create = true)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:291:        assertTrue(s.attemptUnlockOrAdd("passB", genesis, create = false) is UnlockOrAdd.Unlocked)
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:321:            call = { it.attemptUnlockOrAdd("passA", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:324:            call = { it.attemptUnlockOrAdd("nope", genesis, create = false) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:327:            call = { it.attemptUnlockOrAdd("passB", genesis, create = true) })
apps/android/app/src/test/java/com/zitrone/app/AttemptUnlockOrAddTest.kt:330:            call = { it.attemptUnlockOrAdd("burn-me", genesis, create = false) })
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:45: * ([VaultImageStore.attemptUnlockOrAdd]). Draws the same 4 CSPRNG bytes as [randomIndex]
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultSlots.kt:122: * creation path is [VaultImageStore.attemptUnlockOrAdd], which reimplements placement
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:142: * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:146:sealed interface UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:148:    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:155:    data object Burn : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:158:    data class Created(val open: VaultOpen) : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:161:    data object Rejected : UnlockOrAdd
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:607:     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:608:     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:611:     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:612:     * [UnlockOrAdd.Rejected] having written nothing.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:615:     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:630:     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:637:    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:664:                        UnlockOrAdd.Burn
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:680:                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:718:                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:728:                        UnlockOrAdd.Rejected
# PR-1 SPEC — `VaultImageStore.attemptUnlockOrAdd` (0.9.2-beta: second vault + Pucker Burn)

**Status:** APPROVED (user, 2026-07-24T11:52Z) — implementation authorized WITH the blocking §10.1
resolution (option (a), in-scope) and the two §9 review-scope amendments below.
**Author:** claude, 2026-07-24 (REVISED — burn-aware; supersedes the earlier double-entry/25% spec).
**Scope owner:** jackofall1232. **Decisions:** see vault-ledger top block (2026-07-24 REVISED).

---

## 0. Scope & non-goals

**In scope (PR-1):** one new `VaultImageStore` method fusing "try to unlock", "detect a burn match",
and "maybe create a vault" into a single, constant-crypto-work, `imageLock`-atomic operation — plus its
durability, delete-marker, wipe, and **slot-0 (burn) awareness**. Plus a companion change so `create()`
places the everyday vault in the 1–3 pool (never slot 0). This is the sole **new writer** to the durable
image in 0.9.2.

**Out of scope (later / sibling PRs):**
- Triple-entry gate state machine + uninterrupted-sequence guard + timing tests + router fusion → **PR-2**.
- MainActivity wiring, biometric A-only guard (OQ4), doc reconciliation (OQ5) → **PR-3**.
- **Pucker Burn SETUP UX** (settings entry, permanence ack) and **burn WIPE execution** → **sibling PRs**.
  PR-1 makes the store burn-AWARE (returns a `Burn` outcome on a slot-0 match) but does NOT arm burn or
  perform the wipe.
- Per-vault destruction → separate future phase; `destroy()` stays whole-image (OQ3).

**Framing:** `SECURITY_MODEL.md` is already honest (PR-F). 0.9.2 flips status to "two vaults creatable"
and adds the new burn/limitation disclosures.

---

## 1. Slot model (NEW — burn changes placement, not the byte format)

`SLOT_COUNT = 4` (unchanged; raising to 8 rejected — see ledger). Byte format unchanged:
`version(1) ‖ 4×[salt(16)‖wrapped(60)] ‖ 4×payload(256 KiB)`. What changes is **placement semantics**:

```kotlin
const val BURN_SLOT_INDEX = 0                     // reserved for the Pucker Burn credential
val VAULT_SLOT_RANGE = 1 until SLOT_COUNT         // 1..3 — the vault pool
/** Blind vault placement — slot 0 is NEVER chosen. Used by create() AND attemptUnlockOrAdd. */
fun randomVaultSlotIndex(ops) = 1 + randomIndex(SLOT_COUNT - 1, ops)   // 1..3
```

- **Slot 0** is sealed byte-identically to any vault slot (same Argon2id, same structure, same timing).
  Only its *contents* differ (a burn marker, not a VaultState). **Arm-state is stored nowhere** — "armed"
  simply means a passphrase can match slot 0, which is exactly what `tryPassphrase` already tests. An
  examiner cannot tell from structure/timing whether slot 0 is armed. Until burn is set up, slot 0 is
  random filler (indistinguishable), so it never matches.
- **Slots 1–3** hold the vault pool: the everyday vault A (placed by `create()` at `randomVaultSlotIndex`)
  and any created vault B (placed by `attemptUnlockOrAdd` at `randomVaultSlotIndex`).
- **Collision probability is ~1/3 (~33%)**, not 25% (OQ2 corrected): blind placement is over 3 slots.
- **Full-pool overwrite:** if slots 1–3 all hold real vaults, any further creation overwrites one with
  certainty and no warning (ZK: the app cannot detect a full pool). Burn (slot 0) is never touched by
  vault creation, so duress protection survives even a full pool. Documented in SECURITY_MODEL (PR-3).

**Companion change to `create()` / `createVaultSlots`:** placement changes from `randomIndex(SLOT_COUNT)`
(0..3) to `randomVaultSlotIndex` (1..3). Slot 0 becomes filler at onboarding (unarmed burn). This
diverges from the web reference `vault.ts` (which has no burn slot); acceptable — burn is Android-only
and the byte format is unchanged. **Included in PR-1** because it is the same slot-0-reservation invariant.

---

## 2. WRITER / READER invariant table (built FIRST, per standing discipline)

Durable state: `vault.bin` (image), `vault.dek`, `vault.delete-intent`, `vault.delete-confirmed`.
In-RAM: `canonical`, `dek`. **Auth tokens live INSIDE each slot's VaultState payload — per-slot, not a
device file. Burn arm-state is NOT stored — it is implicit in whether slot 0 is a real sealed slot.**

### Writers

| Writer | Writes | DEK | Markers | Slot 0? | New in 0.9.2? |
|---|---|---|---|---|---|
| `create()` (companion-changed) | bin+dek, fresh image, A placed **in 1–3** | writes new | clears BOTH first | leaves slot 0 as filler | placement changed |
| `writeSealedPayload()` | ONE payload region (existing live slot, always 1–3) | reuse | none | never | no |
| `markDeleteIntent` / `markServerDeleteConfirmed` | a marker | — | writes | — | no |
| `destroy()` | confirmed marker, unlinks bin+dek, clears both | deletes | writes then clears | wipes slot 0 too (whole image) | no |
| `clearDeleteIntent` / `clearBothMarkersDurably` | unlink marker(s) | — | clears | — | no |
| **`attemptUnlockOrAdd()` — Created branch (NEW)** | **bin full re-encode: ONE new slot-table entry + ONE new payload at a 1–3 index; all else byte-identical** | **reuse (never touches dek)** | **clears BOTH first (durable), like `create()`** | **never writes slot 0** | **YES** |
| `attemptUnlockOrAdd()` — Unlocked / **Burn** / Rejected | **nothing on disk** | — | none | reads slot 0 (sweep) but writes nothing | YES (no-op writers) |
| **Pucker Burn SETUP (sibling PR)** | **slot 0's slot entry + payload (arms burn)** | reuse | none (TBD) | **writes slot 0** | future |
| **Burn WIPE (sibling PR)** | whole-image destroy | deletes | TBD (open item 2) | — | future |
| **`retireLegacyImage()` (NEW, §10.1(a))** | **unlinks bin+dek+tmps durably — ONLY after re-proving inner version == 2 under imageLock** | wipes RAM copy; deletes file | **none — format retirement is NOT an account delete; markers untouched** | n/a (whole image) | **YES** |

### Reader change (NEW, §10.1(a)): `open()` version branch

`open()` today maps ANY unexpected inner version to `CorruptImage` (escalate, never recreate). PR-1
splits that reader: inner version == 2 (the KNOWN 0.9.1 format) → NEW `VaultImageException.LegacyImage`
(caller routes to fresh onboarding via `retireLegacyImage()` + create); any OTHER unknown version →
`CorruptImage` as today. The v2 image is NEVER slot-interpreted (no sweep, no slot-0 read) — the branch
fires before any slot material is decoded into use. `retireLegacyImage()` re-proves version==2 itself
(full open-style read under `imageLock`) so a misrouted call can never delete a valid v3 image;
retirement happens only on the explicit onboarding action, not silently at boot.

### Readers (unchanged): `open()`, `unlock()`/`unlockWithKey()`, marker readers, boot Route, `onSessionRevoked→clearTokens` (guarded).

### Invariants preserved (and how)

1. **No `{bin-present, dek-absent}` brick.** Created reuses the existing durable DEK and rewrites only
   `bin`; `dek` never written/deleted → present+durable throughout. Stronger than `create()`'s DEK-first
   barrier (no DEK write at all).
2. **A confirmed marker never coexists with a live successor image.** Created clears BOTH markers durably
   BEFORE writing (mirrors `create()` F2 / round-14); non-durable clear → throw, nothing written.
3. **A plain unlock/burn/reject writes no marker and clears no tokens.** Only Created writes.
4. **Tokens are per-slot, inside the payload.** Created writes only the new vault's (empty genesis)
   payload + its slot entry. It never reads/writes/clears another vault's payload or tokens. The only way
   it affects an existing vault is the accepted **~33% blind clobber** (a documented whole-slot destroy).
5. **Slot 0 (burn) is never a write target of vault creation.** `randomVaultSlotIndex` yields 1–3 only,
   so no created/everyday vault can land on slot 0, and the burn credential can never be clobbered by
   vault creation. (Duress protection survives even a full vault pool.)
6. **NEW INTERACTION — top review target.** Created clears the device-level `vault.delete-intent`, which
   **cancels a pending A-side delete-reconcile** (OQ3). Realistically only an intent-only marker is
   present at a lock screen during a triple-entry (a confirmed marker routes boot to auto-destroy before
   any lock screen). Fail-safes toward "no destruction." Flagged for the adversarial pass.
7. **Single-writer / lock order.** Entirely under `imageLock`; never calls a `VaultSession` → no reverse
   `flushLock→imageLock` nesting. Sweep + candidate + write are atomic vs other store ops.

---

## 3. Method contract

```kotlin
sealed interface UnlockOrAdd {
    /** An existing VAULT slot (1..3) matched — normal unlock. Router discards the triple-entry. */
    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
    /** Slot 0 matched — the Pucker Burn credential was entered. The APP performs the wipe (sibling PR);
     *  the store performs NO wipe here. Carries nothing (arm-state/contents are not exposed). */
    data object Burn : UnlockOrAdd
    /** No slot matched AND create==true — a new vault was created + persisted durably. */
    data class Created(val open: VaultOpen) : UnlockOrAdd
    /** No slot matched AND create==false — indistinguishable wrong-password. */
    data object Rejected : UnlockOrAdd
}

/**
 * Fused unlock / burn-detect / maybe-create. ALWAYS identical heavy crypto regardless of outcome:
 * SLOT_COUNT (=4, incl. slot 0) Argon2id sweep + 1 unconditional candidate-seal Argon2id + exactly one
 * 256 KiB payload GCM + one tiny wrapped-key GCM. A slot match (0..3) ALWAYS wins over [create]; a
 * slot-0 match wins as Burn. CPU-heavy: caller MUST be off-main. Under imageLock; opens from disk if needed.
 *
 * @param passphrase entered passphrase (never logged).
 * @param genesisPayload plaintext to seal into a NEW vault (VaultState.empty() encoded). Caller owns+wipes it.
 * @param create whether a no-match should CREATE a vault (router sets true only on the 3rd consecutive
 *   identical non-matching, uninterrupted entry — PR-2). Ignored on ANY slot match (incl. slot 0).
 * @throws VaultImageException.MissingImage/CorruptImage from open() or an unreadable matched vault payload.
 * @throws VaultImageException.NotDurable create wrote but rename durability unconfirmed.
 * @throws VaultImageException.DestroyFailed the pre-create marker clear was not durable.
 */
fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd
```

---

## 4. Algorithm (exact crypto-op accounting)

All under `imageLock`. `EMPTY = ByteArray(0)`.

```
image   = canonical ?: run { open(); canonical!! }         // may throw Missing/Corrupt
decoded = decodeImage(image)

// (1) SWEEP — ALWAYS. 4 Argon2id (slots 0..3), no early exit. Slot 0 included.
val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)

// (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny (60 B) wrapped-key GCM. Real vault B material on
//     create; pure timing filler otherwise. Placement is over the 1..3 pool (never slot 0).
val candKey       = ops.randomBytes(VAULT_KEY_BYTES)
val candSlotIndex = randomVaultSlotIndex(ops)              // 1..3 — slot 0 excluded (invariant 5)
val candSlot      = sealSlot(passphrase, candKey, ops, deriver)

try {
  when {
    // ── BURN (slot 0 match) WINS ───────────────────────────────────────────────
    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
      wipe(candKey)
      // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload, then discard.
      val pt = runCatching { openPayload(unlock.vaultKey, decoded.payloads[0], ops) }.getOrNull()
      pt?.let { wipe(it) }
      wipe(unlock.vaultKey)
      return Burn                                          // APP wipes (sibling PR); store writes nothing
    }

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

| Outcome | Argon2id | 256 KiB GCM | tiny wrapped-key GCM | ~1 MiB outer GCM + write |
|---|---|---|---|---|
| Unlocked (slot 1–3) | 4 sweep + 1 candidate = **5** | 1 (openPayload) | 1 (candidate sealSlot) | none |
| **Burn (slot 0)** | 4 sweep + 1 candidate = **5** | 1 (openPayload slot 0, discarded) | 1 | none |
| Created | 4 sweep + 1 candidate = **5** | 1 (seal genesis) | 1 | **yes (persist)** |
29:- **PR-1** — `VaultImageStore.attemptUnlockOrAdd(...)` fused writer, **burn-aware** (slot-0 match →
63:  identities (plural). B-creation clears a stale `vault.delete-intent` the same way `create()` does.
127:Spec `/root/l00prite/pr1-attemptUnlockOrAdd-spec.md` approved as written, WITH:
132:  interpretation. attemptUnlockOrAdd's slot-0 semantics must NOT land before this — a v2 image with A at
156:- `attemptUnlockOrAdd(passphrase, genesis, create): UnlockOrAdd{Unlocked,Burn,Created,Rejected}` — one
207:- r14: two-marker split (`vault.delete-intent` vs `vault.delete-confirmed`); confirmed R14-1(P2 onSessionRevoked
diff --git a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
index 37b9c0d..e6c90a0 100644
--- a/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
+++ b/apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt
@@ -63,6 +63,23 @@ sealed class VaultImageException(message: String) : Exception(message) {
      */
     class CorruptImage : VaultImageException("vault image is unreadable")
 
+    /**
+     * The image is present, the outer layer authenticated, and the inner image is a
+     * VALID but PRIOR format version ([LEGACY_IMAGE_VERSION] = v2, the 0.9.1 format).
+     * This is DISTINCT from [CorruptImage] on purpose and is SAFETY-CRITICAL: a v2 image
+     * could hold the everyday vault at slot 0, which v3's burn-slot reservation would
+     * misread as a burn credential and WIPE on the user's own correct passphrase. So a v2
+     * image is NEVER unlocked, NEVER slot-interpreted, and NEVER auto-destroyed at boot —
+     * [open] throws this before any slot material is used, the caller routes to fresh
+     * onboarding, and the retirement of the old file happens only on the deliberate
+     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
+     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
+     * recreate). 0.9.1 was fresh-install-only with no real users, so the blast radius is
+     * test devices — but "we happened to have no users" is not a safety property, so this
+     * fail-closed distinction ships regardless.
+     */
+    class LegacyImage : VaultImageException("vault image is a prior, retired format")
+
     /**
      * A payload write's bytes ARE on disk (the atomic rename — the commit point —
      * landed and its content was fsynced), but the directory-entry fsync that would
@@ -121,6 +138,29 @@ internal const val OUTER_IMAGE_BYTES: Int = IMAGE_BYTES + NONCE_BYTES + AEAD_TAG
  */
 internal enum class DirSyncResult { DURABLE, NOT_DURABLE }
 
+/**
+ * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
+ * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
+ * the CALLER learns only which of the four happened, never which slot or how many exist.
+ */
+sealed interface UnlockOrAdd {
+    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
+    data class Unlocked(val open: VaultOpen) : UnlockOrAdd
+
+    /**
+     * Slot 0 ([BURN_SLOT_INDEX]) matched — the Pucker Burn duress credential was entered.
+     * The APP performs the wipe (a sibling feature); the store performs NO wipe here and
+     * exposes nothing about the burn slot's contents or arm-state.
+     */
+    data object Burn : UnlockOrAdd
+
+    /** No slot matched AND create was requested — a new vault was created + persisted durably. */
+    data class Created(val open: VaultOpen) : UnlockOrAdd
+
+    /** No slot matched AND create was not requested — an indistinguishable wrong passphrase. */
+    data object Rejected : UnlockOrAdd
+}
+
 /**
  * The device-level storage layer for the plausible-deniability vault image. Owns
  * the on-disk canonical image and the envelope that protects it at rest; nothing
@@ -224,6 +264,17 @@ class VaultImageStore internal constructor(
     /** True when a vault image is present on disk (`vault.bin`). */
     fun exists(): Boolean = imageLock.withLock { binFile.exists() }
 
+    /**
+     * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
+     * signal to send a 0.9.1 install to fresh onboarding instead of the lock screen (see
+     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
+     * the outer layer and checks the inner version byte only. Returns false for a current-version image,
+     * a missing image, or anything unreadable (a corrupt image is NOT "legacy" — it routes through the
+     * normal unlock → [CorruptImage] escalation, never to a retire). Does NOT alter store state.
+     */
+    fun isLegacyImage(): Boolean =
+        imageLock.withLock { readInnerVersionOrNull() == LEGACY_IMAGE_VERSION }
+
     /**
      * Read `vault.bin` + `vault.dek`, unwrap the DEK, decrypt the outer layer, and
      * hold the validated inner image as [canonical]. A leftover `.tmp` from an
@@ -317,10 +368,19 @@ class VaultImageStore internal constructor(
                     inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD)
                         ?: throw VaultImageException.CorruptImage()
                     if (inner.size != IMAGE_BYTES) throw VaultImageException.CorruptImage()
-                    // Validate the inner VERSION too, not just the size: an unknown version reads
-                    // as CorruptImage for THIS build. A future format bump MUST add its migration
-                    // path here BEFORE [IMAGE_VERSION] changes, or existing images stop opening.
-                    if (inner[0].toInt() and 0xff != IMAGE_VERSION) throw VaultImageException.CorruptImage()
+                    // Validate the inner VERSION too, not just the size. Three cases (order matters):
+                    //  - current [IMAGE_VERSION] → fall through, install as canonical.
+                    //  - [LEGACY_IMAGE_VERSION] (v2, the 0.9.1 format) → [VaultImageException.LegacyImage],
+                    //    thrown HERE before any slot is decoded/interpreted. SAFETY-CRITICAL: a v2 image
+                    //    may hold the everyday vault at slot 0, which v3 would misread as a burn wipe. The
+                    //    caller routes to fresh onboarding; retirement is deliberate ([retireLegacyImage]).
+                    //  - any OTHER version → [CorruptImage] (unknown/tampered; escalate, never recreate).
+                    // A future format bump MUST add its own branch here BEFORE changing [IMAGE_VERSION].
+                    val innerVersion = inner[0].toInt() and 0xff
+                    if (innerVersion != IMAGE_VERSION) {
+                        if (innerVersion == LEGACY_IMAGE_VERSION) throw VaultImageException.LegacyImage()
+                        throw VaultImageException.CorruptImage()
+                    }
                 } catch (t: Throwable) {
                     wipe(unwrapped)
                     throw t
@@ -532,6 +592,152 @@ class VaultImageStore internal constructor(
         }
     }
 
+    /**
+     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
+     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
+     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
+     * cases apart (the plausible-deniability + duress-credential timing contract):
+     *
+     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
+     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
+     *     (real vault-B material on create, pure timing filler otherwise);
+     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
+     *
+     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
+     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
+     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
+     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
+     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
+     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
+     * [UnlockOrAdd.Rejected] having written nothing.
+     *
+     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
+     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
+     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
+     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
+     *
+     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
+     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
+     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
+     * target, so duress protection survives even a full pool.
+     *
+     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
+     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
+     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
+     *
+     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
+     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
+     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
+     *
+     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
+     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
+     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
+     * create write is not confirmed durable.
+     */
+    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
+        imageLock.withLock {
+            val image = canonical ?: run { open(); canonical!! }
+            val activeDek = dek ?: throw IllegalStateException("vault image not open")
+            val decoded = decodeImage(image)
+
+            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
+            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)
+
+            // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + 1 tiny wrapped-key GCM. Real vault-B material on
+            //     the create branch; pure timing filler (wiped) otherwise. Placement is over the VAULT
+            //     POOL (never slot 0) so a create can never clobber the burn credential.
+            val candKey = ops.randomBytes(VAULT_KEY_BYTES)
+            val candSlotIndex = randomVaultSlotIndex(ops)
+            val candSlot = sealSlot(passphrase, candKey, ops, deriver)
+
+            try {
+                return when {
+                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
+                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
+                        wipe(candKey)
+                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
+                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
+                        // duress credential must never be suppressed by a damaged marker (spec §6).
+                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
+                            .getOrNull()?.let { wipe(it) }
+                        wipe(unlock.vaultKey)
+                        UnlockOrAdd.Burn
+                    }
+
+                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
+                    unlock != null -> {
+                        wipe(candKey)
+                        val pt = try {
+                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
+                        } catch (t: Throwable) {
+                            wipe(unlock.vaultKey)
+                            throw VaultImageException.CorruptImage()
+                        }
+                        if (pt == null) {
+                            wipe(unlock.vaultKey)
+                            throw VaultImageException.CorruptImage()
+                        }
+                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
+                    }
+
+                    // ── CREATE a new vault into a vault-pool slot. ──
+                    create -> {
+                        // Clear stale delete markers durably BEFORE any write (OQ3; mirrors create()).
+                        // Conservative tristate: run the clear unless BOTH are CONFIRMED absent.
+                        val markersConfirmedAbsent =
+                            Files.notExists(deleteIntentFile.toPath()) &&
+                                Files.notExists(serverDeletedFile.toPath())
+                        if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
+                            throw VaultImageException.NotDurable()
+                        }
+                        // The 1×256 KiB payload GCM for this branch.
+                        val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
+                        val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
+                        val newPayloads =
+                            decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
+                        val newInner = encodeImage(VaultImage(newSlots, newPayloads))
+                        // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
+                        // unreachable by construction; the dek is already durable on disk from create().
+                        val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
+                        // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
+                        // rename landed, the result reporting the rename's durability.
+                        val sync = atomicWrite(binFile, outer)
+                        // Rename committed → advance canonical BEFORE the durability check so a later
+                        // splice/attempt never works from stale state even on the NotDurable throw.
+                        canonical = newInner
+                        if (sync != DirSyncResult.DURABLE) {
+                            // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
+                            // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
+                            // canonical, so a later single entry of its passphrase unlocks it via the
+                            // match path (no write needed) — or, if the rename did not survive a crash, it
+                            // is simply absent and re-creatable.
+                            wipe(candKey)
+                            throw VaultImageException.NotDurable()
+                        }
+                        // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
+                        UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
+                    }
+
+                    // ── REJECT — no match, no create. Nothing written. ──
+                    else -> {
+                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
+                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
+                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
+                        wipe(candKey)
+                        wipe(throwaway)
+                        UnlockOrAdd.Rejected
+                    }
+                }
+            } catch (t: Throwable) {
+                // Defensive: ensure no live candidate key is abandoned on any throw. On the Created
+                // (return) path this is not reached; on every other path candKey was already wiped, and a
+                // re-wipe of zeroed bytes is a no-op.
+                wipe(candKey)
+                throw t
+            }
+        }
+    }
+
     /**
      * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
      * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
@@ -602,6 +808,80 @@ class VaultImageStore internal constructor(
         }
     }
 
+    /**
+     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
+     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
+     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
+     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
+     * boot).
+     *
+     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
+     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
+     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
+     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
+     * release the single-instance registration.
+     *
+     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
+     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
+     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
+     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
+     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
+     */
+    fun retireLegacyImage() {
+        imageLock.withLock {
+            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
+            val version = readInnerVersionOrNull()
+            check(version == LEGACY_IMAGE_VERSION) {
+                "retireLegacyImage refused: not a legacy image (inner version=$version)"
+            }
+            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
+            dek?.let { wipe(it) }
+            dek = null
+            canonical = null
+            binFile.delete()
+            dekFile.delete()
+            deleteLeftoverTmp(binFile)
+            deleteLeftoverTmp(dekFile)
+            unregister()
+            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
+            if (binFile.exists() || dekFile.exists() ||
+                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
+            ) {
+                throw VaultImageException.DestroyFailed()
+            }
+            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
+                throw VaultImageException.DestroyFailed()
+            }
+        }
+    }
+
+    /**
+     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
+     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
+     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
+     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
+     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
+     */
+    private fun readInnerVersionOrNull(): Int? {
+        if (!binFile.exists() || !dekFile.exists()) return null
+        return try {
+            val dekBlob = dekFile.readBytes()
+            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
+            val binBytes = binFile.readBytes()
+            if (binBytes.size != OUTER_IMAGE_BYTES) return null
+            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
+            try {
+                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
+                if (inner.size != IMAGE_BYTES) return null
+                inner[0].toInt() and 0xff
+            } finally {
+                wipe(unwrapped)
+            }
+        } catch (t: Throwable) {
+            null
+        }
+    }
+
     /**
      * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
      * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM

codex
# Security findings report — commit `321b358`

## 1. High — Second-vault creation cancels an active account deletion

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 683–704; `clearBothMarkersDurably`, lines 968–979.

**Defect mechanism:** The Created branch unconditionally clears both delete markers while the existing vault image remains live. Unlike `create()`, this is not restricted to an image-absent successor-account state. Therefore markers belonging to vault A’s active deletion are treated as stale merely because an unrelated vault B is being added.

This violates Invariant 6: marker ownership is device-wide, but the writer acts as though a new slot constitutes a new account epoch.

**Failure / attack scenarios:**

- A has durable `delete-intent`; its authenticated DELETE failed ambiguously. At the lock screen, three entries of B’s new passphrase reach `create=true`. The intent is durably removed and B is written. The post-unlock reader `deleteIntentPending()` now returns false, so A’s DELETE is never reconciled. A’s server account remains undeleted.
- The random B placement overwrites A’s slot after clearing intent. A’s only authentication material is destroyed, while its pending server deletion has also been forgotten. The account can become permanently undeletable from this device.
- This does not require a confirmed marker or an anomalous boot route; the explicitly acknowledged intent-only state is sufficient.

The new method is not yet called from production app code in this commit, but it is the intended live writer and is unsafe to wire.

---

## 2. Critical — Clearing a confirmed marker can strand a server-deleted account and persistent vault image

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 683–704; `clearBothMarkersDurably`, lines 968–979.

**Defect mechanism:** The method accepts and clears `vault.delete-confirmed` without proving that it belongs to a completed prior account epoch. A confirmed marker means the server account is already gone and is the sole authorization for boot-time local destruction. Removing it converts that terminal state into an ordinary vault state.

The marker clear and image rewrite are separate durable transactions.

**Failure / attack scenarios:**

- State begins `{intent, confirmed, A image}` after the server confirms deletion but before session teardown/destroy. A concurrent or subsequently misrouted B creation clears both markers. A crash immediately after the marker directory fsync and before `sealPayload` or `atomicWrite` leaves `{no markers, A image}`. Boot routes to `Locked`, not `DeleteIncomplete`; A’s server account is gone, but its forensic vault image persists indefinitely.
- If B’s write completes, the state becomes `{no markers, A/B image}`. Neither vault is auto-destroyed despite the confirmed account deletion.
- If B overwrites A, B remains stored over an account epoch already confirmed deleted, again with no destruction authorization.
- A crash during the two unlinks in `clearBothMarkersDurably` can recover as both markers, intent-only, confirmed-only, or neither, depending on which unlink survives journal recovery. The “neither” recovery is unsafe when confirmation had already occurred.

The pre-write ordering prevents B from being auto-destroyed by a surviving stale marker only when the clear succeeds durably. It does so by introducing the more severe opposite failure: erasing valid deletion authorization before B exists.

---

## 3. High — Created session is not proven reachable through its persisted key slot

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 649–718; `VaultSlots.kt`, `sealSlot`, lines 61–75.

**Explicit verdict:** The remaining checks are **not sufficient** to guarantee that the persisted slot is openable with the returned `candKey`.

**Defect mechanism:** Size checks prove only structural lengths:

- `KeySlot` requires a 16-byte salt and 60-byte wrapped blob.
- `sealPayload` requires a 256 KiB output.
- `encodeImage` verifies fixed region sizes.

They do not authenticate or reopen the newly sealed slot. A misbehaving `VaultSodiumOps.aeadEncrypt` can return a correct-size random or incorrectly keyed 60-byte blob. The image is then durably written, while `VaultOpen(candKey, genesisPayload)` is constructed directly without reading either persisted ciphertext.

Similarly, a payload encryptor can return a correct-size unauthenticated blob. The live session starts from the caller-provided genesis copy, but the next restart cannot open the payload.

**Failure / attack scenario:** `sealSlot` returns a correctly sized but undecryptable wrapped key. `atomicWrite` succeeds and Created is published. The session creates identity/messages and may acknowledge activity. After process death, the B passphrase matches no slot, making the vault permanently unreachable.

**Cheapest verification preserving five Argon2id derivations:** authenticate-decrypt the candidate wrapped key using the candidate derivation’s still-live master key before it is wiped, and compare the recovered key with `candKey`. This adds only one small wrapped-key GCM decrypt, not another Argon2id. A candidate payload open is separately necessary to establish payload ciphertext validity; it does not prove slot reachability.

---

## 4. Medium — Vault keys leak when candidate construction throws

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 644–653.

**Defect mechanism:** The cleanup `try` begins only after:

1. `tryPassphrase` may return `unlock.vaultKey`;
2. `candKey` is generated;
3. the placement draw occurs;
4. `sealSlot` completes.

If `randomVaultSlotIndex` or `sealSlot` throws, neither `unlock.vaultKey` nor `candKey` is covered by the catch at lines 731–736. `sealSlot` wipes only its derived master key.

**Failure / attack scenario:** A valid A or burn passphrase matches, then injected/native crypto failure, malformed random output, OOM, or AEAD failure occurs during candidate sealing. The call throws with the recovered live vault key and candidate key left unwiped in heap, increasing recovery exposure under physical memory forensics.

No use-after-wipe was found on normal completed branches.

---

# Mandatory focus verification

## Invariant 6 — Complete marker writer/reader enumeration

### Writers

- `markDeleteIntent()` creates `vault.delete-intent`, then directory-fsyncs.
- `markServerDeleteConfirmed()` creates `vault.delete-confirmed`, then directory-fsyncs.
- `destroy()` creates/ensures `delete-confirmed`, unlinks image files, fsyncs those unlinks, then clears both markers and fsyncs again.
- `clearDeleteIntent()` unlinks only intent and fsyncs.
- `clearBothMarkersDurably()` unlinks intent, then confirmed, performs one directory fsync, and re-stats both.
- `create()` clears both before creating a fresh image, but only after requiring `vault.bin` absent.
- `attemptUnlockOrAdd()` Created clears both while an existing image remains live.
- `retireLegacyImage()` does not modify either marker.

### Readers

- `serverDeleteConfirmed()` reads confirmed existence and authorizes `Route.DeleteIncomplete` in all boot/session route derivations.
- `deleteIntentPending()` reads `intent && !confirmed` and triggers authenticated post-unlock reconciliation.
- `hasDeleteIntentMarker()` reads intent as present-or-indeterminate and prevents token clearing.
- `create()` and `attemptUnlockOrAdd()` read both using `Files.notExists` to decide whether clearing is required.
- `destroy()` relies on confirmed continuity across image unlink and marker retirement.
- `MainActivity` route derivation prioritizes confirmed, otherwise routes an existing image to unlock.
- `MessagingCoordinator` relies on intent continuity for authentication retention and confirmed continuity before terminal teardown.

### Reader assumptions over possible durable states

- `{none}`: readers assume no deletion is active. Invalid after `attemptUnlockOrAdd` clears a genuine intent or confirmation.
- `{intent only}`: reconciliation and auth retention are correct.
- `{intent, confirmed}`: confirmed authorizes destroy; auth remains protected. Correct.
- `{confirmed only}`: auto-destroy remains authorized; `deleteIntentPending` is false. Safe for destruction, although auth protection is absent.
- Mid-clear recovery can produce any of these four states. A recovered `{none}` is unsafe if the starting state contained genuine intent or confirmation.
- Mid-write after a durable clear but before B’s image rename deterministically exposes the unsafe `{none, old image}` state.
- Post-write exposes `{none, modified image}`, canceling reconciliation or destruction.

Therefore the marker readers themselves retain their old meanings, but the new writer produces states in which those meanings are false.

## Corrupt-payload asymmetry — Clean

- Matched vault slot: null or thrown payload open becomes `CorruptImage`; it is never converted into no-match or creation.
- Matched burn slot: payload open is attempted once under `runCatching`; null/throw is discarded and `Burn` is returned.
- No path was found where a successfully matched vault slot with a bad payload feeds the create/reject path.
- An unchecked fatal JVM error remains outside ordinary exception guarantees, as expected.

## Timing parity — Cryptographic operation counts clean; documentation discrepancy confirmed

For a normally functioning crypto implementation, all four outcomes perform:

- 4 Argon2id derivations and 4 wrapped-key GCM decrypt attempts in `tryPassphrase`;
- 1 Argon2id derivation and 1 wrapped-key GCM encryption in `sealSlot`;
- exactly 1 payload-sized GCM operation.

Thus the actual total is **5 wrapped-key GCM operations**, not one. The spec’s “one 60-byte wrapped-key GCM per call” counts only the candidate seal and omits the four sweep decrypts. The test is correct.

Slot 0 uses the same sweep regardless of arm state. Placement uses one four-byte draw and excludes slot 0 without additional disk I/O. Create rewrites the full outer image. The only intended outcome-specific persistence residual is create’s outer GCM, atomic write, and fsync.

Exact wall-clock identity is not literal: authenticated matches perform result/allocation branches that fillers do not, and create performs persistence. Heavy-crypto count parity nevertheless holds.

## Legacy-image isolation — Clean within the application’s store/locking model

- `attemptUnlockOrAdd`, `unlock`, and `unlockWithKey` all call `open()` before decoding when no canonical image exists.
- `open()` checks the decrypted inner version and throws `LegacyImage` before installing canonical or invoking slot code.
- `unlockImage()` independently calls `decodeImage()`, which rejects v2 before `tryPassphrase`.
- A v2 image cannot reach burn interpretation through these paths even if app routing is absent.
- `isLegacyImage()` is advisory only.
- `retireLegacyImage()` re-reads and authenticates the image under `imageLock` immediately before unlinking and refuses current v3. No in-process store TOCTOU exists. An external privileged process replacing files after the proof is outside this lock, but such an actor already has direct deletion authority.

## Other reviewed areas — Clean or informational

- `randomVaultSlotIndex` has no four-byte uniqueness dependency; length-based test interception is test-only. Its unsigned conversion and modulo are valid for the fixed three-slot pool.
- Modulo bias exists mathematically but is negligible and unrelated to safety.
- Canonical advances immediately after successful rename and before a `NotDurable` throw, avoiding stale in-process splices.
- Image-lock ordering introduces no VaultSession callback or reverse nesting.
- `addVaultSlot` and `addVaultToImage` remain burn-unaware but have no production call sites after this commit. They remain publicly reachable primitives and would permit slot-0 placement if later wired without an occupied-slot constraint.
- The corrupt burn behavior matches the approved requirement.
- B is not auto-destroyed after a successfully durable marker clear; the defect is instead loss of legitimate A reconciliation/destruction state.

**Overall verdict: REJECT — the new writer violates the durable deletion-marker state machine and can erase both pending deletion work and confirmed destruction authorization.**
tokens used
77,967
# Security findings report — commit `321b358`

## 1. High — Second-vault creation cancels an active account deletion

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 683–704; `clearBothMarkersDurably`, lines 968–979.

**Defect mechanism:** The Created branch unconditionally clears both delete markers while the existing vault image remains live. Unlike `create()`, this is not restricted to an image-absent successor-account state. Therefore markers belonging to vault A’s active deletion are treated as stale merely because an unrelated vault B is being added.

This violates Invariant 6: marker ownership is device-wide, but the writer acts as though a new slot constitutes a new account epoch.

**Failure / attack scenarios:**

- A has durable `delete-intent`; its authenticated DELETE failed ambiguously. At the lock screen, three entries of B’s new passphrase reach `create=true`. The intent is durably removed and B is written. The post-unlock reader `deleteIntentPending()` now returns false, so A’s DELETE is never reconciled. A’s server account remains undeleted.
- The random B placement overwrites A’s slot after clearing intent. A’s only authentication material is destroyed, while its pending server deletion has also been forgotten. The account can become permanently undeletable from this device.
- This does not require a confirmed marker or an anomalous boot route; the explicitly acknowledged intent-only state is sufficient.

The new method is not yet called from production app code in this commit, but it is the intended live writer and is unsafe to wire.

---

## 2. Critical — Clearing a confirmed marker can strand a server-deleted account and persistent vault image

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 683–704; `clearBothMarkersDurably`, lines 968–979.

**Defect mechanism:** The method accepts and clears `vault.delete-confirmed` without proving that it belongs to a completed prior account epoch. A confirmed marker means the server account is already gone and is the sole authorization for boot-time local destruction. Removing it converts that terminal state into an ordinary vault state.

The marker clear and image rewrite are separate durable transactions.

**Failure / attack scenarios:**

- State begins `{intent, confirmed, A image}` after the server confirms deletion but before session teardown/destroy. A concurrent or subsequently misrouted B creation clears both markers. A crash immediately after the marker directory fsync and before `sealPayload` or `atomicWrite` leaves `{no markers, A image}`. Boot routes to `Locked`, not `DeleteIncomplete`; A’s server account is gone, but its forensic vault image persists indefinitely.
- If B’s write completes, the state becomes `{no markers, A/B image}`. Neither vault is auto-destroyed despite the confirmed account deletion.
- If B overwrites A, B remains stored over an account epoch already confirmed deleted, again with no destruction authorization.
- A crash during the two unlinks in `clearBothMarkersDurably` can recover as both markers, intent-only, confirmed-only, or neither, depending on which unlink survives journal recovery. The “neither” recovery is unsafe when confirmation had already occurred.

The pre-write ordering prevents B from being auto-destroyed by a surviving stale marker only when the clear succeeds durably. It does so by introducing the more severe opposite failure: erasing valid deletion authorization before B exists.

---

## 3. High — Created session is not proven reachable through its persisted key slot

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 649–718; `VaultSlots.kt`, `sealSlot`, lines 61–75.

**Explicit verdict:** The remaining checks are **not sufficient** to guarantee that the persisted slot is openable with the returned `candKey`.

**Defect mechanism:** Size checks prove only structural lengths:

- `KeySlot` requires a 16-byte salt and 60-byte wrapped blob.
- `sealPayload` requires a 256 KiB output.
- `encodeImage` verifies fixed region sizes.

They do not authenticate or reopen the newly sealed slot. A misbehaving `VaultSodiumOps.aeadEncrypt` can return a correct-size random or incorrectly keyed 60-byte blob. The image is then durably written, while `VaultOpen(candKey, genesisPayload)` is constructed directly without reading either persisted ciphertext.

Similarly, a payload encryptor can return a correct-size unauthenticated blob. The live session starts from the caller-provided genesis copy, but the next restart cannot open the payload.

**Failure / attack scenario:** `sealSlot` returns a correctly sized but undecryptable wrapped key. `atomicWrite` succeeds and Created is published. The session creates identity/messages and may acknowledge activity. After process death, the B passphrase matches no slot, making the vault permanently unreachable.

**Cheapest verification preserving five Argon2id derivations:** authenticate-decrypt the candidate wrapped key using the candidate derivation’s still-live master key before it is wiped, and compare the recovered key with `candKey`. This adds only one small wrapped-key GCM decrypt, not another Argon2id. A candidate payload open is separately necessary to establish payload ciphertext validity; it does not prove slot reachability.

---

## 4. Medium — Vault keys leak when candidate construction throws

**File / function:** `VaultImageStore.kt`, `attemptUnlockOrAdd`, lines 644–653.

**Defect mechanism:** The cleanup `try` begins only after:

1. `tryPassphrase` may return `unlock.vaultKey`;
2. `candKey` is generated;
3. the placement draw occurs;
4. `sealSlot` completes.

If `randomVaultSlotIndex` or `sealSlot` throws, neither `unlock.vaultKey` nor `candKey` is covered by the catch at lines 731–736. `sealSlot` wipes only its derived master key.

**Failure / attack scenario:** A valid A or burn passphrase matches, then injected/native crypto failure, malformed random output, OOM, or AEAD failure occurs during candidate sealing. The call throws with the recovered live vault key and candidate key left unwiped in heap, increasing recovery exposure under physical memory forensics.

No use-after-wipe was found on normal completed branches.

---

# Mandatory focus verification

## Invariant 6 — Complete marker writer/reader enumeration

### Writers

- `markDeleteIntent()` creates `vault.delete-intent`, then directory-fsyncs.
- `markServerDeleteConfirmed()` creates `vault.delete-confirmed`, then directory-fsyncs.
- `destroy()` creates/ensures `delete-confirmed`, unlinks image files, fsyncs those unlinks, then clears both markers and fsyncs again.
- `clearDeleteIntent()` unlinks only intent and fsyncs.
- `clearBothMarkersDurably()` unlinks intent, then confirmed, performs one directory fsync, and re-stats both.
- `create()` clears both before creating a fresh image, but only after requiring `vault.bin` absent.
- `attemptUnlockOrAdd()` Created clears both while an existing image remains live.
- `retireLegacyImage()` does not modify either marker.

### Readers

- `serverDeleteConfirmed()` reads confirmed existence and authorizes `Route.DeleteIncomplete` in all boot/session route derivations.
- `deleteIntentPending()` reads `intent && !confirmed` and triggers authenticated post-unlock reconciliation.
- `hasDeleteIntentMarker()` reads intent as present-or-indeterminate and prevents token clearing.
- `create()` and `attemptUnlockOrAdd()` read both using `Files.notExists` to decide whether clearing is required.
- `destroy()` relies on confirmed continuity across image unlink and marker retirement.
- `MainActivity` route derivation prioritizes confirmed, otherwise routes an existing image to unlock.
- `MessagingCoordinator` relies on intent continuity for authentication retention and confirmed continuity before terminal teardown.

### Reader assumptions over possible durable states

- `{none}`: readers assume no deletion is active. Invalid after `attemptUnlockOrAdd` clears a genuine intent or confirmation.
- `{intent only}`: reconciliation and auth retention are correct.
- `{intent, confirmed}`: confirmed authorizes destroy; auth remains protected. Correct.
- `{confirmed only}`: auto-destroy remains authorized; `deleteIntentPending` is false. Safe for destruction, although auth protection is absent.
- Mid-clear recovery can produce any of these four states. A recovered `{none}` is unsafe if the starting state contained genuine intent or confirmation.
- Mid-write after a durable clear but before B’s image rename deterministically exposes the unsafe `{none, old image}` state.
- Post-write exposes `{none, modified image}`, canceling reconciliation or destruction.

Therefore the marker readers themselves retain their old meanings, but the new writer produces states in which those meanings are false.

## Corrupt-payload asymmetry — Clean

- Matched vault slot: null or thrown payload open becomes `CorruptImage`; it is never converted into no-match or creation.
- Matched burn slot: payload open is attempted once under `runCatching`; null/throw is discarded and `Burn` is returned.
- No path was found where a successfully matched vault slot with a bad payload feeds the create/reject path.
- An unchecked fatal JVM error remains outside ordinary exception guarantees, as expected.

## Timing parity — Cryptographic operation counts clean; documentation discrepancy confirmed

For a normally functioning crypto implementation, all four outcomes perform:

- 4 Argon2id derivations and 4 wrapped-key GCM decrypt attempts in `tryPassphrase`;
- 1 Argon2id derivation and 1 wrapped-key GCM encryption in `sealSlot`;
- exactly 1 payload-sized GCM operation.

Thus the actual total is **5 wrapped-key GCM operations**, not one. The spec’s “one 60-byte wrapped-key GCM per call” counts only the candidate seal and omits the four sweep decrypts. The test is correct.

Slot 0 uses the same sweep regardless of arm state. Placement uses one four-byte draw and excludes slot 0 without additional disk I/O. Create rewrites the full outer image. The only intended outcome-specific persistence residual is create’s outer GCM, atomic write, and fsync.

Exact wall-clock identity is not literal: authenticated matches perform result/allocation branches that fillers do not, and create performs persistence. Heavy-crypto count parity nevertheless holds.

## Legacy-image isolation — Clean within the application’s store/locking model

- `attemptUnlockOrAdd`, `unlock`, and `unlockWithKey` all call `open()` before decoding when no canonical image exists.
- `open()` checks the decrypted inner version and throws `LegacyImage` before installing canonical or invoking slot code.
- `unlockImage()` independently calls `decodeImage()`, which rejects v2 before `tryPassphrase`.
- A v2 image cannot reach burn interpretation through these paths even if app routing is absent.
- `isLegacyImage()` is advisory only.
- `retireLegacyImage()` re-reads and authenticates the image under `imageLock` immediately before unlinking and refuses current v3. No in-process store TOCTOU exists. An external privileged process replacing files after the proof is outside this lock, but such an actor already has direct deletion authority.

## Other reviewed areas — Clean or informational

- `randomVaultSlotIndex` has no four-byte uniqueness dependency; length-based test interception is test-only. Its unsigned conversion and modulo are valid for the fixed three-slot pool.
- Modulo bias exists mathematically but is negligible and unrelated to safety.
- Canonical advances immediately after successful rename and before a `NotDurable` throw, avoiding stale in-process splices.
- Image-lock ordering introduces no VaultSession callback or reverse nesting.
- `addVaultSlot` and `addVaultToImage` remain burn-unaware but have no production call sites after this commit. They remain publicly reachable primitives and would permit slot-0 placement if later wired without an occupied-slot constraint.
- The corrupt burn behavior matches the approved requirement.
- B is not auto-destroyed after a successfully durable marker clear; the defect is instead loss of legitimate A reconciliation/destruction state.

**Overall verdict: REJECT — the new writer violates the durable deletion-marker state machine and can erase both pending deletion work and confirmed destruction authorization.**
