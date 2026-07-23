// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * PR-D2b: the session-per-unlock lifecycle. [UnlockController] is factored
 * through lambdas so it drives with fakes here (a real SessionContainer needs a
 * device). These pin the contract AppContainer relies on: build-once, teardown
 * order (stop → cancel scope → publish null), fresh instance per cycle, the
 * post-publish hook (transport re-apply + queued-scan drain), and serialization
 * of an unlock racing a teardown.
 */
class UnlockControllerTest {

    private class FakeSession(val id: Int)

    private class Rig {
        val built = mutableListOf<FakeSession>()
        val scopes = mutableListOf<CoroutineScope>()
        val published = mutableListOf<FakeSession?>()
        val stopped = mutableListOf<FakeSession>()
        val log = mutableListOf<String>()
        var afterPublishCount = 0
        private var nextId = 0

        // Optional latches to freeze a build mid-flight (serialization test).
        var buildStarted: CountDownLatch? = null
        var buildGate: CountDownLatch? = null

        val controller = UnlockController<FakeSession>(
            newSessionScope = {
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
            },
            buildSession = {
                buildStarted?.countDown()
                buildGate?.await()
                FakeSession(nextId++).also {
                    built += it
                    log += "build:${it.id}"
                }
            },
            publish = {
                published += it
                log += "publish:${it?.id}"
            },
            stopSession = {
                stopped += it
                log += "stop:${it.id}"
            },
            afterPublish = {
                afterPublishCount++
                log += "after"
            },
        )
    }

    @Test
    fun `unlock builds and publishes once, a second unlock is a no-op`() {
        val rig = Rig()
        rig.controller.unlock()
        rig.controller.unlock()
        assertEquals(1, rig.built.size)
        assertEquals(listOf<FakeSession?>(rig.built[0]), rig.published)
        assertEquals(1, rig.afterPublishCount)
    }

    @Test
    fun `afterPublish runs once, after the session is published`() {
        val rig = Rig()
        rig.controller.unlock()
        assertEquals(listOf("build:0", "publish:0", "after"), rig.log)
    }

    @Test
    fun `lock stops the session, cancels its scope, then publishes null`() {
        val rig = Rig()
        rig.controller.unlock()
        val scope = rig.scopes[0]
        rig.controller.lock()
        assertEquals(listOf(rig.built[0]), rig.stopped)
        assertFalse("session scope must be cancelled on lock", scope.isActive)
        assertNull(rig.published.last())
        // Teardown order is load-bearing: stop → (cancel scope) → publish null.
        assertEquals(
            listOf("build:0", "publish:0", "after", "stop:0", "publish:null"),
            rig.log,
        )
    }

    @Test
    fun `lock with no live session is a no-op`() {
        val rig = Rig()
        rig.controller.lock()
        assertTrue(rig.stopped.isEmpty())
        assertTrue(rig.published.isEmpty())
    }

    @Test
    fun `unlock, lock, unlock builds a fresh session on a fresh scope`() {
        val rig = Rig()
        rig.controller.unlock()
        rig.controller.lock()
        rig.controller.unlock()
        assertEquals(2, rig.built.size)
        assertNotSame(rig.built[0], rig.built[1])
        assertEquals(2, rig.scopes.size)
        assertFalse("the first cycle's scope stays cancelled", rig.scopes[0].isActive)
        assertTrue("the fresh cycle's scope is live", rig.scopes[1].isActive)
        assertEquals(2, rig.afterPublishCount)
    }

    @Test
    fun `each build reads the CURRENT external state, not a construction-time capture`() {
        // AppContainer's factory derives endpoints from transportResolver.state.value
        // AT BUILD TIME (spec §2). Pin the contract at this level: the factory
        // lambda runs per unlock, so a transport change between cycles is seen
        // by the next build.
        var current = "clearnet"
        val seen = mutableListOf<String>()
        val controller = UnlockController<FakeSession>(
            newSessionScope = { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) },
            buildSession = { seen += current; FakeSession(seen.size) },
            publish = {},
            stopSession = {},
            afterPublish = {},
        )
        controller.unlock()
        controller.lock()
        current = "i2p"
        controller.unlock()
        assertEquals(listOf("clearnet", "i2p"), seen)
    }

    @Test
    fun `lockIf tears down only the expected session`() {
        val rig = Rig()
        rig.controller.unlock()
        val first = rig.built[0]
        rig.controller.lock()
        rig.controller.unlock()
        val second = rig.built[1]

        // A detached callback from the FIRST session's lifetime fires late: it
        // must not tear down the innocent successor.
        rig.controller.lockIf(first)
        assertEquals(listOf(first), rig.stopped)
        assertTrue("successor stays live", rig.scopes[1].isActive)

        // The callback bound to the live session still works.
        rig.controller.lockIf(second)
        assertEquals(listOf(first, second), rig.stopped)
    }

    @Test
    fun `unlock is refused while a terminal wipe is in progress and works after`() {
        val rig = Rig()
        rig.controller.beginTerminalWipe()
        rig.controller.unlock()
        assertTrue("no session may build over stores being wiped", rig.built.isEmpty())
        rig.controller.endTerminalWipe()
        rig.controller.unlock()
        assertEquals(1, rig.built.size)
    }

    @Test
    fun `lock waits for the cancelled session scope to drain`() {
        // Cancellation is cooperative: running work (a ratchet-persisting
        // decrypt) must finish before a successor session can build over the
        // same stores. Simulate with sleep — cancel() cannot interrupt it.
        val drained = java.util.concurrent.atomic.AtomicBoolean(false)
        var scope: CoroutineScope? = null
        val controller = UnlockController<Any>(
            newSessionScope = {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
            },
            buildSession = { Any() },
            publish = {},
            stopSession = {},
            afterPublish = {},
        )
        controller.unlock()
        val started = CountDownLatch(1)
        scope!!.launch {
            started.countDown()
            Thread.sleep(300)
            drained.set(true)
        }
        started.await()
        controller.lock()
        assertTrue("lock must wait out in-flight session work", drained.get())
    }

    @Test
    fun `the drain wait is bounded — a stuck coroutine cannot hang lock`() {
        var scope: CoroutineScope? = null
        val controller = UnlockController<Any>(
            newSessionScope = {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
            },
            buildSession = { Any() },
            publish = {},
            stopSession = {},
            afterPublish = {},
            drainTimeoutMs = 100,
        )
        controller.unlock()
        val started = CountDownLatch(1)
        scope!!.launch {
            started.countDown()
            Thread.sleep(10_000)
        }
        started.await()
        val begun = System.currentTimeMillis()
        controller.lock()
        assertTrue(
            "lock must return at the bound, not wait for the stuck coroutine",
            System.currentTimeMillis() - begun < 5_000,
        )
    }

    @Test
    fun `an unlock in progress serializes a concurrent lock`() {
        val rig = Rig()
        rig.buildStarted = CountDownLatch(1)
        rig.buildGate = CountDownLatch(1)

        val unlocker = Thread { rig.controller.unlock() }.apply { start() }
        // unlock() is now inside buildSession, holding the controller monitor.
        rig.buildStarted!!.await()

        val locker = Thread { rig.controller.lock() }.apply { start() }
        // The locker cannot acquire the monitor until unlock() releases it, so no
        // teardown may have happened yet.
        Thread.sleep(50)
        assertTrue("lock must not interleave with an in-progress unlock", rig.stopped.isEmpty())

        rig.buildGate!!.countDown()
        unlocker.join(2_000)
        locker.join(2_000)

        // lock() ran AFTER unlock() finished — it saw the built session and tore
        // it down, rather than no-opping against a not-yet-published slot.
        assertEquals(listOf(rig.built[0]), rig.stopped)
        assertNull(rig.published.last())
    }
}
