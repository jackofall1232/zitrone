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
      const sizePx = WATERMARK_TILE_DEFAULTS.tileSize;
      const canvas = document.createElement("canvas");
      canvas.width = sizePx;
      canvas.height = sizePx;
      drawFingerprintTile(canvas, localFingerprint);
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
