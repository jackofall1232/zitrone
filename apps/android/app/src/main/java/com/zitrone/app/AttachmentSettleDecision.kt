// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.MessageState

/**
 * Whether an attachment's blob may be abandoned — **the one place where "abandon implies never handed
 * off" is PROVED rather than wired** (0.10.3 C2).
 *
 * Extracted as a pure function for the same reason `ServerErrorRouter` was: nothing in the suite can
 * construct a `MessagingCoordinator`, so logic left inside it is pinned by matching source text
 * instead of tested. Here that would be unacceptable — the decision this function makes is the
 * difference between reclaiming disk and **silently destroying a delivered attachment**.
 *
 * ## The defect this exists to make impossible
 *
 * An attachment blob is deposited *before* its envelope is published. Cleanup that fires while a
 * retry can still name the same blob id deletes the blob underneath a message that then arrives:
 * the recipient sees a permanent `attachmentUnavailable`, and the sender's RAM-only copy dies at the
 * next lock. **Silent to both parties.** Five successive designs failed to survive that race by
 * arbitrating it; this one deletes its precondition instead — **an abandon is issued only when no
 * envelope naming the blob was ever handed to a socket, and none ever can be.**
 *
 * ## Why each branch is what it is
 *
 * - **No memo ⇒ SKIP.** The universal safe default: an absent record can never authorise a
 *   destructive act. This must be a stated rule, not an accident of call ordering.
 * - **SENDING or FAILED ⇒ SKIP.** Both are *retryable* states — `retryable()` arms FAILED, and
 *   SENDING means an attempt is live. A retry re-deposits under the same memoised token, so
 *   abandoning here is exactly the confirmed P1.
 * - **Handed off ⇒ RELEASE_ONLY.** `ws.sendMessage` returning true is an **enqueue** receipt, not
 *   relay acceptance — and teardown makes delivery *more* likely while making acknowledgement
 *   impossible (the socket closes gracefully, so queued frames are written, but the identity guard
 *   then rejects `message.stored`). So a handed-off blob may already be redeemable by the recipient.
 *   Drop the memo; never the blob. **This is monotone: once handed off, no later incarnation of the
 *   same message may abandon it.**
 * - **Otherwise ⇒ ABANDON.** The message is gone or terminal, nothing was ever handed off, and no
 *   retry can resurrect it — so the blob is provably unreferenced and unrecreatable, which is the
 *   only condition under which transmitting its token is acceptable (see `ApiClient.abandonBlob`).
 *
 * ## What actually makes this safe (round-1 review, all three lenses)
 *
 * Not the call-site position, and not the order of the two removals in `releaseDeposit`. **The
 * load-bearing invariant is the fresh-token draw in `AttachmentCrypto.encrypt`:**
 * `val token = reuseToken ?: ByteArray(BLOB_TOKEN_BYTES).also(random::nextBytes)`.
 *
 * A memo re-created after any clearing therefore carries a *fresh random* token, hence a different
 * `blobId`. So a memo that is present names a blob created **after** the last clearing, while any
 * envelope handed off **before** that clearing named the OLD blob id. Chaining that:
 *
 *     memo present AND handoff bit absent  ⇒  no envelope naming the CURRENT blob was ever enqueued
 *
 * which is exactly the ABANDON precondition. The bit and the memo are only ever cleared together
 * (see `releaseDeposit`), and the bit is set in the same non-suspending `confined` tail as
 * `ws.sendMessage`, so no interleaving separates them. **If anyone ever makes the token derivable
 * or reused across clearings, this argument collapses and the P1 returns** — that is the line to
 * defend, and it is why this kdoc names it rather than the call site.
 *
 * The `when` is exhaustive over a closed enum, and its full cross-product is pinned as an explicit
 * decision table by `AttachmentSettleDecisionTest` — so a new [MessageState] cannot silently fall
 * into ABANDON, and no branch can be mutated without failing a named case.
 */
enum class SettleAction {
    /** Do nothing: no record, or the message may still be retried. */
    SKIP,

    /** Drop the memo but keep the blob: an envelope naming it reached a socket. */
    RELEASE_ONLY,

    /** Reclaim the blob: provably unreferenced and unrecreatable. */
    ABANDON,
}

fun settleDecision(
    hasMemo: Boolean,
    state: MessageState?,
    handedOff: Boolean,
): SettleAction = when {
    // An absent record never authorises destruction. Stated first so it cannot be reordered away.
    !hasMemo -> SettleAction.SKIP
    // Retryable: a retry re-deposits under the SAME memoised token, so abandoning is the P1.
    state == MessageState.SENDING || state == MessageState.FAILED -> SettleAction.SKIP
    // Enqueued at least once ⇒ the relay may already hold it and the recipient may redeem it.
    handedOff -> SettleAction.RELEASE_ONLY
    else -> SettleAction.ABANDON
}
