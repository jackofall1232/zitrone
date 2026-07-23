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
        // Round 7: destroyVault (destroyVaultForAccountDeletion) now PROPAGATES a DestroyFailed when a
        // file survived the unlink, so the throw must still run releaseGate (outermost finally) — the
        // caller catches it to decide routing (see the routing-gate test below).
        assertThrows(IllegalStateException::class.java) {
            completeTerminalWipe(
                finishUi = { events += "ui" },
                destroyVault = { throw IllegalStateException("destroy failed") },
                releaseGate = { events += "release" },
            )
        }
        assertEquals("finishUi ran and the gate was released despite the destroy throw", listOf("ui", "release"), events)
    }

    // -- round 7: route to Onboarding-as-success ONLY when the destroy is CONFIRMED ----------------

    /**
     * Models MainActivity.onDeleteAccount's routing gate: run [completeTerminalWipe], and route to
     * Onboarding ONLY when it returned normally (destroy confirmed the image is gone). A destroyVault
     * throw (a surviving file) means NOT-deleted → do not claim success. Cancellation still propagates.
     */
    private fun routeAfterDelete(destroyVault: () -> Unit): String {
        val destroyed = try {
            completeTerminalWipe(finishUi = { }, destroyVault = destroyVault, releaseGate = { })
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            false
        }
        return if (destroyed) "Onboarding" else "Locked"
    }

    @Test
    fun `a confirmed destroy routes to Onboarding-as-success`() {
        assertEquals("Onboarding", routeAfterDelete(destroyVault = { /* image confirmed gone */ }))
    }

    @Test
    fun `a destroy that throws does NOT route to Onboarding — it surfaces a retry on the lock gate`() {
        // The core of the fix: destroy() verify-unlink throws when the full-crypto image survives, so
        // the app must NOT tell the user "deleted" (route to Onboarding) while the image is still on
        // disk — it routes back to the lock gate with a retry instead.
        assertEquals(
            "Locked",
            routeAfterDelete(destroyVault = { throw com.zitrone.app.crypto.vault.VaultImageException.DestroyFailed() }),
        )
    }
}
