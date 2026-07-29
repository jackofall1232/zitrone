// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

/**
 * The relay's `error` frame, routed — extracted from `MessagingCoordinator.onServerError` so it can
 * be tested for BEHAVIOUR rather than pinned by a source tripwire (0.10.1 review round 2).
 *
 * **Why this file exists.** Both blind reviewers ruled that a source tripwire cannot cover this
 * logic, and one of them made the argument from evidence rather than principle: **the absence of a
 * behavioural harness here is what let round 2's P1 escape.** `MessagingCoordinator` cannot be
 * constructed in a JVM test — it needs `Context`, `NotificationScheduler`, `SignalProtocolManager`
 * and more, which is Robolectric-scale for reasons that have nothing to do with error routing. Both
 * reviewers independently proposed this same seam instead of a full application harness, so the two
 * decisions it encodes are now testable with no Android framework at all.
 *
 * The two decisions, and why their independence is the whole point:
 *
 *  1. **The cover-traffic yield fires on the CODE.** `rate_limited` is the one signal the relay gives
 *     about the shared per-account send budget, and spec §4.3 R-U3-1 makes cover the half that
 *     yields under contention.
 *  2. **The user-facing failure fires on the ID.**
 *
 * **Neither is nested inside the other, and that is load-bearing.** A rejection the relay could not
 * attribute still means the budget is contended, so the yield must not become conditional on an id
 * being present — folding it inside the attribution would drop the reactive signal in exactly the
 * case where it matters. Equally, an attributed rejection of some other code must still fail its
 * message without yielding cover. The tests next to this file assert both directions.
 */
internal const val ERROR_RATE_LIMITED = "rate_limited"

/**
 * Route one `error` frame.
 *
 * @param code the relay's error code, never content.
 * @param messageId the relay's attribution, or **null when it did not attribute** — the wire field is
 *   `omitempty` and echoed only for a well-formed UUID, so absent and empty both mean
 *   *unattributable*. A null id is a correct, expected path, not a failure: the send timeout is what
 *   bounds it. Guessing which send it was would be worse than saying nothing.
 * @param yieldCover take cover traffic off — called for `rate_limited` regardless of [messageId].
 * @param failByRelay mark that message failed. **The id is the relay's claim, not proof** — the relay
 *   is conceded in the threat model and can echo any well-formed UUID, so the receiver bounds what
 *   this can touch (see `MessageRepository.markFailedByRelay`, which accepts SENDING only and no-ops
 *   on an id it does not hold, so a cover envelope's rejection cannot surface to a user).
 */
internal fun routeServerError(
    code: String,
    messageId: String?,
    yieldCover: () -> Unit,
    failByRelay: (String) -> Unit,
) {
    // FIRST and unconditional on the id — see the class kdoc for why this ordering is the property,
    // not a style choice.
    if (code == ERROR_RATE_LIMITED) yieldCover()
    if (messageId != null) failByRelay(messageId)
}
