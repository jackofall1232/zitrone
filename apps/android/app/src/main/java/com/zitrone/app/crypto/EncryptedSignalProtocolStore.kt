// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.crypto

import android.content.SharedPreferences
import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * [SignalProtocolStore] persisted exclusively through EncryptedSharedPreferences
 * (Android-Keystore-wrapped AES-256). Every record — identity key pair,
 * prekeys, signed prekeys, ratchet session state — is encrypted at rest;
 * nothing key-shaped ever touches disk in plaintext.
 *
 * The [prefs] constructor is the seam under test (mirroring [com.zitrone.app.data.EncryptedAuthStore]);
 * the [KeyStoreManager] convenience constructor is what production wires, opening
 * the SAME encrypted PREFS_SIGNAL_STORE file as always.
 */
class EncryptedSignalProtocolStore internal constructor(
    private val prefs: SharedPreferences,
) : ZitroneSignalStore {

    constructor(keyStoreManager: KeyStoreManager) :
        this(keyStoreManager.prefs(KeyStoreManager.PREFS_SIGNAL_STORE))

    // -- local identity -----------------------------------------------------

    override fun hasLocalIdentity(): Boolean = prefs.contains(KEY_IDENTITY)

    override fun setLocalIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int) {
        // One transaction so the identity pair and registration id can never be
        // torn apart by a crash between writes. The string form MUST stay
        // byte-identical to what putBytes writes ([getIdentityKeyPair] reads it
        // back through getBytes).
        prefs.edit()
            .putString(KEY_IDENTITY, Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP))
            .putInt(KEY_REGISTRATION_ID, registrationId)
            .apply()
    }

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val bytes = getBytes(KEY_IDENTITY)
            ?: error("Identity key pair not yet generated")
        return IdentityKeyPair(bytes)
    }

    override fun getLocalRegistrationId(): Int =
        prefs.getInt(KEY_REGISTRATION_ID, 0)

    // -- remote identities --------------------------------------------------

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val key = remoteIdentityKey(address)
        val existing = getBytes(key)
        putBytes(key, identityKey.serialize())
        // true == replaced a DIFFERENT pre-existing identity (key change!)
        return existing != null && !existing.contentEquals(identityKey.serialize())
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = getBytes(remoteIdentityKey(address))
            ?: return true // trust-on-first-use; verification happens via safety numbers
        return existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        getBytes(remoteIdentityKey(address))?.let { IdentityKey(it) }

    // -- one-time prekeys ---------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val bytes = getBytes("$KEY_PREKEY$preKeyId")
            ?: throw InvalidKeyIdException("No prekey with id $preKeyId")
        return PreKeyRecord(bytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        putBytes("$KEY_PREKEY$preKeyId", record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        prefs.contains("$KEY_PREKEY$preKeyId")

    /** One-time prekeys are single-use by design — consumed, then deleted. */
    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove("$KEY_PREKEY$preKeyId").apply()
    }

    // -- signed prekeys -----------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val bytes = getBytes("$KEY_SIGNED_PREKEY$signedPreKeyId")
            ?: throw InvalidKeyIdException("No signed prekey with id $signedPreKeyId")
        return SignedPreKeyRecord(bytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        prefs.all.keys
            .filter { it.startsWith(KEY_SIGNED_PREKEY) }
            .mapNotNull { getBytes(it) }
            .map { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        putBytes("$KEY_SIGNED_PREKEY$signedPreKeyId", record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        prefs.contains("$KEY_SIGNED_PREKEY$signedPreKeyId")

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove("$KEY_SIGNED_PREKEY$signedPreKeyId").apply()
    }

    // -- sessions -------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        getBytes(sessionKey(address))?.let { SessionRecord(it) }

    override fun loadExistingSessions(
        addresses: List<SignalProtocolAddress>,
    ): List<SessionRecord> = addresses.map { address ->
        loadSession(address) ?: throw NoSessionException("No session for $address")
    }

    override fun getSubDeviceSessions(name: String): List<Int> =
        prefs.all.keys
            .filter { it.startsWith("$KEY_SESSION$name:") }
            .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
            .filter { it != DEFAULT_DEVICE_ID }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        putBytes(sessionKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        prefs.contains(sessionKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove(sessionKey(address)).apply()
    }

    override fun deleteAllSessions(name: String) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("$KEY_SESSION$name:") }
            .forEach(editor::remove)
        editor.apply()
    }

    /**
     * Full cryptographic teardown for one peer: Double Ratchet session state
     * (root/chain/skipped message keys live inside the SessionRecord), the
     * remote identity record (all device ids — so a re-add cannot inherit a
     * prior TOFU pin and must re-run X3DH against a freshly fetched bundle),
     * and any group sender keys. Does NOT touch local identity or our own
     * prekeys. Irreversible — a re-add must re-run X3DH.
     *
     * Runs as a SINGLE synchronous [android.content.SharedPreferences.Editor.commit]
     * transaction: all three key families are removed in one editor and flushed
     * to disk before this returns, so a crash or power loss immediately after
     * teardown cannot resurrect a deleted session or identity. One `prefs.all`
     * scan, one write (vs. three separate async `apply()`s).
     *
     * @return the [android.content.SharedPreferences.Editor.commit] result —
     *         `false` means the wipe did NOT reach disk (I/O error / storage
     *         exhaustion). The caller must NOT report the contact deleted on
     *         `false`, or orphaned session/identity state can reappear on
     *         restart and be reused instead of forcing a fresh X3DH.
     */
    override fun destroyContactCrypto(name: String): Boolean {
        val editor = prefs.edit()
        val prefixes = listOf(KEY_SESSION, KEY_REMOTE_IDENTITY, KEY_SENDER_KEY)
        prefs.all.keys
            .filter { key -> prefixes.any { key.startsWith("$it$name:") } }
            .forEach(editor::remove)
        return editor.commit()
    }

    // -- Kyber prekeys (post-quantum, required by the store interface) --------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val bytes = getBytes("$KEY_KYBER_PREKEY$kyberPreKeyId")
            ?: throw InvalidKeyIdException("No kyber prekey with id $kyberPreKeyId")
        return KyberPreKeyRecord(bytes)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        prefs.all.keys
            .filter { it.startsWith(KEY_KYBER_PREKEY) }
            .mapNotNull { getBytes(it) }
            .map { KyberPreKeyRecord(it) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        putBytes("$KEY_KYBER_PREKEY$kyberPreKeyId", record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        prefs.contains("$KEY_KYBER_PREKEY$kyberPreKeyId")

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        prefs.edit().putBoolean("$KEY_KYBER_USED$kyberPreKeyId", true).apply()
    }

    // -- sender keys (groups — phase 2; interface requires the methods) -------

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        putBytes(senderKeyKey(sender, distributionId), record.serialize())
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? =
        getBytes(senderKeyKey(sender, distributionId))
            ?.let { SenderKeyRecord(it) }

    // -- counters (PR-D2a: moved out of SignalProtocolManager, SAME prefs keys/defaults) --

    /**
     * The next one-time-prekey id, default 1 — reproduces the legacy
     * `SignalProtocolManager.nextId`'s `prefs.getInt("next_prekey_id", 1)` exactly.
     * SignalProtocolManager keeps the wrap-and-increment logic and stores the
     * result via [setNextPreKeyId], so the id sequence is byte-for-byte unchanged.
     */
    override fun nextPreKeyId(): Int = prefs.getInt(KEY_NEXT_PREKEY_ID, 1)

    override fun setNextPreKeyId(value: Int) {
        prefs.edit().putInt(KEY_NEXT_PREKEY_ID, value).apply()
    }

    /** The next signed-prekey id, default 1 (see [nextPreKeyId]). */
    override fun nextSignedPreKeyId(): Int = prefs.getInt(KEY_NEXT_SIGNED_PREKEY_ID, 1)

    override fun setNextSignedPreKeyId(value: Int) {
        prefs.edit().putInt(KEY_NEXT_SIGNED_PREKEY_ID, value).apply()
    }

    /** The current signed prekey's creation timestamp (ms), default 0 (never rotated). */
    override fun signedPreKeyCreatedAt(): Long = prefs.getLong(KEY_SIGNED_PREKEY_CREATED_AT, 0L)

    override fun setSignedPreKeyCreatedAt(value: Long) {
        prefs.edit().putLong(KEY_SIGNED_PREKEY_CREATED_AT, value).apply()
    }

    /** The signed-prekey id pending upload confirmation, default 0 (none) — see the interface. */
    override fun pendingSignedPreKeyUploadId(): Int =
        prefs.getInt(KEY_PENDING_SIGNED_PREKEY_UPLOAD, 0)

    override fun setPendingSignedPreKeyUploadId(value: Int) {
        if (value == 0) {
            prefs.edit().remove(KEY_PENDING_SIGNED_PREKEY_UPLOAD).apply()
        } else {
            prefs.edit().putInt(KEY_PENDING_SIGNED_PREKEY_UPLOAD, value).apply()
        }
    }

    /** One-time-prekey ids pending upload confirmation (comma-joined), default empty. */
    override fun pendingOneTimePreKeyUploadIds(): List<Int> =
        prefs.getString(KEY_PENDING_PREKEY_UPLOADS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()

    override fun setPendingOneTimePreKeyUploadIds(value: List<Int>) {
        if (value.isEmpty()) {
            prefs.edit().remove(KEY_PENDING_PREKEY_UPLOADS).apply()
        } else {
            prefs.edit().putString(KEY_PENDING_PREKEY_UPLOADS, value.joinToString(",")).apply()
        }
    }

    /** Attempted-upload flag for the pending batch, default false — see the interface. */
    override fun oneTimePreKeyUploadAttempted(): Boolean =
        prefs.getBoolean(KEY_PENDING_PREKEY_UPLOADS_ATTEMPTED, false)

    override fun setOneTimePreKeyUploadAttempted(value: Boolean) {
        if (value) {
            prefs.edit().putBoolean(KEY_PENDING_PREKEY_UPLOADS_ATTEMPTED, true).apply()
        } else {
            prefs.edit().remove(KEY_PENDING_PREKEY_UPLOADS_ATTEMPTED).apply()
        }
    }

    // -- misc -----------------------------------------------------------------

    override fun countOneTimePreKeys(): Int =
        prefs.all.keys.count { it.startsWith(KEY_PREKEY) }

    /**
     * Distinct remote-contact account-ids that still hold an identity record,
     * each paired with that stored remote identity key (base64), or null if it
     * can't be read.
     *
     * WHY this exists: the contact roster used to live ONLY in an in-memory
     * StateFlow, so any full process restart (an app update forces one) wiped
     * it. The Signal store, however, IS persisted — so `remote_identity:` (and
     * `session:`) records for previously-messaged contacts survive as orphans.
     * They are the only on-disk trace of who the user had been talking to, so
     * [com.zitrone.app.data.ConversationRepository]'s one-time repair path
     * rebuilds a bare roster from them (display names are unrecoverable). Reuses
     * the same prefs.all.keys prefix-scan style as the prekey/session accessors.
     */
    override fun knownRemoteContacts(): List<Pair<String, String?>> =
        prefs.all.keys
            .filter { it.startsWith(KEY_REMOTE_IDENTITY) }
            .mapNotNull { key ->
                key.removePrefix(KEY_REMOTE_IDENTITY)
                    .substringBeforeLast(':') // strip the :deviceId suffix
                    .takeIf { it.isNotEmpty() }
            }
            .distinct()
            .map { accountId ->
                // The stored value already IS the NO_WRAP base64 putBytes wrote —
                // read it directly instead of decode+re-encode round-tripping.
                val b64 = prefs.getString("$KEY_REMOTE_IDENTITY$accountId:$DEFAULT_DEVICE_ID", null)
                accountId to b64
            }

    /** Full local wipe — account deletion. Irreversible by design. */
    override fun wipe() {
        prefs.edit().clear().apply()
    }

    // Byte <-> string plumbing for the record values (moved verbatim from
    // KeyStoreManager.putBytes/getBytes, whose only caller was this store).
    // The prefs themselves are EncryptedSharedPreferences in production; the
    // base64 here is just value transport, not protection.

    private fun putBytes(key: String, value: ByteArray) {
        prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    private fun getBytes(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun remoteIdentityKey(address: SignalProtocolAddress) =
        "$KEY_REMOTE_IDENTITY${address.name}:${address.deviceId}"

    private fun sessionKey(address: SignalProtocolAddress) =
        "$KEY_SESSION${address.name}:${address.deviceId}"

    private fun senderKeyKey(sender: SignalProtocolAddress, distributionId: UUID) =
        "$KEY_SENDER_KEY${sender.name}:${sender.deviceId}:$distributionId"

    companion object {
        const val DEFAULT_DEVICE_ID = 1

        private const val KEY_IDENTITY = "identity_keypair"
        private const val KEY_REGISTRATION_ID = "registration_id"
        private const val KEY_REMOTE_IDENTITY = "remote_identity:"
        private const val KEY_PREKEY = "prekey:"
        private const val KEY_SIGNED_PREKEY = "signed_prekey:"
        private const val KEY_SESSION = "session:"
        private const val KEY_KYBER_PREKEY = "kyber_prekey:"
        private const val KEY_KYBER_USED = "kyber_prekey_used:"
        private const val KEY_SENDER_KEY = "sender_key:"

        // Prekey / signed-prekey id counters + the signed-prekey timestamp.
        // Historically written by SignalProtocolManager directly into this same
        // PREFS_SIGNAL_STORE file; PR-D2a moved the plumbing here under the
        // IDENTICAL key strings so the on-disk values are byte-for-byte unchanged.
        private const val KEY_NEXT_PREKEY_ID = "next_prekey_id"
        private const val KEY_NEXT_SIGNED_PREKEY_ID = "next_signed_prekey_id"
        private const val KEY_SIGNED_PREKEY_CREATED_AT = "signed_prekey_created_at"
        private const val KEY_PENDING_SIGNED_PREKEY_UPLOAD = "pending_signed_prekey_upload"
        private const val KEY_PENDING_PREKEY_UPLOADS = "pending_prekey_uploads"
        private const val KEY_PENDING_PREKEY_UPLOADS_ATTEMPTED = "pending_prekey_uploads_attempted"
    }
}
