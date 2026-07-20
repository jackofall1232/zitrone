// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

/**
 * QR dead-drop ("lemon drop") deep-link parser.
 *
 * A lemon-drop QR sticker encodes exactly `https://zitrone.app/d/{qr_id}`, where
 * qr_id is 16 random bytes in UNPADDED BASE64URL — the one canonical form shared
 * with the relay's `qr_id` JSON field (packages/protocol lemondrop.ts and server
 * qrdrops.go, which decodes it with base64.RawURLEncoding). This returns that id
 * VERBATIM — the exact path segment, ready to hand back to the relay unchanged —
 * or null for anything that is not a well-formed sticker URL.
 *
 * Pure Kotlin, no Android framework: a QR reader / the OS hands us arbitrary,
 * untrusted text, and — exactly like [parseContactPayload] — this is the single
 * choke point it passes through, failing closed on anything unexpected. Keeping
 * it framework-free (Uri plumbing stays in MainActivity, which passes the raw
 * link string) keeps the validation covered by a plain JVM unit test.
 *
 * Validation is deliberately strict and mirrors the TypeScript `parseQrDropUrl`
 * reference (packages/protocol lemondrop.ts): https only, host exactly
 * zitrone.app, path exactly `/d/{id}` (one segment), and an id that is exactly
 * 22 base64url characters — which is precisely the length that decodes to the
 * 16-byte qr_id and nothing else (see [isValidQrDropId]).
 */

/** Scheme + host of a sticker URL. Case-INSENSITIVE (URL scheme/host are). */
private const val QR_DROP_ORIGIN = "https://zitrone.app"

/** The dead-drop path prefix. Case-SENSITIVE, like any URL path — and it must
 *  match the manifest App Link filter's `android:pathPrefix="/d/"` exactly. */
private const val QR_DROP_PATH_PREFIX = "/d/"

/**
 * Length of a wire qr_id in unpadded base64url characters. 16 bytes = 128 bits;
 * base64 packs 6 bits/char, so the canonical unpadded form is ceil(128/6) = 22
 * characters. This length is not arbitrary: for the URL-safe alphabet a string
 * of exactly 22 valid characters decodes to exactly 16 bytes and no other byte
 * count can — 21 chars is an illegal base64 remainder (len % 4 == 1) and 24
 * chars would be 18 bytes — so a charset check plus this exact length together
 * ARE the "decodes to exactly 16 bytes" guarantee, no decoder needed. The relay
 * (qrdrops.go) enforces the same 16-byte length after RawURLEncoding decode.
 */
private const val QR_DROP_ID_LENGTH = 22

/** The URL-safe base64 alphabet, no padding: A–Z a–z 0–9 and `-` `_`. */
private val QR_DROP_ID_CHARSET = Regex("^[A-Za-z0-9_-]+$")

/**
 * True when [id] is a syntactically valid wire qr_id: exactly [QR_DROP_ID_LENGTH]
 * characters, all from the unpadded base64url alphabet. Rejects the wrong length,
 * any non-base64url character, and padding (`=` is not in the alphabet). See the
 * [QR_DROP_ID_LENGTH] note for why length + charset equals a 16-byte decode.
 */
fun isValidQrDropId(id: String): Boolean =
    id.length == QR_DROP_ID_LENGTH && QR_DROP_ID_CHARSET.matches(id)

/**
 * Parse a scanned/opened link into its verbatim qr_id, or null if it is not a
 * canonical lemon-drop sticker URL (`https://zitrone.app/d/{id}`). Never throws.
 *
 * Rejects: any non-https scheme, any host but zitrone.app (including look-alikes
 * like `zitrone.app.evil.com` and any `:port` or userinfo — the segment after the
 * origin would not begin with `/d/`), any path but `/d/`, extra path segments,
 * a query or fragment (their `?`/`#` land in the id and fail the charset check),
 * and an id of the wrong length, wrong charset, or with padding.
 */
fun parseQrDropLink(input: String): String? {
    val trimmed = input.trim()
    // Scheme + host are case-insensitive; compare that region loosely.
    if (!trimmed.regionMatches(0, QR_DROP_ORIGIN, 0, QR_DROP_ORIGIN.length, ignoreCase = true)) {
        return null
    }
    // Everything past the origin is the (case-sensitive) path — and it must be
    // exactly `/d/` + the id, so a port, userinfo, or a look-alike host all fail
    // here because `rest` would not start with `/d/`.
    val rest = trimmed.substring(QR_DROP_ORIGIN.length)
    if (!rest.startsWith(QR_DROP_PATH_PREFIX)) return null
    val id = rest.substring(QR_DROP_PATH_PREFIX.length)
    return if (isValidQrDropId(id)) id else null
}

/** The URL-safe base64 alphabet, no padding — the SAME canonical form as
 *  packages/protocol lemondrop.ts `toBase64Url` and the relay's RawURLEncoding.
 *  Framework-free (no android.util.Base64) so the encoder stays JVM-testable
 *  alongside [parseQrDropLink]. */
private const val B64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * Encode a raw qr_id (16 bytes) as unpadded base64url — the one canonical wire
 * form for BOTH the JSON `qr_id` field and the sticker URL path segment (mirror
 * of packages/protocol `encodeQrDropId`; the relay decodes exactly this with
 * base64.RawURLEncoding). Byte math, not android.util.Base64, so [buildQrDropUrl]
 * and the deposit share one order-independent implementation.
 */
fun encodeQrDropId(qrId: ByteArray): String {
    val out = StringBuilder()
    var i = 0
    while (i < qrId.size) {
        val b0 = qrId[i].toInt() and 0xFF
        val hasB1 = i + 1 < qrId.size
        val hasB2 = i + 2 < qrId.size
        val b1 = if (hasB1) qrId[i + 1].toInt() and 0xFF else 0
        val b2 = if (hasB2) qrId[i + 2].toInt() and 0xFF else 0
        out.append(B64URL_ALPHABET[b0 ushr 2])
        out.append(B64URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
        if (!hasB1) break
        out.append(B64URL_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
        if (!hasB2) break
        out.append(B64URL_ALPHABET[b2 and 0x3F])
        i += 3
    }
    return out.toString()
}

/** Build the sticker URL for a raw qr_id: `https://zitrone.app/d/{base64url}`
 *  (mirror of packages/protocol `buildQrDropUrl`). */
fun buildQrDropUrl(qrId: ByteArray): String =
    QR_DROP_ORIGIN + QR_DROP_PATH_PREFIX + encodeQrDropId(qrId)
