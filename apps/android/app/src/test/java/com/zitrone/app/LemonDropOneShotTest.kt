// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.LemonDropOneShot
import com.zitrone.app.crypto.LemonDropSodiumOps
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
 * The cross-stack round trip: a lemon drop created by the PRODUCTION web
 * stack (packages/crypto createLemonDrop — see the committed fixtures and
 * resources/lemondrop/README.md) must open on the production Android path —
 * [LemonDropSodiumOps] over the same libsodium C functions the device binds
 * (lazysodium-java here, lazysodium-android on device) feeding
 * [LemonDropOneShot.open]. This is the test that proves gate item 3 of the
 * Android bridge: a web-created drop is READABLE by its true Android
 * recipient, and honestly refused for everyone else.
 */
class LemonDropOneShotTest {

    private val sodium = LemonDropSodiumOps(SodiumJava())
    private val b64 = Base64.getDecoder()

    private val keys = JSONObject(resource("/lemondrop/recipient-keys.json"))
    private val fixture = JSONObject(resource("/lemondrop/cross-stack-fixture.json"))

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)!!.readBytes().toString(Charsets.UTF_8)

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
        oneTimePrekey = { id -> otp.takeIf { id == keys.getInt("otp_id") } },
    )

    private fun ciphertext(): ByteArray = b64.decode(fixture.getString("ciphertext"))

    @Test
    fun `web-created drop opens for its true Android recipient`() {
        val result = LemonDropOneShot.open(sodium, ciphertext(), recipientKeys())
        assertTrue("expected Message, got $result", result is LemonDropOneShot.Result.Message)
        result as LemonDropOneShot.Result.Message
        assertEquals(fixture.getString("text"), result.text)
        assertEquals(fixture.getString("sender_account_id"), result.senderAccountId)
        assertArrayEquals(
            b64.decode(fixture.getString("sender_identity_pub")),
            result.senderIdentityKey,
        )
        assertEquals(keys.getInt("otp_id"), result.usedOneTimePrekeyId)
        // The recovered burn token must be the preimage of the deposited
        // burn_hash — the capability the relay's burn route verifies.
        assertArrayEquals(
            b64.decode(fixture.getString("burn_hash")),
            MessageDigest.getInstance("SHA-256").digest(result.burnToken),
        )
    }

    @Test
    fun `wrong recipient stays honestly refused — the seal never opens`() {
        // A REAL other Android identity (libsignal-generated), not corrupted
        // bytes: the everyday wrong-scanner case. No crash, no partial
        // plaintext — just "not for you".
        val other = IdentityKeyPair.generate()
        val result = LemonDropOneShot.open(
            sodium,
            ciphertext(),
            recipientKeys(identityPriv = other.privateKey.serialize()),
        )
        assertEquals(LemonDropOneShot.Result.NotRecipient, result)
    }

    @Test
    fun `tampered blob is indistinguishable from not-ours`() {
        val tampered = ciphertext().also { it[100] = (it[100] + 1).toByte() }
        assertEquals(
            LemonDropOneShot.Result.NotRecipient,
            LemonDropOneShot.open(sodium, tampered, recipientKeys()),
        )
    }

    @Test
    fun `truncated blob is indistinguishable from not-ours`() {
        val truncated = ciphertext().copyOfRange(0, 256)
        assertEquals(
            LemonDropOneShot.Result.NotRecipient,
            LemonDropOneShot.open(sodium, truncated, recipientKeys()),
        )
    }

    @Test
    fun `garbage sealed to us is ours-but-invalid, never someone else's`() {
        // The seal opens (it WAS addressed to us) but the payload is junk —
        // the crypto layer must say Invalid, not claim it belongs elsewhere.
        val junk = "not a lemon drop payload".toByteArray()
        val sealed = ByteArray(junk.size + 48)
        SodiumJava().crypto_box_seal(
            sealed, junk, junk.size.toLong(),
            b64.decode(keys.getString("identity_pub")),
        )
        val result = LemonDropOneShot.open(sodium, MessagePadding.pad(sealed), recipientKeys())
        assertEquals(LemonDropOneShot.Result.Invalid, result)
    }

    @Test
    fun `consumed one-time prekey no longer held means ours-but-invalid`() {
        // The creator named OTP id 42 but we no longer hold it (e.g. already
        // consumed at a previous delivery): the responder DH cannot
        // reconstruct — fail closed as Invalid, exactly like the web client.
        val result = LemonDropOneShot.open(sodium, ciphertext(), recipientKeys(otp = null))
        assertEquals(LemonDropOneShot.Result.Invalid, result)
    }
}
