// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * PR-D2b: [AppContainer.transportEndpoints] is the ONE state → (client, apiBase,
 * wsUrl) mapping shared by the apply-loop and the per-unlock session factory
 * (spec §2 — no duplicated mapping). These pin the endpoint selection per state
 * so a regression can't silently point a session at the wrong network.
 */
class TransportEndpointsTest {

    @Test
    fun `I2P maps to plain-http endpoints on the i2p destination`() {
        val (client, apiBase, wsUrl) = AppContainer.transportEndpoints(TransportState.I2P)
        assertNotNull(client)
        // I2P is the transport-security layer — plain http/ws by design.
        assertEquals("http://${BuildConfig.RELAY_I2P_DEST}", apiBase)
        assertEquals("ws://${BuildConfig.RELAY_I2P_DEST}/ws", wsUrl)
    }

    @Test
    fun `TOR maps to the clearnet TLS endpoints`() {
        val (client, apiBase, wsUrl) = AppContainer.transportEndpoints(TransportState.TOR)
        assertNotNull(client)
        assertEquals(AppContainer.API_BASE_URL, apiBase)
        assertEquals(AppContainer.WS_URL, wsUrl)
    }

    @Test
    fun `clearnet fallback maps to the clearnet TLS endpoints`() {
        val (client, apiBase, wsUrl) =
            AppContainer.transportEndpoints(TransportState.CLEARNET_FALLBACK)
        assertNotNull(client)
        assertEquals(AppContainer.API_BASE_URL, apiBase)
        assertEquals(AppContainer.WS_URL, wsUrl)
    }
}
