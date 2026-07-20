// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The QR "lemon drop" result modal. Shows the sticker URL as a scannable QR
 * code with the lemon-slice mark punched through the center, the URL as
 * selectable text with a copy affordance, and the drop's expiry.
 *
 * This variant is recipient-targeted by design: the copy here is deliberately
 * NOT worded as anonymous. Anyone who scans the code can fetch the sealed blob
 * from the blind relay, but only the named recipient's device holds the key to
 * open it.
 */

import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { LemonSlice } from "@zitrone/ui";
import { composeDropPrintPng, dropPrintFilename } from "../lib/dropPrint.js";
import { isTauri } from "../lib/nativeTransport.js";

const QR_SIZE = 240;

// Save a Blob to disk in a plain browser: click a temporary download anchor.
function saveViaBrowser(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objectUrl);
}

// Save a Blob to disk under Tauri: the dialog plugin (already registered, with
// `dialog:allow-save`) picks the path; a tiny native `save_drop_image` command
// writes the bytes (the WebView has no fs plugin). We invoke the dialog command
// directly rather than adding the `@tauri-apps/plugin-dialog` JS dependency —
// `@tauri-apps/api` is already a dep and lazy-imported elsewhere for the same
// reason (keeping it out of the browser bundle graph). Returns false if the
// user cancels the dialog.
async function saveViaTauri(blob: Blob, filename: string): Promise<boolean> {
  const { invoke } = await import("@tauri-apps/api/core");
  const path = await invoke<string | null>("plugin:dialog|save", {
    options: {
      defaultPath: filename,
      filters: [{ name: "PNG image", extensions: ["png"] }],
    },
  });
  if (!path) return false;
  const bytes = Array.from(new Uint8Array(await blob.arrayBuffer()));
  await invoke("save_drop_image", { path, bytes });
  return true;
}

export function QrDropModal({
  url,
  expiresAt,
  recipientName,
  onClose,
}: {
  url: string;
  expiresAt: string;
  recipientName: string;
  onClose: () => void;
}) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");

  const onSaveImage = () => {
    setSaveState("saving");
    void (async () => {
      try {
        const blob = await composeDropPrintPng(url, expiresAt);
        const filename = dropPrintFilename(url);
        if (isTauri()) {
          const saved = await saveViaTauri(blob, filename);
          // A canceled save dialog is not an error — return to idle silently.
          setSaveState(saved ? "saved" : "idle");
        } else {
          saveViaBrowser(blob, filename);
          setSaveState("saved");
        }
      } catch {
        setSaveState("error");
      }
    })();
  };

  useEffect(() => {
    let live = true;
    // Error-correction level H tolerates ~30% damage — that headroom is exactly
    // what lets the lemon slice sit over the center without breaking scans. Dark
    // modules in ink, light in white: highest contrast, most reliably scannable.
    void QRCode.toDataURL(url, {
      errorCorrectionLevel: "H",
      margin: 1,
      width: QR_SIZE,
      color: { dark: "#0D0C00", light: "#FFFFFF" },
    })
      .then((d) => {
        if (live) setDataUrl(d);
      })
      .catch(() => {
        if (live) setDataUrl(null);
      });
    return () => {
      live = false;
    };
  }, [url]);

  const burnsBy = (() => {
    const d = new Date(expiresAt);
    return Number.isNaN(d.getTime()) ? expiresAt : d.toLocaleString();
  })();

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/70"
      role="dialog"
      aria-modal
    >
      <div className="flex w-full max-w-md flex-col gap-4 rounded-xl border border-line bg-bg-secondary p-8">
        <h2 className="font-display text-lg font-semibold text-ink-primary">QR drop sealed</h2>

        {/* QR on a white card (high contrast in the dark UI) with the lemon-slice
            mark overlaid on a small white backing so it never eats real modules. */}
        <div className="flex justify-center">
          <div
            className="relative rounded-lg bg-white p-3"
            style={{ width: QR_SIZE + 24, height: QR_SIZE + 24 }}
          >
            {dataUrl ? (
              <img
                src={dataUrl}
                width={QR_SIZE}
                height={QR_SIZE}
                alt="QR code encoding this drop's link"
                className="block"
              />
            ) : (
              <div
                className="flex items-center justify-center"
                style={{ width: QR_SIZE, height: QR_SIZE }}
              >
                <LemonSlice variant="loading_spinner" size={48} label="Rendering QR" />
              </div>
            )}
            {dataUrl && (
              <div
                className="absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-lg bg-white"
                style={{
                  width: Math.round(QR_SIZE * 0.26),
                  height: Math.round(QR_SIZE * 0.26),
                  boxShadow: "0 1px 4px rgba(0,0,0,0.18)",
                }}
              >
                <LemonSlice variant="logo_mark" size={Math.round(QR_SIZE * 0.2)} label="Zitrone" />
              </div>
            )}
          </div>
        </div>

        <p className="text-sm text-ink-secondary">
          Anyone who scans this can fetch the sealed blob from the relay, but only{" "}
          <span className="text-ink-primary">{recipientName}</span>&apos;s device holds the key to
          open it. This drop is addressed to them by name — it is not anonymous.
        </p>

        <code className="select-text break-all rounded-md border border-line bg-bg-primary p-3 font-mono text-xs text-lemon">
          {url}
        </code>

        <p className="text-[12px] text-ink-muted">🔥 Burns by {burnsBy} if unclaimed.</p>

        <div className="flex justify-end gap-2">
          <button
            onClick={() => {
              void navigator.clipboard?.writeText(url).then(() => setCopied(true));
            }}
            className="rounded-full bg-lemon px-4 py-1.5 text-sm font-medium text-ink-on-lemon"
          >
            {copied ? "Copied" : "Copy link"}
          </button>
          <button
            onClick={onSaveImage}
            disabled={saveState === "saving"}
            className="rounded-full bg-lemon px-4 py-1.5 text-sm font-medium text-ink-on-lemon disabled:opacity-60"
          >
            {saveState === "saving"
              ? "Saving…"
              : saveState === "saved"
                ? "Saved"
                : saveState === "error"
                  ? "Try again"
                  : "Save image"}
          </button>
          <button
            onClick={onClose}
            className="rounded-full px-4 py-1.5 text-sm text-ink-secondary hover:text-ink-primary"
          >
            Done
          </button>
        </div>

        <p className="text-[12px] text-ink-muted">
          The saved image contains the drop link — treat it like the printed sticker itself.
        </p>
      </div>
    </div>
  );
}
