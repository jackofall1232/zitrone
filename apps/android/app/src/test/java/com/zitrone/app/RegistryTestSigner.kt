// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.LemonDropSodiumOps
import java.util.Base64

/**
 * Builds REAL signed registry envelopes for the registry tests — the mirror image of
 * `scripts/registry/registry-sign.mjs`, using lazysodium-java's `crypto_sign_detached`
 * (the identical libsodium C function the Node tool and the production verifier
 * speak, which is what makes these fixtures a real test of the byte path).
 */
internal object RegistryTestSigner {

    private val sodium = SodiumJava()

    /** The production-shaped verify hook, same seam AppContainer wires. */
    val ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean =
        LemonDropSodiumOps(sodium)::ed25519Verify

    class Keypair(val publicKey: ByteArray, val secretKey: ByteArray) {
        val publicB64Url: String get() = b64(publicKey)
    }

    fun freshKeypair(): Keypair {
        val pk = ByteArray(32)
        val sk = ByteArray(64)
        check(sodium.crypto_sign_keypair(pk, sk) == 0)
        return Keypair(pk, sk)
    }

    /** The default signing identity — "the registry key" from the tests' viewpoint. */
    private val key = freshKeypair()
    val trustRoot: String get() = key.publicB64Url

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun sign(payload: ByteArray, keypair: Keypair = key): ByteArray {
        val sig = ByteArray(64)
        check(sodium.crypto_sign_detached(sig, null, payload, payload.size.toLong(), keypair.secretKey) == 0)
        return sig
    }

    const val DEFAULT_RELAYS = """[{
        "id": "relay-cx23",
        "clearnet": { "apiBaseUrl": "https://relay.sublemonable.com",
                      "wsUrl": "wss://relay.sublemonable.com/ws" },
        "onion": null,
        "i2p": { "dest": "testdest.b32.i2p" }
    }]"""

    fun manifestJson(
        schemaVersion: Int = 1,
        epoch: Int = 1,
        validFrom: String = "2026-08-01T00:00:00Z",
        validUntil: String = "2026-11-01T00:00:00Z",
        previousManifestHash: String? = null,
        relaysJson: String = DEFAULT_RELAYS,
    ): String {
        val prev = if (previousManifestHash == null) "null" else "\"$previousManifestHash\""
        return """{
            "schemaVersion": $schemaVersion,
            "epoch": $epoch,
            "validFrom": "$validFrom",
            "validUntil": "$validUntil",
            "previousManifestHash": $prev,
            "relays": $relaysJson
        }"""
    }

    /**
     * A complete signed envelope. [extraSigner] prepends a signature by another key
     * (for the any-one-valid-signature case); [signWith] replaces the registry key
     * entirely (for wrong-key cases).
     */
    fun envelope(
        schemaVersion: Int = 1,
        epoch: Int = 1,
        validFrom: String = "2026-08-01T00:00:00Z",
        validUntil: String = "2026-11-01T00:00:00Z",
        previousManifestHash: String? = null,
        relaysJson: String = DEFAULT_RELAYS,
        envelopeVersion: Int = 1,
        extraSigner: Keypair? = null,
        signWith: Keypair = key,
    ): ByteArray {
        val payload = manifestJson(
            schemaVersion, epoch, validFrom, validUntil, previousManifestHash, relaysJson,
        ).toByteArray(Charsets.UTF_8)
        val sigs = buildList {
            extraSigner?.let {
                add("""{"keyId":"extra","algorithm":"ed25519","signature":"${b64(sign(payload, it))}"}""")
            }
            add("""{"keyId":"test-k1","algorithm":"ed25519","signature":"${b64(sign(payload, signWith))}"}""")
        }
        return """{
            "envelopeVersion": $envelopeVersion,
            "payload": "${b64(payload)}",
            "signatures": [${sigs.joinToString(",")}]
        }""".toByteArray(Charsets.UTF_8)
    }
}
