// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import com.goterl.lazysodium.Sodium

/**
 * [LemonDropOneShot.SodiumOps] over lazysodium's raw JNA bindings — the only
 * place the app calls libsodium. The adapter takes the `Sodium` base class so
 * the SAME code runs against lazysodium-android's `SodiumAndroid` (production,
 * prebuilt .so per ABI, no NDK build) and lazysodium-java's `SodiumJava`
 * (JVM unit tests) — both are bindings over the identical libsodium C
 * functions, which is exactly what makes the cross-stack round-trip test a
 * real test of the production byte path.
 *
 * Why libsodium at all: the sealed-box layer of a lemon drop is libsodium's
 * crypto_box_seal (X25519 + XSalsa20-Poly1305) — XSalsa20 exists in neither
 * libsignal-client nor the JCA, and hand-rolling a stream cipher for a
 * security-critical path is exactly what this project does not do. lazysodium
 * (com.goterl, 5.2.0, Maven Central, last released 2025) is the maintained
 * JVM/Android binding; the version is pinned in the catalog like every other
 * crypto-adjacent dependency.
 */
class LemonDropSodiumOps(private val sodium: Sodium) : LemonDropOneShot.SodiumOps {

    override fun sealOpen(
        sealed: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? {
        if (sealed.size < SEAL_BYTES || publicKey.size != 32 || privateKey.size != 32) return null
        val message = ByteArray(sealed.size - SEAL_BYTES)
        val rc = sodium.crypto_box_seal_open(message, sealed, sealed.size.toLong(), publicKey, privateKey)
        return if (rc == 0) message else null
    }

    override fun scalarMult(privateScalar: ByteArray, publicPoint: ByteArray): ByteArray? {
        if (privateScalar.size != 32 || publicPoint.size != 32) return null
        val shared = ByteArray(32)
        // libsodium returns -1 for a small-order/all-zero result — the same
        // failure the web stack surfaces as a thrown error. Fail, never use.
        val rc = sodium.crypto_scalarmult(shared, privateScalar, publicPoint)
        return if (rc == 0) shared else null
    }

    override fun ed25519PublicKeyToCurve25519(ed25519PublicKey: ByteArray): ByteArray? {
        if (ed25519PublicKey.size != 32) return null
        val curve = ByteArray(32)
        val rc = sodium.crypto_sign_ed25519_pk_to_curve25519(curve, ed25519PublicKey)
        return if (rc == 0) curve else null
    }

    override fun sealTo(recipientPublicKey: ByteArray, message: ByteArray): ByteArray? {
        if (recipientPublicKey.size != 32) return null
        val sealed = ByteArray(message.size + SEAL_BYTES)
        val rc = sodium.crypto_box_seal(sealed, message, message.size.toLong(), recipientPublicKey)
        return if (rc == 0) sealed else null
    }

    override fun ed25519Verify(
        signature: ByteArray,
        message: ByteArray,
        publicKey: ByteArray,
    ): Boolean {
        // libsodium rejects wrong lengths; guard so a Curve25519 (mobile) bundle,
        // whose 64-byte XEdDSA signature is NOT a valid Ed25519 signature over the
        // raw prekey, simply returns false and the caller tries the XEdDSA branch.
        if (signature.size != 64 || publicKey.size != 32) return false
        val rc = sodium.crypto_sign_verify_detached(signature, message, message.size.toLong(), publicKey)
        return rc == 0
    }

    override fun generateX25519KeyPair(): LemonDropOneShot.SodiumOps.X25519KeyPair? {
        val publicKey = ByteArray(32)
        val privateKey = ByteArray(32)
        val rc = sodium.crypto_box_keypair(publicKey, privateKey)
        return if (rc == 0) {
            LemonDropOneShot.SodiumOps.X25519KeyPair(publicKey, privateKey)
        } else {
            null
        }
    }

    override fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        sodium.randombytes_buf(out, length)
        return out
    }

    private companion object {
        /** crypto_box_SEALBYTES: ephemeral pk (32) + Poly1305 MAC (16). */
        const val SEAL_BYTES = 48
    }
}
