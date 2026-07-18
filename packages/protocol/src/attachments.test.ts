// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import {
  ATTACHMENT_MAX_BYTES,
  isControlPayload,
  parseAttachment,
  serializeAttachment,
} from "./attachments.js";

// Any 43-chars-plus-pad base64 string decodes to 32 bytes — a plausible token/key/hash.
const b64of32 = "A".repeat(43) + "=";

const image = {
  kind: "image" as const,
  blobToken: b64of32,
  key: b64of32,
  mimetype: "image/jpeg",
  size: 123_456,
  sha256: b64of32,
};

describe("attachment control payloads", () => {
  it("round-trips an image through serialization", () => {
    const parsed = parseAttachment(serializeAttachment(image));
    expect(parsed).not.toBeNull();
    expect(parsed?.kind).toBe("image");
    expect(parsed?.mimetype).toBe("image/jpeg");
    expect(parsed?.size).toBe(image.size);
    expect(parsed?.filename).toBeNull();
    expect(parsed?.caption).toBeNull();
  });

  it("round-trips a file with filename and caption", () => {
    const parsed = parseAttachment(
      serializeAttachment({
        ...image,
        kind: "file",
        mimetype: "application/pdf",
        filename: "report.pdf",
        caption: "Q3 draft",
      }),
    );
    expect(parsed?.kind).toBe("file");
    expect(parsed?.filename).toBe("report.pdf");
    expect(parsed?.caption).toBe("Q3 draft");
  });

  it("forces filename to null for images even when supplied", () => {
    const parsed = parseAttachment(
      serializeAttachment({ ...image, filename: "leaky-name.jpg" }),
    );
    expect(parsed?.filename).toBeNull();
  });

  it("treats ordinary text and receipt payloads as not-an-attachment", () => {
    expect(parseAttachment("just a message")).toBeNull();
    expect(parseAttachment("")).toBeNull();
    expect(parseAttachment('{"v":1,"control":"receipt.read","message_ids":[]}')).toBeNull();
  });

  it("rejects a matching discriminator with invalid crypto fields", () => {
    const good = JSON.parse(serializeAttachment(image));
    for (const patch of [
      { blob_token: "short" },
      { key: b64of32.slice(0, 20) },
      { sha256: "!".repeat(44) },
      { size: 0 },
      { size: ATTACHMENT_MAX_BYTES + 1 },
      { size: 1.5 },
      { kind: "video" },
      { mimetype: "" },
    ]) {
      expect(parseAttachment(JSON.stringify({ ...good, ...patch }))).toBeNull();
    }
  });

  it("rejects images that smuggle a filename on the wire", () => {
    const good = JSON.parse(serializeAttachment(image));
    expect(parseAttachment(JSON.stringify({ ...good, filename: "x.jpg" }))).toBeNull();
  });

  it("is lenient about unknown extra fields (future revisions)", () => {
    const good = JSON.parse(serializeAttachment(image));
    expect(parseAttachment(JSON.stringify({ ...good, future_field: true }))).not.toBeNull();
  });

  it("flags control-shaped payloads so callers never render them as text", () => {
    // A malformed attachment (bad key length) parses to null but MUST still be
    // recognized as a control payload — it may carry key material.
    const nearMiss = JSON.stringify({
      ...JSON.parse(serializeAttachment(image)),
      key: "tooshort",
    });
    expect(parseAttachment(nearMiss)).toBeNull();
    expect(isControlPayload(nearMiss)).toBe(true);
    // Future control types from newer clients are also flagged.
    expect(isControlPayload('{"v":3,"control":"poll.v1","options":[]}')).toBe(true);
    // Ordinary text and non-control JSON are not.
    expect(isControlPayload("hello {")).toBe(false);
    expect(isControlPayload('{"hello":"world"}')).toBe(false);
    expect(isControlPayload('{"v":"1","control":7}')).toBe(false);
  });

  it("never throws on malformed input", () => {
    expect(parseAttachment("{")).toBeNull();
    expect(parseAttachment("[1,2,3]")).toBeNull();
    expect(parseAttachment("null")).toBeNull();
  });
});
