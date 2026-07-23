// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.VaultCapacityException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2c: the vault contact-delete seal ([ZitroneApp.deleteContactAtomically]) maps its mutate +
 * [com.zitrone.app.crypto.vault.VaultRuntime.flushBeforeAck] to the
 * [com.zitrone.app.data.ConversationRepository.deleteContactDurably] contract through the extracted
 * [sealDurableOrFalse] — which rethrows a [CancellationException] BEFORE its `catch (Throwable) ->
 * false`, so a forced logout / revocation tearing down the session scope mid-delete UNWINDS
 * cooperatively instead of being folded into an "unconfirmed durable" false.
 *
 * Round 4 replaces the previous vacuous test (which drove its OWN seal into `deleteContactDurably`
 * and never touched the lambda's catch-ordering — the fix it claimed to guard) with direct coverage
 * of the real production code path: `sealDurableOrFalse` is exactly the try/catch the seal lambda
 * now runs, extracted top-level so it is host-testable without a live SessionContainer. These cases
 * pin the catch-ORDERING: were the two catches reversed, the cancellation case would return false
 * instead of throwing.
 */
class DeleteSealCancellationTest {

    @Test
    fun `a committed seal returns true`() {
        assertTrue(sealDurableOrFalse { /* mutate + flush succeeded */ })
    }

    @Test
    fun `a CancellationException is rethrown, never folded to false`() {
        // The property the atomicity fix depends on: cooperative cancellation escapes the seal so
        // the coroutine machinery unwinds a teardown, rather than being caught as a false.
        assertThrows(CancellationException::class.java) {
            sealDurableOrFalse { throw CancellationException("session scope cancelled mid-delete") }
        }
    }

    @Test
    fun `a closed-runtime IllegalStateException degrades to an honest false`() {
        // runtime.mutate/flushBeforeAck throw IllegalStateException("closed") on a teardown race;
        // caught as "unconfirmed durable" false (never a crash, never a rollback).
        assertFalse(sealDurableOrFalse { throw IllegalStateException("vault runtime closed") })
    }

    @Test
    fun `a full-vault VaultCapacityException degrades to an honest false`() {
        // VaultCapacityException IS an IllegalStateException — it must still land in the Throwable
        // arm (false), NOT escape like a cancellation.
        assertFalse(sealDurableOrFalse { throw VaultCapacityException("vault slot full") })
    }
}
