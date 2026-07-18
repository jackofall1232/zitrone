// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import org.json.JSONObject

/**
 * Attachment control payloads — encrypted control JSON carried INSIDE the
 * plaintext of an ordinary message envelope, exactly like read receipts (see
 * [ControlPayload]). The attachment bytes themselves NEVER ride in the
 * envelope: they are encrypted under a fresh random key, padded to 64 KiB
 * buckets, and sideloaded through the relay's blind blob store (see
 * crypto/AttachmentCrypto). The payload below carries the redemption token and
 * key, so everything the server holds is an opaque bucket-sized blob it can
 * neither decrypt nor associate with any envelope or account.
 *
 * ON THE WIRE AN ATTACHMENT MESSAGE IS media_type "text" — deliberately. The
 * envelope's media_type field is cleartext the relay can read; emitting the
 * reserved "image"/"file" values (MessageEnvelope.MEDIA_IMAGE / MEDIA_FILE)
 * would hand the server a per-message "this one has an attachment" label. Those
 * values stay in the schema but are never emitted; clients recognize
 * attachments AFTER decryption, by the discriminator below.
 *
 * Wire shape (canonical cross-client definition:
 * packages/protocol/src/attachments.ts):
 *
 *   {"v":1,"control":"attachment.v1","kind":"image"|"file",
 *    "blob_token":<b64 32>,"key":<b64 32>,"mimetype":"image/jpeg",
 *    "filename":<string>|null,"size":123,"sha256":<b64 32>,
 *    "caption":<string>|null}
 *
 * Parsing is strict on the discriminator (both "v" and "control" must match)
 * AND on the cryptographic fields: unlike receipts, a payload that MATCHES the
 * discriminator yet fails field validation is still null — rendered as an
 * "unsupported message" by callers, never as raw text. Leaking a near-miss's
 * key material into a chat bubble would be worse than dropping it. Lenient on
 * unknown extra fields, so future revisions can extend the payload.
 *
 * Parse order in clients: [ControlPayload.parseReadReceipt] -> [parse] ->
 * [isControlPayload] (unsupported placeholder) -> display text.
 */
object AttachmentControlPayload {

    const val VERSION = 1
    const val CONTROL = "attachment.v1"

    const val KIND_IMAGE = "image"
    const val KIND_FILE = "file"

    /** Plaintext size cap (pre-padding). The relay enforces the ciphertext bound. */
    const val ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024

    // Any 43-chars-plus-pad standard-base64 string decodes to exactly 32 bytes.
    // Validating by length (not by decoding) keeps this pure JVM — no
    // android.util.Base64, so the parser is exercised directly in unit tests.
    private val BASE64_32_BYTES = Regex("^[A-Za-z0-9+/]{43}=$")

    /** A parsed, validated attachment control payload. */
    data class Attachment(
        val kind: String,
        val blobToken: String,
        val key: String,
        val mimetype: String,
        /** Original filename for kind "file"; ALWAYS null for "image". */
        val filename: String?,
        /** Plaintext byte length (pre-padding). */
        val size: Int,
        val sha256: String,
        val caption: String?,
    )

    /**
     * Serializes an attachment control payload for envelope plaintext.
     * [filename] is forced null for kind "image" (an image's filename is
     * metadata the recipient has no need for — see the canonical spec).
     */
    fun serialize(
        kind: String,
        blobToken: String,
        key: String,
        mimetype: String,
        filename: String?,
        size: Int,
        sha256: String,
        caption: String?,
    ): String =
        JSONObject()
            .put("v", VERSION)
            .put("control", CONTROL)
            .put("kind", kind)
            .put("blob_token", blobToken)
            .put("key", key)
            .put("mimetype", mimetype)
            .put("filename", if (kind == KIND_IMAGE) JSONObject.NULL else (filename ?: JSONObject.NULL))
            .put("size", size)
            .put("sha256", sha256)
            .put("caption", caption ?: JSONObject.NULL)
            .toString()

    /**
     * Returns the payload when [plaintext] is an attachment control message, or
     * null for anything else. Never throws. Mirrors parseAttachment() in
     * packages/protocol/src/attachments.ts line-by-line.
     */
    fun parse(plaintext: String): Attachment? {
        if (!plaintext.startsWith("{")) return null
        val json = runCatching { JSONObject(plaintext) }.getOrNull() ?: return null
        // Raw-type discrimination (NOT optInt/optString): those coerce a string
        // "1" or a numeric control field, which would let a near-miss slip
        // through with the wrong types.
        if ((json.opt("v") as? Int) != VERSION) return null
        if ((json.opt("control") as? String) != CONTROL) return null
        val kind = json.opt("kind") as? String
        if (kind != KIND_IMAGE && kind != KIND_FILE) return null
        val blobToken = json.opt("blob_token") as? String ?: return null
        if (!BASE64_32_BYTES.matches(blobToken)) return null
        val key = json.opt("key") as? String ?: return null
        if (!BASE64_32_BYTES.matches(key)) return null
        val sha256 = json.opt("sha256") as? String ?: return null
        if (!BASE64_32_BYTES.matches(sha256)) return null
        val mimetype = json.opt("mimetype") as? String ?: return null
        if (mimetype.isEmpty()) return null
        // size must be an integer literal in range; opt returns Double for
        // "1.5", which fails the Int/Long guard exactly like the TS
        // Number.isInteger check.
        val sizeValue = json.opt("size")
        val size = when (sizeValue) {
            is Int -> sizeValue
            is Long -> if (sizeValue in 1..ATTACHMENT_MAX_BYTES.toLong()) sizeValue.toInt() else return null
            else -> return null
        }
        if (size <= 0 || size > ATTACHMENT_MAX_BYTES) return null
        val filenameRaw = json.opt("filename") as? String
        val filename = if (filenameRaw != null && filenameRaw.isNotEmpty()) filenameRaw else null
        if (kind == KIND_IMAGE && filename != null) return null
        val captionRaw = json.opt("caption") as? String
        val caption = if (captionRaw != null && captionRaw.isNotEmpty()) captionRaw else null
        return Attachment(kind, blobToken, key, mimetype, filename, size, sha256, caption)
    }

    /**
     * True when [plaintext] is SHAPED like a control payload (numeric `v` plus
     * string `control`), whether or not this client recognizes it. Callers must
     * check this AFTER the specific parsers return null and render a generic
     * "unsupported message" placeholder instead of raw text: a control payload
     * from a newer client — or an attachment that failed field validation — may
     * carry key material that must never be painted into a chat bubble. Mirrors
     * isControlPayload() in packages/protocol/src/attachments.ts.
     */
    fun isControlPayload(plaintext: String): Boolean {
        if (!plaintext.startsWith("{")) return false
        val json = runCatching { JSONObject(plaintext) }.getOrNull() ?: return false
        return json.opt("v") is Number && json.opt("control") is String
    }
}
