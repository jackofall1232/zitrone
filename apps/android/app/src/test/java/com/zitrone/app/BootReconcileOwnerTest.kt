// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.ResidueSweepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BOOT-OWNER LIFECYCLE CONTRACT (0.9.2 Unit W-A).
 *
 * ── WHY THIS SUITE EXISTS, AND A CORRECTION ──────────────────────────────────────────────────────
 * Two HIGHs in the parent unit lived in this layer, and I reported them as "inspection-verified only —
 * this project has no test infrastructure for lifecycle". **That was wrong, and a five-second check
 * of the build file refutes it:** `kotlinx-coroutines-test` and `robolectric` are both already
 * declared (`app/build.gradle.kts:222,224`). The contract was always testable on the host JVM; it
 * needed the scope AND the IO dispatcher injected. (Writing these tests immediately exposed that the
 * first extraction still hard-coded `Dispatchers.IO`, so the work escaped the test scheduler and
 * nothing was asserted — a green suite that verified nothing.) Only rotation-through-recomposition
 * genuinely needs Compose UI testing, which the project does not have.
 *
 * ── EVERY TEST ASSERTS ON THE DAMAGE ─────────────────────────────────────────────────────────────
 * Per the ELOOP lesson: "the CAS was claimed once" is far weaker than "a cancelled claimant cannot
 * strand a waiter", because the first passes against an implementation that strands. Each test drives
 * a real waiter or counts real destructive work, and names the mutation it uniquely catches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BootReconcileOwnerTest {

    /** Production-shaped harness: the two published signals, plus counters for real work. */
    private class Harness {
        val hold = MutableStateFlow(false)
        val done = MutableStateFlow(false)
        private val claimed = AtomicBoolean(false)
        val sweepRuns = AtomicInteger(0)
        
        fun claim(): Boolean = claimed.compareAndSet(false, true)
        fun publish(h: Boolean) {
            hold.value = h
            done.value = true
        }
    }

    /**
     * MUTATION UNIQUELY CAUGHT: dropping the CAS, so the work runs on every call. Every recreated
     * composition issues `startBootReconcile()`, so without the claim a rotation would re-run a
     * DESTRUCTIVE boot sweep. Asserts on the damage — how many times the sweep actually executed.
     */
    @Test
    fun `a second start does not re-run the destructive sweep`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()
        repeat(3) {
            runBootReconcile(
                scope = this,
                claim = h::claim,
                sweep = { h.sweepRuns.incrementAndGet(); ResidueSweepResult.SWEPT_DURABLE },
                publish = h::publish,
                ioDispatcher = io,
            )
        }
        advanceUntilIdle()

        assertEquals("the destructive sweep must run exactly once per process", 1, h.sweepRuns.get())
        assertTrue("and the single run must publish", h.done.value)
    }

    /**
     * MUTATION UNIQUELY CAUGHT: **the verdict not being carried into the published hold** (e.g.
     * `publish(false)` regardless of the sweep result). A waiter then sees a permissive hold and
     * authorises a fresh-install presentation over non-durable residue — sweep round 1's HIGH.
     *
     * CORRECTED CLAIM (round-4 review, Moonshot). This kdoc previously said it uniquely caught
     * "publishing `done` before `hold`". **It does not, and the mutation was never run to check.**
     * Verified by running it: swapping those two assignments leaves all 8 tests green. Two reasons,
     * both structural — `publish` is INJECTED by the test, so no test here can constrain production's
     * internal ordering; and `StateFlow` conflates, so a waiter resumed after a synchronous `publish`
     * reads the final value either way. That ordering genuinely does not matter for `.value` readers
     * in production, which is why nothing broke — but the header asserted coverage it never had,
     * which is this unit's own recurring failure mode reproduced inside a test header, in the very
     * suite written to satisfy "state which mutation each test uniquely catches".
     */
    @Test
    fun `a consumer released by the done signal never observes a stale hold`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()
        var observedAtRelease: Boolean? = null
        launch {
            h.done.first { it }
            observedAtRelease = h.hold.value
        }

        runBootReconcile(
            scope = this,
            claim = h::claim,
            // NON-durable: the waiter must observe the hold, never the default.
            sweep = { ResidueSweepResult.SWEPT_NOT_DURABLE },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertEquals(
            "the waiter was released while the hold still read its default — exactly how a " +
                "non-durable sweep authorises a fresh-install screen over recoverable residue",
            true,
            observedAtRelease,
        )
    }

    /**
     * MUTATION UNIQUELY CAUGHT: initialising the verdict permissively (`SWEPT_DURABLE`) instead of
     * `SWEPT_NOT_DURABLE`. A run that throws before producing a verdict must release waiters
     * WITHHOLDING onboarding. Asserts on the hold a waiter actually sees after a failed run.
     */
    @Test
    fun `a sweep that throws releases waiters fail-closed`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()

        runBootReconcile(
            scope = this,
            claim = h::claim,
            sweep = { error("simulated filesystem fault") },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertTrue("a failed boot must not release waiters permissively", h.hold.value)
        assertTrue("and must still release them", h.done.value)
    }

    /**
     * THE ROUND-2 DEFECT, AS A TEST — the one that matters most.
     *
     * MUTATION UNIQUELY CAUGHT: moving `publish` out of the `finally`. A claimant cancelled after
     * winning the CAS and before publishing leaves the claim taken with no other writer, so every
     * later consumer waits forever — a rotation-triggered brick for the life of the process.
     *
     * Cancellation is injected as a `CancellationException` from inside the reconcile body, which is
     * what a cancelled `withContext` actually raises. Asserts on the damage: a REAL waiter is driven
     * and the assertion is that IT WAS RELEASED. A test checking only `claimed == true` would pass
     * against the stranding implementation.
     */
    @Test
    fun `a claimant cancelled mid-work does not strand a waiter`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()
        var released = false
        launch {
            h.done.first { it }
            released = true
        }

        runBootReconcile(
            scope = this,
            claim = h::claim,
            // A rotation landing BEFORE the sweep can produce a verdict.
            sweep = { throw CancellationException("recreation mid-reconcile") },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertTrue(
            "a claimant cancelled before publishing MUST still release its waiters — otherwise the " +
                "claim is held forever with no other writer and every later composition blocks",
            released,
        )
        assertTrue(
            "and must release them FAIL-CLOSED: no verdict was produced, so onboarding is withheld",
            h.hold.value,
        )
    }

    /**
     * The other side of the fail-closed default, so "always hold" cannot pass as a fix: a sweep that
     * DID produce a durable verdict must not have that verdict overwritten by the initial
     * SWEPT_NOT_DURABLE. A spurious hold would strand a healthy device on the lock screen for the
     * whole process.
     *
     * NAME CORRECTED in round 1 (Codex). This was called "cancellation after a durable sweep…" and
     * performed no cancellation. Worse, that window does not exist in this shape: `publish` runs in a
     * `finally` with NO suspension point between the verdict and the publication, so a run cannot be
     * cancelled after producing a verdict and before publishing it. The test now claims only what it
     * proves — and the cancellation-before-verdict case, which IS reachable, is covered by the
     * stranding test above.
     */
    @Test
    fun `a durable verdict is never overwritten by the fail-closed default`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()
        var released = false
        launch {
            h.done.first { it }
            released = true
        }

        runBootReconcile(
            scope = this,
            claim = h::claim,
            sweep = { ResidueSweepResult.SWEPT_DURABLE },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertTrue("still released", released)
        assertFalse("the durable verdict was earned — do not withhold onboarding", h.hold.value)
    }

    /**
     * The claim survives a cancelled run, so a later attempt must NOT re-run destructive work — the
     * inverse damage of the test above, and the reason the two must be asserted separately.
     */
    @Test
    fun `a retry after a cancelled run does not re-sweep`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()

        runBootReconcile(
            scope = this,
            claim = h::claim,
            sweep = { h.sweepRuns.incrementAndGet(); ResidueSweepResult.SWEPT_DURABLE },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        runBootReconcile(
            scope = this,
            claim = h::claim,
            sweep = { h.sweepRuns.incrementAndGet(); ResidueSweepResult.SWEPT_DURABLE },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertEquals("destructive boot work must never run twice", 1, h.sweepRuns.get())
    }

    /** A healthy, durable boot must NOT hold — the hold has to be earned, not the default outcome. */
    @Test
    fun `a durable sweep publishes no hold`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()

        runBootReconcile(
            scope = this,
            claim = h::claim,
            sweep = { ResidueSweepResult.SWEPT_DURABLE },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertTrue(h.done.value)
        assertFalse("a durable sweep must not withhold onboarding", h.hold.value)
    }

    /** NO_MUTATION — the ordinary clean cold start — likewise must not hold. */
    @Test
    fun `an untouched disk publishes no hold`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val h = Harness()

        runBootReconcile(
            scope = this,
            claim = h::claim,
            sweep = { ResidueSweepResult.NO_MUTATION },
            publish = h::publish,
            ioDispatcher = io,
        )
        advanceUntilIdle()

        assertTrue(h.done.value)
        assertFalse("nothing was mutated, so nothing to withhold", h.hold.value)
    }
}
