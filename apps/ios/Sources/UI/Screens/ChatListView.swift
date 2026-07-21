// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Chat list per design_system.screens.chat_list: ZITRONE wordmark
/// left-aligned, settings icon right, lemon-bordered pill search bar, and a
/// lemon compose FAB bottom-right with a lemon-glow shadow.
public struct ChatListView: View {
    @ObservedObject var conversations: ConversationStore
    /// This device's own identity fingerprint for the security-paper watermark
    /// (computed once at the composition root and threaded down).
    public let localFingerprint: String?
    public var onOpenConversation: (Conversation) -> Void
    public var onOpenSettings: () -> Void
    public var onCompose: () -> Void
    /// Irreversible contact delete (crypto teardown). Host wires MessageStore.
    public var onDeleteContact: ((Conversation) -> Void)?

    @State private var searchText = ""
    @State private var pendingDelete: Conversation?

    public init(conversations: ConversationStore,
                localFingerprint: String? = nil,
                onOpenConversation: @escaping (Conversation) -> Void,
                onOpenSettings: @escaping () -> Void,
                onCompose: @escaping () -> Void,
                onDeleteContact: ((Conversation) -> Void)? = nil) {
        self.conversations = conversations
        self.localFingerprint = localFingerprint
        self.onOpenConversation = onOpenConversation
        self.onOpenSettings = onOpenSettings
        self.onCompose = onCompose
        self.onDeleteContact = onDeleteContact
    }

    private var filtered: [Conversation] {
        let sorted = conversations.sorted
        guard !searchText.isEmpty else { return sorted }
        return sorted.filter {
            $0.contact.displayName.localizedCaseInsensitiveContains(searchText)
        }
    }

    public var body: some View {
        ZStack(alignment: .bottomTrailing) {
            // Ground + security-paper watermark behind the conversation list.
            ZStack {
                Color.backgroundPrimary
                FingerprintWatermark(fingerprint: localFingerprint)
            }
            .ignoresSafeArea()

            VStack(spacing: 0) {
                header
                searchBar
                if filtered.isEmpty {
                    emptyState
                } else {
                    ConversationList(
                        conversations: filtered,
                        onSelect: onOpenConversation,
                        onDelete: onDeleteContact == nil ? nil : { pendingDelete = $0 }
                    )
                }
            }

            composeFAB
        }
        .confirmationDialog(
            "Delete \(pendingDelete?.contact.displayName ?? "contact")?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete permanently", role: .destructive) {
                if let conversation = pendingDelete {
                    onDeleteContact?(conversation)
                }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) {
                pendingDelete = nil
            }
        } message: {
            Text(
                "This permanently destroys the encryption session and keys for this contact. " +
                "Past messages become permanently undecryptable. You cannot undo this. " +
                "Re-adding the same person starts a completely fresh handshake."
            )
        }
    }

    private var header: some View {
        HStack {
            Text("ZITRONE")
                .font(SubFont.display(TypeScale.lg, weight: .bold))
                .tracking(4)
                .foregroundColor(.lemon)
            Spacer()
            Button(action: onOpenSettings) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 18))
                    .foregroundColor(.textSecondary)
            }
            .accessibilityLabel("Settings")
        }
        .padding(.horizontal, Spacing.s4)
        .padding(.vertical, Spacing.s3)
    }

    private var searchBar: some View {
        HStack(spacing: Spacing.s2) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.textMuted)
            TextField("", text: $searchText)
                .font(SubFont.body(TypeScale.sm))
                .foregroundColor(.textPrimary)
                .tint(.lemon)
                .overlay(alignment: .leading) {
                    if searchText.isEmpty {
                        Text("Search")
                            .font(SubFont.body(TypeScale.sm))
                            .foregroundColor(.textMuted)
                            .allowsHitTesting(false)
                    }
                }
        }
        .padding(.horizontal, Spacing.s4)
        .padding(.vertical, Spacing.s2 + 2)
        .background(Capsule().fill(Color.backgroundSecondary))
        .overlay(Capsule().strokeBorder(Color.lemon.opacity(0.5), lineWidth: 1))
        .padding(.horizontal, Spacing.s4)
        .padding(.bottom, Spacing.s3)
    }

    private var emptyState: some View {
        VStack(spacing: Spacing.s5) {
            Spacer()
            LemonSliceView(variant: .logoMark)
                .frame(width: 72, height: 72)
                .opacity(0.5)
            Text("No conversations yet")
                .font(SubFont.body(TypeScale.base, weight: .medium))
                .foregroundColor(.textSecondary)
            Text("Add a contact by QR code — no phone number,\nno email, no name.")
                .font(SubFont.body(TypeScale.sm))
                .foregroundColor(.textMuted)
                .multilineTextAlignment(.center)
            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private var composeFAB: some View {
        Button(action: onCompose) {
            ZStack {
                Circle().fill(Color.lemon)
                Image(systemName: "plus")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(.textOnLemon)
            }
            .frame(width: 56, height: 56)
            .lemonGlowSmall()
        }
        .buttonStyle(LemonSpringButtonStyle())
        .padding(Spacing.s6)
        .accessibilityLabel("New conversation")
    }
}
