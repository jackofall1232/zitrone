// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.ui.components.FingerprintTileGeometry
import com.zitrone.app.ui.components.WatermarkTileDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure-JVM coverage of the security-paper tile geometry (treatment "G2"). The
 * android.graphics rendering half is not exercised here — only the placement
 * math, which the renderer and the web carrier both depend on.
 */
class FingerprintTileGeometryTest {

    private val S = WatermarkTileDefaults.tileSize // 512
    private val rowGap = WatermarkTileDefaults.rowGapPx // 28
    // A representative run: the 60-hex fingerprint formats to 15 groups of 4
    // ("XXXX " * 15 → 74 chars); at fontPx 10.5 monospace one char advances
    // ~0.6em, so ~466px, and pitch folds in fontPx*4.
    private val runWidth = 466f
    private val pitch = runWidth + WatermarkTileDefaults.fontPx * 4f // 508

    private fun g2Anchors() = FingerprintTileGeometry.anchors(
        runWidth = runWidth,
        tileSize = S,
        rotationDeg = WatermarkTileDefaults.rotationDeg,
        rowGap = rowGap,
        pitch = pitch,
        brickOffset = WatermarkTileDefaults.brickOffset,
    )

    @Test
    fun `every anchor lands inside the fundamental tile domain`() {
        for (a in g2Anchors()) {
            assertTrue("x=${a.x} out of [0,$S)", a.x >= 0f && a.x < S)
            assertTrue("y=${a.y} out of [0,$S)", a.y >= 0f && a.y < S)
        }
    }

    @Test
    fun `anchor count sits within 50 percent of the lattice density estimate`() {
        // Rows every rowGap along η, runs every pitch along ξ: one anchor per
        // rowGap·pitch cell across the S² tile.
        val expected = (S * S) / (rowGap * pitch)
        val actual = g2Anchors().size.toFloat()
        assertTrue(
            "expected ~$expected anchors, got $actual",
            actual in (expected * 0.5f)..(expected * 1.5f),
        )
    }

    @Test
    fun `geometry is deterministic`() {
        assertEquals(g2Anchors(), g2Anchors())
    }

    @Test
    fun `odd rows are brick-shifted by half a pitch`() {
        // Rotate by 0 so rows are axis-aligned and the ξ phase reads directly
        // off x. Even rows begin at ξ = -span; odd rows at ξ = -span +
        // pitch·brickOffset, so their x-phase leads by pitch/2.
        val span = S * 1.6f
        val anchors = FingerprintTileGeometry.anchors(
            runWidth = runWidth,
            tileSize = S,
            rotationDeg = 0f,
            rowGap = 40f,
            pitch = 100f,
            brickOffset = 0.5f,
        )
        val p = 100f
        val tol = 1f
        fun phase(x: Float): Float {
            val r = ((x - S / 2f + span) % p + p) % p
            return r
        }
        val even = anchors.filter { !it.brickRow }
        val odd = anchors.filter { it.brickRow }
        assertTrue("need both row parities", even.isNotEmpty() && odd.isNotEmpty())
        // Even rows: phase pinned to 0 (equivalently p, modulo wrap).
        for (a in even) {
            val r = phase(a.x)
            assertTrue("even-row phase $r not ~0", min(r, p - r) < tol)
        }
        // Odd rows: phase pinned to half a pitch.
        for (a in odd) {
            assertTrue("odd-row phase ${phase(a.x)} not ~${p / 2}", abs(phase(a.x) - p / 2f) < tol)
        }
    }
}
