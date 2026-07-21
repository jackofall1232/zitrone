// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import Zitrone

/// Pure-logic coverage for contact-delete tombstones (TTL window, prune,
/// codec). Keychain / libsignal teardown is NOT exercised here — this
/// environment has no Xcode/simulator; HoboJoe must build and run the full
/// suite (including any future Keychain-backed destroyContact tests) on a
/// Mac before merge.
final class ContactDeletionTombstonesTests: XCTestCase {
    private let bob = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let carol = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    func testRecordAndDetectWithinWindow() {
        var t = ContactDeletionTombstones()
        XCTAssertFalse(t.wasRecentlyDeleted(contactID: bob, now: t0))
        t.record(contactID: bob, now: t0)
        XCTAssertTrue(t.wasRecentlyDeleted(contactID: bob, now: t0.addingTimeInterval(1)))
        XCTAssertTrue(
            t.wasRecentlyDeleted(
                contactID: bob,
                now: t0.addingTimeInterval(ContactDeletionTombstones.window - 1)
            )
        )
    }

    func testExpiresAtWindowBoundary() {
        var t = ContactDeletionTombstones()
        t.record(contactID: bob, now: t0)
        XCTAssertFalse(
            t.wasRecentlyDeleted(
                contactID: bob,
                now: t0.addingTimeInterval(ContactDeletionTombstones.window)
            )
        )
        // Stale entry pruned on the negative check.
        XCTAssertNil(t.entries[bob.uuidString.lowercased()])
    }

    func testScopedToOneContact() {
        var t = ContactDeletionTombstones()
        t.record(contactID: bob, now: t0)
        XCTAssertFalse(t.wasRecentlyDeleted(contactID: carol, now: t0))
    }

    func testPruneOnRecordDropsExpired() {
        var t = ContactDeletionTombstones()
        t.record(contactID: bob, now: t0)
        t.record(
            contactID: carol,
            now: t0.addingTimeInterval(ContactDeletionTombstones.window + 1)
        )
        XCTAssertNil(t.entries[bob.uuidString.lowercased()])
        XCTAssertNotNil(t.entries[carol.uuidString.lowercased()])
    }

    func testRoundTripCodec() {
        var t = ContactDeletionTombstones()
        t.record(contactID: bob, now: t0)
        let data = t.encode()
        let restored = ContactDeletionTombstones.decode(data)
        XCTAssertTrue(restored.wasRecentlyDeleted(contactID: bob, now: t0.addingTimeInterval(60)))
    }

    func testClear() {
        var t = ContactDeletionTombstones()
        t.record(contactID: bob, now: t0)
        t.clear()
        XCTAssertTrue(t.entries.isEmpty)
    }

    func testWindowMatchesAndroidNinetySixHours() {
        XCTAssertEqual(ContactDeletionTombstones.window, 96 * 60 * 60)
    }
}

@MainActor
final class ConversationStoreTombstonePersistenceTests: XCTestCase {
    func testTombstoneSurvivesRestartViaUserDefaults() {
        let suite = "org.zitrone.tests.tombstones.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let bob = UUID(uuidString: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!
        let storeA = ConversationStore(defaults: defaults)
        storeA.upsert(Contact(id: bob, displayName: "Bob", identityKey: Data([0x05] + [UInt8](repeating: 1, count: 32))))
        storeA.recordDeletion(contactID: bob)

        // Simulate process restart: new store instance, same defaults suite.
        let storeB = ConversationStore(defaults: defaults)
        XCTAssertTrue(storeB.wasRecentlyDeleted(contactID: bob))
        XCTAssertNil(storeB.conversation(for: bob)) // roster was memory-only
    }
}
