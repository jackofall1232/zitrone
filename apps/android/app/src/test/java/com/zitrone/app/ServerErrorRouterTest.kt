// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BEHAVIOURAL cover for the relay-error routing — the harness both blind reviewers asked for in
 * 0.10.1 review round 2.
 *
 * Until this existed the two decisions below were pinned only by matching source text inside
 * `MessagingCoordinator.onServerError`, because nothing in the suite can construct that class. A
 * source tripwire cannot catch a behavioural regression that keeps the same substrings, cannot catch
 * a listener that is never installed, and — the argument that settled it — **did not catch round 2's
 * P1**. These tests exercise the real function.
 */
class ServerErrorRouterTest {

    private class Calls {
        val order = mutableListOf<String>()
        val failed = mutableListOf<String>()
        fun yieldCover() { order += "yield" }
        fun failByRelay(id: String) { order += "fail"; failed += id }
    }

    private fun route(code: String, messageId: String?): Calls = Calls().also {
        routeServerError(code, messageId, it::yieldCover, it::failByRelay)
    }

    @Test
    fun `an attributed rate_limited both yields cover and fails that message, yield first`() {
        val c = route(ERROR_RATE_LIMITED, "m1")

        // Order is the property, not an incidental: cover must stand down before anything else runs,
        // and a reader of this list should be able to see the two decisions are separate.
        assertEquals(listOf("yield", "fail"), c.order)
        assertEquals(listOf("m1"), c.failed)
    }

    @Test
    fun `an UNATTRIBUTABLE rate_limited still yields cover`() {
        // THE CASE THAT MATTERS MOST. The relay cannot always name the message — the id is echoed
        // only for a well-formed UUID, and is `omitempty`, so absent and empty both arrive as null.
        // The budget is contended either way, so cover must still stand down. Making the yield
        // conditional on the id would drop the one reactive signal the relay gives us in exactly the
        // case it is most likely to arrive.
        val c = route(ERROR_RATE_LIMITED, null)

        assertEquals(listOf("yield"), c.order)
        assertEquals(emptyList<String>(), c.failed)
    }

    @Test
    fun `an attributed NON-rate-limited error fails the message without touching cover`() {
        // store_failed and bad_envelope attribute the same way, and neither says anything about the
        // send budget — yielding cover for them would take cover off for an unrelated reason.
        for (code in listOf("store_failed", "bad_envelope")) {
            val c = route(code, "m2")
            assertEquals("$code must not yield cover", listOf("fail"), c.order)
            assertEquals(listOf("m2"), c.failed)
        }
    }

    @Test
    fun `an unattributable non-rate-limited error does nothing at all`() {
        // Nothing to attribute and no budget signal: the send timeout is what bounds this case, not
        // a guess about which message it was.
        val c = route("internal", null)

        assertEquals(emptyList<String>(), c.order)
    }

    @Test
    fun `an empty id is not treated as a message whose id is empty`() {
        // WsClient normalises absent/empty to null at the wire boundary, so the router should never
        // see "". Asserted here anyway: if that normalisation is ever moved or lost, this documents
        // that "" reaching the router would attribute to a message id of "" rather than no-oping —
        // the router itself only checks for null, deliberately, because one normalisation point is
        // better than several.
        val c = route(ERROR_RATE_LIMITED, "")

        assertEquals(listOf("yield", "fail"), c.order)
        assertEquals(
            "the router trusts WsClient's normalisation; if this ever changes, fix it at the wire",
            listOf(""),
            c.failed,
        )
    }
}
