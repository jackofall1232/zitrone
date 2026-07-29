// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.decoy.DecoyEnvelopeBuilder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DecoyEnvelopeBuilder.buildReply` — the U4 send-back.
 *
 * The property that matters most here is the one R-U4-3 turns on: **a reply is always
 * established-session shape.** That is not a convenience. A prekey-shaped reply would have to carry
 * the synthetic account's `registration_id` inside the blob, which `DecoyState` does not persist —
 * so producing one would mean a new durable field, a `TAG_DECOY` format change and a §4.1
 * storage-format question. It is also what X3DH actually does: B answers A with a plain
 * `SignalMessage`, because B has the session by then.
 */
class DecoyReplyBuilderTest {

    private val builder = DecoyEnvelopeBuilder()

    private fun received(
        ciphertextBytes: Int = 400,
        ephemeralKey: String? = null,
        preKeyId: Int? = null,
        timestamp: String = "2026-07-28T10:00:00.123Z",
        ttlSeconds: Int = 86_400,
        burnOnRead: Boolean = false,
        mediaType: String = "text",
        version: String = "1",
    ) = MessageEnvelope(
        id = "cover-1",
        senderId = REAL,
        recipientId = SYNTHETIC,
        ciphertext = Base64.getEncoder().encodeToString(ByteArray(ciphertextBytes)),
        ephemeralKey = ephemeralKey,
        preKeyId = preKeyId,
        messageNumber = 7,
        previousChainLength = 3,
        timestamp = timestamp,
        ttlSeconds = ttlSeconds,
        burnOnRead = burnOnRead,
        mediaType = mediaType,
        version = version,
    )

    private fun reply(
        received: MessageEnvelope = received(),
        counter: Int = 0,
        from: String = SYNTHETIC,
        to: String = REAL,
    ) = builder.buildReply(
        replyingAccountId = from,
        recipientAccountId = to,
        received = received,
        counter = counter,
    )

    @Test
    fun `a reply is established-session shape even when the message it answers was a first message`() {
        // A prekey-shaped cover envelope: the real send it mirrored opened a session.
        val prekeyShaped = received(
            ciphertextBytes = 400,
            ephemeralKey = Base64.getEncoder().encodeToString(ByteArray(33).also { it[0] = 5 }),
            preKeyId = 42,
        )

        val reply = reply(prekeyShaped)

        assertNull("a reply never carries an ephemeral key", reply.ephemeralKey)
        assertNull("nor a consumed one-time prekey id", reply.preKeyId)
    }

    @Test
    fun `the reply's ciphertext is exactly as long as the one it answers`() {
        for (size in listOf(330, 592, 848, 1_106)) {
            val answered = received(ciphertextBytes = size)
            val decoded = Base64.getDecoder().decode(reply(answered).ciphertext)
            assertEquals("reply size must match for a $size B ciphertext", size, decoded.size)
        }
    }

    @Test
    fun `the reply is addressed from the synthetic account to the real one`() {
        val reply = reply()

        assertEquals(SYNTHETIC, reply.senderId)
        assertEquals(REAL, reply.recipientId)
    }

    @Test
    fun `the reply mirrors ttl, burn, media type and version`() {
        val answered = received(ttlSeconds = 3_600, burnOnRead = true, mediaType = "file", version = "2")

        val reply = reply(answered)

        assertEquals(3_600, reply.ttlSeconds)
        assertEquals(true, reply.burnOnRead)
        assertEquals("file", reply.mediaType)
        assertEquals("2", reply.version)
    }

    @Test
    fun `the reply's timestamp is the same width as the one it answers`() {
        // 0, 3, 6 or 9 fractional digits — the only widths ISO_INSTANT renders, which is what a
        // real envelope's timestamp can be. The builder refuses anything else rather than guessing.
        for (stamp in listOf(
            "2026-07-28T10:00:00Z",
            "2026-07-28T10:00:00.123Z",
            "2026-07-28T10:00:00.123456789Z",
        )) {
            val reply = reply(received(timestamp = stamp))
            assertEquals("width must match for $stamp", stamp.length, reply.timestamp.length)
        }
    }

    @Test
    fun `the reply carries the counter it was given`() {
        assertEquals(0, reply(counter = 0).messageNumber)
        assertEquals(5, reply(counter = 5).messageNumber)
    }

    @Test
    fun `each reply gets its own message id`() {
        assertTrue(reply().id != reply().id)
    }

    @Test
    fun `it refuses to reply on behalf of an account the envelope was not addressed to`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            reply(from = "acct-somebody-else")
        }
        assertTrue(e.message!!.contains("addressed to"))
    }

    @Test
    fun `it refuses empty account ids and a negative counter`() {
        assertThrows(IllegalArgumentException::class.java) { reply(from = "") }
        assertThrows(IllegalArgumentException::class.java) { reply(to = "") }
        assertThrows(IllegalArgumentException::class.java) { reply(counter = -1) }
    }

    @Test
    fun `it fails closed on a ciphertext too short to carry a padded block`() {
        // Rather than emitting a differently-shaped frame — the defect the builder exists to prevent.
        assertThrows(IllegalArgumentException::class.java) { reply(received(ciphertextBytes = 32)) }
    }

    private companion object {
        const val SYNTHETIC = "acct-synthetic-0001"
        const val REAL = "acct-real-00000001"
    }
}
