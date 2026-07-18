// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.i2p

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * I2P routing via an external router app (Orbot-style), primary target i2pd's
 * local SOCKS5 proxy. Unlike Tor, I2P is the FIXED-primary relay transport in
 * the locked I2P -> Tor -> clearnet chain (see net/TransportResolver.kt), so its
 * setting is opt-OUT, not opt-in. Auto-detection of a running i2pd routes the
 * app through I2P without user action; certificate pinning is naturally inert
 * for the .b32.i2p host (see CertificatePinning.buildI2pClient).
 *
 * v1 only *wires* i2pd (SOCKS 4447). The official Java I2P app is detected — it
 * ships under BOTH ids ([I2P_ANDROID_PLAY] on Play, [I2P_ANDROID_FDROID] on
 * F-Droid) — solely so the UI can say "I2P app found, but Zitrone needs i2pd for
 * relay routing" instead of a blind i2pd install prompt.
 */
object I2pIntegration {

    /** i2pd — the router Zitrone wires. Its default local SOCKS5 endpoint is 4447. */
    const val I2PD_PACKAGE = "org.purplei2p.i2pd"

    /** Official Java I2P router — Play Store package id. */
    const val I2P_ANDROID_PLAY = "net.i2p.android"

    /** Official Java I2P router — F-Droid package id (same app, different id). */
    const val I2P_ANDROID_FDROID = "net.i2p.android.router"

    /** i2pd's default local SOCKS5 port. The host comes from BuildConfig. */
    const val SOCKS_PORT = 4447

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** True when i2pd (the router Zitrone can actually wire) is present. */
    fun isI2pdInstalled(context: Context): Boolean = isInstalled(context, I2PD_PACKAGE)

    /** True when the official Java I2P router is present (either id). */
    fun isJavaRouterInstalled(context: Context): Boolean =
        isInstalled(context, I2P_ANDROID_PLAY) || isInstalled(context, I2P_ANDROID_FDROID)

    /** True when ANY supported I2P router app is installed. */
    fun isAnyRouterInstalled(context: Context): Boolean =
        isI2pdInstalled(context) || isJavaRouterInstalled(context)

    /**
     * Launches i2pd so it can build its tunnels (it shows its own foreground
     * notification). No-op when i2pd is not installed — call [isI2pdInstalled]
     * first to route the user to an install prompt instead.
     *
     * Research found NO verified broadcast/intent start API for i2pd (unlike
     * Orbot's ACTION_START), so this only brings its launcher activity to the
     * foreground; do not invent a broadcast it does not document.
     */
    fun startI2pd(context: Context) {
        if (!isI2pdInstalled(context)) return
        val intent = context.packageManager.getLaunchIntentForPackage(I2PD_PACKAGE) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** F-Droid listing for i2pd — the fallback when no Play Store is present. */
    const val I2PD_FDROID_URL = "https://f-droid.org/packages/$I2PD_PACKAGE/"

    /** Opens the Play Store page for i2pd so the user can install it. */
    fun i2pdInstallIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=$I2PD_PACKAGE"),
    )

    /** Opens the F-Droid listing for i2pd (second install option). */
    fun i2pdFDroidIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse(I2PD_FDROID_URL),
    )

    /**
     * SOCKS proxy pointed at the local i2pd for OkHttp. Proxy.Type.SOCKS passes
     * the unresolved .b32.i2p hostname through as a SOCKS5 domain address (the
     * same mechanism the Orbot wiring relies on), so plain OkHttp reaches an I2P
     * destination for both REST and WebSocket — no manual CONNECT tunneling.
     */
    fun socksProxy(host: String): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, SOCKS_PORT))
}
