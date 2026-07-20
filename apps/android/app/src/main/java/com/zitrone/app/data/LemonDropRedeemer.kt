// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import com.zitrone.app.crypto.EncryptedSignalProtocolStore
import com.zitrone.app.crypto.LemonDropOneShot
import com.zitrone.app.crypto.SafetyNumber
import com.zitrone.app.net.ApiClient
import java.util.Base64

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
 *  - [deliver] runs only after the biometric gate passed and the plaintext is
 *    about to render: it consumes the one-time prekey (single-use by design)
 *    and best-effort burns the drop, the exact side-effect pair the web
 *    client fires at redeem (store.ts redeemQrDrop). Burn failure is
 *    swallowed — TTL is the backstop, same as web.
 *
 * PRIVATE-SCALAR BRIDGE — the deliberate, scoped exception: [recipientKeys]
 * below pulls raw private scalars out of [EncryptedSignalProtocolStore]
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
    private val signalStore: EncryptedSignalProtocolStore,
    private val conversations: ConversationRepository,
    private val sodium: LemonDropOneShot.SodiumOps,
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
        val fetched = runCatching { api.fetchQrDrop(qrId) }
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

        val result = LemonDropOneShot.open(sodium, ciphertext, recipientKeys())
        if (result !is LemonDropOneShot.Result.Message) {
            // NotRecipient and Invalid stay distinct at the crypto layer but
            // collapse to the same warm screen here, exactly like the web UI.
            return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
        }

        // TRUST BOUNDARY (mirror of web redeemQrDrop): the seal proves the box
        // was addressed to us, NOT who wrote it. The claimed sender key must
        // match what we already pin for that account — or, for an unknown
        // sender, the relay's current bundle. Failing the cross-check renders
        // NOTHING (advocacy), because rendering would let an impersonator put
        // words in a trusted name's mouth.
        val claimedKeyBase64 = Base64.getEncoder().encodeToString(result.senderIdentityKey)
        val known = conversations.findByContact(result.senderAccountId)
        val senderLabel: String
        val senderVerified: Boolean
        if (known != null) {
            val pinned = known.pinnedIdentityKeyBase64 ?: known.contactIdentityKeyBase64
            if (pinned != null && pinned != claimedKeyBase64) {
                return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
            }
            if (pinned == null) {
                // A known name with no key on record cannot vouch for the
                // claim — fall back to the relay cross-check below.
                if (!relayConfirms(result.senderAccountId, claimedKeyBase64)) {
                    return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
                }
            }
            senderLabel = known.displayName
            senderVerified = pinned != null
        } else {
            if (!relayConfirms(result.senderAccountId, claimedKeyBase64)) {
                return ProbeResult.Advocacy(LemonDropScanOutcome.SEALED)
            }
            // Unknown sender: label by key fingerprint, never by unverifiable
            // name. Deliberately NO contact and NO session is created — a
            // lemon drop is one-way; a conversation needs the ordinary
            // add-contact flow.
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

    /**
     * Delivery side effects, fired when (and only when) the plaintext renders:
     * delete the consumed one-time prekey (single-use — same consumption the
     * web client does at redeem), then best-effort burn so the relay shreds
     * its only copy now instead of at TTL.
     */
    fun deliver(pending: PendingLemonDrop) {
        pending.usedOneTimePrekeyId?.let { id ->
            runCatching { signalStore.removePreKey(id) }
        }
    }

    /** Burn is network I/O — separated from [deliver] so the caller can fire
     *  it on an IO dispatcher. Swallowed on failure: TTL is the backstop. */
    suspend fun burn(pending: PendingLemonDrop) {
        runCatching { api.burnQrDrop(pending.qrId, pending.burnTokenBase64) }
    }

    /** True when the relay's current bundle for [accountId] carries exactly
     *  the claimed identity key. Any failure — 404 (deleted account), key
     *  mismatch, transport — is an unverifiable claim: false, fail closed. */
    private suspend fun relayConfirms(accountId: String, claimedKeyBase64: String): Boolean =
        runCatching { api.fetchPreKeyBundle(accountId).identityKeyBase64 }
            .getOrNull() == claimedKeyBase64

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
            oneTimePrekey = { id ->
                if (signalStore.containsPreKey(id)) {
                    val keyPair = signalStore.loadPreKey(id).keyPair
                    LemonDropOneShot.OneTimePrekey(
                        publicKey = keyPair.publicKey.getPublicKeyBytes(),
                        privateScalar = keyPair.privateKey.serialize(),
                    )
                } else {
                    null
                }
            },
        )
    }
}
