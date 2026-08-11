// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tor default flip (docs/design/REGISTRY_RESOLUTION.md §6): ON for UNSET
 * preferences only. The load-bearing distinction is key presence — `tor_enabled` is
 * written exclusively by the user's own toggle, so key-absence means "never
 * expressed a preference" and the new default reaches exactly that population. The
 * third test pins the mechanism itself: reading the default must not write the key,
 * or the distinction would silently die for every future default change.
 */
class SettingsTorDefaultTest {

    @Test
    fun `never-set preference gets the new default ON`() {
        val repo = SettingsRepository(FakeSharedPreferences())
        assertTrue(repo.settings.value.torEnabled)
    }

    @Test
    fun `an explicit pre-flip disable is honored verbatim`() {
        // A user who toggled Tor off before this change has the key ON DISK.
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean("tor_enabled", false).commit()
        val repo = SettingsRepository(prefs)
        assertFalse(repo.settings.value.torEnabled)
    }

    @Test
    fun `reading the default never writes the key`() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        assertTrue(repo.settings.value.torEnabled)
        assertFalse("load() must not stamp the default", prefs.contains("tor_enabled"))
    }

    @Test
    fun `an explicit disable after the flip persists across reload`() {
        val prefs = FakeSharedPreferences()
        SettingsRepository(prefs).setTorEnabled(false)
        assertTrue(prefs.contains("tor_enabled"))
        assertFalse(SettingsRepository(prefs).settings.value.torEnabled)
    }
}
