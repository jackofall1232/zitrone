// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

/**
 * A DECRYPTED message. Lives ONLY in memory (see [MessageRepository]) —
 * plaintext is never written to disk, never logged, never serialized.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val text: String,
    val isMine: Boolean,
    /** Epoch millis when composed/received. */
    val timestampMs: Long,
    /** Self-destruct TTL in seconds; null means the message does not expire. */
    val ttlSeconds: Int?,
    val burnOnRead: Boolean,
    /** Epoch millis of delivery — TTL countdown starts here (timer_starts: on_delivery). */
    val deliveredAtMs: Long? = null,
    val state: MessageState = MessageState.SENDING,
    /**
     * A sideloaded image/file when this message carries an attachment; null for
     * a plain text message. The decrypted bytes live ONLY here, in memory —
     * exactly like [text] (see [MessageRepository]'s no-disk rule).
     */
    val attachment: MessageAttachment? = null,
    /**
     * A control payload from a newer client that this build can't parse (see
     * [AttachmentControlPayload.isControlPayload]). Rendered as a generic
     * "unsupported message" placeholder — NEVER as [text], which may carry key
     * material. When true, [text] is left empty.
     */
    val unsupported: Boolean = false,
)

/** Whether an attachment's decrypted bytes are in hand yet. */
enum class AttachmentLoadState {
    /** Redeeming + decrypting the blob (incoming, first display). */
    LOADING,
    /** Bytes present in memory ([MessageAttachment.bytes] non-null). */
    LOADED,
    /** Blob expired, already redeemed, or failed verification — persistent. */
    UNAVAILABLE,
}

/**
 * An image or file attachment. The decrypted [bytes] are in-memory only and
 * never persisted; they are decoded straight into a Bitmap for images or
 * exported on an explicit user Save for files. Metadata comes from the
 * (encrypted) control payload; [bytes] is populated after the blob is redeemed
 * and verified (or on the sender's own copy, immediately).
 */
data class MessageAttachment(
    /** [AttachmentControlPayload.KIND_IMAGE] or KIND_FILE. */
    val kind: String,
    val mimetype: String,
    /** Display filename for files; null for images (metadata minimization). */
    val filename: String?,
    /** Plaintext byte length (pre-padding). */
    val size: Int,
    val caption: String?,
    val loadState: AttachmentLoadState,
    /** Decrypted bytes — non-null only when [loadState] is LOADED. */
    val bytes: ByteArray? = null,
    /**
     * Reveal-and-burn state for a RECEIVED image. Received images render covered
     * (no pixels on screen) until the recipient taps to reveal; the reveal arms a
     * hard 10s timer ([MessageRepository.IMAGE_REVEAL_MS]) after which the image
     * re-covers and the message burns on BOTH ends (reusing the ordinary
     * `message.burn` path). False = covered (default); true = revealed and
     * counting down. Meaningless for sent images and files.
     */
    val revealed: Boolean = false,
)

enum class MessageState {
    /** Composed and handed to the socket; no relay/peer acknowledgement yet. */
    SENDING,
    /** The relay acknowledged storing the envelope (`message.stored`) — one tick. */
    SENT,
    /** The recipient acknowledged receipt (`message.delivered`) — two ticks; sender TTL starts here. */
    DELIVERED,
    READ,
    /** Burn animation in flight — particles dissolving upward. */
    BURNING,
    /**
     * The send did not reach the relay — the blob upload threw, or the socket
     * was down when we tried to hand the envelope off. Terminal until the user
     * taps retry (which flips it back to [SENDING]); see
     * [MessageRepository.retryable]. Honest: we never paint a tick for a message
     * the relay never got.
     */
    FAILED,
}

data class Conversation(
    val id: String,
    /** Routing UUID of the contact — never shown to other users directly. */
    val contactId: String,
    /** Optional display name; not used for routing. */
    val displayName: String,
    /** Base64 identity key of the contact, for safety-number verification. */
    val contactIdentityKeyBase64: String? = null,
    /**
     * Base64 identity key exchanged OUT OF BAND (scanned/pasted contact QR).
     * When present it is pinned: if the relay's prekey bundle later returns a
     * different identity key, that is a key-substitution attempt — the session
     * is refused and [keyChanged] is raised. Null for contacts added by bare
     * UUID/link (no key to pin — trust-on-first-use).
     */
    val pinnedIdentityKeyBase64: String? = null,
    val verified: Boolean = false,
    val keyChanged: Boolean = false,
    val unreadCount: Int = 0,
    val lastActivityMs: Long = 0L,
)
