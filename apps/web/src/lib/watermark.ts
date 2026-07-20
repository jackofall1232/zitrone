// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { embedWatermarkInCanvas, generateInvisibleWatermark } from "@zitrone/crypto";
import { drawFingerprintTile, WATERMARK_TILE_DEFAULTS } from "@zitrone/ui";

/**
 * Compose the chat-surface background used by both ChatView and ChatList: the
 * visible "security paper" fingerprint tile, with the invisible LSB leak-
 * attribution watermark embedded into ITS OWN pixels. Returns a PNG data URL
 * plus the pixel size it must be tiled at.
 *
 * PNG is mandatory — a lossy re-encode (jpeg) would scramble the LSBs and
 * destroy the stego layer. Because the single background image already carries
 * the stego payload in its pixels, callers must layer nothing else over it in
 * CSS: a second layer would corrupt a captured screenshot's stego bits.
 *
 * While `localFingerprint` is null (identity not yet unlocked) there is no
 * fingerprint to tile, so we fall back to the prior invisible-only behavior.
 * Returns null on any failure so the caller simply renders no background.
 */
export function composeChatWatermark(
  accountId: string,
  conversationId: string,
  localFingerprint: string | null,
): { url: string; sizePx: number } | null {
  try {
    if (localFingerprint) {
      // Render the carrier at DEVICE-pixel resolution while tiling it at CSS
      // size: on a DPR-2 display a 512-CSS-px tile paints 1024 device pixels,
      // and if the source were only 512px the browser's resampling would
      // duplicate/interpolate the RGB LSBs and destroy the stego layer in a
      // native-resolution screenshot (PR #8 round 2). With the source at
      // tileSize·dpr the mapping is 1 source pixel : 1 device pixel and the
      // LSBs survive intact. Integer DPRs map exactly; fractional DPRs are
      // best-effort (the visible deterrent is unaffected either way). The
      // geometry scales with the same density treatment Android uses, so the
      // VISUAL period stays one tileSize in CSS px on every display.
      const sizePx = WATERMARK_TILE_DEFAULTS.tileSize;
      const dpr = Math.min(Math.max(Math.round(window.devicePixelRatio || 1), 1), 2);
      const canvas = document.createElement("canvas");
      canvas.width = sizePx * dpr;
      canvas.height = sizePx * dpr;
      drawFingerprintTile(canvas, localFingerprint, {
        tileSize: WATERMARK_TILE_DEFAULTS.tileSize * dpr,
        fontPx: WATERMARK_TILE_DEFAULTS.fontPx * dpr,
        rowGapPx: WATERMARK_TILE_DEFAULTS.rowGapPx * dpr,
      });
      embedWatermarkInCanvas(canvas, accountId, conversationId);
      return { url: canvas.toDataURL(), sizePx };
    }
    // No fingerprint yet: invisible-only, exactly as before this feature.
    const canvas = generateInvisibleWatermark(accountId, conversationId, 256, 256, "#0D0C00");
    return { url: canvas.toDataURL(), sizePx: 256 };
  } catch {
    return null;
  }
}
