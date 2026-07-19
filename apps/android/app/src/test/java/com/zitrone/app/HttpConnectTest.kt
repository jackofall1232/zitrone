// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.net.HttpConnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Wire-shape tests for the I2P HTTP CONNECT helper — the pure request builder and
 * the response status-line parse, exercised without a socket (the same approach
 * as WsClientFrameTest). Semantics mirror the Linux desktop's i2p.rs and the
 * empirical Java-I2P-proxy findings recorded in [HttpConnect]'s KDoc.
 */
class HttpConnectTest {

    private val dest = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnop.b32.i2p"

    private fun statusLineOf(response: String): String? =
        HttpConnect.readStatusLine(ByteArrayInputStream(response.toByteArray(Charsets.US_ASCII)))

    // -- request builder --------------------------------------------------------

    @Test
    fun `connect request is origin-form CONNECT with a Host header`() {
        val bytes = HttpConnect.connectRequest(dest)
        val text = String(bytes, Charsets.US_ASCII)
        assertEquals(
            "CONNECT $dest:80 HTTP/1.1\r\nHost: $dest\r\n\r\n",
            text,
        )
    }

    @Test
    fun `connect request is pure ASCII and terminates on a blank line`() {
        val text = String(HttpConnect.connectRequest(dest), Charsets.US_ASCII)
        assertTrue("must end with the header terminator", text.endsWith("\r\n\r\n"))
        assertTrue("must be ASCII", text.all { it.code in 0..127 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty destination is rejected`() {
        HttpConnect.connectRequest("")
    }

    // -- status-line read -------------------------------------------------------

    @Test
    fun `reads the status line and stops at the header terminator`() {
        // Bytes after \r\n\r\n (pipelined tunnel data) must NOT be consumed here.
        val line = statusLineOf(
            "HTTP/1.1 200 Connection Established\r\nProxy-agent: I2P\r\n\r\nGARBAGE-TUNNEL-BYTES",
        )
        assertEquals("HTTP/1.1 200 Connection Established", line)
    }

    @Test
    fun `short read before the terminator returns null`() {
        // Malformed dest -> the proxy closes instantly with no full header (EOF).
        assertNull(statusLineOf("HTTP/1.1 200 OK\r\n"))
    }

    @Test
    fun `empty stream returns null`() {
        assertNull(statusLineOf(""))
    }

    // -- status-line classification --------------------------------------------

    @Test
    fun `200 status line is success`() {
        assertTrue(HttpConnect.isSuccessStatusLine("HTTP/1.1 200 Connection Established"))
        assertTrue(HttpConnect.isSuccessStatusLine("HTTP/1.0 200 OK"))
    }

    @Test
    fun `504 gateway timeout is not success`() {
        // The unreachable-dest response observed empirically (~5.6s).
        assertFalse(HttpConnect.isSuccessStatusLine("HTTP/1.1 504 Gateway Timeout"))
    }

    @Test
    fun `other 4xx and 5xx are not success`() {
        assertFalse(HttpConnect.isSuccessStatusLine("HTTP/1.1 403 Forbidden"))
        assertFalse(HttpConnect.isSuccessStatusLine("HTTP/1.1 500 Internal Server Error"))
    }

    @Test
    fun `a body that merely starts with 200 is not a 200 status`() {
        // Exact-token match, not a prefix match on the whole line.
        assertFalse(HttpConnect.isSuccessStatusLine("200 OK"))
        assertFalse(HttpConnect.isSuccessStatusLine("HTTP/1.1 2000 Nonsense"))
    }

    @Test
    fun `garbage is not success`() {
        assertFalse(HttpConnect.isSuccessStatusLine(""))
        assertFalse(HttpConnect.isSuccessStatusLine("not an http response"))
        assertFalse(HttpConnect.isSuccessStatusLine("HTTP/2 200"))
    }

    @Test
    fun `end-to-end a 200 response parses and classifies as success`() {
        val line = statusLineOf("HTTP/1.1 200 Connection Established\r\nProxy-agent: I2P\r\n\r\n")
        assertTrue(line != null && HttpConnect.isSuccessStatusLine(line))
    }
}
