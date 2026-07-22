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
 *   Taken as a bare [File] — no Context dependency — so it is host-unit-testable.
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

    /** True when a vault image is present on disk (`vault.bin`). */
    fun exists(): Boolean = imageLock.withLock { binFile.exists() }

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
                // allocation so an inflated bin can never OOM the process.
                if (dekFile.length() != WRAPPED_KEY_BYTES.toLong()) throw VaultImageException.CorruptImage()
                if (binFile.length() != OUTER_IMAGE_BYTES.toLong()) throw VaultImageException.CorruptImage()

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
                    // Validate the inner VERSION too, not just the size: an unknown version reads
                    // as CorruptImage for THIS build. A future format bump MUST add its migration
                    // path here BEFORE [IMAGE_VERSION] changes, or existing images stop opening.
                    if (inner[0].toInt() and 0xff != IMAGE_VERSION) throw VaultImageException.CorruptImage()
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
     * verify / IO failure) leave the disk UNTOUCHED and the whole call retryable. Only
     * then does it atomically write the DEK sidecar FIRST and REQUIRE its durability, then
     * the image (whose rename-durability may be ignored). This dek-durable-before-bin
     * ORDERING is load-bearing: create returns success only once the DEK is CONFIRMED
     * durable and the image rename has landed. A DEK-durability failure THROWS
     * [VaultImageException.NotDurable], leaving at most a stray `vault.dek` and NO
     * `vault.bin` — which [open] reports as [VaultImageException.MissingImage] (bin-keyed),
     * a fully retryable state (the next create overwrites the stray DEK), NEVER a bricked
     * image-exists-but-unopenable state (a durable bin whose dek's rename was lost). Both
     * durable writes land before any in-memory state is installed, so a mid-write throw
     * leaves canonical / dek exactly as they were (null on a fresh store). CPU-heavy:
     * caller MUST be off-main.
     */
    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
        imageLock.withLock {
            // Claim the single-instance registration BEFORE any work (mirrors open()); a
            // failed create releases only what THIS call acquired so a retry can proceed.
            val newlyRegistered = registeredPath == null
            register()
            try {
                require(!binFile.exists()) { "vault image already exists" }
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
                    // write below throws — including the new dek-durability throw — wipe it so no
                    // vault key / plaintext is abandoned on the heap (the wipe-on-every-failure
                    // discipline the package keeps).
                    try {
                        // DEK sidecar FIRST, and REQUIRE its durability. dek-durable-before-bin
                        // ordering makes the bricking case (a durable bin whose dek's rename was
                        // lost → next open() = CorruptImage AND create() rejects the retry because
                        // bin exists) impossible by construction. Rationale for throwing here: a
                        // thrown create leaves at most a stray vault.dek and NO vault.bin, which
                        // open() reports as MissingImage (bin-keyed) — so a retry create() proceeds
                        // and overwrites the stray dek: fully retryable, never bricked.
                        if (atomicWrite(dekFile, wrappedDek) != DirSyncResult.DURABLE) {
                            throw VaultImageException.NotDurable()
                        }
                        // THEN the image; its dir-fsync result may be IGNORED. The DEK is already
                        // confirmed durable, so if bin's rename is lost on a crash it's MissingImage
                        // (retryable), and if bin survives it opens fine — the bricking case (bin
                        // durable while dek is not) is now impossible.
                        atomicWrite(binFile, outer)
                    } catch (t: Throwable) {
                        wipe(liveOpen.vaultKey)
                        wipe(liveOpen.payloadPlaintext)
                        throw t
                    }

                    dek?.let { wipe(it) }
                    dek = newDek.copyOf()
                    canonical = image
                    return liveOpen
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
            require(slotIndex in 0 until SLOT_COUNT) { "slot index out of range" }
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
     * Durable atomic write: write [bytes] to `<name>.tmp` in the SAME directory,
     * `FileChannel.force(true)` (fsync file content + metadata), atomically rename
     * over the target (same-dir rename is atomic on ext4/f2fs), then fsync the
     * directory so the rename itself survives a crash.
     *
     * THROW vs RETURN is the durability contract. This THROWS on any PRE-rename failure
     * (ensure-parent, tmp write, content-fsync, or a refused rename), with best-effort
     * `.tmp` cleanup — the target (previous durable file) is untouched (a same-dir rename
     * replaces atomically or not at all), so a THROW means NOTHING was committed (disk +
     * memory unchanged, fully retryable). After a SUCCESSFUL rename it RETURNS the
     * [dirSync] result for the directory: the rename is the commit point, so a RETURN means
     * the new bytes ARE on disk and the [DirSyncResult] only reports the rename's own
     * durability ([DirSyncResult.DURABLE] / [DirSyncResult.NOT_DURABLE]).
     */
    private fun atomicWrite(target: File, bytes: ByteArray): DirSyncResult {
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
            if (!tmp.renameTo(target)) {
                throw IOException("atomic rename failed for ${target.name}")
            }
        } catch (t: Throwable) {
            // ANY pre-rename failure (an ENOSPC mid-write, a refused rename, …) must not leave
            // a variable-size `.tmp` lingering next to the constant-size files — best-effort
            // delete it, then propagate. The target (previous durable file) is untouched: a
            // same-dir rename replaces atomically or not at all.
            tmp.delete()
            throw t
        }
        // Rename committed. Report the directory-entry durability (never throws — see
        // [defaultFsyncDir]); the caller decides how to act on a NOT_DURABLE result.
        return dirSync(target.parentFile)
    }

    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
    private fun deleteLeftoverTmp(target: File) {
        File(target.parentFile, "${target.name}$TMP_SUFFIX").delete()
    }

    private companion object {
        const val IMAGE_FILE = "vault.bin"
        const val DEK_FILE = "vault.dek"
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
 * (the Android/Linux idiom). NEVER throws — it maps every outcome onto a [DirSyncResult] so
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
    } catch (e: IOException) {
        // Could not OPEN a directory channel — the rename's file CONTENT is already fsynced
        // (atomicWrite), but a fsynced content does NOT make the rename's directory entry durable.
        // Not reachable on minSdk-26 Android/ext4/f2fs; if it were, fail CLOSED rather than ack.
        return DirSyncResult.NOT_DURABLE
    } catch (e: UnsupportedOperationException) {
        return DirSyncResult.NOT_DURABLE
    }
    return try {
        channel.use { it.force(true) }
        DirSyncResult.DURABLE
    } catch (e: IOException) {
        // force() failing on an OPENED dir channel is a REAL storage error (EIO): the rename's
        // durability is unconfirmed. Signal NOT_DURABLE so the caller does not ack.
        DirSyncResult.NOT_DURABLE
    }
}
