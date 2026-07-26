package com.zitrone.app

import kotlinx.coroutines.CancellationException

/**
 * Where the vault's image-bearing material actually is, as THREE states rather than two.
 *
 * `File.exists()` collapses three states into two and defaults the collapse to ABSENT: a stat that
 * FAILS is indistinguishable from a file that is not there. Every fail-open defect this unit has
 * produced traces to that collapse — a routing input that could not tell "proven gone" from
 * "could not tell", and presented a fresh install over the difference.
 *
 * [Present] is a PROVEN presence (`File.exists()` is true only on a successful stat of a real file).
 * [ProvenAbsent] is a PROVEN absence (`Files.notExists` over every image-bearing path). Everything
 * else — a failing stat, an unreadable directory, an I/O fault mid-classification — is
 * [Indeterminate], which is a first-class answer here rather than a silent "absent".
 *
 * **THE RULE: only [ProvenAbsent] may present a fresh install.** [Indeterminate] is read as material
 * that might still be there, never as an empty directory. [mayRouteToOnboarding] is that rule as a
 * value; [treatAsPresent] is its fail-closed complement.
 *
 * **What this type does NOT claim.** [classify] reads its two probes in sequence, not atomically, so
 * a disk that changes underneath it still yields a torn view. What it removes is the ability to
 * REPRESENT a contradiction — "present and proven absent at once" has no value here — and the
 * ability to lose the third state by writing `!exists()`. Absence proof and presence proof are one
 * value with a defined precedence instead of two booleans a caller can pair up wrongly.
 *
 * Introduced 2026-07-25 for the `onRetryDestroy` orchestration owner. The remaining `File.exists()`
 * routing inputs — `serverDeleteConfirmed()` most of all, where an indeterminate marker stat reads
 * "not confirmed" and fails OPEN with respect to delete ownership — are the same defect at other
 * call sites, and migrating them onto this type is mechanical. See `todos.md`.
 */
sealed interface Residence {
    /** A stat succeeded and the image is there. */
    data object Present : Residence

    /** Every image-bearing path is proven absent. The ONLY state that may present a fresh install. */
    data object ProvenAbsent : Residence

    /**
     * Neither proof landed: a failing stat, or a fault while classifying. [cause] carries the
     * throwable when one was raised and is null when the probes merely returned false without
     * throwing (the JDK's `Files.notExists` reports an I/O fault by returning false, not by
     * throwing, so a null [cause] is the ORDINARY indeterminate case, not a missing detail).
     */
    data class Indeterminate(val cause: Throwable?) : Residence

    /** THE RULE, as a value. Only a proven absence may present a fresh install. */
    val mayRouteToOnboarding: Boolean
        get() = this is ProvenAbsent

    /** The fail-closed complement: anything not proven absent is treated as material still on disk. */
    val treatAsPresent: Boolean
        get() = this !is ProvenAbsent

    companion object {
        /**
         * Classify from the two proofs, PRESENCE FIRST.
         *
         * Precedence matters: a proven presence outranks a proven absence, so a disk that changes
         * mid-classification degrades toward [Present] — the fail-closed direction — rather than
         * toward a fresh-install presentation.
         *
         * A throw from either probe yields [Indeterminate] carrying it. [CancellationException] is
         * rethrown, never absorbed: a cancelled boot must not be reported as a disk fact.
         */
        fun classify(present: () -> Boolean, provenAbsent: () -> Boolean): Residence =
            try {
                when {
                    present() -> Present
                    provenAbsent() -> ProvenAbsent
                    else -> Indeterminate(null)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Indeterminate(t)
            }
    }
}
