// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The in-memory runtime for a single unlocked slot.
 *
 * A slot's keystore — identity keys, prekeys, Double Ratchet session state,
 * roster, auth, settings — lives on disk as ONE fixed-size sealed payload region
 * inside the vault image (there is deliberately no on-disk evidence of a second
 * vault, so the whole keystore is a single opaque region, never a growing file).
 * While unlocked it lives here in memory as the current plaintext payload, and it
 * is re-sealed as a WHOLE payload and spliced back into the image on flush.
 *
 * The flush policy bounds how much Double Ratchet state a crash can lose:
 *
 *  1. **flush-before-ack, window = 0 (correctness).** The future receive path
 *     MUST force a synchronous, durable reseal BEFORE it acks an inbound message.
 *     [flushNow] reseals + persists and returns only once the bytes are handed to
 *     the persist sink. An un-acked inbound message stays on the relay and is
 *     redelivered on reconnect, so anything a crash drops BEFORE ack is recovered
 *     — zero permanent loss.
 *
 *  2. **≤ [cooldownMs] coalescing CEILING (max-wait, NOT trailing debounce).**
 *     For coalesced, non-forced mutations the reseal fires at
 *     `firstDirtyAt + cooldownMs`, measured from the FIRST unflushed mutation.
 *     A burst of rapid [update]s therefore still flushes within [cooldownMs] of
 *     the first one, and a single flush covers the whole burst. A trailing /
 *     reset-on-each-update debounce would be WRONG here: it could starve
 *     indefinitely under a steady stream of updates; the ceiling must hold from
 *     first-dirty.
 *
 *  3. On [close] (lock / teardown / background) force a synchronous final reseal,
 *     then wipe the in-memory vault key and payload.
 *
 * THREADING. All public methods are thread-safe. Two monitors:
 *
 *  - [stateLock] guards the in-memory state (payload, image, dirty flags, the
 *    dirty [version], [pending], [closed]). It is held ONLY for fast, non-blocking
 *    transitions — a cheap payload + key snapshot and version capture — NEVER
 *    across the reseal, [persist], or a suspension.
 *  - [flushLock] serializes a whole reseal → persist → commit cycle so two flushes
 *    cannot interleave their disk writes (which could land a stale image and lose
 *    an update). Lock ordering is ALWAYS [flushLock] then [stateLock], never the
 *    reverse.
 *
 * Both the AES-GCM reseal (CPU-heavy, ~256 KiB) and [persist] (a blocking,
 * caller-provided alien sink) run OUTSIDE [stateLock] — under [flushLock], on
 * private copies snapshotted under [stateLock] and wiped right after — so a
 * concurrent [read] / [update] never blocks on crypto or disk I/O (no main-thread
 * stutter / ANR). A mutation that lands mid-flush (including a reentrant call back
 * into the session from the sink) cannot corrupt it: a monotonically increasing
 * [version] counter, captured at seal time, detects it at commit, keeps the
 * session dirty rather than falsely marking it clean, and the flushing caller
 * re-arms the ceiling so the late mutation still flushes.
 *
 * This is an isolated runtime unit: it is deliberately NOT wired into any real
 * store, unlock UI, or coordinator — that is a later sub-phase.
 */
class VaultSession(
    private val scope: CoroutineScope,
    private val ops: VaultSodiumOps,
    initialImage: ByteArray,
    initialPayload: ByteArray,
    initialVaultKey: ByteArray,
    private val slotIndex: Int,
    private val persist: (ByteArray) -> Unit,
    /**
     * Time source for the coalescing ceiling. It measures ELAPSED durations only,
     * so it must be MONOTONIC. The default is `System.nanoTime()` (in ms), which is
     * monotonic on both the JVM and Android and cannot jump backward on an NTP /
     * manual clock change. Production may inject `SystemClock.elapsedRealtime()` to
     * also advance across device deep-sleep; tests inject virtual time.
     */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cooldownMs: Long = 2_000L,
) {
    /** Monitor for the in-memory state. Held only for fast transitions; never across I/O. */
    private val stateLock = Any()

    /** Serializes whole reseal→persist→commit cycles. Outer lock (before [stateLock]). */
    private val flushLock = Any()

    /** The current in-memory keystore plaintext. Owned here; wiped on replace/close. */
    private var payload: ByteArray = initialPayload.copyOf()

    /**
     * The last image we know is on disk. Only THIS slot's payload region ever
     * changes between reseals; every other region is spliced through unchanged.
     */
    private var image: ByteArray = initialImage.copyOf()

    /**
     * The Argon2id-derived slot key that seals this payload. A private COPY: the
     * session owns its key material and wipes it on [close]. Copying means a caller
     * that wipes its own VaultOpen after construction cannot zero the key out from
     * under an active session.
     */
    private val vaultKey: ByteArray = initialVaultKey.copyOf()

    /** True when [payload] has changed since the last successful persist. */
    private var dirty: Boolean = false

    /**
     * Monotonically increasing on every [update]. A flush captures this at seal
     * time; if it has advanced by the time the (outside-the-lock) persist returns,
     * a mutation slipped in during the write, so the flush must NOT mark the session
     * clean. This is what makes calling [persist] outside [stateLock] safe.
     */
    private var version: Long = 0

    /** Elapsed-clock reading of the FIRST unflushed mutation — the ceiling's origin. */
    private var firstDirtyAt: Long? = null

    /** The single armed debounce job, or null when none is pending. */
    private var pending: Job? = null

    /** Once true, [update] / [flushNow] are no-ops and [read] throws. */
    private var closed: Boolean = false

    init {
        // Take ownership of the unlocked secrets: the copies above are ours, so
        // wipe the caller's originals now. The VaultOpen the caller discards after
        // construction then holds no live key or plaintext. initialImage is
        // ciphertext (the sealed image) and not secret — left intact.
        wipe(initialVaultKey)
        wipe(initialPayload)
    }

    /**
     * A COPY of the current in-memory payload. Never hands out the live buffer, so
     * a caller mutating the result cannot corrupt session state. Throws once closed.
     */
    fun read(): ByteArray = synchronized(stateLock) {
        check(!closed) { "vault session is closed" }
        payload.copyOf()
    }

    /**
     * Replace the in-memory payload, mark dirty, and — unless one is already armed
     * and still pending — schedule ONE reseal at `firstDirtyAt + cooldownMs`.
     * Non-blocking. A no-op once closed.
     *
     * Rejects an over-capacity payload BEFORE mutating any state (the region never
     * grows — a larger real payload would leak that a vault lives here and how
     * big it is), mirroring [sealPayload]'s over-capacity throw. The previous
     * payload buffer is wiped on replace.
     */
    fun update(newPayload: ByteArray) {
        synchronized(stateLock) {
            // A closed session is inert — no-op even for an over-capacity input
            // (checked after the closed-gate so close() makes EVERY update a
            // silent no-op, never a throw).
            if (closed) return
            // Reject before touching state: the same bound sealPayload enforces
            // (a 4-byte big-endian length prefix precedes the content inside the
            // fixed plaintext capacity). Checked here so a rejected update leaves
            // the payload unchanged and un-dirtied, never grows the region, and
            // never defers the throw to a later flush.
            require(newPayload.size <= MAX_PAYLOAD_CONTENT_BYTES) {
                "content exceeds vault slot capacity"
            }
            val previous = payload
            payload = newPayload.copyOf()
            wipe(previous)
            dirty = true
            version++
            if (firstDirtyAt == null) firstDirtyAt = clock()
            // Re-arm when nothing is scheduled OR the last job already finished /
            // was cancelled (e.g. its scope was torn down mid-delay) — a completed
            // or cancelled job left in `pending` must not block the next ceiling.
            if (pending?.isActive != true) armLocked()
        }
    }

    /**
     * SYNCHRONOUS, durable reseal. If dirty, seals the current payload, splices it
     * into the image at this slot (every other region byte-unchanged), and hands
     * the new image to [persist] — returning only after [persist] returns. Then
     * cancels the pending debounce job so it cannot fire a redundant reseal. If
     * [persist] throws, the session stays dirty and the throw propagates (a
     * flush-before-ack caller must NOT ack). Idempotent: a no-op when clean/closed.
     */
    fun flushNow() {
        doFlush()
        synchronized(stateLock) {
            if (closed) return
            if (dirty) {
                // A mutation landed during the persist (e.g. a reentrant update): keep
                // it scheduled rather than cancelling its ceiling. Re-arm only if the
                // job isn't already pending.
                if (pending?.isActive != true) armLocked()
            } else {
                pending?.cancel()
                pending = null
            }
        }
    }

    /**
     * Force a final reseal, cancel any pending work, then wipe the vault key and
     * the in-memory payload — the wipes run even if the final reseal throws, so
     * teardown never leaks key material. The ciphertext image is left intact (a
     * retaining persist sink shares that array). After this, [update] / [flushNow]
     * are no-ops and [read] throws. Idempotent.
     */
    fun close() {
        try {
            // Best-effort final reseal. If it throws (persist failure) we still fall
            // through to wipe every secret — teardown must never leak key material,
            // even when the last write could not land. doFlush() takes flushLock
            // then stateLock internally and fully releases both before the finally
            // runs, so the finally's stateLock acquisition never nests under either.
            doFlush()
        } finally {
            synchronized(stateLock) {
                if (!closed) {
                    pending?.cancel()
                    pending = null
                    closed = true
                    wipe(vaultKey)
                    wipe(payload)
                    // Do NOT wipe [image]: it is ciphertext already handed to
                    // [persist], and a sink retaining that array would be corrupted.
                }
            }
        }
    }

    /** Arm exactly one debounce job at the first-dirty ceiling. Caller holds [stateLock]. */
    private fun armLocked() {
        val target = (firstDirtyAt ?: clock()) + cooldownMs
        val job = scope.launch {
            // Residual-delay pattern (mirrors MessageRepository.scheduleTtl): the
            // wait is computed from the clock so the ceiling stays anchored to
            // firstDirtyAt no matter when this body is first dispatched.
            val wait = target - clock()
            if (wait > 0) delay(wait)
            // Reseal OUTSIDE any lock this coroutine holds. A persist failure must not
            // crash the scope, so capture it rather than let it propagate.
            val result = runCatching { doFlush() }
            synchronized(stateLock) {
                // Deregister self (identity-checked, so a newer job is never cleared),
                // and re-arm ONLY on a SUCCESSFUL flush that left us dirty — a mutation
                // that landed mid-persist. Never re-arm after a failure: firstDirtyAt
                // is in the past, so a blind re-arm would hot-loop retrying a failing
                // disk. On failure the next update()/flushNow() retries instead.
                if (pending === coroutineContext[Job]) {
                    if (result.isSuccess && dirty && !closed) armLocked() else pending = null
                }
            }
        }
        pending = job
    }

    /**
     * One reseal → persist → commit cycle, serialized by [flushLock]. Seals the
     * current payload under [stateLock] and captures the dirty [version], releases
     * [stateLock], calls the blocking [persist] OUTSIDE it, then re-takes
     * [stateLock] to commit. Load-bearing for flush-before-ack: [persist] runs
     * FIRST and the session is marked clean ONLY after it returns AND only if no
     * mutation slipped in during the write (version unchanged); a [persist] throw
     * leaves the session dirty and propagates, so a forced caller does not ack an
     * inbound message whose ratchet state never reached disk. A no-op when
     * clean/closed. Does NOT touch [pending] — callers own that.
     */
    private fun doFlush() {
        synchronized(flushLock) {
            val payloadCopy: ByteArray
            val vaultKeyCopy: ByteArray
            val imageRef: ByteArray
            val sealedVersion: Long
            synchronized(stateLock) {
                if (closed || !dirty) return
                // Only a cheap snapshot under the lock: copy the plaintext + key, grab
                // the current image reference and dirty version. The heavy seal runs
                // below, OUTSIDE the lock.
                payloadCopy = payload.copyOf()
                vaultKeyCopy = vaultKey.copyOf()
                imageRef = image
                sealedVersion = version
            }
            try {
                // Heavy AES-GCM reseal (256 KiB) + splice OUTSIDE stateLock, on private
                // copies, so a concurrent read()/update() never blocks on crypto (no
                // main-thread stutter / ANR). The copies are wiped in the finally, so
                // no extra plaintext or key material outlives the seal.
                val next = spliceImagePayload(imageRef, slotIndex, sealPayload(vaultKeyCopy, payloadCopy, ops))
                // Blocking disk write, still no stateLock held: a reentrant update()
                // from the sink just bumps `version`, detected at commit below.
                persist(next)
                synchronized(stateLock) {
                    if (closed) return
                    image = next
                    if (version == sealedVersion) {
                        // Nothing changed during seal+persist — the whole dirty batch
                        // is now durable.
                        dirty = false
                        firstDirtyAt = null
                    }
                    // else: a mutation landed mid-flush (incl. a reentrant update from
                    // the sink). Stay dirty so it is not lost; the caller (flushNow /
                    // the timer body) re-arms the ceiling for it. Cannot occur under
                    // single-worker confinement, where update/flush never overlap.
                }
            } finally {
                wipe(payloadCopy)
                wipe(vaultKeyCopy)
            }
        }
    }

    private companion object {
        /**
         * Mirrors VaultPayload's private LEN_PREFIX_BYTES: the padded plaintext is
         * `len(4 BE) ‖ content ‖ fill`, so the largest content that fits the fixed
         * region is [PAYLOAD_PLAINTEXT_BYTES] minus that 4-byte prefix. sealPayload
         * is the ultimate backstop (it throws on overflow); this up-front bound lets
         * [update] reject before mutating and never defers the throw to a flush.
         */
        const val PAYLOAD_LEN_PREFIX_BYTES = 4
        const val MAX_PAYLOAD_CONTENT_BYTES = PAYLOAD_PLAINTEXT_BYTES - PAYLOAD_LEN_PREFIX_BYTES
    }
}
