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
            app.contains("live?.decoyWsClient?.updateTransport(httpClient, ws)"),
        )
        assertTrue(
            "and must actually be redialled onto them",
            app.contains("live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }"),
        )
    }

    /**
     * The load-bearing half of U4's exemption from U3's disconnect-ownership guard.
     *
     * That guard lets `WsSyntheticSocket` call `ws.disconnect()` outside cover traffic's ownership,
     * on the grounds that the synthetic socket carries no pairings. `ws` is a plain [WsClient],
     * though, so the argument holds only while the thing injected is the **decoy** client. If the
     * real one were ever passed here, U3's guard would be green over a disconnect that can split a
     * pairing — the exact evasion round 5 closed. So the injection is pinned.
     */
    @Test
    fun `the synthetic socket wrapper is only ever given the decoy client`() {
        val constructions = mutableListOf<String>()
        for ((name, source) in allMainSources()) {
            // …other than the class's own declaration, which is the thing being constructed.
            if (name == "WsSyntheticSocket.kt") continue
            Regex("WsSyntheticSocket\\(([^)]*)\\)").findAll(codeOf(source)).forEach {
                // The FIRST argument is the socket; later ones (the rate-limit hook) do not bear
                // on which WsClient is wrapped.
                constructions += "$name: ${it.groupValues[1].split(",").first().trim()}"
            }
        }
        assertEquals(
            "exactly one place may build the synthetic socket wrapper; found $constructions",
            1,
            constructions.size,
        )
        assertEquals(
            "the wrapper must be handed the DECOY WsClient. Handing it the real one would exempt a " +
                "disconnect of the real socket from U3's ownership guard.",
            "ZitroneApp.kt: syntheticWs",
            constructions.single(),
        )
        // …AND that name must come from the decoy client, not merely BE that name (U4 review round
        // 1, Codex P3). The assertion above pins an identifier SPELLING: rebinding `syntheticWs` to
        // the real `wsClient` anywhere in scope would keep it green while `WsSyntheticSocket` — and
        // so its exemption from U3's disconnect-ownership guard — silently wrapped the real socket.
        // Pinning the binding closes the gap between "is called syntheticWs" and "is the decoy
        // socket". Still lexical, and the honest limit of that is stated in the class kdoc.
        val app = codeOf(read("ZitroneApp.kt"))
        assertTrue(
            "`syntheticWs` must be bound by the synthetic socket's own let-block",
            app.contains("syntheticSocket?.let { syntheticWs ->"),
        )
        assertEquals(
            "`syntheticWs` is bound in exactly one place; a second binding could shadow it with " +
                "the real socket and both assertions above would still pass",
            1,
            Regex("syntheticWs\\s*->").findAll(app).count(),
        )
        // …and inside the wrapper, `ws` must have exactly ONE binding (U4 review round 2, Codex
        // P3). U3's disconnect-ownership guard exempts every `ws.disconnect()` in that file on the
        // strength of a receiver SPELLING; code that obtained the real client and aliased it to a
        // second local `ws` would inherit the exemption and could disconnect the real socket
        // outside cover traffic's ownership with every guard green. One binding, and it is the
        // constructor property, closes that specific evasion.
        val wrapper = codeOf(read("decoy/WsSyntheticSocket.kt"))
        assertEquals(
            "`ws` must be bound exactly once in WsSyntheticSocket, as the constructor property",
            1,
            Regex("\\b(?:val|var)\\s+ws\\b").findAll(wrapper).count(),
        )
        assertTrue(
            "and that one binding is the constructor property",
            wrapper.contains("private val ws: WsClient"),
        )
        assertEquals(
            "WsSyntheticSocket must declare exactly one thing OF TYPE WsClient — that property. A " +
                "second WsClient-typed binding here is a second candidate receiver for the " +
                "disconnect exemption. (`WsClient.Listener` is a nested type, not a receiver, and " +
                "is deliberately not matched.)",
            1,
            Regex(": WsClient(?![.\\w])").findAll(wrapper).count(),
        )
    }

    @Test
    fun `the synthetic redial is not gated on the real socket's connection state`() {
        // U4 review round 1, Codex P1. applyTransportLocked used to return null when the REAL
        // socket was DISCONNECTED, and applyTransport bailed out on that null — so a session whose
        // real socket happened to be down never redialled the SYNTHETIC one, leaving it connected
        // on the endpoints the user had just switched away from. The two sockets now decide
        // separately.
        val app = codeOf(read("ZitroneApp.kt"))
        assertTrue(
            "applyTransportLocked must return the live session regardless of the real socket's " +
                "state; the per-socket decision belongs to applyTransport",
            !app.contains("it.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED\n        }"),
        )
        val redial = app.indexOf("live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }")
        val realGate = app.indexOf("if (live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED) {")
        assertTrue("the real socket's redial must be the gated one", realGate > 0)
        assertTrue("the synthetic redial must exist", redial > 0)
        assertTrue(
            "the synthetic redial must sit OUTSIDE the real socket's state gate",
            redial > app.indexOf("}", app.indexOf("live.apiClient.accessToken?.let(live.wsClient::connect)")),
        )
    }

    @Test
    fun `the synthetic socket's rate_limited arms the SYNTHETIC channel, not the shared one`() {
        // U4 review round 1, Grok F4. WsSyntheticSocketTest proves the ADAPTER routes it; this
        // proves production actually hands it somewhere. Without the wiring the meter sees only the
        // real socket's rate_limited, so the relay can be throttling the account that exists solely
        // to carry cover traffic while this side keeps emitting into the refusal.
        val app = codeOf(read("ZitroneApp.kt"))
        assertTrue(
            "the synthetic socket must report rate_limited to the meter's SYNTHETIC channel",
            app.contains("WsSyntheticSocket(syntheticWs, coverPressure::syntheticRateLimited)"),
        )
        assertTrue(
            "routing it to relayRateLimited hands a relay a lever on the REAL send path's cover: " +
                "one frame on the synthetic connection would black out cover for every genuine " +
                "send for a full off-window, with the real account nowhere near its limit",
            !app.contains("WsSyntheticSocket(syntheticWs, coverPressure::relayRateLimited)"),
        )
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
