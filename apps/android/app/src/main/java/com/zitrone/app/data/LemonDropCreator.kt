// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import android.util.Base64
import com.zitrone.app.crypto.EncryptedSignalProtocolStore
import com.zitrone.app.crypto.LemonDropCreate
import com.zitrone.app.crypto.LemonDropOneShot
import com.zitrone.app.crypto.LibsignalXEdDSAVerifier
import com.zitrone.app.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestrates lemon-drop CREATION end to end, mirroring the web store's
 * `sendQrDrop` (apps/web/src/store.ts): fetch a FRESH prekey bundle for the
 * contact, enforce the identity trust boundary, run the one-shot seal + PoW on
 * a cancellable background dispatcher, deposit unauthenticated, and append a
 * local SENT bubble. Returns the sticker URL + expiry so the UI can render the
 * QR and offer save-for-print.
 *
 * ONE-WAY INVARIANT — this NEVER touches the persistent libsignal session or
 * contact records:
 *
 *  - No `signal.establishSession`, no `signal.encrypt`, no session write: the
 *    drop's X3DH + ratchet state is built and discarded inside
 *    [LemonDropCreate.create] (encrypt-and-forget). Contrast [MessagingCoordinator]'s
 *    ordinary send, which advances the persistent ratchet.
 *  - No `conversations.upsert` / no key write: unlike the ordinary send path we
 *    do NOT learn-and-store the relay's identity key (that would be a TOFU write
 *    on a one-shot path), and we do NOT flag a mismatch onto the record — a
 *    changed identity is surfaced to the caller as [Result.IdentityChanged] and
 *    the drop is simply refused. The only local write is the in-memory sent
 *    bubble (plaintext-in-RAM only, exactly like every other outgoing message).
 *
 * PRIVATE-SCALAR BRIDGE — the same scoped exception LemonDropRedeemer holds on
 * the open side: [senderIdentity] pulls the raw X25519 scalar out of
 * [EncryptedSignalProtocolStore] for the one-shot X3DH, which ordinary code must
 * never do. It is private to this class, feeds only [LemonDropCreate.create]
 * (which zeros it), and must not be widened.
 */
class LemonDropCreator(
    private val api: ApiClient,
    private val signalStore: EncryptedSignalProtocolStore,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val sodium: LemonDropOneShot.SodiumOps,
    private val verifyXEdDSA: LemonDropCreate.XEdDSAVerifier = LibsignalXEdDSAVerifier,
) {

    sealed interface Result {
        /** Sealed + deposited. The URL is the sticker; [expiresAt] is ISO 8601. */
        data class Success(val url: String, val expiresAt: String) : Result

        /** The relay served a different identity key than the one pinned for this
         *  contact — a key-substitution attempt. Refused; NOTHING was created or
         *  written. Distinct so the UI can say so honestly (and keep the draft). */
        data object IdentityChanged : Result

        /** Any other failure (no account, network, bundle verification, deposit
         *  rejected). Retryable; the caller keeps the user's draft untouched. */
        data object Failed : Result
    }

    /**
     * Seal [text] to [conversation]'s contact and deposit it under a fresh
     * [ttlHours] lifetime. Never throws (except to propagate coroutine
     * cancellation); every failure lands on an honest [Result]. On success the
     * ONLY side effects are the relay deposit and the local sent bubble — the
     * caller clears the draft only then (web discipline).
     */
    suspend fun create(conversation: Conversation, text: String, ttlHours: Int): Result {
        val senderAccountId = api.accountId ?: return Result.Failed
        try {
            // Fetch a FRESH bundle: a lemon drop runs a brand-new one-shot X3DH
            // against whatever the recipient publishes right now, independent of
            // any live session we hold with this peer.
            val bundleDto = api.fetchPreKeyBundle(conversation.contactId)

            // TRUST BOUNDARY (creation-side mirror of the web check and of
            // MessagingCoordinator.sendText): the drop must seal to the identity
            // key we already trust for this contact — pinned out of band, else the
            // TOFU key learned on first contact — not to whatever the relay serves
            // today. A substituted bundle (malicious relay, re-registration,
            // corruption) would otherwise be silently readable by someone other
            // than the person this UI names. When we hold no key at all there is
            // nothing to compare (brand-new contact-by-UUID) and we proceed TOFU,
            // exactly as the ordinary send path does.
            val knownKey = conversation.pinnedIdentityKeyBase64 ?: conversation.contactIdentityKeyBase64
            if (!qrDropBundleTrusted(knownKey, bundleDto.identityKeyBase64)) {
                return Result.IdentityChanged
            }

            val bundle = LemonDropCreate.RecipientBundle(
                identityKey = decode(bundleDto.identityKeyBase64),
                signedPrekeyId = bundleDto.signedPreKeyId,
                signedPrekeyPublic = decode(bundleDto.signedPreKeyBase64),
                signedPrekeySignature = decode(bundleDto.signedPreKeySignatureBase64),
                oneTimePrekeyId = bundleDto.preKeyId,
                oneTimePrekeyPublic = bundleDto.preKeyBase64?.let { decode(it) },
            )

            // The seal + PoW are CPU-heavy (~1M SHA-256). runInterruptible turns
            // coroutine cancellation into a thread interrupt so the PoW loop
            // (which polls the interrupt flag) actually stops if the user backs
            // out mid-seal.
            val created = runInterruptible(Dispatchers.Default) {
                LemonDropCreate.create(
                    sodium = sodium,
                    verifyXEdDSA = verifyXEdDSA,
                    senderAccountId = senderAccountId,
                    sender = senderIdentity(),
                    recipientAccountId = conversation.contactId,
                    bundle = bundle,
                    text = text,
                )
            }
            val drop = when (created) {
                is LemonDropCreate.Result.Created -> created
                LemonDropCreate.Result.BundleUnverified -> return Result.Failed
            }

            val expiresAt = api.depositQrDrop(
                qrIdB64Url = encodeQrDropId(drop.qrId),
                ciphertextB64 = b64(drop.ciphertext),
                ttlHours = ttlHours,
                powNonceB64 = b64(drop.powNonce),
                burnHashB64 = b64(drop.burnHash),
            )

            // Local sent bubble, exactly like the ordinary send — but the state
            // stays SENT and never advances: a drop's redemption is UNKNOWABLE
            // (the blind relay can't tell us whether or when anyone scanned it),
            // so there is no honest DELIVERED to reach.
            messages.addOutgoing(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversation.id,
                    text = text,
                    isMine = true,
                    timestampMs = System.currentTimeMillis(),
                    ttlSeconds = null,
                    burnOnRead = false,
                    state = MessageState.SENT,
                ),
            )
            conversations.onOutgoingMessage(conversation.id)

            return Result.Success(url = drop.url, expiresAt = expiresAt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No local write happened past the deposit call boundary, so a
            // failure here leaves nothing half-created; the draft is kept.
            return Result.Failed
        }
    }

    // ── the private-scalar bridge (see class doc — do not widen) ─────────────

    private fun senderIdentity(): LemonDropCreate.SenderIdentity {
        val identity = signalStore.getIdentityKeyPair()
        return LemonDropCreate.SenderIdentity(
            // Raw-32 Montgomery public key — exactly what registration uploads and
            // what a recipient pins; stamped verbatim as sender_identity_key.
            publicKey = identity.publicKey.publicKey.getPublicKeyBytes(),
            // Raw X25519 private scalar. A fresh copy per call; create() zeros it.
            privateScalar = identity.privateKey.serialize(),
        )
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * The lemon-drop CREATION trust boundary, as a pure function so it is pinned by
 * a plain JVM test (the orchestrator itself needs the Keystore-backed store and
 * the network, neither available off-device).
 *
 * [knownKey] is the identity we already trust for the contact — pinned out of
 * band, else the TOFU key learned on first contact ([Conversation.pinnedIdentityKeyBase64]
 * ?: [Conversation.contactIdentityKeyBase64]). Returns true (proceed) when we
 * hold NO key to compare (brand-new contact-by-UUID → TOFU, as the ordinary send
 * path does) OR the relay's [bundleKey] equals it; false (REFUSE) on a mismatch —
 * a key-substitution attempt the drop must not seal to.
 */
internal fun qrDropBundleTrusted(knownKey: String?, bundleKey: String): Boolean =
    knownKey == null || knownKey == bundleKey
