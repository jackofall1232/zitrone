// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto

import com.zitrone.app.data.buildQrDropUrl
import org.json.JSONObject
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-shot lemon-drop (QR dead-drop) CREATOR — a deliberate, byte-exact mirror
 * of the web client's `createLemonDrop` (packages/crypto/src/lemondrop.ts) so an
 * Android device can seal a drop addressed to one recipient. This is the CREATE
 * counterpart to [LemonDropOneShot] (the OPEN half): the two files are the
 * ground-truth pair, and the cross-stack round trip (create here → open there,
 * and here → web openLemonDrop) pins their bytes together.
 *
 * ISOLATION CONTRACT — read before touching:
 *
 *  - This is NOT part of ordinary messaging. It never touches
 *    [SignalProtocolManager], never reads or writes a libsignal session, and
 *    never advances a persistent Double Ratchet. A lemon drop is ONE-WAY,
 *    ONE-SHOT, session-less delivery: the X3DH initiator session built here
 *    encrypts EXACTLY ONE message and is discarded before returning — the same
 *    encrypt-and-forget the web `createLemonDrop` performs and the mirror image
 *    of [LemonDropOneShot], which decrypts exactly one and throws its session
 *    away. Reusing a live session here would fork ratchet state.
 *  - It speaks the WEB stack's formats on purpose (see [LemonDropOneShot]'s
 *    contract): libsodium sealed box outside, Zitrone one-shot X3DH (HKDF label
 *    "Zitrone-X3DH-v1") + one Double-Ratchet send step (HKDF label
 *    "Zitrone-Ratchet-Root-v1", AES-256-GCM, header as AAD) inside, with
 *    [MessagePadding]'s 256-byte blocks at both layers. The HKDF labels and the
 *    ratchet header AAD MUST equal [LemonDropOneShot]'s — that file is the
 *    ground truth for the open side and any change starts by keeping the two in
 *    lockstep.
 *  - The sender identity is this device's libsignal Curve25519 (Montgomery)
 *    pair: its raw-32 public key ([SenderIdentity.publicKey]) is what
 *    registration uploads and what the drop stamps as `sender_identity_key`
 *    (family `"curve25519"`), so the recipient's pinned-key compare is direct
 *    with zero conversions. The private half is a raw X25519 scalar
 *    ([SenderIdentity.privateScalar], from ECPrivateKey.serialize()) — the same
 *    narrow, documented exception to "private key bytes never leave the store"
 *    that LemonDropRedeemer's bridge is on the open side. [create] holds it for
 *    one call and best-effort zeros it, and every derived secret, before it
 *    returns (mirroring LemonDropOneShot.tryDecrypt's discipline).
 *
 * The libsodium primitives come in through [LemonDropOneShot.SodiumOps] (one
 * adapter over the same C functions for JVM tests and the device); the
 * Curve25519 (mobile-family) bundle signature is verified through libsignal's
 * own [Curve.verifySignature] via [XEdDSAVerifier], so an Android recipient's
 * XEdDSA-signed prekey is checked by the exact library that signed it.
 */
object LemonDropCreate {

    /** X3DH shared-secret HKDF label — MUST equal [LemonDropOneShot.X3DH_INFO]
     *  (packages/crypto x3dh.ts). */
    private const val X3DH_INFO = "Zitrone-X3DH-v1"

    /** Ratchet root-KDF HKDF label — MUST equal [LemonDropOneShot]'s ROOT_INFO
     *  (packages/crypto ratchet.ts). */
    private const val ROOT_INFO = "Zitrone-Ratchet-Root-v1"

    /** GCM nonce length (aead.ts NONCE_BYTES). */
    private const val NONCE_BYTES = 12

    /** GCM tag length in bytes. */
    private const val TAG_BYTES = 16

    /** qr_id length — 128 bits, minted at random (protocol QR_DROP_ID_BYTES). */
    const val QR_DROP_ID_BYTES = 16

    /** Burn-token length — 256 bits, rides INSIDE the sealed payload
     *  (protocol QR_DROP_BURN_TOKEN_BYTES). */
    const val QR_DROP_BURN_TOKEN_BYTES = 32

    /** Deposit hashcash difficulty, in leading zero bits (deaddrop.ts
     *  DEFAULT_POW_DIFFICULTY / protocol DROP_POW_DIFFICULTY / server
     *  cfg.DropPoWDifficulty). */
    const val POW_DIFFICULTY = 20

    /** The sender family an Android creator always stamps — its identity key IS
     *  the Montgomery point, so the recipient DHs against it verbatim (protocol
     *  SenderKeyFamily; mirrors [LemonDropOneShot]'s curve25519 branch). */
    private const val SENDER_KEY_FAMILY_CURVE = "curve25519"

    private const val PROTOCOL_VERSION = "1"
    private const val CONTROL_VERSION = 1
    private const val LEMON_DROP_CONTROL = "lemondrop.v1"

    /**
     * XEdDSA verification of a Curve25519 (Android/iOS) bundle's signed-prekey
     * signature. Injected so [create] stays libsignal-free at the type level and
     * the JVM suite can pass a stub; production is [LibsignalXEdDSAVerifier].
     */
    fun interface XEdDSAVerifier {
        /** True when [signature] is a valid XEdDSA signature by [identityKey]
         *  (raw-32 Curve25519) over the 33-byte 0x05-tagged serialize() form of
         *  [rawSignedPrekeyPublic]. Never throws — false on any malformed input. */
        fun verify(
            identityKey: ByteArray,
            rawSignedPrekeyPublic: ByteArray,
            signature: ByteArray,
        ): Boolean
    }

    /** The recipient's fetched prekey bundle, decoded to raw bytes. Public halves
     *  only — a bundle carries nothing secret. */
    class RecipientBundle(
        /** Raw-32 identity key — Ed25519 (web) or Curve25519 Montgomery (mobile);
         *  which one is decided by signature verification, never assumed. */
        val identityKey: ByteArray,
        val signedPrekeyId: Int,
        val signedPrekeyPublic: ByteArray,
        val signedPrekeySignature: ByteArray,
        /** Null when the recipient's one-time-prekey stock ran out. */
        val oneTimePrekeyId: Int?,
        val oneTimePrekeyPublic: ByteArray?,
    )

    /** This device's sender identity for one create call. [privateScalar] is a
     *  per-call copy of store material — [create] zeros it before returning. */
    class SenderIdentity(val publicKey: ByteArray, val privateScalar: ByteArray)

    sealed interface Result {
        /**
         * Everything the caller needs to deposit and to render/print the sticker.
         * Raw bytes; the orchestrator base64(url)-encodes for the wire.
         */
        data class Created(
            /** 16 random bytes; base64url-encoded for the wire qr_id + sticker URL. */
            val qrId: ByteArray,
            /** `https://zitrone.app/d/{qr_id}` — the string the QR encodes. */
            val url: String,
            /** Padded sealed box — the deposit ciphertext (standard base64 on wire). */
            val ciphertext: ByteArray,
            /** SHA-256(burn_token) — deposited as burn_hash so only a decryptor burns. */
            val burnHash: ByteArray,
            /** 8-byte hashcash nonce solving the PoW over qrId. */
            val powNonce: ByteArray,
        ) : Result

        /**
         * The recipient bundle verified under NEITHER real signature scheme —
         * fail closed, exactly as the web x3dhInitiate throws. No drop is
         * created; the caller surfaces a refusal (never a silent send to an
         * unverifiable key).
         */
        data object BundleUnverified : Result
    }

    /**
     * Seal a lemon drop to one recipient. Never advances any session; discards
     * the ephemeral X3DH + ratchet state before returning; zeros the sender
     * scalar and every derived secret in a finally. CPU-heavy (the PoW averages
     * ~1M SHA-256): call on a background, cancellable dispatcher.
     */
    fun create(
        sodium: LemonDropOneShot.SodiumOps,
        verifyXEdDSA: XEdDSAVerifier,
        senderAccountId: String,
        sender: SenderIdentity,
        recipientAccountId: String,
        bundle: RecipientBundle,
        text: String,
    ): Result {
        // Family classification — the client mirror of the relay's try-both
        // verifySignedPrekey (handlers.go) and the web classifyBundleIdentity:
        // plain Ed25519 over the raw prekey (web/desktop), else XEdDSA over the
        // 33-byte tagged form (Android/iOS). FAIL CLOSED — neither ⇒ refuse.
        val family = classify(sodium, verifyXEdDSA, bundle) ?: return Result.BundleUnverified

        // An Ed25519 (web) recipient identity participates in DH via the
        // birational map; a Curve25519 (mobile) one ALREADY IS the X25519 point
        // and is used verbatim — converting it as-if-Edwards would derive
        // garbage and seal to a key nobody holds (mirror of x3dhInitiate's
        // family-aware branch, and of sealTo's in lemondrop.ts).
        val recipientX25519 =
            if (family == SENDER_KEY_FAMILY_CURVE) {
                bundle.identityKey
            } else {
                sodium.ed25519PublicKeyToCurve25519(bundle.identityKey) ?: return Result.BundleUnverified
            }

        val secrets = mutableListOf<ByteArray>(sender.privateScalar)
        var paddedPlaintext: ByteArray? = null
        var payloadBytes: ByteArray? = null
        try {
            val ephemeral = sodium.generateX25519KeyPair() ?: return Result.BundleUnverified
            secrets += ephemeral.privateKey

            // X3DH initiator, exactly x3dh.ts x3dhInitiate:
            //   DH1 = DH(IK_A, SPK_B)  DH2 = DH(EK_A, IK_B)  DH3 = DH(EK_A, SPK_B)
            //   DH4 = DH(EK_A, OPK_B)  when a one-time prekey is available
            //   SK  = HKDF(F || DH1..DH4), F = 32 bytes of 0xFF, zero salt.
            val dh1 = sodium.scalarMult(sender.privateScalar, bundle.signedPrekeyPublic)
                ?: return Result.BundleUnverified
            secrets += dh1
            val dh2 = sodium.scalarMult(ephemeral.privateKey, recipientX25519)
                ?: return Result.BundleUnverified
            secrets += dh2
            val dh3 = sodium.scalarMult(ephemeral.privateKey, bundle.signedPrekeyPublic)
                ?: return Result.BundleUnverified
            secrets += dh3
            val parts = mutableListOf(ByteArray(32) { 0xFF.toByte() }, dh1, dh2, dh3)
            val otpPublic = bundle.oneTimePrekeyPublic
            if (bundle.oneTimePrekeyId != null && otpPublic != null) {
                val dh4 = sodium.scalarMult(ephemeral.privateKey, otpPublic)
                    ?: return Result.BundleUnverified
                secrets += dh4
                parts += dh4
            }
            val ikm = concat(parts)
            secrets += ikm
            val sk = hkdfSha256(ikm, ByteArray(32), X3DH_INFO, 32)
            secrets += sk

            // initRatchetAsInitiator + one ratchetEncrypt (ratchet.ts): a fresh
            // sending ratchet keypair, root KDF over DH(dhSelf, SPK_B), then the
            // first message key off the sending chain. n = 0, pn = 0.
            val dhSelf = sodium.generateX25519KeyPair() ?: return Result.BundleUnverified
            secrets += dhSelf.privateKey
            val rootDh = sodium.scalarMult(dhSelf.privateKey, bundle.signedPrekeyPublic)
                ?: return Result.BundleUnverified
            secrets += rootDh
            val okm = hkdfSha256(rootDh, sk, ROOT_INFO, 64)
            secrets += okm
            val sendingChainKey = okm.copyOfRange(32, 64)
            secrets += sendingChainKey
            val messageKey = hmacSha256(sendingChainKey, byteArrayOf(0x01))
            secrets += messageKey

            paddedPlaintext = MessagePadding.pad(text.toByteArray(Charsets.UTF_8))
            // AES-256-GCM with the ratchet header as AAD (ratchet.ts makeHeader:
            // ratchet_pub || n(4, BE) || pn(4, BE)); box = nonce(12) || ct+tag;
            // blob = ratchet_pub || box (what LemonDropOneShot.open slices back).
            val header = concat(
                listOf(dhSelf.publicKey, uint32BigEndian(0), uint32BigEndian(0)),
            )
            val nonce = sodium.randomBytes(NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(messageKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, nonce),
            )
            cipher.updateAAD(header)
            val ctAndTag = cipher.doFinal(paddedPlaintext)
            val blob = concat(listOf(dhSelf.publicKey, nonce, ctAndTag))

            // Burn token: minted here, embedded (base64) in the sealed payload,
            // never retained — only the recipient recovers it. The relay stores
            // its SHA-256 so a wrong-recipient scanner physically cannot burn.
            val burnToken = sodium.randomBytes(QR_DROP_BURN_TOKEN_BYTES)
            secrets += burnToken
            val burnHash = MessageDigest.getInstance("SHA-256").digest(burnToken)

            val envelope = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("sender_id", senderAccountId)
                put("recipient_id", recipientAccountId)
                put("ciphertext", Base64.getEncoder().encodeToString(blob))
                put("ephemeral_key", Base64.getEncoder().encodeToString(ephemeral.publicKey))
                if (bundle.oneTimePrekeyId != null) {
                    put("prekey_id", bundle.oneTimePrekeyId)
                } else {
                    put("prekey_id", JSONObject.NULL)
                }
                put("message_number", 0)
                put("previous_chain_length", 0)
                put("timestamp", java.time.Instant.now().toString())
                put("ttl_seconds", JSONObject.NULL)
                put("burn_on_read", false)
                put("media_type", "text")
                put("version", PROTOCOL_VERSION)
            }
            // serializeLemonDrop (protocol lemondrop.ts): the sender_key_family
            // key is EMITTED ONLY for curve25519 — an Android creator always sets
            // it, so the recipient DHs against the identity key verbatim.
            val payload = JSONObject().apply {
                put("v", CONTROL_VERSION)
                put("control", LEMON_DROP_CONTROL)
                put("envelope", envelope)
                put("sender_identity_key", Base64.getEncoder().encodeToString(sender.publicKey))
                put("burn_token", Base64.getEncoder().encodeToString(burnToken))
                put("sender_key_family", SENDER_KEY_FAMILY_CURVE)
            }
            payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)

            // Seal the whole payload to the recipient's identity key, then pad to
            // a fixed block: the relay and any wrong-recipient scanner see only
            // opaque, length-uninformative bytes.
            val sealed = sodium.sealTo(recipientX25519, payloadBytes) ?: return Result.BundleUnverified
            val ciphertext = MessagePadding.pad(sealed)

            // Admission control: hashcash over the qr_id, bound to this deposit so
            // it cannot be precomputed or reused. Deposit is unauthenticated; PoW
            // is the only cost (same puzzle as dead drops).
            val qrId = sodium.randomBytes(QR_DROP_ID_BYTES)
            val powNonce = solveProofOfWork(qrId, POW_DIFFICULTY)

            return Result.Created(
                qrId = qrId,
                url = buildQrDropUrl(qrId),
                ciphertext = ciphertext,
                burnHash = burnHash,
                powNonce = powNonce,
            )
        } finally {
            secrets.forEach { it.fill(0) }
            // These hold plaintext / the burn token (base64) — zero them too.
            paddedPlaintext?.fill(0)
            payloadBytes?.fill(0)
        }
    }

    /** Ed25519 (web) then XEdDSA (mobile); null ⇒ neither ⇒ fail closed. Returns
     *  the family string used to decide the recipient identity's DH treatment. */
    private fun classify(
        sodium: LemonDropOneShot.SodiumOps,
        verifyXEdDSA: XEdDSAVerifier,
        bundle: RecipientBundle,
    ): String? {
        if (sodium.ed25519Verify(
                bundle.signedPrekeySignature,
                bundle.signedPrekeyPublic,
                bundle.identityKey,
            )
        ) {
            return "ed25519"
        }
        if (verifyXEdDSA.verify(
                bundle.identityKey,
                bundle.signedPrekeyPublic,
                bundle.signedPrekeySignature,
            )
        ) {
            return SENDER_KEY_FAMILY_CURVE
        }
        return null
    }

    /**
     * Solve a hashcash puzzle: an 8-byte big-endian counter nonce such that
     * SHA-256(challenge || nonce) begins with [difficulty] zero bits (MSB-first).
     * Byte-exact with deaddrop.ts `solveProofOfWork` and pow.go `Verify`: the
     * challenge is the raw qr_id, the nonce is 8 bytes, the preimage is
     * challenge||nonce. Interruptible — checks the thread's interrupt flag so a
     * cancelled coroutine on Dispatchers.Default actually stops the search.
     */
    internal fun solveProofOfWork(challenge: ByteArray, difficulty: Int): ByteArray {
        val nonce = ByteArray(8)
        val sha = MessageDigest.getInstance("SHA-256")
        val preimage = ByteArray(challenge.size + nonce.size)
        challenge.copyInto(preimage, 0)
        var i = 0L
        while (true) {
            nonce.copyInto(preimage, challenge.size)
            sha.reset()
            if (hasLeadingZeroBits(sha.digest(preimage), difficulty)) return nonce
            incrementCounter(nonce)
            // ~one interrupt check per animation-frame's worth of hashing; keeps
            // the loop cancellable without measurably slowing the ~1M-hash solve.
            if ((++i and 0x1FFF) == 0L && Thread.currentThread().isInterrupted) {
                throw InterruptedException("proof-of-work cancelled")
            }
        }
    }

    /** True if [digest] begins with at least [bits] zero bits (MSB-first) —
     *  mirror of deaddrop.ts `hasLeadingZeroBits` and pow.go. */
    internal fun hasLeadingZeroBits(digest: ByteArray, bits: Int): Boolean {
        var remaining = bits
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

    /** 8-byte big-endian counter increment (deaddrop.ts `incrementCounter`):
     *  least-significant byte is the last, carry propagates toward index 0. */
    private fun incrementCounter(counter: ByteArray) {
        for (idx in counter.indices.reversed()) {
            val next = (counter[idx].toInt() and 0xFF) + 1
            counter[idx] = next.toByte()
            if (next and 0xFF != 0) return // no carry
        }
    }

    // ── primitives (byte-exact mirrors of LemonDropOneShot / kdf.ts) ─────────
    // Duplicated from LemonDropOneShot ON PURPOSE: that file is the reviewed
    // open-side ground truth and stays untouched; the create→open round-trip
    // test (and the web cross-stack test) fail loudly on any drift here.

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        require(salt.size == 32) { "hkdf salt must be 32 bytes" }
        val prk = hmacSha256(salt, ikm)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArray(length)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val counterBuf = ByteArray(1)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            if (previous.isNotEmpty()) mac.update(previous)
            mac.update(infoBytes)
            counterBuf[0] = counter.toByte()
            mac.update(counterBuf)
            val block = mac.doFinal()
            val take = minOf(block.size, length - filled)
            block.copyInto(out, filled, 0, take)
            filled += take
            counter += 1
            previous.fill(0)
            previous = block
        }
        previous.fill(0)
        prk.fill(0)
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

/**
 * Production [LemonDropCreate.XEdDSAVerifier]: libsignal's own Curve25519
 * signature check, over the 33-byte 0x05-tagged serialize() form the mobile
 * signer produced. Using libsignal (not the web's BigInt XEdDSA port) means an
 * Android recipient's XEdDSA-signed prekey is verified by the exact library
 * that signed it — the same reconstruction SessionBuilder.process does.
 */
object LibsignalXEdDSAVerifier : LemonDropCreate.XEdDSAVerifier {
    override fun verify(
        identityKey: ByteArray,
        rawSignedPrekeyPublic: ByteArray,
        signature: ByteArray,
    ): Boolean = try {
        val signingKey = ECPublicKey.fromPublicKeyBytes(identityKey)
        // The mobile client signed the full serialize() form (DJB tag 0x05 + the
        // 32-byte key), NOT the raw wire bytes — reconstruct it to verify.
        val message = ECPublicKey.fromPublicKeyBytes(rawSignedPrekeyPublic).serialize()
        Curve.verifySignature(signingKey, message, signature)
    } catch (_: Exception) {
        false
    }
}
