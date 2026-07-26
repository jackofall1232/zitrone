// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TRI-STATE READ (0.9.2 Unit W-A follow-up).
 *
 * WHY THIS SUITE EXISTS: every fail-open defect in this unit traces to `File.exists()` collapsing
 * three states into two and defaulting the collapse to ABSENT. [Residence] names the third state so
 * a routing input can no longer lose it, and carries THE RULE — only a proven absence may present a
 * fresh install — as a value rather than as a convention each call site re-implements.
 *
 * The rule is pinned here against `bootRoute` itself, not just against the type, because a rule that
 * holds in the type and not in the router is a rule in name only.
 */
class ResidenceTest {

    /**
     * MUTATION UNIQUELY CAUGHT: reordering [Residence.classify] to test proven-absence first.
     * A disk that changes mid-classification must degrade toward Present, never toward a
     * fresh-install presentation.
     */
    @Test
    fun `a proven presence outranks a proven absence`() {
        assertEquals(Residence.Present, Residence.classify(present = { true }, provenAbsent = { true }))
    }

    @Test
    fun `proven absence requires the presence probe to be false`() {
        assertEquals(
            Residence.ProvenAbsent,
            Residence.classify(present = { false }, provenAbsent = { true }),
        )
    }

    /**
     * The ordinary indeterminate case: neither proof lands and NOTHING throws, because
     * `Files.notExists` reports an I/O fault by returning false rather than by raising. A null
     * `cause` is therefore the norm here, not a dropped detail.
     *
     * MUTATION UNIQUELY CAUGHT: making the `else` branch return `ProvenAbsent`.
     */
    @Test
    fun `neither proof landing is indeterminate, with no cause`() {
        val r = Residence.classify(present = { false }, provenAbsent = { false })
        assertEquals(Residence.Indeterminate(null), r)
    }

    /**
     * A throw is a disk fact we could not establish, not a failure of the boot. It must be captured,
     * not propagated — and it must not be read as absence.
     *
     * MUTATION UNIQUELY CAUGHT: removing the `catch (t: Throwable)` arm.
     */
    @Test
    fun `a throwing probe yields indeterminate carrying the cause`() {
        val boom = IOException("stat failed")
        val r = Residence.classify(present = { throw boom }, provenAbsent = { true })
        assertTrue(r is Residence.Indeterminate)
        assertSame(boom, (r as Residence.Indeterminate).cause)
        assertFalse(r.mayRouteToOnboarding)
    }

    /**
     * A cancelled boot is not a disk fact. Absorbing the CancellationException here would report
     * "indeterminate" for a coroutine that is being torn down — the same swallow the sweep path
     * deliberately avoids.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the `catch (c: CancellationException) { throw c }` arm.
     */
    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `cancellation propagates and is never reported as a disk state`() {
        Residence.classify(
            present = { throw kotlinx.coroutines.CancellationException("boot cancelled") },
            provenAbsent = { true },
        )
    }

    @Test
    fun `only proven absence may route to onboarding`() {
        assertTrue(Residence.ProvenAbsent.mayRouteToOnboarding)
        assertFalse(Residence.Present.mayRouteToOnboarding)
        assertFalse(Residence.Indeterminate(null).mayRouteToOnboarding)

        assertFalse(Residence.ProvenAbsent.treatAsPresent)
        assertTrue(Residence.Present.treatAsPresent)
        assertTrue(Residence.Indeterminate(null).treatAsPresent)
    }

    /**
     * THE RULE, PINNED AGAINST THE ROUTER — for a NON-LEGACY image, `bootRoute` reaches ONBOARDING
     * only from a proven absence. The legacy arm is the one deliberate exception (a legacy image is
     * unusable and onboarding's `create()` retires it) and is excluded here rather than hidden.
     *
     * MUTATION UNIQUELY CAUGHT: any reordering that lets the indeterminate reading reach ONBOARDING.
     */
    @Test
    fun `no non-legacy indeterminate reading can reach onboarding`() {
        val readings = listOf(Residence.Present, Residence.ProvenAbsent, Residence.Indeterminate(null))
        for (r in readings) {
            for (confirmed in listOf(false, true)) {
                for (hold in listOf(false, true)) {
                    val route = bootRoute(
                        serverDeleteConfirmed = confirmed,
                        vaultImagePresent = r is Residence.Present,
                        durabilityHold = hold,
                        vaultProvenAbsent = r.mayRouteToOnboarding,
                        legacyImage = false,
                    )
                    if (route == BootRoute.ONBOARDING) {
                        assertEquals(
                            "ONBOARDING reached from a reading that is not ProvenAbsent",
                            Residence.ProvenAbsent,
                            r,
                        )
                    }
                }
            }
        }
    }

    /**
     * THE RULE HOLDS EVEN WITH THE LEGACY FLAG SET, and this test is why the router changed.
     *
     * Written first as "indeterminate + legacy falls through to LOCKED", it FAILED — `bootRoute`'s
     * legacy arm did not consult `vaultImagePresent` at all, so `legacyImage = true` returned
     * ONBOARDING irrespective of any absence proof. The invariant was real but lived one layer out,
     * in [deriveBootDecision]'s probe guard; the router itself would have onboarded over an image it
     * could not stat, if any caller ever set the flag that way. The arm now carries
     * `&& vaultImagePresent`.
     *
     * MUTATION UNIQUELY CAUGHT: dropping `&& vaultImagePresent` from the legacy arm.
     */
    @Test
    fun `an indeterminate reading cannot onboard even with the legacy flag set`() {
        val indeterminate: Residence = Residence.Indeterminate(null)
        assertEquals(
            BootRoute.LOCKED,
            bootRoute(
                serverDeleteConfirmed = false,
                vaultImagePresent = indeterminate is Residence.Present,
                durabilityHold = false,
                vaultProvenAbsent = indeterminate.mayRouteToOnboarding,
                legacyImage = true,
            ),
        )
    }

    /**
     * The other half of the guard, at the layer that owns it: an indeterminate reading must never
     * even RUN the ~1 MiB legacy probe, because a `true` from it is a fresh-install presentation.
     *
     * MUTATION UNIQUELY CAUGHT: dropping `imagePresent &&` from the probe guard in
     * [deriveBootDecision].
     */
    @Test
    fun `an indeterminate reading never runs the legacy probe`() {
        val indeterminate: Residence = Residence.Indeterminate(null)
        var probed = false
        val d = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = indeterminate is Residence.Present,
            durabilityHold = false,
            vaultProvenAbsent = indeterminate.mayRouteToOnboarding,
            isLegacyImage = { probed = true; true },
        )
        assertFalse("the legacy probe must not run over an image that cannot be stat'd", probed)
        assertEquals(BootRoute.LOCKED, d.route)
    }
}
