// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.AEAD_TAG_BYTES
import com.zitrone.app.crypto.vault.DeviceKeyCipher
import com.zitrone.app.crypto.vault.IMAGE_BYTES
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
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

    /** Decrypt the on-disk envelope back to the inner image bytes — for asserting on
     *  inner regions the outer layer would otherwise hide behind a fresh nonce. */
    private fun decodeOnDiskInner(dir: File): ByteArray {
        val dek = cipher.unwrapDek(File(dir, "vault.dek").readBytes())!!
        return ops.aeadDecrypt(dek, File(dir, "vault.bin").readBytes(), VAULT_IMAGE_OUTER_AD)!!
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
        // Fresh store from disk unlocks to the updated payload, at the same slot.
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
        newStore(dir).create(passphrase, content)

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

        // Remove the blocker; a FRESH store from disk also opens to the ORIGINAL.
        assertTrue(blocker.delete())
        assertArrayEquals(original, newStore(dir).unlock(passphrase)!!.payloadPlaintext)
    }

    // ── 5. Corruption is surfaced distinctly and NEVER silently repaired ──────────

    @Test
    fun corruption_surfacesDistinctly_neverRecreated() {
        // A flipped byte in vault.bin → the outer GCM tag fails → CorruptImage.
        run {
            val dir = tmp.newFolder()
            newStore(dir).create(passphrase, "v".toByteArray(Charsets.UTF_8))
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
            newStore(dir).create(passphrase, "v".toByteArray(Charsets.UTF_8))
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
        val reopened = newStore(dir).unlock(passphrase)!!.payloadPlaintext
        assertTrue(
            "final region is one intact payload, not a torn mix",
            reopened.contentEquals(payloadA) || reopened.contentEquals(payloadB),
        )
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
