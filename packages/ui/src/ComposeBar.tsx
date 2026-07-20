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
  onSendAsQrDrop?: (text: string) => void;
}

export function ComposeBar({
  onSend,
  onAttach,
  disabled = false,
  sending = false,
  placeholder = "Message",
  onSendAsDeadDrop,
  onSendAsQrDrop,
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
    // Hand the text up for the TTL choice + sealing; clear the box like a send.
    onSendAsQrDrop(trimmed);
    setText("");
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
        <button
          type="button"
          onClick={sendAsQrDrop}
          aria-label="Send as QR drop"
          title="Seal into a QR drop for this contact"
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
          {/* QR glyph: three finder squares + a data block, in currentColor. */}
          <svg width={20} height={20} viewBox="0 0 24 24" fill="currentColor" aria-hidden>
            <path d="M3 3h6v6H3V3zm2 2v2h2V5H5z" />
            <path d="M15 3h6v6h-6V3zm2 2v2h2V5h-2z" />
            <path d="M3 15h6v6H3v-6zm2 2v2h2v-2H5z" />
            <path d="M13 13h3v3h-3v-3zm5 0h3v3h-3v-3zm-5 5h3v3h-3v-3zm5 0h3v3h-3v-3z" />
          </svg>
        </button>
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
