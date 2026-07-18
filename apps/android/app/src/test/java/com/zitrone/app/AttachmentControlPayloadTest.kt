// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.AttachmentControlPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format contract for attachment control payloads — must stay
 * byte-compatible with packages/protocol/src/attachments.ts. The near-miss and
 * strict-validation cases are ported verbatim from attachments.test.ts.
 */
class AttachmentControlPayloadTest {

    // Any 43-chars-plus-pad base64 string decodes to 32 bytes — a plausible
    // token/key/hash. Validated by length only, so no Base64 decode is needed
    // (keeps this test pure JVM).
    private val b64of32 = "A".repeat(43) + "="

    private fun imageJson(): String = AttachmentControlPayload.serialize(
        kind = AttachmentControlPayload.KIND_IMAGE,
        blobToken = b64of32,
        key = b64of32,
        mimetype = "image/jpeg",
        filename = null,
        size = 123_456,
        sha256 = b64of32,
        caption = null,
    )

    @Test
    fun `round-trips an image through serialization`() {
        val parsed = AttachmentControlPayload.parse(imageJson())
        assertNotNull(parsed)
        assertEquals(AttachmentControlPayload.KIND_IMAGE, parsed!!.kind)
        assertEquals("image/jpeg", parsed.mimetype)
        assertEquals(123_456, parsed.size)
        assertNull(parsed.filename)
        assertNull(parsed.caption)
    }

    @Test
    fun `round-trips a file with filename and caption`() {
        val json = AttachmentControlPayload.serialize(
            kind = AttachmentControlPayload.KIND_FILE,
            blobToken = b64of32,
            key = b64of32,
            mimetype = "application/pdf",
            filename = "report.pdf",
            size = 123_456,
            sha256 = b64of32,
            caption = "Q3 draft",
        )
        val parsed = AttachmentControlPayload.parse(json)
        assertEquals(AttachmentControlPayload.KIND_FILE, parsed!!.kind)
        assertEquals("report.pdf", parsed.filename)
        assertEquals("Q3 draft", parsed.caption)
    }

    @Test
    fun `forces filename to null for images even when supplied`() {
        val json = AttachmentControlPayload.serialize(
            kind = AttachmentControlPayload.KIND_IMAGE,
            blobToken = b64of32,
            key = b64of32,
            mimetype = "image/jpeg",
            filename = "leaky-name.jpg",
            size = 123_456,
            sha256 = b64of32,
            caption = null,
        )
        assertNull(AttachmentControlPayload.parse(json)!!.filename)
    }

    @Test
    fun `treats ordinary text and receipt payloads as not-an-attachment`() {
        assertNull(AttachmentControlPayload.parse("just a message"))
        assertNull(AttachmentControlPayload.parse(""))
        assertNull(
            AttachmentControlPayload.parse(
                """{"v":1,"control":"receipt.read","message_ids":[]}""",
            ),
        )
    }

    @Test
    fun `rejects a matching discriminator with invalid crypto fields`() {
        val good = JSONObject(imageJson())
        val patches = listOf<Pair<String, Any>>(
            "blob_token" to "short",
            "key" to b64of32.substring(0, 20),
            "sha256" to "!".repeat(44),
            "size" to 0,
            "size" to AttachmentControlPayload.ATTACHMENT_MAX_BYTES + 1,
            "size" to 1.5,
            "kind" to "video",
            "mimetype" to "",
        )
        for ((field, value) in patches) {
            val patched = JSONObject(good.toString()).put(field, value)
            assertNull("expected null for $field=$value", AttachmentControlPayload.parse(patched.toString()))
        }
    }

    @Test
    fun `rejects images that smuggle a filename on the wire`() {
        val patched = JSONObject(imageJson()).put("filename", "x.jpg")
        assertNull(AttachmentControlPayload.parse(patched.toString()))
    }

    @Test
    fun `is lenient about unknown extra fields`() {
        val patched = JSONObject(imageJson()).put("future_field", true)
        assertNotNull(AttachmentControlPayload.parse(patched.toString()))
    }

    @Test
    fun `flags control-shaped payloads so callers never render them as text`() {
        // A malformed attachment (bad key length) parses to null but MUST still
        // be recognized as a control payload — it may carry key material.
        val nearMiss = JSONObject(imageJson()).put("key", "tooshort").toString()
        assertNull(AttachmentControlPayload.parse(nearMiss))
        assertTrue(AttachmentControlPayload.isControlPayload(nearMiss))
        // Future control types from newer clients are also flagged.
        assertTrue(AttachmentControlPayload.isControlPayload("""{"v":3,"control":"poll.v1","options":[]}"""))
        // Ordinary text and non-control JSON are not.
        assertFalse(AttachmentControlPayload.isControlPayload("hello {"))
        assertFalse(AttachmentControlPayload.isControlPayload("""{"hello":"world"}"""))
        assertFalse(AttachmentControlPayload.isControlPayload("""{"v":"1","control":7}"""))
    }

    @Test
    fun `never throws on malformed input`() {
        assertNull(AttachmentControlPayload.parse("{"))
        assertNull(AttachmentControlPayload.parse("[1,2,3]"))
        assertNull(AttachmentControlPayload.parse("null"))
    }
}
