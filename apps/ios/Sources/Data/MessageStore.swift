// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Combine

/// A decrypted message. EXISTS IN MEMORY ONLY — plaintext is never written to
/// disk, never logged, and is destroyed when its TTL expires, when it is
/// burned, or when the process ends.
public struct Message: Identifiable, Equatable {
    public enum State: Equatable {
        case sending
        case sent
        case delivered
        case opened
        /// Particle-dissolve animation in flight (600ms); plaintext is purged
        /// the moment it completes.
        case burning
    }

    public let id: UUID
    public let conversationID: UUID
    public let text: String
    public let isOutgoing: Bool
    public let sentAt: Date
    public let ttlSeconds: Int?
    public let burnOnRead: Bool
    public var deliveredAt: Date?
    public var state: State

    /// TTL countdown starts on delivery (timer_starts: on_delivery).
    public var expiresAt: Date? {
        guard let ttlSeconds, let deliveredAt else { return nil }
        return deliveredAt.addingTimeInterval(TimeInterval(ttlSeconds))
    }

    public init(id: UUID = UUID(),
                conversationID: UUID,
                text: String,
                isOutgoing: Bool,
                sentAt: Date = Date(),
                ttlSeconds: Int? = nil,
                burnOnRead: Bool = false,
                deliveredAt: Date? = nil,
                state: State = .sending) {
        self.id = id
        self.conversationID = conversationID
        self.text = text
        self.isOutgoing = isOutgoing
        self.sentAt = sentAt
        self.ttlSeconds = ttlSeconds
        self.burnOnRead = burnOnRead
        self.deliveredAt = deliveredAt
        self.state = state
    }
}

/// In-memory store of decrypted messages with ephemeral-messaging enforcement:
///
/// - Delivery ack → server purges its envelope copy immediately.
/// - TTL → local plaintext destroyed when the timer fires (both sides).
/// - Burn-on-read → destroyed right after first open; the peer is notified
///   via the `message.burn` WebSocket event so their copy dies too.
@MainActor
public final class MessageStore: ObservableObject {
    @Published public private(set) var messagesByConversation: [UUID: [Message]] = [:]

    private var ttlTasks: [UUID: Task<Void, Never>] = [:]
    private let signal: SignalManager
    private let socket: WebSocketClient
    private let conversations: ConversationStore

    /// Fired on every inbound message — carries NO content and NO sender.
    /// The app layer uses it to post the fixed "New message" notification
    /// when backgrounded.
    public var onInboundMessage: (() -> Void)?

    /// Privacy-safe send-path diagnostics sink (BootDiagnostics.record) —
    /// fixed stage markers + error type/code only, never content or ids.
    /// Without it, a failed send is invisible: the bubble just disappears
    /// (exactly how Android's unsent messages hid until PR #23's Part 1).
    public var diag: (String) -> Void = { _ in }

    public init(signal: SignalManager,
                socket: WebSocketClient,
                conversations: ConversationStore) {
        self.signal = signal
        self.socket = socket
        self.conversations = conversations

        socket.onMessageDeliver = { [weak self] envelope in
            Task { @MainActor in self?.receive(envelope: envelope) }
        }
        socket.onMessageBurned = { [weak self] messageID in
            Task { @MainActor in self?.handleRemoteBurn(messageID: messageID) }
        }
    }

    public func messages(for conversationID: UUID) -> [Message] {
        messagesByConversation[conversationID] ?? []
    }

    // MARK: - Sending

    public func send(text: String,
                     to contact: Contact,
                     ttlSeconds: Int?,
                     burnOnRead: Bool) async {
        // Drop immediately if the peer was already deleted — do not paint a
        // bubble that can only fail after an encrypt await.
        if conversations.wasRecentlyDeleted(contactID: contact.id)
            || conversations.conversation(for: contact.id) == nil {
            diag("send: contact deleted or absent — aborted")
            return
        }

        var message = Message(conversationID: contact.id,
                              text: text,
                              isOutgoing: true,
                              ttlSeconds: ttlSeconds,
                              burnOnRead: burnOnRead)
        append(message)

        do {
            if !signal.hasSession(with: contact.id) {
                diag("send: no session — firing GET prekey bundle")
            }
            // Length-hiding padding before encryption (256-byte blocks) —
            // the cross-platform plaintext convention since PR #24; see
            // MessagePadding / packages/crypto/src/padding.ts.
            let envelope = try await signal.encrypt(
                plaintext: MessagePadding.pad(Data(text.utf8)),
                for: contact,
                messageID: message.id,
                ttlSeconds: ttlSeconds,
                burnOnRead: burnOnRead
            )
            // Re-check after the await: deleteContact may have landed while
            // we suspended on prekey fetch / encrypt. MainActor serializes
            // UI mutations; this is the check-then-act gap for async send.
            if conversations.wasRecentlyDeleted(contactID: contact.id)
                || conversations.conversation(for: contact.id) == nil {
                diag("send: contact deleted mid-encrypt — drop ciphertext")
                remove(messageID: message.id, conversationID: contact.id)
                return
            }
            if socket.state != .connected {
                diag("send: hand-off while socket \(socket.state) — frame will be dropped")
            }
            try socket.send(.messageSend(envelope))
            message.state = .sent
            // Sender-side TTL mirrors the recipient's (enforced_both_sides).
            message.deliveredAt = Date()
            update(message)
            scheduleTTLDestruction(for: message)
        } catch {
            // Encryption/transport failure — never log content, only state.
            diag("send: failed: \(BootDiagnostics.describe(error))")
            remove(messageID: message.id, conversationID: contact.id)
        }
        if conversations.conversation(for: contact.id) != nil {
            conversations.noteActivity(contactID: contact.id, incrementUnread: false)
        }
    }

    // MARK: - Receiving

    public func receive(envelope: MessageEnvelope) {
        guard let senderID = UUID(uuidString: envelope.senderID) else { return }
        // Tombstone FIRST — before decrypt. A PreKey message would otherwise
        // TOFU-establish a fresh session and resurrect crypto state for a
        // contact the user just deleted. Ack so the relay drops its copy.
        // Keyed on the deletion tombstone, NOT roster absence: a first-time
        // inbound sender is legitimately absent and must still be allowed.
        if conversations.wasRecentlyDeleted(contactID: senderID) {
            diag("recv: message for deleted contact — dropped before decrypt")
            try? socket.send(.messageAck(messageID: envelope.id))
            return
        }
        do {
            let plaintext = try signal.decrypt(envelope: envelope)
            // Strip length-hiding padding; a legacy (pre-padding) sender's
            // bytes pass through unchanged — see MessagePadding.
            let body = MessagePadding.unpadOrNil(plaintext) ?? plaintext
            var text = String(decoding: body, as: UTF8.self)
            // Read receipts ride inside ordinary envelopes as encrypted
            // control payloads (packages/protocol/src/receipts.ts, PR #24) —
            // recognize them BEFORE treating the payload as conversation
            // text, or an Android/web peer's receipt renders as a JSON blob.
            // iOS doesn't SEND receipts or surface read state yet (tracked in
            // todos.md); swallowing + acking keeps the store-and-forward
            // contract without rendering garbage.
            if Self.isControlPayload(text) {
                try? socket.send(.messageAck(messageID: envelope.id))
                return
            }
            // Attachments (Android/web, this release) also ride as control
            // payloads — an envelope of media_type "text" whose plaintext is
            // `{"v":1,"control":"attachment.v1",...}` — and, like receipts,
            // must never reach the text pipeline: a control payload can
            // carry key material, and any future control kind we don't
            // recognize yet deserves the same caution. iOS has no attachment
            // support yet (future work), so show a placeholder for the one
            // kind we know and silently drop anything else, same as an
            // unrecognized receipt above.
            if let control = Self.controlKind(text) {
                guard control == "attachment.v1" else {
                    try? socket.send(.messageAck(messageID: envelope.id))
                    return
                }
                text = "📎 Attachment — not supported on iOS yet"
            }
            let message = Message(
                id: UUID(uuidString: envelope.id) ?? UUID(),
                conversationID: senderID,
                text: text,
                isOutgoing: false,
                sentAt: envelope.timestampDate ?? Date(),
                ttlSeconds: envelope.ttlSeconds,
                burnOnRead: envelope.burnOnRead,
                deliveredAt: Date(),
                state: .delivered
            )
            append(message)
            scheduleTTLDestruction(for: message)
            conversations.noteActivity(contactID: senderID, incrementUnread: true)
            onInboundMessage?()
        } catch {
            // Undecryptable envelope (e.g. stale session). Dropped silently —
            // content is unknown by definition and must not be logged.
        }
        // Ack delivery regardless: the server deletes its copy immediately.
        try? socket.send(.messageAck(messageID: envelope.id))
    }

    // MARK: - Burn-on-read

    /// Called when the recipient opens a burn-on-read message. Starts the
    /// 600ms particle dissolve; destruction follows from the animation's
    /// completion callback (burnAnimationFinished).
    public func markOpened(messageID: UUID, conversationID: UUID) {
        guard var message = find(messageID: messageID, conversationID: conversationID),
              message.state != .burning else { return }
        message.state = message.burnOnRead ? .burning : .opened
        update(message)
        if message.burnOnRead {
            // Tell the network the message burned, so the sender's copy and
            // any server residue are destroyed as well. The conversation ID
            // is the peer's account UUID — the server requires peer_id to
            // route the burn notification (rejects it with bad_peer otherwise).
            try? socket.send(.messageBurn(messageID: messageID.uuidString.lowercased(),
                                          peerID: conversationID.uuidString.lowercased()))
        }
    }

    /// Completion of the particle dissolve — the plaintext dies here.
    public func burnAnimationFinished(messageID: UUID, conversationID: UUID) {
        remove(messageID: messageID, conversationID: conversationID)
    }

    /// The peer burned a message (burn-on-read opened, or burned early).
    public func handleRemoteBurn(messageID: String) {
        guard let id = UUID(uuidString: messageID) else { return }
        for (conversationID, messages) in messagesByConversation
        where messages.contains(where: { $0.id == id }) {
            beginBurn(messageID: id, conversationID: conversationID)
        }
    }

    /// "Burn all" chat header action: every message in the conversation goes
    /// through the dissolve and is destroyed, and the peer is notified.
    public func burnAll(conversationID: UUID) {
        burnAll(conversationID: conversationID, notifyPeer: true)
    }

    /// Burn every local message in the conversation. Peer notification is
    /// best-effort (not re-queued if offline) — same contract as Android.
    public func burnAll(conversationID: UUID, notifyPeer: Bool) {
        for message in messages(for: conversationID) {
            if notifyPeer {
                try? socket.send(.messageBurn(messageID: message.id.uuidString.lowercased(),
                                              peerID: conversationID.uuidString.lowercased()))
            }
            beginBurn(messageID: message.id, conversationID: conversationID)
        }
    }

    // MARK: - Contact deletion

    /// Full contact deletion (cryptographic teardown, not soft-delete).
    ///
    /// Order matches Android's durable fail-abort:
    /// 1. `signal.destroyContact` — if false, abort (contact kept)
    /// 2. Persist tombstone so stragglers drop across restart
    /// 3. Burn known local messages (+ best-effort peer burn)
    /// 4. Remove roster entry
    ///
    /// Concurrency: this type is `@MainActor`, so delete cannot interleave with
    /// `receive` / UI-driven `send` start on another MainActor task mid-mutation.
    /// `send` still re-checks after its encrypt `await` (prekey fetch) because
    /// that suspension is the remaining check-then-act gap. Signal crypto itself
    /// is confined to `SignalManager`'s serial queue.
    ///
    /// - Returns: `false` when crypto teardown did not reach the keychain —
    ///   contact is left intact so the user can retry.
    @discardableResult
    public func deleteContact(contactID: UUID) -> Bool {
        guard conversations.conversation(for: contactID) != nil else { return false }
        let wiped = signal.destroyContact(contactID)
        if !wiped {
            diag("delete: crypto teardown failed — aborting, contact kept")
            return false
        }
        conversations.recordDeletion(contactID: contactID)
        // Peer-burn is best-effort; runs while we still know message ids.
        burnAll(conversationID: contactID, notifyPeer: true)
        conversations.remove(contactID: contactID)
        // Drop any residual message map entry after burn animations schedule.
        messagesByConversation[contactID] = nil
        return true
    }

    private func beginBurn(messageID: UUID, conversationID: UUID) {
        guard var message = find(messageID: messageID, conversationID: conversationID),
              message.state != .burning else { return }
        message.state = .burning
        update(message)
        // Failsafe: even if the bubble is off-screen and never reports its
        // animation finishing, the plaintext is purged after the 600ms burn.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Motion.dramatic * 1.5 * 1_000_000_000))
            await self?.burnAnimationFinished(messageID: messageID, conversationID: conversationID)
        }
    }

    // MARK: - TTL enforcement

    private func scheduleTTLDestruction(for message: Message) {
        guard let expiresAt = message.expiresAt else { return }
        ttlTasks[message.id]?.cancel()
        let messageID = message.id
        let conversationID = message.conversationID
        ttlTasks[message.id] = Task { [weak self] in
            let interval = expiresAt.timeIntervalSinceNow
            if interval > 0 {
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            }
            guard !Task.isCancelled else { return }
            await self?.beginBurn(messageID: messageID, conversationID: conversationID)
        }
    }

    // MARK: - Collection plumbing

    private func append(_ message: Message) {
        messagesByConversation[message.conversationID, default: []].append(message)
    }

    private func update(_ message: Message) {
        guard let index = messagesByConversation[message.conversationID]?
            .firstIndex(where: { $0.id == message.id }) else { return }
        messagesByConversation[message.conversationID]?[index] = message
    }

    private func find(messageID: UUID, conversationID: UUID) -> Message? {
        messagesByConversation[conversationID]?.first { $0.id == messageID }
    }

    private func remove(messageID: UUID, conversationID: UUID) {
        ttlTasks[messageID]?.cancel()
        ttlTasks[messageID] = nil
        messagesByConversation[conversationID]?.removeAll { $0.id == messageID }
    }

    /// Panic wipe — destroys every decrypted message in memory.
    public func wipeAll() {
        ttlTasks.values.forEach { $0.cancel() }
        ttlTasks.removeAll()
        messagesByConversation.removeAll()
    }

    // MARK: - Control payloads (packages/protocol/src/receipts.ts)

    /// True when decrypted plaintext is a read-receipt control payload —
    /// the same strict discriminator as the canonical parseReadReceipt
    /// (`v` AND `control` AND a message_ids array must all match; anything
    /// else is conversation text). Never throws on malformed input.
    nonisolated internal static func isControlPayload(_ plaintext: String) -> Bool {
        guard plaintext.hasPrefix("{"),
              let object = try? JSONSerialization.jsonObject(with: Data(plaintext.utf8)),
              let dict = object as? [String: Any] else { return false }
        return (dict["v"] as? Int) == 1
            && (dict["control"] as? String) == "receipt.read"
            && dict["message_ids"] is [Any]
    }

    /// Returns the `control` discriminator for ANY control payload — numeric
    /// `v` plus a string `control`, regardless of value — broader than
    /// isControlPayload's strict receipt.read match. Lets receive() catch
    /// forward-compatible control kinds (e.g. attachment.v1) it doesn't
    /// otherwise recognize, so they're never rendered as raw text. Never
    /// throws on malformed input.
    nonisolated internal static func controlKind(_ plaintext: String) -> String? {
        guard plaintext.hasPrefix("{"),
              let object = try? JSONSerialization.jsonObject(with: Data(plaintext.utf8)),
              let dict = object as? [String: Any],
              (dict["v"] as? Int) != nil else { return nil }
        return dict["control"] as? String
    }
}
