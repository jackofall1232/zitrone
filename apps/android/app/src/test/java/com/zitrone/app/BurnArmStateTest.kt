// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.ArmBurn
import com.zitrone.app.crypto.vault.VaultImageException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BURN-ARMING UI STATE (0.9.3 Unit S, paired-blind review round 1 — the BLOCKING finding).
 *
 * The defect these tests exist to prevent: the arming dialog's state was composition-local
 * `remember`, while the Argon2id arm ran on the container's process scope. An Activity recreation
 * (rotation, dark-mode toggle, font-size change, split-screen) reset those flags and dismissed the
 * dialog — and because a successful arm is signalled ONLY by the dialog closing, that dismissal was
 * INDISTINGUISHABLE from success. A failed arm therefore read as an armed one, leaving the user
 * believing they held a duress credential they did not have.
 *
 * The state now lives in [AppContainer.burnArm]. These tests pin the two properties that make the
 * fix real rather than cosmetic:
 *
 *  1. **Fail-closed mapping** — only [ArmBurn.Armed] may produce [BurnArmUi.Closed].
 *  2. **The outcome outlives the composition** — a terminal state published to the flow is readable
 *     afterwards by an entirely new observer, which is what a recreated composition is.
 */
class BurnArmStateTest {

    // ── 1. Fail-closed mapping ──────────────────────────────────────────────────────────────────

    @Test
    fun `only a real arm closes the dialog`() {
        assertEquals(BurnArmUi.Closed, burnArmOutcome(Result.success(ArmBurn.Armed)))
    }

    @Test
    fun `a vault collision is reported, never silently closed`() {
        val state = burnArmOutcome(Result.success(ArmBurn.CollidesWithVault))

        assertEquals(BurnArmUi.Rejected(BurnArmUi.Reason.CollidesWithVault), state)
        assertNotEquals("a collision must never present as success", BurnArmUi.Closed, state)
    }

    @Test
    fun `a pending delete is reported, never silently closed`() {
        val state = burnArmOutcome(Result.success(ArmBurn.DeletePending))

        assertEquals(BurnArmUi.Rejected(BurnArmUi.Reason.DeletePending), state)
        assertNotEquals(BurnArmUi.Closed, state)
    }

    /**
     * The one that would have shipped the harm: a non-durable write means the credential may not
     * survive a crash, so the user must NOT be told it is set.
     */
    @Test
    fun `a non-durable write is reported, never silently closed`() {
        val state = burnArmOutcome(Result.failure(VaultImageException.NotDurable()))

        assertEquals(BurnArmUi.Rejected(BurnArmUi.Reason.NotDurable), state)
        assertNotEquals("NotDurable must never present as success", BurnArmUi.Closed, state)
    }

    /** Any unexpected throwable is treated as a failure too — fail-closed, not fail-open. */
    @Test
    fun `an unexpected failure is reported, never silently closed`() {
        val state = burnArmOutcome(Result.failure(IllegalStateException("vault image not open")))

        assertNotEquals(BurnArmUi.Closed, state)
        assertTrue(state is BurnArmUi.Rejected)
    }

    // ── 2. The outcome outlives the composition ─────────────────────────────────────────────────

    /**
     * THE REGRESSION TEST FOR THE BLOCKING FINDING.
     *
     * Simulates the rotation: an arm begins, the observing composition is discarded, and the outcome
     * lands afterwards. The state must still hold the failure so the recreated UI can show it. With
     * the old composition-local `remember` this was structurally impossible — the outcome went to a
     * dead composition and the user saw an empty screen that looked exactly like success.
     */
    @Test
    fun `a failure landing after the composition is gone is still readable`() {
        val state = MutableStateFlow<BurnArmUi>(BurnArmUi.Open)

        assertTrue("arming should claim the single-flight", beginBurnArm(state))
        assertEquals(BurnArmUi.Arming, state.value)

        // ── Activity recreation happens here: any composition-local state would be discarded. ──

        // The continuation, still running on the process scope, publishes its real outcome.
        state.value = burnArmOutcome(Result.success(ArmBurn.CollidesWithVault))

        // A brand-new observer — i.e. the recreated composition — still finds the failure.
        assertEquals(BurnArmUi.Rejected(BurnArmUi.Reason.CollidesWithVault), state.value)
        assertNotEquals(
            "the recreated UI must not see the success signal after a failed arm",
            BurnArmUi.Closed,
            state.value,
        )
    }

    /** A recreation mid-arm must find the dialog still busy, not dismissed. */
    @Test
    fun `a recreation mid-arm still sees an arm in flight`() {
        val state = MutableStateFlow<BurnArmUi>(BurnArmUi.Open)
        beginBurnArm(state)

        assertEquals(
            "a recreated composition must restore the busy dialog, not a closed one",
            BurnArmUi.Arming,
            state.value,
        )
    }

    // ── Single-flight ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a second arm cannot start while one is running`() {
        val state = MutableStateFlow<BurnArmUi>(BurnArmUi.Open)

        assertTrue(beginBurnArm(state))
        assertFalse("a double tap, or a recreated screen, must not start a second arm", beginBurnArm(state))
    }

    /**
     * A retry after a failure IS legitimate and must not be dropped — the reason the claim is
     * CAS-looped rather than a fixed expect-value.
     */
    @Test
    fun `a retry after a rejection is allowed`() {
        val state = MutableStateFlow<BurnArmUi>(BurnArmUi.Rejected(BurnArmUi.Reason.CollidesWithVault))

        assertTrue("the user must be able to correct their entry and retry", beginBurnArm(state))
        assertEquals(BurnArmUi.Arming, state.value)
    }

    /**
     * A dismissal must never discard an IN-FLIGHT arm's outcome (review round 2, B1).
     *
     * Unreachable through today's UI — the dialog disables Cancel and system dismissal while busy —
     * but the guarantee belongs at the state machine rather than resting on one composable's `!busy`
     * flag. A future non-UI caller is exactly how the round-1 defect comes back.
     */
    @Test
    fun `a dismissal cannot discard an arm in flight`() {
        val state = MutableStateFlow<BurnArmUi>(BurnArmUi.Open)
        beginBurnArm(state)

        closeBurnSetupState(state)

        assertEquals("closing while Arming would strand the outcome", BurnArmUi.Arming, state.value)

        // And once the outcome has landed, the user CAN dismiss it.
        state.value = burnArmOutcome(Result.success(ArmBurn.DeletePending))
        closeBurnSetupState(state)
        assertEquals(BurnArmUi.Closed, state.value)
    }

    /** Opening the dialog fresh must not inherit a previous attempt's error. */
    @Test
    fun `a reopened dialog starts clean`() {
        val state = MutableStateFlow<BurnArmUi>(BurnArmUi.Rejected(BurnArmUi.Reason.NotDurable))

        state.value = BurnArmUi.Closed
        state.value = BurnArmUi.Open

        assertEquals(BurnArmUi.Open, state.value)
    }
}
