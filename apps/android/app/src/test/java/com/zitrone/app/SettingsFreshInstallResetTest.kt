// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SETTINGS STORE'S FRESH-INSTALL RESET (0.9.2 Unit W-B round-2 review, BLOCKING).
 *
 * The round-1 reasoning — "a fresh install has that prefs file too, so leaving it is correct" — was
 * right about the FILE and wrong about the KEYS. `onboarding_done` is written on every successful
 * `publishSession`, and every device setting the user touched is a key a never-used device does not
 * have. Leaving them is a vault-use oracle inside a file whose presence is innocent.
 *
 * **Scope, stated so it is not overread.** [com.zitrone.app.FakeSharedPreferences] is a plain map:
 * it models the read/write/clear contract, NOT `EncryptedSharedPreferences`' reserved-keyset
 * handling. So this suite proves the repository's own behaviour — clear, commit synchronously,
 * re-read as proof, and republish the in-memory flow. That the androidx store preserves its keysets
 * across that clear (and therefore that the post-burn FILE is byte-identical to a fresh install's)
 * is device behaviour, proven only by [BurnByteForByteGateTest] on a real emulator.
 */
class SettingsFreshInstallResetTest {

    @Test
    fun `every vault-use key is cleared and the defaults come back`() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        repo.setOnboardingDone(true)
        repo.setTorEnabled(true)
        repo.setAutoLockTimeoutSeconds(0)
        repo.setBiometricRequired(false)
        assertTrue("precondition: the store must actually hold vault-use state", repo.settings.value.onboardingDone)

        assertTrue(repo.resetToFreshInstallDefaults())

        assertEquals(
            "post-reset settings must equal the shipped defaults, key for key",
            SettingsRepository.Settings(),
            repo.settings.value,
        )
        assertTrue("no app key may remain in the store", prefs.all.isEmpty())
    }

    /**
     * The IN-MEMORY flow is part of the reset, not a side effect of it. A live observer still holding
     * `onboardingDone = true` would route the burned device past onboarding — and a settings write
     * from that stale state would put the key straight back into the store the burn just emptied.
     */
    @Test
    fun `the published flow is republished from the cleared store`() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        repo.setOnboardingDone(true)

        repo.resetToFreshInstallDefaults()

        assertFalse(repo.settings.value.onboardingDone)
    }

    /**
     * NEGATIVE CONTROL — the return value must be able to say NO. The burn CONSUMES this boolean, so
     * a reset that reports success over a store it could not empty would lower the durability hold
     * over surviving residue. A store whose commit fails is the reachable version of that: the write
     * never lands, the keys are still there, and the answer has to be false.
     */
    @Test
    fun `a store that cannot be cleared fails the reset`() {
        val prefs = UncommittableSharedPreferences()
        val repo = SettingsRepository(prefs)
        repo.setOnboardingDone(true)

        assertFalse(
            "an uncommitted clear must not report a fresh-install baseline",
            repo.resetToFreshInstallDefaults(),
        )
    }

    /**
     * A store whose writes silently do not land. `put` still populates the map (so there IS residue
     * to leave behind), but `clear().commit()` reports false and removes nothing — the shape of a
     * prefs file the process cannot write.
     */
    private class UncommittableSharedPreferences : android.content.SharedPreferences {
        private val delegate = FakeSharedPreferences()

        override fun getAll(): MutableMap<String, *> = delegate.all
        override fun getString(k: String?, d: String?): String? = delegate.getString(k, d)
        override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? =
            delegate.getStringSet(k, d)
        override fun getInt(k: String?, d: Int): Int = delegate.getInt(k, d)
        override fun getLong(k: String?, d: Long): Long = delegate.getLong(k, d)
        override fun getFloat(k: String?, d: Float): Float = delegate.getFloat(k, d)
        override fun getBoolean(k: String?, d: Boolean): Boolean = delegate.getBoolean(k, d)
        override fun contains(k: String?): Boolean = delegate.contains(k)
        override fun registerOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): android.content.SharedPreferences.Editor =
            FailingClearEditor(delegate.edit())

        private class FailingClearEditor(
            private val inner: android.content.SharedPreferences.Editor,
        ) : android.content.SharedPreferences.Editor by inner {
            private var clearRequested = false

            override fun clear(): android.content.SharedPreferences.Editor {
                clearRequested = true
                return this
            }

            override fun commit(): Boolean = if (clearRequested) false else inner.commit()

            override fun apply() {
                if (!clearRequested) inner.apply()
            }
        }
    }
}
