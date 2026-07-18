// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import Zitrone

final class SafetyNumberTests: XCTestCase {
    private let keyA = Data([0x05] + (1...32).map { UInt8($0) })
    private let keyB = Data([0x05] + (101...132).map { UInt8($0) })

    // MARK: format

    func testFormatGroupsOfFourUppercase() {
        XCTAssertEqual(SafetyNumber.format("a1b2c3d4e5f6"), "A1B2 C3D4 E5F6")
    }

    func testFormatHandlesPartialFinalGroup() {
        XCTAssertEqual(SafetyNumber.format("abcdef"), "ABCD EF")
    }

    func testFormatIsIdempotent() {
        let once = SafetyNumber.format("a1b2c3d4")
        XCTAssertEqual(SafetyNumber.format(once), once)
    }

    func testFormatEmptyString() {
        XCTAssertEqual(SafetyNumber.format(""), "")
    }

    // MARK: compute

    func testComputeIsSymmetric() {
        // Both parties must derive the identical number, whoever computes it.
        let ab = SafetyNumber.compute(identityKeyA: keyA, identityKeyB: keyB)
        let ba = SafetyNumber.compute(identityKeyA: keyB, identityKeyB: keyA)
        XCTAssertEqual(ab, ba)
    }

    func testComputeIsDeterministic() {
        XCTAssertEqual(SafetyNumber.compute(identityKeyA: keyA, identityKeyB: keyB),
                       SafetyNumber.compute(identityKeyA: keyA, identityKeyB: keyB))
    }

    func testComputeChangesWhenAKeyChanges() {
        var tampered = keyB
        tampered[5] ^= 0xFF
        XCTAssertNotEqual(SafetyNumber.compute(identityKeyA: keyA, identityKeyB: keyB),
                          SafetyNumber.compute(identityKeyA: keyA, identityKeyB: tampered))
    }

    func testComputeProducesSixtyHexCharsInGroupsOfFour() {
        let number = SafetyNumber.compute(identityKeyA: keyA, identityKeyB: keyB)
        let groups = number.split(separator: " ")
        XCTAssertEqual(groups.count, 15) // 60 chars / 4 per group
        for group in groups {
            XCTAssertEqual(group.count, 4)
            XCTAssertTrue(group.allSatisfy { $0.isHexDigit }, "non-hex char in \(group)")
        }
    }

    // MARK: fingerprint

    func testFingerprintIsDeterministicAndFormatted() {
        let first = SafetyNumber.fingerprint(identityKey: keyA)
        let second = SafetyNumber.fingerprint(identityKey: keyA)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.split(separator: " ").count, 15)
        XCTAssertNotEqual(first, SafetyNumber.fingerprint(identityKey: keyB))
    }

    // MARK: matches

    func testMatchesIgnoresCaseAndSpacing() {
        XCTAssertTrue(SafetyNumber.matches("A1B2 C3D4", "a1b2c3d4"))
        XCTAssertFalse(SafetyNumber.matches("A1B2 C3D5", "A1B2 C3D4"))
    }

    // MARK: canonical cross-platform vectors
    //
    // These EXACT strings are pinned identically in the Android
    // (SafetyNumberTest.kt) and Web (crypto.test.ts) suites. If any platform's
    // construction drifts — prefix, ordering, truncation, or hex case — one of
    // the three suites goes red. Keys are the raw 32-byte published wire form
    // (no 0x05 tag), matching production.

    private let vectorKeyA = Data((0..<32).map { UInt8(1 + $0) })     // 0x01..0x20
    private let vectorKeyB = Data((0..<32).map { UInt8(255 - $0) })   // 0xFF..0xE0

    func testComputeMatchesCanonicalVector() {
        XCTAssertEqual(
            SafetyNumber.compute(identityKeyA: vectorKeyA, identityKeyB: vectorKeyB),
            "005C 0F07 1A4A BF49 3872 21C2 7A0C 8F44 A791 A7A6 DCD2 535C 7815 0963 79A4"
        )
        // order-independent — same number whoever computes it
        XCTAssertEqual(
            SafetyNumber.compute(identityKeyA: vectorKeyB, identityKeyB: vectorKeyA),
            "005C 0F07 1A4A BF49 3872 21C2 7A0C 8F44 A791 A7A6 DCD2 535C 7815 0963 79A4"
        )
    }

    func testFingerprintMatchesCanonicalVector() {
        XCTAssertEqual(
            SafetyNumber.fingerprint(identityKey: vectorKeyA),
            "B7BA C2F0 B9B6 550A 5383 387F 4252 561F BDD2 B4C7 D750 9D3D 7ADC 5AA2 B92E"
        )
    }
}
