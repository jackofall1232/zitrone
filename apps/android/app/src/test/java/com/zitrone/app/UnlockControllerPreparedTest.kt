// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2c §4: [UnlockController.unlock] with a caller-prepared factory carrying resolved
 * credentials. Pins the invariants the vault path relies on — build-once idempotence, and
 * that a REFUSED build (terminal wipe / already-live) runs `onRefused` so the caller wipes
 * the unused VaultOpen — while the D2b teardown/drain semantics stay unchanged.
 */
class UnlockControllerPreparedTest {

    private class FakeSession(val id: Int)

    /** A fake resolved credential (stands in for a VaultOpen); records whether it was wiped. */
    private class FakeOpen {
        var wiped = false
    }

    private class Rig {
        val built = mutableListOf<FakeSession>()
        val published = mutableListOf<FakeSession?>()
        val stopped = mutableListOf<FakeSession>()
        private var nextId = 0

        val controller = UnlockController<FakeSession>(
            newSessionScope = { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) },
            buildSession = { error("no-arg build is unused on the vault path") },
            publish = { published += it },
            stopSession = { stopped += it },
            afterPublish = {},
        )

        fun preparedUnlock(open: FakeOpen) {
            controller.unlock(
                prepared = {
                    // On an accepted build the factory "consumes" the credential (no wipe here);
                    // the session owns it thereafter.
                    FakeSession(nextId++).also { built += it }
                },
                onRefused = { open.wiped = true },
            )
        }
    }

    @Test
    fun `unlock(prepared) builds and publishes once`() {
        val rig = Rig()
        val open = FakeOpen()
        rig.preparedUnlock(open)
        assertEquals(1, rig.built.size)
        assertEquals(listOf<FakeSession?>(rig.built[0]), rig.published)
        assertFalse("an accepted build never runs onRefused", open.wiped)
    }

    @Test
    fun `a second unlock(prepared) while live is refused and wipes the unused VaultOpen`() {
        val rig = Rig()
        rig.preparedUnlock(FakeOpen())
        val second = FakeOpen()
        rig.preparedUnlock(second)
        assertEquals("no second session built", 1, rig.built.size)
        assertTrue("the refused build must wipe its VaultOpen", second.wiped)
    }

    @Test
    fun `a terminal-wipe refusal wipes the prepared VaultOpen and builds nothing`() {
        val rig = Rig()
        rig.controller.beginTerminalWipe()
        val open = FakeOpen()
        rig.preparedUnlock(open)
        assertTrue(rig.built.isEmpty())
        assertTrue("terminal wipe refuses and wipes the VaultOpen", open.wiped)
        // Once the gate lifts, a prepared unlock proceeds normally.
        rig.controller.endTerminalWipe()
        val open2 = FakeOpen()
        rig.preparedUnlock(open2)
        assertEquals(1, rig.built.size)
        assertFalse(open2.wiped)
    }

    @Test
    fun `lock then a fresh prepared unlock builds a new session (teardown unchanged)`() {
        val rig = Rig()
        rig.preparedUnlock(FakeOpen())
        rig.controller.lock()
        assertEquals(listOf(rig.built[0]), rig.stopped)
        assertNull(rig.published.last())
        rig.preparedUnlock(FakeOpen())
        assertEquals(2, rig.built.size)
        assertEquals(rig.built[1], rig.published.last())
    }
}
