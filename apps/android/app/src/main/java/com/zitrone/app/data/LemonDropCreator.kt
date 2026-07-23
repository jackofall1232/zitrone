// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import android.util.Base64
import android.util.Log
import com.zitrone.app.crypto.ZitroneSignalStore
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
 * the open side: [senderIdentity] pulls the raw X25519 scalar out of the
 * [ZitroneSignalStore] for the one-shot X3DH, which ordinary code must
 * never do. It is private to this class, feeds only [LemonDropCreate.create]
 * (which zeros it), and must not be widened.
 */
class LemonDropCreator(
    private val api: ApiClient,
    private val signalStore: ZitroneSignalStore,
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

        /** The draft is too long to seal into a depositable drop — the padded
         *  sealed ciphertext would exceed the relay's ceiling, so it was refused
         *  BEFORE the PoW. Distinct so the UI can say "message too long" instead
         *  of offering a pointless retry. The draft is kept. */
        data object TooLarge : Result

        /**
         * Deposit returned a ROUTER 404 (Fiber's generic `{"error":"error"}`) —
         * the live relay build predates the `/api/v1/qr-drops` routes (same class
         * of outage as pre-blob attachment 404s). Distinct so the UI can say
         * "redeploy the relay" instead of a useless "try again". The draft is kept.
         */
        data object StaleRelay : Result

        /**
         * A HANDLER 404 (`{"error":"not_found"}`) — the recipient's prekey bundle
         * is gone (account reset/deleted). Distinct from [StaleRelay]: the relay
         * is healthy, so "redeploy" would be wrong; the recipient is simply not
         * reachable. The draft is kept.
         */
        data object RecipientUnavailable : Result

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
            // than the person this UI names. A one-shot drop gets NO later
            // safety-number check, so unlike ordinary messaging we also refuse
            // when we hold no key at all (see qrDropBundleTrusted); the compose
            // UI additionally hides the drop button for a keyless conversation,
            // so this is a defense-in-depth backstop.
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
                LemonDropCreate.Result.TooLarge -> return Result.TooLarge
            }

            val expiresAt = api.depositQrDrop(
                qrIdB64Url = encodeQrDropId(drop.qrId),
                ciphertextB64 = b64(drop.ciphertext),
                ttlHours = ttlHours,
                powNonceB64 = b64(drop.powNonce),
                burnHashB64 = b64(drop.burnHash),
            )

            // The deposit is ACCEPTED — the drop is now live on the relay and the
            // sticker URL is the only copy the creator gets. The success is fixed
            // HERE: the local sent-bubble writes below must NOT be able to flip it
            // to Failed. If they could, a persistence hiccup would send the user
            // to retry, minting a SECOND live drop and consuming another of the
            // recipient's one-time prekeys while the first is stranded unseen.
            // This mirrors web sendQrDrop's guarded persist(). (Cancellation still
            // propagates — structured concurrency — but an ordinary write failure
            // is swallowed; the bubble simply catches up on the next persist.)
            val success = Result.Success(url = drop.url, expiresAt = expiresAt)
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deposit already succeeded — never fail the drop for a local
                // bookkeeping error; the URL still returns to the creator. Log the
                // exception (never the URL/plaintext/keys) so a silent local-write
                // failure is still diagnosable.
                Log.e("LemonDropCreator", "local sent-bubble write failed after a successful deposit", e)
            }
            return success
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiClient.ApiException) {
            // A 404 in this block has two distinct causes, told apart by the body:
            //   - ROUTER 404 {"error":"error"}: the deposit route is ABSENT (stale
            //     pre-qr-drops build) → StaleRelay, "redeploy the relay".
            //   - HANDLER 404 {"error":"not_found"}: from the prekey-bundle fetch
            //     that precedes the deposit — the recipient is gone, the relay is
            //     healthy → RecipientUnavailable. Telling the user to redeploy a
            //     working relay would be wrong.
            if (e.code == 404) {
                return if (isRouterAbsent404(e.responseBody)) {
                    Log.e("LemonDropCreator", "lemon-drop deposit 404 — relay missing /api/v1/qr-drops (stale build)", e)
                    Result.StaleRelay
                } else {
                    Log.e("LemonDropCreator", "lemon-drop create 404 — recipient bundle unavailable", e)
                    Result.RecipientUnavailable
                }
            }
            Log.e("LemonDropCreator", "lemon-drop create/deposit failed before the deposit boundary", e)
            return Result.Failed
        } catch (e: Exception) {
            // Failure at or before the deposit boundary — nothing was deposited,
            // so nothing is half-created and the draft is kept for retry. Log the
            // exception (never the URL/plaintext/keys) so the failure is visible.
            Log.e("LemonDropCreator", "lemon-drop create/deposit failed before the deposit boundary", e)
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
 * ?: [Conversation.contactIdentityKeyBase64]). Returns true (proceed) ONLY when
 * we hold a key AND the relay's [bundleKey] equals it; false (REFUSE) both on a
 * mismatch (a key-substitution attempt) AND when we hold no key at all.
 *
 * This is stricter than ordinary messaging, which TOFUs on first contact: a
 * lemon drop is a ONE-SHOT sealed payload with no later safety-number
 * verification to catch a substituted key, so it must only seal to an identity
 * already established out of band or through prior contact — never to whatever
 * the relay serves for a peer we have never keyed. Web `sendQrDrop` gets this
 * for free by requiring a real contact (which always carries an identity key);
 * we enforce presence explicitly.
 */
internal fun qrDropBundleTrusted(knownKey: String?, bundleKey: String): Boolean =
    knownKey != null && knownKey == bundleKey

/**
 * True when a 404 response body is Fiber's generic ROUTER 404 — `{"error":"error"}`
 * — meaning the route was never registered (a stale relay build). A HANDLER 404
 * carries a specific code like `{"error":"not_found"}` and must NOT match, so a
 * missing recipient is not misread as a stale relay. Pure + pinned by a JVM test;
 * mirrors the body check in scripts/verify-relay-build.sh and the web
 * `ApiError.code === "error"` discrimination.
 */
internal fun isRouterAbsent404(body: String?): Boolean =
    body != null && Regex("\"error\"\\s*:\\s*\"error\"").containsMatchIn(body)
