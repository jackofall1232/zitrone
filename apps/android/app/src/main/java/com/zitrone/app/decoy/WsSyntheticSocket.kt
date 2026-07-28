package com.zitrone.app.decoy

import com.zitrone.app.data.MessageEnvelope
import com.zitrone.app.net.WsClient

/**
 * The production [DecoyInboundSession.SyntheticSocket] — the synthetic account's own [WsClient].
 *
 * A second socket, not a second network: it shares the device's uplink and the same transport
 * endpoints as the real one, and it is redialled on a transport swap by the same code that redials
 * the real one. R-U4-4's yield exists because of that sharing.
 *
 * ## Every inbound event except delivery is dropped, and that is the whole design
 *
 * The synthetic account is not a user. It has no UI, no message store, no roster and no session
 * state, so there is nothing for a receipt, a burn notice, a typing indicator or a prekey-low
 * warning to update. **Dropping them is not an omission to be filled in later** — routing any of
 * them anywhere is what would violate R-U4-2, which is a statement about this type's dependencies:
 * it holds a `WsClient` and a callback, and it cannot reach a decryptor or a store because it has
 * neither.
 *
 * `onAuthExpired` is dropped too, and that one is a **declared residual** rather than a design
 * point: the synthetic socket simply stops until the session is rebuilt. Reconnecting would mean a
 * token refresh on the inbound callback thread, and cover traffic that goes quiet is degradation,
 * which R-U4-6 permits — it is not disclosure, because a client whose cover account has no live
 * socket looks exactly like one that never provisioned.
 */
class WsSyntheticSocket(private val ws: WsClient) : DecoyInboundSession.SyntheticSocket {

    override var onDeliver: ((MessageEnvelope) -> Unit)? = null

    init {
        ws.listener = object : WsClient.Listener {
            override fun onMessageDeliver(envelope: MessageEnvelope) {
                onDeliver?.invoke(envelope)
            }

            override fun onMessageBurned(messageId: String) = Unit
            override fun onMessageStored(messageId: String) = Unit
            override fun onMessageDelivered(messageId: String) = Unit
            override fun onTyping(senderId: String, started: Boolean) = Unit
            override fun onPreKeyLow(remaining: Int) = Unit
            override fun onSessionRevoked() = Unit
            override fun onAuthExpired() = Unit
            override fun onServerError(code: String, message: String) = Unit
        }
    }

    override fun connect(accessToken: String) = ws.connect(accessToken)

    override fun disconnect() = ws.disconnect()

    override fun ack(messageId: String): Boolean = ws.ackMessage(messageId)

    override fun burn(messageId: String, peerId: String): Boolean = ws.burnMessage(messageId, peerId)

    override fun send(envelope: MessageEnvelope): Boolean = ws.sendMessage(envelope)
}
