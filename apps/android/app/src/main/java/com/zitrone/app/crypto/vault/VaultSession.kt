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
 *  3. On [close] (lock / teardown / background) force a synchronous [flushNow],
 *     then wipe the in-memory vault key and payload.
 *
 * THREADING. All public methods are thread-safe. [update] will run on a future
 * confined dispatcher; [flushNow] / [close] on the main/lock thread; the debounce
 * job on [scope]. They may race, so every state transition is atomic under a
 * private monitor ([lock]). The monitor is never held across a suspension: the
 * debounce delay happens OUTSIDE the lock, and only the (non-suspending) seal +
 * splice + persist run inside it. Cancelling the pending job inside a forced
 * flush is what prevents a double-persist.
 *
 * This is an isolated runtime unit: it is deliberately NOT wired into any real
 * store, unlock UI, or coordinator — that is a later sub-phase.
 */
class VaultSession(
    private val scope: CoroutineScope,
    private val ops: VaultSodiumOps,
    initialImage: ByteArray,
    initialPayload: ByteArray,
    private val vaultKey: ByteArray,
    private val slotIndex: Int,
    private val persist: (ByteArray) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cooldownMs: Long = 2_000L,
) {
    /** Monitor guarding every field below. Never held across a suspension. */
    private val lock = Any()

    /** The current in-memory keystore plaintext. Owned here; wiped on replace/close. */
    private var payload: ByteArray = initialPayload.copyOf()

    /**
     * The last image we know is on disk. Only THIS slot's payload region ever
     * changes between reseals; every other region is spliced through unchanged.
     */
    private var image: ByteArray = initialImage.copyOf()

    /** True when [payload] has changed since the last successful persist. */
    private var dirty: Boolean = false

    /** Wall-clock of the FIRST unflushed mutation — the coalescing ceiling's origin. */
    private var firstDirtyAt: Long? = null

    /** The single armed debounce job, or null when none is pending. */
    private var pending: Job? = null

    /** Once true, [update] / [flushNow] are no-ops and [read] throws. */
    private var closed: Boolean = false

    /**
     * A COPY of the current in-memory payload. Never hands out the live buffer, so
     * a caller mutating the result cannot corrupt session state. Throws once closed.
     */
    fun read(): ByteArray = synchronized(lock) {
        check(!closed) { "vault session is closed" }
        payload.copyOf()
    }

    /**
     * Replace the in-memory payload, mark dirty, and — if none is already armed —
     * schedule ONE reseal at `firstDirtyAt + cooldownMs`. Non-blocking. A no-op
     * once closed.
     *
     * Rejects an over-capacity payload BEFORE mutating any state (the region never
     * grows — a larger real payload would leak that a vault lives here and how
     * big it is), mirroring [sealPayload]'s over-capacity throw. The previous
     * payload buffer is wiped on replace.
     */
    fun update(newPayload: ByteArray) {
        // Reject up-front, before touching state: the same bound sealPayload
        // enforces (a 4-byte big-endian length prefix precedes the content inside
        // the fixed plaintext capacity). Checked here so a rejected update leaves
        // the payload unchanged and un-dirtied, never grows the region, and never
        // defers the throw to a later flush.
        require(newPayload.size <= MAX_PAYLOAD_CONTENT_BYTES) {
            "content exceeds vault slot capacity"
        }
        synchronized(lock) {
            if (closed) return
            val previous = payload
            payload = newPayload.copyOf()
            wipe(previous)
            dirty = true
            if (firstDirtyAt == null) firstDirtyAt = clock()
            if (pending == null) armLocked()
        }
    }

    /**
     * SYNCHRONOUS, durable reseal. If dirty: seal the current payload, splice it
     * into the image at this slot (every other region byte-unchanged), and hand
     * the new image to [persist] — returning only after [persist] returns. Cancels
     * the pending debounce job (so it cannot double-persist) and clears dirty
     * state. Idempotent: a no-op when clean or closed.
     */
    fun flushNow() = synchronized(lock) { flushLocked() }

    /**
     * Force a final [flushNow], cancel any pending work, then wipe the vault key
     * and the in-memory payload. After this, [update] / [flushNow] are no-ops and
     * [read] throws. Idempotent.
     */
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        flushLocked()
        pending?.cancel()
        pending = null
        closed = true
        wipe(vaultKey)
        wipe(payload)
        wipe(image)
    }

    /** Arm exactly one debounce job at the first-dirty ceiling. Caller holds [lock]. */
    private fun armLocked() {
        val target = (firstDirtyAt ?: clock()) + cooldownMs
        pending = scope.launch {
            // Residual-delay pattern (mirrors MessageRepository.scheduleTtl): the
            // wait is computed from the wall clock so the ceiling stays anchored to
            // firstDirtyAt no matter when this body is first dispatched. The delay
            // is OUTSIDE the monitor — the lock is only taken for the atomic reseal.
            val wait = target - clock()
            if (wait > 0) delay(wait)
            synchronized(lock) {
                // Re-check under the lock: a forced flushNow may have already
                // resealed (dirty == false) or the session may have closed.
                if (!closed && dirty) flushLocked()
            }
        }
    }

    /** Seal + splice + persist + clear, atomically. Caller holds [lock]. */
    private fun flushLocked() {
        if (closed || !dirty) return
        val sealed = sealPayload(vaultKey, payload, ops)
        val next = spliceImagePayload(image, slotIndex, sealed)
        image = next
        dirty = false
        firstDirtyAt = null
        pending?.cancel()
        pending = null
        // Inside the monitor so persists are serialized and cannot interleave with
        // a concurrent update's state transition. persist() is a plain (non-suspend)
        // sink, so holding the lock across it does not violate the never-suspend
        // invariant.
        persist(next)
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
