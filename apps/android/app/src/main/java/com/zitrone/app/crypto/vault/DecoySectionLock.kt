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
 *  - the provisioner reads the section as it stands, commits credentials on top of it, and on a
 *    capacity failure puts back what it read — a *read* and a *restore* in two calls;
 *  - it also writes a back-off ahead of the attempt and later retires **only its own** deferral —
 *    a compare and a clear in two calls;
 *  - `DecoyAuthStore.storeTokens` / `storeTokensForAccount` check that the section still holds the
 *    account the tokens belong to, then write them — a *check* and a *write* in two calls, with
 *    `clearAccount` as the writer that can invalidate the check.
 *
 * Round 1 of review answered each of those with its own check *inside* one of the calls (a snapshot
 * revert, a per-write predicate). Round 2 showed why that could not work: a predicate evaluated in
 * one `runtime.read` and acted on in a later `runtime.mutate` is not atomic with the thing it
 * guards, and a snapshot taken before seconds of network I/O restores stale state over a concurrent
 * write. Both are the same defect: **state sampled outside the lock that protects it.** The fix is
 * one lock over the section, held across each whole sequence, not more checks inside the pieces.
 *
 * **[2026-07-27] The counter allocator was the fourth caller and is gone.** `DecoyCounterReservation`
 * read the durable high-water mark, decided its block was still current, and only then spent it —
 * the sequence that first forced this lock into existence. The idle ping was cut, paired decoys
 * mirror the covered envelope's counter, and the allocator was deleted with its field. **This lock
 * survives on the callers above, which are its own reason and were never the allocator's.**
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
 * session. Like [com.zitrone.app.decoy.DecoyAccountProvisioner]'s gate registry this is
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
