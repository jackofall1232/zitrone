// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.SignalProtocolManager
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import java.security.SecureRandom
import java.util.Base64

/**
 * Key material for the synthetic relay account a vault addresses its cover traffic to.
 *
 * This is deliberately a SEPARATE, minimal path rather than a second [SignalProtocolManager]:
 * that class is bound to a [com.zitrone.app.crypto.ZitroneSignalStore] and every operation on it
 * persists into the vault's ordinary Signal-record space. The synthetic account needs exactly one
 * durable secret — its long-term identity keypair, which is what authenticates it to the relay —
 * and nothing else.
 *
 * **The prekey PRIVATE halves are generated and then DISCARDED, on purpose.** A real account keeps
 * them because peers establish X3DH sessions against its published bundle and it must decrypt what
 * arrives. Nothing ever decrypts a decoy: the ciphertext is random bytes by design (spec §2.3 — a
 * real ratchet with the synthetic peer would double the vault's reseal rate for no observable
 * gain), and the synthetic side acks and burns without reading. Keeping 100 one-time private
 * halves would therefore be pure durable cost for a capability that is ruled out. What IS uploaded
 * is a genuine, correctly-signed bundle of the same shape and batch size a real Android client
 * publishes, so the account is structurally an ordinary account.
 *
 * All byte/base64 conventions mirror [SignalProtocolManager] exactly — raw 32-byte identity key on
 * the wire (the server validates `len == 32`), signatures over libsignal's 33-byte `serialize()`
 * form, `java.util.Base64` rather than `android.util.Base64` so this is exercisable off-device.
 *
 * Nothing here logs, and no method returns a private key to a caller other than the serialized
 * keypair the vault stores.
 */
object DecoyIdentity {

    /** Batch size for the uploaded one-time prekeys — the same a real client publishes. */
    private const val ONE_TIME_PREKEY_BATCH = SignalProtocolManager.ONE_TIME_PREKEY_BATCH

    /** A registered bundle plus the serialized identity the vault must keep. */
    class Material(
        /** libsignal `IdentityKeyPair.serialize()` — PUBLIC ‖ PRIVATE. The vault stores this. */
        val identityKeyPair: ByteArray,
        /** 14-bit registration id, per the Signal spec (1..16380). Sent, never stored. */
        val registrationId: Int,
        val signedPreKey: SignalProtocolManager.SignedPreKeyDto,
        val oneTimePreKeys: List<SignalProtocolManager.OneTimePreKeyDto>,
    ) {
        val identityKeyBase64: String get() = publicKeyBase64(identityKeyPair)
    }

    /**
     * Generate a complete, registerable identity. Purely local — no network, no durable write.
     * The caller owns [Material.identityKeyPair] and is responsible for wiping it if the
     * registration it was generated for never commits.
     */
    fun generate(random: SecureRandom = SecureRandom()): Material {
        val identity = IdentityKeyPair.generate()
        val serialized = identity.serialize()
        // 14-bit registration id per the Signal spec (1..16380) — identical to
        // SignalProtocolManager.ensureIdentity, so nothing about this account's registration is
        // drawn from a different distribution than a real one's.
        val registrationId = random.nextInt(16380) + 1

        // Signed prekey: sign the 33-byte serialize() form (NOT the raw 32-byte wire form), the
        // representation a receiving peer reconstructs and verifies against — see the long note in
        // SignalProtocolManager.generateSignedPreKey. Signing the wrong representation would
        // produce a bundle the relay rejects with bad_prekey_signature.
        val signedPreKeyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(identity.privateKey, signedPreKeyPair.publicKey.serialize())
        val signedPreKey = SignalProtocolManager.SignedPreKeyDto(
            // Ids start at 1 like a fresh real account's allocator does.
            id = 1,
            publicKeyBase64 = encode(signedPreKeyPair.publicKey.getPublicKeyBytes()),
            signatureBase64 = encode(signature),
            timestampMs = System.currentTimeMillis(),
        )

        val oneTimePreKeys = (1..ONE_TIME_PREKEY_BATCH).map { id ->
            SignalProtocolManager.OneTimePreKeyDto(
                id = id,
                publicKeyBase64 = encode(Curve.generateKeyPair().publicKey.getPublicKeyBytes()),
            )
        }

        return Material(
            identityKeyPair = serialized,
            registrationId = registrationId,
            signedPreKey = signedPreKey,
            oneTimePreKeys = oneTimePreKeys,
        )
    }

    /**
     * The raw 32-byte identity public key, base64 — the wire form the relay validates
     * (`len(identity_key) == 32`; libsignal's `serialize()` would add a 0x05 type prefix and be
     * rejected as `bad_identity_key`). Also what the registration proof-of-work binds against.
     */
    fun publicKeyBase64(identityKeyPair: ByteArray): String =
        encode(publicKeyBytes(identityKeyPair))

    /** The raw 32-byte identity public key. */
    fun publicKeyBytes(identityKeyPair: ByteArray): ByteArray =
        IdentityKeyPair(identityKeyPair).publicKey.publicKey.getPublicKeyBytes()

    /**
     * Sign the relay's timestamped login challenge with the synthetic identity key — the same
     * XEdDSA-over-Curve25519 scheme [SignalProtocolManager.signLoginChallenge] uses, so the
     * account authenticates exactly as an ordinary Android account does.
     */
    fun signLoginChallenge(identityKeyPair: ByteArray, challenge: String): String =
        encode(
            IdentityKeyPair(identityKeyPair).privateKey
                .calculateSignature(challenge.toByteArray(Charsets.UTF_8)),
        )

    // java.util.Base64 (not android.util) — no Android runtime dependency, so every byte path
    // here is exercisable in an ordinary unit test. minSdk is 26, where java.util.Base64 exists.
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
