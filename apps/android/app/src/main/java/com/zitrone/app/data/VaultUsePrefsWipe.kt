// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import java.io.File
import java.nio.file.Files

/**
 * Delete the preference FILES that exist only because a vault was used, and PROVE they are gone
 * (0.9.2 Unit W-B round-2 review, BLOCKING — both lenses).
 *
 * **Why a file delete and not a `clear()`.** The four preference stores split into two kinds, and
 * the split is the whole point:
 *
 *  - [com.zitrone.app.crypto.KeyStoreManager.PREFS_SETTINGS] is opened at STARTUP by
 *    `SettingsRepository`'s constructor, on every launch of every install. A never-used device has
 *    that file — with the two androidx keyset entries in it and no app keys. Deleting it would
 *    CREATE a difference; the fresh baseline is reached by emptying its keys in place
 *    ([SettingsRepository.resetToFreshInstallDefaults]).
 *  - The signal / auth / contacts stores are opened LAZILY — by a live session's stores, or by
 *    `wipeLegacyPrefs()` on vault creation. A never-used device has NO such file. Here the fresh
 *    baseline is ABSENCE, so emptying them in place would leave three empty shells a fresh install
 *    does not have — the same "exists only if the feature was used" oracle as the device-key alias,
 *    one layer up.
 *
 * Round 1 reasoned that "a fresh install has that file too" and stopped. That was right about the
 * FILE and wrong about both the KEYS inside it and the three files a fresh install does not have.
 *
 * `.xml.bak` is deleted alongside each `.xml`: `SharedPreferencesImpl` writes the backup during a
 * commit and unlinks it on success, so an interrupted write can leave one behind — and a survivor
 * is residue of exactly the class this function exists to remove.
 *
 * FAIL-CLOSED, like every other burn cleanup: PROVEN absence ([Files.notExists]) or `false`.
 * "Deleted it and did not check" is what let a surviving diagnostics log ride a successful burn.
 *
 * @param dirSync fsync of the containing directory — an unlinked entry that is not durable can come
 *   back on a journal replay, which is the same doubt the image's own `dirSync` exists to settle.
 *   Injected so a test can force the non-durable branch.
 * @return true only if every target is proven absent AND the directory entry is durable.
 */
internal fun wipeLazyPrefsFilesProven(
    sharedPrefsDir: File,
    names: List<String>,
    dirSync: (File) -> Boolean,
): Boolean {
    // ANTI-VACUITY: an empty coverage set proves nothing, and reporting success for it would make a
    // future refactor that drops the store list silently "pass" the burn. Same guard as the boot
    // mutators' non-vacuity assertion.
    if (names.isEmpty()) return false

    val targets = names.flatMap {
        listOf(File(sharedPrefsDir, "$it.xml"), File(sharedPrefsDir, "$it.xml.bak"))
    }
    targets.forEach { runCatching { it.delete() } }
    // Re-stat to PROVE, rather than trusting delete()'s boolean, which is false both for "was not
    // there" and for "could not remove it".
    if (!targets.all { Files.notExists(it.toPath()) }) return false

    // A shared_prefs directory that does not exist is already the fresh baseline and has no entry to
    // make durable — fsyncing it would fail closed over a state that is CORRECT. (Reachable: a burn
    // on an install whose prefs were never written at all.)
    if (Files.notExists(sharedPrefsDir.toPath())) return true
    return dirSync(sharedPrefsDir)
}
