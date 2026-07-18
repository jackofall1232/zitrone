// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.SafetyNumber
import com.zitrone.app.net.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {

    private val keyA = ByteArray(33) { (it + 1).toByte() }
    private val keyB = ByteArray(33) { (200 - it).toByte() }

    @Test
    fun `both parties derive the identical safety number`() {
        assertEquals(SafetyNumber.compute(keyA, keyB), SafetyNumber.compute(keyB, keyA))
    }

    @Test
    fun `different key pairs produce different numbers`() {
        val keyC = ByteArray(33) { (it * 3 + 7).toByte() }
        assertNotEquals(SafetyNumber.compute(keyA, keyB), SafetyNumber.compute(keyA, keyC))
    }

    @Test
    fun `fingerprint renders as groups of four hex chars`() {
        val fingerprint = SafetyNumber.compute(keyA, keyB)
        val groups = fingerprint.split(" ")
        assertEquals(15, groups.size) // 30 bytes -> 60 hex chars -> 15 groups
        groups.forEach { group ->
            assertTrue("group '$group' malformed", group.matches(Regex("[0-9A-F]{4}")))
        }
    }

    // Canonical cross-platform vectors — pinned identically in the iOS
    // (SafetyNumberTests.swift) and Web (crypto.test.ts) suites. If any
    // platform's construction drifts (prefix, ordering, truncation, hex case),
    // one of the three suites goes red. Keys are the raw 32-byte published wire
    // form, matching production.
    private val vectorKeyA = ByteArray(32) { (it + 1).toByte() }   // 0x01..0x20
    private val vectorKeyB = ByteArray(32) { (255 - it).toByte() } // 0xFF..0xE0

    @Test
    fun `safety number matches the canonical cross-platform vector`() {
        val expected = "005C 0F07 1A4A BF49 3872 21C2 7A0C 8F44 A791 A7A6 DCD2 535C 7815 0963 79A4"
        assertEquals(expected, SafetyNumber.compute(vectorKeyA, vectorKeyB))
        assertEquals(expected, SafetyNumber.compute(vectorKeyB, vectorKeyA))
    }

    @Test
    fun `single-key fingerprint matches the canonical vector`() {
        assertEquals(
            "B7BA C2F0 B9B6 550A 5383 387F 4252 561F BDD2 B4C7 D750 9D3D 7ADC 5AA2 B92E",
            SafetyNumber.fingerprintOf(vectorKeyA),
        )
    }

    @Test
    fun `formatting splits bytes deterministically`() {
        val formatted = SafetyNumber.formatFingerprint(byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D))
        assertEquals("0A1B 2C3D", formatted)
    }

    @Test
    fun `login challenge format matches the server contract`() {
        assertEquals(
            "sublemonable-login:9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d:1760000000",
            ApiClient.loginChallenge("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", 1_760_000_000L),
        )
    }
}
