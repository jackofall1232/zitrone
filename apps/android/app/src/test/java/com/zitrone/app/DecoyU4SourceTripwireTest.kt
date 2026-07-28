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
            app.contains("live?.decoySocket?.updateTransport(httpClient, ws)"),
        )
        assertTrue(
            "and must actually be redialled onto them",
            app.contains("live.decoyInbound?.let { session -> scope.launch { session.reconnect() } }"),
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
