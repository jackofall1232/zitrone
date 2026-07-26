// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APPLY-ONCE FOR THE BURN OUTCOME (0.9.2 Unit W-B).
 *
 * The wipe runs on the process scope under `NonCancellable`, so it outlives the composition that
 * started it. An Activity recreation mid-wipe must neither LOSE the outcome nor apply it TWICE.
 *
 * These assertions run against the PRODUCTION coordinator, not a stand-in — the point of extracting
 * it. A test that re-implements claim-and-clear proves only that the test can do it.
 */
class BurnCompletionCoordinatorTest {

    @Test
    fun `nothing pending until an outcome is signalled`() {
        val c = BurnCompletionCoordinator()
        assertNull(c.snapshot())
        assertNull(c.pending.value)
    }

    /**
     * SNAPSHOT DOES NOT CONSUME. A composition about to be destroyed must not swallow an outcome it
     * will never render.
     *
     * MUTATION UNIQUELY CAUGHT: making `snapshot()` clear the pending value.
     */
    @Test
    fun `snapshot does not consume the outcome`() {
        val c = BurnCompletionCoordinator()
        c.signal(BurnCompletion.Wiped)

        assertSame(BurnCompletion.Wiped, c.snapshot())
        assertSame("a second reader must still see it", BurnCompletion.Wiped, c.snapshot())
    }

    /** A claim consumes it, and only the claimant may render. */
    @Test
    fun `claim consumes exactly once`() {
        val c = BurnCompletionCoordinator()
        c.signal(BurnCompletion.Failed)

        assertTrue("the first claimant wins", c.claim(BurnCompletion.Failed))
        assertFalse("a second claim of the same outcome must lose", c.claim(BurnCompletion.Failed))
        assertNull(c.snapshot())
    }

    /**
     * THE RACE THIS CLASS EXISTS FOR: two compositions alive at once (the old one not yet destroyed,
     * the new one already composed) both observe the pending outcome and both try to apply it.
     * EXACTLY ONE may win — a double-apply would route to onboarding twice, or render a uniform
     * failure over a completed reset.
     *
     * MUTATION UNIQUELY CAUGHT: replacing the CAS with a `value == snapshot` check followed by a
     * separate `value = null` — both claimants pass the check before either clears.
     */
    @Test
    fun `two concurrent claimants produce exactly one winner`() {
        repeat(200) {
            val c = BurnCompletionCoordinator()
            c.signal(BurnCompletion.Wiped)

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val winners = AtomicInteger(0)

            repeat(2) {
                Thread {
                    start.await()
                    if (c.claim(BurnCompletion.Wiped)) winners.incrementAndGet()
                    done.countDown()
                }.start()
            }

            start.countDown()
            assertTrue("both claimants must finish", done.await(5, TimeUnit.SECONDS))
            assertEquals("exactly one claimant may apply the outcome", 1, winners.get())
        }
    }

    /**
     * A recreation mid-wipe: the composition that started the burn is gone, and a NEW one observes an
     * outcome signalled while it did not exist.
     *
     * MUTATION UNIQUELY CAUGHT: clearing the pending outcome when a composition leaves, rather than
     * on claim.
     */
    @Test
    fun `an outcome signalled with no live claimant survives for the next one`() {
        val c = BurnCompletionCoordinator()
        c.signal(BurnCompletion.Wiped)
        // ... the composition that started the wipe is destroyed here; nobody claimed ...
        assertSame("the outcome must still be pending for the new composition", BurnCompletion.Wiped, c.snapshot())
        assertTrue(c.claim(BurnCompletion.Wiped))
    }

    /**
     * A stale claim must not consume a NEWER outcome. Claiming is keyed on the exact snapshot, so a
     * composition that read `Failed`, stalled, and woke after a later `Wiped` landed cannot render its
     * stale verdict — nor silently discard the live one.
     *
     * MUTATION UNIQUELY CAUGHT: claiming unconditionally (`value = null; return true`).
     */
    @Test
    fun `a stale claim neither renders nor discards a newer outcome`() {
        val c = BurnCompletionCoordinator()
        c.signal(BurnCompletion.Failed)
        val stale = c.snapshot()!!
        c.signal(BurnCompletion.Wiped)

        assertFalse("the stale claimant must lose", c.claim(stale))
        assertSame("and the newer outcome must survive intact", BurnCompletion.Wiped, c.snapshot())
    }
}
