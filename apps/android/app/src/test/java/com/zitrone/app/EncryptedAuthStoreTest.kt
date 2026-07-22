// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.SharedPreferences
import com.zitrone.app.data.EncryptedAuthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-D2a: ApiClient's inline `authPrefs` account/token persistence was lifted
 * behind the [com.zitrone.app.data.AuthStore] interface as [EncryptedAuthStore].
 * These round-trip the store over an in-memory [SharedPreferences] and assert the
 * token lifecycle mirrors the legacy behaviour byte-for-byte: the SAME
 * `account_id` / `access_token` / `refresh_token` keys, register→storeTokens→
 * clearTokens(logout/401)→clearAccount(delete), and the same remove-on-clear
 * semantics — so wiring it in SessionContainer is behaviour-neutral.
 */
class EncryptedAuthStoreTest {

    @Test
    fun `a fresh store reads null for account and tokens`() {
        val store = EncryptedAuthStore(FakeSharedPreferences())
        assertNull(store.accountId)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun `account id round-trips and survives a token-only clear`() {
        val store = EncryptedAuthStore(FakeSharedPreferences())
        store.accountId = "acct-123"
        assertEquals("acct-123", store.accountId)

        store.storeTokens("access-1", "refresh-1")
        // clearTokens (logout / a 401) drops both tokens, keeps the account.
        store.clearTokens()
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertEquals("acct-123", store.accountId)
    }

    @Test
    fun `tokens round-trip and clearAccount drops only the account`() {
        val store = EncryptedAuthStore(FakeSharedPreferences())
        store.accountId = "acct-123"
        store.storeTokens("access-1", "refresh-1")

        // Full-delete path clears tokens THEN the account; here we assert the
        // account-only clear leaves the (separately cleared) tokens untouched.
        store.clearAccount()
        assertNull(store.accountId)
        assertEquals("access-1", store.accessToken)
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test
    fun `writes land under the exact legacy PREFS_AUTH key names`() {
        val prefs = FakeSharedPreferences()
        val store = EncryptedAuthStore(prefs)
        store.accountId = "acct-9"
        store.storeTokens("a", "r")

        assertEquals("acct-9", prefs.getString("account_id", null))
        assertEquals("a", prefs.getString("access_token", null))
        assertEquals("r", prefs.getString("refresh_token", null))
    }

    @Test
    fun `setting accountId to null removes the key rather than storing a null`() {
        // EncryptedSharedPreferences would persist an encrypted "__NULL__"
        // sentinel for putString(key, null) (contains() stays true); the store
        // must translate null to an explicit remove so null always means ABSENT.
        val prefs = FakeSharedPreferences()
        val store = EncryptedAuthStore(prefs)
        store.accountId = "acct-123"
        store.accountId = null
        assertNull(store.accountId)
        assertFalse(prefs.contains("account_id"))
    }

    @Test
    fun `a token refresh overwrites the previous pair`() {
        val store = EncryptedAuthStore(FakeSharedPreferences())
        store.storeTokens("access-1", "refresh-1")
        store.storeTokens("access-2", "refresh-2")
        assertEquals("access-2", store.accessToken)
        assertEquals("refresh-2", store.refreshToken)
    }

    // A trivial guard so the fake's remove-on-null contract is exercised directly.
    // (The fake itself lives in FakeSharedPreferences.kt, shared with the
    // SignalProtocolManager counter test since the PR-D2a review round.)
    @Test
    fun `putString null removes the key like real SharedPreferences`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("k", "v").apply()
        assertTrue(prefs.contains("k"))
        prefs.edit().putString("k", null).apply()
        assertFalse(prefs.contains("k"))
    }
}
