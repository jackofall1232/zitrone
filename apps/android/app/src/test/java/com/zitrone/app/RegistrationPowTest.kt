// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.LibsodiumRegistrationPowDeriver
import com.zitrone.app.crypto.RegistrationPow
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the 0.9.4 registration proof-of-work solver.
 *
 * The load-bearing one is [libsodiumMatchesGoArgon2idVectors]: the client solves with
 * libsodium and the relay verifies with Go's `x/crypto/argon2`. If those two ever disagree,
 * every proof this client produces is rejected — and the failure is silent from the client's
 * side (a valid-looking 403), so it must be caught here.
 */
class RegistrationPowTest {

    /** Deterministic stand-in for Argon2id — the puzzle logic is what's under test here,
     *  not the KDF, and a real 19 MiB derivation per attempt would make the suite crawl. */
    private val fakeDeriver = RegistrationPow.Argon2idDeriver { password, salt, t, m ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(password)
        md.update(salt)
        md.update(byteArrayOf(t.toByte(), m.toByte()))
        md.digest()
    }

    private fun token(bodyByte: Int = 7): String {
        // A well-formed 56-byte token: body(24) || tag(32). The tag is never checked
        // client-side (it is the relay's HMAC), so any filler is fine.
        val raw = ByteArray(RegistrationPow.CHALLENGE_TOKEN_BYTES) { bodyByte.toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    // ---------------------------------------------------------------- challenge decoding

    @Test
    fun challengeBodyTakesFirst24BytesOfTheToken() {
        val raw = ByteArray(RegistrationPow.CHALLENGE_TOKEN_BYTES) { it.toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val body = RegistrationPow.challengeBody(encoded)
        assertEquals(RegistrationPow.CHALLENGE_BODY_BYTES, body.size)
        assertArrayEquals(raw.copyOfRange(0, 24), body)
    }

    @Test
    fun challengeBodyRejectsWrongLengthToken() {
        val short = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))
        assertThrows(RegistrationPow.MalformedChallengeException::class.java) {
            RegistrationPow.challengeBody(short)
        }
    }

    @Test
    fun challengeBodyRejectsNonBase64() {
        assertThrows(RegistrationPow.MalformedChallengeException::class.java) {
            RegistrationPow.challengeBody("!!!! not base64 !!!!")
        }
    }

    // ------------------------------------------------------------------------ the puzzle

    @Test
    fun solveProducesAProofBothStagesAccept() {
        val identityKey = ByteArray(32) { (it * 3).toByte() }
        val params = RegistrationPow.Params(
            hashcashDifficulty = 8,
            argon2TimeCost = 1,
            argon2MemoryKiB = 8,
            argon2DifficultyBits = 8,
        )
        val t = token()
        val proof = RegistrationPow.solve(t, identityKey, params, fakeDeriver)

        assertEquals(RegistrationPow.HASHCASH_NONCE_BYTES, proof.hashcashNonce.size)
        assertEquals(RegistrationPow.ARGON_NONCE_BYTES, proof.argonNonce.size)
        assertEquals(t, proof.challengeToken)

        // Re-verify exactly as regpow.Verify would: bound = challengeBody || identityKey.
        val bound = RegistrationPow.challengeBody(t) + identityKey

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(bound)
        sha.update(proof.hashcashNonce)
        assertTrue(
            "hashcash stage does not satisfy the difficulty",
            com.zitrone.app.crypto.LemonDropCreate.hasLeadingZeroBits(sha.digest(), 8),
        )

        val out = fakeDeriver.derive(bound, proof.argonNonce, 1, 8)
        assertTrue(
            "argon2id stage does not satisfy the difficulty",
            com.zitrone.app.crypto.LemonDropCreate.hasLeadingZeroBits(out, 8),
        )
    }

    @Test
    fun proofBindsToIdentityKeySoItCannotBeReplayedForAnotherKey() {
        val params = RegistrationPow.Params(8, 1, 8, 8)
        val t = token()
        val proof = RegistrationPow.solve(t, ByteArray(32) { 1 }, params, fakeDeriver)

        // The SAME proof against a DIFFERENT identity key must not verify — this is
        // settled decision 4, and it is the property that stops bulk pre-farming.
        val otherBound = RegistrationPow.challengeBody(t) + ByteArray(32) { 2 }
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(otherBound)
        sha.update(proof.hashcashNonce)
        assertTrue(
            "a proof solved for one identity key also satisfied another — binding is broken",
            !com.zitrone.app.crypto.LemonDropCreate.hasLeadingZeroBits(sha.digest(), 8),
        )
    }

    @Test
    fun progressIsDrivenByWorkAndMayExceedTheExpectation() {
        val params = RegistrationPow.Params(6, 1, 8, 6)
        val seen = mutableListOf<RegistrationPow.Progress>()
        RegistrationPow.solve(token(), ByteArray(32), params, fakeDeriver) { seen.add(it) }

        assertTrue("no progress was reported", seen.isNotEmpty())
        assertTrue(
            "progress must be non-decreasing in work",
            seen.zipWithNext().all { (a, b) -> b.workDone >= a.workDone },
        )
        assertTrue(
            "the solver never reached the Argon2id stage",
            seen.any { it.stage == RegistrationPow.Stage.ARGON2ID },
        )
        // The contract explicitly permits fraction > 1 (geometric tail). Assert only that it
        // is finite and non-negative — clamping is the UI's business, and it must not.
        assertTrue(seen.all { it.fraction >= 0f && it.fraction.isFinite() })
    }

    @Test
    fun expectedAttemptsIsTwoToTheDifficulty() {
        assertEquals(1.0, RegistrationPow.expectedAttempts(0), 0.0)
        assertEquals(16.0, RegistrationPow.expectedAttempts(4), 0.0)
        assertEquals(1_048_576.0, RegistrationPow.expectedAttempts(20), 0.0)
        // Absurd inputs must not overflow into a negative/NaN expectation that would make
        // the UI fraction nonsense.
        assertTrue(RegistrationPow.expectedAttempts(9999).isFinite())
        assertTrue(RegistrationPow.expectedAttempts(-5) > 0.0)
    }

    @Test
    fun nonceIncrementCarriesAcrossBytes() {
        val n = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte())
        RegistrationPow.incrementNonce(n)
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), n)

        val wrap = ByteArray(4) { 0xFF.toByte() }
        RegistrationPow.incrementNonce(wrap)
        assertArrayEquals(ByteArray(4), wrap)
    }

    // ------------------------------------------------- cross-implementation (the important one)

    /**
     * Pins libsodium's `crypto_pwhash` against vectors produced by the RELAY's implementation
     * (`golang.org/x/crypto/argon2` v0.52.0, `IDKey`, p=1, 32-byte output).
     *
     * Vectors generated with password `"zitrone-regpow-crossimpl-vector"` and salt
     * `[0x00,0x01,…,0x0f]`. A failure here means the client and relay disagree about what
     * Argon2id is, so every proof is rejected — **fix the parameter mapping in
     * [LibsodiumRegistrationPowDeriver]; do not regenerate these vectors to match.**
     */
    @Test
    fun libsodiumMatchesGoArgon2idVectors() {
        val deriver = LibsodiumRegistrationPowDeriver(SodiumJava())
        val password = "zitrone-regpow-crossimpl-vector".toByteArray()
        val salt = ByteArray(16) { it.toByte() }

        val vectors = listOf(
            Triple(1, 19 * 1024, "95f18d6b36e307c7ddfda4eb1c45e0929589a7ce1b3a68b3e75aef52bd40f491"),
            Triple(2, 8 * 1024, "a533811c1aa79faaf074e80c70a7a16d5202c85662725b0f90af8457887b5dde"),
            Triple(3, 16 * 1024, "f4d32a72817052272c2a64d5d40834ceabfd7d3a2e3f1b82cf954d76a6f9a5ab"),
        )

        for ((timeCost, memKiB, expectedHex) in vectors) {
            val actual = deriver.derive(password, salt, timeCost, memKiB)
            assertEquals(
                "libsodium disagrees with the relay's Go Argon2id at t=$timeCost m=${memKiB}KiB " +
                    "— the client would produce proofs the relay rejects",
                expectedHex,
                actual.joinToString("") { "%02x".format(it) },
            )
        }
    }

    @Test
    fun deriverRejectsAWrongLengthSalt() {
        val deriver = LibsodiumRegistrationPowDeriver(SodiumJava())
        // Go's IDKey would accept this and just produce a different digest, so libsodium's
        // stricter requirement is the only thing that turns a silent wrong answer into a
        // loud one. Keep it loud.
        assertThrows(IllegalArgumentException::class.java) {
            deriver.derive("pw".toByteArray(), ByteArray(15), 1, 8)
        }
    }

    @Test
    fun differentSaltsGiveDifferentOutputs() {
        val deriver = LibsodiumRegistrationPowDeriver(SodiumJava())
        val a = deriver.derive("pw".toByteArray(), ByteArray(16) { 1 }, 1, 8)
        val b = deriver.derive("pw".toByteArray(), ByteArray(16) { 2 }, 1, 8)
        assertNotEquals(a.joinToString(""), b.joinToString(""))
    }
}
