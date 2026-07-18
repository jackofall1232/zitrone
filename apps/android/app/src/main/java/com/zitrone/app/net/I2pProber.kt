// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * I2P readiness probe. A bare TCP connect to the SOCKS port only proves the
 * proxy is *listening* — i2pd accepts SOCKS connections before its tunnels are
 * built. Real readiness is a full SOCKS5 handshake plus a CONNECT request to
 * `<RELAY_I2P_DEST>:80` that the router answers with reply code 0x00, which it
 * can only do once an outbound tunnel to that destination exists.
 *
 * Three outcomes are distinguished so the resolver can act differently:
 *  - [Readiness.PROXY_DOWN]        TCP connect refused/failed — no router at all.
 *  - [Readiness.TUNNELS_NOT_READY] SOCKS reply != 0x00, or a timeout after the
 *                                  connect — router up, tunnels still building.
 *  - [Readiness.READY]            CONNECT succeeded — route through I2P now.
 *
 * Injected as an interface so the transport resolver stays testable without a
 * live router (see net/TransportResolver.kt and the JVM tests).
 */
interface I2pProber {

    enum class Readiness { PROXY_DOWN, TUNNELS_NOT_READY, READY }

    /**
     * Probe the SOCKS5 proxy at [host]:[port] for a route to [dest]. [timeoutMs]
     * bounds BOTH the TCP connect and each blocking read after it (the caller
     * chooses a short timeout for the quick candidate check and a longer one for
     * background polling that tolerates a 1–3 min tunnel build).
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
 * Real [I2pProber] speaking the minimal SOCKS5 client handshake over a raw
 * [Socket]. Always runs on [Dispatchers.IO] — never the main thread.
 */
class SocksI2pProber : I2pProber {

    override suspend fun probe(
        host: String,
        port: Int,
        dest: String,
        timeoutMs: Int,
    ): I2pProber.Readiness = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            // Connect failures (nothing listening) mean the router is absent.
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                return@withContext I2pProber.Readiness.PROXY_DOWN
            }
            // From here the proxy is up; anything short of a 0x00 CONNECT reply
            // (including a read timeout while a tunnel builds) is "not ready yet".
            try {
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                // Greeting: VER=5, one method, NO-AUTH. Expect "05 00" back.
                out.write(Socks5.GREETING)
                out.flush()
                val method = ByteArray(2)
                if (!readFully(input, method) || method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) {
                    return@withContext I2pProber.Readiness.TUNNELS_NOT_READY
                }

                // CONNECT to <dest>:80 as a SOCKS5 domain address.
                out.write(Socks5.connectRequest(dest))
                out.flush()
                // Reply header is VER REP RSV ATYP ... — REP at index 1 is the
                // only byte we need; 0x00 == succeeded.
                val reply = ByteArray(2)
                if (!readFully(input, reply) || reply[1] != 0x00.toByte()) {
                    return@withContext I2pProber.Readiness.TUNNELS_NOT_READY
                }
                I2pProber.Readiness.READY
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

    /** Reads exactly [buf].size bytes; false on early EOF. */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}

/**
 * SOCKS5 wire encoding — pure functions, extracted (like WsClient's frame
 * builders) so the exact byte layout is unit-testable without a socket.
 */
object Socks5 {

    /** Client greeting: VER=0x05, NMETHODS=1, METHOD=0x00 (no authentication). */
    val GREETING: ByteArray = byteArrayOf(0x05, 0x01, 0x00)

    /** CONNECT to <80> — the relay's I2P server tunnel forwards to plain http. */
    private const val CONNECT_PORT = 80

    /**
     * A SOCKS5 CONNECT request for [dest] on port 80 using ATYP=domain (0x03):
     *   VER(05) CMD(01=CONNECT) RSV(00) ATYP(03) LEN <domain bytes> PORT(big-endian)
     * The domain is passed unresolved so the SOCKS proxy (i2pd) does the .b32.i2p
     * lookup — exactly what routes an I2P destination.
     */
    fun connectRequest(dest: String): ByteArray {
        val host = dest.toByteArray(Charsets.US_ASCII)
        require(host.size in 1..255) { "SOCKS5 domain must be 1..255 bytes" }
        val buf = ByteArray(4 + 1 + host.size + 2)
        buf[0] = 0x05 // VER
        buf[1] = 0x01 // CMD = CONNECT
        buf[2] = 0x00 // RSV
        buf[3] = 0x03 // ATYP = domain name
        buf[4] = host.size.toByte()
        System.arraycopy(host, 0, buf, 5, host.size)
        buf[5 + host.size] = ((CONNECT_PORT ushr 8) and 0xFF).toByte()
        buf[6 + host.size] = (CONNECT_PORT and 0xFF).toByte()
        return buf
    }
}
