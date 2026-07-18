// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import java.security.MessageDigest

/**
 * Safety Number computation for contact verification
 * (features.contact_verification): a SHA-512 fingerprint over BOTH identity
 * keys, ordered canonically so both parties derive the identical number.
 *
 * Pure JVM logic — unit-testable without Android.
 */
object SafetyNumber {

    /** Number of digest bytes rendered (60 hex chars -> 15 groups of 4). */
    private const val DISPLAY_BYTES = 30

    /**
     * Domain-separation constants. CLIENT-SIDE visual-verification values only
     * (never sent to or verified by the relay), so unlike the login challenge
     * they are NOT server-cutover gated. They MUST stay byte-identical across
     * Android/iOS/Web (`SafetyNumber.swift`, `packages/crypto/src/keys.ts`) or
     * the same key pair yields different numbers per platform. The `-v1` suffix
     * is the migration lever.
     */
    const val SAFETY_NUMBER_DOMAIN = "zitrone-safety-number-v1"
    const val FINGERPRINT_DOMAIN = "zitrone-key-fingerprint-v1"

    /**
     * Computes the shared safety number for two serialized public identity
     * keys. Key order is canonicalized (lexicographic byte order) so
     * `compute(a, b) == compute(b, a)`.
     *
     * @return uppercase hex, grouped in 4s separated by single spaces —
     *         the key_fingerprint display format. Render in JetBrains Mono.
     */
    fun compute(identityKeyA: ByteArray, identityKeyB: ByteArray): String {
        val (first, second) = if (lexicographicCompare(identityKeyA, identityKeyB) <= 0) {
            identityKeyA to identityKeyB
        } else {
            identityKeyB to identityKeyA
        }
        val digest = MessageDigest.getInstance("SHA-512").apply {
            update(SAFETY_NUMBER_DOMAIN.toByteArray(Charsets.UTF_8))
            update(first)
            update(second)
        }.digest()
        return formatFingerprint(digest.copyOf(DISPLAY_BYTES))
    }

    /**
     * SHA-512 fingerprint of a single identity key (settings display). Uses a
     * DISTINCT domain constant from [compute] so a single-key fingerprint can
     * never coincide with a two-key safety number.
     */
    fun fingerprintOf(identityKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-512").apply {
            update(FINGERPRINT_DOMAIN.toByteArray(Charsets.UTF_8))
            update(identityKey)
        }.digest()
        return formatFingerprint(digest.copyOf(DISPLAY_BYTES))
    }

    /** Groups of 4 hex chars separated by spaces. */
    fun formatFingerprint(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")

    internal fun lexicographicCompare(a: ByteArray, b: ByteArray): Int {
        val min = minOf(a.size, b.size)
        for (i in 0 until min) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }
}
