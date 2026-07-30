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
 * ## Round-1 review history — why this file looks the way it does
 *
 * The first version of this test asserted only `!= ABANDON` for handed-off messages and closed with
 * a "cross-product size" check. **All three review lenses independently rejected it**, and both
 * complaints were confirmed by running the mutations:
 *
 * - `handedOff -> SKIP` (instead of `RELEASE_ONLY`) **survived the whole suite**. The test proved
 *   the safety property and left the *liveness* property — that the memo is actually released —
 *   entirely unpinned. A mutant that leaks the memo for every handed-off message passed.
 * - The size assertion was tautological: both sides derived from `MessageState.entries`, so it
 *   could never fail. Worse, its companion `abandoned.all { … }` / `.none { … }` assertions pass
 *   **vacuously** when `abandoned` is empty — i.e. a mutant that never returns ABANDON at all, with
 *   the feature completely dead, was green.
 *
 * The fix is to stop asserting *properties over* the outcome set and instead pin the **exact
 * expected outcome for every input**. A full decision table cannot be satisfied vacuously and
 * cannot drift: any change to any branch fails a named case.
 */
class AttachmentSettleDecisionTest {

    private val allStates: List<MessageState?> = listOf(null) + MessageState.entries

    /**
     * THE DECISION TABLE — the expected outcome for every (state, handedOff) pair, written out by
     * hand rather than computed, so it can never agree with the implementation by construction.
     *
     * If a new [MessageState] is added, `every input has an expected outcome` fails and forces
     * whoever added it to classify it here deliberately.
     */
    private fun expected(state: MessageState?, handedOff: Boolean): SettleAction = when {
        // Retryable states are skipped BEFORE the handoff check, in both handoff positions: a retry
        // re-deposits under the memoised token, so neither reclaiming nor forgetting is safe yet.
        state == MessageState.SENDING || state == MessageState.FAILED -> SettleAction.SKIP
        // Enqueued at least once: the relay may hold it and the recipient may redeem it. Drop the
        // memo, never the blob.
        handedOff -> SettleAction.RELEASE_ONLY
        // Gone or terminal, never enqueued, unrecreatable: the only safe time to reclaim.
        else -> SettleAction.ABANDON
    }

    @Test
    fun `every input has an expected outcome, and the implementation matches it exactly`() {
        var checked = 0
        for (state in allStates) {
            for (handedOff in listOf(false, true)) {
                assertEquals(
                    "settleDecision(hasMemo=true, state=$state, handedOff=$handedOff)",
                    expected(state, handedOff),
                    settleDecision(hasMemo = true, state = state, handedOff = handedOff),
                )
                checked++
            }
        }
        // Not a tautology: the expected count is the literal 2*(states+1) written out, so ADDING a
        // state fails here and forces a deliberate classification in `expected` above.
        assertEquals("a MessageState was added — classify it in expected()", 14, checked)
        assertEquals("MessageState gained/lost a constant", 6, MessageState.entries.size)
    }

    @Test
    fun `a handed-off message RELEASES the memo — it is not merely spared abandonment`() {
        // THE MUTATION THE FIRST VERSION MISSED. `handedOff -> SKIP` also never abandons, so an
        // assertion of `!= ABANDON` passes while the memo is never released and the deposit map
        // grows for the process's lifetime — the heap-leak side of the trade this design exists to
        // avoid. Pin RELEASE_ONLY exactly.
        for (state in allStates) {
            if (state == MessageState.SENDING || state == MessageState.FAILED) continue
            assertEquals(
                "handed off, state=$state must RELEASE the memo, not just decline to abandon",
                SettleAction.RELEASE_ONLY,
                settleDecision(hasMemo = true, state = state, handedOff = true),
            )
        }
    }

    @Test
    fun `ABANDON is never returned for a handed-off message, in any state`() {
        // THE SAFETY INVARIANT. `ws.sendMessage` returning true is an OkHttp ENQUEUE receipt, not
        // relay acceptance — and teardown closes the socket GRACEFULLY, so queued frames ARE
        // written, while the identity guard then rejects the resulting `message.stored`. So
        // "handed off but unacknowledged" is systematically populated with blobs the recipient can
        // still redeem, and abandoning one destroys a delivered attachment silently.
        for (state in allStates) {
            assertTrue(
                "handed off + state=$state must never abandon",
                settleDecision(hasMemo = true, state = state, handedOff = true) != SettleAction.ABANDON,
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
            for (handedOff in listOf(false, true)) {
                assertEquals(
                    "state=$state, handedOff=$handedOff must be skipped",
                    SettleAction.SKIP,
                    settleDecision(hasMemo = true, state = state, handedOff = handedOff),
                )
            }
        }
    }

    @Test
    fun `the feature is live — at least one input actually reclaims`() {
        // Anti-vacuity. The first version's property assertions all passed when ABANDON was never
        // returned, so a mutant with the feature entirely dead was green. Pin the positive case.
        assertEquals(
            SettleAction.ABANDON,
            settleDecision(hasMemo = true, state = null, handedOff = false),
        )
    }
}
