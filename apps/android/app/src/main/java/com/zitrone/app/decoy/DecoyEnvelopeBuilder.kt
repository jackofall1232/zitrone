// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.MessagePadding
import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.net.WsClient
import org.signal.libsignal.protocol.ecc.Curve
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Base64
import java.util.UUID

/**
 * Builds one cover-traffic envelope whose `message.send` frame is **the same number of bytes** as
 * the real envelope it covers, and whose every relay-readable field is populated the way the real
 * send path populates it.
 *
 * ## The interface is the security property
 *
 * [build] takes **the real [MessageEnvelope] it is covering** and mirrors it. That is not a
 * convenience; it is the fix for the defect the first version of this class shipped. An earlier
 * signature took only a block count, and derived the envelope's SHAPE from the decoy's own counter
 * — so a real X3DH first message (976 B frame) could be paired with an ordinary decoy (829 B), and
 * the observer read off which frame was real from the size alone. A block count cannot carry shape,
 * counter magnitude, timestamp width or TTL width, and no care inside this class can compensate for
 * an input that lacks them. So the input is the envelope itself, and the last thing [build] does is
 * **measure both frames and throw if they differ** — the property is enforced, not hoped for.
 *
 * Nothing about the covered envelope's CONTENT is copied: the builder reads its ciphertext's
 * base64 LENGTH (it never decodes it), its shape, and the metadata a real decoy must mirror anyway
 * (`ttl_seconds`, `burn_on_read`, `media_type`, `previous_chain_length`, `version`).
 *
 * ## What this class is, and what it deliberately is not
 *
 * It is a **shaper**, not a crypto path. The ciphertext is random bytes laid out in exactly the wire
 * form libsignal produces (spec §2.3), and **no session is ever established** for the synthetic
 * peer: `SessionBuilder.process` would write a durable ratchet session into this vault's real
 * `signalRecords`, a cost the §4 capacity budget does not cover, to buy an observable that random
 * bytes satisfy identically. This class now has **no access to a vault at all** — no
 * `VaultRuntime`, no store, no counter allocator — so "writes nothing durable" is a fact about its
 * type rather than a fact a test has to keep re-checking.
 *
 * ## "Indistinguishable" is a claim about BYTES, so the shape is measured, not modelled from prose
 *
 * Every length rule below was measured against real libsignal 0.46.0 output, and
 * `DecoyEnvelopeBuilderTest` re-measures on every run: it encrypts genuine padded plaintext through
 * a real `SessionCipher`, wraps it in the production [MessageEnvelope] exactly as
 * `MessagingCoordinator` does, frames it with the production [WsClient.messageSendFrame], and
 * asserts the cover frame matches byte count for byte count. An estimate that is a few bytes out is
 * not a near miss here — it is a perfect one-field discriminator, because base64 turns a length
 * difference into a visible `=`.
 *
 * Three facts that cost more than they look:
 *
 *  1. **A serialized public key is 33 bytes, not 32** — `ECPublicKey.serialize()` is a `0x05` type
 *     tag plus the 32-byte Curve25519 point. 33 bytes base64 to 44 characters with NO padding;
 *     32 bytes give 44 characters ending in `=`. `ephemeral_key` therefore carries the type tag.
 *  2. **The counter is a protobuf varint, so `message_number` changes the ciphertext LENGTH.**
 *     Counter 127 costs one byte, counter 128 costs two. Mirroring the covered envelope's counter
 *     makes that difference disappear by construction rather than by arithmetic.
 *  3. **A first message is structurally larger than a JSON field count suggests.** A
 *     `PreKeySignalMessage` wraps the whole `SignalMessage` and adds `registration_id`,
 *     `pre_key_id`, `signed_pre_key_id`, a 33-byte base key and a 33-byte identity key — 81 bytes of
 *     wrapper, +147 B on the frame. The overhead is not a constant either: all three ids are
 *     varints.
 *
 * ## Where the size differences are absorbed, and why it is the random body
 *
 * The cover blob is built to **exactly** the covered ciphertext's byte length, and the slack is
 * taken out of the random AEAD body. Two blob-internal fields cannot be mirrored and would
 * otherwise change the length: `signed_pre_key_id` (the covered message names the real peer's;
 * a cover message must name the synthetic account's own, which is 1) and `previous_counter` (the
 * last counter of the previous sending chain — mirroring it would mean parsing the real
 * ciphertext, which this class deliberately never does). Both are varints, so a width difference
 * of one to three bytes is possible; it is absorbed by the body.
 *
 * The consequence, stated rather than hidden, and recorded in spec §2.4: a real body is always
 * `blocks · 256 + 16` bytes, and an adjusted one is not, so **a relay that parses the blob could see
 * a body length that is not a padded-block multiple.** That is the right trade and the reason is the
 * threat model: a network observer sees only the total frame length and cannot see the split between
 * `ciphertext` and the other JSON fields, so "the body is a plausible block multiple" is
 * unobservable to the adversary this feature defends against, while "the frames are the same size"
 * is directly observable. §1 concedes the relay in full, for reasons far more fundamental than this
 * (cleartext `sender_id`/`recipient_id`). When the two conflict the observable one wins.
 *
 * In the common case there is nothing to absorb at all: a subsequent-shaped cover of a subsequent
 * real message with the same counter and a previous chain no longer than 127 messages lays out
 * byte-for-byte identically, and its body is exactly `blocks · 256 + 16`.
 *
 * ## Why the emitted counter mirrors the covered one instead of advancing monotonically
 *
 * **This is a deliberate reversal of the original design, forced by arithmetic, and it is the one
 * place this class knowingly departs from a written ruling — see spec §2.4.**
 *
 * `message_number` is a JSON *number*, so its DECIMAL width is part of the frame: `5` and `128`
 * differ by two bytes. The instruction was to absorb that difference in the random ciphertext's
 * length. **It cannot be done.** Base64 encodes three bytes to four characters, so a base64 field's
 * length is always a multiple of four — on both sides. Whatever byte length the cover blob is given,
 * the two `ciphertext` fields therefore differ by a multiple of four, and a difference of one, two
 * or three bytes in any other field is unreachable. The only byte-granular knob in the envelope is
 * the decimal width of a numeric field, and `message_number` is the only numeric field that is not
 * pinned by mirroring.
 *
 * A monotonic decoy counter cannot be made to match an arbitrary real counter's width: it can be
 * skipped forward, never back, and real counters reset to 0 on every inbound ratchet turn while a
 * monotonic one climbs forever. So "monotonic decoy counter" and "frames are the same size" are
 * mutually exclusive, and the observable one wins.
 *
 * Mirroring costs less than it looks like it does. §2.3's justification for monotonicity was that
 * "a `message_number` that resets or regresses is a tell a real ratchet can never produce" — but
 * §2.4 of the same document already concedes the opposite: **a real client resets `message_number`
 * to 0 on every inbound ratchet turn**, and a monotonic counter that never resets was itself the
 * declared residual. A mirrored counter reproduces a real conversation's counter sequence exactly,
 * which is the sequence a real ratchet does produce. What it gives up is uniqueness: the synthetic
 * conversation repeats counter values across the covered conversation's ratchet turns, which a
 * relay that tracks the synthetic conversation over time could notice. Relay-visible only.
 *
 * Consequence for U1, **now settled (2026-07-27)**: `DecoyCounterReservation` had no consumer on the
 * paired path, and its only other candidate was the dead-air ping — the one decoy with no envelope to
 * mirror, which therefore had to invent a counter. **The ping was cut** (spec §3.0), so the allocator
 * and `TAG_DECOY.counterHighWater` were deleted rather than left as an unreachable writer on a
 * durable vault surface. Nothing in the decoy path allocates a counter any more: this class reads one
 * off the envelope it covers, and that is the whole mechanism.
 *
 * ## Consistency between the cleartext fields and the bytes they describe
 *
 * A real envelope's `ephemeral_key` is a verbatim copy of the base key *inside* its ciphertext, its
 * `prekey_id` is the pre-key id inside it, and its `message_number` is the counter inside it. So the
 * decoy builds the blob first and reads `ephemeral_key` back out of it rather than drawing it
 * independently — two independent draws would agree only by accident, and anyone who parses the blob
 * would see it. Every cover envelope is internally consistent; the alternative (absorbing the
 * decimal-width difference by letting the cleartext counter disagree with the blob's) would have
 * made every single envelope self-inconsistent to one parse, which is a far louder tell than a
 * repeated counter across a conversation.
 *
 * ## The synthetic keys are GENERATED, not random bytes
 *
 * `ephemeral_key` and the ratchet key are real `Curve.generateKeyPair()` publics with the private
 * half dropped. `0x05 ‖ random(32)` — what this class used to emit — is **not a valid Curve25519
 * public-key encoding**: a genuine one always has bit 255 of the point clear, and random bytes set
 * it about half the time, so roughly half of all cover envelopes carried a structurally impossible
 * key. Generating a real keypair is canonical by construction, which is stronger than masking the
 * one bit that was measured and hoping the rest of the distribution matches. (The private halves are
 * dropped to GC and cannot be wiped — the same libsignal residue `DecoyIdentity`'s kdoc documents at
 * length, and for the same reason: `ECPrivateKey` exposes no destructor.)
 *
 * ## `previous_chain_length` is mirrored, and 0 is what a real send emits
 *
 * Android hardcodes the envelope field to 0 on every send (libsignal's Java API does not expose it),
 * so mirroring the covered envelope's value is both correct and future-proof.
 *
 * ## Fields the caller must not be allowed to pin
 *
 * `ttl_seconds`, `burn_on_read` and `media_type` all come from the covered envelope. Pinning them
 * (`ttl_seconds: null, burn_on_read: false` on every decoy) is one of the three real distinguishers
 * in the existing web generator, and the fix is not a better constant but mirroring.
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
     * (measured, not assumed — see the test), and is range-checked to the interval the real
     * generators emit: `SignalProtocolManager.ensureIdentity` and `DecoyIdentity.generateIdentity`
     * both draw `random.nextInt(16380) + 1`, so `0` is off-distribution and fails closed here.
     */
    class Sender(
        val accountId: String,
        val registrationId: Int,
        val identityKeySerialized: ByteArray,
    ) {
        init {
            require(accountId.isNotEmpty()) { "sender account id must not be empty" }
            require(registrationId in REGISTRATION_IDS) {
                "registration id must be in $REGISTRATION_IDS, the interval the real generator emits"
            }
            require(
                identityKeySerialized.size == KEY_SERIALIZED_BYTES &&
                    identityKeySerialized[0] == KEY_TYPE_DJB,
            ) {
                "identity key must be libsignal's $KEY_SERIALIZED_BYTES-byte serialize() form"
            }
        }
    }

    /**
     * One cover-traffic envelope addressed to [syntheticAccountId], mirroring [cover] — the real
     * envelope this decoy is being sent alongside — in every property a passive observer can measure.
     *
     * The returned envelope's `message.send` frame is **exactly** as many bytes as [cover]'s. That
     * is asserted before returning: **a throw means no cover envelope could be built**, never a
     * silently mismatched one. The caller decides what to do about it; this class will not hand back
     * a decoy that would identify its partner.
     */
    fun build(
        sender: Sender,
        syntheticAccountId: String,
        cover: MessageEnvelope,
    ): MessageEnvelope {
        require(syntheticAccountId.isNotEmpty()) { "recipient account id must not be empty" }
        require(sender.accountId == cover.senderId) {
            "cover traffic is issued by the account that sent the envelope it covers"
        }
        require(syntheticAccountId.length == cover.recipientId.length) {
            "the synthetic recipient id must be the same width as the covered recipient id"
        }
        require((cover.ephemeralKey == null) == (cover.preKeyId == null)) {
            "a real envelope carries ephemeral_key and prekey_id together or not at all"
        }
        require(cover.messageNumber >= 0) { "message_number is never negative" }

        val target = base64DecodedLength(cover.ciphertext)
        require(target <= MAX_CIPHERTEXT_BYTES) {
            "covered ciphertext is $target B, past the $MAX_CIPHERTEXT_BYTES B ceiling"
        }

        val counter = cover.messageNumber
        val blob: ByteArray
        val ephemeralKey: ByteArray?
        val preKeyId: Int?
        val coveredKey = cover.ephemeralKey
        if (coveredKey != null) {
            require(coveredKey.length == KEY_BASE64_CHARS) {
                "a real ephemeral_key is $KEY_BASE64_CHARS base64 characters"
            }
            val id = coverPreKeyId(requireNotNull(cover.preKeyId))
            val baseKey = coverPublicKey()
            val innerSize = lengthPrefixedPayload(
                target - preKeyWrapperFixedBytes(id, sender.registrationId),
            )
            val inner = signalMessageBytes(counter, bodyLengthFor(innerSize, counter))
            check(inner.size == innerSize) { "inner message sizing does not close" }
            blob = preKeySignalMessageBytes(
                preKeyId = id,
                baseKey = baseKey,
                identityKey = sender.identityKeySerialized,
                registrationId = sender.registrationId,
                signedPreKeyId = DecoyIdentity.SIGNED_PREKEY_ID,
                inner = inner,
            )
            // Read back out of the blob rather than reusing the local, so the two can never
            // disagree even if the layout above changes.
            val at = baseKeyOffset(id)
            ephemeralKey = blob.copyOfRange(at, at + KEY_SERIALIZED_BYTES)
            check(ephemeralKey.contentEquals(baseKey)) { "base key offset does not match the layout" }
            preKeyId = id
        } else {
            blob = signalMessageBytes(counter, bodyLengthFor(target, counter))
            ephemeralKey = null
            preKeyId = null
        }
        check(blob.size == target) {
            "cover ciphertext is ${blob.size} B where the covered one is $target B"
        }

        val decoy = MessageEnvelope(
            id = newMessageId(),
            senderId = sender.accountId,
            recipientId = syntheticAccountId,
            ciphertext = encode(blob),
            ephemeralKey = ephemeralKey?.let { encode(it) },
            preKeyId = preKeyId,
            messageNumber = counter,
            previousChainLength = cover.previousChainLength,
            timestamp = timestampAsWideAs(cover.timestamp),
            ttlSeconds = cover.ttlSeconds,
            burnOnRead = cover.burnOnRead,
            mediaType = cover.mediaType,
            version = cover.version,
        )
        // The whole point of the class, checked rather than argued: same frame, to the byte.
        val built = sendFrameLength(decoy)
        val covered = sendFrameLength(cover)
        check(built == covered) { "cover frame is $built B where the covered frame is $covered B" }
        return decoy
    }

    // -- sizing ------------------------------------------------------------------------------

    /**
     * The random AEAD body length that makes a `SignalMessage` at [counter] come to exactly
     * [messageSize] bytes.
     *
     * Fails closed rather than emitting a differently-sized blob: a cover envelope that is not the
     * covered envelope's size is precisely the defect this class exists to prevent.
     */
    private fun bodyLengthFor(messageSize: Int, counter: Int): Int {
        val body = lengthPrefixedPayload(messageSize - signalMessageFixedBytes(counter))
        require(body >= MessagePadding.BLOCK_BYTES + AEAD_TAG_BYTES) {
            "a cover envelope carries at least one padded block; $body B is not one"
        }
        return body
    }

    /**
     * The payload length `n` for which `varint(n) ‖ n bytes` occupies exactly [total] bytes.
     *
     * Two totals in the whole Int range have no solution (129 and 16386, either side of a varint
     * step); no real ciphertext length reaches them, and they fail closed.
     */
    private fun lengthPrefixedPayload(total: Int): Int {
        for (width in 1..5) {
            val n = total - width
            if (n >= 0 && varintLength(n) == width) return n
        }
        throw IllegalArgumentException("no payload length occupies exactly $total bytes with its varint")
    }

    /** Everything in a `SignalMessage` except the ciphertext field's length varint and body. */
    private fun signalMessageFixedBytes(counter: Int): Int =
        1 + // version
            (1 + 1 + KEY_SERIALIZED_BYTES) + // ratchet key: tag, length, key
            (1 + varintLength(counter)) +
            (1 + varintLength(PREVIOUS_COUNTER)) +
            1 + // the ciphertext field's tag
            MAC_BYTES

    /** Everything in a `PreKeySignalMessage` except the inner message's length varint and bytes. */
    private fun preKeyWrapperFixedBytes(preKeyId: Int, registrationId: Int): Int =
        1 + // version
            (1 + varintLength(preKeyId)) +
            (1 + 1 + KEY_SERIALIZED_BYTES) + // base key
            (1 + 1 + KEY_SERIALIZED_BYTES) + // identity key
            1 + // the inner message field's tag
            (1 + varintLength(registrationId)) +
            (1 + varintLength(DecoyIdentity.SIGNED_PREKEY_ID))

    /**
     * The `prekey_id` a cover first message names.
     *
     * `prekey_id` is the RECIPIENT's consumed one-time prekey id, and the cover envelope's recipient
     * is this vault's own synthetic account, so the legitimate draw is the batch
     * [DecoyIdentity.ONE_TIME_PREKEY_IDS] that account uploaded (spec §2.2). The covered id is used
     * verbatim when it is in that batch, which it is for every id up to the batch size; otherwise
     * the widest in-batch id of the same DECIMAL width is used, because the field's decimal width is
     * part of the frame and no other field can absorb a difference in it.
     *
     * Residual, recorded in §2.4: a covered id of four or more digits has no in-batch counterpart at
     * all, and is then mirrored verbatim — an id the synthetic account never published. Relay-visible
     * only, and the relay can already see that the named id was never consumed there (nothing ever
     * fetches this account's bundle), which is the residual `DecoyIdentity.FIRST_ONE_TIME_PREKEY_ID`
     * already declares.
     */
    private fun coverPreKeyId(coveredPreKeyId: Int): Int {
        require(coveredPreKeyId >= 0) { "prekey ids are never negative" }
        if (coveredPreKeyId in DecoyIdentity.ONE_TIME_PREKEY_IDS) return coveredPreKeyId
        val width = coveredPreKeyId.toString().length
        return DecoyIdentity.ONE_TIME_PREKEY_IDS.lastOrNull { it.toString().length == width }
            ?: coveredPreKeyId
    }

    /**
     * A fresh timestamp that renders to the same NUMBER OF CHARACTERS as [covered].
     *
     * `DateTimeFormatter.ISO_INSTANT` trims trailing zeros in the fractional second, so real frames
     * already vary by up to four bytes on the timestamp alone. Both sides draw from the same clock,
     * so they usually agree — but "usually" is not a size guarantee, so the fractional width is
     * coerced to the covered envelope's when it does not. The VALUE is this builder's own clock, not
     * a copy: two envelopes carrying an identical timestamp would pair themselves for the relay.
     *
     * One residual: when the covered timestamp is a whole second (about one send in a thousand) the
     * coerced cover timestamp is this builder's own clock truncated to ITS second, which is the same
     * second whenever the two sends land inside one. That is relay-visible only, and the relay pairs
     * the two frames by arrival time regardless.
     */
    private fun timestampAsWideAs(covered: String): String {
        val now = clock()
        val direct = DateTimeFormatter.ISO_INSTANT.format(now)
        if (direct.length == covered.length) return direct
        val digits = fractionDigits(covered)
        val coerced = DateTimeFormatter.ISO_INSTANT.format(
            now.with(ChronoField.NANO_OF_SECOND, nanosRenderingAs(now.nano, digits).toLong()),
        )
        check(coerced.length == covered.length) {
            "cover timestamp is ${coerced.length} characters where the covered one is ${covered.length}"
        }
        return coerced
    }

    // -- wire shaping ------------------------------------------------------------------------
    //
    // The two message bodies, byte-for-byte as libsignal 0.46.0 serializes them. Field numbers and
    // ordering are from the measured output of a real SessionCipher (see the test, which asserts
    // the real bytes still have this layout rather than trusting these comments).

    /**
     * A `SignalMessage`: version byte, protobuf {1 ratchet key, 2 counter, 3 previous counter,
     * 4 ciphertext}, then an 8-byte truncated MAC.
     */
    private fun signalMessageBytes(counter: Int, bodyLength: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION_BYTE)
        writeKeyField(out, TAG_MESSAGE_RATCHET_KEY, coverPublicKey())
        out.write(TAG_MESSAGE_COUNTER)
        writeVarint(out, counter)
        out.write(TAG_MESSAGE_PREVIOUS_COUNTER)
        writeVarint(out, PREVIOUS_COUNTER)
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
    private fun baseKeyOffset(preKeyId: Int): Int = 1 + 1 + varintLength(preKeyId) + 1 + 1

    private fun writeKeyField(out: ByteArrayOutputStream, tag: Int, key: ByteArray) {
        out.write(tag)
        out.write(KEY_SERIALIZED_BYTES)
        out.write(key)
    }

    /**
     * A genuine Curve25519 public key in libsignal's `ECPublicKey.serialize()` form, with the
     * private half dropped.
     *
     * NOT `0x05 ‖ random(32)`: a real encoding always has bit 255 of the point clear, so random
     * bytes are an impossible encoding about half the time. Generating the key makes the whole
     * distribution right by construction rather than the one bit that happened to be measured.
     */
    private fun coverPublicKey(): ByteArray = Curve.generateKeyPair().publicKey.serialize()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** The protobuf `previous_counter`, 0 on a sending chain that never ratcheted. */
        private const val PREVIOUS_COUNTER = 0

        /**
         * The interval both real registration-id generators draw from
         * (`random.nextInt(16380) + 1`). `0` is off-distribution, so it fails closed.
         */
        internal val REGISTRATION_IDS: IntRange = 1..16_380

        /**
         * libsignal's message version byte: the message version in the high nibble, the current
         * ciphertext version in the low nibble. Measured from real 0.46.0 output.
         */
        internal const val VERSION_BYTE: Int = 0x34

        /** `ECPublicKey.serialize()` — a type tag plus a 32-byte Curve25519 point. */
        internal const val KEY_SERIALIZED_BYTES: Int = 33

        /** 33 bytes base64 to 44 characters with no padding. */
        internal const val KEY_BASE64_CHARS: Int = 44

        /** libsignal's DJB (Curve25519) key type tag. */
        internal const val KEY_TYPE_DJB: Byte = 0x05

        /** AES-CBC/HMAC AEAD expansion over the padded plaintext. */
        internal const val AEAD_TAG_BYTES: Int = 16

        /** Truncated HMAC appended to a serialized `SignalMessage`. */
        internal const val MAC_BYTES: Int = 8

        /**
         * Fail-closed ceiling on the covered ciphertext. Far above any real send (the largest
         * ordinary payload is an attachment control payload at two blocks) and small enough that no
         * length arithmetic here can overflow.
         */
        internal const val MAX_CIPHERTEXT_BYTES: Int = 1 shl 20

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

        /** The framed byte count of a `message.send` for [envelope] — the observable being matched. */
        internal fun sendFrameLength(envelope: MessageEnvelope): Int =
            WsClient.messageSendFrame(envelope).toString().toByteArray(Charsets.UTF_8).size

        /** Decoded byte count of a padded base64 string, WITHOUT decoding it. */
        internal fun base64DecodedLength(encoded: String): Int {
            require(encoded.length >= 4 && encoded.length % 4 == 0) {
                "a padded base64 field is a non-empty whole number of quanta"
            }
            val padding = encoded.takeLastWhile { it == '=' }.length
            require(padding <= 2) { "base64 padding is at most two characters" }
            return encoded.length / 4 * 3 - padding
        }

        /** Fractional-second digits in an ISO_INSTANT rendering: 0, 3, 6 or 9. */
        internal fun fractionDigits(timestamp: String): Int {
            val dot = timestamp.indexOf('.')
            if (dot < 0) return 0
            return timestamp.length - dot - 2 // the '.' itself and the trailing 'Z'
        }

        /**
         * A nano-of-second near [nano] that `ISO_INSTANT` renders with exactly [digits] fractional
         * digits. The formatter emits 0 digits for a whole second, 3 for a whole millisecond, 6 for
         * a whole microsecond, and 9 otherwise.
         */
        internal fun nanosRenderingAs(nano: Int, digits: Int): Int = when (digits) {
            0 -> 0
            3 -> (nano / 1_000_000).let { if (it == 0) 1 else it } * 1_000_000
            6 -> (nano / 1_000 * 1_000).let { if (it % 1_000_000 == 0) it + 1_000 else it }
            9 -> if (nano % 1_000 == 0) nano + 1 else nano
            else -> throw IllegalArgumentException("ISO_INSTANT renders 0, 3, 6 or 9 fractional digits, not $digits")
        }

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
