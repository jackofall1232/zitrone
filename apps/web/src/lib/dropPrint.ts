// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Compose a print-grade PNG of a QR "lemon drop" — the on-screen modal shows a
 * 240px QR for scanning, but a physical sticker needs far more resolution. This
 * renders a large white card (QR + lemon-slice mark + a burn-by caption) that a
 * user can save and print.
 *
 * The QR itself is regenerated at 1024px with error-correction level H, exactly
 * as the modal renders it, so the lemon-slice mark can sit over the center white
 * backing without breaking scans (H tolerates ~30% occlusion).
 */

import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import QRCode from "qrcode";
import { LemonSlice } from "@zitrone/ui";

const QR_PX = 1024;
const CARD_W = 1088; // QR_PX + 32px side padding on each side
const CARD_H = 1216; // 32 top pad + QR_PX + 160 caption zone
const QR_X = Math.round((CARD_W - QR_PX) / 2);
const QR_Y = 32;
const BACKING_PX = Math.round(QR_PX * 0.26); // white square the mark sits on
const MARK_PX = Math.round(QR_PX * 0.2); // the lemon-slice mark itself
const INK = "#0D0C00";
const PAPER = "#FFFFFF";

/**
 * Derive the download filename from a sticker URL. The URL format is
 * `https://zitrone.app/d/<16-byte base64url qr_id>`; we take the first 8 chars
 * of the last path segment (enough to disambiguate saved files without leaking
 * the full capability into a filename that might be shoulder-surfed). Exported
 * separately so the derivation is unit-testable as a pure function.
 */
export function dropPrintFilename(url: string): string {
  let last = "";
  try {
    const parsed = new URL(url);
    const segments = parsed.pathname.split("/").filter(Boolean);
    last = segments[segments.length - 1] ?? "";
  } catch {
    // Not a well-formed absolute URL — fall back to raw path splitting.
    const parts = url.split(/[/?#]/).filter(Boolean);
    last = parts[parts.length - 1] ?? "";
  }
  // Keep only base64url-safe characters, then the first 8.
  const id = last.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 8);
  return `zitrone-drop-${id || "drop"}.png`;
}

/** Localized burn-by phrasing, matching the on-screen modal's wording. */
function burnsByLabel(expiresAt: string): string {
  const d = new Date(expiresAt);
  const when = Number.isNaN(d.getTime()) ? expiresAt : d.toLocaleString();
  return `🔥 Burns by ${when} if unclaimed.`;
}

/**
 * Render the LemonSlice logo mark to an SVG object-URL, load it as an image, and
 * draw it centered on the card over a white backing square. renderToStaticMarkup
 * keeps this a single source of truth with the on-screen mark — no hand-drawn
 * canvas approximation to drift out of sync.
 */
async function drawLemonMark(ctx: CanvasRenderingContext2D): Promise<void> {
  let svg = renderToStaticMarkup(
    createElement(LemonSlice, { variant: "logo_mark", size: MARK_PX, label: "Zitrone" }),
  );
  // React does not emit the SVG namespace; an <img> won't render the markup
  // without it, so inject it if absent.
  if (!svg.includes("xmlns")) {
    svg = svg.replace("<svg", '<svg xmlns="http://www.w3.org/2000/svg"');
  }

  const cx = QR_X + QR_PX / 2;
  const cy = QR_Y + QR_PX / 2;

  // White backing square so the mark never eats real QR modules.
  ctx.fillStyle = PAPER;
  ctx.fillRect(cx - BACKING_PX / 2, cy - BACKING_PX / 2, BACKING_PX, BACKING_PX);

  const blobUrl = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
  try {
    await new Promise<void>((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        ctx.drawImage(img, cx - MARK_PX / 2, cy - MARK_PX / 2, MARK_PX, MARK_PX);
        resolve();
      };
      img.onerror = () => reject(new Error("lemon-slice mark failed to load"));
      img.src = blobUrl;
    });
  } finally {
    URL.revokeObjectURL(blobUrl);
  }
}

/**
 * Compose the print-grade PNG. Returns a PNG Blob; the caller handles saving
 * (browser download anchor or the Tauri save dialog). Runs entirely offscreen.
 */
export async function composeDropPrintPng(url: string, expiresAt: string): Promise<Blob> {
  const card = document.createElement("canvas");
  card.width = CARD_W;
  card.height = CARD_H;
  const ctx = card.getContext("2d");
  if (!ctx) throw new Error("2D canvas context unavailable");

  // White card.
  ctx.fillStyle = PAPER;
  ctx.fillRect(0, 0, CARD_W, CARD_H);

  // QR at print resolution — same options as the modal so the scan behavior is
  // identical, just larger. margin 1 keeps the mandatory quiet zone.
  const qrCanvas = document.createElement("canvas");
  await QRCode.toCanvas(qrCanvas, url, {
    errorCorrectionLevel: "H",
    margin: 1,
    width: QR_PX,
    color: { dark: INK, light: PAPER },
  });
  ctx.drawImage(qrCanvas, QR_X, QR_Y, QR_PX, QR_PX);

  await drawLemonMark(ctx);

  // Burn-by caption centered in the bottom zone, in dark ink.
  ctx.fillStyle = INK;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.font =
    "500 34px ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif";
  const captionY = QR_Y + QR_PX + (CARD_H - (QR_Y + QR_PX)) / 2;
  ctx.fillText(burnsByLabel(expiresAt), CARD_W / 2, captionY, CARD_W - 64);

  return await new Promise<Blob>((resolve, reject) => {
    card.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error("PNG encoding failed"));
    }, "image/png");
  });
}
