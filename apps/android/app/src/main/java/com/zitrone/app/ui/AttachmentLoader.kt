// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.zitrone.app.data.AttachmentControlPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Reads picked content from a content-provider stream STRAIGHT into memory —
 * never copied to a cache dir or any file (matching MessageRepository's
 * plaintext-never-touches-disk rule). Images are downscaled and re-encoded to
 * JPEG, which deliberately STRIPS EXIF (GPS, device, timestamps); files are
 * read raw with a hard size cap. The returned bytes go straight to
 * crypto/AttachmentCrypto — they are the attachment plaintext.
 */
object AttachmentLoader {

    /** Plaintext cap — matches [AttachmentControlPayload.ATTACHMENT_MAX_BYTES]. */
    const val MAX_ATTACHMENT_BYTES = AttachmentControlPayload.ATTACHMENT_MAX_BYTES

    /** Longest edge an image is downscaled to before JPEG re-encoding. */
    private const val MAX_IMAGE_EDGE = 2048

    /** JPEG quality for the re-encode (~0.85, matching the web/desktop client). */
    private const val JPEG_QUALITY = 85

    /** A file too large to send — surfaced to the user as a friendly message. */
    class TooLargeException : Exception("Attachments are limited to 8 MB.")

    /** Prepared, in-memory attachment bytes plus the metadata for the control payload. */
    class Prepared(
        val kind: String,
        val mimetype: String,
        val filename: String?,
        val bytes: ByteArray,
    )

    /**
     * Decodes the picked image, downscales it to at most [MAX_IMAGE_EDGE] on the
     * long edge, and re-encodes it as JPEG (~85). The re-encode strips EXIF by
     * construction — deliberate metadata minimization. kind = image, no
     * filename (an image's filename is metadata the recipient has no need for).
     *
     * GOTCHA — do NOT collapse the stream-open null-check into the bounds decode.
     * [BitmapFactory.decodeStream] with `inJustDecodeBounds = true` returns `null`
     * on SUCCESS by API contract — it only populates `outWidth`/`outHeight` and
     * never allocates a bitmap. So `openInputStream(uri)?.use { decodeStream(...) }`
     * evaluates to `null` on the *happy* path, and a trailing `?: throw` then
     * fires on EVERY valid image (this once made the photo-picker path fail with
     * "Couldn't attach that" 100% of the time). Validate the OPEN separately (a
     * null InputStream is the only "cannot open" signal) and validate the DECODE
     * via the [BitmapFactory.Options.outWidth]/[BitmapFactory.Options.outHeight]
     * the bounds pass fills in.
     *
     * The stream is read on [Dispatchers.IO] from inside the PickVisualMedia
     * result callback, within the URI read grant. Photo-picker grants live for
     * the process lifetime, so no persistable permission is taken (and must not
     * be — takePersistableUriPermission would throw on a PickVisualMedia URI).
     */
    suspend fun prepareImage(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // First pass: bounds only, so a huge source never fully decodes into
        // memory before we know its dimensions. A null InputStream is the only
        // "cannot open" signal here — the bounds decode itself returns null on
        // success (see the GOTCHA above), so its return value is intentionally
        // ignored; dimensions are validated from `bounds` instead.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw IllegalStateException("cannot open image"))
            .use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("undecodable image")
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        // Power-of-two subsample to get near the target cheaply, then an exact
        // scale below finishes the job.
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(longEdge, MAX_IMAGE_EDGE)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalStateException("cannot decode image")
        val scaled = downscale(decoded)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val bytes = out.toByteArray()
        // A zero-length re-encode means the decode produced nothing usable —
        // fail loudly rather than depositing an empty blob the recipient can't open.
        if (bytes.isEmpty()) throw IllegalStateException("empty image encode")
        Prepared(
            kind = AttachmentControlPayload.KIND_IMAGE,
            mimetype = "image/jpeg",
            filename = null,
            bytes = bytes,
        )
    }

    /**
     * Reads the picked file raw into memory, capped at [MAX_ATTACHMENT_BYTES]
     * (a friendly [TooLargeException] past the cap). kind = file, keeps the
     * provider display name for the recipient.
     */
    suspend fun prepareFile(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { readCapped(it) }
            ?: throw IllegalStateException("cannot open file")
        Prepared(
            kind = AttachmentControlPayload.KIND_FILE,
            mimetype = resolver.getType(uri) ?: "application/octet-stream",
            filename = displayName(context, uri),
            bytes = bytes,
        )
    }

    /** Reads the stream fully, but throws the moment it exceeds the cap. */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ATTACHMENT_BYTES) throw TooLargeException()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sampleSizeFor(sourceLongEdge: Int, targetLongEdge: Int): Int {
        var sample = 1
        // Halve until the subsampled long edge is within 2x the target, so the
        // exact scale afterward only ever shrinks (never upscales a blur).
        while (sourceLongEdge / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_IMAGE_EDGE) return bitmap
        val scale = MAX_IMAGE_EDGE.toFloat() / longEdge
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
}
