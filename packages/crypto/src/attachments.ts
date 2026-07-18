// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Attachment blob crypto. An attachment is encrypted OUTSIDE the ratchet under
 * a fresh random AES-256-GCM key; the key and the blob's redemption token then
 * travel inside the ratchet-encrypted control payload (see
 * packages/protocol/src/attachments.ts), so end-to-end confidentiality is
 * inherited from the session while the relay stores only an opaque blob it can
 * neither read nor tie to an envelope. Forward secrecy of the standalone key is
 * a non-issue by construction: the blob is destroyed at first redemption or at
 * its 72-hour TTL, so there is nothing left to decrypt when a key would leak.
 *
 * The plaintext is padded to 64 KiB buckets BEFORE encryption (reusing the
 * message padding layout — len(4, BE) || plaintext || random fill), so the
 * blob's stored size reveals only a bucket count, not the true length.
 * The blob ID the relay stores under is SHA-256(token) — the relay never sees
 * the token until redemption, mirroring the dead-drop construction.
 */

import { sodium, ready } from "./sodium.js";
import { aeadEncrypt, aeadDecrypt } from "./aead.js";
import { pad, unpad } from "./padding.js";

/** Bucket size the padded plaintext is a multiple of. Mirrors
 *  packages/protocol attachments.ts BLOB_BUCKET_BYTES. */
export const BLOB_BUCKET_BYTES = 64 * 1024;

/** Redemption-token / key / hash lengths (all 32 bytes). */
export const BLOB_TOKEN_BYTES = 32;

export interface EncryptedAttachmentBlob {
  /** 32-byte redemption token — goes into the control payload, never uploaded. */
  token: Uint8Array;
  /** SHA-256(token) — the ID the relay stores the blob under (uploaded). */
  blobId: Uint8Array;
  /** 32-byte AES-256-GCM key — goes into the control payload. */
  key: Uint8Array;
  /** nonce(12) || ciphertext+tag of the bucket-padded plaintext (uploaded). */
  box: Uint8Array;
  /** SHA-256 of the plaintext — verified by the recipient after decryption. */
  sha256: Uint8Array;
  /** Plaintext byte length (pre-padding) — carried in the control payload. */
  size: number;
}

/** Encrypts attachment bytes for blind relay storage. */
export async function encryptAttachmentBlob(plain: Uint8Array): Promise<EncryptedAttachmentBlob> {
  await ready();
  if (plain.length === 0) throw new Error("empty attachment");
  const token = sodium.randombytes_buf(BLOB_TOKEN_BYTES);
  const key = sodium.randombytes_buf(32);
  const [blobId, digest] = await Promise.all([sha256(token), sha256(plain)]);
  const padded = await pad(plain, BLOB_BUCKET_BYTES);
  const box = await aeadEncrypt(key, padded);
  return { token, blobId, key, box, sha256: digest, size: plain.length };
}

/**
 * Decrypts a redeemed blob and verifies it against the control payload's
 * declared size and SHA-256. Throws on ANY mismatch — a wrong hash or length
 * means the blob is not what the sender described, and rendering it anyway
 * would let the relay (or anyone who guessed a blob ID) substitute content.
 */
export async function decryptAttachmentBlob(
  key: Uint8Array,
  box: Uint8Array,
  expectedSha256: Uint8Array,
  expectedSize: number,
): Promise<Uint8Array> {
  const plain = unpad(await aeadDecrypt(key, box));
  if (plain.length !== expectedSize) throw new Error("attachment size mismatch");
  const digest = await sha256(plain);
  await ready();
  if (digest.length !== expectedSha256.length || !sodium.memcmp(digest, expectedSha256)) {
    throw new Error("attachment hash mismatch");
  }
  return plain;
}

/** SHA-256 via WebCrypto (same runtime baseline as aead.ts). */
async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  // Same cast as aead.ts toArrayBuffer: slice() is typed ArrayBuffer|SharedArrayBuffer.
  const buf = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", buf));
}
