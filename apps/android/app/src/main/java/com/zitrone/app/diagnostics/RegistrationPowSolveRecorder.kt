// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.diagnostics

import com.zitrone.app.crypto.RegistrationPow

/**
 * Instrumented front door for the 0.9.4 registration proof-of-work solve — the ONLY way the
 * app should run [RegistrationPow.solve], so every real solve leaves numbers behind.
 *
 * **Why this exists:** the Argon2id difficulty is `TODO(pow-calibration)` — no build box here
 * can attach the floor device (Revvl 6x), so the only measurement available is a real
 * registration on a real install. Without instrumentation a cut answers "worked" or "hung";
 * with it, ONE registration attempt on the device yields the actual per-stage timings and the
 * conditions that produced them, and calibration needs one build instead of blind iteration.
 * That is why an abort is logged as diligently as a success: a user bailing at 60s is itself
 * a calibration data point, and how far the solve got before the bail is the useful part.
 *
 * **Privacy:** same rule as every other [BootDiagnostics] line — stage names, timings,
 * counts, and the PoW parameters only. Never the challenge token, the identity key, a nonce,
 * or any derived bytes. The parameters are logged (not just the timing) so a later reader of
 * a shared diagnostics file knows exactly what produced the number.
 *
 * All environment probes are injected as nullable suppliers so this class stays pure Kotlin
 * (host-testable, like the solver itself); a probe returning null renders as `unknown` rather
 * than being skipped, so the reader can tell "not readable on this device" from "false".
 * [inForeground] is sampled on every progress emission (up to ~one sample per 8192 hashes) —
 * it must be a cheap field read, not an IPC.
 */
class RegistrationPowSolveRecorder(
    private val diag: (String) -> Unit,
    private val batterySaver: () -> Boolean?,
    private val inForeground: () -> Boolean?,
    private val clock: () -> Long,
) {

    /**
     * Run [RegistrationPow.solve] with full stage instrumentation. BLOCKS like the solver
     * itself — call off the main thread; cancellation is thread interruption, same contract.
     *
     * Call this immediately after the challenge fetch returns: the total in the COMPLETE
     * line is measured from construction-of-this-call to proof-in-hand, i.e. "challenge
     * received → proof ready". The register POST that submits it is the very next diag line
     * on the boot path, so the file still shows the full challenge→submitted wall time.
     *
     * Emits on every outcome:
     *  - `pow: solve start` — the parameters ACTUALLY used (post debug-override) + conditions
     *  - `pow: sha256 pre-stage done` — duration, hash count, difficulty
     *  - `pow: argon2id stage done` — duration, evaluation count, t/m/p/D
     *  - `pow: solve COMPLETE` — total wall time + end-of-solve conditions
     *  - `pow: solve ABORTED`/`FAILED` — stage reached, work done so far, elapsed, conditions
     *
     * [uiProgress] receives every raw solver emission (same thread, same cadence) so the
     * progress screen can render the fraction without a second tap into the solver — this
     * recorder stays the ONLY front door. Keep the sink as cheap as the recorder's own
     * bookkeeping: it runs on the solving thread, up to ~one call per 8192 hashes.
     */
    @Throws(InterruptedException::class)
    fun solve(
        challengeToken: String,
        identityKey: ByteArray,
        params: RegistrationPow.Params,
        deriver: RegistrationPow.Argon2idDeriver,
        uiProgress: RegistrationPow.ProgressSink? = null,
    ): RegistrationPow.Proof {
        // Log the EFFECTIVE parameters — a debug build with the difficulty override set must
        // not produce lines that read like a real measurement at full difficulty.
        val effective = RegistrationPow.applyDebugOverride(params)
        val startedAt = clock()
        diag(
            "pow: solve start — sha256_d=${effective.hashcashDifficulty} " +
                "argon2id ${argonParams(effective)} " +
                "battery_saver=${tri(batterySaver())} foreground=${tri(inForeground())}",
        )

        // Progress bookkeeping. lastHashcashSeenAt tracks the most recent HASHCASH emission;
        // the solver emits once more at the winning hash, so at the stage transition this IS
        // the pre-stage end time (the first ARGON2ID emission lands one whole evaluation
        // later and would overstate the pre-stage by ~one derive).
        var lastStage = RegistrationPow.Stage.HASHCASH
        var lastHashcashSeenAt = startedAt
        var hashcashHashes = 0L
        var argonEvaluations = 0L
        var sha256LineEmitted = false
        var wentBackground = false

        val sink = RegistrationPow.ProgressSink { p ->
            if (inForeground() == false) wentBackground = true
            when (p.stage) {
                RegistrationPow.Stage.HASHCASH -> {
                    lastHashcashSeenAt = clock()
                    hashcashHashes = p.hashcashHashes
                }
                RegistrationPow.Stage.ARGON2ID -> {
                    hashcashHashes = p.hashcashHashes
                    argonEvaluations = p.argonEvaluations
                    if (!sha256LineEmitted) {
                        sha256LineEmitted = true
                        diag(
                            "pow: sha256 pre-stage done in ${lastHashcashSeenAt - startedAt}ms — " +
                                "$hashcashHashes hashes at d=${effective.hashcashDifficulty}",
                        )
                    }
                }
            }
            lastStage = p.stage
            uiProgress?.onProgress(p)
        }

        val proof = try {
            RegistrationPow.solve(challengeToken, identityKey, params, deriver, sink)
        } catch (t: Throwable) {
            // An abort is a DATA POINT, not just a failure: log how far it got first, then
            // rethrow untouched. InterruptedException is the user/cancellation path
            // ("try later" at the 60s prompt, teardown); anything else is a real failure
            // (e.g. the deriver refusing the memory allocation) and is labelled as such.
            val verb = if (t is InterruptedException) "ABORTED" else "FAILED (${t.javaClass.name})"
            diag(
                "pow: solve $verb after ${clock() - startedAt}ms at stage=${stageName(lastStage)} — " +
                    "$hashcashHashes hashes, $argonEvaluations evaluations done; " +
                    "battery_saver=${tri(batterySaver())} foreground=${tri(inForeground())} " +
                    "backgrounded_mid_solve=$wentBackground",
            )
            throw t
        }

        // Belt-and-braces: solveArgon2id reports every evaluation including the winner, so a
        // completed solve has always emitted the pre-stage line by now. If a future solver
        // change ever coalesces emissions, emit it late rather than never — the file must
        // carry all four lines for every completed solve.
        if (!sha256LineEmitted) {
            diag(
                "pow: sha256 pre-stage done in ${lastHashcashSeenAt - startedAt}ms — " +
                    "$hashcashHashes hashes at d=${effective.hashcashDifficulty}",
            )
        }
        val finishedAt = clock()
        diag(
            "pow: argon2id stage done in ${finishedAt - lastHashcashSeenAt}ms — " +
                "$argonEvaluations evaluations at ${argonParams(effective)}",
        )
        diag(
            "pow: solve COMPLETE in ${finishedAt - startedAt}ms (challenge received → proof ready) — " +
                "battery_saver=${tri(batterySaver())} foreground=${tri(inForeground())} " +
                "backgrounded_mid_solve=$wentBackground",
        )
        return proof
    }

    private fun argonParams(p: RegistrationPow.Params): String =
        "t=${p.argon2TimeCost} m=${p.argon2MemoryKiB}KiB p=1 d_bits=${p.argon2DifficultyBits}"

    private fun stageName(stage: RegistrationPow.Stage): String = when (stage) {
        RegistrationPow.Stage.HASHCASH -> "sha256"
        RegistrationPow.Stage.ARGON2ID -> "argon2id"
    }

    /** Three-valued render: a probe that can't answer must not masquerade as `false`. */
    private fun tri(value: Boolean?): String = value?.toString() ?: "unknown"
}
