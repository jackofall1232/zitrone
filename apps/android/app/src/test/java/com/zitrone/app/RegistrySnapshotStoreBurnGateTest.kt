// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.net.registry.ManifestVerifier
import com.zitrone.app.net.registry.RegistryResolver
import com.zitrone.app.net.registry.RegistrySnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The burn gate on [RegistrySnapshotStore] (blind-review P1): an in-flight registry
 * refresh runs on the app-lifetime scope, which the burn's session quiesce does NOT
 * stop — so without the gate, its `store()` could commit registry keys into
 * `zitrone_settings` during or after `wipeVaultUsePreferences()`. Two failure modes,
 * both covered here:
 *
 *  (a) a write landing AFTER the wipe → registry residue on a burned device;
 *  (b) a write landing BETWEEN the wipe's clear and its `prefs.all.isEmpty()` proof →
 *      the burn step throws DestroyFailed AFTER the vault image step already ran.
 *
 * The tests drive the store with the exact production protocol: the wipe path sets
 * suppression FIRST (`runTerminalBurn`, or the boot-reconcile bracket around
 * `foldBootMutators`), then performs its clear while holding the gate lock (the
 * burn plan's `vault-use-preferences` action). [RegistryResolver.refresh] stands in
 * for the live refresh where the resolver's own behavior matters.
 */
class RegistrySnapshotStoreBurnGateTest {

    private val now = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli()

    private class Harness {
        val prefs = FakeSharedPreferences()
        val gateLock = Any()
        val suppressed = AtomicBoolean(false)
        val store = RegistrySnapshotStore(prefs, gateLock, suppressed::get)

        /** The production wipe protocol: suppress FIRST, then clear under the gate lock. */
        fun burnWipe() {
            suppressed.set(true)
            synchronized(gateLock) { prefs.edit().clear().commit() }
        }
    }

    @Test
    fun `a store admitted before the wipe is erased by it, stores during and after are refused`() {
        val h = Harness()
        assertTrue(h.store.store("snapshot".toByteArray(), 5))
        assertEquals(5, h.store.highWaterEpoch())

        h.burnWipe()

        // The wipe erased the admitted write...
        assertTrue(h.prefs.all.isEmpty())
        // ...and every later store is refused without writing — mode (a) closed.
        assertFalse(h.store.store("late".toByteArray(), 6))
        assertTrue(h.prefs.all.isEmpty())
        assertNull(h.store.snapshotBytes())
        assertEquals(0, h.store.highWaterEpoch())
    }

    @Test
    fun `a writer arriving mid-wipe blocks on the gate lock, then is refused - mode b`() {
        val h = Harness()
        h.suppressed.set(true)
        val writerFinished = CountDownLatch(1)
        val storeResult = AtomicBoolean(true) // fails safe if the writer never runs

        synchronized(h.gateLock) {
            // The wipe holds the gate lock; the refresh thread's store() must block on it.
            val writer = thread(start = false) {
                storeResult.set(h.store.store("racing".toByteArray(), 7))
                writerFinished.countDown()
            }
            writer.start()
            assertFalse(
                "store() must block behind the wipe's gate lock, not interleave with it",
                writerFinished.await(300, TimeUnit.MILLISECONDS),
            )
            // The wipe's clear + the step's verify both happen while the writer waits.
            h.prefs.edit().clear().commit()
            assertTrue(h.prefs.all.isEmpty())
        }

        assertTrue(writerFinished.await(5, TimeUnit.SECONDS))
        // Released from the lock, the writer observes suppression and writes NOTHING —
        // so the just-verified emptiness still holds afterwards.
        assertFalse(storeResult.get())
        assertTrue(h.prefs.all.isEmpty())
    }

    @Test
    fun `concurrent stores across a suppressed wipe never land`() {
        val h = Harness()
        val admissions = AtomicInteger(0)
        h.suppressed.set(true)

        val writers = (1..4).map {
            thread {
                repeat(50) {
                    if (h.store.store("race".toByteArray(), 1)) admissions.incrementAndGet()
                    Thread.sleep(1)
                }
            }
        }
        repeat(10) {
            synchronized(h.gateLock) { h.prefs.edit().clear().commit() }
            Thread.sleep(2)
        }
        writers.forEach { it.join() }

        assertEquals("no store may be admitted while suppression is set", 0, admissions.get())
        assertTrue(h.prefs.all.isEmpty())
    }

    @Test
    fun `suppression lifts with the wipe - a failed burn leaves caching functional`() {
        val h = Harness()
        h.burnWipe()
        assertFalse(h.store.store("during".toByteArray(), 2))
        // runTerminalBurn's finally lifts suppression (the burn failed; the install
        // may still be intact, so refresh caching must resume).
        h.suppressed.set(false)
        assertTrue(h.store.store("after".toByteArray(), 2))
        assertEquals(2, h.store.highWaterEpoch())
    }

    @Test
    fun `a live refresh meeting a running burn verifies but persists nothing`() = runBlocking {
        val h = Harness()
        val resolver = RegistryResolver(
            trustRootB64Url = RegistryTestSigner.trustRoot,
            verifier = ManifestVerifier(RegistryTestSigner.ed25519Verify, nowMs = { now }),
            snapshots = h.store,
            bootstrap = { null },
            pinnedClearnetHost = "relay.sublemonable.com",
        )
        h.suppressed.set(true)
        // The manifest is genuine and verifies — the refusal comes from the burn gate,
        // not the signature path — and refresh reports no durable cache.
        assertFalse(resolver.refresh(listOf("https://reg.example/m.json")) { RegistryTestSigner.envelope() })
        assertTrue(h.prefs.all.isEmpty())
        assertNull(h.store.snapshotBytes())
        assertEquals(0, h.store.highWaterEpoch())
    }
}
