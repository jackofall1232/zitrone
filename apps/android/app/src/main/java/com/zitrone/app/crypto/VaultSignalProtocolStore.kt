// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto

import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.wipe
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
import java.util.Base64
import java.util.UUID

/**
 * A [SignalProtocolStore] backed by the plausible-deniability vault instead of
 * EncryptedSharedPreferences. It is a behavioural TWIN of
 * [EncryptedSignalProtocolStore]: the SAME key scheme, the SAME record semantics, the
 * SAME prefix-scan enumeration — only the backing store changes, from a per-file prefs
 * blob to [signalRecords] inside one sealed [com.zitrone.app.crypto.vault.VaultState].
 * That fidelity is deliberate: PR-E migrates today's records into the vault under these
 * IDENTICAL keys, so anything this facade reads back must be byte-for-byte what the
 * legacy store wrote.
 *
 * Every override maps to [VaultRuntime.read] (pure lookups / prefix scans) or
 * [VaultRuntime.mutate] (writes) over [signalRecords]. Prefix enumeration is a key scan
 * of the shared map. Values are libsignal-native `serialize()` bytes stored RAW; the
 * ints / longs / booleans that legacy shared the Signal prefs file with (registration id,
 * the id counters, the signed-prekey timestamp, the kyber-used markers) are encoded as
 * fixed-width big-endian bytes under their SAME keys (see the `*Bytes` helpers) so the
 * migration copy is uniform.
 *
 * SLOT-AGNOSTIC. Nothing here logs, and no method's work varies with which slot is
 * unlocked — the runtime hands it one opaque map.
 *
 * NOT THREAD-SAFE beyond the runtime: every access already serializes on the runtime's
 * single lock, so this class holds no state of its own. Composed [read]+[mutate] pairs
 * that must be atomic (e.g. [saveIdentity]'s read-existing-then-write) are done INSIDE a
 * single [VaultRuntime.mutate] block.
 *
 * Isolated unit: NOT wired into SignalProtocolManager / ApiClient yet — that is PR-D,
 * where SignalProtocolManager drops its KeyStoreManager dependency in favour of the
 * counter accessors ([nextPreKeyId] etc.) exposed here.
 */
class VaultSignalProtocolStore(
    private val runtime: VaultRuntime,
) : SignalProtocolStore {

    // -- local identity -----------------------------------------------------

    /** True once the long-term identity has been generated (mirrors legacy `hasLocalIdentity`). */
    fun hasLocalIdentity(): Boolean = runtime.read { it.signalRecords.containsKey(KEY_IDENTITY) }

    fun setLocalIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int) {
        runtime.mutate {
            putRecord(it, KEY_IDENTITY, identityKeyPair.serialize())
            putRecord(it, KEY_REGISTRATION_ID, registrationId.toBeBytes())
        }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val bytes = runtime.read { it.signalRecords[KEY_IDENTITY] }
            ?: error("Identity key pair not yet generated")
        return IdentityKeyPair(bytes)
    }

    /** Registration id, default 0 when never set — matches legacy `prefs.getInt(_, 0)`. */
    override fun getLocalRegistrationId(): Int =
        runtime.read { it.signalRecords[KEY_REGISTRATION_ID] }?.toInt() ?: 0

    // -- remote identities --------------------------------------------------

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val key = remoteIdentityKey(address)
        val serialized = identityKey.serialize()
        // Read-existing + write in ONE mutate so the "identity changed?" answer is atomic.
        return runtime.mutate {
            val existing = it.signalRecords[key]
            // true == replaced a DIFFERENT pre-existing identity (key change!). Compute this
            // BEFORE putRecord, which wipes `existing`'s backing array (the superseded record).
            val changed = existing != null && !existing.contentEquals(serialized)
            putRecord(it, key, serialized)
            changed
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = runtime.read {
        val existing = it.signalRecords[remoteIdentityKey(address)]
            ?: return@read true // trust-on-first-use; verification happens via safety numbers
        existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        runtime.read { it.signalRecords[remoteIdentityKey(address)] }?.let { IdentityKey(it) }

    // -- one-time prekeys ---------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val bytes = runtime.read { it.signalRecords["$KEY_PREKEY$preKeyId"] }
            ?: throw InvalidKeyIdException("No prekey with id $preKeyId")
        return PreKeyRecord(bytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runtime.mutate { putRecord(it, "$KEY_PREKEY$preKeyId", record.serialize()) }
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        runtime.read { it.signalRecords.containsKey("$KEY_PREKEY$preKeyId") }

    /** One-time prekeys are single-use by design — consumed, then deleted. */
    override fun removePreKey(preKeyId: Int) {
        runtime.mutate { removeRecord(it, "$KEY_PREKEY$preKeyId") }
    }

    // -- signed prekeys -----------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val bytes = runtime.read { it.signalRecords["$KEY_SIGNED_PREKEY$signedPreKeyId"] }
            ?: throw InvalidKeyIdException("No signed prekey with id $signedPreKeyId")
        return SignedPreKeyRecord(bytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = runtime.read { state ->
        state.signalRecords
            .filterKeys { it.startsWith(KEY_SIGNED_PREKEY) }
            .values
            .map { SignedPreKeyRecord(it) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        runtime.mutate { putRecord(it, "$KEY_SIGNED_PREKEY$signedPreKeyId", record.serialize()) }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        runtime.read { it.signalRecords.containsKey("$KEY_SIGNED_PREKEY$signedPreKeyId") }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        runtime.mutate { removeRecord(it, "$KEY_SIGNED_PREKEY$signedPreKeyId") }
    }

    // -- sessions -------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        runtime.read { it.signalRecords[sessionKey(address)] }?.let { SessionRecord(it) }

    override fun loadExistingSessions(
        addresses: List<SignalProtocolAddress>,
    ): List<SessionRecord> = addresses.map { address ->
        loadSession(address) ?: throw NoSessionException("No session for $address")
    }

    override fun getSubDeviceSessions(name: String): List<Int> = runtime.read { state ->
        state.signalRecords.keys
            .filter { it.startsWith("$KEY_SESSION$name:") }
            .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
            .filter { it != DEFAULT_DEVICE_ID }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        runtime.mutate { putRecord(it, sessionKey(address), record.serialize()) }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        runtime.read { it.signalRecords.containsKey(sessionKey(address)) }

    override fun deleteSession(address: SignalProtocolAddress) {
        runtime.mutate { removeRecord(it, sessionKey(address)) }
    }

    override fun deleteAllSessions(name: String) {
        runtime.mutate { state ->
            state.signalRecords.keys
                .filter { it.startsWith("$KEY_SESSION$name:") }
                .forEach { removeRecord(state, it) }
        }
    }

    /**
     * Full cryptographic teardown for one peer, then a DURABLE commit — the vault twin of
     * [EncryptedSignalProtocolStore.destroyContactCrypto]. Removes the Double Ratchet
     * session (root/chain/skipped keys live inside the SessionRecord), the remote identity
     * record (all device ids — so a re-add re-runs X3DH against a fresh bundle, never
     * inheriting a prior TOFU pin), and any group sender keys, then forces a flush-before-ack.
     * Does NOT touch local identity or our own prekeys. Irreversible.
     *
     * @return `false` if the durable flush threw (the removal did NOT reach disk) — mirrors
     *         the legacy `commit()` boolean. The caller must NOT report the contact deleted
     *         on `false`, or orphaned session/identity state can reappear on restart and be
     *         reused instead of forcing a fresh X3DH.
     */
    fun destroyContactCrypto(name: String): Boolean {
        val prefixes = listOf(KEY_SESSION, KEY_REMOTE_IDENTITY, KEY_SENDER_KEY)
        runtime.mutate { state ->
            state.signalRecords.keys
                .filter { key -> prefixes.any { key.startsWith("$it$name:") } }
                .forEach { removeRecord(state, it) }
        }
        return try {
            runtime.flushBeforeAck()
            true
        } catch (t: Throwable) {
            false
        }
    }

    // -- Kyber prekeys (post-quantum, required by the store interface) --------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val bytes = runtime.read { it.signalRecords["$KEY_KYBER_PREKEY$kyberPreKeyId"] }
            ?: throw InvalidKeyIdException("No kyber prekey with id $kyberPreKeyId")
        return KyberPreKeyRecord(bytes)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = runtime.read { state ->
        // "kyber_prekey:" does NOT prefix-match "kyber_prekey_used:" (':' vs '_' at index 12),
        // so the used-markers are correctly excluded — same guarantee as the legacy store.
        state.signalRecords
            .filterKeys { it.startsWith(KEY_KYBER_PREKEY) }
            .values
            .map { KyberPreKeyRecord(it) }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        runtime.mutate { putRecord(it, "$KEY_KYBER_PREKEY$kyberPreKeyId", record.serialize()) }
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        runtime.read { it.signalRecords.containsKey("$KEY_KYBER_PREKEY$kyberPreKeyId") }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        // A FRESH one-byte marker per write — never a shared constant. VaultState.wipe() zeroes
        // every map value, so a shared array would be zeroed in place and alias every later
        // marker (and any live copy) to byteArrayOf(0). The read side tests presence, not value.
        runtime.mutate { putRecord(it, "$KEY_KYBER_USED$kyberPreKeyId", byteArrayOf(1)) }
    }

    // -- sender keys (groups — phase 2; interface requires the methods) -------

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        runtime.mutate { putRecord(it, senderKeyKey(sender, distributionId), record.serialize()) }
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? =
        runtime.read { it.signalRecords[senderKeyKey(sender, distributionId)] }
            ?.let { SenderKeyRecord(it) }

    // -- counters (PR-D: SignalProtocolManager reads/writes these instead of prefs) --------

    /**
     * The next one-time-prekey id, default 1 — matches legacy `nextId`'s
     * `prefs.getInt(counterKey, 1)`. SignalProtocolManager keeps its own
     * wrap-and-increment logic and just stores the result via [setNextPreKeyId].
     */
    fun nextPreKeyId(): Int = runtime.read { it.signalRecords[KEY_NEXT_PREKEY_ID] }?.toInt() ?: 1

    fun setNextPreKeyId(value: Int) {
        runtime.mutate { putRecord(it, KEY_NEXT_PREKEY_ID, value.toBeBytes()) }
    }

    /** The next signed-prekey id, default 1 (see [nextPreKeyId]). */
    fun nextSignedPreKeyId(): Int =
        runtime.read { it.signalRecords[KEY_NEXT_SIGNED_PREKEY_ID] }?.toInt() ?: 1

    fun setNextSignedPreKeyId(value: Int) {
        runtime.mutate { putRecord(it, KEY_NEXT_SIGNED_PREKEY_ID, value.toBeBytes()) }
    }

    /** The current signed prekey's creation timestamp (ms), default 0 (never rotated). */
    fun signedPreKeyCreatedAt(): Long =
        runtime.read { it.signalRecords[KEY_SIGNED_PREKEY_CREATED_AT] }?.toLong() ?: 0L

    fun setSignedPreKeyCreatedAt(value: Long) {
        runtime.mutate { putRecord(it, KEY_SIGNED_PREKEY_CREATED_AT, value.toBeBytes()) }
    }

    // -- misc -----------------------------------------------------------------

    fun countOneTimePreKeys(): Int =
        runtime.read { state -> state.signalRecords.keys.count { it.startsWith(KEY_PREKEY) } }

    /**
     * Distinct remote-contact account-ids that still hold an identity record, each paired
     * with that stored remote identity key (base64), or null if unreadable. The vault twin
     * of [EncryptedSignalProtocolStore.knownRemoteContacts] — the one-time roster-repair
     * source (see [com.zitrone.app.data.VaultRosterStore.orphanedContacts]). Uses standard
     * base64 (== the legacy store's `NO_WRAP`), and `java.util.Base64` so the facade stays
     * host-JVM testable (no `android.util`).
     */
    fun knownRemoteContacts(): List<Pair<String, String?>> =
        runtime.read { knownRemoteContactsOf(it.signalRecords) }

    /** Full local wipe — account deletion. Irreversible by design (mirrors legacy `wipe`). */
    fun wipe() {
        runtime.mutate { it.wipe() }
    }

    /**
     * Store [value] under [key], ZEROING the superseded record it replaces. Every write here
     * carries raw key material (identity, ratchet SessionRecord, prekeys, sender keys); the old
     * serialized array must not linger un-wiped in heap once a newer record supplants it. The
     * caller must hand a FRESH [value] (serialize()/toBeBytes() return fresh arrays), never one
     * aliased elsewhere in the live map — putRecord wipes only the DISPLACED array.
     */
    private fun putRecord(state: VaultState, key: String, value: ByteArray) {
        state.signalRecords.put(key, value)?.let { wipe(it) }
    }

    /** Remove [key], ZEROING the removed record's bytes (a dropped session/prekey is still secret). */
    private fun removeRecord(state: VaultState, key: String) {
        state.signalRecords.remove(key)?.let { wipe(it) }
    }

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

        // SignalProtocolManager's counter keys — shared the same prefs file historically,
        // now the same signalRecords map.
        private const val KEY_NEXT_PREKEY_ID = "next_prekey_id"
        private const val KEY_NEXT_SIGNED_PREKEY_ID = "next_signed_prekey_id"
        private const val KEY_SIGNED_PREKEY_CREATED_AT = "signed_prekey_created_at"
    }
}

/**
 * Shared implementation behind [VaultSignalProtocolStore.knownRemoteContacts] and
 * [com.zitrone.app.data.VaultRosterStore.orphanedContacts] — both read the SAME
 * [signalRecords] map through the SAME runtime, so the repair source has exactly one
 * definition. Scans `remote_identity:` keys, strips the `:deviceId` suffix, dedupes the
 * account ids, and pairs each with its device-1 identity record (base64) if present.
 */
internal fun knownRemoteContactsOf(records: Map<String, ByteArray>): List<Pair<String, String?>> {
    val prefix = "remote_identity:"
    return records.keys
        .filter { it.startsWith(prefix) }
        .mapNotNull { key ->
            key.removePrefix(prefix)
                .substringBeforeLast(':') // strip the :deviceId suffix
                .takeIf { it.isNotEmpty() }
        }
        .distinct()
        .map { accountId ->
            val b64 = records["$prefix$accountId:${VaultSignalProtocolStore.DEFAULT_DEVICE_ID}"]
                ?.let { Base64.getEncoder().encodeToString(it) }
            accountId to b64
        }
}

// -- fixed-width big-endian scalar codecs (int/long stored RAW under their keys) --------

private fun Int.toBeBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    this.toByte(),
)

private fun Long.toBeBytes(): ByteArray = ByteArray(8) { i ->
    (this ushr (56 - i * 8)).toByte()
}

private fun ByteArray.toInt(): Int {
    require(size == 4) { "expected a 4-byte int record, got $size" }
    return ((this[0].toInt() and 0xff) shl 24) or
        ((this[1].toInt() and 0xff) shl 16) or
        ((this[2].toInt() and 0xff) shl 8) or
        (this[3].toInt() and 0xff)
}

private fun ByteArray.toLong(): Long {
    require(size == 8) { "expected an 8-byte long record, got $size" }
    var v = 0L
    for (b in this) v = (v shl 8) or (b.toLong() and 0xff)
    return v
}
