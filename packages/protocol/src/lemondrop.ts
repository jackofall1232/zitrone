// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * "Lemon drops" — QR dead-drop wire types (V1). A lemon drop is a message
 * encrypted ONCE, at creation time, to a single named recipient via one-shot
 * X3DH (no live session), the whole envelope then sealed to that recipient's
 * identity key. A QR sticker encodes only `https://zitrone.app/d/{qr_id}`, a
 * pointer at the server-stored sealed blob. Anyone can fetch the blob by
 * qr_id; matching is client-side, by whether the sealed box opens — a
 * non-recipient learns nothing, not even that they were not the target.
 *
 * The relay is blind: it stores an opaque blob under a random qr_id, an
 * unfetched-drop TTL, and a burn_hash. It cannot decrypt, cannot tell who
 * deposited (deposit is unauthenticated, gated only by hashcash), and cannot
 * tell who — if anyone — successfully read the drop (fetch is non-destructive;
 * only a successful decryptor, who alone recovers the burn token inside the
 * payload, can burn it).
 *
 * This module is types-only and DEPENDENCY-FREE (no crypto import): the URL
 * codec below is plain byte math so the same helpers run in the browser, the
 * relay, and the crypto package that builds the sealed payload.
 */

import { CONTROL_VERSION } from "./receipts.js";
import { parseEnvelope, type MessageEnvelope } from "./envelope.js";

/** Length of a QR drop id (128 bits). Minted at random, not derived. */
export const QR_DROP_ID_BYTES = 16;

/** Length of the burn token (256 bits) that rides INSIDE the sealed payload. */
export const QR_DROP_BURN_TOKEN_BYTES = 32;

/**
 * Creator-chosen drop lifetime, in hours. A FIXED server-enforced allowlist:
 * arbitrary TTLs would fingerprint a drop (a 37-hour drop is a rarer, more
 * identifiable thing than one of five common values), so only these are
 * accepted. There is deliberately no 1-month option — by decision, a QR
 * sticker in the wild should not outlive two weeks.
 */
export const QR_DROP_TTL_HOURS = [24, 48, 72, 168, 336] as const;

/** One of the allowlisted TTL values. */
export type QrDropTTLHours = (typeof QR_DROP_TTL_HOURS)[number];

/** The origin + path prefix a QR sticker encodes. The id follows as base64url. */
export const QR_DROP_URL_BASE = "https://zitrone.app/d/";

const QR_DROP_HOST = "zitrone.app";

// ── base64url codec (no padding) ─────────────────────────────────────────────
// Small and self-contained so this module keeps its no-dependency promise. The
// URL-safe alphabet (— and _ for + and /) and dropped padding keep a qr_id a
// clean path segment that survives copy-paste and QR encoders unmangled.

const B64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const B64URL_CHARSET = /^[A-Za-z0-9_-]*$/;

function toBase64Url(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i]!;
    const hasB1 = i + 1 < bytes.length;
    const hasB2 = i + 2 < bytes.length;
    const b1 = hasB1 ? bytes[i + 1]! : 0;
    const b2 = hasB2 ? bytes[i + 2]! : 0;
    out += B64URL_ALPHABET.charAt(b0 >> 2);
    out += B64URL_ALPHABET.charAt(((b0 & 0x03) << 4) | (b1 >> 4));
    if (!hasB1) break;
    out += B64URL_ALPHABET.charAt(((b1 & 0x0f) << 2) | (b2 >> 6));
    if (!hasB2) break;
    out += B64URL_ALPHABET.charAt(b2 & 0x3f);
  }
  return out;
}

function fromBase64Url(input: string): Uint8Array | null {
  if (!B64URL_CHARSET.test(input)) return null;
  if (input.length % 4 === 1) return null; // no valid base64 group is one char
  const out = new Uint8Array(Math.floor((input.length * 6) / 8));
  let value = 0;
  let bits = 0;
  let oi = 0;
  for (let i = 0; i < input.length; i++) {
    const idx = B64URL_ALPHABET.indexOf(input.charAt(i));
    if (idx < 0) return null;
    value = (value << 6) | idx;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[oi++] = (value >> bits) & 0xff;
    }
  }
  return out;
}

/**
 * Encode a qr_id for the wire. The JSON `qr_id` fields and the URL path segment
 * share ONE canonical base64url (unpadded) form — the relay decodes exactly
 * this — so a client never juggles two encodings of the same 16 bytes.
 */
export function encodeQrDropId(qrId: Uint8Array): string {
  return toBase64Url(qrId);
}

/** Build the QR sticker URL for a qr_id: `https://zitrone.app/d/{base64url}`. */
export function buildQrDropUrl(qrId: Uint8Array): string {
  return QR_DROP_URL_BASE + toBase64Url(qrId);
}

/**
 * Recover a qr_id from a scanned string. Accepts a full https URL on host
 * zitrone.app with path `/d/{id}`, a bare `/d/{id}` path, or a bare base64url
 * id. Strict: wrong host, wrong path, wrong charset, or an id that does not
 * decode to exactly QR_DROP_ID_BYTES all return null. A QR reader hands us
 * arbitrary text, so this never throws.
 */
export function parseQrDropUrl(input: string): Uint8Array | null {
  const trimmed = input.trim();
  let idPart: string;
  if (/^https?:\/\//i.test(trimmed)) {
    let url: URL;
    try {
      url = new URL(trimmed);
    } catch {
      return null;
    }
    if (url.protocol !== "https:") return null;
    if (url.host.toLowerCase() !== QR_DROP_HOST) return null;
    // A canonical sticker URL carries NOTHING but the path: any query, fragment,
    // or userinfo is rejected outright. new URL() would silently strip these out
    // of pathname, which made a decorated full URL pass while the same
    // decoration on a bare path failed — and while Android's parser (which
    // validates the raw string) refused it. All parsers fail closed identically.
    if (url.search !== "" || url.hash !== "" || url.username !== "" || url.password !== "") {
      return null;
    }
    const match = /^\/d\/([^/]+)$/.exec(url.pathname);
    if (!match) return null;
    idPart = match[1]!;
  } else if (trimmed.startsWith("/d/")) {
    const match = /^\/d\/([^/]+)$/.exec(trimmed);
    if (!match) return null;
    idPart = match[1]!;
  } else {
    idPart = trimmed;
  }
  const bytes = fromBase64Url(idPart);
  if (!bytes || bytes.length !== QR_DROP_ID_BYTES) return null;
  return bytes;
}

// ── server contract ──────────────────────────────────────────────────────────

/**
 * POST /api/v1/qr-drops — deposit a sealed lemon drop → 201. UNAUTHENTICATED:
 * a hashcash proof-of-work over qr_id (difficulty DROP_POW_DIFFICULTY, the
 * same as dead drops) is the only admission control, so spam costs CPU without
 * anyone logging in. There is no sender field — the relay cannot know who
 * deposited.
 */
export interface QrDropDepositRequest {
  /** Base64url 16-byte qr_id — the same id encoded in the QR sticker URL. */
  qr_id: string;
  /** Base64 opaque sealed box (padded), addressed to the recipient's identity key. */
  ciphertext: string;
  /** Drop lifetime — MUST be one of QR_DROP_TTL_HOURS; the relay rejects others. */
  ttl_hours: number;
  /** Base64 8-byte hashcash nonce solving the PoW puzzle over qr_id. */
  pow_nonce: string;
  /** Base64 SHA-256(burn_token) — the relay stores this so only a successful
   *  decryptor (who alone recovers the token) can later burn the drop. */
  burn_hash: string;
}

export interface QrDropDepositResponse {
  /** ISO 8601 UTC — when the drop self-destructs if never burned. */
  expires_at: string;
}

/**
 * POST /api/v1/qr-drops/fetch — fetch the sealed blob by qr_id → 200. NON-
 * destructive and repeatable BY DESIGN: the relay is blind and cannot know the
 * decrypt outcome, so destroying on fetch would let a wrong-recipient scan burn
 * the drop out from under the intended recipient. Missing, expired, and burned
 * drops are ALL 404, indistinguishable — a scanner cannot probe drop state.
 */
export interface QrDropFetchRequest {
  /** Base64url 16-byte qr_id. */
  qr_id: string;
}

export interface QrDropFetchResponse {
  /** Base64 opaque sealed box (padded). */
  ciphertext: string;
}

/**
 * POST /api/v1/qr-drops/burn — destroy the drop → 204. Presents the burn token
 * preimage of the stored burn_hash. Only a client that successfully decrypted
 * the payload knows this preimage, so a wrong-recipient scanner physically
 * cannot burn a drop it could not read.
 */
export interface QrDropBurnRequest {
  /** Base64url 16-byte qr_id. */
  qr_id: string;
  /** Base64 32-byte burn token (recovered from inside the decrypted payload). */
  burn_token: string;
}

// ── the inner (sealed) payload ───────────────────────────────────────────────

export const LEMON_DROP_CONTROL = "lemondrop.v1" as const;

/**
 * The payload sealed INSIDE the box, so ONLY the true recipient ever parses it.
 * It carries the full message envelope (decrypted with a one-shot X3DH
 * responder session), the sender's CLAIMED identity key — the caller must
 * cross-check this against any existing contact record, since a sealed box
 * proves who it was addressed TO, not who wrote it — and the burn token whose
 * SHA-256 the relay holds.
 *
 * Parsing follows the attachments.ts convention: strict on the discriminator
 * (`v` AND `control`) and on the crypto field shapes (a wrong-length burn
 * token or identity key is a malformed drop, not text), lenient on extra
 * fields so future revisions can extend the payload.
 */
export interface LemonDropPayload {
  v: typeof CONTROL_VERSION;
  control: typeof LEMON_DROP_CONTROL;
  envelope: MessageEnvelope;
  /** Base64 32-byte Ed25519 sender identity public key (CLAIMED — cross-check it). */
  sender_identity_key: string;
  /** Base64 32-byte burn token — SHA-256 preimage the relay stores as burn_hash. */
  burn_token: string;
}

const BASE64_32_BYTES = /^[A-Za-z0-9+/]{43}=$/;

export interface SerializeLemonDropInput {
  envelope: MessageEnvelope;
  /** Base64 32-byte Ed25519 sender identity public key. */
  senderIdentityKey: string;
  /** Base64 32-byte burn token. */
  burnToken: string;
}

/** Serializes a lemon drop payload for sealing. */
export function serializeLemonDrop(input: SerializeLemonDropInput): string {
  return JSON.stringify({
    v: CONTROL_VERSION,
    control: LEMON_DROP_CONTROL,
    envelope: input.envelope,
    sender_identity_key: input.senderIdentityKey,
    burn_token: input.burnToken,
  } satisfies LemonDropPayload);
}

/**
 * Returns the payload when a decrypted (seal-opened) plaintext is a well-formed
 * lemon drop, or null otherwise. Never throws. Like attachments, a payload that
 * MATCHES the discriminator yet fails field validation is still null: the
 * caller has already opened a box addressed to it, so a null here means "our
 * drop, but malformed", never raw text to render.
 */
export function parseLemonDrop(plaintext: string): LemonDropPayload | null {
  if (!plaintext.startsWith("{")) return null;
  let value: unknown;
  try {
    value = JSON.parse(plaintext);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const v = value as Record<string, unknown>;
  if (v.v !== CONTROL_VERSION || v.control !== LEMON_DROP_CONTROL) return null;
  if (typeof v.sender_identity_key !== "string" || !BASE64_32_BYTES.test(v.sender_identity_key))
    return null;
  if (typeof v.burn_token !== "string" || !BASE64_32_BYTES.test(v.burn_token)) return null;
  let envelope: MessageEnvelope;
  try {
    envelope = parseEnvelope(v.envelope);
  } catch {
    return null;
  }
  return {
    v: CONTROL_VERSION,
    control: LEMON_DROP_CONTROL,
    envelope,
    sender_identity_key: v.sender_identity_key,
    burn_token: v.burn_token,
  };
}
