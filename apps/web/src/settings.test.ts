// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { parsePersisted } from "./settings.js";

describe("settings persistence merge", () => {
  it("an older blob without qrDropCoachmarkSeen loads as false, not undefined", () => {
    // A persisted blob written before the coachmark flag existed.
    const legacy = JSON.stringify({
      connectionMode: "balanced",
      coverTraffic: "low",
      allowClearnetFallback: true,
    });
    const parsed = parsePersisted(legacy);
    expect(parsed.qrDropCoachmarkSeen).toBe(false);
  });

  it("a blob that recorded the flag round-trips its value", () => {
    const seen = JSON.stringify({ qrDropCoachmarkSeen: true });
    expect(parsePersisted(seen).qrDropCoachmarkSeen).toBe(true);
  });

  it("no stored blob falls back to false", () => {
    expect(parsePersisted(null).qrDropCoachmarkSeen).toBe(false);
  });

  it("a corrupt blob falls back to false", () => {
    expect(parsePersisted("{not json").qrDropCoachmarkSeen).toBe(false);
  });
});
