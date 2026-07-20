// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import {
  fingerprintTileAnchors,
  WATERMARK_TILE_DEFAULTS,
  type FingerprintTileOptions,
} from "./fingerprintTile.js";

// A representative run width for the 74-char fingerprint at 10.5px mono; the
// geometry takes it as an argument, so these tests need no canvas/DOM.
const RUN_WIDTH = 300;

describe("fingerprintTileAnchors", () => {
  it("keeps only anchors inside the tile domain [0,S)²", () => {
    const anchors = fingerprintTileAnchors(RUN_WIDTH, WATERMARK_TILE_DEFAULTS);
    const S = WATERMARK_TILE_DEFAULTS.tileSize;
    expect(anchors.length).toBeGreaterThan(0);
    for (const a of anchors) {
      expect(a.x).toBeGreaterThanOrEqual(0);
      expect(a.x).toBeLessThan(S);
      expect(a.y).toBeGreaterThanOrEqual(0);
      expect(a.y).toBeLessThan(S);
    }
  });

  it("has an anchor density near S²/(rowGap·pitch)", () => {
    const cases: FingerprintTileOptions[] = [
      WATERMARK_TILE_DEFAULTS,
      { ...WATERMARK_TILE_DEFAULTS, rotationDeg: -45, rowGapPx: 40 },
      { ...WATERMARK_TILE_DEFAULTS, fontPx: 12, brickOffset: 0 },
    ];
    for (const opts of cases) {
      const pitch = RUN_WIDTH + opts.fontPx * 4;
      const expected = (opts.tileSize * opts.tileSize) / (opts.rowGapPx * pitch);
      const count = fingerprintTileAnchors(RUN_WIDTH, opts).length;
      expect(count).toBeGreaterThanOrEqual(expected * 0.5);
      expect(count).toBeLessThanOrEqual(expected * 1.5);
    }
  });

  it("is deterministic across calls", () => {
    const a = fingerprintTileAnchors(RUN_WIDTH, WATERMARK_TILE_DEFAULTS);
    const b = fingerprintTileAnchors(RUN_WIDTH, WATERMARK_TILE_DEFAULTS);
    expect(a).toEqual(b);
  });

  it("alternate rows carry the brick flag", () => {
    // With a non-zero brick offset at least some kept anchors must be on a
    // brick row and some not — proving the odd-row shift is applied.
    const anchors = fingerprintTileAnchors(RUN_WIDTH, WATERMARK_TILE_DEFAULTS);
    expect(anchors.some((a) => a.brickRow)).toBe(true);
    expect(anchors.some((a) => !a.brickRow)).toBe(true);
  });
});
