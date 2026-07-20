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
        oneTimePrekeyLoader = { id -> otp.takeIf { id == keys.getInt("otp_id") } },
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

    // ── sub-phase 5a: sender_key_family parsing (mirror of the TS parser) ────
    //
    // Positive curve25519-sender round-trip coverage lives with a REGENERATED
    // Montgomery-sender cross-stack fixture in sub-phase 5b (Android creation) —
    // see resources/lemondrop/README.md. Reachable here through the public
    // open() API: the absent→ed25519 default path is already exercised by
    // `web-created drop opens ...` (the committed fixture carries no family
    // field); these add the fail-closed rejection of a bad value.

    /** Seal a structurally-valid lemon-drop payload to the fixture recipient,
     *  choosing the `sender_key_family` field: null omits it entirely. */
    private fun sealPayloadWithFamily(family: String?): ByteArray {
        val envelope = JSONObject()
            .put("id", "0b9f8c1e-4f2a-4d8b-9c3e-7a6b5d4c3b2a")
            .put("sender_id", "11111111-2222-4333-8444-555555555555")
            .put("recipient_id", "66666666-7777-4888-8999-aaaaaaaaaaaa")
            // A 44-byte body (32 ratchet key + a few) — passes the size floor but
            // will not decrypt; the point is the PARSE, not the crypto.
            .put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(60)))
            .put("ephemeral_key", fixture.getString("sender_identity_pub"))
            .put("prekey_id", JSONObject.NULL)
            .put("message_number", 0)
            .put("previous_chain_length", 0)
        val payload = JSONObject()
            .put("v", 1)
            .put("control", "lemondrop.v1")
            .put("envelope", envelope)
            .put("sender_identity_key", fixture.getString("sender_identity_pub"))
            .put("burn_token", fixture.getString("sender_identity_pub"))
        if (family != null) payload.put("sender_key_family", family)
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val sealed = ByteArray(plaintext.size + 48)
        SodiumJava().crypto_box_seal(
            sealed, plaintext, plaintext.size.toLong(),
            b64.decode(keys.getString("identity_pub")),
        )
        return MessagePadding.pad(sealed)
    }

    @Test
    fun `an unknown sender_key_family fails closed to Invalid, never a crash`() {
        // The seal opens (addressed to us), so the honest outcome is Invalid —
        // a malformed drop of ours — and the strict parse must never throw.
        for (bad in listOf("x25519", "Ed25519", "", "curve25519 ")) {
            assertEquals(
                "family=$bad",
                LemonDropOneShot.Result.Invalid,
                LemonDropOneShot.open(sodium, sealPayloadWithFamily(bad), recipientKeys()),
            )
        }
    }

    @Test
    fun `a known sender_key_family parses without throwing (curve25519 branch)`() {
        // Both known values (and the absent default) reach the decrypt attempt
        // and fail there on this dummy envelope → Invalid, no throw. This walks
        // the new curve25519 branch (identity key used verbatim, no Edwards map).
        for (family in listOf(null, "ed25519", "curve25519")) {
            assertEquals(
                "family=$family",
                LemonDropOneShot.Result.Invalid,
                LemonDropOneShot.open(sodium, sealPayloadWithFamily(family), recipientKeys()),
            )
        }
    }

    /** The raw payload JSON from [sealPayloadWithFamily], pre-seal, with the
     *  family field controlled per-case (SKIP omits it; JSONObject.NULL is an
     *  explicit JSON null — a distinct wire state the parser must reject). */
    private fun payloadJsonWithFamily(family: Any?): ByteArray {
        val envelope = JSONObject()
            .put("id", "0b9f8c1e-4f2a-4d8b-9c3e-7a6b5d4c3b2a")
            .put("sender_id", "11111111-2222-4333-8444-555555555555")
            .put("recipient_id", "66666666-7777-4888-8999-aaaaaaaaaaaa")
            .put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(60)))
            .put("ephemeral_key", fixture.getString("sender_identity_pub"))
            .put("prekey_id", JSONObject.NULL)
            .put("message_number", 0)
            .put("previous_chain_length", 0)
        val payload = JSONObject()
            .put("v", 1)
            .put("control", "lemondrop.v1")
            .put("envelope", envelope)
            .put("sender_identity_key", fixture.getString("sender_identity_pub"))
            .put("burn_token", fixture.getString("sender_identity_pub"))
        if (family != null) payload.put("sender_key_family", family)
        return payload.toString().toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `parser distinguishes absent from explicit-null sender_key_family`() {
        // Lockstep with packages/protocol parseLemonDrop: a truly missing key
        // defaults to ed25519; an explicit JSON null is a present, wrong-typed
        // value and must fail the parse. open() collapses both worlds into
        // Invalid, so this pins the parser decision directly.
        assertEquals(
            "ed25519",
            LemonDropOneShot.parsePayload(payloadJsonWithFamily(null))!!.senderKeyFamily,
        )
        assertEquals(
            "curve25519",
            LemonDropOneShot.parsePayload(payloadJsonWithFamily("curve25519"))!!.senderKeyFamily,
        )
        org.junit.Assert.assertNull(
            LemonDropOneShot.parsePayload(payloadJsonWithFamily(JSONObject.NULL)),
        )
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
