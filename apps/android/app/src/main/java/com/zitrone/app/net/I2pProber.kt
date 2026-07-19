// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * I2P readiness probe. A bare TCP connect to the proxy port only proves the proxy
 * is *listening* — and on the official Android I2P app port 4444 does not even
 * listen until the HTTP-client tunnel is built, so a connect can be refused while
 * the router is still starting. Real readiness is a full HTTP CONNECT to
 * `<RELAY_I2P_DEST>:80` that the proxy answers with `HTTP/1.x 200`, which it can
 * only do once an outbound tunnel to that destination has been established (the
 * 200 is returned AFTER the destination lookup — an unreachable dest yields a 504
 * instead; see [HttpConnect]).
 *
 * Three outcomes are distinguished so the resolver can act differently:
 *  - [Readiness.PROXY_DOWN]        TCP connect refused/failed. This means the
 *                                  proxy is not accepting connections — the router
 *                                  is absent OR still starting up (4444 not yet
 *                                  bound). NOT proof the router is uninstalled.
 *  - [Readiness.TUNNELS_NOT_READY] Proxy up but the CONNECT did not return a 200
 *                                  (a 504, a short read, or a timeout) — the
 *                                  outbound tunnel to the relay isn't built yet.
 *  - [Readiness.READY]            CONNECT returned 200 — route through I2P now.
 *
 * PROXY_DOWN and TUNNELS_NOT_READY are handled identically by the resolver (keep
 * polling), so the startup-vs-absent ambiguity of PROXY_DOWN is harmless; the
 * distinction exists for diagnostics and the mid-session liveness demotion.
 *
 * Injected as an interface so the transport resolver stays testable without a
 * live router (see net/TransportResolver.kt and the JVM tests).
 */
interface I2pProber {

    enum class Readiness { PROXY_DOWN, TUNNELS_NOT_READY, READY }

    /**
     * Probe the HTTP proxy at [host]:[port] for a route to [dest]. [timeoutMs]
     * bounds BOTH the TCP connect and the CONNECT-response read after it (the
     * caller chooses a short timeout for the quick candidate check and a longer
     * one for background polling that tolerates a 1–3 min tunnel build).
     */
    suspend fun probe(host: String, port: Int, dest: String, timeoutMs: Int): Readiness

    /**
     * Cheap liveness check while I2P is the ACTIVE transport: is anything still
     * listening on the proxy port? Deliberately a bare local TCP connect, not a
     * full [probe] — a full CONNECT would open (and immediately drop) a real
     * tunnel to the relay on every check, which is needless I2P traffic and
     * connection churn at the server. This catches the realistic mid-session
     * failure (the user stopped/uninstalled the router); transient tunnel
     * degradation while the router stays up is left to OkHttp's own retries.
     * Default true so test fakes that only care about promotion keep working.
     */
    suspend fun proxyUp(host: String, port: Int, timeoutMs: Int): Boolean = true
}

/**
 * Real [I2pProber] speaking the minimal HTTP CONNECT handshake over a raw
 * [Socket] against the official I2P app's local HTTP proxy. The exact bytes and
 * status-line parse live in [HttpConnect] (unit-tested); this class only owns the
 * socket lifecycle and maps outcomes to [I2pProber.Readiness]. Always runs on
 * [Dispatchers.IO] — never the main thread.
 */
class HttpConnectI2pProber : I2pProber {

    override suspend fun probe(
        host: String,
        port: Int,
        dest: String,
        timeoutMs: Int,
    ): I2pProber.Readiness = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            // Connect failures (nothing listening) mean the proxy isn't accepting
            // yet — router absent OR still starting (4444 unbound during warmup).
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                return@withContext I2pProber.Readiness.PROXY_DOWN
            }
            // From here the proxy is up; anything short of an HTTP/1.x 200 CONNECT
            // reply (a 504 while the tunnel builds, a malformed-dest instant close,
            // or a read timeout) is "not ready yet". We inspect the status line
            // directly (rather than HttpConnect.open, which throws) to keep the
            // READY/NOT-READY decision explicit.
            try {
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(HttpConnect.connectRequest(dest))
                out.flush()
                val line = HttpConnect.readStatusLine(input)
                if (line != null && HttpConnect.isSuccessStatusLine(line)) {
                    I2pProber.Readiness.READY
                } else {
                    I2pProber.Readiness.TUNNELS_NOT_READY
                }
            } catch (e: IOException) {
                I2pProber.Readiness.TUNNELS_NOT_READY
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    override suspend fun proxyUp(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                true
            } catch (e: IOException) {
                false
            }
        }
}
