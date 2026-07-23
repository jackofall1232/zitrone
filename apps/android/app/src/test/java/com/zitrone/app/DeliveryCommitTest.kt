// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.LemonDropRedeemer.DeliveryCommit
import com.zitrone.app.data.classifyDeliveryCommit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * D2c round 10 (Codex) — the lemon-drop delivery-commit split. The load-bearing distinction:
 * a flush failure AFTER the prekey removal applied is APPLIED_UNCONFIRMED (the removal is
 * scheduled — possibly already sealed — so a rescan could never decrypt the drop again; the
 * caller MUST render), never a false "unapplied" that discards the plaintext and promises a
 * rescan that would lose the message forever. Only a consume() throw (closed-runtime teardown,
 * nothing applied) is NOT_APPLIED.
 */
class DeliveryCommitTest {

    @Test
    fun `applied consume with confirmed flush is DURABLE`() = runTest {
        assertEquals(
            DeliveryCommit.DURABLE,
            classifyDeliveryCommit(consume = {}, flush = {}),
        )
    }

    @Test
    fun `flush failure after an applied consume is APPLIED_UNCONFIRMED — never unapplied`() = runTest {
        var consumed = false
        assertEquals(
            DeliveryCommit.APPLIED_UNCONFIRMED,
            classifyDeliveryCommit(
                consume = { consumed = true },
                flush = { throw IOException("reseal not durable") },
            ),
        )
        assertEquals("the consumption really applied first", true, consumed)
    }

    @Test
    fun `a consume throw (closed runtime) is NOT_APPLIED and never flushes`() = runTest {
        var flushed = false
        assertEquals(
            DeliveryCommit.NOT_APPLIED,
            classifyDeliveryCommit(
                consume = { throw IllegalStateException("closed") },
                flush = { flushed = true },
            ),
        )
        assertEquals("no flush for an unapplied consume", false, flushed)
    }

    @Test
    fun `cancellation propagates from either phase`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                classifyDeliveryCommit(
                    consume = { throw CancellationException("teardown") },
                    flush = {},
                )
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                classifyDeliveryCommit(
                    consume = {},
                    flush = { throw CancellationException("teardown") },
                )
            }
        }
    }
}
