// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Conversation list per design_system.components.conversation_list_item.
/// CRITICAL: the preview text is ALWAYS "Encrypted message" — message content
/// is never previewed anywhere outside the open chat.
public struct ConversationList: View {
    public let conversations: [Conversation]
    public var onSelect: (Conversation) -> Void
    /// Long-press delete. Host must show an irreversible confirmation dialog
    /// before calling `MessageStore.deleteContact`.
    public var onDelete: ((Conversation) -> Void)?

    public init(conversations: [Conversation],
                onSelect: @escaping (Conversation) -> Void,
                onDelete: ((Conversation) -> Void)? = nil) {
        self.conversations = conversations
        self.onSelect = onSelect
        self.onDelete = onDelete
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(conversations) { conversation in
                    Button {
                        onSelect(conversation)
                    } label: {
                        ConversationListItem(conversation: conversation)
                    }
                    .buttonStyle(ConversationRowStyle())
                    .contextMenu {
                        if let onDelete {
                            Button(role: .destructive) {
                                onDelete(conversation)
                            } label: {
                                Label("Delete contact", systemImage: "trash")
                            }
                        }
                    }
                    // Long-press affordance for users who don't discover the
                    // context menu: same action as the destructive menu item.
                    .onLongPressGesture(minimumDuration: 0.55) {
                        onDelete?(conversation)
                    }
                }
            }
        }
        // No opaque background of its own: the hosting screen paints the
        // ground AND the fingerprint watermark beneath this list — an opaque
        // fill here would hide the security paper behind every row (PR #8
        // review).
    }
}

public struct ConversationListItem: View {
    public let conversation: Conversation

    public init(conversation: Conversation) {
        self.conversation = conversation
    }

    public var body: some View {
        HStack(spacing: Spacing.s3) {
            LemonAvatar(name: conversation.contact.displayName,
                        verified: conversation.contact.verified)

            VStack(alignment: .leading, spacing: Spacing.s1) {
                Text(conversation.contact.displayName)
                    .font(SubFont.body(TypeScale.base, weight: .medium))
                    .foregroundColor(.textPrimary)
                    .lineLimit(1)
                // Never previews content — by design.
                Text("Encrypted message")
                    .font(SubFont.body(TypeScale.sm))
                    .foregroundColor(.textMuted)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: Spacing.s1) {
                if let lastActivity = conversation.lastActivity {
                    Text(lastActivity, style: .time)
                        .font(SubFont.mono(TypeScale.xs))
                        .foregroundColor(.textMuted)
                }
                if conversation.unreadCount > 0 {
                    Text("\(conversation.unreadCount)")
                        .font(SubFont.body(TypeScale.xs, weight: .medium))
                        .foregroundColor(.textOnLemon)
                        .padding(.horizontal, Spacing.s2)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Color.lemon))
                }
            }
        }
        .padding(.horizontal, Spacing.s4)
        .padding(.vertical, Spacing.s3)
        .contentShape(Rectangle())
    }
}

/// 44pt circular avatar on a lemon gradient; 2pt green ring when the
/// contact's keys are verified.
public struct LemonAvatar: View {
    public let name: String
    public let verified: Bool
    public var size: CGFloat = 44

    public init(name: String, verified: Bool, size: CGFloat = 44) {
        self.name = name
        self.verified = verified
        self.size = size
    }

    public var body: some View {
        ZStack {
            Circle().fill(SubGradient.avatar)
            Text(initials)
                .font(SubFont.display(size * 0.4, weight: .semibold))
                .foregroundColor(.textOnLemon)
        }
        .frame(width: size, height: size)
        .overlay(
            Circle().strokeBorder(verified ? Color.verifiedGreen : .clear, lineWidth: 2)
        )
    }

    private var initials: String {
        let parts = name.split(separator: " ").prefix(2)
        let letters = parts.compactMap { $0.first.map(String.init) }
        return letters.isEmpty ? "?" : letters.joined().uppercased()
    }
}

private struct ConversationRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.backgroundElevated : .clear)
    }
}
