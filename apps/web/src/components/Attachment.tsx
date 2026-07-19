// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Renders an attachment inside a message bubble. Decrypted bytes live only in
 * the in-memory `Blob` on the message (same policy as message plaintext) —
 * images are shown via a short-lived object URL that is revoked on unmount, and
 * files download straight from memory. Nothing is written to disk or cache.
 */

import { useEffect, useState } from "react";
import { formatBytes } from "../lib/attachments.js";
import type { AttachmentView } from "../store.js";

export function Attachment({
  view,
  direction = "received",
  onReveal,
}: {
  view: AttachmentView;
  direction?: "sent" | "received";
  onReveal?: () => void;
}) {
  if (view.status === "loading") {
    return <span className="text-[13px] italic text-ink-muted">Fetching attachment…</span>;
  }
  if (view.status === "expired" || !view.blob) {
    return (
      <span className="text-[13px] italic text-ink-muted">Attachment expired or unavailable</span>
    );
  }
  if (view.kind === "image") {
    // A RECEIVED image stays covered until the recipient taps it: the bytes are
    // never drawn to an <img> before that (nothing to screenshot), and the
    // reveal arms a hard 10s reveal-and-burn timer. Our OWN sent copy is shown.
    if (direction === "received" && !view.revealed) {
      return <CoveredImage onReveal={onReveal} />;
    }
    return (
      <ImageAttachment blob={view.blob} showBurnHint={direction === "received" && !!view.revealed} />
    );
  }
  return <FileAttachment blob={view.blob} filename={view.filename} size={view.size} />;
}

/**
 * The covered state of a received image before it is revealed. No object URL is
 * created and no <img> is mounted, so none of the photo's pixels are on screen
 * until an explicit tap — which reveals it and starts the 10s reveal-and-burn.
 */
function CoveredImage({ onReveal }: { onReveal?: () => void }) {
  return (
    <button
      type="button"
      onClick={onReveal}
      className="flex h-28 w-44 max-w-full flex-col items-center justify-center gap-1 rounded-lg border border-line bg-bg-primary px-3 text-center"
    >
      <span className="text-[13px] font-medium text-ink-primary">🖼 Tap to reveal photo</span>
      <span className="font-mono text-[11px] text-burn-orange">
        🔥 Burns 10s after you reveal it
      </span>
    </button>
  );
}

function ImageAttachment({ blob, showBurnHint }: { blob: Blob; showBurnHint: boolean }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const objectUrl = URL.createObjectURL(blob);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [blob]);
  if (!url) return null;
  return (
    <span className="block">
      <img
        src={url}
        alt="attachment"
        className="max-h-80 max-w-full rounded-lg"
        style={{ display: "block" }}
        draggable={false}
      />
      {showBurnHint && (
        <span className="mt-1 block font-mono text-[11px] text-burn-orange">
          🔥 Revealed — burns in 10s
        </span>
      )}
    </span>
  );
}

function FileAttachment({
  blob,
  filename,
  size,
}: {
  blob: Blob;
  filename: string | null;
  size: number;
}) {
  const name = filename ?? "file";
  const download = () => {
    // Download from the in-memory blob — no server round-trip, no disk cache.
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(objectUrl);
  };
  return (
    <button
      type="button"
      onClick={download}
      className="flex items-center gap-2 rounded-lg border border-line bg-bg-primary px-3 py-2 text-left"
    >
      <span aria-hidden className="text-lg">
        📄
      </span>
      <span className="min-w-0">
        <span className="block truncate text-[13px] font-medium text-ink-primary">{name}</span>
        <span className="block text-[11px] text-ink-muted">{formatBytes(size)} · Download</span>
      </span>
    </button>
  );
}
