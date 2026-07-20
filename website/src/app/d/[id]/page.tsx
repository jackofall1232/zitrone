// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// QR dead-drop sticker fallback (https://zitrone.app/d/{id}). A device with
// Zitrone installed and App Links verified never reaches this page — the app
// intercepts the URL. Everyone else (no app installed, or verification not yet
// propagated) lands here, and by design sees the ordinary marketing site: the
// sticker is a doorway into the network, not an error page and not a feature
// pitch. The path's id segment is never read — the server that resolves drops
// is a different host and this page must not leak ids anywhere (no logging,
// no analytics, and noindex so crawled sticker URLs don't get indexed).

import type { Metadata } from "next";

export { default } from "../../page";

export const metadata: Metadata = {
  robots: { index: false, follow: false },
};
