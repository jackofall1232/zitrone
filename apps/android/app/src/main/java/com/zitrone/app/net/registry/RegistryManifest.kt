// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net.registry

/**
 * One relay from a verified registry manifest (docs/design/REGISTRY_RESOLUTION.md §1.2).
 *
 * All relays are equal peers — list order carries NO primary/failover meaning
 * (docs/MULTI_RELAY_ARCHITECTURE.md §3 rejects active-passive by design). A relay
 * carries at least one endpoint; which endpoint the app uses is the transport
 * chain's decision, not the manifest's.
 */
data class RegistryRelay(
    val id: String,
    /** `https://…` REST base, or null when the relay has no clearnet endpoint. */
    val clearnetApiBaseUrl: String?,
    /** `wss://…` WebSocket URL; present iff [clearnetApiBaseUrl] is. */
    val clearnetWsUrl: String?,
    /** Onion address, or null. Unused by the v1 selection (Tor rides clearnet endpoints today). */
    val onionAddress: String?,
    /** `.b32.i2p` destination, or null. Same value `RELAY_I2P_DEST` carries at build time today. */
    val i2pDest: String?,
)

/**
 * A manifest that has passed EVERY acceptance check in [ManifestVerifier] —
 * signature over the exact payload bytes, envelope/schema versions, validity
 * window, epoch floor, and relay well-formedness. Constructing one any other
 * way is a bug; nothing outside the verifier does.
 *
 * [envelopeBytes] is the raw envelope exactly as received — persisted verbatim
 * by the snapshot cache so that the cached copy re-verifies byte-for-byte
 * (the cache stores untrusted bytes and is re-verified on every read; see the
 * WRITER/READER table, row 1).
 */
class VerifiedManifest(
    val epoch: Int,
    val validUntilMs: Long,
    val relays: List<RegistryRelay>,
    val envelopeBytes: ByteArray,
)
