// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.decoy.CoverPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE R-U3-1 YIELD, driven directly: **under pressure cover is dropped, and it stays dropped for a
 * window rather than stuttering.**
 *
 * This file exists because of the round-5 lesson. That round found a defence nothing tested, because
 * it lived inside a class the suite could only reach through the send path — so the mechanism became
 * a production type and its tests drive it directly. Everything here moves a real threshold: a fake
 * clock and a fake queue reading, and the class's own constants rather than numbers copied from them
 * (a test that hard-codes 40 keeps passing when the constant changes to 4).
 *
 * `DecoySendPairingTest` covers the other half — that the pairing seam actually *consults* this, at
 * the top of every send, and that the drain deliberately does not.
 */
class CoverPressureTest {

    private var now = 1_000_000L
    private var queued = 0L

    private fun pressure() = CoverPressure(queuedBytes = { queued }, nowMs = { now })

    @Test
    fun `a quiet socket does not shed cover`() {
        val pressure = pressure()
        repeat(CoverPressure.RATE_FRAMES - 1) { pressure.recordFrame() }
        assertFalse("cover was dropped with an empty queue and a low send rate", pressure.yielding())
    }

    @Test
    fun `a backed-up outbound queue sheds cover`() {
        // MECHANISM 1. OkHttp buffers frames for its writer thread and refuses — then CLOSES the
        // connection — once the buffer would pass 16 MiB, so a cover frame added to a queue that is
        // backing up is capacity the next REAL frame may need. The watermark is three orders of
        // magnitude below the cap on purpose: cover yields long before anything is refused.
        val pressure = pressure()
        queued = CoverPressure.QUEUE_WATERMARK_BYTES
        assertFalse("cover yielded at the watermark rather than above it", pressure.yielding())
        queued = CoverPressure.QUEUE_WATERMARK_BYTES + 1
        assertTrue("cover kept filling an outbound queue that is already backing up", pressure.yielding())
    }

    @Test
    fun `a relay rate_limited sheds cover immediately`() {
        // MECHANISM 2, reactively. The relay's `rate_limited` carries no message id, which is why a
        // headroom policy was ruled unsound — but yielding needs no id and no limit, only the event.
        val pressure = pressure()
        assertFalse(pressure.yielding())
        pressure.relayRateLimited()
        assertTrue("cover kept spending a budget the relay has just said is exhausted", pressure.yielding())
    }

    @Test
    fun `a high recent send rate sheds cover`() {
        // MECHANISM 2, prospectively — and without knowing what the relay's limit is.
        val pressure = pressure()
        repeat(CoverPressure.RATE_FRAMES - 1) { pressure.recordFrame() }
        assertFalse("the meter tripped early", pressure.yielding())
        pressure.recordFrame()
        assertTrue("the meter did not trip on a full window of frames", pressure.yielding())
    }

    @Test
    fun `the rate window SLIDES - frames spread out do not trip it`() {
        // A tumbling counter would reset on a boundary and let twice the threshold through across
        // two adjacent windows. Spread the same number of frames over more than the window and the
        // sliding meter must stay quiet.
        val pressure = pressure()
        repeat(CoverPressure.RATE_FRAMES * 2) {
            pressure.recordFrame()
            now += CoverPressure.RATE_WINDOW_MS / CoverPressure.RATE_FRAMES + 100
        }
        assertFalse("a sustained but slow send rate shed cover", pressure.yielding())
    }

    @Test
    fun `the rate window slides ACROSS a boundary a tumbling counter would reset on`() {
        val pressure = pressure()
        // Half a window's frames just before an arbitrary boundary…
        repeat(CoverPressure.RATE_FRAMES / 2) { pressure.recordFrame() }
        now += CoverPressure.RATE_WINDOW_MS / 2
        // …and half just after it. A tumbling counter sees two half-full windows; the truth is a
        // full window's worth of frames inside RATE_WINDOW_MS.
        repeat(CoverPressure.RATE_FRAMES / 2) { pressure.recordFrame() }
        assertTrue("the meter tumbles instead of sliding", pressure.yielding())
    }

    @Test
    fun `a trip stays OFF for the whole window, and does not stutter`() {
        // R-U3-3: a condition that prevents cover must produce a consistent state for as long as it
        // lasts. One over-watermark reading takes cover off for OFF_WINDOW_MS even though the queue
        // drains on the very next send.
        val pressure = pressure()
        queued = CoverPressure.QUEUE_WATERMARK_BYTES + 1
        assertTrue(pressure.yielding())
        queued = 0

        var checks = 0
        while (checks < 50) {
            now += CoverPressure.OFF_WINDOW_MS / 100
            assertTrue(
                "cover came back inside the off-window — one pressure event produced a stutter " +
                    "instead of a uniform gap",
                pressure.yielding(),
            )
            checks++
        }
    }

    @Test
    fun `the window expires and cover resumes once the pressure is gone`() {
        val pressure = pressure()
        pressure.relayRateLimited()
        assertTrue(pressure.yielding())
        now += CoverPressure.OFF_WINDOW_MS
        assertFalse(
            "cover never came back — a transient pressure event turned it off permanently",
            pressure.yielding(),
        )
    }

    @Test
    fun `re-checking inside the window neither extends it nor re-reads the socket`() {
        // The window is one decision, not a rolling one: polling it must not push the expiry out.
        var reads = 0
        val pressure = CoverPressure(queuedBytes = { reads++; queued }, nowMs = { now })
        queued = CoverPressure.QUEUE_WATERMARK_BYTES + 1
        assertTrue(pressure.yielding())
        val readsAtTrip = reads

        queued = 0
        repeat(10) {
            now += CoverPressure.OFF_WINDOW_MS / 20
            assertTrue(pressure.yielding())
        }
        assertEquals("the socket is polled while cover is already off", readsAtTrip, reads)

        // Expiry is measured from the TRIP, not from the last check.
        now += CoverPressure.OFF_WINDOW_MS / 2
        assertFalse("polling pushed the window's expiry out", pressure.yielding())
    }

    @Test
    fun `a throwing queue reading yields instead of escaping into the send path`() {
        // The real send has already happened when this is consulted, so a throw escaping here would
        // reach MessagingCoordinator's runCatching and mark a DELIVERED message FAILED — cover
        // traffic corrupting the state of a send it must not be able to touch. It fails toward
        // yielding, which costs one cover frame and nothing else.
        val pressure = CoverPressure(queuedBytes = { throw IllegalStateException("socket") }, nowMs = { now })
        assertTrue("a failed pressure reading did not fail toward yielding", pressure.yielding())
    }

    @Test
    fun `the clock may be negative - a monotonic source is not a wall clock`() {
        // System.nanoTime's origin is arbitrary and routinely negative on a fresh boot. An `offUntil`
        // initialised to 0 would then read as "suppressed until later", and cover would be off for
        // the whole first stretch of every session on such a device.
        now = -5_000_000L
        val pressure = pressure()
        assertFalse("a negative monotonic clock shed cover with no pressure at all", pressure.yielding())
        pressure.relayRateLimited()
        assertTrue(pressure.yielding())
        now += CoverPressure.OFF_WINDOW_MS
        assertFalse(pressure.yielding())
    }

    @Test
    fun `the thresholds are the generous ones the ruling asked for`() {
        // Not style: the maintainer ruling is "do not compute exact remaining capacity or try to
        // spend the last safe slot". These pin the two numbers that could be quietly tuned back
        // toward the boundary, against the two caps they are generous with respect to.
        assertTrue(
            "the queue watermark is no longer a LOW watermark against OkHttp's 16 MiB cap",
            CoverPressure.QUEUE_WATERMARK_BYTES <= 16L * 1024 * 1024 / 100,
        )
        assertTrue(
            "the send-rate threshold no longer leaves most of the relay's nominal 100/min budget " +
                "free for real sends",
            CoverPressure.RATE_FRAMES <= 50,
        )
        assertTrue(
            "the off-window is shorter than the relay's own per-minute bucket, so a trip can " +
                "expire while the pressure that caused it is still there",
            CoverPressure.OFF_WINDOW_MS >= 60_000,
        )
    }
}
