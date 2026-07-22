// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single mutation gate over a [VaultState] and its backing [VaultSession].
 *
 * Every store facade ([VaultSignalProtocolStore], VaultRosterStore, VaultAuthStore,
 * VaultSettingsStore) shares ONE runtime, so all of a slot's keystore lives behind one
 * lock and one session. That is why the old cross-store repair hazard (the roster store
 * and the Signal store persisting to different files that could disagree after a crash)
 * is gone by construction: a roster write and a Signal-record read are the SAME lock over
 * the SAME state, encoded and sealed as one payload.
 *
 * MUTATION MODEL. [mutate] runs its block on the LIVE state, then encodes the whole state
 * and hands the bytes to [VaultSession.update] — all while still holding [stateLock].
 * `update` is non-blocking by session contract (it snapshots and schedules; the heavy
 * reseal happens later, off-lock, on the session's flush thread), and `encode` is O(state)
 * — acceptable, and what the PR-D benchmark validates. Because encode runs INSIDE the lock,
 * two concurrent mutates serialize and never interleave a half-mutated encode.
 *
 * ⚠️ CAPACITY CONTRACT (retained-in-memory, NOT persisted — read this). [mutate] applies
 * the block to the live state BEFORE it encodes, and it cannot generically UNDO an
 * arbitrary block. So when `encode` throws [VaultCapacityException] (the compressed state
 * no longer fits the fixed region), the in-memory state KEEPS the mutation but it is NOT
 * scheduled to disk, the throw propagates to the caller, and the runtime latches
 * [capacityExceeded] for PR-D to surface. This is a deliberate design choice over
 * copy-on-write snapshots (which would cost a full state copy on EVERY write); the facade
 * write paths are all small deltas, so the realistic failure is a gradual approach to the
 * cap that PR-D's headroom check catches before it bites, not a single write that leaps
 * over it. A latched [capacityExceeded] means: at least one mutation is live in memory but
 * unpersisted — treat the vault as needing attention, and do NOT assume a later flush
 * captured it.
 *
 * FLUSH-BEFORE-ACK. [flushBeforeAck] delegates to [VaultSession.flushNow] and propagates
 * its throw VERBATIM (including [VaultImageException.NotDurable] and any IO error). A throw
 * means the state did NOT reach disk durably — the caller MUST NOT ack the inbound message
 * that triggered the mutation; the relay redelivers it, and a later flush that succeeds acks.
 *
 * LOCK-ORDER INVARIANT. [stateLock] is the OUTERMOST lock: [mutate] holds it across
 * `session.update` (which briefly takes the session's own locks), and the session NEVER
 * calls back into the runtime. So the order is always runtime.[stateLock] → session locks →
 * storage lock, never the reverse. NEVER call a runtime method from inside a session persist
 * sink — that would invert the order and can deadlock. [flushBeforeAck] deliberately checks
 * `closed` under [stateLock] and then RELEASES it before the (slow, disk-bound) `flushNow`,
 * so a durable reseal never blocks concurrent reads/mutates.
 *
 * This is an isolated runtime unit: it is deliberately NOT wired into any app coordinator,
 * DI graph, unlock router, or migration — that is a later sub-phase (PR-D).
 */
class VaultRuntime(
    private val session: VaultSession,
    initialState: VaultState,
) : java.io.Closeable {

    /** The single monitor guarding [state], [closed], and [capacityExceeded] transitions. */
    private val stateLock = ReentrantLock()

    /** The live keystore. Mutated only inside [mutate]; read only inside [read]. */
    private val state: VaultState = initialState

    /** Once true, [read] / [mutate] / [flushBeforeAck] throw. Set by [close]; idempotent. */
    private var closed = false

    /**
     * Latched the first time a [mutate] encode overflows the region (see the capacity
     * contract in the class kdoc). Never reset here — PR-D observes it and decides the UX.
     * `@Volatile` so a reader on another thread sees the latch without taking [stateLock].
     */
    @Volatile
    var capacityExceeded: Boolean = false
        private set

    /**
     * Run [block] against the current state and return its result. Read-only by
     * convention — do NOT mutate the state here (nothing is re-encoded or scheduled).
     * Throws [IllegalStateException] once closed.
     */
    fun <T> read(block: (VaultState) -> T): T = stateLock.withLock {
        check(!closed) { "vault runtime is closed" }
        block(state)
    }

    /**
     * Apply [block] to the live state, then encode the whole state and schedule a reseal
     * via [VaultSession.update] — all under [stateLock]. Returns [block]'s result.
     *
     * On [VaultCapacityException] from encode: the in-memory mutation is RETAINED but NOT
     * persisted, [capacityExceeded] latches, and the exception propagates (see the class
     * kdoc's capacity contract). Throws [IllegalStateException] once closed.
     */
    fun <T> mutate(block: (VaultState) -> T): T = stateLock.withLock {
        check(!closed) { "vault runtime is closed" }
        val result = block(state)
        val encoded = try {
            VaultStateCodec.encode(state)
        } catch (e: VaultCapacityException) {
            // The block already mutated the live state and we cannot generically revert it;
            // latch the condition and propagate. State is dirty-in-memory but unpersisted.
            capacityExceeded = true
            throw e
        }
        try {
            // Non-blocking by session contract: it copies + schedules, no I/O here.
            session.update(encoded)
        } finally {
            // update() took its own copy, so this transient (compressed secrets) can go now.
            wipe(encoded)
        }
        result
    }

    /**
     * Force a synchronous, durable reseal of the current state and return only once the
     * bytes are confirmed durable. Propagates [VaultSession.flushNow]'s throw verbatim
     * ([VaultImageException.NotDurable] / IO) — a THROW means DO NOT ACK. Throws
     * [IllegalStateException] once closed.
     *
     * The `closed` check runs under [stateLock]; `flushNow` runs OUTSIDE it (it is disk-bound)
     * so a durable reseal never blocks concurrent reads/mutates (see the lock-order note).
     */
    fun flushBeforeAck() {
        stateLock.withLock { check(!closed) { "vault runtime is closed" } }
        session.flushNow()
    }

    /**
     * Final flush + teardown. Closes the session (its own final reseal + key/payload wipe)
     * then wipes the state, under [stateLock]. Idempotent: a second call is a no-op. After
     * close, [read] / [mutate] / [flushBeforeAck] throw [IllegalStateException].
     *
     * If the session's final reseal fails, [VaultSession.close] still wipes its secrets and
     * then rethrows; this method wipes [state] in a `finally` regardless, so teardown never
     * leaks even when the last write could not land — the throw then propagates to the caller.
     */
    override fun close() = stateLock.withLock {
        if (closed) return@withLock
        try {
            session.close()
        } finally {
            state.wipe()
            closed = true
        }
    }
}
