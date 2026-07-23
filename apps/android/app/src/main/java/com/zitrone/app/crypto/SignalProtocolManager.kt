// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom

/**
 * Wraps libsignal-client: identity generation, prekey generation/rotation,
 * X3DH session establishment from a fetched prekey bundle, and per-message
 * Double Ratchet encrypt/decrypt.
 *
 * All key material is held by the [ZitroneSignalStore] — encrypted at rest
 * (the legacy [EncryptedSignalProtocolStore] behind the Android Keystore, or,
 * from PR-D2c, the vault facade). Nothing here logs, and nothing here ever
 * returns private key bytes to callers. (One deliberate, documented exception
 * exists OUTSIDE this class: LemonDropRedeemer's private key-bridge reads raw
 * scalars from the store for the isolated one-shot lemon-drop responder —
 * see its class doc. It must stay the only one.)
 *
 * PR-D2a made this store-agnostic: it takes the [ZitroneSignalStore] interface
 * (not the concrete legacy store) and reads/writes the prekey / signed-prekey id
 * counters and the signed-prekey timestamp THROUGH the store's counter accessors
 * instead of reaching into `prefs(PREFS_SIGNAL_STORE)` itself. The manager keeps
 * only the wrap-and-increment id logic; the byte-for-byte counter values and id
 * sequences are unchanged.
 */
class SignalProtocolManager(
    private val store: ZitroneSignalStore,
) {

    // -- DTOs (what crosses the network — PUBLIC keys only) ------------------

    data class SignedPreKeyDto(
        val id: Int,
        val publicKeyBase64: String,
        val signatureBase64: String,
        val timestampMs: Long,
    )

    data class OneTimePreKeyDto(
        val id: Int,
        val publicKeyBase64: String,
    )

    data class PreKeyBundleDto(
        val registrationId: Int,
        val deviceId: Int,
        val identityKeyBase64: String,
        val signedPreKeyId: Int,
        val signedPreKeyBase64: String,
        val signedPreKeySignatureBase64: String,
        /** One-time prekey — may be null when the contact's stock ran out. */
        val preKeyId: Int?,
        val preKeyBase64: String?,
    )

    data class EncryptResult(
        val ciphertextBase64: String,
        /** Base64 X3DH base key — present only on prekey (first) messages. */
        val ephemeralKeyBase64: String?,
        /** One-time prekey consumed — present only on prekey messages. */
        val preKeyId: Int?,
        /** Double Ratchet counter for the envelope. */
        val messageNumber: Int,
    )

    // -- identity -------------------------------------------------------------

    /** Generates the long-term identity on first launch. Idempotent. */
    fun ensureIdentity() {
        if (store.hasLocalIdentity()) return
        val identityKeyPair = IdentityKeyPair.generate()
        // 14-bit registration id per the Signal spec (1..16380).
        val registrationId = SecureRandom().nextInt(16380) + 1
        store.setLocalIdentity(identityKeyPair, registrationId)
    }

    fun localRegistrationId(): Int = store.getLocalRegistrationId()

    // Registration wire format is the raw 32-byte Curve25519 key (no libsignal
    // DJB type-prefix byte) — see server internal/api/handlers.go registerRequest,
    // which validates len(identity_key) == ed25519.PublicKeySize (32). serialize()
    // would produce a 33-byte, 0x05-prefixed value and get rejected as bad_identity_key.
    fun localIdentityPublicKeyBase64(): String {
        val identityKey = store.getIdentityKeyPair().publicKey
        return encode(identityKey.publicKey.getPublicKeyBytes())
    }

    // Raw 32-byte form — matches localIdentityPublicKeyBase64() above, and the
    // wire representation contacts receive via ContactExchangePayload / the
    // server, so safetyNumberWith()/localFingerprint() compare the same byte
    // representation on both sides (review: Gemini/Copilot/Codex on PR #21).
    fun localIdentityPublicKeyBytes(): ByteArray =
        store.getIdentityKeyPair().publicKey.publicKey.getPublicKeyBytes()

    /**
     * Signs the timestamped login challenge with the identity key (XEdDSA
     * over the Curve25519 identity key). Challenge format is defined by the
     * server contract: "sublemonable-login:<account_id>:<unix_ts>".
     */
    fun signLoginChallenge(challenge: String): String {
        val signature = store.getIdentityKeyPair().privateKey
            .calculateSignature(challenge.toByteArray(Charsets.UTF_8))
        return encode(signature)
    }

    // -- prekeys ----------------------------------------------------------------

    /**
     * Generates (or rotates) the signed prekey. Rotation cadence is 7 days
     * (security.encryption.key_types.signed_prekey).
     */
    fun generateSignedPreKey(): SignedPreKeyDto {
        val id = allocateSignedPreKeyId()
        val keyPair = Curve.generateKeyPair()
        // Sign the standard libsignal serialize() form (33 bytes, DJB
        // type-prefixed), NOT the raw 32-byte wire form uploaded below.
        // SessionBuilder.process() on a receiving peer reconstructs an
        // ECPublicKey from the bundle and verifies the signature against
        // ITS serialize() output, so the signed message must stay in that
        // representation for peer-to-peer X3DH to work (review: Codex on
        // PR #21). The wire `public_key` can still be the raw 32-byte form
        // the server requires — establishSession() below re-derives the
        // same 33-byte point from it before verification, and a future
        // server-side signature check must do the same reconstruction
        // rather than verifying against the raw bytes directly.
        val signature = Curve.calculateSignature(
            store.getIdentityKeyPair().privateKey,
            keyPair.publicKey.serialize(),
        )
        val timestamp = System.currentTimeMillis()
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, timestamp, keyPair, signature))
        store.setSignedPreKeyCreatedAt(timestamp)
        // Upload-pending until [confirmSignedPreKeyUploaded]: generation bumps createdAt (the age
        // gate), so without this marker a skipped/failed upload would never retry — the relay
        // would keep serving the OLD signed prekey a full extra rotation period.
        store.setPendingSignedPreKeyUploadId(id)
        return SignedPreKeyDto(
            id = id,
            publicKeyBase64 = encode(keyPair.publicKey.getPublicKeyBytes()),
            signatureBase64 = encode(signature),
            timestampMs = timestamp,
        )
    }

    /**
     * The stored signed prekey whose upload was never confirmed, rebuilt from the store — or null
     * when none is pending. Re-serving the SAME record makes the retry idempotent (re-uploading
     * an identical signed prekey is safe; generating a fresh one each attempt would orphan
     * private halves). A pending id whose record has vanished (wiped store) clears itself.
     */
    fun pendingSignedPreKeyUpload(): SignedPreKeyDto? {
        val id = store.pendingSignedPreKeyUploadId()
        if (id == 0) return null
        val record = runCatching { store.loadSignedPreKey(id) }.getOrNull()
        if (record == null) {
            store.setPendingSignedPreKeyUploadId(0)
            return null
        }
        return SignedPreKeyDto(
            id = record.id,
            publicKeyBase64 = encode(record.keyPair.publicKey.getPublicKeyBytes()),
            signatureBase64 = encode(record.signature),
            timestampMs = record.timestamp,
        )
    }

    /** The relay confirmed it holds the signed prekey's public half — retire the pending marker. */
    fun confirmSignedPreKeyUploaded() {
        store.setPendingSignedPreKeyUploadId(0)
    }

    /**
     * Returns the signed prekey the relay still needs: a stored-but-unconfirmed one first
     * (upload retry — see [pendingSignedPreKeyUpload]), else a fresh rotation when the current
     * one is older than 7 days, else null.
     */
    fun rotateSignedPreKeyIfNeeded(): SignedPreKeyDto? {
        pendingSignedPreKeyUpload()?.let { return it }
        val createdAt = store.signedPreKeyCreatedAt()
        val age = System.currentTimeMillis() - createdAt
        return if (createdAt == 0L || age >= SIGNED_PREKEY_MAX_AGE_MS) generateSignedPreKey() else null
    }

    /**
     * The one-time prekeys the relay still needs. A stored batch whose upload was NEVER
     * ATTEMPTED is RE-SERVED (retry after a flush-gated skip — the relay never saw the ids, so
     * the same ids are safe); otherwise a fresh batch is generated (default 100 — the upload
     * batch size from security.encryption.key_types.one_time_prekeys) and marked pending +
     * unattempted. Private halves stay in the store; only public halves are returned.
     *
     * The ATTEMPTED split is load-bearing (round 8, Codex): once an upload REQUEST left the
     * device, a lost response / crash cannot distinguish "relay never got it" from "relay
     * committed it and a peer already consumed an id" — and the relay's insert re-creates a
     * consumed id (`ON CONFLICT DO NOTHING` + consume-by-DELETE), which would serve the same
     * one-time prekey to a second initiator. So an attempted-but-unconfirmed batch is never
     * re-served: its PRIVATE halves stay in the store (a peer may hold a bundle against them)
     * and a fresh batch is generated — the bounded-orphan cost is confined to the rare
     * lost-response window instead of every flush failure. The pending marker is retired by
     * [confirmOneTimePreKeysUploaded]; ids whose records vanished (wiped store) are dropped.
     */
    fun generateOneTimePreKeys(
        count: Int = ONE_TIME_PREKEY_BATCH,
        discardAttempted: Boolean = false,
    ): List<OneTimePreKeyDto> {
        if (!store.oneTimePreKeyUploadAttempted()) {
            val pending = store.pendingOneTimePreKeyUploadIds()
                .mapNotNull { id ->
                    runCatching { store.loadPreKey(id) }.getOrNull()?.let { record ->
                        OneTimePreKeyDto(
                            id = id,
                            publicKeyBase64 = encode(record.keyPair.publicKey.getPublicKeyBytes()),
                        )
                    }
                }
            if (pending.isNotEmpty()) return pending
        } else if (discardAttempted) {
            // REGISTER-retry only (round 11, Codex): with NO live account (accountId == null), a
            // maybe-registered batch's private halves are dead weight — a bundle under the orphan
            // account id can only produce messages addressed TO that orphan, which this client
            // (about to register a fresh id) will never receive. Discarding them keeps the
            // offline-retry loop's vault footprint net-zero (each retry destroys the superseded
            // batch before generating). NEVER valid for the top-up path, where the account is
            // live and peers may hold bundles against the attempted batch.
            store.pendingOneTimePreKeyUploadIds().forEach { id ->
                runCatching { store.removePreKey(id) }
            }
        }
        val fresh = (0 until count).map {
            val id = allocatePreKeyId()
            val keyPair = Curve.generateKeyPair()
            store.storePreKey(id, PreKeyRecord(id, keyPair))
            OneTimePreKeyDto(id = id, publicKeyBase64 = encode(keyPair.publicKey.getPublicKeyBytes()))
        }
        store.setPendingOneTimePreKeyUploadIds(fresh.map { it.id })
        store.setOneTimePreKeyUploadAttempted(false)
        return fresh
    }

    /**
     * The pending batch's upload request is about to leave the device. Callers mark this AFTER
     * the privates' durable flush and reseal it durable BEFORE the actual upload (see
     * [com.zitrone.app.MessagingCoordinator.onPreKeyLow]) — ordering that keeps both retry
     * properties: a flush-gated skip stays re-servable (flag never set), while a lost response
     * can never re-serve possibly-consumed ids (flag durable before the request existed).
     */
    fun markOneTimePreKeyUploadAttempted() {
        store.setOneTimePreKeyUploadAttempted(true)
    }

    /** The relay confirmed it holds the batch's public halves — retire the pending marker. */
    fun confirmOneTimePreKeysUploaded() {
        store.setPendingOneTimePreKeyUploadIds(emptyList())
        store.setOneTimePreKeyUploadAttempted(false)
    }

    /** Register confirmed: it uploaded BOTH the signed prekey and the one-time batch. */
    fun confirmPreKeysUploaded() {
        confirmSignedPreKeyUploaded()
        confirmOneTimePreKeysUploaded()
    }

    fun localOneTimePreKeyCount(): Int = store.countOneTimePreKeys()

    // -- sessions (X3DH + Double Ratchet) ----------------------------------------

    fun hasSession(remoteAccountId: String): Boolean =
        store.containsSession(address(remoteAccountId))

    /**
     * X3DH: establishes an outbound session from a prekey bundle fetched via
     * GET /api/v1/users/:id/prekey. After this, [encrypt] produces a
     * PreKeySignalMessage until the first round trip completes the ratchet.
     *
     * The bundle's keys are the server's raw 32-byte wire form (no DJB
     * type-prefix byte — see localIdentityPublicKeyBase64()), so they're
     * decoded via [ECPublicKey.fromPublicKeyBytes], NOT [Curve.decodePoint]/
     * [IdentityKey]'s byte-array constructor, both of which expect libsignal's
     * type-prefixed serialize() form. Getting this wrong would silently break
     * every first message to a newly registered peer (review: Codex on PR #21).
     */
    fun establishSession(remoteAccountId: String, bundle: PreKeyBundleDto) {
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            bundle.preKeyId ?: -1,
            bundle.preKeyBase64?.let { ECPublicKey.fromPublicKeyBytes(decode(it)) },
            bundle.signedPreKeyId,
            ECPublicKey.fromPublicKeyBytes(decode(bundle.signedPreKeyBase64)),
            decode(bundle.signedPreKeySignatureBase64),
            IdentityKey(ECPublicKey.fromPublicKeyBytes(decode(bundle.identityKeyBase64))),
        )
        SessionBuilder(store, address(remoteAccountId)).process(preKeyBundle)
    }

    /** Encrypts plaintext for [remoteAccountId] via the session cipher. */
    fun encrypt(remoteAccountId: String, plaintext: ByteArray): EncryptResult {
        val cipher = SessionCipher(store, address(remoteAccountId))
        val message = cipher.encrypt(plaintext)
        return when (message.type) {
            CiphertextMessage.PREKEY_TYPE -> {
                val preKeyMessage = PreKeySignalMessage(message.serialize())
                EncryptResult(
                    ciphertextBase64 = encode(message.serialize()),
                    ephemeralKeyBase64 = encode(preKeyMessage.baseKey.serialize()),
                    preKeyId = if (preKeyMessage.preKeyId.isPresent) {
                        preKeyMessage.preKeyId.get()
                    } else {
                        null
                    },
                    messageNumber = preKeyMessage.whisperMessage.counter,
                )
            }
            else -> {
                val signalMessage = SignalMessage(message.serialize())
                EncryptResult(
                    ciphertextBase64 = encode(message.serialize()),
                    ephemeralKeyBase64 = null,
                    preKeyId = null,
                    messageNumber = signalMessage.counter,
                )
            }
        }
    }

    /**
     * Decrypts an inbound envelope. Prekey (first) messages — identified by a
     * non-null ephemeral_key on the envelope — implicitly perform the X3DH
     * response and consume the one-time prekey, which is then deleted
     * (one-time prekeys are single-use by design).
     */
    fun decrypt(
        remoteAccountId: String,
        ciphertextBase64: String,
        isPreKeyMessage: Boolean,
    ): ByteArray {
        val cipher = SessionCipher(store, address(remoteAccountId))
        val bytes = decode(ciphertextBase64)
        return if (isPreKeyMessage) {
            cipher.decrypt(PreKeySignalMessage(bytes))
        } else {
            cipher.decrypt(SignalMessage(bytes))
        }
    }

    /**
     * Full contact crypto teardown (not soft-delete): destroys the Double
     * Ratchet session, the peer's identity key record, and any sender keys.
     * After this, [hasSession] is false and a re-add must fetch a fresh prekey
     * bundle and re-run X3DH — zero reuse of prior session/key material.
     *
     * @return `false` if the teardown could not be flushed to disk; the caller
     *         must then treat the deletion as failed (see
     *         [EncryptedSignalProtocolStore.destroyContactCrypto]).
     */
    fun destroyContact(remoteAccountId: String): Boolean =
        store.destroyContactCrypto(remoteAccountId)

    @Deprecated(
        message = "Prefer destroyContact — a session-only wipe leaves the pinned " +
            "remote identity and sender keys behind, which is insufficient for " +
            "full contact deletion.",
        replaceWith = ReplaceWith("destroyContact(remoteAccountId)"),
    )
    fun endSession(remoteAccountId: String) {
        destroyContact(remoteAccountId)
    }

    // -- verification --------------------------------------------------------------

    /** Shared safety number with a contact — render in JetBrains Mono. */
    fun safetyNumberWith(contactIdentityKeyBase64: String): String =
        SafetyNumber.compute(localIdentityPublicKeyBytes(), decode(contactIdentityKeyBase64))

    /** Fingerprint of our own identity key (settings screen). */
    fun localFingerprint(): String =
        SafetyNumber.fingerprintOf(localIdentityPublicKeyBytes())

    // -- internals -------------------------------------------------------------------

    private fun address(accountId: String) =
        SignalProtocolAddress(accountId, EncryptedSignalProtocolStore.DEFAULT_DEVICE_ID)

    // The wrap-and-increment id logic stays HERE (byte-for-byte as the old
    // `nextId`); only the raw counter read/write moved into the store. Kept
    // @Synchronized so the read-modify-write of a counter is atomic on this
    // manager instance, exactly as before — two allocations can never interleave.

    @Synchronized
    private fun allocatePreKeyId(): Int {
        val next = store.nextPreKeyId()
        // Wrap long before Integer.MAX_VALUE; ids only need short-term uniqueness.
        val following = if (next >= 0xFFFFFF) 1 else next + 1
        store.setNextPreKeyId(following)
        return next
    }

    @Synchronized
    private fun allocateSignedPreKeyId(): Int {
        val next = store.nextSignedPreKeyId()
        // Wrap long before Integer.MAX_VALUE; ids only need short-term uniqueness.
        val following = if (next >= 0xFFFFFF) 1 else next + 1
        store.setNextSignedPreKeyId(following)
        return next
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    companion object {
        const val ONE_TIME_PREKEY_BATCH = 100

        /** Prekey stock threshold below which the client uploads a new batch. */
        const val PREKEY_LOW_WATER_MARK = 20

        /** Signed prekey rotates every 7 days. */
        const val SIGNED_PREKEY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
