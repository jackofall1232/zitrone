// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-shot lemon-drop (QR dead-drop) opener — a deliberate, byte-exact mirror
 * of the web client's `openLemonDrop` (packages/crypto/src/lemondrop.ts) so an
 * Android device can be a lemon drop's true recipient.
 *
 * ISOLATION CONTRACT — read before touching:
 *
 *  - This is NOT part of ordinary messaging. It never touches
 *    [SignalProtocolManager], never reads or writes a libsignal session, and
 *    never parses libsignal wire formats. A lemon drop is one-way, one-shot,
 *    session-less delivery: the X3DH responder session built here decrypts
 *    EXACTLY ONE message and is discarded before returning — mirroring the
 *    creator side, which encrypted exactly one and threw its session away.
 *    There is no reply path and none must ever grow here.
 *  - It speaks the WEB stack's formats on purpose: libsodium sealed box
 *    (X25519 + XSalsa20-Poly1305) outside, the Zitrone one-shot X3DH
 *    (HKDF label "Zitrone-X3DH-v1") + one Double-Ratchet receive step (HKDF
 *    label "Zitrone-Ratchet-Root-v1", AES-256-GCM, header as AAD) inside,
 *    with [MessagePadding]'s 256-byte blocks at both layers. Drops are
 *    created only by web/desktop (packages/crypto createLemonDrop); their
 *    stack defines the bytes, this file follows it. Any change starts THERE.
 *  - Callers hand it RAW PRIVATE SCALARS (see [RecipientKeys]) pulled from the
 *    encrypted Signal store — the narrow, documented exception to the
 *    "private key bytes never leave the store" invariant. The exception lives
 *    in LemonDropRedeemer's private key-bridge and nowhere else; this class
 *    only consumes what it is handed, holds it for the duration of one call,
 *    and best-effort zeros its derived secrets before returning.
 *
 * The three libsodium primitives (sealed-box open, X25519 scalar mult,
 * Ed25519→Curve25519 public-key conversion) come in through [SodiumOps] so
 * the pure protocol logic is JVM-unit-testable against lazysodium-java while
 * production wires lazysodium-android — both are thin JNA bindings over the
 * same libsodium C functions.
 */
object LemonDropOneShot {

    /** X3DH shared-secret HKDF label — MUST equal packages/crypto x3dh.ts. */
    private const val X3DH_INFO = "Zitrone-X3DH-v1"

    /** Ratchet root-KDF HKDF label — MUST equal packages/crypto ratchet.ts. */
    private const val ROOT_INFO = "Zitrone-Ratchet-Root-v1"

    /** GCM nonce length (aead.ts NONCE_BYTES). */
    private const val NONCE_BYTES = 12

    /** GCM tag length in bytes. */
    private const val TAG_BYTES = 16

    /** Bound on receiving-chain advances, mirroring ratchet.ts MAX_SKIP — a
     *  hostile envelope must not be able to buy unbounded HMAC work. */
    private const val MAX_SKIP = 1000

    /** libsodium crypto_box_seal overhead: ephemeral pk (32) + MAC (16). */
    private const val SEAL_OVERHEAD_BYTES = 48

    /** The three libsodium calls this module needs, and nothing more. */
    interface SodiumOps {
        /** crypto_box_seal_open; null when the seal does not open for this key. */
        fun sealOpen(sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray?

        /** crypto_scalarmult (X25519); null on failure (small-order result). */
        fun scalarMult(privateScalar: ByteArray, publicPoint: ByteArray): ByteArray?

        /** crypto_sign_ed25519_pk_to_curve25519; null when not a valid Edwards point. */
        fun ed25519PublicKeyToCurve25519(ed25519PublicKey: ByteArray): ByteArray?
    }

    /** A signed prekey's raw key material (public point, private scalar). */
    data class SignedPrekey(
        val id: Int,
        val publicKey: ByteArray,
        val privateScalar: ByteArray,
        /** Creation time — tried newest-first, like openLemonDrop's spk loop. */
        val timestampMs: Long,
    )

    /** A one-time prekey's raw key material. */
    data class OneTimePrekey(val publicKey: ByteArray, val privateScalar: ByteArray)

    /**
     * This device's key material for one open attempt. The identity key is the
     * libsignal Curve25519 (Montgomery) pair — it already IS the X25519 key the
     * creator sealed to (family-aware sealTo in packages/crypto lemondrop.ts).
     *
     * SINGLE-USE: the arrays here are per-call copies of store material. The
     * owner MUST call [zero] (in a finally) once the open attempt returns —
     * one-time prekeys resolved through [oneTimePrekey] are memoized precisely
     * so [zero] can reach them (review: Gemini on PR #4).
     */
    class RecipientKeys(
        val identityPublicKey: ByteArray,
        val identityPrivateScalar: ByteArray,
        val signedPrekeys: List<SignedPrekey>,
        private val oneTimePrekeyLoader: (Int) -> OneTimePrekey?,
    ) {
        private val loadedOneTimePrekeys = mutableListOf<OneTimePrekey>()

        /** Resolve a one-time prekey by id; null when no longer held. */
        fun oneTimePrekey(id: Int): OneTimePrekey? =
            oneTimePrekeyLoader(id)?.also { loadedOneTimePrekeys += it }

        /** Best-effort zero of every private scalar this object holds. */
        fun zero() {
            identityPrivateScalar.fill(0)
            signedPrekeys.forEach { it.privateScalar.fill(0) }
            loadedOneTimePrekeys.forEach { it.privateScalar.fill(0) }
        }
    }

    /**
     * Mirror of the web crypto layer's three honestly-distinct outcomes
     * (openLemonDrop's LemonDropOpenResult) — the UI may collapse them, the
     * crypto layer must not.
     */
    sealed interface Result {
        /** The seal did not open — not ours, or garbage; indistinguishable by design. */
        data object NotRecipient : Result

        /** The seal DID open (the drop WAS ours) but the payload is malformed
         *  or the inner envelope will not decrypt. */
        data object Invalid : Result

        data class Message(
            val text: String,
            /** The envelope's claimed sender account UUID. */
            val senderAccountId: String,
            /**
             * The sender's CLAIMED Ed25519 identity key from inside the sealed
             * payload. TRUST BOUNDARY: opening the seal proves the box was
             * addressed to us, NOT who wrote it — the caller MUST cross-check
             * against any pinned contact key before trusting the sender.
             */
            val senderIdentityKey: ByteArray,
            /** The recovered 32-byte burn token — present it to burn the drop. */
            val burnToken: ByteArray,
            /** One-time prekey the creator consumed; the caller deletes its
             *  private half AT DELIVERY (not at probe — a probe must leave the
             *  drop re-openable). Null when none was used. */
            val usedOneTimePrekeyId: Int?,
        ) : Result {
            /** Redacted — this object carries plaintext and key material. */
            override fun toString(): String = "Message(senderAccountId=$senderAccountId)"
        }
    }

    /**
     * Try to open a fetched lemon drop as this device. Never throws: every
     * failure maps to [Result.NotRecipient] (seal never opened) or
     * [Result.Invalid] (ours, but broken) exactly as openLemonDrop does.
     */
    fun open(sodium: SodiumOps, ciphertext: ByteArray, keys: RecipientKeys): Result {
        // Unpad + open the seal. ANY failure here means "not for you" — a
        // wrong-recipient scan and a corrupt blob must stay indistinguishable.
        val sealed = MessagePadding.unpadOrNull(ciphertext) ?: return Result.NotRecipient
        if (sealed.size < SEAL_OVERHEAD_BYTES) return Result.NotRecipient
        val payloadBytes = sodium.sealOpen(sealed, keys.identityPublicKey, keys.identityPrivateScalar)
            ?: return Result.NotRecipient

        // From here the box WAS ours — parse failures are "invalid", never
        // "not-recipient": the crypto layer must not claim a box it opened
        // belongs to someone else. The raw payload bytes (they contain the
        // burn token) are zeroed as soon as the parse has copied what it needs.
        val payload = parsePayload(payloadBytes).also { payloadBytes.fill(0) }
            ?: return Result.Invalid

        val senderIdentityCurve = sodium.ed25519PublicKeyToCurve25519(payload.senderIdentityKey)
            ?: return Result.Invalid

        // The one-time prekey the initiator consumed. If they named an id we no
        // longer hold, the responder DH simply won't reconstruct and every
        // decrypt below fails → Invalid, mirroring openLemonDrop.
        val otp = payload.prekeyId?.let(keys::oneTimePrekey)

        val blob = payload.envelopeCiphertext
        if (blob.size < 32 + NONCE_BYTES + TAG_BYTES) return Result.Invalid
        if (payload.messageNumber !in 0..MAX_SKIP) return Result.Invalid
        val theirRatchetKey = blob.copyOfRange(0, 32)
        val box = blob.copyOfRange(32, blob.size)

        // Try our signed prekeys newest-first — the creator used whichever was
        // current when they fetched our bundle, which may since have rotated.
        for (spk in keys.signedPrekeys.sortedByDescending { it.timestampMs }) {
            val plaintext =
                tryDecrypt(sodium, keys, spk, otp, senderIdentityCurve, payload, theirRatchetKey, box)
            if (plaintext != null) {
                val unpadded = MessagePadding.unpadOrNull(plaintext)
                plaintext.fill(0)
                val text = unpadded?.toString(Charsets.UTF_8)
                unpadded?.fill(0)
                if (text == null) continue
                return Result.Message(
                    text = text,
                    senderAccountId = payload.senderAccountId,
                    senderIdentityKey = payload.senderIdentityKey,
                    burnToken = payload.burnToken,
                    usedOneTimePrekeyId = payload.prekeyId,
                )
            }
        }

        // Sealed to us and well-formed, but no signed prekey decrypts it.
        return Result.Invalid
    }

    /** One responder attempt under one signed prekey; null on any failure. */
    private fun tryDecrypt(
        sodium: SodiumOps,
        keys: RecipientKeys,
        spk: SignedPrekey,
        otp: OneTimePrekey?,
        senderIdentityCurve: ByteArray,
        payload: Payload,
        theirRatchetKey: ByteArray,
        box: ByteArray,
    ): ByteArray? {
        val secrets = mutableListOf<ByteArray>()
        try {
            // X3DH responder, exactly x3dh.ts x3dhRespond:
            //   DH1 = DH(SPK_B, IK_A)  DH2 = DH(IK_B, EK_A)  DH3 = DH(SPK_B, EK_A)
            //   DH4 = DH(OPK_B, EK_A)  when a one-time prekey was consumed
            //   SK  = HKDF(F || DH1..DH4), F = 32 bytes of 0xFF, zero salt.
            val dh1 = sodium.scalarMult(spk.privateScalar, senderIdentityCurve) ?: return null
            secrets += dh1
            val dh2 = sodium.scalarMult(keys.identityPrivateScalar, payload.ephemeralKey) ?: return null
            secrets += dh2
            val dh3 = sodium.scalarMult(spk.privateScalar, payload.ephemeralKey) ?: return null
            secrets += dh3
            val parts = mutableListOf(ByteArray(32) { 0xFF.toByte() }, dh1, dh2, dh3)
            if (otp != null) {
                val dh4 = sodium.scalarMult(otp.privateScalar, payload.ephemeralKey) ?: return null
                secrets += dh4
                parts += dh4
            }
            val ikm = concat(parts)
            secrets += ikm
            val sk = hkdfSha256(ikm, ByteArray(32), X3DH_INFO, 32)
            secrets += sk

            // One Double-Ratchet receive step, exactly ratchet.ts's responder
            // path: root KDF over DH(SPK_B, their ratchet key), take the
            // receiving chain, advance it to the envelope's counter.
            val ratchetDh = sodium.scalarMult(spk.privateScalar, theirRatchetKey) ?: return null
            secrets += ratchetDh
            val okm = hkdfSha256(ratchetDh, sk, ROOT_INFO, 64)
            secrets += okm
            var chainKey = okm.copyOfRange(32, 64)
            secrets += chainKey
            repeat(payload.messageNumber) {
                val next = hmacSha256(chainKey, byteArrayOf(0x02))
                secrets += next
                chainKey = next
            }
            val messageKey = hmacSha256(chainKey, byteArrayOf(0x01))
            secrets += messageKey

            // AES-256-GCM with the ratchet header as AAD (ratchet.ts makeHeader:
            // ratchet_pub || n(4, BE) || pn(4, BE)); box = nonce(12) || ct+tag.
            val header = concat(
                listOf(
                    theirRatchetKey,
                    uint32BigEndian(payload.messageNumber),
                    uint32BigEndian(payload.previousChainLength),
                ),
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(messageKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, box, 0, NONCE_BYTES),
            )
            cipher.updateAAD(header)
            return cipher.doFinal(box, NONCE_BYTES, box.size - NONCE_BYTES)
        } catch (_: Exception) {
            // Wrong signed prekey, or genuinely undecryptable — caller tries the next.
            return null
        } finally {
            secrets.forEach { it.fill(0) }
        }
    }

    // ── payload parsing (mirror of packages/protocol parseLemonDrop) ─────────

    private class Payload(
        val senderAccountId: String,
        val senderIdentityKey: ByteArray,
        val burnToken: ByteArray,
        val ephemeralKey: ByteArray,
        val envelopeCiphertext: ByteArray,
        val prekeyId: Int?,
        val messageNumber: Int,
        val previousChainLength: Int,
    )

    /** RFC-4122 shape (versions 1–8, variant 89ab) — the SAME strictness the
     *  web parser applies (packages/protocol envelope.ts UUID_RE); both stacks
     *  must refuse identical payloads or "invalid" drifts per platform. */
    private val UUID_RE = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    /** Strict parse; null on ANY malformed field (fail closed → Invalid). */
    private fun parsePayload(payloadBytes: ByteArray): Payload? = try {
        val root = JSONObject(payloadBytes.toString(Charsets.UTF_8))
        val envelope = root.getJSONObject("envelope")
        val senderIdentityKey = base64Decode32(root.getString("sender_identity_key"))
        val burnToken = base64Decode32(root.getString("burn_token"))
        val ephemeralKey = base64Decode32(envelope.getString("ephemeral_key"))
        val envelopeCiphertext = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        val messageNumber = envelope.getInt("message_number")
        val previousChainLength = envelope.getInt("previous_chain_length")
        val senderAccountId = envelope.getString("sender_id")
        val prekeyId = if (envelope.isNull("prekey_id")) null else envelope.getInt("prekey_id")
        if (senderIdentityKey == null || burnToken == null || ephemeralKey == null ||
            envelopeCiphertext.isEmpty() || messageNumber < 0 || previousChainLength < 0 ||
            !UUID_RE.matches(senderAccountId)
        ) {
            null
        } else {
            Payload(
                senderAccountId = senderAccountId,
                senderIdentityKey = senderIdentityKey,
                burnToken = burnToken,
                ephemeralKey = ephemeralKey,
                envelopeCiphertext = envelopeCiphertext,
                prekeyId = prekeyId,
                messageNumber = messageNumber,
                previousChainLength = previousChainLength,
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Standard base64 decode that must yield exactly 32 bytes, else null. */
    private fun base64Decode32(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value).takeIf { it.size == 32 }
    } catch (_: IllegalArgumentException) {
        null
    }

    // ── primitives (byte-exact mirrors of packages/crypto kdf.ts) ────────────

    /**
     * HKDF-SHA-256 (RFC 5869), matching kdf.ts `hkdf`: extract with the salt
     * as HMAC key (salts here are always exactly 32 bytes), expand with the
     * UTF-8 info string and a 1-based counter byte.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        require(salt.size == 32) { "hkdf salt must be 32 bytes" }
        val prk = hmacSha256(salt, ikm)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmacSha256(prk, previous + infoBytes + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - filled)
            previous.copyInto(out, filled, 0, take)
            filled += take
            counter += 1
        }
        prk.fill(0)
        // `previous` holds the last expanded key-stream block — wipe it too so no
        // derived key material lingers (the returned `out` is the caller's to
        // zero once consumed).
        previous.fill(0)
        return out
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun uint32BigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }
}
