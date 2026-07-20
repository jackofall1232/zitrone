// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.zitrone.app.ui.theme.BackgroundSecondary
import com.zitrone.app.ui.theme.BorderColor
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.MonoFamily
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The five allowlisted QR-drop lifetimes, in hours — the ONE Kotlin source of
 * truth on this side, mirroring protocol QR_DROP_TTL_HOURS and the web's
 * QR_TTL_LABELS. Deliberately capped at two weeks: a sticker in the wild should
 * not outlive that. The relay rejects any TTL not on this list.
 */
val QR_DROP_TTL_HOURS: List<Int> = listOf(24, 48, 72, 168, 336)

/** Human label for a TTL option — same wording as the web's QR_TTL_LABELS. */
fun qrDropTtlLabel(hours: Int): String = when (hours) {
    24 -> "24h"
    48 -> "48h"
    72 -> "72h"
    168 -> "1 week"
    336 -> "2 weeks"
    else -> "${hours}h"
}

/**
 * Pick a lifetime for a QR drop, then seal. While sealing (one-shot X3DH + a
 * ~1M-hash proof-of-work) the picker shows a working state instead of freezing
 * silently — mirror of the web QrTtlPicker.
 */
@Composable
fun QrTtlPickerDialog(
    sealing: Boolean,
    error: String?,
    onPick: (hours: Int) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!sealing) onCancel() }) {
        DialogCard {
            Text(
                text = "Seal as a QR drop",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = "How long should this drop live on the relay before it burns unclaimed?",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            if (sealing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    LemonSliceSpinner(size = 28.dp)
                    Text(
                        text = "Sealing… (solving the deposit proof-of-work)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    QR_DROP_TTL_HOURS.forEach { hours ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, BorderColor, RoundedCornerShape(50))
                                .background(Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(
                                onClick = { onPick(hours) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = qrDropTtlLabel(hours),
                                    fontSize = MaterialTheme.typography.labelLarge.fontSize,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
            error?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = com.zitrone.app.ui.theme.BurnOrange)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { if (!sealing) onCancel() }, enabled = !sealing) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        }
    }
}

/**
 * The QR "lemon drop" result dialog: a scannable QR of the sticker URL with the
 * lemon-slice mark punched through the center, honest recipient-addressed copy,
 * the burn-by line, copy-link, and Save/Share for a print-grade sticker. Mirror
 * of the web QrDropModal — deliberately NOT worded as anonymous: anyone can
 * fetch the sealed blob, but only the named recipient's device can open it.
 */
@Composable
fun QrDropResultDialog(
    url: String,
    expiresAt: String,
    recipientName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // On-screen QR (240dp) and the print-grade PNG are both rendered off the
    // main thread; the PNG is retained so Save and Share reuse one composition.
    val qrBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.Default) {
            QrDropSticker.renderQrBitmap(url, 512).asImageBitmap()
        }
    }
    val printPng by produceState<ByteArray?>(null, url, expiresAt) {
        value = withContext(Dispatchers.Default) { QrDropSticker.composePrintPng(url, expiresAt) }
    }
    val filename = remember(url) { QrDropSticker.printFilename(url) }
    val burnsBy = remember(expiresAt) { QrDropSticker.burnsByLabel(expiresAt) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val bytes = printPng
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    Dialog(onDismissRequest = onClose) {
        DialogCard {
            Text(
                text = "QR drop sealed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )

            // QR on a white card (high contrast in the dark UI) with the mark
            // overlaid on a small white backing so it never eats real modules.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "QR code encoding this drop's link",
                            modifier = Modifier.size(240.dp),
                        )
                        // White backing (~26%) + lemon-slice mark (~20%).
                        Box(
                            modifier = Modifier
                                .size((240 * 0.26f).dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            LemonSliceLogo(size = (240 * 0.2f).dp)
                        }
                    } else {
                        Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                            LemonSliceSpinner(size = 48.dp)
                        }
                    }
                }
            }

            Text(
                text = buildString {
                    append("Anyone who scans this can fetch the sealed blob from the relay, but only ")
                    append(recipientName)
                    append("'s device holds the key to open it. This drop is addressed to them by name — it is not anonymous.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Text(
                text = url,
                fontFamily = MonoFamily,
                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                color = Lemon,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )

            Text(text = "🔥 Burns by $burnsBy if unclaimed.", style = MaterialTheme.typography.labelMedium, color = TextMuted)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton("Copy link") { clipboard.setText(AnnotatedString(url)) }
                PillButton("Save image", enabled = printPng != null) { saveLauncher.launch(filename) }
                PillButton("Share", enabled = printPng != null) {
                    printPng?.let { QrDropSticker.sharePng(context, it, filename) }
                }
                TextButton(onClick = onClose) { Text("Done", color = TextSecondary) }
            }

            Text(
                text = "The saved image contains the drop link — treat it like the printed sticker itself.",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun DialogCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .background(BackgroundSecondary)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun PillButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) Lemon else BorderColor)
            .padding(horizontal = 4.dp),
    ) {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(label, color = TextOnLemon, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Pure(ish) rendering of the sticker artwork: the scannable QR and the
 * print-grade PNG card, mirroring apps/web dropPrint.ts (1088×1216 card, 1024px
 * QR at EC-level H margin 1, lemon-slice mark on a white backing, burn-by
 * caption). No Compose — plain zxing + android.graphics — so it runs off the
 * main thread and the geometry reuses [LemonSliceMath] (the same 8-wedge motif
 * the on-screen mark draws).
 */
object QrDropSticker {

    private const val INK = 0xFF0D0C00.toInt()
    private const val PAPER = AndroidColor.WHITE
    private const val LEMON = 0xFFF5E642.toInt()
    private const val LEMON_DEEP = 0xFFD4C200.toInt()

    private const val QR_PX = 1024
    private const val CARD_W = 1088 // QR_PX + 32px padding each side
    private const val CARD_H = 1216 // 32 top pad + QR_PX + 160 caption zone
    private const val QR_X = (CARD_W - QR_PX) / 2
    private const val QR_Y = 32

    /** Render just the QR (used on screen). EC-level H + margin 1, ink on paper. */
    fun renderQrBitmap(url: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) INK else PAPER)
            }
        }
        return bmp
    }

    /** Compose the print-grade card and return it PNG-encoded. */
    fun composePrintPng(url: String, expiresAt: String): ByteArray {
        val card = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(PAPER)

        val qr = renderQrBitmap(url, QR_PX)
        canvas.drawBitmap(qr, QR_X.toFloat(), QR_Y.toFloat(), null)

        val cx = QR_X + QR_PX / 2f
        val cy = QR_Y + QR_PX / 2f
        val backing = (QR_PX * 0.26f)
        val paperPaint = Paint().apply { color = PAPER; isAntiAlias = true }
        canvas.drawRect(cx - backing / 2f, cy - backing / 2f, cx + backing / 2f, cy + backing / 2f, paperPaint)
        drawLemonMark(canvas, cx, cy, QR_PX * 0.2f)

        val caption = burnsByLabel(expiresAt)
        val textPaint = Paint().apply {
            color = INK
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 34f
        }
        val captionY = QR_Y + QR_PX + (CARD_H - (QR_Y + QR_PX)) / 2f
        // Baseline-centre the text roughly in the caption zone.
        canvas.drawText(caption, CARD_W / 2f, captionY + textPaint.textSize / 3f, textPaint)

        val out = java.io.ByteArrayOutputStream()
        card.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** The 8-wedge lemon slice, drawn straight onto [canvas] with the same
     *  geometry as [drawLemonSlice] (LemonSlice.kt) via [LemonSliceMath]. */
    private fun drawLemonMark(canvas: Canvas, cx: Float, cy: Float, markPx: Float) {
        val outerRadius = markPx / 2f
        val rindStroke = maxOf(outerRadius * 0.10f, 1f)
        val rindPaint = Paint().apply {
            color = LEMON_DEEP
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = rindStroke
        }
        canvas.drawCircle(cx, cy, outerRadius - rindStroke / 2f, rindPaint)

        val wedgeOuter = outerRadius - rindStroke * 1.9f
        val wedgeInner = outerRadius * 0.18f
        val sweep = LemonSliceMath.segmentSweepDegrees()
        val outerRect = RectF(cx - wedgeOuter, cy - wedgeOuter, cx + wedgeOuter, cy + wedgeOuter)
        val innerRect = RectF(cx - wedgeInner, cy - wedgeInner, cx + wedgeInner, cy + wedgeInner)
        val wedgePaint = Paint().apply { color = LEMON; isAntiAlias = true; style = Paint.Style.FILL }
        for (index in 0 until LemonSliceMath.SEGMENT_COUNT) {
            val start = LemonSliceMath.segmentStartAngleDegrees(index)
            val path = Path().apply {
                arcTo(outerRect, start, sweep, true)
                arcTo(innerRect, start + sweep, -sweep, false)
                close()
            }
            canvas.drawPath(path, wedgePaint)
        }
        canvas.drawCircle(cx, cy, (wedgeInner * 0.45f).coerceAtLeast(0.5f), wedgePaint)
    }

    /** Localized burn-by phrasing, matching the on-screen dialog's wording. */
    fun burnsByLabel(expiresAt: String): String {
        val parsed = runCatching { java.time.Instant.parse(expiresAt) }.getOrNull()
        val whenStr = if (parsed != null) {
            DateFormat.getDateTimeInstance().format(Date(parsed.toEpochMilli()))
        } else {
            expiresAt
        }
        return whenStr
    }

    /**
     * Filename from the sticker URL: the first 8 base64url chars of the last
     * path segment (enough to disambiguate saved files without leaking the full
     * capability). Mirror of dropPrint.ts `dropPrintFilename`.
     */
    fun printFilename(url: String): String {
        val lastSegment = url.substringAfterLast('/', "")
        val id = lastSegment.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(8)
        return "zitrone-drop-${id.ifBlank { "drop" }}.png"
    }

    /** Share the PNG via a FileProvider content URI so printer/gallery apps can
     *  reach it (SEND stream). The file lives in cache and is world-unreadable
     *  except through the granted, read-only URI. */
    fun sharePng(context: Context, png: ByteArray, filename: String) {
        runCatching {
            val dir = File(context.cacheDir, "dropshare").apply { mkdirs() }
            val file = File(dir, filename)
            file.writeBytes(png)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share QR drop").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
