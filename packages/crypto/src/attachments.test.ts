// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { BLOB_BUCKET_BYTES, decryptAttachmentBlob, encryptAttachmentBlob } from "./attachments.js";
import { NONCE_BYTES } from "./aead.js";

const AEAD_TAG_BYTES = 16;

function bytes(n: number): Uint8Array {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = i % 251;
  return out;
}

describe("attachment blob crypto", () => {
  it("round-trips and verifies size + hash", async () => {
    const plain = bytes(100_000);
    const blob = await encryptAttachmentBlob(plain);
    const out = await decryptAttachmentBlob(blob.key, blob.box, blob.sha256, blob.size);
    expect(out).toEqual(plain);
  });

  it("pads the ciphertext to 64 KiB buckets (size hiding)", async () => {
    // 1-byte and 60000-byte attachments occupy the SAME single bucket…
    const tiny = await encryptAttachmentBlob(bytes(1));
    const small = await encryptAttachmentBlob(bytes(60_000));
    const oneBucket = BLOB_BUCKET_BYTES + NONCE_BYTES + AEAD_TAG_BYTES;
    expect(tiny.box.length).toBe(oneBucket);
    expect(small.box.length).toBe(oneBucket);
    // …while one past the bucket boundary (minus the 4-byte length prefix) rolls over.
    const big = await encryptAttachmentBlob(bytes(BLOB_BUCKET_BYTES - 4 + 1));
    expect(big.box.length).toBe(2 * BLOB_BUCKET_BYTES + NONCE_BYTES + AEAD_TAG_BYTES);
  });

  it("derives the blob id as SHA-256 of the token", async () => {
    const blob = await encryptAttachmentBlob(bytes(10));
    const digest = new Uint8Array(
      await crypto.subtle.digest("SHA-256", blob.token.slice().buffer),
    );
    expect(blob.blobId).toEqual(digest);
    expect(blob.blobId).not.toEqual(blob.token);
  });

  it("uses a fresh token and key per attachment", async () => {
    const a = await encryptAttachmentBlob(bytes(10));
    const b = await encryptAttachmentBlob(bytes(10));
    expect(a.token).not.toEqual(b.token);
    expect(a.key).not.toEqual(b.key);
    expect(a.box).not.toEqual(b.box);
  });

  it("rejects a size mismatch (substituted blob)", async () => {
    const blob = await encryptAttachmentBlob(bytes(1000));
    await expect(
      decryptAttachmentBlob(blob.key, blob.box, blob.sha256, 999),
    ).rejects.toThrow("size mismatch");
  });

  it("rejects a hash mismatch (substituted blob)", async () => {
    const blob = await encryptAttachmentBlob(bytes(1000));
    const wrongHash = blob.sha256.slice();
    wrongHash[0]! ^= 0xff;
    await expect(
      decryptAttachmentBlob(blob.key, blob.box, wrongHash, blob.size),
    ).rejects.toThrow("hash mismatch");
  });

  it("rejects tampered ciphertext (AEAD)", async () => {
    const blob = await encryptAttachmentBlob(bytes(1000));
    const tampered = blob.box.slice();
    tampered[tampered.length - 1]! ^= 0x01;
    await expect(
      decryptAttachmentBlob(blob.key, tampered, blob.sha256, blob.size),
    ).rejects.toThrow();
  });

  it("rejects an empty attachment", async () => {
    await expect(encryptAttachmentBlob(new Uint8Array(0))).rejects.toThrow("empty");
  });
});
