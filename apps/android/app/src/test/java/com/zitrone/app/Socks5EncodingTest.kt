// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.net.Socks5
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SOCKS5 wire encoding for the readiness probe. The router accepts SOCKS
 * connections before its tunnels exist, so the probe proves readiness with a
 * real CONNECT to <dest>:80; these bytes must match RFC 1928 exactly or a live
 * router would reject the handshake (a "compiles, silently never READY" trap).
 */
class Socks5EncodingTest {

    @Test
    fun `greeting offers only no-auth`() {
        // VER=0x05, NMETHODS=1, METHOD=0x00 (no authentication).
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), Socks5.GREETING)
    }

    @Test
    fun `connect request is a well-formed SOCKS5 domain CONNECT to port 80`() {
        val dest = "ab.b32.i2p"
        val req = Socks5.connectRequest(dest)
        val host = dest.toByteArray(Charsets.US_ASCII)

        // VER CMD RSV ATYP LEN <host> PORT_HI PORT_LO
        assertEquals(4 + 1 + host.size + 2, req.size)
        assertEquals(0x05.toByte(), req[0]) // VER
        assertEquals(0x01.toByte(), req[1]) // CMD = CONNECT
        assertEquals(0x00.toByte(), req[2]) // RSV
        assertEquals(0x03.toByte(), req[3]) // ATYP = domain name
        assertEquals(host.size.toByte(), req[4]) // domain length prefix

        val embedded = req.copyOfRange(5, 5 + host.size)
        assertArrayEquals(host, embedded)

        // Port 80 big-endian.
        assertEquals(0x00.toByte(), req[5 + host.size])
        assertEquals(0x50.toByte(), req[6 + host.size])
    }

    @Test
    fun `an over-long domain is rejected rather than truncated`() {
        // 256 chars — beyond the single-byte SOCKS5 length field.
        val tooLong = "a".repeat(256)
        assertThrows(IllegalArgumentException::class.java) { Socks5.connectRequest(tooLong) }
    }
}
