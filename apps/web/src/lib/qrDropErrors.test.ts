// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { ApiError } from "./api.js";
import { formatQrDropCreateError } from "./qrDropErrors.js";

describe("formatQrDropCreateError", () => {
  it("names a stale relay when deposit 404s (missing qr-drops route)", () => {
    expect(formatQrDropCreateError(new ApiError(404, "error"))).toMatch(/stale server build/i);
  });

  it("names identity change and oversize distinctly", () => {
    expect(formatQrDropCreateError(new Error("identity changed"))).toMatch(/key changed/i);
    expect(formatQrDropCreateError(new Error("payload too large"))).toMatch(/too long/i);
    expect(formatQrDropCreateError(new ApiError(413, "payload_too_large"))).toMatch(/too long/i);
  });

  it("names rate limit and offline", () => {
    expect(formatQrDropCreateError(new ApiError(429, "rate_limited"))).toMatch(/Too many/i);
    expect(formatQrDropCreateError(new ApiError(0, "transport_offline"))).toMatch(/offline/i);
  });

  it("falls back without leaking internals", () => {
    expect(formatQrDropCreateError(new Error("something obscure"))).toMatch(/Couldn't create/i);
    expect(formatQrDropCreateError("nope")).toMatch(/Couldn't create/i);
  });
});
