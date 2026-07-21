// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { useRef, useState } from "react";
import { color, typography } from "./tokens.js";

export interface Conversation {
  id: string;
  displayName: string;
  verified: boolean;
  unreadCount: number;
  lastActivityLabel?: string;
}

export interface ConversationListProps {
  conversations: Conversation[];
  activeId?: string;
  onSelect: (id: string) => void;
  /**
   * Optional delete affordance. When provided, each row gets a long-press /
   * context-menu path that calls this with the conversation id. The host is
   * responsible for the irreversible confirmation dialog.
   */
  onDelete?: (id: string) => void;
}

export function ConversationList({
  conversations,
  activeId,
  onSelect,
  onDelete,
}: ConversationListProps) {
  return (
    <nav aria-label="Conversations" style={{ display: "flex", flexDirection: "column" }}>
      {conversations.map((c) => (
        <ConversationListItem
          key={c.id}
          conversation={c}
          active={c.id === activeId}
          onSelect={onSelect}
          onDelete={onDelete}
        />
      ))}
    </nav>
  );
}

function ConversationListItem({
  conversation: c,
  active,
  onSelect,
  onDelete,
}: {
  conversation: Conversation;
  active: boolean;
  onSelect: (id: string) => void;
  onDelete?: (id: string) => void;
}) {
  const [hovered, setHovered] = useState(false);
  // Long-press (≥550ms) mirrors mobile: fire delete request, suppress the
  // click that would otherwise open the conversation on pointer-up.
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const longPressFired = useRef(false);

  const clearLongPress = () => {
    if (longPressTimer.current != null) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  };

  const armLongPress = () => {
    if (!onDelete) return;
    longPressFired.current = false;
    clearLongPress();
    longPressTimer.current = setTimeout(() => {
      longPressFired.current = true;
      onDelete(c.id);
    }, 550);
  };

  return (
    <button
      type="button"
      onClick={() => {
        if (longPressFired.current) {
          longPressFired.current = false;
          return;
        }
        onSelect(c.id);
      }}
      onContextMenu={(e) => {
        if (!onDelete) return;
        e.preventDefault();
        onDelete(c.id);
      }}
      onPointerDown={armLongPress}
      onPointerUp={clearLongPress}
      onPointerLeave={() => {
        clearLongPress();
        setHovered(false);
      }}
      onPointerCancel={clearLongPress}
      onMouseEnter={() => setHovered(true)}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 12,
        width: "100%",
        padding: "10px 16px",
        border: "none",
        textAlign: "left",
        cursor: "pointer",
        background: active
          ? color.semantic.backgroundElevated
          : hovered
            ? color.semantic.backgroundSecondary
            : "transparent",
      }}
    >
      <span
        aria-hidden
        style={{
          width: 44,
          height: 44,
          flexShrink: 0,
          borderRadius: "50%",
          background: `linear-gradient(135deg, ${color.core.lemon}, ${color.core.lemonZest})`,
          border: c.verified
            ? `2px solid ${color.semantic.verifiedGreen}`
            : "2px solid transparent",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: color.semantic.textOnLemon,
          fontFamily: typography.display.family,
          fontWeight: 600,
        }}
      >
        {c.displayName.charAt(0).toUpperCase()}
      </span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span
          style={{
            display: "block",
            color: color.semantic.textPrimary,
            fontFamily: typography.body.family,
            fontWeight: 500,
            fontSize: "0.9375rem",
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          {c.displayName}
        </span>
        {/* Preview text never shows content — by design. */}
        <span
          style={{
            display: "block",
            color: color.semantic.textMuted,
            fontFamily: typography.body.family,
            fontSize: "0.8125rem",
          }}
        >
          Encrypted message
        </span>
      </span>
      {c.lastActivityLabel && (
        <span
          style={{
            color: color.semantic.textMuted,
            fontFamily: typography.mono.family,
            fontSize: "0.6875rem",
          }}
        >
          {c.lastActivityLabel}
        </span>
      )}
      {c.unreadCount > 0 && (
        <span
          style={{
            background: color.core.lemon,
            color: color.semantic.textOnLemon,
            borderRadius: 9999,
            minWidth: 20,
            padding: "2px 7px",
            textAlign: "center",
            fontFamily: typography.body.family,
            fontWeight: 500,
            fontSize: "0.75rem",
          }}
        >
          {c.unreadCount}
        </span>
      )}
      {onDelete && hovered && (
        <span
          role="button"
          tabIndex={-1}
          aria-label={`Delete ${c.displayName}`}
          title="Delete contact"
          onClick={(e) => {
            e.stopPropagation();
            onDelete(c.id);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.stopPropagation();
              e.preventDefault();
              onDelete(c.id);
            }
          }}
          style={{
            flexShrink: 0,
            color: color.semantic.textMuted,
            fontSize: "0.875rem",
            padding: "4px 6px",
            borderRadius: 6,
            lineHeight: 1,
          }}
        >
          ×
        </span>
      )}
    </button>
  );
}
