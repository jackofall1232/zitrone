// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.MessagePadding
import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.decoy.DecoyCounterReservation
import com.zitrone.app.decoy.DecoyEnvelopeBuilder
import com.zitrone.app.decoy.DecoyIdentity
import com.zitrone.app.net.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
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
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * THE U2 GATE: a cover envelope is indistinguishable field-for-field from a real `message.send` of
 * the same block count.
 *
 * Everything here is measured against **real libsignal 0.46.0 output**, never against a formula
 * copied out of prose. Each size test builds a genuine X3DH session over in-memory stores, encrypts
 * genuine [MessagePadding]-padded plaintext through a real `SessionCipher`, wraps the result in the
 * production [MessageEnvelope] exactly as `MessagingCoordinator` does, and frames it with the
 * production [WsClient.messageSendFrame] — then asserts the cover frame matches. A few bytes out is
 * not a near miss: base64 turns a length difference into a visible `=`, which is a perfect
 * one-field discriminator in the very field added to defeat discrimination.
 *
 * The "real" peer is built to be exactly what [DecoyIdentity.generateBundle] registers — one-time
 * prekey ids from [DecoyIdentity.ONE_TIME_PREKEY_IDS], signed prekey id
 * [DecoyIdentity.SIGNED_PREKEY_ID] — and the relay issues the lowest unconsumed id
 * (`Store.ConsumeOneTimePrekey`, `ORDER BY prekey_id LIMIT 1`). So the comparison is against the
 * real traffic this cover traffic actually has to hide among, not against a convenient fixture.
 *
 * Base64: the production send path uses `android.util.Base64` with `NO_WRAP`, which is not loadable
 * off-device; `java.util.Base64.getEncoder()` is used on both sides here and is the same encoding
 * (RFC 4648 basic alphabet, padded, no line breaks). [`the cover base64 uses the strict padded
 * alphabet with no line breaks`] pins the properties that equivalence rests on, rather than leaving
 * it as an assumption.
 */
class DecoyEnvelopeBuilderTest {

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A live vault, so the counter allocator has somewhere real to reserve from. */
    private inner class Vault(
        startCounter: Long = 0L,
        private val persistFails: Boolean = false,
    ) {
        val state: VaultState = VaultState.empty().also {
            if (startCounter != 0L) it.decoy = DecoyState(counterHighWater = startCounter)
        }
        private val vaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 }
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = vaultKey.copyOf(),
            slotIndex = 0,
            persist = { _, _ -> if (persistFails) throw IOException("sink down") },
            cooldownMs = 60_000L,
            flushContext = Dispatchers.IO,
        )
        val runtime = VaultRuntime(session, state)
    }

    private val fixedInstant: Instant = Instant.parse("2026-07-27T09:41:07.123Z")
    private val senderAccountId = UUID.randomUUID().toString()
    private val syntheticAccountId = UUID.randomUUID().toString()
    private val senderIdentity: IdentityKeyPair = IdentityKeyPair.generate()
    private val senderRegistrationId = 9_142

    private fun sender() = DecoyEnvelopeBuilder.Sender(
        accountId = senderAccountId,
        registrationId = senderRegistrationId,
        identityKeySerialized = senderIdentity.publicKey.serialize(),
    )

    private fun builder(vault: Vault) = DecoyEnvelopeBuilder(
        counters = DecoyCounterReservation.forRuntime(vault.runtime),
        clock = { fixedInstant },
    )

    /**
     * A real sender talking to a peer registered exactly the way the synthetic account is.
     * [advanceTo] drives the real session to the counter under test.
     */
    private inner class RealPath {
        private val peerIdentity = IdentityKeyPair.generate()
        private val local = InMemorySignalProtocolStore(senderIdentity, senderRegistrationId)
        private val peer = InMemorySignalProtocolStore(peerIdentity, 4_211)
        private val peerAddr = SignalProtocolAddress(syntheticAccountId, 1)
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
                DecoyIdentity.SIGNED_PREKEY_ID,
                SignedPreKeyRecord(
                    DecoyIdentity.SIGNED_PREKEY_ID,
                    fixedInstant.toEpochMilli(),
                    signedPreKeyPair,
                    signature,
                ),
            )
            SessionBuilder(local, peerAddr).process(
                PreKeyBundle(
                    4_211, 1,
                    DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID, preKeyPair.publicKey,
                    DecoyIdentity.SIGNED_PREKEY_ID, signedPreKeyPair.publicKey,
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
        fun envelope(message: CiphertextMessage, ttlSeconds: Int?, burnOnRead: Boolean): MessageEnvelope {
            val serialized = message.serialize()
            val prekey = message.type == CiphertextMessage.PREKEY_TYPE
            val parsed = if (prekey) PreKeySignalMessage(serialized) else null
            return MessageEnvelope(
                id = UUID.randomUUID().toString(),
                senderId = senderAccountId,
                recipientId = syntheticAccountId,
                ciphertext = b64(serialized),
                ephemeralKey = parsed?.let { b64(it.baseKey.serialize()) },
                preKeyId = parsed?.preKeyId?.orElse(null),
                messageNumber = if (prekey) {
                    parsed!!.whisperMessage.counter
                } else {
                    SignalMessage(serialized).counter
                },
                previousChainLength = 0,
                timestamp = DateTimeFormatter.ISO_INSTANT.format(fixedInstant),
                ttlSeconds = ttlSeconds,
                burnOnRead = burnOnRead,
                mediaType = MessageEnvelope.MEDIA_TEXT,
            )
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun frameLength(envelope: MessageEnvelope): Int =
        WsClient.messageSendFrame(envelope).toString().toByteArray(Charsets.UTF_8).size

    /**
     * The field-for-field fingerprint of an envelope.
     *
     * Every field is compared by its EXACT value except the three whose content is supposed to
     * differ — `id`, `ciphertext`, `ephemeral_key` — which are compared by JSON type, string length
     * and trailing base64 padding. Padding is recorded separately because base64 quantises: 323 and
     * 324 bytes both encode to 432 characters and differ only in whether the last character is `=`.
     */
    private val randomContentFields = setOf("id", "ciphertext", "ephemeral_key")

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

    // ── THE GATE ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cover FIRST envelope is byte-for-byte the size of a real X3DH first envelope`() {
        for (blocks in 1..4) {
            val real = RealPath().let { it.envelope(it.encrypt(blocks), ttlSeconds = null, burnOnRead = false) }
            val cover = builder(Vault()).build(
                sender = sender(),
                syntheticAccountId = syntheticAccountId,
                blockCount = blocks,
                ttlSeconds = null,
                burnOnRead = false,
            )
            assertEquals(
                "$blocks-block first-message ciphertext BYTE length",
                Base64.getDecoder().decode(real.ciphertext).size,
                Base64.getDecoder().decode(cover.ciphertext).size,
            )
            assertEquals("$blocks-block first-message frame length", frameLength(real), frameLength(cover))
            assertEquals("$blocks-block first-message field shapes", shape(real), shape(cover))
        }
    }

    @Test
    fun `a cover SUBSEQUENT envelope is byte-for-byte the size of a real subsequent envelope`() {
        for (blocks in 1..4) {
            val counter = 7
            val path = RealPath().also { it.advanceTo(counter) }
            val real = path.envelope(path.encrypt(blocks), ttlSeconds = 3_600, burnOnRead = true)
            assertEquals("fixture drove the real session to the counter under test", counter, real.messageNumber)
            val cover = builder(Vault(startCounter = counter.toLong())).build(
                sender = sender(),
                syntheticAccountId = syntheticAccountId,
                blockCount = blocks,
                ttlSeconds = 3_600,
                burnOnRead = true,
            )
            assertEquals("cover spent the seeded counter", counter, cover.messageNumber)
            assertEquals(
                "$blocks-block ciphertext BYTE length",
                Base64.getDecoder().decode(real.ciphertext).size,
                Base64.getDecoder().decode(cover.ciphertext).size,
            )
            assertEquals("$blocks-block frame length", frameLength(real), frameLength(cover))
            assertEquals("$blocks-block field shapes", shape(real), shape(cover))
        }
    }

    @Test
    fun `the counter VARINT boundary is honoured - a cover envelope grows exactly where a real one does`() {
        // message_number rides in the cleartext, and it is a protobuf varint inside the ciphertext:
        // 127 costs one byte, 128 costs two. A cover envelope sized from a fixed formula is a byte
        // short from the 128th onwards, and the mismatch is checkable against the cleartext field.
        // NOTE ON WHAT IS COMPARED. Base64 quantises: 323 and 324 bytes both encode to 432
        // characters, so the first varint step shows up as a change of PADDING, not of string
        // length, and only the second step moves the character count. Both are compared —
        // the decoded byte length (which always moves) and the encoded shape (which carries the
        // padding) — because a test that only measured the string would have been blind to the
        // 128th-counter step entirely.
        val realBytes = mutableMapOf<Int, Int>()
        for (counter in listOf(126, 127, 128, 129, 16_383, 16_384)) {
            val path = RealPath().also { it.advanceTo(counter) }
            val real = path.envelope(path.encrypt(1), ttlSeconds = null, burnOnRead = false)
            assertEquals("real session at the counter under test", counter, real.messageNumber)
            val cover = builder(Vault(startCounter = counter.toLong())).build(
                sender = sender(),
                syntheticAccountId = syntheticAccountId,
                blockCount = 1,
                ttlSeconds = null,
                burnOnRead = false,
            )
            assertEquals("cover spent the seeded counter", counter, cover.messageNumber)
            val realSize = Base64.getDecoder().decode(real.ciphertext).size
            val coverSize = Base64.getDecoder().decode(cover.ciphertext).size
            assertEquals("ciphertext BYTE length at counter $counter", realSize, coverSize)
            assertEquals("ciphertext base64 shape at counter $counter", shape(real), shape(cover))
            realBytes[counter] = realSize
        }
        // And the boundaries are real, not an artefact of both sides sharing one bug: the length
        // genuinely moves across each, so the equalities above have something to be wrong about.
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

    @Test
    fun `the 33-byte ephemeral key base64s to 44 characters with NO padding, as a real one does`() {
        val real = RealPath().let { it.envelope(it.encrypt(1), ttlSeconds = null, burnOnRead = false) }
        val cover = builder(Vault()).build(sender(), syntheticAccountId, 1, null, false)
        val realKey = requireNotNull(real.ephemeralKey)
        val coverKey = requireNotNull(cover.ephemeralKey)
        assertEquals("a real serialized public key is 33 bytes", 33, Base64.getDecoder().decode(realKey).size)
        assertEquals("so the cover one must be too", 33, Base64.getDecoder().decode(coverKey).size)
        assertEquals("44 characters", realKey.length, coverKey.length)
        assertEquals("44 characters", 44, coverKey.length)
        assertTrue("a real first message's ephemeral key carries NO base64 padding", !realKey.endsWith("="))
        assertTrue("and neither may a cover one — a trailing '=' is a perfect discriminator", !coverKey.endsWith("="))
        assertEquals("libsignal's DJB type tag", 0x05, Base64.getDecoder().decode(coverKey)[0].toInt())
    }

    @Test
    fun `the cover base64 uses the strict padded alphabet with no line breaks`() {
        val cover = builder(Vault()).build(sender(), syntheticAccountId, 2, null, false)
        for (field in listOf(cover.ciphertext, requireNotNull(cover.ephemeralKey))) {
            assertTrue("RFC 4648 basic alphabet, padded, unwrapped", Regex("^[A-Za-z0-9+/]+={0,2}$").matches(field))
            assertEquals("a whole number of base64 quanta", 0, field.length % 4)
        }
    }

    @Test
    fun `the cover ciphertext PARSES as a genuine libsignal message carrying the fields the envelope claims`() {
        val first = builder(Vault()).build(sender(), syntheticAccountId, 3, null, false)
        val parsedFirst = PreKeySignalMessage(Base64.getDecoder().decode(first.ciphertext))
        assertEquals("the sender's own registration id", senderRegistrationId, parsedFirst.registrationId)
        assertEquals("the recipient's signed prekey id", DecoyIdentity.SIGNED_PREKEY_ID, parsedFirst.signedPreKeyId)
        assertEquals("the sender's own identity key", senderIdentity.publicKey, parsedFirst.identityKey)
        assertEquals("prekey_id matches the id inside", first.preKeyId, parsedFirst.preKeyId.orElse(null))
        assertEquals(
            "ephemeral_key is a verbatim copy of the base key inside",
            first.ephemeralKey,
            b64(parsedFirst.baseKey.serialize()),
        )
        assertEquals("message_number matches the counter inside", first.messageNumber, parsedFirst.whisperMessage.counter)

        val later = builder(Vault(startCounter = 12L)).build(sender(), syntheticAccountId, 1, null, false)
        val parsedLater = SignalMessage(Base64.getDecoder().decode(later.ciphertext))
        assertEquals("message_number matches the counter inside", later.messageNumber, parsedLater.counter)
        assertEquals("a serialized ratchet key is 33 bytes", 33, parsedLater.senderRatchetKey.serialize().size)
        assertEquals("libsignal's current message version", 3, parsedLater.messageVersion)
    }

    /**
     * The strongest assertion in this file: for the same parameters, the cover ciphertext is
     * **byte-identical to a real one in every position that does not carry random content**.
     *
     * It exists because a field can be wrong without being the wrong SIZE. `previous_counter` is a
     * one-byte varint whatever its value, libsignal's Java API does not expose it, and a length
     * comparison cannot see it — a mutation setting it to 1 passed every other test in this class.
     * Anything inside the blob that a size test is blind to is caught here.
     *
     * The random regions are derived from the layout, not hand-counted, so a layout change moves
     * them with it: the ratchet/base key VALUE minus its type tag, the AEAD body, the MAC. The
     * sender's identity key is deliberately NOT a random region — a real first message carries the
     * sender's own key and so must a cover one.
     */
    @Test
    fun `the cover ciphertext is byte-identical to a real one everywhere it is not random`() {
        // A subsequent message has only eleven structural bytes — version, three field tags with
        // their length/type bytes, and two varints — so the guard against a vacuous comparison is
        // set just under that rather than at some round number that would silently pass an empty
        // check on the smaller of the two shapes.
        fun assertSameLayout(real: ByteArray, cover: ByteArray, random: List<IntRange>) {
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
        }

        fun innerRandom(at: Int, size: Int, bodyLen: Int) = listOf(
            (at + 4) until (at + 4 + 32), // ratchet key value, minus its 0x05 type tag
            (at + size - 8 - bodyLen) until (at + size - 8), // AEAD body
            (at + size - 8) until (at + size), // truncated MAC
        )

        // Subsequent message.
        val counter = 5
        val path = RealPath().also { it.advanceTo(counter) }
        val realPlain = path.encrypt(2).serialize()
        val coverPlain = Base64.getDecoder().decode(
            builder(Vault(startCounter = counter.toLong()))
                .build(sender(), syntheticAccountId, 2, null, false).ciphertext,
        )
        val bodyLen = 2 * MessagePadding.BLOCK_BYTES + 16
        // Pin what each blob IS before comparing where its bytes sit, so a layout mismatch cannot
        // be misread as a byte-level difference when it is really the wrong message shape.
        assertEquals("the real fixture is at the counter under test", counter, SignalMessage(realPlain).counter)
        assertEquals("and so is the cover blob", counter, SignalMessage(coverPlain).counter)
        assertSameLayout(realPlain, coverPlain, innerRandom(0, realPlain.size, bodyLen))

        // First message: the same rules for the inner blob, plus the base key value.
        val realFirst = RealPath().encrypt(2).serialize()
        val coverFirst = Base64.getDecoder().decode(
            builder(Vault()).build(sender(), syntheticAccountId, 2, null, false).ciphertext,
        )
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
        )
    }

    @Test
    fun `the X3DH shape is emitted EXACTLY ONCE - on the first envelope and never again`() {
        val vault = Vault()
        val b = builder(vault)
        val envelopes = (0 until 5).map { b.build(sender(), syntheticAccountId, 1, null, false) }

        assertEquals("counters advance monotonically from zero", listOf(0, 1, 2, 3, 4), envelopes.map { it.messageNumber })
        val first = envelopes.first()
        assertEquals("the first envelope carries a prekey id", DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID, first.preKeyId)
        assertTrue("and an ephemeral key", first.ephemeralKey != null)
        for (later in envelopes.drop(1)) {
            assertNull("no later envelope carries an ephemeral key", later.ephemeralKey)
            assertNull("no later envelope carries a prekey id", later.preKeyId)
        }
        // A later SESSION resumes from the durable mark, so the first shape is not re-emitted.
        val resumed = builder(Vault(startCounter = 64L)).build(sender(), syntheticAccountId, 1, null, false)
        assertNull("a resumed session emits no second first-message", resumed.ephemeralKey)
        assertNull("a resumed session emits no second first-message", resumed.preKeyId)
    }

    @Test
    fun `prekey_id is drawn from the synthetic account's OWN uploaded batch`() {
        val uploaded = DecoyIdentity.generateBundle(DecoyIdentity.generateIdentity()).oneTimePreKeys.map { it.id }
        assertEquals(
            "the declared id range IS the batch that gets uploaded — the builder and the generator " +
                "must not drift, because nothing durable records which ids this account published",
            DecoyIdentity.ONE_TIME_PREKEY_IDS.toList(),
            uploaded,
        )
        val cover = builder(Vault()).build(sender(), syntheticAccountId, 1, null, false)
        assertTrue("the emitted id is one this account actually published", cover.preKeyId in uploaded)
        assertEquals(
            "and it is the one the relay would issue: ConsumeOneTimePrekey pops ORDER BY prekey_id LIMIT 1",
            uploaded.min(),
            cover.preKeyId,
        )
    }

    @Test
    fun `no cleartext field is a CONSTANT where a real message varies`() {
        val vault = Vault(startCounter = 3L)
        val b = builder(vault)
        val a = b.build(sender(), syntheticAccountId, 1, ttlSeconds = null, burnOnRead = false)
        val c = b.build(sender(), syntheticAccountId, 2, ttlSeconds = 86_400, burnOnRead = true)

        assertNull("ttl mirrors the covered message", a.ttlSeconds)
        assertEquals("ttl mirrors the covered message", 86_400, c.ttlSeconds)
        assertEquals("burn mirrors the covered message", false, a.burnOnRead)
        assertEquals("burn mirrors the covered message", true, c.burnOnRead)
        assertNotEquals("block count mirrors the covered message", a.ciphertext.length, c.ciphertext.length)
        assertNotEquals("counters advance", a.messageNumber, c.messageNumber)
        assertNotEquals("message ids are fresh", a.id, c.id)

        // Two envelopes built from IDENTICAL inputs still differ in every random field.
        val d = builder(Vault(startCounter = 3L)).build(sender(), syntheticAccountId, 1, null, false)
        assertEquals("same inputs, same size", a.ciphertext.length, d.ciphertext.length)
        assertNotEquals("but never the same bytes", a.ciphertext, d.ciphertext)
        assertNotEquals("nor the same message id", a.id, d.id)

        // media_type IS constant, and correctly so: every real send path writes "text", precisely so
        // the relay cannot separate a text, a receipt and an attachment control payload.
        assertEquals(MessageEnvelope.MEDIA_TEXT, a.mediaType)
        assertEquals("previous_chain_length is hardcoded 0 on every real Android send", 0, a.previousChainLength)
    }

    @Test
    fun `a counter reservation that cannot be made DURABLE builds no envelope`() {
        val vault = Vault(persistFails = true)
        val b = builder(vault)
        assertThrows(Exception::class.java) { b.build(sender(), syntheticAccountId, 1, null, false) }
    }

    @Test
    fun `building cover traffic writes no Signal record and moves nothing but the counter mark`() {
        val vault = Vault()
        val before = vault.runtime.read { it.decoy }
        builder(vault).build(sender(), syntheticAccountId, 1, null, false)
        val after = requireNotNull(vault.runtime.read { it.decoy })

        assertTrue("no ratchet session was established — SessionBuilder.process is never called",
            vault.runtime.read { it.signalRecords }.isEmpty())
        assertEquals("only the reservation mark moved", DecoyCounterReservation.DEFAULT_BLOCK_SIZE.toLong(), after.counterHighWater)
        assertEquals("credentials untouched", before?.accountId, after.accountId)
        assertNull("the dead-air field stays U5's", after.deadAirNextFireAtMs)
        assertNull("no deferral is written by the send path", after.provisionNotBeforeMs)
    }

    @Test
    fun `a bad argument costs no counter value`() {
        val vault = Vault()
        val b = builder(vault)
        assertThrows(IllegalArgumentException::class.java) { b.build(sender(), syntheticAccountId, 0, null, false) }
        assertThrows(IllegalArgumentException::class.java) { b.build(sender(), "", 1, null, false) }
        assertEquals(
            "the first legitimate envelope still gets counter 0",
            0,
            b.build(sender(), syntheticAccountId, 1, null, false).messageNumber,
        )
    }
}
