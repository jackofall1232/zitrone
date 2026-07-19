// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Attachments — encrypted control payloads carried INSIDE the plaintext of an
 * ordinary message envelope, exactly like read receipts (receipts.ts). The
 * attachment bytes themselves NEVER ride in the envelope: they are encrypted
 * under a fresh random key, padded to 64 KiB buckets, and sideloaded through
 * the relay's blind blob store (blob stored under SHA-256(token), redeemed
 * once by token — fetch-and-burn — or purged at its 1-week unfetched fallback
 * TTL — the same construction as dead drops). The control payload below carries the token and
 * key, so everything the server holds is an opaque bucket-sized blob it can
 * neither decrypt nor associate with any envelope or account.
 *
 * ON THE WIRE AN ATTACHMENT MESSAGE IS `media_type: "text"` — deliberately.
 * The envelope's media_type field is cleartext the relay can read; emitting
 * the reserved "image"/"file" values would hand the server a per-message
 * "this one has an attachment" label. Clients recognize attachments AFTER
 * decryption, by the discriminator below (the receipts.ts precedent: on the
 * wire, indistinguishable from conversation text). The "image"/"file" values
 * stay in the envelope schema but are never emitted — see envelope.ts.
 *
 * Parse order in clients: parseReadReceipt → parseAttachment → display text.
 * Parsing is strict on the discriminator (`v` AND `control` must both match)
 * and on the cryptographic fields (wrong-length key/token/hash is a malformed
 * attachment, not text), and lenient on extra fields for future revisions.
 */

import { CONTROL_VERSION } from "./receipts.js";

export const ATTACHMENT_CONTROL = "attachment.v1" as const;

/** Plaintext size cap (pre-padding). The relay enforces the ciphertext bound. */
export const ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024;

/** Blob ciphertext is padded to multiples of this before encryption, so blob
 *  size reveals only a 64 KiB bucket count, not the true attachment length. */
export const BLOB_BUCKET_BYTES = 64 * 1024;

/**
 * Unfetched-blob fallback TTL (hours). Successful redemption destroys the blob
 * immediately (fetch-and-burn); this is only the max lifetime for ciphertext
 * that is never collected. Matches server BLOB_TTL_HOURS default (1 week).
 */
export const BLOB_TTL_HOURS = 168;

export type AttachmentKind = "image" | "file";

export interface AttachmentPayload {
  v: typeof CONTROL_VERSION;
  control: typeof ATTACHMENT_CONTROL;
  kind: AttachmentKind;
  /** Base64 32-byte redemption token — preimage of the blob ID the relay holds. */
  blob_token: string;
  /** Base64 32-byte AES-256-GCM key the blob is encrypted under. */
  key: string;
  /** MIME type of the decrypted bytes (e.g. "image/jpeg"). */
  mimetype: string;
  /** Original filename for kind "file"; ALWAYS null for "image" (an image's
   *  filename is metadata the recipient has no need for). */
  filename: string | null;
  /** Plaintext byte length (pre-padding) — checked after decryption. */
  size: number;
  /** Base64 SHA-256 of the plaintext — verified after decryption. */
  sha256: string;
  /** Optional short sender caption rendered under the attachment. */
  caption: string | null;
}

const BASE64_32_BYTES = /^[A-Za-z0-9+/]{43}=$/;

/**
 * True when decrypted plaintext is SHAPED like a control payload (numeric `v`
 * plus string `control`), whether or not this client recognizes it. Callers
 * must check this AFTER the specific parsers return null and render a generic
 * "unsupported message" placeholder instead of raw text: a control payload
 * from a newer client — or an attachment that failed field validation — may
 * carry key material that must never be painted into a chat bubble.
 */
export function isControlPayload(plaintext: string): boolean {
  if (!plaintext.startsWith("{")) return false;
  let value: unknown;
  try {
    value = JSON.parse(plaintext);
  } catch {
    return false;
  }
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.v === "number" && typeof v.control === "string";
}

export interface SerializeAttachmentInput {
  kind: AttachmentKind;
  blobToken: string;
  key: string;
  mimetype: string;
  /** Ignored (forced null) for kind "image". */
  filename?: string | null;
  size: number;
  sha256: string;
  caption?: string | null;
}

/** Serializes an attachment control payload for envelope plaintext. */
export function serializeAttachment(input: SerializeAttachmentInput): string {
  return JSON.stringify({
    v: CONTROL_VERSION,
    control: ATTACHMENT_CONTROL,
    kind: input.kind,
    blob_token: input.blobToken,
    key: input.key,
    mimetype: input.mimetype,
    filename: input.kind === "image" ? null : (input.filename ?? null),
    size: input.size,
    sha256: input.sha256,
    caption: input.caption ?? null,
  } satisfies AttachmentPayload);
}

/**
 * Returns the payload when decrypted plaintext is an attachment control
 * message, or null for anything else. Never throws on malformed input — but
 * unlike receipts, a payload that MATCHES the discriminator yet fails field
 * validation is still null (rendered as an unsupported message by callers,
 * never as raw text: leaking a near-miss's key material into a chat bubble
 * would be worse than dropping it).
 */
export function parseAttachment(plaintext: string): AttachmentPayload | null {
  if (!plaintext.startsWith("{")) return null;
  let value: unknown;
  try {
    value = JSON.parse(plaintext);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const v = value as Record<string, unknown>;
  if (v.v !== CONTROL_VERSION || v.control !== ATTACHMENT_CONTROL) return null;
  if (v.kind !== "image" && v.kind !== "file") return null;
  if (typeof v.blob_token !== "string" || !BASE64_32_BYTES.test(v.blob_token)) return null;
  if (typeof v.key !== "string" || !BASE64_32_BYTES.test(v.key)) return null;
  if (typeof v.sha256 !== "string" || !BASE64_32_BYTES.test(v.sha256)) return null;
  if (typeof v.mimetype !== "string" || v.mimetype.length === 0) return null;
  if (typeof v.size !== "number" || !Number.isInteger(v.size)) return null;
  if (v.size <= 0 || v.size > ATTACHMENT_MAX_BYTES) return null;
  const filename = typeof v.filename === "string" && v.filename.length > 0 ? v.filename : null;
  if (v.kind === "image" && filename !== null) return null;
  const caption = typeof v.caption === "string" && v.caption.length > 0 ? v.caption : null;
  return {
    v: CONTROL_VERSION,
    control: ATTACHMENT_CONTROL,
    kind: v.kind,
    blob_token: v.blob_token,
    key: v.key,
    mimetype: v.mimetype,
    filename,
    size: v.size,
    sha256: v.sha256,
    caption,
  };
}

/**
 * POST /api/v1/blobs — deposit an encrypted attachment blob (JWT-authenticated;
 * upload metadata is no more revealing than message.send). The blob ID is the
 * SHA-256 of the redemption token, so the relay never sees the token until
 * redemption — same blindness construction as dead drops.
 */
export interface BlobDepositRequest {
  /** Base64 SHA-256(token) — the ID the encrypted blob is stored under. */
  blob_id: string;
  /** Base64 opaque encrypted blob (bucket-padded AES-256-GCM box). */
  ciphertext: string;
}

export interface BlobDepositResponse {
  /** ISO 8601 UTC — when the blob self-destructs if unredeemed. */
  expires_at: string;
}

/**
 * POST /api/v1/blobs/redeem — present the token; receive the blob; the blob is
 * destroyed in the same operation (single-use; a replay returns 404). NO
 * authentication: the token is the capability, and an unauthenticated fetch
 * means the relay cannot link a redemption to any account.
 */
export interface BlobRedeemRequest {
  /** Base64 32-byte token (from the attachment control payload). */
  token: string;
}

export interface BlobRedeemResponse {
  /** Base64 opaque encrypted blob. */
  ciphertext: string;
}
