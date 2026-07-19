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
import com.zitrone.app.notifications.MessagingNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     * Read receipts awaiting a live socket, keyed by contact. Queued when the
     * hand-off fails (socket down) and flushed on the next CONNECTED
     * transition: the underlying messages are already READ locally, so they
     * will never re-enter [onMessagesSeen] — without this queue the sender
     * would stay at "delivered" forever. In-memory only, like the messages
     * themselves.
     */
    private val pendingReceipts = ConcurrentHashMap<String, MutableList<String>>()

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
        scope.launch {
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
        linkJob = scope.launch { bootstrapLoop() }
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
                        val signedPreKey = signal.generateSignedPreKey()
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
                    diag("boot[$attempt]: firing POST /api/v1/register")
                    registration?.invoke()
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
                        api.uploadPreKeys(emptyList(), rotated)
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
        linkJob?.cancel()
        ws.disconnect()
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
        scope.launch {
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
            if (ws.sendMessage(envelope)) {
                // Enqueued — but honestly still just SENDING. The tick waits for
                // the relay's message.stored (→SENT) and the recipient's
                // message.delivered (→DELIVERED); see [MessageState].
            } else {
                // Socket not open: the send did not reach the relay.
                // Connection state only — never the envelope.
                diag("send: hand-off failed — socket not open (${ws.connectionState.value})")
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
     * if any, TTLs out in 72h. The sender's own copy renders immediately from
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
        scope.launch {
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
            if (ws.sendMessage(envelope)) {
                // Enqueued — honestly still SENDING until the relay/peer acks.
            } else {
                diag("send: hand-off failed — socket not open (${ws.connectionState.value})")
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
        scope.launch {
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
        scope.launch {
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
                if (!ws.sendMessage(envelope)) {
                    // Socket down. The messages are already READ locally, so
                    // they will never re-enter onMessagesSeen — queue the ids
                    // for the reconnect flush. Connection state only — never
                    // the envelope.
                    diag("receipt: hand-off failed — queued (${ws.connectionState.value})")
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

    /** Wipes the server account AND the local keys/messages. Irreversible. */
    fun deleteAccountAndWipe(onComplete: () -> Unit) {
        scope.launch {
            _linking.value = false
            linkJob?.cancel()
            runCatching { api.deleteAccount() }
            ws.disconnect()
            messages.clearAll()
            conversations.clearAll()
            onComplete()
        }
    }

    // -- inbound WebSocket events ---------------------------------------------

    override fun onMessageDeliver(envelope: MessageEnvelope) {
        scope.launch {
            runCatching {
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
                    ws.ackMessage(envelope.id)
                    return@runCatching
                }
                val deliveredAtMs = runCatching {
                    Instant.parse(envelope.timestamp).toEpochMilli()
                }.getOrDefault(System.currentTimeMillis())
                val conversation = conversations.onIncomingMessage(envelope.senderId)
                // Attachments ride inside ordinary envelopes too (see
                // AttachmentControlPayload) — recognize them AFTER receipts but
                // BEFORE treating the payload as text: the blob is redeemed and
                // decrypted from memory once the placeholder is on screen.
                AttachmentControlPayload.parse(text)?.let { attachment ->
                    messages.addIncoming(
                        Message(
                            id = envelope.id,
                            conversationId = conversation.id,
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
                    ws.ackMessage(envelope.id)
                    // Delivery receipt back to the SENDER (peer-routed by the
                    // relay → their message.delivered). senderId comes from the
                    // decrypted envelope; the relay never stored it, preserving
                    // zero-knowledge. Best-effort: a dropped receipt just means
                    // the sender stays at SENT, never worse.
                    ws.sendReceived(envelope.id, envelope.senderId)
                    MessagingNotifications.showNewMessage(appContext)
                    redeemAttachment(envelope.id, attachment)
                    return@runCatching
                }
                // A control payload this build can't parse (a newer client's,
                // or a near-miss attachment) — a generic placeholder, NEVER the
                // raw text, which may carry key material.
                if (AttachmentControlPayload.isControlPayload(text)) {
                    messages.addIncoming(
                        Message(
                            id = envelope.id,
                            conversationId = conversation.id,
                            text = "",
                            isMine = false,
                            timestampMs = deliveredAtMs,
                            ttlSeconds = envelope.ttlSeconds,
                            burnOnRead = envelope.burnOnRead,
                            state = MessageState.DELIVERED,
                            unsupported = true,
                        ),
                    )
                    ws.ackMessage(envelope.id)
                    MessagingNotifications.showNewMessage(appContext)
                    return@runCatching
                }
                messages.addIncoming(
                    Message(
                        id = envelope.id,
                        conversationId = conversation.id,
                        text = text,
                        isMine = false,
                        timestampMs = deliveredAtMs,
                        ttlSeconds = envelope.ttlSeconds,
                        burnOnRead = envelope.burnOnRead,
                        state = MessageState.DELIVERED,
                    ),
                )
                // Ack AFTER successful decrypt + store: this is what makes
                // the server delete its copy (store-and-forward, zero
                // retention).
                ws.ackMessage(envelope.id)
                // Delivery receipt to the SENDER (peer-routed → their
                // message.delivered). See the attachment branch above for the
                // zero-knowledge rationale.
                ws.sendReceived(envelope.id, envelope.senderId)
                // Content-free notification: always just "New message".
                MessagingNotifications.showNewMessage(appContext)
            }
        }
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
        scope.launch {
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
        _typingPeers.value = if (started) {
            _typingPeers.value + senderId
        } else {
            _typingPeers.value - senderId
        }
    }

    override fun onPreKeyLow(remaining: Int) {
        scope.launch {
            runCatching {
                api.uploadPreKeys(signal.generateOneTimePreKeys())
            }
        }
    }

    override fun onSessionRevoked() {
        _linking.value = false
        linkJob?.cancel()
        messages.clearAll()
        api.clearTokens()
        onForcedLogout?.invoke()
    }

    override fun onAuthExpired() {
        // Token rejected mid-session. Wait for any in-flight boot to finish
        // (it's the one that just connected), THEN re-run the boot sequence —
        // registration is skipped (account exists), so this re-mints a fresh
        // session + socket. Latching via join() avoids the race where start()
        // no-ops against a still-active linkJob and the relink is lost.
        val current = linkJob
        scope.launch {
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
