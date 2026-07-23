// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.Context
import android.util.Base64
import android.util.Log
import com.zitrone.app.crypto.AttachmentCrypto
import com.zitrone.app.crypto.MessagePadding
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultImageException
import com.zitrone.app.diagnostics.BootDiagnostics
import com.zitrone.app.data.AttachmentControlPayload
import com.zitrone.app.data.AttachmentLoadState
import com.zitrone.app.data.ControlPayload
import com.zitrone.app.data.Conversation
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.Message
import com.zitrone.app.data.MessageAttachment
import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.data.MessageRepository
import com.zitrone.app.data.MessageState
import com.zitrone.app.data.SettingsRepository
import com.zitrone.app.net.ApiClient
import com.zitrone.app.net.WsClient
import com.zitrone.app.notifications.NotificationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.DuplicateMessageException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Glue between crypto, transport and the in-memory repositories. This is the
 * ONLY place that touches plaintext between decryption and the UI — and it
 * never logs, persists, or transmits it.
 *
 * Network failures are swallowed silently into offline state: an error path
 * that logged envelope details would be a privacy bug, so there is nothing
 * to log by construction. Instead of failing dead, the boot sequence retries
 * on a capped backoff so a transient outage at unlock time can't strand the
 * account unregistered and offline forever (see [start]).
 *
 * The ONE exception to the no-logging rule is transport diagnostics: the
 * boot-stage markers in [bootstrapLoop], the socket-lifecycle lines in
 * [WsClient], and the send-path stage markers in [sendText] (e.g.
 * "firing POST /api/v1/register", "session minted", "X3DH session
 * established") plus the transport exception class/message on failure
 * (connect errors, HTTP status codes, certificate-pin mismatches). All of
 * these strings are compile-time constants or exception metadata — no
 * message content, keys, tokens, account ids, or envelope fields ever flow
 * through them, so nothing sensitive can leak. Without it, a
 * certificate-pinning failure or a dead relay is indistinguishable from
 * airplane mode — the app retries forever with no signal anywhere, client
 * or server (v1.5.3 shipped exactly that failure on the send path).
 *
 * Each such line goes to logcat AND to [BootDiagnostics] (an app-private,
 * capped, on-device file surfaced in Settings → Diagnostics), so a user with
 * no access to `adb` can still read and share the exact failure. See [diag].
 */
class MessagingCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val signal: SignalProtocolManager,
    private val api: ApiClient,
    private val ws: WsClient,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val diagnostics: BootDiagnostics,
    private val notificationScheduler: NotificationScheduler,
    /**
     * Vault-only atomic contact-delete (D2c). When non-null (the vault path), it removes the
     * contact's crypto records + roster entry + tombstone in ONE runtime.mutate + ONE durable
     * flush (VaultSignalProtocolStore atomicity contract :222-231) and returns the
     * [ContactDeleteOutcome] — DURABLE, APPLIED_UNCONFIRMED (removal sticks, flush pending), or
     * NOT_APPLIED (a closed-runtime race meant the removal never touched live state — the delete
     * did not take). [deleteContact] then burns messages and commits the in-memory removal. Null on
     * the legacy path, which keeps its unchanged per-store delete sequence.
     */
    private val vaultContactDelete: (suspend (conversationId: String, contactId: String, at: Long) -> ContactDeleteOutcome)? = null,
    /**
     * Flush-before-ack barrier (D2c — absorbs D4). Invoked on the inbound path AFTER a decrypt
     * has advanced the receiving ratchet and BEFORE [WsClient.ackMessage], so the relay's copy is
     * dropped ONLY once that ratchet advance is durable. On the vault path the SessionContainer
     * supplies [com.zitrone.app.crypto.vault.VaultRuntime.flushBeforeAck]; the default no-op keeps
     * every non-vault construction / test (and the pre-decrypt drop-ack, which mutates nothing)
     * acking immediately as before. A THROW (NotDurable / IO / runtime closed / at-capacity) means
     * NOT durable: the ack is skipped, the relay redelivers, and no acked message is ever lost.
     * Called from the confined worker, never inside a persist sink — so the runtime lock order
     * (runtime.stateLock → session → storage) is preserved.
     */
    private val flushBeforeAck: suspend () -> Unit = {},
) : WsClient.Listener {

    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()

    /**
     * True while the app is unlocked and EXPECTS to be connected — set in
     * [start] and cleared only on an intentional teardown ([stop],
     * [onSessionRevoked], [deleteAccountAndWipe]). Combined with the raw socket
     * state it keeps the UI showing "connecting" (never a silent, dead
     * "offline") whenever we intend to be online but the socket is momentarily
     * down and WsClient is retrying.
     */
    private val _linking = MutableStateFlow(false)

    /** High-level connectivity for the UI: boot supervisor + socket combined. */
    enum class Connectivity { OFFLINE, CONNECTING, ONLINE }

    val connectivity: StateFlow<Connectivity> =
        combine(ws.connectionState, _linking) { wsState, linking ->
            when (wsState) {
                WsClient.ConnectionState.CONNECTED -> Connectivity.ONLINE
                WsClient.ConnectionState.CONNECTING -> Connectivity.CONNECTING
                WsClient.ConnectionState.DISCONNECTED ->
                    if (linking) Connectivity.CONNECTING else Connectivity.OFFLINE
            }
        }.stateIn(scope, SharingStarted.Eagerly, Connectivity.OFFLINE)

    /**
     * Set when the server revokes our session — UI returns to the lock gate.
     * @Volatile: written on the main thread, invoked from OkHttp callback threads.
     */
    @Volatile
    var onForcedLogout: (() -> Unit)? = null

    /**
     * Single-flight guard: only one boot/relink sequence runs at a time.
     * @Volatile: read/written from the main thread and OkHttp callback threads.
     */
    @Volatile
    private var linkJob: Job? = null

    /**
     * Delivery gate. Cleared synchronously the instant a session is torn down
     * ([onSessionRevoked]/[stop]/[deleteAccountAndWipe]) and set on [start].
     * An [onMessageDeliver] coroutine can be parked at [withSessionLock] (behind
     * a send holding the mutex across a network prekey fetch) when teardown
     * fires; when it later resumes it must NOT add a message or arm a
     * notification for a session that is gone. Re-checked right before the
     * publish, so no delivery that resumes after teardown can post an alert or
     * re-arm the reminder scheduler past a logout. @Volatile: written on the
     * socket-callback/main threads, read on the confined dispatcher.
     */
    @Volatile
    private var acceptingDeliveries = false

    /**
     * One mutex per contact serializes every Double Ratchet operation on
     * that session — text sends, receipt sends, and inbound decrypts all run
     * on pooled dispatcher threads, and two operations advancing the same
     * session concurrently would each persist from the same snapshot: a
     * forked ratchet, duplicate counters, and a peer that can no longer
     * decrypt. Entries are never evicted; a Mutex is tiny and the contact
     * set is small.
     */
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withSessionLock(contactId: String, block: suspend () -> T): T =
        sessionLocks.getOrPut(contactId) { Mutex() }.withLock { block() }

    /**
     * Single-worker confinement for ALL coordinator coroutines. Every
     * [scope].launch below runs on this dispatcher, so no two coordinator
     * coroutines ever execute in parallel — their state mutations (roster,
     * message repository, Signal store, typing set, and the [deleteContact]
     * sequence) can only interleave at explicit suspension points.
     *
     * That is the property the post-round-2 epoch guards were emulating by hand
     * and getting wrong under a multi-threaded dispatcher: with confinement, any
     * "check the contact still exists → mutate" tail written **without a
     * suspension in the middle** is atomic with respect to a concurrent
     * [deleteContact], so a delete can never slip between the check and the
     * publish. Blocking work that must not stall this one worker (the network
     * prekey fetch; nothing else) suspends off it as usual. The crypto teardown
     * in [deleteContact] deliberately runs ON this worker (a background IO-pool
     * thread, never main) as a short, non-suspending local commit, so it is
     * mutually exclusive with any same-contact encrypt/decrypt rather than
     * racing them across threads — which is why deletion needs no session lock
     * and cannot be stalled behind an in-flight send's network fetch.
     *
     * IO (not Default) because this worker performs blocking disk commits
     * (EncryptedSharedPreferences); `limitedParallelism(1)` still gives the
     * single-worker confinement guarantee.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.IO.limitedParallelism(1)

    /**
     * Whether [contactId] is still a live roster entry. Used by the send/deliver
     * publish tails: a send is always to an existing conversation, so a `false`
     * here means the contact was torn down mid-send and nothing may be deposited
     * or published for it.
     */
    private fun contactExists(contactId: String): Boolean =
        conversations.findByContact(contactId) != null

    /**
     * Whether [contactId] was explicitly deleted (within the straggler window)
     * and has NOT since been re-added — the inbound guard. Backed by the
     * PERSISTED tombstone in [conversations], so it holds across a process
     * restart (an app update forces one) for as long as a straggler could still
     * be sitting on the relay. True only for a genuine deleted-contact straggler:
     * never for a first-time inbound sender (never deleted) nor for a re-added
     * contact (a live roster entry again).
     */
    private fun isDeletedContact(contactId: String): Boolean =
        conversations.wasRecentlyDeleted(contactId) && !contactExists(contactId)

    /**
     * Read receipts awaiting a live socket, keyed by contact. Queued when the
     * hand-off fails (socket down) and flushed on the next CONNECTED
     * transition: the underlying messages are already READ locally, so they
     * will never re-enter [onMessagesSeen] — without this queue the sender
     * would stay at "delivered" forever. In-memory only, like the messages
     * themselves.
     */
    private val pendingReceipts = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * Post-ack side effects (delivery receipt / notification / attachment redemption) a display
     * branch still OWES for a shown-but-not-yet-acked envelope — see [PendingPostAckLedger].
     * Every display branch registers its owed entry immediately after
     * [MessageRepository.addIncoming], and [settlePostAck] is the SINGLE execution site, called on
     * whichever path finally lands the durable ack: the normal branch, or the
     * duplicate-redelivery ACK_AND_DROP path.
     */
    private val pendingPostAck = PendingPostAckLedger()

    /**
     * Execute + clear the owed post-ack side effects for [envelopeId]. Call ONLY after a DURABLE
     * ack ([ackDurable] returned true). Same order as the pre-round-7 inline code: receipt,
     * notification, redemption. Settling is an atomic remove, so the normal path and the
     * duplicate path can never both run the effects for one envelope.
     */
    private fun settlePostAck(envelopeId: String) {
        // Teardown gate (round 8): the duplicate path can land a durable ack from a coroutine
        // parked across a revocation/logout — the ack itself is correct (the advance IS durable),
        // but no side effect may fire after teardown. Claim + DISCARD the entry; stop() also
        // clears the ledger, this covers the already-queued race.
        if (!acceptingDeliveries) {
            pendingPostAck.settle(envelopeId)
            return
        }
        pendingPostAck.settle(envelopeId)?.let { owed ->
            // Delivery receipt to the SENDER (peer-routed by the relay → their
            // message.delivered). senderId comes from the decrypted envelope; the relay never
            // stored it, preserving zero-knowledge. Best-effort: a dropped receipt just means
            // the sender stays at SENT, never worse. Sent even for a since-burned message —
            // it WAS displayed, so DELIVERED is the truthful sender state.
            if (owed.sendReceipt) ws.sendReceived(envelopeId, owed.senderId)
            // Staleness gate (round 8): a duplicate can land the durable ack long after display
            // (offline gap) — if the message has since TTL-burned out of RAM, a "New message"
            // alert would be a phantom and the redeemed bytes would have no placeholder to land
            // in ([MessageRepository.attachmentLoaded] keys on the message), so both are skipped.
            if (!messages.exists(envelopeId)) return
            // Content-free notification: always just "New message". The scheduler
            // rate-limits + re-fires it per conversation.
            if (owed.notify) notificationScheduler.onIncomingMessage(owed.conversationId)
            // One-shot blob redemption — this settling is what keeps it reachable when the
            // durable ack only lands on the duplicate path (round 7, Codex :1237).
            owed.attachment?.let { redeemAttachment(envelopeId, it) }
        }
    }

    init {
        ws.listener = this
        // Local burns (burn-on-read / burn-all) propagate to the other side.
        // The server routes the burn by peer_id, so resolve the conversation's
        // contact; a burn for an already-removed conversation has no peer to
        // notify and is dropped.
        messages.onMessageBurned = { message ->
            conversations.find(message.conversationId)?.let { conversation ->
                ws.burnMessage(message.id, conversation.contactId)
            }
        }
        // Re-send read receipts that missed a dead socket whenever the
        // connection comes (back) up.
        scope.launch(confined) {
            ws.connectionState.collect { state ->
                if (state == WsClient.ConnectionState.CONNECTED) flushPendingReceipts()
            }
        }
    }

    /**
     * Boot sequence: identity -> registration (first run) -> challenge-signed
     * session -> WebSocket. Safe to call repeatedly (single-flight), safe to
     * fail offline. Retries the whole sequence on a capped exponential backoff
     * until it succeeds, so registration and connection come up automatically
     * once the relay is reachable — no manual user action, ever.
     *
     * Also used to re-authenticate after [onAuthExpired]: with an account
     * already registered, the loop skips registration and just mints a fresh
     * session + socket.
     */
    @Synchronized
    fun start() {
        if (linkJob?.isActive == true) return
        _linking.value = true
        acceptingDeliveries = true
        linkJob = scope.launch(confined) { bootstrapLoop() }
    }

    private suspend fun bootstrapLoop() {
        // One-time prekeys are generated (and persisted) at most ONCE and reused
        // across register retries: regenerating per attempt would orphan a
        // signed prekey + a full batch into the encrypted store on every failed
        // register. Identity generation is idempotent and stays inside the loop,
        // so a transient keystore hiccup retries instead of dead-ending the loop
        // with nothing scheduled to recover it.
        var registration: (suspend () -> Unit)? = null
        var attempt = 0
        while (coroutineContext.isActive && _linking.value) {
            // Boot-stage marker for the diagnostic log in onFailure below.
            // Stage names only — never data.
            var stage = "ensure-identity"
            val ok = runCatching {
                signal.ensureIdentity()
                if (api.accountId == null) {
                    if (registration == null) {
                        stage = "generate-prekeys"
                        // Reuse a stored-but-unconfirmed signed prekey / one-time batch from a
                        // PREVIOUS attempt (process death between store and register) before
                        // generating fresh — the pending-upload markers make the retry idempotent
                        // instead of orphaning private halves per attempt (round 8).
                        val signedPreKey = signal.pendingSignedPreKeyUpload() ?: signal.generateSignedPreKey()
                        val oneTimePreKeys = signal.generateOneTimePreKeys()
                        registration = suspend {
                            api.register(
                                identityKeyBase64 = signal.localIdentityPublicKeyBase64(),
                                registrationId = signal.localRegistrationId(),
                                signedPreKey = signedPreKey,
                                oneTimePreKeys = oneTimePreKeys,
                            )
                            // register() returns the new account id; the loop
                            // only needs its Unit side effect (accountId stored).
                            Unit
                        }
                    }
                    // NOTE: if the register POST reaches the server but the
                    // response is lost (process death mid-flight), accountId is
                    // never stored and a retry mints a second, orphaned account
                    // (public keys only). The client-side null-guard +
                    // single-flight prevents the common case, not this window.
                    stage = "register"
                    // Prekey durability barrier (D2c round 7). ensureIdentity (above) + the signed
                    // prekey + the one-time prekeys just STORED their PRIVATE halves in the vault
                    // (coalesced, ≤2s). Reseal them DURABLE BEFORE api.register publishes their
                    // PUBLIC halves: were a crash to roll the privates back after the relay already
                    // serves a bundle whose private half we no longer hold, that peer's first (X3DH)
                    // message would be permanently undecryptable. On a non-durable flush do NOT
                    // publish — throw so this boot attempt fails and the loop retries (a later flush
                    // that lands then registers). Routes through the SAME injected flushBeforeAck as
                    // the inbound/outbound barriers (no hard vault dep).
                    if (!flushBeforePreKeyPublish {
                            diag("boot[$attempt]: prekey reseal not durable — register deferred to retry")
                        }
                    ) {
                        throw PreKeyFlushNotDurableException()
                    }
                    diag("boot[$attempt]: firing POST /api/v1/register")
                    registration?.invoke()
                    // The relay now holds the public halves — retire both pending-upload markers
                    // (losing this confirm just re-uploads the same records, idempotent).
                    signal.confirmPreKeysUploaded()
                    diag("boot[$attempt]: registration accepted by server")
                }
                stage = "create-session"
                val tokens = api.createSession(signal::signLoginChallenge)
                stage = "ws-connect"
                // Use the freshly-minted token directly rather than reading it
                // back through api.accessToken — that getter decrypts from
                // EncryptedSharedPreferences (Android Keystore) on every call,
                // and the return value is already non-null.
                ws.connect(tokens.accessToken)
            }.onFailure { e ->
                // A cancelled boot (normal teardown via stop()/logout) surfaces
                // here as CancellationException; rethrow it so structured
                // cancellation propagates and we don't log a false "failed"
                // line for an expected shutdown.
                if (e is CancellationException) throw e
                // Transport diagnostics only. The exception class + message is
                // what discriminates the failure: SSLPeerUnverifiedException
                // ("Certificate pinning failure!" — OkHttp lists the served
                // SPKI hashes next to the pinned ones) points at a pin
                // rotation; SSLHandshakeException / "no cipher suites in
                // common" / a TLS-version complaint points at the TLS-1.3-only
                // ConnectionSpec vs. the server's negotiation; Connect/
                // UnknownHost points at the relay simply being unreachable.
                // ApiException.responseBody, when present, is the server's
                // {"error": "<code>"} schema-validation reason (e.g.
                // "bad_identity_key") — the single most useful line for
                // diagnosing a register/session 400 without a second machine.
                val bodySuffix = (e as? ApiClient.ApiException)?.responseBody
                    ?.let { " server_error=$it" }
                    .orEmpty()
                diag(
                    "boot[$attempt]: failed at stage=$stage: " +
                        "${e.javaClass.name}: ${e.message}$bodySuffix",
                )
            }.isSuccess
            if (ok) {
                // ws.connect() only enqueues the socket open; the real
                // CONNECTED/DISCONNECTED transition (and any /ws handshake
                // failure) is delivered asynchronously via ws.connectionState,
                // which drives the UI connectivity badge — NOT observed here.
                // So this marks the boot chain reaching a live session and
                // handing the socket off, not a confirmed-open socket.
                diag("boot[$attempt]: session minted, socket handshake handed off")
                // Reaching a live socket IS success. Signed-prekey rotation is
                // best-effort and must NOT fail the boot — a failed upload here
                // would otherwise tear down the healthy socket on the next
                // iteration. WsClient owns socket-level reconnects from here;
                // auth expiry comes back through onAuthExpired().
                runCatching {
                    signal.rotateSignedPreKeyIfNeeded()?.let { rotated ->
                        // Prekey durability barrier (see the register path): the rotation just STORED
                        // the new signed prekey's PRIVATE half — reseal it DURABLE before publishing
                        // its PUBLIC half. On a non-durable flush do NOT upload. The retry is REAL
                        // (round 8): generation marks the id upload-pending, and
                        // rotateSignedPreKeyIfNeeded re-serves that stored record on every boot
                        // until the confirm below retires it — the age gate alone would never
                        // retry (createdAt was already bumped at generation).
                        if (flushBeforePreKeyPublish {
                                diag("boot: signed-prekey reseal not durable — rotation upload skipped, retries next boot")
                            }
                        ) {
                            api.uploadPreKeys(emptyList(), rotated)
                            signal.confirmSignedPreKeyUploaded()
                        }
                    }
                }
                return
            }
            // Delay from the CURRENT attempt (0-based) so the first retry waits
            // the 1s base, not 2s — then advance (matches WsClient's backoff).
            delay(min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl min(attempt, MAX_BACKOFF_SHIFT)))
            attempt += 1
        }
    }

    fun stop() {
        _linking.value = false
        acceptingDeliveries = false
        linkJob?.cancel()
        ws.disconnect()
        // Teardown hook: drop all pending re-fire jobs + fire state so nothing
        // carries across an identity switch (see NotificationScheduler).
        notificationScheduler.cancelAll()
        // Owed post-ack side effects die with the session: a receipt, notification, or blob
        // redemption must never fire for a locked/logged-out/burned account, and nothing
        // carries across an identity switch (see PendingPostAckLedger).
        pendingPostAck.clear()
    }

    /**
     * Emit one privacy-safe boot-diagnostic line to BOTH logcat (when `adb` is
     * available) and the on-device [BootDiagnostics] file (Settings →
     * Diagnostics, for when it isn't). Callers must pass only fixed stage
     * strings + exception metadata — never user data. See the class kdoc.
     */
    private fun diag(line: String) {
        Log.w(TAG, line)
        diagnostics.record(line)
    }

    /**
     * Durable-ack barrier for the inbound path: reseal the ratchet advance ([flushBeforeAck])
     * BEFORE telling the relay to drop its copy ([WsClient.ackMessage]). Used only on delivery
     * branches where a decrypt advanced the receiving ratchet. Returns true when the ack was sent
     * (flush confirmed durable); returns false when the flush threw — the message is left UN-ACKED
     * so the relay redelivers it (flush-before-ack window=0, zero acked loss). Runs on the confined
     * worker (never inside a persist sink), so touching the runtime here respects the lock order.
     * Delegates to [flushThenAck] so the ordering + fail-closed decision is host-testable without a
     * live socket.
     */
    private suspend fun ackDurable(envelopeId: String): Boolean =
        flushThenAck(
            envelopeId = envelopeId,
            flush = flushBeforeAck,
            ack = { ws.ackMessage(it) },
            onNotDurable = {
                // NotDurable / IO / runtime closed or at-capacity (IllegalStateException): the
                // ratchet advance did NOT reach disk. No envelope field is ever logged.
                diag("recv: durable flush failed before ack — inbound left un-acked (relay redelivers)")
            },
        )

    /**
     * Durable barrier BEFORE publishing generated prekeys' PUBLIC halves (D2c round 7). The private
     * halves — identity ([SignalProtocolManager.ensureIdentity]), signed prekey, and one-time prekeys
     * — were just generated + STORED in the vault (coalesced reseal, ≤2s). Reseal them DURABLE via the
     * injected [flushBeforeAck] and report whether it confirmed; the caller uploads the public halves
     * (api.register / api.uploadPreKeys) ONLY when this returns true. On a non-durable flush the
     * publics are NOT uploaded, so a crash can never roll the privates back while the relay already
     * serves a bundle whose private half we no longer hold (→ a peer's first X3DH message permanently
     * undecryptable). Delegates to [flushSendRatchet] — the SAME injected-barrier, transient-retry,
     * fail-closed decision the outbound send path uses (host-tested there) — so no new vault dependency
     * enters the coordinator. Runs on the confined worker, never inside a persist sink (lock order).
     */
    private suspend fun flushBeforePreKeyPublish(onNotDurable: () -> Unit): Boolean =
        flushSendRatchet(flush = flushBeforeAck, onNotDurable = onNotDurable)

    // Standard base64 WITH padding (NO_WRAP keeps the `=` pad, strips only line
    // breaks) — the wire format the control payload's length-validated fields
    // and the blob store both expect, matching the web/desktop client.
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    /**
     * Encrypt-then-send. X3DH session is established lazily on first send.
     *
     * Send-path stages mirror the boot loop's diagnostics: stage markers on
     * the (rare) first-message session setup, and stage + exception metadata
     * on any failure. Before this, every failure here was swallowed silently
     * by the runCatching — a dead prekey fetch or a failed X3DH looked
     * identical to the user simply never having tapped send.
     */
    fun sendText(conversation: Conversation, text: String, ttlSeconds: Int?, burnOnRead: Boolean) {
        scope.launch(confined) {
            deliverText(
                conversation = conversation,
                messageId = UUID.randomUUID().toString(),
                text = text,
                ttlSeconds = ttlSeconds,
                burnOnRead = burnOnRead,
                existing = false,
            )
        }
    }

    /**
     * Encrypt + hand off one text message under a fixed [messageId]. Shared by
     * the initial [sendText] ([existing] = false, adds the local bubble on a
     * successful encrypt) and [retry] ([existing] = true, the bubble is already
     * on screen and was just flipped back to SENDING).
     *
     * Honesty change (see [MessageState]): a successful ws-enqueue no longer
     * marks the message delivered — it merely means the socket accepted the
     * bytes, not that the relay stored them or the peer received them. The
     * message STAYS in SENDING until the relay's `message.stored` (→ SENT) and
     * the recipient's peer-routed `message.delivered` (→ DELIVERED, TTL start)
     * arrive. A dead socket (ws.sendMessage == false) or a crypto/transport
     * throw flips it to FAILED so the bubble shows "!" + retry rather than a
     * false tick. markFailed on an id whose bubble was never added (an encrypt
     * throw before addOutgoing) is a harmless no-op.
     */
    private suspend fun deliverText(
        conversation: Conversation,
        messageId: String,
        text: String,
        ttlSeconds: Int?,
        burnOnRead: Boolean,
        existing: Boolean,
    ) {
        val accountId = api.accountId ?: return
        // Stage marker for the diagnostic log in onFailure below.
        // Stage names only — never data.
        var stage = "check-session"
        runCatching {
            // Session establishment + encrypt hold the per-contact lock so
            // a concurrent receipt send can't fork the ratchet.
            val encrypted = withSessionLock(conversation.contactId) {
                if (!signal.hasSession(conversation.contactId)) {
                    stage = "fetch-prekey-bundle"
                    diag("send: no session — firing GET prekey bundle")
                    val bundle = api.fetchPreKeyBundle(conversation.contactId)
                    // The prekey fetch suspended; a deleteContact may have landed
                    // in the meantime. Do NOT establish a session or re-upsert
                    // (which would resurrect) a contact that is no longer in the
                    // roster — this is the non-suspending re-check the confinement
                    // model relies on, right before the resurrecting mutation.
                    if (!contactExists(conversation.contactId)) {
                        diag("send: contact deleted during prekey fetch — send aborted")
                        return@withSessionLock null
                    }
                    val pinned = conversation.pinnedIdentityKeyBase64
                    if (pinned != null && pinned != bundle.identityKeyBase64) {
                        // The relay returned a different identity key than the
                        // one exchanged out of band (contact QR). That is a
                        // key-substitution attempt — refuse to establish the
                        // session or send, and raise the warning badge instead
                        // of silently trusting the relay's key.
                        diag("send: identity key mismatch — send refused, warning raised")
                        conversations.flagIdentityMismatch(conversation.contactId)
                        return@withSessionLock null
                    }
                    stage = "establish-session"
                    signal.establishSession(conversation.contactId, bundle)
                    diag("send: X3DH session established")
                    conversations.upsert(
                        conversation.copy(contactIdentityKeyBase64 = bundle.identityKeyBase64),
                    )
                }
                stage = "encrypt"
                // Length-hiding padding before encryption — see MessagePadding.
                signal.encrypt(
                    conversation.contactId,
                    MessagePadding.pad(text.toByteArray(Charsets.UTF_8)),
                )
            } ?: return
            val envelope = MessageEnvelope(
                id = messageId,
                senderId = accountId,
                recipientId = conversation.contactId,
                ciphertext = encrypted.ciphertextBase64,
                ephemeralKey = encrypted.ephemeralKeyBase64,
                preKeyId = encrypted.preKeyId,
                messageNumber = encrypted.messageNumber,
                // libsignal's Java API does not expose the previous chain
                // length; the field is carried for protocol compatibility.
                previousChainLength = 0,
                timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                ttlSeconds = ttlSeconds,
                burnOnRead = burnOnRead,
                mediaType = MessageEnvelope.MEDIA_TEXT,
            )

            if (!existing) {
                val local = Message(
                    id = messageId,
                    conversationId = conversation.id,
                    text = text,
                    isMine = true,
                    timestampMs = System.currentTimeMillis(),
                    ttlSeconds = ttlSeconds,
                    burnOnRead = burnOnRead,
                    state = MessageState.SENDING,
                )
                messages.addOutgoing(local)
                conversations.onOutgoingMessage(conversation.id)
            }

            stage = "ws-send"
            // Outbound durable barrier BEFORE the non-suspending tail (D2c round 6): reseal the
            // SENDING ratchet advance encrypt() just made and confirm it durable NOW — the flush's
            // transient-retry backoff SUSPENDS, so it must complete OUTSIDE the check→send tail,
            // never between them (a suspension there would let a queued deleteContact interleave and
            // publish to a just-deleted contact). On a non-durable flush the message is NOT sent:
            // mark it failed for retry and stop before the tail.
            if (!flushSendRatchet(
                    flush = flushBeforeAck,
                    onNotDurable = {
                        diag("send: sending-ratchet flush not durable — not sent, marked for retry")
                    },
                )
            ) {
                diag("send: not handed to relay — marked failed for retry (${ws.connectionState.value})")
                messages.markFailed(messageId)
                return@runCatching
            }
            // NON-SUSPENDING publish tail: on the confinement worker this check→deposit is atomic
            // against deleteContact (the durable flush already completed above, OUTSIDE this
            // window), so a contact torn down before this point drops the envelope AND the local
            // plaintext, and one torn down after this point was still live when we deposited.
            if (!contactExists(conversation.contactId)) {
                diag("send: contact deleted mid-send — dropping local copy")
                messages.discard(messageId)
            } else if (ws.sendMessage(envelope)) {
                // Handed to the relay — but honestly still just SENDING. The tick waits for the
                // relay's message.stored (→SENT) and the recipient's message.delivered (→DELIVERED);
                // see [MessageState].
            } else {
                // The socket was down: the send did not reach the relay. The ratchet advance is
                // already durable, so a retry advances cleanly. Connection state only — never the
                // envelope.
                diag("send: not handed to relay — marked failed for retry (${ws.connectionState.value})")
                messages.markFailed(messageId)
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            // The message never made it out — surface FAILED so the user can
            // retry (no-op if the bubble was never added).
            messages.markFailed(messageId)
            // Same discrimination logic as the boot loop: exception class +
            // message + the server's {"error": code} body when present —
            // never message content, keys, or ids.
            val bodySuffix = (e as? ApiClient.ApiException)?.responseBody
                ?.let { " server_error=$it" }
                .orEmpty()
            diag("send: failed at stage=$stage: ${e.javaClass.name}: ${e.message}$bodySuffix")
        }
    }

    /**
     * Encrypt-then-sideload an attachment. The bytes are already prepared in
     * memory (downscaled/EXIF-stripped image, or a capped raw file — see
     * ui/AttachmentLoader); nothing here ever touches disk.
     *
     * Flow (contract-mandated): encrypt the blob under a fresh random key →
     * ratchet-encrypt a small control payload referencing it → upload the blob
     * to the blind store FIRST → only then hand the envelope to the socket, so
     * the recipient can always redeem the blob the envelope points at. The
     * envelope rides media_type "text" exactly like a receipt: the reserved
     * MEDIA_IMAGE/MEDIA_FILE values are NEVER emitted on the wire (that would
     * label the message for the relay). The [caption] is the compose-bar draft,
     * if any.
     *
     * Failure handling mirrors [sendText]: a key-substitution refusal aborts
     * before anything is uploaded; a blob-upload throw or a dead socket flips
     * the local copy to FAILED (bubble shows "!" + retry) and the orphaned blob,
     * if any, TTLs out in 1 week (or is fetch-and-burned on redeem). The sender's
     * own copy renders immediately from
     * the prepared bytes, which stay in memory so [retry] can re-upload them.
     */
    fun sendAttachment(
        conversation: Conversation,
        bytes: ByteArray,
        kind: String,
        mimetype: String,
        filename: String?,
        caption: String?,
        ttlSeconds: Int?,
        burnOnRead: Boolean,
    ) {
        scope.launch(confined) {
            deliverAttachment(
                conversation = conversation,
                messageId = UUID.randomUUID().toString(),
                bytes = bytes,
                kind = kind,
                mimetype = mimetype,
                filename = filename,
                caption = caption,
                ttlSeconds = ttlSeconds,
                burnOnRead = burnOnRead,
                existing = false,
            )
        }
    }

    /**
     * Encrypt-blob + sideload-upload + hand off one attachment under a fixed
     * [messageId]. Shared by the initial [sendAttachment] ([existing] = false)
     * and [retry] ([existing] = true, re-uploading a fresh blob from the
     * retained in-memory [bytes] under the same message id). Same honesty rules
     * as [deliverText]: a successful ws-enqueue leaves the message SENDING; the
     * tick advances only on the relay/peer acks; an upload throw or dead socket
     * flips it to FAILED.
     */
    private suspend fun deliverAttachment(
        conversation: Conversation,
        messageId: String,
        bytes: ByteArray,
        kind: String,
        mimetype: String,
        filename: String?,
        caption: String?,
        ttlSeconds: Int?,
        burnOnRead: Boolean,
        existing: Boolean,
    ) {
        val accountId = api.accountId ?: return
        var stage = "encrypt-blob"
        runCatching {
            val blob = AttachmentCrypto.encrypt(bytes)
            // filename is forced null for images inside serialize(); mirror
            // that here so the local copy's metadata matches the wire.
            val controlFilename = if (kind == AttachmentControlPayload.KIND_IMAGE) null else filename
            val controlJson = AttachmentControlPayload.serialize(
                kind = kind,
                blobToken = b64(blob.token),
                key = b64(blob.key),
                mimetype = mimetype,
                filename = filename,
                size = blob.size,
                sha256 = b64(blob.sha256),
                caption = caption,
            )
            // Session establishment + ratchet-encrypt hold the per-contact
            // lock so a concurrent receipt/text send can't fork the ratchet.
            // The key-substitution guard runs here, BEFORE the blob is
            // uploaded, so a refused send never orphans a blob.
            stage = "check-session"
            val encrypted = withSessionLock(conversation.contactId) {
                if (!signal.hasSession(conversation.contactId)) {
                    stage = "fetch-prekey-bundle"
                    diag("send: no session — firing GET prekey bundle")
                    val bundle = api.fetchPreKeyBundle(conversation.contactId)
                    // The prekey fetch suspended; a deleteContact may have landed.
                    // Do NOT establish/re-upsert (resurrect) a removed contact.
                    if (!contactExists(conversation.contactId)) {
                        diag("send: contact deleted during prekey fetch — send aborted")
                        return@withSessionLock null
                    }
                    val pinned = conversation.pinnedIdentityKeyBase64
                    if (pinned != null && pinned != bundle.identityKeyBase64) {
                        diag("send: identity key mismatch — send refused, warning raised")
                        conversations.flagIdentityMismatch(conversation.contactId)
                        return@withSessionLock null
                    }
                    stage = "establish-session"
                    signal.establishSession(conversation.contactId, bundle)
                    diag("send: X3DH session established")
                    conversations.upsert(
                        conversation.copy(contactIdentityKeyBase64 = bundle.identityKeyBase64),
                    )
                }
                stage = "encrypt"
                // Control JSON is padded with the DEFAULT 256-byte block like
                // any message plaintext; only the blob uses 64 KiB buckets.
                signal.encrypt(
                    conversation.contactId,
                    MessagePadding.pad(controlJson.toByteArray(Charsets.UTF_8)),
                )
            } ?: return

            if (!existing) {
                val local = Message(
                    id = messageId,
                    conversationId = conversation.id,
                    text = "",
                    isMine = true,
                    timestampMs = System.currentTimeMillis(),
                    ttlSeconds = ttlSeconds,
                    burnOnRead = burnOnRead,
                    state = MessageState.SENDING,
                    attachment = MessageAttachment(
                        kind = kind,
                        mimetype = mimetype,
                        filename = controlFilename,
                        size = blob.size,
                        caption = caption,
                        // The sender already holds the plaintext — render it now.
                        loadState = AttachmentLoadState.LOADED,
                        bytes = bytes,
                    ),
                )
                messages.addOutgoing(local)
                conversations.onOutgoingMessage(conversation.id)
            }

            // Blob to the blind store FIRST — the recipient must be able to
            // redeem it the moment the envelope arrives.
            stage = "upload-blob"
            diag("send: uploading attachment blob")
            api.uploadBlob(b64(blob.blobId), b64(blob.box))

            val envelope = MessageEnvelope(
                id = messageId,
                senderId = accountId,
                recipientId = conversation.contactId,
                ciphertext = encrypted.ciphertextBase64,
                ephemeralKey = encrypted.ephemeralKeyBase64,
                preKeyId = encrypted.preKeyId,
                messageNumber = encrypted.messageNumber,
                previousChainLength = 0,
                timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                ttlSeconds = ttlSeconds,
                burnOnRead = burnOnRead,
                // NEVER MEDIA_IMAGE/MEDIA_FILE — the relay must not be able to
                // tell an attachment from conversation text (see the control
                // payload rationale).
                mediaType = MessageEnvelope.MEDIA_TEXT,
            )
            stage = "ws-send"
            // Outbound durable barrier BEFORE the non-suspending tail (see [deliverText]): reseal
            // the sending-ratchet advance from encrypt() durable NOW — its transient-retry backoff
            // SUSPENDS, so it must run OUTSIDE the check→send tail (the blob upload above already
            // suspended; the flush is the last suspension before the atomic deposit). On a
            // non-durable flush the attachment is NOT sent: mark it failed and stop before the tail.
            if (!flushSendRatchet(
                    flush = flushBeforeAck,
                    onNotDurable = {
                        diag("send: sending-ratchet flush not durable — not sent, marked for retry")
                    },
                )
            ) {
                diag("send: not handed to relay — marked failed for retry (${ws.connectionState.value})")
                messages.markFailed(messageId)
                return@runCatching
            }
            // NON-SUSPENDING publish tail (see [confined]): atomic against deleteContact with the
            // durable flush already done. If the contact was deleted mid-upload, drop the envelope
            // AND the local copy (incl. the in-memory attachment bytes).
            if (!contactExists(conversation.contactId)) {
                diag("send: contact deleted mid-send — dropping local copy")
                messages.discard(messageId)
            } else if (ws.sendMessage(envelope)) {
                // Handed to the relay — honestly still SENDING until the relay/peer acks.
            } else {
                diag("send: not handed to relay — marked failed for retry (${ws.connectionState.value})")
                messages.markFailed(messageId)
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            // Upload throw or transport error — the attachment never made it out.
            messages.markFailed(messageId)
            val bodySuffix = (e as? ApiClient.ApiException)?.responseBody
                ?.let { " server_error=$it" }
                .orEmpty()
            diag("send: attachment failed at stage=$stage: ${e.javaClass.name}: ${e.message}$bodySuffix")
        }
    }

    /**
     * User tapped retry on a FAILED bubble. Flips it back to SENDING and re-runs
     * the send under the SAME message id — re-encrypting + re-uploading a fresh
     * blob from the retained in-memory attachment bytes, or re-sending the text
     * envelope. A no-op if the message is not FAILED (already sent/burned) or its
     * conversation is gone. An attachment whose bytes were somehow evicted can't
     * be re-uploaded — it is left FAILED (should not happen: a sender's own copy
     * stays LOADED in memory).
     */
    fun retry(messageId: String) {
        scope.launch(confined) {
            val message = messages.retryable(messageId) ?: return@launch
            val conversation = conversations.find(message.conversationId) ?: run {
                messages.markFailed(messageId)
                return@launch
            }
            val attachment = message.attachment
            if (attachment != null) {
                val bytes = attachment.bytes
                if (bytes == null) {
                    messages.markFailed(messageId)
                    return@launch
                }
                deliverAttachment(
                    conversation = conversation,
                    messageId = messageId,
                    bytes = bytes,
                    kind = attachment.kind,
                    mimetype = attachment.mimetype,
                    filename = attachment.filename,
                    caption = attachment.caption,
                    ttlSeconds = message.ttlSeconds,
                    burnOnRead = message.burnOnRead,
                    existing = true,
                )
            } else {
                deliverText(
                    conversation = conversation,
                    messageId = messageId,
                    text = message.text,
                    ttlSeconds = message.ttlSeconds,
                    burnOnRead = message.burnOnRead,
                    existing = true,
                )
            }
        }
    }

    fun sendTyping(conversation: Conversation, started: Boolean) {
        if (started) ws.typingStart(conversation.contactId) else ws.typingStop(conversation.contactId)
    }

    /**
     * The chat screen reports the batch of incoming messages that just became
     * visible. Read state is applied locally (which also arms the burn-on-read
     * grace timers); when "Send read receipts" is enabled, ONE encrypted
     * receipt envelope acknowledges the whole batch — a chat opened onto N
     * unread messages costs a single send against the relay's rate limit, not
     * N. Burn-on-read messages never produce a receipt: their delayed burn
     * signal IS the read confirmation ([MessageRepository.markRead] returns
     * false for them).
     */
    fun onMessagesSeen(conversation: Conversation, messageIds: List<String>) {
        // Messages became visible IN the open chat — that is a read for the
        // reminder cycle, and it must happen for EVERY seen batch, BEFORE the
        // newlyRead filter: burn-on-read messages deliberately return false
        // from markRead (their read-state is the armed burn timer), so a
        // burn-on-read-only batch has an empty newlyRead — but the user still
        // visibly saw those messages, and an armed re-fire must not buzz at
        // the boundary for them. newlyRead below decides receipts only.
        if (messageIds.isNotEmpty()) {
            notificationScheduler.onConversationRead(conversation.id)
        }
        val newlyRead = messageIds.filter { messages.markRead(it) }
        if (newlyRead.isEmpty()) return
        if (!settings.settings.value.readReceipts) return
        sendReadReceipt(conversation.contactId, newlyRead)
    }

    /**
     * Encrypt-and-send a read receipt disguised as an ordinary message
     * envelope — the relay cannot distinguish it from conversation text (see
     * [ControlPayload] for the server-blind rationale). Receipts only ride an
     * existing session: we just decrypted a message from this peer, so one
     * exists; if it somehow doesn't, the receipt is skipped rather than
     * establishing X3DH for a control signal. A receipt that can't be handed
     * off is queued in [pendingReceipts] and re-sent on reconnect.
     */
    private fun sendReadReceipt(contactId: String, messageIds: List<String>) {
        scope.launch(confined) {
            val accountId = api.accountId ?: return@launch
            runCatching {
                val plaintext = ControlPayload.readReceipt(messageIds)
                val encrypted = withSessionLock(contactId) {
                    if (!signal.hasSession(contactId)) return@withSessionLock null
                    // Padded like every text message, so ciphertext length
                    // can't fingerprint the receipt either.
                    signal.encrypt(contactId, MessagePadding.pad(plaintext.toByteArray(Charsets.UTF_8)))
                } ?: return@launch
                val envelope = MessageEnvelope(
                    id = UUID.randomUUID().toString(),
                    senderId = accountId,
                    recipientId = contactId,
                    ciphertext = encrypted.ciphertextBase64,
                    ephemeralKey = encrypted.ephemeralKeyBase64,
                    preKeyId = encrypted.preKeyId,
                    messageNumber = encrypted.messageNumber,
                    previousChainLength = 0,
                    timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                    // Server-blindness: a receipt envelope must look exactly
                    // like a text message — no TTL, no burn flag, text media.
                    ttlSeconds = null,
                    burnOnRead = false,
                    mediaType = MessageEnvelope.MEDIA_TEXT,
                )
                // Outbound durable barrier BEFORE the non-suspending tail (see [deliverText]): the
                // receipt's encrypt() advanced the sending ratchet too — reseal it durable NOW, its
                // suspending backoff OUTSIDE the check→send tail. On a non-durable flush the receipt
                // is NOT sent: the messages are already READ locally so they never re-enter
                // onMessagesSeen — queue the ids for the reconnect flush and stop before the tail.
                if (!flushSendRatchet(
                        flush = flushBeforeAck,
                        onNotDurable = {
                            diag("receipt: sending-ratchet flush not durable — queued for retry")
                        },
                    )
                ) {
                    diag("receipt: not handed to relay — queued (${ws.connectionState.value})")
                    queueReceipts(contactId, messageIds)
                    return@runCatching
                }
                // NON-SUSPENDING publish tail (see [confined]): atomic with deleteContact, the
                // durable flush already done. A receipt for a just-deleted contact is dropped (no
                // post-delete ciphertext) and not queued.
                if (!contactExists(contactId)) {
                    diag("receipt: contact deleted mid-send — dropped, not queued")
                } else if (ws.sendMessage(envelope)) {
                    // Delivered to the socket — nothing more to do.
                } else {
                    // Socket down. The messages are already READ locally, so queue the ids for the
                    // reconnect flush. Connection state only — never the envelope.
                    diag("receipt: not handed to relay — queued (${ws.connectionState.value})")
                    queueReceipts(contactId, messageIds)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                queueReceipts(contactId, messageIds)
                diag("receipt: failed — queued: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    private fun queueReceipts(contactId: String, messageIds: List<String>) {
        pendingReceipts.compute(contactId) { _, existing ->
            val list = existing ?: mutableListOf()
            messageIds.forEach { if (it !in list) list.add(it) }
            list
        }
    }

    private fun flushPendingReceipts() {
        // Iterate over a snapshot of the keys; remove() hands each queued
        // batch to exactly one flush even if two CONNECTED events race.
        pendingReceipts.keys.toList().forEach { contactId ->
            pendingReceipts.remove(contactId)?.let { ids ->
                if (ids.isNotEmpty()) sendReadReceipt(contactId, ids)
            }
        }
    }

    /**
     * Full contact deletion (cryptographic teardown, not soft-delete).
     *
     * Order matters:
     *  1. Burn-all for this conversation first — same path as the chat-header
     *     "burn all" action: every local message is destroyed and each fires a
     *     `message.burn` to the peer while the roster entry still exists (the
     *     burn callback resolves peer_id from the conversation). That is the
     *     simple purge: no separate relay envelope-delete API.
     *  2. Destroy Double Ratchet session state, remote identity, sender keys,
     *     and the roster entry.
     *
     * Ordering and concurrency (see [confined]): this runs on the confinement
     * worker, so it is serialized against every send/deliver coroutine.
     *
     *  1. **Crypto teardown FIRST**, and it is the fallible step — a blocking
     *     local commit run directly on the confinement worker (never main), so
     *     it is non-suspending and therefore mutually exclusive with any
     *     same-contact encrypt/decrypt (which are also non-suspending here); no
     *     session lock, and nothing waits on a network fetch. If the commit does
     *     not reach disk we abort **before** burning anything or removing the
     *     roster entry, so a storage failure leaves the contact fully intact for
     *     a retry rather than half-deleted (crypto gone, messages burned).
     *  2. Only after a durable wipe: burn local messages (+ best-effort peer
     *     `message.burn`) while the roster entry still resolves the peer.
     *  3. **Durable** roster removal (commit), so a crash right after teardown
     *     cannot leave a stale roster blob that resurrects the contact while its
     *     crypto is already gone.
     *  4. Drop per-contact transient state (queued receipts, typing) so a re-add
     *     in this process cannot inherit a stale "typing…" or receipt queue.
     *
     * Any send/deliver that raced this deletion re-checks [contactExists] with no
     * suspension before it publishes, so it drops rather than depositing
     * ciphertext or resurfacing plaintext for the removed contact.
     *
     * Irreversible for session material: re-adding the same person requires a
     * completely fresh X3DH handshake.
     */
    fun deleteContact(conversationId: String, onComplete: (() -> Unit)? = null) {
        scope.launch(confined) {
            val conversation = conversations.find(conversationId) ?: run {
                onComplete?.invoke()
                return@launch
            }
            val contactId = conversation.contactId
            val atomicDelete = vaultContactDelete
            if (atomicDelete != null) {
                // Vault path. Peer-burn FIRST, while the roster entry still resolves the peer for
                // the best-effort burn frames (legacy ordering) — burnAll only READS the roster and
                // enqueues burn frames, it does not persist the roster, so it needs no monitor and
                // cannot race the seal below.
                val at = System.currentTimeMillis()
                messages.burnAll(conversationId, notifyPeer = true)
                // Then the atomic teardown: crypto records + roster entry + tombstone seal in ONE
                // runtime.mutate + ONE durable flush, and the roster RAM reconciles to it — ALL
                // under the ConversationRepository monitor (the single serialization point), so no
                // concurrent roster write can resurrect or lose an entry. The removal is applied in
                // memory + live state REGARDLESS of the durable result (the crypto is already gone
                // and cannot be un-removed), so a false return is reported honestly as "not yet
                // confirmed durable" — NEVER "contact kept" (which would lie: its crypto is gone).
                val outcome = atomicDelete(conversationId, contactId, at)
                // Gate the per-contact transient cleanup on the outcome (Gemini round 3). The
                // removal is in live state for DURABLE and APPLIED_UNCONFIRMED (the contact IS gone),
                // so drop its queued receipts / typing / notification state. On NOT_APPLIED the
                // removal never took — the contact remains — so leave that state fully INTACT for a
                // post-unlock retry; stripping it would desync the UI (typing/receipts/notifications
                // dropped) from a contact that is still present.
                if (outcome != ContactDeleteOutcome.NOT_APPLIED) {
                    pendingReceipts.remove(contactId)
                    // Owed post-ack side effects for this contact's shown-but-unacked envelopes die
                    // with the contact: their redeliveries now hit the deleted-contact drop (a bare
                    // ack, never the duplicate path), so the entries would otherwise just leak —
                    // and a receipt/notification/redemption for a deleted contact must not fire.
                    pendingPostAck.dropContact(contactId)
                    _typingPeers.value = _typingPeers.value - contactId
                    notificationScheduler.onConversationRemoved(conversationId)
                }
                when (outcome) {
                    ContactDeleteOutcome.DURABLE -> Unit
                    ContactDeleteOutcome.APPLIED_UNCONFIRMED ->
                        diag("delete: vault teardown applied in memory + live state; durable flush " +
                            "unconfirmed — it persists on the next flush (contact is gone, not kept)")
                    ContactDeleteOutcome.NOT_APPLIED ->
                        // The runtime closed before the mutate applied (a revocation / forced logout
                        // raced the delete): the removal never reached live state, so the contact is
                        // NOT durably gone and reappears on the next unlock — surfaced honestly, not
                        // as an applied-but-unconfirmed removal.
                        diag("delete: vault runtime closed before teardown applied — delete did not " +
                            "take; retry after unlock")
                }
                onComplete?.invoke()
                return@launch
            }
            val wiped = signal.destroyContact(contactId)
            if (!wiped) {
                // Teardown did not reach disk (I/O error / storage full). Do NOT
                // burn messages or remove the contact: keeping everything intact
                // lets the user retry, and avoids a half-deleted state where the
                // crypto survives on disk but the contact vanished from the UI.
                diag("delete: crypto teardown commit failed — aborting, contact kept")
                onComplete?.invoke()
                return@launch
            }
            // Tombstone the contact (persisted, TTL-bounded) so a straggler
            // inbound message — including one that arrives after a process
            // restart, within the relay's undelivered window — is dropped rather
            // than TOFU-establishing a fresh session and popping the contact back
            // as "Unknown", without blocking genuine first-time inbound senders.
            // See isDeletedContact / ConversationRepository.wasRecentlyDeleted.
            conversations.recordDeletion(contactId)
            // Peer-burn is best-effort (an offline burn frame is not re-queued);
            // the dialog copy states this. Runs after the durable wipe, while the
            // roster entry still resolves the peer for onMessageBurned.
            messages.burnAll(conversationId, notifyPeer = true)
            // Crypto is already durably gone and messages are burned, so we can't
            // roll back; if the roster write did not reach disk we surface it
            // rather than reporting a clean delete. The residual is bounded and
            // safe: a restart would show a session-less ghost (its crypto is
            // gone, so a re-add performs a fresh X3DH), which the user can remove
            // again — never a contact that can still send/receive.
            if (!conversations.removeDurably(conversationId)) {
                diag("delete: roster removal did not reach disk — contact gone in-memory; a restart may show a session-less ghost")
            }
            pendingReceipts.remove(contactId)
            _typingPeers.value = _typingPeers.value - contactId
            // A deleted conversation must never buzz again: cancel any armed
            // re-fire and drop its reminder state entirely, so a later re-add
            // starts fresh (no inherited cooldown).
            notificationScheduler.onConversationRemoved(conversationId)
            onComplete?.invoke()
        }
    }

    /** Wipes the server account AND the local keys/messages. Irreversible. */
    fun deleteAccountAndWipe(onComplete: () -> Unit) {
        acceptingDeliveries = false
        // NonCancellable: the session scope this launches on is cancelled by
        // UnlockController.lock() (e.g. a server revocation racing the delete).
        // The server-side delete and the DURABLE roster clear must complete once
        // started — pre-D2b the process-lifetime scope guaranteed that; this
        // preserves it. Bounded work; onComplete's lock() is idempotent.
        scope.launch(confined + NonCancellable) {
            _linking.value = false
            linkJob?.cancel()
            runCatching { api.deleteAccount() }
            ws.disconnect()
            messages.clearAll()
            conversations.clearAll()
            // Teardown hook: no re-fire job or fire state survives the wipe.
            notificationScheduler.cancelAll()
            onComplete()
        }
    }

    // -- inbound WebSocket events ---------------------------------------------

    override fun onMessageDeliver(envelope: MessageEnvelope) {
        scope.launch(confined) {
            runCatching {
                // A straggler from a DELETED contact must not be decrypted:
                //  - a normal (non-PreKey) message has no session and would throw
                //    NoSessionException BEFORE any later guard, so it would never
                //    be acked → the relay redelivers it forever;
                //  - a PreKey message would TOFU-establish a fresh session and
                //    remote identity inside decrypt, resurrecting crypto state.
                // Check the tombstone FIRST, ack so the relay drops its copy, and
                // drop. Keyed on the deletion tombstone, NOT roster absence — a
                // first-time inbound sender is legitimately absent and must still
                // create an "Unknown contact" below (see isDeletedContact).
                if (isDeletedContact(envelope.senderId)) {
                    diag("recv: message for deleted contact — dropped before decrypt")
                    // No flush barrier: the tombstone check ([isDeletedContact]) is read-only and
                    // the drop happens BEFORE decrypt, so no ratchet/roster mutation occurred —
                    // nothing to make durable, ack immediately.
                    ws.ackMessage(envelope.id)
                    return@runCatching
                }
                // Decrypt advances the receiving ratchet — serialize it with
                // any concurrent encrypt for the same contact.
                val plaintext = withSessionLock(envelope.senderId) {
                    signal.decrypt(
                        remoteAccountId = envelope.senderId,
                        ciphertextBase64 = envelope.ciphertext,
                        isPreKeyMessage = envelope.ephemeralKey != null,
                    )
                }
                // Strip length-hiding padding; a legacy (pre-padding) sender's
                // bytes pass through unchanged — see MessagePadding.
                val body = MessagePadding.unpadOrNull(plaintext) ?: plaintext
                val text = String(body, Charsets.UTF_8)
                // Read receipts ride inside ordinary envelopes (see
                // ControlPayload) — recognize them BEFORE treating the payload
                // as displayable conversation text. A receipt updates our
                // outgoing copies, gets acked (so the server deletes its copy),
                // and never bumps the conversation or fires a notification.
                ControlPayload.parseReadReceipt(text)?.let { readIds ->
                    readIds.forEach(messages::onPeerRead)
                    // The decrypt above advanced the receiving ratchet — flush it durable before
                    // acking (a non-durable flush leaves the envelope for redelivery). D4 absorbed.
                    ackDurable(envelope.id)
                    return@runCatching
                }
                // Revocation gate: this coroutine may have been parked at
                // withSessionLock (behind a send holding the mutex across a
                // prekey fetch) when the session was torn down; the ratchet has
                // now advanced, but the account is gone. Do NOT publish or arm a
                // notification — a resumed delivery must not alert or re-arm the
                // reminder scheduler after a logout/wipe. Ack best-effort so the
                // relay drops its copy.
                if (!acceptingDeliveries) {
                    diag("recv: delivery resumed after teardown — dropped, not published")
                    // The parked decrypt advanced the ratchet before teardown; make that advance
                    // durable before acking so a non-durable flush redelivers rather than losing it.
                    ackDurable(envelope.id)
                    return@runCatching
                }
                val deliveredAtMs = runCatching {
                    Instant.parse(envelope.timestamp).toEpochMilli()
                }.getOrDefault(System.currentTimeMillis())
                // Resolve the conversation id with a READ (no roster mutation) so a decrypted
                // message can be DISPLAYED before the activity/unread bump — a decrypt advanced the
                // ratchet, and messages are RAM-only so displaying can never fail durably. The bump
                // ([onIncomingMessage]) then runs post-display, so a post-decrypt roster mutation
                // that later overflows can never leave a ratchet-advanced message unshown while the
                // dup-ack-drop path acks it away (round 2). onIncomingMessage assigns exactly this
                // id (existing entry's id, else the sender id for a fresh "Unknown contact").
                val conversationId = conversations.conversationIdFor(envelope.senderId)
                // Attachments ride inside ordinary envelopes too (see
                // AttachmentControlPayload) — recognize them AFTER receipts but
                // BEFORE treating the payload as text: the blob is redeemed and
                // decrypted from memory once the placeholder is on screen.
                AttachmentControlPayload.parse(text)?.let { attachment ->
                    // DISPLAY FIRST (RAM-only, cannot fail durably), then the roster bump.
                    messages.addIncoming(
                        Message(
                            id = envelope.id,
                            conversationId = conversationId,
                            text = "",
                            isMine = false,
                            timestampMs = deliveredAtMs,
                            ttlSeconds = envelope.ttlSeconds,
                            burnOnRead = envelope.burnOnRead,
                            state = MessageState.DELIVERED,
                            attachment = MessageAttachment(
                                kind = attachment.kind,
                                mimetype = attachment.mimetype,
                                filename = attachment.filename,
                                size = attachment.size,
                                caption = attachment.caption,
                                loadState = AttachmentLoadState.LOADING,
                            ),
                        ),
                    )
                    // Owe the post-ack side effects BEFORE the roster bump or the flush can fail:
                    // if either does, the relay's redelivery decrypts to a DUPLICATE (the ratchet
                    // is already past it) and the ACK_AND_DROP path — not this branch — lands the
                    // ack; it settles this entry so the one-shot blob still gets redeemed. See
                    // [PendingPostAckLedger].
                    pendingPostAck.owe(
                        envelope.id,
                        PendingPostAckLedger.Owed(
                            senderId = envelope.senderId,
                            conversationId = conversationId,
                            sendReceipt = true,
                            notify = true,
                            attachment = attachment,
                        ),
                    )
                    conversations.onIncomingMessage(envelope.senderId)
                    // Durable barrier: the decrypt advanced the ratchet. On a non-durable flush,
                    // skip the ack AND every post-ack side effect (relay redelivers; the owed
                    // entry above keeps them retryable on the duplicate path). D4 absorbed.
                    if (!ackDurable(envelope.id)) return@runCatching
                    // Receipt → notification → blob redemption, from the owed entry.
                    settlePostAck(envelope.id)
                    return@runCatching
                }
                // A control payload this build can't parse (a newer client's,
                // or a near-miss attachment) — a generic placeholder, NEVER the
                // raw text, which may carry key material.
                if (AttachmentControlPayload.isControlPayload(text)) {
                    // DISPLAY FIRST (RAM-only), then the roster bump — see the attachment branch.
                    messages.addIncoming(
                        Message(
                            id = envelope.id,
                            conversationId = conversationId,
                            text = "",
                            isMine = false,
                            timestampMs = deliveredAtMs,
                            ttlSeconds = envelope.ttlSeconds,
                            burnOnRead = envelope.burnOnRead,
                            state = MessageState.DELIVERED,
                            unsupported = true,
                        ),
                    )
                    // Owe the notification before the bump/flush can fail — see the attachment
                    // branch and [PendingPostAckLedger]. No receipt: this branch never sends one.
                    pendingPostAck.owe(
                        envelope.id,
                        PendingPostAckLedger.Owed(
                            senderId = envelope.senderId,
                            conversationId = conversationId,
                            sendReceipt = false,
                            notify = true,
                            attachment = null,
                        ),
                    )
                    conversations.onIncomingMessage(envelope.senderId)
                    // Durable barrier: the decrypt advanced the ratchet. On a non-durable flush,
                    // skip the ack and the notification (relay redelivers; the owed entry above
                    // keeps it retryable on the duplicate path). D4 absorbed.
                    if (!ackDurable(envelope.id)) return@runCatching
                    settlePostAck(envelope.id)
                    return@runCatching
                }
                // DISPLAY FIRST (RAM-only), then the roster bump — see the attachment branch.
                messages.addIncoming(
                    Message(
                        id = envelope.id,
                        conversationId = conversationId,
                        text = text,
                        isMine = false,
                        timestampMs = deliveredAtMs,
                        ttlSeconds = envelope.ttlSeconds,
                        burnOnRead = envelope.burnOnRead,
                        state = MessageState.DELIVERED,
                    ),
                )
                // Owe the receipt + notification before the bump/flush can fail — see the
                // attachment branch and [PendingPostAckLedger].
                pendingPostAck.owe(
                    envelope.id,
                    PendingPostAckLedger.Owed(
                        senderId = envelope.senderId,
                        conversationId = conversationId,
                        sendReceipt = true,
                        notify = true,
                        attachment = null,
                    ),
                )
                conversations.onIncomingMessage(envelope.senderId)
                // Ack AFTER successful decrypt + store AND a durable ratchet reseal: the ack is
                // what makes the server delete its copy (store-and-forward, zero retention), so it
                // must never outrun the receiving-ratchet advance reaching disk. On a non-durable
                // flush, skip the ack and the receipt (relay redelivers; the owed entry above
                // keeps them retryable on the duplicate path). D4 absorbed.
                if (!ackDurable(envelope.id)) return@runCatching
                // Receipt → notification, from the owed entry (see settlePostAck for the
                // zero-knowledge receipt rationale).
                settlePostAck(envelope.id)
            }.onFailure { e ->
                // The decision (rethrow / ack-drop / diagnose / swallow) is factored into the pure
                // [classifyRecvFailure] so it is host-testable without a live socket; the side
                // effects (ack, diag, rethrow) stay here. Ordering is load-bearing — see that fn.
                when (classifyRecvFailure(e)) {
                    RecvFailureAction.RETHROW -> throw e
                    RecvFailureAction.ACK_AND_DROP -> {
                        // A redelivery of a message we already consumed: decrypt threw
                        // DuplicateMessageException. A forward ratchet can NEVER re-derive it, so
                        // "skip the ack and rely on redelivery" would loop FOREVER (redeliver → dup →
                        // no ack → resend), surviving restart — hence we ack so the relay drops its
                        // copy, then drop.
                        // Round 7 — flush-before-ack even for a duplicate. DuplicateMessageException
                        // does NOT prove the FIRST delivery's receiving-ratchet advance is DURABLE:
                        // the relay can redeliver M while that advance is still only in RAM (the
                        // coalescing window). So reseal DURABLE before acking, exactly like the normal
                        // delivery path; on a non-durable flush do NOT ack (ackDurable diag'd it) —
                        // the relay redelivers → dup again → retry until durable, so a crash can never
                        // drop M from the relay while the ratchet is still short of it. This remains
                        // the net that closes the durable-but-unacked loop: a transient flush the
                        // coalesced reseal later persisted, or a crash after the reseal reached disk
                        // but before the ack, both resolve to a durable ack here on redelivery.
                        // No slot/credential data logged; the envelope id is an opaque relay handle.
                        if (ackDurable(envelope.id)) {
                            // Settle any post-ack side effects the FIRST delivery still owes for
                            // this envelope (it displayed the message, then its roster bump threw
                            // at capacity or its flush was non-durable). This is what keeps an
                            // attachment's one-shot blob redeemable — without it the placeholder
                            // stays LOADING forever (round 7, Codex :1237) — and delivers the owed
                            // receipt + notification. Settling is atomic, so the normal path and
                            // this one can never both run the effects for the same envelope.
                            settlePostAck(envelope.id)
                            diag("recv: duplicate (already consumed) — flushed, acked + dropped")
                        }
                    }
                    RecvFailureAction.DIAGNOSE_AT_CAPACITY ->
                        // Spec §4: surface a vault-at-capacity overflow as a non-fatal DIAGNOSTIC
                        // rather than swallowing it. The inbound ratchet persist (facade
                        // runtime.mutate → encode) threw VaultCapacityException, so the message was
                        // correctly NEVER acked (fail-closed) and the relay will redeliver — but
                        // without this the user gets no signal that the vault is full and how to
                        // recover (delete data).
                        diag("recv: vault at capacity — inbound left un-acked (relay redelivers); " +
                            "free space by deleting data")
                    // Other failures keep their existing swallow-and-redeliver behaviour.
                    RecvFailureAction.SWALLOW -> Unit
                }
            }
        }
    }

    /**
     * The chat screen opened this conversation (unread cleared). Route the read
     * through the coordinator — not straight into the scheduler — so the UI
     * depends only on the coordinator. Resets the conversation's re-fire cycle
     * so the next message alerts immediately.
     */
    fun onConversationRead(conversationId: String) {
        notificationScheduler.onConversationRead(conversationId)
    }

    /**
     * Redeem the sideloaded blob for a just-received attachment, decrypt and
     * verify it, then swap the bytes into the on-screen placeholder — all in
     * memory, never a cache file. Redemption is one-shot: a 404 (expired or
     * already fetched) or a verification failure (size/hash mismatch, tampered
     * ciphertext) is terminal, so the placeholder flips to a persistent
     * "unavailable" state rather than crashing or retrying.
     */
    private fun redeemAttachment(messageId: String, attachment: AttachmentControlPayload.Attachment) {
        scope.launch(confined) {
            runCatching {
                val ciphertext = api.redeemBlob(attachment.blobToken)
                val plain = AttachmentCrypto.decrypt(
                    key = unb64(attachment.key),
                    box = unb64(ciphertext),
                    expectedSha256 = unb64(attachment.sha256),
                    expectedSize = attachment.size,
                )
                messages.attachmentLoaded(messageId, plain)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                messages.attachmentUnavailable(messageId)
                diag("attachment: redeem/decrypt failed: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    override fun onMessageBurned(messageId: String) {
        messages.onRemoteBurn(messageId)
    }

    /**
     * Recipient tapped a received image to reveal it: uncover it and arm the
     * hard reveal-and-burn timer. Pure delegation to the repository — no new
     * wire traffic here; the eventual burn reuses the existing `message.burn`
     * signal (see [MessageRepository.revealAttachment]).
     */
    fun revealAttachment(messageId: String) {
        messages.revealAttachment(messageId)
    }

    /** Relay stored our envelope → SENT tick (one tick, "the relay has it"). */
    override fun onMessageStored(messageId: String) {
        messages.markSent(messageId)
    }

    /**
     * Recipient's peer-routed delivery receipt → DELIVERED tick. This is the
     * FIRST honest proof the message reached the other device, so it — not
     * ws-enqueue — advances the tick AND starts the sender-side TTL (see
     * [MessageRepository.markDelivered]).
     */
    override fun onMessageDelivered(messageId: String) {
        messages.markDelivered(messageId)
    }

    override fun onTyping(senderId: String, started: Boolean) {
        // Ignore a typing.start from anyone not in the roster — a deleted
        // contact whose late frame arrives after teardown, or an unknown sender.
        // Never show or restore a "typing…" for a contact the user can't see.
        if (started && conversations.findByContact(senderId) == null) return
        _typingPeers.value = if (started) {
            _typingPeers.value + senderId
        } else {
            _typingPeers.value - senderId
        }
    }

    override fun onPreKeyLow(remaining: Int) {
        scope.launch(confined) {
            runCatching {
                val oneTimePreKeys = signal.generateOneTimePreKeys()
                // Prekey durability barrier (see the register path): the top-up just STORED the new
                // one-time prekeys' PRIVATE halves — reseal them DURABLE before publishing their
                // PUBLIC halves. On a non-durable flush do NOT upload; the next low-prekey signal
                // RE-SERVES this same stored batch (upload-pending marker, round 8) rather than
                // generating another — a fresh batch per failure would pile orphaned private
                // halves into the fixed-capacity vault. Publishing publics whose privates a crash
                // could roll back would hand peers bundles we can't complete X3DH for.
                if (flushBeforePreKeyPublish {
                        diag("prekey: top-up reseal not durable — upload skipped, retries on next low signal")
                    }
                ) {
                    api.uploadPreKeys(oneTimePreKeys)
                    signal.confirmOneTimePreKeysUploaded()
                }
            }
        }
    }

    override fun onSessionRevoked() {
        // Fast, thread-safe teardown on the socket callback thread: stop the
        // relink loop, drop tokens, and — BEFORE the UI is bounced to the gate —
        // synchronously cancel every armed reminder job. Re-fire jobs run on
        // the container scope (not the confined dispatcher), so one at its
        // boundary could otherwise alert AFTER the user sees the logged-out
        // state but before the queued cleanup below runs.
        _linking.value = false
        acceptingDeliveries = false
        linkJob?.cancel()
        api.clearTokens()
        notificationScheduler.cancelAll()
        // Second, SERIALIZED cancel behind any message.deliver work already
        // queued on the confined dispatcher: those queued deliveries would
        // otherwise re-add messages and re-arm reminder state AFTER the
        // synchronous cancel above. Queued last, this block runs once they
        // have drained, so nothing they armed survives either. (A delivery
        // processed in between may still post one content-free alert — that
        // message genuinely arrived before logout completed; no timer
        // outlives this block.)
        scope.launch(confined) {
            messages.clearAll()
            notificationScheduler.cancelAll()
        }
        onForcedLogout?.invoke()
    }

    override fun onAuthExpired() {
        // Token rejected mid-session. Wait for any in-flight boot to finish
        // (it's the one that just connected), THEN re-run the boot sequence —
        // registration is skipped (account exists), so this re-mints a fresh
        // session + socket. Latching via join() avoids the race where start()
        // no-ops against a still-active linkJob and the relink is lost.
        val current = linkJob
        scope.launch(confined) {
            current?.join()
            // Re-check intent after the join window: a teardown
            // (stop/logout/deleteAccount) may have run in between, and relinking
            // then would resurrect the connection — or, post-delete, silently
            // register a brand-new account.
            if (_linking.value) start()
        }
    }

    override fun onServerError(code: String, message: String) {
        // Server error codes carry no user data; v1 surfaces them only as
        // connection state, never as raw strings.
    }

    private companion object {
        /** Logcat tag for boot-stage transport diagnostics — see class kdoc. */
        const val TAG = "ZitroneBoot"

        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_SHIFT = 6
    }
}

/** The action [onMessageDeliver] takes when a post-decrypt inbound branch throws. */
internal enum class RecvFailureAction {
    /** Cooperative cancellation — rethrow so the scope unwinds; never ack. */
    RETHROW,

    /**
     * A redelivery of an already-consumed message ([DuplicateMessageException]) — flush-before-ack
     * (round 7: a dup does NOT prove the first delivery's ratchet advance is durable) so the relay
     * drops its copy only once durable, then drop. Recovery by redelivery is impossible (forward
     * ratchet), so this is the net that breaks the infinite-redelivery loop for a durable-but-unacked
     * advance — resolving to a durable ack on redelivery once the coalesced reseal has landed.
     */
    ACK_AND_DROP,

    /** Vault at capacity ([VaultCapacityException]) — fail-closed (no ack) + a recovery diagnostic. */
    DIAGNOSE_AT_CAPACITY,

    /** Any other failure — swallow; the relay redelivers (behaviour unchanged from pre-D2c). */
    SWALLOW,
}

/**
 * Classify a post-decrypt inbound failure. Ordering is LOAD-BEARING: cancellation is checked FIRST
 * (a teardown must unwind, never be folded into a lower branch), then the already-consumed duplicate
 * (checked BEFORE capacity because a dup is terminal regardless of vault fullness — and because it
 * is the net that closes the durable-but-unacked loop that a transient/capacity flush failure can
 * open via VaultSession's coalesced background reseal). Extracted pure so the decision is host-
 * testable without a live socket; the side effects live in [onMessageDeliver].
 */
internal fun classifyRecvFailure(e: Throwable): RecvFailureAction = when {
    e is CancellationException -> RecvFailureAction.RETHROW
    e is DuplicateMessageException -> RecvFailureAction.ACK_AND_DROP
    e is VaultCapacityException -> RecvFailureAction.DIAGNOSE_AT_CAPACITY
    else -> RecvFailureAction.SWALLOW
}

/**
 * Thrown to fail-and-retry a boot attempt whose pre-publish prekey reseal ([flushBeforePreKeyPublish])
 * was NOT durable. It aborts the attempt BEFORE api.register publishes any public prekey half — the
 * boot loop's runCatching maps it to a retry with backoff, so a later flush that lands then registers.
 * NOT a [CancellationException]: a real teardown still unwinds; this only defers a publish.
 */
internal class PreKeyFlushNotDurableException :
    Exception("prekey reseal not confirmed durable — publication deferred to the next boot attempt")

/** Attempts (incl. the first) [flushThenAck] makes at a TRANSIENT durable-flush blip. */
internal const val FLUSH_MAX_ATTEMPTS = 3

/** Linear backoff step between transient retries — attempt N waits N × this (~50/100 ms). */
internal const val FLUSH_RETRY_BASE_MS = 50L

/**
 * A flush failure worth RETRYING in-line rather than deferring to the redelivery + duplicate path.
 * Only a genuinely transient durability blip qualifies: an unconfirmed image write
 * ([VaultImageException.NotDurable]) or a raw disk [IOException], which usually clears on the next
 * attempt. A full vault ([VaultCapacityException]) and a closed runtime (a plain
 * [IllegalStateException]) are NOT transient — they must fail-closed without an ack (a later encode
 * that fits, or a fresh session, resolves them, and the duplicate handler backstops any advance
 * that persisted meanwhile). NOTE [VaultCapacityException] is itself an [IllegalStateException], so
 * this deliberate allow-list (NOT an IllegalStateException deny-list) keeps capacity out of retry.
 */
internal fun isTransientFlushFailure(t: Throwable): Boolean =
    t is VaultImageException.NotDurable || t is IOException

/**
 * Flush-before-ack decision (D2c, absorbs D4), extracted so it is host-testable without a live
 * socket. Runs the durable reseal barrier [flush] and only THEN [ack]s the envelope; if [flush]
 * throws (NotDurable / IO / runtime closed or at-capacity) the ratchet advance did NOT reach disk,
 * so it does NOT ack — it invokes [onNotDurable] (diagnostic) and returns false, leaving the
 * inbound un-acked so the relay redelivers (flush-before-ack window=0, zero acked loss). A
 * CancellationException is rethrown so cooperative cancellation still unwinds. The default no-op
 * [flush] on the non-vault path never throws, so the ack always fires there — behaviour-identical
 * to the pre-D2c immediate ack.
 *
 * Round 4: a TRANSIENT flush failure ([isTransientFlushFailure]) is retried up to [maxAttempts]
 * times with a small [backoff] before giving up. A brief disk hiccup usually clears at once,
 * resolving to durable + ack IN-LINE rather than deferring to the wasteful redelivery + duplicate-
 * decrypt path (which is correct — the duplicate handler ack-drops it — but costs a relay round
 * trip). Non-transient failures (capacity, closed) are NOT retried: they fail-closed immediately,
 * exactly as before, and the receiving-ratchet advance that VaultSession's coalesced background
 * reseal may still persist is backstopped by the DuplicateMessageException handler on redelivery.
 */
internal suspend fun flushThenAck(
    envelopeId: String,
    flush: suspend () -> Unit,
    ack: (String) -> Unit,
    onNotDurable: () -> Unit,
    maxAttempts: Int = FLUSH_MAX_ATTEMPTS,
    backoff: suspend (attempt: Int) -> Unit = { attempt -> delay(FLUSH_RETRY_BASE_MS * attempt) },
): Boolean {
    var attempt = 1
    while (true) {
        try {
            flush()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Retry only a transient blip, and only while attempts remain; a full vault or a
            // closed runtime falls straight through to the fail-closed no-ack path below.
            if (attempt < maxAttempts && isTransientFlushFailure(t)) {
                backoff(attempt)
                attempt++
                continue
            }
            onNotDurable()
            return false
        }
        ack(envelopeId)
        return true
    }
}

/**
 * Outbound durable barrier (D2c round 2; round 6 split out the send). signal.encrypt advances the
 * SENDING ratchet (coalesced reseal via the vault); this reseals it DURABLE via [flush] and reports
 * whether that flush confirmed — the CALLER then runs its NON-SUSPENDING `contactExists → sendMessage`
 * tail iff this returned true. Splitting the flush OUT of the send is load-bearing: [flush] SUSPENDS
 * on its transient-retry backoff, so it must run BEFORE the check→send tail, never between the check
 * and the send — otherwise a queued deleteContact could interleave on the confined worker and publish
 * ciphertext to (or resurface plaintext for) a just-deleted contact, breaking delete-atomicity. The
 * durable-before-handoff crash guarantee is unchanged: [flush] is still after encrypt() and before
 * the send, so a crash between the eventual hand-off and the background reseal can never roll the
 * sending ratchet back and re-encrypt a later message at the SAME chain index (key/nonce reuse — a
 * forward-secrecy break).
 *
 * Returns whether the ratchet advance was confirmed DURABLE. false → the caller must NOT send (marks
 * the message failed / queues it for retry); the in-memory advance the coalesced reseal may still
 * persist leaves at worst a benign skipped index, which the recipient's ratchet tolerates. A
 * [CancellationException] is rethrown so cooperative cancellation unwinds. The default no-op [flush]
 * on the non-vault path never throws, so it always returns true — behaviour-identical to the pre-D2c
 * immediate send. Transient blips ([isTransientFlushFailure]) are retried up to [maxAttempts] exactly
 * like the inbound barrier; capacity / closed fail-closed. Extracted top-level (mirroring
 * [flushThenAck]) so the ordering + fail-closed decision is host-testable without a live socket.
 */
internal suspend fun flushSendRatchet(
    flush: suspend () -> Unit,
    onNotDurable: () -> Unit,
    maxAttempts: Int = FLUSH_MAX_ATTEMPTS,
    backoff: suspend (attempt: Int) -> Unit = { attempt -> delay(FLUSH_RETRY_BASE_MS * attempt) },
): Boolean {
    var attempt = 1
    while (true) {
        try {
            flush()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Retry only a transient blip while attempts remain; capacity / closed fail closed and
            // the caller does NOT send — the message stays un-sent for its retry.
            if (attempt < maxAttempts && isTransientFlushFailure(t)) {
                backoff(attempt)
                attempt++
                continue
            }
            onNotDurable()
            return false
        }
        return true
    }
}

/**
 * Outcome of the vault atomic contact-delete seal (ZitroneApp's `deleteContactAtomically`). Public
 * because it is the return type of the public [MessagingCoordinator] constructor's vault-delete hook.
 */
enum class ContactDeleteOutcome {
    /** The mutate applied the removal AND the flush confirmed it durable. */
    DURABLE,

    /**
     * The mutate applied the removal (the contact's crypto is gone from live state, NEVER rolled
     * back) but the durable flush was UNCONFIRMED — it persists on the next successful flush.
     */
    APPLIED_UNCONFIRMED,

    /**
     * The runtime was CLOSED before the mutate applied (a revocation / forced logout ran
     * runtime.close() first): the removal NEVER touched live state, so the delete did not take and
     * the contact reappears on the next unlock. Distinct from [APPLIED_UNCONFIRMED] — reporting it
     * as an applied removal would falsely claim the contact is gone.
     */
    NOT_APPLIED,
}

/**
 * Map the seal's durable result + whether its mutate applied to a [ContactDeleteOutcome]. Extracted
 * so the closed-runtime (NOT_APPLIED) vs unconfirmed-flush (APPLIED_UNCONFIRMED) distinction is
 * host-testable. A `durable` result implies the mutate applied.
 */
internal fun contactDeleteOutcome(durable: Boolean, mutateApplied: Boolean): ContactDeleteOutcome =
    when {
        durable -> ContactDeleteOutcome.DURABLE
        mutateApplied -> ContactDeleteOutcome.APPLIED_UNCONFIRMED
        else -> ContactDeleteOutcome.NOT_APPLIED
    }

/**
 * The post-ack side effects (delivery receipt / notification / attachment redemption) a display
 * branch still OWES for a shown-but-not-yet-acked envelope, keyed by envelope id (round 7,
 * Codex :1237).
 *
 * A display branch registers its owed entry via [owe] immediately after
 * [MessageRepository.addIncoming] — BEFORE the roster bump or the durable flush, either of which
 * can fail (a `VaultCapacityException` from the bump, or a non-durable flush). If one does, the
 * envelope stays un-acked and the relay redelivers — but the redelivery decrypts to
 * `DuplicateMessageException` (the ratchet is already past it), so the duplicate ACK_AND_DROP
 * path, not the display branch, is what finally lands the durable ack. [settle] hands it the owed
 * entry so the one-shot attachment blob still gets redeemed (instead of a placeholder stuck
 * LOADING forever), the sender still sees DELIVERED, and the notification still fires. [settle]
 * is an atomic remove: exactly ONE path runs the effects for an envelope.
 *
 * [dropContact] discards a deleted contact's owed entries — its redeliveries hit the
 * deleted-contact drop (a bare ack, never the duplicate path), and no effect may fire for a
 * deleted contact.
 *
 * In-memory only, like the messages themselves: after a process death the placeholder is gone
 * too, so nothing is left to owe (the RAM-only durable-before-ack residual documented in round 4).
 */
internal class PendingPostAckLedger {
    internal data class Owed(
        val senderId: String,
        val conversationId: String,
        val sendReceipt: Boolean,
        val notify: Boolean,
        val attachment: AttachmentControlPayload.Attachment?,
    )

    private val owed = ConcurrentHashMap<String, Owed>()

    /** Register the side effects [envelopeId]'s display branch owes; overwrites any stale entry. */
    fun owe(envelopeId: String, entry: Owed) {
        owed[envelopeId] = entry
    }

    /** Atomically claim [envelopeId]'s owed entry, or null if none/already settled. */
    fun settle(envelopeId: String): Owed? = owed.remove(envelopeId)

    /** Discard every entry owed for [senderId] (contact deleted — effects must not fire). */
    fun dropContact(senderId: String) {
        owed.entries.removeIf { it.value.senderId == senderId }
    }

    /** Owed envelope ids (test observability). */
    fun pending(): Set<String> = owed.keys.toSet()

    /** Session teardown: discard every owed entry (no effect may cross an identity switch). */
    fun clear() {
        owed.clear()
    }
}
