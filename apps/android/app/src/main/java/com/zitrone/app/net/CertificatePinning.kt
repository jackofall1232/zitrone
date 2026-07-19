// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net

import com.zitrone.app.i2p.I2pIntegration
import com.zitrone.app.tor.TorIntegration
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * TLS hardening for every connection the app makes:
 *  - certificate pinning via OkHttp [CertificatePinner]
 *  - TLS 1.3 only (security.transport)
 *  - optional SOCKS routing through Orbot (Tor)
 */
object CertificatePinning {

    /** Host the pin applies to. Must match API_BASE_URL/WS_URL in ZitroneApp. */
    // TODO(zitrone-cutover): pins/host belong to the LIVE sublemonable relay — change only at deploy cutover.
    const val API_HOST = "relay.sublemonable.com"

    /**
     * ╔══════════════════════════════════════════════════════════════════╗
     * ║ Deployment: relay.sublemonable.com                              ║
     * ║                                                                  ║
     * ║ SPKI pins (SHA-256 of the leaf SubjectPublicKeyInfo). PRIMARY is ║
     * ║ the live Let's Encrypt leaf; Caddy reuses its private key across ║
     * ║ renewals (reuse_private_keys) so the pin stays stable. BACKUP is ║
     * ║ an offline-held spare key — swap the server to it and the app    ║
     * ║ keeps connecting without an update. Keep BOTH; drop the old one  ║
     * ║ only after shipping an update that rotated to a new pair.        ║
     * ║                                                                  ║
     * ║ Re-derive with:                                                  ║
     * ║   openssl s_client -connect relay.sublemonable.com:443 \        ║
     * ║     < /dev/null | openssl x509 -pubkey -noout \                 ║
     * ║     | openssl pkey -pubin -outform DER \                        ║
     * ║     | openssl dgst -sha256 -binary | base64                     ║
     * ║                                                                  ║
     * ║ These MUST match the iOS client's PinnedSessionDelegate.swift.  ║
     * ╚══════════════════════════════════════════════════════════════════╝
     */
    const val PRIMARY_PIN = "sha256/TZbasNP1niaVV0fEtpn2QbjY1QiIS8R7w4zhaU5Yw3U="

    /** Backup pin — offline-held spare key. Replace alongside [PRIMARY_PIN]. */
    const val BACKUP_PIN = "sha256/BoqfuAlHFGnQJiL9nv7n7lAnRMixTWhpCWCs8v1eepM="

    private val pinner: CertificatePinner = CertificatePinner.Builder()
        .add(API_HOST, PRIMARY_PIN)
        .add(API_HOST, BACKUP_PIN)
        .build()

    private val tls13Only: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_3)
        .build()

    /**
     * Builds the app's OkHttp client. When [torEnabled] is set, all traffic
     * is proxied through Orbot's local SOCKS port — certificate pinning
     * still applies on top of the Tor circuit.
     */
    fun buildClient(torEnabled: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectionSpecs(listOf(tls13Only))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (torEnabled) {
            builder.proxy(TorIntegration.socksProxy())
        }
        return builder.build()
    }

    /**
     * Builds the OkHttp client for I2P transport — a SIBLING of [buildClient],
     * deliberately not a branch inside it, so the Tor/clearnet path keeps its
     * exact behavior (TLS 1.3 only, no cleartext). I2P differs on three axes:
     *
     *  - Transport: an [I2pConnectSocketFactory] whose sockets HTTP-CONNECT to the
     *    baked-in [relayDest] via the official I2P app's local HTTP proxy at
     *    [host]:4444. One opaque CONNECT tunnel carries BOTH REST and WebSocket —
     *    the proxy cannot see or rewrite Authorization / Sec-WebSocket-Protocol.
     *    NO `.proxy(...)` is set: a configured HTTP proxy would make OkHttp emit
     *    absolute-form request lines through the already-established tunnel, which
     *    the origin server rejects. (This REPLACES the former i2pd SOCKS5 path —
     *    real-device testing found i2pd's tunnels unreliable and the official app
     *    healthy; see i2p/I2pIntegration.kt.)
     *  - Dns: overridden to a placeholder loopback IP carrying the requested
     *    hostname, so OkHttp never tries to DNS-resolve the (unresolvable)
     *    .b32.i2p host. The socket factory ignores the target address entirely —
     *    it always tunnels to [relayDest] — so no hostname recovery is needed.
     *  - Connection spec: [ConnectionSpec.CLEARTEXT] is ALLOWED — the b32
     *    endpoint is plain http/ws (I2P is the transport-security layer; the
     *    b32 address is the destination's cryptographic identity). The TLS-1.3
     *    spec would reject it outright.
     *
     * connectTimeout is a generous 60s (not the 20s the other builders copy): the
     * factory's connect() covers the TCP dial to 4444 PLUS the CONNECT-response
     * read, and the proxy stalls that 200 during destination lookup — empirically
     * ~5.6s for an unreachable dest's 504 and up to tens of seconds for a cold
     * leaseset lookup.
     *
     * The certificate [pinner] stays attached: it is host-scoped to
     * relay.sublemonable.com, so it never matches the .b32.i2p host and is inert
     * here — leaving it on keeps a single client-hardening path and guards the
     * (impossible-by-construction) case of a TLS connection to the pinned host.
     */
    fun buildI2pClient(host: String, relayDest: String): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(pinner)
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        .socketFactory(I2pConnectSocketFactory(host, I2pIntegration.HTTP_PROXY_PORT, relayDest))
        // Placeholder resolution: attach the requested hostname to a loopback IP
        // with no real lookup (the NetCipher trick). The socket factory ignores it.
        // Dns is a plain interface (not a fun interface), so an explicit object.
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
        })
        .connectTimeout(60, TimeUnit.SECONDS) // TCP-to-4444 + CONNECT lookup; see kdoc
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
