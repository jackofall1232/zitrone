// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { useState } from "react";
import { SendButton } from "./SendButton.js";
import { color, motion, typography } from "./tokens.js";

export interface ComposeBarProps {
  onSend: (text: string) => void;
  onAttach?: () => void;
  disabled?: boolean;
  sending?: boolean;
  placeholder?: string;
  /** Long-press the send button to compose a dead drop (Ghost/Stealth modes). */
  onSendAsDeadDrop?: (text: string) => void;
  /**
   * Seal the composed text into a QR "lemon drop" addressed to this contact.
   * Surfaced as a dedicated leading action (a QR glyph) rather than the send
   * button's long-press, which is already taken by the dead-drop affordance.
   * Available in every mode, Ghost included — it is a distinct mechanism.
   */
  /** QR dead-drop action. The draft is handed up UNCLEARED — sealing has a
   *  TTL-picker step the user may cancel, and clearing here would discard the
   *  only copy. The parent calls `clearDraft` after a successful deposit. */
  onSendAsQrDrop?: (text: string, clearDraft: () => void) => void;
  /**
   * One-time coachmark that teaches the lemon-drop affordance. When true (and
   * onSendAsQrDrop is provided) a small popover explains what sealing does, and
   * the droplet button pulses once. The parent owns the seen-flag; this stays a
   * pure presentational input so the persistence lives with app settings.
   */
  showQrDropCoachmark?: boolean;
  /** Fired when the coachmark is dismissed — via its × or by tapping the button. */
  onQrDropCoachmarkDismiss?: () => void;
}

export function ComposeBar({
  onSend,
  onAttach,
  disabled = false,
  sending = false,
  placeholder = "Message",
  onSendAsDeadDrop,
  onSendAsQrDrop,
  showQrDropCoachmark = false,
  onQrDropCoachmarkDismiss,
}: ComposeBarProps) {
  const [text, setText] = useState("");
  const [focused, setFocused] = useState(false);

  const send = () => {
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText("");
  };

  const sendAsDeadDrop = () => {
    const trimmed = text.trim();
    if (!trimmed || disabled || !onSendAsDeadDrop) return;
    onSendAsDeadDrop(trimmed);
    setText("");
  };

  const sendAsQrDrop = () => {
    const trimmed = text.trim();
    if (!trimmed || disabled || !onSendAsQrDrop) return;
    // Hand the text up for the TTL choice + sealing. Deliberately NOT cleared
    // here: the picker may be canceled (or sealing may fail), and the box holds
    // the only copy of the draft — the parent clears it on successful deposit.
    onSendAsQrDrop(trimmed, () => setText(""));
  };

  // The coachmark only makes sense when the affordance it points at exists.
  const coachmarkVisible = showQrDropCoachmark && !!onSendAsQrDrop;

  const onQrDropClick = () => {
    // Dismiss regardless of draft state: the user has now discovered the button,
    // so the teaching popover has done its job even if sealAsQrDrop no-ops on an
    // empty box. Fire dismiss BEFORE sealing so the coachmark never lingers.
    if (coachmarkVisible) onQrDropCoachmarkDismiss?.();
    sendAsQrDrop();
  };

  return (
    <div
      style={{
        display: "flex",
        alignItems: "flex-end",
        gap: 8,
        padding: 12,
        background: color.semantic.backgroundSecondary,
        borderTop: `1px solid ${color.semantic.border}`,
      }}
    >
      {onAttach && (
        <button
          type="button"
          onClick={onAttach}
          aria-label="Attach"
          style={{
            background: "none",
            border: "none",
            cursor: "pointer",
            color: color.semantic.textSecondary,
            padding: 8,
            fontSize: "1.25rem",
            lineHeight: 1,
          }}
          onMouseEnter={(e) => (e.currentTarget.style.color = color.core.lemon)}
          onMouseLeave={(e) => (e.currentTarget.style.color = color.semantic.textSecondary)}
        >
          +
        </button>
      )}
      {onSendAsQrDrop && (
        // Relative anchor so the coachmark popover can sit ABOVE the droplet
        // button without pushing the compose row's layout. Nothing on the path
        // up to the chat <section> clips overflow, so the popover renders freely.
        <div style={{ position: "relative", display: "inline-flex" }}>
          {coachmarkVisible && (
            <div
              role="note"
              style={{
                position: "absolute",
                bottom: "calc(100% + 10px)",
                left: 0,
                zIndex: 50,
                maxWidth: 240,
                background: color.semantic.backgroundElevated,
                border: `1px solid ${color.semantic.border}`,
                borderRadius: 10,
                padding: "10px 28px 10px 12px",
                fontSize: "12.5px",
                lineHeight: 1.4,
                color: color.semantic.textPrimary,
                boxShadow: "0 6px 20px rgba(0, 0, 0, 0.35)",
              }}
            >
              Seal this message into a QR your contact scans later — it never travels as a live
              message.
              <button
                type="button"
                onClick={onQrDropCoachmarkDismiss}
                aria-label="Dismiss"
                style={{
                  position: "absolute",
                  top: 4,
                  right: 4,
                  background: "none",
                  border: "none",
                  cursor: "pointer",
                  color: color.semantic.textSecondary,
                  padding: 4,
                  fontSize: "14px",
                  lineHeight: 1,
                }}
              >
                ×
              </button>
            </div>
          )}
          <button
            type="button"
            onClick={onQrDropClick}
            aria-label="Seal into a lemon drop"
            title="Seal into a QR drop for this contact"
            className={coachmarkVisible ? "sub-lemon-drop-intro" : undefined}
            style={{
              background: "none",
              border: "none",
              cursor: "pointer",
              color: color.semantic.textSecondary,
              padding: 8,
              lineHeight: 0,
            }}
            onMouseEnter={(e) => (e.currentTarget.style.color = color.core.lemon)}
            onMouseLeave={(e) => (e.currentTarget.style.color = color.semantic.textSecondary)}
          >
            {/* Droplet outline: the lemon-drop mark, in currentColor. */}
            <svg
              width={20}
              height={20}
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={1.8}
              strokeLinejoin="round"
              aria-hidden
            >
              <path d="M12 3C8.2 8 5.8 11.4 5.8 14.6a6.2 6.2 0 0 0 12.4 0C18.2 11.4 15.8 8 12 3z" />
            </svg>
          </button>
        </div>
      )}
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        placeholder={placeholder}
        rows={1}
        style={{
          flex: 1,
          resize: "none",
          background: color.semantic.backgroundElevated,
          border: `1px solid ${focused ? color.semantic.borderActive : color.semantic.border}`,
          borderRadius: 24,
          padding: "10px 16px",
          color: color.semantic.textPrimary,
          fontFamily: typography.body.family,
          fontSize: "0.9375rem",
          outline: "none",
          transition: `border-color ${motion.durationBase} ${motion.easingDefault}`,
        }}
      />
      {/* The send button is always lemon yellow — it is the primary action color.
          Long-press surfaces "Send as dead drop" when the handler is provided. */}
      <SendButton
        onSend={send}
        disabled={disabled}
        sending={sending}
        onLongPress={onSendAsDeadDrop ? sendAsDeadDrop : undefined}
      />
    </div>
  );
}
