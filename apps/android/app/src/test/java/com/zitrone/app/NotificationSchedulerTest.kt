// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.notifications.NotificationScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Trigger logic for the content-free notification: rate-limit within a window,
 * re-fire once per window while unread, reset on read. Virtual time throughout
 * (clock = { currentTime }), mirroring MessageRepositoryTest — the 2-minute
 * cooldown elapses instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSchedulerTest {

    private companion object {
        const val COOLDOWN_MS = 120_000L
    }

    private fun TestScope.scheduler(
        fire: () -> Unit,
        isEnabled: () -> Boolean = { true },
    ) = NotificationScheduler(
        scope = backgroundScope,
        fire = fire,
        isEnabled = isEnabled,
        clock = { currentTime },
        cooldownMs = COOLDOWN_MS,
    )

    // A — rate limit: a rapid burst inside one window alerts exactly once.
    @Test
    fun `rapid burst fires exactly once within the first cooldown window`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ })

        scheduler.onIncomingMessage("c1") // t=0 — first ever, fires now
        repeat(9) {
            advanceTimeBy(5_000) // a few seconds between arrivals
            scheduler.onIncomingMessage("c1")
        }
        // Advance to just before the window boundary — still one alert.
        advanceTimeBy(COOLDOWN_MS - currentTime - 1)
        runCurrent()

        assertEquals(1, fired)
    }

    // B — re-fire (NOT once-total): steady traffic re-alerts ~1×/window.
    @Test
    fun `sustained unread traffic re-fires about once per two-minute window`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ })

        scheduler.onIncomingMessage("c1") // t=0 — fire #1
        // A message every 30s for 8 minutes, never read.
        repeat(16) {
            advanceTimeBy(30_000)
            scheduler.onIncomingMessage("c1")
        }
        runCurrent()

        // Fires at ~0, 120, 240, 360, 480s.
        assertEquals(5, fired)
    }

    // C — read cancels a pending re-fire: no phantom alert after opening.
    @Test
    fun `reading before the window boundary cancels the pending re-fire`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ })

        scheduler.onIncomingMessage("c1") // t=0 — fire #1
        advanceTimeBy(30_000)
        scheduler.onIncomingMessage("c1") // arms a re-fire at t=120_000
        advanceTimeBy(30_000)
        scheduler.onConversationRead("c1") // t=60_000 — cancel + epoch bump
        advanceTimeBy(120_000) // well past the old boundary
        runCurrent()

        assertEquals(1, fired) // no phantom re-fire
    }

    // D — fresh cycle: a message after a read alerts immediately again.
    @Test
    fun `a message after a read starts a fresh cycle and fires immediately`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ })

        scheduler.onIncomingMessage("c1") // t=0 — fire #1
        runCurrent()
        assertEquals(1, fired)

        advanceTimeBy(10_000)
        scheduler.onConversationRead("c1") // reset (lastFiredAt = null)
        scheduler.onIncomingMessage("c1") // fresh cycle — fire #2 now
        runCurrent()

        assertEquals(2, fired)
    }

    // E — burst then silence: one re-fire at the boundary, then quiet.
    @Test
    fun `burst then silence re-fires once at the boundary then goes quiet`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ })

        scheduler.onIncomingMessage("c1") // t=0 — fire #1
        repeat(5) {
            advanceTimeBy(2_000)
            scheduler.onIncomingMessage("c1") // all suppressed inside the window
        }
        advanceTimeBy(200_000) // long silence past the boundary
        runCurrent()

        assertEquals(2, fired) // exactly one re-fire, then nothing
    }

    // F — toggle off: zero alerts, no scheduling at all.
    @Test
    fun `toggle off produces no alerts`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ }, isEnabled = { false })

        repeat(5) {
            scheduler.onIncomingMessage("c1")
            advanceTimeBy(30_000)
        }
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(0, fired)
    }
}
