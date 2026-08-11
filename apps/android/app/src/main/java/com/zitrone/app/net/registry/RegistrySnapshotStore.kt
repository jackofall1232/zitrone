// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net.registry

import android.content.SharedPreferences
import java.util.Base64

/**
 * Last-known-good registry snapshot + epoch high-water mark — resolution source 5
 * and the client-side rollback floor (docs/design/REGISTRY_RESOLUTION.md §4, rows 1–2).
 *
 * Lives in the SAME `zitrone_settings` EncryptedSharedPreferences file the device
 * settings and the biometric wrap share — deliberately NOT a new store. That choice
 * is load-bearing for the burn: `resetToFreshInstallDefaults()`'s in-place key clear
 * removes these keys, and the fresh-install baseline (keys absent) stays honest — a
 * fresh install has no cache and no epoch history. `wipeVaultUsePreferences`' four-store
 * enumeration and its "no other prefs factory exists" claim both stay true.
 *
 * CONCURRENCY (blind-review P1): sharing the burn's wipe target is only sound if a
 * write can never land during or after the wipe. The burn runs while this store's only
 * writer — an in-flight registry refresh on the app-lifetime scope — is still live
 * (the burn quiesces the SESSION, not the app scope, and the fetch has no read
 * timeout). A `store()` commit racing the wipe either rewrote keys the burn had just
 * erased (residue until the next boot) or landed between the clear and its
 * `prefs.all.isEmpty()` proof, aborting the burn AFTER the vault image was already
 * destroyed. The gate closes both: [store] runs under [writeGateLock], which the
 * burn's prefs-wipe action also holds, and refuses outright while [writesSuppressed]
 * — set before the first destructive burn step and around the boot completion pass,
 * lifted only when the wipe path relinquishes the store. Ordering argument: a store
 * admitted before suppression holds the lock, so the wipe's clear happens-after its
 * commit and erases it; a store arriving after suppression is refused under the same
 * lock, so nothing can land between the wipe's clear and its verify, or afterwards.
 *
 * The cached bytes are UNTRUSTED on read: the sole writer ([store]) only ever
 * persists an envelope that passed [ManifestVerifier], but every reader re-verifies
 * anyway, so a corrupted or tampered value degrades to a cache miss, never to
 * acceptance.
 *
 * The cached bytes are UNTRUSTED on read: the sole writer ([store]) only ever
 * persists an envelope that passed [ManifestVerifier], but every reader re-verifies
 * anyway, so a corrupted or tampered value degrades to a cache miss, never to
 * acceptance.
 *
 * Post-burn the high-water mark is gone BY DESIGN: a burned device must be
 * byte-indistinguishable from a fresh install, and a surviving epoch mark would be a
 * burn distinguisher. Rollback protection is subordinate to the burn invariant
 * (table row 2 records this as a decision, not an oversight).
 */
class RegistrySnapshotStore(
    private val prefs: SharedPreferences,
    /**
     * Monitor shared with the burn's prefs-wipe action, so an admitted [store] commit
     * and the wipe's clear are mutually exclusive (see the class kdoc's ordering
     * argument). Defaults to a private lock: tests that don't exercise the burn gate
     * need no wiring.
     */
    private val writeGateLock: Any = Any(),
    /**
     * True while a terminal burn (or the boot completion pass re-running its prefs
     * step) owns the settings store. Checked UNDER [writeGateLock], so a store that
     * observed `false` still commits entirely before the wipe's clear can begin.
     */
    private val writesSuppressed: () -> Boolean = { false },
) {

    /** The cached envelope bytes, or null. Callers MUST re-verify — see class kdoc. */
    fun snapshotBytes(): ByteArray? = runCatching {
        prefs.getString(KEY_SNAPSHOT, null)?.let { Base64.getUrlDecoder().decode(it) }
    }.getOrNull()

    /** Highest epoch this device has ever accepted; 0 when it has never accepted one. */
    fun highWaterEpoch(): Int = runCatching { prefs.getInt(KEY_EPOCH_HIGH_WATER, 0) }.getOrDefault(0)

    /**
     * Persist a VERIFIED envelope and raise the high-water mark — one editor commit,
     * so the two keys cannot diverge (a snapshot at epoch N always sits next to a
     * mark >= N). The mark is monotonic: `coerceAtLeast` means even a buggy caller
     * cannot lower it. `commit()` (not `apply()`): callers run on a background
     * dispatcher, and a refresh that reports success must actually be durable.
     *
     * Returns false WITHOUT writing when [writesSuppressed] — a burn in progress owns
     * the store, and a write landing now would be post-wipe residue (or, worse, fail
     * the wipe's own emptiness proof mid-burn). The caller treats it exactly like a
     * persistence failure: the manifest is dropped, never cached.
     */
    fun store(envelopeBytes: ByteArray, epoch: Int): Boolean = synchronized(writeGateLock) {
        if (writesSuppressed()) {
            false
        } else {
            runCatching {
                prefs.edit()
                    .putString(KEY_SNAPSHOT, Base64.getUrlEncoder().withoutPadding().encodeToString(envelopeBytes))
                    .putInt(KEY_EPOCH_HIGH_WATER, epoch.coerceAtLeast(highWaterEpoch()))
                    .commit()
            }.getOrDefault(false)
        }
    }

    companion object {
        private const val KEY_SNAPSHOT = "registry_snapshot"
        private const val KEY_EPOCH_HIGH_WATER = "registry_epoch_high_water"
    }
}
