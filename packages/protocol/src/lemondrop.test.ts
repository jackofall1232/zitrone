// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { PROTOCOL_VERSION, type MessageEnvelope } from "./envelope.js";
import {
  buildQrDropUrl,
  parseLemonDrop,
  parseQrDropUrl,
  QR_DROP_ID_BYTES,
  QR_DROP_URL_BASE,
  serializeLemonDrop,
} from "./lemondrop.js";

// Any 43-chars-plus-pad base64 string decodes to 32 bytes — a plausible key/token.
const b64of32 = "A".repeat(43) + "=";

function qrId(seed = 1): Uint8Array {
  return new Uint8Array(Array.from({ length: QR_DROP_ID_BYTES }, (_, i) => (i * 7 + seed) & 0xff));
}

const envelope: MessageEnvelope = {
  id: "0b9f8c1e-4f2a-4d8b-9c3e-7a6b5d4c3b2a",
  sender_id: "11111111-2222-4333-8444-555555555555",
  recipient_id: "66666666-7777-4888-8999-aaaaaaaaaaaa",
  ciphertext: "AAAA",
  ephemeral_key: b64of32,
  prekey_id: 7,
  message_number: 0,
  previous_chain_length: 0,
  timestamp: "2026-07-20T00:00:00.000Z",
  ttl_seconds: null,
  burn_on_read: false,
  media_type: "text",
  version: PROTOCOL_VERSION,
};

describe("QR drop URL codec", () => {
  it("round-trips a qr_id through the sticker URL", () => {
    const id = qrId();
    const url = buildQrDropUrl(id);
    expect(url.startsWith(QR_DROP_URL_BASE)).toBe(true);
    expect(parseQrDropUrl(url)).toEqual(id);
  });

  it("accepts a bare path and a bare id", () => {
    const id = qrId(3);
    const bareId = buildQrDropUrl(id).slice(QR_DROP_URL_BASE.length);
    expect(parseQrDropUrl(`/d/${bareId}`)).toEqual(id);
    expect(parseQrDropUrl(bareId)).toEqual(id);
    // A QR reader may hand us surrounding whitespace.
    expect(parseQrDropUrl(`  ${buildQrDropUrl(id)}  `)).toEqual(id);
  });

  it("uses the url-safe alphabet with no padding", () => {
    const bareId = buildQrDropUrl(qrId(9)).slice(QR_DROP_URL_BASE.length);
    expect(bareId).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(bareId).not.toContain("=");
  });

  it("rejects wrong host, wrong scheme, and wrong path", () => {
    const bareId = buildQrDropUrl(qrId()).slice(QR_DROP_URL_BASE.length);
    expect(parseQrDropUrl(`https://evil.app/d/${bareId}`)).toBeNull();
    expect(parseQrDropUrl(`http://zitrone.app/d/${bareId}`)).toBeNull();
    expect(parseQrDropUrl(`https://zitrone.app/x/${bareId}`)).toBeNull();
    expect(parseQrDropUrl(`https://zitrone.app/d/${bareId}/extra`)).toBeNull();
    expect(parseQrDropUrl(`https://zitrone.app/d/${bareId}/`)).toBeNull();
  });

  it("rejects any query, fragment, or userinfo decoration", () => {
    // A canonical sticker URL is the path and nothing else — and the Android
    // parser (raw-string validation) already refuses these, so the TS parser
    // must fail closed identically rather than letting new URL() strip them.
    const bareId = buildQrDropUrl(qrId()).slice(QR_DROP_URL_BASE.length);
    expect(parseQrDropUrl(`https://zitrone.app/d/${bareId}?utm_source=x`)).toBeNull();
    expect(parseQrDropUrl(`https://zitrone.app/d/${bareId}#frag`)).toBeNull();
    expect(parseQrDropUrl(`https://user@zitrone.app/d/${bareId}`)).toBeNull();
    expect(parseQrDropUrl(`https://user:pw@zitrone.app/d/${bareId}`)).toBeNull();
    expect(parseQrDropUrl(`https://zitrone.app:8443/d/${bareId}`)).toBeNull();
  });

  it("rejects a wrong-length id", () => {
    // 15 and 17 bytes both fail the strict 16-byte length check.
    expect(parseQrDropUrl(buildQrDropUrl(new Uint8Array(15)))).toBeNull();
    expect(parseQrDropUrl(buildQrDropUrl(new Uint8Array(17)))).toBeNull();
    expect(parseQrDropUrl("")).toBeNull();
  });

  it("rejects non-base64url characters", () => {
    // '+', '/', and '=' are standard base64 but not url-safe.
    expect(parseQrDropUrl("AAAA+AAAAAAAAAAAAAAAA")).toBeNull();
    expect(parseQrDropUrl("AAAA/AAAAAAAAAAAAAAAA")).toBeNull();
    expect(parseQrDropUrl("not base64url!!")).toBeNull();
  });
});

describe("lemon drop payload", () => {
  it("round-trips a payload through serialization", () => {
    const parsed = parseLemonDrop(
      serializeLemonDrop({ envelope, senderIdentityKey: b64of32, burnToken: b64of32 }),
    );
    expect(parsed).not.toBeNull();
    expect(parsed?.control).toBe("lemondrop.v1");
    expect(parsed?.sender_identity_key).toBe(b64of32);
    expect(parsed?.burn_token).toBe(b64of32);
    expect(parsed?.envelope.sender_id).toBe(envelope.sender_id);
    expect(parsed?.envelope.prekey_id).toBe(7);
  });

  it("rejects ordinary text and a foreign discriminator", () => {
    expect(parseLemonDrop("just a message")).toBeNull();
    expect(parseLemonDrop("")).toBeNull();
    expect(parseLemonDrop('{"v":1,"control":"receipt.read","message_ids":[]}')).toBeNull();
  });

  it("rejects a matching discriminator with invalid crypto fields", () => {
    const good = JSON.parse(
      serializeLemonDrop({ envelope, senderIdentityKey: b64of32, burnToken: b64of32 }),
    );
    for (const patch of [
      { burn_token: "short" },
      { burn_token: b64of32.slice(0, 20) },
      { sender_identity_key: "!".repeat(44) },
      { sender_identity_key: 7 },
    ]) {
      expect(parseLemonDrop(JSON.stringify({ ...good, ...patch }))).toBeNull();
    }
  });

  it("rejects a structurally broken envelope", () => {
    const good = JSON.parse(
      serializeLemonDrop({ envelope, senderIdentityKey: b64of32, burnToken: b64of32 }),
    );
    expect(
      parseLemonDrop(
        JSON.stringify({ ...good, envelope: { ...envelope, sender_id: "not-a-uuid" } }),
      ),
    ).toBeNull();
    expect(parseLemonDrop(JSON.stringify({ ...good, envelope: null }))).toBeNull();
  });

  it("is lenient about unknown extra fields (future revisions)", () => {
    const good = JSON.parse(
      serializeLemonDrop({ envelope, senderIdentityKey: b64of32, burnToken: b64of32 }),
    );
    expect(parseLemonDrop(JSON.stringify({ ...good, future_field: true }))).not.toBeNull();
  });

  it("never throws on malformed input", () => {
    expect(parseLemonDrop("{")).toBeNull();
    expect(parseLemonDrop("[1,2,3]")).toBeNull();
    expect(parseLemonDrop("null")).toBeNull();
  });
});
