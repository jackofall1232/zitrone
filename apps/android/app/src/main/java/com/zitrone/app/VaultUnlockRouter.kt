// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

/**
 * Composable-free unlock-router logic for a vault install (posture B). Holds ONLY the
 * decisions that must be testable and constant across the passphrase / biometric paths:
 * the client-side backoff schedule, the uniform failure message, and the
 * biometric-availability gate. All I/O (the off-main `imageStore.unlock`, the
 * BiometricPrompt) stays in the caller — this class touches no Android and no store, so
 * it host-unit-tests directly.
 *
 * SLOT-AGNOSTIC + leak-free: it never sees a passphrase, a key, or a slot; the failure
 * message is a single generic string (no per-slot branch); the backoff counter is RAM-only
 * (cleared on process death and on any success), never persisted.
 */
class VaultUnlockRouter {

    /**
     * Consecutive failed passphrase attempts THIS process — RAM only, so a relaunch resets
     * it (the store already guarantees identical work per attempt, so a persisted lockout
     * would add nothing but a footgun). Reset on success.
     */
    private var failedAttempts: Int = 0

    /**
     * The delay to enforce BEFORE the next passphrase attempt is accepted, from the count of
     * prior failures: 500 ms × attempts, capped at [MAX_BACKOFF_MS]. Zero on a fresh counter,
     * so the first attempt is never delayed.
     */
    fun backoffDelayMs(): Long = (BACKOFF_STEP_MS * failedAttempts).coerceAtMost(MAX_BACKOFF_MS)

    /** Record a failed passphrase attempt (advances the backoff). */
    fun recordFailure() {
        failedAttempts++
    }

    /** Clear the backoff after any successful unlock. */
    fun recordSuccess() {
        failedAttempts = 0
    }

    /**
     * Whether to OFFER the biometric affordance: only when a wrap is enabled AND the platform
     * can authenticate BIOMETRIC_STRONG right now. An invalidated key (a new enrollment) reads
     * as not-enabled by the caller (its blob is cleared only after the next passphrase unlock),
     * so this is the single availability gate — no per-slot logic.
     */
    fun biometricOffered(enabled: Boolean, canAuthenticateStrong: Boolean): Boolean =
        enabled && canAuthenticateStrong

    companion object {
        /** Uniform, generic failure — never names a slot, a count, or which factor failed. */
        const val UNIFORM_FAILURE = "Couldn't unlock. Check your passphrase and try again."

        /** Honest note shown when a biometric key was invalidated by a new enrollment. */
        const val BIOMETRIC_REENROLL_NOTE =
            "Biometric unlock needs re-enabling after a passphrase unlock."

        /**
         * DISTINCT from [UNIFORM_FAILURE]: surfaced only when the vault IMAGE itself is
         * damaged/unreadable (VaultImageException.CorruptImage / MissingImage), which is NOT a
         * passphrase guess — so it must not be flattened into the wrong-passphrase oracle-avoiding
         * uniform failure. Names no slot and no credential.
         */
        const val IMAGE_UNREADABLE_NOTE =
            "This vault couldn't be opened — the stored image may be damaged."

        private const val BACKOFF_STEP_MS = 500L
        private const val MAX_BACKOFF_MS = 8_000L
    }
}
