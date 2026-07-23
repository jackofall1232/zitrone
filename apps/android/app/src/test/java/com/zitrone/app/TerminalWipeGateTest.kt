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
 * D2c round 2: the account-delete completion's terminal-wipe teardown ([completeTerminalWipe]) must
 * ALWAYS release the unlock gate. Its crypto wipe (signalStore.wipe → runtime.mutate) can THROW on a
 * closed-runtime race — a revocation ran runtime.close() first — and the round-1 code called
 * endTerminalWipe() as the last statement, so that throw left the gate SET forever (the app stuck at
 * a lock screen that can't proceed). The fix runs the release in a `finally` and tolerates the wipe
 * throw (the account is being wiped regardless). Extracted top-level so the finally is host-testable.
 */
class TerminalWipeGateTest {

    @Test
    fun `happy path runs wipe then ui then releases the gate`() {
        val events = mutableListOf<String>()
        completeTerminalWipe(
            wipeCryptoRecords = { events += "wipe" },
            finishUi = { events += "ui" },
            releaseGate = { events += "release" },
        )
        assertEquals(listOf("wipe", "ui", "release"), events)
    }

    @Test
    fun `a closed-runtime wipe throw is tolerated and the gate is STILL released`() {
        val events = mutableListOf<String>()
        // The regression: a throwing wipe must not skip endTerminalWipe(). UI teardown still runs
        // (the throw is tolerated) and the gate is released via the finally.
        completeTerminalWipe(
            wipeCryptoRecords = { throw IllegalStateException("vault runtime is closed") },
            finishUi = { events += "ui" },
            releaseGate = { events += "release" },
        )
        assertEquals("ui completed and the gate was released despite the wipe throw", listOf("ui", "release"), events)
    }

    @Test
    fun `a CancellationException from the wipe propagates but the gate is STILL released`() {
        val events = mutableListOf<String>()
        // Cooperative cancellation is not swallowed as a tolerated wipe failure — it propagates —
        // but the finally still releases the gate so unlock is never blocked forever.
        assertThrows(CancellationException::class.java) {
            completeTerminalWipe(
                wipeCryptoRecords = { throw CancellationException("scope cancelled") },
                finishUi = { events += "ui" },
                releaseGate = { events += "release" },
            )
        }
        assertEquals("finishUi skipped (cancellation), but the gate was released via finally", listOf("release"), events)
    }
}
