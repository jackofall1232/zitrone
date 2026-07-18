// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import Zitrone

/// The read-receipt discriminator must stay in lockstep with the canonical
/// parseReadReceipt (packages/protocol/src/receipts.ts): strict on `v` AND
/// `control` AND the message_ids array; anything else is conversation text.
final class ControlPayloadTests: XCTestCase {

    func testRecognizesCanonicalReceipt() {
        let receipt = #"{"v":1,"control":"receipt.read","message_ids":["a-b-c"]}"#
        XCTAssertTrue(MessageStore.isControlPayload(receipt))
    }

    func testRecognizesEmptyIDList() {
        XCTAssertTrue(MessageStore.isControlPayload(
            #"{"v":1,"control":"receipt.read","message_ids":[]}"#))
    }

    func testExtraFieldsStillRecognized() {
        // Lenient on extra fields, per the canonical parser.
        XCTAssertTrue(MessageStore.isControlPayload(
            #"{"v":1,"control":"receipt.read","message_ids":["x"],"future":"field"}"#))
    }

    func testPlainTextIsNotControl() {
        XCTAssertFalse(MessageStore.isControlPayload("just a message"))
    }

    func testJSONLookingTextIsNotControl() {
        // isControlPayload stays strict to receipt.read: these are not RECEIPTS.
        // (Since attachments landed, receive() separately routes ANY {v, control}-
        // shaped payload away from the text pipeline via controlKind — a control
        // payload can carry key material, so "not a receipt" no longer implies
        // "render as text". See testControlKind below.)
        XCTAssertFalse(MessageStore.isControlPayload(#"{"v":1,"control":"other"}"#))
        XCTAssertFalse(MessageStore.isControlPayload(#"{"v":2,"control":"receipt.read","message_ids":[]}"#))
        XCTAssertFalse(MessageStore.isControlPayload(#"{"control":"receipt.read","message_ids":[]}"#))
        XCTAssertFalse(MessageStore.isControlPayload(#"{"v":1,"control":"receipt.read"}"#))
    }

    func testMalformedJSONNeverThrows() {
        XCTAssertFalse(MessageStore.isControlPayload("{not json"))
        XCTAssertFalse(MessageStore.isControlPayload("{"))
        XCTAssertFalse(MessageStore.isControlPayload(""))
    }

    func testControlKind() {
        // Any numeric-v + string-control payload is a control payload, whatever
        // the kind — recognized kinds get placeholders, the rest are dropped.
        XCTAssertEqual(MessageStore.controlKind(#"{"v":1,"control":"attachment.v1"}"#), "attachment.v1")
        XCTAssertEqual(MessageStore.controlKind(#"{"v":3,"control":"poll.v1"}"#), "poll.v1")
        // Ordinary text and non-control JSON are not.
        XCTAssertNil(MessageStore.controlKind("hello"))
        XCTAssertNil(MessageStore.controlKind(#"{"hello":"world"}"#))
        XCTAssertNil(MessageStore.controlKind(#"{"v":"1","control":7}"#))
        XCTAssertNil(MessageStore.controlKind("{not json"))
    }
}
