// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.ui.components.RegistrationPowState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host tests for the PoW screen's per-tick state decision — the precedence rules the pitcher
 * screen's contract hangs on (§6.3 non-blocking prompt, §6.5 backgrounding). The fraction is
 * deliberately absent here: progress never flows through the ticker (§6.1).
 */
class RegistrationPowTickStateTest {

    @Test
    fun solvingUntilThePromptThreshold() {
        assertEquals(
            RegistrationPowState.SOLVING,
            registrationPowTickState(elapsedSeconds = 0, promptDismissed = false, inForeground = true),
        )
        assertEquals(
            RegistrationPowState.SOLVING,
            registrationPowTickState(elapsedSeconds = 59, promptDismissed = false, inForeground = true),
        )
    }

    @Test
    fun promptRaisesAtSixtySecondsAndStaysUpWhileUndismissed() {
        assertEquals(
            RegistrationPowState.PROMPTED_AT_60S,
            registrationPowTickState(elapsedSeconds = 60, promptDismissed = false, inForeground = true),
        )
        assertEquals(
            RegistrationPowState.PROMPTED_AT_60S,
            registrationPowTickState(elapsedSeconds = 300, promptDismissed = false, inForeground = true),
        )
    }

    @Test
    fun aDismissedPromptNeverReRaises() {
        assertEquals(
            RegistrationPowState.SOLVING,
            registrationPowTickState(elapsedSeconds = 61, promptDismissed = true, inForeground = true),
        )
        assertEquals(
            RegistrationPowState.SOLVING,
            registrationPowTickState(elapsedSeconds = 600, promptDismissed = true, inForeground = true),
        )
    }

    @Test
    fun backgroundedWinsOverThePrompt() {
        assertEquals(
            RegistrationPowState.BACKGROUNDED,
            registrationPowTickState(elapsedSeconds = 61, promptDismissed = false, inForeground = false),
        )
        assertEquals(
            RegistrationPowState.BACKGROUNDED,
            registrationPowTickState(elapsedSeconds = 5, promptDismissed = false, inForeground = false),
        )
    }

    @Test
    fun returningFromBackgroundReRaisesAStillDuePrompt() {
        // The tick after return recomputes from scratch: prompt due + not dismissed → up again.
        assertEquals(
            RegistrationPowState.PROMPTED_AT_60S,
            registrationPowTickState(elapsedSeconds = 90, promptDismissed = false, inForeground = true),
        )
    }

    @Test
    fun anUnanswerableForegroundProbeIsNotTreatedAsBackgrounded() {
        // BACKGROUNDED claims "you left, we're still working" — never claimed on no evidence.
        assertEquals(
            RegistrationPowState.SOLVING,
            registrationPowTickState(elapsedSeconds = 5, promptDismissed = false, inForeground = null),
        )
        assertEquals(
            RegistrationPowState.PROMPTED_AT_60S,
            registrationPowTickState(elapsedSeconds = 60, promptDismissed = false, inForeground = null),
        )
    }
}
