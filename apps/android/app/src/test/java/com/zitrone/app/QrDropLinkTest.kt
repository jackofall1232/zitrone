// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.parseQrDropLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class QrDropLinkTest {

    // A well-formed wire qr_id: 22 unpadded base64url chars (decodes to 16 bytes),
    // deliberately exercising every character class incl. `-` and `_`.
    private val id = "abcABC012_-abcABC012_-"
    private val url = "https://zitrone.app/d/$id"

    @Test
    fun `accepts a sticker url and returns the id verbatim`() {
        assertEquals(id, parseQrDropLink(url))
        assertEquals(id, parseQrDropLink("  $url  "))
    }

    @Test
    fun `round-trips a random 16-byte id`() {
        val bytes = ByteArray(16) { it.toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertEquals(22, encoded.length)
        assertEquals(encoded, parseQrDropLink("https://zitrone.app/d/$encoded"))
    }

    @Test
    fun `is case-insensitive on scheme and host`() {
        assertEquals(id, parseQrDropLink("HTTPS://ZITRONE.APP/d/$id"))
        assertEquals(id, parseQrDropLink("HtTpS://Zitrone.App/d/$id"))
    }

    @Test
    fun `rejects a wrong host`() {
        assertNull(parseQrDropLink("https://zitrone.example/d/$id"))
        // Look-alike host that merely starts with the real one.
        assertNull(parseQrDropLink("https://zitrone.app.evil.com/d/$id"))
        assertNull(parseQrDropLink("https://zitrone.appx/d/$id"))
    }

    @Test
    fun `rejects a non-https scheme`() {
        assertNull(parseQrDropLink("http://zitrone.app/d/$id"))
        assertNull(parseQrDropLink("zitrone://zitrone.app/d/$id"))
    }

    @Test
    fun `rejects a wrong path`() {
        assertNull(parseQrDropLink("https://zitrone.app/x/$id"))
        assertNull(parseQrDropLink("https://zitrone.app/$id"))
        // Path case matters — the App Link filter is /d/ exactly.
        assertNull(parseQrDropLink("https://zitrone.app/D/$id"))
    }

    @Test
    fun `rejects a port userinfo query fragment or extra segment`() {
        assertNull(parseQrDropLink("https://zitrone.app:8443/d/$id"))
        assertNull(parseQrDropLink("https://user@zitrone.app/d/$id"))
        assertNull(parseQrDropLink("https://zitrone.app/d/$id?ref=qr"))
        assertNull(parseQrDropLink("https://zitrone.app/d/$id#frag"))
        assertNull(parseQrDropLink("https://zitrone.app/d/$id/more"))
    }

    @Test
    fun `rejects the wrong id length`() {
        assertNull(parseQrDropLink("https://zitrone.app/d/${id.dropLast(1)}")) // 21
        assertNull(parseQrDropLink("https://zitrone.app/d/${id}A")) // 23
        assertNull(parseQrDropLink("https://zitrone.app/d/")) // empty
    }

    @Test
    fun `rejects non-base64url characters`() {
        // '+' and '/' are STANDARD base64 but not URL-safe; both must fail.
        assertNull(parseQrDropLink("https://zitrone.app/d/abcABC012+/abcABC012+/"))
        assertNull(parseQrDropLink("https://zitrone.app/d/abcABC012.!abcABC012.!"))
    }

    @Test
    fun `rejects a padded id`() {
        // Padded base64url of 16 bytes is 24 chars ending in "==" — the '=' is
        // not in the unpadded alphabet, so it must be rejected.
        val padded = Base64.getUrlEncoder().encodeToString(ByteArray(16))
        assertEquals(24, padded.length)
        assertNull(parseQrDropLink("https://zitrone.app/d/$padded"))
    }

    /**
     * The chat-list in-app scanner (ChatListScreen) promotes scan results ONLY
     * through [parseQrDropLink] before calling onOpenLemonDrop — same choke
     * point as App Links. Anything else is a snackbar, never a redeem attempt.
     */
    @Test
    fun `in-app scanner rejects contact payloads and bare text`() {
        // Contact-exchange JSON (what AddContactScreen scans) must not open a drop.
        assertNull(
            parseQrDropLink(
                """{"version":"1","account_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","identity_key":"AA=="}""",
            ),
        )
        assertNull(parseQrDropLink("hello world"))
        assertNull(parseQrDropLink(""))
        // Canonical sticker still works — the success path for the scanner.
        assertEquals(id, parseQrDropLink(url))
    }

    @Test
    fun `rejects unrelated or blank input`() {
        assertNull(parseQrDropLink(""))
        assertNull(parseQrDropLink("   "))
        assertNull(parseQrDropLink("not a link"))
        assertNull(parseQrDropLink("https://zitrone.app"))
    }
}
