// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4's structural requirements, pinned against the source rather than against behaviour.
 *
 * Three of the four R-U4 requirements are claims about **what the code cannot reach**, not about
 * what it does: R-U4-1 is satisfied by the guard's *position* relative to `signal.decrypt`, and
 * R-U4-2 / R-U4-3 are satisfied by [com.zitrone.app.decoy.DecoyInboundSession]'s *dependencies*.
 * A behavioural test cannot fail when those stop holding — it can only fail once something already
 * went wrong — so they are pinned here instead.
 *
 * The round-5 lesson from U3 is why these exist at all: production dispatch was pinned only by
 * source strings while the tests built their own executor, so the tripwires were green over a
 * defect. These read the shipped files.
 */
class DecoyU4SourceTripwireTest {

    // -- R-U4-1: the guard runs BEFORE decrypt --------------------------------------------------

    @Test
    fun `the synthetic-sender guard precedes signal decrypt on the inbound path`() {
        val source = read("MessagingCoordinator.kt")
        val deliver = source.indexOf("override fun onMessageDeliver(")
        assertTrue("onMessageDeliver not found", deliver > 0)
        val guard = source.indexOf("isSyntheticSender(envelope.senderId)", deliver)
        val decrypt = source.indexOf("signal.decrypt(", deliver)
        assertTrue("the R-U4-1 guard is missing from onMessageDeliver", guard > 0)
        assertTrue("signal.decrypt not found after onMessageDeliver", decrypt > 0)
        assertTrue(
            "the cover-account guard MUST precede decrypt: libsignal's PreKey path TOFU-establishes " +
                "a session and remote identity inside decrypt, before any MAC check can reject the blob",
            guard < decrypt,
        )
    }

    @Test
    fun `the guard returns without decrypting rather than falling through`() {
        val source = read("MessagingCoordinator.kt")
        val guard = source.indexOf("if (isSyntheticSender(envelope.senderId)) {")
        assertTrue("the R-U4-1 guard is missing", guard > 0)
        val body = source.substring(guard, source.indexOf("if (isDeletedContact(", guard))
        assertTrue("the guard must ack so the relay drops its copy", body.contains("ws.ackMessage(envelope.id)"))
        assertTrue("the guard must return, not fall through to decrypt", body.contains("return@runCatching"))
    }

    @Test
    fun `the guard is actually wired in production, not left at its default`() {
        val app = read("ZitroneApp.kt")
        assertTrue(
            "MessagingCoordinator defaults isSyntheticSender to { false }; a build that never " +
                "passes it has a dead guard and cover replies would reach decrypt",
            app.contains("isSyntheticSender = { senderId ->"),
        )
        assertTrue(
            "the guard must read the synthetic id per envelope — a captured null leaves it " +
                "permanently open on exactly the vaults that go on to generate cover traffic",
            app.contains("DecoyAuthStore(rt).accountId?.let { it == senderId } == true"),
        )
    }

    // -- R-U4-2 / R-U4-3: dependencies, not behaviour -------------------------------------------

    @Test
    fun `the synthetic side reaches no crypto and no durable writer`() {
        for (file in U4_FILES) {
            // COMMENTS STRIPPED FIRST. The requirement is about what the code can reach, and these
            // files legitimately *name* the forbidden types in their kdoc — explaining that they
            // cannot reach them is the documentation's job. Matching prose would make the guard
            // fail on an accurate comment while a real dependency added later still passed, which
            // is precisely backwards.
            val source = codeOf(read(file))
            for (forbidden in FORBIDDEN) {
                assertTrue(
                    "$file references `$forbidden`. R-U4-2/R-U4-3 are properties of this type's " +
                        "dependencies — the synthetic side never decrypts, never establishes a " +
                        "session, and writes nothing durable. If this is a deliberate change, the " +
                        "requirement in spec §4.4 has to change first.",
                    !source.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `no U4 surface writes a durable diagnostic about cover traffic`() {
        // U4 review round 4, Codex. The R-U4-1 guard used to diag() when it dropped a cover-account
        // envelope; BootDiagnostics.record writes that to boot-diagnostics.log and shows it in
        // Settings → Diagnostics. A timestamped on-disk line proving THIS DEVICE received cover
        // traffic is evidence a vault with a provisioned synthetic account exists here — and
        // plausible deniability is the product. The rest of the decoy code already takes no logger
        // at all; this pins that the guard cannot reacquire one.
        val guard = codeOf(read("MessagingCoordinator.kt")).let {
            val at = it.indexOf("if (isSyntheticSender(envelope.senderId)) {")
            assertTrue("the R-U4-1 guard is missing", at > 0)
            it.substring(at, it.indexOf("if (isDeletedContact(", at))
        }
        for (sink in listOf("diag(", "Log.", "println", "BootDiagnostics")) {
            assertTrue(
                "the cover-account drop must be SILENT; found `$sink` in the guard",
                !guard.contains(sink),
            )
        }
        for (file in U4_FILES) {
            val source = codeOf(read(file))
            // Bare `diag`, not `diag(` (U4 review round 5, both lenses): the socket wiring never
            // CALLED diag — it accepted a `diag` PARAMETER and forwarded it into WsClient, whose
            // lifecycle lines then reached BootDiagnostics.record and boot-diagnostics.log with no
            // call token anywhere in a U4 file. The structural hole was "U4 may accept a logging
            // sink"; the parameter is gone, so the honest rule is that the token does not appear.
            for (sink in listOf("diag", "Log.", "println", "BootDiagnostics")) {
                assertTrue("$file must not log or accept a logging sink: found `$sink`", !source.contains(sink))
            }
        }
        // …and the PRODUCTION CONSTRUCTION SITE is scanned too (U4 review round 5, both lenses):
        // the round-4 version of this test read only the U4 files, and the defect lived in
        // ZitroneApp, which handed `bootDiagnostics.record` to the socket it was building. No
        // argument the construction passes may name a sink.
        val app = codeOf(read("ZitroneApp.kt"))
        val construction = app.indexOf("WsSyntheticSocket(")
        assertTrue("the synthetic socket is no longer constructed in ZitroneApp", construction > 0)
        val constructionEnd = app.indexOf("decoySocket = syntheticSocket", construction)
        assertTrue("could not locate the end of the synthetic socket construction", constructionEnd > construction)
        val block = app.substring(construction, constructionEnd)
        for (sink in listOf("diag", "Diagnostics", "Log.", "println", "record(")) {
            assertTrue(
                "the synthetic socket must be constructed WITHOUT any diagnostics sink — its " +
                    "socket lifecycle on disk is durable evidence a second socket ran on this " +
                    "device; found `$sink` in the construction",
                !block.contains(sink),
            )
        }
    }

    @Test
    fun `the send-back is built through the reply entry point, never the covering one`() {
        val source = codeOf(read("decoy/DecoyInboundSession.kt"))
        assertTrue("the send-back must use buildReply", source.contains("builder.buildReply("))
        assertTrue(
            "buildReply exists so a reply is established-session shape and needs no registration " +
                "id — routing it through build() would reintroduce the durable-field question " +
                "R-U4-3 closes",
            !source.contains("builder.build("),
        )
    }

    // -- R-U4-4: ONE pressure meter, shared ------------------------------------------------------

    @Test
    fun `the send pairing and the synthetic side share one CoverPressure instance`() {
        val app = read("ZitroneApp.kt")
        val constructions = Regex("CoverPressure\\(").findAll(app).count()
        assertEquals(
            "Two CoverPressure instances over one socket are two independent meters each seeing " +
                "half the traffic, so neither trips when the pair of them should. U4's send-back " +
                "must consult the same instance the send pairing does.",
            1,
            constructions,
        )
        assertTrue(app.contains("val coverPressure = CoverPressure("))
        assertTrue("the pairing takes the shared meter", app.contains("pressure = coverPressure,"))
    }

    // -- the synthetic socket follows the transport ---------------------------------------------

    @Test
    fun `a transport swap re-points and redials the synthetic socket too`() {
        val app = read("ZitroneApp.kt")
        assertTrue(
            "the synthetic socket must get the new endpoints, or cover traffic keeps flowing over " +
                "the transport the user just switched away from",
            app.contains("live?.decoySocket?.updateTransport(httpClient, ws)"),
        )
        assertTrue(
            "and must actually be redialled onto them",
            app.contains("live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }"),
        )
    }

    @Test
    fun `the synthetic redial is not gated on the real socket's connection state`() {
        // U4 review round 1, Codex P1 / Grok F1: applyTransportLocked returned null whenever the
        // REAL socket was DISCONNECTED and applyTransport bailed on that null, so the SYNTHETIC
        // socket was never redialled — left connected on the endpoints the user had just left.
        //
        // RESTORED at round 4 (Grok). My round-3 edit deleted this test along with the one beside
        // it; the mutation sweep caught the OTHER deletion and I restored only that, then recorded
        // the loss as closed. It was not. Position is the property here, so a substring check that
        // both calls merely APPEAR cannot see the defect: re-nesting the synthetic redial inside
        // the real socket's gate keeps every token present and reinstates the P1.
        val app = codeOf(read("ZitroneApp.kt"))
        val realGate = app.indexOf("if (live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {")
        assertTrue("the real socket's redial gate is missing", realGate > 0)
        val redial = app.indexOf("live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }")
        assertTrue("the synthetic redial is missing", redial > 0)
        // The gate's closing brace: the synthetic redial must come after it, not inside it.
        val gateEnd = app.indexOf("\n        }", app.indexOf("live.apiClient.accessToken?.let(live.wsClient::connect)"))
        assertTrue("could not locate the end of the real socket's gate", gateEnd > realGate)
        assertTrue(
            "the synthetic redial must sit OUTSIDE the real socket's connection-state gate — a " +
                "down real socket redials itself through WsClient's backoff, but a live synthetic " +
                "socket left on the old transport keeps cover flowing where the user turned it off",
            redial > gateEnd,
        )
        // `redial > gateEnd` alone pins string geometry, not the property (U4 review round 5, both
        // lenses): a SECOND gate — or a bare `return` — inserted between the first gate's closing
        // brace and the redial keeps the position assertion green while re-gating the synthetic
        // redial on the real socket's state, which is exactly round 1's P1. So the segment between
        // them must be NOTHING but that closing brace: any code appearing here is code that can
        // condition the redial, and has to move or change this test consciously.
        assertTrue(
            "nothing but the gate's closing brace may sit between the real gate and the synthetic " +
                "redial — code here can re-gate the redial on the real socket's connection state",
            Regex("^\\s*\\}\\s*$").matches(app.substring(gateEnd, redial)),
        )
    }

    @Test
    fun `the synthetic socket's rate_limited arms the SYNTHETIC channel, not the shared one`() {
        // U4 review round 2, Grok F2 — and RESTORED after a round-3 edit deleted it along with the
        // test beside it. The mutation sweep is what noticed; nothing else would have, which is the
        // argument for sweeping after every round rather than only after the first.
        //
        // WsSyntheticSocketTest proves the ADAPTER routes rate_limited; this proves production hands
        // it to the right channel.
        val app = codeOf(read("ZitroneApp.kt"))
        assertTrue(
            "the synthetic socket must report rate_limited to the meter's SYNTHETIC channel",
            app.contains("onRateLimited = { coverPressureRef?.syntheticRateLimited() }"),
        )
        assertTrue(
            "routing it to relayRateLimited hands a relay a lever on the REAL send path's cover: " +
                "one frame on the synthetic connection would black out cover for every genuine " +
                "send for a full off-window, with the real account nowhere near its limit",
            !app.contains("coverPressureRef?.relayRateLimited()"),
        )
    }

    /**
     * U4's exemption from U3's disconnect-ownership guard, pinned at the type rather than by name.
     *
     * That guard lets `WsSyntheticSocket` call `ws.disconnect()` outside cover traffic's ownership,
     * because the synthetic socket carries no pairings and disconnecting it can split nothing. The
     * exemption is sound only if that class can never hold the REAL socket.
     *
     * **Three rounds of review defeated three lexical versions of this check** — rename the local,
     * alias it inside the file, then point the decoy binding itself at the real client so every
     * name downstream stayed honest while the object was wrong. The class now CONSTRUCTS its own
     * client and accepts none, so the property is enforced by the compiler. What is left to pin is
     * only that the injection point has not come back.
     */
    @Test
    fun `the synthetic socket wrapper cannot be handed a socket at all`() {
        val wrapper = codeOf(read("decoy/WsSyntheticSocket.kt"))
        val header = wrapper.substring(
            wrapper.indexOf("class WsSyntheticSocket("),
            wrapper.indexOf(") : DecoyInboundSession.SyntheticSocket"),
        )
        assertTrue(
            "WsSyntheticSocket must take NO WsClient parameter. Accepting one reopens the whole " +
                "class of evasion three review rounds spent on it: whatever a test asserts about " +
                "the argument, some binding upstream can be made to name the real socket.",
            !Regex(":\\s*WsClient(?![.\\w])").containsMatchIn(header),
        )
        // …and NOWHERE ELSE IN THE FILE EITHER (U4 review round 4, Grok). Checking only the class
        // header left a same-file helper — `internal fun disconnectClient(ws: WsClient) =
        // ws.disconnect()` — which any caller could invoke on the REAL socket: the call site has no
        // `disconnect()` token, and the only one that exists sits inside the exempted file. The
        // wrapper builds its own client and never needs a WsClient-typed anything, so the honest
        // rule is zero. (`WsClient.Listener` is a nested type, not a client, and is not matched.)
        assertTrue(
            "no WsClient-typed declaration may appear anywhere in WsSyntheticSocket — a helper " +
                "taking one inherits this file's disconnect-ownership exemption",
            !Regex(":\\s*WsClient(?![.\\w])").containsMatchIn(wrapper),
        )
        assertTrue(
            "and it must build its own, so the socket it disconnects is one it owns",
            wrapper.contains("private val ws = WsClient("),
        )
        assertEquals(
            "exactly one WsClient is constructed in that file",
            1,
            Regex("WsClient\\(").findAll(wrapper).count(),
        )
    }

    @Test
    fun `no OkHttp client in the app installs an observability hook`() {
        // U4 review round 6, Codex P3. Round 5 deleted the synthetic socket's diag parameter and
        // its comment claimed "no parameter through which a sink could be supplied" — an
        // overclaim: `httpClient` is such a parameter, because an OkHttpClient carrying an
        // EventListener or interceptor observes every connection it makes, durably if the hook
        // writes. BOTH sockets share the app's client, so a hook added for the real socket's
        // observability silently covers the synthetic connection too. No client builder in the
        // app installs one today; this pins that adding one is a conscious decision that has to
        // reckon with the cover socket, not a drive-by debugging aid.
        val hooks = listOf(
            "EventListener", "addInterceptor(", "addNetworkInterceptor(",
            ".eventListener(", "eventListenerFactory",
        )
        for ((name, source) in allMainSources()) {
            val code = codeOf(source)
            for (hook in hooks) {
                assertTrue(
                    "$name installs or names an OkHttp observability hook (`$hook`) — the app's " +
                        "shared client also carries the SYNTHETIC socket, so any hook observes " +
                        "cover traffic; if one is genuinely needed, it must exclude or reckon " +
                        "with the cover connection and update this test",
                    !code.contains(hook),
                )
            }
        }
    }

    @Test
    fun `the U4 files use no reflection at all`() {
        // U4 review round 5, Codex. Every guard above and the disconnect-ownership scan in
        // DecoySendPairingTest match SOURCE TOKENS — `disconnect()`, `::disconnect`, `: WsClient`.
        // Reflection needs none of them: a helper in the exempted file taking `Any` and resolving
        // `disconnect` via `javaClass.getMethod` disconnects the real socket with every lexical
        // guard green, and inherits this file's ownership exemption while doing it. Neither U4
        // file has any use for reflection, so the honest rule is zero — the lookup surface is
        // banned, which is what makes `Method.invoke` unreachable without ever matching `invoke`
        // (the listener's legitimate `onDeliver?.invoke` stays untouched).
        val lookups = listOf(
            "javaClass", "::class", "Class.forName", "getMethod", "getDeclaredMethod",
            "java.lang.reflect", "kotlin.reflect", "MethodHandles",
        )
        for (file in U4_FILES) {
            val source = codeOf(read(file))
            for (lookup in lookups) {
                assertTrue(
                    "$file must not use reflection: found `$lookup`. A reflective member lookup " +
                        "evades every source-token guard on the disconnect surface; if reflection " +
                        "is ever genuinely needed here, extend the guards first",
                    !source.contains(lookup),
                )
            }
        }
    }

    @Test
    fun `teardown of the synthetic side is bound to the pairing's, not left to a call site`() {
        val app = read("ZitroneApp.kt")
        assertTrue(
            "binding teardown is what makes 'the synthetic socket never outlives the session' " +
                "structural rather than a convention two call sites have to remember",
            app.contains("inbound?.bindTo(pairing) ?: pairing"),
        )
    }

    private fun allMainSources(): List<Pair<String, String>> =
        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .sortedBy { it.first }
            .toList()

    /** [source] with block and line comments removed, so a guard matches code and not prose. */
    private fun codeOf(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")

    private fun read(relative: String): String {
        val file = java.io.File(mainSourceRoot(), relative)
        assertTrue("$relative not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun mainSourceRoot(): java.io.File {
        val relative = "src/main/java/com/zitrone/app"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val U4_FILES = listOf("decoy/DecoyInboundSession.kt", "decoy/WsSyntheticSocket.kt")

        /**
         * Every one of these would make the synthetic side either a crypto participant or a durable
         * writer. They are matched as plain substrings against the shipped source.
         */
        val FORBIDDEN = listOf(
            "SignalProtocolManager",
            "runtime.mutate",
            "DecoySectionLock",
            "storeTokensForAccount",
            "VaultRuntime",
            ".decrypt(",
            "flushBeforeAck",
        )
    }
}
