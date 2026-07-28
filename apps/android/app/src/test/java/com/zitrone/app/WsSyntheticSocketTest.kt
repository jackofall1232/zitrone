// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.decoy.WsSyntheticSocket
import com.zitrone.app.net.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production socket adapter's listener wiring.
 *
 * No socket is ever opened here: [WsClient.listener] is installed in the adapter's `init`, so the
 * whole routing table can be exercised by invoking it directly. That matters because the adapter's
 * design is mostly about what it *drops*, and a mutation sweep found the one thing it must NOT drop
 * was pinned by nothing at all.
 */
class WsSyntheticSocketTest {

    private fun wsClient() = WsClient(
        wsUrl = "wss://example.invalid/ws",
        client = OkHttpClient(),
        scope = CoroutineScope(Job()),
    )

    @Test
    fun `a rate_limited on the SYNTHETIC account reaches the shared pressure meter`() {
        // U4 review round 1, Grok F4. Without this the meter saw only the real socket's
        // rate_limited, so the relay could be throttling the account that exists solely to carry
        // cover traffic while this side kept emitting into the refusal.
        var rateLimited = 0
        val ws = wsClient()
        WsSyntheticSocket(ws) { rateLimited++ }

        ws.listener!!.onServerError("rate_limited", "slow down")

        assertEquals(1, rateLimited)
    }

    @Test
    fun `other server errors do not trip the meter`() {
        var rateLimited = 0
        val ws = wsClient()
        WsSyntheticSocket(ws) { rateLimited++ }

        ws.listener!!.onServerError("bad_request", "nope")
        ws.listener!!.onServerError("internal", "boom")

        assertEquals(0, rateLimited)
    }

    @Test
    fun `a delivery is forwarded to the session's callback`() {
        val ws = wsClient()
        val socket = WsSyntheticSocket(ws)
        val seen = mutableListOf<String>()
        socket.onDeliver = { seen += it.id }

        ws.listener!!.onMessageDeliver(envelope())

        assertEquals(listOf("cover-1"), seen)
    }

    @Test
    fun `every other inbound event is dropped without reaching anything`() {
        // The synthetic account is not a user: it has no UI, store, roster or session state, so
        // there is nothing for these to update. Dropping them is the design, not an omission — and
        // this test exists so that adding a route later is a deliberate act with a failing test
        // behind it rather than a quiet change.
        val ws = wsClient()
        val socket = WsSyntheticSocket(ws)
        var delivered = 0
        socket.onDeliver = { delivered++ }
        val l = ws.listener!!

        l.onMessageBurned("m")
        l.onMessageStored("m")
        l.onMessageDelivered("m")
        l.onTyping("someone", true)
        l.onPreKeyLow(3)
        l.onSessionRevoked()
        l.onAuthExpired()

        assertEquals("none of these is a delivery", 0, delivered)
    }

    @Test
    fun `a delivery arriving with no callback attached is silently ignored`() {
        val ws = wsClient()
        WsSyntheticSocket(ws)

        ws.listener!!.onMessageDeliver(envelope())

        assertTrue("nothing to assert but the absence of a throw", true)
    }

    private fun envelope() = MessageEnvelope(
        id = "cover-1",
        senderId = "acct-real-00000001",
        recipientId = "acct-synthetic-0001",
        ciphertext = "AAAA",
        ephemeralKey = null,
        preKeyId = null,
        messageNumber = 0,
        previousChainLength = 0,
        timestamp = "2026-07-28T10:00:00.123Z",
        ttlSeconds = 86_400,
        burnOnRead = false,
        mediaType = "text",
        version = "1",
    )
}
