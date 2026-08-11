// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net.registry

import java.net.URI

/**
 * Client-side registry resolution (docs/design/REGISTRY_RESOLUTION.md §3; authority:
 * docs/MULTI_RELAY_ARCHITECTURE.md §3). Source order, first success wins:
 *
 *  1. signed bootstrap snapshot embedded at build time      — [resolveLocalRelay]
 *  2. primary registry endpoint (clearnet)                  ┐
 *  3. registry mirror over Tor                              ├ [refresh] (async, cache-feeding)
 *  4. registry mirror over I2P                              ┘
 *  5. last-known-good cached snapshot on device             — [resolveLocalRelay]
 *
 * "Success" is DEFINED (§3.1): a source succeeds only when [ManifestVerifier.verify]
 * accepts its bytes at the device's current epoch high-water mark. That definition is
 * what makes bootstrap-first ordering rollback-safe: once a refresh has cached epoch
 * N, a stale bootstrap (epoch < N) fails the epoch check and the cache wins instead.
 *
 * Two-phase by design (§3.2): [resolveLocalRelay] is synchronous and touches ONLY
 * local sources (1 and 5) — no network on the construction path. [refresh] is the
 * async network half (sources 2–4); it never returns endpoints, it only feeds the
 * cache, and its result is picked up at the NEXT resolution. v1 deliberately does not
 * hot-swap a live session's relay — the transport-swap machinery stays the only
 * mid-session endpoint mover, and it moves transports, not relays.
 *
 * When [trustRootB64Url] is empty (no `REGISTRY_PUBKEY_ED25519` at build time),
 * everything here returns null/false and the app behaves exactly as before this
 * class existed — activation is an explicit release-time decision (§7), not a
 * side effect of shipping the code.
 */
class RegistryResolver(
    private val trustRootB64Url: String,
    private val verifier: ManifestVerifier,
    private val snapshots: RegistrySnapshotStore,
    /** Loader for `assets/registry/bootstrap.json`; null when the build carries none. */
    private val bootstrap: () -> ByteArray?,
    /**
     * The ONLY clearnet host certificate pinning covers (`CertificatePinning.API_HOST`,
     * cutover-protected). See [selectRelay] for the fail-closed gate it feeds.
     */
    private val pinnedClearnetHost: String,
) {

    /**
     * Resolve from LOCAL sources only (bootstrap, then cache) and select a relay.
     * Null means "registry gave nothing" — disabled, no sources, or nothing valid —
     * and the caller falls back to the legacy constants (§3.3), never to a partial
     * answer.
     */
    fun resolveLocalRelay(): RegistryRelay? = localManifest()?.let(::selectRelay)

    private fun localManifest(): VerifiedManifest? {
        if (trustRootB64Url.isEmpty()) return null
        val minEpoch = snapshots.highWaterEpoch()
        bootstrap()?.let { verifier.verify(it, trustRootB64Url, minEpoch)?.let { v -> return v } }
        snapshots.snapshotBytes()?.let { return verifier.verify(it, trustRootB64Url, minEpoch) }
        return null
    }

    /**
     * First relay in manifest order that passes the v1 pin gate (§3.4): its clearnet
     * endpoints must name EXACTLY the pinned host on both URLs. Until pins travel in
     * the manifest (a cutover decision, not this unit's), a manifest naming any other
     * clearnet host must contribute nothing — fail closed — rather than produce an
     * unpinned TLS connection. Relays without a pinned clearnet endpoint are skipped
     * entirely in v1: the Tor and clearnet transports both ride the clearnet
     * endpoints today, so a relay this client cannot reach over them is not usable.
     *
     * Manifest order is an iteration order, not a priority ranking — all relays are
     * equal peers, and with today's single-relay manifest selection is trivial. The
     * abstraction, not the selection policy, is this unit's deliverable; smarter
     * selection is a registry-side ordering change later (authority doc §3).
     */
    fun selectRelay(manifest: VerifiedManifest): RegistryRelay? =
        manifest.relays.firstOrNull { relay ->
            hostOf(relay.clearnetApiBaseUrl) == pinnedClearnetHost &&
                hostOf(relay.clearnetWsUrl) == pinnedClearnetHost
        }

    /**
     * The async network half — sources 2–4. Tries [urls] in order; the first body
     * that VERIFIES (same gate as everything else, same epoch floor) is persisted to
     * the cache and raises the high-water mark. Unverifiable or unreachable bodies
     * are skipped — a hostile or broken mirror can waste one fetch, nothing more.
     *
     * [fetchBytes] is the caller's transport: AppContainer hands a fetcher over the
     * app's CURRENT OkHttp client, so a Tor/I2P user's registry fetch rides the same
     * anonymity layer as all other traffic. Which URLs are reachable over the current
     * transport is also the caller's knowledge (the I2P client tunnels everything to
     * the relay destination, so clearnet URLs are only offered on clearnet/Tor).
     *
     * @return true when a manifest was verified and durably cached.
     */
    suspend fun refresh(urls: List<String>, fetchBytes: suspend (String) -> ByteArray?): Boolean {
        if (trustRootB64Url.isEmpty()) return false
        for (url in urls) {
            val bytes = fetchBytes(url) ?: continue
            val verified = verifier.verify(bytes, trustRootB64Url, snapshots.highWaterEpoch()) ?: continue
            if (snapshots.store(verified.envelopeBytes, verified.epoch)) return true
        }
        return false
    }

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { URI(it).host }.getOrNull() }
}
