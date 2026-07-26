// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.wipeLazyPrefsFilesProven
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE LAZILY-CREATED PREFS FILES (0.9.2 Unit W-B round-2 review, BLOCKING).
 *
 * A never-used device has no `zitrone_signal_store` / `zitrone_auth` / `zitrone_contacts` file, so
 * for these three the fresh-install baseline is ABSENCE and the burn must UNLINK rather than empty.
 *
 * **What this suite does NOT prove**, said here rather than left for a reviewer to discover: it
 * exercises the file-level unlink over a real temp directory, and nothing about
 * `EncryptedSharedPreferences`. The other half of the fix — that clearing the SETTINGS store empties
 * every app key while preserving the androidx keysets and the file — is androidx behaviour on a real
 * device, and only [com.zitrone.app.BurnByteForByteGateTest] can execute it. A JVM test asserting
 * that against a fake would be asserting against a copy.
 */
class VaultUsePrefsWipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stores = listOf("zitrone_signal_store", "zitrone_auth", "zitrone_contacts")

    private fun seed(dir: java.io.File, name: String, suffix: String = ".xml") =
        java.io.File(dir, "$name$suffix").apply { writeText("<map/>") }

    /** The success branch of the injected dir-sync; the parameter exists to match its signature. */
    @Suppress("UNUSED_PARAMETER")
    private fun durable(dir: java.io.File): Boolean = true

    @Test
    fun `every enumerated store is unlinked and proven absent`() {
        val dir = tmp.newFolder("shared_prefs")
        stores.forEach { seed(dir, it) }

        assertTrue(wipeLazyPrefsFilesProven(dir, stores, ::durable))

        stores.forEach {
            assertFalse("$it.xml must be gone", java.io.File(dir, "$it.xml").exists())
        }
    }

    /**
     * `SharedPreferencesImpl` writes `<name>.xml.bak` during a commit and unlinks it on success, so
     * an interrupted write leaves one behind. A surviving backup is residue of exactly the class the
     * burn removes — and it carries the same contents as the file it backs up.
     */
    @Test
    fun `the backup file is unlinked too`() {
        val dir = tmp.newFolder("shared_prefs")
        stores.forEach { seed(dir, it); seed(dir, it, ".xml.bak") }

        assertTrue(wipeLazyPrefsFilesProven(dir, stores, ::durable))

        assertEquals(emptyList<String>(), dir.list()!!.sorted())
    }

    /**
     * The wipe removes what it ENUMERATES and nothing else. The settings store is in the same
     * directory and must SURVIVE — a fresh install has it, so deleting it would create a difference
     * rather than erase one, and would break the store the app reads at startup.
     */
    @Test
    fun `the settings store is left in place`() {
        val dir = tmp.newFolder("shared_prefs")
        stores.forEach { seed(dir, it) }
        val settings = seed(dir, "zitrone_settings")

        assertTrue(wipeLazyPrefsFilesProven(dir, stores, ::durable))

        assertTrue("the startup settings store must survive the unlink pass", settings.exists())
    }

    /**
     * NEGATIVE CONTROL — the proof must be able to FAIL. A `delete()` that does not land has to read
     * as failure, not as "returned false because it was already gone": the two are the same boolean
     * and only a re-stat can tell them apart.
     *
     * A non-empty DIRECTORY at the target path is a real undeletable entry (`File.delete()` refuses
     * it), so this is the actual failure path rather than a mocked one.
     */
    @Test
    fun `a survivor fails the wipe`() {
        val dir = tmp.newFolder("shared_prefs")
        stores.forEach { seed(dir, it) }
        java.io.File(dir, "zitrone_auth.xml").delete()
        java.io.File(dir, "zitrone_auth.xml").mkdir()
        java.io.File(dir, "zitrone_auth.xml/blocker").writeText("undeletable")

        assertFalse(
            "a store that could not be unlinked must fail the burn, not ride it",
            wipeLazyPrefsFilesProven(dir, stores, ::durable),
        )
    }

    /**
     * An unlink whose directory entry is not durable is not an unlink: a journal replay can bring the
     * file back. Same doubt, same verdict, as the image's own `dirSync`.
     */
    @Test
    fun `a non-durable directory sync fails the wipe`() {
        val dir = tmp.newFolder("shared_prefs")
        stores.forEach { seed(dir, it) }

        assertFalse(wipeLazyPrefsFilesProven(dir, stores) { false })
    }

    /**
     * ANTI-VACUITY. An empty coverage set removes nothing and would otherwise report the strongest
     * possible result. This is the guard against a future refactor that drops the store list and
     * turns every green burn into a burn that wiped no preferences at all.
     */
    @Test
    fun `an empty store list is a failure, not a trivially successful wipe`() {
        val dir = tmp.newFolder("shared_prefs")

        assertFalse(wipeLazyPrefsFilesProven(dir, emptyList(), ::durable))
    }

    /**
     * A `shared_prefs` directory that never existed IS the fresh baseline. There is no entry to make
     * durable, and fsyncing a missing directory would fail closed over a correct state — so the
     * sync must not even be attempted.
     */
    @Test
    fun `an absent directory is already fresh and is not synced`() {
        val dir = java.io.File(tmp.root, "shared_prefs_never_created")
        var syncCalled = false

        assertTrue(wipeLazyPrefsFilesProven(dir, stores) { syncCalled = true; false })
        assertFalse("no directory entry exists to sync", syncCalled)
    }
}
