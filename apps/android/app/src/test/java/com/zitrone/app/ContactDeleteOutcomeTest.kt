// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.VaultCapacityException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * D2c round 2: the vault contact-delete seal (ZitroneApp's `deleteContactAtomically`) must
 * distinguish a CLOSED-runtime mutate — whose removal NEVER touched live state, so the delete did
 * not take and the contact reappears next unlock — from an APPLIED-mutate-but-unconfirmed-flush,
 * whose removal sticks and persists on the next flush. The round-2 containment folded BOTH into the
 * same `false`, silently losing the closed-runtime delete while reporting it as an applied removal.
 *
 * The production seal is `sealDurableOrFalse { runtime.mutate {…}; mutateApplied = true;
 * runtime.flushBeforeAck() }`, whose Boolean + the `mutateApplied` flag map through
 * [contactDeleteOutcome]. [runSeal] reproduces that exact shape (mutate stays INSIDE the containment,
 * the flag is set only after mutate returns) so the closed-vs-unconfirmed distinction is pinned
 * without a live SessionContainer.
 */
class ContactDeleteOutcomeTest {

    /** Mirrors the production seal lambda exactly. */
    private fun runSeal(mutate: () -> Unit, flush: () -> Unit): ContactDeleteOutcome {
        var mutateApplied = false
        val durable = sealDurableOrFalse {
            mutate()
            mutateApplied = true
            flush()
        }
        return contactDeleteOutcome(durable, mutateApplied)
    }

    @Test
    fun `mutate applied and flush durable is DURABLE`() {
        assertEquals(ContactDeleteOutcome.DURABLE, runSeal(mutate = { }, flush = { }))
    }

    @Test
    fun `mutate applied but the flush is unconfirmed is APPLIED_UNCONFIRMED (removal sticks)`() {
        // flushBeforeAck throws NotDurable/IO/capacity AFTER the mutate applied the removal — the
        // crypto is gone from live state and persists on the next flush.
        assertEquals(
            ContactDeleteOutcome.APPLIED_UNCONFIRMED,
            runSeal(mutate = { }, flush = { throw IOException("reseal not durable") }),
        )
        assertEquals(
            ContactDeleteOutcome.APPLIED_UNCONFIRMED,
            runSeal(mutate = { }, flush = { throw VaultCapacityException("vault full") }),
        )
    }

    @Test
    fun `a closed-runtime mutate is NOT_APPLIED (the delete did not take)`() {
        // runtime.mutate throws its check(!closed) BEFORE applying the removal — the flag never
        // flips, so this is a lost delete, NOT an applied-but-unconfirmed removal.
        assertEquals(
            ContactDeleteOutcome.NOT_APPLIED,
            runSeal(
                mutate = { throw IllegalStateException("vault runtime is closed") },
                flush = { error("flush must never run when the mutate threw") },
            ),
        )
    }

    @Test
    fun `a CancellationException from the mutate still propagates (cooperative teardown)`() {
        // The round-2/round-4 invariant: cancellation escapes the seal so the coroutine unwinds a
        // teardown, rather than being folded into any outcome.
        assertThrows(CancellationException::class.java) {
            runSeal(
                mutate = { throw CancellationException("session scope cancelled mid-delete") },
                flush = { },
            )
        }
    }

    @Test
    fun `the pure mapping is exhaustive`() {
        assertEquals(ContactDeleteOutcome.DURABLE, contactDeleteOutcome(durable = true, mutateApplied = true))
        assertEquals(ContactDeleteOutcome.APPLIED_UNCONFIRMED, contactDeleteOutcome(durable = false, mutateApplied = true))
        assertEquals(ContactDeleteOutcome.NOT_APPLIED, contactDeleteOutcome(durable = false, mutateApplied = false))
    }
}
