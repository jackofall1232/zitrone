// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.LemonDropCreate
import com.zitrone.app.crypto.LemonDropOneShot
import com.zitrone.app.crypto.LemonDropSodiumOps
import com.zitrone.app.crypto.LibsignalXEdDSAVerifier
import com.zitrone.app.crypto.MessagePadding
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import java.security.MessageDigest
import java.util.Base64

/**
 * The CREATE side of the Android bridge (sub-phase 5b). [LemonDropCreate] is the
 * production seal path; its output must open on the production Android responder
 * ([LemonDropOneShot], untouched) — the create→open round trip proves the two
 * files are the byte-exact ground-truth pair. The recipient is the real
 * Android-family (libsignal, XEdDSA-signed) fixture in resources/lemondrop, so
 * the classifier and the family-aware DH are exercised end to end; the sender is
 * a fresh libsignal Curve25519 identity, exactly as a device would hold.
 *
 * The cross-STACK direction (a Kotlin-created drop opening on the WEB stack)
 * lives in packages/crypto lemondrop-kotlin-fixture.test.ts, over a committed
 * fixture this class's sibling generator produced (see resources/lemondrop/README.md).
 */
class LemonDropCreateTest {

    private val sodium = LemonDropSodiumOps(SodiumJava())
    private val rawSodium = SodiumJava()
    private val b64 = Base64.getDecoder()
    private val b64e = Base64.getEncoder()

    private val keys = JSONObject(resource("/lemondrop/recipient-keys.json"))

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)!!.readBytes().toString(Charsets.UTF_8)

    /** The recipient's PUBLIC bundle (Android-family, genuine XEdDSA signature). */
    private fun recipientBundle(
        signature: ByteArray = b64.decode(keys.getString("spk_sig")),
    ) = LemonDropCreate.RecipientBundle(
        identityKey = b64.decode(keys.getString("identity_pub")),
        signedPrekeyId = keys.getInt("spk_id"),
        signedPrekeyPublic = b64.decode(keys.getString("spk_pub")),
        signedPrekeySignature = signature,
        oneTimePrekeyId = keys.getInt("otp_id"),
        oneTimePrekeyPublic = b64.decode(keys.getString("otp_pub")),
    )

    /** The recipient's PRIVATE key material — this device plays the recipient. */
    private fun recipientKeys(
        identityPriv: ByteArray = b64.decode(keys.getString("identity_priv")),
        otp: LemonDropOneShot.OneTimePrekey? = LemonDropOneShot.OneTimePrekey(
            publicKey = b64.decode(keys.getString("otp_pub")),
            privateScalar = b64.decode(keys.getString("otp_priv")),
        ),
    ) = LemonDropOneShot.RecipientKeys(
        identityPublicKey = b64.decode(keys.getString("identity_pub")),
        identityPrivateScalar = identityPriv,
        signedPrekeys = listOf(
            LemonDropOneShot.SignedPrekey(
                id = keys.getInt("spk_id"),
                publicKey = b64.decode(keys.getString("spk_pub")),
                privateScalar = b64.decode(keys.getString("spk_priv")),
                timestampMs = 1L,
            ),
        ),
        oneTimePrekeyLoader = { id -> otp.takeIf { id == keys.getInt("otp_id") } },
    )

    private val senderAccountId = "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb"
    private val recipientAccountId = "cccccccc-4444-4555-9666-dddddddddddd"

    /** A fresh libsignal Curve25519 sender identity — raw-32 public + serialize()
     *  scalar, exactly what LemonDropCreator exports from the store. */
    private fun senderIdentity(): LemonDropCreate.SenderIdentity {
        val kp = IdentityKeyPair.generate()
        return LemonDropCreate.SenderIdentity(
            publicKey = kp.publicKey.publicKey.getPublicKeyBytes(),
            privateScalar = kp.privateKey.serialize(),
        )
    }

    private fun create(
        text: String,
        bundle: LemonDropCreate.RecipientBundle = recipientBundle(),
        sender: LemonDropCreate.SenderIdentity = senderIdentity(),
    ): LemonDropCreate.Result = LemonDropCreate.create(
        sodium = sodium,
        verifyXEdDSA = LibsignalXEdDSAVerifier,
        senderAccountId = senderAccountId,
        sender = sender,
        recipientAccountId = recipientAccountId,
        bundle = bundle,
        text = text,
    )

    @Test
    fun `a Kotlin-created drop opens for its true Android recipient`() {
        val text = "Meet me where the lemon tree grows. 🍋 — Android creation."
        val sender = senderIdentity()
        val senderKeyBefore = sender.publicKey.copyOf()
        val created = create(text, sender = sender)
        assertTrue("expected Created, got $created", created is LemonDropCreate.Result.Created)
        created as LemonDropCreate.Result.Created

        // Padded like any drop: 256-byte blocks, nothing family-shaped on the wire.
        assertEquals(0, created.ciphertext.size % 256)
        assertEquals(16, created.qrId.size)

        val result = LemonDropOneShot.open(sodium, created.ciphertext, recipientKeys())
        assertTrue("expected Message, got $result", result is LemonDropOneShot.Result.Message)
        result as LemonDropOneShot.Result.Message
        assertEquals(text, result.text)
        assertEquals(senderAccountId, result.senderAccountId)
        // The recovered claimed key is the raw Montgomery sender key verbatim.
        assertArrayEquals(senderKeyBefore, result.senderIdentityKey)
        // The recovered burn token is the preimage of the deposited burn_hash.
        assertArrayEquals(
            created.burnHash,
            MessageDigest.getInstance("SHA-256").digest(result.burnToken),
        )
    }

    @Test
    fun `a wrong recipient stays honestly refused — the seal never opens`() {
        val created = create("not for you") as LemonDropCreate.Result.Created
        val other = IdentityKeyPair.generate()
        val result = LemonDropOneShot.open(
            sodium,
            created.ciphertext,
            recipientKeys(identityPriv = other.privateKey.serialize()),
        )
        assertEquals(LemonDropOneShot.Result.NotRecipient, result)
    }

    @Test
    fun `a corrupted inner ratchet ciphertext opens the seal but fails to decrypt — Invalid`() {
        // White-box: unwrap our own drop, flip a byte of the AEAD ciphertext, and
        // re-seal to the SAME recipient. The seal still opens (it IS addressed to
        // us), so the honest outcome is Invalid — our drop, gone wrong — never
        // NotRecipient. (Flipping the OUTER sealed box instead breaks Poly1305 and
        // is indistinguishable from not-ours; covered in LemonDropOneShotTest.)
        val created = create("tamper me") as LemonDropCreate.Result.Created
        val identityPub = b64.decode(keys.getString("identity_pub"))
        val identityPriv = b64.decode(keys.getString("identity_priv"))

        val sealed = MessagePadding.unpadOrNull(created.ciphertext)!!
        val payloadBytes = sodium.sealOpen(sealed, identityPub, identityPriv)!!
        val payload = JSONObject(payloadBytes.toString(Charsets.UTF_8))
        val envelope = payload.getJSONObject("envelope")
        val blob = b64.decode(envelope.getString("ciphertext"))
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte() // flip a GCM-tag byte
        envelope.put("ciphertext", b64e.encodeToString(blob))
        val newPlaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val reSealed = ByteArray(newPlaintext.size + 48)
        rawSodium.crypto_box_seal(reSealed, newPlaintext, newPlaintext.size.toLong(), identityPub)

        val result = LemonDropOneShot.open(sodium, MessagePadding.pad(reSealed), recipientKeys())
        assertEquals(LemonDropOneShot.Result.Invalid, result)
    }

    @Test
    fun `the deposit proof-of-work validates against a ported verifier`() {
        val created = create("prove the work") as LemonDropCreate.Result.Created
        assertEquals(8, created.powNonce.size)
        // Port of deaddrop.ts verifyProofOfWork / pow.go Verify: SHA-256 over the
        // raw qr_id concatenated with the 8-byte nonce, difficulty leading zero
        // bits, MSB-first.
        assertTrue(verifyPoW(created.qrId, created.powNonce, LemonDropCreate.POW_DIFFICULTY))
        // A one-bit-flipped nonce must NOT verify (the puzzle is real, not trivial).
        val bad = created.powNonce.copyOf().also { it[7] = (it[7] + 1).toByte() }
        assertTrue(
            "a tampered nonce must fail",
            !verifyPoW(created.qrId, bad, LemonDropCreate.POW_DIFFICULTY),
        )
    }

    @Test
    fun `a tampered Android-family bundle signature fails closed — no drop created`() {
        val badSig = b64.decode(keys.getString("spk_sig")).also { it[5] = (it[5] + 1).toByte() }
        val result = create("must not be created", bundle = recipientBundle(signature = badSig))
        assertEquals(LemonDropCreate.Result.BundleUnverified, result)
    }

    /** SHA-256(challenge || nonce) begins with `difficulty` zero bits (MSB-first). */
    private fun verifyPoW(challenge: ByteArray, nonce: ByteArray, difficulty: Int): Boolean {
        if (nonce.size != 8) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(challenge + nonce)
        var remaining = difficulty
        for (b in digest) {
            if (remaining <= 0) return true
            val byte = b.toInt() and 0xFF
            if (remaining >= 8) {
                if (byte != 0) return false
                remaining -= 8
            } else {
                return (byte ushr (8 - remaining)) == 0
            }
        }
        return remaining <= 0
    }
}
