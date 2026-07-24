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
 * D3 idle auto-lock decision — the pure branch matrix, factored out of the ProcessLifecycleOwner
 * glue so it is verifiable without a real Lifecycle (the lifecycle callbacks + coroutine timer in
 * [VaultLockManager] are the only non-host-testable surface).
 */
class AutoLockDecisionTest {

    @Test
    fun `no live session does nothing — nothing is unlocked to lock`() {
        assertEquals(
            AutoLockAction.None,
            autoLockOnBackground(sessionLive = false, terminalWipe = false, timeoutSeconds = 300),
        )
        // Even an "immediate" timeout is a no-op with no session.
        assertEquals(
            AutoLockAction.None,
            autoLockOnBackground(sessionLive = false, terminalWipe = false, timeoutSeconds = 0),
        )
    }

    @Test
    fun `a terminal wipe in progress does nothing — the delete owns teardown`() {
        assertEquals(
            AutoLockAction.None,
            autoLockOnBackground(sessionLive = true, terminalWipe = true, timeoutSeconds = 0),
        )
        assertEquals(
            AutoLockAction.None,
            autoLockOnBackground(sessionLive = true, terminalWipe = true, timeoutSeconds = 300),
        )
    }

    @Test
    fun `immediate (zero or negative) locks now`() {
        assertEquals(
            AutoLockAction.LockNow,
            autoLockOnBackground(sessionLive = true, terminalWipe = false, timeoutSeconds = 0),
        )
        // A negative value ever loaded from settings is still "immediate", never a negative delay
        // (matches the `timeoutSeconds <= 0` branch and autoLockLabel's `<= 0 -> "Immediate"`).
        assertEquals(
            AutoLockAction.LockNow,
            autoLockOnBackground(sessionLive = true, terminalWipe = false, timeoutSeconds = -1),
        )
    }

    @Test
    fun `a positive timeout schedules a lock after that many milliseconds`() {
        assertEquals(
            AutoLockAction.LockAfter(60_000L),
            autoLockOnBackground(sessionLive = true, terminalWipe = false, timeoutSeconds = 60),
        )
        // The default (5 minutes).
        assertEquals(
            AutoLockAction.LockAfter(300_000L),
            autoLockOnBackground(sessionLive = true, terminalWipe = false, timeoutSeconds = 300),
        )
        assertEquals(
            AutoLockAction.LockAfter(900_000L),
            autoLockOnBackground(sessionLive = true, terminalWipe = false, timeoutSeconds = 900),
        )
    }

    @Test
    fun `fire-time re-check gates on a still-live session and no delete`() {
        assertTrue(shouldAutoLockAtFireTime(sessionLive = true, terminalWipe = false))
        // A delete STARTED during the background interval → do not race its teardown.
        assertFalse(shouldAutoLockAtFireTime(sessionLive = true, terminalWipe = true))
        // The session was already torn down (forced logout) during the interval.
        assertFalse(shouldAutoLockAtFireTime(sessionLive = false, terminalWipe = false))
        // Both at once (session gone AND a delete owns teardown) → still do not fire.
        assertFalse(shouldAutoLockAtFireTime(sessionLive = false, terminalWipe = true))
    }
}
