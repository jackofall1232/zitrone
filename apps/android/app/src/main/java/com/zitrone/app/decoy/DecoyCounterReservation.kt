// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultRuntime
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
 * So: reserve [DEFAULT_BLOCK_SIZE] values at a time, persist the new high-water mark BEFORE
 * spending any of them, then spend from memory. One durable write per 64 envelopes.
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
 * The RAM cursor advances only AFTER the mutate returns, so a failed persist (a vault at capacity)
 * leaves the reservation exactly where it was and issues nothing.
 *
 * ## Locking
 *
 * [lock] is a new OUTERMOST lock, above the runtime's: the order is
 * `reservation lock → runtime.stateLock → session locks → storage lock`. Nothing takes the runtime
 * lock and then this one, and this class is never reachable from a session persist sink, so the
 * order cannot invert.
 *
 * One instance per live session, constructed from that session's [VaultRuntime].
 */
class DecoyCounterReservation(
    private val runtime: VaultRuntime,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
) {

    init {
        require(blockSize > 0) { "reservation block size must be positive" }
    }

    private val lock = ReentrantLock()

    /** Next value to issue. Meaningful only while `next < limit`. */
    private var next: Long = 0L

    /** Exclusive end of the reserved block. `next == limit` means "exhausted, reserve again". */
    private var limit: Long = 0L

    /**
     * The next counter value, reserving a fresh block durably when the current one is exhausted.
     *
     * Throws whatever [VaultRuntime.mutate] throws when a reservation cannot be persisted
     * (a closed runtime, or a [com.zitrone.app.crypto.vault.VaultCapacityException]). **A throw
     * means no value was issued** — the caller must not send. This is deliberately NOT swallowed:
     * issuing a counter whose reservation never reached disk is the one failure that could produce
     * a regression, so it must fail loudly to its caller rather than quietly.
     */
    fun next(): Long = lock.withLock {
        // Liveness check on EVERY call, not only when a reservation is due. The reserved block
        // lives in RAM, so without this a torn-down session could keep issuing counters after its
        // runtime closed — "must not survive teardown". The cost is one uncontended lock
        // acquisition per value, against a full AEAD reseal per 64.
        runtime.read { }
        if (next >= limit) reserveLocked()
        next++
    }

    /**
     * Reserve the next block. Re-reads the durable high-water mark rather than trusting the RAM
     * cursor, so this is correct on the first call of a session (RAM starts at 0, the vault may be
     * far ahead) and stays correct if any other writer ever advances the mark.
     */
    private fun reserveLocked() {
        val reservedThrough = runtime.mutate { state ->
            val current = state.decoy?.counterHighWater ?: 0L
            require(current <= Long.MAX_VALUE - blockSize) { "counter reservation would overflow" }
            val advanced = current + blockSize
            state.decoy = (state.decoy ?: DecoyState()).copy(counterHighWater = advanced)
            current to advanced
        }
        // Only AFTER the mutate returns — a failed persist must leave the cursor untouched, so the
        // next call retries the reservation instead of spending values that were never reserved.
        next = reservedThrough.first
        limit = reservedThrough.second
    }

    companion object {
        /** Counters reserved per durable write. */
        const val DEFAULT_BLOCK_SIZE: Int = 64
    }
}
