// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.LemonDropRedeemer
import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.LemonDropVeil
import com.zitrone.app.data.PendingLemonDrop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-D2b's LemonDrop re-gate: the probe fires only once the app is unlocked. A
 * scan while locked is QUEUED (single slot, latest-wins) behind
 * [LemonDropVeil.Locked] and NOT fetched/decrypted; the queued id probes on
 * unlock, over the SAME code path as a live-session scan. The probe is injected
 * so this drives the orchestration without a device (no fetch, no crypto). The
 * pre-existing stale-token guard is preserved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LemonDropVeilControllerTest {

    private val pending = PendingLemonDrop(
        qrId = "q",
        text = "hi",
        senderLabel = "A",
        senderVerified = true,
        burnTokenBase64 = "",
        usedOneTimePrekeyId = null,
    )

    private val sealed = LemonDropRedeemer.ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)

    @Test
    fun `a scan while unlocked probes immediately`() = runTest {
        val calls = mutableListOf<String>()
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { true },
            probe = { qrId -> calls += qrId; sealed },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.onScan("q1")
        advanceUntilIdle()

        assertEquals(listOf("q1"), calls)
        assertEquals(LemonDropVeil.Advocacy(LemonDropScanOutcome.SEALED), controller.veil.value)
    }

    @Test
    fun `a scan while locked queues the id and raises Locked without probing`() = runTest {
        val calls = mutableListOf<String>()
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { false },
            probe = { qrId -> calls += qrId; sealed },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.onScan("q1")
        advanceUntilIdle()

        assertTrue("no fetch/decrypt while locked", calls.isEmpty())
        assertEquals(LemonDropVeil.Locked, controller.veil.value)
    }

    @Test
    fun `unlocking after a locked scan fires exactly one probe with the queued id`() = runTest {
        val calls = mutableListOf<String>()
        var unlocked = false
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { unlocked },
            probe = { qrId -> calls += qrId; LemonDropRedeemer.ProbeResult.ReadyToOpen(pending) },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.onScan("q1")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())

        unlocked = true
        controller.onUnlocked()
        advanceUntilIdle()

        assertEquals(listOf("q1"), calls)
        assertEquals(LemonDropVeil.AwaitUnlock(pending), controller.veil.value)
    }

    @Test
    fun `a second locked scan supersedes the first (latest-wins)`() = runTest {
        val calls = mutableListOf<String>()
        var unlocked = false
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { unlocked },
            probe = { qrId -> calls += qrId; sealed },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.onScan("q1")
        controller.onScan("q2")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())

        unlocked = true
        controller.onUnlocked()
        advanceUntilIdle()

        assertEquals("only the latest queued scan probes", listOf("q2"), calls)
    }

    @Test
    fun `onUnlocked with no queued scan does nothing`() = runTest {
        val calls = mutableListOf<String>()
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { true },
            probe = { qrId -> calls += qrId; sealed },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.onUnlocked()
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
        assertNull(controller.veil.value)
    }

    @Test
    fun `dismiss drops a queued scan so a later unlock cannot revive it`() = runTest {
        val calls = mutableListOf<String>()
        var unlocked = false
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { unlocked },
            probe = { qrId -> calls += qrId; sealed },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.onScan("q1")
        controller.dismiss()
        assertNull(controller.veil.value)

        unlocked = true
        controller.onUnlocked()
        advanceUntilIdle()

        assertTrue("a dismissed scan must not probe on unlock", calls.isEmpty())
        assertNull(controller.veil.value)
    }

    @Test
    fun `a stale probe does not clobber a newer scan`() = runTest {
        val gates = mutableMapOf<String, CompletableDeferred<LemonDropRedeemer.ProbeResult>>()
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { true },
            probe = { qrId -> gates.getValue(qrId).await() },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        gates["q1"] = CompletableDeferred()
        gates["q2"] = CompletableDeferred()

        // Two live-session scans in flight; q2 supersedes q1 (token bumped).
        controller.onScan("q1")
        controller.onScan("q2")
        advanceUntilIdle()

        // The newer scan resolves first and wins the veil.
        gates.getValue("q2").complete(LemonDropRedeemer.ProbeResult.ReadyToOpen(pending))
        advanceUntilIdle()
        assertEquals(LemonDropVeil.AwaitUnlock(pending), controller.veil.value)

        // The stale q1 probe lands late and must NOT overwrite the current veil.
        gates.getValue("q1").complete(sealed)
        advanceUntilIdle()
        assertEquals(LemonDropVeil.AwaitUnlock(pending), controller.veil.value)
    }

    @Test
    fun `clearDelivered clears only a Delivered veil`() = runTest {
        val controller = LemonDropVeilController(
            scope = backgroundScope,
            isUnlocked = { true },
            probe = { sealed },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.veil.value = LemonDropVeil.Advocacy(LemonDropScanOutcome.SEALED)
        controller.clearDelivered()
        assertEquals(LemonDropVeil.Advocacy(LemonDropScanOutcome.SEALED), controller.veil.value)

        controller.veil.value = LemonDropVeil.Delivered("secret", "A", true)
        controller.clearDelivered()
        assertNull(controller.veil.value)
    }
}
