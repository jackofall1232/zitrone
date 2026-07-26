// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.burn.BurnPhase
import com.zitrone.app.burn.BurnStep
import com.zitrone.app.burn.CleanupCompletion
import com.zitrone.app.burn.Durability
import com.zitrone.app.burn.completeInterruptedCleanup
import com.zitrone.app.burn.runBurnPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BURN PLAN (0.9.2 Unit W-B round 4) — the table that replaced the burn's statement sequence,
 * and the boot-side completion that closes the round-4 BLOCKING defect.
 *
 * These are the two properties the fix rests on, so they are pinned rather than described:
 *  1. **Phase order is a SAFETY property**, not presentation. Non-cryptographic cleanups must run
 *     before the image (a crash there leaves an intact vault with cleared caches — innocuous), and
 *     Keystore material must run after it (removing key material while an image lives would make
 *     that image permanently unopenable, a worse oracle than the residue it replaces).
 *  2. **Boot completes an interrupted burn from the RESIDUE ITSELF**, with no durable marker — a
 *     marker written before the first mutation would survive on a device with a fully intact vault
 *     and prove the duress passphrase was entered.
 */
class BurnPlanTest {

    private fun step(
        name: String,
        phase: BurnPhase,
        verify: () -> Boolean = { true },
        action: () -> Unit = {},
    ) = BurnStep(
        name = name,
        phase = phase,
        durability = Durability.KeystoreTransactional,
        verify = verify,
        action = action,
    )

    /**
     * THE ORDERING INVARIANT. Declaration order must not decide execution order — the phase does.
     *
     * MUTATION UNIQUELY CAUGHT: iterating `steps` directly instead of grouping by phase, which would
     * silently honour whatever order someone happened to list the steps in.
     */
    @Test
    fun `phases run before-image then image then after-image regardless of declaration order`() {
        val order = mutableListOf<String>()
        runBurnPlan(
            listOf(
                step("device-key", BurnPhase.AFTER_IMAGE) { order += "after" },
                step("cache", BurnPhase.BEFORE_IMAGE) { order += "before" },
                step("image", BurnPhase.IMAGE) { order += "image" },
            ),
        )
        assertEquals(listOf("before", "image", "after"), order)
    }

    /** ANTI-VACUITY: an empty plan would report a successful burn having wiped nothing. */
    @Test(expected = IllegalArgumentException::class)
    fun `an empty plan is rejected rather than reported as a successful burn`() {
        runBurnPlan(emptyList())
    }

    /** A throwing step aborts the burn — the caller keeps the durability hold raised. */
    @Test
    fun `a failing step propagates and later phases do not run`() {
        val ran = mutableListOf<String>()
        val thrown = runCatching {
            runBurnPlan(
                listOf(
                    step("cache", BurnPhase.BEFORE_IMAGE) { throw IllegalStateException("io") },
                    step("image", BurnPhase.IMAGE) { ran += "image" },
                ),
            )
        }.isFailure
        assertTrue("the failure must reach the caller", thrown)
        assertEquals("nothing after the failure may run", emptyList<String>(), ran)
    }

    // ── boot-side completion ─────────────────────────────────────────────────────────────────

    /**
     * THE ROUND-4 DEFECT, AS A TEST. Image gone, a later cleanup's postcondition false: boot must
     * recognise the residue WITHOUT any marker and finish the job.
     */
    @Test
    fun `boot completes a cleanup left outstanding after the image was destroyed`() {
        var cacheCleaned = false
        val steps = listOf(
            step("cache", BurnPhase.BEFORE_IMAGE, verify = { cacheCleaned }) { cacheCleaned = true },
            step("image", BurnPhase.IMAGE, verify = { true }),
        )

        val result = completeInterruptedCleanup(steps, imageProvenAbsent = true)

        assertEquals(CleanupCompletion.COMPLETED, result)
        assertTrue("the outstanding cleanup must actually have been run", cacheCleaned)
    }

    /**
     * A retry that cannot prove itself must report INCOMPLETE, which the caller turns into a raised
     * durability hold — boot then withholds the fresh-install presentation exactly as the in-RAM hold
     * would have, and with no durable artifact recording that a burn happened.
     *
     * MUTATION UNIQUELY CAUGHT: trusting `action()` not to throw instead of RE-VERIFYING after it.
     * An action that threw and one that silently did nothing are indistinguishable to the caller.
     */
    @Test
    fun `a retry that cannot prove itself reports INCOMPLETE`() {
        val steps = listOf(
            step("cache", BurnPhase.BEFORE_IMAGE, verify = { false }) { /* never succeeds */ },
        )

        assertEquals(
            CleanupCompletion.INCOMPLETE,
            completeInterruptedCleanup(steps, imageProvenAbsent = true),
        )
    }

    /** A throwing retry is a failed retry, not a crash out of boot reconciliation. */
    @Test
    fun `a throwing retry is contained and reported as INCOMPLETE`() {
        val steps = listOf(
            step("cache", BurnPhase.BEFORE_IMAGE, verify = { false }) { throw IllegalStateException("io") },
        )

        assertEquals(
            CleanupCompletion.INCOMPLETE,
            completeInterruptedCleanup(steps, imageProvenAbsent = true),
        )
    }

    /**
     * **THE GUARD THAT MATTERS MOST HERE.** This function DELETES, so it must never run while an
     * image is present — an indeterminate stat read as "absent" would run cleanups against a live
     * vault. A present image means any unmet postcondition is ordinary in-use state, not burn residue.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the `imageProvenAbsent` guard, or deriving it from
     * `File.exists()` rather than a proven absence.
     */
    @Test
    fun `nothing is deleted while the image is present`() {
        var ran = false
        val steps = listOf(step("cache", BurnPhase.BEFORE_IMAGE, verify = { false }) { ran = true })

        val result = completeInterruptedCleanup(steps, imageProvenAbsent = false)

        assertEquals(CleanupCompletion.NOTHING_TO_DO, result)
        assertTrue("a live vault must never have burn cleanups run against it", !ran)
    }

    /** A clean device does no work and reports so — boot must not raise a hold over nothing. */
    @Test
    fun `a device with every postcondition already met does nothing`() {
        var ran = false
        val steps = listOf(step("cache", BurnPhase.BEFORE_IMAGE, verify = { true }) { ran = true })

        assertEquals(
            CleanupCompletion.NOTHING_TO_DO,
            completeInterruptedCleanup(steps, imageProvenAbsent = true),
        )
        assertTrue("a clean device must not be mutated", !ran)
    }

    /**
     * IMAGE-phase steps are skipped at boot: the image is already proven absent, so re-running an
     * obliterate against nothing is at best a no-op and at worst a new failure mode.
     */
    @Test
    fun `the image step is never re-run at boot`() {
        var obliterated = false
        val steps = listOf(step("image", BurnPhase.IMAGE, verify = { false }) { obliterated = true })

        assertEquals(
            CleanupCompletion.NOTHING_TO_DO,
            completeInterruptedCleanup(steps, imageProvenAbsent = true),
        )
        assertTrue("boot must not re-run the obliterate", !obliterated)
    }
}
