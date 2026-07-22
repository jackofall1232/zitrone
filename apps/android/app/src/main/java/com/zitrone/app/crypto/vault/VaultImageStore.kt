// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Associated data for the image's OUTER (device-key) layer. Deliberately EMPTY:
 * the outer layer's sole job is device-bound confidentiality + integrity, and the
 * inner image already self-authenticates every region (each slot's wrapped key and
 * payload carry their own AEAD tag under [SLOT_AD] / [PAYLOAD_AD]). No magic bytes,
 * no version byte, and no AD term is stored outside the ciphertext — the inner
 * image carries its own version. `internal` so the storage tests can decrypt the
 * on-disk blob to assert on inner regions without coupling to a private constant.
 */
internal val VAULT_IMAGE_OUTER_AD: ByteArray = ByteArray(0)

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
 * LOCK-ORDER INVARIANT (load-bearing). When composed with [VaultSession] the order
 * is ALWAYS VaultSession.flushLock → [imageLock]: a flush seals under the session's
 * flushLock and only THEN hands the region to [writeSealedPayload], which takes
 * imageLock. NEVER invoke a VaultSession method while holding [imageLock] — that
 * would nest the locks in the reverse order and can deadlock.
 *
 * THREADING. Every method takes [imageLock]; all are synchronous. [open], [create],
 * [unlock], and [unlockWithKey] are CPU-heavy with the production Argon2id deriver
 * (create / unlock run it up to SLOT_COUNT times), so callers on a UI thread MUST
 * run them off the main thread.
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
class VaultImageStore(
    private val baseDir: File,
    private val ops: VaultSodiumOps,
    private val deviceCipher: DeviceKeyCipher,
    private val deriver: KeyDeriver = argon2idDeriver(ops),
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
     * auth failure, missing / unwrappable DEK, or wrong inner size). NEVER silently
     * recreates on corruption — that would destroy real vaults; the caller escalates.
     */
    fun open() {
        imageLock.withLock {
            // A leftover temp is an incomplete write; the main file is authoritative.
            deleteLeftoverTmp(binFile)
            deleteLeftoverTmp(dekFile)

            // Key on the image file: a stray DEK with no image is the fresh-install /
            // crash-between-writes state (MissingImage), not corruption.
            if (!binFile.exists()) throw VaultImageException.MissingImage()
            if (!dekFile.exists()) throw VaultImageException.CorruptImage()

            val dekBlob = dekFile.readBytes()
            val binBytes = binFile.readBytes()

            val unwrapped = deviceCipher.unwrapDek(dekBlob) ?: throw VaultImageException.CorruptImage()
            // From here `unwrapped` is live key material: wipe it on EVERY failure path,
            // and keep it ONLY on the success path (mirrors tryPassphrase's discipline).
            val inner: ByteArray
            try {
                inner = ops.aeadDecrypt(unwrapped, binBytes, VAULT_IMAGE_OUTER_AD)
                    ?: throw VaultImageException.CorruptImage()
                if (inner.size != IMAGE_BYTES) throw VaultImageException.CorruptImage()
            } catch (t: Throwable) {
                wipe(unwrapped)
                throw t
            }

            // Success: install canonical + DEK, wiping any DEK we already held.
            dek?.let { wipe(it) }
            dek = unwrapped
            canonical = inner
        }
    }

    /**
     * Create a fresh vault image sealing [initialPayload] under [passphrase], write
     * it durably, and return a live [VaultOpen] for it. Requires no image exists yet.
     *
     * Generates a random DEK, builds the image with the audited [createImage]
     * primitive, outer-encrypts it, and atomically writes the DEK sidecar FIRST then
     * the image (a crash between leaves a DEK with no image = [VaultImageException.MissingImage],
     * the DEK overwritten on the next create). It then [unlockImage]s the fresh image
     * to hand back a live open — one extra Argon2id×SLOT_COUNT, one-time at
     * onboarding / migration, reusing the audited primitive rather than adding a new
     * create-and-open surface. CPU-heavy: caller MUST be off-main.
     */
    fun create(passphrase: String, initialPayload: ByteArray): VaultOpen {
        imageLock.withLock {
            require(!binFile.exists()) { "vault image already exists" }
            val newDek = ops.randomBytes(DEK_BYTES)
            // Ephemeral here: the persisted copy lives in `dek`. Wipe the local on EVERY
            // exit, incl. if createImage / encrypt / wrap / write throws mid-way.
            try {
                val image = createImage(passphrase, initialPayload, ops, deriver)
                val outer = ops.aeadEncrypt(newDek, image, VAULT_IMAGE_OUTER_AD)
                val wrappedDek = deviceCipher.wrapDek(newDek)
                // DEK first, then image (see kdoc): the durable writes must both land
                // before any in-memory state is installed, so a mid-write throw leaves
                // canonical / dek exactly as they were (null on a fresh store).
                atomicWrite(dekFile, wrappedDek)
                atomicWrite(binFile, outer)

                val liveOpen = unlockImage(passphrase, image, ops, deriver)
                    ?: throw IllegalStateException("freshly created image failed to open")
                dek?.let { wipe(it) }
                dek = newDek.copyOf()
                canonical = image
                return liveOpen
            } finally {
                wipe(newDek)
            }
        }
    }

    /**
     * Attempt [passphrase] against the current image (opening from disk first if
     * needed). Returns a live [VaultOpen] on a match, or null on none — an
     * indistinguishable wrong passphrase, identical work regardless of outcome
     * (inherited from [tryPassphrase]). CPU-heavy: caller MUST be off-main.
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
     * Requires the store is open. Returns only once the bytes are durable. On ANY
     * throw (not open, wrong size, encrypt / IO failure) [canonical] is left
     * UNCHANGED and the on-disk image still opens to the PREVIOUS state, so the
     * session stays dirty and retries — a flush-before-ack caller must NOT ack.
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
            atomicWrite(binFile, outer)
            // Durable: commit the new canonical. Reached only if every step above
            // succeeded, so a partial write never advances in-memory state.
            canonical = spliced
        }
    }

    /**
     * Wipe the DEK and drop the canonical image. Store open/close is device-level
     * and independent of any vault's lock — the outer layer is not a slot's secret,
     * so keeping the store open across vault locks is fine; this exists for tests /
     * teardown. Idempotent.
     */
    fun close() {
        imageLock.withLock {
            dek?.let { wipe(it) }
            dek = null
            canonical = null
        }
    }

    /**
     * Durable atomic write: write [bytes] to `<name>.tmp` in the SAME directory,
     * `FileChannel.force(true)` (fsync file content + metadata), atomically rename
     * over the target (same-dir rename is atomic on ext4/f2fs), then best-effort
     * fsync the directory so the rename itself survives a crash.
     *
     * On failure the target (previous durable file) is left intact — a same-dir
     * rename replaces atomically or not at all — so a throw here never advances the
     * on-disk state. Only the DIRECTORY fsync failure is swallowed (see [fsyncDir]).
     */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}$TMP_SUFFIX")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            // fsync the file's data + metadata to disk BEFORE the rename, so the renamed
            // name can never point at a not-yet-durable inode.
            fos.channel.force(true)
        }
        if (!tmp.renameTo(target)) {
            // Rename failed: drop the temp and fail loud, leaving the target intact.
            tmp.delete()
            throw IOException("atomic rename failed for ${target.name}")
        }
        fsyncDir(target.parentFile)
    }

    /**
     * Best-effort directory fsync so a completed rename is itself durable. Some
     * platforms refuse to fsync a directory fd (IOException); that failure — and
     * ONLY that failure — is swallowed: the file CONTENT is already fsynced above,
     * so rename durability is best-effort-plus on Android's ext4/f2fs defaults.
     */
    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        try {
            FileInputStream(dir).use { it.fd.sync() }
        } catch (e: IOException) {
            // Directory fsync unsupported on this platform — see kdoc. Swallowed.
        }
    }

    /** Delete an incomplete-write temp for [target], if any. Best-effort. */
    private fun deleteLeftoverTmp(target: File) {
        File(target.parentFile, "${target.name}$TMP_SUFFIX").delete()
    }

    private companion object {
        const val IMAGE_FILE = "vault.bin"
        const val DEK_FILE = "vault.dek"
        const val TMP_SUFFIX = ".tmp"

        /** The data-encryption key is a 32-byte AES-256-GCM key (== [MASTER_KEY_BYTES]). */
        const val DEK_BYTES = MASTER_KEY_BYTES
    }
}
