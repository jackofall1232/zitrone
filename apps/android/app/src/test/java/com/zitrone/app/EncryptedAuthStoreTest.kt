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
    fun `a token refresh overwrites the previous pair`() {
        val store = EncryptedAuthStore(FakeSharedPreferences())
        store.storeTokens("access-1", "refresh-1")
        store.storeTokens("access-2", "refresh-2")
        assertEquals("access-2", store.accessToken)
        assertEquals("refresh-2", store.refreshToken)
    }

    /**
     * In-memory [SharedPreferences] standing in for EncryptedSharedPreferences —
     * the same read/write/remove contract, including `putString(key, null)`
     * removing the key. No Android runtime is touched (every method is a real
     * in-memory override, never the android.jar stub).
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)

        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = set(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = set(key, values)
            override fun putInt(key: String?, value: Int) = set(key, value)
            override fun putLong(key: String?, value: Long) = set(key, value)
            override fun putFloat(key: String?, value: Float) = set(key, value)
            override fun putBoolean(key: String?, value: Boolean) = set(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) { removals.add(key); pending.remove(key) }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean { apply(); return true }

            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }

            private fun set(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) { pending[key] = value; removals.remove(key) }
                return this
            }
        }
    }

    // A trivial guard so the fake's remove-on-null contract is exercised directly.
    @Test
    fun `putString null removes the key like real SharedPreferences`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("k", "v").apply()
        assertTrue(prefs.contains("k"))
        prefs.edit().putString("k", null).apply()
        assertFalse(prefs.contains("k"))
    }
}
