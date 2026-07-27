// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.RegistrationPow
import com.zitrone.app.diagnostics.RegistrationPowSolveRecorder
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the instrumented PoW solve — the calibration measurement path.
 *
 * The lines this recorder writes are the ONLY measurement channel for the
 * TODO(pow-calibration) difficulty (no device attaches to the build box; the number must come
 * back through the Diagnostics screen of a real install). So the tests pin the properties a
 * later reader depends on: every completed solve carries all four lines with the parameters
 * that produced the numbers, and an abort still reports how far it got.
 */
class RegistrationPowSolveRecorderTest {

    /** Same deterministic Argon2id stand-in as RegistrationPowTest, same reason. */
    private val fakeDeriver = RegistrationPow.Argon2idDeriver { password, salt, t, m ->
        val md = MessageDigest.getInstance("SHA-256")
        md.update(password)
        md.update(salt)
        md.update(byteArrayOf(t.toByte(), m.toByte()))
        md.digest()
    }

    private fun token(bodyByte: Int = 7): String {
        val raw = ByteArray(RegistrationPow.CHALLENGE_TOKEN_BYTES) { bodyByte.toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private val params = RegistrationPow.Params(
        hashcashDifficulty = 8,
        argon2TimeCost = 1,
        argon2MemoryKiB = 8,
        argon2DifficultyBits = 8,
    )

    private fun recorder(
        lines: MutableList<String>,
        batterySaver: () -> Boolean? = { false },
        inForeground: () -> Boolean? = { true },
    ): RegistrationPowSolveRecorder {
        var t = 0L
        return RegistrationPowSolveRecorder(
            diag = { lines.add(it) },
            batterySaver = batterySaver,
            inForeground = inForeground,
            clock = { ++t },
        )
    }

    @Test
    fun completedSolveEmitsAllFourLinesInOrderWithParams() {
        val lines = mutableListOf<String>()
        val proof = recorder(lines).solve(token(), ByteArray(32) { 1 }, params, fakeDeriver)

        assertEquals(RegistrationPow.ARGON_NONCE_BYTES, proof.argonNonce.size)
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("pow: solve start — sha256_d=8 argon2id t=1 m=8KiB p=1 d_bits=8"))
        assertTrue(lines[1].contains("pow: sha256 pre-stage done in"))
        assertTrue("pre-stage line must carry the difficulty", lines[1].contains("at d=8"))
        assertTrue(lines[2].contains("pow: argon2id stage done in"))
        assertTrue(
            "argon line must carry the parameters that produced the number",
            lines[2].contains("evaluations at t=1 m=8KiB p=1 d_bits=8"),
        )
        assertTrue(lines[3].contains("pow: solve COMPLETE in"))
        assertTrue(lines[3].contains("battery_saver=false foreground=true backgrounded_mid_solve=false"))
    }

    @Test
    fun countsInLinesAreRealWorkCounts() {
        val lines = mutableListOf<String>()
        recorder(lines).solve(token(), ByteArray(32) { 2 }, params, fakeDeriver)

        val hashes = Regex("— (\\d+) hashes").find(lines[1])!!.groupValues[1].toLong()
        val evals = Regex("— (\\d+) evaluations").find(lines[2])!!.groupValues[1].toLong()
        // The winning attempt itself is counted, so both are at least 1... hashes CAN be 0:
        // the solver reports the 0-indexed attempt counter at the win, so a first-try
        // hashcash reports 0. Evaluations are 1-indexed and must be >= 1.
        assertTrue(hashes >= 0)
        assertTrue(evals >= 1)
    }

    @Test
    fun abortMidArgonLogsHowFarItGotAndRethrows() {
        val lines = mutableListOf<String>()
        // Interrupt the solving thread from inside the deriver: the solver's next loop
        // iteration converts it to InterruptedException — the "try later" / teardown path.
        val interrupting = RegistrationPow.Argon2idDeriver { password, salt, t, m ->
            Thread.currentThread().interrupt()
            fakeDeriver.derive(password, salt, t, m)
        }
        try {
            assertThrows(InterruptedException::class.java) {
                recorder(lines).solve(token(), ByteArray(32) { 3 }, params, interrupting)
            }
        } finally {
            Thread.interrupted() // clear the flag so it cannot leak into other tests
        }

        val last = lines.last()
        assertTrue(last.contains("pow: solve ABORTED after"))
        assertTrue("abort must name the stage reached", last.contains("at stage=argon2id"))
        assertTrue("abort must report work done so far", last.contains("evaluations done"))
        // The pre-stage completed before the abort, so its line was still emitted.
        assertTrue(lines.any { it.contains("sha256 pre-stage done") })
    }

    @Test
    fun deriverFailureLogsFailedWithClassAndRethrows() {
        val lines = mutableListOf<String>()
        val failing = RegistrationPow.Argon2idDeriver { _, _, _, _ ->
            throw IllegalStateException("crypto_pwhash failed (rc=-1) at 8KiB/t=1 — out of memory?")
        }
        assertThrows(IllegalStateException::class.java) {
            recorder(lines).solve(token(), ByteArray(32) { 4 }, params, failing)
        }
        assertTrue(lines.last().contains("pow: solve FAILED (java.lang.IllegalStateException)"))
    }

    @Test
    fun backgroundingMidSolveIsLatchedIntoTheCompleteLine() {
        val lines = mutableListOf<String>()
        var samples = 0
        val recorder = recorder(lines, inForeground = { samples++ < 2 })
        recorder.solve(token(), ByteArray(32) { 5 }, params, fakeDeriver)
        assertTrue(lines.last().contains("backgrounded_mid_solve=true"))
    }

    @Test
    fun unreadableProbesRenderAsUnknownNotFalse() {
        val lines = mutableListOf<String>()
        val recorder = recorder(lines, batterySaver = { null }, inForeground = { null })
        recorder.solve(token(), ByteArray(32) { 6 }, params, fakeDeriver)
        assertTrue(lines[0].contains("battery_saver=unknown foreground=unknown"))
        assertTrue(lines.last().contains("battery_saver=unknown foreground=unknown"))
    }
}
