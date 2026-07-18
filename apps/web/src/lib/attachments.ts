// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Browser-side attachment preparation. This module owns the DOM/canvas work of
 * turning a picked `File` into the raw bytes that go into the blob crypto
 * (packages/crypto encryptAttachmentBlob). The wire/crypto layers are canonical
 * and platform-agnostic; only the pixel wrangling and the file-size gate live
 * here.
 */

import { ATTACHMENT_MAX_BYTES, type AttachmentKind } from "@zitrone/protocol";

/** Images are downscaled so their long edge is at most this many pixels. */
export const MAX_IMAGE_EDGE = 2048;

/** JPEG quality for the re-encode. ~0.85 is visually lossless for photos. */
const JPEG_QUALITY = 0.85;

export interface PreparedAttachment {
  /** Raw plaintext bytes to encrypt + upload. */
  bytes: Uint8Array;
  kind: AttachmentKind;
  mimetype: string;
  /** Always null for images (metadata minimization); the file name otherwise. */
  filename: string | null;
  /** No composer caption UI on web yet — always null on send. */
  caption: string | null;
}

/** Thrown when a file exceeds the plaintext cap; carries a friendly message. */
export class AttachmentTooLargeError extends Error {
  constructor() {
    const mib = Math.floor(ATTACHMENT_MAX_BYTES / (1024 * 1024));
    super(`That file is too large — attachments are capped at ${mib} MiB.`);
    this.name = "AttachmentTooLargeError";
  }
}

/**
 * Prepares a picked file for sending. Images are downscaled to
 * {@link MAX_IMAGE_EDGE} and re-encoded as JPEG, which deliberately strips EXIF
 * (GPS, device, timestamps) — metadata hygiene, not an accident. Any other file
 * is sent raw, byte-capped at {@link ATTACHMENT_MAX_BYTES}, keeping its name.
 */
export async function prepareAttachment(file: File): Promise<PreparedAttachment> {
  if (file.type.startsWith("image/")) {
    const bytes = await downscaleImageToJpeg(file);
    if (bytes.length > ATTACHMENT_MAX_BYTES) throw new AttachmentTooLargeError();
    // filename is dropped on purpose: an image's original name is metadata the
    // recipient has no need for (the canonical payload forces null for images).
    return { bytes, kind: "image", mimetype: "image/jpeg", filename: null, caption: null };
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes.length === 0) throw new Error("empty file");
  if (bytes.length > ATTACHMENT_MAX_BYTES) throw new AttachmentTooLargeError();
  return {
    bytes,
    kind: "file",
    mimetype: file.type || "application/octet-stream",
    filename: file.name || "file",
    caption: null,
  };
}

/** Downscale + JPEG re-encode via canvas. EXIF is stripped as a side effect. */
async function downscaleImageToJpeg(file: File): Promise<Uint8Array> {
  // "from-image" applies any EXIF orientation while decoding, so the re-encode
  // that strips EXIF can't leave a phone photo rotated sideways.
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  try {
    const longEdge = Math.max(bitmap.width, bitmap.height);
    const scale = longEdge > MAX_IMAGE_EDGE ? MAX_IMAGE_EDGE / longEdge : 1;
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("canvas unavailable");
    // JPEG has no alpha — paint white first so transparent PNGs flatten onto
    // white rather than black.
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(bitmap, 0, 0, width, height);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY),
    );
    if (!blob) throw new Error("image encode failed");
    return new Uint8Array(await blob.arrayBuffer());
  } finally {
    bitmap.close();
  }
}

/**
 * Wraps raw bytes in a Blob for in-memory rendering/download. Copies to a plain
 * ArrayBuffer first — the same cast used in crypto/attachments.ts — so the value
 * is a valid BlobPart regardless of whether the backing buffer is typed as
 * ArrayBuffer or SharedArrayBuffer.
 */
export function bytesToBlob(bytes: Uint8Array, type: string): Blob {
  const buf = bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer;
  return new Blob([buf], { type });
}

/** Human-readable byte size for the file-attachment bubble. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
