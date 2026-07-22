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
 * scheduled to disk (`session.update` is never reached) and the throw propagates. The
 * runtime then holds an UNSCHEDULED live mutation: the live [VaultState] carries an advance
 * the session's last-scheduled payload does not. [capacityExceeded] tracks exactly that
 * condition — it is SET here and CLEARED on the next [mutate] whose `session.update`
 * succeeds (that call schedules the WHOLE live state again — including any earlier overflowed
 * mutation that now fits, e.g. after a delete). While it is set, [flushBeforeAck] REFUSES
 * (throws) rather than confirm durability, so a capacity overflow can NEVER be acked as
 * durable: the inbound message that drove the mutation stays un-acked and redelivers until
 * capacity is resolved and the state re-scheduled. This is a deliberate design choice over
 * copy-on-write snapshots (which would cost a full state copy on EVERY write); the facade
 * write paths are all small deltas, so the realistic failure is a gradual approach to the
 * cap that PR-D's headroom check catches before it bites, not a single write that leaps
 * over it. RESIDUAL: an overflow mutation that NEVER fits again is lost on [close] (the
 * session persists only what was scheduled) — but flush-before-ack never acked it, so the
 * inbound redelivers and no ACKED data is lost.
 *
 * FLUSH-BEFORE-ACK. [flushBeforeAck] first REFUSES (throws [IllegalStateException]) when
 * [capacityExceeded] is set — the live state holds an unscheduled mutation, so the session's
 * (older) scheduled payload does NOT reflect the advance a caller would be acking; flushing it
 * and returning normally would ack an inbound ratchet advance that lives only in memory and is
 * lost on close. Otherwise it delegates to [VaultSession.flushNow] and propagates its throw
 * VERBATIM (including [VaultImageException.NotDurable] and any IO error). A throw — capacity or
 * flush failure — means the state did NOT reach disk durably: the caller MUST NOT ack the
 * inbound message that triggered the mutation; the relay redelivers it, and a later flush (once
 * the state is under the cap and re-scheduled) that succeeds acks.
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
     * True while the live state holds a mutation that FAILED to encode and is therefore NOT
     * scheduled to the session (see the capacity contract in the class kdoc). SET when a
     * [mutate] encode overflows the region; CLEARED on the next [mutate] whose `session.update`
     * succeeds (that call schedules the ENTIRE live state — including any earlier overflowed
     * mutation that now fits — so nothing is left unscheduled). [flushBeforeAck] REFUSES while
     * it is set, so an overflow can never be acked as durable. `@Volatile` so a reader on
     * another thread sees the current value without taking [stateLock]; transitions happen only
     * under [stateLock] inside [mutate].
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
     * via [VaultSession.update] — all under [stateLock]. Returns [block]'s result. A
     * successful `update` CLEARS [capacityExceeded] (the whole live state is scheduled again).
     *
     * On [VaultCapacityException] from encode: the in-memory mutation is RETAINED but NOT
     * scheduled, [capacityExceeded] is SET, and the exception propagates (see the class
     * kdoc's capacity contract). Throws [IllegalStateException] once closed.
     */
    fun <T> mutate(block: (VaultState) -> T): T = stateLock.withLock {
        check(!closed) { "vault runtime is closed" }
        val result = block(state)
        val encoded = try {
            VaultStateCodec.encode(state)
        } catch (e: VaultCapacityException) {
            // The block already mutated the live state and we cannot generically revert it;
            // the live state now holds an UNSCHEDULED mutation. Set the flag and propagate so
            // flushBeforeAck refuses to confirm durability until the state is re-scheduled.
            capacityExceeded = true
            throw e
        }
        try {
            // Non-blocking by session contract: it copies + schedules, no I/O here.
            session.update(encoded)
            // A successful update scheduled the ENTIRE current live state, so no unscheduled
            // mutation remains (this also covers an EARLIER overflow that now fits, e.g. after a
            // delete). Clear only AFTER update returns; the capacity-throw above happens BEFORE
            // this, so an overflowing mutate correctly leaves the flag set.
            capacityExceeded = false
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
     * [IllegalStateException] once closed, and ALSO throws [IllegalStateException] BEFORE the
     * flush when [capacityExceeded] is set: the live state holds an unscheduled mutation, so
     * confirming durability of the (older) scheduled payload would ack an advance that never
     * reached the session (see the class kdoc's capacity contract). Both throws mean DO NOT ACK.
     *
     * The `closed` check runs under [stateLock]; `flushNow` runs OUTSIDE it (it is disk-bound)
     * so a durable reseal never blocks concurrent reads/mutates (see the lock-order note).
     *
     * CLOSE-DURING-FLUSH. After `flushNow` returns, this RE-ACQUIRES [stateLock] and RE-CHECKS
     * `closed`, throwing if the runtime closed meanwhile. This matters because `flushNow` on an
     * already-closed session is a SILENT no-op: were a [close] to interleave during the flush —
     * and its own final reseal to FAIL — `flushNow` here would do nothing, yet return normally,
     * and the caller would ack a message whose ratchet advance never reached disk (permanent
     * loss). The post-flush recheck makes flushBeforeAck NEVER return normally once the runtime
     * has closed, so an ack always implies durability. A close whose final flush SUCCEEDED and
     * still races in also makes this throw — conservatively safe: the caller does not ack, the
     * relay redelivers, and the ratchet drops the duplicate.
     */
    fun flushBeforeAck() {
        stateLock.withLock {
            check(!closed) { "vault runtime is closed" }
            // Fail-closed on an unscheduled capacity overflow: the live state holds a mutation
            // the session's scheduled payload does NOT carry, so flushing (which reseals only the
            // scheduled payload) and returning normally would ack an inbound advance that lives
            // only in memory and is lost on close. A throw means DO NOT ACK — the inbound stays
            // un-acked and redelivers until the state is back under cap and re-scheduled.
            check(!capacityExceeded) {
                "vault state exceeds capacity; the live mutation is unscheduled — cannot confirm durability"
            }
        }
        session.flushNow()
        // Post-flush recheck (see kdoc): flushNow no-ops silently on a closed session, so a
        // close that interleaved the flush must NOT let this report false durability.
        stateLock.withLock {
            if (closed) throw IllegalStateException("vault runtime closed during flush")
        }
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
