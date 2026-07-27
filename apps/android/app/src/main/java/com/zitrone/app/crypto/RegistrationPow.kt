// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import com.zitrone.app.BuildConfig
import java.security.MessageDigest
import java.util.Base64

/**
 * 0.9.4-beta registration proof-of-work — CLIENT SOLVER.
 *
 * Mirrors `server/internal/regpow/regpow.go`. Registration is two round-trips: fetch a
 * server-issued HMAC-signed challenge, then submit a proof alongside the registration.
 *
 * The ladder, and who pays for what:
 *  1. **Challenge** — opaque to us. We decode only its *body* (timestamp||nonce) to bind
 *     against, and hand the whole token back verbatim. The HMAC is the relay's to check.
 *  2. **SHA-256 hashcash pre-stage** — the SAME primitive dead drops already ship
 *     ([LemonDropCreate.solveProofOfWork]). ~2^[Params.hashcashDifficulty] hashes.
 *  3. **Argon2id main stage** — a leading-zero PUZZLE, not a single evaluation: search a
 *     16-byte salt until the output has [Params.argon2DifficultyBits] leading zero bits.
 *     **The client runs ~2^bits evaluations; the relay runs exactly ONE.** That asymmetry
 *     is the entire point of the design — see docs/REGISTRATION_POW_CALIBRATION.md.
 *
 * Both stages run over the same bound input `challengeBody || identityKey`, so a solved
 * proof cannot be replayed for a different identity key under the same challenge
 * (spec settled decision 4, matching `regpow.bindChallenge`).
 *
 * **Argon2id parameters here are DELIBERATELY NOT the vault's** (64 MiB / t=3, see
 * `crypto/vault/VaultSodiumOps.kt`). Vault derivation is tuned for brute-force resistance
 * against a stolen device; this is tuned for a few seconds of phone CPU at relay-verification
 * volume. **Do not "harmonise" the two.** They answer different questions.
 *
 * Threading: [solve] BLOCKS and is CPU-bound for seconds. Call it off the main thread. It
 * cooperates with thread interruption (like the shipped hashcash solver) — that is the only
 * cancellation mechanism, and the UI does not own it.
 */
object RegistrationPow {

    /** Client-chosen Argon2id salt length. Mirrors `regpow.ArgonNonceBytes`, and is also
     *  exactly libsodium's required `crypto_pwhash_SALTBYTES`. */
    const val ARGON_NONCE_BYTES = 16

    /** Hashcash nonce length. Mirrors `pow.NonceBytes`. */
    const val HASHCASH_NONCE_BYTES = 8

    /** Challenge body = timestamp(8) || nonce(16). Mirrors `regpow.challengeBodyBytes`. */
    const val CHALLENGE_BODY_BYTES = 24

    /** Body + HMAC-SHA256 tag. Mirrors `regpow.challengeTokenBytes`. */
    const val CHALLENGE_TOKEN_BYTES = CHALLENGE_BODY_BYTES + 32

    /**
     * How many SHA-256 hashes one Argon2id evaluation "costs", for progress weighting ONLY.
     *
     * Measured on CX33 (docs/REGISTRATION_POW_CALIBRATION.md): Argon2id at 19 MiB/t=1 is
     * 23.2 ms and SHA-256 runs at 10.01 MH/s, so one evaluation ≈ 2.3e5 hashes. Both figures
     * scale with CPU speed, so the RATIO travels across devices far better than either
     * absolute number — which is why a constant is defensible here and would not be for the
     * difficulty itself.
     *
     * This affects nothing but the progress fraction the UI renders. It is not a security
     * parameter and the relay neither sees nor cares about it.
     */
    const val ARGON_EVAL_HASH_EQUIVALENT = 230_000.0

    /** The relay's PoW parameters, as fetched/configured. Names mirror `regpow.Params`. */
    data class Params(
        val hashcashDifficulty: Int,
        val argon2TimeCost: Int,
        val argon2MemoryKiB: Int,
        val argon2DifficultyBits: Int,
    )

    /**
     * The parameters this client solves with in production.
     *
     * **The challenge token carries no parameters** (it is timestamp||nonce||HMAC only), so
     * client and relay agree by CONFIGURATION, not by protocol: these must mirror the relay's
     * `REGISTRATION_HASHCASH_DIFFICULTY` / `REGISTRATION_ARGON2_TIME_COST` /
     * `REGISTRATION_ARGON2_MEMORY_KIB` / `REGISTRATION_ARGON2_DIFFICULTY_BITS` env at flip
     * time. A mismatch on any of the four silently rejects every proof (the client just sees
     * 403) — see docs/DEPLOY_0.9.4_POW.md step-5 preconditions.
     *
     * TODO(pow-calibration): `argon2DifficultyBits = 4` is a FIRST REAL-WORLD CALIBRATION
     * ATTEMPT, NOT a measured value. The relay-side placeholder default (D=8, 256 expected
     * evaluations) is established as far too high — ~5.9 s on a 4-core server, so tens of
     * seconds on a throttled phone (docs/REGISTRATION_POW_CALIBRATION.md). D=4 is the low end
     * of that doc's projected D=4–5 landing zone: 16 expected evaluations ≈ 0.4 s on a CX33
     * core, plausibly 1–3 s expected on budget hardware with the geometric 3× tail still
     * under ~10 s — numbers a first device run can CORRECT rather than merely survive. This
     * marker is not resolved until a measured Revvl 6x figure (battery saver, foreground)
     * comes back through the Diagnostics screen's `pow:` lines and replaces it.
     *
     * Hashcash 20 and 19 MiB/t=1 are deliberate: difficulty 20 is the production-proven
     * dead-drop constant already shipped on phones ([LemonDropCreate.POW_DIFFICULTY]), and
     * the Argon2id cost parameters match the relay's verification defaults — raising m/t
     * slows relay verification one-for-one, so D is the only calibration knob (see the
     * calibration doc).
     */
    val DEFAULT_PARAMS = Params(
        hashcashDifficulty = 20,
        argon2TimeCost = 1,
        argon2MemoryKiB = 19 * 1024,
        argon2DifficultyBits = 4,
    )

    /** Which stage the solver is in. Ordinal order is execution order. */
    enum class Stage { HASHCASH, ARGON2ID }

    /**
     * A progress observation. [workDone] and [workExpected] are in SHA-256-hash-equivalents
     * (see [ARGON_EVAL_HASH_EQUIVALENT]) so the two stages share one scale.
     *
     * **[fraction] CAN EXCEED 1.0, and that is correct, not a bug.** Both stages are
     * memoryless searches, so the work required is geometrically distributed around the
     * expectation: ~37% of solves run past 1.0 and ~5% past 3.0. A UI that clamps this to a
     * full bar will sit at "100%" for an unbounded time, which reads as a hang. Render the
     * overflow honestly (see the UI contract next to RegistrationPowScreen).
     */
    data class Progress(
        val stage: Stage,
        val workDone: Double,
        val workExpected: Double,
        val hashcashHashes: Long,
        val argonEvaluations: Long,
    ) {
        val fraction: Float get() = if (workExpected <= 0.0) 0f else (workDone / workExpected).toFloat()
    }

    /** Reported at most every [PROGRESS_STRIDE] hashes / every Argon2id evaluation. */
    fun interface ProgressSink {
        fun onProgress(progress: Progress)
    }

    /**
     * Argon2id at ARBITRARY parameters. Deliberately a separate surface from
     * `VaultSodiumOps.argon2idDeriveKey`, which hard-codes the vault's constants and must
     * keep doing so. Injectable so host tests can substitute a cheap deriver.
     */
    fun interface Argon2idDeriver {
        /** Argon2id (RFC 9106), p=1, 32-byte output. [salt] is [ARGON_NONCE_BYTES] bytes. */
        fun derive(password: ByteArray, salt: ByteArray, timeCost: Int, memoryKiB: Int): ByteArray
    }

    /** A completed proof, ready to submit with the registration request. */
    data class Proof(
        val challengeToken: String,
        val hashcashNonce: ByteArray,
        val argonNonce: ByteArray,
    ) {
        /** Wire form of `handlers.go powProofJSON` — base64 STD (the relay decodes with
         *  `base64.StdEncoding`), NOT the URL alphabet the challenge token uses. */
        fun toJsonMap(): Map<String, String> = mapOf(
            "challenge_token" to challengeToken,
            "hashcash_nonce" to Base64.getEncoder().encodeToString(hashcashNonce),
            "argon_nonce" to Base64.getEncoder().encodeToString(argonNonce),
        )

        // ByteArray fields make the generated equals/hashCode reference-based, which is a
        // trap in tests. Compare by content.
        override fun equals(other: Any?): Boolean =
            other is Proof &&
                challengeToken == other.challengeToken &&
                hashcashNonce.contentEquals(other.hashcashNonce) &&
                argonNonce.contentEquals(other.argonNonce)

        override fun hashCode(): Int =
            (challengeToken.hashCode() * 31 + hashcashNonce.contentHashCode()) * 31 +
                argonNonce.contentHashCode()
    }

    /** Raised when the relay hands back a token that is not a well-formed challenge. */
    class MalformedChallengeException(message: String) : IllegalArgumentException(message)

    /**
     * Decode the challenge token and return the BODY to bind against.
     *
     * The token is `base64url, NO padding` — `base64.RawURLEncoding` on the Go side. Getting
     * this alphabet wrong fails closed (decode error), it does not silently produce a wrong
     * binding, which is why it is checked here rather than trusted.
     *
     * Uses JDK [Base64] rather than `android.util.Base64` so this stays exercisable from the
     * host suite — the same choice `LemonDropCreate` makes, and minSdk is 26.
     */
    internal fun challengeBody(challengeToken: String): ByteArray {
        val raw = try {
            Base64.getUrlDecoder().decode(challengeToken)
        } catch (e: IllegalArgumentException) {
            throw MalformedChallengeException("challenge token is not base64url: ${e.message}")
        }
        if (raw.size != CHALLENGE_TOKEN_BYTES) {
            throw MalformedChallengeException(
                "challenge token is ${raw.size} bytes, expected $CHALLENGE_TOKEN_BYTES",
            )
        }
        return raw.copyOfRange(0, CHALLENGE_BODY_BYTES)
    }

    /**
     * Apply the DEBUG-ONLY difficulty override, if one is configured.
     *
     * Burn testing re-registers constantly; paying a real solve per cycle makes that loop
     * unusable. Two independent guards keep this out of shipped builds: the build flag
     * defaults to -1 (absent), AND [BuildConfig.DEBUG] must be true. A weakened PoW in a
     * release build would be a silent regression rather than a visible break, so one guard
     * is not enough.
     *
     * The override lowers BOTH difficulties — leaving the hashcash stage at 20 would still
     * cost ~1M hashes per cycle, which is most of the point of overriding.
     */
    fun applyDebugOverride(params: Params): Params {
        val override = BuildConfig.REGISTRATION_POW_DEBUG_DIFFICULTY
        if (!BuildConfig.DEBUG || override < 0) return params
        return params.copy(
            hashcashDifficulty = minOf(params.hashcashDifficulty, override),
            argon2DifficultyBits = minOf(params.argon2DifficultyBits, override),
        )
    }

    /**
     * Solve the full ladder. BLOCKS for seconds; run off the main thread.
     *
     * Cancel by interrupting the calling thread — [InterruptedException] propagates and no
     * partial state is left behind (the challenge is stateless server-side, so an abandoned
     * solve consumes nothing and needs no cleanup).
     *
     * @param identityKey the raw identity public key being registered. Bound into both
     *   stages so the proof cannot be farmed ahead of time or replayed for another key.
     */
    @Throws(InterruptedException::class)
    fun solve(
        challengeToken: String,
        identityKey: ByteArray,
        params: Params,
        deriver: Argon2idDeriver,
        progress: ProgressSink? = null,
    ): Proof {
        val effective = applyDebugOverride(params)
        val bound = challengeBody(challengeToken) + identityKey

        val expectedHashcash = expectedAttempts(effective.hashcashDifficulty)
        val expectedArgon = expectedAttempts(effective.argon2DifficultyBits) * ARGON_EVAL_HASH_EQUIVALENT
        val expectedTotal = expectedHashcash + expectedArgon

        var hashes = 0L
        val hashcashNonce = solveHashcash(bound, effective.hashcashDifficulty) { done ->
            hashes = done
            progress?.onProgress(
                Progress(Stage.HASHCASH, done.toDouble(), expectedTotal, done, 0L),
            )
        }

        var evaluations = 0L
        val argonNonce = solveArgon2id(bound, effective, deriver) { done ->
            evaluations = done
            progress?.onProgress(
                Progress(
                    stage = Stage.ARGON2ID,
                    workDone = expectedHashcash.coerceAtMost(hashes.toDouble()) +
                        done * ARGON_EVAL_HASH_EQUIVALENT,
                    workExpected = expectedTotal,
                    hashcashHashes = hashes,
                    argonEvaluations = done,
                ),
            )
        }
        check(evaluations >= 0)

        return Proof(challengeToken, hashcashNonce, argonNonce)
    }

    /** Expected attempts for a leading-zero-bits puzzle = 2^bits. Guards absurd inputs. */
    internal fun expectedAttempts(difficultyBits: Int): Double {
        if (difficultyBits <= 0) return 1.0
        if (difficultyBits >= 63) return Math.pow(2.0, 63.0)
        return Math.pow(2.0, difficultyBits.toDouble())
    }

    private const val PROGRESS_STRIDE = 0x1FFFL

    /**
     * SHA-256 hashcash. Same search as [LemonDropCreate.solveProofOfWork] — reused rather
     * than reimplemented — with a progress tap added, since registration is a foreground
     * wait the user watches and a drop deposit is not.
     */
    @Throws(InterruptedException::class)
    internal fun solveHashcash(
        bound: ByteArray,
        difficulty: Int,
        onProgress: (Long) -> Unit,
    ): ByteArray {
        val nonce = ByteArray(HASHCASH_NONCE_BYTES)
        val sha = MessageDigest.getInstance("SHA-256")
        val preimage = ByteArray(bound.size + nonce.size)
        bound.copyInto(preimage, 0)
        val digest = ByteArray(32)
        var i = 0L
        while (true) {
            nonce.copyInto(preimage, bound.size)
            sha.reset()
            sha.update(preimage)
            sha.digest(digest, 0, 32)
            if (LemonDropCreate.hasLeadingZeroBits(digest, difficulty)) {
                onProgress(i)
                return nonce
            }
            incrementNonce(nonce)
            if ((++i and PROGRESS_STRIDE) == 0L) {
                // Interrupt check and progress report share one stride: both want
                // "occasionally, not every hash", and pairing them keeps the inner loop
                // to a single branch.
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("registration proof-of-work cancelled")
                }
                onProgress(i)
            }
        }
    }

    /**
     * Argon2id leading-zero puzzle. Each attempt is a full memory-hard evaluation, so unlike
     * the hashcash loop there is no stride — interrupt and progress are checked every
     * iteration because every iteration is expensive.
     */
    @Throws(InterruptedException::class)
    internal fun solveArgon2id(
        bound: ByteArray,
        params: Params,
        deriver: Argon2idDeriver,
        onProgress: (Long) -> Unit,
    ): ByteArray {
        val salt = ByteArray(ARGON_NONCE_BYTES)
        var attempts = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("registration proof-of-work cancelled")
            }
            // password = the bound input, salt = the searched nonce. This ordering mirrors
            // `argon2.IDKey(bound, proof.ArgonNonce, ...)` in regpow.Verify exactly; swapping
            // the two yields a proof the relay silently rejects.
            val out = deriver.derive(bound, salt, params.argon2TimeCost, params.argon2MemoryKiB)
            attempts++
            onProgress(attempts)
            if (LemonDropCreate.hasLeadingZeroBits(out, params.argon2DifficultyBits)) {
                return salt.copyOf()
            }
            incrementNonce(salt)
        }
    }

    /** Big-endian counter increment over the whole array. Mirrors the shipped solver's
     *  `incrementCounter`, kept local so this file does not depend on its visibility. */
    internal fun incrementNonce(nonce: ByteArray) {
        for (idx in nonce.indices.reversed()) {
            val next = ((nonce[idx].toInt() and 0xFF) + 1) and 0xFF
            nonce[idx] = next.toByte()
            if (next != 0) return
        }
    }
}
