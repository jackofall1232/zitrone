// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.diagnostics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * On-device, privacy-safe boot diagnostics — a readable alternative to
 * `adb logcat` for users who hit connection problems and have no second
 * machine (the common case: `adb` isn't available on the device or in the
 * terminal environments people actually have on hand).
 *
 * Each entry is a single boot-stage marker or a transport exception
 * (class + message), prefixed with a UTC timestamp. This is EXACTLY the
 * content the boot loop already emits to logcat via [com.zitrone.app
 * .MessagingCoordinator]: fixed stage strings and exception metadata only —
 * never message content, keys, tokens, account ids, or envelope fields, so
 * the file is safe for a user to copy and share verbatim in a bug report.
 *
 * Storage: a plain text file in app-private storage ([Context.getFilesDir]),
 * which no other app can read (absent root) and which is never included in
 * backups (the app sets `allowBackup=false`). The log is capped at the most
 * recent [MAX_ENTRIES] lines so it can never grow unbounded.
 *
 * All writes are best-effort: a diagnostics IO failure (e.g. a full disk)
 * must NEVER be able to break the boot path, so every disk operation is
 * wrapped and swallowed.
 */
class BootDiagnostics(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    // Serializes the read-modify-write in record()/clear(): record() runs on
    // the boot coroutine while the Diagnostics screen may read concurrently.
    private val lock = Any()

    // Guards the one-time lazy load. Construction touches NO disk (it runs on
    // the main thread inside Application.onCreate); every disk read happens
    // off-main and at most once — on the first record() (boot coroutine) or the
    // first refresh() (the Diagnostics screen, on Dispatchers.IO).
    private var loaded = false
    private val _entries = MutableStateFlow<List<String>>(emptyList())

    /**
     * Recorded lines, oldest-first / most-recent-last. The Diagnostics screen
     * observes this so a boot attempt made while the screen is open shows up
     * live, letting a user watch the exact failure happen.
     */
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** Seed in-memory state from disk exactly once. Caller MUST hold [lock]. */
    private fun ensureLoadedLocked() {
        if (loaded) return
        _entries.value = readFile()
        loaded = true
    }

    /**
     * Load persisted entries into memory if not already loaded. Does disk I/O —
     * call OFF the main thread (the Diagnostics screen does, on open). Surfaces a
     * previous process's log before this process has recorded anything itself.
     */
    fun refresh() = synchronized(lock) { ensureLoadedLocked() }

    /**
     * Append one privacy-safe [line] (timestamped, UTC) and rotate to the last
     * [MAX_ENTRIES], writing the whole capped window back. Uses the in-memory
     * list as the source of truth — no per-write disk read. Never throws. Runs
     * on the boot coroutine (off-main); the first call seeds from disk.
     */
    fun record(line: String) {
        val stamped = "${TS.format(Instant.now())}  $line"
        synchronized(lock) {
            ensureLoadedLocked()
            val next = rotateEntries(_entries.value, stamped, MAX_ENTRIES)
            runCatching { file.writeText(next.joinToString("\n") + "\n") }
            _entries.value = next
        }
    }

    /**
     * ERASE THE LOG COMPLETELY — memory, disk, and the durability of the unlink. ONE function
     * (0.9.2 W-B round-3 review, BLOCKING, both lenses).
     *
     * **Why there is no longer a second, weaker cleanup.** This class used to carry `clear()` (the
     * Diagnostics-screen action) and `clearProven()` (the one the BURN consumes) four lines apart,
     * and the burn's one was the weaker: it deleted the file and stat'd it, and did NOT reset
     * `_entries`/`loaded` the way its neighbour did. Two cleanup functions of divergent strength in
     * one class is not a factoring, it is a defect generator — this unit has the empirical proof. The
     * differing CALLER needs (a UI action must not throw; the burn must fail closed) are a wrapper
     * concern, not a semantics concern, so there is one body and [clear] is a thin wrapper over it.
     *
     * **MEMORY IS CLEARED FIRST, AND THE ORDER IS THE FIX.** [record] writes MEMORY to disk, so a
     * `record()` interleaved between "delete the file" and "reset the buffer" rewrites the pre-burn
     * buffer straight back to disk — resurrecting the log after the burn proved absence and lowered
     * the durability hold. Clearing memory first, under the SAME lock `record()` takes, makes a
     * racing `record()` harmless by construction: it can only append to an empty list, so it writes
     * post-burn data, and a fresh install writes boot diagnostics on its first boot too — that line
     * is not a distinguisher. Reset before proof, always.
     *
     * Truncate-before-delete is kept for the UI path only, where a failed delete then leaves an EMPTY
     * file rather than stale content. On the burn path it is irrelevant (a failed delete throws and
     * the hold stays raised), but one shared body serves both and the extra write is one syscall.
     * **It is NOT a remanence claim:** on flash, overwriting a path does not erase the old blocks.
     *
     * @return true only if the file is PROVEN absent AND that absence is durable ([Files.notExists]
     *   plus an fsync of the containing directory — an unlink that is not journal-durable can come
     *   back on a replay, which is the same doubt the vault image's own `dirSync` settles).
     */
    fun erase(): Boolean = synchronized(lock) {
        // 1. MEMORY FIRST — see above. This is the resurrection kill.
        _entries.value = emptyList()
        loaded = true
        // 2. Truncate (UI path only; harmless here).
        runCatching { file.writeText("") }
        // 3. Delete, then make the DIRECTORY ENTRY durable. Not `runCatching { delete() }`: on the
        //    fail-closed path a throw is INFORMATION, so deleteIfExists's exception fails the erase
        //    rather than being swallowed into an absence check that cannot tell "removed it" from
        //    "never existed" from "delete failed but it vanished anyway".
        val durable = runCatching {
            java.nio.file.Files.deleteIfExists(file.toPath())
            java.nio.channels.FileChannel.open(
                file.parentFile!!.toPath(),
                java.nio.file.StandardOpenOption.READ,
            ).use { it.force(true) }
            true
        }.getOrDefault(false)
        // 4. PROVE.
        durable && java.nio.file.Files.notExists(file.toPath())
    }

    /**
     * Wipe the log — the user action from the Diagnostics screen (call off-main). Fail-OPEN by
     * design: a diagnostics IO error must not crash a settings screen. The burn calls [erase]
     * directly and consumes its result.
     */
    fun clear() {
        if (!erase()) android.util.Log.w("ZitroneBoot", "diagnostics erase did not prove absence")
    }

    private fun readFile(): List<String> = runCatching {
        if (file.exists()) file.readText().split("\n").filter { it.isNotBlank() } else emptyList()
    }.getOrDefault(emptyList())

    companion object {
        private const val FILE_NAME = "boot-diagnostics.log"

        /** Rotation cap — only the most recent this-many lines are kept. */
        const val MAX_ENTRIES = 50

        private val TS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        /**
         * Pure rotation: append [newEntry] and keep only the last [max] lines.
         * Extracted so the cap (the unbounded-growth guard) is unit-testable
         * without an Android [Context]. [max] is floored at 0.
         */
        internal fun rotateEntries(existing: List<String>, newEntry: String, max: Int): List<String> =
            (existing + newEntry).takeLast(max.coerceAtLeast(0))
    }
}
