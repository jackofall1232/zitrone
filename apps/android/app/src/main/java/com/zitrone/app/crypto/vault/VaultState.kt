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
    /**
     * Cover-traffic state for THIS vault, or null when this vault has none (the valid
     * initial state — see [DecoyState]). Vault-scoped by requirement: nothing about it
     * may reach device-level storage.
     */
    var decoy: DecoyState? = null,
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
        // [DecoyState.identityKeyPair] is RAW PRIVATE KEY MATERIAL in a ByteArray — the same
        // class of secret as a Signal record, so it is ZEROED (not merely dereferenced) before
        // the reference is dropped. Its token Strings share the un-zeroable-String tradeoff
        // documented above.
        decoy?.wipe()
        decoy = null
    }

    companion object {
        /** A fresh, empty keystore — the genesis state a new vault is created around. */
        fun empty(): VaultState = VaultState(
            signalRecords = HashMap(),
            rosterJson = null,
            tombstonesJson = null,
            settings = VaultScopedSettings(),
            auth = AuthState(),
            decoy = null,
        )
    }
}

/**
 * Cover-traffic state for ONE vault — the whole content of `TAG_DECOY` (0x06).
 *
 * Holds the synthetic relay account this vault addresses its cover traffic to (account id +
 * long-term identity keypair + session tokens), the counter-reservation high-water mark, the
 * dead-air schedule's next fire, and a provisioning deferral. Immutable: it is swapped
 * wholesale inside a [VaultRuntime.mutate] block, never field-mutated, exactly like
 * [com.zitrone.app.data.AuthState].
 *
 * ⚠️ **PRESENCE IS NOT READINESS.** A section exists as soon as there is anything at all to
 * record — including a bare provisioning deferral with no account. The ONLY test for "this vault
 * has a usable synthetic account" is [isProvisioned] (both [accountId] and [identityKeyPair]
 * non-null). Those two are always committed in the SAME mutate, so a state carrying one
 * without the other is unreachable — an interrupted provision leaves an orphaned relay
 * account and NO section change, never a section referencing an account whose signing key was
 * never persisted.
 *
 * ⚠️ **[counterHighWater] is a RESERVATION, and its meaning is "every value strictly below
 * this may already have been issued".** It is persisted BEFORE any value in the newly reserved
 * block is spent, so an interruption SKIPS counter values (invisible — a real Double Ratchet
 * skips on any dropped message) and can never REGRESS them (a tell no real ratchet produces).
 * It must only ever increase.
 *
 * VAULT-SCOPED BY REQUIREMENT. None of this may be mirrored into `SettingsRepository`,
 * `DeviceSettings`, any `SharedPreferences`, or any device-level diagnostics file: a
 * device-level record of how many synthetic accounts exist is a vault-count oracle.
 *
 * [identityKeyPair] is libsignal `IdentityKeyPair.serialize()` — PUBLIC ‖ PRIVATE. It is
 * zeroed by [wipe], which [VaultState.wipe] calls at close.
 */
class DecoyState(
    /** The synthetic relay account's UUID, or null before it is provisioned. */
    val accountId: String? = null,
    /** libsignal `IdentityKeyPair.serialize()` for that account (PRIVATE material), or null. */
    val identityKeyPair: ByteArray? = null,
    /** That account's current access JWT, or null when no session is held. */
    val accessToken: String? = null,
    /** That account's current (single-use, rotated) refresh token, or null. */
    val refreshToken: String? = null,
    /** Reservation high-water mark: every counter value below it may already be issued. */
    val counterHighWater: Long = 0L,
    /** Dead-air schedule next-fire (epoch ms), or null when never armed. Written by U5 only. */
    val deadAirNextFireAtMs: Long? = null,
    /**
     * Earliest epoch-ms at which provisioning may be attempted again, or null for "no deferral".
     *
     * **[R3] Written AHEAD of the attempt, not in response to one.**
     * `DecoyAccountProvisioner.reserveBackoff` mutates AND flushes it before a single byte of relay
     * contact, on every attempt that gets past the deferral check — the durable record that this
     * vault is about to spend from a rate-limit bucket shared by every client worldwide, so that a
     * crash mid-attempt cannot make the next unlock walk straight back into it. (Round 1 wrote it
     * only on a 429, which is what this comment used to say; that left a vault at absolute capacity
     * registering afresh on every unlock, forever.)
     *
     * It is retired by exactly two things: a successful commit, which clears it in the same mutate
     * that stores the credentials, and a failure that provably spent nothing — a challenge fetch
     * that never reached `register`. **A failure from the registration onwards leaves it standing**,
     * whatever the cause, because a `register` that threw may still have created the account.
     */
    val provisionNotBeforeMs: Long? = null,
) {
    /** True only when a usable synthetic account exists — see the presence-vs-readiness note. */
    val isProvisioned: Boolean
        get() = accountId != null && identityKeyPair != null

    /**
     * True when nothing here is worth persisting, so the section may be OMITTED entirely.
     * Keeping the section absent for such a state is what lets a vault that never provisions
     * stay byte-compatible with a 0.9.x reader (which rejects tag 0x06 as corruption).
     */
    val isEmpty: Boolean
        get() = accountId == null && identityKeyPair == null && accessToken == null &&
            refreshToken == null && counterHighWater == 0L && deadAirNextFireAtMs == null &&
            provisionNotBeforeMs == null

    /** Copy-with, mirroring a data class (which a ByteArray field makes unsafe to generate). */
    fun copy(
        accountId: String? = this.accountId,
        identityKeyPair: ByteArray? = this.identityKeyPair,
        accessToken: String? = this.accessToken,
        refreshToken: String? = this.refreshToken,
        counterHighWater: Long = this.counterHighWater,
        deadAirNextFireAtMs: Long? = this.deadAirNextFireAtMs,
        provisionNotBeforeMs: Long? = this.provisionNotBeforeMs,
    ): DecoyState = DecoyState(
        accountId = accountId,
        identityKeyPair = identityKeyPair,
        accessToken = accessToken,
        refreshToken = refreshToken,
        counterHighWater = counterHighWater,
        deadAirNextFireAtMs = deadAirNextFireAtMs,
        provisionNotBeforeMs = provisionNotBeforeMs,
    )

    /** Zero the private key bytes this holder owns. Called by [VaultState.wipe]. */
    fun wipe() {
        identityKeyPair?.let { wipe(it) }
    }

    // A ByteArray field makes a generated equals/hashCode reference-based, which is a trap in
    // tests (the same reason RegistrationPow.Proof overrides them). Compare by content.
    override fun equals(other: Any?): Boolean =
        other is DecoyState &&
            accountId == other.accountId &&
            identityKeyPair.contentEquals(other.identityKeyPair) &&
            accessToken == other.accessToken &&
            refreshToken == other.refreshToken &&
            counterHighWater == other.counterHighWater &&
            deadAirNextFireAtMs == other.deadAirNextFireAtMs &&
            provisionNotBeforeMs == other.provisionNotBeforeMs

    override fun hashCode(): Int {
        var result = accountId?.hashCode() ?: 0
        result = 31 * result + identityKeyPair.contentHashCode()
        result = 31 * result + (accessToken?.hashCode() ?: 0)
        result = 31 * result + (refreshToken?.hashCode() ?: 0)
        result = 31 * result + counterHighWater.hashCode()
        result = 31 * result + (deadAirNextFireAtMs?.hashCode() ?: 0)
        result = 31 * result + (provisionNotBeforeMs?.hashCode() ?: 0)
        return result
    }

    /** Never render secrets. Mirrors the "nothing here is ever logged" discipline. */
    override fun toString(): String = "DecoyState(provisioned=$isProvisioned)"
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
 *  - `0x06` **decoy**: cover-traffic state (see [encodeDecoy]). NULLABLE — the tag is OMITTED
 *    entirely when the vault has no decoy state, which is the valid initial condition.
 *  An UNKNOWN tag on decode THROWS (strict v1 — a future format change owns its own
 *  migration behind a version bump; there is no forward-tolerant skip).
 *
 * ⚠️ FORMAT BREAK (0.10.0-beta, ruled and accepted). `0x06` did not exist before 0.10.0, and
 * strict-v1 means a 0.9.x build opening a vault that carries it rejects the whole state as
 * corruption — `SessionContainer` decodes before it builds anything, so that surfaces as a
 * refused unlock. This is a ONE-WAY format bump, disclosed in the release notes exactly as
 * 0.9.1's fresh-install-only decision was. **Do NOT "fix" this by making the decoder tolerant
 * of unknown high tags** — the strictness is deliberate, the ruling considered and rejected
 * that option (it cannot rescue builds already in the field), and the mitigation that IS in
 * force is that the section is omitted entirely while there is nothing to record.
 *
 * **[R3] What that mitigation is worth, stated exactly.** The tag appears the moment a vault has
 * anything to record — which, since `DecoyAccountProvisioner` writes its back-off before contacting
 * the relay, is as soon as a vault **sets up cover traffic**, not as late as its first sent decoy.
 * An attempt that fails before spending a registration retires that deferral, and the holder then
 * encodes as empty and is omitted again, so a vault whose only brush with cover traffic was a
 * failed offline attempt keeps its 0.9.x readability. A vault that has never used cover traffic at
 * all never carries the tag. That is the honest trigger, and it is the one spec §4.1 states.
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
    private const val TAG_DECOY = 0x06

    /** A null nullable-string is written as this sentinel length (see [encodeAuth]). */
    private const val NULL_LEN = -1

    /**
     * Worst-case bound on what [TAG_DECOY] may add to the ENCODED (deflated) state.
     *
     * Measured, not guessed — `VaultDecoySectionTest` builds a maximum-length section (36-char
     * account UUID, 65-byte `IdentityKeyPair.serialize()`, an RS256 access JWT, a 43-char
     * refresh token, three fixed-width integers) and asserts the real encode-size delta stays
     * under this. It exists to catch a FUTURE field addition, not because the section is
     * tight: [MAX_PAYLOAD_CONTENT_BYTES] is ~262 KB and a realistic full state is single-digit
     * KB. It matters because [VaultRuntime.capacityExceeded] fail-closes `flushBeforeAck`, so
     * overflowing the region is a durability failure, not a cosmetic one.
     */
    const val DECOY_SECTION_BUDGET_BYTES: Int = 1024

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
            // 0x06 — nullable: the tag is omitted entirely when there is no decoy state, AND
            // when the holder is present but carries nothing worth persisting. Omitting an
            // empty holder is not tidiness: while the section is absent the payload stays
            // readable by a 0.9.x build (see the format-break note in the class kdoc), so a
            // vault that never sets up cover traffic never pays for the break — and one whose
            // only attempt failed before spending anything gets that readability back, because
            // retiring the deferral empties the holder and lands here again. [R3]
            state.decoy?.takeUnless { it.isEmpty }?.let { writeSection(out, TAG_DECOY, encodeDecoy(it)) }
            return out.toByteArray()
        } finally {
            // The whole plaintext (raw records) lived here — zero it. The exact-size result
            // is the caller's `plain`, wiped in encode's finally.
            out.wipe()
        }
    }

    private fun parsePlaintext(plain: ByteArray): VaultState =
        parsePlaintext(plain, PartialDecode())

    /**
     * The decoder proper, with the secrets it has decoded SO FAR held in a caller-supplied
     * [PartialDecode] rather than in locals.
     *
     * That is the whole reason for the seam, and it is not a test hook: it is the only way the
     * decode-failure wipe can be *observed*. The buffers a failing parse strands are allocated
     * inside this function and are unreachable from any caller, so a test that merely decodes a
     * malformed payload can assert the throw and nothing more — which is precisely the
     * non-discriminating shape that leaves a production cleanup call unpinned (deleting it keeps
     * every such test green). Handing the accumulator in makes the stranded material the caller's
     * to inspect, so a test can assert the zeroing through the REAL decoder path instead of by
     * calling the cleanup directly and hoping production still calls it too.
     */
    internal fun parsePlaintext(plain: ByteArray, partial: PartialDecode): VaultState {
        var rosterJson: String? = null
        var tombstonesJson: String? = null
        var settings: VaultScopedSettings? = null
        var auth: AuthState? = null

        // v1 emits each tag AT MOST once; a repeat is a noncanonical/malformed payload. Reject it
        // — otherwise the second assignment silently replaces the first decoded value, and for
        // TAG_SIGNAL the first map's key material becomes both unreachable AND un-wiped (the
        // failure-wipe below only covers the FINAL `signal` local).
        val seenTags = HashSet<Int>()
        try {
            // INSIDE the try, header included: the contract of this seam is that a throw from it
            // wipes whatever [partial] holds, and a version check outside the try would break that
            // for the very first bytes it reads — a truncated or wrong-version payload handed an
            // accumulator that already carried key material would strand it un-zeroed. [R3]
            val r = Reader(plain)
            val version = r.u8()
            require(version == VERSION) { "unsupported vault state version: $version" }

            while (r.hasRemaining()) {
                val tag = r.u8()
                val len = r.i32()
                require(len >= 0) { "negative section length" }
                val body = r.bytes(len)
                try {
                    // Reject a duplicate INSIDE this try, so `body` is wiped by the finally and the
                    // outer catch wipes any already-decoded partial signal map before the rethrow.
                    if (!seenTags.add(tag)) {
                        throw IllegalArgumentException("duplicate section tag: $tag")
                    }
                    when (tag) {
                        TAG_SIGNAL -> partial.signal = decodeSignal(body)
                        TAG_ROSTER -> rosterJson = String(body, Charsets.UTF_8)
                        TAG_TOMBSTONES -> tombstonesJson = String(body, Charsets.UTF_8)
                        TAG_SETTINGS -> settings = decodeSettings(body)
                        TAG_AUTH -> auth = decodeAuth(body)
                        TAG_DECOY -> partial.decoy = decodeDecoy(body)
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
            val decodedSignal = requireNotNull(partial.signal) { "missing signal section" }
            val decodedSettings = requireNotNull(settings) { "missing settings section" }
            val decodedAuth = requireNotNull(auth) { "missing auth section" }

            return VaultState(
                signalRecords = decodedSignal,
                rosterJson = rosterJson,
                tombstonesJson = tombstonesJson,
                settings = decodedSettings,
                auth = decodedAuth,
                decoy = partial.decoy,
            )
        } catch (t: Throwable) {
            partial.wipe()
            throw t
        }
    }

    /**
     * The secret-bearing material a [parsePlaintext] has decoded so far, and its cleanup.
     *
     * A malformed/unknown later section (or a missing-mandatory `require`) can throw AFTER
     * [decodeSignal] already copied raw key material into the record map, and after [decodeDecoy]
     * copied a PRIVATE identity key out of the (about-to-be-wiped) section body into an array this
     * holder owns. A throw means no [VaultState] is ever constructed, so [VaultState.wipe] can
     * never reach either of them — [wipe] is their only cleanup path.
     *
     * On the SUCCESS path ownership passes to the returned [VaultState] (the same map and the same
     * holder, not copies), so this must not be wiped then — only from the failure catch.
     */
    internal class PartialDecode {
        var signal: MutableMap<String, ByteArray>? = null
        var decoy: DecoyState? = null

        /** Zero everything decoded so far. Safe on a decode that got nowhere. */
        fun wipe() {
            signal?.let { records ->
                for (value in records.values) wipe(value)
                records.clear()
            }
            decoy?.wipe()
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
        try {
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
        } catch (t: Throwable) {
            // A truncated/invalid later entry (or trailing-bytes check) throws BEFORE we return,
            // so parsePlaintext never assigns `signal` and its outer catch cannot reach these
            // arrays. Zero every record value accumulated so far, then rethrow — no partial key
            // material strands un-wiped in heap. Complementary to parsePlaintext's outer catch,
            // which covers the case where decodeSignal SUCCEEDS but a LATER section throws.
            for (v in map.values) wipe(v)
            map.clear()
            throw t
        }
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

    // ── 0x06 decoy (cover-traffic state) ────────────────────────────────────────

    /**
     * Fixed field order:
     * `accountId ‖ identityKeyPair ‖ accessToken ‖ refreshToken` (four nullable
     * length-prefixed blobs, [NULL_LEN] for null) `‖ counterHighWater(8 BE)`
     * `‖ deadAirNextFire(present(1) ‖ 8 BE) ‖ provisionNotBefore(present(1) ‖ 8 BE)`.
     *
     * The absent-long form mirrors [encodeSettings]'s nullable-ttl encoding (a present flag
     * plus a fixed-width value) rather than inventing a sentinel, so an absent value and a
     * legitimately-zero one stay distinguishable.
     */
    private fun encodeDecoy(d: DecoyState): ByteArray {
        // Strict v1 refuses to PRODUCE what it refuses to READ. [decodeDecoy] rejects a negative
        // high-water mark (it would hand out negative message_numbers — see the note there), and an
        // encoder that happily emits one writes an image its own decoder calls corrupt: the vault
        // would seal, and the next unlock would fail. Unreachable from any writer in this codebase,
        // which is exactly why it must be an assertion and not a silent clamp. [R3]
        require(d.counterHighWater >= 0L) { "negative counter high-water mark in decoy section" }
        val out = WipeableBuffer(128)
        try {
            writeNullableString(out, d.accountId)
            writeNullableBytes(out, d.identityKeyPair)
            writeNullableString(out, d.accessToken)
            writeNullableString(out, d.refreshToken)
            writeLong(out, d.counterHighWater)
            writeNullableLong(out, d.deadAirNextFireAtMs)
            writeNullableLong(out, d.provisionNotBeforeMs)
            return out.toByteArray()
        } finally {
            // out held the identity PRIVATE key + the token bytes — zero it. The exact-size
            // result is the decoy section body, wiped by writeSection.
            out.wipe()
        }
    }

    private fun decodeDecoy(body: ByteArray): DecoyState {
        val r = Reader(body)
        val accountId = readNullableString(r)
        // The identity keypair is PRIVATE key material. On any throw AFTER this read (a
        // truncated later field, trailing bytes) nothing else can reach the array — the
        // DecoyState is not constructed, so neither VaultState.wipe() nor parsePlaintext's
        // catch sees it — so zero it here before rethrowing.
        val identityKeyPair = readNullableBytes(r)
        try {
            val decoded = DecoyState(
                accountId = accountId,
                identityKeyPair = identityKeyPair,
                accessToken = readNullableString(r),
                refreshToken = readNullableString(r),
                counterHighWater = r.i64(),
                deadAirNextFireAtMs = readNullableLong(r),
                provisionNotBeforeMs = readNullableLong(r),
            )
            // A NEGATIVE mark is not a smaller number, it is a different meaning: the invariant is
            // "every value strictly below this may already have been issued", and the allocator
            // reserves `mark + blockSize` and issues from `mark` upward. A negative mark therefore
            // hands out negative `message_number`s — a value no real ratchet produces, i.e. exactly
            // the classifier the counter discipline exists to avoid — and it is unreachable from
            // this encoder, so it can only come from a crafted or corrupt payload.
            require(decoded.counterHighWater >= 0L) {
                "negative counter high-water mark in decoy section"
            }
            require(!r.hasRemaining()) { "trailing bytes in decoy section" }
            return decoded
        } catch (t: Throwable) {
            identityKeyPair?.let { wipe(it) }
            throw t
        }
    }

    /** A nullable string as `len(4 BE)`; [NULL_LEN] (-1) means null, else utf8 bytes follow. */
    private fun writeNullableString(out: WipeableBuffer, s: String?) {
        if (s == null) {
            writeInt(out, NULL_LEN)
            return
        }
        // `bytes` is a fresh copy of token material (the source String is itself un-wipeable).
        val bytes = s.toByteArray(Charsets.UTF_8)
        try {
            writeInt(out, bytes.size)
            out.write(bytes)
        } finally {
            // Zero this transient on EVERY path — a throw mid-write (e.g. OOM while `out` grows)
            // must not strand a token copy un-wiped.
            wipe(bytes)
        }
    }

    private fun readNullableString(r: Reader): String? {
        val len = r.i32()
        if (len == NULL_LEN) return null
        require(len >= 0) { "invalid nullable-string length: $len" }
        // `bytes` is a fresh copy of decoded secret material (account id / access / refresh token);
        // the String constructor copies it out, so zero this transient in `finally` rather than
        // abandon it un-wiped. (Roster/tombstones Strings decode from `body`, already wiped in
        // parsePlaintext's finally — this covers the only OTHER decoded-secret-to-String path.)
        val bytes = r.bytes(len)
        try {
            return String(bytes, Charsets.UTF_8)
        } finally {
            wipe(bytes)
        }
    }

    /**
     * A nullable byte blob as `len(4 BE)`; [NULL_LEN] (-1) means null, else the bytes follow.
     * Unlike [writeNullableString] this does NOT wipe its input — the caller still owns the
     * array (it is the live state's, e.g. the decoy identity keypair), exactly as
     * [encodeSignal] treats record values.
     */
    private fun writeNullableBytes(out: WipeableBuffer, bytes: ByteArray?) {
        if (bytes == null) {
            writeInt(out, NULL_LEN)
            return
        }
        writeInt(out, bytes.size)
        out.write(bytes)
    }

    /** Inverse of [writeNullableBytes]. The returned array is a fresh copy the caller owns. */
    private fun readNullableBytes(r: Reader): ByteArray? {
        val len = r.i32()
        if (len == NULL_LEN) return null
        require(len >= 0) { "invalid nullable-bytes length: $len" }
        return r.bytes(len)
    }

    /** A nullable long as `present(1) ‖ value(8 BE)`; the value is 0 when absent. */
    private fun writeNullableLong(out: WipeableBuffer, value: Long?) {
        out.write(if (value == null) 0 else 1)
        writeLong(out, value ?: 0L)
    }

    /**
     * Inverse of [writeNullableLong], and CANONICAL: the presence byte must be exactly 0 or 1, and
     * an absent value must carry the zero this encoder writes.
     *
     * Strict v1 means one payload per state, not merely "one state per payload". Accepting any
     * nonzero byte as truthy, or arbitrary bytes behind an absent flag, would make decode→encode
     * change accepted bytes — a second, noncanonical spelling of the same state that a
     * determinism claim cannot cover and that a byte-level equality test cannot detect.
     */
    private fun readNullableLong(r: Reader): Long? {
        val present = r.u8()
        require(present == 0 || present == 1) { "noncanonical nullable-long presence flag: $present" }
        val value = r.i64()
        if (present == 0) {
            require(value == 0L) { "noncanonical absent nullable-long carries a value" }
            return null
        }
        return value
    }

    // ── section framing helpers ──────────────────────────────────────────────────

    private fun writeSection(out: WipeableBuffer, tag: Int, body: ByteArray) {
        // The body carried a copy of section secrets into `out`; wipe the transient copy on EVERY
        // path — a throw mid-write (e.g. OOM while `out` grows) must not strand it un-wiped.
        try {
            out.write(tag)
            writeInt(out, body.size)
            out.write(body)
        } finally {
            wipe(body)
        }
    }

    private fun writeInt(out: WipeableBuffer, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeLong(out: WipeableBuffer, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value ushr shift) and 0xff).toInt())
        }
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

        fun i64(): Long {
            require(pos + 8 <= a.size) { "unexpected end of vault state" }
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (a[pos + i].toLong() and 0xff)
            pos += 8
            return v
        }

        /** Copy the next [n] bytes into a fresh array. */
        fun bytes(n: Int): ByteArray {
            require(n >= 0) { "negative length: $n" }
            // `n <= a.size - pos`, NOT `pos + n <= a.size`: `n` is read from the (untrusted)
            // stream, so `pos + n` could overflow to a negative Int and pass the check; the
            // right-hand form never overflows (pos <= a.size, so a.size - pos is a non-negative
            // bound). Fixed-width reads (u8/u16/i32) use a constant N and cannot overflow.
            require(n <= a.size - pos) { "unexpected end of vault state" }
            val out = a.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }
}
