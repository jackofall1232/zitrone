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

    // F — toggle off gates ONLY the repeat reminders: arrival alerts still
    // fire (rate-limited to one per window), but nothing is scheduled and the
    // window boundary stays silent. Turning the toggle off must never
    // silently disable message notifications altogether.
    @Test
    fun `toggle off still alerts on arrival but never re-fires`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ }, isEnabled = { false })

        // Burst inside the first window: exactly one arrival alert.
        repeat(4) {
            scheduler.onIncomingMessage("c1")
            advanceTimeBy(20_000)
        }
        assertEquals(1, fired)

        // Window boundary passes with messages having arrived during it —
        // toggle off means NO deferred re-fire.
        advanceTimeBy(COOLDOWN_MS)
        runCurrent()
        assertEquals(1, fired)

        // A fresh arrival AFTER the window still alerts immediately.
        scheduler.onIncomingMessage("c1")
        assertEquals(2, fired)
    }

    // H — the deferred re-fire consults hasUnread at fire time: if every
    // message that armed the window already vanished (short-TTL burn, remote
    // burn), the boundary stays silent instead of alerting for an empty chat.
    @Test
    fun `re-fire is skipped when the unread messages already burned`() = runTest {
        var fired = 0
        var unread = true
        val scheduler = NotificationScheduler(
            scope = backgroundScope,
            fire = { fired++ },
            isEnabled = { true },
            hasUnread = { unread },
            clock = { currentTime },
            cooldownMs = COOLDOWN_MS,
        )

        scheduler.onIncomingMessage("c1")            // fire #1, opens window
        advanceTimeBy(30_000)
        scheduler.onIncomingMessage("c1")            // arms the boundary re-fire
        assertEquals(1, fired)

        unread = false                                // 30s TTL burned them all
        advanceTimeBy(COOLDOWN_MS)
        runCurrent()
        assertEquals(1, fired)                        // boundary stays silent

        // A NEW arrival after the window alerts immediately (it is itself
        // proof of unread content — hasUnread gates only the deferred path).
        scheduler.onIncomingMessage("c1")
        assertEquals(2, fired)
    }

    // G — a removed (deleted) conversation never fires its armed re-fire, and
    // a later re-add starts a completely fresh cycle with no inherited cooldown.
    @Test
    fun `removing a conversation cancels its re-fire and clears its state`() = runTest {
        var fired = 0
        val scheduler = scheduler(fire = { fired++ })

        scheduler.onIncomingMessage("c1")            // fire #1, opens window
        advanceTimeBy(30_000)
        scheduler.onIncomingMessage("c1")            // in-window: arms re-fire
        assertEquals(1, fired)

        scheduler.onConversationRemoved("c1")        // contact deleted
        advanceTimeBy(COOLDOWN_MS * 2)
        runCurrent()
        assertEquals(1, fired)                       // boundary stays silent

        // Re-added contact: fresh state, immediate alert.
        scheduler.onIncomingMessage("c1")
        assertEquals(2, fired)
    }
}
