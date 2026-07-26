// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.burn

import java.io.File

/**
 * THE BURN AS A TABLE, NOT A PROCEDURE (0.9.2 Unit W-B round 4).
 *
 * **This exists to fix a BLOCKING defect, not to tidy the burn.** Read that first, because it
 * determines what may and may not be changed here.
 *
 * The defect (round 4, Codex; severity upheld by an independent third lens): the durability hold is
 * RAM-only, and every boot reconciler keys on IMAGE-BEARING state (`completeInterruptedBurn` needs
 * `vault.bin` present; `reconcileOrphanedBurnMarkers` needs a marker; the sweep needs residual image
 * files). So once `burnObliterate()` succeeded, a LATER cleanup failure plus process death left a
 * device where every reconciler reports "nothing to do", the hold publishes FALSE, and boot presents
 * ONBOARDING — while the failed cleanup's residue (plaintext attachment cache, diagnostics log,
 * preference keys, orphaned Keystore aliases) is still on disk. A fresh install, carrying proof that
 * a vault existed. That is the feature failing at its purpose.
 *
 * **The fix that was REJECTED, so nobody re-proposes it.** The obvious answer is a durable
 * "burn in progress" marker. Two independent lenses rejected it and they were right: a marker written
 * before the first mutation survives a crash on a device whose vault is still FULLY INTACT — a
 * discoverable artifact proving the duress passphrase was entered, on a device that otherwise looks
 * normal. That is precisely the oracle this feature exists to avoid, and the project already refused
 * a pre-burn intent marker once on the same grounds.
 *
 * **The fix that was taken, in two parts.**
 *
 * 1. **ORDERING CHOSEN BY WHICH FAILURE STATE IS INNOCUOUS.** Non-cryptographic cleanups
 *    ([BurnPhase.BEFORE_IMAGE]) run BEFORE the image is destroyed. A crash there leaves an intact,
 *    unlockable vault whose caches and preferences were cleared — indistinguishable from routine OS
 *    cache eviction, so no oracle. Keystore material ([BurnPhase.AFTER_IMAGE]) must run AFTER the
 *    image: deleting the device key or biometric wrap while a live image remains would render that
 *    image permanently unopenable, which is a DIFFERENT and worse oracle (a vault nobody can open is
 *    not a vault nobody had). The order is derived from which interruption is innocuous, never from
 *    convenience.
 *
 * 2. **THE RESIDUE IS ITS OWN SIGNATURE — no marker required.** `{image PROVEN ABSENT and any step's
 *    postcondition FALSE}` is a shape a fresh install cannot produce: a never-used device has no
 *    diagnostics log, no plaintext cache, no lazily-created preference files, and no device-key
 *    alias. Boot can therefore recognise an interrupted burn from the residue itself and finish it
 *    ([completeInterruptedCleanup]). This is the same structural move that retired the pre-burn
 *    intent marker: the disk state already carries the fact, so persisting the fact separately is
 *    both redundant and dangerous.
 *
 * **Why the steps are DATA and not statements.** Boot has to iterate them. Three rounds of this unit
 * failed the same way — a cleanup that was gated but not durable, durable but not memory-clearing,
 * enumerated on one axis while another went unexamined — and enumerating harder failed twice. A step
 * carries its own [BurnStep.verify] postcondition, so the axes become checkable consequences rather
 * than remembered properties, and **one enumeration serves three consumers**: the burn executes the
 * steps, boot re-checks and completes them, and the byte-for-byte gate asserts the set is covered.
 *
 * **Honest limit, stated rather than overclaimed:** Kotlin cannot stop a future call site from
 * calling `File.delete()` inside a step body and skipping the durable primitives. That is a lint
 * boundary, not a type boundary. What this structure does guarantee is that a step cannot be ADDED
 * without declaring a [Durability] mechanism and a postcondition, and that boot sees every step the
 * burn has.
 */
internal enum class BurnPhase {
    /**
     * Non-cryptographic app-local state. Interruption here leaves an intact vault with cleared
     * caches/preferences — innocuous, so this phase goes FIRST.
     */
    BEFORE_IMAGE,

    /** The vault image, DEK, temps and markers. The point of no return. */
    IMAGE,

    /**
     * Key material whose removal would brick a still-present image. Must follow [IMAGE], because
     * "a vault nobody can open" is a worse oracle than the residue it would replace.
     */
    AFTER_IMAGE,
}

/**
 * HOW a step's effect is made to survive a crash. Every step must name one — there is deliberately
 * no generic "not applicable", because everything can plausibly select "not applicable" whereas a
 * step that touches a file cannot plausibly select [KeystoreTransactional].
 */
internal sealed interface Durability {
    /** Unlink(s) made durable by an fsync of [dir] after the mutation. */
    data class FsyncedDir(val dir: File) : Durability

    /**
     * AndroidKeyStore mutations are persisted transactionally by the keystore daemon. There is no
     * directory to fsync and none is needed — this is a STRONGER guarantee than fsync, not an
     * exemption from it.
     */
    data object KeystoreTransactional : Durability

    /**
     * `EncryptedSharedPreferences` stores: `clear().commit()` (synchronous) then, for the lazily
     * created files, unlink plus an fsync of `shared_prefs`.
     */
    data class PrefsStores(val names: List<String>) : Durability
}

/**
 * One durable cleanup, with the proof of its own end state attached.
 *
 * @param verify the POSTCONDITION — true when this step's end state holds (nothing left to do).
 *   It must be cheap, side-effect-free, and safe to call at boot before any authentication, because
 *   boot calls it on every cold start. **This is what makes the axes checkable instead of
 *   remembered**, and it is the reason the plan is data.
 * @param action performs the cleanup. Throws on any failure; it must never report success it cannot
 *   prove.
 */
internal class BurnStep(
    val name: String,
    val phase: BurnPhase,
    val durability: Durability,
    val verify: () -> Boolean,
    val action: () -> Unit,
)

/**
 * Execute the plan in phase order. Any step that throws aborts the burn with the durability hold
 * still raised — the caller ([com.zitrone.app.runBurnWipe]) owns that.
 *
 * Steps run in declaration order within a phase, and phases run [BurnPhase.BEFORE_IMAGE] →
 * [BurnPhase.IMAGE] → [BurnPhase.AFTER_IMAGE]. The phase ordering is a SAFETY property (see the
 * class kdoc) and is enforced here rather than left to the order someone happened to list them in.
 */
internal fun runBurnPlan(steps: List<BurnStep>) {
    require(steps.isNotEmpty()) { "an empty burn plan would report success having wiped nothing" }
    BurnPhase.entries.forEach { phase ->
        steps.filter { it.phase == phase }.forEach { it.action() }
    }
}

/** What [completeInterruptedCleanup] found and did. */
internal enum class CleanupCompletion {
    /** No residue: every postcondition already held. */
    NOTHING_TO_DO,

    /** Residue found and every retry proved its postcondition. */
    COMPLETED,

    /** Residue found and at least one retry could not prove itself — the hold must stay raised. */
    INCOMPLETE,
}

/**
 * BOOT-SIDE COMPLETION OF AN INTERRUPTED BURN — the marker-free half of the round-4 fix.
 *
 * Called at cold start ONLY when the vault image is PROVEN absent ([imageProvenAbsent]); the caller
 * owns that gate and must use a proven absence, never `File.exists()`, because this function DELETES.
 * With no image present, any surviving step postcondition can only mean a burn (or an account delete)
 * got as far as removing the image and then failed or was killed — a fresh install has none of these
 * artifacts.
 *
 * **Why running the same [BurnStep.action] again is safe:** every step is idempotent by construction
 * (they delete or reset), and each is re-verified afterwards rather than trusted. A step that still
 * cannot prove itself returns [CleanupCompletion.INCOMPLETE], which the caller turns into a raised
 * durability hold — so boot withholds the fresh-install presentation exactly as the in-RAM hold
 * would have, without any durable artifact recording that a burn happened.
 *
 * [BurnPhase.IMAGE] steps are skipped: the image is already proven absent, and re-running an
 * obliterate against no image is at best a no-op and at worst a new failure mode.
 */
internal fun completeInterruptedCleanup(
    steps: List<BurnStep>,
    imageProvenAbsent: Boolean,
): CleanupCompletion {
    if (!imageProvenAbsent) return CleanupCompletion.NOTHING_TO_DO
    val outstanding = steps.filter { it.phase != BurnPhase.IMAGE && !runCatching { it.verify() }.getOrDefault(false) }
    if (outstanding.isEmpty()) return CleanupCompletion.NOTHING_TO_DO
    var allProved = true
    outstanding.forEach { step ->
        runCatching { step.action() }
        // Re-verify rather than trusting the retry: an action that threw and one that silently did
        // nothing are the same to the caller, and only the postcondition can tell them apart.
        if (!runCatching { step.verify() }.getOrDefault(false)) allProved = false
    }
    return if (allProved) CleanupCompletion.COMPLETED else CleanupCompletion.INCOMPLETE
}
