// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { useEffect, useMemo, useRef, useState } from "react";
import { generateInvisibleWatermark } from "@zitrone/crypto";
import {
  platformWarning,
  QR_DROP_TTL_HOURS,
  TTL_OPTIONS_SECONDS,
  type QrDropTTLHours,
} from "@zitrone/protocol";
import {
  ComposeBar,
  ConnectionModeBadge,
  LemonSpinner,
  MessageBubble,
  PlatformWarningBadge,
  PrivacyView,
  SecurityBadge,
} from "@zitrone/ui";
import { MESSAGES_CONTAINER_ID } from "../components/ScreenshotShield.js";
import { Attachment } from "../components/Attachment.js";
import { QrDropModal } from "../components/QrDropModal.js";
import { AttachmentTooLargeError, prepareAttachment } from "../lib/attachments.js";
import { useApp } from "../store.js";
import { useSettings } from "../settings.js";

const TTL_LABELS: Record<number, string> = {
  30: "30s",
  60: "1m",
  300: "5m",
  3600: "1h",
  86400: "1d",
  604800: "1w",
};

// The five allowlisted QR-drop lifetimes (hours). Deliberately capped at two
// weeks — a sticker in the wild should not outlive that (see QR_DROP_TTL_HOURS).
const QR_TTL_LABELS: Record<QrDropTTLHours, string> = {
  24: "24h",
  48: "48h",
  72: "72h",
  168: "1 week",
  336: "2 weeks",
};

export function ChatView({ peerId, onVerify }: { peerId: string; onVerify: () => void }) {
  const contact = useApp((s) => s.contacts[peerId]);
  const keyStore = useApp((s) => s.keyStore);
  const messages = useApp((s) => s.messages[peerId] ?? []);
  const accountId = useApp((s) => s.accountId);
  const sendMessage = useApp((s) => s.sendMessage);
  const sendAttachment = useApp((s) => s.sendAttachment);
  const sendDeadDrop = useApp((s) => s.sendDeadDrop);
  const sendQrDrop = useApp((s) => s.sendQrDrop);
  const openMessage = useApp((s) => s.openMessage);
  const finishBurn = useApp((s) => s.finishBurn);
  const expireMessage = useApp((s) => s.expireMessage);
  const revealAttachment = useApp((s) => s.revealAttachment);
  const retryMessage = useApp((s) => s.retryMessage);

  const connectionMode = useSettings((s) => s.connectionMode);
  const transport = useSettings((s) => s.transport);
  const privacyView = useSettings((s) => s.privacyView);
  const privacyActive = useSettings((s) => s.isPrivacyActive(peerId));
  const togglePrivacy = useSettings((s) => s.togglePrivacyForConversation);

  const [ttl, setTtl] = useState<number | null>(null);
  const [burnOnRead, setBurnOnRead] = useState(false);
  const [warningDismissed, setWarningDismissed] = useState(false);
  const [dropToken, setDropToken] = useState<string | null>(null);
  // QR-drop flow: qrDraft holds the composed text awaiting a TTL choice (its
  // presence opens the picker) plus the ComposeBar's clearDraft callback — the
  // box is only cleared after a successful deposit, so cancel/failure never
  // discards the user's only copy; qrResult holds the sealed drop's url +
  // expiry for the result modal.
  const [qrDraft, setQrDraft] = useState<{ text: string; clearDraft: () => void } | null>(
    null,
  );
  const [qrSealing, setQrSealing] = useState(false);
  const [qrError, setQrError] = useState<string | null>(null);
  const [qrResult, setQrResult] = useState<{ url: string; expiresAt: string } | null>(null);
  const [attachError, setAttachError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  // Mark received messages opened when the conversation is on screen — but never
  // while privacy view hides them (you must actively reveal first).
  useEffect(() => {
    if (privacyActive) return;
    for (const m of messages) {
      if (m.direction === "received" && !m.opened) openMessage(peerId, m.id);
    }
  }, [messages, peerId, openMessage, privacyActive]);

  // Invisible watermark: encodes our account ID + timestamp into the chat
  // background for leak attribution if a capture is ever shared.
  const watermarkUrl = useMemo(() => {
    if (!accountId) return null;
    try {
      return generateInvisibleWatermark(accountId, peerId, 256, 256, "#0D0C00").toDataURL();
    } catch {
      return null;
    }
  }, [accountId, peerId]);

  if (!contact) return null;
  const verified = keyStore?.verifiedContacts[peerId] === contact.identityKey;

  // The web client is always on a browser, where OS-level screenshot protection
  // is unavailable — we say so honestly. Re-shown if the user re-enters later.
  const warning = platformWarning("browser", null);

  const onSendDeadDrop = (text: string) => {
    void sendDeadDrop(peerId, text)
      .then(setDropToken)
      .catch(() => setDropToken(null));
  };

  // QR drop: the compose action hands us the text; we open the TTL picker, and
  // only once a lifetime is chosen do we seal (PoW ~1.5s) and deposit. This runs
  // in every mode — Ghost included — because it is a distinct, recipient-targeted
  // mechanism, not the per-message dead drop Ghost routes ordinary sends through.
  const onSendQrDrop = (text: string, clearDraft: () => void) => {
    setQrError(null);
    setQrDraft({ text, clearDraft });
  };
  const chooseQrTtl = (ttlHours: QrDropTTLHours) => {
    const draft = qrDraft;
    if (draft === null || qrSealing) return;
    setQrSealing(true);
    setQrError(null);
    void sendQrDrop(peerId, draft.text, ttlHours)
      .then((res) => {
        setQrResult(res);
        // Only NOW is the compose box cleared: the deposit was accepted, so the
        // draft has somewhere safer to live than the textarea. A cancel or a
        // failed seal leaves the user's only copy untouched in the box.
        draft.clearDraft();
        setQrDraft(null);
      })
      .catch(() => setQrError("Couldn't seal the drop — try again."))
      .finally(() => setQrSealing(false));
  };
  const cancelQrDraft = () => {
    if (qrSealing) return;
    setQrDraft(null);
    setQrError(null);
  };

  // In Ghost mode every message is a dead drop — no direct channel exists. The
  // primary send action therefore routes through the dead-drop path, not the
  // direct WebSocket envelope, so the persistent recipient is never exposed.
  const ghost = connectionMode === "ghost";
  const onPrimarySend = ghost
    ? onSendDeadDrop
    : (text: string) => void sendMessage(peerId, text, { ttlSeconds: ttl, burnOnRead });

  const onAttach = () => {
    setAttachError(null);
    fileInputRef.current?.click();
  };

  const onFileChosen = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // Clear the input so picking the same file again re-fires onChange.
    event.target.value = "";
    if (!file) return;
    void (async () => {
      try {
        const prepared = await prepareAttachment(file);
        await sendAttachment(peerId, prepared, { ttlSeconds: ttl, burnOnRead });
      } catch (err) {
        setAttachError(
          err instanceof AttachmentTooLargeError
            ? err.message
            : "Couldn't send the attachment — try again.",
        );
      }
    })();
  };

  return (
    <section className="flex h-full flex-1 flex-col bg-bg-primary">
      <header className="flex items-center justify-between border-b border-line px-4 py-3">
        <div className="flex items-center gap-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-lemon to-lemon-zest font-display font-semibold text-ink-on-lemon">
            {contact.displayName.charAt(0).toUpperCase()}
          </span>
          <div>
            <h2 className="text-sm font-medium text-ink-primary">{contact.displayName}</h2>
            <SecurityBadge
              state={verified ? "verified" : "unverified"}
              onClick={onVerify}
              size={14}
            />
          </div>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => togglePrivacy(peerId)}
            aria-pressed={privacyActive}
            title="Privacy view — blur messages until you reveal them"
            className={`text-[11px] ${privacyActive ? "text-lemon" : "text-ink-secondary hover:text-lemon"}`}
          >
            {privacyActive ? "🍋 Private" : "Privacy"}
          </button>
          <ConnectionModeBadge mode={connectionMode} transport={transport} size={18} />
          <span className="font-mono text-[11px] text-lemon-deep">🔒 E2E</span>
        </div>
      </header>

      <PlatformWarningBadge
        copy={warning.copy}
        show={warning.show && !warningDismissed}
        onDismiss={() => setWarningDismissed(true)}
      />

      <div
        id={MESSAGES_CONTAINER_ID}
        className="relative flex-1 overflow-y-auto px-4 py-4"
        style={watermarkUrl ? { backgroundImage: `url(${watermarkUrl})` } : undefined}
        onContextMenu={(e) => e.preventDefault()}
      >
        {messages.map((m) => (
          <PrivacyView
            key={m.id}
            active={privacyActive}
            revealMode={privacyView.revealMode}
            tapTimedSeconds={privacyView.tapTimedSeconds}
            onReveal={() => openMessage(peerId, m.id)}
          >
            <MessageBubble
              direction={m.direction}
              burnOnRead={m.burnOnRead}
              ttlSeconds={m.ttlSeconds ?? undefined}
              deliveredAt={m.deliveredAt ?? undefined}
              status={m.status}
              onRetry={() => void retryMessage(peerId, m.id)}
              burning={m.burning}
              onBurned={() => finishBurn(peerId, m.id)}
              onExpired={() => expireMessage(peerId, m.id)}
              timestamp={new Date(m.timestamp).toLocaleTimeString([], {
                hour: "2-digit",
                minute: "2-digit",
              })}
            >
              {m.attachment ? (
                <div className="flex flex-col gap-1.5">
                  <Attachment
                    view={m.attachment}
                    direction={m.direction}
                    onReveal={() => revealAttachment(peerId, m.id)}
                  />
                  {m.attachment.caption && <span>{m.attachment.caption}</span>}
                </div>
              ) : m.unsupported ? (
                <span className="italic opacity-70">Unsupported message — update Zitrone</span>
              ) : (
                m.text
              )}
            </MessageBubble>
          </PrivacyView>
        ))}
        <div ref={bottomRef} />
      </div>

      <div className="flex items-center gap-2 border-t border-line bg-bg-secondary px-4 py-1.5">
        <span className="text-[11px] uppercase tracking-wider text-ink-muted">Self-destruct</span>
        <button
          onClick={() => setTtl(null)}
          className={`rounded-full px-2 py-0.5 text-[11px] ${ttl === null ? "bg-lemon text-ink-on-lemon" : "text-ink-secondary hover:text-lemon"}`}
        >
          off
        </button>
        {TTL_OPTIONS_SECONDS.map((s) => (
          <button
            key={s}
            onClick={() => setTtl(s)}
            className={`rounded-full px-2 py-0.5 font-mono text-[11px] ${ttl === s ? "bg-lemon text-ink-on-lemon" : "text-ink-secondary hover:text-lemon"}`}
          >
            {TTL_LABELS[s]}
          </button>
        ))}
        <button
          onClick={() => setBurnOnRead((b) => !b)}
          aria-pressed={burnOnRead}
          className={`ml-auto rounded-full px-2.5 py-0.5 text-[11px] ${burnOnRead ? "bg-burn-orange text-bg-primary" : "text-ink-secondary hover:text-burn-orange"}`}
        >
          🔥 burn on read
        </button>
      </div>

      {attachError && (
        <div className="border-t border-line bg-bg-secondary px-4 py-1.5 text-[12px] text-burn-orange">
          {attachError}
        </div>
      )}

      {/* Attachments ride the direct WebSocket envelope, so they're offered only
          when a direct channel exists — not in Ghost mode, which routes every
          message through dead drops to avoid exposing the persistent recipient. */}
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={onFileChosen}
        aria-hidden
      />
      <ComposeBar
        onSend={onPrimarySend}
        onAttach={ghost ? undefined : onAttach}
        onSendAsDeadDrop={ghost ? undefined : onSendDeadDrop}
        onSendAsQrDrop={onSendQrDrop}
        placeholder={ghost ? "Message (dead drop)" : "Message"}
      />

      {dropToken && <DeadDropTokenModal token={dropToken} onClose={() => setDropToken(null)} />}

      {qrDraft !== null && (
        <QrTtlPicker
          sealing={qrSealing}
          error={qrError}
          onPick={chooseQrTtl}
          onCancel={cancelQrDraft}
        />
      )}

      {qrResult && (
        <QrDropModal
          url={qrResult.url}
          expiresAt={qrResult.expiresAt}
          recipientName={contact.displayName}
          onClose={() => setQrResult(null)}
        />
      )}
    </section>
  );
}

// Pick a lifetime for a QR drop, then seal. While sealing (one-shot X3DH + PoW,
// ~1.5s) the picker shows a working state instead of freezing silently.
function QrTtlPicker({
  sealing,
  error,
  onPick,
  onCancel,
}: {
  sealing: boolean;
  error: string | null;
  onPick: (ttlHours: QrDropTTLHours) => void;
  onCancel: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/70"
      role="dialog"
      aria-modal
    >
      <div className="flex w-full max-w-md flex-col gap-4 rounded-xl border border-line bg-bg-secondary p-8">
        <h2 className="font-display text-lg font-semibold text-ink-primary">Seal as a QR drop</h2>
        <p className="text-sm text-ink-secondary">
          How long should this drop live on the relay before it burns unclaimed?
        </p>
        {sealing ? (
          <div className="flex items-center gap-3 py-2 text-sm text-ink-secondary">
            <LemonSpinner size={28} label="Sealing" />
            Sealing… (solving the deposit proof-of-work)
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {QR_DROP_TTL_HOURS.map((h) => (
              <button
                key={h}
                onClick={() => onPick(h)}
                className="rounded-full border border-line px-3 py-1 text-[13px] text-ink-secondary hover:bg-lemon hover:text-ink-on-lemon"
              >
                {QR_TTL_LABELS[h]}
              </button>
            ))}
          </div>
        )}
        {error && <p className="text-[12px] text-burn-orange">{error}</p>}
        <div className="flex justify-end">
          <button
            onClick={onCancel}
            disabled={sealing}
            className="rounded-full px-4 py-1.5 text-sm text-ink-secondary hover:text-ink-primary disabled:opacity-50"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

// The dead-drop token is the capability. It is shared out of band — by QR, a
// separate channel, or in person — and never travels with the server.
function DeadDropTokenModal({ token, onClose }: { token: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/70"
      role="dialog"
      aria-modal
    >
      <div className="flex w-full max-w-md flex-col gap-4 rounded-xl border border-line bg-bg-secondary p-8">
        <h2 className="font-display text-lg font-semibold text-ink-primary">Dead drop created</h2>
        <p className="text-sm text-ink-secondary">
          Share this one-time token with your recipient out of band — a QR code, a separate channel,
          or in person. The server never sees it. It works once, then the drop is destroyed.
        </p>
        <code className="select-text break-all rounded-md border border-line bg-bg-primary p-3 font-mono text-xs text-lemon">
          {token}
        </code>
        <div className="flex justify-end gap-2">
          <button
            onClick={() => {
              void navigator.clipboard?.writeText(token).then(() => setCopied(true));
            }}
            className="rounded-full bg-lemon px-4 py-1.5 text-sm font-medium text-ink-on-lemon"
          >
            {copied ? "Copied" : "Copy token"}
          </button>
          <button
            onClick={onClose}
            className="rounded-full px-4 py-1.5 text-sm text-ink-secondary hover:text-ink-primary"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
