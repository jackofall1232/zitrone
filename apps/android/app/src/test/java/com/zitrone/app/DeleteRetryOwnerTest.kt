// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DELETE-RETRY WIRING (0.9.2 Unit W-A follow-up).
 *
 * WHY THIS SUITE EXISTS: `onRetryDestroy` was the last consumer judging delete success by
 * `!hasVault() && !serverDeleteConfirmed()` — a second, weaker routing authority that could report
 * SUCCESS over surviving DEK/temp residue and route to first-run onboarding. Routing it through the
 * single derivation fixed that, and the fix had NO direct test: `BootRouteTest` and
 * `DeriveBootDecisionTest` pin the decision and its inputs, but neither can catch THIS CALL SITE
 * reverting to the old predicate — the truth table stays green either way.
 *
 * That gap was raised by two independent lenses in the follow-up round, and defended once with the
 * argument that any such test would duplicate `bootRoute` coverage. It does not: what is unique here
 * is the WIRING — that destroy runs first, that the verdict is the derived one, and that only
 * ONBOARDING counts as success.
 */
class DeleteRetryOwnerTest {

    private fun decision(route: BootRoute) =
        BootDecision(present = route != BootRoute.ONBOARDING, legacy = false, route = route)

    /**
     * A decision taken over pre-destroy disk is meaningless — the retry exists precisely to change
     * what disk says.
     *
     * MUTATION UNIQUELY CAUGHT: swapping the two calls in [runDeleteRetry].
     */
    @Test
    fun `destroy runs before the derivation`() = runTest {
        val order = mutableListOf<String>()
        runDeleteRetry(
            destroy = { order += "destroy" },
            derive = { order += "derive"; decision(BootRoute.ONBOARDING) },
        )
        assertEquals(listOf("destroy", "derive"), order)
    }

    /**
     * ONBOARDING is the only success. LOCKED and DELETE_INCOMPLETE both leave the caller reporting
     * failure — including LOCKED raised by a residue sweep hold over an otherwise clean disk, which
     * is the known stale-hold strand (tracked, 0.9.3 derivation fold).
     *
     * MUTATION UNIQUELY CAUGHT: widening the success check to `route != DELETE_INCOMPLETE`.
     */
    @Test
    fun `only ONBOARDING is success`() = runTest {
        assertTrue(runDeleteRetry(destroy = {}, derive = { decision(BootRoute.ONBOARDING) }))
        assertFalse(runDeleteRetry(destroy = {}, derive = { decision(BootRoute.LOCKED) }))
        assertFalse(runDeleteRetry(destroy = {}, derive = { decision(BootRoute.DELETE_INCOMPLETE) }))
    }

    /**
     * THE DIVERGING ROW — the one the old and new predicates answer differently, and the reason this
     * whole change exists (proposed by the follow-up round's second lens, Grok).
     *
     * Disk after the retry: `vault.bin` absent and the confirmed marker absent, so the OLD predicate
     * `!hasVault() && !serverDeleteConfirmed()` reports SUCCESS and routes to first-run onboarding —
     * while a `vault.dek` or `vault.bin.tmp` survives, and a temp stages a COMPLETE outer image.
     * The derivation withholds ONBOARDING because absence is not PROVEN.
     *
     * MUTATION UNIQUELY CAUGHT: reverting the call site to the `!hasVault() && !confirmed` predicate.
     */
    @Test
    fun `residue surviving a retry is failure, where the old predicate said success`() = runTest {
        val binPresent = false          // hasVault() — keys on vault.bin ALONE
        val confirmedMarker = false     // serverDeleteConfirmed()
        // Typed as the interface, not the subtype: the call site reads a `Residence`, and narrowing
        // it here would make the `is Present` check below a compile-time constant instead of the
        // runtime discrimination production performs.
        val residence: Residence = Residence.Indeterminate(null) // dek/temp survives: not proven absent

        val oldPredicateSaidSuccess = !binPresent && !confirmedMarker
        assertTrue("the old predicate must say SUCCESS here, or this row proves nothing", oldPredicateSaidSuccess)

        val succeeded = runDeleteRetry(
            destroy = {},
            derive = {
                deriveBootDecision(
                    serverDeleteConfirmed = confirmedMarker,
                    imagePresent = residence is Residence.Present,
                    residueSweepHold = false,
                    vaultProvenAbsent = residence.mayRouteToOnboarding,
                    isLegacyImage = { false },
                )
            },
        )
        assertFalse("proven absence is required before presenting a fresh install", succeeded)
    }

    /**
     * The clean row still succeeds — a stronger criterion that never says yes is not a fix, it is a
     * brick. Proven absence, no hold, no confirmed marker → onboarding.
     */
    @Test
    fun `a proven-clean retry succeeds`() = runTest {
        val succeeded = runDeleteRetry(
            destroy = {},
            derive = {
                deriveBootDecision(
                    serverDeleteConfirmed = false,
                    imagePresent = false,
                    residueSweepHold = false,
                    vaultProvenAbsent = Residence.ProvenAbsent.mayRouteToOnboarding,
                    isLegacyImage = { false },
                )
            },
        )
        assertTrue(succeeded)
    }

    /**
     * A destroy that throws is the CALLER's to contain (production wraps it in `runCatching`), and
     * this owner must not silently swallow it — a swallowed throw here would derive over a disk the
     * destroy never touched and could report success.
     *
     * MUTATION UNIQUELY CAUGHT: wrapping [runDeleteRetry]'s `destroy()` call in `runCatching`.
     */
    @Test
    fun `a throwing destroy propagates and never reaches the derivation`() = runTest {
        var derived = false
        var threw = false
        try {
            runDeleteRetry(
                destroy = { throw IllegalStateException("destroy blew up") },
                derive = { derived = true; decision(BootRoute.ONBOARDING) },
            )
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse("a failed destroy must not produce a routing verdict", derived)
    }
}
