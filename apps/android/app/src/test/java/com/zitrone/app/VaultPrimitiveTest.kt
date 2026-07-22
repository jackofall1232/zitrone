// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.IMAGE_BYTES
import com.zitrone.app.crypto.vault.KeyDeriver
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.MASTER_KEY_BYTES
import com.zitrone.app.crypto.vault.NONCE_BYTES
import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
import com.zitrone.app.crypto.vault.SALT_BYTES
import com.zitrone.app.crypto.vault.SLOT_COUNT
import com.zitrone.app.crypto.vault.SLOT_PAYLOAD_BYTES
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.WRAPPED_KEY_BYTES
import com.zitrone.app.crypto.vault.addVaultToImage
import com.zitrone.app.crypto.vault.createImage
import com.zitrone.app.crypto.vault.decodeImage
import com.zitrone.app.crypto.vault.openPayload
import com.zitrone.app.crypto.vault.randomPayload
import com.zitrone.app.crypto.vault.randomSlot
import com.zitrone.app.crypto.vault.sealPayload
import com.zitrone.app.crypto.vault.sealSlot
import com.zitrone.app.crypto.vault.tryPassphrase
import com.zitrone.app.crypto.vault.unlockImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * Tests for the plausible-deniability key-slot vault primitive
 * (com.zitrone.app.crypto.vault). The AEAD path is ALWAYS the real production
 * byte path — [LibsodiumVaultOps] over SodiumJava, the same libsodium C the
 * device binds. Only the Argon2id KDF is swapped for a fast, deterministic
 * stand-in ([fast]) via the injectable [KeyDeriver], so the structural tests —
 * above all the no-early-exit timing-parity proof — run in milliseconds. One
 * dedicated test exercises the real 64-MiB Argon2id parameters end to end.
 */
class VaultPrimitiveTest {

    // Real AEAD + CSPRNG, the on-device byte path.
    private val ops = LibsodiumVaultOps(SodiumJava())

    /**
     * Fast, deterministic stand-in for Argon2id: SHA-256(passphrase ‖ salt).
     * Deterministic so the SAME (passphrase, salt) unwraps the slot it sealed;
     * a fresh 32-byte array per call so the wipe assertions are meaningful.
     */
    private val fast: KeyDeriver = { passphrase, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.digest()
    }

    /** Wraps a deriver and counts invocations — the timing-parity instrument. */
    private class CountingDeriver(private val inner: KeyDeriver) {
        var calls = 0
            private set
        val deriver: KeyDeriver = { p, s ->
            calls++
            inner(p, s)
        }
    }

    // ── round trip ────────────────────────────────────────────────────────────

    @Test
    fun createThenUnlockRoundTrips_wrongPassphraseReturnsNull() {
        val payload = "the real keystore bytes".toByteArray(Charsets.UTF_8)
        val image = createImage("correct-pass", payload, ops, fast)
        assertEquals(IMAGE_BYTES, image.size)

        val opened = unlockImage("correct-pass", image, ops, fast)
        assertNotNull(opened)
        assertArrayEquals(payload, opened!!.payloadPlaintext)

        assertNull(unlockImage("wrong-pass", image, ops, fast))
    }

    // ── NO-EARLY-EXIT: the structural timing-parity proof ───────────────────────

    // A counting deriver pins the number of Argon2id derivations to exactly
    // SLOT_COUNT for a match in the FIRST slot, a match in the LAST slot, and no
    // match at all. Because sealSlot and tryPassphrase share the injected
    // deriver, the fast stand-in unwraps end to end while the real AES-GCM tag
    // still gates the match — so the count reflects the true loop, and identical
    // counts across all three positions is proof the loop performs identical
    // work regardless of where (or whether) the passphrase matches. Any early
    // break would drop the count for an early match and leak match position as
    // a wall-clock side-channel.

    @Test
    fun tryPassphrase_derivesEverySlot_matchInFirstSlot() {
        val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
        val slots = MutableList(SLOT_COUNT) { randomSlot(ops) }
        slots[0] = sealSlot("pw", vaultKey, ops, fast)

        val counter = CountingDeriver(fast)
        val result = tryPassphrase("pw", slots, ops, counter.deriver)

        assertNotNull(result)
        assertEquals(0, result!!.slotIndex)
        assertEquals(SLOT_COUNT, counter.calls)
    }

    @Test
    fun tryPassphrase_derivesEverySlot_matchInLastSlot() {
        val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
        val slots = MutableList(SLOT_COUNT) { randomSlot(ops) }
        slots[SLOT_COUNT - 1] = sealSlot("pw", vaultKey, ops, fast)

        val counter = CountingDeriver(fast)
        val result = tryPassphrase("pw", slots, ops, counter.deriver)

        assertNotNull(result)
        assertEquals(SLOT_COUNT - 1, result!!.slotIndex)
        assertEquals(SLOT_COUNT, counter.calls)
    }

    @Test
    fun tryPassphrase_derivesEverySlot_noMatch() {
        val slots = MutableList(SLOT_COUNT) { randomSlot(ops) }

        val counter = CountingDeriver(fast)
        val result = tryPassphrase("pw", slots, ops, counter.deriver)

        assertNull(result)
        assertEquals(SLOT_COUNT, counter.calls)
    }

    // ── two independent vaults in one image ─────────────────────────────────────

    @Test
    fun twoVaults_openIndependently_atIndependentSlots_fillersUnchanged() {
        val payloadA = "vault A — the outer profile".toByteArray(Charsets.UTF_8)
        val payloadB = "vault B — the hidden profile, quite different".toByteArray(Charsets.UTF_8)

        val image0 = createImage("passA", payloadA, ops, fast)
        val idxA = unlockImage("passA", image0, ops, fast)!!.slotIndex

        val image1 = addVaultToImage(image0, setOf(idxA), "passB", payloadB, ops, fast)
        assertEquals(IMAGE_BYTES, image0.size)
        assertEquals(IMAGE_BYTES, image1.size)

        val a = unlockImage("passA", image1, ops, fast)!!
        val b = unlockImage("passB", image1, ops, fast)!!
        assertArrayEquals(payloadA, a.payloadPlaintext)
        assertArrayEquals(payloadB, b.payloadPlaintext)

        // The two real slots landed at independent indices.
        assertNotEquals(a.slotIndex, b.slotIndex)

        // Neither passphrase's key can open the other's payload region.
        val decoded = decodeImage(image1)
        assertNull(openPayload(a.vaultKey, decoded.payloads[b.slotIndex], ops))
        assertNull(openPayload(b.vaultKey, decoded.payloads[a.slotIndex], ops))

        // Every slot and payload OTHER than the one B was sealed into is carried
        // over byte-for-byte from image0 — filler regions are untouched.
        val d0 = decodeImage(image0)
        val d1 = decodeImage(image1)
        for (i in 0 until SLOT_COUNT) {
            if (i == b.slotIndex) continue
            assertArrayEquals(d0.slots[i].salt, d1.slots[i].salt)
            assertArrayEquals(d0.slots[i].wrapped, d1.slots[i].wrapped)
            assertArrayEquals(d0.payloads[i], d1.payloads[i])
        }
    }

    // ── payload layer ───────────────────────────────────────────────────────────

    @Test
    fun payload_sealOpenRoundTrip_fixedSizeRegardlessOfContent() {
        val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)

        val small = "hi".toByteArray(Charsets.UTF_8)
        val sealedSmall = sealPayload(vaultKey, small, ops)
        assertEquals(SLOT_PAYLOAD_BYTES, sealedSmall.size)
        assertArrayEquals(small, openPayload(vaultKey, sealedSmall, ops))

        val big = ByteArray(100_000) { (it and 0xff).toByte() }
        val sealedBig = sealPayload(vaultKey, big, ops)
        assertEquals(SLOT_PAYLOAD_BYTES, sealedBig.size)
        assertArrayEquals(big, openPayload(vaultKey, sealedBig, ops))

        val empty = ByteArray(0)
        val sealedEmpty = sealPayload(vaultKey, empty, ops)
        assertEquals(SLOT_PAYLOAD_BYTES, sealedEmpty.size)
        assertArrayEquals(empty, openPayload(vaultKey, sealedEmpty, ops))

        // The exact maximum that fits (capacity minus the 4-byte length prefix).
        val maxFit = ByteArray(PAYLOAD_PLAINTEXT_BYTES - 4)
        assertEquals(SLOT_PAYLOAD_BYTES, sealPayload(vaultKey, maxFit, ops).size)

        // randomPayload is the same fixed length.
        assertEquals(SLOT_PAYLOAD_BYTES, randomPayload(ops).size)
    }

    @Test
    fun payload_overCapacityThrows_neverGrows() {
        val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
        // capacity itself is over the limit once the 4-byte prefix is added.
        val tooBig = ByteArray(PAYLOAD_PLAINTEXT_BYTES)
        try {
            sealPayload(vaultKey, tooBig, ops)
            fail("expected over-capacity content to throw")
        } catch (e: IllegalArgumentException) {
            // expected — the region never grows to fit.
        }
    }

    // ── wiping ──────────────────────────────────────────────────────────────────

    @Test
    fun derivedMasterKeys_areWipedAfterUse() {
        val captured = mutableListOf<ByteArray>()
        val capturing: KeyDeriver = { p, s ->
            val k = fast(p, s)
            captured.add(k)
            k
        }

        // sealSlot must zero its master key in the finally block.
        val vaultKey = ops.randomBytes(VAULT_KEY_BYTES)
        val realSlot = sealSlot("pw", vaultKey, ops, capturing)
        assertEquals(1, captured.size)
        assertTrue("sealSlot left its master key un-wiped", captured[0].all { it == 0.toByte() })

        // tryPassphrase must zero EVERY derived master key — the winning one and
        // every non-winning one — not just the matched slot.
        captured.clear()
        val slots = MutableList(SLOT_COUNT) { randomSlot(ops) }
        slots[1] = realSlot
        tryPassphrase("pw", slots, ops, capturing)
        assertEquals(SLOT_COUNT, captured.size)
        assertTrue(
            "tryPassphrase left a derived master key un-wiped",
            captured.all { mk -> mk.all { it == 0.toByte() } },
        )
    }

    // ── constants match the web reference ───────────────────────────────────────

    @Test
    fun constants_mirrorTheWebReference() {
        assertEquals(4, SLOT_COUNT)
        assertEquals(60, WRAPPED_KEY_BYTES)
        assertEquals(32, VAULT_KEY_BYTES)
        assertEquals(32, MASTER_KEY_BYTES)
        assertEquals(16, SALT_BYTES)
        assertEquals(12, NONCE_BYTES)
        assertEquals(262144, SLOT_PAYLOAD_BYTES)
    }

    // ── real Argon2id: determinism + the exact kdf.ts parameters ────────────────

    @Test
    fun argon2id_isDeterministic_withRealKdfParams() {
        // The production 64-MiB / 3-iteration Argon2id path (one slow call each).
        val salt = ops.randomBytes(SALT_BYTES)
        val password = "correct horse battery staple".toByteArray(Charsets.UTF_8)

        val k1 = ops.argon2idDeriveKey(password, salt)
        val k2 = ops.argon2idDeriveKey(password, salt)
        assertEquals(MASTER_KEY_BYTES, k1.size)
        assertArrayEquals(k1, k2)

        // A different salt yields a different key.
        val k3 = ops.argon2idDeriveKey(password, ops.randomBytes(SALT_BYTES))
        assertFalse(k1.contentEquals(k3))
    }

    @Test
    fun fullRoundTrip_overRealArgon2id() {
        // End-to-end through the real KDF: create (1 derivation) + unlock
        // (SLOT_COUNT derivations) with the production Argon2id parameters.
        val payload = "keystore over real argon2id".toByteArray(Charsets.UTF_8)
        val image = createImage("real-pass", payload, ops)
        val opened = unlockImage("real-pass", image, ops)
        assertNotNull(opened)
        assertArrayEquals(payload, opened!!.payloadPlaintext)
        assertNull(unlockImage("nope", image, ops))
    }

    /**
     * GOLDEN VECTOR — proves the AEAD is standard AES-256-GCM, byte-identical to
     * the web reference's WebCrypto AES-GCM (and to libsodium's), so the switch
     * to the portable javax.crypto backend did not change the on-the-wire format.
     * NIST GCM test case (AES-256, 128-bit tag): all-zero key + nonce, empty AAD,
     * 16 zero plaintext bytes → ct cea7403d4d606b6e074ec5d3baf39d18,
     * tag d0d1c8a799996bf0265b98b5d48ab919. Fed through the production aeadDecrypt
     * as nonce(12)||ct||tag, it must recover the 16 zero plaintext bytes — which
     * confirms both the AES-256-GCM correctness and the box layout.
     */
    @Test
    fun aead_matchesNistAes256GcmVector() {
        val key = ByteArray(MASTER_KEY_BYTES) // 32 zero bytes
        val nonce = ByteArray(NONCE_BYTES) // 12 zero bytes
        val ctAndTag = hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
        val box = nonce + ctAndTag
        val opened = ops.aeadDecrypt(key, box, ByteArray(0))
        assertNotNull("NIST AES-256-GCM vector must open", opened)
        assertArrayEquals(ByteArray(16), opened) // 16 zero plaintext bytes
        // And a one-bit tamper is rejected the same way a filler slot is (null).
        box[box.size - 1] = (box[box.size - 1].toInt() xor 1).toByte()
        assertNull(ops.aeadDecrypt(key, box, ByteArray(0)))
    }

    /**
     * A vault must not be added under a passphrase that already opens one:
     * tryPassphrase returns only the first match, so a duplicate seal would
     * silently shadow (make unreachable) one of the two vaults.
     */
    @Test
    fun addVault_rejectsAPassphraseThatAlreadyUnlocksASlot() {
        val image = createImage("shared-pass", "A".toByteArray(), ops, fast)
        val idx = unlockImage("shared-pass", image, ops, fast)!!.slotIndex
        try {
            addVaultToImage(image, setOf(idx), "shared-pass", "B".toByteArray(), ops, fast)
            fail("adding a vault under an existing passphrase must throw")
        } catch (e: IllegalArgumentException) {
            // expected — collision rejected
        }
        // A DIFFERENT passphrase still succeeds.
        val two = addVaultToImage(image, setOf(idx), "other-pass", "B".toByteArray(), ops, fast)
        assertNotNull(unlockImage("shared-pass", two, ops, fast))
        assertNotNull(unlockImage("other-pass", two, ops, fast))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
