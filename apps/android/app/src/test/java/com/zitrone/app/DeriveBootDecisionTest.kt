// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SINGLE BOOT DERIVATION (0.9.2 Unit W-A).
 *
 * WHY THIS SUITE EXISTS: round 1 found the five `bootRoute` inputs copy-pasted across all three
 * routing consumers, and the fix collapsed them into one owner — [deriveBootDecision]. Round 2 then
 * found that the new authoritative layer had NO coverage of its own: `BootRouteTest` pins the
 * decision table, and nothing pinned the derivation that feeds it. A corruption between the disk
 * reads and `bootRoute` would leave every truth-table test green.
 *
 * That is this unit's recurring shape one level up — extract a decision so it CAN be tested, then
 * don't test it. The behaviour under test here is not "what does bootRoute decide" (that is
 * `BootRouteTest`) but "are the right inputs assembled, and is the expensive probe correctly
 * suppressed and fail-closed".
 */
class DeriveBootDecisionTest {

    /**
     * The legacy probe reads and decrypts ~1 MiB. It must not run when a confirmed delete already
     * owns the state — that path routes to DeleteIncomplete regardless of what the probe would say.
     *
     * MUTATION UNIQUELY CAUGHT: dropping `!serverDeleteConfirmed` from the probe guard.
     */
    @Test
    fun `a confirmed delete suppresses the legacy probe entirely`() {
        var probed = false
        val d = deriveBootDecision(
            serverDeleteConfirmed = true,
            imagePresent = true,
            residueSweepHold = false,
            vaultProvenAbsent = false,
            isLegacyImage = { probed = true; true },
        )
        assertFalse("the probe must not run over a confirmed delete", probed)
        assertFalse("and legacy must not be asserted", d.legacy)
        assertEquals(BootRoute.DELETE_INCOMPLETE, d.route)
    }

    /**
     * No image means nothing to probe — running a 1 MiB decrypt against an absent file is pure cost.
     *
     * MUTATION UNIQUELY CAUGHT: dropping `imagePresent` from the probe guard.
     */
    @Test
    fun `an absent image suppresses the legacy probe entirely`() {
        var probed = false
        val d = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = false,
            residueSweepHold = false,
            vaultProvenAbsent = true,
            isLegacyImage = { probed = true; true },
        )
        assertFalse("the probe must not run with no image present", probed)
        assertFalse(d.legacy)
        assertEquals(BootRoute.ONBOARDING, d.route)
    }

    /**
     * A probe that THROWS must fail closed to "not legacy" — never propagate, and never assert legacy
     * on a failure. Asserting legacy would route a live vault to onboarding, where the create retires
     * an image that was never proven legacy.
     *
     * MUTATION UNIQUELY CAUGHT: replacing the `runCatching{}.getOrDefault(false)` with `true`, or
     * letting the throw escape.
     */
    @Test
    fun `a failing legacy probe fails closed to not-legacy`() {
        val d = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = true,
            residueSweepHold = false,
            vaultProvenAbsent = false,
            isLegacyImage = { error("simulated decrypt fault") },
        )
        assertFalse("a failed probe must never assert legacy", d.legacy)
        assertEquals("and must route to the lock screen, not onboarding", BootRoute.LOCKED, d.route)
    }

    /** A genuine legacy image is detected and carried into both the decision and `present`/`legacy`. */
    @Test
    fun `a legacy image is detected and routed to onboarding`() {
        val d = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = true,
            residueSweepHold = false,
            vaultProvenAbsent = false,
            isLegacyImage = { true },
        )
        assertTrue(d.present)
        assertTrue(d.legacy)
        assertEquals(BootRoute.ONBOARDING, d.route)
    }

    /**
     * THE POINT OF THE LAYER: every input must reach `bootRoute` unaltered. This pins the wiring, so a
     * derivation that silently drops one — the round-1 defect, one level up — fails here even though
     * BootRouteTest stays green.
     *
     * MUTATION UNIQUELY CAUGHT: passing a constant (e.g. `residueSweepHold = false`) instead of the
     * argument.
     */
    @Test
    fun `every input reaches the decision unaltered`() {
        // The hold is the input most easily dropped: it is the only one not re-derivable from disk.
        val held = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = false,
            residueSweepHold = true,
            vaultProvenAbsent = true,
            isLegacyImage = { false },
        )
        assertEquals(
            "a non-durable sweep must withhold onboarding — if the hold is dropped this reads clean",
            BootRoute.LOCKED,
            held.route,
        )

        val notHeld = deriveBootDecision(
            serverDeleteConfirmed = false,
            imagePresent = false,
            residueSweepHold = false,
            vaultProvenAbsent = true,
            isLegacyImage = { false },
        )
        assertEquals(BootRoute.ONBOARDING, notHeld.route)

        // `present` is reported as observed, independent of the legacy verdict.
        assertTrue(
            deriveBootDecision(false, true, false, false, { false }).present,
        )
    }

    /** Precedence is `bootRoute`'s, unchanged by the derivation: a confirmed delete outbids legacy. */
    @Test
    fun `confirmed outbids legacy through the derivation`() {
        val d = deriveBootDecision(
            serverDeleteConfirmed = true,
            imagePresent = true,
            residueSweepHold = false,
            vaultProvenAbsent = false,
            isLegacyImage = { true },
        )
        assertEquals(BootRoute.DELETE_INCOMPLETE, d.route)
    }
}

/**
 * DOES A COMPLETED DESTROY SUPERSEDE A RESIDUE-SWEEP HOLD? (0.9.2 Unit W-A, round 3.)
 *
 * The account-delete completion path and the session collector decide the SAME routing moment. Before
 * this, the collector consumed the carried `residueSweepHold` and the delete path did not, so a hold
 * raised earlier in the process made them disagree — collector LOCKED, delete path Onboarding, last
 * writer wins, pinning a successfully deleted account to a lock screen for the rest of the process.
 *
 * Unifying them is only safe if a completed destroy genuinely supersedes the hold. It does: destroy
 * proves image-bearing absence with its own required `dirSync` and retires both markers only after
 * that proof — strictly stronger evidence than the sweep's unproven unlink. This pins that reasoning.
 */
class DestroySupersedesResidueHoldTest {

    /** The whole point: a completed destroy clears a stale hold. */
    @Test
    fun `a completed destroy supersedes the hold`() {
        assertTrue(
            destroySupersedesResidueHold(vaultProvenAbsent = true, serverDeleteConfirmed = false),
        )
    }

    /**
     * A destroy that threw before retiring its markers has NOT proven anything — the confirmed marker
     * is still present, and the hold must stand.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the `!serverDeleteConfirmed` conjunct.
     */
    @Test
    fun `a destroy that did not reach its marker retire does not supersede`() {
        assertFalse(
            "a surviving confirmed marker means the destroy never completed — the hold stands",
            destroySupersedesResidueHold(vaultProvenAbsent = true, serverDeleteConfirmed = true),
        )
    }

    /**
     * Absence that is not proven is not absence. Without proven image-bearing absence there is no
     * stronger evidence to supersede the hold with.
     *
     * MUTATION UNIQUELY CAUGHT: dropping the `vaultProvenAbsent` conjunct.
     */
    @Test
    fun `an unproven directory never supersedes the hold`() {
        assertFalse(
            destroySupersedesResidueHold(vaultProvenAbsent = false, serverDeleteConfirmed = false),
        )
        assertFalse(
            destroySupersedesResidueHold(vaultProvenAbsent = false, serverDeleteConfirmed = true),
        )
    }
}
