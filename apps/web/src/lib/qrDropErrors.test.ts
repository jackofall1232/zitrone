// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { ApiError } from "./api.js";
import { formatQrDropCreateError } from "./qrDropErrors.js";

describe("formatQrDropCreateError", () => {
  it("names a stale relay only for a ROUTER 404 (missing qr-drops route)", () => {
    // Fiber's generic router 404 body is {"error":"error"} → code "error".
    expect(formatQrDropCreateError(new ApiError(404, "error"))).toMatch(/stale server build/i);
  });

  it("names a missing recipient — NOT a stale relay — for a handler not_found 404", () => {
    // The prekey-bundle fetch (before deposit) returns {"error":"not_found"} when
    // the recipient is gone. It must NOT tell the user to redeploy a healthy relay.
    const msg = formatQrDropCreateError(new ApiError(404, "not_found"));
    expect(msg).toMatch(/isn't reachable/i);
    expect(msg).not.toMatch(/redeploy|stale/i);
  });

  it("does not claim a stale relay for an unlabeled 404", () => {
    expect(formatQrDropCreateError(new ApiError(404, "request_failed"))).not.toMatch(/stale|redeploy/i);
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
