// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.data.AuthState
import com.zitrone.app.data.VaultScopedSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.Deflater

/**
 * `TAG_DECOY` (0x06) — the cover-traffic section of the vault keystore.
 *
 * Covers the four things the U1 invariant table says this section must guarantee:
 * round-trip fidelity for every field, **absence as the valid initial state** (the section is
 * omitted entirely when there is nothing to record, which is what keeps a vault that never
 * sets up cover traffic readable by an older build), the **wipe obligation** for the identity
 * PRIVATE key the section now carries, and a **measured byte budget** — `capacityExceeded`
 * fail-closes `flushBeforeAck`, so overflowing the fixed region is a durability bug.
 *
 * The compression + TLV byte path is entirely real; only the malformed inputs are hand-crafted.
 */
class VaultDecoySectionTest {

    private val random = Random(20260727L)

    private fun baseState(decoy: DecoyState? = null): VaultState = VaultState(
        signalRecords = linkedMapOf(
            "identity_keypair" to ByteArray(68) { it.toByte() },
            "session:bob-account:1" to ByteArray(300) { (it and 0x7f).toByte() },
        ),
        rosterJson = """[{"id":"alice-account","name":"Alice"}]""",
        tombstonesJson = null,
        settings = VaultScopedSettings(defaultTtlSeconds = 3600, burnOnReadDefault = true),
        auth = AuthState(accountId = "acct-xyz", accessToken = "jwt.aaa.bbb", refreshToken = "refresh-ccc"),
        decoy = decoy,
    )

    /** A fully-populated section: every field non-default, realistic sizes. */
    private fun fullDecoy(): DecoyState = DecoyState(
        accountId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
        identityKeyPair = IdentityKeyPair.generate().serialize(),
        accessToken = fakeAccessJwt(),
        refreshToken = base64Url(32),
        counterHighWater = 4_096L,
        deadAirNextFireAtMs = 1_795_000_000_000L,
        provisionNotBeforeMs = 1_796_000_000_000L,
    )

    // ── round-trip ────────────────────────────────────────────────────────────────

    @Test
    fun `a fully populated decoy section round-trips every field`() {
        val decoy = fullDecoy()
        val decoded = VaultStateCodec.decode(VaultStateCodec.encode(baseState(decoy)))

        val actual = requireNotNull(decoded.decoy) { "the decoy section survived the round trip" }
        assertEquals("accountId", decoy.accountId, actual.accountId)
        assertArrayEquals("identityKeyPair", decoy.identityKeyPair, actual.identityKeyPair)
        assertEquals("accessToken", decoy.accessToken, actual.accessToken)
        assertEquals("refreshToken", decoy.refreshToken, actual.refreshToken)
        assertEquals("counterHighWater", decoy.counterHighWater, actual.counterHighWater)
        assertEquals("deadAirNextFireAtMs", decoy.deadAirNextFireAtMs, actual.deadAirNextFireAtMs)
        assertEquals("provisionNotBeforeMs", decoy.provisionNotBeforeMs, actual.provisionNotBeforeMs)
        assertEquals("whole-section equality", decoy, actual)
    }

    @Test
    fun `a partially populated section round-trips - a deferral with no credentials is NOT provisioned`() {
        // The exact state a 429 leaves behind: the section exists, and it carries no account.
        val deferred = DecoyState(provisionNotBeforeMs = 1_795_000_123_456L)
        val decoded = VaultStateCodec.decode(VaultStateCodec.encode(baseState(deferred)))

        val actual = requireNotNull(decoded.decoy)
        assertEquals(deferred.provisionNotBeforeMs, actual.provisionNotBeforeMs)
        assertNull("no account id", actual.accountId)
        assertNull("no identity keypair", actual.identityKeyPair)
        assertEquals("counter mark defaults to zero", 0L, actual.counterHighWater)
        // The row this pins: PRESENCE IS NOT READINESS. A reader keying on "section exists" would
        // conclude this vault has a usable synthetic account. It does not.
        assertFalse("a deferral-only section is not provisioned", actual.isProvisioned)
    }

    @Test
    fun `a counter-only section round-trips - zero and large marks are distinguishable`() {
        val zero = VaultStateCodec.decode(VaultStateCodec.encode(baseState(DecoyState(counterHighWater = 0L))))
        // counterHighWater == 0 with nothing else set IS the empty holder, so it is omitted.
        assertNull("an all-default holder is not persisted at all", zero.decoy)

        val large = DecoyState(counterHighWater = Long.MAX_VALUE - 64L)
        val decoded = VaultStateCodec.decode(VaultStateCodec.encode(baseState(large)))
        assertEquals(Long.MAX_VALUE - 64L, requireNotNull(decoded.decoy).counterHighWater)
    }

    @Test
    fun `every other section is unaffected by the presence of a decoy section`() {
        val plain = baseState()
        val withDecoy = baseState(fullDecoy())

        val a = VaultStateCodec.decode(VaultStateCodec.encode(plain))
        val b = VaultStateCodec.decode(VaultStateCodec.encode(withDecoy))

        assertEquals("rosterJson", a.rosterJson, b.rosterJson)
        assertEquals("settings", a.settings, b.settings)
        assertEquals("auth", a.auth, b.auth)
        assertEquals("record key set", a.signalRecords.keys, b.signalRecords.keys)
        for (key in a.signalRecords.keys) {
            assertArrayEquals("record $key", a.signalRecords[key], b.signalRecords[key])
        }
    }

    @Test
    fun `encoding stays deterministic with a decoy section present`() {
        val decoy = fullDecoy()
        assertArrayEquals(
            "equal state encodes to identical bytes",
            VaultStateCodec.encode(baseState(decoy)),
            VaultStateCodec.encode(baseState(decoy)),
        )
    }

    // ── absence is the valid initial state ────────────────────────────────────────

    @Test
    fun `a null decoy round-trips as null`() {
        assertNull(VaultStateCodec.decode(VaultStateCodec.encode(baseState(null))).decoy)
        assertNull("VaultState.empty() carries no decoy section", VaultState.empty().decoy)
    }

    @Test
    fun `an all-default decoy holder emits NO section - byte-identical to no holder at all`() {
        // Load-bearing, not tidiness: while tag 0x06 is absent the payload is still decodable by a
        // 0.9.x build, so a vault that never generates cover traffic never pays for the format
        // break. A holder that got materialised and then emptied must not leave the tag behind.
        val withEmptyHolder = VaultStateCodec.encode(baseState(DecoyState()))
        val withNoHolder = VaultStateCodec.encode(baseState(null))
        assertArrayEquals("an empty holder is omitted entirely", withNoHolder, withEmptyHolder)
        assertNull(VaultStateCodec.decode(withEmptyHolder).decoy)

        // Discriminator: a NON-empty holder must NOT encode to the same bytes, or the assertion
        // above would also pass against a codec that never emits the section at all.
        assertNotEquals(
            "a populated holder is genuinely emitted",
            withNoHolder.size,
            VaultStateCodec.encode(baseState(fullDecoy())).size,
        )
    }

    // ── strict v1 is unchanged ────────────────────────────────────────────────────

    @Test
    fun `an unknown tag ABOVE the decoy tag is still rejected - no forward tolerance was added`() {
        // The 0.10.0 format break was ruled as a one-way bump, explicitly NOT as a loosening of
        // the strict-v1 unknown-tag rule. 0x07 must still be corruption.
        val plain = byteArrayOf(1, 0x07, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(plain)) }
    }

    /**
     * These three start from a REAL, fully valid encode and change exactly one thing about the
     * decoy section, so the rejection they assert cannot be satisfied by some other defect in a
     * hand-built payload (every malformed input throws the same exception type, so a fixture with
     * two defects proves nothing about either).
     */
    @Test
    fun `a duplicate decoy tag is rejected`() {
        val plain = realPlaintextWithDecoy()
        assertTrue("the baseline is genuinely valid", VaultStateCodec.decode(deflate(plain)).decoy != null)

        val duplicated = plain + byteArrayOf(0x06, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(duplicated)) }
    }

    @Test
    fun `a decoy section with trailing bytes is rejected`() {
        val plain = realPlaintextWithDecoy()
        val (tagIndex, len) = locateDecoySection(plain)

        // Grow the section by one byte the parser has no field for.
        val grown = plain.copyOf(plain.size + 1)
        writeSectionLength(grown, tagIndex, len + 1)
        grown[grown.size - 1] = 0x77
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(grown)) }
    }

    @Test
    fun `a truncated decoy section is rejected`() {
        val plain = realPlaintextWithDecoy()
        val (tagIndex, len) = locateDecoySection(plain)

        // Drop the section's last byte and its declared length with it: the payload stays
        // structurally consistent, so the ONLY defect is that the decoy fields run short.
        val shortened = plain.copyOf(plain.size - 1)
        writeSectionLength(shortened, tagIndex, len - 1)
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(shortened)) }
    }

    // ── the wipe obligation ───────────────────────────────────────────────────────

    @Test
    fun `VaultState wipe ZEROES the decoy identity private key and drops the holder`() {
        // The section carries raw private key material — the class of secret wipe() must ZERO, not
        // merely dereference (the un-wipeable-String tradeoff covers the tokens, not this).
        val identity = IdentityKeyPair.generate().serialize()
        assertTrue("the fixture really holds key bytes", identity.any { it != 0.toByte() })

        val state = baseState(DecoyState(accountId = "acct", identityKeyPair = identity))
        state.wipe()

        assertArrayEquals("identity keypair zeroed in place", ByteArray(identity.size), identity)
        assertNull("holder dropped", state.decoy)
    }

    @Test
    fun `a decode that fails AFTER the decoy section is REJECTED`() {
        val plain = realPlaintextWithDecoy()
        assertTrue("the baseline is genuinely valid", VaultStateCodec.decode(deflate(plain)).decoy != null)

        val withUnknownTail = plain + byteArrayOf(0x09, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            VaultStateCodec.decode(deflate(withUnknownTail))
        }
    }

    @Test
    fun `the REAL decoder path zeroes the decoy identity key when a later section throws`() {
        // This pins the PRODUCTION cleanup call, not a hand-rolled twin of it. The round-1 pair of
        // tests could not: one asserted only that a malformed payload throws, the other invoked the
        // cleanup helper directly on arrays the test owned — so deleting the call from
        // parsePlaintext's catch left both green while a decoded private key stayed in the heap.
        //
        // The decoder now accumulates what it has decoded into a caller-supplied PartialDecode, so
        // the material a failing parse strands is reachable from here and the zeroing can be
        // observed through the real decode path itself.
        val plain = realPlaintextWithDecoy()
        assertTrue("the baseline is genuinely valid", VaultStateCodec.decode(deflate(plain)).decoy != null)

        val partial = VaultStateCodec.PartialDecode()
        assertThrows(IllegalArgumentException::class.java) {
            // Fails on the unknown tag AFTER both the signal records and the decoy section decoded.
            VaultStateCodec.parsePlaintext(plain + byteArrayOf(0x09, 0, 0, 0, 0), partial)
        }

        val stranded = requireNotNull(partial.decoy) { "the decoy section really was decoded first" }
        val key = requireNotNull(stranded.identityKeyPair) { "…and it really carried a private key" }
        assertTrue("the fixture key is a real one, so zeroing it is observable", key.size >= 64)
        assertArrayEquals("the identity private key the decoder copied out was zeroed", ByteArray(key.size), key)
        assertTrue(
            "the partially decoded signal records were zeroed and dropped too",
            requireNotNull(partial.signal).isEmpty(),
        )
    }

    @Test
    fun `a SUCCESSFUL decode does not wipe what it hands back`() {
        // The mirror of the case above, and the reason the cleanup lives in the catch and not in a
        // finally: on success the very same map and holder become the returned VaultState's, so a
        // wipe there would zero the live keystore the caller is about to use.
        val plain = realPlaintextWithDecoy()
        val decoded = VaultStateCodec.parsePlaintext(plain, VaultStateCodec.PartialDecode())
        val key = requireNotNull(decoded.decoy?.identityKeyPair)
        assertTrue("the decoded identity key is intact", key.any { it != 0.toByte() })
        assertTrue("and so are the signal records", decoded.signalRecords.values.any { r -> r.any { it != 0.toByte() } })
    }

    @Test
    fun `the decode-failure cleanup tolerates a decode that got nowhere`() {
        // The catch runs for a payload that failed before either section was reached.
        VaultStateCodec.PartialDecode().wipe()
    }

    @Test
    fun `a throw on the very FIRST byte still wipes what the accumulator already held`() {
        // The version check used to sit OUTSIDE the try, so a payload that failed on its header
        // skipped partial.wipe() entirely. The seam's whole contract is "a throw from here zeroes
        // what this accumulator holds", and the header is part of "here": a caller that hands in an
        // accumulator carrying decoded key material (the shape this seam exists to make possible)
        // and gets a wrong-version payload back would have that material stranded un-zeroed.
        val key = ByteArray(68) { (it + 1).toByte() }
        val record = ByteArray(32) { (it + 9).toByte() }
        val partial = VaultStateCodec.PartialDecode()
        partial.decoy = DecoyState(identityKeyPair = key)
        partial.signal = mutableMapOf("session" to record)

        assertThrows(IllegalArgumentException::class.java) {
            // Version 0x09 — rejected by the first `require`, before any section is read.
            VaultStateCodec.parsePlaintext(byteArrayOf(0x09), partial)
        }

        assertArrayEquals("the identity private key was zeroed", ByteArray(key.size), key)
        assertArrayEquals("and so was the signal record", ByteArray(record.size), record)
    }

    // ── strict v1 is CANONICAL, not merely parseable ──────────────────────────────

    @Test
    fun `a noncanonical nullable-long presence flag is rejected`() {
        // Any nonzero byte used to be truthy, so 0x02 and 0x01 decoded to the same state — a second
        // spelling of one state that decode→encode silently rewrites, which is exactly what a
        // determinism claim cannot cover.
        val plain = realPlaintextWithDecoy()
        assertTrue("the baseline is genuinely valid", VaultStateCodec.decode(deflate(plain)).decoy != null)

        val tampered = plain.copyOf()
        tampered[tampered.size - DEAD_AIR_PRESENCE_FROM_END] = 0x02
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(tampered)) }
    }

    @Test
    fun `an ABSENT nullable long carrying a value is rejected`() {
        // present=0 used to ignore the eight bytes behind it, so arbitrary content could ride along
        // inside a section that round-trips as "absent".
        val plain = realPlaintextWithDecoy()
        val tampered = plain.copyOf()
        // fullDecoy()'s deadAirNextFireAtMs is a real timestamp, so clearing ONLY the presence flag
        // leaves a nonzero value behind it — the exact noncanonical shape.
        tampered[tampered.size - DEAD_AIR_PRESENCE_FROM_END] = 0x00
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(tampered)) }

        // Discriminator: zeroing the value too makes it the CANONICAL absent form, which must decode.
        val canonical = plain.copyOf()
        canonical[canonical.size - DEAD_AIR_PRESENCE_FROM_END] = 0x00
        for (i in 1..8) canonical[canonical.size - DEAD_AIR_PRESENCE_FROM_END + i] = 0x00
        assertNull(
            "the canonical absent form decodes as absent",
            VaultStateCodec.decode(deflate(canonical)).decoy?.deadAirNextFireAtMs,
        )
    }

    @Test
    fun `a NEGATIVE counter high-water mark is rejected`() {
        // The mark means "every value strictly below this may already have been issued", and the
        // allocator issues upward from it. A negative mark hands out negative message_numbers —
        // a value no real ratchet produces, i.e. the free classifier the counter discipline exists
        // to deny the relay. It is unreachable from the encoder, so it can only be crafted.
        val plain = realPlaintextWithDecoy()
        val tampered = plain.copyOf()
        tampered[tampered.size - COUNTER_FROM_END] = 0xFF.toByte()
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(tampered)) }
    }

    @Test
    fun `the ENCODER refuses a negative counter mark too - strict v1 is symmetric`() {
        // The decoder rejected it and the encoder emitted it happily, so this codec could write an
        // image its own reader calls corrupt: the vault seals, and the next unlock refuses it as a
        // damaged state. Strict v1 must refuse to PRODUCE what it refuses to READ — and because no
        // writer in this codebase can reach a negative mark, the only honest form is an assertion,
        // not a clamp that would silently rewrite a caller's state.
        assertThrows(IllegalArgumentException::class.java) {
            VaultStateCodec.encode(baseState(DecoyState(counterHighWater = -1L)))
        }
        // Discriminator: a positive mark still encodes, so this is not a blanket refusal.
        val ok = VaultStateCodec.decode(VaultStateCodec.encode(baseState(DecoyState(counterHighWater = 7L))))
        assertEquals("a positive mark still round-trips", 7L, ok.decoy?.counterHighWater)
    }

    // ── the register-before-commit invariant, enforced by the FORMAT ──────────────

    @Test
    fun `the ENCODER refuses a credential half-set - an id without its key, and a key without its id`() {
        // [R4] The unit's central invariant is that an account id and its identity keypair are
        // committed together or not at all: a vault referencing an account whose signing key was
        // never persisted is unauthenticatable, undeletable, and breaks every subsequent decoy send.
        // Every writer honoured that — and the codec happily encoded the forbidden state anyway, so
        // "structurally impossible" rested entirely on writers staying careful. isProvisioned then
        // *hid* the malformed state by answering false, which is concealment, not prevention.
        val key = IdentityKeyPair.generate().serialize()
        assertThrows(IllegalArgumentException::class.java) {
            VaultStateCodec.encode(baseState(DecoyState(accountId = "acct", identityKeyPair = null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultStateCodec.encode(baseState(DecoyState(accountId = null, identityKeyPair = key)))
        }
        // Tokens belong TO an account: a token-only section is live bearer credentials for an
        // account this vault does not claim. DecoyAuthStore fails closed on this in both setters;
        // the format has to say it too, or a crafted image can still assert it.
        assertThrows(IllegalArgumentException::class.java) {
            VaultStateCodec.encode(baseState(DecoyState(accessToken = "jwt.a.b", refreshToken = "r")))
        }
        // Discriminators, so this is not a blanket refusal of anything partial. The paired set
        // encodes, and so does the deferral-only section a spent-nothing attempt leaves behind —
        // which is the shape the whole write-ahead back-off depends on being encodable.
        val paired = VaultStateCodec.decode(
            VaultStateCodec.encode(baseState(DecoyState(accountId = "acct", identityKeyPair = key))),
        )
        assertTrue("a paired credential set still encodes", paired.decoy?.isProvisioned == true)
        val deferralOnly = VaultStateCodec.decode(
            VaultStateCodec.encode(baseState(DecoyState(provisionNotBeforeMs = 1_795_000_123_456L))),
        )
        assertEquals(
            "a deferral-only section still encodes",
            1_795_000_123_456L,
            deferralOnly.decoy?.provisionNotBeforeMs,
        )
    }

    @Test
    fun `the DECODER refuses a credential half-set too - strict v1 is symmetric`() {
        // The encoder can no longer produce these, so they can only arrive crafted or corrupt — and
        // a decoder that accepts what its encoder refuses hands the running app exactly the dangling
        // reference the ordering rule exists to rule out, sourced from disk instead of from a
        // writer. Bodies are hand-built rather than tampered field-by-field because the malformed
        // shapes change the section's length.
        val key = IdentityKeyPair.generate().serialize()
        val idOnly = spliceDecoySection(decoyBody(accountId = "acct", identityKeyPair = null))
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(idOnly)) }

        val keyOnly = spliceDecoySection(decoyBody(accountId = null, identityKeyPair = key))
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(keyOnly)) }

        val tokenOnly = spliceDecoySection(
            decoyBody(accountId = null, identityKeyPair = null, accessToken = "jwt.a.b"),
        )
        assertThrows(IllegalArgumentException::class.java) { VaultStateCodec.decode(deflate(tokenOnly)) }

        // Discriminator: the SAME hand-built framing with a paired set decodes, so these failures
        // are the pairing rule and not a broken body builder.
        val paired = spliceDecoySection(decoyBody(accountId = "acct", identityKeyPair = key))
        val decoded = VaultStateCodec.decode(deflate(paired))
        assertEquals("acct", decoded.decoy?.accountId)
        assertArrayEquals("the hand-built paired body decodes", key, decoded.decoy?.identityKeyPair)
    }

    // ── the measured byte budget ──────────────────────────────────────────────────

    @Test
    fun `the decoy section costs less than its declared budget at a realistic maximum`() {
        // NOT an adversarial maximum, and the name no longer claims one: the JWT shape is fixed by
        // the relay (`server/internal/auth/jwt.go` IssueAccessToken) and the refresh token is 32
        // random bytes, so the only field an attacker could stretch is server-issued. What this
        // measures is the largest section the RELAY can produce: a 36-char account UUID, a real
        // serialized libsignal identity keypair, a full-length RS256 access JWT, a 43-char refresh
        // token, and all three integer fields set to a long that costs full width.
        val worstCase = DecoyState(
            accountId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            identityKeyPair = IdentityKeyPair.generate().serialize(),
            accessToken = fakeAccessJwt(),
            refreshToken = base64Url(32),
            counterHighWater = Long.MAX_VALUE / 2,
            deadAirNextFireAtMs = Long.MAX_VALUE / 2,
            provisionNotBeforeMs = Long.MAX_VALUE / 2,
        )
        val without = VaultStateCodec.encode(baseState(null)).size
        val with = VaultStateCodec.encode(baseState(worstCase)).size
        val delta = with - without

        // Discriminator: a codec that silently dropped the section would also satisfy "delta is
        // under budget". It must genuinely cost something.
        assertTrue("the section is actually emitted (delta=$delta)", delta > 0)
        assertTrue(
            "worst-case decoy section delta $delta B exceeds the declared budget " +
                "${VaultStateCodec.DECOY_SECTION_BUDGET_BYTES} B",
            delta <= VaultStateCodec.DECOY_SECTION_BUDGET_BYTES,
        )
        // Headroom against the fixed region: R5 in the invariant table depends on this, because
        // VaultRuntime.capacityExceeded fail-closes flushBeforeAck.
        val remaining = VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES - with
        assertTrue(
            "a realistic state with the section leaves $remaining B of " +
                "${VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES} B free",
            remaining >= VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES / 10 * 9,
        )
        println(
            "MEASURED decoy section: worst-case encoded delta = $delta B " +
                "(budget ${VaultStateCodec.DECOY_SECTION_BUDGET_BYTES} B); " +
                "state with section = $with B of ${VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES} B, " +
                "$remaining B free",
        )
    }

    // ── fixtures + byte helpers ───────────────────────────────────────────────────

    /**
     * An RS256 access JWT of the shape the relay issues: `header.claims.signature`, where the
     * signature is a 256-byte RSA-2048 signature in base64url and the claims carry a UUID subject
     * plus iat/exp/iss (`server/internal/auth/jwt.go` IssueAccessToken).
     */
    private fun fakeAccessJwt(): String =
        base64Url(27) + "." + base64Url(110) + "." + base64Url(256)

    /** [bytes] random bytes as unpadded base64url — the alphabet/entropy real tokens carry. */
    private fun base64Url(bytes: Int): String {
        val raw = ByteArray(bytes).also(random::nextBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    /** The real TLV plaintext of a valid, fully-populated state — the base for every corruption. */
    private fun realPlaintextWithDecoy(): ByteArray =
        inflate(VaultStateCodec.encode(baseState(fullDecoy())))

    /**
     * A hand-built decoy section body in the codec's field order:
     * `accountId ‖ identityKeyPair ‖ accessToken ‖ refreshToken` (nullable, `len(4 BE)` with -1 for
     * null) `‖ counterHighWater(8 BE) ‖ deadAir(present ‖ 8) ‖ provisionNotBefore(present ‖ 8)`.
     *
     * Needed because the shapes the pairing rule forbids can no longer be produced by the encoder,
     * and they are not reachable by flipping bytes in a valid body either — dropping a field changes
     * the section's length.
     */
    private fun decoyBody(
        accountId: String?,
        identityKeyPair: ByteArray?,
        accessToken: String? = null,
        refreshToken: String? = null,
        counterHighWater: Long = 0L,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) = out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        fun i64(v: Long) = (7 downTo 0).forEach { out.write(((v ushr (it * 8)) and 0xff).toInt()) }
        fun blob(bytes: ByteArray?) {
            if (bytes == null) i32(-1) else { i32(bytes.size); out.write(bytes) }
        }
        blob(accountId?.toByteArray(Charsets.UTF_8))
        blob(identityKeyPair)
        blob(accessToken?.toByteArray(Charsets.UTF_8))
        blob(refreshToken?.toByteArray(Charsets.UTF_8))
        i64(counterHighWater)
        out.write(0); i64(0L) // deadAirNextFireAtMs absent, canonical
        out.write(0); i64(0L) // provisionNotBeforeMs absent, canonical
        return out.toByteArray()
    }

    /** A valid plaintext with its decoy section replaced by [body], re-framed to the new length. */
    private fun spliceDecoySection(body: ByteArray): ByteArray {
        val plain = realPlaintextWithDecoy()
        val (tagIndex, _) = locateDecoySection(plain)
        val out = plain.copyOf(tagIndex + 5 + body.size)
        writeSectionLength(out, tagIndex, body.size)
        body.copyInto(out, tagIndex + 5)
        return out
    }

    private companion object {
        /**
         * The decoy section is emitted LAST and ends the plaintext, and its tail is
         * `counterHighWater(8) ‖ deadAir(present(1) ‖ 8) ‖ provisionNotBefore(present(1) ‖ 8)` —
         * 26 bytes. These are offsets BACK from the end of the plaintext, so a hand-edit lands on
         * exactly one field without needing to re-frame the section.
         */
        const val DEAD_AIR_PRESENCE_FROM_END = 18
        const val COUNTER_FROM_END = 26
    }

    /**
     * Find the decoy section in a TLV plaintext: it is emitted LAST, so its tag is the byte whose
     * declared length reaches exactly the end of the plaintext. Returns `(tagIndex, bodyLength)`.
     */
    private fun locateDecoySection(plain: ByteArray): Pair<Int, Int> {
        for (i in plain.indices.reversed()) {
            if (plain[i] != 0x06.toByte() || i + 5 > plain.size) continue
            val len = ((plain[i + 1].toInt() and 0xff) shl 24) or
                ((plain[i + 2].toInt() and 0xff) shl 16) or
                ((plain[i + 3].toInt() and 0xff) shl 8) or
                (plain[i + 4].toInt() and 0xff)
            if (len > 0 && i + 5 + len == plain.size) return i to len
        }
        throw AssertionError("no decoy section found in the plaintext")
    }

    private fun writeSectionLength(plain: ByteArray, tagIndex: Int, length: Int) {
        plain[tagIndex + 1] = ((length ushr 24) and 0xff).toByte()
        plain[tagIndex + 2] = ((length ushr 16) and 0xff).toByte()
        plain[tagIndex + 3] = ((length ushr 8) and 0xff).toByte()
        plain[tagIndex + 4] = (length and 0xff).toByte()
    }

    /** Inflate a codec output back to its TLV plaintext, for crafting corruptions. */
    private fun inflate(input: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        inflater.setInput(input)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0 && (inflater.finished() || inflater.needsInput())) break
            out.write(chunk, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    /** Zlib-format DEFLATE matching the codec's Inflater — for crafting malformed inputs. */
    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        deflater.setInput(input)
        deflater.finish()
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }
}
