// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { ApiError } from "./api.js";

/**
 * Map a lemon-drop create/deposit failure to honest UI copy. The compose path
 * used to collapse every failure into "Couldn't seal the drop", which masked
 * the real diagnosis when the live relay was missing `/api/v1/qr-drops` (router
 * 404) after clients shipped lemon-drop creation. Keep drafts intact; these
 * strings only name why the drop did not deposit.
 */
export function formatQrDropCreateError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === "transport_offline") {
      return "You're offline — connect to deposit a QR drop.";
    }
    if (err.status === 404) {
      // A 404 has two very different causes on this path, told apart by the
      // response body Fiber returns:
      //   - ROUTER 404 {"error":"error"} (code "error") — the qr-drops route is
      //     ABSENT, i.e. the relay build predates PR #3. Redeploy is the fix.
      //   - HANDLER 404 {"error":"not_found"} (code "not_found") — here it comes
      //     from the recipient's prekey-bundle fetch, which precedes the deposit:
      //     the recipient is gone, and the relay is perfectly healthy. Telling
      //     someone to redeploy a working relay would be actively misleading.
      if (err.code === "not_found") {
        return "This contact isn't reachable right now — they may have reset their account.";
      }
      if (err.code === "error") {
        return "The relay doesn't support QR drops yet (stale server build). Redeploy the relay, then try again.";
      }
      // Any other 404 falls through to the generic deposit-failure line below.
    }
    if (err.status === 429 || err.code === "rate_limited") {
      return "Too many QR drops from this network — wait a minute and try again.";
    }
    if (err.status === 413 || err.code === "payload_too_large") {
      return "This message is too long to seal into a QR drop — shorten it and try again.";
    }
    if (err.code === "bad_pow" || err.code === "bad_ttl" || err.code === "bad_qr_id") {
      return "The relay rejected the deposit — try again.";
    }
    if (err.code === "qr_drop_exists") {
      return "That drop id was already used — try again.";
    }
    if (err.status === 0 || err.status >= 500) {
      return "Couldn't reach the relay — check your connection and try again.";
    }
    return "Couldn't deposit the drop — try again.";
  }

  if (err instanceof Error) {
    const msg = err.message;
    if (msg === "identity changed") {
      return "This contact's key changed — the drop was refused.";
    }
    if (msg === "payload too large") {
      return "This message is too long to seal into a QR drop — shorten it and try again.";
    }
    if (msg === "unknown contact") {
      return "Couldn't create the drop — contact is missing.";
    }
    if (
      msg === "prekey bundle signature verification failed" ||
      msg.includes("cross-family bundle")
    ) {
      return "Couldn't verify this contact's keys — the drop was refused.";
    }
  }

  return "Couldn't create the drop — try again.";
}
