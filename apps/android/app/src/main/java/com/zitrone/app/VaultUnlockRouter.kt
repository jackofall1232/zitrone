// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import java.security.MessageDigest

/**
 * Composable-free unlock-router logic for a vault install (posture B). Holds ONLY the
 * decisions that must be testable and constant across the passphrase / biometric paths:
 * the client-side backoff schedule, the uniform failure message, the biometric-availability
 * gate, and the TRIPLE-ENTRY creation gate (0.9.2 second-vault). All I/O (the off-main
 * `imageStore.attemptUnlockOrAdd`, the BiometricPrompt) stays in the caller — this class
 * touches no Android and no store, so it host-unit-tests directly.
 *
 * SLOT-AGNOSTIC + leak-free: it never sees a slot; the failure message is a single generic
 * string (no per-slot branch). Both RAM-only counters are cleared on process death and never
 * persisted. The gate is the ONLY thing that ever holds anything derived from the passphrase,
 * and only a SHA-256 digest of it (never the passphrase itself), wiped on reset.
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
    @Synchronized
    fun backoffDelayMs(): Long = (BACKOFF_STEP_MS * failedAttempts).coerceAtMost(MAX_BACKOFF_MS)

    /** Record a failed passphrase attempt (advances the backoff). */
    @Synchronized
    fun recordFailure() {
        failedAttempts++
    }

    /** Clear the backoff after any successful unlock. */
    @Synchronized
    fun recordSuccess() {
        failedAttempts = 0
    }

    // ── Triple-entry creation gate (0.9.2 second vault) ─────────────────────────────────────
    //
    // Creating slot B has NO discoverable UI: entering the SAME never-before-used passphrase
    // THREE times consecutively and uninterrupted at the lock screen is the entire ceremony.
    // This is DISTINCT from the backoff [failedAttempts] above — a different counter with
    // different reset rules. Both are RAM-only.

    /**
     * SHA-256 of the last non-matching passphrase's UTF-8 (never the passphrase), or null when
     * there is no pending candidate. A digest — not the passphrase — so nothing reversible is
     * held across attempts; wiped to null on [resetCandidate].
     */
    private var candidateHash: ByteArray? = null

    /** Consecutive-identical-non-matching streak for [candidateHash]; 0 when no candidate. */
    private var candidateCount: Int = 0

    /**
     * Decide whether THIS passphrase attempt should request a vault CREATE, and advance the
     * triple-entry state. Called on EVERY passphrase entry, BEFORE the store attempt, so the
     * SHA-256 + constant-time compare is constant work regardless of outcome (never a
     * distinguisher — it is ~µs against ~1 s of Argon2id in the store).
     *
     * Rules (spec §2): if the entered passphrase hashes identically to the pending candidate,
     * advance the streak; otherwise it BECOMES the new pending candidate at streak 1. Returns
     * true once the streak reaches [CREATE_THRESHOLD] (the 3rd consecutive identical entry) —
     * the caller passes that as `create` to `attemptUnlockOrAdd`. A store match ALWAYS wins over
     * create, and the caller MUST [resetCandidate] on any Unlocked/Burn/Created outcome, so a
     * real vault passphrase can never accumulate a ritual (the first match resets it). The streak
     * is preserved ONLY across `Rejected` outcomes; the uninterrupted-sequence guard
     * ([resetCandidate] on background / lock / process death) means no cycling can advance it.
     *
     * Uses a constant-time digest compare ([MessageDigest.isEqual] over two 32-byte digests) and
     * wipes the transient UTF-8 bytes it hashes.
     */
    @Synchronized
    fun decideCreate(passphrase: String): Boolean {
        // Fully synchronized (one atomic operation w.r.t. resetCandidate / backoff, same monitor). The
        // SHA-256 runs under the monitor: a passphrase digest is ~µs even for a long input, so the lock
        // hold is negligible (accepted Info residual — an earlier "hash outside the lock" variant was
        // reverted because it needlessly split decideCreate's atomicity across the hash).
        val hash = sha256(passphrase)
        val pending = candidateHash
        // ALWAYS run the constant-time compare — against a fixed all-zero digest when there is no
        // pending candidate — so the work is byte-identical on every attempt (no short-circuit that
        // would make a fresh/reset attempt observably cheaper than a continuing one).
        val same = MessageDigest.isEqual(hash, pending ?: NO_CANDIDATE)
        if (pending != null && same) {
            // Cap at the threshold: create stays requested for further identical entries (the
            // marker-present fail-closed case) without ever overflowing candidateCount.
            if (candidateCount < CREATE_THRESHOLD) candidateCount++
            hash.fill(0) // identical to the existing candidate — drop the fresh copy
        } else {
            candidateHash?.fill(0)
            candidateHash = hash
            candidateCount = 1
        }
        return candidateCount >= CREATE_THRESHOLD
    }

    /**
     * Discard the triple-entry candidate + streak. Called on any match/create outcome, on ANY session
     * publish (so a biometric unlock or onboarding also interrupts a ritual — [AppContainer.publishSession]),
     * on a create-attempt cancellation, on a NotDurable create failure, AND — the uninterrupted-sequence
     * guard — on app backgrounding ([VaultLockManager.onStop]) and (implicitly) process death. Leaves the
     * backoff untouched. Thread-safe.
     */
    @Synchronized
    fun resetCandidate() {
        candidateHash?.fill(0)
        candidateHash = null
        candidateCount = 0
    }

    /** SHA-256 of the passphrase's UTF-8 bytes; wipes the transient plaintext bytes. */
    private fun sha256(passphrase: String): ByteArray {
        val pw = passphrase.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(pw)
        } finally {
            pw.fill(0)
        }
    }

    /**
     * Whether to OFFER the biometric affordance: only when a wrap is enabled AND the platform
     * can authenticate BIOMETRIC_STRONG right now. An invalidated key (a new enrollment) reads
     * as not-enabled by the caller (its blob is cleared only after the next passphrase unlock),
     * so this is the single availability gate — no per-slot logic.
     */
    fun biometricOffered(enabled: Boolean, canAuthenticateStrong: Boolean): Boolean =
        enabled && canAuthenticateStrong

    /**
     * Whether to render the biometric ENROLL offer over a live session. Deliberately SLOT-FREE: every
     * input is global/transient — [offerPending], [sessionPresent], and [alreadyEnabled] (the GLOBAL
     * `biometricStore.isEnabled()`, identical in an A- and a B-session) — and NONE is a vault slot, so
     * the enroll surface renders IDENTICALLY in every vault session. The A-only restriction on biometric
     * (OQ4) lives ONLY on the write path (`AppContainer.enableBiometricFromSession` refuses to repoint
     * the single wrap), never in what the UI shows, so the enroll affordance can never be a real-vs-decoy
     * distinguisher. [alreadyEnabled] makes the "enable only when no wrap exists" gate STRUCTURAL (round-2
     * F2): with a wrap present the offer is hidden — in BOTH sessions — so a cross-slot enable can never
     * be tapped, which is what removes the enable-action timing tell and the destructive re-enable
     * (round-2 HIGH/MEDIUM). Keeping this slot-parameterless makes the render-identity invariant
     * structural: a slot term would change the signature and break its test.
     */
    fun biometricEnrollOffered(
        offerPending: Boolean,
        sessionPresent: Boolean,
        alreadyEnabled: Boolean,
    ): Boolean = offerPending && sessionPresent && !alreadyEnabled

    /**
     * Whether a session on [sessionSlot] may WRITE the single biometric wrap, given the slot the
     * current wrap is bound to ([boundSlot], null when none). The A-bound single-wrap rule (OQ4):
     * allow ONLY when there is no wrap yet (first-enable-wins, OQ-A(i) — this slot becomes the
     * binding) OR the existing wrap already names this slot (same-vault re-enable). A different slot
     * is refused — the one wrap is never REPOINTED. Pure + slot-explicit so the enable guard is
     * host-testable; the real writer (`AppContainer.enableBiometricFromSession`) fail-closes on false.
     */
    fun biometricEnableAllowed(boundSlot: Int?, sessionSlot: Int): Boolean =
        boundSlot == null || boundSlot == sessionSlot

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

        /** Consecutive identical non-matching entries required to create a vault (triple-entry). */
        const val CREATE_THRESHOLD = 3

        /** Fixed all-zero 32-byte digest compared against when there is no pending candidate, so the
         *  constant-time compare in [decideCreate] runs identically on every attempt. */
        private val NO_CANDIDATE = ByteArray(32)
    }
}
