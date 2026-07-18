// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { parseAttachment, serializeAttachment, serializeReadReceipt } from "@zitrone/protocol";
import { classifyIncoming } from "./incoming.js";

// Standard base64 of 32 zero bytes — a valid 32-byte control field.
const B32 = btoa(String.fromCharCode(...new Uint8Array(32)));

const validAttachment = serializeAttachment({
  kind: "image",
  blobToken: B32,
  key: B32,
  mimetype: "image/jpeg",
  filename: null,
  size: 4096,
  sha256: B32,
  caption: null,
});

describe("classifyIncoming (canonical parse order)", () => {
  it("recognizes a read receipt", () => {
    expect(classifyIncoming(serializeReadReceipt(["m1", "m2"]))).toEqual({ kind: "receipt" });
  });

  it("recognizes a valid attachment payload", () => {
    const result = classifyIncoming(validAttachment);
    expect(result).toEqual({ kind: "attachment", payload: parseAttachment(validAttachment) });
  });

  it("treats an unknown control payload as unsupported, never text", () => {
    // A future control type this client doesn't understand.
    const future = JSON.stringify({ v: 2, control: "something.future", data: "x" });
    expect(classifyIncoming(future)).toEqual({ kind: "unsupported" });
  });

  it("treats a malformed attachment (near-miss) as unsupported, never text", () => {
    // Matches the attachment discriminator but the key is the wrong length —
    // parseAttachment rejects it, and it must NOT leak into a text bubble.
    const nearMiss = JSON.stringify({
      v: 1,
      control: "attachment.v1",
      kind: "file",
      blob_token: B32,
      key: "too-short",
      mimetype: "application/pdf",
      filename: "x.pdf",
      size: 10,
      sha256: B32,
      caption: null,
    });
    expect(classifyIncoming(nearMiss)).toEqual({ kind: "unsupported" });
  });

  it("passes ordinary text through", () => {
    expect(classifyIncoming("hello there")).toEqual({ kind: "text", text: "hello there" });
  });

  it("passes JSON-looking-but-not-control text through", () => {
    // Starts with "{" and parses, but lacks the numeric v + string control
    // shape, so it is plain text, not an unsupported control payload.
    const notControl = JSON.stringify({ hello: "world" });
    expect(classifyIncoming(notControl)).toEqual({ kind: "text", text: notControl });
  });
});
