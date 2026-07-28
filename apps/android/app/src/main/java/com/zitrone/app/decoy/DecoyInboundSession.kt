package com.zitrone.app.decoy

import com.zitrone.app.data.MessageEnvelope
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * U4 — the synthetic side of the cover exchange.
 *
 * The synthetic account holds its own relay socket, acknowledges the cover envelopes addressed to
 * it, burns them a moment later, and occasionally replies. Its whole purpose is stated narrowly in
 * spec §4.4: **§2.4 declares the control channel uncovered**, and a cover exchange without this
 * class is *conspicuously one-directional* — envelopes flow to the synthetic account and nothing
 * ever comes back, which no real conversation does. This is the partial mitigation §2.4 already
 * promised. **It does not close the control channel and must never be described as doing so.**
 *
 * ## What this class deliberately cannot do
 *
 * It holds no [com.zitrone.app.crypto.SignalProtocolManager], no vault store, and no writer of any
 * kind. **R-U4-2** (never decrypts, never establishes a session, never advances a ratchet) and
 * **R-U4-3** (adds no durable-state writer) are therefore properties of this type's *dependencies*,
 * checkable by reading its constructor rather than by tracing its behaviour. A reviewer should
 * check exactly that: nothing here calls `runtime.mutate`, `DecoySectionLock.withSection`, or
 * `storeTokensForAccount`.
 *
 * That is also why teardown is trivial, and the contrast with U3 is worth stating because U3's
 * teardown cost five review rounds. [DecoySendPairing] had to serialise against the vault runtime
 * closing, because an in-flight pairing outliving its transport was a disclosure. This class has
 * **nothing to make durable**, so [stop] disconnects and cancels; there is no bounded wait, no
 * confinement contract, and no fallback path to get wrong.
 *
 * ## The ack and the burn do NOT yield; the send-back does
 *
 * **R-U4-4.** Under contention the send-back is dropped, because it is the purely optional half.
 * The ack and the burn are exempt **on purpose**, and the reasoning is R-U3's disclosure-vs-
 * degradation rule applied unchanged: a cover frame missing under load is *degradation*, but an ack
 * that never fires leaves the relay **holding a cover envelope and retrying delivery** — a durable,
 * observable artefact that would make load itself disclosable. Shedding acks would trade a cheap
 * cost for an expensive leak.
 *
 * ## Failure is silent, and the socket must not outlive the real session
 *
 * **R-U4-6.** Every failure here — a dead socket, a refused ack, a builder that declines a reply —
 * is dropped without a retry, a log line or a UI signal. The bound is not a rate; it is disclosure:
 * this side must not fail in ways that reveal events an observer cannot already observe.
 *
 * The one case that nearly violates it is recorded in §4.4 and is the reason [stop] exists: the
 * synthetic socket disconnects when the vault locks, because its credentials live in the vault.
 * That discloses nothing, because the **real** socket disconnects at the same instant on the same
 * link and is the larger flow. The converse is what would leak — a synthetic socket that stayed up
 * across a lock would disclose the lock *by contrast*, being the one flow that did not stop.
 */
class DecoyInboundSession(
    private val scope: CoroutineScope,
    /**
     * This vault's synthetic account id, or null while it has none. Read per use rather than
     * captured — provisioning is lazy and may complete after this session is constructed.
     */
    private val syntheticAccountId: () -> String?,
    /**
     * The real account this vault sends as — the send-back's recipient. Null when there is no
     * usable local identity, in which case no reply is issued.
     */
    private val realAccountId: () -> String?,
    /**
     * A usable access token for the synthetic account, or null. Suspends because it may have to
     * refresh; a null return simply means no synthetic socket this time.
     */
    private val accessToken: suspend () -> String?,
    /** The synthetic account's own socket. A seam so tests need no OkHttp and no relay. */
    private val socket: SyntheticSocket,
    /**
     * The same [CoverPressure] the send pairing consults — **not** a second instance with its own
     * thresholds. R-U4-4: a second connection is not a second network, and the send-back is
     * addressed to the real account, so it consumes that account's inbound routing.
     */
    private val pressure: CoverPressure,
    private val builder: DecoyEnvelopeBuilder = DecoyEnvelopeBuilder(),
    private val random: SecureRandom = SecureRandom(),
    /** Seam for the drawn delays, so tests need no wall clock. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * The synthetic account's own sending-chain counter. In memory by requirement (R-U4-3): it is
     * never persisted, so it restarts at 0 with the process, which is exactly what a real client
     * emits after a ratchet turn.
     */
    private val replyCounter = AtomicInteger(0)

    /** Terminal once [stop] runs. Never cleared — a stopped session is not restarted, it is rebuilt. */
    @Volatile
    private var stopped = false

    /** Claimed by the [start] that reaches [SyntheticSocket.connect]; see that method's kdoc. */
    private val starting = AtomicBoolean(false)

    /** Pending burns and send-backs, so [stop] can cancel work that must not outlive the session. */
    private val pending = mutableSetOf<Job>()

    private val lock = Any()

    /**
     * Open the synthetic socket if this vault has an account and a token. Silent: no account, no
     * token, or an already-stopped session all return without an error, because "cover traffic is
     * off" is a normal state and never a failure the user hears about.
     *
     * **Idempotent, and it has to be, because it is called from two places by design.** Provisioning
     * is lazy (§6.2a: a vault that never sends never spends a registration), so at session start
     * there may be no synthetic account yet and this returns having done nothing; the provisioning
     * path calls it again when an account appears. A returning vault that already has one connects
     * on the first call and the second is a no-op. The latch is claimed **before** the suspending
     * token read so two concurrent callers cannot both reach [SyntheticSocket.connect]; it is
     * released again if the attempt does not get as far as connecting, so a later call can retry.
     */
    suspend fun start() {
        if (stopped || syntheticAccountId() == null) return
        if (!starting.compareAndSet(false, true)) return
        var connected = false
        try {
            val token = runCatching { accessToken() }.getOrNull() ?: return
            if (stopped) return
            socket.onDeliver = ::onCoverDelivered
            runCatching { socket.connect(token) }.onSuccess { connected = true }
        } finally {
            if (!connected) starting.set(false)
        }
    }

    /**
     * Re-dial after a transport swap: drop the socket on the old endpoints and open one on the new.
     *
     * This exists because [start]'s latch is held for as long as the socket is up, so calling it
     * again would be a no-op — the latch is what makes double-start safe, and clearing it is what
     * makes a redial possible. The two operations are here, in one place, rather than left to a
     * caller to sequence.
     *
     * Non-terminal by construction: it does not set [stopped] and does not cancel pending burns,
     * because the session survives a transport toggle. A burn whose frame is drawn mid-swap simply
     * fails on a dead socket and is dropped, which is degradation (R-U4-6).
     */
    suspend fun reconnect() {
        if (stopped) return
        runCatching { socket.disconnect() }
        starting.set(false)
        start()
    }

    /**
     * Terminal teardown. Disconnects and cancels every pending burn and send-back.
     *
     * Unlike [DecoySendPairing.stop] there is nothing to drain: no frame here is half-committed to
     * durable state, so cancelling a pending burn loses nothing that has to be recovered. A burn
     * that never fires leaves the relay's copy to its own TTL, which is degradation.
     */
    fun stop() {
        stopped = true
        val cancelling = synchronized(lock) { pending.toList().also { pending.clear() } }
        cancelling.forEach { it.cancel() }
        socket.onDeliver = null
        runCatching { socket.disconnect() }
    }

    /**
     * A cover envelope arrived for the synthetic account.
     *
     * Acknowledge immediately so the relay drops its copy, then schedule the burn and — sometimes —
     * a reply. **Nothing here decrypts, parses, or stores the envelope**: the id and the sender are
     * relay-assigned routing fields, and they are all this side ever reads (R-U4-2).
     */
    private fun onCoverDelivered(envelope: MessageEnvelope) {
        if (stopped) return
        // Not conditioned on pressure — see the class kdoc. An unacked cover envelope is one the
        // relay keeps retrying, which turns load into a durable observable.
        runCatching { socket.ack(envelope.id) }
        launchTracked {
            sleep(burnDelayMs())
            if (!stopped) runCatching { socket.burn(envelope.id, envelope.senderId) }
        }
        if (shouldReply()) launchTracked { sendBack(envelope) }
    }

    /**
     * The send-back (R-U4-4): the optional half, and the only part of this class that yields.
     *
     * Pressure is checked **after** the delay rather than before, so the decision reflects the
     * network at the moment the frame would go out rather than one drawn interval earlier. A reply
     * that is declined is simply not sent; there is no retry and no queue.
     */
    private suspend fun sendBack(received: MessageEnvelope) {
        sleep(replyDelayMs())
        if (stopped || pressure.yielding()) return
        val from = syntheticAccountId() ?: return
        val to = realAccountId() ?: return
        // buildReply refuses rather than emitting a mis-shaped frame (a received ciphertext too
        // short to carry a padded block, an id that is not this account's). Declining is correct
        // and silent: a send-back is optional by construction.
        val reply = runCatching {
            builder.buildReply(
                replyingAccountId = from,
                recipientAccountId = to,
                received = received,
                counter = replyCounter.getAndIncrement(),
            )
        }.getOrNull() ?: return
        if (stopped) return
        runCatching { socket.send(reply) }
    }

    /**
     * Run [block] as a tracked child so [stop] can cancel it. A job that finishes on its own
     * deregisters itself, so the set cannot grow without bound across a long session.
     *
     * The registration is checked against [stopped] **under the lock and after the job exists**, so
     * a [stop] racing this call cannot leave a job running past teardown: either stop sees the job
     * in the set and cancels it, or this method sees the flag and cancels it here.
     */
    private fun launchTracked(block: suspend () -> Unit) {
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Silent by requirement (R-U4-6). Cover traffic never surfaces a failure.
            }
        }
        val cancelNow = synchronized(lock) {
            if (stopped) true else { pending.add(job); false }
        }
        if (cancelNow) job.cancel() else job.invokeOnCompletion { synchronized(lock) { pending.remove(job) } }
    }

    /**
     * The delay between acknowledging a cover envelope and burning it. `VAULT_ARCHITECTURE.md` §8
     * says "burn-on-delivery ~30 ms"; per **R-U4-5** that figure is the design intent for this
     * drawn interval and **not** an assertion about what the relay observes. Drawn per envelope so
     * the interval is not a constant an observer can key on.
     */
    private fun burnDelayMs(): Long =
        BURN_DELAY_MIN_MS + random.nextInt(BURN_DELAY_SPREAD_MS).toLong()

    /** The pause before a send-back, so a reply does not arrive implausibly fast for a human. */
    private fun replyDelayMs(): Long =
        REPLY_DELAY_MIN_MS + random.nextInt(REPLY_DELAY_SPREAD_MS).toLong()

    /** Whether this delivery draws a reply. Occasional by design — every message answered is not a conversation shape. */
    private fun shouldReply(): Boolean = random.nextInt(REPLY_DENOMINATOR) == 0

    /** The synthetic account's socket, narrowed to what U4 uses. */
    interface SyntheticSocket {
        /** Invoked when a cover envelope is delivered to the synthetic account. */
        var onDeliver: ((MessageEnvelope) -> Unit)?

        fun connect(accessToken: String)

        fun disconnect()

        fun ack(messageId: String): Boolean

        fun burn(messageId: String, peerId: String): Boolean

        fun send(envelope: MessageEnvelope): Boolean
    }

    /**
     * Bind this session's teardown to [delegate]'s, and return the [CoverTraffic] the coordinator
     * should hold.
     *
     * **Expressed as a dependency rather than as a convention, for the reason `CoverTraffic.stop`
     * already records:** an ordering that two call sites have to remember is one a later edit
     * breaks silently. The synthetic socket must not outlive the real session (R-U4-6 — a
     * connection still up across a vault lock discloses the lock *by contrast*, being the one flow
     * that did not stop), and routing teardown through the same seam makes that structural.
     *
     * [stop] runs **before** the delegate's, so the synthetic socket is already down while the send
     * pairing drains: a drain emits cover frames, and a synthetic side still acking them during
     * teardown would put its control frames on the wire after the real socket's last real frame.
     *
     * [quiesce] is deliberately **not** wrapped. A transport swap is non-terminal and the session
     * survives it; the synthetic socket is redialled by the caller that owns the endpoints, exactly
     * as the real one is. Tearing this session down there would turn a transport toggle into a
     * permanent loss of the synthetic side, since [stop] is terminal.
     */
    fun bindTo(delegate: CoverTraffic): CoverTraffic = object : CoverTraffic {
        override suspend fun cover(real: MessageEnvelope) = delegate.cover(real)

        override fun onRelayRateLimited() = delegate.onRelayRateLimited()

        override fun stop(invalidateTransport: () -> Unit) {
            this@DecoyInboundSession.stop()
            delegate.stop(invalidateTransport)
        }

        override fun quiesce(swapTransport: () -> Unit) = delegate.quiesce(swapTransport)
    }

    companion object {
        internal const val BURN_DELAY_MIN_MS = 20L
        internal const val BURN_DELAY_SPREAD_MS = 20
        internal const val REPLY_DELAY_MIN_MS = 1_500L
        internal const val REPLY_DELAY_SPREAD_MS = 6_000

        /** One delivery in this many draws a send-back. */
        internal const val REPLY_DENOMINATOR = 4
    }
}
