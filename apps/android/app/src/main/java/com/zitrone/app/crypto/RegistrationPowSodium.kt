// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import com.goterl.lazysodium.Sodium
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/**
 * Production [RegistrationPow.Argon2idDeriver] over libsodium's `crypto_pwhash`.
 *
 * Kept out of [RegistrationPow] itself so the solver stays libsodium-free at the type level
 * and the JVM suite can inject a cheap deriver — the same split
 * `crypto/vault/VaultSodiumOps.kt` uses, and for the same reason.
 *
 * **Cross-implementation contract (load-bearing, and verified by test, not assumed):**
 * the relay verifies with Go's `golang.org/x/crypto/argon2.IDKey`. Both this and that are
 * RFC 9106 Argon2id, and they agree byte-for-byte given the same
 * `(password, salt, timeCost, memoryKiB, p=1, 32-byte output)`. The mapping is:
 *
 * | this call | libsodium | Go `IDKey` |
 * |---|---|---|
 * | `timeCost` | `opslimit` | `time` |
 * | `memoryKiB` | `memlimit` (BYTES — multiplied here) | `memory` (KiB) |
 * | p=1 | fixed internally, no parameter | `threads` |
 *
 * `RegistrationPowVectorTest` pins this against vectors generated from the Go
 * implementation. If that test fails, the client is producing proofs the relay will reject —
 * fix the mapping, do not update the vectors.
 *
 * Construct over `SodiumAndroid` on device and `SodiumJava` in host tests — both are `Sodium`.
 */
class LibsodiumRegistrationPowDeriver(private val sodium: Sodium) : RegistrationPow.Argon2idDeriver {

    override fun derive(
        password: ByteArray,
        salt: ByteArray,
        timeCost: Int,
        memoryKiB: Int,
    ): ByteArray {
        require(salt.size == RegistrationPow.ARGON_NONCE_BYTES) {
            // libsodium's crypto_pwhash requires exactly crypto_pwhash_SALTBYTES (16).
            // Go's IDKey accepts any length, so a mismatch here would NOT fail on the relay
            // — it would just produce a different digest and a rejected proof. Fail loudly.
            "salt must be ${RegistrationPow.ARGON_NONCE_BYTES} bytes, was ${salt.size}"
        }
        require(timeCost >= 1) { "timeCost must be >= 1, was $timeCost" }
        require(memoryKiB >= 8) { "memoryKiB must be >= 8, was $memoryKiB" }

        val out = ByteArray(OUTPUT_BYTES)
        val rc = sodium.crypto_pwhash(
            out,
            out.size.toLong(),
            password,
            password.size.toLong(),
            salt,
            timeCost.toLong(),
            NativeLong(memoryKiB.toLong() * 1024L),
            ARGON2ID_ALG,
        )
        // The overwhelmingly likely cause is memlimit exceeding what the device will
        // allocate. Surfacing it as an exception (rather than returning garbage) keeps a
        // failed allocation from being mistaken for an unlucky puzzle attempt.
        check(rc == 0) {
            "crypto_pwhash failed (rc=$rc) at ${memoryKiB}KiB/t=$timeCost — out of memory?"
        }
        return out
    }

    private companion object {
        /** Mirrors `regpow.Argon2KeyLen`. */
        const val OUTPUT_BYTES = 32

        /** crypto_pwhash_ALG_ARGON2ID13 — the same algorithm id the vault pins. */
        val ARGON2ID_ALG: Int = PwHash.Alg.PWHASH_ALG_ARGON2ID13.value
    }
}
