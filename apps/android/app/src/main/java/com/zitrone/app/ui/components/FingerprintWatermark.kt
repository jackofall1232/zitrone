// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The always-on "security paper" watermark: a faint, toroidally-tiled diagonal
 * lattice of the VIEWER'S OWN 60-hex identity fingerprint painted behind the
 * chat surfaces. It is a deterrence layer — anyone photographing the screen is
 * consciously reminded that what they capture is marked as *theirs* — and it is
 * deliberately ALWAYS ON, with no settings toggle (HoboJoe sign-off: a toggle
 * turns a deterrent into a checkbox nobody finds; its value is that it is never
 * negotiable). The exact geometry is treatment "G2", mirrored from the web
 * carrier (packages/ui/src/fingerprintTile.ts) so all clients paint the same
 * paper.
 *
 * Performance contract: the tile is pre-rendered to a single Bitmap ONCE per
 * (fingerprint, density) and repeated by the GPU via a [ShaderBrush]. Nothing
 * allocates in the per-frame draw lambda, so scrolling the chat over the paper
 * costs essentially nothing — the shader tiles the same texture the compositor
 * already holds.
 */
object WatermarkTileDefaults {
    /** Fundamental tile edge, in tile-space px before the density scale. */
    const val tileSize: Float = 512f
    const val rotationDeg: Float = -24f
    const val alpha: Float = 0.045f
    const val fontPx: Float = 10.5f
    const val rowGapPx: Float = 28f
    const val brickOffset: Float = 0.5f

    /** Lemon #F5E642, opaque; the per-run [alpha] is applied on the paint. */
    val color: Int = 0xFFF5E642.toInt()

    // DEVIATION FROM WEB: the tile ground is TRANSPARENT here. The web bakes an
    // opaque #0D0C00 ground into the tile only because that same tile doubles as
    // the LSB stego carrier and must be a complete image on its own. Android has
    // no stego layer: the screen already paints BackgroundPrimary beneath this
    // modifier, and an opaque ground would (a) be redundant and (b) hide any
    // surfaces meant to sit above the ground but below the paper (e.g. the
    // lemon-drop veil). So we draw runs onto transparency and let the real
    // background show through.
}

/** One drawn fingerprint run, anchored in tile (canvas) space. */
data class WatermarkAnchor(val x: Float, val y: Float, val brickRow: Boolean)

/**
 * The pure geometry half of the tile, kept free of android.graphics so it is
 * unit-testable on a plain JVM. Given the measured pixel width of one
 * fingerprint run and the tile parameters, it returns every run anchor that
 * lands INSIDE the fundamental tile domain [0,tileSize)². Rows march along the
 * rotated η axis (alternate rows brick-shifted by `pitch * brickOffset`);
 * repeats march along the ξ axis by `pitch`. Anchors outside the tile are
 * dropped here; the toroidal edge-wrap (drawing each kept run at all nine
 * lattice offsets) is the renderer's job, not the geometry's.
 *
 * `pitch` is authoritative for the ξ step and already folds in the run width
 * (the renderer passes `runWidth + fontPx*4`); `runWidth` is retained in the
 * signature so callers document the run the pitch was derived from.
 */
object FingerprintTileGeometry {
    // runWidth is documented context (pitch = runWidth + fontPx*4 upstream), not
    // used directly: pitch is authoritative for the ξ step.
    @Suppress("UNUSED_PARAMETER")
    fun anchors(
        runWidth: Float,
        tileSize: Float,
        rotationDeg: Float,
        rowGap: Float,
        pitch: Float,
        brickOffset: Float,
    ): List<WatermarkAnchor> {
        // Non-positive steps would loop forever (ANR); an empty tile is the
        // honest degenerate output.
        if (pitch <= 0f || rowGap <= 0f || tileSize <= 0f) return emptyList()
        val theta = Math.toRadians(rotationDeg.toDouble())
        val cos = cos(theta).toFloat()
        val sin = sin(theta).toFloat()
        val span = tileSize * 1.6f
        val half = tileSize / 2f
        val out = ArrayList<WatermarkAnchor>()
        var eta = -span
        var row = 0
        while (eta < span) {
            val brickRow = row % 2 == 1
            val off = if (brickRow) pitch * brickOffset else 0f
            var xi = -span + off
            while (xi < span) {
                val ax = half + xi * cos - eta * sin // anchor, tile space
                val ay = half + xi * sin + eta * cos
                if (ax >= 0f && ax < tileSize && ay >= 0f && ay < tileSize) {
                    out.add(WatermarkAnchor(ax, ay, brickRow))
                }
                xi += pitch
            }
            eta += rowGap
            row++
        }
        return out
    }
}

/**
 * Render the G2 fingerprint tile to a Bitmap the caller can hand to a repeating
 * shader. Sized to (512·density)² and capped at density 2 so the texture never
 * exceeds 1024² = 4 MB. Pure function of (fingerprint, density) — no Compose,
 * no shared state — so it is trivially cacheable by the modifier's `remember`.
 */
fun renderFingerprintTile(fingerprint: String, density: Float): Bitmap {
    val d = density.coerceAtMost(2f)
    // The bitmap edge is the integral tile size; the geometry and the nine-
    // offset wrap use this SAME value so the repeat is seamless (any mismatch
    // between the tile domain and the texture edge would show as a seam).
    val s = (WatermarkTileDefaults.tileSize * d).roundToInt()
    val sf = s.toFloat()

    val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // No fillRect: ARGB_8888 starts fully transparent — the deliberate deviation
    // from the web carrier (see WatermarkTileDefaults).

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = WatermarkTileDefaults.fontPx * d
        textAlign = Paint.Align.LEFT
        color = WatermarkTileDefaults.color
        // Set alpha AFTER color: color resets the alpha channel to opaque.
        alpha = (WatermarkTileDefaults.alpha * 255f).roundToInt()
    }

    val runWidth = paint.measureText(fingerprint)
    val pitch = runWidth + WatermarkTileDefaults.fontPx * d * 4f
    val rowGap = WatermarkTileDefaults.rowGapPx * d
    // Canvas.drawText's y is the BASELINE; the web uses textBaseline "middle",
    // so shift the baseline up by half the text's vertical extent to centre the
    // run on its anchor exactly as the web does.
    val baseline = -((paint.ascent() + paint.descent()) / 2f)

    val anchors = FingerprintTileGeometry.anchors(
        runWidth = runWidth,
        tileSize = sf,
        rotationDeg = WatermarkTileDefaults.rotationDeg,
        rowGap = rowGap,
        pitch = pitch,
        brickOffset = WatermarkTileDefaults.brickOffset,
    )
    for (anchor in anchors) {
        for (dx in -1..1) {
            for (dy in -1..1) {
                canvas.save()
                canvas.translate(anchor.x + dx * sf, anchor.y + dy * sf)
                canvas.rotate(WatermarkTileDefaults.rotationDeg) // degrees
                canvas.drawText(fingerprint, 0f, baseline, paint)
                canvas.restore()
            }
        }
    }
    return bitmap
}

/**
 * Paint the security-paper watermark behind whatever this modifier is applied
 * to. Apply it AFTER the surface's `.background(...)` so the paper sits above
 * the ground and below the content. A null/blank fingerprint (identity not yet
 * unlocked) is a no-op. The tile is pre-rendered once per (fingerprint,
 * density) and GPU-repeated — see the class doc for the perf contract.
 */
fun Modifier.fingerprintWatermark(fingerprint: String?): Modifier {
    if (fingerprint.isNullOrBlank()) return this
    return this.composed {
        val density = LocalDensity.current.density.coerceAtMost(2f)
        val brush = remember(fingerprint, density) {
            ShaderBrush(
                ImageShader(
                    renderFingerprintTile(fingerprint, density).asImageBitmap(),
                    TileMode.Repeated,
                    TileMode.Repeated,
                ),
            )
        }
        drawBehind { drawRect(brush) }
    }
}
