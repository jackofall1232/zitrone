// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { dropPrintFilename } from "./dropPrint.js";

describe("dropPrintFilename", () => {
  it("takes the first 8 chars of the qr id from a well-formed sticker URL", () => {
    // qr_id is a 16-byte base64url string (~22 chars); we keep the leading 8.
    expect(dropPrintFilename("https://zitrone.app/d/AbCd1234EfGh5678IjKl90")).toBe(
      "zitrone-drop-AbCd1234.png",
    );
  });

  it("ignores query strings and fragments after the id", () => {
    expect(dropPrintFilename("https://zitrone.app/d/ZZ-_aa11?x=1#frag")).toBe(
      "zitrone-drop-ZZ-_aa11.png",
    );
  });

  it("sanitizes away any non-base64url characters", () => {
    // Contrived id with stray characters — only base64url-safe survive.
    expect(dropPrintFilename("https://zitrone.app/d/a.b/c%20d")).toMatch(
      /^zitrone-drop-[A-Za-z0-9_-]{0,8}\.png$/,
    );
  });

  it("falls back to a stable name for a URL with no usable segment", () => {
    expect(dropPrintFilename("https://zitrone.app/")).toBe("zitrone-drop-drop.png");
  });

  it("handles a non-URL string by splitting on path separators", () => {
    expect(dropPrintFilename("d/QrId1234abcd")).toBe("zitrone-drop-QrId1234.png");
  });
});
