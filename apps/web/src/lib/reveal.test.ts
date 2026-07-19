// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { IMAGE_REVEAL_MS, isRevealableImage } from "./reveal.js";
import type { AttachmentView } from "../store.js";

const image = (over: Partial<AttachmentView> = {}): AttachmentView => ({
  kind: "image",
  mimetype: "image/jpeg",
  filename: null,
  size: 3,
  caption: null,
  status: "ready",
  ...over,
});

describe("image reveal-and-burn eligibility", () => {
  it("uses a hard 10-second reveal window", () => {
    expect(IMAGE_REVEAL_MS).toBe(10_000);
  });

  it("a received, ready, un-revealed image is revealable", () => {
    expect(isRevealableImage(image(), "received")).toBe(true);
  });

  it("our own sent image is never reveal-burned", () => {
    expect(isRevealableImage(image(), "sent")).toBe(false);
  });

  it("an already-revealed image is not re-revealable (repeat-tap is a no-op)", () => {
    expect(isRevealableImage(image({ revealed: true }), "received")).toBe(false);
  });

  it("a still-loading blob is not yet revealable", () => {
    expect(isRevealableImage(image({ status: "loading" }), "received")).toBe(false);
  });

  it("an expired blob is not revealable", () => {
    expect(isRevealableImage(image({ status: "expired" }), "received")).toBe(false);
  });

  it("files are not reveal-burned", () => {
    expect(isRevealableImage(image({ kind: "file" }), "received")).toBe(false);
  });

  it("a message with no attachment is not revealable", () => {
    expect(isRevealableImage(undefined, "received")).toBe(false);
  });
});
