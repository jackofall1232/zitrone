// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * HTTP CONNECT wire encoding for the I2P HTTP proxy — pure functions, extracted
 * (like WsClient's frame builders) so the exact bytes and the status-line parse
 * are unit-testable without a socket. This is the SAME mechanism the Linux
 * desktop uses (apps/desktop/src-tauri/src/i2p.rs `ws_open_i2p`), verified
 * end-to-end against a live tunnel; the semantics below were re-confirmed on
 * 2026-07-19 against the Java I2P router 2.12.1 proxy (identical i2ptunnel
 * HTTPClient code to the official Android app):
 *
 *  - `CONNECT <b32>:80` -> `HTTP/1.1 200 Connection Established` + `Proxy-agent:
 *    I2P`, after which the tunnel carries arbitrary bytes verbatim (REST and WS
 *    alike). The 200 is answered AFTER destination lookup, so it is a trustworthy
 *    readiness signal (see [HttpConnectI2pProber]).
 *  - A valid but nonexistent b32 -> `HTTP/1.1 504 Gateway Timeout` (~5.6s once
 *    the proxy is listening); a malformed b32 -> the proxy closes instantly with
 *    no response (a short read here).
 */
object HttpConnect {

    /** The relay's I2P server tunnel forwards CONNECT-to-80 to its plain http origin. */
    private const val CONNECT_PORT = 80

    /**
     * Cap on the proxy's CONNECT response (mirrors desktop i2p.rs). A well-behaved
     * proxy answers in well under this; a runaway response is treated as failure.
     */
    private const val MAX_RESPONSE_BYTES = 4096

    private const val CR = '\r'.code
    private const val LF = '\n'.code

    /**
     * The CONNECT request line + Host header for [dest] on port 80:
     *   `CONNECT <dest>:80 HTTP/1.1\r\nHost: <dest>\r\n\r\n`
     * The dest is a `.b32.i2p` address the proxy resolves over I2P — exactly what
     * routes an I2P destination. ASCII-only by construction; a b32 address can
     * never contain a CR/LF, so there is no request-splitting surface here.
     */
    fun connectRequest(dest: String): ByteArray {
        require(dest.isNotEmpty()) { "CONNECT destination must not be empty" }
        return "CONNECT $dest:$CONNECT_PORT HTTP/1.1\r\nHost: $dest\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
    }

    /**
     * Reads the proxy's CONNECT response byte-by-byte up to the terminating
     * `\r\n\r\n` and returns its FIRST line (the status line). Byte-by-byte and
     * bounded exactly at the header end — like desktop i2p.rs — so it never
     * over-reads into pipelined tunnel bytes the proxy may send right after the
     * 200. Returns null on early EOF (the instant-close a malformed dest causes)
     * or if [MAX_RESPONSE_BYTES] is exceeded before the terminator — both "not a
     * usable 200".
     */
    fun readStatusLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(256)
        // Rolling window of the three bytes before the current one, to spot the
        // \r\n\r\n terminator (prev3 prev2 prev1 current == CR LF CR LF).
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null // EOF before the header finished
            buf.write(b)
            if (prev3 == CR && prev2 == LF && prev1 == CR && b == LF) break
            prev3 = prev2; prev2 = prev1; prev1 = b
            if (buf.size() >= MAX_RESPONSE_BYTES) return null
        }
        return String(buf.toByteArray(), Charsets.US_ASCII).substringBefore("\r\n")
    }

    /**
     * Exact status-token match on the CONNECT status line: version starts with
     * `HTTP/1.` AND the status code token is exactly `200`. A prefix match on the
     * whole line would wrongly accept e.g. a body that starts "200"; the
     * unreachable-dest `504` and every other non-200 must read as not-ready.
     */
    fun isSuccessStatusLine(line: String): Boolean {
        val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        val versionOk = parts.getOrNull(0)?.startsWith("HTTP/1.") == true
        val statusOk = parts.getOrNull(1) == "200"
        return versionOk && statusOk
    }

    /**
     * Performs the full CONNECT handshake over an already-connected proxy socket's
     * streams and throws [IOException] unless the proxy answered `HTTP/1.x 200`.
     * Used by [I2pConnectSocketFactory]; the readiness probe instead inspects the
     * status line directly so it can distinguish "not ready" from "proxy down".
     */
    @Throws(IOException::class)
    fun open(input: InputStream, output: OutputStream, dest: String) {
        output.write(connectRequest(dest))
        output.flush()
        val line = readStatusLine(input)
            ?: throw IOException("I2P proxy closed before a CONNECT response (dest unreachable/malformed)")
        if (!isSuccessStatusLine(line)) {
            throw IOException("I2P proxy refused CONNECT: $line")
        }
    }
}

/**
 * OkHttp [SocketFactory] whose sockets tunnel every connection to a single baked-
 * in I2P destination via HTTP CONNECT on the local proxy — the ONE mechanism for
 * both REST and WebSocket over I2P (see net/CertificatePinning.buildI2pClient).
 *
 * Chosen over `Proxy.Type.HTTP`: a CONNECT tunnel is OPAQUE, so the proxy cannot
 * see or rewrite headers (Authorization, Sec-WebSocket-Protocol), and OkHttp
 * sends origin-form request lines through it — a configured HTTP proxy would emit
 * absolute-form lines the origin server rejects. The client therefore sets NO
 * `.proxy(...)`; this factory is the only proxying layer.
 *
 * The socket ignores the address OkHttp asks it to dial (that address is a
 * placeholder produced by the client's Dns override) and always tunnels to
 * [relayDest]. [relayDest] is baked in at build time (BuildConfig.RELAY_I2P_DEST,
 * the I2P analogue of the pinned relay host); it is never runtime-supplied.
 */
class I2pConnectSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val relayDest: String,
) : SocketFactory() {

    override fun createSocket(): Socket = TunnelingSocket(proxyHost, proxyPort, relayDest)

    // OkHttp only ever uses the no-arg createSocket() above and then calls
    // connect(); the auto-connecting overloads are implemented for the
    // SocketFactory contract and route through the same tunnelling connect().
    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    /**
     * A [Socket] that, on connect(), actually dials the local I2P proxy and runs
     * the CONNECT handshake to [relayDest] before returning — after which it is an
     * ordinary connected socket carrying the tunnel. The [timeout] OkHttp passes
     * (the client's connectTimeout) bounds BOTH the TCP connect to the proxy and
     * the CONNECT response read; it is a generous 60s because a cold leaseset
     * lookup can take tens of seconds (see CertificatePinning.buildI2pClient).
     */
    private class TunnelingSocket(
        private val proxyHost: String,
        private val proxyPort: Int,
        private val relayDest: String,
    ) : Socket() {

        override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            // Ignore the requested endpoint (a placeholder from the Dns override) —
            // always dial the proxy, then CONNECT-tunnel to the baked-in relay.
            super.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
            // Bound the CONNECT read with the same budget as the connect; the
            // proxy stalls the 200 while it looks up the destination.
            soTimeout = timeout
            try {
                HttpConnect.open(getInputStream(), getOutputStream(), relayDest)
            } catch (e: IOException) {
                runCatching { close() }
                throw e
            }
            // Restore the default (no socket-level read timeout) so OkHttp's own
            // read-timeout/ping config governs the tunnelled stream (WS uses 0).
            soTimeout = 0
        }
    }
}
