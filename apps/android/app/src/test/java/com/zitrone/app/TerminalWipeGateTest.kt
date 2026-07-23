// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * D2c round 6: the account-delete completion's terminal-wipe teardown ([completeTerminalWipe]) must
 * (a) DESTROY the vault so no crypto remains on disk (no remanence) and (b) ALWAYS release the unlock
 * gate. Ordering is load-bearing: [finishUi] runs FIRST — it tears the session down, and that runs
 * VaultRuntime.close()'s final SYNCHRONOUS reseal, which rewrites the image WITH the account's crypto
 * — then [destroyVault] DELETES the image (+ biometric), so no resealed image survives. destroyVault
 * is in a `finally` around finishUi so even a finishUi throw can't skip the no-remanence step; a
 * finishUi CancellationException still propagates but only AFTER destroyVault ran. [releaseGate]
 * (endTerminalWipe) is the outermost `finally` so nothing above leaves unlock blocked forever.
 * Extracted top-level so the ordering + finally guarantees are host-testable.
 */
class TerminalWipeGateTest {

    @Test
    fun `happy path runs finishUi then destroyVault then releases the gate`() {
        val events = mutableListOf<String>()
        completeTerminalWipe(
            finishUi = { events += "ui" },
            destroyVault = { events += "destroy" },
            releaseGate = { events += "release" },
        )
        // The reseal (in finishUi) STRICTLY precedes the file destroy — the no-remanence ordering.
        assertEquals(listOf("ui", "destroy", "release"), events)
    }

    @Test
    fun `a finishUi throw is tolerated but destroyVault STILL runs and the gate is released`() {
        val events = mutableListOf<String>()
        // The remanence regression guard: a throwing session teardown must NOT skip the file destroy
        // (or the account's crypto would survive on disk) and must not crash the confined worker.
        completeTerminalWipe(
            finishUi = { throw IllegalStateException("teardown failed") },
            destroyVault = { events += "destroy" },
            releaseGate = { events += "release" },
        )
        assertEquals(
            "destroyVault ran despite the finishUi throw, and the gate was released",
            listOf("destroy", "release"), events,
        )
    }

    @Test
    fun `a CancellationException from finishUi propagates but destroyVault and release STILL run`() {
        val events = mutableListOf<String>()
        // Cooperative cancellation is not swallowed as a tolerated failure — it propagates — but the
        // no-remanence destroy and the gate release still run via the finallys before it escapes.
        assertThrows(CancellationException::class.java) {
            completeTerminalWipe(
                finishUi = { throw CancellationException("scope cancelled") },
                destroyVault = { events += "destroy" },
                releaseGate = { events += "release" },
            )
        }
        assertEquals(
            "destroyVault + gate release ran via finally even though finishUi cancelled",
            listOf("destroy", "release"), events,
        )
    }

    @Test
    fun `a destroyVault throw still releases the gate`() {
        val events = mutableListOf<String>()
        // Defense in depth: the real destroyVault tolerates each step's throw internally, but even a
        // stray throw must not leave the unlock gate SET forever — releaseGate is the outermost finally.
        assertThrows(IllegalStateException::class.java) {
            completeTerminalWipe(
                finishUi = { events += "ui" },
                destroyVault = { throw IllegalStateException("destroy failed") },
                releaseGate = { events += "release" },
            )
        }
        assertEquals("finishUi ran and the gate was released despite the destroy throw", listOf("ui", "release"), events)
    }
}
