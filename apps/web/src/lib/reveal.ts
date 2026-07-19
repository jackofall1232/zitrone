// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Image reveal-and-burn timing + eligibility. Pure, UI-independent logic so the
 * store, the component, and the tests all agree on WHEN an image is tappable
 * and HOW long it stays revealed. Mirrors the Android
 * MessageRepository.IMAGE_REVEAL_MS / revealAttachment guard.
 */

import type { AttachmentView } from "../store.js";

/**
 * How long a RECEIVED image stays revealed after the recipient taps it, before
 * it re-covers and the message burns on both ends. A HARD wall-clock window
 * (not idle-reset): backgrounding the tab does not pause it.
 */
export const IMAGE_REVEAL_MS = 10_000;

/**
 * True when an attachment is a received image whose decrypted bytes are in hand
 * and that has not been revealed yet — i.e. it is currently covered and
 * tappable. Sent images (our own copy), files, still-loading or expired blobs,
 * and already-revealed images are all excluded.
 */
export function isRevealableImage(
  view: AttachmentView | undefined,
  direction: "sent" | "received",
): boolean {
  return (
    direction === "received" &&
    view?.kind === "image" &&
    view.status === "ready" &&
    !view.revealed
  );
}
