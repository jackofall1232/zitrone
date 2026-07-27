// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.VaultSodiumOps
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec

/**
 * Builds a [VaultState] that encodes to within a handful of bytes of
 * [VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES], so that ANY additional section overflows the fixed
 * region deterministically.
 *
 * Shared by the tests that need to exercise the capacity-failure path of a decoy write. It exists
 * because a hand-picked blob size is a guess: it can silently leave enough headroom that the
 * scenario quietly becomes the happy path and the test passes against the defect it was written
 * to catch. This converges on the real boundary and asserts it, so a mis-sized fixture fails
 * loudly instead.
 */
class VaultCapacityFixture(ops: VaultSodiumOps) {

    // One random buffer, used by PREFIX, so repeated probes of the same size encode identically
    // (a fresh random blob per probe would make the convergence loop chase its own noise).
    private val filler: ByteArray = ops.randomBytes(VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES)

    private fun stateWithBlob(size: Int): VaultState =
        VaultState.empty().also { it.signalRecords["blob"] = filler.copyOf(size) }

    /** A state whose encoded size leaves at most [MAX_SLACK_BYTES] free in the region. */
    fun stateFilledToCap(): VaultState = stateWithSlack(0, MAX_SLACK_BYTES)

    /**
     * A state whose encoded size leaves between [floor] and [ceiling] bytes free in the region.
     *
     * The band matters for the callers that need a state which can still take a SMALL write but
     * not a large one: too much slack and the scenario quietly becomes the happy path, too little
     * and both writes fail for the same reason. The convergence asserts the boundary it reached
     * rather than trusting a hand-picked blob size.
     */
    fun stateWithSlack(floor: Int, ceiling: Int): VaultState {
        require(ceiling - floor >= 8) { "slack band too tight to converge on" }
        var size = VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES - 4096
        repeat(12) {
            val encoded = VaultStateCodec.encode(stateWithBlob(size)).size
            val slack = VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES - encoded
            if (slack in floor..ceiling) return stateWithBlob(size)
            // Incompressible content, so encoded size tracks blob size almost exactly; aim at the
            // middle of the band and converge.
            size += slack - (floor + ceiling) / 2
            check(size in 1 until VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES) { "fill diverged: $size" }
        }
        throw AssertionError("could not converge on a state $floor..$ceiling bytes from the cap")
    }

    private companion object {
        const val MAX_SLACK_BYTES = 8
    }
}
