// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goterl.lazysodium.SodiumAndroid
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.9.4 registration proof-of-work — ON-DEVICE Argon2id CALIBRATION.
 *
 * This is a MEASUREMENT HARNESS, not a pass/fail test. It asserts nothing about
 * timing (a timing assertion would be flaky on exactly the throttled hardware we
 * care about). It prints a table to logcat and always passes; the numbers are the
 * deliverable.
 *
 * WHY IT EXISTS: the PoW difficulty constant must be floored on the WORST realistic
 * device, not a flagship — a TCL Revvl 6x IN BATTERY SAVER. Registration typically
 * runs moments after install, while the device is still busy unpacking, so the honest
 * floor is a throttled budget SoC under load. That number cannot be measured on the
 * CX33 build box; it has to come from the phone.
 *
 * HOW TO RUN (device attached over adb, from apps/android):
 *
 *   1. On the phone: Settings > Battery > Battery Saver = ON. Unplug it (battery
 *      saver is suppressed while charging on many OEM skins, including TCL's).
 *   2. ./gradlew connectedDebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=\
 *   com.zitrone.app.RegistrationPowCalibrationTest
 *   3. adb logcat -d -s PowCalib:I
 *
 * The harness records device model, power-save state, charging state and thermal
 * status in its own output, so a result carries the evidence of the conditions it
 * was taken under. A run that says POWER_SAVE=false is NOT the floor — rerun it.
 *
 * It exercises the SAME production primitive the client and relay agree on:
 * libsodium crypto_pwhash / ALG_ARGON2ID13 with p=1 (libsodium fixes parallelism
 * internally). The relay verifies with golang.org/x/crypto/argon2 IDKey at the same
 * (memory, iterations, p=1) and gets byte-identical output — both are RFC 9106.
 *
 * NOTE ON PARAMS: these sweep points are deliberately NOT the vault's derivation
 * params (64 MiB / t=3). Vault derivation is tuned for maximum brute-force
 * resistance on a stolen device; PoW is tuned for a few seconds of honest client
 * cost. They must not be "harmonised" into one constant.
 */
@RunWith(AndroidJUnit4::class)
class RegistrationPowCalibrationTest {

    private val tag = "PowCalib"

    @Test
    fun sweepArgon2idCostOnThisDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val power = ctx.getSystemService(PowerManager::class.java)
        val battery = ctx.getSystemService(BatteryManager::class.java)

        val charging = battery?.isCharging ?: false
        val saver = power?.isPowerSaveMode ?: false
        val thermal =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                power?.currentThermalStatus?.toString() ?: "?"
            } else {
                "n/a<Q"
            }

        line("================ ZITRONE 0.9.4 PoW Argon2id CALIBRATION ================")
        line("DEVICE       = ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("ANDROID      = ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("ABI          = ${Build.SUPPORTED_ABIS.joinToString()}")
        line("CORES        = ${Runtime.getRuntime().availableProcessors()}")
        line("POWER_SAVE   = $saver   <-- MUST be true for the floor measurement")
        line("CHARGING     = $charging <-- should be false; charging suppresses saver")
        line("THERMAL      = $thermal")
        if (!saver) {
            line("!! WARNING: battery saver is OFF. This run is NOT the floor. Rerun it.")
        }
        line("Argon2id p=1 (libsodium fixes parallelism), 32-byte output, 16-byte salt")
        line("-----------------------------------------------------------------------")
        line(String.format("%-9s %-6s %12s %14s", "mem", "iters", "ms/eval", "evals/sec"))

        val sodium = SodiumAndroid()
        val password = "identity-key-bound-preimage-example".toByteArray()
        val salt = ByteArray(16) { it.toByte() }

        // Warm up: first call pays JNA binding + allocator startup.
        runCatching { argon2idRaw(sodium, password, salt, 8, 1) }

        val mems = intArrayOf(8, 16, 32, 64, 128)
        val iters = intArrayOf(1, 2, 3)

        for (m in mems) {
            for (t in iters) {
                val reps = if (m <= 16) 5 else 3
                val result = runCatching {
                    // Discard one evaluation at each new grid point so a cold page
                    // mapping for a larger arena is not billed to the measurement.
                    argon2idRaw(sodium, password, salt, m, t)
                    val start = System.nanoTime()
                    repeat(reps) { argon2idRaw(sodium, password, salt, m, t) }
                    (System.nanoTime() - start) / 1e9 / reps
                }
                result.fold(
                    onSuccess = { per ->
                        line(
                            String.format(
                                "%-9s %-6d %12.1f %14.2f",
                                "${m}MiB", t, per * 1000, 1.0 / per,
                            ),
                        )
                    },
                    onFailure = { e ->
                        // A budget device can legitimately fail to allocate the larger
                        // arenas. That is itself a calibration result: it rules the
                        // parameter out, so record it rather than crashing the sweep.
                        line(String.format("%-9s %-6d %12s   (%s)", "${m}MiB", t, "FAILED", e.javaClass.simpleName))
                    },
                )
            }
        }

        line("-----------------------------------------------------------------------")
        sweepSha256()
        line("=======================================================================")
        line("Client solve cost = (expected Argon2id evals) x (ms/eval) at chosen params.")
        line("Relay verify cost = exactly ONE eval at the same params.")
    }

    /**
     * Argon2id at an ARBITRARY (memory, iterations), which the production
     * [LibsodiumVaultOps.argon2idDeriveKey] deliberately does not expose — it hard-codes
     * the vault's params. Calibration needs the whole grid, so it calls crypto_pwhash
     * through the same libsodium binding directly.
     */
    private fun argon2idRaw(
        sodium: SodiumAndroid,
        password: ByteArray,
        salt: ByteArray,
        memMiB: Int,
        iterations: Int,
    ) {
        val out = ByteArray(32)
        val rc = sodium.crypto_pwhash(
            out,
            out.size.toLong(),
            password,
            password.size.toLong(),
            salt,
            iterations.toLong(),
            com.sun.jna.NativeLong(memMiB.toLong() * 1024L * 1024L),
            com.goterl.lazysodium.interfaces.PwHash.Alg.PWHASH_ALG_ARGON2ID13.value,
        )
        check(rc == 0) { "crypto_pwhash rc=$rc at ${memMiB}MiB/t=$iterations (out of memory?)" }
    }

    /**
     * SHA-256 throughput for the CHEAP PRE-STAGE rung of the ladder. The shipped
     * lemon-drop hashcash uses difficulty 20 (~1.05M expected hashes) and is tolerated
     * on this codebase, so this measures what that already-accepted cost is worth in
     * seconds on THIS device.
     */
    private fun sweepSha256() {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(40)
        val n = 200_000
        val start = System.nanoTime()
        for (i in 0 until n) {
            buf[39] = (buf[39] + 1).toByte()
            md.reset()
            md.update(buf)
            md.digest()
        }
        val secs = (System.nanoTime() - start) / 1e9
        val rate = n / secs
        line(String.format("SHA-256: %.2f MH/s single-thread", rate / 1e6))
        line(String.format("  difficulty 20 (~1.05M hashes, the SHIPPED anchor) = %.1f s", 1_048_576.0 / rate))
        line(String.format("  difficulty 22 (~4.2M hashes)                      = %.1f s", 4_194_304.0 / rate))
    }

    private fun line(s: String) {
        Log.i(tag, s)
        println("[$tag] $s")
    }
}
