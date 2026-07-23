// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import com.zitrone.app.crypto.ZitroneSignalStore
import com.zitrone.app.crypto.LemonDropOneShot
import com.zitrone.app.crypto.SafetyNumber
import com.zitrone.app.net.ApiClient
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestrates a lemon-drop scan end to end: one fetch, one isolated decrypt
 * attempt, the sender trust cross-check, and — only at explicit delivery —
 * the irreversible side effects (one-time-prekey consumption + relay burn).
 *
 * Two-phase by design, matching the veil's security invariant
 * (see [LemonDropVeil]):
 *
 *  - [probe] runs pre-unlock and is SIDE-EFFECT-FREE beyond the fetch: it may
 *    decrypt (key material is readable pre-gate, like the rest of the
 *    encrypted store), but it deletes nothing, burns nothing, and stores
 *    nothing — dismissing at this point leaves the drop fully re-scannable.
 *  - [deliverDurablyCommit] runs only after the biometric gate passed and the
 *    plaintext is about to render: it consumes the one-time prekey (single-use
 *    by design) and reseals that consumption durable; the caller renders and
 *    best-effort [burn]s the drop ONLY on a confirmed commit — the same
 *    side-effect pair the web client fires at redeem (store.ts redeemQrDrop),
 *    durability-gated. Burn failure is swallowed — TTL is the backstop, same
 *    as web.
 *
 * PRIVATE-SCALAR BRIDGE — the deliberate, scoped exception: [recipientKeys]
 * below pulls raw private scalars out of the [ZitroneSignalStore]
 * (identity, signed prekeys, one consumed one-time prekey), which ordinary
 * code must never do — SignalProtocolManager's contract is that private key
 * bytes never leave the store. The exception exists because a lemon drop is
 * NOT libsignal traffic (see LemonDropOneShot's isolation contract) and the
 * one-shot X3DH needs the scalars for raw X25519. The bridge is private to
 * this class, feeds only [LemonDropOneShot.open], and must not be widened or
 * made reachable from anywhere else.
 */
class LemonDropRedeemer(
    private val api: ApiClient,
    private val signalStore: ZitroneSignalStore,
    private val conversations: ConversationRepository,
    private val sodium: LemonDropOneShot.SodiumOps,
    /**
     * Durable-flush barrier sealing the prekey consumption (a coalesced vault mutation) before
     * the plaintext renders or [burn] hands the relay its shred order — see
     * [deliverDurablyCommit]. Injected like the coordinator's flush-before-ack (production:
     * `VaultRuntime::flushBeforeAck`); default no-op for callers without a vault runtime.
     */
    private val flushDurable: suspend () -> Unit = {},
) {

    /** Outcome of the pre-unlock probe phase. */
    sealed interface ProbeResult {
        data class Advocacy(val outcome: LemonDropScanOutcome) : ProbeResult
        data class ReadyToOpen(val pending: PendingLemonDrop) : ProbeResult
    }

    /**
     * Fetch the drop and try to open it as this device. Never throws; every
     * failure path lands on an honest advocacy outcome:
     *
     *  - fetch 404 → UNAVAILABLE, fetch never completed → UNKNOWN (unchanged);
     *  - blob served but this device has no identity, the seal doesn't open,
     *    the payload is broken, or the sender cross-check fails → SEALED (a
     *    live drop exists and this device cannot honestly render it).
     */
    suspend fun probe(qrId: String): ProbeResult {
        // runCatching would swallow the coroutine's own CancellationException and
        // let a cancelled probe keep running / overwrite veil state — rethrow it,
        // treating only genuine failures as a fetch outcome.
        val fetched = runCatchingCancellable { api.fetchQrDrop(qrId) }
        val ciphertextBase64 = fetched.getOrNull()
            ?: return ProbeResult.Advocacy(classifyLemonDropFetch(fetched))

        // A device with no identity yet (fresh install, never onboarded) can
        // never be a drop's recipient — accounts are per-device and creation
        // requires the recipient's published bundle.
        if (!signalStore.hasLocalIdentity()) {
            return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
        }

        val ciphertext = runCatching {
            Base64.getDecoder().decode(ciphertextBase64)
        }.getOrNull() ?: return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)

        // Reading our own key material touches the Android Keystore + encrypted
        // store, which can throw (locked keystore, corrupt entry). We cannot
        // honestly attempt an open then, so fall back to the neutral advocacy
        // screen rather than letting the probe coroutine crash and strand the
        // veil at UNKNOWN.
        val keys = try {
            recipientKeys()
        } catch (e: Exception) {
            return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
        }
        // The per-call key copies are zeroed the moment the open attempt
        // returns, whatever its outcome (single-use contract on RecipientKeys).
        // A defensive catch keeps any unexpected store/crypto throw from
        // crashing the probe coroutine — it becomes the neutral advocacy screen;
        // the finally still zeroes the key material on every path.
        val result = try {
            LemonDropOneShot.open(sodium, ciphertext, keys)
        } catch (e: Exception) {
            return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
        } finally {
            keys.zero()
        }
        if (result !is LemonDropOneShot.Result.Message) {
            // NotRecipient and Invalid stay distinct at the crypto layer but
            // collapse to the same warm screen here, exactly like the web UI.
            return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
        }

        // TRUST BOUNDARY. Decrypting the envelope already PROVES the sender held
        // the private half of the claimed identity key: the responder's DH1 =
        // DH(SPK_priv, IK_sender_pub) only matches the initiator's secret if the
        // initiator used that identity key's private scalar. So the message is
        // authentically from whoever owns `sender_identity_key`; what remains is
        // binding that key to a HUMAN name. For a known contact we check the
        // claimed key equals the one we pinned — a mismatch means someone is
        // borrowing a trusted name, so render NOTHING. For everyone else we show
        // the key's fingerprint and label it unverified; the user verifies it
        // out of band. We deliberately do NOT fetch the relay's bundle to
        // "confirm" the key: GET :id/prekey CONSUMES one of the sender's
        // one-time prekeys, and a relay match would add nothing over the crypto
        // proof we already hold (an impersonator would present the victim's real
        // public key regardless). No contact and no session is ever created — a
        // lemon drop is one-way; a conversation needs the ordinary add-contact
        // flow.
        val claimedKeyBase64 = Base64.getEncoder().encodeToString(result.senderIdentityKey)
        val known = conversations.findByContact(result.senderAccountId)
        val pinned = known?.let { it.pinnedIdentityKeyBase64 ?: it.contactIdentityKeyBase64 }
        val senderLabel: String
        val senderVerified: Boolean
        if (known != null && pinned != null) {
            if (pinned != claimedKeyBase64) {
                return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
            }
            senderLabel = known.displayName
            senderVerified = true
        } else {
            // Unknown sender, or a known contact we hold no key for: label by
            // key fingerprint, never by an unverifiable name.
            senderLabel = SafetyNumber.fingerprintOf(result.senderIdentityKey)
            senderVerified = false
        }

        return ProbeResult.ReadyToOpen(
            PendingLemonDrop(
                qrId = qrId,
                text = result.text,
                senderLabel = senderLabel,
                senderVerified = senderVerified,
                burnTokenBase64 = Base64.getEncoder().encodeToString(result.burnToken),
                usedOneTimePrekeyId = result.usedOneTimePrekeyId,
            ),
        )
    }

    /** Outcome of [deliverDurablyCommit] — the applied/durable split is load-bearing. */
    enum class DeliveryCommit {
        /** Consumption confirmed on disk — render AND [burn]. */
        DURABLE,

        /**
         * The prekey removal APPLIED to live state but the durable flush did not confirm. The
         * mutation is never rolled back (the coalesced background reseal — or even the very
         * flush that then threw — may already have persisted it), so a "rescan" could NEVER
         * decrypt this drop again: the message must RENDER now or risk being lost forever
         * (round 10, Codex). Only the relay [burn] is withheld — the handoff must not outrun
         * the consumption reaching disk; the TTL reaps the (now undecryptable-to-us) copy.
         */
        APPLIED_UNCONFIRMED,

        /** The removal never touched live state (closed-runtime teardown race) — render
         *  NOTHING; the drop and its prekey are intact, a re-scan retries cleanly. */
        NOT_APPLIED,
    }

    /**
     * Commit the delivery: consume the one-time prekey and reseal that consumption DURABLE —
     * the same flush-before-handoff rule as every other handoff after a vault mutation (D2c
     * round 8). The caller renders on any APPLIED outcome and fires [burn] only on [DeliveryCommit.DURABLE].
     * Idempotent: a re-commit for an already-consumed id no-ops the removal (removeRecord on an
     * absent key) and just re-runs the flush — which is what lets a backgrounded delivery re-run
     * after a fresh biometric pass. A drop that used no one-time prekey has nothing to persist
     * and commits [DeliveryCommit.DURABLE] trivially.
     */
    suspend fun deliverDurablyCommit(pending: PendingLemonDrop): DeliveryCommit {
        val id = pending.usedOneTimePrekeyId ?: return DeliveryCommit.DURABLE
        return classifyDeliveryCommit(
            consume = { signalStore.removePreKey(id) },
            flush = flushDurable,
        )
    }

    /** Like [runCatching] but never swallows a coroutine [CancellationException]
     *  — cancellation must propagate so a cancelled probe stops rather than
     *  being recorded as an ordinary fetch failure. */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** Burn is network I/O — separated from [deliver] so the caller can fire
     *  it on an IO dispatcher. A genuine failure is swallowed (TTL is the
     *  backstop), but a coroutine CancellationException is rethrown so
     *  cancellation propagates correctly. */
    suspend fun burn(pending: PendingLemonDrop) {
        try {
            api.burnQrDrop(pending.qrId, pending.burnTokenBase64)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort — the drop's TTL guarantees eventual destruction.
        }
    }

    // ── the private-scalar bridge (see class doc — do not widen) ─────────────

    private fun recipientKeys(): LemonDropOneShot.RecipientKeys {
        val identity = signalStore.getIdentityKeyPair()
        return LemonDropOneShot.RecipientKeys(
            identityPublicKey = identity.publicKey.publicKey.getPublicKeyBytes(),
            identityPrivateScalar = identity.privateKey.serialize(),
            signedPrekeys = signalStore.loadSignedPreKeys().map { record ->
                LemonDropOneShot.SignedPrekey(
                    id = record.id,
                    publicKey = record.keyPair.publicKey.getPublicKeyBytes(),
                    privateScalar = record.keyPair.privateKey.serialize(),
                    timestampMs = record.timestamp,
                )
            },
            oneTimePrekeyLoader = { id ->
                // Called lazily DURING open(), so a Keystore/store throw here would
                // otherwise escape open. Treat any failure — or a prekey we no
                // longer hold — as "not available": the responder DH then can't
                // reconstruct and the drop fails closed to Invalid, honestly.
                try {
                    if (signalStore.containsPreKey(id)) {
                        val keyPair = signalStore.loadPreKey(id).keyPair
                        LemonDropOneShot.OneTimePrekey(
                            publicKey = keyPair.publicKey.getPublicKeyBytes(),
                            privateScalar = keyPair.privateKey.serialize(),
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            },
        )
    }
}

/**
 * The delivery-commit decision, extracted top-level (mirroring `flushThenAck` /
 * `sealDurableOrFalse`) so the applied/durable split — the load-bearing part of
 * [LemonDropRedeemer.deliverDurablyCommit] — is host-testable without an ApiClient:
 * a [consume] throw means the mutate never applied (closed-runtime teardown →
 * [LemonDropRedeemer.DeliveryCommit.NOT_APPLIED], nothing lost, drop re-scannable); a [flush]
 * throw AFTER an applied consume is [LemonDropRedeemer.DeliveryCommit.APPLIED_UNCONFIRMED] —
 * never "unapplied", because the removal is scheduled (or already sealed) and a rescan could
 * never decrypt the drop again, so the caller MUST still render. [CancellationException]
 * propagates from either phase (cooperative teardown, never folded into an outcome).
 */
internal suspend fun classifyDeliveryCommit(
    consume: () -> Unit,
    flush: suspend () -> Unit,
): LemonDropRedeemer.DeliveryCommit {
    try {
        consume()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return LemonDropRedeemer.DeliveryCommit.NOT_APPLIED
    }
    return try {
        flush()
        LemonDropRedeemer.DeliveryCommit.DURABLE
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        LemonDropRedeemer.DeliveryCommit.APPLIED_UNCONFIRMED
    }
}
