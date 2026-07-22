// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import com.zitrone.app.data.AuthState
import com.zitrone.app.data.VaultScopedSettings
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
     * token strings are JVM `String`s — immutable and un-zeroable, so their BYTES
     * cannot be scrubbed; but this now DROPS our references to them (nulls the two
     * blobs, swaps in a fresh empty [AuthState] / [VaultScopedSettings]) so they are
     * GC-eligible instead of pinned reachable through this state, which [VaultRuntime]
     * still holds as a private field after close. Un-pinning an un-zeroable `String`
     * is the best available on the JVM — the SAME accepted tradeoff the passphrase
     * path carries (see KeySlot.kt's `KeyDeriver` note) — an honest improvement over
     * leaving them strongly reachable; the derived, high-value secrets (the Signal
     * records) ARE zeroed.
     *
     * SCOPE. This zeroes the LIVE map only. Record bytes also pass transiently
     * through [VaultStateCodec] on every encode/decode; that codec zeroes each of
     * its own intermediate buffers in `finally` (see its class kdoc), leaving only
     * the Deflater/Inflater internal native state as a bounded, documented residual.
     * So "the Signal records ARE wiped" is a claim about THIS map, not a promise
     * that no compression-engine copy ever existed.
     */
    fun wipe() {
        for (value in signalRecords.values) wipe(value)
        signalRecords.clear()
        // Drop references to the un-zeroable String-backed secrets so GC can reclaim them,
        // rather than leaving them pinned reachable through this still-held state after close.
        rosterJson = null
        tombstonesJson = null
        auth = AuthState()
        settings = VaultScopedSettings()
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
 * WIPE DISCIPLINE. The codec accumulates every secret-bearing intermediate — the whole
 * plaintext, each section body, the deflate/inflate output — in a [WipeableBuffer] whose
 * backing array it zeroes in a `finally` on EVERY path (including growth: a grow wipes the
 * array it outgrew before discarding it). It deliberately does NOT use
 * [java.io.ByteArrayOutputStream], whose internal `buf` holds un-reachable copies of raw key
 * material that no `wipe()` can zero. The ONE residual it cannot reach is the Deflater /
 * Inflater's internal native state (input + sliding window): `end()` frees it but does not
 * zero it, so a bounded transient lingers there until the allocator reuses the memory — the
 * same accepted, documented tradeoff as the un-wipeable `String` fields, NOT a claim that
 * nothing lingers.
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
     * Serialize [state] to the sealed-region bytes. Throws [VaultCapacityException] when the
     * plaintext exceeds [INFLATE_CAP] or the compressed result exceeds
     * [MAX_PAYLOAD_CONTENT_BYTES]. Every intermediate (plaintext, section bodies, deflate
     * output — all raw records) is accumulated in a [WipeableBuffer] and zeroed in `finally`;
     * only the Deflater's internal native state is an un-zeroable residual (see class kdoc).
     */
    fun encode(state: VaultState): ByteArray {
        val plain = buildPlaintext(state)
        try {
            // encode and decode share the INFLATE_CAP plaintext bound so the two are symmetric:
            // a plaintext this large would fail decode's INFLATE_CAP inflate guard, so reject it
            // HERE rather than persist a state that could never be reloaded. (Unreachable for
            // real state — ~8KB per the PR-D benchmark — but closes the encode/decode asymmetry.)
            if (plain.size > INFLATE_CAP) {
                throw VaultCapacityException(
                    "vault state plaintext exceeds inflate cap (${plain.size} > $INFLATE_CAP)",
                )
            }
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
     * plaintext and each parsed section body are accumulated/held in wipeable buffers and
     * zeroed in `finally`; only the Inflater's internal native state is an un-zeroable
     * residual (see class kdoc).
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
        val out = WipeableBuffer()
        try {
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
        } finally {
            // The whole plaintext (raw records) lived here — zero it. The exact-size result
            // is the caller's `plain`, wiped in encode's finally.
            out.wipe()
        }
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

        try {
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

            // v1 ALWAYS emits signal, settings, auth (only roster/tombstones are nullable/omitted).
            // A truncated-but-valid-deflate payload missing any of them is corruption, NOT a
            // partial-default state — reject rather than silently fall back to empty holders.
            // requireNotNull throws IllegalArgumentException INSIDE the try, so the catch below
            // also wipes any partial signal map decoded before the missing section was noticed.
            val decodedSignal = requireNotNull(signal) { "missing signal section" }
            val decodedSettings = requireNotNull(settings) { "missing settings section" }
            val decodedAuth = requireNotNull(auth) { "missing auth section" }

            return VaultState(
                signalRecords = decodedSignal,
                rosterJson = rosterJson,
                tombstonesJson = tombstonesJson,
                settings = decodedSettings,
                auth = decodedAuth,
            )
        } catch (t: Throwable) {
            // A malformed/unknown later section (or a missing-mandatory require) can throw AFTER
            // decodeSignal already copied raw key material into `signal`. Zero those record bytes
            // before the throw escapes so a decode failure strands nothing un-wiped in heap.
            signal?.let { partial ->
                for (value in partial.values) wipe(value)
                partial.clear()
            }
            throw t
        }
    }

    // ── 0x01 signal ─────────────────────────────────────────────────────────────

    private fun encodeSignal(records: Map<String, ByteArray>): ByteArray {
        val out = WipeableBuffer()
        try {
            writeInt(out, records.size)
            // Sorted so equal state encodes to identical bytes (determinism; see class kdoc).
            for (key in records.keys.sorted()) {
                val value = records.getValue(key)
                val keyBytes = key.toByteArray(Charsets.UTF_8) // key ("prekey:5" …) is not secret
                writeShort(out, keyBytes.size)
                out.write(keyBytes)
                writeInt(out, value.size)
                out.write(value) // copied INTO out; `value` is the live map's array, never wiped here
            }
            return out.toByteArray()
        } finally {
            // out held every record value — zero it. The exact-size result is the signal
            // section body, wiped by writeSection once folded into the plaintext.
            out.wipe()
        }
    }

    private fun decodeSignal(body: ByteArray): MutableMap<String, ByteArray> {
        val r = Reader(body)
        val count = r.i32()
        require(count >= 0) { "negative signal record count" }
        // Pre-size BOUNDED, never from the raw (untrusted) count: a corrupt huge count would
        // otherwise force a multi-GB HashMap allocation (OOM) before the entry loop's Reader
        // bounds checks — which reject any count larger than the body supports — get to run.
        val map = HashMap<String, ByteArray>(minOf(count, 1024) * 2)
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
        val out = WipeableBuffer(9)
        try {
            val ttl = s.defaultTtlSeconds
            out.write(if (ttl == null) 0 else 1)
            writeInt(out, ttl ?: 0)
            out.write(if (s.burnOnReadDefault) 1 else 0)
            out.write(if (s.readReceipts) 1 else 0)
            out.write(if (s.lemonDropComposeEnabled) 1 else 0)
            out.write(if (s.unreadReminderEnabled) 1 else 0)
            return out.toByteArray()
        } finally {
            out.wipe()
        }
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
        val out = WipeableBuffer()
        try {
            writeNullableString(out, a.accountId)
            writeNullableString(out, a.accessToken)
            writeNullableString(out, a.refreshToken)
            return out.toByteArray()
        } finally {
            // out held the token bytes — zero it. The exact-size result is the auth section
            // body, wiped by writeSection.
            out.wipe()
        }
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
    private fun writeNullableString(out: WipeableBuffer, s: String?) {
        if (s == null) {
            writeInt(out, NULL_LEN)
        } else {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeInt(out, bytes.size)
            out.write(bytes)
            // `bytes` is a fresh copy of token material (the source String is itself
            // un-wipeable) — zero this transient now that it is folded into `out`.
            wipe(bytes)
        }
    }

    private fun readNullableString(r: Reader): String? {
        val len = r.i32()
        if (len == NULL_LEN) return null
        require(len >= 0) { "invalid nullable-string length: $len" }
        return String(r.bytes(len), Charsets.UTF_8)
    }

    // ── section framing helpers ──────────────────────────────────────────────────

    private fun writeSection(out: WipeableBuffer, tag: Int, body: ByteArray) {
        out.write(tag)
        writeInt(out, body.size)
        out.write(body)
        // The body carried a copy of section secrets into `out`; wipe the transient copy.
        wipe(body)
    }

    private fun writeInt(out: WipeableBuffer, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeShort(out: WipeableBuffer, value: Int) {
        require(value in 0..0xffff) { "value out of 16-bit range: $value" }
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    // ── DEFLATE / INFLATE ────────────────────────────────────────────────────────

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val chunk = ByteArray(8192)
        val out = WipeableBuffer(input.size / 2 + 32)
        try {
            deflater.setInput(input)
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end() // frees native input+window state (not zeroed — see class kdoc)
            wipe(chunk)
            out.wipe() // held the compressed secrets
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater()
        val chunk = ByteArray(8192)
        val out = WipeableBuffer(input.size * 2 + 32)
        try {
            inflater.setInput(input)
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
                // Belt-and-braces zip-bomb guard (input is authenticated ciphertext). The
                // `finally` wipes `out` on this throw path too, so no partial plaintext lingers.
                if (out.size() > INFLATE_CAP) {
                    throw IllegalArgumentException("inflated vault state exceeds cap ($INFLATE_CAP)")
                }
            }
            require(inflater.finished()) { "truncated vault state" }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("corrupt vault state (inflate failed)", e)
        } finally {
            inflater.end() // frees native input+window state (not zeroed — see class kdoc)
            wipe(chunk)
            out.wipe() // held the inflated plaintext
        }
    }

    /**
     * A minimal growable byte sink whose backing array is ZEROABLE — the codec's stand-in
     * for [java.io.ByteArrayOutputStream], whose internal `buf` holds copies of raw key
     * material no `wipe()` can reach. Every grow copies into a larger array and then WIPES
     * the array it outgrew, so a secret copy is never orphaned mid-encode; [wipe] zeroes the
     * live array. NOT thread-safe — the codec runs single-threaded under the runtime lock.
     * [toByteArray] returns an exact-size copy the caller owns (and wipes per its discipline).
     */
    private class WipeableBuffer(initial: Int = 64) {
        private var buf: ByteArray = ByteArray(if (initial < 1) 1 else initial)
        private var len: Int = 0

        fun size(): Int = len

        /** Append the low byte of [b] (matching [java.io.ByteArrayOutputStream.write]`(int)`). */
        fun write(b: Int) {
            ensure(1)
            buf[len++] = b.toByte()
        }

        fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

        fun write(bytes: ByteArray, off: Int, n: Int) {
            if (n <= 0) return
            ensure(n)
            System.arraycopy(bytes, off, buf, len, n)
            len += n
        }

        /** An exact-size copy of the written bytes; ownership (and wiping) passes to the caller. */
        fun toByteArray(): ByteArray = buf.copyOf(len)

        /** Zero the backing array and reset the length — call in `finally` on every path. */
        fun wipe() {
            buf.fill(0)
            len = 0
        }

        /** Grow to fit [extra] more bytes, WIPING the outgrown array so no secret copy lingers. */
        private fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var newCap = buf.size * 2
            while (newCap < len + extra) newCap *= 2
            val bigger = ByteArray(newCap)
            System.arraycopy(buf, 0, bigger, 0, len)
            wipe(buf) // zero the old backing array before it becomes unreachable garbage
            buf = bigger
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
