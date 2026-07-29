// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * LOCAL-ONLY, IN-MEMORY storage of decrypted messages.
 *
 * Plaintext never touches disk: there is no database, no file cache, and the
 * process dying takes every decrypted message with it — by design, for an
 * ephemeral messenger. Enforces:
 *
 *  - TTL: countdown starts at delivery (timer_starts: on_delivery); when the
 *    timer fires the message burns locally (particle animation, then removal).
 *  - Burn-on-read: the first read starts a [BURN_ON_READ_DELAY_MS] grace
 *    window so the recipient can actually read the message, THEN destroys it
 *    and notifies the caller so a `message.burn` signal reaches the other
 *    side via WebSocket. The burn arriving at the sender doubles as the read
 *    confirmation for these messages, so the delay is deliberate design, not
 *    slack: burn time ≈ read time + the grace window.
 *
 * Hit concurrently from the main thread (read marks out of the chat screen)
 * and coroutine dispatchers (WS delivery, peer receipts, TTL and read-burn
 * timers) — every state mutation is a single atomic CAS, and guarded
 * transitions carry their guard INTO the CAS (see [update]) so racing
 * writers can neither lose updates nor double-fire a transition.
 */
class MessageRepository(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())

    /** conversationId -> ordered messages. */
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val ttlJobs = ConcurrentHashMap<String, Job>()
    private val sendTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val readBurnJobs = ConcurrentHashMap<String, Job>()
    private val revealJobs = ConcurrentHashMap<String, Job>()

    /** Invoked when a message burns (read or TTL) so the WS layer can signal it. */
    var onMessageBurned: ((Message) -> Unit)? = null

    fun conversationMessages(conversationId: String): List<Message> =
        _messages.value[conversationId].orEmpty()

    fun addOutgoing(message: Message) {
        upsert(message)
        scheduleSendTimeout(message)
    }

    /** Incoming messages are delivered the moment they arrive. */
    fun addIncoming(message: Message) {
        val delivered = message.copy(
            state = MessageState.DELIVERED,
            deliveredAtMs = message.deliveredAtMs ?: clock(),
        )
        upsert(delivered)
        scheduleTtl(delivered)
    }

    /**
     * The relay stored our envelope (`message.stored`) — advance to SENT (one
     * tick, "the relay has it"). Guarded to SENDING inside the CAS: monotonic,
     * so an out-of-order stored ack can never downgrade a message that already
     * reached DELIVERED/READ, and it can never resurrect a BURNING/removed or
     * FAILED message.
     */
    fun markSent(messageId: String) {
        update(
            messageId,
            // FAILED is accepted so a real receipt can HEAL a false failure (0.10.1 review round 1,
            // both lenses). A relay-attributed error can mark a send FAILED; if the relay then says
            // it stored that very message, the receipt is the ground truth and the error was a lie,
            // a duplicate, or stale. Before this, FAILED was terminal against receipts, so a single
            // spurious error left a STORED message displayed as failed forever and a retry
            // double-delivered it. Healing forward is strictly more honest than latching a failure
            // the relay itself contradicts.
            precondition = {
                it.state == MessageState.SENDING || it.state == MessageState.FAILED
            },
            transform = { it.copy(state = MessageState.SENT) },
        )
        // HYGIENE PLUS RACE-SAFETY, and the two are NOT the same thing. In the common path this
        // cancel is what stops the timer; under concurrency it can LOSE the race (the job may
        // already be past its delay and about to run), and then the SENDING-only CAS in the timer
        // body is the last line. Each masks the other under single mutation — deleting either
        // alone changed no observable state, and only deleting BOTH failed a test. That redundancy
        // is deliberate defence-in-depth rather than an accident, and it is recorded here because
        // the sweep cannot otherwise tell the two apart on a single-threaded virtual clock.
        cancelSendTimeout(messageId)
    }

    /**
     * The recipient acknowledged receipt (`message.delivered`) — advance to
     * DELIVERED (two ticks) and START THE SENDER-SIDE TTL HERE. This is the
     * honesty fix: the TTL used to start on ws-enqueue (false optimism — the
     * message might never arrive), and now starts on the real, peer-originated
     * delivery receipt. Incoming messages still start their TTL on arrival
     * ([addIncoming], unchanged).
     *
     * Guarded inside the CAS: allows SENDING→DELIVERED directly (a lost
     * `message.stored` must not block DELIVERED) and SENT→DELIVERED, but is
     * monotonic — it will not regress READ→DELIVERED on an out-of-order frame,
     * nor resurrect a BURNING/removed or FAILED message. scheduleTtl only fires
     * on the one real transition (update returns non-null), so a duplicate
     * receipt cannot double-arm the timer.
     */
    fun markDelivered(messageId: String) {
        val updated = update(
            messageId,
            precondition = {
                // FAILED accepted for the same reason as [markSent] (0.10.1 review round 1): a
                // delivery receipt contradicts an earlier error outright, and the receipt wins.
                it.state == MessageState.SENDING || it.state == MessageState.SENT ||
                    it.state == MessageState.FAILED
            },
            transform = {
                it.copy(state = MessageState.DELIVERED, deliveredAtMs = it.deliveredAtMs ?: clock())
            },
        )
        cancelSendTimeout(messageId)
        updated?.let(::scheduleTtl)
    }

    /**
     * The send never reached the relay (blob upload threw, or the socket was
     * down at hand-off) — flip to FAILED so the bubble shows "!" + retry rather
     * than a dishonest "sent". Guarded to the pre-delivery states (SENDING/SENT)
     * inside the CAS: a late failure signal can never overwrite a message that
     * actually reached DELIVERED/READ, nor resurrect a BURNING/removed one.
     * FAILED is terminal until [retryable].
     */
    fun markFailed(messageId: String) {
        update(
            messageId,
            precondition = {
                // LOCAL failures only — every caller is the device observing first-hand that the
                // send did not happen. A RELAY-attributed rejection does NOT come through here:
                // that is [markFailedByRelay], which is narrower (SENDING only) because an error
                // naming a message the relay already said it STORED is a claim we do not believe.
                //
                // An `isMine` clause was written here when this looked like the relay's entry point
                // and then REMOVED, because it was unreachable: `addIncoming` forces
                // `state = DELIVERED`, so no incoming message is ever SENDING/SENT and this line
                // already excludes every one of them. The mutation sweep proved it — deleting
                // `isMine` broke no test, including the test written for it, which was passing off
                // this check the whole time. An unreachable guard with a test that cannot fail is
                // worse than no guard. Note this is a property of the production call graph, not of
                // the type: `addOutgoing` would accept `isMine = false` at the default SENDING state.
                it.state == MessageState.SENDING || it.state == MessageState.SENT
            },
            transform = { it.copy(state = MessageState.FAILED) },
        )
        cancelSendTimeout(messageId)
    }

    /**
     * A **relay-attributed** rejection — `onServerError` carrying a `message_id` (0.10.1).
     *
     * **Deliberately narrower than [markFailed]: SENDING only, never SENT.** `SENT` means the relay
     * told us it stored this exact message; an error naming it afterwards contradicts the relay's
     * own receipt, and the receipt is the one of the two we should believe. Accepting SENT here let
     * a hostile lie, a duplicate frame, or a redeploy mismatch mark a STORED message failed — and
     * because the user's only recovery is retry-under-the-same-id, that produced a genuine double
     * delivery of a message that was never lost. Both review lenses found this independently in
     * round 1; it was strictly worse than the relay simply dropping the send, which at least leaves
     * an honest SENT.
     *
     * [markFailed] keeps the wider SENDING/SENT window because its callers are LOCAL failures — the
     * blob upload threw, the socket was down at hand-off — where the device knows first-hand that
     * the send did not happen and no relay claim is in play.
     */
    fun markFailedByRelay(messageId: String) {
        update(
            messageId,
            precondition = { it.state == MessageState.SENDING },
            transform = { it.copy(state = MessageState.FAILED) },
        )
        cancelSendTimeout(messageId)
    }

    /**
     * Arm a FAILED message for a user-initiated retry: flip it back to SENDING
     * and return it (with its retained in-memory [Message.text] /
     * [Message.attachment] bytes) so the coordinator can re-encrypt and re-send
     * under the SAME message id. Returns null when the message is not FAILED
     * (already sent, burned, or removed) so a stray retry tap is a no-op.
     */
    fun retryable(messageId: String): Message? =
        update(
            messageId,
            precondition = { it.state == MessageState.FAILED },
            transform = { it.copy(state = MessageState.SENDING) },
        )?.also {
            // A retry is a fresh send and gets a fresh timeout — otherwise the second attempt is
            // the very unbounded SENDING this release exists to remove, and it would be the more
            // likely one to hang, having already failed once.
            scheduleSendTimeout(it)
        }

    /**
     * Marks an incoming message read. Burn-on-read messages flip to READ
     * immediately but stay visible for [BURN_ON_READ_DELAY_MS] before the
     * burn fires (and notifies the peer) — see the class kdoc.
     *
     * @return true when THIS call transitioned a regular (non-burn) incoming
     *   message to READ — the one moment a read receipt should fire. Repeat
     *   calls, own messages, burning messages, and burn-on-read messages
     *   (whose burn signal IS the read confirmation) all return false.
     */
    fun markRead(messageId: String): Boolean {
        // isMine/burnOnRead are immutable per message — safe to route on a
        // snapshot read; the state transition itself is guarded in the CAS.
        val message = find(messageId) ?: return false
        if (message.isMine) return false
        if (message.burnOnRead) {
            scheduleReadBurn(messageId)
            return false
        }
        return update(
            messageId,
            precondition = { it.state != MessageState.BURNING && it.state != MessageState.READ },
            transform = { it.copy(state = MessageState.READ) },
        ) != null
    }

    /**
     * The redeemed attachment blob decrypted and verified — swap the in-memory
     * bytes into the placeholder bubble and flip it to LOADED. The bytes stay
     * in memory only, like every decrypted plaintext. No-op if the message
     * burned away or carries no attachment while the redeem was in flight.
     */
    fun attachmentLoaded(messageId: String, bytes: ByteArray) {
        update(
            messageId,
            precondition = { it.attachment != null },
            transform = {
                it.copy(
                    attachment = it.attachment!!.copy(
                        loadState = AttachmentLoadState.LOADED,
                        bytes = bytes,
                    ),
                )
            },
        )
    }

    /**
     * The blob is gone (expired, already redeemed, or failed verification) —
     * flip the placeholder to a persistent UNAVAILABLE state rather than
     * crashing or retrying. One-shot redemption means a lost blob never comes
     * back, so this is terminal.
     */
    fun attachmentUnavailable(messageId: String) {
        update(
            messageId,
            precondition = { it.attachment != null },
            transform = {
                it.copy(
                    attachment = it.attachment!!.copy(
                        loadState = AttachmentLoadState.UNAVAILABLE,
                        bytes = null,
                    ),
                )
            },
        )
    }

    /**
     * Reveal-and-burn for a RECEIVED image. Uncovers the image (pixels reach the
     * screen for the first time) and arms a HARD [IMAGE_REVEAL_MS] timer —
     * wall-clock, not idle-based. The timer runs on the repository scope, so it
     * survives Compose recomposition AND the app going to background; when it
     * fires the image re-covers and the message burns on BOTH ends via the
     * ordinary [burn] path (peer-notified with the same `message.burn` signal as
     * every other burn). Guarded so only a LOADED received image reveals and a
     * repeat tap inside the window is a no-op. If the process is killed while
     * backgrounded mid-reveal, the in-memory image dies with it (no disk) — at
     * least as safe as the burn it would have triggered.
     */
    fun revealAttachment(messageId: String) {
        if (revealJobs.containsKey(messageId)) return
        update(
            messageId,
            precondition = {
                !it.isMine &&
                    it.state != MessageState.BURNING &&
                    it.attachment != null &&
                    it.attachment.loadState == AttachmentLoadState.LOADED &&
                    it.attachment.kind == AttachmentControlPayload.KIND_IMAGE &&
                    !it.attachment.revealed
            },
            transform = { it.copy(attachment = it.attachment!!.copy(revealed = true)) },
        ) ?: return
        revealJobs[messageId] = scope.launch {
            delay(IMAGE_REVEAL_MS)
            // Drop our handle before burning so burn()'s reveal-job cancel can
            // never cancel the coroutine executing it.
            revealJobs.remove(messageId)
            // Re-cover first: the pixels are gone the instant the timer elapses,
            // even during the 600ms burn dissolve.
            update(
                messageId,
                precondition = { it.attachment != null },
                transform = { it.copy(attachment = it.attachment!!.copy(revealed = false)) },
            )
            burn(messageId, notifyPeer = true)
        }
    }

    /** The peer's read receipt arrived — flip our outgoing copy to READ. */
    fun onPeerRead(messageId: String) {
        update(
            messageId,
            precondition = {
                it.isMine && it.state != MessageState.BURNING && it.state != MessageState.READ
            },
            transform = { it.copy(state = MessageState.READ) },
        )
    }

    /**
     * Burns a message: flips it to BURNING so the UI plays the particle
     * dissolve (600ms, upward), then removes it permanently.
     */
    fun burn(messageId: String, notifyPeer: Boolean) {
        ttlJobs.remove(messageId)?.cancel()
        cancelSendTimeout(messageId)
        // A pending read-burn racing this burn (burn-all, remote burn, TTL)
        // must not fire a second burn after its grace window.
        readBurnJobs.remove(messageId)?.cancel()
        // A remote burn / TTL / burn-all racing an image reveal cancels the
        // pending reveal timer so it can't burn a second time after this one.
        revealJobs.remove(messageId)?.cancel()
        // Guard inside the CAS: racing burns (remote + local) win the flip
        // to BURNING exactly once, so the peer is never notified twice.
        val burning = update(
            messageId,
            precondition = { it.state != MessageState.BURNING },
            transform = { it.copy(state = MessageState.BURNING) },
        ) ?: return
        if (notifyPeer) onMessageBurned?.invoke(burning)
        scope.launch {
            // Let the particle dissolve finish before the message ceases to
            // exist (matches ui.theme.Motion.DurationDramaticMs — 600ms).
            delay(BURN_ANIMATION_MS)
            remove(messageId)
        }
    }

    /** Burns every message in a conversation (the "burn all" header action). */
    fun burnAll(conversationId: String, notifyPeer: Boolean = true) {
        conversationMessages(conversationId)
            .filter { it.state != MessageState.BURNING }
            .forEach { burn(it.id, notifyPeer) }
    }

    /** Remote side destroyed a message — mirror it locally, no echo back. */
    fun onRemoteBurn(messageId: String) {
        burn(messageId, notifyPeer = false)
    }

    /** Wipes everything decrypted from memory (logout / session revoked). */
    fun clearAll() {
        ttlJobs.values.forEach(Job::cancel)
        ttlJobs.clear()
        readBurnJobs.values.forEach(Job::cancel)
        readBurnJobs.clear()
        revealJobs.values.forEach(Job::cancel)
        revealJobs.clear()
        _messages.value = emptyMap()
    }

    // -----------------------------------------------------------------------

    /**
     * Burn-on-read, phase one: the message is READ (visible, counting down),
     * and the actual burn — including the peer notification that acts as the
     * read confirmation — fires after the grace window.
     */
    private fun scheduleReadBurn(messageId: String) {
        if (readBurnJobs.containsKey(messageId)) return
        update(
            messageId,
            precondition = { it.state != MessageState.BURNING && it.state != MessageState.READ },
            transform = { it.copy(state = MessageState.READ) },
        ) ?: return
        readBurnJobs[messageId] = scope.launch {
            delay(BURN_ON_READ_DELAY_MS)
            // Drop our own handle BEFORE burning so burn()'s cancellation of
            // pending read-burns can never cancel the job executing it.
            readBurnJobs.remove(messageId)
            burn(messageId, notifyPeer = true)
        }
    }

    /**
     * Arm the send timeout for an outgoing message that is still awaiting the relay's
     * `message.stored` (0.10.1 review round 1, item 2 — maintainer chose the timeout).
     *
     * **Why this exists at all.** A rejection the relay cannot attribute to a message — and the
     * relay checks its send budget BEFORE parsing the envelope, so `rate_limited` frequently
     * carries no id — used to leave the bubble on SENDING with no way out: only FAILED is
     * clickable in the UI and this store is RAM-only, so nothing short of process death recovered
     * it. This closes that hole **without depending on the relay at all**, which also makes it the
     * only recovery that survives a relay rollback or a client talking to an older deployment.
     *
     * **It times the relay's RECEIPT, never delivery.** Once a message reaches SENT the relay has
     * it, and it may then sit for days while the peer is offline — that is normal and must never
     * be failed. So the timer is armed on SENDING, cancelled the moment anything moves the message
     * off SENDING, and fires through a SENDING-only CAS so a receipt that wins the race no-ops it.
     *
     * **A timeout that fires early is self-correcting**, which is what lets the window stay
     * relatively tight: if the `message.stored` arrives afterwards, [markSent] accepts FAILED and
     * heals the bubble forward. Erring early costs a bubble that briefly shows "!"; erring long
     * costs a user staring at a spinner for a send that is already dead.
     */
    private fun scheduleSendTimeout(message: Message) {
        if (!message.isMine || message.state != MessageState.SENDING) return
        sendTimeoutJobs.remove(message.id)?.cancel()
        sendTimeoutJobs[message.id] = scope.launch {
            delay(SEND_TIMEOUT_MS)
            update(
                message.id,
                // SENDING only: SENT means the relay acknowledged and this timer is moot; FAILED,
                // DELIVERED, BURNING or removed all mean something else already decided.
                precondition = { it.state == MessageState.SENDING },
                transform = { it.copy(state = MessageState.FAILED) },
            )
            sendTimeoutJobs.remove(message.id)
        }
    }

    /** The send is no longer awaiting a receipt — disarm. Idempotent. */
    private fun cancelSendTimeout(messageId: String) {
        sendTimeoutJobs.remove(messageId)?.cancel()
    }

    private fun scheduleTtl(message: Message) {
        val ttlSeconds = message.ttlSeconds ?: return
        val deliveredAt = message.deliveredAtMs ?: return
        if (ttlJobs.containsKey(message.id)) return
        val expiresAt = deliveredAt + ttlSeconds * 1000L
        ttlJobs[message.id] = scope.launch {
            val wait = expiresAt - clock()
            if (wait > 0) delay(wait)
            // TTL enforced both sides — each side burns locally on its own
            // clock, so no peer notification is needed here.
            burn(message.id, notifyPeer = false)
        }
    }

    /**
     * Whether [messageId] is still live in RAM (not TTL-burned/removed). Used by the
     * coordinator's owed post-ack settling to skip a phantom notification / a blob redemption
     * whose placeholder is gone.
     */
    fun exists(messageId: String): Boolean = find(messageId) != null

    private fun find(messageId: String): Message? =
        _messages.value.values.asSequence().flatten().firstOrNull { it.id == messageId }

    private fun upsert(message: Message) {
        _messages.update { current ->
            val list = current[message.conversationId].orEmpty()
            val existing = list.indexOfFirst { it.id == message.id }
            current.toMutableMap().apply {
                put(
                    message.conversationId,
                    if (existing >= 0) {
                        list.toMutableList().also { it[existing] = message }
                    } else {
                        list + message
                    },
                )
            }
        }
    }

    /**
     * Atomically finds and transforms one message when [precondition] holds —
     * a single CAS loop over the state map, so writers on different threads
     * can neither lose each other's updates nor double-fire a guarded
     * transition (e.g. two racing burns both notifying the peer). Both
     * lambdas may re-run on CAS contention and must stay pure. Returns the
     * transformed message, or null when it is missing or the precondition
     * rejected it.
     */
    private fun update(
        messageId: String,
        precondition: (Message) -> Boolean = { true },
        transform: (Message) -> Message,
    ): Message? {
        var applied: Message? = null
        _messages.update { current ->
            applied = null
            val conversationId = current.entries
                .firstOrNull { (_, list) -> list.any { it.id == messageId } }
                ?.key
                ?: return@update current
            val list = current.getValue(conversationId)
            val index = list.indexOfFirst { it.id == messageId }
            val message = list[index]
            if (!precondition(message)) return@update current
            val transformed = transform(message)
            applied = transformed
            current.toMutableMap().apply {
                put(conversationId, list.toMutableList().also { it[index] = transformed })
            }
        }
        return applied
    }

    private fun remove(messageId: String) {
        cancelSendTimeout(messageId)
        ttlJobs.remove(messageId)?.cancel()
        revealJobs.remove(messageId)?.cancel()
        _messages.update { current ->
            current.mapValues { (_, list) -> list.filterNot { it.id == messageId } }
        }
    }

    /**
     * Immediately drop a message with no burn animation and no peer signal.
     * Used when an outbound send is abandoned because its contact was deleted
     * mid-send: the envelope was never deposited, so the local plaintext (and
     * any attachment bytes) must not linger in the repository either.
     */
    fun discard(messageId: String) = remove(messageId)

    companion object {
        /** Duration of the burn particle dissolve before hard removal. */
        const val BURN_ANIMATION_MS = 600L

        /**
         * How long a burn-on-read message stays readable after it is first
         * seen. The window is the read time — burning at first render gave
         * the recipient zero time to read anything.
         */
        const val BURN_ON_READ_DELAY_MS = 5_000L

        /**
         * How long a RECEIVED image stays revealed after the recipient taps it,
         * before it re-covers and burns on both ends. A HARD wall-clock window
         * (not idle-reset): backgrounding the app does not pause it.
         */
        const val IMAGE_REVEAL_MS = 10_000L

        /**
         * How long an outgoing message may sit awaiting the relay's `message.stored` before it is
         * called failed (0.10.1). **This times the RELAY'S RECEIPT, not delivery** — a message the
         * relay has taken can wait indefinitely for an offline peer without being failed.
         *
         * 90 s is chosen for the slowest transport we support rather than the fastest: a fresh Tor
         * circuit or an I2P tunnel can take tens of seconds to establish before the first frame
         * moves at all, and failing a send that was merely slow is the worse error — the user
         * retries and the peer gets it twice. It can afford to be this generous precisely because
         * a stuck bubble is now bounded at all, which it previously was not, and because an early
         * fire self-corrects: [markSent] accepts FAILED, so a late `message.stored` heals it.
         */
        const val SEND_TIMEOUT_MS = 90_000L
    }
}
