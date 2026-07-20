// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

/**
 * What the lemon-drop veil (the full-screen layer a `/d/{id}` link raises in
 * front of the whole app — see ZitroneRoot) is currently showing.
 *
 * SECURITY INVARIANT the states encode: the veil sits IN FRONT of the
 * biometric gate, which is only tolerable while it renders no secret content.
 * [Advocacy] and [AwaitUnlock] render none — AwaitUnlock HOLDS the decrypted
 * message but shows only "unlock to open". [Delivered], the only state that
 * renders plaintext, is reachable EXCLUSIVELY through an explicit biometric
 * success on AwaitUnlock (MainActivity.openLemonDrop). Plaintext never
 * touches saved-instance state or any other persistence: on process death
 * mid-flow the veil is simply gone — and because the drop is burned only at
 * delivery (below), it is still on the relay and a re-scan re-opens it.
 */
sealed interface LemonDropVeil {

    /** No message to show — the warm advocacy screen, copy per [outcome]. */
    data class Advocacy(val outcome: LemonDropScanOutcome) : LemonDropVeil

    /**
     * This device IS the drop's recipient; the message is decrypted and held
     * in process memory, unrendered, pending an explicit biometric unlock.
     * Dismissing here burns NOTHING: the relay still holds the drop and the
     * consumed one-time prekey is still on disk, so a later re-scan works.
     */
    data class AwaitUnlock(val pending: PendingLemonDrop) : LemonDropVeil

    /** Post-unlock: the message is on screen; delivery side effects (one-time
     *  prekey consumption + relay burn) have been fired. One-way by design —
     *  there is deliberately no reply affordance here. */
    data class Delivered(
        val text: String,
        val senderLabel: String,
        /** True when the sender matched a pinned contact key; false means the
         *  claim was verified only against the relay's current bundle. */
        val senderVerified: Boolean,
    ) : LemonDropVeil {
        /** Redacted — this state carries message plaintext. */
        override fun toString(): String = "Delivered(senderVerified=$senderVerified)"
    }
}

/**
 * A decrypted-but-undelivered drop: everything [LemonDropVeil.Delivered] and
 * the delivery side effects need, held in memory between decrypt (probe) and
 * biometric unlock (delivery). Never persisted anywhere.
 */
data class PendingLemonDrop(
    /** Verbatim wire qr_id (unpadded base64url) — needed for the burn call. */
    val qrId: String,
    val text: String,
    val senderLabel: String,
    val senderVerified: Boolean,
    /** Standard-base64 burn token recovered from inside the sealed payload. */
    val burnTokenBase64: String,
    /** One-time prekey to delete at delivery (single-use), or null. */
    val usedOneTimePrekeyId: Int?,
) {
    /** Redacted — this object carries message plaintext and the burn capability. */
    override fun toString(): String = "PendingLemonDrop(qrId=$qrId)"
}
