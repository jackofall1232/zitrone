// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.TransportState
import com.zitrone.app.net.I2pProber
import com.zitrone.app.net.TransportResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision logic for the fixed I2P -> Tor -> clearnet chain. Virtual time
 * throughout — the 20s background poll elapses instantly. The readiness probe
 * and the router-installed checks are injected fakes, so no live router (and no
 * android.content.Context) is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportResolverTest {

    /** Fake prober whose verdict can flip mid-test to exercise promotion/demotion. */
    private class FakeProber(
        var readiness: I2pProber.Readiness,
        var proxyListening: Boolean = true,
    ) : I2pProber {
        var calls = 0
        override suspend fun probe(host: String, port: Int, dest: String, timeoutMs: Int): I2pProber.Readiness {
            calls++
            return readiness
        }
        override suspend fun proxyUp(host: String, port: Int, timeoutMs: Int): Boolean = proxyListening
    }

    private val dest = "abcdefghijklmnop.b32.i2p"

    // Longer than the resolver's 20s poll interval, so a promotion poll fires.
    private val pastPollInterval = 25_000L

    private fun TestScope.resolver(
        inputs: MutableStateFlow<TransportResolver.Inputs>,
        prober: I2pProber,
        relayI2pDest: String = dest,
        i2pdInstalled: Boolean = true,
        orbotInstalled: Boolean = true,
    ) = TransportResolver(
        relayI2pDest = relayI2pDest,
        i2pProxyHost = "127.0.0.1",
        inputs = inputs,
        isI2pdInstalled = { i2pdInstalled },
        isOrbotInstalled = { orbotInstalled },
        prober = prober,
        scope = backgroundScope,
    ).also { it.start() }

    private fun inputs(i2p: Boolean = true, tor: Boolean = false) =
        MutableStateFlow(TransportResolver.Inputs(i2pEnabled = i2p, torEnabled = tor))

    @Test
    fun `ready I2P router wins the chain`() = runTest {
        val r = resolver(inputs(i2p = true, tor = true), FakeProber(I2pProber.Readiness.READY))
        runCurrent()
        assertEquals(TransportState.I2P, r.state.value)
    }

    @Test
    fun `empty destination is never I2P and skips the probe`() = runTest {
        val prober = FakeProber(I2pProber.Readiness.READY)
        val r = resolver(inputs(i2p = true, tor = false), prober, relayI2pDest = "")
        runCurrent()
        assertEquals(TransportState.CLEARNET_FALLBACK, r.state.value)
        assertEquals("empty dest must short-circuit before probing", 0, prober.calls)
    }

    @Test
    fun `I2P disabled is never I2P even when the router is ready`() = runTest {
        val prober = FakeProber(I2pProber.Readiness.READY)
        val r = resolver(inputs(i2p = false, tor = false), prober)
        runCurrent()
        assertEquals(TransportState.CLEARNET_FALLBACK, r.state.value)
        assertEquals(0, prober.calls)
    }

    @Test
    fun `no i2pd installed is never I2P`() = runTest {
        val prober = FakeProber(I2pProber.Readiness.READY)
        val r = resolver(inputs(i2p = true, tor = false), prober, i2pdInstalled = false)
        runCurrent()
        assertEquals(TransportState.CLEARNET_FALLBACK, r.state.value)
        assertEquals(0, prober.calls)
    }

    @Test
    fun `candidate not ready falls through to Tor when enabled`() = runTest {
        val r = resolver(inputs(i2p = true, tor = true), FakeProber(I2pProber.Readiness.TUNNELS_NOT_READY))
        runCurrent()
        assertEquals(TransportState.TOR, r.state.value)
    }

    @Test
    fun `candidate not ready falls through to clearnet when Tor is off`() = runTest {
        val r = resolver(inputs(i2p = true, tor = false), FakeProber(I2pProber.Readiness.TUNNELS_NOT_READY))
        runCurrent()
        assertEquals(TransportState.CLEARNET_FALLBACK, r.state.value)
    }

    @Test
    fun `candidate not ready falls through to Tor but PROXY_DOWN keeps polling to promotion`() = runTest {
        val prober = FakeProber(I2pProber.Readiness.PROXY_DOWN)
        val r = resolver(inputs(i2p = true, tor = true), prober)
        runCurrent()
        // Router down at boot — serve Tor now, keep polling.
        assertEquals(TransportState.TOR, r.state.value)

        // The router finishes building tunnels; the next background poll promotes.
        prober.readiness = I2pProber.Readiness.READY
        advanceTimeBy(pastPollInterval)
        runCurrent()
        assertEquals(TransportState.I2P, r.state.value)
    }

    @Test
    fun `clearnet fallback is promoted to I2P once tunnels come up`() = runTest {
        val prober = FakeProber(I2pProber.Readiness.TUNNELS_NOT_READY)
        val r = resolver(inputs(i2p = true, tor = false), prober)
        runCurrent()
        assertEquals(TransportState.CLEARNET_FALLBACK, r.state.value)

        prober.readiness = I2pProber.Readiness.READY
        advanceTimeBy(pastPollInterval)
        runCurrent()
        assertEquals(TransportState.I2P, r.state.value)
    }

    @Test
    fun `router vanishing mid-session demotes to fallback then re-promotes on restart`() = runTest {
        val prober = FakeProber(I2pProber.Readiness.READY)
        val r = resolver(inputs(i2p = true, tor = true), prober)
        runCurrent()
        assertEquals(TransportState.I2P, r.state.value)

        // The user stops i2pd: the proxy port goes dead. The next liveness check
        // (30s cadence) demotes to the Tor fallback.
        prober.proxyListening = false
        prober.readiness = I2pProber.Readiness.PROXY_DOWN
        advanceTimeBy(35_000L)
        runCurrent()
        assertEquals(TransportState.TOR, r.state.value)

        // i2pd comes back with tunnels built: background polling re-promotes.
        prober.proxyListening = true
        prober.readiness = I2pProber.Readiness.READY
        advanceTimeBy(pastPollInterval)
        runCurrent()
        assertEquals(TransportState.I2P, r.state.value)
    }

    @Test
    fun `flipping the I2P toggle on re-resolves and promotes to I2P`() = runTest {
        val flags = inputs(i2p = false, tor = false)
        val r = resolver(flags, FakeProber(I2pProber.Readiness.READY))
        runCurrent()
        assertEquals(TransportState.CLEARNET_FALLBACK, r.state.value)

        // collectLatest cancels the old resolution and re-runs with the new input.
        flags.value = TransportResolver.Inputs(i2pEnabled = true, torEnabled = false)
        runCurrent()
        assertEquals(TransportState.I2P, r.state.value)
    }
}
