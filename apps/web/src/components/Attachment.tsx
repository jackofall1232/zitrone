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

export function Attachment({ view }: { view: AttachmentView }) {
  if (view.status === "loading") {
    return <span className="text-[13px] italic text-ink-muted">Fetching attachment…</span>;
  }
  if (view.status === "expired" || !view.blob) {
    return (
      <span className="text-[13px] italic text-ink-muted">Attachment expired or unavailable</span>
    );
  }
  return view.kind === "image" ? (
    <ImageAttachment blob={view.blob} />
  ) : (
    <FileAttachment blob={view.blob} filename={view.filename} size={view.size} />
  );
}

function ImageAttachment({ blob }: { blob: Blob }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const objectUrl = URL.createObjectURL(blob);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [blob]);
  if (!url) return null;
  return (
    <img
      src={url}
      alt="attachment"
      className="max-h-80 max-w-full rounded-lg"
      style={{ display: "block" }}
      draggable={false}
    />
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
