// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.crypto.vault

import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The ONE monitor that serializes read-modify-write sequences over a runtime's `TAG_DECOY`
 * section.
 *
 * ## Why [VaultRuntime]'s own lock is not enough, and why this is not a third guard
 *
 * `stateLock` makes each individual `mutate` atomic. That is the wrong granularity for this
 * section, because every correctness argument here spans MORE than one runtime call:
 *
 *  - the counter allocator reads the durable mark, decides its block is still current, and only
 *    then spends it — a *check* and a *spend* in two calls;
 *  - the provisioner reads the section as it stands, commits credentials on top of it, and on a
 *    capacity failure puts back what it read — a *read* and a *restore* in two calls;
 *  - `DecoyAuthStore.clearAccount` resets the mark that the allocator just checked.
 *
 * Round 1 of review answered each of those with its own check *inside* one of the calls (a stale
 * block test, a snapshot revert). Round 2 showed why that could not work: a predicate evaluated in
 * one `runtime.read` and acted on in a later `runtime.mutate` is not atomic with the thing it
 * guards, so `clearAccount()` landing between the two reissues counter values, and a snapshot taken
 * before seconds of network I/O restores an older high-water mark over a concurrent reservation.
 * Both are the same defect: **state sampled outside the lock that protects it.** The fix is one
 * lock over the section, held across each whole sequence, not more checks inside the pieces.
 *
 * ## Scope: it guards SEQUENCES, not fields
 *
 * Single reads (`accountId`, `accessToken`) do not need it — `runtime.read` is already atomic and
 * a caller acting on a stale single value is the caller's own race. Everything that writes the
 * section, and everything that reads it in order to decide what to write, takes this.
 *
 * ## Lock order
 *
 * This is the OUTERMOST lock: `decoy section lock → runtime.stateLock → session locks → storage
 * lock`. Nothing takes `stateLock` and then this one — no `mutate` block and no session persist
 * sink can reach this object — so the order cannot invert. It is held across
 * [VaultRuntime.flushBeforeAck], which releases `stateLock` before its disk-bound `flushNow`; that
 * is added LATENCY on a background path, not added nesting.
 *
 * ## Lifetime
 *
 * One lock per live [VaultRuntime], created on first use, weakly keyed so it evaporates with the
 * session. Like [com.zitrone.app.decoy.DecoyCounterReservation]'s allocator registry this is
 * process-wide but is not a device-global singleton and holds nothing about any vault: no content,
 * no timers, nothing durable, only "which monitor belongs to which live runtime". The values hold
 * no reference back to the key, so an entry never keeps a runtime alive.
 */
object DecoySectionLock {

    private val locks = WeakHashMap<VaultRuntime, ReentrantLock>()
    private val registryLock = ReentrantLock()

    /** The one section monitor for [runtime]. */
    fun forRuntime(runtime: VaultRuntime): ReentrantLock = registryLock.withLock {
        locks.getOrPut(runtime) { ReentrantLock() }
    }

    /** Run [block] holding [runtime]'s section monitor. */
    fun <T> withSection(runtime: VaultRuntime, block: () -> T): T =
        forRuntime(runtime).withLock(block)
}
