// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.net.registry.ManifestVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * [ManifestVerifier] against REAL Ed25519 signatures — [RegistryTestSigner] signs
 * with lazysodium-java's `crypto_sign_detached`, the identical libsodium C function
 * production verifies with through lazysodium-android, so these exercise the
 * production byte path (the [com.zitrone.app.crypto.LemonDropSodiumOps] test
 * pattern). Every rejection asserts null — the verifier's fail-closed contract has
 * no error taxonomy to assert on, deliberately.
 */
class RegistryManifestVerifierTest {

    // Fixed "now" inside the default manifest's window (validFrom 2026-08-01,
    // validUntil 2026-11-01) so the tests never depend on the wall clock.
    private val now = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli()
    private val verifier = ManifestVerifier(RegistryTestSigner.ed25519Verify, nowMs = { now })

    @Test
    fun `valid manifest verifies and parses`() {
        val v = verifier.verify(RegistryTestSigner.envelope(), RegistryTestSigner.trustRoot, 0)
        assertNotNull(v)
        assertEquals(1, v!!.epoch)
        assertEquals(1, v.relays.size)
        val relay = v.relays[0]
        assertEquals("relay-cx23", relay.id)
        assertEquals("https://relay.sublemonable.com", relay.clearnetApiBaseUrl)
        assertEquals("wss://relay.sublemonable.com/ws", relay.clearnetWsUrl)
        assertEquals("testdest.b32.i2p", relay.i2pDest)
    }

    @Test
    fun `tampering with one payload byte fails verification`() {
        val envelope = RegistryTestSigner.envelope()
        // Flip a character INSIDE the base64url payload field, keeping valid JSON.
        val s = String(envelope, Charsets.UTF_8)
        val idx = s.indexOf("\"payload\":\"") + "\"payload\":\"".length
        val flipped = s.substring(0, idx + 2) +
            (if (s[idx + 2] == 'A') 'B' else 'A') + s.substring(idx + 3)
        assertNull(verifier.verify(flipped.toByteArray(), RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `wrong trust root fails`() {
        val otherKey = RegistryTestSigner.freshKeypair().publicB64Url
        assertNull(verifier.verify(RegistryTestSigner.envelope(), otherKey, 0))
    }

    @Test
    fun `empty trust root means disabled and rejects everything`() {
        assertNull(verifier.verify(RegistryTestSigner.envelope(), "", 0))
    }

    @Test
    fun `any one valid signature suffices`() {
        val rogue = RegistryTestSigner.freshKeypair()
        val envelope = RegistryTestSigner.envelope(extraSigner = rogue)
        // Signed by [rogue, real] — the real key's signature is second; still accepted.
        assertNotNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `malformed signature entries are skipped, never veto a valid sibling`() {
        // The signatures array is OUTSIDE the signed payload: an attacker relaying a
        // legitimate envelope can prepend garbage entries without touching the
        // signature. Each malformed shape must count as a non-match, not abort the
        // scan before the valid entry (PR #65 Codex P2).
        val payload = RegistryTestSigner.manifestJson().toByteArray(Charsets.UTF_8)
        val b64 = { b: ByteArray ->
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
        val envelope = """{
            "envelopeVersion": 1,
            "payload": "${b64(payload)}",
            "signatures": [
                {"keyId":"missing-everything"},
                {"keyId":"bad-b64","algorithm":"ed25519","signature":"!!!not base64url!!!"},
                "not-even-an-object",
                {"keyId":"test-k1","algorithm":"ed25519","signature":"${b64(RegistryTestSigner.sign(payload))}"}
            ]
        }""".toByteArray(Charsets.UTF_8)
        assertNotNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
        // And with NO valid entry among the malformed ones, still fail closed.
        val allBad = """{
            "envelopeVersion": 1,
            "payload": "${b64(payload)}",
            "signatures": [{"keyId":"missing-everything"}, "not-even-an-object"]
        }""".toByteArray(Charsets.UTF_8)
        assertNull(verifier.verify(allBad, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `expired manifest fails with no skew tolerance on validUntil`() {
        val envelope = RegistryTestSigner.envelope(
            validFrom = "2026-06-01T00:00:00Z",
            validUntil = "2026-08-14T00:00:00Z", // one day before "now" — barely expired
        )
        assertNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `signedEpoch ignores the window but nothing else`() {
        // Out-of-window (expired long before the fixed now) — verify refuses it,
        // but the SIGNED epoch still floors (§6.5: the floor is ordinal).
        val expired = RegistryTestSigner.envelope(
            epoch = 7,
            validFrom = "2020-01-01T00:00:00Z",
            validUntil = "2020-06-01T00:00:00Z",
            previousManifestHash = "p7",
        )
        assertNull(verifier.verify(expired, RegistryTestSigner.trustRoot, 0))
        assertEquals(7, verifier.signedEpoch(expired, RegistryTestSigner.trustRoot))
        // Tampering still voids it — the window is the ONLY check signedEpoch skips.
        val tampered = expired.copyOf().also { it[it.size / 2] = 'X'.code.toByte() }
        assertNull(verifier.signedEpoch(tampered, RegistryTestSigner.trustRoot))
        assertNull(verifier.signedEpoch(expired, ""))
    }

    @Test
    fun `not-yet-valid inside 24h skew is accepted, beyond it rejected`() {
        val justAhead = RegistryTestSigner.envelope(validFrom = "2026-08-15T12:00:00Z")
        assertNotNull(verifier.verify(justAhead, RegistryTestSigner.trustRoot, 0))
        val farAhead = RegistryTestSigner.envelope(validFrom = "2026-08-17T00:00:00Z")
        assertNull(verifier.verify(farAhead, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `epoch below the high-water floor is a rollback and fails, equal is accepted`() {
        val epoch5 = RegistryTestSigner.envelope(epoch = 5, previousManifestHash = "prevhash")
        assertNull(verifier.verify(epoch5, RegistryTestSigner.trustRoot, 6))
        assertNotNull(verifier.verify(epoch5, RegistryTestSigner.trustRoot, 5))
    }

    @Test
    fun `unknown schema and envelope versions fail`() {
        assertNull(
            verifier.verify(
                RegistryTestSigner.envelope(schemaVersion = 2),
                RegistryTestSigner.trustRoot,
                0,
            ),
        )
        assertNull(
            verifier.verify(
                RegistryTestSigner.envelope(envelopeVersion = 2),
                RegistryTestSigner.trustRoot,
                0,
            ),
        )
    }

    @Test
    fun `previousManifestHash must be null at epoch 1 and present after`() {
        assertNull(
            verifier.verify(
                RegistryTestSigner.envelope(epoch = 1, previousManifestHash = "notnull"),
                RegistryTestSigner.trustRoot,
                0,
            ),
        )
        assertNull(
            verifier.verify(
                RegistryTestSigner.envelope(epoch = 2, previousManifestHash = null),
                RegistryTestSigner.trustRoot,
                0,
            ),
        )
        assertNotNull(
            verifier.verify(
                RegistryTestSigner.envelope(epoch = 2, previousManifestHash = "somehash"),
                RegistryTestSigner.trustRoot,
                0,
            ),
        )
    }

    @Test
    fun `empty relay list fails`() {
        val envelope = RegistryTestSigner.envelope(relaysJson = "[]")
        assertNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `plain-http clearnet endpoint rejects the whole manifest`() {
        val envelope = RegistryTestSigner.envelope(
            relaysJson = """[{"id":"r","clearnet":{"apiBaseUrl":"http://x.example",""" +
                """"wsUrl":"wss://x.example/ws"},"onion":null,"i2p":null}]""",
        )
        assertNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `relay with no endpoint at all rejects the manifest`() {
        val envelope = RegistryTestSigner.envelope(
            relaysJson = """[{"id":"r","clearnet":null,"onion":null,"i2p":null}]""",
        )
        assertNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `oversize envelope is rejected before parsing`() {
        val envelope = RegistryTestSigner.envelope()
        // Same bytes + trailing whitespace (still valid JSON) past the cap: only the
        // size gate can be the cause of the rejection.
        val padded = envelope + ByteArray(ManifestVerifier.MAX_ENVELOPE_BYTES) { ' '.code.toByte() }
        assertNotNull(verifier.verify(envelope, RegistryTestSigner.trustRoot, 0))
        assertNull(verifier.verify(padded, RegistryTestSigner.trustRoot, 0))
    }

    @Test
    fun `garbage bytes fail`() {
        assertNull(verifier.verify("not json".toByteArray(), RegistryTestSigner.trustRoot, 0))
        assertNull(verifier.verify(ByteArray(0), RegistryTestSigner.trustRoot, 0))
    }
}
