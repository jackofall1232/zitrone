// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Post-decryption dispatch for inbound envelope plaintext. Control payloads
 * (read receipts, attachments) ride inside ordinary message plaintext so the
 * relay cannot tell them from conversation text — they are recognized only
 * AFTER decryption, by their discriminator.
 *
 * The parse ORDER is security-relevant and fixed by the attachment contract:
 *   parseReadReceipt → parseAttachment → isControlPayload → display text.
 *
 * The `isControlPayload` fallback is the important one: a payload that is
 * shaped like a control message (numeric `v` + string `control`) but that no
 * parser accepted — a newer client's message, or an attachment whose crypto
 * fields failed validation — must render as a generic "unsupported" placeholder
 * and NEVER as raw text. Such a near-miss can carry key material, and painting
 * it into a chat bubble would leak it.
 */

import { isControlPayload, parseAttachment, parseReadReceipt } from "@zitrone/protocol";
import type { AttachmentPayload } from "@zitrone/protocol";

export type IncomingClassification =
  | { kind: "receipt" }
  | { kind: "attachment"; payload: AttachmentPayload }
  | { kind: "unsupported" }
  | { kind: "text"; text: string };

/** Classifies decrypted plaintext following the canonical parse order. */
export function classifyIncoming(plaintext: string): IncomingClassification {
  if (parseReadReceipt(plaintext) !== null) return { kind: "receipt" };
  const attachment = parseAttachment(plaintext);
  if (attachment !== null) return { kind: "attachment", payload: attachment };
  if (isControlPayload(plaintext)) return { kind: "unsupported" };
  return { kind: "text", text: plaintext };
}
