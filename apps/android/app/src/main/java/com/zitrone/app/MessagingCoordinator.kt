// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.goterl.lazysodium.SodiumAndroid
import com.zitrone.app.crypto.AttachmentCrypto
import com.zitrone.app.crypto.LibsodiumRegistrationPowDeriver
import com.zitrone.app.crypto.MessagePadding
import com.zitrone.app.crypto.RegistrationPow
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultImageException
import com.zitrone.app.diagnostics.BootDiagnostics
import com.zitrone.app.diagnostics.RegistrationPowSolveRecorder
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
import com.zitrone.app.decoy.CoverTraffic
import com.zitrone.app.net.ApiClient
import com.zitrone.app.net.WsClient
import com.zitrone.app.notifications.NotificationScheduler
import com.zitrone.app.ui.components.RegistrationPowState
import com.zitrone.app.ui.components.RegistrationPowUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
    /**
     * Persist the account-deletion INTENT durably (the `vault.delete-intent` marker) — the FIRST
     * step of [deleteAccountAndWipe], before the server-delete request leaves the device. Means
     * ONLY "a delete was initiated"; it NEVER authorises local destruction (round 13). MUST THROW
     * if it cannot be made durable — the delete then aborts without touching the server. Production
     * supplies [AppContainer.markVaultDeleteIntent]; default no-op for the legacy path (no vault).
     */
    private val persistDeleteIntent: () -> Unit = {},
    /**
     * Persist SERVER-DELETE-CONFIRMED durably (the `vault.delete-confirmed` marker) — written ONLY
     * after [ApiClient.deleteAccount] returns [ApiClient.AccountDeleteResult.CONFIRMED_GONE], and
     * REQUIRED-durable (round 14, F1): it MUST throw if it cannot be made durable so the caller
     * never tears down / clears auth over an un-recorded confirmation. This is the ONLY marker that
     * authorises the unlink-only DeleteIncomplete auto-destroy. Production supplies
     * [AppContainer.markServerDeleteConfirmed].
     */
    private val persistServerDeleteConfirmed: () -> Unit = {},
    /**
     * Whether the DURABLE delete-intent marker is present (production:
     * [AppContainer.hasVaultDeleteIntentMarker]). This is the durable auth-protection signal that
     * [onSessionRevoked] honors (round 16, R15-P2): its true-window equals the intent marker's
     * on-disk lifetime — spanning not-confirmed exits AND process restart — which the process-local
     * [deleteInFlight] flag alone could not. Reads a file stat under the image lock; called only on
     * the rare revoke path.
     */
    private val intentMarkerPresent: () -> Boolean = { false },
    /**
     * Cover traffic (0.10.0 U3). Called with every outbound envelope — text, attachment control
     * payload and read receipt alike — **immediately after that envelope's publish tail has handed
     * it to the relay, and only then**, so a same-length decoy frame follows a real one that
     * actually went (fix round 4). [CoverTraffic.NONE] (the default, and every non-vault
     * construction) is a call that returns.
     *
     * **This seam never runs a real send, and nothing it does precedes one** (§4.3 R-U3-1, R-U3-2
     * ruling of 2026-07-27, tightened in U3 fix round 3). Until that round the publish tail was
     * handed to it as a `() -> Unit` that it promised to invoke first — but reaching that invocation
     * still cost an interface dispatch, a captured lambda and entry into a coroutine state machine,
     * all of it between the durable ratchet advance and `ws.sendMessage`, and the OS can kill the
     * process at any instruction. The tail therefore moved back to the call sites
     * ([publishOutgoing], [publishReceipt] — still non-suspending, so D2c stays compiler-enforced)
     * and this seam is called after it. The instruction sequence from the durability barrier to the
     * socket is the pre-U3 one.
     *
     * Teardown runs through [CoverTraffic.stop], which is handed `ws.disconnect` — see
     * [coverTeardown] — and a live transport SWAP runs through [CoverTraffic.quiesce], see
     * [reconnectTransport]. Both are dispatched through [CoverTrafficWorker].
     *
     * **CORRECTED (0.10.2 design review): this used to claim they "cannot interleave with a send's
     * publish-then-pair slice" because both run on [confined]. That is FALSE whenever
     * [CoverTrafficWorker]'s caller-thread fallback fires after its terminal wait — which
     * CoverTrafficWorker's own kdoc already records as structurally defeating the confinement
     * argument, precisely when it fires.** What actually holds the property is that the
     * publish-then-pair sequence is a SUSPENSION-FREE slice, compiler-enforced by
     * [publishOutgoing]/[publishReceipt] being non-suspending; `limitedParallelism(1)` serialises
     * slices, not coroutines, so confinement alone would guarantee nothing across a suspension. **They reach it by different routes,
     * and that difference is a fix (round 5):** terminal teardown may fall back to the caller after a
     * bound, because a vault lock that hangs without wiping keys is worse; a transport swap may
     * NEVER, because `quiesce` leaves the register open and a swap off the worker splits pairs.
     */
    private val coverTraffic: CoverTraffic = CoverTraffic.NONE,
    /**
     * Cover traffic (0.10.0 U4) — **R-U4-1**: whether an inbound envelope's `sender_id` is this
     * vault's synthetic cover account. U4 lets the synthetic side occasionally reply, so the real
     * client can now receive an envelope that must never become a message. True means drop it
     * before decrypt. Default false: every non-vault construction and every pre-U4 test is
     * unaffected, and a vault with no synthetic account answers false for every sender.
     *
     * **Why the guard is here and not after decrypt.** "A cover blob is random bytes, so decryption
     * will fail anyway" is an outcome claim, and a false one: [onMessageDeliver] selects the
     * decrypt path on `ephemeralKey != null`, and libsignal's PreKey path **TOFU-establishes a
     * session and a remote identity inside `decrypt`, before any MAC check can reject the blob** —
     * so the failure lands after the crypto state is written. This is the same reason the
     * deleted-contact tombstone is checked before decrypt, and this guard sits beside it.
     *
     * **Trust assumption, recorded rather than assumed:** `sender_id` is set by the relay, so a
     * hostile relay could suppress a real message by labelling it with the synthetic account id.
     * That grants it no new power — a relay that wants a message dropped can simply drop it.
     */
    private val isSyntheticSender: (String) -> Boolean = { false },
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
     * Registration proof-of-work UI state — drives
     * [com.zitrone.app.ui.components.RegistrationPowScreen] (the lemon-squeeze pitcher)
     * during the first-boot solve. IDLE whenever no solve is running: the relink path
     * (account already registered) and the proofless 404 path never leave IDLE, so the UI
     * composes the screen only during real account creation. The fraction comes ONLY from
     * the solver's progress sink (actual work counts); the ticker in [solveRegistrationPow]
     * owns elapsed time, the 60s prompt, and backgrounded detection — never progress
     * (contract §6.1).
     */
    private val _registrationPow = MutableStateFlow(RegistrationPowUiState())
    val registrationPow: StateFlow<RegistrationPowUiState> = _registrationPow.asStateFlow()

    /**
     * "keep waiting" latch — read by the solve ticker so a dismissed 60s prompt stays
     * dismissed for the remainder of the CURRENT solve (contract §6.3: dismissing changes
     * nothing about the solve, and it does not re-prompt). Reset per solve.
     * @Volatile: written on the main thread, read by the ticker on the confined worker.
     */
    @Volatile
    private var powPromptDismissed = false

    /** The 60s prompt's "keep waiting": dismisses the prompt, nothing else (contract §6.3). */
    fun powKeepWaiting() {
        powPromptDismissed = true
        _registrationPow.update { current ->
            if (current.state == RegistrationPowState.PROMPTED_AT_60S) {
                current.copy(state = RegistrationPowState.SOLVING)
            } else {
                current
            }
        }
    }

    /**
     * The 60s prompt's "try later": aborts the solve cleanly. The solver's only cancellation
     * mechanism is thread interruption, delivered by cancelling the boot job ([stop] — the
     * designed teardown; during registration there is no session or socket to tear down). No
     * durable state is left behind (the solve runs BEFORE the prekey barriers, the challenge
     * is stateless server-side), and the next [start] — next unlock or app launch — retries
     * with a fresh challenge.
     */
    fun powTryLater() {
        stop()
        // Terminal write AFTER stop() so it wins regardless of where the cancellation lands
        // in the solve path (which also writes CANCELLED, harmlessly, on its own catch).
        _registrationPow.value = RegistrationPowUiState(state = RegistrationPowState.CANCELLED)
    }

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
     * OUTBOUND gate — **step 1 of the R-U3-5 teardown lifecycle, "stop admitting new real sends"**
     * (U3 fix round 4). Cleared synchronously at the top of [stop] and [deleteAccountAndWipe]'s
     * teardown, before the cover-traffic teardown is enqueued, and set on [start].
     *
     * Round 3 argued this step was not jointly satisfiable with "no cover-side instruction precedes
     * the real handoff" — that closing the admission window needed a lock in front of the send. That
     * was wrong, and this flag is half of why: refusing a *new* send is a plain volatile read at the
     * very top of the send coroutine, thousands of instructions and several suspension points before
     * the durability barrier. It is nowhere near the barrier→socket window, it takes no lock, and it
     * is not cover-specific — a send admitted after teardown was already doomed to hit a dead socket
     * and be marked FAILED. The other half is that terminal teardown is *enqueued on the confined
     * worker*, behind the sends already running there (see [coverTeardown]).
     *
     * @Volatile: written on the teardown thread, read on the confined dispatcher.
     */
    @Volatile
    private var acceptingSends = false

    /**
     * True only while [deleteAccountAndWipe]'s coroutine is RUNNING (round 15). It covers the
     * narrow window BEFORE the intent marker is durable (coroutine start → intent write), which the
     * durable [intentMarkerPresent] check cannot yet see. The full auth-protection guard is
     * `deleteInFlight || intentMarkerPresent()` (round 16, R15-P2): the union spans coroutine-start
     * through the intent marker's retire, so a revoke can never clear tokens across a not-confirmed
     * exit or a restart while the marker persists — the guard's true-window now EQUALS the marker's
     * lifetime, not just this coroutine's. Written by the confined+NonCancellable coroutine, read on
     * the socket-callback thread. @Volatile for cross-thread visibility.
     */
    @Volatile
    private var deleteInFlight = false

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
     * Per-message attachment deposit secrets, so a RETRY reuses one blob instead of orphaning the
     * previous one (0.10.2 item 5a).
     *
     * `AttachmentCrypto.encrypt` drew a fresh token per call and `blobId = sha256(token)`, so every
     * 0.10.1 retry uploaded a NEW blob and left the old one to its full TTL — N retries = N × up to
     * 8 MiB, and blobs are the dimension that actually threatens the box (~2,079 orphans exhaust
     * CX23's free space, one blob ≈ 545 accounts' worth of disk).
     *
     * **Only the 96-byte secrets are held, never the box.** Holding the 8 MiB ciphertext to force
     * byte-identical re-uploads would trade a disk orphan for a heap leak, on bytes the message
     * ALREADY retains in memory for retry. It is unnecessary anyway: whichever attempt's bytes the
     * relay keeps carry their own nonce, so a stable key opens either.
     *
     * **Per-process is sufficient, and is the smaller surface.** `MessageRepository` is RAM-only, so
     * a retry only ever happens inside one process lifetime — a crash takes the bubble and leaves
     * nothing to retry. So this needs no vault scoping, no durable state, and adds no deniability
     * surface. Entries are released the moment the send stops being retryable; see [releaseDeposit].
     */
    private class AttachmentDeposit(val token: ByteArray, val key: ByteArray)

    private val attachmentDeposits = ConcurrentHashMap<String, AttachmentDeposit>()

    /**
     * Drop a message's memoized deposit secrets. **Without this the map grows for the process's
     * lifetime**, which is the heap-leak side of the trade this fix exists to avoid. Idempotent.
     *
     * **Its call sites, enumerated — an earlier kdoc claimed "every terminal outcome … or it burned"
     * and round-2 review falsified both halves.** The three sites are: [onMessageStored] (the relay
     * took it), [onMessageDelivered] (the recipient got it), and [settleAttachment]'s RELEASE_ONLY
     * branch. A discarded local copy no longer releases directly — it routes through
     * [settleAttachment], which may reclaim the blob instead of merely forgetting it. **Burn does
     * NOT release at all**, so a burned attachment's memo and handoff entry survive to process death
     * (bounded, see [handedOffMessages]); do not skip wiring burn on the belief that this covers it.
     */
    private fun releaseDeposit(messageId: String) {
        attachmentDeposits.remove(messageId)
        // Clear the handoff record too, or it grows for the process's lifetime — the heap-leak side
        // of the very trade this memo exists to avoid. Safe because with no memo settleDecision
        // returns SKIP whatever the bit said, so dropping the two together can never turn a
        // RELEASE_ONLY into an ABANDON.
        //
        // NOT the only clearer, despite what this comment used to say: settleAttachment clears the
        // bit itself before branching. See handedOffMessages for the exact enumeration.
        handedOffMessages.remove(messageId)
    }

    /**
     * Message ids whose envelope has been handed to a socket at least once — **the authoritative,
     * monotone record that forbids abandoning their blob** (0.10.3 C3).
     *
     * Set in [publishOutgoing]'s success branch. A retry re-enters `publishOutgoing` and re-sets it,
     * so no later incarnation can "un-hand-off" an earlier one. Five design passes failed by trying
     * to *arbitrate* the race between a retry and a cleanup; this deletes the race instead, by
     * making "was it ever enqueued?" a question with an answer.
     *
     * **Monotone in the direction that matters, NOT absolutely — corrected in round-1 review.** An
     * earlier version of this kdoc claimed the bit was "cleared only by [settleAttachment]". That
     * was false the moment [releaseDeposit] began clearing it too (added to stop this set growing
     * for the process's lifetime). What actually holds is narrower and sufficient: **the bit and the
     * memo are only ever cleared together**, so a cleared bit always implies a cleared memo, and
     * `settleDecision` returns SKIP with no memo regardless of what the bit said. Teardown never
     * clears it.
     *
     * Why a set and not a flag on [AttachmentDeposit]: the deposit is claimed and removed by whoever
     * settles first, and the handoff fact must outlive that removal.
     *
     * **Bound:** per-process message count, not zero. [stop] and `clearAll` clear neither this set
     * nor [attachmentDeposits], so a handed-off-but-never-acked message leaks one id until process
     * death. Acceptable (ids are small, `MessageRepository` is RAM-only anyway), but it means the
     * "no unbounded heap growth" claim holds per-process rather than absolutely.
     */
    private val handedOffMessages = ConcurrentHashMap.newKeySet<String>()

    /**
     * Terminal cleanup for a message that had an attachment: reclaim the blob **only when it is
     * provably unreferenced and unrecreatable**, per [settleDecision].
     *
     * Structure (the shape the 0.10.3 judging panel converged on, grafting Grok's claim discipline
     * onto Kimi's base):
     *
     * 1. Read the decision inputs.
     * 2. `SKIP` / `RELEASE_ONLY` return without transmitting anything.
     * 3. **The atomic `remove` is the LAST gate and the exclusive claim.** Only one caller can get a
     *    non-null memo, so only one abandon can ever be issued per deposit. Losing the claim means
     *    someone else already settled it — do nothing.
     * 4. **No restore-on-failure.** A failed abandon leaves an orphan the relay's janitor collects at
     *    the blob TTL. Putting the memo back would re-open the claim and reintroduce exactly the
     *    double-settle the claim exists to prevent — trading a self-healing orphan for a live P1.
     *
     * Must run on [confined].
     */
    private fun settleAttachment(messageId: String) {
        val action = settleDecision(
            hasMemo = attachmentDeposits.containsKey(messageId),
            state = messages.stateOf(messageId),
            handedOff = messageId in handedOffMessages,
        )
        if (action == SettleAction.SKIP) return
        handedOffMessages.remove(messageId)
        if (action == SettleAction.RELEASE_ONLY) {
            releaseDeposit(messageId)
            return
        }
        // ABANDON. The claim is the last gate: lose it and another settle already owns this blob.
        val memo = attachmentDeposits.remove(messageId) ?: return
        scope.launch(confined + NonCancellable) {
            // NonCancellable is DELIBERATE and was challenged in round-1 review. It replaces the Job
            // element, so this coroutine is not a child of `scope` and still runs when `scope` is
            // already cancelled — verified empirically, not assumed (one lens asserted the opposite
            // and was wrong). That is the point: teardown is the commonest reason a settle runs at
            // all, and a plain scope.launch would yield an immediately-cancelled coroutine that
            // silently leaks exactly the blob this exists to reclaim.
            //
            // ACCEPTED RESIDUAL (recorded so it stays a decision, not an accident): because it
            // outlives the session, it can fire after the bearer is gone and take a 401 with the
            // token already in the request body — disclosed, with nothing deleted. Tolerable because
            // the relay already holds the ciphertext and can drop any row at will, so it gains no
            // read or destructive capability. The account↔blob linkage argument is subtler than two
            // earlier versions of this comment claimed in opposite directions — see
            // ApiClient.abandonBlob's kdoc, which states both halves.
            //
            // Bounded by BLOB_ABANDON_TIMEOUT_MS, so it cannot park forever on a half-open circuit.
            runCatching { api.abandonBlob(b64(memo.token)) }
                .onSuccess { diag("attachment: reclaimed unsent blob") }
                // Swallowed by design: the janitor collects the orphan at the blob TTL, and there is
                // no user-visible consequence to report.
                .onFailure { diag("attachment: blob reclaim failed — left for the relay janitor") }
        }
    }


    /**
     * Whether [contactId] is still a live roster entry. Used by the send/deliver
     * publish tails: a send is always to an existing conversation, so a `false`
     * here means the contact was torn down mid-send and nothing may be deposited
     * or published for it.
     */
    private fun contactExists(contactId: String): Boolean =
        conversations.findByContact(contactId) != null

    /**
     * THE PUBLISH TAIL for [deliverText] and the attachment control payload — **a non-suspending
     * method, and that is the whole point of it being a method at all.**
     *
     * On the confinement worker this `contactExists → ws.sendMessage` check→deposit must be atomic
     * against `deleteContact`: the durable flush (whose transient-retry backoff SUSPENDS) completes
     * strictly BEFORE this runs, because a suspension between the check and the send would let a
     * queued delete interleave and publish ciphertext to a just-deleted contact (D2c round 6). So a
     * contact torn down before this point drops the envelope AND the local plaintext, and one torn
     * down after it was still live when we deposited.
     *
     * **Why it is a `private fun` rather than four inline lines:** a non-suspending function body
     * cannot contain a suspension point, so the rule above is enforced by the compiler at every
     * caller instead of by a comment each caller has to keep repeating. Cover traffic used to buy
     * that enforcement by taking the tail as a `() -> Unit` (0.10.0 U3); the tail moved back out to
     * the caller in fix round 3 so that no cover-traffic instruction sits between the durability
     * barrier and `ws.sendMessage`, and this method is what kept the enforcement. It is a member of
     * the send path, not of the cover-traffic seam, and it would stay exactly as it is if cover
     * traffic were deleted.
     *
     * **Returns whether the envelope was actually HANDED TO THE RELAY** (U3 fix round 4). It used to
     * return `Unit`, which collapsed three outcomes — discarded because the contact was deleted,
     * refused because the socket was down, and genuinely handed off — into one the caller could not
     * tell apart. The caller ran cover traffic in all three, so two of them put a decoy on the wire
     * with **no real frame behind it**: a frame the user never generated, which is the same
     * marked-pair defect as an unpaired real frame with the sign flipped. Hence the guard on the
     * cover call at all three call sites.
     */
    private fun publishOutgoing(
        envelope: MessageEnvelope,
        contactId: String,
        messageId: String,
    ): Boolean {
        if (!contactExists(contactId)) {
            diag("send: contact deleted mid-send — dropping local copy")
            messages.discard(messageId)
            // Contact deleted mid-send: the local copy is gone, so nothing will retry this id.
            //
            // **This site is NOT "provably never handed off"** — an earlier comment here claimed it
            // was, and all three review lenses independently falsified it with the same trace:
            // attempt 1 hands off, the 90 s timeout flips it to FAILED, the user retries, the
            // contact is deleted mid-retry, and we arrive here with an envelope already enqueued.
            // Safety comes from [settleAttachment] re-proving the condition against the handoff
            // record, never from this call site's position. That is precisely why the decision was
            // extracted into a tested pure function instead of being inlined here.
            settleAttachment(messageId)
            return false
        }
        if (ws.sendMessage(envelope)) {
            // Handed to the relay — but honestly still just SENDING. The tick waits for the relay's
            // message.stored (→SENT) and the recipient's message.delivered (→DELIVERED); see
            // [MessageState].
            //
            // THE SEND TIMEOUT IS ARMED HERE, AND NOWHERE ELSE (0.10.1 review round 2, both lenses
            // found the P1 this fixes). It used to be armed in `addOutgoing`, i.e. when the bubble
            // was created — which for an ATTACHMENT is before the blob upload, so the 90 s window
            // included an unbounded upload (OkHttp's writeTimeout is per-write, not whole-body, so
            // a slow 11 MiB body is never cut off). The timer then fired while attempt #1 was still
            // uploading, showed a FALSE FAILED with a retry affordance, and a user who took it got
            // two independently encrypted envelopes under one id — a real double delivery once the
            // first was acked and its row deleted.
            //
            // Arming at the handoff makes the window exactly what the design always claimed: time
            // spent WAITING FOR THE RELAY'S RECEIPT, with no local work inside it. It is also the
            // single place both the text and attachment paths pass through, so neither can be armed
            // and forgotten. Retries re-enter here and get their own window; nothing arms on
            // `addOutgoing` or `retryable` any more.
            messages.armSendTimeout(messageId)
            // The blob (if any) may now be at the relay and redeemable by the recipient, so it must
            // never be abandoned. Recorded AFTER the enqueue succeeds and never cleared here — see
            // [handedOffMessages].
            handedOffMessages.add(messageId)
            return true
        }
        // The socket was down: the send did not reach the relay. The ratchet advance is already
        // durable, so a retry advances cleanly. Connection state only — never the envelope.
        diag("send: not handed to relay — marked failed for retry (${ws.connectionState.value})")
        messages.markFailed(messageId)
        return false
    }

    /**
     * THE PUBLISH TAIL for read receipts — the same non-suspending contract as [publishOutgoing]
     * and the same `true` = "handed to the relay" result,
     * with the receipt's own failure handling: a receipt for a just-deleted contact is dropped (no
     * post-delete ciphertext) and NOT queued, while a socket-down receipt is queued for the
     * reconnect flush because the messages are already READ locally and will never re-enter
     * [onMessagesSeen].
     */
    private fun publishReceipt(
        envelope: MessageEnvelope,
        contactId: String,
        messageIds: List<String>,
    ): Boolean {
        if (!contactExists(contactId)) {
            diag("receipt: contact deleted mid-send — dropped, not queued")
            return false
        }
        if (ws.sendMessage(envelope)) {
            // Delivered to the socket — nothing more to do.
            return true
        }
        diag("receipt: not handed to relay — queued (${ws.connectionState.value})")
        queueReceipts(contactId, messageIds)
        return false
    }

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
        // A stale terminal PoW state (CANCELLED from a "try later", COMPLETE from a torn-down
        // boot) must not leak into this run's UI; the solve path re-raises it when it runs.
        _registrationPow.value = RegistrationPowUiState()
        _linking.value = true
        acceptingDeliveries = true
        acceptingSends = true
        linkJob = scope.launch(confined) { bootstrapLoop() }
    }

    private suspend fun bootstrapLoop() {
        // One-time prekeys are generated (and persisted) at most ONCE and reused
        // across register retries: regenerating per attempt would orphan a
        // signed prekey + a full batch into the encrypted store on every failed
        // register. Identity generation is idempotent and stays inside the loop,
        // so a transient keystore hiccup retries instead of dead-ending the loop
        // with nothing scheduled to recover it.
        var registration: (suspend (powProof: Map<String, String>?) -> Unit)? = null
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
                        // Reuse a stored-but-unconfirmed signed prekey / NEVER-ATTEMPTED one-time
                        // batch from a previous attempt before generating fresh (round 8). An
                        // ATTEMPTED batch (a register request that may have reached the relay) is
                        // never reused — the same single-use publics must not exist under two
                        // account ids — and its superseded privates are discarded (safe here ONLY:
                        // no live account can ever receive a message keyed to them), keeping the
                        // offline-retry loop net-zero in the vault (round 11, Codex). The signed
                        // prekey IS reused across attempts: it is multi-use and the relay upserts
                        // it per-account.
                        val signedPreKey = signal.pendingSignedPreKeyUpload() ?: signal.generateSignedPreKey()
                        val oneTimePreKeys = signal.generateOneTimePreKeys(discardAttempted = true)
                        registration = { powProof ->
                            api.register(
                                identityKeyBase64 = signal.localIdentityPublicKeyBase64(),
                                registrationId = signal.localRegistrationId(),
                                signedPreKey = signedPreKey,
                                oneTimePreKeys = oneTimePreKeys,
                                powProof = powProof,
                            )
                            // register() returns the new account id; the loop
                            // only needs its Unit side effect (accountId stored).
                            Unit
                        }
                    }
                    // 0.9.4 registration PoW: fetch a challenge and solve BEFORE the prekey
                    // durability barriers below — an aborted or failed solve then leaves no
                    // durable state behind (the challenge is stateless server-side) and never
                    // burns the batch's ATTEMPTED marker for an attempt that produced no
                    // register request. The proof is per-attempt, never cached like the prekey
                    // closure: challenges expire, and a retry only reaches here when the relay
                    // was reachable moments ago, so a fresh fetch is cheap. The solve itself
                    // always runs through the instrumented recorder (Diagnostics screen).
                    stage = "pow-challenge"
                    val challengeToken = try {
                        api.registrationChallenge()
                    } catch (e: ApiClient.ApiException) {
                        if (e.code == 404) {
                            // Relay predates the PoW deploy entirely. Registering proofless is
                            // the designed behaviour, not a fallback an attacker can reach:
                            // an ENFORCING relay serves this endpoint by construction.
                            diag("boot[$attempt]: no PoW challenge endpoint (404) — registering without proof")
                            null
                        } else {
                            throw e
                        }
                    }
                    stage = "pow-solve"
                    val powProof = challengeToken?.let { solveRegistrationPow(attempt, it) }
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
                    // TWO-PHASE attempted marker, same as the top-up path (round 11, Codex): mark
                    // the batch ATTEMPTED + reseal durable BEFORE the register request can leave.
                    // A lost response (or crash) then regenerates instead of re-registering the
                    // same single-use publics under a second account id.
                    signal.markOneTimePreKeyUploadAttempted()
                    if (!flushBeforePreKeyPublish {
                            diag("boot[$attempt]: attempted-marker reseal not durable — register deferred")
                        }
                    ) {
                        throw PreKeyFlushNotDurableException()
                    }
                    diag("boot[$attempt]: firing POST /api/v1/register")
                    try {
                        registration?.invoke(powProof?.toJsonMap())
                    } catch (t: Throwable) {
                        // The request MAY have reached the relay (response lost / any ambiguous
                        // failure): drop the cached closure so the retry regenerates its batch
                        // (the ATTEMPTED marker makes generateOneTimePreKeys refuse to re-serve
                        // this one) instead of re-uploading the same publics.
                        registration = null
                        throw t
                    }
                    // The relay now holds the public halves — retire both pending-upload markers
                    // (losing this confirm just re-uploads the same records, idempotent).
                    signal.confirmPreKeysUploaded()
                    diag("boot[$attempt]: registration accepted by server")
                }
                // Flush the REGISTRATION STATE durable before minting a session (round 10,
                // Codex): register stored the assigned account id through the vault-backed
                // AuthStore as a coalesced mutation only. A crash inside that ≤2s window reopens
                // the vault with accountId == null, and the next boot registers AGAIN — the
                // server mints a fresh UUID and the account that may already have been displayed
                // or shared is orphaned. Deliberately OUTSIDE the register branch: a retry
                // attempt keeps the RAM accountId (register is skipped), so the gate must re-run
                // on EVERY attempt until it confirms — inside the branch, a first-flush failure
                // would never be re-checked. On an already-clean state this is a cheap no-op
                // flush; the session/socket never outruns the identity reaching disk.
                stage = "flush-registration"
                if (!flushBeforePreKeyPublish {
                        diag("boot[$attempt]: registration-state reseal not durable — session deferred to retry")
                    }
                ) {
                    throw PreKeyFlushNotDurableException()
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
                // A failure AFTER a completed solve (register 4xx, session mint, flush) must
                // not leave a full pitcher sitting through the backoff — that reads as a hang
                // (contract §6.2). Drop the screen; the retry's fresh solve raises it again.
                _registrationPow.update { current ->
                    if (current.state == RegistrationPowState.COMPLETE) RegistrationPowUiState() else current
                }
            }.isSuccess
            if (ok) {
                // Boot reached a live session: registration (if any) is fully done — retire
                // the full-pitcher COMPLETE frame and hand the UI back to the session routes.
                _registrationPow.value = RegistrationPowUiState()
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
        // R-U3-5 step 1, and it must come FIRST: no new real send is admitted from here on, so the
        // set of sends the teardown below has to serialise behind is closed rather than growing.
        acceptingSends = false
        linkJob?.cancel()
        // Steps 2–4, ON THE CONFINED WORKER and blocking until they have run — see
        // [CoverTrafficWorker] for why the dispatch is the whole point. The helper skips the
        // dispatch when teardown has already happened, because [deleteAccountAndWipe] tears cover
        // traffic down on the worker and only THEN calls back into a lock() that lands here —
        // dispatching onto the worker from a caller the worker is itself waiting on would stall for
        // the whole bound before falling back.
        coverWorker.runTerminalConfined(::coverTeardown)
        // Teardown hook: drop all pending re-fire jobs + fire state so nothing
        // carries across an identity switch (see NotificationScheduler).
        notificationScheduler.cancelAll()
        // Owed post-ack side effects die with the session: a receipt, notification, or blob
        // redemption must never fire for a locked/logged-out/burned account, and nothing
        // carries across an identity switch (see PendingPostAckLedger).
        pendingPostAck.clear()
    }

    /**
     * Steps 2–4 of the R-U3-5 teardown lifecycle: **the only place in this class that stops cover
     * traffic and invalidates the transport.**
     *
     * The disconnect is passed IN rather than called beside the drain, because getting the order
     * wrong is a real defect and not a style point: until U3 fix round 3 [stop] disconnected first,
     * so every vault lock that landed in a pairing's drawn gap put a lone real frame and then a TLS
     * close on the wire — a deterministic, recognisable class of unpaired real sends correlated with
     * lock, teardown and backgrounding, the exact observable cover traffic exists to remove
     * (R-U3-3). [CoverTraffic.stop] stops admitting pairings, stops provisioning, drains the
     * pairings it already admitted while the socket is still live, and only then runs this lambda.
     *
     * **Must be called ON the confined worker**, and only through [coverWorker] — either
     * [CoverTrafficWorker.runTerminalHere] from a coroutine already running there
     * ([deleteAccountAndWipe]) or [CoverTrafficWorker.runTerminalConfined] ([stop]). The helper owns
     * the exactly-once latch, so this method has none of its own: a session can reach terminal
     * teardown twice (an account delete tears down and then locks; a revoke can race a lock) and the
     * second arrival must not re-drain, re-disconnect, or — worse — dispatch onto a worker that is
     * itself waiting on the caller.
     */
    private fun coverTeardown() {
        coverTraffic.stop { ws.disconnect() }
    }

    /**
     * Where cover-traffic teardown and transport swaps run: the [confined] worker, always. See
     * [CoverTrafficWorker] — it is a separate class because U3 fix round 5 found that the property
     * it carries (production dispatch, the bounded terminal fallback, and the **absence** of a
     * fallback on the non-terminal path) was pinned by nothing but source-string tripwires, and a
     * property under no test is how the round-4 P1 survived a round that claimed to establish it.
     */
    private val coverWorker = CoverTrafficWorker(scope, confined)

    /**
     * A transport SWAP under a live session (Tor/I2P toggle): drain cover traffic, then run
     * [swapTransport], then carry on pairing over the new socket. **Non-terminal** — the session
     * survives and [CoverTraffic.quiesce] leaves the register open.
     *
     * Called by `ZitroneApp.applyTransport`, which used to disconnect and redial the socket
     * directly. That left any pairing sleeping in its drawn gap **split across a TLS teardown and
     * reconnect** — ruled P1 by the third lens in round 3 on a distinction neither reviewer made: a
     * split pair is a *stronger* signal than a missing cover frame, because it lets an observer link
     * two identical-length frames across a connection boundary, ties them to an independently
     * observable infrastructure event, and correlates them with the user changing their anonymity
     * transport.
     *
     * **Asynchronous, and that is the round-5 fix.** Round 4 ran this through the same helper as
     * terminal teardown, which fell back to the CALLING thread after 250 ms — and since `quiesce`
     * leaves the register open, that fallback re-opened the very split-pair class it was built to
     * close. It could not simply be removed while the caller held the app's transport lock (a
     * verified lock inversion, see [CoverTrafficWorker]). So the caller releases that lock first and
     * this no longer waits at all: it queues the drain-and-swap on the worker, where it cannot
     * interleave with any publish/admit slice, and returns. The endpoints the new socket will dial
     * were already installed by the caller under the lock.
     */
    fun reconnectTransport(swapTransport: () -> Unit) =
        coverWorker.requestReconnect { coverTraffic.quiesce(swapTransport) }

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

    /** Lazily constructed: libsodium is only touched if a solve actually runs. */
    private val powDeriver: RegistrationPow.Argon2idDeriver by lazy {
        LibsodiumRegistrationPowDeriver(SodiumAndroid())
    }

    /**
     * Solve the registration PoW through the instrumented recorder so every real solve
     * writes its calibration numbers to the Diagnostics screen (see the recorder's kdoc —
     * that channel produced the 0.9.4 device calibration and is how any future difficulty
     * change gets re-measured).
     *
     * Runs on [Dispatchers.Default]: the solve is pure CPU for seconds and must not occupy
     * the confined boot worker. [runInterruptible] maps coroutine cancellation (stop(),
     * logout, "try later", teardown) onto the solver's thread-interrupt contract, so an
     * abandoned boot aborts the solve promptly — and the recorder logs that abort as a
     * data point.
     *
     * Also the producer of [registrationPow] (the pitcher screen's state). Two disjoint
     * writers while the solve runs, merged with atomic [MutableStateFlow.update]s:
     *  - the solver's progress sink (solver thread) writes ONLY the fraction — progress
     *    tracks actual work, never time (contract §6.1);
     *  - a 1s ticker (this coroutine's scope) writes ONLY elapsed seconds + the
     *    SOLVING/PROMPTED_AT_60S/BACKGROUNDED distinction ([registrationPowTickState]).
     * Terminal states are written here after both are stopped: COMPLETE on proof (held
     * until the boot loop retires it at session-up), CANCELLED on interruption, IDLE on a
     * real solve failure (the boot loop's backoff owns the retry).
     */
    private suspend fun solveRegistrationPow(attempt: Int, challengeToken: String): RegistrationPow.Proof {
        powPromptDismissed = false
        val solveStartedAt = SystemClock.elapsedRealtime()
        fun elapsedSeconds() = (SystemClock.elapsedRealtime() - solveStartedAt) / 1_000
        _registrationPow.value = RegistrationPowUiState(state = RegistrationPowState.SOLVING)
        val proof = try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        delay(1_000)
                        _registrationPow.update { current ->
                            current.copy(
                                state = registrationPowTickState(
                                    elapsedSeconds = elapsedSeconds(),
                                    promptDismissed = powPromptDismissed,
                                    inForeground = processInForeground(),
                                ),
                                elapsedSeconds = elapsedSeconds(),
                            )
                        }
                    }
                }
                try {
                    runInterruptible(Dispatchers.Default) {
                        RegistrationPowSolveRecorder(
                            diag = { line -> diag("boot[$attempt]: $line") },
                            batterySaver = {
                                runCatching {
                                    (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                                        ?.isPowerSaveMode
                                }.getOrNull()
                            },
                            inForeground = ::processInForeground,
                            clock = SystemClock::elapsedRealtime,
                        ).solve(
                            challengeToken = challengeToken,
                            identityKey = signal.localIdentityPublicKeyBytes(),
                            params = RegistrationPow.DEFAULT_PARAMS,
                            deriver = powDeriver,
                            uiProgress = { p ->
                                _registrationPow.update { it.copy(fractionOfExpectedWork = p.fraction) }
                            },
                        )
                    }
                } finally {
                    // coroutineScope won't return until the ticker is really gone, so the
                    // terminal writes below can never be clobbered by a late tick.
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            _registrationPow.update { it.copy(state = RegistrationPowState.CANCELLED) }
            throw e
        } catch (t: Throwable) {
            _registrationPow.value = RegistrationPowUiState()
            throw t
        }
        _registrationPow.update {
            it.copy(state = RegistrationPowState.COMPLETE, elapsedSeconds = elapsedSeconds())
        }
        return proof
    }

    /**
     * A plain field read (LifecycleRegistry keeps state in a field; only mutation is
     * main-thread-enforced), so it is cheap enough for the recorder's per-emission sampling
     * and the UI ticker alike. Worst case it reads slightly stale — fine for both. Null when
     * unreadable: the recorder renders that as `unknown`, and the tick state treats it as
     * not-backgrounded (BACKGROUNDED is a claim about the user having left; don't make it
     * on a probe that can't answer).
     */
    private fun processInForeground(): Boolean? =
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }.getOrNull()

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
        // R-U3-5 step 1 — see [acceptingSends]. Before any crypto, any durable write and any
        // suspension: a send admitted after teardown started could only reach a socket that is being
        // closed, and would advance the ratchet to do it.
        if (!acceptingSends) return
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
            // The NON-SUSPENDING publish tail (see [publishOutgoing]), called directly and FIRST —
            // the instruction sequence from the durability barrier to `ws.sendMessage` is the pre-U3
            // one, with no cover-traffic code in it at all (U3 fix round 3).
            // Cover traffic (U3), strictly AFTER the real frame is on the socket AND ONLY IF IT GOT
            // THERE (fix round 4): it emits a same-length decoy frame after a drawn gap and cannot
            // reach the send above. A decoy for an envelope the relay never received would be a lone
            // marked frame the user never generated.
            if (publishOutgoing(envelope, conversation.contactId, messageId)) coverTraffic.cover(envelope)
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
        // R-U3-5 step 1 — see [acceptingSends] and [deliverText].
        if (!acceptingSends) return
        val accountId = api.accountId ?: return
        var stage = "encrypt-blob"
        runCatching {
            // ONE BLOB PER MESSAGE (0.10.2 item 5a). A retry reuses the first attempt's token and
            // key, so `blobId` is stable and the deposit lands on the same row
            // (`ON CONFLICT (blob_id) DO NOTHING`) instead of orphaning the previous blob. The nonce
            // is still fresh per call — see AttachmentCrypto.encrypt for why forcing byte-identity
            // would be the dangerous option.
            val memo = attachmentDeposits[messageId]
            val blob = AttachmentCrypto.encrypt(bytes, memo?.token, memo?.key)
            if (memo == null) {
                attachmentDeposits[messageId] = AttachmentDeposit(blob.token, blob.key)
            }
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
            // A 409 ON OUR OWN MEMOIZED TOKEN IS SUCCESS, NOT FAILURE (0.10.2 item 5, v6).
            //
            // THIS FIXES A LIVE DEFECT IN SHIPPED CODE, not a hypothetical: `blobId` is
            // `sha256(token)` and the token is MEMOIZED per message, so the FIRST RETRY TAP OF ANY
            // ATTACHMENT re-deposits the same id, the relay answers 409 `blob_exists`
            // (`StoreBlob`'s `ON CONFLICT (blob_id) DO NOTHING` → `ErrBlobExists`), and the throw
            // fails the retry. Every attachment retry is guaranteed to fail today.
            //
            // Treating it as success is safe end to end, and each clause was checked rather than
            // assumed: the token carries 256 bits of OUR OWN entropy, so a 409 means OUR row is the
            // one already stored — not a collision; `digest` and `size` in the control payload are
            // derived from the PLAINTEXT and are therefore attempt-independent; the AES key is
            // memoized with the token, so it opens whichever attempt's bytes the relay kept; and the
            // nonce travels inside the box, so the stored ciphertext is self-describing.
            //
            // This is also why the reversal was abandoned: the memoized token plus `ON CONFLICT` is a
            // DB-ENFORCED one-row-per-message cap that needs no network call, no bearer, no limiter
            // budget and no surviving session. Five design plans proposed replacing it with
            // best-effort client cleanup; all five were rejected, and this is the mechanism they
            // would have given up.
            runCatching { api.uploadBlob(b64(blob.blobId), b64(blob.box)) }
                .onFailure { e ->
                    val alreadyOurs = e is ApiClient.ApiException && e.code == HTTP_CONFLICT
                    if (!alreadyOurs) throw e
                    diag("send: blob already deposited under our token — reusing it")
                }

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
                // ROUTE (a) — item 5b. The blob is already deposited by this point and this send is
                // over, so nothing will ever fetch it: reclaim it rather than leave up to 8 MiB to
                // wait out the full TTL. Abandoning cannot strand a later attempt, because item 5a
                // memoises the token — a retry re-deposits under the SAME blob id.
                // STILL NOT WIRED, and the reason CHANGED in 0.10.3 — do not re-read this as the
                // old P1. That P1 (a stale fire-and-forget abandon deleting the blob a retry had
                // just re-published under the same memoized id) is now structurally prevented by
                // settleDecision: it SKIPs SENDING and FAILED, so a retryable message is never
                // reclaimed. What blocks THIS route is narrower — a non-durable flush leaves the
                // message retryable, so settleDecision would correctly SKIP it anyway and the call
                // would be dead code. Wiring it needs a settle at the point the message stops being
                // retryable, which is a separate change. Orphans here still wait out the blob TTL.
                return@runCatching
            }
            // The NON-SUSPENDING publish tail (see [publishOutgoing]), called directly and FIRST. If
            // the contact was deleted mid-upload it drops the envelope AND the local copy (incl. the
            // in-memory attachment bytes).
            // Cover traffic (U3) — see [deliverText]. An attachment's control payload is an ordinary
            // message.send on the wire and is paired exactly like one, strictly after it and only on
            // a genuine handoff.
            if (publishOutgoing(envelope, conversation.contactId, messageId)) coverTraffic.cover(envelope)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            // Upload throw or transport error — the attachment never made it out.
            messages.markFailed(messageId)
            // ROUTE (c) — item 5b, best effort. If the throw came AFTER a successful deposit the blob
            // is an orphan; if before, there is nothing to delete and the relay's 204 says so without
            // revealing which. The memo is the only place the token survives this far.
            // STILL NOT WIRED, same as the route above — plus a hazard for whoever wires it. Stated
            // as a CONSTRAINT ON FUTURE WORK, not as a description of this code: an earlier version
            // of this comment asserted in the present tense that the route "reads the shared memo
            // after suspension", and round-3 review correctly objected that it reads
            // attachmentDeposits nowhere at all. The hazard is real but conditional — if this is
            // ever wired by looking the memo up here, that lookup happens AFTER arbitrary
            // suspension and can return a LATER attempt's token. settleDecision would not catch it:
            // the message id is the same, so the decision would be about the right message and the
            // wrong blob. Capture the token before suspending; do not re-read the map after.
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
            // R-U3-5 step 1 — see [acceptingSends] and [deliverText]. The ids stay unqueued on
            // purpose: the session is going away, and nothing survives it.
            if (!acceptingSends) return@launch
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
                // The NON-SUSPENDING publish tail (see [publishReceipt]), called directly and FIRST.
                // Cover traffic (U3) — see [deliverText]. A receipt is paired like every other
                // envelope through this choke point, and deliberately so: a receipt envelope is
                // built to be indistinguishable from a text message, and pairing only text would
                // hand an observer the receipt detector that indistinguishability denies it. Only on
                // a genuine handoff: a queued receipt has put nothing on the wire to cover.
                if (publishReceipt(envelope, contactId, messageIds)) coverTraffic.cover(envelope)
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
                // Vault path. Capture the burn set FIRST (read-only) but fire NOTHING yet: burning
                // local history and emitting peer burn frames before the delete is known to have
                // APPLIED would irreversibly destroy the messages of a contact whose deletion then
                // failed (NOT_APPLIED — teardown race), stranding the user with a kept contact and
                // vanished history (round 11, Codex). The frames are sent manually with the
                // captured contactId below because after an applied delete the roster row is gone
                // and burnAll's per-message hook could no longer resolve the peer.
                val at = System.currentTimeMillis()
                val burnIds = messages.messages.value[conversationId].orEmpty()
                    .filter { it.state != MessageState.BURNING }
                    .map { it.id }
                // The atomic teardown: crypto records + roster entry + tombstone seal in ONE
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
                    // RAM-only cleanup — safe regardless of durability, the contact is gone from
                    // live state. NOTE the irreversible PEER burn is NOT here (round 13, Codex
                    // P1-B): the local history is RAM-only (gone on any reload), but telling the
                    // peer to shred its copy must not outrun durable confirmation of the deletion.
                    messages.burnAll(conversationId, notifyPeer = false)
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
                    ContactDeleteOutcome.DURABLE ->
                        // The deletion is durable — the irreversible peer burn is safe now.
                        burnIds.forEach { ws.burnMessage(it, contactId) }
                    ContactDeleteOutcome.APPLIED_UNCONFIRMED -> {
                        diag("delete: vault teardown applied in memory + live state; durable flush " +
                            "unconfirmed — retrying in the background before the peer burn")
                        // Round 13 (Codex P1-B): the removal is applied in RAM + scheduled, but a
                        // bare failed flush does NOT re-arm the coalesced reseal, so it can sit
                        // un-durable until an unrelated mutation/teardown; a process kill in that
                        // window RESURRECTS the contact on the next unlock. The peer burn MUST be
                        // gated on durability — if the contact can come back, the peer must still
                        // hold the history (else an empty contact resurrects while the peer lost
                        // its messages for a delete that never took). Retry the flush; send the
                        // burn frames ONLY once a flush confirms durable. A still-unconfirmed exit
                        // (retries exhausted / scope cancelled / process killed) leaves the peer
                        // un-burned — consistent with a resurrected contact — the documented
                        // resurrect-on-unlock residual (user re-deletes), never a burnt-but-back
                        // inconsistency.
                        scope.launch(confined) {
                            var backoffMs = 1_000L
                            repeat(FLUSH_RETRY_ATTEMPTS) {
                                delay(backoffMs)
                                backoffMs *= 2
                                val confirmed = try {
                                    flushBeforeAck()
                                    true
                                } catch (c: CancellationException) {
                                    throw c
                                } catch (_: Throwable) {
                                    false
                                }
                                if (confirmed) {
                                    diag("delete: deferred durable flush confirmed — sending peer burn")
                                    burnIds.forEach { ws.burnMessage(it, contactId) }
                                    return@launch
                                }
                            }
                            diag("delete: durable flush still unconfirmed after retries — peer burn " +
                                "withheld; contact resurrects on unlock with peer history intact")
                        }
                    }
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

    /**
     * Wipes the server account AND the local keys/messages. Irreversible. Round 13: local
     * destruction happens ONLY on a DEFINITE server-confirmed deletion — never on a swallowed
     * transport/HTTP failure (the round-12 P1: destroying the only local keys while the server
     * account stayed live and orphaned).
     *
     *  - [onConfirmed]  — the server account is confirmed gone AND that confirmation is durably
     *    recorded; RAM state is torn down and the caller destroys the local vault + routes.
     *  - [onNotConfirmed] — the server did NOT confirm deletion (definiteFailure = true when the
     *    server refused, false when the outcome is ambiguous/offline). NOTHING is destroyed; the
     *    session stays live; the intent marker is KEPT (never silently abandoned, round 14 F1); the
     *    caller lifts the terminal-wipe gate and surfaces a retry (reconciled on the next unlock).
     *  - [onConfirmedNotDurable] — the server IS gone but the confirmed marker could not be made
     *    durable (round 14 F1). NOTHING is destroyed and auth is NOT cleared; the intent marker is
     *    KEPT so the next unlock's reconcile repeats the (now idempotent-404) DELETE and records
     *    confirmation durably. Caller lifts the gate + surfaces.
     *  - [onIntentNotDurable] — the intent marker itself could not be made durable; the delete
     *    never touched the server. Caller lifts the gate.
     */
    fun deleteAccountAndWipe(
        onConfirmed: () -> Unit,
        onNotConfirmed: (definiteFailure: Boolean) -> Unit = {},
        onConfirmedNotDurable: () -> Unit = {},
        onIntentNotDurable: () -> Unit = {},
    ) {
        // NonCancellable: the session scope this launches on is cancelled by
        // UnlockController.lock() (e.g. a server revocation racing the delete).
        // The server-side delete and the DURABLE roster clear must complete once
        // started — pre-D2b the process-lifetime scope guaranteed that; this
        // preserves it. Bounded work; onConfirmed's lock() is idempotent.
        scope.launch(confined + NonCancellable) {
          // deleteInFlight guards the WHOLE flow (round 15, R14-1): while set, no OTHER auth-clearing
          // path (notably [onSessionRevoked], which runs async on the socket thread) may strip the
          // vault-backed tokens — clearing them in the intent→confirmed window would defeat the
          // crash-recovery reconcile that needs auth to reach the idempotent 404. Cleared in the
          // finally on EVERY exit so a throw can never latch it on. Set BEFORE the DELETE and held
          // through destroy() (which removes auth with the vault, after which a clear is moot).
          deleteInFlight = true
          try {
            // 1. Persist the deletion INTENT durably FIRST — before api.deleteAccount() can leave
            // the device. It means ONLY "delete initiated" and NEVER authorises destruction, so a
            // crash here leaves a fully valid, unlockable vault (round 13). If it can't be made
            // durable, ABORT untouched.
            val intentDurable = try {
                persistDeleteIntent()
                true
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (!intentDurable) {
                onIntentNotDurable()
                return@launch
            }
            // 2. Server delete, session STILL FULLY LIVE (so a not-confirmed outcome can resume
            // normally, and a retry stays authenticated). Returns a DEFINITE result — never a
            // swallowed throw.
            val result = try {
                api.deleteAccount()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                ApiClient.AccountDeleteResult.AMBIGUOUS
            }
            if (result != ApiClient.AccountDeleteResult.CONFIRMED_GONE) {
                // NOT gone: destroy NOTHING and KEEP the intent marker — never silently abandon
                // (round 14, F1). A DEFINITE failure is an auth/permission problem (the account
                // still exists); an AMBIGUOUS outcome is offline/5xx. Either way the session stays
                // live and the intent persists, so the next unlock's reconcile repeats the DELETE.
                onNotConfirmed(result == ApiClient.AccountDeleteResult.DEFINITE_FAILURE)
                return@launch
            }
            // 3. CONFIRMED gone: record the confirmation REQUIRED-durable (round 14, F1 — no more
            // best-effort swallow). Until vault.delete-confirmed is durable, a crash would strand a
            // gone-server-account against a live vault with no auto-destroy authorization. On a
            // non-durable write, tear down NOTHING and clear NO auth: keep the session + the intent
            // marker so the next unlock's reconcile repeats the (idempotent-404) DELETE and records
            // confirmation. Auth is NOT cleared anywhere in this flow now — it is vault-backed and
            // destroyed with the vault by destroy(), which also keeps it available for the reconcile.
            val confirmedDurable = try {
                persistServerDeleteConfirmed()
                true
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (!confirmedDurable) {
                onConfirmedNotDurable()
                return@launch
            }
            // 4. Confirmation is durable — only now tear the session/RAM down. From here the server
            // account is gone AND recorded, so destruction is unconditionally safe and recoverable.
            acceptingDeliveries = false
            acceptingSends = false
            _linking.value = false
            linkJob?.cancel()
            // The SAME cover-traffic-then-transport teardown as [stop] (U3 fix round 3): the account
            // delete is a teardown too, and a pairing left mid-gap here would leave the same
            // teardown-correlated unpaired real frame on the wire. Run through the ON-WORKER entry
            // point rather than the dispatching one, because this coroutine is already ON the
            // confined worker — dispatching to it from itself and then blocking on the result would
            // stall the worker against its own queue for the whole bound.
            coverWorker.runTerminalHere(::coverTeardown)
            messages.clearAll()
            conversations.clearAll()
            // Teardown hook: no re-fire job or fire state survives the wipe.
            notificationScheduler.cancelAll()
            onConfirmed()
          } finally {
            deleteInFlight = false
          }
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
                // R-U4-1 — a cover frame never becomes a message. The synthetic cover account
                // replies occasionally (U4), and its reply must not reach decryption, the message
                // store, the roster, the unread count or the notification scheduler. Checked FIRST
                // and BEFORE decrypt: see [isSyntheticSender] for why "it would fail to decrypt
                // anyway" is not a defence.
                //
                // Acked BARE, unlike the tombstone branch below, and the difference is deliberate.
                // That branch needs ackDurable because the tombstone it keys on may still be
                // RAM-only, and acking early could let the relay discard a REAL message while a
                // crash restored the pre-delete vault. Here there is no real message to lose: the
                // envelope is cover traffic that must never surface, so dropping the relay's copy
                // immediately is the outcome we want, not a risk we are taking. A crash before the
                // decoy section is durable loses the synthetic account id — and the envelope with
                // it, since the relay no longer holds one to redeliver.
                //
                // AND IT IS SILENT. There is no diag() here, deliberately, and that is a fix (U4
                // review round 4, Codex). The first version logged "cover-account envelope —
                // dropped before decrypt", which BootDiagnostics.record writes to
                // boot-diagnostics.log on disk and surfaces in Settings → Diagnostics. That is a
                // durable, timestamped, user-copyable record that THIS DEVICE received cover
                // traffic — which is evidence that a vault with a provisioned synthetic account
                // exists here, and it survives the process that wrote it. Plausible deniability is
                // the product, so a log line distinguishing "uses cover traffic" from "never did"
                // is a leak of exactly the kind the vault exists to prevent.
                //
                // Every other decoy surface already holds this discipline — the pairing, the
                // builder and the provisioner take no logger at all and fail silent — and this
                // guard was the one place in U4 that broke it.
                if (isSyntheticSender(envelope.senderId)) {
                    ws.ackMessage(envelope.id)
                    return@runCatching
                }
                if (isDeletedContact(envelope.senderId)) {
                    diag("recv: message for deleted contact — dropped before decrypt")
                    // The drop happens BEFORE decrypt, so THIS branch mutates nothing — but the
                    // TOMBSTONE it keys on may itself still be RAM-only (an APPLIED_UNCONFIRMED
                    // delete whose flush hasn't confirmed). Acking bare would let the relay
                    // discard the message while a crash restores the pre-delete vault generation:
                    // contact back, message permanently gone (round 8, Codex). ackDurable forces
                    // the dirty state (the deletion included) durable first; on a non-durable
                    // flush the straggler stays un-acked (redelivered → re-dropped here).
                    ackDurable(envelope.id)
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
        // The relay has it: no further attempt will re-deposit under this id, so the memoized blob
        // secrets are dead weight (0.10.2 item 5a — release, or the map grows for the process's life).
        releaseDeposit(messageId)
    }

    /**
     * Recipient's peer-routed delivery receipt → DELIVERED tick. This is the
     * FIRST honest proof the message reached the other device, so it — not
     * ws-enqueue — advances the tick AND starts the sender-side TTL (see
     * [MessageRepository.markDelivered]).
     */
    override fun onMessageDelivered(messageId: String) {
        messages.markDelivered(messageId)
        // Belt and braces: a delivery receipt can arrive without a preceding `message.stored`.
        releaseDeposit(messageId)
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
                    // TWO-PHASE attempted marker (round 8, Codex): mark the batch ATTEMPTED and
                    // reseal that durable BEFORE the request leaves — a lost response / crash
                    // after the upload must never re-serve possibly-consumed ids (the relay
                    // re-inserts a consumed id). The ordering keeps the flush-gated skip above
                    // re-servable: the flag is only ever durable for a batch whose request was
                    // genuinely about to exist. A non-durable second flush skips the upload too
                    // (the RAM-only flag rolls back on crash → safe re-serve; in-process it
                    // conservatively generates a fresh batch next signal).
                    signal.markOneTimePreKeyUploadAttempted()
                    if (flushBeforePreKeyPublish {
                            diag("prekey: attempted-marker reseal not durable — upload deferred")
                        }
                    ) {
                        api.uploadPreKeys(oneTimePreKeys)
                        signal.confirmOneTimePreKeysUploaded()
                    }
                }
            }
        }
    }

    override fun onSessionRevoked() {
        // A revoke must NOT clear tokens or tear the session down while a delete is PENDING (round
        // 16, R15-P2). "Pending" is the DURABLE intent marker's lifetime — from its durable write
        // until a confirmed destroy() retires it — which persists across DEFINITE_FAILURE /
        // AMBIGUOUS / confirmed-not-durable exits AND process restart, long after this coroutine
        // ends. Stripping the vault-backed tokens in that window would strand a completed- (or
        // ambiguously-) deleted account: the next-unlock reconcile could no longer authenticate the
        // idempotent 404. `deleteInFlight` additionally covers the sub-window before the intent
        // marker is durable. The delete flow owns teardown (CONFIRMED → destroy; not-confirmed →
        // keep the session, a later 401 / reconcile handles the stale session), so during a pending
        // delete this revoke is a no-op. Server-side deletion itself commonly triggers this revoke.
        if (deleteInFlight || intentMarkerPresent()) return
        // Fast, thread-safe teardown on the socket callback thread: stop the
        // relink loop, drop tokens, and — BEFORE the UI is bounced to the gate —
        // synchronously cancel every armed reminder job. Re-fire jobs run on
        // the container scope (not the confined dispatcher), so one at its
        // boundary could otherwise alert AFTER the user sees the logged-out
        // state but before the queued cleanup below runs.
        _linking.value = false
        acceptingDeliveries = false
        // R-U3-5 step 1 on the revoke path too: the tokens are about to go, so a send admitted from
        // here on could only fail — and [onForcedLogout] below runs the real teardown.
        acceptingSends = false
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

    override fun onServerError(code: String, message: String, messageId: String?) {
        // Server error codes carry no user data; v1 surfaces them only as connection state, never as
        // raw strings.
        //
        // THE ROUTING ITSELF LIVES IN [routeServerError] (0.10.1 review round 2). It used to be two
        // statements here, guarded only by a source tripwire, because nothing in the suite can
        // construct a MessagingCoordinator. Both blind reviewers ruled that insufficient and both
        // proposed this same extraction rather than a Robolectric harness.
        //
        // ONE JUSTIFICATION THAT WAS OFFERED FOR IT IS FALSE, and is corrected here rather than left
        // to teach the wrong lesson (round 4): the extraction was partly argued on "the missing
        // harness is what let round 2's P1 escape". It did not. That P1 was arm-at-addOutgoing
        // timing, caught by a MessageRepository behavioural test with no coordinator harness
        // involved, and this extraction would not have caught it either. The extraction earns its
        // place on routing behaviour alone — which it does cover, and which was previously only
        // pattern-matched. The two decisions and their
        // independence (the yield fires on the CODE, the failure on the ID, neither nested in the
        // other) are now covered by behavioural tests instead of by matching source text.
        //
        // What is left here is WIRING, which is what the reduced tripwire pins: this must delegate,
        // and it must pass the cover seam and the relay-attributed failure entry point — not, say,
        // `markFailed`, whose wider CAS would let an error contradict a receipt the relay already
        // gave us.
        routeServerError(
            code = code,
            messageId = messageId,
            yieldCover = { coverTraffic.onRelayRateLimited() },
            failByRelay = messages::markFailedByRelay,
        )
    }

    private companion object {
        /** The relay's "this blob id is already stored" status — see the deposit call site. */
        const val HTTP_CONFLICT = 409

        /** Logcat tag for boot-stage transport diagnostics — see class kdoc. */
        const val TAG = "ZitroneBoot"

        /** Boot-retry backoff base — doubled per attempt up to [MAX_BACKOFF_MS]. */
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

/** Wall-clock threshold for the non-blocking "taking longer than expected" prompt (contract §6.3). */
internal const val REGISTRATION_POW_PROMPT_AT_SECONDS = 60L

/**
 * Which [RegistrationPowState] a RUNNING solve renders on a ticker tick — extracted so the
 * precedence is host-testable. BACKGROUNDED wins over the 60s prompt (a prompt is only
 * meaningful to a user who is looking; on return the very next tick re-raises it if still
 * due), a dismissed prompt stays dismissed for the remainder of the solve, and an
 * unanswerable foreground probe (null) is NOT treated as backgrounded — BACKGROUNDED claims
 * "you left, we're still working", which must not be claimed on no evidence. Terminal states
 * (COMPLETE/CANCELLED/IDLE) are never produced here; the solve path owns them.
 */
internal fun registrationPowTickState(
    elapsedSeconds: Long,
    promptDismissed: Boolean,
    inForeground: Boolean?,
): RegistrationPowState = when {
    inForeground == false -> RegistrationPowState.BACKGROUNDED
    elapsedSeconds >= REGISTRATION_POW_PROMPT_AT_SECONDS && !promptDismissed ->
        RegistrationPowState.PROMPTED_AT_60S
    else -> RegistrationPowState.SOLVING
}

/** Attempts (incl. the first) [flushThenAck] makes at a TRANSIENT durable-flush blip. */
internal const val FLUSH_MAX_ATTEMPTS = 3

/** Background durability-retry attempts for an APPLIED_UNCONFIRMED contact delete (1s/2s/…). */
internal const val FLUSH_RETRY_ATTEMPTS = 5

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
