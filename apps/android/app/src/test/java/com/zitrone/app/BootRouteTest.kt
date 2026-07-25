// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * COLD-START ROUTE DECISION (0.9.2 Unit W-A).
 *
 * WHY THIS SUITE EXISTS, stated plainly: the previous round had a test proving
 * `sweepOrphanedResidue()` RETURNED the right value on a non-durable sync, and nothing anywhere
 * proving anyone ACTED on it. The caller discarded the result and re-derived cleanliness from a fresh
 * stat — which reports absence the instant a file is unlinked, durable or not. So the suite was green
 * while boot could present a fresh-install screen over residue a journal replay could resurrect.
 *
 * **A test that a value is computed is not a test that it is used.** This suite covers the decision
 * that consumes it.
 */
class BootRouteTest {

    /** The ordinary cold start on a genuinely empty install. */
    @Test
    fun `a provably clean directory boots to onboarding`() {
        assertEquals(
            BootRoute.ONBOARDING,
            bootRoute(
                serverDeleteConfirmed = false,
                vaultImagePresent = false,
                durabilityHold = false,
                vaultProvenAbsent = true,
                legacyImage = false,
            ),
        )
    }

    /**
     * THE ROUND-1 DEFECT, as a test. Everything is unlinked — `vaultProvenAbsent` is true, exactly
     * what a fresh stat reports — but the unlink was never made crash-durable. Onboarding here would
     * claim a wipe that a journal replay can undo.
     */
    @Test
    fun `a non-durable sweep withholds onboarding even though the directory stats clean`() {
        assertEquals(
            "absence that is not durable is not absence",
            BootRoute.LOCKED,
            bootRoute(
                serverDeleteConfirmed = false,
                vaultImagePresent = false,
                durabilityHold = true,
                // TRUE — this is the whole point. A stat cannot tell durable from not.
                vaultProvenAbsent = true,
                legacyImage = false,
            ),
        )
    }

    /** Residue still on disk: not clean, so not a fresh install, hold regardless. */
    @Test
    fun `unswept residue holds the lock screen`() {
        assertEquals(
            BootRoute.LOCKED,
            bootRoute(
                serverDeleteConfirmed = false,
                vaultImagePresent = false,
                durabilityHold = false,
                vaultProvenAbsent = false,
                legacyImage = false,
            ),
        )
    }

    /** A live vault is a lock screen, hold or no hold. */
    @Test
    fun `a present image is always a lock screen`() {
        listOf(true, false).forEach { hold ->
            assertEquals(
                "hold=$hold",
                BootRoute.LOCKED,
                bootRoute(
                    serverDeleteConfirmed = false,
                    vaultImagePresent = true,
                    durabilityHold = hold,
                    vaultProvenAbsent = false,
                legacyImage = false,
                ),
            )
        }
    }

    /** A confirmed server delete outbids everything — D2c owns finishing it. */
    @Test
    fun `a confirmed server delete outbids every other input`() {
        listOf(true, false).forEach { present ->
            listOf(true, false).forEach { hold ->
                listOf(true, false).forEach { proven ->
                    assertEquals(
                        "present=$present hold=$hold proven=$proven",
                        BootRoute.DELETE_INCOMPLETE,
                        bootRoute(true, present, hold, proven, legacyImage = false),
                    )
                }
            }
        }
    }

    /**
     * THE ROUND-3 HIGH, AS A TEST. A legacy (v2) image routes to onboarding so its create() can
     * retire it — but a CONFIRMED server delete outbids that absolutely. Legacy detection used to
     * live in a SEPARATE effect that set `Route.Onboarding` on its own, without awaiting boot and
     * without consulting the confirmed marker: with `{v2 image + vault.delete-confirmed}` it
     * preempted `DeleteIncomplete`, and the create() on that screen CLEARS both markers — erasing the
     * SOLE authorisation for D2c's auto-destroy. Ordering it inside this function makes the
     * precedence structural rather than a timing accident.
     *
     * MUTATION UNIQUELY CAUGHT: hoisting the `legacyImage` arm above `serverDeleteConfirmed`.
     */
    @Test
    fun `a confirmed server delete outbids a legacy image`() {
        assertEquals(
            "a legacy image must never preempt finishing a confirmed account delete — the create() " +
                "on that onboarding screen would clear the marker authorising the destroy",
            BootRoute.DELETE_INCOMPLETE,
            bootRoute(
                serverDeleteConfirmed = true,
                vaultImagePresent = true,
                durabilityHold = false,
                vaultProvenAbsent = false,
                legacyImage = true,
            ),
        )
    }

    /** With no confirmed delete, a legacy image DOES route to onboarding — it is unusable as-is. */
    @Test
    fun `a legacy image routes to onboarding when no delete is confirmed`() {
        assertEquals(
            BootRoute.ONBOARDING,
            bootRoute(
                serverDeleteConfirmed = false,
                vaultImagePresent = true,
                durabilityHold = false,
                vaultProvenAbsent = false,
                legacyImage = true,
            ),
        )
    }

    /**
     * And legacy outranks "an image is present" — a legacy image IS present, so without this ordering
     * it would fall through to a dead lock screen the user can never pass.
     *
     * MUTATION UNIQUELY CAUGHT: moving the `legacyImage` arm below `vaultImagePresent`.
     */
    @Test
    fun `legacy outranks image-present but not a confirmed delete`() {
        assertEquals(
            BootRoute.ONBOARDING,
            bootRoute(false, vaultImagePresent = true, durabilityHold = true, vaultProvenAbsent = false, legacyImage = true),
        )
        assertEquals(
            BootRoute.DELETE_INCOMPLETE,
            bootRoute(true, vaultImagePresent = true, durabilityHold = true, vaultProvenAbsent = false, legacyImage = true),
        )
    }

    /**
     * Exhaustive over all 16 combinations as an explicit table — not by re-implementing the rule,
     * which would pass against any refactor including a broken one. (Legacy defaults to false here;
     * its precedence is covered by the three tests above.)
     */
    @Test
    fun `full truth table`() {
        val expected = mapOf(
            // (confirmed, imagePresent, sweepHold, provenAbsent)
            listOf(true, true, true, true) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, true, true, false) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, true, false, true) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, true, false, false) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, false, true, true) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, false, true, false) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, false, false, true) to BootRoute.DELETE_INCOMPLETE,
            listOf(true, false, false, false) to BootRoute.DELETE_INCOMPLETE,
            listOf(false, true, true, true) to BootRoute.LOCKED,
            listOf(false, true, true, false) to BootRoute.LOCKED,
            listOf(false, true, false, true) to BootRoute.LOCKED,
            listOf(false, true, false, false) to BootRoute.LOCKED,
            listOf(false, false, true, true) to BootRoute.LOCKED,
            listOf(false, false, true, false) to BootRoute.LOCKED,
            listOf(false, false, false, true) to BootRoute.ONBOARDING,
            listOf(false, false, false, false) to BootRoute.LOCKED,
        )
        expected.forEach { (inputs, want) ->
            val (confirmed, present, hold, proven) = inputs
            assertEquals(
                "bootRoute(confirmed=$confirmed, present=$present, hold=$hold, proven=$proven)",
                want,
                bootRoute(confirmed, present, hold, proven, legacyImage = false),
            )
        }
        assertEquals("the table must cover every combination", 16, expected.size)
    }

    /**
     * ONBOARDING — the fresh-install presentation, the single most dangerous output — is reachable
     * from exactly ONE of the sixteen input combinations. Stated on its own so a future edit that
     * widens it fails loudly.
     */
    @Test
    fun `onboarding is reachable from exactly the expected input combinations`() {
        // ALL FIVE inputs, 32 combinations (round-4 review, Moonshot). This swept only four and took
        // `legacyImage`'s default, so it asserted "exactly one combination" over a subspace while the
        // function had grown a fifth input — a regression WIDENING onboarding via the legacy arm
        // would not have failed it. The assertion message overstated what the test proved: the same
        // class of defect as a comment claiming a property the code lacks, in an assertion string.
        val all = listOf(true, false).flatMap { c ->
            listOf(true, false).flatMap { i ->
                listOf(true, false).flatMap { h ->
                    listOf(true, false).flatMap { p ->
                        listOf(true, false).map { l -> listOf(c, i, h, p, l) }
                    }
                }
            }
        }
        val onboarding = all.filter { (c, i, h, p, l) -> bootRoute(c, i, h, p, l) == BootRoute.ONBOARDING }
        // Onboarding is reachable two ways, and ONLY two: a proven-clean directory, or a legacy
        // image — each requiring no confirmed delete. Both are enumerated explicitly.
        // ENUMERATED, not re-derived (round-1 review, Gemini). Computing the expectation with a
        // formula that mirrors the implementation means a developer who mutates `bootRoute` can make
        // the suite pass by copying the same mutation here. The expected set is written out instead:
        // onboarding is reachable ONLY with no confirmed delete, and then only via a legacy image or a
        // provably clean directory.
        // NARROWED 2026-07-25 (Unit W-A follow-up): the legacy arm now requires `vaultImagePresent`.
        // Three combinations left this set — legacy claimed over an image that is NOT present:
        //     (false, false, true,  true,  true)   hold + proven-absent + legacy  → now LOCKED
        //     (false, false, true,  false, true)   hold + unprovable   + legacy  → now LOCKED
        //     (false, false, false, false, true)   unprovable          + legacy  → now LOCKED
        // The fourth such combination, (false, false, false, true, true), REMAINS onboarding — it
        // still reaches the proven-absence arm on its own merits, without the legacy flag.
        // None of the three is reachable in production (`deriveBootDecision` computes `legacy` only
        // when the image is present), so no behaviour moved; what moved is that the router now
        // ENFORCES the rule its caller was enforcing for it. See `ResidenceTest`.
        val expected = setOf(
            //     confirmed, present, hold, provenAbsent, legacy
            listOf(false, true, true, true, true),
            listOf(false, true, true, false, true),
            listOf(false, true, false, true, true),
            listOf(false, true, false, false, true),
            listOf(false, false, false, true, true),
            listOf(false, false, false, true, false),
        )
        assertEquals(
            "onboarding — the fresh-install presentation — must be reachable ONLY from a PRESENT " +
                "legacy image or a provably clean directory, and never over a confirmed delete",
            expected,
            onboarding.toSet(),
        )
        assertEquals("the sweep must cover all five inputs", 32, all.size)
    }
}
