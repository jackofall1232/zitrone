// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Persisted, TTL-bounded deleted-contact tombstones.
///
/// Same security purpose as Android's `ConversationRepository` tombstones and
/// the web `deletedContacts` map: a straggler envelope from a just-deleted
/// contact must be dropped (including after process restart) instead of
/// TOFU-establishing a fresh session and resurrecting the roster entry —
/// without silently blocking a genuinely later re-add past the window.
///
/// Pure value type so XCTest can cover prune / window logic without Keychain
/// or libsignal. The durable medium is chosen by the caller (UserDefaults is
/// appropriate: the map is not key material).
public struct ContactDeletionTombstones: Equatable {
    /// Covers the relay's undelivered-envelope window (72 h) plus margin —
    /// identical to Android's `TOMBSTONE_WINDOW_MS` (96 h).
    public static let window: TimeInterval = 96 * 60 * 60

    /// contactId (lowercase UUID string) → deletedAt (timeIntervalSince1970).
    public private(set) var entries: [String: TimeInterval]

    public init(entries: [String: TimeInterval] = [:]) {
        self.entries = entries
    }

    public mutating func record(contactID: UUID, now: Date = Date()) {
        prune(now: now)
        entries[contactID.uuidString.lowercased()] = now.timeIntervalSince1970
    }

    public mutating func wasRecentlyDeleted(contactID: UUID, now: Date = Date()) -> Bool {
        let key = contactID.uuidString.lowercased()
        guard let at = entries[key] else { return false }
        if now.timeIntervalSince1970 - at >= Self.window {
            entries.removeValue(forKey: key)
            return false
        }
        return true
    }

    public mutating func clear() {
        entries.removeAll()
    }

    public mutating func prune(now: Date = Date()) {
        let cutoff = now.timeIntervalSince1970 - Self.window
        entries = entries.filter { $0.value >= cutoff }
    }

    // MARK: - Persistence codec

    public func encode() -> Data {
        // JSON object of string → number. No schema version needed for a flat map.
        let obj = entries as NSDictionary
        return (try? JSONSerialization.data(withJSONObject: obj, options: [])) ?? Data("{}".utf8)
    }

    public static func decode(_ data: Data?) -> ContactDeletionTombstones {
        guard let data, !data.isEmpty,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return ContactDeletionTombstones() }
        var entries: [String: TimeInterval] = [:]
        for (k, v) in obj {
            if let n = v as? TimeInterval {
                entries[k.lowercased()] = n
            } else if let n = v as? Int {
                entries[k.lowercased()] = TimeInterval(n)
            } else if let n = v as? Double {
                entries[k.lowercased()] = n
            }
        }
        var t = ContactDeletionTombstones(entries: entries)
        t.prune()
        return t
    }
}
