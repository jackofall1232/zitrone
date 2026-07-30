// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.MessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FULL CROSS-PRODUCT of `settleDecision`, because this is the one decision in the attachment
 * cleanup path that can destroy a delivered attachment, and everything around it is wiring that no
 * JVM test can reach (`MessagingCoordinator` is not constructible).
 *
 * The property that matters is stated once and then proved exhaustively: **ABANDON is returned only
 * when nothing was ever handed to a socket.**
 */
class AttachmentSettleDecisionTest {

    private val allStates: List<MessageState?> = listOf(null) + MessageState.entries

    @Test
    fun `ABANDON is never returned for a handed-off message, in any state`() {
        // THE INVARIANT. A handed-off envelope may already be at the relay — teardown closes the
        // socket gracefully, so queued frames ARE written, while the identity guard then rejects
        // message.stored. So "handed off but unconfirmed" is systematically populated with blobs the
        // recipient can still redeem, and abandoning one destroys a delivered attachment silently.
        for (state in allStates) {
            val action = settleDecision(hasMemo = true, state = state, handedOff = true)
            assertTrue(
                "handed off + state=$state returned $action — a handed-off blob must NEVER be abandoned",
                action != SettleAction.ABANDON,
            )
        }
    }

    @Test
    fun `an absent memo never authorises anything, in any state or handoff`() {
        // The universal safe default: no record ⇒ no destructive act. Asserted across the whole
        // cross-product so it cannot be weakened to "usually".
        for (state in allStates) {
            for (handedOff in listOf(false, true)) {
                assertEquals(
                    "no memo, state=$state, handedOff=$handedOff",
                    SettleAction.SKIP,
                    settleDecision(hasMemo = false, state = state, handedOff = handedOff),
                )
            }
        }
    }

    @Test
    fun `retryable states are skipped, because a retry re-deposits under the same token`() {
        // SENDING = an attempt is live; FAILED = the retry tap is armed (retryable()'s CAS accepts
        // only FAILED). Either way a retry re-deposits under the memoised token, so abandoning here
        // is exactly the confirmed P1: the stale abandon deletes the blob the retry just published.
        for (state in listOf(MessageState.SENDING, MessageState.FAILED)) {
            assertEquals(
                "state=$state must be skipped even when never handed off",
                SettleAction.SKIP,
                settleDecision(hasMemo = true, state = state, handedOff = false),
            )
        }
    }

    @Test
    fun `a never-handed-off message that cannot be retried is abandoned`() {
        // The whole point of the feature: reclaim the blob when it is provably unreferenced and
        // unrecreatable. A gone message (null) is the contact-deleted / burned case.
        assertEquals(
            SettleAction.ABANDON,
            settleDecision(hasMemo = true, state = null, handedOff = false),
        )
    }

    @Test
    fun `every state is classified deliberately — the cross-product is enumerated, not sampled`() {
        // Guards against a NEW MessageState silently defaulting into ABANDON. If someone adds a
        // state, this fails and forces a decision rather than inheriting the else-branch.
        val classified = mutableMapOf<Pair<MessageState?, Boolean>, SettleAction>()
        for (state in allStates) {
            for (handedOff in listOf(false, true)) {
                classified[state to handedOff] = settleDecision(true, state, handedOff)
            }
        }
        assertEquals(
            "the cross-product size changed — a MessageState was added; classify it explicitly",
            (MessageState.entries.size + 1) * 2,
            classified.size,
        )
        val abandoned = classified.filterValues { it == SettleAction.ABANDON }.keys
        assertTrue(
            "ABANDON must only ever be reachable with handedOff=false: $abandoned",
            abandoned.all { !it.second },
        )
        assertTrue(
            "ABANDON must never be reachable for a retryable state: $abandoned",
            abandoned.none { it.first == MessageState.SENDING || it.first == MessageState.FAILED },
        )
    }
}
