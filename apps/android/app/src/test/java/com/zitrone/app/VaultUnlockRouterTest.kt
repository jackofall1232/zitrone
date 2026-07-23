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
 * D2c §2 unlock-router logic (composable-free): the RAM backoff schedule, the uniform
 * failure surface, and the biometric-availability gate.
 */
class VaultUnlockRouterTest {

    @Test
    fun `backoff is zero fresh, then 500ms times attempts, capped at 8s`() {
        val router = VaultUnlockRouter()
        assertEquals("first attempt is never delayed", 0L, router.backoffDelayMs())
        router.recordFailure()
        assertEquals(500L, router.backoffDelayMs())
        router.recordFailure()
        assertEquals(1_000L, router.backoffDelayMs())
        // Push well past the cap: 20 failures × 500ms = 10s, clamped to 8s.
        repeat(18) { router.recordFailure() }
        assertEquals("capped at 8s", 8_000L, router.backoffDelayMs())
    }

    @Test
    fun `a success clears the backoff counter`() {
        val router = VaultUnlockRouter()
        repeat(5) { router.recordFailure() }
        assertEquals(2_500L, router.backoffDelayMs())
        router.recordSuccess()
        assertEquals(0L, router.backoffDelayMs())
    }

    @Test
    fun `biometric is offered only when enabled AND the platform can authenticate`() {
        val router = VaultUnlockRouter()
        assertTrue(router.biometricOffered(enabled = true, canAuthenticateStrong = true))
        assertFalse("no wrap → not offered", router.biometricOffered(false, true))
        assertFalse("platform can't auth → not offered", router.biometricOffered(true, false))
        assertFalse(router.biometricOffered(false, false))
    }

    @Test
    fun `the failure surface is uniform and names no slot or factor`() {
        // A single generic string — no per-slot / per-factor branch to leak from.
        assertFalse(VaultUnlockRouter.UNIFORM_FAILURE.contains("slot", ignoreCase = true))
        assertFalse(VaultUnlockRouter.UNIFORM_FAILURE.contains("biometric", ignoreCase = true))
    }
}
