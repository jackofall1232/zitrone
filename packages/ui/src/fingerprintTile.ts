// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The visible "security paper" watermark: a faint, toroidally-tiled diagonal
 * lattice of the VIEWER'S OWN identity-key fingerprint, painted behind the chat
 * surfaces. It is a deterrence layer — a photographer consciously notices the
 * screen is marked as theirs — and is deliberately always on, with no settings
 * toggle. This is the exact algorithm signed off in the review rig
 * (docs/design/watermark-tile-preview.html, `makeTile`), minus the rig's
 * devicePixelRatio scaling: this function is deterministic in TILE SPACE and the
 * caller owns the canvas size, so the geometry is unit-testable without a DOM.
 */

/** The monospace stack the fingerprint runs are drawn in (JetBrains Mono ships). */
const MONO = 'ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace';

export interface FingerprintTileOptions {
  tileSize: number;
  rotationDeg: number;
  alpha: number;
  fontPx: number;
  rowGapPx: number;
  brickOffset: number;
  color: string;
  background: string;
}

// HoboJoe-approved treatment "G2" — do not tune without a new design sign-off.
export const WATERMARK_TILE_DEFAULTS: FingerprintTileOptions = {
  tileSize: 512,
  rotationDeg: -24,
  alpha: 0.045,
  fontPx: 10.5,
  rowGapPx: 28,
  brickOffset: 0.5,
  color: "#F5E642",
  background: "#0D0C00",
};

/**
 * The pure geometry half of the tile: given the measured pixel width of one
 * fingerprint run and the tile options, return every run anchor that lands
 * INSIDE the fundamental tile domain [0,tileSize)². Rows march along the
 * rotated η axis (alternate rows brick-shifted by `pitch*brickOffset`); repeats
 * march along the ξ axis by `pitch = runWidth + fontPx*4`. Anchors outside the
 * tile are dropped here; the toroidal edge-wrap (drawing each kept run at all
 * nine lattice offsets) is applied by the renderer, not the geometry. Exported
 * separately so density/placement can be asserted in node with no canvas.
 */
export function fingerprintTileAnchors(
  runWidth: number,
  opts: FingerprintTileOptions,
): Array<{ x: number; y: number; brickRow: boolean }> {
  const S = opts.tileSize;
  const pitch = runWidth + opts.fontPx * 4;
  // Non-positive steps would loop forever (opts are caller-overridable) —
  // an empty tile is the honest degenerate output.
  if (S <= 0 || pitch <= 0 || opts.rowGapPx <= 0) return [];
  const theta = (opts.rotationDeg * Math.PI) / 180;
  const cos = Math.cos(theta);
  const sin = Math.sin(theta);
  const span = S * 1.6;
  const anchors: Array<{ x: number; y: number; brickRow: boolean }> = [];
  let row = 0;
  for (let eta = -span; eta < span; eta += opts.rowGapPx, row++) {
    const brickRow = row % 2 === 1;
    const off = brickRow ? pitch * opts.brickOffset : 0;
    for (let xi = -span + off; xi < span; xi += pitch) {
      const ax = S / 2 + xi * cos - eta * sin; // anchor, canvas space
      const ay = S / 2 + xi * sin + eta * cos;
      if (ax < 0 || ax >= S || ay < 0 || ay >= S) continue;
      anchors.push({ x: ax, y: ay, brickRow });
    }
  }
  return anchors;
}

/**
 * Paint the fingerprint tile onto `canvas` (which the caller has sized to
 * tileSize²). Fills the background, then draws each kept anchor's run at all
 * nine lattice offsets (dx,dy ∈ {−1,0,1}) so a run crossing an edge re-enters on
 * the opposite side — a seamless toroidal wrap.
 */
export function drawFingerprintTile(
  canvas: HTMLCanvasElement,
  fingerprint: string,
  opts: Partial<FingerprintTileOptions> = {},
): void {
  const o: FingerprintTileOptions = { ...WATERMARK_TILE_DEFAULTS, ...opts };
  const S = o.tileSize;
  const ctx = canvas.getContext("2d")!;
  ctx.fillStyle = o.background;
  ctx.fillRect(0, 0, S, S);

  ctx.font = `500 ${o.fontPx}px ${MONO}`;
  ctx.textBaseline = "middle";
  const runWidth = ctx.measureText(fingerprint).width;
  const theta = (o.rotationDeg * Math.PI) / 180;
  ctx.fillStyle = o.color;
  ctx.globalAlpha = o.alpha;

  for (const anchor of fingerprintTileAnchors(runWidth, o)) {
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        ctx.save();
        ctx.translate(anchor.x + dx * S, anchor.y + dy * S);
        ctx.rotate(theta);
        ctx.fillText(fingerprint, 0, 0);
        ctx.restore();
      }
    }
  }
}
