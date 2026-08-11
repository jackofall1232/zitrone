// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.TransportState
import com.zitrone.app.net.registry.RegistryRelay
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

    // ── Registry-resolved relay (docs/design/REGISTRY_RESOLUTION.md §3.2) ──────
    // One fallback rule, per endpoint, in one place: a resolved relay's endpoints
    // win, a null relay (or a null field) keeps the legacy value. The pin gate
    // upstream guarantees a registry relay's clearnet host equals the pinned host;
    // these use it so the fixture matches what selectRelay can actually emit.

    private val registryRelay = RegistryRelay(
        id = "relay-test",
        clearnetApiBaseUrl = "https://relay.sublemonable.com",
        clearnetWsUrl = "wss://relay.sublemonable.com/ws",
        onionAddress = null,
        i2pDest = "registrydest.b32.i2p",
    )

    @Test
    fun `registry relay endpoints win over the constants on TOR and clearnet`() {
        for (state in listOf(TransportState.TOR, TransportState.CLEARNET_FALLBACK)) {
            val (client, apiBase, wsUrl) = AppContainer.transportEndpoints(state, registryRelay)
            assertNotNull(client)
            assertEquals(registryRelay.clearnetApiBaseUrl, apiBase)
            assertEquals(registryRelay.clearnetWsUrl, wsUrl)
        }
    }

    @Test
    fun `registry relay i2p destination drives the I2P endpoints`() {
        val (client, apiBase, wsUrl) = AppContainer.transportEndpoints(TransportState.I2P, registryRelay)
        assertNotNull(client)
        assertEquals("http://registrydest.b32.i2p", apiBase)
        assertEquals("ws://registrydest.b32.i2p/ws", wsUrl)
    }

    @Test
    fun `relay without an i2p dest falls back to the build-time destination`() {
        val noI2p = registryRelay.copy(i2pDest = null)
        val (_, apiBase, wsUrl) = AppContainer.transportEndpoints(TransportState.I2P, noI2p)
        assertEquals("http://${BuildConfig.RELAY_I2P_DEST}", apiBase)
        assertEquals("ws://${BuildConfig.RELAY_I2P_DEST}/ws", wsUrl)
    }
}
