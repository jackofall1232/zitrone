// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.classifyLemonDropFetch
import com.zitrone.app.net.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

// The advocacy screen's copy must reflect what the single blind fetch honestly
// established, and nothing more. These pin the mapping the veil relies on:
// only a served blob may claim a live sealed drop exists, only a 404 may claim
// there is nothing left to open, and everything else must claim nothing.
class LemonDropScanOutcomeTest {

    @Test
    fun `served blob means a live sealed drop`() {
        assertEquals(
            LemonDropScanOutcome.SEALED,
            classifyLemonDropFetch(Result.success(Unit)),
        )
    }

    @Test
    fun `404 means claimed, expired, or never existed`() {
        assertEquals(
            LemonDropScanOutcome.UNAVAILABLE,
            classifyLemonDropFetch(Result.failure(ApiClient.ApiException(404, "not_found"))),
        )
    }

    @Test
    fun `server errors claim nothing about the drop`() {
        assertEquals(
            LemonDropScanOutcome.UNKNOWN,
            classifyLemonDropFetch(Result.failure(ApiClient.ApiException(500, "store_failed"))),
        )
    }

    @Test
    fun `rate limiting claims nothing about the drop`() {
        assertEquals(
            LemonDropScanOutcome.UNKNOWN,
            classifyLemonDropFetch(Result.failure(ApiClient.ApiException(429, "rate_limited"))),
        )
    }

    @Test
    fun `transport failure claims nothing about the drop`() {
        assertEquals(
            LemonDropScanOutcome.UNKNOWN,
            classifyLemonDropFetch(Result.failure(IOException("relay unreachable"))),
        )
    }
}
