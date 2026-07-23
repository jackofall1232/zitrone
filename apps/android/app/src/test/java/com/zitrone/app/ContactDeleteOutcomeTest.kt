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
 * D2c: the vault contact-delete seal (ZitroneApp's `deleteContactAtomically`) must distinguish a
 * CLOSED-runtime mutate — whose removal NEVER touched live state, so the delete did not take and the
 * contact reappears next unlock (NOT_APPLIED) — from an APPLIED-mutate whose durable flush was
 * UNCONFIRMED, whose removal sticks and persists on the next flush (APPLIED_UNCONFIRMED).
 *
 * Round 7 corrects the flag PLACEMENT. VaultRuntime.mutate applies the block to live state FIRST,
 * then ENCODES (which can throw [VaultCapacityException]). Production now sets `mutateApplied` from
 * INSIDE the mutate block (after the removal, before encode):
 * `sealDurableOrFalse { runtime.mutate { …removal…; mutateApplied = true }; runtime.flushBeforeAck() }`.
 * So a capacity-during-encode throw — the block already mutated live state — maps to
 * APPLIED_UNCONFIRMED, not the false NOT_APPLIED the round-2 placement (flag set AFTER mutate
 * returned) produced. [runSeal] reproduces that exact shape: `mutate` receives a `markApplied`
 * callback it invokes at the point the live state is mutated, and may THEN throw (an encode
 * overflow) or return; a closed-runtime mutate throws its `check(!closed)` BEFORE calling it.
 */
class ContactDeleteOutcomeTest {

    /**
     * Mirrors the production seal: `mutate` is `runtime.mutate { …; markApplied() }` — it calls
     * [markApplied] once the removal has touched live state, and may throw AFTER (a capacity encode
     * overflow) or BEFORE (a closed runtime) that point. `flush` is `runtime.flushBeforeAck()`.
     */
    private fun runSeal(mutate: (markApplied: () -> Unit) -> Unit, flush: () -> Unit): ContactDeleteOutcome {
        var mutateApplied = false
        val durable = sealDurableOrFalse {
            mutate { mutateApplied = true }
            flush()
        }
        return contactDeleteOutcome(durable, mutateApplied)
    }

    @Test
    fun `mutate applied and flush durable is DURABLE`() {
        assertEquals(ContactDeleteOutcome.DURABLE, runSeal(mutate = { it() }, flush = { }))
    }

    @Test
    fun `mutate applied but the flush is unconfirmed is APPLIED_UNCONFIRMED (removal sticks)`() {
        // The mutate applied (markApplied ran); flushBeforeAck then throws NotDurable/IO — the crypto
        // is gone from live state and persists on the next flush.
        assertEquals(
            ContactDeleteOutcome.APPLIED_UNCONFIRMED,
            runSeal(mutate = { it() }, flush = { throw IOException("reseal not durable") }),
        )
    }

    @Test
    fun `a capacity throw DURING mutate encode is APPLIED_UNCONFIRMED, not NOT_APPLIED`() {
        // The round-7 fix. runtime.mutate applies the removal (markApplied) then ENCODES, which throws
        // VaultCapacityException — so mutate() itself throws AFTER the live state already changed. The
        // flag is set INSIDE the block, so this is APPLIED_UNCONFIRMED (removal sticks, persists once a
        // later encode fits), NEVER the false NOT_APPLIED the old after-mutate flag placement gave.
        assertEquals(
            ContactDeleteOutcome.APPLIED_UNCONFIRMED,
            runSeal(
                mutate = { markApplied ->
                    markApplied()
                    throw VaultCapacityException("state exceeds region after removal encode")
                },
                flush = { error("flush must never run when mutate's encode threw") },
            ),
        )
    }

    @Test
    fun `a closed-runtime mutate is NOT_APPLIED (the delete did not take)`() {
        // runtime.mutate throws its check(!closed) BEFORE applying the removal — markApplied is never
        // called, so this is a lost delete, NOT an applied-but-unconfirmed removal.
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
