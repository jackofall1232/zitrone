// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.MessagePadding
import com.zitrone.app.data.MessageEnvelope
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Builds one cover-traffic envelope that is field-for-field indistinguishable from a real
 * `message.send` of the same block count.
 *
 * ## What this class is, and what it deliberately is not
 *
 * It is a **shaper**, not a crypto path. The ciphertext is random bytes laid out in exactly the wire
 * form libsignal produces (spec §2.3), and **no session is ever established** for the synthetic
 * peer: `SessionBuilder.process` would write a durable ratchet session into this vault's real
 * `signalRecords`, a cost the §4 capacity budget does not cover, to buy an observable that random
 * bytes satisfy identically. Nothing here reads or writes a Signal record, and the only durable
 * state it touches at all is the counter high-water mark, through [DecoyCounterReservation] —
 * which was built in U1 and whose writer/reader contract is tabled there.
 *
 * ## "Indistinguishable" is a claim about BYTES, so the shape is measured, not modelled from prose
 *
 * Every length constant below was measured against real libsignal 0.46.0 output, and
 * `DecoyEnvelopeBuilderTest` re-measures on every run: it encrypts genuine padded plaintext through
 * a real `SessionCipher`, then asserts the decoy's frame length, ciphertext base64 length and base64
 * padding are equal. An estimate that is a few bytes out is not a near miss here — it is a perfect
 * one-field discriminator, because base64 turns a length difference into a visible `=`.
 *
 * Three facts that cost more than they look:
 *
 *  1. **A serialized public key is 33 bytes, not 32** — `ECPublicKey.serialize()` is a `0x05` type
 *     tag plus the 32-byte Curve25519 point. 33 bytes base64 to 44 characters with NO padding;
 *     32 bytes give 44 characters ending in `=`. `ephemeral_key` therefore carries the type tag.
 *  2. **The counter is a protobuf varint, so `message_number` changes the ciphertext LENGTH.**
 *     Counter 127 costs one byte, counter 128 costs two. A decoy sized from a fixed formula is a
 *     byte short of a real message from the 128th onwards — and `message_number` rides in the
 *     cleartext, so the mismatch is checkable. [signalMessageBytes] encodes the real varint.
 *  3. **A first message is structurally larger than a JSON field count suggests.** A
 *     `PreKeySignalMessage` wraps the whole `SignalMessage` and adds `registration_id`,
 *     `pre_key_id`, `signed_pre_key_id`, a 33-byte base key and a 33-byte identity key. The
 *     overhead is not a constant either: all three ids are varints.
 *
 * ## Consistency between the cleartext fields and the bytes they describe
 *
 * A real envelope's `ephemeral_key` is a verbatim copy of the base key *inside* its ciphertext, its
 * `prekey_id` is the pre-key id inside it, and its `message_number` is the counter inside it. So the
 * decoy builds the blob first and reads those three cleartext fields back out of it, rather than
 * drawing them independently — three independent draws would agree with each other only by
 * accident, and anyone who parses the blob would see it.
 *
 * ## The one-time first envelope
 *
 * A real conversation's first envelope carries non-null `ephemeral_key` and `prekey_id`; every later
 * one has them null. The synthetic conversation shows the same shape, and the "exactly once" is
 * **derived from the counter rather than from a new durable flag**: the first envelope is the one
 * issued counter `0`, and `counterHighWater` already makes "the value 0 has been issued" durable and
 * unrepeatable (that is precisely what reader R2 of the U1 invariant table already assumes). U2
 * therefore adds no durable field, no writer, and no capacity cost.
 *
 * Residual, stated rather than hidden: an interrupted session can leave counter 0 reserved but
 * unspent, and the reservation contract SKIPS rather than reissues it — so such a vault's synthetic
 * conversation begins mid-chain with no first-message envelope. That is visible only to the relay,
 * which §1 of the spec already concedes sees everything here, and it is the cheaper residual: the
 * alternative is a new durable field in a fixed-size region, written on the send path.
 *
 * ## `previous_chain_length` / `previous_counter` are 0, and that is MEASURED real behaviour
 *
 * Android hardcodes the envelope field to 0 on every send (libsignal's Java API does not expose it),
 * so a decoy emitting anything else would be the outlier.
 *
 * The protobuf's own `previous_counter` is a different field and was measured rather than reasoned
 * about: libsignal writes **the last COUNTER of the previous sending chain, not its length**. A real
 * client whose X3DH first message was answered emits `previous_counter = 0` on its whole next chain,
 * because that chain carried exactly one message, at counter 0. That is the same value this builder
 * emits, and it makes the synthetic conversation one coherent story: one first message at counter 0,
 * one ratchet turn, then a single long chain. The chain's own counter 0 is never sent, which is
 * simply a skipped message — the thing a real ratchet does on any drop, and the same thing the
 * counter reservation does after an interrupted session.
 *
 * **Residual, for U4 and the spec rather than for this class:** that story stays coherent only while
 * the synthetic side's replies do not turn the ratchet again. A real client resets `message_number`
 * to 0 on every inbound ratchet turn, and this one never resets, by §2.3's deliberate choice. Once
 * U4 makes the exchange bidirectional, a relay comparing inbound and outbound can see a counter that
 * climbs through replies that should have reset it. That is a cleartext field, but it is
 * relay-visible only, and §1 concedes the relay in full.
 *
 * ## Fields the caller must supply because a constant would be the defect
 *
 * `ttl_seconds` and `burn_on_read` have no defaults here, deliberately. Pinning them
 * (`ttl_seconds: null, burn_on_read: false` on every decoy) is one of the three real distinguishers
 * in the existing web generator, and the fix is not a better constant but for the pairing unit to
 * mirror the message it is covering. `media_type` IS constant, and correctly so: every real send
 * path on this client — text, read receipt, attachment control payload — writes `"text"`, precisely
 * so the relay cannot separate them.
 *
 * ## Discipline
 *
 * No logging, no diagnostics sink, no device-level storage, no string resource, and nothing that
 * names a slot or a vault. `java.util.Base64` rather than `android.util.Base64` so every byte path
 * here is exercisable off-device; the two agree exactly for the flags the real path uses
 * (`NO_WRAP` is the RFC 4648 basic alphabet, padded, no line breaks), and the test pins the produced
 * alphabet and padding rather than assuming it.
 */
class DecoyEnvelopeBuilder(
    private val counters: DecoyCounterReservation,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Instant = Instant::now,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * The sender-side facts a real ciphertext carries in its first message. All three are public or
     * already visible to the relay; none is secret, and none is stored by this class.
     *
     * [identityKeySerialized] is libsignal's 33-byte `IdentityKey.serialize()` form — `0x05` type
     * tag plus the raw 32-byte public key that `SignalProtocolManager.localIdentityPublicKeyBytes()`
     * returns. It is the SENDER's, not the recipient's: a `PreKeySignalMessage` identifies its
     * author so the receiver can complete X3DH. [registrationId] is likewise the sender's own
     * (measured, not assumed — see the test).
     */
    class Sender(
        val accountId: String,
        val registrationId: Int,
        val identityKeySerialized: ByteArray,
    ) {
        init {
            require(accountId.isNotEmpty()) { "sender account id must not be empty" }
            require(registrationId >= 0) { "registration id must not be negative" }
            require(
                identityKeySerialized.size == KEY_SERIALIZED_BYTES &&
                    identityKeySerialized[0] == KEY_TYPE_DJB,
            ) {
                "identity key must be libsignal's $KEY_SERIALIZED_BYTES-byte serialize() form"
            }
        }
    }

    /**
     * One cover-traffic envelope addressed to [syntheticAccountId], sized to [blockCount] padded
     * blocks and mirroring [ttlSeconds] / [burnOnRead] from the message it covers.
     *
     * Spends one counter value. **A throw means nothing was sent and nothing was issued** — it
     * propagates [DecoyCounterReservation.next]'s contract unchanged: a reservation that could not
     * be made durable issues no value, and the caller must not fabricate one.
     */
    fun build(
        sender: Sender,
        syntheticAccountId: String,
        blockCount: Int,
        ttlSeconds: Int?,
        burnOnRead: Boolean,
    ): MessageEnvelope {
        require(blockCount >= 1) { "a cover envelope carries at least one padded block" }
        require(syntheticAccountId.isNotEmpty()) { "recipient account id must not be empty" }
        require(ttlSeconds == null || ttlSeconds > 0) { "ttl must be positive when present" }

        // Every argument check above runs BEFORE the counter is spent, so a caller's bad argument
        // costs no counter value: the reservation only ever skips, never reuses, but a skip bought
        // by a programming error is still a durable write nobody needed. The range check below is
        // the one that cannot be hoisted — it is a fact about the value just issued.
        val issued = counters.next()
        require(issued <= Int.MAX_VALUE) { "counter space exhausted" }
        val counter = issued.toInt()

        val blob: ByteArray
        val ephemeralKey: ByteArray?
        val preKeyId: Int?
        val inner = signalMessageBytes(counter = counter, blockCount = blockCount)
        if (counter == FIRST_COUNTER) {
            val baseKey = typeTaggedRandomKey()
            blob = preKeySignalMessageBytes(
                preKeyId = DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID,
                baseKey = baseKey,
                identityKey = sender.identityKeySerialized,
                registrationId = sender.registrationId,
                signedPreKeyId = DecoyIdentity.SIGNED_PREKEY_ID,
                inner = inner,
            )
            // Read back out of the blob rather than reusing the local, so the two can never
            // disagree even if the layout above changes.
            ephemeralKey = blob.copyOfRange(baseKeyOffset(), baseKeyOffset() + KEY_SERIALIZED_BYTES)
            check(ephemeralKey.contentEquals(baseKey)) { "base key offset does not match the layout" }
            preKeyId = DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID
        } else {
            blob = inner
            ephemeralKey = null
            preKeyId = null
        }

        return MessageEnvelope(
            id = newMessageId(),
            senderId = sender.accountId,
            recipientId = syntheticAccountId,
            ciphertext = encode(blob),
            ephemeralKey = ephemeralKey?.let { encode(it) },
            preKeyId = preKeyId,
            messageNumber = counter,
            // Hardcoded 0 on every real Android send — libsignal's Java API does not expose the
            // previous chain length. Emitting anything else is what would stand out.
            previousChainLength = PREVIOUS_CHAIN_LENGTH,
            timestamp = DateTimeFormatter.ISO_INSTANT.format(clock()),
            ttlSeconds = ttlSeconds,
            burnOnRead = burnOnRead,
            mediaType = MessageEnvelope.MEDIA_TEXT,
        )
    }

    // -- wire shaping ------------------------------------------------------------------------
    //
    // The two message bodies, byte-for-byte as libsignal 0.46.0 serializes them. Field numbers and
    // ordering are from the measured output of a real SessionCipher (see the test, which asserts
    // the real bytes still have this layout rather than trusting these comments).

    /**
     * A `SignalMessage`: version byte, protobuf {1 ratchet key, 2 counter, 3 previous counter,
     * 4 ciphertext}, then an 8-byte truncated MAC.
     *
     * The ciphertext field is `blockCount` padded blocks plus the AEAD tag, matching what a real
     * `SessionCipher.encrypt` of a [MessagePadding]-padded plaintext produces.
     */
    private fun signalMessageBytes(counter: Int, blockCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION_BYTE)
        writeKeyField(out, TAG_MESSAGE_RATCHET_KEY, typeTaggedRandomKey())
        out.write(TAG_MESSAGE_COUNTER)
        writeVarint(out, counter)
        out.write(TAG_MESSAGE_PREVIOUS_COUNTER)
        writeVarint(out, PREVIOUS_COUNTER)
        val bodyLength = blockCount * MessagePadding.BLOCK_BYTES + AEAD_TAG_BYTES
        out.write(TAG_MESSAGE_CIPHERTEXT)
        writeVarint(out, bodyLength)
        out.write(randomBytes(bodyLength))
        out.write(randomBytes(MAC_BYTES))
        return out.toByteArray()
    }

    /**
     * A `PreKeySignalMessage`: version byte, then protobuf {1 pre-key id, 2 base key,
     * 3 identity key, 4 the whole SignalMessage, 5 registration id, 6 signed pre-key id}.
     * There is no MAC of its own — the inner message carries it.
     */
    private fun preKeySignalMessageBytes(
        preKeyId: Int,
        baseKey: ByteArray,
        identityKey: ByteArray,
        registrationId: Int,
        signedPreKeyId: Int,
        inner: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION_BYTE)
        out.write(TAG_PREKEY_ID)
        writeVarint(out, preKeyId)
        writeKeyField(out, TAG_PREKEY_BASE_KEY, baseKey)
        writeKeyField(out, TAG_PREKEY_IDENTITY_KEY, identityKey)
        out.write(TAG_PREKEY_MESSAGE)
        writeVarint(out, inner.size)
        out.write(inner)
        out.write(TAG_PREKEY_REGISTRATION_ID)
        writeVarint(out, registrationId)
        out.write(TAG_PREKEY_SIGNED_PREKEY_ID)
        writeVarint(out, signedPreKeyId)
        return out.toByteArray()
    }

    /**
     * Byte offset of the base key's VALUE inside a `PreKeySignalMessage` built above: the version
     * byte, the pre-key id field, then this field's own tag and length byte.
     */
    private fun baseKeyOffset(): Int =
        1 + 1 + varintLength(DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID) + 1 + 1

    private fun writeKeyField(out: ByteArrayOutputStream, tag: Int, key: ByteArray) {
        out.write(tag)
        out.write(KEY_SERIALIZED_BYTES)
        out.write(key)
    }

    /** `0x05 ‖ random(32)` — libsignal's `ECPublicKey.serialize()` shape. */
    private fun typeTaggedRandomKey(): ByteArray {
        val key = ByteArray(KEY_SERIALIZED_BYTES)
        random.nextBytes(key)
        key[0] = KEY_TYPE_DJB
        return key
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** The counter value whose envelope carries the X3DH first-message shape. */
        const val FIRST_COUNTER: Int = 0

        /** Hardcoded on every real Android send — see the class kdoc. */
        const val PREVIOUS_CHAIN_LENGTH: Int = 0

        /** The protobuf `previous_counter`, 0 on a sending chain that never ratcheted. */
        private const val PREVIOUS_COUNTER = 0

        /**
         * libsignal's message version byte: the message version in the high nibble, the current
         * ciphertext version in the low nibble. Measured from real 0.46.0 output.
         */
        internal const val VERSION_BYTE: Int = 0x34

        /** `ECPublicKey.serialize()` — a type tag plus a 32-byte Curve25519 point. */
        internal const val KEY_SERIALIZED_BYTES: Int = 33

        /** libsignal's DJB (Curve25519) key type tag. */
        internal const val KEY_TYPE_DJB: Byte = 0x05

        /** AES-CBC/HMAC AEAD expansion over the padded plaintext. */
        internal const val AEAD_TAG_BYTES: Int = 16

        /** Truncated HMAC appended to a serialized `SignalMessage`. */
        internal const val MAC_BYTES: Int = 8

        // protobuf field tags = (field number << 3) | wire type
        private const val TAG_MESSAGE_RATCHET_KEY = 0x0A
        private const val TAG_MESSAGE_COUNTER = 0x10
        private const val TAG_MESSAGE_PREVIOUS_COUNTER = 0x18
        private const val TAG_MESSAGE_CIPHERTEXT = 0x22
        private const val TAG_PREKEY_ID = 0x08
        private const val TAG_PREKEY_BASE_KEY = 0x12
        private const val TAG_PREKEY_IDENTITY_KEY = 0x1A
        private const val TAG_PREKEY_MESSAGE = 0x22
        private const val TAG_PREKEY_REGISTRATION_ID = 0x28
        private const val TAG_PREKEY_SIGNED_PREKEY_ID = 0x30

        /** Base-128 varint, low group first, continuation bit set on every group but the last. */
        internal fun writeVarint(out: ByteArrayOutputStream, value: Int) {
            require(value >= 0) { "varint values are non-negative here" }
            var remaining = value
            while (remaining and 0x7F.inv() != 0) {
                out.write((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            out.write(remaining)
        }

        internal fun varintLength(value: Int): Int {
            require(value >= 0) { "varint values are non-negative here" }
            var length = 1
            var remaining = value ushr 7
            while (remaining != 0) {
                length++
                remaining = remaining ushr 7
            }
            return length
        }
    }
}
