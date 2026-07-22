// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import com.zitrone.app.data.AuthState
import com.zitrone.app.data.VaultScopedSettings
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The in-memory keystore a single unlocked slot holds, plus its wire codec.
 *
 * This is the WHOLE plaintext a [VaultSession] seals into one fixed-size payload
 * region: every Signal-protocol record (identity, prekeys, ratchet sessions,
 * sender keys), the contact roster + tombstone blobs, the vault-scoped settings,
 * and the auth tokens. Today those live in five separate EncryptedSharedPreferences
 * files; the vault runtime collapses them into ONE sealed region so a locked vault
 * leaves nothing on disk, and a decoy vault's data is byte-indistinguishable from a
 * real one's. The PR-C facades ([VaultSignalProtocolStore], VaultRosterStore,
 * VaultAuthStore, VaultSettingsStore) read/mutate this object through [VaultRuntime];
 * PR-D wires them into the app, PR-E migrates today's prefs into it.
 *
 * KEY-SCHEME FIDELITY (load-bearing for the PR-E migration). [signalRecords] uses
 * the EXACT key strings today's [com.zitrone.app.crypto.EncryptedSignalProtocolStore]
 * (+ SignalProtocolManager's counters) persist under — `identity_keypair`,
 * `registration_id`, `remote_identity:<acct>:<dev>`, `prekey:<id>`,
 * `signed_prekey:<id>`, `session:<acct>:<dev>`, `kyber_prekey:<id>`,
 * `kyber_prekey_used:<id>`, `sender_key:<acct>:<dev>:<uuid>`, `next_prekey_id`,
 * `next_signed_prekey_id`, `signed_prekey_created_at` — so migration is a verbatim
 * copy under identical keys. Values are libsignal-native `serialize()` bytes RAW
 * (no Base64 — ~25% smaller than today's Base64-in-prefs); the ints / longs /
 * booleans that share those files are encoded as fixed-width bytes under their same
 * keys by [VaultSignalProtocolStore] (this codec is content-agnostic — it moves
 * whatever bytes the facades store).
 *
 * MUTABILITY. [signalRecords] is mutated in place (put/remove) by the signal facade;
 * [rosterJson] / [tombstonesJson] / [settings] / [auth] are swapped wholesale (the
 * settings/auth holders are immutable data classes). ALL mutation happens inside
 * [VaultRuntime.mutate] under its single lock — this object is NOT itself thread-safe
 * and must never be touched outside a runtime read/mutate block.
 */
class VaultState(
    /** Signal-protocol records under TODAY's exact key scheme (see class kdoc). */
    val signalRecords: MutableMap<String, ByteArray>,
    /** ConversationRepository's roster JSON blob, verbatim; null when never written. */
    var rosterJson: String?,
    /** Deleted-contact tombstone JSON blob, verbatim; null when never written. */
    var tombstonesJson: String?,
    /** Vault-scoped user settings (NOT the device-level ones — see [VaultScopedSettings]). */
    var settings: VaultScopedSettings,
    /** Account id + session tokens. */
    var auth: AuthState,
) {
    /**
     * Zero every held secret. Called by [VaultRuntime.close] under its lock.
     *
     * Zeroes each [signalRecords] value (raw key material — identity / ratchet
     * bytes) then clears the map. [rosterJson] / [tombstonesJson] and the [auth]
     * token strings are JVM `String`s — immutable and un-wipeable, so they can
     * linger in heap until GC. That is the SAME accepted, documented tradeoff the
     * passphrase path carries (see KeySlot.kt's `KeyDeriver` note); the derived,
     * high-value secrets (the Signal records) ARE wiped.
     */
    fun wipe() {
        for (value in signalRecords.values) wipe(value)
        signalRecords.clear()
    }

    companion object {
        /** A fresh, empty keystore — the genesis state a new vault is created around. */
        fun empty(): VaultState = VaultState(
            signalRecords = HashMap(),
            rosterJson = null,
            tombstonesJson = null,
            settings = VaultScopedSettings(),
            auth = AuthState(),
        )
    }
}

/**
 * Thrown by [VaultStateCodec.encode] when the compressed keystore no longer fits the
 * fixed payload region. Extends [IllegalStateException] so existing `catch`es still
 * see it, but is a DISTINCT type so [VaultRuntime] and PR-D can treat a capacity
 * failure specially (surface a "vault full" state) rather than as a generic bug. The
 * region never grows — a larger payload would leak that a real vault lives here and
 * how big it is — so hitting the cap is a real, user-facing condition, not corruption.
 */
class VaultCapacityException(message: String) : IllegalStateException(message)

/**
 * Versioned TLV-over-DEFLATE codec between [VaultState] and the sealed payload bytes.
 *
 * WIRE FORMAT (v1). Plaintext is `version(1)=1 ‖ section*`, each section
 * `tag(1) ‖ len(4 BE) ‖ body`:
 *  - `0x01` **signal**: `count(4 BE) ‖ entry*`, entry = `keyLen(2 BE) ‖ keyUtf8 ‖ valLen(4 BE) ‖ val`.
 *    ALWAYS emitted (count 0 when empty). Keys are iterated SORTED so equal state →
 *    identical bytes (a test convenience; there is no security requirement — the whole
 *    thing lives inside the AEAD-sealed padded region).
 *  - `0x02` **rosterJson** (utf8) / `0x03` **tombstonesJson** (utf8): NULLABLE — the tag
 *    is OMITTED entirely when the field is null.
 *  - `0x04` **settings**: fixed 9-byte k/v (see [encodeSettings]). ALWAYS emitted.
 *  - `0x05` **auth**: three length-prefixed nullable strings (see [encodeAuth]). ALWAYS emitted.
 *  An UNKNOWN tag on decode THROWS (strict v1 — a future format change owns its own
 *  migration behind a version bump; there is no forward-tolerant skip).
 *
 * COMPRESSION lives INSIDE the sealed, padded plaintext, so the on-disk region stays a
 * constant [SLOT_PAYLOAD_BYTES] regardless of how compressible the state is — zero
 * size signal. Output is `deflate(plain, BEST_COMPRESSION)`; [decode] inflates first,
 * capped at [INFLATE_CAP] ([PAYLOAD_PLAINTEXT_BYTES] × 8) as a belt-and-braces
 * zip-bomb guard (the input is already authenticated ciphertext, so a bomb is not a
 * real threat — this just refuses to allocate unboundedly on a corrupt blob).
 *
 * CAPACITY. [encode] throws [VaultCapacityException] when the deflated size exceeds
 * [MAX_PAYLOAD_CONTENT_BYTES] — the exact bound [VaultSession.update] enforces, so the
 * typed capacity throw always fires BEFORE the session's generic size `require`.
 *
 * WIPE DISCIPLINE. Both directions zero the intermediate plaintext buffer (which holds
 * raw records) in a `finally`, on every path.
 */
object VaultStateCodec {

    private const val VERSION = 1

    private const val TAG_SIGNAL = 0x01
    private const val TAG_ROSTER = 0x02
    private const val TAG_TOMBSTONES = 0x03
    private const val TAG_SETTINGS = 0x04
    private const val TAG_AUTH = 0x05

    /** A null nullable-string is written as this sentinel length (see [encodeAuth]). */
    private const val NULL_LEN = -1

    /**
     * Largest deflated payload that fits the fixed region: the region's plaintext
     * capacity minus the 4-byte length prefix VaultPayload prepends inside the sealed
     * region. Identical to [VaultSession]'s private `MAX_PAYLOAD_CONTENT_BYTES`, so a
     * state that this codec accepts is always one [VaultSession.update] also accepts.
     */
    const val MAX_PAYLOAD_CONTENT_BYTES: Int = PAYLOAD_PLAINTEXT_BYTES - 4

    /** Zip-bomb ceiling on inflate output — see class kdoc. */
    private const val INFLATE_CAP: Int = PAYLOAD_PLAINTEXT_BYTES * 8

    /**
     * Serialize [state] to the sealed-region bytes. Throws [VaultCapacityException]
     * when the compressed result exceeds [MAX_PAYLOAD_CONTENT_BYTES]. The intermediate
     * plaintext (raw records) is wiped in `finally`.
     */
    fun encode(state: VaultState): ByteArray {
        val plain = buildPlaintext(state)
        try {
            val deflated = deflate(plain)
            if (deflated.size > MAX_PAYLOAD_CONTENT_BYTES) {
                // The compressed blob no longer fits the fixed region. Wipe it too — it
                // is compressed secrets — then throw the typed capacity signal.
                wipe(deflated)
                throw VaultCapacityException(
                    "vault state exceeds slot capacity (${deflated.size} > $MAX_PAYLOAD_CONTENT_BYTES)",
                )
            }
            return deflated
        } finally {
            wipe(plain)
        }
    }

    /**
     * Parse sealed-region [bytes] back into a [VaultState]. Inflates (bounded by
     * [INFLATE_CAP]) then parses the TLV. Throws [IllegalArgumentException] on garbage,
     * truncation, an unknown tag, or a section that overruns its length. The inflated
     * intermediate plaintext is wiped in `finally`.
     */
    fun decode(bytes: ByteArray): VaultState {
        val plain = inflate(bytes)
        try {
            return parsePlaintext(plain)
        } finally {
            wipe(plain)
        }
    }

    // ── plaintext (TLV) ───────────────────────────────────────────────────────────

    private fun buildPlaintext(state: VaultState): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        // 0x01 signal — always present (count 0 when the map is empty).
        writeSection(out, TAG_SIGNAL, encodeSignal(state.signalRecords))
        // 0x02 / 0x03 — nullable: tag omitted entirely when null.
        state.rosterJson?.let { writeSection(out, TAG_ROSTER, it.toByteArray(Charsets.UTF_8)) }
        state.tombstonesJson?.let { writeSection(out, TAG_TOMBSTONES, it.toByteArray(Charsets.UTF_8)) }
        // 0x04 / 0x05 — always present objects.
        writeSection(out, TAG_SETTINGS, encodeSettings(state.settings))
        writeSection(out, TAG_AUTH, encodeAuth(state.auth))
        return out.toByteArray()
    }

    private fun parsePlaintext(plain: ByteArray): VaultState {
        val r = Reader(plain)
        val version = r.u8()
        require(version == VERSION) { "unsupported vault state version: $version" }

        var signal: MutableMap<String, ByteArray>? = null
        var rosterJson: String? = null
        var tombstonesJson: String? = null
        var settings: VaultScopedSettings? = null
        var auth: AuthState? = null

        while (r.hasRemaining()) {
            val tag = r.u8()
            val len = r.i32()
            require(len >= 0) { "negative section length" }
            val body = r.bytes(len)
            try {
                when (tag) {
                    TAG_SIGNAL -> signal = decodeSignal(body)
                    TAG_ROSTER -> rosterJson = String(body, Charsets.UTF_8)
                    TAG_TOMBSTONES -> tombstonesJson = String(body, Charsets.UTF_8)
                    TAG_SETTINGS -> settings = decodeSettings(body)
                    TAG_AUTH -> auth = decodeAuth(body)
                    // Strict v1: an unknown tag is corruption / a wrong version, never skipped.
                    else -> throw IllegalArgumentException("unknown vault state section tag: $tag")
                }
            } finally {
                // Each section body is a copy of sensitive plaintext — wipe it once parsed
                // (record values were copied OUT into the map; the strings are immutable copies).
                wipe(body)
            }
        }

        return VaultState(
            signalRecords = signal ?: HashMap(),
            rosterJson = rosterJson,
            tombstonesJson = tombstonesJson,
            settings = settings ?: VaultScopedSettings(),
            auth = auth ?: AuthState(),
        )
    }

    // ── 0x01 signal ─────────────────────────────────────────────────────────────

    private fun encodeSignal(records: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt(out, records.size)
        // Sorted so equal state encodes to identical bytes (determinism; see class kdoc).
        for (key in records.keys.sorted()) {
            val value = records.getValue(key)
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            writeShort(out, keyBytes.size)
            out.write(keyBytes)
            writeInt(out, value.size)
            out.write(value)
        }
        return out.toByteArray()
    }

    private fun decodeSignal(body: ByteArray): MutableMap<String, ByteArray> {
        val r = Reader(body)
        val count = r.i32()
        require(count >= 0) { "negative signal record count" }
        val map = HashMap<String, ByteArray>(count * 2)
        repeat(count) {
            val keyLen = r.u16()
            val key = String(r.bytes(keyLen), Charsets.UTF_8)
            val valLen = r.i32()
            require(valLen >= 0) { "negative signal value length" }
            // Copy the value OUT of the (soon-wiped) body into an independent array.
            map[key] = r.bytes(valLen)
        }
        require(!r.hasRemaining()) { "trailing bytes in signal section" }
        return map
    }

    // ── 0x04 settings (fixed 9 bytes) ───────────────────────────────────────────

    private fun encodeSettings(s: VaultScopedSettings): ByteArray {
        // ttl: present-flag(1) ‖ int(4 BE) | burnOnRead(1) | readReceipts(1) |
        // lemonDropCompose(1) | unreadReminder(1)  → 9 bytes, fixed order.
        val out = ByteArrayOutputStream(9)
        val ttl = s.defaultTtlSeconds
        out.write(if (ttl == null) 0 else 1)
        writeInt(out, ttl ?: 0)
        out.write(if (s.burnOnReadDefault) 1 else 0)
        out.write(if (s.readReceipts) 1 else 0)
        out.write(if (s.lemonDropComposeEnabled) 1 else 0)
        out.write(if (s.unreadReminderEnabled) 1 else 0)
        return out.toByteArray()
    }

    private fun decodeSettings(body: ByteArray): VaultScopedSettings {
        val r = Reader(body)
        val ttlPresent = r.u8() != 0
        val ttlValue = r.i32()
        val settings = VaultScopedSettings(
            defaultTtlSeconds = if (ttlPresent) ttlValue else null,
            burnOnReadDefault = r.u8() != 0,
            readReceipts = r.u8() != 0,
            lemonDropComposeEnabled = r.u8() != 0,
            unreadReminderEnabled = r.u8() != 0,
        )
        require(!r.hasRemaining()) { "trailing bytes in settings section" }
        return settings
    }

    // ── 0x05 auth (3 length-prefixed nullable strings) ──────────────────────────

    private fun encodeAuth(a: AuthState): ByteArray {
        val out = ByteArrayOutputStream()
        writeNullableString(out, a.accountId)
        writeNullableString(out, a.accessToken)
        writeNullableString(out, a.refreshToken)
        return out.toByteArray()
    }

    private fun decodeAuth(body: ByteArray): AuthState {
        val r = Reader(body)
        val auth = AuthState(
            accountId = readNullableString(r),
            accessToken = readNullableString(r),
            refreshToken = readNullableString(r),
        )
        require(!r.hasRemaining()) { "trailing bytes in auth section" }
        return auth
    }

    /** A nullable string as `len(4 BE)`; [NULL_LEN] (-1) means null, else utf8 bytes follow. */
    private fun writeNullableString(out: ByteArrayOutputStream, s: String?) {
        if (s == null) {
            writeInt(out, NULL_LEN)
        } else {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeInt(out, bytes.size)
            out.write(bytes)
        }
    }

    private fun readNullableString(r: Reader): String? {
        val len = r.i32()
        if (len == NULL_LEN) return null
        require(len >= 0) { "invalid nullable-string length: $len" }
        return String(r.bytes(len), Charsets.UTF_8)
    }

    // ── section framing helpers ──────────────────────────────────────────────────

    private fun writeSection(out: ByteArrayOutputStream, tag: Int, body: ByteArray) {
        out.write(tag)
        writeInt(out, body.size)
        out.write(body)
        // The body carried a copy of section secrets into `out`; wipe the transient copy.
        wipe(body)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..0xffff) { "value out of 16-bit range: $value" }
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    // ── DEFLATE / INFLATE ────────────────────────────────────────────────────────

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val chunk = ByteArray(8192)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 2 + 32)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
            wipe(chunk)
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater()
        val chunk = ByteArray(8192)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size * 2 + 32)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) {
                    // finished / needs a preset dictionary (we never set one) → done or corrupt;
                    // needsInput with unfinished stream → truncated. Either way, stop and let the
                    // finished()/size checks below decide.
                    if (inflater.finished() || inflater.needsDictionary()) break
                    if (inflater.needsInput()) throw IllegalArgumentException("truncated vault state")
                }
                out.write(chunk, 0, n)
                // Belt-and-braces zip-bomb guard (input is authenticated ciphertext).
                if (out.size() > INFLATE_CAP) {
                    val partial = out.toByteArray()
                    wipe(partial)
                    throw IllegalArgumentException("inflated vault state exceeds cap ($INFLATE_CAP)")
                }
            }
            require(inflater.finished()) { "truncated vault state" }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("corrupt vault state (inflate failed)", e)
        } finally {
            inflater.end()
            wipe(chunk)
        }
    }

    /**
     * A bounds-checked forward cursor over a byte array. Every read validates it stays
     * in range and throws [IllegalArgumentException] on underflow, so a truncated or
     * malformed section fails cleanly rather than with a raw index exception.
     */
    private class Reader(private val a: ByteArray) {
        private var pos = 0

        fun hasRemaining(): Boolean = pos < a.size

        fun u8(): Int {
            require(pos + 1 <= a.size) { "unexpected end of vault state" }
            return a[pos++].toInt() and 0xff
        }

        fun u16(): Int {
            require(pos + 2 <= a.size) { "unexpected end of vault state" }
            val v = ((a[pos].toInt() and 0xff) shl 8) or (a[pos + 1].toInt() and 0xff)
            pos += 2
            return v
        }

        fun i32(): Int {
            require(pos + 4 <= a.size) { "unexpected end of vault state" }
            val v = ((a[pos].toInt() and 0xff) shl 24) or
                ((a[pos + 1].toInt() and 0xff) shl 16) or
                ((a[pos + 2].toInt() and 0xff) shl 8) or
                (a[pos + 3].toInt() and 0xff)
            pos += 4
            return v
        }

        /** Copy the next [n] bytes into a fresh array. */
        fun bytes(n: Int): ByteArray {
            require(n >= 0) { "negative length: $n" }
            require(pos + n <= a.size) { "unexpected end of vault state" }
            val out = a.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }
}
