// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.data.qrDropBundleTrusted
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lemon-drop CREATION trust boundary (LemonDropCreator's identity check),
 * pinned as a pure function — the orchestrator itself needs the Keystore-backed
 * store and the network, neither available in a JVM unit test. This is the
 * creation-side mirror of the redeemer's pinned-key cross-check: a drop must
 * seal to the identity we already trust for the contact, or be refused.
 */
class LemonDropCreatorTrustTest {

    private val trusted = "dmT/SwZG8L1h70XRFhWJWzW7uoN4MTyIC0CPp+POcG8="
    private val substituted = "ZFeduFAuckScu/ni1QgZThCYjXVRAXraJDc+kcL2P0k="

    @Test
    fun `proceeds when the relay bundle matches the pinned key`() {
        assertTrue(qrDropBundleTrusted(trusted, trusted))
    }

    @Test
    fun `refuses when the relay serves a different identity than pinned`() {
        assertFalse(qrDropBundleTrusted(trusted, substituted))
    }

    @Test
    fun `refuses when no key is held to compare (one-shot seal has no later verification)`() {
        // Stricter than ordinary TOFU messaging: a lemon drop must seal only to
        // an identity already established for the contact, never to whatever the
        // relay serves for a peer we have never keyed.
        assertFalse(qrDropBundleTrusted(null, substituted))
    }
}
