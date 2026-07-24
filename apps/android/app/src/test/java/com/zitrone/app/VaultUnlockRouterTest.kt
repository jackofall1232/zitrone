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

    // ── Triple-entry creation gate (0.9.2) ──────────────────────────────────────────────────

    @Test
    fun `three consecutive identical entries create on the third, not the first or second`() {
        val router = VaultUnlockRouter()
        assertFalse("1st identical entry does not create", router.decideCreate("new-vault-pass"))
        assertFalse("2nd identical entry does not create", router.decideCreate("new-vault-pass"))
        assertTrue("3rd identical entry creates", router.decideCreate("new-vault-pass"))
    }

    @Test
    fun `a different string mid-sequence resets the streak to one`() {
        val router = VaultUnlockRouter()
        assertFalse(router.decideCreate("candidate-A")) // count 1
        assertFalse(router.decideCreate("candidate-A")) // count 2
        // A different string breaks the streak and becomes the new candidate at count 1.
        assertFalse("different string resets to 1", router.decideCreate("candidate-B"))
        // Re-entering the ORIGINAL now starts its own fresh streak — not a 3rd of the original.
        assertFalse(router.decideCreate("candidate-A")) // count 1 (fresh)
        assertFalse(router.decideCreate("candidate-A")) // count 2
        assertTrue(router.decideCreate("candidate-A"))  // count 3 → create
    }

    @Test
    fun `resetCandidate mid-sequence prevents the third entry from creating`() {
        val router = VaultUnlockRouter()
        assertFalse(router.decideCreate("p")) // 1
        assertFalse(router.decideCreate("p")) // 2
        router.resetCandidate()               // uninterrupted-sequence guard fires (background/lock/death)
        assertFalse("post-reset entry is a fresh candidate, not the 3rd", router.decideCreate("p"))
        assertFalse(router.decideCreate("p"))
        assertTrue(router.decideCreate("p"))  // a fresh, uninterrupted run of 3 still works
    }

    @Test
    fun `the create gate is independent of the backoff counter`() {
        val router = VaultUnlockRouter()
        // Backoff advances on each failed attempt; the candidate streak advances only on IDENTICAL
        // strings. Distinct strings bump backoff but keep resetting the candidate to 1.
        router.decideCreate("x"); router.recordFailure()
        router.decideCreate("y"); router.recordFailure()
        router.decideCreate("z"); router.recordFailure()
        assertEquals("backoff counts all 3 failures", 1_500L, router.backoffDelayMs())
        // None of those created (each was a distinct string → streak stayed at 1).
        assertFalse(router.decideCreate("q")) // still 1 for a new string
        // And a recordSuccess clears backoff but the candidate is managed separately.
        router.recordSuccess()
        assertEquals(0L, router.backoffDelayMs())
    }

    @Test
    fun `once the threshold is reached a further identical entry still requests create`() {
        // Models a create that fails closed (e.g. a delete marker present → store returns Rejected):
        // the caller keeps the streak, and each further identical entry keeps requesting create so it
        // succeeds the moment the block clears.
        val router = VaultUnlockRouter()
        router.decideCreate("p"); router.decideCreate("p")
        assertTrue(router.decideCreate("p")) // 3 → create
        assertTrue("4th identical still requests create", router.decideCreate("p"))
    }
}
