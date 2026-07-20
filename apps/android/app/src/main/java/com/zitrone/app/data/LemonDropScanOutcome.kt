// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import com.zitrone.app.net.ApiClient

/**
 * What a lemon-drop scan honestly established when it did NOT end in a
 * rendered message. Since the Android bridge (LemonDropRedeemer +
 * LemonDropOneShot) a scan DOES attempt to open the drop; these outcomes
 * cover every path where that attempt cannot honestly render one, mirroring
 * the web client's distinct "not-for-us" vs "unavailable" copy:
 *
 *  - [SEALED]      the relay returned a blob but this device cannot render it:
 *                  the seal didn't open (not ours), the payload was broken, the
 *                  device has no identity yet, or the sender cross-check
 *                  failed — all deliberately collapsed to one warm screen.
 *  - [UNAVAILABLE] the relay answered 404: claimed, expired, or never existed
 *                  (deliberately indistinguishable server-side).
 *  - [UNKNOWN]     the fetch never completed (transport failure, rate limit,
 *                  server error) — we know nothing about this drop's state and
 *                  the copy must not pretend otherwise.
 */
enum class LemonDropScanOutcome {
    SEALED,
    UNAVAILABLE,
    UNKNOWN,
}

/**
 * Maps the fetch result to an outcome. Pure so it is unit-testable: a success
 * is [LemonDropScanOutcome.SEALED], an [ApiClient.ApiException] with HTTP 404
 * is [LemonDropScanOutcome.UNAVAILABLE], and every other failure — transport
 * errors, rate limiting, server errors — is [LemonDropScanOutcome.UNKNOWN].
 */
fun classifyLemonDropFetch(result: Result<*>): LemonDropScanOutcome =
    result.fold(
        onSuccess = { LemonDropScanOutcome.SEALED },
        onFailure = { error ->
            if (error is ApiClient.ApiException && error.code == 404) {
                LemonDropScanOutcome.UNAVAILABLE
            } else {
                LemonDropScanOutcome.UNKNOWN
            }
        },
    )
