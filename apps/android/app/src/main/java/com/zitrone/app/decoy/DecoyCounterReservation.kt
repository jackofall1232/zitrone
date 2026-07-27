// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.vault.DecoySectionLock
import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Issues the monotonically advancing counter a cover-traffic envelope carries, spending from RAM
 * against a durably reserved block.
 *
 * ## Why a reservation, and not a durable write per counter
 *
 * The cover ciphertext is random bytes rather than real ratchet output, deliberately: a real
 * ratchet with the synthetic peer would make every decoy a durable `VaultState` mutation and
 * double the vault's reseal rate — battery, capacity pressure, and new write traffic through the
 * exact flush machinery 0.9.1 spent eleven review rounds hardening. **The one thing that must
 * still be durable is the counter**, because a `message_number` that resets or regresses is a tell
 * no real ratchet can produce.
 *
 * So: reserve [DEFAULT_BLOCK_SIZE] values at a time, make the new high-water mark DURABLE BEFORE
 * spending any of them, then spend from memory. One durable write per 64 envelopes.
 *
 * ## Durable means `flushBeforeAck`, NOT `mutate`
 *
 * `VaultRuntime.mutate` encodes the state and **schedules** a reseal (`VaultSession.update`
 * snapshots, marks dirty and returns — "no I/O here"); the bytes reach disk later, off-lock, when
 * the ≤2 s coalescing ceiling fires. A mutate that returned successfully therefore guarantees
 * *scheduled*, not *durable*, and a crash inside that window loses the mark. The synchronous
 * durable path is `VaultRuntime.flushBeforeAck()` → `VaultSession.flushNow()`, and **a throw from
 * it means the reservation never reached disk — so no value from it may be issued.** That is why
 * [reserveLocked] flushes between the mutate and the RAM cursor advance, and why a throw leaves the
 * cursor untouched.
 *
 * ## The invariant
 *
 * `counterHighWater` means **"every value strictly below this may already have been issued"**.
 * The durable write precedes the first spend of the block it covers, so an interruption at any
 * point leaves unspent reserved values behind and the next session resumes at the high-water mark:
 *
 *  - a crash **SKIPS** counter values — invisible, because a real Double Ratchet skips on any
 *    dropped message;
 *  - a crash can never **REGRESS** or reuse one — which is the property that matters.
 *
 * ## One allocator per runtime, structurally
 *
 * The RAM cursor is only safe because it is the ONLY cursor over its durable mark. Two allocators
 * over one runtime interleave `0, 64, 1` — a counter REGRESSION on the wire, the exact fingerprint
 * this class exists to prevent. A kdoc asking callers to build only one is not enforcement, so
 * there are two structural defences:
 *
 *  1. **The constructor is private.** [forRuntime] is the only way to obtain an allocator and it
 *     returns the SAME instance — hence the same [lock] and the same cursor — for a given runtime,
 *     so "two live allocators over one runtime" is unrepresentable rather than merely discouraged.
 *     Returning the existing allocator rather than throwing is deliberate: a throw would convert a
 *     caller's construction mistake into a crash on the cover-traffic path, whose whole contract is
 *     that it degrades silently.
 *  2. **A stale block is abandoned, not spent.** Every [next] re-reads the durable mark and
 *     discards its reservation unless the mark still equals the block's exclusive end. So even if
 *     some FUTURE writer advances or resets `counterHighWater` behind this allocator's back (U5, a
 *     re-provision after [com.zitrone.app.data.DecoyAuthStore.clearAccount]), the response is a
 *     fresh reservation — a skip — never a spend below the mark.
 *
 * ## Locking — the SECTION lock, not a private one [R2]
 *
 * [lock] is [DecoySectionLock] for this runtime: the SAME monitor `DecoyAuthStore` and
 * `DecoyAccountProvisioner` take. That is what makes defence 2 sound rather than decorative.
 * Round 1 shipped this class with a private lock, and review round 2 found the hole: the staleness
 * check reads the durable mark in one `runtime.read` and spends against it in a later call, so a
 * `clearAccount()` landing between the two resets the mark BEHIND a check that already passed —
 * the allocator then issues from a block that is no longer covered and can emit `1, 0`. A check
 * that is not atomic with the spend is not a check. Sharing the section monitor makes the whole
 * read-check-reserve-spend sequence exclusive against every other writer of the section.
 *
 * The order is `decoy section lock → runtime.stateLock → session locks → storage lock`. Nothing
 * takes the runtime lock and then this one, and this class is never reachable from a session
 * persist sink, so the order cannot invert. `flushBeforeAck` releases `stateLock` before its
 * disk-bound `flushNow`, so holding [lock] across it adds no new lock nesting — it only serializes
 * decoy-section writers against each other, which is exactly what it is for. The cost is one
 * disk-bound flush per 64 envelopes, held against a lock no other subsystem takes.
 */
class DecoyCounterReservation private constructor(
    private val runtime: VaultRuntime,
    private val blockSize: Int,
) {

    /** The SECTION monitor, shared with every other writer of `TAG_DECOY` — see the class kdoc. */
    private val lock = DecoySectionLock.forRuntime(runtime)

    /** Next value to issue. Meaningful only while `next < limit`. */
    private var next: Long = 0L

    /** Exclusive end of the reserved block. `next == limit` means "exhausted, reserve again". */
    private var limit: Long = 0L

    /**
     * The next counter value, reserving a fresh block durably when the current one is exhausted or
     * has gone stale.
     *
     * Throws whatever [VaultRuntime.mutate] throws when a reservation cannot be recorded (a closed
     * runtime, or a [com.zitrone.app.crypto.vault.VaultCapacityException]) and whatever
     * [VaultRuntime.flushBeforeAck] throws when it cannot be made DURABLE (an IO failure, a
     * [com.zitrone.app.crypto.vault.VaultImageException.NotDurable], a close racing the flush).
     * **A throw means no value was issued** — the caller must not send. This is deliberately NOT
     * swallowed: issuing a counter whose reservation never reached disk is the one failure that
     * could later produce a regression, so it must fail loudly to its caller rather than quietly.
     */
    fun next(): Long = lock.withLock {
        // Read the durable mark on EVERY call, not only when a reservation is due. Two jobs:
        //  - liveness — the reserved block lives in RAM, so without a runtime touch a torn-down
        //    session could keep issuing counters after its runtime closed ("must not survive
        //    teardown"); `read` throws once closed.
        //  - staleness — a block whose exclusive end is no longer the durable mark is not ours to
        //    spend (defence 2 in the class kdoc). Abandoning it SKIPS values; spending it could
        //    regress below a mark some other writer advanced. [R2] This read and the spend below
        //    are inside the SECTION lock, so no other writer of the section can move the mark
        //    between them — which is the whole reason the check means anything.
        // The cost is one uncontended lock acquisition per value, against a full AEAD reseal
        // plus a synchronous flush per 64.
        val durable = runtime.read { it.decoy?.counterHighWater ?: 0L }
        if (next >= limit || durable != limit) reserveLocked()
        next++
    }

    /**
     * Reserve the next block. Re-reads the durable high-water mark inside the mutate rather than
     * trusting the RAM cursor, so this is correct on the first call of a session (RAM starts at 0,
     * the vault may be far ahead) and stays correct if any other writer ever moves the mark.
     */
    private fun reserveLocked() {
        val reservedThrough = runtime.mutate { state ->
            val current = state.decoy?.counterHighWater ?: 0L
            require(current <= Long.MAX_VALUE - blockSize) { "counter reservation would overflow" }
            val advanced = current + blockSize
            state.decoy = (state.decoy ?: DecoyState()).copy(counterHighWater = advanced)
            current to advanced
        }
        // `mutate` only SCHEDULED that mark; this is what makes it durable, and its throw means the
        // block never reached disk. Between the two calls the mark is live-but-not-durable, which
        // is why the RAM cursor is still untouched here.
        runtime.flushBeforeAck()
        // Only AFTER the flush returns — a failed reservation must leave the cursor exactly where
        // it was, so the next call reserves again (skipping the values that may or may not have
        // landed) instead of spending values that were never durably reserved.
        next = reservedThrough.first
        limit = reservedThrough.second
    }

    companion object {
        /** Counters reserved per durable write. */
        const val DEFAULT_BLOCK_SIZE: Int = 64

        /**
         * The one allocator for [runtime], created on first use.
         *
         * Weak on BOTH sides: the key is a [VaultRuntime] (identity-compared — [VaultRuntime] does
         * not override `equals`), and the value only weakly references the allocator, so the map
         * never keeps a runtime or an allocator alive. This is a process-wide registry but it is
         * not a device-global singleton and does not violate the one-instance-per-session rule: it
         * holds no vault content, no timers and nothing durable — only "which allocator belongs to
         * which live runtime" — and every entry evaporates with its session. An allocator that is
         * dropped and later re-created starts with an empty cursor, which reserves a fresh block:
         * a skip, never a reuse.
         */
        private val allocators = WeakHashMap<VaultRuntime, WeakReference<DecoyCounterReservation>>()
        private val allocatorsLock = ReentrantLock()

        /**
         * THE way to obtain an allocator. Returns the one allocator for [runtime], so two callers
         * over one runtime share one lock and one cursor and cannot interleave a regression.
         *
         * [blockSize] is honoured on first creation; a later call asking for a DIFFERENT size over
         * the same runtime is a caller bug (two components disagreeing about the reservation) and
         * fails closed rather than silently returning the other size.
         */
        fun forRuntime(
            runtime: VaultRuntime,
            blockSize: Int = DEFAULT_BLOCK_SIZE,
        ): DecoyCounterReservation {
            require(blockSize > 0) { "reservation block size must be positive" }
            return allocatorsLock.withLock {
                val existing = allocators[runtime]?.get()
                if (existing != null) {
                    check(existing.blockSize == blockSize) {
                        "a counter allocator for this runtime already exists with a different block size"
                    }
                    existing
                } else {
                    DecoyCounterReservation(runtime, blockSize)
                        .also { allocators[runtime] = WeakReference(it) }
                }
            }
        }
    }
}
