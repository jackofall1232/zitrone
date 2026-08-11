// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net.registry

import org.json.JSONObject
import java.time.Instant
import java.util.Base64

/**
 * The ONE acceptance gate for registry manifests (docs/design/REGISTRY_RESOLUTION.md §1).
 * Every source — embedded bootstrap, network fetch, device cache — funnels its bytes
 * through [verify]; a source "succeeds" exactly when this returns non-null.
 *
 * Fail-closed throughout: every defect — malformed JSON, unknown version, bad
 * signature, out-of-window, epoch rollback, malformed relay — returns null. There is
 * deliberately no error taxonomy: the caller's only correct reaction to any failure
 * is "this source missed, try the next", and returning reasons would invite treating
 * some failures as softer than others.
 *
 * The signature is verified over the DECODED payload bytes exactly as transmitted —
 * the payload is never re-serialized, so JSON canonicalization bugs are impossible by
 * construction (§1.1). This is also why the manifest signature, not the TLS
 * connection to whichever mirror answered, is the trust anchor.
 *
 * [ed25519Verify] is injected in the [LemonDropSodiumOps][com.zitrone.app.crypto.LemonDropSodiumOps]
 * pattern: production hands it lazysodium-android's `crypto_sign_verify_detached`,
 * JVM tests hand the identical C function via lazysodium-java — so the tests exercise
 * the production byte path. [nowMs] is injected so the window checks are testable.
 */
class ManifestVerifier(
    private val ed25519Verify: (signature: ByteArray, message: ByteArray, publicKey: ByteArray) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Verify [envelopeBytes] against [trustRootB64Url] (the raw 32-byte Ed25519
     * registry public key, base64url — `BuildConfig.REGISTRY_PUBKEY_ED25519`).
     *
     * [minEpoch] is the device's high-water epoch: any manifest whose epoch is LOWER
     * is refused as a rollback, however validly signed (a stale-but-signed bootstrap
     * or mirror must never move a client backwards). Equal is accepted — re-reading
     * the manifest the device already trusts is not a rollback.
     */
    fun verify(envelopeBytes: ByteArray, trustRootB64Url: String, minEpoch: Int): VerifiedManifest? {
        if (trustRootB64Url.isEmpty()) return null // registry resolution disabled at build time
        if (envelopeBytes.isEmpty() || envelopeBytes.size > MAX_ENVELOPE_BYTES) return null
        return runCatching { verifyOrThrow(envelopeBytes, trustRootB64Url, minEpoch) }.getOrNull()
    }

    private fun verifyOrThrow(
        envelopeBytes: ByteArray,
        trustRootB64Url: String,
        minEpoch: Int,
    ): VerifiedManifest? {
        val trustRoot = Base64.getUrlDecoder().decode(trustRootB64Url)
        if (trustRoot.size != 32) return null

        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        if (envelope.getInt("envelopeVersion") != 1) return null
        val payloadBytes = Base64.getUrlDecoder().decode(envelope.getString("payload"))

        // Any one valid signature from the build's trust root suffices (1-of-1 today;
        // the array shape is what makes m-of-n an additive change later, §1.1). Each
        // entry is judged in isolation: the signatures array sits OUTSIDE the signed
        // payload, so a malformed entry is attacker-writable without breaking the
        // signature — it must count as a non-match, never veto a valid sibling.
        val signatures = envelope.getJSONArray("signatures")
        val signed = (0 until signatures.length()).any { i ->
            runCatching {
                val sig = signatures.getJSONObject(i)
                sig.getString("algorithm") == "ed25519" &&
                    ed25519Verify(
                        Base64.getUrlDecoder().decode(sig.getString("signature")),
                        payloadBytes,
                        trustRoot,
                    )
            }.getOrDefault(false)
        }
        if (!signed) return null

        // Only now is the payload trusted enough to interpret.
        val manifest = JSONObject(String(payloadBytes, Charsets.UTF_8))
        if (manifest.getInt("schemaVersion") != 1) return null

        val epoch = manifest.getInt("epoch")
        if (epoch < 1 || epoch < minEpoch) return null

        // ±24h skew tolerance on validFrom ONLY: a slightly-slow device clock must not
        // reject a just-published manifest, but validUntil is enforced as written —
        // skew tolerance on expiry would extend every manifest's life by a day (§1.2).
        val now = nowMs()
        val validFrom = Instant.parse(manifest.getString("validFrom")).toEpochMilli()
        val validUntil = Instant.parse(manifest.getString("validUntil")).toEpochMilli()
        if (now < validFrom - VALID_FROM_SKEW_MS || now > validUntil) return null

        if (epoch == 1) {
            if (!manifest.isNull("previousManifestHash")) return null
        } else {
            if (manifest.getString("previousManifestHash").isEmpty()) return null
        }

        // A malformed relay rejects the WHOLE manifest, mirroring the signing tool's
        // own validation: a signed manifest with a bad relay is a signing-side bug,
        // and partially honoring it would hide that bug instead of surfacing it.
        val relaysJson = manifest.getJSONArray("relays")
        if (relaysJson.length() == 0) return null
        val relays = (0 until relaysJson.length()).map { i ->
            val r = relaysJson.getJSONObject(i)
            val id = r.getString("id")
            if (id.isEmpty()) return null
            val clearnet = r.optJSONObject("clearnet")
            val apiBase = clearnet?.getString("apiBaseUrl")
            val ws = clearnet?.getString("wsUrl")
            if (clearnet != null &&
                (apiBase?.startsWith("https://") != true || ws?.startsWith("wss://") != true)
            ) {
                return null
            }
            val onion = r.optJSONObject("onion")?.getString("address")?.takeIf { it.isNotEmpty() }
            val i2p = r.optJSONObject("i2p")?.getString("dest")?.takeIf { it.isNotEmpty() }
            if (clearnet == null && onion == null && i2p == null) return null
            RegistryRelay(id, apiBase, ws, onion, i2p)
        }

        return VerifiedManifest(epoch, validUntil, relays, envelopeBytes)
    }

    companion object {
        /**
         * Hard cap on envelope size, applied before parsing. A registry manifest is a
         * few KB; anything approaching this cap is not a manifest. Bounds what a
         * hostile mirror can make the client buffer and parse.
         */
        const val MAX_ENVELOPE_BYTES = 262_144

        private const val VALID_FROM_SKEW_MS = 24L * 60 * 60 * 1000
    }
}
