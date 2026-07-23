// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.AEAD_TAG_BYTES
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.DirSyncResult
import com.zitrone.app.crypto.vault.IMAGE_BYTES
import com.zitrone.app.crypto.vault.IMAGE_VERSION
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
import com.zitrone.app.crypto.vault.OUTER_IMAGE_BYTES
import com.zitrone.app.crypto.vault.SLOT_COUNT
import com.zitrone.app.crypto.vault.SLOT_PAYLOAD_BYTES
import com.zitrone.app.crypto.vault.VAULT_IMAGE_OUTER_AD
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultImageException
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import com.zitrone.app.crypto.vault.decodeImage
import com.zitrone.app.crypto.vault.openPayload
import com.zitrone.app.crypto.vault.sealPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Storage-layer tests for [VaultImageStore]. The AEAD + CSPRNG path is ALWAYS the
 * real production byte path — [LibsodiumVaultOps] over SodiumJava — and files are
 * written to a real temp directory, so the durability / atomicity behavior is
 * exercised end to end. Only two things are swapped for host testing:
 *
 *  - the CPU-heavy Argon2id KDF, for a fast deterministic SHA-256 stand-in ([fast]),
 *    the same convention VaultPrimitiveTest / VaultSessionTest use; and
 *  - the Android Keystore device key, for a fixed-key `javax.crypto` AES-256-GCM
 *    fake ([FakeDeviceKeyCipher]) — the real [KeystoreDeviceKeyCipher] binds Android
 *    SDK classes that cannot load in a host JVM. The fixed key models one install's
 *    single non-exportable device key: every store here shares one [cipher], exactly
 *    as every store on a device shares the one Keystore key.
 *
 * Test 7 is the load-bearing proof that the PR-A session and this PR-B store compose:
 * a real [VaultSession] persisting through `store::writeSealedPayload` survives a
 * fresh-from-disk reopen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Real AEAD + CSPRNG, the on-device byte path.
    private val ops = LibsodiumVaultOps(SodiumJava())

    /** Fast, deterministic stand-in for Argon2id: SHA-256(passphrase ‖ salt). */
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    /** One fixed device key for the whole test — models the single per-install Keystore key. */
    private val cipher = FakeDeviceKeyCipher()

    private val passphrase = "correct horse battery staple"

    private fun newStore(dir: File) = VaultImageStore(dir, ops, cipher, fast)

    /** A store whose post-rename directory fsync is REPLACED by an injected [dirSync], so the
     *  DURABLE / NOT_DURABLE durability branches are driven deterministically without a
     *  real EIO. Mirrors the package's inject-for-tests convention (ops / deriver / clock). */
    private fun newStore(dir: File, dirSync: (File?) -> DirSyncResult) =
        VaultImageStore(dir, ops, cipher, fast, dirSync)

    /** Decrypt the on-disk envelope back to the inner image bytes — for asserting on
     *  inner regions the outer layer would otherwise hide behind a fresh nonce. */
    private fun decodeOnDiskInner(dir: File): ByteArray {
        val dek = cipher.unwrapDek(File(dir, "vault.dek").readBytes())!!
        return ops.aeadDecrypt(dek, File(dir, "vault.bin").readBytes(), VAULT_IMAGE_OUTER_AD)!!
    }

    /** Outer-encrypt an ARBITRARY inner blob under the DEK this dir's vault.dek wraps,
     *  overwriting vault.bin — for crafting corrupt-inner cases (wrong size / version) the
     *  outer AEAD layer still authenticates, so only the inner validation can reject them. */
    private fun rewriteBinWithInner(dir: File, inner: ByteArray) {
        val dek = cipher.unwrapDek(File(dir, "vault.dek").readBytes())!!
        File(dir, "vault.bin").writeBytes(ops.aeadEncrypt(dek, inner, VAULT_IMAGE_OUTER_AD))
    }

    // ── 1. create → exists → open → unlock; wrong passphrase → null ──────────────

    @Test
    fun createExistsOpenUnlock_roundTrips_wrongPassphraseNull() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        assertFalse("no image before create", store.exists())

        val content = "the real keystore bytes".toByteArray(Charsets.UTF_8)
        val created = store.create(passphrase, content)
        assertArrayEquals("create returns a live open", content, created.payloadPlaintext)
        assertTrue("image exists after create", store.exists())

        // Close first: only ONE store per dir may be live at once, so a fresh store reading
        // from disk models a process restart (the old process is gone).
        store.close()

        // A fresh store, reading only from disk, opens and unlocks.
        val fresh = newStore(dir)
        fresh.open()
        val opened = fresh.unlock(passphrase)
        assertNotNull(opened)
        assertArrayEquals(content, opened!!.payloadPlaintext)
        assertNull("wrong passphrase yields null", fresh.unlock("wrong-pass"))
    }

    // ── 2. writeSealedPayload round-trip; other regions + slot table byte-identical ─

    @Test
    fun writeSealedPayload_roundTrips_otherRegionsAndSlotTableByteIdentical() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))
        val slotIndex = open.slotIndex

        val innerBefore = decodeOnDiskInner(dir)
        val updated = "resealed ratchet state, quite different".toByteArray(Charsets.UTF_8)
        store.writeSealedPayload(slotIndex, sealPayload(open.vaultKey, updated, ops))
        val innerAfter = decodeOnDiskInner(dir)

        assertEquals(IMAGE_BYTES, innerBefore.size)
        assertEquals(IMAGE_BYTES, innerAfter.size)

        val before = decodeImage(innerBefore)
        val after = decodeImage(innerAfter)
        // The whole slot table (every salt + wrapped key) is carried through untouched —
        // a payload write never rewrites a slot entry.
        for (i in 0 until SLOT_COUNT) {
            assertArrayEquals("slot $i salt unchanged", before.slots[i].salt, after.slots[i].salt)
            assertArrayEquals("slot $i wrapped unchanged", before.slots[i].wrapped, after.slots[i].wrapped)
        }
        // Only the target payload region changed; every other region is byte-identical.
        for (i in 0 until SLOT_COUNT) {
            if (i == slotIndex) {
                assertFalse("target region rewritten", before.payloads[i].contentEquals(after.payloads[i]))
            } else {
                assertArrayEquals("other payload region unchanged", before.payloads[i], after.payloads[i])
            }
        }
        // Fresh store from disk unlocks to the updated payload, at the same slot. Close the
        // first store: one store per dir, so this models the old process being gone.
        store.close()
        val reopened = newStore(dir).unlock(passphrase)!!
        assertArrayEquals(updated, reopened.payloadPlaintext)
        assertEquals(slotIndex, reopened.slotIndex)
    }

    // ── 3. unlockWithKey (biometric / dual-wrap path) ────────────────────────────

    @Test
    fun unlockWithKey_rightKeyAndIndexOpens_wrongNull_inputUntouched_returnsCopy() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val content = "hidden profile keystore".toByteArray(Charsets.UTF_8)
        val open = store.create(passphrase, content)
        val slotIndex = open.slotIndex

        // Right key + index opens to the content.
        val keyInput = open.vaultKey.copyOf() // an input we own
        val opened = store.unlockWithKey(keyInput, slotIndex)
        assertNotNull(opened)
        assertArrayEquals(content, opened!!.payloadPlaintext)
        // The store did NOT wipe the caller's input.
        assertFalse("store must not wipe the caller's key", keyInput.all { it == 0.toByte() })
        // The returned key is an INDEPENDENT copy: wiping our input leaves it intact.
        keyInput.fill(0)
        assertFalse("returned key is an independent copy", opened.vaultKey.all { it == 0.toByte() })

        // Wrong index (a filler region) → null; wrong key → null.
        val wrongIndex = (0 until SLOT_COUNT).first { it != slotIndex }
        assertNull("wrong index yields null", store.unlockWithKey(open.vaultKey, wrongIndex))
        assertNull("wrong key yields null", store.unlockWithKey(ByteArray(VAULT_KEY_BYTES) { 0x42 }, slotIndex))

        // Out-of-range index is a caller bug, not a corruption surface.
        assertThrows(IllegalArgumentException::class.java) { store.unlockWithKey(open.vaultKey, SLOT_COUNT) }
    }

    // ── 4. Atomicity / crash ─────────────────────────────────────────────────────

    // (a) a leftover .tmp from a simulated crash is ignored + cleaned; main file intact.
    @Test
    fun open_ignoresAndCleansALeftoverTmp_mainFileIntact() {
        val dir = tmp.newFolder()
        val content = "state before crash".toByteArray(Charsets.UTF_8)
        // A real crash ends the process and releases the single-instance registration; in one
        // JVM we emulate that by close()ing the crashed store before reopening.
        val crashed = newStore(dir)
        crashed.create(passphrase, content)
        crashed.close()

        // Half-finished writes leave temp files next to the durable originals.
        File(dir, "vault.bin.tmp").writeBytes(ByteArray(123) { 0x7e })
        File(dir, "vault.dek.tmp").writeBytes(ByteArray(9) { 0x55 })

        val fresh = newStore(dir)
        fresh.open()
        assertFalse("bin .tmp cleaned on open", File(dir, "vault.bin.tmp").exists())
        assertFalse("dek .tmp cleaned on open", File(dir, "vault.dek.tmp").exists())
        assertArrayEquals(content, fresh.unlock(passphrase)!!.payloadPlaintext)
    }

    // (b) + (c) an IO failure mid-write leaves canonical unchanged, the on-disk file
    // still opens to the PREVIOUS state, and the throw propagates.
    @Test
    fun writeSealedPayload_ioFailureLeavesCanonicalUnchanged_diskOpensToPreviousState() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val original = "original state".toByteArray(Charsets.UTF_8)
        val open = store.create(passphrase, original)

        // Force the next atomicWrite(vault.bin) to fail: make its temp path a directory,
        // so FileOutputStream(vault.bin.tmp) throws (Is a directory). Works even as root.
        val blocker = File(dir, "vault.bin.tmp")
        assertTrue(blocker.mkdir())

        val updated = "state that must NOT land".toByteArray(Charsets.UTF_8)
        val sealed = sealPayload(open.vaultKey, updated, ops)
        // (c) the throw propagates out of writeSealedPayload.
        assertThrows(IOException::class.java) { store.writeSealedPayload(open.slotIndex, sealed) }

        // Canonical unchanged: the same store still unlocks to the ORIGINAL.
        assertArrayEquals(original, store.unlock(passphrase)!!.payloadPlaintext)

        // tmp hygiene: the failed write cleaned up its own .tmp (here the blocker dir it
        // could not open), leaving no variable-size leftover next to the constant-size
        // files. A FRESH store from disk still opens to the ORIGINAL — the durable bin file
        // was never advanced.
        assertFalse("atomicWrite cleans its .tmp on any failure", blocker.exists())
        store.close() // one store per dir: release before a fresh store reads from disk
        assertArrayEquals(original, newStore(dir).unlock(passphrase)!!.payloadPlaintext)
    }

    // ── 5. Corruption is surfaced distinctly and NEVER silently repaired ──────────

    @Test
    fun corruption_surfacesDistinctly_neverRecreated() {
        // A flipped byte in vault.bin → the outer GCM tag fails → CorruptImage.
        run {
            val dir = tmp.newFolder()
            val first = newStore(dir)
            first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
            first.close() // release the dir so a fresh store can attempt the corrupt open
            val bin = File(dir, "vault.bin")
            val bytes = bin.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
            bin.writeBytes(bytes)
            assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
            // Never silently repaired: the (corrupt) image is left on disk, not recreated.
            assertTrue("corrupt image not destroyed", bin.exists())
        }
        // A missing vault.dek with an existing vault.bin → CorruptImage.
        run {
            val dir = tmp.newFolder()
            val first = newStore(dir)
            first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
            first.close() // release the dir so a fresh store can attempt the corrupt open
            assertTrue(File(dir, "vault.dek").delete())
            assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
        }
        // Both missing → MissingImage (the fresh-install state).
        run {
            val dir = tmp.newFolder()
            assertThrows(VaultImageException.MissingImage::class.java) { newStore(dir).open() }
        }
        // A stray wrapped DEK with no image (crash between the two writes) → MissingImage.
        run {
            val dir = tmp.newFolder()
            File(dir, "vault.dek").writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 0x33 })
            assertThrows(VaultImageException.MissingImage::class.java) { newStore(dir).open() }
        }
    }

    // ── 6. Constant file sizes — reveal nothing about slot count ──────────────────

    @Test
    fun files_areConstantSize_afterCreateAndEveryWrite() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))

        assertEquals("vault.bin is IMAGE_BYTES + 28", IMAGE_BYTES + 28, File(dir, "vault.bin").length().toInt())
        assertEquals("vault.dek is 60", WRAPPED_KEY_BYTES, File(dir, "vault.dek").length().toInt())

        repeat(3) { k ->
            store.writeSealedPayload(open.slotIndex, sealPayload(open.vaultKey, "update-$k".toByteArray(Charsets.UTF_8), ops))
            assertEquals("vault.bin constant after write $k", IMAGE_BYTES + 28, File(dir, "vault.bin").length().toInt())
            assertEquals("vault.dek constant after write $k", WRAPPED_KEY_BYTES, File(dir, "vault.dek").length().toInt())
        }
    }

    // ── 7. Session integration: PR-A session + PR-B store compose ─────────────────

    @Test
    fun session_persistsThroughStore_freshStoreUnlocksToTheUpdatedPayload() = runTest {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))

        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = store::writeSealedPayload, // THE composition point
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext, // keep any ceiling in virtual time
        )
        val updated = "ratchet-state-after-receive".toByteArray(Charsets.UTF_8)

        session.update(updated)
        session.flushNow() // synchronous, durable persist through store.writeSealedPayload
        session.close()
        store.close() // one store per dir: release before the fresh store reads from disk

        // A fresh store instance, reading ONLY from disk, unlocks to the UPDATED payload.
        val reopened = newStore(dir).unlock(passphrase)
        assertNotNull("fresh store must unlock", reopened)
        assertArrayEquals("PR-A session + PR-B store compose end to end", updated, reopened!!.payloadPlaintext)
    }

    // ── 8. Lock sanity: concurrent writes serialize, no torn canonical ────────────

    @Test
    fun concurrentWriteSealedPayload_serializes_noTornCanonical() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))
        val key = open.vaultKey.copyOf()
        val slotIndex = open.slotIndex

        val payloadA = "AAAA-thread-a-final-state".toByteArray(Charsets.UTF_8)
        val payloadB = "BBBB-thread-b-final-state".toByteArray(Charsets.UTF_8)
        val iterations = 50
        val a = Thread { repeat(iterations) { store.writeSealedPayload(slotIndex, sealPayload(key, payloadA, ops)) } }
        val b = Thread { repeat(iterations) { store.writeSealedPayload(slotIndex, sealPayload(key, payloadB, ops)) } }
        a.start(); b.start(); a.join(); b.join()

        // No torn write: the on-disk image is the constant size and a fresh store opens
        // to ONE complete, valid payload (never a byte-spliced mix of the two).
        assertEquals(IMAGE_BYTES + 28, File(dir, "vault.bin").length().toInt())
        store.close() // one store per dir: release before the fresh store reads from disk
        val reopened = newStore(dir).unlock(passphrase)!!.payloadPlaintext
        assertTrue(
            "final region is one intact payload, not a torn mix",
            reopened.contentEquals(payloadA) || reopened.contentEquals(payloadB),
        )
    }

    // ── 9. A stray vault.dek with no image is MissingImage, and create() recovers ─────

    @Test
    fun strayDekWithoutBin_opensMissing_thenCreateSucceedsAndUnlocks() {
        val dir = tmp.newFolder()
        // A wrapped DEK with no image — a crash between the store's two writes.
        File(dir, "vault.dek").writeBytes(ByteArray(WRAPPED_KEY_BYTES) { 0x33 })

        // open() reads the fresh-install state, NOT corruption.
        assertThrows(VaultImageException.MissingImage::class.java) { newStore(dir).open() }

        // create() overwrites the stray DEK and produces a working vault.
        val content = "genesis after a stray dek".toByteArray(Charsets.UTF_8)
        val creator = newStore(dir)
        val open = creator.create(passphrase, content)
        assertArrayEquals(content, open.payloadPlaintext)
        creator.close() // one store per dir: release before the fresh store reads from disk

        val reopened = newStore(dir).unlock(passphrase)
        assertNotNull("fresh store unlocks after recovery", reopened)
        assertArrayEquals(content, reopened!!.payloadPlaintext)
    }

    // ── 10. A COMPLETE, valid leftover vault.bin.tmp is discarded — the main file wins ─

    @Test
    fun open_discardsACompleteValidLeftoverTmp_mainFileWins() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, "content-A".toByteArray(Charsets.UTF_8))

        // Snapshot the (complete, current-DEK-decryptable) image that seals content-A.
        val staleButValidBin = File(dir, "vault.bin").readBytes()

        // Advance the durable state to content-B (a payload write re-encrypts under the SAME
        // DEK, so the snapshot above stays a fully valid image — just stale).
        val contentB = "content-B is the authoritative state".toByteArray(Charsets.UTF_8)
        store.writeSealedPayload(open.slotIndex, sealPayload(open.vaultKey, contentB, ops))

        // Drop that stale-but-complete A image next to it as a leftover .tmp.
        File(dir, "vault.bin.tmp").writeBytes(staleButValidBin)
        assertEquals("leftover tmp is a full-size image", IMAGE_BYTES + 28, File(dir, "vault.bin.tmp").length().toInt())

        store.close() // one store per dir: release before the fresh store reads from disk
        val fresh = newStore(dir)
        fresh.open()
        assertFalse("complete leftover tmp discarded on open", File(dir, "vault.bin.tmp").exists())
        assertArrayEquals("main file wins over a complete leftover tmp", contentB, fresh.unlock(passphrase)!!.payloadPlaintext)
    }

    // ── 11. Lifecycle guards: write-before-open / after-close, idempotence, re-open ───

    @Test
    fun lifecycleGuards_writeBeforeOpenAndAfterClose_closeIdempotent_openTwice_createOnExisting() = runTest {
        val dir = tmp.newFolder()

        // writeSealedPayload before any open/create → not open.
        assertThrows(IllegalStateException::class.java) {
            newStore(dir).writeSealedPayload(0, ByteArray(SLOT_PAYLOAD_BYTES))
        }

        val store = newStore(dir)
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))

        // open() twice is safe (re-reads disk, re-installs canonical).
        store.open()
        store.open()
        assertArrayEquals("genesis".toByteArray(Charsets.UTF_8), store.unlock(passphrase)!!.payloadPlaintext)

        // create() on an existing image → require failure.
        assertThrows(IllegalArgumentException::class.java) {
            store.create(passphrase, "second".toByteArray(Charsets.UTF_8))
        }

        // A live session composed with the store BEFORE it closes — so we can prove the
        // session-stays-dirty property once the store's persist sink starts throwing.
        val session = VaultSession(
            scope = backgroundScope,
            ops = ops,
            initialPayload = open.payloadPlaintext,
            initialVaultKey = open.vaultKey,
            slotIndex = open.slotIndex,
            persist = store::writeSealedPayload, // the composition point
            clock = { currentTime },
            cooldownMs = 2_000L,
            flushContext = EmptyCoroutineContext,
        )

        // close() then a direct write → not open; and close() is idempotent.
        store.close()
        store.close()
        assertThrows(IllegalStateException::class.java) {
            store.writeSealedPayload(0, ByteArray(SLOT_PAYLOAD_BYTES))
        }

        // The store is closed → its persist sink throws → flushNow rethrows and the session
        // stays DIRTY (a clean session's flushNow is a silent no-op). A second flushNow still
        // throws, proving it never went clean.
        session.update("dirtying update".toByteArray(Charsets.UTF_8))
        assertThrows(IllegalStateException::class.java) { session.flushNow() }
        assertThrows(IllegalStateException::class.java) { session.flushNow() }
        // close() force-flushes too, so it also rethrows — but still wipes (teardown never
        // leaks). Swallow the expected throw.
        assertThrows(IllegalStateException::class.java) { session.close() }
    }

    // ── 12. Corrupt-but-present DEK, wrong inner size, wrong inner version → Corrupt ──

    @Test
    fun corruptDek_wrongInnerSize_wrongInnerVersion_allSurfaceCorrupt() {
        // (a) a flipped byte in vault.dek → the DEK unwrap fails → CorruptImage.
        run {
            val dir = tmp.newFolder()
            val first = newStore(dir)
            first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
            first.close() // release the dir so a fresh store can attempt the corrupt open
            val dek = File(dir, "vault.dek")
            val bytes = dek.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
            dek.writeBytes(bytes)
            assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
        }
        // (b) a VALID outer layer over a WRONG-SIZED inner → CorruptImage (size check).
        run {
            val dir = tmp.newFolder()
            val first = newStore(dir)
            first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
            first.close() // release the dir so a fresh store can attempt the corrupt open
            rewriteBinWithInner(dir, ByteArray(IMAGE_BYTES - 1) { 0x02 })
            assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
        }
        // (c) a valid, right-sized inner whose VERSION byte is unknown → CorruptImage.
        run {
            val dir = tmp.newFolder()
            val first = newStore(dir)
            first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
            first.close() // release the dir so a fresh store can attempt the corrupt open
            val inner = decodeOnDiskInner(dir)
            inner[0] = (IMAGE_VERSION + 1).toByte() // an unknown future version for THIS build
            rewriteBinWithInner(dir, inner)
            assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
        }
    }

    // ── 13. A wrong-size sealedPayload is rejected before any write; canonical intact ─

    @Test
    fun writeSealedPayload_wrongSize_requireFails_canonicalUnchanged() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val original = "original ratchet state".toByteArray(Charsets.UTF_8)
        val open = store.create(passphrase, original)

        // A region that is not exactly SLOT_PAYLOAD_BYTES → require failure, before any write.
        assertThrows(IllegalArgumentException::class.java) {
            store.writeSealedPayload(open.slotIndex, ByteArray(SLOT_PAYLOAD_BYTES - 1))
        }
        // Canonical untouched: the same store still unlocks to the ORIGINAL, and a fresh
        // store from disk does too (nothing was written).
        assertArrayEquals(original, store.unlock(passphrase)!!.payloadPlaintext)
        store.close() // one store per dir: release before the fresh store reads from disk
        assertArrayEquals(original, newStore(dir).unlock(passphrase)!!.payloadPlaintext)
    }

    // ── 14. Cross-slot writes compose at the store level — no other-slot reversion ────

    @Test
    fun crossSlotWrites_composeAtStoreLevel_noReversion() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))
        val k = open.slotIndex
        val j = (0 until SLOT_COUNT).first { it != k }

        // Seal a payload under a RANDOM key (a different vault's material) and write it at
        // the free slot j — the store must splice it in without disturbing slot k.
        val randomKey = ops.randomBytes(VAULT_KEY_BYTES)
        val sealedJ = sealPayload(randomKey, ops.randomBytes(64), ops)
        randomKey.fill(0)
        store.writeSealedPayload(j, sealedJ)

        // Now write slot k's own update. This must NOT revert slot j (the PR-A hazard: a
        // stale image snapshot would clobber the concurrent other-slot write).
        val updatedK = "slot-k updated ratchet state".toByteArray(Charsets.UTF_8)
        store.writeSealedPayload(k, sealPayload(open.vaultKey, updatedK, ops))

        // Fresh store from disk: slot k unlocks to its update, AND slot j's region is exactly
        // the bytes we wrote — no reversion of the other slot.
        store.close() // one store per dir: release before the fresh store reads from disk
        val fresh = newStore(dir)
        fresh.open()
        assertArrayEquals("slot k unlocks to its update", updatedK, fresh.unlock(passphrase)!!.payloadPlaintext)
        val innerAfter = decodeImage(decodeOnDiskInner(dir))
        assertArrayEquals("slot j region unchanged by the slot-k write", sealedJ, innerAfter.payloads[j])
    }

    // ── 15. A misbehaving cipher fails create() BEFORE any file exists on disk ─────────

    @Test
    fun misbehavingCipher_emitsWrongSizeBlob_createThrowsBeforeAnyFileOnDisk() {
        val dir = tmp.newFolder()
        // A DeviceKeyCipher that emits a 61-byte blob (one over WRAPPED_KEY_BYTES) — the
        // store-level constant-blob check must fail create() LOUDLY before any write, so a
        // malformed blob can never be persisted and brick the next open().
        val badCipher = object : DeviceKeyCipher {
            override fun wrapDek(dek: ByteArray): ByteArray = ByteArray(WRAPPED_KEY_BYTES + 1)
            override fun unwrapDek(blob: ByteArray): ByteArray? = null
        }
        val store = VaultImageStore(dir, ops, badCipher, fast)
        assertThrows(IllegalStateException::class.java) {
            store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))
        }
        // Nothing was written: neither the image nor the DEK sidecar (nor any .tmp) exists.
        assertFalse("no vault.bin after a rejected create", File(dir, "vault.bin").exists())
        assertFalse("no vault.dek after a rejected create", File(dir, "vault.dek").exists())
        assertFalse("no vault.bin.tmp after a rejected create", File(dir, "vault.bin.tmp").exists())
        assertFalse("no vault.dek.tmp after a rejected create", File(dir, "vault.dek.tmp").exists())
    }

    // ── 16. Single instance per baseDir: a second live store on the same dir throws ───

    @Test
    fun twoLiveStoresOnSameDir_secondThrowsUntilFirstCloses() {
        val dir = tmp.newFolder()
        val a = newStore(dir)
        a.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))

        // A second store on the SAME directory while A is live must refuse to open/create —
        // two independent canonical snapshots would silently revert each other's writes.
        val b = newStore(dir)
        assertThrows(IllegalStateException::class.java) { b.open() }
        assertThrows(IllegalStateException::class.java) {
            b.create(passphrase, "second".toByteArray(Charsets.UTF_8))
        }
        // A's failed-B attempts left A's registration intact: A still works.
        assertArrayEquals("genesis".toByteArray(Charsets.UTF_8), a.unlock(passphrase)!!.payloadPlaintext)

        // After A releases the directory (a real process restart ends A), B may open it.
        a.close()
        b.open()
        assertArrayEquals("genesis".toByteArray(Charsets.UTF_8), b.unlock(passphrase)!!.payloadPlaintext)
        b.close()
    }

    // ── 17. Dir-fsync NOT_DURABLE: throws NotDurable but RECONCILES canonical to disk ─────

    @Test
    fun writeSealedPayload_dirSyncNotDurable_throwsNotDurableButReconcilesCanonicalToDisk() {
        val dir = tmp.newFolder()
        // create() confirms both files durable via a single trailing dir-fsync, so onboarding must
        // run under a DURABLE dir-fsync; flip to NOT_DURABLE only for the subsequent payload write.
        var durableSync = true
        val store = newStore(dir) { if (durableSync) DirSyncResult.DURABLE else DirSyncResult.NOT_DURABLE }
        val open = store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))

        durableSync = false
        val updated = "durability-uncertain payload".toByteArray(Charsets.UTF_8)
        // A real dir-fsync EIO: the rename (the commit point) landed and its content was
        // fsynced, but the rename's durability is unconfirmed → writeSealedPayload THROWS
        // NotDurable so a flush-before-ack caller does NOT ack (a THROW, never a return).
        assertThrows(VaultImageException.NotDurable::class.java) {
            store.writeSealedPayload(open.slotIndex, sealPayload(open.vaultKey, updated, ops))
        }
        store.close() // one store per dir: release before a fresh store reads from disk

        // RECONCILED, not desynced: a FRESH store with the REAL dirSync opens the same dir and
        // unlocks to the NEW payload — the write DID land on disk and canonical was advanced to
        // match it, so no later splice can ever work from stale state.
        val reopenStore = newStore(dir)
        val reopened = reopenStore.unlock(passphrase)
        assertNotNull(reopened)
        assertArrayEquals(updated, reopened!!.payloadPlaintext)
        reopenStore.close() // one store per dir: release before the durable-retry store opens

        // A retry whose dir-fsync now SUCCEEDS returns normally (the caller may ack).
        val durablePayload = "confirmed-durable payload".toByteArray(Charsets.UTF_8)
        val store2 = newStore(dir) { DirSyncResult.DURABLE }
        store2.open()
        store2.writeSealedPayload(open.slotIndex, sealPayload(open.vaultKey, durablePayload, ops)) // no throw
        store2.close()
        assertArrayEquals(durablePayload, newStore(dir).unlock(passphrase)!!.payloadPlaintext)
    }

    // ── 18. create() DEK-step NOT_DURABLE: no vault.bin is written; open() = MissingImage; retry OK ──

    @Test
    fun create_dekStepNotDurable_writesNoBin_opensMissing_retryWithDurableSyncSucceeds() {
        val dir = tmp.newFolder()
        // DEK-FIRST BARRIER. create() renames vault.dek, then CONFIRMS that rename durable BEFORE it
        // ever writes vault.bin. Here the FIRST (dek) dir-fsync reports NOT_DURABLE, so create() must
        // THROW before vault.bin is written — proving no image is written until the dek is durable.
        val store = newStore(dir) { DirSyncResult.NOT_DURABLE }
        assertThrows(VaultImageException.NotDurable::class.java) {
            store.create(passphrase, "genesis".toByteArray(Charsets.UTF_8))
        }
        // vault.bin was NEVER written (the barrier: no image until the dek is confirmed durable). A
        // stray vault.dek may remain, but with no image → open() reads MissingImage, the retryable
        // fresh-install state, never a bricked image-exists-but-unopenable one.
        assertFalse("no vault.bin is written before the dek is confirmed durable", File(dir, "vault.bin").exists())
        assertThrows(VaultImageException.MissingImage::class.java) { store.open() }
        store.close() // the failed create/open released the registration; close() is idempotent

        // A RETRY with a DURABLE dir-fsync SUCCEEDS (overwriting any stray dek) and unlocks — proving
        // retryable, not bricked.
        val content = "genesis on retry".toByteArray(Charsets.UTF_8)
        val retry = newStore(dir) { DirSyncResult.DURABLE }
        val open = retry.create(passphrase, content)
        assertArrayEquals("retry create returns a live open", content, open.payloadPlaintext)
        retry.close()
        assertArrayEquals(content, newStore(dir).unlock(passphrase)!!.payloadPlaintext)
    }

    // ── 19. create() IMAGE-step NOT_DURABLE: NO rollback deletes; the complete vault opens (recovery) ─

    @Test
    fun create_imageStepNotDurable_keepsBothFilesNoRollback_openRecoversTheCompleteVault() {
        val dir = tmp.newFolder()
        // DEK durable (call #1 = DURABLE), IMAGE not durable (call #2 = NOT_DURABLE) — key off call
        // ORDER, since both fsyncs target the SAME baseDir. create() must THROW NotDurable but perform
        // NO rollback deletes: the dek was confirmed durable BEFORE the bin was written, so the
        // {bin present, dek absent} brick is unreachable and no delete is needed.
        var call = 0
        val store = newStore(dir) {
            call++
            if (call == 1) DirSyncResult.DURABLE else DirSyncResult.NOT_DURABLE
        }
        val content = "genesis whose image fsync is unconfirmed".toByteArray(Charsets.UTF_8)
        assertThrows(VaultImageException.NotDurable::class.java) { store.create(passphrase, content) }
        assertEquals("both the dek fsync and the bin fsync ran", 2, call)

        // NO rollback deletes: BOTH vault.dek and vault.bin remain on disk — a COMPLETE vault whose
        // IMAGE rename is merely unconfirmed-durable, never a torn {bin, no-dek} state.
        assertTrue("vault.dek kept — no rollback delete", File(dir, "vault.dek").exists())
        assertTrue("vault.bin kept — no rollback delete", File(dir, "vault.bin").exists())
        store.close() // one store per dir: release before the fresh store reads from disk

        // The documented recovery: a subsequent open() SUCCEEDS and unlocks to the created payload —
        // the complete-but-unconfirmed vault is fully openable (the dek is durable, so it can't be lost).
        val reopened = newStore(dir)
        reopened.open()
        assertArrayEquals(
            "complete-but-unconfirmed vault opens to the created payload",
            content,
            reopened.unlock(passphrase)!!.payloadPlaintext,
        )
    }

    // ── 20. A failed RE-open invalidates cached state — never serves it after the disk goes bad ─

    @Test
    fun failedReopen_invalidatesCachedState_neverServesStaleAfterDiskWentBad() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        val original = "content before corruption".toByteArray(Charsets.UTF_8)
        val open = store.create(passphrase, original)
        // The store is open with `original` cached in memory as canonical.
        assertArrayEquals(original, store.unlock(passphrase)!!.payloadPlaintext)

        // Corrupt vault.bin on disk UNDER the live store (flip a byte), then RE-open the same
        // instance: the on-disk image is now unreadable.
        val bin = File(dir, "vault.bin")
        val bytes = bin.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        bin.writeBytes(bytes)
        assertThrows(VaultImageException.CorruptImage::class.java) { store.open() }

        // The failed re-open FULLY invalidated the cached state: the store no longer serves the
        // pre-corruption payload. unlock() now behaves as a COLD store — it re-opens from disk
        // and hits CorruptImage, never returning the stale in-memory image.
        assertThrows(VaultImageException.CorruptImage::class.java) { store.unlock(passphrase) }
        // A direct write is likewise refused as not-open, rather than silently overwriting the
        // corrupt vault.bin with cached data (which would mask the corruption / roll it back).
        assertThrows(IllegalStateException::class.java) {
            store.writeSealedPayload(open.slotIndex, sealPayload(open.vaultKey, "x".toByteArray(Charsets.UTF_8), ops))
        }
    }

    // ── 21. An oversized vault.bin is CorruptImage — rejected fast, no OOM read ────────────

    @Test
    fun oversizedBin_isCorruptImage_withoutOom() {
        val dir = tmp.newFolder()
        val first = newStore(dir)
        first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
        first.close() // release the dir so a fresh store can attempt the tampered open

        // A tampered, hugely-inflated vault.bin (the valid-size dek is left untouched): the
        // length precheck rejects it as CorruptImage BEFORE any readBytes, so a malicious file
        // can never force a giant allocation. The assert returns fast — the garbage is never read.
        File(dir, "vault.bin").writeBytes(ByteArray(OUTER_IMAGE_BYTES + 10_000) { 0x7e })
        assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
    }

    // ── 22. A wrong-size vault.dek is CorruptImage (present-but-wrong, not missing) ────────

    @Test
    fun wrongSizeDek_isCorruptImage() {
        val dir = tmp.newFolder()
        val first = newStore(dir)
        first.create(passphrase, "v".toByteArray(Charsets.UTF_8))
        first.close() // release the dir so a fresh store can attempt the tampered open

        // A present vault.dek of the wrong length (61, one over WRAPPED_KEY_BYTES) is corruption,
        // not "missing": the length precheck rejects it as CorruptImage before any unwrap.
        File(dir, "vault.dek").writeBytes(ByteArray(WRAPPED_KEY_BYTES + 1) { 0x33 })
        assertThrows(VaultImageException.CorruptImage::class.java) { newStore(dir).open() }
    }

    // ── destroy(): the account-deletion primitive (no remanence) ────────────────────

    @Test
    fun destroy_removesBothFiles_exitsFalse_andReCreateWorks() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        store.create(passphrase, "the account crypto".toByteArray(Charsets.UTF_8))
        assertTrue("image + dek exist after create", store.exists())
        assertTrue(File(dir, "vault.bin").exists())
        assertTrue(File(dir, "vault.dek").exists())

        store.destroy()

        // No remanence: BOTH files gone, exists() false — nothing recoverable by a later unlock.
        assertFalse("exists() is false after destroy", store.exists())
        assertFalse("vault.bin deleted", File(dir, "vault.bin").exists())
        assertFalse("vault.dek deleted", File(dir, "vault.dek").exists())

        // destroy() released the single-instance registration, so a fresh store may re-create in the
        // SAME process (re-onboard after account deletion) and the new vault opens from disk.
        val reborn = newStore(dir)
        val fresh = "a brand-new account".toByteArray(Charsets.UTF_8)
        reborn.create(passphrase, fresh)
        assertTrue("re-create works after destroy", reborn.exists())
        reborn.close()
        val opened = newStore(dir).also { it.open() }.unlock(passphrase)
        assertNotNull("the re-created vault opens", opened)
        assertArrayEquals(fresh, opened!!.payloadPlaintext)
    }

    @Test
    fun destroy_isIdempotent_onNeverCreatedAndOnAlreadyDestroyed() {
        val dir = tmp.newFolder()
        // destroy() on a never-created store is a safe no-op (missing files delete cleanly).
        val never = newStore(dir)
        never.destroy()
        assertFalse(never.exists())

        // A second destroy() after a real create+destroy is also a no-op — no throw, files stay gone.
        val store = newStore(dir)
        store.create(passphrase, "x".toByteArray(Charsets.UTF_8))
        store.destroy()
        store.destroy()
        assertFalse("still gone after a second destroy", store.exists())
        assertFalse(File(dir, "vault.bin").exists())
        assertFalse(File(dir, "vault.dek").exists())
    }

    @Test
    fun destroy_removesLeftoverTmp_soNoWriteRemnantSurvives() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        store.create(passphrase, "y".toByteArray(Charsets.UTF_8))
        // Simulate an interrupted-write temp next to the constant-size files.
        val binTmp = File(dir, "vault.bin.tmp").also { it.writeBytes(ByteArray(16) { 0x7 }) }
        val dekTmp = File(dir, "vault.dek.tmp").also { it.writeBytes(ByteArray(8) { 0x9 }) }

        store.destroy()

        assertFalse("vault.bin gone", File(dir, "vault.bin").exists())
        assertFalse("vault.dek gone", File(dir, "vault.dek").exists())
        assertFalse("vault.bin.tmp leftover gone", binTmp.exists())
        assertFalse("vault.dek.tmp leftover gone", dekTmp.exists())
    }

    @Test
    fun destroy_throwsDestroyFailed_whenAFileSurvivesTheUnlink() {
        val dir = tmp.newFolder()
        val store = newStore(dir)
        // Model a delete() that FAILS to remove the image — File.delete() returns false on an I/O /
        // filesystem error just as it does on an already-absent file, so the store must not trust its
        // bool. A NON-EMPTY directory named vault.bin cannot be removed by File.delete(), so it
        // survives. destroy() must RE-STAT and THROW DestroyFailed so account-delete treats the vault
        // as NOT destroyed (never routes to Onboarding-as-success while the full-crypto image remains).
        val bin = File(dir, "vault.bin").also { it.mkdir() }
        File(bin, "child").writeBytes(ByteArray(4))

        assertThrows(VaultImageException.DestroyFailed::class.java) { store.destroy() }
        assertTrue("the un-deletable image is (correctly) reported as still present", bin.exists())
        // Round 8: the failed destroy leaves the destroy-pending marker — boot routing keys on it
        // to resume FINISHING the deletion instead of offering the unlock gate over a half-deleted
        // vault (dead-end on a partial unlink; silent re-register on a zeroed one).
        assertTrue("destroyPending survives the failed unlink", store.destroyPending())

        // The retry converges: once the filesystem cooperates, the SAME store's destroy verifies
        // and retires the marker.
        File(bin, "child").delete()
        store.destroy()
        assertFalse("retry removed the image", bin.exists())
        assertFalse("marker retired after the confirmed destroy", store.destroyPending())
    }

    @Test
    fun destroy_doesNotThrow_whenFilesAreAlreadyAbsent_idempotencyViaExistsNotDeleteBool() {
        val dir = tmp.newFolder()
        // The verify check is keyed on exists(), NOT the delete() bool: an already-absent file re-stats
        // absent and must NOT be mistaken for a failed unlink. A destroy() on a never-created store is
        // a clean success (no throw), which is what keeps a retried/idempotent destroy safe.
        val store = newStore(dir)
        store.destroy()
        assertFalse(File(dir, "vault.bin").exists())
        assertFalse(File(dir, "vault.dek").exists())
        assertFalse("a confirmed destroy leaves no marker", store.destroyPending())
    }

    /**
     * Fixed-key `javax.crypto` AES-256-GCM stand-in for the Android Keystore device
     * key. Emits the SAME 60-byte `nonce(12) ‖ ct(32) ‖ tag(16)` blob shape the
     * production [com.zitrone.app.crypto.vault.KeystoreDeviceKeyCipher] does, and
     * returns null (never throws) on an auth failure, matching the interface contract.
     */
    private class FakeDeviceKeyCipher : DeviceKeyCipher {
        private val key = ByteArray(MASTER_KEY_BYTES) { (0xA0 + it).toByte() }
        private val rng = SecureRandom()

        override fun wrapDek(dek: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce))
            return nonce + cipher.doFinal(dek)
        }

        override fun unwrapDek(blob: ByteArray): ByteArray? {
            if (blob.size != WRAPPED_KEY_BYTES) return null
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(AEAD_TAG_BYTES * 8, blob, 0, NONCE_BYTES),
                )
                cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
            } catch (e: GeneralSecurityException) {
                null
            }
        }
    }
}
