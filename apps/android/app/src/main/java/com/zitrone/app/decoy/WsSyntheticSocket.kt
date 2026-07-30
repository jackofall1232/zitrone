package com.zitrone.app.decoy

import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.net.WsClient
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * The production [DecoyInboundSession.SyntheticSocket] — the synthetic account's own [WsClient].
 *
 * A second socket, not a second network: it dials the same endpoints as the real one and shares the
 * device's uplink, which is why R-U4-4's yield sums both queues and why a transport swap re-points
 * both.
 *
 * ## It BUILDS its socket rather than accepting one, and that is the point
 *
 * This class takes no [WsClient] parameter. It cannot be handed the real one, because it cannot be
 * handed one at all.
 *
 * That is a structural answer to a finding three consecutive review rounds raised in three different
 * forms. U3's disconnect-ownership guard requires every `disconnect()` in the app to be owned by
 * cover traffic, because a disconnect of the **real** socket outside that ownership can split a
 * real/cover pair. This class is exempt — the synthetic socket carries no pairings, so disconnecting
 * it can split nothing — and the exemption was originally justified by a *test* asserting that the
 * injected client was the decoy one. Each round defeated the previous assertion with a cheaper
 * trick: rename the local; alias it inside this file; and finally point the decoy binding itself at
 * the real client, so every name downstream stayed honest while the object was wrong.
 *
 * All three share a root cause: **the property was being checked lexically because the type
 * permitted the mistake.** With no injection point there is nothing to check and nothing to spoof —
 * the socket this class disconnects is one it constructed, and the compiler enforces that.
 *
 * ## Every inbound event except delivery is dropped, and that is the design
 *
 * The synthetic account is not a user. It has no UI, message store, roster or session state, so
 * there is nothing for a receipt, a burn notice, a typing indicator or a prekey-low warning to
 * update. Routing any of them anywhere is what would violate R-U4-2.
 *
 * `onAuthExpired` is dropped too, and that one is a **declared residual** rather than a design
 * point: an expired synthetic JWT takes this side quiet until the session is rebuilt. Cover that
 * goes quiet is degradation, which R-U4-6 permits — a client whose cover account has no live socket
 * looks exactly like one that never provisioned.
 *
 * `rate_limited` is the single exception, routed to [onRateLimited] — the meter's **synthetic**
 * channel, never the shared one. See `CoverPressure.syntheticRateLimited` for why that is
 * load-bearing.
 */
class WsSyntheticSocket(
    wsUrl: String,
    httpClient: OkHttpClient,
    scope: CoroutineScope,
    /**
     * The relay refused a frame on the SYNTHETIC account for volume. Routed into the meter's
     * synthetic channel, which gates send-backs and nothing else: arming the shared window from
     * here would let one relay frame black out cover for every real send.
     */
    private val onRateLimited: () -> Unit = {},
) : DecoyInboundSession.SyntheticSocket {

    override var onDeliver: ((MessageEnvelope) -> Unit)? = null

    /**
     * Installed on [ws] below, and `internal` so tests can drive the routing table directly without
     * a relay. Exposing the listener is deliberate where exposing a settable socket is not: a test
     * can invoke it, but nothing can substitute the socket it was installed on.
     */
    internal val listener: WsClient.Listener = object : WsClient.Listener {
        override fun onMessageDeliver(envelope: MessageEnvelope) {
            onDeliver?.invoke(envelope)
        }

        // `messageId` is accepted and DELIBERATELY IGNORED (0.10.1). The synthetic account's
        // rejections are cover-traffic events, never user-facing ones: attributing one would mean
        // surfacing a decoy's failure to a user who is not supposed to know decoys exist. The id
        // could only ever name a cover envelope, which owns no Message row, so there is nothing
        // here to attribute even if we wanted to. The rate_limited routing is unchanged and stays
        // the meter's SYNTHETIC channel — see the class kdoc for why that separation is
        // load-bearing.
        override fun onServerError(code: String, message: String, messageId: String?) {
            if (code == RATE_LIMITED) onRateLimited()
        }

        override fun onMessageBurned(messageId: String) = Unit
        override fun onMessageStored(messageId: String) = Unit
        override fun onMessageDelivered(messageId: String) = Unit
        override fun onTyping(senderId: String, started: Boolean) = Unit
        override fun onPreKeyLow(remaining: Int) = Unit
        override fun onSessionRevoked() = Unit
        override fun onAuthExpired() = Unit
    }

    // No diagnostics sink, and no NAMED sink parameter left to wire one through (U4 review round
    // 5, both lenses). WsClient's own default is the silent `{}`; every lifecycle line it would
    // otherwise emit — handshake, connected, closed, failure — is durable, timestamped evidence of
    // a SECOND socket once a sink like BootDiagnostics.record is attached, and synthetic handshake
    // failures surfacing anywhere violates R-U4-6's "dropped silently". The real socket logs for
    // connectivity UX; this account has no UX.
    //
    // What this does NOT claim (U4 review round 6, Codex): `httpClient` and `onRateLimited` are
    // still constructor parameters, and both are opaque — an OkHttpClient carrying an
    // EventListener or interceptor would observe this connection durably, and a callback can call
    // anything. The boundary is held by what production passes (a hook-free client, pinned by a
    // tripwire over every client builder, and an in-memory meter callback), not by this type.
    private val ws = WsClient(wsUrl, httpClient, scope).also { it.listener = listener }

    /** Re-point at new endpoints on a transport swap. The redial is [DecoyInboundSession.reconnect]. */
    fun updateTransport(newClient: OkHttpClient, newWsUrl: String) =
        ws.updateTransport(newClient, newWsUrl)

    /** This socket's share of the device uplink, for the shared pressure meter's queue limb. */
    fun outboundQueueBytes(): Long = ws.outboundQueueBytes()

    override fun connect(accessToken: String) = ws.connect(accessToken)

    override fun disconnect() = ws.disconnect()

    override fun ack(messageId: String): Boolean = ws.ackMessage(messageId)

    override fun burn(messageId: String, peerId: String): Boolean = ws.burnMessage(messageId, peerId)

    override fun send(envelope: MessageEnvelope): Boolean = ws.sendMessage(envelope)

    private companion object {
        /** Matches `MessagingCoordinator.ERROR_RATE_LIMITED`; the relay's own code. */
        const val RATE_LIMITED = "rate_limited"
    }
}
