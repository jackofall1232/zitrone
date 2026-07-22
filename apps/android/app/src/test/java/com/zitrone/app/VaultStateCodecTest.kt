// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.PAYLOAD_PLAINTEXT_BYTES
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.data.AuthState
import com.zitrone.app.data.VaultScopedSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * [VaultStateCodec] round-trip + robustness tests. The codec is content-agnostic, so these
 * exercise it directly (no session / store): every record type the vault holds, both nullable
 * sections present and absent, deterministic bytes, the strict-v1 reject paths (unknown tag /
 * garbage / truncated), the capacity boundary, and the zip-bomb guard.
 */
class VaultStateCodecTest {

    private val ops = LibsodiumVaultOps(SodiumJava())

    /** A state exercising every record family + both nullable blobs + non-default settings/auth. */
    private fun populatedState(): VaultState = VaultState(
        signalRecords = linkedMapOf(
            "identity_keypair" to ByteArray(33) { it.toByte() },
            "registration_id" to byteArrayOf(0, 0, 0x2a, 0x10),
            "remote_identity:alice-account:1" to ByteArray(33) { (it * 3).toByte() },
            "prekey:5" to ByteArray(48) { 0x11 },
            "signed_prekey:2" to ByteArray(120) { 0x22 },
            "session:bob-account:1" to ByteArray(300) { (it and 0x7f).toByte() },
            "kyber_prekey:7" to ByteArray(1600) { 0x44 },
            "kyber_prekey_used:7" to byteArrayOf(1),
            "sender_key:carol-account:1:11111111-2222-3333-4444-555555555555" to ByteArray(64) { 0x55 },
            "next_prekey_id" to byteArrayOf(0, 0, 0, 42),
            "next_signed_prekey_id" to byteArrayOf(0, 0, 0, 3),
            "signed_prekey_created_at" to byteArrayOf(0, 0, 1, 0x77.toByte(), 0x35, 0x40.toByte(), 0x12, 0x34),
        ),
        rosterJson = """[{"id":"alice-account","name":"Alice"}]""",
        tombstonesJson = """{"bob-account":1737504000000}""",
        settings = VaultScopedSettings(
            defaultTtlSeconds = 3600,
            burnOnReadDefault = true,
            readReceipts = false,
            lemonDropComposeEnabled = true,
            unreadReminderEnabled = false,
        ),
        auth = AuthState(accountId = "acct-xyz", accessToken = "jwt.aaa.bbb", refreshToken = "refresh-ccc"),
    )

    private fun assertStateEquals(expected: VaultState, actual: VaultState) {
        assertEquals("record key set", expected.signalRecords.keys, actual.signalRecords.keys)
        for (key in expected.signalRecords.keys) {
            assertArrayEquals("record $key", expected.signalRecords[key], actual.signalRecords[key])
        }
        assertEquals("rosterJson", expected.rosterJson, actual.rosterJson)
        assertEquals("tombstonesJson", expected.tombstonesJson, actual.tombstonesJson)
        assertEquals("settings", expected.settings, actual.settings)
        assertEquals("auth", expected.auth, actual.auth)
    }

    // ── round-trip ────────────────────────────────────────────────────────────────

    @Test
    fun `empty state round-trips`() {
        val decoded = VaultStateCodec.decode(VaultStateCodec.encode(VaultState.empty()))
        assertTrue("no records", decoded.signalRecords.isEmpty())
        assertNull(decoded.rosterJson)
        assertNull(decoded.tombstonesJson)
        assertEquals(VaultScopedSettings(), decoded.settings)
        assertEquals(AuthState(), decoded.auth)
    }

    @Test
    fun `fully populated state round-trips every record type and field`() {
        val state = populatedState()
        val decoded = VaultStateCodec.decode(VaultStateCodec.encode(state))
        assertStateEquals(state, decoded)
    }

    @Test
    fun `null nullable sections round-trip as null - null auth fields survive`() {
        val state = VaultState(
            signalRecords = linkedMapOf("prekey:1" to byteArrayOf(1, 2, 3)),
            rosterJson = null,
            tombstonesJson = null,
            settings = VaultScopedSettings(),
            auth = AuthState(accountId = "only-account", accessToken = null, refreshToken = null),
        )
        val decoded = VaultStateCodec.decode(VaultStateCodec.encode(state))
        assertNull(decoded.rosterJson)
        assertNull(decoded.tombstonesJson)
        assertEquals("only-account", decoded.auth.accountId)
        assertNull(decoded.auth.accessToken)
        assertNull(decoded.auth.refreshToken)
    }

    @Test
    fun `settings booleans and null-vs-set ttl round-trip precisely`() {
        // ttl null vs a value, plus each boolean flipped, to pin the fixed 9-byte layout.
        val a = VaultScopedSettings(defaultTtlSeconds = null, burnOnReadDefault = false, readReceipts = true, lemonDropComposeEnabled = false, unreadReminderEnabled = true)
        val b = VaultScopedSettings(defaultTtlSeconds = 0, burnOnReadDefault = true, readReceipts = false, lemonDropComposeEnabled = true, unreadReminderEnabled = false)
        for (settings in listOf(a, b)) {
            val decoded = VaultStateCodec.decode(
                VaultStateCodec.encode(VaultState.empty().also { it.settings = settings }),
            )
            assertEquals(settings, decoded.settings)
        }
    }

    // ── determinism ─────────────────────────────────────────────────────────────

    @Test
    fun `equal state encodes to identical bytes regardless of record insertion order`() {
        val one = VaultState(
            signalRecords = linkedMapOf(
                "session:z:1" to byteArrayOf(3),
                "prekey:1" to byteArrayOf(1),
                "identity_keypair" to byteArrayOf(2),
            ),
            rosterJson = "r", tombstonesJson = "t",
            settings = VaultScopedSettings(), auth = AuthState(),
        )
        val two = VaultState(
            signalRecords = linkedMapOf(
                "identity_keypair" to byteArrayOf(2),
                "prekey:1" to byteArrayOf(1),
                "session:z:1" to byteArrayOf(3),
            ),
            rosterJson = "r", tombstonesJson = "t",
            settings = VaultScopedSettings(), auth = AuthState(),
        )
        assertArrayEquals(
            "sorted-key encoding is order-independent",
            VaultStateCodec.encode(one),
            VaultStateCodec.encode(two),
        )
        // And re-encoding the same state is byte-stable.
        assertArrayEquals(VaultStateCodec.encode(one), VaultStateCodec.encode(one))
    }

    // ── strict-v1 reject paths ───────────────────────────────────────────────────

    @Test
    fun `an unknown section tag is rejected`() {
        // version(1) then a bogus tag 0x09 with a zero-length body.
        val plain = byteArrayOf(1, 0x09, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    @Test
    fun `garbage (non-deflate) input is rejected`() {
        val garbage = ByteArray(64) { 0xFF.toByte() }
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(garbage) }
    }

    @Test
    fun `a truncated valid blob is rejected`() {
        val full = VaultStateCodec.encode(populatedState())
        val truncated = full.copyOf(full.size / 2)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(truncated) }
    }

    @Test
    fun `an unsupported version byte is rejected`() {
        val plain = byteArrayOf(99) // version 99
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    @Test
    fun `a corrupt huge signal record count is rejected cleanly, not with OutOfMemoryError`() {
        // version(1) ‖ signal section (tag 0x01, len 4) whose body is JUST a count of
        // 0x3FFFFFFF and no entries. decode must reject via the Reader's bounds check when
        // it reaches for the (absent) first entry — NOT try to pre-size a ~2-billion-entry
        // HashMap from the raw count and OOM.
        val plain = byteArrayOf(
            1, // version
            0x01, 0, 0, 0, 4, // TAG_SIGNAL, len = 4
            0x3F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // count = 0x3FFFFFFF, no entries
        )
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    // ── mandatory-section rejection (signal / settings / auth always emitted in v1) ──

    @Test
    fun `a payload with only the version byte is rejected as missing mandatory sections`() {
        // Valid deflate + valid version but ZERO sections. v1 always emits signal+settings+auth,
        // so a truncated body carrying none of them is corruption — must throw, not default them.
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(byteArrayOf(1))) }
    }

    @Test
    fun `a payload with a valid signal section but no settings or auth is rejected`() {
        // version(1) ‖ TAG_SIGNAL(0x01) len 4 ‖ count=0 — an empty-but-valid signal section and
        // nothing else. settings/auth are mandatory, so decode must reject rather than default them.
        val plain = byteArrayOf(1, 0x01, 0, 0, 0, 4, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    @Test
    fun `a valid signal section followed by an unknown tag is rejected (decode-failure wipe path)`() {
        // decodeSignal copies a record into the signal map, THEN the unknown tag throws;
        // parsePlaintext must wipe the partial map and rethrow. From here we can only observe the
        // throw (the wiped map is discarded internally) — asserting the throw is the contract.
        val plain = byteArrayOf(
            1, //                                          version
            0x01, 0, 0, 0, 0x15, //                        TAG_SIGNAL, section len = 21
            0, 0, 0, 1, //                                 signal count = 1
            0, 8, //                                       keyLen = 8
            0x70, 0x72, 0x65, 0x6b, 0x65, 0x79, 0x3a, 0x31, // "prekey:1"
            0, 0, 0, 3, //                                 valLen = 3
            1, 2, 3, //                                    value
            0x09, 0, 0, 0, 0, //                           unknown tag 0x09, len 0
        )
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    @Test
    fun `a signal section with a valid record then a truncated second entry is rejected (decodeSignal partial-wipe path)`() {
        // decodeSignal copies the first record into its local map, then the SECOND entry's key
        // overruns the (truncated) section body → decodeSignal itself throws mid-parse, BEFORE it
        // returns, so parsePlaintext never assigns `signal`. decodeSignal's own catch must wipe the
        // partial map and rethrow. From here we can only observe the throw (the wiped map is
        // discarded internally) — asserting the throw is the contract for the partial-wipe path.
        val plain = byteArrayOf(
            1, //                                          version
            0x01, 0, 0, 0, 0x1B, //                        TAG_SIGNAL, section len = 27
            0, 0, 0, 2, //                                 signal count = 2
            // entry 1 (valid): keyLen=8 "prekey:1", valLen=3, [1,2,3]
            0, 8,
            0x70, 0x72, 0x65, 0x6b, 0x65, 0x79, 0x3a, 0x31, // "prekey:1"
            0, 0, 0, 3,
            1, 2, 3,
            // entry 2 (truncated): keyLen=8 but only 4 key bytes follow — key read overruns
            0, 8,
            0x70, 0x72, 0x65, 0x6b, //                     "prek" (section body ends here)
        )
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    // ── capacity boundary ────────────────────────────────────────────────────────

    @Test
    fun `state just under the cap encodes - just over throws VaultCapacityException`() {
        // Incompressible random bytes so the deflated size tracks the raw size.
        val under = VaultState.empty().also {
            it.signalRecords["blob"] = ops.randomBytes(VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES - 50_000)
        }
        val encoded = VaultStateCodec.encode(under) // must NOT throw
        assertTrue("under-cap output fits the region", encoded.size <= VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES)
        assertStateEquals(under, VaultStateCodec.decode(encoded))

        val over = VaultState.empty().also {
            it.signalRecords["blob"] = ops.randomBytes(VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES + 5_000)
        }
        assertThrows(VaultCapacityException::class.java) { VaultStateCodec.encode(over) }
    }

    // ── zip-bomb guard ───────────────────────────────────────────────────────────

    @Test
    fun `decode refuses a blob that inflates past the cap`() {
        // A highly compressible payload far larger than PAYLOAD_PLAINTEXT_BYTES * 8: tiny on
        // the wire, but inflating it must hit the cap and throw rather than allocate unbounded.
        val bomb = deflate(ByteArray(PAYLOAD_PLAINTEXT_BYTES * 8 + 1_000)) // all zeros
        assertTrue("the bomb is small on the wire", bomb.size < 4_096)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(bomb) }
    }

    /** Zlib-format DEFLATE matching the codec's Inflater — for crafting malformed inputs. */
    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }
}
