// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.i2p.I2pIntegration
import com.zitrone.app.net.CertificatePinning
import com.zitrone.app.net.HttpConnectI2pProber
import com.zitrone.app.net.I2pProber
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OPT-IN live integration test against a real I2P HTTP proxy + relay, exercising
 * the REAL production classes end-to-end: [HttpConnectI2pProber] and the CONNECT
 * socket-factory OkHttp client from [CertificatePinning.buildI2pClient]. This is
 * the JVM arm of the real-condition rig (docs/TOR_ARCHITECTURE.md §7) — the same
 * i2ptunnel HTTPClient code the official Android app bundles.
 *
 * SKIPPED by default: it runs only when `I2P_TEST_DEST` is set (the local relay's
 * `.b32.i2p` destination) and a Java I2P router's HTTP proxy is listening on
 * `I2P_TEST_PROXY_HOST` (default 127.0.0.1) : `I2P_TEST_PROXY_PORT` (default
 * 4444). Under `:app:testDebugUnitTest` with no env set, JUnit's [assumeTrue]
 * marks every case skipped, so this compiles and stays out of the default run.
 *
 * NOTE: proves the proxy/tunnel semantics with the identical router codebase, NOT
 * Android-OS specifics (package detection, launch intents) — those need a
 * physical-device pass.
 */
class I2pLiveIntegrationTest {

    private val dest: String = System.getenv("I2P_TEST_DEST").orEmpty()
    private val proxyHost: String = System.getenv("I2P_TEST_PROXY_HOST") ?: "127.0.0.1"
    private val proxyPort: Int =
        System.getenv("I2P_TEST_PROXY_PORT")?.toIntOrNull() ?: I2pIntegration.HTTP_PROXY_PORT

    @Before
    fun requireLiveRig() {
        assumeTrue("set I2P_TEST_DEST to run the live I2P integration test", dest.isNotEmpty())
    }

    /** Step 1 — readiness probe against the live proxy must report READY. */
    @Test
    fun probeReportsReady() = runBlocking {
        val readiness = HttpConnectI2pProber().probe(proxyHost, proxyPort, dest, timeoutMs = 60_000)
        assertEquals(I2pProber.Readiness.READY, readiness)
    }

    /** Step 2 — REST GET /healthz through the CONNECT socket-factory client. */
    @Test
    fun healthzReturnsOkThroughTunnel() {
        val client = CertificatePinning.buildI2pClient(proxyHost, dest)
        val request = Request.Builder().url("http://$dest/healthz").build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            val body = response.body?.string().orEmpty()
            assertTrue("healthz body should report ok, was: $body", body.contains("\"status\":\"ok\""))
        }
    }

    /**
     * Step 3 — WS handshake through the same tunnel. onOpen proves a full success;
     * a server HTTP failure (e.g. 401/403 without a JWT) equally proves the
     * handshake bytes reached the server. A connect/timeout failure (no HTTP
     * response) means the tunnel never carried the handshake — that fails.
     */
    @Test
    fun webSocketHandshakeReachesServer() {
        val client = CertificatePinning.buildI2pClient(proxyHost, dest)
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<String>()
        val request = Request.Builder().url("ws://$dest/ws").build()
        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    outcome.set("open:${response.code}")
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // A response object means the server answered the handshake
                    // (bytes flowed through the proxy); its absence means the
                    // tunnel/connect itself failed.
                    outcome.set(
                        if (response != null) "server_status:${response.code}"
                        else "transport_failure:${t.javaClass.simpleName}:${t.message}",
                    )
                    latch.countDown()
                }
            },
        )
        try {
            assertTrue("WS handshake timed out", latch.await(90, TimeUnit.SECONDS))
            val result = outcome.get()
            assertTrue(
                "WS handshake did not reach the server (was: $result)",
                result.startsWith("open:") || result.startsWith("server_status:"),
            )
        } finally {
            ws.cancel()
        }
    }
}
