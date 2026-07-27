// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.MessagePadding
import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.decoy.DecoyEnvelopeBuilder
import com.zitrone.app.decoy.DecoyIdentity
import com.zitrone.app.net.WsClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * THE U2 GATE: **the cover envelope's `message.send` frame is the same number of bytes as the frame
 * of the real envelope it covers** — for every shape, counter, block count, TTL and burn flag a real
 * send can produce.
 *
 * Everything here is measured against **real libsignal 0.46.0 output**, never against a formula
 * copied out of prose. Each size test builds a genuine X3DH session over in-memory stores, encrypts
 * genuine [MessagePadding]-padded plaintext through a real `SessionCipher`, wraps the result in the
 * production [MessageEnvelope] exactly as `MessagingCoordinator` does, and frames it with the
 * production [WsClient.messageSendFrame] — then asserts the cover frame matches. A few bytes out is
 * not a near miss: base64 turns a length difference into a visible `=`, which is a perfect
 * one-field discriminator in the very field added to defeat discrimination.
 *
 * **What changed after review round 1, and what the tests now have to do differently.** The previous
 * interface took a block count, so the cover's shape came from the decoy's own counter rather than
 * from the message it covered, and the tests only ever compared first→first and subsequent→subsequent
 * — the pairing that actually ships (a real first message beside a decoy that is not one) was
 * unreachable through the API and so untestable. The gate is now a cross product, and the builder
 * takes the covered envelope itself.
 *
 * The "real" peer is built to be exactly what [DecoyIdentity.generateBundle] registers — one-time
 * prekey ids from [DecoyIdentity.ONE_TIME_PREKEY_IDS], signed prekey id
 * [DecoyIdentity.SIGNED_PREKEY_ID] — so the comparison is against the real traffic this cover
 * traffic actually has to hide among, not against a convenient fixture.
 *
 * Base64: the production send path uses `android.util.Base64` with `NO_WRAP`, which is not loadable
 * off-device; `java.util.Base64.getEncoder()` is used on both sides here and is the same encoding
 * (RFC 4648 basic alphabet, padded, no line breaks). [`the cover base64 uses the strict padded
 * alphabet with no line breaks`] pins the properties that equivalence rests on, rather than leaving
 * it as an assumption.
 */
class DecoyEnvelopeBuilderTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private val fixedInstant: Instant = Instant.parse("2026-07-27T09:41:07.123Z")
    private val senderAccountId = UUID.randomUUID().toString()
    private val contactAccountId = UUID.randomUUID().toString()
    private val syntheticAccountId = UUID.randomUUID().toString()
    private val senderIdentity: IdentityKeyPair = IdentityKeyPair.generate()
    private val senderRegistrationId = 9_142

    private fun sender() = DecoyEnvelopeBuilder.Sender(
        accountId = senderAccountId,
        registrationId = senderRegistrationId,
        identityKeySerialized = senderIdentity.publicKey.serialize(),
    )

    /** The cover builder's own clock runs a few milliseconds behind the real send, as U3's will. */
    private fun builder(now: Instant = fixedInstant.plusMillis(31)) =
        DecoyEnvelopeBuilder(clock = { now })

    private fun cover(real: MessageEnvelope, now: Instant = fixedInstant.plusMillis(31)) =
        builder(now).build(sender(), syntheticAccountId, real)

    /**
     * A real sender talking to a peer registered exactly the way the synthetic account is.
     * [advanceTo] drives the real session to the counter under test. [signedPreKeyId] is the PEER's,
     * so a fixture can be built where it does NOT match the synthetic account's own — the case the
     * cover blob has to absorb in its body length.
     */
    private inner class RealPath(signedPreKeyId: Int = DecoyIdentity.SIGNED_PREKEY_ID) {
        private val peerIdentity = IdentityKeyPair.generate()
        private val local = InMemorySignalProtocolStore(senderIdentity, senderRegistrationId)
        private val peer = InMemorySignalProtocolStore(peerIdentity, 4_211)
        private val peerAddr = SignalProtocolAddress(contactAccountId, 1)
        private val localAddr = SignalProtocolAddress(senderAccountId, 1)

        init {
            val preKeyPair = Curve.generateKeyPair()
            val signedPreKeyPair = Curve.generateKeyPair()
            val signature = Curve.calculateSignature(
                peerIdentity.privateKey,
                signedPreKeyPair.publicKey.serialize(),
            )
            // The id the relay would issue for a first fetch, and the signed id the bundle carries.
            peer.storePreKey(
                DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID,
                PreKeyRecord(DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID, preKeyPair),
            )
            peer.storeSignedPreKey(
                signedPreKeyId,
                SignedPreKeyRecord(signedPreKeyId, fixedInstant.toEpochMilli(), signedPreKeyPair, signature),
            )
            SessionBuilder(local, peerAddr).process(
                PreKeyBundle(
                    4_211, 1,
                    DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID, preKeyPair.publicKey,
                    signedPreKeyId, signedPreKeyPair.publicKey,
                    signature, peerIdentity.publicKey,
                ),
            )
        }

        /** Encrypt one padded [blockCount]-block plaintext, as the real send path does. */
        fun encrypt(blockCount: Int): CiphertextMessage {
            val plaintext = ByteArray(blockCount * MessagePadding.BLOCK_BYTES - 8) { 0x41 }
            val padded = MessagePadding.pad(plaintext)
            check(padded.size == blockCount * MessagePadding.BLOCK_BYTES)
            return SessionCipher(local, peerAddr).encrypt(padded)
        }

        /**
         * Complete the ratchet (so later sends are ordinary [SignalMessage]s) and advance the
         * sending counter to [counter] - 1, so the NEXT [encrypt] carries exactly [counter].
         */
        fun advanceTo(counter: Int) {
            val first = encrypt(1)
            SessionCipher(peer, localAddr).decrypt(PreKeySignalMessage(first.serialize()))
            val reply = SessionCipher(peer, localAddr).encrypt(MessagePadding.pad("y".toByteArray()))
            SessionCipher(local, peerAddr).decrypt(SignalMessage(reply.serialize()))
            repeat(counter) { encrypt(1) }
        }

        /** The production envelope, populated exactly as `MessagingCoordinator.deliverText` does. */
        fun envelope(
            message: CiphertextMessage,
            ttlSeconds: Int? = null,
            burnOnRead: Boolean = false,
            at: Instant = fixedInstant,
        ): MessageEnvelope {
            val serialized = message.serialize()
            val prekey = message.type == CiphertextMessage.PREKEY_TYPE
            val parsed = if (prekey) PreKeySignalMessage(serialized) else null
            return MessageEnvelope(
                id = UUID.randomUUID().toString(),
                senderId = senderAccountId,
                recipientId = contactAccountId,
                ciphertext = b64(serialized),
                ephemeralKey = parsed?.let { b64(it.baseKey.serialize()) },
                preKeyId = parsed?.preKeyId?.orElse(null),
                messageNumber = if (prekey) {
                    parsed!!.whisperMessage.counter
                } else {
                    SignalMessage(serialized).counter
                },
                previousChainLength = 0,
                timestamp = DateTimeFormatter.ISO_INSTANT.format(at),
                ttlSeconds = ttlSeconds,
                burnOnRead = burnOnRead,
                mediaType = MessageEnvelope.MEDIA_TEXT,
            )
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun frameLength(envelope: MessageEnvelope): Int =
        WsClient.messageSendFrame(envelope).toString().toByteArray(Charsets.UTF_8).size

    private fun bytes(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    /**
     * The field-for-field fingerprint of an envelope.
     *
     * Every field is compared by its EXACT value except the five whose content is supposed to
     * differ — `id`, `recipient_id`, `ciphertext`, `ephemeral_key`, `timestamp` — which are compared
     * by JSON type, string length and trailing base64 padding. Padding is recorded separately
     * because base64 quantises: 323 and 324 bytes both encode to 432 characters and differ only in
     * whether the last character is `=`. `timestamp` is length-compared and NOT value-compared on
     * purpose: two envelopes a few milliseconds apart carrying an identical timestamp would pair
     * themselves. `recipient_id` is the synthetic account rather than the real contact, which is the
     * whole point; its WIDTH is what has to match.
     */
    private val randomContentFields =
        setOf("id", "recipient_id", "ciphertext", "ephemeral_key", "timestamp")

    private fun shape(envelope: MessageEnvelope): Map<String, String> {
        val json = envelope.toJson()
        return json.keys().asSequence().associateWith { key ->
            val value = json.get(key)
            when {
                value == JSONObject.NULL -> "null"
                key !in randomContentFields -> "exact(${value.javaClass.simpleName}:$value)"
                value is String ->
                    "string(len=${value.length},pad=${value.takeLastWhile { it == '=' }.length})"
                else -> "other(${value.javaClass.simpleName})"
            }
        }
    }

    /** Assert a cover envelope is indistinguishable from the real one it covers. */
    private fun assertCovers(real: MessageEnvelope, cover: MessageEnvelope, what: String) {
        assertEquals("$what — ciphertext BYTE length", bytes(real.ciphertext).size, bytes(cover.ciphertext).size)
        assertEquals("$what — field shapes", shape(real), shape(cover))
        assertEquals("$what — FRAME length", frameLength(real), frameLength(cover))
    }

    // ── THE GATE ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cover envelope frames to exactly the size of the real envelope it covers - every shape, counter and block count`() {
        // The cross product the old block-count interface could not express. Each row is a real
        // envelope of some shape at some counter; the cover has to land on it exactly.
        for (blocks in 1..4) {
            for (counter in listOf(0, 5, 128)) {
                for ((ttl, burn) in listOf(null to false, 86_400 to true)) {
                    // First-shaped: a real X3DH message stays PREKEY_TYPE until the peer replies,
                    // so counters 0..n are all reachable in that shape.
                    val firstPath = RealPath()
                    repeat(counter) { firstPath.encrypt(1) }
                    val realFirst = firstPath.envelope(firstPath.encrypt(blocks), ttl, burn)
                    assertEquals("fixture is first-shaped at $counter", counter, realFirst.messageNumber)
                    assertTrue("fixture really is an X3DH first message", realFirst.ephemeralKey != null)
                    assertCovers(realFirst, cover(realFirst), "first-shaped, $blocks blocks, counter $counter")

                    // Subsequent-shaped at the same counter.
                    val path = RealPath().also { it.advanceTo(counter) }
                    val real = path.envelope(path.encrypt(blocks), ttl, burn)
                    assertEquals("fixture is subsequent-shaped at $counter", counter, real.messageNumber)
                    assertNull("fixture really is an ordinary message", real.ephemeralKey)
                    assertCovers(real, cover(real), "subsequent, $blocks blocks, counter $counter")
                }
            }
        }
    }

    @Test
    fun `the cover mirrors the covered envelope's SHAPE, not any state of the decoy's own`() {
        // The round-1 P1, as a regression test. The old builder emitted the X3DH shape on its own
        // counter 0 and the ordinary shape thereafter, so the FIRST cover of a session was
        // first-shaped whatever it covered and every later one was not. Here one builder covers a
        // subsequent message first and a first message second — the order the old code could not
        // get right — and each cover follows its own subject.
        val b = builder()
        val establishedPath = RealPath().also { it.advanceTo(9) }
        val realSubsequent = establishedPath.envelope(establishedPath.encrypt(1))
        val coverOfSubsequent = b.build(sender(), syntheticAccountId, realSubsequent)
        assertNull("covering an ordinary message emits no ephemeral key", coverOfSubsequent.ephemeralKey)
        assertNull("nor a prekey id", coverOfSubsequent.preKeyId)
        assertCovers(realSubsequent, coverOfSubsequent, "ordinary covered first")

        val freshPath = RealPath()
        val realFirst = freshPath.envelope(freshPath.encrypt(1))
        val coverOfFirst = b.build(sender(), syntheticAccountId, realFirst)
        assertTrue("covering an X3DH first message emits an ephemeral key", coverOfFirst.ephemeralKey != null)
        assertTrue("and a prekey id", coverOfFirst.preKeyId != null)
        assertCovers(realFirst, coverOfFirst, "first-shaped covered second")

        // And the two shapes really are different sizes, so the assertions above had something to
        // be wrong about: this is the 147 bytes the observer used to read the answer off.
        assertNotEquals(
            "the two shapes must differ in frame size for this test to mean anything",
            frameLength(realSubsequent),
            frameLength(realFirst),
        )
        assertEquals(
            "the measured first-message overhead",
            147,
            frameLength(realFirst) - frameLength(realSubsequent),
        )
    }

    @Test
    fun `the DECIMAL width of message_number cannot separate the pair`() {
        // message_number is a JSON number: `5` and `128` are two bytes apart in the frame, and the
        // ciphertext field cannot absorb that (base64 quantises to four characters, so both
        // ciphertexts differ by a multiple of four whatever length the blob is given). Mirroring the
        // covered counter is what closes it.
        val frames = mutableMapOf<Int, Int>()
        for (counter in listOf(5, 128, 1_000)) {
            val path = RealPath().also { it.advanceTo(counter) }
            val real = path.envelope(path.encrypt(1))
            assertEquals("real session at the counter under test", counter, real.messageNumber)
            assertCovers(real, cover(real), "counter $counter")
            frames[counter] = frameLength(real)
        }
        assertNotEquals("a one-digit and a three-digit counter differ in frame size", frames[5], frames[128])
        assertEquals("by exactly the two decimal digits", 2, frames.getValue(128) - frames.getValue(5))
    }

    @Test
    fun `the counter VARINT boundary is honoured - a cover ciphertext grows exactly where a real one does`() {
        // Inside the blob the counter is a protobuf varint: 127 costs one byte, 128 costs two,
        // 16384 costs three. Base64 quantises, so the first step shows up as a change of PADDING and
        // only the second moves the character count — both are compared.
        val realBytes = mutableMapOf<Int, Int>()
        for (counter in listOf(126, 127, 128, 129, 16_383, 16_384)) {
            val path = RealPath().also { it.advanceTo(counter) }
            val real = path.envelope(path.encrypt(1))
            assertCovers(real, cover(real), "varint boundary at $counter")
            realBytes[counter] = bytes(real.ciphertext).size
        }
        assertNotEquals(
            "the first varint boundary must actually move the length",
            realBytes.getValue(127),
            realBytes.getValue(128),
        )
        assertNotEquals(
            "the second varint boundary must move it too",
            realBytes.getValue(16_383),
            realBytes.getValue(16_384),
        )
    }

    // ── KEY MATERIAL ────────────────────────────────────────────────────────────────────────

    @Test
    fun `every synthetic public key is a CANONICAL Curve25519 encoding, as a generated one always is`() {
        // The round-1 P1. `0x05 || random(32)` is not a valid encoding: a genuine Curve25519 public
        // has bit 255 of the point clear, and random bytes set it about half the time — so about
        // half of all covers, and three quarters of first ones (two keys each), carried a
        // structurally impossible key. The builder generates real keypairs and drops the private
        // half, so the whole distribution is right rather than the one bit that was measured.
        val samples = 200
        var randomWouldHaveFailed = 0
        val random = SecureRandom()
        repeat(samples) {
            // Real libsignal keys, re-measuring the claim this test rests on.
            assertHighBitClear(Curve.generateKeyPair().publicKey.serialize(), "a real generated key")
            // And what the old implementation emitted, so this test is known to discriminate.
            val impostor = ByteArray(33).also { b -> random.nextBytes(b) }
            if (impostor[32].toInt() and 0x80 != 0) randomWouldHaveFailed++
        }
        assertTrue(
            "0x05||random(32) must fail this check often, or the assertion below proves nothing " +
                "($randomWouldHaveFailed of $samples)",
            randomWouldHaveFailed > samples / 4,
        )

        val path = RealPath()
        repeat(samples) {
            val real = path.envelope(path.encrypt(1))
            val blob = bytes(cover(real).ciphertext)
            for (at in keyValueOffsets(blob, firstShaped = true)) {
                assertHighBitClear(blob.copyOfRange(at, at + 33), "a cover key at offset $at")
            }
        }
    }

    /** `0x05 ‖ point`, canonical: parses as a point, and bit 255 of the little-endian point is clear. */
    private fun assertHighBitClear(serialized: ByteArray, what: String) {
        assertEquals("$what is 33 bytes", 33, serialized.size)
        assertEquals("$what carries the DJB type tag", 0x05, serialized[0].toInt())
        assertEquals(
            "$what must have bit 255 of the point CLEAR — random bytes set it half the time",
            0,
            serialized[32].toInt() and 0x80,
        )
        // And libsignal itself accepts it as a point.
        Curve.decodePoint(serialized, 0)
    }

    /** Offsets of the serialized-key VALUES in a blob built by the builder's own layout. */
    private fun keyValueOffsets(blob: ByteArray, firstShaped: Boolean): List<Int> {
        if (!firstShaped) return listOf(1 + 2) // version, ratchet-key tag + length
        val baseKeyAt = 1 + 1 + DecoyEnvelopeBuilder.varintLength(DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID) + 2
        val identityKeyAt = baseKeyAt + 33 + 2
        val innerSize = PreKeySignalMessage(blob).whisperMessage.serialize().size
        val trailing = 1 + DecoyEnvelopeBuilder.varintLength(senderRegistrationId) +
            1 + DecoyEnvelopeBuilder.varintLength(DecoyIdentity.SIGNED_PREKEY_ID)
        val innerAt = blob.size - trailing - innerSize
        return listOf(baseKeyAt, identityKeyAt, innerAt + 3)
    }

    // ── STRUCTURE ───────────────────────────────────────────────────────────────────────────

    /**
     * The strongest assertion in this file: for the same parameters, the cover ciphertext is
     * **byte-identical to a real one in every position that does not carry random content**, and
     * every position that DOES is checked for what it is supposed to be rather than skipped.
     *
     * It exists because a field can be wrong without being the wrong SIZE. `previous_counter` is a
     * one-byte varint whatever its value, libsignal's Java API does not expose it, and a length
     * comparison cannot see it — a mutation setting it to 1 passed every other test in this class.
     *
     * The excluded regions were what let the invalid-key defect through round 1: they were simply
     * skipped. They are now ASSERTED — every skipped key region has to be a canonical Curve25519
     * encoding — so "not byte-equal" no longer means "not checked".
     */
    @Test
    fun `the cover ciphertext is byte-identical to a real one everywhere it is not random, and valid where it is`() {
        // A subsequent message has only eleven structural bytes — version, three field tags with
        // their length/type bytes, and two varints — so the guard against a vacuous comparison is
        // set just under that rather than at some round number that would silently pass an empty
        // check on the smaller of the two shapes.
        fun assertSameLayout(real: ByteArray, cover: ByteArray, random: List<IntRange>, keysAt: List<Int>) {
            assertEquals("same serialized length", real.size, cover.size)
            val fixed = real.indices.filter { i -> random.none { i in it } }
            assertTrue("the random regions cannot cover the whole message", fixed.size >= 11)
            for (i in fixed) {
                assertEquals(
                    "byte $i is structure, not content — real 0x%02x, cover 0x%02x".format(real[i], cover[i]),
                    real[i],
                    cover[i],
                )
            }
            for (at in keysAt) {
                assertHighBitClear(cover.copyOfRange(at, at + 33), "the excluded cover key at $at")
                assertHighBitClear(real.copyOfRange(at, at + 33), "the excluded real key at $at")
            }
        }

        fun innerRandom(at: Int, size: Int, bodyLen: Int) = listOf(
            (at + 4) until (at + 4 + 32), // ratchet key value, minus its 0x05 type tag
            (at + size - 8 - bodyLen) until (at + size - 8), // AEAD body
            (at + size - 8) until (at + size), // truncated MAC
        )

        // Subsequent message.
        val counter = 5
        val path = RealPath().also { it.advanceTo(counter) }
        val realEnvelope = path.envelope(path.encrypt(2))
        val realPlain = bytes(realEnvelope.ciphertext)
        val coverPlain = bytes(cover(realEnvelope).ciphertext)
        val bodyLen = 2 * MessagePadding.BLOCK_BYTES + 16
        // Pin what each blob IS before comparing where its bytes sit, so a layout mismatch cannot
        // be misread as a byte-level difference when it is really the wrong message shape.
        assertEquals("the real fixture is at the counter under test", counter, SignalMessage(realPlain).counter)
        assertEquals("and so is the cover blob", counter, SignalMessage(coverPlain).counter)
        assertSameLayout(realPlain, coverPlain, innerRandom(0, realPlain.size, bodyLen), listOf(3))

        // First message: the same rules for the inner blob, plus the base key value.
        val freshPath = RealPath()
        val realFirstEnvelope = freshPath.envelope(freshPath.encrypt(2))
        val realFirst = bytes(realFirstEnvelope.ciphertext)
        val coverFirst = bytes(cover(realFirstEnvelope).ciphertext)
        val innerSize = PreKeySignalMessage(realFirst).whisperMessage.serialize().size
        val trailing = 1 + DecoyEnvelopeBuilder.varintLength(senderRegistrationId) +
            1 + DecoyEnvelopeBuilder.varintLength(DecoyIdentity.SIGNED_PREKEY_ID)
        val innerAt = realFirst.size - trailing - innerSize
        val baseKeyValueAt = 1 + 1 + DecoyEnvelopeBuilder.varintLength(DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID) + 2
        assertSameLayout(
            realFirst,
            coverFirst,
            innerRandom(innerAt, innerSize, bodyLen) +
                listOf((baseKeyValueAt + 1) until (baseKeyValueAt + 33)),
            listOf(baseKeyValueAt, innerAt + 3),
        )
    }

    @Test
    fun `the cover ciphertext PARSES as a genuine libsignal message carrying the fields the envelope claims`() {
        val path = RealPath()
        val real = path.envelope(path.encrypt(3))
        val first = cover(real)
        val parsedFirst = PreKeySignalMessage(bytes(first.ciphertext))
        assertEquals("the sender's own registration id", senderRegistrationId, parsedFirst.registrationId)
        assertEquals("the synthetic account's signed prekey id", DecoyIdentity.SIGNED_PREKEY_ID, parsedFirst.signedPreKeyId)
        assertEquals("the sender's own identity key", senderIdentity.publicKey, parsedFirst.identityKey)
        assertEquals("prekey_id matches the id inside", first.preKeyId, parsedFirst.preKeyId.orElse(null))
        assertEquals(
            "ephemeral_key is a verbatim copy of the base key inside",
            first.ephemeralKey,
            b64(parsedFirst.baseKey.serialize()),
        )
        assertEquals("message_number matches the counter inside", first.messageNumber, parsedFirst.whisperMessage.counter)

        val laterPath = RealPath().also { it.advanceTo(12) }
        val later = cover(laterPath.envelope(laterPath.encrypt(1)))
        val parsedLater = SignalMessage(bytes(later.ciphertext))
        assertEquals("message_number matches the counter inside", later.messageNumber, parsedLater.counter)
        assertEquals("a serialized ratchet key is 33 bytes", 33, parsedLater.senderRatchetKey.serialize().size)
        assertEquals("libsignal's current message version", 3, parsedLater.messageVersion)
    }

    @Test
    fun `the 33-byte ephemeral key base64s to 44 characters with NO padding, as a real one does`() {
        val path = RealPath()
        val real = path.envelope(path.encrypt(1))
        val coverKey = requireNotNull(cover(real).ephemeralKey)
        val realKey = requireNotNull(real.ephemeralKey)
        assertEquals("a real serialized public key is 33 bytes", 33, bytes(realKey).size)
        assertEquals("so the cover one must be too", 33, bytes(coverKey).size)
        assertEquals("44 characters", realKey.length, coverKey.length)
        assertEquals("44 characters", 44, coverKey.length)
        assertTrue("a real first message's ephemeral key carries NO base64 padding", !realKey.endsWith("="))
        assertTrue("and neither may a cover one — a trailing '=' is a perfect discriminator", !coverKey.endsWith("="))
    }

    @Test
    fun `the cover base64 uses the strict padded alphabet with no line breaks`() {
        val path = RealPath()
        val c = cover(path.envelope(path.encrypt(2)))
        for (field in listOf(c.ciphertext, requireNotNull(c.ephemeralKey))) {
            assertTrue("RFC 4648 basic alphabet, padded, unwrapped", Regex("^[A-Za-z0-9+/]+={0,2}$").matches(field))
            assertEquals("a whole number of base64 quanta", 0, field.length % 4)
        }
    }

    // ── ABSORPTION ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a blob field that cannot be mirrored is absorbed by the random body, not by the frame`() {
        // `signed_pre_key_id` inside a real first message names the PEER's signed prekey; a cover
        // one must name the synthetic account's own, which is 1. A peer whose id needs a wider
        // varint therefore makes the cover blob structurally shorter, and the difference has to come
        // out of the random body — the observable that must not move is the frame.
        val path = RealPath(signedPreKeyId = 5_000)
        val real = path.envelope(path.encrypt(1))
        assertEquals("the fixture's peer really uses a two-byte id", 2, DecoyEnvelopeBuilder.varintLength(5_000))
        val c = cover(real)
        assertCovers(real, c, "a peer signed-prekey id the cover cannot mirror")

        val parsed = PreKeySignalMessage(bytes(c.ciphertext))
        assertEquals("the cover names the synthetic account's own signed prekey", 1, parsed.signedPreKeyId)
        val body = parsed.whisperMessage.serialize().size -
            (1 + 35 + 2 + 2 + 1 + DecoyEnvelopeBuilder.varintLength(MessagePadding.BLOCK_BYTES + 16 + 1) + 8)
        assertEquals(
            "the body carries the slack — one byte past a padded-block multiple, the §2.4 residual",
            MessagePadding.BLOCK_BYTES + 16 + 1,
            body,
        )
    }

    @Test
    fun `the cover timestamp is a fresh value of the covered one's WIDTH`() {
        // ISO_INSTANT trims trailing zeros, so a whole-second real send frames four bytes shorter
        // than a millisecond one. The cover has to follow the width without copying the value.
        val path = RealPath()
        val wholeSecond = path.envelope(path.encrypt(1), at = Instant.parse("2026-07-27T09:41:07Z"))
        assertEquals("the fixture really is a whole-second timestamp", 20, wholeSecond.timestamp.length)
        val c = cover(wholeSecond, now = Instant.parse("2026-07-27T09:41:08.154Z"))
        assertEquals("the cover follows the width", 20, c.timestamp.length)
        assertNotEquals("but not the value — identical timestamps would pair the two", wholeSecond.timestamp, c.timestamp)
        assertCovers(wholeSecond, c, "whole-second covered timestamp")

        val millis = path.envelope(path.encrypt(1), at = Instant.parse("2026-07-27T09:41:07.500Z"))
        assertEquals(24, millis.timestamp.length)
        val cm = cover(millis, now = Instant.parse("2026-07-27T09:41:07.531Z"))
        assertEquals("and the millisecond width too", 24, cm.timestamp.length)
        assertCovers(millis, cm, "millisecond covered timestamp")

        // A cover clock that happens to land on a whole second still has to render a 24-character
        // timestamp when the covered one did — the coercion path, not the lucky path.
        val coerced = cover(millis, now = Instant.parse("2026-07-27T09:41:08Z"))
        assertEquals("coerced up to the covered width", 24, coerced.timestamp.length)
        assertCovers(millis, coerced, "coerced cover timestamp")
    }

    // ── FIELDS ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `prekey_id is drawn from the synthetic account's OWN uploaded batch and mirrors the covered width`() {
        val uploaded = DecoyIdentity.generateBundle(DecoyIdentity.generateIdentity()).oneTimePreKeys.map { it.id }
        assertEquals(
            "the declared id range IS the batch that gets uploaded — the builder and the generator " +
                "must not drift, because nothing durable records which ids this account published",
            DecoyIdentity.ONE_TIME_PREKEY_IDS.toList(),
            uploaded,
        )
        val path = RealPath()
        val real = path.envelope(path.encrypt(1))
        assertEquals("the fixture's peer issued its lowest unconsumed id", uploaded.min(), real.preKeyId)
        val c = cover(real)
        assertTrue("the emitted id is one this account actually published", c.preKeyId in uploaded)
        assertEquals("and it mirrors the covered id, which is in the batch", real.preKeyId, c.preKeyId)

        // A covered id past the batch cannot be mirrored verbatim; the width is what must survive,
        // because no other field can absorb a decimal-width difference.
        val wide = real.copy(preKeyId = 512)
        val coverWide = builder().build(sender(), syntheticAccountId, wide)
        assertEquals("three digits in, three digits out", 3, coverWide.preKeyId.toString().length)
        assertTrue("and still an id this account published", coverWide.preKeyId in uploaded)
        assertNotEquals("the covered id itself is not in the batch, so it is not copied", 512, coverWide.preKeyId)
        assertEquals(
            "and the frame still matches, which is the observable that matters",
            frameLength(wide),
            frameLength(coverWide),
        )
    }

    @Test
    fun `no cleartext field is a CONSTANT where a real message varies`() {
        val path = RealPath().also { it.advanceTo(3) }
        val plainReal = path.envelope(path.encrypt(1), ttlSeconds = null, burnOnRead = false)
        val burningReal = path.envelope(path.encrypt(2), ttlSeconds = 86_400, burnOnRead = true)
        val a = cover(plainReal)
        val c = cover(burningReal)

        assertNull("ttl mirrors the covered message", a.ttlSeconds)
        assertEquals("ttl mirrors the covered message", 86_400, c.ttlSeconds)
        assertEquals("burn mirrors the covered message", false, a.burnOnRead)
        assertEquals("burn mirrors the covered message", true, c.burnOnRead)
        assertEquals("media type mirrors the covered message", plainReal.mediaType, a.mediaType)
        assertEquals("previous_chain_length mirrors the covered message", 0, a.previousChainLength)
        assertNotEquals("block count mirrors the covered message", a.ciphertext.length, c.ciphertext.length)
        assertNotEquals("counters advance with the covered conversation", a.messageNumber, c.messageNumber)
        assertNotEquals("message ids are fresh", a.id, c.id)

        // Two covers of the SAME real envelope differ in every random field.
        val d = cover(plainReal)
        assertEquals("same subject, same size", a.ciphertext.length, d.ciphertext.length)
        assertNotEquals("but never the same bytes", a.ciphertext, d.ciphertext)
        assertNotEquals("nor the same message id", a.id, d.id)
    }

    @Test
    fun `a cover envelope carries nothing of the message it covers`() {
        val path = RealPath()
        val real = path.envelope(path.encrypt(2), ttlSeconds = 600, burnOnRead = true)
        val c = cover(real)
        assertNotEquals("not the ciphertext", real.ciphertext, c.ciphertext)
        assertNotEquals("not the ephemeral key", real.ephemeralKey, c.ephemeralKey)
        assertNotEquals("not the message id", real.id, c.id)
        assertNotEquals("not the recipient", real.recipientId, c.recipientId)
        assertEquals("the recipient is the synthetic account", syntheticAccountId, c.recipientId)
        assertEquals("the sender is this account, as on the real send", senderAccountId, c.senderId)
        assertTrue(
            "no run of the covered ciphertext survives into the cover",
            !c.ciphertext.contains(real.ciphertext.substring(0, 24)),
        )
    }

    // ── FAIL CLOSED ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a registration id outside the real generator's interval fails closed`() {
        val identity = senderIdentity.publicKey.serialize()
        assertThrows(IllegalArgumentException::class.java) {
            DecoyEnvelopeBuilder.Sender(senderAccountId, 0, identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DecoyEnvelopeBuilder.Sender(senderAccountId, 16_381, identity)
        }
        // The two ends of the interval the real generators actually emit.
        DecoyEnvelopeBuilder.Sender(senderAccountId, 1, identity)
        DecoyEnvelopeBuilder.Sender(senderAccountId, 16_380, identity)
    }

    @Test
    fun `a covered envelope the builder cannot match exactly fails closed rather than mismatching`() {
        val path = RealPath()
        val real = path.envelope(path.encrypt(1))
        val b = builder()

        // Too small to lay out one padded block.
        assertThrows(IllegalArgumentException::class.java) {
            b.build(sender(), syntheticAccountId, real.copy(ciphertext = "A".repeat(198) + "=="))
        }
        // Past the fail-closed ceiling, which is what makes the length arithmetic overflow-proof.
        assertThrows(IllegalArgumentException::class.java) {
            b.build(sender(), syntheticAccountId, real.copy(ciphertext = "A".repeat(4 * (1 shl 20))))
        }
        // Not this account's own traffic.
        assertThrows(IllegalArgumentException::class.java) {
            b.build(sender(), syntheticAccountId, real.copy(senderId = UUID.randomUUID().toString()))
        }
        // Half a first message.
        assertThrows(IllegalArgumentException::class.java) {
            b.build(sender(), syntheticAccountId, real.copy(preKeyId = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            b.build(sender(), syntheticAccountId, real.copy(ephemeralKey = null))
        }
        // A recipient id of a different width cannot be mirrored.
        assertThrows(IllegalArgumentException::class.java) {
            b.build(sender(), syntheticAccountId, real.copy(recipientId = "short"))
        }
        // And the legitimate envelope still builds afterwards.
        assertCovers(real, b.build(sender(), syntheticAccountId, real), "after the rejected calls")
    }
}
