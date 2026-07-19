// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.i2p

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * I2P routing via an external router app (Orbot-style). The WIRED router is the
 * official Java I2P app — it ships under BOTH ids ([I2P_ANDROID_PLAY] on Play,
 * [I2P_ANDROID_FDROID] on F-Droid) — reached through its local HTTP proxy on
 * [HTTP_PROXY_PORT] (4444). Unlike Tor, I2P is the FIXED-primary relay transport
 * in the locked I2P -> Tor -> clearnet chain (see net/TransportResolver.kt), so
 * its setting is opt-OUT, not opt-in: auto-detection of a running router routes
 * the app through I2P without user action; certificate pinning is naturally
 * inert for the .b32.i2p host (see CertificatePinning.buildI2pClient).
 *
 * WHY the official app and not i2pd (reversal recorded 2026-07-19): real-device
 * testing overrode the earlier SOCKS5/i2pd preference — i2pd failed to build
 * tunnels reliably on a physical device, while the official I2P app warmed up
 * and stayed healthy (73s to first peers -> ~13 active/513 known at 3m -> ~50%
 * green at 5m). The official app's HTTP proxy speaks `CONNECT <b32>:80` (verified
 * against the identical i2ptunnel HTTPClient code in the Java router 2.12.1),
 * which carries both REST and WS over one opaque tunnel — see
 * net/CertificatePinning.kt and net/HttpConnectI2pProber. i2pd is STILL detected,
 * but only so the UI can tell an i2pd-only user that Zitrone now wants the
 * official app instead.
 */
object I2pIntegration {

    /** Official Java I2P router — Play Store package id (the wired router). */
    const val I2P_ANDROID_PLAY = "net.i2p.android"

    /** Official Java I2P router — F-Droid package id (same app, different id). */
    const val I2P_ANDROID_FDROID = "net.i2p.android.router"

    /** i2pd — detected ONLY for the inverse UI hint; no longer wired. */
    const val I2PD_PACKAGE = "org.purplei2p.i2pd"

    /**
     * The official I2P app's default local HTTP proxy port. Replaces i2pd's SOCKS
     * 4447: the HTTP proxy answers `CONNECT <b32>:80` with `HTTP/1.1 200`, so one
     * opaque CONNECT tunnel carries both REST and WebSocket (see
     * net/CertificatePinning.kt and net/HttpConnectI2pProber). The host comes from
     * BuildConfig.I2P_PROXY_HOST.
     */
    const val HTTP_PROXY_PORT = 4444

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** True when the official Java I2P router (the wired router) is present, either id. */
    fun isOfficialRouterInstalled(context: Context): Boolean =
        isInstalled(context, I2P_ANDROID_PLAY) || isInstalled(context, I2P_ANDROID_FDROID)

    /** True when i2pd is present — used only to show the "use the official app" hint. */
    fun isI2pdInstalled(context: Context): Boolean = isInstalled(context, I2PD_PACKAGE)

    /**
     * Launches the official I2P app so it can build its tunnels (it shows its own
     * foreground notification). No-op when the app is not installed — call
     * [isOfficialRouterInstalled] first to route the user to an install prompt
     * instead. Whichever id is installed is brought to the foreground.
     *
     * This only brings the launcher activity to the foreground. Two verified-but-
     * unused start/readiness alternatives exist for a FUTURE pass that accepts a
     * dependency: a broadcast `Intent("net.i2p.android.router.START_I2P")`, and
     * the `IRouterState` AIDL / `net.i2p.android:helper` library's
     * `areTunnelsActive()` (== `State.ACTIVE`) — the latter would beat TCP-probing
     * 4444. We take neither in this pass (no new dependency): launcher-foreground
     * to start, HTTP-CONNECT probe of 4444 for readiness (net/HttpConnectI2pProber).
     */
    fun startI2pRouter(context: Context) {
        val pkg = when {
            isInstalled(context, I2P_ANDROID_PLAY) -> I2P_ANDROID_PLAY
            isInstalled(context, I2P_ANDROID_FDROID) -> I2P_ANDROID_FDROID
            else -> return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** F-Droid listing for the official I2P app — the fallback when no Play Store is present. */
    const val I2P_FDROID_URL = "https://f-droid.org/packages/$I2P_ANDROID_FDROID/"

    /** Opens the Play Store page for the official I2P app so the user can install it. */
    fun i2pInstallIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=$I2P_ANDROID_PLAY"),
    )

    /** Opens the F-Droid listing for the official I2P app (second install option). */
    fun i2pFDroidIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse(I2P_FDROID_URL),
    )
}
