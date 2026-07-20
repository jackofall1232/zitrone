// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Invisible watermarking for leak attribution: the recipient's ID and a
 * timestamp are steganographically embedded into message-background pixels via
 * least-significant-bit encoding. Invisible to the eye, survives lossless
 * screenshots — if a capture is shared, the watermark identifies the recipient.
 */

import { utf8Decode, utf8Encode } from "./encoding.js";

const MAGIC = 0x5b; // "[" — marks a Zitrone watermark stream

/**
 * Embed payload bits into the LSB of the R/G/B channels of RGBA pixel data
 * (alpha untouched — compositing would destroy it). Pure function so it is
 * testable outside the DOM. Layout: magic(1) || length(2 BE) || payload.
 */
export function embedWatermarkBits(pixels: Uint8ClampedArray, payload: Uint8Array): void {
  const stream = new Uint8Array(3 + payload.length);
  stream[0] = MAGIC;
  stream[1] = (payload.length >> 8) & 0xff;
  stream[2] = payload.length & 0xff;
  stream.set(payload, 3);

  const bitsNeeded = stream.length * 8;
  const channelsAvailable = Math.floor(pixels.length / 4) * 3;
  if (bitsNeeded > channelsAvailable) throw new Error("payload too large for canvas");

  let bit = 0;
  for (let px = 0; bit < bitsNeeded; px += 4) {
    for (let c = 0; c < 3 && bit < bitsNeeded; c++, bit++) {
      const byte = stream[bit >> 3]!;
      const value = (byte >> (7 - (bit & 7))) & 1;
      pixels[px + c] = (pixels[px + c]! & 0xfe) | value;
    }
  }
}

/** Recover an embedded watermark payload, or null if none is present. */
export function extractWatermarkBits(pixels: Uint8ClampedArray): Uint8Array | null {
  // Header alone needs 24 bits = 8 pixels (3 channels each) = 32 bytes.
  if (pixels.length < 32) return null;
  const readBytes = (count: number, startBit: number): Uint8Array => {
    const out = new Uint8Array(count);
    for (let bit = 0; bit < count * 8; bit++) {
      const absolute = startBit + bit;
      const px = Math.floor(absolute / 3) * 4;
      const c = absolute % 3;
      const value = pixels[px + c]! & 1;
      out[bit >> 3] = (out[bit >> 3]! << 1) | value;
    }
    return out;
  };

  const header = readBytes(3, 0);
  if (header[0] !== MAGIC) return null;
  const length = (header[1]! << 8) | header[2]!;
  if (length === 0 || length * 8 > Math.floor(pixels.length / 4) * 3 - 24) return null;
  return readBytes(length, 24);
}

export interface WatermarkPayload {
  userId: string;
  messageId: string;
  timestamp: string;
}

export function decodeWatermarkPayload(bytes: Uint8Array): WatermarkPayload {
  return JSON.parse(utf8Decode(bytes)) as WatermarkPayload;
}

/**
 * Pixel-level core of the canvas embed, factored out so it is testable outside
 * the DOM (node has no canvas): LSB-embed `payload` into a raw RGBA buffer of
 * the given dimensions. The buffer's existing content is preserved except for
 * the R/G/B LSBs — so this works over already-drawn pixels (e.g. a rendered
 * watermark tile), not just a flat fill.
 */
export function embedWatermarkInPixels(
  data: Uint8ClampedArray,
  width: number,
  height: number,
  payload: Uint8Array,
): void {
  if (data.length !== width * height * 4) {
    throw new Error("pixel buffer length does not match width × height");
  }
  embedWatermarkBits(data, payload);
}

/**
 * Build the leak-attribution payload (recipient/account id + conversation id +
 * capture timestamp) — kept identical to what [generateInvisibleWatermark]
 * historically embedded so the extractor and any existing captures still round
 * trip. `conversationId` occupies the payload's `messageId` slot.
 */
function watermarkPayloadBytes(userId: string, conversationId: string): Uint8Array {
  const payload: WatermarkPayload = {
    userId,
    messageId: conversationId,
    timestamp: new Date().toISOString(),
  };
  return utf8Encode(JSON.stringify(payload));
}

/**
 * Embed the invisible LSB watermark over a canvas's CURRENT pixels (whatever is
 * already drawn — a fingerprint tile, an image, a flat fill), returning the
 * same canvas. Browser only (requires a DOM canvas). This is what lets the
 * visible "security paper" tile also carry the stego layer in its own pixels,
 * so a screenshot of the tile is self-attributing.
 */
export function embedWatermarkInCanvas(
  canvas: HTMLCanvasElement,
  userId: string,
  conversationId: string,
): HTMLCanvasElement {
  if (typeof document === "undefined") {
    throw new Error("embedWatermarkInCanvas requires a DOM environment");
  }
  const { width, height } = canvas;
  const ctx = canvas.getContext("2d", { willReadFrequently: true })!;
  const imageData = ctx.getImageData(0, 0, width, height);
  embedWatermarkInPixels(
    imageData.data,
    width,
    height,
    watermarkPayloadBytes(userId, conversationId),
  );
  ctx.putImageData(imageData, 0, 0);
  return canvas;
}

/**
 * Render a message-bubble background canvas carrying the invisible watermark.
 * Thin wrapper: fill a flat background, then embed over it. Browser only
 * (requires a DOM canvas).
 */
export function generateInvisibleWatermark(
  userId: string,
  messageId: string,
  width = 256,
  height = 64,
  backgroundColor = "#242100",
): HTMLCanvasElement {
  if (typeof document === "undefined") {
    throw new Error("generateInvisibleWatermark requires a DOM environment");
  }
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true })!;
  ctx.fillStyle = backgroundColor;
  ctx.fillRect(0, 0, width, height);
  return embedWatermarkInCanvas(canvas, userId, messageId);
}
