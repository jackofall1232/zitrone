// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] standing in for EncryptedSharedPreferences —
 * the same read/write/remove contract, including `putString(key, null)`
 * removing the key. No Android runtime is touched (every method is a real
 * in-memory override, never the android.jar stub).
 *
 * Shared by [EncryptedAuthStoreTest] and [SignalProtocolManagerCounterTest] so
 * both PR-D2a seams are exercised over the REAL production stores.
 */
internal class FakeSharedPreferences : SharedPreferences {
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
