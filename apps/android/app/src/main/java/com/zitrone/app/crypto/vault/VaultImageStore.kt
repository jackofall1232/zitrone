// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Associated data for the image's OUTER (device-key) layer. A fixed purpose-binding
 * label — the SAME convention as [SLOT_AD] / [PAYLOAD_AD] — that ties the outer
 * ciphertext to its role so an outer blob can never be authenticated under, or
 * reinterpreted as, a different layer's ciphertext. It is a generic, slot-agnostic
 * constant: it names only the layer ("outer"), never a slot, a vault, or real-vs-decoy,
 * so it is byte-identical for every install and reveals nothing. `internal` so the
 * storage tests can decrypt the on-disk blob to assert on inner regions without coupling
 * to a private constant.
 */
internal val VAULT_IMAGE_OUTER_AD: ByteArray = "Zitrone-Vault-Outer-v1".toByteArray(Charsets.UTF_8)

/**
 * The distinct, non-silently-repaired outcomes of reading the on-disk vault image.
 *
 * A sealed EXCEPTION hierarchy (rather than a returned sealed state) is the cleaner
 * fit for this package: the primitives already fail fast with `require` / `check`
 * and throw, so a corrupt or missing image throws too — a returned state can be
 * ignored, but "NEVER silently repair" must be self-enforcing, and a thrown,
 * exhaustively-`when`-able type gives the caller distinct escalation branches while
 * keeping the happy path (`open()` returns Unit) clean. It is deliberately DISTINCT
 * from the `IllegalStateException` / `IllegalArgumentException` the store throws for
 * caller bugs (writing before open, wrong sizes): those are programming errors,
 * these are environmental/data states the caller must handle.
 *
 * SLOT-AGNOSTIC: the type distinguishes only device-level image presence vs.
 * unreadability — never slot count, occupancy, or "real vs. decoy". The messages
 * name nothing about slots.
 */
sealed class VaultImageException(message: String) : Exception(message) {
    /**
     * No vault image is present (`vault.bin` absent). The caller offers onboarding
     * / creation — this is the fresh-install state, NOT corruption. A stray wrapped
     * DEK with no image (a crash between the store's two writes) also reads as this:
     * the DEK alone protects nothing and is overwritten on the next [VaultImageStore.create].
     */
    class MissingImage : VaultImageException("no vault image present")

    /**
     * The image is present but unreadable: the outer device-key layer failed to
     * authenticate, the wrapped DEK is missing or unwrappable, or the decrypted
     * inner image is the wrong size. The caller ESCALATES (surfaces an error / halts)
     * — it MUST NOT recreate, which would destroy every real vault behind this image.
     */
    class CorruptImage : VaultImageException("vault image is unreadable")

    /**
     * The image is present, the outer layer authenticated, and the inner image is a
     * VALID but PRIOR format version ([LEGACY_IMAGE_VERSION] = v2, the 0.9.1 format).
     * This is DISTINCT from [CorruptImage] on purpose and is SAFETY-CRITICAL: a v2 image
     * could hold the everyday vault at slot 0, which v3's burn-slot reservation would
     * misread as a burn credential and WIPE on the user's own correct passphrase. So a v2
     * image is NEVER unlocked, NEVER slot-interpreted, and NEVER auto-destroyed at boot —
     * [open] throws this before any slot material is used, the caller routes to fresh
     * onboarding, and the retirement of the old file happens only on the deliberate
     * onboarding action via [VaultImageStore.retireLegacyImage]. An UNKNOWN (neither
     * current nor [LEGACY_IMAGE_VERSION]) version stays [CorruptImage] (escalate, never
     * recreate). 0.9.1 was fresh-install-only with no real users, so the blast radius is
     * test devices — but "we happened to have no users" is not a safety property, so this
     * fail-closed distinction ships regardless.
     */
    class LegacyImage : VaultImageException("vault image is a prior, retired format")

    /**
     * A payload write's bytes ARE on disk (the atomic rename — the commit point —
     * landed and its content was fsynced), but the directory-entry fsync that would
     * make the rename itself crash-durable did NOT confirm success — either a real
     * storage error (EIO on an opened directory channel) or a platform that could not
     * open a directory channel at all. Only a confirmed successful directory fsync counts
     * as durable; anything short of that fails CLOSED here rather than risk a false ack.
     * The in-memory [VaultImageStore.canonical] has been ADVANCED to match disk (so no
     * later splice works from stale state), yet the write is NOT confirmed durable — so it
     * is thrown, never returned: a flush-before-ack caller must NOT ack. The session stays
     * dirty and retries; a retry whose dir-fsync succeeds then acks. Distinct from
     * [CorruptImage] — nothing is unreadable; only the rename's durability is unconfirmed.
     */
    class NotDurable : VaultImageException("vault image write not confirmed durable")

    /**
     * [VaultImageStore.destroy] deleted the files but a re-stat found one of them STILL on disk:
     * [File.delete] returned false because of an I/O / filesystem error (not an already-absent
     * file), so the full-crypto image — the account's identity keypair, ratchet records, and
     * roster — SURVIVES. Account deletion MUST treat this as NOT-deleted: surface an error / retry,
     * never route to Onboarding-as-success (which would tell the user "deleted" while the image
     * remains recoverable). Distinct from the read outcomes above — nothing is unreadable; a
     * removal we asked for did not take. Idempotent-safe: an ALREADY-absent file re-stats absent
     * and does NOT throw, so a retried destroy() over a partially-succeeded one still completes.
     */
    class DestroyFailed : VaultImageException("vault image destruction failed — a file survives")
}

/**
 * The exact on-disk size of `vault.bin`: the [IMAGE_BYTES] inner image plus the outer
 * AES-256-GCM envelope's [NONCE_BYTES] nonce and [AEAD_TAG_BYTES] tag. A present
 * `vault.bin` of any OTHER length is corruption (a tampered / truncated / inflated
 * file), not a valid image — [VaultImageStore.open] length-checks against this constant
 * BEFORE reading, so an inflated file can never force a giant allocation. `internal` so
 * the storage tests can craft an off-size file to assert on.
 */
internal const val OUTER_IMAGE_BYTES: Int = IMAGE_BYTES + NONCE_BYTES + AEAD_TAG_BYTES

/**
 * The two durability outcomes of the post-rename directory fsync (see [VaultImageStore]
 * `defaultFsyncDir`). The rename is the COMMIT point — the new image is already on disk and
 * its content already fsynced before the dir-fsync runs — so this result reports only whether
 * the rename's DIRECTORY ENTRY is confirmed crash-durable, never whether the write happened.
 *
 * A rename is NOT guaranteed crash-durable just because the file CONTENT was fsynced: only a
 * successful directory fsync confirms the directory entry itself will survive a crash. So this
 * type is deliberately binary — anything short of a confirmed successful directory fsync is
 * [NOT_DURABLE], so the store can FAIL CLOSED (never falsely report durable) rather than risk a
 * false flush-before-ack.
 *  - [DURABLE]: the directory channel opened AND `force(true)` succeeded — the ONLY confirmed-durable
 *    outcome.
 *  - [NOT_DURABLE]: anything else — the directory channel could not be opened, `force(true)` threw
 *    [IOException] (a real EIO), or there was no directory to sync. The rename's durability is
 *    unconfirmed; the caller must not report the write durable / must not ack.
 * `internal` so the storage tests can inject a forced result to drive each branch.
 */
internal enum class DirSyncResult { DURABLE, NOT_DURABLE }

/**
 * The four outcomes of [VaultImageStore.attemptUnlockOrAdd] — the fused
 * unlock / burn-detect / maybe-create passphrase operation (0.9.2). SLOT-AGNOSTIC:
 * the CALLER learns only which of the four happened, never which slot or how many exist.
 */
sealed interface UnlockOrAdd {
    /** An existing VAULT slot (1..SLOT_COUNT-1) matched — a normal unlock. */
    data class Unlocked(val open: VaultOpen) : UnlockOrAdd

    /**
     * Slot 0 ([BURN_SLOT_INDEX]) matched — the Pucker Burn duress credential was entered.
     * The APP performs the wipe (a sibling feature); the store performs NO wipe here and
     * exposes nothing about the burn slot's contents or arm-state.
     */
    data object Burn : UnlockOrAdd

    /** No slot matched AND create was requested — a new vault was created + persisted durably. */
    data class Created(val open: VaultOpen) : UnlockOrAdd

    /** No slot matched AND create was not requested — an indistinguishable wrong passphrase. */
    data object Rejected : UnlockOrAdd
}

/**
 * The device-level storage layer for the plausible-deniability vault image. Owns
 * the on-disk canonical image and the envelope that protects it at rest; nothing
 * here knows or reveals how many slots are real.
 *
 * AT-REST ENVELOPE (approved D2, see [DeviceKeyCipher]):
 *  - `vault.bin` = `nonce(12) ‖ AES-256-GCM_DEK(innerImage)` = [IMAGE_BYTES] + 28,
 *    a CONSTANT size, fresh random nonce every write. The inner image is the exact
 *    [IMAGE_BYTES] byte form from [encodeImage] (slot table + payload regions).
 *  - `vault.dek` = the 32-byte DEK wrapped by the hardware device key = a constant
 *    [WRAPPED_KEY_BYTES] (60). Exactly one per install that has an image — constant
 *    evidence that reveals nothing about slot count.
 *
 * The DEK encrypts the ~1 MiB image in-process with the fast portable AES-256-GCM
 * backend, so the hardware-gated Keystore crypto only ever touches the DEK's 32
 * bytes (once per open/create), never the per-flush hot path.
 *
 * SINGLE INSTANCE PER baseDir (load-bearing). AT MOST ONE VaultImageStore per baseDir
 * per process. The [imageLock] serializes calls WITHIN an instance; cross-instance
 * safety is provided by this single-instance rule, which the owner (the app container)
 * guarantees by constructing exactly one store per directory. A second instance opening
 * the SAME directory throws [IllegalStateException] — without this, two stores would
 * hold independent [canonical] snapshots and silently revert each other's writes (the
 * same stale-snapshot hazard the PR-A/PR-B redesign exists to kill), mirroring the
 * 'at most one live session per slot' contract on [VaultSession]. The registration is
 * released by [close], so a new instance may open the directory afterwards.
 *
 * LOCK-ORDER INVARIANT (load-bearing). When composed with [VaultSession] the order
 * is ALWAYS VaultSession.flushLock → [imageLock]: a flush seals under the session's
 * flushLock and only THEN hands the region to [writeSealedPayload], which takes
 * imageLock. NEVER invoke a VaultSession method while holding [imageLock] — that
 * would nest the locks in the reverse order and can deadlock.
 *
 * THREADING. Every method takes [imageLock]; all are synchronous. The Argon2id-heavy
 * methods are [create] — exactly SLOT_COUNT+1 derivations with the production deriver:
 * one to seal the real slot, then SLOT_COUNT more for the verification [unlockImage] —
 * and [unlock], exactly SLOT_COUNT (never fewer: the slot loop has no early exit). Both
 * MUST run off a UI thread. [open] is NOT Argon2id-heavy (a single ~1 MiB AEAD decrypt of
 * the outer layer) and [unlockWithKey] does NO Argon2id at all (it opens one payload with
 * an already-derived key); still, run them off-main so the ~1 MiB decrypt never lands on
 * the UI thread.
 *
 * SLOT-AGNOSTIC discipline: no logging, no strings that name slots / vaults / real /
 * decoy, constant-size writes, and no early exit keyed on slot identity.
 *
 * This is an isolated storage unit: it is deliberately NOT wired into any real app
 * coordinator, DI graph, or migration — that is a later sub-phase.
 *
 * @param baseDir directory the two image files live in (production: `context.filesDir`).
 *   Taken as a bare [File] — no Context dependency — so it is host-unit-testable. baseDir MUST
 *   be app-internal storage (production: `context.filesDir` — ext4/f2fs, where directory fsync is
 *   supported). External/removable storage (FAT32/exFAT) is unsupported BY DESIGN: on filesystems
 *   that cannot fsync a directory the store fails CLOSED (every write reads NOT_DURABLE) rather than
 *   silently weakening the flush-before-ack durability guarantee.
 */
class VaultImageStore internal constructor(
    private val baseDir: File,
    private val ops: VaultSodiumOps,
    private val deviceCipher: DeviceKeyCipher,
    private val deriver: KeyDeriver = argon2idDeriver(ops),
    // Injectable for tests (the package's inject-for-tests convention, as with [ops] /
    // [deriver]): the post-rename directory fsync, factored out so both durability branches
    // (DURABLE / NOT_DURABLE) are host-testable without a real EIO. Production uses
    // [defaultFsyncDir]; tests pass a lambda returning a forced [DirSyncResult].
    //
    // The constructor is `internal` (not the public default) because this last parameter's
    // type mentions the `internal` [DirSyncResult]: rather than leak that durability-only
    // implementation type into the public API, construction is kept module-internal — which
    // is where every caller already lives (the `:app` module's tests and, later, its app
    // container). The class type itself stays public.
    private val dirSync: (File?) -> DirSyncResult = ::defaultFsyncDir,
) {
    /** Serializes every read/write of the on-disk image and the in-memory canonical. */
    private val imageLock = ReentrantLock()

    /**
     * The current INNER image bytes ([IMAGE_BYTES]: slot table + payload regions),
     * held in memory after [open] / [create]. Ciphertext + salts only — it is NOT a
     * slot's secret plaintext (the outer layer protects it at rest, not on the heap),
     * so it is dropped, not wiped, on [close].
     */
    private var canonical: ByteArray? = null

    /** The unwrapped 32-byte DEK. Live key material — wiped on [close] and on every
     *  failure path that unwraps it. */
    private var dek: ByteArray? = null

    /**
     * The canonical directory path this instance has registered in [OPEN_PATHS], or null
     * when it holds no registration. Set by [register] on the first [open] / [create],
     * cleared by [unregister] on [close]. Accessed only under [imageLock]. Enforces the
     * single-instance-per-baseDir contract (see class kdoc).
     */
    private var registeredPath: String? = null

    private val binFile: File get() = File(baseDir, IMAGE_FILE)
    private val dekFile: File get() = File(baseDir, DEK_FILE)
    private val deleteIntentFile: File get() = File(baseDir, DELETE_INTENT_FILE)
    private val serverDeletedFile: File get() = File(baseDir, SERVER_DELETED_FILE)

    /** True when a vault image is present on disk (`vault.bin`). */
    fun exists(): Boolean = imageLock.withLock { binFile.exists() }

    /**
     * True iff a present image is the PRIOR [LEGACY_IMAGE_VERSION] (v2) format — the boot-routing
     * signal to send a 0.9.1 install to fresh onboarding instead of the lock screen (see
     * [VaultImageException.LegacyImage] / [retireLegacyImage]). A cheap, Argon2id-free peek: it reads
     * the outer layer and checks the inner version byte only. Returns false for a current-version image,
     * a missing image, or anything unreadable (a corrupt image is NOT "legacy" — it routes through the
     * normal unlock → [CorruptImage] escalation, never to a retire). Does NOT alter store state.
     */
    fun isLegacyImage(): Boolean =
        imageLock.withLock { readInnerVersionOrNull() == LEGACY_IMAGE_VERSION }

    /**
     * Read `vault.bin` + `vault.dek`, unwrap the DEK, decrypt the outer layer, and
     * hold the validated inner image as [canonical]. A leftover `.tmp` from an
     * interrupted write is deleted first (the main file is the last durable state).
     *
     * Throws [VaultImageException.MissingImage] when no image is present and
     * [VaultImageException.CorruptImage] when it is present but unreadable (outer
     * auth failure, missing / unwrappable DEK, wrong inner size, or an unknown inner
     * [IMAGE_VERSION]). NEVER silently recreates on corruption — that would destroy
     * real vaults; the caller escalates.
     *
     * A raw [IOException] (a transient read error — a failing disk, an I/O fault) is
     * deliberately NOT one of the sealed outcomes: it propagates unmapped so the caller
     * can retry a read that may succeed later. Only a file that VANISHED between the
     * existence check and the read (a TOCTOU race) is mapped into the taxonomy — a gone
     * image reads as MissingImage, a gone DEK as CorruptImage.
     *
     * A FAILED open — including a failed RE-open of an already-open store — leaves the
     * store fully CLOSED: the DEK is wiped, the cached [canonical] is dropped, and the
     * single-instance registration is released. The previously cached image is NEVER
     * served again once the disk has gone Missing/Corrupt, so a later persist can never
     * overwrite a bad on-disk image with stale in-memory data (masking corruption / a
     * rollback). A SUCCESSFUL re-open is unaffected — it is idempotent and re-installs
     * [canonical] from disk.
     */
    fun open() {
        imageLock.withLock {
            // Claim the single-instance registration BEFORE any work so two instances
            // racing on the same dir cannot both proceed. A re-open of THIS instance is
            // idempotent (register() no-ops when we already hold the path).
            register()
            try {
                // A leftover temp is an incomplete write; the main file is authoritative.
                deleteLeftoverTmp(binFile)
                deleteLeftoverTmp(dekFile)

                // Key on the image file: a stray DEK with no image is the fresh-install /
                // crash-between-writes state (MissingImage), not corruption.
                if (!binFile.exists()) throw VaultImageException.MissingImage()
                if (!dekFile.exists()) throw VaultImageException.CorruptImage()

                // A PRESENT file of the wrong length is corruption (tampered / truncated /
                // inflated), not "missing" — and length-checking BEFORE readBytes bounds the
                // allocation so an inflated bin can never OOM the process. Use Files.size (which
                // THROWS on a stat failure) rather than File.length (which silently returns 0L on a
                // transient stat error, misreading a valid file as wrong-size → a permanent-looking
                // CorruptImage). A file that VANISHED between the existence check and the stat
                // (NoSuchFileException) is mapped like the readBytes FNF path; any OTHER IOException
                // is a transient stat error and PROPAGATES raw for the caller to retry (same policy
                // as the readBytes IOException path). A size that reads successfully but != the
                // expected constant is CorruptImage as before.
                val dekSize = try {
                    java.nio.file.Files.size(dekFile.toPath())
                } catch (e: java.nio.file.NoSuchFileException) {
                    // A gone dek is always Corrupt (bin already passed its existence check).
                    throw VaultImageException.CorruptImage()
                }
                if (dekSize != WRAPPED_KEY_BYTES.toLong()) throw VaultImageException.CorruptImage()
                val binSize = try {
                    java.nio.file.Files.size(binFile.toPath())
                } catch (e: java.nio.file.NoSuchFileException) {
                    // A truly-gone bin is Missing (bin-keyed); a present-but-unstattable bin is Corrupt.
                    if (binFile.exists()) throw VaultImageException.CorruptImage()
                    else throw VaultImageException.MissingImage()
                }
                if (binSize != OUTER_IMAGE_BYTES.toLong()) throw VaultImageException.CorruptImage()

                // Map a file that vanished OR became unreadable between the checks and the read
                // into the taxonomy; any OTHER IOException is a transient read error and
                // propagates raw for the caller to retry (see kdoc). A FileNotFoundException is
                // ambiguous — absent OR present-but-unreadable (a directory / a permission
                // fault) — so recheck existence: a still-present dek/bin is Corrupt, a truly
                // gone bin is Missing (a gone dek is always Corrupt, bin already passed exists).
                val dekBlob = try {
                    dekFile.readBytes()
                } catch (e: FileNotFoundException) {
                    throw VaultImageException.CorruptImage()
                }
                val binBytes = try {
                    binFile.readBytes()
                } catch (e: FileNotFoundException) {
                    if (binFile.exists()) throw VaultImageException.CorruptImage()
                    else throw VaultImageException.MissingImage()
                }

                val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: throw VaultImageException.CorruptImage()
                // From here `unwrapped` is live key material: wipe it on EVERY failure path,
                // and keep it ONLY on the success path (mirrors tryPassphrase's discipline).
                val inner: ByteArray
                try {
                    inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD)
                        ?: throw VaultImageException.CorruptImage()
                    if (inner.size != IMAGE_BYTES) throw VaultImageException.CorruptImage()
                    // Validate the inner VERSION too, not just the size. Three cases (order matters):
                    //  - current [IMAGE_VERSION] → fall through, install as canonical.
                    //  - [LEGACY_IMAGE_VERSION] (v2, the 0.9.1 format) → [VaultImageException.LegacyImage],
                    //    thrown HERE before any slot is decoded/interpreted. SAFETY-CRITICAL: a v2 image
                    //    may hold the everyday vault at slot 0, which v3 would misread as a burn wipe. The
                    //    caller routes to fresh onboarding; retirement is deliberate ([retireLegacyImage]).
                    //  - any OTHER version → [CorruptImage] (unknown/tampered; escalate, never recreate).
                    // A future format bump MUST add its own branch here BEFORE changing [IMAGE_VERSION].
                    val innerVersion = inner[0].toInt() and 0xff
                    if (innerVersion != IMAGE_VERSION) {
                        if (innerVersion == LEGACY_IMAGE_VERSION) throw VaultImageException.LegacyImage()
                        throw VaultImageException.CorruptImage()
                    }
                } catch (t: Throwable) {
                    wipe(unwrapped)
                    throw t
                }

                // Success: install canonical + DEK, wiping any DEK we already held.
                dek?.let { wipe(it) }
                dek = unwrapped
                canonical = inner
            } catch (t: Throwable) {
                // A failed open — including a failed RE-open of an already-open store — must
                // FULLY invalidate, not just release a freshly-acquired registration. If a
                // re-open finds the disk Missing/Corrupt, retaining the stale canonical would
                // let a later persist overwrite the now-bad image with cached data (masking
                // corruption / a rollback). So drop the DEK + canonical and release the
                // registration UNCONDITIONALLY: the store is left CLOSED and re-openable.
                dek?.let { wipe(it) }
                dek = null
                canonical = null
                unregister()
                throw t
            }
        }
    }

    /**
     * Create a fresh vault image sealing [initialPayload] under [passphrase], write
     * it durably, and return a live [VaultOpen] for it. Requires no image exists yet.
     *
     * Generates a random DEK, builds the image with the audited [createImage] primitive,
     * and outer-encrypts it. It then VERIFIES the fresh image by [unlockImage]ing it —
     * one extra Argon2id×SLOT_COUNT, one-time at onboarding / migration, reusing the
     * audited primitive rather than adding a new create-and-open surface — BEFORE touching
     * disk: [unlockImage] runs purely on the in-memory image and needs no disk state, so
     * verifying first makes ANY failure in create() (bad wrapped-key size, encrypt /
     * verify / IO failure) leave the disk UNTOUCHED and the whole call retryable.
     *
     * Only then does it write to disk under a DEK-FIRST DURABILITY BARRIER: it renames `vault.dek`
     * into place and CONFIRMS that rename crash-durable (a directory fsync) BEFORE `vault.bin` is
     * ever written; only once the DEK is confirmed durable does it rename `vault.bin` into place and
     * CONFIRM that rename durable too. Success is returned ONLY when BOTH renames are confirmed
     * durable — the IMAGE is fail-closed too, not silently trusted, because it may hold a
     * just-migrated / freshly-generated identity that would be lost for good if a durable create()
     * ack survived a crash that dropped `vault.bin`'s rename. Either confirming fsync failing THROWS
     * [VaultImageException.NotDurable]; there are NO rollback deletes.
     *
     * CRASH-STATE GUARANTEE (the load-bearing invariant). Because the DEK is confirmed durable
     * BEFORE `vault.bin` is written, a crash at ANY point leaves one of only these states — all
     * recoverable, and NEVER a {bin-present, dek-absent} [VaultImageException.CorruptImage] brick:
     *  - no `vault.bin` (with or without a stray `vault.dek`) → [open] = [VaultImageException.MissingImage]
     *    → retry create(), which overwrites any stray dek.
     *  - both present → a COMPLETE, valid, openable vault (the DEK is durable, so it cannot have been
     *    lost) → [open] succeeds.
     * The {bin-present, dek-absent} state is UNREACHABLE by construction: the DEK is durable before
     * `vault.bin` exists, so it can never be lost while `vault.bin` survives — which is exactly why
     * no rollback delete is needed to avoid the brick.
     *
     * CALLER CONTRACT: after a create() throw, re-run [open]. A complete-but-unconfirmed vault opens
     * normally; otherwise [open] reads [VaultImageException.MissingImage] and create() may be
     * retried. create() NEVER leaves a bricked state. Both renames + their confirming fsyncs land
     * before any in-memory state is installed, so a mid-write throw leaves canonical / dek exactly as
     * they were (null on a fresh store). CPU-heavy: caller MUST be off-main.
     */
    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
        imageLock.withLock {
            // Claim the single-instance registration BEFORE any work (mirrors open()); a
            // failed create releases only what THIS call acquired so a retry can proceed.
            val newlyRegistered = registeredPath == null
            register()
            try {
                require(!binFile.exists()) { "vault image already exists" }
                // Clear any STALE delete markers BEFORE writing the successor vault (round 14, F2).
                // A marker resurrected by a journal replay from a PRIOR account's delete would
                // otherwise route this fresh vault to DeleteIncomplete → auto-destroy. Done FIRST
                // (no vault byte written yet) and VERIFIED by re-stat + required dirSync, so:
                //  - a silent File.delete() failure (bool false, marker survives) FAILS create with
                //    nothing on disk — never a successor vault coexisting with a live marker;
                //  - the old post-write ordering window ("vault durable, marker-clear not yet
                //    durable" → crash → successor auto-destroyed) is gone: the markers are proven
                //    absent + durable BEFORE the vault exists.
                // Decide whether to run the clear on a CONSERVATIVE check (round 15, R14-2): run it
                // unless BOTH markers are CONFIRMED absent (Files.notExists). A File.exists()==false
                // from an indeterminate stat must not skip the clear over a present-but-unstatable
                // marker — that is exactly how a stale confirmed marker would coexist with the new
                // vault. The clear itself proves absence via the same tristate + a required fsync.
                val markersConfirmedAbsent =
                    Files.notExists(deleteIntentFile.toPath()) &&
                        Files.notExists(serverDeletedFile.toPath())
                if (!markersConfirmedAbsent && !clearBothMarkersDurably()) {
                    throw VaultImageException.NotDurable()
                }
                val newDek = ops.randomBytes(DEK_BYTES)
                // Ephemeral here: the persisted copy lives in `dek`. Wipe the local on EVERY
                // exit, incl. if createImage / encrypt / wrap / verify / write throws mid-way.
                try {
                    val image = createImage(passphrase, initialPayload, ops, deriver)
                    val outer = ops.aeadEncrypt(newDek, image, VAULT_IMAGE_OUTER_AD)
                    val wrappedDek = deviceCipher.wrapDek(newDek)
                    // Store-level constant-blob check: reject a malformed wrapped key from ANY
                    // DeviceKeyCipher impl BEFORE any write, so a bad blob fails create() loudly
                    // instead of persisting and bricking the next open().
                    check(wrappedDek.size == WRAPPED_KEY_BYTES) { "malformed wrapped key" }

                    // Verify BEFORE writing: unlockImage operates on the in-memory image only, so
                    // proving the fresh image opens before any disk write keeps a failed create()
                    // fully retryable (disk untouched).
                    val liveOpen = unlockImage(passphrase, image, ops, deriver)
                        ?: throw IllegalStateException("freshly created image failed to open")
                    // liveOpen now holds live key material (an independent vault-key copy). If a
                    // write below throws — including the NotDurable rollback throw — wipe it so no
                    // vault key / plaintext is abandoned on the heap (the wipe-on-every-failure
                    // discipline the package keeps).
                    try {
                        // DEK-FIRST DURABILITY BARRIER. Write vault.dek and CONFIRM its rename
                        // crash-durable BEFORE vault.bin is ever written; only then write vault.bin
                        // and confirm ITS rename durable. This makes the {vault.bin present,
                        // vault.dek absent} CorruptImage brick UNREACHABLE by construction: the DEK is
                        // durable before the image exists, so it can never be lost while the image
                        // survives. NO rollback deletes are needed (or performed).
                        renameIntoPlace(dekFile, wrappedDek)
                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
                            // The DEK's rename is not confirmed durable → throw BEFORE writing
                            // vault.bin. At most a stray, possibly-not-durable vault.dek exists and NO
                            // vault.bin → open() = MissingImage → a retried create() overwrites it.
                            throw VaultImageException.NotDurable()
                        }
                        renameIntoPlace(binFile, outer)
                        if (dirSync(baseDir) != DirSyncResult.DURABLE) {
                            // vault.bin's rename is not confirmed durable → throw. The DEK is already
                            // durable, so the on-disk state is either {no bin} (open() = MissingImage
                            // → retry) or a COMPLETE, openable vault — never {bin, no-dek}. No rollback
                            // delete is needed.
                            throw VaultImageException.NotDurable()
                        }
                        // Install in-memory state INSIDE the liveOpen-wipe scope so EVERY throw point
                        // before the return — including the 32-byte newDek.copyOf() (an OOM at this
                        // allocation) — wipes liveOpen.vaultKey / liveOpen.payloadPlaintext rather than
                        // abandoning live key material + plaintext on the heap. The on-disk barrier has
                        // already landed above, so this cannot desync disk from memory; it only advances
                        // the in-memory canonical/dek to match the just-confirmed image.
                        dek?.let { wipe(it) }
                        dek = newDek.copyOf()
                        canonical = image
                        return liveOpen
                    } catch (t: Throwable) {
                        wipe(liveOpen.vaultKey)
                        wipe(liveOpen.payloadPlaintext)
                        throw t
                    }
                } finally {
                    wipe(newDek)
                }
            } catch (t: Throwable) {
                // A failed create must not leave a stale registration — release only what
                // THIS call acquired (an already-registered instance keeps its ownership).
                if (newlyRegistered) unregister()
                throw t
            }
        }
    }

    /**
     * Attempt [passphrase] against the current image (opening from disk first if
     * needed). Returns a live [VaultOpen] on a match, or null on none — an
     * indistinguishable wrong passphrase. The per-slot Argon2id work is identical
     * whichever slot (or none) matches — the plausible-deniability parity inherited
     * from [unlockImage] / [tryPassphrase]. A SUCCESSFUL unlock additionally opens one
     * fixed-size payload region, so success and failure are not equal-time; that is the
     * same accepted, documented asymmetry as [unlockImage] (it leaks no bit an observer
     * lacks — the app visibly unlocks or not the instant it happens). CPU-heavy: caller
     * MUST be off-main.
     */
    fun unlock(passphrase: String): VaultOpen? {
        imageLock.withLock {
            val image = canonical ?: run { open(); canonical!! }
            return unlockImage(passphrase, image, ops, deriver)
        }
    }

    /**
     * Open one slot's payload directly with an already-unlocked [vaultKey] (the
     * biometric / dual-wrap path — no passphrase, no Argon2id). Opening from disk
     * first if needed, decrypts `payloads[slotIndex]` with a COPY of [vaultKey] and
     * returns a [VaultOpen] holding that copy; returns null on AEAD failure.
     *
     * ⚠️ OWNERSHIP. The caller retains ownership of its [vaultKey] input and MUST
     * wipe it itself — the store never wipes the caller's array. The returned
     * [VaultOpen] holds an INDEPENDENT copy, so wiping the input does not disturb it.
     */
    fun unlockWithKey(vaultKey: ByteArray, slotIndex: Int): VaultOpen? {
        imageLock.withLock {
            // Only VAULT-POOL slots (1..SLOT_COUNT-1) are openable this way (F9 / Grok): slot 0 is the burn
            // credential and must NEVER be opened as a vault — a future biometric dual-wrap that named slot
            // 0 would otherwise surface the burn payload as an ordinary unlock instead of triggering a wipe.
            // BiometricUnlockStore is tightened to the same range, so a tampered slot-0 wrap reads
            // not-enabled and never reaches here; this require is the store-level backstop.
            require(slotIndex in VAULT_SLOT_RANGE) { "slot index out of range" }
            val image = canonical ?: run { open(); canonical!! }
            val payload = decodeImage(image).payloads[slotIndex]
            // Own COPY: on success the VaultOpen keeps it; on any failure wipe it. The
            // caller's input is never touched (it owns and wipes that itself).
            val keyCopy = vaultKey.copyOf()
            val plaintext = try {
                openPayload(keyCopy, payload, ops)
            } catch (t: Throwable) {
                wipe(keyCopy)
                throw t
            }
            if (plaintext == null) {
                wipe(keyCopy)
                return null
            }
            return VaultOpen(keyCopy, slotIndex, plaintext)
        }
    }

    /**
     * FUSED unlock / burn-detect / maybe-create — the single passphrase entry point for the
     * 0.9.2 second-vault router. Under [imageLock] (opening from disk first if needed). ALWAYS
     * does IDENTICAL heavy crypto regardless of outcome, so a stopwatch cannot tell the four
     * cases apart (the plausible-deniability + duress-credential timing contract):
     *
     *   - [tryPassphrase] over ALL SLOT_COUNT slots (incl. slot 0), no early exit — SLOT_COUNT Argon2id;
     *   - ONE unconditional candidate-slot seal ([sealSlot]) — 1 more Argon2id + 1 tiny wrapped-key GCM
     *     (real vault-B material on create, pure timing filler otherwise);
     *   - EXACTLY ONE 256 KiB payload GCM (open on a match, seal on create/reject).
     *
     * A SLOT MATCH (0..SLOT_COUNT-1) ALWAYS wins over [create]. A match on slot 0
     * ([BURN_SLOT_INDEX]) returns [UnlockOrAdd.Burn] (the app wipes; this method writes nothing
     * and never treats slot 0 as a vault). A match on 1..SLOT_COUNT-1 returns [UnlockOrAdd.Unlocked].
     * No match with [create] true seals a NEW vault into a random VAULT-POOL slot
     * ([randomVaultSlotIndex], never slot 0) sealing [genesisPayload], writes it durably (reusing the
     * EXISTING DEK — no dek write), and returns [UnlockOrAdd.Created]; with [create] false it returns
     * [UnlockOrAdd.Rejected] having written nothing.
     *
     * TIMING RESIDUAL (documented, accepted): only the create path additionally PERSISTS (the ~1 MiB
     * outer GCM + atomic write + dir-fsync that gate a durable [UnlockOrAdd.Created]). That work is
     * post-outcome and dwarfed by the SLOT_COUNT+1 Argon2id; it is the same class as the payload-open
     * asymmetry and is NOT a per-attempt KDF-level distinguisher.
     *
     * BLIND OVERWRITE (~1/(SLOT_COUNT-1) ≈ 33%): placement is over the vault pool with an EMPTY
     * occupied set (occupancy is unknowable by design), so a create can overwrite an existing vault in
     * slots 1..SLOT_COUNT-1 — the documented VeraCrypt-outer-volume tradeoff. Slot 0 (burn) is never a
     * target, so duress protection survives even a full pool.
     *
     * DELETE MARKERS: a create clears BOTH delete markers durably FIRST (mirrors [create]'s F2/round-14
     * discipline) — safety-critical so a stale confirmed marker cannot auto-destroy the new vault and a
     * stale intent cannot resurrect a reconcile against it. A non-durable clear throws before any write.
     *
     * The [create] flag is the CALLER's decision (the router's triple-entry gate); this method holds no
     * cross-call state. [genesisPayload] is the plaintext to seal into a new vault (caller owns+wipes it,
     * as with [create]'s initialPayload); [UnlockOrAdd.Created] carries an independent copy.
     *
     * CPU-heavy (SLOT_COUNT+1 Argon2id): caller MUST be off-main. Throws [VaultImageException.MissingImage]
     * / [VaultImageException.CorruptImage] / [VaultImageException.LegacyImage] from [open]; [CorruptImage]
     * if a MATCHED VAULT slot's payload is unreadable; [NotDurable] if the pre-create marker clear or the
     * create write is not confirmed durable.
     */
    fun attemptUnlockOrAdd(passphrase: String, genesisPayload: ByteArray, create: Boolean): UnlockOrAdd {
        imageLock.withLock {
            val image = canonical ?: run { open(); canonical!! }
            val activeDek = dek ?: throw IllegalStateException("vault image not open")
            val decoded = decodeImage(image)

            // (1) SWEEP — ALWAYS. SLOT_COUNT Argon2id over slots 0..SLOT_COUNT-1, no early exit.
            val unlock: VaultUnlock? = tryPassphrase(passphrase, decoded.slots, ops, deriver)

            // A cleanup mirror of the live candidate key (F4 / Codex): the candidate is generated INSIDE
            // the try below so a throw during its generation (native crypto failure, OOM,
            // sealSlotSelfVerifying self-verify failure) cannot strand it. The catch wipes both this and a
            // live matched vault key — neither is covered if candidate generation sits before the try.
            var candKeyForCleanup: ByteArray? = null
            try {
                // (2) CANDIDATE SEAL — ALWAYS. 1 Argon2id + a SELF-VERIFYING wrap (2 wrapped-key GCM:
                //     encrypt + verify-decrypt, 0 extra Argon2id — B2 / both reviewers). Real vault-B
                //     material on the create branch; pure timing filler (wiped) otherwise. Placement is
                //     over the VAULT POOL (never slot 0). sealSlotSelfVerifying proves the wrap is openable
                //     with candKey BEFORE a create persists it (the add-path substitute for create()'s
                //     re-open, which we cannot afford at +SLOT_COUNT Argon2id).
                val candKey = ops.randomBytes(VAULT_KEY_BYTES).also { candKeyForCleanup = it }
                val candSlotIndex = randomVaultSlotIndex(ops)
                val candSlot = sealSlotSelfVerifying(passphrase, candKey, ops, deriver)

                return when {
                    // ── BURN (slot 0 match) — wins over everything. Store writes nothing. ──
                    unlock != null && unlock.slotIndex == BURN_SLOT_INDEX -> {
                        wipe(candKey)
                        // Parity GCM: open slot 0's payload exactly as a vault unlock opens its payload,
                        // then discard. runCatching so a CORRUPT burn payload still fires the wipe — a
                        // duress credential must never be suppressed by a damaged marker (spec §6).
                        runCatching { openPayload(unlock.vaultKey, decoded.payloads[BURN_SLOT_INDEX], ops) }
                            .getOrNull()?.let { wipe(it) }
                        wipe(unlock.vaultKey)
                        UnlockOrAdd.Burn
                    }

                    // ── VAULT MATCH (slot 1..SLOT_COUNT-1) — wins over create. ──
                    unlock != null -> {
                        wipe(candKey)
                        val pt = try {
                            openPayload(unlock.vaultKey, decoded.payloads[unlock.slotIndex], ops)
                        } catch (t: Throwable) {
                            wipe(unlock.vaultKey)
                            throw VaultImageException.CorruptImage()
                        }
                        if (pt == null) {
                            wipe(unlock.vaultKey)
                            throw VaultImageException.CorruptImage()
                        }
                        UnlockOrAdd.Unlocked(VaultOpen(unlock.vaultKey, unlock.slotIndex, pt))
                    }

                    // ── CREATE a new vault into a vault-pool slot — B1 FAIL-CLOSED. ──
                    create -> {
                        // B1 (fail-closed; reverses OQ3): if we cannot PROVE both delete markers absent, an
                        // account delete may be in flight — intent = reconcile owed, confirmed = destroy
                        // owed — and NOTHING observable distinguishes a stale marker from a live one. So we
                        // NEVER create over that state and NEVER clear a marker (unlike create(), whose
                        // require(!binFile.exists()) has PROVEN its markers orphaned; we have no such proof).
                        // Instead behave EXACTLY like an ordinary wrong password: return Rejected (NOT throw —
                        // a throw is an observable side channel precisely when the device is mid-delete) after
                        // the SAME throwaway payload GCM every other outcome performs. A's delete-state
                        // machine is left completely untouched. This marker check is in the SAME imageLock
                        // critical section as the sweep and the write, and markDeleteIntent /
                        // markServerDeleteConfirmed also take imageLock, so no marker can appear between the
                        // check and the write (no TOCTOU). See docs/SECURITY_MODEL.md for the disclosed cost.
                        val markersAbsent =
                            Files.notExists(deleteIntentFile.toPath()) &&
                                Files.notExists(serverDeletedFile.toPath())
                        if (!markersAbsent) {
                            // The 1×256 KiB payload GCM, identical to Reject — no path skips it (folds in F6).
                            val throwaway = sealPayload(candKey, ByteArray(0), ops)
                            wipe(candKey)
                            wipe(throwaway)
                            UnlockOrAdd.Rejected
                        } else {
                            // The 1×256 KiB payload GCM for the create branch.
                            val sealedGenesis = sealPayload(candKey, genesisPayload, ops)
                            val newSlots = decoded.slots.toMutableList().also { it[candSlotIndex] = candSlot }
                            val newPayloads =
                                decoded.payloads.toMutableList().also { it[candSlotIndex] = sealedGenesis }
                            val newInner = encodeImage(VaultImage(newSlots, newPayloads))
                            // Reuse the EXISTING DEK (no dek write) → the {bin-present, dek-absent} brick is
                            // unreachable by construction; the dek is already durable on disk from create().
                            val outer = ops.aeadEncrypt(activeDek, newInner, VAULT_IMAGE_OUTER_AD)
                            // atomicWrite throws ONLY pre-rename (canonical untouched); a RETURN means the
                            // rename landed, the result reporting the rename's durability.
                            val sync = atomicWrite(binFile, outer)
                            // Rename committed → advance canonical BEFORE the durability check so a later
                            // splice/attempt never works from stale state even on the NotDurable throw.
                            canonical = newInner
                            if (sync != DirSyncResult.DURABLE) {
                                // On disk but durability unconfirmed: fail CLOSED so the caller does NOT ack a
                                // create. candKey is wiped (the caller gets no VaultOpen); the new vault IS in
                                // canonical, so a later single entry of its passphrase unlocks it via the
                                // match path — or, if the rename did not survive a crash, it is simply absent
                                // and re-creatable.
                                wipe(candKey)
                                throw VaultImageException.NotDurable()
                            }
                            // candKey is now the new vault's live key — HANDED to the session, NOT wiped here.
                            UnlockOrAdd.Created(VaultOpen(candKey, candSlotIndex, genesisPayload.copyOf()))
                        }
                    }

                    // ── REJECT — no match, no create. Nothing written. ──
                    else -> {
                        // LOAD-BEARING timing filler: one 256 KiB payload GCM, identical to the create /
                        // match payload op, then discarded. Do NOT optimize away (breaks timing parity).
                        val throwaway = sealPayload(candKey, ByteArray(0), ops)
                        wipe(candKey)
                        wipe(throwaway)
                        UnlockOrAdd.Rejected
                    }
                }
            } catch (t: Throwable) {
                // Ensure no live key is abandoned on ANY throw (F4): the candidate (if generated) AND a
                // matched vault key that had not yet been handed to a VaultOpen. On a normal return this is
                // not reached; on paths that already wiped, a re-wipe of zeroed bytes is a no-op.
                candKeyForCleanup?.let { wipe(it) }
                unlock?.let { wipe(it.vaultKey) }
                throw t
            }
        }
    }

    /**
     * The session persist sink and the flush-before-ack DURABILITY POINT. Splices
     * an already-sealed [sealedPayload] region into [canonical] at [slotIndex]
     * (every other region byte-unchanged), outer-encrypts the result with a fresh
     * nonce, atomically writes it, and ONLY THEN swaps [canonical] to the new image.
     *
     * Requires the store is open. On ANY throw a flush-before-ack caller must NOT ack —
     * a throw ALWAYS means "not confirmed durable". There are two throw shapes, kept
     * distinct because they leave DIFFERENT state:
     *  - PRE-rename failure (not open, wrong size, encrypt / tmp-write / rename / content-fsync
     *    IO failure): nothing was committed — [canonical] AND the on-disk image are both left at
     *    the PREVIOUS state (the atomic rename replaces or not at all). Session stays dirty, no ack.
     *  - POST-rename dir-fsync not confirmed ([DirSyncResult.NOT_DURABLE]): the new bytes ARE on
     *    disk (the rename — the commit point — landed and its content was fsynced) but the rename's
     *    own durability is unconfirmed. Only a confirmed successful directory fsync ([DirSyncResult.DURABLE])
     *    is treated as durable; anything else — a real dir-fsync EIO OR a platform that could not open a
     *    directory channel — fails CLOSED here. [canonical] is ADVANCED to match disk (so a later splice
     *    never works from stale state — the write is on disk, just unconfirmed), and a
     *    [VaultImageException.NotDurable] is thrown so the caller does NOT ack. The session stays dirty and
     *    retries; a retry whose dir-fsync succeeds then acks.
     *
     * Never logs, and does identical work regardless of which slot is written.
     */
    fun writeSealedPayload(slotIndex: Int, sealedPayload: ByteArray) {
        imageLock.withLock {
            val current = canonical ?: throw IllegalStateException("vault image not open")
            val activeDek = dek ?: throw IllegalStateException("vault image not open")
            require(sealedPayload.size == SLOT_PAYLOAD_BYTES) { "malformed payload region" }
            // spliceImagePayload validates slotIndex and returns a NEW array — `current`
            // is untouched, so nothing below can corrupt the live canonical.
            val spliced = spliceImagePayload(current, slotIndex, sealedPayload)
            val outer = ops.aeadEncrypt(activeDek, spliced, VAULT_IMAGE_OUTER_AD)
            // atomicWrite throws ONLY pre-rename (nothing committed, canonical untouched); a
            // RETURN means the rename landed, with the result telling the rename's durability.
            val sync = atomicWrite(binFile, outer)
            // The rename committed → in-memory canonical now matches disk. Advance it BEFORE the
            // durability check so a later splice never works from stale state even on that throw.
            canonical = spliced
            if (sync != DirSyncResult.DURABLE) {
                // On disk but durability NOT confirmed (real dir-fsync EIO, or a platform that
                // could not open a dir channel): only a confirmed dir-fsync counts as durable, so
                // fail CLOSED and throw — a flush-before-ack caller does NOT ack. canonical is
                // already advanced (above), so the session stays dirty and retries; a retry that
                // dir-fsyncs acks.
                throw VaultImageException.NotDurable()
            }
        }
    }

    /**
     * Wipe the DEK and drop the canonical image. Store open/close is device-level
     * and independent of any vault's lock — the outer layer is not a slot's secret,
     * so keeping the store open across vault locks is fine; this exists for tests /
     * teardown. Idempotent.
     *
     * Also RELEASES this instance's single-instance registration (see class kdoc), so a
     * new VaultImageStore may open the same directory afterwards. A real process restart
     * ends the old process and drops the registration implicitly; a test simulating a
     * restart within one JVM MUST close() the old instance first before constructing the
     * next one on the same baseDir.
     */
    fun close() {
        imageLock.withLock {
            dek?.let { wipe(it) }
            dek = null
            canonical = null
            unregister()
        }
    }

    /**
     * Retire a PRIOR-FORMAT ([LEGACY_IMAGE_VERSION], v2) image so a fresh [create] can re-onboard this
     * device in the SAME process. The 0.9.2 slot-0 (burn) reservation makes a v2 image unsafe to unlock
     * (its everyday vault may sit at slot 0, which v3 would misread as a burn wipe), so a v2 image is
     * never opened/unlocked — it is retired here on the DELIBERATE onboarding action (NOT silently at
     * boot).
     *
     * RE-PROVES the version first: reads the outer layer and requires the inner version ==
     * [LEGACY_IMAGE_VERSION]; if it is the CURRENT version (or unreadable/missing) it throws
     * [IllegalStateException] and deletes NOTHING — a misrouted call can never destroy a valid v3 vault.
     * Only on a proven-v2 image does it unlink `vault.bin` + `vault.dek` (+ tmp leftovers), drop RAM, and
     * release the single-instance registration.
     *
     * DISTINCT FROM [destroy] (load-bearing): this is FORMAT retirement, NOT an account delete. It writes
     * and clears NO delete markers and never touches the D2c delete-state machine — a retired v2 image has
     * no server account this device is responsible for deleting (0.9.1 was fresh-install-only). Verify-
     * unlink + dir-fsync as [destroy] does; throws [VaultImageException.DestroyFailed] if a file survives
     * or the retire is not durable (retry re-runs it — idempotent once the files are gone).
     */
    fun retireLegacyImage() {
        imageLock.withLock {
            // Re-prove v2 BEFORE deleting anything — never destroy a current-version vault on a misroute.
            val version = readInnerVersionOrNull()
            check(version == LEGACY_IMAGE_VERSION) {
                "retireLegacyImage refused: not a legacy image (inner version=$version)"
            }
            // Drop any RAM we hold (a v2 image was never installed as canonical, but be defensive).
            dek?.let { wipe(it) }
            dek = null
            canonical = null
            binFile.delete()
            dekFile.delete()
            deleteLeftoverTmp(binFile)
            deleteLeftoverTmp(dekFile)
            unregister()
            // Verify the unlink took (delete() returns false on an I/O error too), then make it durable.
            if (binFile.exists() || dekFile.exists() ||
                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
            ) {
                throw VaultImageException.DestroyFailed()
            }
            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
                throw VaultImageException.DestroyFailed()
            }
        }
    }

    /**
     * Best-effort peek at the inner image version: reads `vault.dek` + `vault.bin`, unwraps the DEK,
     * decrypts the outer layer, and returns the inner version byte — or null if the image is missing,
     * the wrong size, or unreadable (outer auth / unwrap failure). Argon2id-free (one outer AEAD decrypt).
     * Wipes the unwrapped DEK on every path. Caller MUST hold [imageLock]. Used by [isLegacyImage] and
     * [retireLegacyImage]; deliberately NON-throwing so those callers get a clean tristate.
     */
    private fun readInnerVersionOrNull(): Int? {
        if (!binFile.exists() || !dekFile.exists()) return null
        return try {
            val dekBlob = dekFile.readBytes()
            if (dekBlob.size != WRAPPED_KEY_BYTES) return null
            val binBytes = binFile.readBytes()
            if (binBytes.size != OUTER_IMAGE_BYTES) return null
            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: return null
            try {
                val inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD) ?: return null
                if (inner.size != IMAGE_BYTES) return null
                inner[0].toInt() and 0xff
            } finally {
                wipe(unwrapped)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * DESTROY every on-disk trace of this vault and drop all in-RAM state — the
     * account-deletion primitive (no remanence). Under [imageLock]: wipe the in-RAM
     * DEK, drop the cached [canonical], delete `vault.bin` and `vault.dek` (and any
     * interrupted-write `.tmp` leftovers), and RELEASE this instance's single-instance
     * registration so a fresh [create] may re-open the directory in the same process.
     *
     * DISTINCT FROM [close] (load-bearing). [close] only wipes RAM and LEAVES the disk
     * image intact — a lock, not a deletion: after close() [exists] stays true and the
     * encrypted image (with the account's full crypto identity: keypair, ratchet records,
     * roster) survives on disk, recoverable by a later unlock. destroy() is the ONLY path
     * that removes the files, so after it [exists] is false and nothing is recoverable.
     * Account deletion MUST use destroy(), never close()/reseal. When paired with a session
     * teardown that reseals via VaultRuntime.close(), destroy() MUST run AFTER that reseal so
     * no freshly-resealed image survives.
     *
     * IDEMPOTENT: [File.delete] returns false (never throws) on a missing file, so a second
     * destroy() — or a destroy() on a never-created store — is a safe no-op. The file deletes
     * are best-effort; even if one returns false the RAM state is still wiped and the
     * registration released, leaving the store fully closed. Runs ONLY under [imageLock] and
     * never invokes a VaultSession, so it introduces no reverse lock nesting.
     *
     * VERIFY-UNLINK (the no-remanence guarantee): [File.delete] returns false on an I/O /
     * filesystem error just as it does on an already-absent file, so its boolean cannot be
     * trusted to mean "gone". After the deletes this RE-STATS `vault.bin` / `vault.dek`; if
     * either SURVIVES, the full-crypto image is still on disk, so it throws
     * [VaultImageException.DestroyFailed] — account deletion then treats the vault as NOT
     * destroyed (never routes to Onboarding-as-success). The check is on [File.exists], NOT the
     * delete() bool, so an ALREADY-absent file (a second/idempotent destroy) re-stats absent and
     * does NOT throw. The RAM wipe + registration release still happen before the throw, leaving
     * the store fully closed and a retry (idempotent) able to re-attempt the unlink.
     */
    /**
     * TWO-PHASE DELETE MARKERS (round 13). Account deletion is a two-phase durable state machine
     * so that boot routing can tell "delete requested, server outcome unknown" apart from "server
     * account confirmed gone" — the conflation of those two into one marker was the round-12 P1
     * (a crash/failure before the server delete auto-destroyed a still-live-account vault).
     *
     *  - [markDeleteIntent] writes `vault.delete-intent` FIRST, before the server request. It means
     *    ONLY "a delete was initiated" — it NEVER triggers local destruction. A crash here leaves a
     *    fully valid, unlockable vault whose server account may still exist.
     *  - [markServerDeleteConfirmed] writes `vault.delete-confirmed` and is written ONLY after
     *    `api.deleteAccount()` returns a definite gone (2xx / 404). ONLY this marker authorises the
     *    unlink-only [Route.DeleteIncomplete] auto-destroy: when it is present, the server account
     *    is provably gone, so destroying the local copy is always safe.
     *
     * Both are existence-signals made crash-durable with a dir-fsync (fail-closed on non-durable).
     */
    fun markDeleteIntent() {
        imageLock.withLock { writeDurableMarker(deleteIntentFile) }
    }

    fun markServerDeleteConfirmed() {
        imageLock.withLock { writeDurableMarker(serverDeletedFile) }
    }

    /**
     * Durably clear the delete-intent marker. Round 13/14 (F3): the [dirSync] result is CHECKED
     * and the marker RE-STATTED absent — [File.delete] returns false on an I/O failure just like on
     * an already-absent file, so its bool cannot be trusted. Throws [VaultImageException.DestroyFailed]
     * if the marker survives (unlink failed) or the retire is not crash-durable; a no-op (already
     * absent) succeeds.
     */
    fun clearDeleteIntent() {
        imageLock.withLock {
            // Tristate (round 15, R14-2): only a CONFIRMED absence (Files.notExists) is a no-op;
            // present-or-indeterminate falls through to the durable clear + verify below. Using
            // File.exists() here would skip clearing a present-but-unstatable marker.
            if (Files.notExists(deleteIntentFile.toPath())) return@withLock
            deleteIntentFile.delete()
            if (!Files.notExists(deleteIntentFile.toPath()) || dirSync(baseDir) != DirSyncResult.DURABLE) {
                throw VaultImageException.DestroyFailed()
            }
        }
    }

    /**
     * Delete BOTH delete markers and confirm the retire crash-durably by RE-STAT — never by
     * trusting [File.delete]'s bool (false on an I/O failure too). Returns true iff both markers
     * re-stat ABSENT AND the directory fsync is [DirSyncResult.DURABLE]. Idempotent (already-absent
     * markers succeed). The single choke point for the marker-retirement discipline used by
     * [create] (F2) and [destroy] (F4). Caller must hold [imageLock].
     */
    private fun clearBothMarkersDurably(): Boolean {
        deleteIntentFile.delete()
        serverDeletedFile.delete()
        val durable = dirSync(baseDir) == DirSyncResult.DURABLE
        // TRISTATE re-stat (round 15, R14-2): File.exists()==false conflates "absent" with "stat
        // could not be determined" (I/O/permission failure), so trusting it would report a marker
        // that SURVIVED an unlink as gone. Files.notExists returns true ONLY when the path is
        // confirmed absent — present OR indeterminate both yield false, so the clear is proven
        // only on a definite absence (fail-closed).
        return durable &&
            Files.notExists(deleteIntentFile.toPath()) &&
            Files.notExists(serverDeletedFile.toPath())
    }

    /** Create [file] + dir-fsync it; throw [VaultImageException.DestroyFailed] if not durable. */
    private fun writeDurableMarker(file: File) {
        val durable = runCatching {
            file.createNewFile()
            file.exists() && dirSync(baseDir) == DirSyncResult.DURABLE
        }.getOrDefault(false)
        if (!durable) {
            throw VaultImageException.DestroyFailed()
        }
    }

    fun destroy() {
        imageLock.withLock {
            // Wipe live key material + drop the cached image FIRST — before even the marker gate
            // can throw — so no DEK/plaintext-adjacent state is retained on ANY exit. A destroy
            // request is terminal for this store's usefulness regardless of outcome (the session
            // is already torn down); the retry path never needs the cached DEK.
            dek?.let { wipe(it) }
            dek = null
            canonical = null
            // CONFIRMED MARKER before the unlinks (crash/restart continuity): reaching destroy()
            // means the server account is confirmed gone, so write `vault.delete-confirmed`
            // durably BEFORE unlinking. A crash mid-unlink then restarts into
            // [Route.DeleteIncomplete] (the CONFIRMED marker is present) and re-runs this
            // idempotent destroy — never a lock gate over a gone account. REQUIRED-DURABLE: if it
            // can't be written+fsynced, ABORT with the vault files untouched (throw). Idempotent
            // with [markServerDeleteConfirmed], which the delete flow calls first — then a no-op.
            writeDurableMarker(serverDeletedFile)
            // Remove BOTH persisted files and any interrupted-write temps. delete() is
            // best-effort and never throws on a missing file (returns false) — idempotent.
            binFile.delete()
            dekFile.delete()
            deleteLeftoverTmp(binFile)
            deleteLeftoverTmp(dekFile)
            // Release the single-instance registration so a fresh create() may re-open this
            // directory in the SAME process (re-onboard after account deletion).
            unregister()
            // VERIFY everything image-bearing is actually GONE (see kdoc): delete()'s bool is
            // false on an I/O error too, so re-stat instead. The TEMPS are part of the check
            // (round 8, Codex): renameIntoPlace stages the COMPLETE outer image in vault.bin.tmp
            // (and the wrapped DEK in vault.dek.tmp), so under the same failing filesystem this
            // verify exists to catch, an encrypted image copy could survive as a temp while the
            // primaries are gone. A surviving file → destruction FAILED → throw; account-delete
            // treats this as NOT-deleted. exists()==false (already-absent) does NOT throw,
            // keeping destroy() idempotent.
            if (binFile.exists() || dekFile.exists() ||
                leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists()
            ) {
                throw VaultImageException.DestroyFailed()
            }
            // Make the unlinks CRASH-DURABLE before retiring the markers (round 8, Codex): the
            // exists() re-stat proves only the current namespace, not what a journal replay
            // restores. Without this fsync, a crash could resurrect vault.bin/vault.dek while the
            // markers' own (later) unlink survived — restarting into DeleteIncomplete over a
            // now-present image, the exact state the markers exist to signal. A non-durable sync
            // keeps the markers (throw → retry re-runs the idempotent destroy), never false success.
            if (dirSync(baseDir) != DirSyncResult.DURABLE) {
                throw VaultImageException.DestroyFailed()
            }
            // Unlinks confirmed durable — retire BOTH markers, verified by RE-STAT + a required
            // fsync (round 13 Grok P1-2 / round 14 F4): trusting File.delete()'s bool would let a
            // silent unlink failure leave a marker that a journal replay resurrects over a later
            // SUCCESSOR vault → DeleteIncomplete → auto-destroy of a valid re-onboarded vault. A
            // marker that survives the delete, or a non-durable retire, is a FAILED destroy (throw):
            // marker-present + files-absent is the safe stuck state (a retry re-stats the files
            // absent and re-runs the retire). Self-healing over the empty image, now also correct.
            if (!clearBothMarkersDurably()) {
                throw VaultImageException.DestroyFailed()
            }
        }
    }

    /**
     * True once [markServerDeleteConfirmed] has run: the server account is provably gone and the
     * local image must be destroyed. The ONLY authorisation for the unlink-only
     * [Route.DeleteIncomplete] auto-destroy. (Replaces the round-12 `destroyPending`, which
     * conflated intent with confirmation — the P1-A/P1-1 root.)
     */
    fun serverDeleteConfirmed(): Boolean = imageLock.withLock { serverDeletedFile.exists() }

    /**
     * True while a delete was INITIATED but the server delete is not confirmed (intent marker
     * present, confirmed absent) — a crash/failure mid-delete. The vault is still valid and the
     * server account may still exist, so boot routes to normal unlock (NOT auto-destroy) and, on the
     * next live session, RECONCILES by retrying the authenticated DELETE (round 14). It never
     * authorises destruction and is retired only by a confirmed [destroy] — never cleared on boot.
     */
    fun deleteIntentPending(): Boolean =
        imageLock.withLock { deleteIntentFile.exists() && !serverDeletedFile.exists() }

    /**
     * True while the DURABLE delete-intent marker is present — from its durable write until a
     * confirmed [destroy] retires it, spanning every not-confirmed exit AND process restart (round
     * 16, R15-P2). This is the auth-protection lifetime: while it holds, no auth-clearing path may
     * strip the vault-backed tokens, because a future reconcile may need them to reach the
     * idempotent 404. Deliberately NOT `&& !confirmed` (unlike [deleteIntentPending]): a
     * confirmed marker that was created but not fsync-durable ([MessagingCoordinator]'s
     * onConfirmedNotDurable) can vanish on a crash, dropping back to an intent-only reconcile that
     * still needs auth — so auth is protected while the intent file is present, regardless of the
     * confirmed marker (harmlessly true through the brief confirmed→destroy window, where auth is
     * about to be destroyed anyway).
     *
     * FAIL-CLOSED re-stat (round 16, R16-R2 / Codex): this is an auth-PROTECTION read — the guard
     * skips clearing tokens when this is true — so an indeterminate stat must protect, not expose.
     * `File.exists()==false` conflates "absent" with "stat could not be determined", which would
     * fail OPEN (permit the token clear) on an I/O fault while the intent is present. `!Files.notExists`
     * is true when the marker is present OR indeterminate (`Files.notExists` is true ONLY on a proven
     * absence), so auth is protected unless the intent is provably gone. (This is the opposite bias
     * from the routing readers, where an indeterminate false correctly withholds auto-destroy.)
     */
    fun hasDeleteIntentMarker(): Boolean =
        imageLock.withLock { !Files.notExists(deleteIntentFile.toPath()) }

    /**
     * Claim the single-instance registration for [baseDir] (see class kdoc). Idempotent
     * for THIS instance (a re-open no-ops); throws [IllegalStateException] if a DIFFERENT
     * instance already holds the directory. The compound check-then-add is atomic under
     * the [OPEN_PATHS] monitor so two instances racing on the same directory cannot both
     * acquire it. Always called under [imageLock].
     */
    private fun register() {
        val path = baseDir.canonicalFile.path
        synchronized(OPEN_PATHS) {
            if (registeredPath == path) return // idempotent: this instance already owns it
            check(path !in OPEN_PATHS) { "a VaultImageStore is already open for this directory" }
            OPEN_PATHS.add(path)
            registeredPath = path
        }
    }

    /** Release this instance's single-instance registration, if any. Idempotent; always
     *  called under [imageLock]. */
    private fun unregister() {
        val path = registeredPath ?: return
        OPEN_PATHS.remove(path)
        registeredPath = null
    }

    /**
     * Write [bytes] to `<name>.tmp` in the SAME directory, `FileChannel.force(true)` (fsync
     * file content + metadata), and atomically move it over the target via [Files.move] with
     * [StandardCopyOption.ATOMIC_MOVE] (a same-dir atomic rename on ext4/f2fs). Does EVERYTHING
     * [atomicWrite] does EXCEPT the trailing directory fsync — so a caller can batch several
     * renames under a SINGLE trailing [dirSync] (see [create], which renames both files then
     * does one directory fsync covering both).
     *
     * THROWS on any PRE-rename failure (ensure-parent, tmp write, content-fsync, or the move
     * itself), best-effort deleting the `.tmp` first, then rethrowing. The move is
     * ATOMIC-OR-THROWS: [Files.move] with ATOMIC_MOVE either fully replaces the target or throws
     * — never a torn/half state — so a THROW leaves the target (previous durable file) UNTOUCHED
     * and means NOTHING was committed for this file. A platform that cannot perform an atomic move
     * throws [java.nio.file.AtomicMoveNotSupportedException] (an [IOException] subclass), which
     * propagates as a pre-rename failure (retryable, target intact); we deliberately do NOT fall
     * back to a non-atomic move — that would break the atomic-replace guarantee the whole
     * durability model rests on. On a SUCCESSFUL move it returns [Unit]: the new bytes ARE on disk
     * and the rename is atomic, but the rename's directory-entry DURABILITY is NOT yet confirmed —
     * the caller MUST still [dirSync] the parent before treating the rename as crash-durable
     * (ATOMIC_MOVE guarantees atomicity of the rename, never durability of the directory entry).
     */
    private fun renameIntoPlace(target: File, bytes: ByteArray) {
        // Defensive: production baseDir = filesDir always exists, so this is a no-op there,
        // but it covers a caller passing a fresh subdir that has not been created yet.
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tmp = File(target.parentFile, "${target.name}$TMP_SUFFIX")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                // fsync the file's data + metadata to disk BEFORE the rename, so the renamed
                // name can never point at a not-yet-durable inode.
                fos.channel.force(true)
            }
            // Atomic-or-throws replace: ATOMIC_MOVE either fully swaps tmp over target or throws
            // (never a torn state), REPLACE_EXISTING allows overwriting the previous durable file.
            // Files.move THROWS on failure (unlike File.renameTo's false return) — the catch below
            // cleans up tmp and rethrows, leaving the target at its previous state. A platform
            // without atomic-move support throws AtomicMoveNotSupportedException (an IOException):
            // we let it propagate as a pre-rename failure and do NOT fall back to a non-atomic
            // move, which would forfeit the atomic-replace guarantee.
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            // ANY pre-rename failure (an ENOSPC mid-write, a Files.move throw, …) must not leave
            // a variable-size `.tmp` lingering next to the constant-size files — best-effort
            // delete it, then propagate. The target (previous durable file) is untouched: an
            // ATOMIC_MOVE replaces atomically or throws, never a torn state.
            tmp.delete()
            throw t
        }
    }

    /**
     * Durable atomic write of a SINGLE file: [renameIntoPlace] then a directory fsync so the
     * rename itself survives a crash.
     *
     * THROW vs RETURN is the durability contract. This THROWS on any PRE-rename failure (via
     * [renameIntoPlace], with best-effort `.tmp` cleanup) — the target (previous durable file)
     * is untouched, so a THROW means NOTHING was committed (disk + memory unchanged, fully
     * retryable). After a SUCCESSFUL rename it RETURNS the [dirSync] result for the directory:
     * the rename is the commit point, so a RETURN means the new bytes ARE on disk and the
     * [DirSyncResult] only reports the rename's own durability ([DirSyncResult.DURABLE] /
     * [DirSyncResult.NOT_DURABLE]). Used by [writeSealedPayload] (a single file, immediate
     * durability).
     */
    private fun atomicWrite(target: File, bytes: ByteArray): DirSyncResult {
        renameIntoPlace(target, bytes)
        // Rename committed. Report the directory-entry durability (never throws — see
        // [defaultFsyncDir]); the caller decides how to act on a NOT_DURABLE result.
        return dirSync(target.parentFile)
    }

    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
    private fun leftoverTmp(target: File): File =
        File(target.parentFile, "${target.name}$TMP_SUFFIX")

    private fun deleteLeftoverTmp(target: File) {
        leftoverTmp(target).delete()
    }

    private companion object {
        const val IMAGE_FILE = "vault.bin"
        const val DEK_FILE = "vault.dek"

        /**
         * Zero-byte marker: a delete was INITIATED (server outcome unknown). Never authorises
         * destruction — see [markDeleteIntent] / [deleteIntentPending]. Existence is the only signal.
         */
        const val DELETE_INTENT_FILE = "vault.delete-intent"

        /**
         * Zero-byte marker: the server account is CONFIRMED gone and local destroy is owed. The
         * only authorisation for the unlink-only [Route.DeleteIncomplete] auto-destroy — see
         * [markServerDeleteConfirmed] / [serverDeleteConfirmed]. Existence is the only signal.
         */
        const val SERVER_DELETED_FILE = "vault.delete-confirmed"
        const val TMP_SUFFIX = ".tmp"

        /**
         * Process-wide set of canonical baseDir paths with a live VaultImageStore, enforcing
         * the single-instance-per-baseDir contract (see class kdoc). Synchronized so
         * [register] / [unregister] are safe across threads; compound check-then-add is done
         * under the set's own monitor.
         */
        private val OPEN_PATHS = java.util.Collections.synchronizedSet(HashSet<String>())

        /** The data-encryption key is a 32-byte AES-256-GCM key (== [MASTER_KEY_BYTES]). */
        const val DEK_BYTES = MASTER_KEY_BYTES
    }
}

/**
 * The production directory-fsync used by [VaultImageStore]: makes a completed rename
 * itself crash-durable via a read-only [java.nio.channels.FileChannel] over the directory
 * (the Android/Linux idiom). Never throws (Exception-broad by design; Errors still propagate) — it
 * maps every outcome onto a [DirSyncResult] so
 * [VaultImageStore.writeSealedPayload] can act on it without a control-flow exception. Only a
 * CONFIRMED successful directory fsync is [DirSyncResult.DURABLE]; every other outcome is
 * [DirSyncResult.NOT_DURABLE] so the vault FAILS CLOSED (a write never falsely reports durable)
 * rather than risk a false flush-before-ack:
 *  - could NOT open the directory channel (some filesystems refuse a directory FileChannel):
 *    [DirSyncResult.NOT_DURABLE]. A rename is NOT guaranteed crash-durable just because the file
 *    CONTENT was fsynced (in [VaultImageStore] `atomicWrite`) — only a successful directory fsync
 *    confirms the rename's directory entry. On minSdk-26 Android over ext4/f2fs the directory
 *    channel ALWAYS opens, so this can't-open path is not reachable in production; but if a platform
 *    genuinely cannot fsync a directory, the vault fails closed here rather than risk a false ack.
 *  - `force(true)` FAILING on a SUCCESSFULLY-OPENED channel: [DirSyncResult.NOT_DURABLE] — a
 *    real I/O error (EIO). The caller must not report the write durable / must not ack.
 *  - both succeed: [DirSyncResult.DURABLE] — the ONLY confirmed-durable outcome.
 *
 * A null [dir] is [DirSyncResult.NOT_DURABLE] (no directory to sync → not confirmed durable).
 */
private fun defaultFsyncDir(dir: File?): DirSyncResult {
    if (dir == null) return DirSyncResult.NOT_DURABLE
    val channel = try {
        // java.nio.file requires API 26; minSdk is 26 (build.gradle.kts), so this is always
        // linkable — no LinkageError guard needed.
        java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ)
    } catch (e: Exception) {
        // Could not OPEN a directory channel — the rename's file CONTENT is already fsynced
        // (atomicWrite), but a fsynced content does NOT make the rename's directory entry durable.
        // Not reachable on minSdk-26 Android/ext4/f2fs; if it were, fail CLOSED rather than ack.
        // Exception-broad (was IOException / UnsupportedOperationException): any unexpected runtime
        // exception (InvalidPathException, SecurityException) also reads as NOT_DURABLE — fail
        // closed, never throw. A throw here would propagate through atomicWrite BEFORE its caller
        // advances canonical, desyncing the in-memory canonical from disk (the exact hazard the
        // DirSyncResult model exists to prevent). Errors (e.g. OOM) still propagate.
        return DirSyncResult.NOT_DURABLE
    }
    return try {
        channel.use { it.force(true) }
        DirSyncResult.DURABLE
    } catch (e: Exception) {
        // force() failing on an OPENED dir channel is a REAL storage error (EIO): the rename's
        // durability is unconfirmed. Signal NOT_DURABLE so the caller does not ack. Exception-broad
        // (was IOException): any unexpected runtime exception here also reads as NOT_DURABLE — fail
        // closed, never throw. A throw here would propagate through atomicWrite BEFORE its caller
        // advances canonical, desyncing the in-memory canonical from disk. Errors still propagate.
        DirSyncResult.NOT_DURABLE
    }
}
